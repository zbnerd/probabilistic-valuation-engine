# Kafka Pipeline Transition Plan

Date: 2026-05-02
Branch: `feature/row-lease-leader-election`

Companion document: `docs/superpowers/specs/2026-05-02-kafka-edge-cases-design.md` — covers concurrency, race conditions, idempotency, poison messages, DLQ, and dual-write in detail.

## 1. Current Structure

The current V5 pipeline uses PGMQ as the main durable queue.

The hot path is:

```text
Client / API
-> createOrFindActiveJob
-> external_api_queue
-> ExternalApiWorker
   -> OCID resolve
   -> Nexon equipment API fetch
   -> snapshot/input staging
   -> pure calculation
   -> result serialize/gzip/hash
   -> result persist
   -> job complete
   -> outbox insert
-> outbox direct projection
```

The queue name `external_api_queue` no longer describes its actual responsibility. In consolidated mode it owns external API I/O, CPU-heavy calculation, DB persistence, and outbox publication. When a bottleneck appears, the metric attribution collapses into `external_api_queue` or `ExternalApiWorker:Pipeline`, which makes it hard to separate API latency, CPU pressure, DB write pressure, and projection delay.

The existing split PGMQ path has `calculation_requested_queue` and `calculation_completed_queue`, but enabling every step-level queue increases PostgreSQL read/write/archive work because PGMQ is stored in the same primary database that also handles job state, result persistence, projection writes, outbox, and retry scanning.

Docker Compose already includes Kafka:

- Image: `confluentinc/cp-kafka:latest`
- Mode: KRaft, no ZooKeeper
- Host bootstrap server: `localhost:9092`
- Advertised listener: `PLAINTEXT://localhost:9092`
- Topic auto-create is not explicitly configured

Spring Kafka is not currently wired for the calculation pipeline. `KafkaEventPublisher` exists, but it is a stub and belongs to a generic `EventPublisher` path rather than the V5 calculation pipeline.

## 2. Target Kafka Structure

Do not map every PGMQ queue to Kafka 1:1.

Use Kafka to split the God MQ into two coarse-grained responsibilities:

```text
Client / API
-> createOrFindActiveJob
-> Kafka topic: external-api.requested

ExternalApiConsumer
-> OCID resolve
-> Nexon equipment API fetch
-> snapshot/input staging
-> job status to SNAPSHOT_READY
-> Kafka topic: calculation.requested

CalculationConsumer
-> calculation input load
-> pure calculation
-> result serialize/gzip/hash
-> result persist
-> job COMPLETED
-> outbox insert

OutboxProjectionConsumer
-> outbox direct polling
-> read model projection
-> batch mark published
```

Projection stays on direct outbox polling. Do not introduce `result.ready`, `calculation.completed`, `snapshot.ready`, or `projection.requested` topics in the first implementation.

## 3. Topic Design

### `external-api.requested`

Responsibility:

- I/O-bound stage
- OCID resolve
- Nexon equipment API fetch
- Calculation input and snapshot staging
- API retry policy and API error classification

Consumer group:

- `maple-external-api`

### `calculation.requested`

Responsibility:

- CPU-heavy calculation
- Result serialization, gzip, hash
- DB result persistence
- Job completion
- Outbox insert

Consumer group:

- `maple-calculation`

Excluded from first implementation:

- `calculation.completed`
- `result.ready`
- `snapshot.ready`
- `projection.requested`

## 4. Payload Schema

Kafka payloads should contain references, not large JSON blobs.

### `external-api.requested`

```json
{
  "schemaVersion": 1,
  "jobId": "uuid",
  "requestKey": "calc:v1:ign:{normalizedUserIgn}:preset:{presetNo}:schema:1",
  "userIgn": "string",
  "presetNo": 1,
  "traceId": "optional",
  "createdAt": "iso-8601"
}
```

Required fields:

- `schemaVersion`
- `jobId`
- `requestKey`
- `userIgn`
- `presetNo`

Optional fields:

- `traceId`
- `createdAt`

### `calculation.requested`

```json
{
  "schemaVersion": 1,
  "jobId": "uuid",
  "requestKey": "calc:v1:ign:{normalizedUserIgn}:preset:{presetNo}:schema:1",
  "userIgn": "string",
  "presetNo": 1,
  "characterId": "ocid",
  "characterClass": "string",
  "snapshotId": "uuid",
  "traceId": "optional",
  "createdAt": "iso-8601"
}
```

Required fields:

- `schemaVersion`
- `jobId`
- `requestKey`
- `userIgn`
- `presetNo`
- `characterId`
- `characterClass`
- `snapshotId`

Do not put equipment snapshot JSON, calculation input JSON, result JSON, or gzip result bytes in Kafka payloads. Keep those in DB/object storage and pass references through Kafka.

## 5. Key And Partition Strategy

### `external-api.requested`

Recommended key: `requestKey`

Reasoning:

- Available before OCID resolution
- Aligns with active job dedup
- Preserves ordering for the same user/preset request

Tradeoff:

- Hot user/preset keys can skew a partition

Rejected first-pass keys:

- `ocid`: not available before OCID resolve
- `jobId`: distributes well, but weakens request-level ordering and dedup semantics

### `calculation.requested`

Recommended key: `jobId`

Reasoning:

- Calculation work is job-scoped and independent
- Good partition distribution
- DB idempotency and CAS are already job-based

Rejected first-pass keys:

- `calculationKey`: useful later when calculation-level convergence is introduced
- `ocid:presetNo`: higher hot-key skew risk

## 6. Consumer Responsibility Split

### ExternalApiConsumer

Can reuse most of `ExternalApiWorker`'s current external API portion:

- OCID cache lookup and resolve
- Nexon equipment fetch via `EquipmentFetchProvider`
- equipment response to `CalculationInput`
- snapshot object store write
- `calculationInputPort.saveIfAbsent`
- snapshot metadata save
- job transition to `SNAPSHOT_READY`

It must not run pure calculation or persist calculation result.

The seam is the current `consolidatedEnabled=false` branch of `ExternalApiWorker`, but the publish target should become Kafka `calculation.requested`, not PGMQ `calculation_requested_queue`.

### CalculationConsumer

Can reuse the logic from `CalculationRequestedWorker` and `CalculationExecutionService`:

- Check job status
- CAS `SNAPSHOT_READY -> CALCULATING`
- Load calculation input
- Run pure calculation
- Serialize/gzip/hash result
- Persist result
- CAS `CALCULATING -> COMPLETED`
- Insert outbox event

Do not add a `calculation.completed` Kafka topic in the first implementation. Persist result in the same consumer after CPU work.

## 7. Retry And DLQ Strategy

Use at-least-once processing. Correctness must come from DB idempotency and CAS:

- `calculation_jobs.request_key` active unique index
- `calculation_snapshot_inputs.job_id UNIQUE`
- `calculation_results.job_id UNIQUE`
- `outbox_events UNIQUE(job_id, event_type)`
- status transition CAS

Recommended first-pass Spring Kafka strategy:

- `DefaultErrorHandler`
- bounded backoff
- DLT for unrecoverable technical failures
- explicit non-retry handling for known business failures

Error policy:

- `CharacterNotFound` / `OPENAPI00004`: do not retry; mark job `FAILED/CHARACTER_NOT_FOUND`
- 401 / 403: do not hot-loop; mark operational failure or DLT
- 429 / timeout / 5xx: retry with exponential backoff
- DB persist failure: retry `calculation.requested`; idempotent inserts and CAS protect duplicates

Do not archive or commit a message before the business state transition for that stage is durable.

### DLT Topics

- `external-api.requested.DLT`
- `calculation.requested.DLT`

DLT payload format:

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

### DB Retry State Centric

Job state is the source of truth for retries, not Kafka retry count.

```
Processing failure:
  -> job status -> RETRYING + next_attempt_at + retry_count increment
  -> Kafka message ack
  -> retry scanner picks up expired jobs and re-publishes to topic

Max retries exceeded:
  -> job status -> FAILED + error_code
  -> Kafka message ack
  -> DLT for operational visibility
```

This avoids dual retry state (Kafka + DB) and leverages the existing job state machine.

## 8. Consumer Rebalance And DB CAS Claim

### Problem

Consumer A reads message M from partition 0. Rebalance reassigns partition 0 to consumer B. B also reads M. Both try to process the same job.

### Solution: Claim Before Side Effect

Consumer must not call external APIs or write results until DB CAS claim succeeds.

```
ExternalApiConsumer:
  1. Receive message (jobId)
  2. DB CAS: UPDATE calculation_jobs SET status = 'API_IN_PROGRESS',
     locked_until = now() + 60s WHERE job_id = :id AND status = 'API_REQUESTED'
  3. If 0 rows affected: already claimed by another consumer -> ack
  4. If claimed: proceed with Nexon API call
  5. After success: CAS to SNAPSHOT_READY
```

### New Status: API_IN_PROGRESS

```
API_REQUESTED -> API_IN_PROGRESS -> SNAPSHOT_READY
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
SNAPSHOT_READY -> CALCULATING -> COMPLETED
```

CAS claim before loading input and running calculation.

## 9. Poison Message Handling

### Problem

Bad JSON, missing required fields, unsupported schema version. Consumer deserialization fails, triggering infinite retry. Partition stalls.

### Solution: Immediate DLT + Ack

Poison messages are never retried. They are isolated to DLT and the partition advances.

### Consumer Flow

```
1. Receive raw String payload
2. Parse JSON (catch JsonProcessingException -> DLT)
3. Check schemaVersion (unsupported -> DLT)
4. Validate required fields (missing -> DLT)
5. Validation passed -> DB CAS claim
```

### Error Classification

**Non-retryable (immediate DLT + ack):**

- JSON parse failure
- Unsupported schemaVersion
- Missing required field
- Invalid enum/status value
- CharacterNotFound (domain terminal failure -> mark job FAILED + ack)

**Retryable (backoff, then DB retry state):**

- Nexon API timeout
- 429 rate limited
- Temporary DB connection failure
- Kafka produce failure

## 10. Transactional Outbox Pattern (Dual-Write Prevention)

### Problem

DB INSERT (job creation) + Kafka publish are not atomic. DB succeeds, Kafka fails -> orphan job in REQUESTED state forever.

### Solution: Never Dual-Write

Job creation and Kafka publish intent are recorded in the same DB transaction.

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

## 11. Idempotency Chain (Existing Guards)

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

## 12. Metrics Design

Kafka-level metrics:

- `kafka.producer.send.latency{topic}`
- `kafka.producer.error.total{topic}`
- `kafka.consumer.process.latency{topic,group}`
- `kafka.consumer.error.total{topic,group,errorCode}`
- `kafka.consumer.lag{topic,group,partition}`

External API stage metrics:

- `ExternalApiConsumer:ResolveOcid`
- `ExternalApiConsumer:FetchEquipment`
- `ExternalApiConsumer:BuildCalculationInput`
- `ExternalApiConsumer:SaveCalculationInput`
- `ExternalApiConsumer:SnapshotPut`
- `ExternalApiConsumer:SaveSnapshotMetadata`
- `ExternalApiConsumer:PublishCalculationRequested`

Calculation stage metrics:

- `CalculationConsumer:LoadInput`
- `CalculationConsumer:PureCalculate`
- `CalculationConsumer:SerializeResult`
- `CalculationConsumer:GzipResult`
- `CalculationConsumer:HashResult`
- `CalculationConsumer:PersistResult`

Projection metrics:

- `ResultProjection:ProjectBatch`
- `PostgresQuery:BatchUpsertFromCalculation`
- `ReadModel:BestEffortBatchWrite`
- outbox unpublished count
- outbox projection latency

The goal is to stop reporting "external_api_queue is slow" as the only diagnosis. The slow stage must be visible directly.

## 13. Feature Flag And Rollback

Recommended flags:

```yaml
app:
  messaging:
    transport: pgmq # pgmq|kafka
  kafka:
    pipeline:
      enabled: false
```

Rules:

- `transport=pgmq`: existing PGMQ dispatch and PGMQ workers handle the pipeline
- `transport=kafka`: Kafka dispatch and Kafka consumers handle the two-topic pipeline
- Do not dual-dispatch in the first implementation
- Do not allow PGMQ and Kafka to enqueue the same job at the same time
- Rollback affects new requests by switching transport back to `pgmq`
- Existing Kafka messages should be drained or made harmless through job-state idempotency

Kafka publish failure after job creation needs an explicit policy:

- preferred: keep job in `REQUESTED` and let a compensating scanner republish
- acceptable for PoC: mark job failed with a publish error and return rejected receipt

This decision must be explicit before PR-2.

## 14. PR Implementation Plan

### PR-1: Kafka Foundation

Scope:

- Add Spring Kafka dependency
- Add Kafka configuration/properties
- Define topic names
- Add producer/consumer skeletons behind disabled feature flag
- Create `kafka_outbox_events` table migration
- Add `KafkaOutboxPublisher` skeleton (scheduled, behind feature flag)
- No business dispatch change

Success criteria:

- App starts with Kafka disabled
- Existing PGMQ flow unchanged
- Kafka config can connect to local `localhost:9092` when enabled
- `kafka_outbox_events` table exists
- `KafkaOutboxPublisher` compiles but does not run when disabled

### PR-2: `external-api.requested`

Scope:

- Introduce calculation pipeline dispatch abstraction
- Route created jobs to Kafka outbox when `transport=kafka`
- Publish only when `createOrFindActiveJob.created=true`
- Implement ExternalApiConsumer with DB CAS claim
- Add `API_IN_PROGRESS` status and `locked_until` to `calculation_jobs`
- Parse/validate raw String payload (poison -> DLT)
- Stop after input/snapshot staging and publish `calculation.requested` via outbox

Success criteria:

- New job reaches `SNAPSHOT_READY`
- `calculation.requested` outbox event exists
- No duplicate dispatch for reused active jobs
- Poison messages routed to DLT
- Expired `API_IN_PROGRESS` jobs reclaimable by scanner

### PR-3: `calculation.requested`

Scope:

- Implement CalculationConsumer with DB CAS claim
- Parse/validate raw String payload (poison -> DLT)
- Claim `SNAPSHOT_READY -> CALCULATING`
- Run pure calculation
- Persist result and complete job
- Insert outbox event

Success criteria:

- Job reaches `COMPLETED`
- `calculation_results` has one row per job
- `outbox_events` has one `CALCULATION_COMPLETED` per job
- Outbox direct projection creates/updates `character_valuation_views`
- Poison messages routed to DLT

### PR-4: Load Test And Metrics

Scope:

- Compare PGMQ vs Kafka
- Capture 6 samples at 30-second intervals for the standard cold-miss load test
- Report stage p95, Kafka lag, DB pool pressure, outbox projection latency

Success criteria:

- Bottleneck attribution is stage-specific
- Kafka path does not increase primary DB PGMQ pressure
- Sustained throughput improves or the remaining DB bottleneck is clearly identified

## 15. Candidate Files To Modify

No files are modified by this plan except this document.

Implementation candidates:

- `gradle/libs.versions.toml`
- `module-infra/build.gradle`
- `module-app/src/main/resources/application.yml`
- `module-app/src/main/resources/application-local.yml`
- `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt`
- new `TopicNames` or messaging port under `module-core`
- new Kafka config under `module-infra`
- new Kafka producer under `module-infra`
- new Kafka consumers under `module-infra`
- new `KafkaOutboxPublisher` scheduled component under `module-infra`
- new `kafka_outbox_events` migration under `module-infra/src/main/resources/db/migration/`
- new outbox repository under `module-infra`
- `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationRequestedWorker.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt`

## 16. Risks And Decision Points

Risks:

- Kafka adds hop latency
- Topic over-splitting can recreate the current queue-hop problem
- At-least-once delivery requires strict idempotency
- Kafka publish and DB transaction are not atomic (addressed by Transactional Outbox)
- Consumer lag can hide slow downstream stages
- `requestKey` partitioning can create hot partitions
- Postgres result/projection write bottleneck can remain even after PGMQ removal
- Docker Compose Kafka adds local operational complexity
- Consumer crash leaves `API_IN_PROGRESS` jobs stranded (addressed by `locked_until` + scanner)
- Outbox re-publish after `PUBLISHING` mark failure duplicates Kafka messages (addressed by idempotent consumers)
- Dual retry state (Kafka retry + DB retry) can cause confusion (addressed by DB-centric retry)

Decision points:

- Use `requestKey` or `jobId` for `external-api.requested` key
- Decide publish-failure policy after job creation
- Decide whether topic creation is app-managed or docker/admin-managed
- Decide initial partitions for local and production
- Decide when to remove the PGMQ split queues after Kafka path is proven
- Decide `API_IN_PROGRESS` lease duration (default 60s, must exceed p99 API latency)
- Decide outbox publisher poll interval and batch size
- Decide DLT retention policy and alerting

## 17. Office-Hours Decisions (2026-05-02)

### Core Motivation (confirmed)

God MQ separation + PGMQ DB load reduction. Both required.

### Key Principle: Kafka = Event/Pointer

Nexon API responses are 200-300KB. This data must NOT flow through Kafka topics.

```
Kafka message <= few KB (jobId, snapshotId, requestKey)
Large data = snapshot object store / Postgres snapshot table
Postgres = job state, results, idempotency constraints
```

This is already the plan's Section 4 design. Reinforced because:

- 100 req/sec x 300KB = 30MB/sec raw, 90MB/sec with replication factor 3
- PGMQ stores this in primary DB: row + index + WAL + claim update + archive + autovacuum
- Kafka append-log + internal batching handles individual requests better than PGMQ

### Decisions Resolved

| Decision | Resolution |
|----------|-----------|
| Publish failure policy | Compensating scanner republishes |
| Topic management | App-managed (Spring Kafka auto-create) |
| Partition key `external-api.requested` | `requestKey` (pre-OCID, aligns with job dedup) |
| Partition key `calculation.requested` | `jobId` (independent jobs, good distribution) |
| Initial partitions | local: 1, production: 6 (external-api), `availableProcessors` (calculation) |
| DLQ strategy | Spring Kafka DefaultErrorHandler + DLT, exponential backoff 1-16s, max 5 retries |
| Idempotency | Existing DB UNIQUE constraints + CAS status transitions |
| Feature flag | `app.messaging.transport: pgmq\|kafka`, matchIfMissing=pgmq |
| Coexistence | Single transport active. Never dual-dispatch. |
| Rollback | Set transport=pgmq. Kafka messages harmless via job-state idempotency. |
| Dual-write prevention | Transactional Outbox (`kafka_outbox_events` table) |
| Consumer rebalance safety | DB CAS claim + `API_IN_PROGRESS` status + `locked_until` |
| Poison message handling | Raw String consumer, parse/validate before deserialization, immediate DLT + ack |
| Retry state source of truth | Job table (DB), not Kafka retry count |

### Operational Parameters

`external-api.requested`:

- Partitions: 6
- Consumer threads: 6 (virtual thread for API I/O)
- Poll timeout: 500ms
- Max poll records: 10 (API ~1s each -> 10s processing window)
- Auto offset reset: earliest

`calculation.requested`:

- Partitions: `availableProcessors`
- Consumer threads: `availableProcessors` (Dispatchers.Default alignment)
- Max poll records: 50 (CPU-bound, high batch efficiency)
- Auto offset reset: earliest

### Observability

- Kafka lag per topic/partition (Kafka JMX -> Micrometer)
- Stage-level p95 latency (TaskContext -> slow task log)
- Error rate by errorCode (LogicExecutor + Kafka error handler)
- DB pool usage (HikariCP metrics)
- Outbox unpublished count (SQL query)

## 18. Complete Pipeline Flow with All Guards

```
API Request
-> createOrFindActiveJob (request_key dedup)
-> kafka_outbox INSERT (same TX)
-> commit

KafkaOutboxPublisher
-> claim outbox (FOR UPDATE SKIP LOCKED)
-> publish external-api.requested
-> mark PUBLISHED

ExternalApiConsumer
-> raw String -> parse -> validate (poison -> DLT)
-> DB CAS claim: API_REQUESTED -> API_IN_PROGRESS + locked_until
-> claim failed -> ack (another consumer owns it)
-> claim success -> Nexon API call
-> snapshot/input save (idempotent)
-> kafka_outbox INSERT calculation.requested
-> DB CAS: API_IN_PROGRESS -> SNAPSHOT_READY
-> ack

KafkaOutboxPublisher
-> claim outbox
-> publish calculation.requested
-> mark PUBLISHED

CalculationConsumer
-> raw String -> parse -> validate (poison -> DLT)
-> DB CAS claim: SNAPSHOT_READY -> CALCULATING
-> claim failed -> ack
-> claim success -> load input -> pure calculation
-> result persist (ON CONFLICT DO NOTHING)
-> outbox event INSERT (ON CONFLICT DO NOTHING)
-> DB CAS: CALCULATING -> COMPLETED
-> ack

ResultReadyProjectionWorker (unchanged)
-> outbox direct polling
-> read model batch upsert
```

## 19. Design Principles Summary

1. At-least-once delivery assumed. Kafka is a transport, not a correctness guarantee.
2. All correctness comes from DB CAS + unique constraints + idempotent writes.
3. External API calls are guarded by DB CAS claim. Only the claiming consumer calls the API.
4. Poison messages are never retried. DLT + ack.
5. Dual-write is forbidden. Transactional Outbox for all Kafka publishes.
6. Retry state lives in the job table, not Kafka. Consumers ack after recording failure state.
7. `locked_until` handles consumer crash. Expired leases are reclaimable.
