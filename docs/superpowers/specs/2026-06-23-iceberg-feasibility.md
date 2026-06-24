# Iceberg Feasibility Study

- Status: Proposed
- Date: 2026-06-23
- Owner: Architecture Team
- Parent Issue: #1337
- Parent ADR: [ADR-735](../01_ADR/ADR-735-future-analytics-platform-evaluation.md)
- Companion Doc Target: `docs/03_Technical_Guides/iceberg-evaluation.md`

---

## 1. Goal

Determine whether Apache Iceberg v1.7+ is a viable table format for the probabilistic-valuation-engine analytics platform. Deliverable: written feasibility study with concrete GO / NO-GO / DEFER recommendation, validated MinIO + REST catalog integration, and quantified compaction plan for the 340GB/day workload.

Outcome enables ADR-735 Action Item #1 closure and unblocks future Phase 2 trigger decisions.

## 2. Non-Goals

- Migrating Calculator writer hot path to Iceberg
- Replacing JSONL.gz chunk artifact format
- Production deployment of Polaris or Nessie catalog
- Any module code change
- ClickHouse evaluation (separate ADR-735 Action Item #4)
- Spark/Iceberg integration (ADR-735 reserves Phase 3 for MLlib workloads)

## 3. Background

`probabilistic-valuation-engine` produces ~595K user snapshots/day (~340GB/day compressed JSONL.gz on MinIO, ~10TB/month, ~120TB/year). ADR-735 commits PG-only as default and escalates in stages: PG → ClickHouse → Iceberg+Trino → Iceberg+Spark. Iceberg+Trino is the Phase 2 candidate.

Phase 1 trigger conditions (any one):
- T1. Analytical query p95 > 10s on indexed PG (EXPLAIN ANALYZE-verified)
- T2. Analytical load > 20% of DB CPU during peak hour
- T3. Top-N leaderboard or class-rollup becomes user-facing API contract

Phase 2 trigger conditions (any one on top of Phase 1):
- T4. ≥30TB active historical corpus retained for analytics
- T5. Time-travel / point-in-time read model snapshot needed
- T6. Cross-source SQL join required (MinIO chunks + PG read model)

This study runs the Iceberg-on-MinIO integration validation **before** any Phase 2 trigger fires, so that when one does fire the decision cycle is weeks instead of months.

## 4. Design

### 4.1 Catalog Choice

Primary: **Apache Polaris 1.0.x** (REST catalog, Apache-governed, multi-engine).
Fallback: **Project Nessie 0.103+** (if Polaris unstable in test namespace).
Rejected: Hive Metastore (couples to single ecosystem, breaks ADR-735 engine-agnostic posture).

### 4.2 Test Namespace Layout

| Component | Deployment | Notes |
|---|---|---|
| Polaris | Docker compose service in `docker-compose.test.yml` | Postgres backend in same compose file; isolated from serving PG |
| MinIO | Existing cluster, dedicated `iceberg-pilot` bucket | Bucket lifecycle disabled; no expiration |
| Trino | Docker compose service | Iceberg connector, reads Polaris-managed table |
| PyIceberg writer | One-shot script (not a service) | Reads one `character_basic` chunk; writes test table |
| Serving PG/Kafka | Unchanged, isolated | No pilot writes reach serving path |

### 4.3 Test Table Schema

```sql
CREATE TABLE landing.character_basic_pilot (
    character_id BIGINT,
    ign VARCHAR,
    ocid VARCHAR,
    ingest_ts TIMESTAMP,
    raw_payload BINARY  -- parsed from JSONL.gz
) PARTITIONED BY days(ingest_ts), bucket(64, character_id);
```

File format: Parquet v2.
Sort order: `zorder(character_id, ingest_ts)` to accelerate Top-N and character-time lookups.

### 4.4 Compaction Plan

| Parameter | Value | Source |
|---|---|---|
| Target Parquet file size | 256MB | ADR-735 §3 |
| Action | `rewrite-data-files` | Iceberg spec |
| Schedule | Nightly cron (initial) | Operational simplicity |
| Promotion to service | When manifest list > 50MB | ADR-735 risk mitigation |
| Sort order during rewrite | zorder(character_id, ingest_ts) | Top-N acceleration |
| Acceptance band | ≥128MB median within 3 runs | Gate metric |

### 4.5 Snapshot Retention

| Snapshot class | Retention | Mechanism |
|---|---|---|
| Raw (`landing.*`) | 7 days rolling | `expire_snapshots` with `retain_last` |
| Serving read-model (`serving.*`) | 365 days | `expire_snapshots` with `retain_last` |

### 4.6 Validation Matrix

| Gate | Verification command | Pass threshold |
|---|---|---|
| MinIO version | `mc admin info minio` | RELEASE 2024-05+ |
| Polaris health | `curl /healthcheck` | 200 within 30s × 5 |
| Test table create | PyIceberg `create_table` exit 0 | Table appears in Polaris |
| Chunk ingest | Row count = source chunk row count | Exact match |
| Compaction | `rewrite-data-files` exit 0 | Parquet median ≥128MB |
| Manifest growth | Daily `manifest_list_size` measurement | ≤10MB/day for 7 days |
| Time-travel query | `SELECT ... AS OF TIMESTAMP` in Trino | <30s for 7-day scan |
| Cross-engine read | Trino reads table written by PyIceberg | Row count matches |

## 5. Acceptance Criteria

- [ ] AC1. MinIO RELEASE ≥ 2024-05 confirmed in cluster, or upgrade plan documented
- [ ] AC2. Polaris 1.0.x OR Nessie 0.103+ deployed in test namespace; `/healthcheck` 200
- [ ] AC3. Test table created with `PARTITIONED BY days(ingest_ts), bucket(64, character_id)`
- [ ] AC4. One `character_basic` chunk ingested; source row count = table row count
- [ ] AC5. `rewrite-data-files` run nightly; Parquet median file size ≥128MB
- [ ] AC6. Manifest list size measured 7 consecutive days; growth ≤10MB/day
- [ ] AC7. 7-day rolling raw snapshot retention validated
- [ ] AC8. 365-day serving read-model retention policy documented
- [ ] AC9. Time-travel query on 7-day scan returns within 30s
- [ ] AC10. Companion doc published at `docs/03_Technical_Guides/iceberg-evaluation.md`
- [ ] AC11. Recommendation (GO / NO-GO / DEFER) recorded in issue #1337 with conditions/triggers
- [ ] AC12. ADR-735 Action Item #1 marked complete

## 6. Trade-offs

### Sensitivity

- Daily ingest volume: 340GB/day raw → ~50GB/day columnar if adopted
- Manifest list growth (drives compaction cost)
- Catalog availability (single point of failure for read access)
- Test namespace isolation (shared MinIO/PG = blast radius)
- Polaris backend choice (Postgres vs. in-memory)

### Trade-off Table

| Choice | Get | Give up |
|---|---|---|
| Test namespace isolated from serving | No blast radius; can run anytime | Duplicate Postgres for catalog backend |
| Polaris primary, Nessie fallback | Apache governance + multi-engine | Two catalog implementations to validate |
| Nightly cron compaction | Simple ops | Compaction lag risk if cron misses |
| PyIceberg one-shot writer | Minimal service surface | Not representative of future Java writer |
| DEFER default recommendation | Honest with ADR-735 phase gating | Slower path if triggers fire mid-pilot |

### Risk

- **RK1 (High)**: MinIO RELEASE < 2024-05 blocks conditional writes → defer recommendation
- **RK2 (Medium)**: Polaris 1.0.x immaturity → Nessie fallback ready
- **RK3 (Medium)**: Compaction becomes operational burden before ROI → DEFER triggers documented
- **RK4 (Medium)**: Recommendation conflict with ClickHouse-first strategy → scope discipline
- **RK5 (Low)**: Test data bleeds into serving PG via shared catalog → namespace isolation enforced

### Non-Risk

- Calculator throughput regression — out of scope by hard rule
- OLTP serving path — PG remains sole serving store; pilot is read-only analytics
- Vendor lock-in — Iceberg is engine-agnostic

## 7. Open Questions

None. All decisions finalized as best practice.

## 8. References

- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Parent Issue: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1337
- Apache Iceberg S3FileIO: https://iceberg.apache.org/javadoc/latest/org/apache/iceberg/aws/s3/S3FileIOProperties.html
- Apache Polaris: https://polaris.apache.org/
- Project Nessie: https://projectnessie.org/
- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- MinIO RELEASE 2024-05 changelog (conditional writes): https://github.com/minio/minio/releases/tag/RELEASE.2024-05-10T01-41-38Z