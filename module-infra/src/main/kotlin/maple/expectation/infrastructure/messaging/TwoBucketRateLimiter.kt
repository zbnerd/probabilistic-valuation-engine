package maple.expectation.infrastructure.messaging

import maple.expectation.common.resource.ResourceLoader
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.ratelimit.exception.RateLimitExceededException
import org.redisson.api.RBucket
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Two-bucket rate limiter combining Token Bucket and Leaky Bucket algorithms.
 *
 * <p><strong>Design Pattern:</strong> Strategy Pattern with Lua Script atomic execution. Provides
 * both burst handling (Token Bucket) and sustained rate limiting (Leaky Bucket).
 *
 * <p><strong>Redis Cluster Compatibility:</strong> Uses Hash Tag {userId} pattern to ensure all
 * keys map to the same cluster slot. (Section 8-1: infrastructure.md)
 *
 * <p><strong>Algorithm:</strong>
 *
 * <ul>
 *   <li>Token Bucket: Allows burst traffic up to capacity
 *   <li>Leaky Bucket: Refills tokens at constant rate (sustained RPS limit)
 *   <li>Hybrid: Best of both - burst allowance + steady rate enforcement
 * </ul>
 *
 * <p><strong>CLAUDE.md Section 4 Compliance:</strong>
 *
 * <ul>
 *   <li>SRP: Single responsibility - rate limiting only
 *   <li>OCP: Pluggable algorithm via Lua Script
 *   <li>LogicExecutor: Exception handling via executeWithTranslation
 * </ul>
 *
 * <h3>Lua Script Contract (rate_limit_check.lua):</h3>
 *
 * <pre>
 * KEYS[1] = {event:rate}:{userId}
 * ARGV[1] = requests
 * ARGV[2] = capacity (burst size)
 * ARGV[3] = refillRate (tokens per second)
 * ARGV[4] = currentTimeSeconds
 * ARGV[5] = ttlSeconds
 *
 * Returns: {status, remainingTokens, retryAfterSeconds}
 * </pre>
 *
 * <h3>Configuration:</h3>
 *
 * <ul>
 *   <li>{@code app.messaging.rate-limit.capacity}: Burst capacity (default: 500)
 *   <li>{@code app.messaging.rate-limit.refill-rate}: Sustained RPS (default: 500)
 *   <li>{@code app.messaging.rate-limit.ttl-seconds}: State TTL (default: 3600)
 * </ul>
 *
 * @see maple.expectation.infrastructure.messaging.UpdateRequestCoalescer
 * @see maple.expectation.error.exception.RateLimitExceededException
 */
@Component
class TwoBucketRateLimiter(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val resourceLoader: ResourceLoader,
    @Value("\${app.messaging.rate-limit.capacity:500}") private val capacity: Int,
    @Value("\${app.messaging.rate-limit.refill-rate:500}") private val refillRate: Int,
    @Value("\${app.messaging.rate-limit.ttl-seconds:3600}") private val ttlSeconds: Int
) {
    private val logger = LoggerFactory.getLogger(TwoBucketRateLimiter::class.java)

    /**
     * Result of rate limit check.
     *
     * @param allowed Whether request is allowed
     * @param remainingTokens Tokens remaining in bucket
     * @param retryAfterSeconds Seconds until next token available (0 if allowed)
     */
    data class RateLimitResult(
        val allowed: Boolean,
        val remainingTokens: Int,
        val retryAfterSeconds: Int
    ) {
        companion object {
            fun allowed(remainingTokens: Int) = RateLimitResult(true, remainingTokens, 0)
            fun rejected(retryAfterSeconds: Int) = RateLimitResult(false, 0, retryAfterSeconds)
        }
    }

    /**
     * Check if request should be rate limited.
     *
     * <p>Uses Lua Script for atomic token bucket operations.
     *
     * @param userId User identifier (for Hash Tag)
     * @param requests Number of tokens requested
     * @return RateLimitResult with decision and metadata
     * @throws RateLimitExceededException if rate limit exceeded
     */
    fun checkLimit(userId: String, requests: Int): RateLimitResult {
        return executor.executeWithTranslation(
            { checkLimitInternal(userId, requests) },
            { cause, _ -> translateRateLimitException(cause) },
            TaskContext.of("TwoBucketRateLimiter", "CheckLimit", userId)
        )
    }

    /**
     * Internal rate limit check with checked exceptions.
     *
     * <p>Loads Lua Script and executes with Redisson RScript.
     */
    private fun checkLimitInternal(userId: String, requests: Int): RateLimitResult {
        // Load Lua Script
        val luaScript = resourceLoader.loadResourceAsString(LUA_RATE_LIMIT_CHECK)

        // Build Hash Tag key for Redis Cluster compatibility
        val rateKey = buildRateKey(userId)

        // Current time in seconds (Lua script precision)
        val currentTime = System.currentTimeMillis() / 1000

        val script = redissonClient.getScript(StringCodec.INSTANCE)

        // Execute Lua Script
        @Suppress("UNCHECKED_CAST")
        val result = script.eval(
            RScript.Mode.READ_WRITE,
            luaScript,
            RScript.ReturnType.MULTI,
            listOf(rateKey),
            requests.toString(),
            capacity.toString(),
            refillRate.toString(),
            currentTime.toString(),
            ttlSeconds.toString()
        ) as List<Any>

        // Parse result: {status, remainingTokens, retryAfterSeconds}
        val status = result[0] as String
        val remainingTokens = (result[1] as String).toInt()
        val retryAfterSeconds = (result[2] as String).toInt()

        val allowed = "ALLOWED" == status

        logger.debug(
            "[TwoBucketRateLimiter] Rate limit check: userId={}, allowed={}, remainingTokens={}, retryAfter={}",
            userId, allowed, remainingTokens, retryAfterSeconds
        )

        if (!allowed) {
            logger.warn(
                "[TwoBucketRateLimiter] Rate limit exceeded: userId={}, retryAfter={}s, capacity={}, refillRate={}",
                userId, retryAfterSeconds, capacity, refillRate
            )
        }

        return if (allowed) {
            RateLimitResult.allowed(remainingTokens)
        } else {
            RateLimitResult.rejected(retryAfterSeconds)
        }
    }

    /**
     * Check limit and throw exception if exceeded.
     *
     * <p>Convenience method for direct use in business logic.
     *
     * @param userId User identifier
     * @param requests Number of tokens requested
     * @throws RateLimitExceededException if rate limit exceeded
     */
    fun checkLimitOrThrow(userId: String, requests: Int) {
        val result = checkLimit(userId, requests)
        if (!result.allowed) {
            throw RateLimitExceededException(userId, result.retryAfterSeconds, capacity, refillRate)
        }
    }

    /**
     * Get current token count for a user.
     *
     * <p>Non-blocking read operation for monitoring/decision making.
     *
     * @param userId User identifier
     * @return Current token count (0 if no data)
     */
    fun getCurrentTokens(userId: String): Int {
        return executor.executeOrDefault(
            { getCurrentTokensInternal(userId) },
            capacity,
            TaskContext.of("TwoBucketRateLimiter", "GetCurrentTokens", userId)
        )
    }

    private fun getCurrentTokensInternal(userId: String): Int {
        val rateKey = buildRateKey(userId)
        val bucket: RBucket<String> = redissonClient.getBucket(rateKey, StringCodec.INSTANCE)
        val tokens = bucket.get()
        return tokens?.toString()?.toInt() ?: capacity
    }

    /**
     * Reset rate limit state for a user (admin operation).
     *
     * <p>Clears token bucket state, allowing full capacity.
     *
     * @param userId User identifier
     */
    fun reset(userId: String) {
        executor.executeVoidJava(
            {
                val rateKey = buildRateKey(userId)
                val bucket: RBucket<String> = redissonClient.getBucket(rateKey, StringCodec.INSTANCE)
                bucket.delete()
                logger.info("[TwoBucketRateLimiter] Reset rate limit: userId={}", userId)
            },
            TaskContext.of("TwoBucketRateLimiter", "Reset", userId)
        )
    }

    /**
     * Exception translator for rate limit errors.
     *
     * <p>Converts generic script exceptions to domain-specific RateLimitExceededException.
     *
     * @param cause Original exception
     * @return Translated domain exception
     */
    private fun translateRateLimitException(cause: Throwable): RateLimitExceededException {
        if (cause is RateLimitExceededException) {
            return cause
        }
        // Script execution error - wrap with context
        return RateLimitExceededException("unknown", 0, capacity, refillRate, cause)
    }

    // Hash Tag pattern for Redis Cluster (Section 8-1)
    private fun buildRateKey(userId: String): String = "{event:rate}:$userId"

    companion object {
        private const val LUA_RATE_LIMIT_CHECK = "lua/event/rate_limit_check.lua"
    }
}
