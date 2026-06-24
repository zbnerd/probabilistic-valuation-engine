# Plan: Class Hierarchy Data Modeling

- Date: 2026-06-23
- Parent Issue: #1343
- Spec: `docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md`
- Parent ADR: ADR-735

---

## Phase Overview

| Phase | Title | Owner | Verification |
|-------|-------|-------|--------------|
| 1 | Taxonomy Enumeration | data-modeler | YAML/JSON taxonomy file committed |
| 2 | Current PG Audit | data-modeler | Audit report in spec §Acceptance |
| 3 | Dimensional Model Design | data-modeler | `dim_class` schema doc with examples |
| 4 | Cross-Store Mapping | data-modeler | CH + Iceberg mapping table |

All phases are investigation-only. No schema changes. No new tables. No domain model changes.

---

## Phase 1: Taxonomy Enumeration

**Goal:** Enumerate all MapleStory classes with parent-child advancement relationships.

### Task 1.1: Enumerate job groups
- Deliverable: `dim_job_group` rows in `docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md#1.1-job-groups-top-level-rollup`
- Owner: data-modeler
- Verification: 9+ job groups listed with region_origin + release_year

### Task 1.2: Enumerate leaf classes
- Deliverable: CSV/JSON file at `docs/superpowers/specs/data/class-taxonomy.csv` with columns: `class_id, class_name_en, class_name_kr, job_group_id, advancement_tier, parent_class_id, is_5th_job`
- Owner: data-modeler
- Verification: ≥40 rows; each row has `class_id`, `parent_class_id` (nullable for tier-0), `advancement_tier` ∈ {0..5}

### Task 1.3: Validate parent-child edges
- Deliverable: Adjacency list sanity check in taxonomy file
- Owner: data-modeler
- Verification: tree depth ≤ 5; no cycles; mixed-branch classes (Xenon) flagged

---

## Phase 2: Current PG Audit

**Goal:** Document current class encoding in `character_equipment_read_model` and measure denormalization cost.

### Task 2.1: Locate and read current schema
- Deliverable: Path + DDL of `character_equipment_read_model` (and related tables with class fields)
- Owner: data-modeler
- Verification: Column types, NULL policy, indexes, constraints documented

### Task 2.2: Measure column sizes
- Deliverable: `pg_column_size` or `pg_stats` snapshot of class-related columns
- Owner: data-modeler
- Verification: bytes/row per class field; total annual cost projection

### Task 2.3: Index audit
- Deliverable: List of indexes touching class fields (btree, hash, partial)
- Owner: data-modeler
- Verification: index list matches spec §Acceptance Criteria

### Task 2.4: Query pattern inventory
- Deliverable: Top 5 class-based GROUP BY / JOIN queries from production logs / pg_stat_statements
- Owner: data-modeler
- Verification: Each query has current plan + estimated cost

### Task 2.5: Storage cost projection
- Deliverable: Annual cost of denormalization vs normalization (one JOIN cost)
- Owner: data-modeler
- Verification: Numbers reconcile with spec §4 Storage Cost

---

## Phase 3: Dimensional Model Design

**Goal:** Specify `dim_class`, `dim_job_group`, `dim_class_closure` schemas with examples.

### Task 3.1: `dim_class` schema spec
- Deliverable: DDL-style spec with types, constraints, examples for 5 representative classes
- Owner: data-modeler
- Verification: All columns from spec §2.1 present; SCD Type 2 columns included

### Task 3.2: `dim_job_group` schema spec
- Deliverable: DDL-style spec with all 9+ groups
- Owner: data-modeler
- Verification: All columns from spec §2.2 present; release_year populated

### Task 3.3: `dim_class_closure` schema spec (PG only)
- Deliverable: DDL + 5 example rows showing ancestor/descendant relationships
- Owner: data-modeler
- Verification: Closure invariant holds (every node has self at depth 0; transitive closure complete)

### Task 3.4: SCD Type 2 strategy
- Deliverable: Mermaid/sequence diagram + written procedure for class rebalance
- Owner: data-modeler
- Verification: Procedure covers INSERT new + UPDATE old with `effective_to = now()`

### Task 3.5: Mixed-branch handling
- Deliverable: Spec section on Xenon (multi-parent) and 5th-job anomalies
- Owner: data-modeler
- Verification: `parent_class_key` NULL or multi-row strategy documented

---

## Phase 4: Cross-Store Mapping

**Goal:** Map `dim_class` to ClickHouse (Phase 1) and Iceberg (Phase 2) with type adaptations.

### Task 4.1: ClickHouse type mapping
- Deliverable: Mapping table (PG column → CH column with `LowCardinality` annotations)
- Owner: data-modeler
- Verification: All class fields mapped; LowCardinality usage justified

### Task 4.2: ClickHouse table engine
- Deliverable: `CREATE TABLE dim_class (...) ENGINE = MergeTree ORDER BY (class_key)` example
- Owner: data-modeler
- Verification: ORDER BY key supports common query patterns (point lookup + scan by job_group)

### Task 4.3: ClickHouse `dictGet` strategy
- Deliverable: Example query using `dictGet('dim_class', 'class_name_kr', class_key)`
- Owner: data-modeler
- Verification: Dict definition included; no JOIN in hot analytical path

### Task 4.4: Iceberg schema spec
- Deliverable: Iceberg table spec for `dim_class` (no partition, sort by class_key)
- Owner: data-modeler
- Verification: Schema evolution example (add `class_name_ja`) without rewrite

### Task 4.5: Iceberg partition-key decision for fact tables
- Deliverable: Recommendation: `PARTITION BY (year(ts), job_group_id)` for fact_character_snapshot
- Owner: data-modeler
- Verification: 15-30 buckets; aligns with class rollup queries

### Task 4.6: Trino query examples
- Deliverable: 3 Trino SQL examples reading `dim_class` from Iceberg catalog
- Owner: data-modeler
- Verification: Queries match PG semantics; no ClickHouse-specific syntax

---

## Cross-Cutting Deliverables

### Task X.1: Risk register
- Deliverable: 5 risks with mitigations (R1-R5 from spec)
- Owner: data-modeler
- Verification: Each risk has severity + mitigation

### Task X.2: Migration effort estimate
- Deliverable: Hours estimate per phase; engineer-week total
- Owner: data-modeler
- Verification: Reconciled with ADR-735 §4 Metrics

### Task X.3: Sign-off summary
- Deliverable: 1-page summary in spec §Summary
- Owner: data-modeler
- Verification: Matches spec §Summary

---

## Definition of Done

- [ ] All 4 phases complete with verification
- [ ] No schema changes to production tables
- [ ] No new tables in production schema
- [ ] No domain model changes to `module-core`
- [ ] Spec updated with all findings
- [ ] Risks documented with mitigations
- [ ] Migration effort estimated in engineer-weeks
- [ ] Cross-store mapping validated against public docs

---

## Out of Scope

- Schema migration scripts
- Production data backfill
- Calculator / module-core changes
- Iceberg catalog provisioning
- ClickHouse cluster sizing
- ML feature engineering

---

## Open Risks

- **Mixed-branch classes** (Xenon, 5th-job) may need separate handling beyond parent-child edges.
- **Korean name UTF-8 expansion** may increase PG row size if not normalized.
- **Class rebalance churn** (every 6-18 months) requires closure table rebuild.

---

## Summary

> 4 phases, 18 tasks, ~3 engineer-weeks, all investigation — produces a single star-schema dimension that works on PG, ClickHouse, and Iceberg with type adaptations.

---

## Grill-Me (5 Hard Questions)

### Q1: Closure table on PG forces a write-amplification on every class rebalance. If NEXON rebalances 3 classes per year, that's 3 closure table rebuilds/year. Have you considered materializing ancestors as a generated/denormalized column on `dim_class` (e.g., `ancestor_keys INT[]`) to avoid the closure table entirely?

**Resolution:** Closure table stays. PG `INT[]` ancestor column means: (a) no transitive closure (only direct ancestors), (b) UPDATE cost on rebalance, (c) inefficient for subtree queries. Closure table is O(1) ancestor AND O(1) subtree. Cost is amortized: 3 rebuilds/year is small.

### Q2: You store `class_name_kr` + `class_name_en` for locale flexibility, but `class_name_kr` UTF-8 expansion is ~3B/row. If 40M items/day carry a denormalized class name, that's 120MB/day just for the Korean name field. Have you considered storing names only in `dim_class` (4B FK per row) and accepting the JOIN cost on serving reads?

**Resolution:** Names live in `dim_class` only. The current `character_equipment_read_model` keeps its denormalized names (out of scope per issue), but the **new** analytical layer (CH/Iceberg) uses FK + JOIN. Saving: ~840GB-1.2TB/year at one JOIN per analytical query.

### Q3: `LowCardinality` in ClickHouse has a 10K cap. With 50 classes today, you're at 0.5% of cap. But what if NEXON adds a "custom class" feature (server-side generated classes per character)? That could explode to 10K+ per region. Have you designed a fallback path?

**Resolution:** Custom-class scenario is out of scope for this issue (it's a future NEXON feature, not yet announced). If it materializes: switch `class_id` from `LowCardinality(UInt16)` to plain `UInt32`; CH schema change is a 1-hour DDL. Documented as R2.

### Q4: You have `is_5th_job` boolean + `advancement_tier = 5`. But 5th-job is a 2023 mechanic that only applies to Anima/Nova branches. Kinesis, Cygnus, Resistance don't have 5th-job. Won't this make `is_5th_job` redundant with `(job_group_id, advancement_tier)`?

**Resolution:** Keep `is_5th_job` as a derived column for query ergonomics. The boolean is denormalized for index efficiency: queries like "all 5th-job characters" become `WHERE is_5th_job = true` (low-cardinality bit) instead of `WHERE job_group_id IN (4, 6) AND advancement_tier = 5` (4-element scan). Trade-off: storage for speed.

### Q5: Iceberg `PARTITION BY (year(ts), job_group_id)` produces ~15-30 buckets/year. With 340GB/day ingest, each bucket is ~1-2TB. That's 1000+ files per bucket. Iceberg manifest list will grow large; compaction becomes Tier-1 service. Have you modeled compaction cost?

**Resolution:** Compaction cost is real but deferred to ADR-735 §3 Risk (compaction lag). For this issue: keep partition spec simple; revisit at Phase 2 (Iceberg) trigger time. Compaction is already a known Tier-1 service per parent ADR. No new risk introduced.

---

## Post-Grill Resolution Summary

| Q | Decision | Justification |
|---|----------|---------------|
| Q1 | Closure table on PG | O(1) ancestor + subtree; 3 rebuilds/year is cheap |
| Q2 | Names in `dim_class` only | Save 840GB-1.2TB/year; JOIN on analytical path |
| Q3 | `LowCardinality(UInt16)` stays | Custom-class scenario is NEXON future; 1-hour DDL fallback |
| Q4 | `is_5th_job` kept as denorm | Index efficiency; ergonomic for class rollups |
| Q5 | Iceberg partition kept | Compaction cost deferred to ADR-735 §3 |

No spec/plan changes needed. All 5 questions resolved within current design.
