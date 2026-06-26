# Spec: Analytics Layer ADR Finalization (#1339)

- Issue: #1339
- Date: 2026-06-23
- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- ADR template: `.claude/rules/adr-conventions.md`

## Goal

Convert ADR-735 from `Status: Proposed` → `Status: Accepted` once 8 sibling investigation issues close with measured evidence. Populate §4 Result/Evidence with numeric measurements, evaluate T1-T8 trigger conditions against today's data, and obtain a second-maintainer review before status flip.

## Non-Goals

- Implementing any analytics platform (PG, ClickHouse, Iceberg, Trino, Spark).
- Modifying Calculator, external-api, synchronizer, or REST controller modules.
- Production deployment, sizing, or capacity decisions.
- Creating new evaluation docs (companion docs already exist or are owned by sibling issues).
- Reopening the Phase 0/1/2/3 escalation order — it is settled by §2 Decision.

## Background

ADR-735 proposes a phased analytics strategy: PG-only default, escalate to ClickHouse → Iceberg+Trino → Iceberg+Spark as measurable triggers fire. The ADR currently has Status `Proposed` because no measured evidence backs the §4 metrics table; numbers are public benchmarks and estimates.

Issue #1339 is the parent finalization ticket. It is blocked by 8 sibling investigation issues that must close with measurable results before the ADR can be accepted. This spec governs only the finalization mechanics — not the investigation work itself.

## Design (ADR Finalization Workflow)

### Phase A — Evidence aggregation

1. Wait for all 8 sibling investigation issues to close (status `Closed`, not `Reopened`).
2. Enumerate the 8 issue numbers explicitly in the working evidence sheet before any ADR edit. If count < 8, the ADR cannot be finalized yet — re-open issue #1339 and surface the missing investigations.
3. For each closed issue, capture: measured numbers, method, timestamp, sample size. Issues that close with narrative but no numeric measurement are treated as unmeasured and block acceptance.
4. Tabulate evidence against the §4 Result/Evidence table slots.

### Phase B — Decision validation

1. Evaluate T1-T8 trigger conditions against current measurements (today's data, not projected).
2. For each trigger, record: fired? (yes/no), measured value, threshold, gap to threshold.
3. Update §2 Decision block with a "Trigger evaluation (2026-MM-DD)" annotation per phase.
4. **If any trigger has already fired today**, the ADR cannot be blanket-Accepted — escalate to revision (status `Revised` → re-circulate). Acceptance is reserved for ADRs whose decision is unchanged by current measurements.

### Phase C — Metrics table population

1. Replace §4 metrics table estimates with measured numbers where available.
2. Where measurement is not yet available, mark cell as `n/a — not deployed` and link the blocking investigation issue.
3. Add an explicit footnote: *"Cells marked `n/a — not deployed` reflect absence of in-environment measurement; estimates from §References are not carried into the Metrics table."*
4. Update §4 Observed Result bullets to cite measured numbers, not benchmarks.

### Phase D — Status transition

1. Update `Status: Proposed` → `Status: Accepted` with `Date:` stamp.
2. Update §5 Summary with measured reaffirmation (no wording change to the principle).
3. Update References section if any new evaluation doc was produced.

### Phase E — Reviewer gate

1. Open PR from `feature/adr-735-accepted` against `develop`.
2. PR body links issue #1339 and lists the 8 sibling issues as prerequisites.
3. Require ≥1 approval from a maintainer other than the author.
4. On approval, merge PR. Status transition is complete.

### Phase F — Rollback path

If any reviewer flags a measurement gap or contradicted evidence:
1. Revert status to `Proposed` (or `Deprecated` if decision is invalidated).
2. Open follow-up issue per blocker.
3. If decision direction is wrong, write superseding ADR (ADR-735a) and add cross-link.

## Acceptance Criteria

- [ ] All 8 sibling investigation issues closed with measured results.
- [ ] ADR-735 §4 Result/Evidence table populated with measured numbers (cells marked `n/a — not deployed` where applicable, no estimates).
- [ ] §2 Decision T1-T8 trigger conditions evaluated against current data with measured values.
- [ ] `Status: Proposed` → `Status: Accepted` with date stamp.
- [ ] §5 Summary unchanged in principle (only measured reaffirmation added).
- [ ] Final recommendation reviewed by ≥1 other maintainer (PR approval).
- [ ] PR merged via `feature/adr-735-accepted` against `develop`.
- [ ] No implementation work — documentation finalization only.

## Trade-offs

| Choice | Get | Give up |
| -- | -- | -- |
| Wait for all 8 sibling issues before flipping status | Clean acceptance gate; no partial evidence | Slowest path; blocks ADR finalization on slowest investigation |
| Allow `n/a — not deployed` cells | Honest representation of what is measured vs not | Readers may misread blanks as zero |
| Single PR with all updates | Atomic review; one approval gate | Large diff; harder to roll back specific sections |
| Reviewer must be a different maintainer | Avoid self-approval | Single-maintainer projects cannot complete this ADR |

### Sensitivity

- **Measurement latency**: each sibling issue's evidence is only as fresh as its measurement date.
- **Trigger threshold interpretation**: T1-T8 thresholds are subjective (e.g., "p95 > 10s") and may need re-statement.
- **Reviewer availability**: gate depends on at least one other maintainer being active.

### Risk

- **Medium**: Single-reviewer bottleneck delays acceptance indefinitely if no second maintainer is available. Mitigation: pre-arrange reviewer in issue #1339 before starting.
- **Low**: §4 table cells marked `n/a` may be misread. Mitigation: explicit column footnote clarifying scope.

### Non-Risk

- Calculator throughput — out of scope, untouched.
- Implementation work — explicitly out of scope by issue #1339's design.

## References

- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- Issue: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1339
- ADR template: `.claude/rules/adr-conventions.md`
- Workflow rules: `.claude/rules/workflow-rules.md` (Definition of Done, branch + PR)
- Related ADRs: ADR-013, ADR-039, ADR-041 (cross-referenced in ADR-735 §References)
