package maple.expectation.infrastructure.postgres.warmup

import java.time.LocalDate
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Popular Character Access Repository (PostgreSQL)
 *
 * <p>Replaces Redis RScoredSortedSet for tracking character access counts.
 *
 * <h3>Schema</h3>
 * <pre>
 * CREATE TABLE popular_character_access (
 *     character_name VARCHAR(50) NOT NULL,
 *     access_date DATE NOT NULL,
 *     access_count BIGINT NOT NULL DEFAULT 1,
 *     updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
 *     PRIMARY KEY (character_name, access_date)
 * );
 * CREATE INDEX idx_popular_character_date_count
 *     ON popular_character_access(access_date, access_count DESC);
 * </pre>
 */
@Repository
class PopularCharacterAccessRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {

    /**
     * Increment access count for a character on a specific date
     *
     * @return The new access count
     */
    fun incrementAccess(characterName: String, date: LocalDate): Int = executor.executeOrDefault(
        { upsertAccessCount(characterName, date) },
        1,
        TaskContext.of("PopularAccess", "Increment", characterName),
    )

    /**
     * Get top N characters by access count for a specific date
     *
     * @param date The date to query
     * @param limit Maximum number of characters to return
     * @return List of character names ordered by access count descending
     */
    fun getTopCharacters(date: LocalDate, limit: Int): List<String> = executor.executeOrDefault(
        { selectTopCharacters(date, limit) },
        emptyList(),
        TaskContext.of("PopularAccess", "GetTop", "$date:$limit"),
    )

    /**
     * Get access count for a specific character on a specific date
     *
     * @return The access count, or 0 if not found
     */
    fun getAccessCount(characterName: String, date: LocalDate): Int = executor.executeOrDefault(
        { selectAccessCount(characterName, date) },
        0,
        TaskContext.of("PopularAccess", "GetCount", characterName),
    )

    /**
     * Get the number of unique characters accessed on a specific date
     */
    fun getUniqueCount(date: LocalDate): Int = executor.executeOrDefault(
        { selectUniqueCount(date) },
        0,
        TaskContext.of("PopularAccess", "GetUniqueCount", date.toString()),
    )

    /**
     * Clean up old data (older than specified days)
     *
     * @param daysToKeep Number of days to keep (older data will be deleted)
     * @return Number of rows deleted
     */
    fun cleanupOldData(daysToKeep: Int): Int = executor.executeOrDefault(
        { deleteOldData(daysToKeep) },
        0,
        TaskContext.of("PopularAccess", "Cleanup", daysToKeep.toString()),
    )

    // ==================== Private Methods ====================

    private fun upsertAccessCount(characterName: String, date: LocalDate): Int {
        val result = jdbcTemplate.queryForObject(
            """
            INSERT INTO popular_character_access (character_name, access_date, access_count, updated_at)
            VALUES (?, ?, 1, NOW())
            ON CONFLICT (character_name, access_date)
            DO UPDATE SET
                access_count = popular_character_access.access_count + 1,
                updated_at = NOW()
            RETURNING access_count
            """.trimIndent(),
            Int::class.java,
            characterName,
            date,
        )

        log.debug("Incremented access count: {} on {} -> {}", characterName, date, result)
        return result ?: 1
    }

    private fun selectTopCharacters(date: LocalDate, limit: Int): List<String> = jdbcTemplate.queryForList(
        """
            SELECT character_name
            FROM popular_character_access
            WHERE access_date = ?
            ORDER BY access_count DESC
            LIMIT ?
        """.trimIndent(),
        String::class.java,
        date,
        limit,
    )

    private fun selectAccessCount(characterName: String, date: LocalDate): Int = jdbcTemplate.queryForObject(
        """
            SELECT COALESCE(access_count, 0)
            FROM popular_character_access
            WHERE character_name = ? AND access_date = ?
        """.trimIndent(),
        Int::class.java,
        characterName,
        date,
    ) ?: 0

    private fun selectUniqueCount(date: LocalDate): Int = jdbcTemplate.queryForObject(
        """
            SELECT COUNT(*)
            FROM popular_character_access
            WHERE access_date = ?
        """.trimIndent(),
        Int::class.java,
        date,
    ) ?: 0

    private fun deleteOldData(daysToKeep: Int): Int {
        val rows = jdbcTemplate.update(
            """
            DELETE FROM popular_character_access
            WHERE access_date < CURRENT_DATE - INTERVAL '1 day' * ?
            """.trimIndent(),
            daysToKeep,
        )
        if (rows > 0) {
            log.info("Cleaned up {} old access records (older than {} days)", rows, daysToKeep)
        }
        return rows
    }

    companion object {
        private val log = LoggerFactory.getLogger(PopularCharacterAccessRepository::class.java)
    }
}
