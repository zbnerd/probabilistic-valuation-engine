package maple.expectation.infrastructure.persistence

import maple.expectation.core.port.out.PersistenceTrackerPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component

@Component
class PostgresPersistenceTrackerAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) : PersistenceTrackerPort {

    companion object {
        private val log = LoggerFactory.getLogger(PostgresPersistenceTrackerAdapter::class.java)
    }

    override fun insertPending(ocid: String, instanceId: String) {
        val context = TaskContext.of("PersistenceTracker", "InsertPending", ocid)
        executor.executeVoid({
            jdbcTemplate.update(
                "INSERT INTO equipment_persistence_tracker (ocid, instance_id, status) VALUES (?, ?, 'PENDING') " +
                    "ON CONFLICT (ocid) DO UPDATE SET status = 'PENDING', instance_id = EXCLUDED.instance_id, created_at = NOW(), completed_at = NULL",
                ocid,
                instanceId,
            )
        }, context)
    }

    override fun markCompleted(ocid: String) {
        val context = TaskContext.of("PersistenceTracker", "MarkCompleted", ocid)
        executor.executeVoid({
            jdbcTemplate.update(
                "UPDATE equipment_persistence_tracker SET status = 'COMPLETED', completed_at = NOW() WHERE ocid = ?",
                ocid,
            )
        }, context)
    }

    override fun findPendingOperations(): List<String> {
        val context = TaskContext.of("PersistenceTracker", "FindPending", "all")
        val rowMapper = RowMapper<String> { rs, _ -> rs.getString("ocid") }
        return executor.executeOrDefault({
            jdbcTemplate.query(
                "SELECT ocid FROM equipment_persistence_tracker WHERE status = 'PENDING'",
                rowMapper,
            )
        }, emptyList(), context)
    }
}
