# ETL Runtime Ownership Closure Evidence

- Baseline commit: `edfeedaf90e380c5df30cbeaa148813af76a900b`
- Evidence date: 2026-07-20
- Scope: `module-external-api`, `module-calculator`, `module-synchronizer`, and `module-cleanup`
- Verification ceiling: focused tests and static/resolved dependency evidence only; no database reset, Testcontainers, Docker/runtime boot, load test, or performance run.

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

## Verification log

| Task | Command/evidence | Result |
| --- | --- | --- |
| 1 | prerequisite `test -f` checks | exit `0` |
| 1 | four `runtimeClasspath` dependency reports | generated; hashes recorded above |

## Intentional skips

- Task 1 runtime bean/thread probes, boot JAR size baseline, Docker services, four-service boot, runtime health/shutdown timings, and performance/load measurements were skipped under the approved verification ceiling.
- No throughput or runtime-health claim is made from static evidence.
