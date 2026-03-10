package maple.expectation.infrastructure.lock

import java.sql.Connection
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PostgreSQL Advisory Lock Strategy
 *
 * <p>Replaces Redis RCountDownLatch for leader election during character synchronization.
 *
 * <h3>PostgreSQL Advisory Lock Functions</h3>
 * <ul>
 *   <li>{@code pg_try_advisory_lock(bigint)} - Try to acquire lock (non-blocking)</li>
 *   <li>{@code pg_advisory_lock(bigint)} - Acquire lock (blocking)</li>
 *   <li>{@code pg_advisory_unlock(bigint)} - Release lock</li>
 * </ul>
 *
 * <h3>Lock ID Generation</h3>
 * <p>Uses {@code hashtext(key)} to generate a consistent 64-bit lock ID from a string key.
 *
 * <h3>Session Scoped Locks</h3>
 * <p>Advisory locks are automatically released when the database session ends.
 */
@Component
class PostgresAdvisoryLockStrategy(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) : LeaderElectionStrategy {

    override fun <T> executeWithLeaderElection(
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
    ): T {
        val lockId = generateLockId(key)
        val context = TaskContext.of("AdvisoryLock", "ElectLeader", key)

        return jdbcTemplate.execute(
            ConnectionCallback { conn ->
                executeWithConnectionLock(conn, lockId, key, waitTimeSeconds, leaderTask, followerTask, context)
            },
        )!!
    }

    // ==================== Private Methods ====================

    private fun <T> executeWithConnectionLock(
        conn: Connection,
        lockId: Long,
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
        context: TaskContext,
    ): T {
        // Try to become leader (non-blocking)
        val isLeader = tryAcquireLock(conn, lockId)

        return if (isLeader) {
            executeAsLeader(conn, lockId, key, leaderTask, context)
        } else {
            executeAsFollower(conn, lockId, key, waitTimeSeconds, followerTask, context)
        }
    }

    private fun <T> executeAsLeader(
        conn: Connection,
        lockId: Long,
        key: String,
        leaderTask: ThrowingSupplier<T>,
        context: TaskContext,
    ): T {
        log.info("👑 [Leader] Acquired lock for key: {}", key)

        return executor.executeWithFinally(
            { leaderTask.get() },
            { releaseLock(conn, lockId, key) },
            context,
        )
    }

    private fun <T> executeAsFollower(
        conn: Connection,
        lockId: Long,
        key: String,
        waitTimeSeconds: Int,
        followerTask: ThrowingSupplier<T>,
        context: TaskContext,
    ): T {
        log.info("😴 [Follower] Waiting for leader completion: key={}, timeout={}s", key, waitTimeSeconds)

        // Wait for lock with timeout (polling approach)
        val startTime = System.currentTimeMillis()
        val timeoutMs = waitTimeSeconds * 1000L
        val pollIntervalMs = 100L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check if lock is available (leader completed)
            val acquired = tryAcquireLock(conn, lockId)
            if (acquired) {
                // We acquired the lock, meaning leader released it
                releaseLock(conn, lockId, key)
                log.info("✅ [Follower] Leader completed, proceeding: key={}", key)
                break
            }

            // Still locked, wait a bit
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("⏰ [Follower] Interrupted while waiting: key={}", key)
                break
            }
        }

        return executor.execute(
            { followerTask.get() },
            context,
        )
    }

    /**
     * Try to acquire advisory lock (non-blocking)
     *
     * @return true if lock was acquired, false otherwise
     */
    private fun tryAcquireLock(conn: Connection, lockId: Long): Boolean {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT pg_try_advisory_lock($lockId)")
        val acquired = if (rs.next()) rs.getBoolean(1) else false
        rs.close()
        stmt.close()
        return acquired
    }

    /**
     * Release advisory lock
     */
    private fun releaseLock(conn: Connection, lockId: Long, key: String) {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT pg_advisory_unlock($lockId)")
        val released = if (rs.next()) rs.getBoolean(1) else false
        rs.close()
        stmt.close()

        if (released) {
            log.debug("🔒 [AdvisoryLock] Released lock for key: {}", key)
        } else {
            log.warn("⚠️ [AdvisoryLock] Failed to release lock for key: {}", key)
        }
    }

    /**
     * Generate consistent lock ID from string key
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

        // Redis-compatible key prefix
        private const val REDIS_KEY_PREFIX = "latch:char:"
    }
}
