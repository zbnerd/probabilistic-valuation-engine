# CF Chain Blocking Audit — 2026-06-18

- Date: 2026-06-18
- Status: **Audit only. No code changes. PR not created.**
- Author: claude-code + explore subagents
- Branch at audit: `feature/issue-CF-CHAIN-blocking-fix` (no commits)

## Background

Original goal: drop sync return contract of `LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache` and migrate all callers to pure `CompletableFuture<T>` end-to-end.

This audit (not the original plan) was created **after** the original plan proved wrong on key structural assumptions. This file documents actual code state, real blocking sites, and the path forward for any future PR.

## Original plan files (retained for reference)

- `docs/superpowers/specs/2026-06-18-ext-api-blocking-fix-design.md` — design (aspirational, based on wrong assumptions)
- `docs/01_ADR/ADR-blocking-async-contract-cf-chain.md` — ADR (proposed, not accepted)
- `docs/superpowers/plans/2026-06-18-ext-api-blocking-fix.md` — implementation plan (also wrong; ~30% of tasks applicable)

**These 3 files are NOT a binding contract.** They document the brainstorm + plan exercise. Future PRs should write a fresh plan against actual code.

## Original plan errors (so future work avoids them)

| Plan assumed | Actual |
|---|---|
| `LogicExecutor` flat (7 sync + 7 async) | ISP composed: `LogicExecutor : BasicExecutor + SafeExecutor + ResilientExecutor` |
| `DefaultCheckedLogicExecutor` = `LogicExecutor` impl | Separate IO-boundary executor (`CheckedSupplier` input). Real `LogicExecutor` impl = `DefaultLogicExecutor` |
| `TaskContext.simple(...)` factory | Doesn't exist. Real factory: `TaskContext.of(component, operation)` or `TaskContext.of(component, operation, dynamicValue)` |
| `SingleFlight` needs `executeAsync` added | **Already exists** on `SingleFlightStrategy:50` interface + `PostgresSingleFlightStrategy:85` impl. Only `.join()` inside sync facade `:73-76` |
| PGMQ `MessageHandler.handle(): CF<AckResult>` | Doesn't exist. `process(): Boolean` (true=ACK, false=NACK). No `AckResult` class |
| `EquipmentFetchProvider` `.join()` removable | **Explicit ADR in class Javadoc (lines 16-44)**: `.join()` is intentional, kept for Spring `@Cacheable` sync return contract |
| `GlobalAdmissionControl` busy loop | V4 legacy only (V4 controllers). Not used by V5/ext-api. Plan T8 targets wrong component |
| InternalApiController return `CF<ResponseEntity<...>>` | All endpoints return sync `ResponseEntity<...>` |
| 5 active module caller sites | **8 sites** — missed 3 `runBlocking` in `EquipmentRankingRedisWriter.kt:26, 31, 35` + 1 in `OcidLookupRunConsumer.kt:38` |

## Real blocking sites (audited 2026-06-18)

### module-infra

| File:Line | Site | Severity | Notes |
|---|---|---|---|
| `lock/PostgresAdvisoryLockStrategy.kt:72, 117, 143` | `task.get()` inside `lockTransactionTemplate.execute` | HIGH | 3 sites. No async API on `LockStrategy` interface |
| `lock/PostgresLockStrategy.kt:97, 113, 120, 140, 145, 318, 338, 342, 351, 352, 356, 359` | `acquiredLocks.get()` / `lockOrder.get()` | LOW | All `ThreadLocal` reads. Non-blocking. False positive |
| `lock/OrderedLockExecutor.kt:143, 181` | `task.get()` after lock acquire | HIGH | 2 sites |
| `concurrency/PostgresSingleFlightStrategy.kt:75-76` | `.orTimeout().join()` inside sync `execute` facade | MEDIUM | `executeAsync` already exists; only the sync facade blocks |
| `concurrency/SingleFlightExecutor.kt` | none | n/a | Already CF-only |
| `cache/TieredCache.kt:126` | `buffer.submit(key).orTimeout(5, SECONDS).join()` | LOW | Single site, bounded by timeout, internal L2 batcher |
| `worker/ExternalApiWorker.kt:111` | `pipelineAsync(payload).join()` | HIGH | |
| `worker/ExternalApiWorker.kt:306-324` | `runBlocking(Dispatchers.Default)` | MEDIUM | CPU-bound calc |
| `worker/CalculationWorker.kt:83-91` | `.handle().join()` | HIGH | |
| `worker/OcidResolveWorker.kt:64-73` | `.join()` | HIGH | Topic subscriber not `PgmqWorker` |
| `worker/ResultReadyProjectionWorker.kt:81-90` | `.join()` × 2 | HIGH | Scheduled poller, not `PgmqWorker` |
| `worker/ResultReadyProjectionWorker.kt:123` | `runBlocking(Dispatchers.Default)` | MEDIUM | |
| `pgmq/PgmqWorker.kt:380-394` | `runBlocking(Dispatchers.Default)` + `async/awaitAll` | MEDIUM | Sequential batch coroutine path |
| `provider/EquipmentFetchProvider.kt:72` | `nexonApiClient.getItemDataByOcid(ocid).orTimeout(10, SECONDS).join()` | DOC-EXEMPT | **Class Javadoc documents `.join()` is intentional** (lines 16-44, Issue #118) |
| `security/filter/JwtAuthenticationFilter.kt:80-99` | `executor.executeWithFallback { ... }` — sync execution | LOW | Uses `LogicExecutor` for sync. Not blocking but synchronous |
| `admission/GlobalAdmissionControl.kt:238` | `while (running.get() && !Thread.interrupted)` | FALSE | V4 legacy. Not used by V5/ext-api |
| `java/.../service/starforce/StarforceLookupAdapter.java` | none on async path | n/a | Uses `LogicExecutor` for sync |
| `java/.../service/cube/component/CubeComputeBuffer.java` | none | n/a | `ConcurrentHashMap.get` not blocking |

### module-external-api

| File:Line | Site | Severity | Notes |
|---|---|---|---|
| `runstatus/InternalApiController.kt:83, 123` | `executor.submit { scheduler.*Async().join() }` | MEDIUM | Request thread returns 202 immediately. But internal `internalApiExecutor` worker blocks for whole phase |
| `scheduler/ExternalApiScheduler.kt:188` | `runBlocking { ocidLookupPhase.execute(executor, runKey, runId) }` | HIGH | Bridges `suspend` into CF chain. VT carrier is fine per async-patterns.md |
| `scheduler/phase/OcidLookupPhase.kt:147` | `writerJob.join()` | FALSE | `kotlinx.coroutines.Job.join()` IS a suspend function. Suspends in coroutine context, not blocks thread |
| `scheduler/phase/OcidLookupPhase.kt:148` | `putJob.await()` | FALSE | Coroutine `Deferred.await()` — proper suspend |
| `snapshot/ChunkFileManager.kt:132` | `all.get(600_000L, TimeUnit.MILLISECONDS)` | HIGH | 10-min hard blocking on writer thread |
| `snapshot/SnapshotFailedRecordWriter.kt` | none on async path | n/a | Sync I/O on single writer thread (ChunkFileManager thread-affinity L24-25) |
| `auth/AuthCharacterFetchConsumer.kt:51` | `characterListOpt.get()` | NULL-SAFETY | Violates `kotlin-null-safety.md` rule `.isPresent()+.get()`. Not blocking |
| `urgent/UrgentCharacterRequestConsumer.kt:53` | `semaphore.tryAcquire()` | OK | Non-blocking variant. Good |
| `urgent/UrgentCharacterRequestConsumer.kt:66` | `semaphore.release()` inside `whenComplete` only | MEDIUM | Permit leaks on CF cancel external. Need `tryAcquireAsync` + `finally` release or use `BackpressureLimiter` |
| `build.gradle:55` | `bootJar { enabled = true }` | TENSION | Per `workflow-rules.md` ext-api IS a boot service. Per `build-conventions.md` only `module-app` should enable. Both rules exist; depends on which you read |

### Active module callers (5 files, 8 sites)

| File:Line | Site | Severity | Notes |
|---|---|---|---|
| `module-synchronizer/.../consumer/ChunkConsumerTemplate.kt:99` | `request.executor.execute { logicExecutor.executeWithFinally(...) }` | LOW | Fire-and-forget dispatch |
| `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt:35` | `executor.execute { runCatching { runBlocking(...) } }` | MEDIUM | |
| `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt:38` | `runBlocking(Dispatchers.Default) { objectMapper.readValue(...) }` | MEDIUM | Sync bridge to coroutine |
| `module-synchronizer/.../ranking/EquipmentRankingRedisWriter.kt:26` | `runBlocking(Dispatchers.Default) { documents.filter { ... } }` | MEDIUM | |
| `module-synchronizer/.../ranking/EquipmentRankingRedisWriter.kt:31` | `runBlocking(Dispatchers.Default) { rankable.groupBy { ... } }` | MEDIUM | |
| `module-synchronizer/.../ranking/EquipmentRankingRedisWriter.kt:35` | `executor.executeOrDefault(...)` returning sync `Int` | MEDIUM | |
| `module-calculator/.../processor/CalculationCache.kt:75` | `return cache.get(key) { ... }` (Caffeine sync) | LOW | Sync return type. `cache.getAsync` doesn't exist on this Caffeine wrapper |
| `module-external-api/.../auth/AuthCharacterFetchConsumer.kt:42` | `executor.execute { runCatching { ... } }` | LOW | Fire-and-forget |

### Out of scope (audit-confirmed)

- `module-core`: 0 blocking primitive sites
- `module-common`: 0 blocking primitive sites
- `module-app` legacy (20+ Java files): 0 sites audited in this scope, but uses sync `LogicExecutor` extensively — follow-up PR territory

## Recommended path forward

### Sub-PR 1: Lock `*Async` API (HIGH impact, clean)

Add `executeWithLockAsync(key, supplier): CF<T>`, `executeWithLeaderElectionAsync(key, ...): CF<T>`, `executeWithOrderedLocksAsync(...): CF<T>` to `LockStrategy` interface. Implement in `PostgresAdvisoryLockStrategy` + `OrderedLockExecutor`. `@Deprecated` sync methods. module-app legacy continues with warnings.

Eliminates: 5 HIGH sites.

### Sub-PR 2: PGMQ Worker CF path (HIGH impact, larger)

Refactor `process(msg: PgmqMessage<T>): Boolean` to return a richer `ProcessOutcome` sealed class (Ack / Nack(retryable) / Nack(deadletter) / Nack(visibilityReset)). The `Boolean` return is the actual blocking constraint (workers call `.join()` internally to coerce CF chain to Boolean).

Alternatively: introduce `MessageHandler<T>` interface with `CF<ProcessOutcome>` return. Add to `PgmqWorker` framework. Migrate workers one by one.

Eliminates: 8 HIGH + MEDIUM sites.

### Sub-PR 3: ext-api Controller + Scheduler (MEDIUM impact)

Convert `InternalApiController` endpoints to fire-and-forget pattern (already partially there). Remove `runBlocking` in `ExternalApiScheduler.runOcidPhase` by converting to pure CF via `coroutineScope { async { ... }.await() }`.

Eliminates: 2 MEDIUM + 1 HIGH sites.

### Sub-PR 4: ChunkFileManager close path (HIGH impact, contained)

Convert `awaitAllUploads` to `closeAsync(): CF<Void>`. Use `thenRun` for manifest write. `ChunkedSnapshotSink.close()` chains via `thenCompose`.

Eliminates: 1 HIGH site.

### Sub-PR 5: BackpressureLimiter migration + runBlocking cleanup (MEDIUM impact)

- `UrgentCharacterRequestConsumer`: `Semaphore.tryAcquire` + `release` in `whenComplete` → `BackpressureLimiter.tryAcquireAsync` + `releaseAsync` in `whenComplete` (with `.whenComplete` cancel-safe handling).
- `OcidLookupRunConsumer.kt:38` + `EquipmentRankingRedisWriter.kt:26, 31`: convert `runBlocking` to inline async or move to dedicated async endpoint.

Eliminates: 1 MEDIUM + 4 MEDIUM sites.

### Out of scope (DOC-EXEMPT)

- `EquipmentFetchProvider.kt:72` `.join()`: KEEP. Class Javadoc documents intentional.
- `OcidLookupPhase.kt:147-148` `Job.join()` + `await()`: KEEP. Both are suspend functions, not blocking primitives. Add code comment to make this explicit (was task T11 in original plan).

## Audit artifacts

- 6 explore subagents dispatched (1 stopped early, 5 completed)
- 4 production files read directly (DefaultLogicExecutor, TaskContext, CheckedLogicExecutor, JwtAuthenticationFilter, GlobalAdmissionControl, EquipmentFetchProvider, StarforceLookupAdapter, CubeComputeBuffer)
- 0 production code changes
- 0 commits made
- Feature branch `feature/issue-CF-CHAIN-blocking-fix` exists with 0 commits (clean state)

## Recommendation

Defer all 5 sub-PRs to individual issues. Each = small, reviewable, with green build per commit (using `@Deprecated` shim where applicable, mirroring grill-me Q1=A, Q6=B, Q9=A from original brainstorm).

Do NOT use the original 19-task plan as a basis — it assumes wrong code structure.
