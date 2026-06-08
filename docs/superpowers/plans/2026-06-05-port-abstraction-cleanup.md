# Port Abstraction Cleanup (Issue #906) Implementation Plan

> **Note (2026-06-06):** 6 dead Like ports (LikeAtomicFetchStrategy, CompensationCommand, LikeRelationBufferStrategy, LikeRelationSyncPort, LikeSyncPort, LikeEventPublisher) were deleted in PR TBD. See [2026-06-06-like-port-merge-design.md](2026-06-06-like-port-merge-design.md) for the actual deletion rationale.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all infrastructure technology names (PGMQ, Redis, Kafka, MySQL, Spring, Micrometer) from module-core port interfaces, and replace `Any?`-returning methods with typed contracts defined in module-common.

**Architecture:** Single coordinated refactor. Core port interfaces become technology-neutral. Two new typed contracts (`DomainCache`, `MetricsRegistry`) live in module-common. Adapters in module-infra own all casts and queue-name strings. Method names use L-tier nomenclature.

**Tech Stack:** Kotlin, Spring Boot, Caffeine, PostgreSQL (PGMQ), Redis, Micrometer, JUnit 5, AssertJ, Awaitility.

---

## File Structure

### module-common (new)
- Create: `module-common/src/main/kotlin/maple/expectation/common/cache/DomainCache.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/metrics/MetricsRegistry.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/cache/LongCounter.kt` (interface)
- Create: `module-common/src/main/kotlin/maple/expectation/common/metrics/Timer.kt` (interface)

### module-core (modify)
- Rename: `PgmqPort.kt` → `MessageQueuePort.kt`
- Move: `QueueNames.kt` → `module-infra/.../infrastructure/queue/QueueNames.kt`
- Modify: `CacheManagerPort.kt` (typed returns, remove Javadoc infra refs)
- Modify: `MessageTopic.kt` (Javadoc)
- Modify: `EventPublisher.kt` (Javadoc)
- Modify: `FanOutQueuePort.kt` (Javadoc)
- Modify: `LikeSyncPort.kt` (method names + Javadoc)
- Modify: `LikeRelationSyncPort.kt` (method names + Javadoc)
- Modify: `LikeRelationBufferStrategy.kt` (Javadoc)
- Modify: `PersistenceTrackerStrategy.kt` (Javadoc)

### module-infra (modify)
- Rename: `PgmqPortAdapter.kt` → `MessageQueuePortAdapter.kt`
- Create: `infrastructure/cache/DomainCacheAdapter.kt`
- Create: `infrastructure/cache/SpringDomainCacheAdapter.kt`
- Create: `infrastructure/metrics/MetricsRegistryAdapter.kt`
- Create: `infrastructure/metrics/MicrometerLongCounterAdapter.kt`
- Create: `infrastructure/metrics/MicrometerTimerAdapter.kt`
- Modify: all 10+ impl classes that reference renamed port or methods (covered in tasks)

### module-app / module-external-api / module-calculator / module-synchronizer / module-rest-controller
- Update import paths for renamed ports + QueueNames
- Update method calls for renamed `flushLocalToL2()` / `syncL2ToPersistence()`
- Update `getCache().get(...)` → `getCache(...).get(key, Class<T>)`
- Update `getMeterRegistry().counter(...)` → `metricsRegistry.counter(name, tags)`

---

## Task 1: Create DomainCache + MetricsRegistry contracts in module-common

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/common/cache/DomainCache.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/cache/LongCounter.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/metrics/MetricsRegistry.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/metrics/Timer.kt`
- Create: `module-common/src/test/kotlin/maple/expectation/common/cache/DomainCacheTest.kt`

- [ ] **Step 1.1: Write failing test for DomainCache contract**

```kotlin
// module-common/src/test/kotlin/maple/expectation/common/cache/DomainCacheTest.kt
package maple.expectation.common.cache

import org.junit.jupiter.api.Test
import kotlin.test.assertNull

class DomainCacheTest {

    @Test
    fun `null cache get returns null without throwing`() {
        val cache: DomainCache = NullDomainCache
        assertNull(cache.get("any", String::class.java))
    }
}

object NullDomainCache : DomainCache {
    override fun <T : Any> get(key: String, type: Class<T>): T? = null
    override fun put(key: String, value: Any) {}
    override fun put(key: String, value: Any, ttl: java.time.Duration) {}
    override fun invalidate(key: String) {}
    override fun invalidateAll() {}
}
```

- [ ] **Step 1.2: Run test to verify it fails (no DomainCache yet)**

```bash
./gradlew :module-common:test --tests "*DomainCacheTest*" --continue
```

Expected: compilation error — `Unresolved reference: DomainCache`.

- [ ] **Step 1.3: Create DomainCache interface**

```kotlin
// module-common/src/main/kotlin/maple/expectation/common/cache/DomainCache.kt
package maple.expectation.common.cache

import java.time.Duration

/**
 * Technology-neutral cache abstraction.
 * Adapters in module-infra wrap Spring Cache, Caffeine, Redis, etc.
 */
interface DomainCache {
    fun <T : Any> get(key: String, type: Class<T>): T?
    fun put(key: String, value: Any)
    fun put(key: String, value: Any, ttl: Duration)
    fun invalidate(key: String)
    fun invalidateAll()
}
```

- [ ] **Step 1.4: Create LongCounter interface**

```kotlin
// module-common/src/main/kotlin/maple/expectation/common/cache/LongCounter.kt
package maple.expectation.common.cache

interface LongCounter {
    fun increment()
    fun increment(delta: Long)
}
```

- [ ] **Step 1.5: Create Timer interface**

```kotlin
// module-common/src/main/kotlin/maple/expectation/common/metrics/Timer.kt
package maple.expectation.common.metrics

import java.time.Duration

interface Timer {
    fun record(duration: Duration)
}
```

- [ ] **Step 1.6: Create MetricsRegistry interface**

```kotlin
// module-common/src/main/kotlin/maple/expectation/common/metrics/MetricsRegistry.kt
package maple.expectation.common.metrics

import maple.expectation.common.cache.LongCounter

/**
 * Technology-neutral metrics registry.
 * Adapters wrap Micrometer, Dropwizard, etc.
 */
interface MetricsRegistry {
    fun counter(name: String, tags: Map<String, String> = emptyMap()): LongCounter
    fun timer(name: String, tags: Map<String, String> = emptyMap()): Timer
}
```

- [ ] **Step 1.7: Run tests to verify pass**

```bash
./gradlew :module-common:test --tests "*DomainCacheTest*" --continue
```

Expected: PASS.

- [ ] **Step 1.8: Commit**

```bash
git add module-common/src/main/kotlin/maple/expectation/common/cache/DomainCache.kt \
        module-common/src/main/kotlin/maple/expectation/common/cache/LongCounter.kt \
        module-common/src/main/kotlin/maple/expectation/common/metrics/Timer.kt \
        module-common/src/main/kotlin/maple/expectation/common/metrics/MetricsRegistry.kt \
        module-common/src/test/kotlin/maple/expectation/common/cache/DomainCacheTest.kt
git commit -m "feat(common): add DomainCache + MetricsRegistry typed contracts"
```

---

## Task 2: Rename PgmqPort → MessageQueuePort

**Files:**
- Rename: `module-core/src/main/kotlin/maple/expectation/core/port/out/PgmqPort.kt` → `module-core/src/main/kotlin/maple/expectation/core/port/out/MessageQueuePort.kt`
- Modify: all files importing `PgmqPort`

- [ ] **Step 2.1: Create MessageQueuePort with new name + neutral Javadoc**

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/MessageQueuePort.kt
package maple.expectation.core.port.out

/**
 * Outbound port for message queue operations.
 *
 * <p>Technology-neutral contract. Adapters may target PGMQ, Kafka, RabbitMQ,
 * SQS, or any other message broker. Implementations are selected by
 * configuration and injected as a Spring bean.
 */
interface MessageQueuePort {
    fun send(queueName: String, message: Any): Long
    fun queueLength(queueName: String): Long
    fun findActiveMessageIdByUserIgn(queueName: String, userIgn: String): Long?
    fun sendIfAbsent(queueName: String, userIgn: String, payload: Any): Long
}
```

- [ ] **Step 2.2: Delete old PgmqPort.kt**

```bash
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/PgmqPort.kt
```

- [ ] **Step 2.3: Find all consumers**

```bash
grep -rln "PgmqPort" --include="*.kt" --include="*.java" module-core module-infra module-app module-external-api module-calculator module-synchronizer module-rest-controller
```

Expected: ~10 files (adapter + callers).

- [ ] **Step 2.4: Update each consumer's import + type reference**

In each file, replace:
- `import maple.expectation.core.port.out.PgmqPort` → `import maple.expectation.core.port.out.MessageQueuePort`
- `PgmqPort` (type) → `MessageQueuePort`
- `: PgmqPort` (constructor/type) → `: MessageQueuePort`

Files (from grep in 2.3):
- `module-infra/.../adapter/outgoing/PgmqPortAdapter.kt` → rename file too (Task 3)
- All other call sites: type + import only

- [ ] **Step 2.5: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS, no `Unresolved reference: PgmqPort` errors.

- [ ] **Step 2.6: Commit**

```bash
git add -A
git commit -m "refactor(core): rename PgmqPort to MessageQueuePort for tech-neutrality"
```

---

## Task 3: Rename PgmqPortAdapter → MessageQueuePortAdapter

**Files:**
- Rename: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/PgmqPortAdapter.kt` → `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/MessageQueuePortAdapter.kt`
- Update: `@Service`/`@Component`/qualifier name if any uses `pgmqPort`

- [ ] **Step 3.1: Read current adapter to check qualifier name**

```bash
grep -nE "@(Service|Component)|pgmqPort" module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/PgmqPortAdapter.kt
```

- [ ] **Step 3.2: Create new file with new class name**

```kotlin
// module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/MessageQueuePortAdapter.kt
package maple.expectation.adapter.outgoing

import maple.expectation.core.port.out.MessageQueuePort
import org.springframework.stereotype.Component

@Component
class MessageQueuePortAdapter(
    // existing constructor params preserved
) : MessageQueuePort {
    // existing method bodies unchanged
}
```

(Body comes from the existing PgmqPortAdapter — copy verbatim, only class name + interface name change.)

- [ ] **Step 3.3: Delete old file**

```bash
git rm module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/PgmqPortAdapter.kt
```

- [ ] **Step 3.4: Find all consumers of PgmqPortAdapter**

```bash
grep -rln "PgmqPortAdapter" --include="*.kt" --include="*.java" module-infra module-app
```

- [ ] **Step 3.5: Update each consumer**

- Replace `import ...PgmqPortAdapter` → `import ...MessageQueuePortAdapter`
- Replace `PgmqPortAdapter` (type) → `MessageQueuePortAdapter`
- Replace qualifier string `pgmqPort` (if any) → `messageQueuePort`

- [ ] **Step 3.6: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS.

- [ ] **Step 3.7: Commit**

```bash
git add -A
git commit -m "refactor(infra): rename PgmqPortAdapter to MessageQueuePortAdapter"
```

---

## Task 4: Move QueueNames to module-infra

**Files:**
- Move: `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt` → `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/QueueNames.kt`
- Update: all import sites

- [ ] **Step 4.1: Create QueueNames in module-infra with infra package**

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/QueueNames.kt
package maple.expectation.infrastructure.queue

/**
 * Queue name constants.
 *
 * <p>String identifiers for message broker queues. These values are
 * adapter-implementation specific (PGMQ in current setup) and are not
 * part of the public domain port contract.
 */
object QueueNames {
    const val EXPECTATION_CALC_HIGH = "expectation_calc_high"
    const val EXPECTATION_CALC_LOW = "expectation_calc_low"
    const val EXTERNAL_API = "external_api_queue"
    const val CALCULATION_REQUESTED = "calculation_requested_queue"
    const val CALCULATION_COMPLETED = "calculation_completed_queue"
    const val NEXON_API_REQUEST = "nexon_api_request_queue"
    const val NEXON_API_RESPONSE = "nexon_api_response_queue"
    const val OCID_RESOLVE = "ocid_resolve_queue"
    const val RESULT_READY = "result_ready_queue"
}
```

- [ ] **Step 4.2: Delete from module-core**

```bash
git rm module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt
```

- [ ] **Step 4.3: Find all consumers**

```bash
grep -rln "core.port.out.QueueNames" --include="*.kt" --include="*.java" .
```

- [ ] **Step 4.4: Update each consumer's import**

- `import maple.expectation.core.port.out.QueueNames` → `import maple.expectation.infrastructure.queue.QueueNames`

- [ ] **Step 4.5: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS.

- [ ] **Step 4.6: Commit**

```bash
git add -A
git commit -m "refactor(infra): move QueueNames from core to module-infra"
```

---

## Task 5: Update CacheManagerPort to typed contract

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/inbound/CacheManagerPort.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/CacheManagerPortAdapter.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/SpringDomainCacheAdapter.kt`
- Modify: all callers of `getCache().get(...)` and `getMeterRegistry()`

- [ ] **Step 5.1: Rewrite CacheManagerPort with typed returns**

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/CacheManagerPort.kt
package maple.expectation.core.port.inbound

import maple.expectation.common.cache.DomainCache
import maple.expectation.common.metrics.MetricsRegistry

/**
 * Inbound port for cache + metrics access.
 *
 * <p>Returns technology-neutral {@link DomainCache} and {@link MetricsRegistry}
 * instances. Adapters cast from Spring Cache / Micrometer MeterRegistry.
 */
interface CacheManagerPort {
    fun getCache(name: String): DomainCache
    fun getMetrics(): MetricsRegistry
    fun getL1Cache(name: String): DomainCache
}
```

- [ ] **Step 5.2: Create SpringDomainCacheAdapter**

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/SpringDomainCacheAdapter.kt
package maple.expectation.infrastructure.cache

import maple.expectation.common.cache.DomainCache
import org.springframework.cache.Cache
import java.time.Duration

class SpringDomainCacheAdapter(private val springCache: Cache) : DomainCache {
    override fun <T : Any> get(key: String, type: Class<T>): T? {
        val wrapper = springCache.get(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return wrapper.get() as? T
    }

    override fun put(key: String, value: Any) {
        springCache.put(key, value)
    }

    override fun put(key: String, value: Any, ttl: Duration) {
        springCache.put(key, value)
    }

    override fun invalidate(key: String) {
        springCache.evict(key)
    }

    override fun invalidateAll() {
        springCache.clear()
    }
}
```

- [ ] **Step 5.3: Create Micrometer adapter helpers**

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/MetricsRegistryAdapter.kt
package maple.expectation.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.cache.LongCounter
import maple.expectation.common.metrics.MetricsRegistry
import maple.expectation.common.metrics.Timer

class MetricsRegistryAdapter(private val meterRegistry: MeterRegistry) : MetricsRegistry {
    override fun counter(name: String, tags: Map<String, String>): LongCounter =
        MicrometerLongCounterAdapter(meterRegistry.counter(name, tags))

    override fun timer(name: String, tags: Map<String, String>): Timer =
        MicrometerTimerAdapter(meterRegistry.timer(name, tags))
}
```

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/MicrometerLongCounterAdapter.kt
package maple.expectation.infrastructure.metrics

import maple.expectation.common.cache.LongCounter

class MicrometerLongCounterAdapter(
    private val counter: io.micrometer.core.instrument.Counter
) : LongCounter {
    override fun increment() = counter.increment()
    override fun increment(delta: Long) = counter.increment(delta.toDouble())
}
```

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/MicrometerTimerAdapter.kt
package maple.expectation.infrastructure.metrics

import io.micrometer.core.instrument.Timer as MicrometerTimer
import maple.expectation.common.metrics.Timer
import java.time.Duration

class MicrometerTimerAdapter(private val timer: MicrometerTimer) : Timer {
    override fun record(duration: Duration) {
        timer.record(duration)
    }
}
```

- [ ] **Step 5.4: Rewrite CacheManagerPortAdapter**

```kotlin
// module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/CacheManagerPortAdapter.kt
package maple.expectation.infrastructure.cache

import io.micrometer.core.instrument.MeterRegistry
import maple.expectation.common.cache.DomainCache
import maple.expectation.common.metrics.MetricsRegistry
import maple.expectation.core.port.inbound.CacheManagerPort
import maple.expectation.infrastructure.metrics.MetricsRegistryAdapter
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component

@Component
class CacheManagerPortAdapter(
    private val springCacheManager: CacheManager,
    meterRegistry: MeterRegistry
) : CacheManagerPort {

    private val metrics: MetricsRegistry = MetricsRegistryAdapter(meterRegistry)

    override fun getCache(name: String): DomainCache {
        val spring = requireNotNull(springCacheManager.getCache(name)) {
            "Cache '$name' not found in Spring CacheManager"
        }
        return SpringDomainCacheAdapter(spring)
    }

    override fun getMetrics(): MetricsRegistry = metrics

    override fun getL1Cache(name: String): DomainCache = getCache(name)
}
```

- [ ] **Step 5.5: Find all callers using `getCache().get()` or `getMeterRegistry()`**

```bash
grep -rln "getCache(\|getMeterRegistry(\|getL1CacheDirect(" --include="*.kt" --include="*.java" module-app module-infra module-external-api module-calculator module-synchronizer module-rest-controller
```

- [ ] **Step 5.6: For each caller, apply transformation pattern**

Before:
```kotlin
val cache = cacheManagerPort.getCache("v5-character") as org.springframework.cache.Cache
val value = cache.get(key, CharacterView::class.java)
```

After:
```kotlin
val cache = cacheManagerPort.getCache("v5-character")
val value: CharacterView? = cache.get(key, CharacterView::class.java)
```

Before (metrics):
```kotlin
val registry = cacheManagerPort.getMeterRegistry() as io.micrometer.core.instrument.MeterRegistry
registry.counter("foo", "tag", "v").increment()
```

After:
```kotlin
cacheManagerPort.getMetrics().counter("foo", mapOf("tag" to "v")).increment()
```

- [ ] **Step 5.7: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS.

- [ ] **Step 5.8: Commit**

```bash
git add -A
git commit -m "refactor(core,infra): type CacheManagerPort via DomainCache + MetricsRegistry"
```

---

## Task 6: Rename LikeSyncPort / LikeRelationSyncPort methods to L-tier

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt`
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt`
- Modify: all callers of `flushLocalToRedis()` / `syncRedisToDatabase()`

- [ ] **Step 6.1: Update LikeSyncPort interface**

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt
package maple.expectation.core.port.out

/**
 * Like Sync Port - 좋아요 동기화 작업을 위한 인터페이스
 *
 * <p>Method names reference cache tiers (L1/L2) and persistence layer
 * in a technology-neutral way. Adapters determine the actual storage.
 */
interface LikeSyncPort {
    /** L1 → L2 flush */
    fun flushLocalToL2()

    /** L2 → persistence sync */
    fun syncL2ToPersistence()
}
```

- [ ] **Step 6.2: Update LikeRelationSyncPort interface (same shape)**

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt
package maple.expectation.core.port.out

interface LikeRelationSyncPort {
    fun flushLocalToL2()
    fun syncL2ToPersistence()
}
```

- [ ] **Step 6.3: Find all callers**

```bash
grep -rln "flushLocalToRedis\|syncRedisToDatabase" --include="*.kt" --include="*.java" .
```

- [ ] **Step 6.4: Rename method calls at each site**

- `flushLocalToRedis()` → `flushLocalToL2()`
- `syncRedisToDatabase()` → `syncL2ToPersistence()`

- [ ] **Step 6.5: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS.

- [ ] **Step 6.6: Commit**

```bash
git add -A
git commit -m "refactor(core): rename LikeSync/LikeRelationSync methods to L-tier"
```

---

## Task 7: Strip Javadoc infra references from remaining core ports

**Files:**
- Modify: `MessageTopic.kt`
- Modify: `EventPublisher.kt`
- Modify: `FanOutQueuePort.kt`
- Modify: `LikeRelationBufferStrategy.kt`
- Modify: `PersistenceTrackerStrategy.kt`

- [ ] **Step 7.1: Update MessageTopic.kt Javadoc**

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/MessageTopic.kt
package maple.expectation.core.port.out

import java.util.function.BiConsumer

/**
 * Message topic for pub/sub pattern.
 *
 * <p>Domain port for publish-subscribe messaging. Adapters wrap any
 * pub/sub technology (message broker topics, Redis pub/sub, Kafka topics,
 * etc.). Business logic depends on this interface.
 *
 * @param <T> message type
 */
interface MessageTopic<T> {
    fun addListener(messageType: Class<T>, listener: BiConsumer<String, T>): Int
    fun removeListener(listenerId: Int)
    fun publish(channel: String, message: T)
}
```

- [ ] **Step 7.2: Update EventPublisher.kt Javadoc**

Replace Javadoc block with:
```kotlin
/**
 * Strategy interface for event publishing.
 *
 * <p>Concrete adapters (PGMQ, Kafka, etc.) are interchangeable via
 * configuration. Business logic depends on this abstraction, not on
 * concrete publisher implementations.
 */
```

Remove `@see maple.expectation.infrastructure.messaging.PgmqStreamPublisher` and the ADR reference.

- [ ] **Step 7.3: Update FanOutQueuePort.kt Javadoc**

Replace Javadoc block with:
```kotlin
/**
 * FanOut 큐 발행 Port (DIP - Hexagonal Architecture)
 *
 * <p>Rate-limit retry 메시지를 큐로 발행하는 outbound port.
 * 실제 메시지 브로커는 adapter가 결정.
 */
```

Remove `@see maple.expectation.infrastructure.queue.pgmq.FanOutQueueProducer`.

- [ ] **Step 7.4: Update LikeRelationBufferStrategy.kt Javadoc**

Remove the "Implementations" list and the `RedisLikeRelationBuffer` reference. Replace with:
```kotlin
/**
 * Like Relation Buffer Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <p>Pluggable strategy for buffering like relations. Implementations
 * vary by storage backend (in-memory, distributed cache, etc.) and are
 * selected via configuration.
 */
```

Keep `StrategyType` enum (in-memory, distributed) but rename enum values from `REDIS` to `DISTRIBUTED` if any caller references the string value. Otherwise leave enum name as-is — the strategy type describes deployment topology, not tech.

- [ ] **Step 7.5: Update PersistenceTrackerStrategy.kt Javadoc**

Remove `RedisEquipmentPersistenceTracker` and `EquipmentPersistenceTracker` references. Replace with:
```kotlin
/**
 * Persistence Tracker Strategy Interface (#271 V5 Stateless Architecture)
 *
 * <p>Pluggable strategy for tracking async persistence operations.
 * Implementations vary by storage backend and crash-recovery needs.
 */
```

Remove the `@see` tags pointing to specific impl classes.

- [ ] **Step 7.6: Verify compile**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: PASS.

- [ ] **Step 7.7: Commit**

```bash
git add -A
git commit -m "docs(core): strip infra class references from port Javadocs"
```

---

## Task 8: Run full test suite

- [ ] **Step 8.1: Run module-common + module-core + module-infra tests**

```bash
./gradlew :module-common:test :module-core:test :module-infra:test --continue
```

Expected: PASS. No test failures.

- [ ] **Step 8.2: Run all tests**

```bash
./gradlew test --continue
```

Expected: PASS.

- [ ] **Step 8.3: If failures, fix and commit per-task**

Per project rule: `./gradlew test` must pass before PR. Fix in small atomic commits, not one mega-commit.

---

## Task 9: Server runtime verification (per workflow-rules.md)

- [ ] **Step 9.1: Start module-app**

```bash
set -a && source .env && set +a
./gradlew :module-app:bootRun
```

Wait for healthy (poll `curl -sf http://localhost:8080/actuator/health` until 200).

- [ ] **Step 9.2: Hit V5 API**

```bash
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

Expected: `HTTP 202`.

- [ ] **Step 9.3: Verify completion log**

```bash
grep "Calculation completed with result saved" module-calculator/logs/app.log | tail -5
grep "ERROR" module-app/logs/app.log | tail -10
```

Expected: at least one `Calculation completed` line, zero `ERROR` lines.

- [ ] **Step 9.4: Stop server**

```bash
pkill -f "gradlew :module-app:bootRun" || true
```

---

## Task 10: Final grep audit

- [ ] **Step 10.1: Confirm zero tech names in module-core ports**

```bash
grep -rE "Pgmq|Redis|Kafka|MySQL" module-core/src/main/kotlin/maple/expectation/core/port/ || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 10.2: Confirm zero infra `@see` in port Javadocs**

```bash
grep -rE "@see maple.expectation.infrastructure" module-core/src/main/kotlin/maple/expectation/core/port/ || echo "CLEAN"
```

Expected: `CLEAN`.

- [ ] **Step 10.3: Confirm zero `Any?` in CacheManagerPort**

```bash
grep "Any?" module-core/src/main/kotlin/maple/expectation/core/port/inbound/CacheManagerPort.kt || echo "CLEAN"
```

Expected: `CLEAN`.

---

## Self-Review Checklist (before execution)

- [x] Spec coverage: every section in `2026-06-05-port-abstraction-cleanup-design.md` maps to a task
- [x] Placeholder scan: no TBD/TODO/FIXME
- [x] Type consistency: `DomainCache.get<T>(key, Class<T>)` used uniformly; `MessageQueuePort` (not Pgmq) used throughout
- [x] No method signature mismatches across tasks
- [x] Build verification: `--continue` flag on every compile
- [x] Runtime verification: workflow-rules.md procedure in Task 9
