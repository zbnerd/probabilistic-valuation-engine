package maple.expectation.infrastructure.hotkey

import java.sql.Timestamp
import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hot Key Detector using PostgreSQL UNLOGGED Table
 *
 * <p>Detects cache keys exceeding threshold RPS for special handling.
 * Uses UNLOGGED table for high write performance (no WAL overhead).
 *
 * <h4>Threshold Calculation</h4>
 * <ul>
 *   <li>Current average: 50 RPS per key</li>
 *   <li>P99: 200 RPS per key</li>
 *   <li>Threshold: 100 RPS (2x average, 0.5x P99)</li>
 * </ul>
 *
 * @see ADR-005 Single Flight + Hot Key Strategy
 */
@Component
class HotKeyDetector(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
    @Value("\${hotkey.threshold:100}") private val threshold: Int,
    @Value("\${hotkey.window-size-seconds:10}") private val windowSizeSeconds: Int,
) {

    companion object {
        private val log = LoggerFactory.getLogger(HotKeyDetector::class.java)
    }

    /**
     * Check if a key is hot (exceeds threshold RPS)
     *
     * @param key Cache key to check
     * @return true if key is hot (>100 RPS in last 10s)
     */
    fun isHotKey(key: String): Boolean = executor.executeOrDefault(
        {
            val count = jdbcTemplate.queryForObject(
                """
                    SELECT SUM(count) FROM hot_key_counter
                    WHERE key = ? AND window_start > ?
                    """,
                Long::class.java,
                key,
                Timestamp.from(Instant.now().minusSeconds(windowSizeSeconds.toLong())),
            ) ?: 0L

            count > threshold
        },
        false, // Default to not hot on error
        TaskContext.of("HotKey", "Detect", key),
    )

    /**
     * Record key access for hot key detection
     *
     * @param key Cache key being accessed
     */
    fun recordAccess(key: String) {
        executor.executeVoid(
            {
                jdbcTemplate.update(
                    """
                    INSERT INTO hot_key_counter (key, count, window_start)
                    VALUES (?, 1, ?)
                    ON CONFLICT (key, window_start)
                    DO UPDATE SET count = hot_key_counter.count + 1
                    """,
                    key,
                    Timestamp.from(Instant.now()),
                )
            },
            TaskContext.of("HotKey", "Record", key),
        )
    }

    /**
     * Get current access count for a key
     *
     * @param key Cache key
     * @return Current access count in window
     */
    fun getAccessCount(key: String): Long = executor.executeOrDefault(
        {
            jdbcTemplate.queryForObject(
                """
                    SELECT SUM(count) FROM hot_key_counter
                    WHERE key = ? AND window_start > ?
                    """,
                Long::class.java,
                key,
                Timestamp.from(Instant.now().minusSeconds(windowSizeSeconds.toLong())),
            ) ?: 0L
        },
        0L,
        TaskContext.of("HotKey", "GetCount", key),
    )

    /**
     * Cleanup old entries (scheduled every minute)
     */
    @Scheduled(fixedRate = 60000)
    fun cleanupOldEntries() {
        executor.executeVoid(
            {
                val deleted = jdbcTemplate.update(
                    """
                    DELETE FROM hot_key_counter
                    WHERE window_start < ?
                    """,
                    Timestamp.from(Instant.now().minusSeconds(windowSizeSeconds.toLong() * 2)),
                )
                if (deleted > 0) {
                    log.debug("[HotKey] Cleaned up {} old entries", deleted)
                }
            },
            TaskContext.of("HotKey", "Cleanup", "scheduled"),
        )
    }

    /**
     * Get all hot keys currently detected
     *
     * @return List of hot keys with their counts
     */
    fun getAllHotKeys(): Map<String, Long> = executor.executeOrDefault(
        {
            jdbcTemplate.query(
                """
                    SELECT key, SUM(count) as total_count
                    FROM hot_key_counter
                    WHERE window_start > ?
                    GROUP BY key
                    HAVING SUM(count) > ?
                    """,
                { rs, _ ->
                    rs.getString("key") to rs.getLong("total_count")
                },
                Timestamp.from(Instant.now().minusSeconds(windowSizeSeconds.toLong())),
                threshold,
            ).toMap()
        },
        emptyMap(),
        TaskContext.of("HotKey", "GetAll", "hot"),
    )
}
