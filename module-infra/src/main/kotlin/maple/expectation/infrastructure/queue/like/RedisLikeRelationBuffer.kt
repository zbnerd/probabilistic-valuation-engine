package maple.expectation.infrastructure.queue.like

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeRelationBufferStrategy
import maple.expectation.core.port.out.LikeRelationBufferStrategy.StrategyType
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.redisson.api.RScript
import org.redisson.api.RSet
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * Redis 기반 좋아요 관계 버퍼 (#271 V5 Stateless Architecture)
 */
class RedisLikeRelationBuffer(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    meterRegistry: MeterRegistry
) : LikeRelationBufferStrategy {

    private val meterRegistry: MeterRegistry
    private val relationsKey: String
    private val pendingKey: String
    private val unlikedKey: String
    private val fetchPendingSha = AtomicReference<String>()

    init {
        this.meterRegistry = meterRegistry
        this.relationsKey = RedisKey.LIKE_RELATIONS.key
        this.pendingKey = RedisKey.LIKE_RELATIONS_PENDING.key
        this.unlikedKey = RedisKey.LIKE_RELATIONS_UNLIKED.key
        registerMetrics()
        log.info("[RedisLikeRelationBuffer] Initialized with keys: {}, {}, {}", relationsKey, pendingKey, unlikedKey)
    }

    override fun getType(): StrategyType = StrategyType.REDIS

    // Private Redis set properties for efficient access
    private val relationSetProp: RSet<String> get() = redissonClient.getSet(relationsKey)
    private val pendingSetProp: RSet<String> get() = redissonClient.getSet(pendingKey)
    private val unlikedSetProp: RSet<String> get() = redissonClient.getSet(unlikedKey)

    private fun registerMetrics() {
        Gauge.builder("like.relation.buffer.total", this) { buffer -> buffer.getRelationsSize().toDouble() }
            .description("Redis 버퍼의 전체 좋아요 관계 수")
            .register(meterRegistry)

        Gauge.builder("like.relation.buffer.pending", this) { buffer -> buffer.getPendingSize().toDouble() }
            .description("DB 동기화 대기 중인 좋아요 관계 수")
            .register(meterRegistry)
    }

    override fun addRelation(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        return executor.executeOrDefault(
            {
                val isNew = relationSetProp.add(relationKey)

                if (isNew) {
                    pendingSetProp.add(relationKey)
                    meterRegistry.counter("like.relation.add.success").increment()
                    log.debug("[LikeRelation] Added: {}", relationKey)
                } else {
                    meterRegistry.counter("like.relation.add.duplicate").increment()
                    log.debug("[LikeRelation] Duplicate: {}", relationKey)
                }

                isNew
            },
            null,
            TaskContext.of("LikeRelation", "Add", relationKey)
        )
    }

    override fun exists(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        return executor.executeOrDefault(
            { relationSetProp.contains(relationKey) },
            null,
            TaskContext.of("LikeRelation", "Exists", relationKey)
        )
    }

    override fun fetchAndRemovePending(limit: Int): Set<String> {
        return executor.executeOrDefault(
            { doFetchAndRemovePending(limit) },
            emptySet(),
            TaskContext.of("LikeRelation", "FetchPending")
        ) ?: emptySet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun doFetchAndRemovePending(limit: Int): Set<String> {
        val script = redissonClient.getScript(StringCodec.INSTANCE)
        val sha = fetchPendingSha.get()

        val result = executor.executeOrCatch(
            { evalPendingWithCachedSha(script, sha, limit) },
            { e -> evalPendingWithReloadedSha(script, limit) },
            TaskContext.of("LikeRelation", "EvalScript")
        ) as List<String>

        if (result.isNotEmpty()) {
            meterRegistry.counter("like.relation.pending.fetched").increment(result.size.toDouble())
            log.info("[LikeRelation] FetchedPending: {} entries", result.size)
        }

        return result.toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalPendingWithCachedSha(script: RScript, sha: String?, limit: Int): List<String> {
        if (sha == null) {
            throw IllegalStateException("SHA not cached")
        }
        return script.evalSha(
            RScript.Mode.READ_WRITE,
            sha,
            RScript.ReturnType.MULTI,
            listOf(pendingKey),
            limit.toString()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalPendingWithReloadedSha(script: RScript, limit: Int): List<String> {
        val sha = script.scriptLoad(LUA_FETCH_PENDING)
        fetchPendingSha.set(sha)
        return script.evalSha(
            RScript.Mode.READ_WRITE,
            sha,
            RScript.ReturnType.MULTI,
            listOf(pendingKey),
            limit.toString()
        )
    }

    override fun removeRelation(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        return executor.executeOrDefault(
            {
                val removed = relationSetProp.remove(relationKey)
                if (removed) {
                    pendingSetProp.remove(relationKey)
                    meterRegistry.counter("like.relation.remove.success").increment()
                    log.debug("[LikeRelation] Removed: {}", relationKey)
                }
                removed
            },
            null,
            TaskContext.of("LikeRelation", "Remove", relationKey)
        )
    }

    override fun existsInUnliked(accountId: String, targetOcid: String): Boolean? {
        val relationKey = buildRelationKey(accountId, targetOcid)

        return executor.executeOrDefault(
            { unlikedSetProp.contains(relationKey) },
            null,
            TaskContext.of("LikeRelation", "ExistsUnliked", relationKey)
        )
    }

    fun getRelationSet(): RSet<String> = redissonClient.getSet(relationsKey)

    fun getPendingSet(): RSet<String> = redissonClient.getSet(pendingKey)

    fun getUnlikedSet(): RSet<String> = redissonClient.getSet(unlikedKey)

    override fun getRelationsSize(): Int =
        executor.executeOrDefault(
            { relationSetProp.size },
            0,
            TaskContext.of("LikeRelation", "Size")
        )

    override fun getPendingSize(): Int =
        executor.executeOrDefault(
            { pendingSetProp.size },
            0,
            TaskContext.of("LikeRelation", "PendingSize")
        )

    override fun buildRelationKey(accountId: String, targetOcid: String): String {
        return "$accountId:$targetOcid"
    }

    override fun parseRelationKey(relationKey: String): Array<String> {
        return relationKey.split(":".toRegex(), 2).toTypedArray()
    }

    fun getRelationsKey(): String = relationsKey

    fun getPendingKey(): String = pendingKey

    companion object {
        private val LUA_FETCH_PENDING = """
            -- Fetch and remove entries from pending set atomically
            -- Returns: list of removed members
            local pending_key = KEYS[1]
            local limit = tonumber(ARGV[1])

            local members = redis.call('SRANDMEMBER', pending_key, limit)
            if members and #members > 0 then
                redis.call('SREM', pending_key, unpack(members))
            end

            return members or {}
            """

        private val log = LoggerFactory.getLogger(RedisLikeRelationBuffer::class.java)
    }
}
