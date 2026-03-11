package maple.expectation.infrastructure.event.outbox

import maple.expectation.core.port.out.EventProcessorPort
import maple.expectation.domain.v2.EventOutbox
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.messaging.RedisStreamPublisher
import maple.expectation.infrastructure.metrics.EventOutboxMetrics
import maple.expectation.infrastructure.persistence.repository.EventOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Event Outbox 처리 서비스 (Event Outbox Pattern)
 *
 * <h3>Financial-Grade 특성</h3>
 * - SKIP LOCKED: 분산 환경 중복 처리 방지
 * - Exponential Backoff: 재시도 간격 증가
 * - Triple Safety Net 연동: DLQ -> File -> Discord
 *
 * <h3>2-Phase 처리</h3>
 * - Phase 1: fetchAndLock (단일 트랜잭션 via facade)
 * - Phase 2: Individual processing per entry (separate transactions)
 *
 * <h3>P0 Zombie Loop 방지</h3>
 * - 실패 시 반드시 handleFailure() 호출
 * - retryCount 증가 -> maxRetries 도달 시 DLQ 이동
 *
 * @see EventOutboxFetchFacade
 * @see EventDlqHandler
 * @see RedisStreamPublisher
 */
@Service
@EnableConfigurationProperties(OutboxProperties::class)
class EventOutboxProcessor(
    private val fetchFacade: EventOutboxFetchFacade,
    private val dlqHandler: EventDlqHandler,
    private val metrics: EventOutboxMetrics,
    private val executor: LogicExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val properties: OutboxProperties,
    private val eventOutboxRepository: EventOutboxRepository,
    private val redisStreamPublisher: RedisStreamPublisher,
) : EventProcessorPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Pending 항목 폴링 및 처리
     *
     * <h4>2-Phase 처리</h4>
     * 1. Phase 1 (TX): SKIP LOCKED 조회 + markProcessing + save
     * 2. Phase 2 (항목별 TX): 개별 처리 (실패 시 다른 항목에 영향 없음)
     */
    @ObservedTransaction("scheduler.event.outbox.poll")
    override fun pollAndProcess(): Int {
        val context = TaskContext.of("EventOutbox", "PollAndProcess", properties.instanceId)

        return executor.executeOrDefault(
            {
                val locked = fetchFacade.fetchAndLock()
                if (locked.isEmpty()) {
                    return@executeOrDefault 0
                }

                log.info("[EventOutbox] 처리 시작: {}건", locked.size)
                processBatch(locked)
                locked.size
            },
            0,
            context,
        )
    }

    /**
     * Phase 2: 배치 처리 (항목별 독립 트랜잭션)
     *
     * 개별 항목 실패가 전체 배치에 영향을 주지 않음
     */
    private fun processBatch(locked: List<EventOutbox>) {
        var success = 0
        var failed = 0

        locked.forEach { entry ->
            val result = processEntryInTransaction(entry.id!!)
            if (result) success++ else failed++
        }

        log.info("[EventOutbox] 처리 완료: 성공={}, 실패={}", success, failed)
    }

    /**
     * 개별 EventOutbox 항목 처리 (독립 트랜잭션)
     *
     * <h4>Zombie Loop 방지</h4>
     * executeOrCatch로 실패 시 반드시 handleFailure() 호출 -> retryCount 증가 -> DLQ 이동
     */
    private fun processEntryInTransaction(entryId: Long): Boolean {
        val context = TaskContext.of("EventOutbox", "ProcessEntry", entryId.toString())

        return executor.executeOrCatch(
            {
                val result = transactionTemplate.execute { status ->
                    val entry = eventOutboxRepository.findById(entryId).orElse(null)
                        ?: return@execute false

                    processEntry(entry)
                }
                result == true
            },
            { e ->
                log.error("[EventOutbox] 항목 처리 실패: id={}", entryId, e)
                recoverFailedEntry(entryId, e.message)
                false
            },
            context,
        )
    }

    /** 개별 항목 처리 로직 (트랜잭션 내부) */
    private fun processEntry(entry: EventOutbox): Boolean {
        if (!entry.verifyIntegrity()) {
            handleIntegrityFailure(entry)
            return false
        }

        publishToRedisStream(entry)
        entry.markCompleted()
        eventOutboxRepository.save(entry)
        metrics.incrementProcessed()
        return true
    }

    /**
     * P0 Zombie Loop 방지: 실패 항목 복구 (별도 트랜잭션)
     *
     * processEntry 예외 발생 시 반드시 retryCount를 증가시켜 Zombie Loop 방지
     */
    private fun recoverFailedEntry(entryId: Long, errorMessage: String?) {
        val context = TaskContext.of("EventOutbox", "RecoverFailed", entryId.toString())

        executor.executeOrDefault(
            {
                transactionTemplate.executeWithoutResult { status ->
                    val entry = eventOutboxRepository.findById(entryId).orElse(null)
                        ?: return@executeWithoutResult
                    handleFailure(entry, errorMessage ?: "Unknown error")
                }
            },
            null,
            context,
        )
    }

    /**
     * 무결성 검증 실패 처리
     *
     * 재시도 무의미 -> 즉시 DEAD_LETTER 이동
     */
    private fun handleIntegrityFailure(entry: EventOutbox) {
        log.error("[EventOutbox] 무결성 검증 실패 -> 즉시 DLQ 이동: {}", entry.id)
        metrics.incrementIntegrityFailure()

        entry.markFailed("Integrity verification failed - data tampering detected")
        entry.forceDeadLetter()
        eventOutboxRepository.save(entry)

        dlqHandler.handleDeadLetter(entry, "Integrity verification failed")
    }

    /** Redis Stream에 이벤트 발행 */
    private fun publishToRedisStream(entry: EventOutbox) {
        val eventId = entry.id?.toString() ?: "unknown"
        val context = TaskContext.of("EventOutbox", "Publish", eventId)

        executor.executeOrCatch(
            {
                redisStreamPublisher.publish(
                    streamName = entry.targetStream ?: "default",
                    eventId = eventId,
                    eventType = entry.eventType ?: "unknown",
                    payload = entry.payload ?: "{}",
                )
                log.info("[EventOutbox] Redis Stream 발행 완료: eventId={}, stream={}", eventId, entry.targetStream)
                metrics.incrementPublished()
            },
            { e ->
                log.error("[EventOutbox] Redis Stream 발행 실패: eventId={}", eventId, e)
                throw e // Re-throw for retry logic
            },
            context,
        )
    }

    /**
     * 처리 실패 핸들링
     *
     * P0: 반드시 retryCount 증가 -> maxRetries 도달 시 DLQ 이동
     */
    fun handleFailure(entry: EventOutbox, error: String?) {
        entry.markFailed(error ?: "Unknown error")
        eventOutboxRepository.save(entry)
        metrics.incrementFailed()

        if (entry.shouldMoveToDlq()) {
            dlqHandler.handleDeadLetter(entry, error ?: "Unknown error")
        }
    }

    /**
     * Stalled 상태 복구 (JVM 크래시 대응)
     *
     * 복구 전 Content Hash 기반 무결성 검증
     */
    @ObservedTransaction("scheduler.event.outbox.recover_stalled")
    override fun recoverStalled() {
        val staleTime = java.time.LocalDateTime.now().minus(properties.staleThreshold)
        val stalledEntries = eventOutboxRepository.findStalledProcessing(
            staleTime,
            org.springframework.data.domain.PageRequest.of(0, properties.batchSize),
        )

        if (stalledEntries.isEmpty()) {
            return
        }

        log.info("[EventOutbox] Stalled 상태 발견: {}건, 무결성 검증 시작", stalledEntries.size)

        var recovered = 0
        var integrityFailed = 0

        stalledEntries.forEach { entry ->
            if (!entry.verifyIntegrity()) {
                log.error(
                    "[EventOutbox] 무결성 검증 실패 - Zombie 복구 중단, DLQ 이동: eventId={}",
                    entry.id,
                )
                handleIntegrityFailure(entry)
                integrityFailed++
                return@forEach
            }

            entry.resetToRetry()
            eventOutboxRepository.save(entry)
            recovered++
        }

        if (recovered > 0) {
            log.warn(
                "[EventOutbox] Stalled 상태 복구 완료: 성공={}, 무결성실패={}",
                recovered,
                integrityFailed,
            )
            metrics.incrementStalledRecovered(recovered)
        }

        if (integrityFailed > 0) {
            log.error("[EventOutbox] Stalled 복구 중 무결성 검증 실패: {}건", integrityFailed)
        }
    }
}
