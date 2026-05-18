# Chunk Execution Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kafka chunk processing recoverable by recording execution state before ack. PR1 scope only: create `chunk_execution` foundation, insert `PENDING` on first consume, atomically claim `PROCESSING`, persist `SUCCEEDED` / `FAILED_*`, then ack. Retry scheduler, retry topics, cleanup guard, and admin replay are later PRs.

**Architecture:** Kafka consumer owns execution state for its handler. Normal topic missing row creates `PENDING`; retry topic does not exist in PR1. `chunk_execution` row identity is `(execution_type, run_id, endpoint, chunk_id)`. `lease_until` prevents stuck `PROCESSING`. `event_payload_jsonb` stores original metadata event for future replay.

**Tech Stack:** Kotlin, Spring Kafka manual ack, PostgreSQL, `NamedParameterJdbcTemplate`, Flyway migration, module-common event DTOs

---

## Design Decisions

| Decision | Choice |
|----------|--------|
| Execution owner | Consumer creates row on first consume |
| Row identity | `UNIQUE(execution_type, run_id, endpoint, chunk_id)` |
| Payload storage | `event_payload_jsonb` in `chunk_execution` |
| Schema version | Store in payload and DB column |
| Processing recovery | `lease_until` on `PROCESSING` |
| Ack rule | Ack only after terminal/current state write succeeds |
| PR1 retry behavior | No scheduler. Failed chunks become `FAILED_RETRYABLE` or `FAILED_TERMINAL`; later PR handles retry |
| `SUCCEEDED` replay | Not in PR1. Later admin API forbids same-execution replay |

---

## File Structure

### New Files

| File | Responsibility |
|------|----------------|
| `module-infra/src/main/resources/db/migration/V128__chunk_execution.sql` | Create `chunk_execution` table/indexes |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionType.kt` | Execution type enum |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt` | Status enum |
| `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionIdentity.kt` | Natural identity value |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt` | Insert/claim/mark execution rows |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt` | Repository behavior tests |

### Modified Files

| File | Change |
|------|--------|
| `module-synchronizer/.../consumer/ChunkConsumerTemplate.kt` | Use `ChunkExecutionRepository` instead of legacy status callbacks |
| `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt` | Use `SYNCHRONIZER_BASIC_CHUNK` identity |
| `module-synchronizer/.../consumer/KafkaResultChunkConsumer.kt` | Use `SYNCHRONIZER_RESULT_CHUNK` identity |
| `module-calculator/.../consumer/KafkaSnapshotChunkReadyConsumer.kt` | Add calculator execution state if PR1 includes calculator |
| `module-calculator/.../CalculatorChunkProcessingCoordinator.kt` | Accept execution identity/status callbacks if calculator included |
| `module-infra/src/test/.../DatabaseCleaner.kt` variants | Include `chunk_execution` cleanup if tests require |

---

## Task 0: Scope Lock

- [x] **Step 1: Confirm PR1 includes synchronizer only or synchronizer + calculator**

Recommended split:

```text
PR1a: synchronizer chunk_execution foundation (current scope)
PR1b: calculator chunk_execution foundation (deferred)
```

If user wants one PR1, include both. If risk control matters, start synchronizer because DB upsert is highest consistency risk.

- [x] **Step 2: Do not implement retry scheduler**

Explicitly out of scope:

- retry topic
- `REPUBLISHING`
- scheduler claim/publish
- cleanup `RunDeletionGuard`
- admin replay

---

## Task 1: Migration

**Files:**
- Create: `module-infra/src/main/resources/db/migration/V128__chunk_execution.sql`

- [x] **Step 1: Create table**

```sql
CREATE TABLE IF NOT EXISTS chunk_execution (
    id BIGSERIAL PRIMARY KEY,

    execution_type TEXT NOT NULL,
    run_id TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    chunk_id TEXT NOT NULL,

    topic TEXT NOT NULL,
    message_key TEXT NOT NULL,
    event_type TEXT NOT NULL,
    schema_version INT NOT NULL,
    event_payload_jsonb JSONB NOT NULL,

    status TEXT NOT NULL,

    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NULL,
    first_failed_at TIMESTAMPTZ NULL,
    last_failed_at TIMESTAMPTZ NULL,
    last_error TEXT NULL,
    terminal_reason TEXT NULL,

    processing_started_at TIMESTAMPTZ NULL,
    lease_until TIMESTAMPTZ NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_chunk_execution_identity
        UNIQUE (execution_type, run_id, endpoint, chunk_id),
    CONSTRAINT chk_chunk_execution_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'FAILED_RETRYABLE',
            'FAILED_TERMINAL',
            'SUCCEEDED'
        )
    )
);
```

- [x] **Step 2: Add indexes**

```sql
CREATE INDEX IF NOT EXISTS idx_chunk_execution_retry
ON chunk_execution (status, next_retry_at)
WHERE status = 'FAILED_RETRYABLE';

CREATE INDEX IF NOT EXISTS idx_chunk_execution_processing_lease
ON chunk_execution (status, lease_until)
WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_chunk_execution_run
ON chunk_execution (run_id, endpoint, chunk_id);
```

- [x] **Step 3: Keep old `synchronizer_chunk_status` table**

Do not drop old table in PR1. Keep rollback easy. New code can stop writing old table after tests pass.

---

## Task 2: Common Types

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionType.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt`
- Create: `module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionIdentity.kt`

- [x] **Step 1: Create execution type enum**

```kotlin
package maple.expectation.common.event

enum class ChunkExecutionType {
    CALCULATOR_SNAPSHOT_CHUNK,
    SYNCHRONIZER_RESULT_CHUNK,
    SYNCHRONIZER_BASIC_CHUNK,
}
```

- [x] **Step 2: Create status enum**

```kotlin
package maple.expectation.common.event

enum class ChunkExecutionStatus {
    PENDING,
    PROCESSING,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    SUCCEEDED,
}
```

- [x] **Step 3: Create identity value**

```kotlin
package maple.expectation.common.event

data class ChunkExecutionIdentity(
    val executionType: ChunkExecutionType,
    val runId: String,
    val endpoint: String,
    val chunkId: String,
)
```

---

## Task 3: Repository Contract

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkExecutionRepository.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/repository/ChunkExecutionRepositoryTest.kt`

- [x] **Step 1: Define repository operations**

Repository should expose:

```kotlin
fun insertPendingIfAbsent(command: InsertChunkExecutionCommand): Boolean
fun findStatus(identity: ChunkExecutionIdentity): ChunkExecutionStatus?
fun claimProcessing(identity: ChunkExecutionIdentity, processingTimeout: Duration): ChunkExecutionClaim?
fun markSucceeded(identity: ChunkExecutionIdentity, claimedAttempt: Int): Boolean
fun markFailedRetryable(identity: ChunkExecutionIdentity, claimedAttempt: Int, error: String, nextRetryAt: Instant): Boolean
fun markFailedTerminal(identity: ChunkExecutionIdentity, claimedAttempt: Int, error: String, terminalReason: String): Boolean
```

- [x] **Step 2: Implement `insertPendingIfAbsent`**

Use `ON CONFLICT DO NOTHING`.

Must store:

- `execution_type`
- `run_id`
- `endpoint`
- `chunk_id`
- `topic`
- `message_key`
- `event_type`
- `schema_version`
- `event_payload_jsonb`
- `status='PENDING'`
- `attempt_count=0`

- [x] **Step 3: Implement atomic claim**

```sql
UPDATE chunk_execution
SET
  status = 'PROCESSING',
  attempt_count = attempt_count + 1,
  processing_started_at = now(),
  lease_until = now() + (:processingTimeoutSeconds * interval '1 second'),
  updated_at = now()
WHERE execution_type = :executionType
  AND run_id = :runId
  AND endpoint = :endpoint
  AND chunk_id = :chunkId
  AND (
    status = 'PENDING'
    OR (status = 'FAILED_RETRYABLE' AND next_retry_at <= now())
    OR (status = 'PROCESSING' AND lease_until < now())
  )
RETURNING attempt_count;
```

Return a claim containing `attempt_count` when row returned.

- [x] **Step 4: Implement success**

```sql
UPDATE chunk_execution
SET
  status = 'SUCCEEDED',
  lease_until = NULL,
  updated_at = now()
WHERE execution_type = :executionType
  AND run_id = :runId
  AND endpoint = :endpoint
  AND chunk_id = :chunkId
  AND status = 'PROCESSING';
```

- [x] **Step 5: Implement retryable failure**

```sql
UPDATE chunk_execution
SET
  status = 'FAILED_RETRYABLE',
  next_retry_at = :nextRetryAt,
  first_failed_at = COALESCE(first_failed_at, now()),
  last_failed_at = now(),
  last_error = :error,
  lease_until = NULL,
  updated_at = now()
WHERE execution_type = :executionType
  AND run_id = :runId
  AND endpoint = :endpoint
  AND chunk_id = :chunkId
  AND status = 'PROCESSING';
```

- [x] **Step 6: Implement terminal failure**

Same as retryable, but:

```sql
status = 'FAILED_TERMINAL',
terminal_reason = :terminalReason,
next_retry_at = NULL
```

---

## Task 4: Consumer Rule Integration

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt`

- [x] **Step 1: Add event metadata to template request**

`ChunkConsumerRequest` needs:

- `identity: ChunkExecutionIdentity`
- `topic`
- `messageKey`
- `eventType`
- `schemaVersion`
- `eventPayloadJson`

- [x] **Step 2: Normal topic missing row rule**

On consume:

```text
insert PENDING if absent
read status
apply status rule
```

PR1 has only normal topics. Missing row always means first consume.

- [x] **Step 3: Status rules**

```text
SUCCEEDED -> ack
FAILED_TERMINAL -> ack
FAILED_RETRYABLE and now < next_retry_at -> ack
FAILED_RETRYABLE and now >= next_retry_at -> claim PROCESSING
PENDING -> claim PROCESSING
PROCESSING not expired -> ack
PROCESSING expired -> claim PROCESSING
```

`REPUBLISHING` not in PR1.

- [x] **Step 4: Ack after state write**

Only ack after:

- `SUCCEEDED` row update succeeds
- `FAILED_RETRYABLE` row update succeeds
- `FAILED_TERMINAL` row update succeeds
- skip state observed and logged

If status write fails, do not ack. Kafka redelivery handles it.

- [x] **Step 5: Failure classification**

PR1 simple policy:

```text
Artifact missing -> FAILED_RETRYABLE for first N attempts, then FAILED_TERMINAL
Unsupported schema -> FAILED_TERMINAL
Other exception -> FAILED_RETRYABLE
```

Config:

```yaml
chunk-execution:
  processing-timeout-seconds: 600
  retry:
    max-attempts: 5
    base-backoff-seconds: 60
    artifact-missing-max-attempts: 2
```

---

## Task 5: Tests

### Repository Tests

- [x] **Step 1: `insertPendingIfAbsent` idempotent**

Given same `(execution_type, run_id, endpoint, chunk_id)` twice:

- first insert returns true
- second returns false
- row count remains 1

- [x] **Step 2: claim PENDING**

PENDING row -> claim returns true -> status PROCESSING -> attempt_count 1 -> lease_until not null.

- [x] **Step 3: reject non-expired PROCESSING**

PROCESSING with future `lease_until` -> claim returns false.

- [x] **Step 4: reclaim expired PROCESSING**

PROCESSING with past `lease_until` -> claim returns true -> attempt_count increments.

- [x] **Step 5: mark success**

PROCESSING -> SUCCEEDED. lease cleared.

- [x] **Step 6: mark retryable failure**

PROCESSING -> FAILED_RETRYABLE. `first_failed_at`, `last_failed_at`, `next_retry_at`, `last_error` populated.

- [x] **Step 7: mark terminal failure**

PROCESSING -> FAILED_TERMINAL. `terminal_reason` populated.

### Consumer Tests

- [x] **Step 8: first consume inserts PENDING then processes**

Verify order:

```text
insertPendingIfAbsent
claimProcessing
business process
markSucceeded
ack
```

- [x] **Step 9: SUCCEEDED skip acks**

Existing SUCCEEDED -> no process -> ack.

- [x] **Step 10: PROCESSING not expired acks**

Existing PROCESSING with future lease -> no process -> ack.

- [x] **Step 11: expired PROCESSING reclaims**

Existing PROCESSING with expired lease -> process.

- [x] **Step 12: business failure writes failure before ack**

Process throws -> mark failure -> ack.

---

## Task 6: Observability

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerMetrics.kt`
- Add calculator metrics if calculator included.

- [x] **Step 1: Add counters**

```text
chunk_execution_inserted_total{execution_type}
chunk_execution_claimed_total{execution_type}
chunk_execution_skipped_total{execution_type,status}
chunk_execution_succeeded_total{execution_type}
chunk_execution_failed_total{execution_type,status,reason}
chunk_execution_reclaimed_expired_total{execution_type}
```

- [x] **Step 2: Add gauge query later (deferred; out of PR1)**

Out of PR1 unless cheap:

```text
chunk_execution_status_count{execution_type,status}
```

---

## Task 7: Validation

- [x] **Step 1: Run focused tests**

```bash
./gradlew --console=plain \
  :module-common:compileKotlin \
  :module-infra:compileKotlin \
  :module-synchronizer:compileKotlin \
  :module-synchronizer:test --tests '*ChunkExecution*'
```

- [x] **Step 2: Run existing synchronizer tests**

```bash
./gradlew --console=plain \
  :module-synchronizer:test --tests maple.synchronizer.processor.DefaultChunkProcessorTest
```

- [x] **Step 3: If calculator included, run calculator tests (not applicable; calculator deferred to PR1b)**

```bash
./gradlew --console=plain \
  :module-calculator:compileKotlin \
  :module-calculator:test --tests maple.calculator.CalculatorChunkProcessingCoordinatorTest
```

---

## Rollback

- Code rollback safe because old `synchronizer_chunk_status` table remains.
- Do not drop old table in PR1.
- If production issue occurs, disable new repository usage via config flag if implemented:

```yaml
chunk-execution:
  enabled: false
```

If no flag, revert PR.

---

## Out of Scope

- retry scheduler
- retry topics
- `REPUBLISHING`
- `RunDeletionGuard`
- admin replay
- `SUCCEEDED` force replay
- cleanup retention invariant
- upcaster registry

---

## Done Criteria

- `chunk_execution` migration exists
- consumer creates PENDING row on first consume
- atomic claim uses `lease_until`
- success/failure state persisted before ack
- duplicate/redelivery status rules covered by tests
- no retry scheduler in this PR
