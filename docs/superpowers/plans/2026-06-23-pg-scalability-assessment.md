# Plan — PostgreSQL Scalability Assessment

- Issue: #1341
- Parent ADR: docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md
- Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md
- Date: 2026-06-23

**Phases:** Phase 1 baseline → Phase 2 index experiments → Phase 3 mat view experiments → Phase 4 storage projection + T1/T2 verdict → Phase 5 findings ADR.

All experiments run on **staging PG** (not production). No production schema change.

---

## Phase 1 — Baseline capture

### Task 1.1: Verify pg_stat_statements enabled
- **Name:** Pre-check pg_stat_statements availability
- **Scripts:**
  ```sql
  SHOW shared_preload_libraries;
  SELECT * FROM pg_available_extensions WHERE name = 'pg_stat_statements';
  ```
- **Output:** `phase1/pg_stat_precheck.txt` (status: enabled | missing).
- **Verification:** If missing, file pre-req issue; do not proceed with 1.2 until enabled.

### Task 1.2: Capture 24h pg_stat_statements snapshot
- **Name:** 24h workload mix capture
- **Scripts:**
  ```sql
  SELECT queryid, calls, total_exec_time, mean_exec_time, rows, query
  FROM pg_stat_statements
  ORDER BY total_exec_time DESC LIMIT 50;
  ```
  Run hourly for 24h. Persist snapshots to `phase1/pg_stat_hourly_{NN}.csv`. Concatenate into `pg_stat_24h.csv`.
- **Output:** `phase1/pg_stat_top50.csv`, `phase1/pg_stat_slow_calls.csv` (top-20 by mean).
- **Verification:** 24 snapshots, sum of `total_exec_time` within 10% of `pg_stat_database.total_time`.

### Task 1.3: Per-character serving latency sample
- **Name:** 10K IGN latency benchmark
- **Scripts:** Python driver: 10K IGN sample from `userIgn_List.csv`; 50 concurrent workers; split warm (L1 hit) vs cold (L1 miss). Each: `GET /api/v5/characters/{ign}/expectation`, record latency_ms, status, cache_tier.
- **Output:** `phase1/perchar_latency.csv`.
- **Verification:** ≥ 95% HTTP 200; warm p95 < 50ms; cold p95 < 200ms (per ADR-735 §4).

### Task 1.4: Slow query log capture
- **Name:** 7-day slow query aggregation
- **Scripts:** Set `log_min_duration_statement=1000`, `log_lock_waits=on`, `log_temp_files=0` on staging PG. After 7 days, parse log file via `pg_log_statements.py` (or equivalent). Aggregate by normalized query fingerprint.
- **Output:** `phase1/slow_query_aggregate.csv` (fingerprint, calls, total_time, mean_time, max_time).
- **Verification:** ≥ 100 sample queries logged; report top-20 by total_time.

---

## Phase 2 — Index experiments

### Task 2.1: Synthetic 1B-row dataset on staging
- **Name:** Build 1B-row benchmark dataset
- **Scripts:** pg_dump schema of `character_valuation_views`; populate via `generate_series` + skew-preserving distribution (90% under $100M, 9% $100M-1B, 1% $1B+); restore into staging. ~1.2TB on disk.
- **Output:** `phase2/synth_1b_create.sql`, `phase2/synth_1b_size.txt` (table_size, index_size).
- **Verification:** `SELECT count(*) = 1,000,000,000`; row size histogram matches real `pg_stats`.

### Task 2.2: EXPLAIN ANALYZE on top-5 patterns
- **Name:** Capture baseline plans
- **Scripts:** Five queries per spec §4.1.4:
  1. Top-N per (world, class) — `ORDER BY total_cost DESC LIMIT 100`.
  2. World rollup — `SELECT world_name, count(*), avg(total_cost) GROUP BY world_name`.
  3. Level-range histogram — `SELECT character_level/10 AS bucket, count(*) GROUP BY bucket`.
  4. Time-windowed expectation drift — `WHERE calculated_at > NOW()-INTERVAL '7d'`.
  5. Cross-class percentile — `percentile_cont(0.95) WITHIN GROUP (ORDER BY total_cost)`.
  For each: `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, TIMING) > phase2/q{N}_baseline.txt`.
- **Output:** `phase2/q{1..5}_baseline.txt`.
- **Verification:** Each query runs to completion; plan shows expected index/seq scan choice.

### Task 2.3: BRIN on updated_at (I1)
- **Name:** Index experiment I1
- **Scripts:**
  ```sql
  CREATE INDEX idx_brin_updated_at ON character_valuation_views
    USING BRIN (updated_at) WITH (pages_per_range = 32);
  ANALYZE character_valuation_views;
  ```
  Run q4 (time-windowed) 100x cold. Capture latency per run.
- **Output:** `phase2/i1_brin.csv` (run, latency_ms, buffers_hit, buffers_read). Drop index after.
- **Verification:** p95 of time-windowed query reduced ≥2x vs baseline OR explicit "no improvement" recorded.

### Task 2.4: Partial index on hot combos (I2)
- **Name:** Index experiment I2
- **Scripts:**
  - Extract top-100 (class, world) from `pg_stat_statements` (or hot set from 1.2 workload mix).
  - `CREATE INDEX idx_hot_class_world_cost ON character_valuation_views (character_class, world_name, total_cost DESC) WHERE character_class IN (...)`.
  - Run q1 (Top-N per class/world) 100x cold.
- **Output:** `phase2/i2_partial.csv`.
- **Verification:** Top-N p95 reduced ≥2x OR "no improvement" recorded. Drop index after.

### Task 2.5: Covering index for Top-N (I3)
- **Name:** Index experiment I3
- **Scripts:**
  ```sql
  CREATE INDEX idx_cover_world_class_cost ON character_valuation_views
    (world_name, character_class, total_cost DESC)
    INCLUDE (user_ign, ocid);
  ```
  Run q1 100x cold. Check `EXPLAIN` for `Index Only Scan`.
- **Output:** `phase2/i3_covering.csv`, `phase2/i3_plan.txt`.
- **Verification:** Plan uses Index Only Scan; p95 reduced vs baseline. Drop index after.

### Task 2.6: RANGE partitioning by calculated_at (I4)
- **Name:** Index experiment I4
- **Scripts:** Recreate `character_valuation_views` as PARTITION BY RANGE (calculated_at) with daily partitions for 90 days. Run q4 (7-day window) 100x cold; check partition pruning in plan.
- **Output:** `phase2/i4_partition.csv`, `phase2/i4_plan.txt` (showing `Partitions selected: 7`).
- **Verification:** Plan prunes to ≤7 partitions; q4 p95 reduced ≥3x. Document UPSERT compatibility constraint.

---

## Phase 3 — Materialized view experiments

### Task 3.1: Build mat view candidates
- **Name:** Create mat view schemas
- **Scripts:**
  - `mv_topn_hourly`: Top-100 per (world, class) for last 1h. UNIQUE INDEX `(world_name, character_class, user_ign)`.
  - `mv_world_rollup_daily`: daily world rollup. UNIQUE INDEX `(world_name, day_bucket)`.
  - `mv_class_percentile_weekly`: weekly cross-class percentile. UNIQUE INDEX `(character_class, week_bucket)`.
- **Output:** `phase3/mv_create.sql`.
- **Verification:** Each mat view created with valid UNIQUE INDEX enabling `CONCURRENTLY` refresh.

### Task 3.2: Refresh experiment
- **Name:** CONCURRENTLY vs full refresh benchmark
- **Scripts:** At 100M, 500M, 1B rows: run `REFRESH MATERIALIZED VIEW mv_topn_hourly` (full) and `REFRESH MATERIALIZED VIEW CONCURRENTLY mv_topn_hourly`. Capture duration, lock duration (from `pg_stat_activity`).
- **Output:** `phase3/mv_refresh.csv` (row_count, mode, duration_s, lock_duration_s).
- **Verification:** CONCURRENTLY takes ≤2x full but allows serving reads throughout. Lock duration = 0 for CONCURRENTLY.

### Task 3.3: Mat view refresh strategy recommendation
- **Name:** Per-pattern cadence mapping
- **Scripts:** Document per spec §4.3 which pattern gets hourly/daily/weekly/on-demand. Include Airflow DAG references for daily+.
- **Output:** `phase3/mv_strategy.md` (table: pattern → cadence → DAG → last_refresh column → stale_tolerance).
- **Verification:** Every mat view has documented refresh DAG or explicit on-demand trigger; storage estimate ≤5% of base.

---

## Phase 4 — Storage projection + T1/T2 verdict

### Task 4.1: Storage projection
- **Name:** 30/90/365-day disk usage model
- **Scripts:** Spreadsheet or SQL compute using spec §4.4 inputs. Compute heap, index, WAL/FMV for 30/90/365d; sensitivity ±20%.
- **Output:** `phase4/storage_projection.csv` (window, rows, heap_gb, index_gb, wal_gb, total_gb, sens_low, sens_high).
- **Verification:** All 9 columns populated; sensitivity bounds non-negative.

### Task 4.2: p95 vs row count curve
- **Name:** Synthetic benchmark at multiple scales
- **Scripts:** Reuse q1-q5 from Phase 2. Run at 100M, 250M, 500M, 750M, 1B, 1.5B, 2B (scaled-down versions of 1B dataset). Capture p50/p95/p99 per row count per query.
- **Output:** `phase4/scaling.csv` (query_id, row_count, p50, p95, p99, buffers_read, temp_spill).
- **Verification:** All 7 row counts × 5 queries = 35 rows; p95 monotonically non-decreasing per query.

### Task 4.3: T1 crossover analysis
- **Name:** Identify crossover row count
- **Scripts:** From `phase4/scaling.csv`, find row_count where p95 ≥ 10s per query. Plot `phase4/t1_crossover.png`.
- **Output:** `phase4/crossover_by_query.csv` (query_id, crossover_row_count, today_30d=1.2B, today_90d=3.6B, today_365d=14.6B, verdict).
- **Verification:** Each query has explicit verdict per spec §4.5 (VALIDATED / NOT YET / INVALID).

### Task 4.4: T2 DB CPU share
- **Name:** Peak-hour analytical CPU share
- **Scripts:** During production peak (18:00-24:00 KST, sampled every 5 min for 7 days), capture `pg_stat_activity` + `pg_stat_database`. Compute analytical query share = sum of `cpu_time` for queries matching analytical patterns / total DB CPU.
- **Output:** `phase4/t2_cpu_share.csv` (timestamp, total_db_cpu_s, analytical_cpu_s, share_pct).
- **Verification:** T2 verdict: peak share > 20% (VALIDATED) or ≤ 20% (NOT YET).

---

## Phase 5 — Findings ADR

### Task 5.1: Draft findings ADR
- **Name:** Publish ADR-1341 findings
- **Scripts:** Write `docs/01_ADR/ADR-1341-pg-scalability-findings.md` per spec §4.6. Sections: methodology, workload mix, per-character serving, analytical p95 curve, index matrix, mat view strategy, storage projection, T1/T2 verdict, recommendation.
- **Output:** ADR file.
- **Verification:** All 9 sections present; recommendation explicitly addresses ADR-735 owner (keep PG-only / defer Phase 1 / escalate).

### Task 5.2: Cross-link to parent ADR
- **Name:** Update ADR-735 references
- **Scripts:** Add link from ADR-735 §4 Evidence to ADR-1341 findings. Add to Action Items table.
- **Output:** Edits to `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`.
- **Verification:** Link present, action item marked done.

---

## Verification commands (per project rules)

```bash
./gradlew compileKotlin compileJava --continue   # if any code touched (none in this plan)
./gradlew test                                    # if any code touched (none)
```

This plan produces only documentation + CSV outputs + staging SQL. No code change. No production migration. Per workflow-rules §10 Definition of Done: skip runtime server verification (no code change to verify).

## Risks summary

- **pg_stat_statements not enabled** → file pre-req issue (Task 1.1).
- **1B-row synthetic dataset** → 1.2TB staging disk; coordinate with infra (Task 2.1).
- **Mat view CONCURRENTLY requires UNIQUE INDEX** → design constraint documented in 3.1.
