package maple.expectation.infrastructure.nexon.outbox

import maple.expectation.core.port.out.NexonApiOutboxProcessorPort
import maple.expectation.domain.v2.NexonApiOutbox
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ExternalApiException
import maple.expectation.infrastructure.aop.annotation.ObservedTransaction
import maple.expectation.infrastructure.config.OutboxProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.nexon.dlq.NexonApiDlqHandler
import maple.expectation.infrastructure.persistence.repository.NexonApiOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Nexon API Outbox 처리 서비스 (N19: Outbox Replay Pattern)
 */
@Service
class NexonApiOutboxProcessor(
    private val fetchFacade: NexonApiOutboxFetchFacade,
    private val outboxRepository: NexonApiOutboxRepository,
    private val retryClient: NexonApiRetryClient,
    private val metrics: NexonApiOutboxMetrics,
    private val executor: LogicExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val properties: OutboxProperties,
    private val dlqHandler: NexonApiDlqHandler,
) : NexonApiOutboxProcessorPort {

    private val log = LoggerFactory.getLogger(NexonApiOutboxProcessor::class.java)

    @ObservedTransaction("scheduler.nexon_api_outbox.poll")
    override fun pollAndProcess() {
        val context = TaskContext.of("NexonApiOutbox", "PollAndProcess", properties.instanceId)

        executor.executeOrCatch(
            {
                val locked = fetchFacade.fetchAndLock()
                if (locked.isEmpty()) {
                    return@executeOrCatch null
                }

                log.info("[NexonApiOutbox] 처리 시작: {}건", locked.size)
                processBatch(locked)
                null
            },
            { e ->
                log.error("[NexonApiOutbox] 폴링 실패", e)
                metrics.incrementPollFailure()
                null
            },
            context
        )
    }

    private fun processBatch(locked: List<NexonApiOutbox>) {
        var success = 0
        var failed = 0

        for (entry in locked) {
            val result = processEntryInTransaction(entry.id!!)
            if (result) success++
            else failed++
        }

        log.info("[NexonApiOutbox] 처리 완료: 성공={}, 실패={}", success, failed)
    }

    private fun processEntryInTransaction(entryId: Long): Boolean {
        val context = TaskContext.of("NexonApiOutbox", "ProcessEntry", entryId.toString())

        return executor.executeOrCatch(
            {
                val result = transactionTemplate.execute<Boolean> { status ->
                    val entry = outboxRepository.findById(entryId).orElse(null)
                        ?: return@execute false

                    processEntry(entry)
                }
                true == result
            },
            { e ->
                log.error("[NexonApiOutbox] 항목 처리 실패: id={}", entryId, e)
                recoverFailedEntry(entryId, e.message ?: "Unknown error")
                false
            },
            context
        )
    }

    private fun processEntry(entry: NexonApiOutbox): Boolean {
        if (!verifyIntegrity(entry)) {
            handleIntegrityFailure(entry)
            return false
        }

        val apiSuccess = retryClient.processOutboxEntry(entry)

        if (!apiSuccess) {
            throw ExternalApiException(
                CommonErrorCode.EXTERNAL_API_ERROR,
                "Nexon API call failed: %s",
                entry.requestId
            )
        }

        entry.markCompleted()
        outboxRepository.save(entry)
        metrics.incrementProcessed()
        return true
    }

    private fun verifyIntegrity(entry: NexonApiOutbox): Boolean {
        return entry.verifyIntegrity()
    }

    private fun recoverFailedEntry(entryId: Long, errorMessage: String) {
        val context = TaskContext.of("NexonApiOutbox", "RecoverFailed", entryId.toString())

        executor.executeOrDefault(
            {
                transactionTemplate.executeWithoutResult { status ->
                    val entry = outboxRepository.findById(entryId).orElse(null)
                        ?: return@executeWithoutResult

                    handleFailure(entry, errorMessage)
                }
                null
            },
            null,
            context
        )
    }

    private fun handleIntegrityFailure(entry: NexonApiOutbox) {
        log.error("[NexonApiOutbox] 무결성 검증 실패 -> 즉시 DLQ 이동: {}", entry.requestId)
        metrics.incrementIntegrityFailure()

        val reason = "Integrity verification failed - data tampering detected"
        entry.markFailed(reason)
        entry.forceDeadLetter()
        outboxRepository.save(entry)

        dlqHandler.handleDeadLetter(entry, reason)
    }

    fun handleFailure(entry: NexonApiOutbox, error: String) {
        entry.markFailed(error)
        outboxRepository.save(entry)
        metrics.incrementFailed()

        if (entry.shouldMoveToDlq()) {
            log.warn(
                "[NexonApiOutbox] DLQ 이동: requestId={}, retryCount={}",
                entry.requestId,
                entry.retryCount
            )
            metrics.incrementDlq()

            dlqHandler.handleDeadLetter(entry, error)
        }
    }

    @ObservedTransaction("scheduler.nexon_api_outbox.recover_stalled")
    @Transactional
    override fun recoverStalled() {
        val staleTime = LocalDateTime.now().minus(properties.staleThreshold)
        val stalledEntries = outboxRepository.findStalledProcessing(
            staleTime,
            PageRequest.of(0, properties.batchSize)
        )

        if (stalledEntries.isEmpty()) {
            return
        }

        log.info("[NexonApiOutbox] Stalled 상태 발견: {}건, 무결성 검증 시작", stalledEntries.size)

        var recovered = 0
        var integrityFailed = 0

        for (entry in stalledEntries) {
            if (!verifyIntegrity(entry)) {
                log.error(
                    "[NexonApiOutbox] 무결성 검증 실패 - Zombie 복구 중단, DLQ 이동: requestId={}",
                    entry.requestId
                )
                handleIntegrityFailure(entry)
                integrityFailed++
                continue
            }

            entry.resetToRetry()
            outboxRepository.save(entry)
            recovered++
        }

        if (recovered > 0) {
            log.warn("[NexonApiOutbox] Stalled 상태 복구 완료: 성공={}, 무결성실패={}", recovered, integrityFailed)
            metrics.incrementStalledRecovered(recovered)
        }

        if (integrityFailed > 0) {
            log.error("[NexonApiOutbox] Stalled 복구 중 무결성 검증 실패: {}건", integrityFailed)
        }
    }
}
