# Issue 988 — SnapshotChunkProcessor Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose `SnapshotChunkProcessor` (174 lines, 7 deps, 3 responsibilities) into a pure `SnapshotChunkParser`, a generic `SnapshotChunkPipeline`, and a thin orchestrator. Zero behavioral change.

**Architecture:** Extract 2 new `@Component` classes from `SnapshotChunkProcessor`: `SnapshotChunkParser` (sync, pure JSON → `FlatItem` conversion) and `SnapshotChunkPipeline` (suspend, 3-stage coroutine orchestration with typed lambda slots). `SnapshotChunkProcessor` becomes a thin orchestrator that wires `ObjectStorage` → parser → pipeline → writer and owns counter bookkeeping. `CalculationResultWriter` signature changes from `ReceiveChannel<CalculationResult>` to `Flow<CalculationResult>` (writer internally just iterates; Flow is more general).

**Tech Stack:** Kotlin, Spring `@Component`, kotlinx-coroutines (`Channel`, `Flow`, `coroutineScope`), Jackson `ObjectMapper`, JUnit5 + `kotlinx-coroutines-test`.

**Branch:** `refactor/988-snapshot-chunk-decomposition` (already created from `origin/develop`). PR base: `develop`.

**Spec:** `docs/superpowers/specs/2026-06-06-988-snapshot-chunk-processor-decomposition-design.md`

---

## File structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-calculator/.../parser/SnapshotChunkParser.kt` | CREATE | JSON line → `Outcome.Skipped` / `Outcome.Parsed(items: List<FlatItem>)` |
| `module-calculator/.../parser/FlatItem.kt` | CREATE | Data class `FlatItem(ocid, presetNo, item)` |
| `module-calculator/.../pipeline/SnapshotChunkPipeline.kt` | CREATE | 3-stage coroutine pipeline (typed lambda slots) |
| `module-calculator/.../writer/CalculationResultWriter.kt` | MODIFY | Signature: `ReceiveChannel` → `Flow` |
| `module-calculator/.../processor/SnapshotChunkProcessor.kt` | MODIFY | Thin orchestrator (7 deps, 174 → ~80 lines) |
| `module-calculator/src/test/.../parser/SnapshotChunkParserTest.kt` | CREATE | Parser unit tests (4 cases) |
| `module-calculator/src/test/.../pipeline/SnapshotChunkPipelineTest.kt` | CREATE | Pipeline unit tests (3 cases) |

All production files under `module-calculator/src/main/kotlin/maple/calculator/`. All test files under `module-calculator/src/test/kotlin/maple/calculator/`.

---

## Task 1: Verify worktree branch

**Files:** none (verification only)

- [ ] **Step 1.1: Confirm branch**

Run: `git -C /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition branch --show-current`
Expected: `refactor/988-snapshot-chunk-decomposition`

- [ ] **Step 1.2: Confirm clean state**

Run: `git -C /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition status --short`
Expected: empty output (clean)

---

## Task 2: Create `FlatItem` data class

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/parser/FlatItem.kt`

- [ ] **Step 1: Create the file**

```kotlin
package maple.calculator.parser

import maple.expectation.core.dto.v4.EquipmentItem

data class FlatItem(
    val ocid: String,
    val presetNo: Int,
    val item: EquipmentItem,
)
```

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git add module-calculator/src/main/kotlin/maple/calculator/parser/FlatItem.kt
git commit -m "refactor(988): extract FlatItem to parser package"
```

---

## Task 3: Create `SnapshotChunkParser` (TDD)

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotChunkParser.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/parser/SnapshotChunkParserTest.kt`

- [ ] **Step 1: Write the failing test**

Create `module-calculator/src/test/kotlin/maple/calculator/parser/SnapshotChunkParserTest.kt`:

```kotlin
package maple.calculator.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SnapshotChunkParserTest {

    private lateinit var objectMapper: ObjectMapper
    private lateinit var equipmentParser: SnapshotEquipmentParser
    private lateinit var parser: SnapshotChunkParser

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        equipmentParser = mock()
        parser = SnapshotChunkParser(objectMapper, equipmentParser)
    }

    @Test
    fun `parse returns Skipped when status is not SUCCESS`() {
        val line = """{"status":"FAIL","key":"oc1","body":{}}"""

        val outcome = parser.parse(line)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
    }

    @Test
    fun `parse returns Skipped when body is missing or null`() {
        val lineMissing = """{"status":"SUCCESS","key":"oc1"}"""
        val lineNull = """{"status":"SUCCESS","key":"oc1","body":null}"""

        assertThat(parser.parse(lineMissing)).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
        assertThat(parser.parse(lineNull)).isInstanceOf(SnapshotChunkParser.Outcome.Skipped::class.java)
    }

    @Test
    fun `parse returns Parsed with flat items for all three presets`() {
        val realLine = """{"status":"SUCCESS","key":"oc42","body":{"item_equipment_preset_1":[]}}"""
        whenever(equipmentParser.parseAllPresets(objectMapper.readTree(realLine).path("body"))).thenReturn(
            mapOf(
                1 to listOf(stubItem("a")),
                2 to listOf(stubItem("b")),
                3 to listOf(stubItem("c"), stubItem("d")),
            ),
        )

        val outcome = parser.parse(realLine)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Parsed::class.java)
        val parsed = outcome as SnapshotChunkParser.Outcome.Parsed
        assertThat(parsed.items).hasSize(4)
        assertThat(parsed.items.map { it.ocid }).allMatch { it == "oc42" }
        assertThat(parsed.items.map { it.presetNo }.toSet()).isEqualTo(setOf(1, 2, 3))
    }

    @Test
    fun `parse returns Parsed with empty list when equipmentParser yields no presets`() {
        val realLine = """{"status":"SUCCESS","key":"oc1","body":{}}"""
        whenever(equipmentParser.parseAllPresets(objectMapper.readTree(realLine).path("body"))).thenReturn(emptyMap())

        val outcome = parser.parse(realLine)

        assertThat(outcome).isInstanceOf(SnapshotChunkParser.Outcome.Parsed::class.java)
        assertThat((outcome as SnapshotChunkParser.Outcome.Parsed).items).isEmpty()
    }

    private fun stubItem(name: String) = maple.expectation.core.dto.v4.EquipmentItem(
        part = maple.expectation.core.dto.v4.EquipmentSlot.WEAPON,
        equipmentPart = maple.expectation.core.dto.v4.EquipmentPart.WEAPON,
        itemName = name,
        level = 0,
        potential = null,
        additionalPotential = null,
        starforce = "0",
        starforceScrollFlag = maple.expectation.core.dto.v4.StarforceScrollFlag.NONE,
        addOption = maple.expectation.core.dto.v4.AddOption(),
        baseAttackPower = "0",
        baseMagicPower = "0",
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:test --tests "maple.calculator.parser.SnapshotChunkParserTest" --continue 2>&1 | tail -10
```
Expected: COMPILATION FAILURE (`SnapshotChunkParser` not found)

- [ ] **Step 3: Create the parser implementation**

Create `module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotChunkParser.kt`:

```kotlin
package maple.calculator.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SnapshotChunkParser(
    private val objectMapper: ObjectMapper,
    private val equipmentParser: SnapshotEquipmentParser,
) {

    sealed class Outcome {
        data object Skipped : Outcome()
        data class Parsed(val items: List<FlatItem>) : Outcome()
    }

    fun parse(line: String): Outcome {
        val node = objectMapper.readTree(line)
        if (node.path("status").asText() != "SUCCESS") return Outcome.Skipped
        val body = node.path("body").takeIf { !it.isMissingNode && !it.isNull } ?: return Outcome.Skipped
        val ocid = node.path("key").asText("")

        val items = equipmentParser.parseAllPresets(body).flatMap { (presetNo, equipmentItems) ->
            equipmentItems.map { FlatItem(ocid, presetNo, it) }
        }
        return Outcome.Parsed(items)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:test --tests "maple.calculator.parser.SnapshotChunkParserTest" --continue 2>&1 | tail -10
```
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git add module-calculator/src/main/kotlin/maple/calculator/parser/SnapshotChunkParser.kt
git add module-calculator/src/test/kotlin/maple/calculator/parser/SnapshotChunkParserTest.kt
git commit -m "refactor(988): extract SnapshotChunkParser from SnapshotChunkProcessor"
```

---

## Task 4: Create `SnapshotChunkPipeline` (TDD)

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/pipeline/SnapshotChunkPipeline.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/pipeline/SnapshotChunkPipelineTest.kt`

- [ ] **Step 1: Write the failing test**

Create `module-calculator/src/test/kotlin/maple/calculator/pipeline/SnapshotChunkPipelineTest.kt`:

```kotlin
package maple.calculator.pipeline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import maple.expectation.core.dto.v4.EquipmentItem
import maple.expectation.core.dto.v4.EquipmentSlot
import maple.expectation.core.dto.v4.EquipmentPart
import maple.expectation.core.dto.v4.StarforceScrollFlag
import maple.expectation.core.dto.v4.AddOption

class SnapshotChunkPipelineTest {

    private val properties = PipelineProperties(workerCount = 2, channelCapacity = 16)
    private val pipeline = SnapshotChunkPipeline(properties)

    @Test
    fun `run processes all lines and emits one result per FlatItem`() = runTest {
        val source: Flow<String> = flowOf("line-1", "line-2", "line-3")
        val stubItem = stubEquipmentItem()
        val emittedItems = mutableListOf<FlatItem>()

        val parse: suspend (String) -> SnapshotChunkParser.Outcome = { line ->
            SnapshotChunkParser.Outcome.Parsed(listOf(FlatItem(ocid = "oc-$line", presetNo = 1, item = stubItem)))
        }
        val calculate: suspend (FlatItem) -> CalculationResult = { flat ->
            emittedItems += flat
            CalculationResult(ocid = flat.ocid, presetNo = flat.presetNo, status = "SUCCESS")
        }

        val results = pipeline.run(source, parse, calculate).toList()

        assertThat(results).hasSize(3)
        assertThat(results.map { it.ocid }).containsExactly("oc-line-1", "oc-line-2", "oc-line-3")
        assertThat(emittedItems).hasSize(3)
    }

    @Test
    fun `run produces empty result flow when source is empty`() = runTest {
        val source: Flow<String> = flowOf()

        val results = pipeline.run(
            source,
            parse = { SnapshotChunkParser.Outcome.Skipped },
            calculate = { error("should not be called") },
        ).toList()

        assertThat(results).isEmpty()
    }

    @Test
    fun `run does not emit items when parse returns Skipped`() = runTest {
        val source: Flow<String> = flowOf("a", "b", "c")
        var calculateCalls = 0
        val parse: suspend (String) -> SnapshotChunkParser.Outcome = { SnapshotChunkParser.Outcome.Skipped }
        val calculate: suspend (FlatItem) -> CalculationResult = {
            calculateCalls += 1
            error("should not be called when parse skips")
        }

        val results = pipeline.run(source, parse, calculate).toList()

        assertThat(results).isEmpty()
        assertThat(calculateCalls).isEqualTo(0)
    }

    private fun stubEquipmentItem() = EquipmentItem(
        part = EquipmentSlot.WEAPON,
        equipmentPart = EquipmentPart.WEAPON,
        itemName = "test",
        level = 0,
        potential = null,
        additionalPotential = null,
        starforce = "0",
        starforceScrollFlag = StarforceScrollFlag.NONE,
        addOption = AddOption(),
        baseAttackPower = "0",
        baseMagicPower = "0",
    )
}
```

> **Note:** The exact `CalculationResult` constructor signature may differ. Use the existing constructor from `maple.calculator.model.CalculationResult` — adjust the test if needed (all required fields). Refer to existing usages in `SnapshotChunkProcessor` for the canonical call pattern.

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:test --tests "maple.calculator.pipeline.SnapshotChunkPipelineTest" --continue 2>&1 | tail -10
```
Expected: COMPILATION FAILURE (`SnapshotChunkPipeline` not found)

- [ ] **Step 3: Create the pipeline implementation**

Create `module-calculator/src/main/kotlin/maple/calculator/pipeline/SnapshotChunkPipeline.kt`:

```kotlin
package maple.calculator.pipeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import maple.calculator.config.PipelineProperties
import maple.calculator.model.CalculationResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import org.springframework.stereotype.Component

@Component
class SnapshotChunkPipeline(
    private val properties: PipelineProperties,
) {

    private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
        "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
    }

    /**
     * 3-stage coroutine pipeline: source (Flow<String>) → parse → calculate → Flow<CalculationResult>.
     *
     * Stages:
     *  - Stage 1 (IO): reads from `source`, sends lines to internal lineChannel
     *  - Stage 2 (Default, `workerCount` parallel): parses each line, sends FlatItems to itemChannel
     *  - Stage 3 (Default, `workerCount` parallel): calculates each item, sends results to resultChannel
     *
     * Channels are closed by their producing stage. Result flow completes when all stages finish.
     */
    suspend fun run(
        source: Flow<String>,
        parse: suspend (String) -> SnapshotChunkParser.Outcome,
        calculate: suspend (FlatItem) -> CalculationResult,
    ): Flow<CalculationResult> = coroutineScope {
        val lineChannel = Channel<String>(properties.channelCapacity)
        val itemChannel = Channel<FlatItem>(properties.channelCapacity)
        val resultChannel = Channel<CalculationResult>(properties.channelCapacity)

        launch(Dispatchers.IO) {
            source.collect { line -> lineChannel.send(line) }
            lineChannel.close()
        }

        launch {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        for (line in lineChannel) {
                            when (val outcome = parse(line)) {
                                SnapshotChunkParser.Outcome.Skipped -> continue
                                is SnapshotChunkParser.Outcome.Parsed -> outcome.items.forEach { itemChannel.send(it) }
                            }
                        }
                    }
                }
            }
            itemChannel.close()
        }

        launch {
            coroutineScope {
                repeat(workerCount) {
                    launch(Dispatchers.Default) {
                        for (item in itemChannel) {
                            resultChannel.send(calculate(item))
                        }
                    }
                }
            }
            resultChannel.close()
        }

        resultChannel.consumeAsFlow()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:test --tests "maple.calculator.pipeline.SnapshotChunkPipelineTest" --continue 2>&1 | tail -10
```
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git add module-calculator/src/main/kotlin/maple/calculator/pipeline/SnapshotChunkPipeline.kt
git add module-calculator/src/test/kotlin/maple/calculator/pipeline/SnapshotChunkPipelineTest.kt
git commit -m "refactor(988): extract SnapshotChunkPipeline coroutine orchestration"
```

---

## Task 5: Change `CalculationResultWriter` signature

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt`

- [ ] **Step 1: Modify writer signature**

Replace the entire content of `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` with:

```kotlin
package maple.calculator.writer

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.Flow
import maple.calculator.model.CalculationResult
import maple.calculator.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CalculationResultWriter(
    private val objectStorage: ObjectStorage,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CalculationResultWriter::class.java)

    data class WriteResult(
        val objectKey: String,
        val resultCount: Int,
        val uncompressedBytes: Long,
        val compressedBytes: Long,
    )

    suspend fun write(
        objectKey: String,
        results: Flow<CalculationResult>,
    ): WriteResult {
        val compressedCounter = CountingOutputStream(objectStorage.openOutputStream(objectKey))
        val gzipStream = GZIPOutputStream(compressedCounter)
        val uncompressedCounter = CountingOutputStream(gzipStream)
        var resultCount = 0

        objectMapper.factory.createGenerator(uncompressedCounter).use { generator ->
            results.collect { result ->
                generator.writeObject(result)
                generator.writeRaw('\n')
                resultCount += 1
            }
        }

        log.info(
            "[Writer] wrote calculator result chunk: objectKey={} results={} uncompressedBytes={} compressedBytes={}",
            objectKey,
            resultCount,
            uncompressedCounter.bytesWritten,
            compressedCounter.bytesWritten,
        )
        return WriteResult(objectKey, resultCount, uncompressedCounter.bytesWritten, compressedCounter.bytesWritten)
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
```

Changes from original:
- Import: `kotlinx.coroutines.channels.ReceiveChannel` → `kotlinx.coroutines.flow.Flow`
- Parameter type: `ReceiveChannel<CalculationResult>` → `Flow<CalculationResult>`
- Internal iteration: `for (result in results)` → `results.collect { result -> ... }`

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git add module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt
git commit -m "refactor(988): change CalculationResultWriter.write to accept Flow"
```

---

## Task 6: Refactor `SnapshotChunkProcessor` to thin orchestrator

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt`

- [ ] **Step 1: Replace entire file contents**

Replace the entire content of `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` with:

```kotlin
package maple.calculator.processor

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import maple.calculator.model.CalculationResult
import maple.calculator.model.ChunkResult
import maple.calculator.parser.FlatItem
import maple.calculator.parser.SnapshotChunkParser
import maple.calculator.pipeline.SnapshotChunkPipeline
import maple.calculator.reader.GzipJsonlSnapshotRecordReader
import maple.calculator.storage.ObjectStorage
import maple.calculator.writer.CalculationResultWriter
import maple.expectation.common.event.SnapshotChunkReadyEvent
import maple.expectation.core.dto.cube.CubeCalculationInput
import maple.expectation.core.dto.v4.EquipmentItemConverter
import maple.expectation.util.StringMaskingUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SnapshotChunkProcessor(
    private val objectStorage: ObjectStorage,
    private val jsonlReader: GzipJsonlSnapshotRecordReader,
    private val parser: SnapshotChunkParser,
    private val pipeline: SnapshotChunkPipeline,
    private val calculationCache: CalculationCache,
    private val objectMapper: ObjectMapper,
    private val resultWriter: CalculationResultWriter,
) {
    private val log = LoggerFactory.getLogger(SnapshotChunkProcessor::class.java)
    private val sampleCount = AtomicInteger(0)

    suspend fun process(event: SnapshotChunkReadyEvent, resultObjectKey: String): ChunkResult {
        val recordCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalItems = AtomicInteger(0)
        val calculatedCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        val source: Flow<String> = flow {
            objectStorage.openInputStream(event.objectKey).use { stream ->
                emitAll(jsonlReader.readLines(stream))
            }
        }

        val resultFlow = pipeline.run(
            source = source,
            parse = { line ->
                recordCount.incrementAndGet()
                when (val outcome = parser.parse(line)) {
                    SnapshotChunkParser.Outcome.Skipped -> outcome
                    is SnapshotChunkParser.Outcome.Parsed -> {
                        successCount.incrementAndGet()
                        totalItems.addAndGet(outcome.items.size)
                        outcome
                    }
                }
            },
            calculate = { flatItem -> calculateItem(flatItem, calculatedCount, errorCount) },
        )

        val writeResult = resultWriter.write(resultObjectKey, resultFlow)

        return ChunkResult(
            recordCount = recordCount.get(),
            successCount = successCount.get(),
            totalItems = totalItems.get(),
            calculatedCount = calculatedCount.get(),
            errorCount = errorCount.get(),
            resultObjectKey = writeResult.objectKey,
            resultCount = writeResult.resultCount,
            resultUncompressedBytes = writeResult.uncompressedBytes,
            resultCompressedBytes = writeResult.compressedBytes,
        )
    }

    private fun calculateItem(
        flatItem: FlatItem,
        calculatedCount: AtomicInteger,
        errorCount: AtomicInteger,
    ): CalculationResult {
        val result = runCatching {
            val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
            val componentCosts = calculateComponentCosts(cubeInput, flatItem.presetNo)
            val status = if (componentCosts.hasAnyCost) "SUCCESS" else "SKIPPED"
            val successResult = EquipmentCalculationInputConverter.toCalculationResult(
                flatItem.ocid, flatItem.presetNo, cubeInput, componentCosts, status, null,
            )
            logSample(successResult)
            successResult
        }.getOrElse { ex ->
            val cubeInput = EquipmentItemConverter.toCubeInput(flatItem.item)
            log.warn(
                "Calculation error: ocid={} preset={}: {}",
                StringMaskingUtils.maskOcid(flatItem.ocid),
                flatItem.presetNo,
                ex.message,
            )
            EquipmentCalculationInputConverter.toCalculationResult(
                flatItem.ocid, flatItem.presetNo, cubeInput, CalculationCache.ComponentCosts.empty(), "ERROR", ex.message,
            )
        }

        if (result.status == "ERROR") errorCount.incrementAndGet() else calculatedCount.incrementAndGet()
        return result
    }

    private fun calculateComponentCosts(cubeInput: CubeCalculationInput, presetNo: Int): CalculationCache.ComponentCosts {
        val input = EquipmentCalculationInputConverter.toCalculationInput(cubeInput, presetNo)
        return calculationCache.calculate(input)
    }

    private fun logSample(result: CalculationResult) {
        if (sampleCount.incrementAndGet() <= 10) {
            log.debug("[SAMPLE] {}", objectMapper.writeValueAsString(result))
        }
    }
}
```

Changes from original (174 lines → ~95 lines):
- Removed: `private data class FlatItem` (moved to parser package)
- Removed: `parseLines`, `processItems`, `readLines` private suspend methods (logic moved to parser + pipeline)
- Removed: inline `Channel` setup + 3-stage `launch` orchestration (moved to pipeline)
- Added: `parser: SnapshotChunkParser` + `pipeline: SnapshotChunkPipeline` deps
- Kept: `calculateItem` logic (uses `EquipmentItemConverter`, `EquipmentCalculationInputConverter`, `CalculationCache` — domain-specific to processor)
- `workerCount` moved to `SnapshotChunkPipeline`
- `FlatItem` import points to `maple.calculator.parser.FlatItem`

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git add module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt
git commit -m "refactor(988): reduce SnapshotChunkProcessor to thin orchestrator"
```

---

## Task 7: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 1: Compile module-calculator**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:compileKotlin compileJava --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run module-calculator unit tests**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew :module-calculator:test --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL (all tests pass, including pre-existing `CalculatorChunkProcessingCoordinatorTest`).

- [ ] **Step 3: Full repo compile (sanity)**

Run:
```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
./gradlew compileKotlin compileJava --continue 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

---

## Task 8: PR

**Files:** none

- [ ] **Step 1: Push branch**

```bash
cd /home/maple/probabilistic-valuation-engine-worktrees/988-snapshot-chunk-decomposition
git push -u origin refactor/988-snapshot-chunk-decomposition
```

- [ ] **Step 2: Create PR**

```bash
gh pr create \
  --base develop \
  --head refactor/988-snapshot-chunk-decomposition \
  --title "refactor(988): decompose SnapshotChunkProcessor into parser + pipeline" \
  --body "$(cat <<'EOF'
## Summary
Decompose `SnapshotChunkProcessor.process()` (174 lines, 7 deps) into:
- **`SnapshotChunkParser`** — pure JSON line → `Outcome.Skipped | Outcome.Parsed(items)`. Owns `FlatItem` data class.
- **`SnapshotChunkPipeline`** — generic 3-stage coroutine pipeline with typed lambda slots (`parse`, `calculate`). Owns channel wiring + worker fan-out.
- **`SnapshotChunkProcessor`** — thin orchestrator: source flow + parser + calculator + writer. Owns counter bookkeeping and `calculateItem` (calculation is the processor's domain).
- **`CalculationResultWriter.write`** — signature `ReceiveChannel<CalculationResult>` → `Flow<CalculationResult>` (more general, single call site).

## Files
- Created: `parser/SnapshotChunkParser.kt`, `parser/FlatItem.kt`, `pipeline/SnapshotChunkPipeline.kt` + tests
- Modified: `processor/SnapshotChunkProcessor.kt` (174 → 95 lines), `writer/CalculationResultWriter.kt` (Flow signature)

## Verification
- [x] `./gradlew :module-calculator:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-calculator:test` passes
- [x] `CalculatorChunkProcessingCoordinatorTest` unchanged (mocks `SnapshotChunkProcessor` directly)

## Behavior
Zero behavioral change. Same counters, same `ChunkResult` shape, same `process()` signature. Counter increments preserved exactly:
- `recordCount` — every line entering parse lambda
- `successCount` — `Outcome.Parsed` returned
- `totalItems` — `Outcome.Parsed.items.size`
- `calculatedCount` / `errorCount` — based on `result.status` in `calculateItem`

Closes #988

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Verify PR exists**

Run:
```bash
gh pr view --json number,url,title,state | jq '{number, url, title, state}'
```
Expected: state `OPEN`.

---

## Acceptance criteria

From #988:
- [x] Coroutine pipeline orchestration extracted → `SnapshotChunkPipeline` (Task 4)
- [x] Parse/transform logic extracted → `SnapshotChunkParser` (Task 3)
- [x] `SnapshotChunkProcessor` reduced to pipeline assembly (~95 lines) (Task 6)
- [x] Zero behavioral change (counter logic preserved in `process()`) (Task 6)
- [x] `./gradlew compileKotlin compileJava --continue` passes (Task 7)
- [x] `./gradlew test` passes (Task 7)
