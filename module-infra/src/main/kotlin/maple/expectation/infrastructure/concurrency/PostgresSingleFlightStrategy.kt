package maple.expectation.infrastructure.concurrency

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * PostgreSQL-based Single Flight Strategy (xact lock edition)
 *
 * <p>Uses `pg_try_advisory_xact_lock` for atomic leadership claim,
 * eliminating session-scoped lock risks with HikariCP connection pools.
 *
 * <h4>Algorithm</h4>
 * <ol>
 *   <li>Claim leadership via xact lock in a short transaction (auto-released on commit)</li>
 *   <li>Register in-flight entry in ConcurrentHashMap for cross-request coordination</li>
 *   <li>Leader: execute task, cache result, schedule cleanup</li>
 *   <li>Follower: poll result cache or wait on leader's future with timeout</li>
 * </ol>
 *
 * <h4>Why xact locks work here</h4>
 * <p>Concurrent requests competing for the same key will serialize at the xact lock level.
 * After the lock transaction commits, subsequent requests use the `flights` ConcurrentHashMap
 * to detect in-flight leaders. This provides correct coordination without holding
 * session-scoped locks across async boundaries.
 *
 * <h4>Limitations</h4>
 * <p>Cross-instance coordination depends on xact lock overlap timing.
 * Duplicate execution across instances is possible but acceptable
 * (external API calls are idempotent).
 *
 * @see <a href="file:../../docs/adr/005-single-flight-hot-key.md">ADR-005 Single Flight + Hot Key Strategy</a>
 */
@Component
@ConditionalOnProperty(name = ["maple.infra.singleflight.impl"], havingValue = "postgres", matchIfMissing = false)
class PostgresSingleFlightStrategy(
    @Qualifier("lockJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val logicExecutor: LogicExecutor,
    @Qualifier("taskExecutor") private val taskExecutor: Executor,
    @Qualifier("lockTransactionTemplate")
    private val lockTransactionTemplate: TransactionTemplate,
    private val lockMetrics: LockMetrics,
) : SingleFlightStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresSingleFlightStrategy::class.java)
        private val DEFAULT_TIMEOUT = Duration.ofSeconds(10)
        private const val CACHE_TTL_SECONDS = 30L
        private const val POLL_INTERVAL_MS = 100L
    }

    /** In-flight request tracking. Key: request key, Value: leader's CompletableFuture. */
    private val flights = ConcurrentHashMap<String, CompletableFuture<Any>>()

    /** Completed result cache for followers (short-lived, ~30s). */
    private val resultCache = ConcurrentHashMap<String, CompletableFuture<Any>>()

    override fun <T> execute(key: String, supplier: Supplier<T>): T = executeAsync(key) { CompletableFuture.supplyAsync(supplier, taskExecutor) }.join()

    override fun <T> executeAsync(key: String, asyncSupplier: Supplier<CompletableFuture<T>>): CompletableFuture<T> {
        val context = TaskContext.of("SingleFlight", "Execute", key)
        val lockKey = "sf:$key"

        return logicExecutor.execute({
            if (claimLeadership(lockKey)) {
                executeAsLeader(key, asyncSupplier)
            } else {
                executeAsFollower(key, asyncSupplier)
            }
        }, context)
    }

    /**
     * Atomically claim leadership using xact-scoped advisory lock.
     * Lock is held only within the short transaction and auto-released on commit.
     * Subsequent coordination is maintained via `flights` ConcurrentHashMap.
     */
    private fun claimLeadership(lockKey: String): Boolean {
        val lockId = generateLockId(lockKey)
        val acquired = lockTransactionTemplate.execute {
            jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean::class.java,
                lockId,
            ) ?: false
        } ?: false

        if (acquired) {
            lockMetrics.recordLockAcquired("postgres")
        } else {
            lockMetrics.recordFailure("postgres")
        }
        return acquired
    }

    /**
     * Execute as Leader: Register in flights map, run task, cache result.
     * Uses ConcurrentHashMap.putIfAbsent for race-free leadership tracking.
     */
    private fun <T> executeAsLeader(
        key: String,
        asyncSupplier: Supplier<CompletableFuture<T>>,
    ): CompletableFuture<T> {
        val leaderFuture = CompletableFuture<T>()

        @Suppress("UNCHECKED_CAST")
        val entry = leaderFuture as CompletableFuture<Any>

        // Atomic registration: if another leader already registered, follow instead
        val existing = flights.putIfAbsent(key, entry)
        if (existing != null) {
            log.debug("🔄 [SingleFlight] Race lost, following instead: {}", maskKey(key))
            @Suppress("UNCHECKED_CAST")
            return existing as CompletableFuture<T>
        }

        log.debug("👑 [SingleFlight Leader] Claimed for key: {}", maskKey(key))

        asyncSupplier.get().whenComplete { result, error ->
            if (error == null && result != null) {
                @Suppress("UNCHECKED_CAST")
                resultCache[key] = CompletableFuture.completedFuture(result as Any)
                log.debug("💾 [SingleFlight Leader] Cached result: {}", maskKey(key))
            }

            // Propagate to leaderFuture so followers waiting on it get unblocked
            if (error != null) {
                leaderFuture.completeExceptionally(error)
            } else {
                @Suppress("UNCHECKED_CAST")
                (leaderFuture as CompletableFuture<Any?>).complete(result)
            }

            CompletableFuture.delayedExecutor(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
                .execute {
                    resultCache.remove(key)
                    flights.remove(key, entry)
                }

            lockMetrics.recordLockReleased("postgres")
            log.debug("🔓 [SingleFlight Leader] Completed: {}", maskKey(key))
        }

        return leaderFuture
    }

    /**
     * Execute as Follower: Wait for leader's result or timeout.
     */
    private fun <T> executeAsFollower(
        key: String,
        asyncSupplier: Supplier<CompletableFuture<T>>,
    ): CompletableFuture<T> {
        log.debug("😴 [SingleFlight Follower] Waiting: {}", maskKey(key))

        // Check in-flight leader
        val inFlight = flights[key]
        if (inFlight != null) {
            @Suppress("UNCHECKED_CAST")
            return inFlight as CompletableFuture<T>
        }

        // Check cached result (fast path)
        val cached = resultCache[key]
        if (cached != null) {
            log.debug("✅ [SingleFlight Follower] Cached result: {}", maskKey(key))
            @Suppress("UNCHECKED_CAST")
            return cached as CompletableFuture<T>
        }

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

    private fun <T> waitForLeaderResult(key: String): CompletableFuture<T> {
        val resolved = resolveLeaderResult<T>(key)
        if (resolved != null) return resolved

        return scheduleLeaderPoll(key, System.currentTimeMillis())
    }

    private fun <T> resolveLeaderResult(key: String): CompletableFuture<T>? {
        val inFlight = flights[key]
        if (inFlight != null) {
            @Suppress("UNCHECKED_CAST")
            return inFlight as CompletableFuture<T>
        }

        val cached = resultCache[key]
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            return cached as CompletableFuture<T>
        }

        return null
    }

    private fun <T> scheduleLeaderPoll(key: String, startTime: Long): CompletableFuture<T> {
        val future = CompletableFuture<T>()

        fun poll() {
            if (future.isDone) return

            val resolved = resolveLeaderResult<T>(key)
            if (resolved != null) {
                resolved.whenComplete { result, error ->
                    if (error != null) {
                        future.completeExceptionally(error)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        future.complete(result as T)
                    }
                }
                return
            }

            if (System.currentTimeMillis() - startTime >= DEFAULT_TIMEOUT.toMillis()) {
                future.completeExceptionally(TimeoutException("Leader result not available within ${DEFAULT_TIMEOUT.seconds}s"))
                return
            }

            CompletableFuture.delayedExecutor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .execute { poll() }
        }

        poll()
        return future
    }

    private fun generateLockId(key: String): Long = jdbcTemplate.queryForObject(
        "SELECT hashtext(?)",
        Long::class.java,
        key,
    ) ?: key.hashCode().toLong()

    private fun maskKey(key: String): String {
        if (key.length <= 8) return "***"
        return key.substring(0, 4) + "***" + key.substring(key.length - 4)
    }
}
