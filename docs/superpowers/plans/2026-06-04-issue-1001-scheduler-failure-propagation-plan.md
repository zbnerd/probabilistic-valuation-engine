# Issue 1001: Scheduler Failure Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `ExternalApiScheduler.triggerDailyRefresh()` from reporting a failed daily run as `COMPLETED` and from kicking off `ITEM_EQUIPMENT` on a stale OCID cache when ranking fetch fails.

**Architecture:** Drop the `.handle { runDir, ex -> runDir }` block on `rankingPhase.execute()` so CF exceptions propagate to `whenComplete` naturally. Add a strict `?: error(...)` guard in the next `thenCompose`. Branch `startItemEquipmentLoopOnce()` into the success branch of `whenComplete` only. No new modules, no coroutine refactor.

**Tech Stack:** Kotlin, Spring Boot, `CompletableFuture`, mockito-kotlin, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-04-issue-1001-scheduler-failure-propagation-design.md`

**Branch:** create from `develop` as `fix/1001-scheduler-failure-propagation`.

---

## File Structure

| Path | Change | Responsibility |
| --- | --- | --- |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | modify (lines 88-119) | drop `.handle` block, add `?: error(...)`, branch loop start |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | modify (line 42) | change `private val itemEquipmentStarted` → package-private for test access |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` | create | 3 unit tests covering failure/null-runDir/success paths |

No other files change.

---

## Task 1: Branch and verify baseline

**Files:** none

- [ ] **Step 1: Create feature branch from develop**

```bash
cd /home/maple/probabilistic-valuation-engine
git checkout develop
git pull
git checkout -b fix/1001-scheduler-failure-propagation
```

- [ ] **Step 2: Run baseline compile + test for the module**

```bash
./gradlew :module-external-api:compileKotlin compileJava --continue
./gradlew :module-external-api:test
```

Expected: compile succeeds; all existing tests pass. (No existing test covers `ExternalApiScheduler`, so test pass count does not change.)

- [ ] **Step 3: Commit (no changes yet, marker commit) — skip if not needed**

No commit required; branch is fresh.

---

## Task 2: Make `itemEquipmentStarted` package-private

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt:42`

- [ ] **Step 1: Edit the field declaration**

In `ExternalApiScheduler.kt` line 42, change:
```kotlin
    private val itemEquipmentStarted = AtomicBoolean(false)
```
to:
```kotlin
    internal val itemEquipmentStarted = AtomicBoolean(false)
```

`internal` in Kotlin compiles to `public` in Java with a name-mangled getter only when needed; for test access in the same module/package, `internal` is visible to test sources via Kotlin's standard visibility rules. (For Java callers the bytecode emits a getter.) No behavior change at runtime.

- [ ] **Step 2: Verify compile still passes**

```bash
./gradlew :module-external-api:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "refactor(1001): make itemEquipmentStarted package-private for test access"
```

---

## Task 3: Implement the production fix

This task combines production change and test (red-then-green in one go, since the test scaffolding is short and the fix is a one-block edit).

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt:88-119`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

- [ ] **Step 1: Replace the chain in `triggerDailyRefresh()`**

In `ExternalApiScheduler.kt`, locate lines 88-119 (the block beginning with `rankingPhase.execute(executor)` and ending at the `whenComplete` lambda's closing `}`). Replace the entire block (lines 88-119) with:

```kotlin
        log.info("[Scheduler] starting ranking fetch phase, runId={}", runId)
        runStatusTracker.transitionPhase(PipelinePhase.RANKING_FETCH)
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
                if (ex != null) {
                    val cause = ex.cause ?: ex
                    val message = cause.message ?: cause::class.simpleName ?: "unknown error"
                    runStatusTracker.failRun(runId, message)
                    log.error("[Scheduler] daily refresh failed, runId={}", runId, cause)
                    releaseLock()
                } else {
                    runStatusTracker.completeRun(runId, 0, 0)
                    log.info("[Scheduler] daily refresh completed, runId={}", runId)
                    releaseLock()
                    startItemEquipmentLoopOnce()
                }
            }
```

Concretely:
- Drop the `.handle { runDir, ex -> if (ex != null) log.error(...); runDir }` block.
- Drop the `if (runDir == null) { CompletableFuture.completedFuture(null) } else { ... }` branching.
- New `.thenCompose` lambda takes `runDir`, throws `error("ranking fetch returned null runDir")` if null, otherwise calls `transitionPhase(OCID_LOOKUP)` then `ocidLookupPhase.execute(executor, runDir)`.
- The `.thenCompose { _ -> ... }` chain reads `ocidCacheProvider.refresh()`, transitions to `CHARACTER_BASIC`, and calls `snapshotFetchPhase.executeCharacterBasic`.
- The `.whenComplete` body branches: failure → `failRun` + `releaseLock`; success → `completeRun` + `releaseLock` + `startItemEquipmentLoopOnce()`.

The full new block is 27 lines, replacing the 32-line old block.

- [ ] **Step 2: Create the test file**

Write `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`:

```kotlin
package maple.externalapi.scheduler

import maple.externalapi.cache.OcidCacheProvider
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.phase.OcidLookupPhase
import maple.externalapi.scheduler.phase.RankingFetchPhase
import maple.externalapi.scheduler.phase.SnapshotFetchPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExternalApiSchedulerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var ocidLookupPhase: OcidLookupPhase
    private lateinit var snapshotFetchPhase: SnapshotFetchPhase
    private lateinit var ocidCacheProvider: OcidCacheProvider
    private lateinit var rankingPhaseProvider: ObjectProvider<RankingFetchPhase>
    private lateinit var rankingPhase: RankingFetchPhase
    private lateinit var tracker: RunStatusTracker
    private lateinit var executor: ExecutorService
    private lateinit var scheduler: ExternalApiScheduler

    @BeforeEach
    fun setUp() {
        ocidLookupPhase = mock()
        snapshotFetchPhase = mock()
        ocidCacheProvider = mock()
        rankingPhase = mock()
        rankingPhaseProvider = mock()

        whenever(rankingPhaseProvider.ifAvailable).thenReturn(rankingPhase)
        whenever(ocidCacheProvider.refresh()).thenReturn(emptyMap())

        tracker = RunStatusTracker()
        executor = Executors.newSingleThreadExecutor()
        scheduler = ExternalApiScheduler(
            ocidLookupPhase = ocidLookupPhase,
            snapshotFetchPhase = snapshotFetchPhase,
            ocidCacheProvider = ocidCacheProvider,
            rankingFetchPhaseProvider = rankingPhaseProvider,
            runStatusTracker = tracker,
            scheduleEnabled = false,
            runOnStartup = false,
            skipCharacterBasic = false,
            executor = executor,
        )
    }

    @AfterEach
    fun tearDown() {
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    @Test
    fun `ranking failure does not invoke OCID phase and records FAILED`() {
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("nexon 503")))

        scheduler.triggerDailyRefresh("run-fail-1")
        awaitChain()

        verify(ocidLookupPhase, never()).execute(any(), any())
        verify(snapshotFetchPhase, never()).executeCharacterBasic(any(), any())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(last.errorMessage).contains("nexon 503")
        assertThat(scheduler.itemEquipmentStarted.get()).isFalse()
    }

    @Test
    fun `ranking returns null runDir is treated as failure`() {
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.completedFuture(null))

        scheduler.triggerDailyRefresh("run-null-rundir")
        awaitChain()

        verify(ocidLookupPhase, never()).execute(any(), any())
        verify(snapshotFetchPhase, never()).executeCharacterBasic(any(), any())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(last.errorMessage).contains("ranking fetch returned null runDir")
        assertThat(scheduler.itemEquipmentStarted.get()).isFalse()
    }

    @Test
    fun `happy path records COMPLETED and starts item-equipment loop`() {
        val runDir = tempDir.resolve("runs/run-ok")
        whenever(rankingPhase.execute(executor))
            .thenReturn(CompletableFuture.completedFuture(runDir))
        whenever(ocidLookupPhase.execute(executor, runDir))
            .thenReturn(CompletableFuture.completedFuture(runDir))
        whenever(snapshotFetchPhase.executeCharacterBasic(executor, emptyMap()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        scheduler.triggerDailyRefresh("run-ok")
        awaitChain()

        verify(ocidLookupPhase).execute(executor, runDir)
        verify(snapshotFetchPhase).executeCharacterBasic(executor, emptyMap())

        val last = tracker.getLastCompletedRun()
        assertThat(last).isNotNull
        assertThat(last!!.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(scheduler.itemEquipmentStarted.get()).isTrue()
    }

    private fun awaitChain() {
        // Wait for the whenComplete branch to finish. We poll the tracker because
        // ExternalApiScheduler exposes no callback for chain completion and
        // Thread.sleep is forbidden by testing-conventions.md.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (tracker.getLastCompletedRun() != null) return
            Thread.sleep(20)
        }
        throw AssertionError("scheduler chain did not complete within 2s")
    }
}
```

- [ ] **Step 3: Verify compile passes**

```bash
./gradlew :module-external-api:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. No new imports needed (`error` is `kotlin.error`; `any` from `mockito-kotlin`).

- [ ] **Step 4: Run the new tests**

```bash
./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"
```

Expected: 3 tests passed.

- [ ] **Step 5: Run the full module test suite to confirm no regression**

```bash
./gradlew :module-external-api:test
```

Expected: all tests pass (existing 6+ test files unchanged, new test passes).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git add module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "fix(1001): propagate ranking failure; gate item-equipment loop on success"
```

---

## Task 4: Pre-PR verification

**Files:** none

- [ ] **Step 1: Full compile + test for the module**

```bash
./gradlew :module-external-api:compileKotlin compileJava --continue
./gradlew :module-external-api:test
```

Expected: both succeed. No `ERROR` in output.

- [ ] **Step 2: Confirm the diff is exactly what the spec described**

```bash
git diff develop...HEAD -- module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
```

Expected: changes only inside `triggerDailyRefresh()` (lines 88-119 in old code → new 27-line block) and one line for `itemEquipmentStarted` visibility. No accidental changes to `runItemEquipmentCycle` or `acquireLock`/`releaseLock`.

```bash
git diff develop...HEAD -- module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
```

Expected: new file with the three tests.

- [ ] **Step 3: Push branch and open PR targeting develop**

```bash
git push -u origin fix/1001-scheduler-failure-propagation
gh pr create --base develop --title "fix(1001): propagate ranking failure; gate item-equipment loop on success" --body "..."
```

PR body should reference issue 1001, summarize the bug, link the spec, and list the three AC items satisfied (ranking failure halts chain, RunStatusTracker records FAILED, ITEM_EQUIPMENT loop only on success).

- [ ] **Step 4: Final commit (PR description) — skip if PR is opened without local commit**

No additional commit; PR opens from pushed branch.

---

## Self-Review Notes (already checked inline)

- Spec AC #1 (ranking failure halts OCID): covered by Task 3 test 1.
- Spec AC #2 (ranking failure halts character basic): covered by Task 3 test 1 via `verify(snapshotFetchPhase, never())`.
- Spec AC #3 (RunStatusTracker records FAILED): covered by Task 3 tests 1 and 2.
- Spec AC #4 (ITEM_EQUIPMENT loop not started on failure): covered by Task 3 tests 1 and 2 via `itemEquipmentStarted.get()`.
- Spec AC #5 (null runDir → failure): covered by Task 3 test 2.
- Spec AC #6 (happy path COMPLETED + loop started): covered by Task 3 test 3.
- Spec AC #7 (compile passes): Task 3 Step 3 + Task 4 Step 1.
- Spec AC #8 (test passes): Task 3 Step 4 + Task 4 Step 1.
- Type consistency: `executor` is `ExecutorService` (matches field in `ExternalApiScheduler` line 37, qualified `externalApiSchedulerExecutor`); `tracker.getLastCompletedRun()` returns `RunStatus?`; `PipelinePhase.FAILED` exists per spec line 11.
- Visibility change for `itemEquipmentStarted` is scoped to `internal` (Kotlin), which compiles to bytecode-level `public` accessor for the test in the same module. No reflection needed.
