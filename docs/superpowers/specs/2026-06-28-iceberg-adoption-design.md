# Iceberg Adoption Readiness Design (Issue 1426)

> **Forward-looking readiness assessment.** No Iceberg code in this issue. Iceberg adoption itself is gated on ADR-735 Phase 3 trigger conditions (T7: MLlib/iterative compute, T8: data lakehouse contract).

**Goal:** Produce a concrete readiness report mapping the 4 Iceberg prerequisite items: table schemas, catalog options, compaction strategy, schema evolution risks. End state: a plan-ready spec for Iceberg adoption that can be implemented when ADR-735 Phase 3 triggers fire.

**Architecture:** Document-only deliverable. Reuses Avro schemas from #1425. Reuses small-file investigation findings from #1427.

**Tech Stack:** Apache Iceberg (parquet-avro substrate), REST catalog with Postgres backend, daily Airflow DAG for compaction.

---

## 1. Background

ADR-735 establishes the analytics platform strategy: PG → ClickHouse → Iceberg+Trino → Iceberg+Spark. Iceberg adoption sits at Phase 3, gated on:

- **T7:** MLlib / iterative training workload materializes (ALS, feature pipelines)
- **T8:** Data lakehouse contract needed for external partner SQL access

Both prerequisites remain unmet today (ADR-735 last reviewed 2026-06-23). This spec tracks the **readiness** work — what would need to happen when T7 or T8 fires.

---

## 2. Iceberg Prerequisites Status

| Prerequisite | Status | Issue |
| -- | -- | -- |
| Parquet substrate | Done (PoC only) | #1423/#1424 |
| Explicit artifact schemas with field IDs | Done | #1425 |
| Catalog for metadata location | Pending | #1426 (this spec) |
| Compaction job for small-file problem | Pending | #1426 + #1427 implementation follow-up |

---

## 3. Table Schemas

### 3.1 `raw_snapshot` table

```sql
CREATE TABLE raw_snapshot (
    run_id          STRING,
    endpoint        STRING,    -- RANKING | CHARACTER_BASIC | ITEM_EQUIPMENT | OCID_LOOKUP
    status          STRING,    -- SUCCESS | FAILURE | PRE_SERIALIZED | CLOSE_SIGNAL
    body            STRING,    -- nullable inline JSON
    body_bytes      BINARY,    -- nullable large body
    http_status     INT,       -- nullable
    error_message   STRING,    -- nullable
    schema_version  INT,
    fetched_at      TIMESTAMP  -- Iceberg partition column
)
PARTITIONED BY (days(fetched_at), identity(endpoint))
SORTED BY (ocid ASC, fetched_at DESC)
```

### 3.2 `calc_result` table

```sql
CREATE TABLE calc_result (
    run_id            STRING,
    ocid              STRING,
    expected_min      BIGINT,    -- nullable
    expected_max      BIGINT,    -- nullable
    expected_cost     DOUBLE,    -- nullable; future Decimal migration
    potential_options ARRAY<STRING>,  -- nullable elements
    schema_version    INT,
    computed_at       TIMESTAMP  -- Iceberg partition column
)
PARTITIONED BY (bucket(16, ocid))
SORTED BY (expected_cost ASC NULLS LAST)
```

### 3.3 `ocid_mapping` table

```sql
CREATE TABLE ocid_mapping (
    user_ign        STRING,
    ocid            STRING,    -- nullable until lookup completes
    schema_version  INT,
    mapped_at       TIMESTAMP  -- Iceberg partition column
)
PARTITIONED BY (identity(user_ign))
SORTED BY (ocid ASC NULLS LAST)
```

---

## 4. Catalog Options Comparison

| Option | Friction | Cost | Recommendation |
| -- | -- | -- | -- |
| **REST catalog + Postgres backend** | Low | ~1-day setup, 0.5 FTE ongoing | **Chosen** |
| Hive Metastore + Postgres | Medium | Heavy service, MySQL/Postgres dep | Overkill |
| AWS Glue | Blocked (no AWS) | n/a | Out of stack |
| JDBC (Postgres native) | None | Scalability limits >100K files | Insufficient |

**REST catalog justification:**
- Lowest friction with existing MinIO + Postgres stack
- Catalog metadata in Postgres (already provisioned)
- MinIO acts as object storage backend (already provisioned)
- Apache Iceberg REST Catalog Open API spec compliance

---

## 5. Compaction Strategy

### 5.1 Approach

Use Iceberg `rewrite_data_files` procedure via Spark. Target file size: **128 MB** (Iceberg recommended).

```python
spark.sql("""
    CALL catalog.system.rewrite_data_files(
        table => 'raw_snapshot',
        options => map('target-file-size-bytes', '134217728')
    )
""")
```

### 5.2 Cadence

Daily Airflow DAG, runs after morning_chain_pipeline completes.

### 5.3 Estimated reduction

| Phase | Current files/day | Post-compaction files/day | Reduction |
| -- | --: | --: | --: |
| raw_snapshot (post-flush-time-rollup, #1427) | ~12K-120K | ~469 (target 128MB) | ~25-250x |
| calc_result | ~60K-600K | ~133 (target 128MB) | ~450-4500x |
| ocid_mapping | ~1/run | ~negligible | n/a |

---

## 6. Schema Evolution Risk Register

| Risk | Severity | Mitigation |
| -- | -- | -- |
| Field rename (breaks Avro field ID discipline) | High | Frozen field IDs per #1425; never re-use IDs on rename |
| Backward-incompatible change | High | Requires `schema_version` bump + dual-write window + consumer drain |
| Field add (default value) | Low | Easy via Avro default; Iceberg handles natively |
| Field remove | Medium | Requires new schema version + grace period (consumer drain) |
| Type widening (e.g., INT -> BIGINT) | Medium | Iceberg supports primitive type promotion; document in schema_version bump |

---

## 7. Cost / Benefit Estimate

### 7.1 Setup cost

- **FTE-week:** ~1 (catalog provisioning + first table + REST API integration)
- **Components:** REST catalog (Postgres backend), Iceberg Python lib for compaction DAG, Spark (single-node acceptable for this workload)

### 7.2 Ongoing cost

- **FTE-month:** ~0.5 (compaction DAG maintenance, schema migrations, query pattern tuning)
- **Compute:** Spark cluster (single-node adequate, ~1 CPU/day for compaction)

### 7.3 Benefit

Unlocks:
- SQL-on-lakehouse (Trino/DuckDB)
- Time-travel queries
- ML feature pipelines
- External partner data sharing (T8 trigger)

---

## 8. Adoption Trigger

Do NOT adopt Iceberg until ADR-735 Phase 3 fires (T7 or T8). When triggered:

1. File separate implementation issue
2. Provision REST catalog (Postgres backend)
3. Run nightly backfill converting existing gzip JSONL -> Parquet (use 1423 PoC writer)
4. Enable compaction DAG
5. Migrate consumers (Calculator, Synchronizer) to Iceberg reads
6. Retire gzip JSONL artifact path (after one-quarter shadow period)

---

## 9. Out of Scope

- Iceberg adoption itself (separate issue, post-Phase-3-trigger)
- Spark / Trino / DuckDB engine selection (separate ADR)
- Schema changes (we reuse 1425 Avro schemas as-is)
- Compaction DAG implementation (separate issue post-1427 implementation)

---

## 10. References

- ADR-735 (parent analytics platform decision)
- #1425 (Avro schemas)
- #1423/#1424 (Parquet PoC)
- #1427 (small-file investigation)
- Apache Iceberg documentation: https://iceberg.apache.org/
- Iceberg REST Catalog Open API spec