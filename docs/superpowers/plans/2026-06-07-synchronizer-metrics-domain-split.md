# #992 — SynchronizerMetrics Domain Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Split `SynchronizerMetrics` (70 lines, 22 methods, 5 domains) into 3 cohesive `@Component` classes: `ChunkExecutionMetrics` (state-machine counters), `DocumentVolumeMetrics` (volume + pre-upsert), and a slimmed `SynchronizerMetrics` (chunk lifecycle + timers). No Prometheus surface change.

**Architecture:** Three `@Component` classes each own one cluster of related Prometheus meters. All inject `SynchronizerMeterRegistry` (unchanged). Caller migration: `ChunkConsumerTemplate` → `ChunkExecutionMetrics`; `DefaultChunkProcessor` + `SynchronizerChunkMetricsListener` (preUpsertVolume) → `DocumentVolumeMetrics`. Timer-accessor callers keep `SynchronizerMetrics`.

**Tech Stack:** Kotlin, Spring Boot, Micrometer (Counter / DistributionSummary / Timer), Gradle multi-module, JUnit 5 + Mockito Kotlin.

---

## File Structure

| File | Change |
|---|---|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/ChunkExecutionMetrics.kt` | NEW — 6 chunk-execution state-machine methods |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/DocumentVolumeMetrics.kt` | NEW — 5 document/item/pre-upsert volume methods |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt` | MODIFIED — drops 11 methods, keeps 9 |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt` | MODIFIED — inject `ChunkExecutionMetrics`, replace 7 call sites |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt` | MODIFIED — inject `DocumentVolumeMetrics`, replace 3 call sites |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerChunkMetricsListener.kt` | MODIFIED — inject `DocumentVolumeMetrics`, replace 1 call site |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/ChunkExecutionMetricsTest.kt` | NEW — 6 unit tests |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/DocumentVolumeMetricsTest.kt` | NEW — 5 unit tests |

---

## Task 1: Create `ChunkExecutionMetrics`

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/ChunkExecutionMetrics.kt`

- [ ] **Step 1: Create the class**

```kotlin
package maple.synchronizer.metrics

import maple.synchronizer.state.ChunkExecutionStatus
import maple.expectation.common.event.ChunkExecutionType
import org.springframework.stereotype.Component

/**
 * Owns chunk-execution state-machine counters (inserted / claimed / skipped /
 * succeeded / failed / reclaimed). Delegates actual meter creation to
 * [SynchronizerMeterRegistry] so the per-tag factory logic stays in one place.
 */
@Component
class ChunkExecutionMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

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
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin --console=plain
```

Expected: SUCCESS (no consumers yet — class is new and unused).

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/ChunkExecutionMetrics.kt
git commit -m "feat(synchronizer): add ChunkExecutionMetrics (#992)"
```

---

## Task 2: Create `DocumentVolumeMetrics`

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/DocumentVolumeMetrics.kt`

- [ ] **Step 1: Create the class**

```kotlin
package maple.synchronizer.metrics

import org.springframework.stereotype.Component

/**
 * Owns document / item volume counters and pre-upsert data-volume metrics
 * (compressed / uncompressed bytes, JSON row count, compression ratio).
 * Delegates actual meter creation to [SynchronizerMeterRegistry].
 */
@Component
class DocumentVolumeMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementDocuments(count: Int) = meterRegistry.documentsProcessed.increment(count.toDouble())

    fun incrementItems(count: Long) = meterRegistry.itemsProcessed.increment(count.toDouble())

    fun recordChunkSize(documents: Int, items: Long) {
        meterRegistry.chunkDocumentsSummary.record(documents.toDouble())
        meterRegistry.chunkItemsSummary.record(items.toDouble())
    }

    fun recordDocumentEquipment(count: Int) = meterRegistry.documentEquipmentSummary.record(count.toDouble())

    fun recordPreUpsertVolume(compressedBytes: Long, uncompressedBytes: Long, jsonRows: Long) {
        meterRegistry.preUpsertCompressedBytesTotal.increment(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedBytesTotal.increment(uncompressedBytes.toDouble())
        meterRegistry.preUpsertJsonRowsTotal.increment(jsonRows.toDouble())
        meterRegistry.preUpsertCompressedSummary.record(compressedBytes.toDouble())
        meterRegistry.preUpsertUncompressedSummary.record(uncompressedBytes.toDouble())
        if (compressedBytes > 0) {
            meterRegistry.preUpsertCompressionRatio.record(uncompressedBytes.toDouble() / compressedBytes.toDouble())
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin --console=plain
```

Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/DocumentVolumeMetrics.kt
git commit -m "feat(synchronizer): add DocumentVolumeMetrics (#992)"
```

---

## Task 3: Slim down `SynchronizerMetrics`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Owns chunk-lifecycle counters (received / processing / processed / failed),
 * the status-transition counter, the per-chunk compressed-bytes summary,
 * and timer accessors for the synchronizer pipeline stages.
 *
 * Domain-specific metric surfaces (chunk-execution state machine, document /
 * item volume, pre-upsert volume) live in [ChunkExecutionMetrics] and
 * [DocumentVolumeMetrics].
 */
@Component
class SynchronizerMetrics(private val meterRegistry: SynchronizerMeterRegistry) {

    fun incrementReceived() = meterRegistry.chunksReceived.increment()

    fun incrementProcessing() = meterRegistry.chunksProcessing.incrementAndGet()

    fun decrementProcessing() = meterRegistry.chunksProcessing.decrementAndGet()

    fun incrementProcessed() = meterRegistry.chunksProcessed.increment()

    fun incrementFailed() = meterRegistry.chunksFailed.increment()

    fun recordStatusTransition(status: String) = meterRegistry.statusCounter(status).increment()

    fun recordChunkBytes(bytes: Long) {
        meterRegistry.chunkBytesSummary.record(bytes.toDouble())
    }

    fun chunkTimer(): Timer = meterRegistry.chunkTimer

    fun fileReadTimer(): Timer = meterRegistry.fileReadTimer

    fun documentBuildTimer(): Timer = meterRegistry.documentBuildTimer

    fun mainUpsertTimer(): Timer = meterRegistry.mainUpsertTimer
}
```

- [ ] **Step 2: Compile (expect failures — callers still use the removed methods)**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin --console=plain 2>&1 | tail -30
```

Expected: COMPILATION FAILURE listing `recordChunkExecution*` / `incrementDocuments` / `incrementItems` / `recordChunkSize` / `recordDocumentEquipment` / `recordPreUpsertVolume` unresolved on `SynchronizerMetrics`. This is the safety net — the next 3 tasks fix the callers.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt
git commit -m "refactor(synchronizer): drop 11 methods from SynchronizerMetrics (#992)"
```

---

## Task 4: Migrate `ChunkConsumerTemplate` to `ChunkExecutionMetrics`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`

- [ ] **Step 1: Update imports and constructor**

Find the import block (around line 9) and replace the `SynchronizerMetrics` import with `ChunkExecutionMetrics`:

```kotlin
import maple.synchronizer.metrics.ChunkExecutionMetrics
```

(remove the old `import maple.synchronizer.metrics.SynchronizerMetrics` line — it is no longer used after migration)

The constructor currently declares `private val metrics: SynchronizerMetrics`. Since all 8 `metrics.recordChunkExecution*` call sites are migrated, the `metrics` field is no longer used by this class. **Remove** the `metrics` parameter and add `executionMetrics` instead:

```kotlin
class ChunkConsumerTemplate(
    // ... existing parameters ...
    private val executionMetrics: ChunkExecutionMetrics,
) {
```

- [ ] **Step 2: Replace the 7 call sites**

Inside the class body, replace every `metrics.recordChunkExecution*` with `executionMetrics.recordChunkExecution*`. The 7 sites are at lines 31, 53, 88, 92, 94, 145, 172, 223 (note: 6 unique method names, but 7 call sites — `recordChunkExecutionFailed` is called from two places).

```bash
# Verify the substitution is exhaustive:
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
grep -n "metrics\.recordChunkExecution" module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
```

Expected: no output. If any line still references `metrics.recordChunkExecution*`, replace it.

- [ ] **Step 3: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin --console=plain 2>&1 | tail -20
```

Expected: COMPILATION FAILURE only for the remaining callers (`DefaultChunkProcessor`, `SynchronizerChunkMetricsListener`). If the failure list is empty here, that means other callers don't reference removed methods — proceed to Task 5 / 6 anyway since the issue requires those migrations.

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
git commit -m "refactor(synchronizer): ChunkConsumerTemplate injects ChunkExecutionMetrics (#992)"
```

---

## Task 5: Migrate `DefaultChunkProcessor` to `DocumentVolumeMetrics`

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt`

- [ ] **Step 1: Update imports and constructor**

Add the import:

```kotlin
import maple.synchronizer.metrics.DocumentVolumeMetrics
```

(remove the old `import maple.synchronizer.metrics.SynchronizerMetrics` line if present — all 4 call sites are migrated away)

The constructor currently declares `private val metrics: SynchronizerMetrics`. Since all 4 call sites (`incrementDocuments`, `incrementItems`, `recordChunkSize`, `recordDocumentEquipment`) are migrated, the `metrics` field is no longer used. **Remove** the `metrics` parameter and add `volumeMetrics`:

```kotlin
class DefaultChunkProcessor(
    // ... existing parameters ...
    private val volumeMetrics: DocumentVolumeMetrics,
) {
```

- [ ] **Step 2: Replace the 3 call sites**

Inside the class body, replace:

- `metrics.incrementDocuments(...)` → `volumeMetrics.incrementDocuments(...)`
- `metrics.incrementItems(...)` → `volumeMetrics.incrementItems(...)`
- `metrics.recordChunkSize(...)` → `volumeMetrics.recordChunkSize(...)`
- `metrics.recordDocumentEquipment(...)` → `volumeMetrics.recordDocumentEquipment(...)`

Verify with:

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
grep -nE "metrics\.(incrementDocuments|incrementItems|recordChunkSize|recordDocumentEquipment)" \
  module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt
git commit -m "refactor(synchronizer): DefaultChunkProcessor injects DocumentVolumeMetrics (#992)"
```

---

## Task 6: Migrate `SynchronizerChunkMetricsListener` `recordPreUpsertVolume` call

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerChunkMetricsListener.kt`

- [ ] **Step 1: Update imports and constructor**

Add the import:

```kotlin
import maple.synchronizer.metrics.DocumentVolumeMetrics
```

The constructor currently declares `private val metrics: SynchronizerMetrics`. Add the new parameter:

```kotlin
class SynchronizerChunkMetricsListener(
    // ... existing parameters ...
    private val metrics: SynchronizerMetrics,
    private val volumeMetrics: DocumentVolumeMetrics,
) {
```

- [ ] **Step 2: Replace the preUpsert call site**

Find the call (around line 29):

```kotlin
metrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount)
```

Replace with:

```kotlin
volumeMetrics.recordPreUpsertVolume(event.compressedBytes, event.uncompressedBytes, event.resultCount)
```

Verify with:

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
grep -n "metrics\.recordPreUpsertVolume" \
  module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerChunkMetricsListener.kt
```

Expected: no output.

- [ ] **Step 3: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin --console=plain 2>&1 | tail -20
```

Expected: SUCCESS. The 3 callers all use their own metric beans.

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerChunkMetricsListener.kt
git commit -m "refactor(synchronizer): SynchronizerChunkMetricsListener routes preUpsertVolume to DocumentVolumeMetrics (#992)"
```

---

## Task 7: Add `ChunkExecutionMetricsTest`

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/ChunkExecutionMetricsTest.kt`

- [ ] **Step 1: Create the test**

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.expectation.common.event.ChunkExecutionType
import maple.synchronizer.state.ChunkExecutionStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChunkExecutionMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val meterRegistry = SynchronizerMeterRegistry(registry)
    private val metrics = ChunkExecutionMetrics(meterRegistry)

    @Test
    fun `recordChunkExecutionInserted increments chunk_execution_inserted_total`() {
        metrics.recordChunkExecutionInserted(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter: Counter = registry.find("chunk_execution_inserted_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionClaimed increments chunk_execution_claimed_total`() {
        metrics.recordChunkExecutionClaimed(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter: Counter = registry.find("chunk_execution_claimed_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionSkipped increments with status tag`() {
        metrics.recordChunkExecutionSkipped(
            ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            ChunkExecutionStatus.Succeeded,
        )

        val counter: Counter = registry.find("chunk_execution_skipped_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .tag("status", "SUCCEEDED")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionSucceeded increments chunk_execution_succeeded_total`() {
        metrics.recordChunkExecutionSucceeded(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter: Counter = registry.find("chunk_execution_succeeded_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionFailed increments with status and reason tags`() {
        metrics.recordChunkExecutionFailed(
            ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK,
            ChunkExecutionStatus.FailedRetryable(java.time.Instant.now()),
            "TIMEOUT",
        )

        val counter: Counter = registry.find("chunk_execution_failed_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .tag("status", "FAILED_RETRYABLE")
            .tag("reason", "TIMEOUT")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }

    @Test
    fun `recordChunkExecutionReclaimedExpired increments chunk_execution_reclaimed_expired_total`() {
        metrics.recordChunkExecutionReclaimedExpired(ChunkExecutionType.SYNCHRONIZER_RESULT_CHUNK)

        val counter: Counter = registry.find("chunk_execution_reclaimed_expired_total")
            .tag("execution_type", "SYNCHRONIZER_RESULT_CHUNK")
            .counter()
        assertThat(counter.count()).isEqualTo(1.0)
    }
}
```

> **Note:** `ChunkExecutionStatus.FailedRetryable` is a `data class` with an `Instant nextRetryAt` field. If the actual class shape differs, use the constructor that matches the codebase. Verify by reading `module-synchronizer/src/main/kotlin/maple/synchronizer/state/ChunkExecutionStatus.kt` before writing this test.

- [ ] **Step 2: Run the test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:test --tests "maple.synchronizer.metrics.ChunkExecutionMetricsTest" --console=plain
```

Expected: 6 tests passed.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/ChunkExecutionMetricsTest.kt
git commit -m "test(synchronizer): ChunkExecutionMetrics unit tests (#992)"
```

---

## Task 8: Add `DocumentVolumeMetricsTest`

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/DocumentVolumeMetricsTest.kt`

- [ ] **Step 1: Create the test**

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentVolumeMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val meterRegistry = SynchronizerMeterRegistry(registry)
    private val metrics = DocumentVolumeMetrics(meterRegistry)

    @Test
    fun `incrementDocuments adds to documentsProcessed counter`() {
        metrics.incrementDocuments(7)
        metrics.incrementDocuments(3)

        val counter = registry.find("synchronizer_documents_processed_total").counter()
        assertThat(counter.count()).isEqualTo(10.0)
    }

    @Test
    fun `incrementItems adds to itemsProcessed counter`() {
        metrics.incrementItems(100L)
        metrics.incrementItems(50L)

        val counter = registry.find("synchronizer_items_processed_total").counter()
        assertThat(counter.count()).isEqualTo(150.0)
    }

    @Test
    fun `recordChunkSize records both documents and items summaries`() {
        metrics.recordChunkSize(documents = 12, items = 345L)

        val docSummary = registry.find("synchronizer_chunk_documents").summary()
        val itemSummary = registry.find("synchronizer_chunk_items").summary()
        assertThat(docSummary.count()).isEqualTo(1L)
        assertThat(docSummary.totalAmount()).isEqualTo(12.0)
        assertThat(itemSummary.count()).isEqualTo(1L)
        assertThat(itemSummary.totalAmount()).isEqualTo(345.0)
    }

    @Test
    fun `recordDocumentEquipment records per-document equipment count summary`() {
        metrics.recordDocumentEquipment(8)
        metrics.recordDocumentEquipment(2)

        val summary = registry.find("synchronizer_document_equipment_count").summary()
        assertThat(summary.count()).isEqualTo(2L)
        assertThat(summary.totalAmount()).isEqualTo(10.0)
    }

    @Test
    fun `recordPreUpsertVolume increments totals records summaries and computes ratio when compressed non-zero`() {
        metrics.recordPreUpsertVolume(compressedBytes = 100L, uncompressedBytes = 400L, jsonRows = 12L)

        val compressedCounter = registry.find("synchronizer_pre_upsert_compressed_bytes_total").counter()
        val uncompressedCounter = registry.find("synchronizer_pre_upsert_uncompressed_bytes_total").counter()
        val rowsCounter = registry.find("synchronizer_pre_upsert_json_rows_total").counter()
        assertThat(compressedCounter.count()).isEqualTo(100.0)
        assertThat(uncompressedCounter.count()).isEqualTo(400.0)
        assertThat(rowsCounter.count()).isEqualTo(12.0)

        val ratio = registry.find("synchronizer_pre_upsert_compression_ratio").summary()
        assertThat(ratio.count()).isEqualTo(1L)
        assertThat(ratio.totalAmount()).isEqualTo(4.0) // 400/100
    }

    @Test
    fun `recordPreUpsertVolume skips ratio when compressedBytes is zero`() {
        metrics.recordPreUpsertVolume(compressedBytes = 0L, uncompressedBytes = 100L, jsonRows = 0L)

        val ratio = registry.find("synchronizer_pre_upsert_compression_ratio").summary()
        assertThat(ratio.count()).isEqualTo(0L)
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:test --tests "maple.synchronizer.metrics.DocumentVolumeMetricsTest" --console=plain
```

Expected: 6 tests passed.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/metrics/DocumentVolumeMetricsTest.kt
git commit -m "test(synchronizer): DocumentVolumeMetrics unit tests (#992)"
```

---

## Task 9: Full verify

- [ ] **Step 1: Full compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:compileKotlin compileJava --console=plain 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full test suite**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
./gradlew :module-synchronizer:test --console=plain 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 3: Verify no remaining dead references**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
grep -rn "metrics\.recordChunkExecution\|metrics\.incrementDocuments\|metrics\.incrementItems\|metrics\.recordChunkSize\|metrics\.recordDocumentEquipment\|metrics\.recordPreUpsertVolume" \
  module-synchronizer/src/main --include="*.kt"
```

Expected: no output. If anything remains, fix the caller to use the new bean.

- [ ] **Step 4: Verify Prometheus name preservation**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/issue-992
grep -rn "synchronizer_chunks_received\|synchronizer_chunks_processing\|synchronizer_chunks_processed\|synchronizer_chunks_failed\|synchronizer_chunk_status_transition\|synchronizer_chunk_bytes\|synchronizer_chunk_duration\|synchronizer_file_read\|synchronizer_document_build\|synchronizer_main_upsert" \
  module-synchronizer/src/main --include="*.kt"
```

Expected: all match the original `SynchronizerMeterRegistry` declarations. No rename.

- [ ] **Step 5: Commit (if any cleanup was needed)**

```bash
git add -A
git diff --cached --quiet || git commit -m "chore(synchronizer): post-#992 cleanup"
```

---

## Self-Review

**Spec coverage:**
- ✅ 2 new classes created — Tasks 1, 2
- ✅ Existing `SynchronizerMetrics` slimmed — Task 3
- ✅ Caller migration — Tasks 4, 5, 6
- ✅ No Prometheus name/tag change — Task 9 verifies
- ✅ Compile + test pass — Task 9

**Placeholder scan:** none. Every step has actual code or commands.

**Type consistency:** `ChunkExecutionMetrics` and `DocumentVolumeMetrics` method signatures match between Task 1, 2, 7, 8 and the caller migration Tasks 4, 5, 6.
