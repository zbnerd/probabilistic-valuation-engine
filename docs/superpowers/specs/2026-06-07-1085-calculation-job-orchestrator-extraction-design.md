# ADR-1085: Extract OCID + API Orchestrators from CalculationJobService

- Status: Accepted
- Date: 2026-06-07
- Owner: calculator
- Issue: #1085 (step 2/2 of #1073)
- Depends on: #1073 (CalculationDispatchService) — already merged via PR #1182

---

## 1. Background / Problem

### Background

Step 1 (#1073 / PR #1182) extracted `CalculationDispatchService` from `CalculationJobService`. The facade now mixes three remaining concerns:

- **OCID resolution pipeline** — `requestOcidResolve`, `handleOcidFailure` (uses `eventAppender` + `ocidResolveTopic`)
- **API data pipeline** — `resolveOcidAndEnqueueApiData`, `saveSnapshotAndMarkReady`, `markSnapshotReady`, `handleApiFailure` (uses `eventAppender` + `nexonApiRequestTopic` + `nexonApiResponseTopic` + `snapshotRepository`)
- **Snapshot persistence** — `saveInputSnapshotAndMarkReady` (uses `snapshotRepository` only)

### Problem

`CalculationJobService` is 177 lines with 11 public methods. Each orchestrator concern shares no dependency with the others. Future changes to OCID retry policy force edits in the same class as API failure handling. New contributors must understand the full surface to touch one concern.

### Goal

- Group OCID and API pipeline methods into dedicated services
- Reduce `CalculationJobService` to a thin facade (~50 lines) covering job creation + snapshot persistence + dispatch delegation
- Preserve all `@Transactional` semantics, all public behavior, all caller-visible contracts

---

## 2. Decision

> Extract two new orchestrators from `CalculationJobService` and update all 8 callers to inject them directly.

### Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  CalculationJobService (facade, ~50 lines)                        │
│    - createJob                                                    │
│    - createOrFindActiveJob                                        │
│    - saveInputSnapshotAndMarkReady                                │
│    - dispatch* (delegates to CalculationDispatchService)          │
│    - retry* (delegates to CalculationDispatchService)             │
└──────────┬──────────────────────────────────┬────────────────────┘
           │ delegates (inherited)           │ delegates (inherited)
           ▼                                  ▼
┌────────────────────────┐   ┌────────────────────────────────────┐
│ OcidResolution         │   │ ApiDataFetchOrchestrator            │
│ Orchestrator (new)     │   │ (new)                               │
│  - requestOcidResolve  │   │  - resolveOcidAndEnqueueApiData     │
│  - handleOcidFailure   │   │  - saveSnapshotAndMarkReady         │
│  - resolveOcidInPlace  │   │  - markSnapshotReady (internal)     │
│ deps: jobPort,         │   │  - handleApiFailure                 │
│   eventAppender,       │   │ deps: jobPort, eventAppender,       │
│   ocidResolveTopic     │   │   snapshotRepository,               │
└────────────────────────┘   │   nexonApiRequestTopic,             │
                              │   nexonApiResponseTopic            │
                              └────────────────────────────────────┘
```

### Why this split

- Mirrors the existing `CalculationDispatchService` pattern (single-concern service with explicit dependency list)
- Each orchestrator owns one MQ topic family (`ocidResolveTopic` vs `nexonApi*Topic`)
- Snapshot persistence stays in the facade (no event/topic involvement, single method)
- Job creation stays in the facade (no MQ, no snapshot writes)
- Dispatch delegation stays in the facade (already extracted to `CalculationDispatchService`)

### Callers (8 files, 13 method calls)

| Caller | Method called | New target |
|--------|---------------|-----------|
| `OcidResolveWorker` | `handleOcidFailure` | `OcidResolutionOrchestrator` |
| `OcidResolveWorker` | `resolveOcidAndEnqueueApiData` | `ApiDataFetchOrchestrator` |
| `NexonApiWorker` | `handleApiFailure` | `ApiDataFetchOrchestrator` |
| `NexonApiWorker` | `saveSnapshotAndMarkReady` | `ApiDataFetchOrchestrator` |
| `ExternalApiWorker` | `resolveOcidInPlace` | `OcidResolutionOrchestrator` |
| `ExternalApiWorker` | `saveInputSnapshotAndMarkReady` | `CalculationJobService` (unchanged) |
| `ExternalApiWorker` | `saveInputSnapshotAndDispatchCalculation` | `CalculationJobService` (unchanged) |
| `ExternalApiWorker` | `retryExternalApiJob` | `CalculationJobService` (unchanged) |
| `CalculationRequestedWorker` | `dispatchCalculationCompleted` | `CalculationJobService` (unchanged) |
| `CalculationJobTimeoutScanner` | `retryOcidResolvingJob` | `CalculationJobService` (unchanged) |
| `CalculationJobTimeoutScanner` | `retryApiRequestedJob` | `CalculationJobService` (unchanged) |
| `AbstractExpectationCalcWorker` | `createOrFindActiveJob` | `CalculationJobService` (unchanged) |
| `AbstractExpectationCalcWorker` | `dispatchToExternalApi` | `CalculationJobService` (unchanged) |

---

## 3. Trade-offs

### Sensitivity

- **Spring bean wiring**: Two new `@Service` beans must be discoverable. Constructor injection is the only path.
- **`@Transactional` propagation**: Each orchestrator's `@Transactional` annotation must remain at method level (no propagation surprises).
- **Caller count**: 8 files; any miss breaks compilation.
- **Test surface**: `CalculationJobServiceTest` currently constructs the service directly. The facade shrinks; tests for new orchestrators should mirror the existing `CalculationDispatchServiceTest` pattern.

### Trade-off

| Choice | Gained | Sacrificed |
|--------|--------|------------|
| Extract two orchestrators (not three) | Matches the issue's stated decomposition; avoids over-engineering | `saveInputSnapshotAndMarkReady` remains in the facade with snapshot I/O — could become a third service in a follow-up |
| `resolveOcidInPlace` moves to `OcidResolutionOrchestrator` (not stays in facade) | Single home for all OCID transitions | One additional caller update (`ExternalApiWorker`) |
| Direct constructor injection in callers (no facade re-export) | Each worker depends on the smallest surface it needs | Caller constructor lists grow when both orchestrators are needed (`OcidResolveWorker`, `NexonApiWorker`) |

### Risk

- **Circular dependency**: avoided because `CalculationJobService` loses its `eventAppender` + `*Topic` dependencies; it only retains `jobPort`, `snapshotRepository`, `dispatchService`. Orchestrators depend on `jobPort` and the topics — not on the facade.
- **Test gap**: `CalculationJobServiceTest` may need updates; new orchestrators should have parallel tests.
- **`@Transactional` regression**: each extracted method keeps its existing annotation. No new methods introduced.

### Non-Risk

- Public method signatures of `CalculationJobService` for facade-retained methods are unchanged → no API drift for `dispatch*`/`retry*`/`createJob`/`createOrFindActiveJob`/`saveInputSnapshotAndMarkReady`.
- No new MQ topics, no new event factories.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
|--------|-------:|------:|-------|
| `CalculationJobService.kt` lines | 177 | ~55 | facade |
| `OcidResolutionOrchestrator.kt` lines | — | ~40 | new |
| `ApiDataFetchOrchestrator.kt` lines | — | ~65 | new |
| Methods in facade | 11 | 5 | createJob, createOrFindActiveJob, saveInputSnapshotAndMarkReady, dispatch* delegations, retry* delegations |
| Touched caller files | — | 4 | OcidResolveWorker, NexonApiWorker, ExternalApiWorker, CalculationJobServiceTest |

### Observed Result

*(to be filled after implementation)*

- `./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue` passes
- `./gradlew :module-infra:test` passes
- No behavioral change verified by existing test suite (unit tests cover the delegations)

---

## 5. Summary

> Two orchestrators (`OcidResolutionOrchestrator`, `ApiDataFetchOrchestrator`) absorb the OCID and API pipeline concerns from `CalculationJobService`; the facade retains job creation, snapshot persistence, and dispatch delegation; 4 caller files updated with no behavioral change.
