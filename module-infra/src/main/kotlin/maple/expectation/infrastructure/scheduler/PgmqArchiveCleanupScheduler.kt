package maple.expectation.infrastructure.scheduler

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * PGMQ Archive Cleanup Scheduler (Phase 4)
 *
 * PGMQ의 `pgmq.archive()`로 이동된 메시지 중 30일이 경과한 것을 삭제.
 * pg_cron이 프로덕션 DB에 설치되어 있지 않으므로 애플리케이션 스케줄러로 대체.
 *
 * @see maple.expectation.infrastructure.pgmq.PgmqClient
 */
@Component
@ConditionalOnProperty(name = ["pgmq.archive.cleanup.enabled"], havingValue = "true", matchIfMissing = true)
class PgmqArchiveCleanupScheduler(
    private val jdbcTemplate: JdbcTemplate,
    private val executor: LogicExecutor,
) {
    companion object {
        private val log = LoggerFactory.getLogger(PgmqArchiveCleanupScheduler::class.java)
        private const val RETENTION_DAYS = 30
        private val QUEUES = listOf(
            "calculation_queue",
            "donation_queue",
            "nexon_retry_queue",
            "expectation_calc_high",
            "expectation_calc_low",
        )
    }

    @Scheduled(cron = "\${pgmq.archive.cleanup.cron:0 0 3 * * *}")
    fun cleanupArchived() {
        val context = TaskContext.of("PgmqArchive", "Cleanup", "scheduled")
        executor.executeVoid({
            QUEUES.forEach { queue ->
                val sql = "DELETE FROM pgmq.a_$queue WHERE created_at < NOW() - INTERVAL '$RETENTION_DAYS days'"
                val deleted = jdbcTemplate.update(sql)
                if (deleted > 0) {
                    log.info("[ArchiveCleanup] Purged {} archived messages from {}", deleted, queue)
                }
            }
        }, context)
    }
}
