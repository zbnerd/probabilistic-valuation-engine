package maple.expectation.infrastructure.resilience

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import maple.expectation.infrastructure.event.MySQLDownEvent
import maple.expectation.infrastructure.event.MySQLUpEvent
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lock.LockStrategy
import org.redisson.api.BatchOptions
import org.redisson.api.RBatch
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.ArrayList

@Service
class DynamicTTLManager(
    private val redissonClient: RedissonClient,
    private val redisTemplate: StringRedisTemplate,
    private val properties: MySQLFallbackProperties,
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
    private val lockStrategy: LockStrategy
) {
    private val logger = LoggerFactory.getLogger(DynamicTTLManager::class.java)

    companion object {
        private val CACHE_TTL_CONFIG: Map<String, Duration> = mapOf(
            "equipment" to Duration.ofMinutes(10),
            "ocidCache" to Duration.ofMinutes(60)
        )
    }

    init {
        logger.info("[DynamicTTL] 초기화 완료. 대상 캐시 패턴: {}", properties.targetCachePatterns)
    }

    @Async
    @EventListener
    fun onMySQLDown(event: MySQLDownEvent) {
        val context = TaskContext.of("Resilience", "OnMySQLDown", event.circuitBreakerName)
        executor.executeOrDefault(
            {
                logger.warn("[DynamicTTL] MySQL DOWN 이벤트 수신: $event")

                lockStrategy.executeWithLock(
                    properties.ttlLockKey,
                    0,
                    properties.lockLeaseSeconds.toLong(),
                    {
                        extendAllCacheTTL()
                        null
                    }
                )
            },
            null,
            context
        )
    }

    @Async
    @EventListener
    fun onMySQLUp(event: MySQLUpEvent) {
        val context = TaskContext.of("Resilience", "OnMySQLUp", event.circuitBreakerName)
        executor.executeOrDefault(
            {
                logger.info("[DynamicTTL] MySQL UP 이벤트 수신: $event")

                lockStrategy.executeWithLock(
                    properties.ttlLockKey,
                    0,
                    properties.lockLeaseSeconds.toLong(),
                    {
                        restoreAllCacheTTL()
                        null
                    }
                )
            },
            null,
            context
        )
    }

    private fun extendAllCacheTTL() {
        var totalKeys = 0
        for (pattern in properties.targetCachePatterns) {
            val keys = scanKeys(pattern)
            logger.info("[DynamicTTL] TTL 제거 대상 키 수: {} (패턴: {})", keys.size, pattern)

            meterRegistry.gauge(
                "mysql.ttl.scan.keys",
                Tags.of("action", "persist", "pattern", extractCacheName(pattern)),
                keys.size.toDouble()
            )

            if (keys.isEmpty()) {
                continue
            }

            totalKeys += keys.size
            executePersistBatch(keys)
        }

        meterRegistry.counter("mysql.ttl.extended").increment(totalKeys.toDouble())
        logger.info("[DynamicTTL] 모든 대상 캐시 TTL 제거 완료: {} 키", totalKeys)
    }

    private fun restoreAllCacheTTL() {
        var totalKeys = 0
        for (pattern in properties.targetCachePatterns) {
            val keys = scanKeys(pattern)
            logger.info("[DynamicTTL] TTL 복원 대상 키 수: {} (패턴: {})", keys.size, pattern)

            val cacheName = extractCacheName(pattern)
            meterRegistry.gauge(
                "mysql.ttl.scan.keys",
                Tags.of("action", "restore", "pattern", cacheName),
                keys.size.toDouble()
            )

            if (keys.isEmpty()) {
                continue
            }

            totalKeys += keys.size
            val ttl = CACHE_TTL_CONFIG[cacheName] ?: Duration.ofMinutes(15)
            executeExpireBatch(keys, ttl)
        }

        meterRegistry.counter("mysql.ttl.restored").increment(totalKeys.toDouble())
        logger.info("[DynamicTTL] 모든 대상 캐시 TTL 복원 완료: {} 키", totalKeys)
    }

    private fun scanKeys(pattern: String): List<String> {
        val keys = ArrayList<String>()
        val scanCount = properties.scanCount.toLong()

        redisTemplate.execute(
            RedisCallback<Void> { connection ->
                val options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(scanCount)
                    .build()

                connection.keyCommands().scan(options).use { cursor ->
                    while (cursor.hasNext()) {
                        keys.add(String(cursor.next()))
                    }
                }
                null
            }
        )

        return keys
    }

    private fun executePersistBatch(keys: List<String>) {
        val batch: RBatch = redissonClient.createBatch(BatchOptions.defaults())

        for (key in keys) {
            batch.getBucket<Any>(key, StringCodec.INSTANCE).remainTimeToLiveAsync()
            batch.getKeys().clearExpireAsync(key)
        }

        batch.executeAsync()
            .thenAccept {
                logger.debug("[DynamicTTL] PERSIST 배치 완료: {} 키", keys.size)
                meterRegistry.counter("mysql.ttl.batch.success", Tags.of("action", "persist")).increment()
            }
            .exceptionally { ex ->
                logger.error("[DynamicTTL] PERSIST 배치 실패 (P0-N3: Double Failure 가능성)", ex)
                meterRegistry.counter("mysql.ttl.batch.failures", Tags.of("action", "persist")).increment(keys.size.toDouble())
                meterRegistry.counter("mysql.double_failure.count").increment()
                null
            }
    }

    private fun executeExpireBatch(keys: List<String>, ttl: Duration) {
        val batch: RBatch = redissonClient.createBatch(BatchOptions.defaults())

        for (key in keys) {
            batch.getBucket<Any>(key, StringCodec.INSTANCE).expireAsync(ttl)
        }

        batch.executeAsync()
            .thenAccept {
                logger.debug("[DynamicTTL] EXPIRE 배치 완료: {} 키, TTL: {}", keys.size, ttl)
                meterRegistry.counter("mysql.ttl.batch.success", Tags.of("action", "restore")).increment()
            }
            .exceptionally { ex ->
                logger.error("[DynamicTTL] EXPIRE 배치 실패 (P0-N3: Double Failure 가능성)", ex)
                meterRegistry.counter("mysql.ttl.batch.failures", Tags.of("action", "restore")).increment(keys.size.toDouble())
                null
            }
    }

    private fun extractCacheName(pattern: String): String {
        val colonIndex = pattern.indexOf(':')
        return if (colonIndex > 0) pattern.substring(0, colonIndex) else pattern.replace("*", "")
    }
}
