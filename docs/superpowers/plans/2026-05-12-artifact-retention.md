# Artifact Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add storage lifecycle policies across External API, Calculator, and infrastructure to prevent disk-full incidents like the one that killed Kafka.

**Architecture:** Each module gets its own `@Scheduled` cleanup component. A shared `RunRetentionPolicy` in `module-common` provides the pure retention decision logic (keep if active OR recent 5 OR within 48h). External API handles `runs/`, `character-basic/`, `item-equipment/` cleanup (preserves `ocid-lookup/`). Calculator handles `data/calculator/runs/` cleanup. All cleanup starts in dry-run mode. Metrics track deleted files, bytes, and failures. Log rotation and Kafka retention config complete the 1st line of defense.

**Tech Stack:** Kotlin, Spring @Scheduled, Micrometer, Java NIO (Files.walk), logback rolling file appender, Docker Kafka config

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `module-common/.../cleanup/RunInfo.kt` | Run directory metadata data class |
| Create | `module-common/.../cleanup/RunRetentionPolicy.kt` | Pure retention decision logic |
| Create | `module-common/src/test/.../RunRetentionPolicyTest.kt` | Retention policy unit tests |
| Modify | `module-external-api/.../port/out/ExternalApiArtifactStorePort.kt` | Add listRuns, deleteRun, calculateSize |
| Modify | `module-external-api/.../infra/storage/LocalExternalApiArtifactStoreAdapter.kt` | Implement new port methods |
| Modify | `module-external-api/.../scheduler/ExternalApiScheduler.kt` | Add _RUNNING marker on run start |
| Create | `module-external-api/.../metrics/CleanupMetrics.kt` | Micrometer counters/gauges for cleanup |
| Create | `module-external-api/.../cleanup/ArtifactCleanupScheduler.kt` | @Scheduled cleanup for runs + artifacts |
| Modify | `module-calculator/.../storage/ObjectStorage.kt` | Add listRuns, deleteRun, calculateSize |
| Modify | `module-calculator/.../storage/LocalObjectStorageAdapter.kt` | Implement new interface methods |
| Create | `module-calculator/.../cleanup/CalculatorResultCleanupScheduler.kt` | @Scheduled cleanup for calculator results |
| Modify | `module-external-api/src/main/resources/application.yml` | Cleanup config section |
| Modify | `module-calculator/src/main/resources/application.yml` | Cleanup config section |
| Create | `scripts/rotate-logs.sh` | Log rotation for nohup bootrun logs |
| Modify | `docker-compose.yml` | Kafka service with retention config |

---

### Task 1: ADR for Artifact Retention Policy

**Files:**
- Create: `docs/01_ADR/ADR-040_artifact-retention-policy.md`

- [ ] **Step 1: Write ADR**

```markdown
# ADR-040: Artifact Retention Policy

- Status: Accepted
- Date: 2026-05-12
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- Three modules (External API, Calculator, Synchronizer) write artifact files to disk continuously
- Kafka stores message logs on the same disk
- No cleanup policy existed — disk filled to 100%, killing Kafka and halting the pipeline
- 129GB of old run data had to be deleted manually

### Problem

- Unbounded disk growth from runs, artifacts, and logs
- No automated lifecycle management
- No visibility into storage usage or cleanup operations

### Goal

- Automated, configurable artifact cleanup with dry-run safety
- Prevent disk-full incidents without manual intervention

---

## 2. Decision

> Retention policy applied uniformly across External API runs, individual artifacts, and Calculator results.

```text
Keep IF: (active run with _RUNNING marker) OR (recent 5 runs) OR (within 48h)
Delete: only when ALL keep conditions fail
Dry-run mode: mandatory for first deployment
ocid-lookup/: excluded from cleanup (cache dependency)
```

---

## 3. Trade-offs

### Sensitivity

* Run frequency (hourly vs daily) affects how quickly disk fills
* Individual artifact count per run (thousands of .json.gz files)
* Calculator result size per chunk (compressed JSONL)
* Kafka log retention vs available disk

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 48h + recent 5 보존 정책 | 충분한 롤백/조사 창 | 48h 이전 데이터 즉시 삭제 위험 |
| dry-run 기본값 | 삭제 사고 방지 | 첫 배포 시 수동 dry-run→live 전환 필요 |
| 모듈별 독립 스케줄러 | 모듈 독립성, 단순 배포 | 정책 코드 중복 (최소화됨) |

### Risk

* _RUNNING marker 미생성 시 활성 run 삭제 가능 (marker 생성 로직으로 완화)
* Calculator는 _RUNNING marker 없음 — 최근 수정 시간으로 활성 run 추론

### Non-Risk

* ocid-lookup 보존으로 OCID 캐시 재조회 방지
* dry-run 모드로 삭제 전 검증 가능

---

## 4. Result / Evidence

### Metrics

| Metric | Target | Notes |
| ------ | ----: | ----- |
| disk usage ratio | < 70% | cleanup 후 |
| cleanup cycle | 1h interval | @Scheduled |
| dry-run validation | 24h | 첫 배포 후 전환 |

### Observed Result

* TBD (post-deployment)

---

## 5. Summary

> 48시간 + 최근 5개 run 보존, dry-run 필수, ocid-lookup 제외, 모듈별 독립 @Scheduled cleanup
```

- [ ] **Step 2: Commit ADR**

```bash
git add docs/01_ADR/ADR-040_artifact-retention-policy.md
git commit -m "docs: add ADR-040 artifact retention policy"
```

---

### Task 2: RunInfo Data Class + RunRetentionPolicy

Shared retention logic in `module-common` (no Spring dependency — pure Kotlin).

**Files:**
- Create: `module-common/src/main/kotlin/maple/common/cleanup/RunInfo.kt`
- Create: `module-common/src/main/kotlin/maple/common/cleanup/RunRetentionPolicy.kt`
- Create: `module-common/src/test/kotlin/maple/common/cleanup/RunRetentionPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.common.cleanup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class RunRetentionPolicyTest {

    @Test
    fun `should delete run when not active, not recent 5, and older than 48h`() {
        val now = Instant.now()
        val runs = (0..9).map { i ->
            RunInfo(
                runId = "run-${i}",
                createdAt = now.minus(49, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        // runs 0..4 are the 5 most recent → kept
        // runs 5..9 are not recent 5, not active, older than 48h → deleted
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runs,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete.map { it.runId }).containsExactly(
            "run-5", "run-6", "run-7", "run-8", "run-9",
        )
    }

    @Test
    fun `should keep active run even if not recent 5 and older than 48h`() {
        val now = Instant.now()
        val oldActive = RunInfo(
            runId = "active-old",
            createdAt = now.minus(72, ChronoUnit.HOURS),
            isRunning = true,
            sizeBytes = 1024L,
        )
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = listOf(oldActive),
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete).isEmpty()
    }

    @Test
    fun `should keep run within 48h even if not recent 5`() {
        val now = Instant.now()
        val recentButOld = RunInfo(
            runId = "recent-24h",
            createdAt = now.minus(24, ChronoUnit.HOURS),
            isRunning = false,
            sizeBytes = 1024L,
        )
        // Create 6 newer runs so recentButOld is NOT in recent 5
        val newer = (0..5).map { i ->
            RunInfo(
                runId = "newer-$i",
                createdAt = now.minus(1, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = newer + recentButOld,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        // recentButOld is within 48h → kept
        assertThat(toDelete.map { it.runId }).doesNotContain("recent-24h")
    }

    @Test
    fun `should return empty when no runs`() {
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = emptyList(),
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = Instant.now(),
        )
        assertThat(toDelete).isEmpty()
    }

    @Test
    fun `should handle exactly keepRecentCount runs`() {
        val now = Instant.now()
        val runs = (0..4).map { i ->
            RunInfo(
                runId = "run-$i",
                createdAt = now.minus(49, ChronoUnit.HOURS).plusSeconds(i.toLong()),
                isRunning = false,
                sizeBytes = 1024L,
            )
        }
        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runs,
            keepRecentCount = 5,
            keepWithinHours = 48,
            now = now,
        )
        assertThat(toDelete).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-common:test --tests "maple.common.cleanup.RunRetentionPolicyTest" 2>&1 | tail -5
```

Expected: FAIL — `RunInfo` and `RunRetentionPolicy` not found.

- [ ] **Step 3: Create RunInfo data class**

```kotlin
package maple.common.cleanup

import java.time.Instant

data class RunInfo(
    val runId: String,
    val createdAt: Instant,
    val isRunning: Boolean,
    val sizeBytes: Long,
)
```

- [ ] **Step 4: Create RunRetentionPolicy**

```kotlin
package maple.common.cleanup

import java.time.Duration
import java.time.Instant

object RunRetentionPolicy {

    fun selectForDeletion(
        runs: List<RunInfo>,
        keepRecentCount: Int,
        keepWithinHours: Long,
        now: Instant,
    ): List<RunInfo> {
        if (runs.isEmpty()) return emptyList()

        val sortedByNewest = runs.sortedByDescending { it.createdAt }
        val recentRunIds = sortedByNewest.take(keepRecentCount).map { it.runId }.toSet()
        val cutoff = now.minus(Duration.ofHours(keepWithinHours))

        return runs.filter { run ->
            !run.isRunning
                && run.runId !in recentRunIds
                && run.createdAt.isBefore(cutoff)
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :module-common:test --tests "maple.common.cleanup.RunRetentionPolicyTest" 2>&1 | tail -5
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add module-common/src/main/kotlin/maple/common/cleanup/ module-common/src/test/kotlin/maple/common/cleanup/
git commit -m "feat: add RunInfo data class and RunRetentionPolicy with tests"
```

---

### Task 3: ExternalApiArtifactStorePort Cleanup Methods

Add `listRuns()`, `deleteRun()`, `calculateDirectorySize()`, `listArtifactFiles()`, `deleteFile()` to the port and adapter.

**Files:**
- Modify: `module-external-api/.../port/out/ExternalApiArtifactStorePort.kt`
- Modify: `module-external-api/.../infra/storage/LocalExternalApiArtifactStoreAdapter.kt`

- [ ] **Step 1: Add methods to ExternalApiArtifactStorePort**

Append to existing interface:

```kotlin
// In ExternalApiArtifactStorePort.kt, add these methods:

fun listRuns(): List<String>

fun deleteRun(runId: String): Long

fun fileExists(relativePath: String): Boolean

fun calculateDirectorySize(relativePath: String): Long
```

The full updated interface:

```kotlin
package maple.externalapi.port.out

import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiPayloadRef

interface ExternalApiArtifactStorePort {

    fun store(
        endpoint: ExternalApiEndpoint,
        key: String,
        data: ByteArray,
    ): ExternalApiPayloadRef

    fun read(
        endpoint: ExternalApiEndpoint,
        key: String,
    ): ByteArray?

    fun listStoredKeys(endpoint: ExternalApiEndpoint): List<String>

    fun listRuns(): List<String>

    fun deleteRun(runId: String): Long

    fun fileExists(relativePath: String): Boolean

    fun calculateDirectorySize(relativePath: String): Long
}
```

- [ ] **Step 2: Implement methods in LocalExternalApiArtifactStoreAdapter**

Add these imports and methods to the existing class:

```kotlin
// Add imports:
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.stream.Collectors

// Add these methods to LocalExternalApiArtifactStoreAdapter:

override fun listRuns(): List<String> {
    val runsDir = Paths.get(basePath, "runs")
    if (!Files.exists(runsDir)) return emptyList()
    return Files.list(runsDir).use { stream ->
        stream
            .filter { Files.isDirectory(it) }
            .map { it.fileName.toString() }
            .collect(Collectors.toList())
    }
}

override fun deleteRun(runId: String): Long {
    val runDir = Paths.get(basePath, "runs", runId)
    if (!Files.exists(runDir)) return 0L
    var deletedBytes = 0L
    Files.walkFileTree(runDir, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deletedBytes += attrs.size()
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
    return deletedBytes
}

override fun fileExists(relativePath: String): Boolean =
    Files.exists(Paths.get(basePath, relativePath))

override fun calculateDirectorySize(relativePath: String): Long {
    val dir = Paths.get(basePath, relativePath)
    if (!Files.exists(dir)) return 0L
    return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
        stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
    }
}
```

- [ ] **Step 3: Compile to verify**

```bash
./gradlew :module-external-api:compileKotlin 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt
git commit -m "feat: add cleanup methods to ExternalApiArtifactStorePort and adapter"
```

---

### Task 4: _RUNNING Marker in ExternalApiScheduler

Write a `_RUNNING` marker file when a run starts, and delete it when the ChunkedSnapshotSink closes successfully. This lets the cleanup scheduler detect active runs.

**Files:**
- Modify: `module-external-api/.../scheduler/ExternalApiScheduler.kt`
- Modify: `module-external-api/.../snapshot/ChunkedSnapshotSink.kt`

- [ ] **Step 1: Add _RUNNING marker in ExternalApiScheduler**

In `ExternalApiScheduler.kt`, add a helper method and call it in both `doCharacterBasicLookup()` and `doItemEquipmentLookup()`.

Add this private method:

```kotlin
private fun writeRunningMarker(runDir: Path) {
    val marker = runDir.resolve("_RUNNING")
    Files.createDirectories(runDir)
    Files.writeString(marker, Instant.now().toString())
    log.info("[Scheduler] wrote _RUNNING marker: {}", marker)
}
```

In `doCharacterBasicLookup()`, add after `val runDir = Paths.get(...)` and before creating `ChunkedSnapshotSink`:

```kotlin
writeRunningMarker(runDir)
```

In `doItemEquipmentLookup()`, same — add after `val runDir = ...` and before creating `ChunkedSnapshotSink`:

```kotlin
writeRunningMarker(runDir)
```

- [ ] **Step 2: Delete _RUNNING on successful close in ChunkedSnapshotSink**

In `ChunkedSnapshotSink.kt`, in the `close()` method, after `Files.writeString(successPath, "")` (the per-endpoint _SUCCESS), add code to remove the run-level _RUNNING marker:

Add this import:

```kotlin
import java.nio.file.Path
// (Path is already imported)
```

In the `close()` method, after `Files.writeString(successPath, "")`, add:

```kotlin
// Remove run-level _RUNNING marker (if exists)
val runningMarker = runDir.resolve("_RUNNING")
if (Files.exists(runningMarker)) {
    Files.delete(runningMarker)
}
```

Note: `runDir` is already a field in ChunkedSnapshotSink (passed in constructor). Verify it's accessible — looking at the constructor, `runDir` is a `val` parameter, so it's accessible.

Wait — actually, `runDir` is a plain constructor parameter, not a `val`. Let me check:

```kotlin
class ChunkedSnapshotSink(
    runDir: Path,  // <-- plain parameter, not val
    ...
)
```

It's used to derive `chunksDir`, `failedPath`, `manifestPath`, `successPath`. So it's NOT a field. We need to make it accessible. Add a private property:

Actually, looking more carefully at the constructor, `runDir` is used in init to derive other paths. The simplest fix is to store it:

Change `runDir: Path` to `private val runDir: Path` in the constructor.

Wait — that would be a breaking change if anyone relies on it being a plain parameter. But since `ChunkedSnapshotSink` is only constructed by `ExternalApiScheduler`, this is safe.

Change the constructor parameter from `runDir: Path` to `private val runDir: Path`.

- [ ] **Step 3: Compile to verify**

```bash
./gradlew :module-external-api:compileKotlin 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "feat: add _RUNNING marker for active run detection in external-api"
```

---

### Task 5: CleanupMetrics for External API

Micrometer metrics for monitoring cleanup operations. Includes duration timer and throttling visibility.

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/metrics/CleanupMetrics.kt`

- [ ] **Step 1: Create CleanupMetrics**

```kotlin
package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class CleanupMetrics(registry: MeterRegistry) {

    private val deletedRuns = registry.counter("artifact_cleanup_deleted_runs_total", "module", "external-api")
    private val deletedBytes = registry.counter("artifact_cleanup_deleted_bytes_total", "module", "external-api")
    private val errors = registry.counter("artifact_cleanup_errors_total", "module", "external-api")
    private val skippedActive = registry.counter("artifact_cleanup_skipped_active_runs_total", "module", "external-api")
    private val throttledRuns = registry.counter("artifact_cleanup_throttled_runs_total", "module", "external-api")

    private val durationTimer = Timer.builder("artifact_cleanup_duration_seconds")
        .description("Time spent on cleanup cycle")
        .tag("module", "external-api")
        .register(registry)

    @Volatile private var storageUsedBytes = 0L

    init {
        Gauge.builder("artifact_storage_used_bytes") { storageUsedBytes }
            .description("Current storage usage in bytes for external-api artifacts")
            .tag("module", "external-api")
            .register(registry)
    }

    fun recordDeletedRuns(count: Int) = deletedRuns.increment(count.toDouble())
    fun recordDeletedBytes(bytes: Long) = deletedBytes.increment(bytes.toDouble())
    fun recordError() = errors.increment()
    fun recordSkippedActive() = skippedActive.increment()
    fun recordThrottled(count: Int) = throttledRuns.increment(count.toDouble())
    fun timer(): Timer = durationTimer
    fun updateStorageUsed(bytes: Long) { storageUsedBytes = bytes }
}
```

- [ ] **Step 2: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/metrics/CleanupMetrics.kt
git commit -m "feat: add CleanupMetrics with duration timer and throttling counters"
```

---

### Task 6: ArtifactCleanupScheduler for External API

Background cleanup with TPS protection. Key design principles:

1. **Pipeline isolation**: All exceptions caught — cleanup failure never affects service health
2. **Throttling**: Per-run limits on deleted runs, bytes, and wall-clock time
3. **Directory-level deletion**: Entire `runs/{runId}/` removed at once, no per-file scanning
4. **Low frequency**: Default 6h interval. Never 1min.

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt`

- [ ] **Step 1: Create ArtifactCleanupScheduler**

```kotlin
package maple.externalapi.cleanup

import maple.common.cleanup.RunInfo
import maple.common.cleanup.RunRetentionPolicy
import maple.externalapi.metrics.CleanupMetrics
import maple.externalapi.port.out.ExternalApiArtifactStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
@ConditionalOnProperty(name = ["external-api.cleanup.enabled"], havingValue = "true")
class ArtifactCleanupScheduler(
    private val artifactStore: ExternalApiArtifactStorePort,
    private val metrics: CleanupMetrics,
    @Value("\${external-api.cleanup.dry-run:true}")
    private val dryRun: Boolean,
    @Value("\${external-api.cleanup.runs.keep-recent:5}")
    private val keepRecent: Int,
    @Value("\${external-api.cleanup.runs.keep-within-hours:48}")
    private val keepWithinHours: Long,
    @Value("\${external-api.cleanup.max-delete-runs-per-cycle:10}")
    private val maxDeleteRunsPerCycle: Int,
    @Value("\${external-api.cleanup.max-delete-bytes-per-cycle:5368709120}")
    private val maxDeleteBytesPerCycle: Long,
    @Value("\${external-api.cleanup.max-runtime-seconds:60}")
    private val maxRuntimeSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(ArtifactCleanupScheduler::class.java)

    private val runIdPattern = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    @Scheduled(fixedDelayString = "\${external-api.cleanup.interval-ms:21600000}")
    fun cleanup() {
        val sample = Timer.start()
        val start = Instant.now()
        log.info("[Cleanup] started: dryRun={}", dryRun)

        updateStorageMetrics()

        // Pipeline isolation: catch everything, never propagate
        val result = runCatching { cleanupRuns(start) }

        val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()
        sample.stop(metrics.timer())

        result.onSuccess { res ->
            log.info(
                "[Cleanup] completed: dryRun={}, runsDeleted={}, bytesDeleted={}, " +
                    "throttled={}, errors={}, durationMs={}",
                dryRun, res.runsDeleted, res.bytesDeleted, res.throttled, res.errors, durationMs,
            )
        }.onFailure { ex ->
            metrics.recordError()
            log.error("[Cleanup] failed (pipeline NOT affected): {}", ex.message, ex)
        }
    }

    private fun cleanupRuns(startedAt: Instant): CleanupResult {
        val runIds = artifactStore.listRuns()
        if (runIds.isEmpty()) {
            log.info("[Cleanup] no runs found")
            return CleanupResult.ZERO
        }

        var skippedActive = 0
        val runInfos = runIds.mapNotNull { runId ->
            val isRunning = artifactStore.fileExists("runs/$runId/_RUNNING")
            if (isRunning) {
                skippedActive++
                metrics.recordSkippedActive()
                return@mapNotNull null
            }
            val createdAt = parseRunIdTimestamp(runId) ?: return@mapNotNull null
            RunInfo(
                runId = runId,
                createdAt = createdAt,
                isRunning = false,
                sizeBytes = artifactStore.calculateDirectorySize("runs/$runId"),
            )
        }

        log.info("[Cleanup] scanned {} runs, skipped {} active, {} parseable", runIds.size, skippedActive, runInfos.size)

        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runInfos,
            keepRecentCount = keepRecent,
            keepWithinHours = keepWithinHours,
            now = Instant.now(),
        )

        if (toDelete.isEmpty()) {
            log.info("[Cleanup] no runs to delete")
            return CleanupResult.ZERO
        }

        // Apply throttling limits
        val throttled = toDelete.size - maxDeleteRunsPerCycle
        val limited = if (toDelete.size > maxDeleteRunsPerCycle) {
            log.info("[Cleanup] throttling: {} candidates, limit {}", toDelete.size, maxDeleteRunsPerCycle)
            metrics.recordThrottled(throttled)
            toDelete.take(maxDeleteRunsPerCycle)
        } else {
            toDelete
        }

        log.info(
            "[Cleanup] candidates: {} of {} scanned (throttled={}, dryRun={})",
            limited.size, runInfos.size, maxOf(0, throttled), dryRun,
        )

        if (dryRun) {
            limited.forEach { run ->
                log.info(
                    "[Cleanup] would delete: runId={}, size={}MB, createdAt={}",
                    run.runId, run.sizeBytes / (1024 * 1024), run.createdAt,
                )
            }
            return CleanupResult(limited.size, limited.sumOf { it.sizeBytes }, 0, maxOf(0, throttled))
        }

        return deleteRunWithLimits(limited, startedAt)
    }

    private fun deleteRunWithLimits(runs: List<RunInfo>, startedAt: Instant): CleanupResult {
        var deletedRuns = 0
        var deletedBytes = 0L
        var errors = 0

        for (run in runs) {
            // Check runtime limit
            val elapsed = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            if (elapsed > maxRuntimeSeconds * 1000) {
                log.info("[Cleanup] runtime limit reached: {}ms > {}ms, stopping", elapsed, maxRuntimeSeconds * 1000)
                break
            }
            // Check byte limit
            if (deletedBytes >= maxDeleteBytesPerCycle) {
                log.info("[Cleanup] byte limit reached: {} >= {}, stopping", deletedBytes, maxDeleteBytesPerCycle)
                break
            }

            val bytes = artifactStore.deleteRun(run.runId)
            if (bytes >= 0) {
                deletedRuns++
                deletedBytes += bytes
                metrics.recordDeletedBytes(bytes)
            } else {
                errors++
                metrics.recordError()
                log.warn("[Cleanup] failed to delete run: {} (pipeline NOT affected)", run.runId)
            }
        }

        metrics.recordDeletedRuns(deletedRuns)
        return CleanupResult(deletedRuns, deletedBytes, errors, 0)
    }

    private fun parseRunIdTimestamp(runId: String): Instant? {
        return runIdPattern.parse(runId) { Instant.from(it) }
    }

    private fun updateStorageMetrics() {
        val runsSize = artifactStore.calculateDirectorySize("runs")
        metrics.updateStorageUsed(runsSize)
    }

    private data class CleanupResult(
        val runsDeleted: Int,
        val bytesDeleted: Long,
        val errors: Int,
        val throttled: Int,
    ) {
        companion object {
            val ZERO = CleanupResult(0, 0L, 0, 0)
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```bash
./gradlew :module-external-api:compileKotlin 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/
git commit -m "feat: add throttled ArtifactCleanupScheduler with TPS protection"
```

---

### Task 7: Calculator ObjectStorage Cleanup Methods

Add cleanup methods to calculator's `ObjectStorage` interface and `LocalObjectStorageAdapter`.

**Files:**
- Modify: `module-calculator/.../storage/ObjectStorage.kt`
- Modify: `module-calculator/.../storage/LocalObjectStorageAdapter.kt`

- [ ] **Step 1: Add methods to ObjectStorage interface**

```kotlin
package maple.calculator.storage

import java.io.InputStream
import java.io.OutputStream

interface ObjectStorage {
    fun openInputStream(objectKey: String): InputStream
    fun openOutputStream(objectKey: String): OutputStream
    fun exists(objectKey: String): Boolean

    fun listDirectories(prefix: String): List<String>

    fun deleteDirectory(prefix: String): Long

    fun calculateDirectorySize(prefix: String): Long
}
```

- [ ] **Step 2: Implement in LocalObjectStorageAdapter**

Add imports and methods:

```kotlin
// Add imports:
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors

// Add methods:

override fun listDirectories(prefix: String): List<String> {
    val dir = Paths.get(basePath, prefix)
    if (!Files.exists(dir)) return emptyList()
    return Files.list(dir).use { stream ->
        stream
            .filter { Files.isDirectory(it) }
            .map { it.fileName.toString() }
            .collect(Collectors.toList())
    }
}

override fun deleteDirectory(prefix: String): Long {
    val dir = Paths.get(basePath, prefix)
    if (!Files.exists(dir)) return 0L
    var deletedBytes = 0L
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deletedBytes += attrs.size()
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
    return deletedBytes
}

override fun calculateDirectorySize(prefix: String): Long {
    val dir = Paths.get(basePath, prefix)
    if (!Files.exists(dir)) return 0L
    return Files.walk(dir, FileVisitOption.FOLLOW_LINKS).use { stream ->
        stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
    }
}
```

- [ ] **Step 3: Compile to verify**

```bash
./gradlew :module-calculator:compileKotlin 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 4: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/storage/ObjectStorage.kt module-calculator/src/main/kotlin/maple/calculator/storage/LocalObjectStorageAdapter.kt
git commit -m "feat: add cleanup methods to calculator ObjectStorage and adapter"
```

---

### Task 8: CalculatorResultCleanupScheduler

Calculator-specific cleanup for `data/calculator/runs/`. Same TPS protection as external-api: throttling, runtime limits, pipeline isolation. Uses directory last-modified time for activity detection (no _RUNNING marker).

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt`

- [ ] **Step 1: Create CalculatorResultCleanupScheduler**

```kotlin
package maple.calculator.cleanup

import maple.calculator.storage.ObjectStorage
import maple.common.cleanup.RunInfo
import maple.common.cleanup.RunRetentionPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["calculator.cleanup.enabled"], havingValue = "true")
class CalculatorResultCleanupScheduler(
    private val objectStorage: ObjectStorage,
    @Value("\${calculator.cleanup.dry-run:true}")
    private val dryRun: Boolean,
    @Value("\${calculator.cleanup.runs.keep-recent:5}")
    private val keepRecent: Int,
    @Value("\${calculator.cleanup.runs.keep-within-hours:48}")
    private val keepWithinHours: Long,
    @Value("\${calculator.cleanup.max-delete-runs-per-cycle:10}")
    private val maxDeleteRunsPerCycle: Int,
    @Value("\${calculator.cleanup.max-delete-bytes-per-cycle:5368709120}")
    private val maxDeleteBytesPerCycle: Long,
    @Value("\${calculator.cleanup.max-runtime-seconds:60}")
    private val maxRuntimeSeconds: Long,
    @Value("\${calculator.store.input-base-path:./external-api-data}")
    private val basePath: String,
) {
    private val log = LoggerFactory.getLogger(CalculatorResultCleanupScheduler::class.java)

    @Scheduled(fixedDelayString = "\${calculator.cleanup.interval-ms:21600000}")
    fun cleanup() {
        val start = Instant.now()
        log.info("[CalculatorCleanup] started: dryRun={}", dryRun)

        // Pipeline isolation: catch everything, never propagate
        val result = runCatching { cleanupRuns(start) }

        val durationMs = Instant.now().toEpochMilli() - start.toEpochMilli()

        result.onSuccess { res ->
            log.info(
                "[CalculatorCleanup] completed: dryRun={}, deleted={}, bytes={}, " +
                    "errors={}, throttled={}, durationMs={}",
                dryRun, res.deleted, res.bytes, res.errors, res.throttled, durationMs,
            )
        }.onFailure { ex ->
            log.error("[CalculatorCleanup] failed (pipeline NOT affected): {}", ex.message, ex)
        }
    }

    private fun cleanupRuns(startedAt: Instant): CleanupResult {
        val prefix = "data/calculator/runs"
        val runDirs = objectStorage.listDirectories(prefix)
        if (runDirs.isEmpty()) {
            log.info("[CalculatorCleanup] no calculator runs found")
            return CleanupResult.ZERO
        }

        val runInfos = runDirs.mapNotNull { parseRunInfo(prefix, it) }

        val toDelete = RunRetentionPolicy.selectForDeletion(
            runs = runInfos,
            keepRecentCount = keepRecent,
            keepWithinHours = keepWithinHours,
            now = Instant.now(),
        )

        if (toDelete.isEmpty()) {
            log.info("[CalculatorCleanup] no runs to delete")
            return CleanupResult.ZERO
        }

        // Apply throttling limits
        val throttled = maxOf(0, toDelete.size - maxDeleteRunsPerCycle)
        val limited = if (toDelete.size > maxDeleteRunsPerCycle) {
            log.info("[CalculatorCleanup] throttling: {} candidates, limit {}", toDelete.size, maxDeleteRunsPerCycle)
            toDelete.take(maxDeleteRunsPerCycle)
        } else {
            toDelete
        }

        log.info(
            "[CalculatorCleanup] candidates: {} of {} (throttled={}, dryRun={})",
            limited.size, runInfos.size, throttled, dryRun,
        )

        if (dryRun) {
            limited.forEach { run ->
                log.info(
                    "[CalculatorCleanup] would delete: runId={}, size={}MB",
                    run.runId, run.sizeBytes / (1024 * 1024),
                )
            }
            return CleanupResult(limited.size, limited.sumOf { it.sizeBytes }, 0, throttled)
        }

        return deleteRunWithLimits(limited, startedAt)
    }

    private fun deleteRunWithLimits(runs: List<RunInfo>, startedAt: Instant): CleanupResult {
        var deleted = 0
        var bytes = 0L
        var errors = 0

        for (run in runs) {
            // Check runtime limit
            val elapsed = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
            if (elapsed > maxRuntimeSeconds * 1000) {
                log.info("[CalculatorCleanup] runtime limit reached: {}ms, stopping", elapsed)
                break
            }
            // Check byte limit
            if (bytes >= maxDeleteBytesPerCycle) {
                log.info("[CalculatorCleanup] byte limit reached: {} bytes, stopping", bytes)
                break
            }

            val deletedBytes = objectStorage.deleteDirectory("data/calculator/runs/${run.runId}")
            if (deletedBytes >= 0) {
                deleted++
                bytes += deletedBytes
            } else {
                errors++
                log.warn("[CalculatorCleanup] failed to delete: {} (pipeline NOT affected)", run.runId)
            }
        }

        return CleanupResult(deleted, bytes, errors, 0)
    }

    private fun parseRunInfo(prefix: String, runId: String): RunInfo? {
        val fullPath = "$prefix/$runId"
        val sizeBytes = objectStorage.calculateDirectorySize(fullPath)
        val createdAt = readDirectoryCreatedTime(fullPath) ?: return null
        val isRunning = isRecentlyModified(fullPath)
        return RunInfo(
            runId = runId,
            createdAt = createdAt,
            isRunning = isRunning,
            sizeBytes = sizeBytes,
        )
    }

    private fun readDirectoryCreatedTime(prefix: String): Instant? {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return null
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        return Instant.ofEpochMilli(attrs.creationTime().toMillis())
    }

    private fun isRecentlyModified(prefix: String): Boolean {
        val path = Paths.get(basePath, prefix)
        if (!Files.exists(path)) return false
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        val modifiedAt = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
        return modifiedAt.isAfter(Instant.now().minusSeconds(1800))
    }

    private data class CleanupResult(
        val deleted: Int,
        val bytes: Long,
        val errors: Int,
        val throttled: Int,
    ) {
        companion object {
            val ZERO = CleanupResult(0, 0L, 0, 0)
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

```bash
./gradlew :module-calculator:compileKotlin 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 3: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/cleanup/
git commit -m "feat: add CalculatorResultCleanupScheduler for calculator artifact retention"
```

---

### Task 9: YAML Configuration

Add cleanup config sections to both modules' `application.yml`.

**Files:**
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/resources/application.yml`

- [ ] **Step 1: Add cleanup config to external-api application.yml**

Read the current file first, then add under the `external-api:` block (after `store:`):

```yaml
  # Artifact cleanup — TPS-safe background maintenance
  cleanup:
    enabled: false                    # enable to activate cleanup scheduler
    dry-run: true                     # true = log candidates without deleting
    interval-ms: 21600000             # 6 hours between cleanup cycles (never 1min)
    max-delete-runs-per-cycle: 10     # max runs deleted per cycle
    max-delete-bytes-per-cycle: 5368709120  # 5GB max deleted per cycle
    max-runtime-seconds: 60           # hard wall-clock limit per cycle
    runs:
      keep-recent: 5                  # always keep the 5 most recent runs
      keep-within-hours: 48           # keep runs newer than 48 hours
```

- [ ] **Step 2: Add cleanup config to calculator application.yml**

Read the current file first, then add under the `calculator:` block (after `store:`):

```yaml
  # Calculator result cleanup — TPS-safe background maintenance
  cleanup:
    enabled: false                    # enable to activate cleanup scheduler
    dry-run: true                     # true = log candidates without deleting
    interval-ms: 21600000             # 6 hours between cleanup cycles
    max-delete-runs-per-cycle: 10     # max runs deleted per cycle
    max-delete-bytes-per-cycle: 5368709120  # 5GB max deleted per cycle
    max-runtime-seconds: 60           # hard wall-clock limit per cycle
    runs:
      keep-recent: 5                  # always keep the 5 most recent runs
      keep-within-hours: 48           # keep runs newer than 48 hours
```

- [ ] **Step 3: Compile to verify**

```bash
./gradlew :module-external-api:compileKotlin :module-calculator:compileKotlin --continue 2>&1 | grep -E "FAIL|ERROR" || echo "OK"
```

Expected: OK

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/resources/application.yml module-calculator/src/main/resources/application.yml
git commit -m "feat: add cleanup YAML config for external-api and calculator (disabled by default)"
```

---

### Task 10: nohup Log Rotation

Create a script to rotate nohup/bootrun logs that accumulate indefinitely.

**Files:**
- Create: `scripts/rotate-logs.sh`

- [ ] **Step 1: Create log rotation script**

```bash
#!/usr/bin/env bash
# Rotate large log files by truncating to the last N megabytes.
# Usage: ./scripts/rotate-logs.sh [max-mb]
# Default: 100MB

set -euo pipefail

MAX_MB="${1:-100}"
MAX_BYTES=$((MAX_MB * 1024 * 1024))

# Log files to rotate (add new paths as needed)
LOG_FILES=(
  "/tmp/synchronizer.log"
  "/tmp/external-api.log"
  "/tmp/calculator.log"
  "/tmp/module-app.log"
)

for log_file in "${LOG_FILES[@]}"; do
  if [ ! -f "$log_file" ]; then
    continue
  fi

  size=$(stat -f%z "$log_file" 2>/dev/null || stat -c%s "$log_file" 2>/dev/null || echo 0)

  if [ "$size" -gt "$MAX_BYTES" ]; then
    echo "Rotating $log_file ($(numfmt --to=iec $size) > ${MAX_MB}MB)"
    tmp_file="${log_file}.tmp"
    tail -c "$MAX_BYTES" "$log_file" > "$tmp_file"
    mv "$tmp_file" "$log_file"
    echo "  -> truncated to last ${MAX_MB}MB"
  fi
done

echo "Log rotation complete"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/rotate-logs.sh
```

- [ ] **Step 3: Commit**

```bash
git add scripts/rotate-logs.sh
git commit -m "feat: add log rotation script for nohup bootrun logs"
```

---

### Task 11: Kafka Retention Config

Add Kafka service to docker-compose with retention settings.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add Kafka service to docker-compose.yml**

Add this service to the existing `docker-compose.yml` under `services:`:

```yaml
  # Kafka (Message Queue)
  kafka:
    image: confluentinc/cp-kafka:latest
    container_name: maple-kafka
    restart: always
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,HOST://localhost:9092
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
      KAFKA_LISTENERS: PLAINTEXT://kafka:29092,CONTROLLER://kafka:29093,HOST://0.0.0.0:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CLUSTER_ID: maple-kafka-cluster
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      # Retention settings to prevent disk fill
      KAFKA_LOG_RETENTION_HOURS: 48
      KAFKA_LOG_RETENTION_BYTES: 5368709120   # 5GB
      KAFKA_LOG_SEGMENT_BYTES: 1073741824     # 1GB segments
      KAFKA_LOG_CLEANUP_POLICY: delete
    volumes:
      - kafka_data:/var/lib/kafka/data
    networks:
      - maple-network
    depends_on:
      - postgres
```

Also add `kafka_data` to the `volumes:` section:

```yaml
volumes:
  prometheus_data:
  grafana_data:
  postgres_data:
  kafka_data:
```

**Note:** If Kafka is already running outside docker-compose (e.g., on a separate server), this step should be adapted to configure retention on the existing Kafka instance via `server.properties`:

```properties
log.retention.hours=48
log.retention.bytes=5368709120
log.segment.bytes=1073741824
log.cleanup.policy=delete
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add Kafka service with retention config to docker-compose"
```

---

### Task 12: Compile and Test

Full verification.

**Files:** None (verification only)

- [ ] **Step 1: Compile all modules**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run module-common tests (RunRetentionPolicy)**

```bash
./gradlew :module-common:test 2>&1 | tail -10
```

Expected: Tests pass

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit any fixes**

If compilation or tests fail, fix and commit.

---

## Verification Checklist

- [ ] ADR-040 written and committed
- [ ] RunRetentionPolicy unit tests pass
- [ ] ExternalApiArtifactStorePort has cleanup methods (listRuns, deleteRun, fileExists, calculateDirectorySize)
- [ ] _RUNNING marker written on run start, removed on close
- [ ] CleanupMetrics registered with duration timer, throttling counters, skipped-active counter
- [ ] ArtifactCleanupScheduler with throttling (maxDeleteRunsPerCycle, maxDeleteBytesPerCycle, maxRuntimeSeconds)
- [ ] Cleanup exceptions caught — pipeline never affected
- [ ] Calculator ObjectStorage has cleanup methods
- [ ] CalculatorResultCleanupScheduler with same throttling and safety
- [ ] YAML config: 6h interval, dry-run=true, throttling limits set
- [ ] Log rotation script created
- [ ] Kafka retention config added
- [ ] All modules compile
- [ ] All tests pass

## TPS Protection Criteria

Cleanup running must NOT cause:
- External API users/sec drop
- Calculator items/sec drop
- Synchronizer docs/sec drop
- Kafka lag increase
- JVM heap spike
- Disk I/O wait spike

Monitor via:
```
artifact_cleanup_duration_seconds     — cleanup wall-clock time
artifact_cleanup_deleted_runs_total   — runs deleted per cycle
artifact_cleanup_deleted_bytes_total  — bytes deleted per cycle
artifact_cleanup_errors_total         — failed deletions (pipeline unaffected)
artifact_cleanup_skipped_active_runs_total — active runs preserved
artifact_cleanup_throttled_runs_total — runs deferred due to limits
artifact_storage_used_bytes           — current disk usage
```

## Post-Deployment

1. Enable cleanup in YAML: `external-api.cleanup.enabled=true` (keep `dry-run: true`)
2. Monitor `[Cleanup]` log entries — verify candidates correct, throttling working
3. Check `artifact_cleanup_duration_seconds` — should be well under maxRuntimeSeconds
4. After 24h of dry-run, switch `dry-run: false`
5. Monitor `artifact_cleanup_*` metrics in Prometheus/Grafana
6. Verify TPS metrics unaffected during cleanup cycles
7. Repeat for calculator: `calculator.cleanup.enabled=true`
