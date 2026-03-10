package maple.expectation.infrastructure.scheduler

import maple.expectation.core.port.out.LikeBufferStrategy
import maple.expectation.core.port.out.LikeRelationSyncPort
import maple.expectation.core.port.out.LikeSyncPort
import maple.expectation.error.exception.DistributedLockException
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import maple.expectation.infrastructure.queue.like.PartitionedFlushStrategy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.lang.Nullable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 좋아요 동기화 스케줄러 (ADR-005 이관)
 *
 * <h3>동기화 주기 (2x Multiplier)</h3>
 * <ul>
 *   <li>L1 → L2: 3초 (로컬 → Redis) - UX를 위해 빠르게 유지
 *   <li>L2 → L3 Count: 6초 (Redis → DB) - 3초의 2배
 *   <li>L2 → L3 Relation: 12초 (Redis → DB) - 6초의 2배
 * </ul>
 *
 * <h3>Configuration (application.yml)</h3>
 * <pre>
 * scheduler:
 *   like-sync:
 *     local-flush-delay-ms: 3000
 *     global-count-delay-ms: 6000
 *     global-relation-delay-ms: 12000
 * </pre>
 */
@Component
@ConditionalOnProperty(
    name = ["scheduler.like-sync.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class LikeSyncScheduler(
    private val likeSyncPort: LikeSyncPort,
    private val likeRelationSyncPort: LikeRelationSyncPort,
    private val lockStrategy: LockStrategy,
    private val executor: LogicExecutor,
    private val likeBufferStrategy: LikeBufferStrategy,
    @Nullable private val partitionedFlushStrategy: PartitionedFlushStrategy?,
) {
    private val log = LoggerFactory.getLogger(LikeSyncScheduler::class.java)

    /**
     * L1 → L2 Flush (likeCount + likeRelation)
     *
     * <p>3초 유지 - 사용자 경험(UX)을 위해 빠른 동기화 유지
     */
    @Scheduled(fixedDelayString = "\${scheduler.like-sync.local-flush-delay-ms:3000}")
    fun localFlush() {
        // likeCount 버퍼 동기화
        executor.executeVoidJava(
            { likeSyncPort.flushLocalToRedis() },
            TaskContext.of("Scheduler", "LocalFlush.Count"),
        )

        // likeRelation 버퍼 동기화
        executor.executeVoidJava(
            { likeRelationSyncPort.flushLocalToRedis() },
            TaskContext.of("Scheduler", "LocalFlush.Relation"),
        )
    }

    /**
     * L2 → L3 DB 동기화 (likeCount)
     *
     * <p>6초로 변경 (기존 5초 → 6초, 2x multiplier)
     */
    @Scheduled(fixedDelayString = "\${scheduler.like-sync.global-count-delay-ms:6000}")
    fun globalSyncCount() {
        val context = TaskContext.of("Scheduler", "GlobalSync.Count")

        // Redis 모드에서 Partitioned Flush 사용
        if (isRedisMode() && partitionedFlushStrategy != null) {
            executor.executeOrCatch(
                {
                    partitionedFlushStrategy.flushAssignedPartitions()
                    null
                },
                { e ->
                    handleSyncFailure(e, "PartitionedFlush")
                    null
                },
                context,
            )
            return
        }

        // In-Memory 모드: 기존 락 기반 동기화
        executor.executeOrCatch(
            {
                lockStrategy.executeWithLock(
                    "like-db-sync-lock",
                    0,
                    30,
                ) {
                    likeSyncPort.syncRedisToDatabase()
                    null
                }
                null
            },
            { e ->
                handleSyncFailure(e, "Count")
                null
            },
            context,
        )
    }

    /**
     * L2 → L3 DB 동기화 (likeRelation)
     *
     * <p>12초로 변경 (기존 10초 → 12초, 2x multiplier)
     */
    @Scheduled(fixedDelayString = "\${scheduler.like-sync.global-relation-delay-ms:12000}")
    fun globalSyncRelation() {
        val context = TaskContext.of("Scheduler", "GlobalSync.Relation")

        executor.executeOrCatch(
            {
                lockStrategy.executeWithLock(
                    "like-relation-sync-lock",
                    0,
                    30,
                ) {
                    likeRelationSyncPort.syncRedisToDatabase()
                    null
                }
                null
            },
            { e ->
                handleSyncFailure(e, "Relation")
                null
            },
            context,
        )
    }

    private fun isRedisMode(): Boolean = likeBufferStrategy.getType() == LikeBufferStrategy.StrategyType.REDIS

    private fun handleSyncFailure(t: Throwable, syncType: String) {
        if (t is DistributedLockException) {
            log.debug("ℹ️ [LikeSync.{}] 락 획득 스킵: 다른 서버가 동기화 진행 중", syncType)
            return
        }
        log.error("⚠️ [LikeSync.{}] 동기화 중 에러 발생: {}", syncType, t.message)
    }
}
