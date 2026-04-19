# Two-Phase Batch UPSERT — Design Spec

**Date**: 2026-04-19
**Status**: Approved
**Related**: ADR-pgmq-kafka-migration, Issue #726 (PgBouncer)

---

## Problem

PGMQ worker processes messages individually. Each message consumes 5-6 DB connections across calculation, persistence, cache, and archive. With 50 parallel messages, connection demand reaches 300 against a HikariCP pool of 100, causing severe contention (acquire_seconds_max: 8.18s).

## Solution

Split processing into two phases:

1. **Phase 1 (parallel)**: Calculate results without DB writes
2. **Phase 2 (sequential)**: Batch write all results with minimal connections

---

## Architecture

### Phase 1: Calculate Only

```
PgmqWorker.processMessages()
  1. read batch(N) from PGMQ
  2. preWarmBatch() — existing OCID resolution + equipment cache pre-warm
  3. Batch L1/L2 cache check (BS1)
     → cache hits: immediate archive, skip calculation
     → cache misses: proceed to parallel calculation
  4. Parallel calculation via Virtual Threads (BS2: no @Transactional)
     calculateOnly(message) → CalculationResult?
       ├─ findCharacterBypassingWorker() — DB read (short TX or cached)
       ├─ loadEquipmentDataAsync().join() — Nexon API (0 DB)
       ├─ calculateAllPresets() — CPU (0 DB)
       └─ buildResponse() — memory
  5. Collect: List<CalculationResult> + List<FailedMessage>
```

**Connection demand**: ~N cache-miss character lookups (most hit via preWarm)

### Phase 2: Batch Write

```
  6. batchWrite(results)
     ├─ batch L2 cache putAll()           — 1 connection (BS5)
     ├─ batch view table upsert           — 1 connection
     ├─ batch PGMQ archive                — 1 connection (BS4)
     └─ L1 (Caffeine) populate            — 0 connections (in-memory)
  7. handleFailed(failedMessages) — existing retry/DLQ logic
  8. metrics update (BS7)
```

**Connection demand**: 3

### Total Connection Demand

| | Before | After |
|---|---|---|
| Phase 1 | 50 x 6 = 300 | ~20 reads (cache misses only) |
| Phase 2 | - | 3 |
| **Total** | **300** | **~23** |

---

## Design Decisions

### BS1: Batch Cache Check
Before Phase 1, check L1 (in-memory, free) and L2 (`PostgresL2CacheStrategy.getAll()`, 1 query) for all IGNs in batch. Cache hits are archived immediately, skipping calculation entirely.

### BS2: No @Transactional on calculateOnly()
`calculateExpectation()` currently wraps the entire calculation in `@Transactional`, holding a DB connection during Nexon API calls (1-5s). `calculateOnly()` runs without transaction scope. Character lookup uses its own short read-only transaction.

### BS3: Singleflight Not Applied (Accepted)
Advisory lock in Phase 1 would serialize processing, defeating the purpose of parallel calculation. Duplicate calculations are acceptable because:
- Upsert is idempotent (no correctness issue)
- Batch cache check (BS1) filters most already-cached IGNs
- Duplicate work is waste, not error

### BS4: Batch PGMQ Archive
`PgmqClient.archive()` processes one message per call. New `archiveBatch()` method uses a single SQL:

```sql
WITH deleted AS (
  DELETE FROM pgmq.q_{queue} WHERE msg_id = ANY($1)
  RETURNING *
)
INSERT INTO pgmq.a_{queue} SELECT * FROM deleted;
```

### BS5: L2 Cache putAll()
`L2CacheStrategy` interface gets `putAll(keys, values, ttlMinutes)`. `PostgresL2CacheStrategy` implements batch UPSERT with one query.

### BS6: CalculationResult Wrapper
Data class carrying all information from Phase 1 to Phase 2:

```kotlin
data class CalculationResult(
    val message: PgmqMessage<ExpectationCalcMessage>,
    val response: EquipmentExpectationResponseV4,
    val character: GameCharacter,
)
```

### BS7: Metrics Timing
- `concurrent` decrement: Phase 1 end (calculation done)
- `inflight` decrement + `success` increment: Phase 2 end (committed)
- `failure`/`dlq`: Failed messages after retry logic

---

## File Changes

### PgmqWorker.kt
- Add `open fun calculateOnly(message): Any?` (default: null → fallback to process())
- Add `open fun batchWrite(results: List<BatchResult<T>>)` (default: no-op)
- Modify `processMessages()`: two-phase orchestration with cache check

### PgmqClient.kt
- Add `archiveBatch(queueName, messageIds: List<Long>): Int`

### L2CacheStrategy.kt
- Add `putAll(entries: List<Pair<String, Any>>, ttlMinutes: Long)`

### PostgresL2CacheStrategy.kt
- Implement `putAll()` with batch UPSERT

### AbstractExpectationCalcWorker.kt
- Override `calculateOnly()`: call service without DB writes
- Override `batchWrite()`: batch view upsert + batch cache put + batch archive

### EquipmentExpectationServiceV4.java
- Add `calculateExpectationWriteOnly(userIgn, force, taskId)`: character lookup + API + calculation, no persistence/cache writes

### ExpectationV4Port.kt
- Add `calculateExpectationWriteOnly(userIgn, force, taskId): Any`

### ExpectationV4PortAdapter.java
- Implement `calculateExpectationWriteOnly()` delegating to service

### New: CalculationResult.kt
- Data class in `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/`

---

## Backward Compatibility

- HTTP path (`calculateExpectation()`, `cacheCoordinator.getOrCalculate()`) unchanged
- Existing `process()` method in PgmqWorker remains as fallback for workers that don't override `calculateOnly()`
- No existing behavior changes for non-batch workers

---

## Risks

| Risk | Mitigation |
|------|-----------|
| Phase 2 partial failure (batch write succeeds, archive fails) | Messages reprocessed after visibility timeout. Upsert is idempotent. |
| Memory pressure (50 CalculationResult in memory) | Each ~100KB = 5MB total. Acceptable. |
| Phase 1 character lookup still needs DB connection | Most hit via preWarm L1 cache. Short transactions only. |
| PGMQ queue name SQL injection in batch archive | Existing `validateQueueName()` regex validation |

---

## Implementation Order

1. BS1: Batch cache check in PgmqWorker
2. BS2: `calculateOnly()` without @Transactional
3. BS4: `PgmqClient.archiveBatch()`
4. BS5: `L2CacheStrategy.putAll()` + PostgresL2CacheStrategy impl
5. BS6: `CalculationResult` wrapper + AbstractExpectationCalcWorker overrides
6. BS7: Metrics timing adjustment
