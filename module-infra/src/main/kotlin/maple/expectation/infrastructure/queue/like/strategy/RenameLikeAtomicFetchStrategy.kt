package maple.expectation.infrastructure.queue.like.strategy
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.dto.like.FetchResult
import maple.expectation.core.port.out.like.LikeAtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RENAME 기반 원자적 Fetch 전략 (Fallback)
 *
 * <p>RENAME 명령으로 원본 키를 임시 키로 이동 후 데이터를 조회.
 * Lua Script 미지원 환경에서 사용.
 */
class RenameLikeAtomicFetchStrategy(
    private val redisTemplate: StringRedisTemplate,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val tempKeyTtlSeconds: Int,
) : LikeAtomicFetchStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(RenameLikeAtomicFetchStrategy::class.java)
    }

    override fun fetchAndMove(sourceKey: String, tempKey: String): FetchResult = executor.executeOrDefault(
        {
            // RENAME으로 원자적 이동 (원본 키 즉시 비움)
            redisTemplate.rename(sourceKey, tempKey)

            // 임시 키에서 모든 필드 조회
            val entries = redisTemplate.opsForHash<String, String>().entries(tempKey)

            // Map<String, String> -> Map<String, Long>
            val data = entries.entries.associate {
                val count = it.value.toLongOrNull() ?: 0L
                it.key to count
            }

            FetchResult(tempKey, data)
        },
        FetchResult.empty(),
        TaskContext.of("RenameLikeAtomicFetchStrategy", "FetchAndMove", sourceKey),
    ) ?: FetchResult.empty()

    override fun restore(tempKey: String?, sourceKey: String) {
        if (tempKey == null) return

        executor.executeVoid(
            {
                val entries = redisTemplate.opsForHash<String, String>().entries(tempKey)
                if (entries.isEmpty()) {
                    log.debug("No entries to restore from tempKey: {}", tempKey)
                } else {
                    // 원본 키로 데이터 복원
                    entries.forEach { (key, value) ->
                        redisTemplate.opsForHash<String, String>().increment(sourceKey, key, value.toLongOrNull() ?: 0L)
                    }

                    meterRegistry.counter("like.sync.restore", "strategy", "rename").increment()
                    log.info("Restored {} entries from {} to {}", entries.size, tempKey, sourceKey)
                }
            },
            TaskContext.of("RenameLikeAtomicFetchStrategy", "Restore", tempKey),
        )
    }

    override fun deleteTempKey(tempKey: String?) {
        if (tempKey == null) return

        executor.executeVoid(
            {
                redisTemplate.delete(tempKey)
                meterRegistry.counter("like.sync.delete", "strategy", "rename").increment()
            },
            TaskContext.of("RenameLikeAtomicFetchStrategy", "DeleteTempKey", tempKey),
        )
    }

    override fun strategyName(): String = "rename"
}
