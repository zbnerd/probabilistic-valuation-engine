package maple.expectation.infrastructure.external.impl

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import maple.expectation.core.domain.nexon.NexonApiEventType
import maple.expectation.infrastructure.executor.CheckedLogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.nexon.util.ContentHashUtil
import maple.expectation.infrastructure.pgmq.NexonRetryMessage
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate

/**
 * PGMQ Fallback Publisher - 실패한 API 호출을 PGMQ 큐에 발행
 *
 * <h4>기존 OutboxFallbackManager 대체</h4>
 * <p>NexonApiOutbox 테이블 대신 PGMQ nexon_retry_queue에 메시지를 발행합니다.
 *
 * <h4>책임</h4>
 * <ul>
 *   <li>멱등성 Request ID 생성
 *   <li>PGMQ 큐에 재시도 메시지 발행
 *   <li>PII 마스킹
 *   <li>활성화/비활성화 제어
 * </ul>
 */
class PgmqFallbackPublisher(
    private val pgmqClient: PgmqClient,
    private val checkedExecutor: CheckedLogicExecutor,
    private val transactionTemplate: TransactionTemplate,
    private val alertTaskExecutor: Executor,
) {

    companion object {
        private val log = LoggerFactory.getLogger(PgmqFallbackPublisher::class.java)
        private const val QUEUE_NAME = "nexon_retry_queue"
    }

    /** Fallback 활성화 여부 */
    @Volatile
    var isEnabled = true

    /**
     * 실패한 API 호출을 PGMQ 큐에 발행 (비동기)
     *
     * @param requestId 멱등성 ID
     * @param eventType API 이벤트 타입
     * @param payload 요청 파라미터
     */
    fun saveToOutbox(
        requestId: String,
        eventType: NexonApiEventType,
        payload: String,
    ) {
        if (!isEnabled) {
            log.debug("[PGMQ] Fallback 비활성화로 인해 발행 스킵. requestId={}", requestId)
            return
        }

        CompletableFuture.runAsync(
            {
                val context = TaskContext.of("PGMQ", "PublishRetry", requestId)

                checkedExecutor.executeUncheckedVoid(
                    {
                        val contentHash = ContentHashUtil.computeV1(requestId, eventType.name, payload)
                        val message = NexonRetryMessage(
                            eventType = eventType.name,
                            payload = payload,
                            retryCount = 0,
                            contentHash = contentHash,
                            requestId = requestId,
                        )

                        transactionTemplate.executeWithoutResult {
                            pgmqClient.send(QUEUE_NAME, message)
                        }

                        log.info(
                            "[PGMQ] 실패한 API 호출을 PGMQ에 발행: requestId={}, eventType={}, payload={}",
                            requestId,
                            eventType,
                            maskPayload(payload),
                        )
                    },
                    context,
                ) { e: Exception ->
                    maple.expectation.error.exception.ExternalServiceException(
                        "[PGMQ] PGMQ 발행 실패 (best-effort): requestId=$requestId",
                        e,
                    )
                }
            },
            alertTaskExecutor,
        ).exceptionally { ex ->
            log.error("[PGMQ] PGMQ 발행 비동기 실행 실패 (best-effort): requestId=$requestId", ex)
            null
        }
    }

    /**
     * 멱등성 Request ID 생성
     *
     * @param eventType API 이벤트 타입
     * @param payload 요청 파라미터
     * @return requestId (UUID-based)
     */
    fun generateRequestId(eventType: String, payload: String): String {
        val base = String.format("%s-%s-%d", eventType, payload, System.currentTimeMillis())
        return UUID.nameUUIDFromBytes(base.toByteArray()).toString()
    }

    /** PII 마스킹 */
    private fun maskPayload(payload: String?): String {
        if (payload.isNullOrEmpty() || payload.length <= 4) {
            return "***"
        }
        return payload.substring(0, 4) + "***"
    }
}
