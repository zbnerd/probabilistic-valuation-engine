package maple.expectation.infrastructure.cache.like

import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeRelationBufferStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 좋아요 관계 버퍼 (L1 Caffeine + L2 Redis)
 *
 * 계층 구조:
 * - L1 (Caffeine): 로컬 버퍼 - 빠른 중복 체크
 * - L2 (Redis): 글로벌 버퍼 - 분산 환경 중복 체크
 * - L3 (DB): 영구 저장 - 배치 동기화
 *
 * @see maple.expectation.infrastructure.queue.like.RedisLikeRelationBuffer Redis 전용 구현
 */
@Component
@ConditionalOnProperty(name = ["app.buffer.redis.enabled"], havingValue = "false")
class HybridLikeRelationBuffer(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    registry: MeterRegistry
) : LikeRelationBufferStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(HybridLikeRelationBuffer::class.java)
        private const val REDIS_SET_KEY = "buffer:like:relations"
        private const val REDIS_PENDING_SET_KEY = "buffer:like:relations:pending"
    }

    /** L1 캐시: 로컬 중복 체크용 */
    val localCache = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, Boolean>()

    /** L1 Pending Set: L2 동기화 대기 중인 관계 */
    val localPendingSet = ConcurrentHashMap<String, Boolean>()

    init {
        Gauge.builder("like.relation.buffer.l1.size", this) { it.localCache.estimatedSize().toDouble() }
            .description("L1 버퍼링된 좋아요 관계 수 (Caffeine)")
            .register(registry)

        Gauge.builder("like.relation.buffer.l1.pending", this) { it.localPendingSet.size.toDouble() }
            .description("L2 동기화 대기 중인 좋아요 관계 수")
            .register(registry)
    }

    override fun getType() = LikeRelationBufferStrategy.StrategyType.IN_MEMORY

    override fun addRelation(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        // 1. L1 빠른 체크
        if (localCache.getIfPresent(relationKey) != null) {
            log.debug("🔄 [LikeRelation] L1 중복 감지: {}", relationKey)
            return false
        }

        // 2. L2 원자적 중복 검사 + 추가
        val isNew = executor.executeOrDefault(
            { getRelationSet().add(relationKey) },
            null,
            TaskContext.of("LikeRelation", "L2Add", relationKey)
        ) ?: return null // Redis 장애

        if (isNew) {
            // 3. L1에 추가 + Pending Set에 등록
            localCache.put(relationKey, true)
            localPendingSet[relationKey] = true

            executor.executeVoid(
                { getPendingSet().add(relationKey) },
                TaskContext.of("LikeRelation", "AddPending", relationKey)
            )

            log.debug("✅ [LikeRelation] 새 관계 추가: {}", relationKey)
        } else {
            // L2에서 중복 감지 → L1에도 추가
            localCache.put(relationKey, true)
            log.debug("🔄 [LikeRelation] L2 중복 감지: {}", relationKey)
        }

        return isNew
    }

    override fun exists(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        // L1 체크
        if (localCache.getIfPresent(relationKey) != null) {
            return true
        }

        // L2 체크
        return executor.executeOrDefault(
            {
                val exists = getRelationSet().contains(relationKey)
                if (exists) {
                    localCache.put(relationKey, true)
                }
                exists
            },
            null,
            TaskContext.of("LikeRelation", "Exists", relationKey)
        )
    }

    override fun removeRelation(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        // L1 제거
        localCache.invalidate(relationKey)
        localPendingSet.remove(relationKey)

        // L2 제거
        return executor.executeOrDefault(
            {
                val removed = getRelationSet().remove(relationKey)
                getPendingSet().remove(relationKey)
                removed
            },
            false,
            TaskContext.of("LikeRelation", "Remove", relationKey)
        )
    }

    override fun fetchAndRemovePending(limit: Int): Set<String> {
        return executor.executeOrDefault(
            {
                val result = mutableSetOf<String>()
                val pendingSet = getPendingSet()

                repeat(limit) {
                    val relationKey = pendingSet.removeRandom() ?: return@repeat
                    result.add(relationKey)
                }

                result
            },
            emptySet(),
            TaskContext.of("LikeRelation", "FetchPending")
        )
    }

    override fun buildRelationKey(accountId: String, targetOcid: String): String {
        return "$accountId:$targetOcid"
    }

    override fun parseRelationKey(relationKey: String): Array<String> {
        return relationKey.split(":", limit = 2).toTypedArray()
    }

    override fun existsInUnliked(accountId: String, targetOcid: String): Boolean? = false

    override fun getRelationsSize(): Int {
        return executor.executeOrDefault(
            { getRelationSet().size },
            0,
            TaskContext.of("LikeRelation", "GetSize")
        )
    }

    override fun getPendingSize(): Int {
        return executor.executeOrDefault(
            { getPendingSet().size },
            0,
            TaskContext.of("LikeRelation", "GetPendingSize")
        )
    }

    /** L1 Pending → L2 동기화 */
    fun flushLocalToRedis() {
        if (localPendingSet.isEmpty()) return

        localPendingSet.forEach { (relationKey, _) ->
            executor.executeOrCatch(
                {
                    getRelationSet().add(relationKey)
                    getPendingSet().add(relationKey)
                    localPendingSet.remove(relationKey)
                    null
                },
                { e ->
                    log.warn("⚠️ [LikeRelation] L1→L2 동기화 실패: {}", relationKey)
                    null
                },
                TaskContext.of("LikeRelation", "L1toL2", relationKey)
            )
        }
    }

    private fun getRelationSet() = redissonClient.getSet<String>(REDIS_SET_KEY)
    private fun getPendingSet() = redissonClient.getSet<String>(REDIS_PENDING_SET_KEY)
}
