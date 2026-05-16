package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.StringMaskingUtils.maskIgn
import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

class ReadModelCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: V6ReadProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "v6:read"
        private const val NEGATIVE_KEY_PREFIX = "v6:not-found"
        private const val URGENT_PENDING_PREFIX = "v6:urgent-pending"
    }

    fun cacheKey(userIgn: String, presetNo: Int): String =
        "$KEY_PREFIX:$userIgn:$presetNo"

    /**
     * Redis multiGet for partial cache lookup.
     * @return Pair of (cacheHits: userIgn -> V6ExpectationResponse, cacheMissKeys: userIgn -> presetNo)
     */
    fun multiGet(
        requests: Map<String, Int>
    ): Pair<Map<String, V6ExpectationResponse>, Map<String, Int>> {
        if (requests.isEmpty()) return emptyMap<String, V6ExpectationResponse>() to emptyMap()

        val keys = requests.entries.associate { (userIgn, presetNo) ->
            cacheKey(userIgn, presetNo) to (userIgn to presetNo)
        }

        val values = redisTemplate.opsForValue().multiGet(keys.keys.toList())

        val hits = mutableMapOf<String, V6ExpectationResponse>()
        val misses = mutableMapOf<String, Int>()

        keys.entries.forEachIndexed { index, (key, userIgnAndPresetNo) ->
            val (userIgn, presetNo) = userIgnAndPresetNo
            val value = values?.get(index)

            if (value != null) {
                val response = objectMapper.readValue(value, V6ExpectationResponse::class.java)
                hits[userIgn] = response
            } else {
                misses[userIgn] = presetNo
            }
        }

        log.debug("Redis cache lookup: hits={}, misses={}", hits.size, misses.size)
        return hits to misses
    }

    /**
     * Write DB results to Redis cache with TTL.
     */
    fun multiPut(results: Map<String, V6ExpectationResponse>) {
        if (results.isEmpty()) return

        val ttl = Duration.ofSeconds(properties.cacheTtlSeconds)
        results.forEach { (userIgn, response) ->
            val key = cacheKey(userIgn, response.presetNo)
            val json = objectMapper.writeValueAsString(response)
            redisTemplate.opsForValue().set(key, json, ttl)
            clearUrgentPending(userIgn)
        }

        log.debug("Redis cache write: {} entries, TTL={}s", results.size, ttl.seconds)
    }

    // --- Negative cache (non-existent characters) ---

    fun negativeCacheKey(userIgn: String): String = "$NEGATIVE_KEY_PREFIX:$userIgn"

    fun getNegativeCache(userIgn: String): Boolean {
        return redisTemplate.hasKey(negativeCacheKey(userIgn))
    }

    fun setNegativeCache(userIgn: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(negativeCacheKey(userIgn), "NOT_FOUND", Duration.ofSeconds(ttlSeconds))
        log.info("Set negative cache: userIgn={}", maskIgn(userIgn))
    }

    // --- Urgent dedup (prevent duplicate triggers) ---

    fun urgentPendingKey(userIgn: String): String = "$URGENT_PENDING_PREFIX:$userIgn"

    fun tryMarkUrgentPending(userIgn: String): Boolean {
        val result = redisTemplate.opsForValue()
            .setIfAbsent(urgentPendingKey(userIgn), "1", Duration.ofSeconds(properties.urgentPendingTtlSeconds))
        return result == true
    }

    fun isUrgentPending(userIgn: String): Boolean =
        redisTemplate.hasKey(urgentPendingKey(userIgn))

    fun clearUrgentPending(userIgn: String) {
        redisTemplate.delete(urgentPendingKey(userIgn))
    }
}
