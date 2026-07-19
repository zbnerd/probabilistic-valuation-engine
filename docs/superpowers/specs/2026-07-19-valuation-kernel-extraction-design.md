# Valuation Calculation Kernel Extraction

- **Status**: Approved
- **Priority**: P1
- **Date**: 2026-07-19
- **Program**: [ETL module-infra Deepening Program](2026-07-19-etl-infra-deepening-program-design.md)

---

## 1. Scope

module-calculator가 사용하는 equipment valuation의 순수 계산 규칙을 `module-core`로 이동한다. CSV resource loading, cache, metrics, Spring wiring, parser/result serialization은 `module-calculator`에 남긴다. module-app/web가 사용하는 기존 type/bean은 `module-infra` compatibility facade로 유지한다.

대상에는 V4 equipment factory/decorators, cube trial computation의 pure subset, cube cost policy의 pure representation, starforce lookup, Noljang 규칙, canonical input/output가 포함된다.

## 2. Non-goals

- 계산 공식 또는 rounding 결과 변경
- cube V1/V2 전체 서비스를 한 번에 core로 이동
- module-app/web endpoint나 response DTO 변경
- CSV를 DB table로 전환
- calculator result JSON 또는 Kafka event schema 변경
- cache backend 교체 자체
- flame, PGMQ worker, JPA calculation result 경로 이동

## 3. Problem

`module-calculator`는 현재 `CalculatorEngineAutoConfiguration`과 `CoreExecutorConfig`를 통해 module-infra의 계산 엔진을 가져온다. pure calculation, Spring component, `LogicExecutor`, classpath CSV repository, cache가 한 graph에 있어 계산 테스트가 Spring wiring을 요구하고 calculator가 module-infra 전체 runtime classpath를 받는다.

`CubeProbabilityRepositoryImpl`은 이름과 package는 persistence지만 실제로는 시작 시 CSV 413,802개 row를 메모리에 적재하는 resource adapter다. Noljang 확률은 module-core와 module-infra에 중복돼 drift 가능성이 있다.

## 4. Decision

### 4.1 Ownership

```text
module-core
  └─ maple.expectation.core.calculation
       ├─ ValuationKernel
       ├─ ValuationInput / ComponentCosts / ValuationResult
       ├─ ProbabilityTableSnapshot / ProbabilityKey / ProbabilityRow
       ├─ cube policies and decorators as pure objects
       └─ canonical NoljangProbabilityCalculator

module-calculator
  ├─ probability/CsvProbabilityTableLoader
  ├─ cache/ValuationCache + existing backend adapter
  ├─ config/ValuationEngineConfiguration
  ├─ processor input/output mapping
  └─ metrics

module-infra
  └─ legacy CalculatorEngineAutoConfiguration and old public types
       delegate to core kernel for module-app/module-web compatibility
```

Core production code may not import Spring, Jackson/CSV, `LogicExecutor`, cache annotations/backends, filesystem/resource classes, or module-infra packages.

### 4.2 Kernel contract

`ValuationKernel.calculate(input, table)` is a deterministic function from canonical `ValuationInput` and immutable `ProbabilityTableSnapshot` to `ValuationResult`.

`ValuationInput` contains only calculation facts:

- item identity needed by formulas
- normalized part/equipment part
- item level and preset
- current/target star and Noljang flag
- potential/additional grade and ordered option values

`ValuationResult` contains `ComponentCosts(blackCubeCost, additionalCubeCost, starforceCost)` plus internal metadata `tableVersion` and `logicVersion`. module-calculator maps this to the existing wire `CalculationResult` without adding or renaming serialized fields during this migration.

The kernel does not:

- read a resource or mutable repository
- perform caching
- log or emit metrics
- catch infrastructure errors
- create threads/futures
- depend on OCID, Kafka, ObjectStorage, Spring bean lifecycle

### 4.3 Immutable probability table

`ProbabilityTableSnapshot` is constructed once at boot and then immutable.

- index key: cube type, level, normalized part, grade, slot
- values: immutable ordered probability rows
- version: configured logical version plus content checksum captured during load
- duplicate/conflicting keys and invalid mass are rejected during construction
- lookup for a supported key with no rows is an explicit `MissingProbabilityException`

`CsvProbabilityTableLoader` lives in module-calculator and converts `data/cube_probability.csv` into the snapshot. It owns Jackson CSV and `ClassPathResource`. Missing resource, parse error, empty dataset, invalid row, or inconsistent probability mass fails application startup. It does not use `LogicExecutor` because boot initialization must surface the original cause directly.

The legacy module-infra loader used by app/web builds the same core snapshot contract while its old public repository facade remains callable. Its compatibility surface is not imported by module-calculator.

### 4.4 Pure calculation composition

The existing decorator order remains:

```text
base
  → black cube when potential exists
  → additional cube when additional potential exists
  → starforce when star information exists
```

The new kernel may implement this as direct composition rather than Spring components, but golden results and cost breakdown must remain identical. Red cube behavior remains available where existing callers use it; calculator's full path continues using its current black/additional/starforce composition.

`CubeCostPolicy` becomes a pure policy/value lookup consumed by the kernel. Environment-backed `PolicyPort` adaptation stays outside core and materializes the policy before calculation.

`NoljangProbabilityCalculator` in module-core is canonical. The module-infra `NoljangProbabilityTable` becomes a compatibility delegate with no independent probability constants/table. calculator input conversion imports only the core canonical API.

### 4.5 Cache boundary

module-calculator owns a narrow `ValuationCache` adapter around its existing backend.

Cache key includes:

- every normalized calculation input field that changes output
- probability table version/checksum
- logic version

OCID and preset are excluded unless they affect formula output. Option list normalization is performed once by the input mapper and both kernel/cache use the canonical representation.

Behavior:

- hit returns the same `ComponentCosts` as direct kernel calculation
- miss computes once and stores
- cache read/write/serialization failure increments a metric and falls back to direct computation
- kernel failure is never converted into a cache miss or empty/default result
- cache failure does not change result metadata/version

### 4.6 Error taxonomy

| Error | Boundary behavior |
| --- | --- |
| malformed source item | deterministic item-level `ERROR` result under existing schema |
| unsupported part/grade/star range | explicit domain input error; mapper decides item-level error |
| missing probability for supported input | kernel invariant failure; abort chunk and return Kafka `Retryable` |
| arithmetic/non-finite result | kernel invariant failure; abort chunk |
| CSV missing/empty/invalid | boot failure |
| cache failure | direct compute + metric |
| serialization/storage failure | calculation result is not altered; pipeline delivery handles retry |

`SnapshotChunkProcessor` may not catch every throwable and emit an empty `ComponentCosts`. Only classified source-data errors become per-item `ERROR`. Engine invariant and infrastructure errors propagate so the Kafka delivery contract can retry/DLT the chunk.

### 4.7 Spring wiring and compatibility

`module-calculator/config/ValuationEngineConfiguration` constructs snapshot, kernel, cache adapter, and processor dependencies directly. `CalculatorEngineConfiguration` imports only local calculator configuration and no module-infra class.

`module-infra/CalculatorEngineAutoConfiguration` keeps its FQN and bean compatibility for app/web. It delegates calculation to module-core and retains only app-facing adapter/wiring. This facade is marked as legacy in architecture documentation, not with a removal date.

No duplicate Spring bean definition is active in the same application context. Bean names required by existing app tests remain stable.

## 5. Migration

1. Build representative golden-master fixtures from the current `EquipmentExpectationCalculatorFactory`.
2. Add boundary/property tests for cube, additional, starforce, Noljang, absent components, and invalid inputs.
3. Introduce immutable core table types and adapt current repository data into them without changing calculations.
4. Move the canonical Noljang implementation and turn old table into a delegate.
5. Move factory/decorator pure logic into `ValuationKernel` in behavior-preserving slices.
6. Add calculator CSV loader and boot validation.
7. Wrap existing calculator cache behind `ValuationCache` and add table/logic version to key.
8. Switch calculator processor to core kernel and local Spring wiring.
9. Convert module-infra public calculation classes/configuration to app/web compatibility delegates.
10. Remove calculator imports of old V4/module-infra types and add dependency guard.

Move-only and behavior-change commits stay separate. Golden-master comparison runs after each calculation slice.

## 6. Tests

- golden master: old factory vs new kernel for a representative fixture matrix
- every combination of potential/additional/starforce presence
- black/red/additional cube types and grade/level/part boundaries
- regular and Noljang star boundaries, including max Noljang star
- option order/normalization and secondary weapon category
- empty/missing/invalid CSV boot failure
- probability mass, duplicate key, missing-key invariant
- direct calculation vs cache hit equivalence
- cache get/put failure fallback with metrics
- domain input error vs kernel invariant propagation
- pure core architecture test forbidding Spring, Jackson, infra, calculator imports
- calculator application context smoke test with only local engine wiring
- app compatibility tests through legacy infra facade
- throughput/allocation benchmark using the same fixture, JVM options, warmup, and sample size before/after

Floating-point comparison uses existing production rounding and a fixture-specific tolerance only where the current result is non-integral. The migration may not widen tolerance to hide drift.

## 7. Observability

- calculation count/duration/error by component and normalized error class
- probability table load duration/row count/version checksum
- cache hit/miss/failure/fallback
- golden drift count during shadow characterization
- allocation and items/sec before/after

item name, OCID, option text, exception message, full table version checksum are not metric tags. A short bounded logic/table version label is allowed.

## 8. Acceptance Criteria

- module-core calculation package has no Spring, Jackson/CSV, LogicExecutor, cache, filesystem, or module-infra dependency.
- module-calculator imports neither `maple.expectation.application.service.calculator.v4` nor infra Noljang/config/repository types.
- `CubeProbabilityRepositoryImpl` is no longer calculator's generic persistence dependency.
- one canonical Noljang probability implementation exists.
- old and new engines match the approved golden fixture matrix with zero unexplained drift.
- missing/empty table fails boot; classified internal errors are not collapsed into default costs.
- cache failure returns the direct kernel result and records a metric.
- table/logic version participates in cache identity and internal result metadata.
- calculator's direct module-infra Gradle dependency is removed.
- app/web compatibility tests pass through module-infra facade.
- throughput/allocation show no unapproved regression and evidence is retained.

## 9. ADR Alignment

- ADR-050 and ADR-352 authorize pure calculation movement into core.
- ADR-350 and ADR-351 defer wholesale cube migration; this design moves only the pure subset and preserves compatibility adapters.
- ADR-353 dependency direction is enforced.
- ADR-722 package ownership follows module responsibility.
