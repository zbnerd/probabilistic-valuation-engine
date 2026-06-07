# Issue #987 — ChunkedSnapshotSink Event Publisher Extraction Design

**Status:** Accepted
**Date:** 2026-06-07
**Owner:** synchronizer

---

## 1. Background / Problem

### Background

`ChunkedSnapshotSink` (277 lines, 13 methods, 4 responsibility domains) directly builds and dispatches three event types inline:
- `SnapshotChunkReadyEvent` (in `publishChunkReady`, with volume-metrics recording and `snapshotVolume` log)
- `SnapshotRunCompletedEvent` (in `publishRunCompleted`, after `_SUCCESS` marker is written)
- `SnapshotRunFailedEvent` (in `publishRunFailed`, on writer-thread failure)

Event construction is the only responsibility that requires knowledge of:
- DTO shape (`eventId`, `createdAt`, `kafkaKey` paths)
- `Clock` for `createdAt` timestamps
- `SnapshotVolumeMetrics` for chunk-size metrics
- `objectKey` and `manifestPath` layout (`runs/{runId}/{endpoint}/...`)

The sink class currently mixes file I/O, queue management, writer-thread lifecycle, manifest bookkeeping, AND event construction in one class. The acceptance criterion asks to separate the last concern so that:
1. The sink can be tested without instantiating the event-publisher surface.
2. The new class is independently testable for event-shape regressions.
3. The sink shrinks back below 250 lines (current 277; only the dispatch call sites remain).

### Problem

`ChunkedSnapshotSink.publishChunkReady`/`publishRunCompleted`/`publishRunFailed` (lines 228-276) embed 40+ lines of event construction + metrics + logging that have no relation to the sink's chunk-rotation / queueing / file-write responsibilities. This makes the sink harder to read and harder to test in isolation. Any change to event shape, metric recording, or path layout currently requires editing the sink.

### Goal

Extract event construction + volume metrics + snapshot-volume logging into a new `SnapshotSinkEventPublisher` class. The sink retains only the call-site delegation. No behavior change: same event payloads, same publish timing, same log line, same metric tags.

---

## 2. Decision

> Introduce `SnapshotSinkEventPublisher` as a stateless `@Component` that owns event DTO construction, volume-metrics recording, the `snapshotVolume` log line, and the `SinkEventPublisher` dispatch. The sink keeps only the 3 dispatch calls.

```text
ChunkedSnapshotSink                                SnapshotSinkEventPublisher (NEW)
  ├── submit(record)                                 ├── publishChunkReady(stats, runId, endpoint)
  ├── close()                                        │     ├─ volumeMetrics.recordChunk(...)
  │     ├── cleanupOnFailure()                       │     ├─ log "snapshotVolume"
  │     ├── publishRunFailed()  ──────────────►      │     ├─ build SnapshotChunkReadyEvent
  │     └── publishRunCompleted() ────────────►      │     └─ eventPublisher.publishChunkReady
  ├── runWriterLoop()                                ├── publishRunCompleted(manifest, endpoint)
  ├── handleSuccess(record)                          │     ├─ build SnapshotRunCompletedEvent
  ├── rotateChunk()                                  │     └─ eventPublisher.publishRunCompleted
  │     └── publishChunkReady() ─────────────►      └── publishRunFailed(manifest, endpoint, errorMessage)
  └── closeCurrentChunk()                                  ├─ build SnapshotRunFailedEvent
        └── publishChunkReady() ─────────────►            └─ eventPublisher.publishRunFailed
```

### Surface

```kotlin
@Component
class SnapshotSinkEventPublisher(
    private val eventPublisher: SinkEventPublisher,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun publishChunkReady(stats: ChunkStats, runId: String, endpoint: String)
    fun publishRunCompleted(manifest: SnapshotChunkManifest, endpoint: String)
    fun publishRunFailed(manifest: SnapshotChunkManifest, endpoint: String, errorMessage: String)
}
```

Methods are stateless beyond the injected `clock` + collaborators. The class does not own `runId`/`endpoint` as fields — those vary per sink instance, and threading the call-site context into the method keeps the publisher reusable.

### Wiring

The 3 construction sites (`RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`) currently build `SinkEventPublisher` inline and pass it + `volumeMetrics` to the sink. After extraction:
- Each site injects the new `SnapshotSinkEventPublisher` bean (Spring autowire by type).
- The factory/phase passes only the publisher to `ChunkedSnapshotSink`.
- The `volumeMetrics` and `eventPublisher` parameters are removed from the sink constructor.
- The sink drops `import com.fasterxml.jackson.databind.ObjectMapper`? No — `ObjectMapper` is still needed for the manifest writer and chunk writer.

### Testability

A focused `SnapshotSinkEventPublisherTest` is added (none exists for this surface today). Tests verify:
- `publishChunkReady` builds an event with correct fields (objectKey, chunkId format, createdAt) and calls `eventPublisher.publishChunkReady`.
- `publishRunCompleted` builds an event with `manifestPath`, `chunkCount`, `totalRecords` from the manifest.
- `publishRunFailed` builds an event with `errorMessage` and dispatches.
- `volumeMetrics.recordChunk` is called with the right compressed/uncompressed/recordCount values for `publishChunkReady`.

---

## 3. Trade-offs

### Sensitivity

* `runId` and `endpoint` strings — passed as parameters, not fields. They vary per sink instance, and the publisher is a singleton bean.
* `Clock` injection — same `Clock.systemUTC()` default the sink uses today, so behavior is identical. The test injects a fixed Clock to assert `createdAt` exactly.
* `SnapshotVolumeMetrics` dependency — same instance the sink used; no bean duplication.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Extract as stateless `@Component` with method params | Easy unit testing; reusable across sink types; no per-instance state | One extra method parameter on each call (`runId`/`endpoint`) |
| Keep `manifest` + `endpoint` as method params (vs. wrap in a context type) | No new value type; minimal API surface | Slightly wider method signatures (acceptable for 3 callers) |

### Risk

* The 3 construction sites need to update in lockstep — if a phase still passes the old `SinkEventPublisher` directly to the sink, the compile will fail (sink no longer accepts that type). Compilation is the safety net.
* Event-shape drift: a future change to `SnapshotChunkReadyEvent` could miss updating the new class. Mitigation: `SnapshotSinkEventPublisherTest` pins the field values.

### Non-Risk

* No new package boundary — both classes stay in `maple.externalapi.snapshot`.
* No DB or Kafka port changes — the publisher still delegates to `SinkEventPublisher` (which delegates to the existing `SnapshotChunkEventPublisher` port).
* No thread model change — the publisher methods are called from the sink's writer thread (rotation/close paths) and main thread (close); both are fine since the underlying `SinkEventPublisher` returns a `CompletableFuture` and is fire-and-forget.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
| ------ | ----: | ----: | ----- |
| `ChunkedSnapshotSink.kt` line count | 277 | ~245 | 3 publish methods + 1 `objectKeyFor` removed |
| `ChunkedSnapshotSink` private methods | 13 | 10 | -3 publish methods |
| `ChunkedSnapshotSink` constructor params | 9 | 7 | -`volumeMetrics`, -`objectKeyFor` is already inlined |
| New class `SnapshotSinkEventPublisher` | n/a | ~80 lines | Stateless, fully unit-tested |

### Observed Result

* `./gradlew :module-external-api:compileKotlin compileJava --continue` — green
* `./gradlew :module-external-api:test` — green
* New `SnapshotSinkEventPublisherTest` covers all 3 publish methods + volume-metrics side-effect

---

## 5. Summary

> Move event construction, volume metrics, and the `snapshotVolume` log line from `ChunkedSnapshotSink` into a new stateless `SnapshotSinkEventPublisher`; the sink delegates. No behavior change.
