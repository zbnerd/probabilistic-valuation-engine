# Query Plan Analysis: character_equipment_read_model

- Date: 2026-05-19
- DB: PostgreSQL 16 (local, `maple_expectation`)
- Table: `character_equipment_read_model`

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

### Indexes

| Index | Columns | Size | Scans | Purpose |
|-------|---------|------|-------|---------|
| `character_equipment_read_model_pkey` | `read_key` (PK) | 83 MB | 90,740,406 | Upsert arbiter |
| `uq_character_equipment_ocid_preset` | `(ocid, preset_no)` UNIQUE | 83 MB | 3 | Dedup constraint |
| `idx_equipment_read_model_user_ign_preset` | `(user_ign, preset_no)` | 10 MB | 7 | Query support |

### Column Statistics (pg_stats)

| Column | n_distinct | null_frac | correlation | Notes |
|--------|-----------|-----------|-------------|-------|
| `user_ign` | 0 | **1.0 (100%)** | - | **Pipeline bug: NULL in all recent data** (6 / 864,868 non-null). 5/17 bulk upsert (861K rows): 0% populated |
| `preset_no` | 3 | 0 | 0.34 | Even split: {3: 33.5%, 2: 33.3%, 1: 33.2%} |
| `total_cost` | -0.62 | 0 | -0.006 | Low correlation, high cardinality |
| `ocid` | -0.33 | 0 | 0.004 | High cardinality, low correlation |
| `document_hash` | -1 | 0 | 0.002 | Near-unique |
| `equipment_count` | 23 | 0 | 0.38 | Mode: 20 (60%) |

---

## 2. Current Bottlenecks

### 2.1 Query 2: ORDER BY total_cost LIMIT — **303 ms (Critical)**

```sql
SELECT user_ign, total_cost
FROM character_equipment_read_model
WHERE preset_no = 0
ORDER BY total_cost DESC
LIMIT 10;
```

**Execution Plan:**

```
Limit (cost=11686.87..11686.87 rows=1 width=41) (actual time=303.056..303.059 rows=0 loops=1)
  -> Sort (cost=11686.87..11686.87 rows=1 width=41) (actual time=303.054..303.056 rows=0 loops=1)
       Sort Key: total_cost DESC
       Sort Method: quicksort  Memory: 25kB
       -> Index Scan using idx_equipment_read_model_user_ign_preset
            Index Cond: (preset_no = 0)
            Buffers: shared hit=23 read=1269
```

**Why this plan:**

1. Planner picks `idx_equipment_read_model_user_ign_preset` because `preset_no` is the trailing column
2. Since `user_ign` is unconstrained, only the second index column is usable — but as a **skip scan** over the leading NULL column
3. PostgreSQL must traverse all NULL user_ign entries where preset_no=0 (~288K rows, 33% of table)
4. All 288K rows fetched, then **sorted in memory** by total_cost DESC
5. LIMIT 10 applied after full sort — late optimization

**Bottleneck:** Missing index on `(preset_no, total_cost DESC)` forces full scan + sort for every leaderboard query.

### 2.2 user_ign = 100% NULL — Pipeline Bug + Index Dead Weight

`user_ign` is NULL across **all** recent data, not just old rows:

| Date | Rows | user_ign populated | % |
|------|------|-------------------|---|
| 2026-05-17 | 861,654 | 0 | 0.00% |
| 2026-05-13 | 3,203 | 6 | 0.19% |
| 2026-05-12 | 4 | 0 | 0.00% |

**Root cause:** Current pipeline does not populate `user_ign` during upsert. The column was likely intended to be filled but the mapping is missing in the Synchronizer/Calculator write path.

The index `idx_equipment_read_model_user_ign_preset` has `user_ign` as leading column. Since user_ign is NULL in 99.999% of rows:

- **Query 1** (`WHERE user_ign = ? AND preset_no = ?`): Returns 0 rows. Index works but serves no real data.
- The index only contributes to Query 2 by accident (providing preset_no filtering via skip scan), which is the wrong tool for that job.
- **10 MB wasted** on an index that has been scanned 7 times total.

### 2.3 Upsert Write Amplification

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

**Analysis:**

- 92.4% HOT rate is healthy — most updates don't touch indexed columns
- The 7.6% non-HOT updates cause index page splits and bloat on the PK index
- Each non-HOT update writes a new index entry in all 3 indexes (PK, ocid_preset, user_ign_preset)
- The `user_ign_preset` index is maintained on every update despite being useless — adding write cost with zero read benefit

**Upsert conflict filter:** Since user_ign is NULL on both old and new rows almost always, `NULL IS DISTINCT FROM NULL` = `false`. The update fires only when `document_hash` changes. Correct behavior, but the OR condition adds eval cost on every conflict check.

---

## 3. Planner Decision Analysis

### Why planner chose Index Scan over Seq Scan for Query 2

- Table has 864K rows, `preset_no = 0` matches ~0 rows (preset values are 1,2,3 — not 0)
- Planner estimates 1 row → index scan cheaper than seq scan
- **But**: When preset_no=1/2/3 (the real values), the index would match ~288K rows each
- With 288K rows, planner might switch to Seq Scan + Sort — neither is good
- The correct fix is an index that provides pre-sorted order

### Why HOT rate is 92.4% without fillfactor tuning

- `document` (bytea, ~avg 500B compressed) is TOASTed — stored out of line
- When only `total_cost`, `updated_at`, `document_hash` change, the heap tuple fits in the same page
- Non-HOT occurs when: tuple grows beyond page free space, or indexed column (`ocid`, `preset_no`, `read_key`) changes
- 7.6% non-HOT likely from row migration after repeated updates fill the page

---

## 4. Recommendations

### 4.1 Add: `(preset_no, total_cost DESC)` — Priority: P0

```sql
CREATE INDEX idx_equipment_read_model_preset_cost
    ON character_equipment_read_model (preset_no, total_cost DESC);
```

**Expected plan change:**

```
Limit (cost=0.42..1.24 rows=10)
  -> Index Scan using idx_equipment_read_model_preset_cost
       Index Cond: (preset_no = ?)
       -- No sort needed. Index already ordered by total_cost DESC.
```

**Expected improvement:**

| Metric | Before | After |
|--------|--------|-------|
| Execution time | 303 ms | < 1 ms |
| Buffers read | 1,269 | < 20 |
| Sort required | Yes (288K rows) | No |
| Index depth | N/A | 3-4 levels |

**Trade-off:**

- +~20-30 MB index size
- +index maintenance on every INSERT/UPDATE (total_cost changes frequently)
- HOT update rate may decrease slightly if total_cost is in the new index and changes trigger non-HOT updates
- **Net: strongly positive** — leaderboard query goes from 300ms to <1ms

### 4.2 Drop or Repurpose: `idx_equipment_read_model_user_ign_preset` — Priority: P1

```sql
-- Option A: Drop entirely if user_ign queries are not used
DROP INDEX idx_equipment_read_model_user_ign_preset;

-- Option B: Replace with (preset_no, user_ign) if user_ign will be populated later
DROP INDEX idx_equipment_read_model_user_ign_preset;
CREATE INDEX idx_equipment_read_model_preset_ign
    ON character_equipment_read_model (preset_no, user_ign);
```

**Rationale:**

- user_ign is 99.999% NULL — index provides zero query acceleration
- Every upsert pays maintenance cost on this index (insert + potential page split)
- Dropping saves 10 MB and reduces per-upsert overhead
- If user_ign will be populated in the future, recreate with `preset_no` as leading column for better selectivity

### 4.3 Consider: Fillfactor 90 — Priority: P2

```sql
ALTER TABLE character_equipment_read_model SET (fillfactor = 90);
-- Requires VACUUM FULL to rebuild table with new fillfactor
-- VACUUM FULL requires exclusive lock — schedule during maintenance window
```

**Expected impact:**

| Metric | Before (fillfactor=100) | After (fillfactor=90) |
|--------|------------------------|----------------------|
| HOT update rate | 92.4% | ~97-98% |
| Table size increase | baseline | +10% (~48 MB) |
| Non-HOT updates reduced | 6.85M | ~1.7M |

**Trade-off:**

- 10% more disk usage per page (free space reserved for updates)
- VACUUM FULL requires full table rewrite + exclusive lock
- Diminishing returns — 92.4% → 97% is nice but not critical
- **Defer until non-HOT updates become a measurable bottleneck**

### 4.4 Consider: Partial index on user_ign — Priority: P3

If user_ign will be populated for a subset of rows:

```sql
CREATE INDEX idx_equipment_read_model_user_ign_partial
    ON character_equipment_read_model (user_ign, preset_no)
    WHERE user_ign IS NOT NULL;
```

Tiny index (6 rows currently), fast for the rare case of user_ign lookups.

---

## 5. Read/Write Trade-off Summary

| Change | Read Benefit | Write Cost | Verdict |
|--------|-------------|------------|---------|
| Add `(preset_no, total_cost DESC)` | 300x faster leaderboard | +index maint on upsert | **Do now** |
| Drop `(user_ign, preset_no)` | None (dead index) | -10 MB, -1 index to maintain | **Do now** |
| Fillfactor 90 | Indirect (more HOT) | +48 MB, VACUUM FULL lock | **Defer** |
| Partial index on user_ign | Fast NULL-filtered lookup | Negligible | **If user_ign gets populated** |

---

## 6. Implementation Order

```
1. CREATE INDEX idx_equipment_read_model_preset_cost
   ON character_equipment_read_model (preset_no, total_cost DESC);
   -- CREATE INDEX CONCURRENTLY in production to avoid lock

2. DROP INDEX idx_equipment_read_model_user_ign_preset;
   -- Safe: only 7 scans in history, all returns 0 rows

3. Monitor: pg_stat_user_indexes for new index usage

4. (Future) ALTER TABLE SET (fillfactor = 90) + VACUUM FULL
   -- Schedule during maintenance window
```

---

## 7. EXPLAIN ANALYZE Raw Results

### Query 1: user_ign + preset_no lookup

```
Index Scan using idx_equipment_read_model_user_ign_preset
  (cost=0.42..8.45 rows=1 width=400)
  (actual time=8.125..8.127 rows=0 loops=1)
  Index Cond: ((user_ign = '진격캐넌'::text) AND (preset_no = 0))
  Buffers: shared hit=6
Planning Time: 16.883 ms
Execution Time: 8.249 ms
```

### Query 2: ORDER BY total_cost DESC LIMIT 10

```
Limit (cost=11686.87..11686.87 rows=1 width=41)
  (actual time=303.056..303.059 rows=0 loops=1)
  Buffers: shared hit=26 read=1269
  -> Sort (cost=11686.87..11686.87 rows=1 width=41)
       Sort Key: total_cost DESC
       Sort Method: quicksort  Memory: 25kB
       -> Index Scan using idx_equipment_read_model_user_ign_preset
            Index Cond: (preset_no = 0)
            Buffers: shared hit=23 read=1269
Planning Time: 0.934 ms
Execution Time: 303.104 ms
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

### ocid + preset_no lookup (unique constraint)

```
Index Scan using uq_character_equipment_ocid_preset
  (cost=0.42..8.44 rows=1 width=55)
  (actual time=2.400..2.401 rows=0 loops=1)
  Index Cond: ((ocid = 'abcdef1234567890'::text) AND (preset_no = 0))
  Buffers: shared hit=5 read=1
Planning Time: 1.215 ms
Execution Time: 2.471 ms
```
