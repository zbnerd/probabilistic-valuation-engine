package maple.restcontroller.read

import java.time.Duration
import maple.expectation.util.StringMaskingUtils.maskIgn
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Negative cache for characters confirmed not found by the upstream API.
 *
 * Wraps a single concern: a short-TTL Redis key per userIgn that lets the
 * read path short-circuit on cache miss without re-triggering the urgent
 * pipeline. Setting a negative cache also clears any in-flight urgent
 * state (delegated to [UrgentDedupService]) so the dedup machinery
 * converges when a character is confirmed as not-found.
 */
class NegativeCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val urgentDedupService: UrgentDedupService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "v6:not-found"
    }

    fun negativeCacheKey(userIgn: String): String = "$KEY_PREFIX:$userIgn"

    fun getNegativeCache(userIgn: String): Boolean = redisTemplate.hasKey(negativeCacheKey(userIgn))

    fun setNegativeCache(userIgn: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(negativeCacheKey(userIgn), "NOT_FOUND", Duration.ofSeconds(ttlSeconds))
        urgentDedupService.clearUrgentPending(userIgn)
        urgentDedupService.removeUrgentStatus(userIgn)
        log.info("Set negative cache: userIgn={}", maskIgn(userIgn))
    }
}
