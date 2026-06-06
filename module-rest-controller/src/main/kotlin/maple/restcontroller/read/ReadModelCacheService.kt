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
        private const val URGENT_STATUS_QUEUE_KEY = "v6:urgent:status-queue"
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
     * Write DB results to Redis cache with TTL using pipeline (1 RTT).
     */
    fun multiPut(results: Map<String, V6ExpectationResponse>) {
        if (results.isEmpty()) return

        val ttl = properties.cacheTtlSeconds
        val pendingKeys = mutableListOf<String>()
        val statusMembers = mutableListOf<String>()

        redisTemplate.executePipelined { connection ->
            results.forEach { (userIgn, response) ->
                val key = cacheKey(userIgn, response.presetNo).toByteArray()
                val json = objectMapper.writeValueAsBytes(response)
                connection.stringCommands().setEx(key, ttl, json)
                pendingKeys.add(urgentPendingKey(userIgn))
                statusMembers.add(userIgn)
            }
            null
        }

        if (pendingKeys.isNotEmpty()) {
            redisTemplate.delete(pendingKeys)
            redisTemplate.opsForZSet().remove(URGENT_STATUS_QUEUE_KEY, *statusMembers.toTypedArray())
        }

        log.debug("Redis cache write (pipeline): {} entries, TTL={}s", results.size, ttl)
    }

    // --- Negative cache (non-existent characters) ---

    fun negativeCacheKey(userIgn: String): String = "$NEGATIVE_KEY_PREFIX:$userIgn"

    fun getNegativeCache(userIgn: String): Boolean {
        return redisTemplate.hasKey(negativeCacheKey(userIgn))
    }

    fun setNegativeCache(userIgn: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(negativeCacheKey(userIgn), "NOT_FOUND", Duration.ofSeconds(ttlSeconds))
        clearUrgentPending(userIgn)
        removeUrgentStatus(userIgn)
        log.info("Set negative cache: userIgn={}", maskIgn(userIgn))
    }

    // --- Urgent dedup (prevent duplicate triggers) ---

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

    fun statusUrl(userIgn: String, presetNo: Int): String = "/api/v6/characters/$userIgn/status?presetNo=$presetNo"

    fun status(userIgn: String, presetNo: Int): UrgentReadStatusResponse {
        val state: UrgentReadState = when {
            hasReadyCache(userIgn, presetNo) -> UrgentReadState.Ready
            getNegativeCache(userIgn) -> UrgentReadState.NotFound
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

    private fun hasReadyCache(userIgn: String, presetNo: Int): Boolean =
        redisTemplate.hasKey(cacheKey(userIgn, presetNo))

    private fun queuePosition(userIgn: String): Long? =
        redisTemplate.opsForZSet().rank(URGENT_STATUS_QUEUE_KEY, userIgn)?.plus(1)

    private fun estimateWaitSeconds(queuePosition: Long): Long {
        val throughput = properties.statusEstimatedThroughputPerSecond.takeIf { it > 0.0 } ?: 1.0
        return kotlin.math.ceil(queuePosition / throughput).toLong().coerceAtLeast(1)
    }

    private fun removeUrgentStatus(userIgn: String) {
        redisTemplate.opsForZSet().remove(URGENT_STATUS_QUEUE_KEY, userIgn)
    }
}
