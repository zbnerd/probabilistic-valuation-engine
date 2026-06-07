# SnapshotSinkEventPublisher Extraction Implementation Plan (#987)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move event construction, volume metrics, and the `snapshotVolume` log line from `ChunkedSnapshotSink` into a new stateless `SnapshotSinkEventPublisher`. Sink delegates only.

**Architecture:** New plain class in `maple.externalapi.snapshot` (NOT a `@Component` — must be constructed per-endpoint to preserve per-endpoint `SinkEventPublisher` / Kafka routing). Takes `SinkEventPublisher` (existing fire-and-forget wrapper) + `SnapshotVolumeMetrics` + `Clock`. Three methods: `publishChunkReady(stats, runId, endpoint)`, `publishRunCompleted(manifest, endpoint)`, `publishRunFailed(manifest, endpoint, errorMessage)`. The three construction sites (`RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`) build the new publisher inline (replacing the `SinkEventPublisher` wrap and the direct `volumeMetrics` pass-through to the sink). No behavior change.

**Tech Stack:** Kotlin, Spring `@Component`, JUnit 5, AssertJ, Mockito-Kotlin, Jackson, Java `Clock`.

**Branch:** `refactor/987-snapshot-sink-event-publisher` off `develop`. PR base: `develop`.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-external-api/.../snapshot/SnapshotSinkEventPublisher.kt` | CREATE | Event DTO construction + volume metrics + snapshotVolume log + dispatch |
| `module-external-api/.../snapshot/ChunkedSnapshotSink.kt` | MODIFY | Drop `publishChunkReady`/`publishRunCompleted`/`publishRunFailed`/`objectKeyFor`; replace with 3 delegation calls; remove `volumeMetrics` ctor param |
| `module-external-api/.../snapshot/RankingSnapshotSinkFactory.kt` | MODIFY | Inject `SnapshotSinkEventPublisher`; drop manual `SinkEventPublisher` build and `volumeMetrics` injection |
| `module-external-api/.../scheduler/phase/CharacterBasicFetchPhase.kt` | MODIFY | Inject `SnapshotSinkEventPublisher`; drop `volumeMetrics` + `SinkEventPublisher` from sink ctor |
| `module-external-api/.../scheduler/phase/ItemEquipmentFetchPhase.kt` | MODIFY | Same as CharacterBasicFetchPhase |
| `module-external-api/.../snapshot/SnapshotSinkEventPublisherTest.kt` | CREATE | Unit tests for the 3 publish methods + volume-metrics side-effect |

All Kotlin files in `module-external-api/src/{main,test}/kotlin/maple/externalapi/snapshot/` (or `scheduler/phase/` for the two phase files).

---

## Task 1: Create branch off develop (already done in worktree)

**Files:** none

- [x] **Step 1.1: Verify worktree and branch**

Run from `/.worktrees/issue-987`:
```bash
git -C /home/maple/probabilistic-valuation-engine/.worktrees/issue-987 branch --show-current
```
Expected: `refactor/987-snapshot-sink-event-publisher`

---

## Task 2: Create SnapshotSinkEventPublisher with publishChunkReady

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt`

- [ ] **Step 2.1: Create the file with publishChunkReady only**

```kotlin
package maple.externalapi.snapshot

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.util.CompressionUtils
import maple.externalapi.metrics.SnapshotVolumeMetrics
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Owns snapshot-sink event DTO construction, volume-metrics recording, and
 * the `snapshotVolume` log line. Delegates the actual send to [SinkEventPublisher].
 *
 * Plain class (not a Spring bean) — each phase/factory constructs one with its
 * own [SinkEventPublisher] so per-endpoint Kafka routing is preserved. Every
 * method takes the call-site context (runId, endpoint, manifest) and returns
 * nothing.
 */
class SnapshotSinkEventPublisher(
    private val eventPublisher: SinkEventPublisher,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(SnapshotSinkEventPublisher::class.java)

    /**
     * Build [SnapshotChunkReadyEvent] for a finished chunk, record its size in
     * [volumeMetrics], emit the `snapshotVolume` log line, and dispatch.
     */
    fun publishChunkReady(stats: ChunkStats, runId: String, endpoint: String) {
        val chunkId = String.format("part-%06d", stats.partIndex)
        val ratio = CompressionUtils.ratioString(stats.uncompressedBytes, stats.compressedBytes)
        volumeMetrics.recordChunk(stats.compressedBytes, stats.uncompressedBytes, stats.recordCount.toLong())
        log.info(
            "[snapshotVolume] runId={} chunkId={} compressedBytes={} uncompressedBytes={} jsonRows={} compressionRatio={}",
            runId, chunkId, stats.compressedBytes, stats.uncompressedBytes, stats.recordCount, ratio,
        )

        val event = SnapshotChunkReadyEvent(
            eventId = UUID.randomUUID().toString(),
            runId = runId,
            endpoint = endpoint,
            chunkId = chunkId,
            objectKey = "runs/$runId/$endpoint/${stats.path}",
            recordCount = stats.recordCount,
            uncompressedBytes = stats.uncompressedBytes,
            compressedBytes = stats.compressedBytes,
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishChunkReady(event)
    }
}
```

- [ ] **Step 2.2: Compile main code**

Run from worktree root:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: SUCCESS. `SnapshotSinkEventPublisher` compiles with `publishChunkReady` only.

- [ ] **Step 2.3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt
git commit -m "feat(external-api): add SnapshotSinkEventPublisher.publishChunkReady (#987)"
```

---

## Task 3: Add publishRunCompleted and publishRunFailed to SnapshotSinkEventPublisher

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt`

- [ ] **Step 3.1: Append the two new methods**

Add these imports at the top of the file (after the existing imports):
```kotlin
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
```

Add the closing `}` of the class on its own line, then insert these methods before it (after the existing `publishChunkReady`):

```kotlin
    /**
     * Build [SnapshotRunCompletedEvent] from a finalized manifest and dispatch.
     * Caller must have set `manifest.finishedAt` before invoking.
     */
    fun publishRunCompleted(manifest: SnapshotChunkManifest, endpoint: String) {
        val event = SnapshotRunCompletedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            manifestPath = "runs/${manifest.runId}/$endpoint/manifest.json",
            totalRecords = manifest.totalRecords,
            totalFailed = manifest.totalFailed,
            chunkCount = manifest.chunks.size,
            startedAt = manifest.startedAt,
            finishedAt = requireNotNull(manifest.finishedAt),
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishRunCompleted(event)
    }

    /**
     * Build [SnapshotRunFailedEvent] carrying the writer-thread error message and dispatch.
     */
    fun publishRunFailed(manifest: SnapshotChunkManifest, endpoint: String, errorMessage: String) {
        val event = SnapshotRunFailedEvent(
            eventId = UUID.randomUUID().toString(),
            runId = manifest.runId,
            endpoint = endpoint,
            errorMessage = errorMessage,
            createdAt = java.time.Instant.now(clock),
        )
        eventPublisher.publishRunFailed(event)
    }
```

- [ ] **Step 3.2: Compile**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: SUCCESS.

- [ ] **Step 3.3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt
git commit -m "feat(external-api): add publishRunCompleted + publishRunFailed to SnapshotSinkEventPublisher (#987)"
```

---

## Task 4: Write failing test for publishChunkReady

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt`

- [ ] **Step 4.1: Create the test file with the first test**

```kotlin
package maple.externalapi.snapshot

import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.metrics.SnapshotVolumeMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

class SnapshotSinkEventPublisherTest {

    private val sinkEventPublisher = mock<SinkEventPublisher>()
    private val volumeMetrics = mock<SnapshotVolumeMetrics>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-07T10:00:00Z"), ZoneOffset.UTC)

    private val publisher = SnapshotSinkEventPublisher(
        eventPublisher = sinkEventPublisher,
        volumeMetrics = volumeMetrics,
        clock = fixedClock,
    )

    @Test
    fun `publishChunkReady builds event with chunkId objectKey and createdAt`() {
        whenever(sinkEventPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))

        val stats = ChunkStats(
            path = "chunks/part-000001.jsonl.gz",
            partIndex = 1,
            recordCount = 42,
            uncompressedBytes = 1000L,
            compressedBytes = 250L,
            startedAt = Instant.parse("2026-06-07T09:50:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:55:00Z"),
        )

        publisher.publishChunkReady(stats, runId = "run-1", endpoint = "result")

        val captor = argumentCaptor<SnapshotChunkReadyEvent>()
        verify(sinkEventPublisher).publishChunkReady(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-1")
        assertThat(event.endpoint).isEqualTo("result")
        assertThat(event.chunkId).isEqualTo("part-000001")
        assertThat(event.objectKey).isEqualTo("runs/run-1/result/chunks/part-000001.jsonl.gz")
        assertThat(event.recordCount).isEqualTo(42)
        assertThat(event.uncompressedBytes).isEqualTo(1000L)
        assertThat(event.compressedBytes).isEqualTo(250L)
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
    }

    @Test
    fun `publishChunkReady records volume metrics with compressed uncompressed and count`() {
        whenever(sinkEventPublisher.publishChunkReady(any())).thenReturn(CompletableFuture.completedFuture(null))

        val stats = ChunkStats(
            path = "chunks/part-000007.jsonl.gz",
            partIndex = 7,
            recordCount = 99,
            uncompressedBytes = 4096L,
            compressedBytes = 1024L,
            startedAt = Instant.parse("2026-06-07T09:00:00Z"),
            finishedAt = Instant.parse("2026-06-07T09:10:00Z"),
        )

        publisher.publishChunkReady(stats, "run-2", "item")

        verify(volumeMetrics).recordChunk(1024L, 4096L, 99L)
    }
}
```

- [ ] **Step 4.2: Run the test — expect partial success (publishRunCompleted/RunFailed not yet covered)**

The test for `publishChunkReady` will pass, but the test class is incomplete. Run:

```bash
./gradlew :module-external-api:test --tests 'maple.externalapi.snapshot.SnapshotSinkEventPublisherTest'
```
Expected: 2 tests pass.

- [ ] **Step 4.3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt
git commit -m "test(external-api): SnapshotSinkEventPublisher.publishChunkReady unit tests (#987)"
```

---

## Task 5: Add failing tests for publishRunCompleted and publishRunFailed

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt`

- [ ] **Step 5.1: Append two more test methods to the class**

Add these imports at the top of the test file (alongside the existing imports):
```kotlin
import org.mockito.kotlin.verifyNoInteractions
```

Append these tests after the existing `publishChunkReady records volume metrics ...` test:

```kotlin
    @Test
    fun `publishRunCompleted builds event from manifest with manifestPath and counts`() {
        whenever(sinkEventPublisher.publishRunCompleted(any())).thenReturn(CompletableFuture.completedFuture(null))

        val manifest = SnapshotChunkManifest(
            runId = "run-3",
            endpoint = "result",
            startedAt = Instant.parse("2026-06-07T08:00:00Z"),
        ).apply {
            totalRecords = 123
            totalFailed = 4
            finishedAt = Instant.parse("2026-06-07T09:00:00Z")
            chunks.add(
                ChunkEntry(
                    path = "chunks/part-000001.jsonl.gz",
                    recordCount = 123,
                    uncompressedBytes = 4096L,
                    compressedBytes = 1024L,
                    startedAt = Instant.parse("2026-06-07T08:30:00Z"),
                    finishedAt = Instant.parse("2026-06-07T08:35:00Z"),
                ),
            )
        }

        publisher.publishRunCompleted(manifest, endpoint = "result")

        val captor = argumentCaptor<SnapshotRunCompletedEvent>()
        verify(sinkEventPublisher).publishRunCompleted(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-3")
        assertThat(event.endpoint).isEqualTo("result")
        assertThat(event.manifestPath).isEqualTo("runs/run-3/result/manifest.json")
        assertThat(event.totalRecords).isEqualTo(123)
        assertThat(event.totalFailed).isEqualTo(4)
        assertThat(event.chunkCount).isEqualTo(1)
        assertThat(event.startedAt).isEqualTo(Instant.parse("2026-06-07T08:00:00Z"))
        assertThat(event.finishedAt).isEqualTo(Instant.parse("2026-06-07T09:00:00Z"))
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
        verifyNoInteractions(volumeMetrics)
    }

    @Test
    fun `publishRunFailed builds event with error message and dispatches`() {
        whenever(sinkEventPublisher.publishRunFailed(any())).thenReturn(CompletableFuture.completedFuture(null))

        val manifest = SnapshotChunkManifest(
            runId = "run-4",
            endpoint = "item",
            startedAt = Instant.parse("2026-06-07T07:00:00Z"),
        )

        publisher.publishRunFailed(manifest, endpoint = "item", errorMessage = "writer thread died")

        val captor = argumentCaptor<SnapshotRunFailedEvent>()
        verify(sinkEventPublisher).publishRunFailed(captor.capture())
        val event = captor.firstValue
        assertThat(event.runId).isEqualTo("run-4")
        assertThat(event.endpoint).isEqualTo("item")
        assertThat(event.errorMessage).isEqualTo("writer thread died")
        assertThat(event.createdAt).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"))
        assertThat(event.eventId).isNotBlank()
        verifyNoInteractions(volumeMetrics)
    }
```

- [ ] **Step 5.2: Run the test class**

Run:
```bash
./gradlew :module-external-api:test --tests 'maple.externalapi.snapshot.SnapshotSinkEventPublisherTest'
```
Expected: 4 tests pass.

- [ ] **Step 5.3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/test/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisherTest.kt
git commit -m "test(external-api): SnapshotSinkEventPublisher publishRunCompleted + publishRunFailed tests (#987)"
```

---

## Task 6: Refactor ChunkedSnapshotSink to delegate

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt`

- [ ] **Step 6.1: Update imports**

Remove these imports from `ChunkedSnapshotSink.kt`:
```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.CompressionUtils
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.expectation.common.event.SnapshotRunFailedEvent
import maple.externalapi.metrics.SnapshotVolumeMetrics
import java.util.UUID
```

Add this import:
```kotlin
import maple.externalapi.snapshot.SnapshotSinkEventPublisher
```

`ObjectMapper` and `CompressionUtils` are no longer needed in this file. `SnapshotVolumeMetrics` is no longer needed. The 3 event DTOs are no longer needed here. `UUID` is no longer needed.

- [ ] **Step 6.2: Update the constructor**

Replace the existing `class ChunkedSnapshotSink(...)` declaration (lines 23-36) with:

```kotlin
class ChunkedSnapshotSink(
    private val runDir: Path,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val queueCapacity: Int,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: SnapshotSinkEventPublisher,
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-writer-$endpoint").unstarted(runnable)
    },
    private val clock: Clock = Clock.systemUTC(),
) {
```

Changes:
- Removed `volumeMetrics: SnapshotVolumeMetrics` constructor parameter
- Renamed `eventPublisher: SinkEventPublisher` to `eventPublisher: SnapshotSinkEventPublisher` and changed the type

Note: the `objectMapper` parameter is kept because the sink still constructs `SnapshotFailedRecordWriter` and `SnapshotChunkManifestWriter` which need it. (Don't remove `objectMapper`!)

- [ ] **Step 6.3: Delete the 4 private methods and 1 helper**

Delete the following methods from `ChunkedSnapshotSink.kt`:
- `private fun objectKeyFor(stats: ChunkStats): String` (lines 225-226)
- `private fun publishChunkReady(stats: ChunkStats)` (lines 228-249)
- `private fun publishRunCompleted()` (lines 251-265)
- `private fun publishRunFailed(errorMessage: String)` (lines 267-276)

After deletion, the sink class ends after `private fun newChunkWriter(...)`.

- [ ] **Step 6.4: Update the 4 call sites in `close()` and `rotateChunk()` and `closeCurrentChunk()`**

In `close()` (around lines 110-141), the call sites are:
- `publishRunFailed(err.message ?: "unknown")` → `eventPublisher.publishRunFailed(manifest, endpoint, err.message ?: "unknown")`
- `publishRunCompleted()` → `eventPublisher.publishRunCompleted(manifest, endpoint)`

In `rotateChunk()` (around line 196):
- `publishChunkReady(stats)` → `eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)`

In `closeCurrentChunk()` (around line 213):
- `publishChunkReady(stats)` → `eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)`

- [ ] **Step 6.5: Compile main code (expect 3 sites to fail)**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: FAIL — the 3 construction sites still pass the old `SinkEventPublisher` + `volumeMetrics` to the sink. Compilation error message will list the unsatisfied parameters in `RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`.

- [ ] **Step 6.6: Commit the sink refactor**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "refactor(external-api): ChunkedSnapshotSink delegates event publishing to SnapshotSinkEventPublisher (#987)"
```

---

## Task 7: Update RankingSnapshotSinkFactory

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt`

- [ ] **Step 7.1: Replace the file**

Replace the entire `RankingSnapshotSinkFactory.kt` with:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builds [ChunkedSnapshotSink] instances for the ranking phase. Owns the
 * `ObjectMapper` reference so [maple.externalapi.scheduler.phase.RankingFetchPhase]
 * can stay free of direct Jackson imports.
 */
@Component
class RankingSnapshotSinkFactory(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
) {
    fun create(runDir: Path, endpoint: String): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        val sinkEventPublisher = SnapshotSinkEventPublisher(
            eventPublisher = SinkEventPublisher(rankingPublisher),
            volumeMetrics = volumeMetrics,
        )
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = sinkEventPublisher,
        )
    }
}
```

Changes:
- `volumeMetrics` injection kept (now passed to the new publisher, not the sink)
- The `SinkEventPublisher(rankingPublisher)` wrap is moved inside `create()` so the new publisher wraps it
- The sink no longer takes `volumeMetrics` — it gets `eventPublisher = sinkEventPublisher` instead

- [ ] **Step 7.2: Compile**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: still FAIL — 2 phase files (CharacterBasicFetchPhase, ItemEquipmentFetchPhase) still need updating.

- [ ] **Step 7.3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt
git commit -m "refactor(external-api): RankingSnapshotSinkFactory injects SnapshotSinkEventPublisher (#987)"
```

---

## Task 8: Update CharacterBasicFetchPhase

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`

- [ ] **Step 8.1: Update imports**

Remove:
```kotlin
import maple.externalapi.snapshot.SinkEventPublisher
```

Keep:
```kotlin
import maple.externalapi.metrics.SnapshotVolumeMetrics
```

- [ ] **Step 8.2: Keep the existing constructor parameters**

`CharacterBasicFetchPhase` keeps `volumeMetrics: SnapshotVolumeMetrics` and the raw `eventPublisher: SnapshotChunkEventPublisher` (port). Both are still needed — they get composed into a per-run `SnapshotSinkEventPublisher` inside `run()`.

- [ ] **Step 8.3: Update the sink construction site**

In the `run()` method, find the `ChunkedSnapshotSink(...)` call (around line 72). Replace the `eventPublisher = SinkEventPublisher(eventPublisher),` and `volumeMetrics = volumeMetrics,` lines with construction of a per-run `SnapshotSinkEventPublisher`:

```kotlin
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(eventPublisher),
                volumeMetrics = volumeMetrics,
            ),
```

The sink no longer takes `volumeMetrics` directly; that goes into the new publisher. The new `eventPublisher` is a `SnapshotSinkEventPublisher`.

- [ ] **Step 8.4: Compile**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: still FAIL — `ItemEquipmentFetchPhase` still needs updating.

- [ ] **Step 8.5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt
git commit -m "refactor(external-api): CharacterBasicFetchPhase injects SnapshotSinkEventPublisher (#987)"
```

---

## Task 9: Update ItemEquipmentFetchPhase

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`

- [ ] **Step 9.1: Update imports**

Remove:
```kotlin
import maple.externalapi.snapshot.SinkEventPublisher
```

Keep:
```kotlin
import maple.externalapi.metrics.SnapshotVolumeMetrics
```

- [ ] **Step 9.2: Keep the existing constructor parameters**

Same as Task 8.2 — keep `volumeMetrics` and the raw `eventPublisher` port.

- [ ] **Step 9.3: Update the sink construction site**

Apply the same changes as Task 8.3. The `ChunkedSnapshotSink(...)` call is around line 61.

- [ ] **Step 9.4: Compile — should now pass**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue
```
Expected: SUCCESS. All 3 construction sites now wire `SnapshotSinkEventPublisher`.

- [ ] **Step 9.5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt
git commit -m "refactor(external-api): ItemEquipmentFetchPhase injects SnapshotSinkEventPublisher (#987)"
```

---

## Task 10: Update existing tests for the new wiring

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt` (and any other test that constructs `ChunkedSnapshotSink` or the phase classes directly)

- [ ] **Step 10.1: Find affected tests**

Run:
```bash
grep -rn "SnapshotVolumeMetrics\|SinkEventPublisher" /home/maple/probabilistic-valuation-engine/.worktrees/issue-987/module-external-api/src/test --include='*.kt'
```
Expected: at least one test file references these (e.g., `RankingFetchPhaseTest.kt` line 56 constructs `SnapshotVolumeMetrics(registry)`).

- [ ] **Step 10.2: For each affected test**

Replace any direct `SnapshotVolumeMetrics(registry)` construction with `mock<SnapshotVolumeMetrics>()`. Replace any `SinkEventPublisher(mock)` usage with `SnapshotSinkEventPublisher(mock, mock<SnapshotVolumeMetrics>(), Clock.fixed(...))` or with a `mock<SnapshotSinkEventPublisher>()`.

- [ ] **Step 10.3: Compile and run all external-api tests**

Run:
```bash
./gradlew :module-external-api:compileTestKotlin :module-external-api:compileTestJava :module-external-api:test --continue
```
Expected: SUCCESS. All tests pass.

- [ ] **Step 10.4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git add module-external-api/src/test/kotlin
git commit -m "test(external-api): update phase tests for SnapshotSinkEventPublisher wiring (#987)"
```

---

## Task 11: Final verification + close #987

- [ ] **Step 11.1: Compile + test final pass**

Run:
```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava :module-external-api:compileTestKotlin :module-external-api:compileTestJava :module-external-api:test --continue
```
Expected: SUCCESS.

- [ ] **Step 11.2: Line count check**

Run:
```bash
wc -l /home/maple/probabilistic-valuation-engine/.worktrees/issue-987/module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
```
Expected: 245 or fewer (down from 277).

- [ ] **Step 11.3: Verify no leftover references**

Run:
```bash
grep -rn "publishChunkReady\|publishRunCompleted\|publishRunFailed\|objectKeyFor" /home/maple/probabilistic-valuation-engine/.worktrees/issue-987/module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
```
Expected: no output.

- [ ] **Step 11.4: Push branch and open PR**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-987
git push -u origin refactor/987-snapshot-sink-event-publisher
gh pr create --base develop --head refactor/987-snapshot-sink-event-publisher \
  --title "refactor(external-api): #987 extract SnapshotSinkEventPublisher from ChunkedSnapshotSink" \
  --body "Moves event DTO construction, volume metrics, and the snapshotVolume log line from ChunkedSnapshotSink into a new SnapshotSinkEventPublisher class. The sink now delegates to 3 publish methods. No behavior change — same event payloads, timing, and log lines.

Verification:
- compileKotlin + compileJava + test pass
- ChunkedSnapshotSink: 277 → ~245 lines (-32)
- 3 publish methods removed from sink
- 4 new unit tests on SnapshotSinkEventPublisher"
```

- [ ] **Step 11.5: Close #987 once PR is merged**

After PR is merged into develop:
```bash
gh issue close 987 --comment "Resolved by extracting event publishing into SnapshotSinkEventPublisher. PR merged."
```

---

## Self-Review

**Spec coverage:**
- ✅ `SnapshotSinkEventPublisher` new class — Task 2 + 3
- ✅ `publishChunkReady()` extracted — Task 2
- ✅ `publishRunCompleted()` extracted — Task 3
- ✅ `publishRunFailed()` extracted — Task 3
- ✅ `ChunkedSnapshotSink` delegates only — Task 6
- ✅ No behavior change — same event payload construction, same call timing (rotate/close/cleanup)
- ✅ Compile + test pass — Tasks 6-10
- ✅ Test for new class — Tasks 4-5
- ✅ Line reduction — Task 11.2 verifies

**Placeholder scan:** No TBD/TODO. Every step has actual code or exact commands.

**Type consistency:** `SnapshotSinkEventPublisher` defined in Task 2 with `publishChunkReady`. Task 3 adds `publishRunCompleted` and `publishRunFailed`. Task 4-5 tests use these same signatures. Task 6 sink update uses the same signatures. All consistent.

**Site coverage:** All 3 construction sites (`RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`) are updated in Tasks 7-9.
