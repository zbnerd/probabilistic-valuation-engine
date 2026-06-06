# ADR-1090: Synchronizer — Complete infra extraction from DefaultChunkProcessor

- Status: Accepted
- Date: 2026-06-06
- Owner: synchronizer

## 1. Background / Problem

### Background

PR #1143 (commit `974efe438`) decomposed `DefaultChunkProcessor.process` into three stage classes:
- `ChunkDataReader.read` (file read + OCID resolution)
- `ChunkDocumentTransformer.transform` (grouped results → documents)
- `ChunkDocumentWriter.write` (DB upsert + Redis ranking)

`process()` shrank from ~40 mixed lines to ~20 orchestrator lines. The decomposition is incomplete: `process()` still calls `metrics.*` (lines 23, 25, 26, 27, 28) and a `log.info` (line 23) inline, mixing infra concerns into what should be pure orchestration.

### Problem

`DefaultChunkProcessor.process` is supposed to be orchestration-only ("transform → persist → metrics" per issue #1090). It still owns:
- A `log.info` reporting grouped→document count
- `metrics.incrementDocuments(documentCount)` / `metrics.incrementItems(itemCount)` — emit metrics about a stage's output
- `metrics.recordChunkSize(...)` — emit combined metric
- `metrics.recordDocumentEquipment(...)` per-prepped — emit per-item metric

These are observations about what the transformer produced, not orchestration. They make `process()` look like it knows the shape of the transformer's output, which couples the orchestrator to stage internals.

### Goal

`DefaultChunkProcessor.process` becomes pure orchestration: call three stages, return result. No metrics, no log, no awareness of intermediate shapes.

## 2. Decision

> The transformer emits the per-chunk and per-document metrics, since it already owns the data shape that the metrics describe. The orchestrator calls three stage methods and returns a result.

### Concretely

```text
DefaultChunkProcessor.process(input):
    grouped   ← dataReader.read(input.objectKey)
    transform ← transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)
    writer.write(input.sourceRunId, input.sourceChunkId, transform.prepped)
    return ChunkProcessResult(documentCount, itemCount, input.resultCount)
```

`transform()` takes ownership of:
- `log.info("[Synchronizer] grouped {} results into {} documents", ...)` — moved from process
- `metrics.incrementDocuments(documentCount)` — moved from process
- `metrics.incrementItems(itemCount)` — moved from process
- `metrics.recordChunkSize(documentCount, itemCount)` — moved from process
- `metrics.recordDocumentEquipment(equipmentCount)` per prepped — moved from process

`writer.write` already owns the bulk-upsert timer + ranking write.

`reader.read` already owns the file-read timer.

`process()` becomes 5 lines (or fewer) of straight-line calls.

### Why not a decorator

Two options for separating metrics from stages:

| Option | Trade-off |
| --- | --- |
| A: Push metrics into the stage that owns the data | Simple, no new abstraction, no double-dispatch. Stage classes are still focused — they grow from "do work" to "do work and report". |
| B: Decorator (e.g. `MetricsAwareChunkProcessor` wrapping `ChunkProcessor`) | Pure separation of concerns, but metrics calls depend on the `TransformResult` shape — decorator must also know that shape. Two indirections for no functional benefit. |

Option A is recommended. The stage classes are not "domain" — they already call `metrics.fileReadTimer()` / `mainUpsertTimer()`. Adding three more metric calls in the transformer does not break the separation, and it eliminates a leaky abstraction (orchestrator peeking at stage output).

## 3. Trade-offs

### Sensitivity

- Test coverage of metric emission: the existing test (`DefaultChunkProcessorTest`) mocks stages. After this change, the transformer test must cover the metric calls. The orchestrator test simplifies (fewer mock setups).
- Logging format: the `log.info` line is consumer-visible in production. Moving it from `process()` to `transformer.transform()` changes the logger name shown in `[Synchronizer]` — wait, no: the line already says `[Synchronizer]` literally (not the logger name), so the visible output is identical.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Push metrics into stage | Orchestrator is pure orchestration; no leaky shape; simpler test mocks | Stage classes couple to `SynchronizerMetrics` (already true for reader and writer) |

### Risk

- **None measurable**: the metrics output is byte-identical (same calls, same arguments, same ordering within a chunk). The log line is byte-identical. Behavior is unchanged.
- Slight risk: if the orchestrator ever needs to do post-write work (e.g. emit a "chunk done" gauge), the orchestrator stays the right place — but that's hypothetical and YAGNI.

### Non-Risk

- The earlier separation risk (mixed domain/infra) is now fully resolved: orchestrator has no knowledge of stage internals.
- The `metrics` field becomes unused on `DefaultChunkProcessor`; drop the constructor param.

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| `DefaultChunkProcessor` LOC | 38 → ~20 | Remove metrics + log + constructor param |
| `ChunkDocumentTransformer` LOC | 49 → ~55 | +6 lines for moved metrics + log |
| Metrics calls emitted per chunk | unchanged | Same call sites, same arguments |
| Log line emitted per chunk | unchanged | Same format, same trigger |

### Observed Result

After merging:
- `DefaultChunkProcessor.process` contains only `read → transform → write → return`.
- Constructor signature: `dataReader, transformer, writer` (no `metrics`).
- `./gradlew :module-synchronizer:test` passes.
- `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue` passes.
- `DefaultChunkProcessorTest` still passes (mocks stages, returns canned `TransformResult`; no behavior change to verify).
- New `ChunkDocumentTransformerTest` cases cover the moved metrics calls (or extend existing ones if a transformer test exists).

## 5. Summary

> Push the five remaining infra calls (one log + four metrics groups) from `DefaultChunkProcessor.process` into `ChunkDocumentTransformer.transform`, which already owns the data those calls describe. The orchestrator becomes pure 3-step orchestration.
