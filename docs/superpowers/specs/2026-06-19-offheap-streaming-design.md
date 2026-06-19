# Off-heap Streaming + Reactive Pipeline — Design

- Date: 2026-06-19
- Status: Draft (pending user review)
- Owner: maple-pipeline
- Shape: A (multi-phase refactor)

---

## 1. Goal

Reduce heap + RSS pressure in `module-external-api` and `module-calculator` by moving hot-path data structures (chunk buffers, OCID lookup cache, intermediate POJO lists) off-heap and into reactive streams. Today both modules sit at **~400MB heap + ~900MB RSS off-heap** (Netty/Kafka direct buffers); goal is **≤150MB heap + ≤500MB RSS** with no throughput regression.

**Constraints:**
- Off-heap is **load-bearing for OOM safety**, not for raw speed. Heap reduction is the primary metric.
- Migration must be **incremental** — each phase ships independently with a measurable improvement and zero regression.
- Must preserve bytewise output equivalence for the result stream (calculator → MinIO) — downstream consumers expect gzip-compressed JSONL with identical bytes.

**Out of scope (deferred):**
- Replacing Netty with a different HTTP client.
- Replacing Kafka with a different queue.
- Schema migration or output format change.

---

## 2. Background

Per `diagnose` run on 2026-06-19:

| Module | Heap used | RSS | Heap max | Notes |
|--------|-----------|-----|----------|-------|
| ext-api | 410MB | 1311MB | 2GB | 150MB Eden, 246MB Old Gen; ~900MB off-heap |
| calculator | 414MB | 1316MB | 1GB | 355MB Eden, 269MB Old Gen, 41MB Survivor; ~900MB off-heap |
| synchronizer | 229MB | 915MB | 1GB | mostly idle |

Off-heap consumers (heap - RSS = ~900MB):
- Reactor Netty PooledByteBufAllocator (~512MB by default)
- Apache Kafka client buffer pool (~200-300MB)
- JVM internals: GC card tables, thread stacks (~70MB × N threads), CodeCache

Hot-path heap consumers:
- `CalculationResultWriter` (`module-calculator/.../writer/CalculationResultWriter.kt:33`): buffers entire chunk in `ByteArrayOutputStream` before gzipping and uploading. With `CHUNK_PROCESS_PERMITS=4`, peak heap = 4 × chunk-size.
- Caffeine OCID lookup cache (`calculator_cache_size = 100K` entries, ~30-50MB heap per `calculator` module).
- `CalculatorChunkProcessingCoordinator` (`module-calculator/.../CalculatorChunkProcessingCoordinator.kt:58`): `Semaphore(CHUNK_PROCESS_PERMITS = 4)` permits.

---

## 3. Architecture

### 3.1 Layered off-heap strategy

Three layers, applied incrementally:

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 3 — Streaming pipeline (terminal)                       │
│   Calculator: gzip stream → MinIO putObject (no full buf)    │
│   ext-api: JsonParser incremental parse → flow               │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│ Layer 2 — Off-heap cache (Chronicle Map)                     │
│   OCID lookup cache: 100K entries → off-heap KV             │
│   ~0 heap bytes (entries live in mapped native memory)       │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│ Layer 1 — Direct buffer cap (JVM flag)                        │
│   -XX:MaxDirectMemorySize=512m                               │
│   Netty/Kafka direct pool auto-tunes to bound                │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 Off-heap tech choice

| Need | Library | Rationale |
|------|---------|-----------|
| Key-value cache (OCID) | **Chronicle Map** | Off-heap KV store, native persistence optional, simple API, used in HFT systems for low-GC cache. License: Apache 2.0. |
| Streaming I/O buffers | **Netty ByteBuf** (already in use via PooledByteBufAllocator) | Direct ByteBuf pool; tied to Netty's reference counting. |
| Streaming JSON parse | **Jackson JsonParser** (already in use) | Token-stream API: read one token at a time, no full POJO List. |
| Streaming gzip → S3 | **Netty ByteBuf + S3AsyncClient (multipart upload)** | Multipart upload accepts streaming input; no full-buffer intermediate. |

Chronicle Map is the only NEW dependency. The rest uses what's already in the build.

---

## 4. Phase Plan (incremental, each independently shippable)

### Phase 1 — Direct memory cap (config-only)

**Scope:** JVM flag + monitoring.

**Changes:**
- `build.gradle` (calculator + ext-api): add `jvmArgs '-XX:MaxDirectMemorySize=512m'`
- Add Prometheus alert at >400MB direct buffer usage (early warning)

**Expected impact:** RSS -300-400MB. Heap unchanged. Netty/Kafka auto-tune pool size to bound.

**Risk:** None. Netty will shrink direct buffers; minor throughput dip on first request after GC cycle.

**Verification:** RSS < 800MB sustained, throughput within 5% of baseline.

### Phase 2 — Off-heap OCID cache (Chronicle Map)

**Scope:** Replace Caffeine L1 cache with Chronicle Map.

**Changes:**
- New dependency: `net.openhft:chronicle-map:3.21ea11` (or current stable) in `module-calculator/build.gradle`.
- Replace `calculator/config/CacheConfig.kt` (or equivalent Caffeine config) with Chronicle Map factory.
- Migration: read from Caffeine at startup, write to Chronicle Map on first miss; expose metrics with `chronicle_map_*` prefix.
- Fallback: if Chronicle Map unavailable (test env), use Caffeine — keep both implementations behind a profile switch.

**Expected impact:** heap -30-50MB (Caffeine entries gone).

**Risk:** Medium — new dependency, serialization compatibility for OCID key. Limit via feature flag `calculator.cache.backend=caffeine|chronicle`.

**Verification:** heap reduction measured, cache hit rate unchanged, no read errors.

### Phase 3 — Streaming calculator writer

**Scope:** Replace `ByteArrayOutputStream`-based gzip → upload with streaming pipe.

**Changes:**
- `CalculationResultWriter.write()`: pipe results through `JsonGenerator → CountingOutputStream → GZIPOutputStream → PipedOutputStream → S3AsyncClient multipart upload`.
- Buffering cap: 8MB streaming buffer (was full chunk in memory).
- Tests: bytewise equivalence of gz output (decompress and compare with reference run).

**Expected impact:** heap -40MB (4 concurrent chunks × 10MB buffer eliminated).

**Risk:** Medium-high — streaming upload has retry semantics. Verify with chaos test.

**Verification:** gzip output bytewise identical to current, upload success rate 99.9%+, heap reduction measured.

### Phase 4 — Streaming ext-api chunk parser

**Scope:** Parse gz-compressed chunk JSONL one record at a time.

**Changes:**
- `NexonAdapter` (or the chunk loader): use `JsonParser` to iterate tokens, emit `Flow<ItemRecord>` to downstream.
- No POJO `List<ItemRecord>` intermediate.

**Expected impact:** heap -50MB on peak chunks.

**Risk:** Medium — Jackson streaming API has different error semantics than `readValue`. Need thorough test coverage.

**Verification:** throughput unchanged, heap reduction measured, no parse errors on edge cases.

### Phase 5 — Direct buffer tuning (post-baseline)

**Scope:** Tune Netty/Kafka direct buffer pool sizes after Phase 1 baseline.

**Changes:**
- Netty: `-Dio.netty.allocator.numDirectArenas=<cpu/2>` (auto-tune based on cores)
- Kafka: `buffer.memory=64MB` (down from default 256MB) on producer/consumer

**Expected impact:** RSS -100-200MB additional.

**Risk:** Low — explicit values, monitor + rollback.

---

## 5. Component Breakdown

### 5.1 `OffHeapCacheBackend` (new abstraction)

Interface in `module-calculator/.../cache/`:

```kotlin
interface OffHeapCacheBackend<K, V> {
    fun get(key: K): V?
    fun put(key: K, value: V)
    fun size(): Long
    fun close()
}
```

Implementations:
- `CaffeineCacheBackend` — current Caffeine wrapper
- `ChronicleMapBackend` — Chronicle Map impl

Selection via `calculator.cache.backend` profile property.

### 5.2 `StreamingResultUploader` (rewrite)

New class in `module-calculator/.../writer/`:
- Input: `Flow<CalculationResult>`
- Output: S3 multipart upload with gzip streaming
- Buffer cap: 8MB (configurable)
- Backpressure: respects `Semaphore(CHUNK_PROCESS_PERMITS)`

Replaces `CalculationResultWriter.write()` for new deployments.

### 5.3 `StreamingChunkParser` (new)

New class in `module-external-api/.../parser/`:
- Input: `InputStream` (gz-compressed JSONL)
- Output: `Flow<ItemRecord>` via `JsonParser`
- No intermediate POJO collection

Used by the chunk loader to feed downstream flows.

---

## 6. Data Flow

### Before (Phase 1 baseline)

```
ext-api chunk loader:
  InputStream (gz) → GZIPInputStream → byte[] (full chunk) → ObjectMapper.readValue → List<ItemRecord> (heap)

calculator result writer:
  Flow<CalculationResult> → collect → ByteArrayOutputStream (full chunk, 10MB) → 
    GZIPOutputStream → byte[] → putStream(key, ByteArrayInputStream)
```

### After (Phase 5)

```
ext-api chunk loader:
  InputStream (gz) → GZIPInputStream → JsonParser (streaming) → Flow<ItemRecord> (off-heap)
    [DirectByteBuffer pool for parse buffer, ~8MB max]

calculator result writer:
  Flow<CalculationResult> → JsonGenerator → CountingOutputStream → 
    GZIPOutputStream → PipedOutputStream → S3AsyncClient multipart upload
    [8MB streaming buffer, no full-chunk heap allocation]
```

OCID lookup:
```
Caffeine L1 (100K entries, 30-50MB heap) → Chronicle Map (off-heap, ~0 heap)
```

---

## 7. Error Handling

| Failure | Behavior |
|---------|----------|
| Chronicle Map corruption on startup | Fall back to Caffeine, log WARN, retry on next restart. |
| Streaming upload mid-chunk error | Multipart abort, retry from last successful part. Bounded retries (3) before failing the chunk. |
| JsonParser streaming error (malformed record) | Skip record, log ERROR with offset, continue with next record. Don't fail the whole chunk. |
| Direct buffer cap exceeded (Phase 1) | JVM throws `OutOfMemoryError: Direct buffer memory` → process dies. Mitigation: monitor Prometheus alert at 80% of cap (410MB of 512MB) for early warning. |

---

## 8. Verification

### Per-phase

**Phase 1:**
- Build, deploy, run pipeline for 30min.
- Metrics: `process_cpu_usage` < 70%, `jvm_memory_used_bytes{area="heap"}` unchanged, RSS < 800MB.
- Compare: `external_api_users_fetched_total` rate within ±5% of baseline.

**Phase 2:**
- Build, deploy, run pipeline for 1hr.
- Metrics: `jvm_memory_used_bytes{area="heap"}` calc < 200MB (from 400MB baseline). Cache hit rate unchanged (`calculator_cache_hit_rate`).
- Negative test: simulate chronicle corruption (delete chronicle file mid-run), confirm Caffeine fallback works.

**Phase 3:**
- Build, deploy, run pipeline for 1hr.
- Metrics: heap < 200MB sustained (calc), RSS < 700MB.
- Bytewise test: `diff <(gunzip -c new_run.jsonl.gz) <(gunzip -c reference_run.jsonl.gz)` → no diff.
- Chaos test: kill S3 endpoint mid-upload, confirm partial upload detection + retry.

**Phase 4:**
- Build, deploy, run pipeline for 1hr.
- Metrics: ext-api heap < 200MB sustained (from 410MB baseline).
- Parse error test: feed malformed JSON, confirm record-level skip (not chunk-level fail).

**Phase 5:**
- Build, deploy, run pipeline for 1hr.
- Metrics: RSS < 500MB total (ext-api + calc combined).
- Sustained 1hr without OOM.

### Aggregate (after all phases)

| Metric | Baseline | Target | Measurement |
|--------|----------|--------|-------------|
| ext-api heap | 410MB | ≤150MB | `jvm_memory_used_bytes{area="heap"}{application="external-api"}` |
| calculator heap | 414MB | ≤200MB | `jvm_memory_used_bytes{area="heap"}{application="calculator"}` |
| ext-api RSS | 1311MB | ≤500MB | `/proc/<pid>/status` `VmRSS` |
| calculator RSS | 1316MB | ≤500MB | same |
| Pipeline throughput | baseline | within ±5% | `external_api_users_fetched_total` rate |

---

## 9. Critical Files

| File | Phase | Change |
|------|-------|--------|
| `module-external-api/build.gradle.kts` | 1 | Add `-XX:MaxDirectMemorySize=512m` to jvmArgs |
| `module-calculator/build.gradle.kts` | 1 | Same flag |
| `module-calculator/build.gradle.kts` | 2 | Add `net.openhft:chronicle-map` dependency |
| `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapCacheBackend.kt` | 2 | New interface |
| `module-calculator/src/main/kotlin/maple/calculator/cache/ChronicleMapBackend.kt` | 2 | New Chronicle Map impl |
| `module-calculator/src/main/kotlin/maple/calculator/cache/CaffeineCacheBackend.kt` | 2 | New Caffeine impl (refactor of current) |
| `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` | 3 | Refactor to streaming gzip → S3 |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/StreamingChunkParser.kt` | 4 | New streaming parser |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PhaseLoopController.kt` (or wherever chunks are loaded) | 4 | Use StreamingChunkParser |
| `docker/prometheus/rules/load-test-rules.yml` (or new `offheap-alerts.yml`) | 1 | Add direct buffer usage alert |
| `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` | - | This file |

---

## 10. Reused Symbols

- `CalculatorChunkProcessingCoordinator.kt:222` `CHUNK_PROCESS_PERMITS` — backpressure semaphore, reused.
- `JsonGenerator`, `CountingOutputStream` — already in writer, reused.
- `PooledByteBufAllocator.DEFAULT` (Netty) — reused for direct buffer cap (Phase 1 auto-tuning).
- `JsonParser` (Jackson) — reused for streaming parse (Phase 4).
- `Semaphore`, `kotlinx.coroutines.sync.Semaphore` — existing backpressure primitives.

---

## 11. Open Questions

1. **Chronicle Map version pinning**: latest stable is 3.21.x. Should we pin to a specific patch? (recommendation: pin to latest stable, monitor for breaking changes in 3.22+).
2. **S3 async client availability**: `module-calculator` currently uses synchronous `ObjectStorage` (S3 sync). Switching to `S3AsyncClient` may be a larger refactor than Phase 3 alone — should Phase 3 be limited to "no full-buffer intermediate" using a bounded in-memory ring buffer (~8MB) and keep the sync client? **Recommendation: ring buffer** (simpler, still solves the heap issue without async migration).
3. **Cache fallback policy**: if Chronicle Map corrupts, do we auto-restart or use Caffeine? (recommendation: Caffeine fallback with WARN log; manual intervention if recurring).

These will be addressed in writing-plans / to-issues.