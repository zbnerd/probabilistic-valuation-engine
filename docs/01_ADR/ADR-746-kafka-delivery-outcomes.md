# ADR-746: Kafka delivery outcomes

- Status: Accepted
- Date: 2026-07-20
- Owner: ETL pipeline

---

## 1. Background / Problem

### Background

- Active ETL services consume Kafka records through workload-local listeners.
- ACK, retry, backpressure, and DLT behavior is split between workload code and a shared legacy container factory.

### Problem

- Some workloads ACK before required asynchronous sends finish, retry ownership can be nested, and capacity failures can silently drop records.
- Secret-bearing auth records need a DLT boundary that cannot copy credentials.

### Goal

- Give each record one explicit durable outcome and one technical retry owner while preserving existing topics, groups, keys, partitions, and event JSON.

---

## 2. Decision

> Create `module-pipeline-messaging`. Workloads return a closed `DeliveryOutcome` and expose `PipelineSubscription` beans. The messaging module alone owns per-partition serial lanes, manual-immediate ACK, one initial plus three fixed-backoff retries, partition pause/resume, DLT publication, secret sanitization, and delivery metrics. Synchronizer lease attempts remain business state. No distributed transaction or wire-schema change is introduced.

```text
Kafka record -> partition lane -> workload outcome -> retry/backpressure/DLT -> safe ACK
```

---

## 3. Trade-offs

### Sensitivity

- Partition count, `max.poll.records`, handler latency, and retry/DLT availability.
- Workload idempotency during rebalances and replay.

### Trade-off

| Choice | Gain | Cost |
| -- | -- | -- |
| Per-partition serial lanes | Monotonic ACK and partition-local isolation | At most one in-flight workload call per partition |
| DLT-before-commit | No acknowledged record without a durable terminal destination | DLT outage pauses source progress |
| Closed workload outcomes | One retry owner and transport-free business handlers | All active listeners must migrate together |

### Risk

- Long handler latency can hold a partition paused and increase lag.
- Rebalance completion can repeat durable work, so workload idempotency remains mandatory.

### Non-Risk

- Wire schemas, topic names, group IDs, keys, and partition selection do not change.
- Synchronizer business lease/attempt semantics do not move into the transport adapter.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| -- | --: | -- |
| Static active listener paths | 9 | Calculator 2, synchronizer 4, external API 2, and cleanup 1 |
| Calculator nested handler attempts | Up to 16 | Four workload attempts combined with one initial plus three container attempts |
| Technical retry target | 3 | Fixed one-second backoff after migration |

### Observed Result

- The static baseline and the reproducible before/after measurement protocol are recorded in `docs/05_Reports/2026-07-19-kafka-delivery-outcome-evidence.md`.
- Runtime/load measurements are intentionally deferred under the approved focused-verification ceiling; no values are inferred.

---

## 5. Summary

> A shared messaging boundary owns ACK, technical retry, partition backpressure, and secret-safe DLT delivery after durable workload completion.
