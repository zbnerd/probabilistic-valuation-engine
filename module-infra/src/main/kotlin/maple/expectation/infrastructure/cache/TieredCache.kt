package maple.expectation.infrastructure.cache

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.Optional
import java.util.concurrent.Callable
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.function.Consumer
import java.util.function.Supplier
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.cache.invalidation.CacheInvalidationEvent
import maple.expectation.infrastructure.cache.tiered.BatchL2LookupBuffer
import maple.expectation.infrastructure.cache.tiered.BatchL2WriteBuffer
import maple.expectation.infrastructure.cache.tiered.CacheStampedeTimeoutException
import maple.expectation.infrastructure.cache.tiered.L2CacheStrategy
import maple.expectation.infrastructure.cache.tiered.PostgresL2CacheAdapter
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.executor.strategy.ExceptionTranslator
import maple.expectation.infrastructure.lock.LeaderElectionStrategy
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.Cache.ValueWrapper
import org.springframework.cache.support.SimpleValueWrapper

/**
 * Issue #555: Check if L2 cache is in Caffeine-only (no-op) mode
 */
private fun isL2Disabled(cache: Cache): Boolean = cache is CaffeineOnlyCacheManager.NoOpCacheImplementation

/**
 * Issue #555: Check if cache is NoOpCache implementation
 */
private fun Cache?.isNoOp(): Boolean = this != null && this is CaffeineOnlyCacheManager.NoOpCacheImplementation

class TieredCache(
    private val l1: Cache,
    private val l2: Cache,
    private val executor: LogicExecutor,
    private val leaderElectionStrategy: LeaderElectionStrategy?,
    private val meterRegistry: MeterRegistry,
    private val lockWaitSeconds: Int,
    private val instanceIdSupplier: Supplier<String>,
    private val callbackSupplier: Supplier<Consumer<CacheInvalidationEvent>>,
) : Cache {
    companion object {
        private val log = LoggerFactory.getLogger(TieredCache::class.java)
    }

    private val versionCounter = AtomicLong(0)
    private val keyVersions = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<Any, Long>()

    private val l1HitCounter: Counter
    private val l2HitCounter: Counter
    private val missCounter: Counter
    private val lockFailureCounter: Counter
    private val l2FailureCounter: Counter
    private val stampedeTimeoutCounter: Counter

    /** Issue #555: L2 disabled flag (Caffeine-only mode) */
    private val l2Enabled: Boolean

    /** Access underlying L2CacheStrategy for batch operations */
    private val l2Strategy: L2CacheStrategy? = (l2 as? PostgresL2CacheAdapter)?.nativeCache as? L2CacheStrategy

    /** Time-window batching buffer for L2 lookups (null if L2 disabled) */
    private val batchBuffer: BatchL2LookupBuffer?

    /** Time-window batching buffer for L2 writes (null if L2 disabled) */
    private val writeBuffer: BatchL2WriteBuffer?

    init {
        val cacheName = l2.name
        l2Enabled = !isL2Disabled(l2)
        batchBuffer = if (l2Enabled && l2Strategy != null) {
            log.info("[TieredCache] Batch L2 enabled: cache={}", cacheName)
            BatchL2LookupBuffer(l2Strategy, l1, meterRegistry, executor)
        } else {
            null
        }
        writeBuffer = if (l2Enabled && l2Strategy != null) {
            val ttl = (l2 as? PostgresL2CacheAdapter)?.ttlMinutes ?: 15L
            BatchL2WriteBuffer(l2Strategy, l1, ttl, meterRegistry, executor)
        } else {
            null
        }
        l1HitCounter = Counter.builder("cache.hit").tag("layer", "L1").tag("cache", cacheName).register(meterRegistry)
        l2HitCounter = Counter.builder("cache.hit").tag("layer", "L2").tag("cache", cacheName).register(meterRegistry)
        missCounter = Counter.builder("cache.miss").tag("cache", cacheName).register(meterRegistry)
        lockFailureCounter = Counter.builder("cache.lock.failure").tag("cache", cacheName).register(meterRegistry)
        l2FailureCounter = Counter.builder("cache.l2.failure").tag("cache", cacheName).register(meterRegistry)
        stampedeTimeoutCounter = Counter.builder("cache.stampede.timeout").tag("cache", cacheName).register(meterRegistry)

        if (!l2Enabled) {
            log.info("[TieredCache] Caffeine-only mode (L2 disabled): cache={}", cacheName)
        }
    }

    override fun getName(): String = l2.name
    override fun getNativeCache(): Any = l1.nativeCache ?: l2.nativeCache ?: emptyMap<Any, Any>()

    override fun get(key: Any): ValueWrapper? {
        val context = TaskContext.of("Cache", "Get", key.toString())
        return executor.execute({ getFromCacheLayers(key) }, context)
    }

    private fun getFromCacheLayers(key: Any): ValueWrapper? = Optional.ofNullable(l1.get(key))
        .map { w -> tapCacheHit(w, "L1") }
        .orElseGet {
            // Issue #555: Skip L2 when disabled
            if (l2Enabled) getFromL2WithBackfill(key) else null
        }

    private fun getFromL2WithBackfill(key: Any): ValueWrapper? {
        val buffer = batchBuffer
        if (buffer != null) {
            val value = try {
                buffer.submit(key)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .join()
            } catch (ex: java.util.concurrent.CompletionException) {
                if (ex.cause is java.util.concurrent.TimeoutException) {
                    log.warn("[TieredCache] L2 buffer timeout, falling back to direct lookup: key={}", key)
                    return Optional.ofNullable(l2.get(key))
                        .map { w ->
                            l1.put(key, w.get())
                            keyVersions.put(key, versionCounter.incrementAndGet())
                            tapCacheHit(w, "L2")
                        }
                        .orElseGet { null }
                }
                throw ex
            }
            return if (value != null) {
                keyVersions.put(key, versionCounter.incrementAndGet())
                l2HitCounter.increment()
                SimpleValueWrapper(value)
            } else {
                null
            }
        }
        return Optional.ofNullable(l2.get(key))
            .map { w ->
                l1.put(key, w.get())
                keyVersions.put(key, versionCounter.incrementAndGet())
                tapCacheHit(w, "L2")
            }
            .orElseGet { null }
    }

    override fun put(key: Any, value: Any?) {
        val context = TaskContext.of("Cache", "Put", key.toString())
        // Issue #555: In L1-only mode, skip L2 and put directly to L1
        if (!l2Enabled) {
            executor.executeVoid({ l1.put(key, value) }, context)
            return
        }
        val buffer = writeBuffer
        if (buffer != null) {
            buffer.submit(key, value)
            val ver = versionCounter.incrementAndGet()
            keyVersions.put(key, ver)
            publishEvictEvent(key, ver)
            return
        }
        val l2Success = executor.executeOrDefault({
            l2.put(key, value)
            true
        }, false, context)
        if (l2Success) {
            executor.executeVoid({ l1.put(key, value) }, context)
            val ver = versionCounter.incrementAndGet()
            keyVersions.put(key, ver)
            publishEvictEvent(key, ver)
        } else {
            log.warn("[TieredCache] L2 put failed, skipping L1 for consistency: key={}", key)
            l2FailureCounter.increment()
        }
    }

    override fun evict(key: Any) {
        val context = TaskContext.of("Cache", "Evict", key.toString())
        // Issue #555: In L1-only mode, skip L2 and evict directly from L1
        if (!l2Enabled) {
            executor.executeVoid({ l1.evict(key) }, context)
            return
        }
        val l2Success = executor.executeOrDefault({
            l2.evict(key)
            true
        }, false, context)
        if (!l2Success) {
            log.warn("[TieredCache] L2 evict failed, proceeding with L1: key={}", key)
            l2FailureCounter.increment()
        }
        executor.executeVoid({ l1.evict(key) }, context)
        keyVersions.invalidate(key)
        publishEvictEvent(key, versionCounter.incrementAndGet())
    }

    override fun clear() {
        val context = TaskContext.of("Cache", "Clear")
        // Issue #555: In L1-only mode, skip L2 and clear L1 directly
        if (!l2Enabled) {
            executor.executeVoid({ l1.clear() }, context)
            return
        }
        val l2Success = executor.executeOrDefault({
            l2.clear()
            true
        }, false, context)
        if (!l2Success) {
            log.warn("[TieredCache] L2 clear failed, proceeding with L1")
            l2FailureCounter.increment()
        }
        executor.executeVoid({ l1.clear() }, context)
        keyVersions.invalidateAll()
        publishClearAllEvent()
    }

    /**
     * Batch retrieval: L1 bulk check → L2 WHERE IN batch fetch → L1 backfill
     *
     * Replaces N individual L2 SELECT queries with chunked WHERE IN queries.
     * Used by BatchL2LookupBuffer and explicit batch pre-fetch callers.
     */
    fun getAll(keys: Collection<Any>): Map<Any, Any> {
        if (keys.isEmpty()) return emptyMap()

        val result = mutableMapOf<Any, Any>()
        val missKeys = mutableListOf<Any>()

        // 1. L1 bulk check
        for (key in keys) {
            val l1Value = l1.get(key)
            if (l1Value != null) {
                val value = l1Value.get()
                if (value != null) result[key] = value
                l1HitCounter.increment()
            } else {
                missKeys.add(key)
            }
        }

        if (missKeys.isEmpty() || !l2Enabled) return result

        // 2. L2 batch fetch via L2CacheStrategy.getAll()
        val strategy = l2Strategy
        if (strategy == null) {
            // Fallback: individual L2 lookups
            for (key in missKeys) {
                val l2Value = getFromL2WithBackfill(key)
                if (l2Value != null) {
                    val value = l2Value.get()
                    if (value != null) result[key] = value
                }
            }
            return result
        }

        val context = TaskContext.of("Cache", "GetAll", "${missKeys.size}")
        val l2Results = executor.executeOrDefault({
            val keyStrings = missKeys.map { it.toString() }
            strategy.getAll(keyStrings, Any::class.java)
        }, emptyMap(), context)

        // 3. L1 backfill + merge
        for ((keyStr, value) in l2Results) {
            val originalKey = missKeys.find { it.toString() == keyStr } ?: continue
            l1.put(originalKey, value)
            result[originalKey] = value
            l2HitCounter.increment()
        }

        val missCount = missKeys.size - l2Results.size
        repeat(missCount) { missCounter.increment() }

        return result
    }

    private fun publishEvictEvent(key: Any, version: Long) {
        callbackSupplier.get().accept(CacheInvalidationEvent.evict(name, key.toString(), instanceIdSupplier.get(), version))
    }

    private fun publishClearAllEvent() {
        callbackSupplier.get().accept(CacheInvalidationEvent.clearAll(name, instanceIdSupplier.get(), versionCounter.get()))
    }

    fun clearKeyVersions() {
        keyVersions.invalidateAll()
    }

    fun clearKeyVersion(key: Any) {
        keyVersions.invalidate(key)
    }

    fun getKeyVersion(key: Any): Long? = keyVersions.getIfPresent(key)

    fun getCurrentVersion(): Long = versionCounter.get()

    /** Shutdown batch buffers (called by TieredCacheManager @PreDestroy) */
    fun shutdown() {
        batchBuffer?.let { BatchL2LookupBuffer.shutdown() }
        writeBuffer?.let { BatchL2WriteBuffer.shutdown() }
    }

    override fun <T : Any?> get(key: Any, type: Class<T>?): T? {
        val wrapper = get(key)
        @Suppress("UNCHECKED_CAST")
        return wrapper?.let { type?.cast(it.get()) as? T }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> get(key: Any, valueLoader: Callable<T>): T {
        val keyStr = key.toString()
        val context = TaskContext.of("Cache", "GetWithLoader", keyStr)
        return executor.executeWithTranslation(
            { doGetWithSingleFlight(key, valueLoader, keyStr) as T },
            ExceptionTranslator.forCache(key, valueLoader),
            context,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> doGetWithSingleFlight(key: Any, valueLoader: Callable<T>, keyStr: String): T {
        val cached = getCachedValueFromLayers<T>(key)
        if (cached != null) return cached
        return executeWithDistributedLock(key, valueLoader, keyStr)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getCachedValueFromLayers(key: Any): T? {
        val l1Result = l1.get(key)
        if (l1Result != null) {
            l1HitCounter.increment()
            return l1Result.get() as? T
        }
        // Issue #555: Skip L2 lookup when disabled
        if (!l2Enabled) return null
        val l2Result = executor.executeOrDefault({ l2.get(key) }, null, TaskContext.of("Cache", "GetL2", key.toString()))
        if (l2Result != null) {
            l1.put(key, l2Result.get())
            l2HitCounter.increment()
            return l2Result.get() as? T
        }
        return null
    }

    private fun <T> executeWithDistributedLock(key: Any, valueLoader: Callable<T>, keyStr: String): T {
        // Issue #555: Skip distributed lock in L1-only mode (single-instance mode)
        if (!l2Enabled || leaderElectionStrategy == null) {
            return executeAndCache(key, valueLoader)
        }
        val lockKey = "cache:sf:" + l2.name + ":" + keyStr

        return leaderElectionStrategy.executeWithLeaderElection(
            key = lockKey,
            waitTimeSeconds = lockWaitSeconds,
            leaderTask = ThrowingSupplier { executeDoubleCheckAndLoad(key, valueLoader) },
            followerTask = ThrowingSupplier { pollL2OrThrow(key, keyStr) },
        )
    }

    /**
     * Follower: L2만 폴링. 타임아웃 시 valueLoader 호출하지 않고 예외 throw → stampede 방지 (#647)
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> pollL2OrThrow(key: Any, keyStr: String): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(lockWaitSeconds.toLong())
        while (System.nanoTime() < deadline) {
            val value = executor.executeOrDefault({ l2.get(key) }, null, TaskContext.of("Cache", "PollL2", keyStr))
            if (value != null) {
                l1.put(key, value.get())
                return value.get() as T
            }
            LockSupport.parkNanos(this, 50_000_000L) // 50ms polling, Virtual Thread friendly
        }
        // 타임아웃: valueLoader 호출하지 않고 예외 throw → stampede 방지
        stampedeTimeoutCounter.increment()
        throw CacheStampedeTimeoutException(l2.name, keyStr)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> executeDoubleCheckAndLoad(key: Any, valueLoader: Callable<T>): T {
        val wrapper = executor.executeOrDefault({ l2.get(key) }, null, TaskContext.of("Cache", "DoubleCheckL2", key.toString()))
        if (wrapper != null) {
            l1.put(key, wrapper.get())
            return wrapper.get() as T
        }
        missCounter.increment()
        return executeAndCache(key, valueLoader)
    }

    private fun <T> executeAndCache(key: Any, valueLoader: Callable<T>): T {
        val value = valueLoader.call()
        // Issue #555: In L1-only mode, skip L2 put
        if (!l2Enabled) {
            l1.put(key, value)
            return value
        }
        val buffer = writeBuffer
        if (buffer != null) {
            buffer.submit(key, value)
            return value
        }
        val l2Success = executor.executeOrDefault({
            l2.put(key, value)
            true
        }, false, TaskContext.of("Cache", "PutL2", key.toString()))
        if (l2Success) {
            l1.put(key, value)
        } else {
            log.warn("[TieredCache] L2 put failed, skipping L1: key={}", key)
            l2FailureCounter.increment()
        }
        return value
    }

    private fun tapCacheHit(wrapper: ValueWrapper, layer: String): ValueWrapper {
        if ("L1" == layer) l1HitCounter.increment() else l2HitCounter.increment()
        return wrapper
    }
}
