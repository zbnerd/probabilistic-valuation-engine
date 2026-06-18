# Phase Stop Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /api/internal/stop/phase/{phaseName}` to gracefully halt an in-flight ext-api phase at its chunk/page boundary, transitioning the slot to `STOPPED`.

**Architecture:** Shared `PhaseStopSignal` component (ConcurrentHashMap<PipelinePhase, AtomicBoolean>) is consulted by each phase bean's chunk loop. Tripped flag → throw `PhaseStoppedException` → scheduler catches in `whenComplete` → `RunStatusTracker.stopRun()` (terminal-but-retainable, slot persists, next acquire overwrites). Stop endpoint is fire-and-forget (202); not-running case returns 200 NOT_RUNNING.

**Tech Stack:** Kotlin, Spring Boot, ConcurrentHashMap, AtomicBoolean, CompletableFuture whenComplete, JUnit 5 + AssertJ + mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-06-18-issue-1290-phase-stop-endpoint-design.md`

---

## File Structure

**New files:**
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStopSignal.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStoppedException.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/PhaseStopSignalTest.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupportStopTest.kt`

**Modified files:**
- `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`
- `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

---

### Task 1: Add `STOPPED` to PipelinePhase enum + extend RunStatus.isTerminal

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt` (create if missing)

- [ ] **Step 1: Locate or create RunStatusTest**

Check if `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt` exists. If not, create it:

```kotlin
package maple.externalapi.runstatus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.time.Instant

class RunStatusTest {

    private fun statusOf(phase: PipelinePhase): RunStatus = RunStatus(
        runId = "r",
        phase = phase,
        triggeredPhase = phase,
        startedAt = Instant.EPOCH,
    )

    @Test
    fun `COMPLETED is terminal`() {
        assertTrue(statusOf(PipelinePhase.COMPLETED).isTerminal)
    }

    @Test
    fun `FAILED is terminal`() {
        assertTrue(statusOf(PipelinePhase.FAILED).isTerminal)
    }

    @Test
    fun `STOPPED is terminal`() {
        assertTrue(statusOf(PipelinePhase.STOPPED).isTerminal)
    }

    @Test
    fun `RANKING_FETCH is not terminal`() {
        assertFalse(statusOf(PipelinePhase.RANKING_FETCH).isTerminal)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTest"`

Expected: Compile error — `STOPPED` is not a member of `PipelinePhase`.

- [ ] **Step 3: Add STOPPED to PipelinePhase**

Edit `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt`:

```kotlin
package maple.externalapi.runstatus

enum class PipelinePhase {
    IDLE,
    RANKING_FETCH,
    OCID_LOOKUP,
    OCID_CACHE_REFRESH,
    CHARACTER_BASIC,
    CHARACTER_BASIC_DONE,
    ITEM_EQUIPMENT,
    COMPLETED,
    FAILED,
    STOPPED,
}
```

- [ ] **Step 4: Extend RunStatus.isTerminal predicate**

Edit `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt`:

```kotlin
package maple.externalapi.runstatus

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

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
    val isTerminal: Boolean
        get() = phase == PipelinePhase.COMPLETED
            || phase == PipelinePhase.FAILED
            || phase == PipelinePhase.STOPPED
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTest"`

Expected: PASS (4/4).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt \
        module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatus.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTest.kt
git commit -m "feat(ext-api): add STOPPED to PipelinePhase + extend isTerminal

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: PhaseStoppedException

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStoppedException.kt`

- [ ] **Step 1: Create the exception class**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStoppedException.kt`:

```kotlin
package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase

/**
 * Thrown by phase beans when they detect a stop request at a chunk/page/batch
 * boundary. Caught specifically in `ExternalApiScheduler.runXxxPhase` `whenComplete`
 * handlers to drive a STOPPED terminal transition (vs. FAILED for other exceptions).
 */
class PhaseStoppedException(
    val phase: PipelinePhase,
) : RuntimeException("phase ${phase.name} stopped at chunk boundary")
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStoppedException.kt
git commit -m "feat(ext-api): add PhaseStoppedException

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: PhaseStopSignal component + tests

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStopSignal.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/PhaseStopSignalTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/PhaseStopSignalTest.kt`:

```kotlin
package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PhaseStopSignalTest {

    @Test
    fun `requestStop on idle phase returns true and trips flag`() {
        val signal = PhaseStopSignal()
        assertTrue(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(signal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestStop is idempotent — second call returns false (no state change)`() {
        val signal = PhaseStopSignal()
        assertTrue(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(signal.requestStop(PipelinePhase.ITEM_EQUIPMENT))
        assertTrue(signal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `isStopRequested on never-requested phase is false`() {
        val signal = PhaseStopSignal()
        assertFalse(signal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }

    @Test
    fun `clear resets flag to false`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.RANKING_FETCH)
        signal.clear(PipelinePhase.RANKING_FETCH)
        assertFalse(signal.isStopRequested(PipelinePhase.RANKING_FETCH))
    }

    @Test
    fun `clear on never-requested phase is no-op`() {
        val signal = PhaseStopSignal()
        signal.clear(PipelinePhase.CHARACTER_BASIC)
        assertFalse(signal.isStopRequested(PipelinePhase.CHARACTER_BASIC))
    }

    @Test
    fun `flags are per-phase — one phase's stop does not affect another`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.ITEM_EQUIPMENT)
        assertFalse(signal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.PhaseStopSignalTest"`

Expected: Compile error — `PhaseStopSignal` unresolved.

- [ ] **Step 3: Implement PhaseStopSignal**

Create `module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStopSignal.kt`:

```kotlin
package maple.externalapi.scheduler

import maple.externalapi.runstatus.PipelinePhase
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-phase stop flag map. `requestStop` returns the previous state (true on first
 * call, false on idempotent repeat) so callers can tell whether their request was
 * the one that tripped the flag. `clear` is unconditional reset.
 */
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

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.PhaseStopSignalTest"`

Expected: PASS (6/6).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/PhaseStopSignal.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/PhaseStopSignalTest.kt
git commit -m "feat(ext-api): add PhaseStopSignal component

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: RunStatusTracker.stopRun + tests

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` (create if missing)

- [ ] **Step 1: Locate or create RunStatusTrackerTest**

Check if `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` exists. If not, create it with the contents below. If it exists, add the new test methods to the existing file.

```kotlin
package maple.externalapi.runstatus

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RunStatusTrackerTest {

    private val fixedInstant = Instant.parse("2026-06-18T05:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val tracker = RunStatusTracker(clock)

    @Test
    fun `stopRun sets phase to STOPPED with terminal=true and persists slot record`() {
        val acquired = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertNotNull(acquired)

        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", chunksProcessed = 42, recordsProcessed = 1000L)

        val status = tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertNotNull(status, "stopped record must persist in slot")
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertEquals(42, status.chunksProcessed)
        assertEquals(1000L, status.recordsProcessed)
        assertEquals(fixedInstant, status.completedAt)
    }

    @Test
    fun `stopRun with mismatched runId is a no-op`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "different-run", 0, 0L)

        val status = tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(PipelinePhase.ITEM_EQUIPMENT, status!!.phase)
        assertFalse(status.isTerminal)
    }

    @Test
    fun `acquirePhaseSlot after stopRun succeeds (terminal-overwrite)`() {
        tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        tracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)
        assertTrue(tracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)!!.isTerminal)

        val newAcquire = tracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-2")
        assertNotNull(newAcquire, "slot must be acquirable after STOPPED")
        assertEquals("run-2", newAcquire!!.runId)
        assertEquals(PipelinePhase.ITEM_EQUIPMENT, newAcquire.phase)
    }

    @Test
    fun `hasNonTerminalRun returns null after stopRun (allows next trigger)`() {
        tracker.acquirePhaseSlot(PipelinePhase.OCID_LOOKUP, "run-1")
        tracker.stopRun(PipelinePhase.OCID_LOOKUP, "run-1", 0, 0L)

        assertNull(tracker.hasNonTerminalRun(PipelinePhase.OCID_LOOKUP))
    }

    @Test
    fun `stopRun on empty slot is no-op`() {
        tracker.stopRun(PipelinePhase.RANKING_FETCH, "phantom", 0, 0L)
        assertNull(tracker.getPhaseStatus(PipelinePhase.RANKING_FETCH))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`

Expected: Compile error — `stopRun` unresolved.

- [ ] **Step 3: Add stopRun method**

In `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`, add the following method after `failRun` (before `releasePhaseSlot`):

```kotlin
    /**
     * Mark phase slot's run as STOPPED with chunks/records counts. Slot record
     * persists (NOT cleared). Next acquire on the same phase will overwrite the
     * STOPPED terminal record (terminal-overwrite CAS). Use this when a phase run
     * ended because a stop request was detected at a chunk/page boundary.
     */
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

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.RunStatusTrackerTest"`

Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "feat(ext-api): add RunStatusTracker.stopRun

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: BatchFetchSupport — phase on ctx + signal check

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt`
- Modify (existing tests in same package to add `phase` to BatchFetchContext construction)
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupportStopTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupportStopTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import io.github.bucket4j.Bucket
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.snapshot.ChunkedSnapshotSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.concurrent.CompletableFuture

class BatchFetchSupportStopTest {

    @Test
    fun `processBatch throws PhaseStoppedException when stop requested before first batch`() {
        val clientPort = mock<ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenAnswer {
            CompletableFuture.completedFuture(ByteArray(0))
        }
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        val support = BatchFetchSupport(
            clientPort = clientPort,
            fetchMetrics = mock<SnapshotFetchMetrics>(),
            maxInFlight = 10,
            schedulerRateLimiter = mock(),
            schedulerProgressLogger = mock(),
            httpStatusExtractor = mock(),
            stopSignal = signal,
        )

        val ctx = BatchFetchContext(
            endpoint = "item-equipment",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            onFetched = {},
            onFailed = {},
        )
        val sink = mock<ChunkedSnapshotSink>()

        val ex = assertThrows(PhaseStoppedException::class.java) {
            kotlinx.coroutines.runBlocking {
                support.processBatch(
                    rateLimiter = Bucket.builder().addLimit(limit = io.github.bucket4j.Bandwidth.simple(java.time.Duration.ofSeconds(1), 100)).build(),
                    entries = listOf(
                        AbstractMap.SimpleEntry("ign1", "ocid1"),
                        AbstractMap.SimpleEntry("ign2", "ocid2"),
                    ),
                    batchSize = 10,
                    ctx = ctx,
                    sink = sink,
                    runId = "test-run",
                    start = Instant.now(),
                )
            }
        }
        assertEquals(PipelinePhase.ITEM_EQUIPMENT, ex.phase)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.BatchFetchSupportStopTest"`

Expected: Compile error — `BatchFetchContext` lacks `phase`, `BatchFetchSupport` lacks `stopSignal` ctor param, `PhaseStoppedException` unresolved from this package.

- [ ] **Step 3: Add `phase` field to BatchFetchContext**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt`, replace the data class:

```kotlin
data class BatchFetchContext(
    val endpoint: String,
    val phase: PipelinePhase,
    val apiEndpoint: ExternalApiEndpoint,
    val onFetched: () -> Unit,
    val onFailed: () -> Unit,
)
```

Add the import at the top (next to existing imports):

```kotlin
import maple.externalapi.runstatus.PipelinePhase
import maple.externalapi.scheduler.PhaseStopSignal
```

- [ ] **Step 4: Inject stopSignal into BatchFetchSupport constructor**

In the same file, replace the `BatchFetchSupport` constructor signature:

```kotlin
@Component
class BatchFetchSupport(
    private val clientPort: ExternalApiClientPort,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
    private val schedulerRateLimiter: SchedulerRateLimiter,
    private val schedulerProgressLogger: SchedulerProgressLogger,
    private val httpStatusExtractor: HttpStatusExtractor,
    private val stopSignal: PhaseStopSignal,
) {
```

- [ ] **Step 5: Add stop check at top of processBatch while loop**

In `processBatch`, replace the `while (processed < entries.size) {` opening:

```kotlin
        while (processed < entries.size) {
            if (stopSignal.isStopRequested(ctx.phase)) {
                throw PhaseStoppedException(ctx.phase)
            }
            val permits = schedulerRateLimiter.acquirePermitsSuspend(rateLimiter, batchSize, entries.size - processed)
            if (permits == 0) continue
```

Add the import:

```kotlin
import maple.externalapi.scheduler.PhaseStoppedException
```

- [ ] **Step 6: Fix existing callers (CharBasic / ItemEquipment phases will fail to compile next task)**

CharBasic and ItemEquipment phases construct `BatchFetchContext` without `phase`. Compile will fail in those tasks. The two following tasks (8, 9) add `phase = PipelinePhase.XXX` to those constructions.

If any existing test in the module constructs `BatchFetchContext` directly, add `phase = PipelinePhase.ITEM_EQUIPMENT` (or the matching phase) — search with:

```bash
grep -rn "BatchFetchContext(" module-external-api/src/test --include="*.kt"
```

and update each call site.

- [ ] **Step 7: Run stop test, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.BatchFetchSupportStopTest"`

Expected: PASS (1/1).

- [ ] **Step 8: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupportStopTest.kt
git commit -m "feat(ext-api): BatchFetchSupport phase-aware stop check

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: RankingFetchPhase — inject signal + check in processPages

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt` (create if missing)

- [ ] **Step 1: Locate or create RankingFetchPhaseTest**

Check if `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt` exists. If yes, read it to understand construction. If no, skip ahead to step 2.

If creating new, write:

```kotlin
package maple.externalapi.scheduler.phase

import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.runstatus.PipelinePhase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.mock
import java.util.concurrent.Executors

class RankingFetchPhaseStopTest {

    @Test
    fun `execute throws PhaseStoppedException when stop requested before first page`() {
        val signal = PhaseStopSignal()
        signal.requestStop(PipelinePhase.RANKING_FETCH)

        val phase = RankingFetchPhase(
            clientPort = mock(),
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
            chunkingProperties = mock(),
            volumeMetrics = mock(),
            metrics = mock(),
            rankingPublisher = mock(),
            maxPages = 5,
            permitsPerSecond = 100,
            runMarkerWriter = mock(),
            objectStorage = mock(),
            stopSignal = signal,
        )

        val ex = assertThrows(PhaseStoppedException::class.java) {
            phase.execute(Executors.newSingleThreadExecutor(), "test-run").join()
        }
        assertEquals(PipelinePhase.RANKING_FETCH, ex.phase)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RankingFetchPhaseStopTest"`

Expected: Compile error — `stopSignal` ctor param unresolved.

- [ ] **Step 3: Add stopSignal ctor param + check in processPages**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`:

Add imports (next to existing imports):

```kotlin
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
```

Replace constructor:

```kotlin
class RankingFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.ranking.max-pages:300}")
    private val maxPages: Int,
    @Value("\${external-api.ranking.permits-per-second:50}")
    private val permitsPerSecond: Int,
    private val runMarkerWriter: RunMarkerWriter,
    private val objectStorage: ObjectStorage,
    private val stopSignal: PhaseStopSignal,
) {
```

Replace `processPages` opening:

```kotlin
    private fun processPages(
        workerExecutor: ExecutorService,
        sink: ChunkedSnapshotSink,
        rateLimiter: io.github.bucket4j.Bucket,
        date: String,
        currentPage: Int,
        fetched: AtomicInteger,
        failed: AtomicInteger,
    ): CompletableFuture<Void> {
        if (currentPage > maxPages) {
            return CompletableFuture.completedFuture(null)
        }
        if (stopSignal.isStopRequested(PipelinePhase.RANKING_FETCH)) {
            throw PhaseStoppedException(PipelinePhase.RANKING_FETCH)
        }

        SchedulerPhaseUtils.acquirePermits(rateLimiter, 1, 1)
```

- [ ] **Step 4: Update existing RankingFetchPhaseTest construction (if it exists)**

If existing `RankingFetchPhaseTest.kt` constructs `RankingFetchPhase`, add the new ctor arg:

```kotlin
            stopSignal = maple.externalapi.scheduler.PhaseStopSignal(),
```

- [ ] **Step 5: Run all tests in this phase package, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.*"`

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseStopTest.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt
git commit -m "feat(ext-api): RankingFetchPhase stop boundary check

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: OcidLookupPhase — inject signal + check in processBatch

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`

- [ ] **Step 1: Add a stop-check test**

Append to `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`:

```kotlin
    @Test
    fun `execute throws PhaseStoppedException when stop requested before processBatch`() {
        val storage = mock<ObjectStorage>()
        val nexonClient = mock<NexonAuthClient>()
        val objectMapper = ObjectMapper().registerModule(kotlinModule())
        val stopSignal = PhaseStopSignal()
        stopSignal.requestStop(PipelinePhase.OCID_LOOKUP)

        val now = Instant.now()
        whenever(storage.listByPrefix("runs/abc/ranking-overall/chunks")).thenReturn(listOf(
            ObjectInfo("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz", 100, now)
        ))
        val chunkBytes = run {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(out).use { gz ->
                gz.write("{\"key\":\"user1\"}\n".toByteArray())
            }
            out.toByteArray()
        }
        whenever(storage.getStream("runs/abc/ranking-overall/chunks/part-000001.jsonl.gz"))
            .thenReturn(chunkBytes.inputStream())
        whenever(storage.listByPrefix("ocid-mapping/")).thenReturn(emptyList())

        val clientPort = mock<maple.externalapi.port.out.ExternalApiClientPort>()
        whenever(clientPort.fetch(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(ByteArray(0)))

        val phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 100,
            batchSize = 100,
            eventPublisher = mock<maple.externalapi.snapshot.event.SnapshotChunkEventPublisher>(),
            objectStorage = storage,
            nexonAuthClient = nexonClient,
            stopSignal = stopSignal,
        )

        assertThrows(PhaseStoppedException::class.java) {
            kotlinx.coroutines.runBlocking {
                phase.execute(Executors.newSingleThreadExecutor(), "runs/abc", "abc")
            }
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest.execute throws PhaseStoppedException when stop requested before processBatch"`

Expected: Compile error — `stopSignal` ctor param unresolved.

- [ ] **Step 3: Add stopSignal ctor + check at processBatch loop top**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`:

Add imports:

```kotlin
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
```

Replace constructor:

```kotlin
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Qualifier("ocidLookupSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val objectStorage: ObjectStorage,
    private val nexonAuthClient: NexonAuthClient,
    private val stopSignal: PhaseStopSignal,
) {
```

In `processBatch` (private suspend function), replace the `while (current < igns.size) {` opening:

```kotlin
        var current = processed
        while (current < igns.size) {
            if (stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP)) {
                throw PhaseStoppedException(PipelinePhase.OCID_LOOKUP)
            }
            val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - current)
```

- [ ] **Step 4: Update existing OcidLookupPhaseTest construction**

In `OcidLookupPhaseTest.kt`, update the `OcidLookupPhase(...)` construction in the two existing tests to add:

```kotlin
            stopSignal = PhaseStopSignal(),
```

- [ ] **Step 5: Run all tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest"`

Expected: PASS (3/3 including new stop test).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "feat(ext-api): OcidLookupPhase stop boundary check

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: CharacterBasicFetchPhase — set ctx.phase = CHARACTER_BASIC

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhaseTest.kt` (if exists)

- [ ] **Step 1: Update ctx construction in CharacterBasicFetchPhase**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`, replace the `ctx = BatchFetchContext(` block in `execute()`:

```kotlin
        val ctx = BatchFetchContext(
            endpoint = "character-basic",
            phase = PipelinePhase.CHARACTER_BASIC,
            apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
            onFetched = { metrics.recordCharacterBasicFetched() },
            onFailed = { metrics.recordCharacterBasicFailed() },
        )
```

Add import:

```kotlin
import maple.externalapi.runstatus.PipelinePhase
```

- [ ] **Step 2: Update existing test construction**

If `CharacterBasicFetchPhaseTest.kt` constructs `BatchFetchContext` directly, add `phase = PipelinePhase.CHARACTER_BASIC`. Search:

```bash
grep -n "BatchFetchContext(" module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhaseTest.kt
```

- [ ] **Step 3: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.CharacterBasicFetchPhase*"`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhaseTest.kt
git commit -m "feat(ext-api): CharacterBasicFetchPhase wires ctx.phase

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: ItemEquipmentFetchPhase — set ctx.phase = ITEM_EQUIPMENT

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhaseTest.kt` (if exists)

- [ ] **Step 1: Update ctx construction in ItemEquipmentFetchPhase**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`, replace the `ctx = BatchFetchContext(` block in `execute()`:

```kotlin
        val ctx = BatchFetchContext(
            endpoint = "item-equipment",
            phase = PipelinePhase.ITEM_EQUIPMENT,
            apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            onFetched = { metrics.recordItemEquipmentFetched() },
            onFailed = { metrics.recordItemEquipmentFailed() },
        )
```

Add import:

```kotlin
import maple.externalapi.runstatus.PipelinePhase
```

- [ ] **Step 2: Update existing test construction**

If `ItemEquipmentFetchPhaseTest.kt` constructs `BatchFetchContext` directly, add `phase = PipelinePhase.ITEM_EQUIPMENT`. Search:

```bash
grep -n "BatchFetchContext(" module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhaseTest.kt
```

- [ ] **Step 3: Run tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.ItemEquipmentFetchPhase*"`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhaseTest.kt
git commit -m "feat(ext-api): ItemEquipmentFetchPhase wires ctx.phase

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: ExternalApiScheduler — requestPhaseStop + whenComplete stop branch

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` (extend)

- [ ] **Step 1: Wire PhaseStopSignal into ExternalApiSchedulerTest setup (if test constructs scheduler manually)**

If `ExternalApiSchedulerTest.kt` constructs `ExternalApiScheduler(...)` directly (not via Spring autowiring), construct a `PhaseStopSignal` in the `@BeforeEach` (or shared setup) and pass it as the new constructor arg. Example patch:

```kotlin
private val stopSignal = PhaseStopSignal()

@BeforeEach
fun setup() {
    // ... existing setup ...
    scheduler = ExternalApiScheduler(
        // ... existing args ...
        stopSignal = stopSignal,
    )
}
```

If the test uses `@SpringBootTest` or `@WebMvcTest` and the scheduler is autowired, no change to the setup is needed — `PhaseStopSignal` is auto-injected.

- [ ] **Step 2: Extend ExternalApiSchedulerTest with stop-related cases**

Append the following tests to the test class:

```kotlin
    @Test
    fun `requestPhaseStop returns true when phase slot has non-terminal run`() {
        // pre-populate slot with non-terminal run
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        assertTrue(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestPhaseStop returns false when phase slot empty`() {
        assertFalse(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `requestPhaseStop returns false when phase slot already terminal`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-1", 0, 0L)
        assertFalse(scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `runXxxPhase whenComplete catches PhaseStoppedException → stopRun + signal cleared`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        scheduler.requestPhaseStop(PipelinePhase.ITEM_EQUIPMENT)
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))

        // Force the run to throw PhaseStoppedException by stubbing itemEquipmentPhase
        whenever(itemEquipmentPhase.execute(any(), any(), any())).thenReturn(
            CompletableFuture.failedFuture(PhaseStoppedException(PipelinePhase.ITEM_EQUIPMENT))
        )

        val future = scheduler.runItemEquipmentPhase("run-1", "upstream-run")
        future.join() // wait — exception absorbed by whenComplete, future completed normally

        val status = runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)
        assertEquals(PipelinePhase.STOPPED, status!!.phase)
        assertTrue(status.isTerminal)
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT), "signal must be cleared")
    }

    @Test
    fun `runXxxPhase success path clears signal`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        whenever(itemEquipmentPhase.execute(any(), any(), any())).thenReturn(
            CompletableFuture.completedFuture(Unit)
        )

        scheduler.runItemEquipmentPhase("run-1", "upstream-run").join()

        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertEquals(PipelinePhase.COMPLETED, runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)!!.phase)
    }

    @Test
    fun `runXxxPhase generic failure path clears signal`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")
        stopSignal.requestStop(PipelinePhase.ITEM_EQUIPMENT)

        whenever(itemEquipmentPhase.execute(any(), any(), any())).thenReturn(
            CompletableFuture.failedFuture(RuntimeException("nexon down"))
        )

        scheduler.runItemEquipmentPhase("run-1", "upstream-run").join()

        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertEquals(PipelinePhase.FAILED, runStatusTracker.getPhaseStatus(PipelinePhase.ITEM_EQUIPMENT)!!.phase)
    }
```

Add to the imports at the top of `ExternalApiSchedulerTest.kt`:

```kotlin
import maple.externalapi.scheduler.PhaseStopSignal
import maple.externalapi.scheduler.PhaseStoppedException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"`

Expected: Compile error — `requestPhaseStop` unresolved on scheduler, plus signal clearing tests fail.

- [ ] **Step 4: Add stopSignal ctor + requestPhaseStop method**

In `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`:

Add imports:

```kotlin
import maple.externalapi.scheduler.PhaseStopSignal
```

Replace constructor (add `private val stopSignal: PhaseStopSignal,` at the end of the param list):

```kotlin
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
    private val stopSignal: PhaseStopSignal,
) : ManagedLifecycle {
```

Add the new method (anywhere before `triggerPhase`):

```kotlin
    /**
     * Set the stop flag for [phase] if and only if its slot currently holds a
     * non-terminal run. Returns true if the request was applied (or already
     * applied — flag is idempotent); false if there is nothing to stop.
     *
     * Cross-phase safe: only the named phase's flag is set. Other phases
     * continue uninterrupted.
     */
    fun requestPhaseStop(phase: PipelinePhase): Boolean {
        val hadNonTerminal = runStatusTracker.hasNonTerminalRun(phase) != null
        if (hadNonTerminal) {
            stopSignal.requestStop(phase)
            log.info("[Scheduler] stop requested phase={} runId={}",
                phase, runStatusTracker.getPhaseStatus(phase)?.runId)
        }
        return hadNonTerminal || stopSignal.isStopRequested(phase)
    }
```

- [ ] **Step 5: Update whenComplete in runRankingPhase**

In `runRankingPhase`, replace the existing `.whenComplete { _, ex -> ... }.thenRun { }` block:

```kotlin
        return future
            .whenComplete { _, ex ->
                when {
                    ex is PhaseStoppedException -> {
                        log.info("[Scheduler] runRankingPhase stopped runId={} phase={}", runId, ex.phase)
                        runStatusTracker.stopRun(PipelinePhase.RANKING_FETCH, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.RANKING_FETCH)
                    }
                    ex != null -> {
                        log.error("[Scheduler] runRankingPhase failed runId={}", runId, ex)
                        runStatusTracker.failRun(PipelinePhase.RANKING_FETCH, runId, ex.message ?: "unknown")
                        runStatusTracker.releasePhaseSlot(PipelinePhase.RANKING_FETCH, runId)
                        stopSignal.clear(PipelinePhase.RANKING_FETCH)
                    }
                    else -> {
                        runStatusTracker.completeRun(PipelinePhase.RANKING_FETCH, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.RANKING_FETCH)
                    }
                }
            }
            .thenRun { }
```

- [ ] **Step 6: Update whenComplete in runOcidPhase**

Same pattern, in `runOcidPhase`:

```kotlin
        return future
            .whenComplete { _, ex ->
                when {
                    ex is PhaseStoppedException -> {
                        log.info("[Scheduler] runOcidPhase stopped runId={} phase={}", runId, ex.phase)
                        runStatusTracker.stopRun(PipelinePhase.OCID_LOOKUP, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.OCID_LOOKUP)
                    }
                    ex != null -> {
                        log.error("[Scheduler] runOcidPhase failed runId={} upstreamRunId={}", runId, upstreamRunId, ex)
                        runStatusTracker.failRun(PipelinePhase.OCID_LOOKUP, runId, ex.message ?: "unknown")
                        runStatusTracker.releasePhaseSlot(PipelinePhase.OCID_LOOKUP, runId)
                        stopSignal.clear(PipelinePhase.OCID_LOOKUP)
                    }
                    else -> {
                        runStatusTracker.completeRun(PipelinePhase.OCID_LOOKUP, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.OCID_LOOKUP)
                    }
                }
            }
            .thenRun { }
```

- [ ] **Step 7: Update whenComplete in runCharBasicPhase**

Same pattern, replace the `.whenComplete { _, ex -> ... }.thenRun { }` block:

```kotlin
        return future
            .whenComplete { _, ex ->
                when {
                    ex is PhaseStoppedException -> {
                        log.info("[Scheduler] runCharBasicPhase stopped runId={} phase={}", runId, ex.phase)
                        runStatusTracker.stopRun(PipelinePhase.CHARACTER_BASIC, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.CHARACTER_BASIC)
                    }
                    ex != null -> {
                        log.error("[Scheduler] runCharBasicPhase failed runId={}", runId, ex)
                        runStatusTracker.failRun(PipelinePhase.CHARACTER_BASIC, runId, ex.message ?: "unknown")
                        runStatusTracker.releasePhaseSlot(PipelinePhase.CHARACTER_BASIC, runId)
                        stopSignal.clear(PipelinePhase.CHARACTER_BASIC)
                    }
                    else -> {
                        runStatusTracker.completeRun(PipelinePhase.CHARACTER_BASIC, runId, 0, 0)
                        stopSignal.clear(PipelinePhase.CHARACTER_BASIC)
                    }
                }
            }
            .thenRun { }
```

- [ ] **Step 8: Update whenComplete in runItemEquipmentPhase**

Same pattern, replace the `.whenComplete { _, ex -> ... }.thenRun { }` block. Preserve the existing chunks/records drain logic inside the `else` branch:

```kotlin
        return future
            .whenComplete { _, ex ->
                when {
                    ex is PhaseStoppedException -> {
                        log.info("[Scheduler] runItemEquipmentPhase stopped runId={} phase={}", runId, ex.phase)
                        val chunks = schedulerMetrics.drainRunChunks().toInt()
                        val records = schedulerMetrics.drainRunRecords()
                        runStatusTracker.stopRun(PipelinePhase.ITEM_EQUIPMENT, runId, chunks, records)
                        stopSignal.clear(PipelinePhase.ITEM_EQUIPMENT)
                    }
                    ex != null -> {
                        log.error("[Scheduler] runItemEquipmentPhase failed runId={}", runId, ex)
                        runStatusTracker.failRun(PipelinePhase.ITEM_EQUIPMENT, runId, ex.message ?: "unknown")
                        runStatusTracker.releasePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, runId)
                        stopSignal.clear(PipelinePhase.ITEM_EQUIPMENT)
                    }
                    else -> {
                        val chunks = schedulerMetrics.drainRunChunks().toInt()
                        val records = schedulerMetrics.drainRunRecords()
                        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, runId, chunks, records)
                        stopSignal.clear(PipelinePhase.ITEM_EQUIPMENT)
                    }
                }
            }
            .thenRun { }
```

- [ ] **Step 9: Run scheduler tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.ExternalApiSchedulerTest"`

Expected: PASS (existing + new tests).

- [ ] **Step 10: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt
git commit -m "feat(ext-api): scheduler requestPhaseStop + PhaseStoppedException handling

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 11: InternalApiController — stopPhase endpoint + tests

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt`

- [ ] **Step 1: Wire PhaseStopSignal into InternalApiControllerTest setup (if test constructs controller manually)**

If `InternalApiControllerTest.kt` constructs `InternalApiController(...)` directly (not via Spring autowiring), construct a `PhaseStopSignal` in the `@BeforeEach` (or shared setup) and inject it via the `scheduler` arg. Example patch:

```kotlin
private val stopSignal = PhaseStopSignal()

@BeforeEach
fun setup() {
    // ... existing setup ...
    val scheduler = ExternalApiScheduler(
        // ... existing args ...
        stopSignal = stopSignal,
    )
    controller = InternalApiController(
        // ... existing args ...
        scheduler = scheduler,
    )
}
```

If the test uses `@WebMvcTest` / `@SpringBootTest` and the controller is autowired, no change to setup is needed.

- [ ] **Step 2: Extend InternalApiControllerTest**

Append the following tests:

```kotlin
    @Test
    fun `POST stop phase returns 202 STOP_REQUESTED when phase is running`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")

        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = "airflow-corr-1",
        )

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val body = response.body!!
        assertEquals("STOP_REQUESTED", body["status"])
        assertEquals("ITEM_EQUIPMENT", body["phase"])
        assertEquals("run-1", body["runId"])
        assertEquals("airflow-corr-1", body["airflowRunId"])
        assertTrue(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
    }

    @Test
    fun `POST stop phase returns 200 NOT_RUNNING when slot empty`() {
        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals("NOT_RUNNING", body["status"])
        assertEquals("ITEM_EQUIPMENT", body["phase"])
    }

    @Test
    fun `POST stop phase returns 200 NOT_RUNNING when slot is terminal`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-old")
        runStatusTracker.completeRun(PipelinePhase.ITEM_EQUIPMENT, "run-old", 0, 0L)

        val response = controller.stopPhase(
            phaseName = "ITEM_EQUIPMENT",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("NOT_RUNNING", response.body!!["status"])
    }

    @Test
    fun `POST stop phase returns 400 INVALID_PHASE for unknown name`() {
        val response = controller.stopPhase(
            phaseName = "BOGUS",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("INVALID_PHASE", response.body!!["error"])
    }

    @Test
    fun `POST stop phase returns 400 INVALID_PHASE for non-triggerable phase`() {
        val response = controller.stopPhase(
            phaseName = "COMPLETED",
            airflowRunId = null,
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("INVALID_PHASE", response.body!!["error"])
    }

    @Test
    fun `POST stop phase on phase A does not affect phase B running`() {
        runStatusTracker.acquirePhaseSlot(PipelinePhase.ITEM_EQUIPMENT, "run-1")

        controller.stopPhase(phaseName = "OCID_LOOKUP", airflowRunId = null)

        // OCID_LOOKUP is not running → NOT_RUNNING, and ITEM_EQUIPMENT flag not set
        assertFalse(stopSignal.isStopRequested(PipelinePhase.ITEM_EQUIPMENT))
        assertFalse(stopSignal.isStopRequested(PipelinePhase.OCID_LOOKUP))
    }
```

Add imports to the test file as needed (the test setup likely uses `controller` and `runStatusTracker` already; if `stopSignal` is not yet wired into the test setup, add it).

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`

Expected: Compile error — `stopPhase` unresolved on controller.

- [ ] **Step 4: Add stopPhase endpoint to controller**

In `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt`, add the new method below `triggerPhase`:

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
            val runId = runStatusTracker.getPhaseStatus(phase)?.runId ?: ""
            log.info(
                "[InternalApi] stop requested phase={} runId={} airflowRunId={}",
                phase, runId, airflowRunId,
            )
            return ResponseEntity.accepted().body(mapOf(
                "status" to "STOP_REQUESTED",
                "phase" to phase.name,
                "runId" to runId,
                "airflowRunId" to (airflowRunId ?: ""),
            ))
        }
        val lastRunId = runStatusTracker.getLastCompletedForPhase(phase)?.runId ?: ""
        return ResponseEntity.ok().body(mapOf(
            "status" to "NOT_RUNNING",
            "phase" to phase.name,
            "runId" to lastRunId,
            "airflowRunId" to (airflowRunId ?: ""),
        ))
    }
```

Add the logger field to the controller (alongside other private fields, or as a top-level `private val log`):

```kotlin
private val log = LoggerFactory.getLogger(InternalApiController::class.java)
```

Add imports at top:

```kotlin
import org.slf4j.LoggerFactory
```

- [ ] **Step 5: Run all controller tests, verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.runstatus.InternalApiControllerTest"`

Expected: PASS (existing + 6 new tests).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt \
        module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt
git commit -m "feat(ext-api): POST /api/internal/stop/phase/{phaseName} endpoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 12: Final compile + full module test pass

**Files:** none (verification only)

- [ ] **Step 1: Compile everything**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full module test suite**

Run: `./gradlew :module-external-api:test`

Expected: BUILD SUCCESSFUL with all tests passing (existing + new).

- [ ] **Step 3: Verify no leftover `XXX` placeholders in production code**

```bash
grep -rn "XXX" module-external-api/src/main --include="*.kt"
```

Expected: No matches (or only matches in unrelated test fixtures).

- [ ] **Step 4: Push branch and prepare for review**

```bash
git push -u origin feature/issue-1290-stop-endpoint
```

Final report to user:
- Branch: `feature/issue-1290-stop-endpoint`
- Commit count
- File diff summary
- Test counts (existing + new)
