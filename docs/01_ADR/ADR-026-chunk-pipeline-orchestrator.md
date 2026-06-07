# ADR-026: Chunk Pipeline Orchestrator + Thin Delegate

- Status: Accepted
- Date: 2026-06-07
- Owner: synchronizer

---

## 1. Background / Problem

### Background

`DefaultChunkProcessor` mixes stage chain assembly with aggregate metrics in a single method. Issue #990 (PR2 of the #1143 stage-split) wants explicit stage chain assembly.

### Problem

- `DefaultChunkProcessor` is 38 lines today (down from 71 post-#1143), but the orchestration logic is still inline: read → transform → metrics → write.
- Aggregate metrics (`incrementDocuments`, `incrementItems`, `recordChunkSize`, `recordDocumentEquipment`) live in the processor — they are pipeline concerns, not stage concerns.
- Adding a new stage requires editing `DefaultChunkProcessor` directly.

### Goal

Extract pipeline assembly to a dedicated component. Keep behavior identical. Backward compatible with the existing `ChunkProcessor` interface consumer (`KafkaResultChunkConsumer`).

---

## 2. Decision

> Extract pipeline assembly to `ChunkPipelineOrchestrator`. Keep `DefaultChunkProcessor` as a 1-line `@Deprecated` delegate to preserve the `ChunkProcessor` contract that `KafkaResultChunkConsumer` depends on.

```text
KafkaResultChunkConsumer → ChunkProcessor (interface) → DefaultChunkProcessor (@Deprecated delegate) → ChunkPipelineOrchestrator
                                                                                                       ├── ChunkDataReader
                                                                                                       ├── ChunkDocumentTransformer
                                                                                                       ├── ChunkDocumentWriter
                                                                                                       └── SynchronizerMetrics
```

`ChunkPipelineOrchestrator` lives in `module-synchronizer/adapter/chunk/`. It takes the three existing stage beans and `SynchronizerMetrics` as constructor dependencies. Aggregate metrics move from the processor into the orchestrator. Per-stage timers stay inside the stage classes.

---

## 3. Trade-offs

### Sensitivity

* Future stage addition frequency (target: 0/quarter, expected 1-2/quarter)
* Number of stages in the chain (currently 3)
* Bean wiring count for `ChunkProcessor` interface consumers

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Orchestrator + thin delegate (this PR) | Backward compat, no consumer change, isolated blast radius | Two beans for one job until follow-up removal |
| Full removal of `DefaultChunkProcessor` | One bean, single source of truth | Touches `KafkaResultChunkConsumer` and the `ChunkProcessor` interface, wider diff |
| Port interface migration (full spec PR2) | Generic stage composition across modules | Requires all 3 stage classes to be `suspend` + change signatures |

### Risk

* Two beans (`DefaultChunkProcessor` + `ChunkPipelineOrchestrator`) doing the same thing for some time — mitigated by `@Deprecated` on the delegate and a follow-up removal issue.
* Spring autowires `ChunkProcessor` → `DefaultChunkProcessor` by interface. If both are registered, ambiguity. Verified: only `DefaultChunkProcessor` implements `ChunkProcessor`, so single binding.
* `BasicChunkIngestionService` is a separate code path (uses `fileReader.readInBatches` + `repository.bulkUpsert` + `upsertOcidFromBasicRecords`); not affected by this ADR.

### Non-Risk

* Stage class internals unchanged.
* `ChunkProcessor` interface unchanged.
* `ChunkProcessInput` / `ChunkProcessResult` / `TransformResult` types unchanged.
* Concurrency model (Semaphore, executor) unchanged.
* JSON serialization / metrics emission surface unchanged (same metric methods, same args).

---

## 4. Result / Evidence

To be filled after merge: test counts, line counts, follow-up issue opened.

---

## 5. Summary

> Add `ChunkPipelineOrchestrator` and reduce `DefaultChunkProcessor` to a 1-line `@Deprecated` delegate. Port interface migration deferred to a follow-up issue.
