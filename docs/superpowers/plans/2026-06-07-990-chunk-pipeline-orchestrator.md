# Chunk Pipeline Orchestrator (PR2 of #990) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `ChunkPipelineOrchestrator` and refactor `DefaultChunkProcessor` to a thin delegate. Pipeline stage chain assembly becomes explicit, with aggregate metrics in the orchestrator.

**Architecture:** New `ChunkPipelineOrchestrator` Spring `@Component` in `module-synchronizer/adapter/chunk/`. Takes the three existing stage beans (`ChunkDataReader`, `ChunkDocumentTransformer`, `ChunkDocumentWriter`) plus `SynchronizerMetrics` as constructor deps. `DefaultChunkProcessor` becomes a 1-line delegate to `orchestrator.execute()` for backward compat with `KafkaResultChunkConsumer` which depends on the `ChunkProcessor` interface. No stage class renames, no port interface migration (deferred to follow-up issue).

**Out of scope (deferred to follow-up issues):**
- Port interface migration: `ChunkDataReader` / `ChunkDocumentTransformer` / `ChunkDocumentWriter` do NOT implement `module-core/.../ChunkReader` / `ChunkTransformer` / `ChunkWriter` yet. That is full spec PR2. This plan covers orchestrator only.
- Removing `DefaultChunkProcessor`: kept as a 1-line `@Deprecated` delegate. Full removal = future PR that updates `KafkaResultChunkConsumer` to inject `ChunkPipelineOrchestrator` directly.
- `BasicChunkIngestionService` is on a separate code path (uses `fileReader.readInBatches` + `repository.bulkUpsert` + `upsertOcidFromBasicRecords`, NOT `DefaultChunkProcessor`). This refactor does not touch it.

**Tech Stack:** Kotlin, Spring, JUnit5, Mockito, AssertJ, Micrometer.

**Spec Reference:** `docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md` §4, §6 PR2 (partial)

**Issue:** #990

**Issue #990 Acceptance Criteria:**
- [ ] Pipeline stage classes independent (read, build, persist) — already true post-#1143
- [ ] `DefaultChunkProcessor` = stage chain assembly only (delegate)
- [ ] Behavior unchanged (chunk result identical)
- [ ] `./gradlew compileKotlin compileJava --continue` passes
- [ ] `./gradlew test` passes

---

## File Structure

```
module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/
└── ChunkPipelineOrchestrator.kt        (new — ~50 lines)

module-synchronizer/src/test/kotlin/maple/synchronizer/adapter/chunk/
└── ChunkPipelineOrchestratorTest.kt    (new — TDD-driven)

module-synchronizer/src/main/kotlin/maple/synchronizer/processor/
├── ChunkDataReader.kt                  (unchanged)
├── ChunkDocumentTransformer.kt         (unchanged)
├── ChunkDocumentWriter.kt              (unchanged)
├── ChunkProcessor.kt                   (unchanged)
└── DefaultChunkProcessor.kt            (modified — 1-line delegate to orchestrator)

module-synchronizer/src/test/kotlin/maple/synchronizer/processor/
└── DefaultChunkProcessorTest.kt        (unchanged — already mocks stages, will still pass)
```

No file moves. No new modules. Backward compatible with `KafkaResultChunkConsumer`.

---

## Task 0: Setup — worktree + ADR

Per project workflow: implementation work requires a worktree and an ADR before any code change.

- [ ] **Step 1: Create worktree from develop**

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin
git worktree add ../refactor-990-chunk-pipeline -b refactor/990-chunk-pipeline origin/develop
cd ../refactor-990-chunk-pipeline
```

- [ ] **Step 2: Write ADR-026 documenting the orchestrator + delegate pattern**

Create `docs/01_ADR/ADR-026-chunk-pipeline-orchestrator.md` with these required sections (per `.claude/rules/adr-conventions.md`):

```markdown
# ADR-026: Chunk Pipeline Orchestrator + Thin Delegate

- Status: Accepted
- Date: 2026-06-07
- Owner: synchronizer

## 1. Background / Problem

`DefaultChunkProcessor` mixes 5 responsibilities and aggregate metrics in one method. Issue #990 (PR2 of #1143 stage-split) wants explicit stage chain assembly.

## 2. Decision

Extract pipeline assembly to `ChunkPipelineOrchestrator` (new `@Component` in `module-synchronizer/adapter/chunk/`). Keep `DefaultChunkProcessor` as a 1-line `@Deprecated` delegate to preserve the `ChunkProcessor` contract that `KafkaResultChunkConsumer` depends on.

```text
KafkaResultChunkConsumer → ChunkProcessor (interface) → DefaultChunkProcessor (delegate) → ChunkPipelineOrchestrator
                                                                                          ├── ChunkDataReader
                                                                                          ├── ChunkDocumentTransformer
                                                                                          ├── ChunkDocumentWriter
                                                                                          └── SynchronizerMetrics
```

## 3. Trade-offs

### Sensitivity
* Future stage addition frequency (target: 0/quarter, expected 1-2/quarter)
* Number of stages in the chain (currently 3)

### Trade-off
| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Orchestrator + thin delegate (this PR) | Backward compat, no consumer change, isolated blast radius | Two bean types for one job until follow-up removal |
| Full removal of DefaultChunkProcessor (full spec PR2) | One bean, single source of truth | Touches `KafkaResultChunkConsumer` and the `ChunkProcessor` interface, wider diff |
| Port interface migration (full spec PR2) | Generic stage composition across modules | Requires all 3 stage classes to be `suspend` + change signatures |

### Risk
* Two beans (`DefaultChunkProcessor` + `ChunkPipelineOrchestrator`) doing the same thing for some time — mitigated by `@Deprecated` on the delegate and follow-up removal issue.
* `BasicChunkIngestionService` is a separate code path; not affected by this ADR.

### Non-Risk
* Stage class internals unchanged.
* `ChunkProcessor` interface unchanged.
* Concurrency model (Semaphore, executor) unchanged.

## 4. Result / Evidence

To be filled after merge: test counts, line counts, follow-up issue opened.

## 5. Summary

> Add `ChunkPipelineOrchestrator` and reduce `DefaultChunkProcessor` to a 1-line delegate. Port interface migration deferred to a follow-up issue.
```

- [ ] **Step 3: Commit ADR**

```bash
git add docs/01_ADR/ADR-026-chunk-pipeline-orchestrator.md
git commit -m "docs(adr): ADR-026 chunk pipeline orchestrator + thin delegate (#990)"
```

---

## Task 1: Write failing test for ChunkPipelineOrchestrator (happy path)

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestratorTest.kt`

- [ ] **Step 1: Create the test file with the happy path test**

```kotlin
package maple.synchronizer.adapter.chunk

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMeterRegistry
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.PreppedDocument
import maple.synchronizer.processor.ChunkDataReader
import maple.synchronizer.processor.ChunkDocumentTransformer
import maple.synchronizer.processor.ChunkDocumentWriter
import maple.synchronizer.processor.TransformResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class ChunkPipelineOrchestratorTest {

    private val dataReader: ChunkDataReader = mock()
    private val transformer: ChunkDocumentTransformer = mock()
    private val writer: ChunkDocumentWriter = mock()
    private val metrics = SynchronizerMetrics(SynchronizerMeterRegistry(SimpleMeterRegistry()))

    private lateinit var orchestrator: ChunkPipelineOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = ChunkPipelineOrchestrator(dataReader, transformer, writer, metrics)
    }

    @Test
    fun `execute - happy path returns result with correct counts`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = orchestrator.execute(input)

        assertThat(result.documentCount).isEqualTo(1)
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.jsonRowCount).isEqualTo(input.resultCount.toLong())
    }

    @Test
    fun `execute - calls stages in order read then transform then write`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(1, 1, prepped))

        orchestrator.execute(input)

        verify(dataReader).read(eq(input.objectKey))
        verify(transformer).transform(eq(input.sourceRunId), eq(input.sourceChunkId), eq(grouped))
        verify(writer).write(eq(input.sourceRunId), eq(input.sourceChunkId), eq(prepped))
    }

    @Test
    fun `execute - records aggregate metrics for documents and items`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock(), mock(), mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(documentCount = 3, itemCount = 7, prepped = prepped))

        orchestrator.execute(input)

        org.mockito.kotlin.verify(metrics).incrementDocuments(3)
        org.mockito.kotlin.verify(metrics).incrementItems(7L)
        org.mockito.kotlin.verify(metrics).recordChunkSize(3, 7L)
        org.mockito.kotlin.verify(metrics, org.mockito.kotlin.times(3))
            .recordDocumentEquipment(org.mockito.kotlin.any())
    }

    @Test
    fun `execute - propagates exception from reader`() {
        val input = testInput()
        val ex = RuntimeException("file read failed")
        whenever(dataReader.read(any())).thenThrow(ex)

        val thrown = runCatching { orchestrator.execute(input) }.exceptionOrNull()
        assertThat(thrown).isSameAs(ex)
    }

    @Test
    fun `execute - propagates exception from writer`() {
        val input = testInput()
        val grouped = listOf(testGrouped())
        val prepped = listOf<PreppedDocument>(mock())
        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any()))
            .thenReturn(TransformResult(1, 1, prepped))
        val ex = RuntimeException("DB connection failed")
        whenever(writer.write(any(), any(), any())).thenThrow(ex)

        val thrown = runCatching { orchestrator.execute(input) }.exceptionOrNull()
        assertThat(thrown).isSameAs(ex)
    }

    private fun testInput(
        objectKey: String = "run1/chunk001.jsonl.gz",
        resultCount: Int = 1,
    ) = ChunkProcessInput(
        objectKey = objectKey,
        sourceRunId = "run-1",
        sourceChunkId = "chunk-001",
        resultCount = resultCount,
    )

    private fun testGrouped() = GroupedEquipmentResult(
        readKey = "oc1:1",
        ocid = "oc1",
        presetNo = 1,
        items = listOf(testItem()),
    )

    private fun testItem() = CalculatedEquipmentItem(
        ocid = "oc1",
        presetNo = 1,
        itemName = "Test Sword",
        itemLevel = 160,
        itemPart = "Weapon",
        itemEquipmentPart = "무기",
        potentialGrade = "레전드리",
        potentialOptions = listOf("공격력 +12%"),
        additionalGrade = "에픽",
        additionalOptions = listOf("STR +9%"),
        currentStar = 17,
        targetStar = 22,
        status = "SUCCESS",
        totalCost = BigDecimal("150000000000"),
        blackCubeCost = BigDecimal("50000000000"),
        additionalCubeCost = BigDecimal("30000000000"),
        starforceCost = BigDecimal("70000000000"),
        errorMessage = null,
    )
}
```

- [ ] **Step 2: Run test to verify it fails (compilation error expected)**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.adapter.chunk.ChunkPipelineOrchestratorTest" --continue 2>&1 | tail -10`
Expected: COMPILATION FAILURE — `ChunkPipelineOrchestrator` class not found.

---

## Task 2: Implement ChunkPipelineOrchestrator

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt`

- [ ] **Step 1: Create the orchestrator class**

```kotlin
package maple.synchronizer.adapter.chunk

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.processor.ChunkDataReader
import maple.synchronizer.processor.ChunkDocumentTransformer
import maple.synchronizer.processor.ChunkDocumentWriter
import maple.synchronizer.processor.ChunkProcessResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Pipeline orchestrator that runs the chunk stage chain in order:
 *   read (file + ocid resolve) → transform (build + prepare) → write (upsert + ranking).
 *
 * Each stage is a Spring `@Component` injected as a constructor dependency. Stage-specific
 * timers stay inside the stages; this orchestrator records only the aggregate metrics
 * (documents, items, chunk size, per-document equipment).
 */
@Component
class ChunkPipelineOrchestrator(
    private val dataReader: ChunkDataReader,
    private val transformer: ChunkDocumentTransformer,
    private val writer: ChunkDocumentWriter,
    private val metrics: SynchronizerMetrics,
) {
    private val log = LoggerFactory.getLogger(ChunkPipelineOrchestrator::class.java)

    fun execute(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = dataReader.read(input.objectKey)

        val transformResult = transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)

        log.info(
            "[Synchronizer] grouped {} results into {} documents",
            input.resultCount,
            transformResult.documentCount,
        )

        metrics.incrementDocuments(transformResult.documentCount)
        metrics.incrementItems(transformResult.itemCount)
        metrics.recordChunkSize(transformResult.documentCount, transformResult.itemCount)
        transformResult.prepped.forEach { metrics.recordDocumentEquipment(it.equipmentCount) }

        writer.write(input.sourceRunId, input.sourceChunkId, transformResult.prepped)

        return ChunkProcessResult(
            documentCount = transformResult.documentCount,
            itemCount = transformResult.itemCount,
            jsonRowCount = input.resultCount.toLong(),
        )
    }
}
```

- [ ] **Step 2: Run test to verify all 5 tests pass**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.adapter.chunk.ChunkPipelineOrchestratorTest" --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL — 5 tests passed.

The test verifies metrics via mock `verify(...)` calls on the `SynchronizerMetrics` dependency, not by reading internal `meterRegistry` state — no changes to `SynchronizerMetrics` are required.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt module-synchronizer/src/test/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestratorTest.kt
git commit -m "feat(synchronizer): add ChunkPipelineOrchestrator (#990)"
```

---

## Task 3: Refactor DefaultChunkProcessor to delegate to orchestrator

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt`

- [ ] **Step 1: Replace DefaultChunkProcessor body with delegation**

Replace the entire file contents with:

```kotlin
package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator
import org.springframework.stereotype.Component

/**
 * Thin delegate to [ChunkPipelineOrchestrator]. Retained for backward compatibility with
 * consumers that depend on the [ChunkProcessor] interface (e.g. `KafkaResultChunkConsumer`).
 * New stage composition should happen in the orchestrator. To be removed in a follow-up
 * issue once the consumer migrates to inject `ChunkPipelineOrchestrator` directly.
 */
@Deprecated(
    message = "Use ChunkPipelineOrchestrator directly. This delegate will be removed.",
    replaceWith = ReplaceWith("ChunkPipelineOrchestrator", "maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator"),
)
@Component
class DefaultChunkProcessor(
    private val orchestrator: ChunkPipelineOrchestrator,
) : ChunkProcessor {

    override fun process(input: ChunkProcessInput): ChunkProcessResult =
        orchestrator.execute(input)
}
```

- [ ] **Step 2: Update DefaultChunkProcessorTest to use the new constructor**

Modify `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt`. Replace the imports and the test setUp/class declarations. The new test only verifies delegation, so the existing 5 tests become thin delegation checks. Replace the entire file with:

```kotlin
package maple.synchronizer.processor

import maple.core.domain.chunk.ChunkProcessInput
import maple.synchronizer.adapter.chunk.ChunkPipelineOrchestrator
import maple.expectation.error.CommonErrorCode
import maple.expectation.error.exception.ArtifactNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DefaultChunkProcessorTest {

    private val orchestrator: ChunkPipelineOrchestrator = mock()
    private lateinit var chunkProcessor: DefaultChunkProcessor

    @BeforeEach
    fun setUp() {
        chunkProcessor = DefaultChunkProcessor(orchestrator)
    }

    @Test
    fun `process - delegates to orchestrator and returns its result`() {
        val input = testInput()
        val expected = ChunkProcessResult(documentCount = 5, itemCount = 9, jsonRowCount = 100L)
        whenever(orchestrator.execute(any())).thenReturn(expected)

        val result = chunkProcessor.process(input)

        assertThat(result).isSameAs(expected)
        verify(orchestrator).execute(input)
    }

    @Test
    fun `process - propagates ArtifactNotFoundException from orchestrator`() {
        val input = testInput()
        whenever(orchestrator.execute(any()))
            .thenThrow(ArtifactNotFoundException(CommonErrorCode.ARTIFACT_NOT_FOUND, "ResultFileReader", "/tmp/missing"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(ArtifactNotFoundException::class.java)
            .hasMessageContaining("ResultFileReader")
    }

    @Test
    fun `process - propagates RuntimeException from orchestrator`() {
        val input = testInput()
        whenever(orchestrator.execute(any())).thenThrow(RuntimeException("DB connection failed"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB connection failed")
    }

    @Test
    fun `process - calls execute exactly once per process call`() {
        val input = testInput()
        whenever(orchestrator.execute(any())).thenReturn(ChunkProcessResult(0, 0, 0L))

        chunkProcessor.process(input)
        chunkProcessor.process(input)

        verify(orchestrator, org.mockito.kotlin.times(2)).execute(input)
    }

    private fun testInput() = ChunkProcessInput(
        objectKey = "run1/chunk001.jsonl.gz",
        sourceRunId = "run-1",
        sourceChunkId = "chunk-001",
        resultCount = 1,
    )
}
```

- [ ] **Step 3: Run DefaultChunkProcessorTest to verify delegation works**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.processor.DefaultChunkProcessorTest" --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL — 4 tests passed.

- [ ] **Step 4: Run ChunkPipelineOrchestratorTest to verify orchestrator works**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.adapter.chunk.ChunkPipelineOrchestratorTest" --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL — 5 tests passed.

- [ ] **Step 5: Run all synchronizer tests for regression**

Run: `./gradlew :module-synchronizer:test --continue 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL. All synchronizer tests pass.

- [ ] **Step 6: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt
git commit -m "refactor(synchronizer): DefaultChunkProcessor delegates to ChunkPipelineOrchestrator (#990)"
```

---

## Task 4: Final verification and PR

- [ ] **Step 1: Compile entire repo**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `./gradlew test --continue 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. No regressions.

- [ ] **Step 3: Push branch and open PR**

```bash
git push -u origin refactor/990-chunk-pipeline
gh pr create --base develop --title "refactor(synchronizer): add ChunkPipelineOrchestrator (PR2 of #990)" --body '## Summary
- New \`ChunkPipelineOrchestrator\` in \`module-synchronizer/adapter/chunk/\` (TDD, 5 unit tests).
- \`DefaultChunkProcessor\` refactored to a 1-line delegate to the orchestrator. Backward compatible with \`KafkaResultChunkConsumer\`.
- Aggregate metrics (documents, items, chunk size, per-doc equipment) moved from processor to orchestrator.
- Stage beans (\`ChunkDataReader\`, \`ChunkDocumentTransformer\`, \`ChunkDocumentWriter\`) unchanged.

## Spec
docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md (PR2 partial — orchestrator only; full port interface migration deferred to follow-up issue)

## Issue
Closes #990

## Out of scope
- Port interface migration (\`ChunkReader\` / \`ChunkTransformer\` / \`ChunkWriter\` adapters) — separate follow-up issue.
- Renaming or relocating existing stage classes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

- [ ] **Step 5: Verify PR created and CI passes**

```bash
gh pr checks --watch
```

Expected: All checks green. Address any failures before merge.

---

## Self-Review

**1. Spec coverage:**
- §4 `ChunkPipelineOrchestrator` — Task 2 ✓
- §6 PR2 partial (orchestrator only, deferred adapter migration) — Task 1-3 ✓
- §7 Test strategy (fake stages + e2e) — Task 1 covers happy + error paths ✓
- §8 Success signal — Task 4 PR green + future stage addition without touching processor ✓

**2. Issue #990 acceptance criteria coverage:**
- [x] Pipeline stage classes independent (read, build, persist) — already true from #1143, unchanged
- [x] `DefaultChunkProcessor` is thin (1-line delegate)
- [x] Behavior unchanged — `DefaultChunkProcessorTest` proves delegation, `ChunkPipelineOrchestratorTest` proves logic, `KafkaResultChunkConsumer` integration unchanged
- [x] `./gradlew compileKotlin compileJava --continue` passes — Task 4 Step 1
- [x] `./gradlew test` passes — Task 4 Step 2

**3. Placeholder scan:** No TBD / TODO. All code blocks complete. Step 2.5 is conditional and includes full code.

**4. Type consistency:**
- `ChunkPipelineOrchestrator` constructor: `(ChunkDataReader, ChunkDocumentTransformer, ChunkDocumentWriter, SynchronizerMetrics)` — Task 1 test matches Task 2 impl ✓
- `execute(input: ChunkProcessInput): ChunkProcessResult` — Task 1 test, Task 2 impl, Task 3 delegate all consistent ✓
- `DefaultChunkProcessor` constructor: `(ChunkPipelineOrchestrator)` — Task 3 test + impl match ✓

**5. Backward compatibility:**
- `ChunkProcessor` interface unchanged ✓
- `KafkaResultChunkConsumer` still injects `ChunkProcessor` (Spring resolves to `DefaultChunkProcessor` which delegates) ✓
- `ChunkConsumerMappingTest` still mocks `ChunkProcessor` (not the orchestrator) — no change needed ✓
