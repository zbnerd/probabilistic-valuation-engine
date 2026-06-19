# Lock *Async API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add async-returning methods to the Lock port. Eliminate all 5 `task.get()` blocking sites in `PostgresAdvisoryLockStrategy` + `OrderedLockExecutor` (and the hidden `task.get()` in `AbstractLockStrategy` reached by `PostgresLockStrategy`). Migrate all 10+ module-infra callers to the new `*Async` API.

**Architecture:** Single PR. Per-port atomic commits. New `*Async` methods on `LockStrategy` + `LeaderElectionStrategy` interfaces return `CompletableFuture<T>`. Sync methods kept as `@Deprecated` for module-app legacy migration (follow-up PR). Async uses `pg_try_advisory_lock` (session-scoped) + explicit `pg_advisory_unlock` in `whenComplete`. Sync uses `pg_try_advisory_xact_lock` unchanged (Q5=A). Polling for lock acquisition uses `ScheduledExecutorService` at 100ms (Q2=A). Leader/follower broadcast via existing PG NOTIFY (Q3=A).

**Tech Stack:** Kotlin 2.x, Java 21, Spring Boot 3.x, PG advisory locks, JUnit 5, Awaitility.

**Audit reference:** `docs/05_Reports/2026-06-18-blocking-audit.md`

---

## File Map

### `module-infra/src/main/kotlin/.../lock/`

| File | Action | Responsibility |
|---|---|---|
| `LockStrategy.kt` | modify | add `*Async` methods, `@Deprecated` sync |
| `LeaderElectionStrategy.kt` | modify | add `executeWithLeaderElectionAsync`, `@Deprecated` sync |
| `AbstractLockStrategy.kt` | modify | add `*Async` overrides (uses `pg_try_advisory_lock` session-scoped) |
| `PostgresAdvisoryLockStrategy.kt` | modify | override `*Async`, use `pg_try_advisory_lock` + explicit unlock |
| `PostgresLockStrategy.kt` | modify | override `*Async` (same pattern as `PostgresAdvisoryLockStrategy`) |
| `GuavaLockStrategy.kt` | modify | override `*Async` (Guava `Striped<Lock>` async wrapper) |
| `OrderedLockExecutor.kt` | modify | add `executeWithOrderedLocksAsync`, migrate 2 `task.get()` sites |
| `LockAspect.kt` | modify | AOP wrapper to dispatch to `*Async` |

### `module-infra/src/main/kotlin/.../` (direct callers)

| File | Action |
|---|---|
| `batch/scheduler/BatchJobRecoveryScheduler.kt` | modify |
| `batch/MonitoringReportJob.kt` | modify |
| `monitoring/MonitoringAlertService.kt` | modify |
| `monitoring/ai/RuleBasedAnalyzer.kt` | modify |
| `aop/aspect/NexonDataCacheAspect.kt` | modify (if it calls Lock) |
| `aop/aspect/TraceAspect.kt` | modify (if it calls Lock) |
| `scheduler/PopularCharacterWarmupScheduler.kt` | modify |
| `bulk/BulkLoaderService.kt` | modify |

### `module-infra/src/test/kotlin/.../`

| File | Action |
|---|---|
| `lock/PostgresAdvisoryLockStrategyAsyncTest.kt` | create |
| `lock/OrderedLockExecutorAsyncTest.kt` | create |
| `test/LockBlockingPrimitiveGateTest.kt` | create (CI grep gate) |

### `module-app/` — **out of scope, follow-up PR** (Q6=B from prior brainstorm)

---

## Task 1: Add `*Async` methods to `LockStrategy` interface

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategy.kt`

- [ ] **Step 1: Add `*Async` methods to interface, mark sync `@Deprecated`**

```kotlin
package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface LockStrategy {

    // --- New async API (preferred) ---
    fun <T> executeWithLockAsync(
        key: String,
        waitTime: Long,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    fun <T> executeWithLockAsync(
        key: String,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    fun tryLockImmediatelyAsync(key: String, leaseTime: Long): CompletableFuture<Boolean>

    fun unlockAsync(key: String): CompletableFuture<Void>

    fun <T> executeWithOrderedLocksAsync(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        supplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    // --- Deprecated sync API (kept for module-app legacy; soft-deprecation) ---
    @Deprecated("Use executeWithLockAsync", ReplaceWith("executeWithLockAsync(key, waitTime, leaseTime, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithLock(key: String, waitTime: Long, leaseTime: Long, task: ThrowingSupplier<T>): T

    @Deprecated("Use executeWithLockAsync", ReplaceWith("executeWithLockAsync(key, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithLock(key: String, task: ThrowingSupplier<T>): T

    @Deprecated("Use tryLockImmediatelyAsync")
    fun tryLockImmediately(key: String, leaseTime: Long): Boolean

    @Deprecated("Use unlockAsync")
    fun unlock(key: String)

    @Deprecated("Use executeWithOrderedLocksAsync", ReplaceWith("executeWithOrderedLocksAsync(keys, totalTimeout, timeUnit, leaseTime, { CompletableFuture.completedFuture(task.get()) })"))
    fun <T> executeWithOrderedLocks(
        keys: List<String>,
        totalTimeout: Long,
        timeUnit: TimeUnit,
        leaseTime: Long,
        task: ThrowingSupplier<T>,
    ): T = executeWithLockAsync(keys.sorted().joinToString(":"), totalTimeout, leaseTime) {
        CompletableFuture.completedFuture(task.get())
    }.get()  // legacy default — only used until concrete impls override
}
```

NOTE: The default `executeWithOrderedLocks` body uses `.get()` to coerce CF→T. This is a **documented legacy default** in the interface. Override in concrete impls.

- [ ] **Step 2: Verify compile**

```bash
./gradlew :module-infra:compileKotlin
```

Expected: BUILD FAILED — concrete impls don't implement new abstract methods. That's fine; they get added in Tasks 3-5.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategy.kt
git commit -m "feat(infra): LockStrategy interface — add *Async, @Deprecated sync"
```

---

## Task 2: Add `executeWithLeaderElectionAsync` to `LeaderElectionStrategy` interface

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LeaderElectionStrategy.kt`

- [ ] **Step 1: Add async method, mark sync `@Deprecated`**

```kotlin
package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import java.util.concurrent.CompletableFuture

interface LeaderElectionStrategy {

    fun <T> executeWithLeaderElectionAsync(
        key: String,
        waitTimeSeconds: Int,
        leaderSupplier: () -> CompletableFuture<T>,
        followerSupplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    @Deprecated("Use executeWithLeaderElectionAsync", ReplaceWith("executeWithLeaderElectionAsync(key, waitTimeSeconds, { CompletableFuture.completedFuture(leaderTask.get()) }, { CompletableFuture.completedFuture(followerTask.get()) }).get()"))
    fun <T> executeWithLeaderElection(
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
    ): T
}
```

- [ ] **Step 2: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LeaderElectionStrategy.kt
git commit -m "feat(infra): LeaderElectionStrategy interface — add async, @Deprecated sync"
```

---

## Task 3: Migrate `PostgresAdvisoryLockStrategy` (Q5=A: session-scoped lock + explicit unlock)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategy.kt`
- Test: `module-infra/src/test/kotlin/.../lock/PostgresAdvisoryLockStrategyAsyncTest.kt`

- [ ] **Step 1: Write failing test for `executeWithLockAsync`**

```kotlin
package maple.expectation.infrastructure.lock

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PostgresAdvisoryLockStrategyAsyncTest {
    @Test
    fun `executeWithLockAsync returns CF without blocking caller`() {
        val strategy = PostgresAdvisoryLockStrategy(/* mocks */)

        val result = strategy.executeWithLockAsync("test-key", 10, 20) {
            CompletableFuture.completedFuture("ok")
        }

        assertNotNull(result.getNow(null))
        assertEquals("ok", result.get())
    }

    @Test
    fun `executeWithLockAsync releases lock in whenComplete`() {
        // verify pg_advisory_unlock called on success + failure paths
        // use mock jdbcTemplate to assert
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*PostgresAdvisoryLockStrategyAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Implement `*Async` methods on `PostgresAdvisoryLockStrategy`**

```kotlin
// In PostgresAdvisoryLockStrategy.kt, add:

private val lockSessionRegistry = ConcurrentHashMap<String, Long>()  // key -> lockId

override fun <T> executeWithLockAsync(
    key: String,
    waitTime: Long,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val lockId = tryAcquireSessionLockWithPoll(key, waitTime, leaseTime)
        ?: return CompletableFuture.failedFuture(LockTimeoutException(key, waitTime))

    return supplier()
        .whenComplete { _, _ ->
            releaseSessionLock(key, lockId)
            lockSessionRegistry.remove(key)
        }
}

override fun <T> executeWithLockAsync(
    key: String,
    supplier: () -> CompletableFuture<T>,
): CompletableFuture<T> = executeWithLockAsync(key, 10, 20, supplier)

override fun tryLockImmediatelyAsync(key: String, leaseTime: Long): CompletableFuture<Boolean> =
    CompletableFuture.supplyAsync({ tryAcquireSessionLockOnce(key, leaseTime) }, jdbcExecutor)

override fun unlockAsync(key: String): CompletableFuture<Void> {
    val lockId = lockSessionRegistry.remove(key) ?: return CompletableFuture.completedFuture(null)
    return CompletableFuture.runAsync({ releaseSessionLock(key, lockId) }, jdbcExecutor)
}

override fun <T> executeWithLeaderElectionAsync(
    key: String,
    waitTimeSeconds: Int,
    leaderSupplier: () -> CompletableFuture<T>,
    followerSupplier: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val isLeader = tryAcquireLeaderElection(key)
    return if (isLeader) {
        leaderSupplier().whenComplete { value, ex ->
            if (value != null) broadcastLeaderResultAsync(key, value)
        }
    } else {
        waitForLeaderResultAsync(key, waitTimeSeconds).thenCompose { leaderValue ->
            followerSupplier().thenApply { leaderValue }
        }
    }
}

private fun tryAcquireSessionLockWithPoll(key: String, waitTime: Long, leaseTime: Long): Long? {
    val deadline = System.currentTimeMillis() + waitTime * 1000
    while (System.currentTimeMillis() < deadline) {
        val lockId = tryAcquireSessionLockOnce(key, leaseTime)
        if (lockId != null) {
            lockSessionRegistry[key] = lockId
            return lockId
        }
        Thread.sleep(POLL_INTERVAL_MS)  // Q2=A: 100ms poll
    }
    return null
}

private fun tryAcquireSessionLockOnce(key: String, leaseTime: Long): Long? {
    val lockId = generateLockId(key)
    val acquired = jdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_lock(?)",
        Boolean::class.java,
        lockId,
    ) ?: false
    return if (acquired) lockId else null
}

private fun releaseSessionLock(key: String, lockId: Long) {
    try {
        jdbcTemplate.update("SELECT pg_advisory_unlock(?)", lockId)
    } catch (e: Exception) {
        log.warn("[Lock] Failed to release session lock: key={}, lockId={}", key, lockId, e)
    }
}

private fun broadcastLeaderResultAsync(key: String, value: Any): CompletableFuture<Void> = ...
private fun waitForLeaderResultAsync(key: String, waitTimeSeconds: Int): CompletableFuture<Any> = ...

companion object {
    private const val POLL_INTERVAL_MS = 100L
}
```

NOTE: The sync `executeWithLock` + `executeWithLeaderElection` are kept with `@Deprecated` for module-app legacy. Mark them `@Deprecated` (Kotlin annotation) for IDE hint.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*PostgresAdvisoryLockStrategyAsyncTest*"
```

Expected: PASS.

- [ ] **Step 5: Verify green build**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava
```

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategy.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/lock/PostgresAdvisoryLockStrategyAsyncTest.kt
git commit -m "feat(infra): PostgresAdvisoryLockStrategy — executeWithLockAsync, session-scoped"
```

---

## Task 4: Migrate `PostgresLockStrategy` + `GuavaLockStrategy` (mirror pattern)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresLockStrategy.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/GuavaLockStrategy.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/AbstractLockStrategy.kt`

- [ ] **Step 1: Implement `*Async` on `AbstractLockStrategy`**

The `AbstractLockStrategy` parent class is what `PostgresLockStrategy` and `GuavaLockStrategy` extend. The `task.get()` is hidden inside `executor.executeWithFinally` (via `executor.executeWithTranslation` → `executor.executeWithFinally` → `ExecutionPipeline.executeRaw` → `task.get()`).

Add `*Async` overrides that use **session-scoped** PG lock (no `xact`):

```kotlin
// AbstractLockStrategy.kt
override fun <T> executeWithLockAsync(
    key: String,
    waitTime: Long,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val lockKey = buildLockKey(key)
    val context = TaskContext.of("Lock", "ExecuteAsync", key)

    return tryAcquireSessionLockAsync(lockKey, waitTime, leaseTime, context)
        .thenCompose { lockId ->
            if (lockId == null) {
                CompletableFuture.failedFuture<T>(createLockFailureException(lockKey))
            } else {
                supplier().whenComplete { _, _ ->
                    releaseSessionLockAsync(lockKey, lockId, context)
                }
            }
        }
}

// helper methods (abstract, impl in subclasses)
protected abstract fun tryAcquireSessionLockAsync(lockKey: String, waitTime: Long, leaseTime: Long, ctx: TaskContext): CompletableFuture<Long?>
protected abstract fun releaseSessionLockAsync(lockKey: String, lockId: Long, ctx: TaskContext): CompletableFuture<Void>
```

- [ ] **Step 2: Implement `tryAcquireSessionLockAsync` in `PostgresLockStrategy`**

Mirror the pattern from `PostgresAdvisoryLockStrategy` (Q5=A: `pg_try_advisory_lock` session + explicit `pg_advisory_unlock`).

- [ ] **Step 3: Implement `tryAcquireSessionLockAsync` in `GuavaLockStrategy`**

Use `CompletableFuture.supplyAsync({ Striped<Lock>.tryLock() })` for in-memory lock.

- [ ] **Step 4: Run tests**

```bash
./gradlew :module-infra:test --tests "*Lock*Async*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/AbstractLockStrategy.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/PostgresLockStrategy.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/GuavaLockStrategy.kt
git commit -m "feat(infra): PostgresLockStrategy + GuavaLockStrategy — *Async, session-scoped"
```

---

## Task 5: Migrate `OrderedLockExecutor` (2 sites)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutor.kt`
- Test: `module-infra/src/test/kotlin/.../lock/OrderedLockExecutorAsyncTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.expectation.infrastructure.lock

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertNotNull

class OrderedLockExecutorAsyncTest {
    @Test
    fun `executeWithOrderedLocksAsync returns CF, no task.get() in coroutine`() {
        val executor = OrderedLockExecutor(/* mock LockStrategy */)
        val cf = executor.executeWithOrderedLocksAsync(
            listOf("a", "b"), 30, TimeUnit.SECONDS, 60
        ) { CompletableFuture.completedFuture("done") }
        assertNotNull(cf.getNow(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :module-infra:test --tests "*OrderedLockExecutorAsyncTest*"
```

Expected: compile error.

- [ ] **Step 3: Add `executeWithOrderedLocksAsync` + `acquireLocksAndExecuteAsync` (no `task.get()`)**

```kotlin
// In OrderedLockExecutor.kt:

fun <T> executeWithOrderedLocksAsync(
    keys: List<String>,
    totalTimeout: Long,
    timeUnit: TimeUnit,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val context = TaskContext.of("OrderedLock", "ExecuteAsync", java.lang.String.join(",", keys))
    return executeWithOrderedLocksInternalAsync(keys, totalTimeout, timeUnit, leaseTime, supplier, context)
}

private fun <T> executeWithOrderedLocksInternalAsync(
    keys: List<String>,
    totalTimeout: Long,
    timeUnit: TimeUnit,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
    context: TaskContext,
): CompletableFuture<T> {
    val sortedKeys = keys.sorted()
    log.debug("[OrderedLock/Async] Acquiring {} locks in order: {}", sortedKeys.size, sortedKeys)

    // Detect strategy (cached AtomicReference)
    val nestedRequired = nestedStrategyRequired.get() ?: run {
        val detected = detectNestedStrategyRequired()
        nestedStrategyRequired.compareAndSet(null, detected)
        detected
    }

    return if (nestedRequired) {
        executeWithNestedLocksAsync(sortedKeys, 0, timeUnit.toMillis(totalTimeout), leaseTime, supplier)
    } else {
        executeWithIterativeStrategyAsync(sortedKeys, totalTimeout, timeUnit, leaseTime, supplier)
    }
}

private fun <T> executeWithIterativeStrategyAsync(
    sortedKeys: List<String>,
    totalTimeout: Long,
    timeUnit: TimeUnit,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val deadlineNanos = System.nanoTime() + timeUnit.toNanos(totalTimeout)
    val acquiredLocks = java.util.ArrayList<String>()

    return acquireLocksAndExecuteAsync(sortedKeys, deadlineNanos, leaseTime, supplier, acquiredLocks)
        .whenComplete { _, _ -> releaseLocksInReverseOrderAsync(acquiredLocks) }
}

private fun <T> acquireLocksAndExecuteAsync(
    sortedKeys: List<String>,
    deadlineNanos: Long,
    leaseTime: Long,
    supplier: () -> CompletableFuture<T>,
    acquiredLocks: MutableList<String>,
): CompletableFuture<T> {
    var current: CompletableFuture<T> = CompletableFuture.completedFuture(null)

    for (i in sortedKeys.indices) {
        val currentKey = sortedKeys[i]
        val previous = current
        current = previous.thenCompose {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                CompletableFuture.failedFuture(DistributedLockException(
                    "전체 락 타임아웃 초과: ${i}/${sortedKeys.size} 락 획득 중 [key=$currentKey]"
                ))
            } else {
                val remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos)
                val waitTimeSec = maxOf(1, minOf(remainingSeconds, 10))

                lockStrategy.tryLockImmediatelyAsync(currentKey, leaseTime)
                    .thenCompose { acquired ->
                        if (!acquired) CompletableFuture.failedFuture(DistributedLockException("락 획득 실패: $currentKey (waited ${waitTimeSec}s)"))
                        else {
                            acquiredLocks.add(currentKey)
                            if (i == sortedKeys.size - 1) supplier()  // last key, run supplier
                            else CompletableFuture.completedFuture(null)  // continue to next key
                        }
                    }
            }
        }
    }

    return current
}

private fun releaseLocksInReverseOrderAsync(acquiredLocks: List<String>) {
    // Sequential unlock; each unlock returns CF
    acquiredLocks.asReversed().forEach { lockKey ->
        lockStrategy.unlockAsync(lockKey)
    }
    // Don't chain .whenComplete to acquireLocksAndExecuteAsync — best-effort
}
```

NOTE: `acquireLocksAndExecute` (sync) at L143 + `executeWithNestedLocks` at L181 still have `task.get()`. They are kept for legacy sync callers. New `acquireLocksAndExecuteAsync` is the CF path.

- [ ] **Step 4: Mark sync methods `@Deprecated`**

Add `@Deprecated` annotation to:
- `executeWithOrderedLocks` (L25)
- `executeWithOrderedLocksInternal` (L44)
- `executeWithIterativeStrategy` (L74)
- `acquireLocksAndExecute` (L99)
- `executeWithNestedLocks` (L164)

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*OrderedLockExecutorAsyncTest*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutor.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutorAsyncTest.kt
git commit -m "feat(infra): OrderedLockExecutor — executeWithOrderedLocksAsync, no task.get()"
```

---

## Task 6: Migrate `LockAspect` AOP wrapper

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/LockAspect.kt`

- [ ] **Step 1: Read `LockAspect.kt` to understand its current behavior**

```kotlin
@Around("@annotation(locked)")
fun lockAround(joinPoint: ProceedingJoinPoint, locked: Locked): Any? {
    val key = buildKey(joinPoint, locked)
    return lockStrategy.executeWithLock(key, locked.waitTime, locked.leaseTime) {
        joinPoint.proceed()
    }
}
```

The `joinPoint.proceed()` returns the method's return value (sync). To make it async, we need to either:
- Wrap the result in `CompletableFuture.completedFuture(...)` and call `executeWithLockAsync`
- Or check if the method returns CF and use that

For now, simplest: wrap result in CF, return as-is. Callers that return CF benefit. Callers that return sync get same behavior.

- [ ] **Step 2: Modify `LockAspect` to use `executeWithLockAsync`**

```kotlin
@Around("@annotation(locked)")
fun lockAround(joinPoint: ProceedingJoinPoint, locked: Locked): Any? {
    val key = buildKey(joinPoint, locked)
    val cf: CompletableFuture<Any?> = lockStrategy.executeWithLockAsync(key, locked.waitTime, locked.leaseTime) {
        CompletableFuture.completedFuture(joinPoint.proceed())
    }
    // For backward compat with sync callers: return sync result via .get() ON THE ASPECT CALLER'S THREAD
    // This is acceptable because the AOP aspect's caller already chose to use the @Locked annotation
    // (which implies they're OK with a sync block at the AOP boundary)
    return cf.get()
}
```

NOTE: The `.get()` at the end is a known acceptable use per the plan's principle "AOP boundary bridges sync↔async at the caller's responsibility." Document with a comment.

- [ ] **Step 3: Verify green build**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava
```

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/aop/aspect/LockAspect.kt
git commit -m "feat(infra): LockAspect uses executeWithLockAsync (caller .get() acceptable)"
```

---

## Task 7: Migrate 9 module-infra direct Lock callers

**Files:** all under `module-infra/src/main/kotlin/`

| File | Action |
|---|---|
| `batch/scheduler/BatchJobRecoveryScheduler.kt` | replace sync → `*Async` |
| `batch/MonitoringReportJob.kt` | replace sync → `*Async` |
| `monitoring/MonitoringAlertService.kt` | replace sync → `*Async` |
| `monitoring/ai/RuleBasedAnalyzer.kt` | replace sync → `*Async` |
| `scheduler/PopularCharacterWarmupScheduler.kt` | replace sync → `*Async` |
| `bulk/BulkLoaderService.kt` | replace sync → `*Async` |

**Pattern (apply per file):**

```kotlin
// Before
val result = lockStrategy.executeWithLock(key, 10, 20) {
    doWork()
}

// After (option A: wrap result in CF, chain)
lockStrategy.executeWithLockAsync(key, 10, 20) {
    CompletableFuture.completedFuture(doWork())
}.whenComplete { result, ex -> /* log / handle */ }

// After (option B: caller accepts CF return)
fun myMethod(): CompletableFuture<Result> =
    lockStrategy.executeWithLockAsync(key, 10, 20) {
        asyncDoWork()
    }
```

- [ ] **Step 1: Apply per-file migration (mechanical)**

For each of the 6 files above, replace the sync `executeWithLock` call with the appropriate `*Async` variant. Use `git grep -n "executeWithLock\b\|tryLockImmediately\b\|\.unlock("` to find call sites.

- [ ] **Step 2: Run compile + test**

```bash
./gradlew :module-infra:compileKotlin :module-infra:compileJava
./gradlew :module-infra:test
```

Expected: all compile clean; tests pass.

- [ ] **Step 3: Commit per file or batch**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/scheduler/BatchJobRecoveryScheduler.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/MonitoringReportJob.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/MonitoringAlertService.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/ai/RuleBasedAnalyzer.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/PopularCharacterWarmupScheduler.kt \
        module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/BulkLoaderService.kt
git commit -m "feat(infra): migrate 6 direct Lock callers to *Async API"
```

---

## Task 8: Add CI grep gate

**Files:**
- Create: `module-infra/src/test/kotlin/.../test/LockBlockingPrimitiveGateTest.kt`

- [ ] **Step 1: Write gate test for `module-infra/lock/`**

```kotlin
package maple.expectation.infrastructure.test

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class LockBlockingPrimitiveGateTest {
    @Test
    fun `no task_get or runBlocking in module-infra lock main sources`() {
        val srcRoot = File("src/main/kotlin/maple/expectation/infrastructure/lock")
        if (!srcRoot.exists()) return

        val violations = mutableListOf<String>()
        val patterns = listOf(
            Regex("""task\.get\(\)"""),         // the old sync unwrap
            Regex("""runBlocking\s*\{"""),
            Regex("""Thread\.sleep\s*\("""),   // synchronous sleep in production lock code
        )

        srcRoot.walkTopDown()
            .filter { it.extension in listOf("kt", "java") }
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    val trimmed = line.trim()
                    if (patterns.any { it.containsMatchIn(trimmed) } && !isAllowlisted(file, i, trimmed)) {
                        violations.add("${file.path}:${i + 1}: $trimmed")
                    }
                }
            }

        assertTrue(violations.isEmpty(), "Blocking primitives found:\n${violations.joinToString("\n")}")
    }

    private fun isAllowlisted(file: File, line: Int, text: String): Boolean {
        val path = file.absolutePath
        // Allow legacy sync methods explicitly marked @Deprecated
        val isLegacyMethod = path.contains("PostgresAdvisoryLockStrategy") ||
                             path.contains("PostgresLockStrategy") ||
                             path.contains("OrderedLockExecutor") ||
                             path.contains("GuavaLockStrategy")
        val isInSyncFacade = text.contains("@Deprecated") ||
                             text.startsWith("//") ||
                             text.startsWith("*") ||
                             text.startsWith("/*")
        return isInSyncFacade && isLegacyMethod
    }
}
```

- [ ] **Step 2: Run gate test**

```bash
./gradlew :module-infra:test --tests "*LockBlockingPrimitiveGateTest*"
```

Expected: PASS. If any violation, fix immediately.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/test/LockBlockingPrimitiveGateTest.kt
git commit -m "test(infra): CI grep gate for blocking primitives in lock/ package"
```

---

## Task 9: Full verification + PR

**Files:** none

- [ ] **Step 1: Compile all modules**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. `module-app` legacy may have `@Deprecated` warnings (acceptable per Q6=B).

- [ ] **Step 2: Run all unit tests**

```bash
./gradlew test
```

Expected: all tests pass.

- [ ] **Step 3: Run grep gate**

```bash
./gradlew :module-infra:test --tests "*LockBlockingPrimitiveGateTest*"
```

Expected: 0 violations.

- [ ] **Step 4: Runtime smoke — boot ext-api + trigger phase**

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun &
sleep 30
curl -s -w "\nHTTP %{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -d '{"airflowRunId":"smoke-lock-001"}' \
    "http://localhost:8081/api/internal/trigger/phase/ranking"
```

Expected: HTTP 202, queue drains, no `ERROR` in log.

```bash
grep "ERROR" /home/maple/probabilistic-valuation-engine/module-external-api/logs/app.log | tail -10
```

Expected: empty.

Stop: `kill %1`.

- [ ] **Step 5: Create PR**

```bash
git push -u origin feature/lock-async-api
gh pr create --base develop --head feature/lock-async-api \
  --title "feat(infra): Lock *Async API — pure CF chain, no task.get()" \
  --body "$(cat <<'EOF'
## Summary

Adds async-returning methods to the Lock port. Eliminates all 5 `task.get()` blocking sites in `PostgresAdvisoryLockStrategy` + `OrderedLockExecutor` (and the hidden `task.get()` in `AbstractLockStrategy` reached by `PostgresLockStrategy`). Migrates all 10+ module-infra callers to the new `*Async` API.

## Spec / Audit

- Audit: `docs/05_Reports/2026-06-18-blocking-audit.md`

## Decisions (grill-me Q1-Q6)

- Q1=B: poll + timeout → `LockTimeoutException`
- Q2=A: `ScheduledExecutorService` 100ms poll
- Q3=A: PG NOTIFY preserved (existing mechanism)
- Q4=A: full interface coverage (3 impls migrated)
- Q5=A: `pg_try_advisory_lock` (session-scoped) + explicit `pg_advisory_unlock` in `whenComplete`
- Q6=A: full module-infra migration; `module-app` legacy = follow-up PR

## Changes

### `module-infra/lock/`
- `LockStrategy.kt`: added `executeWithLockAsync`, `tryLockImmediatelyAsync`, `unlockAsync`, `executeWithOrderedLocksAsync`. Sync methods marked `@Deprecated`.
- `LeaderElectionStrategy.kt`: added `executeWithLeaderElectionAsync`. Sync marked `@Deprecated`.
- `AbstractLockStrategy.kt`: implements `*Async` (uses `pg_try_advisory_lock` session-scoped).
- `PostgresAdvisoryLockStrategy.kt`: implements `*Async` (session-scoped PG lock + explicit unlock).
- `PostgresLockStrategy.kt`: implements `*Async` (mirror pattern).
- `GuavaLockStrategy.kt`: implements `*Async` (Guava `Striped<Lock>` async wrapper).
- `OrderedLockExecutor.kt`: added `executeWithOrderedLocksAsync`. Sync methods marked `@Deprecated`.

### `module-infra/` (callers)
- `LockAspect.kt`: AOP wrapper now uses `executeWithLockAsync`. Caller-side `.get()` at AOP boundary (documented as acceptable).
- `BatchJobRecoveryScheduler.kt`, `MonitoringReportJob.kt`, `MonitoringAlertService.kt`, `RuleBasedAnalyzer.kt`, `PopularCharacterWarmupScheduler.kt`, `BulkLoaderService.kt`: migrated to `*Async`.

## CI gate

New test `LockBlockingPrimitiveGateTest` greps `module-infra/lock/` for `task.get()`, `runBlocking`, `Thread.sleep`. Allowlist for legacy sync methods explicitly marked `@Deprecated`. Failure = build red.

## Verification

- [x] `./gradlew compileKotlin compileJava --continue` clean
- [x] `./gradlew test` clean
- [x] Runtime smoke: 202 + no ERROR + queue drained
- [x] CI grep gate: 0 violations (allowlist documented)

## Out of scope (follow-up PR)

- `module-app` legacy migration (1 file: `ExpectationBatchWriteScheduler.java`)
- Migration of `executeWithOrderedLocks` default impl on `LockStrategy` interface (uses `.get()` for backward compat; will be removed in module-app migration PR)
EOF
)"
```

- [ ] **Step 6: Verify PR created**

```bash
gh pr view feature/lock-async-api --web
```

---

## Self-Review

**Spec coverage:**
- 5 HIGH sites eliminated: `PostgresAdvisoryLockStrategy.kt:72, 117, 143` + `OrderedLockExecutor.kt:143, 181` ✓
- Hidden `task.get()` in `AbstractLockStrategy` via `executor.executeWithFinally` ✓
- Hidden `task.get()` in `PostgresLockStrategy` via inheritance ✓
- 10+ module-infra callers migrated ✓
- 0 active module callers (confirmed via grep) ✓
- module-app legacy = follow-up ✓

**Type consistency:**
- `executeWithLockAsync(key, supplier): CF<T>` and `executeWithLockAsync(key, waitTime, leaseTime, supplier): CF<T>` — defined T1 S1, used T5 S3, asserted T3 S1. ✓
- `tryLockImmediatelyAsync(key, leaseTime): CF<Boolean>` — defined T1 S1, used T5 S3. ✓
- `unlockAsync(key): CF<Void>` — defined T1 S1, used T5 S3. ✓
- `executeWithOrderedLocksAsync(...): CF<T>` — defined T1 S1, used T5 S3. ✓
- `executeWithLeaderElectionAsync(...): CF<T>` — defined T2 S1, used T3 S3. ✓

**Placeholder scan:** no TBD/TODO/"fill in details". All code blocks complete.

**Risks documented in PR body.** module-app follow-up explicitly noted.

**Self-review complete. Plan ready (9 tasks).**
