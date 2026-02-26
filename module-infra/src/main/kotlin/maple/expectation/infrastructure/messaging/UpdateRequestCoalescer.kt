package maple.expectation.infrastructure.messaging

import maple.expectation.common.resource.ResourceLoader
import maple.expectation.error.exception.AtomicFetchException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import org.redisson.api.RBucket
import org.redisson.api.RMap
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Coalesces multiple update requests for the same user using Redis Lua Script.
 *
 * <p><strong>Design Pattern:</strong> Command Pattern with Atomic Lua Script execution. Prevents
 * duplicate processing and batches requests for efficiency.
 *
 * <p><strong>Redis Cluster Compatibility:</strong> Uses Hash Tag {userId} pattern to ensure all
 * keys map to the same cluster slot. (Section 8-1: infrastructure.md)
 *
 * <p><strong>Atomic Operations:</strong>
 *
 * <ul>
 *   <li>Deduplication: HGET prevents duplicate event IDs
 *   <li>Batching: HINCRBY tracks batch size
 *   <li>Safety: TTL prevents orphan keys from memory leaks
 * </ul>
 *
 * <p><strong>CLAUDE.md Section 4 Compliance:</strong>
 *
 * <ul>
 *   <li>SRP: Single responsibility - coalescing logic only
 *   <li>DIP: Depends on RedisClient abstraction
 *   <li>LogicExecutor: Exception handling via executeWithTranslation
 * </ul>
 *
 * <h3>Lua Script Contract (coalesce_add.lua):</h3>
 *
 * <pre>
 * KEYS[1] = {event:coalesce}:{userId}
 * KEYS[2] = {event:coalesce:counter}:{userId}
 * ARGV[1] = eventType
 * ARGV[2] = eventId
 * ARGV[3] = eventData
 * ARGV[4] = maxBatchSize
 * ARGV[5] = ttlSeconds
 *
 * Returns: {status, batchCount, shouldFlush}
 * </pre>
 *
 * @see maple.expectation.infrastructure.messaging.TwoBucketRateLimiter
 * @see maple.expectation.error.exception.AtomicFetchException
 */
@Component
class UpdateRequestCoalescer(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val resourceLoader: ResourceLoader
) {
    private val logger = LoggerFactory.getLogger(UpdateRequestCoalescer::class.java)

    /**
     * Result of coalesce operation.
     *
     * @param status Operation status (QUEUED, DUPLICATE)
     * @param batchCount Current number of requests in batch
     * @param shouldFlush Whether batch should be flushed
     */
    data class CoalesceResult(
        val status: String,
        val batchCount: Int,
        val shouldFlush: Boolean
    ) {
        fun isDuplicate() = "DUPLICATE" == status
        fun isQueued() = "QUEUED" == status
    }

    /**
     * Coalesce an update request for the specified user.
     *
     * <p>Uses Lua Script for atomic deduplication and batching.
     *
     * @param userId User identifier (for Hash Tag)
     * @param eventType Type of event (e.g., "CHARACTER_UPDATE")
     * @param eventId Unique event identifier
     * @param eventData Serialized event data
     * @param maxBatchSize Maximum batch size before forcing flush
     * @param ttlSeconds TTL for coalesced data (safety)
     * @return CoalesceResult with operation status
     * @throws AtomicFetchException if Lua Script execution fails
     */
    fun coalesce(
        userId: String,
        eventType: String,
        eventId: String,
        eventData: String,
        maxBatchSize: Int,
        ttlSeconds: Int
    ): CoalesceResult {
        return executor.executeWithTranslation(
            {
                coalesceInternal(
                    userId,
                    eventType,
                    eventId,
                    eventData,
                    maxBatchSize,
                    ttlSeconds
                )
            },
            ExceptionTranslator.forRedisScript(),
            TaskContext.of("UpdateRequestCoalescer", "Coalesce", userId)
        )
    }

    /**
     * Internal coalesce implementation with checked exceptions.
     *
     * <p>Loads Lua Script and executes with Redisson RScript.
     */
    private fun coalesceInternal(
        userId: String,
        eventType: String,
        eventId: String,
        eventData: String,
        maxBatchSize: Int,
        ttlSeconds: Int
    ): CoalesceResult {
        // Load Lua Script
        val luaScript = resourceLoader.loadResourceAsString(LUA_COALESCE_ADD)

        // Build Hash Tag keys for Redis Cluster compatibility
        val coalesceKey = buildCoalesceKey(userId)
        val counterKey = buildCounterKey(userId)

        val script = redissonClient.getScript(StringCodec.INSTANCE)

        // Execute Lua Script
        @Suppress("UNCHECKED_CAST")
        val result = script.eval(
            RScript.Mode.READ_WRITE,
            luaScript,
            RScript.ReturnType.MULTI,
            listOf(coalesceKey, counterKey),
            eventType,
            eventId,
            eventData,
            maxBatchSize.toString(),
            ttlSeconds.toString()
        ) as List<Any>

        // Parse result: {status, batchCount, shouldFlush}
        val status = result[0] as String
        val batchCount = (result[1] as String).toInt()
        val shouldFlush = "1" == result[2]

        logger.debug(
            "[UpdateRequestCoalescer] Coalesce result: userId={}, eventId={}, status={}, batchCount={}, shouldFlush={}",
            userId, eventId, status, batchCount, shouldFlush
        )

        return CoalesceResult(status, batchCount, shouldFlush)
    }

    /**
     * Get current batch count for a user.
     *
     * <p>Non-blocking read operation for monitoring/decision making.
     *
     * @param userId User identifier
     * @param eventType Event type to query
     * @return Current batch count (0 if no data)
     */
    fun getBatchCount(userId: String, eventType: String): Int {
        return executor.executeOrDefault(
            { getBatchCountInternal(userId, eventType) },
            0,
            TaskContext.of("UpdateRequestCoalescer", "GetBatchCount", userId)
        )
    }

    private fun getBatchCountInternal(userId: String, eventType: String): Int {
        val counterKey = buildCounterKey(userId)
        val bucket: RBucket<String> = redissonClient.getBucket("$counterKey:$eventType", StringCodec.INSTANCE)
        val count: String? = bucket.get()
        return count?.toString()?.toInt() ?: 0
    }

    /**
     * Flush coalesced batch for processing.
     *
     * <p>Atomically fetches and removes all events for the user.
     *
     * @param userId User identifier
     * @return List of event data (may be empty)
     */
    fun flushBatch(userId: String): List<String> {
        return executor.execute(
            { flushBatchInternal(userId) },
            TaskContext.of("UpdateRequestCoalescer", "FlushBatch", userId)
        )
    }

    private fun flushBatchInternal(userId: String): List<String> {
        val coalesceKey = buildCoalesceKey(userId)
        val counterKey = buildCounterKey(userId)

        // Fetch all events
        val map: RMap<String, String> = redissonClient.getMap(coalesceKey, StringCodec.INSTANCE)
        val events: List<String> = ArrayList(map.readAllMap().values)

        // Clear batch atomically
        map.delete()
        val bucket: RBucket<String> = redissonClient.getBucket(counterKey, StringCodec.INSTANCE)
        bucket.delete()

        logger.debug(
            "[UpdateRequestCoalescer] Flushed batch: userId={}, eventCount={}",
            userId,
            events.size
        )

        return events
    }

    // Hash Tag pattern for Redis Cluster (Section 8-1)
    private fun buildCoalesceKey(userId: String): String = "{event:coalesce}:$userId"

    private fun buildCounterKey(userId: String): String = "{event:coalesce:counter}:$userId"

    companion object {
        private const val LUA_COALESCE_ADD = "lua/event/coalesce_add.lua"
    }
}
