package maple.expectation.infrastructure.postgres.fallback

import java.time.Duration
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Fallback Cache Repository (PostgreSQL)
 *
 * <p>Replaces Redis RBucket for caching equipment data during MySQL degradation.
 *
 * <h3>Schema</h3>
 * <pre>
 * CREATE TABLE fallback_cache (
 *     cache_key VARCHAR(255) PRIMARY KEY,
 *     cache_value TEXT NOT NULL,
 *     created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
 *     expires_at TIMESTAMP WITH TIME ZONE NOT NULL
 * );
 * CREATE INDEX idx_fallback_cache_expires ON fallback_cache(expires_at);
 * </pre>
 */
@Repository
class FallbackCacheRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {

    /**
     * Get cached value if not expired
     *
     * @param key Cache key
     * @return Cached value or null if not found or expired
     */
    fun get(key: String): String? = executor.executeOrDefault(
        { selectValue(key) },
        null,
        TaskContext.of("FallbackCache", "Get", key),
    )

    /**
     * Set cache value with TTL
     *
     * @param key Cache key
     * @param value Value to cache
     * @param ttl Time to live
     */
    fun set(key: String, value: String, ttl: Duration) {
        executor.executeVoid(
            { upsertValue(key, value, ttl) },
            TaskContext.of("FallbackCache", "Set", key),
        )
    }

    /**
     * Delete cache entry
     *
     * @return true if entry was deleted, false otherwise
     */
    fun delete(key: String): Boolean = executor.executeOrDefault(
        { deleteValue(key) },
        false,
        TaskContext.of("FallbackCache", "Delete", key),
    )

    /**
     * Clean up expired entries
     *
     * @return Number of expired entries deleted
     */
    fun cleanupExpired(): Int = executor.executeOrDefault(
        { deleteExpired() },
        0,
        TaskContext.of("FallbackCache", "Cleanup"),
    )

    // ==================== Private Methods ====================

    private fun selectValue(key: String): String? {
        val result = jdbcTemplate.queryForObject(
            """
            SELECT cache_value
            FROM fallback_cache
            WHERE cache_key = ? AND expires_at > NOW()
            """.trimIndent(),
            String::class.java,
            key,
        )

        if (result != null) {
            log.debug("Cache hit: {}", key)
        } else {
            log.debug("Cache miss: {}", key)
        }

        return result
    }

    private fun upsertValue(key: String, value: String, ttl: Duration) {
        jdbcTemplate.update(
            """
            INSERT INTO fallback_cache (cache_key, cache_value, created_at, expires_at)
            VALUES (?, ?, NOW(), NOW() + INTERVAL '1 millisecond' * ?)
            ON CONFLICT (cache_key)
            DO UPDATE SET
                cache_value = EXCLUDED.cache_value,
                created_at = NOW(),
                expires_at = NOW() + INTERVAL '1 millisecond' * ?
            """.trimIndent(),
            key,
            value,
            ttl.toMillis(),
            ttl.toMillis(),
        )

        log.debug("Cached value: key={}, ttl={}ms", key, ttl.toMillis())
    }

    private fun deleteValue(key: String): Boolean {
        val rows = jdbcTemplate.update(
            "DELETE FROM fallback_cache WHERE cache_key = ?",
            key,
        )

        if (rows > 0) {
            log.debug("Deleted cache entry: {}", key)
        }

        return rows > 0
    }

    private fun deleteExpired(): Int {
        val rows = jdbcTemplate.update(
            "DELETE FROM fallback_cache WHERE expires_at <= NOW()",
        )

        if (rows > 0) {
            log.info("Cleaned up {} expired cache entries", rows)
        }

        return rows
    }

    companion object {
        private val log = LoggerFactory.getLogger(FallbackCacheRepository::class.java)
    }
}
