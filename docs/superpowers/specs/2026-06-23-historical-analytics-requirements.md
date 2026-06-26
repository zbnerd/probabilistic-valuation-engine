# Spec: Historical Analytics Requirements (Issue #1342)

- Status: Proposed
- Date: 2026-06-23
- Owner: Architecture Team
- Parent: ADR-735
- Issue: #1342

---

## 1. Goal

Produce a concrete, ranked inventory of analytical workloads that the platform must support beyond the current per-character serving path. The inventory is the evidence base for the trigger gates (T1-T8) defined in ADR-735 §2 and feeds the platform-escalation decision.

Success = every potential analytical use case is captured with frequency, latency budget, volume, freshness, consumer, cross-source join requirement, time-travel requirement, retention class, roadmap commitment, and priority tier (P0-P3).

## 2. Non-Goals

- No implementation of any analytical workload
- No database schema changes
- No new service deployment
- No vendor selection (ClickHouse / Iceberg / Trino / Spark)
- No code in this investigation

## 3. Background

ADR-735 establishes PG-only as the default analytics strategy and defines 8 escalation triggers (T1-T8) tied to measurable conditions. ADR-735 §1 lists candidate workloads (class/world/level rollups, multi-week trend, Top-N, recommendations, ML feature pipelines) but does not enumerate them, rank them, or map them to triggers.

Without this inventory, the trigger gates have no concrete workload to fire on, and platform-escalation decisions become speculative. Issue #1342 closes that gap by requiring the workload inventory before any platform work begins.

## 4. Design

### 4.1 Workload Inventory Template

Every workload entry captures the following 14 fields:

| # | Field | Type | Required |
|---|-------|------|----------|
| 1 | ID | `WA-N` slug | yes |
| 2 | Persona / Story | "As a …, I want …, so that …" | yes |
| 3 | Question template | concrete parameterized SQL shape | yes |
| 4 | Cardinality | rows scanned / rows emitted / distinct keys | yes |
| 5 | Frequency | ad-hoc / hourly / daily / weekly / monthly / on-demand | yes |
| 6 | Latency budget | p50 / p95 / p99 numeric (ms or s) | yes |
| 7 | Data volume | GB scanned per run; rows returned | yes |
| 8 | Freshness | real-time (<5min) / near-RT (<1h) / hourly / daily / weekly | yes |
| 9 | Consumer | analyst / dashboard / user-facing API / ML feature / partner / regulatory | yes |
| 10 | Cross-source join | PG-only / PG+MinIO / multi-system | yes |
| 11 | Time-travel | TT-0 none / TT-1 point-in-time / TT-2 range / TT-3 version-tag / TT-4 branch | yes |
| 12 | Retention | RET-N none / 30-90d / 1-7y / 7-10y / permanent | yes |
| 13 | Roadmap commitment | COMMITTED / SPECULATIVE | yes |
| 14 | Trigger gate hit | T1..T8 from ADR-735 §2 (multi-select) | yes |

### 4.2 Consumer Taxonomy

| Code | Consumer | SLA class | Counted for trigger? |
|------|----------|-----------|----------------------|
| C-1 | Analyst ad-hoc | best-effort, human-rate | no (T1 only) |
| C-2 | Internal dashboard | scheduled, low concurrency | no |
| C-3 | User-facing API | contractual p95 budget | **yes (T3)** |
| C-4 | ML feature pipeline | batch, latency-tolerant | **yes (T7)** |
| C-5 | Partner / external SQL | contractual | **yes (T8)** |
| C-6 | Regulatory report | scheduled, immutable output | yes (retention-driven) |

### 4.3 Priority Ranking Rubric

Score 0-3 per axis, sum, sort into tiers.

| Axis | 0 | 1 | 2 | 3 |
|------|---|---|---|---|
| Roadmap commitment | speculative | desired | planned | committed in next 2Q |
| Frequency | on-demand | monthly | daily | hourly+ |
| Consumer breadth | single analyst | team-internal | cross-team | user-facing / external |
| Trigger gate hit | none | soft (T1) | hard (T2-T4) | mandate (T5-T8) |
| Decision impact | informational | internal | product feature | contractual SLA |

Tiers:
- **P0 (12-15)**: ship Phase-0 support now, design for Phase-1 cutover
- **P1 (8-11)**: design Phase-0, defer platform decision
- **P2 (4-7)**: document only, revisit in 6 months
- **P3 (0-3)**: drop or punt to a future ADR

### 4.4 Cross-Source Join Classification

- **CSJ-A (PG-only)**: phase 0 default
- **CSJ-B (PG + MinIO chunks)**: triggers T6
- **CSJ-C (multi-system)**: triggers T6 + partner data layer

### 4.5 Time-Travel Taxonomy

- **TT-0**: latest state (PG works)
- **TT-1**: point-in-time (PG time-bucketed partitioning works)
- **TT-2**: range scan (PG range partitioning works)
- **TT-3**: version-tag (Iceberg)
- **TT-4**: branch / fork (Iceberg)

TT-3+ triggers T5.

### 4.6 Retention Classes

- **RET-N**: ephemeral
- **RET-1**: operational 30-90d
- **RET-2**: financial 1-7y
- **RET-3**: compliance 7-10y
- **RET-4**: permanent / WORM

RET-3+ implies immutable storage tier; influences T8 partner data contract.

### 4.7 Output Artifacts

1. **Workload matrix** — `docs/03_Technical_Guides/historical-analytics-workloads.md` (Markdown table, one row per workload)
2. **Priority ranking** — sorted P0-P3 list with score breakdown
3. **Trigger-gate map** — matrix: workload × T1..T8
4. **Cross-source map** — workload × CSJ class
5. **Time-travel map** — workload × TT class
6. **Retention map** — workload × RET class

### 4.8 Seed Workload List (from ADR-735 §1)

| ID | Workload (short) |
|----|------------------|
| WA-1 | Class / world / level-range rollup |
| WA-2 | Multi-week expectation drift |
| WA-3 | Top-N leaderboard per class/world |
| WA-4 | Recommendation system inputs |
| WA-5 | ML feature pipelines (per-item features) |
| WA-6 | Item price history (per character) |
| WA-7 | Ingestion anomaly detection |
| WA-8 | Cross-class economic comparison |
| WA-9 | Daily active user / churn signals |
| WA-10 | Regulatory / data-lineage report |

## 5. Acceptance Criteria

- [ ] Workload inventory contains ≥10 candidate workloads, each with all 14 template fields filled
- [ ] Frequency, latency, volume, freshness captured per workload
- [ ] Consumer identified per workload from the C-1..C-6 taxonomy
- [ ] Cross-source join class (CSJ-A / B / C) per workload
- [ ] Time-travel class (TT-0..TT-4) per workload
- [ ] Retention class (RET-N..RET-4) per workload
- [ ] Roadmap commitment (COMMITTED / SPECULATIVE) per workload
- [ ] Priority score 0-15 with P0-P3 tier assignment per workload
- [ ] Trigger-gate map (workload × T1..T8) published
- [ ] No code, no schema, no deployment — investigation only
- [ ] Output doc published under `docs/03_Technical_Guides/`
- [ ] Doc linked from ADR-735 §5 References

## 6. Trade-offs

### Sensitivity

- Roadmap commitment accuracy (depends on PM/roadmap source)
- Latency budgets for hypothetical user-facing APIs (no contract yet)
- Regulatory baseline jurisdiction (KR, US, EU may differ)
- Cardinality estimates (depend on growth projection)

### Trade-off

| Choice | Get | Give up |
|--------|-----|---------|
| Capture all 14 fields per workload | Comparable ranking; trigger mapping | Slower interview cycle; harder to keep matrix current |
| Use 0-3 ordinal scoring | Simple, auditable rubric | Coarse; ties possible |
| Seed list from ADR-735 | Anchor to existing roadmap signals | May miss workloads not yet named |
| Output as Markdown table | Versioned in git, diffable | Not directly queryable; copy-paste into BI tools needed |

### Risk

- Latency budgets guessed for non-existent APIs → wrong priority → over-build
- Roadmap commitment misread (planned ≠ committed) → P0 actually P2
- Regulatory class overlooked → RET-3 workload shipped on mutable storage

### Non-Risk

- No infra change in this investigation (Phase-0 untouched)
- No platform commitment (only inventory + ranking)

## 7. Grill-me (self-challenge)

> Five hard questions raised against the spec, with answers baked into the design. No follow-up tickets; the spec is responsible for the resolution.

1. **Q: "Roadmap commitment" is the load-bearing axis — who is the source of truth?**
   A: The PM owner of analytics. COMMITTED requires a link to a roadmap doc / issue with a target quarter. SPECULATIVE is the default; promotion to COMMITTED is gated on PM sign-off in Phase 3 task 3.4.
2. **Q: "Latency budget" for workloads that have no API contract yet — is this just guessing?**
   A: Yes, and that is acceptable. Guessed budgets are tagged `estimated` in the matrix. The first user-facing analytical API contract triggers a re-score for that workload (revisit quarterly).
3. **Q: "Time-travel" beyond TT-2 (TT-3 version-tag, TT-4 branch) auto-fires T5. Does that mean the inventory implicitly commits to Iceberg?**
   A: No. T5 firing is a trigger condition, not a commitment. The inventory records that T5 would fire for a workload; ADR-735 owns the platform decision. The two artifacts stay independent.
4. **Q: "Cross-source join CSJ-B" depends on MinIO chunk shape — but chunks are JSONL.gz per run, not a queryable table. How is the join modeled?**
   A: CSJ-B is a "join to raw snapshot" workload, executed by either (a) external ETL that writes a derived table to PG, or (b) Phase-2 Iceberg tables that read MinIO directly. The inventory records CSJ-B existence; the join mechanism is decided per-workload when the trigger fires.
5. **Q: "Retention RET-3 (7-10y)" implies WORM storage. Is that a platform decision this ADR is making?**
   A: No. Retention is recorded as a requirement. If RET-3+ workloads exist, they generate a separate compliance/storage ADR; ADR-735 stays focused on analytics tier.

## 8. References

- ADR-735 §1 Background (candidate workloads)
- ADR-735 §2 Decision (trigger gates T1-T8)
- Issue #1342 acceptance criteria
- Companion issues: Query Engine Evaluation, PG Scalability Assessment
- `docs/agents/issue-tracker.md`
- `docs/agents/triage-labels.md`
