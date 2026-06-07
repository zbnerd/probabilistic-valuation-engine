# Concurrency Adapter Package Design

- Date: 2026-06-05
- Owner: TBD
- Branch: TBD (worktree, post-#906/#1104/#1076/#896 merge)

---

## 1. Background / Problem

### Background

PR #1113-#1123 (one day, 8 merged) fixed recurring concurrency mishandling in `module-infra`:

| PR | Pattern |
|----|---------|
| 1113 | `CallerRunsPolicy` selected (overload propagates to caller thread) |
| 1114 | `@PreDestroy` lifecycle missing on 5 components |
| 1115 | `Thread.ofPlatform()` used in `ChunkedSnapshotSink` |
| 1116 | `keyVersions` map memory leak + `PgmqWorker` drain missing |
| 1117 | backpressure semaphore missing on `OcidLookup` / `SnapshotFetch` / `UrgentConsumer` |
| 1118 | blocking-in-async in Discord subscribe, TieredCache, SingleFlight, AuthClient |
| 1122 | semaphore leak, volatile, bounded buffer, init race |
| 1123 | `ForkJoinPool.commonPool()` used in 8 sites |

Each fix is a single PR. The pattern is the same: `Thread`/`Executor`/`Semaphore` is created ad-hoc at the call site, and the rules of use (lifecycle, policy, drain, fallback executor) are not encoded anywhere.

### Problem

Concurrency primitives are scattered across `module-infra/**` with no shared interface. The codebase has no locality for "how we use concurrency":

- New component authors pick a policy / executor / semaphore by intuition
- A reviewer cannot tell if `new Thread()` is acceptable (it is not)
- `@PreDestroy` is optional, so components leak buffers / threads on shutdown
- `ForkJoinPool.commonPool()` is the default in many CF chains — unknown to most callers

### Goal

Centralize the six recurring concurrency concerns behind single-purpose adapters in `module-infra/concurrency/`. Make misuse unrepresentable by routing all concurrency operations through the adapters.

---

## 2. Decision

> Introduce six single-purpose adapters in `module-infra/concurrency/`. Each adapter owns exactly one concern. All new code that needs the concern uses the adapter. Existing direct calls migrate one domain at a time.

```text
module-infra/concurrency/
├── LifecycleComponent.kt           (1) DisposableBean standard
├── BackpressureLimiter.kt          (2) request-throttling, fast-fail
├── BoundedSemaphore.kt             (3) N-concurrent execution, finally release
├── ExecutorSelector.kt             (4) executor whitelist, no FJP.commonPool
├── ThreadLauncher.kt               (5) no Thread.ofPlatform / new Thread
└── AsyncGuard.kt                   (6) async chain timeout / blocking detection
```

Each adapter is a Spring `@Component` (or `@Configuration` for factories) and is auto-discovered. The contract is enforced by:

1. Code rule: "no `new Thread()`, no `ForkJoinPool.commonPool()`, no direct `Semaphore` outside `concurrency/`"
2. Review checklist (added to PR template)
3. Adapter return types discourage raw primitive return

---

## 3. Adapters — Interface Contracts

### 3.1 LifecycleComponent

```kotlin
interface LifecycleComponent : DisposableBean {
    fun componentName(): String
    suspend fun drain()   // override default no-op
    fun shutdownTimeoutMs(): Long = 5_000
}
```

Default `destroy()` impl calls `drain()` then waits `shutdownTimeoutMs`. Replaces ad-hoc `@PreDestroy` methods. Used by PgmqWorker, ChunkedSnapshotSink, TieredCache, etc.

### 3.2 BackpressureLimiter

```kotlin
interface BackpressureLimiter {
    suspend fun <T> withPermit(timeoutMs: Long, block: suspend () -> T): T
    // throws BackpressureRejectedException on timeout
}
```

`tryAcquire(timeoutMs)` then `release` in `finally`. Distinct from `BoundedSemaphore` in that the intent is fast-fail under load, not long-running concurrency cap. Used by OcidLookup, SnapshotFetch, UrgentConsumer.

### 3.3 BoundedSemaphore

```kotlin
interface BoundedSemaphore {
    suspend fun <T> withPermit(block: suspend () -> T): T
    fun availablePermits(): Int
}
```

Wraps `Semaphore` with `acquire`/`release` in `finally` — no leak. Used for "max N concurrent in-flight X" semantics (chunk processing, item equipment fetches). Distinct from `BackpressureLimiter`: the wait is expected to be long, blocking is fine.

### 3.4 ExecutorSelector

```kotlin
interface ExecutorSelector {
    fun <T> submit(qualifier: ExecutorQualifier, block: () -> T): CompletableFuture<T>
    fun shutdownAll(phase: ShutdownPhase)
}
enum class ExecutorQualifier { CALCULATION, IO, SCHEDULER, CHUNK, BACKFILL }
enum class ShutdownPhase { CONSUMERS, PRODUCERS, INFRA }
```

Returns only executors registered via the adapter. Any code path that needs an `ExecutorService` calls `submit(qualifier, block)`. `ForkJoinPool.commonPool()` and direct `Executors.newXxx()` are disallowed at the call site.

### 3.5 ThreadLauncher

```kotlin
interface ThreadLauncher {
    fun launch(name: String, block: () -> Unit): Future<*>
}
```

Wraps an `ExecutorService` (its own dedicated one). No `Thread.ofPlatform()`, no `new Thread()`. Used by one-shot fire-and-forget tasks that today call `Thread.ofPlatform().start { }`.

### 3.6 AsyncGuard

```kotlin
interface AsyncGuard {
    fun <T> guard(name: String, timeoutMs: Long, chain: CompletableFuture<T>): CompletableFuture<T>
    // wraps the chain with .orTimeout + .whenComplete
}
```

AOP-style wrapper for `CompletableFuture` chains. Detects blocking-in-async (chain exceeds `timeoutMs`, or block detected by `StackTrace` heuristic). Emits `WARN` log + counter metric. Used in Discord subscribe, TieredCache, SingleFlight, AuthClient.

---

## 4. Placement

`module-infra/concurrency/` — flat package, six files + one internal `ExecutorRegistry`.

Spring wiring: a single `@Configuration` `ConcurrencyConfiguration` registers all six as beans. `ExecutorRegistry` holds the named `ExecutorService` instances (one per `ExecutorQualifier`).

No dependency on `module-core` — these are infrastructure adapters, not ports. (Could become a `module-executor` extraction later per ADR-050.)

---

## 5. Migration Plan

Per adapter × per domain (calculator / external-api / synchronizer / rest-controller). One PR = one adapter migration in one domain. Estimated 6 adapters × 4 domains = 24 PRs, but most are small.

Priority order (matches the 8-PR pattern):

1. **LifecycleComponent** — highest recurrence, simplest migration
2. **ExecutorSelector** — cuts FJP.commonPool foot-gun
3. **BoundedSemaphore** + **BackpressureLimiter** — both in PR #1122 cluster
4. **ThreadLauncher** — niche
5. **AsyncGuard** — last, requires baseline metric to know "what slow looks like"

Each migration PR is self-contained:

```
PR-X: "refactor(calculator): migrate X callsites to LifecycleComponent"
  - introduce adapter usage
  - delete the ad-hoc @PreDestroy / drain code
  - run :module-calculator:test
  - run bootRun + expectation API
```

---

## 6. Test Strategy

Unit tests only (per workflow-rules: no Testcontainers, no integration test).

- **fake executor**: in-memory `ExecutorService` for `ExecutorSelector` tests
- **virtual time**: `kotlinx-coroutines-test` `runTest` + `TestScope` for `LifecycleComponent.drain()`
- **deterministic semaphore**: tests construct `BoundedSemaphore` with `permits=2` and assert N=2 concurrent blocks, N+1 throws
- **AsyncGuard**: assert `WARN` log emitted when synthetic `CompletableFuture` chain exceeds timeout

Target coverage: ≥ 80% line, 100% of `finally`/`whenComplete` paths.

---

## 7. Trade-offs

### Sensitivity

* Volume of new concurrency code (any new fan-out / new worker)
* Number of modules (4 currently active) — migration scales linearly
* Lifecycle event ordering during shutdown (drain order matters)
* Virtual thread interaction (jdbc + sync DB, vs CF for MQ)

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Six small adapters | Single responsibility; test in isolation; review surface small | More boilerplate at call site; 6 beans to learn |
| `@Component` scan + code rule (vs ArchUnit) | Visible rule, no new test infra | Rule is enforced by reviewer, not by build |
| Per-adapter × per-domain migration | Small PRs, low risk | 24 PRs total, longer calendar time |
| Unit test only (vs load test) | Fast feedback, no infra | Concurrency bugs may not show until load test |

### Risk

* Migration PRs may break shutdown ordering if drain order changes — need load-test verification after each
* `AsyncGuard` baseline metric requires running with a known-good workload to know "what slow looks like" before introducing it
* `LifecycleComponent` default `shutdownTimeoutMs=5000` may be too short for some components (backfill, chunk pipeline) — must be overridable

### Non-Risk

* `BackpressureLimiter` and `BoundedSemaphore` overlap in API but differ in intent (fast-fail vs long-wait) — worth the duplication
* Replacing `@PreDestroy` with `LifecycleComponent.destroy()` does not change bean lifecycle semantics

---

## 8. Success Signal

Per the brainstorming decision: **30 days with zero concurrency-fix PRs after the migration is complete.**

Definition of "complete": all 6 adapters exist, all 4 active modules use the adapters exclusively for their respective concerns, and `module-infra/concurrency/` is the only place these patterns occur.

---

## 9. Open Questions

None at design level. Migration PRs will surface ordering questions (e.g. should `LifecycleComponent.drain()` be called in domain order or qualifier order).
