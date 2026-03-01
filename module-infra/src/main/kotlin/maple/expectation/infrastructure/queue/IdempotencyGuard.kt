package maple.expectation.infrastructure.queue

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class IdempotencyGuard(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    @Value("\${idempotency.ttl-hours:24}") private val ttlHours: Int
) {
    private val log = LoggerFactory.getLogger(IdempotencyGuard::class.java)

    companion object {
        private const val STATUS_PROCESSING = "PROCESSING"
        private const val STATUS_COMPLETED = "COMPLETED"
    }

    fun tryAcquire(jobType: String, msgId: String): Boolean {
        val key = buildKey(jobType, msgId)

        return executor.executeOrDefault(
            {
                val bucket: RBucket<String> = redissonClient.getBucket(key)
                val acquired = bucket.trySet(STATUS_PROCESSING, ttlHours.toLong(), TimeUnit.HOURS)

                if (acquired) {
                    meterRegistry.counter("idempotency.acquire.success", "job", jobType).increment()
                    log.debug("[IdempotencyGuard] Acquired: {} -> {}", jobType, msgId)
                } else {
                    val currentStatus = bucket.get()
                    meterRegistry
                        .counter("idempotency.acquire.skip", "job", jobType, "status", currentStatus ?: "null")
                        .increment()
                    log.debug("[IdempotencyGuard] Skip ({}): {} -> {}", currentStatus, jobType, msgId)
                }

                acquired
            },
            false,
            TaskContext.of("Idempotency", "TryAcquire", msgId)
        )
    }

    fun markCompleted(jobType: String, msgId: String) {
        val key = buildKey(jobType, msgId)

        executor.executeVoidJava({
            val bucket: RBucket<String> = redissonClient.getBucket(key)
            bucket.set(STATUS_COMPLETED, ttlHours.toLong(), TimeUnit.HOURS)

            meterRegistry.counter("idempotency.completed", "job", jobType).increment()
            log.debug("[IdempotencyGuard] Completed: {} -> {}", jobType, msgId)
        }, TaskContext.of("Idempotency", "MarkCompleted", msgId))
    }

    fun release(jobType: String, msgId: String) {
        val key = buildKey(jobType, msgId)

        executor.executeVoidJava({
            val deleted = redissonClient.getBucket<String>(key).delete()

            if (deleted) {
                meterRegistry.counter("idempotency.release.success", "job", jobType).increment()
                log.debug("[IdempotencyGuard] Released: {} -> {}", jobType, msgId)
            } else {
                meterRegistry.counter("idempotency.release.not_found", "job", jobType).increment()
                log.debug("[IdempotencyGuard] Release not found: {} -> {}", jobType, msgId)
            }
        }, TaskContext.of("Idempotency", "Release", msgId))
    }

    fun getStatus(jobType: String, msgId: String): String? {
        val key = buildKey(jobType, msgId)

        return executor.executeOrDefault(
            { redissonClient.getBucket<String>(key).get() },
            null,
            TaskContext.of("Idempotency", "GetStatus", msgId)
        )
    }

    fun isCompleted(jobType: String, msgId: String): Boolean {
        return STATUS_COMPLETED == getStatus(jobType, msgId)
    }

    private fun buildKey(jobType: String, msgId: String): String {
        return RedisKey.IDEMPOTENCY_PREFIX.key + "job:" + jobType + ":" + msgId
    }
}
