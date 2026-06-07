# Spec: ChunkedSnapshotSink File I/O + Chunk Rotation Extraction (Issue #989)

- Status: Approved
- Date: 2026-06-07
- Issue: #989
- Owner: zbnerd
- Blocked-by: #987 (CLOSED) — event-publisher work already done in worktree

## Background

`ChunkedSnapshotSink` (277 lines, 13 methods, 4 domains) is a queue-backed writer
that mixes four concerns in one class:

1. **Queue / writer-thread lifecycle** — bounded `ArrayBlockingQueue`,
   `accepting` flag, `writerError`, single-thread executor, submit/close lifecycle.
2. **File I/O + chunk rotation** — chunk directory layout, `currentWriter`
   rotation, `failedWriter` (delegated), manifest file writes, `_SUCCESS` marker,
   `_RUNNING` marker cleanup.
3. **Event publishing** — DTO construction, `snapshotVolume` log line,
   `volumeMetrics` recording, dispatch to `SinkEventPublisher`.
4. **Per-record error handling** — fallback to `failedWriter` when body bytes
   are invalid.

The 292-line event-publisher extraction (issue #987) is already done in the
`issue-987` worktree (and is what this branch is based on via
`refactor/987-snapshot-sink-event-publisher`). After that lands, the next-largest
extraction is file I/O + chunk rotation.

This issue (989) extracts that I/O concern into a new `ChunkFileManager` class.
The sink keeps only queue + writer-thread lifecycle. Pure mechanical refactor —
no behavior change.

## Decision

Create a new class `ChunkFileManager` that owns every filesystem concern of
the sink, and reduce `ChunkedSnapshotSink` to queue + writer-thread lifecycle.

### `ChunkFileManager` owns

- Path layout: `chunksDir`, `failedPath`, `manifestPath`, `successPath`
  (and `_RUNNING` lookup via the injected `runDir`).
- The mutable `SnapshotChunkManifest` for the run.
- The active `GzipJsonlChunkWriter` (`currentWriter`) + `nextPartIndex`.
- The `SnapshotFailedRecordWriter` instance.
- Public API:

```kotlin
class ChunkFileManager(
    private val runDir: Path,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun initialize()                           // mkdir, open chunk-1, instantiate failedWriter
    fun appendSuccess(record: SnapshotChunkRecord.Success)  // delegates to currentWriter, may rotate
    fun appendFailure(record: SnapshotChunkRecord.Failure)  // delegates to failedWriter
    fun rotateChunk(): ChunkStats?              // close current, return stats if records > 0
    fun closeCurrentChunk(): ChunkStats?       // same as rotate but for shutdown
    fun cleanupOnFailure()                     // delete tmp files of currentWriter
    fun writeManifestAndSuccessMarker()        // finalize manifest, write manifest.json, write _SUCCESS
    fun deleteRunningMarker()                  // best-effort _RUNNING cleanup
    fun manifest(): SnapshotChunkManifest      // read access for event publishing
    fun currentRecordCount(): Int              // manifest.totalRecords (read for event payloads)
}
```

Constructor takes the same knobs the sink currently threads into
`GzipJsonlChunkWriter` (`maxRecords`, `maxUncompressedBytes`, `objectMapper`,
`clock`) plus the run dir + endpoint. It opens the first chunk eagerly in
`initialize()` so the constructor stays pure and the sink still owns init order.

### `ChunkedSnapshotSink` after extraction

Holds:

- `queue`, `accepting`, `writerError` (concurrency primitives)
- `writerExecutor` (single-thread platform executor for the writer loop)
- `fileManager: ChunkFileManager`
- `eventPublisher: SinkEventPublisher` / `SnapshotSinkEventPublisher` (already
  extracted in #987)
- `volumeMetrics`, `clock`

The writer loop's `handleSuccess`/`Failure` become thin calls into
`fileManager`. The `close()` method orchestrates:

1. enqueue `CloseSignal`
2. `writerExecutor.shutdown()` + 60s await
3. on writer error → `fileManager.cleanupOnFailure()` + `publishRunFailed`
4. on success → `fileManager.closeCurrentChunk()` + write manifest + `_SUCCESS`
5. `fileManager.deleteRunningMarker()`
6. `publishRunCompleted()`

Event publishing (publishChunkReady inside rotateChunk, publishRunCompleted in
close, publishRunFailed on error) stays in the sink. The `ChunkStats` from
`rotateChunk()`/`closeCurrentChunk()` is the payload the sink feeds into
`SnapshotSinkEventPublisher.publishChunkReady(stats, runId, endpoint)`.

## Scope

### Modify

- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`
  - Remove: `chunksDir`, `failedPath`, `manifestPath`, `successPath`,
    `manifest`, `failedWriter`, `currentWriter`, `nextPartIndex`.
  - Remove: `rotateChunk()`, `closeCurrentChunk()`, `newChunkWriter()`,
    `cleanupOnFailure()` (delegates move to `fileManager`).
  - Remove: `_SUCCESS` write, `_RUNNING` delete, `manifest.finishedAt`
    assignment, `manifest.totalFailed = failedWriter.count()` — all move
    to `fileManager.writeManifestAndSuccessMarker()`.
  - Keep: `submit()`, `queueDepth()`, `close()`, `runWriterLoop()`,
    `handleSuccess()` (now `fileManager.appendSuccess` + `fileManager.rotateChunk`).
- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt`
  - Replace `objectMapper` + chunking-related args with a single
    `ChunkFileManager` factory call (or pass them through unchanged — TBD
    below).
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`
  - Same: build `ChunkFileManager` and pass to `ChunkedSnapshotSink`.

### Add

- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`
  - new class, ~120 lines, 11 public methods above
  - one private `newChunkWriter()` helper
  - owns `SnapshotChunkManifest` mutation in a single place

### Out of Scope

- Event-publishing extraction (#987 territory; already landed in worktree).
- `SnapshotChunkRecord` / `SnapshotFailedRecordWriter` / `GzipJsonlChunkWriter` /
  `SnapshotChunkManifestWriter` — unchanged.
- `SnapshotChunkingProperties`, `RankingSnapshotSinkFactory` — unchanged
  structurally; only call sites update to construct the manager first.
- Any new metrics, logs, or behaviour changes.
- Tests for `ChunkFileManager` — issue acceptance criteria do not require new
  tests. Existing behavior must be preserved (verified by `./gradlew test`).
  If a unit test is naturally required to pin the manifest write order, it
  goes in a follow-up — not blocking this PR.

## Trade-offs

### Sensitivity

- **Writer-loop call count to manager**: handleSuccess hot path. Each success
  record currently does 1-2 virtual calls into the manager. Negligible vs.
  gzip + Jackson serialization cost.
- **Field count of `ChunkedSnapshotSink`**: drops from 17 to ~8. The 277-line
  file should drop below 200 lines.

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| New `ChunkFileManager` class | Single owner for I/O; sink ≈ 200 lines | New class to learn, 4-line call-site change at 3 factories |
| Pass 6 args to manager from factory | Minimal indirection | Manager constructor reads like a sink-mirror, weaker abstraction |
| Keep `currentWriter` and rotation in sink | No new class | Original goal (sink = queue + lifecycle) missed |

→ Chose new class with 6 ctor args — `GzipJsonlChunkWriter` already takes
those args, the manager just hoists them one level up.

### Risk

- **Init-order mistake**: `failedPath.parent` must be created before the
  failed writer's first `append`. Current code does this in sink `init {}`
  via `Files.createDirectories(failedPath.parent)`. Manager `initialize()`
  must do the same — same ordering, just in a new home. **Mitigation**:
  initialize() does mkdir for chunksDir + failedPath.parent + first chunk
  open, all in one block. Server runtime test catches any miss.

- **Manifest mutation order**: today, manifest is mutated in sink under the
  writer thread (single-thread guarantee). After extraction, the manager
  is the only writer to `manifest.chunks` and `manifest.totalRecords`.
  Since `fileManager` is called only from the writer thread (same as before),
  no new concurrency hazard. **Mitigation**: document thread-affinity in
  KDoc — manager methods are NOT thread-safe, must be called from the
  sink's single writer thread.

- **Public API of `ChunkStats` rotation**: the sink still needs
  `ChunkStats` to build `SnapshotChunkReadyEvent`. `rotateChunk()` returns
  `ChunkStats?` (null when zero records). Already-thread-safe since only the
  writer thread calls it.

### Non-Risk

- Bean conflicts: new class is not a Spring bean, constructed inline by
  factory / phase. No `@Component` / `@Service` collision possible.
- Kafka topic routing: `SinkEventPublisher` is unchanged. Per-endpoint
  routing preserved.
- Wire format: `SnapshotChunkReadyEvent` payload, `snapshotVolume` log line,
  and `volumeMetrics.recordChunk` call are byte-identical — verified by
  `git diff` after refactor.

## Result / Evidence

### Metrics

| Metric | Before | After | Notes |
| --- | ---: | ---: | --- |
| `ChunkedSnapshotSink` lines | 277 | ~190 | -87 lines, ~31% reduction |
| `ChunkedSnapshotSink` methods | 13 | ~9 | rotateChunk/closeCurrentChunk/newChunkWriter/cleanupOnFailure moved |
| `ChunkedSnapshotSink` mutable fields | 13 | 7 | I/O state moved out |
| New class `ChunkFileManager` | 0 | ~120 | net +30 lines, expected for class boundary boilerplate |

### Observed Result

- `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue` → 0 errors
- `./gradlew :module-external-api:test` → all pass
- `./gradlew :module-app:bootRun` (or `module-external-api:bootRun` for that module) → server starts
- `curl /api/v5/characters/진격캐넌/expectation` → 202
- `grep "Calculation completed" module-calculator/logs/app.log` → completes
  (end-to-end pipeline still works)
- `grep ERROR` on all module logs → no new errors

## Summary

> Extract every filesystem concern (chunk rotation, manifest, success/running
> markers, failed-record file) from `ChunkedSnapshotSink` into a new
> `ChunkFileManager`. Sink shrinks to ~190 lines, holding only the queue,
> writer thread, and event-publishing calls. Pure mechanical refactor — no
> behavior change, no new tests required, no metric/payload diff.
