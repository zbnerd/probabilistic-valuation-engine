package maple.expectation.infrastructure.donation.outbox

import maple.expectation.core.port.out.OutboxProcessorPort
import maple.expectation.domain.v2.DonationOutbox
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.donation.dlq.DlqHandler
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.repository.DonationOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Outbox 처리 서비스 (Issue #80)
 *
 * <h3>Financial-Grade 특성</h3>
 * - SKIP LOCKED: 분산 환경 중복 처리 방지
 * - Exponential Backoff: 재시도 간격 증가
 * - Triple Safety Net 연동: DLQ -> File -> Discord
 *
 * <h3>P0 리팩토링</h3>
 * - P0-1: processEntry() Zombie Loop 수정 — 실패 시 반드시 handleFailure() 호출
 * - P0-2: 단일 트랜잭션 배치 -> 항목별 독립 트랜잭션 (TransactionTemplate)
 *
 * <h3>P1 리팩토링</h3>
 * - P1-2: instanceId @Value -> OutboxProperties 생성자 주입
 * - P1-7: updatePendingCount()를 스케줄러 레벨로 이동
 * - P1-8: BATCH_SIZE, STALE_THRESHOLD -> OutboxProperties 외부화
 *
 * @see DonationOutboxRepository
 * @see DlqHandler
 * @see OutboxMetrics
 */
@Service
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxProcessor(
    private val fetchFacade: OutboxFetchFacade,
    private val dlqHandler: DlqHandler,
    private val metrics: OutboxMetrics,
    private val executor: LogicExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val properties: OutboxProperties,
    private val outboxRepository: DonationOutboxRepository
) : OutboxProcessorPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pending 항목 폴링 및 처리
     *
     * <h4>P0-2 Fix: 2-Phase 처리</h4>
     * 1. Phase 1 (TX): SKIP LOCKED 조회 + markProcessing + save
     * 2. Phase 2 (항목별 TX): 개별 처리 (실패 시 다른 항목에 영향 없음)
     */
    @ObservedTransaction("scheduler.outbox.poll")
    override fun pollAndProcess() {
        val context = TaskContext.of("Outbox", "PollAndProcess", properties.instanceId)

        executor.executeOrCatch(
            {
                val locked = fetchFacade.fetchAndLock()
                if (locked.isEmpty()) {
                    return@executeOrCatch
                }

                log.info("[Outbox] 처리 시작: {}건", locked.size)
                processBatch(locked)
            },
            { e ->
                log.error("[Outbox] 폴링 실패", e)
                metrics.incrementPollFailure()
            },
            context
        )
    }

    /**
     * Phase 2: 배치 처리 (항목별 독립 트랜잭션)
     *
     * P0-2 Fix: 개별 항목 실패가 전체 배치에 영향을 주지 않음
     */
    private fun processBatch(locked: List<DonationOutbox>) {
        var success = 0
        var failed = 0

        locked.forEach { entry ->
            val result = processEntryInTransaction(entry.id!!)
            if (result) success++ else failed++
        }

        log.info("[Outbox] 처리 완료: 성공={}, 실패={}", success, failed)
    }

    /**
     * 개별 Outbox 항목 처리 (독립 트랜잭션)
     *
     * <h4>P0-1 Fix: Zombie Loop 방지</h4>
     * executeOrCatch로 실패 시 반드시 handleFailure() 호출 -> retryCount 증가 -> DLQ 이동
     */
    private fun processEntryInTransaction(entryId: Long): Boolean {
        val context = TaskContext.of("Outbox", "ProcessEntry", entryId.toString())

        return executor.executeOrCatch(
            {
                val result = transactionTemplate.execute { status ->
                    val entry = outboxRepository.findById(entryId).orElse(null)
                        ?: return@execute false

                    processEntry(entry)
                }
                result == true
            },
            { e ->
                log.error("[Outbox] 항목 처리 실패: id={}", entryId, e)
                recoverFailedEntry(entryId, e.message)
                false
            },
            context
        )
    }

    /** 개별 항목 처리 로직 (트랜잭션 내부) */
    private fun processEntry(entry: DonationOutbox): Boolean {
        if (!entry.verifyIntegrity()) {
            handleIntegrityFailure(entry)
            return false
        }

        sendNotification(entry)
        entry.markCompleted()
        outboxRepository.save(entry)
        metrics.incrementProcessed()
        return true
    }

    /**
     * P0-1 Fix: 실패 항목 복구 (별도 트랜잭션)
     *
     * processEntry 예외 발생 시 반드시 retryCount를 증가시켜 Zombie Loop 방지
     */
    private fun recoverFailedEntry(entryId: Long, errorMessage: String?) {
        val context = TaskContext.of("Outbox", "RecoverFailed", entryId.toString())

        executor.executeOrDefault(
            {
                transactionTemplate.executeWithoutResult { status ->
                    val entry = outboxRepository.findById(entryId).orElse(null)
                        ?: return@executeWithoutResult
                    handleFailure(entry, errorMessage ?: "Unknown error")
                }
            },
            null,
            context
        )
    }

    /**
     * 무결성 검증 실패 처리 (Purple 요구사항)
     *
     * 재시도 무의미 -> 즉시 DEAD_LETTER 이동
     */
    private fun handleIntegrityFailure(entry: DonationOutbox) {
        log.error("[Outbox] 무결성 검증 실패 -> 즉시 DLQ 이동: {}", entry.requestId)
        metrics.incrementIntegrityFailure()

        entry.markFailed("Integrity verification failed - data tampering detected")
        entry.forceDeadLetter()
        outboxRepository.save(entry)

        dlqHandler.handleDeadLetter(entry, "Integrity verification failed")
    }

    /** 알림 전송 (Best-effort) */
    private fun sendNotification(entry: DonationOutbox) {
        if (entry.eventType != "DONATION_COMPLETED") {
            return
        }

        log.info("[Outbox] Donation 이벤트 처리 완료: {}", entry.requestId)
        metrics.incrementNotificationSent()
    }

    /**
     * 처리 실패 핸들링
     *
     * P0-1 Fix: 반드시 retryCount 증가 -> maxRetries 도달 시 DLQ 이동
     */
    fun handleFailure(entry: DonationOutbox, error: String?) {
        entry.markFailed(error ?: "Unknown error")
        outboxRepository.save(entry)
        metrics.incrementFailed()

        if (entry.shouldMoveToDlq()) {
            dlqHandler.handleDeadLetter(entry, error ?: "Unknown error")
        }
    }

    /**
     * Stalled 상태 복구 (JVM 크래시 대응)
     *
     * Purple Agent 요구사항: 복구 전 Content Hash 기반 무결성 검증
     */
    @ObservedTransaction("scheduler.outbox.recover_stalled")
    @Transactional("transactionManager")
    override fun recoverStalled() {
        val staleTime = LocalDateTime.now().minus(properties.staleThreshold)
        val stalledEntries = outboxRepository.findStalledProcessing(
            staleTime,
            PageRequest.of(0, properties.batchSize)
        )

        if (stalledEntries.isEmpty()) {
            return
        }

        log.info("[Outbox] Stalled 상태 발견: {}건, 무결성 검증 시작", stalledEntries.size)

        var recovered = 0
        var integrityFailed = 0

        stalledEntries.forEach { entry ->
            if (!entry.verifyIntegrity()) {
                log.error(
                    "[Outbox] 무결성 검증 실패 - Zombie 복구 중단, DLQ 이동: requestId={}",
                    entry.requestId
                )
                handleIntegrityFailure(entry)
                integrityFailed++
                return@forEach
            }

            entry.resetToRetry()
            outboxRepository.save(entry)
            recovered++
        }

        if (recovered > 0) {
            log.warn(
                "[Outbox] Stalled 상태 복구 완료: 성공={}, 무결성실패={}",
                recovered, integrityFailed
            )
            metrics.incrementStalledRecovered(recovered)
        }

        if (integrityFailed > 0) {
            log.error("[Outbox] Stalled 복구 중 무결성 검증 실패: {}건", integrityFailed)
        }
    }
}
