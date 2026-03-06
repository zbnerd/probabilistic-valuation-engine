package maple.expectation.infrastructure.event.outbox

import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.domain.v2.EventDlq
import maple.expectation.domain.v2.EventOutbox
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.event.outbox.metrics.EventOutboxMetrics
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.EventDlqRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Event Dead Letter Queue 처리 서비스
 *
 * <h3>Triple Safety Net (P0 - 데이터 영구 손실 방지)</h3>
 * 1. **1차**: DB DLQ INSERT
 * 2. **2차**: File Backup (DLQ 실패 시)
 * 3. **3차**: Stateless Critical Alert + Metric
 *
 * <h3>P0/P1 리팩토링</h3>
 * - P0-3: ClassCastException 수정 — Throwable 다운캐스트 제거
 * - P1-6: 3-Line Rule 준수 — 람다 -> 메서드 추출
 *
 * @see EventDlqRepository
 * @see ShutdownDataPersistencePort
 * @see StatelessAlertService
 * @see EventOutboxMetrics
 */
@Service
class EventDlqHandler(
    private val eventDlqRepository: EventDlqRepository,
    private val fileBackupService: ShutdownDataPersistencePort,
    private val statelessAlertService: StatelessAlertService,
    private val executor: LogicExecutor,
    private val metrics: EventOutboxMetrics
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Triple Safety Net 실행
     *
     * @param entry 실패한 EventOutbox 엔티티
     * @param reason 실패 사유
     */
    fun handleDeadLetter(entry: EventOutbox, reason: String) {
        val context = TaskContext.of("EventDLQ", "Handle", entry.eventId)

        executor.executeOrCatch(
            { saveToDbDlq(entry, reason) },
            { dbEx -> handleDbDlqFailure(entry, reason, context) },
            context
        )
    }

    /** 1차 안전망: DB DLQ INSERT (P1-6: 메서드 추출) */
    private fun saveToDbDlq(entry: EventOutbox, reason: String) {
        val dlq = EventDlq.from(entry, reason)
        eventDlqRepository.save(dlq)
        metrics.incrementDlq()
        log.warn("[EventDLQ] Entry moved to DLQ: {}", entry.eventId)
    }

    /** 2차 안전망: File Backup (DB DLQ 실패 시) */
    private fun handleDbDlqFailure(entry: EventOutbox, reason: String, context: TaskContext) {
        log.error("[EventDLQ] DB DLQ 저장 실패, File Backup 시도: {}", entry.eventId)

        executor.executeOrCatch(
            { saveToFileBackup(entry) },
            { fileEx -> handleCriticalFailure(entry, reason, fileEx) },
            context
        )
    }

    /** File Backup 실행 (P1-6: 메서드 추출) */
    private fun saveToFileBackup(entry: EventOutbox) {
        fileBackupService.appendOutboxEntry(entry.eventId, entry.payload)
        metrics.incrementFileBackup()
        log.warn("[EventDLQ] File Backup 성공: {}", entry.eventId)
    }

    /**
     * 3차 안전망: Critical Alert (최후의 안전망)
     *
     * **P0-3 Fix: ClassCastException 제거**
     * 기존: `(Exception) fileEx` — Throwable -> Exception 다운캐스트 시
     * Error(OOM 등)에서 ClassCastException 발생 -> Triple Safety Net 완전 실패
     *
     * 수정: Throwable 그대로 전달
     */
    private fun handleCriticalFailure(entry: EventOutbox, reason: String, fileEx: Throwable) {
        metrics.incrementCriticalFailure()

        val title = "EVENT OUTBOX CRITICAL FAILURE"
        val description = """
            EventId: ${entry.eventId}
            EventType: ${entry.eventType}
            Reason: $reason
            Manual intervention required!
        """.trimIndent()

        statelessAlertService.sendCritical(title, description, fileEx)
        log.error(
            "[CRITICAL] All safety nets failed for: {} - Manual intervention required!",
            entry.eventId
        )
    }
}
