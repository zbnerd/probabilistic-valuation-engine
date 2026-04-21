# ADR: PGMQ Atomic Dedup & Monotonic Read-Model Upsert

**Date:** 2026-04-21
**Status:** Accepted
**PR:** #742

## Context

The V5 CQRS flow (`GET /api/v5/characters/{userIgn}/expectation`) enqueues calculation tasks via PGMQ when the PostgreSQL read model returns a cache MISS. Two issues were identified in a deep performance/concurrency review:

1. **TOCTOU race in queue dedup**: `ExpectationCalculationQueue.enqueue()` performed `findActiveMessageIdByUserIgn()` then `send()` as two separate DB operations. Under concurrent requests for the same IGN, both callers could observe no active message and both enqueue, producing duplicate compute work.

2. **Non-monotonic read-model upsert**: `upsert_expectation_read_model()` used `ON CONFLICT DO UPDATE` without a timestamp guard. An older calculation result arriving after a newer one could overwrite the correct data.

3. **Missing expression index**: Dedup queries scan `pgmq.q_expectation_calc_*` JSONB payloads via `message ->> 'userIgn'`. Without an expression index, this becomes a full scan as queue depth grows.

## Decision

### 1. Atomic dedup via PL/pgSQL

Create `pgmq_send_if_absent(queue_name, user_ign, payload)` function that performs find-or-send in a single SQL statement within the same transaction. Returns positive `msg_id` for new messages, negative `-msg_id` for reused existing messages.

```sql
CREATE OR REPLACE FUNCTION pgmq_send_if_absent(
  queue_name TEXT, user_ign TEXT, payload JSONB
) RETURNS BIGINT ...
```

The `ExpectationCalculationQueue` now calls `pgmqPort.sendIfAbsent()` for non-force requests, eliminating the TOCTOU window.

### 2. Monotonic upsert with calculated_at guard

Add `WHERE EXCLUDED.calculated_at >= character_expectation_read_model.calculated_at` to the upsert function. This ensures only newer calculation results overwrite existing data.

### 3. Expression indexes on PGMQ queue tables

```sql
CREATE INDEX idx_q_expectation_calc_high_user_ign
  ON pgmq.q_expectation_calc_high ((message ->> 'userIgn'));

CREATE INDEX idx_q_expectation_calc_low_user_ign
  ON pgmq.q_expectation_calc_low ((message ->> 'userIgn'));
```

### 4. Read-only transaction optimization

- `GameCharacterRepositoryImpl`: Class-level `@Transactional(readOnly = true)`, write methods override with `readOnly = false`.
- `CharacterViewQueryServicePostgres`: Read methods (`findByUserIgn`, `countByUserIgn`, `getLastAppliedVersion`) marked `@Transactional(readOnly = true)`.

## Consequences

**Positive:**
- Eliminates duplicate compute from concurrent enqueue races.
- Prevents stale-over-fresh data in read model.
- Dedup queries use index instead of sequential scan.
- Read-only transactions reduce flush overhead.

**Negative:**
- V112 migration adds PL/pgSQL function — must be deployed before code change.
- `sendIfAbsent` is slightly slower than bare `send` due to the dedup check, but avoids duplicate work.

## Alternatives Considered

1. **Advisory lock around find+send**: Would serialize all enqueues for the same IGN, but adds lock contention and complexity.
2. **Application-level dedup cache (Caffeine)**: Fast but does not survive restarts and diverges across instances in scale-out.
3. **Unique constraint on materialized column**: More invasive schema change; expression index is non-intrusive.

## References

- V112 migration: `module-infra/src/main/resources/db/migration/V112__pgmq_dedup_index_and_monotonic_upsert.sql`
- `ExpectationCalculationQueue.java`: `enqueue()` method
- `PgmqPort.kt`: `sendIfAbsent()` interface
- `PgmqPortAdapter.kt`: JDBC delegation to PL/pgSQL
