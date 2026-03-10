package maple.expectation.infrastructure.postgres.admin

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Admin Fingerprint Repository (PostgreSQL)
 *
 * <p>Replaces Redis RSet for admin fingerprint storage.
 *
 * <h3>Schema</h3>
 * <pre>
 * CREATE TABLE admin_fingerprint (
 *     fingerprint VARCHAR(64) PRIMARY KEY,
 *     created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
 * );
 * </pre>
 */
@Repository
class AdminFingerprintRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {

    /**
     * Check if fingerprint exists
     */
    fun exists(fingerprint: String): Boolean = executor.executeOrDefault(
        { checkExists(fingerprint) },
        false,
        TaskContext.of("AdminRepo", "Exists", maskFingerprint(fingerprint)),
    )

    /**
     * Add new fingerprint (idempotent insert)
     */
    fun add(fingerprint: String): Boolean = executor.executeOrDefault(
        { insertFingerprint(fingerprint) },
        false,
        TaskContext.of("AdminRepo", "Add", maskFingerprint(fingerprint)),
    )

    /**
     * Remove fingerprint
     */
    fun remove(fingerprint: String): Boolean = executor.executeOrDefault(
        { deleteFingerprint(fingerprint) },
        false,
        TaskContext.of("AdminRepo", "Remove", maskFingerprint(fingerprint)),
    )

    /**
     * Get all fingerprints
     */
    fun findAll(): Set<String> = executor.executeOrDefault(
        { selectAll() },
        emptySet(),
        TaskContext.of("AdminRepo", "FindAll"),
    )

    /**
     * Count fingerprints
     */
    fun count(): Int = executor.executeOrDefault(
        { selectCount() },
        0,
        TaskContext.of("AdminRepo", "Count"),
    )

    // ==================== Private Methods ====================

    private fun checkExists(fingerprint: String): Boolean {
        val result = jdbcTemplate.queryForObject(
            "SELECT 1 FROM admin_fingerprint WHERE fingerprint = ?",
            Int::class.java,
            fingerprint,
        )
        return result == 1
    }

    private fun insertFingerprint(fingerprint: String): Boolean {
        val rows = jdbcTemplate.update(
            "INSERT INTO admin_fingerprint (fingerprint) VALUES (?) " +
                "ON CONFLICT (fingerprint) DO NOTHING",
            fingerprint,
        )
        log.debug("Added admin fingerprint: {}", maskFingerprint(fingerprint))
        return rows > 0
    }

    private fun deleteFingerprint(fingerprint: String): Boolean {
        val rows = jdbcTemplate.update(
            "DELETE FROM admin_fingerprint WHERE fingerprint = ?",
            fingerprint,
        )
        if (rows > 0) {
            log.info("Removed admin fingerprint: {}", maskFingerprint(fingerprint))
        }
        return rows > 0
    }

    private fun selectAll(): Set<String> = jdbcTemplate.queryForList(
        "SELECT fingerprint FROM admin_fingerprint",
        String::class.java,
    ).toSet()

    private fun selectCount(): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM admin_fingerprint",
        Int::class.java,
    ) ?: 0

    private fun maskFingerprint(fingerprint: String): String {
        if (fingerprint.length <= 8) return "***"
        return fingerprint.substring(0, 4) + "..." + fingerprint.substring(fingerprint.length - 4)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdminFingerprintRepository::class.java)
    }
}
