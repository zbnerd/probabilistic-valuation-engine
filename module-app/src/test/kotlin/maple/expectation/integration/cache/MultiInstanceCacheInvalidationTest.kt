package maple.expectation.integration.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.config.TestcontainersConfiguration
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.config.CacheProperties
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationPublisher
import maple.expectation.infrastructure.cache.invalidation.impl.PostgresNotifyPublisher
import maple.expectation.infrastructure.cache.invalidation.impl.PostgresNotifySubscriber
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheFactory
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheStrategy
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.function.ThrowingRunnable
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.common.function.ThrowingSupplier
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.cache.Cache
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Multi-Instance Cache Invalidation Consistency Test (Issue #704)
 *
 * Verifies PostgreSQL LISTEN/NOTIFY based cache invalidation works correctly
 * across multiple "instances" sharing the same PostgreSQL database.
 *
 * Uses direct component construction (not Spring multi-context) to test
 * the actual LISTEN/NOTIFY mechanism in isolation.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Multi-Instance Cache Invalidation Consistency")
class MultiInstanceCacheInvalidationTest {

    // Shared components
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val meterRegistry = SimpleMeterRegistry()
    private val executor = ImmediateLogicExecutor()
    private lateinit var l2CacheFactory: PostgresL2CacheFactory
    private lateinit var publisher: CacheInvalidationPublisher

    // Instances
    private lateinit var instanceA: CacheInstance
    private lateinit var instanceB: CacheInstance
    private lateinit var instanceC: CacheInstance

    companion object {
        private const val CACHE_NAME = "testCache"
    }

    @BeforeAll
    fun setUp() {
        // 1. Shared DataSource from Testcontainers
        val container = TestcontainersConfiguration.postgresContainer
        dataSource = HikariDataSource().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 10
        }
        jdbcTemplate = JdbcTemplate(dataSource)

        // 2. Create cache_storage table (missing from migrations)
        jdbcTemplate.execute(
            """
            CREATE UNLOGGED TABLE IF NOT EXISTS cache_storage (
                cache_key VARCHAR(500) PRIMARY KEY,
                cache_value BYTEA NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            )
            """.trimIndent(),
        )

        // 3. Shared L2
        val l2Strategy = PostgresL2CacheStrategy(jdbcTemplate, executor, objectMapper, meterRegistry, CacheProperties())
        l2CacheFactory = PostgresL2CacheFactory(l2Strategy, executor, meterRegistry, CacheProperties())

        // 4. Shared publisher
        publisher = PostgresNotifyPublisher(jdbcTemplate, objectMapper, executor, meterRegistry)

        // 5. Create 3 instances with unique IDs
        instanceA = createInstance("test-A")
        instanceB = createInstance("test-B")
        instanceC = createInstance("test-C")

        // 6. Start all subscribers (triggers LISTEN)
        instanceA.subscriber.subscribe()
        instanceB.subscriber.subscribe()
        instanceC.subscriber.subscribe()

        // 7. Wait for subscribers to be ready
        await().atMost(2, TimeUnit.SECONDS).pollDelay(100, TimeUnit.MILLISECONDS)
            .until { true }
    }

    @AfterAll
    fun tearDown() {
        instanceA.subscriber.unsubscribe()
        instanceB.subscriber.unsubscribe()
        instanceC.subscriber.unsubscribe()
        dataSource.close()
    }

    @BeforeEach
    fun clearState() {
        // Clear L1 caches
        instanceA.tieredCacheManager.getL1CacheDirect(CACHE_NAME)?.clear()
        instanceB.tieredCacheManager.getL1CacheDirect(CACHE_NAME)?.clear()
        instanceC.tieredCacheManager.getL1CacheDirect(CACHE_NAME)?.clear()

        // Clear version tracking
        instanceA.tieredCacheManager.clearKeyVersions(CACHE_NAME)
        instanceB.tieredCacheManager.clearKeyVersions(CACHE_NAME)
        instanceC.tieredCacheManager.clearKeyVersions(CACHE_NAME)

        // Clear L2
        jdbcTemplate.execute("TRUNCATE TABLE cache_storage")

        // Re-populate caches so TieredCache instances have clean state
        instanceA.tieredCacheManager.getCache(CACHE_NAME)
        instanceB.tieredCacheManager.getCache(CACHE_NAME)
        instanceC.tieredCacheManager.getCache(CACHE_NAME)
    }

    @Test
    @DisplayName("evict on A should invalidate L1 on B and C")
    fun evictPropagatesToOtherInstances() {
        // Directly populate B and C L1 (bypasses TieredCache version tracking)
        instanceB.l1Cache()?.put("key1", "value1")
        instanceC.l1Cache()?.put("key1", "value1")

        // Verify B and C have the value in L1
        assertThat(instanceB.l1Cache()?.get("key1")?.get()).isEqualTo("value1")
        assertThat(instanceC.l1Cache()?.get("key1")?.get()).isEqualTo("value1")

        // A evicts via TieredCache (triggers NOTIFY)
        instanceA.tieredCacheManager.getCache(CACHE_NAME)?.evict("key1")

        // B and C should have L1 invalidated via NOTIFY
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(instanceB.l1Cache()?.get("key1")).isNull()
            assertThat(instanceC.l1Cache()?.get("key1")).isNull()
        }
    }

    @Test
    @DisplayName("burst evict 50 keys should propagate to all instances")
    fun burstEvictPropagates() {
        val keys = (1..50).map { "burst-key-$it" }

        // Directly populate B and C L1 (bypasses version tracking)
        keys.forEach { key ->
            instanceB.l1Cache()?.put(key, "value-$key")
            instanceC.l1Cache()?.put(key, "value-$key")
        }

        // Verify B has values
        assertThat(instanceB.l1Cache()?.get("burst-key-1")?.get()).isNotNull

        // Evict all 50 keys from A via TieredCache (triggers NOTIFY)
        keys.forEach { instanceA.tieredCacheManager.getCache(CACHE_NAME)?.evict(it) }

        // B and C should have all L1 entries invalidated
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            keys.forEach { key ->
                assertThat(instanceB.l1Cache()?.get(key)).isNull()
                assertThat(instanceC.l1Cache()?.get(key)).isNull()
            }
        }
    }

    @Test
    @DisplayName("stale version event should be ignored")
    fun staleVersionEventIgnored() {
        // B puts value (version becomes >= 1)
        instanceB.put("stale-key", "fresh-value")

        // B reads from L2 to populate L1
        instanceB.tieredCacheManager.getCache(CACHE_NAME)?.get("stale-key")
        assertThat(instanceB.l1Cache()?.get("stale-key")?.get()).isEqualTo("fresh-value")

        // Record current version
        val currentVersion = instanceB.tieredCacheManager.getKeyVersion(CACHE_NAME, "stale-key")
        assertThat(currentVersion).isNotNull

        // A publishes a stale event (version 0, which is <= current version)
        val staleEvent = CacheInvalidationEvent.evict(
            CACHE_NAME, "stale-key", "test-A", version = 0L,
        )
        (publisher as PostgresNotifyPublisher).publish(staleEvent)

        // Wait for notification to be processed
        await().atMost(3, TimeUnit.SECONDS).pollDelay(200, TimeUnit.MILLISECONDS).untilAsserted {
            // B's L1 should still have the value (stale event was ignored)
            assertThat(instanceB.l1Cache()?.get("stale-key")?.get()).isEqualTo("fresh-value")
        }
    }

    @Test
    @DisplayName("self-evict: A subscriber skips own event")
    fun selfEvictSkipped() {
        // Directly populate all L1s (bypasses version tracking)
        instanceA.l1Cache()?.put("self-key", "value")
        instanceB.l1Cache()?.put("self-key", "value")
        instanceC.l1Cache()?.put("self-key", "value")

        // All should have value
        assertThat(instanceA.l1Cache()?.get("self-key")?.get()).isEqualTo("value")
        assertThat(instanceB.l1Cache()?.get("self-key")?.get()).isEqualTo("value")

        // A evicts — A's local L1 is evicted by TieredCache.evict() directly (by design)
        instanceA.tieredCacheManager.getCache(CACHE_NAME)?.evict("self-key")
        assertThat(instanceA.l1Cache()?.get("self-key")).isNull()

        // B and C should have L1 invalidated via NOTIFY
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(instanceB.l1Cache()?.get("self-key")).isNull()
            assertThat(instanceC.l1Cache()?.get("self-key")).isNull()
        }
    }

    @Test
    @DisplayName("CLEAR_ALL propagates to other instances")
    fun clearAllPropagates() {
        // Directly populate B and C L1
        listOf("clear-1", "clear-2", "clear-3").forEach { key ->
            instanceB.l1Cache()?.put(key, "v-$key")
            instanceC.l1Cache()?.put(key, "v-$key")
        }

        // Verify B has values
        assertThat(instanceB.l1Cache()?.get("clear-1")?.get()).isNotNull

        // A clears all via TieredCache (triggers NOTIFY with CLEAR_ALL)
        instanceA.tieredCacheManager.getCache(CACHE_NAME)?.clear()

        // B and C should have entire L1 cleared
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(instanceB.l1Cache()?.get("clear-1")).isNull()
            assertThat(instanceB.l1Cache()?.get("clear-2")).isNull()
            assertThat(instanceB.l1Cache()?.get("clear-3")).isNull()
            assertThat(instanceC.l1Cache()?.get("clear-1")).isNull()
            assertThat(instanceC.l1Cache()?.get("clear-2")).isNull()
            assertThat(instanceC.l1Cache()?.get("clear-3")).isNull()
        }
    }

    @Test
    @DisplayName("concurrent evicts from A and B reach C")
    fun concurrentEvictsReachThirdInstance() {
        // Directly populate C L1
        instanceC.l1Cache()?.put("concurrent-1", "from-A")
        instanceC.l1Cache()?.put("concurrent-2", "from-B")
        assertThat(instanceC.l1Cache()?.get("concurrent-1")?.get()).isNotNull

        // A and B evict concurrently
        val threadA = Thread { instanceA.tieredCacheManager.getCache(CACHE_NAME)?.evict("concurrent-1") }
        val threadB = Thread { instanceB.tieredCacheManager.getCache(CACHE_NAME)?.evict("concurrent-2") }
        threadA.start()
        threadB.start()
        threadA.join(5000)
        threadB.join(5000)

        // C should have both keys invalidated
        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(instanceC.l1Cache()?.get("concurrent-1")).isNull()
            assertThat(instanceC.l1Cache()?.get("concurrent-2")).isNull()
        }
    }

    // === Helper Methods ===

    private fun createInstance(instanceId: String): CacheInstance {
        // L1 (Caffeine) — unique per instance
        val l1Manager = CaffeineCacheManager()
        l1Manager.registerCustomCache(
            CACHE_NAME,
            Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1000L)
                .recordStats()
                .build(),
        )

        // TieredCacheManager
        val tcm = TieredCacheManager(l1Manager, l2CacheFactory, executor, null, meterRegistry, 5)

        // Initialize BEFORE creating TieredCache instances
        tcm.initializeInstanceId(instanceId)
        tcm.initializeInvalidationCallback(Consumer { event -> publisher.publish(event) })

        // Trigger cache creation (TieredCache captures Suppliers with correct values)
        tcm.getCache(CACHE_NAME)

        // Subscriber with unique instanceId
        val subscriber = PostgresNotifySubscriber(
            dataSource, tcm, objectMapper, executor, meterRegistry,
            instanceId, 50L, 1000L,
        )

        return CacheInstance(instanceId, tcm, publisher, subscriber)
    }

    private fun CacheInstance.put(key: String, value: String) {
        tieredCacheManager.getCache(CACHE_NAME)?.put(key, value)
    }

    private fun CacheInstance.l1Cache(): Cache? = tieredCacheManager.getL1CacheDirect(CACHE_NAME)

    /** Immediate LogicExecutor for testing (no Spring context needed) */
    private class ImmediateLogicExecutor : LogicExecutor {
        override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T = task.get()

        override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T =
            runCatching { task.get() ?: defaultValue }.getOrElse { defaultValue }

        override fun executeVoid(task: ThrowingRunnable, context: TaskContext) {
            task.run()
        }

        override fun executeVoidJava(task: Runnable, context: TaskContext) {
            task.run()
        }

        override fun <T> executeWithFinally(task: ThrowingSupplier<T>, finallyBlock: Runnable, context: TaskContext): T =
            try {
                task.get()
            } finally {
                finallyBlock.run()
            }

        override fun <T> executeWithTranslation(
            task: ThrowingSupplier<T>,
            customTranslator: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: (Throwable) -> T,
            context: TaskContext,
        ): T = runCatching { task.get() }.getOrElse { fallback(it) }

        override fun <T> executeWithFallback(
            task: ThrowingSupplier<T>,
            fallback: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: (Throwable) -> T,
            context: TaskContext,
        ): T = runCatching { task.get() }.getOrElse { recovery(it) }

        override fun <T> executeOrCatch(
            task: ThrowingSupplier<T>,
            recovery: ExceptionTranslator,
            context: TaskContext,
        ): T = task.get()
    }
}

data class CacheInstance(
    val instanceId: String,
    val tieredCacheManager: TieredCacheManager,
    val publisher: CacheInvalidationPublisher,
    val subscriber: PostgresNotifySubscriber,
)
