package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.restcontroller.config.V6ReadProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Read-model Redis cache: multiGet / multiPut only.
 *
 * Negative-cache and urgent-dedup concerns are owned by [NegativeCacheService]
 * and [UrgentDedupService] respectively. A successful [multiPut] invalidates
 * any in-flight urgent dedup state for the same characters, so this service
 * delegates the cleanup to [UrgentDedupService] after the pipeline returns.
 */
class ReadModelCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: V6ReadProperties,
    private val urgentDedupService: UrgentDedupService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "v6:read"
    }

    fun cacheKey(userIgn: String, presetNo: Int): String = "$KEY_PREFIX:$userIgn:$presetNo"

    /**
     * True iff a ready value exists in the read-model cache. Used by
     * callers before delegating to [UrgentDedupService.status] so the
     * status projection knows whether the request can be answered from
     * cache without falling through to urgent dedup state.
     */
    fun hasReadyCache(userIgn: String, presetNo: Int): Boolean = redisTemplate.hasKey(cacheKey(userIgn, presetNo))

    /**
     * Redis multiGet for partial cache lookup.
     * @return Pair of (cacheHits: userIgn -> V6ExpectationResponse, cacheMissKeys: userIgn -> presetNo)
     */
    fun multiGet(
        requests: Map<String, Int>,
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
     * Write DB results to Redis cache with TTL using pipeline (1 RTT).
     * Also clears any urgent-dedup state for the same characters via
     * [UrgentDedupService] — a freshly populated cache invalidates the
     * "still pending" flag and the status ZSet entry.
     */
    fun multiPut(results: Map<String, V6ExpectationResponse>) {
        if (results.isEmpty()) return

        val ttl = properties.cacheTtlSeconds

        redisTemplate.executePipelined { connection ->
            results.forEach { (userIgn, response) ->
                val key = cacheKey(userIgn, response.presetNo).toByteArray()
                val json = objectMapper.writeValueAsBytes(response)
                connection.stringCommands().setEx(key, ttl, json)
            }
            null
        }

        results.keys.forEach { userIgn ->
            urgentDedupService.clearUrgentPending(userIgn)
            urgentDedupService.removeUrgentStatus(userIgn)
        }

        log.debug("Redis cache write (pipeline): {} entries, TTL={}s", results.size, ttl)
    }
}
