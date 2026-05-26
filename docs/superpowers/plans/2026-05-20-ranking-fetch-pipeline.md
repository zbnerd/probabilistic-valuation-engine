# Ranking Fetch Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Daily fetch of Nexon overall ranking (pages 1..300), store as chunked gzip JSONL, extract character_names to CSV for downstream OCID lookup.

**Architecture:** New `RankingFetchPhase` prepended to `ExternalApiScheduler` daily pipeline. Ranking API pages fetched sequentially with rate limiting. Each page response flattened into individual ranking entry JSONL records → `ChunkedSnapshotSink` for gzip storage. character_names collected during streaming → CSV overwrite after completion. Ranking failure does NOT block downstream OCID lookup (error isolation via `.handle`).

**Tech Stack:** Kotlin, Spring Boot, WebClient, Virtual Threads, CompletableFuture chaining, Bucket4j, Jackson, gzip JSONL

**Pipeline flow:**
```
RankingFetchPhase (gzip + CSV) → [error isolation] → OcidLookupPhase (reads CSV) → SnapshotFetchPhase
```

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `module-external-api/.../domain/ExternalApiFetchCommand.kt` | Add `RANKING_OVERALL` endpoint + `KeyType.DATE_PAGE` |
| Modify | `module-external-api/.../infra/nexon/NexonExternalApiClientAdapter.kt` | Handle `DATE_PAGE` → `date` + `page` query params |
| Modify | `module-external-api/.../snapshot/SnapshotChunkingProperties.kt` | Add `rankingOverall` chunk config + `configFor` branch |
| Modify | `module-external-api/.../snapshot/event/SnapshotEventPublisherConfig.kt` | Add `rankingSnapshotPublisher` qualified beans |
| Modify | `module-external-api/.../metrics/ExternalApiMetrics.kt` | Add ranking fetched/failed counters |
| Create | `module-external-api/.../scheduler/phase/RankingFetchPhase.kt` | Core phase: page iteration, entry flattening, gzip sink, CSV overwrite |
| Modify | `module-external-api/.../scheduler/ExternalApiScheduler.kt` | Inject via `ObjectProvider`, chain ranking before OCID lookup with error isolation |
| Modify | `module-external-api/src/main/resources/application.yml` | Add ranking config block |

---

### Task 1: Add RANKING_OVERALL endpoint + KeyType.DATE_PAGE

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiFetchCommand.kt`

- [ ] **Step 1: Add `DATE_PAGE` to KeyType and `RANKING_OVERALL` to ExternalApiEndpoint**

```kotlin
enum class ExternalApiEndpoint(
    val path: String,
    val keyType: KeyType,
) {
    OCID_LOOKUP("/maplestory/v1/id", KeyType.USER_IGN),
    CHARACTER_BASIC("/maplestory/v1/character/basic", KeyType.OCID),
    ITEM_EQUIPMENT("/maplestory/v1/character/item-equipment", KeyType.OCID),
    RANKING_OVERALL("/maplestory/v1/ranking/overall", KeyType.DATE_PAGE),
    ;

    fun storageSubDir(): String = name.lowercase().replace('_', '-')
}

enum class KeyType {
    USER_IGN,
    OCID,
    DATE_PAGE,
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiFetchCommand.kt
git commit -m "feat(external-api): add RANKING_OVERALL endpoint and DATE_PAGE key type"
```

---

### Task 2: Handle DATE_PAGE in NexonExternalApiClientAdapter

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt`

The `fetch()` method currently has a `val queryParam = when(endpoint.keyType)` block (line 76-79) that only handles `USER_IGN` and `OCID`. Need to add `DATE_PAGE` handling that splits the requestKey by `:` into `date` + `page` params.

- [ ] **Step 1: Update fetch() method**

Replace lines 76-88 (from `val queryParam = when(...)` through `.build()` in the uri lambda):

```kotlin
    override fun fetch(
        provider: ExternalApiProvider,
        endpoint: ExternalApiEndpoint,
        requestKey: String,
    ): CompletableFuture<ByteArray> {
        val startedAt = Instant.now()
        return webClient.get()
            .uri { builder ->
                val pathBuilder = builder.path(endpoint.path)
                when (endpoint.keyType) {
                    KeyType.USER_IGN -> pathBuilder.queryParam("character_name", requestKey)
                    KeyType.OCID -> pathBuilder.queryParam("ocid", requestKey)
                    KeyType.DATE_PAGE -> {
                        val parts = requestKey.split(":", limit = 2)
                        pathBuilder
                            .queryParam("date", parts[0])
                            .queryParam("page", parts.getOrElse(1) { "1" })
                    }
                }.build()
            }
```

Rest of the method (`.header`, `.retrieve()`, `.bodyToMono`, metrics, error handling, `.timeout`, `.toFuture`) stays unchanged.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt
git commit -m "feat(external-api): handle DATE_PAGE key type in Nexon adapter"
```

---

### Task 3: Add ranking chunk config + YAML properties

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkingProperties.kt`
- Modify: `module-external-api/src/main/resources/application.yml`

- [ ] **Step 1: Add `rankingOverall` to ChunkConfig and `configFor` branch**

```kotlin
@ConfigurationProperties(prefix = "external-api.snapshot")
data class SnapshotChunkingProperties(
    val chunk: ChunkConfig = ChunkConfig(),
    val queueCapacity: Int = 1000,
) {
    data class ChunkConfig(
        val characterBasic: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 2000),
        val itemEquipment: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 500),
        val rankingOverall: EndpointChunkConfig = EndpointChunkConfig(maxRecords = 5000),
    )

    data class EndpointChunkConfig(
        val maxRecords: Int = 1000,
        val maxUncompressedBytes: Long = 134217728L,
    )

    fun configFor(endpoint: String): EndpointChunkConfig = when (endpoint) {
        "character-basic" -> chunk.characterBasic
        "item-equipment" -> chunk.itemEquipment
        "ranking-overall" -> chunk.rankingOverall
        else -> EndpointChunkConfig()
    }
}
```

- [ ] **Step 2: Add ranking config to application.yml**

Add under the existing `external-api:` block, after `snapshot:` section (line 77), before `management:`:

```yaml
  ranking:
    enabled: true
    max-pages: 300
    permits-per-second: 50
```

The full ranking config sits at `external-api.ranking.*`. Insert at the same indentation level as `schedule:`, `urgent:`, `snapshot:`.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkingProperties.kt module-external-api/src/main/resources/application.yml
git commit -m "feat(external-api): add ranking chunk config and YAML properties"
```

---

### Task 4: Add ranking publisher bean + ranking metrics

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/metrics/ExternalApiMetrics.kt`

- [ ] **Step 1: Add `rankingSnapshotPublisher` qualified beans**

Add two new bean methods inside `SnapshotEventPublisherConfig` class, after the existing `characterBasicSnapshotPublisher` beans:

```kotlin
    @Bean
    @Qualifier("rankingSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun noOpRankingSnapshotPublisher(): SnapshotChunkEventPublisher =
        NoOpSnapshotChunkEventPublisher()

    @Bean
    @Qualifier("rankingSnapshotPublisher")
    @ConditionalOnProperty(
        prefix = "external-api.snapshot.events.kafka",
        name = ["enabled"],
        havingValue = "true",
    )
    fun kafkaRankingSnapshotPublisher(
        kafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
        properties: SnapshotEventProperties,
    ): SnapshotChunkEventPublisher =
        KafkaSnapshotChunkEventPublisher(
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            chunkReadyTopic = properties.kafka.chunkReadyTopic,
            runCompletedTopic = properties.kafka.runCompletedTopic,
            runFailedTopic = properties.kafka.runFailedTopic,
        )
```

- [ ] **Step 2: Add ranking metrics to ExternalApiMetrics**

Add two counter fields and two methods to `ExternalApiMetrics`:

```kotlin
    private val rankingFetched = registry.counter("external_api_ranking_fetched_total")
    private val rankingFailed = registry.counter("external_api_ranking_failed_total")

    fun recordRankingFetched(count: Int = 1) {
        rankingFetched.increment(count.toDouble())
    }

    fun recordRankingFailed(count: Int = 1) {
        rankingFailed.increment(count.toDouble())
    }
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt module-external-api/src/main/kotlin/maple/externalapi/metrics/ExternalApiMetrics.kt
git commit -m "feat(external-api): add ranking snapshot publisher bean and metrics"
```

---

### Task 5: Create RankingFetchPhase

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt`

This is the core new class. Follows `SnapshotFetchPhase` patterns:
- `runDir = Paths.get(storeBasePath, "runs", runId)` — sink creates endpoint subdirectory internally
- `ChunkedSnapshotSink(runDir = runDir: Path, ...)` — no `runId` parameter
- Sequential page processing via recursive `thenCompose` CF chaining
- Rate limited via Bucket4j
- CSV overwrite with collected character_names after all pages complete

- [ ] **Step 1: Create RankingFetchPhase**

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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
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
    @Value("\${external-api.csv.path:userIgn_List.csv}")
    private val csvPath: String,
) {
    private val log = LoggerFactory.getLogger(RankingFetchPhase::class.java)

    fun execute(workerExecutor: ExecutorService): CompletableFuture<Void> {
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
    }

    private fun processPages(
        workerExecutor: ExecutorService,
        sink: ChunkedSnapshotSink,
        rateLimiter: io.github.bucket4j.Bucket,
        date: String,
        currentPage: Int,
        fetched: AtomicInteger,
        failed: AtomicInteger,
        characterNames: MutableList<String>,
    ): CompletableFuture<Void> {
        if (currentPage > maxPages) {
            return CompletableFuture.completedFuture(null)
        }

        SchedulerPhaseUtils.acquirePermits(rateLimiter, 1, 1)

        val requestKey = "$date:$currentPage"
        return clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.RANKING_OVERALL, requestKey)
            .thenAcceptAsync({ bodyBytes ->
                val count = submitRankingEntries(sink, bodyBytes, currentPage, characterNames)
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
            .thenCompose { processPages(workerExecutor, sink, rateLimiter, date, currentPage + 1, fetched, failed, characterNames) }
    }

    private fun submitRankingEntries(
        sink: ChunkedSnapshotSink,
        bodyBytes: ByteArray,
        page: Int,
        characterNames: MutableList<String>,
    ): Int {
        val root = objectMapper.readTree(bodyBytes)
        val rankingArray = root.get("ranking")
        if (rankingArray == null || !rankingArray.isArray) {
            log.warn("[RankingFetch] no ranking array in response: page={}", page)
            return 0
        }

        var count = 0
        for (node in rankingArray) {
            val entryBytes = objectMapper.writeValueAsBytes(node)
            val name = node.get("character_name")?.asText() ?: continue
            characterNames.add(name)
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

    private fun writeCsv(characterNames: List<String>) {
        val path = Paths.get(csvPath)
        val content = characterNames.joinToString("\n", postfix = "\n")
        Files.writeString(path, content)
        log.info("[RankingFetch] CSV overwritten: path={}, count={}", path.toAbsolutePath(), characterNames.size)
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt
git commit -m "feat(external-api): create RankingFetchPhase with gzip sink and CSV overwrite"
```

---

### Task 6: Wire RankingFetchPhase into ExternalApiScheduler

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

Key changes:
- Inject `ObjectProvider<RankingFetchPhase>` for conditional bean (not nullable constructor param)
- Chain ranking before OCID lookup
- Error isolation: `.handle { _, _ -> null }` so ranking failure does NOT block downstream

- [ ] **Step 1: Update scheduler constructor and triggerDailyRefresh**

Add import:
```kotlin
import maple.externalapi.scheduler.phase.RankingFetchPhase
import org.springframework.beans.factory.ObjectProvider
```

Update constructor to add `rankingFetchPhaseProvider`:
```kotlin
class ExternalApiScheduler(
    private val ocidLookupPhase: OcidLookupPhase,
    private val snapshotFetchPhase: SnapshotFetchPhase,
    private val ocidCacheProvider: OcidCacheProvider,
    private val rankingFetchPhaseProvider: ObjectProvider<RankingFetchPhase>,
    @Value("\${external-api.schedule.run-on-startup:false}")
    private val runOnStartup: Boolean,
    @Value("\${external-api.schedule.skip-character-basic:false}")
    private val skipCharacterBasic: Boolean,
) : ManagedLifecycle {
```

Update `triggerDailyRefresh()` — replace lines 62-72 (the `ocidLookupPhase.execute(executor)...` block):

```kotlin
        val rankingPhase = rankingFetchPhaseProvider.ifAvailable
        val rankingFuture = if (rankingPhase != null) {
            log.info("[Scheduler] starting ranking fetch phase")
            rankingPhase.execute(executor)
                .handle { _, ex ->
                    if (ex != null) {
                        log.error("[Scheduler] ranking fetch failed, continuing with OCID lookup", ex)
                    }
                    null
                }
        } else {
            log.info("[Scheduler] ranking fetch phase disabled, skipping")
            CompletableFuture.completedFuture(null)
        }

        rankingFuture
            .thenCompose {
                ocidLookupPhase.execute(executor)
            }
            .thenCompose {
                val cache = ocidCacheProvider.refresh()
                snapshotFetchPhase.executeCharacterBasic(executor, cache)
            }
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.error("[Scheduler] daily refresh failed", ex)
                }
                running.set(false)
            }
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "feat(external-api): wire RankingFetchPhase into daily scheduler with error isolation"
```

---

### Task 7: Full compile + test verification

- [ ] **Step 1: Full project compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run module-external-api tests**

Run: `./gradlew :module-external-api:test 2>&1 | tail -10`
Expected: All existing tests pass (new phase is conditional, no tests break)

- [ ] **Step 3: Verify YAML property keys match code**

Check all `@Value` and `@ConditionalOnProperty` references:
- `external-api.ranking.enabled` → `@ConditionalOnProperty` in RankingFetchPhase
- `external-api.ranking.max-pages` → `@Value` in RankingFetchPhase
- `external-api.ranking.permits-per-second` → `@Value` in RankingFetchPhase
- `external-api.csv.path` → `@Value` in RankingFetchPhase
- `external-api.store.base-path` → existing, reused

- [ ] **Step 4: Final commit if any fixes needed**

---

## Self-Review Checklist

- [x] Spec coverage: Daily cron, page 1..N, YAML config, gzip JSONL via ChunkedSnapshotSink, CSV overwrite, error isolation
- [x] Placeholder scan: No TBD/TODO/placeholders
- [x] Type consistency: `SnapshotChunkRecord.Success` params match sealed interface (key, endpoint, keyType, httpStatus, fetchedAt, bodyBytes); `KeyType.DATE_PAGE` used consistently; `runDir: Path` not String
- [x] No duplicate logic: Reuses ExternalApiClientPort, ChunkedSnapshotSink, GzipJsonlChunkWriter, SnapshotChunkEventPublisher, SchedulerPhaseUtils, Bucket4j
- [x] Project rules: No try-catch (CF chaining + .handle), no join/get, no System.out, no `!!`, logging via LoggerFactory, LogicExecutor not needed (no try-catch used)
- [x] Module boundaries: All changes within module-external-api, port interfaces unchanged
- [x] Async safety: Sequential CF chaining via recursive `thenCompose`, no blocking, `.handle` for error isolation
- [x] CSV format: One IGN per line, matches `UserIgnCsvReader.readAll()` expectations (trim + blank filter)
