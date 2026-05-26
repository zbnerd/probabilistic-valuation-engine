package maple.restcontroller.popular

import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant

class PopularCharacterService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recordV6ExpectationRequest(userIgn: String) {
        val normalizedIgn = userIgn.trim()
        if (normalizedIgn.isBlank()) return

        runCatching {
            val key = bucketKey(currentEpochHour())
            redisTemplate.opsForZSet().incrementScore(key, normalizedIgn, 1.0)
            redisTemplate.expire(key, Duration.ofHours(properties.popular.bucketTtlHours))
        }.onFailure { error ->
            log.warn(
                "Popular character Redis write failed: userIgn={} error={}",
                maskIgn(normalizedIgn),
                error.javaClass.simpleName,
            )
        }
    }

    fun top(windowHours: Int? = null): PopularCharacterResponse {
        val effectiveWindowHours = effectiveWindowHours(windowHours)
        val limit = properties.popular.topSize.coerceAtLeast(1)

        return runCatching {
            val readKey = rollingReadKey(effectiveWindowHours)
            val entries = redisTemplate.opsForZSet()
                .reverseRangeWithScores(readKey, 0, (limit - 1).toLong())
                ?.mapIndexedNotNull { index, tuple ->
                    val userIgn = tuple.value ?: return@mapIndexedNotNull null
                    val score = tuple.score ?: return@mapIndexedNotNull null
                    PopularCharacterEntry(
                        rank = index + 1,
                        userIgn = userIgn,
                        requestCount = score.toLong(),
                    )
                }
                .orEmpty()

            PopularCharacterResponse(
                windowHours = effectiveWindowHours,
                source = PopularCharacterSource.REDIS,
                degraded = false,
                characters = entries,
            )
        }.getOrElse { error ->
            log.warn(
                "Popular character Redis read failed: windowHours={} error={}",
                effectiveWindowHours,
                error.javaClass.simpleName,
            )
            PopularCharacterResponse(
                windowHours = effectiveWindowHours,
                source = PopularCharacterSource.DEGRADED,
                degraded = true,
                characters = emptyList(),
            )
        }
    }

    private fun rollingReadKey(windowHours: Int): String {
        val currentHour = currentEpochHour()
        if (windowHours == 1) return bucketKey(currentHour)

        val keys = (0 until windowHours).map { offset -> bucketKey(currentHour - offset) }
        val destinationKey = rollingKey(currentHour, windowHours)
        redisTemplate.opsForZSet().unionAndStore(keys.first(), keys.drop(1), destinationKey)
        redisTemplate.expire(destinationKey, Duration.ofSeconds(properties.popular.rollingTtlSeconds))
        return destinationKey
    }

    private fun effectiveWindowHours(windowHours: Int?): Int =
        (windowHours ?: properties.popular.defaultWindowHours)
            .coerceAtLeast(1)
            .coerceAtMost(properties.popular.maxWindowHours.coerceAtLeast(1))

    private fun currentEpochHour(): Long =
        Instant.now().epochSecond / SECONDS_PER_HOUR

    private fun bucketKey(epochHour: Long): String =
        "${properties.popular.redisKeyPrefix}:hour:$epochHour"

    private fun rollingKey(epochHour: Long, windowHours: Int): String =
        "${properties.popular.redisKeyPrefix}:rolling:${windowHours}h:$epochHour"

    private companion object {
        const val SECONDS_PER_HOUR = 3600L
    }
}
