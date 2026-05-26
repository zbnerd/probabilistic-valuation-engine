# OCID Lookup from Ranking Gzip Chunks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change OcidLookupPhase input source from CSV file to ranking gzip JSONL chunks. Remove CSV dependency entirely. OCID lookup runs daily (no skip guard), deleting prior OCID files before each run.

**Architecture:** RankingFetchPhase returns its runDir. OcidLookupPhase reads character_names from gzip JSONL chunks in that runDir, deletes existing OCID files, then fetches OCID for all characters. ranking enabled=true is mandatory. Scheduler wires runDir via CF chain.

**Tech Stack:** Kotlin, Jackson ObjectMapper, java.util.zip.GZIPInputStream, CompletableFuture, JUnit 5 + Mockito

---

### Task 1: RankingFetchPhase returns runDir, remove CSV

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`

- [ ] **Step 1: Update RankingFetchPhase — return Path, remove csvPath + writeCsv**

Remove `csvPath` constructor parameter, `characterNames` accumulator, and `writeCsv()` method. Return `CompletableFuture<Path>`:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.concurrent.atomic.AtomicInteger

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
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Path> {
        val runId = SchedulerPhaseUtils.newRunId()
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
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
        val fetched = AtomicInteger(0)
        val failed = AtomicInteger(0)

        log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)
        val start = Instant.now()

        return processPages(workerExecutor, sink, rateLimiter, date, 1, fetched, failed)
            .whenComplete { _, ex ->
                sink.close()
                if (ex != null) {
                    log.error("[RankingFetch] failed: runId={}, fetched={}, failed={}", runId, fetched.get(), failed.get(), ex)
                } else {
                    SchedulerPhaseUtils.logSummary("RankingFetch", fetched.get(), fetched.get(), fetched.get(), failed.get(), start)
                }
            }
            .thenApply { runDir }
    }

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

        SchedulerPhaseUtils.acquirePermits(rateLimiter, 1, 1)

        val requestKey = "$date:$currentPage"
        return clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, requestKey)
            .thenAcceptAsync({ bodyBytes ->
                val count = submitRankingEntries(sink, bodyBytes, currentPage)
                fetched.addAndGet(count)
                metrics.recordRankingFetched(count)
                if (fetched.get() % 10000 == 0) {
                    log.info("[RankingFetch] progress: fetched={}, failed={}, page={}/{}", fetched.get(), failed.get(), currentPage, maxPages)
                }
            }, workerExecutor)
            .handle { _, ex ->
                if (ex != null) {
                    failed.incrementAndGet()
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
                null
            }
            .thenCompose { processPages(workerExecutor, sink, rateLimiter, date, currentPage + 1, fetched, failed) }
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

- [ ] **Step 2: Update RankingFetchPhaseTest — remove CSV assertions, remove csvPath**

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.metrics.ExternalApiMetrics
import maple.externalapi.metrics.SnapshotVolumeMetrics
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class RankingFetchPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var objectMapper: ObjectMapper
    private lateinit var phase: RankingFetchPhase
    private lateinit var executor: java.util.concurrent.ExecutorService

    @BeforeEach
    fun setUp() {
        clientPort = mock()
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val storeBasePath = tempDir.resolve("store").toString()

        val registry = SimpleMeterRegistry()
        phase = RankingFetchPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            chunkingProperties = SnapshotChunkingProperties(),
            volumeMetrics = SnapshotVolumeMetrics(registry),
            metrics = ExternalApiMetrics(registry),
            rankingPublisher = NoOpSnapshotChunkEventPublisher(),
            maxPages = 3,
            permitsPerSecond = 100,
            storeBasePath = storeBasePath,
        )
        executor = Executors.newVirtualThreadPerTaskExecutor()
    }

    @AfterEach
    fun tearDown() {
        executor.close()
    }

    private fun rankingJson(vararg names: String): ByteArray {
        val entries = names.mapIndexed { i, name ->
            """{"ranking":${i + 1},"character_name":"$name","world_name":"크로아","class_name":"전사"}"""
        }.joinToString(",", prefix = """{"ranking":[""", postfix = "]}")
        return entries.toByteArray()
    }

    @Test
    fun `execute returns runDir and creates gzip chunks`() {
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerA", "PlayerB")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerC", "PlayerD")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerE")))

        val resultPath = phase.execute(executor).join()

        assertThat(resultPath).isNotNull
        assertThat(resultPath.toString()).contains("runs")

        val gzFiles = Files.walk(tempDir.resolve("store").resolve("runs"))
            .filter { it.toString().endsWith(".gz") }.toList()
        assertThat(gzFiles).isNotEmpty

        val successMarkers = Files.walk(tempDir.resolve("store").resolve("runs"))
            .filter { it.fileName.toString() == "_SUCCESS" }.toList()
        assertThat(successMarkers).hasSize(1)
    }

    @Test
    fun `execute continues on page failure`() {
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerA")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.failedFuture(RuntimeException("API error")))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerC")))

        val resultPath = phase.execute(executor).join()

        assertThat(resultPath).isNotNull
        val gzFiles = Files.walk(tempDir.resolve("store").resolve("runs"))
            .filter { it.toString().endsWith(".gz") }.toList()
        assertThat(gzFiles).isNotEmpty
    }

    @Test
    fun `execute skips entries without character_name`() {
        val json = """{"ranking":[{"ranking":1,"character_name":"ValidName","world_name":"크로아"},{"ranking":2,"world_name":"크로아"}]}""".toByteArray()
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
            .thenReturn(CompletableFuture.completedFuture(json))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
            .thenReturn(CompletableFuture.completedFuture("""{"ranking":[]}""".toByteArray()))
        whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
            .thenReturn(CompletableFuture.completedFuture("""{"ranking":[]}""".toByteArray()))

        val resultPath = phase.execute(executor).join()

        assertThat(resultPath).isNotNull
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RankingFetchPhaseTest"`
Expected: All 3 tests PASS

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt
git commit -m "refactor(external-api): RankingFetchPhase returns runDir, remove CSV"
```

---

### Task 2: Rewrite OcidLookupPhase — gzip input, no skip guard, pre-delete

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`

- [ ] **Step 1: Write test for readCharacterNamesFromChunks**

Create `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class OcidLookupPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var objectMapper: ObjectMapper
    private lateinit var phase: OcidLookupPhase

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        phase = OcidLookupPhase(
            clientPort = mock(),
            artifactStore = mock(),
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 400,
            batchSize = 1000,
            storeBasePath = tempDir.resolve("store").toString(),
        )
    }

    private fun writeGzipJsonl(chunkFilePath: Path, keys: List<String>) {
        Files.createDirectories(chunkFilePath.parent)
        val fos = FileOutputStream(chunkFilePath.toFile())
        val gzip = GZIPOutputStream(BufferedOutputStream(fos))
        for (key in keys) {
            val line = """{"endpoint":"ranking-overall","keyType":"DATE_PAGE","key":"$key","status":"SUCCESS","httpStatus":200,"fetchedAt":"2026-05-20T02:00:00Z","body":{"character_name":"$key"}}"""
            gzip.write((line + "\n").toByteArray())
        }
        gzip.finish()
        fos.close()
    }

    @Test
    fun `readCharacterNamesFromChunks extracts distinct keys from gzip JSONL`() {
        val chunksDir = tempDir.resolve("runs").resolve("20260520-030000-123").resolve("ranking-overall").resolve("chunks")
        writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerA", "PlayerB", "PlayerC"))
        writeGzipJsonl(chunksDir.resolve("part-000002.jsonl.gz"), listOf("PlayerC", "PlayerD"))

        val names = phase.readCharacterNamesFromChunks(tempDir.resolve("runs").resolve("20260520-030000-123"))

        assertThat(names).containsExactlyInAnyOrder("PlayerA", "PlayerB", "PlayerC", "PlayerD")
    }

    @Test
    fun `readCharacterNamesFromChunks returns empty list when no chunk files`() {
        val runDir = tempDir.resolve("runs").resolve("empty-run")

        val names = phase.readCharacterNamesFromChunks(runDir)

        assertThat(names).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest"`
Expected: FAIL — `readCharacterNamesFromChunks` not defined, constructor mismatch

- [ ] **Step 3: Rewrite OcidLookupPhase**

Replace entire `OcidLookupPhase.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

@Component
@ConditionalOnProperty(name = ["external-api.schedule.enabled"], havingValue = "true")
class OcidLookupPhase(
    private val clientPort: ExternalApiClientPort,
    private val artifactStore: ExternalApiArtifactStorePort,
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.rate-limit.ocid-lookup-permits-per-second:400}")
    private val ocidLookupPermitsPerSecond: Int,
    @Value("\${external-api.batch-size:1000}")
    private val batchSize: Int,
    @Value("\${external-api.store.base-path:/data/external-api}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): CompletableFuture<Void> {
        val deleted = artifactStore.deleteAll(ExternalApiEndpoint.OCID_LOOKUP)
        log.info("[Scheduler] deleted {} existing OCID files", deleted)

        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return CompletableFuture.completedFuture(null)
        }
        log.info("[Scheduler] read {} character names from ranking chunks: {}", igns.size, rankingRunDir)

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info(
            "[Scheduler] config: total={}, rate={}/s, batchSize={}, store={}",
            igns.size, ocidLookupPermitsPerSecond, batchSize, storeBasePath,
        )

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val storedCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)

        return processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            igns = igns,
            processed = 0,
            successCount = successCount,
            failCount = failCount,
            storedCount = storedCount,
            lastProgressLog = lastProgressLog,
            start = start,
        ).whenComplete { _, _ ->
            SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount.get(), storedCount.get(), failCount.get(), start)
        }
    }

    fun readCharacterNamesFromChunks(runDir: Path): List<String> {
        val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
        if (!Files.exists(chunksDir)) return emptyList()

        val names = linkedSetOf<String>()
        Files.list(chunksDir)
            .filter { it.toString().endsWith(".jsonl.gz") }
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
        return names.toList()
    }

    private fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        processed: Int,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        storedCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        start: Instant,
    ): CompletableFuture<Void> {
        if (processed >= igns.size) {
            return CompletableFuture.completedFuture(null)
        }

        val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - processed)
        if (permits == 0) {
            return processBatch(workerExecutor, rateLimiter, igns, processed, successCount, failCount, storedCount, lastProgressLog, start)
        }

        val chunk = igns.subList(processed, processed + permits)
        val futures = chunk.map { ign ->
            fetchAndStoreOcidAsync(ign, workerExecutor, successCount, failCount, storedCount)
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenCompose {
            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog.get() >= 5000) {
                lastProgressLog.set(progress)
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, storedCount.get(), failCount.get(), start)
            }
            processBatch(workerExecutor, rateLimiter, igns, processed + permits, successCount, failCount, storedCount, lastProgressLog, start)
        }
    }

    private fun fetchAndStoreOcidAsync(
        ign: String,
        workerExecutor: ExecutorService,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        storedCount: AtomicInteger,
    ): CompletableFuture<Void> =
        clientPort.fetch(
            ExternalApiProvider.NEXON,
            ExternalApiEndpoint.OCID_LOOKUP,
            ign,
        )
            .thenAcceptAsync({ data ->
                artifactStore.store(ExternalApiEndpoint.OCID_LOOKUP, ign, data)
                successCount.incrementAndGet()
                storedCount.incrementAndGet()
            }, workerExecutor)
            .handle { _, ex ->
                if (ex != null) failCount.incrementAndGet()
                null
            }
}
```

Key changes from original:
- Removed `UserIgnCsvReader` dependency
- Removed skip guard (always runs)
- Added `artifactStore.deleteAll()` at start of each run
- `rankingRunDir: Path` is required (no nullable fallback)
- Added `ObjectMapper` constructor parameter
- Added `readCharacterNamesFromChunks(runDir: Path): List<String>`

- [ ] **Step 4: Add `deleteAll` to ExternalApiArtifactStorePort**

Check if `deleteAll` method exists in `ExternalApiArtifactStorePort`. If not, add it:

```kotlin
// In ExternalApiArtifactStorePort interface:
fun deleteAll(endpoint: ExternalApiEndpoint): Int
```

And implement in the local adapter — delete the directory for the given endpoint and return count of deleted files.

- [ ] **Step 5: Run tests**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest"`
Expected: Both tests PASS

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "feat(external-api): OcidLookupPhase reads from ranking gzip, no skip guard, pre-delete"
```

---

### Task 3: Wire runDir in ExternalApiScheduler

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Update triggerDailyRefresh() CF chain**

```kotlin
val rankingPhase = rankingFetchPhaseProvider.ifAvailable
if (rankingPhase == null) {
    log.error("[Scheduler] ranking fetch phase is required but not enabled")
    releaseLock()
    return
}

log.info("[Scheduler] starting ranking fetch phase")
rankingPhase.execute(executor)
    .handle { runDir, ex ->
        if (ex != null) {
            log.error("[Scheduler] ranking fetch failed, cannot proceed with OCID lookup", ex)
        }
        runDir
    }
    .thenCompose { runDir ->
        if (runDir == null) {
            CompletableFuture.completedFuture(null)
        } else {
            ocidLookupPhase.execute(executor, runDir)
        }
    }
    .thenCompose {
        val cache = ocidCacheProvider.refresh()
        snapshotFetchPhase.executeCharacterBasic(executor, cache)
    }
    .whenComplete { _, ex ->
        if (ex != null) {
            log.error("[Scheduler] daily refresh failed", ex)
        }
        releaseLock()
    }
```

Key changes:
- ranking is now required — log error and release lock if disabled
- `rankingRunDir` passed directly to `ocidLookupPhase.execute(executor, runDir)`

- [ ] **Step 2: Compile**

Run: `./gradlew :module-external-api:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "feat(external-api): wire ranking runDir to OCID lookup, ranking required"
```

---

### Task 4: Cleanup — remove CSV infrastructure

**Files:**
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/reader/UserIgnCsvReader.kt`
- Modify: `module-external-api/src/main/resources/application.yml` (remove `csv.path` if present)

- [ ] **Step 1: Delete UserIgnCsvReader**

```bash
git rm module-external-api/src/main/kotlin/maple/externalapi/reader/UserIgnCsvReader.kt
```

- [ ] **Step 2: Remove csv.path from application.yml if present**

Check `module-external-api/src/main/resources/application.yml` for `csv` or `csv-path` keys and remove them.

- [ ] **Step 3: Compile**

Run: `./gradlew :module-external-api:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore(external-api): remove UserIgnCsvReader and CSV config"
```

---

### Task 5: Integration verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :module-external-api:test`
Expected: All tests PASS

- [ ] **Step 2: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Runtime test**

```bash
set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local && export EXTERNAL_API_RANKING_MAX_PAGES=2 && export EXTERNAL_API_SCHEDULE_RUN_ON_STARTUP=true && ./gradlew :module-external-api:bootRun
```

Verify in logs:
1. `[RankingFetch]` completes with `result: total=400`
2. `[Scheduler] deleted N existing OCID files`
3. `[Scheduler] read 400 character names from ranking chunks: ...`
4. `[Scheduler] ========== OCID lookup start ==========`
5. OCID lookup processes 400 character names
