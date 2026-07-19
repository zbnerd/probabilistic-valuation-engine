# Valuation Calculation Kernel Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the calculator's deterministic equipment valuation rules into a pure `module-core` kernel, leave CSV/cache/Spring/metrics in `module-calculator`, and preserve app/web behavior through `module-infra` compatibility types.

**Architecture:** `ProbabilityTableSnapshot` and `ValuationKernel` are immutable pure core objects. Calculator loads the CSV once, maps source items into canonical inputs, caches by complete input plus table/logic version, and maps core results to the existing result JSON. Infra's old public factory/configuration delegates to the same core kernel for legacy callers.

**Tech Stack:** Kotlin and Java/JDK 21, Gradle, pure module-core domain types, Jackson CSV in calculator/infra adapters, Caffeine/off-heap existing cache backends, JUnit 5, jqwik, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-07-19-valuation-kernel-extraction-design.md`

**Depends on:** the artifact plan for calculator storage wiring and the messaging plan for chunk-level `Retryable` propagation. Core extraction can begin after artifact identity is stable.

## Global Constraints

- This is behavior-preserving extraction. Do not change formulas, decorator order, rounding, default starforce options, table mass normalization, result JSON, or Kafka event JSON.
- Preserve the observed `TableMassConfig.STRICT` behavior: normalize by total mass, including outside `1e-5`. Correcting the documented STRICT meaning is a separate change.
- A supported probability key with zero rows is an invariant failure. A populated key whose options contribute zero to the requested stat is a valid `{0: 1.0}` distribution.
- Core calculation production code must not import Spring, Jackson/CSV, filesystem/resource APIs, cache frameworks, `LogicExecutor`, calculator packages, or module-infra packages.
- Only classified source/input errors become per-item `ERROR`. Missing probability, non-finite arithmetic, and other kernel invariants must abort the chunk for Kafka retry/DLT.
- Keep the two CSV resources byte-identical while the infra facade exists. Current baseline is SHA-256 `9a329fe4b861c9f21b69d766e1847e59982c2a02ea4f30c6e5b332a7f2e955c0` and 413,803 lines including the header.
- Do not use `join`, blocking `get`, `runBlocking`, `Thread.sleep`, or coroutine `delay` in new code/tests. Do not add Testcontainers.
- Keep move-only commits separate from formula/behavior changes. Any unexplained golden drift blocks the task.
- Record runtimeClasspath/bootJar size, table-load time/rows, items per second, and allocation evidence before/after in `docs/05_Reports/2026-07-19-valuation-kernel-extraction-evidence.md`.

---

## Task 1: Freeze current outputs, write ADR-747, and record the baseline

**Files:**

- Create: `docs/01_ADR/ADR-747-valuation-kernel-ownership.md`
- Create: `docs/05_Reports/2026-07-19-valuation-kernel-extraction-evidence.md`
- Create: `module-infra/src/test/resources/golden/valuation-kernel-v1-cases.json`
- Create: `module-infra/src/test/kotlin/maple/expectation/application/service/calculator/v4/LegacyValuationGoldenMasterTest.kt`

**Interfaces:**

- Consumes: the current `EquipmentExpectationCalculatorFactory`, current CSV, and representative equipment inputs.
- Produces: reviewed immutable expected component costs/trials and reproducible throughput/allocation baseline.

- [ ] **Step 1: Capture current dependency/resource/runtime evidence**

Run:

```bash
sha256sum module-calculator/src/main/resources/data/cube_probability.csv module-infra/src/main/resources/data/cube_probability.csv
wc -l module-calculator/src/main/resources/data/cube_probability.csv module-infra/src/main/resources/data/cube_probability.csv
./gradlew :module-calculator:dependencies --configuration runtimeClasspath > /tmp/valuation-calculator-runtime-before.txt
./gradlew :module-calculator:bootJar
stat -c '%n %s' module-calculator/build/libs/*.jar
```

Expected: both resources print the hash and line count stated in Global Constraints; Gradle exits `0`. Record exact outputs in the evidence report.

- [ ] **Step 2: Define the golden fixture matrix**

The JSON cases must cover:

- no potential/additional/starforce;
- potential only, additional only, starforce only, and all three in current decorator order;
- BLACK and ADDITIONAL component entry points;
- every grade boundary and representative levels `0`, `100`, `150`, `200`, `250` where supported;
- current star equal to target, normal range, maximum regular star, Noljang `0`, `11`, `12`, `15` boundaries;
- secondary weapon category normalization;
- option ordering, all-stat contribution, target-stat zero contribution, compound options, and unsupported source input.

Each case stores the canonical input plus exact old `blackCubeCost`, `additionalCubeCost`, `starforceCost`, black/additional trials, total, and enhance path. Produce values by invoking the current factory once, review the diff, then make the test read-only; do not retain a regeneration mode in committed test code.

- [ ] **Step 3: Write and run the old-engine golden test**

The test must assert exact integral outputs and the current fixture-specific floating tolerance only for non-integral trials. It must fail on missing expected fields rather than writing them.

Add an evidence-only method enabled by `VALUATION_EVIDENCE_ENABLED=1`. It loads the same read-only golden JSON, runs 25 full-corpus warmup passes followed by 250 full-corpus measured passes in each of five repetitions, and writes each throughput result plus median items/sec to `module-infra/build/reports/valuation-evidence/legacy.json`. Use `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes` around only the measured calculation loop and report bytes/item; fail the evidence run when thread allocation measurement is unsupported instead of substituting an estimate. Also record CSV load duration/row count separately from calculation time. No timing threshold belongs in the JUnit assertion; the evidence report performs the before/after comparison.

Run: `./gradlew :module-infra:test --tests '*LegacyValuationGoldenMasterTest'`

Expected: `BUILD SUCCESSFUL`; every fixture has a frozen expected result.

Capture the baseline with a fresh JVM:

```bash
JAVA_TOOL_OPTIONS='-Xms1g -Xmx1g -XX:+UseG1GC' \
VALUATION_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon :module-infra:test \
  --tests '*LegacyValuationGoldenMasterTest' --rerun-tasks
sha256sum module-infra/build/reports/valuation-evidence/legacy.json
```

Record the fixture SHA/count, commit, JDK, CPU, JVM flags, all five repetitions, median, allocation bytes/item, table-load duration/rows, command, exit code, and JSON SHA in the evidence report.

- [ ] **Step 4: Create ADR-747**

Use the five-section ADR format and state:

```markdown
Move the deterministic V4 equipment valuation subset and immutable probability-table model to module-core, reusing existing core cost/rate/starforce policies. Calculator owns CSV loading, cache, metrics, mapping, Spring wiring, and the observed Noljang target cap. module-infra keeps old public types as delegates for app/web. Extraction preserves observed mass normalization and all golden outputs.
```

- [ ] **Step 5: Commit the baseline before production movement**

```bash
git add docs/01_ADR/ADR-747-valuation-kernel-ownership.md docs/05_Reports/2026-07-19-valuation-kernel-extraction-evidence.md module-infra/src/test/resources/golden/valuation-kernel-v1-cases.json module-infra/src/test/kotlin/maple/expectation/application/service/calculator/v4/LegacyValuationGoldenMasterTest.kt
git commit -m "test: freeze legacy valuation outputs"
```

---

## Task 2: Add immutable probability-table types to core

**Files:**

- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/probability/ProbabilityTableVersion.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/probability/ProbabilityKey.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/probability/ProbabilityRow.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/probability/ProbabilityTableSnapshot.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/error/ValuationExceptions.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/probability/ProbabilityTableSnapshotTest.kt`
- Modify: `module-core/src/test/java/maple/expectation/arch/CoreDependencyRuleTest.java`

**Interfaces:**

- Consumes: already-decoded rows and an explicit logical/content version.
- Produces: an immutable ordered index with validated lookup and no resource/framework dependency.

- [ ] **Step 1: Write failing construction/property tests**

Cover immutable copies, finite rates in `[0,1]`, slot/level/segment validation, exact duplicate multiplicity, conflicting duplicate identity, supported missing key, stable order, and version equality. A jqwik property must prove that mutating the caller's input collection cannot mutate the snapshot.

Run: `./gradlew :module-core:test --tests '*ProbabilityTableSnapshotTest'`

Expected: compilation fails because the table types do not exist.

- [ ] **Step 2: Implement the value types**

```kotlin
data class ProbabilityTableVersion(
    val logical: String,
    val contentSha256: String,
) {
    init {
        require(logical.isNotBlank())
        require(contentSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class ProbabilityKey(
    val cubeType: CubeType,
    val level: Int,
    val part: String,
    val grade: String,
    val slot: Int,
) {
    init {
        require(level >= 0)
        require(part.isNotBlank())
        require(grade.isNotBlank())
        require(slot in 1..3)
    }
}

data class ProbabilityRow(
    val optionName: String,
    val rate: Double,
) {
    init {
        require(optionName.isNotBlank())
        require(rate.isFinite() && rate in 0.0..1.0)
    }
}
```

`ProbabilityTableSnapshot` copies each list with `toList`, stores an unmodifiable map, and has:

```kotlin
fun rows(key: ProbabilityKey): List<ProbabilityRow> =
    index[key]
        ?.takeIf { it.isNotEmpty() }
        ?: throw MissingProbabilityException(key)
```

The builder preserves exact duplicate rows in original order because the current repository includes every row in mass/trials. It rejects the same `(ProbabilityKey, optionName)` associated with different rates. `MissingProbabilityException` extends `ValuationInvariantException`; validation failures at construction extend `ProbabilityTableInitializationException`.

- [ ] **Step 3: Strengthen the architecture rule**

Add an ArchUnit rule limited to `..core.calculation..` forbidding dependencies on:

```text
org.springframework..
com.fasterxml.jackson..
java.nio.file..
org.springframework.cache..
maple.expectation.infrastructure..
maple.calculator..
```

- [ ] **Step 4: Verify and commit table types**

Run:

```bash
./gradlew :module-core:test --tests '*ProbabilityTableSnapshotTest' --tests '*CoreDependencyRuleTest'
```

Expected: all tests pass.

```bash
git add module-core
git commit -m "feat: add immutable probability snapshot"
```

---

## Task 3: Extract pure option distribution and cube-trials calculation

**Files:**

- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/cube/StatContributionExtractor.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/cube/SlotDistributionBuilder.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/cube/PermutationCubeTrialsKernel.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/cube/CubeTrialsKernel.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/cube/DpModeInferrer.kt`
- Modify: `module-core/src/main/kotlin/maple/expectation/core/calculator/CubeRateCalculator.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/cube/SlotDistributionBuilderTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/cube/PermutationCubeTrialsKernelTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/cube/CubeTrialsKernelTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/cube/DpModeInferrerTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculator/CubeRateCalculatorProbabilityRowTest.kt`

**Interfaces:**

- Consumes: pure cube facts plus `ProbabilityTableSnapshot`.
- Produces: deterministic expected trials or a typed input/invariant failure.

- [ ] **Step 1: Write failing parity and edge-case tests**

Port the current extraction rules: direct stat, ALLSTAT-to-individual, zero contribution, primary-stat drift, compound categories, three slots, tail clamp, and Kahan mass. Freeze the current mode choice: explicit DP fields require DP enablement, a non-compound inferred target with confidence at least `0.5` uses DP even when the feature flag is false, and compound/uninferable options use the permutation engine. Add explicit tests for:

```text
rows absent                           => MissingProbabilityException
rows present/all non-target options   => SparsePmf { 0 -> 1.0 }
mass 0.99998 under STRICT baseline     => normalized result, no throw
mass 1.00002 under STRICT baseline     => normalized result, no throw
negative/NaN/infinite row              => rejected at snapshot construction
```

Run: `./gradlew :module-core:test --tests '*SlotDistributionBuilderTest' --tests '*CubeTrialsKernelTest'`

Expected: compilation fails because pure components do not exist.

- [ ] **Step 2: Implement extraction without `LogicExecutor`**

`StatContributionExtractor` directly uses core `StatParser`/`StatType`. It returns empty contributions for non-target/proc text and throws the existing `OptionParseException` for primary-stat-looking drift. No logging, default result, or exception translation belongs in core.

The pure `DpModeInferrer` sums contributions by stat, expands ALLSTAT into STR/DEX/INT/LUK, removes ALLSTAT as a selectable target, rejects mixed option categories, chooses the largest positive contribution with the current enum/map tie order, and calculates `confidence = best / total`. It returns an immutable inference and never mutates an input DTO. Blank/unknown option extraction contributes nothing; primary-stat-looking parse drift remains exceptional rather than being converted to an empty contribution.

- [ ] **Step 3: Preserve observed mass normalization**

The pure `SlotDistributionBuilder` computes Kahan total mass, rejects zero/non-finite total, divides every row by total regardless of configured legacy STRICT/LENIENT label, includes contribution `0`, builds `SparsePmf`, and enforces no negative/NaN/over-one values. Expose the deviation as a returned bounded observation or loader metric input; do not log from core.

- [ ] **Step 4: Preserve the permutation fallback**

Add a `CubeRateCalculator.getOptionRate(optionName: String, rows: List<ProbabilityRow>)` overload that owns the existing blank/unknown/exact-first/missing semantics; keep its old `CubeRate` overload for the excluded legacy app path. `PermutationCubeTrialsKernel` reuses that core overload while porting the remaining current V1 behavior exactly: generate unique permutations of the three ordered target options, sum case probabilities, and return `1 / p` or positive infinity at zero probability. It reads slot-specific rows from the immutable snapshot and does not reintroduce a repository or a second option-rate implementation.

- [ ] **Step 5: Compose existing core probability utilities and mode selection**

`CubeTrialsKernel` uses existing `ProbabilityConvolver` and `TailProbabilityCalculator` with three slots for BLACK/RED/ADDITIONAL. Its contract is:

```kotlin
data class CubeTrialInput(
    val cubeType: CubeType,
    val level: Int,
    val part: String,
    val grade: String,
    val orderedOptions: List<String>,
    val explicitTargetStat: StatType? = null,
    val explicitMinimumTotal: Int? = null,
    val dpEnabled: Boolean = false,
    val enableTailClamp: Boolean = true,
)

enum class CubeTrialMode { EXPLICIT_DP, INFERRED_DP, PERMUTATION }

data class CubeTrialResult(
    val expectedTrials: Double,
    val mode: CubeTrialMode,
)

class CubeTrialsKernel {
    fun calculate(input: CubeTrialInput, table: ProbabilityTableSnapshot): CubeTrialResult
}
```

Require explicit target/minimum to be both present or both absent. Explicit DP with `dpEnabled=false` throws the existing unsupported-engine domain error. Otherwise infer once; use DP only for a valid, non-compound inference with confidence `>= 0.5`; use permutation for every other case. Shadow comparison is observability outside core and cannot select the returned result.

- [ ] **Step 6: Verify parity and commit**

Run:

```bash
./gradlew :module-core:test --tests '*SlotDistributionBuilderTest' --tests '*PermutationCubeTrialsKernelTest' --tests '*CubeTrialsKernelTest' --tests '*DpModeInferrerTest' --tests '*CubeRateCalculatorProbabilityRowTest' --tests '*CoreDependencyRuleTest'
```

Expected: all tests pass and mass-outside-tolerance cases normalize exactly as the legacy implementation.

```bash
git add module-core
git commit -m "refactor: extract pure cube trials kernel"
```

---

## Task 4: Build the pure full valuation kernel from existing core policies

**Files:**

- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/ValuationInput.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/ComponentCosts.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/ComponentTrials.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/ValuationResult.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/calculation/ValuationKernel.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/ValuationKernelTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/calculation/ValuationKernelPropertyTest.kt`

**Interfaces:**

- Consumes: one canonical immutable input, one immutable probability snapshot, and the existing pure core cost/starforce policies.
- Produces: component costs plus table/logic version; no cache, IO, Spring, or DTO serialization.

- [ ] **Step 1: Write failing decorator-order and component tests**

Assert the exact old order base → black → additional → starforce, absent-component `null` behavior, rounded cube trials before multiplication, current-star/target-star handling, regular vs Noljang, and metadata versions. Property tests must prove deterministic repeatability and that option order changes cache/input identity when it changes semantics.

Run: `./gradlew :module-core:test --tests '*ValuationKernelTest' --tests '*ValuationKernelPropertyTest'`

Expected: compilation fails because kernel types do not exist.

- [ ] **Step 2: Implement canonical input/result types**

```kotlin
data class ValuationInput(
    val itemName: String,
    val part: String,
    val equipmentPart: String,
    val itemLevel: Int,
    val currentStar: Int,
    val targetStar: Int,
    val noljang: Boolean,
    val potentialGrade: String?,
    val potentialOptions: List<String>,
    val additionalGrade: String?,
    val additionalOptions: List<String>,
)

data class ComponentCosts(
    val blackCubeCost: Double?,
    val additionalCubeCost: Double?,
    val starforceCost: Double?,
) {
    val totalCost: Double? = listOfNotNull(
        blackCubeCost,
        additionalCubeCost,
        starforceCost,
    ).takeIf { it.isNotEmpty() }?.sum()
}

data class ComponentTrials(
    val blackCubeTrials: Double?,
    val additionalCubeTrials: Double?,
)

data class ValuationResult(
    val costs: ComponentCosts,
    val trials: ComponentTrials,
    val enhancePath: String,
    val tableVersion: ProbabilityTableVersion,
    val logicVersion: String,
)
```

Copy ordered option lists defensively in the mapper before constructing the input. Define logic version as the bounded constant `valuation-v1`.

- [ ] **Step 3: Implement pure policy composition**

`ValuationKernel` injects the existing core `CostCalculationStrategy`; production wiring uses `TableBasedCostStrategy`, so no second cube-cost table is introduced. It uses `CubeTrialsKernel`, preserves each unrounded component trial value, rounds trials with the current `Math.round` behavior only for cost multiplication, and multiplies by the matching cube cost. It builds the exact current enhance-path suffixes in base → black → additional → starforce order. `CubeTrialsKernel` can still evaluate core `CubeType.RED`, but the full valuation result does not add an unused red component; the legacy adapter fills the old record's red fields with `0.0`.

Preserve the observed V4 Noljang behavior rather than correcting it in this extraction: input normalization caps a Noljang target at `NoljangProbabilityCalculator.MAX_NOLJANG_STAR`, but starforce cost still uses `StarforceCalculationEngine` with the current default flags because that is what `EquipmentExpectationCalculatorFactory`/`StarforceLookupAdapter` does today. Add a named golden case proving this. Switching Noljang cost formulas is a separate behavior change. If `StarforceCalculationEngine` differs from the frozen adapter output, adjust the core implementation to exact golden parity before switching callers.

The method signature is:

```kotlin
fun calculate(
    input: ValuationInput,
    table: ProbabilityTableSnapshot,
): ValuationResult
```

- [ ] **Step 4: Verify pure kernel and commit**

Run:

```bash
./gradlew :module-core:test --tests '*ValuationKernelTest' --tests '*ValuationKernelPropertyTest' --tests '*CoreDependencyRuleTest'
```

Expected: all tests pass with zero unexplained golden drift.

```bash
git add module-core
git commit -m "refactor: add pure valuation kernel"
```

---

## Task 5: Add calculator CSV loading, SHA parity, and local Spring wiring

**Files:**

- Modify: `module-calculator/build.gradle`
- Create: `module-calculator/src/main/kotlin/maple/calculator/probability/CsvProbabilityRow.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/probability/CsvProbabilityTableLoader.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/config/ValuationEngineConfiguration.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/probability/CsvProbabilityTableLoaderTest.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/probability/CubeProbabilityResourceParityTest.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/config/ValuationEngineConfigurationTest.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/processor/ValuationPerformanceEvidenceTest.kt`

**Interfaces:**

- Consumes: `classpath:data/cube_probability.csv` and configured pure policy values.
- Produces: one boot-time `ProbabilityTableSnapshot`, one `ValuationKernel`, and no infra calculation bean.

- [ ] **Step 1: Write failing loader/boot tests**

Test exact row mapping, logical version `csv-v1.0`, computed SHA, 413,802 data rows, missing/empty/malformed/negative/non-finite rows, conflict duplicates, and direct surfacing of the original initialization cause. Add a two-resource SHA test that reads both module resource files from repository paths during the compatibility period.

Run:

```bash
./gradlew :module-calculator:test --tests '*CsvProbabilityTableLoaderTest' --tests '*CubeProbabilityResourceParityTest'
```

Expected: compilation fails because the loader does not exist.

- [ ] **Step 2: Add direct calculator dependencies**

Add `implementation(libs.jackson.dataformat.csv)` explicitly. Do not rely on `module-infra` to bring CSV parsing transitively.

- [ ] **Step 3: Implement fail-fast loading**

`CsvProbabilityTableLoader.load()` reads `ClassPathResource`, streams rows into the core snapshot builder, calculates SHA-256 over the exact resource bytes, validates non-empty data, and returns the immutable snapshot. Use `runCatching` only to attach the typed `ProbabilityTableInitializationException` while preserving its cause. Record load duration/row count and only a short version label in metrics.

- [ ] **Step 4: Replace calculator's infra import facade**

`CalculatorEngineConfiguration` becomes:

```kotlin
@Configuration
@Import(ValuationEngineConfiguration::class)
class CalculatorEngineConfiguration
```

`ValuationEngineConfiguration` constructs loader, snapshot, the existing `TableBasedCostStrategy`, cube trials kernel, and valuation kernel. It must not import `CalculatorEngineAutoConfiguration` or `CoreExecutorConfig`.

- [ ] **Step 5: Verify local context and resource parity**

Run:

```bash
./gradlew :module-calculator:test --tests '*CsvProbabilityTableLoaderTest' --tests '*CubeProbabilityResourceParityTest' --tests '*ValuationEngineConfigurationTest'
```

Expected: all tests pass; SHA is the baseline value until an explicitly reviewed data update changes both copies together.

`ValuationPerformanceEvidenceTest` is enabled only by `VALUATION_EVIDENCE_ENABLED=1`. It reads the exact golden fixture by repository path, verifies its SHA before measuring, and runs the same 25/250/five-repetition direct-kernel protocol and allocation measurement as Task 1. Write the same schema to `module-calculator/build/reports/valuation-evidence/kernel.json`; include table-load duration and 413,802 rows, but exclude fixture parsing/table loading from the kernel timing loop.

- [ ] **Step 6: Commit calculator loading/wiring**

```bash
git add module-calculator
git commit -m "refactor: load valuation table in calculator"
```

---

## Task 6: Put complete input/table/logic identity behind `ValuationCache`

**Files:**

- Create: `module-calculator/src/main/kotlin/maple/calculator/processor/ValuationCache.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/metrics/ValuationCacheMetrics.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/processor/ValuationCacheTest.kt`

**Interfaces:**

- Consumes: normalized `ValuationInput`, snapshot version, logic version, and current cache backend.
- Produces: the same `ValuationResult` as direct calculation; cache failure is observable but cannot alter the result.

- [ ] **Step 1: Write failing cache identity/fallback tests**

Change one field at a time: part, equipment part, level, current star, target star, Noljang, both grades, and each ordered option. Every output-affecting change must miss. Equal canonical inputs with equal table/logical versions must hit. Different table checksum or logic version must miss. Backend get/put/serialization failures must call the kernel directly and increment exactly one bounded failure metric. Kernel failure must propagate and must not be converted to an empty result.

Run: `./gradlew :module-calculator:test --tests '*ValuationCacheTest'`

Expected: compilation fails because `ValuationCache` does not exist; current `CalculationCache.CacheKey` also misses required identity fields.

- [ ] **Step 2: Implement complete cache identity**

```kotlin
data class ValuationCacheKey(
    val input: ValuationInput,
    val tableLogicalVersion: String,
    val tableContentSha256: String,
    val logicVersion: String,
)
```

Do not include OCID. Do not include preset number because it does not participate in the kernel result; if a golden case proves otherwise, add the actual output-affecting fact to `ValuationInput` rather than appending transport context to the key.

- [ ] **Step 3: Implement failure-isolated read/compute/write**

Use separate `runCatching` boundaries for cache get and put. A get failure records a metric and computes; a put failure records a metric and still returns the computed value. The kernel call itself is outside recovery/default logic so its exception propagates unchanged.

- [ ] **Step 4: Keep the new cache unwired until the processor switch**

Do not annotate `ValuationCache` as a component yet and do not change `CalculationCache` in this commit. Task 7 switches `CacheBackendConfig`, `SnapshotChunkProcessor`, converter, and metrics together, then deletes `CalculationCache`; this avoids two erased `OffHeapCacheBackend` generic beans and prevents a half-migrated runtime.

- [ ] **Step 5: Verify and commit cache boundary**

Run:

```bash
./gradlew :module-calculator:test --tests '*ValuationCacheTest' --tests '*OffHeapSerializedBackendTest' --tests '*EquipmentCalculationInputConverterTest'
```

Expected: all tests pass; cache errors return direct results with identical metadata.

```bash
git add module-calculator
git commit -m "refactor: cache canonical valuation results"
```

---

## Task 7: Switch item processing and stop collapsing kernel invariants

**Files:**

- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/EquipmentCalculationInputConverter.kt`
- Delete: `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/config/CacheBackendConfig.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/metrics/CacheMetrics.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/cache/CacheBackendFactory.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/cache/OffHeapSerializedBackend.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/processor/ValuationFailurePolicy.kt`
- Create: `module-calculator/src/test/kotlin/maple/calculator/processor/SnapshotChunkProcessorFailurePolicyTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/processor/ValuationPerformanceEvidenceTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/cache/OffHeapSerializedBackendTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/cache/CacheBackendFactoryTest.kt`
- Modify: `module-calculator/src/test/kotlin/maple/calculator/processor/EquipmentCalculationInputConverterTest.kt`

**Interfaces:**

- Consumes: parsed equipment items and the local `ValuationCache`.
- Produces: existing `CalculationResult`; only source-domain errors become item `ERROR`, invariants escape to the chunk boundary.

- [ ] **Step 1: Write failing error-taxonomy tests**

Assert malformed source/unsupported input yields one existing-schema `ERROR` item. Assert `MissingProbabilityException`, probability invariant failure, arithmetic non-finite result, cache backend failure followed by kernel failure, and unexpected runtime error fail the processing stage/chunk instead of producing empty `ComponentCosts`. Add backend cases proving two equal-but-distinct `ValuationCacheKey` instances hit the same off-heap entry, two unequal keys do not collide, and a stored `ValuationResult` decodes back to its concrete type rather than `LinkedHashMap`.

Run: `./gradlew :module-calculator:test --tests '*SnapshotChunkProcessorFailurePolicyTest'`

Expected: invariant tests fail because current `calculateItem` catches every throwable and emits an empty component result.

- [ ] **Step 2: Implement explicit classification**

```kotlin
sealed interface ItemFailureDecision {
    data class SourceError(val message: String) : ItemFailureDecision
    data class AbortChunk(val cause: Throwable) : ItemFailureDecision
}
```

`ValuationFailurePolicy` maps only the approved input/parser exception types to `SourceError`; `ValuationInvariantException`, `ProbabilityTableInitializationException`, and unknown causes map to `AbortChunk` preserving the original throwable.

- [ ] **Step 3: Make off-heap identity content-stable and typed**

Change `CacheBackendFactory.create` to accept the Boot-configured Kotlin-capable `ObjectMapper` and pass it with `keyClass`/`valueClass` into `OffHeapSerializedBackend`; remove its private plain `ObjectMapper()` default. Replace `identityHashCode` with a 128-bit digest key derived from SHA-256 of canonical Jackson key bytes; store those full canonical key bytes in the entry and compare them on lookup to defend against digest collision. Decode values with `mapper.readValue(bytes, valueClass)`. Preserve the existing max-entry eviction, direct value buffer, backend stats, and fail-soft get/put contract.

Update `CacheBackendConfig` to inject the application `ObjectMapper`, expose exactly one `OffHeapCacheBackend<ValuationCacheKey, ValuationResult>`, and expose one `ValuationCache` bean. Update `CacheMetrics` to inject `ValuationCache`. Delete `CalculationCache` after `rg` shows no remaining caller; update converter signatures/tests to core `ComponentCosts`/`ValuationResult`.

Extend the evidence test with a cache-hit phase that preloads every canonical fixture key before timing, then runs the identical 25/250/five-repetition loop through `ValuationCache`. Record cache-hit items/sec and allocation bytes/item in `kernel.json`; assert the measured phase has zero misses, but keep performance thresholds in the evidence comparison rather than the test.

- [ ] **Step 4: Replace catch-all default output**

Use this control shape in `SnapshotChunkProcessor.calculateItem`:

```kotlin
return runCatching { valuationCache.getOrCalculate(input) }
    .fold(
        onSuccess = { result -> converter.toCalculationResult(flatItem, result) },
        onFailure = { failure ->
            when (val decision = failurePolicy.classify(failure)) {
                is ItemFailureDecision.SourceError -> converter.toErrorResult(flatItem, decision.message)
                is ItemFailureDecision.AbortChunk -> throw decision.cause
            }
        },
    )
```

Keep masked OCID/preset diagnostics in logs and constant metric tags. Do not manufacture empty component costs for an invariant/cache+kernel failure.

- [ ] **Step 5: Verify chunk propagation and wire compatibility**

Run:

```bash
./gradlew :module-calculator:test --tests '*SnapshotChunkProcessorFailurePolicyTest' --tests '*CalculatorChunkProcessingCoordinatorTest' --tests '*EquipmentCalculationInputConverterTest' --tests '*OffHeapSerializedBackendTest' --tests '*CacheBackendFactoryTest' --tests '*ValuationCacheTest'
```

Expected: tests pass; result JSON fields are unchanged, and invariant failures reach the messaging handler as retryable chunk failures.

- [ ] **Step 6: Commit processor migration**

```bash
git add module-calculator
git commit -m "fix: propagate valuation kernel invariants"
```

---

## Task 8: Delegate legacy infra callers, remove calculator infra dependency, and prove parity

**Files:**

- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CubeProbabilityRepositoryImpl.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/calculation/LegacyProbabilityTableLoader.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/calculation/LegacyValuationConfiguration.kt`
- Modify: `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/EquipmentExpectationCalculatorFactory.java`
- Create: `module-infra/src/main/java/maple/expectation/application/service/calculator/v4/CoreValuationCalculatorAdapter.java`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculatorEngineAutoConfiguration.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/application/service/calculator/v4/CoreLegacyValuationParityTest.kt`
- Modify: `module-calculator/build.gradle`
- Modify: `docs/05_Reports/2026-07-19-valuation-kernel-extraction-evidence.md`

**Interfaces:**

- Consumes: old app/web factory/repository/config APIs and the new core kernel.
- Produces: stable legacy types backed by the core implementation, while calculator runtime contains no module-infra.

- [ ] **Step 1: Write failing old-vs-new parity tests**

Run every frozen case through both the legacy public factory and direct core kernel. Assert costs, trials, totals, path, error type, and table logical version. Add app-facing bean-name/context tests for `EquipmentExpectationCalculatorFactory`, `CubeProbabilityRepository`, and `CalculatorEngineAutoConfiguration`.

Run: `./gradlew :module-infra:test --tests '*CoreLegacyValuationParityTest' --tests '*LegacyValuationGoldenMasterTest'`

Expected: parity test initially fails because the legacy factory is still the independent implementation.

- [ ] **Step 2: Build the infra snapshot with the same core contract**

`LegacyProbabilityTableLoader` owns its Jackson/resource adapter and returns `ProbabilityTableSnapshot`. `CubeProbabilityRepositoryImpl` preserves its old methods/FQN/bean name by projecting snapshot rows back to `CubeProbability` and returns `snapshot.version.logical` from `getCurrentTableVersion`. It no longer uses `LogicExecutor` for boot loading.

- [ ] **Step 3: Delegate the old factory**

Modify the existing Spring bean in place so its FQN, bean identity, and four public creation methods remain callable, while its constructor now injects the core kernel/snapshot adapter instead of `CubeTrialsProvider`, `CubeCostPolicy`, and `StarforceLookupPort`. `CoreValuationCalculatorAdapter` implements the old `EquipmentExpectationCalculator` interface over one immutable `ValuationResult`, including exact enhance path and black/additional trials; it returns `0.0` for the legacy record's unused red cost/trials. Component-only factory methods build the corresponding canonical input; full calculation uses black/additional/starforce order. Do not modify the separate V1/V2 `CubeServiceImpl` app path in this program.

- [ ] **Step 4: Remove calculator's direct infra dependency**

Delete `implementation project(':module-infra')` from `module-calculator/build.gradle`. Add only the already-extracted direct modules it uses (`module-pipeline-artifact`, `module-pipeline-messaging`) plus existing `module-common`/`module-core`. Run dependency insight:

```bash
./gradlew :module-calculator:dependencyInsight --dependency module-infra --configuration runtimeClasspath
```

Expected: no matching dependency.

- [ ] **Step 5: Run full calculation and compatibility verification**

Run:

```bash
./gradlew :module-core:test
./gradlew :module-calculator:test
./gradlew :module-infra:test --tests '*CoreLegacyValuationParityTest' --tests '*LegacyValuationGoldenMasterTest'
./gradlew :module-app:test --tests '*CubeServiceTest'
./gradlew compileKotlin compileJava --continue
```

Expected: commands exit `0`; frozen parity has zero unexplained drift and core architecture rules remain green.

- [ ] **Step 6: Capture after performance/runtime evidence**

Run:

```bash
JAVA_TOOL_OPTIONS='-Xms1g -Xmx1g -XX:+UseG1GC' \
VALUATION_EVIDENCE_ENABLED=1 \
./gradlew --no-daemon :module-calculator:test \
  --tests '*ValuationPerformanceEvidenceTest' --rerun-tasks
sha256sum module-calculator/build/reports/valuation-evidence/kernel.json
```

Use the same golden input corpus, JVM options, warmup count, repetition count, cache state, and table resource as Task 1. Record table-load duration/row count, every repetition and median direct-kernel items/sec, cache-hit items/sec, allocations, calculator bootJar bytes, runtimeClasspath, and application startup time. Start calculator on port `8082`, verify `/actuator/health`, and exercise one non-destructive existing chunk fixture without DB reset flags.

Expected: no unapproved output drift or material performance/allocation regression; calculator starts without infra calculation/executor beans.

- [ ] **Step 7: Commit compatibility/dependency/evidence**

```bash
git add module-infra module-calculator docs/05_Reports/2026-07-19-valuation-kernel-extraction-evidence.md
git commit -m "refactor: switch calculator to core valuation kernel"
```

## Plan Completion Gate

- [ ] `git diff --check` is clean.
- [ ] `rg -n 'maple\.expectation\.(application\.service\.calculator\.v4|infrastructure\.)' module-calculator/src/main` returns no matches.
- [ ] `module-calculator` runtimeClasspath does not contain `module-infra`.
- [ ] Core architecture, probability edge cases, old/new golden parity, cache fallback, and chunk error-taxonomy tests pass.
- [ ] The named Noljang golden case proves target capping and current regular-starforce cost parity; no app/web-only `NoljangProbabilityTable` refactor is included.
- [ ] CSV SHA parity and before/after performance evidence are recorded.
