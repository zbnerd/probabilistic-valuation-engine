# Issue 988 — SnapshotChunkProcessor Decomposition Design

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Spec: [#988](https://github.com/zbnerd/probabilistic-valuation-engine/issues/988)
- Branch: `refactor/988-snapshot-chunk-decomposition`

---

## 1. Background / Problem

### Background

`module-calculator/.../processor/SnapshotChunkProcessor.kt` is **174 lines** with **7 dependencies** and **3 distinct responsibilities** mixed in a single `process()` method:

1. **I/O & streaming** — gzip JSONL read from `ObjectStorage`, channel-based streaming
2. **Parse & calculate** — JSON line → `FlatItem` → `CalculationResult`
3. **Concurrency orchestration** — 3-stage coroutine pipeline (`reader → parser → calculator → writer`)

The file is hard to test in isolation (parsers and pipelines cannot be exercised without the full Spring graph) and the orchestration code dominates the parse/calculate logic.

### Problem

The monolithic `process()` method interleaves three concerns:
- Channel construction (3 channels × 1 capacity × 1 close call each)
- Worker fan-out (`repeat(workerCount) { launch { ... } }`) for parser and calculator stages
- JSON parsing + counter mutation + flat-item construction
- Calculation invocation + error wrapping + result emission

### Goal

Split into focused units with clear, testable boundaries. Zero behavioral change. Same public API (`process(event, resultObjectKey): ChunkResult`).

---

## 2. Decision

> Extract two focused `@Component` classes: `SnapshotChunkParser` (pure parse logic) and `SnapshotChunkPipeline` (coroutine orchestration). Refactor `SnapshotChunkProcessor` to a thin orchestrator. Change `CalculationResultWriter` signature to accept `Flow<CalculationResult>` instead of `ReceiveChannel<CalculationResult>`.

### Component map

```
┌────────────────────────────────────────────────────────────────────┐
│  SnapshotChunkProcessor (thin orchestrator, 7 deps → 7 deps)       │
│    ├── ObjectStorage            (read source stream)               │
│    ├── GzipJsonlSnapshotRecordReader (Flow<String>)               │
│    ├── SnapshotChunkPipeline    (NEW — coroutine orchestration)    │
│    ├── SnapshotChunkParser      (NEW — JSON → FlatItem)            │
│    ├── CalculationCache         (per-item cost computation)        │
│    ├── ObjectMapper             (sample-log serialization)         │
│    └── CalculationResultWriter  (Flow<CalculationResult> → file)   │
└────────────────────────────────────────────────────────────────────┘
```

### Responsibilities

| Class | Inputs | Outputs | Side effects |
|---|---|---|---|
| `SnapshotChunkParser` | `line: String` | `Outcome.Skipped` / `Outcome.Parsed(items: List<FlatItem>)` | None (pure) |
| `SnapshotChunkPipeline` | `source: Flow<String>`, `parse: suspend (String) → Outcome`, `calculate: suspend (FlatItem) → CalculationResult` | `Flow<CalculationResult>` | Coroutine lifecycle, Channel wiring |
| `SnapshotChunkProcessor` | `event: SnapshotChunkReadyEvent`, `resultObjectKey: String` | `ChunkResult` | Counter bookkeeping, sample logging, error wrapping |
| `CalculationResultWriter` | `objectKey: String`, `results: Flow<CalculationResult>` | `WriteResult` | Writes gzipped JSONL to `ObjectStorage` |

---

## 3. Trade-offs

### Sensitivity

- **Channel capacity** (`properties.channelCapacity`) — back-pressure bound. Reader/parser/calculator stages depend on this.
- **Worker count** (`properties.workerCount`) — fan-out for parser and calculator stages. Validated `> 0`.
- **Counter accuracy** — must match original behavior:
  - `recordCount`: every line read (parser stage)
  - `successCount`: lines with `status == "SUCCESS"` and non-null body (parser stage)
  - `totalItems`: items emitted (parser stage)
  - `calculatedCount`: results with `status != "ERROR"` (calculator stage)
  - `errorCount`: results with `status == "ERROR"` (calculator stage)
- **`objectStorage.openInputStream(...).use {}`** — must be wrapped to avoid leaking the connection. Currently relies on `processItems` closing the result channel + `coroutineScope` completing.

### Trade-off

| Choice | Gained | Given up |
|---|---|---|
| 2 extractions (parser + pipeline) | Minimal blast radius, issue's literal scope | `calculateItem` still lives in processor (mild residual coupling) |
| Channel → Flow in writer | One less forced channel ownership, idiomatic coroutines | 1-line signature change in 1 file |
| Keep `FlatItem` in parser package | Co-locates type with its only producer | Slight package churn |

### Risk

- `Flow<CalculationResult>` collected by writer must close cleanly on cancellation. Original `ReceiveChannel` auto-closed; Flow needs explicit `flow { ... }` block with `try/finally` for resource safety.
- Counter increments scattered across parse/calculate lambdas; easy to mis-place during refactor. Mitigation: keep counter creation in `process()` and pass `AtomicInteger` to the lambdas (no shared state class).

### Non-Risk

- `process()` public signature unchanged → `CalculatorChunkProcessingCoordinator` and its test need no changes.
- `FlatItem` is package-private; only used within `SnapshotChunkProcessor` (verified by `grep`).
- `CalculationResultWriter` has no existing unit test; signature change is safe.

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After | Notes |
|---|---:|---:|---|
| `SnapshotChunkProcessor.kt` lines | 174 | ~80 | Orchestrator only |
| `SnapshotChunkProcessor` constructor deps | 7 | 7 | Same deps, different composition |
| New components | 0 | 2 | `SnapshotChunkParser`, `SnapshotChunkPipeline` |
| Behavioral change | — | 0 | Counters, output, errors identical |

### Verification commands

```bash
./gradlew :module-calculator:compileKotlin compileJava --continue
./gradlew :module-calculator:test
```

Compile + unit test pass. No integration test execution (per project policy: Testcontainers excluded from default test run).

### Runtime verification (per project workflow)

Per `.claude/rules/workflow-rules.md` §10, runtime server check is required before merge:

```bash
set -a && source .env && set +a
./gradlew :module-calculator:bootRun &
# Wait for health check
grep "Calculation completed" module-calculator/logs/app.log | tail -5
grep "ERROR" module-calculator/logs/app.log | tail -10
```

Success criteria: same `Calculation completed` log + same counter values as pre-refactor.

---

## 5. Summary

> Split `SnapshotChunkProcessor` into a pure `SnapshotChunkParser` (JSON → items) and a generic `SnapshotChunkPipeline` (coroutine orchestration), letting the processor become a thin counter-bookkeeping orchestrator. One signature change (`writer.write` Channel → Flow) is the only external API touch.

---

## Appendix A — File structure

```
module-calculator/src/main/kotlin/maple/calculator/
├── parser/
│   ├── SnapshotChunkParser.kt          (NEW)
│   └── SnapshotEquipmentParser.kt      (unchanged)
├── pipeline/
│   └── SnapshotChunkPipeline.kt        (NEW)
├── processor/
│   └── SnapshotChunkProcessor.kt       (MODIFIED — thin orchestrator)
├── writer/
│   └── CalculationResultWriter.kt      (MODIFIED — Flow signature)
└── ...
```

## Appendix B — Key signatures

```kotlin
// SnapshotChunkParser.kt
@Component
class SnapshotChunkParser(
    private val objectMapper: ObjectMapper,
    private val equipmentParser: SnapshotEquipmentParser,
) {
    sealed class Outcome {
        data object Skipped : Outcome()
        data class Parsed(val items: List<FlatItem>) : Outcome()
    }

    fun parse(line: String): Outcome { ... }
}

data class FlatItem(
    val ocid: String,
    val presetNo: Int,
    val item: EquipmentItem,
)
```

```kotlin
// SnapshotChunkPipeline.kt
@Component
class SnapshotChunkPipeline(
    private val properties: PipelineProperties,
) {
    private val workerCount: Int = requireNotNull(properties.workerCount.takeIf { it > 0 }) {
        "calculator.pipeline.worker-count must be positive: ${properties.workerCount}"
    }

    suspend fun run(
        source: Flow<String>,
        parse: suspend (String) -> SnapshotChunkParser.Outcome,
        calculate: suspend (FlatItem) -> CalculationResult,
    ): Flow<CalculationResult> = coroutineScope { ... }
}
```

```kotlin
// CalculationResultWriter.kt — signature change only
suspend fun write(
    objectKey: String,
    results: Flow<CalculationResult>,  // was: ReceiveChannel<CalculationResult>
): WriteResult { ... }
```

```kotlin
// SnapshotChunkProcessor.kt — refactored (sketch)
suspend fun process(event: SnapshotChunkReadyEvent, resultObjectKey: String): ChunkResult {
    val recordCount = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    val totalItems = AtomicInteger(0)
    val calculatedCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)

    val source = objectStorage.openInputStream(event.objectKey).use { stream ->
        jsonlReader.readLines(stream)
    }

    val resultFlow = pipeline.run(
        source = source,
        parse = { line ->
            recordCount.incrementAndGet()
            when (val outcome = parser.parse(line)) {
                is SnapshotChunkParser.Outcome.Skipped -> outcome
                is SnapshotChunkParser.Outcome.Parsed -> {
                    successCount.incrementAndGet()
                    totalItems.addAndGet(outcome.items.size)
                    outcome
                }
            }
        },
        calculate = { item -> calculateItem(item, calculatedCount, errorCount) },
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
```
