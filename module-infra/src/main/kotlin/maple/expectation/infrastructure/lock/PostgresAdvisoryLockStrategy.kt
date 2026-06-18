package maple.expectation.infrastructure.lock

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.stream.Collectors

/**
 * PostgreSQL Advisory Lock Strategy
 *
 * <p>Replaces Redis RCountDownLatch for leader election during character synchronization.
 *
 * <h3>Lock Scope Design (#628)</h3>
 * <ul>
 *   <li><b>XACT-SCOPED</b>: [executeWithLock], [executeWithLeaderElection] use
 *       {@code pg_try_advisory_xact_lock} within [TransactionTemplate].
 *       Lock auto-released on tx commit/rollback.</li>
 *   <li><b>SESSION-SCOPED</b>: [tryLockImmediately], [unlock] use
 *       {@code pg_try_advisory_lock} (session scope).
 *       Required by async patterns (e.g., PostgresSingleFlightStrategy).</li>
 * </ul>
 *
 * <h3>Lock ID Generation</h3>
 * <p>Uses {@code hashtext(key)} to generate a consistent 64-bit lock ID from a string key.
 *
 * <h3>PostgreSQL-only Mode</h3>
 * <p>When Redis is disabled, this becomes the primary lock strategy.
 */
@Primary
@Component
class PostgresAdvisoryLockStrategy(
    @Qualifier("lockJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    @Qualifier("lockTransactionTemplate")
    private val lockTransactionTemplate: TransactionTemplate,
    private val lockMetrics: LockMetrics,
) : LockStrategy,
    LeaderElectionStrategy {

    /**
     * Session-scoped lock registry: maps logical key -> generated lockId so that
     * [unlockAsync] knows which PG advisory lock to release for [tryLockImmediatelyAsync].
     * Session-scoped locks require explicit [pg_advisory_unlock]; xact-scoped locks
     * do not (auto-released on tx commit/rollback).
     */
    private val lockSessionRegistry = ConcurrentHashMap<String, Long>()

    /**
     * Executor for short-lived JDBC round-trips in async paths.
     * Uses the common ForkJoinPool; this is fine because each call is a brief
     * blocking JDBC query (HikariCP connection check-out + single SQL round-trip),
     * not a long-running compute task. Spring `@Async` is not appropriate here
     * because the lock acquisition polling is in-process and bounded by waitTime.
     */
    private val jdbcExecutor: Executor = java.util.concurrent.ForkJoinPool.commonPool()

    // ==================== Async Lock Methods (preferred) ====================

    /**
     * [SESSION-SCOPED] Async: Execute supplier with advisory lock.
     *
     * Uses `pg_try_advisory_lock` (session scope) + explicit `pg_advisory_unlock`
     * in `whenComplete`. Caller thread is never blocked.
     */
    override fun <T> executeWithLockAsync(
        key: String,
        waitTime: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> = tryAcquireSessionLockWithPollAsync(key, waitTime, leaseTime)
        .thenCompose { lockId ->
            if (lockId == null) {
                lockMetrics.recordFailure("postgres")
                CompletableFuture.failedFuture(
                    DistributedLockException("Failed to acquire lock within timeout: $key"),
                )
            } else {
                supplier().whenComplete { _, _ ->
                    releaseSessionLock(key, lockId)
                }
            }
        }

    override fun <T> executeWithLockAsync(
        key: String,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> = executeWithLockAsync(key, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, supplier)

    /**
     * [SESSION-SCOPED] Async: Try to acquire advisory lock (non-blocking).
     * MUST call [unlockAsync] explicitly to release.
     */
    override fun tryLockImmediatelyAsync(key: String, leaseTime: Long): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({ tryAcquireSessionLockOnce(key, leaseTime) != null }, jdbcExecutor)

    /**
     * [SESSION-SCOPED] Async: Release previously acquired session-scoped lock.
     */
    override fun unlockAsync(key: String): CompletableFuture<Void> {
        val lockId = lockSessionRegistry.remove(key)
            ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.runAsync({ releaseSessionLock(key, lockId) }, jdbcExecutor)
    }

    /**
     * Async: Default implementation - alphabetic sort + composite key + executeWithLockAsync.
     * Concrete impls may override for true multi-lock semantics.
     */
    override fun <T> executeWithOrderedLocksAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val compositeKey = keys.stream().sorted().collect(Collectors.joining(":"))
        val timeoutSeconds = timeUnit.toSeconds(totalTimeout)
        return executeWithLockAsync(compositeKey, timeoutSeconds, leaseTime, supplier)
    }

    /**
     * Async: Execute with leader election pattern (session-scoped lock).
     *
     * Leader acquires `pg_try_advisory_lock` and runs [leaderSupplier];
     * followers wait for the leader's session lock to release, then run [followerSupplier].
     */
    override fun <T> executeWithLeaderElectionAsync(
        key: String,
        waitTimeSeconds: Int,
        leaderSupplier: () -> CompletableFuture<T>,
        followerSupplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val lockId = tryAcquireSessionLockOnce(key, DEFAULT_LEASE_TIME)
        return if (lockId != null) {
            log.info("👑 [Leader] Acquired session lock for key: {}", key)
            lockMetrics.recordLockAcquired("postgres")
            leaderSupplier().whenComplete { _, _ ->
                releaseSessionLock(key, lockId)
                lockMetrics.recordLockReleased("postgres")
            }
        } else {
            log.info("😴 [Follower] Waiting for leader completion: key={}, timeout={}s", key, waitTimeSeconds)
            waitForLeaderSessionReleaseAsync(key, waitTimeSeconds).thenCompose {
                followerSupplier()
            }
        }
    }

    // ==================== XACT-SCOPED Lock Methods (Deprecated) ====================

    /**
     * [XACT-SCOPED] Execute task with advisory lock.
     *
     * Uses `pg_try_advisory_xact_lock` within a TransactionTemplate.
     * Lock is automatically released when the transaction commits or rolls back.
     *
     * @deprecated Use [executeWithLockAsync] — xact-scoped blocks the caller thread on
     *             `task.get()`. Migration: see interface deprecation note.
     */
    @Deprecated("Use executeWithLockAsync — xact-scoped blocks the caller thread on task.get()")
    override fun <T> executeWithLock(key: String, waitTime: Long, leaseTime: Long, task: ThrowingSupplier<T>): T {
        val lockId = generateLockId(key)
        val context = TaskContext.of("AdvisoryLock", "ExecuteWithLock", key)
        val startTime = System.currentTimeMillis()
        val timeoutMs = waitTime * 1000L

        // Poll until lock acquired or timeout
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            var acquired = false
            val result = lockTransactionTemplate.execute {
                val lockAcquired = tryAcquireXactLock(lockId)
                acquired = lockAcquired
                if (lockAcquired) {
                    log.debug("🔒 [AdvisoryLock] Acquired xact lock for key: {}", key)
                    lockMetrics.recordLockAcquired("postgres")
                    executor.execute({ task.get() }, context)
                } else {
                    null
                }
            }
            if (acquired) {
                lockMetrics.recordWaitTime(System.currentTimeMillis() - startTime, "postgres")
            }
            if (result != null) {
                lockMetrics.recordLockReleased("postgres")
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            parkForPollOrThrow(key)
        }

        lockMetrics.recordFailure("postgres")
        throw DistributedLockException("Failed to acquire lock within timeout: $key")
    }

    @Deprecated("Use executeWithLockAsync")
    override fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T = executeWithLock(key, 10, 20, task)

    /**
     * [XACT-SCOPED] Execute with leader election pattern.
     *
     * Leader acquires `pg_try_advisory_xact_lock` and executes leaderTask.
     * Followers poll until leader's transaction commits (releasing the lock),
     * then execute followerTask.
     *
     * @deprecated Use [executeWithLeaderElectionAsync] — xact-scoped blocks the caller thread
     *             on `task.get()`.
     */
    @Deprecated("Use executeWithLeaderElectionAsync — xact-scoped blocks the caller thread on task.get()")
    override fun <T> executeWithLeaderElection(
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
    ): T {
        val lockId = generateLockId(key)
        val context = TaskContext.of("AdvisoryLock", "ElectLeader", key)
        val startTime = System.currentTimeMillis()

        // Try to become leader in a single transaction
        val leaderResult = lockTransactionTemplate.execute {
            val acquired = tryAcquireXactLock(lockId)
            if (acquired) {
                log.info("👑 [Leader] Acquired xact lock for key: {}", key)
                lockMetrics.recordLockAcquired("postgres")
                executor.execute({ leaderTask.get() }, context)
            } else {
                null
            }
        }

        if (leaderResult != null) {
            lockMetrics.recordWaitTime(System.currentTimeMillis() - startTime, "postgres")
            lockMetrics.recordLockReleased("postgres")
            @Suppress("UNCHECKED_CAST")
            return leaderResult as T
        }

        // Follower: wait for leader's transaction to commit (lock auto-released)
        log.info("😴 [Follower] Waiting for leader completion: key={}, timeout={}s", key, waitTimeSeconds)
        val timeoutMs = waitTimeSeconds * 1000L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val leaderDone = lockTransactionTemplate.execute { tryAcquireXactLock(lockId) } ?: false
            if (leaderDone) {
                log.info("✅ [Follower] Leader completed, proceeding: key={}", key)
                break
            }
            parkForPollOrThrow(key)
        }

        return executor.execute({ followerTask.get() }, context)
    }

    // ==================== SESSION-SCOPED Lock Methods (Deprecated) ====================

    /**
     * [SESSION-SCOPED] Try to acquire advisory lock (non-blocking).
     *
     * Uses `pg_try_advisory_lock` (session scope).
     * MUST call [unlock] explicitly to release.
     * Retained for async patterns where lock must outlive the method call.
     *
     * @deprecated Use [tryLockImmediatelyAsync].
     */
    @Deprecated("Use tryLockImmediatelyAsync")
    override fun tryLockImmediately(key: String, leaseTime: Long): Boolean {
        val lockId = generateLockId(key)
        val acquired = jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_lock(?)",
            Boolean::class.java,
            lockId,
        ) ?: false
        if (acquired) {
            lockMetrics.recordLockAcquired("postgres")
        } else {
            lockMetrics.recordFailure("postgres")
        }
        return acquired
    }

    /**
     * [SESSION-SCOPED] Release session-scoped advisory lock.
     *
     * Only meaningful after [tryLockImmediately]. No-op for xact-scoped locks
     * (those auto-release on transaction commit/rollback).
     *
     * @deprecated Use [unlockAsync].
     */
    @Deprecated("Use unlockAsync")
    override fun unlock(key: String) {
        val lockId = generateLockId(key)
        jdbcTemplate.queryForObject(
            "SELECT pg_advisory_unlock(?)",
            Boolean::class.java,
            lockId,
        )
        lockMetrics.recordLockReleased("postgres")
        log.debug("🔓 [AdvisoryLock] Unlocked key: {}", key)
    }

    // ==================== Private Methods ====================

    /**
     * Try to acquire transaction-scoped advisory lock.
     * Must be called within an active transaction.
     */
    private fun tryAcquireXactLock(lockId: Long): Boolean = jdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_xact_lock(?)",
        Boolean::class.java,
        lockId,
    ) ?: false

    /**
     * Generate consistent lock ID from string key.
     *
     * <p>Uses PostgreSQL's hashtext() function to generate a 64-bit hash.
     * The same key always produces the same lock ID.
     */
    private fun generateLockId(key: String): Long = jdbcTemplate.queryForObject(
        "SELECT hashtext(?)",
        Long::class.java,
        "latch:char:$key",
    ) ?: key.hashCode().toLong()

    private fun parkForPollOrThrow(key: String) {
        if (Thread.currentThread().isInterrupted) {
            throw DistributedLockException("Interrupted while waiting for lock: $key")
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS))
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt()
            throw DistributedLockException("Interrupted while waiting for lock: $key")
        }
    }

    // ==================== Session-Scoped Lock Helpers (Async) ====================

    /**
     * Poll for session-scoped lock acquisition on a JDBC-bound executor.
     * Returns the lockId on success, or null on timeout.
     */
    private fun tryAcquireSessionLockWithPollAsync(
        key: String,
        waitTime: Long,
        leaseTime: Long,
    ): CompletableFuture<Long?> = CompletableFuture.supplyAsync({
        val deadline = System.currentTimeMillis() + waitTime * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted) {
                throw DistributedLockException("Interrupted while waiting for lock: $key")
            }
            val lockId = tryAcquireSessionLockOnce(key, leaseTime)
            if (lockId != null) {
                return@supplyAsync lockId
            }
            // VT-friendly: Thread.sleep on virtual thread does not pin carrier.
            Thread.sleep(POLL_INTERVAL_MS)
        }
        null
    }, jdbcExecutor)

    /**
     * Try once to acquire `pg_try_advisory_lock`. On success, registers the lockId
     * in [lockSessionRegistry] so that [unlockAsync] can release the same lock.
     * Returns null on contention.
     */
    private fun tryAcquireSessionLockOnce(key: String, leaseTime: Long): Long? {
        val lockId = generateLockId(key)
        val acquired: Boolean? = jdbcTemplate.queryForObject(
            "SELECT pg_try_advisory_lock(?)",
            Boolean::class.java,
            lockId,
        )
        return if (acquired == true) {
            lockSessionRegistry[key] = lockId
            lockMetrics.recordLockAcquired("postgres")
            lockId
        } else {
            null
        }
    }

    /**
     * Release the session-scoped lock acquired by [tryAcquireSessionLockOnce].
     * Called from `whenComplete` — must never throw.
     */
    private fun releaseSessionLock(key: String, lockId: Long) {
        lockSessionRegistry.remove(key, lockId)
        try {
            jdbcTemplate.queryForObject(
                "SELECT pg_advisory_unlock(?)",
                Boolean::class.java,
                lockId,
            )
            lockMetrics.recordLockReleased("postgres")
            log.debug("🔓 [AdvisoryLock] Unlocked key: {}", key)
        } catch (e: Exception) {
            log.warn("[AdvisoryLock] Failed to release session lock: key={}, lockId={}", key, lockId, e)
        }
    }

    /**
     * Follower path: poll until the leader's session lock has been released.
     * Returns CompletableFuture<Void> completing on release-or-timeout.
     */
    private fun waitForLeaderSessionReleaseAsync(key: String, waitTimeSeconds: Int): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val lockId = generateLockId(key)
            val deadline = System.currentTimeMillis() + waitTimeSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted) {
                    throw DistributedLockException("Interrupted while waiting for leader: $key")
                }
                // Try to acquire the same lock; if it succeeds, leader has released.
                val leaderDone: Boolean = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)",
                    Boolean::class.java,
                    lockId,
                ) ?: false
                if (leaderDone) {
                    // Immediately release the follower's hold (it was just a probe).
                    try {
                        jdbcTemplate.queryForObject(
                            "SELECT pg_advisory_unlock(?)",
                            Boolean::class.java,
                            lockId,
                        )
                    } catch (e: Exception) {
                        log.warn("[AdvisoryLock] Follower failed to release probe lock: key={}", key, e)
                    }
                    log.info("✅ [Follower] Leader completed, proceeding: key={}", key)
                    return@runAsync
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            log.warn("⏰ [Follower] Timed out waiting for leader: key={}, timeout={}s", key, waitTimeSeconds)
        }, jdbcExecutor)

    companion object {
        private val log = LoggerFactory.getLogger(PostgresAdvisoryLockStrategy::class.java)
        private const val POLL_INTERVAL_MS = 100L
        private const val DEFAULT_WAIT_TIME = 10L
        private const val DEFAULT_LEASE_TIME = 20L
    }
}
