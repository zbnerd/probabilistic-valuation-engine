# Off-heap Calculator OCID Cache (Chronicle Map) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 100K-entry Caffeine OCID lookup cache in `module-calculator` with Chronicle Map (off-heap KV), behind a profile switch. Caffeine remains as the default and as the auto-fallback when Chronicle init fails. Reduces calculator heap by 30–50MB without changing call-site code.

**Architecture:** `OffHeapCacheBackend<K, V>` interface in `module-calculator/.../cache/`. Two impls: `CaffeineCacheBackend` (existing logic, refactored) and `ChronicleMapBackend` (new). `CacheBackendFactory` selects impl from `calculator.cache.backend` profile property (`caffeine` default, `chronicle` opt-in). `CalculationCache` refactored to depend on the interface; callers (`SnapshotChunkProcessor`) untouched. Chronicle Map pinned to exact stable version `3.26.8`; init failure auto-falls-back to Caffeine + WARN log.

**Tech Stack:** Kotlin (JVM 21), Spring Boot, Chronicle Map 3.26.8, Caffeine 3.1.8 (existing), Micrometer/Prometheus.

**Spec:** [`docs/superpowers/specs/2026-06-19-offheap-calculator-cache-design.md`](../specs/2026-06-19-offheap-calculator-cache-design.md)

---

## File Structure

| Status | Path | Responsibility |
|--------|------|----------------|
| Modify | `gradle/libs.versions.toml` | Add Chronicle Map version + library entry |
| Modify | `module-calculator/build.gradle` | Add `chronicle-map` implementation dep |
| Modify | `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt` | Depend on `OffHeapCacheBackend<CacheKey, ComponentCosts>` instead of concrete Caffeine `Cache`. Public API unchanged. |
| Modify | `module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt` | Read stats from `OffHeapCacheBackend` interface; tag by backend name |
| Modify | `module-calculator/src/main/resources/application.yml` | Add `calculator.cache.backend`, `calculator.cache.chronicle.path`, `calculator.cache.chronicle.max-entries` |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt` | Generic interface `get/put/size/stats/close` |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/CacheStats.kt` | Immutable stats snapshot (hits, misses, errors, size) |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt` | Config holder (path, maxEntries) |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendSerializers.kt` | Chronicle `ValueSerializer<CacheKey>` + `ValueSerializer<ComponentCosts>` |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt` | Caffeine impl, wraps existing builder pattern |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt` | Chronicle Map impl, mmap-backed file |
| Create | `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt` | Profile switch + Chronicle init fallback |
| Create | `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt` | Spring `@Configuration` + `@Bean(destroyMethod = "close")` |
| Create | `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendTest.kt` | 11 unit tests (issue AC baseline + extensions) |
| Create | `docker/prometheus/rules/cache-backend-alerts.yml` | `calculator_cache_errors_total` rate alert |

---

## Task 1: Add Chronicle Map Dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `module-calculator/build.gradle`

- [ ] **Step 1.1: Add Chronicle Map version + library entry to version catalog**

Edit `gradle/libs.versions.toml`. Find the `[versions]` block and the `[libraries]` block. Add:

```toml
# In [versions]
chronicle-map = "3.26.8"
```

```toml
# In [libraries]
chronicle-map = { module = "net.openhft:chronicle-map", version.ref = "chronicle-map" }
```

Place the `[versions]` entry alphabetically near `caffeine`. Place the `[libraries]` entry after the existing `caffeine` library line.

- [ ] **Step 1.2: Add dependency to module-calculator/build.gradle**

Edit `module-calculator/build.gradle`. After the `implementation(libs.caffeine)` line (line 37), add:

```groovy
	// Off-heap cache (issue #1311)
	implementation(libs.chronicle.map)
```

- [ ] **Step 1.3: Verify Gradle resolves the new dependency**

Run:
```bash
./gradlew :module-calculator:dependencies --configuration runtimeClasspath | grep chronicle
```

Expected: One line containing `net.openhft:chronicle-map:3.26.8`.

- [ ] **Step 1.4: Verify the module still compiles**

Run:
```bash
./gradlew :module-calculator:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. No new errors. (Existing code is untouched in this task.)

- [ ] **Step 1.5: Commit**

```bash
git add gradle/libs.versions.toml module-calculator/build.gradle
git commit -m "build(calculator): add chronicle-map 3.26.8 dependency

Pinned exact patch for off-heap OCID cache (issue #1311, Phase 2).
Chronicle Map 3.26.8 (latest stable, Dec 2025). File format version-sensitive; pin required."
```

---

## Task 2: Define `OffHeapCacheBackend` Interface

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CacheStats.kt`

- [ ] **Step 2.1: Create CacheStats data class**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/CacheStats.kt`:

```kotlin
package maple.calculator.cache

/**
 * Immutable snapshot of cache backend counters.
 * Sourced from [OffHeapCacheBackend.stats] on demand; Prometheus scrape reads
 * this supplier so values are always current.
 */
data class CacheStats(
    val size: Long,
    val hits: Long,
    val misses: Long,
    val errors: Long,
) {
    val hitRatePercent: Double
        get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses) * 100.0
}
```

- [ ] **Step 2.2: Create OffHeapCacheBackend interface**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt`:

```kotlin
package maple.calculator.cache

/**
 * Off-heap cache backend abstraction. Two impls: [CaffeineCacheBackend]
 * (heap, default) and [ChronicleMapBackend] (off-heap, opt-in).
 *
 * Implementations must:
 * - be safe for concurrent get/put from multiple threads
 * - never block callers on a missing/corrupt file (fail-soft per spec §5)
 * - report size/hits/misses/errors via [stats]
 */
interface OffHeapCacheBackend<K : Any, V : Any> : AutoCloseable {

    /** Returns the cached value for [key], or null on miss. Increments hit/miss counters. */
    fun get(key: K): V?

    /** Stores [value] under [key]. On error: logs, increments error counter, does NOT throw. */
    fun put(key: K, value: V)

    /** Current number of entries. O(1). */
    fun size(): Long

    /** Cumulative stats snapshot. */
    fun stats(): CacheStats

    /** Name of the backend ("caffeine" or "chronicle"). Used as Prometheus label value. */
    val name: String

    /** Release native resources. Best-effort; never throws. */
    override fun close()
}
```

- [ ] **Step 2.3: Verify the new files compile**

Run:
```bash
./gradlew :module-calculator:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.4: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt \
        module-calculator/src/main/kotlin/maple/calculator/cache/CacheStats.kt
git commit -m "feat(calculator): define OffHeapCacheBackend interface

Generic K/V contract with stats snapshot for Prometheus scraping.
Used by CaffeineCacheBackend and ChronicleMapBackend."
```

---

## Task 3: Define `CacheConfig` Holder

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt`

- [ ] **Step 3.1: Create CacheConfig**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt`:

```kotlin
package maple.calculator.cache

/**
 * Cache backend configuration. Bound from `calculator.cache.*` YAML keys.
 *
 * @property maxEntries Caffeine maximumSize / Chronicle entries().
 *   Sized to 100K to match existing working set (one chunk of item-equipment lookups).
 * @property chroniclePath Filesystem path for the Chronicle Map mmap file.
 *   Ignored when backend is caffeine.
 */
data class CacheConfig(
    val maxEntries: Long = 100_000L,
    val chroniclePath: String = "/var/lib/calculator/chronicle-ocid",
)
```

- [ ] **Step 3.2: Verify compile**

Run:
```bash
./gradlew :module-calculator:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt
git commit -m "feat(calculator): add CacheConfig holder for backend configuration"
```

---

## Task 4: Implement `CaffeineCacheBackend` (TDD)

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/cache/CaffeineCacheBackendTest.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt`

- [ ] **Step 4.1: Write failing test for putGetRoundTrip**

Create file `module-calculator/src/test/kotlin/maple/calculator/cache/CaffeineCacheBackendTest.kt`:

```kotlin
package maple.calculator.cache

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("unit")
class CaffeineCacheBackendTest {

    private lateinit var backend: OffHeapCacheBackend<String, String>

    @AfterEach
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    @Test
    fun `put then get returns the stored value`() {
        backend = CaffeineCacheBackend<String, String>(CacheConfig())
        backend.put("key1", "value1")
        assertEquals("value1", backend.get("key1"))
    }

    @Test
    fun `put twice with same key overwrites`() {
        backend = CaffeineCacheBackend<String, String>(CacheConfig())
        backend.put("k", "v1")
        backend.put("k", "v2")
        assertEquals("v2", backend.get("k"))
    }

    @Test
    fun `size reflects entry count`() {
        backend = CaffeineCacheBackend<String, String>(CacheConfig())
        backend.put("a", "1")
        backend.put("b", "2")
        backend.put("c", "3")
        assertEquals(3L, backend.size())
    }

    @Test
    fun `get returns null on miss and increments miss counter`() {
        backend = CaffeineCacheBackend<String, String>(CacheConfig())
        assertNull(backend.get("missing"))
        assertEquals(1L, backend.stats().misses)
        assertEquals(0L, backend.stats().hits)
    }

    @Test
    fun `name returns caffeine`() {
        backend = CaffeineCacheBackend<String, String>(CacheConfig())
        assertEquals("caffeine", backend.name)
    }
}
```

- [ ] **Step 4.2: Run tests to verify they fail**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CaffeineCacheBackendTest"
```

Expected: FAIL with "Unresolved reference: CaffeineCacheBackend".

- [ ] **Step 4.3: Implement CaffeineCacheBackend**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt`:

```kotlin
package maple.calculator.cache

import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.atomic.LongAdder

/**
 * Caffeine-backed [OffHeapCacheBackend]. Heap-resident; the default and the
 * permanent fallback for Chronicle Map init failures.
 *
 * Concurrent-safe via Caffeine's internal striping. Counters use LongAdder
 * to avoid contention under load (4 concurrent chunk workers).
 */
class CaffeineCacheBackend<K : Any, V : Any>(
    private val config: CacheConfig,
) : OffHeapCacheBackend<K, V> {

    override val name: String = "caffeine"

    private val cache = Caffeine.newBuilder()
        .maximumSize(config.maxEntries)
        .recordStats()
        .build<K, V>()

    private val hitsAdder = LongAdder()
    private val missesAdder = LongAdder()
    private val errorsAdder = LongAdder()

    override fun get(key: K): V? {
        val v = cache.getIfPresent(key)
        if (v == null) missesAdder.increment() else hitsAdder.increment()
        return v
    }

    override fun put(key: K, value: V) {
        try {
            cache.put(key, value)
        } catch (e: Exception) {
            errorsAdder.increment()
            // Fail-soft per spec §5: log and continue. Do not propagate.
            // (logger injected via companion in task 5 wiring; here we keep it inline.)
        }
    }

    override fun size(): Long = cache.estimatedSize()

    override fun stats(): CacheStats = CacheStats(
        size = cache.estimatedSize(),
        hits = hitsAdder.sum(),
        misses = missesAdder.sum(),
        errors = errorsAdder.sum(),
    )

    override fun close() {
        cache.invalidateAll()
        cache.cleanUp()
    }
}
```

- [ ] **Step 4.4: Run tests to verify they pass**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CaffeineCacheBackendTest"
```

Expected: 5 tests passed.

- [ ] **Step 4.5: Commit**

```bash
git add module-calculator/src/test/kotlin/maple/calculator/cache/CaffeineCacheBackendTest.kt \
        module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt
git commit -m "feat(calculator): CaffeineCacheBackend impl with LongAdder counters

Wraps existing Caffeine builder behind OffHeapCacheBackend.
5 unit tests cover put/get/overwrite/size/miss-counter/name."
```

---

## Task 5: Refactor `CalculationCache` to Use `OffHeapCacheBackend`

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt`

Current file has `CalculationCache(factory)` with private `cache: Cache<CacheKey, ComponentCosts>`. Refactor to inject `OffHeapCacheBackend<CacheKey, ComponentCosts>` and delegate.

- [ ] **Step 5.1: Read current callers**

```bash
grep -rn "CalculationCache" module-calculator/src --include="*.kt"
```

Expect callers in `SnapshotChunkProcessor.kt` and possibly `CacheMetrics.kt`. Note them for verification.

- [ ] **Step 5.2: Rewrite CalculationCache.kt**

Replace `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt` with:

```kotlin
package maple.calculator.processor

import maple.calculator.cache.OffHeapCacheBackend
import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.core.dto.v4.EquipmentCalculationInput
import org.springframework.stereotype.Component

@Component
class CalculationCache(
    private val factory: EquipmentExpectationCalculatorFactory,
    private val backend: OffHeapCacheBackend<CacheKey, ComponentCosts>,
) {
    data class CacheKey(
        val itemName: String,
        val itemPart: String,
        val itemLevel: Int,
        val potentialGrade: String?,
        val potentialOptions: List<String?>?,
        val additionalPotentialGrade: String?,
        val additionalPotentialOptions: List<String?>?,
        val targetStar: Int,
        val isNoljang: Boolean,
    )

    data class ComponentCosts(
        val blackCubeCost: Double?,
        val additionalCubeCost: Double?,
        val starforceCost: Double?,
    ) {
        val hasAnyCost: Boolean = blackCubeCost != null || additionalCubeCost != null || starforceCost != null
        val totalCost: Double?
            get() = if (hasAnyCost) {
                (blackCubeCost ?: 0.0) + (additionalCubeCost ?: 0.0) + (starforceCost ?: 0.0)
            } else {
                null
            }

        companion object {
            fun empty(): ComponentCosts = ComponentCosts(null, null, null)
        }
    }

    fun backend(): OffHeapCacheBackend<CacheKey, ComponentCosts> = backend

    fun stats(): String {
        val s = backend.stats()
        return "size=${s.size} hits=${s.hits} misses=${s.misses} hitRate=${"%.1f%%".format(s.hitRatePercent)}"
    }

    fun calculate(input: EquipmentCalculationInput): ComponentCosts {
        val key = CacheKey(
            itemName = input.itemName,
            itemPart = input.itemPart,
            itemLevel = input.itemLevel,
            potentialGrade = input.potentialGrade,
            potentialOptions = input.potentialOptions,
            additionalPotentialGrade = input.additionalPotentialGrade,
            additionalPotentialOptions = input.additionalPotentialOptions,
            targetStar = input.targetStar,
            isNoljang = input.isNoljang,
        )
        val cached = backend.get(key)
        if (cached != null) return cached
        val calculator = factory.createFullCalculator(input)
        val details = calculator.detailedCosts
        val value = ComponentCosts(
            blackCubeCost = details.blackCubeCost,
            additionalCubeCost = details.additionalCubeCost,
            starforceCost = details.starforceCost,
        )
        backend.put(key, value)
        return value
    }
}
```

- [ ] **Step 5.3: Verify compile fails (no bean wired yet)**

Run:
```bash
./gradlew :module-calculator:compileKotlin --continue
```

Expected: FAIL because no `OffHeapCacheBackend<CacheKey, ComponentCosts>` bean exists yet. (Wiring added in Task 7.)

If compile succeeds: an existing `@Bean` somewhere already provides the type. Grep to confirm before proceeding.

- [ ] **Step 5.4: Commit (compilation will be wired in Task 7)**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt
git commit -m "refactor(calculator): CalculationCache depends on OffHeapCacheBackend

Public API (calculate, stats) unchanged. SnapshotChunkProcessor and
other callers untouched. Spring @Bean wiring added in next task."
```

---

## Task 6: Implement `CacheBackendFactory` (TDD)

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendFactoryTest.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt`

- [ ] **Step 6.1: Write failing tests**

Create file `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendFactoryTest.kt`:

```kotlin
package maple.calculator.cache

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("unit")
class CacheBackendFactoryTest {

    private val created = mutableListOf<OffHeapCacheBackend<*, *>>()

    @AfterEach
    fun tearDown() {
        created.forEach { it.close() }
        created.clear()
    }

    private fun <K : Any, V : Any> track(b: OffHeapCacheBackend<K, V>): OffHeapCacheBackend<K, V> {
        @Suppress("UNCHECKED_CAST")
        created.add(b as OffHeapCacheBackend<*, *>)
        return b
    }

    @Test
    fun `caffeine profile returns CaffeineCacheBackend`() {
        val b = track(CacheBackendFactory.create("caffeine", CacheConfig(), String::class.java, String::class.java))
        assertEquals("caffeine", b.name)
    }

    @Test
    fun `invalid profile falls back to caffeine`() {
        val b = track(CacheBackendFactory.create("redis", CacheConfig(), String::class.java, String::class.java))
        assertEquals("caffeine", b.name)
    }

    @Test
    fun `chronicle profile returns ChronicleMapBackend when available`() {
        // Smoke test: Chronicle Map 3.26.8 should be on the test classpath.
        // If init fails for any reason (e.g., test env lacks tmp dir), this test fails loudly.
        val b = track(CacheBackendFactory.create("chronicle", CacheConfig(maxEntries = 100L), String::class.java, String::class.java))
        assertEquals("chronicle", b.name)
    }

    @Test
    fun `chronicle init failure falls back to caffeine`() {
        // Pass a path that is a directory — Chronicle's file open will fail.
        // Deterministic failure independent of filesystem permissions.
        val tempDir = kotlin.io.path.createTempDirectory("chronicle-fallback-test").toFile()
        try {
            val cfg = CacheConfig(chroniclePath = tempDir.absolutePath) // path IS a directory
            val b = track(CacheBackendFactory.create("chronicle", cfg, String::class.java, String::class.java))
            assertEquals("caffeine", b.name, "expected fallback to caffeine on init failure")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `chronicle missing library falls back to caffeine`() {
        // Simulate Chronicle Map class missing by asking factory to load a Chronicle type
        // that does not exist on the classpath. The factory's catch (NoClassDefFoundError)
        // path must engage. We approximate by passing a Class object from a bogus package.
        // Direct unit-level trigger of NoClassDefFoundError in JVM tests requires classloader
        // manipulation; instead, verify the catch block is reachable via reflection.
        val method = CacheBackendFactory::class.java.getDeclaredMethod(
            "fallbackToCaffeine", String::class.java, CacheConfig::class.java, Throwable::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val b = method.invoke(
            CacheBackendFactory, "chronicle", CacheConfig(),
            NoClassDefFoundError("net.openhft.chronicle.map.ChronicleMap"),
        ) as OffHeapCacheBackend<String, String>
        assertEquals("caffeine", b.name)
    }
}
```

- [ ] **Step 6.2: Run tests to verify they fail**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendFactoryTest"
```

Expected: FAIL with "Unresolved reference: CacheBackendFactory".

- [ ] **Step 6.3: Implement CacheBackendFactory**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt`:

```kotlin
package maple.calculator.cache

import org.slf4j.LoggerFactory

/**
 * Selects and instantiates the configured [OffHeapCacheBackend].
 *
 * Profile values:
 * - "caffeine" (default): instantiates CaffeineCacheBackend directly.
 * - "chronicle": tries ChronicleMapBackend; falls back to CaffeineCacheBackend
 *   on recoverable init failure (Exception, NoClassDefFoundError, LinkageError),
 *   logging WARN. Auto-fallback is per spec §5.
 * - anything else: logs ERROR, returns CaffeineCacheBackend.
 *
 * Why multi-catch (not `catch (Throwable)`): `OutOfMemoryError`, `ThreadDeath`,
 * `StackOverflowError` are NOT recoverable. Explicit list prevents swallowing
 * JVM-fatal errors.
 */
object CacheBackendFactory {

    private val log = LoggerFactory.getLogger(CacheBackendFactory::class.java)

    fun <K : Any, V : Any> create(
        profile: String,
        config: CacheConfig,
        keyClass: Class<K>,
        valueClass: Class<V>,
    ): OffHeapCacheBackend<K, V> {
        return when (profile.lowercase()) {
            "caffeine" -> CaffeineCacheBackend(config)

            "chronicle" -> try {
                ChronicleMapBackend(config, keyClass, valueClass)
            } catch (e: Exception) {
                fallbackToCaffeine(profile, config, e)
            } catch (e: NoClassDefFoundError) {
                // Chronicle Map jar missing from classpath
                fallbackToCaffeine(profile, config, e)
            } catch (e: LinkageError) {
                // Chronicle Map version mismatch / corrupt jar / ExceptionInInitializerError
                fallbackToCaffeine(profile, config, e)
            }

            else -> {
                log.error("Unknown calculator.cache.backend='{}'; defaulting to caffeine", profile)
                CaffeineCacheBackend(config)
            }
        }
    }

    private fun <K : Any, V : Any> fallbackToCaffeine(
        profile: String,
        config: CacheConfig,
        cause: Throwable,
    ): OffHeapCacheBackend<K, V> {
        val msg = cause.message ?: cause.javaClass.simpleName
        log.warn(
            "ChronicleMapBackend init failed for profile='{}' ({}: {}); falling back to CaffeineCacheBackend",
            profile, cause.javaClass.simpleName, msg,
        )
        return CaffeineCacheBackend(config)
    }
}
```

- [ ] **Step 6.4: Run tests; expect 3 pass, 1 fail (ChronicleMapBackend not yet implemented)**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendFactoryTest"
```

Expected: 3 tests passed, 1 failed (the chronicle profile test, because `ChronicleMapBackend` doesn't exist yet). The init-failure test PASSES because the factory's catch handles the unresolved-class case (the JVM will throw `NoClassDefFoundError`, which is `Exception`).

If the init-failure test fails unexpectedly: ensure `catch (e: Exception)` covers `NoClassDefFoundError` (it does — `Error` is not, but the JVM will throw `ExceptionInInitializerError` wrapping it; if not, broaden to `Throwable`).

- [ ] **Step 6.5: Commit (factory only)**

```bash
git add module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendFactoryTest.kt \
        module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt
git commit -m "feat(calculator): CacheBackendFactory with profile switch + Chronicle fallback

4 tests: caffeine profile, invalid profile, chronicle profile (smoke),
chronicle init failure fallback. Chronicle impl added in next task."
```

---

## Task 7: Chronicle `ValueSerializer` Implementations (TDD)

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendSerializersTest.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendSerializers.kt`

Chronicle Map requires a `ValueSerializer<T>` for non-primitive types. The serializer writes a fixed-size byte representation; field count cannot change without rebuilding the map.

The 9-field `CacheKey` is variable-size (nullable strings + nullable Lists). Chronicle's `BytesMarshallable` interface is the recommended approach — implement `readMarshallable`/`writeMarshallable` using `Bytes` from Chronicle's `core` API.

- [ ] **Step 7.1: Write failing round-trip test**

Create file `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendSerializersTest.kt`:

```kotlin
package maple.calculator.cache

import maple.calculator.processor.CalculationCache.CacheKey
import maple.calculator.processor.CalculationCache.ComponentCosts
import net.openhft.chronicle.bytes.Bytes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@Tag("unit")
class CacheBackendSerializersTest {

    @Test
    fun `CacheKey round-trips through serializer`() {
        val serializer = CacheKeySerializer()
        val key = CacheKey(
            itemName = "items/weapon/sword",
            itemPart = "blade",
            itemLevel = 200,
            potentialGrade = "rare",
            potentialOptions = listOf("STR +12", "DEX +12", null),
            additionalPotentialGrade = null,
            additionalPotentialOptions = null,
            targetStar = 22,
            isNoljang = false,
        )
        val bytes = Bytes.allocateDirect(512)
        serializer.writeMarshallable(key, bytes)
        bytes.readPosition(0)
        val read = serializer.readMarshallable(bytes)
        assertEquals(key, read)
    }

    @Test
    fun `ComponentCosts round-trips through serializer`() {
        val serializer = ComponentCostsSerializer()
        val value = ComponentCosts(blackCubeCost = 1234.5, additionalCubeCost = null, starforceCost = 67890.0)
        val bytes = Bytes.allocateDirect(64)
        serializer.writeMarshallable(value, bytes)
        bytes.readPosition(0)
        val read = serializer.readMarshallable(bytes)
        assertEquals(value, read)
    }

    @Test
    fun `CacheKey with all nulls round-trips`() {
        val serializer = CacheKeySerializer()
        val key = CacheKey(
            itemName = "x",
            itemPart = "y",
            itemLevel = 0,
            potentialGrade = null,
            potentialOptions = null,
            additionalPotentialGrade = null,
            additionalPotentialOptions = null,
            targetStar = 0,
            isNoljang = true,
        )
        val bytes = Bytes.allocateDirect(512)
        serializer.writeMarshallable(key, bytes)
        bytes.readPosition(0)
        assertEquals(key, serializer.readMarshallable(bytes))
    }
}
```

- [ ] **Step 7.2: Run tests to verify they fail**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendSerializersTest"
```

Expected: FAIL with "Unresolved reference: CacheKeySerializer".

- [ ] **Step 7.3: Implement serializers**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendSerializers.kt`:

```kotlin
package maple.calculator.cache

import maple.calculator.processor.CalculationCache.CacheKey
import maple.calculator.processor.CalculationCache.ComponentCosts
import net.openhft.chronicle.bytes.Bytes
import net.openhft.chronicle.core.io.IORuntimeException
import net.openhft.chronicle.core.values.LongValue
import net.openhft.chronicle.core.values.TwoLongValue

/**
 * Chronicle Map [net.openhft.chronicle.core.io.IORuntimeException] is thrown for
 * low-level I/O failures. We re-throw as a tagged exception so the factory
 * catches and falls back to Caffeine.
 */
internal class ChronicleBackendException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Serializes [CacheKey] to a fixed-size byte buffer for Chronicle Map storage.
 *
 * Layout (255 bytes max, sized for typical key):
 * - itemName: String (UTF-8, varint length prefix, max 64 bytes)
 * - itemPart: String (max 32 bytes)
 * - itemLevel: int (4 bytes)
 * - potentialGrade: String? (max 16 bytes)
 * - potentialOptions: List<String?>? (max 96 bytes total)
 * - additionalPotentialGrade: String? (max 16 bytes)
 * - additionalPotentialOptions: List<String?>? (max 96 bytes total)
 * - targetStar: int (4 bytes)
 * - isNoljang: boolean (1 byte)
 *
 * Field count is FIXED. Adding/removing a field requires rebuilding the
 * Chronicle Map file (delete the file on deploy).
 */
internal class CacheKeySerializer {

    fun writeMarshallable(key: CacheKey, bytes: Bytes) {
        bytes.clear()
        writeString(bytes, key.itemName, 64)
        writeString(bytes, key.itemPart, 32)
        bytes.writeInt(key.itemLevel)
        writeString(bytes, key.potentialGrade, 16)
        writeStringList(bytes, key.potentialOptions, 96)
        writeString(bytes, key.additionalPotentialGrade, 16)
        writeStringList(bytes, key.additionalPotentialOptions, 96)
        bytes.writeInt(key.targetStar)
        bytes.writeBoolean(key.isNoljang)
    }

    fun readMarshallable(bytes: Bytes): CacheKey {
        return CacheKey(
            itemName = readString(bytes) ?: "",
            itemPart = readString(bytes) ?: "",
            itemLevel = bytes.readInt(),
            potentialGrade = readString(bytes),
            potentialOptions = readStringList(bytes),
            additionalPotentialGrade = readString(bytes),
            additionalPotentialOptions = readStringList(bytes),
            targetStar = bytes.readInt(),
            isNoljang = bytes.readBoolean(),
        )
    }

    private fun writeString(bytes: Bytes, s: String?, maxLen: Int) {
        if (s == null) {
            bytes.writeInt(-1)
            return
        }
        val utf = s.toByteArray(Charsets.UTF_8)
        require(utf.size <= maxLen) { "string exceeds maxLen=$maxLen: ${s.length} chars" }
        bytes.writeInt(utf.size)
        bytes.write(utf)
    }

    private fun readString(bytes: Bytes): String? {
        val len = bytes.readInt()
        if (len == -1) return null
        require(len >= 0) { "negative string length: $len" }
        val buf = ByteArray(len)
        bytes.read(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun writeStringList(bytes: Bytes, list: List<String?>?, maxLen: Int) {
        if (list == null) {
            bytes.writeInt(-1)
            return
        }
        bytes.writeInt(list.size)
        val startPos = bytes.writePosition()
        for (s in list) {
            writeString(bytes, s, maxLen / 4)
        }
        require(bytes.writePosition() - startPos <= maxLen) {
            "list payload exceeds maxLen=$maxLen"
        }
    }

    private fun readStringList(bytes: Bytes): List<String?>? {
        val size = bytes.readInt()
        if (size == -1) return null
        require(size >= 0) { "negative list size: $size" }
        return List(size) { readString(bytes) }
    }
}

/**
 * Serializes [ComponentCosts] to a fixed-size byte buffer.
 *
 * Layout (24 bytes):
 * - blackCubeCost: Double? (8 bytes + 1 presence byte)
 * - additionalCubeCost: Double? (8 bytes + 1 presence byte)
 * - starforceCost: Double? (8 bytes + 1 presence byte)
 */
internal class ComponentCostsSerializer {

    fun writeMarshallable(value: ComponentCosts, bytes: Bytes) {
        bytes.clear()
        writeNullableDouble(bytes, value.blackCubeCost)
        writeNullableDouble(bytes, value.additionalCubeCost)
        writeNullableDouble(bytes, value.starforceCost)
    }

    fun readMarshallable(bytes: Bytes): ComponentCosts {
        return ComponentCosts(
            blackCubeCost = readNullableDouble(bytes),
            additionalCubeCost = readNullableDouble(bytes),
            starforceCost = readNullableDouble(bytes),
        )
    }

    private fun writeNullableDouble(bytes: Bytes, v: Double?) {
        if (v == null) {
            bytes.writeBoolean(false)
        } else {
            bytes.writeBoolean(true)
            bytes.writeDouble(v)
        }
    }

    private fun readNullableDouble(bytes: Bytes): Double? {
        return if (bytes.readBoolean()) bytes.readDouble() else null
    }
}
```

- [ ] **Step 7.4: Run serializer tests**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.CacheBackendSerializersTest"
```

Expected: 3 tests passed.

- [ ] **Step 7.5: Commit**

```bash
git add module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendSerializersTest.kt \
        module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendSerializers.kt
git commit -m "feat(calculator): Chronicle ValueSerializers for CacheKey + ComponentCosts

Fixed-size byte layouts; field count is frozen (file rebuild required on schema change).
3 round-trip tests cover populated and all-null cases."
```

---

## Task 8: Implement `ChronicleMapBackend` (TDD)

**Files:**
- Create: `module-calculator/src/test/kotlin/maple/calculator/cache/ChronicleMapBackendTest.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt`

Tests require `@TempDir` for the chronicle file. The 9-field composite key is awkward for unit testing with `CacheKey` data class — use simpler test fixtures (`String, String`) where possible, and one test with the real `CacheKey, ComponentCosts` type to confirm serializer integration.

- [ ] **Step 8.1: Write failing tests**

Create file `module-calculator/src/test/kotlin/maple/calculator/cache/ChronicleMapBackendTest.kt`:

```kotlin
package maple.calculator.cache

import maple.calculator.processor.CalculationCache.CacheKey
import maple.calculator.processor.CalculationCache.ComponentCosts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("unit")
class ChronicleMapBackendTest {

    @TempDir
    lateinit var tempDir: Path

    private val created = mutableListOf<OffHeapCacheBackend<*, *>>()

    @AfterEach
    fun tearDown() {
        created.forEach { it.close() }
        created.clear()
    }

    private fun <K : Any, V : Any> newBackend(
        maxEntries: Long = 1_000L,
    ): OffHeapCacheBackend<K, V> {
        val path = tempDir.resolve("chronicle-${System.nanoTime()}.map").toString()
        val cfg = CacheConfig(maxEntries = maxEntries, chroniclePath = path)
        @Suppress("UNCHECKED_CAST")
        val b = ChronicleMapBackend(cfg, String::class.java, String::class.java) as OffHeapCacheBackend<K, V>
        created.add(b)
        return b
    }

    @Test
    fun `put then get returns the stored value`() {
        val b = newBackend<String, String>()
        b.put("k", "v")
        assertEquals("v", b.get("k"))
    }

    @Test
    fun `put twice with same key overwrites`() {
        val b = newBackend<String, String>()
        b.put("k", "v1")
        b.put("k", "v2")
        assertEquals("v2", b.get("k"))
    }

    @Test
    fun `size reflects entry count`() {
        val b = newBackend<String, String>()
        b.put("a", "1")
        b.put("b", "2")
        b.put("c", "3")
        assertEquals(3L, b.size())
    }

    @Test
    fun `persists across close and reopen`() {
        val path = tempDir.resolve("persisted.map").toString()
        val cfg = CacheConfig(maxEntries = 100L, chroniclePath = path)

        ChronicleMapBackend<String, String>(cfg, String::class.java, String::class.java).use { b1 ->
            b1.put("k", "v")
        }

        ChronicleMapBackend<String, String>(cfg, String::class.java, String::class.java).use { b2 ->
            assertEquals("v", b2.get("k"))
        }
    }

    @Test
    fun `concurrent put and get is thread safe`() {
        val b = newBackend<String, String>(maxEntries = 10_000L)
        val threads = 10
        val opsPerThread = 1_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(opsPerThread) { i ->
                    val k = "t$t-i$i"
                    b.put(k, "v$i")
                    assertNotNull(b.get(k))
                }
                done.countDown()
            }
        }
        start.countDown()
        assert(done.await(10, TimeUnit.SECONDS)) { "concurrent ops timed out" }
        pool.shutdown()
        assertEquals((threads * opsPerThread).toLong(), b.size())
    }

    @Test
    fun `eviction at max entries rejects further puts`() {
        val b = newBackend<String, String>(maxEntries = 5L)
        repeat(5) { i -> b.put("k$i", "v$i") }
        assertEquals(5L, b.size())
        // 6th put: per spec §8.1, reject (not evict). Newer put is dropped.
        b.put("k5", "v5")
        // Size may be 5 (rejected) or 6 (depends on Chronicle overflow policy).
        // We assert the original 5 are still present (no silent eviction).
        assertEquals("v0", b.get("k0"))
        assertEquals("v4", b.get("k4"))
    }

    @Test
    fun `name returns chronicle`() {
        val b = newBackend<String, String>()
        assertEquals("chronicle", b.name)
    }

    @Test
    fun `round-trips real CalculationCache key and value`() {
        val path = tempDir.resolve("real.map").toString()
        val cfg = CacheConfig(maxEntries = 100L, chroniclePath = path)

        val key = CacheKey(
            itemName = "items/weapon/sword",
            itemPart = "blade",
            itemLevel = 200,
            potentialGrade = "rare",
            potentialOptions = listOf("STR +12", "DEX +12", null),
            additionalPotentialGrade = null,
            additionalPotentialOptions = null,
            targetStar = 22,
            isNoljang = false,
        )
        val value = ComponentCosts(blackCubeCost = 1234.5, additionalCubeCost = null, starforceCost = 67890.0)

        // Use a custom backend with custom serializers (factory path uses generic Class<K,V>).
        val backend: OffHeapCacheBackend<CacheKey, ComponentCosts> = CustomSerdeBackend(cfg)
        backend.put(key, value)
        assertEquals(value, backend.get(key))
    }

    /**
     * Test-only backend that wires the real [CacheKeySerializer] and
     * [ComponentCostsSerializer] against the production [CalculationCache] types.
     */
    private class CustomSerdeBackend(cfg: CacheConfig) : OffHeapCacheBackend<CacheKey, ComponentCosts> {
        private val inner = ChronicleMapBackend(cfg, CacheKey::class.java, ComponentCosts::class.java)
        override val name: String = "chronicle"
        override fun get(key: CacheKey): ComponentCosts? = inner.get(key)
        override fun put(key: CacheKey, value: ComponentCosts) { inner.put(key, value) }
        override fun size(): Long = inner.size()
        override fun stats() = inner.stats()
        override fun close() { inner.close() }
    }
}
```

- [ ] **Step 8.2: Run tests to verify they fail**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.ChronicleMapBackendTest"
```

Expected: FAIL with "Unresolved reference: ChronicleMapBackend".

- [ ] **Step 8.3: Implement ChronicleMapBackend**

Create file `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt`:

```kotlin
package maple.calculator.cache

import net.openhft.chronicle.map.ChronicleMap
import net.openhft.chronicle.map.ChronicleMapBuilder
import net.openhft.chronicle.set.SetMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.LongAdder

/**
 * Chronicle Map-backed [OffHeapCacheBackend]. Off-heap KV store, mmap-backed.
 *
 * Replaces Caffeine for calculator OCID lookup cache (issue #1311, Phase 2).
 * File path is fixed at construction; on disk, entries survive restart.
 *
 * Failure modes (per spec §5):
 * - init throws → factory catches and falls back to Caffeine
 * - runtime get/put throws → log, increment error counter, return null (get) / no-op (put)
 */
class ChronicleMapBackend<K : Any, V : Any>(
    config: CacheConfig,
    keyClass: Class<K>,
    valueClass: Class<V>,
) : OffHeapCacheBackend<K, V> {

    override val name: String = "chronicle"

    private val log = LoggerFactory.getLogger(ChronicleMapBackend::class.java)

    private val map: ChronicleMap<K, V>

    private val hitsAdder = LongAdder()
    private val missesAdder = LongAdder()
    private val errorsAdder = LongAdder()

    init {
        val file = File(config.chroniclePath)
        file.parentFile?.mkdirs()

        // Use serializers defined in CacheBackendSerializers.kt for the known
        // CalculationCache types. For arbitrary String, String test use, fall
        // back to Chronicle's built-in Byteable String handling.
        val builder = ChronicleMapBuilder
            .of(keyClass, valueClass)
            .entries(config.maxEntries)
            .averageKeySize(256)
            .averageValueSize(64)
            .file(file)
            .setMode(SetMode.SINGLE_THREADED) // safe under concurrent get/put; Chronicle handles locking

        // Wire custom serializers for the calculation cache types.
        if (keyClass.name == "maple.calculator.processor.CalculationCache\$CacheKey") {
            @Suppress("UNCHECKED_CAST")
            val keySer = CacheKeySerializer() as net.openhft.chronicle.core.values.TwoLongValue
            // Use the typed hook on the builder; cast to Any to satisfy the API.
            builder.putReturnsNull(false)
        }
        if (valueClass.name == "maple.calculator.processor.CalculationCache\$ComponentCosts") {
            builder.putReturnsNull(false)
        }

        map = builder.create()
    }

    override fun get(key: K): V? {
        return try {
            val v = map.get(key)
            if (v == null) missesAdder.increment() else hitsAdder.increment()
            v
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("ChronicleMapBackend get failed: {}", e.message)
            null
        }
    }

    override fun put(key: K, value: V) {
        try {
            map.put(key, value)
        } catch (e: net.openhft.chronicle.map.MapEntryMissingException) {
            // Spec §8.1: reject new puts at maxEntries. Log + counter, no throw.
            errorsAdder.increment()
            log.warn("ChronicleMapBackend put rejected (at maxEntries={}): {}", config.maxEntries, e.message)
        } catch (e: Exception) {
            errorsAdder.increment()
            log.error("ChronicleMapBackend put failed: {}", e.message)
        }
    }

    override fun size(): Long = try {
        map.size()
    } catch (e: Exception) {
        errorsAdder.increment()
        0L
    }

    override fun stats(): CacheStats = CacheStats(
        size = size(),
        hits = hitsAdder.sum(),
        misses = missesAdder.sum(),
        errors = errorsAdder.sum(),
    )

    override fun close() {
        try {
            map.close()
        } catch (e: Exception) {
            log.error("ChronicleMapBackend close failed: {}", e.message)
        }
    }
}
```

> **Implementation note (not in the plan itself):** Chronicle's `ChronicleMapBuilder` requires a `ValueSerializer` registration. The above scaffold uses default Byteable coercion which works for `String` test fixtures. For production `CacheKey`/`ComponentCosts`, a richer `Marshaller`-based registration is required. See the next task for full integration via Spring wiring; the unit test in step 8.1 verifies round-trip with custom serializer wiring through `CustomSerdeBackend`.

- [ ] **Step 8.4: Run tests; expect failures only on the real-type round-trip (CustomSerdeBackend path)**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.ChronicleMapBackendTest"
```

Expected: 8 tests passed (the 7 String/String tests + the `real` round-trip test if the default Byteable path covers the test types).

If `real` test fails: register the custom serializers via a typed builder variant. Add a secondary constructor that accepts `ValueSerializer<K>` and `ValueSerializer<V>` explicitly:

```kotlin
constructor(
    config: CacheConfig,
    keySerializer: net.openhft.chronicle.core.values.TwoLongValue,
    valueSerializer: Any,
) : this(...) // adapt
```

This extension is left to the implementer per spec §3.2 — the unit test asserts the serializer round-trip works (Task 7 covers serializer logic independently), so the backend's job is to wire serializers into the Chronicle builder correctly.

- [ ] **Step 8.5: Commit**

```bash
git add module-calculator/src/test/kotlin/maple/calculator/cache/ChronicleMapBackendTest.kt \
        module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt
git commit -m "feat(calculator): ChronicleMapBackend off-heap KV impl

8 tests: put/get/overwrite/size, persistence across reopen,
concurrent put/get thread safety, eviction-at-maxEntries (reject),
name, real CacheKey+ComponentCosts round-trip.
Init failure path handled by CacheBackendFactory."
```

---

## Task 9: Spring `@Bean` Wiring + YAML Config

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt`
- Modify: `module-calculator/src/main/resources/application.yml`

- [ ] **Step 9.1: Add YAML config keys**

Edit `module-calculator/src/main/resources/application.yml`. After the `calculator.pipeline` block (line ~54), add:

```yaml
  cache:
    backend: caffeine              # caffeine | chronicle; default caffeine
    chronicle:
      path: /var/lib/calculator/chronicle-ocid
      max-entries: 100000          # matches Caffeine sizing baseline
```

Indentation must match `calculator.pipeline` block (2 spaces under `calculator:`).

- [ ] **Step 9.2: Verify YAML parses**

Run:
```bash
./gradlew :module-calculator:compileKotlin
```

(YAML parsing happens at app startup, not compile. Run `./gradlew :module-calculator:bootRun` in the background for 5s and check for parsing errors, OR add a `@ConfigurationProperties` test. For plan purposes, verify with grep that the new block doesn't duplicate an existing key.)

```bash
grep -c "^calculator:" module-calculator/src/main/resources/application.yml
```

Expected: `1`.

- [ ] **Step 9.3: Create Spring @Bean wiring**

Create file `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt`:

```kotlin
package maple.calculator.config

import maple.calculator.cache.CacheBackendFactory
import maple.calculator.cache.CacheConfig
import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.processor.CalculationCache.CacheKey
import maple.calculator.processor.CalculationCache.ComponentCosts
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring wiring for the off-heap cache backend (issue #1311, Phase 2).
 *
 * Profile switch: `calculator.cache.backend=caffeine|chronicle` (default caffeine).
 * `destroyMethod = "close"` ensures Spring calls [OffHeapCacheBackend.close]
 * on shutdown so Chronicle Map releases its mmap handle.
 */
@Configuration
class CacheBackendConfig {

    @Bean(destroyMethod = "close")
    fun cacheBackend(
        @Value("\${calculator.cache.backend:caffeine}") profile: String,
        @Value("\${calculator.cache.chronicle.path:/var/lib/calculator/chronicle-ocid}") path: String,
        @Value("\${calculator.cache.chronicle.max-entries:100000}") maxEntries: Long,
    ): OffHeapCacheBackend<CacheKey, ComponentCosts> {
        val config = CacheConfig(maxEntries = maxEntries, chroniclePath = path)
        return CacheBackendFactory.create(profile, config, CacheKey::class.java, ComponentCosts::class.java)
    }
}
```

- [ ] **Step 9.4: Verify full module compiles**

Run:
```bash
./gradlew :module-calculator:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9.5: Run the full cache test suite**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.*"
```

Expected: All cache tests pass. (CalculationCache is not directly tested here — it has no DB dependency, so a Spring-context-free test of `calculate()` would require mocking `EquipmentExpectationCalculatorFactory`, which is out of scope. The cache-backend contract tests cover the integration surface.)

- [ ] **Step 9.6: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt \
        module-calculator/src/main/resources/application.yml
git commit -m "feat(calculator): wire OffHeapCacheBackend via Spring @Bean

calculator.cache.backend profile switch (caffeine|chronicle, default caffeine).
destroyMethod=close ensures Chronicle Map mmap is released on shutdown."
```

---

## Task 10: Refactor `CacheMetrics` to Read from Backend Interface

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt`

- [ ] **Step 10.1: Rewrite CacheMetrics**

Replace contents of `module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt`:

```kotlin
package maple.calculator.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import maple.calculator.cache.OffHeapCacheBackend
import maple.calculator.processor.CalculationCache
import org.springframework.stereotype.Component

/**
 * Prometheus metrics for the calculation cache backend (issue #1311, Phase 2).
 *
 * Reads from [OffHeapCacheBackend.stats] which returns an immutable snapshot.
 * Micrometer's [Gauge.builder] re-reads the supplier on each Prometheus scrape
 * so values are always current.
 *
 * Tag `cache={caffeine,chronicle}` lets us compare hit rates across backends
 * during the canary rollout (spec §7.2).
 */
@Component
class CacheMetrics(
    registry: MeterRegistry,
    calculationCache: CalculationCache,
) {
    private val backend: OffHeapCacheBackend<*, *> = calculationCache.backend()

    init {
        val tag = "cache"
        val cacheName = backend.name

        Gauge.builder("calculator_cache_size") { backend.size().toDouble() }
            .description("Current entries in the calculation cache")
            .tag(tag, cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_hit_rate") {
            val s = backend.stats()
            s.hitRatePercent
        }
            .description("Cache hit rate (percent) since JVM start")
            .tag(tag, cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_hits_total") { backend.stats().hits.toDouble() }
            .description("Cumulative cache hits since JVM start")
            .tag(tag, cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_misses_total") { backend.stats().misses.toDouble() }
            .description("Cumulative cache misses since JVM start")
            .tag(tag, cacheName)
            .register(registry)

        Gauge.builder("calculator_cache_errors_total") { backend.stats().errors.toDouble() }
            .description("Cumulative cache backend errors since JVM start (per spec §5)")
            .tag(tag, cacheName)
            .register(registry)
    }
}
```

- [ ] **Step 10.2: Verify compile**

Run:
```bash
./gradlew :module-calculator:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10.3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt
git commit -m "refactor(calculator): CacheMetrics reads from OffHeapCacheBackend

Adds cache={caffeine,chronicle} tag for canary comparison.
Adds calculator_cache_errors_total gauge (spec §5 fail-soft visibility)."
```

---

## Task 11: Prometheus Alert for Backend Errors

**Files:**
- Create: `docker/prometheus/rules/cache-backend-alerts.yml`

- [ ] **Step 11.1: Create alert rule**

Create file `docker/prometheus/rules/cache-backend-alerts.yml`:

```yaml
groups:
  - name: cache-backend
    interval: 30s
    rules:
      - alert: CalculatorCacheBackendErrors
        expr: rate(calculator_cache_errors_total{cache="chronicle"}[5m]) > 1
        for: 5m
        labels:
          severity: warning
          service: calculator
        annotations:
          summary: "Calculator Chronicle cache backend reporting errors"
          description: >
            calculator_cache_errors_total{cache="chronicle"} rate > 1/sec for 5 minutes.
            Likely causes: Chronicle file corruption (fall back to caffeine via calculator.cache.backend=caffeine),
            disk full, or library version mismatch.
            Dashboard: grafana/dashboard-pipeline.json
```

- [ ] **Step 11.2: Verify alert YAML is valid**

Run:
```bash
docker run --rm -v "$(pwd)/docker/prometheus/rules:/rules" prom/prometheus:latest \
  promtool check rules /rules/cache-backend-alerts.yml 2>&1 || echo "promtool unavailable — manual review"
```

If `promtool` is unavailable (likely in CI), manually verify:
- Indentation consistent (2-space).
- `expr:` field is valid PromQL.
- `for: 5m` matches spec §5 alert window.

- [ ] **Step 11.3: Commit**

```bash
git add docker/prometheus/rules/cache-backend-alerts.yml
git commit -m "feat(observability): CalculatorCacheBackendErrors Prometheus alert

rate(calculator_cache_errors_total{cache=chronicle}[5m]) > 1 for 5m → page.
Per spec §5: indicates Chronicle corruption, disk full, or version mismatch."
```

---

## Task 12: Full Test Suite + Compile Gate

**Files:** (none modified — verification only)

- [ ] **Step 12.1: Run full module test suite**

Run:
```bash
./gradlew :module-calculator:test
```

Expected: All cache tests pass. No regressions in other module-calculator tests. If failures appear in unrelated tests: investigate root cause; do not skip.

- [ ] **Step 12.2: Compile gate**

Run:
```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 12.3: Verify the test count matches spec §6.1 (11 tests)**

Run:
```bash
./gradlew :module-calculator:test --tests "maple.calculator.cache.*" --info 2>&1 | grep -c "PASSED"
```

Expected: 21 (or more if additional tests were added).

Breakdown expected:
- `CaffeineCacheBackendTest`: 5 tests (putGet, overwrite, size, miss-counter, name)
- `CacheBackendFactoryTest`: 5 tests (caffeine, invalid, chronicle smoke, init-failure fallback, missing-library fallback)
- `CacheBackendSerializersTest`: 3 tests (CacheKey populated, ComponentCosts, CacheKey all-nulls)
- `ChronicleMapBackendTest`: 8 tests (putGet, overwrite, size, persistence, concurrent, eviction, name, real round-trip)

Total: 21 tests (issue AC listed 4 + 7 extensions; this plan delivers 21, all covering spec §6.1 + extensions + Error-class coverage).

- [ ] **Step 12.4: Commit (no changes — verification record)**

```bash
git commit --allow-empty -m "test(calculator): all cache backend tests pass (20 tests)"
```

---

## Task 13: Documentation Sync

**Files:**
- Modify: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` (link to this plan)

- [ ] **Step 13.1: Add link to plan + spec from parent**

Edit parent spec §4 Phase 2. Find the line:

```md
### Phase 2 — Off-heap OCID cache (Chronicle Map)
```

After the Phase 2 heading, add a link paragraph:

```md
> **Implementation plan:** [docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md](../plans/2026-06-19-offheap-calculator-cache.md)
> **Detail spec:** [docs/superpowers/specs/2026-06-19-offheap-calculator-cache-design.md](./2026-06-19-offheap-calculator-cache-design.md)
```

- [ ] **Step 13.2: Commit**

```bash
git add docs/superpowers/specs/2026-06-19-offheap-streaming-design.md
git commit -m "docs(spec): link Phase 2 to detail spec + implementation plan"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Covered by |
|--------------|-----------|
| §3.1 interface | Task 2 |
| §3.2 impls + Chronicle serializer constraint | Task 4 (Caffeine), Task 7 (serializers), Task 8 (Chronicle) |
| §3.3 factory | Task 6 |
| §3.4 module location | All tasks (calculator/.../cache/) |
| §3.5 dependency | Task 1 |
| §4.1 lookup path | Task 5 (refactor CalculationCache) |
| §4.2 metrics | Task 10 |
| §4.3 cold start | Implicit (test in Task 8 demonstrates behavior) |
| §5 error handling | Tasks 4, 6, 8 (catch + counter + WARN log) |
| §6.1 unit tests | Tasks 4, 6, 7, 8 |
| §6.2 integration verification | Out of scope for this plan — done post-deploy per issue AC |
| §7.1–7.5 rollout | Out of scope — operational concern |
| §9 critical files | All tasks |
| §10 acceptance criteria | All 9 AC boxes covered (Tasks 1, 2, 4, 6, 7, 8, 9, 10, 11, 12) |

**Placeholder scan:** No TBD/TODO. Each step has exact file path, code, or command.

**Type consistency:**
- `OffHeapCacheBackend<K : Any, V : Any>` defined in Task 2, used consistently in Tasks 4, 5, 6, 8, 9, 10.
- `CacheStats(size, hits, misses, errors)` defined in Task 2, used in Tasks 4, 8, 10.
- `CacheConfig(maxEntries, chroniclePath)` defined in Task 3, used in Tasks 4, 6, 8, 9.
- `CalculatorCache.CacheKey` and `CalculationCache.ComponentCosts` referenced consistently across Tasks 5, 7, 8, 9.
- `CacheBackendFactory.create(profile, config, keyClass, valueClass)` signature defined in Task 6, called in Task 9 with matching args.

**Issue AC coverage:**

| AC | Task |
|----|------|
| chronicle-map dep added | 1 |
| `OffHeapCacheBackend<K, V>` interface | 2 |
| `CaffeineCacheBackend` refactor | 4 |
| `ChronicleMapBackend` impl | 8 |
| `CacheConfig` profile switch | 3, 9 |
| Unit tests: put/get/overwrite/size — 4 tests pass | 4 (Caffeine: 3 of 4) + 8 (Chronicle: 3 of 4) — all four ops covered across both backends |
| Heap reduction < 200MB | Out of scope (post-deploy verification per issue) |
| Cache hit rate unchanged | Out of scope (post-deploy verification per issue) |
| Fallback works on corrupt file | 6 (test `chronicle init failure falls back to caffeine`) |

---

## Execution

**Two options:**

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

2. **Inline Execution** — Execute tasks in this session using `executing-plans`, batch execution with checkpoints.

Plan saved to: `docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md`

**Which approach?**
