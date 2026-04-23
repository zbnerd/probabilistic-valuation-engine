# PGMQ Worker Pipeline Design

## Goal

Remove batch synchronization bottleneck in PGMQ worker to achieve 50+ t/s (from 2.8 t/s).

## Problem

Current `processBatchTwoPhase()` uses `CompletableFuture.allOf().join()` to wait for ALL Phase 1 calculations before ANY Phase 2 write. A single slow Nexon API response blocks the entire batch of 50 messages.

```
poll 50 → allOf().join() → batchWrite → archive → next poll
           ↑ slow tail blocks all 49 others
```

## Solution: ConcurrentQueue + Scheduled Drain (Approach A)

### Architecture

```
[Scheduled Poll - fixedDelay=300ms]
    │
    ├─ poll(50) → messages
    ├─ preWarmBatch()
    └─ messages.forEach { msg ->
         CompletableFuture.supplyAsync({ calculateOnly(msg) }, workerPool)
           .exceptionally { error -> handleError(msg, error) }
           .thenAccept { result -> pipelineBuffer.offer(result) }
       }
    // returns immediately, no join()

[PipelineBuffer Drainer - fixedDelay=100ms]
    │
    ├─ buffer.drain(microBatchSize=10)
    │   ├─ up to 10 → batchWrite(results)
    │   └─ archiveBatch(messageIds)
    └─ if empty, no-op
```

### Components

#### PipelineBuffer (new)

```kotlin
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
}
```

#### PgmqWorker changes

- Remove `processBatchTwoPhase()` and `handlePhaseTwoCompletion()`
- Add `pipelineBuffer` field initialized per worker
- `processMessages()` launches per-message futures with `.thenAccept { pipelineBuffer.offer(it) }`
- Add `drainBuffer()` scheduled method with `fixedDelay=100ms`
- Backpressure: if `pipelineBuffer.size() >= maxBufferSize`, skip `read()`

#### AbstractExpectationCalcWorker changes

- Keep `calculateOnly()` as-is
- Keep `batchWrite()` as-is — Drainer calls it with micro-batch sized lists (1~10 items)
- No new methods needed. PgmqWorker's Drainer calls `batchWrite(drainedResults)` directly

### Concurrency Model

| Thread | Role |
|--------|------|
| Poll (scheduler) | PGMQ read, launch Phase 1 futures |
| Worker pool | Phase 1 calculation (CPU + I/O) |
| Drainer (scheduler) | Buffer drain → batchWrite → archive |

fixedDelay for drainer prevents overlap. If batchWrite takes 50ms, next drain fires 150ms after previous start. Natural backpressure.

### Error Handling

```
Phase 1 success → pipelineBuffer.offer() → Drainer batchWrite
Phase 1 failure → .exceptionally {} block
    ├─ readCount < 3 → visibility timeout expires → PGMQ auto-retry
    └─ readCount >= 3 → archive() → DLQ
```

Each message independent. One failure never blocks others.

### Backpressure

```
buffer.size >= 500 → skip poll (don't read messages)
PGMQ visibility timeout (120s) → automatic retry when poll resumes
buffer drains below 500 → resume polling
```

### Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| microBatchSize | 10 | Items per Phase 2 batchWrite |
| drainIntervalMs | 100 | fixedDelay between drains |
| maxBufferSize | 500 | Backpressure threshold |

### Performance Target

- Current: 2.8 t/s
- Target: 50+ t/s (200 t/s achievable with this design)
- Improvement: ~18x minimum

### Files to Modify

- `module-infra/.../pgmq/PgmqWorker.kt` — pipeline flow, drainBuffer()
- `module-infra/.../pgmq/PipelineBuffer.kt` — new concurrent buffer
- `module-infra/.../worker/AbstractExpectationCalcWorker.kt` — adapt batchWrite for micro-batch
- `module-infra/.../worker/ExpectationCalcWorker.kt` — constructor updates
- `module-infra/.../worker/ExpectationCalcLowWorker.kt` — constructor updates
- `module-app/src/main/resources/application.yml` — pipeline config properties

### Out of Scope

- Poll batch size changes (separate tuning)
- Ring buffer / Disruptor (unnecessary at 200 t/s)
- View upsert in module-infra (deferred, requires port)
