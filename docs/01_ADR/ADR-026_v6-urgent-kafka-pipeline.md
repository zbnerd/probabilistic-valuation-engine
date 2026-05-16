# ADR-026: V6 Urgent Kafka Pipeline

- Status: Proposed
- Date: 2026-05-16
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- V6 read endpoint (`module-rest-controller`) serves character expectation data via Redis → DB → OCID lookup fallback chain.
- When all three layers miss (cold character), the user receives a 202 but must poll or wait for the batch pipeline to eventually process the character.
- Batch pipeline operates on scheduled chunks; latency from request to result can be minutes to hours.

### Problem

- No mechanism exists to trigger urgent (on-demand) data fetch for a single character outside the batch cycle.
- Cold-character users experience unacceptable latency with no feedback until the next batch run picks them up.

### Goal

- Add an urgent pipeline that fetches data for a single character on-demand when the V6 read path finds no data, returning results within seconds rather than waiting for the next batch cycle.

---

## 2. Decision

> Urgent pipeline uses dedicated Kafka topics separate from batch, triggered by `module-rest-controller` on cache miss.

```text
rest-controller (read miss)
  → SETNX dedup check (Redis)
  → Kafka: urgent-character-request
  → external-api: resolve OCID + fetch basic/item
     → Nexon 400 → Kafka: urgent-character-not-found → rest-controller → 404 + Redis negative cache
     → Success → Kafka: external-api.urgent.snapshot.chunk-ready
        → calculator (urgent consumer group)
        → synchronizer/basic (urgent consumer group)
  → calculator → Kafka: calculator.result.chunk-ready (existing shared topic)
  → synchronizer/result → DB write → Redis write
  → rest-controller reads result on next poll
```

Topic architecture:

| Topic | Publisher | Consumer |
|-------|-----------|----------|
| `urgent-character-request` | rest-controller | external-api |
| `urgent-character-not-found` | external-api | rest-controller |
| `external-api.urgent.snapshot.chunk-ready` | external-api | calculator (urgent group), synchronizer/basic (urgent group) |
| `calculator.result.chunk-ready` (existing) | calculator | synchronizer/result (existing, shared) |

---

## 3. Trade-offs

### Sensitivity

* Nexon API rate limit — urgent requests bypass batch pacing, can exhaust quota
* Kafka topic partition count — single-partition urgent topics serialize per-character; too few partitions limit parallelism
* Redis negative cache TTL — too short causes repeated Nexon 400 calls; too long delays valid retries
* Feature flag `expectation.v6.urgent.enabled` — controls entire urgent path; misconfiguration silently disables

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Dedicated urgent Kafka topics | Isolation from batch backlog; independent consumer group sizing | More topics to monitor and manage |
| Calculator publishes to shared result topic | No new result pipeline; existing synchronizer/result handles both urgent and batch | Urgent results compete with batch for consumer capacity |
| Redis SETNX dedup for trigger | Prevents duplicate urgent requests for same IGN | Additional Redis round-trip on every miss |
| Redis negative cache for Nexon 400 | Fast 404 response; protects Nexon API quota | Stale negative cache if character becomes valid (mitigated by TTL) |

### Risk

* Urgent requests could starve batch consumers if not properly throttled
* Calculator shared result topic becomes a coupling point — urgent SLA depends on batch not saturating consumers

### Non-Risk

* Urgent pipeline does not modify batch pipeline code paths — dedicated topics provide clean isolation
* Calculator and synchronizer processing speed is not the bottleneck; urgency bottleneck is rest-controller → external-api (OCID + API fetch)

---

## 4. Result / Evidence

### Metrics

| Metric | Target | Notes |
| ------ | ----: | ----- |
| Urgent pipeline end-to-end latency | < 5s (p99) | From trigger to result available in Redis |
| Nexon 400 → 404 response | < 100ms | Redis negative cache hit path |
| Urgent trigger dedup hit rate | > 90% | SETNX prevents redundant triggers |

### Observed Result

* To be measured after implementation.

---

## 5. Summary

> Urgent pipeline uses dedicated Kafka topics for request/snapshot stages while sharing the calculator result topic, trading topic sprawl for batch isolation with minimal operational overhead.
