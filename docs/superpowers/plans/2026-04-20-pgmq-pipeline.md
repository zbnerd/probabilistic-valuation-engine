# PGMQ Worker Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove batch synchronization bottleneck in PGMQ worker to achieve 50+ t/s (from 2.8 t/s).

**Architecture:** Replace `CompletableFuture.allOf().join()` barrier with ConcurrentQueue + Scheduled Drain pipeline. Phase 1 completions flow into `PipelineBuffer`, separate Drainer thread micro-batches (N=10) into `batchWrite()`. Per-message error handling. Backpressure via buffer size threshold.

**Tech Stack:** Kotlin, Spring Boot @Scheduled, CompletableFuture, ConcurrentLinkedQueue, LogicExecutor, PGMQ, HikariCP

**Spec:** `docs/superpowers/specs/2026-04-20-pgmq-pipeline-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-infra/.../pgmq/PipelineBuffer.kt` | Create | Concurrent queue with drain/backpressure |
| `module-infra/.../pgmq/PgmqWorker.kt` | Modify | Replace batch sync with pipeline flow |
| `module-infra/.../pgmq/PgmqWorkerConfig.kt` | Modify | Add pipeline config properties |
| `module-infra/.../worker/AbstractExpectationCalcWorker.kt` | Modify | Adapt batchWrite for micro-batch |
| `module-infra/.../worker/ExpectationCalcWorker.kt` | No change | Pass-through constructor |
| `module-infra/.../worker/ExpectationCalcLowWorker.kt` | No change | Pass-through constructor |
| `module-app/src/main/resources/application.yml` | Modify | Add pipeline config section |
| `module-infra/src/test/kotlin/.../pgmq/PipelineBufferTest.kt` | Create | Unit tests for PipelineBuffer |
| `module-infra/src/test/kotlin/.../pgmq/PgmqWorkerPipelineTest.kt` | Create | Unit tests for pipeline flow |

Exact paths:
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PipelineBuffer.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`
- `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PipelineBufferTest.kt`
- `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerPipelineTest.kt`

---

### Task 1: PipelineBuffer — Concurrent Queue with Drain

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PipelineBuffer.kt`
- Test: `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PipelineBufferTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PipelineBufferTest.kt`:

```kotlin
package maple.expectation.infrastructure.pgmq

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PipelineBufferTest {

    @Test
    fun `offer adds item and returns true when under max`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 10)
        assertThat(buffer.offer("a")).isTrue
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `offer returns false when at max capacity`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 2)
        buffer.offer("a")
        buffer.offer("b")
        assertThat(buffer.offer("c")).isFalse
        assertThat(buffer.size()).isEqualTo(2)
    }

    @Test
    fun `drain returns up to maxItems from buffer`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 100)
        buffer.offer("a")
        buffer.offer("b")
        buffer.offer("c")

        val batch = buffer.drain(2)
        assertThat(batch).containsExactly("a", "b")
        assertThat(buffer.size()).isEqualTo(1)
    }

    @Test
    fun `drain returns fewer items when buffer has less than maxItems`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 100)
        buffer.offer("a")

        val batch = buffer.drain(5)
        assertThat(batch).containsExactly("a")
        assertThat(buffer.size()).isEqualTo(0)
    }

    @Test
    fun `drain returns empty list when buffer is empty`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 100)
        val batch = buffer.drain(5)
        assertThat(batch).isEmpty()
    }

    @Test
    fun `drain removes items from buffer`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 2, maxBufferSize = 100)
        buffer.offer("a")
        buffer.offer("b")
        buffer.offer("c")

        val first = buffer.drain(2)
        assertThat(first).containsExactly("a", "b")

        val second = buffer.drain(2)
        assertThat(second).containsExactly("c")

        assertThat(buffer.size()).isEqualTo(0)
    }

    @Test
    fun `isFull returns true when at max capacity`() {
        val buffer = PipelineBuffer<String>(microBatchSize = 5, maxBufferSize = 2)
        assertThat(buffer.isFull()).isFalse
        buffer.offer("a")
        assertThat(buffer.isFull()).isFalse
        buffer.offer("b")
        assertThat(buffer.isFull()).isTrue
    }

    @Test
    fun `concurrent offer and drain do not lose items`() {
        val buffer = PipelineBuffer<Int>(microBatchSize = 10, maxBufferSize = 1000)
        val itemCount = 100
        val latch = CountDownLatch(1)
        val drainResults = mutableListOf<Int>()

        val producerThread = Thread {
            for (i in 0 until itemCount) {
                buffer.offer(i)
            }
        }

        val consumerThread = Thread {
            latch.await(5, TimeUnit.SECONDS)
            while (drainResults.size < itemCount) {
                val batch = buffer.drain(10)
                if (batch.isEmpty()) {
                    Thread.sleep(1)
                    continue
                }
                drainResults.addAll(batch)
            }
        }

        producerThread.start()
        consumerThread.start()
        latch.countDown()

        producerThread.join(5000)
        consumerThread.join(5000)

        assertThat(drainResults).hasSize(itemCount)
        assertThat(drainResults.sorted()).containsExactlyElementsOf((0 until itemCount).toList())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.pgmq.PipelineBufferTest" -i 2>&1 | tail -20`
Expected: FAIL — `PipelineBuffer` class not found

- [ ] **Step 3: Write PipelineBuffer implementation**

Create `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PipelineBuffer.kt`:

```kotlin
package maple.expectation.infrastructure.pgmq

import java.util.concurrent.ConcurrentLinkedQueue

class PipelineBuffer<T>(
    private val microBatchSize: Int = 10,
    private val maxBufferSize: Int = 500,
) {
    private val queue = ConcurrentLinkedQueue<T>()

    fun offer(result: T): Boolean {
        if (queue.size >= maxBufferSize) return false
        queue.add(result)
        return true
    }

    fun drain(maxItems: Int): List<T> {
        val batch = mutableListOf<T>()
        repeat(maxItems) {
            val item = queue.poll() ?: return batch
            batch.add(item)
        }
        return batch
    }

    fun size(): Int = queue.size

    fun isFull(): Boolean = queue.size >= maxBufferSize
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.pgmq.PipelineBufferTest" -i 2>&1 | tail -20`
Expected: PASS — all 7 tests

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PipelineBuffer.kt \
       module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PipelineBufferTest.kt
git commit -m "feat(pgmq): add PipelineBuffer — concurrent queue with micro-batch drain"
```

---

### Task 2: PgmqWorkerConfig — Add Pipeline Settings

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt`

Current `CommonSettings` (lines 45-57 in PgmqWorkerConfig.kt):

```kotlin
data class CommonSettings(
    var pollingIntervalMs: Long = 300,
    var batchSize: Int = 50,
    var maxRetries: Int = 3,
    var visibilityTimeoutSec: Int = 120,
)
```

- [ ] **Step 1: Add pipeline settings to CommonSettings**

Add these 3 fields to `CommonSettings`:

```kotlin
data class CommonSettings(
    var pollingIntervalMs: Long = 300,
    var batchSize: Int = 50,
    var maxRetries: Int = 3,
    var visibilityTimeoutSec: Int = 120,
    /** Pipeline micro-batch size for drain */
    var pipelineMicroBatchSize: Int = 10,
    /** Pipeline drain interval (ms) */
    var pipelineDrainIntervalMs: Long = 100,
    /** Pipeline max buffer size (backpressure threshold) */
    var pipelineMaxBufferSize: Int = 500,
)
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt
git commit -m "feat(pgmq): add pipeline config properties to PgmqWorkerConfig"
```

---

### Task 3: PgmqWorker — Replace Batch Sync with Pipeline Flow

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt`

This is the core change. Current flow (lines 127-230):
- `processMessages()` → `processBatchTwoPhase()` → `allOf().join()` → `handlePhaseTwoCompletion()`

New flow:
- `processMessages()` → per-message `supplyAsync` + `.thenAccept(pipelineBuffer::offer)` + `.exceptionally(handleError)`
- `drainBuffer()` — new `@Scheduled(fixedDelay=100ms)` method that drains buffer → `batchWrite()`

- [ ] **Step 1: Add PipelineBuffer field to PgmqWorker**

After the `workerPool` field (around line 41), add:

```kotlin
/** Pipeline buffer for two-phase workers — Phase 1 results queue here before drain */
private val pipelineBuffer = PipelineBuffer<CalculationResult>(
    microBatchSize = config.common.pipelineMicroBatchSize,
    maxBufferSize = config.common.pipelineMaxBufferSize,
)
```

- [ ] **Step 2: Replace processMessages() two-phase branch**

In `processMessages()`, replace the two-phase routing block (lines ~162-166):

```kotlin
// BEFORE:
if (supportsTwoPhase) {
    processBatchTwoPhase(messages)
} else {
    processBatchSinglePhase(messages)
}

// AFTER:
if (supportsTwoPhase) {
    if (pipelineBuffer.isFull()) {
        log.warn("[{}] Pipeline buffer full ({}), skipping poll", queueName, pipelineBuffer.size())
    } else {
        processBatchPipelined(messages)
    }
} else {
    processBatchSinglePhase(messages)
}
```

- [ ] **Step 3: Add processBatchPipelined() method**

Replace `processBatchTwoPhase()` (lines 175-187) and `handlePhaseTwoCompletion()` (lines 189-208) with:

```kotlin
private fun processBatchPipelined(messages: List<PgmqMessage<T>>) {
    messages.forEach { message ->
        CompletableFuture.supplyAsync(
            { executePhaseOne(message) },
            workerPool,
        ).exceptionally { error ->
            log.warn("[{}] Phase 1 failed for msgId={}: {}", queueName, message.messageId, error.message)
            metrics.inflightDecrement()
            null to message
        }.thenAccept { result ->
            val calcResult = result.first as? CalculationResult
            if (calcResult != null) {
                pipelineBuffer.offer(calcResult)
            }
            // Failure results are not offered — visibility timeout handles retry
        }
    }
}
```

Remove `processBatchTwoPhase()` and `handlePhaseTwoCompletion()` entirely. Keep `executePhaseOne()` as-is.

- [ ] **Step 4: Add drainBuffer() scheduled method**

Add a new scheduled method after `processMessages()`:

```kotlin
@Scheduled(fixedDelayString = "\${pgmq.worker.common.pipeline-drain-interval-ms:100}")
fun drainBuffer() {
    if (!supportsTwoPhase) return
    if (!lifecycleWrapper.beforeTask()) return

    val context = TaskContext.of("PgmqWorker", "DrainBuffer", queueName)

    executor.executeWithFinally(
        task = {
            val microBatchSize = config.common.pipelineMicroBatchSize
            val batch = pipelineBuffer.drain(microBatchSize)
            if (batch.isEmpty()) return@executeWithFinally

            batchWrite(batch)

            batch.forEach {
                metrics.success.increment()
                metrics.inflightDecrement()
            }

            log.debug("[{}] Drained {} results", queueName, batch.size)
        },
        finallyBlock = { lifecycleWrapper.afterTask() },
        context = context,
    )
}
```

- [ ] **Step 5: Update metrics handling in executePhaseOne()**

In `executePhaseOne()` (lines 215-230), the current flow increments `metrics.concurrentIncrement()` at start and `metrics.concurrentDecrement()` in finally. This stays the same. But the success/failure metric tracking moves:
- Success: handled in `drainBuffer()` after `batchWrite()` completes
- Failure: handled in `.exceptionally` block with `metrics.inflightDecrement()`

The `metrics.inflightDecrement()` was previously in `handlePhaseTwoCompletion()`. Now it's split between `drainBuffer()` (success path) and `.exceptionally` (failure path). Make sure every inflight message gets decremented exactly once.

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run existing tests**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests" | tail -5`
Expected: BUILD SUCCESSFUL (all existing tests pass — `processBatchSinglePhase` path unchanged)

- [ ] **Step 8: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt
git commit -m "feat(pgmq): replace batch sync with pipeline flow — ConcurrentQueue + Scheduled Drain

Removes CompletableFuture.allOf().join() barrier.
Phase 1 completions flow into PipelineBuffer, drainBuffer() micro-batches into batchWrite.
Per-message error handling via .exceptionally(). Backpressure via buffer.isFull()."
```

---

### Task 4: AbstractExpectationCalcWorker — Adapt batchWrite for Micro-batch

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`

Current `batchWrite()` (lines 118-133) already works with any list size. The only change needed is logging level: reduce from INFO to DEBUG for small batches, since drainBuffer calls this frequently with 1-10 items.

- [ ] **Step 1: Adjust batchWrite logging for micro-batch frequency**

In `batchWrite()`, change the INFO log to only log at INFO for batches >= 10, DEBUG otherwise:

```kotlin
override fun batchWrite(results: List<CalculationResult>) {
    if (results.isEmpty()) return

    val context = TaskContext.of(workerName, "BatchWrite", "${results.size}")
    executor.executeVoid({
        transactionTemplate.executeWithoutResult {
            if (results.size >= 10) {
                workerLog.info("[{}] Phase 2 batchWrite: {} results", workerName, results.size)
            } else {
                workerLog.debug("[{}] Phase 2 batchWrite: {} results", workerName, results.size)
            }

            batchL2CachePut(results)

            val messageIds = results.map { it.message.messageId }
            val archived = pgmqClient.archiveBatch(queueName, messageIds)
            if (results.size >= 10) {
                workerLog.info("[{}] Batch archived: {}/{}", workerName, archived, messageIds.size)
            }
        }
    }, context)
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt
git commit -m "refactor(pgmq): adjust batchWrite logging for micro-batch frequency

INFO for batches >= 10, DEBUG for smaller. Prevents log spam from 100ms drain interval."
```

---

### Task 5: Pipeline Flow Unit Tests

**Files:**
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerPipelineTest.kt`

- [ ] **Step 1: Write pipeline flow tests**

Create `module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerPipelineTest.kt`:

```kotlin
package maple.expectation.infrastructure.pgmq

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig.CommonSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PgmqWorkerPipelineTest {

    private val pipelineBuffer = PipelineBuffer<CalculationResult>(
        microBatchSize = 3,
        maxBufferSize = 10,
    )

    @Test
    fun `pipeline buffer accepts Phase 1 results`() {
        val result = createCalculationResult("user1")
        assertThat(pipelineBuffer.offer(result)).isTrue
        assertThat(pipelineBuffer.size()).isEqualTo(1)
    }

    @Test
    fun `pipeline buffer rejects when full`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 3, maxBufferSize = 2)
        buffer.offer(createCalculationResult("user1"))
        buffer.offer(createCalculationResult("user2"))
        assertThat(buffer.offer(createCalculationResult("user3"))).isFalse
    }

    @Test
    fun `drain batches micro-batch size results`() {
        pipelineBuffer.offer(createCalculationResult("user1"))
        pipelineBuffer.offer(createCalculationResult("user2"))
        pipelineBuffer.offer(createCalculationResult("user3"))
        pipelineBuffer.offer(createCalculationResult("user4"))

        val batch = pipelineBuffer.drain(3)
        assertThat(batch).hasSize(3)
        assertThat(pipelineBuffer.size()).isEqualTo(1)
    }

    @Test
    fun `drain returns partial batch when fewer items available`() {
        pipelineBuffer.offer(createCalculationResult("user1"))

        val batch = pipelineBuffer.drain(5)
        assertThat(batch).hasSize(1)
        assertThat(pipelineBuffer.size()).isEqualTo(0)
    }

    @Test
    fun `concurrent offers and drains maintain data integrity`() {
        val buffer = PipelineBuffer<CalculationResult>(microBatchSize = 10, maxBufferSize = 1000)
        val count = 50
        val latch = CountDownLatch(count)

        // Simulate Phase 1 completions
        val futures = (0 until count).map { i ->
            Thread.startVirtualThread {
                buffer.offer(createCalculationResult("user$i"))
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        // Drain all
        val allResults = mutableListOf<CalculationResult>()
        while (buffer.size() > 0) {
            allResults.addAll(buffer.drain(10))
        }

        assertThat(allResults).hasSize(count)
    }

    private fun createCalculationResult(userIgn: String): CalculationResult {
        return CalculationResult(
            message = PgmqMessage(
                messageId = 1L,
                readCount = 1,
                enqueuedAt = Instant.now(),
                visibilityTimeout = Instant.now().plusSeconds(120),
                payload = ExpectationCalcMessage(
                    userIgn = userIgn,
                    forceRecalculation = false,
                ),
            ),
            response = Any(),
            character = mock<GameCharacter>(),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.pgmq.PgmqWorkerPipelineTest" -i 2>&1 | tail -20`
Expected: PASS — all 5 tests

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/test/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerPipelineTest.kt
git commit -m "test(pgmq): add pipeline flow unit tests — buffer, drain, concurrency"
```

---

### Task 6: Full Compile + Test Verification

**Files:** None (verification only)

- [ ] **Step 1: Full compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Full test suite**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAIL|tests" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify no regressions in existing PgmqWorker tests**

Run: `./gradlew :module-infra:test --tests "*Pgmq*" -i 2>&1 | grep -E "PASS|FAIL|tests" | tail -10`
Expected: All PGMQ-related tests pass
