# SynchronizerMetrics Decomposition Design

- Date: 2026-06-06
- Issue: #1066
- Branch: `refactor/issue-1066-sync-metrics-split`
- Owner: TBD

---

## 1. Background / Problem

### Background

`module-synchronizer/.../metrics/SynchronizerMetrics.kt` is 176 lines and bundles two unrelated concerns:

1. **Meter registration** (~67 lines) — 19 counter/timer/summary/gauge declarations in property initialisers plus a `init {}` block for the gauge
2. **Recording methods** (~90 lines) — public API invoked from consumers (ChunkConsumerTemplate, ChunkDataReader, ChunkDocumentWriter, DefaultChunkProcessor, ChunkDocumentTransformer, SynchronizerChunkMetricsListener)

Adding a new metric currently requires editing the same class as the recording logic, which is the wrong locality: the registration is structural (one-time at startup) and the recording is behavioural (called from business code paths).

### Problem

- Registration boilerplate and recording logic are co-mingled in a single file
- A new metric touches the file that records values — separation of concerns is missing
- Tests construct `SynchronizerMetrics(SimpleMeterRegistry())` to get a single metric, paying for 19 meter inits

### Goal

Separate meter registration from recording so that:

1. Adding a new metric requires editing **one** class (`SynchronizerMeterRegistry`)
2. Recording logic is the only thing in `SynchronizerMetrics`
3. Consumers are unaffected (public API of `SynchronizerMetrics` unchanged)

---

## 2. Decision

> Split `SynchronizerMetrics` into two `@Component` classes: `SynchronizerMeterRegistry` (meter creation, 1-time init) and `SynchronizerMetrics` (recording methods only, holding a reference to the registry).

```text
module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/
├── SynchronizerMeterRegistry.kt   (NEW — 80 lines)
├── SynchronizerMetrics.kt          (refactored — 90 lines, no MeterRegistry)
└── SynchronizerChunkMetricsListener.kt   (unchanged)
```

---

## 3. Component Contracts

### 3.1 `SynchronizerMeterRegistry` (new)

```kotlin
@Component
class SynchronizerMeterRegistry(private val registry: MeterRegistry) {
    // Counter getters
    fun chunksReceived(): Counter
    fun chunksProcessed(): Counter
    fun chunksFailed(): Counter
    fun documentsProcessed(): Counter
    fun itemsProcessed(): Counter
    fun preUpsertCompressedBytesTotal(): Counter
    fun preUpsertUncompressedBytesTotal(): Counter
    fun preUpsertJsonRowsTotal(): Counter

    // Gauge source
    fun chunksProcessing(): AtomicInteger   // gauge bound in init

    // Timer getters
    fun chunkTimer(): Timer
    fun fileReadTimer(): Timer
    fun documentBuildTimer(): Timer
    fun mainUpsertTimer(): Timer

    // DistributionSummary getters
    fun chunkDocumentsSummary(): DistributionSummary
    fun chunkItemsSummary(): DistributionSummary
    fun chunkBytesSummary(): DistributionSummary
    fun documentEquipmentSummary(): DistributionSummary
    fun preUpsertCompressedSummary(): DistributionSummary
    fun preUpsertUncompressedSummary(): DistributionSummary
    fun preUpsertCompressionRatio(): DistributionSummary

    // Status / execution factory methods (preserved verbatim)
    fun statusCounter(status: String): Counter
    fun chunkExecutionCounter(name: String, executionType: ChunkExecutionType): Counter
    fun chunkExecutionSkippedCounter(executionType: ChunkExecutionType, status: ChunkExecutionStatus): Counter
    fun chunkExecutionFailedCounter(
        executionType: ChunkExecutionType,
        status: ChunkExecutionStatus,
        reason: String,
    ): Counter
}
```

All meters created in property initialisers / `init {}` block, identical to the current `SynchronizerMetrics` constructor. No recording logic.

### 3.2 `SynchronizerMetrics` (refactored)

```kotlin
@Component
class SynchronizerMetrics(private val meterRegistry: SynchronizerMeterRegistry) {
    fun incrementReceived() = meterRegistry.chunksReceived().increment()
    fun incrementProcessing() = meterRegistry.chunksProcessing().incrementAndGet()
    fun decrementProcessing() = meterRegistry.chunksProcessing().decrementAndGet()
    // ... all public methods preserved, body delegates to meterRegistry
    fun chunkTimer(): Timer = meterRegistry.chunkTimer()
    // ... etc.
}
```

Public API of `SynchronizerMetrics` is **byte-for-byte identical**. Consumers (`ChunkConsumerTemplate`, `ChunkDataReader`, `ChunkDocumentWriter`, `DefaultChunkProcessor`, `ChunkDocumentTransformer`, `SynchronizerChunkMetricsListener`, tests) do not change.

---

## 4. Trade-offs

### Sensitivity

* Number of future metrics added to synchronizer
* Test surface that constructs `SynchronizerMetrics` directly (currently 1 test: `DefaultChunkProcessorTest`)

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Two `@Component` classes | Single responsibility, registration vs recording localised | One extra Spring bean (negligible startup cost) |
| SynchronizerMetrics holds registry reference (not MeterRegistry) | Recording is one indirection; can mock registry in tests | Indirect call cost (negligible — JVM inlines) |
| Public API of SynchronizerMetrics unchanged | Zero consumer-side diff | None |

### Risk

* Indirect call (`meterRegistry.chunksReceived().increment()`) is one extra method call per recording — negligible runtime cost
* Tests that build `SynchronizerMetrics(SimpleMeterRegistry())` must be updated to use `SynchronizerMeterRegistry(SimpleMeterRegistry())` — affects `DefaultChunkProcessorTest`

### Non-Risk

* Prometheus metric names unchanged (gauge/counter/timer creation logic identical)
* Lifecycle semantics identical (both classes are `@Component` singletons, instantiated once at startup)
* Consumer call sites unchanged

---

## 5. Migration

1. Create `SynchronizerMeterRegistry` with the 19 meter declarations + factory methods copied verbatim
2. Refactor `SynchronizerMetrics`:
   - Constructor: replace `MeterRegistry` parameter with `SynchronizerMeterRegistry`
   - Remove all 19 meter property declarations + `init {}` gauge block
   - Update each recording method body to call `meterRegistry.x().y()`
3. Update `DefaultChunkProcessorTest` (only test that constructs `SynchronizerMetrics` directly) to construct via `SynchronizerMeterRegistry(SimpleMeterRegistry())`
4. Run `./gradlew :module-synchronizer:test`
5. Run `./gradlew compileKotlin compileJava --continue`
6. Run bootRun smoke check (per workflow-rules §10)

---

## 6. Test Strategy

Existing tests pass unchanged (consumer behaviour preserved). Only test file modification: `DefaultChunkProcessorTest` line 27 currently constructs `SynchronizerMetrics(SimpleMeterRegistry())` — change to `SynchronizerMetrics(SynchronizerMeterRegistry(SimpleMeterRegistry()))`.

No new tests required — this is a structural refactor with no behaviour change.

---

## 7. Acceptance Criteria

- [ ] `SynchronizerMeterRegistry` separated (owns meter creation)
- [ ] `SynchronizerMetrics` contains only recording methods (no `MeterRegistry` reference)
- [ ] Public API of `SynchronizerMetrics` byte-for-byte identical
- [ ] New metric addition requires editing `SynchronizerMeterRegistry` only
- [ ] `./gradlew :module-synchronizer:test` passes
- [ ] `./gradlew compileKotlin compileJava --continue` passes

---

## 8. Summary

> Split `SynchronizerMetrics` into `SynchronizerMeterRegistry` (registration) + `SynchronizerMetrics` (recording). Consumers and public API unchanged. Adding a new metric touches one class.
