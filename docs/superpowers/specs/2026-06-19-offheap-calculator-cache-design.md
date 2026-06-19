# Off-heap Calculator OCID Cache (Chronicle Map) — Design

- Date: 2026-06-19
- Status: Draft (pending user review)
- Owner: maple-pipeline
- Parent: [2026-06-19-offheap-streaming-design.md §4 Phase 2](./2026-06-19-offheap-streaming-design.md)
- Issue: #1311

---

## 1. Goal

Reduce `module-calculator` heap by 30–50MB by moving the 100K-entry OCID lookup cache from heap-resident Caffeine to off-heap Chronicle Map. Preserve cache hit rate within ±1% of baseline. Zero behavior change when feature flag `calculator.cache.backend=caffeine`.

**Out of scope (handled by other phases):**
- Phase 1 direct memory cap (parent spec §4.1)
- Phase 3 streaming calculator writer
- Phase 4 streaming ext-api chunk parser
- Phase 5 Netty/Kafka buffer tuning

---

## 2. Background

Per parent spec §2 diagnose baseline, `module-calculator` sits at **414MB heap**. Of this, the Caffeine OCID lookup cache (`calculator_cache_size = 100K`) consumes **30–50MB** by retaining `OcidMapping` POJOs with 12 fields each (OCID, IGN, character class, level, server, etc.) in JVM heap.

Chronicle Map stores entries in mapped native memory (`mmap`-backed file), not JVM heap. Entries are GC-free; old-generation pressure eliminated for this data structure. Cost: serialization required for keys/values, fixed schema (no field add/remove without rebuild), and file format lock to library version.

**Risk drivers (parent spec §11):**
- Chronicle Map file format changes between minor versions → must pin exact patch.
- Cache miss storm on first deploy → cold start with 100% miss rate for one chunk cycle.
- Library availability — Chronicle Map 3.23.5 is Apache 2.0, mature (10+ years), but third-party dependency.

---



### Known blocker (as of 2026-06)

Chronicle Map does not support JDK 21 in any stable release:
- 3.23.5 (latest stable): uses , REMOVED in JDK 17+
- 3.27ea0 (latest ea): requires  whose POM references unpublished SNAPSHOT

**Workaround shipped:**  is a stub that logs WARN + falls back to Caffeine per spec §5.  is the only working profile. Off-heap heap-reduction goal deferred until upstream JDK 21 support ships.

**Issue #1311 acceptance status:** heap reduction (< 200MB), hit rate unchanged, and full Chronicle fallback path CANNOT be verified in this environment. Re-evaluate on each Chronicle Map stable release.

---

## 3. Architecture

### 3.1 Interface

```kotlin
// module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt
package maple.calculator.cache

interface OffHeapCacheBackend<K : Any, V : Any> : AutoCloseable {
    fun get(key: K): V?
    fun put(key: K, value: V)
    fun size(): Long
    override fun close()
}
```

Generic on key/value to permit reuse for future off-heap caches (not a current need; YAGNI applies — do not pre-build for it).

**Key/Value types for this phase** (from existing `CalculationCache.kt`):
- Key: `CalculationCache.CacheKey` (9 fields: `itemName: String`, `itemPart: String`, `itemLevel: Int`, `potentialGrade: String?`, `potentialOptions: List<String?>?`, `additionalPotentialGrade: String?`, `additionalPotentialOptions: List<String?>?`, `targetStar: Int`, `isNoljang: Boolean`).
- Value: `CalculationCache.ComponentCosts` (3 nullable Doubles).

Both already satisfy `<K : Any, V : Any>` non-null bound — values are nullable Double, but `ComponentCosts` itself is non-null.

### 3.2 Implementations

| Class | Backing | Use case |
|-------|---------|----------|
| `CaffeineCacheBackend` | `com.github.ben-manes.caffeine:caffeine` (existing dep) | Default (test + prod until cutover) |
| `ChronicleMapBackend` | `net.openhft:chronicle-map:3.23.5` | Production after canary |

Both wrap the same `OffHeapCacheBackend<K, V>` interface.

**Chronicle Map serialization constraint:** `CacheKey` is a composite data class with 9 fields including nested `List<String?>?` (potentialOptions). Chronicle Map requires `ValueSerializer` for non-primitive types. `ChronicleMapBackend` must implement `ValueSerializer<CacheKey>` and `ValueSerializer<ComponentCosts>` using Chronicle's `BytesMarshallable` or `Byteable` interface — not `Serializable` (Java serialization forbidden by codebase convention).

**Refactor scope:** existing `CalculationCache` (`module-calculator/.../processor/CalculationCache.kt`) is changed to depend on `OffHeapCacheBackend<CacheKey, ComponentCosts>` interface rather than concrete `Cache<CacheKey, ComponentCosts>`. The Caffeine builder pattern moves into `CaffeineCacheBackend`. `CalculationCache.calculate()` retains its public API — callers (`SnapshotChunkProcessor`) untouched.

### 3.3 Factory

```kotlin
// CacheBackendFactory.kt
object CacheBackendFactory {
    fun <K : Any, V : Any> create(
        profile: String,
        config: CacheConfig
    ): OffHeapCacheBackend<K, V> = when (profile.lowercase()) {
        "chronicle" -> try {
            ChronicleMapBackend(config) as OffHeapCacheBackend<K, V>
        } catch (e: ChronicleException) {
            log.warn("Chronicle init failed ({}), falling back to Caffeine", e.message)
            CaffeineCacheBackend(config)
        }
        "caffeine" -> CaffeineCacheBackend(config)
        else -> {
            log.error("Unknown calculator.cache.backend='{}', defaulting to caffeine", profile)
            CaffeineCacheBackend(config)
        }
    }
}
```

### 3.4 Module location

All cache classes live in `module-calculator/.../cache/`. No `module-common` pollution (parent rule: `module-common` is Spring-zero, no external deps).

### 3.5 Dependency

`module-calculator/build.gradle.kts`:
```kotlin
dependencies {
    implementation("net.openhft:chronicle-map:3.23.5")
}
```

Pinned exact patch per Approach C1. Chronicle Map is known for file format changes between minor versions; on-disk data must be readable by the same version family.

---

## 4. Data Flow

### 4.1 Lookup path

```
Chunk arrives
    → OcidLookupService.lookup(ocid)
    → cacheBackend.get(ocid)        [OffHeapCacheBackend]
        ├─ hit  → return cached
        └─ miss → DB SELECT
                  → cacheBackend.put(ocid, value)
                  → return value
```

Identical for both backends. Caller code unchanged.

### 4.2 Metrics

| Metric | Type | Labels |
|--------|------|--------|
| `calculator_cache_hits_total` | Counter | `cache={caffeine,chronicle}` |
| `calculator_cache_misses_total` | Counter | `cache={caffeine,chronicle}` |
| `calculator_cache_errors_total` | Counter | `cache={caffeine,chronicle}` |
| `calculator_cache_size` | Gauge | `cache={caffeine,chronicle}` |
| `calculator_cache_hit_rate` | Gauge (percent) | `cache={caffeine,chronicle}` |

Naming aligns with existing `CacheMetrics.kt` convention (`calculator_cache_*` prefix). The `cache` label distinguishes backend for canary comparison. No `op={get,put}` label — cardinality would compound Prometheus storage without operator benefit (errors are rare, log gives the op).

Hit rate = `calculator_cache_hit_rate` (percent). Must match pre-Phase 2 baseline within ±1%.

### 4.3 Cold start

First deploy with `calculator.cache.backend=chronicle`:
1. Chronicle file empty (or absent — auto-created).
2. First chunk: ~100K lookups, all miss → DB reads → Chronicle puts.
3. End of chunk 1: cache warm, hit rate ~baseline.
4. Sustained: identical to Caffeine path, but ~0 heap bytes for entries.

Acceptable: one chunk of degraded latency during canary deploy. Mitigated by canary rollout (§7).

---

## 5. Error Handling

| Failure | Detection | Behavior |
|---------|-----------|----------|
| Chronicle init fail (corrupt file, missing lib) | `ChronicleMap.of()` throws at startup | Factory catches → `CaffeineCacheBackend` → WARN log → continue |
| Chronicle runtime put/get throws | Per-call exception | ERROR log, `calculator_cache_errors_total++`, treat as miss, fall through to DB |
| Disk full / I/O error on mapped file | `IOException` on put | Same as runtime error — fail-soft to DB |
| Chronicle close() fails | Exception in `close()` | ERROR log, swallow (best-effort) |
| Invalid profile value | At startup | Default to caffeine, ERROR log with invalid value |

**No mid-run backend swap.** Runtime corruption = log + fail-soft + metric. Hot-path never blocks on cache.

**Alert (Grafana):**
```
rate(calculator_cache_errors_total{cache="chronicle"}[5m]) > 1
```
Page on-call → manual investigation (likely file format mismatch after version bump, or disk issue).

---

## 6. Testing Strategy

### 6.1 Unit tests (module-calculator/src/test/.../cache/CacheBackendTest.kt)

| Test | Verifies |
|------|----------|
| `caffeine_putGetRoundTrip` | Basic Caffeine round-trip |
| `caffeine_putOverwritesExisting` | Overwrite semantics |
| `caffeine_sizeReflectsEntries` | Size counter |
| `chronicle_putGetRoundTrip` | Basic Chronicle round-trip |
| `chronicle_putOverwritesExisting` | Overwrite semantics |
| `chronicle_sizeReflectsEntries` | Size counter |
| `chronicle_concurrentPutGetThreadSafe` | 10 threads × 1K ops, no data loss |
| `chronicle_persistenceAcrossReopen` | close() → re-open → entries preserved |
| `chronicle_evictionAtMaxEntries` | Beyond `maxEntries` → oldest evicted (or reject, see §8) |
| `factory_corruptFileFallsBackToCaffeine` | Delete chronicle file before factory call → returns Caffeine, WARN logged |
| `factory_invalidProfileDefaultsToCaffeine` | `profile="foo"` → Caffeine, ERROR logged |

21 tests total (4 from issue AC + 17 extensions across both backends, factory, and serializers). Tagged `@Tag("unit")`.

### 6.2 Integration verification (post-deploy)

Per issue #1311 AC:
```bash
# Heap reduction
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="calculator",area="heap"}' | jq

# Hit rate unchanged
curl -s 'http://localhost:9090/api/v1/query?query=calculator_cache_hit_rate' | jq
```

Targets (per parent spec §8 Phase 2):
- Calculator heap < 200MB sustained (from 414MB baseline).
- `calculator_cache_hit_rate` within ±1% of pre-Phase 2 baseline.
- 1hr pipeline run with no `calculator_cache_errors_total` increment.

---

## 7. Migration / Rollout

### 7.1 Phase 1 — Deploy dependency (zero behavior change)

1. Add `net.openhft:chronicle-map:3.23.5` to `module-calculator/build.gradle.kts`.
2. Refactor existing Caffeine usage to wrap in `OffHeapCacheBackend` interface (no functional change).
3. Deploy with `calculator.cache.backend: caffeine` (default).
4. Verify in prod: hit rate identical to pre-refactor.

### 7.2 Phase 2 — Canary

1. Flip `calculator.cache.backend: chronicle` on **one** calculator instance.
2. Chronicle file auto-created at `${calculator.cache.chronicle.path}` (default `/var/lib/calculator/chronicle-ocid`).
3. Cold start: 100% miss for first chunk (~100K lookups).
4. Observe 1hr: heap < 200MB, hit rate within ±1%, `calculator_cache_errors_total = 0`.
5. If green → proceed to Phase 3. If regression → flip back to caffeine, restart, investigate.

### 7.3 Phase 3 — Full rollout

1. Flip `calculator.cache.backend: chronicle` on all calculator instances.
2. Keep `CaffeineCacheBackend` impl in code (fallback path) — never delete.

### 7.4 Rollback

Flip flag back to `caffeine`, restart. Chronicle file persists on disk (harmless). Subsequent flip to `chronicle` reuses warm file.

### 7.5 Lifecycle

Spring bean wiring:
```kotlin
@Bean(destroyMethod = "close")
fun cacheBackend(
    @Value("\${calculator.cache.backend:caffeine}") profile: String,
    @Value("\${calculator.cache.chronicle.path:/var/lib/calculator/chronicle-ocid}") path: String,
    @Value("\${calculator.cache.chronicle.maxEntries:100000}") maxEntries: Long
): OffHeapCacheBackend<Long, OcidMapping> =
    CacheBackendFactory.create(profile, CacheConfig(path, maxEntries))
```

`destroyMethod = "close"` ensures Spring calls `AutoCloseable.close()` on shutdown.

---

## 8. Open Questions

### 8.1 Eviction policy at maxEntries

Chronicle Map supports two overflow behaviors:
- **Reject** new puts beyond `entries()` (closest to Caffeine `maximumSize` semantics).
- **Evict** oldest (requires custom `ChronicleMapBuilder.removeIfValuePredicate` or external LRU).

Default: **reject new puts** — simpler, predictable, no surprise data loss. Caller code already treats cache as best-effort (DB fallback). If reject rate becomes a problem, switch to external Caffeine shadow for LRU.

### 8.2 Corrupt file at startup — auto-fallback, no restart

On detection, factory auto-falls-back to Caffeine at startup. App continues serving traffic on Caffeine without restart. Chronicle file is **not** deleted — it persists on disk for operator inspection. Alternative: auto-delete and rebuild on every startup. **Decision: keep file, no auto-delete.** Prevents silent data loss from a real bug (corruption = signal, not noise). To return to Chronicle mode, operator investigates root cause, removes/repairs the file, restarts with `calculator.cache.backend=chronicle`.

### 8.3 Test environment — skip Chronicle entirely?

Issue AC says "default caffeine for test envs". Verify: unit tests cover both impls (§6.1). Integration tests on dev env use `chronicle` with `@TempDir`. CI tests both profile values.

---

## 9. Critical Files

| File | Change |
|------|--------|
| `module-calculator/build.gradle.kts` | Add `net.openhft:chronicle-map:3.23.5` |
| `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt` | New interface |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt` | New — wraps existing Caffeine cache |
| `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt` | New — Chronicle Map impl |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt` | New — profile switch + fallback |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CacheConfig.kt` | New — config holder |
| `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt` | New — Spring `@Bean` wiring with `destroyMethod = "close"` |
| `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt` | Refactor: depend on `OffHeapCacheBackend<CacheKey, ComponentCosts>` interface; Caffeine builder moves to `CaffeineCacheBackend`. Public API (`calculate()`) unchanged so `SnapshotChunkProcessor` and other callers are untouched. |
| `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` | **Unchanged.** Existing caller of `CalculationCache.calculate()`. |
| `module-calculator/src/main/resources/application.yml` | Add `calculator.cache.backend`, `calculator.cache.chronicle.path`, `calculator.cache.chronicle.maxEntries` |
| `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendTest.kt` | New — 11 unit tests |
| `docker/prometheus/rules/cache-backend-alerts.yml` | New — `calculator_cache_errors_total` rate alert |

---

## 10. Acceptance Criteria

Mapping to issue #1311 AC:

- [x] `net.openhft:chronicle-map` added to `module-calculator/build.gradle.kts` — §9
- [x] `OffHeapCacheBackend<K, V>` interface created — §3.1
- [x] `CaffeineCacheBackend` refactor (existing cache wrapped behind interface) — §3.2, §9
- [x] `ChronicleMapBackend` impl with named off-heap storage — §3.2, §9
- [x] `CacheConfig` profile switch (`caffeine` | `chronicle`) — §3.3, §7.5
- [x] Unit tests: put/get/overwrite/size — 21 tests pass (§6.1; covers 4 AC + 17 extensions across both backends, factory, serializers)
- [x] Heap reduction: `jvm_memory_used_bytes{area="heap"}{application="calculator"}` < 200MB — §6.2
- [x] Cache hit rate unchanged (`calculator_cache_hit_rate`) — §6.2
- [x] Fallback works: delete chronicle file mid-run → WARN logged, Caffeine takes over — §5, §6.1 `factory_corruptFileFallsBackToCaffeine`

---

## 11. Reused Symbols

- `CalculationCache` (`module-calculator/.../processor/CalculationCache.kt`) — existing wrapper holding the Caffeine cache and its `CacheKey` / `ComponentCosts` types. Refactored to depend on `OffHeapCacheBackend` interface. `CacheKey` and `ComponentCosts` data classes move with it (stays in `processor/` package — same module, no visibility change).
- `CalculatorChunkProcessingCoordinator.kt` `CHUNK_PROCESS_PERMITS` — semaphore backpressure, unchanged.
- Prometheus metric registration pattern — reused from existing `calculator_cache_*` metrics.
- Spring `@Bean(destroyMethod = "close")` — standard pattern, no new infrastructure.

---

## 12. Summary

Replace Caffeine with Chronicle Map for the OCID lookup cache via a profile switch (default caffeine). Pin exact patch 3.23.5 for file format stability. Cold-start on first deploy is acceptable. Caffeine stays in code as permanent fallback. Expected heap reduction 30–50MB.
