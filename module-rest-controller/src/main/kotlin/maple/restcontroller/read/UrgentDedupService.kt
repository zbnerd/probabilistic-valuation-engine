package maple.restcontroller.read

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Urgent-pipeline deduplication and status projection.
 *
 * Owns:
 *  - the urgent-pending flag (Redis SETNX with TTL) that prevents duplicate
 *    urgent triggers for the same character within the dedup window,
 *  - the ZSet-backed status queue used to compute queue position for the
 *    `/status` endpoint,
 *  - the projection of cache/negative-cache/pending state into
 *    [UrgentReadStatusResponse].
 *
 * [status] is intentionally pure: callers pre-compute
 * `hasReadyCache` and `hasNegativeCache` from their respective services
 * and pass them in. This breaks the cycle between the three cache
 * services and keeps each service's concern narrow.
 */
class UrgentDedupService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val URGENT_PENDING_PREFIX = "v6:urgent-pending"
        private const val URGENT_STATUS_QUEUE_KEY = "v6:urgent:status-queue"
    }

    fun urgentPendingKey(userIgn: String): String = "$URGENT_PENDING_PREFIX:$userIgn"

    fun tryMarkUrgentPending(userIgn: String): Boolean {
        val result = redisTemplate.opsForValue()
            .setIfAbsent(urgentPendingKey(userIgn), System.currentTimeMillis().toString(), Duration.ofSeconds(properties.urgentPendingTtlSeconds))
        if (result == true) {
            redisTemplate.opsForZSet().add(URGENT_STATUS_QUEUE_KEY, userIgn, System.currentTimeMillis().toDouble())
        } else if (isUrgentPending(userIgn) && redisTemplate.opsForZSet().rank(URGENT_STATUS_QUEUE_KEY, userIgn) == null) {
            redisTemplate.opsForZSet().add(URGENT_STATUS_QUEUE_KEY, userIgn, System.currentTimeMillis().toDouble())
        }
        return result == true
    }

    fun isUrgentPending(userIgn: String): Boolean =
        redisTemplate.hasKey(urgentPendingKey(userIgn))

    fun clearUrgentPending(userIgn: String) {
        redisTemplate.delete(urgentPendingKey(userIgn))
    }

    fun removeUrgentStatus(userIgn: String) {
        redisTemplate.opsForZSet().remove(URGENT_STATUS_QUEUE_KEY, userIgn)
    }

    fun statusUrl(userIgn: String, presetNo: Int): String = "/api/v6/characters/$userIgn/status?presetNo=$presetNo"

    fun status(
        userIgn: String,
        presetNo: Int,
        hasReadyCache: Boolean,
        hasNegativeCache: Boolean,
    ): UrgentReadStatusResponse {
        val state: UrgentReadState = when {
            hasReadyCache -> UrgentReadState.Ready
            hasNegativeCache -> UrgentReadState.NotFound
            isUrgentPending(userIgn) -> {
                val pos = queuePosition(userIgn)
                UrgentReadState.Pending(
                    queuePositionApprox = pos,
                    estimatedWaitSeconds = pos?.let(::estimateWaitSeconds),
                )
            }
            else -> UrgentReadState.Unknown
        }
        return UrgentReadStatusResponse(
            state = state,
            userIgn = userIgn,
            statusUrl = statusUrl(userIgn, presetNo),
            queuePositionApprox = state.queuePositionApprox,
            estimatedWaitSeconds = state.estimatedWaitSeconds,
            retryAfterSeconds = state.retryAfterSeconds(properties.statusRetryAfterSeconds),
        )
    }

    private fun queuePosition(userIgn: String): Long? =
        redisTemplate.opsForZSet().rank(URGENT_STATUS_QUEUE_KEY, userIgn)?.plus(1)

    private fun estimateWaitSeconds(queuePosition: Long): Long {
        val throughput = properties.statusEstimatedThroughputPerSecond.takeIf { it > 0.0 } ?: 1.0
        return kotlin.math.ceil(queuePosition / throughput).toLong().coerceAtLeast(1)
    }
}
