# Phase Infinite-Loop Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/internal/loop/phase/{phaseName}` and `POST /api/internal/stop/loop/phase/{phaseName}` to `module-external-api` so operators can keep a phase (typically `ITEM_EQUIPMENT`) running continuously, each iteration with a fresh `runId` sharing one `loopId`. Stop endpoint trips the existing per-phase `PhaseStopSignal`; iteration halts at the next chunk boundary; loop finalizes.

**Architecture:** New `PhaseLoopController` owns per-phase loop state (`ConcurrentHashMap<PipelinePhase, AtomicReference<LoopState>>`). On `startLoop`, allocates a `loopId`, snapshots upstream `runId` from `RunStatusTracker.getLastCompletedForPhase`, then chains iterations via dedicated virtual-thread `loopExecutor` bean. Each iteration calls `externalApiScheduler.triggerPhase(phase, runId, upstream, loopId)`; the new 4-arg overload propagates `loopId` to `acquirePhaseSlot` (records `loopId` on `RunStatus`) and **suppresses** the `stopSignal.clear(phase)` in `handlePhaseTerminal` so a stop request between iterations remains visible. Stop endpoint flips the existing `PhaseStopSignal`; current iteration throws `PhaseStoppedException` at chunk boundary; controller's `handleIterationEnd` calls `finalize` which clears the signal and transitions state to `STOPPED`. `/trigger/phase/{name}` and `/trigger/daily` reject 409 with `LOOP_ACTIVE` if a loop is active for the same phase. `/run-status` decorates response with `loopSummaries` map and per-`RunStatusView.loopId/loopActive` fields.

**Tech Stack:** Kotlin, Spring Boot, `ConcurrentHashMap`, `AtomicReference`, `CompletableFuture.whenComplete`, `ThreadPoolTaskExecutor` (virtual threads), JUnit 5 + AssertJ + mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-06-19-issue-1291-loop-endpoint-design.md`

**Blocked-by:** #1289 (merged), #1290 (merged).

---

## File Structure

**New files:**

| File | Purpose |
| --- | --- |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt` | `enum class LoopStatus { RUNNING, STOPPING, STOPPED }` |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt` | `data class LoopState` (mutable) with `loopId/phase/startedAt/status/iterationCount/lastRunId/lastError` |
| `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt` | `@Component` owning per-phase loop state + iteration chain |
| `module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt` | `@Bean("loopExecutor")` ThreadPoolTaskExecutor (virtual threads) |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/LoopStateTest.kt` | enum + data class invariants |
| `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt` | controller behavior + concurrency |
| `module-external-api/src/test/kotlin/maple/externalapi/loop/LoopExecutorConfigTest.kt` | bean wiring (sizing, virtual flag) |

**Modified files:**

| File | Change |
| --- | --- |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt` | add `val loopId: String? = null` |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` | `acquirePhaseSlot(phase, runId, loopId = null)` overload |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt` | add `loopSummaries: Map<String, LoopSummaryView>`; add `LoopSummaryView` data class |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` | inject `PhaseLoopController`; add `startLoop`/`stopLoop`; 409 trigger pre-check; `/run-status` decoration |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | `triggerPhase(..., loopId)` 4-arg overload; 4 `runXxxPhase` take `loopId`; `handlePhaseTerminal` skips `stopSignal.clear` when `loopId != null` |
| `module-external-api/src/main/resources/application.yml` | add `external-api.loop.executor.*` block under existing `external-api:` |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` | add `loopId` pass-through tests |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` | wire `PhaseLoopController`; add loop endpoint tests + 409 cases |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` | add `loopId` pass-through test for `triggerPhase` + `handlePhaseTerminal` signal-preservation test |

**Spec coverage map:**

| Spec section | Implementing task(s) |
| --- | --- |
| §2.1 LoopStatus enum | Task 1 |
| §2.2 LoopState data class | Task 1 |
| §2.3 PhaseLoopController | Tasks 5–8 |
| §2.4 loopExecutor bean | Task 4 |
| §3 RunStatus.loopId | Task 2 |
| §3.2 acquirePhaseSlot overload | Task 3 |
| §4.1 triggerPhase overload | Task 9 |
| §4.2 iteration lifecycle | Task 7 |
| §4.3 latestUpstreamRunId | Task 7 |
| §5.1 POST /loop/phase/{name} | Task 11 |
| §5.2 POST /stop/loop/phase/{name} | Task 11 |
| §5.4 trigger 409 LOOP_ACTIVE | Task 11 |
| §6 /run-status decoration | Task 12 |
| §13 shutdown | Task 8 |

---

### Task 1: LoopStatus enum + LoopState data class + tests

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/LoopStateTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/runstatus/LoopStateTest.kt`:

```kotlin
package maple.externalapi.runstatus

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoopStateTest {

    @Test
    fun `LoopStatus enum has three states in lifecycle order`() {
        val values = LoopStatus.values().map { it.name }
        assertEquals(listOf("RUNNING", "STOPPING", "STOPPED"), values)
    }

    @Test
    fun `new LoopState defaults to RUNNING with zero iterations and no last runId`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.parse("2026-06-19T00:00:00Z"),
        )
        assertEquals(LoopStatus.RUNNING, state.status)
        assertEquals(0, state.iterationCount)
        assertNull(state.lastRunId)
        assertNull(state.lastError)
    }

    @Test
    fun `LoopState mutates status, iterationCount, lastRunId, lastError in place`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.OCID_LOOKUP,
            startedAt = Instant.parse("2026-06-19T00:00:00Z"),
        )
        state.iterationCount = 3
        state.lastRunId = "run-3"
        state.lastError = "boom"
        state.status = LoopStatus.STOPPING

        assertEquals(3, state.iterationCount)
        assertEquals("run-3", state.lastRunId)
        assertEquals("boom", state.lastError)
        assertEquals(LoopStatus.STOPPING, state.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.LoopStateTest"`
Expected: Compile error — `LoopStatus` and `LoopState` unresolved.

- [ ] **Step 3: Create LoopStatus enum**

Create `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt`:

```kotlin
package maple.externalapi.runstatus

/**
 * Lifecycle state of a phase loop.
 *  - RUNNING: at least one iteration has been submitted; loop is active.
 *  - STOPPING: a stop was requested or an iteration failed; current iteration
 *    may still be in-flight, but no new iteration will be submitted.
 *  - STOPPED: terminal; controller has called [PhaseLoopController.finalize].
 */
enum class LoopStatus {
    RUNNING,
    STOPPING,
    STOPPED,
}
```

- [ ] **Step 4: Create LoopState data class**

Create `module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt`:

```kotlin
package maple.externalapi.runstatus

import java.time.Instant

/**
 * Mutable per-loop state held by `PhaseLoopController`. Stored under
 * `AtomicReference<LoopState>` for lock-free updates from iteration
 * `whenComplete` callbacks.
 *
 * `iterationCount` and `lastRunId` reflect the most recent iteration submitted
 * (or completed) at the time of read; they are advisory, not transactional.
 */
data class LoopState(
    val loopId: String,
    val phase: PipelinePhase,
    val startedAt: Instant,
    var status: LoopStatus = LoopStatus.RUNNING,
    var iterationCount: Int = 0,
    var lastRunId: String? = null,
    var lastError: String? = null,
)
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.LoopStateTest"`
Expected: PASS (3/3).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt \
        module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/LoopStateTest.kt
git commit -m "feat(ext-api): add LoopStatus enum + LoopState data class

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: RunStatus.loopId field

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt`

- [ ] **Step 1: Add failing test for loopId field**

Append to `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt` (create the file if it does not exist with the contents from the 1290 plan; otherwise append only):

```kotlin
    @Test
    fun `RunStatus loopId defaults to null for non-loop runs`() {
        val status = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.EPOCH,
        )
        assertNull(status.loopId)
    }

    @Test
    fun `RunStatus loopId can be set on construction for loop iterations`() {
        val status = RunStatus(
            runId = "run-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = Instant.EPOCH,
            loopId = "L-7",
        )
        assertEquals("L-7", status.loopId)
    }
```

Add the imports at the top of the file if missing:

```kotlin
import org.junit.jupiter.api.Assertions.assertNull
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTest"`
Expected: Compile error — `loopId` constructor arg unresolved.

- [ ] **Step 3: Add `loopId` field to RunStatus**

Edit `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt` — replace the data class body:

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
    val loopId: String? = null,
) {
    @get:JsonProperty("terminal")
    val isTerminal: Boolean
        get() = phase == PipelinePhase.COMPLETED
            || phase == PipelinePhase.FAILED
            || phase == PipelinePhase.STOPPED
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTest"`
Expected: PASS (existing 4 + 2 new = 6/6).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt
git commit -m "feat(ext-api): add loopId field to RunStatus

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: RunStatusTracker.acquirePhaseSlot loopId overload + tests

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`

- [ ] **Step 1: Add failing tests for loopId overload**

Append to `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`:

```kotlin
    @Test
    fun `acquirePhaseSlot with loopId stores loopId on slot record`() {
        val acquired = tracker.acquirePhaseSlot(
            phase = PipelinePhase.ITEM_EQUIPMENT,
            runId = "run-1",
            loopId = "L-1",
        )
        assertThat(acquired).isNotNull
        assertThat(acquired!!.loopId).isEqualTo("L-1")
        assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)?.loopId).isEqualTo("L-1")
    }

    @Test
    fun `acquirePhaseSlot without loopId leaves loopId null on slot record`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertThat(acquired).isNotNull
        assertThat(acquired!!.loopId).isNull()
    }

    @Test
    fun `acquirePhaseSlot overwrites loopId on terminal-overwrite acquire`() {
        // First loop iteration completes; terminal record persists with loopId
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1", "L-1")
        tracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)

        // Next iteration within same loop overwrites the terminal record
        val next = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-2", "L-1")
        assertThat(next).isNotNull
        assertThat(next!!.loopId).isEqualTo("L-1")
        assertThat(next.runId).isEqualTo("run-2")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`
Expected: Compile error — `acquirePhaseSlot` does not accept `loopId` arg.

- [ ] **Step 3: Add loopId overload to acquirePhaseSlot**

In `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`, replace the existing `acquirePhaseSlot`:

```kotlin
    /**
     * Atomic acquire. Returns the new RunStatus if acquired (slot was null OR terminal);
     * returns null if slot occupied by a non-terminal run.
     * Used by ExternalApiScheduler.triggerPhase and the /api/internal/trigger/phase controller.
     *
     * [loopId] is recorded on the slot record when non-null so /run-status can correlate
     * a phase run with the loop it belongs to. Pass null for single-shot (non-loop) runs.
     */
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
            log.info("[RunStatus] phase-slot acquired phase={} runId={} loopId={}",
                phase, runId, loopId ?: "-")
            result
        } else {
            log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, result.runId)
            null
        }
    }
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`
Expected: PASS (existing + 3 new = 23/23).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "feat(ext-api): RunStatusTracker.acquirePhaseSlot loopId overload

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: LoopExecutorConfig bean + YAML config + tests

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/loop/LoopExecutorConfigTest.kt`
- Modify: `module-external-api/src/main/resources/application.yml`

- [ ] **Step 1: Add YAML block under existing `external-api:` root**

In `module-external-api/src/main/resources/application.yml`, locate the existing `external-api:` block (line 34) and add the `loop` subsection immediately after the `schedule:` block (after `skip-character-basic: false` on line 62). The new block must be indented at 2 spaces under `external-api:` and 4 spaces under `loop:`:

```yaml
  loop:
    executor:
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 64
      thread-name-prefix: ext-api-loop-
      virtual-threads: true
      await-termination-seconds: 30
```

Verify with: `grep -n "loop:" module-external-api/src/main/resources/application.yml`
Expected: one match, inside the `external-api:` block (line ~63).

- [ ] **Step 2: Write failing test for the executor bean**

Create `module-external-api/src/test/kotlin/maple/externalapi/loop/LoopExecutorConfigTest.kt`:

```kotlin
package maple.externalapi.loop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

class LoopExecutorConfigTest {

    @Test
    fun `loopExecutor bean is a virtual-thread ThreadPoolTaskExecutor with configured sizing`() {
        val cfg = LoopExecutorConfig()
        val executor = cfg.loopExecutor(
            corePoolSize = 4,
            maxPoolSize = 16,
            queueCapacity = 64,
            threadNamePrefix = "ext-api-loop-",
            virtualThreads = true,
            awaitTerminationSeconds = 30,
        )

        assertNotNull(executor)
        assertTrue(executor is ThreadPoolTaskExecutor)
        val tpe = executor as ThreadPoolTaskExecutor
        assertEquals(4, tpe.corePoolSize)
        assertEquals(16, tpe.maxPoolSize)
        assertEquals(64, tpe.queueCapacity)
        assertEquals("ext-api-loop-", tpe.threadNamePrefix)
        assertTrue(tpe.isWaitForTasksToCompleteOnShutdown)
        assertEquals(30, tpe.awaitTerminationSeconds)

        tpe.shutdown()
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.LoopExecutorConfigTest"`
Expected: Compile error — `LoopExecutorConfig` unresolved.

- [ ] **Step 4: Create LoopExecutorConfig**

Create `module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt`:

```kotlin
package maple.externalapi.loop

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Loop executor configuration (Issue #1291).
 *
 * <p>Dedicated virtual-thread pool for `PhaseLoopController` iterations.
 * 분리된 from `ExternalApiScheduler` 의 inline `newVirtualThreadPerTaskExecutor`
 * (daily + per-phase triggers) so a slow loop iteration can never starve the
 * scheduler's submit path, and so @PreDestroy can drain the pool independently
 * of the scheduler's lifecycle.
 *
 * <h3>Sizing (per #1128 sizing principles)</h3>
 * <ul>
 *   <li>core: 4 — three loopable phases + headroom</li>
 *   <li>max: 16 — burst capacity if multiple loops overlap (loop + one-shot)</li>
 *   <li>queue: 64 — back-pressure window before caller blocks</li>
 *   <li>virtual: true — per-iteration Virtual Thread (Issue #1128 precedent)</li>
 *   <li>await-termination: 30s — matches `spring.lifecycle.timeout-per-shutdown-phase`</li>
 * </ul>
 */
@Configuration
class LoopExecutorConfig {

    private val log = LoggerFactory.getLogger(LoopExecutorConfig::class.java)

    @Bean(name = ["loopExecutor"])
    fun loopExecutor(
        @Value("\${external-api.loop.executor.core-pool-size:4}") corePoolSize: Int,
        @Value("\${external-api.loop.executor.max-pool-size:16}") maxPoolSize: Int,
        @Value("\${external-api.loop.executor.queue-capacity:64}") queueCapacity: Int,
        @Value("\${external-api.loop.executor.thread-name-prefix:ext-api-loop-}") threadNamePrefix: String,
        @Value("\${external-api.loop.executor.virtual-threads:true}") virtualThreads: Boolean,
        @Value("\${external-api.loop.executor.await-termination-seconds:30}") awaitTerminationSeconds: Int,
    ): AsyncTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = corePoolSize
        executor.maxPoolSize = maxPoolSize
        executor.queueCapacity = queueCapacity
        executor.setThreadNamePrefix(threadNamePrefix)
        executor.setVirtualThreads(virtualThreads)
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds)
        executor.initialize()
        log.info(
            "[LoopExecutorConfig] loopExecutor initialized: core={}, max={}, queue={}, virtual={}",
            corePoolSize, maxPoolSize, queueCapacity, virtualThreads,
        )
        return executor
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.LoopExecutorConfigTest"`
Expected: PASS (1/1).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt \
        module-external-api/src/main/resources/application.yml \
        module-external-api/src/test/kotlin/maple/externalapi/loop/LoopExecutorConfigTest.kt
git commit -m "feat(ext-api): loopExecutor bean + external-api.loop.executor config

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: PhaseLoopController skeleton + startLoop + hasActiveLoop + tests

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`

- [ ] **Step 1: Write failing test for startLoop + hasActiveLoop + getLoopState**

Create `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`:

```kotlin
package maple.externalapi.loop

import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import maple.externalapi.runstatus.LoopState
import maple.externalapi.runstatus.LoopStatus
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.ExternalApiScheduler
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.task.AsyncTaskExecutor

class PhaseLoopControllerTest {

    private val runStatusTracker = RunStatusTracker(Clock.systemUTC())
    private val scheduler = mock<ExternalApiScheduler>()
    private val stopSignal = PhaseStopSignal()
    private val runIdGenerator = RunIdGenerator(Clock.systemUTC())
    // No-op executor for tests that verify single submit via scheduler mock.
    // Prevents the synchronous-whenComplete chain from re-entering submitIteration.
    private val noopExecutor = AsyncTaskExecutor { /* drop submitted Runnable */ }
    // Runs the first submission inline, drops the rest. Lets iteration-chain
    // tests verify one chained submit without infinite recursion.
    private val oneShotInlineExecutor = OneShotInlineExecutor()

    private fun controller(executor: AsyncTaskExecutor = noopExecutor) = PhaseLoopController(
        externalApiScheduler = scheduler,
        runStatusTracker = runStatusTracker,
        runIdGenerator = runIdGenerator,
        stopSignal = stopSignal,
        loopExecutor = executor,
    )

    @Test
    fun `startLoop on ITEM_EQUIPMENT returns LoopState with RUNNING status and submits first iteration`() {
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val state = controller().startLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(PipelinePhase.ITEM_EQUIPMENT, state.phase)
        assertEquals(LoopStatus.RUNNING, state.status)
        assertNotNull(state.loopId)
        assertThat(state.loopId).hasSizeGreaterThan(0)
        // first iteration submitted via scheduler
        verify(scheduler).triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `startLoop on duplicate phase returns existing state without resubmit`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Unit>())

        val ctrl = controller()
        val first = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val second = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(first.loopId, second.loopId)
        verify(scheduler, org.mockito.kotlin.times(1))
            .triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `startLoop rejects non-loopable phase with IllegalArgumentException`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            controller().startLoop(PipelinePhase.RANKING_FETCH)
        }
        assertTrue(ex.message!!.contains("RANKING_FETCH"))
    }

    @Test
    fun `hasActiveLoop true while RUNNING, false after STOPPED`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ctrl = controller()
        assertFalse(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
        ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertTrue(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))

        // Simulate finalize
        val state = ctrl.getLoopState(PipelinePhase.ITEM_EQUIPMENT)!!
        state.status = LoopStatus.STOPPED
        assertFalse(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `getLoopState returns null for phase with no active loop`() {
        assertNull(controller().getLoopState(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `startLoop returns iterationCount=0 and lastRunId null`() {
        // Pending future: keeps whenComplete from firing inline, so we observe
        // the state right after startLoop returns, before any iteration completes.
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture<Unit>())

        val state = controller().startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(0, state.iterationCount)
        assertNull(state.lastRunId)
        verify(scheduler).triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `handleIterationEnd increments iterationCount on success`() {
        whenever(scheduler.triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        // oneShotInline: iter 1's whenComplete fires inline, count becomes 1,
        // submit iter 2 runs inline, iter 2's whenComplete fires, count becomes 2,
        // submit iter 3 is dropped by the executor. count ends at 2.
        val state = controller(oneShotInlineExecutor).startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(2, state.iterationCount)
        // lastRunId reflects the most recent completed iteration (iter 2).
        assertNotNull(state.lastRunId)
    }

    @Test
    fun `successful iteration chains into next iteration via loopExecutor`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        // oneShotInline: iter 1 succeeds, iter 2 is submitted but blocked.
        val ctrl = controller(oneShotInlineExecutor)
        ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)

        // First iteration's whenComplete fired inline; handleIterationEnd ran;
        // loopExecutor.execute { submitIteration(iter 2) } was submitted inline
        // (oneShot allowed one run); second iteration's scheduler.triggerPhase was called.
        // Iter 2's whenComplete fired, count went to 2, submit iter 3 dropped.
        verify(scheduler, org.mockito.kotlin.times(2))
            .triggerPhase(eq(PipelinePhase.ITEM_EQUIPMENT), any(), anyOrNull(), anyOrNull())
    }

    private class OneShotInlineExecutor : AsyncTaskExecutor {
        private var used = false
        override fun execute(task: Runnable) {
            if (used) return
            used = true
            task.run()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.PhaseLoopControllerTest"`
Expected: Compile error — `PhaseLoopController` unresolved.

- [ ] **Step 3: Create PhaseLoopController skeleton (Task 5 subset)**

Create `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`:

```kotlin
package maple.externalapi.loop

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import maple.externalapi.runstatus.LoopState
import maple.externalapi.runstatus.LoopStatus
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.runstatus.RunStatusTracker
import maple.externalapi.scheduler.ExternalApiScheduler
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.phase.RunIdGenerator
import org.slf4j.LoggerFactory
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Component

/**
 * Owns per-phase infinite-loop state. One loop per phase; phases in
 * [loopablePhases]. Each iteration gets a fresh `runId`; the loopId
 * is shared across iterations and recorded on each `RunStatus` for
 * `/run-status` correlation.
 *
 * Iteration chaining:
 *   startLoop -> runIteration -> scheduler.triggerPhase (with loopId)
 *                          -> handleIterationEnd -> submit next runIteration
 *                          -> finalize (status=STOPPED, stopSignal.clear)
 *
 * Stop flow: `/stop/loop/phase/{name}` -> [PhaseStopSignal.requestStop] ->
 * current iteration throws `PhaseStoppedException` at chunk boundary ->
 * `handleIterationEnd` sets status=STOPPING -> `finalize`.
 *
 * @see docs/superpowers/specs/2026-06-19-issue-1291-loop-endpoint-design.md
 */
@Component
class PhaseLoopController(
    private val externalApiScheduler: ExternalApiScheduler,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    private val stopSignal: PhaseStopSignal,
    private val loopExecutor: AsyncTaskExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(PhaseLoopController::class.java)

    private val loopablePhases: Set<PipelinePhase> = setOf(
        PipelinePhase.ITEM_EQUIPMENT,
        PipelinePhase.CHARACTER_BASIC,
    )

    private val loops = ConcurrentHashMap<PipelinePhase, AtomicReference<LoopState>>()

    /**
     * Allocate a fresh loopId for [phase], snapshot the most-recent upstream
     * `runId`, submit the first iteration. If a loop is already active for
     * [phase], return the existing state without submitting a new iteration.
     */
    fun startLoop(phase: PipelinePhase): LoopState {
        require(phase in loopablePhases) { "phase $phase is not loopable; allowed: $loopablePhases" }

        val ref = loops.computeIfAbsent(phase) { AtomicReference(null) }
        val newLoopId = UUID.randomUUID().toString()

        val created = ref.updateAndGet { current ->
            if (current != null && current.status != LoopStatus.STOPPED) {
                current
            } else {
                LoopState(
                    loopId = newLoopId,
                    phase = phase,
                    startedAt = java.time.Instant.now(clock),
                )
            }
        }

        if (created.loopId == newLoopId) {
            log.info("[Loop] startLoop phase={} loopId={}", phase, created.loopId)
            // lastRunId remains null until the first iteration completes.
            // iterationCount defaults to 0 (matches spec §5.1 LOOP_STARTED response).
            submitIteration(phase, created.loopId, runIdGenerator.newRunId(), n = 1)
        } else {
            log.info("[Loop] startLoop reused existing loop phase={} loopId={}", phase, created.loopId)
        }
        return created
    }

    fun hasActiveLoop(phase: PipelinePhase): Boolean {
        val state = loops[phase]?.get() ?: return false
        return state.status != LoopStatus.STOPPED
    }

    fun getLoopState(phase: PipelinePhase): LoopState? = loops[phase]?.get()

    fun activeLoops(): List<LoopState> =
        loops.values.mapNotNull { it.get() }.filter { it.status != LoopStatus.STOPPED }

    private fun submitIteration(phase: PipelinePhase, loopId: String, runId: String, n: Int) {
        val upstream = latestUpstreamRunId(phase)
        try {
            externalApiScheduler.triggerPhase(phase, runId, upstream, loopId)
                .whenComplete { _, ex -> handleIterationEnd(phase, loopId, runId, ex, n) }
        } catch (ex: Throwable) {
            log.error("[Loop] iteration submit failed phase={} loopId={} iter={}", phase, loopId, n, ex)
            val state = loops[phase]?.get() ?: return
            if (state.loopId != loopId) return
            state.lastError = ex.message
            state.status = LoopStatus.STOPPING
            finalize(phase, loopId)
        }
    }

    /**
     * Both loopable phases (ITEM_EQUIPMENT, CHARACTER_BASIC) consume the
     * OCID_LOOKUP's last completed runId as upstream. OCID_LOOKUP itself
     * is not loopable (no upstream source; `runOcidPhase` requires
     * upstreamRunId).
     */
    private fun latestUpstreamRunId(phase: PipelinePhase): String? =
        runStatusTracker.getLastCompletedForPhase(PipelinePhase.OCID_LOOKUP)?.runId

    private fun handleIterationEnd(phase: PipelinePhase, loopId: String, runId: String, ex: Throwable?, n: Int) {
        val state = loops[phase]?.get() ?: return
        if (state.loopId != loopId) return
        // lastRunId = "most recent **completed** iteration's runId". Stable.
        // Set unconditionally on terminal — success, stop, and fail all set it.
        state.lastRunId = runId
        when {
            ex is maple.externalapi.scheduler.PhaseStoppedException -> {
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
        // Count this iteration as completed (terminal or successful). Submitted-but-not-finished
        // iterations do not count. Spec §5.1 LOOP_STARTED response shows iterationCount:0.
        state.iterationCount += 1
        if (state.status == LoopStatus.STOPPING) {
            finalize(phase, loopId)
        } else {
            val nextN = n + 1
            val nextRunId = runIdGenerator.newRunId()
            // lastRunId is NOT updated to nextRunId here. It reflects the most
            // recent *completed* iteration; the next iteration's runId will
            // be set when it completes (or fails/stops).
            loopExecutor.execute { submitIteration(phase, loopId, nextRunId, nextN) }
        }
    }

    private fun finalize(phase: PipelinePhase, loopId: String) {
        val state = loops[phase]?.get() ?: return
        if (state.loopId != loopId) return
        state.status = LoopStatus.STOPPED
        stopSignal.clear(phase)
        log.info("[Loop] stopped loopId={} phase={} iterations={} lastError={}",
            loopId, phase, state.iterationCount, state.lastError ?: "none")
    }
}
```

- [ ] **Step 4: Compile (tests still don't pass yet because `triggerPhase` 4-arg doesn't exist)**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`
Expected: BUILD SUCCESSFUL for compile; tests fail because scheduler mock signature mismatch.

- [ ] **Step 5: Commit (skeleton only — do not run tests yet)**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt
git commit -m "feat(ext-api): PhaseLoopController skeleton with startLoop

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: ExternalApiScheduler.triggerPhase 4-arg overload + loopId signal preservation

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` (or `ExternalApiSchedulerStopTest.kt` if extending)

- [ ] **Step 1: Add failing test for loopId pass-through + signal preservation**

Append to `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerStopTest.kt`:

```kotlin
    @Test
    fun `triggerPhase with loopId propagates loopId to acquirePhaseSlot`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        scheduler.triggerPhase(PipelinePhase.ITEM_EQUIPMENT, "run-1", "upstream", "L-1").join()

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals("L-1", status?.loopId)
    }

    @Test
    fun `triggerPhase with loopId does NOT clear stopSignal on success`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        // Pre-trip the stop signal as if a stop arrived between iterations
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))

        scheduler.triggerPhase(PipelinePhase.ITEM_EQUIPMENT, "run-1", "upstream", "L-1").join()

        // Signal must be preserved for the next iteration's runIteration top check
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT),
            "loop iteration must preserve stop signal so next iteration can see it")
    }

    @Test
    fun `triggerPhase without loopId clears stopSignal on success (single-shot path preserved)`() {
        val runStatusTracker = realRunStatusTracker()
        val stopSignal = PhaseStopSignal()
        val itemEquipmentPhase = mock<ItemEquipmentFetchPhase>()
        whenever(itemEquipmentPhase.execute(any<ExecutorService>(), any<List<Map.Entry<String, String>>>(), any<String>()))
            .thenReturn(CompletableFuture.completedFuture(Unit))

        val scheduler = itemEquipmentScheduler(runStatusTracker, stopSignal, itemEquipmentPhase, ocidEntries = mapOf("ign1" to "ocid1"))

        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        scheduler.triggerPhase(PipelinePhase.ITEM_EQUIPMENT, "run-1", "upstream", null).join()

        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerStopTest"`
Expected: Compile error — `triggerPhase(..., loopId)` 4-arg overload unresolved.

- [ ] **Step 3: Add `loopId` parameter to 4 `runXxxPhase` methods + `handlePhaseTerminal`**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`, change the 4 `runXxxPhase` signatures and the `acquirePhaseSlot` calls. Use these exact replacements.

Replace `runRankingPhase` (the `acquirePhaseSlot` call):

```kotlin
    fun runRankingPhase(runId: String, upstreamRunId: String?, loopId: String? = null): CompletableFuture<Void> {
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, runId, loopId)
```

Replace `runOcidPhase` (the `acquirePhaseSlot` call):

```kotlin
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, runId, loopId)
```

Replace `runCharBasicPhase` (the `acquirePhaseSlot` call):

```kotlin
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId, loopId)
```

Replace `runItemEquipmentPhase` (the `acquirePhaseSlot` call):

```kotlin
        val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId, loopId)
```

Replace `handlePhaseTerminal` (the whole method):

```kotlin
    private fun handlePhaseTerminal(
        phase: PipelinePhase,
        phaseLabel: String,
        runId: String,
        ex: Throwable?,
        loopId: String? = null,
        drainMetrics: () -> Pair<Int, Long> = { 0 to 0L },
        failureLogContext: () -> String? = { null },
    ) {
        // Loop iterations preserve the stop signal so a stop request that
        // arrived between iterations is still visible at the next runIteration
        // top check. Single-shot (loopId == null) clears on every terminal,
        // matching the original behavior.
        val clearSignal = loopId == null
        when {
            ex is PhaseStoppedException -> {
                log.info("[Scheduler] {} stopped runId={} phase={}", phaseLabel, runId, ex.phase)
                val (chunks, records) = drainMetrics()
                runStatusTracker.stopRun(phase, runId, chunks, records)
                if (clearSignal) stopSignal.clear(phase)
            }
            ex != null -> {
                val extra = failureLogContext()?.let { " $it" } ?: ""
                log.error("[Scheduler] {} failed runId={}$extra", phaseLabel, runId, ex)
                runStatusTracker.failRun(phase, runId, ex.message ?: "unknown")
                runStatusTracker.releasePhaseSlot(phase, runId)
                if (clearSignal) stopSignal.clear(phase)
            }
            else -> {
                val (chunks, records) = drainMetrics()
                runStatusTracker.completeRun(phase, runId, chunks, records)
                if (clearSignal) stopSignal.clear(phase)
            }
        }
    }
```

Now pass `loopId` from each of the 4 `whenComplete` blocks to `handlePhaseTerminal`. In `runRankingPhase`, replace the `whenComplete`:

```kotlin
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.RANKING_FETCH,
                    phaseLabel = "runRankingPhase",
                    runId = runId,
                    ex = ex,
                    loopId = loopId,
                )
            }
            .thenRun { }
```

In `runOcidPhase`, replace the `whenComplete`:

```kotlin
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.OCID_LOOKUP,
                    phaseLabel = "runOcidPhase",
                    runId = runId,
                    ex = ex,
                    loopId = loopId,
                    failureLogContext = { "upstreamRunId={$upstreamRunId}" },
                )
            }
            .thenRun { }
```

In `runCharBasicPhase`, replace the `whenComplete`:

```kotlin
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.CHARACTER_BASIC,
                    phaseLabel = "runCharBasicPhase",
                    runId = runId,
                    ex = ex,
                    loopId = loopId,
                )
            }
            .thenRun { }
```

In `runItemEquipmentPhase`, replace the `whenComplete`:

```kotlin
        return future
            .whenComplete { _, ex ->
                handlePhaseTerminal(
                    phase = PipelinePhase.ITEM_EQUIPMENT,
                    phaseLabel = "runItemEquipmentPhase",
                    runId = runId,
                    ex = ex,
                    loopId = loopId,
                    drainMetrics = { schedulerMetrics.drainRunChunks().toInt() to schedulerMetrics.drainRunRecords() },
                )
            }
            .thenRun { }
```

- [ ] **Step 4: Add 4-arg `triggerPhase` overload**

Replace the existing `triggerPhase`:

```kotlin
    fun triggerPhase(
        phase: PipelinePhase,
        runId: String,
        upstreamRunId: String?,
        loopId: String? = null,
    ): CompletableFuture<Void> {
        return when (phase) {
            PipelinePhase.RANKING_FETCH -> runRankingPhase(runId, upstreamRunId, loopId)
            PipelinePhase.OCID_LOOKUP -> runOcidPhase(runId, upstreamRunId, loopId)
            PipelinePhase.CHARACTER_BASIC -> runCharBasicPhase(runId, upstreamRunId, loopId)
            PipelinePhase.ITEM_EQUIPMENT -> runItemEquipmentPhase(runId, upstreamRunId, loopId)
            else -> CompletableFuture.failedFuture(
                IllegalArgumentException("Phase $phase is not a standalone-triggerable phase")
            )
        }
    }
```

- [ ] **Step 5: Run all loop + scheduler tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.*" --tests "maple.externalapi.scheduler.*"`
Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerStopTest.kt
git commit -m "feat(ext-api): triggerPhase loopId overload + handlePhaseTerminal signal preservation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: PhaseLoopController stopLoop + tests

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`

- [ ] **Step 1: Add failing tests for stopLoop**

Append to `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`:

```kotlin
    @Test
    fun `stopLoop on active loop sets stopSignal and returns existing state`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ctrl = controller()
        val state = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val stopped = ctrl.stopLoop(PipelinePhase.ITEM_EQUIPMENT)

        assertEquals(state.loopId, stopped!!.loopId)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `stopLoop on no active loop returns null`() {
        assertNull(controller().stopLoop(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `finalize clears stopSignal and transitions status to STOPPED`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ctrl = controller()
        val state = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT) // simulate

        // Manually drive finalize path
        state.status = LoopStatus.STOPPING
        val finalizeRef = maple.externalapi.loop.PhaseLoopController::class.java
            .getDeclaredMethod("finalize", PipelinePhase::class.java, String::class.java)
            .apply { isAccessible = true }
        finalizeRef.invoke(ctrl, PipelinePhase.ITEM_EQUIPMENT, state.loopId)

        assertEquals(LoopStatus.STOPPED, state.status)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.PhaseLoopControllerTest"`
Expected: Compile error — `stopLoop` unresolved.

- [ ] **Step 3: Add `stopLoop` to PhaseLoopController**

In `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`, add the method after `activeLoops`:

```kotlin
    /**
     * Trip the per-phase stop flag. Current iteration (if any) will throw
     * `PhaseStoppedException` at next chunk boundary; the controller then
     * finalizes the loop and clears the stop signal.
     *
     * Returns the existing loop state if a loop is active; null otherwise.
     * Idempotent — re-issuing `stopLoop` while a loop is already STOPPING is
     * a no-op (signal was already set).
     */
    fun stopLoop(phase: PipelinePhase): LoopState? {
        val state = loops[phase]?.get() ?: return null
        if (state.status == LoopStatus.STOPPED) return null
        stopSignal.requestStop(phase)
        state.status = LoopStatus.STOPPING
        log.info("[Loop] stop requested phase={} loopId={} iterations={}",
            phase, state.loopId, state.iterationCount)
        return state
    }
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.PhaseLoopControllerTest"`
Expected: PASS (existing 6 + 3 new = 9/9).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt
git commit -m "feat(ext-api): PhaseLoopController.stopLoop

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: PhaseLoopController @PreDestroy shutdown + tests

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`

- [ ] **Step 1: Add failing test for shutdown**

Append to `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt`:

```kotlin
    @Test
    fun `shutdown trips stopSignal for all active loops and transitions to STOPPED`() {
        whenever(scheduler.triggerPhase(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(CompletableFuture.completedFuture(null))

        val ctrl = controller()
        val ieState = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        val cbState = ctrl.startLoop(PipelinePhase.CHARACTER_BASIC)

        assertTrue(ctrl.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(ctrl.hasActiveLoop(PipelinePhase.CHARACTER_BASIC))

        ctrl.shutdown()

        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(stopSignal.isStopRequested(PipelinePhase.CHARACTER_BASIC))
        assertEquals(LoopStatus.STOPPED, ieState.status)
        assertEquals(LoopStatus.STOPPED, cbState.status)
        // After shutdown, a fresh startLoop for the same phase must allocate a NEW loopId,
        // not return the stale STOPPED state. Verifies the cleanup is complete.
        val fresh = ctrl.startLoop(PipelinePhase.ITEM_EQUIPMENT)
        assertNotEquals(ieState.loopId, fresh.loopId, "shutdown must leave state clean for fresh startLoop")
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.PhaseLoopControllerTest"`
Expected: Compile error — `shutdown` unresolved.

- [ ] **Step 3: Add @PreDestroy shutdown to PhaseLoopController**

In `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt`, add imports at the top:

```kotlin
import jakarta.annotation.PreDestroy
```

Add the method after `finalize`:

```kotlin
    /**
     * Spring `@PreDestroy` hook. Trips the per-phase stop signal so any
     * in-flight iteration halts at its next chunk boundary, then calls
     * `finalize` for each active loop. This transitions the loop state
     * directly to `STOPPED` so the next `startLoop` for the same phase
     * allocates a fresh loopId (no stale `STOPPING` left behind if the
     * loop is between iterations or the executor's 30s drain timeout
     * drops the pending submit task).
     *
     * The executor bean's own `setWaitForTasksToCompleteOnShutdown(true)`
     * + `setAwaitTerminationSeconds(30)` provides the actual drain window
     * (matches `spring.lifecycle.timeout-per-shutdown-phase`). This method
     * does not block.
     */
    @PreDestroy
    fun shutdown() {
        val active = activeLoops()
        if (active.isEmpty()) {
            log.info("[Loop] shutdown: no active loops")
            return
        }
        log.info("[Loop] shutdown: stopping {} active loops", active.size)
        for (state in active) {
            stopSignal.requestStop(state.phase)
            finalize(state.phase, state.loopId)
        }
    }
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.loop.PhaseLoopControllerTest"`
Expected: PASS (10/10).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt
git commit -m "feat(ext-api): PhaseLoopController @PreDestroy shutdown

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: RunStatusResponse LoopSummaryView + decoration-ready shape

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt`

- [ ] **Step 1: Add `LoopSummaryView` data class + `loopSummaries` field**

Replace the file contents:

```kotlin
package maple.externalapi.runstatus

import java.time.Instant

/**
 * Per-phase run-status payload. Active slots and last-completed run per phase.
 * The `current` and `lastCompleted` fields are legacy aliases for
 * single-slot API consumers; deprecated.
 */
data class RunStatusResponse(
    val slots: Map<PipelinePhase, RunStatus?>,
    val lastCompletedByPhase: Map<PipelinePhase, RunStatus?>,
    @Deprecated("Use slots map instead") val current: RunStatus?,
    @Deprecated("Use lastCompletedByPhase map instead") val lastCompleted: RunStatus?,
    val loopSummaries: Map<String, LoopSummaryView> = emptyMap(),
)

/**
 * Per-active-loop summary. Keyed by `phase.name` in the parent
 * [RunStatusResponse.loopSummaries] map.
 */
data class LoopSummaryView(
    val loopId: String,
    val phase: String,
    val startedAt: Instant,
    val iterationCount: Int,
    val lastRunId: String?,
    val status: String, // RUNNING | STOPPING | STOPPED
    val lastError: String?,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`
Expected: BUILD SUCCESSFUL. (No tests added in this task — decoration is wired in Task 12.)

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt
git commit -m "feat(ext-api): RunStatusResponse loopSummaries + LoopSummaryView

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: InternalApiController loop endpoints + trigger 409 pre-check

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Wire PhaseLoopController into InternalApiControllerTest setup**

In `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`, add a field for a mock loop controller and a default for tests that do not care about loops. Insert at the top of the class (near other `lateinit var`):

```kotlin
    private lateinit var phaseLoopController: maple.externalapi.loop.PhaseLoopController
```

In `setUp()`, after `controller = InternalApiController(...)`, add:

```kotlin
        phaseLoopController = org.mockito.kotlin.mock()
        // Default: no active loops
        org.mockito.kotlin.whenever(phaseLoopController.hasActiveLoop(org.mockito.kotlin.any())).thenReturn(false)
        org.mockito.kotlin.whenever(phaseLoopController.getLoopState(org.mockito.kotlin.any())).thenReturn(null)
```

Refactor the `setUp()` to construct the controller with the loop arg. Replace the existing `controller = InternalApiController(...)` line with:

```kotlin
        controller = InternalApiController(
            runStatusTracker = runStatusTracker,
            scheduler = scheduler,
            executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            phaseLoopController = phaseLoopController,
        )
```

(For tests that construct `InternalApiController` directly inline, they will need the extra arg too. Grep `grep -n "InternalApiController(" module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` to find each, then add `phaseLoopController = org.mockito.kotlin.mock<maple.externalapi.loop.PhaseLoopController>().apply { whenever(hasActiveLoop(any())).thenReturn(false); whenever(getLoopState(any())).thenReturn(null) }`.)

- [ ] **Step 2: Add failing tests for loop endpoints + 409 trigger pre-check**

Append to `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`:

```kotlin
    @Test
    fun `POST loop phase ITEM_EQUIPMENT returns 202 LOOP_STARTED with generated loopId`() {
        whenever(phaseLoopController.startLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(
            LoopState(
                loopId = "L-1",
                phase = PipelinePhase.ITEM_EQUIPMENT,
                startedAt = java.time.Instant.now(),
            ),
        )

        mockMvc.perform(post("/api/internal/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("LOOP_STARTED"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-1"))
            .andExpect(jsonPath("$.iterationCount").value(0))
    }

    @Test
    fun `POST loop phase RANKING_FETCH returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/loop/phase/RANKING_FETCH"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST loop phase BOGUS returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/loop/phase/BOGUS"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST stop loop phase ITEM_EQUIPMENT while loop active returns 202 STOP_REQUESTED`() {
        val state = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = java.time.Instant.now(),
            iterationCount = 3,
            lastRunId = "run-3",
        )
        whenever(phaseLoopController.stopLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(state)

        mockMvc.perform(post("/api/internal/stop/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("STOP_REQUESTED"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-1"))
            .andExpect(jsonPath("$.iterationCount").value(3))
    }

    @Test
    fun `POST stop loop phase ITEM_EQUIPMENT while no loop returns 200 NOT_LOOPING`() {
        whenever(phaseLoopController.stopLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(null)

        mockMvc.perform(post("/api/internal/stop/loop/phase/ITEM_EQUIPMENT"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("NOT_LOOPING"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
    }

    @Test
    fun `POST stop loop phase RANKING_FETCH returns 400 INVALID_PHASE`() {
        mockMvc.perform(post("/api/internal/stop/loop/phase/RANKING_FETCH"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("INVALID_PHASE"))
    }

    @Test
    fun `POST trigger phase ITEM_EQUIPMENT while loop active returns 409 LOOP_ACTIVE`() {
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(true)
        whenever(phaseLoopController.getLoopState(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(
            LoopState(loopId = "L-9", phase = PipelinePhase.ITEM_EQUIPMENT, startedAt = java.time.Instant.now()),
        )

        mockMvc.perform(
            post("/api/internal/trigger/phase/ITEM_EQUIPMENT").header("X-Upstream-Run-Id", "u-1"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("LOOP_ACTIVE"))
            .andExpect(jsonPath("$.phase").value("ITEM_EQUIPMENT"))
            .andExpect(jsonPath("$.loopId").value("L-9"))
    }

    @Test
    fun `POST trigger daily while any loop active returns 409 LOOP_ACTIVE for first looped phase`() {
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.RANKING_FETCH)).thenReturn(false)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.OCID_LOOKUP)).thenReturn(false)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.CHARACTER_BASIC)).thenReturn(true)
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(false)
        whenever(phaseLoopController.getLoopState(PipelinePhase.CHARACTER_BASIC)).thenReturn(
            LoopState(loopId = "L-CB", phase = PipelinePhase.CHARACTER_BASIC, startedAt = java.time.Instant.now()),
        )

        mockMvc.perform(post("/api/internal/trigger/daily"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.status").value("LOOP_ACTIVE"))
            .andExpect(jsonPath("$.loopId").value("L-CB"))
    }
```

Add imports to the test file:

```kotlin
import maple.externalapi.runstatus.LoopState
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`
Expected: Compile error — `phaseLoopController` arg unresolved, loop endpoints unresolved.

- [ ] **Step 4: Inject PhaseLoopController into InternalApiController**

In `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`, add the import and constructor arg:

```kotlin
import maple.externalapi.loop.PhaseLoopController
```

Replace the class signature:

```kotlin
@RestController
@RequestMapping("/api/internal")
class InternalApiController(
    private val runStatusTracker: RunStatusTracker,
    private val scheduler: ExternalApiScheduler,
    @Qualifier("internalApiExecutor") private val executor: ExecutorService,
    private val phaseLoopController: PhaseLoopController,
) {
```

Add a `loopablePhases` set near `triggerablePhases`:

```kotlin
    private val loopablePhases = setOf(
        PipelinePhase.ITEM_EQUIPMENT,
        PipelinePhase.CHARACTER_BASIC,
    )
```

- [ ] **Step 5: Add 409 pre-check to `/trigger/daily` and `/trigger/phase/{name}`**

In `/trigger/daily`, insert the pre-check before the existing RANKING_FETCH slot check:

```kotlin
        // Block daily trigger if ANY loop is active. Daily covers all 4 phases;
        // if any of them is being driven by a loop, the loop would be raced.
        for (phase in triggerablePhases) {
            if (phaseLoopController.hasActiveLoop(phase)) {
                val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
                return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                    "status" to "LOOP_ACTIVE",
                    "phase" to phase.name,
                    "loopId" to loopId,
                ))
            }
        }
```

In `/trigger/phase/{phaseName}`, insert the pre-check after phase validation and before the existing slot check:

```kotlin
        if (phaseLoopController.hasActiveLoop(phase)) {
            val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf(
                "status" to "LOOP_ACTIVE",
                "phase" to phase.name,
                "loopId" to loopId,
            ))
        }
```

- [ ] **Step 6: Add `startLoop` and `stopLoop` endpoints**

Add to `InternalApiController` after `stopPhase`:

```kotlin
    @PostMapping("/loop/phase/{phaseName}")
    fun startLoop(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in loopablePhases) {
            return badRequestInvalidLoopablePhase()
        }
        val state = phaseLoopController.startLoop(phase)
        log.info(
            "[InternalApi] loop start phase={} loopId={} airflowRunId={}",
            phase, state.loopId, airflowRunId,
        )
        return ResponseEntity.accepted().body(mapOf(
            "status" to "LOOP_STARTED",
            "phase" to phase.name,
            "loopId" to state.loopId,
            "iterationCount" to state.iterationCount.toString(),
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }

    @PostMapping("/stop/loop/phase/{phaseName}")
    fun stopLoop(
        @PathVariable phaseName: String,
        @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    ): ResponseEntity<Map<String, String>> {
        val phase = runCatching { PipelinePhase.valueOf(phaseName) }.getOrNull()
        if (phase == null || phase !in loopablePhases) {
            return badRequestInvalidLoopablePhase()
        }
        val state = phaseLoopController.stopLoop(phase)
        if (state != null) {
            log.info(
                "[InternalApi] loop stop requested phase={} loopId={} iterations={} airflowRunId={}",
                phase, state.loopId, state.iterationCount, airflowRunId,
            )
            return ResponseEntity.accepted().body(mapOf(
                "status" to "STOP_REQUESTED",
                "phase" to phase.name,
                "loopId" to state.loopId,
                "iterationCount" to state.iterationCount.toString(),
                "airflowRunId" to (airflowRunId ?: ""),
            ))
        }
        return ResponseEntity.ok().body(mapOf(
            "status" to "NOT_LOOPING",
            "phase" to phase.name,
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }

    private fun badRequestInvalidLoopablePhase(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf(
                "error" to "INVALID_PHASE",
                "allowed" to loopablePhases.joinToString(",") { it.name },
            ))
```

- [ ] **Step 6.5: Add `/stop/phase/{name}` warning when loop active for same phase (Q3 follow-up)**

The existing `/stop/phase/{name}` method (added in #1290) trips the same `PhaseStopSignal` as `/stop/loop/phase/{name}`. If a loop is active for the same phase, a single-shot stop silently halts the loop's current iteration (per spec §5.3). Add a `log.warn` at the top of `stopPhase` to surface this:

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
        if (phaseLoopController.hasActiveLoop(phase)) {
            val loopId = phaseLoopController.getLoopState(phase)?.loopId ?: ""
            log.warn(
                "[InternalApi] /stop/phase tripped loop for active loop — phase={} loopId={} airflowRunId={}; " +
                    "use /stop/loop/phase/{} to target the loop explicitly",
                phase, loopId, airflowRunId, phase,
            )
        }
        val wasRunning = scheduler.requestPhaseStop(phase)
        // ... rest unchanged
```

No new test required — the existing 409 LOOP_ACTIVE tests already cover the controller's loop awareness. The warning is observational only.

- [ ] **Step 7: Run all controller tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`
Expected: PASS (existing + 8 new).

- [ ] **Step 8: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(ext-api): loop + stop-loop endpoints + 409 trigger pre-check

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 11: /run-status decoration with loopSummaries + per-RunStatus loopId

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Add failing test for /run-status decoration**

Append to `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`:

```kotlin
    @Test
    fun `GET run-status with active loop decorates response with loopSummaries`() {
        val itemLoop = LoopState(
            loopId = "L-1",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = java.time.Instant.parse("2026-06-19T00:00:00Z"),
            iterationCount = 5,
            lastRunId = "run-5",
        )
        val itemSlot = RunStatus(
            runId = "run-5",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
            startedAt = java.time.Instant.parse("2026-06-19T00:01:00Z"),
            loopId = "L-1",
        )

        stubEmptyPerPhaseLookups()
        whenever(phaseLoopController.activeLoops()).thenReturn(listOf(itemLoop))
        whenever(phaseLoopController.hasActiveLoop(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(true)
        whenever(phaseLoopController.getLoopState(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(itemLoop)
        whenever(runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)).thenReturn(itemSlot)

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.loopId").value("L-1"))
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.iterationCount").value(5))
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT.status").value("RUNNING"))
            .andExpect(jsonPath("$.slots.ITEM_EQUIPMENT.loopId").value("L-1"))
    }

    @Test
    fun `GET run-status with no active loops returns empty loopSummaries`() {
        stubEmptyPerPhaseLookups()
        whenever(phaseLoopController.activeLoops()).thenReturn(emptyList())

        mockMvc.perform(get("/api/internal/run-status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.loopSummaries").isMap)
            .andExpect(jsonPath("$.loopSummaries.ITEM_EQUIPMENT").doesNotExist())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`
Expected: JSON path `$.loopSummaries.ITEM_EQUIPMENT` not found.

- [ ] **Step 3: Decorate `getRunStatus` response**

In `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`, replace `getRunStatus`:

```kotlin
    @GetMapping("/run-status")
    fun getRunStatus(): ResponseEntity<RunStatusResponse> {
        val phases = listOf(
            PipelinePhase.RANKING_FETCH,
            PipelinePhase.OCID_LOOKUP,
            PipelinePhase.CHARACTER_BASIC,
            PipelinePhase.ITEM_EQUIPMENT,
        )
        val slots = phases.associateWith { runStatusTracker.getPhaseStatus(it) }
        val lastCompletedByPhase = phases.associateWith {
            runStatusTracker.getLastCompletedForPhase(it)
        }
        val loopSummaries = phaseLoopController.activeLoops().associate { state ->
            state.phase.name to LoopSummaryView(
                loopId = state.loopId,
                phase = state.phase.name,
                startedAt = state.startedAt,
                iterationCount = state.iterationCount,
                lastRunId = state.lastRunId,
                status = state.status.name,
                lastError = state.lastError,
            )
        }
        val response = RunStatusResponse(
            slots = slots,
            lastCompletedByPhase = lastCompletedByPhase,
            current = runStatusTracker.getCurrentStatus(),
            lastCompleted = runStatusTracker.getLastCompletedRun(),
            loopSummaries = loopSummaries,
        )
        return ResponseEntity.ok(response)
    }
```

- [ ] **Step 4: Run all controller tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`
Expected: PASS (existing + 8 loop endpoint tests + 2 decoration tests).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(ext-api): /run-status decorates with loopSummaries + slot loopId

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 12: Final compile + full module test pass

**Files:** none (verification only).

- [ ] **Step 1: Compile everything**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full module test suite**

Run: `./gradlew :module-external-api:test`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 3: Verify no leftover placeholders in production code**

Run: `grep -rn "TODO\|FIXME\|XXX" module-external-api/src/main/kotlin/maple/externalapi/loop module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopStatus.kt module-external-api/src/main/kotlin/maple/externalapi/runstatus/LoopState.kt`
Expected: No matches.

- [ ] **Step 4: Verify `loopExecutor` is the only `AsyncTaskExecutor` bean**

Run: `grep -rn "AsyncTaskExecutor" module-external-api/src/main --include="*.kt"`
Expected: Only `PhaseLoopController.loopExecutor` reference. Bean definition in `LoopExecutorConfig`.

- [ ] **Step 5: Push branch and prepare for review**

```bash
git push -u origin feature/issue-1291-loop-spec
```

Final report to user:
- Branch: `feature/issue-1291-loop-spec`
- Commit count
- File diff summary
- Test counts (existing + new)
- Manual smoke: bootRun + `curl -X POST /api/internal/loop/phase/ITEM_EQUIPMENT` + observe `/run-status` for ≥10 min (deferred to reviewer; not in this task scope)

---

## Self-Review Notes

- **Type consistency:** `LoopState.loopId`, `RunStatus.loopId`, `RunStatusTracker.acquirePhaseSlot(... loopId)`, `ExternalApiScheduler.triggerPhase(... loopId)`, `PhaseLoopController.startLoop(phase) -> LoopState` — all use `String?` and `loopId: String`. Verified across tasks 1, 2, 3, 5, 6, 7, 9, 10, 11.
- **Spec coverage:** Every section in spec §1–§13 maps to at least one task (see table in File Structure). §11 out-of-scope items explicitly excluded.
- **Placeholders:** No "TBD"/"implement later" patterns. Every code block is complete.
- **Self-loop risk in tests:** `RecordingAsyncExecutor` (running submitted Runnables inline) would cause infinite recursion when `handleIterationEnd` calls `loopExecutor.execute` for the next iteration. Mitigation applied in Task 5: use a `noopExecutor` (drops submitted Runnables) for `startLoop` count tests, verifying via `verify(scheduler).triggerPhase(...)` instead. Iteration-chain tests use `OneShotInlineExecutor` (runs the first submit inline, drops the rest) to verify chain submission without infinite recursion. `startLoop` count tests stub `scheduler.triggerPhase` with a pending `CompletableFuture<Unit>()` so `whenComplete` does NOT fire inline. Concurrent-startLoop test uses `noopExecutor`.
- **Concurrency in tests:** Spec §9.2 requires a "two threads call startLoop concurrently → exactly one wins" test. Added in the Task 7 supplement at the end of the test file; uses `noopExecutor` so the iteration chain does not run.
- **OCID_LOOKUP removed from loopablePhases:** First-grill question resolved — `runOcidPhase` requires `upstreamRunId != null`, and `latestUpstreamRunId(OCID_LOOKUP) = null` would throw `IllegalArgumentException` on iteration 1. OCID_LOOKUP is removed from the set; only `ITEM_EQUIPMENT` and `CHARACTER_BASIC` are loopable. RANKING_FETCH was already excluded.
- **/stop/phase/{name} logs warning when loop active for same phase:** Q3 follow-up — both endpoints trip the same `PhaseStopSignal`, so a single-shot stop silently kills the loop's current iteration. Added a `log.warn` in `stopPhase` when `phaseLoopController.hasActiveLoop(phase)` is true, naming the loopId and pointing operators at `/stop/loop/phase/{name}` for explicit intent.
- **iterationCount semantics = "iterations that reached terminal state":** Second-grill question resolved. `startLoop` returns 0 (matches spec §5.1 202 LOOP_STARTED). `handleIterationEnd` increments after the iteration's `whenComplete` fires (success, stop, or fail). Submitted-but-in-flight iterations do not count. Plan Task 5 controller code + tests updated accordingly.
- **Shutdown calls finalize() directly:** Third-grill question resolved. Pre-fix: shutdown set STOPPING + tripped signal, but if the loop was between iterations or the executor's 30s drain dropped the pending submit task, state stayed at STOPPING forever, causing next `startLoop` to return a stale state. Fix: `@PreDestroy` calls `finalize(phase, loopId)` for each active loop, transitioning state directly to STOPPED. In-flight iteration's `whenComplete` then sees `state.loopId` mismatch and bails (idempotent — see `finalize` loopId check). Task 8 test asserts a fresh `startLoop` after shutdown allocates a new `loopId`.
- **lastRunId = "most recent completed iteration's runId":** Fourth-grill question resolved. Pre-fix: `startLoop` set `lastRunId` to the first runId and `handleIterationEnd` updated it to nextRunId on each chain — value flickered between completed and to-be-submitted. Fix: `startLoop` does NOT set `lastRunId` (stays null until first iteration completes). `handleIterationEnd` sets `lastRunId` only on the just-completed runId. Next-iter branch does NOT update `lastRunId` to nextRunId. Stable per-iteration view. Plan Task 5 controller code + tests updated; `OneShotInlineExecutor` helper added for chain tests to avoid infinite recursion.
- **Backwards compatibility:** All existing 1-arg `acquirePhaseSlot` callers continue to work (default `loopId = null`). All existing `runXxxPhase` callers continue to work (default `loopId = null`). All existing `triggerPhase` callers continue to work (default `loopId = null`). Verified in tasks 3, 6, 9.
