package maple.expectation.infrastructure.lock

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

    // ==================== XACT-SCOPED Lock Methods ====================

    /**
     * [XACT-SCOPED] Execute task with advisory lock.
     *
     * Uses `pg_try_advisory_xact_lock` within a TransactionTemplate.
     * Lock is automatically released when the transaction commits or rolls back.
     */
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
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS))
        }

        lockMetrics.recordFailure("postgres")
        throw DistributedLockException("Failed to acquire lock within timeout: $key")
    }

    override fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T = executeWithLock(key, 10, 20, task)

    /**
     * [XACT-SCOPED] Execute with leader election pattern.
     *
     * Leader acquires `pg_try_advisory_xact_lock` and executes leaderTask.
     * Followers poll until leader's transaction commits (releasing the lock),
     * then execute followerTask.
     */
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
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS))
        }

        return executor.execute({ followerTask.get() }, context)
    }

    // ==================== SESSION-SCOPED Lock Methods ====================

    /**
     * [SESSION-SCOPED] Try to acquire advisory lock (non-blocking).
     *
     * Uses `pg_try_advisory_lock` (session scope).
     * MUST call [unlock] explicitly to release.
     * Retained for async patterns where lock must outlive the method call.
     */
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
     */
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

    companion object {
        private val log = LoggerFactory.getLogger(PostgresAdvisoryLockStrategy::class.java)
        private const val POLL_INTERVAL_MS = 100L
    }
}
