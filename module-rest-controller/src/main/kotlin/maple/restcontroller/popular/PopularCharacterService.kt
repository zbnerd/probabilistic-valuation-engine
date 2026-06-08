package maple.restcontroller.popular

import java.time.Duration
import java.time.Instant
import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import maple.restcontroller.popular.port.out.PopularCharacterRedisPort
import org.slf4j.LoggerFactory

class PopularCharacterService(
    private val redisPort: PopularCharacterRedisPort,
    private val properties: V6ReadProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recordV6ExpectationRequest(userIgn: String) {
        val normalizedIgn = userIgn.trim()
        if (normalizedIgn.isBlank()) return

        runCatching {
            redisPort.incrementScore(normalizedIgn, 1.0)
            redisPort.expireAt(
                normalizedIgn,
                Instant.now().plus(Duration.ofHours(properties.popular.bucketTtlHours)),
            )
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
            val entries = redisPort.readTopWithScores(effectiveWindowHours, limit)
                .mapIndexedNotNull { index, entry ->
                    val userIgn = entry.value ?: return@mapIndexedNotNull null
                    val score = entry.score ?: return@mapIndexedNotNull null
                    PopularCharacterEntry(
                        rank = index + 1,
                        userIgn = userIgn,
                        requestCount = score.toLong(),
                    )
                }

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

    private fun effectiveWindowHours(windowHours: Int?): Int = (windowHours ?: properties.popular.defaultWindowHours)
        .coerceAtLeast(1)
        .coerceAtMost(properties.popular.maxWindowHours.coerceAtLeast(1))
}
