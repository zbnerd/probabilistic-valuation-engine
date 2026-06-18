# Issue #1290: Graceful Phase Stop Endpoint — Design

- Date: 2026-06-18
- Branch: `feature/issue-1290-spec`
- Status: Draft (pending user review)

## 1. Goal

Add `POST /api/internal/stop/phase/{phaseName}` to `module-external-api` for graceful stop of an in-flight phase. The phase finishes its current chunk / page / batch, then halts cleanly. No half-written chunks in MinIO. Stopped phase slot transitions to `STOPPED` (terminal, record persists in slot — next acquire overwrites).

Blocked by #1289 (merged): per-phase runId tracking + slot CAS in `RunStatusTracker` are already in place.

## 2. Components

### 2.1 New: `PhaseStopSignal`

`@Component` singleton. `ConcurrentHashMap<PipelinePhase, AtomicBoolean>`.

```kotlin
@Component
class PhaseStopSignal {
    private val flags = ConcurrentHashMap<PipelinePhase, AtomicBoolean>()

    fun requestStop(phase: PipelinePhase): Boolean {
        val flag = flags.computeIfAbsent(phase) { AtomicBoolean(false) }
        return flag.compareAndSet(false, true)
    }

    fun isStopRequested(phase: PipelinePhase): Boolean =
        flags[phase]?.get() == true

    fun clear(phase: PipelinePhase) {
        flags[phase]?.set(false)
    }
}
```

Notes:
- `requestStop` returns the previous state (`false → true` = first request; `true → true` = already requested). Idempotent for the caller.
- `clear` is unconditional reset; called by scheduler in `whenComplete` regardless of outcome.

### 2.2 New: `PhaseStoppedException`

```kotlin
class PhaseStoppedException(val phase: PipelinePhase) : RuntimeException("phase ${phase.name} stopped at chunk boundary")
```

Throws from phase bean at chunk/page/batch boundary when `isStopRequested(phase)` returns true.

### 2.3 PipelinePhase enum extension

```kotlin
enum class PipelinePhase {
    IDLE, RANKING_FETCH, OCID_LOOKUP, OCID_CACHE_REFRESH,
    CHARACTER_BASIC, CHARACTER_BASIC_DONE, ITEM_EQUIPMENT,
    COMPLETED, FAILED,
    STOPPED,  // ← new
}
```

### 2.4 RunStatus.isTerminal extension

```kotlin
val isTerminal: Boolean
    get() = phase == PipelinePhase.COMPLETED
        || phase == PipelinePhase.FAILED
        || phase == PipelinePhase.STOPPED
```

Existing `acquirePhaseSlot` CAS check (`current == null || current.isTerminal`) already covers STOPPED. No change to `acquirePhaseSlot` logic.

### 2.5 RunStatusTracker — new `stopRun`

```kotlin
fun stopRun(phase: PipelinePhase, runId: String, chunksProcessed: Int, recordsProcessed: Long) {
    val now = Instant.now(clock)
    slots[phase]?.updateAndGet { current ->
        if (current == null || current.runId != runId) return@updateAndGet current
        current.copy(
            phase = PipelinePhase.STOPPED,
            updatedAt = now,
            completedAt = now,
            chunksProcessed = chunksProcessed,
            recordsProcessed = recordsProcessed,
        )
    }
    log.info("[RunStatus] phase-slot stopped phase={} runId={} chunks={} records={}",
        phase, runId, chunksProcessed, recordsProcessed)
}
```

Slot record persists. `releasePhaseSlot` NOT called.

## 3. Stop Endpoint Contract

`POST /api/internal/stop/phase/{phaseName}`

| Header | Required | Purpose |
|--------|----------|---------|
| `X-Airflow-Run-Id` | optional | correlation only, best-effort, no validation against slot runId |

### 3.1 Response shapes

| Condition | HTTP | Body |
|-----------|------|------|
| Phase slot has non-terminal run | 202 | `{"status":"STOP_REQUESTED","phase":"ITEM_EQUIPMENT","runId":"<running-runId>","airflowRunId":"<echoed-from-header>"}` |
| Phase slot empty or terminal | 200 | `{"status":"NOT_RUNNING","phase":"ITEM_EQUIPMENT","runId":null}` (or last-known runId from `getLastCompletedForPhase`) |
| `phaseName` not in triggerable set | 400 | `{"error":"INVALID_PHASE","allowed":"RANKING_FETCH,OCID_LOOKUP,CHARACTER_BASIC,ITEM_EQUIPMENT"}` |

### 3.2 triggerablePhases set

Reuse the same set as `/trigger/phase/{phaseName}`:
```kotlin
private val triggerablePhases = setOf(
    PipelinePhase.RANKING_FETCH,
    PipelinePhase.OCID_LOOKUP,
    PipelinePhase.CHARACTER_BASIC,
    PipelinePhase.ITEM_EQUIPMENT,
)
```

## 4. Phase Bean Boundary Check Pattern

Each phase bean injects `PhaseStopSignal` and checks at the top of each iteration:

```kotlin
// RankingFetchPhase.processPages
if (stopSignal.isStopRequested(PipelinePhase.RANKING_FETCH)) {
    throw PhaseStoppedException(PipelinePhase.RANKING_FETCH)
}

// OcidLookupPhase.processBatch (top of while loop)
if (stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP)) {
    throw PhaseStoppedException(PipelinePhase.OCID_LOOKUP)
}

// BatchFetchSupport.processBatch — extended BatchFetchContext
data class BatchFetchContext(
    val endpoint: String,
    val phase: PipelinePhase,  // ← new
    val apiEndpoint: ExternalApiEndpoint,
    val onFetched: () -> Unit,
    val onFailed: () -> Unit,
)
// top of while loop:
if (stopSignal.isStopRequested(ctx.phase)) {
    throw PhaseStoppedException(ctx.phase)
}
```

`BatchFetchSupport` constructor adds `PhaseStopSignal` injection. Callers (char-basic, item-equipment phases) populate `ctx.phase` when constructing `BatchFetchContext`.

## 5. Scheduler Wiring

### 5.1 New: `requestPhaseStop`

```kotlin
fun requestPhaseStop(phase: PipelinePhase): Boolean {
    val hadNonTerminal = runStatusTracker.hasNonTerminalRun(phase) != null
    if (hadNonTerminal) {
        stopSignal.requestStop(phase)
        log.info("[Scheduler] stop requested phase={} runId={}",
            phase, runStatusTracker.getPhaseStatus(phase)?.runId)
    }
    return hadNonTerminal
}
```

### 5.2 `runXxxPhase` whenComplete update (all 4)

Template below uses `XXX` as a placeholder for the per-phase enum value (`RANKING_FETCH`, `OCID_LOOKUP`, `CHARACTER_BASIC`, `ITEM_EQUIPMENT`). The same shape is duplicated in each of the 4 phase methods.

```kotlin
return future
    .whenComplete { _, ex ->
        when {
            ex is PhaseStoppedException -> {
                log.info("[Scheduler] runXxxPhase stopped runId={} phase={}", runId, ex.phase)
                runStatusTracker.stopRun(PipelinePhase.XXX, runId, 0, 0)
                stopSignal.clear(PipelinePhase.XXX)
            }
            ex != null -> {
                log.error("[Scheduler] runXxxPhase failed runId={}", runId, ex)
                runStatusTracker.failRun(PipelinePhase.XXX, runId, ex.message ?: "unknown")
                runStatusTracker.releasePhaseSlot(PipelinePhase.XXX, runId)
                stopSignal.clear(PipelinePhase.XXX)
            }
            else -> {
                runStatusTracker.completeRun(PipelinePhase.XXX, runId, 0, 0)
                stopSignal.clear(PipelinePhase.XXX)
            }
        }
    }
    .thenRun { }
```

`clear` in all 3 branches prevents flag leakage to next run on the same phase.

### 5.3 Completion cause unwrapping

`CompletableFuture.whenComplete` receives the upstream exception directly. `ex is PhaseStoppedException` matches both synchronous throw and async rejection. No `CompletionException` unwrap needed for our specific type check.

## 6. Controller Endpoint

```kotlin
@PostMapping("/stop/phase/{phaseName}")
fun stopPhase(
    @PathVariable phaseName: String,
    @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
): ResponseEntity<Map<String, String>> {
    val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
    if (phase == null || phase !in triggerablePhases) {
        return badRequestInvalidPhase()
    }
    val wasRunning = scheduler.requestPhaseStop(phase)
    if (wasRunning) {
        val runId = runStatusTracker.getPhaseStatus(phase)?.runId
        return ResponseEntity.accepted().body(mapOf(
            "status" to "STOP_REQUESTED",
            "phase" to phase.name,
            "runId" to (runId ?: ""),
        ))
    }
    val lastRunId = runStatusTracker.getLastCompletedForPhase(phase)?.runId
    return ResponseEntity.ok().body(mapOf(
        "status" to "NOT_RUNNING",
        "phase" to phase.name,
        "runId" to (lastRunId ?: ""),
    ))
}
```

## 7. Slot State Machine

| Trigger | slot.phase before | slot.phase after | slot cleared? |
|---------|------------------|-----------------|---------------|
| Normal completion | phase | COMPLETED | No (terminal persists) |
| Generic exception | phase | FAILED | Yes (`releasePhaseSlot`) |
| `PhaseStoppedException` | phase | STOPPED | No (terminal persists, next acquire overwrites) |
| New trigger on STOPPED | STOPPED | new phase | No (overwrite in-place) |
| New trigger on COMPLETED | COMPLETED | new phase | No (overwrite in-place) |
| New trigger on FAILED | null (released) | new phase | n/a |

## 8. Files Touched

| File | Change |
|------|--------|
| `module-external-api/.../runstatus/PipelinePhase.kt` | add `STOPPED` |
| `module-external-api/.../runstatus/RunStatus.kt` | extend `isTerminal` predicate |
| `module-external-api/.../runstatus/RunStatusTracker.kt` | add `stopRun` |
| `module-external-api/.../runstatus/PhaseStopSignal.kt` | new component |
| `module-external-api/.../scheduler/PhaseStoppedException.kt` | new exception |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | inject `PhaseStopSignal`, add `requestPhaseStop`, update `whenComplete` for all 4 phase methods |
| `module-external-api/.../scheduler/phase/BatchFetchSupport.kt` | inject `PhaseStopSignal`, add `phase` to `BatchFetchContext`, check at loop top |
| `module-external-api/.../scheduler/phase/RankingFetchPhase.kt` | inject `PhaseStopSignal`, check in `processPages` |
| `module-external-api/.../scheduler/phase/OcidLookupPhase.kt` | inject `PhaseStopSignal`, check in `processBatch` while loop |
| `module-external-api/.../scheduler/phase/CharacterBasicFetchPhase.kt` | set `ctx.phase = PipelinePhase.CHARACTER_BASIC` |
| `module-external-api/.../scheduler/phase/ItemEquipmentFetchPhase.kt` | set `ctx.phase = PipelinePhase.ITEM_EQUIPMENT` |
| `module-external-api/.../runstatus/InternalApiController.kt` | add `stopPhase` endpoint |

## 9. Test Plan

### 9.1 Unit tests

- `PhaseStopSignalTest`: requestStop CAS, isStopRequested, clear
- `RunStatusTrackerTest`: `stopRun` semantics; subsequent `acquirePhaseSlot` succeeds (terminal-overwrite)
- `BatchFetchSupportTest`: stop flag trips at loop top with `ctx.phase`
- `RankingFetchPhaseTest`: pre-set flag → no fetch, throws `PhaseStoppedException`
- `OcidLookupPhaseTest`: pre-set flag → no batch, throws `PhaseStoppedException`
- `CharacterBasicFetchPhaseTest`: pre-set flag → no batch, throws
- `ItemEquipmentFetchPhaseTest`: pre-set flag → no batch, throws
- `ExternalApiSchedulerTest`: `requestPhaseStop` true/false paths; whenComplete `stopRun`/`completeRun`/`failRun` branches; signal `clear` in all 3 branches
- `InternalApiControllerTest`: 202 STOP_REQUESTED, 200 NOT_RUNNING, 400 INVALID_PHASE

### 9.2 Edge cases

- Stop on phase A while phase B running: no cross-phase interference (each phase has own flag)
- Stop on already-STOPPED slot: `requestPhaseStop` returns false → 200 NOT_RUNNING
- Double stop (idempotent): both requests return true; scheduler clears flag once at whenComplete
- Stop request during trigger acquire race: scheduler clears in previous run's whenComplete (may not yet run when new run starts); new run's first boundary check sees flag → exits clean. Correct.

### 9.3 Verification

```
./gradlew :module-external-api:test
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```

## 10. Acceptance Criteria Mapping

| AC | Implementation |
|----|----------------|
| `POST /api/internal/stop/phase/ITEM_EQUIPMENT` halts within one chunk boundary (≤30s) | Section 4 boundary check in `BatchFetchSupport.processBatch` (≤7s at 200/s, batchSize 1000) |
| Same endpoint works for each phase | Section 4 per-phase boundary checks |
| Stopped phase shows `phase=STOPPED` in /run-status, terminal | Section 2.3, 2.4, 2.5 + Section 7 state machine |
| Not-running returns 200 NOT_RUNNING | Section 3.1 + Section 6 controller |
| No half-written chunks in MinIO | `ChunkedSnapshotSink` atomic temp-file move; pipe in `OcidLookupPhase` close in `finally` |
| New phase triggered immediately after stop | STOPPED is terminal-but-retainable; `acquirePhaseSlot` overwrites (Section 2.4) |
| Existing daily unaffected by stop on different phase | Each phase has own flag; `requestPhaseStop` only sets the named phase's flag |

## 11. Out of Scope

- Manual smoke test against live Nexon API (deferred — same as #1289).
- Stop endpoint for the daily chain as a whole (not requested).
- Resumable stop (phase resumes from last successful chunk on next trigger — out of scope; new trigger starts fresh).
- Per-`runId` stop (current design stops whatever non-terminal run is in the named phase slot).
