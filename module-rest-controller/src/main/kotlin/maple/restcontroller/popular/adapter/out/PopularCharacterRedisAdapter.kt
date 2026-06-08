package maple.restcontroller.popular.adapter.out

import java.time.Duration
import java.time.Instant
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.popular.port.out.PopularCharacterRedisPort
import maple.restcontroller.popular.port.out.PopularCharacterScoreEntry
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import org.springframework.stereotype.Component

/**
 * Redis adapter — only file in module-rest-controller that touches
 * [StringRedisTemplate]. Owns ALL Redis-shaped key naming so callers express
 * intent ("top N for window W", "record request for IGN") without leaking
 * Redis key formats into the service layer.
 */
@Component
class PopularCharacterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val properties: V6ReadProperties,
) : PopularCharacterRedisPort {

    override fun incrementScore(ign: String, delta: Double, now: Instant) {
        val key = bucketKey(epochHourOf(now))
        redisTemplate.opsForZSet().incrementScore(key, ign, delta)
    }

    override fun expireAt(ign: String, expireAt: Instant) {
        val key = bucketKey(epochHourOf(expireAt))
        val ttl = Duration.between(Instant.now(), expireAt)
        if (ttl.isNegative || ttl.isZero) return
        redisTemplate.expire(key, ttl)
    }

    override fun readTopWithScores(
        windowHours: Int,
        limit: Int,
    ): List<PopularCharacterScoreEntry> {
        val currentHour = epochHourOf(Instant.now())
        val readKey = if (windowHours == 1) {
            bucketKey(currentHour)
        } else {
            val sourceKeys = (0 until windowHours).map { offset -> bucketKey(currentHour - offset) }
            val destinationKey = rollingKey(currentHour, windowHours)
            unionAndStoreInto(destinationKey, sourceKeys)
            destinationKey
        }
        val tuples: Set<ZSetOperations.TypedTuple<String>>? = redisTemplate.opsForZSet()
            .reverseRangeWithScores(readKey, 0, (limit - 1).toLong())
        return tuples
            ?.map { PopularCharacterScoreEntry(it.value, it.score) }
            ?: emptyList()
    }

    private fun unionAndStoreInto(destination: String, sources: List<String>) {
        if (sources.isEmpty()) return
        redisTemplate.opsForZSet().unionAndStore(sources.first(), sources.drop(1), destination)
        redisTemplate.expire(
            destination,
            Duration.ofSeconds(properties.popular.rollingTtlSeconds),
        )
    }

    /** Per-IGN hour bucket key. Format: `<prefix>:hour:<epochHour>`. */
    private fun bucketKey(epochHour: Long): String = "${properties.popular.redisKeyPrefix}:hour:$epochHour"

    /** Rolling read destination key. Format: `<prefix>:rolling:<windowHours>h:<epochHour>`. */
    private fun rollingKey(epochHour: Long, windowHours: Int): String = "${properties.popular.redisKeyPrefix}:rolling:${windowHours}h:$epochHour"

    private fun epochHourOf(at: Instant): Long = at.epochSecond / SECONDS_PER_HOUR

    private companion object {
        const val SECONDS_PER_HOUR = 3600L
    }
}
