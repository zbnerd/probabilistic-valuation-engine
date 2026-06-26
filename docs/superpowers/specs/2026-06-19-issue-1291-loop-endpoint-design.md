# Issue #1291: Phase Infinite-Loop Endpoint — Design

- Date: 2026-06-19
- Branch: `feature/issue-1291-loop-spec`
- Status: Draft (pending user review)
- Blocked-by: #1289 (merged), #1290 (merged)

## 1. Goal

Add `POST /api/internal/loop/phase/{phaseName}` and `POST /api/internal/stop/loop/phase/{phaseName}` to `module-external-api`. The start endpoint begins a continuous loop of the named phase: each iteration runs the phase end-to-end, then the next iteration starts immediately with a fresh runId. The stop endpoint halts the loop at the next chunk boundary. Loop iterations share a single `loopId` but each get its own per-iteration `runId`.

**Use case:** keep `ITEM_EQUIPMENT` polling continuously so the read model reflects fresh gear data without waiting for the daily trigger.

**Note on the issue body:** the issue references an "existing `ItemEquipmentContinuousLoop`" — that class does not exist in the codebase. This spec builds the loop controller from scratch (decision recorded in brainstorming Q1).

## 2. Components

### 2.1 `LoopStatus` enum

```kotlin
enum class LoopStatus { RUNNING, STOPPING, STOPPED }
```

### 2.2 `LoopState` data class

```kotlin
data class LoopState(
    val loopId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    var status: LoopStatus,
    var iterationCount: Int = 0,
    var lastRunId: String? = null,
    var lastError: String? = null,
)
```

Mutable (`var`) for in-place updates from iteration `.whenComplete`. Stored under `AtomicReference<LoopState>` for CAS.

### 2.3 `PhaseLoopController` (`@Component`)

```kotlin
@Component
class PhaseLoopController(
    private val externalApiScheduler: ExternalApiScheduler,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    private val stopSignal: PhaseStopSignal,
    private val loopExecutor: AsyncTaskExecutor,  // virtual thread bean
    private val clock: Clock = Clock.systemUTC(),
) {
    private val loopablePhases = setOf(
        PipelinePhase.ITEM_EQUIPMENT,
        PipelinePhase.CHARACTER_BASIC,
        PipelinePhase.OCID_LOOKUP,
    )
    private val loops = ConcurrentHashMap<PipelinePhase, AtomicReference<LoopState>>()

    private val log = LoggerFactory.getLogger(PhaseLoopController::class.java)

    fun startLoop(phase: PipelinePhase): LoopState { ... }
    fun stopLoop(phase: PipelinePhase): LoopState? { ... }
    fun hasActiveLoop(phase: PipelinePhase): Boolean { ... }
    fun getLoopState(phase: PipelinePhase): LoopState? { ... }
    fun activeLoops(): List<LoopState> = ...   // for /run-status decoration

    @PreDestroy
    fun shutdown() { ... }   // STOPPING all RUNNING loops, drain executor

    private fun runIteration(phase, loopId, n) { ... }
    private fun handleIterationEnd(...) { ... }
    private fun finalize(phase, loopId) { ... }
}
```

### 2.4 `loopExecutor` bean (new)

Dedicated virtual-thread executor, separate from `module-external-api`'s existing `ext-api-scheduler-executor` (which serves daily + per-phase triggers).

```yaml
# application.yml
external-api:
  loop:
    executor:
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 64
      thread-name-prefix: ext-api-loop-
      virtual-threads: true
```

Bean wiring in `module-external-api/.../config/SchedulerConfig.kt` (or new `LoopConfig.kt`):

```kotlin
@Bean("loopExecutor")
@Qualifier("loopExecutor")
fun loopExecutor(cfg: ExternalApiLoopExecutorProperties): AsyncTaskExecutor { ... }
```

Lifecycle: Spring `DisposableBean` / `@PreDestroy` drains in-flight iterations within timeout, then terminates. `spring.lifecycle.timeout-per-shutdown-phase=30s` (already in YAML per project rules).

## 3. RunStatus extension

### 3.1 `RunStatus` adds `loopId`

```kotlin
data class RunStatus(
    val runId: String,
    val phase: PipelinePhase,
    val triggeredPhase: PipelinePhase,
    val startedAt: Instant,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
    val chunksProcessed: Int = 0,
    val recordsProcessed: Long = 0,
    val errorMessage: String? = null,
    val loopId: String? = null,  // ← new; null for non-loop runs
)
```

### 3.2 `RunStatusTracker.acquirePhaseSlot` overload

Add overload that accepts `loopId`. Backward-compatible default.

```kotlin
fun acquirePhaseSlot(
    phase: PipelinePhase,
    runId: String,
    loopId: String? = null,
): RunStatus? {
    val slot = slots.computeIfAbsent(phase) { AtomicReference(null) }
    val now = Instant.now(clock)
    val candidate = RunStatus(
        runId = runId,
        phase = phase,
        triggeredPhase = phase,
        startedAt = now,
        updatedAt = now,
        loopId = loopId,
    )
    val result = slot.updateAndGet { current ->
        if (current == null || current.isTerminal) candidate else current
    }
    return if (result.runId == runId) {
        log.info("[RunStatus] phase-slot acquired phase={} runId={} loopId={}", phase, runId, loopId ?: "-")
        result
    } else {
        log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, result.runId)
        null
    }
}
```

Existing 1-arg callers keep working (default `loopId = null`).

## 4. Scheduler wiring

### 4.1 `ExternalApiScheduler.triggerPhase` overload

```kotlin
fun triggerPhase(
    phase: PipelinePhase,
    runId: String,
    upstreamRunId: String?,
    loopId: String? = null,
): CompletableFuture<Void> {
    // existing logic, but acquirePhaseSlot(phase, runId, loopId)
    // AND whenComplete: if loopId != null, do NOT call stopSignal.clear(phase)
}
```

The 4 existing single-phase `runXxxPhase` methods pass `loopId = null` (or skip the new overload).

### 4.2 Iteration lifecycle in `PhaseLoopController.runIteration`

```kotlin
private fun runIteration(phase: PipelinePhase, loopId: String, n: Int) {
    val state = loops[phase]?.get() ?: run {
        log.warn("[Loop] state missing phase={} loopId={}", phase, loopId); return
    }
    if (state.status == LoopStatus.STOPPING) {
        finalize(phase, loopId); return
    }
    if (stopSignal.isStopRequested(phase)) {
        state.status = LoopStatus.STOPPING
        finalize(phase, loopId); return
    }
    val runId = runIdGenerator.newRunId()
    state.iterationCount = n
    state.lastRunId = runId
    val upstreamRunId = latestUpstreamRunId(phase)

    log.info("[Loop] iteration start phase={} loopId={} iter={} runId={}", phase, loopId, n, runId)
    try {
        externalApiScheduler.triggerPhase(phase, runId, upstreamRunId, loopId)
            .whenComplete { _, ex -> handleIterationEnd(phase, loopId, runId, ex, n) }
    } catch (ex: Throwable) {
        log.error("[Loop] iteration submit failed phase={} loopId={}", phase, loopId, ex)
        state.lastError = ex.message
        state.status = LoopStatus.STOPPING
        finalize(phase, loopId)
    }
}

private fun handleIterationEnd(phase: PipelinePhase, loopId: String, runId: String, ex: Throwable?, n: Int) {
    val state = loops[phase]?.get() ?: return
    state.lastRunId = runId
    when {
        ex is PhaseStoppedException -> {
            log.info("[Loop] iteration stopped phase={} loopId={} iter={}", phase, loopId, n)
            state.status = LoopStatus.STOPPING
        }
        ex != null -> {
            log.error("[Loop] iteration failed phase={} loopId={} iter={}", phase, loopId, n, ex)
            state.lastError = ex.message ?: "unknown"
            state.status = LoopStatus.STOPPING
        }
        else -> {
            log.info("[Loop] iteration done phase={} loopId={} iter={}", phase, loopId, n)
        }
    }
    if (state.status == LoopStatus.STOPPING) {
        finalize(phase, loopId)
    } else {
        loopExecutor.execute { runIteration(phase, loopId, n + 1) }
    }
}

private fun finalize(phase: PipelinePhase, loopId: String) {
    val state = loops[phase]?.get() ?: return
    state.status = LoopStatus.STOPPED
    stopSignal.clear(phase)
    log.info("[Loop] stopped loopId={} phase={} iterations={} lastError={}",
        loopId, phase, state.iterationCount, state.lastError ?: "none")
}
```

### 4.3 `latestUpstreamRunId`

For ITEM_EQUIPMENT/CHARACTER_BASIC, upstream is OCID_LOOKUP. Each iteration reads `runStatusTracker.getLastCompletedForPhase(OCID_LOOKUP)?.runId`.

```kotlin
private fun latestUpstreamRunId(phase: PipelinePhase): String? = when (phase) {
    PipelinePhase.ITEM_EQUIPMENT, PipelinePhase.CHARACTER_BASIC ->
        runStatusTracker.getLastCompletedForPhase(PipelinePhase.OCID_LOOKUP)?.runId
    PipelinePhase.OCID_LOOKUP -> null  // no upstream
    else -> null
}
```

`getLastCompletedForPhase` already exists on `RunStatusTracker`.

## 5. Start / stop API contracts

### 5.1 `POST /api/internal/loop/phase/{phaseName}`

| Condition | HTTP | Body |
|-----------|------|------|
| Loop started | 202 | `{status:"LOOP_STARTED", phase, loopId, iterationCount:0}` |
| Loop already active for phase | 409 | `{status:"LOOP_ALREADY_ACTIVE", phase, loopId, startedAt}` |
| `phaseName` not in `loopablePhases` | 400 | `{error:"INVALID_PHASE", allowed:"ITEM_EQUIPMENT,CHARACTER_BASIC,OCID_LOOKUP"}` |
| `phaseName` not parseable | 400 | same as 400 INVALID_PHASE |

Header `X-Airflow-Run-Id` optional, correlation only, stored in `LoopState.startedAtAirflowRunId`.

### 5.2 `POST /api/internal/stop/loop/phase/{phaseName}`

| Condition | HTTP | Body |
|-----------|------|------|
| Active loop, stop request submitted | 202 | `{status:"STOP_REQUESTED", phase, loopId, iterationCount}` |
| No active loop | 200 | `{status:"NOT_LOOPING", phase}` |
| `phaseName` invalid | 400 | `{error:"INVALID_PHASE", allowed:...}` |

`stopLoop` sets `PhaseStopSignal.requestStop(phase)` (same flag as `/stop/phase/{name}`). The currently-running iteration will throw `PhaseStoppedException` at its next chunk boundary (≤30s, depending on phase). The loop controller then exits the chain via `handleIterationEnd` → `finalize`.

### 5.3 Interaction with `/stop/phase/{name}`

`/stop/phase/{name}` sets the same `PhaseStopSignal`. If a loop is active for that phase, the loop's current iteration will halt at chunk boundary, loop exits via `handleIterationEnd`. AC: "loop iterations share the same stop semantics" — covered.

### 5.4 Trigger 409 extension

`/trigger/daily` and `/trigger/phase/{name}` add a loop-active pre-check before existing slot check:

```kotlin
val activeLoop = phaseLoopController.hasActiveLoop(phase)
if (activeLoop) {
    return ResponseEntity.status(409).body(mapOf(
        "status" to "LOOP_ACTIVE",
        "phase" to phase.name,
        "loopId" to (phaseLoopController.getLoopState(phase)?.loopId ?: ""),
    ))
}
```

Daily trigger checks ALL four phases before starting.

## 6. `/run-status` decoration

### 6.1 `RunStatusResponse` adds

```kotlin
data class RunStatusResponse(
    val current: RunStatusView?,
    val lastCompleted: RunStatusView?,
    val loopSummaries: Map<String, LoopSummaryView> = emptyMap(),
)

data class LoopSummaryView(
    val loopId: String,
    val phase: String,
    val startedAt: Instant,
    val iterationCount: Int,
    val lastRunId: String?,
    val status: String,  // RUNNING|STOPPING|STOPPED
    val lastError: String?,
)

data class RunStatusView(
    // ... existing fields
    val loopId: String? = null,
    val loopActive: Boolean = false,
)
```

### 6.2 `RunStatusView.loopActive` derivation

If `phaseLoopController.hasActiveLoop(current.phase)` → `loopActive = true`, `loopId = controller's loopId for that phase`. Slot record's `loopId` (from `RunStatus.loopId`) also reported — these match for the latest iteration.

`loopSummaries` keys: phase name. Value: `LoopSummaryView`. Empty when no active loops.

## 7. Slot state machine (loop vs single-shot)

| Trigger | `phase` | slot before | slot after | loop state | slot cleared? |
|---------|---------|-------------|------------|------------|---------------|
| Single-shot trigger | X | empty/terminal | X (non-terminal) | unchanged | No |
| Single-shot complete | X | X | COMPLETED | unchanged | No |
| Single-shot fail | X | X | FAILED → released | unchanged | Yes |
| Loop iteration N acquire | X | empty/terminal | X (non-terminal, loopId=L) | RUNNING, iterCount=N | No |
| Loop iteration N complete | X | X | COMPLETED (loopId=L) | RUNNING, iterCount=N | No |
| Loop iteration N+1 acquire | X | COMPLETED | X (non-terminal, loopId=L, runId=N+1) | RUNNING, iterCount=N+1 | No (overwrite) |
| Loop iteration N fails | X | X | FAILED → released | STOPPING → STOPPED | Yes (fail release) |
| Loop iteration N stopped | X | X | STOPPED | STOPPING → STOPPED | No (terminal persists) |
| Loop finalized | X | (any) | unchanged | STOPPED | unchanged |
| `/stop/loop` while iter running | X | X | STOPPED | STOPPING → STOPPED | No |

**Loop state lives independently of the slot.** Slot reflects latest iteration's terminal record. Loop state is the controller's `ConcurrentHashMap<PipelinePhase, AtomicReference<LoopState>>`. Both checked by `/trigger` (loop first, then slot).

## 8. Files Touched

| File | Change |
|------|--------|
| `module-external-api/.../runstatus/LoopStatus.kt` | new enum |
| `module-external-api/.../runstatus/LoopState.kt` | new data class |
| `module-external-api/.../loop/PhaseLoopController.kt` | new component (separate package from runstatus to keep boundaries clear) |
| `module-external-api/.../loop/LoopExecutorConfig.kt` | new executor bean |
| `module-external-api/.../runstatus/RunStatus.kt` | add `loopId: String? = null` |
| `module-external-api/.../runstatus/RunStatusTracker.kt` | `acquirePhaseSlot` overload with loopId; `getPhaseStatus` already returns RunStatus |
| `module-external-api/.../runstatus/RunStatusResponse.kt` | add `LoopSummaryView`, extend `RunStatusView` |
| `module-external-api/.../runstatus/InternalApiController.kt` | `startLoop`/`stopLoop` endpoints; trigger 409 loop check; /run-status decoration |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | `triggerPhase(..., loopId)` overload; whenComplete skips stopSignal.clear when loopId set |
| `module-external-api/src/main/resources/application.yml` | add `external-api.loop.executor.*` config block |
| `module-external-api/src/test/.../loop/PhaseLoopControllerTest.kt` | new |
| `module-external-api/src/test/.../runstatus/RunStatusTrackerTest.kt` | extend with loopId tests |
| `module-external-api/src/test/.../runstatus/InternalApiControllerTest.kt` | extend with loop endpoints + 409 cases |
| `module-external-api/src/test/.../scheduler/ExternalApiSchedulerTest.kt` | extend with loopId pass-through |

## 9. Test Plan

### 9.1 Unit tests

`PhaseLoopControllerTest`:
- `startLoop(ITEM_EQUIPMENT)` returns state with RUNNING status, submits `runIteration`
- duplicate `startLoop` same phase returns existing state, no resubmit
- `runIteration` calls `scheduler.triggerPhase` with generated runId, current loopId, upstream from getLastCompletedForPhase(OCID_LOOKUP)
- successful iteration → next iteration submitted with new runId
- iteration `PhaseStoppedException` → status STOPPING → finalize (no resubmit)
- iteration generic exception → status STOPPING + lastError set → finalize
- `stopLoop(phase)` while RUNNING sets stopSignal.requestStop + state.STOPPING + returns existing loopId
- `stopLoop(phase)` while no loop returns null (controller returns 200 NOT_LOOPING via API)
- `hasActiveLoop` true while RUNNING|STOPPING; false after STOPPED
- `latestUpstreamRunId` for ITEM_EQUIPMENT returns OCID_LOOKUP's last completed runId; for OCID_LOOKUP returns null

`RunStatusTrackerTest` (extend):
- `acquirePhaseSlot(phase, runId, loopId)` stores loopId on slot record
- subsequent `getPhaseStatus` returns loopId
- existing single-shot `acquirePhaseSlot(phase, runId)` (no loopId) leaves loopId null

`InternalApiControllerTest` (extend):
- POST /loop/phase/ITEM_EQUIPMENT → 202 LOOP_STARTED, loopId non-null
- POST /loop/phase/ITEM_EQUIPMENT twice → second 409 LOOP_ALREADY_ACTIVE
- POST /loop/phase/RANKING_FETCH → 400 INVALID_PHASE
- POST /stop/loop/phase/ITEM_EQUIPMENT while loop active → 202 STOP_REQUESTED, iterationCount present
- POST /stop/loop/phase/ITEM_EQUIPMENT while no loop → 200 NOT_LOOPING
- POST /stop/loop/phase/RANKING_FETCH → 400 INVALID_PHASE
- POST /trigger/phase/ITEM_EQUIPMENT while loop active → 409 LOOP_ACTIVE
- POST /trigger/daily while any loop active → 409 LOOP_ACTIVE
- GET /run-status with active loop → current.loopId set, current.loopActive=true, loopSummaries contains phase

`ExternalApiSchedulerTest` (extend):
- `triggerPhase(phase, runId, upstreamRunId, loopId="L1")` calls `acquirePhaseSlot(phase, runId, "L1")`
- successful iteration with `loopId` set → whenComplete does NOT call `stopSignal.clear(phase)`
- existing single-shot path unchanged (no loopId)

### 9.2 Concurrency tests

`PhaseLoopControllerTest` concurrency:
- Two threads call `startLoop(ITEM_EQUIPMENT)` concurrently → exactly one wins, other returns existing state
- `stopLoop` arriving mid-iteration (after `triggerPhase` returns future but before complete) → iteration completes (terminal), then finalize
- `stopLoop` arriving between iterations (after iteration N completes, before iteration N+1 submitted) → runIteration top check sees STOPPING, exits clean

### 9.3 Verification

```
./gradlew :module-external-api:test
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```

Manual smoke: bootRun module-external-api, `curl -X POST /api/internal/loop/phase/ITEM_EQUIPMENT`, observe `/run-status` for 10+ minutes (deferred — not unit-testable).

## 10. Acceptance Criteria Mapping

| AC | Implementation |
|----|----------------|
| `POST /api/internal/loop/phase/ITEM_EQUIPMENT` starts loop, returns 202 + loopId | §5.1 + §2.3 startLoop |
| `/run-status` shows latest iteration ACTIVE + loopId field | §6 + §3 RunStatus.loopId |
| Loop runs ≥10 min without intervention | §4.2 iteration chaining; manual smoke verifies |
| `POST /api/internal/stop/loop/phase/ITEM_EQUIPMENT` halts within chunk boundary | §5.2 + §4.2 stopLoop → stopSignal → PhaseStoppedException at next chunk boundary |
| Same works for OCID_LOOKUP and others | §2.3 loopablePhases set, parametrized tests |
| Second loop on same phase → 409 with active loopId | §5.1 + §2.3 ConcurrentHashMap CAS |
| Daily + per-phase trigger reject 409 if loop active | §5.4 |
| Loop iterations produce non-overlapping runIds | §4.2 runIdGenerator.newRunId() per iteration |

## 11. Out of Scope

- Persistent loop state across restarts (in-memory only — documented limitation in §5)
- Loop-level Prometheus metrics (iteration rate, avg duration) — deferred
- Loop-pause/resume (only start/stop)
- Cross-phase loops (e.g., chain ITEM_EQUIPMENT + CHARACTER_BASIC in one loop)
- Loop cooldown / rate tuning (Q3 decision: no cooldown)
- Iteration failure → retry (Q2 decision: fail stops loop)
- `/loop/phase/RANKING_FETCH` (no use case; excluded from loopablePhases)
- Automatic loop startup (`run-on-startup` for loops — separate feature if needed)

## 12. Dependencies

- PhaseStopSignal (from #1290) — reused for stop propagation
- PhaseStoppedException (from #1290) — reused for iteration halt
- RunStatusTracker.per-phase slot CAS (from #1289) — extended with loopId
- ExternalApiScheduler.triggerPhase (existing) — overloaded with loopId
- RunIdGenerator (existing) — per-iteration runId generation

## 13. Risks

- **Shutdown race**: loop iterations in flight at `@PreDestroy` could leak. Mitigation: `shutdown()` sets STOPPING on all RUNNING loops, signals PhaseStopSignal, awaits `loopExecutor` termination with 30s timeout (matches `spring.lifecycle.timeout-per-shutdown-phase`). After timeout, log warning with loopId/iteration/runId of orphaned iterations.
- **Module restart loses loop state**: in-memory `ConcurrentHashMap`. Documented. Operators must re-issue `/loop/phase/{name}`.
- **Iteration thrash on fast-completing phases**: OCID_LOOKUP loop with empty upstream cache exits immediately, next iteration starts immediately. Mitigation: future enhancement could add per-phase minimum-iteration-duration guard. Out of scope for this spec.
- **Memory growth in `lastError`**: `LoopState.lastError` accumulates per failed iteration. Only set on failure, overwritten on next failure. Bounded.