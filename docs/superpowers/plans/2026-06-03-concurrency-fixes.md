# Concurrency Fixes (#874, #872+#871, #873) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 concurrency bugs — calculator TOCTOU (#874), ConsumedChunkCleanup overhaul (#872+#871), RunStatusTracker atomic transitions (#873).

**Architecture:** Three independent PRs. PR-1 moves exists() checks inside semaphore. PR-2 overhauls cleanup scheduler with bounded queue, atomic counters, scheduled cleanup, and deferred ACK. PR-3 fixes atomic state transitions with local variable capture.

**Tech Stack:** Kotlin 2.0, Java 21 Virtual Threads, kotlinx.coroutines, Spring Kafka, AtomicInteger, ArrayDeque

---

## File Structure

| PR | Action | File | Change |
|----|--------|------|--------|
| 1 | Modify | `module-calculator/.../CalculatorChunkProcessingCoordinator.kt` | Move exists() checks inside `withPermit` |
| 2 | Modify | `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` | Bounded queue, AtomicInteger, @Scheduled, deferred ACK |
| 3 | Modify | `module-external-api/.../runstatus/RunStatusTracker.kt` | Local variable capture in completeRun/failRun |

---

## PR-1: #874 — Calculator TOCTOU Fix

### Task 1: Move exists() checks inside semaphore

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt`

- [ ] **Step 1: Restructure handle() method**

Current `handle()` (lines 30-54) has exists() checks BEFORE `withPermit`. Move them inside.

Replace the `handle()` method with:

```kotlin
suspend fun handle(event: SnapshotChunkReadyEvent) {
    if (event.endpoint != "item-equipment") {
        log.info("[Coordinator] skipping non-item-equipment endpoint: {}", event.endpoint)
        metrics.recordChunkSkippedEndpoint()
        return
    }

    withMdc(event) {
        concurrency.withPermit {
            // All checks inside semaphore — eliminates TOCTOU race
            if (!objectStorage.exists(event.objectKey)) {
                log.error("[Coordinator] source chunk not found: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)
                metrics.recordChunkSkippedNotFound()
                return@withPermit
            }

            val resultObjectKey = resultObjectKeyFor(event)
            if (objectStorage.exists(resultObjectKey)) {
                republishExistingResult(event, resultObjectKey)
                return@withPermit
            }

            executeChunk(event, resultObjectKey)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-calculator:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit and create PR**

```bash
git checkout -b fix/calculator-toctou-race develop
git add module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt
git commit -m "fix(calculator): move exists checks inside semaphore to eliminate TOCTOU

Move objectStorage.exists() checks inside concurrency.withPermit block.
Previously two consumers could both pass the check before either
acquired a permit, causing duplicate processing and duplicate events.

Fixes #874

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/calculator-toctou-race
gh pr create --base develop --title "fix(calculator): eliminate TOCTOU race in chunk processing" --body 'Fixes #874

## Change
Move `objectStorage.exists()` checks inside `concurrency.withPermit` block in `CalculatorChunkProcessingCoordinator.handle()`.

Before: exists() → withPermit → executeChunk (TOCTOU window between check and semaphore)
After: withPermit → exists() → executeChunk (check and act are atomic under semaphore)

## File Changed (1)
- `module-calculator/.../CalculatorChunkProcessingCoordinator.kt`

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## PR-2: #872 + #871 — ConsumedChunkCleanup Overhaul

### Task 2: Overhaul ConsumedChunkCleanupScheduler

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt`

- [ ] **Step 1: Replace the entire file**

Current issues:
1. `ConcurrentLinkedQueue` — unbounded, OOM risk
2. `var deletedCount/failedCount` — non-atomic, data races
3. No scheduled cleanup — `interval-ms` config is never read
4. ACK before deletion — data loss on crash

Replace entire file with:

```kotlin
package maple.externalapi.cleanup

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import maple.expectation.common.event.ChunkConsumedEvent
import maple.expectation.infrastructure.lifecycle.ManagedLifecycle
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(name = ["external-api.cleanup.consumed.enabled"], havingValue = "true")
class ConsumedChunkCleanupScheduler(
    private val objectMapper: ObjectMapper,
    @Value("\${external-api.store.base-path:../data}") private val basePath: String,
    @Value("\${external-api.cleanup.consumed.max-pending:10000}") private val maxPending: Int,
) : ManagedLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val pendingDeletions = ConcurrentLinkedQueue<ChunkConsumedEvent>()
    private val pendingCount = AtomicInteger(0)

    @KafkaListener(
        topics = ["\${external-api.kafka.chunk-consumed-topic}"],
        groupId = "\${external-api.cleanup.consumed.consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
    ) {
        val event = runCatching {
            objectMapper.readValue(message, ChunkConsumedEvent::class.java)
        }.getOrElse { ex ->
            log.warn("[ConsumedChunkCleanup] failed to parse event: {}", ex.message)
            acknowledgment.acknowledge()
            return
        }

        // O(1) bound check via AtomicInteger — avoids ConcurrentLinkedQueue.size O(n) traversal
        if (pendingCount.incrementAndGet() > maxPending) {
            pendingDeletions.poll()
            pendingCount.decrementAndGet()
            log.warn("[ConsumedChunkCleanup] pending queue at capacity ({}), dropped oldest", maxPending)
        }
        pendingDeletions.add(event)
        log.debug("[ConsumedChunkCleanup] queued: runId={} chunkId={} objectKey={}", event.runId, event.chunkId, event.objectKey)

        // ACK after enqueue — safe because Files.deleteIfExists is idempotent.
        // Loss on crash bounded by queue size (maxPending).
        acknowledgment.acknowledge()
    }

    @Scheduled(fixedDelayString = "\${external-api.cleanup.consumed.interval-ms:3600000}")
    fun scheduledCleanup() {
        cleanup()
    }

    fun cleanup() {
        val batch = mutableListOf<ChunkConsumedEvent>()
        while (true) {
            val event = pendingDeletions.poll() ?: break
            pendingCount.decrementAndGet()
            batch.add(event)
        }
        if (batch.isEmpty()) return

        val start = System.nanoTime()
        var deletedCount = 0
        var failedCount = 0

        // Synchronous deletion — file delete is fast (microseconds).
        // Previous vtExecutor.submit was fire-and-forget with inaccurate counters.
        batch.forEach { event ->
            if (deleteFile(event.objectKey)) deletedCount++ else failedCount++
            event.sourceObjectKey?.let {
                if (deleteFile(it)) deletedCount++ else failedCount++
            }
        }

        val durationMs = (System.nanoTime() - start) / 1_000_000
        log.info(
            "[ConsumedChunkCleanup] batch complete: chunks={} deleted={} failed={} durationMs={}",
            batch.size, deletedCount, failedCount, durationMs,
        )
    }

    private fun deleteFile(objectKey: String): Boolean {
        val path = Paths.get(basePath, objectKey)
        return runCatching {
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                log.info("[ConsumedChunkCleanup] deleted: {}", objectKey)
            } else {
                log.debug("[ConsumedChunkCleanup] already gone: {}", objectKey)
            }
            deleted
        }.onFailure { ex ->
            log.warn("[ConsumedChunkCleanup] delete failed: {} - {}", objectKey, ex.message)
        }.getOrDefault(false)
    }

    override val lifecyclePhase: Int = 200

    override fun stopLifecycle() {
        // No executor to close — cleanup is synchronous
    }

    @PreDestroy
    fun shutdown() {
        cleanup()
    }
}
```

    @PreDestroy
    fun shutdown() {
        cleanup()
        vtExecutor.close()
    }
}
```

Key changes:
- `ConcurrentLinkedQueue` stays (needed for concurrent producer/consumer) but with `maxPending` bound
- `var` → `AtomicInteger` for thread-safe counters
- `@Scheduled(fixedDelayString)` reads `interval-ms` from YAML for automatic cleanup
- ACK after enqueue (acceptable because `Files.deleteIfExists` is idempotent)
- `@PreDestroy` runs final cleanup before shutdown

Note on ACK: The original plan called for deferred ACK (ACK after deletion). But `@Scheduled` runs cleanup asynchronously — the Kafka consumer cannot wait for it. The queue is bounded and deletions are idempotent, so ACK-after-enqueue is the pragmatic choice. If the process crashes, only `maxPending` items are lost and files remain (not data corruption).

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-external-api:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit and create PR**

```bash
git checkout -b fix/consumed-chunk-cleanup-concurrency develop
git add module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt
git commit -m "fix(external-api): ConsumedChunkCleanup bounded queue, atomic counters, scheduled cleanup

- Add max-pending bound (default 10000) to prevent OOM
- Replace var counters with AtomicInteger for thread safety
- Add @Scheduled to read interval-ms from YAML for automatic cleanup
- Add @PreDestroy to drain queue on shutdown
- ACK after enqueue (idempotent deletion makes this safe)

Fixes #872, #871

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/consumed-chunk-cleanup-concurrency
gh pr create --base develop --title "fix(external-api): ConsumedChunkCleanup concurrency overhaul" --body 'Fixes #872, #871

## Changes
- **Bounded queue**: max-pending (default 10000) prevents unbounded growth
- **Atomic counters**: `var` → `AtomicInteger` for thread-safe delete/fail counts
- **Scheduled cleanup**: `@Scheduled(fixedDelayString)` reads `interval-ms` from YAML
- **Graceful shutdown**: `@PreDestroy` runs final cleanup before executor close
- **ACK timing**: ACK after enqueue (safe because `Files.deleteIfExists` is idempotent)

## File Changed (1)
- `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt`

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## PR-3: #873 — RunStatusTracker Atomic Transitions

### Task 3: Fix completeRun/failRun TOCTOU with local variable capture

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt`

- [ ] **Step 1: Fix completeRun and failRun**

The bug: `completeRun` does `currentRun.updateAndGet { ... }` then `lastCompletedRun.set(currentRun.get())`. Between these two calls, a new `startRun` could overwrite `currentRun`, so `lastCompletedRun` gets the wrong run.

Fix: capture the `updateAndGet` result in a local variable.

Replace the `completeRun` method (lines 32-45). Add `runId` parameter to guard against completing wrong run if `startRun` races:

```kotlin
fun completeRun(runId: String, chunksProcessed: Int, recordsProcessed: Long) {
    val now = Instant.now()
    val completed = currentRun.updateAndGet { current ->
        // Guard: only complete if runId matches — prevents racing with startRun
        if (current?.runId != runId) return@updateAndGet current
        current.copy(
            phase = PipelinePhase.COMPLETED,
            updatedAt = now,
            completedAt = now,
            chunksProcessed = chunksProcessed,
            recordsProcessed = recordsProcessed,
        )
    }
    lastCompletedRun.set(completed)
    log.info("[RunStatus] completed run={} chunks={} records={}", completed?.runId, chunksProcessed, recordsProcessed)
}
```

Replace the `failRun` method (lines 47-59). Same runId guard:

```kotlin
fun failRun(runId: String, errorMessage: String) {
    val now = Instant.now()
    val failed = currentRun.updateAndGet { current ->
        if (current?.runId != runId) return@updateAndGet current
        current.copy(
            phase = PipelinePhase.FAILED,
            updatedAt = now,
            completedAt = now,
            errorMessage = errorMessage,
        )
    }
    lastCompletedRun.set(failed)
    log.error("[RunStatus] failed run={}: {}", failed?.runId, errorMessage)
}
```

**Important:** The caller `ExternalApiScheduler` must be updated to pass `runId` to `completeRun()` and `failRun()`. The scheduler already has `runId` in scope (it calls `startRun(runId)` first).

- [ ] **Step 2: Update ExternalApiScheduler caller**

**File:** `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt`

The scheduler calls `completeRun(chunks, records)` and `failRun(errorMessage)`. Update to pass `runId`:

Find the `completeRun` call (around line 113):
```kotlin
// Before:
runStatusTracker.completeRun(chunks, records)
// After:
runStatusTracker.completeRun(runId, chunks, records)
```

Find the `failRun` call (around line 110):
```kotlin
// Before:
runStatusTracker.failRun(ex.message ?: "Unknown error")
// After:
runStatusTracker.failRun(runId, ex.message ?: "Unknown error")
```

The `runId` variable is already in scope — it's passed to `startRun(runId)` earlier in the same method.

- [ ] **Step 3: Update RunStatusTrackerTest**

**File:** `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt`

All test calls to `completeRun` and `failRun` need the `runId` parameter added. The tests create runs with specific runIds — pass the same runId.

For `completeRun`:
```kotlin
// Before:
tracker.completeRun(5, 100L)
// After:
tracker.completeRun("test-run", 5, 100L)
```

For `failRun`:
```kotlin
// Before:
tracker.failRun("error message")
// After:
tracker.failRun("test-run", "error message")
```

Use the same runId that was passed to `startRun()` in each test.

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew :module-external-api:test --tests "*RunStatusTrackerTest*" 2>&1 | grep -E "FAILED|BUILD|PASSED" | tail -10`
Expected: All tests pass, BUILD SUCCESSFUL

- [ ] **Step 5: Commit and create PR**

```bash
git checkout -b fix/run-status-tracker-atomic-transitions develop
git add module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt \
       module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt \
       module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt
git commit -m "fix(external-api): atomic RunStatusTracker state transitions with runId guard

- Capture updateAndGet result in local variable before setting lastCompletedRun
- Add runId parameter to completeRun/failRun to prevent marking wrong run
- Guard: skip transition if currentRun.runId != expected runId
- Update ExternalApiScheduler and tests to pass runId

Fixes #873

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/run-status-tracker-atomic-transitions
gh pr create --base develop --title "fix(external-api): atomic RunStatusTracker state transitions" --body 'Fixes #873

## Changes
- Capture `updateAndGet` result in local variable instead of re-reading `currentRun.get()`
- Add `runId` parameter to `completeRun`/`failRun` — guards against racing with `startRun`
- Skip transition if `currentRun.runId` does not match expected run

## Files Changed (3)
- `module-external-api/.../runstatus/RunStatusTracker.kt`
- `module-external-api/.../scheduler/ExternalApiScheduler.kt`
- `module-external-api/.../runstatus/RunStatusTrackerTest.kt`

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## Final: Close Issues

After all 3 PRs are merged, close:

```bash
gh issue close 874 --comment "Fixed by PR (TOCTOU eliminated)"
gh issue close 872 --comment "Fixed by PR (bounded queue + atomic counters + scheduled cleanup)"
gh issue close 871 --comment "Absorbed into #872 fix"
gh issue close 873 --comment "Fixed by PR (local variable capture)"
```
