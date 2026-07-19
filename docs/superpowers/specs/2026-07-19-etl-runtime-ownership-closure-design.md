# ETL Runtime Ownership Closure

- **Status**: Approved
- **Priority**: P1
- **Date**: 2026-07-19
- **Program**: [ETL module-infra Deepening Program](2026-07-19-etl-infra-deepening-program-design.md)
- **Reason added**: grill-me found residual imports that block the direct-dependency-zero acceptance criterion

---

## 1. Scope

storage, messaging, calculation, Nexon seams를 옮긴 뒤에도 활성 ETL에 남는 `LogicExecutor`, `TaskContext`, `CoreExecutorConfig`, `VtExecutorConfig`, `ManagedLifecycle`/`ManagedLifecycleCoordinator` 의존을 제거한다.

새 공통 runtime module을 만들지 않는다. 각 worker가 자신의 named executor, rejection/backpressure, shutdown, lifecycle을 소유하고 error classification은 해당 workload 또는 Kafka delivery boundary에서 명시한다.

## 2. Non-goals

- module-app/web/infra 내부 `LogicExecutor` 제거
- 모든 executor를 같은 구현이나 pool size로 통합
- thread model 또는 concurrency 수치 tuning
- virtual thread를 platform thread로 일괄 교체
- 새 generic task framework, annotation, lifecycle framework 도입

## 3. Evidence

현재 residual production imports는 다음과 같다.

| Module | Residual dependency |
| --- | --- |
| module-external-api | executor package scan, `CoreExecutorConfig`, `VtExecutorConfig`, `LogicExecutor`/`TaskContext` in orphan cleanup, `ManagedLifecycle`/coordinator |
| module-calculator | `CoreExecutorConfig` through engine wiring |
| module-synchronizer | executor package scan, `CoreExecutorConfig`, `VtExecutorConfig`, `LogicExecutor`/`TaskContext` in consumer/ranking, empty `ManagedLifecycleCoordinator` import |
| module-cleanup | none after artifact/messaging extraction |

`VtExecutorConfig` itself contains bean names for unrelated executable modules. 실제 Spring wiring에서는 external-api의 로컬 `AuthExecutorConfig`가 conditional infra bean보다 우선하므로 `authCharacterFetchExecutor`는 virtual-thread executor가 아니라 core 2/max 4/queue 100의 platform `ThreadPoolTaskExecutor`다. `externalApiSchedulerExecutor`는 주입점이 없고 scheduler가 내부 virtual-thread-per-task executor를 직접 소유한다. `ManagedLifecycleCoordinator` in synchronizer has no synchronizer `ManagedLifecycle` implementation and therefore coordinates an empty list.

## 4. Decision

### 4.1 External API ownership

`module-external-api/config/ExternalApiExecutorConfiguration` owns only the active executor injection points used in that application:

- `internalApiExecutor`: virtual-thread-per-task, local five-second graceful shutdown
- `urgentCharacterRequestExecutor`: virtual-thread-per-task, local five-second graceful shutdown

Kafka delivery migration removes the old auth consumer's executor injection, so the already-local `AuthExecutorConfig` and its `authCharacterFetchExecutor` bean are deleted once the last caller is gone. If that caller still exists at the runtime-closure checkpoint, the migration stops rather than silently changing the effective core 2/max 4/queue 100 platform-thread behavior. The unused `externalApiSchedulerExecutor` bean is not recreated; `ExternalApiScheduler` remains the sole owner of its internal virtual-thread-per-task executor.

The initial implementation preserves active thread semantics and existing active bean names. It tracks every created `ExecutorService` and performs graceful shutdown with the existing five-second wait before `shutdownNow`. Pool/concurrency tuning is outside this migration.

`OrphanTempFileCleanupHook` replaces `LogicExecutor.executeVoid` with explicit `runCatching`, structured logging, and the same best-effort startup behavior. Cleanup failure must not prevent boot unless the current behavior already does.

`ExternalApiScheduler` no longer implements the infra `ManagedLifecycle`. A local `ExternalApiSchedulerLifecycle` implements Spring `SmartLifecycle` and calls the scheduler's explicit start/stop methods in the same phase/order. This keeps Spring-specific lifecycle wiring in the executable module and leaves scheduler logic independently testable.

### 4.2 Synchronizer ownership

`module-synchronizer/config/SynchronizerExecutorConfiguration` owns:

- `kafkaResultChunkExecutor`
- `basicSnapshotChunkExecutor`
- `synchronizerOcidLookupExecutor`

The result/basic executors preserve virtual-thread-per-task behavior, bean names, and five-second shutdown wait. The OCID subscription uses the new local named platform `ThreadPoolTaskExecutor` with the effective old `defaultAsyncExecutor` defaults: core 8/max 16/queue 200, `async-` thread prefix, 30-second graceful shutdown, and abort-on-rejection semantics. It is renamed at the injection point so the worker no longer imports the broad default executor configuration. The unused `ManagedLifecycleCoordinator` import is deleted rather than copied.

`ChunkConsumerTemplate` removes `LogicExecutor` wrappers. Its explicit control flow is:

1. insert/find/claim execution state
2. acquire/release permit in one visible `try/finally` boundary
3. run durable workload
4. await required outbound event
5. persist success or classified retryable/terminal failure
6. return `DeliveryOutcome`

Exception translation is not performed by a generic executor. `ChunkExecutionStateMachine` and messaging delivery classifier receive the original cause.

`EquipmentRankingRedisWriter` keeps its current best-effort semantics with local `runCatching` and metric/log callback. `BasicChunkIngestionService` and `KafkaResultChunkConsumer` use workload-specific context/metrics instead of `TaskContext`.

### 4.3 Calculator ownership

The valuation-kernel migration removes `CoreExecutorConfig` from `CalculatorEngineConfiguration`. Calculator already owns its coroutine dispatchers and cache executors; it does not gain a replacement generic executor.

### 4.4 Artifact runtime ownership

`module-pipeline-artifact` owns the executor used for LocalFS asynchronous upload/stream drain and the lifecycle of S3 sync/async clients, transfer manager, and stream reader executor. Bean names needed by compatibility wiring remain available through the artifact auto-configuration.

No unqualified generic `Executor` injection is allowed in storage configuration. The upload executor has an explicit qualifier and bounded ownership/shutdown.

### 4.5 Error and observability rule

Removing `LogicExecutor` must not remove failure visibility.

- every best-effort branch has a counter and structured warning
- every retryable/terminal branch returns the typed delivery result
- original causes are preserved; secret-bearing messages are sanitized at their owning boundary
- task metric tags are static operation names owned by the module
- dynamic run/chunk/key values stay in logs, not metric tags

## 5. Migration

1. Record current bean names, thread type/name, startup, shutdown order, and exception behavior.
2. Add external-api executor configuration and switch bean providers.
3. Add local external scheduler lifecycle adapter and remove infra lifecycle imports.
4. Remove external orphan cleanup executor wrapper.
5. Add synchronizer executor configuration and remove the empty lifecycle coordinator.
6. Rewrite synchronizer consumer error flow together with Kafka delivery outcome.
7. Replace ranking writer wrapper with explicit best-effort control.
8. Remove calculator generic executor import during valuation wiring migration.
9. Give artifact upload resources explicit ownership and shutdown.
10. Remove executor package scanning and `project(':module-infra')` from active modules.
11. Add source/Gradle dependency guards.

Changes that alter exception outcome are made in the owning Kafka/calculation spec task, not in a mechanical import-removal commit.

## 6. Tests

- active executor bean-name compatibility and preserved per-bean thread semantics
- graceful shutdown waits then forces only unfinished owned tasks
- external scheduler start/stop order and callback completion
- orphan cleanup best-effort failure metric/log
- synchronizer permit release on every success/failure/cancellation branch
- original exception reaches state/delivery classifier
- Redis ranking write remains best effort
- artifact executor/S3 client/transfer-manager shutdown
- application context smoke tests for all four executables without module-infra
- architecture test forbidding `maple.expectation.infrastructure..` imports in active modules
- Gradle dependency assertion forbidding direct/transitive module-infra on active runtime classpaths

The transitive check allows module-infra only in test fixtures that explicitly validate legacy compatibility; production runtime classpaths may not contain it.

## 7. Acceptance Criteria

- active ETL production source contains no `maple.expectation.infrastructure.*` import or fully-qualified reference.
- active ETL Gradle files contain no direct `project(':module-infra')`.
- active ETL production runtime classpaths do not include module-infra.
- existing active named executor injection points resolve with the same bean names; the OCID injection is deliberately renamed to `synchronizerOcidLookupExecutor` while preserving effective pool semantics.
- every created executor/client/transfer manager has one owner and deterministic shutdown.
- synchronizer no longer hides original failures behind `LogicExecutor` translation.
- external scheduler lifecycle behavior and shutdown completion remain equivalent.
- no generic worker-runtime module or cross-worker executor configuration is introduced.

## 8. Rollback

Each module's runtime ownership switch is a separate commit. Reverting one switch restores its old imports without changing Kafka, artifact, calculation, or Nexon wire contracts. The final Gradle dependency removal occurs only after that module's application-context and runtime-classpath checks pass.

## 9. ADR Alignment

- ADR-050: executor extraction remains scoped; active worker ownership removes only the coupling required for this program.
- ADR-353: executable modules depend on narrow libraries/core, not module-infra.
- ADR-722: module-infra compatibility code retains its infrastructure naming policy; new worker config lives under its owner package.
