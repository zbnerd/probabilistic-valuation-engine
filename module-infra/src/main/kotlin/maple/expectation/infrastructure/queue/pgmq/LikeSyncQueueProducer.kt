package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.LikeSyncRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 좋아요 동기화 큐 프로듀서 (ADR-002)
 *
 * <h3>역할</h3>
 * <p>좋아요 카운트 동기화 요청을 PGMQ 큐에 발행
 *
 * <h3>메시지 형식</h3>
 * <pre>
 * {
 *   "character_name": "닉네임",
 *   "delta": 1,
 *   "requested_at": "2026-03-09T10:00:00Z"
 * }
 * </pre>
 *
 * <h3>사용 시나리오</h3>
 * <ul>
 *   <li>좋아요 버퍼에서 DB로 동기화 (Write-Behind)
 *   <li>좋아요 카운트 정합성 복구
 * </ul>
 *
 * @see LikeSyncRequest 메시지 페이로드
 * @see PgmqClient PGMQ 클라이언트
 */
@Component
class LikeSyncQueueProducer(
    private val pgmqClient: PgmqClient,
    private val executor: LogicExecutor,
) {

    /**
     * 좋아요 동기화 요청 발행
     *
     * @param characterName 캐릭터 이름
     * @param delta 증감량 (보통 1, 일괄 처리 시 더 큰 값)
     * @return 메시지 ID
     */
    fun publish(characterName: String, delta: Long = 1): Long {
        val context = TaskContext.of("LikeSyncQueue", "Publish", characterName)

        return executor.execute(
            {
                val request = LikeSyncRequest(
                    characterName = characterName,
                    delta = delta,
                    requestedAt = Instant.now().toString(),
                )

                val messageId = pgmqClient.send(QUEUE_NAME, request)
                log.debug("📤 [LikeSyncQueue] Published: character={}, delta={}, msgId={}", characterName, delta, messageId)
                messageId
            },
            context,
        )
    }

    /**
     * 일괄 좋아요 동기화 요청 발행
     *
     * <p>여러 캐릭터의 좋아요를 일괄로 동기화할 때 사용
     *
     * @param requests 동기화 요청 목록
     * @return 발행된 메시지 ID 목록
     */
    fun publishBatch(requests: List<LikeSyncRequest>): List<Long> {
        val context = TaskContext.of("LikeSyncQueue", "PublishBatch", "${requests.size} items")

        return executor.execute(
            {
                requests.map { request ->
                    pgmqClient.send(QUEUE_NAME, request).also {
                        log.debug("📤 [LikeSyncQueue] Published: character={}, delta={}, msgId={}", request.characterName, request.delta, it)
                    }
                }
            },
            context,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LikeSyncQueueProducer::class.java)

        /** 큐 이름 */
        const val QUEUE_NAME = "like_sync_queue"
    }
}
