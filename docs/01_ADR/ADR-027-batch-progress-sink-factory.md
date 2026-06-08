# ADR-027: BatchProgress + EndpointSinkFactory + FetchProgressTracker

- Status: Accepted
- Date: 2026-06-07
- Owner: external-api

---

## 1. Background / Problem

### Background

After #986 split `SnapshotFetchPhase` into per-endpoint phases and #1082 added shared utilities (`BatchFetchSupport`, `SchedulerPhaseUtils`, `SchedulerProgressLogger`), `SnapshotFetchPhase` (the class targeted by issue #1062) no longer exists. The remaining work both #1057 and #1062 call for is:

- Sink construction is inline (9-arg `ChunkedSnapshotSink(...)`) in 3 phase classes.
- Batch accumulator state (`successCount`, `failCount`, `lastProgressLog`, `start`) is duplicated between `OcidLookupPhase` and `BatchFetchSupport`.

### Problem

- Adding a new endpoint phase requires re-typing the 9-arg sink constructor; copy-paste drift risk.
- `RankingSnapshotSinkFactory` couples to a single publisher qualifier; future 2nd-qualifier forks it.
- Batch state updates scattered across 2 sites (`OcidLookupPhase`, `BatchFetchSupport`) with `var` mutation.

### Goal

- One `BatchProgress` data class for batch state, shared.
- One `EndpointSinkFactory` for all 3 endpoint phases.
- A `FetchProgressTracker` wrapper exposing the per-fetch API surface from #1062's body.
- Remove `RankingSnapshotSinkFactory`.

---

## 2. Decision

> Cherry-pick #1057's 5 foundation commits. Complete 3 remaining migrations (RankingFetchPhase + OcidLookupPhase + BatchFetchSupport). Add `FetchProgressTracker` per #1062's spec.

```text
CharacterBasicFetchPhase                    EndpointSinkFactory
  └─→ ChunkedSnapshotSink ←────────────────┤ - createForCharacterBasic()
ItemEquipmentFetchPhase                     │ - createForItemEquipment()
  └─→ ChunkedSnapshotSink ←────────────────┤ - createForRanking() (replaces
RankingFetchPhase                           │   RankingSnapshotSinkFactory)
  └─→ ChunkedSnapshotSink ←────────────────┘

OcidLookupPhase.processBatchSuspend  BatchFetchSupport.processBatch
  └─→ BatchProgress (shared) ←────────────┘
  └─→ FetchProgressTracker (#1062)
       └─→ recordSuccess(ocid, duration, queueDepth)
       └─→ recordFailure(ocid, ex)
```

---

## 3. Trade-offs

### Sensitivity

- Future endpoint additions (target: 0/quarter, expected 1-2/year)
- Batch state changes (currently 0, expected 1-2/year)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Single EndpointSinkFactory w/ 3 create methods | One owner of objectMapper + properties, easy to add 4th endpoint | Slightly larger bean (3 publishers vs 1) |
| BatchProgress immutable data class | Testable, no shared mutable state | Tiny allocation per .copy() |
| FetchProgressTracker wrapper around BatchProgress | Per-call API surface from #1062, decoupled from data shape | One extra layer (minor) |
| Remove RankingSnapshotSinkFactory in this PR | No dead code | Slightly wider diff |

### Risk

- `EndpointSinkFactory` bean injection by qualifier — mitigated by `@ConditionalOnProperty` on each publisher (consistent with phase flags).
- `BatchProgress.copy()` in coroutine context — semantically equivalent to `var` (no other coroutine sees mid-loop state).

### Non-Risk

- Concurrency primitives unchanged.
- Metrics emission unchanged.
- `ChunkedSnapshotSink` constructor unchanged.

---

## 4. Result / Evidence

To be filled after merge: test counts, line-count deltas.

---

## 5. Summary

> Combine #1057 + #1062 into a single PR. Cherry-pick the 5 #1057 foundation commits, complete 3 remaining migrations, and add the `FetchProgressTracker` wrapper per #1062's spec.
