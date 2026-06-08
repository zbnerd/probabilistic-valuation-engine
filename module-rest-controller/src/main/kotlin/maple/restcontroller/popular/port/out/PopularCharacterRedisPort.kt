package maple.restcontroller.popular.port.out

import java.time.Instant

/**
 * Outbound port for PopularCharacterService's Redis ZSET persistence.
 * Adapter implementations encapsulate [org.springframework.data.redis.core.StringRedisTemplate]
 * details and rolling-key generation; the service depends on this interface only.
 */
interface PopularCharacterRedisPort {
    /** Increment the IGN's score by [delta] in the current rolling window. */
    fun incrementScore(ign: String, delta: Double, now: Instant = Instant.now())

    /** Set TTL on the IGN's rolling-window ZSET key to expire at [expireAt]. */
    fun expireAt(ign: String, expireAt: Instant)

    /**
     * Aggregate the last [windowHours] hour buckets into a rolling read key and
     * return the top [limit] entries by score (descending). The service expresses
     * intent ("top N for window W"); the adapter owns the Redis-shaped key names
     * and the unionAndStore/expire/reverseRangeWithScores orchestration.
     */
    fun readTopWithScores(windowHours: Int, limit: Int): List<PopularCharacterScoreEntry>
}
