# Spec: Query Engine Benchmark (Issue #1340)

- Status: Proposed
- Date: 2026-06-23
- Parent: Issue #1340, ADR-735
- Owner: Architecture Team

---

## 1. Goal

Produce an evidence-backed comparison of PostgreSQL 16, ClickHouse, Trino, and Spark on the four named analytical workloads defined in ADR-735 §1, at 1TB and 10TB scale. Recommendation per workload must cite falsifiable conditions from ADR-735 §2 (T1: p95 > 10s; T3: user-facing; T4: ≥30TB corpus).

## 2. Non-Goals

- No production cluster sizing decisions
- No ClickHouse Keeper deployment
- No Spark cluster deployment
- No migration of any read traffic
- No production code changes
- No new analytical schemas in production PG
- No changes to Calculator / synchronizer / REST modules

## 3. Background

ADR-735 proposes PG-only as the default analytics tier, escalating to ClickHouse, Iceberg+Trino, and Iceberg+Spark based on measurable trigger conditions. Issue #1340 is the empirical foundation: a controlled benchmark that turns the trigger conditions into numbers.

Without this benchmark, every analytical workload becomes either an expensive PG query competing with serving reads, a one-off Python script over MinIO JSONL.gz, or a deferred decision. The benchmark replaces intuition with measured latency/memory/spill on each candidate.

Today's data profile: 595K users/day × 40M items/day → 340GB/day raw → ~120TB/year. Analytical workloads target class/world/level rollups, Top-N rankings, and multi-week expectation drift.

## 4. Design

### 4.1 Workloads

Same 4 SQL queries are run identically on every engine where syntax permits. Engines use SQL extensions where required (e.g., ClickHouse `quantileExact`, Trino `approx_percentile`).

| ID | Workload | SQL intent |
|----|----------|------------|
| W1 | Class statistics | `SELECT class, count(*), avg(level), percentile(0.95) within group (order by item_score), max(item_score) FROM characters GROUP BY class` |
| W2 | World statistics | `SELECT world, class, count(*) FROM characters GROUP BY world, class` |
| W3 | Level-range analytics | `SELECT class, count(*) FROM characters WHERE level BETWEEN 200 AND 260 GROUP BY class` |
| W4 | Historical trend | `SELECT date_trunc('day', snapshot_ts) AS d, class, avg(expectation_0) FROM characters GROUP BY d, class ORDER BY d, class` |

### 4.2 Engines

| ID | Engine | Version | Container image |
|----|--------|---------|-----------------|
| E1 | PostgreSQL | 16.3 | `postgres:16.3` |
| E2 | ClickHouse | 24.5 LTS | `clickhouse/clickhouse-server:24.5` |
| E3 | Trino | 446 | `trinodb/trino:446` |
| E4 | Spark | 3.5.2 | `apache/spark:3.5.2` |

### 4.3 Test data

- Generator: standalone Python script (`docs/03_Technical_Guides/_bench/gen_data.py`), numpy + pyarrow.
- Schema: 12 columns matching production anonymized shape (see brainstorm).
- Cardinality:
  - 1B rows ≈ 700GB Parquet compressed (1TB target)
  - 10B rows ≈ 7TB Parquet compressed (10TB target)
- Distribution: seeded RNG (seed = 20260623), class/world/level/expectation distributions sampled from production `character_valuation_views` snapshot 2026-06-15.
- Output formats: Parquet (all engines), CSV (PG bulk-loader fallback).
- Storage path: `/tmp/benchmark/data/` on host, bind-mounted into each engine container.

### 4.4 Benchmark cell

A cell is (engine, workload, scale, cache-state). 4 × 4 × 2 × 2 = 64 cells. Per cell:
- 3 warm-up runs (results discarded)
- 5 timed runs
- Metrics: p50, p95, p99 latency (ms), peak RSS (MB), disk spill (bytes), result row count

Total timed runs: 64 × 5 = 320. Estimated ~3 hours wall-clock.

### 4.5 Cache states

- **Warm**: warm-up runs populate OS file cache + engine buffer pool; timed runs hit warm cache.
- **Cold**: between each timed run, `sync && echo 3 > /proc/sys/vm/drop_caches` on host + engine-specific cache reset (`pg_prewarm` reset for PG, `SYSTEM DROP MARK CACHE` for CH, restart coordinator for Trino, no-op for Spark with `spark.sql.inMemoryColumnarStorage.partitions=0`).

### 4.6 Isolation

- Each engine runs in dedicated Docker container, dedicated volume, dedicated DB namespace.
- One cell at a time, sequential.
- Localhost-only networking. No external API.
- Container resource caps: 8 vCPU, 32GB RAM (1TB cells); 16 vCPU, 64GB RAM (10TB cells).

### 4.7 Metrics capture

- Latency: container-side `time` wrapper around query execution.
- Memory: `docker stats --no-stream --format '{{.MemUsage}}'` sampled at start + every 1s during query; peak recorded.
- Disk spill:
  - PG: `pg_stat_database` `blk_write_time` + temp file size from `EXPLAIN ANALYZE`
  - ClickHouse: `system.metrics` `DiskSpill`
  - Trino: `QueryStatistics` from `system.runtime.queries`
  - Spark: `ShuffleSpillMetrics` from event log
- Output: one JSON file per cell at `/tmp/benchmark/results/{engine}_{workload}_{scale}_{cache}.json`.

### 4.8 Reporting

- Report path: `docs/03_Technical_Guples/query-engine-benchmark.md`.
- Sections:
  - Test setup (data, engines, hardware)
  - Results table per scale (4 tables, one per workload)
  - Cross-workload summary (engine wins per workload per scale)
  - Recommendations per workload with falsifiable conditions mapped to ADR-735 §2 triggers
  - Caveats and limitations

## 5. Acceptance Criteria

- [ ] Test data generator script checked in at `docs/03_Technical_Guides/_bench/gen_data.py`
- [ ] 1B-row dataset generated at `/tmp/benchmark/data/characters_1b.parquet`
- [ ] 10B-row dataset generated at `/tmp/benchmark/data/characters_10b.parquet`
- [ ] All 4 engines load both datasets successfully
- [ ] All 16 (engine × workload) cells complete at 1TB scale
- [ ] All 16 cells complete at 10TB scale
- [ ] Per-cell JSON metrics captured (64 files total)
- [ ] Report published at `docs/03_Technical_Guides/query-engine-benchmark.md`
- [ ] Report contains: comparison matrix, winner per workload, falsifiable recommendations mapped to ADR-735 triggers
- [ ] No production code changes; investigation only
- [ ] No `.env` modifications; runs in isolated `/tmp/benchmark/` workspace

## 6. Trade-offs

### Sensitivity

- Dataset distribution skew: results valid only for the chosen production snapshot; re-running with different distribution may shift winners.
- Hardware cap (32GB / 64GB): engines allowed to use all available RAM; results scale with hardware but ratio between engines remains comparable.
- Cold-cache methodology: dropping OS cache is invasive and may not reflect real cluster conditions.
- Spark cold-start: Spark's driver startup adds 10-30s baseline; reported separately as "cold start" overhead.

### Trade-off

| Choice | Get | Give up |
| -- | -- | -- |
| Single-host benchmark | Reproducible, fast (~3h), no cluster ops | Results don't capture distributed shuffle overhead |
| Parquet format | All 4 engines read natively | PG bulk load is slower than COPY from CSV |
| Drop OS cache for cold runs | True cold-cache measurement | Slower total run time |
| 5 timed runs | Stable percentiles | 5× run time vs single-run |
| Same SQL text per workload | Like-for-like comparison | Engines with better-suited syntax can't show their advantage |

### Risk

- **Medium**: 10B-row generation may take >2 hours. Mitigation: budget 4 hours, generate overnight.
- **Medium**: Trino/Spark containers may OOM at 10TB if distributions skew. Mitigation: 64GB cap, generator keeps level distribution bounded.
- **Low**: Result-row-count mismatch indicates wrong SQL semantics. Mitigation: assert equality across engines per cell.
- **Low**: PG CSV bulk-load is slow. Mitigation: 8-thread parallel COPY + CSV split.

### Non-Risk

- Production data exposure: generator uses seeded RNG + production distribution parameters only; no actual IGNs leaked.
- Calculator throughput: out of scope per ADR-735 §2.
- Cost: benchmark uses local docker, zero cloud spend.

## 7. References

- Issue #1340: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1340
- ADR-735: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Brainstorm: `/tmp/brainstorm-1340.md`
- Plan: `docs/superpowers/plans/2026-06-23-query-engine-benchmark.md`
- ClickBench leaderboard: https://benchmark.clickhouse.com/
- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- Spark MLlib: https://spark.apache.org/docs/latest/ml-collaborative-filtering.html

## 8. Summary

> **Benchmark 4 engines × 4 workloads × 2 scales × 2 cache states = 64 cells on synthetic data matching production distribution; produce a per-workload winner with falsifiable ADR-735 trigger conditions; report at `docs/03_Technical_Guides/query-engine-benchmark.md`.**
