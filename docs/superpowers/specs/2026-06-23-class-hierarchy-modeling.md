# Spec: Class Hierarchy Data Modeling

- Date: 2026-06-23
- Parent Issue: #1343
- Parent ADR: ADR-735 (Future Analytics Platform Evaluation)
- Status: Proposed

---

## Goal

Design a portable, cross-store data model for the MapleStory class hierarchy (40+ jobs, 5 advancement tiers) that:

1. Documents the current PG encoding and its denormalization cost.
2. Proposes a single star-schema dimension (`dim_class`) usable on PostgreSQL (serving), ClickHouse (Phase 1), and Iceberg+Trino (Phase 2).
3. Captures the 1st-5th advancement hierarchy with a strategy that supports ancestor queries, branch rollups, and class rebalances.
4. Provides a basis for the "Serving Layer vs Analytics Layer Separation" companion issue.

---

## Non-Goals

- No schema changes to production tables.
- No modification of existing class encoding in `character_equipment_read_model`.
- No domain model changes to `module-core`.
- No new tables in the production schema.
- No implementation work — this is investigation/specification only.

---

## Background

`probabilistic-valuation-engine` ingests ~595K user snapshots/day and produces ~40M items/day. Character snapshots carry a class dimension (job, tier, job group) that is heavily denormalized into `character_equipment_read_model` for read efficiency.

ADR-735 commits the project to a phased analytics platform escalation:

- **Phase 0 (today):** PG-only serving + materialized views.
- **Phase 1:** ClickHouse for analytical Top-N and class rollups (trigger: p95 > 10s or Top-N becomes user-facing).
- **Phase 2:** Iceberg+Trino for ≥30TB historical corpus or time-travel needs.
- **Phase 3:** Iceberg+Spark for MLlib workloads.

The class dimension is the highest-cardinality analytical group-by (40+ classes × 50 worlds × 10 level buckets = 15K groups at base; explodes with time window and item category). Without a clean dimension model, the same hierarchy must be redefined in each store, and class rebalances will silently desynchronize serving and analytical views.

The MapleStory class taxonomy is a 5-tier tree (Beginner → 1st → 2nd → 3rd → 4th; 5th-job is a 2023 Anima/Nova mechanic layered on top). It includes ~40-50 leaf classes plus intermediate parents, distributed across 9+ job groups (Explorer, Cygnus, Resistance, Nova, Sengoku, Anima, Jianghu, Shine, Arcana).

---

## Design

### 1. Taxonomy

#### 1.1 Job Groups (top-level rollup)

| Group | Examples | Branches |
|-------|----------|----------|
| Beginner | Citizen | 1 |
| Explorer | Warrior, Magician, Archer, Thief, Pirate, Xenon | 30 |
| Cygnus | Noblesse, Dawn Warrior, Blaze Wizard, Wind Archer, Night Walker, Thunder Breaker | 6 |
| Resistance | Wild Hunter, Battle Mage, Mechanic | 3 |
| Nova | Kaiser, Angelic Buster | 2 |
| Sengoku | Hayato, Kanna | 2 |
| Anima | Lara, Hoyoung | 2 |
| Jianghu | Kinesis | 1 |
| Shine | Sia, Aria | 2 |
| Arcana | (TBD) | 1-3 |
| **Total** | | **~52** |

#### 1.2 Advancement Tiers

| Tier | Description | Storage |
|------|-------------|---------|
| 0 | Beginner / Citizen | `advancement_tier = 0` |
| 1 | 1st job | `advancement_tier = 1` |
| 2 | 2nd job | `advancement_tier = 2` |
| 3 | 3rd job | `advancement_tier = 3` |
| 4 | 4th job | `advancement_tier = 4` |
| 5 | 5th job (Anima/Nova special mechanic) | `advancement_tier = 5` |

#### 1.3 Hierarchy Storage Strategy

| Store | Strategy | Rationale |
|-------|----------|-----------|
| PostgreSQL (serving) | Closure table `dim_class_closure(ancestor, descendant, depth)` | O(1) ancestor lookup; cheap subtree query; supports SCD Type 2 |
| ClickHouse (analytical) | Adjacency list (`parent_class_key`) | Depth bounded at 5; JOIN depth 5 acceptable; no closure table writes |
| Iceberg (analytical) | Adjacency list | Same as ClickHouse; small dim table, JOIN cost negligible |

Rationale: PG needs fast ancestry for class-rollup queries on the read path; analytical stores trade write amplification for join cost. With max depth 5, the difference is small but the PG read path is hotter.

### 2. Dimensional Model

#### 2.1 `dim_class`

```
dim_class (
  class_key           INT           PRIMARY KEY,    -- surrogate
  class_id            SMALLINT      NOT NULL,        -- source id (NEXON/Mediaroh)
  class_name_en       VARCHAR(32)   NOT NULL,
  class_name_kr       VARCHAR(32),
  job_group_id        SMALLINT      NOT NULL,        -- FK dim_job_group
  job_group_name      VARCHAR(32),                   -- denormalized for fast rollup
  advancement_tier    TINYINT       NOT NULL,        -- 0..5
  parent_class_key    INT           REFERENCES dim_class(class_key),
  is_5th_job          BOOLEAN,                       -- computed at load
  is_active           BOOLEAN       NOT NULL,
  effective_from      DATE          NOT NULL,        -- SCD Type 2
  effective_to        DATE                            -- NULL = current
)
```

#### 2.2 `dim_job_group` (separate small dim)

```
dim_job_group (
  job_group_id        SMALLINT      PRIMARY KEY,
  job_group_name      VARCHAR(32),
  release_year        SMALLINT,
  region_origin       VARCHAR(16)                    -- KMS / JMS / GMS / MSEA
)
```

#### 2.3 `dim_class_closure` (PG only)

```
dim_class_closure (
  ancestor_key        INT           NOT NULL,
  descendant_key      INT           NOT NULL,
  depth               TINYINT       NOT NULL,
  PRIMARY KEY (ancestor_key, descendant_key)
)
```

#### 2.4 Backward-Compatible Read Model

`character_equipment_read_model` keeps current columns. New columns (e.g. `class_key`) are **optional** in the spec; the audit will confirm whether surrogate-key migration is in scope for a follow-up issue.

### 3. Cross-Store Compatibility

#### 3.1 PostgreSQL (current)

- `dim_class` + `dim_class_closure` as regular tables.
- `dim_job_group` as a small reference table.
- Closure table rebuilt on class rebalance via `INSERT ... ON CONFLICT`.
- `LowCardinality` equivalent: not applicable (PG has no such type).
- JOIN cost on `class_key` (INT) is negligible relative to fact scan cost.

#### 3.2 ClickHouse (Phase 1)

- `dim_class` table engine: `MergeTree ORDER BY (class_key)`.
- `class_id`, `job_group_id` → `LowCardinality(UInt16)`.
- `class_name_kr` → `LowCardinality(String)`.
- `class_name_en` → `LowCardinality(String)` (max ~50 distinct values; below 10K cap).
- `is_5th_job`, `is_active` → `LowCardinality(UInt8)` (0/1).
- For hot analytical queries: use `dictGet('dim_class', ...)` to skip JOIN.
- No closure table; use CTE with `JOIN` up to 5 levels.

#### 3.3 Iceberg+Trino (Phase 2)

- `dim_class` as Iceberg table, no partition (≤100 rows, partition overhead exceeds benefit).
- `PARTITION BY (job_group_id)` is optional — only if dimension grows beyond 10K rows (it won't).
- Schema evolution: new locale columns (`class_name_ja`) added without rewrite.
- Trino reads with `SELECT * FROM iceberg.dw.dim_class` — same shape as PG.
- Fact table partition: `PARTITION BY (year(ts), job_group_id)` aligns with class rollup queries.

### 4. Storage Cost (current PG)

- Denormalized class fields per row: `class_id` (2B SMALLINT) + `class_name` (~16-24B UTF-8 KR/EN) + `job_group_name` (4-8B) + `advancement_tier` (1B) = **~25-35B per row**.
- 40M items/day × 30B = 1.2GB/day attributable to class fields.
- Monthly: ~36GB; annual: ~432GB. (~4% of total 10TB/year storage.)
- Normalizing to `dim_class` + INT FK (4B) saves ~21-31B/row → ~840GB-1.2TB/year savings.
- Trade-off: one JOIN per analytical query (acceptable for cold analytical path).

### 5. Query Patterns Covered

| Pattern | PG Plan | ClickHouse Plan | Iceberg Plan |
|---------|---------|-----------------|--------------|
| Top 100 per class × world | `RANK() OVER (PARTITION BY class_id, world_id)` on indexed scan | `topK(100) BY class_id, world_id` | Trino window function |
| Class distribution histogram | `GROUP BY class_id` btree | `groupArray(class_name_en)` | `GROUP BY class_id` |
| Ancestor rollup (e.g. "all Explorers") | `JOIN dim_class_closure ON ancestor_key = explorer_root` | `JOIN dim_class` 5-deep | Trino `UNNEST` on recursive CTE |
| Subtree at tier N | `WHERE depth = N` on closure | `WHERE advancement_tier = N` | Same |

---

## Acceptance Criteria

- [ ] Current PG class encoding documented: column types, constraints, indexes, NULL policy.
- [ ] 40+ job class taxonomy enumerated with parent_class_key relationships and tier assignments.
- [ ] Denormalization cost measured: bytes/row attributable to class fields, projected to annual cost.
- [ ] Query pattern inventory: top 5 class-based GROUP BY / JOIN patterns with current PG plan costs.
- [ ] `dim_class` schema designed: columns, types, SCD Type 2 strategy.
- [ ] `dim_job_group` schema designed.
- [ ] `dim_class_closure` schema designed (PG only).
- [ ] Cross-store mapping documented: PG (serving), ClickHouse (Phase 1), Iceberg (Phase 2) with type adaptations.
- [ ] LowCardinality mapping specified for ClickHouse.
- [ ] Iceberg partition-key implications documented (fact tables only).
- [ ] Risk register: 5 risks with mitigations.
- [ ] No implementation work — investigation only.

---

## Trade-offs

### Sensitivity

- **Class taxonomy churn**: NEXON rebalances a class every 6-18 months; affects SCD Type 2 closure table writes.
- **LowCardinality cardinality ceiling in CH**: 10K cap; current 50 classes is 0.5% of cap.
- **Storage cost vs JOIN cost**: denormalization saves ~840GB-1.2TB/year; one extra JOIN per analytical query.
- **Mixed-branch classes (Xenon)**: cross-tree ancestry breaks simple parent-child.
- **5th-job explosion**: 2023 mechanic; Kinesis/Hoyoung/Lara split into branches; total leaves grows by 2-3.

### Trade-off

| Choice | Get | Give up |
|--------|-----|---------|
| **Closure table in PG** | O(1) ancestor; cheap subtree | Write amplification on rebalance |
| **Adjacency list in CH/Iceberg** | Simple schema, no separate table | Recursive CTE for ancestor (depth 5 acceptable) |
| **SCD Type 2 on dim_class** | Time-travel for class rebalances | Storage overhead; multiple active rows per class_id |
| **Job group as separate dim** | Clean rollup; separable release metadata | Extra JOIN for "Explorer only" queries |
| **Korean + English names both stored** | Locale-flexible serving | UTF-8 expansion ~3B/row in PG |

### Risk

- **R1 (Medium)**: Class rebalance rewrites closure table; large transactions may lock the table. Mitigation: batch closure writes in <10K-row chunks; defer until Phase 1 since serving reads dominate today.
- **R2 (Low)**: LowCardinality cardinality ceiling in CH (10K). Mitigation: monitor; alert at 1K; schema change is a 1-hour DDL.
- **R3 (Low)**: Mixed-branch classes (Xenon) break parent-child expectations. Mitigation: explicit `parent_class_key` may be NULL for hybrid classes; treat as multi-parent in rollups via UNION.
- **R4 (Low)**: 5th-job mechanic is recent and may evolve. Mitigation: `is_5th_job` boolean + `advancement_tier` allows both legacy and future 5th-job variants.
- **R5 (Low)**: UTF-8 name storage inflates PG row size. Mitigation: normalize to FK + 4B INT, store names only in `dim_class`.

### Non-Risk

- **Calculator writer hot path**: untouched; class dimension lives in serving + analytical layers, not calculator.
- **OLTP serving path**: PG remains the sole serving store; dimension normalization is additive.
- **Module-core domain model**: not affected; class taxonomy lives in read model + analytics dim.

---

## References

- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` §3 Sensitivity, §4 Metrics
- Issue: #1343 "[Investigation] Class Hierarchy Data Modeling"
- Companion issue: "Serving Layer vs Analytics Layer Separation" (referenced in #1343)
- Public sources:
  - [MapleStory class taxonomy (Mediaroh / NEXON wiki data)](https://maplestory.fandom.com/wiki/Job)
  - [ClickHouse LowCardinality type](https://clickhouse.com/docs/en/sql-reference/data-types/lowcardinality)
  - [Apache Iceberg schema evolution](https://iceberg.apache.org/spec/#schema-evolution)
  - [Trino Iceberg connector](https://trino.io/docs/current/connector/iceberg.html)
- Internal:
  - `module-synchronizer/.../read-model/` (current `character_equipment_read_model` definition)
  - `module-calculator/.../core/port/inbound/` (class dimension in domain)

---

## Summary

> A single `dim_class` + `dim_job_group` + (PG-only) `dim_class_closure` model, with type adaptations for ClickHouse `LowCardinality` and Iceberg schema evolution, supports the same class hierarchy across all three stores — denormalization today costs ~840GB-1.2TB/year, saved by one JOIN per analytical query.
