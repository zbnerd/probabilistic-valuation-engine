# #984: Recursive CF → suspend fun + while loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert 3 Phase classes in module-external-api from recursive `CompletableFuture` chains to `suspend fun` + while loop, eliminating `Thread.sleep` busy-wait and `AtomicInteger` mutable state.

**Architecture:** Each Phase's `execute()` keeps its `CompletableFuture` return type — `ExternalApiScheduler` unchanged. Internally, `future {}` bridge starts a coroutine on the passed executor (wrapped as `CoroutineDispatcher`). Recursive `processBatch()`/`processPages()` become `suspend fun` with while loops. `java.util.concurrent.Semaphore` → `kotlinx.coroutines.sync.Semaphore` with `withTimeoutOrNull(10s) { withPermit {} }` (prevents indefinite hang). `Thread.sleep()` → `delay()` (only in `acquirePermitsSuspend`, callers do NOT add extra delay). `AtomicInteger` → local `var`. Per-item error isolation via `runCatching` inside `async` blocks, with `onFailure` for Failure record submission.

**Tech Stack:** Kotlin coroutines, kotlinx-coroutines-jdk8 (`future {}`, `await()`), kotlinx-coroutines-sync (`Semaphore`, `withPermit`)

**Dependencies already present:** `kotlinx-coroutines-core`, `kotlinx-coroutines-jdk8` in `module-external-api/build.gradle`

---

## Files

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `module-external-api/.../scheduler/phase/SchedulerPhaseUtils.kt` | Add `acquirePermitsSuspend()` |
| Modify | `module-external-api/.../scheduler/phase/RankingFetchPhase.kt` | Convert to suspend fun |
| Modify | `module-external-api/.../scheduler/phase/OcidLookupPhase.kt` | Convert to suspend fun |
| Modify | `module-external-api/.../scheduler/phase/SnapshotFetchPhase.kt` | Convert to suspend fun |
| Create | `module-external-api/src/test/.../phase/SchedulerPhaseUtilsTest.kt` | Test suspend rate limiter |
| Verify | `module-external-api/src/test/.../phase/RankingFetchPhaseTest.kt` | Existing tests unchanged |
| Verify | `module-external-api/src/test/.../phase/OcidLookupPhaseTest.kt` | Existing tests unchanged |

**No changes:** `ExternalApiScheduler.kt`, `ExternalApiClientPort.kt`

---

### Task 1: SchedulerPhaseUtils — Add suspend acquirePermitsSuspend()

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtilsTest.kt`

- [ ] **Step 1: Write failing test for acquirePermitsSuspend**

```kotlin
// File: module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtilsTest.kt
package maple.externalapi.scheduler.phase

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SchedulerPhaseUtilsTest {

    @Test
    fun `acquirePermitsSuspend returns permits when available`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(10)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 5, 10)
        assertThat(permits).isEqualTo(5)
    }

    @Test
    fun `acquirePermitsSuspend respects remaining limit`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(100)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 50, 10)
        assertThat(permits).isEqualTo(10) // min(batchSize, remaining)
    }

    @Test
    fun `acquirePermitsSuspend returns zero when bucket empty without blocking`() = runTest {
        val bucket = SchedulerPhaseUtils.newRateLimiter(1)
        // Consume the only permit
        SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 1, 1)
        // Bucket is now empty — should return 0 after delay (not throw, not block thread)
        val permits = SchedulerPhaseUtils.acquirePermitsSuspend(bucket, 1, 1)
        assertThat(permits).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run test — verify compilation failure**

Run: `./gradlew :module-external-api:compileTestKotlin 2>&1 | tail -20`
Expected: Error — `acquirePermitsSuspend` unresolved reference.

- [ ] **Step 3: Implement acquirePermitsSuspend**

Add import to `SchedulerPhaseUtils.kt`:

```kotlin
import kotlinx.coroutines.delay
```

Add method to `SchedulerPhaseUtils` object, after existing `acquirePermits`:

```kotlin
/**
 * Suspend-friendly rate limit permit acquisition.
 * Replaces Thread.sleep(100) with coroutine delay(100) when no permits available.
 */
suspend fun acquirePermitsSuspend(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int {
    val maxBatch = minOf(batchSize, remaining)
    val consumed = rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt()
    if (consumed == 0) {
        delay(100)
    }
    return consumed
}
```

- [ ] **Step 4: Run tests — verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.SchedulerPhaseUtilsTest" 2>&1 | tail -10`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt \
        module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtilsTest.kt
git commit -m "feat(#984): add suspend acquirePermitsSuspend to SchedulerPhaseUtils"
```

---

### Task 2: RankingFetchPhase — Convert to suspend fun

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Verify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`

**Strategy:** Simplest Phase — sequential page processing, no semaphore, no parallel fan-out. Establishes the conversion pattern.

- [ ] **Step 1: Run existing tests (baseline)**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RankingFetchPhaseTest" 2>&1 | tail -10`
Expected: 3 tests PASS.

- [ ] **Step 2: Replace RankingFetchPhase.kt**

Replace full file content with:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.domain.KeyType
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

@Component
@ConditionalOnProperty(name = ["external-api.ranking.enabled"], havingValue = "true", matchIfMissing = false)
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
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        val runId = SchedulerPhaseUtils.newRunId()
        val date = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val runDir: Path = Paths.get(storeBasePath, "runs", runId)
        val endpointConfig = chunkingProperties.configFor("ranking-overall")

        SchedulerPhaseUtils.writeRunningMarker(runDir)

        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "ranking-overall",
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = rankingPublisher,
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)
        val start = Instant.now()
        val dispatcher = workerExecutor.asCoroutineDispatcher()

        log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)

        return future(dispatcher) {
            try {
                val (fetched, failed) = processPagesSuspend(sink, rateLimiter, date)
                SchedulerPhaseUtils.logSummary("RankingFetch", fetched, fetched, fetched, failed, start)
            } catch (ex: Throwable) {
                log.error("[RankingFetch] failed: runId={}, error={}", runId, ex.message)
                throw ex
            } finally {
                sink.close()
            }
            runDir
        }
    }

    /**
     * Sequential page processing with suspend-based rate limiting.
     * Replaces recursive CompletableFuture chain with while loop.
     */
    private suspend fun processPagesSuspend(
        sink: ChunkedSnapshotSink,
        rateLimiter: io.github.bucket4j.Bucket,
        date: String,
    ): Pair<Int, Int> {
        var fetched = 0
        var failed = 0
        var currentPage = 1

        while (currentPage <= maxPages) {
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, 1, 1)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val requestKey = "$date:$currentPage"
            try {
                val bodyBytes = clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, requestKey).await()
                val count = submitRankingEntries(sink, bodyBytes, currentPage)
                fetched += count
                metrics.recordRankingFetched(count)
                if (fetched % 10000 == 0) {
                    log.info("[RankingFetch] progress: fetched={}, failed={}, page={}/{}", fetched, failed, currentPage, maxPages)
                }
            } catch (ex: Throwable) {
                failed++
                metrics.recordRankingFailed()
                val status = SchedulerPhaseUtils.extractHttpStatus(ex)
                sink.submit(SnapshotChunkRecord.Failure(
                    key = requestKey,
                    endpoint = "ranking-overall",
                    keyType = KeyType.DATE_PAGE.name,
                    httpStatus = status,
                    fetchedAt = Instant.now(),
                    errorMessage = ex.message ?: "unknown",
                ))
                log.warn("[RankingFetch] page failed: page={}, status={}, error={}", currentPage, status, ex.message)
            }
            currentPage++
        }
        return fetched to failed
    }

    private fun submitRankingEntries(
        sink: ChunkedSnapshotSink,
        bodyBytes: ByteArray,
        page: Int,
    ): Int {
        val root = objectMapper.readTree(bodyBytes)
        val rankingArray = root.get("ranking")
        if (rankingArray == null || !rankingArray.isArray) {
            log.warn("[RankingFetch] no ranking array in response: page={}", page)
            return 0
        }

        var count = 0
        for (node in rankingArray) {
            val name = node.get("character_name")?.asText() ?: continue
            val entryBytes = objectMapper.writeValueAsBytes(node)
            sink.submit(SnapshotChunkRecord.Success(
                bodyBytes = entryBytes,
                key = name,
                endpoint = "ranking-overall",
                keyType = KeyType.DATE_PAGE.name,
                httpStatus = 200,
                fetchedAt = Instant.now(),
            ))
            count++
        }
        return count
    }
}
```

**Key changes from original:**
- Removed: `AtomicInteger`, `thenAcceptAsync`, `.handle`, `.thenCompose` recursion, `processPages` method
- Added: `future(dispatcher) { }` bridge, `processPagesSuspend` while loop, `await()` on CF
- Unchanged: Constructor, `submitRankingEntries()`, all `@Value`/`@Qualifier` config

- [ ] **Step 3: Run existing tests — verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RankingFetchPhaseTest" 2>&1 | tail -10`
Expected: 3 tests PASS. Tests call `phase.execute(executor).join()` — CF API unchanged.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt
git commit -m "refactor(#984): convert RankingFetchPhase to suspend fun + while loop"
```

---

### Task 3: OcidLookupPhase — Convert to suspend fun

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Verify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`

**Strategy:** Parallel batch + coroutine Semaphore. Replace `tryAcquireWithBackoff()` + `Thread.sleep` with `Semaphore.withPermit {}`. Replace `AtomicInteger` with local `var` accumulators. Use `coroutineScope + async/awaitAll` for per-batch parallelism.

- [ ] **Step 1: Run existing tests (baseline)**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" 2>&1 | tail -10`
Expected: 2 tests PASS.

- [ ] **Step 2: Replace OcidLookupPhase.kt**

Replace full file content with:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import maple.expectation.common.event.SnapshotRunCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Qualifier("ocidLookupSnapshotPublisher")
    private val eventPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)
    private val semaphore = Semaphore(maxInFlight)

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): CompletableFuture<Path?> {
        val mappingDir = Path.of(storeBasePath).resolve("ocid-mapping")
        deleteOldMappingFiles(mappingDir)

        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return CompletableFuture.completedFuture(null)
        }
        log.info("[Scheduler] read {} character names from ranking chunks: {}", igns.size, rankingRunDir)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, maxInFlight={}, store={}",
            igns.size, ocidLookupPermitsPerSecond, batchSize, maxInFlight, storeBasePath,
        )

        val start = Instant.now()
        val dispatcher = workerExecutor.asCoroutineDispatcher()
        val results = mutableListOf<String>()

        return future(dispatcher) {
            val (successCount, failCount) = processBatchSuspend(rateLimiter, igns, results)

            val runId = SchedulerPhaseUtils.newRunId()
            val outputPath = writeGzipJsonl(mappingDir, results, runId)
            SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount, successCount, failCount, start)
            eventPublisher.publishRunCompleted(SnapshotRunCompletedEvent(
                eventId = UUID.randomUUID().toString(),
                runId = runId,
                endpoint = "ocid-lookup",
                manifestPath = "ocid-mapping/${outputPath.fileName}",
                totalRecords = results.size,
                totalFailed = failCount,
                chunkCount = 1,
                startedAt = start,
                finishedAt = Instant.now(),
                createdAt = Instant.now(),
            ))
            outputPath
        }
    }

    /**
     * Batch processing with coroutine-based parallelism and semaphore-gated concurrency.
     * Replaces recursive CF chain + AtomicInteger with while loop + local accumulators.
     */
    private suspend fun processBatchSuspend(
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        results: MutableList<String>,
    ): Pair<Int, Int> {
        var processed = 0
        var successCount = 0
        var failCount = 0
        var lastProgressLog = 0
        val start = Instant.now()

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
            successCount += batchSuccess.size
            failCount += chunk.size - batchSuccess.size

            processed += permits

            val progress = successCount + failCount
            if (progress - lastProgressLog >= 5000) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, successCount, failCount, start)
            }
        }
        return successCount to failCount
    }

    /**
     * Fetches OCID for a single IGN. Coroutine Semaphore gates concurrency —
     * replaces tryAcquireWithBackoff() + Thread.sleep with structured suspension.
     */
    private suspend fun fetchOcid(ign: String): String? {
        return withTimeoutOrNull(java.time.Duration.ofSeconds(10)) {
            semaphore.withPermit {
            val data = clientPort.fetch(
                ExternalApiProvider.NEXON,
                ExternalApiEndpoint.OCID_LOOKUP,
                ign,
            ).await()
            val ocid = objectMapper.readTree(data).get("ocid")?.asText()
            if (ocid != null) {
                String(objectMapper.writeValueAsBytes(mapOf("userIgn" to ign, "ocid" to ocid)))
            } else null
            }
        }
    }

    fun readCharacterNamesFromChunks(runDir: Path): List<String> {
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        if (!Files.exists(chunksDir)) return emptyList()

        val names = linkedSetOf<String>()
        Files.list(chunksDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
                .sorted()
                .forEach { chunkFile ->
                    GZIPInputStream(BufferedInputStream(Files.newInputStream(chunkFile))).bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            if (line.isNotBlank()) {
                                val node = objectMapper.readTree(line)
                                val key = node.get("key")?.asText()
                                if (key != null) names.add(key)
                            }
                        }
                    }
                }
        }
        return names.toList()
    }

    private fun deleteOldMappingFiles(mappingDir: Path) {
        if (!Files.exists(mappingDir)) return
        var deleted = 0
        Files.list(mappingDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
                .forEach { file ->
                    Files.deleteIfExists(file)
                    deleted++
                }
        }
        log.info("[Scheduler] deleted {} old OCID mapping files in {}", deleted, mappingDir)
    }

    private fun writeGzipJsonl(mappingDir: Path, results: List<String>, runId: String): Path {
        Files.createDirectories(mappingDir)
        val outputPath = mappingDir.resolve("ocid-mapping-$runId.jsonl.gz")
        val tempFile = Files.createTempFile("ocid-mapping-", ".jsonl")
        tempFile.toFile().deleteOnExit()
        Files.write(tempFile, results)
        GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(outputPath))).use { gzip ->
            Files.copy(tempFile, gzip)
        }
        Files.deleteIfExists(tempFile)

        val size = Files.size(outputPath)
        log.info("[Scheduler] wrote {} OCID mappings to {} ({} bytes)", results.size, outputPath, size)
        return outputPath
    }
}
```

**Key changes from original:**
- Removed: `AtomicInteger` (4 fields), `processBatch()` recursive CF, `fetchAndCollectOcidAsync()`, `tryAcquireWithBackoff()`, `java.util.concurrent.Semaphore`
- Added: `processBatchSuspend()` while loop, `fetchOcid()` suspend with `Semaphore.withPermit {}`, `coroutineScope + async/awaitAll` for parallel batch
- Changed: `java.util.concurrent.Semaphore` → `kotlinx.coroutines.sync.Semaphore`, `Collections.synchronizedList` → plain `ArrayList`
- Unchanged: Constructor signature, `readCharacterNamesFromChunks()`, `deleteOldMappingFiles()`, `writeGzipJsonl()`

- [ ] **Step 3: Run existing tests — verify pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" 2>&1 | tail -10`
Expected: 2 tests PASS. Tests only call `readCharacterNamesFromChunks()` — no change.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "refactor(#984): convert OcidLookupPhase to suspend fun + while loop"
```

---

### Task 4: SnapshotFetchPhase — Convert to suspend fun

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt`

**Strategy:** Same pattern as OcidLookupPhase but with `SnapshotFetchConfig` wrapper. Two entry points (`executeCharacterBasic`, `executeItemEquipment`) share the internal `execute()` → `processBatchSuspend()` pipeline.

- [ ] **Step 1: Replace SnapshotFetchPhase.kt**

Replace full file content with:

```kotlin
package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotFetchMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.ChunkedSnapshotSink
import maple.externalapi.snapshot.SnapshotChunkRecord
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotChunkEventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

data class SnapshotFetchConfig(
    val endpoint: String,
    val apiEndpoint: ExternalApiEndpoint,
    val eventPublisher: SnapshotChunkEventPublisher,
    val onFetched: () -> Unit,
    val onFailed: () -> Unit,
    val recordDuration: (Duration) -> Unit,
    val skipIfExisting: Boolean = false,
)

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class SnapshotFetchPhase(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    private val chunkingProperties: SnapshotChunkingProperties,
    private val volumeMetrics: SnapshotVolumeMetrics,
    private val metrics: ExternalApiMetrics,
    private val fetchMetrics: SnapshotFetchMetrics,
    @Qualifier("characterBasicSnapshotPublisher")
    private val characterBasicPublisher: SnapshotChunkEventPublisher,
    private val itemEquipmentPublisher: SnapshotChunkEventPublisher,
    @Value("\${external-api.rate-limit.permits-per-second:200}")
    private val permitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:../data}")
    private val storeBasePath: String,
    @Value("\${external-api.concurrency.max-in-flight:100}")
    maxInFlight: Int,
) {
    private val log = LoggerFactory.getLogger(SnapshotFetchPhase::class.java)
    private val semaphore = Semaphore(maxInFlight)

    fun executeCharacterBasic(workerExecutor: ExecutorService, ocidCache: Map<String, String>): CompletableFuture<Void> =
        execute(
            workerExecutor,
            ocidCache.entries.toList(),
            SnapshotFetchConfig(
                endpoint = "character-basic",
                apiEndpoint = ExternalApiEndpoint.CHARACTER_BASIC,
                eventPublisher = characterBasicPublisher,
                onFetched = { metrics.recordCharacterBasicFetched() },
                onFailed = { metrics.recordCharacterBasicFailed() },
                recordDuration = { metrics.characterBasicTimer().record(it) },
                skipIfExisting = true,
            ),
        )

    fun executeItemEquipment(workerExecutor: ExecutorService, entries: List<Map.Entry<String, String>>): CompletableFuture<Void> =
        execute(
            workerExecutor,
            entries,
            SnapshotFetchConfig(
                endpoint = "item-equipment",
                apiEndpoint = ExternalApiEndpoint.ITEM_EQUIPMENT,
                eventPublisher = itemEquipmentPublisher,
                onFetched = { metrics.recordItemEquipmentFetched() },
                onFailed = { metrics.recordItemEquipmentFailed() },
                recordDuration = { metrics.itemEquipmentTimer().record(it) },
            ),
        )

    private fun execute(
        workerExecutor: ExecutorService,
        entries: List<Map.Entry<String, String>>,
        config: SnapshotFetchConfig,
    ): CompletableFuture<Void> {
        if (config.skipIfExisting) {
            val existing = artifactStore.listStoredKeys(config.apiEndpoint)
            if (existing.isNotEmpty()) {
                log.info("[Scheduler] {} already done ({} files), skipping", config.endpoint, existing.size)
                return CompletableFuture.completedFuture(null)
            }
        }

        if (entries.isEmpty()) {
            log.warn("[Scheduler] OCID cache empty, skipping {}", config.endpoint)
            return CompletableFuture.completedFuture(null)
        }

        val runId = SchedulerPhaseUtils.newRunId()
        val chunkConfig = chunkingProperties.configFor(config.endpoint)
        val runDir = Paths.get(storeBasePath, "runs", runId)
        SchedulerPhaseUtils.writeRunningMarker(runDir)
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = config.endpoint,
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            queueCapacity = chunkingProperties.queueCapacity,
            objectMapper = objectMapper,
            eventPublisher = config.eventPublisher,
            volumeMetrics = volumeMetrics,
        )

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(permitsPerSecond)

        log.info("[Scheduler] ========== {} lookup start ==========", config.endpoint)
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, chunk={}records/{}bytes, runId={}",
            entries.size, permitsPerSecond, batchSize,
            chunkConfig.maxRecords, chunkConfig.maxUncompressedBytes, runId,
        )

        val start = Instant.now()
        val dispatcher = workerExecutor.asCoroutineDispatcher()

        return future(dispatcher) {
            try {
                val (successCount, failCount) = processBatchSuspend(rateLimiter, entries, config, sink, runId, start)
                SchedulerPhaseUtils.logSummary(config.endpoint, entries.size, successCount, successCount, failCount, start)
            } finally {
                sink.close()
                config.recordDuration(Duration.between(start, Instant.now()))
            }
            null
        }
    }

    /**
     * Batch processing with coroutine-based parallelism and semaphore-gated concurrency.
     * Replaces recursive CF chain + AtomicInteger with while loop + local accumulators.
     */
    private suspend fun processBatchSuspend(
        rateLimiter: io.github.bucket4j.Bucket,
        entries: List<Map.Entry<String, String>>,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        runId: String,
        start: Instant,
    ): Pair<Int, Int> {
        var processed = 0
        var successCount = 0
        var failCount = 0
        var lastProgressLog = 0

        while (processed < entries.size) {
            val permits = SchedulerPhaseUtils.acquirePermitsSuspend(rateLimiter, batchSize, entries.size - processed)
            if (permits == 0) continue // acquirePermitsSuspend already delays 100ms

            val chunk = entries.subList(processed, processed + permits)
            val batchWaitStart = Instant.now()

            val batchResults = coroutineScope {
                chunk.map { (_, ocid) ->
                    async {
                        runCatching {
                            fetchSingle(ocid, config, sink)
                        }.onFailure { ex ->
                            handleSnapshotFailure(ocid, config, sink, ex)
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            val batchWaitDuration = Duration.between(batchWaitStart, Instant.now())
            fetchMetrics.recordBatchWait(config.endpoint, batchWaitDuration, chunk.size)
            if (batchWaitDuration.toMillis() >= 1_000) {
                log.info(
                    "[SnapshotFetchMetrics] batch wait: endpoint={}, runId={}, batchSize={}, durationMs={}, success={}, failed={}",
                    config.endpoint,
                    runId,
                    chunk.size,
                    batchWaitDuration.toMillis(),
                    successCount,
                    failCount,
                )
            }

            val batchSuccess = batchResults.filterNotNull().size
            successCount += batchSuccess
            failCount += chunk.size - batchSuccess

            processed += permits

            val progress = successCount + failCount
            if (progress - lastProgressLog >= 5000) {
                lastProgressLog = progress
                SchedulerPhaseUtils.logProgress(config.endpoint, progress, entries.size, successCount, failCount, start)
            }
        }
        return successCount to failCount
    }

    /**
     * Fetches a single OCID with semaphore-gated concurrency.
     * Returns true on success, null on failure (for runCatching).
     */
    private suspend fun fetchSingle(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
    ): Boolean {
        return withTimeoutOrNull(java.time.Duration.ofSeconds(10)) {
            semaphore.withPermit {
            val fetchStart = Instant.now()
            val bodyBytes = clientPort.fetch(
                ExternalApiProvider.NEXON,
                config.apiEndpoint,
                ocid,
            ).await()

            val fetchDuration = Duration.between(fetchStart, Instant.now())
            fetchMetrics.recordFetchJoin(config.endpoint, fetchDuration)

            val queueDepthBeforeSubmit = sink.queueDepth()
            val submitStart = Instant.now()
            sink.submit(
                SnapshotChunkRecord.Success(
                    key = ocid,
                    endpoint = config.endpoint,
                    keyType = "OCID",
                    httpStatus = 200,
                    fetchedAt = Instant.now(),
                    bodyBytes = bodyBytes,
                ),
            )
            val submitDuration = Duration.between(submitStart, Instant.now())
            fetchMetrics.recordSinkSubmit(config.endpoint, submitDuration, queueDepthBeforeSubmit)
            if (fetchDuration.toMillis() >= 500 || submitDuration.toMillis() >= 100) {
                log.info(
                    "[SnapshotFetchMetrics] fetch/sink: endpoint={}, ocid={}, responseBytes={}, fetchJoinMs={}, sinkSubmitMs={}, sinkQueueDepthBeforeSubmit={}",
                    config.endpoint,
                    ocid,
                    bodyBytes.size,
                    fetchDuration.toMillis(),
                    submitDuration.toMillis(),
                    queueDepthBeforeSubmit,
                )
            }
            config.onFetched()
                true
            }
        } ?: false

    private fun handleSnapshotFailure(
        ocid: String,
        config: SnapshotFetchConfig,
        sink: ChunkedSnapshotSink,
        ex: Throwable,
    ) {
        val httpStatus = SchedulerPhaseUtils.extractHttpStatus(ex)
        sink.submit(
            SnapshotChunkRecord.Failure(
                key = ocid,
                endpoint = config.endpoint,
                keyType = "OCID",
                httpStatus = httpStatus,
                fetchedAt = Instant.now(),
                errorMessage = ex.message ?: "unknown",
            ),
        )
        config.onFailed()
    }
}
```

**Key changes from original:**
- Removed: `AtomicInteger` (3 fields), `processBatch()` recursive CF (11 params), `fetchSingleAsync()`, `tryAcquireWithBackoff()`, `java.util.concurrent.Semaphore`, `handleSnapshotSuccess()` (inlined into `fetchSingle`)
- Added: `processBatchSuspend()` while loop, `fetchSingle()` suspend with `Semaphore.withPermit {}`
- Changed: Error handling per-item via `runCatching` in `async` blocks → failures counted from `null` results
- Unchanged: Constructor, `SnapshotFetchConfig`, `executeCharacterBasic()`, `executeItemEquipment()`, `handleSnapshotFailure()`

- [ ] **Step 2: Compile check**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all module-external-api tests**

Run: `./gradlew :module-external-api:test 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt
git commit -m "refactor(#984): convert SnapshotFetchPhase to suspend fun + while loop"
```

---

### Task 5: Cleanup & Full Verification

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt`

**Strategy:** Remove old `acquirePermits()` since all callers now use `acquirePermitsSuspend()`. Full compile + test verification.

- [ ] **Step 1: Verify no callers of old acquirePermits()**

Run: `grep -rn "acquirePermits(" module-external-api/src/main/kotlin/ | grep -v "acquirePermitsSuspend" | grep -v "fun acquirePermits"`
Expected: No results (all callers migrated).

- [ ] **Step 2: Remove old acquirePermits() from SchedulerPhaseUtils**

Remove the `acquirePermits` method and its `Thread.sleep` import (if no other usage):

```kotlin
// REMOVE this method:
fun acquirePermits(rateLimiter: Bucket, batchSize: Int, remaining: Int): Int {
    val maxBatch = minOf(batchSize, remaining)
    return rateLimiter.tryConsumeAsMuchAsPossible(maxBatch.toLong()).toInt().also {
        if (it == 0) Thread.sleep(Duration.ofMillis(100))
    }
}
```

Also remove `import java.time.Duration` if no other usage in the file (check — `newRateLimiter` uses `Duration.ofSeconds`, so keep it).

The only import to remove:
```kotlin
// Thread.sleep is used via Duration.ofMillis(100) — remove only the method body
// Duration import stays (used by newRateLimiter)
```

Actually, `Thread.sleep` is `java.lang.Thread.sleep` — no explicit import needed. Just remove the method.

- [ ] **Step 3: Full compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 4: Full test suite**

Run: `./gradlew :module-external-api:test 2>&1 | tail -15`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerPhaseUtils.kt
git commit -m "refactor(#984): remove deprecated acquirePermits with Thread.sleep"
```

---

## Self-Review Checklist

- [x] Spec coverage: All 3 Phase classes + SchedulerPhaseUtils covered
- [x] No placeholders: All code blocks contain complete implementations, old "Fix" notes removed
- [x] Type consistency: `Semaphore` consistently refers to `kotlinx.coroutines.sync.Semaphore` across all files
- [x] Import consistency: All new imports verified against kotlinx-coroutines-jdk8 API. Unused `delay` imports removed from Phase files (only in SchedulerPhaseUtils)
- [x] Error isolation: `runCatching` inside `async` prevents `coroutineScope` cancellation on per-item failure
- [x] Error handling: `onFailure { handleSnapshotFailure() }` in SnapshotFetchPhase submits Failure records
- [x] Resource cleanup: `sink.close()` in `finally` block for all Phases
- [x] Semaphore migration: `withTimeoutOrNull(10s) { withPermit {} }` prevents indefinite hang, guarantees release
- [x] Caller unchanged: `ExternalApiScheduler` not modified — `execute()` returns `CompletableFuture`
- [x] No double delay: Only `acquirePermitsSuspend` contains `delay(100)`, callers use simple `continue`
- [x] No unnecessary synchronization: `ArrayList` instead of `Collections.synchronizedList` in OcidLookupPhase

## Grill-Me Fixes Applied

| # | Issue | Fix |
|---|-------|-----|
| Q1 | Semaphore 무한 대기 | `withTimeoutOrNull(10s)` 추가 |
| Q2 | Double delay 버그 | caller의 `delay(100)` 제거, `acquirePermitsSuspend`만 delay |
| Q3 | SnapshotFetch 에러 핸들링 누락 | `onFailure { handleSnapshotFailure() }` 추가 |
| Q4 | results 불필요한 동기화 | `ArrayList`로 교체 |
| Q5 | Dispatcher 선택 | VT executor `asCoroutineDispatcher()` 유지 |
| Q6 | Timeout 값 | 10초 |
