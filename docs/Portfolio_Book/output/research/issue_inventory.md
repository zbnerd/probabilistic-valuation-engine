# Issue Inventory — GitHub Evidence Audit

## Scope and method

- Repository: `zbnerd/probabilistic-valuation-engine`
- Original retrieval (UTC): 2026-08-01T05:40:48Z.
- Live reconciliation (UTC): 2026-08-08. Authenticated GitHub REST issue enumeration with pagination, Search `is:issue`, and GraphQL `repository.issues.totalCount` still each returned 752; no issue record was added after the original retrieval.
- Counts: 752 unique issue numbers; duplicate count 0; all 752 closed and 0 open at live reconciliation.
- Detail source: authenticated GitHub GraphQL `repository.issues`, ordered by `CREATED_AT` ascending. Each record includes issue metadata/body, comment count and sample, plus GitHub `closedByPullRequestsReferences` (PR state/merge metadata) where available.
- Privacy: email-like strings in summaries are redacted; no credentials or private contact details are reproduced.

<!-- ISSUE_CROSSREF_SUMMARY_START -->
## Repository cross-reference audit

- Local reachability scan: 2,396 unique commits from `git rev-list --all`; 369 issues have 1,149 unique commit-message references.
- Available PR inventory scan: 710 unique PR records; 311 issues have 428 unique textual PR references.
- Issue-record text scan: 127 issues contain 129 non-formal PR-number mentions that resolve to available PR records.
- Union: 398 issues have at least one repository cross-reference; 354 have none. Formal issue-to-PR links remain separate (6 links in issue records).
- Match rules: explicit `#N`, labeled `Issue N`/`issue-N`/`Issues: N`/`이슈 N`, numeric conventional-commit scope such as `fix(1019):`, and a four-or-more-digit trailing title tag such as `(1423)`. Matches are deduplicated per commit/PR.
- Safeguard: a textual reference is navigation evidence only. It is not promoted to GitHub closed-by semantics and does not by itself prove root cause, runtime effect, or completion of every acceptance criterion.
<!-- ISSUE_CROSSREF_SUMMARY_END -->

## Interpretation safeguards

- A closed issue is not treated as a code resolution. Only a linked PR with a non-null `mergedAt` is evidence of merged code. If no such link is returned, the resolution is explicitly unverified.
- Root cause is labeled “reported” unless the linked merged PR/file evidence or the issue’s own evidence verifies it.
- Textual references to issues from commits/PRs are reported separately as repository cross-references and are not promoted to formal links; formal linkage is only GitHub’s closed-by-PR relation.

## Records

<!-- ISSUE_RECORDS -->
### Issue #1380 — [#1341] Index experiment I3 — Covering index for Top-N
- author zbnerd; CLOSED; created 2026-06-23T06:33:31Z; closed 2026-06-23T06:46:09Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.2 I3 Plan: Task 2.5 CREATE INDEX idx_cover_world_class_cost ON c…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1381 — [#1341] Index experiment I4 — RANGE partitioning by calculated_at
- author zbnerd; CLOSED; created 2026-06-23T06:33:34Z; closed 2026-06-23T06:46:11Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.2 I4 Plan: Task 2.6 Recreate character_valuation_views as PARTIT…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1382 — [#1341] Build materialized view candidates (hourly/daily/weekly)
- author zbnerd; CLOSED; created 2026-06-23T06:33:37Z; closed 2026-06-23T06:46:12Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.3 Plan: Task 3.1 Create mv_topn_hourly (UNIQUE INDEX (world_name…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1383 — [#1341] Materialized view refresh benchmark (CONCURRENTLY vs full)
- author zbnerd; CLOSED; created 2026-06-23T06:33:40Z; closed 2026-06-23T06:46:14Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.3 Plan: Task 3.2 At 100M, 500M, 1B rows: REFRESH MATERIALIZED VI…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1384 — [#1341] Materialized view refresh strategy recommendation
- author zbnerd; CLOSED; created 2026-06-23T06:33:43Z; closed 2026-06-23T06:46:17Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.3 Plan: Task 3.3 Document per-pattern cadence mapping (hourly/da…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1385 — [#1341] Storage projection at 30/90/365-day retention
- author zbnerd; CLOSED; created 2026-06-23T06:33:51Z; closed 2026-06-23T06:46:19Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.4 Plan: Task 4.1 Compute heap, index, WAL/FMV for 30/90/365-day …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1386 — [#1341] p95 vs row count scaling benchmark
- author zbnerd; CLOSED; created 2026-06-23T06:33:54Z; closed 2026-06-23T06:46:21Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.5 Plan: Task 4.2 Reuse q1-q5 from Phase 2. Run at 100M, 250M, 50…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1387 — [#1341] T1 trigger crossover analysis
- author zbnerd; CLOSED; created 2026-06-23T06:33:58Z; closed 2026-06-23T06:46:23Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.5 Plan: Task 4.3 From phase4/scaling.csv, find row_count where p…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1388 — [#1341] T2 DB CPU share measurement during peak hour
- author zbnerd; CLOSED; created 2026-06-23T06:34:01Z; closed 2026-06-23T06:46:25Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.5 Plan: Task 4.4 Sample pg_stat_activity + pg_stat_database ever…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1389 — [#1341] Publish ADR-1341 findings (PG scalability assessment)
- author zbnerd; CLOSED; created 2026-06-23T06:34:04Z; closed 2026-06-23T06:46:27Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.6 Plan: Task 5.1 Write docs/01_ADR/ADR-1341-pg-scalability-findi…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1390 — [#1341] Cross-link ADR-735 to ADR-1341 findings
- author zbnerd; CLOSED; created 2026-06-23T06:34:07Z; closed 2026-06-23T06:46:29Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.6 Plan: Task 5.2 Add link from ADR-735 §4 Evidence to ADR-1341 f…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1391 — [Investigation] Class Hierarchy - Taxonomy Enumeration (Phase 1)
- author zbnerd; CLOSED; created 2026-06-23T06:34:22Z; closed 2026-06-23T06:46:31Z; reason COMPLETED. Body: Parent: #1343 Plan: docs/superpowers/plans/2026-06-23-class-hierarchy-modeling.md Spec: docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1392 — [Investigation] Class Hierarchy - Current PG Audit (Phase 2)
- author zbnerd; CLOSED; created 2026-06-23T06:34:29Z; closed 2026-06-23T06:46:33Z; reason COMPLETED. Body: Parent: #1343 Plan: docs/superpowers/plans/2026-06-23-class-hierarchy-modeling.md Spec: docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1393 — [Investigation] Class Hierarchy - Dimensional Model Design (Phase 3)
- author zbnerd; CLOSED; created 2026-06-23T06:34:32Z; closed 2026-06-23T06:46:34Z; reason COMPLETED. Body: Parent: #1343 Plan: docs/superpowers/plans/2026-06-23-class-hierarchy-modeling.md Spec: docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1394 — [Investigation] Class Hierarchy - Cross-Store Mapping (Phase 4)
- author zbnerd; CLOSED; created 2026-06-23T06:34:34Z; closed 2026-06-23T06:46:36Z; reason COMPLETED. Body: Parent: #1343 Plan: docs/superpowers/plans/2026-06-23-class-hierarchy-modeling.md Spec: docs/superpowers/specs/2026-06-23-class-hierarchy-modeling.md …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1395 — [Architectural Design] Document analytics consumer matrix (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:34:50Z; closed 2026-06-23T06:46:38Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 1.1. Write `docs/03_Technical_Guides/analytics-consumer-matrix.md` with…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1396 — [Architectural Design] Document analytics data flow decision (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:34:52Z; closed 2026-06-23T06:46:41Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 1.2. Write `docs/03_Technical_Guides/analytics-data-flow.md` capturing …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1397 — [Architectural Design] Define AnalyticsQueryPort interface in module-core (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:34:54Z; closed 2026-06-23T06:46:42Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 2.1. Create `module-core/.../core/port/out/analytics/AnalyticsQueryPort…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1398 — [Architectural Design] Define IcebergSnapshotPort interface in module-core (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:34:57Z; closed 2026-06-23T06:46:44Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 2.2. Create `module-core/.../core/port/out/analytics/IcebergSnapshotPor…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1399 — [Architectural Design] Extend ArchUnit rule to forbid engine packages (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:34:59Z; closed 2026-06-23T06:46:46Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 2.3. Extend `module-app/src/test/java/maple/expectation/architecture/Ar…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1400 — [Architectural Design] Create conditional analytics adapter stubs (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:35:01Z; closed 2026-06-23T06:46:48Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 3.1. Create 3 conditional stub adapters in `module-infra/.../adapter/ou…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1401 — [Architectural Design] Document analytics failure isolation matrix (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:35:06Z; closed 2026-06-23T06:46:49Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 4.1. Write `docs/03_Technical_Guides/analytics-failure-isolation.md` wi…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1402 — [Architectural Design] Update ADR-735 references and cross-links (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:35:10Z; closed 2026-06-23T06:46:51Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 4.2 + 4.3. Append references (`analytics-consumer-matrix.md`, `analytic…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1403 — [Architectural Design] Verify analytics config defaults to disabled (#1344)
- author zbnerd; CLOSED; created 2026-06-23T06:35:24Z; closed 2026-06-23T06:46:53Z; reason COMPLETED. Body: Part of #1344 (Serving Layer vs Analytics Layer Separation). ## Task Plan Task 3.2. Ensure all `application*.yml` profiles document `analytics.engine:…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1404 — [MinIO-Compat/T0] Preflight + test bucket bootstrap
- author zbnerd; CLOSED; created 2026-06-23T06:36:59Z; closed 2026-06-23T06:46:54Z; reason COMPLETED. Body: Parent: #1338. Plan task T0.1-T0.3 in docs/superpowers/plans/2026-06-23-minio-compatibility.md. Confirm MinIO live, create `maple-iceberg-test` bucket…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1405 — [MinIO-Compat/T1] Capture MinIO version + bucket config
- author zbnerd; CLOSED; created 2026-06-23T06:37:02Z; closed 2026-06-23T06:46:54Z; reason COMPLETED. Body: Parent: #1338. Plan task T1.1-T1.2. Run `mc admin info` to capture server version + release date; document current bucket configuration (versioning, l…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1406 — [MinIO-Compat/T2] Tier 1 S3 API baseline smoke
- author zbnerd; CLOSED; created 2026-06-23T06:37:16Z; closed 2026-06-23T06:46:54Z; reason COMPLETED. Body: Parent: #1338. Plan task T2.1-T2.2. Test ListObjectsV2, GetObject (range), PutObject, CopyObject, DeleteObjects (batch), HeadObject, HeadBucket, plus …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1407 — [MinIO-Compat/T3] Conditional writes + versioning
- author zbnerd; CLOSED; created 2026-06-23T06:37:18Z; closed 2026-06-23T06:46:55Z; reason COMPLETED. Body: Parent: #1338. Plan task T3.1-T3.4. Enable versioning on test bucket, verify If-Match and If-None-Match:*, multipart copy. Required by Iceberg retry p…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1408 — [MinIO-Compat/T4] Lifecycle + SA policy review
- author zbnerd; CLOSED; created 2026-06-23T06:37:33Z; closed 2026-06-23T06:46:55Z; reason COMPLETED. Body: Parent: #1338. Plan task T4.1-T4.2. Capture ILM for data/runs/{runId}/... prefix; review per-SA policies (ext-api, calculator, synchronizer, cleanup) …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1409 — [MinIO-Compat/T5.1] Iceberg smoke (PyIceberg + Hadoop catalog)
- author zbnerd; CLOSED; created 2026-06-23T06:37:34Z; closed 2026-06-23T06:46:55Z; reason COMPLETED. Body: Parent: #1338. Plan task T5.1. PyIceberg 0.9.0 + Hadoop catalog against s3://maple-iceberg-test/. Verify metadata.json conditional PUT semantics. REST…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1410 — [MinIO-Compat/T5.2] Trino Hive connector smoke
- author zbnerd; CLOSED; created 2026-06-23T06:38:06Z; closed 2026-06-23T06:46:56Z; reason COMPLETED. Body: Parent: #1338. Plan task T5.2. Trino 435 + Hive connector pointed at s3://maple-iceberg-test/. CREATE SCHEMA/TABLE + INSERT/SELECT roundtrip.
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1411 — [MinIO-Compat/T5.3] Spark S3A smoke (pinned Hadoop-AWS)
- author zbnerd; CLOSED; created 2026-06-23T06:38:08Z; closed 2026-06-23T06:46:56Z; reason COMPLETED. Body: Parent: #1338. Plan task T5.3. Spark 3.5.1 + hadoop-aws-3.3.4 + aws-java-sdk-bundle-1.12.262 (pinned). Verify parquet read/write via s3a://.
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1412 — [MinIO-Compat/T5.4] ClickHouse S3 disk smoke
- author zbnerd; CLOSED; created 2026-06-23T06:38:09Z; closed 2026-06-23T06:46:56Z; reason COMPLETED. Body: Parent: #1338. Plan task T5.4. ClickHouse 24.x LTS with S3PlainRewritable disk; INSERT/SELECT roundtrip + If-None-Match:* on new metadata.
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1413 — [MinIO-Compat/T6] Compatibility matrix + gap issues
- author zbnerd; CLOSED; created 2026-06-23T06:38:12Z; closed 2026-06-23T06:46:57Z; reason COMPLETED. Body: Parent: #1338. Plan task T6.1-T6.2. Publish docs/03_Technical_Guides/minio-compatibility-report.md with per-engine × per-API matrix; open gap issues f…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1414 — [Iceberg Pilot] Phase 1 — MinIO version audit + test bucket
- author zbnerd; CLOSED; created 2026-06-23T06:39:35Z; closed 2026-06-23T06:46:57Z; reason COMPLETED. Body: Part of #1337 ## Description Verify MinIO RELEASE ≥ 2024-05-10 in cluster (mandatory for If-Match conditional writes); create dedicated `iceberg-pilot…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1415 — [Iceberg Pilot] Phase 2 — Polaris/Nessie catalog deployment
- author zbnerd; CLOSED; created 2026-06-23T06:39:36Z; closed 2026-06-23T06:46:57Z; reason COMPLETED. Body: Part of #1337 ## Description Author `tools/iceberg-pilot/docker-compose.test.yml` with Polaris (primary) and Nessie (fallback) profiles. Deploy Polari…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1416 — [Iceberg Pilot] Phase 3 — Test table + chunk ingest (PyIceberg)
- author zbnerd; CLOSED; created 2026-06-23T06:39:38Z; closed 2026-06-23T06:46:58Z; reason COMPLETED. Body: Part of #1337 ## Description Author PyIceberg writer script; create test table `landing.character_basic_pilot` with partitioning `days(ingest_ts), buc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1417 — [Iceberg Pilot] Phase 4 — Compaction validation + crash recovery
- author zbnerd; CLOSED; created 2026-06-23T06:39:40Z; closed 2026-06-23T06:46:58Z; reason COMPLETED. Body: Part of #1337 ## Description Run nightly `rewrite-data-files` 3 times; measure Parquet file size distribution (target median ≥128MB, ADR-735 target 25…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1418 — [Iceberg Pilot] Phase 5 — Snapshot retention validation (7d raw / 365d serving)
- author zbnerd; CLOSED; created 2026-06-23T06:40:15Z; closed 2026-06-23T06:46:58Z; reason COMPLETED. Body: Part of #1337 ## Description Validate snapshot retention policy: 7-day rolling for raw `landing.*` snapshots via `expire_snapshots` with `retain_last=…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1419 — [Iceberg Pilot] Phase 6 — Time-travel + Trino cross-engine read
- author zbnerd; CLOSED; created 2026-06-23T06:40:16Z; closed 2026-06-23T06:46:59Z; reason COMPLETED. Body: Part of #1337 ## Description Validate time-travel queries via `FOR SYSTEM_TIME AS OF` (7-day scan ≤30s) and cross-engine interop (Trino reads Polaris-…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1420 — [Iceberg Pilot] Phase 7 — Manifest list monitoring (7 days) + alarm threshold
- author zbnerd; CLOSED; created 2026-06-23T06:40:18Z; closed 2026-06-23T06:46:59Z; reason COMPLETED. Body: Part of #1337 ## Description Track manifest list size daily for 7 consecutive days. Author `tools/iceberg-pilot/measure_manifest.sh` and cron schedule…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1421 — [Iceberg Pilot] Phase 8 — Companion doc + recommendation + teardown
- author zbnerd; CLOSED; created 2026-06-23T06:40:20Z; closed 2026-06-23T06:47:00Z; reason COMPLETED. Body: Part of #1337 ## Description Author `docs/03_Technical_Guides/iceberg-evaluation.md` covering AC1–AC12 with cost/effort/FTE/migration-effort sections.…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1423 — [Spike] Evaluate Parquet + ZSTD artifact format (PoC for OCID mapping)
- author zbnerd; CLOSED; created 2026-06-24T04:46:52Z; closed 2026-06-28T14:03:46Z; reason COMPLETED. Body: ## Background Current artifact format is gzip-compressed JSONL streaming across all 4 modules: ``` External API → JSONL.gz snapshot chunk → MinIO → Ka…
- discussion: 1 / Closed via PR #1447 (merged into develop). Parquet PoC complete; recommendation:…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1447/MERGED]; reachable commit-message refs 9 [060c56da3ca2, 39dad8c2dc8a, 3c9d05adef87, 4d96bb090e60, 5858c251ad2b, b18292d2dc36, c40deb95513a, c6c2fbdfb5d6, f821e8c9ccbb]; PR-record issue refs 1 [#1447/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1424 — [Spike] Parquet + ZSTD PoC for OCID mapping artifact
- author zbnerd; CLOSED; created 2026-06-24T04:57:39Z; closed 2026-06-28T14:03:48Z; reason COMPLETED. Body: ## Motivation Current artifact format is gzip-compressed JSONL streaming across all 4 modules. Daily volume: - 297 GB compressed snapshot (3.45 TB unc…
- discussion: 1 / Duplicate of #1423; closed together via PR #1447. Same PoC and benchmark report:…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1447/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1425 — Artifact schema formalization (Parquet/Avro/Protobuf decision)
- author zbnerd; CLOSED; created 2026-06-24T04:57:42Z; closed 2026-06-28T13:00:43Z; reason COMPLETED. Body: ## Motivation Today, artifact schemas live implicitly in Kotlin data classes: - `SnapshotChunkRecord` (`module-external-api/.../snapshot/SnapshotChunk…
- discussion: 1 / Closed via PR #1446. Schema files added; no migration in this PR.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1446/MERGED]; reachable commit-message refs 4 [0a93b54f16eb, 353517983026, 385294f792c5, 5858c251ad2b]; PR-record issue refs 1 [#1446/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1426 — Iceberg readiness assessment (post-Parquet prerequisite)
- author zbnerd; CLOSED; created 2026-06-24T04:58:43Z; closed 2026-06-28T14:12:55Z; reason COMPLETED. Body: ## Motivation Long-term analytics vision (per ADR-735 and `docs/superpowers/plans/2026-06-23-iceberg-feasibility.md`) includes Iceberg adoption for SQ…
- discussion: 1 / Closed via PR #1449. Readiness assessment complete; actual adoption gated on ADR…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1449/MERGED]; reachable commit-message refs 1 [3c9d05adef87]; PR-record issue refs 1 [#1449/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1427 — Investigate small-file problem (120K files/day)
- author zbnerd; CLOSED; created 2026-06-24T04:58:51Z; closed 2026-06-28T14:10:26Z; reason COMPLETED. Body: ## Motivation Pipeline writes ~120,000 small gzip JSONL files per day: - Snapshot: 500 records/file (avg) - Result: 600 records/file (avg) Total daily…
- discussion: 1 / Closed via PR #1448. Investigation complete: docs/02_Investigations/2026-06-28-s…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1448/MERGED]; reachable commit-message refs 2 [4d96bb090e60, ee9cb9671dc4]; PR-record issue refs 1 [#1448/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1428 — 4 active 모듈 nohup → docker compose 배포 전환
- author zbnerd; CLOSED; created 2026-06-24T09:05:37Z; closed 2026-06-26T00:17:12Z; reason COMPLETED. Body: ## What to build 현재 `external-api` / `calculator` / `synchronizer` / `cleanup` 4 active 모듈은 nohup 호스트 프로세스로 운영 중. 이미지는 빌드됨(`maple/{module}:sha-75cb631…
- discussion: 1 / Done via #1434 (merged). 4 modules deployed via docker compose: external-api/cal…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1434/MERGED]; reachable commit-message refs 5 [3dcb5b69db62, 93e2e229a84c, b395132ebe79, d71a6589050b, ec8605d5caca]; PR-record issue refs 3 [#1434/MERGED, #1436/MERGED, #1438/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1429 — autoheal 컨테이너 활성화 + 서비스 autoheal=true 라벨링
- author zbnerd; CLOSED; created 2026-06-24T09:06:00Z; closed 2026-06-26T00:17:17Z; reason COMPLETED. Body: ## What to build `docker-compose.yml`에 `autoheal`(willfarrell/autoheal)이 정의돼 있으나 미실행. docker 운영으로 전환(#1428)한 후 unhealthy 컨테이너 자동 복구 활성화. 대상: 4 active …
- discussion: 1 / Done (app scope) via #1434. autoheal running; induced-unhealthy test passed (thr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1434/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#1438/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1430 — cadvisor 활성화로 컨테이너 리소스 메트릭 확보
- author zbnerd; CLOSED; created 2026-06-24T09:06:06Z; closed 2026-06-26T00:17:13Z; reason COMPLETED. Body: ## What to build `docker-compose.yml`에 `cadvisor`(gcr.io/cadvisor/cadvisor)가 정의돼 있으나 미실행. docker 운영으로 전환(#1428) 후 컨테이너별 CPU/메모리/네트워크 I/O 메트릭을 Promethe…
- discussion: 1 / Done via #1434. cadvisor running; prometheus scrapes container_cpu_usage_seconds…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1434/MERGED]; reachable commit-message refs 1 [3dcb5b69db62]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1431 — nohup → docker 배포 전환 runbook 문서화
- author zbnerd; CLOSED; created 2026-06-24T09:06:10Z; closed 2026-06-26T00:17:15Z; reason COMPLETED. Body: ## What to build 4 active 모듈 nohup → docker compose 배포 전환(#1428) 절차를 운영 문서로 정리. long-run test 이후 반복 전환/rollback 시 참조용. 대상 위치: `docs/21_Operations/` 또는…
- discussion: 1 / Done via #1434. Runbook: docs/21_Operations/docker-deploy-runbook.md — forward/r…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1434/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1435 — airflow autoheal 활성화 (host/bridge network reconcile 선행)
- author zbnerd; CLOSED; created 2026-06-26T00:16:36Z; closed 2026-06-26T01:06:13Z; reason COMPLETED. Body: ## Background #1429 의 airflow 부분. airflow-webserver/scheduler 가 compose 에서는 bridge(`maple-network`) 선언이나 실제로는 host network 로 구동 중(docker inspect 확인). …
- discussion: 1 / Done via #1437 (merged). airflow DB reach repaired (airflow-db 5433:5432 publish…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1437/MERGED]; reachable commit-message refs 7 [2022abcf89d2, 5144b83542fd, 56f288bfca22, 60e6134d8b2c, ec8605d5caca, f036dda8177e, fe319aad4a0a]; PR-record issue refs 1 [#1437/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1209 — refactor(sync): verify OcidLookupRunConsumer defaultAsyncExecutor reuse
- author zbnerd; CLOSED; created 2026-06-08T12:57:34Z; closed 2026-06-08T13:57:41Z; reason COMPLETED. Body: OcidLookupRunConsumer 가 @Qualifier("defaultAsyncExecutor") 를 재사용. OcidLookup topic 의 처리량 (low volume) 에 적합. 후속 volume 증가 시 전용 executor (ocidLookupRunE…
- discussion: 1 / 검증 완료 (no PR, comment only). defaultAsyncExecutor (CoreExecutorConfig.kt): Threa…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1216 — VS1: ObjectStorage interface + Local + MinIO adapters + docker-compose + .env
- author zbnerd; CLOSED; created 2026-06-09T07:16:14Z; closed 2026-06-09T10:22:32Z; reason COMPLETED. Body: ## What to build Introduce a single `ObjectStorage` interface in `module-common` that replaces the three local filesystem port interfaces scattered ac…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [38e6cbc3be14, 4129b7fc01e9, 64727071096f, a67ca5d1fe3c, aa4a8691859a]; PR-record issue refs 1 [#1222/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1217 — VS2: Pipeline modules migration — calculator + external-api + synchronizer + snapshot wrap…
- author zbnerd; CLOSED; created 2026-06-09T07:16:59Z; closed 2026-06-09T14:40:51Z; reason COMPLETED. Body: ## What to build Migrate the four application modules to use the unified `ObjectStorage` interface (added in VS1). After this slice, every storage cal…
- discussion: 1 / VS2 migration completed via local develop + remote develop sync. All 4 applicati…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 21 [167bf8436b8b, 1c54202ac951, 254441e6588a, 2ac3aa869844, 350d79f01e53, 38e6cbc3be14, 3bc6708d0641, 3cd52b9dc616, 3d17a68a0eba, 5460b05ca7e6, 9720aad98814, 99595b599ef2, 9fbea109fc4b, c8ae2038fb70, da10ea4e4e7b, e02b14758199, e1676e36abb5, eca64d5cd6a1, ef551a76e506, ef5a0bd6055d, fe71dcb3795f]; PR-record issue refs 2 [#1223/MERGED, #1231/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1218 — VS3: Dev e2e MinIO validation
- author zbnerd; CLOSED; created 2026-06-09T07:17:25Z; closed 2026-06-10T05:10:31Z; reason COMPLETED. Body: ## What to build Run the full pipeline against MinIO in the dev environment. This slice has no code change — it is the manual gate that proves VS1 + V…
- discussion: 3 / VS3 dev cutover tooling complete. Partial L3 validation produced (smoke could no…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [38e6cbc3be14, 79fcbdf75fa8, da10ea4e4e7b]; PR-record issue refs 1 [#1224/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1219 — VS4: Production atomic cutover runbook
- author zbnerd; CLOSED; created 2026-06-09T07:17:56Z; closed 2026-06-12T00:47:40Z; reason COMPLETED. Body: ## What to build Author and execute the production atomic cutover. The runbook is a living document; the cutover itself is a human-driven maintenance-…
- discussion: 1 / VS4 (prod cutover) is human-driven maintenance window work. Closing here; cutove…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [38e6cbc3be14, da10ea4e4e7b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1220 — VS5: Default minio in production yaml + deprecate 2 ports + ADR-720
- author zbnerd; CLOSED; created 2026-06-09T07:18:12Z; closed 2026-06-12T00:50:16Z; reason COMPLETED. Body: ## What to build Flip the production default backend to MinIO and lock in the architectural decision with ADR-720. After this slice, `storage.backend=…
- discussion: 1 / VS5 mostly already done. `ExternalApiArtifactStorePort` deleted in #1230 (caad95…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1230/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1221 — VS6: Remove deprecated ports + Local adapter cleanup
- author zbnerd; CLOSED; created 2026-06-09T07:18:36Z; closed 2026-06-12T00:50:18Z; reason COMPLETED. Body: ## What to build Delete the two deprecated port interfaces and their local filesystem adapters now that the MinIO default has been stable in productio…
- discussion: 1 / VS6 work already done. `LocalExternalApiArtifactStoreAdapter` deleted in #1228 (…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1228/MERGED]; reachable commit-message refs 4 [13b6ebc0b717, 38e6cbc3be14, 97a4bce300e9, 99595b599ef2]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1236 — infra: remove dead V4 application/service duplicates from module-app + delete TraceAspect
- author zbnerd; CLOSED; created 2026-06-11T15:32:13Z; closed 2026-06-14T15:59:50Z; reason COMPLETED. Body: ## What to build Delete two categories of dead source that block module-app removal: **A) TraceAspect in module-infra (no-op pointcut).** The pointcut…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [74725906005c]; PR-record issue refs 1 [#1241/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1237 — rest-controller: migrate live web components (MDCFilter, validators, CORS, mapper, DTOs) f…
- author zbnerd; CLOSED; created 2026-06-11T15:32:29Z; closed 2026-06-14T15:59:50Z; reason COMPLETED. Body: ## What to build Migrate every component in module-web that has no 1:1 replacement in module-rest-controller or module-auth. The v6 read path runs fro…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [74725906005c]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1238 — chore: delete module-chaos-test (Issue #207 alignment)
- author zbnerd; CLOSED; created 2026-06-11T15:32:32Z; closed 2026-06-14T15:59:51Z; reason COMPLETED. Body: ## What to build Delete module-chaos-test. The 22 Testcontainers-based chaos tests in this module violate workflow-rules.md §10 ("통합테스트 금지 - Issue #20…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#20/MERGED]; reachable commit-message refs 1 [74725906005c]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1239 — ops: prod deploy target swap to module-rest-controller (gradle.yml, Dockerfile, load-test,…
- author zbnerd; CLOSED; created 2026-06-11T15:35:33Z; closed 2026-06-14T15:59:51Z; reason COMPLETED. Body: ## What to build Swap the production deploy target from module-app to module-rest-controller. The current pipeline (`.github/workflows/gradle.yml`) bu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [74725906005c]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1240 — chore: delete module-app + module-web (settings.gradle, directories, ADR-deprecated tag)
- author zbnerd; CLOSED; created 2026-06-11T15:35:49Z; closed 2026-06-14T15:59:51Z; reason COMPLETED. Body: ## What to build Final deletion. Remove the now-empty module-app and module-web from the build. By this point module-app contains only the boot main c…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [74725906005c]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1243 — ops: PostgreSQL nightly pg_dump + off-host rsync
- author zbnerd; CLOSED; created 2026-06-12T01:30:08Z; closed 2026-06-14T15:59:52Z; reason COMPLETED. Body: ## What to build Nightly PostgreSQL logical backup via pg_dump, stored off-host on a worker node (Node2). Single command, single cron job, zero new in…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1244 — ops: MinIO distributed mode (4-disk erasure coding)
- author zbnerd; CLOSED; created 2026-06-12T01:30:17Z; closed 2026-06-14T15:59:52Z; reason COMPLETED. Body: ## What to build Switch MinIO from single-node single-drive to single-node 4-drive erasure coding (EC:2). Keeps SPOF at the Node1 level but eliminates…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1245 — 4 active 모듈 Dockerfile + .dockerignore 표준화
- author zbnerd; CLOSED; created 2026-06-12T01:30:20Z; closed 2026-06-14T15:59:52Z; reason COMPLETED. Body: ## What to build 표준 multistage Dockerfile을 4 active 모듈(module-rest-controller, module-external-api, module-calculator, module-synchronizer)에 적용. 1 모듈 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1246 — ops: PostgreSQL read replica + PITR via wal-g
- author zbnerd; CLOSED; created 2026-06-12T01:30:27Z; closed 2026-06-14T15:59:52Z; reason COMPLETED. Body: ## What to build Add a streaming read replica (PostgreSQL) for read scale + Point-In-Time Recovery (PITR) via wal-g. Replica lives on the same Node1 b…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1247 — infra compose 8 services → Coolify App (infra)
- author zbnerd; CLOSED; created 2026-06-12T01:30:41Z; closed 2026-06-14T15:59:53Z; reason COMPLETED. Body: ## What to build 기존 `docker-compose.yml`의 infra 서비스 8종을 Coolify App(`infra`)으로 이전. Coolify compose import 기능 사용. PGMQ는 `jumski/postgres-17-pgmq` 이미지 그…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1248 — ops: Tailscale 4-node mesh for Node1↔Node2/3/4 encryption
- author zbnerd; CLOSED; created 2026-06-12T01:30:45Z; closed 2026-06-14T15:59:53Z; reason COMPLETED. Body: ## What to build Deploy Tailscale on Node1 + Node2/3/4. Replaces plaintext cross-node traffic (Kafka 9092, MinIO 9000) with WireGuard-encrypted mesh. …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1249 — ops: Kafka SASL/SSL for cross-node traffic
- author zbnerd; CLOSED; created 2026-06-12T01:30:53Z; closed 2026-06-14T15:59:53Z; reason COMPLETED. Body: ## What to build Enable SASL_SSL on Kafka broker. Generate CA, sign broker cert + 4 client certs (Node1 services + Node2/3/4 workers). Update producer…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1250 — pipeline 3 services → Coolify App (pipeline)
- author zbnerd; CLOSED; created 2026-06-12T01:30:54Z; closed 2026-06-14T15:59:54Z; reason COMPLETED. Body: ## What to build ext-api, calculator, synchronizer 3 서비스를 Coolify App(`pipeline`)으로 이전. 1개 Coolify App에 3 서비스 compose. 1 replica each. 대상 서비스: - `modu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1251 — edge 2 services + Traefik labels → Coolify App (edge)
- author zbnerd; CLOSED; created 2026-06-12T01:31:04Z; closed 2026-06-14T15:59:54Z; reason COMPLETED. Body: ## What to build rest-con, airflow-webserver 2 서비스를 Coolify App(`edge`)으로 이전. 1개 Coolify App에 2 서비스. Traefik label로 public 노출 + Let's Encrypt 자동 발급. 1…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1252 — ops: MinIO TLS termination
- author zbnerd; CLOSED; created 2026-06-12T01:31:05Z; closed 2026-06-14T15:59:54Z; reason COMPLETED. Body: ## What to build Add TLS to MinIO endpoint. Self-signed cert via internal CA, valid for Node1 FQDN. Workers (Node2/3/4) and same-host modules connect …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1253 — .env 12+ 키 → Coolify UI env vars 이관
- author zbnerd; CLOSED; created 2026-06-12T01:31:08Z; closed 2026-06-14T15:59:19Z; reason COMPLETED. Body: ## What to build `.env` 파일의 12+ 키 전부를 Coolify UI env vars로 이관. Coolify UI에서 per-app scope로 분리 입력, secret 마킹. 대상 키 (현재 .env 기준): - DB: `DB_ROOT_PASSWOR…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1254 — ops: MinIO service-account separation (4 accounts, prefix IAM)
- author zbnerd; CLOSED; created 2026-06-12T01:31:09Z; closed 2026-06-14T15:59:20Z; reason COMPLETED. Body: ## What to build Replace the single shared MinIO credential with 4 service accounts, each with prefix-scoped IAM. Apply least privilege: writer (ext-a…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1255 — ops: Prometheus alert rules (5 baseline rules)
- author zbnerd; CLOSED; created 2026-06-12T01:31:25Z; closed 2026-06-14T15:59:20Z; reason COMPLETED. Body: ## What to build Add a baseline Prometheus alert rules file at monitoring/prometheus/rules/pipeline.yml with 5 alerts covering the failure modes that …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1256 — big-bang cut-over runbook
- author zbnerd; CLOSED; created 2026-06-12T01:31:28Z; closed 2026-06-14T15:59:20Z; reason COMPLETED. Body: ## What to build 4 모듈 + infra 전체 Coolify 이전을 위한 big-bang cut-over runbook. 메인터넌스 윔 1회, step-by-step 절차 + healthcheck pass/fail 기준 + 롤백 절차. HITL (사람이 직…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1257 — feat(ext-api+calc+sync): MDC runId + Loki label for log correlation
- author zbnerd; CLOSED; created 2026-06-12T01:31:34Z; closed 2026-06-14T15:59:20Z; reason COMPLETED. Body: ## What to build Add runId to MDC (Mapped Diagnostic Context) in all log statements across ext-api, calculator, synchronizer, cleanup. Configure Promt…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1258 — post-cutover smoke test 자동화
- author zbnerd; CLOSED; created 2026-06-12T01:31:43Z; closed 2026-06-14T15:59:21Z; reason COMPLETED. Body: ## What to build Coolify cut-over 후 4 모듈 + infra 정상 동작을 자동으로 검증. e2e 테스트 + 부하테스트 light + 로그 검증. 검증 항목: 1. **health check**: 4 모듈 `/actuator/health` 20…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1259 — 레거시 docker-compose*.yml + .env 폐기 + 문서
- author zbnerd; CLOSED; created 2026-06-12T01:32:00Z; closed 2026-06-14T15:59:21Z; reason COMPLETED. Body: ## What to build Coolify cut-over + smoke test 안정화 확인 후 legacy 자산 폐기. 5 파일 git에서 제거 + 아키텍처 문서 Coolify 기준으로 갱신. 대상 파일: - `docker-compose.yml` - `docker…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1260 — feat(ext-api): Nexon API quota Prometheus metric + alert
- author zbnerd; CLOSED; created 2026-06-12T01:32:11Z; closed 2026-06-14T15:59:21Z; reason COMPLETED. Body: ## What to build Track Nexon API daily quota consumption as a Prometheus gauge. Parse quota from response headers (X-RateLimit-Remaining, X-Quota-Used…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1261 — ops: Per-run progress Grafana dashboard
- author zbnerd; CLOSED; created 2026-06-12T01:32:13Z; closed 2026-06-14T15:59:22Z; reason COMPLETED. Body: ## What to build Add a Grafana dashboard json (grafana/dashboard-pipeline-per-run.json) showing per-run progress across 4 modules. Drill-down: click r…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1262 — docs: SLO + Error Budget definition
- author zbnerd; CLOSED; created 2026-06-12T01:32:16Z; closed 2026-06-14T15:59:22Z; reason COMPLETED. Body: ## What to build Define SLOs (Service Level Objectives) and error budgets for the pipeline. Document in docs/21_Operations/slo-error-budget.md. Wire t…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1263 — fix(ext-api+calc+sync+cleanup): Kafka producer idempotence + transactional
- author zbnerd; CLOSED; created 2026-06-12T01:32:33Z; closed 2026-06-14T15:59:22Z; reason COMPLETED. Body: ## What to build Enable Kafka producer idempotence (enable.idempotence=true) and consider transactional writes for chunk-ready + result-ready topics. …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1264 — ops: Kafka topic partition count tuning for 3 ext-api workers
- author zbnerd; CLOSED; created 2026-06-12T01:32:40Z; closed 2026-06-14T15:59:23Z; reason COMPLETED. Body: ## What to build Inspect current Kafka topic partition counts. For chunk-ready, result-ready, chunk-consumed, ocid-lookup-ready — set to 6-12 partitio…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1265 — feat(kafka): Schema registry (Avro) for chunk-ready + result-ready
- author zbnerd; CLOSED; created 2026-06-12T01:32:47Z; closed 2026-06-14T15:59:23Z; reason COMPLETED. Body: ## What to build Introduce Confluent Schema Registry (or Apicurio) on Node1. Define Avro schemas for chunk-ready, result-ready, chunk-consumed, run-co…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1266 — bug: Batch vs Urgent pipeline race causes out-of-order write in read-model
- author zbnerd; CLOSED; created 2026-06-12T01:32:55Z; closed 2026-06-14T15:59:23Z; reason COMPLETED. Body: ## What to build Fix the race where batch charBasic and urgent charBasic publish chunks for the same ocid out-of-order, and synchronizer's last-writer…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1267 — feat(calculator): scale-out path via Kafka consumer group
- author zbnerd; CLOSED; created 2026-06-12T01:33:10Z; closed 2026-06-14T15:59:24Z; reason COMPLETED. Body: ## What to build Replace PGMQ q_expectation_calc_high with a Kafka topic for chunk-ready distribution. Configure 2-4 calculator instances with same co…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1268 — feat(synchronizer): scale-out path via partition key = endpoint
- author zbnerd; CLOSED; created 2026-06-12T01:33:17Z; closed 2026-06-14T15:59:24Z; reason COMPLETED. Body: ## What to build Run 2-3 synchronizer instances with same consumer group on result-ready topic. Partition by endpoint so each instance owns a disjoint…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1269 — bug: Chunk loss prevention — audit producer acks + retry + idempotence
- author zbnerd; CLOSED; created 2026-06-12T01:33:24Z; closed 2026-06-14T15:59:24Z; reason COMPLETED. Body: ## What to build Audit the producer config across all 4 modules. Verify each publishes with acks=all, retries=MAX, delivery.timeout.ms sufficient for …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1270 — docs(adr): ADR for Redis re-introduction (supersede ADR-022)
- author zbnerd; CLOSED; created 2026-06-12T01:33:38Z; closed 2026-06-14T15:59:25Z; reason COMPLETED. Body: ## What to build Write ADR-726 (or next available) capturing the decision to re-introduce Redis. Document the use case (cache L2? rate limit? hot path…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1271 — docs(adr): ADR for MinIO distributed mode topology
- author zbnerd; CLOSED; created 2026-06-12T01:33:42Z; closed 2026-06-14T15:59:25Z; reason COMPLETED. Body: ## What to build Write ADR capturing the decision to deploy MinIO in 4-disk erasure coding mode on Node1 (single node, 4 drives). Document the trade-o…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1272 — docs(adr): ADR for production deployment topology (Node1/2/3/4)
- author zbnerd; CLOSED; created 2026-06-12T01:33:50Z; closed 2026-06-14T15:59:25Z; reason COMPLETED. Body: ## What to build Write ADR capturing the production deployment topology: Node1 (32GB, all stateful services), Node2/3/4 (stateless ext-api workers). D…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1273 — chore(setup): worktree + branch for architecture review 2026-06-12
- author zbnerd; CLOSED; created 2026-06-12T05:20:52Z; closed 2026-06-14T15:59:26Z; reason COMPLETED. Body: ## What to build Create the worktree + branch for the 2026-06-12 architectural review work. Steps: 1. `git checkout develop && git pull origin develop…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1274 — docs(arch-review): Phase 1 review (sections 1-7: control plane + data flow)
- author zbnerd; CLOSED; created 2026-06-12T05:21:09Z; closed 2026-06-14T15:59:26Z; reason COMPLETED. Body: ## What to build Targeted code dive for sections 1-7 of the 2026-06-12 architectural review, then write Phase 1 spec + Critical/High finding specs. **…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1275 — docs(arch-review): Phase 2 review (sections 8-15: ops + evolution)
- author zbnerd; CLOSED; created 2026-06-12T05:21:14Z; closed 2026-06-14T15:59:26Z; reason COMPLETED. Body: ## What to build Targeted code dive for sections 8-15 of the 2026-06-12 architectural review, then write Phase 2 spec + Critical/High finding specs. *…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1276 — docs(arch-review): Phase 3 summary + maturity assessment
- author zbnerd; CLOSED; created 2026-06-12T05:21:30Z; closed 2026-06-14T15:59:27Z; reason COMPLETED. Body: ## What to build Synthesis from Phase 1 + Phase 2 evidence into the 7 mandatory summary items + maturity assessment. **Output artifact:** - `docs/supe…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1277 — chore(arch-review): finalize — commit, push, open PR
- author zbnerd; CLOSED; created 2026-06-12T05:21:42Z; closed 2026-06-14T15:59:27Z; reason COMPLETED. Body: ## What to build Final cleanup after Phase 3 review is approved. Commit any remaining artifacts, push branch, open PR to develop. Steps: 1. `git statu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1289 — [ext-api] Phase trigger endpoint: HTTP API for running a single phase standalone
- author zbnerd; CLOSED; created 2026-06-16T04:52:02Z; closed 2026-06-18T05:05:32Z; reason COMPLETED. Body: ## What to build A new HTTP endpoint `POST /api/internal/trigger/phase/{phaseName}` that runs a single pipeline phase as an independent, self-containe…
- discussion: 1 / Resolved by #1299. POST /api/internal/trigger/phase/{phaseName} implemented with…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1299/MERGED]; reachable commit-message refs 4 [40156cfa74c5, 521f6db88b5f, b52047ba79d4, c86c21b6bf79]; PR-record issue refs 1 [#1299/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1290 — [ext-api] Phase stop endpoint: gracefully halt a running phase
- author zbnerd; CLOSED; created 2026-06-16T04:52:17Z; closed 2026-06-18T07:19:43Z; reason COMPLETED. Body: ## What to build A new HTTP endpoint `POST /api/internal/stop/phase/{phaseName}` that requests graceful shutdown of a running phase. The phase finishe…
- discussion: 1 / Resolved by #1300. Added POST /api/internal/stop/phase/{phaseName} for graceful …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1300/MERGED]; reachable commit-message refs 4 [28ab2dc20461, 39f636b1377a, a8653e0c7446, ee9bde82edff]; PR-record issue refs 1 [#1300/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1291 — [ext-api] Phase infinite-loop endpoint: continuous single-phase execution
- author zbnerd; CLOSED; created 2026-06-16T04:52:33Z; closed 2026-06-18T08:55:19Z; reason COMPLETED. Body: ## What to build A new HTTP endpoint `POST /api/internal/loop/phase/{phaseName}` that starts a continuous loop of a single phase (re-runs the phase ba…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [39f636b1377a, bad931560d53]; PR-record issue refs 1 [#1302/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1292 — [airflow] Per-phase DAG: parameterized phase trigger / stop / loop tasks
- author zbnerd; CLOSED; created 2026-06-16T04:52:51Z; closed 2026-06-18T12:23:51Z; reason COMPLETED. Body: ## What to build Extend the existing Airflow control plane to drive the new per-phase ext-api endpoints. Operators should be able to trigger, stop, or…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [fd5348e79f9a]; PR-record issue refs 1 [#1303/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1296 — [ext-api] startup hook: prune orphan gzip-chunk-*.tmp files in /tmp
- author zbnerd; CLOSED; created 2026-06-16T12:14:08Z; closed 2026-06-19T05:57:41Z; reason COMPLETED. Body: ## What to build Add a startup hook in `module-external-api` that prunes orphan `gzip-chunk-*.tmp` files from `/tmp` on application boot. These files …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [74a65d50a571]; PR-record issue refs 1 [#1309/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1310 — perf(pipeline): cap direct memory at 512MB
- author zbnerd; CLOSED; created 2026-06-19T13:21:48Z; closed 2026-06-19T15:37:51Z; reason COMPLETED. Body: ## Parent Spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 1 Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.…
- discussion: 1 / PR #1315 merged at aed47f55b. Pure config change complete. Acceptance criteria r…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1315/MERGED]; reachable commit-message refs 6 [28dca3ea2ce2, 30c601724bcd, 4be816e9930d, 6cadc2d8b807, 85b5528df557, 8e2f5333f482]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1311 — perf(calculator): off-heap OCID cache via Chronicle Map
- author zbnerd; CLOSED; created 2026-06-19T13:22:35Z; closed 2026-06-19T17:43:12Z; reason COMPLETED. Body: ## Parent Spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 2 Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.…
- discussion: 1 / Closed: completed via PR #1319 (merged 2026-06-19). Summary: - Off-heap cache vi…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1319/MERGED]; reachable commit-message refs 6 [69690f51c6ba, 85b5528df557, 8f8deca54b9b, a4f912d0b393, c0a163928a47, cca3dfba9d6b]; PR-record issue refs 1 [#1319/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1312 — perf(calculator): streaming gzip → S3 multipart upload via CF chain
- author zbnerd; CLOSED; created 2026-06-19T13:22:38Z; closed 2026-06-21T05:16:10Z; reason COMPLETED. Body: ## Parent Spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 3 Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [4c8af3a7ad58, 69690f51c6ba, 83e060ae89ee, 85b5528df557, b53a396e0038, f133716bf822]; PR-record issue refs 2 [#1317/MERGED, #1318/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1313 — perf(ext-api): streaming JSONL parser for chunk payloads
- author zbnerd; CLOSED; created 2026-06-19T13:23:14Z; closed 2026-06-19T17:19:12Z; reason COMPLETED. Body: ## Parent Spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 4 Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.…
- discussion: 1 / Closed via PR #1317 (https://github.com/zbnerd/probabilistic-valuation-engine/pu…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1317/MERGED]; reachable commit-message refs 6 [4e7797493235, 69690f51c6ba, 6a9526c51b8c, 85b5528df557, 9f731899ef70, d055377246de]; PR-record issue refs 2 [#1317/MERGED, #1318/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1314 — perf(pipeline): tune Netty/Kafka direct buffer pools
- author zbnerd; CLOSED; created 2026-06-19T13:23:17Z; closed 2026-06-19T16:39:11Z; reason COMPLETED. Body: ## Parent Spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 5 Plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.…
- discussion: 1 / Resolved by PR #1316 (merged as 7adbe50a). Phase 5 of off-heap streaming plan: N…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1316/MERGED]; reachable commit-message refs 18 [04ae6085461b, 266ba6c86678, 28dca3ea2ce2, 30c601724bcd, 4be816e9930d, 5737218aa1fc, 5a8119df2b93, 74de51eadfef, 7adbe50aafa9, 85b5528df557, 8e2f5333f482, 92e8fc655aaa, 9530e49edca6, 9cab09dbcc7a, b8b392a4b737, eab990b6a809, ef0b1e29b16b, f7c1ddde07d9]; PR-record issue refs 1 [#1316/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1337 — [Investigation] Iceberg Feasibility Study
- author zbnerd; CLOSED; created 2026-06-23T06:24:10Z; closed 2026-06-23T06:41:12Z; reason COMPLETED. Body: ## Description Evaluate whether Apache Iceberg v1.7+ is a viable table format for the probabilistic-valuation-engine data platform. Goal: produce a wr…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-iceberg-…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1338 — [Investigation] MinIO Compatibility Validation
- author zbnerd; CLOSED; created 2026-06-23T06:24:15Z; closed 2026-06-23T06:41:14Z; reason COMPLETED. Body: ## Description Validate that the existing MinIO deployment (S3-compatible object storage) supports all S3 APIs required by (a) Apache Iceberg S3FileIO…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-minio-co…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1339 — [Investigation] Analytics Layer ADR Finalization
- author zbnerd; CLOSED; created 2026-06-23T06:24:17Z; closed 2026-06-23T06:41:17Z; reason COMPLETED. Body: ## Description Convert the proposed ADR-735 (Future Analytics Platform Evaluation) into an Accepted decision once the investigation issues in this tra…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-analytic…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1340 — [Investigation] Query Engine Evaluation
- author zbnerd; CLOSED; created 2026-06-23T06:24:22Z; closed 2026-06-23T06:41:20Z; reason COMPLETED. Body: ## Description Conduct a comparative benchmark of PostgreSQL 16, ClickHouse, Trino, and Spark on the four named analytical workloads: class statistics…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-query-en…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1341 — [Investigation] PostgreSQL Scalability Assessment
- author zbnerd; CLOSED; created 2026-06-23T06:24:33Z; closed 2026-06-23T06:41:22Z; reason COMPLETED. Body: ## Description Measure current PostgreSQL 16 read-model scalability on the probabilistic-valuation-engine serving path. Identify the analytical worklo…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-pg-scala…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1342 — [Investigation] Historical Analytics Requirements
- author zbnerd; CLOSED; created 2026-06-23T06:24:36Z; closed 2026-06-23T06:41:25Z; reason COMPLETED. Body: ## Description Define the concrete analytical workload requirements that would justify an Analytics Layer beyond the current PostgreSQL read model. Ca…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-historic…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1343 — [Investigation] Class Hierarchy Data Modeling
- author zbnerd; CLOSED; created 2026-06-23T06:24:46Z; closed 2026-06-23T06:41:27Z; reason COMPLETED. Body: ## Description Design the data model for representing MapleStory class hierarchy in the read model and any future analytics layer. Capture the current…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-class-hi…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1344 — [Investigation] Serving Layer vs Analytics Layer Separation
- author zbnerd; CLOSED; created 2026-06-23T06:24:53Z; closed 2026-06-23T06:41:30Z; reason COMPLETED. Body: ## Description Define the boundary between the serving layer (PostgreSQL read model + REST API, p95 < 100ms SLA) and a future analytics layer. Documen…
- discussion: 1 / Completed by planning pipeline. Spec: docs/superpowers/specs/2026-06-23-serving-…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1345 — [ADR-735] Phase 0/A: prerequisite gate + evidence aggregation
- author zbnerd; CLOSED; created 2026-06-23T06:31:59Z; closed 2026-06-23T06:44:52Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1346 — [ADR-735] Phase B: T1-T8 trigger evaluation annotation
- author zbnerd; CLOSED; created 2026-06-23T06:32:02Z; closed 2026-06-23T06:44:54Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1347 — [ADR-735] Phase C: metrics table population
- author zbnerd; CLOSED; created 2026-06-23T06:32:03Z; closed 2026-06-23T06:44:56Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1348 — [ADR-735] Phase D: status transition Proposed→Accepted
- author zbnerd; CLOSED; created 2026-06-23T06:32:09Z; closed 2026-06-23T06:44:58Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1349 — [ADR-735] Phase E: PR review + approval gate
- author zbnerd; CLOSED; created 2026-06-23T06:32:12Z; closed 2026-06-23T06:45:00Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1350 — [ADR-735] Phase F: rollback path (conditional)
- author zbnerd; CLOSED; created 2026-06-23T06:32:16Z; closed 2026-06-23T06:45:02Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1351 — [ADR-735] update §1 References cross-links
- author zbnerd; CLOSED; created 2026-06-23T06:32:19Z; closed 2026-06-23T06:45:04Z; reason COMPLETED. Body: Parent: #1339 Spec: docs/superpowers/specs/2026-06-23-analytics-adr-finalization.md Plan: docs/superpowers/plans/2026-06-23-analytics-adr-finalization…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1352 — [Benchmark] Write dataset generator script (Phase 1.1)
- author zbnerd; CLOSED; created 2026-06-23T06:32:32Z; closed 2026-06-23T06:45:11Z; reason COMPLETED. Body: Parent: #1340 Spec: docs/superpowers/specs/2026-06-23-query-engine-benchmark.md Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Pha…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1353 — [1342.1] Interview data analyst persona (ad-hoc query author)
- author zbnerd; CLOSED; created 2026-06-23T06:32:37Z; closed 2026-06-23T06:45:13Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 1 task 1.1: collect WA-1, WA-2, WA-6, WA-8 raw notes (persona, question template, cardinality, …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1354 — [Benchmark] Generate 1B-row synthetic dataset (Phase 1.2)
- author zbnerd; CLOSED; created 2026-06-23T06:32:38Z; closed 2026-06-23T06:45:15Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 1.2) Run gen_data.py at scale 1B rows. Output /tmp/benchmark/da…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1355 — [1342.2] Interview PM (roadmap commitment COMMITTED vs SPECULATIVE)
- author zbnerd; CLOSED; created 2026-06-23T06:32:39Z; closed 2026-06-23T06:45:16Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 1 task 1.2: assign COMMITTED vs SPECULATIVE per workload with roadmap citation (Slack/Issue lin…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1356 — [Benchmark] Generate 10B-row synthetic dataset (Phase 1.3, optional)
- author zbnerd; CLOSED; created 2026-06-23T06:32:40Z; closed 2026-06-23T06:45:18Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 1.3) Run gen_data.py at scale 10B rows. Output /tmp/benchmark/d…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1357 — [1342.3] Interview SRE (cardinality, frequency, latency, volume, freshness)
- author zbnerd; CLOSED; created 2026-06-23T06:32:40Z; closed 2026-06-23T06:45:21Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 1 task 1.3: capture cardinality / frequency / p95 / volume / freshness from infra view, cross-c…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1358 — [1342.4] Interview on-call engineer (anomaly, retention, audit)
- author zbnerd; CLOSED; created 2026-06-23T06:32:42Z; closed 2026-06-23T06:45:22Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 1 task 1.4: capture WA-7 (anomaly detection), WA-10 (regulatory), and retention classes (RET-N.…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1359 — [Benchmark] PostgreSQL 16.3 baseline (Phase 2)
- author zbnerd; CLOSED; created 2026-06-23T06:32:42Z; closed 2026-06-23T06:45:24Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 2) Tasks 2.1-2.4. Spin up postgres:16.3 container, COPY load Pa…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1360 — [Benchmark] ClickHouse 24.5 (Phase 3)
- author zbnerd; CLOSED; created 2026-06-23T06:32:44Z; closed 2026-06-23T06:45:26Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 3) Tasks 3.1-3.4. Spin up clickhouse/clickhouse-server:24.5, Me…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1361 — [Benchmark] Trino 446 (Phase 4)
- author zbnerd; CLOSED; created 2026-06-23T06:32:46Z; closed 2026-06-23T06:45:28Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 4) Tasks 4.1-4.4. Spin up trinodb/trino:446 with Hive connector…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1362 — [Benchmark] Spark 3.5.2 (Phase 5)
- author zbnerd; CLOSED; created 2026-06-23T06:32:47Z; closed 2026-06-23T06:45:29Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 5) Tasks 5.1-5.4. Spin up apache/spark:3.5.2, read Parquet, run…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1363 — [1342.5] Build canonical workload matrix (14 fields per row)
- author zbnerd; CLOSED; created 2026-06-23T06:32:48Z; closed 2026-06-23T06:45:32Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 2 task 2.1: consolidate Phase 1 interview notes into a single matrix with all 14 spec fields fi…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1364 — [Benchmark] Aggregate and publish final report (Phase 6)
- author zbnerd; CLOSED; created 2026-06-23T06:32:49Z; closed 2026-06-23T06:45:34Z; reason COMPLETED. Body: Parent: #1340 Plan: docs/superpowers/plans/2026-06-23-query-engine-benchmark.md (Phase 6) Tasks 6.1-6.3. Aggregate 64 cell JSONs into all_cells.json, …
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1365 — [1342.6] Classify cross-source / time-travel / retention per workload
- author zbnerd; CLOSED; created 2026-06-23T06:32:50Z; closed 2026-06-23T06:45:35Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 2 tasks 2.2 / 2.3 / 2.4: assign CSJ-A/B/C, TT-0..TT-4, RET-N..RET-4 for every workload. Deliver…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1366 — [1342.7] Build trigger-gate map (workload × T1..T8)
- author zbnerd; CLOSED; created 2026-06-23T06:32:52Z; closed 2026-06-23T06:45:37Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 2 task 2.5: produce workload × T1..T8 multi-select matrix, justified cell by cell. Deliverable:…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1367 — [1342.8] Score workloads (0-3 × 5 axes) and assign P0-P3 tiers
- author zbnerd; CLOSED; created 2026-06-23T06:32:54Z; closed 2026-06-23T06:45:39Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 3 tasks 3.1 / 3.2: score every workload on commitment / frequency / consumer / trigger / impact…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1368 — [1342.9] ADR-735 §2 trigger review — narrative on first-firing trigger
- author zbnerd; CLOSED; created 2026-06-23T06:32:56Z; closed 2026-06-23T06:45:41Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 3 task 3.3: for each P0/P1 workload, name the first T1-T8 trigger that would fire and the rough…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1369 — [1342.10] Reviewer pass on scoring (counter-arguments + resolution)
- author zbnerd; CLOSED; created 2026-06-23T06:32:57Z; closed 2026-06-23T06:45:44Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 3 task 3.4: external reviewer challenges ≥3 scoring decisions; resolution recorded in PR. Deliv…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1370 — [1342.11] Publish workload matrix + trigger map; link from ADR-735
- author zbnerd; CLOSED; created 2026-06-23T06:33:00Z; closed 2026-06-23T06:45:51Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 4 tasks 4.1 / 4.2 / 4.3: publish final docs under docs/03_Technical_Guides/, add links to ADR-7…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #1371 — [1342.12] Close #1342 with summary comment + artifact links
- author zbnerd; CLOSED; created 2026-06-23T06:33:01Z; closed 2026-06-23T06:45:53Z; reason COMPLETED. Body: Part of #1342 Historical Analytics Requirements. Phase 4 task 4.4: post closure comment on #1342 linking both published docs and the trigger narrative…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1372 — [#1341] Pre-check pg_stat_statements availability on staging
- author zbnerd; CLOSED; created 2026-06-23T06:33:02Z; closed 2026-06-23T06:45:54Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md Plan: docs/superpowers/plans/2026-06-23-pg-scalability-assessment.m…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1373 — [#1341] Capture 24h pg_stat_statements workload mix
- author zbnerd; CLOSED; created 2026-06-23T06:33:09Z; closed 2026-06-23T06:45:56Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.1.1 Plan: Task 1.2 Hourly snapshot of pg_stat_statements over 24…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1374 — [#1341] Per-character serving latency sample (10K IGN)
- author zbnerd; CLOSED; created 2026-06-23T06:33:13Z; closed 2026-06-23T06:45:58Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.1.2 Plan: Task 1.3 Sample 10K IGN from userIgn_List.csv. Run GET…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1375 — [#1341] 7-day slow query log capture on staging
- author zbnerd; CLOSED; created 2026-06-23T06:33:15Z; closed 2026-06-23T06:46:00Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.1.3 Plan: Task 1.4 Set log_min_duration_statement=1000, log_lock…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1376 — [#1341] Build synthetic 1B-row benchmark dataset
- author zbnerd; CLOSED; created 2026-06-23T06:33:18Z; closed 2026-06-23T06:46:01Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.2 Plan: Task 2.1 pg_dump schema of character_valuation_views. Po…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #1377 — [#1341] Capture baseline EXPLAIN ANALYZE for top-5 analytical patterns
- author zbnerd; CLOSED; created 2026-06-23T06:33:21Z; closed 2026-06-23T06:46:03Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.1.4 Plan: Task 2.2 Five queries: (1) Top-N per (world,class); (2…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1378 — [#1341] Index experiment I1 — BRIN on updated_at
- author zbnerd; CLOSED; created 2026-06-23T06:33:25Z; closed 2026-06-23T06:46:05Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.2 I1 Plan: Task 2.3 CREATE INDEX idx_brin_updated_at ON characte…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1379 — [#1341] Index experiment I2 — Partial index on hot (class,world)
- author zbnerd; CLOSED; created 2026-06-23T06:33:28Z; closed 2026-06-23T06:46:07Z; reason COMPLETED. Body: Parent: #1341 Spec: docs/superpowers/specs/2026-06-23-pg-scalability-assessment.md §4.2 I2 Plan: Task 2.4 Extract top-100 (class, world) from pg_stat.…
- discussion: 1 / Closing: analytics platform adoption deferred until trigger T1-T8 fires (ADR-735…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1042 — refactor: Semaphore 동시성 제한 Properties 외부화 — CalculatorChunkProcessingCoordinator
- author zbnerd; CLOSED; created 2026-06-03T16:22:15Z; closed 2026-06-14T16:01:31Z; reason COMPLETED. Body: ## Parent #1023 ## What to build #1023이 4개 컴포넌트의 Semaphore를 다루지만 `CalculatorChunkProcessingCoordinator`의 `Semaphore(2)`는 누락. 하드코딩된 동시성 제한을 Properties로…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1043 — TDD: AuthCharacterFetchConsumer NexonAuthClient → port interface 전환
- author zbnerd; CLOSED; created 2026-06-03T16:22:33Z; closed 2026-06-14T16:01:31Z; reason COMPLETED. Body: ## What to build `AuthCharacterFetchConsumer`가 구체 클래스 `NexonAuthClient`를 직접 import 한다. 이를 port interface로 대체하여 DI 가능하게 만들고 단위 테스트를 추가한다. 현재 상태: - `Aut…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1044 — TDD: GzipJsonlChunkWriter 스트림 공급자 인터페이스 추출
- author zbnerd; CLOSED; created 2026-06-03T16:22:36Z; closed 2026-06-14T16:01:31Z; reason COMPLETED. Body: ## What to build `GzipJsonlChunkWriter`가 생성자에서 `FileOutputStream` → `BufferedOutputStream` → `GZIPOutputStream`을 직접 생성한다. 파일 시스템 없이는 테스트 불가. 스트림 생성을 `…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1045 — TDD: UrgentCharacterRequestConsumer.publishUrgentChunkAsync 분해
- author zbnerd; CLOSED; created 2026-06-03T16:22:38Z; closed 2026-06-14T16:01:31Z; reason COMPLETED. Body: ## What to build `UrgentCharacterRequestConsumer.publishUrgentChunkAsync()` (lines 124-172, 48줄)가 파일 I/O, GzipJsonlChunkWriter 생성, 이벤트 구성, Kafka 발행을 하…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1046 — refactor: Fix LikeController ForkJoinPool.commonPool() usage
- author zbnerd; CLOSED; created 2026-06-03T16:22:56Z; closed 2026-06-04T04:01:49Z; reason COMPLETED. Body: ## What to build `LikeController`의 `toggleLike`와 `getStatusByUser`가 `CompletableFuture.supplyAsync { }`를 executor 없이 호출. 이는 `ForkJoinPool.commonPool()…
- discussion: 1 / #1102 PR #1123에 포함되어 처리 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [6f22ef7dc825]; PR-record issue refs 1 [#1123/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1047 — refactor: Deduplicate utility functions (sha256Hex, GZIP reader, 503 response, toCubeInput…
- author zbnerd; CLOSED; created 2026-06-03T16:22:59Z; closed 2026-06-14T16:01:32Z; reason COMPLETED. Body: ## What to build 4개 중복 유틸리티 패턴 제거: 1. **sha256Hex()**: `module-synchronizer`에 2곳 (`BasicChunkFileReader:126`, `EquipmentDocumentPreparer:30`)에 동일 구현. …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1048 — refactor: Introduce type-safe enums, apply existing domain types
- author zbnerd; CLOSED; created 2026-06-03T16:23:02Z; closed 2026-06-14T16:01:32Z; reason COMPLETED. Body: ## What to build Magic string을 type-safe enum으로 교체: 1. **CalculationStatus enum** 신규 생성: `SUCCESS`, `SKIPPED`, `ERROR`. 적용: `CalculationResult.status`…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1049 — TDD: SchedulerPhaseUtils rate-limiter/sleep 인터페이스 추출
- author zbnerd; CLOSED; created 2026-06-03T16:23:14Z; closed 2026-06-14T16:01:32Z; reason COMPLETED. Body: ## What to build `SchedulerPhaseUtils`가 `Bucket.builder()` (static)와 `Thread.sleep(Duration)`을 직접 호출. 테스트에서 제어 불가, non-deterministic delay 발생. `RateLi…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1050 — TDD: ExternalApiScheduler.triggerDailyRefresh 분해
- author zbnerd; CLOSED; created 2026-06-03T16:23:17Z; closed 2026-06-14T16:01:33Z; reason COMPLETED. Body: ## What to build `ExternalApiScheduler.triggerDailyRefresh()` (lines 61-119, 58줄)가 lock 획득, 조건 분기, CF 오케스트레이션, 상태 추적, lock 해제를 하나의 메서드에 혼합. 순수 스케줄링/분기…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1051 — TDD: ConsumedChunkCleanupScheduler 분해 — queue capacity 순수 로직 추출
- author zbnerd; CLOSED; created 2026-06-03T16:23:19Z; closed 2026-06-14T16:01:33Z; reason COMPLETED. Body: ## What to build `ConsumedChunkCleanupScheduler.consume()` (lines 36-58, 22줄)가 Kafka 메시지 역직렬화, `ConcurrentLinkedQueue` + `AtomicInteger` 용량 관리, ACK를 혼…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1052 — TDD: SnapshotFetchPhase.processBatch 분해 — BatchFetchCoordinator 추출
- author zbnerd; CLOSED; created 2026-06-03T16:23:22Z; closed 2026-06-14T16:01:33Z; reason COMPLETED. Body: ## What to build `SnapshotFetchPhase.processBatch()` (lines 154-203, 49줄)가 rate limiter 상호작용, batch slicing, async fetch 코디네이션, progress logging, recu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1053 — refactor: Introduce ChunkContext(runId, chunkId) value class in synchronizer
- author zbnerd; CLOSED; created 2026-06-03T16:23:30Z; closed 2026-06-14T16:01:34Z; reason COMPLETED. Body: ## What to build `synchronizer` 모듈에서 `runId: String, chunkId: String` 파라미터 쌍이 8개 메서드, 4개 파일에 걸쳐 반복. `ChunkContext(runId: String, chunkId: String)` val…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1054 — refactor: Introduce ReadKey(userIgn, presetNo) value class in rest-controller
- author zbnerd; CLOSED; created 2026-06-03T16:23:33Z; closed 2026-06-14T16:01:34Z; reason COMPLETED. Body: ## What to build `rest-controller` 모듈에서 `userIgn: String, presetNo: Int` 파라미터 쌍이 14+ 메서드, 5개 파일에 반복. `ReadRequest` data class가 이미 존재하나 파라미터 타입으로 미사용. …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1055 — arch: add shared test fixtures to module-common
- author zbnerd; CLOSED; created 2026-06-03T16:23:34Z; closed 2026-06-14T16:01:34Z; reason COMPLETED. Body: ## What to build Create a `testFixtures` source set in `module-common` with shared test utilities used across module-external-api, module-synchronizer…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1056 — refactor: Extract common consumer handler in calculator
- author zbnerd; CLOSED; created 2026-06-03T16:23:35Z; closed 2026-06-14T16:01:34Z; reason COMPLETED. Body: ## What to build `KafkaSnapshotChunkReadyConsumer`의 `consume()`과 `consumeUrgent()`가 라인 단위로 동일. ACK/error handling/coroutine scoping 로직이 중복. `@KafkaLis…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1057 — refactor: Introduce BatchProgress and sink factory in external-api phases
- author zbnerd; CLOSED; created 2026-06-03T16:23:39Z; closed 2026-06-07T10:49:25Z; reason COMPLETED. Body: ## What to build `SnapshotFetchPhase.processBatch()`가 11개 파라미터 보유. 그중 4개 accumulator state (`successCount`, `failCount`, `lastProgressLog`, `start`)가 …
- discussion: 1 / Closed by squash-merge of PR #1191. Introduced `BatchProgress` (shared batch sta…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1191/MERGED]; reachable commit-message refs 2 [7ea9ab9fa55c, ae4bc4a6229d]; PR-record issue refs 1 [#1191/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1058 — refactor: Introduce StoragePathResolver in external-api
- author zbnerd; CLOSED; created 2026-06-03T16:23:42Z; closed 2026-06-14T16:01:35Z; reason COMPLETED. Body: ## What to build `@Value("\${external-api.store.base-path:../data}")`가 7개 클래스에 독립 주입. 각 클래스가 `Path.of(storeBasePath, ...)`로 수동 path 조합. `StoragePathRe…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1059 — refactor: Reduce config boilerplate (ExecutorConfig + SnapshotEventPublisherConfig)
- author zbnerd; CLOSED; created 2026-06-03T16:24:04Z; closed 2026-06-14T16:01:35Z; reason COMPLETED. Body: ## What to build 두 config 클래스의 반복 보일러플레이트 제거: 1. **ExecutorConfig** (353 lines, infra): 6개 executor bean이 동일 ~30 line 보일러플레이트 반복 (`corePoolSize / maxP…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1060 — refactor: Split ReadModelCacheService into focused services
- author zbnerd; CLOSED; created 2026-06-03T16:24:07Z; closed 2026-06-07T07:39:46Z; reason COMPLETED. Body: ## What to build `ReadModelCacheService` (161 lines)이 4개 독립 관심사에 대해 변경됨: 1. Read model cache (multiGet/multiPut) 2. Negative cache (getNegativeCache/s…
- discussion: 1 / Closed by refactor commits. ReadModelCacheService split into 3 focused services:…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc60d43e28fa]; PR-record issue refs 1 [#1184/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1061 — refactor: Extract event publishing from ChunkedSnapshotSink
- author zbnerd; CLOSED; created 2026-06-03T16:24:10Z; closed 2026-06-06T13:33:40Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink` (292 lines)이 3개 독립 이유로 변경: 1. Queue/thread lifecycle (submit, close, runWriterLoop) 2. Chunk rotation/file I/O …
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1062 — refactor: Decompose SnapshotFetchPhase (metrics + sink lifecycle extraction)
- author zbnerd; CLOSED; created 2026-06-03T16:24:12Z; closed 2026-06-07T11:35:06Z; reason COMPLETED. Body: ## What to build `SnapshotFetchPhase` (292 lines)이 3개 이유로 변경: 1. Fetch orchestration (rate limiting, batching) 2. Metrics recording (per-fetch timing,…
- discussion: 1 / Closed by squash-merge of PR #1193 (commit on develop). FetchProgressTracker del…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1193/MERGED]; reachable commit-message refs 1 [7ea9ab9fa55c]; PR-record issue refs 1 [#1193/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1063 — arch: split ExecutorConfig into CoreExecutorConfig and InfraExecutorConfig
- author zbnerd; CLOSED; created 2026-06-03T16:24:30Z; closed 2026-06-04T02:48:28Z; reason COMPLETED. Body: ## What to build Split module-infra's monolithic ExecutorConfig into two focused configuration classes so that lightweight modules (external-api, sync…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2ce910d54240, 39e7752830d6, c4256927e7c6]; PR-record issue refs 1 [#1119/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1064 — arch: replace SnapshotEventPublisherConfig 8-bean boilerplate with factory pattern
- author zbnerd; CLOSED; created 2026-06-03T16:24:32Z; closed 2026-06-14T16:01:35Z; reason COMPLETED. Body: ## What to build Replace the 8 manual @Bean methods in SnapshotEventPublisherConfig with a factory pattern that auto-generates publishers from an enum…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1065 — refactor: Simplify CubeCalculationInput (use AddOption, remove hand-written Builder)
- author zbnerd; CLOSED; created 2026-06-03T16:24:33Z; closed 2026-06-14T16:01:36Z; reason COMPLETED. Body: ## What to build `CubeCalculationInput` (297 lines, 28 fields). 동일 필드 집합이 4번 선언: 1. Data class primary constructor 2. No-arg constructor (all fields w…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1066 — refactor: Decompose SynchronizerMetrics (registration vs recording)
- author zbnerd; CLOSED; created 2026-06-03T16:24:36Z; closed 2026-06-06T16:40:43Z; reason COMPLETED. Body: ## What to build `SynchronizerMetrics` (176 lines)이 두 독립 관심사에 대해 변경: 1. Meter registration (67 lines 보일러플레이트): counter/timer/summary/gauge 생성 2. Recor…
- discussion: 1 / Closed by PR #1178 (already merged to develop on 2026-06-06). Decomposes Synchro…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1178/MERGED]; reachable commit-message refs 1 [c6a0c724a228]; PR-record issue refs 1 [#1178/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1067 — refactor: Move ExpectationV6Controller.getStatus() logic to facade
- author zbnerd; CLOSED; created 2026-06-03T16:24:38Z; closed 2026-06-14T16:01:36Z; reason COMPLETED. Body: ## What to build `ExpectationV6Controller.getStatus()`가 controller에서 직접 `queryService.batchQuery()` + `cacheService.multiPut()` 호출. Infrastructure orc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1068 — arch: convert VirtualThreadExecutorManager from inline creation to @Bean injection
- author zbnerd; CLOSED; created 2026-06-03T16:25:21Z; closed 2026-06-04T03:32:06Z; reason COMPLETED. Body: ## What to build Replace the 6 inline VirtualThreadExecutorManager("name") creation sites across module-external-api (4 sites) and module-synchronizer…
- discussion: 1 / PR #1120에서 VT executor @Bean injection으로 전환 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [3f1a321f9bf8, 8bca3f1afc54, c4256927e7c6]; PR-record issue refs 1 [#1120/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1069 — arch: extract CalculatorEngineAutoConfiguration to module-core
- author zbnerd; CLOSED; created 2026-06-03T16:25:24Z; closed 2026-06-06T05:50:41Z; reason COMPLETED. Body: ## What to build Extract the 17-class @Import block in module-calculator's CalculatorEngineConfiguration into a single CalculatorEngineAutoConfigurati…
- discussion: 1 / Closed by PR #1170. **Outcome:** - New `CalculatorEngineAutoConfiguration` in `m…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1170/MERGED]; reachable commit-message refs 1 [39e7752830d6]; PR-record issue refs 1 [#1170/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1070 — test: fix test quality issues — naming, assertions, structure across 4 modules
- author zbnerd; CLOSED; created 2026-06-03T16:25:27Z; closed 2026-06-14T16:01:36Z; reason COMPLETED. Body: ## What to build Fix test quality issues across module-external-api, module-synchronizer, module-calculator, and module-rest-controller. This slice ad…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1071 — refactor: Remove dead dependencies across 4 modules
- author zbnerd; CLOSED; created 2026-06-03T16:36:39Z; closed 2026-06-06T13:55:19Z; reason COMPLETED. Body: ## What to build Remove injected fields that are never referenced by any method in their containing class. These are dead dependencies — injected via …
- discussion: 1 / Closed by refactor commits. Dead dependencies removed from 4 classes (V6ReadMetr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [a2bcd214242e]; PR-record issue refs 1 [#1177/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1072 — refactor: Deduplicate CalculationExecutionService completion methods
- author zbnerd; CLOSED; created 2026-06-03T16:36:42Z; closed 2026-06-14T16:01:37Z; reason COMPLETED. Body: ## What to build `CalculationExecutionService` (module-infra, 286 lines) has 4 near-identical completion methods: - `completeCalculation` and `complet…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1073 — refactor: Extract CalculationDispatchService from CalculationJobService (step 1/2)
- author zbnerd; CLOSED; created 2026-06-03T16:36:44Z; closed 2026-06-06T17:11:17Z; reason COMPLETED. Body: ## What to build `CalculationJobService` (module-infra, 245 lines, 7 fields, 16 methods) is a God Service mixing 4 orchestration concerns. This is ste…
- discussion: 1 / Closed via merged PR #1182. Step 1/2 of God Service decomposition complete. 6 PG…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1182/MERGED]; reachable commit-message refs 1 [2cf2e36b2b03]; PR-record issue refs 2 [#1182/MERGED, #1186/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1074 — refactor: Extract BatchResolver from BatchReadScheduler
- author zbnerd; CLOSED; created 2026-06-03T16:37:01Z; closed 2026-06-06T13:33:39Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler` (module-rest-controller, 161 lines, 7 dependencies) mixes 3 responsibilities: lifecycle management (SmartLifecyc…
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1075 — refactor: Fix PgmqPortAdapter.sendIfAbsent — eliminate raw JDBC bypass
- author zbnerd; CLOSED; created 2026-06-03T16:37:15Z; closed 2026-06-14T16:00:57Z; reason COMPLETED. Body: ## What to build `PgmqPortAdapter` (module-infra, 67 lines) has an inconsistent data access pattern. Three methods (`send`, `queueLength`, `findActive…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1076 — refactor: Relocate outbox query from CalculationJobPortAdapter to OutboxEventPortAdapter
- author zbnerd; CLOSED; created 2026-06-03T16:37:17Z; closed 2026-06-05T04:40:17Z; reason COMPLETED. Body: ## What to build `CalculationJobPortAdapter` (module-infra, 152 lines) has 18 methods. 16 of them use `jobRepository` for job lifecycle management. Bu…
- discussion: 1 / Closed by PR #1149. `findCompletedJobsMissingOutboxEvents` moved from `Calculati…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1149/MERGED]; reachable commit-message refs 1 [5ecaca1a7cee]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1077 — refactor: Split OcidMappingRepository into DB and Redis
- author zbnerd; CLOSED; created 2026-06-03T16:37:20Z; closed 2026-06-04T13:50:06Z; reason COMPLETED. Body: ## What to build `OcidMappingRepository` (module-synchronizer, 91 lines) has two methods with zero shared dependencies: - `batchUpsert()` uses only `j…
- discussion: 1 / Merged via #1140. OcidMappingRepository split into DB-only repo + OcidMappingRed…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1140/MERGED]; reachable commit-message refs 1 [38d72c7171db]; PR-record issue refs 1 [#1140/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1078 — refactor: Fix CalculatorResultCleanupScheduler abstraction leak
- author zbnerd; CLOSED; created 2026-06-03T16:37:22Z; closed 2026-06-14T16:00:58Z; reason COMPLETED. Body: ## What to build `CalculatorResultCleanupScheduler` (module-calculator, 104 lines) uses both `ObjectStorage` (abstracted interface) and raw `java.nio.…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1079 — [refactor] Calculator: Fix ObjectStorage bypass in cleanup scheduler
- author zbnerd; CLOSED; created 2026-06-03T16:37:29Z; closed 2026-06-14T16:00:58Z; reason COMPLETED. Body: ## What to build `CalculatorResultCleanupScheduler`의 `readDirectoryCreatedTime`과 `isRecentlyModified` 메서드가 `java.nio.file.Files`/`Paths`를 직접 사용하여 Obje…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1080 — [refactor] Calculator: Extract JSON parsing from SnapshotChunkProcessor
- author zbnerd; CLOSED; created 2026-06-03T16:37:31Z; closed 2026-06-06T13:33:37Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor`의 `parseLines`와 `calculateItem` 메서드가 비즈니스 로직과 JSON 파싱/직렬화를 혼합. `ObjectMapper`가 비즈니스 흐름 안에서 직접 사용됨. 혼합 지점: - `…
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1081 — [refactor] Rest-Controller: Decompose ReadModelQueryService.batchQuery
- author zbnerd; CLOSED; created 2026-06-03T16:37:33Z; closed 2026-06-06T16:59:27Z; reason COMPLETED. Body: ## What to build `ReadModelQueryService.batchQuery` (lines 23-87)가 3개의 근본적으로 다른 책임을 한 메서드에 혼합: 1. **SQL/DB**: 동적 SQL 생성 (pair predicate 문자열 조합), JDBC …
- discussion: 1 / Closed via PR #1181. ReadModelRowQuery / StalenessCheck / ReadModelDocumentExtra…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1181/MERGED]; reachable commit-message refs 4 [7eb7fa48a5aa, bef2a620e5f4, cf48f90ac95b, e5413c253711]; PR-record issue refs 1 [#1181/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1082 — [refactor] Rest-Controller: Extract multi-infra orchestration from BatchReadScheduler
- author zbnerd; CLOSED; created 2026-06-03T16:37:36Z; closed 2026-06-07T05:50:11Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler.resolveBatch` (lines 78-143)가 6개 책임을 단일 메서드에 혼합. 분석에서 심각도 1위. 혼합 책임: 1. Redis cache 읽기 (`multiGet`) 2. DB 조회 (`qu…
- discussion: 1 / Closed by refactor commits. resolveBatch and enqueue return typed sealed results…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [47d5028d9aac, c4001c8e087e, d8314f714848, f30c0baf724f]; PR-record issue refs 1 [#1183/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1083 — [refactor] Rest-Controller: Separate PopularCharacterService from Redis operations
- author zbnerd; CLOSED; created 2026-06-03T16:37:38Z; closed 2026-06-06T13:33:36Z; reason COMPLETED. Body: ## What to build `PopularCharacterService`가 비즈니스 정책과 Redis ZSET 연산을 직접 혼합. 혼합 지점: - `top` (lines 33-71): window clamping + degradation 판단 (비즈니스) + `re…
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1084 — [refactor] External-API: Extract JSON/file/MQ from UrgentCharacterRequestConsumer
- author zbnerd; CLOSED; created 2026-06-03T16:37:40Z; closed 2026-06-06T13:33:43Z; reason COMPLETED. Body: ## What to build `UrgentCharacterRequestConsumer`의 여러 메서드가 JSON 파싱 + 파일 I/O + Kafka publish를 비즈니스 로직과 혼합. 혼합 지점: - `processUrgentCharacterAsync` (line…
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1085 — refactor: Extract OCID + API orchestration from CalculationJobService (step 2/2)
- author zbnerd; CLOSED; created 2026-06-03T16:37:41Z; closed 2026-06-07T10:13:39Z; reason COMPLETED. Body: ## What to build Continues the decomposition started in #1073. After `CalculationDispatchService` is extracted, `CalculationJobService` still mixes 3 …
- discussion: 1 / Closed by squash-merge of PR #1186. Refactor extracts `OcidResolutionOrchestrato…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1186/MERGED]; reachable commit-message refs 1 [24e80a870d79]; PR-record issue refs 1 [#1186/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1086 — refactor: Extract RuleBasedAnalyzer from AiSreService
- author zbnerd; CLOSED; created 2026-06-03T16:37:43Z; closed 2026-06-06T13:58:27Z; reason COMPLETED. Body: ## What to build `AiSreService` (module-infra, 288 lines) contains a rule-based fallback analyzer embedded as private methods. Four methods (`analyzeB…
- discussion: 1 / Closed by refactor commits. RuleBasedAnalyzer owns the 4 pure rule-based helpers…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [a2bcd214242e]; PR-record issue refs 1 [#1177/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1087 — [refactor] External-API: Extract JSON parsing from OcidLookupPhase and RankingFetchPhase
- author zbnerd; CLOSED; created 2026-06-03T16:37:49Z; closed 2026-06-06T13:33:42Z; reason COMPLETED. Body: ## What to build `OcidLookupPhase`와 `RankingFetchPhase`가 HTTP 응답 JSON 파싱을 비즈니스 로직과 혼합. 혼합 지점: - `OcidLookupPhase.fetchAndCollectOcidAsync` (lines 185-…
- discussion: 1 / Closed by squash-merge of PR #1176 to develop. Refactor extracts the listed infr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1176/MERGED]; reachable commit-message refs 1 [9f6a93f1de3c]; PR-record issue refs 1 [#1176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1088 — [refactor] Synchronizer: Decompose OcidLookupRunConsumer flat consumer
- author zbnerd; CLOSED; created 2026-06-03T16:37:52Z; closed 2026-06-06T16:40:52Z; reason COMPLETED. Body: ## What to build `OcidLookupRunConsumer.consume` (lines 29-62)가 JSON 파싱 + 필터링 + DB upsert + Redis write + ACK를 flat하게 처리. 동일 모듈의 `BasicSnapshotChunkCo…
- discussion: 1 / Closed via PR #1179. OcidLookupService / BasicChunkIngestionService / ResultChun…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1179/MERGED]; reachable commit-message refs 6 [488c722b0fa5, 5b0152efcc64, 809ba34a190f, 99393d5179a0, 9b0a28d585e9, e76f4a50d2d9]; PR-record issue refs 1 [#1179/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1089 — [refactor] Synchronizer: Separate merge policy from SQL in OcidMappingRepository
- author zbnerd; CLOSED; created 2026-06-03T16:37:54Z; closed 2026-06-07T08:04:40Z; reason COMPLETED. Body: ## What to build `OcidMappingRepository.batchUpsert` (lines 24-67)가 merge/dedup 전략(비즈니스)을 raw SQL로 직접 구현. 수동 트랜잭션 관리도 혼재. 혼합 지점: - `batchUpsert` (24-6…
- discussion: 1 / Closed by refactor commits. Merge policy extracted to OcidMappingMergePolicy; @T…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc60d43e28fa]; PR-record issue refs 1 [#1184/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1090 — [refactor] Synchronizer: Extract infra from DefaultChunkProcessor
- author zbnerd; CLOSED; created 2026-06-03T16:37:57Z; closed 2026-06-06T09:39:11Z; reason COMPLETED. Body: ## What to build `DefaultChunkProcessor.process` (lines 29-65)가 도메인 변환 + metrics + DB + Redis를 한 메서드에 혼합. 혼합 지점: - Lines 34-35: OCID → userIgn 매핑 (도메인…
- discussion: 2 / Already resolved by PR #1143 which decomposed `DefaultChunkProcessor.process` in…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1143/MERGED]; reachable commit-message refs 3 [37b82573290d, b766ede36794, c6a0c724a228]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1091 — Clean Code: Extract shared endpoint name constants
- author zbnerd; CLOSED; created 2026-06-03T16:38:29Z; closed 2026-06-14T16:00:58Z; reason COMPLETED. Body: ## What to build Endpoint name string literals (`"character-basic"`, `"item-equipment"`, `"ranking-overall"`, `"ocid-lookup"`, `"result"`) are scatter…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1092 — Clean Code: Extract status code enum from stringly-typed pattern
- author zbnerd; CLOSED; created 2026-06-03T16:38:32Z; closed 2026-06-14T16:00:58Z; reason COMPLETED. Body: ## What to build Status codes `"SUCCESS"`, `"ERROR"`, `"SKIPPED"`, `"UNKNOWN"` are used as raw string literals across calculator and synchronizer modu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1093 — Clean Code: Replace magic numbers with named constants
- author zbnerd; CLOSED; created 2026-06-03T16:38:35Z; closed 2026-06-06T11:15:28Z; reason COMPLETED. Body: ## What to build Raw numeric literals throughout the codebase require mental arithmetic or external lookup to understand. Replace them with self-docum…
- discussion: 1 / Closed by squash-merge of PR #1175 to develop (merge commit b766ede3). Refactor …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1175/MERGED]; reachable commit-message refs 3 [37b82573290d, b766ede36794, c6a0c724a228]; PR-record issue refs 1 [#1175/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1094 — Clean Code: Externalize hardcoded operational params to config
- author zbnerd; CLOSED; created 2026-06-03T16:38:37Z; closed 2026-06-14T16:00:59Z; reason COMPLETED. Body: ## What to build Several operational parameters are hardcoded with no way to tune per environment. Externalize them to Spring configuration properties…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1095 — Clean Code: Fix calculator observability bugs
- author zbnerd; CLOSED; created 2026-06-03T16:39:28Z; closed 2026-06-14T16:00:59Z; reason COMPLETED. Body: ## What to build Three observability defects in `module-calculator` that silently hide processing information. ### Bug 1: sampleCount never resets **F…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1096 — Clean Code: Fix OcidLookupPhase silent data loss
- author zbnerd; CLOSED; created 2026-06-03T16:39:31Z; closed 2026-06-04T13:13:53Z; reason COMPLETED. Body: ## What to build Three silent data loss scenarios in the OCID lookup pipeline where records vanish without any logging or metrics. ### Bug 1: OCID nul…
- discussion: 1 / Bug 1 fixed by #1018. Bug 2 fixed by #1019 (PR #1136). Remaining Bug 3 (OcidUser…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1136/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1097 — fix: Executor lifecycle — 7개 컴포넌트 @PreDestroy 누수 수정
- author zbnerd; CLOSED; created 2026-06-03T16:39:33Z; closed 2026-06-04T00:56:46Z; reason COMPLETED. Body: ## What to build 인라인 생성된 Executor/ThreadPool/Scheduler가 Spring lifecycle에 의해 정상적으로 종료되지 않는 7개 컴포넌트를 수정. 컨텍스트 재배포 시 스레드 누적, 메모리 누수 발생. ### 대상 컴포넌트 1. *…
- discussion: 1 / PR #1114 merged to develop. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1114/MERGED]; reachable commit-message refs 1 [5b1a906477bd]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1098 — Clean Code: Clean up ChunkConsumerTemplate failure classification
- author zbnerd; CLOSED; created 2026-06-03T16:40:20Z; closed 2026-06-06T10:09:28Z; reason COMPLETED. Body: ## What to build Two readability defects in the synchronizer chunk consumer template that make failure handling fragile. ### Problem 1: String-matchin…
- discussion: 2 / Resolved by: 1. Renamed abstract method + 5 overrides on `ChunkExecutionStatus` …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [b766ede36794]; PR-record issue refs 1 [#1175/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1099 — Clean Code: Improve complex conditional readability
- author zbnerd; CLOSED; created 2026-06-03T16:40:22Z; closed 2026-06-14T16:00:59Z; reason COMPLETED. Body: ## What to build Several complex conditional blocks across modules where the business intent is buried in implementation details. Extract named predic…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1100 — fix: KafkaSnapshotChunkReadyConsumer DLQ 라우팅 수정 — 무한 재전손 방지
- author zbnerd; CLOSED; created 2026-06-03T16:40:24Z; closed 2026-06-04T12:30:37Z; reason COMPLETED. Body: ## What to build `KafkaSnapshotChunkReadyConsumer`가 코루틴 `scope.launch` 내부에서 예외를 catch하고 있어 Spring Kafka `DefaultErrorHandler`가 예외를 감지하지 못함. 결과적으로 DLQ …
- discussion: 1 / Fixed in 48689bedc (calculator DLQ routing). Tests pass.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [48689bedcf4a]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1101 — Clean Code: Improve variable naming in high-density files
- author zbnerd; CLOSED; created 2026-06-03T16:40:25Z; closed 2026-06-14T16:01:00Z; reason COMPLETED. Body: ## What to build Replace meaningless or abbreviated variable names across the worst-offending files. Each rename is a drop-in replacement with no beha…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1102 — fix: ForkJoinPool.commonPool() 제거 — 전용 executor 8건
- author zbnerd; CLOSED; created 2026-06-03T16:40:26Z; closed 2026-06-04T04:01:46Z; reason COMPLETED. Body: ## What to build `CompletableFuture.supplyAsync` / `runAsync` 호출 시 executor를 지정하지 않아 `ForkJoinPool.commonPool()`에 작업이 실행되는 8개 위치를 수정. commonPool은 DB-b…
- discussion: 1 / PR #1123에서 8개 commonPool 사용처 전부 전용 executor로 전환 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [33941a00331d, 6f22ef7dc825]; PR-record issue refs 1 [#1123/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1103 — Clean Code: Fix cross-module silent side effects
- author zbnerd; CLOSED; created 2026-06-03T16:40:28Z; closed 2026-06-14T16:01:00Z; reason COMPLETED. Body: ## What to build Several hidden side effects across modules where method names do not convey what actually happens. These require human review because…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1104 — fix: @Transactional 위생 — readOnly 명시, scope 축소, @Async+TX 분리
- author zbnerd; CLOSED; created 2026-06-03T16:40:29Z; closed 2026-06-05T04:37:31Z; reason COMPLETED. Body: ## What to build 프로젝트 규칙(`data-access.md`) 위반: `@Transactional`에 `readOnly`와 `transactionManager` 명시 누락, 트랜잭션 범위 과다, `@Async`+`@Transactional` 조합. ###…
- discussion: 1 / Merged via #1147 (0279b380). 5 fix areas applied: CalculationJobService 16 readO…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1147/MERGED]; reachable commit-message refs 2 [0279b38060b2, 2c6a9d34f0b3]; PR-record issue refs 1 [#1147/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1105 — fix: CallerRunsPolicy → AbortPolicy 전환 (taskExecutor, backfillExecutor)
- author zbnerd; CLOSED; created 2026-06-03T16:40:31Z; closed 2026-06-04T00:56:44Z; reason COMPLETED. Body: ## What to build `ExecutorConfig.kt`의 `taskExecutor` (line 208)와 `backfillExecutor` (line 314)가 `CallerRunsPolicy`를 사용. 프로젝트 규칙(`async-concurrency.md`…
- discussion: 1 / PR #1113 merged to develop. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1113/MERGED]; reachable commit-message refs 1 [18249926e051]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1106 — fix: AbstractExpectationCalcWorker N+1 → 배치 업서트
- author zbnerd; CLOSED; created 2026-06-03T16:41:24Z; closed 2026-06-14T16:01:00Z; reason COMPLETED. Body: ## What to build `AbstractExpectationCalcWorker.batchViewUpsert()` (line 164-177)에서 bulk upsert 직후 동일 결과에 대해 N개 개별 DB 호출 발생. 프로젝트 규칙(`data-access.md`)…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#1142/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1107 — fix: DB 접근 패턴 — findAll 페이징, 배치 청킹, 루프 INSERT
- author zbnerd; CLOSED; created 2026-06-03T16:41:27Z; closed 2026-06-14T16:01:01Z; reason COMPLETED. Body: ## What to build 3개 DB 접근 안티패턴 수정. ### 1. findAll() 무페이징 3건 파일 라인 용도 ------ ------ ------ `GameCharacterRepositoryImpl.kt` 40 전체 테이블 로드 `CharacterOcid…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1108 — fix: Backpressure — OcidLookup/SnapshotFetch/UrgentConsumer 동시성 제한
- author zbnerd; CLOSED; created 2026-06-03T16:41:30Z; closed 2026-06-04T02:50:05Z; reason COMPLETED. Body: ## What to build 3개 위치에서 동시 실행 수 제한 없이 대규모 fan-out 발생. 외부 API 과부하 및 스레드 풀 고갈 위험. ### 1. OcidLookupPhase — 배치당 1000개 동시 fan-out `OcidLookupPhase.kt:175…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0fc8ea516398]; PR-record issue refs 1 [#1117/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1109 — fix: Blocking-in-async — TieredCache join, SingleFlight, WebClient .block()
- author zbnerd; CLOSED; created 2026-06-03T16:41:32Z; closed 2026-06-04T02:49:43Z; reason COMPLETED. Body: ## What to build 비동기 API 내부에서 동기 블로킹 발생하는 4개 위치 수정. ### 1. TieredCache — 요청 스레드에서 L2 버퍼 .join() `TieredCache.kt:120` — `buffer.submit(key).join()`이 요청…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [4e66ba3cd030, dc61fd63b584]; PR-record issue refs 2 [#1118/MERGED, #1121/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1110 — fix: 관측성 — PgmqClient CB 메트릭 + InflightRequestRegistry 정리
- author zbnerd; CLOSED; created 2026-06-03T16:42:18Z; closed 2026-06-14T16:01:01Z; reason COMPLETED. Body: ## What to build silent failure 가시화 2건. ### 1. PgmqClient 서킷 브레이커 오픈 시 silent failure `PgmqClient.kt:467-468` — CB 오픈 시 `readFallback()`이 빈 리스트 반환. 워커…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1111 — fix: KafkaEventPublisher STUB — silent data loss 경고
- author zbnerd; CLOSED; created 2026-06-03T16:42:21Z; closed 2026-06-04T12:30:39Z; reason COMPLETED. Body: ## What to build `KafkaEventPublisher.kt:41-52`가 실제 Kafka 발행 없이 WARN 로그만 남김. `app.event-publisher.type=kafka` 설정 활성화 시 이벤트(NEXON_DATA_COLLECTED 등) 조용히…
- discussion: 1 / Fixed in 0a4b1ddae (infra KafkaEventPublisher @PostConstruct ERROR log).. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0a4b1ddae543]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1112 — arch: ExternalApiWorker 파이프라인 분해 — 15초+ 동기 블로킹 해소
- author zbnerd; CLOSED; created 2026-06-03T16:42:23Z; closed 2026-06-04T06:26:40Z; reason COMPLETED. Body: ## What to build `ExternalApiWorker.process()`가 7단계 파이프라인 전체를 단일 PGMQ 워커 스레드에서 동기 실행. 총 블로킹 15초+ 가능. 전체 파이프라인에서 가장 큰 throughput 병목. ### 현재 동작 (단일 메시지당…
- discussion: 1 / PR #1132 merged. ExternalApiWorker pipeline converted to CF chaining.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1132/MERGED]; reachable commit-message refs 1 [0ad17c0afe37]; PR-record issue refs 1 [#1132/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1125 — arch: IO바운드(VT) / CPU바운드(코루틴 Dispatchers.Default) 분리 패턴 확립
- author zbnerd; CLOSED; created 2026-06-04T05:53:12Z; closed 2026-06-08T12:39:19Z; reason COMPLETED. Body: ## What to build 모든 활성 모듈(module-external-api, module-synchronizer, module-calculator, module-rest-controller)에 적용할 IO/CPU 분리 패턴 확립. **원칙:** - IO-boun…
- discussion: 1 / 완료. PR #1199 머지됨 (commit a6a07c661). ADR-723 + Guide §23 IO/CPU split pattern 확립…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1199/MERGED]; reachable commit-message refs 6 [22f1ffa80408, 27543e0f1758, 763e97307397, 9c020689b9f1, a6a07c661d36, ac20c4672e8f]; PR-record issue refs 1 [#1199/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1126 — infra: expectationComputeExecutor IO/CPU 분리 + taskExecutor bean naming 명확화
- author zbnerd; CLOSED; created 2026-06-04T05:53:46Z; closed 2026-06-08T12:39:21Z; reason COMPLETED. Body: ## What to build `InfraExecutorConfig`의 `expectationComputeExecutor`가 IO와 CPU 작업을 모두 처리하도록 정의되어 있음. Bean 이름은 "compute"이지만 docstring에 "parsing/calculat…
- discussion: 1 / 완료. PR #1200 머지됨 (commit bcbda2f96). executor bean rename + expectationComputeEx…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1200/MERGED]; reachable commit-message refs 14 [150da0f50f9a, 22d9d77253e2, 232ecb110a54, 43506c57b2fe, 616fbaf82e93, 66bce9ac5339, 6835af99e8d2, 6ad9ea047c33, 790e91bcd8af, 8a837fe57fcc, afe2c25f77df, bcbda2f96dfc, c91782ea43b1, dede92163c50]; PR-record issue refs 1 [#1200/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1127 — calculator: SnapshotChunkProcessor parse/calc worker count 독립 설정화
- author zbnerd; CLOSED; created 2026-06-04T05:53:49Z; closed 2026-06-08T12:39:23Z; reason COMPLETED. Body: ## What to build module-calculator의 SnapshotChunkProcessor는 이미 잘 설계됨: - IO stage (file read/write): `Dispatchers.IO` - CPU stage (JSON parse + 계산): `D…
- discussion: 1 / 완료. PR #1203 머지됨 (commit e3d1006dd). calculator parse/calc worker split + dispat…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1203/MERGED]; reachable commit-message refs 8 [22c4cab42385, 84c150c7aff8, 85d31fb194e0, 9145a4b95897, a2cc3e5f8d84, a9ffb7bd6803, db13ebe43fc5, e3d1006dd702]; PR-record issue refs 1 [#1203/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1128 — external-api: CPU 작업(JSON parse, GZIP, SHA-256)을 Dispatchers.Default로 offload
- author zbnerd; CLOSED; created 2026-06-04T05:54:32Z; closed 2026-06-08T12:39:25Z; reason COMPLETED. Body: ## What to build module-external-api의 모든 VT executor가 CPU-heavy 작업을 inline 실행. `Dispatchers.Default` 또는 `Dispatchers.IO` 사용 전무. JSON parse, GZIP compr…
- discussion: 1 / 완료. PR #1207 머지됨 (commit 10c794a70). 5 file CPU offload. 2 file (SnapshotFetchPh…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1207/MERGED]; reachable commit-message refs 13 [0344c62b717f, 0a8ef7baba42, 10c794a70551, 27543e0f1758, 2fd370041aed, 56b5a79a9481, 6c8bb2462cf1, 763e97307397, 856869c779b5, 8a837fe57fcc, 91884abfa983, ac20c4672e8f, be7a84c51a00]; PR-record issue refs 1 [#1207/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1129 — synchronizer: CPU 작업 Dispatchers.Default offload + OcidLookupRunConsumer executor dispatch
- author zbnerd; CLOSED; created 2026-06-04T05:54:35Z; closed 2026-06-08T13:19:24Z; reason COMPLETED. Body: ## What to build module-synchronizer의 모든 VT executor가 CPU-heavy 작업을 inline 실행. 코루틴 사용 전무. JSON parse, GZIP compress/decompress, SHA-256, 대규모 collectio…
- discussion: 1 / 완료. PR #1210 머지됨 (commit d2307e65e). 5 file CPU offload + OcidLookupRunConsumer …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1210/MERGED]; reachable commit-message refs 11 [15ad61fe1f51, 27543e0f1758, 43506c57b2fe, 67d4f27ccc91, 69c182631f0b, 763e97307397, ac20c4672e8f, b9a35a460890, bd38ce816dd8, d2307e65ed4b, f05a70e1266a]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #1130 — rest-controller: BatchReadScheduler IO+CPU 혼합 분리, ReadModelQueryService gzip+JSON 코루틴 offl…
- author zbnerd; CLOSED; created 2026-06-04T05:54:42Z; closed 2026-06-08T13:19:26Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler`의 `@Scheduled(fixedDelay=10ms)` thread에서 Redis multiGet + JDBC query + per-row gzip decompress + JSON parse + Red…
- discussion: 1 / 완료. PR #1211 머지됨 (commit ca4728734). ReadModelQueryService.batchQuery() + Expect…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1211/MERGED]; reachable commit-message refs 7 [27543e0f1758, 2c5bc1ec8f60, 763e97307397, 8a837fe57fcc, ac20c4672e8f, ca472873421a, cf0bcf2c82d9]; PR-record issue refs 1 [#1211/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1131 — infra: ExternalApiWorker 계산+GZIP+SHA-256 Dispatchers.Default offload, PgmqWorker Dispatche…
- author zbnerd; CLOSED; created 2026-06-04T05:54:44Z; closed 2026-06-08T13:19:29Z; reason COMPLETED. Body: ## What to build **ExternalApiWorker.runCalculationAndComplete** (lines ~276-330): - `pureCalculationPort.calculate()` (확률 계산, Markov chain, DP convol…
- discussion: 1 / 완료. PR #1212 머지됨 (commit 30326c780). ExternalApiWorker.runCalculationAndComplete…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1212/MERGED]; reachable commit-message refs 7 [0e8e0c5a8d5a, 27543e0f1758, 30326c780e5b, 341bb75fe854, 746af5c67eda, 763e97307397, ac20c4672e8f]; PR-record issue refs 1 [#1212/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1138 — fix: OcidUserIgnResolver silent data loss on null/empty user_ign
- author zbnerd; CLOSED; created 2026-06-04T13:13:56Z; closed 2026-06-04T13:18:40Z; reason COMPLETED. Body: OCID lookup pipeline silently drops OCIDs when their corresponding user_ign is null/empty in character_basic_read_model. ## Source - ## Current behavi…
- discussion: 1 / Fixed in #1139 — aggregate debug log added to OcidUserIgnResolver.resolve() show…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1139/MERGED]; reachable commit-message refs 4 [07a4b395562a, 092db1a86674, 14aa19f94023, 5b79a7497ed5]; PR-record issue refs 1 [#1139/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1150 — refactor: remove 18 dead outbound ports (follow-up #897)
- author zbnerd; CLOSED; created 2026-06-05T04:47:50Z; closed 2026-06-14T16:01:01Z; reason COMPLETED. Body: ## Background Issue #897 감사 결과 49개 아웃바운드 포트 중 18개가 Dead seam (prod 어댑터 0개). 모두 호출자도 없거나, 호출자가 존재하지만 인터페이스 구현체 자체가 없음. ## Dead seam inventory (from spe…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1151 — refactor: merge Like ports 6→2 (follow-up #897)
- author zbnerd; CLOSED; created 2026-06-05T04:48:00Z; closed 2026-06-06T05:16:26Z; reason COMPLETED. Body: ## Background Issue #897에서 식별된 Like 관련 포트 6개의 책임 중복을 해소하기 위한 병합 작업. ## Target ports - `LikeSyncPort` (Dead) - `LikeRelationSyncPort` (Dead) - `LikeEve…
- discussion: 1 / Closed by PR #1160. The original 6→2 merge sketch in this issue was based on ass…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1160/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1152 — refactor: merge Monitoring ports 7→2 (follow-up #897)
- author zbnerd; CLOSED; created 2026-06-05T04:48:09Z; closed 2026-06-14T16:01:01Z; reason COMPLETED. Body: ## Background Issue #897에서 식별된 Monitoring 관련 포트 7개의 책임 중복을 해소하기 위한 병합 작업. ## Target ports - `AlertPort` (Dead — 별도 이슈 #898에서 처리 권장) - `AlertPublisher`…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1153 — investigate: GameCharacterPort callers exist but no impl
- author zbnerd; CLOSED; created 2026-06-05T04:48:22Z; closed 2026-06-05T04:56:49Z; reason COMPLETED. Body: ## Background Issue #897 감사에서 `GameCharacterPort`가 Dead seam (prod 어댑터 0개) 으로 분류됨. 그러나 호출자 3곳 존재: - `module-infra/.../worker/ExpectationCalcWorker.kt`…
- discussion: 1 / 조사 완료. PR #1156 (ADR-392) 으로 보고. Findings: - GameCharacterPort 미완성 추출 (3d0911f62…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1156/MERGED]; reachable commit-message refs 2 [606f2e5f7eec, 89de860e28e0]; PR-record issue refs 1 [#1156/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1161 — refactor: extract module-executor from module-infra (#907.1)
- author zbnerd; CLOSED; created 2026-06-06T05:36:21Z; closed 2026-06-14T16:01:02Z; reason COMPLETED. Body: ## Background Follow-up to #907 (module-infra 444-file God Module decomposition, ADR-050). 1순위 추출 대상. ## Scope Extract 25 .kt files from `module-infra…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1162 — refactor: extract module-persistence from module-infra (#907.2)
- author zbnerd; CLOSED; created 2026-06-06T05:36:35Z; closed 2026-06-14T16:01:02Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 2순위 추출. ~49 files, ~4,260 LOC (JPA, QueryDSL, repository). ## Scope Extract `module-infra/.../infrastructur…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1163 — refactor: extract module-monitoring from module-infra (#907.3)
- author zbnerd; CLOSED; created 2026-06-06T05:36:39Z; closed 2026-06-14T16:01:02Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 3순위. ~48 files, ~4,088 LOC (Prometheus, alerts, observability). ## Scope Extract `module-infra/.../infrastr…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1164 — refactor: extract module-cache from module-infra (#907.4)
- author zbnerd; CLOSED; created 2026-06-06T05:36:42Z; closed 2026-06-14T16:01:03Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 4순위. ~29 files, ~3,595 LOC (Caffeine L1, PostgreSQL UNLOGGED L2, SingleFlight). ## Scope Extract `module-in…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1165 — refactor: extract module-pgmq from module-infra (#907.5)
- author zbnerd; CLOSED; created 2026-06-06T05:36:45Z; closed 2026-06-14T16:01:03Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 5순위. ~33 files, ~3,392 LOC (PGMQ wrapper + messaging + queue + event). ## Scope Extract `module-infra/.../i…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1166 — refactor: extract module-aop from module-infra (#907.6)
- author zbnerd; CLOSED; created 2026-06-06T05:36:47Z; closed 2026-06-14T16:01:03Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 6순위. ~35 files, ~3,528 LOC (retry, lock, singleflight, concurrency AOP). ## Scope Extract `module-infra/...…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1167 — refactor: extract module-external-client from module-infra (#907.7)
- author zbnerd; CLOSED; created 2026-06-06T05:36:49Z; closed 2026-06-14T16:01:04Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 7순위. ~20 files, ~2,145 LOC (Nexon API WebClient wrapper). ## Scope Extract `module-infra/.../infrastructure…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1168 — refactor: extract module-security from module-infra (#907.8)
- author zbnerd; CLOSED; created 2026-06-06T05:36:51Z; closed 2026-06-14T16:01:04Z; reason COMPLETED. Body: ## Background Follow-up to #907 + ADR-050. 8순위. ~9 files, ~1,034 LOC (SecurityFilterChain + auth helpers). ## Scope Extract `module-infra/.../infrastr…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1188 — refactor(synchronizer): remove DefaultChunkProcessor delegate, inject ChunkPipelineOrchest…
- author zbnerd; CLOSED; created 2026-06-07T10:26:28Z; closed 2026-06-14T16:01:04Z; reason COMPLETED. Body: ## What to build Follow-up to #990. `DefaultChunkProcessor` is a 1-line `@Deprecated` delegate. Once #1187 (PR2 of #990) is merged, the migration of t…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 2 [#2/MERGED, #1187/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1189 — refactor(synchronizer): port interface migration — ChunkDataReader/ChunkDocumentTransforme…
- author zbnerd; CLOSED; created 2026-06-07T10:26:41Z; closed 2026-06-14T16:01:05Z; reason COMPLETED. Body: ## What to build Follow-up to #990. The spec `docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md` §6 PR2 originally included making the 3 s…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#2/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1198 — infra: expose ForkJoinPool.commonPool().activeThreadCount as Prometheus metric
- author zbnerd; CLOSED; created 2026-06-08T06:05:14Z; closed 2026-06-08T13:19:31Z; reason COMPLETED. Body: ## Background ADR-723 (IO/CPU split pattern) 의 §4 Result/Evidence 에서 cross-module saturation detection 을 위해 `ForkJoinPool.commonPool().activeThreadCou…
- discussion: 1 / 완료. PR #1213 머지됨 (commit 779028bff). ForkJoinPool.commonPool() 3 Prometheus Gaug…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1213/MERGED]; reachable commit-message refs 3 [2d3c9ac4c5d8, 779028bff87c, b4480ab4212e]; PR-record issue refs 1 [#1213/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1201 — refactor(calculator): deprecate legacy workerCount field
- author zbnerd; CLOSED; created 2026-06-08T08:52:07Z; closed 2026-06-08T13:58:05Z; reason COMPLETED. Body: ## Background Issue #1127 introduced `parseWorkers` + `calcWorkers` as independent fields. The legacy `workerCount` field (in `PipelineProperties`) is…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [6935da34ca0d, a2cc3e5f8d84]; PR-record issue refs 1 [#1214/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1202 — refactor(calculator): register parseDispatcher/calcDispatcher as named Spring beans
- author zbnerd; CLOSED; created 2026-06-08T08:52:10Z; closed 2026-06-08T14:00:54Z; reason COMPLETED. Body: ## Background Issue #1127 introduced `parseDispatcher`/`calcDispatcher` as YAML String → CoroutineDispatcher via `CoroutineDispatcherConverter`. Limit…
- discussion: 1 / named beans refactor 는 본 세션 scope 외. PipelineProperties 의 parseDispatcher/calcDi…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [a2cc3e5f8d84]; PR-record issue refs 1 [#1214/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1204 — refactor(ext-api): apply CPU offload to SnapshotFetchPhase.kt when reintroduced
- author zbnerd; CLOSED; created 2026-06-08T10:12:11Z; closed 2026-06-08T14:00:18Z; reason COMPLETED. Body: ## Background Issue #1128's body listed 6 files for CPU offload. During implementation (PR #XXXX), #986 refactor was discovered to have dropped `Snaps…
- discussion: 1 / 재도입 미진행. SnapshotFetchPhase.kt 는 #986 에서 dropped. 본 세션 scope 외. 후속 작업 시 git hist…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1205 — refactor(ext-api): apply CPU offload to UrgentCharacterRequestConsumer.publishUrgentChunkA…
- author zbnerd; CLOSED; created 2026-06-08T10:12:13Z; closed 2026-06-08T14:00:20Z; reason COMPLETED. Body: ## Background Issue #1128's body listed UrgentCharacterRequestConsumer for CPU offload in `publishUrgentChunkAsync()` (GZIP compression + JSON seriali…
- discussion: 1 / 재도입 미진행. UrgentChunkArtifactWriter.kt 는 develop HEAD 에 없음 (worktree-only). 후속 작업…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1206 — refactor(ext-api): verify AuthCharacterFetchConsumer executor type (VT vs platform)
- author zbnerd; CLOSED; created 2026-06-08T10:12:14Z; closed 2026-06-08T13:57:59Z; reason COMPLETED. Body: ## Background Issue #1128's spec noted that `AuthCharacterFetchConsumer.publishResponse()` JSON serialize was offloaded to `Dispatchers.Default`. The …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2fd370041aed, a7880e8f58df]; PR-record issue refs 1 [#1215/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1208 — refactor(sync): mitigate multi-threaded runBlocking risk in 4 synchronizer files
- author zbnerd; CLOSED; created 2026-06-08T12:57:32Z; closed 2026-06-08T13:59:22Z; reason COMPLETED. Body: ## Background Issue #1129 applied runBlocking(Dispatchers.Default) to 4 files (EquipmentDocumentPreparer, ResultFileReader, BasicChunkFileReader, Equi…
- discussion: 1 / 검증 완료 (no PR, comment only). 4 file (EquipmentDocumentPreparer, ResultFileReader…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [2fd370041aed]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #942 — Refactor module-external-api naming: split SchedulerPhaseUtils, rename process/handle meth…
- author zbnerd; CLOSED; created 2026-06-03T15:41:46Z; closed 2026-06-14T16:00:30Z; reason COMPLETED. Body: ## What to build Rename vague names in `module-external-api` to intention-revealing alternatives. The highest-impact change is splitting the grab-bag …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #943 — DRY: module-common 공통 유틸리티 추출 (KafkaConsumerConfig, CompressionUtils, sha256Hex)
- author zbnerd; CLOSED; created 2026-06-03T15:41:48Z; closed 2026-06-14T16:00:31Z; reason COMPLETED. Body: ## What to build 3개 모듈(calculator, synchronizer, external-api)에 바이트 단위 동일한 코드를 module-common으로 추출. ### 1. KafkaConsumerConfig auto-configuration - `mo…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [f41a32084675]; PR-record issue refs 1 [#1144/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #944 — Refactor module-rest-controller naming: result variables, exception handler, cache service
- author zbnerd; CLOSED; created 2026-06-03T15:41:49Z; closed 2026-06-14T16:00:31Z; reason COMPLETED. Body: ## What to build Rename vague names in `module-rest-controller` to intention-revealing alternatives. The most pervasive anti-pattern is `val result` (…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #945 — DRY: rest-controller 중복 Port 구현체 제거 (LikeTogglePort, CharacterOcidPort)
- author zbnerd; CLOSED; created 2026-06-03T15:42:14Z; closed 2026-06-14T16:00:31Z; reason COMPLETED. Body: ## What to build rest-controller가 module-infra와 동일 Port 인터페이스를 각각 별도 JDBC 구현체로 중복 구현. 캐시 누락, unsafe cast, 에러 처리 불일치 발생. ### LikeTogglePort - rest-cont…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #946 — DRY: VirtualThreadExecutorManager shutdown 보일러플레이트 6곳 통일
- author zbnerd; CLOSED; created 2026-06-03T15:42:36Z; closed 2026-06-14T16:00:32Z; reason COMPLETED. Body: ## What to build 6곳에서 `VirtualThreadExecutorManager` 생성 + shutdown 보일러플레이트 반복. 과거 4번 shutdown 누락으로 리소스 누수 발생. ### 중복 위치 - `module-external-api/AuthCha…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #947 — DRY: calculator 내부 중복 제거 (consume/consumeUrgent, toCalculationInput/toCalculationResult)
- author zbnerd; CLOSED; created 2026-06-03T15:42:53Z; closed 2026-06-14T16:00:32Z; reason COMPLETED. Body: ## What to build calculator 모듈 내부 2가지 중복 제거. ### 1. consume() / consumeUrgent() 중복 - `module-calculator/.../consumer/KafkaSnapshotChunkReadyConsumer.k…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #948 — DRY: rest-controller 에러 응답 표준화 (3가지 JSON 형태 통일 + Redis ZSet + 503 상수)
- author zbnerd; CLOSED; created 2026-06-03T15:43:12Z; closed 2026-06-14T16:00:32Z; reason COMPLETED. Body: ## What to build rest-controller 모듈 내부 3가지 DRY 위반 해결. ### 1. 에러 응답 JSON 형태 3가지 통일 - `RestControllerExceptionHandler.kt:22` → `{status, code, message, …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #949 — DRY: module-infra 공통 인프라 패턴 추출 (VolumeMetrics, KafkaEventPublisher, GuardedTrigger)
- author zbnerd; CLOSED; created 2026-06-03T15:43:39Z; closed 2026-06-14T16:00:32Z; reason COMPLETED. Body: ## What to build calculator + external-api에서 동일 구조의 인프라 패턴을 module-infra에 추출. ### 1. VolumeMetrics 베이스 클래스 - `module-calculator/.../metrics/Calculator…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #950 — DRY: LocalObjectStorage 중복 제거 (calculator + external-api → module-common)
- author zbnerd; CLOSED; created 2026-06-03T15:43:56Z; closed 2026-06-14T16:00:33Z; reason COMPLETED. Body: ## What to build calculator + external-api의 LocalObjectStorage 디렉토리 연산이 거의 동일. module-common으로 통합. ### 중복 위치 - `module-calculator/.../storage/LocalObj…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #951 — DRY: external-api 내부 중복 제거 (BatchProcessor, ChunkRecord factory, PublisherConfig, JSONL bu…
- author zbnerd; CLOSED; created 2026-06-03T15:44:19Z; closed 2026-06-14T16:00:33Z; reason COMPLETED. Body: ## What to build external-api 모듈 내부 4가지 중복 제거. ### 1. RateLimitedBatchProcessor<T> 추출 - `OcidLookupPhase.kt:150-183`, `SnapshotFetchPhase.kt:154-203`,…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #952 — DRY: synchronizer 내부 중복 제거 (GzipJsonlReader, unnestUpsert, endpoint filter)
- author zbnerd; CLOSED; created 2026-06-03T15:44:40Z; closed 2026-06-14T16:00:33Z; reason COMPLETED. Body: ## What to build synchronizer 모듈 내부 3가지 중복 제거. ### 1. GzipJsonl 파일 읽기 통합 + missing-file 에러 전략 통일 - `ResultFileReader.kt:22-52` — `throw IllegalStateEx…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [dd8438fe623f]; PR-record issue refs 1 [#1145/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #953 — refactor: Introduce CalculationStatus enum to replace magic string status in Calculator
- author zbnerd; CLOSED; created 2026-06-03T15:55:14Z; closed 2026-06-14T16:00:34Z; reason COMPLETED. Body: ## What to build Replace the untyped `String` status field (`"SUCCESS"`, `"SKIPPED"`, `"ERROR"`) in `CalculationResult` with a typed `CalculationStatu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #954 — refactor: Clock 인프라 + module-rest-controller Instant.now() 전환
- author zbnerd; CLOSED; created 2026-06-03T15:55:27Z; closed 2026-06-14T16:00:34Z; reason COMPLETED. Body: ## What to build `java.time.Clock` Bean을 도입하고 module-rest-controller의 하드코딩된 시간 호출 11건을 `Instant.now(clock)` / `clock.millis()` 로 전환한다. 가장 작은 활성 모듈로 Cl…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #955 — refactor: Type-safe status and grade fields in module-core
- author zbnerd; CLOSED; created 2026-06-03T15:55:32Z; closed 2026-06-14T16:00:34Z; reason COMPLETED. Body: ## What to build Replace stringly-typed fields in module-core with existing domain enums. Two changes: ### Change 1: `EquipmentCalculationInput.potent…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #956 — refactor: Extract parameterized executor factory in ExecutorConfig
- author zbnerd; CLOSED; created 2026-06-03T15:55:50Z; closed 2026-06-14T16:00:35Z; reason COMPLETED. Body: ## What to build Replace 5 nearly-identical executor bean methods in `ExecutorConfig` with a single parameterized private factory method. Each current…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #957 — refactor: module-calculator Instant.now() / System.nanoTime() → Clock 전환
- author zbnerd; CLOSED; created 2026-06-03T15:55:57Z; closed 2026-06-14T16:00:35Z; reason COMPLETED. Body: ## What to build module-calculator의 하드코딩된 시간 호출 6건을 `Clock` 주입으로 전환한다. #954에서 확립한 패턴을 그대로 적용. ### 대상 (module-calculator) 파일 패턴 라인 용도 ------ ------ ---…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #958 — refactor: module-synchronizer Instant.now() / System.nanoTime() → Clock 전환
- author zbnerd; CLOSED; created 2026-06-03T15:56:00Z; closed 2026-06-14T16:00:35Z; reason COMPLETED. Body: ## What to build module-synchronizer의 하드코딩된 시간 호출 10건을 `Clock` 주입으로 전환한다. 도메인 필드(`calculatedAt`)와 retry/lease 분기 로직이 포함되어 가장 높은 테스트 가치를 제공. ### 대상 (mo…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #959 — refactor: Enrich UrgentReadState with behavior as sealed class
- author zbnerd; CLOSED; created 2026-06-03T15:56:08Z; closed 2026-06-06T09:21:10Z; reason COMPLETED. Body: ## What to build Convert `UrgentReadState` from a bare enum to a sealed class hierarchy where each state encapsulates its own behavior for: queue posi…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [0dc0042596e0, 280329c29603, 8aa3d249753b, c2eaa6434ef0, d88f12ac72e1]; PR-record issue refs 1 [#1172/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #960 — refactor: Consolidate ChunkExecutionStatus state machine into sealed class
- author zbnerd; CLOSED; created 2026-06-03T15:56:28Z; closed 2026-06-06T09:21:09Z; reason COMPLETED. Body: ## What to build Consolidate the scattered `ChunkExecutionStatus` enum comparison logic in `ChunkConsumerTemplate` into a sealed class hierarchy where…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 11 [03b346993b6f, 0dc0042596e0, 1c186ced4bf3, 280329c29603, 6bbac2afd04e, 6fb8d5918979, a70486d31795, af938d11f568, b237c451b0f9, beba914c2bc1, e49f850e0739]; PR-record issue refs 1 [#1171/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #961 — refactor: module-external-api Instant.now() / System.nanoTime() 48건 → Clock 전환
- author zbnerd; CLOSED; created 2026-06-03T15:56:34Z; closed 2026-06-07T05:50:10Z; reason COMPLETED. Body: ## What to build module-external-api의 하드코딩된 시간 호출 48건을 `Clock` 주입으로 전환한다. 가장 많은 발생 건수. `SchedulerPhaseUtils` God Object 내부 호출은 제외 (#961에서 별도 처리). ### …
- discussion: 1 / Closed by refactor commits. Clock injected into 11 classes (SnapshotFetchPhase r…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [d8314f714848]; PR-record issue refs 1 [#1183/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #962 — refactor: IdGenerator 인터페이스 도입 + UUID.randomUUID() 11건 전환
- author zbnerd; CLOSED; created 2026-06-03T15:56:37Z; closed 2026-06-14T16:00:36Z; reason COMPLETED. Body: ## What to build `UUID.randomUUID()` 직접 호출 11건을 `IdGenerator` 인터페이스 주입으로 전환. 이벤트 ID, run ID 등이 테스트에서 결정적이 되도록 개선. ### 인터페이스 정의 ```kotlin // module-com…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #963 — refactor: Deduplicate consumer, serializer, and metrics boilerplate across 3 modules
- author zbnerd; CLOSED; created 2026-06-03T15:56:46Z; closed 2026-06-14T16:01:50Z; reason COMPLETED. Body: ## What to build Three independent deduplication tasks across 3 modules. Each is small and standalone. ### Task A: Deduplicate KafkaSnapshotChunkReady…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #964 — refactor: CalculationJob에 상태 전이 메서드 추가
- author zbnerd; CLOSED; created 2026-06-03T15:56:53Z; closed 2026-06-14T16:01:50Z; reason COMPLETED. Body: ## What to build `module-core`의 `CalculationJob` data class에 상태 머신 전이 메서드를 추가한다. 현재 17개 필드를 가진 anemic data class로, 상태 전이 판단이 모든 호출자에 분산되어 있다. `module-…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #965 — refactor: Replace string-based endpoint dispatch with enum dispatch
- author zbnerd; CLOSED; created 2026-06-03T15:57:03Z; closed 2026-06-14T16:01:51Z; reason COMPLETED. Body: ## What to build Replace two string-based `when` dispatches with typed enum dispatch. ### Change 1: SnapshotChunkingProperties.configFor() **File:** `…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #966 — refactor: SchedulerPhaseUtils God Object 분해 — Clock, Thread.sleep, Files 숨김 제거
- author zbnerd; CLOSED; created 2026-06-03T15:57:12Z; closed 2026-06-07T11:36:58Z; reason COMPLETED. Body: ## What to build `SchedulerPhaseUtils` (`internal object`)가 `Instant.now()` 6건, `Thread.sleep` 3건, `Files.write`/`Files.createDirectories`를 모두 숨기고 있음.…
- discussion: 1 / Closed by squash-merge of PR #1194. `SchedulerPhaseUtils` (God Object, internal …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1194/MERGED]; reachable commit-message refs 1 [3dfa32898d8d]; PR-record issue refs 1 [#1194/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #967 — refactor: ChunkFileStorage 인터페이스 추출 — 파일 시스템 직접 접근 6건 제거
- author zbnerd; CLOSED; created 2026-06-03T15:57:15Z; closed 2026-06-14T16:01:51Z; reason COMPLETED. Body: ## What to build synchronizer와 calculator에서 `Paths.get()` + `Files.exists()` + `GZIPInputStream(Files.newInputStream())` 직접 호출 6건을 `ChunkFileStorage` …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #968 — refactor: ChunkExecutionState에 상태 판단 로직 이관
- author zbnerd; CLOSED; created 2026-06-03T15:57:17Z; closed 2026-06-14T16:01:51Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `ChunkConsumerTemplate`에 정의된 `ChunkExecutionState` 상태 판단 extension function을 `ChunkExecutionState` data class …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #969 — refactor: CalculationStatus enum 도입 — magic string 제거
- author zbnerd; CLOSED; created 2026-06-03T15:57:20Z; closed 2026-06-14T16:01:52Z; reason COMPLETED. Body: ## What to build `module-calculator`의 `CalculationResult.status: String`과 `module-synchronizer`의 `CalculatedEquipmentItem.status: String`에서 magic stri…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #970 — refactor: Enrich synchronizer domain classes with behavior and typed status
- author zbnerd; CLOSED; created 2026-06-03T15:57:23Z; closed 2026-06-14T16:01:52Z; reason COMPLETED. Body: ## What to build Add behavior to synchronizer domain data classes and introduce typed status enum to replace magic strings. ### Change 1: Introduce It…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #971 — refactor: static utility Bean화 + sha256Hex 중복 제거 (GzipUtils, MessageDigest)
- author zbnerd; CLOSED; created 2026-06-03T15:57:44Z; closed 2026-06-14T16:01:52Z; reason COMPLETED. Body: ## What to build static utility 호출(`GzipUtils.compress()`, `GzipUtils.decompress()`, `MessageDigest.getInstance("SHA-256")`)을 주입 가능한 Bean으로 전환. `sha25…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #972 — refactor: Semaphore(2) 하드코딩 + CoroutineScope 직접 생성 — 설정 외부화
- author zbnerd; CLOSED; created 2026-06-03T15:57:46Z; closed 2026-06-14T16:01:53Z; reason COMPLETED. Body: ## What to build `Semaphore(2)` 하드코딩 3건과 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 직접 생성 1건을 설정 외부화 + 주입으로 전환. 테스트에서 동시성 제어 가능하게 개선. ###…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #973 — refactor: 바이트·시간 유틸리티 상수 추출 (module-common)
- author zbnerd; CLOSED; created 2026-06-03T15:57:47Z; closed 2026-06-14T16:01:53Z; reason COMPLETED. Body: ## What to build `module-common`에 바이트 확장 함수(`Long.mebiBytes()`, `Long.gibiBytes()`)와 시간 변환 유틸리티를 추가하고, 4개 활성 모듈에서 raw byte literal과 nanosecond 변환 매직 넘…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #974 — refactor: CalculatedEquipmentItem + GroupedEquipmentResult에 행위 추가
- author zbnerd; CLOSED; created 2026-06-03T15:57:47Z; closed 2026-06-14T16:01:54Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `CalculatedEquipmentItem`과 `GroupedEquipmentResult`에 현재 `EquipmentDocumentBuilder`에 있는 행위를 이관한다. 현재 문제: - `Equ…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #975 — refactor: EquipmentReadDocument computed property로 getter chain 정리
- author zbnerd; CLOSED; created 2026-06-03T15:57:50Z; closed 2026-06-14T16:01:54Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `EquipmentReadDocument` 내부 구조 접근(3-depth getter chain)을 computed property로 캡슐화한다. 현재 문제: - `EquipmentDocumentP…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #976 — refactor: Kafka 오류 핸들러 공통 팩토리 추출 (3개 모듈 중복 제거)
- author zbnerd; CLOSED; created 2026-06-03T15:57:50Z; closed 2026-06-14T16:01:54Z; reason COMPLETED. Body: ## What to build `module-infra`에 `KafkaConsumerErrorHandlerFactory`를 추가하여, 3개 모듈에 복사된 동일한 Kafka error handler 설정 코드를 통일한다. ### 현재 복사된 코드 (3개 파일, 완전 동일…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #977 — refactor: Lifecycle phase 상수 중앙 집중화 (6개 파일)
- author zbnerd; CLOSED; created 2026-06-03T15:57:52Z; closed 2026-06-14T16:01:55Z; reason COMPLETED. Body: ## What to build `module-common`에 `LifecyclePhases` 상수 객체를 정의하고, 4개 모듈 6개 파일에 흩어진 bare lifecycle phase 숫자를 상수 참조로 교체한다. ### 현재 하드코딩 위치 파일 값 의도 ------ …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #978 — refactor: HTTP 상태 코드 bare int → HttpStatus enum 교체 (module-rest-controller)
- author zbnerd; CLOSED; created 2026-06-03T15:57:55Z; closed 2026-06-14T16:01:55Z; reason COMPLETED. Body: ## What to build `module-rest-controller`에서 bare HTTP 상태 코드 정수(`401`, `404`, `500`, `503`)를 Spring `HttpStatus` enum으로 교체한다. ### 현재 위치 파일:줄 bare 값 교체 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #979 — refactor: Status·Endpoint enum 추출로 타입 안전성 확보 (4개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T15:57:57Z; closed 2026-06-14T16:01:55Z; reason COMPLETED. Body: ## What to build 4개 모듈에 흩어진 status 문자열(`"SUCCESS"`, `"ERROR"`, `"SKIPPED"`, `"ALREADY_RUNNING"` 등)과 endpoint 이름 문자열(`"character-basic"`, `"item-equipm…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #980 — refactor: ReadModelQueryService 긴 Optional 체인 정리 (module-rest-controller)
- author zbnerd; CLOSED; created 2026-06-03T15:58:00Z; closed 2026-06-14T16:01:56Z; reason COMPLETED. Body: ## What to build `ReadModelQueryService.batchQuery()`의 30줄 forEach 블록에서 JSON tree → DB row → default 3-level fallback 패턴을 헬퍼로 추출하고, `@Suppress("UNCHEC…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #981 — refactor: 스토리지 경로 프로토콜 ArtifactPathBuilder 추출 (3개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T15:59:17Z; closed 2026-06-14T16:01:56Z; reason COMPLETED. Body: ## What to build 파일 스토리지 레이아웃 경로 세그먼트를 `ArtifactPathBuilder`로 상수화하고, 3개 모듈 10개 파일에 흩어진 경로 생성을 통일한다. ### 현재 암묵적 파일 프로토콜 ``` runs/{runId}/{endpoint}/chu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #982 — refactor: JSON 필드명 하드코딩 → 데이터 클래스 역직렬화 통일 (4개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T15:59:20Z; closed 2026-06-14T16:01:56Z; reason COMPLETED. Body: ## What to build 4개 모듈에 흩어진 40개+ JSON 필드명 하드코딩을 Jackson 데이터 클래스 기반 역직렬화로 교체하고, 수동 JSON 조립(`GzipJsonlChunkWriter.buildRecordLine`)을 `objectMapper.write…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #983 — refactor: ChunkConsumerTemplate 상태 머신 sealed class 재구성 (module-synchronizer)
- author zbnerd; CLOSED; created 2026-06-03T15:59:22Z; closed 2026-06-06T09:21:11Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate`의 복잡한 조건문 3개 메서드를 sealed class와 exhaustive `when`으로 재구성하여 가독성과 유지보수성을 개선한다. ### 현재 문제 코드 **1. `markFailureAndA…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [0dc0042596e0, 280329c29603, 3ed68b87e4a5, 6bae30f56e81, cd5be38cd3fe, d914c05d0845]; PR-record issue refs 2 [#1173/CLOSED_UNMERGED, #1174/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #984 — arch: Recursive CompletableFuture → Kotlin Flow 전환 (module-external-api)
- author zbnerd; CLOSED; created 2026-06-03T15:59:25Z; closed 2026-06-04T05:23:08Z; reason COMPLETED. Body: ## What to build `module-external-api`의 3개 Phase 클래스에서 재귀 `CompletableFuture` 체인(9-11 params)을 Kotlin `Flow` + while 루프로 전환한다. 이 작업은 아키텍처 변경으로 사전 설계 검…
- discussion: 1 / PR #1124 merged to develop. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1124/MERGED]; reachable commit-message refs 1 [df202a28b6a7]; PR-record issue refs 1 [#1124/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #985 — refactor: ChunkConsumerTemplate에서 상태머신 로직 추출 (synchronizer)
- author zbnerd; CLOSED; created 2026-06-03T16:05:13Z; closed 2026-06-07T07:50:44Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate`(335줄, 12 메서드, 4 도메인)에서 상태머신 로직을 독립 클래스로 추출. 현재 `ChunkConsumerTemplate`이 담당하는 4개 책임: 1. Chunk 실행 상태머신 (PENDING…
- discussion: 1 / Closed by refactor commits. State machine extracted to ChunkExecutionStateMachin…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc60d43e28fa]; PR-record issue refs 1 [#1184/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #986 — refactor: SnapshotFetchPhase를 endpoint-specific phase로 분리 (external-api)
- author zbnerd; CLOSED; created 2026-06-03T16:05:15Z; closed 2026-06-07T05:34:30Z; reason COMPLETED. Body: ## What to build `SnapshotFetchPhase`(292줄, 9 의존성, 4 도메인)를 CHARACTER_BASIC과 ITEM_EQUIPMENT 각각의 전용 phase로 분리. 현재 `execute()`가 parameterized dispatcher로…
- discussion: 1 / Closed by refactor commits. SnapshotFetchPhase split into CharacterBasicFetchPha…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [be7a84c51a00, d8314f714848]; PR-record issue refs 2 [#1183/MERGED, #1191/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #987 — refactor: ChunkedSnapshotSink에서 이벤트 발행 로직 추출 (external-api)
- author zbnerd; CLOSED; created 2026-06-03T16:05:18Z; closed 2026-06-07T10:02:26Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink`(292줄, 13 메서드, 4 도메인)에서 이벤트 발행 책임을 독립 클래스로 추출. 현재 Sink가 직접 처리하는 3개 이벤트 발행: - `publishChunkReady()` — chunk 완료 시 …
- discussion: 1 / Resolved by extracting event publishing into `SnapshotSinkEventPublisher`. The s…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [9596d4722f40]; PR-record issue refs 1 [#1185/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #988 — refactor: SnapshotChunkProcessor coroutine pipeline 분리 (calculator)
- author zbnerd; CLOSED; created 2026-06-03T16:05:21Z; closed 2026-06-06T16:48:50Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor`(174줄, 7 의존성, 3 도메인)에서 coroutine pipeline 세팅과 파싱/변환 로직을 분리. 현재 3개 책임: 1. I/O & 스트리밍 — gzip JSONL 읽기, Channel …
- discussion: 1 / Closed via merged PR #1180. Refactor completed: SnapshotChunkProcessor decompose…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1180/MERGED]; reachable commit-message refs 1 [0d7f9ddca8f6]; PR-record issue refs 1 [#1180/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #989 — refactor: ChunkedSnapshotSink에서 파일 I/O + chunk rotation 분리 (external-api)
- author zbnerd; CLOSED; created 2026-06-03T16:05:52Z; closed 2026-06-07T11:05:19Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink`에서 파일 I/O 및 chunk rotation 로직을 독립 클래스로 추출. 이슈 #987 (이벤트 발행 분리) 이후 작업. 현재 Sink의 파일 I/O 책임: - `rotateChunk()` — ch…
- discussion: 1 / Closed via #1192 (merged). ChunkFileManager extraction complete: sink 217→134 li…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1192/MERGED]; reachable commit-message refs 3 [4a5590c030f8, 8ecad90bac4c, f83322b0db7e]; PR-record issue refs 1 [#1192/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #990 — refactor: DefaultChunkProcessor 파이프라인 스테이지 분리 (synchronizer)
- author zbnerd; CLOSED; created 2026-06-03T16:05:55Z; closed 2026-06-07T10:20:17Z; reason COMPLETED. Body: ## What to build `DefaultChunkProcessor`(71줄, 6 의존성, 5 도메인)를 pipeline stage 패턴으로 분해. 이슈 #985 (ChunkConsumerTemplate 상태머신 추출) 이후 작업. 현재 5개 책임이 단일 `proc…
- discussion: 1 / Closed by squash-merge of PR #1187. Extracted `ChunkPipelineOrchestrator` (53 li…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1187/MERGED]; reachable commit-message refs 1 [7ef9a2477262]; PR-record issue refs 1 [#1187/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #991 — refactor: ExternalApiScheduler에서 연속 루프 분리 (external-api)
- author zbnerd; CLOSED; created 2026-06-03T16:05:59Z; closed 2026-06-07T05:44:41Z; reason COMPLETED. Body: ## What to build `ExternalApiScheduler`(195줄, 6 의존성, 4 도메인)에서 ITEM_EQUIPMENT 연속 루프를 독립 클래스로 추출. 이슈 #986 (SnapshotFetchPhase 분리) 이후 작업. 현재 4개 책임: 1. 일일…
- discussion: 1 / Closed by refactor. ITEM_EQUIPMENT continuous loop extracted to ItemEquipmentCon…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [d8314f714848]; PR-record issue refs 1 [#1183/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #992 — refactor: SynchronizerMetrics 도메인별 metrics 클래스 분리
- author zbnerd; CLOSED; created 2026-06-03T16:06:02Z; closed 2026-06-07T11:08:14Z; reason COMPLETED. Body: ## What to build `SynchronizerMetrics`(176줄, 19 메서드, 5 도메인)를 도메인별 metrics 클래스로 분리. 이슈 #985, #989 이후 작업. 현재 5개 메트릭 도메인이 단일 클래스에 집중: 1. Chunk lifecycle …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [4a5590c030f8]; PR-record issue refs 1 [#1190/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #993 — refactor: module-common에 JsonNode 확장 함수 추가 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:08:03Z; closed 2026-06-14T16:01:57Z; reason COMPLETED. Body: ## Parent Law of Demeter 위반 정리 — JsonNode 파싱 체인 전체의 기반 이슈. ## What to build `module-common`에 Jackson `JsonNode` 확장 함수를 추가. 현재 4개 모듈(synchronizer, calc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #994 — Synchronizer: ChunkExecutionRepository CAS 상태 전환 boolean→예외
- author zbnerd; CLOSED; created 2026-06-03T16:08:03Z; closed 2026-06-14T16:01:57Z; reason COMPLETED. Body: ## What to build `ChunkExecutionRepository`의 `markSucceeded`, `markFailedRetryable`, `markFailedTerminal` 세 메서드가 CAS(compare-and-swap) 결과를 `Boolean`으로…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #995 — fix: Optional.get() violations — JwtAuthInterceptor, AuthCharacterFetchConsumer
- author zbnerd; CLOSED; created 2026-06-03T16:08:26Z; closed 2026-06-14T16:01:57Z; reason COMPLETED. Body: ## What to build `Optional.get()` 패턴을 `.map()` / `.orElseThrow()` 체이닝으로 교체. 프로젝트 규칙(`kotlin-null-safety.md`)에서 `.isPresent() + .get()` 금지. ### 대상 파일 1…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #996 — Synchronizer: 파일 리더 파싱 오류 silent loss 해소
- author zbnerd; CLOSED; created 2026-06-03T16:08:34Z; closed 2026-06-04T13:11:14Z; reason COMPLETED. Body: ## What to build `OcidMappingFileReader.parseMapping`과 `BasicChunkFileReader.parseRecord`가 `runCatching { ... }.getOrNull()`으로 JSON 파싱 오류를 모두 무시한다. 손상…
- discussion: 1 / Fixed in #1137 — `OcidMappingFileReader.read` throws `IllegalStateException` on …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1137/MERGED]; reachable commit-message refs 7 [7ebe2a7568ab, 8f53f0e09cdb, a76b9332a122, abb24bccb2ba, c1401db41bcf, f1bc4a74333b, fcb9015d67c2]; PR-record issue refs 1 [#1137/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #997 — External-API: OcidLookupPhase silent data loss
- author zbnerd; CLOSED; created 2026-06-03T16:08:36Z; closed 2026-06-04T13:13:50Z; reason COMPLETED. Body: ## What to build `OcidLookupPhase`에 두 가지 silent data loss 경로가 있다: 1. `fetchAndCollectOcidAsync`: API 응답에 `ocid` 필드가 없으면 캐릭터가 success/fail 카운트 모두에서 사라짐…
- discussion: 1 / Superseded by #1096 which broadened scope. Bug 1 (ocid null) was already fixed b…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #998 — External-API: acquireLock timeout→예외
- author zbnerd; CLOSED; created 2026-06-03T16:08:39Z; closed 2026-06-04T13:11:11Z; reason COMPLETED. Body: ## What to build `ExternalApiScheduler.acquireLock`이 락 획득 실패 시 `false` 반환. 호출부가 warning 로그만 남기고 전체 파이프라인을 스킵하거나 무한 재시도 루프에 빠짐. 락 타임아웃은 다른 작업이 stuck/de…
- discussion: 1 / Fixed in #1137 — `acquireLock` now throws `DistributedLockException` on timeout;…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1137/MERGED]; reachable commit-message refs 6 [249b591dcdae, 33781f68a2b3, 5d510ce09f3b, b214f99cada2, f1bc4a74333b, fcb9015d67c2]; PR-record issue refs 1 [#1137/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #999 — External-API: ArtifactStore/Cleanup null→예외
- author zbnerd; CLOSED; created 2026-06-03T16:08:42Z; closed 2026-06-04T12:54:55Z; reason COMPLETED. Body: ## What to build 두 가지 null/boolean 안티패턴: 1. `LocalExternalApiArtifactStoreAdapter.read`: 파일 없으면 `null` 반환. port interface가 `ByteArray?`로 nullable 계약. …
- discussion: 1 / Closed via PR #1135 (merged at c1048e29). Implemented: ArtifactNotFoundException…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1135/MERGED]; reachable commit-message refs 10 [488e5eb1ebac, 4af997b22305, 7513ec26909e, 782dcf32f14b, 86cdbd8d4383, b3080b503379, bf09f89fc97c, c1048e297962, d1a339950cae, e16657c5e4fb]; PR-record issue refs 1 [#1135/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1000 — fix: unchecked casts in ReadModelQueryService — NPE risk
- author zbnerd; CLOSED; created 2026-06-03T16:08:56Z; closed 2026-06-14T16:01:58Z; reason COMPLETED. Body: ## What to build `ReadModelQueryService`에서 unchecked cast 3건을 안전한 패턴으로 교체. ### 대상 파일 `module-rest-controller/.../read/ReadModelQueryService.kt` 1. **라…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1001 — fix: silent failure→success propagation in ExternalApiScheduler
- author zbnerd; CLOSED; created 2026-06-03T16:08:59Z; closed 2026-06-04T12:43:29Z; reason COMPLETED. Body: ## What to build `ExternalApiScheduler.triggerDailyRefresh()`에서 ranking fetch 실패가 null로 전환되어 이후 phase가 계속 실행되고 최종적으로 "성공"으로 보고되는 문제 수정. ### 대상 파일 `mod…
- discussion: 1 / Fixed via PR #1133. ExternalApiScheduler.triggerDailyRefresh() now propagates ra…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1133/MERGED]; reachable commit-message refs 5 [03d39323cfaa, 3d59da8ecaba, 798079858726, ac15137416ac, d97fce8206b2]; PR-record issue refs 1 [#1133/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1002 — refactor: Synchronizer JsonNode 파싱 LoD 정리 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:07Z; closed 2026-06-14T16:01:58Z; reason COMPLETED. Body: ## Parent #993 ## What to build Synchronizer 모듈의 JSON 파싱 코드에서 Law of Demeter 위반(2-hop `node.get("X")?.asText()` 체인)을 #993에서 추가한 확장 함수로 교체. **대상 파일 및 건…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1003 — refactor: Calculator JsonNode 파싱 LoD 정리 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:10Z; closed 2026-06-14T16:01:59Z; reason COMPLETED. Body: ## Parent #993 ## What to build Calculator 모듈의 JSON 파싱 코드에서 Law of Demeter 위반을 #993의 확장 함수로 교체. **대상 파일 및 건수:** 1. **`SnapshotEquipmentParser.kt` (20+…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1004 — Calculator: 상태 raw string→typed enum
- author zbnerd; CLOSED; created 2026-06-03T16:09:11Z; closed 2026-06-14T16:00:46Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor.calculateItem`에서 결과 상태를 raw string `"SUCCESS"`, `"ERROR"`, `"SKIPPED"`로 관리. 오타 시 컴파일 에러 없이 에러가 성공으로 분류됨. `Cal…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1005 — refactor: REST Controller JsonNode 파싱 LoD 정리 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:13Z; closed 2026-06-14T16:00:46Z; reason COMPLETED. Body: ## Parent #993 ## What to build REST Controller 모듈의 JSON 파싱 코드에서 Law of Demeter 위반을 #993의 확장 함수로 교체. **대상 파일 및 건수:** 1. **`ReadModelQueryService.kt` (…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1006 — Calculator: SnapshotEquipmentParser 인식 불가 grade 처리
- author zbnerd; CLOSED; created 2026-06-03T16:09:13Z; closed 2026-06-14T16:00:47Z; reason COMPLETED. Body: ## What to build `SnapshotEquipmentParser.buildPotentialLines`에서 `PotentialGrade` 매칭 실패 시 `return null`. "잠재 없음"(정상)과 "인식 불가 grade 문자열"(비정상)을 구분하지 못함.…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1007 — refactor: External API JsonNode 파싱 LoD 정리 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:16Z; closed 2026-06-14T16:00:47Z; reason COMPLETED. Body: ## Parent #993 ## What to build External API 모듈의 JSON 파싱 코드에서 Law of Demeter 위반을 #993의 확장 함수로 교체. **대상 파일 및 건수:** 1. **`OcidCacheProvider.kt` (2건)** —…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1008 — Calculator: CleanupScheduler 관측성 개선
- author zbnerd; CLOSED; created 2026-06-03T16:09:16Z; closed 2026-06-14T16:00:47Z; reason COMPLETED. Body: ## What to build `CalculatorResultCleanupScheduler`의 세 메서드가 silent skip: 1. `parseRunInfo`/`readDirectoryCreatedTime`: 스토리지 리스팅과 로컬 경로 불일치 시 `return n…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1009 — REST-Controller: JdbcLikeToggleService 불일치 수정
- author zbnerd; CLOSED; created 2026-06-03T16:09:18Z; closed 2026-06-14T16:00:48Z; reason COMPLETED. Body: ## What to build `JdbcLikeToggleService`에서 동일한 `resolveOcid() == null` 조건에 세 가지 다른 처리: 메서드 동작 -------- ------ `toggleLikeWithCount()` `CharacterNotFou…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1010 — refactor: REST Controller Redis opsForXxx 필드 캐싱 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:18Z; closed 2026-06-14T16:00:48Z; reason COMPLETED. Body: ## Parent Law of Demeter 위반 정리 — Redis template opsFor 체인. ## What to build REST Controller 모듈에서 `redisTemplate.opsForZSet().add()` / `redisTemplate.o…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1011 — refactor: OcidCacheProvider.entries 캡슐화 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:24Z; closed 2026-06-14T16:00:48Z; reason COMPLETED. Body: ## Parent Law of Demeter 위반 정리 — OcidCacheProvider Map 내부 구조 노출. ## What to build `OcidCacheProvider.current()`가 `Map<String, String>`을 반환하고, 호출자가 `.e…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1012 — refactor: replace FailureDecision null sentinel with sealed class
- author zbnerd; CLOSED; created 2026-06-03T16:09:25Z; closed 2026-06-07T09:24:09Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate`에서 `terminalReason: String?` null sentinel을 sealed interface로 교체. null을 retryable 의미로 사용하는 암묵적 계약을 타입 안전하게 만듦.…
- discussion: 1 / Resolved by #985 + #983 — `FailureDecision` is now a sealed class with `Retryabl…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1013 — refactor: Calculator temporal/file attribute 확장 함수 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:27Z; closed 2026-06-14T16:00:49Z; reason COMPLETED. Body: ## Parent Law of Demeter 위반 정리 — File attribute 및 temporal 체인. ## What to build Calculator 모듈의 파일 속성 접근과 시간 계산에서 Law of Demeter 위반을 확장 함수로 교체. **대상 파일…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1014 — fix: nullable port returns + repetitive null checks in rest-controller
- author zbnerd; CLOSED; created 2026-06-03T16:09:28Z; closed 2026-06-14T16:00:49Z; reason COMPLETED. Body: ## What to build `JdbcOcidQueryAdapter.resolveOcid()` nullable 반환으로 인한 호출부 6회 반복 null 체크 + `EquipmentRankingCacheService` null 반환 에러/빈결과 혼동 수정. ### 대상…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1015 — refactor: REST Controller error.javaClass.simpleName 유틸 (#902 LoD)
- author zbnerd; CLOSED; created 2026-06-03T16:09:30Z; closed 2026-06-14T16:00:49Z; reason COMPLETED. Body: ## Parent Law of Demeter 위반 정리 — 예외 타입 이름 접근 체인. ## What to build `error.javaClass.simpleName` 형태의 2-hop 체인을 유틸 함수로 캡슐화. **대상 파일 및 건수:** 1. **`Popular…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1016 — REST-Controller: EquipmentRankingCacheService null→typed result
- author zbnerd; CLOSED; created 2026-06-03T16:09:36Z; closed 2026-06-14T16:00:50Z; reason COMPLETED. Body: ## What to build `EquipmentRankingCacheService.topByTotalCost`가 Redis 오류 시 `null` 반환. 인프라 장애와 빈 캐시(정상)가 동일 `null`. fallback은 동작하나 장애 메트릭/알럿 없음. 변경 내용:…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1017 — External-API: AuthCharacterFetch + RunStatusTracker 예외 처리 개선
- author zbnerd; CLOSED; created 2026-06-03T16:09:39Z; closed 2026-06-14T16:00:50Z; reason COMPLETED. Body: ## What to build 두 가지 low-severity 안티패턴: 1. `AuthCharacterFetchConsumer` (lines 48-53): `Optional.isEmpty()` + `Optional.get()` 안티패턴 사용. 프로젝트 `kotlin-…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1018 — fix: silent data drop — null OCID not counted in OcidLookupPhase
- author zbnerd; CLOSED; created 2026-06-03T16:10:08Z; closed 2026-06-04T12:43:27Z; reason COMPLETED. Body: ## What to build `OcidLookupPhase.fetchAndCollectOcidAsync()`에서 null OCID가 success/fail 카운트 모두에서 누락되는 문제 수정. ### 대상 파일 `module-external-api/.../schedu…
- discussion: 1 / Fixed via PR #1134 (merge commit 3b29a426). Added log.warn with maskIgn in OcidL…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1134/MERGED]; reachable commit-message refs 4 [30d88cbbce94, 3b29a426c49b, 59d32ebfff15, d7bee9dd4600]; PR-record issue refs 2 [#1133/MERGED, #1134/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1019 — fix: add logging to silent parse failures in synchronizer file readers
- author zbnerd; CLOSED; created 2026-06-03T16:10:11Z; closed 2026-06-04T13:04:53Z; reason COMPLETED. Body: ## What to build `OcidMappingFileReader.parseMapping()` 및 `BasicChunkFileReader.parseRecord()`에서 `runCatching{}.getOrNull()`으로 인해 malformed 데이터가 조용히 무…
- discussion: 1 / Fixed in #1136 — debug logging added to all silent parse paths in OcidMappingFil…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1136/MERGED]; reachable commit-message refs 5 [34f0a051d5e3, 4c6fe7252070, 66e7dec4b108, 9a236f2c0733, e5319abc0cda]; PR-record issue refs 1 [#1136/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1020 — refactor: replace nullable Double? with defaults in CalculationCache
- author zbnerd; CLOSED; created 2026-06-03T16:10:13Z; closed 2026-06-14T16:00:50Z; reason COMPLETED. Body: ## What to build `CalculationCache.ComponentCosts`에서 3개 `Double?` 필드를 non-nullable `Double` 기본값으로 변경. "비용 없음"과 "null"이 동일 의미인데 타입으로 구분 불가. ### 대상 파일 `…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1021 — refactor: replace @Autowired(required=false) with ObjectProvider
- author zbnerd; CLOSED; created 2026-06-03T16:10:16Z; closed 2026-06-14T16:00:50Z; reason COMPLETED. Body: ## What to build `InternalApiController`의 nullable `@Autowired(required=false)` 필드를 `ObjectProvider<T>`로 교체. 새 메서드 추가 시 null 체크 누락 방지. ### 대상 파일 `modu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1022 — TDD: Clock 추상화 주입 — Instant.now()/System.currentTimeMillis() 대체
- author zbnerd; CLOSED; created 2026-06-03T16:18:16Z; closed 2026-06-14T16:00:51Z; reason COMPLETED. Body: ## What to build 시간 의존 로직이 `Instant.now()` / `System.currentTimeMillis()`를 직접 호출하여 단위 테스트가 불가능한 컴포넌트에 `java.time.Clock`을 생성자 주입한다. 대상 파일: - `module-sy…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1023 — TDD: ManagedExecutorProvider 인터페이스 추출 — VirtualThreadExecutorManager 생성자 주입
- author zbnerd; CLOSED; created 2026-06-03T16:18:18Z; closed 2026-06-04T03:32:12Z; reason COMPLETED. Body: ## What to build `VirtualThreadExecutorManager`를 직접 생성하는 4개 컴포넌트에 인터페이스 기반 executor provider를 주입하여 테스트 가능하게 만든다. 현재 패턴 (각 컴포넌트가 내부에서 직접 생성): ```kotlin…
- discussion: 1 / #1068에서 VirtualThreadExecutorManager를 @Bean injection으로 전환하면서 ManagedExecutorPro…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1024 — TDD: @Import 구체 클래스 → 인터페이스 기반 wiring 수정
- author zbnerd; CLOSED; created 2026-06-03T16:18:21Z; closed 2026-06-14T16:00:51Z; reason COMPLETED. Body: ## What to build `@Import`로 구체적인 구현체를 직접 참조하는 두 모듈의 설정 클래스를 인터페이스 기반 wiring으로 수정한다. 현재 문제: - `module-external-api/ExternalApiApplication.kt:34` — `@Im…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1025 — TDD: ChunkedSnapshotSink에서 순수 회전 로직 추출 — ChunkRotationLogic
- author zbnerd; CLOSED; created 2026-06-03T16:18:23Z; closed 2026-06-14T16:00:51Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink`(293줄)의 청크 회전 결정 로직을 순수 클래스로 추출하여 단위 테스트 가능하게 만든다. 현재 문제: - `ChunkedSnapshotSink`가 `init` 블록에서 `Thread.ofPlatfor…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1026 — TDD: SnapshotChunkProcessor에서 순수 변환 로직 추출 — SnapshotItemTransformer
- author zbnerd; CLOSED; created 2026-06-03T16:18:25Z; closed 2026-06-14T16:00:52Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor`(174줄)의 파싱/계산 로직을 순수 클래스로 추출하여 단위 테스트 가능하게 만든다. 현재 문제: - `parseLines()`와 `processItems()`가 private suspend 함수…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1027 — TDD: LikeController 전용 Executor 주입 — ForkJoinPool.commonPool() 제거
- author zbnerd; CLOSED; created 2026-06-03T16:18:28Z; closed 2026-06-04T04:01:53Z; reason COMPLETED. Body: ## What to build `LikeController`가 `CompletableFuture.supplyAsync { ... }`를 executor 지정 없이 호출하여 암시적으로 `ForkJoinPool.commonPool()`을 사용하는 문제를 수정한다. 프로젝트…
- discussion: 1 / #1102 PR #1123에 포함되어 처리 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [6f22ef7dc825]; PR-record issue refs 1 [#1123/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1028 — TDD: KafkaConsumeTemplate 추출 — 4개 Consumer 중복 패턴 제거
- author zbnerd; CLOSED; created 2026-06-03T16:18:55Z; closed 2026-06-14T16:00:52Z; reason COMPLETED. Body: ## What to build 4개 Kafka Consumer의 `consume()` / `consumeUrgent()` 메서드가 동일한 패턴(역직렬화 → 코루틴 실행 → handler 호출 → ACK)을 반복한다. 이를 `KafkaConsumeTemplate`로 추출…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #1029 — TDD: ChunkConsumerTemplate에서 FailureClassifier 순수 로직 추출
- author zbnerd; CLOSED; created 2026-06-03T16:19:02Z; closed 2026-06-07T09:24:06Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate.markFailureAndAck()`(49줄)이 7가지 관심사(실패 분류, 재시도 가능/종료 판단, 백오프 계산, DB 상태 쓰기, 메트릭, 로깅, ACK 결정)를 혼합하고 있다. 실패 분류와 백오…
- discussion: 1 / Resolved by #985 — failure classification extracted to `ChunkExecutionStateMachi…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1030 — TDD: ReadModelCacheService 분해 — UrgentRequestDedup / ReadModelCache / CacheKeyFormat
- author zbnerd; CLOSED; created 2026-06-03T16:19:05Z; closed 2026-06-14T16:00:52Z; reason COMPLETED. Body: ## What to build `ReadModelCacheService`(161줄)가 4가지 관심사(캐시 읽기/쓰기, negative cache, urgent 중복 제거, 상태 응답)를 혼합하고 있다. 이를 집중된 서비스로 분해한다. 대상 파일: `module-rest…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1031 — TDD: BatchReadScheduler.resolveBatch() 분해 — 66줄 모놀리스 메서드 쪼개기
- author zbnerd; CLOSED; created 2026-06-03T16:19:22Z; closed 2026-06-14T16:00:53Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler.resolveBatch()`(66줄, 영 테스트)가 7가지 관심사(캐시 조회, DB 폴백, 캐시 write-back, deferred 해결, negative-cache 확인, urgent 트리거, 메트릭…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #1032 — refactor: WebClient → @Configuration Bean 전환 (NexonExternalApiClientAdapter)
- author zbnerd; CLOSED; created 2026-06-03T16:21:10Z; closed 2026-06-14T16:00:53Z; reason COMPLETED. Body: ## What to build `NexonExternalApiClientAdapter`가 `by lazy`로 내부 생성하는 `WebClient`를 `@Configuration` Bean으로 전환한다. Reactor Netty `ConnectionProvider`가 Sp…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1033 — refactor: CalculationCache Caffeine 설정 외부화 (100K → Properties)
- author zbnerd; CLOSED; created 2026-06-03T16:21:13Z; closed 2026-06-14T16:00:53Z; reason COMPLETED. Body: ## What to build `CalculationCache`가 하드코딩한 `maximumSize(100_000)`로 Caffeine cache를 생성. 설정을 `PipelineProperties`로 외부화하여 튜닝/테스트 가능하게 만든다. 현재 패턴 (`Calcul…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1034 — refactor: RunCleanupExecutor → @Bean 전환 (2개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T16:21:15Z; closed 2026-06-14T16:00:54Z; reason COMPLETED. Body: ## What to build `RunCleanupExecutor`를 Spring Bean으로 등록하고 생성자 주입으로 전환. 현재 2개 스케줄러가 직접 인스턴스 생성. 현재 패턴: ```kotlin // ArtifactCleanupScheduler.kt:33 (mod…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1035 — refactor: EquipmentDocumentBuilder/Preparer → @Component DI 전환
- author zbnerd; CLOSED; created 2026-06-03T16:21:18Z; closed 2026-06-14T16:01:29Z; reason COMPLETED. Body: ## What to build `DefaultChunkProcessor`가 직접 생성하는 2개 collaborator를 Spring Bean으로 전환. `EquipmentDocumentBuilder`는 `Instant.now()` 숨겨진 시간 의존성 포함. 현재 패턴 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1036 — refactor: CoroutineScope 주입 — KafkaSnapshotChunkReadyConsumer
- author zbnerd; CLOSED; created 2026-06-03T16:21:20Z; closed 2026-06-04T00:56:49Z; reason COMPLETED. Body: ## What to build `KafkaSnapshotChunkReadyConsumer`가 필드에서 직접 생성하는 `CoroutineScope`를 주입으로 전환. 테스트에서 `TestDispatcher`로 교체 가능하게 만든다. 현재 패턴 (`KafkaSnapshot…
- discussion: 1 / Dead code (injected CoroutineScope unused, runBlocking remains). Will be address…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1037 — refactor: ChunkedSnapshotSink — Thread.ofPlatform() → ExecutorService 주입
- author zbnerd; CLOSED; created 2026-06-03T16:21:23Z; closed 2026-06-04T00:56:48Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink`가 직접 생성하는 플랫폼 스레드를 주입받은 `ExecutorService`로 교체. 누수 시 감지/정리 가능하게 만든다. 현재 패턴 (`ChunkedSnapshotSink.kt:56-58`): ```k…
- discussion: 1 / PR #1115 merged to develop. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1115/MERGED]; reachable commit-message refs 1 [f445d4203936]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1038 — refactor: EquipmentCalculationInputConverter object → @Component
- author zbnerd; CLOSED; created 2026-06-03T16:22:03Z; closed 2026-06-14T16:01:30Z; reason COMPLETED. Body: ## What to build `EquipmentCalculationInputConverter`를 Kotlin `object` 싱글턴에서 `@Component` 클래스로 전환. static dispatch로 인해 테스트 격리 불가. 현재 패턴 (`EquipmentCal…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1039 — refactor: Kafka FixedBackOff 재시도 설정 외부화 (3개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T16:22:06Z; closed 2026-06-14T16:01:30Z; reason COMPLETED. Body: ## What to build 3개 모듈의 `KafkaConsumerConfig`에 하드코딩된 `FixedBackOff(1000, 3)` 재시도 파라미터를 Properties로 외부화. 현재 패턴 (3개 모듈 공통): ```kotlin factory.setCommonE…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #1040 — refactor: V6Config bean wiring 정리 — 수동 vs @Component 혼재 해소
- author zbnerd; CLOSED; created 2026-06-03T16:22:09Z; closed 2026-06-14T16:01:30Z; reason COMPLETED. Body: ## What to build `module-rest-controller`의 `V6ReadConfig`가 11개 bean을 수동으로 생성하는 반면, 동일 모듈의 다른 클래스들은 `@Component`/`@Service`를 사용. 이 혼재가 bean 중복 충돌 위험을 만…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #1041 — refactor: VT ExecutorManager — external-api 누락 3개 컴포넌트 추가
- author zbnerd; CLOSED; created 2026-06-03T16:22:12Z; closed 2026-06-04T03:32:09Z; reason COMPLETED. Body: ## Parent #1023 ## What to build #1023이 4개 컴포넌트만 다루고 있음. `module-external-api`의 3개 컴포넌트가 누락되어 동일한 `VirtualThreadExecutorManager` 직접 생성 패턴 유지. 누락된 파일: …
- discussion: 1 / PR #1120에서 #1068에 포함되어 처리 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8bca3f1afc54]; PR-record issue refs 1 [#1120/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #656 — [P2][Test] Thread.sleep → Awaitility 전환 (테스트 안정성)
- author zbnerd; CLOSED; created 2026-03-28T15:51:11Z; closed 2026-04-18T05:04:09Z; reason COMPLETED. Body: ## 문제 CLAUDE.md Rule 12 위반. 여러 테스트 파일에서 `Thread.sleep()` 사용: - `PgmqCompetitiveConsumerTest.kt:92,164,189` - `AdvisoryLockConcurrencyTest.kt:42` - `Pg…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2d6d586012b1, 2e46b7fc0b3a]; PR-record issue refs 1 [#712/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #657 — [P2][Null-Safety] Builder build() 메서드 !! → require() 전환
- author zbnerd; CLOSED; created 2026-03-28T15:51:12Z; closed 2026-04-18T05:04:10Z; reason COMPLETED. Body: ## 문제 `EquipmentExpectationResponseV4.kt` Builder에서 `!!` 연산자로 필수 필드 검증. UninitializedPropertyAccessException 발생 시 디버깅 어려움. ## 위치 - `module-web/.../dto…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2d6d586012b1, 2e46b7fc0b3a]; PR-record issue refs 1 [#712/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #658 — [P2][Code-Quality] BulkLoaderService 순환 복잡도 개선
- author zbnerd; CLOSED; created 2026-03-28T15:51:13Z; closed 2026-04-18T05:04:11Z; reason COMPLETED. Body: ## 문제 `BulkLoaderService.kt` (504 lines)의 높은 순환 복잡도. 유지보수 어려움. ## 위치 - `module-infra/.../bulk/BulkLoaderService.kt` ## 수정 메서드 분리, 전략 패턴 도입으로 복잡도 감소. *…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2d6d586012b1, 2e46b7fc0b3a]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #659 — [P2][Performance] N+1 쿼리 검증 - JPA Batch Fetch 적용 상태
- author zbnerd; CLOSED; created 2026-03-28T15:51:15Z; closed 2026-04-18T05:04:12Z; reason COMPLETED. Body: ## 문제 `default_batch_fetch_size: 100` 설정은 있으나, Entity 그래프 탐색 시 실제 N+1 발생 여부 미검증. ## 수정 Hibernate statistics 활성화 후 실제 쿼리 카운트 검증. **검출 에이전트:** Architect
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2d6d586012b1, 2e46b7fc0b3a]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #660 — [P2][Architecture] API v1 Deprecation 계획 수립
- author zbnerd; CLOSED; created 2026-03-28T15:51:16Z; closed 2026-04-18T04:22:30Z; reason COMPLETED. Body: ## 문제 v1, v4, v5 Controller가 공존. v1 → v5 마이그레이션 타임라인 없음. ## 위치 - `module-web/.../controller/v1/GameCharacterControllerV1.kt` ## 수정 v1 Deprecation 일정 및…
- discussion: 1 / GameCharacterControllerV1에 deprecation 코멘트 존재 ('V1 레거시 — ADR-005 이관'). V2 마이그레이션…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #662 — [P0][Security] game_character fingerprint 컬럼 추가 — self-like 방지 정확도 향상
- author zbnerd; CLOSED; created 2026-03-28T18:42:40Z; closed 2026-03-29T10:06:38Z; reason COMPLETED. Body: ## 문제 JWT Filter에서 self-like 방지를 위해 `CharacterOcidPort.resolveAllOcids()`를 사용하여 모든 캐릭터의 OCID를 조회한다. 현재 `game_character` 테이블에는 사용자 식별 정보(fingerprint, a…
- discussion: 1 / PR #666으로 해결. Fingerprint 기반 multi-character self-like 방지 구현.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [06952c403932, 672d369096cf, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#666/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #663 — [P1][Architecture] account_id Identity 컬럼 마이그레이션 — 멱등성 보장
- author zbnerd; CLOSED; created 2026-03-28T18:42:42Z; closed 2026-03-29T10:06:40Z; reason COMPLETED. Body: ## 문제 `game_character` 테이블의 PK가 현재 자동 생성 ID를 사용하고 있으나, 여러 데이터 소스(CSV bulk load, API 동기화)에서 동일 캐릭터가 중복插入될 위험이 있다. `account_id`를 명시적 identity 컬럼으로 지정하여:…
- discussion: 1 / PR #666으로 해결. account_id = fingerprint identity 구현.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [672d369096cf, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #664 — [P1][Data-Integrity] like_count와 character_like 테이블 불일치 — DB Trigger 도입 필요
- author zbnerd; CLOSED; created 2026-03-28T18:52:49Z; closed 2026-03-29T10:06:42Z; reason COMPLETED. Body: ## 문제 `LikeToggleService.likeCharacter()`에서 `insertIfAbsent()`와 `incrementLikeCount()`가 별도의 SQL 문으로 실행되어, 그 사이 예외/스레드 선점 시 `character_like`는 INSERT되지만…
- discussion: 1 / PR #666으로 해결. DB Trigger(fn_like_count_trigger)로 like_count 원자성 보장.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [5dacf53f279a, 672d369096cf, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#690/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #665 — arch(like): cache coherency failure — liked/likeCount split-brain state across like domain
- author zbnerd; CLOSED; created 2026-03-28T19:46:16Z; closed 2026-03-29T10:06:44Z; reason COMPLETED. Body: ## 📄 Description Like toggle 도메인에서 `liked`(boolean)과 `likeCount`(long)이 하나의 트랜잭션 내에서 별개 필드로 관리되지만, 캐시 전략이 통일되지 않아 상태 불일치가 발생한다. E2E 테스트에서 토글 후 상태 조회 시…
- discussion: 1 / PR #666으로 해결. Direct DB 읽기 + trigger로 count drift 해결. ADR-031 참조.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [06952c403932, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#666/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #667 — [P0][Auth] Login 시 Nexon API 계정 검증 누락 — API Key 무제한 수용으로 Like 시스템 무력화 가능
- author zbnerd; CLOSED; created 2026-03-29T10:35:12Z; closed 2026-04-18T04:22:25Z; reason COMPLETED. Body: ## Problem 현재 `AuthPortAdapter.login()`은 **Nexon API를 호출하지 않고** `apiKey.hashCode()` 기반 fingerprint를 생성합니다: ```kotlin // AuthPortAdapter.kt:64-66 priva…
- discussion: 1 / PR #711에서 해결 — ApiKeyValidator Nexon 계정 검증 강화 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 8 [0cf11b1a5d41, 4c9b3652ff94, 8722ef8e9391, 8a092c375a02, a0d919547548, b31961878777, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#668/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #672 — refactor(infra): PostgresSingleFlightStrategy 세션 락 → xact 락 전환
- author zbnerd; CLOSED; created 2026-03-29T18:29:46Z; closed 2026-04-18T05:04:13Z; reason COMPLETED. Body: ## Summary `PostgresSingleFlightStrategy`가 세션 스코프 advisory lock (`pg_try_advisory_lock`/`pg_advisory_unlock`)에 의존 중. CLAUDE.md Rule 7 (xact-lock only)…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [2d6d586012b1, 2e46b7fc0b3a, 8a092c375a02, bd9641dfbbee, e692e289ec79, f952db781234]; PR-record issue refs 1 [#712/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #693 — [P1][Infra] MetricsNexonApiClientWrapper Rate Limiter permit leak 가능성
- author zbnerd; CLOSED; created 2026-04-04T12:40:18Z; closed 2026-04-18T04:22:26Z; reason COMPLETED. Body: ## 원인 `MetricsNexonApiClientWrapper.recordApiCall()`에서 `block()`이 예외를 던지면 `whenComplete`가 부착되지 않아 permit이 release되지 않을 수 있음. ```kotlin // MetricsNexon…
- discussion: 1 / MetricsNexonApiClientWrapper Rate Limiter — PostgresRateLimiter try-with-resourc…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #694 — [P2][Architecture] ExecutorPort 확장으로 DIP 준수 — @Qualifier 직접 주입 제거
- author zbnerd; CLOSED; created 2026-04-04T12:40:20Z; closed 2026-04-18T04:22:28Z; reason COMPLETED. Body: ## 현황 `GameCharacterControllerV5`에서 `@Qualifier("expectationComputeExecutor")`로 module-infra Bean을 직접 주입 중. ADR-384에 Hexagonal Architecture 예외로 기록됨. #…
- discussion: 1 / ExecutorPort 인터페이스가 module-core에 존재하며 executeVoid, executeOrDefault, executeWith…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #695 — [P2][Test] NexonFanOutBatchLoader 단위 테스트 추가
- author zbnerd; CLOSED; created 2026-04-04T12:40:21Z; closed 2026-04-18T04:22:32Z; reason COMPLETED. Body: ## 현황 `NexonFanOutBatchLoader`에 전용 테스트 파일 없음. ADR-384에서 rate limiter 이중 acquire 제거 후 검증 부족. ## 필요 테스트 1. Batch Lane에서 rate limiter가 정확히 1회만 호출되는지 (per…
- discussion: 1 / NexonFanOutBatchLoader 단위 테스트 (성공/429/non-429/헬퍼) 이미 추가됨. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #700 — FEAT : Spring Profile 기반 API/Worker 프로세스 분리
- author zbnerd; CLOSED; created 2026-04-05T02:30:29Z; closed 2026-06-02T15:10:40Z; reason COMPLETED. Body: ## Summary 동일 코드베이스에서 Spring Profile(`api`, `worker`)로 실행만 분리하여 API 서버와 Worker를 독립 프로세스로 운영. **목표:** 코드 수정 최소화, 신규 파일 0개, 어노테이션 추가만으로 분리. ## Motivatio…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #704 — TEST: Distributed Cache Invalidation Consistency (LISTEN/NOTIFY) — Multi-Instance 검증
- author zbnerd; CLOSED; created 2026-04-05T07:17:30Z; closed 2026-04-18T06:10:43Z; reason COMPLETED. Body: ## Background PostgreSQL `LISTEN/NOTIFY` 기반으로 Scale-out 환경의 L1(Caffeine) 캐시 무효화를 구현함 (Issue #278). **현재 아키텍처:** ``` Instance A → evict(key) → NOTIFY "…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [2e46b7fc0b3a, 57b7777b832e, c582c090d37e, c88efd6a228f]; PR-record issue refs 1 [#714/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #715 — BUG : cache_storage 테이블 CREATE 마이그레이션 누락
- author zbnerd; CLOSED; created 2026-04-18T06:10:08Z; closed 2026-04-18T06:30:56Z; reason COMPLETED. Body: ## 문제 `cache_storage` 테이블이 코드베이스 어디에도 CREATE되지 않음. - V102 (`load_test_index_optimization.sql`): `idx_cache_storage_key_expires` 인덱스만 생성 - V107 (`cache…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 57b7777b832e, 8cc3d9861347]; PR-record issue refs 1 [#717/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #716 — BUG : TieredCache 버전 카운터 충돌로 cross-instance evict 무시
- author zbnerd; CLOSED; created 2026-04-18T06:10:09Z; closed 2026-04-18T06:30:57Z; reason COMPLETED. Body: ## 문제 각 인스턴스의 `TieredCache.versionCounter`가 `AtomicLong(0)`에서 독립적으로 증가하므로, 인스턴스 간 버전이 같아질 수 있음. 이 경우 `PostgresNotifySubscriber`의 stale filter가 **올바른 e…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 57b7777b832e, 8cc3d9861347]; PR-record issue refs 1 [#717/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #724 — FEAT : character_read_model LOGGED 테이블 마이그레이션 (Phase 2)
- author zbnerd; CLOSED; created 2026-04-19T09:19:15Z; closed 2026-06-02T15:10:41Z; reason COMPLETED. Body: ## 🗣 개요 cache_storage (UNLOGGED) → character_read_model (LOGGED) 테이블 마이그레이션. V5 Query Server 분리 Phase 2 작업. ## 🛠 작업 내용 ### 1. Flyway Migration ```sql …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #726 — feat: PgBouncer 도입 — PGMQ 쓰기 파이프라인 DB 커넥션 병목 해결
- author zbnerd; CLOSED; created 2026-04-19T17:46:27Z; closed 2026-06-02T15:10:42Z; reason COMPLETED. Body: ## 배경 10K IGN 부하 테스트에서 PGMQ 워커의 DB 커넥션 경합으로 인한 처리량 한계 확인. ### 부하 테스트 프로세스 ```bash # ── DB 접속 헬퍼 (.env 기반) ── source .env _DB_HOST=$(echo "$DB_URL" sed…
- discussion: 1 / ## 관련 문서 (References) ### ADR (Architecture Decision Records) - [ADR-pgmq-kafka-…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #732 — Epic: Pipeline 구조 리팩토링 — Fan-Out Burst 제거
- author zbnerd; CLOSED; created 2026-04-21T17:11:58Z; closed 2026-04-23T11:34:41Z; reason COMPLETED. Body: ## 목표 message → preset → item 중첩 fan-out 구조를 평탄화하여, executor 정책에 correctness/throughput이 좌우되는 구조를 제거한다. bounded work queue + fixed worker 모델로 전환. ## 배…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2773210b32ac, 88f7dd598433]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #733 — [P0] Semaphore(64) 제거 — 불필요한 간접층
- author zbnerd; CLOSED; created 2026-04-21T17:12:42Z; closed 2026-04-22T13:38:04Z; reason COMPLETED. Body: ## 배경 `PresetCalculationHelper`에 `Semaphore(64)`가 존재하며 item 계산 전 `acquireUninterruptibly()`를 호출한다. ThreadPool max=32이므로 Semaphore(64)는 항상 즉시 acquire된다…
- discussion: 1 / Semaphore 제거 완료. PR #744 머지 진행. 부하테스트 결과: 960만 회 submit retry는 기존과 동일 (Semaphore…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#744/MERGED]; reachable commit-message refs 2 [2773210b32ac, 95ed8e4414c2]; PR-record issue refs 1 [#744/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #734 — [P0] Read Model Write 배치화 — row-by-row → bulk
- author zbnerd; CLOSED; created 2026-04-21T17:12:44Z; closed 2026-04-22T14:12:17Z; reason COMPLETED. Body: ## 배경 `AbstractExpectationCalcWorker.batchViewUpsert()`에서 view table은 `batchRepo.bulkUpsert()`로 batch write하지만, read model은 여전히 개별 `writeToReadModelRa…
- discussion: 1 / Bulk JDBC upsert 구현 완료. PR #745 머지 후 클로즈.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#745/MERGED]; reachable commit-message refs 2 [499b9bad1a51, 7fe91d0bd754]; PR-record issue refs 1 [#745/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #735 — [P1] Virtual Thread → Platform Thread 교체 (PgmqWorker)
- author zbnerd; CLOSED; created 2026-04-21T17:12:45Z; closed 2026-04-22T14:32:00Z; reason COMPLETED. Body: ## 배경 `PgmqWorker`가 `Executors.newVirtualThreadPerTaskExecutor()`로 Phase 1 메시지를 처리한다. 각 메시지 내에서 CPU-bound 계산(수학 연산)이 수행된다. Virtual Thread는 I/O 대기에 최적화…
- discussion: 1 / Virtual Thread → FixedThreadPool 교체 완료. 부하테스트: admission throughput 5.1x 향상, 에러율…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [0321e9d57ddb, 31d8025a37a5]; PR-record issue refs 2 [#746/MERGED, #747/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #736 — [P1] CompletableFuture fan-out → flat work queue
- author zbnerd; CLOSED; created 2026-04-21T17:12:46Z; closed 2026-04-22T14:20:30Z; reason COMPLETED. Body: ## 배경 `PresetCalculationHelper.calculatePresetAsync()`가 ~20개 item에 대해 `CompletableFuture.supplyAsync`를 호출하고 `thenCombine`으로 결과를 누적한다. 40 메시지 × 60 item…
- discussion: 1 / #743 (compute key dedup)이 대체. 클로즈.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #737 — [P1] Per-item error isolation — thenCombine 실패 전파 차단
- author zbnerd; CLOSED; created 2026-04-21T17:13:27Z; closed 2026-04-22T14:20:32Z; reason COMPLETED. Body: ## 배경 현재 `calculatePresetAsync()`에서 item들이 `thenCombine`으로 연결되어 있다. 1개 item의 CompletableFuture가 실패하면 전체 chain이 실패하여 해당 preset 전체가 손실된다. 60개 item 중 1개 …
- discussion: 1 / #743 (compute key dedup)이 흡수. 클로즈.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #738 — [P2] BlockingSubmitExecutor 제거
- author zbnerd; CLOSED; created 2026-04-21T17:13:28Z; closed 2026-04-22T14:38:06Z; reason COMPLETED. Body: ## 배경 `BlockingSubmitExecutor`는 executor queue가 꽉 찼을 때 submit을 블로킹하며 재시도하는 wrapper다. Issue #4(flat work queue)가 적용되면 item submit이 bounded work queue를 …
- discussion: 1 / BlockingSubmitExecutor wrapper 제거 완료. PR #747 머지됨.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 2 [#4/MERGED, #747/MERGED]; reachable commit-message refs 2 [31d8025a37a5, 95ed8e4414c2]; PR-record issue refs 1 [#747/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #739 — [P2] PipelineBuffer backpressure 정렬 — capacity inflight와 연동
- author zbnerd; CLOSED; created 2026-04-21T17:13:29Z; closed 2026-04-22T14:51:43Z; reason COMPLETED. Body: ## 배경 `PipelineBuffer`는 max=500이지만, inflight Semaphore=40 × 결과 당 1개 = 최대 40개만 들어온다. 반면 burst 시 40 메시지가 동시 계산 완료되면 40개가 한 번에 offer된다. buffer가 full이면 결과…
- discussion: 1 / PipelineBuffer capacity를 maxInflight*2로 자동 정렬. buffer full 시 drain 우선 실행. PR #74…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#74/MERGED]; reachable commit-message refs 2 [0837f2f0179a, 950f3f4dd61c]; PR-record issue refs 1 [#748/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #740 — [P1] Item Compute Worker 분리 — PgmqWorker에서 계산 책임 분리
- author zbnerd; CLOSED; created 2026-04-21T17:51:07Z; closed 2026-04-22T22:12:24Z; reason COMPLETED. Body: ## 배경 현재 PgmqWorker가 메시지 수신 + 계산 실행을 모두 담당한다. `processBatchPipelined()`에서 Virtual Thread로 메시지를 받고, 그 안에서 `calculateAllPresets()` → `calculatePresetAsy…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #743 — [P0] Compute Key 기반 사전 연산 — 유니크 키 순차 처리로 컨텍스트 스위칭 제거
- author zbnerd; CLOSED; created 2026-04-22T12:12:23Z; closed 2026-04-22T22:12:28Z; reason COMPLETED. Body: ## 컨텍스트 현재 아이템당 compute는 600ms 소요. DP convolution 자체는 O(3 x target x K)로 마이크로초 단위이지만, **32개 스레드가 2 vCPU에서 동시에 CPU-bound 연산**을 수행하여 컨텍스트 스위칭 오버헤드가 발생하는…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 7 [13e478cf6a98, 2d80fc78adc0, 3bf164959dc3, 5dcfe95f6213, 8a1d33e20937, 95ed8e4414c2, ca8c3006b578]; PR-record issue refs 1 [#749/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #758 — feat: Three-Path Independence — Read/Write/External API 분리 (MQ-only boundary)
- author zbnerd; CLOSED; created 2026-04-24T12:21:35Z; closed 2026-06-02T15:10:43Z; reason COMPLETED. Body: ## Summary Read / Write / External API 세 패스를 독립적으로 분리하고, 패스 간 통신은 PGMQ 메시지 큐로만 수행한다. ## Core Principles 1. **Read Path**: HTTP + 조회 + enqueue only. `c…
- discussion: 1 / ### Production Readiness 보강 완료 commit `64754542`에서 실무 운영 관점 10가지를 ADR에 추가했습니다. *…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [ff56a59633f8]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #759 — refactor: TieredCache God Class 분해 — 60 fields, 47 methods, 4개 책임 클러스터 (P0)
- author zbnerd; CLOSED; created 2026-04-24T14:02:53Z; closed 2026-06-02T15:10:44Z; reason COMPLETED. Body: ## 문제 `TieredCache.kt`가 4개의 독립된 관심사를 단일 클래스에 포함하고 있어 God Class로 확인됨. - **파일**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/Ti…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #760 — refactor: BulkLoaderService God Class 분해 — 9 deps, 7 Atomic state, 6개 책임 (P1)
- author zbnerd; CLOSED; created 2026-04-24T14:02:54Z; closed 2026-06-02T15:10:45Z; reason COMPLETED. Body: ## 문제 `BulkLoaderService.kt`가 오케스트레이션, 체크포인트, 진행률 추적, 실패 관리, 스로틀링, 백프레셔를 단일 클래스에 혼합. - **파일**: `module-infra/src/main/kotlin/maple/expectation/infrast…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #761 — refactor: AdaptiveMicroBatchUserService God Class 분해 — worker+router+coalescer 혼합 (P1)
- author zbnerd; CLOSED; created 2026-04-24T14:02:55Z; closed 2026-06-02T15:10:46Z; reason COMPLETED. Body: ## 문제 `AdaptiveMicroBatchUserService.kt`가 캐시, 요청 코얼레싱, 적응형 라우팅, 배치 워커, 메트릭, 백프레셔를 단일 클래스에 혼합. - **파일**: `module-infra/src/main/kotlin/maple/expectatio…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #762 — refactor: PgmqWorker 3개 처리 모드 Strategy 분리 (P1)
- author zbnerd; CLOSED; created 2026-04-24T14:02:56Z; closed 2026-06-02T15:10:46Z; reason COMPLETED. Body: ## 문제 `PgmqWorker.kt`가 3개의 서로 다른 처리 모드(단일, 파이프라인 2-phase, 순차 배치)를 단일 `processMessages()` 메서드에 혼합. - **파일**: `module-infra/src/main/kotlin/maple/expect…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #763 — refactor: AdaptiveAdmissionControl CPU 모니터 추출 — 어드미션+CPU+워커풀 혼합 (P1)
- author zbnerd; CLOSED; created 2026-04-24T14:02:57Z; closed 2026-06-02T15:10:47Z; reason COMPLETED. Body: ## 문제 `AdaptiveAdmissionControl.kt`가 CPU 모니터링, 어드미션 제어, 워커 풀 관리, 메트릭을 단일 클래스에 혼합. - **파일**: `module-infra/src/main/kotlin/maple/expectation/infrastruc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #764 — refactor: PgmqClient TX 검증 + CB 폴백 템플릿화 (P2)
- author zbnerd; CLOSED; created 2026-04-24T14:03:29Z; closed 2026-06-02T15:10:48Z; reason COMPLETED. Body: ## 문제 `PgmqClient.kt`가 트랜잭션 검증 로직과 7개 Circuit Breaker 폴백 메서드를 큐 클라이언트에 혼합. - **파일**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pg…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #765 — refactor: GlobalAdmissionControl + AdaptiveAdmissionControl 공통 기반 추출 (P2)
- author zbnerd; CLOSED; created 2026-04-24T14:03:30Z; closed 2026-06-02T15:10:49Z; reason COMPLETED. Body: ## 문제 `GlobalAdmissionControl.kt`와 `AdaptiveAdmissionControl.kt`가 유사한 패턴(큐, 세마포어, 워커풀, 메트릭)을 중복 구현. - **GlobalAdmissionControl**: 322 lines, 24 method…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #812 — refactor(external-api): ExternalApiIngestionService.fetchSingle() returns sync but uses Co…
- author zbnerd; CLOSED; created 2026-05-05T13:52:34Z; closed 2026-05-31T05:29:10Z; reason COMPLETED. Body: ## Problem `ExternalApiIngestionService.fetchSingle()` has a synchronous return type (`ExternalApiFetchResult`) but internally calls `clientPort.fetch…
- discussion: 1 / ## Stale — already resolved 이 이슈에 언급된 `ExternalApiIngestionService` / `FetchExte…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [385a25c1afb6, bbff5127cbfc]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #867 — fix(kafka): add DLQ and DefaultErrorHandler for poison message prevention
- author zbnerd; CLOSED; created 2026-05-31T05:20:59Z; closed 2026-05-31T05:57:38Z; reason COMPLETED. Body: ## What to build All Kafka consumers in calculator and external-api lack a `DefaultErrorHandler` and DLQ topic. When `objectMapper.readValue` throws o…
- discussion: 1 / Fixed by #879 — added DefaultErrorHandler + DLQ to all 3 modules, fixed ACK-befo…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#879/MERGED]; reachable commit-message refs 1 [7ddc6d4b9ccb]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #868 — fix(airflow): handle 409 CONFLICT in DAG response_check
- author zbnerd; CLOSED; created 2026-05-31T05:21:23Z; closed 2026-06-03T06:47:07Z; reason COMPLETED. Body: ## What to build All `response_check` lambdas in Airflow DAGs reject 409 CONFLICT (ALREADY_RUNNING) as task failure. This causes: - `daily_collection_…
- discussion: 1 / Fixed by #881 — added is_accepted_response() helper + 409 CONFLICT handling to b…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#881/MERGED]; reachable commit-message refs 1 [cd8062ebb21a]; PR-record issue refs 1 [#881/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #869 — fix(infra): semaphore early release in EventConsumer
- author zbnerd; CLOSED; created 2026-05-31T05:21:25Z; closed 2026-06-03T06:47:04Z; reason COMPLETED. Body: ## What to build `HighPriorityEventConsumer` and `LowPriorityEventConsumer` release semaphore permits immediately after `executor.execute()`, before `…
- discussion: 1 / Semaphore paths verified safe. runBlocking removed by #880, replaced with Corout…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#880/MERGED]; reachable commit-message refs 1 [be92b9c1fd77]; PR-record issue refs 1 [#880/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #870 — fix(infra): add @PreDestroy for virtual thread executors
- author zbnerd; CLOSED; created 2026-05-31T05:21:27Z; closed 2026-06-03T06:47:02Z; reason COMPLETED. Body: ## What to build Multiple components create `Executors.newVirtualThreadPerTaskExecutor()` without shutdown lifecycle: - `EventConsumerConfig` (2 execu…
- discussion: 1 / Fixed by #880 — added @PreDestroy to all 8 VT executors. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#880/MERGED]; reachable commit-message refs 1 [be92b9c1fd77]; PR-record issue refs 1 [#880/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #871 — fix(kafka): ACK after async work completes, not before
- author zbnerd; CLOSED; created 2026-05-31T05:21:29Z; closed 2026-06-03T07:36:39Z; reason COMPLETED. Body: ## What to build Two consumers ACK the Kafka message before async work completes: - `AuthCharacterFetchConsumer` — ACKs immediately after `vtExecutor.…
- discussion: 1 / Absorbed into #872 fix (PR #883). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#883/MERGED]; reachable commit-message refs 2 [330dc678c25b, 73477dd22a95]; PR-record issue refs 1 [#883/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #872 — fix(external-api): ConsumedChunkCleanup concurrent counters and bounded queue
- author zbnerd; CLOSED; created 2026-05-31T05:22:06Z; closed 2026-06-03T07:36:38Z; reason COMPLETED. Body: ## What to build `ConsumedChunkCleanupScheduler` has two issues: 1. `deletedCount`/`failedCount` are plain `var Int` incremented from virtual threads …
- discussion: 1 / Fixed by #883 — bounded queue, AtomicInteger, @Scheduled, synchronous cleanup. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#883/MERGED]; reachable commit-message refs 2 [330dc678c25b, 73477dd22a95]; PR-record issue refs 1 [#883/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #873 — fix(external-api): atomic RunStatusTracker state transitions
- author zbnerd; CLOSED; created 2026-05-31T05:22:08Z; closed 2026-06-03T07:36:41Z; reason COMPLETED. Body: ## What to build Two race conditions in run status management: 1. `RunStatusTracker.completeRun`/`failRun` — `updateAndGet` then `lastCompletedRun.set…
- discussion: 1 / Fixed by #884 — local variable capture + runId guard. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#884/MERGED]; reachable commit-message refs 1 [ba3f7d0a8e30]; PR-record issue refs 1 [#884/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #874 — fix(calculator): chunk processing TOCTOU race
- author zbnerd; CLOSED; created 2026-05-31T05:22:11Z; closed 2026-06-03T07:36:36Z; reason COMPLETED. Body: ## What to build `CalculatorChunkProcessingCoordinator` checks `objectStorage.exists()` for source and result, then processes. Two consumer threads ca…
- discussion: 1 / Fixed by #882 — exists() checks moved inside semaphore. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#882/MERGED]; reachable commit-message refs 1 [429d2d1ad490]; PR-record issue refs 1 [#882/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #875 — fix(synchronizer): atomic Redis writes
- author zbnerd; CLOSED; created 2026-05-31T05:22:13Z; closed 2026-06-03T11:04:57Z; reason COMPLETED. Body: ## What to build Two non-atomic Redis write patterns: 1. `OcidMappingRepository` — `delete(key)` then pipeline `hSet`. Readers see empty hash between …
- discussion: 1 / Fixed by PR #887 (RENAME pattern + trim once after all batches). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#887/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#887/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #876 — fix(synchronizer): OCID upsert transaction and batch operations
- author zbnerd; CLOSED; created 2026-05-31T05:22:15Z; closed 2026-06-03T11:04:55Z; reason COMPLETED. Body: ## What to build Three issues in synchronizer data integrity: 1. `OcidLookupRunConsumer` — DB batchUpsert succeeds but Redis writeOcidToRedis fails → …
- discussion: 1 / Fixed by PR #886 (batch COPY→merge + Redis error handling + dead code removal). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#886/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#886/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #877 — fix(infra): TieredCache keyVersions leak and PgmqWorker shutdown drain
- author zbnerd; CLOSED; created 2026-05-31T05:22:17Z; closed 2026-06-04T00:56:51Z; reason COMPLETED. Body: ## What to build Three related issues: 1. `TieredCache.keyVersions: ConcurrentHashMap` grows without bound — only cleared on explicit evict, not on L1…
- discussion: 1 / PR #1116 merged to develop. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1116/MERGED]; reachable commit-message refs 1 [51f7e06f8926]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #878 — fix(airflow): Kafka networking for containerized deployment
- author zbnerd; CLOSED; created 2026-05-31T05:22:19Z; closed 2026-06-03T11:04:53Z; reason COMPLETED. Body: ## What to build Current Airflow DAGs use hardcoded `host.docker.internal` for both HTTP and Kafka connections. This works when services run on the ba…
- discussion: 1 / Fixed by PR #885 (runId filter + per-run group_id + connections.sh scheme fix). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#885/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#885/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #888 — refactor: 데드 코드 전면 제거 (6개 모듈)
- author zbnerd; CLOSED; created 2026-06-03T11:29:59Z; closed 2026-06-03T13:57:15Z; reason COMPLETED. Body: ## What to build 7개 모듈에서 식별된 미사용/데드 코드를 제거한다. ### 제거 대상 파일 이유 ------ ------ `module-synchronizer/repository/SynchronizerChunkStatusRepository.kt` 80줄,…
- discussion: 1 / Legacy module — not in active use.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #889 — refactor: 설정 바인딩 통합 — synchronizer ChunkExecutionProperties
- author zbnerd; CLOSED; created 2026-06-03T11:30:42Z; closed 2026-06-03T14:37:49Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `ChunkConsumerTemplate`에 산재된 `@Value` 4개를 `@ConfigurationProperties` data class로 통합한다. ### 현재 상태 `ChunkConsume…
- discussion: 1 / Duplicate — resolved by #909 (ChunkExecutionProperties created, 4 @Value replace…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#909/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #890 — refactor: 설정 바인딩 통합 — calculator CleanupProperties
- author zbnerd; CLOSED; created 2026-06-03T11:30:44Z; closed 2026-06-03T14:14:30Z; reason COMPLETED. Body: ## What to build `module-calculator`의 `CalculatorResultCleanupScheduler`에 산재된 `@Value` 7개를 `@ConfigurationProperties` data class로 통합한다. ### 현재 상태 `Cal…
- discussion: 2 / PR: #908. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#908/MERGED]; reachable commit-message refs 2 [23bee6bff072, 717bbfa3aafc]; PR-record issue refs 1 [#908/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #891 — refactor: CalculatorApplication 18-class @Import → AutoConfiguration
- author zbnerd; CLOSED; created 2026-06-03T11:30:46Z; closed 2026-06-03T14:14:32Z; reason COMPLETED. Body: ## What to build `module-calculator`의 `CalculatorApplication`에 하드코딩된 18개 `@Import`를 module-core의 `AutoConfiguration` 클래스 1개로 교체한다. ### 현재 상태 ```kotlin…
- discussion: 2 / PR: #908. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#908/MERGED]; reachable commit-message refs 2 [23bee6bff072, 717bbfa3aafc]; PR-record issue refs 1 [#908/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #892 — refactor: Phase 배치 실행기 추출 (module-external-api)
- author zbnerd; CLOSED; created 2026-06-03T11:30:49Z; closed 2026-06-14T16:00:20Z; reason COMPLETED. Body: ## What to build `module-external-api`의 `OcidLookupPhase`와 `SnapshotFetchPhase`에서 중복되는 재귀 배치 패턴을 공통 `BoundedAsyncBatchExecutor<T>`로 추출한다. ### 현재 상태 두 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #893 — refactor: 코디네이터 메트릭 관심사 분리 — calculator
- author zbnerd; CLOSED; created 2026-06-03T11:31:46Z; closed 2026-06-03T14:35:32Z; reason COMPLETED. Body: ## What to build `module-calculator`의 `CalculatorChunkProcessingCoordinator`에서 메트릭 기록 로직(~44%, 60줄)을 이벤트 리스너로 추출한다. ### 현재 상태 `CalculatorChunkProcessi…
- discussion: 1 / Resolved by #909. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#909/MERGED]; reachable commit-message refs 1 [cd793dcf5ebf]; PR-record issue refs 1 [#909/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #894 — refactor: 컨슈머 메트릭 관심사 분리 — synchronizer
- author zbnerd; CLOSED; created 2026-06-03T11:31:48Z; closed 2026-06-03T14:35:34Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `KafkaResultChunkConsumer`에서 콜백 내 메트릭 호출(6+ calls)을 이벤트 리스너로 추출한다. ### 현재 상태 `KafkaResultChunkConsumer`의 onSuc…
- discussion: 1 / Resolved by #909. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#909/MERGED]; reachable commit-message refs 1 [cd793dcf5ebf]; PR-record issue refs 1 [#909/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #895 — arch: ChunkConsumerTemplate 관심사 분해 설계
- author zbnerd; CLOSED; created 2026-06-03T11:31:50Z; closed 2026-06-03T14:35:37Z; reason COMPLETED. Body: ## What to build `module-synchronizer`의 `ChunkConsumerTemplate` (~320줄)이 담당하는 6개 관심사를 분리하는 설계를 합의한다. ### 현재 상태 단일 클래스가 다음 6개 관심사를 모두 처리: 1. DB 백업 상태 머…
- discussion: 1 / Resolved by #909. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#909/MERGED]; reachable commit-message refs 1 [cd793dcf5ebf]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #896 — refactor: 레거시 domain/v2 이관 및 중복 enum 제거 (module-infra)
- author zbnerd; CLOSED; created 2026-06-03T11:31:52Z; closed 2026-06-05T04:37:46Z; reason COMPLETED. Body: ## What to build `module-infra/domain/v2/`의 레거시 JPA 엔티티 7개를 올바른 위치(`infrastructure/persistence/entity/`)로 이관하고, module-core와 중복되는 enum을 통합한다. ### 현재 상…
- discussion: 1 / Closed by PR #1148 (merge ac704b1d). Moved 7 v2 entities + 5 port interfaces to …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1148/MERGED]; reachable commit-message refs 1 [ac704b1d6163]; PR-record issue refs 1 [#1148/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #897 — arch: module-core 아웃바운드 포트 감사 — 실제 seam vs 가설 seam 분류
- author zbnerd; CLOSED; created 2026-06-03T11:32:36Z; closed 2026-06-05T04:50:13Z; reason COMPLETED. Body: ## What to build `module-core`의 47개 아웃바운드 포트 인터페이스를 감사하여, 실제 seam(어댑터 2개+)과 가설 seam(어댑터 1개)을 분류한다. ### 현재 상태 `module-core/src/main/.../core/port/out/`…
- discussion: 1 / Closed via PR #1154 (squash-merged to develop). Deliverables: - docs/superpowers…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1154/MERGED]; reachable commit-message refs 2 [2809de25cf40, ed74670bee69]; PR-record issue refs 2 [#1154/MERGED, #1160/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #898 — refactor: 중복 포트 어댑터 통합 — rest-controller → module-infra
- author zbnerd; CLOSED; created 2026-06-03T11:32:57Z; closed 2026-06-05T04:45:31Z; reason COMPLETED. Body: ## What to build `module-rest-controller`에 존재하는 중복 어댑터를 제거하고, `module-infra`에 이미 존재하는 어댑터를 재사용한다. ### 중복 어댑터 포트 인터페이스 rest-controller 어댑터 module-infra…
- discussion: 1 / 코드 조사 결과 이슈 본문이 전제한 중복 어댑터가 현 시점 코드에 존재하지 않음. **확인 결과:** - `JdbcLikeToggleServic…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #899 — arch: module-rest-controller 미니 모놀리스 분해 — 인프라 추출
- author zbnerd; CLOSED; created 2026-06-03T11:32:59Z; closed 2026-06-05T04:48:03Z; reason COMPLETED. Body: ## What to build `module-rest-controller`에 혼재된 인프라 코드(JDBC, Redis, Kafka)를 `module-infra`로 이관하고, module-infra에 재활용 가능한 코드가 있으면 우선 재사용한다. ### 현재 상태 mod…
- discussion: 1 / 코드 조사 결과 이슈 전제와 현재 아키텍처 불일치로 close. **확인 결과:** 1. **module-infra에 Kafka/Redis 코드…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #900 — security: 프로덕션 시크릿 노출 — 즉시 로테이션 필요
- author zbnerd; CLOSED; created 2026-06-03T11:47:15Z; closed 2026-06-03T13:52:57Z; reason COMPLETED. Body: ## What to build 프로덕션 시크릿이 코드 저장소에 노출되어 있음. 즉시 로테이션 필요. ### CRITICAL 발견 ID 파일 내용 ---- ------ ------ C-01 `.env` (4-8행) 프로덕션 Supabase DB 비밀번호, JDBC URL…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #901 — refactor: join()/get()/runBlocking 위반 15건 제거
- author zbnerd; CLOSED; created 2026-06-03T11:47:17Z; closed 2026-06-04T03:32:15Z; reason COMPLETED. Body: ## What to build 프로젝트 규칙에 따라 서버 코드에서 `.join()`, `.get()`, `runBlocking` 사용이 금지되어 있으나 15건 발견됨. ### CRITICAL: runBlocking (3건) 파일 라인 컨텍스트 ------ ------ …
- discussion: 2 / **PGMQ는 레거시로 제거 대상.** PgmqWorker runBlocking 항목은 이 이슈에서 제외. 나머지 14건만 처리.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [4e66ba3cd030, da78f6163b2f]; PR-record issue refs 1 [#1121/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #902 — refactor: 중복 코드 통합 — KafkaConsumerConfig, CharacterId, gzipCompress/sha256Hex
- author zbnerd; CLOSED; created 2026-06-03T11:47:19Z; closed 2026-06-03T14:57:59Z; reason COMPLETED. Body: ## What to build 모듈 간 중복 코드 16개 패턴(~795줄) 중 즉시 통합 가능한 것들을 처리한다. ### 우선순위 HIGH ID 중복 내용 위치 중복 줄 ---- ---------- ------ --------- DUP-1 `KafkaConsumerCo…
- discussion: 1 / Resolved by #910 (DUP-10, DUP-6, DUP-3). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#910/MERGED]; reachable commit-message refs 2 [425728513ca0, 42b282613a62]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #903 — refactor: God 클래스 분해 — ExternalApiWorker, PgmqWorker, PgmqClient
- author zbnerd; CLOSED; created 2026-06-03T11:47:21Z; closed 2026-06-03T14:21:43Z; reason COMPLETED. Body: ## What to build 혼합 책임을 가진 God 클래스 5개를 단일 책임 컴포넌트로 분해한다. ### 분해 대상 클래스 파일 줄 수 혼합 책임 -------- ------ ------- ---------- `ExternalApiWorker` module-infr…
- discussion: 1 / **PGMQ는 레거시로 제거 대상.** PgmqWorker(488줄), PgmqClient(522줄) 분해는 이 이슈에서 제외. External…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #904 — arch: LogicExecutor/TaskContext를 module-core로 승격 — 전체 결합도 핵심 해결
- author zbnerd; CLOSED; created 2026-06-03T11:48:28Z; closed 2026-06-05T04:43:46Z; reason COMPLETED. Body: ## What to build `LogicExecutor` (Ca=149)와 `TaskContext` (Ca=144)가 `module-infra`에 정의되어 있어, 모든 모듈이 `module-infra`에 의존하게 됨. 이 두 타입을 `module-core`로 승격하여…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #905 — refactor: 동시성 안티패턴 수정 — Race Condition, Semaphore Leak, Thread Starvation
- author zbnerd; CLOSED; created 2026-06-03T11:48:29Z; closed 2026-06-08T04:29:26Z; reason COMPLETED. Body: ## What to build 동시성 관련 안티패턴 4건을 수정한다. ### 1. Semaphore Leak — SimpleAdmissionControl (HIGH) **파일:** `module-infra/.../admission/SimpleAdmissionContro…
- discussion: 1 / **PGMQ는 레거시로 제거 대상.** PgmqWorker thread starvation 항목은 이 이슈에서 제외. 나머지 3건(SimpleA…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [4a1a4fc303b1, e9ada29a0bf2]; PR-record issue refs 1 [#1122/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #906 — refactor: module-core 포트 인터페이스 기술명 노출 제거 (Leaky Abstraction)
- author zbnerd; CLOSED; created 2026-06-03T11:48:31Z; closed 2026-06-05T04:21:59Z; reason COMPLETED. Body: ## What to build module-core의 포트 인터페이스에 PGMQ, Redis 등 인프라 기술명이 노출되어 있어, 구현 교체 시 인터페이스까지 수정 필요. ### 노출된 기술명 포트 인터페이스 문제 해결 ---------------- ------ ----…
- discussion: 2 / **PGMQ는 레거시로 제거 대상.** PgmqPort rename 항목은 이 이슈에서 제외. 나머지 Redis/MySQL 메서드명 변경, Ja…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 8 [480c08a97653, 56857db67199, 7947bacc69a7, 92700f008b5d, a6a3b0f8e771, b9274797adca, ce2a2f6424ce, e950307733f9]; PR-record issue refs 1 [#1146/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #907 — arch: module-infra 모듈화 — 444파일 God Module 분해 로드맵
- author zbnerd; CLOSED; created 2026-06-03T11:48:33Z; closed 2026-06-06T05:38:03Z; reason COMPLETED. Body: ## What to build `module-infra` (444파일, 48,620줄, 전체 코드의 51%)를 응집도 높은 서브모듈로 분해하는 로드맵을 수립한다. ### 현재 상태 module-infra가 domain 모델, application 서비스, port ad…
- discussion: 2 / **PGMQ는 레거시로 제거 대상.** `module-mq` 서브모듈 추출 항목은 로드맵에서 제외. 나머지 7개 서브모듈(module-execu…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2526bde04c51, 35df34f7132e]; PR-record issue refs 2 [#1155/MERGED, #1169/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #913 — refactor: ChunkConsumerTemplate.submit() 분해 — 86줄 → 5개 private method
- author zbnerd; CLOSED; created 2026-06-03T15:37:41Z; closed 2026-06-14T16:00:21Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate.submit()` 메서드가 86줄로 10개 책임을 가짐. 각 상태 핸들링 로직을 private method로 추출. **대상 파일:** `module-synchronizer/.../consumer/…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #914 — refactor: BatchReadScheduler.resolveBatch() 분해 — 65줄 → 4개 private method
- author zbnerd; CLOSED; created 2026-06-03T15:37:45Z; closed 2026-06-14T16:00:21Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler.resolveBatch()` 메서드가 65줄로 6개 책임, 중첩 if 3레벨 포함. cache hit/miss 처리와 urgent trigger를 분리. **대상 파일:** `module-rest-con…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #915 — refactor: ReadModelQueryService.batchQuery() 분해 — 64줄 → 3개 private method
- author zbnerd; CLOSED; created 2026-06-03T15:37:48Z; closed 2026-06-14T16:00:21Z; reason COMPLETED. Body: ## What to build `ReadModelQueryService.batchQuery()` 메서드가 64줄로 SQL 빌드 + 실행 + row 파싱 혼합. SQL 생성과 row-to-response 변환을 분리. **대상 파일:** `module-rest-contr…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #916 — refactor: SnapshotFetchPhase.execute() + processBatch() 분해 — 64줄/50줄
- author zbnerd; CLOSED; created 2026-06-03T15:37:50Z; closed 2026-06-14T16:00:21Z; reason COMPLETED. Body: ## What to build `SnapshotFetchPhase`의 `execute()` (64줄)와 `processBatch()` (50줄)이 각각 다수 책임. setup/execute/finalize 단계 분리. **대상 파일:** `module-external-…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #917 — refactor: OcidLookupPhase.execute() + processBatch() 분해 — 53줄/33줄
- author zbnerd; CLOSED; created 2026-06-03T15:37:53Z; closed 2026-06-14T16:00:22Z; reason COMPLETED. Body: ## What to build `OcidLookupPhase`의 `execute()` (53줄)와 `processBatch()` (33줄)이 다수 책임 혼합. setup, execution, output 분리. **대상 파일:** `module-external-api/…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #918 — refactor: NexonExternalApiClientAdapter.fetch() 분해 — 54줄 reactive chain
- author zbnerd; CLOSED; created 2026-06-03T15:37:56Z; closed 2026-06-14T16:00:22Z; reason COMPLETED. Body: ## What to build `NexonExternalApiClientAdapter.fetch()` (54줄)가 URI 빌드 + API 호출 + metrics 기록 + 에러 핸들링 혼합. metrics 콜백과 에러 핸들러를 private method로 추출. **대상…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #919 — Extract ReadModelDocumentMapper from ReadModelQueryService.batchQuery()
- author zbnerd; CLOSED; created 2026-06-03T15:38:21Z; closed 2026-06-14T16:00:22Z; reason COMPLETED. Body: ## What to build `ReadModelQueryService.batchQuery()` has 5 mixed responsibilities: dynamic SQL construction, JDBC execution, GZIP decompression, JSON…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #920 — Remove OCID resolution from JdbcLikeToggleService
- author zbnerd; CLOSED; created 2026-06-03T15:38:23Z; closed 2026-06-14T16:00:23Z; reason COMPLETED. Body: ## What to build `JdbcLikeToggleService` mixes external API call (OCID resolution via `characterOcidPort.resolveOcid`) with DB operations (SELECT EXIS…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #921 — Extract file lifecycle and event publishing from ChunkedSnapshotSink
- author zbnerd; CLOSED; created 2026-06-03T15:38:25Z; closed 2026-06-07T10:03:08Z; reason COMPLETED. Body: ## What to build `ChunkedSnapshotSink` mixes 6+ responsibilities in `close()`: file flush, manifest write, `_SUCCESS` marker creation, `_RUNNING` mark…
- discussion: 1 / Resolved by #987 — `SnapshotSinkEventPublisher` now handles chunk-ready/run-comp…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #922 — Extract API fetch and domain orchestration from UrgentCharacterRequestConsumer
- author zbnerd; CLOSED; created 2026-06-03T15:38:28Z; closed 2026-06-14T16:00:23Z; reason COMPLETED. Body: ## What to build `UrgentCharacterRequestConsumer` mixes API calls (OCID lookup, character-basic, item-equipment), response parsing, domain orchestrati…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #923 — Decompose DefaultChunkProcessor.process() into read / transform / write stages
- author zbnerd; CLOSED; created 2026-06-03T15:38:31Z; closed 2026-06-07T10:20:42Z; reason COMPLETED. Body: ## What to build `DefaultChunkProcessor.process()` has 7 mixed responsibilities: file read, DB query, domain transformation, serialization + compressi…
- discussion: 1 / Closed by squash-merge of PR #1143. `DefaultChunkProcessor.process()` decomposed…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1143/MERGED]; reachable commit-message refs 1 [974efe438cd7]; PR-record issue refs 1 [#1143/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #924 — Extract ChunkExecutionStateManager from ChunkConsumerTemplate
- author zbnerd; CLOSED; created 2026-06-03T15:38:34Z; closed 2026-06-14T16:00:23Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate` mixes DB state management (INSERT pending, query state, CAS claim), validation (schema version check, skip/re…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #925 — Extract SnapshotRecordParser from SnapshotChunkProcessor.parseLines()
- author zbnerd; CLOSED; created 2026-06-03T15:38:36Z; closed 2026-06-14T16:00:23Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor.parseLines()` mixes Channel I/O, JSON deserialization, status filtering, domain parsing, FlatItem constructio…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #926 — Separate event publishing and metrics from CalculatorChunkProcessingCoordinator.onChunkPro…
- author zbnerd; CLOSED; created 2026-06-03T15:38:38Z; closed 2026-06-14T16:00:24Z; reason COMPLETED. Body: ## What to build `CalculatorChunkProcessingCoordinator` mixes validation, query (existence checks), event publishing, and metrics recording across `ha…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #927 — Move read-through-cache logic from ExpectationV6Controller.getStatus() to service layer
- author zbnerd; CLOSED; created 2026-06-03T15:39:03Z; closed 2026-06-14T16:00:24Z; reason COMPLETED. Body: ## What to build `ExpectationV6Controller.getStatus()` is a controller method that directly orchestrates read-through-cache logic: cache read → state …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #928 — Decompose BatchReadScheduler.resolveBatch() into cache / DB / urgent trigger
- author zbnerd; CLOSED; created 2026-06-03T15:39:06Z; closed 2026-06-14T16:00:24Z; reason COMPLETED. Body: ## What to build `BatchReadScheduler.resolveBatch()` has 6 mixed responsibilities: cache lookup, DB query, cache write, urgent trigger (Redis SETNX + …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #929 — refactor: ChunkConsumerTemplate.markFailureAndAck() 분해 — 50줄 → 3개 private method
- author zbnerd; CLOSED; created 2026-06-03T15:39:12Z; closed 2026-06-14T16:00:25Z; reason COMPLETED. Body: ## What to build `ChunkConsumerTemplate.markFailureAndAck()` 메서드가 50줄로 failure 분류 + DB 기록 + metrics + callback + ACK 혼합. 중첩 if 3레벨. **대상 파일:** `module…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #930 — refactor: Synchronizer consumer inline lambda → named method (KafkaResult + BasicSnapshot)
- author zbnerd; CLOSED; created 2026-06-03T15:39:15Z; closed 2026-06-14T16:00:25Z; reason COMPLETED. Body: ## What to build `KafkaResultChunkConsumer.consume()` (76줄)과 `BasicSnapshotChunkConsumer.submitBasicChunk()` (70줄)이 ChunkConsumerRequest에 inline lambd…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #931 — refactor: SnapshotChunkProcessor.process() 분해 — 48줄 coroutine pipeline
- author zbnerd; CLOSED; created 2026-06-03T15:39:18Z; closed 2026-06-14T16:00:25Z; reason COMPLETED. Body: ## What to build `SnapshotChunkProcessor.process()` (48줄)가 3단계 coroutine pipeline + counter 조립 혼합. 각 pipeline stage를 private method로 추출. **대상 파일:** `m…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #932 — refactor: CalculatorChunkProcessingCoordinator.onChunkProcessed() 분해 — 41줄
- author zbnerd; CLOSED; created 2026-06-03T15:39:21Z; closed 2026-06-14T16:00:25Z; reason COMPLETED. Body: ## What to build `CalculatorChunkProcessingCoordinator.onChunkProcessed()` (41줄)가 event 발행 + 로깅 + metrics 혼합. **대상 파일:** `module-calculator/.../Calcul…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #933 — refactor: Synchronizer repository SQL 메서드 분해 (CharacterBasic, Equipment, OcidMapping)
- author zbnerd; CLOSED; created 2026-06-03T15:39:24Z; closed 2026-06-05T00:38:16Z; reason COMPLETED. Body: ## What to build 3개 repository의 batch 메서드가 SQL 문자열 + 파라미터 바인딩 혼합으로 과도히 긺. **대상 파일:** - `module-synchronizer/.../repository/CharacterBasicRepository.kt…
- discussion: 1 / Merged via #1141. SQL decomposition complete.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#1141/MERGED]; reachable commit-message refs 1 [91269c42211c]; PR-record issue refs 1 [#1141/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #934 — refactor: External-api remaining long methods (ChunkedSnapshotSink, UrgentConsumer, AuthCo…
- author zbnerd; CLOSED; created 2026-06-03T15:39:26Z; closed 2026-06-14T16:00:26Z; reason COMPLETED. Body: ## What to build 3개 컴포넌트의 긴 메서드 분해. **대상 파일:** ### 1. ChunkedSnapshotSink.close() — 46줄 `module-external-api/.../snapshot/ChunkedSnapshotSink.kt:87-13…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #935 — refactor: Rest-controller remaining long methods (PopularService, LikeToggle, JwtAuth, Exp…
- author zbnerd; CLOSED; created 2026-06-03T15:39:29Z; closed 2026-06-14T16:00:28Z; reason COMPLETED. Body: ## What to build 4개 컴포넌트의 긴 메서드 분해. **대상 파일:** ### 1. BatchReadScheduler.stop() — 37줄 `module-rest-controller/.../read/BatchReadScheduler.kt:39-76` dr…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #936 — refactor: DRY — KafkaSnapshotChunkReadyConsumer consume/consumeUrgent 복제 제거
- author zbnerd; CLOSED; created 2026-06-03T15:39:32Z; closed 2026-06-14T16:00:28Z; reason COMPLETED. Body: ## What to build `KafkaSnapshotChunkReadyConsumer`의 `consume()` (20줄)과 `consumeUrgent()` (18줄)이 try-catch + ACK 로직을 복제. "URGENT" 로그 prefix만 다름. **대상 파…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #937 — refactor: DRY — SnapshotEventPublisherConfig 8개 bean → factory method
- author zbnerd; CLOSED; created 2026-06-03T15:39:35Z; closed 2026-06-14T16:00:29Z; reason COMPLETED. Body: ## What to build `SnapshotEventPublisherConfig` (125줄)가 4쌍의 거의 동일한 bean 정의 포함. 각 쌍은 NoOp + Kafka 구현. `@Qualifier`와 topic config만 다름. **대상 파일:** `modul…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #938 — Rename shared types: RunInfo, RunCleanupResult, LikeToggleResult, ErrorInfo
- author zbnerd; CLOSED; created 2026-06-03T15:40:39Z; closed 2026-06-14T16:00:29Z; reason COMPLETED. Body: ## What to build Rename 4 generic type names in `module-common` and `module-core` to intention-revealing alternatives, and update all import sites acr…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #939 — Rename VirtualThreadExecutorManager → VirtualThreadExecutorLifecycle
- author zbnerd; CLOSED; created 2026-06-03T15:40:41Z; closed 2026-06-14T16:00:29Z; reason COMPLETED. Body: ## What to build Rename `VirtualThreadExecutorManager` in `module-infra` to `VirtualThreadExecutorLifecycle`. The class owns creation + graceful shutd…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #940 — Rename calculator DTOs and classes: ChunkResult, CalculationResult, SnapshotChunkProcessor…
- author zbnerd; CLOSED; created 2026-06-03T15:41:36Z; closed 2026-06-14T16:00:30Z; reason COMPLETED. Body: ## What to build Rename vague type names in `module-calculator` to intention-revealing alternatives. This is the highest-impact naming refactor in the…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #941 — Rename synchronizer types: ChunkProcessor, ChunkProcessResult, processor package, etc.
- author zbnerd; CLOSED; created 2026-06-03T15:41:44Z; closed 2026-06-14T16:00:30Z; reason COMPLETED. Body: ## What to build Rename vague type names in `module-synchronizer` to intention-revealing alternatives. Includes interface, implementation, DTOs, packa…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #489 — [P0] ArchUnit 의존성 규칙 - Core 모듈 순수성 검증
- author zbnerd; CLOSED; created 2026-03-01T06:16:56Z; closed 2026-03-10T15:58:07Z; reason COMPLETED. Body: ## 🎯 목표 module-core가 Spring/Infra/Web 의존하지 않음을 CI에서 강제 ## 📋 배경 ADR-004에서 cube 모듈 이관 시 Spring 의존이 문제였음 core 모듈은 순수 Java/Kotlin만 허용해야 MSA 분리 가능 ## ✅ 작업 …
- discussion: 1 / ✅ Already exists: CoreDependencyRuleTest.java - module-core/src/test/java/maple/…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc64d9bb1e66]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #490 — [P0] ArchUnit 의존성 규칙 - 모듈 간 의존 방향 검증
- author zbnerd; CLOSED; created 2026-03-01T06:16:57Z; closed 2026-03-10T15:58:09Z; reason COMPLETED. Body: ## 🎯 목표 ADR-005 의존성 그래프를 빌드 실패로 강제 ## 📋 배경 작은 PR에서 infra → app, common → web 같은 규칙 위반이 스며들 위험 사람이 지키기 어렵고 CI로 강제해야 함 ## ✅ 작업 항목 - [ ] app → web 의존 금지 …
- discussion: 1 / ✅ Already exists: ModuleDependencyTest.java - module-web/src/test/java/maple/exp…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc64d9bb1e66]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #491 — [P0] DTO 소유권 규칙 문서화
- author zbnerd; CLOSED; created 2026-03-01T06:16:59Z; closed 2026-03-10T15:58:05Z; reason COMPLETED. Body: ## 🎯 목표 DTO가 레이어/모듈 경계를 넘나들며 결합도를 만드는 문제 방지 ## 📋 배경 현재 module-web, module-app, module-common에 DTO 혼재 가능성 "복잡 DTO로 컨트롤러 이관 보류" 상황 해결 필요 ## ✅ 작업 항목 - [ …
- discussion: 1 / ✅ Completed: DTO 소유권 규칙 문서화 완료 - docs/03_Technical_Guides/dto-ownership.md 추가 - …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc64d9bb1e66]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #492 — [P0] ArchUnit 의존성 규칙 - Core 모듈 순수성 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:05Z; closed 2026-03-01T06:20:29Z; reason COMPLETED. Body: ## 🎯 목표 module-core가 Spring/Infra/Web 의존하지 않음을 CI에서 강제 ## 📋 배경 ADR-004에서 cube 모듈 이관 시 Spring 의존이 문제였음. core 모듈은 순수 Java/Kotlin만 허용해야 MSA 분리 가능. ## ✅ 작…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #493 — [P0] ArchUnit 의존성 규칙 - 모듈 간 의존 방향 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:07Z; closed 2026-03-01T06:20:31Z; reason COMPLETED. Body: ## 🎯 목표 ADR-005 의존성 그래프를 빌드 실패로 강제 ## 📋 배경 작은 PR에서 infra → app, common → web 같은 규칙 위반이 스며들 위험. 사람이 지키기 어렵고 CI로 강제해야 함. ## ✅ 작업 항목 - [ ] app → web 의존 금…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #494 — [P0] DTO 소유권 규칙 문서화
- author zbnerd; CLOSED; created 2026-03-01T06:17:09Z; closed 2026-03-01T06:20:32Z; reason COMPLETED. Body: ## 🎯 목표 DTO가 레이어/모듈 경계를 넘나들며 결합도를 만드는 문제 방지 ## 📋 배경 현재 module-web, module-app, module-common에 DTO 혼재 가능성. "복잡 DTO로 컨트롤러 이관 보류" 상황 해결 필요. ## ✅ 작업 항목 - …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #495 — [P0] DTO 의존 방향 ArchUnit 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:10Z; closed 2026-03-01T06:20:33Z; reason COMPLETED. Body: ## 🎯 목표 DTO 소유권 규칙을 자동화하여 위반 시 CI 실패 ## 📋 배경 문서만으로는 규칙 위반 방지 불가. 자동화로 "DTO 지옥" 재발 방지. ## ✅ 작업 항목 - [ ] core/app에서 web DTO 참조 금지 규칙 - [ ] common DTO에서 …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #496 — [P0] Port 작성 가이드 문서화
- author zbnerd; CLOSED; created 2026-03-01T06:17:12Z; closed 2026-03-01T06:20:35Z; reason COMPLETED. Body: ## 🎯 목표 Port가 "MSA 분리 시 서비스 간 계약" 역할을 하도록 가이드 확정 ## 📋 배경 Port가 흔들리면 어댑터/컨트롤러/유즈케이스가 함께 흔들림. web 타입이 port에 섞이면 경계 즉시 무너짐. ## ✅ 작업 항목 - [ ] Port 작성 가이드 …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #497 — [P0] Port 금지 타입 ArchUnit 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:13Z; closed 2026-03-01T06:20:36Z; reason COMPLETED. Body: ## 🎯 목표 Port에 web/infra 타입이 섞이는 것을 자동화로 방지 ## 📋 배경 Port 시그니처에 ResponseEntity, Pageable 등이 들어오면 MSA 분리 시 서비스 간 계약이 무너짐. ## ✅ 작업 항목 - [ ] Port에서 금지 타입 사…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #498 — [P0] DTO 의존 방향 ArchUnit 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:33Z; closed 2026-03-10T15:58:11Z; reason COMPLETED. Body: ## 🎯 목표 DTO 소유권 규칙을 자동화하여 위반 시 CI 실패 ## 📋 배경 문서만으로는 규칙 위반 방지 불가 자동화로 "DTO 지옥" 재발 방지 ## ✅ 작업 항목 - [ ] core/app에서 web DTO 참조 금지 규칙 - [ ] common DTO에서 sp…
- discussion: 1 / ⏸️ Deferred to v2 rewrite DTO의 domain 타입 의존은 현재 아키텍처에서 정상적인 패턴임. - CubeCalculati…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #499 — [P0] Port 작성 가이드 문서화
- author zbnerd; CLOSED; created 2026-03-01T06:17:34Z; closed 2026-03-10T15:58:03Z; reason COMPLETED. Body: ## 🎯 목표 Port가 "MSA 분리 시 서비스 간 계약" 역할을 하도록 가이드 확정 ## 📋 배경 Port가 흔들리면 어댑터/컨트롤러/유즈케이스가 함께 흔들림 web 타입이 port에 섞이면 경계 즉시 무너짐 ## ✅ 작업 항목 - [ ] Port 작성 가이드 작성…
- discussion: 1 / ✅ Completed: Port 작성 가이드 문서화 완료 - docs/03_Technical_Guides/port-guide.md 추가 - Co…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #500 — [P0] Port 금지 타입 ArchUnit 검증
- author zbnerd; CLOSED; created 2026-03-01T06:17:36Z; closed 2026-03-10T16:28:44Z; reason COMPLETED. Body: ## 🎯 목표 Port에 web/infra 타입이 섞이는 것을 자동화로 방지 ## 📋 배경 Port 시그니처에 ResponseEntity, Pageable 등이 들어오면 MSA 분리 시 서비스 간 계약이 무너짐 ## ✅ 작업 항목 - [ ] Port에서 금지 타입 사용…
- discussion: 0. Linked PRs: 1 [#586/MERGED/merged=yes 9b58c9d264a5c693538ef49ac3e718b5c2e79c09]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#586/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [457603788536, 670c2c09ee2f, 9b58c9d264a5]; PR-record issue refs 2 [#585/CLOSED, #586/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 기능 case.

### Issue #501 — [P1] 이벤트 네이밍/공통 필드 표준 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:03Z; closed 2026-03-15T08:00:40Z; reason COMPLETED. Body: ## 🎯 목표 이벤트를 안정적인 계약으로 만들기 위한 v1 스키마 정의 ## 📋 배경 향후 CQRS/MSA 분리 시 이벤트는 API만큼 중요 표준 없으면 소비자 호환성 관리 불가 ## ✅ 작업 항목 - [ ] 이벤트 네이밍 규칙: `{domain}.{action}.v{…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [457603788536, 670c2c09ee2f]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #502 — [P1] 샘플 이벤트 3개 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:05Z; closed 2026-03-15T07:59:42Z; reason COMPLETED. Body: ## 🎯 목표 이벤트 계약 v1 적용 예시 3개 작성 ## ✅ 작업 항목 - [ ] 이벤트 1: CharacterCalculatedEvent - [ ] 이벤트 2: DonationCreatedEvent - [ ] 이벤트 3: CacheInvalidatedEvent - …
- discussion: 1 / ✅ **Verified Complete** All requirements met: - Sample Event 1: `docs/events/sam…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [457603788536, 52bcc2d5cab9, 670c2c09ee2f, 8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #503 — [P1] 이벤트 변경 호환성 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:06Z; closed 2026-03-15T07:59:44Z; reason COMPLETED. Body: ## 🎯 목표 이벤트 스키마 변경 시 backward compatible 원칙 정의 ## ✅ 작업 항목 - [ ] 필드 추가: 허용 (기본값 필수) - [ ] 필드 삭제: 금지 - [ ] 필드 타입 변경: 금지 - [ ] 필수 → 선택 변경: 허용 - [ ] 선택 → …
- discussion: 1 / ✅ **Verified Complete** All requirements met: - Compatibility Matrix (ALLOWED/CA…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [457603788536, 52bcc2d5cab9, 670c2c09ee2f, 8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #504 — [P1] 이벤트 네이밍/공통 필드 표준 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:09Z; closed 2026-03-01T06:20:37Z; reason COMPLETED. Body: ## 🎯 목표 이벤트를 안정적인 계약으로 만들기 위한 v1 스키마 정의 ## 📋 배경 향후 CQRS/MSA 분리 시 이벤트는 API만큼 중요. 표준 없으면 소비자 호환성 관리 불가. ## ✅ 작업 항목 - [ ] 이벤트 네이밍 규칙: `{domain}.{action}.…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #505 — [P1] 샘플 이벤트 3개 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:10Z; closed 2026-03-01T06:20:39Z; reason COMPLETED. Body: ## 🎯 목표 이벤트 계약 v1 적용 예시 3개 작성 ## ✅ 작업 항목 - [ ] 이벤트 1: CharacterCalculatedEvent - [ ] 이벤트 2: DonationCreatedEvent - [ ] 이벤트 3: CacheInvalidatedEvent - …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #506 — [P1] 이벤트 변경 호환성 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:12Z; closed 2026-03-01T06:20:40Z; reason COMPLETED. Body: ## 🎯 목표 이벤트 스키마 변경 시 backward compatible 원칙 정의 ## ✅ 작업 항목 - [ ] 필드 추가: 허용 (기본값 필수) - [ ] 필드 삭제: 금지 - [ ] 필드 타입 변경: 금지 - [ ] 필수 → 선택 변경: 허용 - [ ] 선택 → …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #507 — [P1] Outbox/Idempotency 패턴 선택 ADR
- author zbnerd; CLOSED; created 2026-03-01T06:18:13Z; closed 2026-03-15T07:59:45Z; reason COMPLETED. Body: ## 🎯 목표 At-least-once 환경에서 DB 반영 + 이벤트 발행 신뢰성 전략 결정 ## 📋 배경 완전 exactly-once 불가 체감. 최소한 전략 결정 필요. ## ✅ 작업 항목 - [ ] 대안 비교: Transactional Outbox + 폴링/CDC…
- discussion: 1 / ✅ **Verified Complete** All requirements met: - Pattern Selection ADR: ADR-010 (…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [52bcc2d5cab9, 8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #508 — [P1] Core 단위 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:14Z; closed 2026-03-15T08:46:03Z; reason COMPLETED. Body: ## 🎯 목표 core 모듈의 순수 단위 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] core 테스트 원칙 문서화 - [ ] Mocking 없이 순수 로직 테스트 - [ ] 빠르게, 많게 원칙 - [ ] 샘플 테스트 1개 작성 - [ ] JUnit 5 + Asser…
- discussion: 1 / Implemented in PR #603 - Core Unit Test Template with Given-When-Then helpers an…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#603/MERGED]; reachable commit-message refs 4 [52bcc2d5cab9, 8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 2 [#602/MERGED, #603/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #509 — [P1] App Usecase 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:16Z; closed 2026-03-15T08:46:04Z; reason COMPLETED. Body: ## 🎯 목표 app 모듈의 usecase 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] app 테스트 원칙 문서화 - [ ] Port mocking 패턴 - [ ] Usecase 입력/출력 검증 - [ ] 샘플 테스트 1개 작성 - [ ] Mockito vs 수동 …
- discussion: 1 / Implemented in PR #603 - App Usecase/Service Test Templates with IntegrationTest…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#603/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #510 — [P1] Infra Adapter 통합 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:17Z; closed 2026-03-15T08:46:06Z; reason COMPLETED. Body: ## 🎯 목표 infra 모듈의 adapter 통합 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] infra 테스트 원칙 문서화 - [ ] Testcontainers 패턴 (MySQL/Redis) - [ ] 슬라이스 테스트 (@DataJpaTest 등) - [ ] 샘…
- discussion: 1 / Implemented in PR #603 - Infra Adapter Integration Test Template with Circuit Br…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#603/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #511 — [P1] Web Controller 계약 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:19Z; closed 2026-03-15T08:46:07Z; reason COMPLETED. Body: ## 🎯 목표 web 모듈의 controller 계약 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] web 테스트 원칙 문서화 - [ ] MockMvc 패턴 - [ ] Request/Response 계약 검증 - [ ] 샘플 테스트 1개 작성 - [ ] Validat…
- discussion: 1 / Implemented in PR #603 - Web Controller Contract Test Template with HTTP request…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#603/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #512 — [P1] P0 스모크 테스트 시나리오 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:20Z; closed 2026-03-15T08:46:09Z; reason COMPLETED. Body: ## 🎯 목표 회귀 방지를 위한 핵심 시나리오 5~10개 정의 ## ✅ 작업 항목 - [ ] 스모크 시나리오 리스트 작성 - [ ] 우선순위: 캐릭터 조회 > 계산 > 좋아요 > 기부 - [ ] 각 시나리오별 검증 포인트 정의 - [ ] 자동화 가능 여부 표시 ## 📏…
- discussion: 1 / Implemented in PR #603 - P0 Smoke Test Scenarios documented and implemented (dis…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#603/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, eef957fb3a20]; PR-record issue refs 1 [#603/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #513 — [P1] Outbox/Idempotency 패턴 선택 ADR
- author zbnerd; CLOSED; created 2026-03-01T06:18:36Z; closed 2026-03-01T06:20:42Z; reason COMPLETED. Body: ## 🎯 목표 At-least-once 환경에서 DB 반영 + 이벤트 발행 신뢰성 전략 결정 ## 📋 배경 완전 exactly-once 불가 체감 최소한 전략 결정 필요 ## ✅ 작업 항목 - [ ] 대안 비교: - Transactional Outbox + 폴링/CDC…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #514 — [P1] Core 단위 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:38Z; closed 2026-03-01T06:20:43Z; reason COMPLETED. Body: ## 🎯 목표 core 모듈의 순수 단위 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] core 테스트 원칙 문서화 - [ ] Mocking 없이 순수 로직 테스트 - [ ] 빠르게, 많게 원칙 - [ ] 샘플 테스트 1개 작성 - [ ] JUnit 5 + Asser…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #515 — [P2] Observability Tracing 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:39Z; closed 2026-03-15T10:08:41Z; reason COMPLETED. Body: ## 🎯 목표 trace/span naming 및 전달 경로 규칙 정의 ## ✅ 작업 항목 - [ ] traceId 생성 규칙 - [ ] span naming 규칙 - [ ] MDC 전달 경로 (web → app → infra) - [ ] 공통 인터셉터/필터 위치 ##…
- discussion: 1 / Closed by PR #604 - ADR-026 observability-tracing-rules.md created with span cre…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#604/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, fc9873d11304]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #516 — [P1] App Usecase 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:18:39Z; closed 2026-03-01T06:20:44Z; reason COMPLETED. Body: ## 🎯 목표 app 모듈의 usecase 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] app 테스트 원칙 문서화 - [ ] Port mocking 패턴 - [ ] Usecase 입력/출력 검증 - [ ] 샘플 테스트 1개 작성 - [ ] Mockito vs 수동 …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #517 — [P2] Observability Metrics 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:18:40Z; closed 2026-03-15T10:08:43Z; reason COMPLETED. Body: ## 🎯 목표 metric naming 및 계측 위치 규칙 정의 ## ✅ 작업 항목 - [ ] metric naming 규칙 - [ ] "계측은 adapter에서만" 원칙 - [ ] KPI 메트릭 리스트 - [ ] Prometheus 쿼리 예시 ## 📏 DoD - [ …
- discussion: 1 / Closed by PR #604 - ADR-025 observability-metrics-rules.md created with naming c…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#604/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, fc9873d11304]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #518 — [P2] module-common 공개 API 정리
- author zbnerd; CLOSED; created 2026-03-01T06:18:42Z; closed 2026-03-15T10:08:44Z; reason COMPLETED. Body: ## 🎯 목표 module-common에서 공개/비공개 패키지 구분 ## ✅ 작업 항목 - [ ] 공개 패키지 목록 작성 - [ ] internal 금지 규칙 검토 - [ ] 공개 API 문서화 - [ ] 사용 가이드 작성 ## 📏 DoD - [ ] 공개 패키지 목록 …
- discussion: 1 / Closed by PR #604 - module-common-api-manifest.md created documenting 62 public …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#604/MERGED]; reachable commit-message refs 3 [8066cd454795, e91501d6a5d6, fc9873d11304]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #519 — [P1] Infra Adapter 통합 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:19:09Z; closed 2026-03-01T06:20:45Z; reason COMPLETED. Body: ## 🎯 목표 infra 모듈의 adapter 통합 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] infra 테스트 원칙 문서화 - [ ] Testcontainers 패턴 (MySQL/Redis) - [ ] 슬라이스 테스트 (@DataJpaTest 등) - [ ] 샘…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #520 — [P1] Web Controller 계약 테스트 템플릿
- author zbnerd; CLOSED; created 2026-03-01T06:19:11Z; closed 2026-03-01T06:20:47Z; reason COMPLETED. Body: ## 🎯 목표 web 모듈의 controller 계약 테스트 패턴 정립 ## ✅ 작업 항목 - [ ] web 테스트 원칙 문서화 - [ ] MockMvc 패턴 - [ ] Request/Response 계약 검증 - [ ] 샘플 테스트 1개 작성 - [ ] Validat…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #521 — [P1] P0 스모크 테스트 시나리오 정의
- author zbnerd; CLOSED; created 2026-03-01T06:19:12Z; closed 2026-03-01T06:20:48Z; reason COMPLETED. Body: ## 🎯 목표 회귀 방지를 위한 핵심 시나리오 5~10개 정의 ## ✅ 작업 항목 - [ ] 스모크 시나리오 리스트 작성 - [ ] 우선순위: 캐릭터 조회 > 계산 > 좋아요 > 기부 - [ ] 각 시나리오별 검증 포인트 정의 - [ ] 자동화 가능 여부 표시 ## 📏…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #522 — [P2] Observability Tracing 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:19:32Z; closed 2026-03-01T06:20:50Z; reason COMPLETED. Body: ## 🎯 목표 trace/span naming 및 전달 경로 규칙 정의 ## ✅ 작업 항목 - [ ] traceId 생성 규칙 - [ ] span naming 규칙 - [ ] MDC 전달 경로 (web → app → infra) - [ ] 공통 인터셉터/필터 위치 ##…
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [cc45f5129cc8]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #523 — [P2] Observability Metrics 규칙 정의
- author zbnerd; CLOSED; created 2026-03-01T06:19:34Z; closed 2026-03-01T06:20:51Z; reason COMPLETED. Body: ## 🎯 목표 metric naming 및 계측 위치 규칙 정의 ## ✅ 작업 항목 - [ ] metric naming 규칙 - [ ] "계측은 adapter에서만" 원칙 - [ ] KPI 메트릭 리스트 - [ ] Prometheus 쿼리 예시 ## 📏 DoD - [ …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #524 — [P2] module-common 공개 API 정리
- author zbnerd; CLOSED; created 2026-03-01T06:19:35Z; closed 2026-03-01T06:20:52Z; reason COMPLETED. Body: ## 🎯 목표 module-common에서 공개/비공개 패키지 구분 ## ✅ 작업 항목 - [ ] 공개 패키지 목록 작성 - [ ] internal 금지 규칙 검토 - [ ] 공개 API 문서화 - [ ] 사용 가이드 작성 ## 📏 DoD - [ ] 공개 패키지 목록 …
- discussion: 1 / 중복 이슈로 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #547 — [P0-02] PostgreSQL + PGMQ Docker Compose 설정
- author zbnerd; CLOSED; created 2026-03-06T10:42:03Z; closed 2026-03-09T14:08:46Z; reason COMPLETED. Body: ## Overview 로컬 개발환경용 PostgreSQL + PGMQ Extension이 포함된 Docker Compose를 구성한다. ## Tasks - [ ] docker-compose.yml에서 MySQL, MongoDB, Redis 제거 - [ ] Postgre…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [30cd769324f2, 59ac7b738a48, a7d2179af959, e0bf0b963d9a]; PR-record issue refs 2 [#576/MERGED, #578/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #548 — [P0-01] 프로젝트 초기 설정 + Kotlin 변환 기반
- author zbnerd; CLOSED; created 2026-03-06T10:42:03Z; closed 2026-03-09T14:08:47Z; reason COMPLETED. Body: ## Overview v2/postgresql-redesign 브랜치를 생성하고, Java → Kotlin 변환의 기반을 마련한다. ## Tasks - [ ] \`develop\` 브랜치에서 \`v2/postgresql-redesign\` 분기 - [ ] build.g…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [30cd769324f2, 59ac7b738a48, e0bf0b963d9a]; PR-record issue refs 2 [#576/MERGED, #578/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #549 — [P1-02] 도메인 엔티티 정의 (PostgreSQL + jsonb)
- author zbnerd; CLOSED; created 2026-03-06T10:42:40Z; closed 2026-03-10T05:45:06Z; reason COMPLETED. Body: ## Overview 기존 JPA Entity를 PostgreSQL jsonb를 활용한 형태로 재설계하고 Kotlin으로 변환한다. ## Tasks - [ ] 기존 Entity 분석: GameCharacter, Member, CubeProbability, Equipme…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #550 — [P1-03] Repository 레이어 + Port 인터페이스
- author zbnerd; CLOSED; created 2026-03-06T10:42:40Z; closed 2026-03-10T05:45:06Z; reason COMPLETED. Body: ## Overview 헥사고날 아키텍처에 맞게 Port 인터페이스와 Repository 구현체를 분리하고 Kotlin으로 변환한다. ## Tasks - [ ] module-core에 Port 인터페이스 정의: - GameCharacterPort (Load, Save, …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #551 — [P1-01] ADR-001 PostgreSQL 단일 DB 전략
- author zbnerd; CLOSED; created 2026-03-06T10:42:40Z; closed 2026-03-09T14:08:48Z; reason COMPLETED. Body: ## Overview MySQL + MongoDB + Redis를 PostgreSQL 단일 DB로 통합한 결정 근거를 ADR로 문서화한다. ## Tasks - [ ] ADR-001 문서 작성 (docs/adr/001-postgresql-single-db-strategy…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [30cd769324f2, 59ac7b738a48, e0bf0b963d9a]; PR-record issue refs 1 [#578/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #552 — [P2-02] PGMQ 프로듀서 & 컨슈머 구현
- author zbnerd; CLOSED; created 2026-03-06T10:43:05Z; closed 2026-03-10T05:45:07Z; reason COMPLETED. Body: ## Overview PGMQ 기반 메시지 큐의 프로듀서와 컨슈머를 구현한다. 기존 Outbox 스케줄러 3개를 대체한다. ## Tasks - [ ] PGMQ 클라이언트 구현 (JDBC 기반) - [ ] 큐 정의: - calculation_queue (기대값 계산 요청…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#579/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #553 — [P2-01] ADR-002 PGMQ 도입
- author zbnerd; CLOSED; created 2026-03-06T10:43:05Z; closed 2026-03-10T05:45:08Z; reason COMPLETED. Body: ## Overview Redis Streams + Outbox 패턴을 PGMQ로 대체한 결정 근거를 ADR로 문서화한다. ## Tasks - [ ] ADR-002 문서 작성 (docs/adr/002-pgmq-integration.md) - [ ] 기존 Outbox 패턴…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#579/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #554 — [P3-01] ADR-003 Advisory Lock (Redisson Replacement)
- author zbnerd; CLOSED; created 2026-03-06T10:43:50Z; closed 2026-03-10T13:56:03Z; reason COMPLETED. Body: ## Overview Redisson 분산 락을 PostgreSQL Advisory Lock로 대체한 결정 근거를 ADR로 문서화한다. ## Tasks - [ ] ADR-003 문서 작성 (docs/adr/003-advisory-lock.md) - [ ] 기존 Redi…
- discussion: 1 / Merged via PR #584: PostgreSQL scale-out migration complete. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#584/MERGED]; reachable commit-message refs 2 [8d0157e50b0b, e45a208ac0cd]; PR-record issue refs 2 [#583/MERGED, #584/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #555 — [P3-02] Caffeine 캐시 (Redis Cache 대체)
- author zbnerd; CLOSED; created 2026-03-06T10:43:51Z; closed 2026-03-10T11:22:51Z; reason COMPLETED. Body: ## Overview Redis 분산 캐시를 Caffeine 로컬 캐시로 대체한다. TieredCache를 Caffeine 단일 계층으로 변경한다. ## Tasks - [ ] 기존 TieredCache 분석 (L1 Caffeine + L2 Redis) - [ ] Caf…
- discussion: 1 / ✅ **구현 완료** 구현된 파일: - `module-infra/.../cache/CaffeineOnlyCacheManager.kt` PR #5…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#5/MERGED]; reachable commit-message refs 1 [8d0157e50b0b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #556 — [P4-02] Nexon API 수집기 (→ PostgreSQL)
- author zbnerd; CLOSED; created 2026-03-06T10:43:52Z; closed 2026-03-10T11:22:53Z; reason COMPLETED. Body: ## Overview 기존 Nexon API 수집 로직을 PostgreSQL 저장으로 변경하고, Outbox 패턴을 PGMQ로 대체한다. ## Tasks - [ ] 기존 NexonApiOutboxScheduler 분석 - [ ] 새로운 수집 파이프라인 구현: - 스케줄…
- discussion: 1 / ✅ **구현 완료** 구현된 파일: - `module-infra/.../scheduler/NexonApiCollectorScheduler.kt`…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8d0157e50b0b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #557 — [P4-03] 기대값 계산 워커 (PGMQ 기반)
- author zbnerd; CLOSED; created 2026-03-06T10:43:52Z; closed 2026-03-10T11:22:55Z; reason COMPLETED. Body: ## Overview PGMQ 큐에서 계산 요청을 소비하고, 기대값을 계산하여 사전 계산 테이블에 저장하는 워커를 구현한다. ## Tasks - [ ] 계산 워커 구현 (PGMQ read → 계산 → archive) - [ ] 기존 계산 엔진 Kotlin 변환 (Sta…
- discussion: 1 / ✅ **구현 완료** 구현된 파일: - `module-infra/.../pgmq/PgmqWorker.kt` - `module-infra/.../…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #558 — [P4-01] ADR-004 수집/계산/서빙 분리 전략
- author zbnerd; CLOSED; created 2026-03-06T10:43:53Z; closed 2026-03-10T11:22:56Z; reason COMPLETED. Body: ## Overview 데이터 파이프라인을 수집(Collect) / 계산(Compute) / 서빙(Serve)으로 분리한 전략을 ADR로 문서화한다. ## Tasks - [ ] ADR-004 문서 작성 (docs/adr/004-pipeline-separation.md) …
- discussion: 1 / ✅ **구현 완료** 구현된 파일: - `docs/adr/004-collect-compute-serve-pipeline.md` (610행) PR…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8d0157e50b0b]; PR-record issue refs 1 [#583/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #559 — [P6-01] 좋아요 시스템 (PostgreSQL UNLOGGED + PGMQ)
- author zbnerd; CLOSED; created 2026-03-06T10:44:33Z; closed 2026-03-10T13:56:05Z; reason COMPLETED. Body: ## Overview 기존 Redis 기반 좋아요 버퍼를 PostgreSQL UNLOGGED TABLE로 대체하고, PGMQ로 동기화한다. ## Tasks - [ ] UNLOGGED TABLE 스키마 설계 (like_buffer, like_counts) - [ ] 좋아…
- discussion: 1 / Merged via PR #584: PostgreSQL scale-out migration complete. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#584/MERGED]; reachable commit-message refs 1 [e45a208ac0cd]; PR-record issue refs 1 [#584/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #560 — [P6-02] 후원 시스템 (PostgreSQL + PGMQ)
- author zbnerd; CLOSED; created 2026-03-06T10:44:33Z; closed 2026-03-10T13:56:07Z; reason COMPLETED. Body: ## Overview 기존 DonationOutbox를 PGMQ로 대체하고, 후원 처리 파이프라인을 재구현한다. ## Tasks - [ ] 후원 테이블 스키마 설계 - [ ] 후원 서비스 Kotlin 변환 - [ ] PGMQ 기반 후원 처리 워커 - [ ] 기존 Don…
- discussion: 1 / Merged via PR #584: PostgreSQL scale-out migration complete. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#584/MERGED]; reachable commit-message refs 1 [e45a208ac0cd]; PR-record issue refs 1 [#584/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #561 — [P5-02] ADR-005 Single Flight + 핫 키 대응
- author zbnerd; CLOSED; created 2026-03-06T10:44:33Z; closed 2026-03-10T13:56:09Z; reason COMPLETED. Body: ## Overview 인기 캐릭터 바이럴 시 동일 요청 중복 처리를 방지하는 Single Flight 패턴을 Advisory Lock로 구현한 결정 근거를 ADR로 문서화한다. ## Tasks - [ ] ADR-005 문서 작성 (docs/adr/005-single-f…
- discussion: 1 / Merged via PR #584: PostgreSQL scale-out migration complete. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#584/MERGED]; reachable commit-message refs 1 [e45a208ac0cd]; PR-record issue refs 1 [#584/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #562 — [P8-02] 부하 테스트 + 최적화
- author zbnerd; CLOSED; created 2026-03-06T10:45:20Z; closed 2026-03-20T03:04:40Z; reason COMPLETED. Body: ## Overview wrk 또는 Gatling으로 부하 테스트를 수행하고, 성능을 최적화한다. ## Tasks - [ ] 부하 테스트 시나리오: - 평시: 0.5 QPS - 패치일: 500 QPS - 바이럴: 2,000 QPS - [ ] Before/After 메트릭…
- discussion: 1 / Load test completed successfully. **Results:** - RPS: **10,994 QPS** (target: 50…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #563 — [P7-01] 통합 테스트 (Testcontainers + PGMQ)
- author zbnerd; CLOSED; created 2026-03-06T10:45:20Z; closed 2026-03-18T14:13:11Z; reason COMPLETED. Body: ## Overview PostgreSQL + PGMQ 환경에서 통합 테스트를 작성한다. Testcontainers 재사용 모드를 사용한다. ## Tasks - [ ] Testcontainers 설정 (재사용 모드) - [ ] PGMQ 통합 테스트 - [ ] Reposi…
- discussion: 1 / 통합 테스트 인프라 구축 완료. PR #607 참조.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#607/MERGED]; reachable commit-message refs 2 [04bd04fa5277, e91501d6a5d6]; PR-record issue refs 1 [#607/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #564 — [P8-01] ADR-006 스케일아웃 전략
- author zbnerd; CLOSED; created 2026-03-06T10:45:20Z; closed 2026-03-10T13:56:12Z; reason COMPLETED. Body: ## Overview PostgreSQL 단일 인스턴스 한계 도달 시 스케일아웃 전략과 Redis 재도입 트리거 조건을 ADR로 문서화한다. ## Tasks - [ ] ADR-006 문서 작성 (docs/adr/006-scaleout-strategy.md) - [ ] …
- discussion: 1 / Merged via PR #584: PostgreSQL scale-out migration complete. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#584/MERGED]; reachable commit-message refs 1 [e45a208ac0cd]; PR-record issue refs 1 [#584/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #565 — [P9-02] 모니터링 + Runbook 업데이트
- author zbnerd; CLOSED; created 2026-03-06T10:45:20Z; closed 2026-04-05T16:40:51Z; reason COMPLETED. Body: ## Overview 새로운 아키텍처에 맞게 모니터링 대시보드와 Runbook을 업데이트한다. ## Tasks - [ ] Grafana 대시보드 수정: - PostgreSQL 메트릭 추가 - PGMQ 큐 깊이 - Advisory Lock 대기 시간 - Redis 메트릭…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #566 — [P9-01] CI/CD 파이프라인 업데이트
- author zbnerd; CLOSED; created 2026-03-06T10:45:21Z; closed 2026-03-26T08:32:16Z; reason COMPLETED. Body: ## Overview PostgreSQL 기반 아키텍처에 맞게 CI/CD 파이프라인을 수정한다. ## Tasks - [ ] GitHub Actions 워크플로우 수정: - PostgreSQL 서비스 컨테이너 추가 - PGMQ Extension 설치 - [ ] 배포 파이…
- discussion: 1 / ✅ 완료: 모니터링 + Runbook PostgreSQL로 업데이트 **Prometheus Alert Rules:** - Redis latenc…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [2c610860562e]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #567 — [P7-02] 카오스 테스트 (PostgreSQL 장애 시나리오)
- author zbnerd; CLOSED; created 2026-03-06T10:47:57Z; closed 2026-03-17T13:16:53Z; reason COMPLETED. Body: ## Overview PostgreSQL 장애 상황에서 시스템의 회복 탄력성을 테스트한다. Toxiproxy로 장애를 주입한다. ## Tasks - [ ] Toxiproxy 설정 (PostgreSQL 장애 주입) - [ ] 장애 시나리오: - DB 연결 끊김 - DB …
- discussion: 1 / P7-02 PostgreSQL Chaos Tests completed and merged to develop branch. **Completed…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8066cd454795]; PR-record issue refs 1 [#606/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #568 — [P6-03] JWT 인증 (유지, Kotlin 변환)
- author zbnerd; CLOSED; created 2026-03-06T10:47:57Z; closed 2026-03-15T10:37:47Z; reason COMPLETED. Body: ## Overview 기존 JWT 인증 로직을 유지하면서 Kotlin으로 변환한다. 기능 변경 없음. ## Tasks - [ ] JwtTokenProvider Kotlin 변환 - [ ] JwtPayload Kotlin 변환 - [ ] FingerprintGenerat…
- discussion: 1 / ✅ Completed via PR #605 **변환 완료 파일:** - → - → **이미 Kotlin으로 변환되어 있던 파일:** - JwtT…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#605/MERGED]; reachable commit-message refs 3 [3adc0aa64627, 8066cd454795, e91501d6a5d6]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #588 — 단일 DB 환경을 위한 적응형 마이크로 배칭(Adaptive Micro-Batching) 조회 로직 구현
- author zbnerd; CLOSED; created 2026-03-11T03:19:09Z; closed 2026-03-19T09:51:47Z; reason COMPLETED. Body: ## 1. 배경 및 목적 (Context) - 현재 시스템은 Redis 같은 분산 캐시를 제거하고 PostgreSQL 단일 DB 아키텍처로 운영 포인트를 통합한 상태 - 1000 RPS 이상의 트래픽이 몰리며 로컬 캐시(Caffeine)가 전부 Miss 나는 상황(Ca…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [e0d3c5039c33, e91501d6a5d6, f4fcea5ba8b7]; PR-record issue refs 1 [#608/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #589 — [V5 Migration] Redis/Redisson 의존성 완전 제거
- author zbnerd; CLOSED; created 2026-03-11T13:43:57Z; closed 2026-03-11T21:52:40Z; reason COMPLETED. Body: ## 🎯 목표 Redis, Redisson 의존성을 완전히 제거하고 PostgreSQL로 마이그레이션 ## 📋 작업 항목 ### 1. Session 저장소 - [ ] RedisSessionRepository 제거 - [ ] InMemorySessionRepository…
- discussion: 1 / Redis/Redisson 의존성 제거는 이미 PR #594에서 완료되었습니다. ## 완료된 작업 - ✅ Redis/Redisson 관련 코드 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 15 [0cf11b1a5d41, 2f694ba7d29e, 33ec3b66bbb5, 5f91c3212d09, 69d675cedb99, 8066cd454795, 8722ef8e9391, 8a092c375a02, 922851c7a643, 95fc61236ef3, 9f594f161464, a31011c12f73, c42d00c5c403, e692e289ec79, e91501d6a5d6]; PR-record issue refs 2 [#594/MERGED, #596/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #590 — [V5 Migration] MongoDB 의존성 완전 제거
- author zbnerd; CLOSED; created 2026-03-11T13:44:25Z; closed 2026-03-11T21:47:44Z; reason COMPLETED. Body: ## 🎯 목표 MongoDB 의존성을 완전히 제거하고 PostgreSQL로 마이그레이션 ## 📋 작업 항목 ### 1. CharacterView 저장소 - [ ] CharacterValuationRepository (MongoDB) 제거 - [ ] CharacterVa…
- discussion: 1 / MongoDB 의존성 완전 제거 완료 PR #595에서 MongoDB 패키지 삭제 및 PostgreSQL로 마이그레이션 완료했습니다. ## 완료…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 10 [2f694ba7d29e, 33ec3b66bbb5, 48c5e18207c1, 5f91c3212d09, 69d675cedb99, 8066cd454795, 922851c7a643, 95fc61236ef3, 9f594f161464, e91501d6a5d6]; PR-record issue refs 2 [#595/MERGED, #596/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #591 — [V5 Migration] MySQL 의존성 완전 제거
- author zbnerd; CLOSED; created 2026-03-11T13:44:47Z; closed 2026-03-11T22:28:46Z; reason COMPLETED. Body: ## 🎯 목표 MySQL 의존성을 완전히 제거하고 PostgreSQL로 통합 ## 📋 작업 항목 ### 1. Lock 관련 - [ ] MySqlNamedLockStrategy 제거 - [ ] LockHikariConfig에서 MySQL 설정 제거 - [ ] Postgr…
- discussion: 1 / PR #596에서 머지 완료. MySQL 의존성 완전 제거 및 PostgreSQL로 마이그레이션 완료.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 12 [2f694ba7d29e, 33ec3b66bbb5, 5f91c3212d09, 69d675cedb99, 6ca308e1e200, 8066cd454795, 893d1f99d1da, 922851c7a643, 95fc61236ef3, 9f594f161464, cc7f26b4221c, e91501d6a5d6]; PR-record issue refs 2 [#596/MERGED, #597/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #592 — [V5 Migration] 임시 조건부 어노테이션 정리
- author zbnerd; CLOSED; created 2026-03-11T13:45:20Z; closed 2026-03-26T08:23:33Z; reason COMPLETED. Body: ## 🎯 목표 마이그레이션 중 추가된 임시 @ConditionalOn* 어노테이션 정리 ## 📋 정리 항목 ### 제거할 임시 어노테이션 현재 Redis 비활성화 시 앱 시작을 위해 추가된 조건부 어노테이션들: - [ ] \`RedissonConfig\` @Condit…
- discussion: 1 / ## Resolution Updated CI pipeline for PostgreSQL-based architecture. ### Changes…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [69d675cedb99, 8066cd454795, 922851c7a643, e91501d6a5d6]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #599 — DB Query Micro-Batching 구현으로 PostgreSQL I/O 경합 감소
- author zbnerd; CLOSED; created 2026-03-12T06:25:27Z; closed 2026-03-19T09:51:39Z; reason COMPLETED. Body: ## 배경 현재 요청 처리 모델은 각 요청마다 독립적으로 DB 조회를 수행한다. 버스트 트래픽 상황에서 (예: 수천 명이 동시에 기대값 조회) 다음 문제 발생: - 과도한 PostgreSQL 왕복 (roundtrip) - HikariCP 커넥션 풀 경합 심화 - 불필요…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [e0d3c5039c33, e91501d6a5d6, f4fcea5ba8b7]; PR-record issue refs 1 [#608/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #610 — 현실 워크로드 검증 테스트 계획서 (Phase 1~6)
- author zbnerd; CLOSED; created 2026-03-20T06:27:46Z; closed 2026-03-26T08:22:23Z; reason COMPLETED. Body: ## 목적 본 테스트의 목적은 특정 시점의 최대 QPS를 과시하는 것이 아니라, **현실 워크로드 조건에서 시스템의 성능, 정합성, 재현 가능성을 검증**하고 그 결과를 일관된 형식으로 기록하는 데 있다. ### 검증 항목 - [ ] 현실 데이터 분포에서의 처리량과 지…
- discussion: 1 / ## Resolution Completed cleanup of temporary @ConditionalOn* annotations from V5…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#612/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #611 — feat: 300k character bulk loading with adaptive micro-batch processing
- author zbnerd; CLOSED; created 2026-03-20T08:43:15Z; closed 2026-03-26T07:43:11Z; reason COMPLETED. Body: ## Summary Implement a robust bulk loading system to pre-load 300k characters into PostgreSQL for realistic load testing, with adaptive throttling to …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [57616f5cff96, e91501d6a5d6]; PR-record issue refs 1 [#612/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #617 — perf: retrospective analysis - verified facts vs inferences & corrected priorities
- author zbnerd; CLOSED; created 2026-03-24T12:35:52Z; closed 2026-03-26T07:43:15Z; reason COMPLETED. Body: ## Performance Baseline Retrospective Analysis **Date**: 2026-03-24 **Related Documents**: - 📄 [ADR-086: Performance Baseline Analysis & Bottleneck De…
- discussion: 2 / ## Global Admission Control과 Micro-Batching의 관계 (Issue #617) **Date**: 2026-03-2…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [0264e5f77d88, 5f7fa30ec80c]; PR-record issue refs 1 [#618/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #623 — 아키텍처 이슈: 동기식 Fan-Out I/O + 읽기/쓰기 결합 안티패턴
- author zbnerd; CLOSED; created 2026-03-28T15:06:01Z; closed 2026-06-02T15:10:39Z; reason COMPLETED. Body: ## Issue (재정의 — Consensus Review 기반) > **원래 정의가 부정확했음.** 3에이전트(Architect, Critic, Code-Reviewer) 컨센서스 리뷰 결과, "Synchronous Fan-Out" 패턴은 이미 비동기로 구현되어 있었…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [088c2d3c8e28, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #624 — [P0][Security] SecurityConfig .anyRequest().permitAll() → denyAll() 변경
- author zbnerd; CLOSED; created 2026-03-28T15:50:28Z; closed 2026-03-29T19:51:35Z; reason COMPLETED. Body: ## 문제 `module-infra/.../security/config/SecurityConfig.kt:44-45`에서 `.anyRequest().permitAll()` 사용. 새 엔드포인트가 `/api/**` 패턴 밖에 추가되면 인증 없이 노출됨. Deny-by-de…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [f6aaff5f506d]; PR-record issue refs 1 [#670/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #625 — [P0][Security] 프로덕션 코드 System.out.println 사용 (3개 파일)
- author zbnerd; CLOSED; created 2026-03-28T15:50:30Z; closed 2026-03-29T19:51:36Z; reason COMPLETED. Body: ## 문제 CLAUDE.md 규칙 위반: System.out.println() 금지, @Slf4j 사용. ## 위치 1. `PrometheusSecurityFilter.kt:48,81,93` - println() 3건 2. `CorsValidationFilter.kt:…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [8a092c375a02, 8cff721f309b, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#669/CLOSED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #626 — [P0][Concurrency] Like Buffer Race Condition - fetchAndClear 비원자적 스냅샷
- author zbnerd; CLOSED; created 2026-03-28T15:50:31Z; closed 2026-03-29T19:51:36Z; reason COMPLETED. Body: ## 문제 `InMemoryLikeBufferStorage.fetchAndClear()`에서 `likeCache.asMap()` 순회 중 동시 수정으로 카운트 누락 가능. 1. Thread A: increment("user1", 5) 2. Thread B: fetchA…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [8a092c375a02, 8cff721f309b, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #627 — [P0][Concurrency] Carrier Thread Pinning - Virtual Thread 환경에서 Caffeine 동기화
- author zbnerd; CLOSED; created 2026-03-28T15:50:33Z; closed 2026-03-29T19:51:36Z; reason COMPLETED. Body: ## 문제 Caffeine Cache의 `asMap()` 연산이 내부적으로 synchronized 블록 사용 가능. Virtual Thread 환경에서 carrier thread pinning 유발. CLAUDE.md Rule 9: `synchronized` 블록 안에…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [8a092c375a02, 8cff721f309b, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #628 — [P0][Data] Advisory Lock 세션 스코프 → 트랜잭션 스코프 전환
- author zbnerd; CLOSED; created 2026-03-28T15:50:34Z; closed 2026-03-29T19:51:36Z; reason COMPLETED. Body: ## 문제 `PostgresAdvisoryLockStrategy`에서 `pg_try_advisory_lock()` (세션 스코프) 사용. HikariCP 연결 풀 환경에서: 1. Lock이 connection에 종속 2. Connection 반환 후에도 lock 유지 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 7 [1095c31583ca, 8a092c375a02, a13587d5cdaa, b5bef2dd074d, bd9641dfbbee, e692e289ec79, f952db781234]; PR-record issue refs 1 [#671/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #629 — [P0][Null-Safety] OrderedLockExecutor unsafe !! 연산자
- author zbnerd; CLOSED; created 2026-03-28T15:50:35Z; closed 2026-03-29T19:51:37Z; reason COMPLETED. Body: ## 문제 `OrderedLockExecutor.kt:228`에서 CAS 실패 후 null 반환 시 `!!`로 NPE 발생. ```kotlin return nestedStrategyRequired.get()!! // CAS 실패 시 null → NPE ``` ## 위치…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [8a092c375a02, a13587d5cdaa, bd9641dfbbee, e692e289ec79, f952db781234]; PR-record issue refs 1 [#671/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #630 — [P0][Null-Safety] EquipmentExpectationResponseV4 unsafe as 캐스팅
- author zbnerd; CLOSED; created 2026-03-28T15:50:36Z; closed 2026-03-29T19:51:37Z; reason COMPLETED. Body: ## 문제 리플렉션 기반 `as Number` 캐스팅이 실제 타입이 Number가 아닐 경우 ClassCastException 발생. ```kotlin val blackCubeCost = breakdown.javaClass.getDeclaredMethod("blackC…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [8a092c375a02, a13587d5cdaa, bd9641dfbbee, e692e289ec79, f952db781234]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #631 — [P0][Null-Safety] ExpectationV4PortAdapter .orElse(null) 패턴
- author zbnerd; CLOSED; created 2026-03-28T15:50:38Z; closed 2026-03-29T19:51:37Z; reason COMPLETED. Body: ## 문제 Optional의 목적을 무력화하는 `.orElse(null)` 사용. ```java var result = expectationService.getGzipFromL1CacheDirect(userIgn); return result.orElse(null); `…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [8a092c375a02, a13587d5cdaa, bd9641dfbbee, e692e289ec79, f952db781234]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #632 — [P1][Scale-out] Caffeine L1 캐시 일관성 - 다중 인스턴스 분기
- author zbnerd; CLOSED; created 2026-03-28T15:50:39Z; closed 2026-03-29T19:51:37Z; reason COMPLETED. Body: ## 문제 L1(Caffeine)은 인스턴스 로컬. 다중 인스턴스에서 서로 다른 캐시 값을 가질 수 있음. LISTEN/NOTIFY로 무효화 중이나 Race Condition 존재. Instance A가 L2에 쓰는 동안 Instance B가 L1에서 이전 값을 읽을 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [542f69b4965c, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #633 — [P1][Scale-out] EquipmentPersistenceTracker 인메모리 상태 → PostgreSQL 전환
- author zbnerd; CLOSED; created 2026-03-28T15:50:41Z; closed 2026-03-29T19:51:38Z; reason COMPLETED. Body: ## 문제 `ConcurrentHashMap<String, CompletableFuture<Void>> pendingOperations`이 인스턴스 로컬. 장애 시 진행 중인 작업 추적 불가. ## 위치 - `module-app/.../shutdown/Equipment…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [542f69b4965c, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #634 — [P1][Scale-out] ExpectationCalculationQueue 인메모리 큐 → PGMQ 전환
- author zbnerd; CLOSED; created 2026-03-28T15:50:42Z; closed 2026-03-29T19:51:38Z; reason COMPLETED. Body: ## 문제 `LinkedBlockingQueue` 기반 인메모리 큐. 인스턴스 장애 시 큐 내 작업 유실. ## 위치 - `module-app/.../queue/ExpectationCalculationQueue.java:49-50` ## 수정 LinkedBlocking…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [542f69b4965c, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#710/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #635 — [P1][Data] Circuit Breaker 오픈 시 Like 카운트 유실
- author zbnerd; CLOSED; created 2026-03-28T15:50:43Z; closed 2026-03-29T19:51:38Z; reason COMPLETED. Body: ## 문제 `LikeSyncExecutor.batchFallback()`에서 예외 throw 시 fetchAndClear로 이미 비워진 버퍼 복구 불가. 사용자 좋아요 클릭이 영구 유실. 1. Buffer에서 fetchAndClear (이미 비워짐) 2. DB writ…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [542f69b4965c, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #636 — [P1][Data] TieredCache L2 실패 시 L1 스킵 → 캐시 불일치
- author zbnerd; CLOSED; created 2026-03-28T15:50:44Z; closed 2026-03-30T13:41:48Z; reason COMPLETED. Body: ## 문제 `executeAndCache`에서 L2 put 실패 시 L1도 스킵. 계산된 값이 어디에도 캐시되지 않는 상태 발생. ```kotlin if (l2Success) { l1.put(key, value) } else { log.warn("[TieredCache…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #637 — [P1][Null-Safety] Optional 안티패턴 .isPresent() + .get() (3건)
- author zbnerd; CLOSED; created 2026-03-28T15:50:46Z; closed 2026-03-30T13:40:13Z; reason COMPLETED. Body: ## 문제 Optional의 목적을 무력화하는 `.isPresent()` + `.get()` 패턴. ## 위치 1. `GameCharacterFacade.java:69-71` 2. `EquipmentApplicationService.java:118-120` 3. `Eq…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, c877b82f182b, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #638 — [P1][Null-Safety] Java-Kotlin interop NPE - OcidResolver double-null check
- author zbnerd; CLOSED; created 2026-03-28T15:50:47Z; closed 2026-03-30T13:40:14Z; reason COMPLETED. Body: ## 문제 `OcidResolver.java:77-78`에서 Kotlin Value Object(`CharacterId`)에 대한 double-null check. Kotlin side가 non-nullable이지만 Java에서 nullable로 접근. Platform…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #639 — [P1][Architecture] module-web → module-infra 직접 의존 (DIP 위반)
- author zbnerd; CLOSED; created 2026-03-28T15:50:48Z; closed 2026-03-30T13:40:16Z; reason COMPLETED. Body: ## 문제 `module-web/build.gradle:17`에서 `module-infra` 직접 의존. Hexagonal Architecture (ADR-005) 위반. Web layer가 Infrastructure 구현체에 직접 접근 가능. ## 위치 - `modu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 2 [#673/MERGED, #710/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #640 — [P1][Architecture] CacheCoordinator Port 추출 - module-app이 infra 구현체 직접 참조
- author zbnerd; CLOSED; created 2026-03-28T15:50:49Z; closed 2026-03-30T13:40:17Z; reason COMPLETED. Body: ## 문제 `ExpectationCacheCoordinator.java`가 `TieredCacheManager` (infra 구현체)를 직접 주입. DIP 위반. ## 위치 - `module-app/.../expectation/cache/ExpectationCacheC…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #641 — [P1][Anti-Pattern] Thread.sleep() polling - GameCharacterFacade
- author zbnerd; CLOSED; created 2026-03-28T15:50:51Z; closed 2026-03-30T13:40:18Z; reason COMPLETED. Body: ## 문제 `GameCharacterFacade.java:75`에서 `TimeUnit.MILLISECONDS.sleep()` 사용. CLAUDE.md Rule 12 위반. ## 위치 - `module-app/.../character/GameCharacterFacade.…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #642 — [P1][Anti-Pattern] Thread.sleep() polling - PostgresNotifySubscriber
- author zbnerd; CLOSED; created 2026-03-28T15:50:52Z; closed 2026-03-30T13:40:20Z; reason COMPLETED. Body: ## 문제 `PostgresNotifySubscriber.kt:154`에서 `Thread.sleep(POLL_INTERVAL_MS)` 루프. Virtual Thread 환경에서 busy-wait anti-pattern. ## 위치 - `module-infra/.../c…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #643 — [P1][Null-Safety] JWT Token claims.expiration NPE 가능성
- author zbnerd; CLOSED; created 2026-03-28T15:50:53Z; closed 2026-03-30T13:40:21Z; reason COMPLETED. Body: ## 문제 `JwtTokenProvider.kt:220-229`에서 `.expiration.toInstant()` 호출 전 null 체크 없음. Malformed token에 exp claim이 없으면 NPE. ## 위치 - `module-infra/.../securi…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #644 — [P1][Architecture] God Object - ExpectationCacheCoordinator 분해
- author zbnerd; CLOSED; created 2026-03-28T15:50:55Z; closed 2026-03-30T13:40:22Z; reason COMPLETED. Body: ## 문제 `ExpectationCacheCoordinator` (441 lines)가 다중 책임 (caching, compression, admission control coordination). SRP 위반. ## 위치 - `module-app/.../expecta…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [7261bd8ecaa2, 8a092c375a02, bd9641dfbbee, c877b82f182b, e692e289ec79]; PR-record issue refs 1 [#673/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #645 — [P2][Performance] L2 Cache LIKE 풀테이블 스캔 → 인덱스 활용 개선
- author zbnerd; CLOSED; created 2026-03-28T15:50:56Z; closed 2026-04-09T01:26:01Z; reason COMPLETED. Body: ## 문제 `PostgresL2CacheStrategy.kt:230-237`에서 `LIKE 'prefix%'` 사용. 인덱스 미활용으로 풀테이블 스캔 발생. ## 위치 - `module-infra/.../cache/tiered/PostgresL2CacheStrategy…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 2 [#706/MERGED, #709/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #646 — [P2][Reliability] DLQ 자동 Replay 메커니즘 부재
- author zbnerd; CLOSED; created 2026-03-28T15:50:57Z; closed 2026-04-09T01:26:08Z; reason COMPLETED. Body: ## 문제 DLQ로 이동된 메시지에 자동 재처리 메커니즘 없음. 수동 개입 필요. ## 수정 스케줄드 DLQ replay worker 구현. Exponential backoff 적용. **검출 에이전트:** Critic
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 1 [#706/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #647 — [P2][Reliability] Cache Stampede Protection 부재
- author zbnerd; CLOSED; created 2026-03-28T15:50:58Z; closed 2026-04-09T01:26:19Z; reason COMPLETED. Body: ## 문제 다중 스레드가 동시에 cache miss 시 동일 계산 중복 실행. Lock-free stampede prevention (BLANK null marker 등) 미구현. ## 위치 - `module-infra/.../cache/TieredCache.kt` #…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 1 [#706/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #648 — [P2][Reliability] Graceful Shutdown 조율 - 스케줄드 태스크 중단 보장
- author zbnerd; CLOSED; created 2026-03-28T15:51:00Z; closed 2026-04-09T01:26:27Z; reason COMPLETED. Body: ## 문제 다수 스케줄드 태스크 실행 중 graceful shutdown 시 mid-task interruption 가능. 조율 메커니즘 부재. ## 수정 `SmartLifecycle` 구현으로 순차적 shutdown 보장. **검출 에이전트:** Critic
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #649 — [P2][Reliability] LikeSyncExecutor mid-batch 실패 시 보상 트랜잭션 부재
- author zbnerd; CLOSED; created 2026-03-28T15:51:01Z; closed 2026-04-08T10:47:58Z; reason COMPLETED. Body: ## 문제 배치 DB write 중간 실패 시 부분 업데이트 복구 불가. Compensating transaction 미구현. ## 위치 - `module-infra/.../queue/like/LikeSyncExecutor.kt` ## 수정 배치 단위 트랜잭션 또는 실…
- discussion: 1 / Close: LikeSyncExecutor는 #664에서 DB Trigger(fn_like_count_trigger)로 대체되어 @Depreca…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #650 — [P2][Reliability] DB Connection Pool 모니터링 경고 부재
- author zbnerd; CLOSED; created 2026-03-28T15:51:02Z; closed 2026-04-09T01:26:35Z; reason COMPLETED. Body: ## 문제 Metrics는 존재하나 pool exhaustion 시나리오에 대한 알림 설정 부재. ## 수정 HikariCP metrics 기반 Prometheus alert rule 추가. **검출 에이전트:** Critic
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, a7725146a664]; PR-record issue refs 1 [#706/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #651 — [P2][Reliability] Leader Election 관측 가능성 - 구조화된 메트릭 부재
- author zbnerd; CLOSED; created 2026-03-28T15:51:04Z; closed 2026-04-18T04:22:16Z; reason COMPLETED. Body: ## 문제 `PostgresAdvisoryLockStrategy`가 leader/follower 로그만 출력. Lock contention 메트릭 없음. ## 위치 - `module-infra/.../lock/PostgresAdvisoryLockStrategy.kt` …
- discussion: 1 / PR #708에서 해결 — LockMetrics Map 기반 리팩토링 + PostgresAdvisoryLockStrategy 메트릭 연동 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, e8e68d12b628]; PR-record issue refs 2 [#708/MERGED, #709/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #652 — [P2][Security] 인증 엔드포인트 Rate Limiting 미적용
- author zbnerd; CLOSED; created 2026-03-28T15:51:05Z; closed 2026-04-18T04:22:18Z; reason COMPLETED. Body: ## 문제 `/api/**` 엔드포인트는 인증 필요하지만 rate limiting 미적용. 비싼 연산(expectation calculation)에 DoS 가능. ## 위치 - `module-infra/.../security/config/SecurityConfig.kt…
- discussion: 1 / PR #708에서 해결 — RateLimitingFilter @Component + SecurityConfig 등록 + V109 DDL 마이그레…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, e8e68d12b628]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #653 — [P2][Architecture] @Volatile → AtomicReference 전환 - PostgresNotifySubscriber
- author zbnerd; CLOSED; created 2026-03-28T15:51:07Z; closed 2026-04-18T04:22:20Z; reason COMPLETED. Body: ## 문제 `@Volatile`만으로는 원자적 갱신 보장 안됨. Compound operation (read-modify-write) 시 경쟁 조건. ## 위치 - `module-infra/.../cache/invalidation/impl/PostgresNotifySu…
- discussion: 1 / PR #708에서 해결 — PostgresNotifySubscriber + CharacterCreationListener AtomicRefere…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, e8e68d12b628]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #654 — [P2][Configuration] 하드코딩 매직 넘버 설정 외부화
- author zbnerd; CLOSED; created 2026-03-28T15:51:08Z; closed 2026-04-18T04:22:21Z; reason COMPLETED. Body: ## 문제 다수의 하드코딩 상수가 설정 파일로 관리되지 않음: - `POLL_INTERVAL_MS = 100L` (PostgresNotifySubscriber) - `RECONNECT_DELAY_MS = 5000L` (PostgresNotifySubscriber) - …
- discussion: 1 / PR #708에서 해결 — PostgresNotifySubscriber/ExpectationCalculationQueue @Value 생성자 주…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, e8e68d12b628]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #655 — [P2][Architecture] ADR-022 Redis 제거 잔여물 정리
- author zbnerd; CLOSED; created 2026-03-28T15:51:10Z; closed 2026-04-18T04:22:23Z; reason COMPLETED. Body: ## 문제 ADR-022 Redis 제거 부분 완료. 잔여물 존재: - `RedisSessionRepository`, `RedisRefreshTokenRepository` ## 위치 - `docs/adr/022-redis-dependency-removal.md:44-9…
- discussion: 1 / PR #708에서 해결 — LuaScripts.kt, LikeAtomicOperations.kt, BufferLuaScripts.kt 삭제 + …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2e46b7fc0b3a, 978889c72a39, e8e68d12b628]; PR-record issue refs 1 [#708/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #255 — docs: Scenario Planning (시나리오 플래닝) 문서 작성
- author zbnerd; CLOSED; created 2026-01-23T18:41:11Z; closed 2026-01-25T14:33:10Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **B3 (확장성): 2/6**, **B4 (실현 가능성): 2/6** 로 낮은 점수를 받았습니다. 평가 코멘트: > "프로젝트는 '고밀도 트래픽과 대용량 JSON 처리'라는 특정 미래를 강하게 가정하…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [837cd48cce62, ba471ede2742]; PR-record issue refs 1 [#267/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #256 — docs: Adoption Guide (도입 가이드) 문서 작성
- author zbnerd; CLOSED; created 2026-01-23T18:41:17Z; closed 2026-01-25T15:00:50Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **A2 (타겟 사용자 정의): 2/5** 로 낮은 점수를 받았습니다. 평가 코멘트: > "사용자 니즈가 기술적 문제 해결로만 표현되어 실제 사용자의 업무 흐름(도입 난이도, 마이그레이션, 운영/관측,…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8f9ec3752865]; PR-record issue refs 1 [#268/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #257 — docs: Architecture Decision Records (ADR) 작성
- author zbnerd; CLOSED; created 2026-01-23T18:43:20Z; closed 2026-01-25T15:00:51Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **D2 (코드 주석): 2/4** 로 낮은 점수를 받았습니다. 평가 코멘트: > "'Yes, And'의 핵심인 상호작용(누가 무엇을 제안했고, 다른 사람이 어떻게 받아 더했는지)을 보여주는 기록(참여…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [8f9ec3752865]; PR-record issue refs 1 [#268/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #258 — docs: Demo Guide (데모 가이드) 작성
- author zbnerd; CLOSED; created 2026-01-23T18:43:26Z; closed 2026-01-26T04:07:45Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **D3 (발표/데모 품질): 3/6** 로 중간 점수를 받았습니다. 평가 코멘트: > "발표 자료(스토리/근거/시각화)는 상위권이지만, 데모 운영과 Q&A 준비는 확인 불가 혹은 미흡으로 보수적으로 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [174f1dd67f1d]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #259 — docs: Test Coverage & CI/CD 문서화 개선
- author zbnerd; CLOSED; created 2026-01-23T18:44:54Z; closed 2026-01-26T04:07:45Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **C (구현 완성도): 0/30** 로 최하점을 받았습니다. 평가 코멘트: > "평가 시점에 해당 섹션이 제출되지 않았거나, 코드/테스트가 충분히 보이지 않았기 때문" 현재 프로젝트는 **`.gith…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [174f1dd67f1d]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #260 — docs: Observability 문서화 강화 (Dashboard, Alerts, Queries)
- author zbnerd; CLOSED; created 2026-01-23T18:44:59Z; closed 2026-01-26T04:07:45Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **C (구현 완성도): 0/30**, **D1 (README 품질): 1/6** 로 낮은 점수를 받았습니다. 현재 프로젝트는 **Prometheus + Grafana + Loki** 관측 스택이 구축…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [174f1dd67f1d]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #262 — [Performance] V4 API Singleflight 패턴 미적용으로 인한 캐시 스탬피드
- author zbnerd; CLOSED; created 2026-01-23T19:59:53Z; closed 2026-01-24T00:48:51Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) V4 기대값 API (`/api/v4/characters/{userIgn}/expectation`)가 동시 요청 시 **Singleflight 패턴 없이** 모든 요청이 중복 계산을 수행하여 심각한 성능 저하 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [418cc04dfe9d, 96893bd9bd13]; PR-record issue refs 1 [#263/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #264 — [Performance] V4 API 캐시 히트 시 RPS 병목 (120 RPS → 300+ 목표)
- author zbnerd; CLOSED; created 2026-01-24T09:27:53Z; closed 2026-01-24T10:24:30Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) V4 기대값 API 캐시 히트 시나리오에서 RPS가 120대로 제한됨. 좋은 로컬 환경에서도 성능이 제한되는 현상 발생. **Sequential Thinking 병목 분석 결과:** 병목 지점 원인 영향도 --…
- discussion: 1 / ## ✅ Issue #264 완료 ### 성과 요약 Metric Before After 개선율 -------- -------- ------- -…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [376bee7e1e58, 584a23bab304, 67d92aa79f30, 6fbadecc2f31, d8d89b7f917c]; PR-record issue refs 1 [#265/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #271 — [Arch] V5: Stateless 아키텍처 전환 - Redis External Buffer + 무한 Scale-out
- author zbnerd; CLOSED; created 2026-01-26T05:22:14Z; closed 2026-01-27T04:05:34Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 V4 아키텍처는 **단일 노드에서 965 RPS**라는 극한의 효율을 달성했으나, 다음과 같은 **Stateful 구조의 고유 한계**가 존재합니다: ### 현재 아키텍처의 한계 ``` ┌──────────…
- discussion: 3 / ## 🎉 P0 Core Implementation Complete (2026-01-27) ### Completed Items Component …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [1d730ed42872, 2026c5796c80, 371377642885, 6447313fcfe8, d350f06c761d]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #278 — feat: Scale-out 환경 실시간 좋아요 동기화 (Redis Pub/Sub 또는 Streams)
- author zbnerd; CLOSED; created 2026-01-27T23:11:02Z; closed 2026-01-30T16:56:37Z; reason COMPLETED. Body: ## 배경 현재 좋아요 시스템은 Redis HINCRBY를 사용하여 버퍼링하고 있으나, Scale-out 환경에서 실시간 동기화가 필요합니다. ## 현재 구조의 한계 - 버퍼 데이터는 Redis에 공유됨 ✅ - 하지만 응답 시점의 likeCount 계산이 각 인스턴스에…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 8 [195cc551cb6b, 371377642885, 45307a46b980, 685a1d5b3ce1, 69f87c60da87, 8c824195cd31, 8e4d86e1d575, e91501d6a5d6]; PR-record issue refs 5 [#280/MERGED, #286/MERGED, #298/MERGED, #299/MERGED, #609/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #279 — JWT/세션 만료 시 401 복구 경로 개선
- author zbnerd; CLOSED; created 2026-01-27T23:18:09Z; closed 2026-01-28T13:27:06Z; reason COMPLETED. Body: ## 문제 상황 JWT 토큰 또는 Redis 세션이 만료된 후 401 Unauthorized 에러가 발생하며, 사용자가 올바른 자격 증명으로 로그인해도 실패하는 경우가 발생. ## 원인 분석 ### 1. JWT와 Redis 세션 TTL 불일치 - **JWT 만료 시간*…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [195cc551cb6b, 371377642885, c8a14fa320f4]; PR-record issue refs 1 [#281/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #282 — [Architecture] 멀티 모듈 전환: 횡단 관심사(Cross-Cutting Concerns) 분리
- author zbnerd; CLOSED; created 2026-01-28T13:35:51Z; closed 2026-02-18T12:23:00Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 단일 모듈 모놀리식 구조에서 `global` 패키지에 **130개 이상의 횡단 관심사(Cross-Cutting Concerns) 파일**이 집중되어 있다. [#126 Pragmatic CQRS](https…
- discussion: 1 / ## ✅ DoD 달성 완료 (테스트 제외) ### 완료된 항목: - ✅ 4개 모듈 구조 빌드 성공 (module-app, module-commo…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [11e6d53fff1b, 4eea703f448f, 65f6c1684de8, 9b0b5b1014a1, fb337489ed4d]; PR-record issue refs 1 [#340/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #283 — [Architecture] Scale-out 방해 요소 제거: P0/P1 Stateful 컴포넌트 분산화
- author zbnerd; CLOSED; created 2026-01-28T13:50:05Z; closed 2026-02-01T16:45:17Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 코드베이스를 전수 분석한 결과, Scale-out(수평 확장)을 방해하는 **P0(Critical) 8개, P1(High) 9개** 항목이 발견되었다. 대부분의 문제가 **3가지 패턴**으로 귀결된다: 패…
- discussion: 2 / ## 2차 심층 분석 결과: 추가 P1 5개 발견 전체 코드베이스 2차 심층 분석을 통해 **P1 5개**를 추가 발견했습니다. 총계: **P0…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 11 [284861b4839f, 30cd769324f2, 36c569ef8ede, 57f9f75e40ed, a95ea156b7a0, b428af79abb8, b680e0d784fd, b93d3a18f2ec, bd87d1ff0590, cce180fe07e9, f3a644d3bbf6]; PR-record issue refs 4 [#301/MERGED, #302/MERGED, #348/MERGED, #573/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #284 — [Performance] 대규모 트래픽(1000+ RPS) P0/P1 병목 해결
- author zbnerd; CLOSED; created 2026-01-28T14:37:12Z; closed 2026-01-30T16:56:16Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 시스템은 **235 RPS**에서 안정적으로 동작하지만, **1000+ RPS** 목표 달성을 위해 다음 병목 지점이 해결되어야 한다. 전체 코드베이스를 대규모 트래픽/대용량 데이터 관점에서 분석한 결과,…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [807b487c3a27, 8458164f2b1a, 8e4d86e1d575, bd87d1ff0590]; PR-record issue refs 3 [#297/MERGED, #298/MERGED, #299/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #300 — [Architecture] Anti-Corruption Layer: 3단계 프로토콜 전략 — REST → Queue → JDBC Batch 파이프라인
- author zbnerd; CLOSED; created 2026-02-01T11:34:31Z; closed 2026-02-10T02:29:58Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 Nexon Open API에서 데이터를 수집할 때, **외부의 제약(REST)**이 내부 처리 파이프라인까지 침투하여 성능 병목이 발생한다. 넥슨 서버가 REST만 지원하므로 수집 단계에서는 HTTP를 사…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 7 [0aafc285c5ff, 34be9a3ba859, 36453d53ca54, a72e886d581c, af9a543de0cb, b7c8001c854d, b9271ee8d326]; PR-record issue refs 1 [#323/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #303 — refactor: 통합 테스트 구조 개선 — Spring Context 분산 + 실행 시간 최적화
- author zbnerd; CLOSED; created 2026-02-01T16:15:42Z; closed 2026-02-10T02:30:09Z; reason COMPLETED. Body: ## 배경 Issue #283 Sprint 2+3 작업 중 전체 테스트 실행 시 **30분+ 소요** 확인. fastTest (unit + 경량 테스트 661개)는 **30초**에 완료되나, 전체 746개 실행 시 Docker 컨테이너 기반 통합 테스트가 병목. ## …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [34c722b082ee, 39337e011ebc, 47d7b96344de, 62d099499ed9, 641680198fb8, a573771eb32d]; PR-record issue refs 2 [#304/MERGED, #305/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #310 — [Architecture] Redis Lock 전환으로 MySQL Lock Pool 병목 완화
- author zbnerd; CLOSED; created 2026-02-06T15:28:29Z; closed 2026-02-06T21:41:55Z; reason COMPLETED. Body: ## [Architecture] Redis Lock 전환으로 MySQL Lock Pool 병목 완화 ### Problem MySQL Named Lock 기반(`GlobalLockManager`)에서 Lock 전용 Hikari pool이 포화되며 병목이 발생. - 현상:…
- discussion: 1 / ## ✅ Resolution Issue #310은 PR #318이 머지되어 완료되었습니다. ### 구현 완료 항목 - Phase 0: Instr…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [239e25321649, 624ea8d58d57, 6a527afc7914, 9feab6ec1c95, ccc477def1a9]; PR-record issue refs 1 [#318/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #311 — [Feature] Discord Auto-Mitigation - 자율 운영 시스템 구현
- author zbnerd; CLOSED; created 2026-02-06T15:33:30Z; closed 2026-03-05T05:50:55Z; reason COMPLETED. Body: ## [Feature] Discord Button-based Auto-Mitigation (Policy-Guarded) ### Goal Incident 분석/제안 단계에서 → Discord 클릭 1회로 안전하게 remediation 실행. **중요**: LLM은 *요약…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [624ea8d58d57, 9feab6ec1c95]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #312 — [Improvement] Discord 알림 포맷 강화 - 증거값 포함 및 중복 제거
- author zbnerd; CLOSED; created 2026-02-06T16:19:17Z; closed 2026-02-06T23:08:11Z; reason COMPLETED. Body: ## 개요 현재 Discord 알림이 "PromQL 쿼리"만 포함하고 있어 **감사(audit)가 불가능**합니다. 평가된 결과값(evaluated values) + 타임스탬프 + 중복 제거(dedup) 기능을 추가해야 합니다. ## 현재 문제점 ### 1. 중복 시그…
- discussion: 1 / ## 해결 완료 모니터링 인프라 강화 및 메트릭 수집 개선이 완료되었습니다. ### 완료된 작업 - [x] Prometheus/Grafana 인…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [0fc305aad5ad, 624ea8d58d57, 9e043474befc, 9feab6ec1c95, bbb197938e42, c43381c8db6a]; PR-record issue refs 1 [#319/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #313 — [Documentation] Claim-Evidence Matrix - AI SRE 운영 증거 체계
- author zbnerd; CLOSED; created 2026-02-06T16:19:53Z; closed 2026-02-20T12:58:30Z; reason COMPLETED. Body: ## 개요 AI SRE 시스템의 **주장(Claim) ↔ 코드 ↔ 증거(Evidence)** 매핑을 문서화합니다. "누가/어떻게/무엇을 근거로/어떤 변경을 했는지"가 **감사 가능하게 재현**되도록 합니다. ## 목적 면접관/운영자가 다음을 한 번에 확인할 수 있도록 …
- discussion: 1 / ✅ 문서화 완료 - Claim-Evidence Matrix 생성됨. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [624ea8d58d57, 9feab6ec1c95, ad1d5917db9b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #314 — [Documentation] README에 AI SRE 섹션 추가 - Policy-Guarded Autonomous Loop
- author zbnerd; CLOSED; created 2026-02-06T16:20:43Z; closed 2026-02-20T12:58:31Z; reason COMPLETED. Body: ## 개요 README에 **AI SRE (Policy-Guarded Autonomous Loop)** 섹션을 추가하여 "감사 가능한 자율 운영 시스템"임을 명확히 보여줍니다. ## 목적 면접관이 README를 읽고 다음을 한 번에 이해하도록 합니다: 1. **AI S…
- discussion: 1 / ✅ README에 AI SRE 섹션 추가 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [624ea8d58d57, 9feab6ec1c95, ad1d5917db9b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #316 — [Evidence Pack] 실제 증거 파일/아티팩트 생성
- author zbnerd; CLOSED; created 2026-02-06T17:00:15Z; closed 2026-02-20T12:58:33Z; reason COMPLETED. Body: ## 목적 README Evidence Pack에 명시된 증거 링크를 실제 파일/아티팩트로 생성하여 서류 통과 시 "클릭 즉시 확인 가능한 증거"를 제공 --- ## 증거 항목별 생성 파일 ### 1) N19 Outbox Replay - [ ] `evidence/N19…
- discussion: 1 / ✅ Evidence Pack 파일 11개 생성 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [ad1d5917db9b]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #320 — fix: CI 테스트 실패 해소 (58개 실패 테스트 수정)
- author zbnerd; CLOSED; created 2026-02-07T00:42:25Z; closed 2026-02-07T02:24:34Z; reason COMPLETED. Body: ## 문제 설명 CI 파이프라인에서 58개의 테스트가 실패하여 빌드가 실패하고 있습니다. ### CI 실패 현상 - **Workflow**: Build & Test (Unit Only) - **상태**: failed (2m 42s) - **에러 메시지**: - `Pub…
- discussion: 3 / ## 🔍 Root Cause Analysis Update ### 실제 원인 파악 완료 테스트 실패의 **근본 원인**은 **Testcontain…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [07f50858a727, a5d5a509a865, a64c18f71815]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #322 — feat: SOLID 위반 100% 개선 및 MSA 전환 계획 수립
- author zbnerd; CLOSED; created 2026-02-07T06:28:26Z; closed 2026-02-10T02:29:41Z; reason COMPLETED. Body: ## 개요 SOLID 원칙 위반 100% 개선 완료 (7개 수정) 및 MSA 전환을 위한 Kafka/EDA 도입 설계 완료 ## 배경 Phase 0-3 Clean Architecture 리팩토링을 통해 프로젝트 구조를 정비하고, 향후 MSA 전환을 위한 기반을 마련함 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #328 — [Flaky Test] DonationTest 동시성 테스트 Race Condition
- author zbnerd; CLOSED; created 2026-02-08T17:11:21Z; closed 2026-02-10T02:40:47Z; reason COMPLETED. Body: ## 문제 개요 DonationTest의 두 동시성 테스트(`concurrencyTest`, `hotspotTest`)에서 Race Condition으로 인한 테스트 실패가 발생합니다. ### 테스트 정보 - **테스트 클래스**: `DonationTest` - **테…
- discussion: 1 / Fixed by PR #339 - Resolved race condition in DonationTest with TestAwaitilityHe…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#339/MERGED]; reachable commit-message refs 1 [c5a9747e8239]; PR-record issue refs 2 [#338/MERGED, #339/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #329 — [Flaky Test] RefreshTokenIntegrationTest Redis 저장 대기 시간 이슈
- author zbnerd; CLOSED; created 2026-02-08T17:11:23Z; closed 2026-02-10T02:40:48Z; reason COMPLETED. Body: ## 문제 개요 RefreshTokenIntegrationTest에서 Redis 저장 완료 전에 `rotateRefreshToken()`를 호출하여 `InvalidRefreshTokenException`이 발생합니다. 현재 `Thread.sleep(200ms)`로 우회…
- discussion: 1 / Fixed by PR #339 - Resolved Redis storage wait time issue in RefreshTokenIntegra…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#339/MERGED]; reachable commit-message refs 1 [c5a9747e8239]; PR-record issue refs 2 [#338/MERGED, #339/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #330 — [Flaky Test] LikeSyncCompensationIntegrationTest Redis Key Deletion Issue
- author zbnerd; CLOSED; created 2026-02-08T17:26:30Z; closed 2026-02-10T02:40:50Z; reason COMPLETED. Body: ## 문제 개요 LikeSyncCompensationIntegrationTest의 두 테스트에서 \`syncRedisToDatabase()\` 호출 후 원본 키(\`{buffer:likes}\`)가 삭제되지 않고 남아있는 문제가 발생합니다. ### 테스트 정보 - **…
- discussion: 1 / Fixed by PR #339 - Resolved Redis key deletion issue in LikeSyncCompensationInte…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#339/MERGED]; reachable commit-message refs 1 [c5a9747e8239]; PR-record issue refs 2 [#338/MERGED, #339/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #331 — [TODO] Phase 8 - NexonDataCollector Reactive 전환
- author zbnerd; CLOSED; created 2026-02-08T18:46:02Z; closed 2026-02-08T21:33:50Z; reason COMPLETED. Body: ## 개요 NexonDataCollector.fetchFromNexonApi()에서 .block() 사용 제거하고 Mono를 반환하도록 리팩토링 ## 현재 상태 - **위치**: src/main/java/maple/expectation/service/ingestion/…
- discussion: 1 / Closed by #336. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#336/MERGED]; reachable commit-message refs 2 [0dbfa245bfc4, e8966c9a9ea7]; PR-record issue refs 1 [#336/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #332 — [TODO] 큐브 데이터 조회 API 연동
- author zbnerd; CLOSED; created 2026-02-08T18:46:04Z; closed 2026-02-08T21:33:52Z; reason COMPLETED. Body: ## 개요 Nexon API 큐브 데이터 조회 기능 구현 ## 현재 상태 - **위치**: src/main/java/maple/expectation/service/v2/outbox/impl/NexonApiRetryClientImpl.java:153 - **상태**: 미…
- discussion: 1 / Closed by #336. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#336/MERGED]; reachable commit-message refs 2 [0dbfa245bfc4, e8966c9a9ea7]; PR-record issue refs 1 [#336/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #333 — [TODO] DLQ 핸들러 연동 (NexonApiOutboxProcessor)
- author zbnerd; CLOSED; created 2026-02-08T18:46:05Z; closed 2026-02-08T21:33:53Z; reason COMPLETED. Body: ## 개요 NexonApiOutboxProcessor 실패 시 DLQ 핸들러 연동 ## 현재 상태 - **위치**: - src/main/java/maple/expectation/service/v2/outbox/NexonApiOutboxProcessor.java:264 …
- discussion: 1 / Closed by #336. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#336/MERGED]; reachable commit-message refs 2 [0dbfa245bfc4, e8966c9a9ea7]; PR-record issue refs 1 [#336/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #344 — P0: 운영환경 MySQL Connection Pool 고갈로 인한 서비스 불안정
- author zbnerd; CLOSED; created 2026-02-11T12:48:33Z; closed 2026-02-13T08:38:29Z; reason COMPLETED. Body: ## 장애 보고 ### 시나리오 정보 - **발생 일시**: 2026-02-11 12:40 KST - **Git Commit**: 9a9ad3e - **담당 에이전트**: 🔴 Red (SRE) --- ### 실패 상세 #### 실패 메시지 ``` MySQLLockPoo…
- discussion: 4 / ## 추가 조사 결과 (2026-02-11 13:07) ### 상황 - Lock Pool Size 감소 (150 → 50) 적용됨 - 스케줄러 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [8ca484b3a346, 92647617676f, 988e19c331d9]; PR-record issue refs 1 [#347/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #345 — feat: 커넥션 풀 고갈 시 Discord 웹훅 알림 미작동 문제
- author zbnerd; CLOSED; created 2026-02-11T13:20:31Z; closed 2026-02-13T05:53:21Z; reason COMPLETED. Body: ## 개요 스케줄러 스레드 무한 생성으로 인한 MySQL/Redis 커넥션 풀 고갈 발생 시, Discord 웹훅 알림이 전송되지 않음. ## 현상 - **발생 시간**: 2026-02-11 13:20경 - **문제**: 커넥션 풀 고갈 상황에서 Discord 알림 미…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [d3dd91a47092]; PR-record issue refs 1 [#346/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #353 — [CI] Integration Tests 실패 - Testcontainers Redis 연결 불가
- author zbnerd; CLOSED; created 2026-02-19T12:47:00Z; closed 2026-03-05T05:50:56Z; reason COMPLETED. Body: ## CI 테스트 실패 보고 ### 시나리오 정보 - **시나리오 번호**: CI-001 - **시나리오 명**: GitHub Actions Integration Tests Testcontainers 실패 - **실행 일시**: 2026-02-19 11:21:33 UT…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #354 — V5 CQRS: Redis Stream 메시지 소비 실패 (MongoDB Sync Worker)
- author zbnerd; CLOSED; created 2026-02-20T11:04:39Z; closed 2026-02-20T12:22:17Z; reason COMPLETED. Body: # V5 CQRS: Redis Stream 메시지 소비 문제 ## 📋 문제 개요 Redis Stream에 발행된 메시지를 MongoDBSyncWorker가 소비하지 못하여 MongoDB에 데이터가写入되지 않음 ## 🔍 증상 ### 관찰된 현상 - ✅ **Redis St…
- discussion: 1 / ## ✅ Issue #354 해결 완료 ### 해결 내용 **1. Redis Stream Consumer Group 초기화 문제 해결** - S…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [52601e5b42bd, c4d4dd5a133d]; PR-record issue refs 1 [#355/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #356 — [기능 요청] Spring Batch로 전체 유저 장비 데이터 주기 갱신
- author zbnerd; CLOSED; created 2026-02-20T12:49:37Z; closed 2026-02-23T11:44:43Z; reason COMPLETED. Body: ## 개요 `game_character` 테이블에 있는 모든 OCID를 가지고 주기적으로 Nexon API `/maplestory/v1/character/item-equipment`를 호출하여 전체 유저 데이터를 갱신하는 Spring Batch 작업을 구현합니다. ##…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [3893bbcab1d8]; PR-record issue refs 1 [#363/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #357 — [Bug] V5 CQRS Redis Stream Codec 불일치导致 MongoDB Sync 실패
- author zbnerd; CLOSED; created 2026-02-21T09:18:17Z; closed 2026-03-05T05:50:13Z; reason COMPLETED. Body: ## 문제 개요 V5 CQRS 구조에서 Command Side(MySQL INSERT + Redis Stream Publish)는 정상 동작하나, Query Side(MongoDB)에 데이터가 동기화되지 않는 문제 ## 증상 - ✅ API 호출: 202 Accepted…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #358 — [Feature] 30만 캐릭터 실데이터 수집 및 MongoDB 적재
- author zbnerd; CLOSED; created 2026-02-21T09:18:19Z; closed 2026-03-26T07:43:11Z; reason COMPLETED. Body: ## 기능 요약 V5 부하 테스트의 신뢰성 확보를 위해 30만 캐릭터 실데이터를 수집하고 MongoDB에 적재 ## 배경 현재 V4 부하 테스트는 DB 10건으로 수행 → 반쪽짜리 테스트 실제 규모에서의 인덱스 효율, Buffer Pool 경합, 쿼리 플랜 변화 검증 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #359 — [Refactoring] Command Side JPA → JDBC 배치 전환
- author zbnerd; CLOSED; created 2026-02-21T09:18:20Z; closed 2026-03-05T05:50:56Z; reason COMPLETED. Body: ## 리팩토링 요약 V5 Command Side의 JPA saveAll()을 JDBC 배치 upsert로 전환하여 대량 쓰기 성능 33배 개선 ## 배경 (실측 근거) ### 3개월 전 블로그 게시 결과 방식 1만 건 소요 시간 성능 --- ---------------…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #360 — [Documentation] V4 vs V5 성능 비교표 작성
- author zbnerd; CLOSED; created 2026-02-21T09:18:21Z; closed 2026-03-05T05:50:57Z; reason COMPLETED. Body: ## 문서 요약 V4와 V5 아키텍처의 성능 비교 분석 및 포트폴리오용 정리 문서 작성 ## 비교 항목 ### 1. 응답 시간 시나리오 V4 V5 (CQRS) 개선폭 ---------- ----- ----------- -------- Cache HIT p99 214ms…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #361 — [Refactoring] 도메인 리브랜딩 - 패키지명/인터페이스 정리
- author zbnerd; CLOSED; created 2026-02-21T09:18:24Z; closed 2026-02-21T09:19:20Z; reason COMPLETED. Body: ## 리팩토링 요약 V5 아키텍처 안정화 후 도메인 패키지명과 인터페이스를 정리하여 일관성 확보 ## 배경 V4 → V5 전환 과정에서 기존 패키지 구조와 인터페이스가 혼재 면접 준비 기간을 고려하여 **면접 후 1~2일** 작업 예정 ## 작업 범위 ### Phase…
- discussion: 1 / 면접 준비로 인해 일정 미정 - 필요시 재등록 예정. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #362 — [Feature] OpenTelemetry + Jaeger 트레이싱 추가 (선택적)
- author zbnerd; CLOSED; created 2026-02-21T09:18:37Z; closed 2026-03-05T05:50:57Z; reason COMPLETED. Body: ## 기능 요약 V5 CQRS 파이프라인의 분산 트레이싱을 위해 OpenTelemetry + Jaeger 도입 ## 배경 현재 로그만으로는 Redis Stream → SyncWorker → Transform → MongoDB 파이프라인의 흐름 추적이 어려움 ## 목표 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #366 — [Migration] Phase 1-1: module-common (공통 유틸리티)
- author zbnerd; CLOSED; created 2026-02-24T12:28:50Z; closed 2026-02-24T14:23:53Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 1 첫 번째 배치 ## 대상 파일 (3개) - [ ] CommonException.java (30 lines, 난이도: Low) - [ ] CommonErrorCode.java (40 lines, 난이도: L…
- discussion: 1 / Java-to-Kotlin 마이그레이션 완료. module-common은 이미 100% Kotlin 상태였으며, CommonErrorCodeTe…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [f9456442443f]; PR-record issue refs 1 [#390/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #367 — [Migration] Phase 2-1: module-core Domain 기초 (1/3)
- author zbnerd; CLOSED; created 2026-02-24T12:28:51Z; closed 2026-02-24T14:23:56Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 2 첫 번째 배치 ## 대상 파일 (17개) - [ ] FlameEquipCategory.java (150 lines, Medium) - [ ] FlameType.java (100 lines, Medium) …
- discussion: 1 / Java-to-Kotlin 마이그레이션 완료. 14개 파일 변환 (core/domain/flame: 5개, core/domain/model: 7…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [f9456442443f]; PR-record issue refs 1 [#390/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #368 — [Migration] Phase 2-2: module-core 계산기 인터페이스 (2/3)
- author zbnerd; CLOSED; created 2026-02-24T12:28:52Z; closed 2026-02-24T14:23:58Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 2 두 번째 배치 ## 대상 파일 (17개) 계산기 인터페이스 및 구현체 - [ ] Calculator.java - [ ] CubeCalculator.java - [ ] StarforceCalculator.j…
- discussion: 1 / Java-to-Kotlin 마이그레이션 완료. 11개 파일 변환 (calculator: 3개, character: 3개, equipment: 3…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [f9456442443f]; PR-record issue refs 1 [#390/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #369 — [Migration] Phase 2-3: module-core 도메인 모델 (3/3)
- author zbnerd; CLOSED; created 2026-02-24T12:28:54Z; closed 2026-02-25T08:53:03Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 2 세 번째 배치 ## 대상 파일 (15개) 도메인 모델 및 Value Object - [ ] User.java - [ ] Subscription.java - [ ] Equipment.java - [ ] Eq…
- discussion: 1 / PR #391에서 완료됨 - module-core 도메인 모델 Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [0c40698921d7, fb787b678204]; PR-record issue refs 1 [#391/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #370 — [Migration] Phase 3-1: module-infra Redis/Cache (1/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:32Z; closed 2026-02-25T08:53:05Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 첫 번째 배치 - Redis/Cache 관련 ## 대상 파일 (20개) - [ ] RedissonConfig.java - [ ] RedissonClientFactory.java - [ ] RedisCach…
- discussion: 1 / PR #391에서 완료됨 - module-infra Redis/Cache Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0c40698921d7]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #371 — [Migration] Phase 3-2: module-infra JPA Repository (2/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:34Z; closed 2026-02-25T08:53:07Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 두 번째 배치 - JPA Repository ## 대상 파일 (20개) - [ ] UserRepository.java - [ ] SubscriptionRepository.java - [ ] Equipmen…
- discussion: 1 / PR #391에서 완료됨 - module-infra JPA Repository Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0c40698921d7]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #372 — [Migration] Phase 3-3: module-infra Spring Config (3/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:35Z; closed 2026-02-25T08:53:08Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 세 번째 배치 - Spring Configuration ## 대상 파일 (20개) - [ ] AppConfig.java - [ ] WebConfig.java - [ ] SecurityConfig.java …
- discussion: 1 / PR #391에서 완료됨 - module-infra Spring Config Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0c40698921d7]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #373 — [Migration] Phase 3-4: module-infra AOP Aspects (4/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:37Z; closed 2026-02-25T08:53:10Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 네 번째 배치 - AOP Aspects ## 대상 파일 (10개) - [ ] LoggingAspect.java - [ ] TraceAspect.java - [ ] ExecutionTimeAspect.jav…
- discussion: 1 / PR #391에서 완료됨 - module-infra AOP Aspects Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0c40698921d7]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #374 — [Migration] Phase 3-5: module-infra External API Client (5/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:38Z; closed 2026-02-25T08:53:11Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 다섯 번째 배치 - External API Client ## 대상 파일 (20개) - [ ] NexonApiClient.java - [ ] NexonApiConfig.java - [ ] NexonApiRe…
- discussion: 1 / PR #391에서 완료됨 - module-infra External API Client Kotlin 마이그레이션 완료. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0c40698921d7]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #375 — [Migration] Phase 3-6: module-infra Queue/Message (6/12)
- author zbnerd; CLOSED; created 2026-02-24T12:30:40Z; closed 2026-02-25T11:27:37Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 여섯 번째 배치 - Queue/Message ## 대상 파일 (15개) - [ ] MessageQueueConfig.java - [ ] QueueProducer.java - [ ] QueueConsumer…
- discussion: 1 / PR #392 머지 완료: Java to Kotlin migration for Cube decorators and DTOs. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#392/MERGED]; reachable commit-message refs 2 [35b2a0b13765, 750995264edd]; PR-record issue refs 1 [#392/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #376 — [Migration] Phase 3-7: module-infra Security/JWT (7/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:44Z; closed 2026-02-25T11:27:39Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 일곱 번째 배치 - Security/JWT ## 대상 파일 (10개) - [ ] JwtTokenProvider.java - [ ] JwtAuthenticationFilter.java - [ ] JwtAut…
- discussion: 1 / PR #392 머지 완료: Java to Kotlin migration for Cube decorators and DTOs. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#392/MERGED]; reachable commit-message refs 2 [35b2a0b13765, 750995264edd]; PR-record issue refs 1 [#392/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #377 — [Migration] Phase 3-8: module-infra Utility Classes (8/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:45Z; closed 2026-02-25T14:39:23Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 여덟 번째 배치 - Utility Classes ## 대상 파일 (15개) - [ ] StringUtils.java - [ ] DateTimeUtils.java - [ ] JsonUtils.java - […
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [3b92942ac907]; PR-record issue refs 1 [#395/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #378 — [Migration] Phase 3-9: module-infra Exception/Handler (9/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:46Z; closed 2026-02-25T14:39:31Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 아홉 번째 배치 - Exception/Handler ## 대상 파일 (20개) - [ ] GlobalExceptionHandler.java - [ ] ErrorResponse.java - [ ] Clien…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [3b92942ac907]; PR-record issue refs 1 [#395/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #379 — [Migration] Phase 3-10: module-infra Scheduler/Batch (10/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:48Z; closed 2026-02-26T12:13:59Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 열 번째 배치 - Scheduler/Batch ## 대상 파일 (15개) - [ ] SchedulerConfig.java - [ ] CacheWarmupScheduler.java - [ ] DataSync…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #380 — [Migration] Phase 3-11: module-infra Monitoring/Metrics (11/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:49Z; closed 2026-02-26T12:14:06Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 열한 번째 배치 - Monitoring/Metrics ## 대상 파일 (15개) - [ ] MetricsConfig.java - [ ] PrometheusConfig.java - [ ] Micrometer…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #381 — [Migration] Phase 3-12: module-infra 나머지 컴포넌트 (12/12)
- author zbnerd; CLOSED; created 2026-02-24T12:31:51Z; closed 2026-02-26T11:56:50Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 Phase 3 열두 번째 배치 - 나머지 컴포넌트 ## 대상 파일 (약 99개) module-infra에 남은 모든 Java 파일 - Filter/Interceptor 관련 - DTO/Request/Response 클래…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #382 — [Migration] Phase 5: 최종 검증 및 문서화
- author zbnerd; CLOSED; created 2026-02-24T12:32:20Z; closed 2026-02-27T21:41:32Z; reason COMPLETED. Body: ## 개요 Java-to-Kotlin 마이그레이션 완료 후 최종 검증 ## 선행 조건 - Phase 1 완료 (module-common) - Phase 2 완료 (module-core) - Phase 3 완료 (module-infra) ## 검증 항목 - [ ] 전체 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [12a59dcbcb61]; PR-record issue refs 1 [#401/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #383 — [Architecture] Refactor 1: application service → module-core 이관
- author zbnerd; CLOSED; created 2026-02-24T12:35:56Z; closed 2026-02-26T14:12:49Z; reason COMPLETED. Body: ## 개요 헥사고널 아키텍처 준수를 위해 module-app의 application layer를 module-core로 이관 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (약 20개) ### application/dto/ → core/ap…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [0f2bd08e32a2, b2075654f2db, e3c93143fcd5]; PR-record issue refs 1 [#398/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #384 — [Architecture] Refactor 2: scheduler → module-infra 이관
- author zbnerd; CLOSED; created 2026-02-24T12:35:58Z; closed 2026-02-26T14:12:56Z; reason COMPLETED. Body: ## 개요 스케줄링은 인프라 관심사이므로 module-app에서 module-infra로 이관 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (7개) ### scheduler/ → infra/scheduler/ - [ ] BufferReco…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [0f2bd08e32a2, b2075654f2db, e3c93143fcd5]; PR-record issue refs 1 [#398/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #385 — [Architecture] Refactor 3: monitoring → module-infra 이관
- author zbnerd; CLOSED; created 2026-02-24T12:35:59Z; closed 2026-02-26T18:44:52Z; reason COMPLETED. Body: ## 개요 모니터링은 인프라 관심사이므로 module-app에서 module-infra로 이관 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (약 15개) ### monitoring/ → infra/monitoring/ - [ ] ai/Ai…
- discussion: 1 / 아키텍처 재정렬 완료: monitoring 패키지를 module-infra로 이관하고 DIP 준수를 위한 BufferStatusQuery Por…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [7db4b029601c]; PR-record issue refs 1 [#399/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #386 — [Architecture] Refactor 4: infra config → module-infra 이관
- author zbnerd; CLOSED; created 2026-02-24T12:36:01Z; closed 2026-02-26T18:44:53Z; reason COMPLETED. Body: ## 개요 인프라 설정은 module-infra로 이관, app에는 앱 조립 설정만 유지 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (약 15개) ### config/ → infra/config/ - [ ] BufferConfig.jav…
- discussion: 1 / 아키텍처 재정렬 완료: config 패키지의 중복 Java 파일 11개 삭제 및 module-infra Kotlin 버전으로 통합. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [7db4b029601c]; PR-record issue refs 1 [#399/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #387 — [Architecture] Refactor 5: interfaces (event/filter) → module-infra 이관
- author zbnerd; CLOSED; created 2026-02-24T12:36:02Z; closed 2026-02-26T21:21:38Z; reason COMPLETED. Body: ## 개요 이벤트 어댑터와 서블릿 필터는 인프라 어댑터이므로 module-infra로 이관 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (약 10개) ### interfaces/event/ → infra/adapter/incoming/ev…
- discussion: 1 / PR #400에서 완료되었습니다. GlobalExceptionHandler가 module-web으로 이관되었습니다.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2c921d8f2471, d63ae192bb72]; PR-record issue refs 1 [#400/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #388 — [Architecture] Refactor 6: 공통 컴포넌트 → module-common 이관
- author zbnerd; CLOSED; created 2026-02-24T12:36:04Z; closed 2026-02-26T21:21:40Z; reason COMPLETED. Body: ## 개요 전역 에러 처리와 공통 DTO/Util은 module-common으로 이관 ## 선행 조건 - Kotlin 마이그레이션 완료 후 진행 ## 대상 파일 (약 10개) ### error/ → common/error/ - [ ] GlobalExceptionHand…
- discussion: 1 / PR #400에서 완료되었습니다. 웹 모듈 구조 개선이 완료되었습니다.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2c921d8f2471, d63ae192bb72]; PR-record issue refs 1 [#400/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #389 — [Architecture] Refactor 7: 최종 검증 및 module-app 정리
- author zbnerd; CLOSED; created 2026-02-24T12:36:41Z; closed 2026-02-27T21:41:27Z; reason COMPLETED. Body: ## 개요 헥사고널 아키텍처 준수를 위한 최종 검증 ## 선행 조건 - Refactor 1~6 완료 ## 이상적인 module-app 구조 ``` module-app/ ├── controller/ # 인바운드 어댑터 (✅ 유지) │ ├── v2/ │ ├── v4/ │ …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [12a59dcbcb61]; PR-record issue refs 1 [#401/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #409 — [Architecture] 모듈 분리 ADR 작성 - 4모듈 구조 정의
- author zbnerd; CLOSED; created 2026-02-27T21:42:52Z; closed 2026-02-28T18:44:04Z; reason COMPLETED. Body: ## 개요 module-app에 347개 파일이 몰려있는 구조를 4개 모듈(module-common, module-core, module-infra, module-web)로 분리하기 위한 Architecture Decision Record(ADR) 작성 ## 배경 - …
- discussion: 1 / ADR-003 Hexagonal Architecture 채택 문서 작성 완료 (PR #448) - 모듈 구조 정의 (module-core, mo…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#448/MERGED]; reachable commit-message refs 1 [026c047a26d4]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #410 — [Architecture] Gradle 멀티모듈 의존성 구조 정립
- author zbnerd; CLOSED; created 2026-02-27T21:43:28Z; closed 2026-02-28T20:33:35Z; reason COMPLETED. Body: ## 개요 4개 모듈 간 의존성 구조를 Gradle에 정의하고, 순환 참조를 방지하는 빌드 구조 확립 ## 배경 - 모듈 분리 전 의존성 방향을 명확히 해야 함 - core가 infra/web을 참조하지 않도록 컴파일 타임에 차단 필요 ## 작업 내용 - [ ] mod…
- discussion: 1 / ArchUnit 모듈 의존성 규칙 검증 테스트 추가 완료 (PR #461) - core → infra/web/app 의존 금지 검증 - comm…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#461/MERGED]; reachable commit-message refs 2 [1c15ddc1c032, 820dc73f0245]; PR-record issue refs 2 [#460/MERGED, #461/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #411 — [Module-Web] Controller/DTO 계층 이관
- author zbnerd; CLOSED; created 2026-02-27T21:43:29Z; closed 2026-03-01T02:03:10Z; reason COMPLETED. Body: ## 개요 module-app의 controller 패키지와 controller/dto 패키지를 module-web으로 이관 ## 이관 대상 ### Controllers - AdminController.java - AlertTestController.java - Aut…
- discussion: 1 / ✅ 완료: 모든 Controller가 module-web으로 이관됨 **완료된 이관 목록:** - AdminController (PR #470)…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#470/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 2 [#460/MERGED, #464/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #412 — [Module-Web] Filter/Interceptor 계층 이관
- author zbnerd; CLOSED; created 2026-02-27T21:43:30Z; closed 2026-03-01T02:11:38Z; reason COMPLETED. Body: ## 개요 module-app의 interfaces/filter 및 관련 필터/인터셉터를 module-web으로 이관 ## 이관 대상 - interfaces/filter/* - interfaces/rest/* - 인증/인가 관련 필터 ## 작업 내용 - [ ] modu…
- discussion: 1 / 이미 완료됨 - Filter/Interceptor가 module-infra에 위치: - MDCFilter - DeduplicationFilter…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#464/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #413 — [Module-Web] Web Config 이관 (CorsProperties, OpenApiConfig, WebConfig)
- author zbnerd; CLOSED; created 2026-02-27T21:43:32Z; closed 2026-03-01T02:11:40Z; reason COMPLETED. Body: ## 개요 HTTP 관련 설정 클래스들을 module-web으로 이관 ## 이관 대상 - config/CorsProperties.java - config/OpenApiConfig.java - config/WebConfig.java ## 작업 내용 - [ ] module…
- discussion: 1 / PR #477 머지 완료 - WebConfig, CorsProperties, OpenApiConfig 이관. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#477/MERGED]; reachable commit-message refs 1 [6b721c1443fa]; PR-record issue refs 2 [#464/MERGED, #477/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #414 — [Module-Core] Application 계층 이관 (application/*)
- author zbnerd; CLOSED; created 2026-02-27T21:44:18Z; closed 2026-03-01T02:12:54Z; reason COMPLETED. Body: ## 개요 유스케이스 계층인 application 패키지를 module-core로 이관 ## 이관 대상 ### DTOs - application/dto/CharacterEquipmentDto.java - application/dto/CharacterLikeDto.jav…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 **분석 결과:** - ApplicationService는 `@Service`, `LogicExecuto…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #415 — [Module-Core] Calculator 도메인 이관 (service/calculator/*)
- author zbnerd; CLOSED; created 2026-02-27T21:44:20Z; closed 2026-02-28T19:59:04Z; reason COMPLETED. Body: ## 개요 장비 강화 비용 계산 엔진인 calculator 패키지를 module-core로 이관 ## 이관 대상 ### 구현체 - service/calculator/impl/BaseItem.java - service/calculator/v4/impl/BaseEquipm…
- discussion: 1 / Calculator 도메인 이관 완료 (PR #458) - ExpectationCalculatorPort, EnhanceDecorator, Ba…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#458/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#458/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #416 — [Module-Core] Cube 도메인 이관 (service/cube/*)
- author zbnerd; CLOSED; created 2026-02-27T21:44:21Z; closed 2026-03-01T02:13:57Z; reason COMPLETED. Body: ## 개요 큐브 확률 계산 및 관련 컴포넌트를 module-core로 이관 ## 이관 대상 ### Components - service/cube/component/CubeDpCalculator.java - service/cube/component/CubeSlotCoun…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 **분석 결과:** Cube 컴포넌트들은 Spring 의존성 보유: - `@Component`, `@Ca…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#458/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #417 — [Module-Core] Flame 도메인 이관 (service/flame/*)
- author zbnerd; CLOSED; created 2026-02-27T21:44:23Z; closed 2026-02-28T19:59:05Z; reason COMPLETED. Body: ## 개요 플레임(업그레이드) 관련 계산 로직을 module-core로 이관 ## 이관 대상 ### Components - service/flame/component/FlameScoreResolver.java ### Config (레지스트리) - service/flam…
- discussion: 1 / Flame 도메인 이관 완료 (PR #458) - BossEquipmentRegistry, JobStatMapping 이관 (순수 데이터) - …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#458/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#458/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #418 — [Module-Core] Starforce 도메인 이관 (service/starforce/*)
- author zbnerd; CLOSED; created 2026-02-27T21:44:24Z; closed 2026-02-28T19:59:07Z; reason COMPLETED. Body: ## 개요 스타포스 강화 확률 테이블 및 계산 로직을 module-core로 이관 ## 이관 대상 - service/starforce/config/NoljangProbabilityTable.java - service/starforce/config/StarforceLoo…
- discussion: 1 / Starforce 도메인 이관 완료 (PR #458) - StarforceConstants 이관 (순수 상수) - StarforceCalcula…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#458/MERGED]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#458/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #419 — [Module-Core] Policy 계층 이관 (service/policy/*)
- author zbnerd; CLOSED; created 2026-02-27T21:45:11Z; closed 2026-02-28T20:15:01Z; reason COMPLETED. Body: ## 개요 비용 계산 정책 및 전략 패턴 구현체를 module-core로 이관 ## 이관 대상 - service/policy/CostCalculationStrategy.java - service/policy/CubeCostPolicy.java - service/poli…
- discussion: 1 / Policy 도메인 이관 완료 (PR #459) - CostCalculationStrategy 인터페이스 이관 - TableBasedCostSt…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#459/MERGED]; reachable commit-message refs 2 [91193cca4efc, 96f2c855d71f]; PR-record issue refs 1 [#459/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #420 — [Module-Core] Facade 서비스 이관 (service/facade/*)
- author zbnerd; CLOSED; created 2026-02-27T21:45:13Z; closed 2026-03-01T02:15:30Z; reason COMPLETED. Body: ## 개요 유스케이스 조합을 담당하는 Facade 서비스를 module-core로 이관 ## 이관 대상 - service/facade/GameCharacterFacade.java - service/facade/GameCharacterSynchronizer.java ##…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 Facade 서비스는 `@Service`, `@Transactional` 등 Spring 의존성 보유. …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #421 — [Module-Core] V4 순수 로직 이관 (service/v4/*)
- author zbnerd; CLOSED; created 2026-02-27T21:45:15Z; closed 2026-03-01T02:15:31Z; reason COMPLETED. Body: ## 개요 V4 API의 비즈니스 로직 중 순수 계산/전략 부분을 module-core로 이관 ## 이관 대상 (core로 이관) - service/v4/buffer/BackoffStrategy.java - service/v4/buffer/ExpectationWrite…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 V4 서비스들은 Spring 의존성(@Service, @Cacheable 등) 보유. 순수 계산 로직은 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #422 — [Module-Core] V5 순수 로직 이관 (service/v5/*)
- author zbnerd; CLOSED; created 2026-02-27T21:45:17Z; closed 2026-03-01T02:15:33Z; reason COMPLETED. Body: ## 개요 V5 API의 비즈니스 로직 중 순수 계산/전략/매핑 부분을 module-core로 이관 ## 이관 대상 (core로 이관) ### Services - service/v5/ViewTransformer.java - service/v5/executor/Prior…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 V5 서비스들은 Spring 의존성(@Service, @Scheduled 등) 보유. 순수 로직은 이미 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #423 — [Module-Core] Monitoring 순수 로직 이관 (monitoring/*)
- author zbnerd; CLOSED; created 2026-02-27T21:45:19Z; closed 2026-03-01T02:15:35Z; reason COMPLETED. Body: ## 개요 모니터링 패키지 중 순수 판단/알고리즘/모델 부분을 module-core로 이관 ## 이관 대상 (core로 이관) ### AI 로직 - monitoring/ai/AiAnalysisFormatter.java - monitoring/ai/AiPromptBuil…
- discussion: 1 / ADR-005 원칙과 충돌로 이관 보류 Monitoring 컴포넌트들은 Spring 의존성 보유. 순수 로직은 이미 core에 위치. Sprin…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #424 — [Module-Infra] Batch/Scheduler 계층 이관
- author zbnerd; CLOSED; created 2026-02-27T21:46:02Z; closed 2026-03-05T05:49:57Z; reason COMPLETED. Body: ## 개요 배치 잡과 스케줄러를 module-infra로 이관 ## 이관 대상 ### Batch - batch/listener/BatchMetricsLogger.java - batch/reader/OcidReader.java - batch/writer/LowPriori…
- discussion: 3 / ## 진행 상황 업데이트 (2026-02-28) ### ✅ 완료된 작업 - **LowPriorityQueueWriter** → Hexagonal…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 7 [0979c84dd9f2, 3ce4461c33ac, 40ff4128d38c, 7fc87084ada6, 8f76f93b6fb6, 933f788af611, b197d949f0de]; PR-record issue refs 13 [#449/MERGED, #450/MERGED, #451/MERGED, #452/MERGED, #453/MERGED, #457/MERGED, #478/MERGED, #479/MERGED, #480/MERGED, #481/MERGED, #482/MERGED, #483/MERGED, #484/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #425 — [Module-Infra] Cache 구현체 이관 (service/cache/*)
- author zbnerd; CLOSED; created 2026-02-27T21:46:04Z; closed 2026-03-01T03:14:18Z; reason COMPLETED. Body: ## 개요 캐시 서비스 및 저장소 구현체를 module-infra로 이관 ## 이관 대상 (infra로 이관) - service/cache/AbstractTieredCacheService.java - service/cache/EquipmentCacheService.ja…
- discussion: 1 / 이미 완료됨 - Cache 구현체가 module-infra/infrastructure/cache에 위치: - TieredCache.kt - Ti…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #426 — [Module-Infra] Redis 어댑터 이관 (service/like/realtime, RedisLock 등)
- author zbnerd; CLOSED; created 2026-02-27T21:46:06Z; closed 2026-03-01T03:14:48Z; reason COMPLETED. Body: ## 개요 Redis 연동 어댑터들을 module-infra로 이관 ## 이관 대상 (infra로 이관) ### Like Realtime 구현체 - service/like/realtime/impl/RedisLikeEventPublisher.java - service/l…
- discussion: 1 / 이미 완료됨 - Redis 어댑터가 module-infra에 위치: - infrastructure/redis/ - infrastructure/p…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #427 — [Module-Infra] Outbox/DLQ 구현체 이관 (service/outbox/*)
- author zbnerd; CLOSED; created 2026-02-27T21:46:08Z; closed 2026-03-01T03:15:03Z; reason COMPLETED. Body: ## 개요 Transactional Outbox 패턴 및 DLQ 구현체를 module-infra로 이관 ## 이관 대상 (infra로 이관) - service/outbox/impl/NexonApiRetryClientImpl.java - service/outbox/imp…
- discussion: 1 / 이미 완료됨 - Outbox/DLQ 구현체가 module-infra에 위치: - domain/v2/*Outbox.kt, *Dlq.kt - inf…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #428 — [Module-Infra] Nexon API 클라이언트 이관
- author zbnerd; CLOSED; created 2026-02-27T21:46:10Z; closed 2026-03-01T03:15:22Z; reason COMPLETED. Body: ## 개요 Nexon Open API 연동 클라이언트를 module-infra로 이관 ## 이관 대상 ### API 클라이언트 - service/ingestion/NexonDataCollector.java - service/ingestion/AclPipelineMetr…
- discussion: 1 / 이미 완료됨 - Nexon API 클라이언트가 module-infra에 위치: - infrastructure/external/NexonApiCl…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #429 — [Module-Infra] Discord/OpenAI/Prometheus 어댑터 이관
- author zbnerd; CLOSED; created 2026-02-27T21:46:49Z; closed 2026-03-01T03:15:40Z; reason COMPLETED. Body: ## 개요 외부 서비스 연동 어댑터들을 module-infra로 이관 ## 이관 대상 ### Discord - service/v2/alert/dto/DiscordMessage.java - service/v2/alert/DiscordAlertService.java - s…
- discussion: 1 / 이미 완료됨 - 어댑터들이 module-infra에 위치: - DiscordNotifier.kt, DiscordAlertChannel.kt - …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #430 — [Module-Infra] Infra 설정 클래스 이관 (config/*)
- author zbnerd; CLOSED; created 2026-02-27T21:46:51Z; closed 2026-03-01T03:15:58Z; reason COMPLETED. Body: ## 개요 인프라 관련 설정 클래스들을 module-infra로 이관 ## 이관 대상 - config/AppProperties.java - config/CacheConfig.java - config/CalculationProperties.java - config/Dat…
- discussion: 1 / 이미 완료됨 - Infra 설정 클래스가 module-infra/infrastructure/config에 위치. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #431 — [Module-Infra] Shutdown/Recovery 구현체 이관 (service/shutdown/*)
- author zbnerd; CLOSED; created 2026-02-27T21:46:52Z; closed 2026-03-01T03:15:59Z; reason COMPLETED. Body: ## 개요 Graceful Shutdown 및 복구 관련 구현체를 module-infra로 이관 ## 이관 대상 (infra로 이관) - service/shutdown/RedisEquipmentPersistenceTrackerAdapter.java - service/s…
- discussion: 1 / 이미 완료됨 - Shutdown/Recovery 구현체가 module-infra/infrastructure/lifecycle에 위치. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #432 — [Module-Infra] V5 Worker/Config 이관
- author zbnerd; CLOSED; created 2026-02-27T21:46:55Z; closed 2026-03-01T03:16:01Z; reason COMPLETED. Body: ## 개요 V5 API의 Worker 및 Config 구현체를 module-infra로 이관 ## 이관 대상 - service/v5/event/MongoSyncEventPublisher.java - service/v5/event/MongoSyncEventPublishe…
- discussion: 1 / V5 Worker/Config는 module-app에 유지 - 비즈니스 로직 영역. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #433 — [Module-Infra] Auth 구현체 이관 (service/auth/*)
- author zbnerd; CLOSED; created 2026-02-27T21:46:57Z; closed 2026-03-01T03:16:03Z; reason COMPLETED. Body: ## 개요 인증/인가 관련 구현체를 module-infra로 이관하고 포트 분리 ## 이관 대상 (infra로 이관) - service/auth/ApiKeyValidator.java - service/auth/SessionManager.java - service/aut…
- discussion: 1 / Auth 구현체가 이미 module-infra에 위치 (NexonAuthClient, AuthenticatedUser). Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #434 — [Module-Infra] V2 서비스 계층 정리 (service/v2/*)
- author zbnerd; CLOSED; created 2026-02-27T21:47:59Z; closed 2026-03-01T03:16:06Z; reason COMPLETED. Body: ## 개요 V2 서비스 계층을 core/infra로 적절히 분리하여 이관 ## 이관 대상 ### alert (→ infra) - service/v2/alert/dto/DiscordMessage.java - service/v2/alert/DiscordAlertServic…
- discussion: 1 / V2 서비스 계층은 module-app에 유지 - 유즈케이스/트랜잭션 경계. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #435 — [Module-Common] 공통 유틸/예외 정의
- author zbnerd; CLOSED; created 2026-02-27T21:48:01Z; closed 2026-02-28T21:14:43Z; reason COMPLETED. Body: ## 개요 진정으로 공통인 유틸리티와 예외 타입만 module-common에 정의 ## 이관 대상 (엄격한 기준 적용) ### 가능한 것 - 범용 예외 타입 (ClientBaseException, ServerBaseException 등) - 공통 에러 코드 Enum -…
- discussion: 1 / ## 완료 사항 module-common이 이미 ADR-005/ADR-014 완벽 준수: ### 수락 기준 충족 기준 상태 ------ ----…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#462/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #436 — [Refactoring] Port/Adapter 패턴 적용 - 인터페이스 분리
- author zbnerd; CLOSED; created 2026-02-27T21:48:04Z; closed 2026-02-28T10:38:33Z; reason COMPLETED. Body: ## 개요 모듈 분리 과정에서 식별된 Port/Adapter 분리 작업 통합 관리 ## 분리 대상 ### Auth - TokenPort (core) / JwtTokenService (infra) - SessionPort (core) / RedisSessionManage…
- discussion: 1 / ✅ Port/Adapter 패턴 적용 완료 (1차) - TokenPort 인터페이스 정의 (module-core) - TokenPortImpl …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#447/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #437 — [Kotlin] Kotlin 혼용 환경 설정
- author zbnerd; CLOSED; created 2026-02-27T21:48:06Z; closed 2026-02-28T10:38:29Z; reason COMPLETED. Body: ## 개요 Java + Kotlin 공존 환경을 설정하여 점진적 마이그레이션 준비 ## 작업 내용 - [ ] build.gradle에 Kotlin 플러그인 추가 - [ ] Kotlin 표준 라이브러리 의존성 추가 - [ ] Java + Kotlin 공동 컴파일 설정 -…
- discussion: 1 / ✅ Kotlin 혼용 환경 설정 완료 - build.gradle에 Kotlin 플러그인 추가 - ktlint/spotless 설정 완료 - Ja…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#447/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #438 — [Kotlin] 모듈별 마이그레이션 우선순위 정의
- author zbnerd; CLOSED; created 2026-02-27T21:48:08Z; closed 2026-02-28T10:38:31Z; reason COMPLETED. Body: ## 개요 Kotlin 마이그레이션의 안전한 순서와 전략 정의 ## 추천 순서 1. **module-web**: DTO/Controller (변환 난이도 낮음, 효과 빠름) 2. **module-common**: 작아서 정리 쉬움 3. **module-core**: 순…
- discussion: 1 / ✅ 모듈별 마이그레이션 우선순위 정의 완료 ADR-002 및 구현 계획 문서에 정의됨: - Priority 1: module-web (DTOs …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#447/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #439 — [Integration] 모듈 분리 후 통합 테스트 검증
- author zbnerd; CLOSED; created 2026-02-27T21:49:04Z; closed 2026-03-05T05:49:57Z; reason COMPLETED. Body: ## 개요 모든 모듈 분리 완료 후 전체 시스템 동작 검증 ## 작업 내용 - [ ] ./gradlew clean build 전체 통과 - [ ] ./gradlew test 전체 통과 (479개+) - [ ] Chaos Test 시나리오 전체 통과 - [ ] API 엔…
- discussion: 1 / ## 검증 결과 (2026-03-01) ### ✅ 빌드 검증 - `./gradlew clean build -x test` 통과 (57 tasks…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #440 — [Documentation] 모듈 분리 완료 문서화
- author zbnerd; CLOSED; created 2026-02-27T21:49:06Z; closed 2026-03-05T05:49:57Z; reason COMPLETED. Body: ## 개요 모듈 분리 완료 후 아키텍처 문서 업데이트 ## 작업 내용 - [ ] CLAUDE.md 모듈 구조 섹션 업데이트 - [ ] architecture.md 다이어그램 업데이트 - [ ] README.md 모듈 설명 추가 - [ ] 개발자 온보딩 가이드 업데이트 …
- discussion: 1 / ## 문서화 현황 ### ✅ 완료된 문서 - **ADR-005**: 모듈 의존성 그래프 및 이관 전략 (Completed) - 의존성 다이어그램…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#487/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #441 — [Cleanup] module-app 제거 및 bootstrap 모듈 고려
- author zbnerd; CLOSED; created 2026-02-27T21:49:08Z; closed 2026-03-05T05:49:57Z; reason COMPLETED. Body: ## 개요 모든 코드 이관 후 module-app 정리 및 실행 모듈 구조 개선 ## 작업 내용 - [ ] module-app에 남은 코드 확인 - [ ] ExpectationApplication.java → 적절한 모듈로 이동 - [ ] module-app 제거 또는…
- discussion: 1 / ## 현황 분석 module-app은 여전히 필요: - 유즈케이스 서비스 (service/v2, v4, v5) - ApplicationServi…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 2 [#486/MERGED, #488/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #442 — [CI/CD] 모듈 분리 후 빌드 파이프라인 업데이트
- author zbnerd; CLOSED; created 2026-02-27T21:49:10Z; closed 2026-03-05T05:49:58Z; reason COMPLETED. Body: ## 개요 멀티모듈 구조에 맞게 CI/CD 파이프라인 업데이트 ## 작업 내용 - [ ] GitHub Actions 워크플로우 업데이트 - [ ] 모듈별 테스트 병렬 실행 설정 - [ ] 모듈별 빌드 캐시 최적화 - [ ] Docker 빌드 전략 업데이트 - [ ] 배…
- discussion: 1 / ## CI/CD 현황 현재 빌드 파이프라인 정상 동작: - `./gradlew clean build` 통과 - 멀티 모듈 구조 지원 ADR-00…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #443 — [ADR] 모듈 분리 완료 후 ADR 상태 업데이트
- author zbnerd; CLOSED; created 2026-02-27T21:49:11Z; closed 2026-03-01T02:03:22Z; reason COMPLETED. Body: ## 개요 모듈 분리 작업 완료 후 관련 ADR 문서 상태를 Accepted → Superseded/Completed로 업데이트 ## 작업 내용 - [ ] #409에서 작성한 ADR 최종 상태로 업데이트 - [ ] 결정 사항 이행 여부 체크리스트 완료 - [ ] 실제 …
- discussion: 1 / ✅ 완료: ADR-005 상태를 Completed로 업데이트 **PR:** #476 **변경 사항:** - 모든 Phase 완료 표시 - 이관 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 1 [#476/MERGED]; reachable commit-message refs 1 [026c047a26d4]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #8 — [Refactor/Perf] 키 기반 락 도입을 통한 장비 데이터 캐싱 동시성 문제 해결
- author zbnerd; CLOSED; created 2025-12-15T21:23:16Z; closed 2025-12-17T07:19:54Z; reason COMPLETED. Body: 현상: 장비 데이터 캐시 만료 시, 동일 캐릭터에 대한 다수의 요청이 외부 API 중복 호출 및 DB 중복 쓰기를 유발합니다. 문제: 불필요한 네트워크 트래픽 및 DB 부하 발생, 레이스 컨디션으로 인한 데이터 갱신 비일관성 발생 가능성. 목표: ConcurrentHa…
- discussion: 1 / ## ✅ 해결 완료 (Resolved) ### 적용 내용 1. **Key-based Lock 도입:** `ReentrantLock`과 `Conc…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [040abeab64e5, 1dbcec1518b5, 2890e5ca2b74, 8da72d5ea7ab]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #9 — [Bug/Concurrency] 최초 캐릭터 생성 시 Race Condition으로 인한 Unique 제약 위반 방지
- author zbnerd; CLOSED; created 2025-12-15T21:24:27Z; closed 2025-12-19T15:51:24Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** `GameCharacterService.findCharacterByUserIgn` 메서드에서 특정 유저가 DB에 존재하지 않을 때, 찰나의 순간에 동일한 `userIgn`으로 여러 요청이 들어…
- discussion: 2 / ##📝 Issue Comment: Race Condition 재현 성공 및 원인 분석 1. 재현 테스트 코드 (@MockitoBean 활용) N…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [2890e5ca2b74]; PR-record issue refs 1 [#41/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #10 — [Refactor/Memory] LoggingAspect의 실행 시간 통계 메모리 누수 위험 제거
- author zbnerd; CLOSED; created 2025-12-15T21:25:07Z; closed 2025-12-23T17:34:37Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: `LoggingAspect` 및 `PerformanceStatisticsCollector`가 모든 호출 시간을 `ConcurrentLinkedQueue`에 개별 저장함. - **위험**: 개별…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [2890e5ca2b74, 3225c550aa9f]; PR-record issue refs 1 [#66/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #11 — [Feature/Cache] EquipmentResponse 결과 캐싱 도입을 통한 응답 속도 및 DB 부하 개선
- author zbnerd; CLOSED; created 2025-12-15T21:26:56Z; closed 2025-12-27T22:34:58Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** 동일 캐릭터의 장비 정보 요청 시, 매번 DB에서 Raw JSON을 조회하고 이를 `EquipmentResponse` 객체로 파싱하는 고비용 작업이 반복됨. - **리스크:** 빈번한 트래픽 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [026c047a26d4, 2890e5ca2b74, 48142d52fc5c, 4b326926aa10, c9fa7032bcc8]; PR-record issue refs 1 [#83/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #12 — [Refactor/Memory] CubeService 계산 결과 캐시(calculationCache) 무한정 성장 방지
- author zbnerd; CLOSED; created 2025-12-15T21:27:22Z; closed 2025-12-27T22:34:59Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** 현재 `CubeService`의 계산 결과 캐시가 크기 제한이 없는(Unbounded) 구조로 설계되어 있어, 유니크한 옵션 조합 요청이 누적될수록 메모리 점유율이 지속적으로 상승함. - **…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [2890e5ca2b74, 48142d52fc5c, 4b326926aa10, 5530c297c407]; PR-record issue refs 1 [#84/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #13 — [Refactor/Reliability] LikeSyncScheduler의 DB 반영 실패 시 재시도 및 알림 체계 도입
- author zbnerd; CLOSED; created 2025-12-15T21:28:24Z; closed 2025-12-23T17:48:19Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: 스케줄러가 DB 업데이트 실패 시 로그만 남기고 작업을 중단함. - **위험**: 일시적 DB 오류나 네트워크 이슈 발생 시 좋아요 누적 수치가 영구적으로 유실됨. ### 🎯 Goal (목표)…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [2890e5ca2b74, 9cb3a051747d, d1eaa1485f21]; PR-record issue refs 1 [#67/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #14 — [Refactor/Cleanup] 미사용 및 Deprecated 코드베이스 정리
- author zbnerd; CLOSED; created 2025-12-15T21:28:50Z; closed 2026-01-17T01:49:29Z; reason COMPLETED. Body: 현상: 코드베이스에 최종 채택되지 않은 낙관적 락 로직(clickLikeWithOptimisticLock) 등 미사용 코드와 @Deprecated 코드가 남아있어 가독성을 저해합니다. 문제: 코드 이해 및 유지보수 시 혼란 유발, 불필요한 코드 추적 비용 발생. 목표:…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, bf6fa4cc4bfd]; PR-record issue refs 1 [#193/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #16 — [Refactor/Reliability] requestId 기반 도네이션 요청 멱등성 보장
- author zbnerd; CLOSED; created 2025-12-16T05:47:01Z; closed 2025-12-16T13:18:56Z; reason COMPLETED. Body: 현상: 현재 도네이션 로직은 잔액 부족 조건으로 단순한 따닥 요청만 방어하며, 네트워크 재전송이나 외부 시스템의 재호출로 인한 **이중 처리(Double Processing)**에는 취약합니다. 문제: 성공적인 도네이션이 재처리될 경우, 게스트의 잔액이 이중으로 차감되…
- discussion: 0. Linked PRs: 1 [#19/MERGED/merged=yes 229fd878d05efd505f21734a0494ea090fc5d196]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#19/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [229fd878d05e, b8445966f792, c18da480ee2d]; PR-record issue refs 1 [#19/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 설계 case.

### Issue #17 — [Feature/Alert] 도네이션 핵심 트랜잭션 실패 시 모니터링 알림 구축
- author zbnerd; CLOSED; created 2025-12-16T05:47:31Z; closed 2025-12-17T05:33:28Z; reason COMPLETED. Body: 현상: 도네이션 과정 중 DB 연결 끊김, 락 타임아웃 등 심각한 오류 발생 시 현재는 log.error만 기록합니다. 문제: 중요한 이체 과정의 영구적 실패가 발생해도 실시간으로 개발팀/운영팀에게 경고가 전달되지 않아 조기 대응이 불가능합니다. 목표: sendCoff…
- discussion: 0. Linked PRs: 1 [#22/MERGED/merged=yes b8445966f792e33bef6466bef3a46c93f685f4bc]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#22/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [b8445966f792]; PR-record issue refs 1 [#22/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 기능 case.

### Issue #18 — [Infra] Spring Profiles를 이용한 Local/Prod DB 환경 분리 및 안전장치 구축
- author zbnerd; CLOSED; created 2025-12-16T05:48:23Z; closed 2025-12-16T14:45:54Z; reason COMPLETED. Body: Background 현재 로컬 개발 시 EC2(MySQL)와 같은 공용 DB를 직접 바라보며 개발하는 구조로 인해 다음과 같은 리스크가 존재합니다. 데이터 손실 위험: 테스트나 로컬 실행 중 deleteAll() 또는 잘못된 초기화 로직 수행 시 운영/공용 데이터가 삭…
- discussion: 1 / ### 🚨 테스트 실패 원인 분석 및 환경 분리 필요성 제기 현재 로컬/테스트 환경이 운영(또는 공용) DB를 함께 바라보고 있어 **데이터 오…. Linked PRs: 1 [#20/MERGED/merged=yes c18da480ee2de254c80c7fbed869dbb96a821db2]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#20/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [b8445966f792, c18da480ee2d]; PR-record issue refs 1 [#20/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 운영 case.

### Issue #21 — Exception 계층 구조 개편 및 Global Exception Handling 구축
- author zbnerd; CLOSED; created 2025-12-16T15:11:16Z; closed 2025-12-20T13:32:15Z; reason COMPLETED. Body: 📝 개요 (Description) 현재 프로젝트 내에 산재된 커스텀 예외(CharacterNotFoundException, InsufficientPointException 등)들을 단일한 응답 코드(Response Code) 기반의 계층 구조로 재편합니다. 이를 통해 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [410e0f757579, c08970f6d734]; PR-record issue refs 1 [#53/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #26 — [Ops] Server Restart 시 In-Memory 데이터 유실 가능성 분석 및 대응 방안 검토
- author zbnerd; CLOSED; created 2025-12-17T14:28:05Z; closed 2025-12-22T22:52:43Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 `Write-Behind` 패턴을 통해 극강의 RPS(Throughput)를 확보했으나, 데이터가 DB에 반영되기 전 메모리(Caffeine Cache / AtomicLong)에 머무는 구간이 존재합니다.…
- discussion: 0. Linked PRs: 1 [#60/MERGED/merged=yes d8840de818b7f3cb3bfb690ab044658d6d06ea58]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#60/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [d8840de818b7]; PR-record issue refs 1 [#60/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 운영 case.

### Issue #27 — [Refactoring] Scale-out 확장을 대비한 저장소 전체 코드 분석 및 동시성 제어 구조 리팩토링
- author zbnerd; CLOSED; created 2025-12-17T14:28:34Z; closed 2025-12-28T12:56:50Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 프로젝트는 초기 개발 단계로, 동시성 제어가 필요한 구간에 Java 고유의 `synchronized` 키워드나 인메모리 상태 관리가 혼재되어 있습니다. 이러한 구조는 **단일 서버(Scale-up)** 환경…
- discussion: 11 / ### [Identify] `synchronized(String.intern())` 기반 App-level lock 발견 - **Location…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 14 [27a8dffce90d, 27c30c4793fc, 32ee737c3bd2, 3b85bbd5bc92, 48142d52fc5c, 4b326926aa10, 52f576a2e2c0, 57880f64deaf, 5964ac016fd8, 6024a43e0aa6, 8a63e563cb9d, a2cb1b92913b, cca2e03f1789, f0e14ed551a8]; PR-record issue refs 10 [#85/MERGED, #86/MERGED, #87/MERGED, #88/MERGED, #92/MERGED, #93/MERGED, #94/MERGED, #95/MERGED, #96/MERGED, #97/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #28 — [Design] Pessimistic Lock vs Atomic Update 선택 근거 및 도메인별 적용 기준 정리
- author zbnerd; CLOSED; created 2025-12-17T14:29:15Z; closed 2026-01-27T14:26:10Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 동시성 제어를 위해 여러 기법(비관적 락, 낙관적 락, Atomic Update 등)을 사용할 수 있으나, 모든 도메인에 동일한 방식을 적용하는 것은 비효율적입니다. - **비관적 락(Pessimistic Loc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [00fca3a58edd, 371377642885]; PR-record issue refs 1 [#275/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #29 — [Monitoring] 성능 저하 및 장애 원인 추적을 위한 핵심 지표(Metrics) 정의
- author zbnerd; CLOSED; created 2025-12-17T14:29:55Z; closed 2025-12-22T20:31:16Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: 현재 시스템은 외부 부하 테스트(Locust) 결과에만 의존하고 있어, 실제 운영 환경에서의 내부 상태(White-box monitoring) 파악이 불가능함. - **위험**: 응답 지연 발…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [44027ed3bf7a]; PR-record issue refs 1 [#55/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #30 — [Test] Test 환경에서 운영 DB 접근 위험 차단 및 환경 격리 전략 수립
- author zbnerd; CLOSED; created 2025-12-17T14:30:22Z; closed 2025-12-18T15:22:59Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 테스트 코드 실행 시 별도의 프로필 분리가 명확하지 않아, 로컬(`local`) 설정이 테스트 환경(`test`)에 영향을 주는 문제가 발견되었습니다. 이로 인해 테스트 수행 시 H2(인메모리)가 아닌 운영/개발…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #31 — [Architecture] 넥슨 장비 데이터 조회 성능 개선: MySQL 기반 Cache-Aside 전략 도입 (TTL 15min)
- author zbnerd; CLOSED; created 2025-12-17T15:23:04Z; closed 2025-12-18T15:22:33Z; reason COMPLETED. Body: ## 📋 Context (배경 및 문제 상황) **MapleExpectation** 서비스는 유저의 장비 데이터를 조회하기 위해 넥슨 Open API를 사용하고 있습니다. 현재 구조에서는 다음과 같은 성능 및 비용 문제가 발생하고 있습니다. - **High Latenc…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #32 — [Infra] 로컬 개발 환경 불안정성 해결 및 운영 리스크 방지를 위한 DB 컨테이너화 (Docker Compose 도입)
- author zbnerd; CLOSED; created 2025-12-18T10:05:50Z; closed 2025-12-18T13:19:07Z; reason COMPLETED. Body: ## [Infra] 로컬 개발 환경 불안정성 해결 및 운영 리스크 방지를 위한 DB 컨테이너화 (Docker Compose 도입) ### 📌 Problem Definition (문제 정의) * **Local Environment Corruption:** 윈도우 Hype…
- discussion: 4 / Issue #32: DB 컨테이너화 및 인프라 안정화 작업 회고 드디어 DB 컨테이너 도입과 배포 안정화 작업을 완료했습니다. 이 과정에서 겪은…. Linked PRs: 1 [#33/MERGED/merged=yes bf045f0d1532ea41a6869921630eae8cab48c290]. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 1 [#33/MERGED]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [35eac2498471, 47b21834d87f, a686646e18a1, bf045f0d1532, e0303653a7d3, f292cdddfcd5]; PR-record issue refs 1 [#33/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: linked merged PR exists; code resolution evidence is that PR’s file/commit record. Portfolio: 운영 case.

### Issue #39 — [Design Pattern] Proxy 및 Decorator 패턴 도입을 통한 확장성 개선 및 코드 정제
- author zbnerd; CLOSED; created 2025-12-18T15:27:49Z; closed 2025-12-19T13:54:11Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 1. **캐싱 로직의 파편화:** 현재 Caffeine Cache와 DB 캐싱 로직이 서비스 레이어나 리포지토리 곳곳에 산재해 있어, 데이터 소스 변경이나 캐싱 전략 수정 시 영향 범위가 넓음. 2. **강화 …
- discussion: 9 / **대상**: MaplestoryApiClient.java **위치**: 클래스 전체 및 호출 메서드 **이유**: 현재 WebClient를 이…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 1 [#40/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #46 — [Reliability] 외부 의존성 장애 격리를 위한 Circuit Breaker 도입
- author zbnerd; CLOSED; created 2025-12-19T16:07:45Z; closed 2025-12-22T19:33:00Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: 외부 API(Nexon) 장애 시 내부 스레드가 타임아웃까지 대기하며 커넥션 풀을 점유함. - **원인**: 외부 장애가 내부 서비스의 연쇄 장애(Cascading Failure)로 이어지는 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [44027ed3bf7a]; PR-record issue refs 1 [#54/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #47 — [Ops] 분산 환경에서의 스케줄러 중복 실행 방지 (Distributed Lock)
- author zbnerd; CLOSED; created 2025-12-19T16:08:44Z; closed 2025-12-23T16:25:18Z; reason COMPLETED. Body: # [Ops] 분산 환경에서의 스케줄러 중복 실행 방지 (Distributed Lock) #47 ### 📌 Problem Definition (문제 정의) - **현상**: 시스템 가용성 확보를 위해 서버를 스케일 아웃(Scale-out)할 경우, 각 인스턴스에 포함된…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [09cf532d9196]; PR-record issue refs 1 [#61/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #48 — [Stability] 대량 데이터 동기화 시 DB 락 경합 및 성능 최적화
- author zbnerd; CLOSED; created 2025-12-19T16:09:06Z; closed 2026-01-16T22:33:29Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) * **현상**: 버퍼에 쌓인 수만 건의 '좋아요' 데이터를 한 번에 `Bulk Update`할 때, MySQL InnoDB의 **Next-Key Lock(Gap Lock + Record Lock)**이 광범위…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [5a6823054b7a, baccd0a25234, c61a2617459f, ec32ac5a7452]; PR-record issue refs 1 [#189/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #49 — [Architecture/Reliability] 외부 API 의존성에 따른 SLA 정의 및 Timeout / Fail-fast 전략 수립
- author zbnerd; CLOSED; created 2025-12-19T21:02:47Z; closed 2025-12-22T21:59:46Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 MapleExpectation 서비스는 외부 서비스(Nexon Open API)에 의존하는 구조입니다. 이는 곧 외부 API의 지연, 장애, Rate Limit 초과가 내부 서비스 장애로 전파될 수 있는 *…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [410e0f757579, 44027ed3bf7a]; PR-record issue refs 2 [#50/MERGED, #51/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #56 — [QA] JaCoCo를 활용한 프로젝트 테스트 커버리지 분석 및 사각지대 해소
- author zbnerd; CLOSED; created 2025-12-22T20:33:10Z; closed 2026-01-17T01:49:27Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** 현재 다수의 테스트 코드가 작성되어 있으나, 전체 코드 중 어느 정도가 검증되었는지 객관적인 수치(Coverage)가 파악되지 않음. - **위험:** 테스트가 누락된 '사각지대'에서 버그가 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, bf6fa4cc4bfd]; PR-record issue refs 1 [#193/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #57 — [perf] JPA N+1 쿼리 잠재 구간 탐색 및 Fetch Join을 통한 성능 최적화
- author zbnerd; CLOSED; created 2025-12-22T20:34:15Z; closed 2026-01-02T08:17:42Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** JPA 연관 엔티티 조회 시 지연 로딩(Lazy Loading)으로 인한 N+1 쿼리 발생 가능성이 존재함. - **위험:** 100만 건 이상의 대규모 데이터 환경에서 수천 개의 추가 쿼리가…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [4dc6260a9593, 53004661e0f2, 5a6823054b7a, 612ed334a120, baccd0a25234]; PR-record issue refs 1 [#125/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #58 — [Resilience] 외부 API 장애 대응 시나리오 명세화 및 최종 검증
- author zbnerd; CLOSED; created 2025-12-22T22:05:41Z; closed 2025-12-23T18:44:20Z; reason COMPLETED. Body: ## 🔗 관련 이슈 - #49 ## 📌 Problem Definition (문제 정의) 현재 외부 API 장애에 대응하는 개별 로직들은 존재하나, 상황별(에러/지연) 및 리소스 상태(캐시 유무)에 따른 전체적인 방어 시나리오가 명문화되어 있지 않습니다. 이로 인해 장애…
- discussion: 1 / ## 💱 트레이드 오프 결정 근거 (Design Decisions) 1. **Isolation(격리)을 위한 비동기 경계 분리**: - 단순히 …. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [d1eaa1485f21]; PR-record issue refs 1 [#68/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #62 — [Architecture/Security] DI 순환 참조 해결 및 관리자 API 보안 강화
- author zbnerd; CLOSED; created 2025-12-23T15:06:21Z; closed 2025-12-23T16:48:59Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **순환 참조**: `@Primary`인 `CubeTrialsCachingProxy`가 자기 자신을 주입받아 무한 재귀 또는 빈 생성 오류 위험이 있음. - **보안 취약점**: 관리자 전용 테스트 알림 엔…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [005d4e95536f]; PR-record issue refs 1 [#65/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #63 — [Performance] V3 스트리밍 경로 최적화 및 캐시 표준화
- author zbnerd; CLOSED; created 2025-12-23T15:07:00Z; closed 2026-01-27T12:46:22Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **압축 효율 저하**: DB의 압축 데이터(Gzip)를 풀었다가 컨트롤러에서 다시 압축하는 불필요한 연산 발생. - **메모리 압박**: `ocidCache`, `trialsCache` 등에 `maximu…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [371377642885, 4518bde86a33, 5a6823054b7a, baccd0a25234, c61a2617459f]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #64 — [Refactor] DTO 네이밍 충돌 해결 및 운영 가이드(Runbook) 문서화 #64
- author zbnerd; CLOSED; created 2025-12-23T15:07:28Z; closed 2026-01-17T01:49:30Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **가독성 저하**: V1/V2 DTO명이 동일하여 유지보수 및 디버깅 시 혼선 유발. - **운영 공백**: `ExternalServiceException` 등 커스텀 장애 발생 시 운영자의 행동 지침이 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, bf6fa4cc4bfd]; PR-record issue refs 1 [#193/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #77 — [Infra] Redis 고가용성(HA) 아키텍처 구축 및 Redlock 알고리즘 검토
- author zbnerd; CLOSED; created 2025-12-26T22:36:26Z; closed 2026-01-03T06:37:06Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - [cite_start]현재 분산 환경에서 Redisson을 활용한 분산 락(Distributed Lock)을 사용 중이나, 단일 Redis 노드 장애 시 전체 시스템의 동시성 제어 메커니즘이 마비될 위험이 …
- discussion: 1 / ## ✅ 최종 결과 요약 Redis Sentinel을 활용한 고가용성(HA) 아키텍처 구축 및 테스트 안정화 작업을 완료하였습니다. ### 1.…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [1c15d13c4553, 287f7d6889d5, 4dc6260a9593, 5a6823054b7a, 612ed334a120, baccd0a25234]; PR-record issue refs 2 [#132/MERGED, #134/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #78 —  [Engineering] Redis 장애 격리(Fault Tolerance)를 위한 시나리오 기반 다중 락(Tiered Locking) 및 Fallback 전략…
- author zbnerd; CLOSED; created 2025-12-26T22:37:16Z; closed 2026-01-02T14:22:48Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** Redis 서버의 다운 또는 네트워크 순단 시, 분산 락을 사용하는 비즈니스 로직에서 스레드 차단(Blocking) 및 커넥션 타임아웃이 발생함. - **리스크:** 특정 기능의 장애가 전체 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [4dc6260a9593, 5a6823054b7a, 612ed334a120, baccd0a25234, d9acdb873da1]; PR-record issue refs 1 [#129/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #79 — [Durability] Redis 장애 장기화 대비 Graceful Shutdown 고도화 및 데이터 Flush 로직 강화
- author zbnerd; CLOSED; created 2025-12-26T22:38:00Z; closed 2026-01-02T06:04:24Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - Write-Behind 패턴 사용 시 Redis 장애로 DB 동기화가 지연되면 로컬 메모리(Caffeine Cache)에 미반영 데이터가 장시간 체류하게 됨. - 이 상태에서 서버 재시작이나 예기치 못한 종…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [57d359c99c0b]; PR-record issue refs 1 [#121/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #80 — [Reliability] 후원(커피 사주기) 기능의 트랜잭션 무결성을 위한 Transactional Outbox 패턴 도입
- author zbnerd; CLOSED; created 2025-12-26T22:46:28Z; closed 2026-01-16T10:06:46Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - 후원 기능 수행 시 DB에 결제 정보를 저장하고 외부 시스템(Discord 알림, 이메일, 포인트 서버 등)에 이벤트를 발행해야 함. - 이때 DB 저장(Commit)은 성공했으나 네트워크 장애 등으로 외부…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [ecd5f53b1418]; PR-record issue refs 2 [#187/MERGED, #188/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #81 — [Optimization] 동시성 성능 향상을 위한 트랜잭션 격리 수준(Isolation Level) 조정 및 분석 (RR -> RC)
- author zbnerd; CLOSED; created 2025-12-26T22:47:10Z; closed 2026-01-16T10:06:46Z; reason COMPLETED. Body:  ### 📌 Problem Definition (문제 정의) - [cite_start]MySQL(InnoDB)의 기본 격리 수준인 **Repeatable Read(RR)**는 갭 락(Gap Lock)을 사용하여 데이터 일관성을 높게 유지하지만, 후원이나 '좋아요'처럼 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [5a6823054b7a, baccd0a25234, c61a2617459f, ecd5f53b1418]; PR-record issue refs 2 [#187/MERGED, #188/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #82 — [Engineering] 분산 락 Lease Time 만료에 따른 Race Condition 방어 설계
- author zbnerd; CLOSED; created 2025-12-27T21:38:02Z; closed 2025-12-30T05:07:47Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상:** 비즈니스 로직 수행 시간이 설정된 분산 락의 `Lease Time`(임계 시간)보다 길어질 경우, 로직이 완료되지 않았음에도 Redis에서 락이 자동 해제됨. - **리스크:** 락이 해제된 …
- discussion: 1 / 이슈 #113 작업을 통해 본 이슈의 근본 원인이 해결되었으므로 본 이슈를 Close 함.. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #91 — [Architecture/Test] Testcontainers 기반 인프라 통합 테스트 자동화 및 장애 대응(Resilience) 검증
- author zbnerd; CLOSED; created 2025-12-28T09:31:33Z; closed 2026-01-03T11:11:32Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - 현재 L1/L2 캐시, 분산 락, 비동기 파이프라인에 대한 검증이 로컬 인프라(Redis, MySQL) 수동 구동 및 Postman 호출에 의존하고 있음. - 네트워크 지연(Latency)이나 연결 끊김과 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #98 — [ISSUE] V3 다중 계층 캐시 환경의 분산 락 병목 현상 해결 및 성능 최적화
- author zbnerd; CLOSED; created 2025-12-29T13:34:11Z; closed 2025-12-29T23:34:50Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: 500명 동시 접속 부하 테스트 시, V3 엔진에서 `S002 치명적인 트랜잭션 오류: 락 획득 타임아웃` 발생. - **원인 분석**: 1. **Lock Contention (락 경합)**:…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [2cf66873c128, 33ff205be157, 5e5a14c9ef4f, bb554858bfde, f416ea19a312]; PR-record issue refs 8 [#99/MERGED, #100/MERGED, #101/MERGED, #102/MERGED, #103/MERGED, #104/MERGED, #105/MERGED, #111/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #113 — perf: Redis Pub/Sub 기반 요청 결합(Request Collapsing) 도입 및 Negative Caching 고도화
- author zbnerd; CLOSED; created 2025-12-29T23:35:57Z; closed 2025-12-30T04:07:21Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - 고부하 환경(240~500 RPS)에서 동일 캐릭터에 대한 중복 API 호출이 발생하여 넥슨 API의 **429 (Too Many Requests)** 에러가 빈번하게 발생함. - 현재의 분산 락 기반 대기…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [4c3365bb4d7e, 57880f64deaf]; PR-record issue refs 1 [#114/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #118 — [Refactor] 비동기 파이프라인 전환 및 .join() 제거 (#49, #58 준수)
- author zbnerd; CLOSED; created 2026-01-02T02:14:35Z; closed 2026-01-13T06:39:17Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 `ResilientNexonApiClient` 및 `EquipmentService`는 이슈 #49(SLA)와 #58(장애 대응 시나리오)을 구현하는 과정에서 `CompletableFuture.join()`을…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 7 [10b37b1b42df, 5a6823054b7a, 807b487c3a27, 8458164f2b1a, baccd0a25234, c61a2617459f, e29e36ae7d3d]; PR-record issue refs 3 [#160/MERGED, #177/CLOSED, #178/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #119 — [Refactor] 순환 참조 제거 및 좋아요 도메인 로직 분리 (SRP/DIP 준수)
- author zbnerd; CLOSED; created 2026-01-02T02:16:13Z; closed 2026-01-16T22:33:28Z; reason COMPLETED. Body: # [Refactor] 순환 참조 제거 및 좋아요 도메인 로직 분리 (SRP/DIP 준수) ## 📌 Problem Definition (문제 정의) - `GameCharacterService`와 `DatabaseLikeProcessor`가 서로를 참조하며 `@Lazy`…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [5a6823054b7a, baccd0a25234, c61a2617459f, ec32ac5a7452]; PR-record issue refs 1 [#189/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #120 — [Refactor] Rich Domain Model 전환: 서비스 중심에서 엔티티 중심으로 비즈니스 로직 이동
- author zbnerd; CLOSED; created 2026-01-02T02:17:28Z; closed 2026-01-27T12:46:15Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) - 현재 엔티티(`GameCharacter`, `CharacterEquipment` 등)가 상태 값만 가지는 데이터 바구니(Anemic Domain Model) 역할에 그치고 있습니다. - 이로 인해 `Equip…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [371377642885, 4518bde86a33]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #123 — [Bug/Durability] 장애 상황 내 2차 장애 대응을 위한 데이터 보호 로직 보강
- author zbnerd; CLOSED; created 2026-01-02T06:29:50Z; closed 2026-01-02T07:02:02Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 이슈 #79를 통해 고가용성 셧다운 로직을 구현했으나, Codex 리뷰 결과 **'장애 처리 중 또 다른 장애가 발생할 경우'** 데이터가 유실될 수 있는 엣지 케이스 2곳이 발견되었습니다. 1. **Redis …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [4dc6260a9593, 5a6823054b7a, 612ed334a120, baccd0a25234, efd37c6908fe]; PR-record issue refs 1 [#124/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #126 — [Architecture] Pragmatic CQRS: 조회/처리 서버 분리 및 이벤트 기반 비동기 파이프라인 구축
- author zbnerd; CLOSED; created 2026-01-02T09:34:13Z; closed 2026-03-26T07:43:28Z; reason COMPLETED. Body: ## [Architecture] Pragmatic CQRS: 조회/처리 서버 분리 및 이벤트 기반 비동기 파이프라인 구축 **Labels:** `architecture`, `scalability`, `priority:high`, `milestone:v2.0` ### 📌…
- discussion: 1 / CQRS 도입을 고려했으나, 현재 트래픽 대비 복잡도 비용이 높아 보류함. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #127 — [Reliability] 데이터 복구 로직의 멱등성(Idempotency) 확보 및 부분 성공 처리 개선
- author zbnerd; CLOSED; created 2026-01-02T10:04:28Z; closed 2026-01-16T10:06:48Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: `recoverLikeBuffer` 로직에서 파일 내 여러 엔트리를 처리하던 중 일부만 성공하고 예외가 발생할 경우, 파일이 아카이브되지 않고 그대로 남음. - **리스크**: 시스템 재시작 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [ecd5f53b1418]; PR-record issue refs 2 [#187/MERGED, #188/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #128 — [Performance] 엔티티-DTO 분리를 통한 API 응답 페이로드 최적화 및 데이터 노출 방지
- author zbnerd; CLOSED; created 2026-01-02T10:06:16Z; closed 2026-01-27T12:46:19Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - **현상**: `GameCharacter` 엔티티가 `CharacterEquipment`와 연관 관계를 맺으면서, API 반환 시 Jackson에 의해 장비의 원본 JSON 블롭(`jsonContent`)까…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [371377642885, 4518bde86a33]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #130 — [Bug] Tiered Locking 정합성 결함 및 MySQL 세션 락 메커니즘 오류 수정 (#78 후속)
- author zbnerd; CLOSED; created 2026-01-02T11:04:18Z; closed 2026-01-08T12:39:13Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 1. **MySQL 세션 락 고립**: MySQL Named Lock은 커넥션(세션)에 종속적입니다. 현재 `JdbcTemplate`을 사용하여 획득(`GET_LOCK`)과 해제(`RELEASE_LOCK`)를 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [26145977521a, 5a6823054b7a, 70a11f600e91, baccd0a25234, c61a2617459f]; PR-record issue refs 1 [#157/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #131 — [Refactor] 전역 로직 실행 템플릿 도입을 통한 보일러플레이트 제거 및 예외 처리 구조화
- author zbnerd; CLOSED; created 2026-01-03T02:45:14Z; closed 2026-01-04T10:46:13Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) * **기술적 노이즈 과다**: `Jackson`, `GZIP`, `AOP`, `Lock` 로직 전반에 걸쳐 `try-catch` 블록과 `throws Throwable`이 산재하여 비즈니스 로직의 가독성을 저…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [5a6823054b7a, baccd0a25234, c61e0fa0933b]; PR-record issue refs 3 [#140/MERGED, #141/MERGED, #1316/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #133 — [Performance] 테스트 초기화(Instantiating tests) 지연 해결 및 테스트 구조 리팩토링
- author zbnerd; CLOSED; created 2026-01-03T05:50:28Z; closed 2026-01-03T09:43:09Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) - IntelliJ에서 JUnit 5 테스트 실행 시 "Instantiating tests..." 단계에서 수 십초에서 수 분 이상의 지연 발생. - Spring Boot의 `ApplicationContext`…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [03d0e7eda6d6, 4dc6260a9593, 5a6823054b7a, 612ed334a120, baccd0a25234]; PR-record issue refs 1 [#135/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #138 — [Design] 메트릭 카디널리티 제어 및 관측성 어스펙트 고도화 (TaskContext 도입)
- author zbnerd; CLOSED; created 2026-01-03T23:02:47Z; closed 2026-01-17T00:55:17Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 1. **메트릭 카디널리티 폭발 위험**: 현재 `taskName`에 유저 ID나 캐릭터 명 등 동적 값이 포함되어 Prometheus 시계열 데이터가 무한히 증폭될 위험이 있음. 2. **관측성 로직의 중복*…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [468525c053c6, 5a6823054b7a, 77b56797acf3, baccd0a25234, c61a2617459f, e84f55a015f5]; PR-record issue refs 1 [#192/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #139 — ​[Refactor] 큐브 기대값 엔진 고도화: DP 기반 누적 확률(Tail Probability) 연산 도입
- author zbnerd; CLOSED; created 2026-01-04T05:44:08Z; closed 2026-01-09T08:38:33Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) * **비즈니스 로직 구현의 복잡성 (핵심):** 현재의 순열(Permutation) 방식은 '정확히 일치(Exact Match)'하는 확률만 계산하기에 적합함. 그러나 유저가 원하는 "21% 이상"과 같은 누…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [16ed3af3a5e8, 5a6823054b7a, baccd0a25234, c61a2617459f]; PR-record issue refs 1 [#159/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 설계 case.

### Issue #142 — [Issue] LogicExecutor 아키텍처 고도화: 런타임 승격 및 실행 정책 단일화
- author zbnerd; CLOSED; created 2026-01-04T11:52:50Z; closed 2026-01-07T14:44:33Z; reason COMPLETED. Body:  > 목적: #131에서 구축한 “전역 실행 템플릿(LogicExecutor)”의 철학(노이즈 제거, 정책 중앙화)을 유지하면서 > 1) 런타임 예외 승격을 명확히 하고, 2) IO 경계(Checked)와 일반 서비스(Runtime)를 분리하며, > 3) execute…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [5a5072945995, 5a6823054b7a, baccd0a25234]; PR-record issue refs 1 [#144/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #143 — # [DevOps] 관측성(Observability) 확보를 위한 모니터링 인프라 구축 (Loki + Grafana + Tracing)
- author zbnerd; CLOSED; created 2026-01-07T04:21:42Z; closed 2026-01-18T08:53:28Z; reason COMPLETED. Body:  **Labels:** `devops`, `infrastructure`, `monitoring`, `priority:high` ### 📌 Problem Definition (문제 정의) 현재 단일 서버 환경에서 로그를 `Slf4j` 또는 로컬 파일로만 관리하고 있어, …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [468525c053c6, 5a6823054b7a, 77b56797acf3, baccd0a25234, c61a2617459f, e6655efb1c0d]; PR-record issue refs 2 [#206/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #145 — [P0] Nexon WebClient 무한 대기 방지 및 Timeout 강제 (Scenario C)
- author zbnerd; CLOSED; created 2026-01-07T13:38:51Z; closed 2026-01-08T10:30:36Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) **외부 시스템(Nexon API)의 응답 지연이 내부 시스템의 전체 장애(System-wide Failure)로 전파되는 구조적 취약점이 존재함.** - 현재 `WebClient`/`RestTemplate` …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [5a6823054b7a, baccd0a25234, c61a2617459f]; PR-record issue refs 1 [#156/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #146 — [P0] Admin/핵심 API 인증·인가 최소선 구축
- author zbnerd; CLOSED; created 2026-01-07T13:39:13Z; closed 2026-01-12T15:38:31Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) **관리자 권한 및 핵심 비즈니스 로직에 대한 접근 제어(Access Control)가 부재하여 시스템이 무방비 상태임.** - **현상:** `/api/admin/**` (관리자 페이지) 및 `/api/don…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [5a6823054b7a, baccd0a25234, c61a2617459f, cdf6a7167144]; PR-record issue refs 1 [#165/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #147 — [P0] LikeSyncService 데이터 유실 방지 (Redis 원자성)
- author zbnerd; CLOSED; created 2026-01-07T13:39:31Z; closed 2026-01-12T23:08:03Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) **Redis의 데이터를 DB로 이관(Sync)하는 과정이 원자적(Atomic)이지 않아, 고트래픽 상황에서 데이터 유실(Data Loss)이 발생함.** - **현상:** `LikeSyncScheduler`가…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [18141cd99750, 5a6823054b7a, 720ef59898c0, baccd0a25234, c61a2617459f]; PR-record issue refs 3 [#164/MERGED, #174/CLOSED, #175/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #148 — [P0] TieredCache Race Condition 제거 (L1/L2 일관성)
- author zbnerd; CLOSED; created 2026-01-07T13:39:55Z; closed 2026-01-11T15:59:04Z; reason COMPLETED. Body: --- labels: ["P0", "Cache", "Consistency", "Performance", "EPIC-C"] assignees: [] --- ### 📌 Problem Definition (문제 정의) **Multi-Layer Cache(L1: Local, …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [18141cd99750, 5a6823054b7a, 720ef59898c0, 9060ab10deb6, baccd0a25234, c61a2617459f]; PR-record issue refs 1 [#163/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #151 — [P0/P1] 입력값 검증(Validation) 전면 적용
- author zbnerd; CLOSED; created 2026-01-07T13:41:24Z; closed 2026-01-13T10:45:23Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) User Input에 대한 검증이 비즈니스 로직에 섞여있거나 누락되어, 잘못된 데이터가 DB까지 도달하거나 예외 처리가 파편화됨. - 데이터 오염, 500 에러 남발, SQL Injection 등 잠재적 보안 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [0725b90e17d2, 5a6823054b7a, baccd0a25234, c61a2617459f]; PR-record issue refs 1 [#181/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #152 — [P1] Rate Limiting 도입
- author zbnerd; CLOSED; created 2026-01-07T13:42:13Z; closed 2026-01-17T00:55:16Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) API 요청 횟수 제한이 없어, 특정 IP나 유저가 과도한 트래픽을 유발할 경우 서비스 거부(DoS) 상태가 될 수 있음. - 서비스 가용성 저하, 클라우드 비용 증가 위험 ### 🎯 Goal (목표) - 주요…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [468525c053c6, 5a6823054b7a, 77b56797acf3, baccd0a25234, c61a2617459f, e84f55a015f5]; PR-record issue refs 1 [#192/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #153 — [P1] CI/CD Quality Gate (Test Skip 금지)
- author zbnerd; CLOSED; created 2026-01-07T13:44:07Z; closed 2026-01-17T00:55:17Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 테스트가 실패해도 배포가 가능하거나, 바쁠 때 `-x test` 옵션으로 테스트를 건너뛰고 배포하는 관행이 가능함. - 검증되지 않은 코드가 운영 환경에 배포되어 장애 유발 위험 ### 🎯 Goal (목표) -…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 6 [468525c053c6, 5a6823054b7a, 77b56797acf3, baccd0a25234, c61a2617459f, e84f55a015f5]; PR-record issue refs 1 [#192/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #158 — [P0] Expectation API 캐시 타겟 전환: EquipmentResponse 저장 중단 + TotalExpectationResponse 결과 캐싱
- author zbnerd; CLOSED; created 2026-01-08T15:31:02Z; closed 2026-01-10T10:04:28Z; reason COMPLETED. Body: **Labels:** `P0` `performance` `redis` `cache` `backend` `cost` `refactor` --- ### 📌 Problem Definition (문제 정의) 현재 `/api/v3/characters/{userIgn}/expec…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [29fe7d4375db, 7d9fa90c1805, b93d3a18f2ec, e29e36ae7d3d]; PR-record issue refs 1 [#160/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #166 — [P0/Critical] NexonDataCacheAspect 예외 변환 체계 수정 - CompletionException 원본 타입 손실
- author zbnerd; CLOSED; created 2026-01-12T17:12:56Z; closed 2026-01-13T05:16:44Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `NexonDataCacheAspect.toRuntimeException()` 메서드에서 checked exception을 `CompletionException`으로 감싸면서 **원본 예외 타입이 손실**됩니다…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [5ec7f7c4ce68]; PR-record issue refs 1 [#179/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #167 — [P1/Major] NexonDataCacheAspect 람다 중첩 제거 - 괄호 지옥 해결
- author zbnerd; CLOSED; created 2026-01-12T17:12:57Z; closed 2026-01-12T17:21:33Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `NexonDataCacheAspect`에서 3단계 이상의 람다 중첩이 발생하여 **가독성 저하** 및 **유지보수 어려움**이 있습니다. **위치:** `src/main/java/maple/expectatio…
- discussion: 1 / 서비스 돌아가는데 치명적이지는 않기에 이슈 닫음. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #168 — [P0/Critical] ExecutorConfig CallerRunsPolicy 극한 부하 대응 - 톰캣 스레드 고갈 위험
- author zbnerd; CLOSED; created 2026-01-12T17:12:59Z; closed 2026-01-12T23:35:14Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `expectationComputeExecutor`의 `CallerRunsPolicy` 설정으로 인해 **극한 부하 시 톰캣 스레드가 블로킹**되어 전체 시스템이 응답 불가 상태가 될 수 있습니다. **위치:*…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [061fadc46f0e]; PR-record issue refs 1 [#176/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #169 — [P1/Major] GameCharacterFacade 예외 분류 수정 - TimeoutException을 5xx로 처리하는 문제
- author zbnerd; CLOSED; created 2026-01-12T17:13:00Z; closed 2026-01-13T09:41:16Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `GameCharacterFacade.awaitFuture()` 메서드에서 `TimeoutException`을 **5xx 서버 오류**로 분류하여 **서킷브레이커 오동작**을 유발합니다. **위치:** `src…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [c033044e9bbf]; PR-record issue refs 1 [#180/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #170 — [P1/Major] JwtAuthenticationFilter Redis Timeout 설정 - 인증 시스템 장애 격리
- author zbnerd; CLOSED; created 2026-01-12T17:14:19Z; closed 2026-01-13T10:45:15Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `JwtAuthenticationFilter`에서 `sessionService.getSessionAndRefresh()` 호출 시 **Redis timeout이 설정되지 않아** Redis 장애 시 전체 인증 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0725b90e17d2]; PR-record issue refs 1 [#181/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #171 — [P1/Major] LockHikariConfig MaximumPoolSize 설정 수정 - 주석과 실제값 불일치
- author zbnerd; CLOSED; created 2026-01-12T17:14:20Z; closed 2026-01-16T22:33:27Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `LockHikariConfig`에서 `MaximumPoolSize(50)`가 설정되어 있으나, 주석에는 **"작은 풀(10)"**로 명시되어 있어 **설정과 의도가 불일치**합니다. **위치:** `src/m…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [ec32ac5a7452]; PR-record issue refs 1 [#189/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #172 — [P1/Major] SecurityConfig CORS 와일드카드 제거 - 프로덕션 보안 강화
- author zbnerd; CLOSED; created 2026-01-12T17:14:21Z; closed 2026-01-13T10:45:16Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `SecurityConfig`에서 CORS 설정이 **와일드카드(*)**로 되어 있어 **모든 오리진이 허용**됩니다. **위치:** `src/main/java/maple/expectation/config/Se…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [0725b90e17d2]; PR-record issue refs 1 [#181/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 결함·보안 case.

### Issue #173 — [P1/Major] EquipmentDataResolver 타임아웃 처리 강화 - orTimeout race condition 해결
- author zbnerd; CLOSED; created 2026-01-12T17:14:23Z; closed 2026-01-13T09:41:17Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) `EquipmentDataResolver.fetchFromNexonApiAndSave()`에서 `orTimeout()`이 **전체 체인에 적용**되어 API 응답 후 처리(decompress, DB 저장)까지 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 1 [c033044e9bbf]; PR-record issue refs 1 [#180/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #194 — [QA] 낮은 커버리지 영역 테스트 보강 (53% → 90% 목표)
- author zbnerd; CLOSED; created 2026-01-17T01:51:36Z; closed 2026-01-27T12:46:25Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) JaCoCo 분석 결과 전체 커버리지 **53%** (Instructions), **41%** (Branches)로 확인됨. 특히 Security, Cache, Controller 등 핵심 영역의 커버리지가 30…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [371377642885, 4518bde86a33]; PR-record issue refs 1 [#274/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #195 — [P0] Blocking Call (.block()/.join()) on Tomcat Thread 제거
- author zbnerd; CLOSED; created 2026-01-17T04:10:38Z; closed 2026-01-17T10:22:03Z; reason COMPLETED. Body: ## 문제 설명 현재 코드베이스에서 `.block()` 또는 `.join()` 호출이 Tomcat 스레드에서 실행되어 스레드 고갈 위험이 있습니다. ## 발견된 위치 ### 1. RealNexonApiClient.java:39 ```java public Characte…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [319c23cd4e9b, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#199/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #196 — [P1] WebClient Timeout 설정 누락
- author zbnerd; CLOSED; created 2026-01-17T04:10:54Z; closed 2026-01-17T10:22:03Z; reason COMPLETED. Body: ## 문제 설명 일부 WebClient 호출에서 timeout 설정이 누락되어 외부 서비스 장애 시 무한 대기 위험이 있습니다. ## 발견된 위치 ### 1. RealNexonApiClient.java:39 ```java .bodyToMono(CharacterOcidR…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [319c23cd4e9b, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#199/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #197 — [P1] CubeCostPolicy 입력값 검증 누락
- author zbnerd; CLOSED; created 2026-01-17T04:11:11Z; closed 2026-01-17T10:22:04Z; reason COMPLETED. Body: ## 문제 설명 `CubeCostPolicy.getCubeCost()` 메서드에서 입력값(grade) 검증이 누락되어 잘못된 값 입력 시 0L을 반환합니다. ## 발견된 위치 ### CubeCostPolicy.java:37-46 ```java public long ge…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [319c23cd4e9b, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#199/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #198 — [P1] @Transactional Isolation Level 명시적 지정 필요
- author zbnerd; CLOSED; created 2026-01-17T04:11:30Z; closed 2026-01-17T10:22:04Z; reason COMPLETED. Body: ## 문제 설명 금융성 거래(포인트 이동)를 처리하는 `DonationService.sendCoffee()`에서 `@Transactional`의 isolation level이 명시되지 않아 DB 기본값에 의존합니다. ## 발견된 위치 ### DonationService…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [319c23cd4e9b, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#199/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #200 — [Flaky Test] EquipmentServiceTest.getEquipmentByUserIgnAsync_NonBlocking 타이밍 의존성 제거
- author zbnerd; CLOSED; created 2026-01-17T08:12:05Z; closed 2026-01-17T10:21:30Z; reason COMPLETED. Body: ## 🐛 문제 설명 `EquipmentServiceTest.getEquipmentByUserIgnAsync_NonBlocking()` 테스트가 간헐적으로 실패합니다. ## 📍 위치 `src/test/java/maple/expectation/service/v2/Equip…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, ef28c94cc6f1]; PR-record issue refs 1 [#203/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #201 — [Flaky Test] ShutdownDataRecoveryIntegrationTest Redis/파일시스템 격리 강화
- author zbnerd; CLOSED; created 2026-01-17T08:12:06Z; closed 2026-01-17T10:21:25Z; reason COMPLETED. Body: ## 🐛 문제 설명 `ShutdownDataRecoveryIntegrationTest`의 여러 테스트 메서드가 간헐적으로 실패합니다. ## 📍 위치 `src/test/java/maple/expectation/service/v2/shutdown/ShutdownDataRe…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, f5b324a0e44e]; PR-record issue refs 1 [#204/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #202 — [Flaky Test] ResilientNexonApiClientTest.retryLogicTest Resilience4j 비동기 호환성
- author zbnerd; CLOSED; created 2026-01-17T08:12:07Z; closed 2026-01-17T10:21:21Z; reason COMPLETED. Body: ## 🐛 문제 설명 `ResilientNexonApiClientTest.retryLogicTest()` 테스트가 Issue #195 (CompletableFuture 전환) 이후 간헐적으로 실패할 수 있습니다. ## 📍 위치 `src/test/java/maple/exp…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 6b137f041211, 77b56797acf3]; PR-record issue refs 1 [#205/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #207 — [CI] 통합테스트 10분+ 장기화 및 플래키 테스트 지속으로 인한 파이프라인 신뢰도/속도 저하
- author zbnerd; CLOSED; created 2026-01-17T18:34:16Z; closed 2026-01-18T06:43:26Z; reason COMPLETED. Body: # 🧩 통합테스트 10분+ / 플래키 지속 이슈 정의 > **Label (필수):** `test-reliability` `ci-performance` `integration-test` `flaky-test` `tech-debt` --- ## 📌 Problem Defin…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [468525c053c6, 56ba266d8292, 77b56797acf3, 7dcfbed60594, c6b857b79155]; PR-record issue refs 4 [#211/MERGED, #243/MERGED, #326/MERGED, #327/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #208 — [Performance] DB 성능 최적화를 위한 InnoDB Buffer Pool 튜닝 및 커버링 인덱스 적용
- author zbnerd; CLOSED; created 2026-01-18T02:47:52Z; closed 2026-01-21T06:58:07Z; reason COMPLETED. Body:  **Labels:** `performance`, `database`, `optimization` ## 📌 Problem Definition (문제 정의) 현재 프로젝트(`MapleExpectation`)는 **InnoDB 스토리지 엔진**을 사용 중이나, MySQL …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, f5dda8640ed7]; PR-record issue refs 2 [#238/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 성능 case.

### Issue #209 — [Observability] MySQL Slow Query Log 활성화 및 Loki+Grafana 연동을 통한 쿼리 병목 시각화
- author zbnerd; CLOSED; created 2026-01-18T03:08:57Z; closed 2026-01-18T08:39:43Z; reason COMPLETED. Body:  **Labels:** `observability`, `database`, `monitoring`, `performance` ## 📌 Problem Definition (문제 정의) 현재 DB에 인덱싱(`1m_db_indexing.sql` 등)은 적용되어 있으나, 실제…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [468525c053c6, 577d408d66d8, 5fae12cf0546, 77b56797acf3, f8f6a166f346]; PR-record issue refs 5 [#213/MERGED, #214/MERGED, #217/MERGED, #224/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #210 — # [test-reliability]] Flaky Test 누적 추적/로그화 및 Quarantine 운영 체계 구축
- author zbnerd; CLOSED; created 2026-01-18T04:55:28Z; closed 2026-01-18T08:02:53Z; reason COMPLETED. Body:  ## 📌 Problem Definition (문제 정의) Spring Boot + JUnit + Gradle 환경에서 테스트가 **랜덤하게(실행마다 1~4개) 실패**하는 플래키 현상이 지속되고 있다. 현재는 실패 시 재실행/재시도로 임시 대응하거나 원인 분석이 지연…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 54086f231624, 77b56797acf3]; PR-record issue refs 2 [#212/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #215 — [Infra] Migrate Database from EC2 Docker to AWS RDS
- author zbnerd; CLOSED; created 2026-01-18T09:15:01Z; closed 2026-02-13T09:07:45Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 `MapleExpectation` 서비스의 데이터베이스(MySQL)가 WAS(Spring Boot)와 동일한 EC2 인스턴스 내에서 Docker Container로 구동되고 있음. 이로 인해 다음과 같은 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #216 — [Infra] Migrate Monitoring Stack to Grafana Cloud (SaaS)
- author zbnerd; CLOSED; created 2026-01-18T09:17:20Z; closed 2026-03-05T05:50:55Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 모니터링 스택(Loki, Grafana)이 운영 서버(EC2) 내에서 Docker Container로 함께 구동되고 있음. 이로 인해 **"서버가 죽으면 로그도 같이 사라지는"** 치명적인 결함(SPOF)…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 운영 case.

### Issue #218 — feat: MySQL 장애 시 Resilience 강화 - Dynamic TTL + Nexon API Fallback + Compensation Log
- author zbnerd; CLOSED; created 2026-01-19T10:08:42Z; closed 2026-01-27T08:06:41Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) MySQL 장애 발생 시 현재 시스템은 **Fail Fast**로 빠르게 실패하지만, 다음 두 가지 개선이 필요합니다: ### 문제 1: Redis 캐시 TTL 고갈 - MySQL 장애 중 Redis TTL이 만…
- discussion: 1 / ## 🟢 Green Agent's Performance Review (성능 리뷰) ### 🛑 치명적 성능 이슈 발견 **문제의 코드:** ```…. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 5 [2378494238fc, 371377642885, 468525c053c6, 4e979f2b5583, 77b56797acf3]; PR-record issue refs 2 [#243/MERGED, #273/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #219 — feat: Cache Stampede 방지를 위한 PER(Probabilistic Early Recomputation) 알고리즘 도입
- author zbnerd; CLOSED; created 2026-01-19T10:44:32Z; closed 2026-01-21T06:58:05Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 시스템은 Redis 캐시 만료(TTL) 시점(`T_expiry`)에 수천 개의 요청이 동시에 몰리는 **Cache Stampede(Thundering Herd)** 현상이 발생할 위험이 있습니다. - **현…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, f5dda8640ed7]; PR-record issue refs 2 [#238/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #221 — [P0][Nightmare-02] Lock Ordering 미적용으로 인한 Deadlock 발생
- author zbnerd; CLOSED; created 2026-01-19T11:24:15Z; closed 2026-01-20T09:03:40Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 02 테스트에서 두 트랜잭션이 서로 다른 순서로 테이블 락을 획득하여 **Deadlock이 100% 발생**함. ### 테스트 결과 ``` 교차 락 획득 시 Deadlock 발생 여부 검증 FA…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [02ef79ebf70b, 468525c053c6, 61e08be3a34e, 77b56797acf3]; PR-record issue refs 3 [#223/MERGED, #236/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #222 — [P1][Nightmare-03] CallerRunsPolicy로 인한 메인 스레드 블로킹 발생
- author zbnerd; CLOSED; created 2026-01-19T11:24:18Z; closed 2026-01-21T02:57:03Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 03 테스트에서 `@Async` Thread Pool이 포화될 때 **CallerRunsPolicy가 메인 스레드를 블로킹**하여 작업 제출 시간이 급증함. ### 테스트 결과 ``` Calle…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [02ef79ebf70b, 345f2f998a92, 468525c053c6, 77b56797acf3]; PR-record issue refs 3 [#223/MERGED, #237/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #225 — [P1][Nightmare-06] 타임아웃 계층 불일치로 인한 Zombie Request 발생
- author zbnerd; CLOSED; created 2026-01-19T12:23:18Z; closed 2026-01-21T02:57:04Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 클라이언트 타임아웃(3s)이 서버 처리 체인(17s+)보다 짧아 **Zombie Request**가 발생하고 리소스가 낭비됩니다. ### 타임아웃 계층 현황 ``` Client Timeout: 3s (또는 10s…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [345f2f998a92, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#237/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #226 — [P1][Nightmare-04] @Transactional 내 외부 API 블로킹 호출로 인한 Connection Pool 고갈 위험
- author zbnerd; CLOSED; created 2026-01-19T12:24:01Z; closed 2026-01-21T02:57:05Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) `GameCharacterService.createNewCharacter()` 메서드가 `@Transactional` 범위 내에서 외부 API를 블로킹 호출(`.join()`)하여 **최대 28초 동안 DB 커넥…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [345f2f998a92, 468525c053c6, 77b56797acf3]; PR-record issue refs 2 [#237/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #227 — [P0][Nightmare-07] Production DDL 실행 시 Metadata Lock으로 전체 쿼리 블로킹
- author zbnerd; CLOSED; created 2026-01-19T21:12:23Z; closed 2026-01-20T09:03:55Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 07 테스트에서 **ALTER TABLE 실행 시 후속 SELECT 쿼리가 블로킹**되는 Metadata Lock Freeze 현상이 발생함. ### 테스트 결과 (2025-01-20) 테스트 …
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 61e08be3a34e, 77b56797acf3]; PR-record issue refs 2 [#232/MERGED, #236/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #228 — [P0][Nightmare-09] Named Lock 역순 획득으로 인한 Circular Deadlock 발생
- author zbnerd; CLOSED; created 2026-01-19T21:12:25Z; closed 2026-01-20T09:04:10Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 09 테스트에서 **MySqlNamedLockStrategy에서 역순 락 획득 시 Deadlock**이 발생함. ### 테스트 결과 (2025-01-20) 테스트 메서드 결과 설명 -------…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 61e08be3a34e, 77b56797acf3]; PR-record issue refs 2 [#232/MERGED, #236/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 품질 case.

### Issue #229 — [P1][Nightmare-13] Outbox Zombie 복구 시 데이터 무결성 보장 필요
- author zbnerd; CLOSED; created 2026-01-19T21:12:26Z; closed 2026-01-21T06:58:03Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 13 테스트에서 **JVM 크래시로 인한 Zombie Outbox 복구 시 데이터 무결성 검증 실패**. ### 테스트 결과 (2025-01-20) 테스트 메서드 결과 설명 -----------…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, f5dda8640ed7]; PR-record issue refs 3 [#232/MERGED, #238/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #230 — [P1][Nightmare-14] LogicExecutor.execute() 예외 전파 동작 검증 필요
- author zbnerd; CLOSED; created 2026-01-19T21:12:28Z; closed 2026-01-21T06:58:02Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 14 테스트에서 **LogicExecutor.execute() 패턴의 예외 전파가 예상과 다르게 동작**. ### 테스트 결과 (2025-01-20) 테스트 메서드 결과 설명 ----------…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 77b56797acf3, f5dda8640ed7]; PR-record issue refs 3 [#232/MERGED, #238/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #231 — [P2][Nightmare-17] OutboxProcessor의 ContentHash 검증 및 DLQ 이동 로직 점검 필요
- author zbnerd; CLOSED; created 2026-01-19T21:12:29Z; closed 2026-01-21T13:46:25Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) Nightmare 17 테스트에서 **Payload 변조 시 자동 DLQ 이동이 예상대로 동작하지 않음**. ### 테스트 결과 (2025-01-20) 테스트 메서드 결과 설명 ------------- -----…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 3 [468525c053c6, 628636a7418e, 77b56797acf3]; PR-record issue refs 2 [#232/MERGED, #239/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #233 — [P1][Nightmare-18] Deep Paging 성능 개선 - Cursor-based Pagination 도입
- author zbnerd; CLOSED; created 2026-01-20T01:01:46Z; closed 2026-01-21T06:58:04Z; reason COMPLETED. Body: ## 📌 문제 정의 Load Test (750 users, 5분) 결과 N18 Deep Paging 엔드포인트에서 성능 문제와 500 에러가 확인됨. ### 증상 - **500 에러**: 109건 발생 (전체 에러의 0.3%) - **응답시간 증가**: page_100…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 4 [468525c053c6, 5722f1fc7a59, 77b56797acf3, f5dda8640ed7]; PR-record issue refs 3 [#234/MERGED, #238/MERGED, #243/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #240 — [Refactor] 장비 데이터 ETL 파이프라인 구축 및 Gzip 압축 스토리지 최적화
- author zbnerd; CLOSED; created 2026-01-21T09:12:18Z; closed 2026-01-21T20:04:46Z; reason COMPLETED. Body: ## 📌 배경 및 문제 정의 (Context) 현재 시스템은 Nexon API로부터 받은 장비 데이터(Raw JSON, 200~300KB)를 그대로 압축 저장하려 했으나, 다음과 같은 문제가 있습니다. 1. **리소스 낭비:** Raw Data에는 '기대값 계산'과 무…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 12 [271f312a2a0b, 2eaaeb879576, 468525c053c6, 495bdcca5848, 57c86fbfa54e, 628636a7418e, 77b56797acf3, 7f9c01f44b27, 929e270ceddc, c2cb4218e366, ce591da957c7, d019d5604523]; PR-record issue refs 6 [#239/MERGED, #242/MERGED, #243/MERGED, #244/MERGED, #245/MERGED, #246/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: no formal GitHub closed-by PR is present, but repository cross-reference finds 12 reachable commit-message references and 6 merged PR records (#239, #242, #243, #244, #245, #246). These independently show implementation activity associated with #240; they do not prove every ETL acceptance criterion was completed. Portfolio: 설계 case.

### Issue #250 — [Feat] LLM 기반 AI SRE(Site Reliability Engineering) 모니터링 에이전트 도입
- author zbnerd; CLOSED; created 2026-01-23T09:22:52Z; closed 2026-01-23T09:39:10Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 `MonitoringAlertService`와 `DiscordAlertService`를 통해 시스템 장애 시 알림을 받고 있으나, 다음과 같은 한계가 존재함. 1. **단순 정보 전달:** "스레드 풀 고…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 0 [none]; PR-record issue refs 0 [none].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #251 — [Feat] LangChain4j 기반 AI SRE 모니터링 에이전트 + OpenTelemetry 트레이싱 도입
- author zbnerd; CLOSED; created 2026-01-23T09:39:36Z; closed 2026-01-27T14:26:11Z; reason COMPLETED. Body: ## 📌 Problem Definition (문제 정의) 현재 `MonitoringAlertService`와 `DiscordAlertService`를 통해 시스템 장애 시 알림을 받고 있으나, 다음과 같은 한계가 존재함: 1. **단순 정보 전달:** `TaskReje…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [00fca3a58edd, 371377642885]; PR-record issue refs 1 [#275/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 기능 case.

### Issue #252 — docs: README Product-Level Overhaul (Target Users, Value Proposition, TL;DR)
- author zbnerd; CLOSED; created 2026-01-23T18:38:03Z; closed 2026-01-25T15:00:49Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 현재 README는 기술적 설명(7대 모듈, Chaos Engineering, Performance)은 풍부하지만, **"누가 이걸 왜 써야 하는가"**에 대한 제품 관점의 설득력이 부족합니다. 평가 결과: -…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [8f9ec3752865, 969e7714a68f]; PR-record issue refs 2 [#261/MERGED, #268/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #253 — docs: Business Model Canvas (BMC) 문서 작성
- author zbnerd; CLOSED; created 2026-01-23T18:39:22Z; closed 2026-01-25T14:33:18Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **A3 (솔루션 차별성): 1/7**, **A4 (시장 가치): 1/5** 로 매우 낮은 점수를 받았습니다. 평가 코멘트: > "제공된 자료는 사실상 '기술 솔루션/레퍼런스 아키텍처' 소개로, BMC…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [837cd48cce62, 969e7714a68f]; PR-record issue refs 1 [#261/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.

### Issue #254 — docs: KPI-BSC (균형성과표) 문서 작성
- author zbnerd; CLOSED; created 2026-01-23T18:39:26Z; closed 2026-01-25T14:33:19Z; reason COMPLETED. Body: ### 📌 Problem Definition (문제 정의) 평가에서 **D1 (README 품질): 1/6** 로 매우 낮은 점수를 받았습니다. 평가 코멘트: > "BSC의 4가지 관점에서 성과를 어떻게 측정할지에 대한 체계가 나타나지 않습니다. > 내부 프로세스 관점…
- discussion: 0. Linked PRs: 0 []. Linked commits: only merge commits of those formal links; other commit linkage not exposed by this aggregate query.
- Repository cross-reference (textual; non-formal): formal closed-by PRs 0 [none]; issue-record textual PR mentions 0 [none]; reachable commit-message refs 2 [837cd48cce62, 969e7714a68f]; PR-record issue refs 1 [#261/MERGED].
- Root cause: reported in issue text only; not independently verified here. Resolution: closed with no linked merged PR; code resolution unverified. Portfolio: 문서화 case.
