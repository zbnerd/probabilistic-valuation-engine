# Issue #1126: Executor Bean Rename + expectationComputeExecutor IO/CPU Split

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** expectationComputeExecutor를 IO/CPU executor로 분리하고, Core/RestController의 taskExecutor bean 이름 충돌을 `defaultAsyncExecutor` / `restApiControllerExecutor`로 명시화한다. ADR-723 §23.3과 양립하는 legacy 한정 refactor.

**Architecture:**
- 4 bean rename/split: `defaultAsyncExecutor` (Core, replace taskExecutor), `restApiControllerExecutor` (RestController NEW), `expectationComputeIoExecutor` (Infra, replace expectationComputeExecutor), `expectationComputeCpuExecutor` (Infra NEW)
- 1 신규 파일 (RestControllerExecutorConfig.kt)
- 5 injection site hard rename (4 file)
- 1 ExecutorProperties wrapper 클래스 추가
- 1 yaml 키 구조 변경 (`executor.expectation.*` → `executor.expectation.compute-io/cpu.*`)
- 1 기존 test (ExecutorConfigTest.java) 의 4 test method 의 bean method reference 갱신

**Tech Stack:** Kotlin, Spring Boot 3.5, ThreadPoolTaskExecutor, @ConfigurationProperties, JUnit 5

---

## File Structure

| 파일 | 작업 | 책임 |
|---|---|---|
| `module-infra/.../config/ExecutorProperties.kt` | Modify | `ExpectationConfig` wrapper 추가 (computeIo + computeCpu sub-pool) |
| `module-infra/.../config/CoreExecutorConfig.kt` | Modify | `taskExecutor` → `defaultAsyncExecutor` |
| `module-infra/.../config/InfraExecutorConfig.kt` | Modify | `expectationComputeExecutor` → `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` |
| `module-infra/.../config/RestControllerExecutorConfig.kt` | **Create** | `restApiControllerExecutor` |
| `module-infra/.../config/ExecutorConfig.kt` | Modify | `@Import(RestControllerExecutorConfig)` 추가 |
| `module-infra/.../messaging/PgmqEventPublisherAdapter.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../messaging/KafkaEventPublisher.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../provider/EquipmentFetchProvider.kt` | Modify | `@Qualifier("defaultAsyncExecutor")` |
| `module-infra/.../cache/equipment/EquipmentDataResolver.kt` | Modify | `@Qualifier("expectationComputeIoExecutor")` |
| `module-app/src/test/.../config/ExecutorConfigTest.java` | Modify | 4 test method 의 bean method reference 갱신 |
| `module-app/src/main/resources/application.yml` | Modify | `executor.expectation.*` → `executor.expectation.compute-io/cpu.*` |
| `module-calculator/src/main/resources/application.yml` | Modify | 동일 |
| `module-external-api/src/main/resources/application.yml` | Modify | 동일 |
| `module-synchronizer/src/main/resources/application.yml` | Modify | 동일 |
| `module-rest-controller/src/main/resources/application.yml` | Modify | 동일 |

**각 모듈의 `application.yml`은 동일한 `executor.expectation.compute-io` + `executor.expectation.compute-cpu` 키 추가. 기존 `executor.expectation.*` flat key 제거.**

---

## Task 1: Setup worktree + branch

**Files:** None (git only)

- [ ] **Step 1: develop 최신 sync**

Run:
```bash
git checkout develop && git pull origin develop
```

Expected: Already on develop at `a6a07c661` (post-#1125 merge) or newer. Pull 무동작.

- [ ] **Step 2: feature branch 생성**

Run:
```bash
git checkout -b feature/1126-executor-rename-split
```

Expected: `Switched to a new branch 'feature/1126-executor-rename-split'`.

- [ ] **Step 3: working tree 깨진 파일 복원**

이전 세션 잔재로 `InfraExecutorConfig.kt`, `CoreExecutorConfig.kt` 가 working tree 에서 missing. 복원:

Run:
```bash
git checkout HEAD -- module-infra/src/main/kotlin/maple/expectation/infrastructure/config/InfraExecutorConfig.kt \
                     module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CoreExecutorConfig.kt
```

Expected: No output (silent restore). `ls module-infra/.../config/InfraExecutorConfig.kt` → 파일 존재 확인.

- [ ] **Step 4: branch 확인**

Run:
```bash
git branch --show-current
```

Expected: `feature/1126-executor-rename-split`.

---

## Task 2: Modify ExecutorProperties.kt — ExpectationConfig wrapper

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorProperties.kt`

- [ ] **Step 1: `data class ExecutorProperties` 의 `expectation` 필드 타입 변경**

기존:
```kotlin
data class ExecutorProperties(
    @DefaultValue val equipment: PoolConfig = PoolConfig(),
    @DefaultValue val preset: PoolConfig = PoolConfig(),
    @DefaultValue val alert: PoolConfig = PoolConfig(),
    @DefaultValue val expectation: PoolConfig = PoolConfig(),
    @DefaultValue val async: PoolConfig = PoolConfig(),
    @DefaultValue val operational: PoolConfig = PoolConfig(),
    @DefaultValue val backfill: PoolConfig = PoolConfig(),
    @DefaultValue val item: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 200),
) {
```

변경 후:
```kotlin
data class ExecutorProperties(
    @DefaultValue val equipment: PoolConfig = PoolConfig(),
    @DefaultValue val preset: PoolConfig = PoolConfig(),
    @DefaultValue val alert: PoolConfig = PoolConfig(),
    @DefaultValue val expectation: ExpectationConfig = ExpectationConfig(),
    @DefaultValue val async: PoolConfig = PoolConfig(),
    @DefaultValue val operational: PoolConfig = PoolConfig(),
    @DefaultValue val backfill: PoolConfig = PoolConfig(),
    @DefaultValue val item: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 200),
) {
```

- [ ] **Step 2: `ExpectationConfig` data class 추가 (PoolConfig 정의 직후, validateAll() 직전)**

기존 `data class PoolConfig` 블록 다음, `validateAll()` 메서드 직전에 다음을 삽입:

```kotlin
    /**
     * Expectation compute executor 설정 wrapper (IO + CPU 분리)
     *
     * <p>기존 `expectation: PoolConfig` 를 두 개의 sub-pool 로 분리:
     * <ul>
     *   <li>computeIo: IO-bound 외부 호출/DB read/write (legacy sizing 유지)
     *   <li>computeCpu: CPU-bound JSON parse/serialization/계산
     * </ul>
     *
     * <p>YAML:
     * <pre>
     * executor:
     *   expectation:
     *     compute-io:
     *       core-pool-size: 4
     *       max-pool-size: 8
     *       queue-capacity: 200
     *     compute-cpu:
     *       core-pool-size: 4
     *       max-pool-size: 4
     *       queue-capacity: 1000
     * </pre>
     */
    data class ExpectationConfig(
        @DefaultValue val computeIo: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 200),
        @DefaultValue val computeCpu: PoolConfig = PoolConfig(corePoolSize = 4, maxPoolSize = 8, queueCapacity = 1000),
    )
```

- [ ] **Step 3: `validateAll()` 메서드 갱신**

기존:
```kotlin
    fun validateAll() {
        equipment.validateRatio("equipment")
        preset.validateRatio("preset")
        alert.validateRatio("alert")
        expectation.validateRatio("expectation")
        async.validateRatio("async")
        operational.validateRatio("operational")
        backfill.validateRatio("backfill")
        item.validateRatio("item")
    }
```

변경 후:
```kotlin
    fun validateAll() {
        equipment.validateRatio("equipment")
        preset.validateRatio("preset")
        alert.validateRatio("alert")
        expectation.computeIo.validateRatio("expectation.compute-io")
        expectation.computeCpu.validateRatio("expectation.compute-cpu")
        async.validateRatio("async")
        operational.validateRatio("operational")
        backfill.validateRatio("backfill")
        item.validateRatio("item")
    }
```

- [ ] **Step 4: import 추가**

파일 상단에 `import org.springframework.boot.context.properties.bind.DefaultValue` 가 이미 있는지 확인. 없으면 추가. (이미 line 6 에 존재 확인됨.)

- [ ] **Step 5: compile 검증**

Run:
```bash
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. (다른 곳에서 `executorProperties.expectation.corePoolSize` 호출하는 곳은 compile fail 예상. 이 Task 는 properties 데이터 구조만 변경. 호출 측은 Task 5 에서 갱신.)

- [ ] **Step 6: Commit**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorProperties.kt
git commit -m "$(cat <<'EOF'
refactor(properties): split expectation into compute-io/cpu (#1126)

Add ExpectationConfig wrapper with computeIo (IO-bound) and computeCpu
(CPU-bound) sub-pools. Sets up data structure for InfraExecutorConfig
split in subsequent commits.

Note: InfraExecutorConfig.kt still references old structure.
Expected compile failure handled in Task 5.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: `[feature/1126-executor-rename-split <hash>] refactor(properties): split expectation into compute-io/cpu (#1126)`

---

## Task 3: Modify CoreExecutorConfig.kt — `taskExecutor` → `defaultAsyncExecutor`

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CoreExecutorConfig.kt`

- [ ] **Step 1: 메서드 이름 + `@Bean` 이름 변경**

기존 (lines 96-118):
```kotlin
    @Bean(name = ["taskExecutor"])
    @ConditionalOnMissingBean(name = ["taskExecutor"])
    fun taskExecutor(contextPropagatingDecorator: TaskDecorator): java.util.concurrent.Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("async-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAsyncAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

        log.info("[CoreExecutorConfig] taskExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }
```

변경 후:
```kotlin
    @Bean(name = ["defaultAsyncExecutor"])
    @ConditionalOnMissingBean(name = ["defaultAsyncExecutor"])
    fun defaultAsyncExecutor(contextPropagatingDecorator: TaskDecorator): java.util.concurrent.Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("async-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory().createAsyncAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator().registerExecutorMetrics(executor, "async")

        log.info("[CoreExecutorConfig] defaultAsyncExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }
```

- [ ] **Step 2: javadoc 갱신**

기존 (line 34):
```kotlin
 *   <li>taskExecutor (기본 @Async용)
```

변경 후:
```kotlin
 *   <li>defaultAsyncExecutor (기본 @Async용)
```

- [ ] **Step 3: compile 검증 (이 시점에서는 fail 예상, Task 4/5 가 다른 호출 site 갱신할 때까지 대기)**

Run:
```bash
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20
```

Expected: BUILD FAILED with `error: unresolved reference: taskExecutor` in `PgmqEventPublisherAdapter.kt` / `KafkaEventPublisher.kt` / `EquipmentFetchProvider.kt`. (Task 7 에서 fix 예정. **현재는 이 에러가 정상.**)

- [ ] **Step 4: Commit**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CoreExecutorConfig.kt
git commit -m "$(cat <<'EOF'
refactor(core): rename taskExecutor to defaultAsyncExecutor (#1126)

CoreExecutorConfig: @Bean name + method renamed to defaultAsyncExecutor.
thread prefix 'async-' preserved. Injection sites will be updated in
Task 7. Expected compile failure in dependent files.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: `[feature/1126-executor-rename-split <hash>] refactor(core): rename taskExecutor to defaultAsyncExecutor (#1126)`

---

## Task 4: Create RestControllerExecutorConfig.kt

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RestControllerExecutorConfig.kt`

- [ ] **Step 1: 파일 생성**

다음 내용으로 파일 생성:

```kotlin
package maple.expectation.infrastructure.config

import java.util.concurrent.Executor
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * RestController 전용 Executor Configuration — module-rest-controller 한정
 *
 * <p>Issue #1126: CoreExecutorConfig.taskExecutor 와의 bean 이름 충돌 해결을 위해
 * 명시적 `restApiControllerExecutor` bean 이름 사용.
 *
 * <p>module-rest-controller 만 이 설정을 import 한다.
 * module-app 등 full-stack 모듈은 {@link ExecutorConfig} 통해 {@link CoreExecutorConfig} +
 * {@link InfraExecutorConfig} 만 import.
 *
 * <p>포함 Bean:
 * <ul>
 *   <li>restApiControllerExecutor (RestController dispatch용)
 * </ul>
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties::class)
class RestControllerExecutorConfig(
    private val executorProperties: ExecutorProperties,
) {

    private val log = LoggerFactory.getLogger(RestControllerExecutorConfig::class.java)

    @Bean(name = ["restApiControllerExecutor"])
    @ConditionalOnMissingBean(name = ["restApiControllerExecutor"])
    fun restApiControllerExecutor(
        contextPropagatingDecorator: TaskDecorator,
        rejectionPolicyFactory: RejectionPolicyFactory,
        executorMetricsConfigurator: ExecutorMetricsConfigurator,
    ): Executor {
        val config = executorProperties.async
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("rest-api-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createAsyncAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "rest.api")

        log.info(
            "[RestControllerExecutorConfig] restApiControllerExecutor initialized: core={}, max={}, queue={}",
            config.corePoolSize,
            config.maxPoolSize,
            config.queueCapacity,
        )
        return executor
    }
}
```

- [ ] **Step 2: compile 검증 (다른 호출 site 는 없으므로 fail 없음 예상)**

Run:
```bash
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20
```

Expected: BUILD FAILED (PgmqEventPublisherAdapter / KafkaEventPublisher 등 미갱신, Task 7 에서 fix). 또는 module-rest-controller 의 import 누락 에러.

- [ ] **Step 3: Commit**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RestControllerExecutorConfig.kt
git commit -m "$(cat <<'EOF'
feat(infra): add RestControllerExecutorConfig (#1126)

New file: RestControllerExecutorConfig with restApiControllerExecutor
(prefix: rest-api-). module-rest-controller imports this directly;
module-app continues using ExecutorConfig (Core + Infra only).

Resolves taskExecutor bean name collision (Issue #1126).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Modify InfraExecutorConfig.kt — `expectationComputeExecutor` split

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/InfraExecutorConfig.kt`

- [ ] **Step 1: `expectationComputeExecutor` 메서드 제거 + `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` 추가**

기존 (lines 98-120) 의 `expectationComputeExecutor` 메서드를 다음으로 교체:

```kotlin
    @Bean(name = ["expectationComputeIoExecutor"])
    @ConditionalOnMissingBean(name = ["expectationComputeIoExecutor"])
    fun expectationComputeIoExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.expectation.computeIo
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-io-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createExpectationAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "expectation.compute-io")

        log.info("[InfraExecutorConfig] expectationComputeIoExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }

    @Bean(name = ["expectationComputeCpuExecutor"])
    @ConditionalOnMissingBean(name = ["expectationComputeCpuExecutor"])
    fun expectationComputeCpuExecutor(contextPropagatingDecorator: TaskDecorator, rejectionPolicyFactory: RejectionPolicyFactory, executorMetricsConfigurator: ExecutorMetricsConfigurator): Executor {
        val config = executorProperties.expectation.computeCpu
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = config.corePoolSize
        executor.maxPoolSize = config.maxPoolSize
        executor.queueCapacity = config.queueCapacity
        executor.setThreadNamePrefix("expectation-cpu-")
        executor.setAllowCoreThreadTimeOut(true)
        executor.setKeepAliveSeconds(30)

        executor.setTaskDecorator(contextPropagatingDecorator)
        executor.setRejectedExecutionHandler(rejectionPolicyFactory.createExpectationAbortPolicy())
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)

        executor.initialize()
        executorMetricsConfigurator.registerExecutorMetrics(executor, "expectation.compute-cpu")

        log.info("[InfraExecutorConfig] expectationComputeCpuExecutor initialized: core={}, max={}, queue={}", config.corePoolSize, config.maxPoolSize, config.queueCapacity)
        return executor
    }
```

- [ ] **Step 2: javadoc 갱신**

기존 (line 29):
```kotlin
 *   <li>expectationComputeExecutor, operationalExecutor, backfillExecutor
```

변경 후:
```kotlin
 *   <li>expectationComputeIoExecutor, expectationComputeCpuExecutor, operationalExecutor, backfillExecutor
```

- [ ] **Step 3: compile 검증**

Run:
```bash
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20
```

Expected: BUILD FAILED with `unresolved reference: expectationComputeExecutor` in `EquipmentDataResolver.kt` (Task 7 에서 갱신).

- [ ] **Step 4: Commit**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/InfraExecutorConfig.kt
git commit -m "$(cat <<'EOF'
refactor(infra): split expectationComputeExecutor into IO/CPU (#1126)

InfraExecutorConfig: replace expectationComputeExecutor with:
- expectationComputeIoExecutor (prefix: expectation-io-)
  Sizing from executorProperties.expectation.computeIo
- expectationComputeCpuExecutor (prefix: expectation-cpu-)
  Sizing from executorProperties.expectation.computeCpu
  (ItemCalculationExecutorConfig sizing pattern)

EquipmentDataResolver will reference new IO bean in Task 7.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Modify ExecutorConfig.kt — add RestController import

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt`

- [ ] **Step 1: `@Import` 에 `RestControllerExecutorConfig` 추가**

기존:
```kotlin
@Configuration
@Import(CoreExecutorConfig::class, InfraExecutorConfig::class)
class ExecutorConfig
```

변경 후:
```kotlin
@Configuration
@Import(CoreExecutorConfig::class, InfraExecutorConfig::class, RestControllerExecutorConfig::class)
class ExecutorConfig
```

- [ ] **Step 2: javadoc 갱신**

기존 (lines 7-15):
```kotlin
/**
 * Legacy Executor Configuration — imports both Core and Infra configs.
 *
 * <p>module-app and other full-stack modules can continue using this.
 * Lightweight modules (external-api, synchronizer, calculator) should
 * import {@link CoreExecutorConfig} directly.
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 */
```

변경 후:
```kotlin
/**
 * Legacy Executor Configuration — imports Core, Infra, and RestController configs.
 *
 * <p>module-app and other full-stack modules can continue using this.
 * Lightweight modules (external-api, synchronizer, calculator) should
 * import {@link CoreExecutorConfig} directly.
 * module-rest-controller imports {@link RestControllerExecutorConfig} directly.
 *
 * @see CoreExecutorConfig
 * @see InfraExecutorConfig
 * @see RestControllerExecutorConfig
 */
```

- [ ] **Step 3: compile 검증**

Run:
```bash
./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -20
```

Expected: BUILD FAILED (injection sites 미갱신). 다음 Task 에서 fix.

- [ ] **Step 4: Commit**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt
git commit -m "$(cat <<'EOF'
refactor(infra): import RestControllerExecutorConfig in ExecutorConfig

module-app now imports Core + Infra + RestController via ExecutorConfig.
restApiControllerExecutor bean available to all consumers.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Update injection sites (5 sites / 4 files)

**Files:**
- Modify: `module-infra/.../messaging/PgmqEventPublisherAdapter.kt`
- Modify: `module-infra/.../messaging/KafkaEventPublisher.kt`
- Modify: `module-infra/.../provider/EquipmentFetchProvider.kt`
- Modify: `module-infra/.../cache/equipment/EquipmentDataResolver.kt`

- [ ] **Step 1: PgmqEventPublisherAdapter.kt — `@Qualifier("taskExecutor")` → `@Qualifier("defaultAsyncExecutor")`**

Read the file (line 35 area) and apply:

```kotlin
// Old:
    @Qualifier("taskExecutor") private val taskExecutor: Executor,

// New:
    @Qualifier("defaultAsyncExecutor") private val taskExecutor: Executor,
```

(필드 이름 `taskExecutor` 는 그대로 유지. qualifier 만 변경.)

- [ ] **Step 2: KafkaEventPublisher.kt — 동일 변경 (line 26)**

```kotlin
// Old:
    @Qualifier("taskExecutor") private val taskExecutor: Executor,

// New:
    @Qualifier("defaultAsyncExecutor") private val taskExecutor: Executor,
```

- [ ] **Step 3: EquipmentFetchProvider.kt — 동일 변경 (line 51)**

```kotlin
// Old:
    @Qualifier("taskExecutor") private val executor: Executor,

// New:
    @Qualifier("defaultAsyncExecutor") private val executor: Executor,
```

- [ ] **Step 4: EquipmentDataResolver.kt — `@Qualifier("expectationComputeExecutor")` → `@Qualifier("expectationComputeIoExecutor")` (line 42)**

```kotlin
// Old:
    @Qualifier("expectationComputeExecutor") private val expectationExecutor: Executor,

// New:
    @Qualifier("expectationComputeIoExecutor") private val expectationExecutor: Executor,
```

- [ ] **Step 5: compile 검증**

Run:
```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava --continue 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. (모든 @Qualifier 가 일치하는 bean 으로 resolve.)

- [ ] **Step 6: Commit (4 file 모두 한 commit)**

Run:
```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/PgmqEventPublisherAdapter.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/KafkaEventPublisher.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/provider/EquipmentFetchProvider.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/equipment/EquipmentDataResolver.kt
git commit -m "$(cat <<'EOF'
refactor(infra): update @Qualifier to new bean names (#1126)

5 sites hard renamed:
- PgmqEventPublisherAdapter: taskExecutor → defaultAsyncExecutor
- KafkaEventPublisher: taskExecutor → defaultAsyncExecutor
- EquipmentFetchProvider: taskExecutor → defaultAsyncExecutor
- EquipmentDataResolver: expectationComputeExecutor → expectationComputeIoExecutor

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Update ExecutorConfigTest.java — 4 test method 갱신

**Files:**
- Modify: `module-app/src/test/java/maple/expectation/config/ExecutorConfigTest.java`

- [ ] **Step 1: `setUp()` 메서드의 expectation override 갱신**

기존 (lines 39-40):
```java
    // Override expectation pool to match documented values (core=4, max=8)
    executorProperties.getExpectation().setCorePoolSize(4);
    executorProperties.getExpectation().setMaxPoolSize(8);
```

변경 후:
```java
    // Override expectation.compute-io pool to match documented values (core=4, max=8)
    executorProperties.getExpectation().getComputeIo().setCorePoolSize(4);
    executorProperties.getExpectation().getComputeIo().setMaxPoolSize(8);
```

(또는 한 줄로: `executorProperties.getExpectation().getComputeIo().setCorePoolSize(4); executorProperties.getExpectation().getComputeIo().setMaxPoolSize(8);`)

- [ ] **Step 2: 4 test method 의 bean method reference 갱신**

기존 (lines 49-52 등 4 곳):
```java
Executor executor = executorConfig.expectationComputeExecutor(noOpDecorator);
```

변경 후:
```java
Executor executor = executorConfig.expectationComputeIoExecutor(noOpDecorator);
```

이 라인은 4 test 에 등장:
- `expectationComputeExecutor_QueueFull_ThrowsRejected` (line 52)
- `expectationComputeExecutor_RejectedCounter_Increments` (line 136)
- `expectationComputeExecutor_MetricsRegistered` (line 222)
- (`_QueueFull_ThrowsRejected` 의 displayName 도 갱신 가능하나 코드 동작에는 무관)

`replace_all: true` 로 4개 일괄 갱신 권장.

- [ ] **Step 3: 검증 — metric name 갱신**

기존 (lines 156, 166, 225, 228):
```java
double beforeCount =
    meterRegistry.counter("executor.rejected", "name", "expectation.compute").count();
...
double afterCount =
    meterRegistry.counter("executor.rejected", "name", "expectation.compute").count();
```

변경 후:
```java
double beforeCount =
    meterRegistry.counter("executor.rejected", "name", "expectation.compute-io").count();
...
double afterCount =
    meterRegistry.counter("executor.rejected", "name", "expectation.compute-io").count();
```

또한:
```java
assertThat(meterRegistry.find("executor.pool.size").tag("name", "expectation.compute").gauge())
```

변경 후:
```java
assertThat(meterRegistry.find("executor.pool.size").tag("name", "expectation.compute-io").gauge())
```

- [ ] **Step 4: test compile + run**

Run:
```bash
./gradlew :module-app:compileTestJava :module-app:test --tests "*ExecutorConfigTest" 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

Run:
```bash
git add module-app/src/test/java/maple/expectation/config/ExecutorConfigTest.java
git commit -m "$(cat <<'EOF'
test(app): update ExecutorConfigTest for IO/CPU split (#1126)

- setUp(): expectation core/max override now on .getComputeIo()
- 3 test methods: bean method expectationComputeExecutor → expectationComputeIoExecutor
- 3 metric name references: expectation.compute → expectation.compute-io

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Update all module application.yml files

**Files:**
- Modify: `module-app/src/main/resources/application.yml`
- Modify: `module-calculator/src/main/resources/application.yml`
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-synchronizer/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application.yml`

- [ ] **Step 1: 기존 `executor.expectation: ...` 블록을 nested 구조로 교체**

각 모듈의 `application.yml`에서 기존 (예: module-app):
```yaml
executor:
  equipment:
    core-pool-size: 8
    max-pool-size: 16
    queue-capacity: 200
  preset:
    core-pool-size: 12
    max-pool-size: 24
    queue-capacity: 100
  alert:
    core-pool-size: 2
    max-pool-size: 4
    queue-capacity: 200
  expectation:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 200
  async:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100
  # ... 기타 유지
```

을 다음으로 교체:
```yaml
executor:
  equipment:
    core-pool-size: 8
    max-pool-size: 16
    queue-capacity: 200
  preset:
    core-pool-size: 12
    max-pool-size: 24
    queue-capacity: 100
  alert:
    core-pool-size: 2
    max-pool-size: 4
    queue-capacity: 200
  expectation:
    compute-io:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 200
    compute-cpu:
      core-pool-size: 4
      max-pool-size: 8
      queue-capacity: 1000
  async:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100
  # ... 기타 유지
```

(YAML-config.md 규칙: 기존 `expectation:` 블록에 merge, 새 root key 생성 금지. 들여쓰기 2-space.)

- [ ] **Step 2: 5개 모듈 모두 동일하게 적용**

각 application.yml 의 `executor.expectation.*` (flat) → `executor.expectation.compute-io.*` + `executor.expectation.compute-cpu.*` 로 교체.

- [ ] **Step 3: 검증 (production-grade profile 도 함께 갱신)**

Run:
```bash
find . -name "application*.yml" -not -path "*/build/*" -not -path "*/.worktrees/*" -not -path "*/node_modules/*" -exec grep -l "expectation:" {} \;
```

Expected: 5+ files (application.yml, application-local.yml, application-prod.yml, application-test.yml, application-ci.yml 등). 각 파일에서 `executor.expectation: ...` flat key 가 있는지 확인하고, 있다면 동일하게 nested 구조로 갱신.

- [ ] **Step 4: commit (per module 또는 batch)**

Run:
```bash
git add module-app/src/main/resources/application.yml \
        module-calculator/src/main/resources/application.yml \
        module-external-api/src/main/resources/application.yml \
        module-synchronizer/src/main/resources/application.yml \
        module-rest-controller/src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
fix(yaml): nest executor.expectation.compute-io/cpu (#1126)

5 module application.yml updated:
- module-app
- module-calculator
- module-external-api
- module-synchronizer
- module-rest-controller

executor.expectation (flat: core-pool-size, max-pool-size, queue-capacity)
→ executor.expectation.compute-io (IO-bound, core=4 max=8 queue=200)
+ executor.expectation.compute-cpu (CPU-bound, core=4 max=4 queue=1000)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Final verification

**Files:** None (read-only verification + compile + push + PR)

- [ ] **Step 1: old name grep 검증**

```bash
grep -rn '"taskExecutor"' --include='*.kt' --include='*.java' \
    module-infra module-app module-external-api module-synchronizer module-calculator module-rest-controller 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
```
Expected: 0 hit (모두 `defaultAsyncExecutor` 또는 `restApiControllerExecutor` 로 rename).

```bash
grep -rn '"expectationComputeExecutor"' --include='*.kt' --include='*.java' \
    module-infra module-app 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
```
Expected: 0 hit (모두 `expectationComputeIoExecutor` 또는 `expectationComputeCpuExecutor` 로).

- [ ] **Step 2: new name grep 검증**

```bash
grep -rn '@Qualifier("(defaultAsyncExecutor|restApiControllerExecutor|expectationComputeIoExecutor|expectationComputeCpuExecutor)")' --include='*.kt' --include='*.java' \
    module-infra module-app 2>/dev/null \
    | grep -v '/build/' | grep -v '\.worktrees/'
```
Expected: 4+ hits (PgmqEventPublisherAdapter, KafkaEventPublisher, EquipmentFetchProvider, EquipmentDataResolver).

- [ ] **Step 3: compile 검증 (전체)**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: unit test 검증**

```bash
./gradlew :module-app:test --tests "*ExecutorConfigTest" 2>&1 | tail -15
```
Expected: 4 tests passed.

- [ ] **Step 5: push + PR**

```bash
git push -u origin feature/1126-executor-rename-split
```

```bash
gh pr create --base develop --head feature/1126-executor-rename-split \
    --title "refactor(infra): executor bean rename + expectationComputeExecutor IO/CPU split (#1126)" \
    --body "$(cat <<'EOF'
## Summary

- expectationComputeExecutor를 IO/CPU 두 executor로 분리 (Issue #1126)
- taskExecutor bean 이름 충돌 해결: `defaultAsyncExecutor` (Core) + `restApiControllerExecutor` (RestController, 신규)
- 5 injection site hard rename
- 5 module application.yml 의 expectation 키 구조 nested

## 산출물

### 신규 bean (4)
| Bean | Config | Pool sizing |
|---|---|---|
| `defaultAsyncExecutor` | CoreExecutorConfig | `executor.async` (async- prefix) |
| `restApiControllerExecutor` | RestControllerExecutorConfig (NEW) | `executor.async` (rest-api- prefix) |
| `expectationComputeIoExecutor` | InfraExecutorConfig | `executor.expectation.compute-io` (expectation-io- prefix) |
| `expectationComputeCpuExecutor` | InfraExecutorConfig | `executor.expectation.compute-cpu` (expectation-cpu- prefix) |

### 제거 (3)
- `CoreExecutorConfig.taskExecutor` (replaced by defaultAsyncExecutor)
- `InfraExecutorConfig.expectationComputeExecutor` (replaced by IoCpu split)
- (RestControllerExecutorConfig 는 신규, 제거 항목 없음)

## Acceptance Criteria 매핑

| #1126 AC | 충족 위치 |
|---|---|
| expectationComputeExecutor가 IO/CPU 역할로 분리됨 | `expectationComputeIoExecutor` + `expectationComputeCpuExecutor` |
| taskExecutor bean 이름 충돌 해결 | `defaultAsyncExecutor` + `restApiControllerExecutor` 명시 분리 |
| 기존 bean을 참조하는 모든 injection point에 @Qualifier 업데이트 | 4 file (5 site) hard rename |
| compileKotlin --continue 통과 | Task 7 Step 5 + Task 10 Step 3 |

## 영향 범위

- 5 file modified (ExecutorProperties, CoreExecutorConfig, InfraExecutorConfig, RestControllerExecutorConfig (NEW), ExecutorConfig)
- 4 file modified (PgmqEventPublisherAdapter, KafkaEventPublisher, EquipmentFetchProvider, EquipmentDataResolver)
- 1 file modified (ExecutorConfigTest.java)
- 5 module application.yml updated

## Verification

```bash
# 1. Old names absent
grep -rn '"taskExecutor"\|"expectationComputeExecutor"' --include='*.kt' --include='*.java' module-* 2>/dev/null | grep -v build/ | grep -v .worktrees/
# Expected: 0 hits

# 2. New names present
grep -rn '@Qualifier("(defaultAsyncExecutor|restApiControllerExecutor|expectationComputeIoExecutor|expectationComputeCpuExecutor)")' --include='*.kt' --include='*.java' module-infra module-app 2>/dev/null | grep -v build/
# Expected: 4+ hits

# 3. Compile
./gradlew compileKotlin compileJava --continue
# Expected: BUILD SUCCESSFUL

# 4. Tests
./gradlew :module-app:test --tests "*ExecutorConfigTest"
# Expected: 4 tests passed
```

## Out of Scope

- #1125 ADR-723 의 `Dispatchers.Default` 결정 변경 안 함 (legacy CPU executor 한정, 후속 #1128-#1131 은 ADR-723 패턴)
- 다른 executor 이름 (alertTaskExecutor, aiTaskExecutor, operationalExecutor, backfillExecutor) 유지
- runtime 부하테스트는 #1125 머지 후 cold-miss 시나리오로 별도 측정

## Related

- Spec: docs/superpowers/specs/2026-06-08-1126-executor-rename-split-design.md
- ADR-723: docs/01_ADR/ADR-723_io-cpu-split-pattern.md (Dispatchers.Default 결정)
- Blocks (indirect): #1198 (saturation metric follow-up)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: PR URL 출력. 사용자에게 보고.

- [ ] **Step 6: PR URL 사용자 보고**

`gh pr view --json url,state,baseRefName,headRefName,number` 으로 verify. `state: OPEN`, `base: develop`, `head: feature/1126-executor-rename-split` 확인.

---

## Self-Review Checklist

- [x] **Spec coverage:** #1126 AC 4개 모두 Task 2-9에 매핑. `expectationComputeExecutor 분리` (Task 5) + `taskExecutor bean 충돌 해결` (Task 3+4) + `injection site @Qualifier 업데이트` (Task 7) + `compileKotlin --continue 통과` (Task 10 Step 3)
- [x] **No placeholders:** TBD/TODO 없음. 모든 코드 블록 완전.
- [x] **Type/symbol consistency:**
  - `ExpectationConfig.computeIo` / `computeCpu` — Task 2에서 정의, Task 5에서 사용
  - `defaultAsyncExecutor` / `restApiControllerExecutor` / `expectationComputeIoExecutor` / `expectationComputeCpuExecutor` — 일관
  - `@Bean(name = [...])` 와 `@Qualifier("...")` 매칭
- [x] **Frequent commits:** Task 1-9 각각 의미있는 commit 단위 분리 (총 ~10 commit)
- [x] **Test:** 기존 ExecutorConfigTest.java 갱신 (4 test method), refactor 의도상 새 test 추가 없음

## Execution Notes

- Task 2-6 compile 이 **중간에 fail** 함 (injection site 미갱신). 이는 의도됨 — `git checkout HEAD --` 로 복원 가능. Task 7 완료 후 compile SUCCESSFUL.
- YAML 변경은 5 module 모두 동일 패턴. 일괄 sed 또는 read+rewrite 가능. `yaml-config.md` 규칙 준수 (2-space, 기존 블록 merge).
- PR 머지 후 #1198 (saturation metric) 작업 시작 가능.
- 부하테스트는 #1125 머지 후 cold-miss 시나리오로 별도 진행 (별도 PR).
