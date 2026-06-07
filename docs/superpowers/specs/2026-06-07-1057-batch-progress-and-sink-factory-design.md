# ADR-1057: BatchProgress + EndpointSinkFactory in external-api phases

- Status: Accepted
- Date: 2026-06-07
- Owner: external-api
- Issue: #1057

---

## 1. Background / Problem

### Background

Issue #1057 was opened when `SnapshotFetchPhase` was the single per-endpoint phase, before the #986 phase split. After PR #1183 (issue #986), `SnapshotFetchPhase` was dropped and replaced by `CharacterBasicFetchPhase` + `ItemEquipmentFetchPhase`. The `processBatch` 11-arg signature is now `BatchFetchSupport.processBatch` with 7 args.

The remaining work the issue calls out is still real:

- The 4 accumulator state vars (`successCount`, `failCount`, `lastProgressLog`, `start`) are duplicated in `OcidLookupPhase.processBatchSuspend` and `BatchFetchSupport.processBatch` — different signatures, same shape.
- `ChunkedSnapshotSink` is constructed inline (9 args) in `CharacterBasicFetchPhase` and `ItemEquipmentFetchPhase`; `RankingSnapshotSinkFactory` is the only factory and it is hard-coded to the ranking publisher qualifier.

### Problem

- `OcidLookupPhase.processBatchSuspend` (38 lines) carries the same accumulator pattern that `BatchFetchSupport.processBatch` already implements. Future batch-state changes must be made in two places.
- Adding a new endpoint phase requires re-typing the 9-arg `ChunkedSnapshotSink(...)` constructor; copy-paste drift risk between `CharacterBasicFetchPhase` and `ItemEquipmentFetchPhase`.
- `RankingSnapshotSinkFactory` couples sink construction to a specific publisher qualifier; a future second qualifier path will fork the factory.

### Goal

- One data class for batch progress state, shared by `OcidLookupPhase` and `BatchFetchSupport`.
- One factory for `ChunkedSnapshotSink` used by all three endpoint phases.

---

## 2. Decision

> Introduce `BatchProgress` and `EndpointSinkFactory`. Migrate `OcidLookupPhase` to use `BatchProgress`. Migrate `CharacterBasicFetchPhase` + `ItemEquipmentFetchPhase` to use `EndpointSinkFactory`.

### Architecture

```text
                                            ┌─────────────────────────────────┐
CharacterBasicFetchPhase                   │  EndpointSinkFactory            │
  └─→ ChunkedSnapshotSink ←────────────────┤  - createForCharacterBasic()    │
ItemEquipmentFetchPhase                    │  - createForItemEquipment()     │
  └─→ ChunkedSnapshotSink ←────────────────┤  - createForRanking() (replaces │
RankingFetchPhase                          │    RankingSnapshotSinkFactory)  │
  └─→ ChunkedSnapshotSink ←────────────────┘                                 │
                                            └─────────────────────────────────┘

OcidLookupPhase.processBatchSuspend          BatchFetchSupport.processBatch
  └─→ BatchProgress (shared)  ←────────────── └─→ BatchProgress (shared)
```

### What changes

1. **`BatchProgress` (new)** — data class in `module-external-api/.../scheduler/phase/`:
   - `val successCount: Int`
   - `val failCount: Int`
   - `val lastProgressLog: Int`
   - `val start: Instant`
   - Helper method `shouldLogProgress(): Boolean` and `markLogged(): BatchProgress`
   - Implemented as immutable data class; accumulators are simple ints updated by copy (`progress = progress.copy(successCount = ...)`).

2. **`EndpointSinkFactory` (new)** in `module-external-api/.../snapshot/`:
   - Single `@Component` with three publisher qualifiers (characterBasic, itemEquipment, ranking) injected.
   - Three methods: `createForCharacterBasic(runDir)`, `createForItemEquipment(runDir)`, `createForRanking(runDir)`.
   - Owns `objectMapper`, `chunkingProperties`, `volumeMetrics`, `clock`.
   - Replaces `RankingSnapshotSinkFactory` (deprecated, kept for one cycle if any caller remains, then removed).

3. **`OcidLookupPhase` migration**:
   - Replace local `var successCount/failCount/lastProgressLog/start` with `BatchProgress` instance.
   - Loop body updates via `.copy()`.
   - `processBatchSuspend` becomes ~10 lines shorter.

4. **`BatchFetchSupport.processBatch` migration**:
   - Same migration: replace local vars with `BatchProgress` instance.
   - `processBatch` signature unchanged (7 args) — the data class is internal state, not a parameter.

5. **Phase migration to `EndpointSinkFactory`**:
   - `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`: replace 11-line inline sink construction with `endpointSinkFactory.createForCharacterBasic(runDir)`.
   - `RankingFetchPhase`: switch from `RankingSnapshotSinkFactory` to `endpointSinkFactory.createForRanking(runDir)`.

### What does NOT change

- Public method signatures of any phase class
- `BatchFetchSupport.processBatch` parameter list (7 args, target met)
- `ChunkedSnapshotSink` constructor (no breaking change)
- Metrics emission (timers, counters, queue depth) — identical
- Concurrency model (Semaphore, executor, dispatcher) — identical
- Test surface (unit tests for BatchFetchSupport still apply)

---

## 3. Trade-offs

### Sensitivity

- **Data class mutation pattern**: Kotlin data classes are immutable; using `.copy()` to update `successCount` allocates per update. At PROGRESS_LOG_INTERVAL=5,000 updates per 1M items, the allocation cost is negligible (≈200 allocations per second for the OCID phase).
- **EndpointSinkFactory bean count**: All three qualifiers injected into one bean. Bean construction cost is one-time; lookup cost per `create*` call is 3 field reads.
- **Backward compat**: If `RankingSnapshotSinkFactory` has external callers (other modules), keep it as a deprecated delegate to `EndpointSinkFactory`. If no external callers, remove it in this PR.

### Trade-off

| Choice | Gained | Sacrificed |
|--------|--------|------------|
| `BatchProgress` as immutable data class | Testable, no shared mutable state, easy to log/serialize | Tiny allocation per `.copy()` |
| One `EndpointSinkFactory` with three `create*` methods | Single owner of `objectMapper` + properties | Slightly larger bean (3 publishers vs 1) |
| Replace `RankingSnapshotSinkFactory` in this PR | No dead code | Slightly wider diff (3 call sites updated vs 2) |
| Keep `BatchFetchSupport.processBatch` signature stable | No call-site updates needed for Item/Character phases | Internals now go through `.copy()` instead of `var` |

### Risk

- **OCID-loop subtle behavior change**: Local `var` updates and `BatchProgress.copy()` updates are semantically equivalent in the coroutine model (no other coroutine sees mid-loop state). Confirmed by reading `OcidLookupPhase.processBatchSuspend` and `BatchFetchSupport.processBatch`.
- **`EndpointSinkFactory` ordering**: The factory injects 3 publishers by qualifier. If a qualifier is missing in a profile (e.g., `external-api.ranking.enabled=false`), the ranking publisher bean may not exist. Mitigate by giving the ranking publisher its own `@ConditionalOnProperty` consistent with the phase.
- **Test updates**: Any test that constructs a phase class directly will need the new factory in the constructor. Most tests mock `BatchFetchSupport` and `ChunkedSnapshotSink` already.

### Non-Risk

- Concurrency primitives unchanged.
- Queue depth / submit / fetch metrics unchanged.
- `ChunkedSnapshotSink` lifecycle (`close()`, `_SUCCESS` marker) unchanged.
- Spring bean wiring (no new `@Primary` or scope changes).

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
|--------|-------:|------:|-------|
| `OcidLookupPhase.processBatchSuspend` lines | 38 | ~26 | uses `BatchProgress` |
| `BatchFetchSupport.processBatch` lines | 55 | ~45 | uses `BatchProgress` |
| `CharacterBasicFetchPhase` constructor + sink lines | 11 | 1 | factory call |
| `ItemEquipmentFetchPhase` constructor + sink lines | 11 | 1 | factory call |
| `RankingSnapshotSinkFactory` files | 1 | 0 | replaced by `EndpointSinkFactory` |
| `BatchProgress` files | 0 | 1 | new |
| `EndpointSinkFactory` files | 0 | 1 | new |

### Observed Result

*(filled after implementation)*

- `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue` → BUILD SUCCESSFUL
- `./gradlew :module-external-api:test` → BUILD SUCCESSFUL
- No behavioral change verified by existing test suite.

---

## 5. Summary

> Introduce `BatchProgress` (shared batch state data class) and `EndpointSinkFactory` (one factory for all three endpoint phase sinks). Migrate `OcidLookupPhase` and `BatchFetchSupport` to use `BatchProgress`. Migrate `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`, and `RankingFetchPhase` to use `EndpointSinkFactory`. Remove `RankingSnapshotSinkFactory`. No behavioral change.
