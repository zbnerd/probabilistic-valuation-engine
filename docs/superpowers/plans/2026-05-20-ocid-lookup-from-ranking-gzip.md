# OCID Lookup from Ranking Gzip Chunks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change OcidLookupPhase input source from CSV file to ranking gzip JSONL chunks, passing runDir through the scheduler CF chain.

**Architecture:** RankingFetchPhase returns its runDir after completion. OcidLookupPhase reads character_names from gzip JSONL chunks in that runDir. If ranking is disabled (runDir=null), falls back to existing CSV reader. Scheduler wires the runDir via CF chain.

**Tech Stack:** Kotlin, Jackson ObjectMapper, java.util.zip.GZIPInputStream, CompletableFuture, JUnit 5 + Mockito

---

### Task 1: RankingFetchPhase returns runDir

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt:51`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt`

- [ ] **Step 1: Update RankingFetchPhase.execute() return type**

Change return type from `CompletableFuture<Void>` to `CompletableFuture<Path>` and add `.thenApply { runDir }`:

```kotlin
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
    val characterNames = mutableListOf<String>()

    log.info("[RankingFetch] starting: runId={}, date={}, maxPages={}, permitsPerSecond={}", runId, date, maxPages, permitsPerSecond)
    val start = Instant.now()

    return processPages(workerExecutor, sink, rateLimiter, date, 1, fetched, failed, characterNames)
        .whenComplete { _, ex ->
            sink.close()
            if (ex != null) {
                log.error("[RankingFetch] failed: runId={}, fetched={}, failed={}", runId, fetched.get(), failed.get(), ex)
            } else {
                writeCsv(characterNames)
                SchedulerPhaseUtils.logSummary("RankingFetch", fetched.get(), fetched.get(), fetched.get(), failed.get(), start)
            }
        }
        .thenApply { runDir }
}
```

- [ ] **Step 2: Update existing test to verify runDir is returned**

In `RankingFetchPhaseTest`, update the first test to verify the returned path:

```kotlin
@Test
fun `execute fetches all pages and writes character names to CSV`() {
    whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:1"))
        .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerA", "PlayerB")))
    whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:2"))
        .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerC", "PlayerD")))
    whenever(clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, "2026-05-20:3"))
        .thenReturn(CompletableFuture.completedFuture(rankingJson("PlayerE")))

    val future = phase.execute(executor)
    val resultPath = future.join()

    // Then - returns runDir path
    assertThat(resultPath).isNotNull
    assertThat(resultPath.toString()).contains("runs")

    // And - CSV contains all character names
    val csvLines = Files.readAllLines(csvPath).filter { it.isNotBlank() }
    assertThat(csvLines).containsExactly("PlayerA", "PlayerB", "PlayerC", "PlayerD", "PlayerE")

    // And - gzip chunk files created in store
    val chunksDir = tempDir.resolve("store").resolve("runs")
    val gzFiles = Files.walk(chunksDir).filter { it.toString().endsWith(".gz") }.toList()
    assertThat(gzFiles).isNotEmpty

    // And - _SUCCESS marker exists
    val successMarkers = Files.walk(chunksDir).filter { it.fileName.toString() == "_SUCCESS" }.toList()
    assertThat(successMarkers).hasSize(1)
}
```

- [ ] **Step 3: Run tests to verify**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.RankingFetchPhaseTest" -i`
Expected: All 3 tests PASS

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhaseTest.kt
git commit -m "refactor(external-api): RankingFetchPhase returns runDir Path"
```

---

### Task 2: Add gzip JSONL chunk reader to OcidLookupPhase

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Test: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` (create)

- [ ] **Step 1: Write test for readCharacterNamesFromChunks**

Create test file at `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.reader.UserIgnCsvReader
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
            csvReader = mock(),
            artifactStore = mock(),
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
        // Given - two chunk files with some duplicate keys
        val chunksDir = tempDir.resolve("runs").resolve("20260520-030000-123").resolve("ranking-overall").resolve("chunks")
        writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerA", "PlayerB", "PlayerC"))
        writeGzipJsonl(chunksDir.resolve("part-000002.jsonl.gz"), listOf("PlayerC", "PlayerD"))

        // When
        val names = phase.readCharacterNamesFromChunks(tempDir.resolve("runs").resolve("20260520-030000-123"))

        // Then - distinct character names
        assertThat(names).containsExactlyInAnyOrder("PlayerA", "PlayerB", "PlayerC", "PlayerD")
    }

    @Test
    fun `readCharacterNamesFromChunks returns empty list when no chunk files`() {
        // Given - empty directory
        val runDir = tempDir.resolve("runs").resolve("empty-run")

        // When
        val names = phase.readCharacterNamesFromChunks(runDir)

        // Then
        assertThat(names).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" -i`
Expected: FAIL — `readCharacterNamesFromChunks` is not defined

- [ ] **Step 3: Add readCharacterNamesFromChunks method and update execute signature**

Add to `OcidLookupPhase.kt`:

```kotlin
package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import maple.externalapi.reader.UserIgnCsvReader
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
    private val csvReader: UserIgnCsvReader,
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

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path? = null): CompletableFuture<Void> {
        val existingOcids = artifactStore.listStoredKeys(ExternalApiEndpoint.OCID_LOOKUP)
        if (existingOcids.isNotEmpty()) {
            log.info("[Scheduler] OCID lookup already done ({} files), skipping", existingOcids.size)
            return CompletableFuture.completedFuture(null)
        }

        val igns = if (rankingRunDir != null) {
            val names = readCharacterNamesFromChunks(rankingRunDir)
            log.info("[Scheduler] read {} character names from ranking chunks: {}", names.size, rankingRunDir)
            names
        } else {
            csvReader.readAll()
        }

        if (igns.isEmpty()) {
            log.warn("[Scheduler] no IGNs to process")
            return CompletableFuture.completedFuture(null)
        }

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

Key changes:
- Added `ObjectMapper` constructor parameter
- Added `rankingRunDir: Path? = null` parameter to `execute()`
- Added `readCharacterNamesFromChunks(runDir: Path): List<String>`
- If rankingRunDir provided, reads from gzip chunks; otherwise falls back to csvReader

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" -i`
Expected: Both tests PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "feat(external-api): OcidLookupPhase reads from ranking gzip chunks"
```

---

### Task 3: Wire runDir in ExternalApiScheduler

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt:64-78`

- [ ] **Step 1: Update scheduler CF chain to pass runDir**

In `triggerDailyRefresh()`, update the ranking → OCID lookup chain:

```kotlin
val rankingPhase = rankingFetchPhaseProvider.ifAvailable
val rankingFuture = if (rankingPhase != null) {
    log.info("[Scheduler] starting ranking fetch phase")
    rankingPhase.execute(executor)
        .handle { runDir, ex ->
            if (ex != null) {
                log.error("[Scheduler] ranking fetch failed, continuing with OCID lookup", ex)
            }
            runDir
        }
} else {
    log.info("[Scheduler] ranking fetch phase disabled, skipping")
    CompletableFuture.completedFuture(null)
}

rankingFuture
    .thenCompose { runDir ->
        ocidLookupPhase.execute(executor, runDir)
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

Note: `.handle` now passes through `runDir` (Path or null) instead of always returning null. This allows OCID lookup to receive the ranking run directory when ranking is enabled and successful.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "feat(external-api): wire ranking runDir to OCID lookup via scheduler chain"
```

---

### Task 4: Integration verification

**Files:** None (runtime verification only)

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :module-external-api:test -i`
Expected: All tests PASS (RankingFetchPhaseTest 3 tests + OcidLookupPhaseTest 2 tests)

- [ ] **Step 2: Compile all modules**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Runtime test**

Start server:
```bash
set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local && export EXTERNAL_API_RANKING_MAX_PAGES=2 && export EXTERNAL_API_SCHEDULE_RUN_ON_STARTUP=true && ./gradlew :module-external-api:bootRun
```

Verify in logs:
1. `[RankingFetch]` completes with `result: total=400`
2. `[Scheduler] read 400 character names from ranking chunks: ...`
3. `[Scheduler] ========== OCID lookup start ==========`
4. OCID lookup processes the 400 character names from ranking gzip chunks
