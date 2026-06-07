# ChunkFileManager Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract all filesystem concerns (chunk rotation, manifest, success/running markers, failed-record file) from `ChunkedSnapshotSink` into a new `ChunkFileManager` class. The sink shrinks to ~190 lines holding only the queue, writer thread, and event-publishing calls.

**Architecture:** Add a new `ChunkFileManager` plain class (not a Spring bean) in the same package `maple.externalapi.snapshot`. The manager is constructed inline by `RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, and `ItemEquipmentFetchPhase` and passed to the sink. The sink delegates every filesystem mutation to the manager. The manager is single-thread-affine (called only from the sink's writer thread); not thread-safe.

**Tech Stack:** Kotlin, Java NIO Files, Jackson ObjectMapper, JUnit 5, Spring Boot 3.x, Gradle Kotlin DSL.

**Worktree:** `/home/maple/probabilistic-valuation-engine/.worktrees/issue-989` (branch `refactor/989-chunk-file-manager`)

**Spec:** `docs/superpowers/specs/2026-06-07-989-chunk-file-manager-extraction-design.md`

---

## File Structure

### Create

- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` — new class, ~150 lines, owns all filesystem state and ops.

### Modify

- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` — drop 8 I/O fields, drop 4 private methods, replace 1 field type. Add 1 `fileManager` field.
- `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt` — construct `ChunkFileManager` and pass to sink.
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` — construct `ChunkFileManager` and pass to sink.
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` — construct `ChunkFileManager` and pass to sink.

### No new tests

Issue #989 acceptance criteria do not require new tests. `./gradlew :module-external-api:test` (full module test suite) must still pass. Behavior preserved by mirroring the original logic exactly inside the manager.

---

## Task 1: Create ChunkFileManager skeleton

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt`

- [ ] **Step 1: Create the file with class skeleton and field declarations**

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * Owns every filesystem concern of a snapshot-sink run:
 *  - chunk directory layout (chunks/, failed.jsonl, manifest.json, _SUCCESS, _RUNNING)
 *  - the [SnapshotChunkManifest] for the run
 *  - the active [GzipJsonlChunkWriter] and rotation
 *  - the [SnapshotFailedRecordWriter] for failure records
 *
 * **Thread-affinity:** NOT thread-safe. All methods must be called from the
 * sink's single writer thread. The class does not perform its own locking.
 *
 * @param runDir  the run directory (e.g. `runs/<runId>`)
 * @param endpoint the API endpoint name (used as subdirectory)
 * @param maxRecords max records per chunk before rotation
 * @param maxUncompressedBytes hard cap per uncompressed chunk
 * @param objectMapper Jackson mapper for manifest and failure lines
 * @param clock injected clock for deterministic timestamps
 */
class ChunkFileManager(
    private val runDir: Path,
    private val endpoint: String,
    private val maxRecords: Int,
    private val maxUncompressedBytes: Long,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ChunkFileManager::class.java)

    val chunksDir: Path = runDir.resolve(endpoint).resolve("chunks")
    private val failedPath: Path = runDir.resolve(endpoint).resolve("failed.jsonl")
    private val manifestPath: Path = runDir.resolve(endpoint).resolve("manifest.json")
    private val successPath: Path = runDir.resolve(endpoint).resolve("_SUCCESS")
    private val runningMarker: Path = runDir.resolve("_RUNNING")

    private val manifest = SnapshotChunkManifest(
        runId = runDir.fileName.toString(),
        endpoint = endpoint,
        startedAt = Instant.now(clock),
    )

    private val failedWriter = SnapshotFailedRecordWriter(failedPath, objectMapper)
    private var currentWriter: GzipJsonlChunkWriter
    private var nextPartIndex = 2

    init {
        Files.createDirectories(chunksDir)
        Files.createDirectories(failedPath.parent)
        currentWriter = newChunkWriter(1)
    }

    fun appendSuccess(record: SnapshotChunkRecord.Success): ChunkStats? {
        currentWriter.append(record)
        manifest.totalRecords++
        if (currentWriter.shouldRotate()) {
            return rotateChunk()
        }
        return null
    }

    fun appendFailure(record: SnapshotChunkRecord.Failure) {
        failedWriter.append(record)
    }

    fun rotateChunk(): ChunkStats? {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        currentWriter = newChunkWriter(nextPartIndex++)
        return stats.takeIf { it.recordCount > 0 }
    }

    fun closeCurrentChunk(): ChunkStats? {
        val stats = currentWriter.close()
        if (stats.recordCount > 0) {
            manifest.chunks.add(toEntry(stats))
        }
        return stats.takeIf { it.recordCount > 0 }
    }

    fun cleanupOnFailure() {
        currentWriter.deleteTmp()
        log.warn("[ChunkFileManager] cleaned up .tmp files after failure")
    }

    fun writeManifestAndSuccessMarker() {
        manifest.totalFailed = failedWriter.count()
        manifest.finishedAt = Instant.now(clock)
        SnapshotChunkManifestWriter(manifestPath, objectMapper).write(manifest)
        Files.writeString(successPath, "")
    }

    fun deleteRunningMarker() {
        if (Files.exists(runningMarker)) {
            Files.delete(runningMarker)
        }
    }

    fun manifest(): SnapshotChunkManifest = manifest

    private fun newChunkWriter(partIndex: Int): GzipJsonlChunkWriter =
        GzipJsonlChunkWriter(chunksDir, partIndex, maxRecords, maxUncompressedBytes, objectMapper, clock)

    private fun toEntry(stats: ChunkStats): ChunkEntry = ChunkEntry(
        path = stats.path,
        recordCount = stats.recordCount,
        uncompressedBytes = stats.uncompressedBytes,
        compressedBytes = stats.compressedBytes,
        startedAt = stats.startedAt,
        finishedAt = stats.finishedAt,
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run from worktree root `/home/maple/probabilistic-valuation-engine/.worktrees/issue-989`:

```bash
./gradlew :module-external-api:compileKotlin --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with no errors. The new class is unused so far, but must typecheck.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-989
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt
git commit -m "refactor(989): add ChunkFileManager skeleton"
```

---

## Task 2: Refactor ChunkedSnapshotSink to delegate to ChunkFileManager

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt:1-217`

- [ ] **Step 1: Replace imports**

Replace the file's import block (lines 1-15) with:

```kotlin
package maple.externalapi.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
```

- [ ] **Step 2: Replace the constructor**

Replace lines 17-55 (class declaration through end of `init {}` block) with:

```kotlin
class ChunkedSnapshotSink(
    private val runDir: Path,
    private val endpoint: String,
    private val queueCapacity: Int,
    private val fileManager: ChunkFileManager,
    private val eventPublisher: SnapshotSinkEventPublisher,
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread.ofPlatform().name("snapshot-writer-$endpoint").unstarted(runnable)
    },
) {
    private val log = LoggerFactory.getLogger(ChunkedSnapshotSink::class.java)

    private val queue = ArrayBlockingQueue<SnapshotChunkRecord>(queueCapacity)
    private val accepting = AtomicBoolean(true)
    private val writerError = AtomicReference<Throwable?>(null)
```

Drop from the constructor: `maxRecords`, `maxUncompressedBytes`, `objectMapper`, `clock` — all moved into `ChunkFileManager` (constructed by the caller).

- [ ] **Step 3: Replace close() body**

Replace lines 89-135 (the body of `fun close()`) with:

```kotlin
    fun close() {
        accepting.set(false)
        if (!queue.offer(SnapshotChunkRecord.CloseSignal, 30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw IllegalStateException("failed to enqueue close signal after 30s")
        }

        writerExecutor.shutdown()
        if (!writerExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.warn("[Sink] writer executor did not terminate within 60s, forcing shutdown")
            writerExecutor.shutdownNow()
        }

        val err = writerError.get()
        val manifest = fileManager.manifest()
        if (err != null) {
            fileManager.cleanupOnFailure()
            eventPublisher.publishRunFailed(manifest, endpoint, err.message ?: "unknown")
            throw RuntimeException("writer thread failed: ${err.message}", err)
        }

        // close current chunk and write manifest + _SUCCESS marker
        fileManager.closeCurrentChunk()?.let { stats ->
            eventPublisher.publishChunkReady(stats, manifest.runId, endpoint)
        }
        fileManager.writeManifestAndSuccessMarker()
        fileManager.deleteRunningMarker()

        log.info(
            "[Sink] closed: endpoint={}, chunks={}, records={}, failed={}",
            endpoint, manifest.chunks.size, manifest.totalRecords, manifest.totalFailed,
        )

        // publish run completed (after _SUCCESS)
        eventPublisher.publishRunCompleted(manifest, endpoint)
    }
```

- [ ] **Step 4: Replace runWriterLoop and handleSuccess**

Replace lines 137-175 (`runWriterLoop` and `handleSuccess`) with:

```kotlin
    private fun runWriterLoop() {
        try {
            while (true) {
                val record = queue.take()
                when (record) {
                    is SnapshotChunkRecord.Success -> handleSuccess(record)
                    is SnapshotChunkRecord.Failure -> fileManager.appendFailure(record)
                    is SnapshotChunkRecord.CloseSignal -> return
                }
            }
        } catch (ex: Exception) {
            writerError.set(ex)
            accepting.set(false)
            log.error("[Sink] writer thread error: {}", ex.message, ex)
        }
    }

    private fun handleSuccess(record: SnapshotChunkRecord.Success) {
        try {
            val stats = fileManager.appendSuccess(record)
            if (stats != null) {
                eventPublisher.publishChunkReady(stats, fileManager.manifest().runId, endpoint)
            }
        } catch (ex: Exception) {
            log.warn("[Sink] invalid bodyBytes for key={}, treating as failure: {}", record.key, ex.message)
            fileManager.appendFailure(
                SnapshotChunkRecord.Failure(
                    key = record.key,
                    endpoint = record.endpoint,
                    keyType = record.keyType,
                    httpStatus = record.httpStatus,
                    fetchedAt = record.fetchedAt,
                    errorMessage = "invalid body: ${ex.message}",
                ),
            )
        }
    }
```

- [ ] **Step 5: Remove the four private methods at the bottom of the file**

Delete lines 177-216 — `rotateChunk()`, `closeCurrentChunk()`, `cleanupOnFailure()`, `newChunkWriter()`. Their responsibilities are now in `ChunkFileManager`.

The file should now end with the closing `}` of `handleSuccess` followed by the class closing `}`.

- [ ] **Step 6: Verify the file compiles**

Run from worktree root:

```bash
./gradlew :module-external-api:compileKotlin --console=plain 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` for `compileKotlin`, but the three call sites (`RankingSnapshotSinkFactory`, `CharacterBasicFetchPhase`, `ItemEquipmentFetchPhase`) will FAIL because they still pass the old 8-arg constructor with `maxRecords`, `maxUncompressedBytes`, `objectMapper`, `clock`. That is expected — fixed in Task 3.

- [ ] **Step 7: Commit (broken — Task 3 fixes it)**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-989
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
git commit -m "refactor(989): delegate ChunkedSnapshotSink I/O to ChunkFileManager"
```

---

## Task 3: Update call sites to construct ChunkFileManager

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt:16-36`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt:72-82`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt:61-71`

- [ ] **Step 1: Update RankingSnapshotSinkFactory.create()**

Replace lines 23-35 (the body of `fun create(...)`) with:

```kotlin
    fun create(runDir: Path, endpoint: String): ChunkedSnapshotSink {
        val endpointConfig = chunkingProperties.configFor(endpoint)
        val fileManager = ChunkFileManager(
            runDir = runDir,
            endpoint = endpoint,
            maxRecords = endpointConfig.maxRecords,
            maxUncompressedBytes = endpointConfig.maxUncompressedBytes,
            objectMapper = objectMapper,
        )
        return ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = endpoint,
            queueCapacity = chunkingProperties.queueCapacity,
            fileManager = fileManager,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(rankingPublisher),
                volumeMetrics = volumeMetrics,
            ),
        )
    }
```

Note: `RankingSnapshotSinkFactory` no longer needs `clock` (clock lives inside `ChunkFileManager`).

- [ ] **Step 2: Update CharacterBasicFetchPhase sink construction**

Replace lines 72-82 (`val sink = ChunkedSnapshotSink(...)`) with:

```kotlin
        val fileManager = ChunkFileManager(
            runDir = runDir,
            endpoint = "character-basic",
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            objectMapper = objectMapper,
            clock = clock,
        )
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "character-basic",
            queueCapacity = chunkingProperties.queueCapacity,
            fileManager = fileManager,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(eventPublisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
        )
```

- [ ] **Step 3: Update ItemEquipmentFetchPhase sink construction**

Replace lines 61-71 (`val sink = ChunkedSnapshotSink(...)`) with:

```kotlin
        val fileManager = ChunkFileManager(
            runDir = runDir,
            endpoint = "item-equipment",
            maxRecords = chunkConfig.maxRecords,
            maxUncompressedBytes = chunkConfig.maxUncompressedBytes,
            objectMapper = objectMapper,
            clock = clock,
        )
        val sink = ChunkedSnapshotSink(
            runDir = runDir,
            endpoint = "item-equipment",
            queueCapacity = chunkingProperties.queueCapacity,
            fileManager = fileManager,
            eventPublisher = SnapshotSinkEventPublisher(
                eventPublisher = SinkEventPublisher(eventPublisher),
                volumeMetrics = volumeMetrics,
                clock = clock,
            ),
        )
```

- [ ] **Step 4: Verify whole module compiles**

Run from worktree root:

```bash
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue --console=plain 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` for both `compileKotlin` and `compileJava`. Zero errors.

- [ ] **Step 5: Verify the full Gradle build compiles**

Run:

```bash
./gradlew compileKotlin compileJava --continue --console=plain 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. No reference to removed `maxRecords`/`maxUncompressedBytes`/`objectMapper`/`clock` constructor args remains in the sink callers.

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-989
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/RankingSnapshotSinkFactory.kt \
        module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt \
        module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt
git commit -m "refactor(989): construct ChunkFileManager in 3 sink factories"
```

---

## Task 4: Run unit tests

**Files:** none

- [ ] **Step 1: Run module-external-api tests**

Run from worktree root:

```bash
./gradlew :module-external-api:test --console=plain 2>&1 | tail -40
```

Expected: `BUILD SUCCESSFUL`. No test failures.

- [ ] **Step 2: Run full test suite**

```bash
./gradlew test --console=plain 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`. Existing test suite passes — behavior preserved.

- [ ] **Step 3: Report line count change**

Run:

```bash
wc -l /home/maple/probabilistic-valuation-engine/.worktrees/issue-989/module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt \
      /home/maple/probabilistic-valuation-engine/.worktrees/issue-989/module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt
```

Expected output (approximate):

```
   ~140 module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt
   ~150 module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt
   ~290 total
```

- [ ] **Step 4: No commit — just report**

If `wc` shows the sink dropped below 200 lines and the manager is the only new file, report pass. If sink is still > 200 lines or manager is missing, stop and report which private method/field was missed.

---

## Task 5: Server runtime validation

**Files:** none

- [ ] **Step 1: Start module-external-api**

Run from worktree root in background:

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-989
set -a && source ../../.env && set +a
./gradlew :module-external-api:bootRun > module-external-api/logs/run-989-validation.log 2>&1 &
echo "PID=$!"
```

Wait for startup by tailing the log until "Started" appears:

```bash
tail -f module-external-api/logs/run-989-validation.log | grep -m 1 "Started"
```

Expected: line containing "Started ExternalApiApplication" within 90 seconds.

- [ ] **Step 2: Hit the expectation API**

```bash
curl -s -w "\nHTTP %{http_code}\n" "http://localhost:8081/api/v5/characters/진격캐넌/expectation"
```

Expected: `HTTP 202`. (Port 8081 is the external-api port for this service — note it does NOT go through the rest-controller.)

- [ ] **Step 3: Check the snapshot output directory**

The scheduler should write a `runs/<runId>/character-basic/chunks/part-XXXXXX.jsonl.gz` file within ~2 minutes. Check with:

```bash
find ../data/runs -name "part-*.jsonl.gz" 2>/dev/null | head -5
find ../data/runs -name "manifest.json" 2>/dev/null | head -5
find ../data/runs -name "_SUCCESS" 2>/dev/null | head -5
```

Expected: at least one `part-*.jsonl.gz`, one `manifest.json`, and one `_SUCCESS` marker per run directory. If `..` does not resolve to the data root, use the absolute path from `.env`'s `EXTERNAL_API_STORE_BASE_PATH` (default `../data`).

- [ ] **Step 4: Verify the manifest content is valid JSON**

```bash
cat $(find ../data/runs -name "manifest.json" | head -1) | python3 -m json.tool | head -30
```

Expected: well-formed JSON with `runId`, `endpoint`, `startedAt`, `chunks[]`, `totalRecords`, `totalFailed`.

- [ ] **Step 5: Stop the server**

```bash
pkill -f "gradlew :module-external-api:bootRun" || true
pkill -f "ExternalApiApplication" || true
sleep 2
```

- [ ] **Step 6: No commit — runtime validation only**

If all of: HTTP 202, chunks present, manifest valid JSON, no `ERROR` lines in `run-989-validation.log` — pass. Report success with:
- first chunk file path
- manifest path
- `totalRecords` value
- `grep -c ERROR module-external-api/logs/run-989-validation.log` (must be 0)

If `grep -c ERROR` is non-zero, attach the ERROR lines to the failure report and stop.

---

## Task 6: Push branch and open PR

**Files:** none

- [ ] **Step 1: Confirm git status is clean**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-989
git status -sb
```

Expected: `## refactor/989-chunk-file-manager...origin/refactor/989-chunk-file-manager [ahead 4]` (no untracked files; 4 commits ahead).

- [ ] **Step 2: Push branch**

```bash
git push -u origin refactor/989-chunk-file-manager 2>&1 | tail -5
```

Expected: branch created on origin.

- [ ] **Step 3: Open PR targeting develop**

```bash
gh pr create --base develop --head refactor/989-chunk-file-manager \
  --title "refactor: #989 extract ChunkFileManager from ChunkedSnapshotSink" \
  --body "$(cat <<'EOF'
## Summary

- Issue #989: extract file I/O + chunk rotation from ChunkedSnapshotSink into new ChunkFileManager class
- Sink reduces from 217 → ~140 lines, holds only queue + writer thread + event-publisher calls
- Manager owns chunks dir, failed.jsonl, manifest.json, _SUCCESS, _RUNNING, and the active chunk writer
- Pure mechanical refactor; no behavior change

## Files

- New: `module-external-api/.../snapshot/ChunkFileManager.kt`
- Modified: `ChunkedSnapshotSink.kt`, `RankingSnapshotSinkFactory.kt`, `CharacterBasicFetchPhase.kt`, `ItemEquipmentFetchPhase.kt`

## Verification

- `./gradlew compileKotlin compileJava --continue` → BUILD SUCCESSFUL
- `./gradlew test` → BUILD SUCCESSFUL
- Server runtime: bootRun + expectation API → 202 + chunk files + valid manifest
- No new ERROR logs

## Spec

- `docs/superpowers/specs/2026-06-07-989-chunk-file-manager-extraction-design.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL printed. Confirm `gh pr view --json state,url` shows `OPEN` and the URL.

- [ ] **Step 4: Report PR URL and final state**

Stop here. Do not merge — the user will review the PR and merge.

---

## Self-Review

### 1. Spec coverage

| Spec section | Plan task |
| --- | --- |
| `ChunkFileManager` owns chunksDir/failedPath/manifestPath/successPath | Task 1 (Step 1) |
| `ChunkFileManager` owns currentWriter + nextPartIndex | Task 1 (Step 1) |
| `ChunkFileManager` owns manifest + failedWriter | Task 1 (Step 1) |
| `appendSuccess`, `rotateChunk`, `closeCurrentChunk`, `cleanupOnFailure`, `writeManifestAndSuccessMarker`, `deleteRunningMarker`, `manifest()` | Task 1 (Step 1) |
| Sink keeps `queue`, `accepting`, `writerError`, `writerExecutor` | Task 2 (Step 2) |
| Sink drops `chunksDir/failedPath/manifestPath/successPath/manifest/failedWriter/currentWriter/nextPartIndex` | Task 2 (Step 2, Step 5) |
| Sink drops `rotateChunk/closeCurrentChunk/newChunkWriter/cleanupOnFailure` | Task 2 (Step 5) |
| Sink calls `eventPublisher.publishChunkReady(stats, runId, endpoint)` | Task 2 (Step 3, Step 4) |
| Constructor changes (sink takes `fileManager` instead of 4 I/O args) | Task 2 (Step 2) |
| 3 call sites updated to build `ChunkFileManager` | Task 3 (Steps 1, 2, 3) |
| `./gradlew compileKotlin compileJava --continue` passes | Task 3 (Step 5) |
| `./gradlew test` passes | Task 4 (Step 2) |
| Server runtime validation | Task 5 |

All spec acceptance criteria are covered.

### 2. Placeholder scan

- "TBD"/"TODO"/"implement later" → none
- "Add appropriate error handling" → none (behavior preserved verbatim)
- "Write tests for the above" without code → none (no new tests required by spec)
- "Similar to Task N" → none (full code in every code step)
- Undefined types/methods → all referenced types (`SnapshotChunkRecord.Success/Failure/CloseSignal`, `ChunkStats`, `ChunkEntry`, `SnapshotChunkManifest`, `SnapshotFailedRecordWriter`, `GzipJsonlChunkWriter`, `SnapshotChunkManifestWriter`, `SnapshotSinkEventPublisher`, `SinkEventPublisher`, `SnapshotVolumeMetrics`, `Clock`, `ExecutorService`) are pre-existing in the codebase.

### 3. Type consistency

- `ChunkFileManager.appendSuccess(record)` returns `ChunkStats?` consistently in Task 1 (declaration) and Task 2 (handleSuccess).
- `rotateChunk()` returns `ChunkStats?` consistently in Task 1 and Task 2 (close()).
- `closeCurrentChunk()` returns `ChunkStats?` consistently in Task 1 and Task 2 (close()).
- `manifest()` getter matches usage `fileManager.manifest()` in Task 2.
- `eventPublisher.publishChunkReady(stats, runId, endpoint)` signature matches `SnapshotSinkEventPublisher.publishChunkReady(stats: ChunkStats, runId: String, endpoint: String)` from issue-987 spec — verified by inspecting `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt:31`.
- `eventPublisher.publishRunCompleted(manifest, endpoint)` and `publishRunFailed(manifest, endpoint, errorMessage)` match `SnapshotSinkEventPublisher:58,77`.

Plan is consistent.
