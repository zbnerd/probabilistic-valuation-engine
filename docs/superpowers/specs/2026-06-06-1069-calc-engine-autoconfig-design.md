# CalculatorEngineAutoConfiguration Extraction Spec (#1069)

- Date: 2026-06-06
- Owner: TBD
- Related: #1069, #1063 (merged — CoreExecutorConfig split)
- Parent: ADR-050 (module-infra decomposition roadmap)

---

## 1. Background / Problem

### Background

`module-calculator/.../CalculatorEngineConfiguration.kt` currently lists 17 classes in a single `@Import` block:

```
EquipmentExpectationCalculatorFactory, CubeServiceImpl, CubeComputeBuffer,
CubeDpCalculator, CubeSlotCountResolver, DpModeInferrer, SlotDistributionBuilder,
StatValueExtractor, CubeCostPolicy, StarforceLookupAdapter,
PolicyAdapter, DefaultLogicExecutor, DefaultExceptionClassifier,
CubeProbabilityRepositoryImpl,
CubeEngineFeatureFlag, TableMassConfig, CalculationPortConfig, CoreExecutorConfig
```

Adding/removing/renaming any cube component requires editing `module-calculator` — coupling that violates the principle that consumers should not know implementation details.

`#1063` split `ExecutorConfig` into `CoreExecutorConfig` (lightweight) + `InfraExecutorConfig` (heavy). Three downstream modules (`calculator`, `external-api`, `synchronizer`) adopted `CoreExecutorConfig` directly via PRs `#1150`-`#1152`. `CalculatorEngineConfiguration` is the next-level facade: 17 classes → 1 class.

### Problem

**All 17 classes live in `module-infra`** (Java under `maple.expectation.application.service.*` legacy path, Kotlin under `maple.expectation.infrastructure.*`). The issue body states "12 module-core classes" but module-core's package structure has no `maple.expectation.application.*` at all — these classes were moved to module-infra during the module-app decomposition (commit `ac704b1d6` #1148, see also `517059d78` "Complete Infrastructure Move").

**Module-core constraint** (`.claude/rules/module-boundaries.md`): "Spring annotation, JPA, infra 구현체 금지" — `@Configuration` cannot live in module-core. So the auto-configuration must live in **module-infra**, not module-core.

The 17 classes are application services (not "infra" by hexagonal naming), but the legacy module-app migration already placed them in `module-infra/.../application/service/`. ADR-050's per-sub-module extraction is the long-term move; this PR scopes only the facade.

### Goal

Reduce `CalculatorEngineConfiguration` from 17 `@Import` entries to 2. Add a single new `CalculatorEngineAutoConfiguration` in `module-infra` that owns the 17-class import list. `module-calculator` knows only 1 calc engine class.

---

## 2. Decision

> Create `module-infra/.../config/CalculatorEngineAutoConfiguration.kt` with `@Import` of the 17 classes. `module-calculator/.../CalculatorEngineConfiguration.kt` becomes a 2-import facade (`CalculatorEngineAutoConfiguration` + `CoreExecutorConfig`).

```text
// New file: module-infra/.../infrastructure/config/CalculatorEngineAutoConfiguration.kt
@Configuration
@Import(
    EquipmentExpectationCalculatorFactory::class,
    CubeServiceImpl::class,
    CubeComputeBuffer::class,
    CubeDpCalculator::class,
    CubeSlotCountResolver::class,
    DpModeInferrer::class,
    SlotDistributionBuilder::class,
    StatValueExtractor::class,
    CubeCostPolicy::class,
    StarforceLookupAdapter::class,
    PolicyAdapter::class,
    DefaultLogicExecutor::class,
    DefaultExceptionClassifier::class,
    CubeProbabilityRepositoryImpl::class,
    CubeEngineFeatureFlag::class,
    TableMassConfig::class,
    CalculationPortConfig::class,
    CoreExecutorConfig::class,
)
class CalculatorEngineAutoConfiguration
```

```text
// Slimmed: module-calculator/.../config/CalculatorEngineConfiguration.kt
@Configuration
@Import(
    CalculatorEngineAutoConfiguration::class,
    CoreExecutorConfig::class,
)
class CalculatorEngineConfiguration
```

### Why module-infra (not module-core)

1. **module-core forbids Spring annotations** (`module-boundaries.md`) — `@Configuration` is illegal
2. **All 17 classes are already in module-infra** — no new module dependency required
3. **ADR-050 decomposition is per-sub-module, not per-facade** — the auto-config is a thin wrapper that adds zero logic, so it lives with its dependencies
4. **`#1063` precedent**: `CoreExecutorConfig` is in `module-infra/.../config/`, not module-core. The "shared bean config" pattern in this codebase is module-infra-resident

### Future path (out of scope)

ADR-050's `module-executor` extraction (PR #1169, follow-up #1161) will move `DefaultLogicExecutor` + `DefaultExceptionClassifier` out. The auto-config's `@Import` will then reference `maple.expectation.executor.*` FQN, but the file structure remains identical.

---

## 3. Trade-offs

### Sensitivity

* `CalculatorApplication` bean wiring — wrong import = missing bean at runtime (silent failure until first chunk)
* Spring `@Configuration` class scanning — auto-config must be in component scan path
* `CoreExecutorConfig` already in `module-infra/.../config/` — auto-config and core config are co-located (no split-brain risk)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| module-infra 위치 (not module-core) | 모듈 의존성 변경 없음, 빌드 그래프 무증가 | issue #1069의 "module-core application layer" framing 불일치 |
| 17→1 import 축소 | module-calculator의 추상화 경계 개선, 추가/제거 시 edit 1곳 | 새 파일 1개 + 자동 import 18 lines |
| `CoreExecutorConfig` import 유지 (auto-config에도 포함) | 명시적, 외부 module도 단일 import로 완결 | minor redundancy (auto-config + CalculatorEngineConfiguration 둘 다 import) |
| `CalculatorEngineConfiguration` 클래스 유지 | 기존 bean wiring 호환, CalculatorApplication.kt 변경 없음 | 클래스 빈껍데기 (어차피 0 logic) |

### Risk

* **Import 누락**: 17→18 line 단순 이동, IDE refactor + `--continue` 빌드로 검출
* **Bean ordering**: `@Import` 순서 무관 (Spring이 의존성 자동 해석), `CoreExecutorConfig` 양쪽 import = idempotent (Spring dedup)
* **Auto-config 이름 충돌**: `CalculatorEngineAutoConfiguration`은 새 FQN — 충돌 없음 확인 (`grep` 검증)

### Non-Risk

* **시그니처 변경 0**: 이동뿐, 로직 변경 없음
* **테스트 회귀 0**: `CalculatorApplication` boot, chunk 처리 동일
* **빌드 그래프 변화 0**: 새 모듈 의존성 없음
* **다른 다운스트림 영향 0**: `module-external-api`, `module-synchronizer`는 변경 없음 (그들이 사용할지는 후속 PR)

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After PR | Notes |
| ------ | ----: | ----: | ----- |
| `CalculatorEngineConfiguration` `@Import` entries | 17 | 2 | 88% reduction |
| `module-calculator/.../config/` files | 1 | 1 | no growth |
| `module-infra/.../config/` files | N+1 | N+2 | +1 new file |
| Bean wiring surface visible to module-calculator | 17 FQN | 1 FQN | abstraction boundary |
| compile/test runtime | baseline | unchanged | no signature change |

### Observed Result (expected)

* `./gradlew :module-calculator:bootRun` 정상 기동
* `./gradlew :module-calculator:test` 통과
* Chunk 처리 시 `Calculation completed with result saved` 로그 확인 (workflow-rules.md §10)

---

## 5. Summary

> Move the 17-class `@Import` block from `module-calculator` to a new `CalculatorEngineAutoConfiguration` in `module-infra`, reducing `module-calculator`'s knowledge of cube engine internals from 17 to 1 class.
