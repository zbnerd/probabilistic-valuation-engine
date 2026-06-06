# Issue 1073 — CalculationDispatchService Extraction Design (step 1/2)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Spec: [#1073](https://github.com/zbnerd/probabilistic-valuation-engine/issues/1073)
- Branch: `refactor/1073-calculation-dispatch-extraction`

---

## 1. Background / Problem

### Background

`module-infra/.../job/CalculationJobService.kt` is **245 lines** with **7 constructor fields** and **16 methods**. It mixes 4 orchestration concerns:

1. **Job lifecycle creation / lookup** — `createJob`, `createOrFindActiveJob`
2. **Status transition + event append** — `requestOcidResolve`, `resolveOcidAndEnqueueApiData`, `markSnapshotReady`, `markSnapshotReadyInternal`
3. **Failure handling** — `handleApiFailure`, `handleOcidFailure`
4. **PGMQ dispatch** — `retryOcidResolvingJob`, `retryApiRequestedJob`, `dispatchToExternalApi`, `dispatchCalculationCompleted`, `saveInputSnapshotAndDispatchCalculation`, `retryExternalApiJob`

The **6 dispatch methods** use only `jobPort` + `pgmqClient` and never touch `eventAppender`, the topic fields, or `snapshotRepository` (except `saveInputSnapshotAndDispatchCalculation` which also writes to `snapshotRepository`). They form the cleanest extraction boundary.

This is **step 1 of 2**. Step 2 (out of scope for this PR) will update external callers (`CalculationJobTimeoutScanner`, `CalculationRequestedWorker`, `AbstractExpectationCalcWorker`, `ExternalApiWorker`) to depend on `CalculationDispatchService` directly and remove the delegation in `CalculationJobService`.

### Problem

`CalculationJobService` violates Single Responsibility — one service manages job CRUD, status transitions, failure handling, and message dispatch. Each concern has different deps and change cadence, but they share one god class. The 6 dispatch methods are the natural first extraction because they have minimal coupling (only `jobPort` + `pgmqClient` + `snapshotRepository` for one method).

### Goal

Extract the 6 PGMQ-dispatch methods into a new `CalculationDispatchService`. `CalculationJobService` delegates to it. Zero behavioral change. All existing tests pass.

---

## 2. Decision

> Create `CalculationDispatchService` containing the 6 PGMQ-dispatch methods verbatim from `CalculationJobService`. `CalculationJobService` removes `pgmqClient`, adds `dispatchService: CalculationDispatchService`, and turns the 6 methods into 1-line delegates.

### Component map (after)

```
┌──────────────────────────────────────────────────────────────────────┐
│  CalculationJobService (delegator)                                   │
│    ├── CalculationJobPort         (shared, retained)                 │
│    ├── DomainEventAppender        (unchanged)                        │
│    ├── OcidResolveTopic           (unchanged)                        │
│    ├── NexonApiRequestTopic       (unchanged)                        │
│    ├── NexonApiResponseTopic      (unchanged)                        │
│    ├── CalculationSnapshotRepository (unchanged)                     │
│    └── CalculationDispatchService (NEW — delegate target)            │
└──────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│  CalculationDispatchService (NEW)                                    │
│    ├── CalculationJobPort                                             │
│    └── PgmqClient                                                     │
│    Methods:                                                           │
│    - retryOcidResolvingJob                                            │
│    - retryApiRequestedJob                                             │
│    - dispatchToExternalApi                                            │
│    - dispatchCalculationCompleted                                     │
│    - saveInputSnapshotAndDispatchCalculation                          │
│    - retryExternalApiJob                                              │
└──────────────────────────────────────────────────────────────────────┘
```

### Method mapping

| Source (`CalculationJobService`) | Target (`CalculationDispatchService`) | Body |
|---|---|---|
| `retryOcidResolvingJob` | same name, same signature | Verbatim copy |
| `retryApiRequestedJob` | same name, same signature | Verbatim copy |
| `dispatchToExternalApi` | same name, same signature | Verbatim copy |
| `dispatchCalculationCompleted` | same name, same signature | Verbatim copy |
| `saveInputSnapshotAndDispatchCalculation` | same name, same signature | Verbatim copy (also uses `snapshotRepository`) |
| `retryExternalApiJob` | same name, same signature | Verbatim copy |

After the move, `CalculationJobService` keeps each of the 6 methods as a 1-line delegate:
```kotlin
fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean =
    dispatchService.retryExternalApiJob(jobId, errorCode)
```

---

## 3. Trade-offs

### Sensitivity

- **`@Transactional` propagation** — all 6 dispatch methods carry `@Transactional(value = "transactionManager", readOnly = false)`. The annotation must move to `CalculationDispatchService` methods (the delegator's `@Transactional` on a 1-line call is a no-op when the call site isn't proxied — Spring AOP doesn't proxy self-invocations). Verification: existing tests don't observe transaction behavior, so behavior is unchanged either way.
- **`pgmqClient` field ownership** — only the 6 dispatch methods reference `pgmqClient`. After extraction, `CalculationJobService` no longer needs it.
- **`snapshotRepository` ownership** — `saveInputSnapshotAndDispatchCalculation` references it. Either move `snapshotRepository` to `CalculationDispatchService` (preferred — single-concern) or share it. Per principle of single-concern, move it to the dispatch service.
- **Test mocks** — `CalculationJobServiceTest` mocks `pgmqClient` and `snapshotRepository`. After the move, these mocks become unused for the dispatch path (still used for `markSnapshotReady`, `saveSnapshotAndMarkReady`, `saveInputSnapshotAndMarkReady` which stay in `CalculationJobService`).

### Trade-off

| Choice | Gained | Given up |
|---|---|---|
| Delegation pattern (per issue) | No caller changes; step 2 will be trivial | Two methods with the same name temporarily |
| `snapshotRepository` moves to dispatch service | Single-concern per service | Slightly larger new service (3 deps) |
| Keep existing `CalculationJobServiceTest` unchanged | Minimal diff, tests pass via delegation path | Tests verify delegation, not real logic |
| New `CalculationDispatchServiceTest` | Direct coverage of new service | Slight test count growth |

### Risk

- **`@Transactional` boundary change** — the dispatcher methods now have their own proxy boundary. Behavior is unchanged because the inner logic is identical and no caller relies on propagation through the delegate. (The delegate's `@Transactional` would be ineffective anyway due to AOP self-invocation, so this is actually a correctness improvement.)
- **Constructor signature change** — `CalculationJobService` constructor changes (pgmqClient → dispatchService). Caller changes: NONE (Spring auto-wires). Test changes: `CalculationJobServiceTest` constructor must pass `dispatchService` (mock).

### Non-Risk

- Method signatures unchanged → all 4 caller files (`CalculationJobTimeoutScanner`, `CalculationRequestedWorker`, `AbstractExpectationCalcWorker`, `ExternalApiWorker`) need no changes.
- 5 existing `retryExternalApiJob` tests in `CalculationJobServiceTest` continue to work — they call `service.retryExternalApiJob(...)` which delegates. The test would verify delegation behavior (and indirectly the real logic via the mock chain), which is acceptable for step 1.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
|---|---:|---:|---|
| `CalculationJobService.kt` lines | 245 | ~150 | After extracting 6 methods (logic moved verbatim, delegators are 1-liners) |
| `CalculationJobService` constructor fields | 7 | 7 | Swap: `-pgmqClient, +dispatchService` |
| `CalculationJobService` methods | 16 | 16 | 10 stay, 6 become delegates |
| New components | 0 | 1 | `CalculationDispatchService` |
| New test file | 0 | 1 | `CalculationDispatchServiceTest` |
| Caller changes | — | 0 | Step 2 (out of scope) |

### Verification commands

```bash
./gradlew :module-infra:compileKotlin compileJava --continue
./gradlew :module-infra:test
```

### Runtime verification (per project workflow)

Per `.claude/rules/workflow-rules.md` §10, runtime server check is required before merge. Since this refactor has no behavioral change, the verification focuses on observing the same job lifecycle logs in the synchronizer pipeline.

---

## 5. Summary

> Extract `CalculationJobService`'s 6 PGMQ-dispatch methods into a new `CalculationDispatchService`. `CalculationJobService` delegates to the new service (step 1 of 2 — step 2 will remove the delegation and migrate callers).

---

## Appendix A — File structure

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/job/
├── CalculationJobService.kt        (MODIFIED — 6 methods become delegates)
└── CalculationDispatchService.kt   (NEW — 6 methods verbatim from above)

module-infra/src/test/kotlin/maple/expectation/infrastructure/job/
├── CalculationJobServiceTest.kt    (MODIFIED — swap pgmqClient mock for dispatchService mock)
└── CalculationDispatchServiceTest.kt (NEW — focused unit tests for 6 methods)
```

## Appendix B — Key signatures

```kotlin
// CalculationDispatchService.kt
@Service
class CalculationDispatchService(
    private val jobPort: CalculationJobPort,
    private val pgmqClient: PgmqClient,
    private val snapshotRepository: CalculationSnapshotRepository,
) {
    @Transactional(value = "transactionManager", readOnly = false)
    fun retryOcidResolvingJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean { ... }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryApiRequestedJob(jobId: UUID, userIgn: String, presetNo: Int): Boolean { ... }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchToExternalApi(jobId: UUID, userIgn: String, presetNo: Int) { ... }

    @Transactional(value = "transactionManager", readOnly = false)
    fun dispatchCalculationCompleted(payload: CalculationCompletedPayload) { ... }

    @Transactional(value = "transactionManager", readOnly = false)
    fun saveInputSnapshotAndDispatchCalculation(
        snapshotEntity: CalculationSnapshotEntity,
        jobId: UUID,
        snapshotId: UUID,
        payload: CalculationRequestedPayload,
    ): Boolean { ... }

    @Transactional(value = "transactionManager", readOnly = false)
    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean { ... }
}
```

```kotlin
// CalculationJobService.kt — refactored (sketch)
@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val dispatchService: CalculationDispatchService,
) {
    // ... 10 unchanged methods ...

    fun retryExternalApiJob(jobId: UUID, errorCode: String = "EXTERNAL_API_ERROR"): Boolean =
        dispatchService.retryExternalApiJob(jobId, errorCode)

    // ... 5 more 1-line delegates ...
}
```
