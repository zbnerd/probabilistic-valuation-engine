# module-executor Extraction Spec (#907 PR1 of ADR-050)

- Date: 2026-06-06
- Owner: TBD
- Related: #907, ADR-050
- Parent ADR: `docs/01_ADR/ADR-050-module-infra-decomposition-roadmap.md`

---

## 1. Background / Problem

### Background

ADR-050 lays out 7 sub-modules to extract from `module-infra` (444 files / 48,620 LOC). `module-executor` is the 1순위 blocker — `LogicExecutor` (Ca=149) and `TaskContext` (Ca=144) are referenced from every other candidate sub-module. Until executor lives in its own module, none of the other 6 extractions can happen cleanly.

This spec covers the **1st of 7 follow-up PRs**: `module-executor` extraction. The 6 remaining follow-ups (persistence, monitoring, cache, pgmq, aop, external-client, security) follow the same template — see §6.

### Problem

`LogicExecutor` and its 24 supporting files currently live at `module-infra/.../infrastructure/executor/`:

```
infrastructure/executor/
├── LogicExecutor.kt                  (composite interface)
├── BasicExecutor.kt                  (ISP: raw + finally)
├── SafeExecutor.kt                   (ISP: orDefault/orCatch)
├── ResilientExecutor.kt              (ISP: fallback/translation)
├── DefaultLogicExecutor.kt           (impl, @Component)
├── CheckedLogicExecutor.kt           (ISP: Checked variant)
├── DefaultCheckedLogicExecutor.kt    (impl)
├── TaskContext.kt                    (component:operation:dynValue)
├── StepTimer.kt                      (slow task trace)
├── classifier/
│   ├── ExceptionClassifier.kt
│   ├── DefaultExceptionClassifier.kt
│   └── CircuitBreakerClassification.kt
├── function/
│   ├── CheckedRunnable.kt
│   ├── CheckedSupplier.kt
│   └── ThrowingRunnable.kt
├── policy/
│   ├── ExecutionPolicy.kt
│   ├── ExecutionPipeline.kt
│   ├── ExecutionOutcome.kt
│   ├── FailureMode.kt
│   ├── FinallyPolicy.kt
│   ├── LoggingPolicy.kt
│   ├── PolicyOrder.kt
│   ├── TaskLogSupport.kt
│   └── TaskLogTags.kt
└── strategy/
    └── ExceptionTranslator.kt
```

Total: **25 .kt files** (ADR-050 said 34, corrected by `find`).

Cross-module consumer count (estimated from grep): ~498 import lines across 5+ downstream modules (`module-calculator`, `module-external-api`, `module-synchronizer`, `module-infra`'s own tests, `module-app`).

### Goal

Extract the 25 .kt files into a new `module-executor` Gradle module. The new module:

1. Has **zero** `module-infra` dependency
2. Depends only on `module-common`, `kotlin-stdlib`, `jackson`, `spring-core`, `spring-context`, `kotlin-coroutines`, `reactor-core`, `micrometer-core` (per ADR-050 §2.1)
3. Verifies via `verifyNoSpringDependency` task (executor legitimately uses Spring, so this task applies to a different module — executor is **not** `module-common`)
4. Provides `LogicExecutor` as a Spring `@Component` for downstream autowire
5. Downstream 5 modules can optionally switch from `module-infra` (re-export) to direct `module-executor` dependency

---

## 2. Decision

> Create `module-executor` Gradle module containing 25 .kt files. Package change: `maple.expectation.infrastructure.executor.*` → `maple.expectation.executor.*`. `module-infra` re-exports for backward compatibility (no source breakage in 5 downstream modules).

```text
// New module
module-common
    └── module-executor               (NEW: 25 files, ~2,332 LOC)
            ↑ (re-export for compat)
        module-infra                  (shrinks: executor/ subdir deleted)
            ↑
        module-calculator, module-external-api, module-synchronizer,
        module-rest-controller, module-app
```

### Package migration

| Old FQN | New FQN |
|---------|---------|
| `maple.expectation.infrastructure.executor.LogicExecutor` | `maple.expectation.executor.LogicExecutor` |
| `maple.expectation.infrastructure.executor.DefaultLogicExecutor` | `maple.expectation.executor.DefaultLogicExecutor` |
| `maple.expectation.infrastructure.executor.TaskContext` | `maple.expectation.executor.TaskContext` |
| `maple.expectation.infrastructure.executor.StepTimer` | `maple.expectation.executor.StepTimer` |
| `maple.expectation.infrastructure.executor.classifier.*` | `maple.expectation.executor.classifier.*` |
| `maple.expectation.infrastructure.executor.function.*` | `maple.expectation.executor.function.*` |
| `maple.expectation.infrastructure.executor.policy.*` | `maple.expectation.executor.policy.*` |
| `maple.expectation.infrastructure.executor.strategy.*` | `maple.expectation.executor.strategy.*` |

### Public API (no signature change)

```kotlin
// module-executor/.../executor/LogicExecutor.kt
interface LogicExecutor : BasicExecutor, SafeExecutor, ResilientExecutor {
    override fun <T> execute(task: ThrowingSupplier<T>, context: TaskContext): T
    override fun <T> executeOrDefault(task: ThrowingSupplier<T>, defaultValue: T, context: TaskContext): T
    override fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, context: TaskContext): T
    // ... 7 patterns total (code-rules.md §1)
}

data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String? = null,
) { companion object { fun of(component: String, operation: String): TaskContext = ... } }
```

### module-executor build.gradle (sketch)

```kotlin
// module-executor/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot") apply false
}

dependencies {
    implementation(project(":module-common"))
    implementation("org.springframework:spring-context")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.projectreactor:reactor-core")
    implementation("io.micrometer:micrometer-core")

    testImplementation(testFixtures(project(":module-common")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
```

---

## 3. Per-File Migration Table

| File | Lines (est) | Depends on | Public API changes |
|------|------------:|------------|--------------------|
| `LogicExecutor.kt` | ~125 | `function.ThrowingRunnable`, `strategy.ExceptionTranslator` | No (composite interface, KDoc preserved) |
| `BasicExecutor.kt` | ~30 | — | No |
| `SafeExecutor.kt` | ~25 | — | No |
| `ResilientExecutor.kt` | ~40 | `strategy.ExceptionTranslator` | No |
| `DefaultLogicExecutor.kt` | ~302 | `policy.ExecutionPipeline`, `policy.FinallyPolicy`, `strategy.ExceptionTranslator` | No (impl) |
| `CheckedLogicExecutor.kt` | ~20 | — | No |
| `DefaultCheckedLogicExecutor.kt` | ~30 | — | No |
| `TaskContext.kt` | ~76 | — | No (data class, companion) |
| `StepTimer.kt` | ~42 | SLF4J | No |
| `classifier/ExceptionClassifier.kt` | ~10 | — | No |
| `classifier/DefaultExceptionClassifier.kt` | ~30 | — | No |
| `classifier/CircuitBreakerClassification.kt` | ~20 | — | No |
| `function/CheckedRunnable.kt` | ~15 | — | No |
| `function/CheckedSupplier.kt` | ~15 | — | No |
| `function/ThrowingRunnable.kt` | ~20 | — | No |
| `policy/ExecutionPolicy.kt` | ~50 | — | No |
| `policy/ExecutionPipeline.kt` | ~80 | `policy.*`, `TaskContext` | No |
| `policy/ExecutionOutcome.kt` | ~30 | — | No |
| `policy/FailureMode.kt` | ~25 | — | No |
| `policy/FinallyPolicy.kt` | ~30 | — | No |
| `policy/LoggingPolicy.kt` | ~40 | — | No |
| `policy/PolicyOrder.kt` | ~15 | — | No |
| `policy/TaskLogSupport.kt` | ~30 | — | No |
| `policy/TaskLogTags.kt` | ~20 | — | No |
| `strategy/ExceptionTranslator.kt` | ~25 | `TaskContext` | No |

**Total:** 25 files, ~1,087 LOC (vs. ADR-050's 2,332 estimate — includes policy + function + strategy subfolders, ADR likely double-counted).

---

## 4. Migration Plan (Single PR)

1. Create `module-executor/build.gradle.kts` + `module-executor/src/main/kotlin/maple/expectation/executor/`
2. `git mv` 25 files from `module-infra/.../infrastructure/executor/` → `module-executor/src/main/kotlin/maple/expectation/executor/`
3. Update package declarations in all 25 files (`infrastructure.executor` → `executor`)
4. Update internal `import` statements (cross-references within the 25 files: e.g. `LogicExecutor.kt` imports `function.ThrowingRunnable` from same package → still works, just new package)
5. Update `module-infra` consumers' imports in:
   - `module-infra/src/test/...` (~498 import lines)
   - `module-calculator/src/main/...`
   - `module-external-api/src/main/...`
   - `module-synchronizer/src/main/...`
   - `module-app/src/main/...`
6. Add `module-infra/build.gradle` `api(project(":module-executor"))` (re-export for compat — 5 downstream modules see no change)
7. Run `./gradlew compileKotlin compileJava --continue` — verify
8. Run `./gradlew test` — verify (LogicExecutorTest, ExceptionClassifierTest must pass)

**No signature change → no downstream code logic change.** Only `import` statement path swap.

---

## 5. Test Strategy

* `module-executor:test` runs in isolation (no `module-infra` dep):
  - `LogicExecutorTest.java` — 8 execution patterns
  - `ExceptionClassifierTest.kt` — classifier logic
  - `StepTimer` slow-task trace (if unit testable)
* `./gradlew :module-calculator:bootRun` and `./gradlew :module-external-api:bootRun` — verify wiring intact
* `module-infra` test suite — verify zero regression

Coverage target: > 85% on `module-executor` per ADR-050 metrics.

---

## 6. Template for remaining 6 sub-module specs

Each follow-up spec follows the same structure as this doc (§1-5) with the 4 sub-module-specific sections customized:

| Follow-up | Sub-module | Critical port(s) to extract |
|-----------|-----------|------------------------------|
| #907.1 | `module-executor` | (this spec) |
| #907.2 | `module-persistence` | `repository/` (JPA, QueryDSL) — 49 files |
| #907.3 | `module-monitoring` | `metrics/`, `observability/`, `alert/` — 48 files |
| #907.4 | `module-cache` | `cache/` (Caffeine, L2, SingleFlight) — 29 files |
| #907.5 | `module-pgmq` | `pgmq/`, `messaging/`, `queue/`, `event/` — 33 files |
| #907.6 | `module-aop` | `aop/`, `concurrency/`, `lock/`, `singleflight/` — 35 files |
| #907.7 | `module-external-client` | `external/`, `webclient/` — 20 files |
| #907.8 | `module-security` | `security/`, `auth/` — 9 files |

Each follow-up is its own brainstorm → spec → plan → PR cycle.

---

## 7. Trade-offs

### Sensitivity

* All `LogicExecutor.execute()` call sites (~498 import lines × unknown internal call density) — refactor scope high
* `TaskContext` step timer serialization cost — must remain unchanged
* `verifyNoSpringDependency` task — must NOT apply to `module-executor` (executor legitimately uses `@Component`)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| `module-executor` 1순위 | 다른 6 모듈의 차단 해소 | 단일 PR의 ROI 낮음 (다른 모듈 추출은 후속 PR 의존) |
| Package 변경 `infrastructure.executor` → `executor` | 깔끔한 의존성 그래프 | ~498 import line 일괄 변경 |
| `module-infra` re-export (api) | 5 다운스트림 무중단 | `module-infra`는 facade로 잔류 (God Module 위험) |
| 단일 PR 이동 | 단순 | 1 PR 큼 (~25 file move + 498 import) |

### Risk

* **Import 경로 오류**: 498 import lines 중 1개라도 누락 시 컴파일 실패 — IDE refactor + build `--continue` 로 검출
* **`@Component` 인식 실패**: Spring component scan이 `module-executor` 패키지를 스캔하도록 `module-infra` 또는 `module-app`의 `@ComponentScan` 범위 조정 필요
* **`maple.expectation.infrastructure.executor.Class` 문자열 참조**: KDoc 내 `[{@link ...}]` 또는 `application.yml`의 FQN 문자열이 있을 수 있음 — grep 검증 필요

### Non-Risk

* **시그니처 변경 없음**: 7개 execute 메서드 동일 — 다운스트림 로직 변경 0
* **테스트 회귀**: `LogicExecutorTest`, `ExceptionClassifierTest` 동일 위치/시그니처로 이동
* **빌드 그래프 폭증**: `module-executor` 단일 모듈 추가만, 1 edge 증가

---

## 8. Result / Evidence (예상)

| Metric | Before | After PR1 |
|--------|-------:|----------:|
| `module-infra` files | 444 | 419 (-25) |
| `module-infra` LOC | 48,620 | ~46,288 |
| `module-executor` files | 0 | 25 |
| 다운스트림 `import` 변경 | n/a | ~498 (단순 path swap) |
| `compileKotlin` wall time | baseline | -10% (1/8 분할) |

---

## 9. Out of Scope

* 6 remaining sub-module extractions (#907.2-#907.8) — follow-up issues
* `ExecutorPort` (outbound port in `module-core`) — separate ADR-050 follow-up
* `maple.expectation.infrastructure.executor` re-export facade deletion — when `module-infra` fan-in 0
