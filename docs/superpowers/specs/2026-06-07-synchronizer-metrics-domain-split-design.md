# Issue #992 — SynchronizerMetrics Domain Split Design

**Status:** Accepted
**Date:** 2026-06-07
**Owner:** synchronizer

---

## 1. Background / Problem

### Background

`SynchronizerMetrics` (70 lines, 22 methods after #1066 split, 5 responsibility domains) is a `@Component` that proxies 5 unrelated metric surfaces through one facade:

1. **Chunk lifecycle counters** — `chunksReceived`, `chunksProcessing`, `chunksProcessed`, `chunksFailed`
2. **Chunk execution state machine counters** — `inserted/claimed/skipped/succeeded/failed/reclaimed` (6 factories, all `chunkExecution*Total`)
3. **Document/item volume** — `documentsProcessed`, `itemsProcessed`, `chunkDocumentsSummary`, `chunkItemsSummary`, `documentEquipmentSummary`
4. **Pre-upsert volume** — `preUpsertCompressedBytesTotal/UncompressedBytesTotal/JsonRowsTotal`, 2 distribution summaries, compression ratio summary
5. **Timers** — `chunkTimer`, `fileReadTimer`, `documentBuildTimer`, `mainUpsertTimer`
6. **Misc** — `recordStatusTransition`, `recordChunkBytes`

The state machine counters (domain 2) are owned by `ChunkConsumerTemplate` only. The volume metrics (domains 3+4) are owned by `DefaultChunkProcessor` + `SynchronizerChunkMetricsListener`. Keeping them in one class creates two problems:
- Test isolation: a unit test for `ChunkExecutionStateMachine` flow can only assert via a mock of the entire `SynchronizerMetrics` surface.
- Cohesion drift: a future change to one domain (e.g., add a tag to chunk-execution counters) forces the whole file to be touched and reviewed.

### Problem

Domain 2 has 7 call sites, all in `ChunkConsumerTemplate`. Domain 3+4 has 5 call sites, spread across `DefaultChunkProcessor` and `SynchronizerChunkMetricsListener`. They have no shared logic — only the `SynchronizerMeterRegistry` dependency. A reader of the consumer template today has to scan 17 methods on `SynchronizerMetrics` to know which one updates the chunk-execution counter family.

### Goal

Extract 2 new `@Component` classes:
- `ChunkExecutionMetrics` — owns all `chunkExecution*Total` counter methods.
- `DocumentVolumeMetrics` — owns document/item volume + pre-upsert volume methods.

`SynchronizerMetrics` keeps chunk lifecycle + timer accessors + status transition + chunk-bytes recording. Callers migrate. Prometheus metric names and tags stay identical.

---

## 2. Decision

> Split into 3 cohesive metric classes, each owning one cluster of related Prometheus meters. No new package boundary (all stay in `maple.synchronizer.metrics`). No metric rename, no tag change.

```text
SynchronizerMetrics (slimmed)        ChunkExecutionMetrics (NEW)       DocumentVolumeMetrics (NEW)
├── incrementReceived                ├── recordChunkExecutionInserted   ├── incrementDocuments
├── incrementProcessing              ├── recordChunkExecutionClaimed    ├── incrementItems
├── decrementProcessing              ├── recordChunkExecutionSkipped    ├── recordChunkSize
├── incrementProcessed               ├── recordChunkExecutionSucceeded  ├── recordDocumentEquipment
├── incrementFailed                  ├── recordChunkExecutionFailed     └── recordPreUpsertVolume
├── recordStatusTransition           └── recordChunkExecutionReclaimed
├── recordChunkBytes
├── chunkTimer                                                       SynchronizerMeterRegistry (unchanged)
├── fileReadTimer                                                    └── provides all Counter/Timer/DistributionSummary
├── documentBuildTimer
└── mainUpsertTimer
```

### Surface

```kotlin
@Component
class ChunkExecutionMetrics(private val meterRegistry: SynchronizerMeterRegistry) {
    fun recordChunkExecutionInserted(executionType: ChunkExecutionType)
    fun recordChunkExecutionClaimed(executionType: ChunkExecutionType)
    fun recordChunkExecutionSkipped(executionType: ChunkExecutionType, status: ChunkExecutionStatus)
    fun recordChunkExecutionSucceeded(executionType: ChunkExecutionType)
    fun recordChunkExecutionFailed(executionType: ChunkExecutionType, status: ChunkExecutionStatus, reason: String)
    fun recordChunkExecutionReclaimedExpired(executionType: ChunkExecutionType)
}

@Component
class DocumentVolumeMetrics(private val meterRegistry: SynchronizerMeterRegistry) {
    fun incrementDocuments(count: Int)
    fun incrementItems(count: Long)
    fun recordChunkSize(documents: Int, items: Long)
    fun recordDocumentEquipment(count: Int)
    fun recordPreUpsertVolume(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long)
}
```

### Caller Migration

| Class | Before | After |
|---|---|---|
| `ChunkConsumerTemplate` | `metrics: SynchronizerMetrics` (7 call sites) | `executionMetrics: ChunkExecutionMetrics` |
| `DefaultChunkProcessor` | `metrics: SynchronizerMetrics` (3 call sites) | `volumeMetrics: DocumentVolumeMetrics` |
| `SynchronizerChunkMetricsListener` | `metrics: SynchronizerMetrics` (1 preUpsertVolume call) | `volumeMetrics: DocumentVolumeMetrics` |
| `ChunkDataReader`, `ChunkDocumentWriter`, `ChunkDocumentTransformer` | `metrics: SynchronizerMetrics` (timer accessors) | unchanged — keep `SynchronizerMetrics` |
| `SynchronizerChunkMetricsListener` | `metrics: SynchronizerMetrics` (other calls) | unchanged — keep `SynchronizerMetrics` for lifecycle + chunkTimer + recordChunkBytes |

### Wiring

All 3 classes are `@Component` and inject `SynchronizerMeterRegistry`. Spring auto-wires by type. No factory needed.

### Testability

- New `ChunkExecutionMetricsTest` — verify each method calls the correct registry factory with the right tags.
- New `DocumentVolumeMetricsTest` — verify volume counters/summaries increment and that `recordPreUpsertVolume` skips the ratio summary when `compressedBytes == 0`.
- Existing `SynchronizerMeterRegistryTest` (if any) covers registry-level concerns. New tests focus on the recording class.

---

## 3. Trade-offs

### Sensitivity

* `ChunkExecutionType` / `ChunkExecutionStatus` enums — same types used in `ChunkExecutionStateMachine` (refactored in #985).
* Prometheus name+tag strings — locked, must not drift (existing dashboards).
* Caller count — 3 classes today, 1 new test class per new metric class.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 2 new classes (`ChunkExecutionMetrics`, `DocumentVolumeMetrics`) | Cohesion; per-domain testability; consumer template reads cleanly | 2 extra `@Component` declarations; caller-side constructor widening (1 extra dep) |
| Keep `SynchronizerMetrics` as a thin facade for chunk-lifecycle + timers | Backward-compatible injection for existing timer-accessor callers | Class still has mixed concerns (lifecycle + timer) — acceptable since these always go together in `SynchronizerChunkMetricsListener` |
| No metric rename, no tag change | Prometheus compatibility | Stuck with the current naming if a better one becomes available |

### Risk

* Forgetting a call site — compile is the safety net because the methods are removed from `SynchronizerMetrics`. The compiler will flag the dead reference.
* Test surface proliferation — 2 new test classes. Acceptable: ~30 lines each.

### Non-Risk

* No new package boundary — all stay in `maple.synchronizer.metrics`.
* No DI pattern change — all `@Component`.
* No thread model change — all methods are still non-blocking counter/summary increments.
* No DB or Kafka port impact.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
| ------ | ----: | ----: | ----- |
| `SynchronizerMetrics.kt` line count | 70 | ~40 | -3 volume methods, -6 execution methods, +2 imports |
| `SynchronizerMetrics` public methods | 22 | 11 | -11 |
| New `ChunkExecutionMetrics` | n/a | ~30 | 6 methods, 1 dependency |
| New `DocumentVolumeMetrics` | n/a | ~30 | 5 methods, 1 dependency |
| New test classes | n/a | 2 | ~50 LOC each |
| Prometheus metric names | unchanged | unchanged | Compatibility preserved |
| Prometheus tags | unchanged | unchanged | Compatibility preserved |

### Observed Result

* `./gradlew :module-synchronizer:compileKotlin compileJava --continue` — green
* `./gradlew :module-synchronizer:test` — green (existing tests + 2 new test classes pass)
* Caller migration: `ChunkConsumerTemplate`, `DefaultChunkProcessor`, `SynchronizerChunkMetricsListener` updated. Other callers unchanged.

---

## 5. Summary

> Extract chunk-execution state-machine counters and document/pre-upsert volume metrics into dedicated `@Component` classes. Keep `SynchronizerMetrics` for chunk lifecycle + timers. No Prometheus surface change.
