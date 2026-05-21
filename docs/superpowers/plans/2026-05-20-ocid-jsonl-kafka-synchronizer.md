# OCID Lookup: JSONL + Kafka + Synchronizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-file OCID storage (594K files, 15min+ load) with single gzip JSONL file + Kafka event + Synchronizer batch upsert (DB + Redis).

**Architecture:** OcidLookupPhase writes all OCID results to one gzip JSONL file under shared `./data/` storage, publishes Kafka run-completed event with relative path. OcidCacheProvider reads that single file (1-2s). New Synchronizer consumer resolves relative path against its `store.base-path`, batch upserts `game_character` table, and writes Redis HashMap (DELETE + HSET for stale entry prevention).

**Tech Stack:** Kotlin, CompletableFuture, Jackson, Kafka, Redis (synchronizer only), JDBC batch, gzip

**Storage layout** (project root, no module names):
```
data/
├── runs/{runId}/ranking-overall/chunks/   # ranking chunks
├── runs/{runId}/character-basic/chunks/   # character basic chunks
├── runs/{runId}/item-equipment/chunks/    # item equipment chunks
└── ocid-mapping/                          # OCID JSONL files
    └── ocid-mapping-{runId}.jsonl.gz
```

---

## File Structure

### Create
| File | Responsibility |
|------|---------------|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt` | Read gzip JSONL, parse OcidMapping records |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` | Batch upsert game_character + Redis HSET |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` | Kafka consumer for ocid-lookup run-completed events |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt` | Test file reader |
| `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` | Update existing test |

### Modify
| File | Change |
|------|--------|
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` | Rewrite: JSONL file instead of per-file storage, delete old files, publish Kafka event |
| `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` | Rewrite: read gzip JSONL instead of 594K files |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` | Remove artifactStore.deleteAll() call, minor flow update |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt` | Add OCID publisher qualified bean |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventProperties.kt` | Add ocidLookupTopic field |
| `module-external-api/src/main/resources/application.yml` | Change store.base-path to `./data`, add OCID event topic config |
| `module-synchronizer/src/main/resources/application.yml` | Change store.base-path to `./data`, add OCID consumer topic + group |

---

## Task 1: Rewrite OcidLookupPhase — collect results + write JSONL

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`

- [ ] **Step 1: Write the failing test**

Update `OcidLookupPhaseTest` to verify JSONL file output instead of per-file storage:

```kotlin
package maple.externalapi.scheduler.phase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import maple.externalapi.port.out.ExternalApiClientPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class OcidLookupPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var objectMapper: ObjectMapper
    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var phase: OcidLookupPhase

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        clientPort = mock()
        phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 400,
            batchSize = 1000,
            storeBasePath = tempDir.resolve("store").toString(),
        )
    }

    @Test
    fun `execute writes gzip JSONL with userIgn and ocid`() {
        // Create ranking chunks with character names
        val chunksDir = tempDir.resolve("store").resolve("runs").resolve("run-001")
            .resolve("ranking-overall").resolve("chunks")
        writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerA", "PlayerB"))
        writeGzipJsonl(chunksDir.resolve("part-000002.jsonl.gz"), listOf("PlayerC"))

        // Mock OCID API responses
        whenever(clientPort.fetch(maple.externalapi.domain.ExternalApiProvider.NEXON, maple.externalapi.domain.ExternalApiEndpoint.OCID_LOOKUP, "PlayerA"))
            .thenReturn(CompletableFuture.completedFuture("""{"ocid":"ocid-a"}""".toByteArray()))
        whenever(clientPort.fetch(maple.externalapi.domain.ExternalApiProvider.NEXON, maple.externalapi.domain.ExternalApiEndpoint.OCID_LOOKUP, "PlayerB"))
            .thenReturn(CompletableFuture.completedFuture("""{"ocid":"ocid-b"}""".toByteArray()))
        whenever(clientPort.fetch(maple.externalapi.domain.ExternalApiProvider.NEXON, maple.externalapi.domain.ExternalApiEndpoint.OCID_LOOKUP, "PlayerC"))
            .thenReturn(CompletableFuture.completedFuture("""{"ocid":"ocid-c"}""".toByteArray()))

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val resultPath = phase.execute(executor, tempDir.resolve("store").resolve("runs").resolve("run-001")).join()

        assertThat(resultPath).isNotNull
        assertThat(resultPath.toString()).endsWith(".jsonl.gz")
        assertThat(Files.exists(resultPath)).isTrue()

        // Verify content
        val mappings = readGzipJsonl(resultPath)
        assertThat(mappings).containsExactlyInAnyOrder(
            "PlayerA:ocid-a", "PlayerB:ocid-b", "PlayerC:ocid-c"
        )

        executor.close()
    }

    @Test
    fun `execute returns null when no character names`() {
        val runDir = tempDir.resolve("store").resolve("runs").resolve("empty-run")
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        val result = phase.execute(executor, runDir).join()
        assertThat(result).isNull()

        executor.close()
    }

    private fun writeGzipJsonl(chunkFilePath: Path, keys: List<String>) {
        Files.createDirectories(chunkFilePath.parent)
        java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(chunkFilePath.toFile()))).use { gzip ->
            for (key in keys) {
                val line = """{"endpoint":"ranking-overall","keyType":"DATE_PAGE","key":"$key","status":"SUCCESS","httpStatus":200,"fetchedAt":"2026-05-20T02:00:00Z","body":{"character_name":"$key"}}"""
                gzip.write((line + "\n").toByteArray())
            }
        }
    }

    private fun readGzipJsonl(path: Path): List<String> {
        val results = mutableListOf<String>()
        GZIPInputStream(java.io.BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    val node = objectMapper.readTree(line)
                    val ign = node.get("userIgn")?.asText()
                    val ocid = node.get("ocid")?.asText()
                    if (ign != null && ocid != null) {
                        results.add("$ign:$ocid")
                    }
                }
            }
        }
        return results
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" 2>&1 | tail -20`
Expected: FAIL — constructor signature mismatch, `artifactStore` still required

- [ ] **Step 3: Rewrite OcidLookupPhase implementation**

```kotlin
package maple.externalapi.scheduler.phase

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
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
    @Value("\${external-api.store.base-path:./data}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidLookupPhase::class.java)

    fun execute(workerExecutor: ExecutorService, rankingRunDir: Path): CompletableFuture<Path?> {
        val igns = readCharacterNamesFromChunks(rankingRunDir)
        if (igns.isEmpty()) {
            log.warn("[Scheduler] no character names from ranking chunks: {}", rankingRunDir)
            return CompletableFuture.completedFuture(null)
        }
        log.info("[Scheduler] read {} character names from ranking chunks", igns.size)

        val runId = SchedulerPhaseUtils.newRunId()
        val ocidDir = Paths.get(storeBasePath, "ocid-mapping")
        Files.createDirectories(ocidDir)
        deleteOldMappingFiles(ocidDir)
        val gzPath = ocidDir.resolve("ocid-mapping-$runId.jsonl.gz")

        val rateLimiter = SchedulerPhaseUtils.newRateLimiter(ocidLookupPermitsPerSecond)
        val results = Collections.synchronizedList(mutableListOf<String>())

        log.info("[Scheduler] ========== OCID lookup start ==========")
        log.info("[Scheduler] config: total={}, rate={}/s, batchSize={}", igns.size, ocidLookupPermitsPerSecond, batchSize)

        val start = Instant.now()
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val lastProgressLog = AtomicInteger(0)

        return processBatch(
            workerExecutor = workerExecutor,
            rateLimiter = rateLimiter,
            igns = igns,
            results = results,
            processed = 0,
            successCount = successCount,
            failCount = failCount,
            lastProgressLog = lastProgressLog,
            start = start,
        ).thenApply {
            writeGzipJsonl(gzPath, results)
            SchedulerPhaseUtils.logSummary("OCID lookup", igns.size, successCount.get(), results.size, failCount.get(), start)
            log.info("[Scheduler] OCID mapping written: {} entries, file={}", results.size, gzPath)
            gzPath
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

    private fun writeGzipJsonl(targetPath: Path, results: List<String>) {
        val tempJsonl = targetPath.resolveSibling(targetPath.fileName.toString().removeSuffix(".gz") + ".tmp")
        Files.newBufferedWriter(tempJsonl).use { writer ->
            for (jsonLine in results) {
                writer.write(jsonLine)
                writer.newLine()
            }
        }
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(targetPath.toFile()))).use { gzip ->
            Files.copy(tempJsonl, gzip)
        }
        Files.delete(tempJsonl)
    }

    private fun deleteOldMappingFiles(ocidDir: Path) {
        if (!Files.exists(ocidDir)) return
        Files.list(ocidDir).use { stream ->
            stream.filter { it.toString().endsWith(".jsonl.gz") }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun processBatch(
        workerExecutor: ExecutorService,
        rateLimiter: io.github.bucket4j.Bucket,
        igns: List<String>,
        results: MutableList<String>,
        processed: Int,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
        lastProgressLog: AtomicInteger,
        start: Instant,
    ): CompletableFuture<Void> {
        if (processed >= igns.size) {
            return CompletableFuture.completedFuture(null)
        }

        val permits = SchedulerPhaseUtils.acquirePermits(rateLimiter, batchSize, igns.size - processed)
        if (permits == 0) {
            return processBatch(workerExecutor, rateLimiter, igns, results, processed, successCount, failCount, lastProgressLog, start)
        }

        val chunk = igns.subList(processed, processed + permits)
        val futures = chunk.map { ign ->
            fetchAndCollectOcid(ign, workerExecutor, results, successCount, failCount)
        }

        return CompletableFuture.allOf(*futures.toTypedArray()).thenCompose {
            val progress = successCount.get() + failCount.get()
            if (progress - lastProgressLog.get() >= 5000) {
                lastProgressLog.set(progress)
                SchedulerPhaseUtils.logProgress("OCID lookup", progress, igns.size, results.size, failCount.get(), start)
            }
            processBatch(workerExecutor, rateLimiter, igns, results, processed + permits, successCount, failCount, lastProgressLog, start)
        }
    }

    private fun fetchAndCollectOcid(
        ign: String,
        workerExecutor: ExecutorService,
        results: MutableList<String>,
        successCount: AtomicInteger,
        failCount: AtomicInteger,
    ): CompletableFuture<Void> =
        clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, ign)
            .thenAcceptAsync({ data ->
                val ocid = objectMapper.readTree(data).get("ocid")?.asText()
                if (ocid != null) {
                    val json = objectMapper.writeValueAsBytes(mapOf("userIgn" to ign, "ocid" to ocid))
                    results.add(String(json))
                    successCount.incrementAndGet()
                }
            }, workerExecutor)
            .handle { _, ex ->
                if (ex != null) failCount.incrementAndGet()
                null
            }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "refactor(external-api): rewrite OcidLookupPhase to write single gzip JSONL instead of per-file storage"
```

---

## Task 2: Rewrite OcidCacheProvider — read from gzip JSONL

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`

- [ ] **Step 1: Rewrite OcidCacheProvider**

```kotlin
package maple.externalapi.cache

import com.fasterxml.jackson.databind.ObjectMapper
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

@Component
class OcidCacheProvider(
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.store.base-path:./data}")
    private val storeBasePath: String,
) {
    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val gzPath = findLatestOcidMappingFile()
        if (gzPath == null) {
            log.info("[OcidCache] no ocid-mapping file found, cache empty")
            return current()
        }

        val cache = loadFromGzipJsonl(gzPath)
        cacheRef.set(cache)
        log.info("[OcidCache] loaded: {} entries from {}", cache.size, gzPath.fileName)
        return cache
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()

    private fun findLatestOcidMappingFile(): Path? {
        val mappingDir = Paths.get(storeBasePath, "ocid-mapping")
        if (!Files.exists(mappingDir)) return null

        return Files.list(mappingDir)
            .use { stream ->
                stream
                    .filter { it.toString().endsWith(".jsonl.gz") }
                    .sorted()
                    .toList()
                    .lastOrNull()
            }
    }

    private fun loadFromGzipJsonl(gzPath: Path): Map<String, String> {
        val cache = mutableMapOf<String, String>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(gzPath))).bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    val node = objectMapper.readTree(line)
                    val ign = node.get("userIgn")?.asText()
                    val ocid = node.get("ocid")?.asText()
                    if (ign != null && ocid != null) {
                        cache[ign] = ocid
                    }
                }
            }
        }
        return cache
    }
}
```

- [ ] **Step 2: Update OcidCacheProvider usages — remove artifactStore constructor arg**

Check all places that construct or inject OcidCacheProvider. Remove `ExternalApiArtifactStorePort` from its constructor. No other code changes needed — `refresh()` and `current()` signatures unchanged.

- [ ] **Step 3: Run compile check**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt
git commit -m "refactor(external-api): OcidCacheProvider reads single gzip JSONL instead of 594K files"
```

---

## Task 3: Update ExternalApiScheduler — remove artifactStore.deleteAll()

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

- [ ] **Step 1: Remove artifactStore usage from scheduler**

In `ExternalApiScheduler`, remove the `artifactStore` field if only used for `deleteAll(OCID_LOOKUP)`. The new OcidLookupPhase no longer needs pre-deletion since it writes to a new file each run.

Changes:
- Remove `private val artifactStore: ExternalApiArtifactStorePort` from constructor (if only used for OCID delete)
- Remove `artifactStore.deleteAll(OCID_LOOKUP)` call in the flow (if present)
- The scheduler flow stays the same:
  ```
  ranking → OCID lookup → ocidCacheProvider.refresh() → character basic → releaseLock
  ```

- [ ] **Step 2: Run compile check**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "refactor(external-api): remove artifactStore.deleteAll from scheduler, OCID uses single file"
```

---

## Task 4: Add OCID Kafka publisher config

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt`
- Modify: `module-external-api/src/main/resources/application.yml`

- [ ] **Step 1: Add OCID publisher qualified bean**

Add a 4th qualified bean to `SnapshotEventPublisherConfig`:

```kotlin
@Bean
@Qualifier("ocidLookupSnapshotPublisher")
@ConditionalOnProperty(name = ["external-api.snapshot.events.kafka.enabled"], havingValue = "true")
fun ocidLookupKafkaPublisher(
    kafkaTemplate: KafkaTemplate<String, String>,
    objectMapper: ObjectMapper,
    properties: SnapshotEventProperties,
): SnapshotChunkEventPublisher =
    KafkaSnapshotChunkEventPublisher(
        kafkaTemplate = kafkaTemplate,
        objectMapper = objectMapper,
        chunkReadyTopic = properties.kafka.ocidLookupTopic,
        runCompletedTopic = properties.kafka.ocidLookupTopic,
        runFailedTopic = properties.kafka.runFailedTopic,
    )

@Bean
@Qualifier("ocidLookupSnapshotPublisher")
@ConditionalOnProperty(name = ["external-api.snapshot.events.kafka.enabled"], havingValue = "false", matchIfMissing = true)
fun ocidLookupNoOpPublisher(): SnapshotChunkEventPublisher = NoOpSnapshotChunkEventPublisher()
```

Add `ocidLookupTopic` field to `SnapshotEventProperties.Kafka`:

```kotlin
val ocidLookupTopic: String = "external-api.ocid.lookup-ready",
```

- [ ] **Step 2: Add YAML config**

Add to `module-external-api/src/main/resources/application.yml` under `external-api.snapshot.events.kafka`:

```yaml
ocid-lookup-topic: external-api.ocid.lookup-ready
```

- [ ] **Step 3: Update OcidLookupPhase to publish Kafka event**

Add `@Qualifier("ocidLookupSnapshotPublisher") private val eventPublisher: SnapshotChunkEventPublisher` to OcidLookupPhase constructor.

After writing the gzip file in `execute()`, publish:

```kotlin
eventPublisher.publishRunCompleted(SnapshotRunCompletedEvent(
    eventId = java.util.UUID.randomUUID().toString(),
    runId = runId,
    endpoint = "ocid-lookup",
    manifestPath = "ocid-mapping/${gzPath.fileName}",
    totalRecords = results.size,
    totalFailed = failCount.get(),
    chunkCount = 1,
    startedAt = start,
    finishedAt = Instant.now(),
    createdAt = Instant.now(),
))
```

Note: For the test, use `NoOpSnapshotChunkEventPublisher` in the constructor.

- [ ] **Step 4: Run compile check**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt module-external-api/src/main/resources/application.yml
git commit -m "feat(external-api): add OCID Kafka event publisher for run-completed notification"
```

---

## Task 5: Create OcidMappingFileReader (synchronizer)

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class OcidMappingFileReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var reader: OcidMappingFileReader
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule()
        reader = OcidMappingFileReader(objectMapper)
    }

    @Test
    fun `read parses gzip JSONL into OcidMapping list`() {
        val gzPath = tempDir.resolve("ocid-mapping.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            """{"userIgn":"PlayerB","ocid":"ocid-b"}""",
            """{"userIgn":"PlayerC","ocid":"ocid-c"}""",
        ))

        val mappings = reader.read(gzPath.toString())

        assertThat(mappings).hasSize(3)
        assertThat(mappings[0].userIgn).isEqualTo("PlayerA")
        assertThat(mappings[0].ocid).isEqualTo("ocid-a")
        assertThat(mappings[1].userIgn).isEqualTo("PlayerB")
        assertThat(mappings[2].userIgn).isEqualTo("PlayerC")
    }

    @Test
    fun `read skips blank lines and invalid JSON`() {
        val gzPath = tempDir.resolve("ocid-mapping.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            "",
            """invalid json""",
            """{"userIgn":"PlayerB","ocid":"ocid-b"}""",
        ))

        val mappings = reader.read(gzPath.toString())

        assertThat(mappings).hasSize(2)
    }

    @Test
    fun `read returns empty list when file not found`() {
        val mappings = reader.read("/nonexistent/path.jsonl.gz")
        assertThat(mappings).isEmpty()
    }

    private fun writeGzipJsonl(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { gzip ->
            for (line in lines) {
                gzip.write((line + "\n").toByteArray())
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.storage.OcidMappingFileReaderTest" 2>&1 | tail -10`
Expected: FAIL — OcidMappingFileReader not found

- [ ] **Step 3: Write OcidMappingFileReader**

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

data class OcidMapping(
    val userIgn: String,
    val ocid: String,
)

class OcidMappingFileReader(
    private val objectMapper: ObjectMapper,
    private val storeBasePath: String,
) {
    fun read(objectKey: String): List<OcidMapping> {
        val path = Paths.get(storeBasePath, objectKey)
        if (!Files.exists(path)) return emptyList()

        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    val node = objectMapper.readTree(line)
                    val ign = node.get("userIgn")?.asText()
                    val ocid = node.get("ocid")?.asText()
                    if (ign != null && ocid != null) {
                        mappings.add(OcidMapping(ign, ocid))
                    }
                }
            }
        }
        return mappings
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.storage.OcidMappingFileReaderTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt
git commit -m "feat(synchronizer): add OcidMappingFileReader for gzip JSONL OCID mapping files"
```

---

## Task 6: Create OcidMappingRepository — DB batch upsert + Redis

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`

- [ ] **Step 1: Write OcidMappingRepository**

```kotlin
package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OcidMappingRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(OcidMappingRepository::class.java)

    companion object {
        private const val BATCH_SIZE = 1000
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        var upserted = 0
        mappings.chunked(BATCH_SIZE).forEach { batch ->
            val sql = """
                INSERT INTO game_character (user_ign, ocid, updated_at)
                SELECT unnest(:userIgns::varchar[]), unnest(:ocids::varchar[]), now()
                ON CONFLICT (user_ign) DO UPDATE SET
                    ocid = EXCLUDED.ocid,
                    updated_at = EXCLUDED.updated_at
                WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
            """.trimIndent()

            val params = mapOf(
                "userIgns" to batch.map { it.userIgn }.toTypedArray(),
                "ocids" to batch.map { it.ocid }.toTypedArray(),
            )
            jdbcTemplate.update(sql, params)
            upserted += batch.size
        }
        log.info("[OcidMapping] DB upserted: {} mappings", upserted)
    }

    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        redisTemplate.delete(REDIS_KEY)
        redisTemplate.executePipelined { connection ->
            for (mapping in mappings) {
                connection.hashCommands().hSet(
                    REDIS_KEY.toByteArray(),
                    mapping.userIgn.toByteArray(),
                    mapping.ocid.toByteArray(),
                )
            }
            null
        }
        log.info("[OcidMapping] Redis written: {} mappings to {}", mappings.size, REDIS_KEY)
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt
git commit -m "feat(synchronizer): add OcidMappingRepository for DB batch upsert + Redis HSET"
```

---

## Task 7: Create OcidLookupRunConsumer (synchronizer)

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`

- [ ] **Step 1: Write OcidLookupRunConsumer**

```kotlin
package maple.synchronizer.consumer

import maple.expectation.common.event.SnapshotRunCompletedEvent
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMappingFileReader
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["synchronizer.kafka.ocid-lookup-enabled"], havingValue = "true")
class OcidLookupRunConsumer(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OcidLookupRunConsumer::class.java)

    @KafkaListener(
        topics = ["\${synchronizer.kafka.ocid-lookup-topic}"],
        groupId = "\${synchronizer.kafka.ocid-lookup-consumer-group-id}",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val event = objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java)
        if (event.endpoint != "ocid-lookup") return

        log.info("[OcidConsumer] received: runId={}, totalRecords={}, manifestPath={}",
            event.runId, event.totalRecords, event.manifestPath)

        val mappings = fileReader.read(event.manifestPath)
        if (mappings.isEmpty()) {
            log.warn("[OcidConsumer] no mappings found in: {}", event.manifestPath)
            return
        }

        repository.batchUpsert(mappings)
        repository.writeOcidToRedis(mappings)

        log.info("[OcidConsumer] completed: runId={}, processed={}", event.runId, mappings.size)
    }
}
```

- [ ] **Step 2: Run compile check**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt
git commit -m "feat(synchronizer): add OcidLookupRunConsumer for OCID mapping Kafka events"
```

---

## Task 8: Update YAML configs

**Files:**
- Modify: `module-synchronizer/src/main/resources/application.yml`

- [ ] **Step 1: Add OCID consumer config to synchronizer YAML**

Under `synchronizer.kafka`, add:

```yaml
ocid-lookup-enabled: true
ocid-lookup-topic: external-api.ocid.lookup-ready
ocid-lookup-consumer-group-id: synchronizer-ocid-lookup-consumer
```

- [ ] **Step 2: Run compile check**

Run: `./gradlew :module-external-api:compileKotlin :module-synchronizer:compileKotlin --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/resources/application.yml
git commit -m "feat(synchronizer): add OCID lookup Kafka consumer config"
```

---

## Task 9: Full compile + test verification

- [ ] **Step 1: Run full compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run affected module tests**

Run: `./gradlew :module-external-api:test :module-synchronizer:test 2>&1 | grep -E "BUILD|FAIL|tests completed" | head -10`
Expected: BUILD SUCCESSFUL

---

## Task 10: Runtime verification

- [ ] **Step 1: Start all modules**

```bash
set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local
./gradlew :module-external-api:bootRun > logs/pipeline-test-external-api.log 2>&1 &
# Wait for health
until curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; do sleep 3; done
echo "external-api ready"

./gradlew :module-synchronizer:bootRun > logs/pipeline-test-synchronizer.log 2>&1 &
until curl -sf http://localhost:8083/actuator/health > /dev/null 2>&1; do sleep 3; done
echo "synchronizer ready"
```

- [ ] **Step 2: Verify OCID JSONL file created**

```bash
find /data/external-api/ocid-mapping/ -name "*.jsonl.gz" -type f
```

- [ ] **Step 3: Verify Synchronizer consumed event and wrote to DB**

```bash
grep "OcidConsumer.*completed" logs/pipeline-test-synchronizer.log | tail -5
```

- [ ] **Step 4: Verify Redis has OCID data**

```bash
redis-cli HLEN ocid:mapping
```

- [ ] **Step 5: Verify OcidCacheProvider loaded from JSONL**

```bash
grep "OcidCache.*loaded" logs/pipeline-test-external-api.log | tail -5
```

- [ ] **Step 6: Cleanup**

```bash
for port in 8081 8083; do kill $(lsof -ti:$port) 2>/dev/null; done
```

- [ ] **Step 7: Final commit + PR**

Create PR targeting develop branch with all changes.
