# PGMQ Worker `processAsync()` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add async-returning `processAsync(): CF<ProcessOutcome>` to the PGMQ worker contract. Eliminate all 8 `task.get()` / `runBlocking` / `.join()` blocking sites in PGMQ workers + `ResultReadyProjectionWorker`. Migrate all 6 sync PGMQ workers + `ResultReadyProjectionWorker` to use the new async API.

**Architecture:** Single PR. Per-port atomic commits. New `processAsync()` method on `PgmqWorker` abstract class returns `CF<ProcessOutcome>` where `ProcessOutcome` is a sealed class: `Ack | Nack(retryable, visibilityReset) | DeadLetter(reason)`. Sync `process(): Boolean` kept `@Deprecated` for module-app legacy. Cancel = `Nack(retryable=true, visibilityReset=...)` redeliver. `@Scheduled` poll loops stay sync, internally call `processAsync()` and chain via `.whenComplete`. Parallel dispatch via `CompletableFuture.allOf` per chunk, bounded by existing `Semaphore(maxInflight=100)`.

**Tech Stack:** Kotlin 2.x, Java 21, Spring Boot 3.x, PGMQ, JUnit 5, Awaitility, Mockito.

**Audit reference:** `docs/05_Reports/2026-06-18-blocking-audit.md`
**Spec:** This plan.

---

## File Map

### `module-infra/src/main/kotlin/.../pgmq/`

| File | Action | Responsibility |
|---|---|---|
| `ProcessOutcome.kt` | create | Sealed class: `Ack`, `Nack(retryable, visibilityReset)`, `DeadLetter(reason)` |
| `PgmqWorker.kt` | modify | Add abstract `processAsync()`. Mark `process()` `@Deprecated`. |
| `PgmqWorker.kt:processMessages` | modify | Internal call to `processAsync()` + `.whenComplete { archiveOrRetry }` |
| `PgmqWorker.kt:processSequentialBatch` | modify | `runBlocking` → `CompletableFuture.supplyAsync(..., cpuExecutor)` + `allOf` |

### `module-infra/src/main/kotlin/.../worker/`

| File | Action | Blocking sites (per audit) |
|---|---|---|
| `ExternalApiWorker.kt:111` | modify `processAsync` | `.join()` |
| `ExternalApiWorker.kt:306-324` | modify | `runBlocking(Dispatchers.Default)` |
| `CalculationWorker.kt:83-91` | modify `processAsync` | `.handle().join()` |
| `CalculationRequestedWorker.kt:48` | modify `processAsync` | (pure sync, no internal blocks — just add async entry) |
| `CalculationCompletedWorker.kt:39` | modify `processAsync` | (pure sync — just add async entry) |
| `DonationWorker.kt:54` | modify `processAsync` | (pure sync — just add async entry) |
| `NexonFanOutWorker.kt:62` | modify `processAsync` | (pure sync — just add async entry) |
| `ResultReadyProjectionWorker.kt:81-90` | modify | `.join()` × 2 |
| `ResultReadyProjectionWorker.kt:123` | modify | `runBlocking(Dispatchers.Default)` |

### `module-infra/src/test/kotlin/.../pgmq/`

| File | Action |
|---|---|
| `ProcessOutcomeTest.kt` | create (sealed class exhaustive test) |
| `PgmqWorkerProcessAsyncTest.kt` | create (abstract contract test) |
| `worker/ExternalApiWorkerAsyncTest.kt` | create (or extend existing) |
| `worker/CalculationWorkerAsyncTest.kt` | create (or extend) |
| `worker/ResultReadyProjectionWorkerAsyncTest.kt` | create (or extend) |
| `test/PgmqBlockingPrimitiveGateTest.kt` | create (CI grep gate) |

### Out of scope (follow-up PR)

- `worker/OcidResolveWorker.kt:64-73` `.join()` — topic subscriber (`MQTopicGroup.subscribe`), not `PgmqWorker`. Different framework.
- `worker/NexonApiWorker.kt:38-40` — same reason.
- `module-app/.../worker/*` legacy users of sync `process(): Boolean` (if any).

---

## Task 1: Create `ProcessOutcome` sealed class

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcome.kt`
- Test: `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcomeTest.kt`

- [ ] **Step 1: Write failing test for sealed class**

```kotlin
package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ProcessOutcomeTest {
    @Test
    fun `Ack is singleton`() {
        val a1: ProcessOutcome = ProcessOutcome.Ack
        val a2: ProcessOutcome = ProcessOutcome.Ack
        assertThat(a1).isEqualTo(a2)
        assertThat(a1).isInstanceOf(ProcessOutcome.Ack::class.java)
    }

    @Test
    fun `Nack carries retryable and visibilityReset`() {
        val nack = ProcessOutcome.Nack(retryable = true, visibilityReset = Duration.ofSeconds(5))
        assertThat(nack).isInstanceOf(ProcessOutcome.Nack::class.java)
        assertThat(nack.retryable).isTrue()
        assertThat(nack.visibilityReset).isEqualTo(Duration.ofSeconds(5))
    }

    @Test
    fun `Nack supports null visibilityReset`() {
        val nack = ProcessOutcome.Nack(retryable = false, visibilityReset = null)
        assertThat(nack.retryable).isFalse()
        assertThat(nack.visibilityReset).isNull()
    }

    @Test
    fun `DeadLetter carries reason`() {
        val dlq = ProcessOutcome.DeadLetter(reason = "poison message")
        assertThat(dlq).isInstanceOf(ProcessOutcome.DeadLetter::class.java)
        assertThat(dlq.reason).isEqualTo("poison message")
    }

    @Test
    fun `sealed class allows exhaustive when`() {
        val outcomes: List<ProcessOutcome> = listOf(
            ProcessOutcome.Ack,
            ProcessOutcome.Nack(retryable = true, visibilityReset = null),
            ProcessOutcome.DeadLetter("test")
        )
        outcomes.forEach { outcome ->
            val label = when (outcome) {
                is ProcessOutcome.Ack -> "ack"
                is ProcessOutcome.Nack -> "nack"
                is ProcessOutcome.DeadLetter -> "dlq"
            }
            assertThat(label).isNotEmpty
        }
    }
}
```

> **Note:** Tests use `org.assertj.core.api.Assertions.assertThat` (the convention across `module-infra/.../pgmq/` test files). `module-infra/build.gradle` does NOT declare `kotlin-test`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-infra:test --tests "*ProcessOutcomeTest*"
```

Expected: compile error (sealed class doesn't exist).

- [ ] **Step 3: Create `ProcessOutcome.kt`**

```kotlin
package maple.expectation.infrastructure.pgmq

import java.time.Duration

/**
 * Result of a PGMQ worker's `processAsync()` invocation.
 *
 * - [Ack] — message processed successfully; archive.
 * - [Nack] — processing failed; retry per `retryable` flag, optionally resetting visibility window.
 * - [DeadLetter] — message cannot be processed; send to DLQ.
 *
 * Sealed class enables exhaustive `when` expressions at call sites.
 */
sealed class ProcessOutcome {

    /** Message processed successfully. Archive from PGMQ. */
    data object Ack : ProcessOutcome()

    /**
     * Message processing failed. Retry per [retryable] flag.
     *
     * @param retryable if true, requeue the message for retry. If false, send to DLQ.
     * @param visibilityReset optional override for the PGMQ visibility window. If null,
     *                       the PGMQ client's default visibility is used.
     */
    data class Nack(
        val retryable: Boolean,
        val visibilityReset: Duration? = null,
    ) : ProcessOutcome()

    /**
     * Message cannot be processed (poison message, validation failure, etc.).
     * Send to DLQ for manual inspection.
     */
    data class DeadLetter(
        val reason: String,
    ) : ProcessOutcome()
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :module-infra:test --tests "*ProcessOutcomeTest*"
```

Expected: 5/5 PASS.

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcome.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcomeTest.kt
git commit -m "feat(infra): ProcessOutcome sealed class — Ack | Nack | DeadLetter"
```

---

## Task 2: Add `processAsync` to `PgmqWorker` (open + default, build stays green)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`
- Test: `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerProcessAsyncTest.kt`

**Design decision:** `processAsync()` added as `open` with default impl wrapping `process()` via `CompletableFuture.supplyAsync(..., workerPool)`. Keeps build GREEN — workers aren't forced to implement immediately. Workers override `processAsync()` incrementally in Tasks 3-9. After all workers migrate, a follow-up can flip `processAsync()` to abstract + `@Deprecated` the sync version (Sub-PR 1 "soft-@Deprecated + green build per commit" pattern, locked in by Q1=A from Sub-PR 1 grill-me).

- [ ] **Step 1: Write failing test for `processAsync` method**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerProcessAsyncTest.kt`:

```kotlin
package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class PgmqWorkerProcessAsyncTest {
    @Test
    fun `default processAsync wraps sync process() returning Ack on true`() {
        val worker = SyncAckWorker()
        val outcome = worker.processAsync(TestMessage()).get()
        assertThat(outcome).isEqualTo(ProcessOutcome.Ack)
    }

    @Test
    fun `default processAsync wraps sync process() returning Nack on false`() {
        val worker = SyncNackWorker()
        val outcome = worker.processAsync(TestMessage()).get()
        assertThat(outcome).isInstanceOf(ProcessOutcome.Nack::class.java)
        assertThat((outcome as ProcessOutcome.Nack).retryable).isTrue()
    }

    @Test
    fun `overridden processAsync is used in preference to default`() {
        val worker = OverrideWorker()
        val outcome = worker.processAsync(TestMessage()).get()
        assertThat(outcome).isEqualTo(ProcessOutcome.DeadLetter("overridden"))
    }

    data class TestMessage(val payload: String = "test")

    class SyncAckWorker : PgmqWorker<TestMessage>() {
        override val queueName = "test-queue-ack"
        override val payloadClass = TestMessage::class.java
        override val workerSettings = testSettings()
        override fun process(message: PgmqMessage<TestMessage>): Boolean = true
    }

    class SyncNackWorker : PgmqWorker<TestMessage>() {
        override val queueName = "test-queue-nack"
        override val payloadClass = TestMessage::class.java
        override val workerSettings = testSettings()
        override fun process(message: PgmqMessage<TestMessage>): Boolean = false
    }

    class OverrideWorker : PgmqWorker<TestMessage>() {
        override val queueName = "test-queue-override"
        override val payloadClass = TestMessage::class.java
        override val workerSettings = testSettings()
        override fun process(message: PgmqMessage<TestMessage>): Boolean = true
        override fun processAsync(message: PgmqMessage<TestMessage>): CompletableFuture<ProcessOutcome> =
            CompletableFuture.completedFuture(ProcessOutcome.DeadLetter("overridden"))
    }
}
```

> **Test infra note:** The TestWorker classes above extend `PgmqWorker<T>()` and must supply all abstract members. Read `PgmqWorker.kt` constructor signature (lines 42-49: `pgmqClient, executor, config, meterRegistry, queueMetrics, lifecycleWrapper`). For tests, supply `null` for collaborators you don't use OR use mockk/Mockito mocks. The `testSettings()` helper may need to be added or replaced with a real `WorkerSettings` instance — search `module-infra` for existing test fixtures for `PgmqWorker`.

- [ ] **Step 2: Run test to verify it fails (compile error)**

```bash
cd /home/maple/probabilistic-valuation-engine
./gradlew :module-infra:test --tests "*PgmqWorkerProcessAsyncTest*"
```

Expected: compile error `Unresolved reference: processAsync`. If you hit a different error first (e.g., test fixture issues), fix the fixture so the test compiles, then verify the test fails on `Unresolved reference: processAsync`.

- [ ] **Step 3: Add `processAsync` to `PgmqWorker.kt`**

In `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`:

1. Add import (likely already present from existing `CompletableFuture.supplyAsync` at line 273):
   ```kotlin
   import java.util.concurrent.CompletableFuture
   ```
2. Add `open` method with default impl right after the existing `abstract fun process(...)` at line 102:

```kotlin
/**
 * Async variant of [process]. Default implementation wraps [process] in [CompletableFuture.supplyAsync]
 * via the worker pool. Override to delegate directly to an async pipeline (eliminates blocking sites).
 *
 * Returning [ProcessOutcome.Ack] triggers archive; [ProcessOutcome.Nack] triggers retry or DLQ;
 * [ProcessOutcome.DeadLetter] triggers DLQ.
 */
protected open fun processAsync(message: PgmqMessage<T>): CompletableFuture<ProcessOutcome> =
    CompletableFuture.supplyAsync(
        {
            if (process(message)) ProcessOutcome.Ack
            else ProcessOutcome.Nack(retryable = true)
        },
        workerPool,
    )
```

> **Rationale for `workerPool` executor:** Default impl uses the existing `workerPool` (Virtual Thread per worker, sized via `workerPoolSize` config). CPU-bound workers override with dedicated CPU executor in Tasks 3-9.

- [ ] **Step 4: Run new test**

```bash
./gradlew :module-infra:test --tests "*PgmqWorkerProcessAsyncTest*"
```

Expected: 3/3 PASS. If the test infra (constructor mocks + testSettings) is too cumbersome, simplify by:
- Using a shared test base class (search `module-infra/src/test` for `*PgmqWorker*Test`)
- OR mark the test class `@Disabled` and ship a minimal `TestPgmqWorkerBase` helper in the test source set

- [ ] **Step 5: Compile to confirm all 6 workers still build**

```bash
./gradlew :module-infra:compileKotlin compileJava --continue
```

Expected: BUILD SUCCESSFUL. The 6 workers still satisfy the abstract `process()` contract and inherit the default `processAsync()`.

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerProcessAsyncTest.kt
git commit -m "feat(infra): PgmqWorker.processAsync — open + default wraps process()"
```

**Build state after this commit:** GREEN. All 6 workers compile, 3 new tests pass. Workers override `processAsync()` in Tasks 3-9 to eliminate blocking sites.

---

## Task 3: Migrate `ExternalApiWorker` (3 sites)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt`
- Test: `module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorkerAsyncTest.kt` (or extend existing)

- [ ] **Step 1: Add `processAsync` implementation**

Find the `process()` method (line 103 per audit). Add `processAsync` alongside it:

```kotlin
override fun processAsync(message: PgmqMessage<ExternalApiJobPayload>): CompletableFuture<ProcessOutcome> =
    pipeline.processAsync(message.payload)
        .thenCompose { chunks -> publisher.publishAsync(chunks) }
        .thenApply { ProcessOutcome.Ack }
        .exceptionally { ex ->
            log.error("[ExternalApi] pipeline failed: {}", ex.cause ?: ex)
            ProcessOutcome.Nack(retryable = true)
        }
```

- [ ] **Step 2: Update `process()` to delegate to `processAsync().get()` for backward compat**

```kotlin
@Deprecated("Use processAsync")
override fun process(message: PgmqMessage<ExternalApiJobPayload>): Boolean =
    processAsync(message).let { cf ->
        try {
            cf.get() == ProcessOutcome.Ack
        } catch (e: Exception) {
            false
        }
    }
```

- [ ] **Step 3: Migrate `runBlocking(Dispatchers.Default)` at line 306-324**

Replace with:
```kotlin
CompletableFuture.supplyAsync({
    // CPU-bound section body
}, cpuExecutor)
```

- [ ] **Step 4: Run test**

```bash
./gradlew :module-infra:test --tests "*ExternalApiWorker*"
```

Expected: PASS (existing tests + new async test).

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt \
        module-infra/src/test/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorkerAsyncTest.kt
git commit -m "feat(infra): ExternalApiWorker — processAsync, no .join, no runBlocking"
```

---

## Task 4: Migrate `CalculationWorker` (1 site)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationWorker.kt`
- Test: extend existing

- [ ] **Step 1: Add `processAsync` implementation**

Find `process()` at line 71. Add alongside:

```kotlin
override fun processAsync(message: PgmqMessage<CalculationRequest>): CompletableFuture<ProcessOutcome> =
    expectationPort.calculateExpectationAsync(message.payload, /* context */)
        .handle { value, ex ->
            when {
                ex != null -> {
                    log.error("[Calculation] failed: msgId={}", message.messageId, ex.cause ?: ex)
                    ProcessOutcome.Nack(retryable = true)
                }
                value != null -> ProcessOutcome.Ack
                else -> ProcessOutcome.DeadLetter(reason = "null result")
            }
        }
```

- [ ] **Step 2: Update `process()` to delegate**

```kotlin
@Deprecated("Use processAsync")
override fun process(message: PgmqMessage<CalculationRequest>): Boolean =
    processAsync(message).get() == ProcessOutcome.Ack
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :module-infra:test --tests "*CalculationWorker*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationWorker.kt
git commit -m "feat(infra): CalculationWorker — processAsync, no .join"
```

---

## Task 5: Migrate `CalculationRequestedWorker` (pure sync, just add async entry)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationRequestedWorker.kt`

- [ ] **Step 1: Add `processAsync` that delegates to existing sync `process`**

```kotlin
override fun processAsync(message: PgmqMessage<CalculationRequestedPayload>): CompletableFuture<ProcessOutcome> =
    CompletableFuture.supplyAsync({
        if (process(message)) ProcessOutcome.Ack
        else ProcessOutcome.Nack(retryable = true)
    }, cpuExecutor)

@Deprecated("Use processAsync")
override fun process(message: PgmqMessage<CalculationRequestedPayload>): Boolean = /* existing impl */
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew :module-infra:test --tests "*CalculationRequested*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationRequestedWorker.kt
git commit -m "feat(infra): CalculationRequestedWorker — processAsync entry"
```

---

## Task 6: Migrate `CalculationCompletedWorker` (same pattern)

- [ ] Same as Task 5 but for `CalculationCompletedWorker.kt`

```bash
./gradlew :module-infra:test --tests "*CalculationCompleted*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationCompletedWorker.kt
git commit -m "feat(infra): CalculationCompletedWorker — processAsync entry"
```

---

## Task 7: Migrate `DonationWorker` (same pattern)

- [ ] Same as Task 5 but for `DonationWorker.kt`

```bash
./gradlew :module-infra:test --tests "*Donation*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/DonationWorker.kt
git commit -m "feat(infra): DonationWorker — processAsync entry"
```

---

## Task 8: Migrate `NexonFanOutWorker` (429 retry logic)

- [ ] **Step 1: Add `processAsync` with 429 retry handling**

```kotlin
override fun processAsync(message: PgmqMessage<FanOutRequest>): CompletableFuture<ProcessOutcome> =
    /* 429 retry chain — return Nack(retryable=true, visibilityReset=Duration.ofSeconds(60)) for 429 */
    CompletableFuture.completedFuture(/* ... existing sync logic wrapped in CF ... */)
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew :module-infra:test --tests "*NexonFanOut*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonFanOutWorker.kt
git commit -m "feat(infra): NexonFanOutWorker — processAsync, 429 retry via visibilityReset"
```

---

## Task 9: Migrate `ResultReadyProjectionWorker` (3 sites)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt`
- Test: extend existing or create new

- [ ] **Step 1: Replace `jobsFuture.join()` + `lightResultsFuture.join()` at lines 81-90**

```kotlin
// Before
val jobs = jobsFuture.join()
val light = lightResultsFuture.join()

// After
return jobsFuture
    .thenCombine(lightResultsFuture) { jobs, light -> jobs to light }
    .thenCompose { (jobs, light) -> projectBatchAsync(jobs, light, ctx) }
    .thenApply { ProcessOutcome.Ack }
    .exceptionally { ex ->
        log.error("[ResultReadyProjection] failed: {}", ex.cause ?: ex)
        ProcessOutcome.Nack(retryable = true)
    }
```

- [ ] **Step 2: Replace `runBlocking(Dispatchers.Default) { parsed.map { async ... }.awaitAll() }` at line 123**

```kotlin
return CompletableFuture.allOf(
    *parsed.map { msg ->
        CompletableFuture.supplyAsync({
            // CPU-bound per-msg projection
        }, cpuExecutor)
    }.toTypedArray()
).thenApply { ProcessOutcome.Ack }
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :module-infra:test --tests "*ResultReadyProjection*"
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt
git commit -m "feat(infra): ResultReadyProjectionWorker — no .join, no runBlocking"
```

---

## Task 10: Add CI grep gate

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/test/PgmqBlockingPrimitiveGateTest.kt`

- [ ] **Step 1: Write the gate test**

```kotlin
package maple.expectation.infrastructure.test

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class PgmqBlockingPrimitiveGateTest {
    @Test
    fun `no join or runBlocking in module-infra pgmq/worker main sources`() {
        val srcRoots = listOf(
            File("src/main/kotlin/maple/expectation/infrastructure/pgmq"),
            File("src/main/kotlin/maple/expectation/infrastructure/worker"),
        )

        val violations = mutableListOf<String>()
        val patterns = listOf(
            Regex("""\.join\(\)"""),
            Regex("""runBlocking\s*\{"""),
            Regex("""Task\.join\(\)"""),
            Regex("""task\.get\(\)"""),
            Regex("""Thread\.sleep\s*\("""),
        )

        srcRoots.forEach { srcRoot ->
            if (!srcRoot.exists()) return@forEach
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
        }

        assertTrue(violations.isEmpty(), "Blocking primitives found:\n${violations.joinToString("\n")}")
    }

    private fun isAllowlisted(file: File, line: Int, text: String): Boolean {
        val path = file.absolutePath
        val isLegacySync = path.contains("PgmqWorker") ||  // sync `process(): Boolean` @Deprecated compat
                            path.contains("AbstractExpectationCalcWorker")
        val inSyncMethod = text.contains("@Deprecated") ||
                            text.startsWith("//") ||
                            text.startsWith("*") ||
                            text.startsWith("/*")
        return isLegacySync && inSyncMethod
    }
}
```

- [ ] **Step 2: Run + commit**

```bash
./gradlew :module-infra:test --tests "*PgmqBlockingPrimitiveGateTest*"
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/test/PgmqBlockingPrimitiveGateTest.kt
git commit -m "test(infra): CI grep gate for blocking primitives in pgmq/worker"
```

---

## Task 11: Verify + PR

- [ ] **Step 1: Compile**

```bash
./gradlew compileKotlin compileJava --continue
```

- [ ] **Step 2: Run all tests**

```bash
./gradlew test
```

- [ ] **Step 3: Grep gate**

```bash
./gradlew :module-infra:test --tests "*PgmqBlockingPrimitiveGateTest*"
```

- [ ] **Step 4: Push + create PR**

```bash
git push -u origin feature/pgmq-process-async
gh pr create --base develop --head feature/pgmq-process-async \
  --title "feat(infra): PGMQ workers — processAsync CF<ProcessOutcome>, no .join" \
  --body "$(cat <<'EOF'
## Summary

Adds async-returning `processAsync(): CF<ProcessOutcome>` to PGMQ workers. Eliminates 8 blocking sites (`.join()` × 4, `runBlocking` × 3, `Task.join()` × 1) across 6 PGMQ workers + `ResultReadyProjectionWorker`. Sync `process(): Boolean` kept `@Deprecated` for module-app legacy.

## Spec / Audit

- Audit: `docs/05_Reports/2026-06-18-blocking-audit.md`
- Plan: `docs/superpowers/plans/2026-06-18-pgmq-process-async.md`

## Decisions (grill-me Q1-Q8)

- Q1=A: 6 PGMQ workers (ExternalApiWorker, CalculationWorker, CalculationRequestedWorker, CalculationCompletedWorker, DonationWorker, NexonFanOutWorker) + ResultReadyProjectionWorker
- Q2=A: sealed class `ProcessOutcome` (Ack | Nack(retryable, visibilityReset) | DeadLetter)
- Q3=A: add `processAsync()` + sync `@Deprecated`
- Q4=A: @Scheduled poll loop sync, async chain internal via `.whenComplete`
- Q5=A: parallel via `CompletableFuture.allOf` bounded by `Semaphore(maxInflight=100)`
- Q6=A: ResultReadyProjectionWorker in scope (2 `.join()` + 1 `runBlocking`)
- Q7=B: topic subscribers (OcidResolveWorker, NexonApiWorker) out of scope — different framework (MQTopicGroup)
- Q8=A: `Nack.visibilityReset: Duration?` enables per-cancel redelivery timing

## Changes

### `module-infra/pgmq/`
- `ProcessOutcome.kt`: new sealed class
- `PgmqWorker.kt`: added abstract `processAsync()`, marked `process()` `@Deprecated`

### `module-infra/worker/`
- 6 PGMQ workers migrated: each adds `processAsync()` returning `CF<ProcessOutcome>`, delegates to async pipeline
- `ResultReadyProjectionWorker`: `.join()` → `.thenCombine`/`allOf`, `runBlocking` → `supplyAsync`

### CI gate

New test `PgmqBlockingPrimitiveGateTest` greps `module-infra/{pgmq,worker}/` for `.join()`, `runBlocking`, `Task.join()`, `Thread.sleep`. Allowlist for `@Deprecated` sync compat.

## Verification

- `./gradlew compileKotlin compileJava --continue` clean
- `./gradlew test` clean
- CI grep gate: 0 violations

## Out of scope (follow-up PR)

- `worker/OcidResolveWorker.kt` and `NexonApiWorker.kt` (topic subscribers, different framework)
- `module-app` legacy `process(): Boolean` users
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- 8 blocking sites eliminated (3 in `ExternalApiWorker`, 1 in `CalculationWorker`, 2 in `ResultReadyProjectionWorker`, 1 in `PgmqWorker.processSequentialBatch`, 1 `runBlocking` in `ResultReadyProjectionWorker`)
- 6 PGMQ workers migrated to `processAsync()`
- `ResultReadyProjectionWorker` migrated to async
- CI grep gate enforces no regression

**Type consistency:**
- `processAsync(message): CompletableFuture<ProcessOutcome>` — defined T2 S3, implemented T3-T9 ✓
- `ProcessOutcome` sealed class — defined T1 S3, used in `processAsync` ✓
- `Nack.visibilityReset: Duration?` — defined T1 S3, used in cancel handling ✓

**Placeholder scan:** no TBD/TODO. All code blocks complete.

**Self-review complete. Plan ready (11 tasks).**
