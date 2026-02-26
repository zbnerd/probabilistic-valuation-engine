package maple.expectation.scheduler

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.EquipmentExpectationSummaryRepository
import maple.expectation.infrastructure.shutdown.ShutdownProperties
import maple.expectation.service.v4.buffer.ExpectationWriteBackBuffer
import maple.expectation.service.v4.buffer.ExpectationWriteTask
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class ExpectationBatchShutdownHandler(
    private val buffer: ExpectationWriteBackBuffer,
    private val repository: EquipmentExpectationSummaryRepository,
    private val executor: LogicExecutor,
    private val properties: ShutdownProperties,
    meterRegistry: MeterRegistry
) : SmartLifecycle {

    companion object {
        private val log = LoggerFactory.getLogger(ExpectationBatchShutdownHandler::class.java)
    }

    private val shutdownDrainTimer: Timer = Timer.builder("shutdown.buffer.drain.duration")
        .description("Expectation 버퍼 Drain 소요 시간")
        .register(meterRegistry)

    private val drainSuccessCounter: Counter = Counter.builder("shutdown.buffer.drain.tasks")
        .tag("status", "success")
        .description("Drain 성공 건수")
        .register(meterRegistry)

    private val drainFailureCounter: Counter = Counter.builder("shutdown.buffer.drain.tasks")
        .tag("status", "failure")
        .description("Drain 실패 건수")
        .register(meterRegistry)

    @Volatile
    private var running = true

    override fun start() {
        this.running = true
        log.debug("[ExpectationShutdown] Started")
    }

    override fun stop() {
        val context = TaskContext.of("ExpectationShutdown", "DrainBuffer")
        val startNanos = System.nanoTime()

        executor.executeWithFinally(
            {
                log.info(
                    "[ExpectationShutdown] Starting 3-phase shutdown... pending={}",
                    buffer.pendingCount
                )

                buffer.prepareShutdown()
                log.info("[ExpectationShutdown] Phase 1 complete - new offers blocked")

                val pendingCount = buffer.pendingCount
                if (pendingCount == 0) {
                    log.info("[ExpectationShutdown] Phase 2 skipped - no pending offers")
                } else {
                    val awaitTimeout = buffer.shutdownAwaitTimeout
                    val allCompleted = buffer.awaitPendingOffers(awaitTimeout)
                    if (allCompleted) {
                        log.info("[ExpectationShutdown] Phase 2 complete - all in-flight offers completed")
                    } else {
                        log.warn(
                            "[ExpectationShutdown] Phase 2 timeout - some offers may not have completed"
                        )
                    }
                }

                val totalFlushed = drainBuffer()
                log.info("[ExpectationShutdown] Phase 3 complete - {} tasks flushed to DB", totalFlushed)

                null
            },
            {
                this.running = false
                shutdownDrainTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS)
            },
            context
        )
    }

    private fun drainBuffer(): Int {
        var totalFlushed = 0
        var emptyRetries = 0

        while (emptyRetries < properties.emptyBatchRetryCount) {
            val batch = buffer.drain(properties.batchSize)

            if (batch.isEmpty()) {
                emptyRetries++
                sleepSafely(properties.emptyBatchWaitMs)
                continue
            }

            emptyRetries = 0
            val batchFlushed = flushBatch(batch)
            totalFlushed += batchFlushed

            log.debug(
                "[ExpectationShutdown] Flushed batch: {} tasks, total: {}",
                batchFlushed,
                totalFlushed
            )
        }

        return totalFlushed
    }

    private fun sleepSafely(millis: Long) {
        executor.executeOrDefault(
            {
                Thread.sleep(millis)
                null
            },
            null,
            TaskContext.of("ExpectationShutdown", "SleepSafely")
        )
    }

    private fun flushBatch(batch: List<ExpectationWriteTask>): Int {
        var successCount = 0
        var failureCount = 0

        for (task in batch) {
            val success = executor.executeOrDefault(
                {
                    repository.upsertExpectationSummary(
                        task.characterId,
                        task.presetNo,
                        task.totalExpectedCost,
                        task.blackCubeCost,
                        task.redCubeCost,
                        task.additionalCubeCost,
                        task.starforceCost
                    )
                    true
                },
                false,
                TaskContext.of("ExpectationShutdown", "Upsert", task.key())
            )

            if (success) {
                successCount++
            } else {
                failureCount++
                log.warn("[ExpectationShutdown] Failed to save task: {}", task.key())
            }
        }

        drainSuccessCounter.increment(successCount.toDouble())
        if (failureCount > 0) {
            drainFailureCounter.increment(failureCount.toDouble())
            log.warn(
                "[ExpectationShutdown] Batch completed with failures: success={}, failure={}",
                successCount,
                failureCount
            )
        }

        return successCount
    }

    override fun getPhase(): Int = Integer.MAX_VALUE - 500

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true
}
