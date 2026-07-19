# ETL Runtime Ownership Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the final `module-infra` runtime, executor, task-wrapper, and lifecycle coupling from the four active ETL executables while preserving each active bean's effective thread model, failure visibility, shutdown, and app/web compatibility.

**Architecture:** Do not create a shared runtime module. External-api owns only its active executors and scheduler lifecycle; synchronizer owns its result/basic/OCID executors and explicit best-effort ranking metrics; calculator and artifact resources remain owned by the modules established in the preceding plans. A root Gradle gate rejects both source references and transitive runtime-classpath reintroduction of `module-infra`.

**Tech Stack:** Kotlin/JDK 21, Gradle Groovy DSL, Spring Boot 3.5.4, Spring `SmartLifecycle`, `ThreadPoolTaskExecutor`, Java virtual threads, CompletableFuture, Micrometer, JUnit 5, AssertJ, Mockito-Kotlin, Awaitility.

**Spec:** `docs/superpowers/specs/2026-07-19-etl-runtime-ownership-closure-design.md`

**Depends on:** Complete `2026-07-19-pipeline-artifact-lifecycle.md`, `2026-07-19-kafka-delivery-outcome.md`, `2026-07-19-valuation-kernel-extraction.md`, and `2026-07-19-nexon-access-consolidation.md` first. This plan is the final closure slice and assumes their compatibility facades and active-module replacements exist.

## Global Constraints

- Do not introduce `module-runtime`, a generic executor facade, a generic task context, or a copied `LogicExecutor`.
- Preserve effective runtime behavior, not the unused bean list in infra. `authCharacterFetchExecutor` is currently a local platform pool (core 2/max 4/queue 100); the preceding messaging plan removes its caller, so delete it. `externalApiSchedulerExecutor` has no caller, so do not recreate it.
- Preserve `internalApiExecutor`, `urgentCharacterRequestExecutor`, `kafkaResultChunkExecutor`, and `basicSnapshotChunkExecutor` as virtual-thread-per-task executors with their current names. Rename only the former broad `defaultAsyncExecutor` injection to the workload-specific `synchronizerOcidLookupExecutor`, preserving core 8/max 16/queue 200 platform-pool behavior.
- Keep `loopExecutor` in its existing external-api local configuration. Do not merge it with scheduler, urgent, or internal API execution.
- Do not use `join()`, `get()`, `runBlocking`, `Thread.sleep`, or coroutine `delay` in new production/test code. Use completion-stage composition, latches, and Awaitility.
- Do not add Testcontainers or an integration-test source set. Use deterministic component tests and the repository's existing Docker services for final boot checks.
- Do not add a new `try-catch` or `!!`. Use `runCatching`; restore interrupt status when an owned shutdown wait receives `InterruptedException`.
- Best-effort work must retain a structured warning and a counter with static tags. Required Kafka/storage/DB work must retain the original cause for the owning delivery classifier.
- Do not run destructive database commands or either load-test reset flag.
- Capture exact before/after runtime classpaths, bootJar sizes, startup health times, thread names/types, and shutdown outcomes in `docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md`.
- Before every task commit, run its focused tests and `git diff --check`.

---

## Task 1: Freeze effective runtime semantics and record ADR-749

**Files:**

- Create: `docs/01_ADR/ADR-749-worker-owned-etl-runtime.md`
- Create: `docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md`
- Read/record: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/VtExecutorConfig.kt`
- Read/record: `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CoreExecutorConfig.kt`
- Read/record: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthExecutorConfig.kt`
- Read/record: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

**Interfaces:**

- Consumes: the post-P0/P1 source tree and resolved Spring/Gradle runtime graph.
- Produces: an immutable baseline table and an accepted decision describing exactly which runtime resources move, remain local, or disappear.

- [ ] **Step 1: Prove that all prerequisite plans are present**

Run:

```bash
test -f module-pipeline-artifact/build.gradle
test -f module-pipeline-messaging/build.gradle
test -f module-nexon-client/build.gradle
test -f module-core/src/main/kotlin/maple/expectation/core/calculation/ValuationKernel.kt
```

Expected: every command exits `0`. If any file is absent, stop this plan and complete its named prerequisite; do not create temporary compatibility code in the active workers.

- [ ] **Step 2: Capture residual source and dependency evidence**

Run:

```bash
rg -n 'maple\.expectation\.infrastructure|CoreExecutorConfig|VtExecutorConfig|ManagedLifecycle|LogicExecutor|TaskContext' \
  module-external-api/src/main module-calculator/src/main module-synchronizer/src/main module-cleanup/src/main
./gradlew :module-external-api:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-external-before.txt
./gradlew :module-calculator:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-calculator-before.txt
./gradlew :module-synchronizer:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-synchronizer-before.txt
./gradlew :module-cleanup:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-cleanup-before.txt
```

Expected: only runtime-ownership residuals remain. The calculator report already excludes `project :module-infra` after the valuation plan; external-api, synchronizer, and cleanup still show their exact residual path until this closure. Copy the commands, SHA-256 hashes of the four report files, and every residual location into the evidence report.

- [ ] **Step 3: Record the effective bean table**

Add this table to the evidence report and replace the final `Observed caller after prerequisites` column with the exact `rg` result:

| Bean/resource | Effective implementation before closure | Required result |
| --- | --- | --- |
| `authCharacterFetchExecutor` | local platform `ThreadPoolTaskExecutor`, 2/4/100 | no caller; delete bean/config |
| `externalApiSchedulerExecutor` | conditional infra VT bean | no caller; do not recreate |
| scheduler internal executor | inline virtual-thread-per-task | remain scheduler-owned |
| `internalApiExecutor` | conditional infra virtual-thread-per-task | local external-api VT bean |
| `urgentCharacterRequestExecutor` | conditional infra virtual-thread-per-task | local external-api VT bean |
| `loopExecutor` | local configurable `ThreadPoolTaskExecutor` | unchanged |
| `kafkaResultChunkExecutor` | conditional infra virtual-thread-per-task | local synchronizer VT bean |
| `basicSnapshotChunkExecutor` | conditional infra virtual-thread-per-task | local synchronizer VT bean |
| `defaultAsyncExecutor` for OCID | infra platform `ThreadPoolTaskExecutor`, 8/16/200 | local `synchronizerOcidLookupExecutor`, 8/16/200 |
| scheduler lifecycle | infra coordinator at Spring phase `Int.MAX_VALUE - 100` | local adapter at the same Spring phase |

Also record thread names with one submitted task per active bean. Do not infer the auth executor from `VtExecutorConfig`; verify the effective local bean precedence.

- [ ] **Step 4: Write ADR-749**

Create `docs/01_ADR/ADR-749-worker-owned-etl-runtime.md` with these exact decisions:

```markdown
# ADR-749: Active ETL workers own their runtime resources

- Status: Accepted
- Date: 2026-07-19

## Context

After extracting artifact, messaging, valuation, and Nexon seams, active ETL workers still import module-infra for generic execution and lifecycle wiring. The central VT configuration also advertises unused or shadowed beans, so copying it would preserve configuration shape rather than effective behavior.

## Decision

Each executable owns only its active named executors and lifecycle adapters. External-api keeps local loop execution, owns internal/urgent VT executors, and owns scheduler start/stop. Synchronizer owns result/basic VT executors and a workload-named 8/16/200 OCID platform pool. LogicExecutor and TaskContext are not copied. Calculator and artifact resources stay with their already-extracted owners. A Gradle gate rejects source and transitive runtime module-infra dependencies in the four workers.

## Rejected alternatives

A generic worker-runtime module, copying every VtExecutorConfig bean, and changing all pools to one thread model are rejected because they recreate coupling or alter effective production behavior.

## Consequences

Resource ownership and shutdown become visible per executable. App/web continue through module-infra compatibility facades. New active-worker code cannot import or transitively resolve module-infra.

## Evidence

Verification is recorded in docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md.
```

- [ ] **Step 5: Commit the frozen baseline and decision**

Run: `git diff --check`

Expected: no whitespace errors.

```bash
git add docs/01_ADR/ADR-749-worker-owned-etl-runtime.md docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md
git commit -m "docs: define worker-owned ETL runtime"
```

---

## Task 2: Move active external-api executors to their owner

**Files:**

- Create: `module-external-api/src/main/kotlin/maple/externalapi/config/ExternalApiExecutorConfiguration.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/config/ExternalApiExecutorConfigurationTest.kt`
- Delete: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthExecutorConfig.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/auth/AuthCharacterFetchHandlerTest.kt`

**Interfaces:**

- Consumes: Spring bean lookups for `internalApiExecutor` and `urgentCharacterRequestExecutor`.
- Produces: two locally owned `ExecutorService` beans with virtual threads and bounded, observable shutdown; no auth or unused scheduler executor bean.

- [ ] **Step 1: Write failing bean and thread-semantics tests**

In `ExternalApiExecutorConfigurationTest`, instantiate an `AnnotationConfigApplicationContext`, register `ExternalApiExecutorConfiguration`, and assert:

```kotlin
val internal = context.getBean("internalApiExecutor", ExecutorService::class.java)
val urgent = context.getBean("urgentCharacterRequestExecutor", ExecutorService::class.java)

assertThat(internal).isNotSameAs(urgent)
assertThat(context.containsBean("authCharacterFetchExecutor")).isFalse()
assertThat(context.containsBean("externalApiSchedulerExecutor")).isFalse()
```

Submit tasks that return `Thread.currentThread().isVirtual` and thread names. Use Awaitility until each future is done, then AssertJ's future assertions; do not call `get()` or `join()`. Assert both virtual flags are true.

Add a shutdown test with a `CountDownLatch`: start one task, close the context on a named test thread, release the task from the test thread, and assert both the close thread and executor terminate. Add a second configuration instance with a package-internal 10 ms shutdown timeout and a never-released task; assert the force-shutdown counter increments and the task is interrupted. No wall-clock sleeps.

Run: `./gradlew :module-external-api:test --tests '*ExternalApiExecutorConfigurationTest'`

Expected: compilation fails because the owner configuration does not exist.

- [ ] **Step 2: Implement the two-bean owner**

`ExternalApiExecutorConfiguration` has this public surface:

```kotlin
@Configuration
class ExternalApiExecutorConfiguration(
    private val meterRegistry: MeterRegistry,
    @Value("\${external-api.executor.shutdown-timeout:PT5S}")
    private val shutdownTimeout: Duration,
) {
    @Bean(name = ["internalApiExecutor"], destroyMethod = "")
    fun internalApiExecutor(): ExecutorService

    @Bean(name = ["urgentCharacterRequestExecutor"], destroyMethod = "")
    fun urgentCharacterRequestExecutor(): ExecutorService

    @PreDestroy
    fun shutdownOwnedExecutors()
}
```

Create each bean with `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("external-internal-", 0).factory())` and the corresponding `external-urgent-` prefix. Track only the two returned services. On shutdown, call `shutdown`, await each for `shutdownTimeout`, restore the interrupt flag on `InterruptedException`, then `shutdownNow` only unfinished services. Increment `etl_executor_forced_shutdown_total{module="external-api",executor="internal|urgent"}` with static tags.

- [ ] **Step 3: Remove the now-unused auth pool**

The messaging plan removes transport-owned execution and the Nexon plan replaces the consumer with `AuthCharacterFetchHandler`, which has no `Executor` constructor argument. Prove that state first:

```bash
rg -n 'authCharacterFetchExecutor' module-external-api/src/main
```

Expected before deletion: the only match is `AuthExecutorConfig.kt`. Delete that file and remove executor setup from its test fixtures. If another production caller appears, stop and repair the preceding messaging migration; do not preserve a dead executor for convenience.

- [ ] **Step 4: Verify active executor consumers**

Run:

```bash
rg -n '@Qualifier\("(internalApiExecutor|urgentCharacterRequestExecutor)"\)' module-external-api/src/main
! rg -n 'externalApiSchedulerExecutor|authCharacterFetchExecutor' module-external-api/src/main
./gradlew :module-external-api:test --tests '*ExternalApiExecutorConfigurationTest' --tests '*InternalApiControllerTest' --tests '*UrgentCharacterRequestConsumerTest'
```

Expected: the two active qualifiers resolve, neither dead name remains, and all tests pass.

- [ ] **Step 5: Commit external executor ownership**

```bash
git add module-external-api
git commit -m "refactor: move external executors to owner"
```

---

## Task 3: Replace the external infra lifecycle and task wrapper

**Files:**

- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerLifecycle.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerStopTest.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerLifecycleTest.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/metrics/SchedulerMetrics.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/metrics/SchedulerMetricsTest.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt`
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHookTest.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/metrics/OrphanCleanupMetrics.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/metrics/OrphanCleanupMetricsTest.kt`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`

**Interfaces:**

- Consumes: `ApplicationReadyEvent`, Spring shutdown callback, scheduler phase requests, and the boot-time temp directory scan.
- Produces: the same post-ready refresh/run-on-startup behavior, bounded callback-completing scheduler shutdown, and a non-blocking best-effort orphan scan with explicit metrics.

- [ ] **Step 1: Write failing lifecycle-order tests**

`ExternalApiSchedulerLifecycleTest` uses a mocked scheduler and asserts:

- `start()` marks the adapter running but does not refresh or trigger work before readiness.
- the first `ApplicationReadyEvent` calls `scheduler.startAfterReady()` exactly once; duplicate ready events are ignored.
- `phase == Int.MAX_VALUE - 100` and `isAutoStartup == true`.
- `stop(callback)` invokes `scheduler.stopAndAwait(Duration.ofSeconds(5))` on a named virtual thread, flips `isRunning` false, and invokes callback exactly once on success or failure.
- calling stop twice does not stop the scheduler twice and still completes both callbacks.

Use a latch and Awaitility to control/observe the asynchronous callback.

Run: `./gradlew :module-external-api:test --tests '*ExternalApiSchedulerLifecycleTest'`

Expected: compilation fails because the local lifecycle adapter and explicit scheduler methods do not exist.

- [ ] **Step 2: Make the scheduler Spring-neutral**

Remove `ManagedLifecycle`, `ApplicationReadyEvent`, and `@EventListener` from `ExternalApiScheduler`. Keep its internal virtual-thread-per-task executor. Expose only:

```kotlin
internal fun startAfterReady() {
    ocidCacheProvider.refresh()
    if (runOnStartup) triggerDailyRefresh(null)
}

internal fun stopAndAwait(timeout: Duration) {
    executor.shutdown()
    // runCatching around awaitTermination; restore interrupt; force only on timeout/interruption
}
```

Keep the current log messages. Add `SchedulerMetrics.recordLifecycleFailure(operation: String)` and `recordForcedShutdown()` backed by `external_api_scheduler_lifecycle_failures_total{operation="start|stop"}` and `external_api_scheduler_forced_shutdown_total`; reject any other operation in the method. `stopAndAwait` never swallows shutdown failure: it logs a structured warning and increments the appropriate counter. Delete `lifecyclePhase` and `stopLifecycle`.

- [ ] **Step 3: Add the local lifecycle adapter**

Implement `ExternalApiSchedulerLifecycle` as `SmartLifecycle` plus `ApplicationListener<ApplicationReadyEvent>`. Guard start/readiness with `AtomicBoolean`s and represent stop with one shared `CompletableFuture<Void>`. The first `stop(callback)` starts a one-shot named virtual thread (`external-api-scheduler-stop`) that runs `stopAndAwait` and completes the shared future through `runCatching`; every stop callback attaches to that same future and is invoked exactly once. It must not allocate a persistent executor or use the common pool.

- [ ] **Step 4: Write failing orphan-cleanup outcome tests**

Remove all `LogicExecutor`/`TaskContext` fixtures from `OrphanTempFileCleanupHookTest`. Assert with controllable executors:

- submit failure records `result="submit_failed"` and does not fail application startup;
- successful completion records `result="success"` with scanned/deleted/bytes counts;
- timeout records `result="timeout"`, calls `cancel(true)`, and leaves startup successful;
- scan/delete failure records `result="failed"` and preserves retry-on-next-boot behavior.

Run:

```bash
./gradlew :module-external-api:test --tests '*OrphanTempFileCleanupHookTest' --tests '*OrphanCleanupMetricsTest'
```

Expected: tests fail because the hook still requires infra task wrappers and lacks outcome counters.

- [ ] **Step 5: Rewrite orphan cleanup as explicit best effort**

Constructor surface becomes:

```kotlin
class OrphanTempFileCleanupHook(
    @Qualifier("loopExecutor") private val asyncExecutor: AsyncTaskExecutor,
    private val metrics: OrphanCleanupMetrics,
    private val clock: Clock = Clock.systemUTC(),
    private val scanDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    private val timeout: Duration = Duration.ofSeconds(30),
) : ApplicationRunner
```

Change the qualified type to `AsyncTaskExecutor`. `run` creates a result `CompletableFuture<OrphanCleanupSummary>`, submits one `Future` whose body completes that result through `runCatching`, and schedules a timeout with `CompletableFuture.delayedExecutor(timeout.toMillis(), MILLISECONDS, Executor { command -> command.run() })`. The timeout callback completes the result exceptionally only if still pending and calls `submitted.cancel(true)`, so the underlying `FutureTask` receives an interrupt. One `whenComplete` records the unwrapped terminal result. Synchronous submission uses `runCatching`. `cleanupOrphans` returns an `OrphanCleanupSummary`, closes its directory stream with `use`, and checks `Thread.currentThread().isInterrupted` before inspecting or deleting each entry; cancellation stops the scan rather than continuing filesystem work after timeout. It does not create dynamic metric tags. `OrphanCleanupMetrics` exposes `record(result: OrphanCleanupResult, summary: OrphanCleanupSummary?)` with the closed results `SUCCESS`, `SUBMIT_FAILED`, `TIMEOUT`, and `FAILED`.

- [ ] **Step 6: Remove external lifecycle imports and verify**

Delete `ManagedLifecycleCoordinator` from `ExternalApiApplication` imports and `@Import`; retain only configurations provided by the four prerequisite plans and the external-api package scan.

Run:

```bash
./gradlew :module-external-api:test --tests '*ExternalApiSchedulerTest' --tests '*ExternalApiSchedulerStopTest' --tests '*ExternalApiSchedulerLifecycleTest' --tests '*SchedulerMetricsTest' --tests '*OrphanTempFileCleanupHookTest' --tests '*OrphanCleanupMetricsTest'
! rg -n 'maple\.expectation\.infrastructure\.(executor|lifecycle)' module-external-api/src/main
```

Expected: tests pass and no external production source imports infra execution/lifecycle packages.

- [ ] **Step 7: Commit lifecycle and best-effort ownership**

```bash
git add module-external-api
git commit -m "refactor: localize external lifecycle"
```

---

## Task 4: Move synchronizer executors and best-effort ranking control to their owner

**Files:**

- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerExecutorConfiguration.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerMdcTaskDecorator.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/config/SynchronizerExecutorConfigurationTest.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/SynchronizerSubscriptionsTest.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingMetrics.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriterTest.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt`

**Interfaces:**

- Consumes: named executor injections for result/basic/OCID handling and best-effort Redis ranking documents.
- Produces: worker-owned executors with preserved effective semantics and an explicit ranking fallback whose failures remain visible.

- [ ] **Step 1: Write failing executor compatibility tests**

In `SynchronizerExecutorConfigurationTest`, load only the new configuration and `SimpleMeterRegistry`. Assert:

- `kafkaResultChunkExecutor` and `basicSnapshotChunkExecutor` are distinct `ExecutorService`s and execute on virtual threads;
- `synchronizerOcidLookupExecutor` is a `ThreadPoolTaskExecutor` with core 8, max 16, queue capacity 200, `async-` prefix, wait-for-completion enabled, and 30-second await termination;
- one MDC key is propagated into the OCID task and the worker's prior MDC is restored afterward; no app/web-only cache context is imported;
- the context has no `defaultAsyncExecutor`;
- closing the context terminates the two VT owners and the platform pool; a controlled unfinished VT task exercises the five-second force path through a package-internal short timeout.

Use AssertJ future assertions and Awaitility, never blocking future retrieval.

Run: `./gradlew :module-synchronizer:test --tests '*SynchronizerExecutorConfigurationTest'`

Expected: compilation fails because the owner configuration does not exist.

- [ ] **Step 2: Implement synchronizer executor ownership**

Expose exactly:

```kotlin
@Bean(name = ["kafkaResultChunkExecutor"], destroyMethod = "")
fun kafkaResultChunkExecutor(): ExecutorService

@Bean(name = ["basicSnapshotChunkExecutor"], destroyMethod = "")
fun basicSnapshotChunkExecutor(): ExecutorService

@Bean(name = ["synchronizerOcidLookupExecutor"])
fun synchronizerOcidLookupExecutor(): ThreadPoolTaskExecutor
```

Name VT threads `sync-result-chunk-` and `sync-basic-chunk-`; track and close only those two in a configuration `@PreDestroy` using the same bounded algorithm as Task 2. Inject `@Value("\${synchronizer.executor.vt-shutdown-timeout:PT5S}") Duration` so tests can exercise the force path without waiting five seconds. Configure the OCID pool to core 8/max 16/queue 200, `async-` prefix, core timeout true, keep-alive 30 seconds, workload-local `SynchronizerMdcTaskDecorator`, `AbortPolicy`, wait-for-tasks true, and await termination 30 seconds. The decorator snapshots/restores only SLF4J MDC; do not copy infra's app/web cache context. Let Spring own the pool's single shutdown; do not add it to the VT tracker.

- [ ] **Step 3: Rename the OCID injection without changing the work chain**

In the post-messaging `OcidLookupRunConsumer`/subscription handler, change only the qualifier from `defaultAsyncExecutor` to `synchronizerOcidLookupExecutor`. Keep JSON decoding on `CompletableFuture.supplyAsync` and compose `ocidLookupService.ingest` into its outcome. A parse/ingest failure must reach `DeliveryOutcome.Retryable` with its original unwrapped cause; it must not acknowledge in a `whenComplete` callback.

- [ ] **Step 4: Write failing best-effort ranking tests**

Test the three current boundaries independently: filter, group, and Redis update. For each injected failure, assert `update` returns normally, no later unsafe stage runs, `equipment_ranking_write_failures_total{stage="filter|group|redis"}` increments once, and a structured warning is emitted. Assert successful grouping and top-N trim remain byte/key compatible.

Run: `./gradlew :module-synchronizer:test --tests '*EquipmentRankingRedisWriterTest'`

Expected: tests fail because the writer still imports `LogicExecutor` and has no local failure metric.

- [ ] **Step 5: Replace LogicExecutor with visible local control**

Remove `LogicExecutor` and `TaskContext` from `EquipmentRankingRedisWriter`. Inject `EquipmentRankingMetrics`. Use one `runCatching` per existing boundary and `getOrElse` to preserve current fallbacks:

```kotlin
val rankable = runCatching { documents.filter { it.userIgn?.isNotBlank() == true } }
    .getOrElse { failure ->
        metrics.recordFailure("filter")
        log.warn("Equipment ranking filter failed", failure)
        emptyList()
    }
```

Repeat exactly for `group` and `redis`; accepted metric tags are the three static values only. Do not move the call to a different executor: the old `LogicExecutor` executed these suppliers synchronously.

- [ ] **Step 6: Remove empty coordinator wiring and verify**

Delete `ManagedLifecycleCoordinator`, `CoreExecutorConfig`, `VtExecutorConfig`, and infra executor package scanning from `SynchronizerApplication`. Import the messaging/artifact configurations supplied by prerequisites and rely on component scanning for `SynchronizerExecutorConfiguration`.

Run:

```bash
./gradlew :module-synchronizer:test --tests '*SynchronizerExecutorConfigurationTest' --tests '*SynchronizerSubscriptionsTest' --tests '*ChunkConsumerTemplateTest' --tests '*EquipmentRankingRedisWriterTest'
! rg -n 'maple\.expectation\.infrastructure\.(executor|lifecycle)' module-synchronizer/src/main
```

Expected: tests pass; original causes reach delivery classification; no infra execution/lifecycle import remains.

- [ ] **Step 7: Commit synchronizer runtime ownership**

```bash
git add module-synchronizer
git commit -m "refactor: localize synchronizer runtime"
```

---

## Task 5: Remove `module-infra` from all active worker graphs and add a permanent gate

**Files:**

- Modify: `module-external-api/build.gradle`
- Modify: `module-calculator/build.gradle`
- Modify: `module-synchronizer/build.gradle`
- Modify: `module-cleanup/build.gradle`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt`
- Modify: `module-cleanup/src/main/kotlin/maple/cleanup/CleanupApplication.kt`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/consumer/SnapshotDispatchService.kt`
- Modify: `build.gradle`

**Interfaces:**

- Consumes: the replacement modules/configurations completed by all five plans.
- Produces: four active production source trees and four resolved runtime classpaths with no direct or transitive `module-infra` dependency, guarded on every root `check`.

- [ ] **Step 1: Add the failing isolation gate first**

Add this root task before removing dependencies:

```groovy
def activeEtlProjectPaths = [
    ':module-external-api',
    ':module-calculator',
    ':module-synchronizer',
    ':module-cleanup',
]

tasks.register('verifyActiveEtlInfraIsolation') {
    group = 'verification'
    description = 'Rejects module-infra source and runtime dependencies in active ETL workers.'

    doLast {
        def violations = []
        activeEtlProjectPaths.each { projectPath ->
            def worker = project(projectPath)
            worker.fileTree('src/main') {
                include '**/*.kt', '**/*.java'
            }.each { source ->
                if (source.getText('UTF-8').contains('maple.expectation.infrastructure')) {
                    violations << "${projectPath}:source:${worker.relativePath(source)}"
                }
            }

            worker.configurations.runtimeClasspath.incoming.resolutionResult.allComponents.each { component ->
                def id = component.id
                if (id instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier &&
                    id.projectPath == ':module-infra') {
                    violations << "${projectPath}:runtimeClasspath:${component.id.displayName}"
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException("Active ETL module-infra isolation violations:\n" + violations.sort().join('\n'))
        }
    }
}

tasks.named('check') {
    dependsOn(tasks.named('verifyActiveEtlInfraIsolation'))
}
```

Run: `./gradlew verifyActiveEtlInfraIsolation`

Expected: task fails and prints each still-direct/transitive source/runtime violation. Keep this red output in the evidence report.

- [ ] **Step 2: Remove direct dependencies and residual imports**

Delete `implementation project(':module-infra')` / `implementation(project(":module-infra"))` from external-api, synchronizer, and cleanup. Verify calculator's valuation plan has already removed it; do not re-add it. Do not replace any dependency with broad transitives. Each worker retains only the exact modules in the program target dependency table.

Remove every infra import/fully-qualified `@Import` from the four application classes. Update `SnapshotDispatchService`'s stale comment to name `module-pipeline-messaging`. There must be no production reference, including comments, because the gate is intentionally lexical as well as resolved.

- [ ] **Step 3: Run the isolation and dependency-direction checks**

Run:

```bash
./gradlew verifyActiveEtlInfraIsolation
! rg -n 'maple\.expectation\.infrastructure' \
  module-external-api/src/main module-calculator/src/main module-synchronizer/src/main module-cleanup/src/main
! rg -n 'module-infra' \
  module-external-api/build.gradle module-calculator/build.gradle module-synchronizer/build.gradle module-cleanup/build.gradle
./gradlew :module-external-api:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-external-after.txt
./gradlew :module-calculator:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-calculator-after.txt
./gradlew :module-synchronizer:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-synchronizer-after.txt
./gradlew :module-cleanup:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-cleanup-after.txt
! rg -n 'project :module-infra' /tmp/runtime-closure-*-after.txt
```

Expected: every command exits `0`, with no source, direct build, or transitive resolved dependency match.

- [ ] **Step 4: Prove app/web compatibility still compiles**

Run:

```bash
./gradlew :module-app:compileKotlin :module-app:compileJava :module-web:compileKotlin :module-web:compileJava --continue
./gradlew :module-infra:test --tests '*StorageConfigTest' --tests '*CoreLegacyValuationParityTest' --tests '*NexonCompatibilityAdapterTest'
```

Expected: app/web compile through the infra facades; all artifact/calculation/Nexon compatibility tests created by prerequisite plans pass. Do not move app/web-only infrastructure code.

- [ ] **Step 5: Compile all active workers**

Run:

```bash
./gradlew \
  :module-external-api:compileKotlin :module-external-api:compileJava \
  :module-calculator:compileKotlin :module-calculator:compileJava \
  :module-synchronizer:compileKotlin :module-synchronizer:compileJava \
  :module-cleanup:compileKotlin :module-cleanup:compileJava \
  --continue
```

Expected: `BUILD SUCCESSFUL`; no missing bean/type is hidden by `--continue` output.

- [ ] **Step 6: Commit the graph closure and gate**

```bash
git add build.gradle module-external-api module-calculator module-synchronizer module-cleanup
git commit -m "refactor: remove infra from active ETL"
```

---

## Task 6: Run focused regression, runtime boot, and before/after evidence

**Files:**

- Modify: `docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md`
- Modify only if the measured result disproves an assumption: the owning configuration/test from Tasks 2-5

**Interfaces:**

- Consumes: the final four active executables and existing Docker Kafka/Postgres/Redis/MinIO services.
- Produces: reproducible compile/test/boot/dependency evidence and a clean, guarded closure.

- [ ] **Step 1: Run all focused runtime tests together**

Run:

```bash
./gradlew :module-external-api:test \
  --tests '*ExternalApiExecutorConfigurationTest' \
  --tests '*ExternalApiSchedulerTest' \
  --tests '*ExternalApiSchedulerStopTest' \
  --tests '*ExternalApiSchedulerLifecycleTest' \
  --tests '*SchedulerMetricsTest' \
  --tests '*OrphanTempFileCleanupHookTest' \
  --tests '*OrphanCleanupMetricsTest'
./gradlew :module-synchronizer:test \
  --tests '*SynchronizerExecutorConfigurationTest' \
  --tests '*SynchronizerSubscriptionsTest' \
  --tests '*ChunkConsumerTemplateTest' \
  --tests '*EquipmentRankingRedisWriterTest'
./gradlew :module-calculator:test \
  --tests '*ValuationEngineConfigurationTest' \
  --tests '*CubeProbabilityResourceParityTest'
./gradlew :module-cleanup:test --tests '*ConsumedChunkInboxTest'
./gradlew :module-pipeline-artifact:test --tests '*ArtifactUploadResourcesTest' --tests '*MinioStorageResourcesTest'
./gradlew :module-core:test --tests '*ValuationKernelTest'
./gradlew :module-infra:test --tests '*CoreLegacyValuationParityTest'
```

Expected: all focused tests pass with no skipped test introduced by this plan; both direct and compatibility kernel paths are covered.

- [ ] **Step 2: Build active boot JARs and record size**

Run:

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar :module-cleanup:bootJar
stat -c '%n %s' module-external-api/build/libs/*.jar module-calculator/build/libs/*.jar module-synchronizer/build/libs/*.jar module-cleanup/build/libs/*.jar
```

Expected: build succeeds. Add exact before/after byte sizes and deltas to the evidence report; do not claim improvement if a JAR grew because a narrower module replaced infra.

- [ ] **Step 3: Start existing dependencies without resets**

Run:

```bash
docker compose up -d postgres redis kafka minio minio-bootstrap
docker compose ps postgres redis kafka minio
```

Expected: the four services become healthy/running. Do not set `RESET_VIEWS` or `RESET_ACTIVE_JOBS`, and do not delete any database/object data.

- [ ] **Step 4: Boot and stop each executable by captured PID**

Run the built executable JARs sequentially with this exact helper so the PID belongs to the application rather than a Gradle daemon:

```bash
set -euo pipefail
mkdir -p logs

runtime_closure_boot_check() (
  runtime_module="$1"
  runtime_port="$2"
  runtime_jar="$runtime_module/build/libs/$runtime_module-0.0.1-SNAPSHOT.jar"
  runtime_log="logs/runtime-closure-$runtime_module.log"
  runtime_health="logs/runtime-closure-$runtime_module-health.json"
  runtime_pid=""

  runtime_cleanup() {
    if test -n "$runtime_pid" && kill -0 "$runtime_pid" 2>/dev/null; then
      kill -TERM "$runtime_pid" 2>/dev/null || true
      for runtime_cleanup_attempt in $(seq 1 10); do
        if ! kill -0 "$runtime_pid" 2>/dev/null; then
          break
        fi
        sleep 1
      done
      if kill -0 "$runtime_pid" 2>/dev/null; then
        kill -KILL "$runtime_pid" 2>/dev/null || true
      fi
      wait "$runtime_pid" 2>/dev/null || true
    fi
  }
  trap runtime_cleanup EXIT
  trap 'exit 130' INT TERM

  test -f "$runtime_jar"
  ! curl -sS -o /dev/null "http://localhost:$runtime_port/actuator/health"

  runtime_started_at=$(date +%s)
  java -jar "$runtime_jar" > "$runtime_log" 2>&1 &
  runtime_pid=$!
  runtime_healthy=0

  for runtime_attempt in $(seq 1 120); do
    if curl -fsS "http://localhost:$runtime_port/actuator/health" > "$runtime_health"; then
      runtime_healthy=1
      break
    fi
    kill -0 "$runtime_pid"
    sleep 1
  done

  test "$runtime_healthy" -eq 1
  printf '%s startup_seconds=%s\n' "$runtime_module" "$(($(date +%s) - runtime_started_at))"

  runtime_stopped_at=$(date +%s)
  kill -TERM "$runtime_pid"
  for runtime_attempt in $(seq 1 40); do
    if ! kill -0 "$runtime_pid" 2>/dev/null; then
      break
    fi
    sleep 1
  done
  if kill -0 "$runtime_pid" 2>/dev/null; then
    kill -KILL "$runtime_pid"
    wait "$runtime_pid" || true
    return 1
  fi
  wait "$runtime_pid" || true
  runtime_pid=""
  printf '%s shutdown_seconds=%s\n' "$runtime_module" "$(($(date +%s) - runtime_stopped_at))"
  trap - EXIT INT TERM
)

runtime_closure_boot_check module-external-api 8081
runtime_closure_boot_check module-calculator 8082
runtime_closure_boot_check module-synchronizer 8083
runtime_closure_boot_check module-cleanup 8084
```

Expected: every health response is `UP`; logs contain no missing-bean, duplicate-bean, unresolved configuration, executor rejection, or shutdown timeout. External scheduler shutdown invokes its callback; every owned resource logs one shutdown.

- [ ] **Step 5: Compare dependency and runtime evidence**

Add to the evidence report:

- SHA-256 and `diff -u` summaries for each before/after runtime dependency report;
- bootJar byte deltas;
- startup health seconds and shutdown seconds;
- observed active executor thread prefix/type;
- forced-shutdown counter test output;
- `verifyActiveEtlInfraIsolation` green output;
- exact focused/full verification commands and exit codes.

Do not invent throughput data. Reuse artifact/calculation before/after measurements from the prerequisite reports and link them; runtime closure itself claims only no regression when the same recorded workloads pass.

- [ ] **Step 6: Run final verification**

Run:

```bash
./gradlew verifyActiveEtlInfraIsolation check
git diff --check
git status --short
```

Expected: Gradle is successful, `git diff --check` is silent, and status lists only the completed evidence update.

- [ ] **Step 7: Commit the evidence**

```bash
git add docs/05_Reports/2026-07-19-etl-runtime-ownership-closure-evidence.md
git commit -m "docs: record ETL runtime closure evidence"
```

Expected final state: `git status --short` is empty; all four active ETL runtime classpaths exclude `module-infra`; app/web compatibility remains intact; every executor/client/transfer manager has one visible owner and deterministic shutdown.

## Plan Completion Gate

- [ ] `./gradlew verifyActiveEtlInfraIsolation check` succeeds and `git diff --check` is silent.
- [ ] The four active production source trees and resolved runtime classpaths contain no `module-infra` reference.
- [ ] Active executor bean names/thread models match the frozen table; dead auth/scheduler beans are absent.
- [ ] External readiness/startup order and `SmartLifecycle` shutdown callback are proven; every owned resource closes once.
- [ ] Synchronizer DB/publish failures reach the typed delivery classifier without `LogicExecutor` translation, and permits release on every branch.
- [ ] App/web compatibility compilation and the three explicit infra facade tests pass without moving app/web-only code.
- [ ] Exact before/after classpath, JAR, boot health, thread, shutdown, and linked throughput evidence is recorded.
- [ ] `git status --short` is empty after the final evidence commit.
