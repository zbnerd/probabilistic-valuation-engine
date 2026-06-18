# Phase Trigger Endpoint: HTTP API for Single-Phase Standalone Runs

- **Status**: Approved
- **Date**: 2026-06-18
- **Owner**: solo dev
- **Issue**: #1289

> Per-phase trigger endpoint, refactored `ExternalApiScheduler`, per-phase slots in `RunStatusTracker`, `ItemEquipmentContinuousLoop` retired. Daily trigger refactored to chain 4 per-phase calls.

---

## 1. Background / Problem

### Background

`POST /api/internal/trigger/daily` is the single HTTP entry point to ext-api and always runs the full chain (RANKING_FETCH → OCID_LOOKUP → CHARACTER_BASIC → ITEM_EQUIPMENT) under one composite runId, managed by `ExternalApiScheduler.triggerDailyRefresh`. Per-phase logic is injected as separate phase beans but called inline. `ItemEquipmentContinuousLoop` runs ITEM_EQUIPMENT as an independent background loop on startup, separately tracked via `RunStatusTracker.startItemEquipmentCycle(runId)`.

`RunStatusTracker` is single-slot: one `currentRun: AtomicReference<RunStatus>` plus one `lastCompletedRun`. No per-phase state, no per-phase concurrency.

### Problem

1. Operators cannot run a single phase in isolation (e.g. rerun CHARACTER_BASIC against an existing OCID cache without re-fetching ranking data).
2. Per-phase retry is impossible because failures collapse into a single composite runId.
3. `ItemEquipmentContinuousLoop` runs unconditionally on startup, racing the daily chain for ITEM_EQUIPMENT phase ownership.
4. Single-slot tracker enforces only global 409; no per-phase occupancy.

### Goal

Add `POST /api/internal/trigger/phase/{phaseName}` that runs one phase standalone with explicit upstream binding. Refactor scheduler to expose per-phase entry points. Retain daily trigger external behavior unchanged.

### Non-Goal

- Airflow DAG split (`daily_collection_pipeline` keeps calling `/trigger/daily`).
- Stale-slot TTL or operator `?force=true` override.
- Per-environment credentials or cross-region replication.

---

## 2. Decision

> **Per-phase HTTP trigger endpoint, per-phase slots in `RunStatusTracker`, extracted per-phase methods on `ExternalApiScheduler`, `ItemEquipmentContinuousLoop` deleted, daily trigger refactored to chain 4 per-phase calls (each with its own runId), `X-Upstream-Run-Id` header binds phase runs to upstream output.**

```text
HTTP layer
  POST /api/internal/trigger/phase/{phaseName}
    headers: X-Airflow-Run-Id, X-Upstream-Run-Id
    202 STARTED {runId} | 400 INVALID_PHASE | 400 MISSING_UPSTREAM | 409 ALREADY_RUNNING

  POST /api/internal/trigger/daily   (unchanged externally)
    internally: triggerPhase(RANKING_FETCH)
              + triggerPhase(OCID_LOOKUP, upstreamRunId=r1)
              + triggerPhase(CHARACTER_BASIC, upstreamRunId=r2)
              + triggerPhase(ITEM_EQUIPMENT, upstreamRunId=r3)

  GET /api/internal/run-status
    slots: Map<PipelinePhase, RunStatus>   // active runs per phase
    lastCompleted: Map<PipelinePhase, RunStatus>   // most-recent per phase
    (legacy `current` field kept as alias)

Scheduler
  ExternalApiScheduler
    + triggerPhase(phase, runId, upstreamRunId): PhaseResult
    + runRankingPhase(runId, upstreamRunId)
    + runOcidPhase(runId, upstreamRunId)
    + runCharBasicPhase(runId, upstreamRunId)
    + runItemEquipmentPhase(runId, upstreamRunId)
    - triggerDailyRefresh → chains 4 triggerPhase calls
    - ItemEquipmentContinuousLoop dependency removed; loop body folds into runItemEquipmentPhase

Tracker
  RunStatusTracker
    - currentRun: AtomicReference<RunStatus>
    + slots: ConcurrentHashMap<PipelinePhase, AtomicReference<RunStatus>>
    + acquirePhaseSlot(phase, runId): RunStatus  // CAS, throws if occupied
    + releasePhaseSlot(phase, runId)
    + hasNonTerminalRun(phase): RunStatus?

  RunStatus
    + triggeredPhase: PipelinePhase   // which phase this slot tracks

PipelinePhase enum (unchanged)
  IDLE, RANKING_FETCH, OCID_LOOKUP, OCID_CACHE_REFRESH, CHARACTER_BASIC,
  CHARACTER_BASIC_DONE, ITEM_EQUIPMENT, COMPLETED, FAILED
```

### Flow: per-phase trigger

```text
1. Validate phaseName ∈ {RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC, ITEM_EQUIPMENT}
   → 400 INVALID_PHASE
2. Validate upstreamRunId present for non-RANKING_FETCH phases
   → 400 MISSING_UPSTREAM
3. tracker.hasNonTerminalRun(phase)
   → 409 ALREADY_RUNNING {runId: existing}
4. tracker.acquirePhaseSlot(phase, runId)   // CAS
5. Submit to internalApiExecutor:
     scheduler.triggerPhase(phase, runId, upstreamRunId)
       ├─ acquireLock(phase)
       ├─ transitionPhase(phase)
       ├─ phaseBean.execute(runId, upstreamRunId)
       │   on success → completeRun(runId, chunks, records)
       │   on failure → failRun(runId, ex.message)
       ├─ releaseLock(phase)               // finally
       └─ releasePhaseSlot(phase, runId)   // whenComplete
6. Return 202 STARTED {runId}
```

### Flow: daily trigger

```text
triggerDailyRefresh(dailyRunId):
  r1 = UUID   // RANKING_FETCH runId
  triggerPhase(RANKING_FETCH, r1, null)
    .thenCompose(_ -> {
      r2 = UUID
      triggerPhase(OCID_LOOKUP, r2, upstreamRunId=r1)
    })
    .thenCompose(_ -> {
      r3 = UUID
      triggerPhase(CHARACTER_BASIC, r3, upstreamRunId=r2)
    })
    .thenCompose(_ -> {
      r4 = UUID
      triggerPhase(ITEM_EQUIPMENT, r4, upstreamRunId=r3)
    })
```

---

## 3. Trade-offs

### Sensitivity

- Per-phase concurrency: 4 slots available, bounded by JVM executor (currently `internalApiExecutor`).
- Slot acquisition atomicity: `AtomicReference.compareAndSet` on slot entry; safe under concurrent POSTs.
- Phase bean lock: existing per-phase JVM `ReentrantLock` retained; extended to per-phase granularity (one lock per `PipelinePhase`).
- Header propagation: `X-Upstream-Run-Id` must be threaded through `phaseBean.execute(runId, upstreamRunId)` to phase-specific logic that currently reads artifacts by latest runId.

### Trade-off

| Choice | Get | Lose |
| --- | --- | --- |
| Per-phase slots vs single-slot | Operators can run phases in parallel; per-phase 409; clearer retry semantics | One more concurrent primitive; response payload grows |
| Flatten daily into 4 phase-trigger calls | No parent linkage state; each slot independently traceable | Daily operator loses "one runId to grep" affordance — must chain via `upstreamRunId` |
| `ItemEquipmentContinuousLoop` deleted | Single source of truth for ITEM_EQUIPMENT phase | Background auto-resume on startup removed (must HTTP-trigger) |
| `X-Upstream-Run-Id` header (vs implicit latest scan) | Deterministic, auditable, O(1) | Caller must thread runId through chain manually |

### Risk

- `ItemEquipmentContinuousLoop` deletion may break consumers that relied on auto-resume (e.g. Airflow `wait_for_item_equipment_cycle` consuming `synchronizer.chunk.consumed` for ITEM_EQUIPMENT completion). Confirm the consumer reads the Kafka topic directly, not via the loop.
- Per-phase lock granularity means RANKING_FETCH and CHARACTER_BASIC can run in parallel; both touch overlapping MinIO prefixes (`runs/*`). Concurrency on shared prefixes is the implementer's responsibility per existing pattern.
- `OCID_CACHE_REFRESH` and `IDLE` declared in `PipelinePhase` but unused today; refactor must not delete them.

### Non-Risk

- Single-slot model risk (only-one-run-at-a-time) eliminated; per-phase slots independently owned.
- Daily trigger external behavior unchanged; no Airflow DAG modification.
- No new module boundary; `module-external-api` only.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After (predicted) | Notes |
| --- | ---: | ---: | --- |
| Files touched | 0 | 5 | scheduler, tracker, status, controller, delete loop |
| Lines added | 0 | ~250 | 4 methods + slot map + 1 controller route + RunStatus field |
| Lines deleted | 0 | ~120 | ItemEquipmentContinuousLoop + inline chain |
| Phase trigger surfaces | 1 (daily only) | 5 (daily + 4 phases) | per-phase HTTP entry |
| Concurrent active phases | 1 (single-slot) | 4 (per-phase slots) | bounded by executor |
| Airflow DAG LOC | 239 | 239 | unchanged |

### Observed Result

- Unit tests pass for tracker per-slot acquire/release, scheduler per-phase dispatch, controller 400/409/202 mapping.
- Manual smoke: `curl POST /trigger/phase/{RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC, ITEM_EQUIPMENT}` in sequence with `X-Upstream-Run-Id` chain; `mc ls local/maple-expectation/runs/<runId>/` shows chunks for each.
- `POST /trigger/daily` still works; `/run-status` shows 4 slots transiently populated then released.

---

## 5. Summary

> Per-phase HTTP trigger endpoint with per-phase slots in `RunStatusTracker`, extracted per-phase methods on `ExternalApiScheduler`, retired `ItemEquipmentContinuousLoop`, daily trigger chained from per-phase primitives — all within `module-external-api`.

---

## Open Items for Implementation

1. **Confirm `ItemEquipmentContinuousLoop`'s Kafka consumer role.** Before deletion, verify whether it owns `synchronizer.chunk.consumed` ITEM_EQUIPMENT filtering or whether scheduler's `KafkaSnapshotChunkReadyConsumer` already handles it. If the former, migrate to scheduler-level consumer.
2. **Phase bean signatures.** Today's phase beans take only `runId`. Verify each can accept `upstreamRunId` without breaking internal state. If a phase needs additional scope inputs (e.g. OCID cache rebuild flag), expose via `@RequestParam` or fixed default in `runXxxPhase` method.
3. **`/run-status` legacy field.** `current` field as alias for one of the slots (RANKING_FETCH or most-recent-active) until deprecation window closes. Document in controller docstring.
