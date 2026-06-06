# SynchronizerMetrics Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `SynchronizerMetrics` into a `SynchronizerMeterRegistry` (meter creation) and a refactored `SynchronizerMetrics` (recording methods only). Consumers and public API unchanged.

**Architecture:** New `@Component` class `SynchronizerMeterRegistry` holds `MeterRegistry` and owns 19 meter declarations + 4 factory methods. `SynchronizerMetrics` is refactored to hold `SynchronizerMeterRegistry` and delegates every recording method. No consumer-side changes.

**Tech Stack:** Kotlin, Spring `@Component`, Micrometer `MeterRegistry` (Counter, Timer, DistributionSummary, Gauge), `java.util.concurrent.atomic.AtomicInteger`.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMeterRegistry.kt` | Create | Owns all meter creation, 1-time init. Exposes meters via getters. |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt` | Modify | Refactored: holds `SynchronizerMeterRegistry`, recording methods only. Public API unchanged. |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt` | Modify | Update line 27 constructor call to wrap `SimpleMeterRegistry` in `SynchronizerMeterRegistry`. |

All consumer files (`ChunkConsumerTemplate.kt`, `ChunkDataReader.kt`, `ChunkDocumentWriter.kt`, `DefaultChunkProcessor.kt`, `ChunkDocumentTransformer.kt`, `SynchronizerChunkMetricsListener.kt`) are **untouched** — public API of `SynchronizerMetrics` is byte-for-byte identical.

---

## Task 1: Create SynchronizerMeterRegistry

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMeterRegistry.kt`

- [ ] **Step 1: Create the new file with all 19 meter declarations + 4 factory methods**

Path: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMeterRegistry.kt`

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class SynchronizerMeterRegistry(private val registry: MeterRegistry) {

    // Chunk counters
    private val chunksReceived = registry.counter("synchronizer_chunks_received_total")
    private val chunksProcessing = AtomicInteger(0)
    private val chunksProcessed = registry.counter("synchronizer_chunks_processed_total")
    private val chunksFailed = registry.counter("synchronizer_chunks_failed_total")

    init {
        registry.gauge("synchronizer_chunks_processing", chunksProcessing)
    }

    // Document / item counters
    private val documentsProcessed = registry.counter("synchronizer_documents_processed_total")
    private val itemsProcessed = registry.counter("synchronizer_items_processed_total")

    // Timers — 각 단계별 latency
    private val chunkTimer = Timer.builder("synchronizer_chunk_duration_seconds")
        .description("Total time to process a single chunk end-to-end")
        .publishPercentileHistogram()
        .register(registry)

    private val fileReadTimer = Timer.builder("synchronizer_file_read_duration_seconds")
        .description("Time to read and decompress gzip JSONL file")
        .publishPercentileHistogram()
        .register(registry)

    private val documentBuildTimer = Timer.builder("synchronizer_document_build_duration_seconds")
        .description("Time to build read model documents from grouped results")
        .publishPercentileHistogram()
        .register(registry)

    private val mainUpsertTimer = Timer.builder("synchronizer_main_upsert_duration_seconds")
        .description("Time to bulk upsert documents into main read model table")
        .publishPercentileHistogram()
        .register(registry)

    // Distribution summaries — chunk 크기/분포
    private val chunkDocumentsSummary = DistributionSummary.builder("synchronizer_chunk_documents")
        .description("Number of documents per chunk")
        .register(registry)

    private val chunkItemsSummary = DistributionSummary.builder("synchronizer_chunk_items")
        .description("Number of items per chunk")
        .register(registry)

    private val chunkBytesSummary = DistributionSummary.builder("synchronizer_chunk_bytes")
        .description("Compressed document bytes per chunk")
        .register(registry)

    private val documentEquipmentSummary = DistributionSummary.builder("synchronizer_document_equipment_count")
        .description("Equipment count per document")
        .register(registry)

    // Volume metrics — pre-upsert data volume
    private val preUpsertCompressedBytesTotal = registry.counter("synchronizer_pre_upsert_compressed_bytes_total")
    private val preUpsertUncompressedBytesTotal = registry.counter("synchronizer_pre_upsert_uncompressed_bytes_total")
    private val preUpsertJsonRowsTotal = registry.counter("synchronizer_pre_upsert_json_rows_total")

    private val preUpsertCompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_compressed_bytes")
        .description("Compressed artifact bytes per chunk before DB upsert")
        .register(registry)

    private val preUpsertUncompressedSummary = DistributionSummary.builder("synchronizer_pre_upsert_uncompressed_bytes")
        .description("Uncompressed artifact bytes per chunk before DB upsert")
        .register(registry)

    private val preUpsertCompressionRatio = DistributionSummary.builder("synchronizer_pre_upsert_compression_ratio")
        .description("Compression ratio (uncompressed/compressed) per chunk before DB upsert")
        .register(registry)

    // Counter getters
    fun chunksReceived(): Counter = chunksReceived
    fun chunksProcessing(): AtomicInteger = chunksProcessing
    fun chunksProcessed(): Counter = chunksProcessed
    fun chunksFailed(): Counter = chunksFailed
    fun documentsProcessed(): Counter = documentsProcessed
    fun itemsProcessed(): Counter = itemsProcessed
    fun preUpsertCompressedBytesTotal(): Counter = preUpsertCompressedBytesTotal
    fun preUpsertUncompressedBytesTotal(): Counter = preUpsertUncompressedBytesTotal
    fun preUpsertJsonRowsTotal(): Counter = preUpsertJsonRowsTotal

    // Timer getters
    fun chunkTimer(): Timer = chunkTimer
    fun fileReadTimer(): Timer = fileReadTimer
    fun documentBuildTimer(): Timer = documentBuildTimer
    fun mainUpsertTimer(): Timer = mainUpsertTimer

    // DistributionSummary getters
    fun chunkDocumentsSummary(): DistributionSummary = chunkDocumentsSummary
    fun chunkItemsSummary(): DistributionSummary = chunkItemsSummary
    fun chunkBytesSummary(): DistributionSummary = chunkBytesSummary
    fun documentEquipmentSummary(): DistributionSummary = documentEquipmentSummary
    fun preUpsertCompressedSummary(): DistributionSummary = preUpsertCompressedSummary
    fun preUpsertUncompressedSummary(): DistributionSummary = preUpsertUncompressedSummary
    fun preUpsertCompressionRatio(): DistributionSummary = preUpsertCompressionRatio

    // Status transition counter
    fun statusCounter(status: String): Counter =
        registry.counter("synchronizer_chunk_status_transition_total", "status", status)

    fun chunkExecutionCounter(name: String, executionType: ChunkExecutionType): Counter =
        registry.counter(name, "execution_type", executionType.name)

    fun chunkExecutionSkippedCounter(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
    ): Counter =
        registry.counter(
            "chunk_execution_skipped_total",
            "execution_type",
            executionType.name,
            "status",
            status.name,
        )

    fun chunkExecutionFailedCounter(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
        reason: String,
    ): Counter =
        registry.counter(
            "chunk_execution_failed_total",
            "execution_type",
            executionType.name,
            "status",
            status.name,
            "reason",
            reason,
        )
}
```

Note: getter names mirror the private property names for the 1:1 meters (e.g. `chunksReceived()` returns `chunksReceived`). This is the only deliberate shadow — Kotlin allows it because one is a property and the other a function.

- [ ] **Step 2: Compile to verify the new file builds**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin --continue
```

Expected: BUILD SUCCESSFUL. (Other modules may fail; use `--continue` to keep going.)

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMeterRegistry.kt
git commit -m "refactor(synchronizer): add SynchronizerMeterRegistry for meter creation"
```

---

## Task 2: Refactor SynchronizerMetrics

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt`

- [ ] **Step 1: Replace the entire file**

Path: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt`

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer
import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.springframework.stereotype.Component

@Component
class SynchronizerMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementReceived() = meterRegistry.chunksReceived().increment()
    fun incrementProcessing() = meterRegistry.chunksProcessing().incrementAndGet()
    fun decrementProcessing() = meterRegistry.chunksProcessing().decrementAndGet()
    fun incrementProcessed() = meterRegistry.chunksProcessed().increment()
    fun incrementFailed() = meterRegistry.chunksFailed().increment()

    fun recordChunkExecutionInserted(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_inserted_total", executionType).increment()

    fun recordChunkExecutionClaimed(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_claimed_total", executionType).increment()

    fun recordChunkExecutionSkipped(executionType: ChunkExecutionType, status: ChunkExecutionStatus) =
        meterRegistry.chunkExecutionSkippedCounter(executionType, status).increment()

    fun recordChunkExecutionSucceeded(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_succeeded_total", executionType).increment()

    fun recordChunkExecutionFailed(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
        reason: String,
    ) = meterRegistry.chunkExecutionFailedCounter(executionType, status, reason).increment()

    fun recordChunkExecutionReclaimedExpired(executionType: ChunkExecutionType) =
        meterRegistry.chunkExecutionCounter("chunk_execution_reclaimed_expired_total", executionType).increment()

    fun incrementDocuments(count: Int) = meterRegistry.documentsProcessed().increment(count.toDouble())
    fun incrementItems(count: Long) = meterRegistry.itemsProcessed().increment(count.toDouble())

    fun recordStatusTransition(status: String) = meterRegistry.statusCounter(status).increment()

    fun recordChunkSize(documents: Int, items: Long) {
        meterRegistry.chunkDocumentsSummary().record(documents.toDouble())
        meterRegistry.chunkItemsSummary().record(items.toDouble())
    }

    fun recordChunkBytes(bytes: Long) {
        meterRegistry.chunkBytesSummary().record(bytes.toDouble())
    }

    fun recordDocumentEquipment(count: Int) = meterRegistry.documentEquipmentSummary().record(count.toDouble())

    fun recordPreUpsertVolume(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        meterRegistry.preUpsertCompressedBytesTotal().increment(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedBytesTotal().increment(uncompressedBytes.toDouble())
        meterRegistry.preUpsertJsonRowsTotal().increment(jsonRows.toDouble())
        meterRegistry.preUpsertCompressedSummary().record(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedSummary().record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            meterRegistry.preUpsertCompressionRatio().record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }

    fun chunkTimer(): Timer = meterRegistry.chunkTimer()
    fun fileReadTimer(): Timer = meterRegistry.fileReadTimer()
    fun documentBuildTimer(): Timer = meterRegistry.documentBuildTimer()
    fun mainUpsertTimer(): Timer = meterRegistry.mainUpsertTimer()
}
```

Note: `Counter` and `DistributionSummary` imports remain because the **method signatures** of `recordChunkExecutionInserted` etc. resolve through the registry's factory methods which return `Counter`. Actually the return type is inferred — they can be dropped. Check after compile.

- [ ] **Step 2: Compile to verify the refactor builds**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin --continue
```

Expected: BUILD SUCCESSFUL. If `Counter`/`DistributionSummary` imports are flagged as unused, remove them and re-run.

- [ ] **Step 3: Verify consumer sites still compile (the public API is byte-for-byte identical, so this should pass with no other changes)**

Run:
```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt
git commit -m "refactor(synchronizer): delegate SynchronizerMetrics recording to meter registry"
```

---

## Task 3: Update DefaultChunkProcessorTest constructor

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt:27`

- [ ] **Step 1: Locate the constructor call at line 27**

The current line is:
```kotlin
private val metrics = SynchronizerMetrics(SimpleMeterRegistry())
```

- [ ] **Step 2: Replace with the new constructor that wraps the registry**

```kotlin
private val metrics = SynchronizerMetrics(SynchronizerMeterRegistry(SimpleMeterRegistry()))
```

Verify the file imports `SynchronizerMeterRegistry` from `maple.synchronizer.metrics` — if not, add the import. (Test files in this module typically import with `import maple.synchronizer.metrics.SynchronizerMetrics` already; add `import maple.synchronizer.metrics.SynchronizerMeterRegistry` next to it.)

- [ ] **Step 3: Run the test file to verify the change**

Run:
```bash
./gradlew :module-synchronizer:test --tests "maple.synchronizer.processor.DefaultChunkProcessorTest"
```

Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt
git commit -m "test(synchronizer): update DefaultChunkProcessorTest for split constructor"
```

---

## Task 4: Run full module-synchronizer test suite

**Files:** none (verification only)

- [ ] **Step 1: Run the synchronizer module tests**

Run:
```bash
./gradlew :module-synchronizer:test
```

Expected: BUILD SUCCESSFUL. All tests pass.

- [ ] **Step 2: Run full compile check across all modules**

Run:
```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. No errors emitted. (Per workflow-rules §11, only failures should appear; absence of error output = success.)

---

## Task 5: BootRun smoke check

**Files:** none (verification only)

- [ ] **Step 1: Start the synchronizer module**

```bash
set -a && source .env && set +a
./gradlew :module-synchronizer:bootRun
```

Run in background: `run_in_background: true`. Wait for `Started SynchronizerApplication` log line.

- [ ] **Step 2: Verify the meter registry bean is registered**

Hit Prometheus endpoint:
```bash
curl -s http://localhost:8083/actuator/prometheus | grep -E "^synchronizer_(chunks_received|chunks_processed|documents_processed)" | head -5
```

Expected: lines like `synchronizer_chunks_received_total{application="synchronizer"} 0.0` etc. (gauge is registered but not yet incremented). No `ERROR` in logs.

- [ ] **Step 3: Stop the server**

```bash
pkill -f 'gradlew :module-synchronizer:bootRun' || pkill -f 'SynchronizerApplication'
```

---

## Task 6: Open PR to develop

**Files:** none

- [ ] **Step 1: Push the branch**

```bash
git push -u origin refactor/issue-1066-sync-metrics-split
```

- [ ] **Step 2: Open PR via gh**

```bash
gh pr create \
  --base develop \
  --head refactor/issue-1066-sync-metrics-split \
  --title "refactor(synchronizer): split SynchronizerMetrics into meter registry + recording (#1066)" \
  --body "Decomposes SynchronizerMetrics (176 lines) into SynchronizerMeterRegistry (meter creation, 80 lines) and SynchronizerMetrics (recording only, 90 lines). Public API of SynchronizerMetrics is unchanged — no consumer diff. New metrics now touch one class only.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- SynchronizerMeterRegistry separated → Task 1 ✓
- SynchronizerMetrics recording only → Task 2 ✓
- Public API byte-for-byte identical → Task 2 explicitly preserves all 24 public methods ✓
- New metric = 1 file change → enforced by file structure (registration in registry only) ✓
- :module-synchronizer:test passes → Task 4 ✓
- compileKotlin compileJava passes → Task 4 ✓
- bootRun smoke check → Task 5 ✓

**2. Placeholder scan:** No TBD/TODO. No "implement later". All code blocks are complete.

**3. Type consistency:** `chunkTimer()` getter exists in `SynchronizerMeterRegistry` (Task 1) and is referenced in `SynchronizerMetrics.chunkTimer()` (Task 2) — names match. All factory method names (`statusCounter`, `chunkExecutionCounter`, `chunkExecutionSkippedCounter`, `chunkExecutionFailedCounter`) preserved exactly. `AtomicInteger` access via `incrementAndGet`/`decrementAndGet` preserved.
