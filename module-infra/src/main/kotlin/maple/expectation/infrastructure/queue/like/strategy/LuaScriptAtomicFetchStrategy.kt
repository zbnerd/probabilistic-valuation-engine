package maple.expectation.infrastructure.queue.like.strategy

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.AtomicFetchStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.redisson.api.RScript
import org.redisson.api.RedissonClient

/**
 * Lua Script 기반 원자적 Fetch 전략
 */
class LuaScriptAtomicFetchStrategy(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val tempKeyTtlSeconds: Int,
) : AtomicFetchStrategy {

    companion object {
        private val LUA_SCRIPT = """
            local result = redis.call('HGETALL', KEYS[1])
            if #result > 0 then
                redis.call('DEL', KEYS[1])
            end
            return result
        """.trimIndent()
    }

    override fun fetchAndDelete(key: String): MutableMap<String, String> {
        return executor.executeOrDefault(
            {
                val result: Any? = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE,
                    LUA_SCRIPT,
                    RScript.ReturnType.MULTI,
                    listOf(key),
                )

                val list = result as? List<*> ?: return@executeOrDefault mutableMapOf<String, String>()

                val map = mutableMapOf<String, String>()
                var i = 0
                while (i + 1 < list.size) {
                    val k = list[i] as? String
                    val v = list[i + 1] as? String
                    if (k != null && v != null) {
                        map[k] = v
                    }
                    i += 2
                }
                map
            },
            mutableMapOf(),
            TaskContext.of("LuaScriptAtomicFetchStrategy", "FetchAndDelete", key),
        ) ?: mutableMapOf()
    }

    override fun getStrategyType(): AtomicFetchStrategy.StrategyType = AtomicFetchStrategy.StrategyType.LUA_SCRIPT
}
