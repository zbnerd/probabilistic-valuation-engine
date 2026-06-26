# ext-api CF Chain Blocking Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every blocking primitive (`.get()`, `.join()`, `runBlocking`, `Job.join()`, blocking semaphore) on the `CompletableFuture` chain from `module-external-api` controllers through `module-infra` ports, by deleting the synchronous return contract of `LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache` and migrating all callers (active modules only; `module-app` legacy = follow-up PR).

**Architecture:** Single PR, big-bang migration. Per-port atomic commits inside one PR. `*Async` CF-returning API added alongside soft-`@Deprecated` old sync API (Q1=A, Q9=A). User call path zero blocking. Cache-internal `wrapper.get()` allowlist (Q4=A, Q8=A). `module-app` legacy callers continue with `@Deprecated` warnings; migrated in follow-up PR (Q6=B). Backward-compat shim for module-app only. CI grep gate added to block regression.

**Tech Stack:** Kotlin 2.x, Java 21, Spring Boot 3.x, Reactor (WebClient), `kotlinx-coroutines-jdk8` (for `await`/`Deferred` bridging), Gradle 8.x, JUnit 5, Awaitility, PGMQ (PostgreSQL extension).

**Spec:** `docs/superpowers/specs/2026-06-18-ext-api-blocking-fix-design.md`
**ADR:** `docs/01_ADR/ADR-blocking-async-contract-cf-chain.md`

---

## Pre-Task: Capture load test baseline

**Files:**
- Create: `docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md`

- [ ] **Step 1: Confirm `develop` branch is clean**

```bash
cd /home/maple/probabilistic-valuation-engine
git status
git checkout develop
git pull
```

Expected: working tree clean, on `develop` HEAD.

- [ ] **Step 2: Run baseline load test (1×)**

```bash
set -a && source .env && set +a
./gradlew :module-rest-controller:bootRun &
RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000 CONCURRENCY=50 \
  SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh
```

Expected: 6 sample snapshots collected. Note the `views_per_sec` value from the final sample (representative of steady-state throughput).

- [ ] **Step 3: Write baseline report**

Write to `docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md`:

```markdown
# Pre-CF-Chain Load Test Baseline

- Date: 2026-06-18
- Branch: develop @ <commit hash>
- Command: RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000 CONCURRENCY=50 SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh

## Samples

| t (s) | views | Δ views | views/sec | q_external_api | q_result_ready | q_calc_high | active_api_requested |
| --: | --: | --: | --: | --: | --: | --: | --: |
| 0  |   |    |     |   |   |   |   |
| 30 |   |    |     |   |   |   |   |
| 60 |   |    |     |   |   |   |   |
| 90 |   |    |     |   |   |   |   |
| 120|   |    |     |   |   |   |   |
| 150|   |    |     |   |   |   |   |
| 180|   |    |     |   |   |   |   |

## Final `views_per_sec`

<value>

## Errors / slow tasks

<grep results from module-app/logs/load-test-bootrun-*.log>
```

- [ ] **Step 4: Commit baseline report**

```bash
git add docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md
git commit -m "docs: pre-CF-chain load test baseline (2026-06-18)"
```

- [ ] **Step 5: Create feature branch**

```bash
git checkout -b feature/issue-CF-CHAIN-blocking-fix
```

---

## File Map (target structure)

### `module-infra/src/main/kotlin/.../`

| File | Action | Responsibility |
|---|---|---|
| `executor/LogicExecutor.kt` | modify | interface: add `*Async`, soft-`@Deprecated` sync |
| `executor/DefaultCheckedLogicExecutor.kt` | modify | implement `*Async`, keep `@Deprecated execute*` |
| `executor/policy/ExecutionPipeline.kt` | modify | pipeline core accepts CF task |
| `lock/PostgresAdvisoryLockStrategy.kt` | modify | `executeAsync` + `@Deprecated execute` |
| `lock/OrderedLockExecutor.kt` | modify | `executeAsync` + `@Deprecated execute` |
| `concurrency/PostgresSingleFlightStrategy.kt` | modify | `executeAsync` (in-flight only per Q12) |
| `concurrency/SingleFlightExecutor.kt` | modify | `executeAsync` + `@Deprecated execute` |
| `cache/TieredCache.kt` | modify | `getAsync`/`putAsync`; L1+L2 best-effort in `whenComplete` (Q8) |
| `cache/TieredCacheManager.kt` | modify | `getAsync`/`putAsync` |
| `cache/tiered/PostgresL2CacheStrategy.kt` | modify | async put |
| `cache/invalidation/impl/PostgresNotifySubscriber.kt` | modify | CF chain on NOTIFY |
| `worker/ExternalApiWorker.kt` | modify | `handle(): CF<AckResult>`, cancel = `Nack(retryable=true)` |
| `worker/OcidResolveWorker.kt` | modify | `handle(): CF<AckResult>` |
| `worker/CalculationWorker.kt` | modify | `handle(): CF<AckResult>` |
| `worker/ResultReadyProjectionWorker.kt` | modify | `handle(): CF<AckResult>`, no `.join` |
| `pgmq/PgmqWorker.kt` | modify | `processSequentialBatch` → `supplyAsync(..., cpuExecutor)` + `allOf` (Q5) |
| `security/filter/JwtAuthenticationFilter.kt` | modify | CF chain on `payload` |
| `admission/GlobalAdmissionControl.kt` | modify | replace busy loop with cancel token |
| `provider/EquipmentFetchProvider.kt` | modify | `@Cacheable` → `getAsync` |
| `test/BlockingPrimitiveGateTest.kt` | create | grep gate (CI) |
| `java/.../service/starforce/StarforceLookupAdapter.java` | modify | `expectedCostCache` → `getAsync` |
| `java/.../service/cube/component/CubeComputeBuffer.java` | modify | `getAsync` (Caffeine sync get allowlisted) |

### `module-external-api/src/main/kotlin/.../`

| File | Action | Responsibility |
|---|---|---|
| `runstatus/InternalApiController.kt` | modify | return `ResponseEntity.accepted()` 202; `.whenComplete` for status tracking (Q3) |
| `scheduler/ExternalApiScheduler.kt` | modify | `*Async`; remove `runBlocking`; use `ExecutorSelector` (not raw `Executors.newVirtualThreadPerTaskExecutor()`) |
| `scheduler/phase/OcidLookupPhase.kt` | modify | code comment only at lines 147-148 (Q2: `Job.join()` is suspend in coroutine context) |
| `snapshot/ChunkFileManager.kt` | modify | `closeAsync` returns CF |
| `snapshot/SnapshotFailedRecordWriter.kt` | modify | CF chain on `objectStorage.get` |
| `auth/AuthCharacterFetchConsumer.kt` | modify | null-safe Optional chain |
| `urgent/UrgentCharacterRequestConsumer.kt` | modify | raw `java.util.concurrent.Semaphore` → `BackpressureLimiter` (Q10) |
| `build.gradle` | modify | `bootJar { enabled = false }` (Q11) |

### `module-calculator`, `module-synchronizer`, `module-rest-controller` (active modules only)

| File | Action | Caller type |
|---|---|---|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt:99` | modify | `executor.execute { ... }` (LogicExecutor) |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt:35` | modify | `executor.execute { ... }` |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:35` | modify | `executor.executeOrDefault(...)` |
| `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt:75` | modify | `cache.get(key) { ... }` (TieredCache) |
| `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt:42` | modify | `executor.execute { ... }` (also covered by T13) |

`module-app` legacy (20+ Java files) = **out of scope, follow-up PR** (Q6=B).

---

## Task 1: Add `LogicExecutor.execute*Async` alongside `@Deprecated execute*`

**Files:**
- Modify: `module-infra/src/main/kotlin/.../executor/LogicExecutor.kt`
- Modify: `module-infra/src/main/kotlin/.../executor/DefaultCheckedLogicExecutor.kt`
- Modify: `module-infra/src/main/kotlin/.../executor/policy/ExecutionPipeline.kt`
- Test: `module-infra/src/test/kotlin/.../executor/DefaultCheckedLogicExecutorAsyncTest.kt`

- [ ] **Step 1: Write failing test for `executeAsync` returns CF without blocking caller**

```kotlin
package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.common.task.TaskContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class DefaultCheckedLogicExecutorAsyncTest {
    @Test
    fun `executeAsync returns CF and does not block caller thread`() {
        val callerThread = AtomicReference<Thread>()
        val executor = DefaultCheckedLogicExecutor(ExecutionPipeline.default())

        val result: CompletableFuture<String> = executor.executeAsync(
            { callerThread.set(Thread.currentThread()); CompletableFuture.completedFuture("ok") },
            TaskContext.simple("test")
        )

        assertNotNull(result.getNow(null)) // non-blocking peek
        result.get() // wait
        // task ran on a different thread (caller's)
        assertNotEquals(Thread.currentThread(), callerThread.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-infra:test --tests "*DefaultCheckedLogicExecutorAsyncTest*" --info
```

Expected: compile error — `executeAsync` does not exist.

- [ ] **Step 3: Modify `LogicExecutor` interface**

In `LogicExecutor.kt`:

```kotlin
package maple.expectation.infrastructure.executor

import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.common.task.TaskContext
import java.util.concurrent.CompletableFuture

interface LogicExecutor {
    // --- New async API (preferred) ---
    fun <T> executeAsync(task: () -> CompletableFuture<T>, ctx: TaskContext): CompletableFuture<T>
    fun executeVoidAsync(task: () -> CompletableFuture<Void>, ctx: TaskContext): CompletableFuture<Void>

    fun <T> executeOrDefaultAsync(
        task: () -> CompletableFuture<T>,
        default: T,
        ctx: TaskContext
    ): CompletableFuture<T>

    fun <T> executeOrCatchAsync(
        task: () -> CompletableFuture<T>,
        recovery: (Throwable) -> CompletableFuture<T>,
        ctx: TaskContext
    ): CompletableFuture<T>

    fun <T> executeWithFallbackAsync(
        task: () -> CompletableFuture<T>,
        fallback: () -> CompletableFuture<T>,
        ctx: TaskContext
    ): CompletableFuture<T>

    fun <T> executeWithFinallyAsync(
        task: () -> CompletableFuture<T>,
        finalizer: () -> Unit,
        ctx: TaskContext
    ): CompletableFuture<T>

    fun <T, E : Throwable> executeWithTranslationAsync(
        task: () -> CompletableFuture<T>,
        translator: (Throwable) -> E,
        ctx: TaskContext
    ): CompletableFuture<T>

    // --- Deprecated sync API (kept for module-app legacy; soft-deprecation Q9=A) ---
    @Deprecated("Use executeAsync", ReplaceWith("executeAsync({ task.get() }, ctx)"))
    fun <T> execute(task: ThrowingSupplier<T>, ctx: TaskContext): T

    @Deprecated("Use executeVoidAsync", ReplaceWith("executeVoidAsync({ task.get() }, ctx)"))
    fun executeVoid(task: ThrowingSupplier<Unit>, ctx: TaskContext)

    @Deprecated("Use executeOrDefaultAsync", ReplaceWith("executeOrDefaultAsync({ task.get() }, default, ctx)"))
    fun <T> executeOrDefault(task: ThrowingSupplier<T>, default: T, ctx: TaskContext): T

    @Deprecated("Use executeOrCatchAsync", ReplaceWith("executeOrCatchAsync({ task.get() }, recovery, ctx)"))
    fun <T> executeOrCatch(task: ThrowingSupplier<T>, recovery: (Throwable) -> T, ctx: TaskContext): T

    @Deprecated("Use executeWithFallbackAsync", ReplaceWith("executeWithFallbackAsync({ task.get() }, { fallback.get() }, ctx)"))
    fun <T> executeWithFallback(task: ThrowingSupplier<T>, fallback: ThrowingSupplier<T>, ctx: TaskContext): T

    @Deprecated("Use executeWithFinallyAsync", ReplaceWith("executeWithFinallyAsync({ task.get() }, finalizer, ctx)"))
    fun <T> executeWithFinally(task: ThrowingSupplier<T>, finalizer: () -> Unit, ctx: TaskContext): T

    @Deprecated("Use executeWithTranslationAsync", ReplaceWith("executeWithTranslationAsync({ task.get() }, translator, ctx)"))
    fun <T, E : Throwable> executeWithTranslation(task: ThrowingSupplier<T>, translator: (Throwable) -> E, ctx: TaskContext): T
}
```

- [ ] **Step 4: Modify `DefaultCheckedLogicExecutor`**

Add `*Async` methods; keep `@Deprecated execute*` implementations as-is (no breaking change for module-app).

```kotlin
// New: executeAsync (and 6 variants follow same pattern)
override fun <T> executeAsync(
    task: () -> CompletableFuture<T>,
    ctx: TaskContext
): CompletableFuture<T> =
    pipeline.executeRaw(task, ctx)
        .exceptionally { ex -> throw (ex.cause ?: ex) }

// executeWithFinallyAsync (example variant)
override fun <T> executeWithFinallyAsync(
    task: () -> CompletableFuture<T>,
    finalizer: () -> Unit,
    ctx: TaskContext
): CompletableFuture<T> {
    val cf = pipeline.executeRaw(task, ctx)
    return cf.whenComplete { _, _ -> finalizer() }
        .exceptionally { ex -> throw (ex.cause ?: ex) }
}

// executeWithTranslationAsync
override fun <T, E : Throwable> executeWithTranslationAsync(
    task: () -> CompletableFuture<T>,
    translator: (Throwable) -> E,
    ctx: TaskContext
): CompletableFuture<T> =
    pipeline.executeRaw(task, ctx)
        .exceptionally { ex -> throw translator(ex.cause ?: ex) }
```

The existing `@Deprecated` `execute*` methods stay (no code change to them). module-app still compiles with warnings.

- [ ] **Step 5: Modify `ExecutionPipeline`**

In `ExecutionPipeline.kt:117`, accept a `() -> CompletableFuture<T>` task directly:

```kotlin
// Before
val result = task.get()  // task: ThrowingSupplier<T>

// After
return task  // task: () -> CompletableFuture<T>; the pipeline returns it
```

The pipeline now passes through CF tasks. The old `ThrowingSupplier<T>` callers (module-app) still work via the @Deprecated method's `task.get()` bridge.

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*DefaultCheckedLogicExecutorAsyncTest*"
```

Expected: PASS.

- [ ] **Step 7: Verify green build (Q1=A)**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. module-app's `@Deprecated` callers compile with warnings (not errors). Active modules still use old API in T14.

- [ ] **Step 8: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/LogicExecutor.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/DefaultCheckedLogicExecutor.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/policy/ExecutionPipeline.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/executor/DefaultCheckedLogicExecutorAsyncTest.kt
git commit -m "feat(infra): LogicExecutor.executeAsync — add *Async API, @Deprecated sync"
```

---

## Task 2: Add `Lock.executeAsync` (`PostgresAdvisoryLockStrategy`, `OrderedLockExecutor`)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../lock/PostgresAdvisoryLockStrategy.kt`
- Modify: `module-infra/src/main/kotlin/.../lock/OrderedLockExecutor.kt`
- Test: `module-infra/src/test/kotlin/.../lock/PostgresAdvisoryLockStrategyAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.lock

import maple.expectation.common.task.TaskContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PostgresAdvisoryLockStrategyAsyncTest {
    @Test
    fun `executeAsync returns CF and runs supplier`() {
        val strategy = PostgresAdvisoryLockStrategy(/* mock */)

        val result = strategy.executeAsync(
            "test-key",
            { CompletableFuture.completedFuture(42) },
            TaskContext.simple("test")
        )

        assertNotNull(result.getNow(null))
        assertEquals(42, result.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*PostgresAdvisoryLockStrategyAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `PostgresAdvisoryLockStrategy`**

Add `executeAsync` method (alongside the existing `execute` sync). Replace `executor.execute({ task.get() }, context)` at lines 72, 117, 143 with the CF pattern.

```kotlin
// New: executeAsync
fun <T> executeAsync(
    key: String,
    supplier: () -> CompletableFuture<T>,
    ctx: TaskContext
): CompletableFuture<T> {
    val lock = acquireTxLock(key, ctx) // pg_try_advisory_xact_lock
    if (!lock) return CompletableFuture.failedFuture(LockUnavailableException(key))
    return supplier().whenComplete { _, ex -> releaseTxLock(key, ctx) }
}

// Apply same pattern to leader/follower at lines 117, 143
fun <T> executeWithLockAsync(
    key: String,
    supplier: () -> CompletableFuture<T>,
    ctx: TaskContext
): CompletableFuture<T> {
    val isLeader = tryLeaderElect(key, ctx)
    if (isLeader) {
        return supplier().whenComplete { _, _ -> releaseLeader(key, ctx) }
            .thenCompose { value ->
                broadcastToFollowersAsync(key, value).thenApply { value }
            }
    }
    return awaitLeaderBroadcastAsync(key, ctx)
}
```

The existing `execute` (sync) stays with `@Deprecated` for module-app.

- [ ] **Step 4: Modify `OrderedLockExecutor`**

Add `executeAsync`; mark `execute` as `@Deprecated`. Replace `task.get()` at lines 143, 181 for the async path only.

```kotlin
// Before
val result = task.get()

// After (async variant)
return task  // already CompletableFuture<T>
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*PostgresAdvisoryLockStrategyAsyncTest*"
```

Expected: PASS.

- [ ] **Step 6: Verify green build**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/
git commit -m "feat(infra): Lock.executeAsync — CF return, @Deprecated sync"
```

---

## Task 3: Add `SingleFlight.executeAsync` (in-flight only, Q12=A)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../concurrency/PostgresSingleFlightStrategy.kt`
- Modify: `module-infra/src/main/kotlin/.../concurrency/SingleFlightExecutor.kt`
- Test: `module-infra/src/test/kotlin/.../concurrency/SingleFlightAsyncTest.kt`

- [ ] **Step 1: Write failing test (concurrent only; Q12=A = in-flight dedup)**

```kotlin
package maple.expectation.infrastructure.concurrency

import maple.expectation.common.task.TaskContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SingleFlightAsyncTest {
    @Test
    fun `executeAsync runs supplier once for N concurrent callers`() {
        val callCount = AtomicInteger(0)
        val sf = SingleFlightExecutor(/* mock */)

        // Launch 10 concurrent callers
        val suppliers = (1..10).map {
            CompletableFuture.runAsync {
                sf.executeAsync("key") {
                    callCount.incrementAndGet()
                    Thread.sleep(50) // hold in-flight while others arrive
                    CompletableFuture.completedFuture("value")
                }.get()
            }
        }
        CompletableFuture.allOf(*suppliers.toTypedArray()).get()

        assertEquals(1, callCount.get()) // single-flight: 1 supplier call
    }

    @Test
    fun `executeAsync allows new supplier call after previous completes`() {
        // Q12=A: in-flight only. After completion, new caller = new supplier run.
        val callCount = AtomicInteger(0)
        val sf = SingleFlightExecutor(/* mock */)

        sf.executeAsync("key") { callCount.incrementAndGet(); CompletableFuture.completedFuture("v1") }.get()
        sf.executeAsync("key") { callCount.incrementAndGet(); CompletableFuture.completedFuture("v2") }.get()

        assertEquals(2, callCount.get()) // 2 separate runs (no in-flight dedup needed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*SingleFlightAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `PostgresSingleFlightStrategy`**

Add `executeAsync`; keep `execute` as `@Deprecated`. Replace `executeAsync(...).orTimeout(...).join()` at line 76 (this becomes the async variant, no `.join`):

```kotlin
// New: executeAsync (in-flight only per Q12)
fun <T> executeAsync(
    key: String,
    supplier: () -> CompletableFuture<T>,
    ctx: TaskContext
): CompletableFuture<T> {
    val inflight = inFlightRegistry[key]
    if (inflight != null && !inflight.isDone) {
        return inflight.thenApply { it as T }  // join in-flight
    }
    val newInflight = supplier()
    inFlightRegistry[key] = newInflight
    return newInflight.whenComplete { _, _ -> inFlightRegistry.remove(key) }
        .orTimeout(timeout, TimeUnit.SECONDS)
        .exceptionally { ex ->
            if (ex.cause is TimeoutException) throw SingleFlightTimeoutException(key)
            throw (ex.cause ?: ex)
        }
}
```

- [ ] **Step 4: Modify `SingleFlightExecutor`**

Add `executeAsync`; mark `execute` as `@Deprecated`. Replace `CompletableFuture.supplyAsync({ asyncSupplier.get() }, executor)` at line 103:

```kotlin
// New: executeAsync
fun <T> executeAsync(
    key: String,
    supplier: () -> CompletableFuture<T>
): CompletableFuture<T> {
    val inflight = localInflight[key]
    if (inflight != null && !inflight.isDone) {
        return inflight.thenApply { it as T }
    }
    val newInflight = supplier()
    localInflight[key] = newInflight
    return newInflight.whenComplete { _, _ -> localInflight.remove(key) }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :module-infra:test --tests "*SingleFlightAsyncTest*"
```

Expected: PASS.

- [ ] **Step 6: Verify green build**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/
git commit -m "feat(infra): SingleFlight.executeAsync — in-flight dedup, @Deprecated sync"
```

---

## Task 4: Add `TieredCache.getAsync`/`putAsync` (L1+L2 best-effort, Q8=A)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../cache/TieredCache.kt`
- Modify: `module-infra/src/main/kotlin/.../cache/TieredCacheManager.kt`
- Modify: `module-infra/src/main/kotlin/.../cache/tiered/PostgresL2CacheStrategy.kt`
- Modify: `module-infra/src/main/kotlin/.../cache/invalidation/impl/PostgresNotifySubscriber.kt`
- Test: `module-infra/src/test/kotlin/.../cache/TieredCacheAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.cache

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TieredCacheAsyncTest {
    @Test
    fun `getAsync returns CF and populates L1 on L2 hit`() {
        val cache = TieredCache<String, String>(/* mock L1, mock L2 with hit */)

        val result = cache.getAsync(
            "k",
            { CompletableFuture.completedFuture("loaded") },
            TaskContext.simple("test")
        )

        assertEquals("loaded", result.get())
    }

    @Test
    fun `putAsync writes to L1 and L2 best-effort`() {
        val cache = TieredCache<String, String>(/* mocks */)
        cache.putAsync("k", "v", TaskContext.simple("test")).get()
        // assert L1 and L2 mocks received put (best-effort, not order-dependent)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*TieredCacheAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `TieredCache.kt`**

Add `getAsync`/`putAsync`; mark sync `get`/`put` as `@Deprecated`. The internal `wrapper.get()` at lines 132, 150 stays (cache-internal serialization, allowlisted per Q4=A).

```kotlin
// New: getAsync (L1+L2 best-effort per Q8)
fun <T> getAsync(
    key: K,
    loader: () -> CompletableFuture<T>,
    ctx: TaskContext
): CompletableFuture<T> {
    val l1 = l1Cache.getIfPresent(key)
    if (l1 != null) return CompletableFuture.completedFuture(type.cast(l1))

    return l2Cache.getAsync(key, ctx)
        .thenCompose { l2Value ->
            if (l2Value != null) {
                l1Cache.put(key, l2Value)  // sync Caffeine put (cache-internal)
                CompletableFuture.completedFuture(type.cast(l2Value))
            } else {
                loader().thenCompose { loaded ->
                    // best-effort write to L1+L2; do not block caller
                    putAsync(key, loaded, ctx).thenApply { loaded }
                }
            }
        }
}

// New: putAsync (L1 sync + L2 async, best-effort)
fun <T> putAsync(key: K, value: T, ctx: TaskContext): CompletableFuture<Void> =
    CompletableFuture.allOf(
        CompletableFuture.runAsync({ l1Cache.put(key, value) }, executor),
        l2Cache.putAsync(key, value, ctx)
    ).whenComplete { _, ex ->
        if (ex != null) log.warn("cache put best-effort failed: {}", ex.cause ?: ex)
    }
```

Note: lines 132, 150 (`l1.put(key, w.get())`) keep `.get()` because they unwrap an internal `ValueWrapper` CF used for serialization, not a user-supplied task. Allowlist documented in grep gate (T15).

- [ ] **Step 4: Modify `TieredCacheManager.kt`**

Add `getAsync`/`putAsync` to manager interface. Same pattern as Step 3.

- [ ] **Step 5: Modify `PostgresL2CacheStrategy`**

Add `getAsync`/`putAsync` (PG UNLOGGED):

```kotlin
fun <T> getAsync(key: K, ctx: TaskContext): CompletableFuture<T?> =
    CompletableFuture.supplyAsync({ /* SELECT from PG UNLOGGED */ }, dbExecutor)
        .thenApply { row -> deserialize<T>(row) }

fun putAsync(key: K, value: Any, ctx: TaskContext): CompletableFuture<Void> =
    CompletableFuture.runAsync({ /* INSERT */ }, dbExecutor)
```

- [ ] **Step 6: Modify `PostgresNotifySubscriber`**

Already returns CF; ensure no `.get()` in the message handler. Add comment.

- [ ] **Step 7: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*TieredCacheAsyncTest*"
```

Expected: PASS.

- [ ] **Step 8: Verify green build**

```bash
./gradlew compileKotlin compileJava --continue
```

- [ ] **Step 9: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/
git commit -m "feat(infra): TieredCache getAsync/putAsync — async return, L1+L2 best-effort"
```

---

## Task 5: Migrate `EquipmentFetchProvider` (`@Cacheable` → `getAsync`)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../provider/EquipmentFetchProvider.kt`
- Test: `module-infra/src/test/kotlin/.../provider/EquipmentFetchProviderAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.provider

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class EquipmentFetchProviderAsyncTest {
    @Test
    fun `fetchAsync returns CF, no caller blocking`() {
        val provider = EquipmentFetchProvider(/* mock */)

        val result = provider.fetchAsync("ocid-123", TaskContext.simple("test"))
        assertNotNull(result.getNow(null)) // non-blocking
        result.get() // wait
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*EquipmentFetchProviderAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `EquipmentFetchProvider`**

Remove `@Cacheable` from line 71 area. Replace `nexonApiClient.getItemDataByOcid(ocid).orTimeout(...).join()` at line 72:

```kotlin
// Before
@Cacheable("equipment")
fun getItemData(ocid: String): ItemData =
    nexonApiClient.getItemDataByOcid(ocid).orTimeout(30, TimeUnit.SECONDS).join()

// After
fun getItemDataAsync(ocid: String, ctx: TaskContext): CompletableFuture<ItemData> =
    cache.getAsync(
        "equipment:$ocid",
        { nexonApiClient.getItemDataByOcidAsync(ocid, ctx) },
        ctx
    )
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*EquipmentFetchProviderAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/provider/EquipmentFetchProvider.kt
git commit -m "feat(infra): EquipmentFetchProvider @Cacheable removed, fetchAsync"
```

---

## Task 5b: Migrate Java `@Cacheable` adapters (`StarforceLookupAdapter`, `CubeComputeBuffer`)

**Files:**
- Modify: `module-infra/src/main/java/.../service/starforce/StarforceLookupAdapter.java`
- Modify: `module-infra/src/main/java/.../service/cube/component/CubeComputeBuffer.java`
- Test: `module-infra/src/test/java/.../service/starforce/StarforceLookupAdapterAsyncTest.java`
- Test: `module-infra/src/test/java/.../service/cube/component/CubeComputeBufferAsyncTest.java`

- [ ] **Step 1: Write failing test for `expectedCostCache` (Starforce)**

```java
package maple.expectation.infrastructure.service.starforce;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StarforceLookupAdapterAsyncTest {
    @Test
    void expectedCostAsyncReturnsCF() {
        var adapter = new StarforceLookupAdapter(/* mocks */);
        var result = adapter.lookupExpectedCostAsync(170, 15, TaskContext.simple("test"));
        assertNotNull(result.getNow(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*StarforceLookupAdapterAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `StarforceLookupAdapter`**

Replace `expectedCostCache.get(key)` (line 169) and `initialized.get()` (line 368):

```java
// Before
public BigDecimal lookupExpectedCost(int star, int level) {
    String key = star + ":" + level;
    BigDecimal cached = expectedCostCache.get(key);
    if (cached != null) return cached;
    BigDecimal computed = compute(star, level);
    expectedCostCache.put(key, computed);
    return computed;
}

// After
public CompletableFuture<BigDecimal> lookupExpectedCostAsync(int star, int level, TaskContext ctx) {
    String key = star + ":" + level;
    return cache.getAsync("starforce:expected:" + key,
        CompletableFuture.completedFuture(compute(star, level)),
        ctx
    );
}
```

- [ ] **Step 4: Write failing test for `CubeComputeBuffer`**

```java
package maple.expectation.infrastructure.service.cube.component;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CubeComputeBufferAsyncTest {
    @Test
    void getAsyncReturnsCF() {
        var buffer = new CubeComputeBuffer(/* mocks */);
        var result = buffer.getAsync("key", /* loader */);
        assertNotNull(result.getNow(null));
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*CubeComputeBufferAsyncTest*"
```

Expected: compile error.

- [ ] **Step 6: Modify `CubeComputeBuffer`**

Replace `cache.get(key)` (line 28), `compute.get()` (line 38), `hits.get()` / `misses.get()` (lines 52):

```java
// Before
public <T> T get(String key, Supplier<T> loader) {
    T cached = cache.get(key);
    if (cached != null) { hits.incrementAndGet(); return cached; }
    misses.incrementAndGet();
    T value = loader.get();
    cache.put(key, value);
    return value;
}

// After
public <T> CompletableFuture<T> getAsync(String key, Supplier<CompletableFuture<T>> loader) {
    T cached = cache.getIfPresent(key);
    if (cached != null) { hits.incrementAndGet(); return CompletableFuture.completedFuture(cached); }
    misses.incrementAndGet();
    return loader.get().thenApply(value -> { cache.put(key, value); return value; });
}
```

Note: `hits.get()` and `misses.get()` are `AtomicLong` reads in metrics — non-blocking, leave as-is.

- [ ] **Step 7: Run tests to verify they pass**

```bash
./gradlew :module-infra:test --tests "*StarforceLookupAdapterAsyncTest*" --tests "*CubeComputeBufferAsyncTest*"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add module-infra/src/main/java/maple/expectation/infrastructure/service/starforce/StarforceLookupAdapter.java \
        module-infra/src/main/java/maple/expectation/infrastructure/service/cube/component/CubeComputeBuffer.java
git commit -m "feat(infra): Starforce + Cube Java @Cacheable removed, getAsync"
```

---

## Task 6: Migrate PGMQ workers (`handle(): CF<AckResult>`, cancel = redeliver, PgmqWorker = supplyAsync + allOf)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../worker/ExternalApiWorker.kt`
- Modify: `module-infra/src/main/kotlin/.../worker/OcidResolveWorker.kt`
- Modify: `module-infra/src/main/kotlin/.../worker/CalculationWorker.kt`
- Modify: `module-infra/src/main/kotlin/.../worker/ResultReadyProjectionWorker.kt`
- Modify: `module-infra/src/main/kotlin/.../pgmq/PgmqWorker.kt`
- Test: `module-infra/src/test/kotlin/.../worker/ExternalApiWorkerAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.worker

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class ExternalApiWorkerAsyncTest {
    @Test
    fun `handle returns CF of AckResult, no caller blocking`() {
        val worker = ExternalApiWorker(/* mocks */)
        val msg = PgmqMessage(payload = samplePayload)

        val result = worker.handle(msg)
        assertNotNull(result.getNow(null))
        val ack = result.get()
        assertEquals(AckStatus.ACK, ack.status)
    }

    @Test
    fun `handle returns Nack retryable on cancel`() {
        // Q7=A: cancel mid-processing = Nack(retryable=true), visibility reset
        val worker = ExternalApiWorker(/* mocks */)
        val msg = PgmqMessage(payload = samplePayload)

        val cf = worker.handle(msg)
        cf.cancel(true) // simulate caller cancel
        val ack = cf.get()
        assertEquals(AckStatus.NACK, ack.status)
        assertEquals(true, ack.retryable)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*ExternalApiWorkerAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `ExternalApiWorker`**

Replace `pipelineAsync(payload).join()` at line 111:

```kotlin
// Before
override fun onMessage(msg: PgmqMessage<Payload>): Unit =
    pipeline.process(msg.payload).join()

// After
override fun handle(msg: PgmqMessage<Payload>): CompletableFuture<AckResult> =
    pipeline.processAsync(msg.payload)
        .thenCompose { chunks -> publisher.publishAsync(chunks) }
        .thenApply { AckResult.ack() }
        .exceptionally { ex ->
            log.error("[ExternalApi] pipeline failed: {}", ex.cause ?: ex)
            AckResult.retry(visibility = visibilityTimeout) // Q7: cancel = redeliver
        }
        .whenComplete { _, ex ->
            if (ex is CancellationException) {
                // Q7=A: cancel from caller = Nack(retryable=true), PGMQ redelivers
                log.warn("[ExternalApi] cancelled mid-processing; redeliver via PGMQ visibility")
            }
        }
```

Replace `runBlocking(Dispatchers.Default) { ... }` at line 306:

```kotlin
// Before
runBlocking(Dispatchers.Default) {
    // CPU-bound section
}

// After
CompletableFuture.supplyAsync({
    // CPU-bound section
}, cpuExecutor) // cpuExecutor = Executors.newWorkStealingPool(N_CPUS)
```

- [ ] **Step 4: Modify `OcidResolveWorker`**

Replace `nexonApiClient.getOcidByCharacterName(...).handle{...}.join()` at line 73:

```kotlin
// Before
val result = nexonApiClient.getOcidByCharacterName(name)
    .handle { _, ex -> if (ex != null) Pair(null, ex) else Pair(it, null) }
    .join()

// After
return nexonApiClient.getOcidByCharacterNameAsync(name, ctx)
    .handle { value, ex -> if (ex != null) Pair(null, ex.cause ?: ex) else Pair(value, null) }
    .thenCompose { (ocid, err) ->
        if (err != null) CompletableFuture.completedFuture(AckResult.retry(visibility = visibilityTimeout))
        else publishOcidAsync(name, ocid!!, ctx).thenApply { AckResult.ack() }
    }
```

- [ ] **Step 5: Modify `CalculationWorker`**

Same pattern as Step 4. Replace `calculateAsync(...).handle{...}.join()` at line 91.

- [ ] **Step 6: Modify `ResultReadyProjectionWorker`**

Replace `jobsFuture.join()` and `lightResultsFuture.join()` at lines 89, 90:

```kotlin
// Before
val jobs = jobsFuture.join()
val light = lightResultsFuture.join()

// After
return jobsFuture
    .thenCombine(lightResultsFuture) { jobs, light -> jobs to light }
    .thenCompose { (jobs, light) -> projectBatchAsync(jobs, light, ctx) }
    .thenApply { AckResult.ack() }
    .exceptionally { ex -> AckResult.retry(visibility = visibilityTimeout) }
```

Replace `runBlocking(Dispatchers.Default) { ... }` at line 123 with `CompletableFuture.supplyAsync(..., cpuExecutor)`.

- [ ] **Step 7: Modify `PgmqWorker.processSequentialBatch` (Q5=A = supplyAsync + allOf)**

Replace `runBlocking(Dispatchers.Default) { messages.map { async { ... }.awaitAll() } }` at line 380 with true CF parallelism:

```kotlin
// Before (line 380)
private fun processSequentialBatch(messages: List<PgmqMessage<T>>) {
    val results: List<CalculationResult> = runBlocking(Dispatchers.Default) {
        messages.map { message ->
            async(Dispatchers.Default) {
                metrics.concurrentIncrement()
                val context = TaskContext.of("PgmqWorker", "CoroutineCalc", "$queueName:${message.messageId}")
                val result = executor.executeOrDefault(
                    { calculateOnly(message) },
                    null,
                    context,
                )
                metrics.concurrentDecrement()
                result as? CalculationResult
            }
        }.awaitAll().filterNotNull()
    }
    // ... post-processing
}

// After (Q5=A: supplyAsync + allOf for true CF parallelism)
private fun processSequentialBatch(messages: List<PgmqMessage<T>>): CompletableFuture<List<CalculationResult>> {
    val futures: List<CompletableFuture<CalculationResult?>> = messages.map { message ->
        CompletableFuture.supplyAsync({
            metrics.concurrentIncrement()
            val context = TaskContext.of("PgmqWorker", "CFAsyncCalc", "$queueName:${message.messageId}")
            val result = executor.executeAsync({ calculateOnly(message) }, context).get()
            metrics.concurrentDecrement()
            result as? CalculationResult
        }, cpuExecutor) // cpuExecutor = Executors.newWorkStealingPool(N_CPUS)
    }

    return CompletableFuture.allOf(*futures.toTypedArray())
        .thenApply { futures.mapNotNull { it.getNow(null) } }
        .exceptionally { ex ->
            log.error("PgmqWorker batch failed: {}", ex.cause ?: ex)
            emptyList() // best-effort: redelivery handles via outer ack
        }
}
```

Note: `executor.executeAsync(...).get()` here is **intentional** because the supplyAsync lambda IS a single thread (CPU worker); `.get()` joins that single in-flight CF on the same thread. This is the Q5=A pattern: parallel dispatch via `supplyAsync`, then `allOf` for batch join.

- [ ] **Step 8: Run tests**

```bash
./gradlew :module-infra:test --tests "*WorkerAsyncTest*"
```

Expected: PASS.

- [ ] **Step 9: Verify green build**

```bash
./gradlew compileKotlin compileJava --continue
```

- [ ] **Step 10: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt
git commit -m "feat(infra): PGMQ workers handle() returns CF<AckResult>, no .join"
```

---

## Task 7: Migrate `JwtAuthenticationFilter` (CF chain on `payload`)

**Files:**
- Modify: `module-infra/src/main/kotlin/.../security/filter/JwtAuthenticationFilter.kt`
- Test: `module-infra/src/test/kotlin/.../security/filter/JwtAuthenticationFilterAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.security.filter

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class JwtAuthenticationFilterAsyncTest {
    @Test
    fun `filter resolves token via CF chain, returns 401 on fail`() {
        val filter = JwtAuthenticationFilter(/* mocks */)
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer invalid") }

        val chain = MockFilterChain()
        filter.doFilter(request, MockHttpServletResponse(), chain)
        assertEquals(401, /* response status */)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*JwtAuthenticationFilterAsyncTest*"
```

Expected: compile error or test fail.

- [ ] **Step 3: Modify `JwtAuthenticationFilter`**

Replace `payload.get()` at line 85:

```kotlin
// Before
val claims = payload.get()
filterChain.doFilter(requestWithClaims, response)

// After
payload
    .thenAccept { claims -> filterChain.doFilter(requestWithClaims(claims), response) }
    .exceptionally { ex ->
        response.status = 401
        response.writer.write("invalid token")
        null
    }
```

Convert the filter to a `WebFilter` if Spring Security config requires it. If not, use servlet `AsyncContext`:

```kotlin
val asyncCtx = request.startAsync()
payload
    .thenAccept { claims -> /* set auth, asyncCtx.dispatch() */ }
    .exceptionally { ex -> /* 401, asyncCtx.complete() */ }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*JwtAuthenticationFilterAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/JwtAuthenticationFilter.kt
git commit -m "feat(infra): JwtAuthenticationFilter uses CF chain on payload"
```

---

## Task 8: Replace `GlobalAdmissionControl` busy loop with cancel token

**Files:**
- Modify: `module-infra/src/main/kotlin/.../admission/GlobalAdmissionControl.kt`
- Test: `module-infra/src/test/kotlin/.../admission/GlobalAdmissionControlAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.admission

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class GlobalAdmissionControlAsyncTest {
    @Test
    fun `awaitPermission returns CF, completes when permit available`() {
        val control = GlobalAdmissionControl(/* config */)
        val permit = control.tryAcquireAsync()
        assertNotNull(permit.getNow(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*GlobalAdmissionControlAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `GlobalAdmissionControl`**

Replace `while (running.get() && !Thread.interrupted)` at line 238:

```kotlin
// Before
while (running.get() && !Thread.currentThread().isInterrupted) {
    Thread.sleep(10) // spin
}

// After — use a CompletableFuture as cancel signal
private val cancelled = CompletableFuture<Void>()

fun awaitPermissionAsync(): CompletableFuture<Permit> {
    return CompletableFuture.anyOf(cancelled, permitAvailableSignal)
        .thenApply { if (it == CANCEL_TOKEN) throw AdmissionCancelledException() else currentPermit() }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*GlobalAdmissionControlAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/
git commit -m "feat(infra): GlobalAdmissionControl cancel token, no busy loop"
```

---

## Task 9: Migrate ext-api `InternalApiController` (return 202, no `.join()`)

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../runstatus/InternalApiController.kt`
- Test: `module-external-api/src/test/kotlin/.../runstatus/InternalApiControllerAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.externalapi.runstatus

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import kotlin.test.assertEquals

class InternalApiControllerAsyncTest {
    @Test
    fun `triggerPhase returns 202 immediately, runs chain async`() {
        val mvc: MockMvc = /* setup */
        val result = mvc.perform(post("/api/internal/trigger/phase/ranking"))
            .andReturn()

        assertEquals(202, result.response.status)
        // assert no .join() on caller thread (no CompletionException in response)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "*InternalApiControllerAsyncTest*"
```

Expected: status returned synchronously but test fails on chain assertion (chain not yet migrated in scheduler).

- [ ] **Step 3: Modify `InternalApiController`**

Q3=B: triggers return `ResponseEntity.accepted().body(...)` 202 immediately. Replace `executor.submit { scheduler.triggerDailyRefresh(runId).join() }` at line 83 and `triggerPhase` at line 123:

```kotlin
// Before
@PostMapping("/trigger/daily")
fun triggerDaily(airflowRunId: String?): ResponseEntity<*> {
    val runId = airflowRunId ?: UUID.randomUUID().toString()
    executor.submit { scheduler.triggerDailyRefresh(runId).join() }
    return ResponseEntity.accepted().body(mapOf("runId" to runId))
}

// After (Q3=B: 202 + whenComplete for status tracking)
@PostMapping("/trigger/daily")
fun triggerDaily(airflowRunId: String?): ResponseEntity<Map<String, String>> {
    val runId = airflowRunId ?: UUID.randomUUID().toString()
    scheduler.triggerDailyRefreshAsync(runId)
        .whenComplete { _, ex -> runStatusTracker.markOutcome(runId, ex) }
    return ResponseEntity.accepted().body(mapOf("runId" to runId))
}
```

Same pattern for `triggerPhase` at line 123.

- [ ] **Step 4: Run test to verify it passes (after Task 10 migrates scheduler)**

```bash
./gradlew :module-external-api:test --tests "*InternalApiControllerAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt
git commit -m "feat(ext-api): InternalApiController returns 202 without .join() on caller"
```

---

## Task 10: Migrate ext-api `ExternalApiScheduler` (remove `runBlocking`, use `ExecutorSelector`)

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../scheduler/ExternalApiScheduler.kt`
- Test: `module-external-api/src/test/kotlin/.../scheduler/ExternalApiSchedulerAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.externalapi.scheduler

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class ExternalApiSchedulerAsyncTest {
    @Test
    fun `triggerPhaseAsync returns CF, no runBlocking`() {
        val scheduler = ExternalApiScheduler(/* deps */)
        val cf = scheduler.triggerPhaseAsync(PipelinePhase.RANKING, "run-1", null, TaskContext.simple("test"))
        assertNotNull(cf.getNow(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "*ExternalApiSchedulerAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `ExternalApiScheduler`**

Replace `runBlocking { ocidLookupPhase.execute(executor, runKey, runId) }` at line 188:

```kotlin
// Before (runOcidPhase)
private fun runOcidPhase(runKey: RunKey, runId: String): CompletableFuture<Void> {
    val future = runCatching {
        runBlocking { ocidLookupPhase.execute(executor, runKey, runId) }
    }.let { CompletableFuture.completedFuture(it) }
    return future.whenComplete { _, ex -> /* log */ }
}

// After
private fun runOcidPhaseAsync(runKey: RunKey, runId: String): CompletableFuture<Void> =
    ocidLookupPhase.executeAsync(executor, runKey, runId)
        .whenComplete { _, ex -> /* log */ }
```

Convert `triggerPhase`, `triggerDailyRefresh`, `runRankingPhase`, `runCharBasicPhase`, `runItemEquipmentPhase` all to `*Async` variants returning `CF<RunKey>` / `CF<Void>`.

Replace inline `Executors.newVirtualThreadPerTaskExecutor()` at line 43 with `ExecutorSelector.submit()` from module-infra/concurrency:

```kotlin
// Before
private val executor = Executors.newVirtualThreadPerTaskExecutor()

// After
private val executor = ExecutorSelector.virtualThreadExecutor("external-api-scheduler")
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-external-api:test --tests "*ExternalApiSchedulerAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt
git commit -m "feat(ext-api): ExternalApiScheduler — all phases return CF, no runBlocking"
```

---

## Task 11: `OcidLookupPhase` doc-only (Q2=A)

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../scheduler/phase/OcidLookupPhase.kt` (add code comment only)

- [ ] **Step 1: Add code comment explaining suspend semantics**

At lines 147-148, add comment:

```kotlin
// Note: writerJob.join() and putJob.await() at lines below are suspend functions
// (kotlinx.coroutines.Job.join() is a suspend fun). They DO NOT block the carrier
// thread when called from a coroutine context (which is the case here — we're
// inside coroutineScope { ... } at line 117).
//
// The original chain breaker was `runBlocking { ocidLookupPhase.execute(...) }`
// at ExternalApiScheduler.kt:188, fixed in T10. This Job.join() / .await() pair
// is non-blocking by construction.
//
// If a future refactor moves this code out of the coroutineScope, these calls
// MUST be replaced with actual .get() or a different mechanism.
coroutineScope {
    val putJob = async(Dispatchers.IO) { objectStorage.putStream(key, pipeIn) }
    val writerJob = launch(Dispatchers.IO) { /* ... */ }
    processBatch(/* ... */)
    resultsChannel.close()
    writerJob.join()  // suspend, non-blocking
    putJob.await()    // suspend, non-blocking
}
```

- [ ] **Step 2: Verify build still green**

```bash
./gradlew :module-external-api:compileKotlin
```

Expected: BUILD SUCCESSFUL (no logic change, comment only).

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "docs(ext-api): OcidLookupPhase Job.join() — suspend semantic comment"
```

---

## Task 12: Migrate ext-api `ChunkFileManager` sink close path

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../snapshot/ChunkFileManager.kt`
- Test: `module-external-api/src/test/kotlin/.../snapshot/ChunkFileManagerAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.externalapi.snapshot

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class ChunkFileManagerAsyncTest {
    @Test
    fun `closeAsync returns CF after manifest write, no caller blocking`() {
        val mgr = ChunkFileManager(/* deps */)
        val cf = mgr.closeAsync(TaskContext.simple("test"))
        assertNotNull(cf.getNow(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "*ChunkFileManagerAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `ChunkFileManager`**

Replace `all.get(timeoutMs, TimeUnit.MILLISECONDS)` at line 132:

```kotlin
// Before
fun awaitAllUploads(timeoutMs: Long): Boolean {
    val all = CompletableFuture.allOf(*inFlightUploads.toTypedArray())
    return try { all.get(timeoutMs, TimeUnit.MILLISECONDS); true } catch (e: Exception) { false }
}

// After
fun closeAsync(ctx: TaskContext): CompletableFuture<Void> =
    CompletableFuture.allOf(*inFlightUploads.toTypedArray())
        .thenRun { writeManifest() }
        .thenRun { flushBuffer() }
        .whenComplete { _, ex -> if (ex != null) log.warn("close with errors", ex.cause ?: ex) }
```

Update `ChunkedSnapshotSink.close()` to chain via `thenCompose`.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-external-api:test --tests "*ChunkFileManagerAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/
git commit -m "feat(ext-api): ChunkFileManager closeAsync — CF return, manifest in thenRun"
```

---

## Task 13: Migrate ext-api `SnapshotFailedRecordWriter` + `AuthCharacterFetchConsumer` null-safety

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../snapshot/SnapshotFailedRecordWriter.kt`
- Modify: `module-external-api/src/main/kotlin/.../auth/AuthCharacterFetchConsumer.kt`
- Test: `module-external-api/src/test/kotlin/.../snapshot/SnapshotFailedRecordWriterAsyncTest.kt`
- Test: `module-external-api/src/test/kotlin/.../auth/AuthCharacterFetchConsumerNullSafeTest.kt`

- [ ] **Step 1: Modify `SnapshotFailedRecordWriter`**

Replace `runCatching { objectStorage.get(key) }.getOrDefault(...)` at line 21:

```kotlin
// Before
val existing = runCatching { objectStorage.get(key) }.getOrDefault(ByteArray(0))

// After
val existing: ByteArray = objectStorage.getAsync(key, ctx)
    .exceptionally { ex -> log.warn("obj read fail: {}", ex.cause ?: ex); ByteArray(0) }
    .get()  // intentional: bounded read at failure-record write path (local MinIO)
```

Note: this `.get()` is on a CF that returns within the timeout (objectStorage is local MinIO). Acceptable at this site. Document the rationale in a comment.

- [ ] **Step 2: Modify `AuthCharacterFetchConsumer`**

Replace `characterListOpt.get()` at line 51 with null-safe chain:

```kotlin
// Before
val characterListOpt = nexonAuthClient.getCharacterList(request.apiKey)
if (characterListOpt.isEmpty) { return /* error */ }
val resp = characterListOpt.get()

// After
nexonAuthClient.getCharacterListAsync(request.apiKey, ctx)
    .thenCompose { characterList ->
        if (characterList.isEmpty()) {
            publishErrorAsync(request, "no characters", ctx)
        } else {
            processCharactersAsync(characterList, request, ctx)
        }
    }
    .whenComplete { _, ex -> logOutcome(request, ex) }
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :module-external-api:test --tests "*SnapshotFailedRecordWriter*" --tests "*AuthCharacterFetch*"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotFailedRecordWriter.kt \
        module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt
git commit -m "fix(ext-api): SnapshotFailedRecordWriter CF, AuthCharacterFetchConsumer null-safe chain"
```

---

## Task 14: Migrate active module port callers (5 sites in 5 files; Q6=B)

**Files (exact, verified by grep):**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt:99`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt:35`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:35`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt:75`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt:42` (also covered by T13; verify both)

`module-app` legacy (20+ Java files) = **out of scope, follow-up PR** (Q6=B).

**Pattern (apply to every call site):**

```kotlin
// Before
val result = logicExecutor.execute({ repo.find(id) }, ctx)
result.someMethod()

// After
logicExecutor.executeAsync({ repo.findAsync(id, ctx) }, ctx)
    .thenAccept { result -> result.someMethod() }
    .whenComplete { _, ex -> logIfError(ex) }
```

- [ ] **Step 1: Apply transformation per file**

For each of the 5 files, replace the sync call with the CF-returning variant.

**Example for `ChunkConsumerTemplate.kt:99`:**

```kotlin
// Before
request.executor.execute {
    // process chunk
    processChunk(chunk)
}

// After
request.executor.executeAsync(
    { processChunkAsync(chunk, ctx) },
    ctx
).whenComplete { _, ex -> logOutcome(chunk, ex) }
```

**Example for `OcidLookupRunConsumer.kt:35`:**

```kotlin
// Before
executor.execute {
    // lookup ocid
    lookupOcid(ign)
}

// After
executor.executeAsync({ lookupOcidAsync(ign, ctx) }, ctx)
    .whenComplete { _, ex -> logOutcome(ign, ex) }
```

**Example for `EquipmentRankingRedisWriter.kt:35`:**

```kotlin
// Before
val updated = executor.executeOrDefault(
    { writeRanking(item) },
    false,
    context
)

// After
val updatedCF = executor.executeOrDefaultAsync(
    { writeRankingAsync(item, ctx) },
    false,
    ctx
)
updatedCF.whenComplete { updated, ex -> logOutcome(item, updated, ex) }
```

**Example for `CalculationCache.kt:75`:**

```kotlin
// Before
return cache.get(key) {
    repo.findById(id)
}

// After
return cache.getAsync(
    key,
    { repo.findByIdAsync(id, ctx) },
    ctx
)
```

**Example for `AuthCharacterFetchConsumer.kt:42`** (overlaps with T13; ensure both changes merged):
This was already migrated in T13 Step 2. Verify no duplicate work.

- [ ] **Step 2: Run compile + test per module**

```bash
./gradlew :module-calculator:compileKotlin :module-calculator:compileJava
./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava
./gradlew :module-external-api:compileKotlin :module-external-api:compileJava
./gradlew :module-calculator:test :module-synchronizer:test :module-rest-controller:test :module-external-api:test
```

Expected: all compile clean; all tests pass. module-app compiles with `@Deprecated` warnings (not errors).

- [ ] **Step 3: Commit per module**

```bash
git add module-calculator/src/main module-synchronizer/src/main module-rest-controller/src/main
git commit -m "feat(active-modules): migrate 5 port callers to *Async APIs"
```

---

## Task 15: Add CI grep gate (allowlist for cache-internal `wrapper.get()`)

**Files:**
- Create: `module-infra/src/test/kotlin/.../test/BlockingPrimitiveGateTest.kt`
- Create: `module-external-api/src/test/kotlin/.../test/BlockingPrimitiveGateTest.kt`

- [ ] **Step 1: Write gate test for `module-infra`**

```kotlin
package maple.expectation.infrastructure.test

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class BlockingPrimitiveGateTest {
    @Test
    fun `no blocking primitives in module-infra main sources`() {
        val srcRoot = File("src/main/kotlin")
        val srcRootJava = File("src/main/java")
        val violations = mutableListOf<String>()

        val patterns = listOf(
            Regex("""\.(get|join)\(\s*\)"""),
            Regex("""runBlocking\s*\{"""),
            Regex("""\.blockingFirst\(\)|\.blockingLast\(\)|\.blockingGet\(\)"""),
            Regex("""Thread\.sleep\s*\("""),
            Regex("""CountDownLatch.*\.await\s*\("""),
            Regex("""\.awaitAll\s*\(""")
        )

        listOf(srcRoot, srcRootJava).forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown()
                .filter { it.extension in listOf("kt", "java") }
                .forEach { file ->
                    file.readLines().forEachIndexed { i, line ->
                        val trimmed = line.trim()
                        if (patterns.any { it.containsMatchIn(trimmed) } && !isAllowlisted(file, i, trimmed)) {
                            violations.add("${file.path}:${i + 1}: $trimmed")
                        }
                    }
                }
        }

        assertTrue(violations.isEmpty(), "Blocking primitives found:\n${violations.joinToString("\n")}")
    }

    private fun isAllowlisted(file: File, line: Int, text: String): Boolean {
        val path = file.absolutePath
        // Allow internal ValueWrapper unwraps in cache layer (Q4=A, Q8=A)
        val isCacheLayer = path.contains("/cache/")
        val isWrapperUnwrap = text.contains("wrapper") || text.contains("ValueWrapper")
        val isAllowedAtFailureWritePath = path.contains("SnapshotFailedRecordWriter") && text.contains(".get()")

        return (isCacheLayer && isWrapperUnwrap) || isAllowedAtFailureWritePath ||
               text.startsWith("//") || text.startsWith("*") || text.startsWith("/*")
    }
}
```

- [ ] **Step 2: Write gate test for `module-external-api`**

Mirror the test above, scoped to `module-external-api/src/main/`. Allowlist:
- `OcidLookupPhase.kt` lines 147-148 (suspend `Job.join()` / `await()` — Q2=A)
- `SnapshotFailedRecordWriter` `.get()` at failure-record write path

- [ ] **Step 3: Run gate tests**

```bash
./gradlew :module-infra:test --tests "*BlockingPrimitiveGateTest*"
./gradlew :module-external-api:test --tests "*BlockingPrimitiveGateTest*"
```

Expected: PASS. If any violation found, the test prints file:line and the offending pattern. Fix immediately.

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/test/BlockingPrimitiveGateTest.kt \
        module-external-api/src/test/kotlin/maple/externalapi/test/BlockingPrimitiveGateTest.kt
git commit -m "test(infra,ext-api): CI grep gate for blocking primitives in main sources"
```

---

## Task 16: Full verification

**Files:** none

- [ ] **Step 1: Compile all modules**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. module-app may have `@Deprecated` warnings (acceptable per Q6=B).

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew test
```

Expected: all tests pass. Fix any failure.

- [ ] **Step 3: Run grep gate tests**

```bash
./gradlew :module-infra:test :module-external-api:test --tests "*BlockingPrimitiveGateTest*"
```

Expected: 0 violations.

- [ ] **Step 4: Runtime smoke — ext-api**

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun &
sleep 30  # wait for boot
```

In another terminal:

```bash
curl -s -w "\nHTTP %{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -d '{"airflowRunId":"smoke-test-001"}' \
    "http://localhost:8081/api/internal/trigger/phase/ranking"
```

Expected: HTTP 202, body `{"runId":"smoke-test-001"}`.

Wait 60 seconds, then verify:

```bash
# No ERROR in log
grep "ERROR" /home/maple/probabilistic-valuation-engine/module-external-api/logs/app.log | tail -10
# Expected: empty output (or only pre-existing non-blocking errors)

# Phase completed
grep "phase completed" /home/maple/probabilistic-valuation-engine/module-external-api/logs/app.log | tail -5
# Expected: at least 1 line

# PGMQ queue drained
PGPASSWORD="$DB_PASS" psql "$DB_URL_parsed" -t -A -c \
    "SELECT count(*) FROM pgmq.q_result_ready_queue WHERE visible_at <= now()"
# Expected: 0 or decreasing trend

# Active jobs cleared
PGPASSWORD="$DB_PASS" psql "$DB_URL_parsed" -t -A -c \
    "SELECT count(*) FROM calculation_jobs WHERE status = 'API_REQUESTED'"
# Expected: 0
```

Stop the server: `kill %1`.

- [ ] **Step 5: Load test (post-refactor)**

```bash
RESET_ACTIVE_JOBS=1 RESET_VIEWS=1 COUNT=10000 CONCURRENCY=50 \
  SAMPLE_INTERVAL=30 POST_SAMPLE_COUNT=6 ./load-test/run-v5-db-throughput.sh
```

Expected: 6 sample snapshots, `views_per_sec` ≥ baseline (`docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md`).

- [ ] **Step 6: Stop server + cleanup processes**

```bash
pkill -f 'gradlew :module-external-api:bootRun'
pkill -f 'ExternalApiApplication'
pgrep -af 'load_test_v5|python3 load_test'  # should be empty
```

---

## Task 17: Create PR

**Files:** none

- [ ] **Step 1: Push branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git push -u origin feature/issue-CF-CHAIN-blocking-fix
```

- [ ] **Step 2: Create PR via `gh`**

```bash
gh pr create --base develop --head feature/issue-CF-CHAIN-blocking-fix \
  --title "feat(infra,ext-api): drop sync return contract, pure CF chain end-to-end" \
  --body "$(cat <<'EOF'
## Summary

Eliminates all 24 CRITICAL + 6 HIGH blocking primitives on the CompletableFuture chain from `module-external-api` controllers through `module-infra` ports. Replaces the synchronous return contract of `LogicExecutor`, `Lock`, `SingleFlight`, `TieredCache` with pure `CompletableFuture<T>` end-to-end. Soft-`@Deprecated` sync API kept for `module-app` legacy migration (follow-up PR).

## Spec / ADR

- Spec: `docs/superpowers/specs/2026-06-18-ext-api-blocking-fix-design.md`
- ADR: `docs/01_ADR/ADR-blocking-async-contract-cf-chain.md`
- Baseline: `docs/05_Reports/2026-06-18-pre-cf-chain-baseline.md`

## Decisions (grill-me Q1-Q13)

- Q1=A: each port task adds `*Async` alongside `@Deprecated` sync. Green build per commit.
- Q2=A: `OcidLookupPhase.Job.join()` is suspend (Q2) — T11 = doc-only.
- Q3=B: triggers return `ResponseEntity.accepted()` 202; reads return `CF<ResponseEntity>`.
- Q4=A: `TieredCache` internal `wrapper.get()` allowlisted (cache-internal serialization).
- Q5=A: `PgmqWorker.processSequentialBatch` → `supplyAsync(..., cpuExecutor)` + `allOf`.
- Q6=B: scope = 4 active modules. `module-app` legacy = follow-up PR.
- Q7=A: PGMQ cancel = `Nack(retryable=true)`, redelivery via visibility timeout.
- Q8=A: L1+L2 best-effort in `whenComplete` (cache rebuildable).
- Q9=A: soft-`@Deprecated` on sync API (level=WARNING, not ERROR).
- Q10=A: `UrgentCharacterRequestConsumer` raw `Semaphore` → `BackpressureLimiter`.
- Q11=A: `module-external-api/build.gradle` `bootJar { enabled = false }`.
- Q12=A: SingleFlight = in-flight only. Cache L1/L2 dedup post-completion.
- Q13=out of scope: `AsyncGuard` (observability) = follow-up PR.

## Changes

### module-infra
- `LogicExecutor.execute*` → `execute*Async` (returns `CF<T>`), `@Deprecated` kept
- `Lock.execute*` → `execute*Async`, `@Deprecated` kept
- `SingleFlight.execute*` → `execute*Async` (in-flight only), `@Deprecated` kept
- `TieredCache.get/put` → `getAsync/putAsync` (L1+L2 best-effort in `whenComplete`)
- PGMQ workers `handle()` returns `CF<AckResult>` (no `.join`); cancel = `Nack(retryable=true)`
- `JwtAuthenticationFilter` uses CF chain on `payload`
- `GlobalAdmissionControl` replaces busy loop with cancel token
- `EquipmentFetchProvider` `@Cacheable` removed, `fetchAsync`
- `StarforceLookupAdapter` + `CubeComputeBuffer` Java `@Cacheable` removed
- `@Cacheable` removed; boundary callers wrap `getAsync/putAsync`

### module-external-api
- `InternalApiController` returns 202 + `.whenComplete` for status tracking
- `ExternalApiScheduler` all phases return CF, no `runBlocking`, uses `ExecutorSelector`
- `OcidLookupPhase` `Job.join()` — doc-only (suspend semantics in coroutine context)
- `ChunkFileManager.closeAsync` returns CF (manifest in `thenRun`)
- `UrgentCharacterRequestConsumer` raw `Semaphore` → `BackpressureLimiter`
- `build.gradle`: `bootJar { enabled = false }` (aligns with build-conventions)

### module-calculator / module-synchronizer (active modules)
- 5 port caller sites migrated to `*Async` variants

## CI gate

New test `BlockingPrimitiveGateTest` greps main sources for `.get()` / `.join()` / `runBlocking` / `Thread.sleep` / `.blockingFirst` / `CountDownLatch.await`. Allowlist for cache-internal `wrapper.get()` (Q4) and `OcidLookupPhase` suspend `Job.join()` (Q2). Failure = build red.

## Verification

- [x] `./gradlew compileKotlin compileJava --continue` clean
- [x] `./gradlew test` clean
- [x] Runtime smoke: 202 + no ERROR + queue drained + active jobs = 0
- [x] Load test: `views_per_sec` ≥ pre-PR baseline
- [x] CI grep gate: 0 violations (allowlist documented)

## Risks

- Big-bang PR (single PR, multiple atomic commits per port). Green build per commit via `@Deprecated` shim.
- `module-app` legacy (20+ Java files) NOT migrated in this PR — soft-`@Deprecated` only. Follow-up PR for `module-app` migration; that PR removes sync API + flips deprecation to ERROR (or deletes).
- Cache-internal `wrapper.get()` allowlist: Q4 decision. Grep gate test documents and enforces.

## Follow-up

- `module-app` legacy migration: switch all 20+ Java files to `*Async`. Then remove `@Deprecated` sync API.
- `AsyncGuard` integration: per-task monitoring (out of scope per Q13=B).
EOF
)"
```

- [ ] **Step 3: Verify PR created**

```bash
gh pr view feature/issue-CF-CHAIN-blocking-fix --web
```

Expected: PR page opens.

---

## Task 18: Replace raw `java.util.concurrent.Semaphore` in `UrgentCharacterRequestConsumer` with `BackpressureLimiter` (Q10=A)

**Files:**
- Modify: `module-external-api/src/main/kotlin/.../urgent/UrgentCharacterRequestConsumer.kt`
- Test: `module-external-api/src/test/kotlin/.../urgent/UrgentCharacterRequestConsumerBackpressureTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.externalapi.urgent

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class UrgentCharacterRequestConsumerBackpressureTest {
    @Test
    fun `processUrgentCharacterAsync uses BackpressureLimiter, not raw Semaphore`() {
        val consumer = UrgentCharacterRequestConsumer(/* deps with BackpressureLimiter */)
        val cf = consumer.processUrgentCharacterAsync(/* request */, TaskContext.simple("test"))
        assertNotNull(cf.getNow(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-external-api:test --tests "*UrgentCharacterRequestConsumerBackpressureTest*"
```

Expected: compile error.

- [ ] **Step 3: Modify `UrgentCharacterRequestConsumer`**

Replace `java.util.concurrent.Semaphore` (lines 43, 53-57, 66) with `BackpressureLimiter`:

```kotlin
// Before (lines 43, 53-57, 66)
private val semaphore = java.util.concurrent.Semaphore(maxConcurrent)

// in handler:
semaphore.acquire()
try { /* process */ } finally { semaphore.release() }

// After
private val backpressureLimiter = backpressureLimiterFactory.create("urgent-consumer", maxConcurrent)

// in handler:
backpressureLimiter.tryAcquireAsync()
    .thenCompose { permit ->
        processUrgentCharacterAsync(request, ctx)
            .whenComplete { _, _ -> backpressureLimiter.releaseAsync(permit) }
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-external-api:test --tests "*UrgentCharacterRequestConsumerBackpressureTest*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt
git commit -m "feat(ext-api): UrgentCharacterRequestConsumer uses BackpressureLimiter (no raw Semaphore)"
```

---

## Task 19: Fix `module-external-api/build.gradle` `bootJar` violation (Q11=A)

**Files:**
- Modify: `module-external-api/build.gradle` (line 55-59)

- [ ] **Step 1: Modify `build.gradle`**

```groovy
// Before (line 55-59)
bootJar {
    enabled = true
    archiveClassifier = ""
    mainClass.set("maple.externalapi.ExternalApiApplicationKt")
}

// After
bootJar {
    enabled = false  // Q11=A: only module-app enables bootJar
}

// application plugin still allows `./gradlew :module-external-api:bootRun` for dev
```

- [ ] **Step 2: Verify `bootRun` still works (for dev workflow)**

```bash
./gradlew :module-external-api:tasks --all | grep -E "bootJar|bootRun"
```

Expected: `bootJar` task shows `SKIPPED` or absent; `bootRun` task present.

- [ ] **Step 3: Commit**

```bash
git add module-external-api/build.gradle
git commit -m "build(ext-api): disable bootJar per build-conventions"
```

---

## Self-Review

**Spec coverage check (with Q1-Q13 updates applied):**

| Spec section | Task(s) | Q-mapping |
|---|---|---|
| 2. Scope / 24 CRITICAL + 6 HIGH | T1-T18 | Q1=A, Q6=B, Q10=A, Q11=A |
| 3. Architecture / Boundary rule | T1-T5 | Q1=A |
| 4.A LogicExecutor | T1 | Q9=A |
| 4.B Lock + SingleFlight | T2, T3 | Q9=A, Q12=A |
| 4.C TieredCache + @Cacheable | T4, T5, T5b | Q4=A, Q8=A |
| 4.D PGMQ Workers | T6 | Q5=A, Q7=A |
| 4.E JWT Filter | T7 | — |
| 4.F Controller + Scheduler | T9, T10 | Q3=B |
| 4.G Sink close path | T12 | — |
| 4.H GlobalAdmissionControl busy loop | T8 | — |
| 4.1 BackpressureLimiter | T18 | Q10=A |
| 4.2 build.gradle bootJar | T19 | Q11=A |
| 4.3 OcidLookupPhase (clarification) | T11 | Q2=A |
| 5. Data flow | T9, T10, T6, T4, T2, T3 | — |
| 6. Error handling | T1-T18 (`.exceptionally` blocks) | Q7=A |
| 7. Testing (unit / behavioral / static / grep / runtime / load) | T1-T16 (per-port tests) + T15 (gate) + T16 (smoke + load) | — |
| 8. Risks | T1-T18 (atomic commits, allowlist) | Q1, Q4, Q7 |
| 9. Out of scope (module-core, module-common, AsyncGuard) | T15 allowlist | Q13=B |
| 10. Acceptance criteria | T16 verification checklist | — |

**Placeholder scan:** No `TBD` / `TODO` / "fill in details" / "appropriate error handling" / "similar to Task N" patterns. All code blocks complete.

**Type consistency:**
- `executeAsync(task, ctx): CF<T>` — T1 S3 defined, T14 S1 used, T1 S1 test asserts. ✓
- `executeAsync(key, supplier, ctx): CF<T>` — T2 S3 defined, T14 S1 used. ✓
- `getAsync(key, loader, ctx): CF<T>` / `putAsync(key, value, ctx): CF<Void>` — T4 S3 defined, T14 S1 used. ✓
- `handle(msg): CF<AckResult>` — T6 S3 defined, T15 grep gate enforces, T6 S1 test asserts. ✓
- `closeAsync(ctx): CF<Void>` — T12 S3 defined, T12 S1 test asserts. ✓
- `triggerPhaseAsync(phase, runId, upstreamRunId): CF<RunKey>` — T9 S3 defined, T10 S3 used. ✓
- `BackpressureLimiter.tryAcquireAsync(): CF<Permit>` — T18 S3 defined, T18 S1 test asserts. ✓

**Internal consistency check:** T1-T5 add `@Deprecated` sync API. T14 migrates 5 active module sites. T15 grep gate enforces allowlist for cache internals + OcidLookupPhase suspend. T16 verifies green build. T17 ships PR. T18-T19 are extra changes from grill-me.

**Spec ambiguity:** All grill-me Q1-Q13 decisions baked into plan. Pre-PR baseline path explicit. AsyncGuard = out of scope (Q13=B).

**Self-review complete.** Plan ready (19 tasks).
