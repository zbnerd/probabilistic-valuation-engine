# Issue 1001: silent failure → success in ExternalApiScheduler

- Status: Accepted
- Date: 2026-06-04
- Owner: zbnerd
- Issue: #1001

## Background

`ExternalApiScheduler.triggerDailyRefresh()` orchestrates the daily fetch pipeline: ranking → OCID lookup → OCID cache refresh → character basic. Each phase is a `CompletableFuture<Path?>` chained via `thenCompose`.

The current chain (lines 88-119) contains two defects that combine to mask a complete pipeline failure as a successful run:

1. `rankingPhase.execute(executor).handle { runDir, ex -> ... runDir }` swallows any exception from the ranking phase and converts it to a `null` `runDir`. Per `async-patterns.md` (CF exception semantics), this is a "catch and ignore" anti-pattern — the result type is `Path?` so `null` is a legal "no value" rather than a failure.
2. `thenCompose { runDir -> if (runDir == null) completedFuture(null) else ocidLookupPhase.execute(...) }` interprets `null` as "skip to next phase" and continues the chain.

The downstream `whenComplete` block (lines 108-119) only sees the chain result. By the time execution reaches it, the `null` swallow has been absorbed, the chain ran to its natural end, and `ex` is `null`. The block calls `runStatusTracker.completeRun(runId, 0, 0)` and logs "daily refresh completed" — even though the actual work was zero pages fetched, zero OCIDs looked up, zero characters processed.

The same `whenComplete` block calls `startItemEquipmentLoopOnce()` unconditionally in both success and failure branches (line 118), so a failed ranking also kicks off the ITEM_EQUIPMENT continuous loop on a stale OCID cache from a prior run. This widens the blast radius: not only is the failure invisible, the system continues making downstream API calls based on outdated data.

## Problem

A complete ranking fetch failure (Nexon API outage, network partition, sink initialization error) is reported as a successful daily run with zero records. Operators see `PipelinePhase.COMPLETED` in the run-status endpoint and cannot distinguish a real success from a swallowed failure. The ITEM_EQUIPMENT loop starts anyway and runs against the previous run's OCID cache.

Goal: any phase failure must (a) abort the chain, (b) record `PipelinePhase.FAILED` in `RunStatusTracker`, and (c) skip `startItemEquipmentLoopOnce()`. Success must (d) record `COMPLETED` and start the loop as before.

## Decision

Two surgical changes inside `triggerDailyRefresh()` (lines 88-119). No new files, no new abstractions, no coroutine refactor, no metric changes.

### Change A — propagate exceptions through the phase chain

Drop the `.handle` block on `rankingPhase.execute(executor)`. Let CF propagate the exception naturally to `whenComplete`. Make the `thenCompose` callback strict: if the upstream phase returns a `null` `runDir`, throw `IllegalStateException` with a descriptive message — never silently skip to the next phase.

```kotlin
rankingPhase.execute(executor)
    .thenCompose { runDir ->
        val resolved = runDir ?: error("ranking fetch returned null runDir")
        runStatusTracker.transitionPhase(PipelinePhase.OCID_LOOKUP)
        ocidLookupPhase.execute(executor, resolved)
    }
    .thenCompose { _ ->
        val cache = ocidCacheProvider.refresh()
        runStatusTracker.transitionPhase(PipelinePhase.CHARACTER_BASIC)
        snapshotFetchPhase.executeCharacterBasic(executor, cache)
    }
    .whenComplete { _, ex ->
        // see Change B
    }
```

Rationale:
- `.handle { _, ex -> value }` is exactly the "catch and ignore" pattern called out in `code-rules.md` §5 (Error Handling & Logging). Replacing it with exception propagation is the natural fix.
- The defensive `?: error(...)` makes the contract explicit: a phase that completes without producing its output is a bug, not a "skip me" signal. If `RankingFetchPhase` ever returns a null `runDir` (which it currently does not — line 85 of that file returns `runDir` unconditionally), we want a loud failure rather than silent progress.
- `OcidLookupPhase.execute` returns `CompletableFuture<Path?>` per its signature (line 55). Its failure path is `exceptionally` from the underlying coroutine; downstream `thenCompose` ignores the value and proceeds to OCID cache refresh + character basic. No change to OcidLookupPhase needed.

### Change B — branch `startItemEquipmentLoopOnce()` on outcome

Split the `whenComplete` body into explicit success and failure branches. Lock release stays in both. `startItemEquipmentLoopOnce()` moves into the success branch only.

```kotlin
.whenComplete { _, ex ->
    if (ex != null) {
        val cause = ex.cause ?: ex
        val message = cause.message ?: cause::class.simpleName ?: "unknown error"
        runStatusTracker.failRun(runId, message)
        log.error("[Scheduler] daily refresh failed, runId={}", runId, cause)
        releaseLock()
        // do NOT start item-equipment loop on failure
    } else {
        runStatusTracker.completeRun(runId, 0, 0)
        log.info("[Scheduler] daily refresh completed, runId={}", runId)
        releaseLock()
        startItemEquipmentLoopOnce()
    }
}
```

Rationale:
- `whenComplete` runs on success or failure, so `releaseLock()` belongs in both branches. The previous code had `releaseLock()` after the `if/else` so it ran once, which is correct; keeping it inside each branch preserves that property while making the success/failure asymmetry explicit.
- `startItemEquipmentLoopOnce()` is guarded by `compareAndSet(false, true)` (line 123), so calling it from only the success branch is safe — a second call from a different code path is a no-op.
- The `ex.cause` extraction matters: per `async-patterns.md` "CompletionException 언래핑", CF wraps thrown exceptions in `CompletionException`. `ex.cause` is the original cause. The current code already does this on line 110, so no behavior change — preserved for `failRun` consistency.
- `cause::class.simpleName` is a defensive fallback if both `cause.message` and `ex.message` are null. `BaseException.message` should always be non-null in this codebase, but the fallback prevents an empty error string from reaching `RunStatusTracker`.

### Why not a coroutine refactor

`async-patterns.md` recommends `suspend fun` for IO-bound work, but a full coroutine rewrite of `triggerDailyRefresh` is out of scope for a bug fix. The bug is a CF-chaining mistake; the fix is a CF-chaining fix. A coroutine refactor would be a separate ADR-sized change.

### Why no tracker changes

`RunStatusTracker.failRun()` already sets `phase = FAILED`, `errorMessage`, `completedAt`, and updates `lastCompletedRun` (lines 48-61). The defect was that `failRun` was never called; the fix is in the scheduler, not the tracker.

## Trade-offs

### Sensitivity
- Nexon API availability — every daily run depends on the API being reachable. Any outage now correctly surfaces as FAILED.
- CF chain length — 3 phases chained. Each adds a small completion-stack frame. Not a perf concern.
- Operator response time — FAILED status now visible via `/api/internal/run-status` instead of silently COMPLETED.

### Trade-off
| Choice | Gain | Give up |
| --- | --- | --- |
| Drop `.handle`, let CF propagate | Exception semantics correct, no swallowed failures | Slightly more verbose defensive `?: error(...)` |
| Skip `startItemEquipmentLoopOnce()` on failure | No downstream calls on stale OCID cache | Operator must manually re-trigger ranking before ITEM_EQUIPMENT resumes |
| No coroutine refactor | Minimal diff, low risk, easy review | Future `suspend fun` rewrite still owed |

### Risk
- `OcidLookupPhase.execute` currently throws on sink init failure (its own `throw ex` at the coroutine level). With Change A, that throw now reaches `whenComplete` and triggers FAILED. Operators who relied on the silent recovery would see new failures — but those silent recoveries were the bug.
- If `error("ranking fetch returned null runDir")` ever fires (e.g., a future change to `RankingFetchPhase` returns nullable), the descriptive message lets operators find the cause immediately.

### Non-Risk
- Lock leak — `releaseLock()` is in both branches, identical to current placement. The only behavior change is which branch calls `startItemEquipmentLoopOnce()`.
- Existing tests — `ExternalApiScheduler` has no unit test today (only JaCoCo coverage artifacts). No existing test to break.
- `RunStatusTracker` — no change to the tracker, so `RunStatusTrackerTest` keeps passing.

## Acceptance criteria

- [ ] `rankingPhase.execute()` failure → `ocidLookupPhase.execute()` not called
- [ ] `rankingPhase.execute()` failure → `snapshotFetchPhase.executeCharacterBasic()` not called
- [ ] `rankingPhase.execute()` failure → `RunStatusTracker.failRun()` called with descriptive message
- [ ] `rankingPhase.execute()` failure → `startItemEquipmentLoopOnce()` not called
- [ ] `rankingPhase.execute()` returns null `runDir` → same behavior as failure above
- [ ] Happy path → all phases called → `RunStatusTracker.completeRun()` called → `startItemEquipmentLoopOnce()` called
- [ ] `./gradlew :module-external-api:compileKotlin compileJava --continue` passes
- [ ] `./gradlew :module-external-api:test` passes (new + existing)

## Result / Evidence

### Metrics
| Metric | Before | After | Notes |
| --- | --- | --- | --- |
| Silent failure rate | unbounded (no signal) | 0 (all failures → FAILED) | observable via `/api/internal/run-status` |
| FAILED runs/day | 0 (swallowed) | matches Nexon API outage count | first deployment will show real rate |

### Observed result
TBD — measured after first staging deployment.

## Testing

New unit test class: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`.

Pure constructor + hand-rolled fakes (no Spring context, no Testcontainers, no DB). Per `testing-conventions.md` H2 금지 — we use no DB here, so H2 is not a concern.

Dependencies to mock: `OcidLookupPhase`, `SnapshotFetchPhase`, `OcidCacheProvider`, `ObjectProvider<RankingFetchPhase>`, `ExecutorService`. Keep real `RunStatusTracker` (already covered by `RunStatusTrackerTest`).

`ObjectProvider<RankingFetchPhase>` mock must return the test's ranking phase from `ifAvailable` — the scheduler reads `rankingFetchPhaseProvider.ifAvailable` at line 75 and bails out with "ranking fetch phase is required but not enabled" if null. A `Mockito.when(provider.ifAvailable).thenReturn(phase)` covers all three test cases.

Test cases:
1. `ranking failure does not invoke OCID phase and records FAILED`
   - `rankingPhase.execute` returns `CompletableFuture.failedFuture(RuntimeException("nexon 503"))`
   - After `triggerDailyRefresh` completes (use a CountDownLatch on the `whenComplete`), assert:
     - `ocidLookupPhase.execute` was never called
     - `snapshotFetchPhase.executeCharacterBasic` was never called
     - `runStatusTracker.getLastCompletedRun()?.phase == PipelinePhase.FAILED`
     - `runStatusTracker.getLastCompletedRun()?.errorMessage` contains "nexon 503"

2. `ranking returns null runDir is treated as failure`
   - `rankingPhase.execute` returns `CompletableFuture.completedFuture(null)`
   - Same assertions as test 1

3. `happy path records COMPLETED and starts item-equipment loop`
   - `rankingPhase.execute` returns `CompletableFuture.completedFuture(tempDir)`
   - `ocidLookupPhase.execute` returns `CompletableFuture.completedFuture(tempDir)`
   - `ocidCacheProvider.refresh()` returns empty map
   - `snapshotFetchPhase.executeCharacterBasic` returns `CompletableFuture.completedFuture(Unit)`
   - After `triggerDailyRefresh` completes:
     - `runStatusTracker.getLastCompletedRun()?.phase == PipelinePhase.COMPLETED`
     - `itemEquipmentStarted.get() == true` — make the existing private field package-private (no behavior change) for test access; do not use reflection

Lock-release verification: in test 1, after the failure, call `triggerDailyRefresh` again with a successful ranking phase. It must run, not skip with "could not acquire lock".

## Summary

Replace `.handle { runDir, ex -> runDir }` (which swallowed ranking failures to `null`) with strict exception propagation; branch `startItemEquipmentLoopOnce()` so it runs only on success. Two changes, one file, no new abstractions, no new metrics — the bug becomes a `FAILED` run-status line instead of a silent `COMPLETED`.
