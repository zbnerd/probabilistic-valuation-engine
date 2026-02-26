package maple.expectation.infrastructure.queue.like

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.core.port.out.LikeBufferStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.queue.RedisKey
import org.slf4j.LoggerFactory
import org.redisson.api.RMap
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.LongCodec
import org.redisson.client.codec.StringCodec
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

/**
 * Redis 기반 좋아요 카운터 버퍼 (#271 V5 Stateless Architecture)
 */
class RedisLikeBufferStorage(
    private val redissonClient: RedissonClient,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry
) : LikeBufferStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(RedisLikeBufferStorage::class.java)

        private const val FIELD_INDEX = 0
        private const val VALUE_INDEX = 1
        private const val MIN_ENTRY_SIZE = 2

        private val LUA_FETCH_AND_CLEAR = """
            -- Fetch all entries and delete them atomically
            -- Returns: [[field1, value1], [field2, value2], ...]
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])

            local cursor = '0'
            local results = {}
            local fields_to_delete = {}
            local count = 0

            -- HSCAN to get entries (limit으로 제한)
            repeat
                local scan_result = redis.call('HSCAN', key, cursor, 'COUNT', 100)
                cursor = scan_result[1]
                local entries = scan_result[2]

                for i = 1, #entries, 2 do
                    if count >= limit then
                        break
                    end
                    local field = entries[i]
                    local value = entries[i + 1]
                    table.insert(results, {field, value})
                    table.insert(fields_to_delete, field)
                    count = count + 1
                end
            until cursor == '0' or count >= limit

            -- Delete fetched fields
            if #fields_to_delete > 0 then
                redis.call('HDEL', key, unpack(fields_to_delete))
            end

            return results
            """
    }

    private val bufferKey: String = RedisKey.LIKE_BUFFER.key

    /** Lua Script SHA 캐싱 */
    private val fetchAndClearSha = AtomicReference<String>()

    init {
        registerMetrics()
        log.info("[RedisLikeBufferStorage] Initialized with key: $bufferKey")
    }

    private fun registerMetrics() {
        // 버퍼 내 대기 중인 카운터 수
        Gauge.builder("like.buffer.redis.entries", this) { storage -> storage.getBufferSize().toDouble() }
            .description("Redis 버퍼의 미반영 좋아요 엔트리 수")
            .register(meterRegistry)

        // 버퍼 내 총 delta 합계
        Gauge.builder("like.buffer.redis.total_delta", this) { storage -> storage.getTotalDelta().toDouble() }
            .description("Redis 버퍼의 미반영 좋아요 총합")
            .register(meterRegistry)
    }

    override fun increment(userIgn: String, delta: Long): Long? {
        return executor.executeOrDefault(
            {
                val buffer = getBuffer()
                val newValue = buffer.addAndGet(userIgn, delta)

                meterRegistry.counter("like.buffer.increment", "ign", userIgn).increment()
                log.debug("[LikeBuffer] Increment: $userIgn += $delta -> $newValue")

                newValue
            },
            null,
            TaskContext.of("LikeBuffer", "Increment", userIgn)
        )
    }

    override fun get(userIgn: String): Long? {
        return executor.executeOrDefault(
            {
                val value = getBuffer()[userIgn]
                value ?: 0L
            },
            null,
            TaskContext.of("LikeBuffer", "Get", userIgn)
        )
    }

    override fun getAllCounters(): Map<String, Long> {
        return executor.executeOrDefault(
            {
                val rawMap: Map<*, *> = getBuffer().readAllMap()
                rawMap.entries.associate { it.key.toString() to it.value.toString().toLong() }
            },
            emptyMap(),
            TaskContext.of("LikeBuffer", "GetAll")
        )
    }

    override fun fetchAndClear(limit: Int): Map<String, Long> {
        return executor.executeOrDefault(
            { doFetchAndClear(limit) },
            emptyMap(),
            TaskContext.of("LikeBuffer", "FetchAndClear")
        )
    }

    /** Lua Script 실행 */
    @Suppress("UNCHECKED_CAST")
    private fun doFetchAndClear(limit: Int): Map<String, Long> {
        val script = redissonClient.getScript(StringCodec.INSTANCE)
        val sha = fetchAndClearSha.get()

        val rawResult: List<List<String>> = executor.executeOrCatch(
            { evalWithCachedSha(script, sha, limit) },
            { e -> evalWithReloadedSha(script, limit) },
            TaskContext.of("LikeBuffer", "EvalScript")
        )

        return parseRawResult(rawResult)
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalWithCachedSha(script: RScript, sha: String?, limit: Int): List<List<String>> {
        if (sha == null) {
            throw IllegalStateException("SHA not cached")
        }
        return script.evalSha(
            RScript.Mode.READ_WRITE,
            sha,
            RScript.ReturnType.MULTI,
            listOf(bufferKey),
            limit.toString()
        ) as List<List<String>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun evalWithReloadedSha(script: RScript, limit: Int): List<List<String>> {
        val sha = script.scriptLoad(LUA_FETCH_AND_CLEAR)
        fetchAndClearSha.set(sha)
        return script.evalSha(
            RScript.Mode.READ_WRITE,
            sha,
            RScript.ReturnType.MULTI,
            listOf(bufferKey),
            limit.toString()
        ) as List<List<String>>
    }

    private fun parseRawResult(rawResult: List<List<String>>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (entry in rawResult) {
            if (entry.size >= MIN_ENTRY_SIZE) {
                val field = entry[FIELD_INDEX]
                val value = entry[VALUE_INDEX].toLong()
                result[field] = value
            }
        }

        if (result.isNotEmpty()) {
            meterRegistry.counter("like.buffer.flush.entries").increment(result.size.toDouble())
            log.info("[LikeBuffer] FetchAndClear: {} entries", result.size)
        }

        return result.toMap()
    }

    override fun getBufferSize(): Int {
        return executor.executeOrDefault(
            { getBuffer().size },
            0,
            TaskContext.of("LikeBuffer", "Size")
        )
    }

    /** 총 delta 합계 조회 (메트릭용) */
    private fun getTotalDelta(): Long {
        return executor.executeOrDefault(
            { getBuffer().readAllValues().stream().mapToLong { it }.sum() },
            0L,
            TaskContext.of("LikeBuffer", "TotalDelta")
        )
    }

    /** Redis HASH 버퍼 접근 (LongCodec 사용) */
    private fun getBuffer(): RMap<String, Long> {
        return redissonClient.getMap(bufferKey, LongCodec.INSTANCE)
    }

    /** 버퍼 키 조회 (테스트용) */
    fun getBufferKey(): String = bufferKey

    override fun getType(): LikeBufferStrategy.StrategyType = LikeBufferStrategy.StrategyType.REDIS
}
