package maple.expectation.infrastructure.nexon.pgmq

import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import maple.expectation.core.domain.nexon.NexonApiEventType
import maple.expectation.core.port.out.NexonApiOutboxProcessorPort
import maple.expectation.core.port.out.ShutdownDataPersistencePort
import maple.expectation.infrastructure.alert.StatelessAlertService
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.NexonApiClient
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.nexon.util.ContentHashUtil
import maple.expectation.infrastructure.pgmq.NexonRetryMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.util.ExceptionUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Nexon API PGMQ 기반 재시도 프로세서 (Phase 3 - Outbox to PGMQ Migration)
 *
 * <h3>기존 NexonApiOutboxProcessor 대체</h3>
 * <p>Outbox 테이블 폴링 대신 PGMQ 큐를 폴링하여 재시도 처리
 *
 * <h3>핵심 기능</h3>
 * <ul>
 *   <li>PGMQ 큐 폴링 및 메시지 소비
 *   <li>Event Type 기반 API 호출 분기
 *   <li>Exponential Backoff (visibility timeout 활용)
 *   <li>DLQ: File backup → Discord alert → PGMQ delete
 *   <li>Content Hash 무결성 검증
 * </ul>
 *
 * <h3>PgmqWorker 비상속 이유</h3>
 * <p>NexonApiOutboxProcessorPort 인터페이스를 구현해야 하고,
 * Kotlin은 단일 상속만 지원하므로 독립 @Component로 구현
 *
 * @see NexonApiOutboxProcessorPort
 * @see NexonApiPgmqMetrics
 */
@Component
@Suppress("DEPRECATION")
class NexonApiPgmqProcessor(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
    @Qualifier("realNexonApiClient") private val nexonApiClient: NexonApiClient,
    private val metrics: NexonApiPgmqMetrics,
    private val statelessAlertService: StatelessAlertService,
    private val fileBackupService: ShutdownDataPersistencePort,
    private val lifecycleWrapper: ScheduledTaskLifecycleWrapper,
) : NexonApiOutboxProcessorPort {

    companion object {
        private val log = LoggerFactory.getLogger(NexonApiPgmqProcessor::class.java)
        private const val QUEUE_NAME = "nexon_retry_queue"
        private const val MAX_RETRIES = 3
        private const val BASE_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_SECONDS = 3600L
        private const val BATCH_SIZE = 10
        private const val VISIBILITY_TIMEOUT_SEC = 30
    }

    @Value("\${app.nexon.api.timeout:10}")
    private var apiTimeoutSeconds: Long = 10

    /**
     * PGMQ 큐 폴링 및 메시지 처리
     */
    @Scheduled(fixedDelayString = "\${nexon.retry.polling-interval-ms:10000}")
    override fun pollAndProcess() {
        if (!lifecycleWrapper.beforeTask()) return
        val context = TaskContext.of("NexonApiPgmqProcessor", "PollAndProcess", QUEUE_NAME)
        executor.executeVoid({ performPollAndProcess() }, context)
        lifecycleWrapper.afterTask()
    }

    /**
     * Stalled 복구 — PGMQ에서는 visibility timeout 만료로 자동 복구됨
     */
    @Scheduled(fixedDelayString = "\${nexon.retry.recover-stalled-interval-ms:300000}")
    override fun recoverStalled() {
        log.debug("[NexonApiPgmqProcessor] recoverStalled() — PGMQ VT 만료로 자동 복구됨")
        metrics.updatePendingCount()
    }

    private fun performPollAndProcess() {
        val messages = pgmqClient.read(QUEUE_NAME, NexonRetryMessage::class.java, BATCH_SIZE, VISIBILITY_TIMEOUT_SEC)

        if (messages.isEmpty()) return

        log.info("[NexonApiPgmqProcessor] Polled {} messages from {}", messages.size, QUEUE_NAME)

        messages.forEach { message ->
            processMessage(message)
        }

        metrics.updatePendingCount()
    }

    private fun processMessage(message: PgmqMessage<NexonRetryMessage>) {
        val payload = message.payload
        val context = TaskContext.of("NexonApiPgmqProcessor", "ProcessMessage", payload.requestId)

        executor.executeVoid({
            // 1. Content hash 검증
            val expectedHash = ContentHashUtil.computeV1(
                payload.requestId,
                payload.eventType,
                payload.payload,
            )
            if (payload.contentHash != expectedHash) {
                log.error(
                    "[NexonApiPgmqProcessor] Content hash mismatch: msgId={}, requestId={}",
                    message.messageId,
                    payload.requestId,
                )
                moveToDlq(message, "Content hash mismatch")
                metrics.incrementIntegrityFailure()
                return@executeVoid
            }

            // 2. API 호출
            val success = callNexonApi(payload)

            if (success) {
                pgmqClient.archive(QUEUE_NAME, message.messageId)
                log.info(
                    "[NexonApiPgmqProcessor] Archived: msgId={}, requestId={}",
                    message.messageId,
                    payload.requestId,
                )
                metrics.incrementApiCallSuccess()
                metrics.incrementProcessed()
            } else {
                handleRetry(message)
            }
        }, context)
    }

    private fun callNexonApi(payload: NexonRetryMessage): Boolean {
        val eventType = try {
            NexonApiEventType.valueOf(payload.eventType)
        } catch (_: IllegalArgumentException) {
            log.error("[NexonApiPgmqProcessor] Unknown eventType: {}", payload.eventType)
            return false
        }

        return try {
            when (eventType) {
                NexonApiEventType.GET_OCID -> {
                    nexonApiClient.getOcidByCharacterName(payload.payload)
                        .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                        .join()
                }
                NexonApiEventType.GET_CHARACTER_BASIC -> {
                    nexonApiClient.getCharacterBasic(payload.payload)
                        .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                        .join()
                }
                NexonApiEventType.GET_ITEM_DATA -> {
                    nexonApiClient.getItemDataByOcid(payload.payload)
                        .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                        .join()
                }
                NexonApiEventType.GET_CUBES -> {
                    nexonApiClient.getCubeHistory(payload.payload)
                        .orTimeout(apiTimeoutSeconds, TimeUnit.SECONDS)
                        .join()
                }
            }
            log.info("[NexonApiPgmqProcessor] API call success: eventType={}, payload={}", eventType, maskPayload(payload.payload))
            true
        } catch (e: Exception) {
            val root = ExceptionUtils.unwrapAsyncException(e)

            if (root is WebClientResponseException && root.statusCode.is4xxClientError) {
                log.warn(
                    "[NexonApiPgmqProcessor] 4xx 오류 (재시도 불가): eventType={}, status={}",
                    eventType,
                    root.statusCode,
                )
                return false
            }

            log.warn(
                "[NexonApiPgmqProcessor] 일시적 장애: eventType={}, error={}",
                eventType,
                root?.message,
            )
            metrics.incrementApiCallRetry()
            false
        }
    }

    private fun handleRetry(message: PgmqMessage<NexonRetryMessage>) {
        // readCount: PGMQ가 자동 추적. readCount-1 = 재시도 횟수 (readCount=1은 초기 시도)
        val retryCount = message.readCount - 1

        if (retryCount >= MAX_RETRIES) {
            moveToDlq(message, "Max retries ($MAX_RETRIES) exceeded (readCount=${message.readCount})")
            return
        }

        val backoffSeconds = min(
            2.0.pow(retryCount.toDouble()).toLong() * BASE_BACKOFF_SECONDS,
            MAX_BACKOFF_SECONDS,
        )
        pgmqClient.setVisibilityTimeout(QUEUE_NAME, message.messageId, backoffSeconds)
        log.info(
            "[NexonApiPgmqProcessor] Set VT: msgId={}, retry={}, backoff={}s",
            message.messageId,
            retryCount,
            backoffSeconds,
        )
        metrics.incrementFailed()
    }

    /**
     * DLQ 처리: File backup → Discord alert → PGMQ archive
     *
     * <p>순서 보장: backup 성공 후에만 archive 수행 (메시지 영구 손실 방지)
     * <p>Archive로 전환 (#646): DLQ replay worker가 재처리 가능
     */
    private fun moveToDlq(message: PgmqMessage<NexonRetryMessage>, reason: String) {
        val context = TaskContext.of("NexonApiPgmqProcessor", "MoveToDlq", "msgId=${message.messageId}")

        executor.executeVoid({
            // 1. File backup (반드시 먼저 — replay 실패 시 안전망)
            fileBackupService.appendOutboxEntry(
                message.payload.requestId,
                "eventType=${message.payload.eventType}, payload=${message.payload.payload}, reason=$reason",
            )
            log.warn(
                "[DLQ] Backed up message: msgId={}, requestId={}",
                message.messageId,
                message.payload.requestId,
            )
            metrics.incrementDlqFileBackup()

            // 2. Discord alert (best-effort)
            sendDlqAlert(message, reason)

            // 3. PGMQ에서 아카이브 (backup 성공 확인 후 — replay 가능)
            pgmqClient.archive(QUEUE_NAME, message.messageId)
            log.info("[DLQ] Archived from queue: msgId={}, reason={}", message.messageId, reason)
            metrics.incrementDlq()
        }, context)
    }

    private fun sendDlqAlert(message: PgmqMessage<NexonRetryMessage>, reason: String) {
        val alertContext = TaskContext.of("DLQ", "Alert", message.payload.requestId)
        executor.executeOrCatch(
            {
                val title = "NEXON API PGMQ CRITICAL FAILURE"
                val description = """
                    RequestId: ${message.payload.requestId}
                    EventType: ${message.payload.eventType}
                    Reason: $reason
                    Manual intervention required!
                """.trimIndent()
                statelessAlertService.sendCritical(title, description, null)
            },
            { e -> log.warn("[DLQ] Discord alert 실패: {}", e.message) },
            alertContext,
        )
    }

    private fun maskPayload(payload: String?): String {
        if (payload == null || payload.length <= 4) return "***"
        return payload.substring(0, 4) + "***"
    }
}
