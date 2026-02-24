package maple.expectation.infrastructure.concurrency

import lombok.RequiredArgsConstructor
import lombok.extern.slf4j.Slf4j.Slf4j
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import org.redisson.api.RBucket
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Supplier

/**
 * 분산 Single-Flight 서비스 (Issue #283 P0-4)
 *
 * <h3>Scale-out 대응</h3>
 *
 * <p>기존 SingleFlightExecutor는 인스턴스 레벨에서만 중복 계산을 방지합니다. 이 서비스는 Redis 기반 분산 락 + 캐시를 사용하여 멀티 인스턴스
 * 환경에서도 동일 키에 대한 중복 계산을 방지합니다.
 *
 * <h4>동작 방식</h4>
 *
 * <pre>
 * 1. Redis 캐시 확인 → HIT: 즉시 반환
 * 2. 분산 락 획득 시도 → 실패: 캐시 재확인 후 대기
 * 3. 계산 실행 → 결과를 Redis에 캐시 (short TTL)
 * 4. 락 해제
 * </pre>
 *
 * @see SingleFlightExecutor 인스턴스 레벨 Single-Flight (비동기)
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DistributedSingleFlightService(
    private val executor: LogicExecutor,
    private val checkedExecutor: CheckedLogicExecutor,
    private val lockStrategy: LockStrategy,
    private val redissonClient: RedissonClient
) {
    companion object {
        private val DEFAULT_CACHE_TTL: Duration = Duration.ofSeconds(30)
        private const val CACHE_PREFIX = "{single-flight}:result:"
        private const val MAX_RETRIES = 6
        private const val BASE_DELAY_MS = 50L
    }

    /**
     * 분산 Single-Flight 실행
     *
     * @param key 계산 식별 키
     * @param computation 실제 계산 로직
     * @param cacheTtl 결과 캐시 TTL
     * @param T 결과 타입 (Serializable 필수)
     * @return 계산 결과
     */
    fun <T> executeOrShare(key: String, computation: Supplier<T>, cacheTtl: Duration): T {
        return executor.execute(
            { doExecuteOrShare(key, computation, cacheTtl) },
            TaskContext.of("SingleFlight", "Distributed", key)
        )
    }

    /** 분산 Single-Flight 실행 (기본 TTL 30초) */
    fun <T> executeOrShare(key: String, computation: Supplier<T>): T {
        return executeOrShare(key, computation, DEFAULT_CACHE_TTL)
    }

    private fun <T> doExecuteOrShare(key: String, computation: Supplier<T>, cacheTtl: Duration): T {
        if (key.isBlank()) {
            throw IllegalArgumentException("Key must not be null or empty")
        }
        val cacheKey = CACHE_PREFIX + key

        // Step 1: Check Redis cache
        val cached = getCachedResult<T>(cacheKey)
        if (cached != null) {
            log.debug("[DistributedSingleFlight] Cache HIT: {}", key)
            return cached
        }

        // Step 2: Acquire distributed lock and compute
        // If lock timeout occurs, retry cache reads (another instance may be computing)
        return executor.executeOrCatch(
            {
                lockStrategy.executeWithLock(
                    "single-flight:$key",
                    5,
                    30
                ) { computeAndCache(key, cacheKey, computation, cacheTtl) }
            },
            { e ->
                if (e is DistributedLockException) {
                    log.debug("[DistributedSingleFlight] Lock timeout, retrying cache read: {}", key)
                    executor.execute(
                        { retryCacheRead(cacheKey, key) },
                        TaskContext.of("SingleFlight", "RetryCacheRead", key)
                    )
                } else {
                    throw DistributedLockException("Lock execution failed", e)
                }
            },
            TaskContext.of("SingleFlight", "ExecuteWithLock", key)
        )
    }

    /**
     * Retry cache read with exponential backoff when lock acquisition fails.
     *
     * <p>This handles the case where another instance is computing the result and we should wait for
     * it to complete instead of failing immediately.
     *
     * @param cacheKey Redis cache key
     * @param originalKey Original key for logging
     * @return Cached result or throws if computation fails
     */
    private fun <T> retryCacheRead(cacheKey: String, originalKey: String): T {
        return getCachedResultWithRetry<T>(cacheKey, originalKey)
    }

    /**
     * Retry cache read with exponential backoff when lock acquisition fails.
     *
     * <p>This handles the case where another instance is computing the result and we should wait for
     * it to complete instead of failing immediately.
     *
     * @param cacheKey Redis cache key
     * @param originalKey Original key for logging
     * @return Cached result or throws if computation fails
     */
    private fun <T> getCachedResultWithRetry(cacheKey: String, originalKey: String): T {
        for (attempt in 0 until MAX_RETRIES) {
            val cached = getCachedResult<T>(cacheKey)
            if (cached != null) {
                log.debug(
                    "[DistributedSingleFlight] Cache HIT after retry (attempt {}): {}",
                    attempt + 1,
                    originalKey
                )
                return cached
            }

            sleepWithBackoff(attempt, originalKey)
        }

        return handleFinalRetry<T>(cacheKey, originalKey)
    }

    /** Sleep with exponential backoff during cache retry. */
    private fun <T> sleepWithBackoff(attempt: Int, originalKey: String) {
        val delayMs = BASE_DELAY_MS * (1L shl attempt) // Exponential backoff: 50ms, 100ms, 200ms, 400ms...
        val jitter = ThreadLocalRandom.current().nextLong(0, delayMs / 4)
        val totalDelay = delayMs + jitter

        // Use LogicExecutor pattern instead of direct Thread.sleep
        executor.executeVoid({
            try {
                Thread.sleep(totalDelay)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DistributedLockException(
                    "Cache read retry interrupted [key=$originalKey]", ie
                )
            }
        }, TaskContext.of("SingleFlight", "SleepBackoff", attempt.toString()))
    }

    /** Handle final retry after all exponential backoff attempts. */
    private fun <T> handleFinalRetry(cacheKey: String, originalKey: String): T {
        val finalCached = getCachedResult<T>(cacheKey)
        if (finalCached != null) {
            log.debug("[DistributedSingleFlight] Cache HIT after final retry: {}", originalKey)
            return finalCached
        }

        log.error(
            "[DistributedSingleFlight] Cache miss after all retries, computation may have failed: {}",
            originalKey
        )
        throw DistributedLockException(
            "Failed to acquire lock and no cached result available after " +
                    MAX_RETRIES + " retries [key=$originalKey]"
        )
    }

    private fun <T> getCachedResult(cacheKey: String): T? {
        return executor.executeOrDefault(
            {
                val bucket: RBucket<T> = redissonClient.getBucket(cacheKey)
                bucket.get()
            },
            null,
            TaskContext.of("SingleFlight", "CacheGet")
        )
    }

    private fun <T> computeAndCache(
        key: String,
        cacheKey: String,
        computation: Supplier<T>,
        cacheTtl: Duration
    ): T {
        // Double-check cache after acquiring lock
        val existing = getCachedResult<T>(cacheKey)
        if (existing != null) {
            log.debug("[DistributedSingleFlight] Cache HIT after lock: {}", key)
            return existing
        }

        val result = computation.get()

        // Cache result with TTL
        executor.executeVoid(
            { redissonClient.getBucket<T>(cacheKey).set(result, cacheTtl) },
            TaskContext.of("SingleFlight", "CacheSet", key)
        )

        log.debug("[DistributedSingleFlight] Computed and cached: {}", key)
        return result
    }
}
