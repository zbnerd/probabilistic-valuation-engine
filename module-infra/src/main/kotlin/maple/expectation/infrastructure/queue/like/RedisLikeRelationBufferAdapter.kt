package maple.expectation.infrastructure.queue.like

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeRelationBufferStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RedissonClient

/**
 * Redis 기반 Like Relation Buffer 어댑터
 */
class RedisLikeRelationBufferAdapter(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeRelationBufferStrategy {

    companion object {
        private const val RELATIONS_KEY = "like:relations"
        private const val PENDING_KEY = "like:relations:pending"
        private const val UNLIKED_KEY = "like:relations:unliked"
    }

    override fun getType(): LikeRelationBufferStrategy.StrategyType =
        LikeRelationBufferStrategy.StrategyType.REDIS

    override fun addRelation(accountId: String, targetOcid: String): Boolean? {
        return executor.executeOrDefault(
            {
                val key = buildRelationKey(accountId, targetOcid)
                val relations = redissonClient.getSet<String>(RELATIONS_KEY)
                relations.add(key)
            },
            null,
            TaskContext.of("RedisLikeRelationBuffer", "AddRelation", accountId)
        )
    }

    override fun exists(accountId: String, targetOcid: String): Boolean? {
        return executor.executeOrDefault(
            {
                val key = buildRelationKey(accountId, targetOcid)
                val relations = redissonClient.getSet<String>(RELATIONS_KEY)
                relations.contains(key)
            },
            null,
            TaskContext.of("RedisLikeRelationBuffer", "Exists", accountId)
        )
    }

    override fun removeRelation(accountId: String, targetOcid: String): Boolean? {
        return executor.executeOrDefault(
            {
                val key = buildRelationKey(accountId, targetOcid)
                val relations = redissonClient.getSet<String>(RELATIONS_KEY)
                relations.remove(key)
            },
            null,
            TaskContext.of("RedisLikeRelationBuffer", "RemoveRelation", accountId)
        )
    }

    override fun fetchAndRemovePending(limit: Int): Set<String> {
        return executor.executeOrDefault(
            {
                val pending = redissonClient.getSet<String>(PENDING_KEY)
                val resultSet = mutableSetOf<String>()
                var count = 0
                val iterator = pending.iterator()
                while (iterator.hasNext() && count < limit) {
                    resultSet.add(iterator.next())
                    iterator.remove()
                    count++
                }
                resultSet.toSet()
            },
            emptySet(),
            TaskContext.of("RedisLikeRelationBuffer", "FetchAndRemovePending", limit.toString())
        ) ?: emptySet()
    }

    override fun buildRelationKey(accountId: String, targetOcid: String): String =
        "$accountId:$targetOcid"

    override fun parseRelationKey(relationKey: String): Array<String> =
        relationKey.split(":").toTypedArray()

    override fun existsInUnliked(accountId: String, targetOcid: String): Boolean? {
        return executor.executeOrDefault(
            {
                val key = buildRelationKey(accountId, targetOcid)
                val unliked = redissonClient.getSet<String>(UNLIKED_KEY)
                unliked.contains(key)
            },
            null,
            TaskContext.of("RedisLikeRelationBuffer", "ExistsInUnliked", accountId)
        )
    }

    override fun getRelationsSize(): Int {
        return executor.executeOrDefault(
            { redissonClient.getSet<String>(RELATIONS_KEY).size },
            0,
            TaskContext.of("RedisLikeRelationBuffer", "GetRelationsSize", "")
        )
    }

    override fun getPendingSize(): Int {
        return executor.executeOrDefault(
            { redissonClient.getSet<String>(PENDING_KEY).size },
            0,
            TaskContext.of("RedisLikeRelationBuffer", "GetPendingSize", "")
        )
    }
}
