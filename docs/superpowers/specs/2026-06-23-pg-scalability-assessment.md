# Spec — PostgreSQL Scalability Assessment

- Status: Proposed
- Date: 2026-06-23
- Issue: #1341
- Parent ADR: docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md

---

## 1. Goal

Validate or invalidate the trigger conditions **T1** (analytical query p95 > 10s on indexed PG) and **T2** (analytical load > 20% of DB CPU during peak hour) defined in ADR-735 §2. Produce measurable numbers at current and projected scale so the architecture owner can decide whether Phase 1 (ClickHouse adoption) is justified now, deferred, or unnecessary.

This is an **investigation-only** deliverable. No schema change, no new table, no production index modification, no new analytical engine.

## 2. Non-Goals

- Adding new analytical tables or mat views to production PG.
- Modifying existing indexes in production without ADR.
- ClickHouse / Iceberg / Spark adoption work.
- Production schema changes (all experiments run on staging).
- Building user-facing Top-N endpoints.

## 3. Background

Probabilistic valuation engine ingests 595K user snapshots/day, produces 40M items/day, ~340GB/day raw storage, ~120TB/year projected. Serving layer = PG 16 read models + L1 Caffeine + L2 PG UNLOGGED. Per-character query p95 < 100ms.

ADR-735 commits to a PG-only default for analytics, escalating to ClickHouse when analytical queries exceed 10s p95 (T1) or analytical load exceeds 20% of DB CPU (T2). Both thresholds are asserted without measurement today. This spec captures the measurements.

Schema inventory (read models touched by analytical queries):
- `character_valuation_views` (hot, primary serving + analytics)
- `character_equipment_read_model` (ocid + preset_no)
- `character_basic_read_model` (ocid-keyed)
- `character_expectation_read_model` (user_ign-keyed, gzip payload)
- `calculation_jobs`, `calculation_results` (operational, secondary)

## 4. Design

### 4.1 Measurement

**4.1.1 Workload mix.** Capture 24h of `pg_stat_statements` under production load. Group by `queryid`; report top-50 by `total_exec_time` and top-20 by `mean_exec_time`. Output: `pg_stat_top50.csv`.

**4.1.2 Per-character serving latency.** Sample 10K IGN. Run `GET /api/v5/characters/{ign}/expectation` at CONCURRENCY=50, split warm-cache vs cold-L1. Report p50/p95/p99. Output: `perchar_latency.csv`.

**4.1.3 Slow query log.** Set `log_min_duration_statement=1000`, `log_lock_waits=on`, `log_temp_files=0` on staging for 7 days. Aggregate by query fingerprint. Output: `slow_query_aggregate.csv`.

**4.1.4 EXPLAIN ANALYZE on top-5 analytical patterns.** For each of: Top-N per (world, class), world rollup, level-range histogram, time-windowed expectation drift, cross-class percentile — capture `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, TIMING)` at scale. Output: `q{1..5}.txt`.

**4.1.5 Synthetic benchmark.** Replay top-5 patterns at 100M, 250M, 500M, 750M, 1B, 1.5B, 2B rows. Record p50/p95/p99, shared buffers hit/read, temp spill, parallel workers. Output: `analytical_benchmark.csv`.

### 4.2 Index experiments

Test against synthetic 1B-row dataset, baseline = current btree schema.

| # | Index | Hypothesis | Metric |
|---|-------|------------|--------|
| I1 | BRIN `(updated_at) pages_per_range=32` | Time-windowed scans drop 5x | p95 of `WHERE updated_at > NOW()-INTERVAL '1 day'` |
| I2 | Partial `(character_class, world_name, total_cost DESC) WHERE class IN (top-100)` | Hot Top-N 2-5x faster | p95 of Top-100 query |
| I3 | Covering `(world_name, character_class, total_cost DESC) INCLUDE (user_ign, ocid)` | Index-only scan, 1.5-3x | p95 + buffers hit/read |
| I4 | RANGE partitioning by `calculated_at` (daily) | Time-windowed prune 5-10x | p95 + disk per query |

Each experiment: `CREATE INDEX …` (or no-op for baseline) → `ANALYZE` → 100 cold runs → report median + p95. Drop index between experiments.

### 4.3 Materialized view strategy

For each pattern from 4.1.4, recommend one of:

- **Hourly** (refresh after calculator batch close): last-1h expectation drift, Top-N per class/world for last hour.
- **Daily** (Airflow @ 02:00 KST): world rollup, class-level percentile, daily-active histogram.
- **Weekly**: 7-day drift, cross-class percentile.
- **On-demand**: ad-hoc analytical questions.

Mat view design constraints: UNIQUE INDEX matching serving predicate (enables `REFRESH CONCURRENTLY`); documented source tables + refresh DAG + last-refresh time + stale tolerance; storage cap at 5% of base table.

Refresh experiment: `REFRESH CONCURRENTLY` vs `REFRESH` (full) at 100M, 500M, 1B rows. Output: `mv_refresh.csv` (duration, lock duration, write amplification).

### 4.4 Storage projection

Project disk usage at 30/90/365 day retention with current row model.

| Input | Value |
|-------|------:|
| Items/day | 40M (sensitivity ±20%) |
| Avg compressed row | ~300 B |
| btree overhead | 30-50% |
| WAL/FMV | ~25% |
| TOAST compression ratio | 2-3x |

Output: `storage_projection.csv` (window, rows, heap_gb, index_gb, wal_gb, total_gb, sensitivity_low, sensitivity_high).

### 4.5 T1/T2 trigger validation

Plot p95 vs row count for each of top-5 patterns using data from 4.1.5. Identify crossover row count where p95 = 10s. Compare to 30/90/365-day row counts from 4.4.

**Verdict per trigger:**
- T1 VALIDATED if any pattern crossover ≤ 1.2B rows (today's 30-day baseline).
- T1 NOT YET if crossover 1.2B-4B; PHASE 1 defer justified.
- T1 INVALID if crossover > 4B for all patterns; T1 threshold too low.

**T2** verdict requires comparison of analytical query CPU share to total DB CPU during production peak hour. Source: `pg_stat_activity` + `pg_stat_database` snapshot at peak.

### 4.6 Findings deliverable

`docs/01_ADR/ADR-1341-pg-scalability-findings.md` with:
1. Methodology + dataset scale.
2. Workload mix.
3. Per-character serving p95/p99 today.
4. Analytical p95 vs row count + crossover plot.
5. Index experiment matrix + winner per pattern.
6. Mat view refresh strategy recommendation.
7. Storage projection table.
8. T1/T2 verdict per §4.5.
9. Recommendation to ADR-735 owner: keep PG-only, defer Phase 1, or escalate.

## 5. Acceptance Criteria

- [ ] `pg_stat_top50.csv` produced from 24h production-load capture (or prod-equivalent replay).
- [ ] `perchar_latency.csv` with p50/p95/p99 for 10K IGN sample.
- [ ] EXPLAIN ANALYZE captured for 5 analytical patterns at 1B-row scale.
- [ ] Index experiments I1-I4 completed; winner per pattern documented.
- [ ] Mat view refresh strategy: per-pattern cadence (hourly/daily/weekly/on-demand).
- [ ] `storage_projection.csv` at 30/90/365 day retention with sensitivity bounds.
- [ ] T1/T2 verdict per §4.5 with crossover row count.
- [ ] Findings ADR published.
- [ ] No production schema change.
- [ ] No new analytical engine adopted.

## 6. Trade-offs

### Sensitivity

- **Production traffic capture vs synthetic replay.** pg_stat from prod is ground truth; synthetic at 1B-row requires staging clone with proportional CPU/IO. Replay may understate planner choices vs real mixed workload.
- **Hot class/world list volatility.** Partial index (I2) assumes a stable hot set. If top-100 churns weekly, maintenance cost exceeds benefit.
- **Mat view storage.** 5% cap is heuristic; class-level percentile may exceed under heavy churn.
- **Synthetic dataset representativeness.** Value distribution in real data skews (most users low-cost, long tail). Synthetic must preserve skew or p95 numbers mislead.

### Trade-off

| Choice | Get | Give up |
|---|---|---|
| Synthetic 1B-row benchmark | Reproducible scaling curve; safe to experiment | Slight skew vs real distribution; staging clone cost |
| pg_stat capture only (no synthetic) | Real numbers | Cannot extrapolate beyond current 30-day volume |
| Index experiment I2 partial | 2-5x Top-N speedup | Maintenance overhead when hot list shifts |
| Mat view hourly refresh | Always-fresh leaderboards | Refresh write amplification + 5% storage cost |
| Defer Phase 1 if T1 not validated | No new infra; zero ops overhead | Risk of late escalation when roadmap commits to user-facing Top-N |

### Risk

- **Medium**: pg_stat_statements not enabled in current PG. If absent, Phase 1 baseline capture fails; require pre-req migration.
- **Medium**: Synthetic 1B-row staging dataset takes >1TB disk + 24h pg_restore. Capacity planning needed.
- **Low**: Mat view refresh competing with serving reads during calculator batch close window. Mitigation: refresh only during off-peak (02:00-04:00 KST) and document contention.

### Non-Risk

- Calculator throughput: untouched by this investigation.
- Serving path: experiments run on staging; prod indexes unchanged.
- Cost: zero new infra (uses existing staging PG).

## 7. Hard questions (grill-me)

1. **Synthetic representativeness.** 1B-row dataset built from `generate_series` cannot replicate real planner choices over a 90-day mixed workload (writes + reads competing for buffers). If the synthetic p95 is 2x lower than real, the T1 verdict flips. **Mitigation:** cap synthetic-vs-prod p95 delta at 2x; if exceeded, mark T1 verdict "indeterminate" and require prod EXPLAIN capture for top-5 patterns during peak hour.
2. **Hot list volatility for I2.** Partial index on top-100 (class, world) assumes the hot set is stable. Real workload shows class-meta rebalances every major patch (~quarterly). **Mitigation:** document partial-index maintenance as quarterly ADR; if maintenance cadence slips, partial index silently regresses to btree-equivalent without alerting. Add a check that compares `pg_stat_user_indexes.idx_scan` against expected hot-set traffic.
3. **Mat view write amplification under 1B rows.** `REFRESH MATERIALIZED VIEW CONCURRENTLY` rewrites the entire mat view; at 1B source rows × 5% size = 50M mat rows, that's non-trivial. **Mitigation:** size each mat view at creation; if any exceeds 5% of base, flag for incremental strategy (delta tables + UNION, or recompute pipeline).
4. **T2 measurement is sampling-blind.** `pg_stat_activity` sampled every 5 min for 7 days misses sub-minute spikes. If analytical queries burst for 30s during calculator batch close, T2 share could exceed 20% in that window. **Mitigation:** combine sampling with `pg_stat_statements.total_exec_time` aggregation over the same window; report both numbers.
5. **Scope creep risk.** Spec is investigation-only, but finding "T1 validated" naturally pulls in ClickHouse POC work. **Mitigation:** explicitly out-of-scope per §2; recommendation in findings ADR points to a separate Phase 1 escalation issue, NOT implementation.

## 8. References

- Issue #1341 — [Investigation] PostgreSQL Scalability Assessment
- ADR-735 — Future Analytics Platform Evaluation (§2 Decision, §3 Sensitivity, §4 Metrics)
- V111 — character_expectation_read_model
- V123 — character_equipment_read_model
- V126 — character_basic_read_model
- `.claude/rules/data-access.md`
- `.claude/rules/db-migration.md` (read-only reference; no migration in this scope)
- Brainstorm: /tmp/brainstorm-1341.md
