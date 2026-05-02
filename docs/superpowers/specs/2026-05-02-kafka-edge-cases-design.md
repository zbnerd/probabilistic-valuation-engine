# Kafka Pipeline Edge Cases Design

Date: 2026-05-02
Branch: `feature/row-lease-leader-election`
Status: Draft

Companion document to `docs/09_Plans/2026-05-02-kafka-pipeline-transition-plan.md`.
Covers concurrency, race conditions, idempotency, poison messages, DLQ, and dual-write.

## 1. At-Least-Once Delivery Assumption

Kafka consumers must assume at-least-once delivery. Rebalance, crash, and offset commit failure can cause the same message to be processed by multiple consumers. Correctness comes from DB-level guards, not Kafka delivery guarantees.

## 2. Consumer Rebalance: DB CAS Claim

### Problem

Consumer A reads message M from partition 0. Rebalance reassigns partition 0 to consumer B. B also reads M. Both try to process the same job.

### Solution: Claim Before Side Effect

Consumer must not call external APIs or write results until DB CAS claim succeeds.

```
ExternalApiConsumer:
  1. Receive message (jobId)
  2. DB CAS: UPDATE calculation_jobs SET status = 'API_IN_PROGRESS',
     locked_until = now() + 60s WHERE job_id = :id AND status = 'API_REQUESTED'
  3. If 0 rows affected: already claimed by another consumer → ack
  4. If claimed: proceed with Nexon API call
  5. After success: CAS to SNAPSHOT_READY
```

### New Status: API_IN_PROGRESS

```
API_REQUESTED → API_IN_PROGRESS → SNAPSHOT_READY
```

- `API_REQUESTED`: eligible for claim
- `API_IN_PROGRESS`: one consumer owns it, guarded by `locked_until`
- `SNAPSHOT_READY`: API + snapshot done, eligible for calculation

### locked_until for Crash Recovery

```sql
UPDATE calculation_jobs
SET worker_id = :consumerId,
    locked_until = now() + interval '60 seconds'
WHERE id = :jobId
  AND status IN ('API_REQUESTED',
                  (SELECT status FROM calculation_jobs WHERE id = :jobId
                   AND status = 'API_IN_PROGRESS' AND locked_until < now()))
RETURNING id;
```

Expired `API_IN_PROGRESS` jobs are reclaimable. The retry publisher or a compensating scanner detects them and re-publishes to `external-api.requested`.

### CalculationConsumer: Same Pattern

```
CALCULATION_REQUESTED → CALCULATING → COMPLETED
```

CAS claim before loading input and running calculation.

## 3. Poison Message Handling

### Problem

Bad JSON, missing required fields, unsupported schema version. Consumer deserialization fails, triggering infinite retry. Partition stalls.

### Solution: Immediate DLT + Ack

Poison messages are never retried. They are isolated to DLT and the partition advances.

### Consumer Flow

```
1. Receive raw String payload
2. Parse JSON (catch JsonProcessingException → DLT)
3. Check schemaVersion (unsupported → DLT)
4. Validate required fields (missing → DLT)
5. Validation passed → DB CAS claim
```

### Error Classification

**Non-retryable (immediate DLT + ack):**
- JSON parse failure
- Unsupported schemaVersion
- Missing required field
- Invalid enum/status value
- CharacterNotFound (domain terminal failure → mark job FAILED + ack)

**Retryable (backoff, then DB retry state):**
- Nexon API timeout
- 429 rate limited
- Temporary DB connection failure
- Kafka produce failure

### DLT Payload Format

```json
{
  "originalTopic": "external-api.requested",
  "originalPartition": 0,
  "originalOffset": 12345,
  "consumerGroup": "maple-external-api",
  "errorType": "SCHEMA_VALIDATION_FAILED",
  "errorMessage": "missing required field: jobId",
  "payload": "{ ... original raw ... }",
  "failedAt": "2026-05-02T12:00:00Z"
}
```

DLT topics: `external-api.requested.DLT`, `calculation.requested.DLT`

## 4. Dual-Write: Transactional Outbox

### Problem

DB INSERT (job creation) + Kafka publish are not atomic. DB succeeds, Kafka fails → orphan job in REQUESTED state forever.

### Solution: Transactional Outbox Pattern

Never dual-write. Job creation and Kafka publish intent are recorded in the same DB transaction.

```
createOrFindActiveJob(created=true):
  TX {
    calculation_jobs INSERT
    kafka_outbox_events INSERT (event_type='external-api.requested')
  }
  commit
```

### kafka_outbox_events Table

```sql
CREATE TABLE kafka_outbox_events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_kafka_outbox_event_dedup
ON kafka_outbox_events (event_type, aggregate_id)
WHERE status IN ('PENDING', 'PUBLISHING', 'PUBLISHED');
```

### KafkaOutboxPublisher

Separate scheduled component that polls the outbox and publishes to Kafka.

Claim query:

```sql
WITH picked AS (
    SELECT id FROM kafka_outbox_events
    WHERE status = 'PENDING' AND next_attempt_at <= now()
    ORDER BY created_at LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE kafka_outbox_events e
SET status = 'PUBLISHING', updated_at = now()
FROM picked WHERE e.id = picked.id
RETURNING e.*;
```

After Kafka publish success:

```sql
UPDATE kafka_outbox_events
SET status = 'PUBLISHED', published_at = now(), updated_at = now()
WHERE id = :id AND status = 'PUBLISHING';
```

After Kafka publish failure:

```sql
UPDATE kafka_outbox_events
SET status = 'PENDING', retry_count = retry_count + 1,
    next_attempt_at = now() + interval '10 seconds',
    last_error = :error, updated_at = now()
WHERE id = :id;
```

### Edge Case: Publish Succeeds, Mark Fails

Kafka publish succeeds but DB `PUBLISHED` mark fails. Outbox stays in `PUBLISHING`. Next poll will re-publish the same message.

This is safe because consumers are idempotent (DB CAS + ON CONFLICT guards). The at-least-once publish + idempotent consume combination handles this.

## 5. Idempotency Chain (Existing Guards)

All guards from the current PGMQ pipeline remain valid for Kafka:

| Guard | Mechanism |
|-------|-----------|
| Job dedup | `request_key` partial unique index on active statuses |
| Terminal state skip | Check COMPLETED/FAILED before processing |
| CAS status transition | `UPDATE ... WHERE status = :from` returns affected rows |
| Snapshot input | `ON CONFLICT (job_id) DO NOTHING` |
| Result insert | `ON CONFLICT (job_id) DO NOTHING` |
| Outbox event | `ON CONFLICT (job_id, event_type) DO NOTHING` |

No new idempotency guards needed. Kafka consumers use the same DB-level protections.

## 6. Retry Strategy: DB State Centric

Job state is the source of truth for retries, not Kafka retry count.

```
Processing failure:
  → job status → RETRYING + next_attempt_at + retry_count increment
  → Kafka message ack
  → retry scanner picks up expired jobs and re-publishes to topic

Max retries exceeded:
  → job status → FAILED + error_code
  → Kafka message ack
  → DLT for operational visibility
```

This avoids dual retry state (Kafka + DB) and leverages the existing job state machine.

## 7. Complete Pipeline Flow with All Guards

```
API Request
→ createOrFindActiveJob (request_key dedup)
→ kafka_outbox INSERT (same TX)
→ commit

KafkaOutboxPublisher
→ claim outbox (FOR UPDATE SKIP LOCKED)
→ publish external-api.requested
→ mark PUBLISHED

ExternalApiConsumer
→ raw String → parse → validate (poison → DLT)
→ DB CAS claim: API_REQUESTED → API_IN_PROGRESS + locked_until
→ claim failed → ack (another consumer owns it)
→ claim success → Nexon API call
→ snapshot/input save (idempotent)
→ kafka_outbox INSERT calculation.requested
→ DB CAS: API_IN_PROGRESS → SNAPSHOT_READY
→ ack

KafkaOutboxPublisher
→ claim outbox
→ publish calculation.requested
→ mark PUBLISHED

CalculationConsumer
→ raw String → parse → validate (poison → DLT)
→ DB CAS claim: SNAPSHOT_READY → CALCULATING
→ claim failed → ack
→ claim success → load input → pure calculation
→ result persist (ON CONFLICT DO NOTHING)
→ outbox event INSERT (ON CONFLICT DO NOTHING)
→ DB CAS: CALCULATING → COMPLETED
→ ack

ResultReadyProjectionWorker (unchanged)
→ outbox direct polling
→ read model batch upsert
```

## 8. Design Principles Summary

1. At-least-once delivery assumed. Kafka is a transport, not a correctness guarantee.
2. All correctness comes from DB CAS + unique constraints + idempotent writes.
3. External API calls are guarded by DB CAS claim. Only the claiming consumer calls the API.
4. Poison messages are never retried. DLT + ack.
5. Dual-write is forbidden. Transactional outbox for all Kafka publishes.
6. Retry state lives in the job table, not Kafka. Consumers ack after recording failure state.
7. `locked_until` handles consumer crash. Expired leases are reclaimable.
