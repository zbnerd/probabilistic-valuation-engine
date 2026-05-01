# ADR-389: Request-Key Active Job Dedup Without Equipment Advisory Lock

## Status

Accepted

## Context

The V5 cold-miss load test sends many distinct character requests through:

```text
Controller -> calculation_jobs -> external_api_queue -> ExternalApiWorker -> EquipmentFetchProvider
```

The equipment cache miss path also used `NexonDataCacheAspect` and PostgreSQL advisory-lock leader election before calling the Nexon equipment API. That is useful for hot-key cache stampede protection, but the V5 cold-miss workload is mostly unique OCIDs. In that case, nearly every request becomes the leader and still pays the fixed lock overhead:

- lock id generation
- lock pool connection acquisition
- `pg_try_advisory_xact_lock`
- leader election transaction
- possible follower polling

PostgreSQL is already used for primary data, PGMQ queues, job state, result writes, snapshots, outbox, retry scanning, and projections. Adding advisory-lock coordination to every unique equipment miss competes with the same database resources while providing little deduplication benefit.

## Decision

For the first implementation phase, converge duplicate calculation requests at `calculation_jobs` using a deterministic request key:

```text
calc:v1:ign:{normalizedUserIgn}:preset:{presetNo}:schema:1
```

The active job claim is the `calculation_jobs` insert itself. `createOrFindActiveJob` returns both the job and whether it was newly created.

- `created=true`: transition the job and publish one `external_api_queue` message.
- `created=false`: return the existing active job id and do not publish a new external API message.

The active unique index covers:

- `REQUESTED`: queued but not dispatched yet
- `OCID_RESOLVING`: resolving IGN to OCID
- `API_REQUESTED`: external API/equipment stage
- `SNAPSHOT_READY`: result write/calculation completion boundary
- `RETRYING`: retry scheduled and still active
- `CALCULATING`: retained for compatibility with existing status transitions even though the consolidated worker usually completes from `SNAPSHOT_READY`

`COMPLETED` and `FAILED` are excluded so a later request can create a new job.

The equipment cache advisory lock remains available as infrastructure, but the equipment cache miss path can bypass it by configuration. Local/load-test configuration disables this single-flight path.

## Implementation Notes

PostgreSQL partial unique indexes cannot be referenced by a simple column-only `ON CONFLICT (request_key)` unless the conflict predicate is repeated exactly. To keep the first change small, the insert uses:

```sql
INSERT ... ON CONFLICT DO NOTHING RETURNING job_id
```

Then it queries the active row by `request_key` when the insert did not return a row. This can also suppress inserts that conflict with another unique constraint, so the adapter falls back to the existing active `user_ign + preset_no` lookup if needed. A later tightening can specify the conflict predicate explicitly or remove overlapping legacy active indexes after validation.

## Deferred

This phase does not add `calculation_key`, OCID-based convergence, `external_api_jobs`, or `equipment_fetch_claims`. Those would add DB writes to every cold miss and should be validated separately after request-key dedup and advisory-lock bypass are measured.
