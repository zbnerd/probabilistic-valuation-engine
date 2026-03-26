package maple.expectation.infrastructure.admission

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.config.GlobalAdmissionProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Tag("unit")
@DisplayName("GlobalAdmissionControl Tests")
class GlobalAdmissionControlTest {

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var properties: GlobalAdmissionProperties
    private lateinit var admissionControl: GlobalAdmissionControl
    private lateinit var executor: LogicExecutor
    private lateinit var workerExecutor: Executor

    @BeforeEach
    fun setup() {
        meterRegistry = SimpleMeterRegistry()
        properties = GlobalAdmissionProperties(
            maxInFlight = 100,
            queueTimeoutMs = 5000,
            maxQueueSize = 1000
        )

        // Create simple executor for testing
        val testExecutor = Executors.newSingleThreadExecutor()
        workerExecutor = Executors.newFixedThreadPool(4) // Worker pool for admission control
        executor = object : LogicExecutor {
            override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
                return testExecutor.submit(task::get).get()
            }

            override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Exception) {
                    defaultValue
                }
            }

            override fun <T> executeWithTranslation(task: ThrowingSupplier<T>, customTranslator: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Throwable) {
                    fallback(e)
                }
            }

            override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Throwable) {
                    recovery(e)
                }
            }

            override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
                testExecutor.submit { task.run() }.get()
            }

            override fun executeVoidJava(task: Runnable, context: TaskContext) {
                testExecutor.submit(task).get()
            }

            override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T {
                return try {
                    execute(task, context)
                } finally {
                    finallyBlock.run()
                }
            }
        }

        admissionControl = GlobalAdmissionControl(properties, meterRegistry, executor, workerExecutor)
    }

    @AfterEach
    fun cleanup() {
        meterRegistry.close()
    }

    private fun createTestControl(maxInFlight: Int, queueTimeoutMs: Long, maxQueueSize: Int): GlobalAdmissionControl {
        val testProperties = GlobalAdmissionProperties(
            maxInFlight = maxInFlight,
            queueTimeoutMs = queueTimeoutMs,
            maxQueueSize = maxQueueSize
        )

        val testExecutor = Executors.newSingleThreadExecutor()
        val testExecutorService = object : LogicExecutor {
            override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T {
                return testExecutor.submit(task::get).get()
            }

            override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Exception) {
                    defaultValue
                }
            }

            override fun <T> executeWithTranslation(task: ThrowingSupplier<T>, customTranslator: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: (Throwable) -> T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Throwable) {
                    fallback(e)
                }
            }

            override fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T {
                return try {
                    execute(task, context)
                } catch (e: Throwable) {
                    recovery(e)
                }
            }

            override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: maple.expectation.infrastructure.executor.strategy.ExceptionTranslator, context: TaskContext): T {
                return execute(task, context)
            }

            override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
                testExecutor.submit { task.run() }.get()
            }

            override fun executeVoidJava(task: Runnable, context: TaskContext) {
                testExecutor.submit(task).get()
            }

            override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T {
                return try {
                    execute(task, context)
                } finally {
                    finallyBlock.run()
                }
            }
        }

        val workerPool = Executors.newFixedThreadPool(4)
        return GlobalAdmissionControl(testProperties, meterRegistry, testExecutorService, workerPool)
    }

    @Test
    @DisplayName("should enforce max in-flight limit")
    fun `should enforce max in-flight limit`() {
        val latch = CountDownLatch(1)
        val executionCount = AtomicInteger(0)
        val maxInFlight = 5 // Small number for testing

        val testControl = createTestControl(maxInFlight, 5000, 1000)

        // Submit 10 requests with maxInFlight=5
        val futures = (1..10).map { i ->
            testControl.submitOrWait("key-$i") {
                latch.await() // Block until released
                executionCount.incrementAndGet()
                "result-$i"
            }
        }

        // Wait a bit for some requests to start executing
        Thread.sleep(100)

        // Check in-flight metric does not exceed maxInFlight
        val inFlightGauge = meterRegistry.get("admission_control.in_flight").gauge()
        val currentInFlight = inFlightGauge?.value() ?: 0.0

        assertThat(currentInFlight).isLessThanOrEqualTo(maxInFlight.toDouble())

        // Release all requests
        latch.countDown()

        // Verify all complete successfully
        futures.forEach { it.get(10, TimeUnit.SECONDS) }

        assertThat(executionCount.get()).isEqualTo(10)
    }

    @Test
    @DisplayName("should timeout when queue exceeds timeout")
    fun `should timeout when queue exceeds timeout`() {
        val testControl = createTestControl(1, 100, 1)

        val blockLatch = CountDownLatch(1)

        // First request acquires permit and blocks
        val f1 = testControl.submitOrWait("key1") {
            blockLatch.await() // Block to keep permit occupied
            "result1"
        }

        // Wait for first request to acquire permit
        Thread.sleep(50)

        // Second request waits in queue but times out
        val f2 = testControl.submitOrWait("key2") {
            "result2"
        }

        // Verify timeout exception (wrapped in ExecutionException)
        val exception = org.junit.jupiter.api.assertThrows<java.util.concurrent.ExecutionException> {
            f2.get(1, TimeUnit.SECONDS)
        }
        assertThat(exception.cause).isInstanceOf(AdmissionTimeoutException::class.java)
        assertThat(exception.cause?.message).contains("timeout")

        // Verify timeout counter incremented
        val timeoutCounter = meterRegistry.get("admission_control.queue.timeout").counter()
        assertThat(timeoutCounter.count()).isGreaterThan(0.0)

        // Release first request
        blockLatch.countDown()
        f1.get(1, TimeUnit.SECONDS)
    }

    @Test
    @DisplayName("should track queue depth metric")
    fun `should track queue depth metric`() {
        val maxInFlight = 2
        val testControl = createTestControl(maxInFlight, 5000, 1000)

        val blockLatch = CountDownLatch(1)

        // Submit more requests than maxInFlight to create queue
        val futures = (1..5).map { i ->
            testControl.submitOrWait("key-$i") {
                blockLatch.await()
                "result-$i"
            }
        }

        // Wait for queue to build up
        Thread.sleep(100)

        // Check queue depth metric
        val queueDepthGauge = meterRegistry.get("admission_control.queue_depth").gauge()
        val queueDepth = queueDepthGauge?.value() ?: 0.0

        assertThat(queueDepth).isGreaterThan(0.0)

        // Release all requests
        blockLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
    }

    @Test
    @DisplayName("should register all required Prometheus metrics")
    fun `should register all required Prometheus metrics`() {
        val requiredMetrics = listOf(
            "admission_control.in_flight",
            "admission_control.queue_depth",
            "admission_control.queue.timeout",
            "admission_control.queue.full",
            "admission_control.rejected"
        )

        requiredMetrics.forEach { metricName ->
            val meter = meterRegistry.getMeters()
                .firstOrNull { it.id.name == metricName }

            assertThat(meter).isNotNull()
        }
    }

    @Test
    @DisplayName("should execute immediately when permit available")
    fun `should execute immediately when permit available`() {
        val testWorkerPool = Executors.newFixedThreadPool(4)
        val testControl = GlobalAdmissionControl(properties, meterRegistry, executor, testWorkerPool)

        val startTime = System.nanoTime()
        val future = testControl.submitOrWait("immediate-key") {
            "immediate-result"
        }
        val result = future.get(1, TimeUnit.SECONDS)
        val duration = (System.nanoTime() - startTime) / 1_000_000 // Convert to ms

        assertThat(result).isEqualTo("immediate-result")
        assertThat(duration).isLessThan(100)
    }
}
