package maple.expectation.infrastructure.queue.like.strategy

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.AtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RENAME 기반 원자적 Fetch 전략 (폴백)
 */
class RenameAtomicFetchStrategy(
    private val redisTemplate: StringRedisTemplate,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val tempKeyTtlSeconds: Int
) : AtomicFetchStrategy {

    override fun fetchAndDelete(key: String): MutableMap<String, String> {
        return executor.executeOrDefault(
            {
                val tempKey = "${key}:temp:${System.nanoTime()}"

                // RENAME (원자적 이동)
                redisTemplate.renameIfAbsent(key, tempKey)

                // 임시 키에서 모든 필드 조회
                val entries = redisTemplate.opsForHash<String, String>().entries(tempKey)

                // 임시 키 삭제
                redisTemplate.delete(tempKey)

                entries.toMutableMap()
            },
            mutableMapOf(),
            TaskContext.of("RenameAtomicFetchStrategy", "FetchAndDelete", key)
        ) ?: mutableMapOf()
    }

    override fun getStrategyType(): AtomicFetchStrategy.StrategyType =
        AtomicFetchStrategy.StrategyType.RENAME
}
