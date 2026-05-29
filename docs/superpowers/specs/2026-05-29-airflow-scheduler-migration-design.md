# Airflow Scheduler Migration Design

## Goal

Migrate 4 batch/operational schedulers from Spring Boot `@Scheduled` to Airflow trigger endpoints. Single authority scheduling, multi-instance safety, no cron duplication.

## Scope

### Migrate (4 schedulers → Airflow trigger endpoints)

| Module | Scheduler | Current | Trigger Endpoint |
|--------|-----------|---------|-----------------|
| external-api | ExternalApiScheduler.scheduledDailyRefresh() | cron 03:00 | `POST /api/internal/trigger/daily` (already done) |
| external-api | ArtifactCleanupScheduler.cleanup() | fixedDelay 6h | `POST /api/internal/trigger/artifact-cleanup` |
| external-api | ConsumedChunkCleanupScheduler.cleanup() | fixedDelay 1h | `POST /api/internal/trigger/consumed-cleanup` |
| calculator | CalculatorResultCleanupScheduler.cleanup() | fixedDelay 6h | `POST /api/internal/trigger/result-cleanup` |

### Keep (event-driven, realtime, PGMQ, legacy)

All 28 remaining `@Scheduled` in module-infra, module-rest-controller, module-app — no changes.

## Architecture

```
Airflow (Control Plane)
├── DAG 1: daily_collection_pipeline (03:00 KST)
│   ├── health-check (HttpSensor)
│   ├── trigger daily (HttpOperator → POST /trigger/daily)
│   └── poll completion (PythonOperator → GET /run-status)
│
└── DAG 2: daily_cleanup_pipeline (TriggerDagRunOperator, after DAG 1 success)
    ├── trigger artifact-cleanup  → POST /api/internal/trigger/artifact-cleanup  → 202
    ├── trigger consumed-cleanup  → POST /api/internal/trigger/consumed-cleanup  → 202
    └── trigger result-cleanup    → POST /api/internal/trigger/result-cleanup    → 202
    (all parallel)
```

### Multi-instance

Each instance gets its own Airflow HTTP connection (`external_api_1`, `external_api_2`). Cleanup DAG triggers all instances. Each instance cleans its local data.

When MinIO replaces local FS, all instances share object storage — cleanup still works, Kafka consumer group partitions work across instances.

### Data flow

```
Pipeline run
  ├── Produces chunk files → Object Storage (local FS now, MinIO later)
  ├── Kafka ChunkConsumedEvent on success
  └── Airflow detects completion via run-status

Cleanup (after pipeline completes)
  ├── Consumes Kafka ChunkConsumedEvent
  ├── Deletes only successfully consumed chunks from Object Storage
  └── Artifact cleanup respects keep-recent / keep-within-hours config
```

## Changes

### module-external-api

1. **ArtifactCleanupScheduler** — remove `@Scheduled`, keep `cleanup()` method
2. **ConsumedChunkCleanupScheduler** — remove `@Scheduled`, keep `cleanup()` method
3. **InternalApiController** — add 2 trigger endpoints:
   - `POST /api/internal/trigger/artifact-cleanup` → 202 fire-and-forget
   - `POST /api/internal/trigger/consumed-cleanup` → 202 fire-and-forget
4. **application.yml** — set cleanup enabled=false (disable self-scheduling)

### module-calculator

1. **CalculatorResultCleanupScheduler** — remove `@Scheduled`, keep `cleanup()` method
2. **InternalApiController** (new) — add trigger endpoint:
   - `POST /api/internal/trigger/result-cleanup` → 202 fire-and-forget
3. **application.yml** — set cleanup enabled=false

### docker/airflow/dags/

1. **daily_cleanup_pipeline.py** (new) — 3 parallel HttpOperator tasks
2. **daily_collection_pipeline.py** — add `TriggerDagRunOperator` at end to chain DAG 2

### Airflow connections

Add HTTP connections for each instance:
- `external_api` → http://host.docker.internal:8081
- `calculator` → http://host.docker.internal:8082
- Future: `external_api_2`, `calculator_2` for multi-node

## Trigger endpoint pattern

All cleanup endpoints follow the same pattern as existing `/trigger/daily`:

```kotlin
@PostMapping("/trigger/{cleanup-type}")
fun triggerCleanup(): ResponseEntity<Map<String, String>> {
    if (alreadyRunning) return 409 ALREADY_RUNNING
    triggerExecutor.submit { cleanupMethod() }
    return 202 {"status": "STARTED"}
}
```

Fire-and-forget: 202 immediate response, cleanup runs on virtual thread.

## Out of scope

- Object storage migration (MinIO) — separate effort
- Dynamic instance discovery — static connections for now
- PGMQ workers, outbox scanners, monitoring schedulers — keep as-is
- module-app legacy schedulers — untouched
