# Processor Decomposition Implementation Plan (#923)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose `DefaultChunkProcessor.process()` into 3 stages (read → transform → write) as separate `@Component` classes.

**Architecture:** Extract 3 Spring `@Component` classes from the monolithic `process()` method: `ChunkDataReader` (file read + OCID resolution), `ChunkDocumentTransformer` (build + prepare documents), `ChunkDocumentWriter` (DB upsert + Redis update). `DefaultChunkProcessor` becomes a thin orchestrator calling the 3 stages sequentially. Zero behavioral change.

**Tech Stack:** Kotlin, Spring `@Component`, Micrometer timers, Mockito-Kotlin.

**Branch:** Create `refactor/923-processor-decomposition` off `develop`. PR base: `develop`.

---

## File structure

| File | Action | Responsibility |
|------|--------|---------------|
| `module-synchronizer/.../processor/ChunkDataReader.kt` | CREATE | File read + OCID resolution |
| `module-synchronizer/.../processor/ChunkDocumentTransformer.kt` | CREATE | Build + prepare documents, includes `TransformResult` |
| `module-synchronizer/.../processor/ChunkDocumentWriter.kt` | CREATE | DB upsert + Redis update |
| `module-synchronizer/.../processor/DefaultChunkProcessor.kt` | MODIFY | Thin orchestrator (6 deps → 4 deps) |
| `module-synchronizer/.../processor/DefaultChunkProcessorTest.kt` | MODIFY | Update constructor + mocks for stage-based design |

All files in `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/`.

---

## Task 1: Create branch off develop

**Files:** none

- [ ] **Step 1.1: Fetch develop and create branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git checkout develop
git pull origin develop
git checkout -b refactor/923-processor-decomposition
```

- [ ] **Step 1.2: Verify branch**

Run: `git branch --show-current`
Expected: `refactor/923-processor-decomposition`

---

## Task 2: Create ChunkDataReader

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDataReader.kt`

- [ ] **Step 2.1: Create ChunkDataReader**

```kotlin
package maple.synchronizer.processor

import io.micrometer.core.instrument.Timer
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.resolver.OcidUserIgnResolver
import maple.synchronizer.storage.ResultFileReader
import org.springframework.stereotype.Component

@Component
class ChunkDataReader(
    private val resultFileReader: ResultFileReader,
    private val ocidUserIgnResolver: OcidUserIgnResolver,
    private val metrics: SynchronizerMetrics,
) {

    fun read(objectKey: String): List<GroupedEquipmentResult> {
        val grouped = timed(metrics.fileReadTimer()) {
            resultFileReader.readAndGroupByCompositeKey(objectKey)
        }

        val ocids = grouped.map { it.ocid }.toSet()
        val ocidToUserIgn = ocidUserIgnResolver.resolve(ocids)

        return grouped.map { it.copy(userIgn = ocidToUserIgn[it.ocid]) }
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
```

- [ ] **Step 2.2: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDataReader.kt
git commit -m "refactor(923): extract ChunkDataReader from DefaultChunkProcessor"
```

---

## Task 3: Create ChunkDocumentTransformer

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentTransformer.kt`

- [ ] **Step 3.1: Create ChunkDocumentTransformer with TransformResult**

```kotlin
package maple.synchronizer.processor

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Timer
import maple.synchronizer.builder.EquipmentDocumentBuilder
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.EquipmentDocumentPreparer
import maple.synchronizer.preparer.PreppedDocument
import org.springframework.stereotype.Component

data class TransformResult(
    val documentCount: Int,
    val itemCount: Long,
    val prepped: List<PreppedDocument>,
)

@Component
class ChunkDocumentTransformer(
    objectMapper: ObjectMapper,
    private val metrics: SynchronizerMetrics,
) {

    private val documentBuilder = EquipmentDocumentBuilder()
    private val preparer = EquipmentDocumentPreparer(objectMapper)

    fun transform(runId: String, chunkId: String, grouped: List<GroupedEquipmentResult>): TransformResult {
        val documents = timed(metrics.documentBuildTimer()) {
            grouped.map { g ->
                documentBuilder.build(runId, chunkId, g)
            }
        }

        val itemCount = grouped.sumOf { it.items.size.toLong() }
        val prepped = preparer.prepare(documents)

        return TransformResult(
            documentCount = documents.size,
            itemCount = itemCount,
            prepped = prepped,
        )
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
```

- [ ] **Step 3.2: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentTransformer.kt
git commit -m "refactor(923): extract ChunkDocumentTransformer from DefaultChunkProcessor"
```

---

## Task 4: Create ChunkDocumentWriter

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentWriter.kt`

- [ ] **Step 4.1: Create ChunkDocumentWriter**

```kotlin
package maple.synchronizer.processor

import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.PreppedDocument
import maple.synchronizer.ranking.EquipmentRankingRedisWriter
import maple.synchronizer.repository.EquipmentReadModelRepository
import org.springframework.stereotype.Component

@Component
class ChunkDocumentWriter(
    private val readModelRepository: EquipmentReadModelRepository,
    private val rankingWriter: EquipmentRankingRedisWriter,
    private val metrics: SynchronizerMetrics,
) {

    fun write(runId: String, chunkId: String, prepped: List<PreppedDocument>) {
        metrics.mainUpsertTimer().record(Runnable {
            readModelRepository.bulkUpsert(runId, chunkId, prepped)
        })
        rankingWriter.update(prepped)
    }
}
```

- [ ] **Step 4.2: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentWriter.kt
git commit -m "refactor(923): extract ChunkDocumentWriter from DefaultChunkProcessor"
```

---

## Task 5: Refactor DefaultChunkProcessor to thin orchestrator

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt`

- [ ] **Step 5.1: Replace entire file contents**

Replace the entire file with:

```kotlin
package maple.synchronizer.processor

import maple.synchronizer.metrics.SynchronizerMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class DefaultChunkProcessor(
    private val dataReader: ChunkDataReader,
    private val transformer: ChunkDocumentTransformer,
    private val writer: ChunkDocumentWriter,
    private val metrics: SynchronizerMetrics,
) : ChunkProcessor {

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = dataReader.read(input.objectKey)

        val transformResult = transformer.transform(input.sourceRunId, input.sourceChunkId, grouped)

        log.info("[Synchronizer] grouped {} results into {} documents", input.resultCount, transformResult.documentCount)

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

Changes from original:
- Constructor: 6 deps → 4 deps (`dataReader`, `transformer`, `writer`, `metrics`)
- `process()` body: 3 stage calls + metrics at boundaries
- `timed()` helper removed (moved to reader/transformer)
- `documentBuilder`, `preparer`, `resultFileReader`, `ocidUserIgnResolver`, `readModelRepository`, `rankingWriter`, `objectMapper` removed — moved to stage classes

- [ ] **Step 5.2: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt
git commit -m "refactor(923): reduce DefaultChunkProcessor to thin orchestrator"
```

---

## Task 6: Update DefaultChunkProcessorTest

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt`

- [ ] **Step 6.1: Replace entire test file**

Replace the entire file with:

```kotlin
package maple.synchronizer.processor

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.domain.CalculatedEquipmentItem
import maple.synchronizer.domain.GroupedEquipmentResult
import maple.synchronizer.metrics.SynchronizerMetrics
import maple.synchronizer.preparer.PreppedDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

class DefaultChunkProcessorTest {

    private val dataReader: ChunkDataReader = mock()
    private val transformer: ChunkDocumentTransformer = mock()
    private val writer: ChunkDocumentWriter = mock()
    private val metrics = SynchronizerMetrics(SimpleMeterRegistry())

    private lateinit var chunkProcessor: DefaultChunkProcessor

    @BeforeEach
    fun setUp() {
        chunkProcessor = DefaultChunkProcessor(dataReader, transformer, writer, metrics)
    }

    @Test
    fun `process - happy path returns result with correct counts`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = chunkProcessor.process(input)

        assertThat(result.documentCount).isEqualTo(1)
        assertThat(result.itemCount).isEqualTo(1)
        assertThat(result.jsonRowCount).isEqualTo(input.resultCount.toLong())
    }

    @Test
    fun `process - calls writer with prepped documents`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        chunkProcessor.process(input)

        verify(writer).write(eq(input.sourceRunId), eq(input.sourceChunkId), eq(prepped))
    }

    @Test
    fun `process - file not found propagates exception`() {
        val input = testInput()
        whenever(dataReader.read(any()))
            .thenThrow(IllegalStateException("Result file not found"))

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Result file not found")
    }

    @Test
    fun `process - upsert failure propagates exception`() {
        val input = testInput()
        val grouped = listOf(GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem())))
        val prepped = listOf<PreppedDocument>(mock())
        val transformResult = TransformResult(documentCount = 1, itemCount = 1, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)
        doThrow(RuntimeException("DB connection failed"))
            .whenever(writer).write(any(), any(), any())

        assertThatThrownBy { chunkProcessor.process(input) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("DB connection failed")
    }

    @Test
    fun `process - multiple groups produce multiple documents`() {
        val input = testInput(resultCount = 3)
        val grouped = listOf(
            GroupedEquipmentResult(readKey = "oc1:1", ocid = "oc1", presetNo = 1, items = listOf(testItem(ocid = "oc1"))),
            GroupedEquipmentResult(readKey = "oc2:1", ocid = "oc2", presetNo = 1, items = listOf(testItem(ocid = "oc2"), testItem(ocid = "oc2"))),
        )
        val prepped = listOf<PreppedDocument>(mock(), mock())
        val transformResult = TransformResult(documentCount = 2, itemCount = 3, prepped = prepped)

        whenever(dataReader.read(any())).thenReturn(grouped)
        whenever(transformer.transform(any(), any(), any())).thenReturn(transformResult)

        val result = chunkProcessor.process(input)

        assertThat(result.documentCount).isEqualTo(2)
        assertThat(result.itemCount).isEqualTo(3)
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

    private fun testItem(
        ocid: String = "oc1",
        presetNo: Int = 1,
    ) = CalculatedEquipmentItem(
        ocid = ocid,
        presetNo = presetNo,
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

Changes from original:
- Constructor: 6 mocked deps → 3 mocked stage deps + metrics
- Removed: `resultFileReader`, `readModelRepository`, `ocidUserIgnResolver`, `rankingWriter`, `objectMapper` direct mocks
- Added: `dataReader`, `transformer`, `writer` stage mocks
- Tests verify stage interactions instead of individual dep interactions
- `testInput()` and `testItem()` helpers unchanged

- [ ] **Step 6.2: Commit**

```bash
git add module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt
git commit -m "refactor(923): update DefaultChunkProcessorTest for stage-based design"
```

---

## Task 7: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 7.1: Compile module-synchronizer**

Run: `./gradlew :module-synchronizer:compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7.2: Run module-synchronizer tests**

Run: `./gradlew :module-synchronizer:test`
Expected: BUILD SUCCESSFUL.

---

## Task 8: PR

**Files:** none

- [ ] **Step 8.1: Push branch**

```bash
git push -u origin refactor/923-processor-decomposition
```

- [ ] **Step 8.2: Create PR with `gh`**

```bash
gh pr create \
  --base develop \
  --head refactor/923-processor-decomposition \
  --title "refactor(923): decompose DefaultChunkProcessor into read/transform/write stages" \
  --body "$(cat <<'EOF'
## Summary
Decompose `DefaultChunkProcessor.process()` into 3 `@Component` stages:
- **ChunkDataReader** — file read + OCID resolution
- **ChunkDocumentTransformer** — document build + prepare (includes `TransformResult` data class)
- **ChunkDocumentWriter** — DB bulk upsert + Redis ranking update

`DefaultChunkProcessor` becomes a thin orchestrator: `read() → transform() → write()` with metrics at stage boundaries.

## Files
- Created: `ChunkDataReader.kt`, `ChunkDocumentTransformer.kt`, `ChunkDocumentWriter.kt`
- Modified: `DefaultChunkProcessor.kt` (37-line process → 3 stage calls), `DefaultChunkProcessorTest.kt` (stage-based mocks)

## Verification
- [x] `./gradlew :module-synchronizer:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-synchronizer:test` passes

Closes #923
EOF
)"
```

- [ ] **Step 8.3: Verify PR exists**

Run: `gh pr view --json number,url,title,state | jq '{number, url, title, state}'`
Expected: state `OPEN`.

---

## Acceptance criteria

From #923:
- [x] 3 new `@Component` classes for read / transform / write stages
- [x] `process()` reduced to 3 sequential calls + metrics at boundaries
- [x] `DocumentBuilder` and `Preparer` stay as inner components of transform stage
- [x] DB upsert and Redis upsert grouped in write stage
- [x] Existing tests pass without behavioral change
