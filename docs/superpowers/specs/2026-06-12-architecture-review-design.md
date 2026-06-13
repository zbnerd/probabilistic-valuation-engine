# Architecture Review Design — 2026-06-12

- Date: 2026-06-12
- Status: draft (pending user review)
- Owner: solo dev
- Reviewer lens: Principal Data Engineer / Platform Architect / SRE

## Goal

Define issues in the current data platform via a 15-question architectural review.
Output feeds GitHub Issues for tracking.

Out of scope: implementation work, code changes. Review = evidence gathering + design.

## Scope

3 phases, 3 spec files + 1 spec per Critical/High finding.

| Phase | Spec | Sections | Theme |
|-------|------|----------|-------|
| 1 | `2026-06-12-arch-review-phase1-control-data-plane.md` | 1-7 | Control plane, Airflow, Kafka, MinIO, Replay, Sync bottleneck, Urgent path |
| 2 | `2026-06-12-arch-review-phase2-ops-evolution.md` | 8-15 | Multi-node, Observability, Backfill, Cost, Security, Tech debt, SPOF, Spark/Flink path |
| 3 | `2026-06-12-arch-review-phase3-summary-maturity.md` | 7 summary items + maturity | TOP 5s, blind spots, maturity level, min-change to L4 |

Critical/High findings: 1 spec file each at `2026-06-12-finding-<slug>.md`.
Medium/Low: inline in phase spec only, no separate file, no issue.

## Deliverable structure (Hybrid)

```
docs/superpowers/specs/
├── 2026-06-12-arch-review-phase1-control-data-plane.md
├── 2026-06-12-arch-review-phase2-ops-evolution.md
├── 2026-06-12-arch-review-phase3-summary-maturity.md
└── 2026-06-12-finding-<slug>.md × N   (Critical/High only, ~5-10 files)
```

## Per-phase spec structure

```markdown
# Architecture Review — Phase N: <title>

- Date: 2026-06-12
- Phase: N/3 (sections X-Y)
- Reviewer lens: ...
- Status: draft | under-review | approved
- Source-of-truth: targeted code dive + prior zoom-out context

## Evidence base
- Module code paths read
- Kafka / MinIO / PostgreSQL state inspected
- Airflow DAGs read

## Section X: <title>
### Finding X.Y
- Severity: Critical | High | Medium | Low
- Why: 1-2 sentences
- Scenario: concrete failure case
- Fix: concrete change with file:line
- When: now | quarter | later | YAGNI
- Evidence: file:line refs
- → see `finding-<slug>.md` (Critical/High only)

(Medium/Low: 5-10 lines inline, no separate spec)

## Mermaid diagram
<at least 1 per phase: current state + proposed change>
```

## Per-finding spec structure (Critical/High only)

```markdown
# Finding: <slug>

- Severity: Critical | High
- Source phase: 1 | 2 | 3
- Date: 2026-06-12
- Owner: TBD
- Status: open | accepted | deferred

## Problem
2-3 paragraphs, concrete

## Evidence
- file:line — what's wrong
- metric/log/config — observed value

## Failure scenario
step-by-step

## Fix options
1. Option A — effort S/M/L, risk S/M/L
2. Option B
3. Option C

## Recommendation
which + why

## Solo-dev cost-benefit
worth doing now?
```

## Investigation protocol

### Per section: 1-3 falsifiable claims before reading

Example: "Synchronizer is single-threaded" → hypothesis → confirm/refute via `KafkaResultChunkConsumer.kt`, `application.yml`, observed metrics.

### Evidence types (priority)

| Type | Example | Weight |
|------|---------|--------|
| Code | `module-synchronizer/.../Consumer.kt:42` shows `concurrency=1` | Strongest |
| Config | `application.yml` setting | Strong |
| Metric/log | observed value | Strong |
| Doc | project doc claim | Medium |
| Inference | "probably..." | Weak — demoted to Low or dropped |

Critical/High = ≥1 strong evidence. Inference-only → Low or dropped.

### Time budget

| Complexity | Time | Lines read |
|------------|------|-----------|
| Single file | 5-10 min | 100-300 |
| Multi-file | 15-20 min | 300-800 |
| Open-ended | 25-30 min | sweep + targeted |

Hard cap: 30 min/section. Beyond cap = "needs deeper investigation" issue, not guess.

### Contradiction handling

Code contradicts doc → code wins, contradiction becomes its own finding ("stale docs").
Metric contradicts code → both findings, "but observed says otherwise, check X".

## Issue conversion

After all 3 phases done + committed, invoke `to-issues` skill:
- 1 GitHub issue per Critical/High spec
- Title: `[arch-review][Critical] <slug>` or `[arch-review][High] <slug>`
- Body: spec summary + spec file link
- Labels: per `docs/agents/triage-labels.md` (severity / area / type)
- Status updates in issue, not spec

Medium/Low: no issue, no separate spec. Inline in phase spec only.
Phase 3 (7 summaries + maturity): no issues, meta-summary only.

## Quality gates

Self-review per spec, before commit:

| Check | Pass |
|-------|------|
| No placeholders | no TBD/TODO/<fill> |
| No contradictions | sections don't say opposite of each other |
| Evidence ≥1 strong | every Critical/High has file:line or metric |
| Severity calibrated | Critical = data loss/outage/security. High = perf >2x or major debt |
| Fix concrete | "modify file:line to do X" not "improve" |
| When honest | now = blocks user. quarter = important. later = known. YAGNI = drop |
| Solo-dev sized | M = 1-2 days, L = 1+ week. L needs justification |
| Cites existing ADR | contradicts ADR-NNN → surface explicitly |
| Mermaid diagram | ≥1 per phase spec |
| No generic advice | "add monitoring" → where, what, what threshold |

## Phase done criteria

1. All sections in phase have findings or explicit "no issue found"
2. Self-review passes
3. User reviews + approves
4. Spec committed to git
5. Critical/High findings have dedicated spec files
6. Critical/High spec files also pass self-review

## Whole review done criteria

All 3 phases done + Phase 3 spec has:
- TOP 5 strengths (evidence-backed)
- TOP 5 risks (mitigation status)
- TOP 5 over-engineering for solo dev
- TOP 5 quick wins (effort estimate)
- TOP 5 1-year-regret items
- "Over-worrying" list
- Blind spots
- Maturity level (1-5) with evidence
- Min change to L4 (3-5 items max)

## Anti-patterns (will not do)

- ❌ Implementation code in this review (writing-plans job, later)
- ❌ GitHub issues before all phases done (premature)
- ❌ Inference-only Critical/High
- ❌ Skipping self-review
- ❌ Inflating Critical count for importance
- ❌ Generic advice without specifics

## Next step

After this design is approved, invoke writing-plans to plan the 3-phase review execution,
then begin Phase 1 dive.
