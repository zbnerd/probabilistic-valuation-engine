package maple.expectation.infrastructure.buffer

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.core.port.out.BackoffStrategy
import maple.expectation.core.port.out.ExpectationBufferPort
import maple.expectation.infrastructure.config.BufferProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Expectation Write-Behind 메모리 버퍼 (#266 ADR 정합성 리팩토링)
 */
@Component
class ExpectationWriteBackBuffer(
    private val properties: BufferProperties,
    private val meterRegistry: MeterRegistry,
    private val backoffStrategy: BackoffStrategy,
    private val executor: LogicExecutor,
) : ExpectationBufferPort {

    private val queue: ConcurrentLinkedQueue<ExpectationWriteTask> = ConcurrentLinkedQueue()
    private val pendingCount: AtomicInteger = AtomicInteger(0)

    private val shutdownPhaser: Phaser = object : Phaser() {
        override fun onAdvance(phase: Int, registeredParties: Int): Boolean = registeredParties == 0
    }

    @Volatile
    private var shuttingDown = false

    companion object {
        private val log = LoggerFactory.getLogger(ExpectationWriteBackBuffer::class.java)
    }

    init {
        registerMetrics()
    }

    private fun registerMetrics() {
        Gauge.builder("expectation.buffer.pending", pendingCount) { it.get().toDouble() }
            .description("Expectation 버퍼 대기 작업 수")
            .register(meterRegistry)
    }

    fun offer(tasks: List<ExpectationWriteTask>): Boolean {
        if (shuttingDown) {
            meterRegistry.counter("expectation.buffer.rejected.shutdown").increment()
            log.debug("[ExpectationBuffer] Rejected during shutdown: tasks={}", tasks.size)
            return false
        }

        shutdownPhaser.register()

        return executor.executeWithFinally(
            { offerInternal(tasks) },
            { shutdownPhaser.arriveAndDeregister() },
            TaskContext.of("Buffer", "Offer", "tasks=${tasks.size}"),
        )
    }

    private fun offerInternal(tasks: List<ExpectationWriteTask>): Boolean {
        val required = tasks.size
        val newCount = pendingCount.addAndGet(required)

        if (newCount > properties.maxQueueSize) {
            pendingCount.addAndGet(-required)
            meterRegistry.counter("expectation.buffer.rejected.backpressure").increment()
            log.warn(
                "[ExpectationBuffer] Backpressure triggered: pending={}, required={}, max={}",
                newCount - required,
                required,
                properties.maxQueueSize,
            )
            return false
        }

        for (task in tasks) {
            queue.offer(task)
        }
        meterRegistry.counter("expectation.buffer.cas.success").increment()
        log.debug(
            "[ExpectationBuffer] Buffered {} tasks, pending={}",
            tasks.size,
            newCount,
        )
        return true
    }

    fun drain(maxBatchSize: Int): List<ExpectationWriteTask> {
        val batch = mutableListOf<ExpectationWriteTask>()
        var task: ExpectationWriteTask?

        while (batch.size < maxBatchSize) {
            task = queue.poll() ?: break
            batch.add(task)
            pendingCount.decrementAndGet()
        }

        return batch
    }

    val pendingCountValue: Int
        get() = pendingCount.get()

    // ExpectationBufferPort Implementation
    override fun isShuttingDown(): Boolean = shuttingDown

    override fun isEmpty(): Boolean = queue.isEmpty()

    override fun getPendingCount(): Int = pendingCount.get()

    // Shutdown Race Prevention
    fun prepareShutdown() {
        this.shuttingDown = true
        log.info("[ExpectationBuffer] Shutdown prepared - new offers will be rejected")
    }

    fun awaitPendingOffers(timeout: Duration): Boolean {
        return executor.executeOrDefault(
            {
                if (shutdownPhaser.registeredParties == 0) {
                    log.debug("[Buffer] No registered parties in shutdown phaser, skipping await")
                    return@executeOrDefault true
                }

                val phase = shutdownPhaser.phase
                shutdownPhaser.awaitAdvanceInterruptibly(phase, timeout.toMillis(), TimeUnit.MILLISECONDS)
                true
            },
            false,
            TaskContext.of("Buffer", "AwaitPendingOffers", "timeout=${timeout.seconds}s"),
        )
    }

    val shutdownAwaitTimeout: Duration
        get() = Duration.ofSeconds(properties.shutdownAwaitTimeoutSeconds.toLong())
}
