# Plan: Analytics Layer ADR Finalization (#1339)

- Spec: `docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md`
- Issue: #1339
- Parent ADR: `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md`
- ADR template: `.claude/rules/adr-conventions.md`
- Branch base: `develop`
- Feature branch: `feature/adr-735-accepted`

## Phasing

Each phase below is gated on the previous. Phases A-D are mechanical doc edits; Phase E is the reviewer gate; Phase F is the rollback path (only fires on rejection).

---

## Phase 0 — Prerequisites gate

**Task:** Confirm all 8 sibling investigation issues are closed with measured results.
**Files:** none (read-only check).
**Evidence to gather:** `gh issue list --label analytics-investigation --state closed` returns 8 issues. List the 8 issue numbers explicitly in the working evidence sheet. Each closed issue must contain a numeric "Measured" or "Result" line — narrative-only closes do not satisfy this gate.
**Reviewer:** self (gate is mechanical).
**Verification:** count = 8; each issue has measured numbers, not just narrative; if count < 8, halt and reopen #1339.

---

## Phase A — Evidence aggregation

**Task:** Tabulate measured numbers from 8 sibling issues into a working evidence sheet.
**File:** `/tmp/adr-735-evidence.md` (working doc, not committed).
**ADR section to update (later):** §4 Result/Evidence → Metrics table + Observed Result bullets.
**Evidence to gather:** per-issue: metric name, measured value, method, sample size, timestamp.
**Reviewer:** self (aggregation only).
**Verification:** every metric slot in §4 has either a measured value or an `n/a — not deployed` marker.

---

## Phase B — Trigger evaluation annotation

**Task:** Add a "Trigger evaluation (2026-MM-DD)" subsection to ADR-735 §2 Decision, recording measured T1-T8 status.
**File:** `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` §2.
**Evidence to gather:** per trigger T1-T8: fired (yes/no), measured value, threshold, gap.
**Reviewer:** self (composes from working evidence sheet).
**Verification:** all 8 triggers have a one-line entry; entries cite source measurement. **If any trigger fired today**, halt acceptance, mark ADR `Revised`, re-circulate.

---

## Phase C — Metrics table population

**Task:** Replace estimate cells in §4 Metrics table with measured numbers or `n/a — not deployed` markers.
**File:** `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` §4.
**ADR template check:** keep the 5-column table shape (Metric | Today | ClickHouse | Iceberg+Trino | Iceberg+Spark). Add a footnote explaining `n/a` semantics.
**Evidence to gather:** Phase A evidence sheet.
**Reviewer:** self.
**Verification:** no estimate language ("5-30s", "~1.5-2TB", "~5 nodes") remains except as historical baseline annotation; each cell traces to a source. Add footnote citing absence-of-measurement semantics for `n/a` cells.

---

## Phase D — Status transition

**Task:** Update ADR-735 status field + date stamp.
**File:** `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` header.
**Changes:**
- `Status: Proposed` → `Status: Accepted`
- `Date: 2026-06-23` (kept) → add `Date: 2026-MM-DD (Accepted)` line.
**Evidence to gather:** merge commit hash from Phase E.
**Reviewer:** second maintainer (via PR approval in Phase E).
**Verification:** status field reads `Accepted`; date stamp present.

---

## Phase E — Reviewer gate

**Task:** Open PR from `feature/adr-735-accepted` against `develop`, obtain ≥1 approval.
**Commands:**
```bash
git checkout develop && git pull
git checkout -b feature/adr-735-accepted
# (apply edits from Phases B-D)
git add docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md
git commit -m "docs(adr): accept ADR-735 analytics platform evaluation

Closes #1339

Finalizes the analytics platform strategy with measured evidence
from 8 sibling investigation issues."
git push -u origin feature/adr-735-accepted
gh pr create --base develop --title "ADR-735: Accept analytics platform evaluation" --body "Closes #1339. See spec at docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md."
```
**Reviewer assignment:** second maintainer (pre-arranged in issue #1339 comment before starting).
**Fallback:** if no reviewer responds within 14 days, apply `needs-reviewer` label, ping #architecture channel, do not self-approve.
**Verification:** PR has ≥1 approval from non-author maintainer; CI passes (docs-only).

---

## Phase F — Rollback path (conditional)

**Trigger:** reviewer rejects, measurement gap surfaces, or decision is contradicted by new evidence.
**Steps:**
1. Close PR without merging.
2. Revert ADR-735 status to `Proposed` (or `Deprecated` if decision is invalidated).
3. Open follow-up issue per blocker with measured gap as title.
4. If decision direction is wrong, author `docs/01_ADR/ADR-735a-{topic}.md` as superseding ADR and add cross-link to ADR-735 §References.
**Reviewer:** self.
**Verification:** issue #1339 reopened with reason; status field reverted; superseding ADR committed if applicable.

---

## Per-task summary table

| Task | File / Section | Evidence source | Reviewer | Verification |
| -- | -- | -- | -- | -- |
| Phase 0 prerequisites | none (read-only) | 8 sibling issues closed | self | count = 8 |
| Phase A evidence sheet | `/tmp/adr-735-evidence.md` | 8 sibling issue comments | self | every slot covered |
| Phase B trigger annotation | ADR-735 §2 | evidence sheet | self | T1-T8 all annotated |
| Phase C metrics table | ADR-735 §4 | evidence sheet | self | no estimate language |
| Phase D status flip | ADR-735 header | merge commit | second maintainer | field reads `Accepted` |
| Phase E PR + approval | git + gh | review comment | second maintainer | ≥1 approval, CI green |
| Phase F rollback | ADR-735 status | rejection reason | self | status reverted, issue reopened |

---

## Definition of Done

- [ ] ADR template per `.claude/rules/adr-conventions.md` strictly followed (5 sections, Trade-offs include Sensitivity/Trade-off/Risk/Non-Risk).
- [ ] §4 metrics table cells: measured numbers OR `n/a — not deployed`.
- [ ] §2 trigger annotation present with T1-T8 entries.
- [ ] Status `Accepted` with date stamp.
- [ ] PR merged to `develop` with ≥1 non-author approval.
- [ ] No code changes; documentation only.
- [ ] Issue #1339 closed with merge commit hash in closing comment.
