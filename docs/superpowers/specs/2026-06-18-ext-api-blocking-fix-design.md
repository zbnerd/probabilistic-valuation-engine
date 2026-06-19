# Design: ext-api CompletableFuture Chain — Blocking Fix

- Date: 2026-06-18
- Owner: TBD
- Status: Proposed
- Related ADR: [ADR-blocking-async-contract-cf-chain](../../01_ADR/ADR-blocking-async-contract-cf-chain.md)

---

## 1. Goal

Eliminate every blocking primitive (`.get()`, `.join()`, `runBlocking`, `Job.join()`, `CountDownLatch.await`, `Thread.sleep`, blocking semaphore acquire) in the call chain from `module-external-api` controllers through `module-infra` ports. Replace the **synchronous return contract** of `LogicExecutor`, `Lock`, `SingleFlight`, and `TieredCache` with pure `CompletableFuture<T>` end-to-end. Single PR. One mega-ADR.

## 2. Scope

### In scope

- All 4 CRITICAL ext-api sites (`InternalApiController:83,123`, `ExternalApiScheduler:188`, `OcidLookupPhase:147-148`).
- All 7 PGMQ worker + VT-worker sites in `module-infra` (`ExternalApiWorker:111,306`, `OcidResolveWorker:73`, `CalculationWorker:91`, `ResultReadyProjectionWorker:89,90,123`, `PgmqWorker:380`).
- All `LogicExecutor` / `Lock` / `SingleFlight` / `OrderedLockExecutor` / `TieredCache` sync return bridges (≈22 sites).
- Defense-line sites: `JwtAuthenticationFilter:85`, `GlobalAdmissionControl:238` busy loop, `ChunkFileManager:132` sink close, `SnapshotFailedRecordWriter:21` obj-storage read, `AuthCharacterFetchConsumer:51` Optional.get null-safety.
- Migration of all `module-calculator`, `module-synchronizer`, `module-rest-controller` callers (~15 files) from sync API to CF API.
- Grep gate (CI test) asserting zero blocking primitives in `module-infra/src/main` and `module-external-api/src/main`.

### Out of scope

- `module-core` (scan returned 0 hits).
- `module-common` (scan returned 0 hits).
- PGMQ consumer thread model redesign.
- Reactor↔Coroutines interop cleanup beyond what chain repair needs.
- Replacing virtual threads with structured concurrency.

## 3. Architecture

### Current (broken)

```
HTTP → controller → executor.submit { CF.join() }   ← blocks executor
     → scheduler  → runBlocking { suspend }          ← ties VT
     → worker     → CF.handle{}.join()               ← blocks worker
     → LogicExec  → ThrowingSupplier { CF.get() }    ← sync return
     → Lock       → executor.execute({ CF.get() })   ← sync return
     → Cache      → @Cacheable sync T (unwraps CF)   ← sync return
```

### Target

```
HTTP → controller → CF.thenAccept(202)              ← non-blocking
     → scheduler  → CF chain end-to-end              ← pure async
     → worker     → CF.thenCompose/whenComplete      ← pure async
     → LogicExec  → executeAsync() : CF<T>           ← async return
     → Lock       → lockAsync() : CF<T>              ← async return
     → Cache      → getAsync/putAsync : CF<T>        ← async return
```

### Boundary rule

| Primitive | New contract |
|---|---|
| `LogicExecutor.execute(task, ctx)` | `executeAsync(task, ctx): CF<T>` |
| `LogicExecutor.executeVoid(task, ctx)` | `executeVoidAsync(task, ctx): CF<Void>` |
| `Lock.execute(key, supplier, ctx)` | `executeAsync(key, supplier, ctx): CF<T>` |
| `SingleFlight.execute(key, supplier, ctx)` | `executeAsync(key, supplier, ctx): CF<T>` |
| `TieredCache.get(key, loader, ctx)` | `getAsync(key, loader, ctx): CF<T>` |
| `TieredCache.put(key, value, ctx)` | `putAsync(key, value, ctx): CF<Void>` |
| PGMQ `MessageHandler.handle(msg)` | `CF<AckResult>` |
| `@Cacheable` annotations | **Removed**. Caller wraps `getAsync`/`putAsync`. |
| `Controller.trigger*()` | Returns `CF<RunKey>` or `CF<Void>` directly. No `executor.submit` wrap. |

Sync variants (`execute`, `executeVoid`, `Lock.execute`, `SingleFlight.execute`, `TieredCache.get/put`, `@Cacheable` sync) are **deleted in the same PR** — no backward compat shim (per Q3 = C big-bang).

## 4. Components

### A. LogicExecutor

```kotlin
interface LogicExecutor {
    fun <T> executeAsync(task: ThrowingSupplier<CF<T>>, ctx: TaskContext): CF<T>
    fun executeVoidAsync(task: ThrowingSupplier<CF<Void>>, ctx: TaskContext): CF<Void>
    fun <T> executeWithFallbackAsync(task, fallback, ctx): CF<T>
    fun <T> executeWithFinallyAsync(task, finalizer, ctx): CF<T>
    fun <T> executeWithTranslationAsync(task, translator, ctx): CF<T>
}
```

`DefaultCheckedLogicExecutor` and `ExecutionPipeline` lose all `ThrowingSupplier { task.get() }` sites.

### B. Lock + SingleFlight

`PostgresAdvisoryLockStrategy`, `OrderedLockExecutor`, `PostgresSingleFlightStrategy`, `SingleFlightExecutor` → `executeAsync` returns `CF<T>`. Tx-scoped advisory lock (`pg_try_advisory_xact_lock`) preserved per `architecture-guardrails.md` #7.

### C. TieredCache

`@Cacheable` removed. Boundary caller wraps:
```kotlin
// Before
@Cacheable("foo") fun getFoo(id): Foo = repo.findById(id).get()

// After
fun getFooAsync(id, ctx): CF<Foo> =
    cache.getAsync("foo:$id", repo.findByIdAsync(id, ctx), ctx)
```

`EquipmentFetchProvider`, `StarforceLookupAdapter`, `CubeComputeBuffer` migrate.

### D. PGMQ Workers

```kotlin
interface MessageHandler<P> {
    fun handle(msg: PgmqMessage<P>): CF<AckResult>
}
```

Driver chain:
```kotlin
override fun handle(msg): CF<AckResult> =
    pipeline(msg.payload)
        .thenCompose(::archiveIfOk)
        .thenCompose(::resetVisibilityIfRetryable)
        .whenComplete(::logOutcome)
```

`ExternalApiWorker`, `OcidResolveWorker`, `CalculationWorker`, `ResultReadyProjectionWorker` migrate.

### E. JWT Filter

`JwtAuthenticationFilter:85` `payload.get()` → CF chain via Spring Security reactive filter (`WebFilter`). 401 on failure via CF.failedFuture.

### F. Controller + Scheduler

```kotlin
// Before
executor.submit { scheduler.triggerDailyRefresh(runId).join() }
return 202

// After
scheduler.triggerDailyRefreshAsync(runId)
    .thenAccept { log.info("done") }
    .whenComplete { _, ex -> tracker.mark(ex) }
return 202  // built before chain completes
```

`InternalApiController`, `ExternalApiScheduler`, `PhaseLoopController` migrate.

### G. Sink close path

`ChunkFileManager.closeAsync` returns `CF<Void>`:
```kotlin
override fun closeAsync(ctx): CF<Void> =
    allOf(*uploads.toTypedArray())
        .thenRun { writeManifest() }
        .thenRun { flush() }
```

### H. GlobalAdmissionControl busy loop

`while (running.get() && !Thread.interrupted)` → `awaitCancellation` or CF-cancel token, polled via `thenRun` + `CompletableFuture.runAsync` with explicit cancel.

## 5. Data flow

### Inbound (REST trigger)

```
HTTP POST /api/internal/trigger/phase/{phase}
    ▼
InternalApiController.triggerPhase(phase, runId, upstreamRunId)
    ▼
scheduler.triggerPhaseAsync(phase, runId, upstreamRunId) : CF<RunKey>
    ▼
PhaseLoopController.submitIterationAsync(phase, runKey) : CF<Void>
    ▼
LoopExecutorConfig.loopExecutor.submit(task)            [VT, non-blocking]
    ▼
Return 202 Accepted (response built before chain completes)
```

### PGMQ consume path

```
PgmqWorker.run()                                         [VT carrier]
    ▼
PgmqWorker.handle(msg) : CF<AckResult>
    ▼
ExternalApiWorker.process(payload) : CF<Void>
    ▼
pipelineAsync(payload) : CF<List<Chunk>>
    ▼
EquipmentFetchProvider.fetchAsync(ocid) : CF<ItemData>
    ▼
TieredCache.getAsync(key, loader, ctx) : CF<ItemData>
    │       ├── L1 hit       → CF.completedFuture
    │       ├── L1+L2 hit    → backfill L1, CF.completedFuture
    │       └── L1+L2 miss   → loader, .thenRun { putAsync L1+L2 }
    ▼
NexonExternalApiClientAdapter.fetch(provider, endpoint, key)
    │  WebClient.get().bodyToMono().timeout().toFuture()
    ▼
CF<ByteArray> → .thenCompose(::parse) → .thenCompose(::persist)
                → .whenComplete(::log)
```

### Lock + SingleFlight

```
TieredCache.getAsync(key, loader, ctx) : CF<T>
    ▼
PostgresSingleFlightStrategy.executeAsync(key, loader, ctx) : CF<T>
    │  pg_try_advisory_xact_lock (tx-scoped)
    │  leader   → run loader, broadcast via NOTIFY
    │  follower → await leader CF
    ▼
CF<T>
```

### Outbound (result publish)

```
Worker CF completes
    ▼
PgmqClient:Send:result_ready_queue                     [already async]
    ▼
ResultReadyProjectionWorker.handle(msg) : CF<Void>     [no .join]
    ▼
.thenCompose(::projectBatch) → .whenComplete(::log)
    ▼
CF<Void> → ack to PGMQ (thenRun success branch only)
```

### Shutdown

```
SIGTERM
    ▼
ManagedLifecycleCoordinator.stopAsync() : CF<Void>
    │  1. cancel scheduled tasks (CF.cancel)
    │  2. drain active loops (LoopExecutor.shutdownAsync)
    │  3. flush in-flight uploads (ChunkFileManager.closeAsync)
    │  4. terminate PGMQ consumers (thenRun)
    ▼
CF<Void> completed (whenComplete regardless of step outcomes)
```

## 6. Error handling

### Exception unwrap

`CF.join()/.get()` wraps in `CompletionException`. After removal, callers use `.handle()` / `.exceptionally()` / `.whenComplete()` which receive raw cause.

Per `async-patterns.md`: `instanceof` checks always look at `.cause`. Pre-existing sites that already do this stay; new sites must follow.

### Per-component failure modes

| Component | Failure | CF action |
|---|---|---|
| `NexonExternalApiClientAdapter.fetch` | HTTP 5xx, timeout | `CF.failedFuture(NexonApiException)` → caller `.exceptionally(::toRetryable)` |
| `TieredCache.getAsync` | L1+L2 both fail | `CF.failedFuture` → caller decides fail-fast or fallback |
| `PostgresSingleFlightStrategy.executeAsync` | advisory lock fail | `CF.failedFuture` → bubble up |
| `PgmqWorker.handle` | any exception | `.exceptionally { Nack(retryable=true) }` |
| `JwtAuthenticationFilter` | token invalid | `CF.failedFuture` → 401 |
| `ChunkFileManager.closeAsync` | upload fail | `.thenCompose` of best-effort flush + log → completed normally |
| `GlobalAdmissionControl` | backpressure reject | `CF.failedFuture(BackpressureException)` → caller `.handle` |
| `LogicExecutor.executeAsync` | supplier throws | `CF.failedFuture(cause)` via `ThrowingSupplier` |

### Retry / Visibility window (mq-messaging.md)

- ACK only after business success: `thenRun` on success branch.
- Visibility reset: `.exceptionally { Nack(retryable=true) }` → driver handles.
- `PgmqClient.send` already async, no change.
- No `LinkedBlockingQueue` for in-flight (already enforced).

### Backpressure

`BackpressureLimiter` (module-infra/concurrency) wraps submission:
```kotlin
backpressureLimiter.tryAcquireAsync()
    .thenCompose { permit ->
        worker.process(msg).whenComplete { backpressureLimiter.releaseAsync(permit) }
    }
```

### Shutdown error policy

- Per-step CF timeout (5s, 30s, 60s).
- Step failure → log + continue (best-effort).
- Overall CF completes regardless of step outcomes.
- **No `Thread.interrupt()` mid-chain** — interrupts break CF completion contract.

## 7. Testing

### Per project rules

- Unit tests required. `./gradlew test` must pass.
- **No integration tests** (Testcontainers banned, Issue #207).
- No `delay()` / `Thread.sleep()` → `Awaitility` + virtual time.
- H2 forbidden.

### Layers

1. **Unit (logic per port)** — `CF<T>` returned (never sync `T`); cause unwrap correct; chain order preserved.
2. **Behavioral (chain semantics)** — `executeAsync(supplier).getNow(EMPTY)` returns EMPTY before task starts (non-blocking on caller); single-flight 1× loader run for N concurrent; L1 miss + L2 hit populates L1.
3. **Static gates** — `compileKotlin compileJava --continue` clean; `gradlew test` clean.
4. **Grep gate (CI test)** — assert no `.get(` / `.join(` / `runBlocking` / `Job.join(` in `module-infra/src/main` and `module-external-api/src/main`.
5. **Runtime smoke** — `bootRun` ext-api; `curl /api/internal/trigger/phase/ranking`; 202 returned, no `ERROR` in log, phase completes; `pgmq.q_result_ready_queue` drains; active job count = 0.
6. **Load test** — `RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh`. `views_per_sec` ≥ baseline.

### Coverage

- New async code: ≥80% line coverage per port.
- Migrated sync callers: ≥70% branch coverage.

## 8. Risks

- **Big-bang PR size** — ~30+ files changed, hard to review. Mitigation: split into N atomic commits within single PR (one commit per port), pre-commit grep gate enforces no `.get()` between commits.
- **No fallback path** — any caller missed → compile error (sync API deleted). Mitigation: compile gate.
- **Test runtime regression** — async tests + virtual time may slow CI. Mitigation: limit Awaitility timeout, parallelize.
- **Load test baseline** — pre-refactor `views_per_sec` must be captured **before** code changes. Mitigation: run load test on develop branch, save to `docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md` before first commit.

## 9. Out of scope (explicit)

- `module-core` (clean).
- `module-common` (clean).
- PGMQ thread model.
- VT vs coroutine migration.
- Reactive filter migration beyond JWT.

## 10. Acceptance criteria

- [ ] `module-infra/src/main` and `module-external-api/src/main` contain zero blocking primitives (CI grep gate green).
- [ ] `LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache` APIs return only `CF<T>` / `CF<Void>`.
- [ ] All `module-calculator` / `module-synchronizer` / `module-rest-controller` callers compile and pass unit tests.
- [ ] `./gradlew compileKotlin compileJava --continue` clean.
- [ ] `./gradlew test` clean.
- [ ] Runtime smoke: ext-api phase trigger returns 202, chain completes without `ERROR`, queue drains, active jobs = 0.
- [ ] Load test: `views_per_sec` ≥ baseline (pre-PR baseline captured to `docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md` before code changes).
- [ ] ADR committed: `docs/01_ADR/ADR-blocking-async-contract-cf-chain.md`.
- [ ] Single PR, branched from `develop`, multiple atomic commits.

## 11. Summary

Drop the synchronous return contract across LogicExecutor, Lock, SingleFlight, and TieredCache. Replace with pure `CompletableFuture<T>` end-to-end. One PR, one ADR, one big-bang migration. All 24 CRITICAL + 6 HIGH blocking sites eliminated. CI grep gate prevents regression.
