package maple.expectation.infrastructure.queue.like.strategy

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.dto.like.FetchResult
import maple.expectation.core.port.out.like.LikeAtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory

/**
 * Lua Script 기반 원자적 Fetch 전략 (Like 동기화용)
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li>Lua Script로 RENAME + HGETALL + DEL 원자적 실행</li>
 *   <li>JVM 크래시 시 임시 키로 복구 가능</li>
 *   <li>TTL 설정으로 메모리 누수 방지</li>
 * </ul>
 *
 * @since 2.0.0
 */
class LuaScriptLikeAtomicFetchStrategy(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val tempKeyTtlSeconds: Int,
) : LikeAtomicFetchStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(LuaScriptLikeAtomicFetchStrategy::class.java)

        private val LUA_FETCH_AND_MOVE = """
            local sourceKey = KEYS[1]
            local tempKey = KEYS[2]
            local ttl = tonumber(ARGV[1])

            -- 원본 키를 임시 키로 이동 (원자적)
            local exists = redis.call('EXISTS', sourceKey)
            if exists == 0 then
                return {}
            end

            -- RENAME (원자적 이동)
            redis.call('RENAME', sourceKey, tempKey)

            -- 임시 키에 TTL 설정
            redis.call('EXPIRE', tempKey, ttl)

            -- 임시 키에서 모든 필드 조회
            local result = redis.call('HGETALL', tempKey)
            return result
        """.trimIndent()

        private val LUA_RESTORE = """
            local tempKey = KEYS[1]
            local sourceKey = KEYS[2]

            -- 임시 키 존재 확인
            local exists = redis.call('EXISTS', tempKey)
            if exists == 0 then
                return 0
            end

            -- 임시 키 데이터를 원본 키로 이동
            redis.call('RENAME', tempKey, sourceKey)
            return 1
        """.trimIndent()
    }

    override fun fetchAndMove(sourceKey: String, tempKey: String): FetchResult = executor.executeOrDefault(
        {
            val result: Any? = redissonClient.getScript().eval(
                RScript.Mode.READ_WRITE,
                LUA_FETCH_AND_MOVE,
                RScript.ReturnType.MULTI,
                listOf(sourceKey, tempKey),
                tempKeyTtlSeconds.toString(),
            )

            val list = result as? List<*> ?: return@executeOrDefault FetchResult.empty()

            val data = mutableMapOf<String, Long>()
            var i = 0
            while (i + 1 < list.size) {
                val k = list[i] as? String
                val v = list[i + 1] as? String
                if (k != null && v != null) {
                    data[k] = v.toLongOrNull() ?: 0L
                }
                i += 2
            }

            meterRegistry.counter("like.sync.fetch", "strategy", "lua").increment()
            FetchResult(tempKey, data)
        },
        FetchResult.empty(),
        TaskContext.of("LuaScriptLikeAtomicFetchStrategy", "FetchAndMove", sourceKey),
    ) ?: FetchResult.empty()

    override fun restore(tempKey: String?, sourceKey: String) {
        if (tempKey == null) return

        executor.executeVoid(
            {
                val scriptObject: Any? = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    LUA_RESTORE,
                    RScript.ReturnType.INTEGER,
                    listOf(tempKey, sourceKey),
                )
                meterRegistry.counter("like.sync.restore", "strategy", "lua").increment()
                log.debug("Lua restore completed: tempKey={} -> sourceKey={}", tempKey, sourceKey)
            },
            TaskContext.of("LuaScriptLikeAtomicFetchStrategy", "Restore", tempKey),
        )
    }

    override fun deleteTempKey(tempKey: String?) {
        if (tempKey == null) return

        executor.executeVoid(
            {
                redissonClient.getKeys().delete(tempKey)
                meterRegistry.counter("like.sync.delete", "strategy", "lua").increment()
            },
            TaskContext.of("LuaScriptLikeAtomicFetchStrategy", "DeleteTempKey", tempKey),
        )
    }

    override fun strategyName(): String = "lua"
}
