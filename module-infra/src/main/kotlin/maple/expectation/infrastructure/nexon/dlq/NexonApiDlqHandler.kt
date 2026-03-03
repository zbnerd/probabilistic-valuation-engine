package maple.expectation.infrastructure.nexon.dlq

import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.domain.v2.NexonApiDlq
import maple.expectation.domain.v2.NexonApiOutbox
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.nexon.outbox.NexonApiOutboxMetrics
import maple.expectation.infrastructure.persistence.repository.NexonApiDlqRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Dead Letter Queue 처리 서비스 for Nexon API (Issue #333)
 *
 * <h3>Triple Safety Net (P0 - 데이터 영구 손실 방지)</h3>
 *
 * <ol>
 *   <li><b>1차</b>: DB DLQ INSERT
 *   <li><b>2차</b>: File Backup (DLQ 실패 시)
 *   <li><b>3차</b>: Stateless Critical Alert + Metric
 * </ol>
 *
 * <h3>Design Pattern</h3>
 *
 * <p>DonationDlqHandler 패턴을 따르며 Nexon API 특화
 *
 * <h3>P0/P1 리팩토링 준수</h3>
 *
 * <ul>
 *   <li>P1-6: 3-Line Rule 준수 — 람다 -> 메서드 추출
 *   <li>CLAUDE.md Section 12: LogicExecutor Pattern (zero try-catch)
 *   <li>CLAUDE.md Section 6: Constructor Injection
 * </ul>
 *
 * @see maple.expectation.infrastructure.persistence.repository.NexonApiDlqRepository
 * @see maple.expectation.core.port.out.ShutdownDataPersistencePort
 * @see maple.expectation.infrastructure.alert.StatelessAlertService
 * @see maple.expectation.infrastructure.nexon.outbox.NexonApiOutboxMetrics
 */
@Service
class NexonApiDlqHandler(
    private val dlqRepository: NexonApiDlqRepository,
    private val fileBackupService: ShutdownDataPersistencePort,
    private val statelessAlertService: StatelessAlertService,
    private val executor: LogicExecutor,
    private val metrics: NexonApiOutboxMetrics,
) {
    private val log = LoggerFactory.getLogger(NexonApiDlqHandler::class.java)

    /**
     * Triple Safety Net 실행
     *
     * <p>NexonApiOutbox 항목 처리 실패 시 DLQ로 이동
     *
     * @param entry 실패한 Outbox 엔티티
     * @param cause 실패 원인 예외
     */
    fun handleDeadLetter(entry: NexonApiOutbox, cause: Throwable?) {
        val context = TaskContext.of("NexonApiDLQ", "Handle", entry.requestId)

        executor.executeOrCatch(
            { saveToDbDlq(entry, cause) },
            { dbEx -> handleDbDlqFailure(entry, cause, context) },
            context
        )
    }

    /**
     * Triple Safety Net 실행 (String reason 오버로딩)
     *
     * <p>무결성 검증 실패 등 예외가 아닌 경우 사용
     *
     * @param entry 실패한 Outbox 엔티티
     * @param reason 실패 사유
     */
    fun handleDeadLetter(entry: NexonApiOutbox, reason: String) {
        val context = TaskContext.of("NexonApiDLQ", "Handle", entry.requestId)

        executor.executeOrCatch(
            { saveToDbDlq(entry, reason) },
            { dbEx -> handleDbDlqFailure(entry, reason, context) },
            context
        )
    }

    /** 1차 안전망: DB DLQ INSERT (P1-6: 메서드 추출) */
    private fun saveToDbDlq(entry: NexonApiOutbox, cause: Throwable?): Unit? {
        val reason = cause?.message ?: "Unknown error"
        return saveToDbDlq(entry, reason)
    }

    /** 1차 안전망: DB DLQ INSERT (String reason) */
    private fun saveToDbDlq(entry: NexonApiOutbox, reason: String): Unit? {
        val dlq = NexonApiDlq.from(entry, reason)
        dlqRepository.save(dlq)
        metrics.incrementDlqMoved()
        log.warn(
            "[NexonApiDLQ] Entry moved to DLQ: requestId={}, reason={}",
            entry.requestId,
            reason
        )
        return null
    }

    /** 2차 안전망: File Backup (DB DLQ 실패 시) */
    private fun handleDbDlqFailure(
        entry: NexonApiOutbox,
        cause: Throwable?,
        context: TaskContext
    ): Unit? {
        val reason = cause?.message ?: "Unknown error"
        log.error(
            "[NexonApiDLQ] DB DLQ 저장 실패, File Backup 시도: requestId={}",
            entry.requestId
        )

        executor.executeOrCatch(
            { saveToFileBackup(entry) },
            { fileEx -> handleCriticalFailure(entry, reason, fileEx) },
            context
        )
        return null
    }

    /** 2차 안전망: File Backup (String reason 오버로딩) */
    private fun handleDbDlqFailure(
        entry: NexonApiOutbox,
        reason: String,
        context: TaskContext
    ): Unit? {
        log.error(
            "[NexonApiDLQ] DB DLQ 저장 실패, File Backup 시도: requestId={}",
            entry.requestId
        )

        executor.executeOrCatch(
            { saveToFileBackup(entry) },
            { fileEx -> handleCriticalFailure(entry, reason, fileEx) },
            context
        )
        return null
    }

    /** File Backup 실행 (P1-6: 메서드 추출) */
    private fun saveToFileBackup(entry: NexonApiOutbox): Unit? {
        fileBackupService.appendOutboxEntry(entry.requestId, entry.payload)
        metrics.incrementDlqFileBackup()
        log.warn("[NexonApiDLQ] File Backup 성공: requestId={}", entry.requestId)
        return null
    }

    /**
     * 3차 안전망: Critical Alert (최후의 안전망)
     *
     * <h4>ADR-016 Triple Safety Net 패턴</h4>
     *
     * <p>DB, File 모두 실패 시 Discord 알림으로 운영자에게 수동 복구 요청
     *
     * @param entry 실패한 Outbox 엔티티
     * @param reason 실패 사유
     * @param fileEx File Backup 실패 예외
     */
    private fun handleCriticalFailure(
        entry: NexonApiOutbox,
        reason: String,
        fileEx: Throwable?
    ): Unit? {
        metrics.incrementDlqCriticalFailure()

        val title = "NEXON API OUTBOX CRITICAL FAILURE"
        val description =
            """
            RequestId: ${entry.requestId}
            EventType: ${entry.eventType?.name}
            Reason: $reason
            Manual intervention required!
            """.trimIndent()

        statelessAlertService.sendCritical(title, description, fileEx)
        log.error(
            "[CRITICAL] All safety nets failed for NexonApiOutbox: requestId={}, eventType={} - Manual intervention required!",
            entry.requestId,
            entry.eventType?.name
        )

        return null
    }
}
