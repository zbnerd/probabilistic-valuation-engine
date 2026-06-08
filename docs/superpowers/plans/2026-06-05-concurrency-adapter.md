# Concurrency Adapter Package — Phase 1 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce six single-purpose concurrency adapters in `module-infra/concurrency/` with unit tests, behind a single Spring `@Configuration`. No migration of existing call sites in this phase — Phase 2 is per-domain migration.

**Architecture:** Flat package `module-infra/concurrency/` with six Kotlin interfaces + one `ConcurrencyConfiguration` registering all as Spring beans. Each adapter wraps a single concern. Unit tests use `kotlinx-coroutines-test` for virtual time and an in-memory fake `ExecutorService`.

**Tech Stack:** Kotlin, Spring Boot, kotlinx-coroutines, JUnit5, MockK (if used).

**Spec Reference:** `docs/superpowers/specs/2026-06-05-concurrency-adapter-design.md`

---

## File Structure

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/
├── LifecycleComponent.kt           (interface + default impl base)
├── BackpressureLimiter.kt          (interface + DefaultBackpressureLimiter)
├── BoundedSemaphore.kt             (interface + DefaultBoundedSemaphore)
├── ExecutorSelector.kt             (interface + DefaultExecutorSelector)
├── ThreadLauncher.kt               (interface + DefaultThreadLauncher)
├── AsyncGuard.kt                   (interface + DefaultAsyncGuard)
├── ExecutorQualifier.kt            (enum)
├── ShutdownPhase.kt                (enum)
├── ConcurrencyConfiguration.kt     (@Configuration wiring)
└── ExecutorRegistry.kt             (internal — holds per-qualifier ExecutorService)

module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/
├── LifecycleComponentTest.kt
├── BackpressureLimiterTest.kt
├── BoundedSemaphoreTest.kt
├── ExecutorSelectorTest.kt
├── ThreadLauncherTest.kt
└── AsyncGuardTest.kt
```

Each production file has one responsibility. Tests mirror production files. No test fixtures file — each test stands alone with fake executors.

---

## Task 1: Add Concurrency Package Skeleton

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorQualifier.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ShutdownPhase.kt`

- [ ] **Step 1: Create ExecutorQualifier enum**

```kotlin
package maple.expectation.infrastructure.concurrency

enum class ExecutorQualifier {
    CALCULATION,
    IO,
    SCHEDULER,
    CHUNK,
    BACKFILL
}
```

- [ ] **Step 2: Create ShutdownPhase enum**

```kotlin
package maple.expectation.infrastructure.concurrency

enum class ShutdownPhase {
    CONSUMERS,
    PRODUCERS,
    INFRA
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/
git commit -m "feat(concurrency): add ExecutorQualifier and ShutdownPhase enums"
```

---

## Task 2: LifecycleComponent Interface

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponent.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponentTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals

class LifecycleComponentTest {
    @Test
    fun `default shutdown timeout is 5000ms`() {
        val component = TestComponent("test")
        assertEquals(5_000L, component.shutdownTimeoutMs())
    }

    @Test
    fun `destroy calls drain then waits timeout`() {
        val component = TestComponent("test")
        component.destroy()
        assertTrue(component.drainCalled)
    }

    private class TestComponent(name: String) : LifecycleComponent {
        var drainCalled = false
        override fun componentName() = name
        override suspend fun drain() { drainCalled = true }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.LifecycleComponentTest" --continue`
Expected: COMPILATION FAILURE (LifecycleComponent not found)

- [ ] **Step 3: Write LifecycleComponent interface**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.springframework.beans.factory.DisposableBean

interface LifecycleComponent : DisposableBean {
    fun componentName(): String
    suspend fun drain()

    override fun destroy() {
        kotlinx.coroutines.runBlocking { drain() }
    }

    fun shutdownTimeoutMs(): Long = 5_000L
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.LifecycleComponentTest" --continue`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponent.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/LifecycleComponentTest.kt
git commit -m "feat(concurrency): add LifecycleComponent interface with default destroy"
```

---

## Task 3: BackpressureLimiter Interface

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiter.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureRejectedException.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

class BackpressureLimiterTest {
    @Test
    fun `withPermit returns block result on success`() = runTest {
        val limiter = DefaultBackpressureLimiter(permits = 1)
        val result = limiter.withPermit(timeoutMs = 1_000) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `withPermit throws BackpressureRejected on timeout`() = runTest {
        val limiter = DefaultBackpressureLimiter(permits = 1)
        val holding = kotlinx.coroutines.async { limiter.withPermit(1_000) { kotlinx.coroutines.delay(2_000) } }
        kotlinx.coroutines.delay(50)  // let first acquire
        assertThrows(BackpressureRejectedException::class.java) {
            kotlinx.coroutines.runBlocking { limiter.withPermit(50) { "never" } }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.BackpressureLimiterTest" --continue`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create BackpressureRejectedException**

```kotlin
package maple.expectation.infrastructure.concurrency

class BackpressureRejectedException(val component: String, timeoutMs: Long) :
    RuntimeException("Backpressure timeout after ${timeoutMs}ms in $component")
```

- [ ] **Step 4: Create BackpressureLimiter interface and default impl**

```kotlin
package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

interface BackpressureLimiter {
    suspend fun <T> withPermit(timeoutMs: Long, block: suspend () -> T): T
}

class DefaultBackpressureLimiter(
    private val permits: Int,
    private val component: String = "unknown"
) : BackpressureLimiter {
    private val sem = Semaphore(permits)

    override suspend fun <T> withPermit(timeoutMs: Long, block: suspend () -> T): T {
        if (!sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw BackpressureRejectedException(component, timeoutMs)
        }
        try {
            return block()
        } finally {
            sem.release()
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.BackpressureLimiterTest" --continue`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiter.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BackpressureRejectedException.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BackpressureLimiterTest.kt
git commit -m "feat(concurrency): add BackpressureLimiter with tryAcquire timeout"
```

---

## Task 4: BoundedSemaphore Interface

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphore.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphoreTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.atomic.AtomicInteger

class BoundedSemaphoreTest {
    @Test
    fun `max N concurrent blocks execute simultaneously`() = runTest {
        val sem = DefaultBoundedSemaphore(permits = 2)
        val concurrent = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val jobs = (1..5).map {
            async {
                sem.withPermit {
                    val now = concurrent.incrementAndGet()
                    maxObserved.updateAndGet { kotlin.math.max(it, now) }
                    delay(50)
                    concurrent.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(2, maxObserved.get())
    }

    @Test
    fun `availablePermits reports remaining`() = runTest {
        val sem = DefaultBoundedSemaphore(permits = 3)
        assertEquals(3, sem.availablePermits())
        sem.withPermit { /* holds nothing */ }
        assertEquals(3, sem.availablePermits())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.BoundedSemaphoreTest" --continue`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create BoundedSemaphore interface and default impl**

```kotlin
package maple.expectation.infrastructure.concurrency

import java.util.concurrent.Semaphore

interface BoundedSemaphore {
    suspend fun <T> withPermit(block: suspend () -> T): T
    fun availablePermits(): Int
}

class DefaultBoundedSemaphore(permits: Int) : BoundedSemaphore {
    private val sem = Semaphore(permits)

    override suspend fun <T> withPermit(block: suspend () -> T): T {
        sem.acquire()
        try {
            return block()
        } finally {
            sem.release()
        }
    }

    override fun availablePermits(): Int = sem.availablePermits()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.BoundedSemaphoreTest" --continue`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphore.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/BoundedSemaphoreTest.kt
git commit -m "feat(concurrency): add BoundedSemaphore with finally-guarded release"
```

---

## Task 5: ExecutorRegistry + ExecutorSelector

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorRegistry.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelector.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelectorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExecutorSelectorTest {
    @Test
    fun `submit runs block on registered executor`() {
        val exec = Executors.newSingleThreadExecutor()
        val registry = ExecutorRegistry(mapOf(ExecutorQualifier.IO to exec))
        val selector = DefaultExecutorSelector(registry)
        val counter = AtomicInteger(0)

        selector.submit(ExecutorQualifier.IO) { counter.incrementAndGet() }.get(1, TimeUnit.SECONDS)
        assertEquals(1, counter.get())

        exec.shutdown()
    }

    @Test
    fun `submit throws on unknown qualifier`() {
        val registry = ExecutorRegistry(emptyMap())
        val selector = DefaultExecutorSelector(registry)
        try {
            selector.submit(ExecutorQualifier.CALCULATION) { 1 }
            org.junit.jupiter.api.Assertions.fail("expected exception")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.ExecutorSelectorTest" --continue`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create ExecutorRegistry**

```kotlin
package maple.expectation.infrastructure.concurrency

import java.util.concurrent.ExecutorService

class ExecutorRegistry(private val executors: Map<ExecutorQualifier, ExecutorService>) {
    fun get(qualifier: ExecutorQualifier): ExecutorService =
        executors[qualifier]
            ?: throw IllegalArgumentException("No executor registered for $qualifier")
}
```

- [ ] **Step 4: Create ExecutorSelector interface and default impl**

```kotlin
package maple.expectation.infrastructure.concurrency

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

interface ExecutorSelector {
    fun <T> submit(qualifier: ExecutorQualifier, block: () -> T): CompletableFuture<T>
    fun shutdownAll(phase: ShutdownPhase)
}

class DefaultExecutorSelector(private val registry: ExecutorRegistry) : ExecutorSelector {
    override fun <T> submit(qualifier: ExecutorQualifier, block: () -> T): CompletableFuture<T> {
        val exec: ExecutorService = registry.get(qualifier)
        return CompletableFuture.supplyAsync({ block() }, exec)
    }

    override fun shutdownAll(phase: ShutdownPhase) {
        // phase-based ordering deferred to Phase 2
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.ExecutorSelectorTest" --continue`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorRegistry.kt
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelector.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ExecutorSelectorTest.kt
git commit -m "feat(concurrency): add ExecutorRegistry and ExecutorSelector"
```

---

## Task 6: ThreadLauncher

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncher.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncherTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ThreadLauncherTest {
    @Test
    fun `launch runs block asynchronously`() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val launcher = DefaultThreadLauncher(exec)
        val ran = AtomicBoolean(false)

        launcher.launch("test-task") { ran.set(true) }
        exec.shutdown()
        assertTrue(exec.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(ran.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.ThreadLauncherTest" --continue`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create ThreadLauncher interface and default impl**

```kotlin
package maple.expectation.infrastructure.concurrency

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

interface ThreadLauncher {
    fun launch(name: String, block: () -> Unit): Future<*>
}

class DefaultThreadLauncher(private val executor: ExecutorService) : ThreadLauncher {
    override fun launch(name: String, block: () -> Unit): Future<*> =
        executor.submit {
            Thread.currentThread().name = name
            block()
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.ThreadLauncherTest" --continue`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncher.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/ThreadLauncherTest.kt
git commit -m "feat(concurrency): add ThreadLauncher wrapping ExecutorService"
```

---

## Task 7: AsyncGuard

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuard.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class AsyncGuardTest {
    @Test
    fun `guard returns chain result when within timeout`() {
        val guard = DefaultAsyncGuard()
        val chain = CompletableFuture.supplyAsync({ 42 })
        val guarded = guard.guard("test", 1_000, chain)
        assertEquals(42, guarded.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `guard fails chain on timeout`() {
        val guard = DefaultAsyncGuard()
        val slow = CompletableFuture.supplyAsync {
            Thread.sleep(500)
            "late"
        }
        val guarded = guard.guard("slow-test", 50, slow)
        val ex = assertThrows(ExecutionException::class.java) {
            guarded.get(2, TimeUnit.SECONDS)
        }
        assert(ex.cause is java.util.concurrent.TimeoutException)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.AsyncGuardTest" --continue`
Expected: COMPILATION FAILURE

- [ ] **Step 3: Create AsyncGuard interface and default impl**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

interface AsyncGuard {
    fun <T> guard(name: String, timeoutMs: Long, chain: CompletableFuture<T>): CompletableFuture<T>
}

class DefaultAsyncGuard : AsyncGuard {
    private val log = LoggerFactory.getLogger(DefaultAsyncGuard::class.java)

    override fun <T> guard(name: String, timeoutMs: Long, chain: CompletableFuture<T>): CompletableFuture<T> {
        val guarded = CompletableFuture<T>()
        chain.whenComplete { result, ex ->
            if (ex != null) guarded.completeExceptionally(ex)
            else guarded.complete(result)
        }
        // schedule timeout
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "async-guard-$name").apply { isDaemon = true }
        }
        scheduler.schedule<Unit>({
            if (!guarded.isDone) {
                log.warn("AsyncGuard timeout: chain '$name' exceeded ${timeoutMs}ms")
                guarded.completeExceptionally(TimeoutException("AsyncGuard: $name exceeded ${timeoutMs}ms"))
            }
            scheduler.shutdown()
        }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return guarded
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.AsyncGuardTest" --continue`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuard.kt
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuardTest.kt
git commit -m "feat(concurrency): add AsyncGuard with timeout wrapper"
```

---

## Task 8: ConcurrencyConfiguration Wiring

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt`

- [ ] **Step 1: Create ConcurrencyConfiguration**

```kotlin
package maple.expectation.infrastructure.concurrency

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class ConcurrencyConfiguration {

    @Bean
    fun executorRegistry(): ExecutorRegistry {
        val map = mapOf(
            ExecutorQualifier.CALCULATION to namedExecutor("calc", 4, 8),
            ExecutorQualifier.IO to namedExecutor("io", 8, 16),
            ExecutorQualifier.SCHEDULER to namedExecutor("scheduler", 2, 4),
            ExecutorQualifier.CHUNK to namedExecutor("chunk", 2, 4),
            ExecutorQualifier.BACKFILL to namedExecutor("backfill", 2, 4)
        )
        return ExecutorRegistry(map)
    }

    @Bean
    fun executorSelector(registry: ExecutorRegistry): ExecutorSelector =
        DefaultExecutorSelector(registry)

    @Bean
    fun threadLauncher(registry: ExecutorRegistry): ThreadLauncher =
        DefaultThreadLauncher(registry.get(ExecutorQualifier.BACKFILL))

    @Bean
    fun backpressureLimiter(): BackpressureLimiter =
        DefaultBackpressureLimiter(permits = 16, component = "default")

    @Bean
    fun asyncGuard(): AsyncGuard = DefaultAsyncGuard()

    private fun namedExecutor(name: String, core: Int, max: Int): ThreadPoolTaskExecutor {
        val e = ThreadPoolTaskExecutor()
        e.corePoolSize = core
        e.maxPoolSize = max
        e.queueCapacity = 64
        e.setThreadNamePrefix(name)
        e.setWaitForTasksToCompleteOnShutdown(true)
        e.setAwaitTerminationSeconds(10)
        e.initialize()
        return e
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-infra:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run all concurrency tests**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.concurrency.*" --continue`
Expected: PASS (9 tests total)

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/ConcurrencyConfiguration.kt
git commit -m "feat(concurrency): wire six adapters as Spring beans"
```

---

## Task 9: Code Rule Documentation

**Files:**
- Modify: `.claude/rules/async-concurrency.md` (add rule block)

- [ ] **Step 1: Append rule block to async-concurrency.md**

Append at end of file (preserving existing content):

```markdown

## Concurrency Adapter Rule (Effective Phase 2)

All new code that needs concurrency primitives MUST use the adapters in `maple.expectation.infrastructure.concurrency`:

| Need | Adapter |
|------|---------|
| @PreDestroy / drain / shutdown | `LifecycleComponent` |
| Fast-fail under load | `BackpressureLimiter` |
| Limit concurrent execution | `BoundedSemaphore` |
| Submit work to a specific executor | `ExecutorSelector` |
| Fire-and-forget thread | `ThreadLauncher` |
| Detect slow async chain | `AsyncGuard` |

**Forbidden at call site** (enforced by reviewer):
- `new Thread()` / `Thread.ofPlatform().start { }`
- `ForkJoinPool.commonPool()` direct
- `Executors.newXxx()` direct (use `ExecutorRegistry` via `ConcurrencyConfiguration`)
- `@PreDestroy` direct (extend `LifecycleComponent` instead)
```

- [ ] **Step 2: Commit**

```bash
git add .claude/rules/async-concurrency.md
git commit -m "docs(rules): require concurrency adapters for new code"
```

---

## Task 10: Final Verification

- [ ] **Step 1: Compile entire repo**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all module-infra tests**

Run: `./gradlew :module-infra:test --continue`
Expected: BUILD SUCCESSFUL (no test failures)

- [ ] **Step 3: Boot smoke test**

Run:
```bash
set -a && source .env && set +a
./gradlew :module-rest-controller:bootRun &
sleep 60
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

Expected: HTTP 202 (request accepted)

- [ ] **Step 4: Confirm bean wiring**

Check `module-rest-controller/logs/app.log` for:
- "ConcurrencyConfiguration" bean init lines
- "ExecutorQualifier" references in startup

- [ ] **Step 5: Kill server**

```bash
pkill -f "module-rest-controller:bootRun"
```

- [ ] **Step 6: Final commit + push branch**

```bash
git log --oneline -10  # verify all 9 commits present
git push -u origin <branch-name>
gh pr create --base develop --title "feat(concurrency): introduce 6 single-purpose adapters (Phase 1)" --body-file - <<'EOF'
## Summary
Introduces `module-infra/concurrency/` package with six single-purpose adapters (LifecycleComponent, BackpressureLimiter, BoundedSemaphore, ExecutorSelector, ThreadLauncher, AsyncGuard) plus ConcurrencyConfiguration wiring them as Spring beans. No call-site migration yet — Phase 2 will move existing ad-hoc Thread/Executor/Semaphore usage one domain at a time.

## Spec
docs/superpowers/specs/2026-06-05-concurrency-adapter-design.md

## Test
9 new unit tests, all passing. Server boots, /api/v5/characters/진격캐넌/expectation returns 202.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
```

---

## Self-Review

**1. Spec coverage:**
- §3 6 adapter interfaces — Tasks 2-7 ✓
- §4 placement + Spring config — Task 8 ✓
- §6 unit tests with virtual time / fake executor — Tasks 2-7 ✓
- §7 trade-off (small adapters) — implicit in task count ✓
- §8 success signal — out of scope for Phase 1 (Phase 2 measurement) ✓
- §9 open questions (drain order) — deferred to Phase 2 ✓

**2. Placeholder scan:** No TBD / TODO / "add appropriate error handling" found. All code blocks complete.

**3. Type consistency:**
- `ExecutorQualifier` enum: defined Task 1, used Tasks 5/8 ✓
- `ShutdownPhase` enum: defined Task 1, used Tasks 5/8 ✓
- `BackpressureRejectedException`: defined Task 3, caught in Task 3 test ✓
- `LifecycleComponent.drain()` is `suspend`: test uses `runBlocking` in default `destroy()` ✓

**4. Scope:** Phase 1 is self-contained — introduces adapters without breaking any existing call site. Phase 2 will follow in a separate plan.
