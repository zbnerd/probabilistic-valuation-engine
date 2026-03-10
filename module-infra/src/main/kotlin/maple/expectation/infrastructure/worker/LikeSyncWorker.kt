package maple.expectation.infrastructure.worker

import maple.expectation.domain.repository.GameCharacterRepository
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.pgmq.LikeSyncRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.queue.pgmq.LikeSyncQueueProducer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 좋아요 동기화 Worker (ADR-002)
 *
 * <h3>역할</h3>
 * <p>좋아요 동기화 큐에서 메시지를 소비하고 DB에 좋아요 수 반영
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li>like_sync_queue에서 메시지 읽기</li>
 *   <li>GameCharacterRepository를 통해 좋아요 수 업데이트</li>
 *   <li>성공 시 아카이브, 실패 시 재시도 또는 삭제</li>
 * </ol>
 *
 * <h3>Feature Flag</h3>
 * <p>pgmq.worker.like-sync.enabled=true로 활성화
 *
 * @see LikeSyncQueueProducer 프로듀서
 * @see GameCharacterRepository 캐릭터 리포지토리
 */
@Component
@ConditionalOnProperty(name = ["pgmq.worker.like-sync.enabled"], havingValue = "true")
class LikeSyncWorker(
    pgmqClient: PgmqClient,
    executor: LogicExecutor,
    config: PgmqWorkerConfig,
    private val characterRepository: GameCharacterRepository,
) : PgmqWorker<LikeSyncRequest>(pgmqClient, executor, config) {

    override val queueName: String = LikeSyncQueueProducer.QUEUE_NAME
    override val payloadClass: Class<LikeSyncRequest> = LikeSyncRequest::class.java
    override val workerSettings: PgmqWorkerConfig.WorkerSettings = config.likeSync

    override fun process(message: PgmqMessage<LikeSyncRequest>): Boolean {
        val request = message.payload
        val context = TaskContext.of("LikeSyncWorker", "Process", request.characterName)

        return executor.executeOrDefault({
            log.info("🔄 [LikeSyncWorker] Processing: character={}, delta={}", request.characterName, request.delta)

            characterRepository.incrementLikeCount(request.characterName, request.delta)

            log.info("✅ [LikeSyncWorker] Completed: character={}, delta={}", request.characterName, request.delta)
            true
        }, false, context)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LikeSyncWorker::class.java)
    }
}
