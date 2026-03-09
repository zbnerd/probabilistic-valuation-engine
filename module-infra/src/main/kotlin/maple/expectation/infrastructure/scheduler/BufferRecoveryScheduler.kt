package maple.expectation.infrastructure.scheduler

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import maple.expectation.infrastructure.queue.strategy.RedisBufferStrategy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Buffer 복구 스케줄러 (#271 V5 Stateless Architecture)
 */
@Component
@ConditionalOnBean(RedisBufferStrategy::class)
@ConditionalOnProperty(
    name = ["scheduler.buffer-recovery.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BufferRecoveryScheduler(
    private val redisBufferStrategy: RedisBufferStrategy<*>,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(BufferRecoveryScheduler::class.java)

    @Value("\${buffer.inflight.timeout-ms:60000}")
    private var inflightTimeoutMs: Long = 60000

    @Value("\${buffer.recovery.batch-size:100}")
    private var batchSize: Int = 100

    @Scheduled(fixedDelayString = "\${scheduler.buffer-recovery.retry-rate:10000}")
    fun processRetryQueue() {
        executor.executeOrDefault(
            {
                lockStrategy.executeWithLock(
                    "scheduler:buffer-recovery:retry",
                    0,
                    30,
                ) {
                    doProcessRetryQueue()
                    null
                }
            },
            null,
            TaskContext.of("Scheduler", "Buffer.ProcessRetry"),
        )
    }

    private fun doProcessRetryQueue() {
        val processed = redisBufferStrategy.processRetryQueue(batchSize)

        if (processed.isNotEmpty()) {
            meterRegistry.counter("buffer.scheduler.retry.processed").increment(processed.size.toDouble())
            log.info("[BufferRecovery] Processed {} retry messages", processed.size)
        }
    }

    @Scheduled(fixedDelayString = "\${scheduler.buffer-recovery.redrive-rate:60000}")
    fun redriveExpiredInflight() {
        executor.executeOrDefault(
            {
                lockStrategy.executeWithLock(
                    "scheduler:buffer-recovery:redrive",
                    0,
                    60,
                ) {
                    doRedriveExpiredInflight()
                    null
                }
            },
            null,
            TaskContext.of("Scheduler", "Buffer.Redrive"),
        )
    }

    private fun doRedriveExpiredInflight() {
        val expiredMsgIds = redisBufferStrategy.getExpiredInflightMessages(inflightTimeoutMs, batchSize)

        if (expiredMsgIds.isEmpty()) {
            return
        }

        var redriveCount = 0
        var skipCount = 0

        for (msgId in expiredMsgIds) {
            val redriven = redisBufferStrategy.redrive(msgId)
            if (redriven) {
                redriveCount++
            } else {
                skipCount++
            }
        }

        if (redriveCount > 0) {
            meterRegistry.counter("buffer.scheduler.redrive.success").increment(redriveCount.toDouble())
            log.warn(
                "[BufferRecovery] Redriven {} expired INFLIGHT messages (skipped: {})",
                redriveCount,
                skipCount,
            )
        }
    }
}
