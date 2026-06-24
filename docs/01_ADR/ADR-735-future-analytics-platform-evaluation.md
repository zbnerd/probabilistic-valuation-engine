# ADR-735: Future Analytics Platform Evaluation

- Status: Proposed
- Date: 2026-06-23
- Owner: Architecture Team

---

## 1. Background / Problem

### Background

`probabilistic-valuation-engine` processes ~595K user snapshots/day through a 4-service Spring Boot pipeline (external-api → calculator → synchronizer → REST). Today the system produces 40M items/day, ~340GB/day internal storage after compression, ~10TB/month, ~120TB/year. Serving layer is PostgreSQL 16 read models + L1 Caffeine + L2 PostgreSQL UNLOGGED. Chunk artifacts persist on MinIO as JSONL.gz at `data/runs/{runId}/{endpoint}/chunks/part-{NNNN}.jsonl.gz`. Per-character query p95 < 100ms.

Roadmap signals indicate future analytical workloads not expressible efficiently on the current PG-only path:

- Class/world/level-range rollups over the full user base
- Historical trend analysis (multi-week expectation drift)
- Top-N rankings (top 100 per class/world)
- Recommendation systems and ML feature pipelines

### Problem

Without an analytics tier, every analytical workload becomes (a) an expensive PG query that competes with serving reads, (b) a one-off Python script over MinIO JSONL.gz, or (c) a deferred "we'll build it later." Each candidate platform (Iceberg+Trino, Iceberg+Spark, ClickHouse, PostgreSQL-only with materialized views) carries different cost/complexity/ML-fit trade-offs.

### Goal

Choose a default analytics strategy with measurable trigger conditions for when to escalate to a heavier platform. Avoid premature introduction of distributed compute engines.

---

## 2. Decision

> **Default: PostgreSQL-only with materialized views + declarative time-bucketed partitioning. Adopt ClickHouse as a parallel analytical store when analytical query p95 exceeds 10s on indexed PG queries or when Top-N/class-rollup workloads become user-facing APIs.**

```text
Today (Phase 0):
  Per-character query ─── PG read model + L1/L2 cache ─── p95 < 100ms
  Analytical query ─────── PG materialized views, refreshed by Airflow

Phase 1 trigger (any one):
  T1: Analytical query p95 > 10s on indexed PG (EXPLAIN ANALYZE-verified)
  T2: Analytical load > 20% of DB CPU during peak hour
  T3: Top-N leaderboard or class-rollup becomes user-facing API contract

Phase 2 trigger (any one on top of Phase 1):
  T4: ≥30TB active historical corpus retained for analytics
  T5: Time-travel / point-in-time read model snapshot needed
  T6: Cross-source SQL join required (MinIO chunks + PG read model)

Phase 3 trigger:
  T7: MLlib / iterative training workload materializes (ALS, feature pipelines)
  T8: Data lakehouse contract needed for external partner SQL access
```

Order of escalation: **PG → ClickHouse → Iceberg+Trino → Iceberg+Spark**. Each step justified by a measurable condition above. Spark sits at the end because it is the heaviest platform, the worst fit for the stateless transform workloads that dominate today, and only justified when MLlib or iterative compute enters the picture.

Calculator module (port 8082) is **not** affected by this decision. It continues to write JSONL.gz → MinIO and PG read models. The analytics layer is additive.

---

## 3. Trade-offs

### Sensitivity

* Daily ingest volume: 340GB/day raw → 40-50GB/day columnar if migrated
* Concurrent analytical query count vs OLTP serving reads on same PG instance
* Latency budget per analytical query (interactive vs nightly batch)
* Hot query cardinality (Top-N per class × world × level bucket = combinatorially large)
* Time-travel requirement (Iceberg-native, expensive in PG, impossible in ClickHouse)
* ML workload emergence (ALS, feature pipelines — only Spark has first-class MLlib)

### Trade-off

| Choice | Get | Give up |
| -- | -- | -- |
| **PG-only default** | Zero new infra; reuse existing PG + Airflow; 0 ops overhead; battle-tested | Poor scaling past ~30TB historical corpus; no columnar compression; no time-travel |
| **ClickHouse as parallel store** | 10-100× faster grouped aggregates; Kafka-native ingestion via engine tables; columnar compression 5-10× | No OLTP consistency; eventual dedup semantics; Keeper quorum (3 nodes); 6 engineer-weeks migration |
| **Iceberg+Trino (Lakehouse)** | ACID on MinIO; time-travel; hidden partitioning; schema evolution; engine-agnostic | New catalog service (Polaris/Nessie); mandatory compaction service; rewrite of writer hot paths; ~13 weeks phased |
| **Iceberg+Spark** | MLlib (ALS); Structured Streaming; mature batch+iterative | Cold-start 30s-2min; shuffle disk pressure; team skills gap; Spark-on-K8s ops surface |
| **Calculator untouched** | Preserves 20K items/s throughput on 1 JVM heap; no Spark re-write | None — this is preserved by the additive design |

### Risk

* **High**: PG becomes the analytical bottleneck if roadmap commits to user-facing Top-N before ClickHouse is stood up. Mitigation: T1+T3 trigger gates any commit that depends on sub-10s p95 analytical queries.
* **Medium**: ClickHouse eventual consistency (`ReplacingMergeTree`) leaks duplicates into Top-N if dedup keys are wrong. Mitigation: PRIMARY KEY on `(ign, version)` + `FINAL` for serving endpoints or explicit dedup at query time.
* **Medium**: Iceberg compaction lag (skipping nightly `rewrite-data-files`) collapses planner performance over weeks. Mitigation: compaction treated as Tier-1 service, not cron; alert on manifest list size > 50MB.
* **Low**: Spark adoption before T7 fires means carrying K8s operator + catalog + shuffle service complexity for workloads that never arrive. Mitigation: defer Spark adoption entirely; Iceberg catalog remains the durable interface so Spark is replaceable later.

### Non-Risk

* Calculator throughput regression — Calculator is out of scope for this ADR; its writer hot path is preserved until Phase 3 Iceberg cutover is independently validated.
* OLTP consistency on serving path — PG remains the sole serving store; ClickHouse and Iceberg are read-only for analytics endpoints.
* Vendor lock-in to a single analytics engine — Iceberg (when adopted) is engine-agnostic; ClickHouse is the only committed vendor surface, and only for one workload class.

---

## 4. Result / Evidence

### Metrics

| Metric | Today (PG-only) | ClickHouse (Phase 1) | Iceberg+Trino (Phase 2) | Iceberg+Spark (Phase 3) |
| --- | ---: | ---: | ---: | ---: |
| Top-N p99 latency (1B rows) | 5-30s | 50-300ms | 1-5s | 2-10min (cold) |
| Storage (10TB working set) | 10TB PG | ~1.5-2TB CH + PG | ~1TB Iceberg + 10GB catalog | Same as Phase 2 |
| Cluster size added | 0 | ~5 nodes (1 shard × 2 replica + 3 Keeper) | ~8 services (5 Trino + 3 catalog) | +Spark K8s operator |
| Monthly infra cost (10TB) | $0 marginal | ~$860 | ~$1,660 | ~$2,000+ |
| Migration effort | 0 | ~6 engineer-weeks | ~13 weeks (3 phases) | +4-6 weeks per MLlib pipeline |
| Time-travel queries | ❌ | ❌ | ✅ | ✅ |
| MLlib / iterative ML | ❌ | ❌ | ❌ | ✅ |

### Observed Result

This decision is **proposed, not implemented**. Evidence below is from public benchmarks and the agent evaluations referenced in §5; no platform has been deployed yet.

* **PG columnar ceiling**: PostgreSQL Top-N over 1B rows with btree + sort step is multi-second; ClickBench leaderboard shows ClickHouse 10-100× faster on grouped aggregates over equivalent data volume.
* **Iceberg-on-MinIO viability**: Iceberg javadoc confirms `S3FileIO` supports arbitrary S3-compatible endpoints via `s3.endpoint`, `s3.path-style-access=true`. MinIO RELEASE 2024-05+ required for safe concurrent writer commits via `If-Match` conditional writes.
* **Trino-Iceberg interoperability**: Trino can read Iceberg tables Spark writes — escape hatch exists if Spark is added later.
* **Spark workload mismatch**: Calculator's stateless per-item transform at 20K/s on 1 JVM heap is 10-100× more efficient per item than Spark; Spark would add zero capability the current Calculator needs.

---

## 5. Summary

> **Default to PG-only; escalate to ClickHouse when analytical p95 > 10s or Top-N becomes user-facing; defer Iceberg+Trino until ≥30TB historical corpus or time-travel is required; reserve Spark for MLlib workloads that don't yet exist on the roadmap.**

---

## References

### Related ADRs

* ADR-013: High-throughput event pipeline (Kafka choreography)
* ADR-039: Current architecture assessment (multi-module structure)
* ADR-041: Multi-module hexagonal architecture (DIP boundaries)

### Evaluation Source Material

* Iceberg evaluation: see companion doc in `docs/03_Technical_Guides/iceberg-evaluation.md` (pending creation via issue #2 in this ADR's action items)
* Trino evaluation: see companion doc in `docs/03_Technical_Guides/trino-evaluation.md`
* Spark evaluation: see `docs/03_Technical_Guides/spark-evaluation.md`
* ClickHouse evaluation: see `docs/03_Technical_Guides/clickhouse-evaluation.md`

### Public Sources

* [Apache Iceberg S3FileIO javadoc](https://iceberg.apache.org/javadoc/latest/org/apache/iceberg/aws/s3/S3FileIOProperties.html)
* [Apache Polaris](https://polaris.apache.org/)
* [Trino Iceberg connector](https://trino.io/docs/current/connector/iceberg.html)
* [ClickHouse Kafka engine docs](https://clickhouse.com/docs/en/engines/table-engines/integrations/kafka)
* [ClickBench leaderboard](https://benchmark.clickhouse.com/)
* [Spark MLlib collaborative filtering](https://spark.apache.org/docs/latest/ml-collaborative-filtering.html)
* [Spark on Kubernetes](https://spark.apache.org/docs/latest/running-on-kubernetes.html)

---

## Action Items (Issues to Create)

1. Iceberg Feasibility Study
2. MinIO Compatibility Validation
3. Analytics Layer ADR (this document)
4. Query Engine Evaluation
5. PostgreSQL Scalability Assessment
6. Historical Analytics Requirements
7. Class Hierarchy Data Modeling
8. Serving Layer vs Analytics Layer Separation
