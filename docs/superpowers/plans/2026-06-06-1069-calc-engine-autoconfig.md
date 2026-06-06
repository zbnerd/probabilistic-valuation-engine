# #1069 CalculatorEngineAutoConfiguration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce `module-calculator`'s `CalculatorEngineConfiguration` from 17 `@Import` entries to 2, by adding a new `CalculatorEngineAutoConfiguration` facade in `module-infra`.

**Architecture:** 1 new file in `module-infra/.../infrastructure/config/` owns the 17-class import list. `module-calculator` retains a thin 2-import `CalculatorEngineConfiguration` for backward compatibility with `CalculatorApplication.kt`.

**Tech Stack:** Spring Boot `@Configuration` + `@Import`, Kotlin

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculatorEngineAutoConfiguration.kt` | CREATE | Owns 17-class @Import block |
| `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt` | MODIFY | Reduce @Import to 2 entries |

No test changes required — bean wiring test is implicit via `CalculatorApplication` boot.

---

### Task 1: Create CalculatorEngineAutoConfiguration.kt

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculatorEngineAutoConfiguration.kt`

- [ ] **Step 1: Write the file**

```kotlin
package maple.expectation.infrastructure.config

import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.application.service.cube.CubeServiceImpl
import maple.expectation.application.service.cube.component.CubeComputeBuffer
import maple.expectation.application.service.cube.component.CubeDpCalculator
import maple.expectation.application.service.cube.component.CubeSlotCountResolver
import maple.expectation.application.service.cube.component.DpModeInferrer
import maple.expectation.application.service.cube.component.SlotDistributionBuilder
import maple.expectation.application.service.cube.component.StatValueExtractor
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.application.service.starforce.StarforceLookupAdapter
import maple.expectation.config.CubeEngineFeatureFlag
import maple.expectation.config.TableMassConfig
import maple.expectation.infrastructure.adapter.policy.PolicyAdapter
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Calculator Engine Auto-Configuration — module-calculator 전용 17-class import facade.
 *
 * <p>module-calculator는 이 클래스 1개만 import하면 큐브 계산 엔진에 필요한
 * 모든 빈을 조립할 수 있다. 큐브 컴포넌트 추가/제거/이름 변경 시
 * module-calculator가 아닌 이 파일만 수정하면 된다.
 *
 * <p>포함 빈 (17):
 * <ul>
 *   <li>Application services: EquipmentExpectationCalculatorFactory, CubeServiceImpl,
 *       CubeComputeBuffer, CubeDpCalculator, CubeSlotCountResolver, DpModeInferrer,
 *       SlotDistributionBuilder, StatValueExtractor, CubeCostPolicy, StarforceLookupAdapter</li>
 *   <li>Config: CubeEngineFeatureFlag, TableMassConfig, CalculationPortConfig, CoreExecutorConfig</li>
 *   <li>Infra: PolicyAdapter, DefaultLogicExecutor, DefaultExceptionClassifier,
 *       CubeProbabilityRepositoryImpl</li>
 * </ul>
 *
 * @see maple.calculator.config.CalculatorEngineConfiguration — module-calculator의 2-import facade
 */
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

- [ ] **Step 2: Verify file compiles**

Run: `./gradlew :module-infra:compileKotlin --continue`
Expected: BUILD SUCCESSFUL. No errors.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CalculatorEngineAutoConfiguration.kt
git commit -m "refactor(infra): add CalculatorEngineAutoConfiguration facade (#1069)

Owns the 17-class @Import block previously duplicated in
module-calculator/CalculatorEngineConfiguration. module-calculator
will switch to importing this single class in a follow-up commit.

Refs: #1069, #1063"
```

---

### Task 2: Slim CalculatorEngineConfiguration.kt

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt`

- [ ] **Step 1: Replace file contents**

Old (17 imports in @Import block):

```kotlin
package maple.calculator.config

import maple.expectation.application.service.calculator.v4.EquipmentExpectationCalculatorFactory
import maple.expectation.application.service.cube.CubeServiceImpl
import maple.expectation.application.service.cube.component.CubeComputeBuffer
import maple.expectation.application.service.cube.component.CubeDpCalculator
import maple.expectation.application.service.cube.component.CubeSlotCountResolver
import maple.expectation.application.service.cube.component.DpModeInferrer
import maple.expectation.application.service.cube.component.SlotDistributionBuilder
import maple.expectation.application.service.cube.component.StatValueExtractor
import maple.expectation.application.service.cube.policy.CubeCostPolicy
import maple.expectation.application.service.starforce.StarforceLookupAdapter
import maple.expectation.config.CubeEngineFeatureFlag
import maple.expectation.config.TableMassConfig
import maple.expectation.infrastructure.adapter.policy.PolicyAdapter
import maple.expectation.infrastructure.config.CalculationPortConfig
import maple.expectation.infrastructure.config.CoreExecutorConfig
import maple.expectation.infrastructure.executor.DefaultLogicExecutor
import maple.expectation.infrastructure.executor.classifier.DefaultExceptionClassifier
import maple.expectation.infrastructure.persistence.repository.CubeProbabilityRepositoryImpl
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(
    EquipmentExpectationCalculatorFactory::class,
    CubeServiceImpl::class,
    CubeDpCalculator::class,
    CubeComputeBuffer::class,
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
class CalculatorEngineConfiguration
```

New (2 imports):

```kotlin
package maple.calculator.config

import maple.expectation.infrastructure.config.CalculatorEngineAutoConfiguration
import maple.expectation.infrastructure.config.CoreExecutorConfig
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Calculator Engine Configuration — module-calculator의 2-import facade.
 *
 * <p>Cube engine의 17개 빈은 {@link CalculatorEngineAutoConfiguration}이 소유.
 * 이 클래스는 {@link CoreExecutorConfig}를 함께 import하여 lightweight executor
 * 빈이 calculator에 노출되도록 한다.
 *
 * @see maple.expectation.infrastructure.config.CalculatorEngineAutoConfiguration
 */
@Configuration
@Import(
    CalculatorEngineAutoConfiguration::class,
    CoreExecutorConfig::class,
)
class CalculatorEngineConfiguration
```

- [ ] **Step 2: Verify file compiles**

Run: `./gradlew :module-calculator:compileKotlin --continue`
Expected: BUILD SUCCESSFUL. No errors.

- [ ] **Step 3: Run calculator tests**

Run: `./gradlew :module-calculator:test --continue`
Expected: BUILD SUCCESSFUL. Pre-existing failures (if any) are out of scope.

- [ ] **Step 4: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt
git commit -m "refactor(calculator): reduce CalculatorEngineConfiguration to 2 imports (#1069)

Switches from listing 17 concrete classes to importing the
CalculatorEngineAutoConfiguration facade. Cube component additions
or renames are now localized to module-infra.

Refs: #1069"
```

---

### Task 3: Final verification

**Files:** none

- [ ] **Step 1: Full compile check**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full test suite**

Run: `./gradlew test --continue`
Expected: BUILD SUCCESSFUL. Pre-existing failures (if any) out of scope.

- [ ] **Step 3: Boot calculator + smoke test**

```bash
set -a && source .env && set +a
./gradlew :module-calculator:bootRun &
BOOT_PID=$!
sleep 60  # wait for spring boot
curl -s -w "\nHTTP %{http_code}" "http://localhost:8082/actuator/health" | head -5
```

Expected: HTTP 200 (or whatever calculator's health endpoint returns)

- [ ] **Step 4: Stop bootRun**

```bash
kill $BOOT_PID
```

- [ ] **Step 5: Commit (if any edits during verification)**

If no edits: skip. If edits: amend or add follow-up commit.

---

### Task 4: Push + PR

- [ ] **Step 1: Push branch**

```bash
git push -u origin refactor/1069-calc-engine-autoconfig
```

- [ ] **Step 2: Open PR with develop base**

```bash
gh pr create --base develop --head refactor/1069-calc-engine-autoconfig \
  --title "refactor(1069): extract CalculatorEngineAutoConfiguration facade" \
  --body "## Summary
- Add CalculatorEngineAutoConfiguration in module-infra/.../config/ (17 @Import entries)
- Reduce CalculatorEngineConfiguration in module-calculator to 2 @Import entries
- Cube component additions/renames now localized to module-infra

## Refs
- Closes #1069
- Builds on #1063 (CoreExecutorConfig split)

## Verification
- [x] ./gradlew compileKotlin compileJava --continue
- [x] ./gradlew test --continue
- [x] module-calculator bootRun + health endpoint 200"
```

- [ ] **Step 3: Close #1069 with PR link**

```bash
gh issue close 1069 --comment "Closed by PR <PR_NUMBER>. CalculatorEngineConfiguration reduced from 17 to 2 @Import entries; new CalculatorEngineAutoConfiguration facade in module-infra owns the bean list."
```

---

## Self-Review

**1. Spec coverage:** §1 Problem → Tasks 1-2 (new file + slim). §2 Decision → exact code shown. §3 Trade-offs → import count reduction, CoreExecutorConfig double-import, no signature change. §4 Metrics → captured in commit messages. ✅

**2. Placeholder scan:** No "TBD", "implement later", "add appropriate error handling" — all code shown in full. ✅

**3. Type consistency:** `CalculatorEngineAutoConfiguration::class` referenced in both Task 1 (definition) and Task 2 (consumer). `CoreExecutorConfig::class` referenced in both Task 1 and Task 2 (slightly redundant but explicit). ✅
