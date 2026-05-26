# Query Plan Analysis: character_equipment_read_model

- Date: 2026-05-19
- DB: PostgreSQL 16 (local, `maple_expectation`)
- Table: `character_equipment_read_model`

---

## Executive Summary

EXPLAIN ANALYZE 중 두 가지 버그 발견:

1. **Pipeline data population bug**: `user_ign` 컬럼이 99.999% NULL. Synchronizer/Calculator write path에서 user_ign mapping 누락. read path 전체가 비활성 상태.
2. **Ranking query 병목**: `ORDER BY total_cost DESC LIMIT 10` 쿼리가 preset_no별 288K row full scan + sort 수행. **3.8초** 소요. `(preset_no, total_cost DESC)` index로 <1ms 예상.

---

## 1. Table Overview

| Metric | Value |
|--------|-------|
| Row count | 864,868 |
| Table size | 481 MB |
| Index size | 176 MB (3 indexes) |
| Total size (incl. TOAST) | 3,005 MB |
| HOT update rate | 92.4% |
| Fillfactor | default (100) |

### Row Distribution by preset_no

| preset_no | count | user_ign populated |
|-----------|-------|--------------------|
| 1 | 288,206 | 2 |
| 2 | 288,382 | 2 |
| 3 | 288,280 | 2 |

### Indexes

| Index | Columns | Size | Scans | Purpose |
|-------|---------|------|-------|---------|
| `character_equipment_read_model_pkey` | `read_key` (PK) | 83 MB | 90,740,406 | Upsert arbiter |
| `uq_character_equipment_ocid_preset` | `(ocid, preset_no)` UNIQUE | 83 MB | 3 | Dedup constraint |
| `idx_equipment_read_model_user_ign_preset` | `(user_ign, preset_no)` | 10 MB | 7 | Read path (currently inactive) |

### Column Statistics (pg_stats)

| Column | n_distinct | null_frac | correlation | Notes |
|--------|-----------|-----------|-------------|-------|
| `user_ign` | 0 | **1.0 (100%)** | - | **Pipeline bug: NULL in all recent data**. 5/17 bulk upsert (861K rows): 0% populated |
| `preset_no` | 3 | 0 | 0.34 | Even split: {1: 33.3%, 2: 33.3%, 3: 33.3%} |
| `total_cost` | -0.62 | 0 | -0.006 | Low correlation, high cardinality |
| `ocid` | -0.33 | 0 | 0.004 | High cardinality, low correlation |
| `document_hash` | -1 | 0 | 0.002 | Near-unique |
| `equipment_count` | 23 | 0 | 0.38 | Mode: 20 (60%) |

---

## 2. Bug #1: user_ign Pipeline Population Failure

### Evidence

`user_ign` is NULL across **all** recent data, not just old rows:

| Date | Rows | user_ign populated | % |
|------|------|-------------------|---|
| 2026-05-17 | 861,654 | 0 | 0.00% |
| 2026-05-13 | 3,203 | 6 | 0.19% |
| 2026-05-12 | 4 | 0 | 0.00% |

### Root Cause

Current pipeline (Synchronizer/Calculator → upsert) does not map `user_ign` during write. The column exists in the table and index but the write path omits the value.

### Impact

- **Query 1** (`WHERE user_ign = ? AND preset_no = ?`): Returns 0 rows for all IGN lookups. Read path via user_ign is completely broken.
- **Index `idx_equipment_read_model_user_ign_preset`**: Scanned only 7 times. Not because it's unneeded — because the data it indexes doesn't exist.
- **Index should NOT be dropped** — it becomes the primary read path once user_ign is populated.

### Fix Required

1. Trace Synchronizer/Calculator write path → find where user_ign mapping should occur
2. Add user_ign to the upsert DTO/projection
3. Backfill or let next sync cycle populate user_ign
4. Verify index usage after population

---

## 3. Bug #2: Ranking Query Full Scan — 3,779 ms

### Query Shape

```sql
SELECT user_ign, total_cost
FROM character_equipment_read_model
WHERE preset_no = 1
ORDER BY total_cost DESC
LIMIT 10;
```

### Execution Plan (preset_no=1, 288K matching rows)

```
Limit (actual time=3761.632..3776.930 rows=10)
  -> Gather Merge (Workers Planned: 2, Workers Launched: 2)
       -> Sort (Sort Method: top-N heapsort Memory: 25kB)
            Sort Key: total_cost DESC
            -> Parallel Seq Scan on character_equipment_read_model
                 Filter: (preset_no = 1)
                 Rows Removed by Filter: 192,221
                 Buffers: shared hit=11264 read=50365
Execution Time: 3779.657 ms
```

### Why This Plan

1. `preset_no = 1` matches 288K rows (33% of table) — planner correctly rejects index scan
2. Falls back to **Parallel Seq Scan** (2 workers), reading ~400 MB from disk
3. Each worker sorts its ~96K row partition with **top-N heapsort**
4. Gather Merge merges 3 sorted streams, LIMIT 10 applied last
5. **No index supports `ORDER BY total_cost DESC`** — sort is unavoidable without it

### Why Initial Measurement Was Misleading

Initial EXPLAIN used `preset_no = 0` which has **0 matching rows** → planner chose cheap index scan → 303ms. Real preset values (1/2/3) each match 288K rows → planner switches to parallel seq scan → **3.8 seconds**.

| Query | preset_no | Matching rows | Plan | Time |
|-------|-----------|---------------|------|------|
| Initial test | 0 | 0 | Index Scan | 303 ms |
| **Actual** | **1** | **288,206** | **Parallel Seq Scan + Sort** | **3,779 ms** |
| **Actual** | **2** | **288,382** | **Parallel Seq Scan + Sort** | **~3,800 ms** |

### Bottleneck

Missing index on `(preset_no, total_cost DESC)` forces 288K row scan + sort per ranking query.

---

## 4. Upsert Write Amplification

### Current Upsert Pattern

```sql
INSERT ... ON CONFLICT (read_key) DO UPDATE SET ...
WHERE document_hash IS DISTINCT FROM EXCLUDED.document_hash
   OR user_ign IS DISTINCT FROM EXCLUDED.user_ign;
```

| Metric | Value |
|--------|-------|
| Total inserts | 864,868 |
| Total updates | 89,875,544 |
| HOT updates | 83,024,492 (92.4%) |
| Non-HOT updates | 6,850,052 (7.6%) |

### Analysis

- 92.4% HOT rate is healthy — most updates change only `document`, `total_cost`, `updated_at`, `document_hash`
- 7.6% non-HOT from page overflow after repeated in-place updates
- `user_ign_preset` index maintenance cost is real but small (10 MB index, B-tree insert only)
- Upsert conflict filter: since user_ign is NULL on both sides, `NULL IS DISTINCT FROM NULL` = `false`. Update fires only on `document_hash` change. Correct but adds eval overhead.

### HOT update detail

- `document` (bytea) is TOASTed — stored out of line, heap tuple stays small
- Non-HOT occurs when tuple grows beyond page free space after multiple updates
- 92.4% without fillfactor tuning is already good — fillfactor=90 could push to ~97% but diminishing returns

---

## 5. Planner Decision Analysis

### Why Parallel Seq Scan for ranking query

- 288K/864K = 33% selectivity on preset_no — beyond index scan threshold (~5-10%)
- No index provides pre-sorted order on total_cost
- Planner correctly chooses parallel seq scan over index scan + random heap access
- Adding `(preset_no, total_cost DESC)` index lets planner do **index-only scan** returning first 10 rows directly — no sort, no seq scan

### Why Index Scan was chosen for preset_no=0 (initial test)

- 0 matching rows → cardinality estimate = 1 → index scan wins over seq scan
- Misleading measurement — real workload uses preset_no 1/2/3

---

## 6. Recommendations

### 6.1 Fix user_ign Pipeline Mapping — Priority: P0 (Data Bug)

Trace Synchronizer/Calculator write path. Add user_ign to upsert projection. Verify with next sync cycle.

### 6.2 Add `(preset_no, total_cost DESC)` Index — Priority: P0

```sql
CREATE INDEX CONCURRENTLY idx_equipment_read_model_preset_cost
    ON character_equipment_read_model (preset_no, total_cost DESC);
```

**Expected plan:**

```
Limit (cost=0.42..1.24 rows=10)
  -> Index Scan using idx_equipment_read_model_preset_cost
       Index Cond: (preset_no = ?)
       -- Pre-sorted. No sort. No seq scan.
```

**Expected improvement:**

| Metric | Before | After |
|--------|--------|-------|
| Execution time | 3,779 ms | < 1 ms |
| Buffers read | 50,365 | < 20 |
| Rows scanned | 288,206 | 10 |
| Sort required | Yes (top-N heapsort) | No |
| Workers needed | 2 | 0 |

**Trade-off:**

- +~20-30 MB index size
- +index maintenance on every upsert where total_cost changes
- HOT update rate may decrease (new indexed column changes → non-HOT)
- **Net: strongly positive** — 3,800x improvement on ranking query

### 6.3 Keep `idx_equipment_read_model_user_ign_preset` — Priority: Defer

**Do NOT drop.** This index becomes the primary read path once user_ign pipeline bug is fixed.

After user_ign population:
- Re-evaluate with `ANALYZE character_equipment_read_model`
- Verify index usage in Query 1 plans
- Consider reversing column order to `(preset_no, user_ign)` if preset_no selectivity is needed first

### 6.4 Consider Fillfactor 90 — Priority: P2

```sql
ALTER TABLE character_equipment_read_model SET (fillfactor = 90);
-- Requires VACUUM FULL — schedule during maintenance window
```

| Metric | Before (fillfactor=100) | After (fillfactor=90) |
|--------|------------------------|----------------------|
| HOT update rate | 92.4% | ~97-98% |
| Table size increase | baseline | +10% (~48 MB) |
| Non-HOT updates reduced | 6.85M | ~1.7M |

Defer — 92.4% is already healthy.

---

## 7. Implementation Order

```
1. [P0] Fix user_ign mapping in Synchronizer/Calculator write path
2. [P0] CREATE INDEX CONCURRENTLY idx_equipment_read_model_preset_cost
       ON character_equipment_read_model (preset_no, total_cost DESC);
3. [Verify] ANALYZE character_equipment_read_model;
4. [Verify] Re-run EXPLAIN ANALYZE with preset_no = 1, user_ign populated
5. [P2] (Future) ALTER TABLE SET (fillfactor = 90) + VACUUM FULL
```

---

## 8. EXPLAIN ANALYZE Raw Results

### Ranking Query: preset_no=1, ORDER BY total_cost DESC LIMIT 10 (SELECT user_ign, total_cost)

```
Limit (cost=69640.16..69641.33 rows=10 width=41)
  (actual time=3761.632..3776.930 rows=10 loops=1)
  Buffers: shared hit=11264 read=50365
  -> Gather Merge (cost=69640.16..97545.74 rows=239174 width=41)
       Workers Planned: 2  Workers Launched: 2
       Buffers: shared hit=11264 read=50365
       -> Sort (cost=68640.14..68939.11 rows=119587 width=41)
            (actual time=3749.682..3749.685 rows=8 loops=3)
            Sort Key: total_cost DESC
            Sort Method: top-N heapsort  Memory: 25kB
            -> Parallel Seq Scan on character_equipment_read_model
                 (cost=0.00..66055.91 rows=119587 width=41)
                 (actual time=1.137..3709.418 rows=96069 loops=3)
                 Filter: (preset_no = 1)
                 Rows Removed by Filter: 192221
                 Buffers: shared hit=11192 read=50365
Planning Time: 11.285 ms
Execution Time: 3779.657 ms
```

### Ranking Query: preset_no=1, SELECT * (full row)

```
Limit (cost=69640.16..69641.33 rows=10 width=400)
  (actual time=164.914..176.240 rows=10 loops=1)
  Buffers: shared hit=11360 read=50269
  -> Gather Merge
       -> Sort (Sort Method: top-N heapsort Memory: 30kB)
            Sort Key: total_cost DESC
            -> Parallel Seq Scan on character_equipment_read_model
                 Filter: (preset_no = 1)
                 Rows Removed by Filter: 192221
Planning Time: 5.090 ms
Execution Time: 176.591 ms
```

### Query 1: user_ign + preset_no lookup (currently returns 0 rows)

```
Index Scan using idx_equipment_read_model_user_ign_preset
  (cost=0.42..8.45 rows=1 width=400)
  (actual time=8.125..8.127 rows=0 loops=1)
  Index Cond: ((user_ign = '진격캐넌'::text) AND (preset_no = 0))
  Buffers: shared hit=6
Planning Time: 16.883 ms
Execution Time: 8.249 ms
```

### ocid + preset_no lookup (unique constraint, with data)

```
Nested Loop (cost=8.87..16.90 rows=1 width=87)
  (actual time=1.698..1.702 rows=1 loops=1)
  -> HashAggregate (Group Key: ocid)
       -> Limit -> Index Scan using idx_equipment_read_model_user_ign_preset
            Index Cond: (user_ign IS NOT NULL)
  -> Index Scan using uq_character_equipment_ocid_preset
       Index Cond: ((ocid = ?) AND (preset_no = 1))
       Buffers: shared hit=7
Planning Time: 13.151 ms
Execution Time: 2.029 ms
```

### Upsert pattern

```
Insert on character_equipment_read_model
  (cost=0.00..0.02 rows=0 width=0)
  (actual time=13.833..13.834 rows=0 loops=1)
  Conflict Resolution: UPDATE
  Conflict Arbiter Indexes: character_equipment_read_model_pkey
  Conflict Filter: ((document_hash IS DISTINCT FROM excluded.document_hash)
                    OR (user_ign IS DISTINCT FROM excluded.user_ign))
  Tuples Inserted: 1  Conflicting Tuples: 0
  Buffers: shared hit=24 read=6 dirtied=4
Planning Time: 1.029 ms
Execution Time: 13.905 ms
```
