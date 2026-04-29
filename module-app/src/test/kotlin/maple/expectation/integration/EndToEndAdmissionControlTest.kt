package maple.expectation.integration

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.application.service.expectation.cache.ExpectationCacheCoordinator
import maple.expectation.core.dto.v4.EquipmentExpectationResponseV4
import maple.expectation.infrastructure.admission.GlobalAdmissionControl
import maple.expectation.infrastructure.batch.DedupeMicroBatchWriter
import maple.expectation.infrastructure.buffer.ExpectationWriteTask
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.config.GlobalAdmissionProperties
import maple.expectation.infrastructure.config.MicroBatchWriterProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.ExpectationBatchRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cache.Cache
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-End Integration Test for Issue #617: Global Admission Control + Micro-Batching
 *
 * <h3>Acceptance Criteria</h3>
 * <ul>
 *   <li>✓ Test simulates 100 concurrent unique key requests and verifies all complete successfully (no 429)</li>
 *   <li>✓ Test simulates 1000 concurrent unique key requests and verifies in-flight never exceeds maxInFlight=100</li>
 *   <li>✓ Test simulates backfill + operational concurrent load and verifies operational p99 latency < 500ms</li>
 *   <li>✓ Verify GlobalAdmissionControl metrics (admission_control_in_flight, admission_control_queue_depth)</li>
 *   <li>✓ Verify DedupeMicroBatchWriter metrics (micro_batch_dedupe, micro_batch_flush)</li>
 * </ul>
 *
 * <h3>Test Scenarios</h3>
 * <ol>
 *   <li>Fan-out scenario: 100 concurrent unique keys → all complete, no rejections</li>
 *   <li>Stampede scenario: 1000 concurrent unique keys → maxInFlight never exceeded</li>
 *   <li>Operational vs Backfill: Operational requests maintain p99 < 500ms under backfill load</li>
 *   <li>Micro-batch dedupe: Duplicate keys in buffer → latest-wins behavior</li>
 * </ol>
 */
@Tag("integration")
@DisplayName("US-005: End-to-End Admission Control + Micro-Batch Test")
@SpringBootTest(classes = [])
@ActiveProfiles("test")
class EndToEndAdmissionControlTest {

    @Mock
    private lateinit var tieredCacheManager: TieredCacheManager

    @Mock
    private lateinit var mockCache: Cache

    @Mock
    private lateinit var batchRepository: ExpectationBatchRepository

    @Mock
    private lateinit var executor: LogicExecutor

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var admissionControl: GlobalAdmissionControl
    private lateinit var microBatchWriter: DedupeMicroBatchWriter
    private lateinit var cacheCoordinator: ExpectationCacheCoordinator

    private val closables = AutoCloseableList()

    // Test configuration
    private val maxInFlight = 100 // Production-like setting
    private val operationalExecutorPool = Executors.newFixedThreadPool(16)
    private val backfillExecutorPool = Executors.newFixedThreadPool(8)

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this).also { closables.add(it) }

        meterRegistry = SimpleMeterRegistry()

        // Mock cache behavior
        Mockito.`when`(tieredCacheManager.getCache(Mockito.anyString())).thenReturn(mockCache)
        Mockito.`when`(tieredCacheManager.meterRegistry).thenReturn(meterRegistry)
        Mockito.`when`(mockCache.get(Mockito.any())).thenReturn(null) // Always cache miss for testing

        // Mock executor behavior
        val testExecutor = Executors.newFixedThreadPool(20)
        closables.add { testExecutor.shutdown() }

        // Create real LogicExecutor wrapper
        executor = object : LogicExecutor {
            override fun <T> execute(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                context: TaskContext,
            ): T = testExecutor.submit(task::get).get()

            override fun <T> executeOrDefault(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                defaultValue: T,
                context: TaskContext,
            ): T = try {
                execute(task, context)
            } catch (e: Exception) {
                defaultValue
            }

            override fun <T> executeWithTranslation(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                customTranslator: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator,
                context: TaskContext,
            ): T = execute(task, context)

            override fun <T> executeWithFallback(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                fallback: (Throwable) -> T,
                context: TaskContext,
            ): T = try {
                execute(task, context)
            } catch (e: Throwable) {
                fallback(e)
            }

            override fun <T> executeWithFallback(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                fallback: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator,
                context: TaskContext,
            ): T = execute(task, context)

            override fun <T> executeOrCatch(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                recovery: (Throwable) -> T,
                context: TaskContext,
            ): T = try {
                execute(task, context)
            } catch (e: Throwable) {
                recovery(e)
            }

            override fun <T> executeOrCatch(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                recovery: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator,
                context: TaskContext,
            ): T = execute(task, context)

            override fun executeVoid(
                task: maple.expectation.infrastructure.executor.function.ThrowingRunnable,
                context: TaskContext,
            ) {
                testExecutor.submit { task.run() }.get()
            }

            override fun executeVoidJava(task: Runnable, context: TaskContext) {
                testExecutor.submit(task).get()
            }

            override fun <T> executeWithFinally(
                task: maple.expectation.common.function.ThrowingSupplier<T>,
                finallyBlock: Runnable,
                context: TaskContext,
            ): T = try {
                execute(task, context)
            } finally {
                finallyBlock.run()
            }
        }

        // Create GlobalAdmissionControl
        val admissionProperties = GlobalAdmissionProperties(
            maxInFlight = maxInFlight,
            queueTimeoutMs = 5000,
            maxQueueSize = 1000,
        )
        admissionControl = GlobalAdmissionControl(admissionProperties, meterRegistry, executor, testExecutor)

        // Create DedupeMicroBatchWriter
        val batchProperties = MicroBatchWriterProperties(
            flushSize = 500,
            flushIntervalMs = 50,
        )
        microBatchWriter = DedupeMicroBatchWriter(
            batchProperties,
            batchRepository,
            meterRegistry,
            executor,
        )
        closables.add { microBatchWriter.shutdown() }

        // Create ExpectationCacheCoordinator with admission control
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
            admissionControl,
            compressionService,
            valueConverter,
            responseBuilder,
        )
    }

    @AfterEach
    fun cleanup() {
        closables.close()
        operationalExecutorPool.shutdown()
        backfillExecutorPool.shutdown()
        meterRegistry.clear()
    }

    @Test
    @DisplayName("AC-1: 100 concurrent unique keys - all complete successfully (no 429)")
    fun `AC1 - 100 concurrent unique keys complete successfully`() {
        val completedCount = AtomicInteger(0)
        val requestCount = 100
        val testLatch = CountDownLatch(1)

        // Submit 100 unique keys concurrently
        val futures = (1..requestCount).map { i ->
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                try {
                    val calculator = Callable<EquipmentExpectationResponseV4> {
                        testLatch.await() // All wait at same barrier
                        Thread.sleep(10) // Simulate quick calculation
                        completedCount.incrementAndGet()
                        createMockResponse("user-$i")
                    }
                    cacheCoordinator.getOrCalculate("user-$i", false, calculator)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw e
                }
            }
        }

        // Let admission control acquire permits
        await().atMost(500, TimeUnit.MILLISECONDS).until {
            val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
            val currentInFlight = inFlightGauge?.value() ?: 0.0
            currentInFlight > 0
        }
        testLatch.countDown() // Release all requests

        // Wait for all to complete
        futures.forEach { future ->
            val result = future.get(30, TimeUnit.SECONDS)
            assertThat(result).isNotNull
        }

        assertThat(completedCount.get()).isEqualTo(requestCount)

        // Verify no rejections
        val rejectedCounter = meterRegistry.find("admission_control.rejected").counter()
        val rejectionCount = rejectedCounter?.count() ?: 0.0
        assertThat(rejectionCount).isEqualTo(0.0)

        // Verify all requests completed
        val timeoutCounter = meterRegistry.find("admission_control.queue.timeout").counter()
        val timeoutCount = timeoutCounter?.count() ?: 0.0
        assertThat(timeoutCount).isEqualTo(0.0)
    }

    @Test
    @DisplayName("AC-2: 1000 concurrent unique keys - in-flight never exceeds maxInFlight=100")
    fun `AC2 - 1000 concurrent unique keys respect maxInFlight`() {
        val processedCount = AtomicInteger(0)
        val requestCount = 1000
        val testLatch = CountDownLatch(1)
        val maxObservedInFlight = AtomicInteger(0)

        // Start a background thread to monitor in-flight gauge
        val monitorThread = Thread {
            while (testLatch.count > 0) {
                val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
                val currentInFlight = inFlightGauge?.value()?.toInt() ?: 0
                maxObservedInFlight.updateAndGet { maxOf(it, currentInFlight) }
                Thread.sleep(10) // Simulate monitoring interval (KEEP)
            }
        }
        monitorThread.start()

        // Submit 1000 unique keys
        val futures = (1..requestCount).map { i ->
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                val calculator = Callable<EquipmentExpectationResponseV4> {
                    testLatch.await() // All wait at same barrier
                    Thread.sleep(10)
                    processedCount.incrementAndGet()
                    createMockResponse("user-$i")
                }
                cacheCoordinator.getOrCalculate("user-$i", false, calculator)
            }
        }

        // Let admission control stabilize
        await().atMost(2, TimeUnit.SECONDS).until {
            val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
            val currentInFlight = inFlightGauge?.value()?.toInt() ?: 0
            currentInFlight > 0
        }
        testLatch.countDown() // Release all

        // Wait for all to complete
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        monitorThread.join()

        assertThat(processedCount.get()).isEqualTo(requestCount)

        // Verify maxInFlight never exceeded
        assertThat(maxObservedInFlight.get()).isLessThanOrEqualTo(maxInFlight)

        // Verify final in-flight is 0 (all completed)
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        val finalInFlight = inFlightGauge?.value()?.toInt() ?: 0
        assertThat(finalInFlight).isEqualTo(0)
    }

    @Test
    @DisplayName("AC-3: Backfill + Operational concurrent load - operational p99 < 500ms")
    fun `AC3 - operational p99 latency under backfill load`() {
        val operationalLatencies = mutableListOf<Long>()
        val operationalCount = 100
        val backfillCount = 500
        val operationalLatch = CountDownLatch(operationalCount)

        // Operational requests (user-facing, low latency required)
        val operationalFutures = (1..operationalCount).map { i ->
            operationalExecutorPool.submit<EquipmentExpectationResponseV4> {
                val startTime = System.nanoTime()
                val calculator = Callable<EquipmentExpectationResponseV4> {
                    Thread.sleep(5) // Fast calculation (5ms)
                    createMockResponse("operational-user-$i")
                }
                val result = cacheCoordinator.getOrCalculate("operational-$i", false, calculator)
                val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                synchronized(operationalLatencies) {
                    operationalLatencies.add(latency)
                }
                operationalLatch.countDown()
                result
            }
        }

        // Backfill requests (background, can be slower)
        val backfillFutures = (1..backfillCount).map { i ->
            backfillExecutorPool.submit<Unit> {
                val task = createWriteTask(characterId = i.toLong(), presetNo = 1)
                microBatchWriter.offer(task)
                Thread.sleep(10) // Simulate backfill pacing
            }
        }

        // Wait for all operational requests to complete
        operationalLatch.await(30, TimeUnit.SECONDS)
        operationalFutures.forEach { it.get(30, TimeUnit.SECONDS) }

        // Calculate p99 latency
        val sortedLatencies = operationalLatencies.sorted()
        val p99Index = (sortedLatencies.size * 0.99).toInt()
        val p99Latency = sortedLatencies[p99Index]

        assertThat(p99Latency).isLessThan(500) // P99 must be < 500ms

        // Wait for backfill to complete
        backfillFutures.forEach { it.get(60, TimeUnit.SECONDS) }

        // Verify micro-batch metrics
        val dedupeCounter = meterRegistry.find("micro_batch_dedupe").counter()
        val flushCounter = meterRegistry.find("micro_batch_flush").counter()

        assertThat(dedupeCounter).isNotNull
        assertThat(flushCounter).isNotNull
    }

    @Test
    @DisplayName("AC-4: Admission control metrics are tracked correctly")
    fun `AC4 - admission control metrics tracked`() {
        val requestCount = 50

        // Submit requests
        val futures = (1..requestCount).map { i ->
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                val calculator = Callable<EquipmentExpectationResponseV4> {
                    Thread.sleep(10)
                    createMockResponse("metrics-user-$i")
                }
                cacheCoordinator.getOrCalculate("metrics-$i", false, calculator)
            }
        }

        futures.forEach { it.get(30, TimeUnit.SECONDS) }

        // Verify admission control metrics exist
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        assertThat(inFlightGauge).isNotNull

        val queueDepthGauge = meterRegistry.get("admission_control.queue_depth").gauge()
        assertThat(queueDepthGauge).isNotNull

        val timeoutCounter = meterRegistry.get("admission_control.queue.timeout").counter()
        assertThat(timeoutCounter).isNotNull

        val queueFullCounter = meterRegistry.get("admission_control.queue.full").counter()
        assertThat(queueFullCounter).isNotNull
    }

    @Test
    @DisplayName("AC-5: Micro-batch dedupe metrics are tracked correctly")
    fun `AC5 - micro batch dedupe metrics tracked`() {
        val task1 = createWriteTask(characterId = 1L, presetNo = 1, totalCost = 1000.0)
        val task2 = createWriteTask(characterId = 1L, presetNo = 1, totalCost = 2000.0) // Same key

        microBatchWriter.offer(task1)
        microBatchWriter.offer(task2)

        // Wait for potential flush
        await().atMost(1, TimeUnit.SECONDS).until {
            val dedupeCounter = meterRegistry.find("micro_batch_dedupe").counter()
            (dedupeCounter?.count() ?: 0.0) > 0.0
        }

        // Verify dedupe metric
        val dedupeCounter = meterRegistry.find("micro_batch_dedupe").counter()
        assertThat(dedupeCounter).isNotNull
        assertThat(dedupeCounter?.count()).isGreaterThan(0.0)

        // Verify flush metrics
        val flushCounter = meterRegistry.find("micro_batch_flush").counter()
        assertThat(flushCounter).isNotNull

        val bufferSizeGauge = meterRegistry.get("micro_batch_buffer_size").gauge()
        assertThat(bufferSizeGauge).isNotNull
    }

    @Test
    @DisplayName("AC-6: Stampede scenario - same key concurrent requests")
    fun `AC6 - stampede scenario same key concurrent requests`() {
        val calculationCount = AtomicInteger(0)
        val responseCount = AtomicInteger(0)
        val requestCount = 100
        val sameKey = "stampede-user"

        // Submit 100 concurrent requests with same key
        val futures = (1..requestCount).map {
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                val calculator = Callable<EquipmentExpectationResponseV4> {
                    Thread.sleep(50) // Slow calculation
                    calculationCount.incrementAndGet()
                    createMockResponse(sameKey)
                }
                val result = cacheCoordinator.getOrCalculate(sameKey, false, calculator)
                responseCount.incrementAndGet()
                result
            }
        }

        futures.forEach { it.get(30, TimeUnit.SECONDS) }

        // Single-flight: should only calculate once (cache hit for rest)
        // Note: Due to admission control + single-flight, only 1 calculation should occur
        assertThat(calculationCount.get()).isLessThanOrEqualTo(2) // Allow small race window
        assertThat(responseCount.get()).isEqualTo(requestCount)

        // Verify no rejections
        val rejectedCounter = meterRegistry.find("admission_control.rejected").counter()
        val rejectionCount = rejectedCounter?.count() ?: 0.0
        assertThat(rejectionCount).isEqualTo(0.0)
    }

    @Test
    @DisplayName("AC-7: Queue depth metric reflects waiting requests")
    fun `AC7 - queue depth metric reflects waiting requests`() {
        val testLatch = CountDownLatch(1)
        val requestCount = 200 // More than maxInFlight=100

        // Submit requests that will wait
        val futures = (1..requestCount).map { i ->
            Executors.newCachedThreadPool().submit<EquipmentExpectationResponseV4> {
                val calculator = Callable<EquipmentExpectationResponseV4> {
                    testLatch.await()
                    Thread.sleep(10)
                    createMockResponse("queue-user-$i")
                }
                cacheCoordinator.getOrCalculate("queue-$i", false, calculator)
            }
        }

        // Let admission control stabilize
        await().atMost(1, TimeUnit.SECONDS).until {
            val queueDepthGauge = meterRegistry.get("admission_control.queue_depth").gauge()
            val queueDepth = queueDepthGauge?.value()?.toInt() ?: 0
            queueDepth > 0
        }

        // Verify queue depth is non-zero (requests waiting)
        val queueDepthGauge = meterRegistry.get("admission_control.queue_depth").gauge()
        val queueDepth = queueDepthGauge?.value()?.toInt() ?: 0
        assertThat(queueDepth).isGreaterThan(0)

        testLatch.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
    }

    private fun createMockResponse(userIgn: String): EquipmentExpectationResponseV4 {
        val breakdown = EquipmentExpectationResponseV4.CostBreakdownDto.empty()

        return EquipmentExpectationResponseV4.builder()
            .userIgn(userIgn)
            .calculatedAt(LocalDateTime.now())
            .fromCache(false)
            .totalExpectedCost(0.0)
            .totalCostText("0 mesos")
            .totalCostBreakdown(breakdown)
            .maxPresetNo(0)
            .presets(mutableListOf())
            .build()
    }

    private fun createWriteTask(
        characterId: Long,
        presetNo: Int,
        totalCost: Double = 1000.0,
    ): ExpectationWriteTask = ExpectationWriteTask(
        characterId = characterId,
        presetNo = presetNo,
        totalExpectedCost = totalCost,
        blackCubeCost = totalCost * 0.3,
        redCubeCost = totalCost * 0.2,
        additionalCubeCost = totalCost * 0.1,
        starforceCost = totalCost * 0.4,
        createdAt = LocalDateTime.now(),
    )

    private class AutoCloseableList :
        ArrayList<AutoCloseable>(),
        AutoCloseable {
        override fun close() {
            forEach { it.close() }
        }
    }
}
