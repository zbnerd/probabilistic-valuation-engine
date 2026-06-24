# Plan: Historical Analytics Requirements (Issue #1342)

- Spec: `docs/superpowers/specs/2026-06-23-historical-analytics-requirements.md`
- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Investigation parent: #1342
- Date: 2026-06-23

---

## Phase 1 — Stakeholder Interviews (requirements gathering)

Goal: collect the 14 fields per workload from the people who would author or consume the workload.

| # | Task | Deliverable | Owner suggestion | Verification |
|---|------|-------------|------------------|--------------|
| 1.1 | Interview data analyst persona (ad-hoc query author) | WA-1, WA-2, WA-6, WA-8 raw notes | Data analyst lead | Notes file checked in; ≥3 candidate workloads captured |
| 1.2 | Interview product manager (roadmap commitment) | COMMITTED vs SPECULATIVE tag per workload | PM owner of analytics | Roadmap citations per workload (Slack/Issue link) |
| 1.3 | Interview SRE (latency / volume / freshness from infra view) | Cardinality, frequency, p95 budgets | SRE on-call lead | Numbers cross-checked with Prometheus / DB stats |
| 1.4 | Interview on-call engineer (anomaly, retention, audit) | WA-7, WA-10, retention classes | On-call lead | Anomaly thresholds traceable to alert rules |

## Phase 2 — Workload Matrix Build

Goal: produce the 14-field inventory.

| # | Task | Deliverable | Owner suggestion | Verification |
|---|------|-------------|------------------|--------------|
| 2.1 | Consolidate Phase 1 notes into canonical matrix | `historical-analytics-workloads.md` draft | Architecture team | All 14 fields filled per row; no `TBD` allowed in P0/P1 |
| 2.2 | Classify cross-source join per workload | CSJ-A/B/C column | Architecture team | Each row has CSJ class |
| 2.3 | Classify time-travel per workload | TT-0..TT-4 column | Architecture team | Each row has TT class |
| 2.4 | Classify retention per workload | RET-N..RET-4 column | Architecture team | Each row has RET class |
| 2.5 | Trigger-gate map (workload × T1..T8) | `historical-analytics-trigger-map.md` | Architecture team | Multi-select cells justified; no orphan T-values |

## Phase 3 — Priority Ranking

Goal: assign P0-P3 with auditable scoring.

| # | Task | Deliverable | Owner suggestion | Verification |
|---|------|-------------|------------------|--------------|
| 3.1 | Score each workload on 5 axes (0-3) | Score column per row | Architecture team | Sum matches tier boundary (P0 12-15, P1 8-11, P2 4-7, P3 0-3) |
| 3.2 | P0-P3 tier assignment + rationale | Sorted list | Architecture team | Each tier has ≥1 workload; ties broken by commitment axis |
| 3.3 | ADR-735 §2 trigger review: which workloads actually fire T1-T8? | Trigger narrative | Architecture team | First trigger (smallest timeline) named explicitly |
| 3.4 | Reviewer pass (counter-arguments) | Comment thread on PR | Code reviewer | ≥3 challenges raised and resolved |

## Phase 4 — Documentation Publication

Goal: ship the inventory as durable artifact.

| # | Task | Deliverable | Owner suggestion | Verification |
|---|------|-------------|------------------|--------------|
| 4.1 | Publish workload matrix in `docs/03_Technical_Guides/historical-analytics-workloads.md` | Final doc | Doc owner | Doc renders; linked from ADR-735 §5 |
| 4.2 | Publish trigger map in same directory | `historical-analytics-trigger-map.md` | Doc owner | Linked from spec §4.7 |
| 4.3 | Update ADR-735 §5 References with both docs | ADR diff | Architecture team | `git diff` shows the reference add only |
| 4.4 | Close #1342 with summary comment + links | Issue comment | Issue author | #1342 closed; summary contains all doc links |

---

## Cross-cutting

- **Branch**: `feature/1342-historical-analytics-requirements` from `develop`
- **PR target**: `develop`
- **No code**: every PR is docs only. CI lint catches accidental code change.
- **Definition of Done**: all 12 acceptance-criteria checkboxes from spec §5 ticked, reviewer approval, ADR-735 reference updated, #1342 closed.

## Risk and Rollback

- Workload matrix becomes stale within 1 quarter → add a "last reviewed" date; quarterly review in ADR-735 maintenance.
- Latency budget guessed for hypothetical user-facing API → tag as "estimated" in matrix; revisit on first API contract.
- Roadmap commitment misread → PM sign-off required in Phase 3 review.
