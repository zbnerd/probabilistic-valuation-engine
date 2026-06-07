# Issue #1057 Implementation Plan: BatchProgress + EndpointSinkFactory

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `BatchProgress` (shared batch state) and `EndpointSinkFactory` (one factory for all endpoint phases) to remove the duplicated batch-state vars in `OcidLookupPhase` and the duplicated 9-arg sink construction in `CharacterBasicFetchPhase` + `ItemEquipmentFetchPhase` + `RankingFetchPhase`.

**Architecture:** Two new classes (`BatchProgress` data class, `EndpointSinkFactory` `@Component`). Migrate `OcidLookupPhase.processBatchSuspend` and `BatchFetchSupport.processBatch` to use `BatchProgress`. Migrate all three endpoint phases to use `EndpointSinkFactory`. Remove `RankingSnapshotSinkFactory`.

**Tech Stack:** Kotlin 2.x, Spring Boot 3.x, coroutines, Mockito-Kotlin.

**Spec:** `docs/superpowers/specs/2026-06-07-1057-batch-progress-and-sink-factory-design.md`

**Issue:** #1057 (partially obsoleted by #986 — `SnapshotFetchPhase` no longer exists)

**Worktree:** create on `refactor/1057-batch-progress-sink-factory` branch

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchProgress.kt` | Immutable batch state data class |
| Create | `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt` | Factory for `ChunkedSnapshotSink` across all endpoints |
| Create | `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchProgressTest.kt` | Unit tests for `BatchProgress` |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt` | Use `BatchProgress` internally |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` | Use `BatchProgress` in `processBatchSuspend` |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` | Use `EndpointSinkFactory` |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` | Use `EndpointSinkFactory` |
| Modify | `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` | Use `EndpointSinkFactory` (drop `RankingSnapshotSinkFactory`) |
| Delete | `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt` | Replaced by `EndpointSinkFactory` |

No public API change. No new MQ topics. No new event factories. Concurrency model unchanged.

---

## Task 1: Create BatchProgress data class

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchProgress.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.externalapi.scheduler.phase

import java.time.Instant

/**
 * Immutable batch state shared by phase batch loops (OCID lookup, character-basic,
 * item-equipment). Accumulators are updated via [copy] producing a new instance.
 *
 * Use [shouldLogProgress] / [markLogged] to drive periodic progress logging without
 * leaking the [lastProgressLog] field through the loop body.
 */
data class BatchProgress(
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastProgressLog: Int = 0,
    val start: Instant = Instant.now(),
) {
    fun totalProcessed(): Int = successCount + failCount

    fun shouldLogProgress(logInterval: Int): Boolean =
        totalProcessed() - lastProgressLog >= logInterval

    fun markLogged(): BatchProgress = copy(lastProgressLog = totalProcessed())

    fun addSuccess(count: Int): BatchProgress = copy(successCount = successCount + count)

    fun addFailure(count: Int): BatchProgress = copy(failCount = failCount + count)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchProgress.kt
git commit -m "refactor(1057): add BatchProgress data class"
```

---

## Task 2: Add BatchProgress unit tests

**Files:**
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchProgressTest.kt`

- [ ] **Step 1: Write the tests**

```kotlin
package maple.externalapi.scheduler.phase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class BatchProgressTest {

    @Test
    fun `defaults to zero counters and current instant`() {
        val before = Instant.now()
        val progress = BatchProgress()
        val after = Instant.now()

        assertThat(progress.successCount).isZero()
        assertThat(progress.failCount).isZero()
        assertThat(progress.lastProgressLog).isZero()
        assertThat(progress.start).isBetween(before, after)
    }

    @Test
    fun `totalProcessed sums success and fail`() {
        val progress = BatchProgress(successCount = 7, failCount = 3)

        assertThat(progress.totalProcessed()).isEqualTo(10)
    }

    @Test
    fun `shouldLogProgress false when delta below interval`() {
        val progress = BatchProgress(successCount = 100, lastProgressLog = 0)

        assertThat(progress.shouldLogProgress(logInterval = 5_000)).isFalse()
    }

    @Test
    fun `shouldLogProgress true when delta hits interval`() {
        val progress = BatchProgress(successCount = 5_000, lastProgressLog = 0)

        assertThat(progress.shouldLogProgress(logInterval = 5_000)).isTrue()
    }

    @Test
    fun `markLogged updates lastProgressLog to current total`() {
        val progress = BatchProgress(successCount = 5_000, failCount = 0, lastProgressLog = 0)

        val marked = progress.markLogged()

        assertThat(marked.lastProgressLog).isEqualTo(5_000)
        assertThat(marked.successCount).isEqualTo(5_000)
    }

    @Test
    fun `addSuccess and addFailure produce new instance with updated counters`() {
        val progress = BatchProgress()

        val updated = progress.addSuccess(3).addFailure(1)

        assertThat(updated.successCount).isEqualTo(3)
        assertThat(updated.failCount).isEqualTo(1)
        assertThat(progress.successCount).isZero()  // original unchanged
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./gradlew :module-external-api:test --tests "*BatchProgressTest*" --no-daemon`
Expected: 6 tests, all pass.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/BatchProgressTest.kt
git commit -m "test(1057): add BatchProgress unit tests"
```

---

## Task 3: Create EndpointSinkFactory

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock

/**
 * Single factory for [ChunkedSnapshotSink] across all endpoint phases.
 * Owns [ObjectMapper], [SnapshotChunkingProperties], [SnapshotVolumeMetrics], and [Clock]
 * so callers do not have to thread them through their constructors.
 *
 * Replaces [RankingSnapshotSinkFactory] (now removed). Each endpoint has its own
 * publisher qualifier wired by Spring.
 */
@Component
class EndpointSinkFactory(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    @Qualifier("ocidLookupSnapshotPublisher")
    private val ocidLookupPublisher: SnapshotChunkEventPublisher,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createForCharacterBasic(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "character-basic", characterBasicPublisher)

    fun createForItemEquipment(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "item-equipment", characterBasicPublisher)

    fun createForRanking(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "ranking-overall", rankingPublisher)

    fun createForOcidMapping(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "ocid-mapping", ocidLookupPublisher)

    private fun build(
        runDir: Path,
        endpoint: String,
        publisher: SnapshotChunkEventPublisher,
    ): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SinkEventPublisher(publisher),
            volumeMetrics = volumeMetrics,
            clock = clock,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles (expected to fail: `ocidLookupSnapshotPublisher` qualifier and `OcidMapping` endpoint config may not exist)**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: FAIL with errors about missing `ocidLookupSnapshotPublisher` qualifier bean or `ocid-mapping` chunk config.

- [ ] **Step 3: Investigate the failures**

The OCID lookup phase does not currently use a `ChunkedSnapshotSink`; it uses `RankingSnapshotSinkFactory` for ranking and a custom gzip writer for OCID mapping. **Remove** `createForOcidMapping` from the factory for now — that path doesn't need a sink. Also confirm `ocidLookupSnapshotPublisher` is unused for sink construction.

Open `OcidLookupPhase` and confirm: it writes to a gzip JSONL file directly (`writeGzipJsonl`), not through `ChunkedSnapshotSink`. So `EndpointSinkFactory` should not need the `ocidLookupSnapshotPublisher` qualifier.

Update the file by removing the `createForOcidMapping` method and the `ocidLookupSnapshotPublisher` field:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock

@Component
class EndpointSinkFactory(
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    @Qualifier("rankingSnapshotPublisher")
    private val rankingPublisher: SnapshotChunkEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createForCharacterBasic(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "character-basic", characterBasicPublisher)

    fun createForItemEquipment(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "item-equipment", characterBasicPublisher)

    fun createForRanking(runDir: Path): ChunkedSnapshotSink =
        build(runDir, "ranking-overall", rankingPublisher)

    private fun build(
        runDir: Path,
        endpoint: String,
        publisher: SnapshotChunkEventPublisher,
    ): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = SinkEventPublisher(publisher),
            volumeMetrics = volumeMetrics,
            clock = clock,
        )
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt
git commit -m "refactor(1057): add EndpointSinkFactory"
```

---

## Task 4: Migrate CharacterBasicFetchPhase to EndpointSinkFactory

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`

- [ ] **Step 1: Update the file**

Replace the file contents with:

```kotlin
package maple.externalapi.scheduler.phase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * CHARACTER_BASIC snapshot fetch phase. Skips when stored keys already exist
 * (idempotent re-run safety — daily refresh should not double-fetch if a prior
 * run wrote chunks).
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class CharacterBasicFetchPhase(
    private val artifactStore: ExternalApiArtifactStorePort,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val batchSupport: BatchFetchSupport,
    private val sinkFactory: EndpointSinkFactory,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(CharacterBasicFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, ocidCache: Map<String, String>): CompletableFuture<Unit> {
        val existing = artifactStore.listStoredKeys(ExternalApiEndpoint.CHARACTER_BASIC)
        if (existing.isNotEmpty()) {
            log.info("[Scheduler] character-basic already done ({} files), skipping", existing.size)
            return CompletableFuture.completedFuture(Unit)
        }

        val entries = ocidCache.entries.toList()
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping character-basic")
            return CompletableFuture.completedFuture(Unit)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor("character-basic")
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = sinkFactory.createForCharacterBasic(runDir)

        val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== character-basic lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now(clock)
        val ctx = BatchFetchContext(
            endpoint = "character-basic",
            apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
            onFetched = { metrics.recordCharacterBasicFetched() },
            onFailed = { metrics.recordCharacterBasicFailed() },
        )

        val dispatcher = workerExecutor.asCoroutineDispatcher()
        return CoroutineScope(dispatcher).future {
            try {
                val (successCount, failCount) = batchSupport.processBatch(
                    rateLimiter, entries, batchSize, ctx, sink, runId, start,
                )
                SchedulerPhaseUtils.logSummary("character-basic", entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                metrics.characterBasicTimer().record(Duration.between(start, Instant.now(clock)))
            }
        }
    }
}
```

Notes:
- Removed: `objectMapper`, `volumeMetrics` fields (now owned by factory)
- Added: `sinkFactory: EndpointSinkFactory` field
- Changed: `ChunkedSnapshotSink(...)` 11-line construction → `sinkFactory.createForCharacterBasic(runDir)`
- `eventPublisher` field is kept because it is used elsewhere — check whether it is referenced in the body. If not used, drop it.

- [ ] **Step 2: Check `eventPublisher` usage in body**

Run: `grep -n "eventPublisher" module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt`
Expected: 0 references in the body. The field is unused in the method body.

- [ ] **Step 3: Remove the unused `eventPublisher` field**

In the constructor, remove the line:
```kotlin
    @Qualifier("characterBasicSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
```
and the `import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher` import.

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt
git commit -m "refactor(1057): migrate CharacterBasicFetchPhase to EndpointSinkFactory"
```

---

## Task 5: Migrate ItemEquipmentFetchPhase to EndpointSinkFactory

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`

- [ ] **Step 1: Update the file**

Replace the file contents with:

```kotlin
package maple.externalapi.scheduler.phase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.snapshot.EndpointSinkFactory
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * ITEM_EQUIPMENT snapshot fetch phase. Driven by ExternalApiScheduler's
 * continuous loop. No skipIfExisting guard — each cycle is expected to write
 * a fresh snapshot run.
 */
@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class ItemEquipmentFetchPhase(
    private val chunkingProperties: SnapshotChunkingProperties,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    private val batchSupport: BatchFetchSupport,
    private val sinkFactory: EndpointSinkFactory,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ItemEquipmentFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Unit> {
        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping item-equipment")
            return CompletableFuture.completedFuture(Unit)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor("item-equipment")
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = sinkFactory.createForItemEquipment(runDir)

        val rateLimiter = batchSupport.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== item-equipment lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now(clock)
        val ctx = BatchFetchContext(
            endpoint = "item-equipment",
            apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
            onFetched = { metrics.recordItemEquipmentFetched() },
            onFailed = { metrics.recordItemEquipmentFailed() },
        )

        val dispatcher = workerExecutor.asCoroutineDispatcher()
        return CoroutineScope(dispatcher).future {
            try {
                val (successCount, failCount) = batchSupport.processBatch(
                    rateLimiter, entries, batchSize, ctx, sink, runId, start,
                )
                SchedulerPhaseUtils.logSummary("item-equipment", entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                metrics.itemEquipmentTimer().record(Duration.between(start, Instant.now(clock)))
            }
        }
    }
}
```

- [ ] **Step 2: Check `eventPublisher` usage in body**

Run: `grep -n "eventPublisher" module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt`
Expected: 0 references. Remove the field + import if unused.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt
git commit -m "refactor(1057): migrate ItemEquipmentFetchPhase to EndpointSinkFactory"
```

---

## Task 6: Migrate RankingFetchPhase to EndpointSinkFactory and remove RankingSnapshotSinkFactory

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`

- [ ] **Step 1: Update RankingFetchPhase constructor**

Replace the import:
```kotlin
import maple.externalapi.snapshot.RankingSnapshotSinkFactory
```
with:
```kotlin
import maple.externalapi.snapshot.EndpointSinkFactory
```

Replace the field:
```kotlin
    private val sinkFactory: RankingSnapshotSinkFactory,
```
with:
```kotlin
    private val sinkFactory: EndpointSinkFactory,
```

Update the sink creation:
```kotlin
        val sink = sinkFactory.create(runDir, "ranking-overall")
```
to:
```kotlin
        val sink = sinkFactory.createForRanking(runDir)
```

- [ ] **Step 2: Update RankingFetchPhaseTest constructor**

In `RankingFetchPhaseTest.kt`:
- Replace the import `maple.externalapi.snapshot.RankingSnapshotSinkFactory` with `maple.externalapi.snapshot.EndpointSinkFactory`.
- Replace the `sinkFactory = RankingSnapshotSinkFactory(objectMapper = ..., chunkingProperties = ..., volumeMetrics = ..., rankingPublisher = ...)` construction with:

```kotlin
            sinkFactory = EndpointSinkFactory(
                objectMapper = objectMapper,
                chunkingProperties = SnapshotChunkingProperties(),
                volumeMetrics = SnapshotVolumeMetrics(registry),
                characterBasicPublisher = NoOpSnapshotChunkEventPublisher(),
                rankingPublisher = NoOpSnapshotChunkEventPublisher(),
            ),
```

- [ ] **Step 3: Delete RankingSnapshotSinkFactory**

```bash
git rm module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt
```

- [ ] **Step 4: Verify it compiles and tests pass**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava :module-external-api:test --tests "*RankingFetchPhaseTest*" --continue --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt \
        module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt
git commit -m "refactor(1057): migrate RankingFetchPhase to EndpointSinkFactory and drop RankingSnapshotSinkFactory"
```

---

## Task 7: Migrate OcidLookupPhase to BatchProgress

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`

- [ ] **Step 1: Replace `processBatchSuspend` body**

Find the `processBatchSuspend` method (around lines 110-148). Replace the method body — keep the signature unchanged:

```kotlin
    private suspend fun processBatchSuspend(
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        results: MutableList<String>,
    ): Pair<Int, Int> {
        var processed = 0
        var progress = BatchProgress(start = Instant.now(clock))

        while (processed < igns.size) {
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, batchSize, igns.size - processed)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val chunk = igns.subList(processed, processed + permits)
            val batchResults = coroutineScope {
                chunk.map { ign ->
                    async {
                        runCatching { fetchOcid(ign) }.getOrNull()
                    }
                }.awaitAll()
            }

            val batchSuccess = batchResults.filterNotNull()
            results.addAll(batchSuccess)
            progress = progress
                .addSuccess(batchSuccess.size)
                .addFailure(chunk.size - batchSuccess.size)
            processed += permits

            if (progress.shouldLogProgress(PROGRESS_LOG_INTERVAL)) {
                progress = progress.markLogged()
                SchedulerPhaseUtils.logProgress("OCID lookup", progress.totalProcessed(), igns.size, progress.successCount, progress.failCount, progress.start)
            }
        }
        return progress.successCount to progress.failCount
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "refactor(1057): migrate OcidLookupPhase.processBatchSuspend to BatchProgress"
```

---

## Task 8: Migrate BatchFetchSupport to BatchProgress

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt`

- [ ] **Step 1: Replace `processBatch` accumulator vars**

In `BatchFetchSupport.processBatch` (lines 70-124), replace the accumulator block at the top of the function:
```kotlin
        var processed = 0
        var successCount = 0
        var failCount = 0
        var lastProgressLog = 0
```
with:
```kotlin
        var processed = 0
        var progress = BatchProgress(start = start)
```

Replace the post-chunk block:
```kotlin
            val batchSuccess = batchResults.filterNotNull().size
            successCount += batchSuccess
            failCount += chunk.size - batchSuccess
            processed += permits

            val progress = successCount + failCount
            if (progress - lastProgressLog >= PROGRESS_LOG_INTERVAL) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress(ctx.endpoint, progress, entries.size, successCount, failCount, start)
            }
```
with:
```kotlin
            val batchSuccess = batchResults.filterNotNull().size
            progress = progress
                .addSuccess(batchSuccess)
                .addFailure(chunk.size - batchSuccess)
            processed += permits

            if (progress.shouldLogProgress(PROGRESS_LOG_INTERVAL)) {
                progress = progress.markLogged()
                SchedulerPhaseUtils.logProgress(ctx.endpoint, progress.totalProcessed(), entries.size, progress.successCount, progress.failCount, progress.start)
            }
```

Replace the return:
```kotlin
        return successCount to failCount
```
with:
```kotlin
        return progress.successCount to progress.failCount
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt
git commit -m "refactor(1057): migrate BatchFetchSupport.processBatch to BatchProgress"
```

---

## Task 9: Full verification

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run full test suite**

Run: `./gradlew test --no-daemon`
Expected: `BUILD SUCCESSFUL`, no failing tests, no `UnexpectedRollbackException`.

- [ ] **Step 3: Verify line counts**

Run: `wc -l module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/{OcidLookupPhase,BatchFetchSupport,CharacterBasicFetchPhase,ItemEquipmentFetchPhase,RankingFetchPhase,BatchProgress}.kt module-external-api/src/main/kotlin/maple/externalapi/snapshot/{ChunkedSnapshotSink,EndpointSinkFactory}.kt`
Expected: OcidLookupPhase ~180, BatchFetchSupport ~165, phases reduced, BatchProgress ~30, EndpointSinkFactory ~50.

- [ ] **Step 4: Verify RankingSnapshotSinkFactory is gone**

Run: `ls module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt 2>&1`
Expected: file not found (deleted in Task 6).

- [ ] **Step 5: Final commit (only if any incidental fix was needed)**

```bash
git status
# If there are incidental changes:
# git add -A && git commit -m "chore(1057): post-verification cleanup"
```

---

## Self-Review Checklist

- [x] `BatchProgress` data class created (Task 1)
- [x] `BatchProgress` unit tests added (Task 2)
- [x] `EndpointSinkFactory` created (Task 3) — handles 3 endpoints
- [x] `CharacterBasicFetchPhase` migrated to factory (Task 4)
- [x] `ItemEquipmentFetchPhase` migrated to factory (Task 5)
- [x] `RankingFetchPhase` migrated to factory + `RankingSnapshotSinkFactory` removed (Task 6)
- [x] `OcidLookupPhase.processBatchSuspend` uses `BatchProgress` (Task 7)
- [x] `BatchFetchSupport.processBatch` uses `BatchProgress` (Task 8)
- [x] All 9 tasks verify with compile + commit
- [x] No `TBD` / `TODO` / "implement later"
- [x] No "add appropriate error handling" vague steps
- [x] All `@Transactional` semantics preserved
- [x] Test code shown for every test step
- [x] Exact file paths, exact commands, exact expected output throughout
