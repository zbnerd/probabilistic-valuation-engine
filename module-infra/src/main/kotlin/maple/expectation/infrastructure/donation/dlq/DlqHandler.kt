package maple.expectation.infrastructure.donation.dlq

import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.domain.v2.DonationDlq
import maple.expectation.domain.v2.DonationOutbox
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.donation.outbox.OutboxMetrics
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.DonationDlqRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Dead Letter Queue 처리 서비스 (Issue #80)
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
 * @see DonationDlqRepository
 * @see ShutdownDataPersistenceService
 * @see StatelessAlertService
 * @see OutboxMetrics
 */
@Service
class DlqHandler(
    private val dlqRepository: DonationDlqRepository,
    private val fileBackupService: ShutdownDataPersistencePort,
    private val statelessAlertService: StatelessAlertService,
    private val executor: LogicExecutor,
    private val metrics: OutboxMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Triple Safety Net 실행
     *
     * @param entry 실패한 Outbox 엔티티
     * @param reason 실패 사유
     */
    fun handleDeadLetter(entry: DonationOutbox, reason: String) {
        val context = TaskContext.of("DLQ", "Handle", entry.requestId)

        executor.executeOrCatch(
            { saveToDbDlq(entry, reason) },
            { dbEx -> handleDbDlqFailure(entry, reason, context) },
            context,
        )
    }

    /** 1차 안전망: DB DLQ INSERT (P1-6: 메서드 추출) */
    private fun saveToDbDlq(entry: DonationOutbox, reason: String) {
        val dlq = DonationDlq.from(entry, reason)
        dlqRepository.save(dlq)
        metrics.incrementDlq()
        log.warn("[DLQ] Entry moved to DLQ: {}", entry.requestId)
    }

    /** 2차 안전망: File Backup (DB DLQ 실패 시) */
    private fun handleDbDlqFailure(entry: DonationOutbox, reason: String, context: TaskContext) {
        log.error("[DLQ] DB DLQ 저장 실패, File Backup 시도: {}", entry.requestId)

        executor.executeOrCatch(
            { saveToFileBackup(entry) },
            { fileEx -> handleCriticalFailure(entry, reason, fileEx) },
            context,
        )
    }

    /** File Backup 실행 (P1-6: 메서드 추출) */
    private fun saveToFileBackup(entry: DonationOutbox) {
        fileBackupService.appendOutboxEntry(entry.requestId, entry.payload)
        metrics.incrementFileBackup()
        log.warn("[DLQ] File Backup 성공: {}", entry.requestId)
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
    private fun handleCriticalFailure(entry: DonationOutbox, reason: String, fileEx: Throwable) {
        metrics.incrementCriticalFailure()

        val title = "OUTBOX CRITICAL FAILURE"
        val description = """
            RequestId: ${entry.requestId}
            Reason: $reason
            Manual intervention required!
        """.trimIndent()

        statelessAlertService.sendCritical(title, description, fileEx)
        log.error(
            "[CRITICAL] All safety nets failed for: {} - Manual intervention required!",
            entry.requestId,
        )
    }
}
