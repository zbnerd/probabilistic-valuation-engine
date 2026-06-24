# Plan: Query Engine Benchmark (Issue #1340)

- Spec: `docs/superpowers/specs/2026-06-23-query-engine-benchmark.md`
- Parent: Issue #1340
- Date: 2026-06-23

Phases run sequentially. Each phase produces artifacts and a verification gate before next phase.

---

## Phase 1 — Dataset Generation

### Task 1.1 — Write generator script
- **Path**: `docs/03_Technical_Guides/_bench/gen_data.py`
- **Stack**: Python 3.11, numpy, pyarrow, pandas (read-only prod sample)
- **Inputs**:
  - Production sample CSV: pull anonymized class/world/level counts from `character_valuation_views` snapshot 2026-06-15 via JDBC query (read-only)
  - Seed: 20260623
- **Outputs**:
  - `docs/03_Technical_Guides/_bench/gen_data.py`
  - `/tmp/benchmark/data/characters_1b.parquet`
  - `/tmp/benchmark/data/characters_10b.parquet`
  - `/tmp/benchmark/data/manifest.json` (row count, schema, size)
- **Verification**:
  - `python gen_data.py --rows 1000 --out /tmp/smoke.parquet` produces valid Parquet
  - `python -c "import pyarrow.parquet as pq; t=pq.read_table('/tmp/smoke.parquet'); print(t.num_rows, t.schema)"` shows 1000 rows + 12 cols
  - Distribution check: class histogram matches production distribution within ±5%

### Task 1.2 — Generate 1B-row dataset
- **Output**: `/tmp/benchmark/data/characters_1b.parquet` (~700GB)
- **Estimated time**: 30-60 min on 8 vCPU
- **Verification**:
  - File exists, size > 500GB
  - `manifest.json` records `{"rows": 1_000_000_000, "bytes": ...}`

### Task 1.3 — Generate 10B-row dataset
- **Output**: `/tmp/benchmark/data/characters_10b.parquet` (~7TB)
- **Estimated time**: 4-8 hours (run overnight or skip if 1TB sufficient)
- **Verification**: same as 1.2 with 10× scale

---

## Phase 2 — PostgreSQL Baseline

### Task 2.1 — Spin up PG 16.3 container
- **Script**: `docs/03_Technical_Guides/_bench/up_pg.sh`
- **Image**: `postgres:16.3`
- **Resources**: 8 vCPU, 32GB RAM (1TB); 16 vCPU, 64GB RAM (10TB)
- **Mount**: `/tmp/benchmark/data` → `/data` in container
- **Verification**: `docker exec pg16 pg_isready` returns 0; `SELECT version()` reports 16.3

### Task 2.2 — Load data
- **Approach**: `COPY characters FROM '/data/characters_{1b,10b}.parquet'` via `pgfutter` or split CSV
- **Indexes**: btree on (class, world), btree on (level), btree on (snapshot_ts)
- **Script**: `docs/03_Technical_Guides/_bench/load_pg.sh`
- **Verification**: `SELECT count(*) FROM characters` matches dataset row count

### Task 2.3 — Run benchmark cells (PG)
- **Script**: `docs/03_Technical_Guides/_bench/run_pg.sh`
- **Cells**: 4 workloads × 2 scales × 2 cache states = 16 cells
- **Per cell**: 3 warm-up + 5 timed runs; metrics → `/tmp/benchmark/results/pg_{workload}_{scale}_{cache}.json`
- **Cache drop**: `docker exec pg16 psql -c "SELECT pg_prewarm(...)"` reset; OS cache drop on host
- **Verification**: 16 JSON files exist; each has p50, p95, p99, peak_mem_mb, spill_bytes, row_count

### Task 2.4 — Report PG baseline section
- **Output**: intermediate JSON summary at `/tmp/benchmark/results/pg_summary.json`
- **Verification**: JSON parses; rows match between cells

---

## Phase 3 — ClickHouse

### Task 3.1 — Spin up ClickHouse 24.5 container
- **Script**: `docs/03_Technical_Guides/_bench/up_ch.sh`
- **Image**: `clickhouse/clickhouse-server:24.5`
- **Resources**: same as PG
- **Mount**: `/tmp/benchmark/data` → `/data`
- **Verification**: `curl http://localhost:8123/` returns OK; `SELECT version()` reports 24.5

### Task 3.2 — Create table + load
- **DDL**: `CREATE TABLE characters (...) ENGINE=MergeTree ORDER BY (class, world, level)`
- **Load**: `INSERT INTO characters FROM '/data/characters_{1b,10b}.parquet'`
- **Script**: `docs/03_Technical_Guides/_bench/load_ch.sh`
- **Verification**: `SELECT count(*) FROM characters` matches dataset

### Task 3.3 — Run benchmark cells (CH)
- **Script**: `docs/03_Technical_Guides/_bench/run_ch.sh`
- **Cells**: 16 (same matrix as PG)
- **Per cell**: same protocol; cache drop via `SYSTEM DROP MARK CACHE; SYSTEM DROP UNCOMPRESSED CACHE`
- **Verification**: 16 JSON files; spill bytes from `system.metrics` `DiskSpill`

### Task 3.4 — Report CH section
- **Output**: `/tmp/benchmark/results/ch_summary.json`
- **Verification**: JSON valid

---

## Phase 4 — Trino

### Task 4.1 — Spin up Trino 446 container
- **Script**: `docs/03_Technical_Guides/_bench/up_trino.sh`
- **Image**: `trinodb/trino:446`
- **Connector**: Hive connector pointing at `/data` for Parquet
- **Resources**: same as PG/CH
- **Verification**: `trino --execute "SELECT 1"` returns 1

### Task 4.2 — Configure Hive connector
- **Catalog config**: `docs/03_Technical_Guides/_bench/trino-catalog/hive.properties`
- **Schema**: `CREATE SCHEMA bench WITH (location = 'file:///data')`
- **Table**: `CREATE TABLE bench.characters (...) WITH (format = 'PARQUET', external_location = 'file:///data/characters_1b.parquet')`
- **Verification**: `SHOW TABLES FROM bench.characters` returns `characters`

### Task 4.3 — Run benchmark cells (Trino)
- **Script**: `docs/03_Technical_Guides/_bench/run_trino.sh`
- **Cells**: 16
- **Cache drop**: restart coordinator container between cold cells
- **Verification**: 16 JSON files

### Task 4.4 — Report Trino section
- **Output**: `/tmp/benchmark/results/trino_summary.json`

---

## Phase 5 — Spark

### Task 5.1 — Spin up Spark 3.5.2 container
- **Script**: `docs/03_Technical_Guides/_bench/up_spark.sh`
- **Image**: `apache/spark:3.5.2`
- **Resources**: same
- **Mount**: `/tmp/benchmark/data`
- **Verification**: `spark-shell --version` reports 3.5.2

### Task 5.2 — Load Parquet → Spark table
- **Approach**: `spark.sql("CREATE TABLE characters USING parquet LOCATION '/data/characters_1b.parquet'")` (or read directly in queries)
- **Script**: `docs/03_Technical_Guides/_bench/run_spark.sh`
- **Verification**: `SELECT count(*) FROM characters` matches

### Task 5.3 — Run benchmark cells (Spark)
- **Cells**: 16
- **Cache drop**: `spark.catalog.clearCache()`; drop OS cache
- **Cold-start overhead**: report separately as `cold_start_ms` per query
- **Verification**: 16 JSON files; each includes `cold_start_ms`

### Task 5.4 — Report Spark section
- **Output**: `/tmp/benchmark/results/spark_summary.json`

---

## Phase 6 — Final Report

### Task 6.1 — Aggregate all cell JSONs
- **Script**: `docs/03_Technical_Guides/_bench/aggregate.py`
- **Output**: `/tmp/benchmark/results/all_cells.json` (64 records)

### Task 6.2 — Render markdown report
- **Script**: `docs/03_Technical_Guides/_bench/render_report.py`
- **Output**: `docs/03_Technical_Guides/query-engine-benchmark.md`
- **Sections**:
  - Test setup
  - Per-workload comparison table (4 tables, one per workload)
  - Cross-workload summary (engine wins per workload per scale)
  - Per-workload recommendations with ADR-735 trigger mapping
  - Caveats

### Task 6.3 — Verification
- [ ] `docs/03_Technical_Guides/query-engine-benchmark.md` exists
- [ ] Contains 4 workload tables
- [ ] Contains winner per workload per scale
- [ ] Recommendations cite ADR-735 §2 triggers
- [ ] No production code modified: `git diff --stat` shows only `docs/03_Technical_Guides/`

---

## Issue Cross-References

Each phase becomes a GitHub issue cross-referenced to #1340.

| Issue | Phase | Title |
|-------|-------|-------|
| #TBD-1 | 1.1 | Write benchmark dataset generator script |
| #TBD-2 | 1.2 | Generate 1B-row synthetic dataset |
| #TBD-3 | 1.3 | Generate 10B-row synthetic dataset (optional, overnight) |
| #TBD-4 | 2.* | PostgreSQL 16.3 baseline benchmark |
| #TBD-5 | 3.* | ClickHouse 24.5 benchmark |
| #TBD-6 | 4.* | Trino 446 benchmark |
| #TBD-7 | 5.* | Spark 3.5.2 benchmark |
| #TBD-8 | 6.* | Aggregate and publish final report |

---

## Verification Gates

- After Phase 1: dataset manifest validates; row counts match expectations
- After each engine phase: 16 JSON files present + JSON valid + per-cell row count sanity
- After Phase 6: report file exists + recommendation section cites ADR-735

---

## Grill-Me: 5 Hard Questions

1. **Q: 1B rows on a single host is not 10B. Will the 1TB cell actually stress the engines, or just stress PG's planner?**
   A: 1B rows is sufficient to differentiate engines on grouped aggregates (W1/W2), but Trino/Spark are designed for distributed shuffle and may show unrealistically poor single-node numbers. Mitigation: report the single-node p95 as a "lower bound" for distributed Trino/Spark and use the engine's published distributed benchmarks (ClickBench) for cross-reference.

2. **Q: Synthetic data with seeded RNG cannot reproduce production skew. Class histograms match but joint distributions (class × level × world) won't.**
   A: True. Spec §3 accepts this. Mitigation: the 4 workloads target different cardinalities (40 classes, 50 worlds, 300 levels, 90 days), so individual workload results are interpretable even if joint distribution drifts. Document this caveat prominently in the report.

3. **Q: Trino reads Parquet via Hive connector, not Iceberg. Does this miss the Iceberg value-prop (hidden partitioning, time-travel)?**
   A: Yes. Trino-on-Hive-Parquet is the worst case for Trino; Iceberg would help. Mitigation: keep this benchmark as "baseline Trino" and flag in the report that Iceberg-specific benefits are not measured. A follow-up benchmark (Iceberg+Trino) is out of scope per #1340.

4. **Q: Dropping OS cache between cold runs takes 5-10s per drop × 320 timed runs = 25-50 min just on cache drops.**
   A: True. Cold-cache runs dominate wall-clock. Mitigation: warm-cache cells run first (cache stays hot throughout); cold-cache cells run last per workload with cache drop between. Saves ~30 min wall-clock.

5. **Q: Spark cold-start (10-30s) will dominate p99 for small queries. Reporting p99 across cold+warm conflates "engine slowness" with "startup overhead".**
   A: True. Mitigation: report cold-start as separate metric (`cold_start_ms`) per cell. Aggregate p99 excludes the first query's cold-start by dropping the first of 5 timed runs (4 used).

---

## Out-of-Scope Reminders

- No `.env` changes
- No production DB writes
- No Calculator/synchronizer/REST module changes
- No cluster deployment (ClickHouse Keeper, Spark K8s)
