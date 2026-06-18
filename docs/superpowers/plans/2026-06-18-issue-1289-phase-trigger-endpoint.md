# Phase Trigger Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/internal/trigger/phase/{phaseName}` for standalone per-phase runs, refactor `ExternalApiScheduler` to extract per-phase methods, retire `ItemEquipmentContinuousLoop`, and convert `RunStatusTracker` to per-phase slots.

**Architecture:** Per-phase slot map in `RunStatusTracker` (4 slots, one per `PipelinePhase`). `ExternalApiScheduler.triggerPhase(phase, runId, upstreamRunId)` is the new public entry point; `triggerDailyRefresh` chains 4 `triggerPhase` calls. `ItemEquipmentContinuousLoop` body folds into `ExternalApiScheduler.runItemEquipmentPhase`. Item-equipment auto-resume on startup removed.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.x, JUnit 5, AssertJ, mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-06-18-issue-1289-phase-trigger-endpoint-design.md`

---

## File Structure

| File | Action |
| --- | --- |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt` | Modify — add `triggeredPhase` field |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` | Modify — slot map, `acquirePhaseSlot`, `releasePhaseSlot`, `hasNonTerminalRun`, `getPhaseStatus` |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt` | Create — response shape with `slots`, `lastCompletedByPhase`, deprecated `current`/`lastCompleted` aliases |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | Modify — extract `runRankingPhase`, `runOcidPhase`, `runCharBasicPhase`, `runItemEquipmentPhase`; add public `triggerPhase(phase, runId, upstreamRunId)`; refactor `triggerDailyRefresh` to chain; remove `ItemEquipmentContinuousLoop` field + onStartup call; fold loop body into `runItemEquipmentPhase` |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt` | **Delete** |
| `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` | Modify — add `POST /trigger/phase/{phaseName}`; update `GET /run-status` response |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` | Modify — add slot map tests |
| `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` | Modify — add `triggerPhase` tests |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` | Modify — replace existing tests with per-phase tests |

---

### Task 1: Add `triggeredPhase` field to `RunStatus`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`

- [ ] **Step 1: Add failing test for `triggeredPhase`**

Append to `RunStatusTrackerTest.kt`:

```kotlin
@Test
fun `RunStatus carries triggeredPhase field set on startRun`() {
    tracker.startRun("run-1")
    val status = tracker.getCurrentStatus()!!
    assertThat(status.triggeredPhase).isEqualTo(PipelinePhase.RANKING_FETCH)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest.runStatus carries triggeredPhase field set on startRun"`
Expected: FAIL with `unresolved reference: triggeredPhase` (field does not exist).

- [ ] **Step 3: Add `triggeredPhase` field to `RunStatus`**

Edit `RunStatus.kt` — replace data class body:

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
) {
    @get:JsonProperty("terminal")
    val isTerminal: Boolean get() = phase == PipelinePhase.COMPLETED || phase == PipelinePhase.FAILED
}
```

- [ ] **Step 4: Update `RunStatusTracker.startRun`, `startItemEquipmentCycle`, `transitionPhase` to populate `triggeredPhase`**

Edit `RunStatusTracker.kt` — change `startRun`:

```kotlin
fun startRun(runId: String) {
    val status = RunStatus(
        runId = runId,
        phase = PipelinePhase.RANKING_FETCH,
        triggeredPhase = PipelinePhase.RANKING_FETCH,
        startedAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
    )
    currentRun.set(status)
    log.info("[RunStatus] started run={}", runId)
}
```

Change `startItemEquipmentCycle`:

```kotlin
fun startItemEquipmentCycle(runId: String) {
    val status = RunStatus(
        runId = runId,
        phase = PipelinePhase.ITEM_EQUIPMENT,
        triggeredPhase = PipelinePhase.ITEM_EQUIPMENT,
        startedAt = Instant.now(clock),
        updatedAt = Instant.now(clock),
    )
    currentRun.set(status)
    log.info("[RunStatus] item-equipment cycle started run={}", runId)
}
```

Change `transitionPhase` to preserve `triggeredPhase` on copy:

```kotlin
fun transitionPhase(phase: PipelinePhase) {
    currentRun.updateAndGet { current ->
        current?.copy(phase = phase, updatedAt = Instant.now(clock))
    }
    log.info("[RunStatus] phase={}", phase)
}
```

The `copy()` preserves `triggeredPhase` because data-class `copy()` retains unspecified fields.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt \
        module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "feat(ext-api): add triggeredPhase field to RunStatus"
```

---

### Task 2: Replace single-slot tracker with per-phase slot map

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`

- [ ] **Step 1: Add failing test for per-phase slot acquire**

Append to `RunStatusTrackerTest.kt`:

```kotlin
@Test
fun `acquirePhaseSlot succeeds when slot empty`() {
    val acquired = tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-ocid-1")
    assertThat(acquired.runId).isEqualTo("run-ocid-1")
    assertThat(acquired.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    assertThat(acquired.triggeredPhase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-ocid-1")
}

@Test
fun `acquirePhaseSlot returns null when slot has non-terminal run`() {
    tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-1")
    val second = tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-2")
    assertThat(second).isNull()
}

@Test
fun `releasePhaseSlot sets phase to terminal and clears slot`() {
    tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
    tracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1", PipelinePhase.COMPLETED, 50, 100_000L)
    val status = tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)
    assertThat(status?.phase).isEqualTo(PipelinePhase.COMPLETED)
    assertThat(status?.isTerminal).isTrue()
    assertThat(status?.chunksProcessed).isEqualTo(50)
}

@Test
fun `hasNonTerminalRun returns true for non-terminal slot`() {
    tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1")
    assertThat(tracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).isNotNull()
}

@Test
fun `per-phase slots are independent`() {
    tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
    tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
    tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb")
    tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie")
    assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)?.runId).isEqualTo("run-r")
    assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-o")
    assertThat(tracker.getPhaseStatus(PipelinePhase.CHARACTER_BASIC)?.runId).isEqualTo("run-cb")
    assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)?.runId).isEqualTo("run-ie")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`
Expected: FAIL with `unresolved reference: acquirePhaseSlot` and similar for `releasePhaseSlot`, `hasNonTerminalRun`, `getPhaseStatus`.

- [ ] **Step 3: Replace tracker with slot-map implementation**

Replace `RunStatusTracker.kt` entirely:

```kotlin
package maple.externalapi.runstatus

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class RunStatusTracker(
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val slots = ConcurrentHashMap<PipelinePhase, AtomicReference<RunStatus>>()

    /** Legacy single-slot compatibility. Returns the most recent slot's current value, or null. */
    fun getCurrentStatus(): RunStatus? {
        return slots.values
            .mapNotNull { it.get() }
            .filterNot { it.isTerminal }
            .maxByOrNull { it.startedAt }
    }

    fun getLastCompletedRun(): RunStatus? {
        return slots.values
            .mapNotNull { it.get() }
            .filter { it.isTerminal }
            .maxByOrNull { it.completedAt ?: it.startedAt }
    }

    fun getPhaseStatus(phase: PipelinePhase): RunStatus? = slots[phase]?.get()

    fun hasNonTerminalRun(phase: PipelinePhase): RunStatus? {
        val slot = slots[phase]?.get() ?: return null
        return if (slot.isTerminal) null else slot
    }

    /**
     * Atomic CAS acquire. Returns the new RunStatus if acquired; null if slot occupied
     * by a non-terminal run. Used by ExternalApiScheduler.triggerPhase and the
     * /api/internal/trigger/phase controller.
     */
    fun acquirePhaseSlot(phase: PipelinePhase, runId: String): RunStatus? {
        val slot = slots.computeIfAbsent(phase) { AtomicReference(null) }
        val now = Instant.now(clock)
        val candidate = RunStatus(
            runId = runId,
            phase = phase,
            triggeredPhase = phase,
            startedAt = now,
            updatedAt = now,
        )
        return if (slot.compareAndSet(null, candidate)) {
            log.info("[RunStatus] phase-slot acquired phase={} runId={}", phase, runId)
            candidate
        } else {
            log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, slot.get()?.runId)
            null
        }
    }

    /**
     * Transition the run in [phase] slot. No-op if slot empty or runId mismatch.
     */
    fun transitionPhase(phase: PipelinePhase, runId: String? = null) {
        slots[phase]?.updateAndGet { current ->
            if (current == null) return@updateAndGet null
            if (runId != null && current.runId != runId) return@updateAndGet current
            current.copy(phase = phase, updatedAt = Instant.now(clock))
        }
        log.info("[RunStatus] phase-slot transition phase={} runId={}", phase, runId)
    }

    /**
     * Mark phase slot's run as COMPLETED and stamp chunks/records. No-op if runId
     * mismatch or slot empty.
     */
    fun completeRun(phase: PipelinePhase, runId: String, chunksProcessed: Int, recordsProcessed: Long) {
        val now = Instant.now(clock)
        slots[phase]?.updateAndGet { current ->
            if (current == null || current.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.COMPLETED,
                updatedAt = now,
                completedAt = now,
                chunksProcessed = chunksProcessed,
                recordsProcessed = recordsProcessed,
            )
        }
        log.info("[RunStatus] phase-slot completed phase={} runId={} chunks={} records={}",
            phase, runId, chunksProcessed, recordsProcessed)
    }

    /**
     * Mark phase slot's run as FAILED with errorMessage. No-op on runId mismatch.
     */
    fun failRun(phase: PipelinePhase, runId: String, errorMessage: String) {
        val now = Instant.now(clock)
        slots[phase]?.updateAndGet { current ->
            if (current == null || current.runId != runId) return@updateAndGet current
            current.copy(
                phase = PipelinePhase.FAILED,
                updatedAt = now,
                completedAt = now,
                errorMessage = errorMessage,
            )
        }
        log.error("[RunStatus] phase-slot failed phase={} runId={} error={}", phase, runId, errorMessage)
    }

    /**
     * Clear slot if the runId matches. Idempotent. Safe to call after terminal
     * state already recorded.
     */
    fun releasePhaseSlot(phase: PipelinePhase, runId: String) {
        slots[phase]?.updateAndGet { current ->
            if (current?.runId == runId) null else current
        }
        log.info("[RunStatus] phase-slot released phase={} runId={}", phase, runId)
    }

    // Legacy methods retained for backward compatibility with code paths not yet migrated.
    @Deprecated("Use acquirePhaseSlot(phase, runId) instead")
    fun startRun(runId: String) {
        acquirePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
    }

    @Deprecated("Use acquirePhaseSlot(ITEM_EQUIPMENT, runId) instead")
    fun startItemEquipmentCycle(runId: String) {
        acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
    }
}
```

- [ ] **Step 4: Run tracker tests; fix existing failures from signature change**

The existing tests in `RunStatusTrackerTest.kt` reference the old `startRun`/`completeRun(runId, chunks, records)` signatures. Update them to use the new slot-based API.

Edit the existing test file — replace the entire body:

```kotlin
package maple.externalapi.runstatus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RunStatusTrackerTest {

    private val tracker = RunStatusTracker()

    @Test
    fun `all slots empty initially`() {
        assertThat(tracker.getCurrentStatus()).isNull()
        assertThat(tracker.getLastCompletedRun()).isNull()
        for (phase in listOf(PipelinePhase.RANKING_FETCH, PipelinePhase.OCID_LOOKUP, PipelinePhase.CHARACTER_BASIC, PipelinePhase.ITEM_EQUIPMENT)) {
            assertThat(tracker.getPhaseStatus(phase)).isNull()
        }
    }

    @Test
    fun `acquirePhaseSlot succeeds when slot empty`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-ocid-1")
        assertThat(acquired).isNotNull
        assertThat(acquired!!.runId).isEqualTo("run-ocid-1")
        assertThat(acquired.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(acquired.triggeredPhase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-ocid-1")
    }

    @Test
    fun `acquirePhaseSlot returns null when slot has non-terminal run`() {
        tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-1")
        val second = tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-2")
        assertThat(second).isNull()
    }

    @Test
    fun `completeRun sets phase to COMPLETED with chunks and records`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.completeRun(PipelinePhase.RANKING_FETCH, "run-r-1", 50, 100_000L)
        val status = tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.COMPLETED)
        assertThat(status.isTerminal).isTrue()
        assertThat(status.chunksProcessed).isEqualTo(50)
        assertThat(status.recordsProcessed).isEqualTo(100_000L)
        assertThat(status.completedAt).isNotNull
    }

    @Test
    fun `failRun sets phase to FAILED with errorMessage`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        tracker.failRun(PipelinePhase.OCID_LOOKUP, "run-o-1", "Nexon API timeout")
        val status = tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.FAILED)
        assertThat(status.errorMessage).isEqualTo("Nexon API timeout")
    }

    @Test
    fun `transitionPhase preserves triggeredPhase and updates phase`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o-1")
        tracker.transitionPhase(PipelinePhase.OCID_LOOKUP, "run-o-1")
        val status = tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)!!
        assertThat(status.phase).isEqualTo(PipelinePhase.OCID_LOOKUP)
        assertThat(status.triggeredPhase).isEqualTo(PipelinePhase.OCID_LOOKUP)
    }

    @Test
    fun `releasePhaseSlot clears slot when runId matches`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)).isNull()
    }

    @Test
    fun `releasePhaseSlot is no-op when runId mismatch`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
        tracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-2")
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)?.runId).isEqualTo("run-r-1")
    }

    @Test
    fun `hasNonTerminalRun returns the run when slot non-terminal`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1")
        assertThat(tracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)?.runId).isEqualTo("run-ie-1")
    }

    @Test
    fun `hasNonTerminalRun returns null after completeRun`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1")
        tracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-ie-1", 10, 1_000L)
        assertThat(tracker.hasNonTerminalRun(PipelinePhase.ITEM_EQUIPMENT)).isNull()
    }

    @Test
    fun `per-phase slots are independent`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        tracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb")
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-ie")
        assertThat(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH)?.runId).isEqualTo("run-r")
        assertThat(tracker.getPhaseStatus(PipelinePhase.OCID_LOOKUP)?.runId).isEqualTo("run-o")
        assertThat(tracker.getPhaseStatus(PipelinePhase.CHARACTER_BASIC)?.runId).isEqualTo("run-cb")
        assertThat(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)?.runId).isEqualTo("run-ie")
    }

    @Test
    fun `getCurrentStatus returns the most recently started non-terminal run across phases`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        Thread.sleep(5)
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        val current = tracker.getCurrentStatus()
        assertThat(current?.runId).isEqualTo("run-o")
    }

    @Test
    fun `getLastCompletedRun returns the most recent terminal run across phases`() {
        tracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r")
        tracker.completeRun(PipelinePhase.RANKING_FETCH, "run-r", 10, 1_000L)
        Thread.sleep(5)
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-o")
        tracker.completeRun(PipelinePhase.OCID_LOOKUP, "run-o", 20, 2_000L)
        val last = tracker.getLastCompletedRun()
        assertThat(last?.runId).isEqualTo("run-o")
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "feat(ext-api): refactor RunStatusTracker to per-phase slot map"
```

---

### Task 3: Update `RunStatusResponse` and create new response shape

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`

- [ ] **Step 1: Create `RunStatusResponse.kt`**

```kotlin
package maple.externalapi.runstatus

/**
 * Per-phase run-status payload. Active slots and last-completed run per phase.
 * The `current` and `lastCompleted` fields are legacy aliases for
 * the single-slot API consumers; deprecated.
 */
data class RunStatusResponse(
    val slots: Map<PipelinePhase, RunStatus?>,
    val lastCompletedByPhase: Map<PipelinePhase, RunStatus?>,
    @Deprecated("Use slots map instead") val current: RunStatus?,
    @Deprecated("Use lastCompletedByPhase map instead") val lastCompleted: RunStatus?,
)
```

- [ ] **Step 2: Update `InternalApiController` `getRunStatus` to use new shape**

Replace the existing `getRunStatus`:

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
    val response = RunStatusResponse(
        slots = slots,
        lastCompletedByPhase = lastCompletedByPhase,
        current = runStatusTracker.getCurrentStatus(),
        lastCompleted = runStatusTracker.getLastCompletedRun(),
    )
    return ResponseEntity.ok(response)
}
```

- [ ] **Step 3: Add `getLastCompletedForPhase` method to tracker**

Append to `RunStatusTracker.kt`:

```kotlin
/**
 * Return the most recent terminal RunStatus for [phase] (across cycles/runs).
 * Looks at the slot's current value if terminal; null if slot is empty or non-terminal.
 */
fun getLastCompletedForPhase(phase: PipelinePhase): RunStatus? {
    val slot = slots[phase]?.get() ?: return null
    return if (slot.isTerminal) slot else null
}
```

- [ ] **Step 4: Run compile to verify no errors**

Run: `./gradlew :module-external-api:compileKotlin`
Expected: BUILD SUCCESSFUL. (InternalApiController may fail until step 5 is done — only run after step 5.)

- [ ] **Step 5: Update `InternalApiController.triggerDailyRefresh` to use new tracker API**

The existing controller code calls `runStatusTracker.getCurrentStatus()` for the 409 check. Replace with per-phase check on RANKING_FETCH:

```kotlin
@PostMapping("/trigger/daily")
fun triggerDailyRefresh(
    @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
): ResponseEntity<Map<String, String>> {
    // 409 if RANKING_FETCH slot occupied (daily always starts at ranking).
    val existing = runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)
    if (existing != null) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
    }

    val runId = airflowRunId ?: UUID.randomUUID().toString()
    executor.submit { scheduler.triggerDailyRefresh(runId) }
    return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
}
```

- [ ] **Step 6: Run compile**

Run: `./gradlew :module-external-api:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusResponse.kt \
        module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
        module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt
git commit -m "feat(ext-api): per-phase run-status response with slot map"
```

---

### Task 4: Extract `runRankingPhase` and `runOcidPhase` methods on `ExternalApiScheduler`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

- [ ] **Step 1: Add failing test for `runRankingPhase` extracted method**

Append to `ExternalApiSchedulerTest.kt`:

```kotlin
@Test
fun `runRankingPhase calls rankingFetchPhaseProvider execute with runId`() {
    val rankingPhase = mock<RankingFetchPhase>()
    whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
        .thenReturn(CompletableFuture.completedFuture("runs/run-r-1"))

    val ocidLookupPhase = mock<OcidLookupPhase>()
    val ocidCache = mock<OcidCacheProvider>()
    val runStatusTracker = mock<RunStatusTracker>()
    val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
    whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
    val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
    whenever(charBasicProvider.ifAvailable).thenReturn(null)
    val itemEquipmentLoop = mock<ItemEquipmentContinuousLoop>()

    val scheduler = ExternalApiScheduler(
        ocidLookupPhase = ocidLookupPhase,
        ocidCacheProvider = ocidCache,
        rankingFetchPhaseProvider = rankingProvider,
        characterBasicPhaseProvider = charBasicProvider,
        itemEquipmentContinuousLoop = itemEquipmentLoop,
        runStatusTracker = runStatusTracker,
        runIdGenerator = RunIdGenerator(Clock.systemUTC()),
        runOnStartup = false,
        skipCharacterBasic = false,
    )

    scheduler.runRankingPhase("run-r-1", null).get()

    verify(rankingPhase).execute(any<ExecutorService>(), eq("run-r-1"))
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "run-r-1")
    verify(runStatusTracker).completeRun(PipelinePhase.RANKING_FETCH, "run-r-1", any(), any())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest.runRankingPhase calls rankingFetchPhaseProvider execute with runId"`
Expected: FAIL with `unresolved reference: runRankingPhase`.

- [ ] **Step 3: Add `runRankingPhase` and `runOcidPhase` methods to `ExternalApiScheduler`**

In `ExternalApiScheduler.kt`, after `triggerDailyRefresh` (currently the only public method), add:

```kotlin
/**
 * Run RANKING_FETCH phase standalone. Acquires RANKING_FETCH slot in tracker,
 * calls ranking phase bean, completes slot on success or fails slot on exception.
 * [upstreamRunId] is unused for ranking (no upstream).
 */
fun runRankingPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
    val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
        ?: return CompletableFuture.failedFuture(
            IllegalStateException("RANKING_FETCH slot occupied")
        )

    val rankingPhase = rankingFetchPhaseProvider.ifAvailable
    if (rankingPhase == null) {
        runStatusTracker.failRun(PipelinePhase.RANKING_FETCH, runId, "ranking fetch phase not enabled")
        return CompletableFuture.failedFuture(
            IllegalStateException("ranking fetch phase not enabled")
        )
    }

    return rankingPhase.execute(executor, runId)
        .whenComplete { _, ex ->
            if (ex != null) {
                log.error("[Scheduler] runRankingPhase failed runId={}", runId, ex)
                runStatusTracker.failRun(PipelinePhase.RANKING_FETCH, runId, ex.message ?: "unknown")
            } else {
                runStatusTracker.completeRun(PipelinePhase.RANKING_FETCH, runId, 0, 0)
            }
            runStatusTracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
        }
        .thenApply { }
}

/**
 * Run OCID_LOOKUP phase standalone. Reads character names from [upstreamRunId]'s
 * ranking chunks, fetches OCIDs, writes ocid-mapping file.
 */
fun runOcidPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
    require(upstreamRunId != null) { "OCID_LOOKUP requires upstreamRunId" }
    val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, runId)
        ?: return CompletableFuture.failedFuture(
            IllegalStateException("OCID_LOOKUP slot occupied")
        )

    val runKey = "runs/$upstreamRunId"
    return runBlocking { ocidLookupPhase.execute(executor, runKey) }
        .let { CompletableFuture.completedFuture(it) }
        .whenComplete { _, ex ->
            if (ex != null) {
                log.error("[Scheduler] runOcidPhase failed runId={} upstreamRunId={}", runId, upstreamRunId, ex)
                runStatusTracker.failRun(PipelinePhase.OCID_LOOKUP, runId, ex.message ?: "unknown")
            } else {
                runStatusTracker.completeRun(PipelinePhase.OCID_LOOKUP, runId, 0, 0)
            }
            runStatusTracker.releasePhaseSlot(PipelinePhase.OCID_LOOKUP, runId)
        }
        .thenApply { }
}
```

- [ ] **Step 4: Run tests; adjust mockito matchers if needed**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest.runRankingPhase calls rankingFetchPhaseProvider execute with runId"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "feat(ext-api): extract runRankingPhase and runOcidPhase methods"
```

---

### Task 5: Extract `runCharBasicPhase` and `runItemEquipmentPhase`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

- [ ] **Step 1: Add `ItemEquipmentFetchPhase` dependency to scheduler**

Update the constructor of `ExternalApiScheduler` — replace the parameter list:

```kotlin
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    private val characterBasicPhaseProvider: ObjectProvider<CharacterBasicFetchPhase>,
    private val itemEquipmentFetchPhaseProvider: ObjectProvider<ItemEquipmentFetchPhase>,
    private val schedulerMetrics: SchedulerMetrics,
    private val runStatusTracker: RunStatusTracker,
    private val runIdGenerator: RunIdGenerator,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
) : ManagedLifecycle {
```

Add imports:

```kotlin
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
```

- [ ] **Step 2: Add failing test for `runCharBasicPhase`**

Append to `ExternalApiSchedulerTest.kt`:

```kotlin
@Test
fun `runCharBasicPhase invokes characterBasicFetchPhase execute with ocidCache from upstreamRunId`() {
    val ocidCache = mock<OcidCacheProvider>()
    val ocidMap = mapOf("ign1" to "ocid1", "ign2" to "ocid2")
    whenever(ocidCache.current()).thenReturn(ocidMap)

    val charBasicPhase = mock<CharacterBasicFetchPhase>()
    whenever(charBasicPhase.execute(any<ExecutorService>(), any<Map<String, String>>()))
        .thenReturn(CompletableFuture.completedFuture(Unit))

    val rankingPhase = mock<RankingFetchPhase>()
    val ocidLookupPhase = mock<OcidLookupPhase>()
    val runStatusTracker = mock<RunStatusTracker>()
    val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
    whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
    val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
    whenever(charBasicProvider.ifAvailable).thenReturn(charBasicPhase)
    val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
    whenever(itemEquipmentProvider.ifAvailable).thenReturn(null)
    val schedulerMetrics = mock<SchedulerMetrics>()
    val itemEquipmentLoop = mock<ItemEquipmentContinuousLoop>()

    val scheduler = ExternalApiScheduler(
        ocidLookupPhase = ocidLookupPhase,
        ocidCacheProvider = ocidCache,
        rankingFetchPhaseProvider = rankingProvider,
        characterBasicPhaseProvider = charBasicProvider,
        itemEquipmentFetchPhaseProvider = itemEquipmentProvider,
        schedulerMetrics = schedulerMetrics,
        runStatusTracker = runStatusTracker,
        runIdGenerator = RunIdGenerator(Clock.systemUTC()),
        runOnStartup = false,
        skipCharacterBasic = false,
    )

    scheduler.runCharBasicPhase("run-cb-1", "run-ocid-1").get()

    verify(charBasicPhase).execute(any<ExecutorService>(), eq(ocidMap))
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "run-cb-1")
}
```

- [ ] **Step 3: Run test to verify it fails**

Expected: FAIL with constructor mismatch (test still passes the old `itemEquipmentContinuousLoop` parameter).

- [ ] **Step 4: Update existing tests in `ExternalApiSchedulerTest.kt` to pass new constructor args**

Replace all `ExternalApiScheduler(...)` constructions in the test file to include `itemEquipmentFetchPhaseProvider` and `schedulerMetrics` instead of `itemEquipmentContinuousLoop`. Use this helper at the top of the test class:

```kotlin
private fun buildScheduler(
    rankingProvider: ObjectProvider<RankingFetchPhase>,
    charBasicProvider: ObjectProvider<CharacterBasicFetchPhase>,
    itemEquipmentProvider: ObjectProvider<ItemEquipmentFetchPhase> = mock { on { ifAvailable } doReturn null },
    schedulerMetrics: SchedulerMetrics = mock(),
    ocidLookupPhase: OcidLookupPhase = mock(),
    ocidCache: OcidCacheProvider = mock(),
    runStatusTracker: RunStatusTracker = mock(),
): ExternalApiScheduler {
    return ExternalApiScheduler(
        ocidLookupPhase = ocidLookupPhase,
        ocidCacheProvider = ocidCache,
        rankingFetchPhaseProvider = rankingProvider,
        characterBasicPhaseProvider = charBasicProvider,
        itemEquipmentFetchPhaseProvider = itemEquipmentProvider,
        schedulerMetrics = schedulerMetrics,
        runStatusTracker = runStatusTracker,
        runIdGenerator = RunIdGenerator(Clock.systemUTC()),
        runOnStartup = false,
        skipCharacterBasic = false,
    )
}
```

Add imports:

```kotlin
import maple.externalapi.metrics.SchedulerMetrics
import maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase
```

Replace each `ExternalApiScheduler(...)` call site with `buildScheduler(rankingProvider, charBasicProvider, ...)`. Existing tests that assert the OCID lookup chain should still pass after the chain is moved to `triggerPhase` (Task 6) — these tests will be replaced in Task 7 when `triggerDailyRefresh` is refactored.

- [ ] **Step 5: Add `runCharBasicPhase` method**

Append to `ExternalApiScheduler.kt`:

```kotlin
/**
 * Run CHARACTER_BASIC phase standalone. Reads OCID cache, calls char-basic
 * phase bean. [upstreamRunId] is used to verify the upstream OCID mapping is
 * present; if the cache is empty the phase short-circuits (consistent with
 * daily-refresh behavior).
 */
fun runCharBasicPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
    require(upstreamRunId != null) { "CHARACTER_BASIC requires upstreamRunId" }
    val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
        ?: return CompletableFuture.failedFuture(
            IllegalStateException("CHARACTER_BASIC slot occupied")
        )

    val charBasicPhase = characterBasicPhaseProvider.ifAvailable
    if (charBasicPhase == null) {
        runStatusTracker.failRun(PipelinePhase.CHARACTER_BASIC, runId, "character-basic phase not enabled")
        return CompletableFuture.failedFuture(
            IllegalStateException("character-basic phase not enabled")
        )
    }

    val ocidCache = ocidCacheProvider.current()
    if (ocidCache.isEmpty()) {
        log.warn("[Scheduler] OCID cache empty, skipping character-basic runId={}", runId)
        runStatusTracker.completeRun(PipelinePhase.CHARACTER_BASIC, runId, 0, 0)
        runStatusTracker.releasePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
        return CompletableFuture.completedFuture(null)
    }

    return charBasicPhase.execute(executor, ocidCache)
        .whenComplete { _, ex ->
            if (ex != null) {
                log.error("[Scheduler] runCharBasicPhase failed runId={}", runId, ex)
                runStatusTracker.failRun(PipelinePhase.CHARACTER_BASIC, runId, ex.message ?: "unknown")
            } else {
                runStatusTracker.completeRun(PipelinePhase.CHARACTER_BASIC, runId, 0, 0)
            }
            runStatusTracker.releasePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
        }
        .thenApply { }
}
```

- [ ] **Step 6: Add `runItemEquipmentPhase` method (folds in loop body)**

Append to `ExternalApiScheduler.kt`:

```kotlin
/**
 * Run ITEM_EQUIPMENT phase standalone. Folds in the body of the legacy
 * ItemEquipmentContinuousLoop single cycle: read OCID cache, call
 * itemEquipmentFetchPhase bean, record chunks/records, complete slot.
 *
 * Single-shot: does not loop. Caller (controller or triggerPhase) decides
 * how often to invoke. The continuous-loop auto-resume on startup is gone.
 */
fun runItemEquipmentPhase(runId: String, upstreamRunId: String?): CompletableFuture<Void> {
    require(upstreamRunId != null) { "ITEM_EQUIPMENT requires upstreamRunId" }
    val acquired = runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
        ?: return CompletableFuture.failedFuture(
            IllegalStateException("ITEM_EQUIPMENT slot occupied")
        )

    val itemEquipmentPhase = itemEquipmentFetchPhaseProvider.ifAvailable
    if (itemEquipmentPhase == null) {
        runStatusTracker.failRun(PipelinePhase.ITEM_EQUIPMENT, runId, "item-equipment phase not enabled")
        return CompletableFuture.failedFuture(
            IllegalStateException("item-equipment phase not enabled")
        )
    }

    val entries = ocidCacheProvider.current().entries.toList()
    if (entries.isEmpty()) {
        log.warn("[Scheduler] OCID cache empty, skipping item-equipment runId={}", runId)
        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, runId, 0, 0)
        runStatusTracker.releasePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
        return CompletableFuture.completedFuture(null)
    }

    return itemEquipmentPhase.execute(executor, entries, runId)
        .whenComplete { _, ex ->
            if (ex != null) {
                log.error("[Scheduler] runItemEquipmentPhase failed runId={}", runId, ex)
                runStatusTracker.failRun(PipelinePhase.ITEM_EQUIPMENT, runId, ex.message ?: "unknown")
            } else {
                val chunks = schedulerMetrics.drainRunChunks().toInt()
                val records = schedulerMetrics.drainRunRecords()
                runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, runId, chunks, records)
            }
            runStatusTracker.releasePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
        }
        .thenApply { }
}
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"`
Expected: existing tests may fail because the test class passes the old constructor signature. Tests need to be updated (Step 4) — apply helper usage to all sites.

- [ ] **Step 8: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "feat(ext-api): extract runCharBasicPhase and runItemEquipmentPhase methods"
```

---

### Task 6: Add `scheduler.triggerPhase(phase, runId, upstreamRunId)` public entry point

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

- [ ] **Step 1: Add failing test for `triggerPhase` dispatch**

Append to `ExternalApiSchedulerTest.kt`:

```kotlin
@Test
fun `triggerPhase dispatches to runRankingPhase for RANKING_FETCH`() {
    val rankingPhase = mock<RankingFetchPhase>()
    whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
        .thenReturn(CompletableFuture.completedFuture("runs/run-r-1"))

    val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
    whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
    val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
    whenever(charBasicProvider.ifAvailable).thenReturn(null)
    val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
    val ocidLookupPhase = mock<OcidLookupPhase>()
    val ocidCache = mock<OcidCacheProvider>()
    val runStatusTracker = mock<RunStatusTracker>()

    val scheduler = buildScheduler(
        rankingProvider = rankingProvider,
        charBasicProvider = charBasicProvider,
        itemEquipmentProvider = itemEquipmentProvider,
        ocidLookupPhase = ocidLookupPhase,
        ocidCache = ocidCache,
        runStatusTracker = runStatusTracker,
    )

    scheduler.triggerPhase(PipelinePhase.RANKING_FETCH, "run-r-1", null).get()

    verify(rankingPhase).execute(any<ExecutorService>(), eq("run-r-1"))
}

@Test
fun `triggerPhase returns failed future for unknown phase`() {
    val scheduler = buildScheduler(
        rankingProvider = mock(),
        charBasicProvider = mock(),
    )
    val result = scheduler.triggerPhase(PipelinePhase.IDLE, "run-x", null)
    assertThat(result.isCompletedExceptionally).isTrue()
}

@Test
fun `triggerPhase rejects missing upstreamRunId for OCID_LOOKUP`() {
    val scheduler = buildScheduler(
        rankingProvider = mock(),
        charBasicProvider = mock(),
    )
    val result = scheduler.triggerPhase(PipelinePhase.OCID_LOOKUP, "run-o-1", null)
    assertThat(result.isCompletedExceptionally).isTrue()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: FAIL with `unresolved reference: triggerPhase`.

- [ ] **Step 3: Add `triggerPhase` method**

Append to `ExternalApiScheduler.kt`:

```kotlin
/**
 * Public entry point. Dispatches to the right per-phase method based on [phase].
 * Returns a CompletableFuture that completes when the phase reaches terminal
 * state (COMPLETED or FAILED). The /api/internal/trigger/phase controller
 * and triggerDailyRefresh both call this.
 *
 * Phases IDLE, OCID_CACHE_REFRESH, CHARACTER_BASIC_DONE, COMPLETED, FAILED
 * are not valid standalone triggers — they are intermediate states. Returns
 * a failed future for these.
 */
fun triggerPhase(phase: PipelinePhase, runId: String, upstreamRunId: String?): CompletableFuture<Void> {
    return when (phase) {
        PipelinePhase.RANKING_FETCH -> runRankingPhase(runId, upstreamRunId)
        PipelinePhase.OCID_LOOKUP -> runOcidPhase(runId, upstreamRunId)
        PipelinePhase.CHARACTER_BASIC -> runCharBasicPhase(runId, upstreamRunId)
        PipelinePhase.ITEM_EQUIPMENT -> runItemEquipmentPhase(runId, upstreamRunId)
        else -> CompletableFuture.failedFuture(
            IllegalArgumentException("Phase $phase is not a standalone-triggerable phase")
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"`
Expected: New tests PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "feat(ext-api): add scheduler.triggerPhase dispatch entry point"
```

---

### Task 7: Refactor `triggerDailyRefresh` to chain 4 `triggerPhase` calls

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`

- [ ] **Step 1: Add failing test for daily-chain verification**

Append to `ExternalApiSchedulerTest.kt`:

```kotlin
@Test
fun `triggerDailyRefresh chains 4 triggerPhase calls in order`() {
    val rankingPhase = mock<RankingFetchPhase>()
    whenever(rankingPhase.execute(any<ExecutorService>(), any<String>()))
        .thenReturn(CompletableFuture.completedFuture("runs/run-daily-r"))
    val ocidLookupPhase = mock<OcidLookupPhase>()
    val ocidCache = mock<OcidCacheProvider>()
    val runStatusTracker = mock<RunStatusTracker>()

    val rankingProvider = mock<ObjectProvider<RankingFetchPhase>>()
    whenever(rankingProvider.ifAvailable).thenReturn(rankingPhase)
    val charBasicProvider = mock<ObjectProvider<CharacterBasicFetchPhase>>()
    whenever(charBasicProvider.ifAvailable).thenReturn(null)
    val itemEquipmentProvider = mock<ObjectProvider<ItemEquipmentFetchPhase>>()
    val schedulerMetrics = mock<SchedulerMetrics>()

    val scheduler = buildScheduler(
        rankingProvider = rankingProvider,
        charBasicProvider = charBasicProvider,
        itemEquipmentProvider = itemEquipmentProvider,
        schedulerMetrics = schedulerMetrics,
        ocidLookupPhase = ocidLookupPhase,
        ocidCache = ocidCache,
        runStatusTracker = runStatusTracker,
    )

    scheduler.triggerDailyRefresh("daily-run-1").get()

    // All 4 phase slots acquired in order
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.RANKING_FETCH, "daily-r")
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "daily-o")
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.CHARACTER_BASIC, "daily-cb")
    verify(runStatusTracker).acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "daily-ie")
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — `triggerDailyRefresh` returns Unit, not `CompletableFuture<Void>`.

- [ ] **Step 3: Replace `triggerDailyRefresh` body**

In `ExternalApiScheduler.kt`, replace the existing `triggerDailyRefresh` body:

```kotlin
/**
 * Daily pipeline trigger. Chains 4 per-phase runs sequentially, each with
 * its own runId. 409 protection lives in the controller layer (checks
 * RANKING_FETCH slot occupancy before submitting).
 */
fun triggerDailyRefresh(airflowRunId: String?): CompletableFuture<Void> {
    if (skipCharacterBasic) {
        log.info("[Scheduler] skip-character-basic enabled, loading OCID cache from existing data")
        ocidCacheProvider.refresh()
        return CompletableFuture.completedFuture(null)
    }

    val rankingPhase = rankingFetchPhaseProvider.ifAvailable
    if (rankingPhase == null) {
        log.error("[Scheduler] ranking fetch phase is required but not enabled")
        return CompletableFuture.failedFuture(
            IllegalStateException("ranking fetch phase not enabled")
        )
    }

    val rRunId = runIdGenerator.newRunId()
    val oRunId = runIdGenerator.newRunId()
    val cbRunId = runIdGenerator.newRunId()
    val ieRunId = runIdGenerator.newRunId()

    log.info("[Scheduler] daily chain starting r={} o={} cb={} ie={}", rRunId, oRunId, cbRunId, ieRunId)

    return triggerPhase(PipelinePhase.RANKING_FETCH, rRunId, null)
        .thenCompose { triggerPhase(PipelinePhase.OCID_LOOKUP, oRunId, rRunId) }
        .thenCompose {
            if (characterBasicPhaseProvider.ifAvailable == null) {
                log.warn("[Scheduler] character-basic phase not enabled, skipping")
                CompletableFuture.completedFuture(null)
            } else {
                ocidCacheProvider.refresh()
                triggerPhase(PipelinePhase.CHARACTER_BASIC, cbRunId, oRunId)
            }
        }
        .thenCompose { triggerPhase(PipelinePhase.ITEM_EQUIPMENT, ieRunId, cbRunId) }
        .whenComplete { _, ex ->
            if (ex != null) {
                log.error("[Scheduler] daily chain failed", ex)
            } else {
                log.info("[Scheduler] daily chain completed r={} o={} cb={} ie={}", rRunId, oRunId, cbRunId, ieRunId)
            }
        }
}
```

Note: the legacy `acquireLock`/`releaseLock` mechanism is removed. Per-phase slot acquisition in `acquirePhaseSlot` already prevents double-runs. The `running` AtomicBoolean + ReentrantLock + Condition is no longer needed. Keep them removed.

- [ ] **Step 4: Remove unused lock infrastructure from scheduler**

In `ExternalApiScheduler.kt`, remove these field/method declarations:

```kotlin
private val running = AtomicBoolean(false)
private val shutdown = AtomicBoolean(false)
private val lock = ReentrantLock()
private val idle = lock.newCondition()
```

And remove `acquireLock(timeoutMs)` and `releaseLock()` private methods.

Update imports — remove:

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
```

- [ ] **Step 5: Update `onStartup` — remove item-equipment loop start, keep run-on-startup**

In `onStartup`, replace:

```kotlin
@EventListener(ApplicationReadyEvent::class)
fun onStartup() {
    ocidCacheProvider.refresh()
    if (runOnStartup) {
        log.info("[Scheduler] run-on-startup enabled, triggering daily refresh")
        triggerDailyRefresh(null)
    }
    // ItemEquipmentContinuousLoop removed; item-equipment now runs via HTTP trigger.
}
```

- [ ] **Step 6: Update `stopLifecycle` — remove item-equipment loop stop**

Replace `stopLifecycle`:

```kotlin
override fun stopLifecycle() {
    log.info("[Scheduler] shutdown requested")
    executor.close()
}
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"`
Expected: New chain test PASS; some existing tests may still reference removed `acquireLock`/`releaseLock` — fix as needed by deleting the affected tests (they tested the old single-daily-lock behavior).

- [ ] **Step 8: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "feat(ext-api): refactor triggerDailyRefresh to chain 4 triggerPhase calls"
```

---

### Task 8: Add `POST /api/internal/trigger/phase/{phaseName}` controller endpoint

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Add failing tests for `triggerPhase` endpoint**

Append to `InternalApiControllerTest.kt` (read the existing file first to match style; if it uses MockMvc add `@WebMvcTest` setup):

```kotlin
@Test
fun `POST trigger phase returns 202 with runId when slot empty`() {
    val runStatusTracker = mock<RunStatusTracker>()
    val scheduler = mock<ExternalApiScheduler>()
    whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.RANKING_FETCH)).thenReturn(null)
    val controller = InternalApiController(runStatusTracker, scheduler, mock())

    val response = controller.triggerPhase("RANKING_FETCH", null, null)
    assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
    assertThat(response.body?.get("status")).isEqualTo("STARTED")
    assertThat(response.body?.get("runId")).isNotNull()
}

@Test
fun `POST trigger phase returns 400 for invalid phase name`() {
    val controller = InternalApiController(mock(), mock(), mock())
    val response = controller.triggerPhase("BOGUS_PHASE", null, null)
    assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    assertThat(response.body?.get("error")).isEqualTo("INVALID_PHASE")
}

@Test
fun `POST trigger phase returns 400 for OCID_LOOKUP without upstreamRunId`() {
    val controller = InternalApiController(mock(), mock(), mock())
    val response = controller.triggerPhase("OCID_LOOKUP", null, null)
    assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    assertThat(response.body?.get("error")).isEqualTo("MISSING_UPSTREAM")
}

@Test
fun `POST trigger phase returns 409 when slot occupied`() {
    val runStatusTracker = mock<RunStatusTracker>()
    val existing = RunStatus(
        runId = "existing-run",
        phase = PipelinePhase.CHARACTER_BASIC,
        triggeredPhase = PipelinePhase.CHARACTER_BASIC,
        startedAt = java.time.Instant.now(),
    )
    whenever(runStatusTracker.hasNonTerminalRun(PipelinePhase.CHARACTER_BASIC)).thenReturn(existing)
    val controller = InternalApiController(runStatusTracker, mock(), mock())

    val response = controller.triggerPhase("CHARACTER_BASIC", null, "upstream")
    assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    assertThat(response.body?.get("status")).isEqualTo("ALREADY_RUNNING")
    assertThat(response.body?.get("runId")).isEqualTo("existing-run")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Expected: FAIL with `unresolved reference: triggerPhase`.

- [ ] **Step 3: Add `triggerPhase` method to `InternalApiController`**

Append to `InternalApiController.kt`:

```kotlin
private val triggerablePhases = setOf(
    PipelinePhase.RANKING_FETCH,
    PipelinePhase.OCID_LOOKUP,
    PipelinePhase.CHARACTER_BASIC,
    PipelinePhase.ITEM_EQUIPMENT,
)

@PostMapping("/trigger/phase/{phaseName}")
fun triggerPhase(
    @PathVariable phaseName: String,
    @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
    @RequestHeader("X-Upstream-Run-Id", required = false) upstreamRunId: String?,
): ResponseEntity<Map<String, String>> {
    val phase = try {
        PipelinePhase.valueOf(phaseName)
    } catch (ex: IllegalArgumentException) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "INVALID_PHASE", "allowed" to triggerablePhases.map { it.name }.toString()))
    }

    if (phase !in triggerablePhases) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "INVALID_PHASE", "allowed" to triggerablePhases.map { it.name }.toString()))
    }

    if (phase != PipelinePhase.RANKING_FETCH && upstreamRunId.isNullOrBlank()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "MISSING_UPSTREAM", "phase" to phase.name))
    }

    val existing = runStatusTracker.hasNonTerminalRun(phase)
    if (existing != null) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("status" to "ALREADY_RUNNING", "runId" to existing.runId))
    }

    val runId = airflowRunId ?: UUID.randomUUID().toString()
    executor.submit { scheduler.triggerPhase(phase, runId, upstreamRunId) }
    return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
}
```

Add imports:

```kotlin
import maple.externalapi.runstatus.PipelinePhase
```

- [ ] **Step 4: Run controller tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(ext-api): add POST /api/internal/trigger/phase/{phaseName} endpoint"
```

---

### Task 9: Delete `ItemEquipmentContinuousLoop.kt` and remove its bean wiring

**Files:**
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt`
- Modify: any Spring configuration files that wire the bean (if present)

- [ ] **Step 1: Search for `ItemEquipmentContinuousLoop` references**

Run: `grep -rn "ItemEquipmentContinuousLoop" module-external-api/src --include="*.kt" --include="*.java"`

Expected: only the file itself and the scheduler constructor (already removed in Task 5/7).

- [ ] **Step 2: Delete the file**

Run: `git rm module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt`

- [ ] **Step 3: Compile to verify no broken references**

Run: `./gradlew :module-external-api:compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run full ext-api test suite**

Run: `./gradlew :module-external-api:test`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(ext-api): delete ItemEquipmentContinuousLoop, item-equipment runs via trigger endpoint"
```

---

### Task 10: Manual smoke test against local stack

- [ ] **Step 1: Start infrastructure**

```bash
docker compose up -d minio postgres kafka
```

- [ ] **Step 2: Boot ext-api module**

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun
```

Wait for `curl -sf http://localhost:8081/actuator/health` to return 200.

- [ ] **Step 3: Trigger each phase standalone with runId chain**

```bash
# 1. RANKING_FETCH
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/RANKING_FETCH \
  -H "X-Airflow-Run-Id: smoke-r1" | python3 -m json.tool
# Poll /run-status until slot[RANKING_FETCH] reaches terminal
curl -s http://localhost:8081/api/internal/run-status | jq '.slots.RANKING_FETCH'

# 2. OCID_LOOKUP
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/OCID_LOOKUP \
  -H "X-Airflow-Run-Id: smoke-r2" -H "X-Upstream-Run-Id: smoke-r1" | python3 -m json.tool

# 3. CHARACTER_BASIC
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/CHARACTER_BASIC \
  -H "X-Airflow-Run-Id: smoke-r3" -H "X-Upstream-Run-Id: smoke-r2" | python3 -m json.tool

# 4. ITEM_EQUIPMENT
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/ITEM_EQUIPMENT \
  -H "X-Airflow-Run-Id: smoke-r4" -H "X-Upstream-Run-Id: smoke-r3" | python3 -m json.tool
```

- [ ] **Step 4: Verify MinIO chunks landed**

```bash
mc alias set local http://localhost:9000 minioadmin <MINIO_ROOT_PASSWORD>
mc ls local/maple-expectation/runs/smoke-r1/   # expect ranking chunks + _SUCCESS
mc ls local/maple-expectation/runs/smoke-r2/   # expect ocid-mapping/ + chunks
mc ls local/maple-expectation/runs/smoke-r4/   # expect item-equipment chunks + _SUCCESS
```

- [ ] **Step 5: Verify 409 on duplicate phase trigger**

```bash
# While smoke-r1 is still running, try another RANKING_FETCH
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/RANKING_FETCH \
  -H "X-Airflow-Run-Id: smoke-r1-dup" -w "\nHTTP %{http_code}\n"
# Expected: HTTP 409 + {"status":"ALREADY_RUNNING","runId":"smoke-r1"}
```

- [ ] **Step 6: Verify 400 on invalid phase name**

```bash
curl -s -X POST http://localhost:8081/api/internal/trigger/phase/BOGUS \
  -w "\nHTTP %{http_code}\n"
# Expected: HTTP 400 + {"error":"INVALID_PHASE",...}
```

- [ ] **Step 7: Verify daily trigger still works**

```bash
curl -s -X POST http://localhost:8081/api/internal/trigger/daily \
  -H "X-Airflow-Run-Id: smoke-daily" | python3 -m json.tool
# Poll /run-status — expect 4 slots transiently populated
```

- [ ] **Step 8: Stop ext-api and document outcome**

```bash
# Stop bootRun
lsof -ti:8081 | xargs kill -9
```

Note any failures in the PR description.

---

## Self-Review

After implementation:

1. **Spec coverage:**
   - `POST /api/internal/trigger/phase/{phaseName}` — Task 8 ✓
   - 202 with runId on success — Task 8 ✓
   - 409 with runId on conflict — Task 8 ✓
   - 4 triggerable phases — Task 8 ✓
   - `X-Airflow-Run-Id` header — Task 8 ✓
   - `X-Upstream-Run-Id` header — Task 8 ✓
   - Per-phase methods extracted — Tasks 4, 5 ✓
   - `triggerDailyRefresh` chains 4 phase calls — Task 7 ✓
   - Single-phase run reaches COMPLETED without CHARACTER_BASIC_DONE — Task 2 (slot-based complete) + Task 5 (runCharBasicPhase direct complete) ✓
   - `RunStatus.triggeredPhase` field — Task 1 ✓
   - `ItemEquipmentContinuousLoop` retired — Task 9 ✓
   - Manual smoke test — Task 10 ✓

2. **Placeholders:** None. All steps have code.

3. **Type consistency:**
   - `triggerPhase(phase, runId, upstreamRunId)` signature matches across controller (Task 8), scheduler (Task 6), and tests (Tasks 1, 4-8).
   - `acquirePhaseSlot(phase, runId)` returns `RunStatus?` consistent across tracker (Task 2) and scheduler callers (Tasks 4-7).
   - `RunStatus.triggeredPhase` field added in Task 1, used in tests (Tasks 1, 2).

4. **Open items from spec:**
   - **Open Item 1 (Kafka consumer role):** Resolved — `ItemEquipmentContinuousLoop` had no Kafka listener; the loop only did the cycle. Deletion is safe. `KafkaSnapshotChunkReadyConsumer` (separate component) handles run completion signal.
   - **Open Item 2 (phase bean signatures):** `OcidLookupPhase.execute` already takes `runKey`; `CharacterBasicFetchPhase` and `ItemEquipmentFetchPhase` already take `ocidCache`/`entries` — no signature change needed.
   - **Open Item 3 (legacy `current` field):** Kept as deprecated alias in `RunStatusResponse` (Task 3).

Plan complete.

---

## Plan Revisions (post-grilling)

5 gaps resolved via `/grill-me` skill. Apply during implementation:

### Revision 1: Slot lifecycle semantics (Gap C)

**Affects:** Task 2 (tracker), Tasks 4-6 (per-phase methods).

`acquirePhaseSlot` accepts slots whose current value is `null` OR terminal (CAS-replace). `completeRun` leaves the slot populated with `phase=COMPLETED`. `releasePhaseSlot` is called **only on FAILED**, never on success. This preserves the last-completed record across phases.

Replace Task 2 step 3 `acquirePhaseSlot`:

```kotlin
fun acquirePhaseSlot(phase: PipelinePhase, runId: String): RunStatus? {
    val slot = slots.computeIfAbsent(phase) { AtomicReference(null) }
    val now = Instant.now(clock)
    val candidate = RunStatus(
        runId = runId,
        phase = phase,
        triggeredPhase = phase,
        startedAt = now,
        updatedAt = now,
    )
    return slot.updateAndGet { current ->
        if (current == null || current.isTerminal) candidate else current
    }.let { result ->
        if (result.runId == runId && result.startedAt == now) {
            log.info("[RunStatus] phase-slot acquired phase={} runId={}", phase, runId)
            result
        } else {
            log.warn("[RunStatus] phase-slot occupied phase={} existingRunId={}", phase, result.runId)
            null
        }
    }
}
```

In Tasks 4-6 `whenComplete` blocks: drop `releasePhaseSlot` from the success path. Keep it in the failure path:

```kotlin
.whenComplete { _, ex ->
    if (ex != null) {
        runStatusTracker.failRun(phase, runId, ex.message ?: "unknown")
        runStatusTracker.releasePhaseSlot(phase, runId)
    } else {
        runStatusTracker.completeRun(phase, runId, 0, 0)
        // do NOT release — slot keeps COMPLETED record for /run-status
    }
}
```

### Revision 2: Controller `triggerDailyRefresh` call (Gap B)

**Affects:** Task 7 + Task 8 controller wiring.

In `InternalApiController.triggerDailyRefresh`, change:

```kotlin
executor.submit { scheduler.triggerDailyRefresh(runId).join() }
```

(`triggerDailyRefresh` returns `CompletableFuture<Void>`; the lambda must adapt to `Runnable`. `.join()` runs the chain on the executor thread. Acceptable per async-patterns.md — controller executor thread is fair game.)

### Revision 3: `OcidCacheProvider.loadFromRun(runId)` for char-basic / item-equipment (Gap A)

**Affects:** New sub-task in Task 5 (between steps 1 and 5).

Add to `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`:

```kotlin
/**
 * Load OCID mapping from a specific prior run. Used by standalone char-basic
 * and item-equipment triggers to consume a known upstream's OCID file rather
 * than the most-recent one. Key format: `ocid-mapping/ocid-mapping-{runId}.jsonl.gz`.
 * Returns the loaded map and updates the cache reference.
 */
fun loadFromRun(runId: String): Map<String, String> {
    val key = "ocid-mapping/ocid-mapping-$runId.jsonl.gz"
    val map = HashMap<String, String>()
    var parseErrors = 0
    try {
        GZIPInputStream(BufferedInputStream(objectStorage.getStream(key))).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val entry = parseLine(line)
                if (entry != null) {
                    map[entry.first] = entry.second
                } else {
                    parseErrors++
                }
            }
        }
    } catch (ex: Exception) {
        log.error("[OcidCache] loadFromRun failed runId={} key={}", runId, key, ex)
        return emptyMap()
    }
    cacheRef.set(map)
    if (parseErrors > 0) {
        log.warn("[OcidCache] loaded from runId={}: {} entries ({} parse errors)", runId, map.size, parseErrors)
    } else {
        log.info("[OcidCache] loaded from runId={}: {} entries", runId, map.size)
    }
    return map
}
```

Use in `runCharBasicPhase` (Task 5 step 5) and `runItemEquipmentPhase` (Task 5 step 6):

```kotlin
val ocidCache = if (upstreamRunId != null) {
    val loaded = ocidCacheProvider.loadFromRun(upstreamRunId)
    if (loaded.isEmpty()) {
        log.warn("[Scheduler] upstream OCID mapping empty for runId={} upstreamRunId={}", runId, upstreamRunId)
    }
    loaded
} else {
    ocidCacheProvider.current()
}
```

### Revision 4: `runId` parameter on phase beans (Gap D)

**Affects:** Task 5 step 5 + Task 5 step 6.

Modify `CharacterBasicFetchPhase.execute` and `ItemEquipmentFetchPhase.execute` to accept an explicit `runId` parameter. When the scheduler passes the slot's `runId`, the bean uses it; when `runIdGenerator.newRunId()` is the fallback (existing daily-refresh behavior), it's preserved by leaving the parameter nullable with a fallback.

In `CharacterBasicFetchPhase.execute`:

```kotlin
fun execute(workerExecutor: ExecutorService, ocidCache: Map<String, String>, runId: String? = null): CompletableFuture<Unit> {
    val existing = objectStorage.listByPrefix("character-basic/")
    if (existing.isNotEmpty()) {
        log.info("[Scheduler] character-basic already done ({} files), skipping", existing.size)
        return CompletableFuture.completedFuture(Unit)
    }

    val entries = ocidCache.entries.toList()
    if (entries.isEmpty()) {
        log.warn("[Scheduler] OCID cache empty, skipping character-basic")
        return CompletableFuture.completedFuture(Unit)
    }

    val effectiveRunId = runId ?: runIdGenerator.newRunId()
    val chunkConfig = chunkingProperties.configFor("character-basic")
    val runKey = "runs/$effectiveRunId/character-basic"
    runMarkerWriter.writeRunMarker(runKey)
    val sink = sinkFactory.createForCharacterBasic(runKey)
    // ... rest unchanged, use effectiveRunId instead of runId
}
```

In `ItemEquipmentFetchPhase.execute`:

```kotlin
fun execute(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>, runId: String? = null): CompletableFuture<Unit> {
    // ... existing early returns ...
    val effectiveRunId = runId ?: "ie-${UUID.randomUUID().toString().take(8)}"  // preserve existing fallback
    val chunkConfig = chunkingProperties.configFor("item-equipment")
    val runKey = "runs/$effectiveRunId/item-equipment"
    // ... rest unchanged, use effectiveRunId
}
```

Note: `ItemEquipmentFetchPhase` previously did not generate its own runId (loop always passed one). The fallback preserves that behavior. Verify the existing fallback in the production code path before changing — read the full file.

### Revision 5: `OcidLookupPhase.deleteOldMappingFiles` filter by current runId (Gap E)

**Affects:** Task 4 step 3 `runOcidPhase`.

Modify `OcidLookupPhase.deleteOldMappingFiles` to accept a `currentRunId` and skip that file:

```kotlin
private fun deleteOldMappingFiles(mappingDir: String, currentRunId: String) {
    val prefix = "$mappingDir/"
    val total = objectStorage.deleteByPrefix(prefix)
    // Re-write the current run's mapping file if it was accidentally deleted above
    // (defensive — the writer coroutine below re-creates it anyway)
    log.info("[Scheduler] deleted {} old OCID mapping objects in {}/ (current runId={} preserved by re-write)",
        total, mappingDir, currentRunId)
}
```

Wait — `deleteByPrefix` deletes ALL objects including the current runId's. The writer coroutine in `OcidLookupPhase.execute` writes the current run's mapping AFTER the delete. So the current run's mapping is the freshly-written one. But if a CONCURRENT standalone trigger is mid-write, the daily's `deleteByPrefix` clobbers it.

Fix: skip current runId in the delete loop. Replace `deleteByPrefix` with explicit per-object delete filtered by name:

```kotlin
private fun deleteOldMappingFiles(mappingDir: String, currentRunId: String) {
    val prefix = "$mappingDir/"
    val objects = objectStorage.listByPrefix(prefix)
    val toDelete = objects.filter { !it.key.endsWith("ocid-mapping-$currentRunId.jsonl.gz") }
    var deleted = 0
    for (obj in toDelete) {
        if (objectStorage.delete(obj.key)) deleted++
    }
    log.info("[Scheduler] deleted {} old OCID mapping objects in {}/ (preserved current runId={})",
        deleted, mappingDir, currentRunId)
}
```

(Verify `ObjectStorage` has a per-key `delete(key)` method; if not, fall back to `deleteByPrefix` with explicit exclusion list management. Read `ObjectStorage` interface before implementing.)

Update `OcidLookupPhase.execute` to pass `runId`:

```kotlin
suspend fun execute(workerExecutor: ExecutorService, runKey: String, runId: String) {
    val mappingDir = "ocid-mapping"
    deleteOldMappingFiles(mappingDir, runId)  // pass runId
    // ... rest unchanged
}
```

Update `runOcidPhase` (Task 4 step 3) to extract runId from upstreamRunId and pass it:

```kotlin
val upstreamKey = "runs/$upstreamRunId"
return runBlocking { ocidLookupPhase.execute(executor, upstreamKey, upstreamRunId) }
    // ... rest unchanged
```

(Note: `runKey` is `runs/$upstreamRunId`. The OCID mapping file written by this phase is `ocid-mapping/ocid-mapping-{runId}.jsonl.gz` where `runId = runKey.removePrefix("runs/").substringBefore('/')` = upstreamRunId. So the daily path passes the char-basic runId. Standalone OCID_LOOKUP passes the upstream runId. Verify this matches the existing extraction in OcidLookupPhase line 100: `val runId = runKey.removePrefix("runs/").substringBefore('/')`. So `runKey = runs/<X>` → runId = X. When called standalone with upstreamRunId=X, runKey=`runs/X`, runId=X. Correct.)
