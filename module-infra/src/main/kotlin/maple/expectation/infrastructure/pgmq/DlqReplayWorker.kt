package maple.expectation.infrastructure.pgmq

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * DLQ Replay Worker (#646)
 *
 * <h3>Purpose</h3>
 * <p>Periodically scans PGMQ archive tables for dead-lettered messages
 * and replays them with exponential backoff. Prevents permanent message loss.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Discover archived messages from pgmq.a_{queue_name} not yet tracked</li>
 *   <li>Insert tracking records into dlq_replay_meta</li>
 *   <li>Replay eligible messages (backoff elapsed, replay_count < MAX)</li>
 *   <li>Alert on permanent failures (replay_count >= MAX)</li>
 * </ol>
 *
 * <h3>Zero Try-Catch</h3>
 * <p>All operations wrapped in LogicExecutor
 */
@Component
class DlqReplayWorker(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
    private val alertService: StatelessAlertService,
    private val lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    @Value("\${pgmq.dlq.max-replay-attempts:3}") private val maxReplayAttempts: Int,
    @Value("\${pgmq.dlq.backoff-base-hours:1}") private val backoffBaseHours: Long,
) {
    companion object {
        private val log = LoggerFactory.getLogger(DlqReplayWorker::class.java)
        private val QUEUE_NAMES = listOf(
            "expectation_calc_high",
            "expectation_calc_low",
            "calculation_queue",
            "donation_queue",
            "nexon_fanout_queue",
            "nexon_retry_queue",
        )
    }

    @Scheduled(fixedDelayString = "\${pgmq.dlq.replay-interval-ms:3600000}")
    fun replayDeadLetters() {
        if (!lifecycleWrapper.beforeTask()) return
        val context = TaskContext.of("DlqReplayWorker", "Replay")

        executor.executeVoid({ doReplay() }, context)
        lifecycleWrapper.afterTask()
    }

    private fun doReplay() {
        var totalReplayed = 0
        var totalPermanent = 0

        for (queueName in QUEUE_NAMES) {
            discoverAndTrack(queueName)
            totalReplayed += replayEligible(queueName)
            totalPermanent += alertPermanentFailures(queueName)
        }

        if (totalReplayed > 0 || totalPermanent > 0) {
            log.info("[DlqReplayWorker] Summary: replayed={}, permanentFailures={}", totalReplayed, totalPermanent)
        }
    }

    /**
     * PGMQ archive 테이블에서 추적되지 않은 메시지를 발견하여 dlq_replay_meta에 등록
     */
    private fun discoverAndTrack(queueName: String) {
        val archiveTable = "pgmq.a_$queueName"

        val untracked = jdbcTemplate.queryForList(
            // archive 테이블에서 msg_id 목록 조회 + 이미 추적 중인 것 제외
            """
            SELECT a.msg_id FROM $archiveTable a
            WHERE NOT EXISTS (
                SELECT 1 FROM dlq_replay_meta m
                WHERE m.queue_name = ? AND m.message_id = a.msg_id
            )
            """.trimIndent(),
            Long::class.java,
            queueName,
        )

        if (untracked.isEmpty()) return

        for (msgId in untracked) {
            insertTracking(queueName, msgId)
        }

        log.info("[DlqReplayWorker] Discovered {} new archived messages in {}", untracked.size, queueName)
    }

    private fun insertTracking(queueName: String, messageId: Long) {
        jdbcTemplate.update(
            "INSERT INTO dlq_replay_meta (queue_name, message_id, replay_count, first_failed_at) VALUES (?, ?, 0, NOW()) ON CONFLICT DO NOTHING",
            queueName,
            messageId,
        )
    }

    /**
     * 백오프 경과한 메시지를 재발행
     */
    private fun replayEligible(queueName: String): Int {
        val candidates = findReplayCandidates(queueName)
        if (candidates.isEmpty()) return 0

        val eligible = candidates.filter { isBackoffElapsed(it) }
        for (candidate in eligible) {
            replaySingleMessage(candidate)
        }
        return eligible.size
    }

    private fun findReplayCandidates(queueName: String): List<ReplayCandidate> {
        return jdbcTemplate.query(
            """
            SELECT queue_name, message_id, replay_count, first_failed_at, last_replayed_at
            FROM dlq_replay_meta
            WHERE queue_name = ? AND replay_count < ?
            ORDER BY first_failed_at ASC
            """.trimIndent(),
            { rs, _ ->
                ReplayCandidate(
                    queueName = rs.getString("queue_name"),
                    messageId = rs.getLong("message_id"),
                    replayCount = rs.getInt("replay_count"),
                    firstFailedAt = rs.getTimestamp("first_failed_at")?.toInstant(),
                    lastReplayedAt = rs.getTimestamp("last_replayed_at")?.toInstant(),
                )
            },
            queueName,
            maxReplayAttempts,
        )
    }

    private fun isBackoffElapsed(candidate: ReplayCandidate): Boolean {
        val lastAttempt = candidate.lastReplayedAt ?: candidate.firstFailedAt ?: return true
        val backoffHours = (1L shl candidate.replayCount) * backoffBaseHours
        val nextAttempt = lastAttempt.plusSeconds(backoffHours * 3600)
        return Instant.now().isAfter(nextAttempt)
    }

    private fun replaySingleMessage(candidate: ReplayCandidate) {
        val archiveTable = "pgmq.a_${candidate.queueName}"

        val payload = jdbcTemplate.queryForObject(
            "SELECT message FROM $archiveTable WHERE msg_id = ?",
            String::class.java,
            candidate.messageId,
        )

        if (payload == null) {
            log.warn("[DlqReplayWorker] Archived message not found: queue={}, msgId={}", candidate.queueName, candidate.messageId)
            return
        }

        pgmqClient.send(candidate.queueName, payload)
        incrementReplayCount(candidate)

        meterRegistry.counter("pgmq.worker.replay", "queue", candidate.queueName).increment()
        log.info(
            "[DlqReplayWorker] Replayed: queue={}, msgId={}, replayCount={}",
            candidate.queueName, candidate.messageId, candidate.replayCount + 1,
        )
    }

    private fun incrementReplayCount(candidate: ReplayCandidate) {
        jdbcTemplate.update(
            "UPDATE dlq_replay_meta SET replay_count = replay_count + 1, last_replayed_at = NOW() WHERE queue_name = ? AND message_id = ?",
            candidate.queueName,
            candidate.messageId,
        )
    }

    /**
     * 영구 실패 (replay_count >= MAX) 메시지 알림
     */
    private fun alertPermanentFailures(queueName: String): Int {
        val permanent = jdbcTemplate.queryForList(
            "SELECT message_id FROM dlq_replay_meta WHERE queue_name = ? AND replay_count >= ?",
            Long::class.java,
            queueName,
            maxReplayAttempts,
        )

        if (permanent.isEmpty()) return 0

        val alertContext = TaskContext.of("DlqReplayWorker", "AlertPermanentFailure", queueName)
        executor.executeOrCatch(
            {
                alertService.sendCritical(
                    "DLQ PERMANENT FAILURE: $queueName",
                    "${permanent.size} messages exhausted all replay attempts ($maxReplayAttempts). Message IDs: ${permanent.take(10)}",
                    null,
                )
            },
            { e -> log.warn("[DlqReplayWorker] Alert failed: {}", e.message) },
            alertContext,
        )

        return permanent.size
    }

    data class ReplayCandidate(
        val queueName: String,
        val messageId: Long,
        val replayCount: Int,
        val firstFailedAt: Instant?,
        val lastReplayedAt: Instant?,
    )
}
