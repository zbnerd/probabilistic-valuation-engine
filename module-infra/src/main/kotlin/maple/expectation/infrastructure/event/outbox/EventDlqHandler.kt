package maple.expectation.infrastructure.event.outbox

import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.domain.v2.EventOutbox
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Event Dead Letter Queue 처리 서비스
 *
 * <h3>Safety Net</h3>
 * 1. **1차**: File Backup
 * 2. **2차**: Stateless Critical Alert + Metric
 *
 * @see ShutdownDataPersistencePort
 * @see StatelessAlertService
 * @see EventOutboxMetrics
 */
@Service
class EventDlqHandler(
    private val fileBackupService: ShutdownDataPersistencePort,
    private val statelessAlertService: StatelessAlertService,
    private val executor: LogicExecutor,
    private val metrics: EventOutboxMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Safety Net 실행
     *
     * @param entry 실패한 EventOutbox 엔티티
     * @param reason 실패 사유
     */
    fun handleDeadLetter(entry: EventOutbox, reason: String) {
        val eventId = entry.id?.toString() ?: "unknown"
        val context = TaskContext.of("EventDLQ", "Handle", eventId)

        executor.executeOrCatch(
            { saveToFileBackup(entry, reason) },
            { fileEx -> handleFileBackupFailure(entry, reason, fileEx) },
            context,
        )
    }

    /** File Backup 실행 */
    private fun saveToFileBackup(entry: EventOutbox, reason: String) {
        val eventId = entry.id?.toString() ?: "unknown"
        fileBackupService.appendOutboxEntry(eventId, entry.payload ?: "{}")
        // Note: EventOutboxMetrics doesn't have incrementFileBackup, using log only
        log.warn("[EventDLQ] File Backup 성공: {}", eventId)
    }

    /**
     * Critical Alert (최후의 안전망)
     */
    private fun handleFileBackupFailure(entry: EventOutbox, reason: String, fileEx: Throwable) {
        // Note: EventOutboxMetrics doesn't have incrementCriticalFailure, using log only
        val eventId = entry.id?.toString() ?: "unknown"

        val title = "EVENT OUTBOX CRITICAL FAILURE"
        val description = """
            EventId: $eventId
            EventType: ${entry.eventType}
            Reason: $reason
            Manual intervention required!
        """.trimIndent()

        statelessAlertService.sendCritical(title, description, fileEx)
        log.error(
            "[CRITICAL] All safety nets failed for: {} - Manual intervention required!",
            eventId,
        )
    }
}
