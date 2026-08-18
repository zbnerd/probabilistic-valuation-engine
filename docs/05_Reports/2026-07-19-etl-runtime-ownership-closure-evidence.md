# ETL Runtime Ownership Closure Evidence

- Baseline commit: `edfeedaf90e380c5df30cbeaa148813af76a900b`
- Evidence date: 2026-07-20
- Scope: `module-external-api`, `module-calculator`, `module-synchronizer`, and `module-cleanup`
- Verification ceiling: focused named tests, static/resolved dependency evidence, and one ordered four-worker packaging pass; no broad/root `check`, database reset, Testcontainers, Docker/runtime boot, load test, or performance run.

## Task 1: Effective runtime baseline

### Prerequisites

All four prerequisite artifacts were present at the baseline commit:

```text
module-pipeline-artifact/build.gradle
module-pipeline-messaging/build.gradle
module-nexon-client/build.gradle
module-core/src/main/kotlin/maple/expectation/core/calculation/ValuationKernel.kt
```

The four `test -f` checks from the plan exited `0`.

### Baseline commands

```bash
rg -n 'maple\.expectation\.infrastructure|CoreExecutorConfig|VtExecutorConfig|ManagedLifecycle|LogicExecutor|TaskContext' \
  module-external-api/src/main module-calculator/src/main module-synchronizer/src/main module-cleanup/src/main
./gradlew :module-external-api:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-external-before.txt
./gradlew :module-calculator:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-calculator-before.txt
./gradlew :module-synchronizer:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-synchronizer-before.txt
./gradlew :module-cleanup:dependencies --configuration runtimeClasspath > /tmp/runtime-closure-cleanup-before.txt
```

### Runtime classpath report hashes

| Worker | SHA-256 | Baseline `module-infra` result |
| --- | --- | --- |
| external-api | `2dac675605113c48bc76fe67bc2dcec1b6ac90cc48f0a73b12f29eca6eb328b4` | direct `project :module-infra` |
| calculator | `342acec013fc6c704323769c9d474177d90a409953a7c6b4bd2e95fe1284103e` | absent |
| synchronizer | `e0f772f04d22501bcc3cab1c4cfce33d59771d4bd7ccce195a037b3888d85c27` | direct `project :module-infra` |
| cleanup | `3075c0713c8f19aec12bfa45f82e6de400ddd829debd6d8585fdc1e160ac1c26` | direct `project :module-infra` |

### Residual production locations

```text
module-cleanup/src/main/resources/application.yml:34:    maple.expectation.infrastructure: INFO
module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt:3:import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt:20:        "maple.expectation.infrastructure.executor",
module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt:29:    maple.expectation.infrastructure.config.CoreExecutorConfig::class,
module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt:30:    maple.expectation.infrastructure.config.VtExecutorConfig::class,
module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt:34:    ManagedLifecycleCoordinator::class,
module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt:12:import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt:42:) : ManagedLifecycle {
module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt:3:import maple.expectation.infrastructure.executor.LogicExecutor
module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt:4:import maple.expectation.infrastructure.executor.TaskContext
module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt:24:    private val executor: LogicExecutor,
module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt:35:            TaskContext.of("OrphanTempFileCleanup", "BootScan"),
module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt:3:import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt:16:        "maple.expectation.infrastructure.executor",
module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt:21:    maple.expectation.infrastructure.config.CoreExecutorConfig::class,
module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt:22:    maple.expectation.infrastructure.config.VtExecutorConfig::class,
module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt:25:    ManagedLifecycleCoordinator::class,
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:4:import maple.expectation.infrastructure.executor.LogicExecutor
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:5:import maple.expectation.infrastructure.executor.TaskContext
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:15:    private val executor: LogicExecutor,
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:22:        // Issue #1129: CPU offload — filter + groupBy delegated to LogicExecutor's
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:30:            TaskContext.of("Synchronizer", "UpdateEquipmentRanking:filter"),
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:39:            TaskContext.of("Synchronizer", "UpdateEquipmentRanking:groupBy"),
module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:47:            TaskContext.of("Synchronizer", "UpdateEquipmentRanking:sum"),
```

The lexical baseline intentionally includes the cleanup logging category because the final source gate scans all files under `src/main`.

### Effective bean and caller table

| Bean/resource | Effective implementation before closure | Observed caller after prerequisites | Required result |
| --- | --- | --- | --- |
| `authCharacterFetchExecutor` | local platform `ThreadPoolTaskExecutor`, core 2/max 4/queue 100, prefix `auth-character-fetch-` | `AuthCharacterFetchHandler` still qualified the bean at baseline; Task 2 repairs this prerequisite drift | remove caller, then delete bean/config |
| `externalApiSchedulerExecutor` | conditional infra virtual-thread-per-task bean with no explicit thread prefix | no production caller | do not recreate |
| scheduler internal executor | inline virtual-thread-per-task with no explicit thread prefix | `ExternalApiScheduler` phase execution | remain scheduler-owned |
| `internalApiExecutor` | conditional infra virtual-thread-per-task with no explicit thread prefix | `InternalApiController` | local external-api VT bean named `external-internal-` |
| `urgentCharacterRequestExecutor` | conditional infra virtual-thread-per-task with no explicit thread prefix | `UrgentCharacterRequestConsumer` | local external-api VT bean named `external-urgent-` |
| `loopExecutor` | local configurable `ThreadPoolTaskExecutor` | `PhaseLoopController`, `OrphanTempFileCleanupHook` | unchanged |
| `kafkaResultChunkExecutor` | conditional infra virtual-thread-per-task with no explicit thread prefix | `KafkaResultChunkConsumer` | local synchronizer VT bean named `sync-result-chunk-` |
| `basicSnapshotChunkExecutor` | conditional infra virtual-thread-per-task with no explicit thread prefix | `BasicChunkIngestionService` | local synchronizer VT bean named `sync-basic-chunk-` |
| `defaultAsyncExecutor` for OCID | infra platform `ThreadPoolTaskExecutor`, effective defaults core 8/max 16/queue 200, prefix `async-` | `OcidLookupRunConsumer` | local `synchronizerOcidLookupExecutor`, 8/16/200 |
| scheduler lifecycle | infra coordinator at Spring phase `Int.MAX_VALUE - 100`; scheduler advertises relative phase 100 | `ExternalApiScheduler` | local adapter at Spring phase `Int.MAX_VALUE - 100` |

Task 1 used only static source and resolved Gradle configuration evidence under the approved speed ceiling. It did not start Spring contexts or submit runtime probe tasks, so thread prefixes/types above are configuration facts rather than runtime observations.

## Tasks 2-4: Local runtime owners

### Executor and lifecycle evidence

| Runtime resource | Final owner and semantics | Focused test evidence |
| --- | --- | --- |
| `internalApiExecutor` | external-api; distinct virtual-thread-per-task executor, prefix `external-internal-` | submitted task observed `Thread.isVirtual == true` and the prefix; bean is distinct from urgent |
| `urgentCharacterRequestExecutor` | external-api; distinct virtual-thread-per-task executor, prefix `external-urgent-` | submitted task observed `Thread.isVirtual == true` and the prefix; dead auth/scheduler executor beans are absent |
| external owned-executor shutdown | external-api configuration; graceful wait, then `shutdownNow()` | context close waited for active work and terminated both owners; forced internal shutdown interrupted work and incremented `etl.executor.forced.shutdown{module="external-api",executor="internal"}` to `1.0`, while the unforced urgent series was absent |
| external scheduler lifecycle | external-api `SmartLifecycle`, phase `Int.MAX_VALUE - 100`; work starts on the first readiness event only | two readiness events caused one start; duplicate stop calls shared one `external-api-scheduler-stop` virtual thread and completed both callbacks; a stop failure still completed its callback once |
| external scheduler executor | scheduler-owned virtual-thread-per-task executor; bounded `stopAndAwait` | lifecycle tests prove one shared stop call and callback completion on success/failure; the metrics slice asserts `external_api_scheduler_forced_shutdown_total` and permits only closed start/stop lifecycle failure operations; direct executor termination was not live-probed |
| orphan boot cleanup | external-api hook; explicit `AsyncTaskExecutor` future, cancellable timeout, interrupt-aware scan | success, delete failure, scan failure, timeout/cancel, and submission failure all map to closed metrics outcomes without failing startup |
| `kafkaResultChunkExecutor` | synchronizer; distinct virtual-thread-per-task executor, prefix `sync-result-chunk-` | submitted task observed `Thread.isVirtual == true` and the prefix |
| `basicSnapshotChunkExecutor` | synchronizer; distinct virtual-thread-per-task executor, prefix `sync-basic-chunk-` | submitted task observed `Thread.isVirtual == true` and the prefix |
| synchronizer VT shutdown | synchronizer configuration; graceful wait, then `shutdownNow()` | context close terminated both owners; forced result shutdown interrupted work and incremented `etl.executor.forced.shutdown{module="synchronizer",executor="result"}` to `1.0`, while the unforced basic series was absent |
| `synchronizerOcidLookupExecutor` | synchronizer platform `ThreadPoolTaskExecutor`; core 8, max 16, queue 200, prefix `async-`, core timeout, 30-second keepalive/drain, abort rejection | configuration and termination asserted; caller MDC propagation and prior worker MDC restoration both passed; legacy `defaultAsyncExecutor` bean is absent |
| ranking projection | synchronizer caller thread; synchronous best-effort filter/group/Redis boundaries | success preserves Redis key/member/score bytes and top-10 trim; filter/group failures skip Redis and Redis failure skips unsafe trim; all failures increment their closed stage outcome |

OCID parse and ingest failures are returned as retryable delivery outcomes with the original unwrapped cause. No live executable was booted, so the thread observations above come from focused Spring/configuration tests that submit real tasks, not from a production-process probe.

## Task 5: Active graph closure

The new `verifyActiveEtlInfraIsolation` gate was first run before removing the direct worker edges. Its expected RED named exactly these resolved violations:

```text
:module-cleanup:runtimeClasspath:project :module-infra
:module-external-api:runtimeClasspath:project :module-infra
:module-synchronizer:runtimeClasspath:project :module-infra
```

Calculator was already isolated by the valuation-kernel prerequisite. After removing the other three direct edges, the gate passed. These separate guards also exited `0` with no matches:

```bash
! rg -n 'maple\.expectation\.infrastructure' \
  module-external-api/src/main module-calculator/src/main module-synchronizer/src/main module-cleanup/src/main
! rg -n 'module-infra' \
  module-external-api/build.gradle module-calculator/build.gradle module-synchronizer/build.gradle module-cleanup/build.gradle
! rg -n 'project :module-infra' /tmp/runtime-closure-*-after.txt
```

The app/web compatibility compile remained green. The prescribed infra compatibility slice passed 12/12. Removing the worker edge exposed one narrow test-fixture prerequisite: `StorageConfigTest` now imports `JacksonAutoConfiguration` explicitly so `cleanupInboxStore` receives its serializer without relying on incidental worker classpath wiring. No app/web-only production infrastructure was moved.

### Final runtime classpaths

| Worker | Before SHA-256 | After SHA-256 | `diff -u` summary | Final `module-infra` |
| --- | --- | --- | --- | --- |
| external-api | `2dac675605113c48bc76fe67bc2dcec1b6ac90cc48f0a73b12f29eca6eb328b4` | `c0a1f283c865edd4fe5f7131b32890e8c5ede8f0c3f7108a507ce3ce27e80b00` | 366 insertions, 637 deletions | absent |
| calculator | `342acec013fc6c704323769c9d474177d90a409953a7c6b4bd2e95fe1284103e` | `dc8f8d3558fab75edda56f48cc4910cf5770586a05406e768e50aa48b983061a` | 1 insertion, 1 deletion; dependency tree unchanged, only Gradle elapsed text changed | absent |
| synchronizer | `e0f772f04d22501bcc3cab1c4cfce33d59771d4bd7ccce195a037b3888d85c27` | `d91f598ab4762983d1b668ad066340286dece5d17328f2a749f7f7d2691c9dcf` | 351 insertions, 647 deletions | absent |
| cleanup | `3075c0713c8f19aec12bfa45f82e6de400ddd829debd6d8585fdc1e160ac1c26` | `31bdd1f99acbf5866679e4d9561d2beb387b8e3d5d0f3340d0b2eb607c555ab4` | 300 insertions, 649 deletions | absent |

The large textual diffs are Gradle tree re-indentation plus removal of the `module-infra` subtree after its top-level edge disappeared. The raw reports remain at `/tmp/runtime-closure-{external,calculator,synchronizer,cleanup}-{before,after}.txt` for this execution environment.

## Task 6: Focused regression and packaging

### Focused test result

| Module/slice | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| external executor, scheduler/lifecycle, metrics, orphan cleanup | 42 | 0 | 0 | 0 |
| synchronizer executor, subscriptions, delivery template, ranking writer | 20 | 0 | 0 | 0 |
| calculator configuration/resource parity | 2 | 0 | 0 | 0 |
| cleanup durable inbox | 5 | 0 | 0 | 0 |
| artifact upload/MinIO resource lifecycle | 9 | 0 | 0 | 0 |
| core valuation kernel | 6 | 0 | 0 | 0 |
| infra legacy/core compatibility | 2 | 0 | 0 | 0 |
| **Total** | **86** | **0** | **0** | **0** |

The artifact forced-shutdown slice additionally verifies its existing static-tag counter and interruption/close-once behavior. Calculation parity covers both the direct core kernel and legacy infra compatibility path. Prior artifact and valuation measurements remain in the [pipeline artifact extraction evidence](2026-07-19-pipeline-artifact-extraction-evidence.md) and [valuation kernel extraction evidence](2026-07-19-valuation-kernel-extraction-evidence.md); this closure run did not execute or extrapolate a throughput workload.

### Ordered compile and packaging result

One ordered command built all four executable JARs and therefore compiled their affected production graphs:

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar \
  :module-synchronizer:bootJar :module-cleanup:bootJar
```

Result: exit `0`, `BUILD SUCCESSFUL in 10s`, 25 actionable tasks (5 executed, 20 up-to-date).

| Worker | Final boot JAR bytes | Before bytes | Delta |
| --- | ---: | ---: | ---: |
| external-api | 91,864,498 | not captured | not available |
| calculator | 90,324,949 | not captured | not available |
| synchronizer | 123,623,827 | not captured | not available |
| cleanup | 84,046,113 | not captured | not available |

Task 1's boot JAR baseline was intentionally skipped under the speed ceiling, so no before value or size-improvement claim is fabricated. The same `stat` command also reported plain JARs of 403,459; 1,692,884; 221,551; and 46,775 bytes respectively.

## Verification log

| Task | Command/evidence | Result |
| --- | --- | --- |
| 1 | prerequisite `test -f` checks | exit `0` |
| 1 | four `runtimeClasspath` dependency reports | generated; hashes recorded above |
| 2 | external executor/controller/urgent focused slices | 37/37 passed before Task 2 commit |
| 3 | external scheduler/lifecycle/orphan focused slices | 39/39 passed before Task 3 commit |
| 4 | synchronizer executor/subscription/chunk/ranking focused slices | 20/20 passed before Task 4 commit |
| 5 | `./gradlew verifyActiveEtlInfraIsolation` | expected RED on three workers, then exit `0` after graph closure |
| 5 | source/direct-build/resolved-runtime `rg` guards | exit `0`; no matches |
| 5 | app/web `compileKotlin` + `compileJava --continue` | exit `0` |
| 5 | infra `StorageConfigTest`, `CoreLegacyValuationParityTest`, `NexonCompatibilityAdapterTest` | 12/12 passed |
| 5 | all four workers `compileKotlin` + `compileJava --continue` | exit `0`, zero compile errors |
| 6 | seven prescribed focused test commands | exit `0`; 86/86 passed, zero skipped |
| 6 | ordered four-worker `bootJar` command | exit `0`; exact final sizes recorded above |
| 6 | final `verifyActiveEtlInfraIsolation` and lexical/dependency guards | exit `0`; no matches |
| 6 | `git diff --check` | exit `0`; silent |

## Intentional skips

- Runtime bean probes and direct executable boot were not run. Consequently there are no live health responses, startup seconds, shutdown seconds, application-log scans, or live-process thread samples.
- Docker Compose services were not started. No database/object state was reset or deleted, and no Testcontainers test was run.
- Load and performance tests were not run. No throughput, latency, allocation, or no-regression performance number is claimed.
- The Task 1 boot JAR baseline was not captured, so final JAR deltas are unavailable.
- Broad module suites, full-repository tests, and root `check` were intentionally not run. The final verification used only the named focused slices, one ordered compile/packaging pass, `verifyActiveEtlInfraIsolation`, lexical/resolved dependency guards, and `git diff --check`.
- These deviations follow the explicit execution ceiling for this closure run; they are recorded rather than silently treated as passing runtime evidence.
