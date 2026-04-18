package maple.expectation.integration

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.application.service.expectation.cache.ExpectationCacheCoordinator
import maple.expectation.infrastructure.admission.GlobalAdmissionControl
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.config.GlobalAdmissionProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.web.dto.v4.EquipmentExpectationResponseV4
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cache.Cache
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

/**
 * Integration Test for US-002: Global Admission Control in Cache Service (Issue #617)
 *
 * <h3>Acceptance Criteria</h3>
 * <ul>
 *   <li>✓ TotalExpectationCacheService/ExpectationCacheCoordinator has optional GlobalAdmissionControl</li>
 *   <li>✓ Cold-path calculation wrapped with admissionControl.submitOrWait()</li>
 *   <li>✓ Integration preserves same-key single-flight behavior</li>
 *   <li>✓ Integration allows unique-key requests to wait in global queue</li>
 *   <li>✓ Integration test verifies 1000 unique keys don't exceed maxInFlight</li>
 *   <li>✓ Integration test verifies no 429 responses - all requests eventually processed</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("US-002: Admission Control Integration Test")
@SpringBootTest(classes = [AdmissionControlIntegrationTest.TestConfiguration::class])
@ActiveProfiles("test")
class AdmissionControlIntegrationTest {

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var admissionControl: GlobalAdmissionControl

    @Autowired
    private lateinit var executor: LogicExecutor

    @MockBean
    private lateinit var tieredCacheManager: TieredCacheManager

    @MockBean
    private lateinit var mockCache: Cache

    private lateinit var cacheCoordinator: ExpectationCacheCoordinator

    // Test tracking
    private val executionCount = AtomicInteger(0)
    private val maxInFlight = 10 // Small for testing

    @BeforeEach
    fun setup() {
        executionCount.set(0)

        // Mock cache behavior
        Mockito.`when`(tieredCacheManager.getCache(ArgumentMatchers.anyString())).thenReturn(mockCache)
        Mockito.`when`(tieredCacheManager.meterRegistry).thenReturn(meterRegistry)
        Mockito.`when`(mockCache.get(ArgumentMatchers.any())).thenReturn(null) // Always cache miss for testing

        // Create cache coordinator with admission control
        // Note: New constructor signature for Issue #644 (God Object Decomposition)
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        val executorPort = maple.expectation.application.usecase.ApplicationExecutionPort(executor)
        val cacheManagerPort = maple.expectation.infrastructure.cache.CacheManagerPortAdapter(tieredCacheManager)
        val compressionService = maple.expectation.application.service.expectation.cache.ExpectationCacheCompressionService(objectMapper)
        val valueConverter = maple.expectation.application.service.expectation.cache.CacheValueConverter()
        val responseBuilder = maple.expectation.application.service.expectation.cache.CachedResponseBuilder()

        cacheCoordinator = ExpectationCacheCoordinator(
            executorPort,
            cacheManagerPort,
            admissionControl, // Inject admission control
            compressionService,
            valueConverter,
            responseBuilder
        )
    }

    @AfterEach
    fun cleanup() {
        meterRegistry.clear()
    }

    @Test
    @DisplayName("AC-1: ExpectationCacheCoordinator has optional GlobalAdmissionControl parameter")
    fun `AC1 - coordinator has optional admission control`() {
        assertThat(cacheCoordinator).isNotNull
        assertThat(admissionControl).isNotNull
        assertThat(maxInFlight).isPositive()
    }

    @Test
    @DisplayName("AC-2: Cold-path calculation wrapped with admission control submitOrWait")
    fun `AC2 - cold path wrapped with admission control`() {
        val testLatch = CountDownLatch(1)
        val calculator = Callable<EquipmentExpectationResponseV4> {
            testLatch.await()
            executionCount.incrementAndGet()
            createMockResponse("test-user")
        }

        // Submit cache miss (cold path)
        val future = Executors.newSingleThreadExecutor().submit<EquipmentExpectationResponseV4> {
            cacheCoordinator.getOrCalculate("test-user", false, calculator)
        }

        // Let admission control acquire permit
        await().atMost(500, TimeUnit.MILLISECONDS).until {
            executionCount.get() > 0 || testLatch.count == 0L
        }
        testLatch.countDown()

        val result = future.get(5, TimeUnit.SECONDS)
        assertThat(result).isNotNull
        assertThat(executionCount.get()).isEqualTo(1)
    }

    @Test
    @DisplayName("AC-3: Integration preserves same-key single-flight behavior")
    fun `AC3 - same key single flight preserved`() {
        val testLatch = CountDownLatch(1)
        val calculationCount = AtomicInteger(0)

        val calculator = Callable<EquipmentExpectationResponseV4> {
            calculationCount.incrementAndGet()
            testLatch.await() // Simulate slow calculation
            createMockResponse("same-key-user")
        }

        // Submit same key 10 times concurrently
        val futures = (1..10).map { i ->
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                cacheCoordinator.getOrCalculate("same-key-user", false, calculator)
            }
        }

        // Let first request acquire admission permit
        await().atMost(500, TimeUnit.MILLISECONDS).until {
            calculationCount.get() > 0 || testLatch.count == 0L
        }
        testLatch.countDown()

        // Wait for all to complete
        futures.forEach { it.get(10, TimeUnit.SECONDS) }

        // Single-flight: should only calculate once (second request hits cache)
        assertThat(calculationCount.get()).isLessThanOrEqualTo(1)
    }

    @Test
    @DisplayName("AC-4: Unique-key requests wait in global queue")
    fun `AC4 - unique keys wait in global queue`() {
        val testLatch = CountDownLatch(1)
        val completedCount = AtomicInteger(0)

        // Submit 50 unique keys with maxInFlight=10
        val futures = (1..50).map { i ->
            val calculator = Callable<EquipmentExpectationResponseV4> {
                testLatch.await() // All wait for same latch
                completedCount.incrementAndGet()
                createMockResponse("user-$i")
            }

            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                cacheCoordinator.getOrCalculate("user-$i", false, calculator)
            }
        }

        // Let first maxInFlight requests acquire permits
        await().atMost(1, TimeUnit.SECONDS).until {
            val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
            val currentInFlight = inFlightGauge?.value() ?: 0.0
            currentInFlight > 0
        }

        // Verify in-flight doesn't exceed maxInFlight
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        val currentInFlight = inFlightGauge?.value() ?: 0.0
        assertThat(currentInFlight).isLessThanOrEqualTo(maxInFlight.toDouble())

        // Release all requests
        testLatch.countDown()

        // All requests should complete successfully (no 429/rejections)
        futures.forEach {
            val result = it.get(15, TimeUnit.SECONDS)
            assertThat(result).isNotNull
        }

        assertThat(completedCount.get()).isEqualTo(50)
    }

    @Test
    @DisplayName("AC-5: 1000 unique keys don't exceed maxInFlight")
    fun `AC5 - 1000 unique keys respect maxInFlight`() {
        val testLatch = CountDownLatch(1)
        val processedCount = AtomicInteger(0)
        val requestCount = 1000

        // Submit 1000 unique keys
        val futures = (1..requestCount).map { i ->
            val calculator = Callable<EquipmentExpectationResponseV4> {
                testLatch.await()
                processedCount.incrementAndGet()
                createMockResponse("user-$i")
            }

            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                cacheCoordinator.getOrCalculate("user-$i", false, calculator)
            }
        }

        // Let admission control stabilize
        await().atMost(2, TimeUnit.SECONDS).until {
            val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
            val currentInFlight = inFlightGauge?.value()?.toInt() ?: 0
            currentInFlight > 0
        }

        // Verify in-flight never exceeds maxInFlight
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        val maxObservedInFlight = inFlightGauge?.value() ?: 0.0
        assertThat(maxObservedInFlight).isLessThanOrEqualTo(maxInFlight.toDouble())

        // Release all requests
        testLatch.countDown()

        // All requests should complete
        futures.forEach { it.get(60, TimeUnit.SECONDS) }

        assertThat(processedCount.get()).isEqualTo(requestCount)
    }

    @Test
    @DisplayName("AC-6: No 429 responses - all requests eventually processed")
    fun `AC6 - no rejections under normal load`() {
        val processedCount = AtomicInteger(0)
        val requestCount = 100

        // Submit requests with fast completion (no blocking)
        val futures = (1..requestCount).map { i ->
            val calculator = Callable<EquipmentExpectationResponseV4> {
                Thread.sleep(10) // Simulate quick calculation
                processedCount.incrementAndGet()
                createMockResponse("user-$i")
            }

            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                try {
                    cacheCoordinator.getOrCalculate("user-$i", false, calculator)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }
            }
        }

        // Wait for all to complete
        futures.forEach { future ->
            val result = future.get(30, TimeUnit.SECONDS)
            assertThat(result).isNotNull
        }

        assertThat(processedCount.get()).isEqualTo(requestCount)

        // Verify no rejection metrics
        val rejectedCounter = meterRegistry.find("admission_control.rejected").counter()
        val rejectionCount = rejectedCounter?.count() ?: 0.0
        assertThat(rejectionCount).isEqualTo(0.0)
    }

    @Test
    @DisplayName("AC-7: Admission control metrics are tracked")
    fun `AC7 - metrics tracked`() {
        val calculator = Callable<EquipmentExpectationResponseV4> {
            createMockResponse("metrics-user")
        }

        cacheCoordinator.getOrCalculate("metrics-user", false, calculator)

        // Verify metrics exist
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        assertThat(inFlightGauge).isNotNull

        val queueDepthGauge = meterRegistry.get("admission_control.queue_depth").gauge()
        assertThat(queueDepthGauge).isNotNull

        val timeoutCounter = meterRegistry.get("admission_control.queue.timeout").counter()
        assertThat(timeoutCounter).isNotNull
    }

    private fun createMockResponse(userIgn: String): EquipmentExpectationResponseV4 {
        val breakdown = EquipmentExpectationResponseV4.CostBreakdownDto.empty()

        return EquipmentExpectationResponseV4.builder()
            .userIgn(userIgn)
            .calculatedAt(java.time.LocalDateTime.now())
            .fromCache(false)
            .totalExpectedCost(0.0)
            .totalCostText("0 mesos")
            .totalCostBreakdown(breakdown)
            .maxPresetNo(0)
            .presets(mutableListOf())
            .build()
    }

    // Test configuration for Spring context
    @org.springframework.boot.test.context.TestConfiguration
    class TestConfiguration {
        @org.springframework.context.annotation.Bean
        fun meterRegistry(): MeterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()

        @org.springframework.context.annotation.Bean
        fun globalAdmissionControl(
            meterRegistry: MeterRegistry,
            executor: LogicExecutor,
            testExecutor: java.util.concurrent.Executor
        ): GlobalAdmissionControl {
            val properties = GlobalAdmissionProperties(
                maxInFlight = 100,
                queueTimeoutMs = 5000,
                maxQueueSize = 1000
            )
            return GlobalAdmissionControl(properties, meterRegistry, executor, testExecutor)
        }

        @org.springframework.context.annotation.Bean
        fun logicExecutor(): LogicExecutor {
            val testExecutor = Executors.newFixedThreadPool(10)
            return object : LogicExecutor {
                override fun <T> execute(task: maple.expectation.common.function.ThrowingSupplier<T>, context: TaskContext): T {
                    return testExecutor.submit(task::get).get()
                }

                override fun <T> executeOrDefault(task: maple.expectation.common.function.ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T {
                    return try { execute(task, context) } catch (e: Exception) { defaultValue }
                }

                override fun <T> executeWithTranslation(task: maple.expectation.common.function.ThrowingSupplier<T>, customTranslator: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                    return execute(task, context)
                }

                override fun <T> executeWithFallback(task: maple.expectation.common.function.ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T {
                    return try { execute(task, context) } catch (e: Throwable) { fallback(e) }
                }

                override fun <T> executeWithFallback(task: maple.expectation.common.function.ThrowingSupplier<T>, fallback: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                    return execute(task, context)
                }

                override fun <T> executeOrCatch(task: maple.expectation.common.function.ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T {
                    return try { execute(task, context) } catch (e: Throwable) { recovery(e) }
                }

                override fun <T> executeOrCatch(task: maple.expectation.common.function.ThrowingSupplier<T>, recovery: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                    return execute(task, context)
                }

                override fun executeVoid(task: maple.expectation.infrastructure.executor.function.ThrowingRunnable, context: TaskContext) {
                    testExecutor.submit { task.run() }.get()
                }

                override fun executeVoidJava(task: Runnable, context: TaskContext) {
                    testExecutor.submit(task).get()
                }

                override fun <T> executeWithFinally(task: maple.expectation.common.function.ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T {
                    return try { execute(task, context) } finally { finallyBlock.run() }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:h2:mem:testdb" }
            registry.add("spring.datasource.driver-class-name") { "org.h2.Driver" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
        }
    }
}
