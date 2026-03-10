package maple.expectation.infrastructure.concurrency

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.PostgresLockStrategy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * PostgreSQL-based Single Flight Strategy
 *
 * <p>Replaces in-memory SingleFlightExecutor for scale-out support.
 * Uses PostgreSQL advisory locks for distributed coordination across multiple instances.
 *
 * <h4>Algorithm</h4>
 * <ol>
 *   <li>Hash request key → advisory lock ID (via PostgresLockStrategy)</li>
 *   <li>Try acquire advisory lock with {@code tryLockImmediately()}</li>
 *   <li>If acquired (Leader): Execute request, cache result, release lock</li>
 *   <li>If not acquired (Follower): Wait for result with timeout, then fallback to independent execution</li>
 * </ol>
 *
 * <h4>Leader-Follower Pattern</h4>
 * <ul>
 *   <li><b>Leader:</b> Acquires lock, executes task, stores result in local cache for 30s</li>
 *   <li><b>Follower:</b> Polls local cache for result (100ms intervals), times out after 10s</li>
 * </ul>
 *
 * <h4>Fallback Strategy</h4>
 * <p>If timeout occurs, followers execute independently to prevent blocking.
 * This is acceptable because:
 * <ul>
 *   <li>The duplicate execution window is bounded (10s)</li>
 *   <li>External API calls are idempotent (Nexon Open API)</li>
 *   <li>System remains available under leader failure scenarios</li>
 * </ul>
 *
 * <h4>Limitations & Future Improvements</h4>
 * <p>Current implementation uses local cache + polling. Future versions may integrate
 * PGMQ for reliable result broadcasting (ADR-005 Phase 2.2).
 *
 * @see maple.expectation.infrastructure.lock.PostgresLockStrategy
 * @see <a href="file:../../docs/adr/005-single-flight-hot-key.md">ADR-005 Single Flight + Hot Key Strategy</a>
 */
@Component
@ConditionalOnProperty(name = ["maple.infra.singleflight.impl"], havingValue = "postgres", matchIfMissing = false)
class PostgresSingleFlightStrategy(
    @Qualifier("postgresAdvisoryLockStrategy")
    private val lockStrategy: PostgresLockStrategy,
    private val executor: LogicExecutor,
) : SingleFlightStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresSingleFlightStrategy::class.java)
        private val DEFAULT_TIMEOUT = Duration.ofSeconds(10)
        private const val CACHE_TTL_SECONDS = 30L
        private const val POLL_INTERVAL_MS = 100L
    }

    /**
     * Local result cache for followers (short-lived, ~30s)
     *
     * <p>Key: request key, Value: CompletableFuture with result
     * This cache is NOT shared across instances. It's a simple optimization
     * to reduce redundant polling when followers arrive shortly after leader completes.
     */
    private val resultCache = ConcurrentHashMap<String, CompletableFuture<Any>>()

    override fun <T> execute(key: String, supplier: Supplier<T>): T = executeAsync(key) { CompletableFuture.supplyAsync(supplier) }.join()

    override fun <T> executeAsync(key: String, asyncSupplier: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val context = TaskContext.of("SingleFlight", "Execute", key)
        val lockKey = "sf:$key"

        return executor.execute(
            {
                // Try to become leader
                if (lockStrategy.tryLockImmediately(lockKey, CACHE_TTL_SECONDS)) {
                    executeAsLeader(key, lockKey, asyncSupplier)
                } else {
                    executeAsFollower(key, asyncSupplier)
                }
            },
            context,
        )
    }

    /**
     * Execute as Leader: Acquired lock, run task, cache result
     */
    private fun <T> executeAsLeader(
        key: String,
        lockKey: String,
        asyncSupplier: Supplier<CompletableFuture<T>>,
    ): CompletableFuture<T> {
        log.debug("👑 [SingleFlight Leader] Acquired lock for key: {}", maskKey(key))

        return asyncSupplier
            .get()
            .whenComplete { result, error ->
                // Cache result for followers (success only)
                if (error == null && result != null) {
                    @Suppress("UNCHECKED_CAST")
                    resultCache[key] = CompletableFuture.completedFuture(result as Any)
                    log.debug("💾 [SingleFlight Leader] Cached result for key: {}", maskKey(key))
                }

                // Schedule cache cleanup after TTL
                CompletableFuture.delayedExecutor(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                    .execute { resultCache.remove(key) }

                // Release lock
                lockStrategy.unlock(lockKey)
                log.debug("🔓 [SingleFlight Leader] Released lock for key: {}", maskKey(key))
            }
    }

    /**
     * Execute as Follower: Wait for leader's result or timeout and execute independently
     */
    private fun <T> executeAsFollower(
        key: String,
        asyncSupplier: Supplier<CompletableFuture<T>>,
    ): CompletableFuture<T> {
        log.debug("😴 [SingleFlight Follower] Waiting for leader: {}", maskKey(key))

        // Check cached result first (fast path)
        val cached = resultCache[key]
        if (cached != null) {
            log.debug("✅ [SingleFlight Follower] Found cached result: {}", maskKey(key))
            @Suppress("UNCHECKED_CAST")
            return cached as CompletableFuture<T>
        }

        // Wait for leader with timeout, then fallback to independent execution
        return waitForLeaderResult<T>(key)
            .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionallyCompose { e ->
                when (e.cause) {
                    is TimeoutException -> {
                        log.warn("⏰ [SingleFlight Follower] Timeout, executing independently: {}", maskKey(key))
                        asyncSupplier.get()
                    }
                    else -> {
                        log.error("❌ [SingleFlight Follower] Unexpected error: {}", maskKey(key), e.cause)
                        CompletableFuture.failedFuture(e.cause ?: e)
                    }
                }
            }
    }

    /**
     * Poll for leader's result with timeout
     *
     * <p>This is a simple polling implementation. Future versions may use
     * PGMQ or PostgreSQL LISTEN/NOTIFY for event-driven result delivery.
     */
    private fun <T> waitForLeaderResult(key: String): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val startTime = System.currentTimeMillis()

        CompletableFuture.runAsync {
            while (System.currentTimeMillis() - startTime < DEFAULT_TIMEOUT.toMillis()) {
                val cached = resultCache[key]
                if (cached != null) {
                    @Suppress("UNCHECKED_CAST")
                    future.complete(cached.get() as T)
                    return@runAsync
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            future.completeExceptionally(TimeoutException("Leader result not available within ${DEFAULT_TIMEOUT.seconds}s"))
        }

        return future
    }

    /**
     * Mask sensitive key for logging (show first 4 and last 4 chars)
     */
    private fun maskKey(key: String): String {
        if (key.length <= 8) return "***"
        return key.substring(0, 4) + "***" + key.substring(key.length - 4)
    }
}
