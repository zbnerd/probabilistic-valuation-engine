# Pull Request Inventory — GitHub Evidence Audit

## Scope and method

- Repository: `zbnerd/probabilistic-valuation-engine`
- Original retrieval (UTC): 2026-08-01T05:40:48Z; this preserved the original 709 records below.
- Live reconciliation (UTC): 2026-08-08T14:16:30Z. Authenticated GitHub REST `GET /repos/zbnerd/probabilistic-valuation-engine/pulls?state=all&per_page=100` was read with `--paginate`, independently checked against GitHub Search `is:pr` and GraphQL `repository.pullRequests.totalCount`.
- Live counts: REST enumeration 710 unique PR numbers; Search `total_count` 710 (`incomplete_results: false`); GraphQL total 710; duplicate count 0. REST state distribution: 710 closed, of which 699 have `merged_at` and 11 are closed without `merged_at`; 0 open.
- Original detail source: authenticated GitHub GraphQL `repository.pullRequests`, ordered by `CREATED_AT` ascending, for the 500 records that completed in the original aggregate retrieval.
- Detail-gap refresh (UTC): 2026-08-08T14:55:24Z. The 209 records previously blocked by aggregate GraphQL HTTP 502, plus live-added PR #1464, were read independently. Each PR used paginated REST connections for reviews, review comments, conversation comments, commits, and changed files; formal linked issues used paginated GraphQL `closingIssuesReferences`. GitHub caps PR #1196's 288-commit relation at 250 nodes, so its full set is the locally reachable `head ^base` range: 288 unique objects matching API metadata, with all 250 API-returned SHAs contained in the set.
- Deterministic companion: [`pr_detail_inventory.jsonl`](./pr_detail_inventory.jsonl) contains 210 records sorted by PR number. It preserves complete connected commit SHAs (including all 50 for PR #1464), all returned file paths, discussion IDs/timestamps, formal linked issues, and hashes/byte counts rather than bodies for untrusted free-form discussion text.
- Privacy: email-like strings in Markdown summaries remain redacted; credential material and raw discussion bodies are not reproduced.

## Interpretation safeguards

- `MERGED`/a non-null `mergedAt` is the sole basis for calling a PR merged. `CLOSED` without that evidence is explicitly treated as not applied.
- The change/resolution statement is based on returned changed-file metadata (path, type, additions, deletions) plus commit IDs and PR body summary; it is not a claim that unmerged work was deployed.
- “Linked issues” means GitHub `closingIssuesReferences`; textual mentions may be present but are not asserted as formal links.
- “Discussion” separates review submissions, inline review comments, and conversation comments. The refreshed records were followed through every API page, and their returned counts were checked against GitHub totals.

## Detail completeness

The earlier 209-record HTTP-502 limitation is closed by the per-PR refresh above. For every refreshed record, connected commit and changed-file counts match PR REST metadata; review counts match GraphQL totals; review-comment and conversation-comment counts match PR REST metadata; and formal closing-issue counts match GraphQL totals. The JSONL companion is the complete machine-readable evidence surface for those connections, while this Markdown remains compact.

## Records

<!-- PR_RECORDS -->
### PR #1455 — fix(infra): harden airflow-db after container compromise
- author zbnerd; closed; created 2026-07-03T02:15:15Z; closed 2026-07-03T02:16:34Z; merged yes/2026-07-03T02:16:34Z; merge commit bcb7a5b54844e7242b1303a8f4e6b4c389b67a04. Body: ## Summary - `maple-airflow-db` was compromised by a **cryptominer**. Entry vector: internet-exposed postgres port (`5432 → 0.0.0.…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1455.
- commits: 1 [fd0aa22]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +306/-4]. Sample: MODIFIED `.gitignore` +3/-0; MODIFIED `docker-compose.airflow.yml` +10/-4; ADDED `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md` +293/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1456 — docs(incident): enhance airflow-db compromise report
- author zbnerd; closed; created 2026-07-03T02:24:47Z; closed 2026-07-03T02:42:50Z; merged yes/2026-07-03T02:42:50Z; merge commit 7d3411a5c9b54bba49c887ed234fc43186d0688d. Body: ## Summary Enhance the 2026-07-03 incident report per review feedback. Adds the sections expected of a professional Security Incid…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1456.
- commits: 1 [070345d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +119/-12]. Sample: MODIFIED `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md` +119/-12. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1457 — docs(incident): root-cause framing + incident timeline (MTTD/MTTC/MTTR)
- author zbnerd; closed; created 2026-07-03T03:01:16Z; closed 2026-07-03T03:26:55Z; merged yes/2026-07-03T03:26:55Z; merge commit 1a3d26f33a0d9e5c37f2920e550ed307ccc32271. Body: ## Summary Two refinements to the 2026-07-03 incident report, both per review: **1. Root-cause framing (§7)** — `0.0.0.0` bind is …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1457.
- commits: 2 [70c6911, d7fb0fd]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +68/-5]. Sample: MODIFIED `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md` +68/-5. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1458 — docs(incident): add payload-vs-capability severity framing
- author zbnerd; closed; created 2026-07-03T03:29:34Z; closed 2026-07-03T03:31:50Z; merged yes/2026-07-03T03:31:50Z; merge commit 3b990969ac83ff2666bf119c3074f80db5589d68. Body: ## Summary Reframes severity: the cryptominer was the **payload**, but the achieved **capability** was arbitrary code execution as…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1458.
- commits: 1 [dbbbf15]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +22/-1]. Sample: MODIFIED `docs/23_Incident_Response_Journey/2026-07-03-airflow-db-container-compromise.md` +22/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1459 — infra(adr-744): move services to maple-network bridge, force loopback-only
- author zbnerd; closed; created 2026-07-03T10:13:32Z; closed 2026-07-03T10:13:41Z; merged yes/2026-07-03T10:13:41Z; merge commit 40ac1b9a27de5ba76aaa53b2b5a421dd0d66999d. Body: ## Summary Post-incident hardening (incident #1455/#1456 — airflow-db cryptominer). Closes the 0.0.0.0-exposed service posture tha…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1459.
- commits: 1 [cd452b8]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=1, MODIFIED=4; +203/-57]. Sample: MODIFIED `docker-compose.airflow.yml` +14/-10; MODIFIED `docker-compose.services.yml` +5/-8; MODIFIED `docker-compose.yml` +36/-25. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1460 — feat(ext-api): add per-endpoint Prometheus counters
- author zbnerd; closed; created 2026-07-04T07:39:43Z; closed 2026-07-04T07:39:51Z; merged yes/2026-07-04T07:39:51Z; merge commit 501c5a26ec6c3dae1db5b0e71d76f31e93347db9. Body: ## Summary - `external_api_nexon_total_ms_total{endpoint=...}` — cumulative ms of successful Nexon fetches per RANKING_FETCH / OCI…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1460.
- commits: 1 [bdd1513]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +22/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/metrics/SnapshotFetchMetrics.kt` +7/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/metrics/SnapshotVolumeMetrics.kt` +14/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt` +1/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1461 — fix(ext-api): normalize endpoint case in recordUsersCompleted
- author zbnerd; closed; created 2026-07-06T00:34:21Z; closed 2026-07-06T00:34:31Z; merged yes/2026-07-06T00:34:31Z; merge commit 0dd4a0f7efc0ec5ab7f402f057fc16fbbe781dab. Body: ## Summary PR #1460 introduced two per-endpoint counters but they emitted with mismatched `endpoint` tag case: - `external_api_nex…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1461.
- commits: 1 [fc58fb1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +7/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotSinkEventPublisher.kt` +7/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1462 — Develop
- author zbnerd; closed; created 2026-07-18T05:59:41Z; closed 2026-07-18T05:59:54Z; merged yes/2026-07-18T05:59:54Z; merge commit 5ff387d73799248905e7a55c67a1946366a4303a. Body: none
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1462.
- commits: 58 [5f28d96, 5351f6d, c522c7f, 1c3eec4, dd99220, f749702, 1ec76cc, bd2974d, … +50]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 52 [ADDED=31, MODIFIED=21; +6254/-273]. Sample: MODIFIED `.gitignore` +3/-0; MODIFIED `README.md` +106/-191; MODIFIED `docker-compose.airflow.yml` +26/-7. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1463 — refactor: deepen ETL infrastructure ownership
- author zbnerd; closed; created 2026-07-20T15:38:08Z; closed 2026-07-20T22:56:40Z; merged yes/2026-07-20T22:56:40Z; merge commit cf85917b5ce8a463a0cf4e4b04bcaed74a5be174. Body: ## Summary - Extract pipeline artifact identity, storage, and lifecycle ownership into `module-pipeline-artifact`. - Centralize Ka…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1463.
- commits: 45 [3f0c34c, db62df5, 4da3985, a358092, 008c79b, b9b8810, 56946d3, 0ad4449, … +37]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 327 [ADDED=203, MODIFIED=109, REMOVED=13, RENAMED=2; +442498/-4788]. Sample: ADDED `.superpowers/sdd/task-5-report.md` +46/-0; ADDED `.superpowers/sdd/task-6-report.md` +43/-0; ADDED `.superpowers/sdd/task-7-report.md` +38/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1464 — docs(portfolio): capture exhaustive evidence snapshot
- author/state/dates: zbnerd | MERGED | created 2026-08-03T04:27:57Z | closed 2026-08-03T04:29:06Z | merged yes at 2026-08-03T04:29:06Z | merge 79c30703f371692bc9b7e7f2d244dcc3f0166c60. Canonical link: https://github.com/zbnerd/probabilistic-valuation-engine/pull/1464.
- body summary: portfolio evidence-capture, redaction, archive, coverage, and publication-validation tooling; this is author-supplied PR context, not an independent effectiveness claim.
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1464.
- commits: 50 [aa2338c, 123c464, 7b8dcd6, 645b980, 46e9226, d93e5d1, b90f862, 4741d7e, … +42]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 117 [ADDED=115, MODIFIED=2; +50157/-2627]. Sample: MODIFIED `docs/Portfolio_Book/output/build_source_inventory.py` +7/-284; ADDED `docs/Portfolio_Book/output/research/ai-trace-records-001.tar.gz` +0/-0; ADDED `docs/Portfolio_Book/output/research/ai-trace-records-002.tar.gz` +0/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1196 — feat(calculator): expose Caffeine cache stats to Prometheus
- author zbnerd; closed; created 2026-06-08T04:13:36Z; closed 2026-06-08T04:29:23Z; merged yes/2026-06-08T04:29:23Z; merge commit 8d0272d510f21ef7a744232a87b7c18a2ec95515. Body: ## Summary - `CalculationCache`: add public `cache()` accessor for metrics-only access - `CacheMetrics`: register 5 Prometheus gau…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1196.
- commits: 288 [6fcf4b3, 26fe587, cc80121, da6e736, fe49d18, 3ecf1b7, fa69b23, 19f1976, … +280]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 578 [ADDED=324, MODIFIED=217, REMOVED=25, RENAMED=12; +41267/-5865]. Sample: ADDED `.claude/hooks/trace-lib.sh` +39/-0; ADDED `.claude/hooks/trace-prompt.sh` +20/-0; ADDED `.claude/hooks/trace-session-init.sh` +28/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1197 — style: apply spotless formatting across 270 files
- author zbnerd; closed; created 2026-06-08T04:40:21Z; closed 2026-06-08T04:54:10Z; merged yes/2026-06-08T04:54:10Z; merge commit a42a7fc4c5fa95cd389ab036317cf0397008ff37. Body: ## Summary Pure import reordering + whitespace cleanup across 270 files. No semantic changes. ## Background `foojay-resolver-conve…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1197.
- commits: 1 [4ae7196]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 270 [MODIFIED=270; +1628/-1413]. Sample: MODIFIED `module-app/src/main/java/maple/expectation/application/service/character/CharacterCreationService.java` +1/-1; MODIFIED `module-app/src/main/java/maple/expectation/application/service/character/GameCharacterService.java` +1/-1; MODIFIED `module-app/src/main/java/maple/expectation/application/service/character/OcidResolver.java` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1199 — docs(adr): ADR-723 + Guide §23 IO/CPU split pattern (#1125)
- author zbnerd; closed; created 2026-06-08T06:05:47Z; closed 2026-06-08T06:10:21Z; merged yes/2026-06-08T06:10:21Z; merge commit a6a07c661d36cdc177a675db2a1afff01442a622. Body: ## Summary - ADR-723: IO/CPU 분리 패턴 5섹션 결정 기록 - Guide §23: Algorithm-based CPU 분류 + 모듈별 wrap 방식 (withContext/runBlocking) - Follow-…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1199.
- commits: 7 [27543e0, 9c02068, 763e973, 22f1ffa, ac20c46, 13669fa, 65ca23c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=3, MODIFIED=1; +1170/-0]. Sample: ADDED `docs/01_ADR/ADR-723_io-cpu-split-pattern.md` +122/-0; MODIFIED `docs/03_Technical_Guides/async-concurrency.md` +165/-0; ADDED `docs/superpowers/plans/2026-06-08-io-cpu-split-pattern.md` +703/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1200 — refactor(infra): executor bean rename + expectationComputeExecutor IO/CPU split (#1126)
- author zbnerd; closed; created 2026-06-08T07:36:33Z; closed 2026-06-08T07:44:08Z; merged yes/2026-06-08T07:44:08Z; merge commit bcbda2f96dfc8c0ddbca4a9ba731a5054f6641c5. Body: ## Summary - expectationComputeExecutor를 IO/CPU executor로 분리 (Issue #1126) - taskExecutor bean 이름 충돌 해결: `defaultAsyncExecutor` (C…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1200.
- commits: 13 [dede921, 790e91b, 616fbaf, 6ad9ea0, 232ecb1, afe2c25, 6835af9, 4f6460c, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 18 [ADDED=3, MODIFIED=15; +1453/-70]. Sample: ADDED `docs/superpowers/plans/2026-06-08-1126-executor-rename-split.md` +1074/-0; ADDED `docs/superpowers/specs/2026-06-08-1126-executor-rename-split-design.md` +193/-0; MODIFIED `gradle/libs.versions.toml` +1/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1203 — refactor(calculator): SnapshotChunkProcessor parse/calc worker split + dispatcher override…
- author zbnerd; closed; created 2026-06-08T08:52:38Z; closed 2026-06-08T08:55:45Z; merged yes/2026-06-08T08:55:45Z; merge commit e3d1006dd7022336112a0c37f2a1fe16fa4cc4fc. Body: ## Summary - parseWorkers + calcWorkers YAML 독립 설정 (#1127) - parseDispatcher + calcDispatcher YAML override (CoroutineDispatcherCo…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1203.
- commits: 6 [a9ffb7b, 22c4cab, 85d31fb, 9145a4b, db13ebe, 84c150c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=2, MODIFIED=3; +387/-74]. Sample: MODIFIED `docs/03_Technical_Guides/async-concurrency.md` +1/-1; ADDED `docs/superpowers/specs/2026-06-08-1127-calculator-worker-split-design.md` +216/-0; ADDED `module-calculator/src/main/kotlin/maple/calculator/config/CoroutineDispatcherConverter.kt` +35/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1207 — refactor(ext-api): CPU 작업 Dispatchers.Default offload — 5 file (#1128)
- author zbnerd; closed; created 2026-06-08T10:12:46Z; closed 2026-06-08T10:22:14Z; merged yes/2026-06-08T10:22:14Z; merge commit 10c794a705512c34f4be44a71a445d2bf1e9f521. Body: ## Summary - 5 file 에 CPU offload 적용 (Issue #1128) - 2 file follow-up (SnapshotFetchPhase dropped, UrgentCharacterRequestConsumer …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1207.
- commits: 6 [be7a84c, 0344c62, 6c8bb24, 856869c, 56b5a79, 91884ab]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=1, MODIFIED=6; +666/-326]. Sample: ADDED `docs/superpowers/specs/2026-06-08-1128-external-api-cpu-offload-design.md` +198/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` +13/-3; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` +16/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1210 — refactor(sync): CPU 작업 Dispatchers.Default offload + OcidLookupRunConsumer executor dispat…
- author zbnerd; closed; created 2026-06-08T12:58:14Z; closed 2026-06-08T13:01:19Z; merged yes/2026-06-08T13:01:19Z; merge commit d2307e65ed4beb428f36455b7faa69fbe4c8478d. Body: ## Summary - 5 file 의 CPU 작업을 `runBlocking(Dispatchers.Default) { }` 로 offload - OcidLookupRunConsumer 에 executor dispatch 추가 (Kaf…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1210.
- commits: 7 [bd38ce8, b9a35a4, 67d4f27, 69c1826, f05a70e, 15ad61f, 43506c5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=2, MODIFIED=5; +620/-33]. Sample: ADDED `docs/superpowers/plans/2026-06-08-1129-synchronizer-cpu-offload.md` +372/-0; ADDED `docs/superpowers/specs/2026-06-08-1129-synchronizer-cpu-offload-design.md` +170/-0; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` +25/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1211 — refactor(rest): ReadModelQueryService.batchQuery + getStatus CPU offload (Closes #1130)
- author zbnerd; closed; created 2026-06-08T13:08:01Z; closed 2026-06-08T13:17:50Z; merged yes/2026-06-08T13:17:50Z; merge commit ca472873421a3d1ddb03f9cf3d0cb3539cec5ad2. Body: ## Summary - 2 file 의 IO/CPU 분리 (ReadModelQueryService + ExpectationV6Controller) - 1 file (BatchReadScheduler) skip: BatchResolve…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1211.
- commits: 3 [2c5bc1e, cf0bcf2, 8a837fe]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=2, MODIFIED=2; +393/-23]. Sample: ADDED `docs/superpowers/plans/2026-06-08-1130-rest-controller-io-cpu-split.md` +194/-0; ADDED `docs/superpowers/specs/2026-06-08-1130-rest-controller-io-cpu-split-design.md` +155/-0; MODIFIED `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` +19/-10. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1212 — refactor(infra): ExternalApiWorker + PgmqWorker CPU/IO 분리 (Closes #1131)
- author zbnerd; closed; created 2026-06-08T13:13:52Z; closed 2026-06-08T13:17:54Z; merged yes/2026-06-08T13:17:54Z; merge commit 30326c780e5b5d396fa6680bfc1325a3220c92f9. Body: ## Summary - ExternalApiWorker.runCalculationAndComplete: CPU section (calculate + serialize + gzip + SHA-256) on Dispatchers.Defa…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1212.
- commits: 3 [746af5c, 341bb75, 0e8e0c5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +160/-29]. Sample: ADDED `docs/superpowers/specs/2026-06-08-1131-infra-worker-dispatcher-design.md` +116/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` +5/-2; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt` +39/-27. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1213 — feat(infra): expose ForkJoinPool.commonPool() metrics for ADR-723 saturation (Closes #1198…
- author zbnerd; closed; created 2026-06-08T13:17:01Z; closed 2026-06-08T13:17:57Z; merged yes/2026-06-08T13:17:57Z; merge commit 779028bff87c981bc2d9a301aa6f50bf09b8262b. Body: ## Summary - ForkJoinPool.commonPool() 의 CPU activity 를 Prometheus 로 노출 - ADR-723 §4 cross-module saturation detection trigger ## …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1213.
- commits: 1 [b4480ab]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +47/-0]. Sample: ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/metrics/ForkJoinPoolMetrics.kt` +47/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1214 — refactor(calc): deprecate workerCount, prepare for dispatcher as beans (Closes #1201)
- author zbnerd; closed; created 2026-06-08T13:54:04Z; closed 2026-06-08T13:56:01Z; merged yes/2026-06-08T13:56:01Z; merge commit 6935da34ca0d6f415c497c5df85a34e3d3de1311. Body: Issue #1201: @Deprecated on workerCount (backward compat kept). #1202 status: parseDispatcher/calcDispatcher still String-based vi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1214.
- commits: 1 [a2cc3e5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +10/-9]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/config/PipelineProperties.kt` +10/-9. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1215 — fix(ext-api): add authCharacterFetchExecutor bean (Closes #1206)
- author zbnerd; closed; created 2026-06-08T13:56:02Z; closed 2026-06-08T13:57:03Z; merged yes/2026-06-08T13:57:03Z; merge commit a7880e8f58df83de9dfecaf5e3da0a95d2ff51a6. Body: Issue #1206: AuthCharacterFetchConsumer 의 @Qualifier("authCharacterFetchExecutor") 인자 wired. bean 정의 누락 → Spring startup fail 위험. …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1215.
- commits: 1 [2fd3700]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +45/-0]. Sample: ADDED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthExecutorConfig.kt` +45/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1222 — feat(infra): V1 ObjectStorage foundation (issue #1216)
- author zbnerd; closed; created 2026-06-09T10:21:00Z; closed 2026-06-09T10:21:24Z; merged yes/2026-06-09T10:21:24Z; merge commit aa4a8691859a0c00a42f266504ef4da936ba51dd. Body: ## Summary Implements VS1 of the macro MinIO storage migration spec (`docs/superpowers/specs/2026-06-09-minio-storage-migration-de…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1222.
- commits: 16 [2d3c9ac, 7b8ba39, 6472707, a67ca5d, 4a61efb, 4129b7f, b69bb2a, e533fc9, … +8]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 26 [ADDED=15, MODIFIED=11; +3658/-2]. Sample: MODIFIED `.env.example` +15/-0; MODIFIED `build.gradle` +1/-0; MODIFIED `docker-compose.yml` +39/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1223 — docs(adr): ADR-725 Object Storage MinIO migration VS1+VS2 summary
- author zbnerd; closed; created 2026-06-09T14:09:31Z; closed 2026-06-09T14:09:43Z; merged yes/2026-06-09T14:09:43Z; merge commit 38e6cbc3be1403211d1361d16f920b7303a3329a. Body: # VS2: Object Storage Migration + MinIO Readiness — PR Summary Closes #1217 (VS2). Captures the trade-off summary for VS1 (PR #122…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1223.
- commits: 20 [3cd52b9, 2ac3aa8, 9fbea10, ef551a7, 3d17a68, fe71dcb, c8ae203, 3bc6708, … +12]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 58 [ADDED=14, MODIFIED=30, REMOVED=14; +3046/-1825]. Sample: MODIFIED `docs/01_ADR/ADR-022-redis-dependency-removal.md` +29/-1; ADDED `docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md` +117/-0; ADDED `docs/superpowers/plans/2026-06-09-v2-pipeline-modules-migration-plan.md` +1500/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1224 — VS3: Dev e2e MinIO validation tooling (#1218)
- author zbnerd; closed; created 2026-06-10T03:09:54Z; closed 2026-06-10T04:50:03Z; merged yes/2026-06-10T04:50:03Z; merge commit 79fcbdf75fa85feb3bb540162cb112d477c4a4bd. Body: ## Summary VS3 dev cutover tooling per issue #1218: `STORAGE_BACKEND=minio` wrapper + MinIO-aware `pipeline-test` skill + ADR-725 …
- reviews/discussion: 1 reviews [COMMENTED=1]; 3 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1224.
- commits: 14 [adf5a12, 746655a, cfdc725, af9be20, 72677c9, 63c16b4, 413400c, b7e9a17, … +6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [ADDED=10, MODIFIED=3; +2690/-1]. Sample: ADDED `.claude/skills/pipeline-test/SKILL.md` +464/-0; MODIFIED `.gitignore` +4/-1; MODIFIED `docs/01_ADR/ADR-725_object-storage-minio-migration-vs1-vs2.md` +24/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1225 — fix(module-external-api): ExecutorService return type for authCharacterFetchExecutor bean
- author zbnerd; closed; created 2026-06-10T06:37:28Z; closed 2026-06-10T06:39:28Z; merged yes/2026-06-10T06:39:28Z; merge commit 6a48ee65231bcf1426e3671d5d27115440c6b955. Body: ## Problem `module-external-api` failed to boot with: \`\`\` NoSuchBeanDefinitionException: No qualifying bean of type 'ExecutorSe…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1225.
- commits: 1 [b944ac8]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +3/-3]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthExecutorConfig.kt` +3/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1226 — fix(boot): wire StorageConfig + restore RunStatusTracker + executor types
- author zbnerd; closed; created 2026-06-10T07:53:29Z; closed 2026-06-10T07:56:54Z; merged yes/2026-06-10T07:56:54Z; merge commit 31d899496d84b1bb64ad1be746e6565f81edcf11. Body: ## Summary Pipeline boot and runtime tracking broken by VS2 Object Storage migration. 8 surgical fixes across 3 modules — all test…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1226.
- commits: 5 [62211f5, 9a9ac38, aa066dc, 5463e83, 46b19ed]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [MODIFIED=12; +93/-49]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +7/-0; MODIFIED `.gitignore` +1/-2; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1227 — fix(boot): OcidLookupPhase recursion + MinIO wiring + pipeline-test default
- author zbnerd; closed; created 2026-06-10T09:19:37Z; closed 2026-06-10T09:20:25Z; merged yes/2026-06-10T09:20:25Z; merge commit b7a0c0c4715cb3ce9ddfc84b879f9ca495cde6f1. Body: ## Summary Three latent issues from the VS2 migration + the pipeline-test skill rewrite for MinIO default. ### OcidLookupPhase inf…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1227.
- commits: 4 [0a8ef7b, 4e5459a, e6b911d, 6c78a28]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [MODIFIED=9; +102/-60]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +39/-30; MODIFIED `gradle/libs.versions.toml` +1/-0; MODIFIED `module-calculator/build.gradle` +1/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1228 — refactor(ext-api, cleanup): migrate to ObjectStorage (Tasks 1-14)
- author zbnerd; closed; created 2026-06-10T12:36:32Z; closed 2026-06-10T13:39:07Z; merged yes/2026-06-10T13:39:07Z; merge commit 8b21ac532a024e888374c07c2d64ca4ae0d4768c. Body: ## Summary Migrates ext-api (writers, readers, phases, scheduler) and module-cleanup to use the unified `ObjectStorage` interface …
- reviews/discussion: 1 reviews [COMMENTED=1]; 6 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1228.
- commits: 19 [97a4bce, 56ba266, d034419, 13b6ebc, 6a7a60a, 0eb8fda, 538c7fd, e2f3990, … +11]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 36 [ADDED=15, MODIFIED=20, REMOVED=1; +3500/-658]. Sample: ADDED `docs/superpowers/plans/2026-06-10-raw-path-to-minio-migration.md` +1592/-0; ADDED `docs/superpowers/specs/2026-06-10-raw-path-to-minio-migration-design.md` +208/-0; MODIFIED `module-cleanup/src/main/kotlin/maple/cleanup/controller/CleanupController.kt` +12/-11. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1229 — fix(ext-api): repair 5 post-merge test fixtures + RankingFetch race
- author zbnerd; closed; created 2026-06-10T13:55:40Z; closed 2026-06-10T14:19:17Z; merged yes/2026-06-10T14:19:17Z; merge commit 7f5fdee6b0c523398b980c4aed39c06dbcab4027. Body: ## Summary Two followups from PR #1228 (raw path → MinIO migration): 1. **5 pre-existing test files** had compile errors after the…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1229.
- commits: 1 [1c54202]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [MODIFIED=9; +74/-44]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt` +1/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` +15/-10; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriterTest.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1230 — fix(ext-api): complete raw-path-to-minio migration audit fixes
- author zbnerd; closed; created 2026-06-11T00:45:12Z; closed 2026-06-11T00:46:10Z; merged yes/2026-06-11T00:46:10Z; merge commit caad957811c47ed1854669dadb37cafbdb2b4355. Body: Post-merge audit of PR #1228 (raw-path-to-minio migration). Found 21 issues across 4 modules; this PR addresses the actionable one…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1230.
- commits: 1 [e554d03]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [MODIFIED=12, REMOVED=1; +86/-118]. Sample: MODIFIED `docs/superpowers/specs/2026-06-10-raw-path-to-minio-migration-design.md` +2/-2; MODIFIED `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` +4/-3; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriter.kt` +6/-5. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1231 — fix(ext-api): re-enable character-basic and item-equipment phases
- author zbnerd; closed; created 2026-06-11T03:58:40Z; closed 2026-06-11T06:39:29Z; merged yes/2026-06-11T06:39:29Z; merge commit 350d79f01e536a9de0892ce865cd5c93eea2bc5a. Body: Issue #1217 left the char-basic + item-equipment phases disabled in `ExternalApiScheduler.triggerDailyRefresh` with a TODO marker.…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1231.
- commits: 1 [7cf14ae]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +28/-47]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +20/-47; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/ExternalApiSchedulerTest.kt` +8/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1232 — fix(ext-api): OcidCacheProvider reads JSONL instead of splitting on tab
- author zbnerd; closed; created 2026-06-11T05:13:09Z; closed 2026-06-11T06:39:48Z; merged yes/2026-06-11T06:39:48Z; merge commit 5460b05ca7e6f00ee703ae5b4195d8801a7c88b6. Body: The reader split each line on "\t" and required parts.size >= 2 to accept an entry. OcidLookupPhase writes JSON lines ({"userIgn":…
- reviews/discussion: 0 reviews [none]; 0 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1232.
- commits: 3 [7cf14ae, 5ce1002, 56b1ee9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [MODIFIED=4; +123/-58]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` +40/-5; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +20/-47; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/cache/OcidCacheProviderTest.kt` +55/-6. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1233 — fix(calc+sync): decode bodyBytes when chunk records have no inline body
- author zbnerd; closed; created 2026-06-11T07:28:26Z; closed 2026-06-11T07:30:07Z; merged yes/2026-06-11T07:30:07Z; merge commit 254441e6588a1e81a992ba328c323824e5612947. Body: Ext-api writers (BatchFetchSupport) emit SnapshotChunkRecord.Success with the response payload as a base64-encoded ByteArray field…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1233.
- commits: 5 [7cf14ae, 5ce1002, 56b1ee9, b99e2dc, 1547df8]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +191/-63]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt` +32/-2; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` +40/-5; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +20/-47. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1234 — fix(ext-api): stream OCID mapping writes to MinIO instead of buffering
- author zbnerd; closed; created 2026-06-11T09:10:43Z; closed 2026-06-11T09:11:28Z; merged yes/2026-06-11T09:11:28Z; merge commit 4fa308f191a28c95def6c7f42e439a7cb4ff178a. Body: The previous implementation accumulated every successful mapping (`{"userIgn":"...","ocid":"..."}` JSON, ~200B) in a single `resul…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1234.
- commits: 1 [c74cdfd]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +96/-53]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` +73/-41; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` +23/-12. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1235 — fix(ext-api): catch Throwable in sink writer loop so OOM/Error surfaces
- author zbnerd; closed; created 2026-06-11T13:26:05Z; closed 2026-06-11T15:00:33Z; merged yes/2026-06-11T15:00:33Z; merge commit 445044c0c1fe31560611bdac0f0db3985f0c8a0e. Body: ChunkedSnapshotSink.runWriterLoop had `catch (ex: Exception)`. When the writer thread threw an `Error` (most commonly `OutOfMemory…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1235.
- commits: 1 [d0747c1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +123/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` +8/-1; ADDED `module-external-api/src/test/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSinkTest.kt` +115/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1241 — chore: record module-app + module-web deletion plan
- author zbnerd; closed; created 2026-06-11T15:39:03Z; closed 2026-06-11T15:44:56Z; merged yes/2026-06-11T15:44:56Z; merge commit 74725906005ce5a1c46dec96ae6ebcdb3c3c98cb. Body: 이슈 트래커 + 이 PR이 module-app/module-web 삭제 작업의 단일 소스. 이슈 5개로 분할됨 (의존성 순): - #1236 — infra: dead V4 service dedup + TraceAspect 제거 (병행…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1241.
- commits: 1 [bde4b2d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 0 [none; +0/-0]. Sample: none. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1242 — fix(ext-api): track item-equipment completion in run-status via CHARACTER_BASIC_DONE
- author zbnerd; closed; created 2026-06-12T00:38:39Z; closed 2026-06-12T00:38:49Z; merged yes/2026-06-12T00:38:49Z; merge commit acd09e1e70572c7ed1f071c378e7a15df44f135d. Body: ## Summary - Add `PipelinePhase.CHARACTER_BASIC_DONE` as intermediate (non-terminal) state - `ExternalApiScheduler` transitions to…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1242.
- commits: 1 [98517fe]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +124/-19]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/PipelinePhase.kt` +1/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +8/-8; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt` +26/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1278 — fix(ext-api): two startRun + OOM fixes
- author zbnerd; closed; created 2026-06-12T05:42:00Z; closed 2026-06-12T06:02:17Z; merged yes/2026-06-12T06:02:17Z; merge commit 2e3fd56796dd37f2b889b98f5c08b3d61bc2d114. Body: ## Summary Two fixes on the same branch. ### Fix 1: Writer-thread OOM - `GzipJsonlChunkWriter` buffered each chunk in a `ByteArray…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1278.
- commits: 2 [4a38044, eafb905]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [MODIFIED=13; +296/-54]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +16/-6; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt` +12/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` +1/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1279 — fix(ext-api): backport OOM + startRun fixes to release-0.6.12
- author zbnerd; closed; created 2026-06-12T06:06:33Z; closed 2026-06-12T06:27:49Z; merged yes/2026-06-12T06:27:49Z; merge commit ec3dd6fe47b850cfb20e284ee52736c6b433733a. Body: ## Summary Cherry-pick of the two fix commits from PR #1278 onto the release branch. - **810b3ca41** — `fix(ext-api): stream gzipp…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1279.
- commits: 2 [810b3ca, fe8a60c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [MODIFIED=13; +296/-54]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +16/-6; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt` +12/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` +1/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1280 — fix(storage): add putFile to skip double-spool in writer hot path
- author zbnerd; closed; created 2026-06-12T10:00:48Z; closed 2026-06-12T10:01:32Z; merged yes/2026-06-12T10:01:32Z; merge commit 47da4ffa78fb782816a039e2be5243645a01b237. Body: ## Summary The OOM fix in #1278 moved chunk upload from `ObjectStorage.put(byte[])` to `ObjectStorage.putStream(InputStream)`. The…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1280.
- commits: 1 [3dbc18c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [MODIFIED=9; +117/-57]. Sample: MODIFIED `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` +13/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt` +26/-16; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/artifact/UrgentChunkArtifactWriterTest.kt` +6/-8. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1281 — fix(ext-api): set ITEM_EQUIPMENT phase + don't overwrite daily runId
- author zbnerd; closed; created 2026-06-13T06:12:00Z; closed 2026-06-13T06:19:27Z; merged yes/2026-06-13T06:19:27Z; merge commit e27600f4b6e8b4ac2514449a6211a7a8feab5cb4. Body: ## Summary Two related fixes on the same branch — both stem from PR #1278, which added `startRun` to the item-equipment continuous…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1281.
- commits: 2 [cbff899, 7a38411]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +80/-7]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` +22/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ItemEquipmentContinuousLoop.kt` +23/-7; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` +35/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1282 — fix(airflow): bump daily_collection_pipeline task timeouts to fit pipeline runtime
- author zbnerd; closed; created 2026-06-13T09:24:38Z; closed 2026-06-13T12:32:35Z; merged yes/2026-06-13T12:32:35Z; merge commit 4bb853e05b5e5fe71cd0dc1967c214b934bc1bc4. Body: ## Summary - `wait_for_completion` execution_timeout: **2h → 4h** - `wait_ie_cycle` execution_timeout: **1h → 2h** The full daily …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1282.
- commits: 6 [810b3ca, fe8a60c, ec3dd6f, 8337490, 051762b, 3043ba8]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=2, MODIFIED=1; +743/-2]. Sample: MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +10/-2; ADDED `docs/superpowers/plans/2026-06-12-architecture-review.md` +534/-0; ADDED `docs/superpowers/specs/2026-06-12-architecture-review-design.md` +199/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1283 — perf(ext-api): fire-and-forget chunk uploads via S3TransferManager
- author zbnerd; closed; created 2026-06-13T14:48:58Z; closed 2026-06-13T14:53:56Z; merged yes/2026-06-13T14:53:56Z; merge commit 89b26ce2b70efa4d007af5715804c935b0e0cd30. Body: ## Summary The writer thread previously blocked 5-10s per 128MB chunk on a synchronous `s3.putObject` call. With ~50 files/s for i…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1283.
- commits: 1 [94cdd56]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 15 [MODIFIED=15; +288/-37]. Sample: MODIFIED `gradle/libs.versions.toml` +1/-0; MODIFIED `module-common/src/main/kotlin/maple/expectation/common/storage/ObjectStorage.kt` +21/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` +55/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1284 — fix(airflow): trigger task — PythonOperator over HttpOperator
- author zbnerd; closed; created 2026-06-14T07:13:20Z; closed 2026-06-14T08:10:46Z; merged yes/2026-06-14T08:10:46Z; merge commit 38c3ecd2982c55759f44adb42fa4c65bf76c6b6e. Body: ## Problem `daily_collection_pipeline.trigger_daily_collection` failed on every scheduled run since 2026-06-12 (3 consecutive fail…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1284.
- commits: 3 [742cd97, 9734a19, 05e0939]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +263/-50]. Sample: MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +126/-50; ADDED `docs/01_ADR/ADR-726-airflow-trigger-task-design.md` +137/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1285 — fix(infra): paginate MinioObjectStorage.listByPrefix
- author zbnerd; closed; created 2026-06-14T08:09:09Z; closed 2026-06-14T08:10:49Z; merged yes/2026-06-14T08:10:49Z; merge commit 935a88717b9254f9ab4bf8843f86d2d502085a18. Body: ## Problem `module-cleanup` returned `runsDeleted: 0` despite the bucket holding 70+ old runs that exceeded the retention window. …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1285.
- commits: 4 [742cd97, 9734a19, 05e0939, 4002eff]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +290/-59]. Sample: MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +126/-50; ADDED `docs/01_ADR/ADR-726-airflow-trigger-task-design.md` +137/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioObjectStorage.kt` +27/-9. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1286 — feat(calculator,cleanup): stale runId filter + stale-kafka scan endpoint
- author zbnerd; closed; created 2026-06-14T13:46:27Z; closed 2026-06-14T13:46:40Z; merged yes/2026-06-14T13:46:40Z; merge commit 7689a7211426d444d1202c94df4be0aeb6e2fa02. Body: ## Summary - **Calculator**: Polls ext-api's `/api/internal/run-status` every 30s to discover the active runId, and drops chunk-re…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1286.
- commits: 1 [10e7855]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=4, MODIFIED=8; +432/-2]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +30/-1; ADDED `docs/01_ADR/ADR-727_stale-kafka-run-handling.md` +112/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt` +2/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1287 — chore(release): merge release-0.6.12 into master
- author zbnerd; closed; created 2026-06-14T16:06:28Z; closed 2026-06-14T16:06:51Z; merged yes/2026-06-14T16:06:51Z; merge commit 7ba2375187bf6549223eef9ea9219d217a75f427. Body: ## Summary Promotes `release-0.6.12` to `master`. This is a release-candidate promotion: `release-0.6.12` has been the live branch…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1287.
- commits: 167 [27543e0, 9c02068, 763e973, 22f1ffa, ac20c46, 13669fa, 65ca23c, a6a07c6, … +159]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 181 [ADDED=75, MODIFIED=93, REMOVED=13; +20131/-2826]. Sample: ADDED `.claude/skills/pipeline-test/SKILL.md` +509/-0; MODIFIED `.env.example` +15/-0; MODIFIED `.gitignore` +2/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1288 — feat(minio): 4 SA prefix-policy isolation + ephemeral CI
- author zbnerd; closed; created 2026-06-15T01:29:53Z; closed 2026-06-15T02:43:27Z; merged yes/2026-06-15T02:43:27Z; merge commit f09fc9d0352ee0542ae1ab455784f0cdefdefff2. Body: ## Summary - Replace shared `minioadmin` root credential with 4 prefix-scoped service accounts. - Single bucket `maple-expectation…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1288.
- commits: 27 [6550449, 029aeeb, 0d6e07c, b4b9b71, 8028c6f, 781c55e, 2b11f93, 31451a0, … +19]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 24 [ADDED=19, MODIFIED=4, RENAMED=1; +2894/-42]. Sample: ADDED `.env.bootstrap.template` +11/-0; ADDED `.env.calculator.template` +4/-0; ADDED `.env.cleanup.template` +4/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1293 — chore(ext-api): bump heap -Xmx1g to -Xmx2g
- author zbnerd; closed; created 2026-06-16T08:05:12Z; closed 2026-06-16T08:05:36Z; merged yes/2026-06-16T08:05:36Z; merge commit 9cf1195a83f051033b8140cebd3ddd4f4335eb9c. Body: ## Summary - `scripts/systemd/maple-external-api.service`: ExecStart `-Xmx1g` → `-Xmx2g` - `.claude/skills/pipeline-test/SKILL.md`…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1293.
- commits: 8 [b8626f0, 2976c17, f416b8a, d4986f7, cc7c0de, 6a081b1, 2e779d0, a76ff88]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 14 [ADDED=5, MODIFIED=9; +340/-92]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +70/-35; MODIFIED `.gitignore` +4/-1; MODIFIED `docker-compose.airflow.yml` +36/-27. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1294 — fix(race+throughput): close 3 throughput-limiting gaps
- author zbnerd; closed; created 2026-06-16T08:07:51Z; closed 2026-06-16T08:08:15Z; merged yes/2026-06-16T08:08:15Z; merge commit b8d2fb9d94ab6971dfd131bdc862ab8bdd8267c6. Body: ## Summary 3 independent fixes that together restore item-equipment throughput to 150 files/s (from 102). All verified end-to-end …
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1294.
- commits: 1 [ecee745]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +309/-41]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +54/-19; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/config/PipelineProperties.kt` +7/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/runstate/CalculatorCurrentRunIdHolder.kt` +45/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1295 — chore(release): merge release-0.6.16 into master
- author zbnerd; closed; created 2026-06-16T08:13:59Z; closed 2026-06-16T08:14:20Z; merged yes/2026-06-16T08:14:20Z; merge commit 46e6f0d1f12690dec49b4f22daa3501bea0abb3c. Body: ## Summary - release-0.6.16 cut from develop - Includes all develop commits since release-0.6.12 - Throughput improvements verifie…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1295.
- commits: 13 [f09fc9d, b8626f0, 2976c17, f416b8a, d4986f7, cc7c0de, 6a081b1, 2e779d0, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 42 [ADDED=24, MODIFIED=17, RENAMED=1; +3541/-173]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +70/-35; ADDED `.env.bootstrap.template` +11/-0; ADDED `.env.calculator.template` +4/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1297 — hotfix(airflow): fix cleanup DAG connection + add ensure script
- author zbnerd; closed; created 2026-06-17T04:13:11Z; closed 2026-06-17T04:13:23Z; merged yes/2026-06-17T04:13:23Z; merge commit 29c275f1f6560e963e0e7b3c8345366c0e7d94e3. Body: ## Summary Fix `daily_cleanup_pipeline` DAG that has been failing for 6+ cycles (since 2026-06-15 12:00 UTC) because: 1. The `clea…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1297.
- commits: 1 [4b5944b]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +84/-2]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +16/-2; ADDED `scripts/airflow-ensure-connections.sh` +68/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1298 — fix(compose): override MINIO_ENDPOINT for in-container minio-bootstrap
- author zbnerd; closed; created 2026-06-17T06:52:11Z; closed 2026-06-17T07:35:00Z; merged yes/2026-06-17T07:35:00Z; merge commit afbb7da4e60e4e37f35ba8a1f52e76c18f92320b. Body: ## Summary Add `environment.MINIO_ENDPOINT=http://minio:9000` to `minio-bootstrap` service in docker-compose.yml. The `.env.bootst…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1298.
- commits: 4 [e0b9a2a, a260709, 998c0bf, 939121a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [MODIFIED=4; +7/-3]. Sample: MODIFIED `docker-compose.yml` +4/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/config/NexonHttpClientProperties.kt` +1/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1299 — feat(ext-api): per-phase HTTP trigger endpoint (#1289)
- author zbnerd; closed; created 2026-06-18T05:04:49Z; closed 2026-06-18T05:05:19Z; merged yes/2026-06-18T05:05:19Z; merge commit 40156cfa74c51a61db412ed4bc60ca6ebcd1700c. Body: ## Summary Closes #1289 — adds `POST /api/internal/trigger/phase/{phaseName}` for standalone per-phase runs. Operators can now re-…
- reviews/discussion: 0 reviews [none]; 0 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1299.
- commits: 20 [c86c21b, 521f6db, cc4bab5, 23d82dc, 29b740a, db242d8, ca05801, 83479ad, … +12]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 22 [ADDED=3, MODIFIED=18, REMOVED=1; +3436/-494]. Sample: ADDED `docs/superpowers/plans/2026-06-18-issue-1289-phase-trigger-endpoint.md` +1750/-0; ADDED `docs/superpowers/specs/2026-06-18-issue-1289-phase-trigger-endpoint-design.md` +193/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1300 — feat(ext-api): phase stop endpoint (#1290)
- author zbnerd; closed; created 2026-06-18T06:54:52Z; closed 2026-06-18T07:19:14Z; merged yes/2026-06-18T07:19:14Z; merge commit a8653e0c7446e09a7504cc20443c2837d7f709e9. Body: ## Summary - Adds `POST /api/internal/stop/phase/{phaseName}` to gracefully halt an in-flight ext-api phase at its chunk/page boun…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1300.
- commits: 21 [28ab2dc, ee9bde8, fa3a88f, f4aa2e9, 05b44ec, 525dbd4, 314c6be, 2221e23, … +13]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 25 [ADDED=9, MODIFIED=16; +2825/-38]. Sample: ADDED `docs/superpowers/plans/2026-06-18-issue-1290-phase-stop-endpoint.md` +1541/-0; ADDED `docs/superpowers/specs/2026-06-18-issue-1290-phase-stop-endpoint-design.md` +306/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` +34/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1301 — sync to dev
- author zbnerd; closed; created 2026-06-18T07:20:51Z; closed 2026-06-18T07:21:07Z; merged yes/2026-06-18T07:21:07Z; merge commit fd7d4caecb34bf2b60c18d07f55e25272f1b2eb4. Body: sync to dev
- reviews/discussion: 0 reviews [none]; 0 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1301.
- commits: 4 [a260709, 998c0bf, 939121a, afbb7da]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [MODIFIED=4; +7/-3]. Sample: MODIFIED `docker-compose.yml` +4/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/config/NexonHttpClientProperties.kt` +1/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SchedulerRateLimiter.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1302 — feat(ext-api): phase infinite-loop endpoint (issue #1291)
- author zbnerd; closed; created 2026-06-18T08:47:40Z; closed 2026-06-18T08:47:51Z; merged yes/2026-06-18T08:47:51Z; merge commit bad931560d53ac93f45ed6515f2ec6e67768c1b9. Body: ## Summary - Add `POST /api/internal/loop/phase/{phaseName}` and `POST /api/internal/stop/loop/phase/{phaseName}` to `module-exter…
- reviews/discussion: 0 reviews [none]; 0 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1302.
- commits: 15 [39f636b, d4b9de4, bcf1cb0, 629eddb, cce00fd, 9bdfe34, 03fb7cf, b90a0c4, … +7]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 20 [ADDED=9, MODIFIED=11; +3447/-40]. Sample: ADDED `docs/superpowers/plans/2026-06-19-issue-1291-loop-endpoint.md` +1906/-0; ADDED `docs/superpowers/specs/2026-06-19-issue-1291-loop-endpoint-design.md` +457/-0; ADDED `module-external-api/src/main/kotlin/maple/externalapi/loop/LoopExecutorConfig.kt` +50/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1303 — feat(airflow): per-phase scope-driven branch in daily DAG (#1292)
- author zbnerd; closed; created 2026-06-18T10:08:06Z; closed 2026-06-18T10:12:00Z; merged yes/2026-06-18T10:12:00Z; merge commit fd5348e79f9a984523ecba21789be3b4cf515b5d. Body: ## Summary Extends `daily_collection_pipeline.py` with a `scope`-driven branch (`branch_on_scope`) so operators can trigger / loop…
- reviews/discussion: 2 reviews [COMMENTED=2]; 5 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1303.
- commits: 17 [b1f02f2, 41d3288, 1ee9a00, 65ade80, dea85b9, d1af370, f598d91, 16c1101, … +9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [ADDED=7, MODIFIED=2; +2925/-2]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +134/-0; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +53/-2; ADDED `docker/airflow/dags/per_phase_tasks.py` +254/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1304 — feat(infra): Lock *Async API — pure CF chain, no task.get()
- author zbnerd; closed; created 2026-06-18T12:53:28Z; closed 2026-06-19T04:16:37Z; merged yes/2026-06-19T04:16:37Z; merge commit 59ae20f8dbc373c5b537d74cf9adde1eb3e62f16. Body: ## Summary Adds async-returning methods to the Lock port. Eliminates all 5 `task.get()` blocking sites in `PostgresAdvisoryLockStr…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1304.
- commits: 18 [97461e1, d51b50d, 3b45569, a2ec8ff, 1f8b1cd, 5cb1d11, 776db9a, f9e5166, … +10]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 19 [ADDED=4, MODIFIED=15; +1523/-214]. Sample: ADDED `docs/05_Reports/2026-06-18-blocking-audit.md` +147/-0; MODIFIED `module-app/src/test/java/maple/expectation/monitoring/MonitoringAlertServiceUnitTest.java` +14/-6; MODIFIED `module-app/src/test/java/maple/expectation/scheduler/PopularCharacterWarmupSchedulerTest.java` +47/-58. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1305 — feat(infra): PGMQ workers — processAsync CF<ProcessOutcome>, no .join
- author zbnerd; closed; created 2026-06-18T14:37:58Z; closed 2026-06-18T21:18:15Z; merged yes/2026-06-18T21:18:14Z; merge commit e0f156c42c33b12633321006e59bfc6035892112. Body: ## Summary Adds async-returning `processAsync(): CF<ProcessOutcome>` to PGMQ workers. Eliminates 8 blocking sites (`.join()` × 4, …
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1305.
- commits: 13 [97461e1, d7cafa2, ad7a24e, 3091195, a449408, e49e0c8, 63123ee, bde2da5, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 20 [ADDED=12, MODIFIED=8; +2079/-189]. Sample: ADDED `docs/05_Reports/2026-06-18-blocking-audit.md` +147/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` +69/-41; ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/ProcessOutcome.kt` +38/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1306 — feat(ext-api): InternalApiController — drop .join(), true fire-and-forget
- author zbnerd; closed; created 2026-06-18T22:07:42Z; closed 2026-06-18T22:08:12Z; merged yes/2026-06-18T22:08:12Z; merge commit f82bd83088a42870f6a57c1726abe73cb815197e. Body: ## Summary Removes \`.join()\` from \`InternalApiController\` trigger endpoints (lines 83, 123) so the controller returns 202 imme…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1306.
- commits: 2 [4413af3, 54924b9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +200/-2]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` +2/-2; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/runstatus/InternalApiControllerTest.kt` +122/-0; ADDED `module-external-api/src/test/kotlin/maple/externalapi/test/ExtApiBlockingPrimitiveGateTest.kt` +76/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1307 — feat(ext-api): ChunkFileManager/Sink closeAsync — no all.get(10min)
- author zbnerd; closed; created 2026-06-19T03:26:06Z; closed 2026-06-19T03:26:28Z; merged yes/2026-06-19T03:26:28Z; merge commit 5e5a1f2f1b9807f17ceda2f068befbc8ad5c79d2. Body: ## Summary Converts the 10-minute hard blocking `all.get(600_000L, TimeUnit.MILLISECONDS)` in `ChunkFileManager.awaitAllUploads` t…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1307.
- commits: 2 [b259918, a801ba6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=1, MODIFIED=6; +281/-34]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` +13/-15; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/ItemEquipmentFetchPhase.kt` +13/-15; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1308 — feat(sync): BackpressureLimiter cancel-safety + runBlocking cleanup in consumer/ranking
- author zbnerd; closed; created 2026-06-19T04:06:25Z; closed 2026-06-19T04:06:42Z; merged yes/2026-06-19T04:06:42Z; merge commit a04c864578102ab1d42b576e3bbe2d0b14744433. Body: ## Summary Eliminates the remaining 5 blocking primitive sites from the audit: 1. `UrgentCharacterRequestConsumer` permit-leak ris…
- reviews/discussion: 0 reviews [none]; 0 review comments; 3 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1308.
- commits: 2 [8b56bdc, 2edf07d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=1, MODIFIED=4; +136/-27]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/urgent/UrgentCharacterRequestConsumer.kt` +8/-1; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/test/ExtApiBlockingPrimitiveGateTest.kt` +3/-2; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` +12/-13. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1309 — feat(ext-api): OrphanTempFileCleanupHook — boot sweep of orphan gzip-chunk-*.tmp (#1296)
- author zbnerd; closed; created 2026-06-19T05:09:51Z; closed 2026-06-19T05:51:35Z; merged yes/2026-06-19T05:51:35Z; merge commit 74a65d50a571280eb6b4433bd79a5df6895db01e. Body: ## Summary - Adds `OrphanTempFileCleanupHook` (Spring `ApplicationRunner`) that deletes orphan `gzip-chunk-*.tmp` files older than…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1309.
- commits: 7 [c8f786c, 515f996, 9f5cad7, a38e6a3, d47a35f, 55e4677, 66f16f9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=4; +1266/-0]. Sample: ADDED `docs/superpowers/plans/2026-06-19-ext-api-orphan-tmp-cleanup.md` +641/-0; ADDED `docs/superpowers/specs/2026-06-19-ext-api-orphan-tmp-cleanup-design.md` +320/-0; ADDED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/OrphanTempFileCleanupHook.kt` +113/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1315 — perf(pipeline): cap direct buffer memory at 512MB
- author zbnerd; closed; created 2026-06-19T15:36:43Z; closed 2026-06-19T15:37:13Z; merged yes/2026-06-19T15:37:13Z; merge commit aed47f55b24fa1cc4e2de0126ec16ed318e93c49. Body: ## Summary - Cap JVM direct buffer memory at 512MB on `module-external-api` and `module-calculator` via `-XX:MaxDirectMemorySize=5…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1315.
- commits: 4 [650cf79, f16a4c2, 522ef07, 6cadc2d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=3, MODIFIED=2; +1570/-0]. Sample: ADDED `docker/prometheus/rules/offheap-alerts.yml` +18/-0; ADDED `docs/superpowers/plans/2026-06-19-offheap-streaming.md` +1212/-0; ADDED `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` +322/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1316 — perf(pipeline): tune Netty/Kafka direct buffer pools (issue #1314)
- author zbnerd; closed; created 2026-06-19T16:35:03Z; closed 2026-06-19T16:35:14Z; merged yes/2026-06-19T16:35:14Z; merge commit 7adbe50aafa93c12209fa60ae10e859666e33303. Body: ## Summary Phase 5 of the off-heap streaming plan. Config-only tuning to fit within the 512MB direct memory cap from Phase 1 (#131…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1316.
- commits: 9 [cca3dfb, 28dca3e, 266ba6c, 9530e49, 5a8119d, 5737218, d595548, 9cab09d, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=2, MODIFIED=8; +581/-2]. Sample: MODIFIED `build.gradle` +3/-1; ADDED `docs/superpowers/plans/2026-06-19-issue-1314-direct-buffer-tuning.md` +285/-0; MODIFIED `docs/superpowers/plans/2026-06-19-offheap-streaming.md` +9/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1317 — perf(ext-api): streaming JSONL parser for chunk payloads (#1313)
- author zbnerd; closed; created 2026-06-19T17:17:06Z; closed 2026-06-19T17:23:39Z; merged yes/2026-06-19T17:23:39Z; merge commit 69690f51c6bac7937427ed791e30e7219316be72. Body: Resolves #1313. Replaces per-line `objectMapper.readTree(line)` with a shared line-bounded `StreamingChunkParser` (Jackson + `Buff…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1317.
- commits: 21 [5545a89, 35fdaba, fb922b9, c796695, d373f80, 8189aae, 57cffc2, 1ac566e, … +13]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 21 [ADDED=12, MODIFIED=9; +5811/-121]. Sample: ADDED `docs/superpowers/plans/2026-06-19-issue-1312-streaming-writer-cf-chain.md` +1381/-0; ADDED `docs/superpowers/plans/2026-06-19-issue-1313-streaming-chunk-parser.md` +1234/-0; ADDED `docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md` +1692/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1318 — feat(calculator): streaming gzip -> S3 via CF chain (issue #1312)
- author zbnerd; closed; created 2026-06-19T17:35:48Z; closed 2026-06-19T17:40:06Z; merged yes/2026-06-19T17:40:06Z; merge commit 85b5528df557377675b080edfe7d6515c9993461. Body: ## Summary Replace the legacy ByteArrayOutputStream buffering in CalculationResultWriter with a CF chain (Flow -> gzip -> 8MB pipe…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1318.
- commits: 32 [9f73189, 83e060a, a4f912d, 8f8deca, 8e2f533, f7c1ddd, d0a234a, d055377, … +24]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 22 [ADDED=7, MODIFIED=15; +662/-169]. Sample: MODIFIED `docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md` +40/-82; MODIFIED `docs/superpowers/specs/2026-06-19-issue-1313-streaming-chunk-parser-design.md` +0/-9; MODIFIED `docs/superpowers/specs/2026-06-19-offheap-calculator-cache-design.md` +2/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1319 — perf(calculator): off-heap OCID cache via OffHeapSerializedBackend (#1311)
- author zbnerd; closed; created 2026-06-19T17:37:14Z; closed 2026-06-19T17:41:40Z; merged yes/2026-06-19T17:41:40Z; merge commit c0a163928a47a3fdecec16413bc925d1233825bf. Body: ## Summary Replaces heap-resident Caffeine OCID lookup cache with off-heap-backed storage. Achieves issue #1311's primary AC of re…
- reviews/discussion: 0 reviews [none]; 0 review comments; 2 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1319.
- commits: 28 [688067a, e16e8be, 594f427, 8de1564, 15f9981, 2642ee1, 0be5227, 68d2190, … +20]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 25 [ADDED=11, MODIFIED=14; +709/-101]. Sample: MODIFIED `build.gradle` +1/-3; ADDED `docker/prometheus/rules/cache-backend-alerts.yml` +18/-0; MODIFIED `docs/superpowers/plans/2026-06-19-offheap-calculator-cache.md` +7/-7. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1320 — refactor(ext-api): OcidLookupPhase putStream -> putStreamMultipart (issue #1319)
- author zbnerd; closed; created 2026-06-19T17:52:42Z; closed 2026-06-19T17:52:50Z; merged yes/2026-06-19T17:52:50Z; merge commit 4c8af3a7ad585cea68614324ff9fbc7e00871aba. Body: ## Summary Migrate OcidLookupPhase to the CF-based putStreamMultipart API. Eliminates the heap-draining readBytes() path inside pu…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1320.
- commits: 1 [b53a396]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +42/-19]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` +27/-10; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` +15/-9. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1321 — perf(ext-api): producer-side serialize + OCID read-through (ADR-729)
- author zbnerd; closed; created 2026-06-20T05:33:59Z; closed 2026-06-20T07:28:43Z; merged yes/2026-06-20T07:28:43Z; merge commit 474c59d9685f77322583a16cd54280e95036f5bb. Body: ## Summary ITEM_EQUIPMENT loop throughput gap (~98 vs reference 150-160 files/s) addressed with two independent moves. See ADR-729…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1321.
- commits: 4 [b53a396, b733a8d, c7b20f4, 303eab5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=1, MODIFIED=10; +352/-54]. Sample: ADDED `docs/01_ADR/ADR-729-ext-api-item-equipment-loop-throughput.md` +104/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` +12/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt` +24/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1322 — fix(ext-api): ITEM_EQUIPMENT uses OCID_LOOKUP runId, not CHARACTER_BASIC
- author zbnerd; closed; created 2026-06-21T05:17:21Z; closed 2026-06-21T05:17:46Z; merged yes/2026-06-21T05:17:46Z; merge commit d34533546403286bd6fd8c57e60d5fb59d8d5b48. Body: ## Summary - `ExternalApiScheduler` chained `ITEM_EQUIPMENT` after `CHARACTER_BASIC` but passed `cbRunId` as upstream runId. `Ocid…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1322.
- commits: 1 [e4dc0dc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +6/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +6/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1323 — fix(ci): ci.yml context + tests + minio-it container workflow
- author zbnerd; closed; created 2026-06-21T05:22:21Z; closed 2026-06-21T07:19:35Z; merged yes/2026-06-21T07:19:35Z; merge commit 2f1a063ea013b25f6ae4bb4c5d5b1d27a5e0b36e. Body: ## Summary Restores CI green from a 9-day regression on every PR/develop run. ## Fix 1 — \`ci.yml\` job-level env context error \`…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1323.
- commits: 11 [8d86a54, 5a41003, 869f57d, 0110f74, c512beb, be21f6b, e48ccf3, 79363bd, … +3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +85/-59]. Sample: MODIFIED `.github/workflows/ci.yml` +53/-33; MODIFIED `module-cleanup/src/test/kotlin/maple/cleanup/controller/CleanupControllerTest.kt` +7/-0; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` +25/-26. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1324 — feat(services): containerize 4 Spring Boot services + Airflow DNS switch
- author zbnerd; closed; created 2026-06-22T02:54:28Z; closed 2026-06-22T03:48:55Z; merged yes/2026-06-22T03:48:55Z; merge commit 556017399c468a033a55274857d1aedaeb6b8765. Body: ## Summary - **entrypoint wrapper** reads \`MINIO_SECRET_KEY_FILE\` (Docker secret file mount at \`/run/secrets/sa-<module>\`) and…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1324.
- commits: 14 [f4b3fc6, 71bb1c1, ffcf4a8, d947cd8, bb1e84a, f20eb72, 86145a0, 9dabbfa, … +6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 26 [ADDED=3, MODIFIED=18, REMOVED=5; +574/-115]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +67/-30; REMOVED `.env.bootstrap.template` +0/-11; REMOVED `.env.calculator.template` +0/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1325 — fix(calculator): drain result writer to temp file (empty result-part files)
- author zbnerd; closed; created 2026-06-22T05:25:51Z; closed 2026-06-22T05:37:57Z; merged yes/2026-06-22T05:37:57Z; merge commit 205ce14b814b98cf1533a8072d1393dca9cfd310. Body: ## Summary - `CalculationResultWriter` produced **0-row (empty)** `result-part-*.jsonl.gz` on every item-equipment chunk → synchro…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1325.
- commits: 1 [7f74801]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=1, MODIFIED=3; +178/-74]. Sample: ADDED `docs/01_ADR/ADR-730_calculator-writer-temp-file-upload.md` +97/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt` +68/-64; MODIFIED `module-calculator/src/test/kotlin/maple/calculator/writer/CalculationResultWriterTest.kt` +1/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1326 — feat(coolify): self-healing design + Phase 1 (infra under Coolify + autoheal)
- author zbnerd; closed; created 2026-06-22T06:46:18Z; closed 2026-06-22T06:59:38Z; merged yes/2026-06-22T06:59:38Z; merge commit 991a3fa51cb7ebd0628b1bd84d3e098bc8d4959d. Body: ## Summary Brings the infra stack under Coolify as a self-healing Docker Compose resource and closes the soft-failure self-healing…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1326.
- commits: 9 [939dcc4, a10f000, 12e8832, 3f7fff0, 27228cf, ae44d59, 33af3c5, 510a002, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=5, MODIFIED=2; +2373/-4]. Sample: MODIFIED `.github/workflows/ci.yml` +9/-0; MODIFIED `docker-compose.yml` +83/-4; ADDED `docs/01_ADR/ADR-731_coolify-self-healing-infra.md` +87/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1327 — feat(coolify): Phase 2 — apps under Coolify + CI->GHCR image pipeline
- author zbnerd; closed; created 2026-06-22T07:15:24Z; closed 2026-06-22T07:29:06Z; merged yes/2026-06-22T07:29:06Z; merge commit 2d20987c25ab094bb01ecb2096aed1eea3b06d96. Body: ## Summary Brings the 4 Spring Boot services under Coolify with the same 3-layer self-healing as the infra layer, and establishes …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1327.
- commits: 5 [38bd6db, 04fd62b, 505557b, 0c21556, bac9c5f]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=1, MODIFIED=4; +198/-15]. Sample: MODIFIED `.github/workflows/ci.yml` +50/-0; MODIFIED `docker-compose.services.yml` +40/-8; MODIFIED `docker-compose.yml` +11/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #1328 — feat(coolify): Phase 3 — cAdvisor restart alert + promtail docker_sd + ops guide
- author zbnerd; closed; created 2026-06-22T07:36:51Z; closed 2026-06-22T07:51:48Z; merged yes/2026-06-22T07:51:48Z; merge commit 238d40fa7a21c39675ab31bd4ade77ee35b5aa4b. Body: ## Summary Closes out the Coolify self-healing rollout — observability (cAdvisor restart alert + opt-in container stdout via promt…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1328.
- commits: 4 [b3ce359, b874c54, 1221abc, a9f3ece]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 6 [ADDED=2, MODIFIED=4; +247/-1]. Sample: MODIFIED `docker-compose.yml` +28/-0; MODIFIED `docker/prometheus/prometheus.yml` +13/-0; MODIFIED `docker/prometheus/rules/alert_rules.yml` +16/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1329 — feat(airflow): phase-separated DAGs (5 single-purpose DAGs)
- author zbnerd; closed; created 2026-06-22T13:43:46Z; closed 2026-06-22T13:43:57Z; merged yes/2026-06-22T13:43:57Z; merge commit a46ba59e4294f04c4a6359626b01b4e20f517330. Body: ## Summary Replace `daily_collection_pipeline` (one DAG, 3 workflow intents via JSON `scope`/`steps`) with 5 single-purpose DAGs: …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1329.
- commits: 23 [b3ce359, b874c54, 1221abc, a9f3ece, 3fd6d38, c44cf02, 3239ce4, d0edb6f, … +15]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 22 [ADDED=15, MODIFIED=7; +5058/-17]. Sample: MODIFIED `docker-compose.yml` +28/-0; ADDED `docker/airflow/dags/character_basic_pipeline.py` +15/-0; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +15/-7. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1330 — fix(airflow): gate phase DAGs on upstream terminal (PR #1329 follow-up)
- author zbnerd; closed; created 2026-06-22T17:44:12Z; closed 2026-06-22T17:44:20Z; merged yes/2026-06-22T17:44:20Z; merge commit 3e81c8f739ba85c2c2c2f9e92ee507653237ab34. Body: ## Summary - `phase_pipeline_factory.make_phase_dag(phase, dag_id, upstream_phase=None)` — new optional parameter that wires a `wa…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1330.
- commits: 1 [d69eedb]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [MODIFIED=4; +279/-2]. Sample: MODIFIED `docker/airflow/dags/character_basic_pipeline.py` +1/-0; MODIFIED `docker/airflow/dags/item_equipment_pipeline.py` +1/-0; MODIFIED `docker/airflow/dags/phase_pipeline_factory.py` +124/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1331 — feat(pipeline-test): docker-first startup + Airflow DAG pytest wrapper
- author zbnerd; closed; created 2026-06-22T23:18:30Z; closed 2026-06-22T23:18:36Z; merged yes/2026-06-22T23:18:36Z; merge commit f7a66ddd0a329963c1896eca25e24b7f8d2aee7e. Body: ## Summary - **Skill**: new step `1b. Start docker services (docker-first)` runs `docker compose up airflow-db airflow-webserver a…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1331.
- commits: 1 [79cf260]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +100/-10]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +32/-10; MODIFIED `.gitignore` +1/-0; ADDED `scripts/run-pipeline-tests.sh` +67/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #1332 — fix(pipeline-test): guard docker mode on PR #1324 infra files
- author zbnerd; closed; created 2026-06-22T23:40:58Z; closed 2026-06-22T23:41:04Z; merged yes/2026-06-22T23:41:04Z; merge commit 274926a7c3f5bb251e3ba53f3f21aaa38e3fd675. Body: ## Summary - Add fail-fast guard at start of `START_MODE=docker` branch in step 3: if `docker-compose.services.yml` or `docker/ser…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1332.
- commits: 1 [2a2068e]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +16/-0]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +16/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1333 — fix(airflow): add .airflowignore to tests/ to silence import errors
- author zbnerd; closed; created 2026-06-23T02:52:24Z; closed 2026-06-23T02:52:26Z; merged yes/2026-06-23T02:52:26Z; merge commit 90787ad76e6c361ecea3ac63c4d742e681a3cda8. Body: ## Summary - Add `docker/airflow/dags/tests/.airflowignore` with content `.*` (RE2 regex) so the Airflow scheduler skips the pytes…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1333.
- commits: 1 [1305143]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +1/-0]. Sample: ADDED `docker/airflow/dags/tests/.airflowignore` +1/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1334 — fix(pipeline-test): install kafka-python-ng in webserver too
- author zbnerd; closed; created 2026-06-23T03:01:07Z; closed 2026-06-23T03:01:10Z; merged yes/2026-06-23T03:01:10Z; merge commit a5be8f0403b0080e05b249dc28a44c76537c73df. Body: ## Summary - Step 5 of `pipeline-test` skill now installs `kafka-python-ng` in **both** scheduler and webserver containers. ## Bac…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1334.
- commits: 1 [87eda2d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +7/-1]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +7/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1335 — fix(airflow): per-phase gate passes on lastCompletedByPhase.terminal
- author zbnerd; closed; created 2026-06-23T03:12:21Z; closed 2026-06-23T03:12:24Z; merged yes/2026-06-23T03:12:24Z; merge commit 83d0c9f1bbe7e9a343fc71c94c3486b54c847ea3. Body: ## Summary - `_wait_phase_terminal_fn` in `phase_pipeline_factory.py` now also passes when `current=null` AND `lastCompletedByPhas…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1335.
- commits: 1 [f203688]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +14/-0]. Sample: MODIFIED `docker/airflow/dags/phase_pipeline_factory.py` +14/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1336 — fix(airflow): phase gate also checks lastCompletedByPhase (correct placement)
- author zbnerd; closed; created 2026-06-23T03:18:42Z; closed 2026-06-23T03:18:45Z; merged yes/2026-06-23T03:18:45Z; merge commit 483ce2f676df03b5ea196f1ea015aeaf99258549. Body: ## Summary - Move the `lastCompletedByPhase.terminal` check inside the `if not current_run_id` block (previous PR placed it after …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1336.
- commits: 1 [f7a4e51]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +12/-0]. Sample: MODIFIED `docker/airflow/dags/phase_pipeline_factory.py` +12/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1422 — feat(airflow): 3am KST morning_chain_pipeline orchestration DAG
- author zbnerd; closed; created 2026-06-23T08:30:08Z; closed 2026-06-23T09:33:35Z; merged yes/2026-06-23T09:33:35Z; merge commit 4cd7e13df6178d3dfed845770983f7b7cf9ec437. Body: ## Summary - New Airflow master DAG `morning_chain_pipeline` chains the 4 existing per-phase DAGs at 03:00 KST (cron `0 18 * * *` …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1422.
- commits: 23 [9f73189, 83e060a, a4f912d, 8f8deca, 8e2f533, f7c1ddd, d0a234a, d055377, … +15]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=3; +371/-0]. Sample: ADDED `docker/airflow/dags/morning_chain_pipeline.py` +138/-0; ADDED `docker/airflow/dags/tests/test_morning_chain_pipeline.py` +130/-0; ADDED `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md` +103/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1432 — docs: CLAUDE precedence directive + analytics investigation artifacts
- author zbnerd; closed; created 2026-06-24T12:55:18Z; closed 2026-06-24T12:55:37Z; merged yes/2026-06-24T12:55:37Z; merge commit ca2132b97822f6cab560ea7a68fa72fd46930349. Body: ## Summary - **CLAUDE.md**: 맨 상단에 선제 참조 지시문 추가 — 외운 지식·LLM 기본 동작보다 CLAUDE.md 와 `.claude/rules/` 를 먼저 참조. "항상" 규칙이 매 세션 자동 로드됨을 명시해…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1432.
- commits: 2 [ddd52de, f4da537]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 19 [ADDED=18, MODIFIED=1; +3838/-0]. Sample: MODIFIED `CLAUDE.md` +8/-0; ADDED `docs/01_ADR/ADR-735-future-analytics-platform-evaluation.md` +165/-0; ADDED `docs/superpowers/plans/2026-06-23-3am-pipeline-chain.md` +589/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1433 — fix(external-api): disable legacy daily cron (ADR-736) + Endurance Test #2
- author zbnerd; closed; created 2026-06-25T23:26:59Z; closed 2026-06-25T23:30:25Z; merged yes/2026-06-25T23:30:25Z; merge commit 75834365ed631b86fb5d9293af44b67b7d1cd591. Body: ## Root cause — daily-rollover dual orchestration Endurance Test #2 (~71h) reproduced a 03:00 KST crash twice (06-25, 06-26). Code…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1433.
- commits: 1 [85229a4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=2, MODIFIED=2, RENAMED=1; +313/-7]. Sample: ADDED `docs/01_ADR/ADR-736_disable-legacy-daily-cron.md` +96/-0; ADDED `docs/endurance-test/endurance-report-71h.md` +217/-0; RENAMED `docs/endurance-test/endurance-report-82h.md` +0/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1434 — feat(ops): nohup→docker deploy + autoheal/cadvisor (#1428-1431)
- author zbnerd; closed; created 2026-06-26T00:16:31Z; closed 2026-06-26T00:16:47Z; merged yes/2026-06-26T00:16:47Z; merge commit 93e2e229a84c33a684c8e0d2c61f59bdeeca4df7. Body: ## Summary - 4 active 모듈(external-api/calculator/synchronizer/cleanup) nohup → docker compose 전환 - autoheal 가동 (4 app 컨테이너 라벨 기존 존…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1434.
- commits: 6 [3dcb5b6, d71a658, 2f2e09d, b395132, 0e120ea, ea2f420]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=5; +1066/-0]. Sample: ADDED `docker/services/deploy-apps.sh` +168/-0; ADDED `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` +101/-0; ADDED `docs/21_Operations/docker-deploy-runbook.md` +130/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1436 — docs(skills): adapt pipeline-test to docker deployment
- author zbnerd; closed; created 2026-06-26T00:30:23Z; closed 2026-06-26T00:30:34Z; merged yes/2026-06-26T00:30:33Z; merge commit 53af9ec1395f91578ebc7aa21aaa0e11242983e1. Body: ## Summary After #1428 (nohup→docker), the pipeline-test skill had host/nohup assumptions that broke for containerized modules. ##…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1436.
- commits: 1 [ec8605d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +25/-6]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +25/-6. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #1437 — fix(airflow): DB reach repair + scheduler healthcheck + autoheal (#1435)
- author zbnerd; closed; created 2026-06-26T01:05:37Z; closed 2026-06-26T01:05:58Z; merged yes/2026-06-26T01:05:58Z; merge commit 60e6134d8b2cf63a64ec307626d8fa139d83b086. Body: ## Summary Resolves #1435. Root cause: host-network airflow scheduler couldn't resolve `airflow-db` DNS (bridge, no port publish) …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1437.
- commits: 7 [f036dda, 56f288b, fe319aa, 2022abc, 37d4e10, 2601122, 5144b83]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=3, MODIFIED=2; +630/-11]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +2/-2; MODIFIED `docker-compose.airflow.yml` +14/-9; ADDED `docs/01_ADR/ADR-738_airflow-db-port-publish.md` +100/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1438 — Release 0.6.26
- author zbnerd; closed; created 2026-06-26T04:51:12Z; closed 2026-06-26T04:51:35Z; merged yes/2026-06-26T04:51:35Z; merge commit cc1714d55690303a123ae7fb7a8cdd055fdb2f0b. Body: Release 0.6.26 (2026-06-26). ## Contents (since last release) - #1428 nohup→docker compose deploy (4 modules) - #1429 autoheal act…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1438.
- commits: 247 [c86c21b, 521f6db, cc4bab5, 23d82dc, 29b740a, db242d8, ca05801, 83479ad, … +239]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 250 [ADDED=152, MODIFIED=91, REMOVED=6, RENAMED=1; +30796/-1437]. Sample: MODIFIED `.claude/skills/pipeline-test/SKILL.md` +411/-44; REMOVED `.env.bootstrap.template` +0/-11; REMOVED `.env.calculator.template` +0/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1439 — docs(readme): rewrite README with ETL pipeline architecture
- author zbnerd; closed; created 2026-06-26T05:01:57Z; closed 2026-06-26T05:02:55Z; merged yes/2026-06-26T05:02:54Z; merge commit 5351f6db596769a727aa66b61c0bb949ea8f9af8. Body: ## Summary - README 전면 재작성. ETL 파이프라인 아키텍처 다이어그램을 전면에 배치 (가독성 중심) - Control plane (Airflow morning_chain, 03:00 KST) + 4-module da…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1439.
- commits: 1 [5f28d96]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +126/-142]. Sample: MODIFIED `README.md` +126/-142. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1440 — docs(readme): strip performance narrative (re-apply lost commit)
- author zbnerd; closed; created 2026-06-26T05:09:50Z; closed 2026-06-26T05:10:05Z; merged yes/2026-06-26T05:10:05Z; merge commit 1c3eec4d647f9cd0898b84842027c4e394348533. Body: ## Summary - #1439 머지 시 `gh pr merge` 가 첫 커밋(성능 서사 버전)만 머지하고 strip 커밋(d16872490)을 누락하는 anomaly 발생 → develop README 에 성능 서사 잔존 - st…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1440.
- commits: 1 [c522c7f]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +27/-96]. Sample: MODIFIED `README.md` +27/-96. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1441 — fix(airflow): morning_chain loop-started sensor condition (iterationCount -> status)
- author zbnerd; closed; created 2026-06-26T09:55:15Z; closed 2026-06-26T10:21:02Z; merged yes/2026-06-26T10:21:02Z; merge commit f749702ec2437bdb2892cc95fab53e41b3d2fd2c. Body: ## Problem `wait_first_iteration_started` sensor gated on `iterationCount >= 1`. That counter increments only on iteration **compl…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1441.
- commits: 1 [dd99220]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +105/-8]. Sample: MODIFIED `docker/airflow/dags/morning_chain_pipeline.py` +9/-8; ADDED `docs/01_ADR/ADR-739_loop-started-sensor-condition.md` +96/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1442 — fix(airflow): retire daily_full_pipeline (schedule=None, morning_chain owns 03:00 KST)
- author zbnerd; closed; created 2026-06-27T13:05:38Z; closed 2026-06-27T13:06:57Z; merged yes/2026-06-27T13:06:57Z; merge commit bd2974ded7b0285755f81764fe338c1c248a2e08. Body: ## Problem `daily_full_pipeline` and `morning_chain_pipeline` both scheduled `0 18 * * *` (03:00 KST) → competed for same phase sl…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1442.
- commits: 1 [1ec76cc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +101/-4]. Sample: MODIFIED `docker/airflow/dags/daily_full_pipeline.py` +10/-4; ADDED `docs/01_ADR/ADR-740_retire-daily-full-pipeline.md` +91/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1443 — ops(log): increase app module log retention to 500m for loop lifecycle diagnosis
- author zbnerd; closed; created 2026-06-27T13:25:07Z; closed 2026-06-27T13:28:03Z; merged yes/2026-06-27T13:28:03Z; merge commit 5d52bba61978f76a49f5ec76762abe19e95518f4. Body: ## Problem 4 app modules used daemon-default log config (30m cap). external-api item-equipment logs every few seconds → `[Loop]` l…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1443.
- commits: 1 [a23aeac]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +112/-0]. Sample: MODIFIED `docker-compose.services.yml` +20/-0; ADDED `docs/01_ADR/ADR-741_app-log-retention.md` +92/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #1444 — fix(loop): defer iteration when upstream OCID_LOOKUP not ready (prevent daily loop death)
- author zbnerd; closed; created 2026-06-28T05:38:35Z; closed 2026-06-28T05:48:41Z; merged yes/2026-06-28T05:48:41Z; merge commit 0a568e26bbafe9ca61a6300124afbbc00ffd22c7. Body: ## Problem Every 03:00 KST, morning_chain refreshes OCID_LOOKUP. During that window `latestUpstreamRunId` returns null → next loop…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1444.
- commits: 1 [3d1264d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +167/-5]. Sample: ADDED `docs/01_ADR/ADR-742_loop-upstream-defer.md` +98/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/loop/PhaseLoopController.kt` +40/-4; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/loop/PhaseLoopControllerTest.kt` +29/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1445 — docs: add troubleshooting casebook from ai-traces (problem/cause/fix/alternatives)
- author zbnerd; closed; created 2026-06-28T07:50:09Z; closed 2026-06-28T08:02:23Z; merged yes/2026-06-28T08:02:23Z; merge commit 413e7392b0e225a3838dd380aeaf4ff677ca02f1. Body: ## Summary Synthesize `docs/ai-traces/` (110 sessions, 2026-06-09..06-28) + 192 ADRs + git history into a themed **troubleshooting…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1445.
- commits: 2 [e6b61b1, 29bddeb]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=7; +476/-0]. Sample: ADDED `docs/24_Troubleshooting_Casebook/00_index.md` +72/-0; ADDED `docs/24_Troubleshooting_Casebook/01_async_concurrency.md` +66/-0; ADDED `docs/24_Troubleshooting_Casebook/02_memory_streaming.md` +66/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1446 — feat(module-common): add Avro schemas for snapshot/result/ocid-mapping (1425)
- author zbnerd; closed; created 2026-06-28T13:00:32Z; closed 2026-06-28T13:00:39Z; merged yes/2026-06-28T13:00:39Z; merge commit 5858c251ad2b4c1e659ec8a9754c32a2065a6e1b. Body: ## Summary - Avro plugin + parquet-avro deps in module-common - 3 .avsc schema files: ocid-mapping, snapshot, result - schema_vers…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1446.
- commits: 6 [349f73b, 737290f, 550bdd5, 3535179, 385294f, 0a93b54]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 6 [ADDED=5, MODIFIED=1; +1353/-0]. Sample: ADDED `docs/superpowers/plans/2026-06-28-parquet-iceberg-readiness.md` +1155/-0; ADDED `docs/superpowers/specs/2026-06-28-parquet-iceberg-readiness-design.md` +148/-0; MODIFIED `module-common/build.gradle` +8/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #1447 — feat(ext-api): Parquet+ZSTD PoC for OCID mapping (1423)
- author zbnerd; closed; created 2026-06-28T14:03:10Z; closed 2026-06-28T14:03:22Z; merged yes/2026-06-28T14:03:22Z; merge commit c6c2fbdfb5d6814d44cde495d8eff30e332549ab. Body: ## Summary - Adds side-by-side Parquet+ZSTD writer/reader for OCID mapping - OcidLookupPhase writes both gzip JSONL (production) A…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1447.
- commits: 6 [ba32d82, c40deb9, f821e8c, 060c56d, 39dad8c, b18292d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=7, MODIFIED=3; +460/-9]. Sample: ADDED `docs/24_Troubleshooting_Casebook/07_parquet_poc_benchmark.md` +41/-0; MODIFIED `module-external-api/build.gradle` +14/-0; ADDED `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetBenchmark.kt` +82/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #1448 — docs: small-file problem investigation + ADR (1427)
- author zbnerd; closed; created 2026-06-28T14:10:05Z; closed 2026-06-28T14:10:14Z; merged yes/2026-06-28T14:10:14Z; merge commit 4d96bb090e60e4d00d08671f338c865b0af626f2. Body: ## Summary - Measured ~1.2K-2.4K files/run artifact creation at chunk boundaries 2000/500 - Evaluated 4 consolidation approaches (…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1448.
- commits: 1 [ee9cb96]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=2; +265/-0]. Sample: ADDED `docs/01_ADR/ADR-743-small-file-resolution.md` +113/-0; ADDED `docs/02_Investigations/2026-06-28-small-file-measurement.md` +152/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1449 — docs: Iceberg readiness assessment (1426)
- author zbnerd; closed; created 2026-06-28T14:12:32Z; closed 2026-06-28T14:12:42Z; merged yes/2026-06-28T14:12:42Z; merge commit 3c9d05adef87051cebecae79cfb44fe6bbe85892. Body: ## Summary - Iceberg table schema + partition spec per artifact (3 tables) - Catalog comparison + recommendation (REST + Postgres)…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1449.
- commits: 1 [5a3ce0a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +196/-0]. Sample: ADDED `docs/superpowers/specs/2026-06-28-iceberg-adoption-design.md` +196/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1450 — fix(module-cleanup): bind KAFKA_BOOTSTRAP_SERVERS env to spring.kafka.bootstrap-servers
- author zbnerd; closed; created 2026-06-29T00:12:47Z; closed 2026-06-29T00:13:30Z; merged yes/2026-06-29T00:13:30Z; merge commit 38f9b32d871bb94320f902a144cdbb1550ba1655. Body: ## Summary - module-cleanup's @KafkaListener (cleanup-inbox group) had no `spring.kafka.bootstrap-servers` binding in application.…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1450.
- commits: 1 [4526237]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +2/-0]. Sample: MODIFIED `module-cleanup/src/main/resources/application.yml` +2/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1451 — docs(reports): throughput ceiling endurance test report (2026-07-02)
- author zbnerd; closed; created 2026-07-01T23:42:57Z; closed 2026-07-01T23:43:24Z; merged yes/2026-07-01T23:43:24Z; merge commit a72c2bcf96d5b036c11d59a36fe2d3ce4fa2ce24. Body: ## Summary - 80h endurance 관측 결과 100-150 users/s 천장 확정 - Bottleneck = `ChunkedSnapshotSink` 단일 writer thread (in-process queue 300…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1451.
- commits: 1 [bd952d5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +264/-0]. Sample: ADDED `docs/05_Reports/05_06_Load_Tests/ENDURANCE_THROUGHPUT_CEILING_20260702.md` +264/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1452 — perf(external-api): tune sink drain (Kafka batching + MinIO multipart + chunk idle flush)
- author zbnerd; closed; created 2026-07-01T23:59:28Z; closed 2026-07-02T03:18:49Z; merged no; merge commit aff3135ba34c2ebcb8ac391b1c754ce9cc745db2. Body: ## Summary ADR-744. Three independent tunings targeting the sink drain bottleneck identified in the 2026-07-02 endurance report. -…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1452.
- commits: 1 [c2c1bbc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=1, MODIFIED=9; +185/-4]. Sample: ADDED `docs/01_ADR/ADR-744-sink-throughput-tuning.md` +106/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` +1/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` +21/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: closed without merge; evidence is not treated as applied. Portfolio: 성능.

### PR #1453 — perf(ext-api): default gzip chunk writer to BEST_SPEED
- author zbnerd; closed; created 2026-07-02T16:25:21Z; closed 2026-07-02T23:32:13Z; merged yes/2026-07-02T23:32:13Z; merge commit 15c9396dbb303d667cc14c370a32f2e5b3298511. Body: ## Summary - Snapshot writer thread is single-threaded gzip on the hot path; item-equipment payloads are large (avg 218KB, 98% >10…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1453.
- commits: 1 [7c120d6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +116/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` +3/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriter.kt` +19/-1; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/snapshot/GzipJsonlChunkWriterTest.kt` +94/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #1454 — fix(infra): disable airflow-db parallel query (worker leak)
- author zbnerd; closed; created 2026-07-02T23:42:28Z; closed 2026-07-02T23:43:17Z; merged yes/2026-07-02T23:43:17Z; merge commit 92a9e3545a2dfdf9723b748054ea9933ab18a5a4. Body: ## Summary - airflow-db (postgres metadata) leaks parallel workers → orphaned live workers spin ~700% CPU, recur after restart, in…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1454.
- commits: 1 [655c4fc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +9/-0]. Sample: MODIFIED `docker-compose.airflow.yml` +9/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #842 — fix(synchronizer): resolve user_ign from character_basic_read_model
- author zbnerd; closed; created 2026-05-19T14:56:20Z; closed 2026-05-19T14:57:20Z; merged yes/2026-05-19T14:57:20Z; merge commit bcb546dec060dd1a9e6faafcda769df0fce9f116. Body: ## Summary - `OcidUserIgnResolver`가 `game_character`(2 rows) 대신 `character_basic_read_model`(288K rows)에서 user_ign을 조회하도록 수정 - 이 변…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #842.
- commits: 1 [140ec98]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +1/-1]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #843 — fix(config): remove duplicate bean definitions + update workflow rules
- author zbnerd; closed; created 2026-05-19T15:13:44Z; closed 2026-05-19T15:14:07Z; merged yes/2026-05-19T15:14:07Z; merge commit fbacec414d4fa369f2992d4bb5a8abd9f1c9229e. Body: ## Summary - `CorePortAdapterConfig`(module-app)에서 `CalculationPortConfig`(module-infra)와 중복된 8개 bean 제거 - `BeanDefinitionOverride…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #843.
- commits: 1 [cf8c25c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +23/-91]. Sample: MODIFIED `.claude/rules/workflow-rules.md` +23/-7; MODIFIED `module-app/src/main/java/maple/expectation/config/CorePortAdapterConfig.java` +0/-84. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #844 — feat(external-api): ranking fetch pipeline with daily cron scheduler
- author zbnerd; closed; created 2026-05-20T02:59:49Z; closed 2026-05-20T03:00:00Z; merged yes/2026-05-20T03:00:00Z; merge commit 9a072c4a92a1eb7da8c81520304b3767ecac3090. Body: ## Summary - **RankingFetchPhase**: Nexon 전체 랭킹 API (page 1~N) 비동기 호출 → JSONL gzip 청크 저장 + character_name CSV 덮어쓰기 - **ExternalApi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #844.
- commits: 2 [8c826e5, ed5a167]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=4, MODIFIED=7; +1014/-22]. Sample: ADDED `docs/superpowers/plans/2026-05-20-ranking-fetch-pipeline.md` +578/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/domain/ExternalApiFetchCommand.kt` +2/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt` +11/-9. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #845 — feat(external-api): OCID lookup from ranking gzip chunks, remove CSV
- author zbnerd; closed; created 2026-05-20T03:38:53Z; closed 2026-05-20T03:44:04Z; merged yes/2026-05-20T03:44:04Z; merge commit f7b26e3cc71c228af078e1125172cfe44596d3e3. Body: ## Summary - **OcidLookupPhase reads from ranking gzip JSONL chunks** instead of CSV file - **Remove skip guard** — OCID lookup ru…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #845.
- commits: 5 [2926172, 012fa6d, 03f8c02, 59872bd, ae782a2]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=2, MODIFIED=8, REMOVED=1; +1286/-300116]. Sample: ADDED `docs/superpowers/plans/2026-05-20-ocid-lookup-from-ranking-gzip.md` +722/-0; MODIFIED `module-app/src/main/resources/data/userIgn_List.csv` +400/-300000; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt` +18/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #846 — feat: replace per-file OCID storage with gzip JSONL + Kafka + Synchronizer
- author zbnerd; closed; created 2026-05-20T06:59:49Z; closed 2026-05-20T09:27:48Z; merged yes/2026-05-20T09:27:48Z; merge commit d9abaf8a12c3e917b45ad8e7bdb9d4d1b5c8afcb. Body: ## Summary - Replace OcidLookupPhase per-file OCID storage (594K files, 15+ min load) with single gzip JSONL file (~18MB, <2s load…
- reviews/discussion: 1 reviews [COMMENTED=1]; 3 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #846.
- commits: 13 [69ed4e3, 2c13b4f, 0690aef, 527c181, ea9dc65, 0ee275d, 93645c2, baaad68, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 17 [ADDED=4, MODIFIED=13; +419/-71]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt` +1/-1; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/storage/LocalObjectStorageAdapter.kt` +1/-1; MODIFIED `module-calculator/src/main/resources/application.yml` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 품질.

### PR #847 — fix: unify storage paths, ocid dedup, ranking yesterday date
- author zbnerd; closed; created 2026-05-20T23:23:18Z; closed 2026-05-20T23:23:30Z; merged yes/2026-05-20T23:23:30Z; merge commit 879f8759b65771b5d4eb28c5fe27065b6aee78e3. Body: ## Summary - Unify @Value defaults from `/data/external-api` to `../data` across external-api module (3 files) - Fix `UrgentCharac…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #847.
- commits: 4 [fc34ee9, 8c61b04, 3f5a668, b3baf09]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [MODIFIED=10; +72/-43]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt` +1/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/RankingFetchPhase.kt` +2/-2; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/SnapshotFetchPhase.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #848 — perf(synchronizer): reduce transient memory allocations and fix bootJar mainClass
- author zbnerd; closed; created 2026-05-21T05:51:52Z; closed 2026-05-21T05:52:03Z; merged yes/2026-05-21T05:52:03Z; merge commit bf04b76516fb2d8a6c30a38407cccf8cc001d979. Body: ## Summary - Add `GzipUtils.compress(ByteArray)` overload to skip unnecessary String→ByteArray conversion - BasicChunkFileReader: …
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #848.
- commits: 1 [e2bb64b]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +37/-21]. Sample: MODIFIED `module-calculator/build.gradle` +1/-1; MODIFIED `module-common/src/main/kotlin/maple/expectation/util/GzipUtils.kt` +14/-4; MODIFIED `module-external-api/build.gradle` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #849 — perf(synchronizer): stream BasicChunkFileReader in sub-batches to reduce peak memory
- author zbnerd; closed; created 2026-05-21T05:58:00Z; closed 2026-05-21T05:58:09Z; merged yes/2026-05-21T05:58:09Z; merge commit 94ea2aed084dd8767c9f84ebc615875710273ca0. Body: ## Summary - Add `readInBatches(objectKey, batchSize, handler)` to `BasicChunkFileReader` - Streams gzip JSONL line-by-line, flush…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #849.
- commits: 1 [aa430af]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +49/-5]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` +8/-5; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` +41/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 성능.

### PR #850 — feat(synchronizer): auto-delete consumed chunks via Kafka event + scheduler
- author zbnerd; closed; created 2026-05-22T04:05:12Z; closed 2026-05-22T04:11:01Z; merged yes/2026-05-22T04:11:01Z; merge commit a31647a37121e28547495ba1d53d9556c785cd94. Body: ## Summary - Fix calculator double-path bug (`data/data/calculator/...` → `calculator/...`) - Synchronizer publishes `CHUNK_CONSUM…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #850.
- commits: 2 [4d9ee92, 022304a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=3, MODIFIED=9; +205/-11]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +1/-1; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt` +2/-2; MODIFIED `module-calculator/src/test/kotlin/maple/calculator/CalculatorChunkProcessingCoordinatorTest.kt` +3/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #851 — feat(infra): unified Docker Compose with profiles + app containerization
- author zbnerd; closed; created 2026-05-23T05:58:33Z; closed 2026-05-23T06:00:19Z; merged yes/2026-05-23T06:00:19Z; merge commit 0fba638560cfd29335ce5ad0289b5a7e523ab2e7. Body: ## Summary - Merge 3 compose files → single `docker/compose/docker-compose.yml` with profile separation - Add `docker/Dockerfile.r…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #851.
- commits: 2 [19f30c9, d4d38e9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=8, MODIFIED=2; +1404/-65]. Sample: MODIFIED `.env.example` +23/-3; ADDED `docker/Dockerfile.runtime` +15/-0; ADDED `docker/compose/backup.sh` +32/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #852 — merge Develop to master
- author zbnerd; closed; created 2026-05-26T08:00:59Z; closed 2026-05-26T08:01:16Z; merged yes/2026-05-26T08:01:16Z; merge commit 9b107ccd1c16e0d9dc825f46a024c014417168b1. Body: none
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #852.
- commits: 80 [d9da63b, 1ff54c8, dd8f6b5, 59732a3, 32b0792, 329b91e, 2d22588, 63d3032, … +72]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 262 [ADDED=183, MODIFIED=47, REMOVED=1, RENAMED=31; +438106/-300808]. Sample: ADDED `.claude/rules/adr-conventions.md` +106/-0; MODIFIED `.claude/rules/async-patterns.md` +23/-0; ADDED `.claude/rules/prometheus-metrics.md` +119/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #853 — docs: add 82h endurance report, architecture, and operations documentation
- author zbnerd; closed; created 2026-05-27T00:15:46Z; closed 2026-05-27T00:16:25Z; merged yes/2026-05-27T00:16:25Z; merge commit 26fe587cb9b8164a320419bf459dabe9ff5bf08c. Body: ## Summary - 82-hour endurance test report (60M users, 4B items, 13TB raw data, zero errors) - Pipeline architecture documentation…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #853.
- commits: 1 [6fcf4b3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 6 [ADDED=6; +1034/-0]. Sample: ADDED `docs/README.md` +40/-0; ADDED `docs/architecture.md` +240/-0; ADDED `docs/endurance-report.md` +161/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #854 — refactor(auth): decouple auth from JPA + fix external-api Prometheus 401
- author zbnerd; closed; created 2026-05-27T01:26:16Z; closed 2026-05-27T01:26:25Z; merged yes/2026-05-27T01:26:25Z; merge commit 1dc7b282ef959e9e40f7375643e7cb1a94685679. Body: ## Summary - Extract `JwtPayload`, `JwtParserPort`, `JwtGeneratorPort` to `module-core/auth/` — lightweight modules can now use JW…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #854.
- commits: 9 [cc80121, da6e736, fe49d18, 3ecf1b7, fa69b23, 19f1976, a98c744, 624acd5, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 16 [ADDED=4, MODIFIED=11, REMOVED=1; +520/-131]. Sample: MODIFIED `.claude/rules/code-rules.md` +5/-3; ADDED `docs/superpowers/plans/2026-05-27-auth-decoupling.md` +426/-0; MODIFIED `module-app/src/test/java/maple/expectation/application/service/character/Issues637To644E2ETest.java` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #855 — feat(observability): AI trace logging hooks for agent audit trail
- author zbnerd; closed; created 2026-05-27T01:33:24Z; closed 2026-05-27T01:33:36Z; merged yes/2026-05-27T01:33:36Z; merge commit e4ea10a45e2a8d70b5616749d549db1256b32f04. Body: ## Summary - Session-scoped JSONL trace logging for Claude Code agent observability - 4 hooks: SessionStart, PostToolUse, UserProm…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #855.
- commits: 1 [66a0a65]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=7; +221/-0]. Sample: ADDED `.claude/hooks/trace-lib.sh` +39/-0; ADDED `.claude/hooks/trace-prompt.sh` +20/-0; ADDED `.claude/hooks/trace-session-init.sh` +25/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #856 — feat(rest-controller): add V6 like endpoints with JWT auth
- author zbnerd; closed; created 2026-05-27T01:57:48Z; closed 2026-05-27T01:58:20Z; merged yes/2026-05-27T01:58:20Z; merge commit 68220a2cabc40db472a4182010ee232c3d45ca20. Body: ## Summary - Add `POST /api/v6/characters/{userIgn}/like` (toggle) and `GET /api/v6/characters/{userIgn}/like/status` endpoints - …
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #856.
- commits: 1 [37cde62]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 8 [ADDED=6, MODIFIED=2; +346/-0]. Sample: MODIFIED `module-rest-controller/build.gradle` +5/-0; ADDED `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JdbcOcidQueryAdapter.kt` +50/-0; ADDED `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt` +71/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #857 — feat(observability): improve AI trace hooks
- author zbnerd; closed; created 2026-05-27T02:59:34Z; closed 2026-05-27T02:59:59Z; merged yes/2026-05-27T02:59:59Z; merge commit 377ed78676d273fd29ad092554f7320e7cfb274c. Body: ## Summary - Capture actual tool result in `result_preview` field (try `.tool_result`/`.result`, handle string/object types) - Aut…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #857.
- commits: 1 [ebd64db]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +7/-2]. Sample: MODIFIED `.claude/hooks/trace-session-init.sh` +3/-0; MODIFIED `.claude/hooks/trace-tool-use.sh` +4/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #858 — feat(auth): add module-auth login with Kafka + Nexon API verification
- author zbnerd; closed; created 2026-05-27T04:45:48Z; closed 2026-05-27T05:16:48Z; merged yes/2026-05-27T05:16:48Z; merge commit 2db3f9c286da14964ab98249b503730dcd087fbd. Body: ## Summary - Add `module-auth` with login orchestration: fingerprint generation, Kafka request/response, Redis session cache, JWT …
- reviews/discussion: 1 reviews [COMMENTED=1]; 4 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #858.
- commits: 8 [2af74e6, 2e504fe, 77d52c7, 76b0b53, 0d9c591, bef5f30, 8982f68, b3a22b5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 27 [ADDED=19, MODIFIED=8; +2161/-63]. Sample: ADDED `docs/superpowers/plans/2026-05-27-module-auth-login.md` +1318/-0; ADDED `module-auth/build.gradle` +41/-0; ADDED `module-auth/src/main/kotlin/maple/auth/fingerprint/FingerprintService.kt` +15/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 운영.

### PR #859 — fix(auth): derive fingerprint from Nexon account_id
- author zbnerd; closed; created 2026-05-27T08:26:18Z; closed 2026-05-27T08:41:04Z; merged yes/2026-05-27T08:41:04Z; merge commit 46401bf7c8197ccc56a334de572187f2c0eaa3b1. Body: ## Summary - Fingerprint now generated from Nexon `account_id` instead of API key - Same Nexon account with multiple API keys (liv…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #859.
- commits: 1 [708043c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [MODIFIED=9; +74/-77]. Sample: MODIFIED `module-auth/src/main/kotlin/maple/auth/kafka/AuthEventPublisher.kt` +2/-2; MODIFIED `module-auth/src/main/kotlin/maple/auth/kafka/AuthResponseConsumer.kt` +2/-2; MODIFIED `module-auth/src/main/kotlin/maple/auth/kafka/PendingLoginRegistry.kt` +7/-7. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #860 — feat(external-api,airflow): Airflow control plane adoption
- author zbnerd; closed; created 2026-05-29T04:10:12Z; closed 2026-05-29T04:10:47Z; merged yes/2026-05-29T04:10:47Z; merge commit 38c5b883d560503e6348a7bd9cec585d6eb63f3b. Body: ## Summary - Add RunStatusTracker, PipelinePhase enum, RunStatus model for pipeline state tracking - Add InternalApiController wit…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #860.
- commits: 8 [7293766, a981724, 2c8607c, 8322aff, a666c67, 329afb7, 7711f9a, 7c8bd33]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=11, MODIFIED=1; +1950/-6]. Sample: ADDED `docker-compose.airflow.yml` +75/-0; ADDED `docker/airflow/connections.sh` +16/-0; ADDED `docker/airflow/dags/daily_collection_pipeline.py` +82/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #861 — feat(airflow): migrate 4 cleanup schedulers to Airflow trigger endpoints
- author zbnerd; closed; created 2026-05-29T05:47:40Z; closed 2026-05-29T06:02:53Z; merged yes/2026-05-29T06:02:53Z; merge commit e44a0f2c8587c40fdcbcf23818943a8d5487e79e. Body: ## Summary - Remove `@Scheduled` from 3 cleanup schedulers (ArtifactCleanup, ConsumedChunk, CalculatorResult) - Add trigger endpoi…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #861.
- commits: 11 [ecf854c, 6883412, 1c25adf, dd3e9f3, 9de3c7f, 8e96d33, e519292, 40636d0, … +3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 14 [ADDED=5, MODIFIED=9; +1172/-42]. Sample: MODIFIED `docker/airflow/connections.sh` +7/-0; ADDED `docker/airflow/dags/daily_cleanup_pipeline.py` +69/-0; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +9/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #862 — fix(cleanup): remove @ConditionalOnProperty from migrated schedulers
- author zbnerd; closed; created 2026-05-29T07:54:13Z; closed 2026-05-29T07:54:54Z; merged yes/2026-05-29T07:54:54Z; merge commit a1d22b8d616091b005ea8c32c40bd01afc7fa2c3. Body: ## Summary - Remove `@ConditionalOnProperty` from `ArtifactCleanupScheduler` and `CalculatorResultCleanupScheduler` - These schedu…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #862.
- commits: 1 [a9e8a1f]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +0/-5]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt` +0/-3; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt` +0/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #863 — feat(observability): comprehensive Grafana dashboard + port fix
- author zbnerd; closed; created 2026-05-29T07:56:17Z; closed 2026-05-29T07:56:30Z; merged yes/2026-05-29T07:56:30Z; merge commit bc3ea8507e0b638facacbd273ffc9e8f7444af46. Body: ## Summary - Add `grafana/dashboard-pipeline-comprehensive.json` — 9 sections, 30+ panels covering all 3 modules - Sections: Throu…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #863.
- commits: 1 [8514ecb]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +434/-1]. Sample: MODIFIED `docker-compose.yml` +1/-1; ADDED `grafana/dashboard-pipeline-comprehensive.json` +433/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #864 — fix(external-api): defer item-equipment loop until daily refresh completes
- author zbnerd; closed; created 2026-05-29T11:58:45Z; closed 2026-05-29T12:00:43Z; merged yes/2026-05-29T12:00:43Z; merge commit f804a7ff8b3efca4559d2f35ec6f0e73a83caa05. Body: ## Summary - Item-equipment loop now starts only after `triggerDailyRefresh()` completes (via `whenComplete`), not unconditionally…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #864.
- commits: 1 [5c72d60]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +10/-1]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +10/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #865 — fix(airflow): parse XCom JSON string in poll_run_completion
- author zbnerd; closed; created 2026-05-29T13:43:59Z; closed 2026-05-30T08:05:54Z; merged yes/2026-05-30T08:05:54Z; merge commit 906ffd1fa59b6b994f3325539ff1a78dcfa94bc0. Body: ## Summary - Fix TypeError in `poll_run_completion` where XCom returns JSON string instead of dict - Added `json.loads()` with `is…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #865.
- commits: 2 [cefac86, 5d74412]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +47/-5]. Sample: MODIFIED `docker/airflow/dags/daily_cleanup_pipeline.py` +3/-3; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +44/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #866 — fix(cleanup): run ID parsing bug and tighten retention policy
- author zbnerd; closed; created 2026-05-31T04:16:13Z; closed 2026-05-31T04:16:37Z; merged yes/2026-05-31T04:16:37Z; merge commit ea5c20c88df90d03146c6b8822b29b379de17a28. Body: ## Summary - Fix `parseRunIdTimestamp` to handle run IDs with random suffix (`yyyyMMdd-HHmmss-XXXXXXXXX`) using `substringBeforeLa…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #866.
- commits: 1 [2002c6a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +6/-5]. Sample: MODIFIED `docker/airflow/dags/daily_cleanup_pipeline.py` +1/-1; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt` +2/-1; MODIFIED `module-external-api/src/main/resources/application.yml` +3/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #879 — fix(kafka): add DLQ and DefaultErrorHandler for poison message prevention
- author zbnerd; closed; created 2026-05-31T05:52:19Z; closed 2026-05-31T05:57:04Z; merged yes/2026-05-31T05:57:04Z; merge commit 00c31c77028116f91abdc5810c036a991f70192d. Body: ## Summary - Add `KafkaConsumerConfig` with `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` to calculator, external-api, s…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #879.
- commits: 1 [7ddc6d4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=3, MODIFIED=2; +109/-5]. Sample: ADDED `module-calculator/src/main/kotlin/maple/calculator/config/KafkaConsumerConfig.kt` +34/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` +3/-2; ADDED `module-external-api/src/main/kotlin/maple/externalapi/config/KafkaConsumerConfig.kt` +34/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #880 — fix(infra): VT executor shutdown hooks + runBlocking removal
- author zbnerd; closed; created 2026-06-03T06:26:10Z; closed 2026-06-03T06:46:07Z; merged yes/2026-06-03T06:46:07Z; merge commit d666ea34fd38a7fefbc4c4935709106935d5d91d. Body: Fixes #870, #869 ## Changes - **#870**: Add `@PreDestroy` to 8 virtual thread executors missing shutdown hooks - ExecutorConfig: a…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #880.
- commits: 1 [be92b9c]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +130/-16]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt` +43/-5; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` +11/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/EventConsumerConfig.kt` +18/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #881 — fix(airflow): handle 409 CONFLICT in DAG response checks
- author zbnerd; closed; created 2026-06-03T06:29:29Z; closed 2026-06-03T06:46:11Z; merged yes/2026-06-03T06:46:11Z; merge commit 1f1898507b299cf1931b3c251ad6edd9c70e2268. Body: Fixes #868 ## Changes - Add `is_accepted_response()` helper to both DAGs — treats 409 CONFLICT as success - `poll_run_completion`:…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #881.
- commits: 1 [cd8062e]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +36/-6]. Sample: MODIFIED `docker/airflow/dags/daily_cleanup_pipeline.py` +17/-4; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +19/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #882 — fix(calculator): eliminate TOCTOU race in chunk processing
- author zbnerd; closed; created 2026-06-03T07:28:00Z; closed 2026-06-03T07:36:08Z; merged yes/2026-06-03T07:36:08Z; merge commit f4fb5fc3bd737b3cdc1934f253f879df4b8b2f5f. Body: Fixes #874 Move objectStorage.exists() checks inside concurrency.withPermit block. Before: exists() → withPermit → executeChunk (T…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #882.
- commits: 2 [429d2d1, 73477dd]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +53/-28]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +13/-12; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt` +40/-16. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #883 — fix(external-api): ConsumedChunkCleanup concurrency overhaul
- author zbnerd; closed; created 2026-06-03T07:31:12Z; closed 2026-06-03T07:36:12Z; merged yes/2026-06-03T07:36:12Z; merge commit 2e81fa6ed60c2f9875e6dc508854393531da93f3. Body: Fixes #872, #871 Bounded queue (AtomicInteger O(1)), synchronous deletion, @Scheduled auto-cleanup, @PreDestroy drain. 🤖 Generated…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #883.
- commits: 1 [330dc67]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +40/-16]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt` +40/-16. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #884 — fix(external-api): atomic RunStatusTracker state transitions
- author zbnerd; closed; created 2026-06-03T07:35:09Z; closed 2026-06-03T07:36:16Z; merged yes/2026-06-03T07:36:16Z; merge commit 5b2a14ad35bb17d0453abe5018da9fbb5aabeef4. Body: Fixes #873 Capture `updateAndGet` result in local variable + add `runId` parameter to `completeRun`/`failRun`. Guard: skip transit…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #884.
- commits: 1 [ba3f7d0]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +17/-15]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/RunStatusTracker.kt` +12/-10; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +2/-2; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/runstatus/RunStatusTrackerTest.kt` +3/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #885 — fix(airflow): Kafka networking and correctness fixes
- author zbnerd; closed; created 2026-06-03T08:53:47Z; closed 2026-06-03T11:04:31Z; merged yes/2026-06-03T11:04:31Z; merge commit 3d3083a921494a8f5683f43f7209a8eb52243767. Body: Fixes #878 ## Changes - **connections.sh**: Remove double http:// scheme in --conn-host (scheme already from --conn-schema) - **ru…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #885.
- commits: 2 [6d3c24d, d386f13]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +12/-7]. Sample: MODIFIED `docker/airflow/connections.sh` +2/-2; MODIFIED `docker/airflow/dags/daily_collection_pipeline.py` +10/-5. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #886 — fix(synchronizer): OCID upsert batch, error handling, dead code removal
- author zbnerd; closed; created 2026-06-03T11:01:30Z; closed 2026-06-03T11:04:34Z; merged yes/2026-06-03T11:04:34Z; merge commit df514f7673356894cc3c607ece95f45c5d0e7a08. Body: Fixes #876 ## Changes - **Batch OCID upsert**: Replace per-row DELETE+INSERT loop with `OcidMappingRepository.batchUpsert()` (COPY…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #886.
- commits: 3 [b199bf4, 7e28fa2, bab697e]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=2, REMOVED=1; +14/-100]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` +6/-20; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` +8/-1; REMOVED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/SynchronizerChunkStatusRepository.kt` +0/-79. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #887 — fix(synchronizer): atomic Redis writes
- author zbnerd; closed; created 2026-06-03T11:04:03Z; closed 2026-06-03T11:04:38Z; merged yes/2026-06-03T11:04:38Z; merge commit 4190eaeb782af81871feb3618e0d644bf0a636ca. Body: Fixes #875 ## Changes - **OcidMapping atomic writes**: Replace `delete + hSet` with `write-to-temp + RENAME` pattern. RENAME is at…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #887.
- commits: 2 [7a4bf94, 7cdc5e6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +19/-5]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt` +10/-2; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` +9/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #908 — refactor(calculator): consolidate @Value into CleanupProperties, extract @Import into Engi…
- author zbnerd; closed; created 2026-06-03T14:12:54Z; closed 2026-06-03T14:36:16Z; merged yes/2026-06-03T14:36:16Z; merge commit 91a49017ceb59052620aa9fc013bf2abbe4efa62. Body: ## Summary - **#890**: `CalculatorResultCleanupScheduler` 6 `@Value` → `CalculatorCleanupProperties` data class - **#891**: `Calcu…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #908.
- commits: 1 [717bbfa]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=2, MODIFIED=2; +76/-59]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt` +4/-39; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/cleanup/CalculatorResultCleanupScheduler.kt` +10/-20; ADDED `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorCleanupProperties.kt` +17/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #909 — refactor: config binding consolidation + metrics event listener extraction
- author zbnerd; closed; created 2026-06-03T14:35:19Z; closed 2026-06-03T14:36:21Z; merged yes/2026-06-03T14:36:21Z; merge commit 327f3bf22b6c5133b27fee1e0f71c1af386af805. Body: ## Summary - **#893**: `ChunkConsumerTemplate` 4 `@Value` → `ChunkExecutionProperties` data class (synchronizer) - **#894**: `Calc…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #909.
- commits: 1 [cd793dc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=5, MODIFIED=7; +249/-98]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +20/-20; ADDED `module-calculator/src/main/kotlin/maple/calculator/event/ChunkProcessingEvent.kt` +33/-0; ADDED `module-calculator/src/main/kotlin/maple/calculator/metrics/CalculatorMetricsListener.kt` +44/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #910 — refactor: deduplicate code — maskIgn, CharacterId, gzipCompress/sha256Hex
- author zbnerd; closed; created 2026-06-03T14:56:19Z; closed 2026-06-03T14:58:03Z; merged yes/2026-06-03T14:58:03Z; merge commit 1ebf95b49f827278ef37a6f4c37288d7d5837dcb. Body: ## Summary - **DUP-10**: Private `maskIgn` in 2 files → `StringMaskingUtils.maskIgn` - **DUP-6**: Duplicate `CharacterId` removed …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #910.
- commits: 1 [42b2826]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=1, MODIFIED=10, REMOVED=1; +42/-96]. Sample: MODIFIED `module-app/src/main/java/maple/expectation/config/CorePortAdapterConfig.java` +1/-1; MODIFIED `module-common/src/main/kotlin/maple/expectation/util/GzipUtils.kt` +11/-0; ADDED `module-common/src/main/kotlin/maple/expectation/util/HashUtils.kt` +11/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #911 — refactor: merge High/Low EventConsumer, remove dead CubeRatePort
- author zbnerd; closed; created 2026-06-03T15:07:28Z; closed 2026-06-03T15:07:31Z; merged yes/2026-06-03T15:07:31Z; merge commit 218147182cd92508a99828d681ebbd0e8b0d3408. Body: ## Summary - **DUP-2**: `HighPriorityEventConsumer` + `LowPriorityEventConsumer` (84 lines each) → `IntegrationEventConsumer` base…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #911.
- commits: 1 [4257285]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=1, MODIFIED=2, REMOVED=1; +92/-167]. Sample: REMOVED `module-core/src/main/kotlin/maple/expectation/core/calculator/port/CubeRatePort.kt` +0/-19; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/HighPriorityEventConsumer.kt` +4/-74; ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/event/IntegrationEventConsumer.kt` +84/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #912 — refactor: extract VirtualThreadExecutorManager for VT executor lifecycle (PAT-1)
- author zbnerd; closed; created 2026-06-03T15:16:14Z; closed 2026-06-03T15:16:18Z; merged yes/2026-06-03T15:16:18Z; merge commit 49741b2aff63b0befdcc137335d5e2a33fafd793. Body: ## Summary - New `VirtualThreadExecutorManager` utility — creates VT executor + standardized shutdown (5s timeout, shutdownNow fal…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #912.
- commits: 1 [920c119]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=1, MODIFIED=10; +89/-81]. Sample: MODIFIED `module-calculator/src/main/kotlin/maple/calculator/cleanup/InternalApiController.kt` +4/-4; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` +4/-9; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` +6/-6. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1113 — fix: replace CallerRunsPolicy with AbortPolicy (taskExecutor, backfillExecutor)
- author zbnerd; closed; created 2026-06-04T00:50:33Z; closed 2026-06-04T00:51:20Z; merged yes/2026-06-04T00:51:20Z; merge commit 6f284ec4ad3a183abced6c4f1ed3031d446effb1. Body: ## Summary Replace `CallerRunsPolicy` with `AbortPolicy` for `taskExecutor` and `backfillExecutor` in `ExecutorConfig.kt`. **Probl…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1113.
- commits: 1 [1824992]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +81/-2]. Sample: MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ExecutorConfig.kt` +2/-2; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/RejectionPolicyFactory.kt` +79/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1114 — fix: add @PreDestroy executor lifecycle to 5 components
- author zbnerd; closed; created 2026-06-04T00:50:36Z; closed 2026-06-04T00:51:23Z; merged yes/2026-06-04T00:51:23Z; merge commit 276d1ead9e53fd2d2bd0f2026f8e06faada530b9. Body: ## Summary Add `@PreDestroy` shutdown lifecycle to 5 components that create inline executors without cleanup. **Problem:** 5 compo…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1114.
- commits: 1 [5b1a906]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [MODIFIED=7; +67/-7]. Sample: MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/AdaptiveAdmissionControl.kt` +20/-3; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/SimpleAdmissionControl.kt` +2/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/bulk/BulkLoaderService.kt` +16/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1115 — refactor: replace Thread.ofPlatform() with ExecutorService in ChunkedSnapshotSink
- author zbnerd; closed; created 2026-06-04T00:50:39Z; closed 2026-06-04T00:51:26Z; merged yes/2026-06-04T00:51:26Z; merge commit db5ad83f23baa5566c807bb9d6a0b502ff886169. Body: ## Summary Replace raw `Thread.ofPlatform()` with managed `ExecutorService` in `ChunkedSnapshotSink`. **Problem:** `ChunkedSnapsho…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1115.
- commits: 1 [f445d42]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +12/-6]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` +12/-6. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1116 — fix: TieredCache keyVersions memory leak + PgmqWorker shutdown drain
- author zbnerd; closed; created 2026-06-04T00:50:42Z; closed 2026-06-04T00:51:28Z; merged yes/2026-06-04T00:51:28Z; merge commit 9e3f7b0d2645eb28ffe40f2c5ac6118167678616. Body: ## Summary Fix TieredCache `keyVersions` unbounded memory leak and PgmqWorker shutdown data loss. ### Part A: TieredCache keyVersi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1116.
- commits: 1 [51f7e06]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +26/-11]. Sample: MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt` +14/-11; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` +12/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1117 — fix: Backpressure — OcidLookup/SnapshotFetch/UrgentConsumer 동시성 제한
- author zbnerd; closed; created 2026-06-04T01:44:44Z; closed 2026-06-04T02:20:38Z; merged yes/2026-06-04T02:20:38Z; merge commit 2818e6f7f9daf3975e7f61b6bb2c4197bb046978. Body: ## Summary Closes #1108 5개 위치에 backpressure 메커니즘 추가: 위치 변경 설정값 ------ ------ -------- OcidLookupPhase Semaphore + backoff retry `m…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1117.
- commits: 1 [0fc8ea5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=1, MODIFIED=10; +219/-39]. Sample: ADDED `docs/01_ADR/ADR-backpressure-concurrency-limits.md` +92/-0; MODIFIED `module-app/src/main/resources/application.yml` +2/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` +45/-18. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1118 — fix: Blocking-in-async — Discord subscribe, TieredCache/SingleFlight/AuthClient timeout
- author zbnerd; closed; created 2026-06-04T02:07:12Z; closed 2026-06-04T02:20:52Z; merged yes/2026-06-04T02:20:52Z; merge commit 30f0bbc50b503799d5b21f256019154c37d129d8. Body: ## Summary Closes #1109 4개 위치 blocking 패턴 수정: 위치 변경 타임아웃 ------ ------ ---------- DiscordAlertChannel `.block()` → `.subscribe()` …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1118.
- commits: 1 [dc61fd6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=1, MODIFIED=4; +141/-44]. Sample: ADDED `docs/01_ADR/ADR-blocking-in-async-timeout.md` +90/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/alert/channel/DiscordAlertChannel.kt` +19/-41; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt` +17/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1119 — refactor(#1063): Split ExecutorConfig into Core + Infra
- author zbnerd; closed; created 2026-06-04T02:47:31Z; closed 2026-06-04T02:48:10Z; merged yes/2026-06-04T02:48:10Z; merge commit 2ce910d542403d3cec7033f05f386ca368d0c1c0. Body: #1063 ## Changes - **CoreExecutorConfig** — LogicExecutor, CheckedLogicExecutor, ExecutionPipeline, ExceptionTranslator, LoggingPo…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1119.
- commits: 1 [c425692]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=2, MODIFIED=5; +335/-361]. Sample: MODIFIED `module-app/src/test/java/maple/expectation/config/ExecutorConfigTest.java` +19/-13; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorEngineConfiguration.kt` +2/-2; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1120 — arch(#1068): VT ExecutorManager inline → @Bean injection
- author zbnerd; closed; created 2026-06-04T03:05:17Z; closed 2026-06-04T03:18:12Z; merged yes/2026-06-04T03:18:12Z; merge commit 3f1a321f9bf823493468cf64e1fbc18a517bddcf. Body: #1068, Closes #1041 ## Changes - **VtExecutorConfig** — 6 named VT `ExecutorService` beans with `@PreDestroy` centralized shutdown…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1120.
- commits: 1 [8bca3f1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=1, MODIFIED=11; +130/-64]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt` +1/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` +4/-9; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/runstatus/InternalApiController.kt` +6/-9. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1121 — docs(#901): ADR-721 sync boundary justification
- author zbnerd; closed; created 2026-06-04T03:27:39Z; closed 2026-06-04T03:32:03Z; merged yes/2026-06-04T03:32:02Z; merge commit da78f6163b2f013ea4df5c10a1d1cffb6c9d3d67. Body: #901 ## Summary All 15 identified `.join()`/`.get()`/`runBlocking` violations analyzed: - **4 fixed** in #1109 (TieredCache timeou…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1121.
- commits: 1 [4e66ba3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +106/-0]. Sample: ADDED `docs/01_ADR/ADR-721_sync-boundary-justification.md` +106/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1122 — fix(#905): concurrency antipatterns — semaphore leak, volatile, bounded buffer, init race
- author zbnerd; closed; created 2026-06-04T03:46:35Z; closed 2026-06-04T03:46:52Z; merged yes/2026-06-04T03:46:52Z; merge commit e9ada29a0bf2ff36a7be21a42f7c3228aec287fc. Body: #905 ## Changes # Component Issue Fix --- ----------- ------- ----- 1 SimpleAdmissionControl Semaphore permit leak on exception Mo…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1122.
- commits: 1 [4a1a4fc]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [MODIFIED=5; +29/-14]. Sample: MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/SimpleAdmissionControl.kt` +14/-11; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/SignalDefinitionLoader.kt` +7/-2; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/AccumulationBuffer.kt` +3/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1123 — fix(#1102): remove ForkJoinPool.commonPool() — dedicated executor for all 8 sites
- author zbnerd; closed; created 2026-06-04T04:01:18Z; closed 2026-06-04T04:01:42Z; merged yes/2026-06-04T04:01:42Z; merge commit 33941a00331dd40df084e03f0d65e871e2f4fbd6. Body: #1102, Closes #1046, Closes #1027 ## Changes # Component Before After --- ----------- -------- ------- 1-2 LikeController `supplyA…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1123.
- commits: 1 [6f22ef7]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [ADDED=1, MODIFIED=8; +70/-21]. Sample: MODIFIED `module-core/src/main/kotlin/maple/expectation/core/port/out/EventPublisher.kt` +1/-1; MODIFIED `module-infra/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java` +19/-10; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/KafkaEventPublisher.kt` +4/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1124 — refactor(#984): Recursive CF → suspend fun + while loop
- author zbnerd; closed; created 2026-06-04T05:22:09Z; closed 2026-06-04T05:22:49Z; merged yes/2026-06-04T05:22:49Z; merge commit df202a28b6a702e8899ee8ff3b182eca7dedaa23. Body: ## Summary 3 Phase classes in `module-external-api` converted from recursive `CompletableFuture` chains to `suspend fun` + while l…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1124.
- commits: 5 [94ca788, 7660e3a, ae0addc, 1f3a5fa, 8e182ae]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=1, MODIFIED=6; +287/-289]. Sample: MODIFIED `gradle/libs.versions.toml` +1/-0; MODIFIED `module-external-api/build.gradle` +1/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` +86/-106. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1132 — refactor(external-api-worker): CF chaining pipeline (#1112)
- author zbnerd; closed; created 2026-06-04T06:25:51Z; closed 2026-06-04T06:26:28Z; merged yes/2026-06-04T06:26:28Z; merge commit 0ad17c0afe3725a2448a3f0f03da4547a9c83c22. Body: ## Summary - ExternalApiWorker.processPipeline()을 CompletableFuture 체이닝으로 전환 - `.join()` 3회 + `runBlocking` 1회 → 단일 `.join()` (ACK…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1132.
- commits: 4 [53ed8ca, 9599935, 8cade5c, 826cf48]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=1, MODIFIED=3; +259/-153]. Sample: ADDED `docs/01_ADR/ADR-XXX_external-api-worker-cf-chaining.md` +99/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/StepTimer.kt` +5/-4; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt` +152/-149. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1133 — fix(1001): propagate ranking failure; gate item-equipment loop on success
- author zbnerd; closed; created 2026-06-04T12:40:01Z; closed 2026-06-04T12:41:44Z; merged yes/2026-06-04T12:41:44Z; merge commit 3d59da8ecaba60a1a63e74ad68773de565a8b449. Body: ## Summary Fixes https://github.com/zbnerd/probabilistic-valuation-engine/issues/1001 The `ExternalApiScheduler.triggerDailyRefres…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1133.
- commits: 14 [7c99271, b54d1a8, 48689be, 0a4b1dd, 30d88cb, 782dcf3, fcb9015, d7bee9d, … +6]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 16 [ADDED=12, MODIFIED=4; +3541/-60]. Sample: ADDED `docs/superpowers/plans/2026-06-04-externalapi-null-to-exception.md` +579/-0; ADDED `docs/superpowers/plans/2026-06-04-issue-1001-scheduler-failure-propagation-plan.md` +378/-0; ADDED `docs/superpowers/plans/2026-06-04-issue-1018-null-ocid-observability.md` +272/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1134 — fix(external-api): log warn for null ocid in OcidLookupPhase (issue 1018)
- author zbnerd; closed; created 2026-06-04T12:41:13Z; closed 2026-06-04T12:42:10Z; merged yes/2026-06-04T12:42:10Z; merge commit 3b29a426c49b06f1eff1301e3a0caab31b573da2. Body: ## Summary - Add `log.warn("[OCID] null ocid for ign={}", maskIgn(ign))` to `OcidLookupPhase.fetchOcid()` else-branch - Surface Ne…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1134.
- commits: 3 [3c4a703, e7906e8, 59d32eb]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [MODIFIED=2; +47/-2]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` +5/-1; MODIFIED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` +42/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1135 — fix(external-api): ArtifactStore/Cleanup null→예외 for #999
- author zbnerd; closed; created 2026-06-04T12:53:57Z; closed 2026-06-04T12:54:20Z; merged yes/2026-06-04T12:54:20Z; merge commit c1048e297962ca7955a987372911e7bdf3ae9b87. Body: ## Summary EPIC-2 silent data loss 제거 — `module-external-api`의 두 null/boolean swallow 경로를 명시적 예외로 변환. - **`LocalExternalApiArtifac…
- reviews/discussion: 1 reviews [COMMENTED=1]; 1 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1135.
- commits: 6 [7513ec2, e16657c, d1a3399, 86cdbd8, 488e5eb, bf09f89]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=4, MODIFIED=6; +216/-121]. Sample: MODIFIED `docs/superpowers/plans/2026-06-04-externalapi-null-to-exception.md` +33/-105; MODIFIED `docs/superpowers/specs/2026-06-04-externalapi-null-to-exception-design.md` +7/-7; MODIFIED `module-common/src/main/kotlin/maple/expectation/error/CommonErrorCode.kt` +1/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1136 — fix(1019): add debug logging to synchronizer file reader silent parse paths
- author zbnerd; closed; created 2026-06-04T13:03:23Z; closed 2026-06-04T13:04:19Z; merged yes/2026-06-04T13:04:19Z; merge commit e5319abc0cda527fb2c20fa0eee24dbbff2dd2f4. Body: ## Summary - `OcidMappingFileReader.parseMapping()` — 2 null paths + runCatching.onFailure logged - `BasicChunkFileReader.parseRec…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1136.
- commits: 4 [34f0a05, 66e7dec, 4c6fe72, 9a236f2]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [ADDED=2, MODIFIED=2; +313/-9]. Sample: ADDED `docs/superpowers/plans/2026-06-04-1019-synchronizer-file-reader-logging.md` +203/-0; ADDED `docs/superpowers/specs/2026-06-04-1019-synchronizer-file-reader-logging-design.md` +77/-0; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` +24/-6. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1137 — fix(scheduler,synchronizer): surface lock timeout + reader parse errors
- author zbnerd; closed; created 2026-06-04T13:07:43Z; closed 2026-06-04T13:10:47Z; merged yes/2026-06-04T13:10:47Z; merge commit 33781f68a2b3cc8ed4b52e2f0807fb5b7327307b. Body: ## Summary Closes #998 and #996. ### #998 — ExternalApiScheduler acquireLock timeout - `acquireLock` now throws `DistributedLockEx…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1137.
- commits: 10 [5075845, 249b591, b214f99, 4a74ed4, a76b933, 8f53f0e, 7ebe2a7, c1401db, … +2]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=5, MODIFIED=5; +407/-79]. Sample: ADDED `module-external-api/src/main/kotlin/maple/externalapi/metrics/SchedulerMetrics.kt` +24/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` +19/-7; ADDED `module-external-api/src/test/kotlin/maple/externalapi/metrics/SchedulerMetricsTest.kt` +23/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1139 — fix(1138): add debug logging for empty userIgn exclusions in OcidUserIgnResolver
- author zbnerd; closed; created 2026-06-04T13:18:10Z; closed 2026-06-04T13:18:28Z; merged yes/2026-06-04T13:18:27Z; merge commit 14aa19f9402339e3368c70e4d627923b3ea1689b. Body: ## Summary - Add aggregate debug log to OcidUserIgnResolver.resolve() showing count + sample of OCIDs excluded for empty user_ign …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1139.
- commits: 3 [07a4b39, 5b79a74, 092db1a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=2, MODIFIED=1; +223/-7]. Sample: ADDED `docs/superpowers/plans/2026-06-04-1138-ocid-user-ign-resolver-logging.md` +116/-0; ADDED `docs/superpowers/specs/2026-06-04-1138-ocid-user-ign-resolver-logging-design.md` +94/-0; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt` +13/-7. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1140 — refactor(1077): split OcidMappingRepository into DB and Redis
- author zbnerd; closed; created 2026-06-04T13:47:22Z; closed 2026-06-04T13:49:52Z; merged yes/2026-06-04T13:49:52Z; merge commit 38d72c7171db2a71ae03fc3fde435b7f2ec4ae11. Body: ## Summary - Split `OcidMappingRepository` (DB-only) and new `OcidMappingRedisWriter` (Redis-only). - `OcidLookupRunConsumer` inje…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1140.
- commits: 3 [0e63740, b56d9da, 52904ee]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +41/-28]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` +3/-1; ADDED `module-synchronizer/src/main/kotlin/maple/synchronizer/redis/OcidMappingRedisWriter.kt` +38/-0; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` +0/-27. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1141 — refactor(933): decompose synchronizer repository SQL methods
- author zbnerd; closed; created 2026-06-05T00:35:54Z; closed 2026-06-05T00:38:11Z; merged yes/2026-06-05T00:38:11Z; merge commit 91269c42211cb550e376e049d273bb3a9b38126c. Body: ## Summary - Extract SQL strings to companion constants in 3 repositories. - Extract parameter binding to `buildUpsertParams()` he…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1141.
- commits: 1 [a386613]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [MODIFIED=3; +104/-83]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt` +32/-28; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt` +28/-26; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` +44/-29. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1142 — fix(1106,1107): DB access pattern fixes — N+1, LIMIT, chunking, batch INSERT
- author zbnerd; closed; created 2026-06-05T01:00:29Z; closed 2026-06-05T01:01:45Z; merged yes/2026-06-05T01:01:45Z; merge commit 5ee7615f11c6904ea82035a8a59ddd073c6bb9e0. Body: ## Summary - **#1106:** Replace N+1 `forEach` in `AbstractExpectationCalcWorker` with existing `batchUpsertFromCalculations()` met…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1142.
- commits: 4 [5445602, 542a6db, 247edf7, 5fbc3db]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 4 [MODIFIED=4; +18/-20]. Sample: MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustomImpl.kt` +1/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationBatchRepository.kt` +8/-6; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt` +4/-11. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1143 — refactor(923): decompose DefaultChunkProcessor into read/transform/write stages
- author zbnerd; closed; created 2026-06-05T01:41:09Z; closed 2026-06-05T01:56:27Z; merged yes/2026-06-05T01:56:27Z; merge commit 974efe438cd7bab9ae241f536c2b3ec459027ded. Body: ## Summary Decompose `DefaultChunkProcessor.process()` into 3 `@Component` stages: - **ChunkDataReader** — file read + OCID resolu…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1143.
- commits: 6 [caf1ac2, 13a9ebd, c169c25, 0cd233e, cdff928, f92d632]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=3, MODIFIED=2; +142/-78]. Sample: ADDED `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDataReader.kt` +32/-0; ADDED `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentTransformer.kt` +48/-0; ADDED `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/ChunkDocumentWriter.kt` +22/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1144 — refactor(943): DRY extraction — CompressionUtils, sha256Hex, KafkaConsumerConfig
- author zbnerd; closed; created 2026-06-05T02:29:09Z; closed 2026-06-05T02:29:45Z; merged yes/2026-06-05T02:29:45Z; merge commit f41a3208467527c7536ffd8738939c43ff8814f4. Body: ## Summary Extract 3 duplicated patterns: - **CompressionUtils.ratioString()** → `module-common/util/` (new, pure Kotlin object) -…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1144.
- commits: 4 [7a8da95, fbe3316, 0fd7b40, 8aba809]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 30 [ADDED=17, MODIFIED=10, REMOVED=2, RENAMED=1; +6488/-90]. Sample: ADDED `docs/11_Observability/bug-scan-2026-05-31.md` +84/-0; ADDED `docs/superpowers/plans/2026-06-03-concurrency-fixes.md` +451/-0; ADDED `docs/superpowers/plans/2026-06-03-infra-reliability-fixes.md` +602/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1145 — refactor(952): unify missing-file error strategy in BasicChunkFileReader
- author zbnerd; closed; created 2026-06-05T02:41:10Z; closed 2026-06-05T02:41:51Z; merged yes/2026-06-05T02:41:51Z; merge commit dd8438fe623f20796451c22adc88ca5684af3432. Body: ## Summary Unify missing-file error handling in `BasicChunkFileReader` — changed `require()` (→ `IllegalArgumentException`) to exp…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1145.
- commits: 1 [f3d095e]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [MODIFIED=1; +2/-2]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` +2/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1146 — refactor: module-core port interface tech-name removal (#906)
- author zbnerd; closed; created 2026-06-05T04:19:41Z; closed 2026-06-05T04:20:26Z; merged yes/2026-06-05T04:20:26Z; merge commit b9274797adca981f48e8a2439b237a0e3e9766e0. Body: Resolves #906 (Leaky Abstraction cleanup, partial). ## Summary Removes infrastructure technology names (PGMQ, Redis, Kafka, MySQL)…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1146.
- commits: 9 [dc4c0c8, 9b107cc, 92700f0, e950307, 480c08a, a6a3b0f, ce2a2f6, 56857db, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 27 [ADDED=6, MODIFIED=18, RENAMED=3; +1102/-153]. Sample: ADDED `docs/superpowers/plans/2026-06-05-port-abstraction-cleanup.md` +847/-0; ADDED `docs/superpowers/specs/2026-06-05-port-abstraction-cleanup-design.md` +136/-0; MODIFIED `module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java` +4/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1147 — fix(infra): @Transactional hygiene — readOnly 명시, scope 축소, @Async+TX 분리 (#1104)
- author zbnerd; closed; created 2026-06-05T04:36:23Z; closed 2026-06-05T04:36:49Z; merged yes/2026-06-05T04:36:49Z; merge commit 0279b38060b24f927f23873f31d99ee5ae40f010. Body: ## Summary Resolves #1104. \`@Transactional\` 위생 — readOnly 명시, scope 축소, @Async+TX 분리. ## Changes (5 commits on `worktree-chore+1…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1147.
- commits: 6 [25aa821, 12100ff, e896f42, aa44db5, 4d37f95, 2c6a9d3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=1, MODIFIED=6; +881/-67]. Sample: ADDED `docs/superpowers/plans/2026-06-05-tx-hygiene.md` +731/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt` +64/-16; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` +16/-16. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 결함·보안.

### PR #1148 — refactor(infra): move 7 v2 entities + 5 ports to infrastructure/persistence (#896)
- author zbnerd; closed; created 2026-06-05T04:37:06Z; closed 2026-06-05T04:37:21Z; merged yes/2026-06-05T04:37:20Z; merge commit ac704b1d616322a6a9771e9a13d8e0ffbd5d94ca. Body: Move legacy JPA entities from module-infra/domain/v2/ to infrastructure/persistence/entity/, port interfaces from domain/repositor…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1148.
- commits: 4 [12f8cc5, 03ec7cf, feb90b5, a600302]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 75 [ADDED=3, MODIFIED=59, REMOVED=5, RENAMED=8; +1180/-911]. Sample: ADDED `docs/superpowers/plans/2026-06-05-issue-896-domain-v2-migration.md` +889/-0; MODIFIED `module-app/src/main/java/maple/expectation/application/service/EquipmentApplicationService.java` +1/-1; MODIFIED `module-app/src/main/java/maple/expectation/application/service/character/CharacterCreationService.java` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1149 — refactor: relocate outbox query from CalculationJobPortAdapter to OutboxEventPortAdapter (…
- author zbnerd; closed; created 2026-06-05T04:38:35Z; closed 2026-06-05T04:39:26Z; merged yes/2026-06-05T04:39:26Z; merge commit 5ecaca1a7ceeb62f1c7c8f72c1bcb1b599f6c6db. Body: ## Summary Relocates `findCompletedJobsMissingOutboxEvents` from `CalculationJobPort`/`CalculationJobPortAdapter` to `OutboxEventP…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1149.
- commits: 8 [5f19cc7, a60d4a8, 1d9e4b2, 69a358f, ec82e71, 66b7c88, c015ebe, 4153685]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 8 [ADDED=2, MODIFIED=6; +649/-18]. Sample: ADDED `docs/superpowers/plans/2026-06-05-outbox-relocate.md` +462/-0; ADDED `docs/superpowers/specs/2026-06-05-outbox-relocate-design.md` +136/-0; MODIFIED `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt` +0/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1154 — docs(897): port audit spec + ADR + implementation plan
- author zbnerd; closed; created 2026-06-05T04:49:31Z; closed 2026-06-05T04:49:42Z; merged yes/2026-06-05T04:49:42Z; merge commit ed74670bee692897c27dae3a548c3c112806f368. Body: ## Summary Issue #897: module-core의 49개 아웃바운드 포트를 어댑터 개수 기준으로 감사/분류. **조사 결과 (issue body 보정 포함):** - 총 49개 (audit-verified, issue …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1154.
- commits: 1 [ea19af3]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=3; +591/-0]. Sample: ADDED `docs/01_ADR/ADR-391-outbound-port-seam-classification.md` +94/-0; ADDED `docs/superpowers/plans/2026-06-05-897-port-audit.md` +339/-0; ADDED `docs/superpowers/specs/2026-06-05-897-port-audit-design.md` +158/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1155 — docs: ADR-050 module-infra decomposition roadmap (#907)
- author zbnerd; closed; created 2026-06-05T04:49:35Z; closed 2026-06-05T04:49:53Z; merged yes/2026-06-05T04:49:53Z; merge commit 2526bde04c5149513e6b837989397d067f4b7d20. Body: ## Summary - Adds `docs/01_ADR/ADR-050-module-infra-decomposition-roadmap.md` - Design-only deliverable for #907 (no code changes)…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1155.
- commits: 1 [32a1463]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +214/-0]. Sample: ADDED `docs/01_ADR/ADR-050-module-infra-decomposition-roadmap.md` +214/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1156 — docs(1153): GameCharacterPort 미완성 추출 조사 보고 (ADR-392)
- author zbnerd; closed; created 2026-06-05T04:56:37Z; closed 2026-06-05T04:57:14Z; merged yes/2026-06-05T04:57:14Z; merge commit 606f2e5f7eec810fe5b217507b8e9558d0b38c35. Body: ## Summary Issue #1153 조사 결과: `GameCharacterPort` 는 미완성 추출 (Issue #464, commit 3d0911f62). 인터페이스만 `core/port/out/` 으로 이동, 어댑터 작성 없…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1156.
- commits: 1 [89de860]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 1 [ADDED=1; +111/-0]. Sample: ADDED `docs/01_ADR/ADR-392-gamecharacter-port-incomplete-extraction.md` +111/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1157 — feat(concurrency): introduce 6 single-purpose adapters (Phase 1)
- author zbnerd; closed; created 2026-06-05T13:22:39Z; closed 2026-06-06T06:06:45Z; merged yes/2026-06-06T06:06:45Z; merge commit dab6cdab9de5a711a496f1467b0fe3432117ea38. Body: ## Summary Introduces `module-infra/concurrency/` package with six single-purpose adapters: Adapter Concern PRs Sealed --------- -…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1157.
- commits: 9 [7ef68ff, 09977b2, 486e12a, 094080b, 2b737c2, bc9e948, d1675dc, ab36b18, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 19 [ADDED=17, MODIFIED=2; +419/-0]. Sample: MODIFIED `.claude/rules/async-concurrency.md` +19/-0; MODIFIED `module-infra/build.gradle` +1/-0; ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/AsyncGuard.kt` +37/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1158 — docs(adr): ADR-722 package naming policy + ADR-050 post-EPIC-4 update
- author zbnerd; closed; created 2026-06-05T13:29:37Z; closed 2026-06-06T06:06:57Z; merged yes/2026-06-06T06:06:57Z; merge commit 01ad482e576a01ef142830174be448abe3aa55e7. Body: ## Summary - **ADR-722**: `infrastructure/{domain}/{role}/` package naming policy. Bans version suffixes, flat entity packages, JP…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1158.
- commits: 1 [97f30c9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 2 [ADDED=1, MODIFIED=1; +125/-0]. Sample: MODIFIED `docs/01_ADR/ADR-050-module-infra-decomposition-roadmap.md` +31/-0; ADDED `docs/01_ADR/ADR-722_infrastructure-package-naming-policy.md` +94/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1159 — feat(core): add Chunk stage port interfaces (PR1)
- author zbnerd; closed; created 2026-06-06T03:13:33Z; closed 2026-06-06T06:07:14Z; merged yes/2026-06-06T06:07:14Z; merge commit 565c84e827522334908cdd8dbcd82edb66adcc24. Body: ## Summary Introduces `module-core/domain/chunk/`: - `Chunk<T>` data class with `input` + `data` + `metadata` - `ChunkProcessInput…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1159.
- commits: 5 [526c770, 4b5f3d7, a62ed0d, e3450ca, c297460]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 14 [ADDED=8, MODIFIED=5, RENAMED=1; +136/-2]. Sample: MODIFIED `module-core/build.gradle` +4/-0; ADDED `module-core/src/main/kotlin/maple/core/domain/chunk/Chunk.kt` +7/-0; RENAMED `module-core/src/main/kotlin/maple/core/domain/chunk/ChunkProcessInput.kt` +1/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1160 — refactor(core): delete 6 dead Like port hypothetical seams (#897)
- author zbnerd; closed; created 2026-06-06T04:22:10Z; closed 2026-06-06T06:07:29Z; merged yes/2026-06-06T06:07:29Z; merge commit 2809de25cf40531332b594bed8747b68e1b5c414. Body: ## Summary Closes #897 partial Removes 6 unused outbound port interfaces in module-core that were dead hypothetical seams (zero ad…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1160.
- commits: 13 [6c5ab88, 50a1813, 8d68af9, b914ed9, a3341ab, 755bd82, f981ad9, c70cb8b, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 15 [ADDED=3, MODIFIED=6, REMOVED=6; +651/-356]. Sample: MODIFIED `docs/superpowers/plans/2026-06-05-897-port-audit.md` +2/-0; MODIFIED `docs/superpowers/plans/2026-06-05-port-abstraction-cleanup.md` +2/-0; ADDED `docs/superpowers/plans/2026-06-06-like-port-merge.md` +333/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1169 — docs(spec): module-executor extraction spec (#907.1, ADR-050 PR1)
- author zbnerd; closed; created 2026-06-06T05:37:46Z; closed 2026-06-06T06:12:48Z; merged yes/2026-06-06T06:12:48Z; merge commit 35df34f7132edebecd02903e3964515a342b48a3. Body: ## Summary Closes part of #907 Adds the detailed extraction spec for `module-executor` — the 1순위 sub-module from ADR-050 (module-i…
- reviews/discussion: 1 reviews [COMMENTED=1]; 2 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1169.
- commits: 3 [6c5ab88, 50a1813, 47c20f5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=3; +577/-0]. Sample: ADDED `docs/superpowers/specs/2026-06-06-907-module-executor-extraction-design.md` +277/-0; ADDED `docs/superpowers/specs/2026-06-06-chunk-stage-ports-design.md` +161/-0; ADDED `docs/superpowers/specs/2026-06-06-like-port-merge-design.md` +139/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 문서화.

### PR #1170 — refactor(1069): extract CalculatorEngineAutoConfiguration facade
- author zbnerd; closed; created 2026-06-06T05:50:30Z; closed 2026-06-06T06:12:24Z; merged yes/2026-06-06T06:12:24Z; merge commit 39e7752830d67823c618e665d3208ebd0059cf20. Body: ## Summary - Add `CalculatorEngineAutoConfiguration` in `module-infra/.../config/` (17 `@Import` entries) - Reduce `CalculatorEngi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1170.
- commits: 5 [a510004, f461fae, 799b74b, 3e37504, 3c0e06f]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=3, MODIFIED=4; +602/-112]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1069-calc-engine-autoconfig.md` +305/-0; ADDED `docs/superpowers/specs/2026-06-06-1069-calc-engine-autoconfig-design.md` +150/-0; MODIFIED `docs/superpowers/specs/2026-06-06-like-port-merge-design.md` +67/-76. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1171 — refactor(synchronizer): ChunkExecutionStatus sealed class (#960)
- author zbnerd; closed; created 2026-06-06T09:00:46Z; closed 2026-06-06T09:00:57Z; merged yes/2026-06-06T09:00:57Z; merge commit 0dc0042596e05392660ba2bf15e3477510a6d84a. Body: Implements #960. Replace enum with sealed class (Pending, Processing, Succeeded, FailedRetryable, FailedTerminal). State-level sho…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1171.
- commits: 18 [6c5ab88, 50a1813, 03b3469, 1c186ce, 6bbac2a, c2eaa64, d88f12a, 8aa3d24, … +10]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 16 [ADDED=10, MODIFIED=5, REMOVED=1; +2468/-50]. Sample: ADDED `docs/superpowers/plans/2026-06-06-chunk-consumer-template-state-machine.md` +580/-0; ADDED `docs/superpowers/plans/2026-06-06-chunk-execution-status-sealed.md` +646/-0; ADDED `docs/superpowers/plans/2026-06-06-urgent-read-state-sealed.md` +354/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1172 — refactor(rest): UrgentReadState sealed class with behavior (#959)
- author zbnerd; closed; created 2026-06-06T09:04:48Z; closed 2026-06-06T09:04:59Z; merged yes/2026-06-06T09:04:59Z; merge commit 280329c29603f5fece239eaf042bd1e56af08aba. Body: Implements #959. Sealed class replaces enum. Behavior methods on subtypes. JSON contract preserved.
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1172.
- commits: 12 [6c5ab88, 50a1813, 03b3469, 1c186ce, 6bbac2a, c2eaa64, d88f12a, 8aa3d24, … +4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=9, MODIFIED=3; +2401/-15]. Sample: ADDED `docs/superpowers/plans/2026-06-06-chunk-consumer-template-state-machine.md` +580/-0; ADDED `docs/superpowers/plans/2026-06-06-chunk-execution-status-sealed.md` +646/-0; ADDED `docs/superpowers/plans/2026-06-06-urgent-read-state-sealed.md` +354/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1173 — refactor(synchronizer): convert FailureDecision to sealed class (#983)
- author zbnerd; closed; created 2026-06-06T09:16:27Z; closed 2026-06-06T09:19:02Z; merged no; merge commit —. Body: ## Summary Convert `FailureDecision` from a private data class with nullable `terminalReason` to a sealed class with `Retryable` a…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1173.
- commits: 20 [6c5ab88, 50a1813, 03b3469, 1c186ce, 6bbac2a, c2eaa64, d88f12a, 8aa3d24, … +12]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 22 [ADDED=10, MODIFIED=11, REMOVED=1; +2558/-96]. Sample: ADDED `docs/superpowers/plans/2026-06-06-chunk-consumer-template-state-machine.md` +580/-0; ADDED `docs/superpowers/plans/2026-06-06-chunk-execution-status-sealed.md` +646/-0; ADDED `docs/superpowers/plans/2026-06-06-urgent-read-state-sealed.md` +354/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: closed without merge; evidence is not treated as applied. Portfolio: 설계.

### PR #1174 — refactor(synchronizer): convert FailureDecision to sealed class (#983)
- author zbnerd; closed; created 2026-06-06T09:19:04Z; closed 2026-06-06T09:19:12Z; merged yes/2026-06-06T09:19:12Z; merge commit 6bae30f56e81380339e0c3e261f43b9bbf6000df. Body: ## Summary Convert `FailureDecision` from a private data class with nullable `terminalReason` to a sealed class with `Retryable` a…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1174.
- commits: 1 [981f6b4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [MODIFIED=5; +90/-53]. Sample: MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt` +71/-42; MODIFIED `module-synchronizer/src/test/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplateTest.kt` +3/-1; MODIFIED `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt` +7/-5. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1175 — refactor: #1098 ChunkConsumerTemplate cleanup + #1093 magic numbers (#1098 #1093)
- author zbnerd; closed; created 2026-06-06T11:14:09Z; closed 2026-06-06T11:15:01Z; merged yes/2026-06-06T11:15:01Z; merge commit b766ede36794dbbea661ad6df288b3a63160ca08. Body: Resolves two clean-code issues across the four active service modules. ## #1098 — ChunkConsumerTemplate failure classification - R…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1175.
- commits: 17 [37b8257, 39df6b8, 15df4c3, 4f745cc, 3918b5f, ed3feed, 116638c, 653d7ae, … +9]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 30 [ADDED=3, MODIFIED=27; +419/-49]. Sample: ADDED `docs/superpowers/specs/2026-06-06-1090-synchronizer-infra-extraction-design.md` +116/-0; ADDED `docs/superpowers/specs/2026-06-06-1093-magic-numbers-design.md` +140/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt` +4/-1. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1176 — refactor: 6 extraction refactors — batch 1 (#1083 #1080 #1074 #1061 #1087 #1084)
- author zbnerd; closed; created 2026-06-06T13:33:18Z; closed 2026-06-06T13:39:33Z; merged yes/2026-06-06T13:39:33Z; merge commit 9f6a93f1de3cd633a851e13fe78220a5d9b6e4f3. Body: Resolves six ready-for-agent extraction refactors across the four active service modules. ## #1083 — Rest-Controller: PopularChara…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1176.
- commits: 25 [4638af8, 1457325, 802b856, 3bb6930, 5841aef, 07a1c28, 79e5e10, abcd177, … +17]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 37 [ADDED=23, MODIFIED=14; +2291/-394]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1061-sink-event-publisher.md` +160/-0; ADDED `docs/superpowers/plans/2026-06-06-1074-batch-resolver-extraction.md` +135/-0; ADDED `docs/superpowers/plans/2026-06-06-1080-snapshot-chunk-processor-parser.md` +314/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1177 — refactor: #1071 dead dependencies + #1086 RuleBasedAnalyzer extraction
- author zbnerd; closed; created 2026-06-06T13:59:10Z; closed 2026-06-06T13:59:22Z; merged yes/2026-06-06T13:59:22Z; merge commit a2bcd214242ed97bdd23b4d683916051f0d72e6c. Body: Resolves two ready-for-agent refactors in module-infra (and small touches in module-calculator / module-rest-controller). ## #1071…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1177.
- commits: 7 [70b7e26, 2a6fd22, 01b1590, 766e7e7, 77f5a4a, 6863cc5, deb5e68]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [ADDED=3, MODIFIED=6; +433/-68]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1071-remove-dead-dependencies.md` +194/-0; ADDED `docs/superpowers/plans/2026-06-06-1086-rule-based-analyzer.md` +184/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/processor/CalculationCache.kt` +0/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1178 — refactor(synchronizer): split SynchronizerMetrics into meter registry + recording (#1066)
- author zbnerd; closed; created 2026-06-06T16:27:49Z; closed 2026-06-06T16:37:55Z; merged yes/2026-06-06T16:37:55Z; merge commit c6a0c724a228b718d4584b2e1b828a69af6d536d. Body: Decomposes `SynchronizerMetrics` (176 lines) into: - `SynchronizerMeterRegistry` (NEW, 117 lines) — owns all 19 meter declarations…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1178.
- commits: 8 [37b8257, 613f930, 2ded3fc, cc402c9, 336d4b5, fb5f068, ca4a333, 3991c75]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 7 [ADDED=5, MODIFIED=2; +1008/-137]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1066-synchronizer-metrics-split.md` +423/-0; ADDED `docs/superpowers/specs/2026-06-06-1066-synchronizer-metrics-split-design.md` +180/-0; ADDED `docs/superpowers/specs/2026-06-06-1090-synchronizer-infra-extraction-design.md` +116/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1179 — refactor(synchronizer): #1088 decompose flat consumers
- author zbnerd; closed; created 2026-06-06T16:33:31Z; closed 2026-06-06T16:36:13Z; merged yes/2026-06-06T16:36:13Z; merge commit e76f4a50d2d951b987ab7a4e06307ec3c045addf. Body: ## Summary - Extract `OcidLookupService` from `OcidLookupRunConsumer` (endpoint filter + file read + batch upsert + Redis write + …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1179.
- commits: 13 [37b8257, 488c722, f30c0ba, 5b0152e, 809ba34, 79518f9, d4cafe9, e1fb85a, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [ADDED=9, MODIFIED=4; +1500/-183]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1088-flat-consumer-decomposition.md` +767/-0; ADDED `docs/superpowers/specs/2026-06-06-1088-flat-consumer-decomposition-design.md` +201/-0; ADDED `docs/superpowers/specs/2026-06-06-batch-read-orchestration-extraction-design.md` +102/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1180 — refactor(988): decompose SnapshotChunkProcessor into parser + pipeline
- author zbnerd; closed; created 2026-06-06T16:43:31Z; closed 2026-06-06T16:48:00Z; merged yes/2026-06-06T16:48:00Z; merge commit 0d7f9ddca8f62d319638371eef28beca971bf55c. Body: ## Summary Decompose `SnapshotChunkProcessor.process()` (177 lines, 8 deps, 3 responsibilities) into: - **`SnapshotChunkParser`** …
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1180.
- commits: 9 [ebd5074, e26774e, ce3a302, ecdffe1, 6b8b5b6, 4a76e81, 9a6d45a, c411320, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 11 [ADDED=7, MODIFIED=3, REMOVED=1; +1440/-144]. Sample: ADDED `docs/superpowers/plans/2026-06-06-988-snapshot-chunk-decomposition.md` +813/-0; ADDED `docs/superpowers/specs/2026-06-06-988-snapshot-chunk-processor-decomposition-design.md` +253/-0; MODIFIED `module-calculator/build.gradle` +1/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1181 — refactor(rest-controller): #1081 decompose ReadModelQueryService.batchQuery
- author zbnerd; closed; created 2026-06-06T16:58:59Z; closed 2026-06-06T16:59:10Z; merged yes/2026-06-06T16:59:10Z; merge commit e5413c2537110653572854e7831ee9d8a9a5df42. Body: ## Summary - Extract `ReadModelRowQuery` (object): dynamic SQL + `MapSqlParameterSource` builder - Extract `StalenessCheck` (objec…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1181.
- commits: 12 [c4001c8, 47d5028, 99393d5, 7eb7fa4, cf48f90, bef2a62, f8a3e2e, 78de9d2, … +4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 12 [ADDED=9, MODIFIED=3; +2762/-61]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1081-readmodel-query-decomposition.md` +518/-0; ADDED `docs/superpowers/plans/2026-06-06-batch-read-orchestration-extraction.md` +1724/-0; ADDED `docs/superpowers/specs/2026-06-06-1081-readmodel-query-decomposition-design.md` +235/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1182 — refactor(1073): extract CalculationDispatchService (step 1/2)
- author zbnerd; closed; created 2026-06-06T17:10:21Z; closed 2026-06-06T17:10:46Z; merged yes/2026-06-06T17:10:46Z; merge commit 2cf2e36b2b03906ecf7253c8ab67e90db5bcc052. Body: ## Summary Extract 6 PGMQ-dispatch methods from `CalculationJobService` into a new `CalculationDispatchService` (step 1 of 2). `Ca…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1182.
- commits: 5 [a369ceb, d7eb0b6, b047923, 52eb583, 6bafcec]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 6 [ADDED=4, MODIFIED=2; +1281/-186]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1073-calculation-dispatch-extraction.md` +722/-0; ADDED `docs/superpowers/specs/2026-06-06-1073-calculation-dispatch-service-design.md` +216/-0; ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationDispatchService.kt` +111/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1183 — refactor: #986 + #991 + #961 + #1082 — ext-api phase split, Clock injection, rest-controll…
- author zbnerd; closed; created 2026-06-07T05:51:11Z; closed 2026-06-07T05:51:24Z; merged yes/2026-06-07T05:51:24Z; merge commit d8314f714848b914ca8da00f60727b1baee06070. Body: Resolves four ready-for-agent refactors across module-external-api and module-rest-controller. ## #986 — SnapshotFetchPhase endpoi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1183.
- commits: 24 [8976fca, 0825f70, 88da03f, feecfc0, 38f32fa, 1551393, 81cd30d, bd9af91, … +16]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 31 [ADDED=12, MODIFIED=18, REMOVED=1; +1613/-483]. Sample: ADDED `docs/superpowers/plans/2026-06-06-1082-batch-scheduler-orchestration.md` +208/-0; ADDED `docs/superpowers/plans/2026-06-06-961-instant-clock-migration.md` +150/-0; ADDED `docs/superpowers/plans/2026-06-06-986-snapshot-fetch-phase-split.md` +239/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1184 — refactor: #985 + #1060 + #1089 — ChunkExecutionStateMachine, ReadModelCacheService split, …
- author zbnerd; closed; created 2026-06-07T08:06:47Z; closed 2026-06-07T08:07:00Z; merged yes/2026-06-07T08:07:00Z; merge commit cc60d43e28fa8a2cf688728005d887dd0caf1712. Body: Resolves three ready-for-agent refactors across module-synchronizer and module-rest-controller. ## #985 — ChunkExecutionStateMachi…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1184.
- commits: 8 [b5e6f1c, 7e5759a, 62a0fd5, 65eba9a, ab5f41a, 6b30104, 0be7ab8, 68af5c4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 19 [ADDED=5, MODIFIED=14; +481/-272]. Sample: MODIFIED `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt` +26/-6; MODIFIED `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` +16/-4; MODIFIED `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchResolver.kt` +7/-5. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1185 — refactor(external-api): #987 extract SnapshotSinkEventPublisher from ChunkedSnapshotSink
- author zbnerd; closed; created 2026-06-07T10:02:13Z; closed 2026-06-07T10:10:24Z; merged yes/2026-06-07T10:10:24Z; merge commit 9596d4722f4013281f8461505b2814f4400be58e. Body: Moves event DTO construction, volume metrics, and the snapshotVolume log line from `ChunkedSnapshotSink` into a new `SnapshotSinkE…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1185.
- commits: 13 [bd2a720, 622e736, 80ba67f, c60bc02, ca92478, 81bf174, 214222c, 2886b4e, … +5]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 8 [ADDED=4, MODIFIED=4; +1160/-71]. Sample: ADDED `docs/superpowers/plans/2026-06-07-snapshot-sink-event-publisher.md` +764/-0; ADDED `docs/superpowers/specs/2026-06-07-snapshot-sink-event-publisher-design.md` +142/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` +6/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1186 — refactor(1085): extract OcidResolutionOrchestrator + ApiDataFetchOrchestrator from Calcula…
- author zbnerd; closed; created 2026-06-07T10:12:59Z; closed 2026-06-07T10:13:18Z; merged yes/2026-06-07T10:13:18Z; merge commit 24e80a870d794d067fb61cc6013f4030f44c74e0. Body: Resolves #1085 (step 2/2 of #1073). ## What Extract two new orchestrators from `CalculationJobService`: - **`OcidResolutionOrchest…
- reviews/discussion: 0 reviews [none]; 0 review comments; 0 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1186.
- commits: 7 [61c8670, 49abceb, 2f1eb9a, 2abd505, 0c2105f, ce5a3f3, cbe3a09]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 9 [ADDED=4, MODIFIED=5; +425/-132]. Sample: ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/ApiDataFetchOrchestrator.kt` +83/-0; MODIFIED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` +0/-102; ADDED `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OcidResolutionOrchestrator.kt` +55/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1187 — refactor(synchronizer): add ChunkPipelineOrchestrator (PR2 of #990)
- author zbnerd; closed; created 2026-06-07T10:18:13Z; closed 2026-06-07T10:20:06Z; merged yes/2026-06-07T10:20:06Z; merge commit 7ef9a247726219cc588db8dbac044a7b473a822d. Body: ## Summary - New `ChunkPipelineOrchestrator` in `module-synchronizer/adapter/chunk/` (TDD, 7 unit tests covering happy path, stage…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1187.
- commits: 4 [702a04d, 84e8aaa, 169bf52, 0134f3a]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 5 [ADDED=3, MODIFIED=2; +357/-124]. Sample: ADDED `docs/01_ADR/ADR-026-chunk-pipeline-orchestrator.md` +83/-0; ADDED `module-synchronizer/src/main/kotlin/maple/synchronizer/adapter/chunk/ChunkPipelineOrchestrator.kt` +53/-0; MODIFIED `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt` +14/-28. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1190 — refactor: #992 — split SynchronizerMetrics by domain (ChunkExecution, DocumentVolume)
- author zbnerd; closed; created 2026-06-07T10:47:44Z; closed 2026-06-07T11:07:45Z; merged yes/2026-06-07T11:07:45Z; merge commit 4a5590c030f85779a2fb9fea0b2848b1bc6e42d2. Body: ## Issue Closes #992 ## Summary Split `SynchronizerMetrics` (70 lines, 22 methods, 5 domains) into 3 cohesive `@Component` classes…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1190.
- commits: 12 [8ecad90, ee39021, 31ef6b1, 173d2c3, 005eccb, bcfced4, 005cc71, 433d8e2, … +4]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 13 [ADDED=7, MODIFIED=6; +1344/-80]. Sample: ADDED `docs/superpowers/plans/2026-06-07-synchronizer-metrics-domain-split.md` +672/-0; ADDED `docs/superpowers/specs/2026-06-07-989-chunk-file-manager-extraction-design.md` +222/-0; ADDED `docs/superpowers/specs/2026-06-07-synchronizer-metrics-domain-split-design.md` +158/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1191 — refactor(1057): BatchProgress + EndpointSinkFactory in external-api phases
- author zbnerd; closed; created 2026-06-07T10:49:04Z; closed 2026-06-07T10:49:14Z; merged yes/2026-06-07T10:49:14Z; merge commit ae4bc4a6229d16381e36f91b2c9d4cceabad90a6. Body: Resolves #1057 (scope narrowed — `SnapshotFetchPhase` was dropped in #986 / PR #1183). ## What ### BatchProgress — shared batch st…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1191.
- commits: 9 [0f0035a, 11031c5, 20f6a86, 71d020e, 9529cc5, dc04670, cffa422, 612d15a, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 10 [ADDED=3, MODIFIED=6, REMOVED=1; +179/-115]. Sample: MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt` +9/-11; ADDED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchProgress.kt` +28/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/CharacterBasicFetchPhase.kt` +3/-25. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1192 — refactor: #989 extract ChunkFileManager from ChunkedSnapshotSink
- author zbnerd; closed; created 2026-06-07T11:04:31Z; closed 2026-06-07T11:04:53Z; merged yes/2026-06-07T11:04:53Z; merge commit f83322b0db7e88e60dee279b5d36e7830b2b726f. Body: ## Summary - Issue #989: extract file I/O + chunk rotation from ChunkedSnapshotSink into new ChunkFileManager class - Sink reduces…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1192.
- commits: 2 [307b8ab, aeb146d]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=1, MODIFIED=2; +141/-99]. Sample: ADDED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkFileManager.kt` +120/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/ChunkedSnapshotSink.kt` +13/-96; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/snapshot/EndpointSinkFactory.kt` +8/-3. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1193 — feat(external-api): add FetchProgressTracker wrapping BatchProgress (#1062)
- author zbnerd; closed; created 2026-06-07T11:32:00Z; closed 2026-06-07T11:34:47Z; merged yes/2026-06-07T11:34:47Z; merge commit 7ea9ab9fa55ca933bde877d39ecca3ebe0e2f1f3. Body: ## Summary Adds `FetchProgressTracker` per issue #1062 spec. The foundational work (BatchProgress data class + EndpointSinkFactory…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1193.
- commits: 2 [0a1a47b, be720a2]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 3 [ADDED=3; +201/-0]. Sample: ADDED `docs/01_ADR/ADR-027-batch-progress-sink-factory.md` +91/-0; ADDED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/FetchProgressTracker.kt` +49/-0; ADDED `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/FetchProgressTrackerTest.kt` +61/-0. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 기능.

### PR #1194 — refactor(966): decompose SchedulerPhaseUtils God Object into 6 single-responsibility compo…
- author zbnerd; closed; created 2026-06-07T11:36:22Z; closed 2026-06-07T11:36:45Z; merged yes/2026-06-07T11:36:45Z; merge commit 3dfa32898d8db91d1f355cb38bef0110cae90d63. Body: Resolves #966. ## What `SchedulerPhaseUtils` (internal object with 6 distinct responsibilities + 6 `Instant.now()` calls + filesys…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1194.
- commits: 15 [e59cded, 118271f, 53357a3, 82ae297, 101afd0, d628d43, fe6c5b0, f0452a4, … +7]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 22 [ADDED=13, MODIFIED=7, REMOVED=2; +1301/-134]. Sample: ADDED `docs/superpowers/plans/2026-06-06-966-scheduler-phase-utils-decomposition.md` +712/-0; ADDED `docs/superpowers/specs/2026-06-06-966-scheduler-phase-utils-decomposition-design.md` +268/-0; MODIFIED `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/BatchFetchSupport.kt` +7/-4. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #1195 — refactor(cleanup): port cleanup schedulers from ext/calc to Airflow + module-cleanup
- author zbnerd; closed; created 2026-06-07T17:38:33Z; closed 2026-06-07T17:40:29Z; merged yes/2026-06-07T17:40:29Z; merge commit 5bec31f43bb52534a9ee3104a5dd816239bb5b90. Body: ## Summary - New `module-cleanup` Spring Boot app (:8084) hosts all cleanup logic + 3 HTTP endpoints (`/api/internal/cleanup/{runs…
- reviews/discussion: 0 reviews [none]; 0 review comments; 1 conversation comments. Complete IDs, timestamps, and body hashes: [machine-readable companion](./pr_detail_inventory.jsonl), PR #1195.
- commits: 9 [f7e7d70, 18dc681, 2f16534, a23a499, 1381703, 150fd7a, 54a367d, 2542e4c, … +1]; linked issues: 0 [none]. All 40-character connected SHAs and formal `closingIssuesReferences`: [machine-readable companion](./pr_detail_inventory.jsonl).
- file evidence: 26 [ADDED=15, MODIFIED=4, REMOVED=6, RENAMED=1; +2056/-594]. Sample: ADDED `airflow/dags/cleanup_pipeline.py` +57/-0; ADDED `docs/superpowers/plans/2026-06-07-cleanup-airflow-port.md` +1347/-0; MODIFIED `module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt` +1/-2. Complete paginated paths/counts: [machine-readable companion](./pr_detail_inventory.jsonl).
- Actual: merged by GitHub metadata; paginated file/commit/discussion evidence above. Portfolio: 설계.

### PR #721 — DOCS: V5 Query-Only 서버 분리 아키텍처 다이어그램
- author zbnerd; MERGED; created 2026-04-19T06:52:25Z; closed 2026-04-19T06:52:34Z; merged yes/2026-04-19T06:52:34Z; merge commit c95293b54d2fdb37996f0657ba0a463f2900250e. Body: ## Summary - Query-Only 서버 분리 시 아키텍처 분석 다이어그램 - 현재 모놀리식 구조 vs 분리 후 구조 비교 - Query Server / Calculatio…
- reviews 0 []; discussion 0; commits 1 [e1d2f71]; linked issues 0 [].
- file evidence 1 [ADDED docs/04_Sequence_Diagrams/v5-query-server-separation.md +141/-0]. Actual: merged; file/commit evidence above. Portfolio: 문서화.

### PR #722 — REFACTOR : Externalize cache TTL and key version to YAML config
- author zbnerd; MERGED; created 2026-04-19T09:12:23Z; closed 2026-04-19T09:13:02Z; merged yes/2026-04-19T09:13:02Z; merge commit 629a713d0eb35faf3aa2c2990b553317c2c55fbc. Body: ## 🔗 관련 이슈 V5 Query Server 분리 (Phase 1 Java 변경사항) ## 🗣 개요 PostgresL2CacheAdapter의 하드코딩된 TTL 상수(15L)와…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [a7bb0ba]; linked issues 0 [].
- file evidence 7 [MODIFIED module-app/src/main/resources/application.yml +2/-0; MODIFIED module-app/src/test/kotlin/maple/expectation/integration/cache/MultiInstanceCacheInvalidationTest.kt +3/-2; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheFactory.kt +21/-7]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #723 — DOCS : V5 Query Server 분리 Phase 1 Next.js 구현 가이드
- author zbnerd; MERGED; created 2026-04-19T09:18:49Z; closed 2026-04-19T09:19:38Z; merged yes/2026-04-19T09:19:38Z; merge commit 6998c037ee7dadf8c6183e2c7c7d479f4347b16f. Body: ## 🔗 관련 이슈 See also: #722 ## 🗣 개요 V5 Query Server를 Next.js로 분리하기 위한 Phase 1 구현 가이드 문서. ## 🛠 작업 내용 - …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [61ff458]; linked issues 0 [].
- file evidence 1 [ADDED docs/09_Plans/v5-query-server-nextjs-separation.md +265/-0]. Actual: merged; file/commit evidence above. Portfolio: 문서화.

### PR #725 — fix(pgmq): write pipeline debugging + non-blocking worker + Kafka ADR
- author zbnerd; MERGED; created 2026-04-19T17:35:51Z; closed 2026-04-19T17:36:04Z; merged yes/2026-04-19T17:36:04Z; merge commit 01d2824fc93026daa4a724f8ef669825ea6c34de. Body: ## Summary - 96%→0.7% 실패율 개선: btree(JSONB) 인덱스 제거, Kotlin varargs 수정, 캐시 오염 방어 - Head-of-line blocki…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 9 [3b6be30,32f5fcf,7e00c65]; linked issues 0 [].
- file evidence 28 [MODIFIED CLAUDE.md +21/-0; ADDED docs/01_ADR/ADR-btree-jsonb-index-removal.md +105/-0; ADDED docs/01_ADR/ADR-pgmq-kafka-migration.md +143/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #727 — feat: PGMQ migration, V5 query server, two-phase batch UPSERT
- author zbnerd; MERGED; created 2026-04-19T20:28:24Z; closed 2026-04-19T20:34:18Z; merged yes/2026-04-19T20:34:18Z; merge commit 2e46b7fc0b3a501c1936c578bc379b143b648830. Body: ## Summary - **Two-phase batch UPSERT**: Split PGMQ worker into parallel calculation (Phase 1) + bat…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 29 [a772514,6535191,e8e68d1]; linked issues 0 [].
- file evidence 191 [MODIFIED .gitignore +1/-0; MODIFIED CLAUDE.md +21/-0; ADDED docs/01_ADR/ADR-704-multi-instance-cache-invalidation-test.md +62/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #728 — fix: resolve Codex review issues from PRs #697-727
- author zbnerd; MERGED; created 2026-04-19T20:50:26Z; closed 2026-04-19T20:52:14Z; merged yes/2026-04-19T20:52:14Z; merge commit e5bbf80ba3216cd6cc0595ecb6a0dcd7fe175655. Body: ## Summary Resolve all actionable Codex (AI code review) findings from PRs #697-727. 6 of 13 issues …
- reviews 0 []; discussion 0; commits 1 [1b089f3]; linked issues 0 [].
- file evidence 9 [MODIFIED module-app/src/main/java/maple/expectation/application/service/task/TaskStatusService.java +8/-8; MODIFIED module-app/src/test/java/maple/expectation/service/v5/TaskStatusServiceTest.java +4/-2; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserService.kt +6/-20]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #729 — feat(pgmq): pipeline architecture — ConcurrentQueue + Scheduled Drain
- author zbnerd; MERGED; created 2026-04-20T08:46:31Z; closed 2026-04-20T08:47:02Z; merged yes/2026-04-20T08:47:02Z; merge commit 19cb8bd761304253108ee03c4b9570a57149d2ba. Body: ## Summary - Replace `CompletableFuture.allOf().join()` batch synchronization bottleneck with Concur…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 6 [d31ea20,3a9686b,082d5bd]; linked issues 0 [].
- file evidence 8 [ADDED docs/superpowers/plans/2026-04-20-pgmq-pipeline.md +608/-0; ADDED docs/superpowers/specs/2026-04-20-pgmq-pipeline-design.md +140/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +58/-30]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #730 — feat(pgmq): pipeline two-phase batch + performance tuning
- author zbnerd; MERGED; created 2026-04-20T12:36:50Z; closed 2026-04-20T12:37:14Z; merged yes/2026-04-20T12:37:14Z; merge commit 527196f74bc8a3912587a7362222dac05d17380f. Body: ## Summary - PGMQ 워커 two-phase batch 파이프라인 구현 (calculateOnly → batchWrite) - 1-pass 프리셋 파싱 최적화 (+43%…
- reviews 0 []; discussion 0; commits 15 [d31ea20,3a9686b,082d5bd]; linked issues 0 [].
- file evidence 24 [ADDED docs/01_ADR/ADR-360-pgmq-pipeline-load-test-tuning.md +121/-0; ADDED docs/05_Reports/LOAD_TEST_PGMQ_PIPELINE_20260420.md +125/-0; ADDED docs/05_Reports/LOAD_TEST_PGMQ_PIPELINE_20260420_R2.md +226/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #731 — perf: eliminate CalculateOnly timeout ceiling + capacity tuning
- author zbnerd; MERGED; created 2026-04-20T17:17:01Z; closed 2026-04-20T17:17:13Z; merged yes/2026-04-20T17:17:13Z; merge commit 46993fb5428453b1d90e56a4960d48ca1b773739. Body: ## Summary - **Bounded fan-out**: Per-request `Semaphore(8)` in `PresetCalculationHelper` limits ite…
- reviews 0 []; discussion 0; commits 6 [d0352eb,cb8f54b,d399c61]; linked issues 0 [].
- file evidence 8 [MODIFIED docs/05_Reports/SLOW_TASK_ANALYSIS.md +61/-82; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +9/-12; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java +99/-19]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #741 — refactor(arch): fix 5 hexagonal architecture violations
- author zbnerd; MERGED; created 2026-04-21T21:47:16Z; closed 2026-04-21T21:54:31Z; merged yes/2026-04-21T21:54:31Z; merge commit 1a3856f35eaa8688cf7b680bcc888f95a1f3b5ee. Body: ## Summary - Move shared DTOs (`CubeCalculationInput`, `EquipmentCalculationInput`, `EquipmentExpect…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 3 [b1b3387,5ed51ea,593dfda]; linked issues 0 [].
- file evidence 61 [ADDED docs/01_ADR/ADR-hexagonal-architecture-violation-fixes.md +60/-0; MODIFIED module-app/src/main/java/maple/expectation/application/mapper/EquipmentMapper.java +1/-1; MODIFIED module-app/src/main/java/maple/expectation/application/service/calculator/ExpectationCalculatorFactory.java +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #742 — perf: atomic PGMQ dedup, read-only TX, REST API fixes
- author zbnerd; MERGED; created 2026-04-21T22:19:55Z; closed 2026-04-21T22:38:44Z; merged yes/2026-04-21T22:38:44Z; merge commit 61db363bf734459476bb1a98fd163fd9fdb19e5b. Body: ## Summary ### Concurrency (Commit 1, 4) - **V112 migration**: Expression indexes on `(message ->> '…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 6 [be9a789,f35c4c4,04f56d7]; linked issues 0 [].
- file evidence 20 [ADDED docs/01_ADR/ADR-pgmq-atomic-dedup-monotonic-upsert.md +73/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +14/-17; ADDED module-app/src/test/java/maple/expectation/service/v5/CalculationQueuePortAdapterTest.java +158/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #744 — refactor: remove Semaphore(64) + pipeline ADR + V5 flow diagrams
- author zbnerd; MERGED; created 2026-04-22T13:30:11Z; closed 2026-04-22T13:38:08Z; merged yes/2026-04-22T13:38:07Z; merge commit dcaf29de2b18f72c2f194d142ba8eec4fdd1ec82. Body: ## Summary - **#733 Semaphore(64) 제거**: `PresetCalculationHelper`에서 불필요한 Semaphore 제거. ThreadPool ma…
- reviews 0 []; discussion 0; commits 1 [2773210]; linked issues 0 [].
- file evidence 2 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java +2/-14; MODIFIED module-app/src/main/resources/application.yml +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #745 — refactor: bulk JDBC upsert for view batch writes (#734)
- author zbnerd; MERGED; created 2026-04-22T13:53:21Z; closed 2026-04-22T14:11:53Z; merged yes/2026-04-22T14:11:53Z; merge commit 7fe91d0bd754e861ff20fc90013718705f98b6b4. Body: ## Summary - Replace per-row JPA (30-120 DB round trips per batch) with bulk JDBC using `CharacterVi…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [499b9ba,d3f6aea]; linked issues 0 [].
- file evidence 4 [ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CharacterViewBatchRepository.kt +154/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt +29/-14; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcLowWorker.kt +6/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #746 — refactor: Virtual Thread → FixedThreadPool in PgmqWorker (#735)
- author zbnerd; MERGED; created 2026-04-22T14:20:13Z; closed 2026-04-22T14:31:32Z; merged yes/2026-04-22T14:31:32Z; merge commit 0321e9d57ddbe4258c0461d019c3f23d589a22e1. Body: ## Summary - Replace `newVirtualThreadPerTaskExecutor()` with `newFixedThreadPool(workerPoolSize)` i…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [f13d3a4]; linked issues 0 [].
- file evidence 3 [MODIFIED module-app/src/main/resources/application-local.yml +2/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +22/-2; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt +3/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #747 — refactor: remove BlockingSubmitExecutor wrapper (#738)
- author zbnerd; MERGED; created 2026-04-22T14:37:44Z; closed 2026-04-22T14:37:59Z; merged yes/2026-04-22T14:37:59Z; merge commit 31d8025a37a51c17009a3be4e19e06142092327b. Body: ## Summary - Delete `BlockingSubmitExecutor.kt` — spin-wait retry loop unnecessary after #735 (VT→Fi…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [10f4f9b]; linked issues 0 [].
- file evidence 2 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/config/ItemCalculationExecutorConfig.kt +2/-20; DELETED module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/BlockingSubmitExecutor.kt +0/-53]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #748 — refactor: align PipelineBuffer capacity with maxInflight (#739)
- author zbnerd; MERGED; created 2026-04-22T14:51:21Z; closed 2026-04-22T14:51:37Z; merged yes/2026-04-22T14:51:37Z; merge commit 0837f2f0179ad6c0b5343fd813140855007200b1. Body: ## Summary - Buffer capacity = `maxInflight * 2` (auto-calculated from semaphore, removes independen…
- reviews 0 []; discussion 0; commits 1 [7ac7bf9]; linked issues 0 [].
- file evidence 2 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +4/-6; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt +0/-2]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #749 — feat(#743): compute key dedup for batch cube trials
- author zbnerd; MERGED; created 2026-04-22T17:02:41Z; closed 2026-04-22T19:03:35Z; merged yes/2026-04-22T19:03:35Z; merge commit 13e478cf6a98ae5abafe2723921508be20e166a5. Body: ## Summary - Add `CubeComputeKey` data class for deduplicating cube trial computations within a PGMQ…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 12 [5dcfe95,2d80fc7,ca8c300]; linked issues 0 [].
- file evidence 18 [MODIFIED load_test_v5.py +1/-1; MODIFIED module-app/src/main/java/maple/expectation/application/service/cube/CubeServiceImpl.java +6/-2; ADDED module-app/src/main/java/maple/expectation/application/service/cube/component/CubeComputeBuffer.java +53/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #750 — feat(cache): time-window batch L2 lookup
- author zbnerd; MERGED; created 2026-04-22T21:09:42Z; closed 2026-04-22T21:09:52Z; merged yes/2026-04-22T21:09:51Z; merge commit 34c402ecc5b9c2ccbdc822e478077e9704dfb01e. Body: ## Summary - Add `BatchL2LookupBuffer` (10ms time-window) that collects L1-miss keys from concurrent…
- reviews 0 []; discussion 0; commits 1 [4cf78ec]; linked issues 0 [].
- file evidence 2 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt +91/-6; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/BatchL2LookupBuffer.kt +146/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #751 — perf(cache): batch L2 writes to eliminate individual UPSERT queries
- author zbnerd; MERGED; created 2026-04-22T21:34:41Z; closed 2026-04-22T21:34:51Z; merged yes/2026-04-22T21:34:51Z; merge commit 36ce2960bd4e1fcd76bc443eaf6731763fd7a6ff. Body: ## Summary - Add `BatchL2WriteBuffer` for time-window batching (10ms) of L2 cache writes - Immediate…
- reviews 0 []; discussion 0; commits 1 [36ce296]; linked issues 0 [].
- file evidence 3 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt +21/-0; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/BatchL2WriteBuffer.kt +120/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/tiered/PostgresL2CacheFactory.kt +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #752 — feat(api): single preset calculation with presetNo parameter
- author zbnerd; MERGED; created 2026-04-22T22:11:33Z; closed 2026-04-22T22:11:42Z; merged yes/2026-04-22T22:11:42Z; merge commit c936243a82eb976711076b58ea83ae8efd677d38. Body: ## Summary - Add `presetNo` parameter (1-3) to V5 expectation API endpoint - Thread presetNo through…
- reviews 0 []; discussion 0; commits 1 [c936243]; linked issues 0 [].
- file evidence 27 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +56/-45; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +75/-50; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java +17/-3]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #753 — feat: single preset optimization + parseSinglePreset + batch L2 writes
- author zbnerd; MERGED; created 2026-04-22T22:49:00Z; closed 2026-04-22T22:49:08Z; merged yes/2026-04-22T22:49:08Z; merge commit 44658c41262387ef34029c8d81c449a3c8051334. Body: ## Summary - **parseSinglePreset()**: 지정된 presetNo(1-3)의 장비 배열만 파싱하여 ~3x 파싱 속도 향상 - **presetNo 파이프라인…
- reviews 0 []; discussion 0; commits 2 [c4d513b,b760afe]; linked issues 0 [].
- file evidence 4 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +1/-3; MODIFIED module-app/src/main/java/maple/expectation/parser/EquipmentStreamingParser.java +43/-0; ADDED module-infra/src/main/resources/db/migration/V113__add_preset_no_to_views.sql +10/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #754 — fix(jackson): KotlinModule 등록 + L2 캐시 비활성화
- author zbnerd; MERGED; created 2026-04-23T10:47:37Z; closed 2026-04-23T11:08:14Z; merged yes/2026-04-23T11:08:14Z; merge commit d8bae956f15c41d191b3c025c4f020b8f8499718. Body: ## Summary - **Jackson KotlinModule 역직렬화 버그 수정**: `Jackson2ObjectMapperBuilderCustomizer.builder.mod…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [5076acc,7c10986]; linked issues 0 [].
- file evidence 6 [MODIFIED gradle/libs.versions.toml +1/-1; MODIFIED module-app/src/main/resources/application-local.yml +4/-5; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/config/CaffeineOnlyCacheConfig.kt +12/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #755 — docs: ADR - 27x PGMQ pipeline performance journey (04-19~04-23)
- author zbnerd; MERGED; created 2026-04-23T11:20:33Z; closed 2026-04-23T11:22:19Z; merged yes/2026-04-23T11:22:19Z; merge commit 773c3474200802e50b6b983fed51bbfb7d9cd545. Body: ## Summary - Comprehensive ADR documenting the 5-day performance optimization journey - 3.3/sec → 90…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [75b3b0b,284fee6]; linked issues 0 [].
- file evidence 1 [ADDED docs/01_ADR/ADR-pgmq-calculation-pipeline-perf-27x.md +498/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #756 — Merge develop → master: 27x PGMQ pipeline performance optimization
- author zbnerd; MERGED; created 2026-04-23T11:35:38Z; closed 2026-04-23T11:36:59Z; merged yes/2026-04-23T11:36:59Z; merge commit 1f0289396118fde8dfd6db7cf8f1c77e49d8e420. Body: ## Summary - 27x PGMQ 계산 파이프라인 성능 최적화 (PR #729~#755) - Single preset optimization + Jackson KotlinMo…
- reviews 0 []; discussion 1 /  ### 💡 Codex Review https://github.com/z…; commits 91 [a772514,6535191,e8e68d1]; linked issues 0 [].
- file evidence 106 [ADDED docs/01_ADR/ADR-360-pgmq-pipeline-load-test-tuning.md +121/-0; ADDED docs/01_ADR/ADR-hexagonal-architecture-violation-fixes.md +60/-0; ADDED docs/01_ADR/ADR-pgmq-atomic-dedup-monotonic-upsert.md +73/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #757 — fix: restore presetNo optimization — correct merge resolution
- author zbnerd; MERGED; created 2026-04-23T11:51:09Z; closed 2026-04-23T11:51:19Z; merged yes/2026-04-23T11:51:19Z; merge commit 6053714ba255bdfdf54f5d969b00a0e801c9fcd0. Body: ## Summary - 이전 병합(8b0d0853)에서 `--theirs`를 잘못 사용하여 master 구버전으로 29개 파일이 덮어씌워짐 - presetNo 최적화, 코루틴 병렬…
- reviews 0 []; discussion 0; commits 1 [11141b3]; linked issues 0 [].
- file evidence 30 [MODIFIED load_test_v5.py +1/-1; DELETED module-app/logs/trace.log +0/-32; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +64/-54]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #766 — chore: unify all profiles to use only DB_URL
- author zbnerd; MERGED; created 2026-04-27T18:11:16Z; closed 2026-04-27T18:12:12Z; merged yes/2026-04-27T18:12:12Z; merge commit 063061f69f903aa2a76b6e4e1c912560ef419f69. Body: ## Summary - 모든 Spring 프로필(prod, vultr, local, pglocal, ci)의 datasource 설정을 `DB_URL` 단일 환경변수로 통일 - 프…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [c503195]; linked issues 0 [].
- file evidence 5 [MODIFIED module-app/src/main/resources/application-ci.yml +1/-4; MODIFIED module-app/src/main/resources/application-local.yml +1/-4; MODIFIED module-app/src/main/resources/application-pglocal.yml +1/-3]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #767 — feat: External API Boundary Separation — MQ-based async pipeline
- author zbnerd; MERGED; created 2026-04-27T21:10:57Z; closed 2026-04-27T21:12:14Z; merged yes/2026-04-27T21:12:14Z; merge commit c6fcf9cb2b808de2cfc1655d3fa06b7f71dc9d27. Body: ## Summary - PGMQ 기반 비동기 상태머신으로 External API 호출과 Write Path를 완전히 분리 - `calculation_jobs` (상태머신) + `c…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 15 [8539bb2,57497a7,9a111ec]; linked issues 0 [].
- file evidence 28 [ADDED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +89/-0; MODIFIED module-app/src/main/resources/application-local.yml +5/-0; MODIFIED module-app/src/main/resources/application-prod.yml +12/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #768 — feat: External API Boundary Separation — fully async PGMQ pipeline
- author zbnerd; MERGED; created 2026-04-27T21:53:19Z; closed 2026-04-27T22:23:03Z; merged yes/2026-04-27T22:23:03Z; merge commit 5a7ced44b542a8351ba1578a0ac9eced3bbb4639. Body: ## Summary - External API 호출(Nexon OCID resolve + Equipment fetch)을 요청 스레드에서 완전히 분리하여 PGMQ 기반 비동기 상태…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 5 [70276e8,9a98f30,eec5d88]; linked issues 0 [].
- file evidence 21 [MODIFIED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +26/-2; MODIFIED module-app/src/main/resources/application-prod.yml +8/-0; MODIFIED module-app/src/main/resources/application.yml +4/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #769 — fix: External API Boundary — PR review fixes and dead code cleanup
- author zbnerd; MERGED; created 2026-04-27T22:31:24Z; closed 2026-04-27T22:31:41Z; merged yes/2026-04-27T22:31:41Z; merge commit a78ded6564df19ff809fe1c416411df5ec55ec73. Body: ## Summary - Fix 4 P1 PR review issues from chatgpt-codex-connector - Add V116 migration for NULL OC…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [f30acb6]; linked issues 0 [].
- file evidence 3 [MODIFIED module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobStatus.kt +0/-3; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt +1/-1; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt +0/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #770 — feat: MQ Abstraction Layer (Phase 1) — 3 queues + 3 workers
- author zbnerd; MERGED; created 2026-04-28T01:36:49Z; closed 2026-04-28T01:43:18Z; merged yes/2026-04-28T01:43:18Z; merge commit 89b6a247f5174c1e8c58d71352937f20eb5daf99. Body: ## Summary - Introduce `MQTopicGroup` interface (`publish`/`subscribe`) and `DomainEventAppender` to…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 10 [1d266ff,2cf628a,1c22d35]; linked issues 0 [].
- file evidence 25 [ADDED docs/09_Plans/2026-04-28-external-api-boundary-separation.md +1361/-0; ADDED docs/superpowers/plans/2026-04-28-mq-abstraction-layer.md +1038/-0; MODIFIED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +63/-65]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #771 — feat: Write Path Snapshot Calculator
- author zbnerd; MERGED; created 2026-04-28T04:23:05Z; closed 2026-04-28T05:29:13Z; merged yes/2026-04-28T05:29:13Z; merge commit 2d6b7bbfb3dd0781fecde2427ebefa830a957258. Body: ## Summary - Write Path가 External API DTO(EquipmentResponse)를 완전히 차단 - Typed CalculationInput 계약 모델 …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 18 [33a8d79,44bdbb5,42b2c8a]; linked issues 0 [].
- file evidence 41 [ADDED docs/01_ADR/ADR-write-path-snapshot-calculator.md +258/-0; ADDED docs/superpowers/plans/2026-04-28-write-path-snapshot-calculator.md +1813/-0; ADDED docs/superpowers/specs/2026-04-28-write-path-snapshot-calculator-design.md +473/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #772 — refactor(write-path): 순수 계산 분리 — PureExpectationCalculator + .join() 제거
- author zbnerd; MERGED; created 2026-04-28T08:19:39Z; closed 2026-04-28T08:20:11Z; merged yes/2026-04-28T08:20:11Z; merge commit f565e71d3f6ab7f6d779a545d6ebfb68e84d6c56. Body: ## Summary - Write Path에서 fetch+calculate+persist 파이프라인의 순수 계산을 `PureExpectationCalculator`로 추출 - `A…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 6 [70b518f,99054b0,0c50cbd]; linked issues 0 [].
- file evidence 9 [ADDED module-app/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt +33/-0; MODIFIED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +5/-8; ADDED module-app/src/test/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculatorTest.kt +113/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #773 — refactor(write-path): CalculationJobService를 External API + Write Path로 분리
- author zbnerd; MERGED; created 2026-04-29T04:50:11Z; closed 2026-04-29T04:50:19Z; merged yes/2026-04-29T04:50:19Z; merge commit 7bafc43a57d3b7e65cedf43abb5b332372cdd013. Body: ## Summary - `CalculationJobService`를 경로별로 분리하여 External API Path와 Write Path의 결합도 제거 - Write Path 메…
- reviews 0 []; discussion 0; commits 1 [6b6f666]; linked issues 0 [].
- file evidence 4 [MODIFIED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +5/-5; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt +126/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt +1/-135]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #774 — fix: eliminate async pattern violations (41건)
- author zbnerd; MERGED; created 2026-04-29T06:49:07Z; closed 2026-04-29T07:01:40Z; merged yes/2026-04-29T07:01:40Z; merge commit 6758bb1aa2aedf929282f00291ed91bc820674f9. Body: ## Summary - `.join()` / `.get()` / `runBlocking` 23건 → CompletableFuture 체이닝 (thenApply, thenCompos…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [4730247,0be0f81]; linked issues 0 [].
- file evidence 141 [MODIFIED .claude/rules/workflow-rules.md +15/-2; MODIFIED .gitignore +8/-0; MODIFIED module-app/src/main/java/maple/expectation/application/mapper/EquipmentMapper.java +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #775 — fix: apply Codex review feedback PR 729-774 (17 items)
- author zbnerd; MERGED; created 2026-04-29T07:29:43Z; closed 2026-04-29T07:29:59Z; merged yes/2026-04-29T07:29:59Z; merge commit e39d9b7df00d4ad526dba0169cd2f64022fe15f0. Body: ## Summary - PR 729~774에서 수집한 Codex 리뷰 피드백 35건 중 실제 수정이 필요한 17건 반영 - P1: 13건 (Micrometer tags, batch…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [5e26ec7]; linked issues 0 [].
- file evidence 13 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +9/-6; MODIFIED module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItemConverter.kt +1/-1; MODIFIED module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt +2/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #776 — fix: code rules violations (null safety, Thread.sleep, try-catch)
- author zbnerd; MERGED; created 2026-04-29T11:17:07Z; closed 2026-04-29T11:17:37Z; merged yes/2026-04-29T11:17:37Z; merge commit 8906a5578cee52a43038c2c1c5ef87394a939b21. Body: ## Summary - **Null Safety (~48건)**: `!!` → `requireNotNull()`, `.orElse(null)` → `.orElseGet`, `.is…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [28bc160]; linked issues 0 [].
- file evidence 84 [MODIFIED module-app/src/main/java/maple/expectation/application/service/character/GameCharacterFacade.java +2/-2; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +2/-2; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +48/-60]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #777 — refactor: Read Path V5 ADR boundary cleanup (Phase 2)
- author zbnerd; MERGED; created 2026-04-29T11:55:49Z; closed 2026-04-29T11:55:58Z; merged yes/2026-04-29T11:55:58Z; merge commit ff56a59633f870c378f14037176ac5a2c7d7c94e. Body: ## Summary - Remove pre-warm (`EquipmentFanOutPort`) from V5 controller — was triggering Nexon API c…
- reviews 0 []; discussion 0; commits 1 [83afaa6]; linked issues 0 [].
- file evidence 14 [MODIFIED module-app/src/test/java/maple/expectation/service/v5/GameCharacterControllerV5Test.java +0/-30; MODIFIED module-core/src/main/kotlin/maple/expectation/core/port/inbound/CharacterViewQueryPort.kt +0/-7; DELETED module-core/src/main/kotlin/maple/expectation/core/port/out/EquipmentFanOutPort.kt +0/-29]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #778 — fix: widen equipment_persistence_tracker.ocid to VARCHAR(64)
- author zbnerd; MERGED; created 2026-04-29T12:23:44Z; closed 2026-04-29T12:24:48Z; merged yes/2026-04-29T12:24:48Z; merge commit c4e33def9e6a90e96b974bfbd8869d49d4c5ec59. Body: ## Summary - `equipment_persistence_tracker.ocid` 컬럼이 `VARCHAR(20)`이어서 실제 Nexon OCID 값 삽입 시 `DataInt…
- reviews 0 []; discussion 0; commits 1 [6ee943a]; linked issues 0 [].
- file evidence 1 [ADDED module-infra/src/main/resources/db/migration/V119__widen_equipment_persistence_tracker_ocid.sql +4/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #779 — refactor: consolidate 5 PGMQ queues into 3 with ExternalApiWorker
- author zbnerd; MERGED; created 2026-04-29T16:24:51Z; closed 2026-04-29T16:57:59Z; merged yes/2026-04-29T16:57:58Z; merge commit 083a19eeef903152e658b8560bebf002db050fd8. Body: ## Summary - Collapse `ocid_resolve_queue` + `nexon_api_request_queue` + `nexon_api_response_queue` …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [04fbb57]; linked issues 0 [].
- file evidence 18 [MODIFIED module-app/src/main/java/maple/expectation/application/service/character/GameCharacterFacade.java +9/-2; ADDED module-app/src/main/kotlin/maple/expectation/application/adapter/PureCalculationAdapter.kt +14/-0; MODIFIED module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt +2/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #780 — Fix/disable listen notify reduce pool supabase
- author zbnerd; MERGED; created 2026-04-29T19:49:32Z; closed 2026-04-29T19:51:13Z; merged yes/2026-04-29T19:51:13Z; merge commit 2d90af1e956769188ca73ee8e6c58f0d568b3a4a. Body: none
- reviews 0 []; discussion 1 /  ### 💡 Codex Review https://github.com/z…; commits 5 [04fbb57,d40fe2c,eb66c79]; linked issues 0 [].
- file evidence 1 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt +27/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #781 — perf: fix TimeoutScanner duplicate dispatch + reduce polling overhead
- author zbnerd; MERGED; created 2026-04-29T21:22:12Z; closed 2026-04-29T22:47:23Z; merged yes/2026-04-29T22:47:23Z; merge commit 38747e8aaae5433fdbab6eb1d34217a74d1d0dce. Body: ## Summary - TimeoutScanner이 legacy topic(ocidResolve/nexonApiRequest)이 아닌 통합 external_api_queue로 재시…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [21295d3]; linked issues 0 [].
- file evidence 2 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt +23/-9; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +4/-1]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #782 — refactor: V5 pipeline optimization (ADR, 6-step)
- author zbnerd; MERGED; created 2026-04-29T22:49:07Z; closed 2026-04-29T23:08:56Z; merged yes/2026-04-29T23:08:56Z; merge commit f827f5d061a4439248489f024ec22758ac966f55. Body: ## Summary ADR 기반 V5 파이프라인 최적화 — 10K 부하 테스트 병목 분석 결과 반영 - **P0 TimeoutScanner CAS**: atomic `increme…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [cb593e2,56ef315]; linked issues 0 [].
- file evidence 16 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +67/-136; MODIFIED module-app/src/main/java/maple/expectation/application/service/task/TaskStatusService.java +23/-51; MODIFIED module-app/src/main/resources/application-local.yml +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #783 — refactor: consolidate ExternalApiWorker DB calls (5→3 TX)
- author zbnerd; MERGED; created 2026-04-29T23:18:27Z; closed 2026-04-29T23:27:38Z; merged yes/2026-04-29T23:27:38Z; merge commit 3d6c4757cf599324bcc2e14cf3cab63668cad85f. Body: ## Summary ExternalApiWorker 파이프라인의 DB 트랜잭션을 5개에서 3개로 압축하여 커넥션 풀(45개) 회전율 개선. **Before (5 TX):** - T…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [e911166]; linked issues 0 [].
- file evidence 3 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt +52/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt +8/-4; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExternalApiWorker.kt +7/-20]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #784 — refactor: optimize DB write path — eliminate SELECT, move CPU outside TX
- author zbnerd; MERGED; created 2026-04-30T00:14:15Z; closed 2026-04-30T00:46:07Z; merged yes/2026-04-30T00:46:07Z; merge commit 44d53be44a6c68c475af2a298ad8deeeb4dbece9. Body: ## Summary - **P0**: Replace SELECT-before-INSERT with `INSERT ON CONFLICT DO NOTHING` for Calculati…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 3 [792ce58,9ac3c36,076b956]; linked issues 0 [].
- file evidence 17 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java +41/-0; MODIFIED module-app/src/main/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculator.kt +1/-3; MODIFIED module-app/src/test/kotlin/maple/expectation/application/service/expectation/PureExpectationCalculatorTest.kt +4/-5]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #785 — fix(v5): stabilize db throughput load test
- author zbnerd; MERGED; created 2026-04-30T19:43:01Z; closed 2026-04-30T19:43:12Z; merged yes/2026-04-30T19:43:12Z; merge commit 0dd790f5d45b1fed00ce335e40e99e626b0adefd. Body: ## Summary - Add DB-backed V5 throughput load-test runner that parses DB_URL from .env and samples v…
- reviews 0 []; discussion 0; commits 1 [b661aa2]; linked issues 0 [].
- file evidence 12 [ADDED AGENTS.md +15/-0; ADDED docs/superpowers/plans/2026-04-28-write-path-pure-calculate.md +826/-0; ADDED docs/superpowers/plans/2026-04-29-read-path-boundary-cleanup.md +127/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #786 — Optimize V5 throughput pipeline
- author zbnerd; MERGED; created 2026-05-01T00:45:05Z; closed 2026-05-01T00:45:14Z; merged yes/2026-05-01T00:45:14Z; merge commit 7f60ab846bbfad05322528126f9f47e8eb330550. Body: ## Summary - Batch result-ready projection into read model writes and archive processed PGMQ message…
- reviews 0 []; discussion 0; commits 1 [7037a7d]; linked issues 0 [].
- file evidence 19 [MODIFIED AGENTS.md +11/-0; MODIFIED load-test/run-v5-db-throughput.sh +68/-3; MODIFIED module-core/src/main/kotlin/maple/expectation/core/port/inbound/CharacterViewQueryPort.kt +14/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #787 — perf: request-key job dedup and projection stage tuning
- author zbnerd; MERGED; created 2026-05-01T10:20:06Z; closed 2026-05-01T10:20:54Z; merged yes/2026-05-01T10:20:54Z; merge commit 85451ab5c1e9e080f17f083aedb046f08561e3dc. Body: ## Summary - add request_key based active calculation job dedup and dispatch only newly-created jobs…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [2006661]; linked issues 0 [].
- file evidence 21 [MODIFIED .claude/rules/architecture-guardrails.md +2/-2; MODIFIED AGENTS.md +1/-0; ADDED docs/01_ADR/ADR-389-request-key-active-job-dedup.md +63/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #788 — Split calculation pipeline workers
- author zbnerd; MERGED; created 2026-05-01T10:31:53Z; closed 2026-05-01T10:32:47Z; merged yes/2026-05-01T10:32:47Z; merge commit 4d67063531fe70b043457ea258343602cfd0dcd9. Body: ## Summary - Split `ExternalApiWorker` so it only resolves OCID, fetches equipment, saves input/snap…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [6e2e5c2]; linked issues 0 [].
- file evidence 18 [MODIFIED module-app/src/main/resources/application-local.yml +10/-0; MODIFIED module-app/src/main/resources/application-pglocal.yml +15/-0; MODIFIED module-app/src/main/resources/application-pgprod.yml +15/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #789 — perf: consolidate calculation pipeline + outbox projection optimization
- author zbnerd; CLOSED; created 2026-05-01T12:42:10Z; closed 2026-05-01T12:44:13Z; merged no; merge commit —. Body: ## Summary - **Consolidate V5 pipeline**: ExternalApiWorker가 API 호출 → 계산 → 결과 저장을 인라인 처리하여 2개 PGMQ 홉…
- reviews 0 []; discussion 1 /  ### 💡 Codex Review https://github.com/z…; commits 5 [086c34d,1229923,2966e03]; linked issues 0 [].
- file evidence 15 [ADDED .claude/rules/load-test.md +59/-0; MODIFIED .claude/rules/workflow-rules.md +11/-0; MODIFIED .gitignore +5/-0]. Actual: closed, not merged; no application claim. Portfolio: 성능.

### PR #790 — perf: consolidate calculation pipeline + outbox projection optimization
- author zbnerd; MERGED; created 2026-05-01T12:44:28Z; closed 2026-05-01T12:45:09Z; merged yes/2026-05-01T12:45:09Z; merge commit bb675afdad07bf3ac426b30cc9156381e959b025. Body: ## Summary - **Consolidate V5 pipeline**: ExternalApiWorker가 API 호출 → 계산 → 결과 저장을 인라인 처리하여 2개 PGMQ 홉…
- reviews 0 []; discussion 0; commits 5 [086c34d,1229923,2966e03]; linked issues 0 [].
- file evidence 15 [ADDED .claude/rules/load-test.md +59/-0; MODIFIED .claude/rules/workflow-rules.md +11/-0; MODIFIED .gitignore +5/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #791 — perf: consolidate calculation pipeline + outbox projection optimization
- author zbnerd; MERGED; created 2026-05-01T13:39:06Z; closed 2026-05-01T13:40:24Z; merged yes/2026-05-01T13:40:24Z; merge commit ecaaf91d2aa2cf9f8c2b8a3d85acb25e4731361e. Body: ## Summary - **Consolidate V5 pipeline**: ExternalApiWorker가 API 호출 → 계산 → 결과 저장을 인라인 처리하여 2개 PGMQ 홉…
- reviews 0 []; discussion 0; commits 3 [bfaad01,68433ce,b1e1960]; linked issues 0 [].
- file evidence 0 []. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #792 — feat(kafka): Kafka pipeline transition (PR-1~3)
- author zbnerd; MERGED; created 2026-05-02T02:00:24Z; closed 2026-05-02T02:38:32Z; merged yes/2026-05-02T02:38:32Z; merge commit 14a1d843c0c2a56dd9d22651efbeb067004442e5. Body: ## Summary - **PR-1**: Kafka foundation — Spring Kafka 설정, 토픽 정의, outbox 테이블 마이그레이션, KafkaOutboxPubl…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 4 [4bbfe5b,e9dd8cc,aa81929]; linked issues 0 [].
- file evidence 22 [ADDED docs/09_Plans/2026-05-02-kafka-pipeline-transition-plan.md +812/-0; ADDED docs/superpowers/specs/2026-05-02-kafka-edge-cases-design.md +290/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +6/-10]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #793 — refactor: replace outbox events with direct PGMQ send
- author zbnerd; MERGED; created 2026-05-02T06:20:43Z; closed 2026-05-02T06:21:59Z; merged yes/2026-05-02T06:21:59Z; merge commit d894611de83be3185f853e6f1c5fa860339aac43. Body: ## Summary - `CalculationExecutionService`의 4개 완료 메서드에서 `outboxPort.insertIfAbsent()`을 `pgmqClient.s…
- reviews 0 []; discussion 1 / You have reached your Codex usage limits…; commits 1 [81962d4]; linked issues 0 [].
- file evidence 4 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt +11/-12; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt +2/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt +1/-94]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #794 — perf: async read model write + upsert optimization
- author zbnerd; MERGED; created 2026-05-02T08:38:56Z; closed 2026-05-02T08:39:36Z; merged yes/2026-05-02T08:39:36Z; merge commit b55ddb954e64e88be30d64e874434f3f1c6f5e54. Body: ## Summary - **Async read model write**: `saveToReadModel`/`saveToReadModelBatch`을 `asyncExecutor`(v…
- reviews 0 []; discussion 0; commits 5 [ba8e948,fc5b8b4,8b6a47c]; linked issues 0 [].
- file evidence 8 [MODIFIED .claude/rules/load-test.md +35/-4; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java +16/-11; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/StepTimer.kt +40/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #795 — perf: parallelize dual DB queries in ResultReadyProjectionWorker
- author zbnerd; MERGED; created 2026-05-02T08:43:43Z; closed 2026-05-02T08:44:00Z; merged yes/2026-05-02T08:44:00Z; merge commit e6bb7b82e8ea49a2f0d1631005183b44335131cd. Body: ## Summary - `loadCalculationResults`에서 두 개의 독립적인 DB 쿼리(`findJobsByIds`, `findByJobIds`)를 `Completab…
- reviews 0 []; discussion 0; commits 1 [db0606b]; linked issues 0 [].
- file evidence 1 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ResultReadyProjectionWorker.kt +14/-2]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #796 — perf: extract projection fields at write time to skip BYTEA decompress
- author zbnerd; MERGED; created 2026-05-02T09:27:37Z; closed 2026-05-02T12:02:02Z; merged yes/2026-05-02T12:02:02Z; merge commit 7d119488e4783b68fee7e0430b3a51a4472dbda6. Body: ## Summary - Add `total_expected_cost`, `max_preset_no`, `presets` columns to `calculation_results` …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 3 [c961de4,e1f322b,a5b9606]; linked issues 0 [].
- file evidence 13 [MODIFIED module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt +14/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt +40/-14; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationExecutionService.kt +12/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #797 — perf: merge pre-calculation DB writes into single TX
- author zbnerd; CLOSED; created 2026-05-02T12:47:59Z; closed 2026-05-02T23:10:15Z; merged no; merge commit —. Body: ## Summary - **P0-1**: Pass in-memory `CalculationInput` directly to `runCalculationAndComplete`, el…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 0 []; linked issues 0 [].
- file evidence 0 []. Actual: closed, not merged; no application claim. Portfolio: 성능.

### PR #798 — feat: pipeline restructuring, Supabase migration & performance optimization (Ch.…
- author zbnerd; MERGED; created 2026-05-03T12:31:08Z; closed 2026-05-03T12:31:16Z; merged yes/2026-05-03T12:31:16Z; merge commit 401101cb6ab673e8a4441fca3a618840cf2c4aa5. Body: ## Summary - 3-stage PGMQ 파이프라인 분리 (단일 워커 → 계산/API/Projection 독립 스테이지) - Supabase Pooler 마이그레이션 (Vul…
- reviews 0 []; discussion 0; commits 143 [b5faa2b,85c5bb2,67928fe]; linked issues 0 [].
- file evidence 323 [ADDED .claude/rules/architecture-guardrails.md +32/-0; ADDED .claude/rules/async-concurrency.md +43/-0; ADDED .claude/rules/async-patterns.md +41/-0]. Actual: merged; file/commit evidence above. Portfolio: 성능.

### PR #799 — docs: rewrite Chapter 13 — pipeline restructuring & Supabase migration
- author zbnerd; MERGED; created 2026-05-03T12:41:20Z; closed 2026-05-03T12:44:22Z; merged yes/2026-05-03T12:44:22Z; merge commit 6670cdfb099e2c5e438bba72a1dd04cbc4c33477. Body: ## Summary - Comprehensive rewrite of Chapter 13 (pipeline restructuring & Supabase migration journe…
- reviews 0 []; discussion 0; commits 1 [458d1c0]; linked issues 0 [].
- file evidence 1 [MODIFIED docs/06_Performance_Journey/13_pipeline_restructuring_supabase.md +362/-179]. Actual: merged; file/commit evidence above. Portfolio: 문서화.

### PR #800 — docs: polish Chapter 13 — fix interview-vulnerable expressions
- author zbnerd; MERGED; created 2026-05-03T13:03:33Z; closed 2026-05-04T01:47:05Z; merged yes/2026-05-04T01:47:05Z; merge commit 54fa09e613051cb1ed7e3c77a3ce8b43fe7d4560. Body: ## Summary - Fix architecture flow: expectation_calc_high as job orchestration, not "calculation onl…
- reviews 0 []; discussion 0; commits 1 [a855235]; linked issues 0 [].
- file evidence 1 [MODIFIED docs/06_Performance_Journey/13_pipeline_restructuring_supabase.md +31/-19]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #801 — feat: add module-external-api with real Nexon API + virtual thread fan-out
- author zbnerd; MERGED; created 2026-05-04T02:41:27Z; closed 2026-05-04T04:42:53Z; merged yes/2026-05-04T04:42:53Z; merge commit 366d3c6dd64ea9d2e218b67aad057a29b198f9d7. Body: ## Summary - Add `module-external-api` as standalone Spring Boot module for Nexon API data collectio…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 5 [e188084,06b1dae,77a272d]; linked issues 0 [].
- file evidence 20 [ADDED module-external-api/.gitignore +1/-0; ADDED module-external-api/build.gradle +51/-0; ADDED module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt +12/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #802 — feat: add CHARACTER_BASIC pipeline + shard directory structure
- author zbnerd; MERGED; created 2026-05-04T04:56:44Z; closed 2026-05-04T04:56:52Z; merged yes/2026-05-04T04:56:52Z; merge commit ba94a12e9120482597d00f3058f59d1f2697af94. Body: ## Summary - Add CHARACTER_BASIC endpoint to scheduler pipeline (OCID lookup → CHARACTER_BASIC) - Sh…
- reviews 0 []; discussion 0; commits 1 [a4ce1fe]; linked issues 0 [].
- file evidence 3 [MODIFIED module-external-api/src/main/kotlin/maple/externalapi/infra/storage/LocalExternalApiArtifactStoreAdapter.kt +13/-1; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/port/out/ExternalApiArtifactStorePort.kt +2/-0; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt +144/-42]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #803 — feat: skip OCID lookup when already completed
- author zbnerd; MERGED; created 2026-05-04T04:58:47Z; closed 2026-05-04T04:58:49Z; merged yes/2026-05-04T04:58:49Z; merge commit d90e456a3ad82c8837a81e5d5c53f6cf8dc6dbf5. Body: ## Summary - Skip OCID lookup phase if stored OCID files already exist - Go straight to CHARACTER_BA…
- reviews 0 []; discussion 0; commits 1 [29d4829]; linked issues 0 [].
- file evidence 1 [MODIFIED module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt +6/-1]. Actual: merged; file/commit evidence above. Portfolio: 품질.

### PR #804 — feat: separate OCID/CHARACTER_BASIC rate limits + skip completed OCID
- author zbnerd; MERGED; created 2026-05-04T05:47:07Z; closed 2026-05-04T05:47:10Z; merged yes/2026-05-04T05:47:10Z; merge commit d5082aa12200debb586dcaff22ab4aa1afd37bca. Body: ## Summary - OCID lookup rate limit: 400/s (config: `ocid-lookup-permits-per-second`) - CHARACTER_BA…
- reviews 0 []; discussion 0; commits 1 [a4e2508]; linked issues 0 [].
- file evidence 2 [MODIFIED module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt +6/-4; MODIFIED module-external-api/src/main/resources/application.yml +1/-0]. Actual: merged; file/commit evidence above. Portfolio: 품질.

### PR #805 — feat: add ITEM_EQUIPMENT pipeline + skip completed endpoints
- author zbnerd; MERGED; created 2026-05-04T06:05:09Z; closed 2026-05-04T06:05:11Z; merged yes/2026-05-04T06:05:11Z; merge commit 1302797023bd6cb900833323ded72fcb6e72b9d8. Body: ## Summary - Add ITEM_EQUIPMENT endpoint to scheduler pipeline - Skip OCID lookup and CHARACTER_BASI…
- reviews 0 []; discussion 0; commits 1 [6056c3e]; linked issues 0 [].
- file evidence 1 [MODIFIED module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt +80/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #806 — feat: restructure scheduler — daily OCID/BASIC + continuous ITEM_EQUIPMENT
- author zbnerd; MERGED; created 2026-05-04T07:26:59Z; closed 2026-05-04T07:27:33Z; merged yes/2026-05-04T07:27:33Z; merge commit 904ba43899c0fe3dda0fa0ec1bf4866a492b7625. Body: ## Summary - OCID 데이터를 `AtomicReference<Map>` 에 캐시하여 디스크 파싱 반복 제거 - 데일리 크론(새벽 3시)으로 OCID + CHARACTER…
- reviews 0 []; discussion 0; commits 1 [1acf629]; linked issues 0 [].
- file evidence 2 [MODIFIED module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt +87/-53; MODIFIED module-external-api/src/main/resources/application.yml +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 품질.

### PR #807 — feat: chunked JSONL snapshot storage with Kafka event publishing
- author zbnerd; MERGED; created 2026-05-04T15:04:18Z; closed 2026-05-04T15:05:08Z; merged yes/2026-05-04T15:05:08Z; merge commit e1db0a9dab7f3c6d775b6068577a76f31809d9da. Body: ## Summary - OCID별 개별 `.json.gz` 파일 저장을 streaming gzip JSONL chunk 파일로 교체 - 단일 writer thread + bound…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [8e13021]; linked issues 0 [].
- file evidence 20 [ADDED docs/18_Portfolio/external-api-pipeline-evolution.md +412/-0; MODIFIED gradle/libs.versions.toml +1/-0; MODIFIED module-external-api/build.gradle +3/-0]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #808 — feat: add module-calculator with coroutine chunk processing pipeline
- author zbnerd; MERGED; created 2026-05-05T05:41:48Z; closed 2026-05-05T05:45:36Z; merged yes/2026-05-05T05:45:36Z; merge commit 359c613fe5b582569e955f8914a658c7775f1bc7. Body: ## Summary - Add `module-calculator` — Kafka-driven snapshot chunk processing module - Coroutine pip…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [a5cc9d8]; linked issues 0 [].
- file evidence 14 [ADDED module-calculator/build.gradle +46/-0; ADDED module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt +14/-0; ADDED module-calculator/src/main/kotlin/maple/calculator/config/PipelineProperties.kt +9/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #809 — feat: calculator calculation cache, parallel parsing, manual Kafka ack
- author zbnerd; MERGED; created 2026-05-05T08:29:09Z; closed 2026-05-05T08:29:21Z; merged yes/2026-05-05T08:29:21Z; merge commit c0da9343c3ac1355229c85821816c15610e0d0e6. Body: ## Summary - Add Caffeine-based CalculationCache (potential/additional/starforce 분리 캐시) - readAndFla…
- reviews 0 []; discussion 0; commits 2 [90af01f,77c150c]; linked issues 0 [].
- file evidence 36 [MODIFIED module-calculator/build.gradle +5/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt +39/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt +25/-6]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #810 — feat(calculator): publish result chunk events
- author zbnerd; MERGED; created 2026-05-05T09:10:41Z; closed 2026-05-05T09:11:05Z; merged yes/2026-05-05T09:11:05Z; merge commit 63bd6998fe0392b401b4ca98c4dc2398004b2d0d. Body: ## Summary - write calculator results as gzip JSONL chunks under data/calculator/runs/{sourceRunId}/…
- reviews 0 []; discussion 0; commits 1 [a0a0f9f]; linked issues 0 [].
- file evidence 8 [MODIFIED module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt +41/-13; ADDED module-calculator/src/main/kotlin/maple/calculator/event/CalculatorResultChunkReadyEvent.kt +20/-0; ADDED module-calculator/src/main/kotlin/maple/calculator/event/KafkaResultEventPublisher.kt +32/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #811 — fix(calculator): use createFullCalculator for all item types
- author zbnerd; MERGED; created 2026-05-05T11:00:39Z; closed 2026-05-05T12:34:22Z; merged yes/2026-05-05T12:34:22Z; merge commit a7bba1c98ba476830d6dba99ab01c9e724a56b2b. Body: ## Summary - Switch from individual cache methods (createBlackCubeCalculator, etc.) to `createFullCa…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 1 / ## 단일 인스턴스 CPU 경합 분석 이번 E2E 테스트에서 두 JVM을…; commits 2 [cfc158d,6b1c871]; linked issues 0 [].
- file evidence 3 [MODIFIED module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt +45/-40; MODIFIED module-calculator/src/main/kotlin/maple/calculator/writer/CalculationResultWriter.kt +9/-13; MODIFIED module-external-api/src/main/resources/application.yml +1/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #813 — fix: address code review findings (P1/P2)
- author zbnerd; MERGED; created 2026-05-05T13:53:26Z; closed 2026-05-05T13:54:22Z; merged yes/2026-05-05T13:54:22Z; merge commit bbff5127cbfcfb5c1b7fc529cbdcdae2bfe8c47f. Body: ## Summary Code review에서 발견된 P1/P2 이슈 수정. ### P1 (Critical) - **Kafka publish 동기화**: `KafkaSnapshotC…
- reviews 0 []; discussion 0; commits 1 [385a25c]; linked issues 0 [].
- file evidence 5 [MODIFIED module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt +2/-6; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/application/ExternalApiIngestionService.kt +11/-0; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/infra/nexon/NexonExternalApiClientAdapter.kt +2/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #814 — feat: add module-synchronizer with Kafka result chunk consumer
- author zbnerd; MERGED; created 2026-05-10T04:32:53Z; closed 2026-05-10T04:33:10Z; merged yes/2026-05-10T04:33:10Z; merge commit b0d4f33dbed98fe3e7b8a3003c2ed67cd06a453d. Body: ## Summary - New `module-synchronizer` Spring Boot module (port 8083) - Kafka consumer subscribing t…
- reviews 0 []; discussion 0; commits 1 [e1b2a6b]; linked issues 0 [].
- file evidence 6 [ADDED module-synchronizer/build.gradle +45/-0; ADDED module-synchronizer/src/main/kotlin/maple/synchronizer/SynchronizerApplication.kt +11/-0; ADDED module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt +35/-0]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #815 — feat: Prometheus metrics, Grafana dashboard, metric reference rules
- author zbnerd; MERGED; created 2026-05-10T06:33:38Z; closed 2026-05-10T06:33:47Z; merged yes/2026-05-10T06:33:47Z; merge commit b756ec15fb2164fe2d6288c40e904d66e03928d1. Body: ## Summary - ExternalApiMetrics에 per-endpoint 카운터/타이머 추가 (CHARACTER_BASIC, ITEM_EQUIPMENT) - Calcula…
- reviews 0 []; discussion 0; commits 1 [7de14e2]; linked issues 0 [].
- file evidence 8 [ADDED .claude/rules/prometheus-metrics.md +119/-0; MODIFIED CLAUDE.md +1/-0; ADDED grafana/dashboard-pipeline.json +359/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #816 — feat: add synchronizer read model DB with gzip compression
- author zbnerd; MERGED; created 2026-05-10T13:28:48Z; closed 2026-05-11T23:22:08Z; merged yes/2026-05-11T23:22:08Z; merge commit 488d1b1bee79daadb1fd77bd014c4a7ee8afc13a. Body: ## Summary - Consume calculator result chunks from Kafka, read gzip JSONL files, group by ocid:prese…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 3 [467c561,2de13e6,40474b6]; linked issues 0 [].
- file evidence 15 [ADDED .claude/rules/adr-conventions.md +106/-0; MODIFIED CLAUDE.md +1/-0; ADDED module-infra/src/main/resources/db/migration/V123__synchronizer_read_model_tables.sql +43/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #817 — feat: artifact retention cleanup, volume metrics, 24h endurance test
- author zbnerd; MERGED; created 2026-05-13T09:19:11Z; closed 2026-05-13T09:19:35Z; merged yes/2026-05-13T09:19:35Z; merge commit 6b5e47b1a0fc964dafee45748153ece90bd79a7f. Body: ## Summary - **Artifact cleanup system**: Run-based retention policy with dry-run support, active ru…
- reviews 0 []; discussion 0; commits 19 [4e1da22,9f42d48,1a23459]; linked issues 0 [].
- file evidence 25 [MODIFIED docker-compose.yml +32/-0; ADDED docs/01_ADR/ADR-390_artifact-retention-policy.md +98/-0; ADDED docs/engineering-archive-kafka-pipeline.md +564/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #818 — feat: Elasticsearch logging pipeline + artifact retention cleanup
- author zbnerd; MERGED; created 2026-05-13T11:35:46Z; closed 2026-05-13T11:35:56Z; merged yes/2026-05-13T11:35:56Z; merge commit 3c20f654dbacc7ff08b03b1aa2caeb8639a5c882. Body: ## Summary - **Elasticsearch logging pipeline**: JSON structured logging (logstash-logback-encoder) …
- reviews 0 []; discussion 0; commits 9 [6bd577f,789f055,3141e15]; linked issues 0 [].
- file evidence 27 [MODIFIED .gitignore +1/-0; MODIFIED docker-compose.yml +60/-0; ADDED docker/fluent-bit/es-setup-ilm.sh +68/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #819 — refactor(external-api): ExternalApiScheduler god-class decomposition
- author zbnerd; MERGED; created 2026-05-14T03:58:27Z; closed 2026-05-14T03:58:42Z; merged yes/2026-05-14T03:58:42Z; merge commit 378c3238d911d55fea0f0c152ca92a9e2decc675. Body: ## Summary - ExternalApiScheduler 495→90줄 god-class 해체: 3 phase 분리 → 이벤트 seam 통합 → shallow use case …
- reviews 0 []; discussion 0; commits 6 [ded9c53,dceadc6,878e2a0]; linked issues 0 [].
- file evidence 13 [MODIFIED module-external-api/build.gradle +1/-0; DELETED module-external-api/src/main/kotlin/maple/externalapi/application/ExternalApiIngestionService.kt +0/-82; ADDED module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt +60/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #820 — refactor(calculator): extract Coordinator + Converter, fix MDC + ACK safety
- author zbnerd; MERGED; created 2026-05-14T06:28:04Z; closed 2026-05-14T06:43:32Z; merged yes/2026-05-14T06:43:32Z; merge commit 7eef234af373cc7514c82735ccdb5ae00b7ec6a9. Body: ## Summary - Extract workflow from Consumer → `CalculatorChunkProcessingCoordinator` (endpoint filte…
- reviews 0 []; discussion 0; commits 2 [323c589,fc96d49]; linked issues 0 [].
- file evidence 11 [MODIFIED gradle/libs.versions.toml +1/-0; MODIFIED module-calculator/build.gradle +2/-0; ADDED module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt +135/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #821 — fix(common): apply keepRecentCount in RunRetentionPolicy
- author zbnerd; MERGED; created 2026-05-14T07:44:19Z; closed 2026-05-14T07:44:21Z; merged yes/2026-05-14T07:44:21Z; merge commit e47c0ca4a58af2800a6086938ded028f1a3ee165. Body: ## Summary - `RunRetentionPolicy.selectForDeletion`이 `keepRecentCount` 파라미터를 무시하고 시간 조건만으로 삭제 대상 판별 …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [63b97b7]; linked issues 0 [].
- file evidence 1 [MODIFIED module-common/src/main/kotlin/maple/common/cleanup/RunRetentionPolicy.kt +5/-1]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #822 — refactor(synchronizer): extract ChunkProcessor seam, replace try-catch with Logi…
- author zbnerd; CLOSED; created 2026-05-14T08:13:36Z; closed 2026-05-14T08:40:05Z; merged no; merge commit —. Body: ## Summary - Extract `ChunkProcessor` interface + `DefaultChunkProcessor` from `KafkaResultChunkCons…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 1 / Superseded by #823 — all changes merged …; commits 1 [a5ece93]; linked issues 0 [].
- file evidence 7 [ADDED docs/01_ADR/ADR-716_synchronizer-extract-chunk-processor.md +85/-0; MODIFIED module-synchronizer/build.gradle +1/-0; MODIFIED module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt +39/-75]. Actual: closed, not merged; no application claim. Portfolio: 설계.

### PR #823 — refactor(synchronizer,calculator): extract ChunkProcessor, shared event, prepare…
- author zbnerd; MERGED; created 2026-05-14T08:38:20Z; closed 2026-05-14T09:31:21Z; merged yes/2026-05-14T09:31:21Z; merge commit 4daf79e6ff527defe7b751e11df0bbea2b73ac36. Body: ## Summary Architectural improvements to module-synchronizer (ADR-716 기반): ### ChunkProcessor Seam 추…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 4 [705235b,3482081,ff96948]; linked issues 0 [].
- file evidence 21 [ADDED docs/01_ADR/ADR-716_synchronizer-extract-chunk-processor.md +85/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt +1/-1; MODIFIED module-calculator/src/main/kotlin/maple/calculator/event/KafkaResultEventPublisher.kt +1/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #824 — feat(rest-controller): V6 read path Phase 1 — request buffering with dedup
- author zbnerd; MERGED; created 2026-05-15T04:46:57Z; closed 2026-05-16T07:56:36Z; merged yes/2026-05-16T07:56:36Z; merge commit 2e973db391c5be39a67d53e8279d2241f436d412. Body: ## Summary - V6 Read Path Phase 1: REST API 요청을 받아 LocalRequestBuffer에 적재하는 구조 - `GET /api/v6/charac…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [eb9557a,f3e0e04]; linked issues 0 [].
- file evidence 21 [ADDED docs/superpowers/plans/2026-05-15-v6-read-path-phase1.md +1204/-0; MODIFIED module-rest-controller/build.gradle +2/-0; ADDED module-rest-controller/src/main/kotlin/maple/restcontroller/advice/RestControllerExceptionHandler.kt +30/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #825 — feat: V6 read path Phase 2 — userIGN batch query with read model
- author zbnerd; MERGED; created 2026-05-16T11:14:37Z; closed 2026-05-16T11:15:18Z; merged yes/2026-05-16T11:15:18Z; merge commit 8de7c4486f266c245b433221afb7a8754e594f75. Body: ## Summary - V125 migration: nullable `user_ign` column on `character_equipment_read_model` with bac…
- reviews 0 []; discussion 0; commits 15 [3853021,6f766f6,b1f1833]; linked issues 0 [].
- file evidence 27 [ADDED docs/superpowers/plans/2026-05-16-v6-read-path-phase2-userign.md +1009/-0; ADDED docs/superpowers/specs/2026-05-16-v6-read-model-userign-design.md +121/-0; MODIFIED gradle/libs.versions.toml +1/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #826 — feat(rest-controller): V6 read path Phase 2 with Redis caching
- author zbnerd; MERGED; created 2026-05-16T13:25:36Z; closed 2026-05-16T13:25:52Z; merged yes/2026-05-16T13:25:52Z; merge commit 4723303572c7b8c8a6f019a61066cf43691094e5. Body: ## Summary - V6 read path Phase 2: userIgn 기반 read model 조회 with Redis 캐싱 - BatchReadScheduler에서 Red…
- reviews 0 []; discussion 0; commits 102 [e188084,06b1dae,77a272d]; linked issues 0 [].
- file evidence 176 [ADDED .claude/rules/adr-conventions.md +106/-0; ADDED .claude/rules/prometheus-metrics.md +119/-0; MODIFIED .gitignore +1/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #827 — revert: PR #826 — incorrect base branch merge
- author zbnerd; MERGED; created 2026-05-16T13:31:56Z; closed 2026-05-16T13:32:52Z; merged yes/2026-05-16T13:32:52Z; merge commit 318d39099154c650920c5d7830b80b494f78a52e. Body: ## Summary Reverts #826 — incorrect base branch merge. Changes will be re-submitted as develop-base …
- reviews 0 []; discussion 0; commits 1 [4038216]; linked issues 0 [].
- file evidence 176 [DELETED .claude/rules/adr-conventions.md +0/-106; DELETED .claude/rules/prometheus-metrics.md +0/-119; MODIFIED .gitignore +0/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #828 — feat(rest-controller): V6 read path Redis caching with partial hit/miss support
- author zbnerd; MERGED; created 2026-05-16T13:32:34Z; closed 2026-05-16T13:32:43Z; merged yes/2026-05-16T13:32:43Z; merge commit 48fcd9129d94abac9721e47aecdba89c40561b95. Body: ## Summary - BatchReadScheduler에서 Redis multiGet → cache hit/miss 분리 → miss만 DB batch query → Redis …
- reviews 0 []; discussion 0; commits 1 [30dce82]; linked issues 0 [].
- file evidence 8 [MODIFIED gradle/libs.versions.toml +1/-0; MODIFIED module-rest-controller/build.gradle +3/-0; MODIFIED module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt +9/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #829 — feat(synchronizer): CHARACTER_BASIC snapshot pipeline
- author zbnerd; MERGED; created 2026-05-16T14:41:30Z; closed 2026-05-16T14:41:38Z; merged yes/2026-05-16T14:41:38Z; merge commit 07453a1b0b0e04d537a6a35b5de9b077a1d09e9c. Body: ## Summary - CHARACTER_BASIC snapshot chunk → Kafka → Synchronizer → DB bulk upsert 파이프라인 구현 - Exter…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [55835aa]; linked issues 0 [].
- file evidence 9 [ADDED docs/superpowers/specs/2026-05-16-character-basic-sync-design.md +40/-0; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt +3/-1; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/snapshot/event/SnapshotEventPublisherConfig.kt +27/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #830 — feat: V6 urgent Kafka pipeline for cache-miss character data
- author zbnerd; MERGED; created 2026-05-16T19:02:37Z; closed 2026-05-16T19:05:10Z; merged yes/2026-05-16T19:05:10Z; merge commit ccf199ae2d34b4a4ff2edc6993ad8741bf7cccd3. Body: ## Summary - V6 read endpoint에서 Redis + DB miss 시 urgent Kafka pipeline 트리거 - module-rest-controller…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 9 [2c73af2,971b565,49e6342]; linked issues 0 [].
- file evidence 19 [ADDED docs/01_ADR/ADR-026_v6-urgent-kafka-pipeline.md +105/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt +14/-0; MODIFIED module-calculator/src/main/resources/application.yml +2/-0]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #831 — fix(rest-controller): prevent batch-urgent pipeline overlap
- author zbnerd; MERGED; created 2026-05-16T19:34:10Z; closed 2026-05-16T19:34:26Z; merged yes/2026-05-16T19:34:26Z; merge commit 24fe4b716f74fc553e4a3cdc956bb358042f8ae7. Body: ## Summary - Skip DB lookup for characters with urgent-pending key (avoids wasted query while urgent…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [027389d]; linked issues 0 [].
- file evidence 5 [MODIFIED .claude/rules/workflow-rules.md +16/-3; MODIFIED module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadProperties.kt +1/-0; MODIFIED module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt +10/-2]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #832 — Merge develop into master
- author zbnerd; MERGED; created 2026-05-16T19:35:16Z; closed 2026-05-16T19:36:47Z; merged yes/2026-05-16T19:36:47Z; merge commit dc4c0c832be7fbb5f1ecbed4c260989b5ea53349. Body: ## Summary - V6 urgent Kafka pipeline for cache-miss character data (#830) - Batch-urgent pipeline o…
- reviews 0 []; discussion 0; commits 7 [30dce82,48fcd91,07453a1]; linked issues 0 [].
- file evidence 28 [MODIFIED .claude/rules/workflow-rules.md +16/-3; ADDED docs/01_ADR/ADR-026_v6-urgent-kafka-pipeline.md +105/-0; ADDED docs/superpowers/specs/2026-05-16-character-basic-sync-design.md +40/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #833 — feat(rest-controller): add V6 urgent status polling
- author zbnerd; MERGED; created 2026-05-17T00:16:48Z; closed 2026-05-17T00:29:18Z; merged yes/2026-05-17T00:29:18Z; merge commit dd8f6b5f152762b74cf846ce2ae87526ae755cce. Body: ## Summary - Add V6 urgent status polling response for 202 Accepted (`Location`, `Retry-After`, stat…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [1ff54c8]; linked issues 0 [].
- file evidence 21 [MODIFIED gradle/libs.versions.toml +3/-0; MODIFIED module-common/src/main/kotlin/maple/expectation/util/StringMaskingUtils.kt +9/-0; ADDED module-rest-controller/src/main/kotlin/maple/restcontroller/RestControllerApplication.kt +11/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #834 — fix(pipeline): restore urgent worker modules
- author zbnerd; MERGED; created 2026-05-17T05:44:14Z; closed 2026-05-17T05:44:35Z; merged yes/2026-05-17T05:44:35Z; merge commit 32b07923226766af00e9e4d940c21e6034e4d7b6. Body: ## Summary - Restore urgent worker modules removed by the mistaken master-base revert path: `module-…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 1 / 복구 PR입니다. - 잘못된 master-base revert 경로로 빠…; commits 1 [59732a3]; linked issues 0 [].
- file evidence 107 [MODIFIED gradle/libs.versions.toml +3/-0; ADDED module-calculator/build.gradle +60/-0; ADDED module-calculator/src/main/kotlin/maple/calculator/CalculatorApplication.kt +57/-0]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #835 — revert: restore develop to PR 825 state
- author zbnerd; MERGED; created 2026-05-17T06:08:36Z; closed 2026-05-17T06:08:55Z; merged yes/2026-05-17T06:08:55Z; merge commit 2d22588990ea9a2075f983fd0ac452235e90eb3b. Body: ## Summary - restore repository tree to PR #825 merge commit `8de7c4486` - back out post-#825 change…
- reviews 0 []; discussion 0; commits 1 [329b91e]; linked issues 0 [].
- file evidence 107 [ADDED .claude/rules/adr-conventions.md +106/-0; ADDED .claude/rules/prometheus-metrics.md +119/-0; MODIFIED .claude/rules/workflow-rules.md +3/-16]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #836 — Rebuild V6 urgent Kafka pipeline from PR 825 base
- author zbnerd; MERGED; created 2026-05-17T06:55:36Z; closed 2026-05-17T06:59:55Z; merged yes/2026-05-17T06:59:55Z; merge commit 761b97952399fa9d5f9d0972ce76bee814bb94da. Body: ## Summary - Rebuild V6 urgent status polling and Kafka urgent pipeline on top of restored PR 825 de…
- reviews 0 []; discussion 2 / You have reached your Codex usage limits…; commits 14 [63d3032,90b539b,0dfda62]; linked issues 0 [].
- file evidence 32 [ADDED docs/superpowers/specs/2026-05-16-character-basic-sync-design.md +40/-0; MODIFIED gradle/libs.versions.toml +1/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/consumer/KafkaSnapshotChunkReadyConsumer.kt +14/-0]. Actual: merged; file/commit evidence above. Portfolio: 운영.

### PR #837 — Tune external-api Nexon async fetch pipeline
- author zbnerd; MERGED; created 2026-05-17T13:25:07Z; closed 2026-05-17T13:48:04Z; merged yes/2026-05-17T13:48:04Z; merge commit 5a5ec75211dc808744826a551fa97e53578fccd0. Body: ## Summary - restore/expose Nexon WebClient connection-pool metrics and HTTP client tuning - default…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [7437ef7,fc15fb0]; linked issues 0 [].
- file evidence 17 [MODIFIED .claude/rules/async-patterns.md +23/-0; ADDED docs/01_ADR/ADR-717-external-api-nexon-throughput-tuning.md +102/-0; MODIFIED module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt +2/-1]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #838 — feat: add equipment ranking projection
- author zbnerd; MERGED; created 2026-05-17T15:56:54Z; closed 2026-05-17T15:57:22Z; merged yes/2026-05-17T15:57:22Z; merge commit 8bc49f4e79f4debbd6b10690b7ac2893bc1ecd18. Body: ## Summary - Project equipment total-cost Top10 into Redis sorted sets after synchronizer DB upsert …
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 2 [633093b,5349d37]; linked issues 0 [].
- file evidence 18 [MODIFIED .gitignore +1/-0; ADDED module-infra/src/main/resources/db/migration/V127__equipment_total_cost_ranking_index.sql +4/-0; MODIFIED module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt +19/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #839 — refactor(pipeline): centralize chunk contracts
- author zbnerd; MERGED; created 2026-05-18T00:52:56Z; closed 2026-05-18T00:53:07Z; merged yes/2026-05-18T00:53:07Z; merge commit 5f86c4faf5a9205ce62466354eac68dd44c458cb. Body: ## Summary - Move snapshot pipeline events into module-common and share Kafka key helpers - Make ext…
- reviews 0 []; discussion 0; commits 2 [d742231,133ae9a]; linked issues 0 [].
- file evidence 43 [MODIFIED .gitignore +8/-0; MODIFIED CLAUDE.md +14/-0; ADDED docs/superpowers/plans/2026-05-16-v6-urgent-kafka-pipeline.md +1046/-0]. Actual: merged; file/commit evidence above. Portfolio: 설계.

### PR #840 — feat(synchronizer): add chunk execution state
- author zbnerd; MERGED; created 2026-05-18T12:10:28Z; closed 2026-05-18T12:10:50Z; merged yes/2026-05-18T12:10:50Z; merge commit 7c4d0867113062c394962967c2000af3c1aa6250. Body: ## Summary - add chunk_execution migration and common execution identity/status types - persist sync…
- reviews 1 [COMMENTED/chatgpt-codex-connector]; discussion 0; commits 1 [f636d1a]; linked issues 0 [].
- file evidence 13 [ADDED docs/superpowers/plans/2026-05-18-chunk-execution-foundation.md +543/-0; ADDED module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionIdentity.kt +8/-0; ADDED module-common/src/main/kotlin/maple/expectation/common/event/ChunkExecutionStatus.kt +9/-0]. Actual: merged; file/commit evidence above. Portfolio: 기능.

### PR #841 — fix(pipeline): address review feedback
- author zbnerd; MERGED; created 2026-05-18T23:30:09Z; closed 2026-05-18T23:52:14Z; merged yes/2026-05-18T23:52:14Z; merge commit c8fe76e759430ba54a9fc4857ac5cf9ffd424fc1. Body: ## Summary - Apply PR 774-840 review feedback across external-api, calculator, synchronizer, rest-co…
- reviews 0 []; discussion 0; commits 3 [2f8fa94,ca61e86,eadccc5]; linked issues 0 [].
- file evidence 41 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +1/-1; MODIFIED module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt +1/-0; MODIFIED module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt +5/-3]. Actual: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #620 — fix: resolve test hangs and stabilize CI fastTest pipeline
- author/state/dates: zbnerd | MERGED | created 2026-03-26T14:51:53Z | closed 2026-03-26T14:52:28Z | merged yes at 2026-03-26T14:52:28Z | merge b02ba8b2c50395f03e36b344301a916b64dfe966.
- body: ## Summary - Add `shutdown()` to `GlobalAdmissionControl` for graceful worker pool termination - Fix `GlobalAdmissionControlTest` …
- reviews/discussion: 0 []; 0.
- commits: 104 [c4edd59, 255c0c9, cf00576]; linked issues: 0 [].
- file evidence: 172 [RENAMED .backup/build.gradle.backup_before_catalog +0/-0; RENAMED .backup/docker_backup_temp/promtail/config.yml +0/-0; MODIFIED .github/workflows/ci.yml +0/-138].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #621 — fix: resolve merge compilation errors and apply stashed changes
- author/state/dates: zbnerd | MERGED | created 2026-03-26T15:00:44Z | closed 2026-03-26T15:00:51Z | merged yes at 2026-03-26T15:00:51Z | merge 1f8cb5bbe99cb88271bee66af03f195fce728f05.
- body: ## Summary - Restore DatabaseCleaner.kt (deleted during merge but still referenced by IntegrationTestBase) - Implement fetchAllWit…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [02f6eaf, 1ca807b, c2388c3]; linked issues: 0 [].
- file evidence: 5 [MODIFIED CLAUDE.md +205/-39; MODIFIED module-app/checkpoint.json +3/-3; MODIFIED module-app/failed.csv +11263/-1700].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #622 — feat(like): Direct DB 토글 서비스 (ADR-029)
- author/state/dates: zbnerd | MERGED | created 2026-03-28T09:22:52Z | closed 2026-03-28T09:23:13Z | merged yes at 2026-03-28T09:23:13Z | merge 6756cb753e69a426863aab810b3a77a4c049be7c.
- body: ## Summary - ADR-029: Like Toggle Direct DB 방식 구현 (Buffer/PGMQ 없이 PostgreSQL 직접 쓰기) - `LikeTogglePort` 인터페이스 (module-core) + `Like…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [fae74f1, f2bbf6b, 0f1774d]; linked issues: 0 [].
- file evidence: 14 [ADDED docs/adr/029-like-direct-db-approach.md +119/-0; ADDED module-app/src/main/java/maple/expectation/application/service/like/LikeToggleService.java +150/-0; ADDED module-app/src/main/java/maple/expectation/application/service/like/OcidResolutionService.java +45/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #661 — fix(like): OCID cache bug + 401 auth + E2E test
- author/state/dates: zbnerd | MERGED | created 2026-03-28T17:31:24Z | closed 2026-03-28T19:25:02Z | merged yes at 2026-03-28T19:25:02Z | merge 672d369096cf7d278a2d20ac889dc8bf44a0d3ff.
- body: ## Summary - Fix `OcidResolutionService` returning 960-char GZIP blob instead of 32-char OCID (TieredCache L2 serialization issue)…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 15 [10b33bb, 61a2be6, 063bf8e]; linked issues: 0 [].
- file evidence: 15 [MODIFIED module-app/src/main/java/maple/expectation/application/service/like/LikeToggleService.java +14/-3; MODIFIED module-app/src/main/java/maple/expectation/application/service/like/OcidResolutionService.java +59/-5; ADDED module-app/src/test/java/maple/expectation/application/service/like/LikeToggleServiceTest.java +278/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #666 — feat(like): fingerprint identity + DB trigger for like_count atomicity (#662-#665)
- author/state/dates: zbnerd | MERGED | created 2026-03-29T09:57:57Z | closed 2026-03-29T10:06:19Z | merged yes at 2026-03-29T10:06:19Z | merge 06952c403932b1153ca33efba8fa4a280568a741.
- body: ## Summary - **#662**: Fingerprint-based multi-character self-like prevention — 동일 API Key의 모든 캐릭터 OCID를 `myOcids`에 포함하여 자신의 캐릭터에 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [ef1f35f, 0cd7fda, ae02cca]; linked issues: 0 [].
- file evidence: 15 [ADDED docs/adr/031-like-fingerprint-account-id-trigger.md +143/-0; ADDED docs/plan/like-domain-662-665-plan.md +357/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/like/LikeToggleService.java +0/-4].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #668 — fix(auth): Nexon API validation on login (#667)
- author/state/dates: zbnerd | MERGED | created 2026-03-29T10:53:10Z | closed 2026-03-29T11:06:09Z | merged yes at 2026-03-29T11:06:09Z | merge b31961878777ddc1154540c690819f82da69c6cb.
- body: ## Summary - Wire `ApiKeyValidator` into `AuthPortAdapter.login()` — login now calls Nexon `/maplestory/v1/character/list` to vali…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [4c9b365]; linked issues: 0 [].
- file evidence: 4 [MODIFIED module-app/src/main/kotlin/maple/expectation/application/service/auth/ApiKeyValidator.kt +19/-5; MODIFIED module-app/src/main/kotlin/maple/expectation/application/usecase/AuthPortAdapter.kt +20/-16; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/security/AuthenticatedUser.kt +2/-2].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #669 — fix(security,cache): SLF4J logging, like buffer race condition, VT pinning diagnostics (#6…
- author/state/dates: zbnerd | CLOSED | created 2026-03-29T17:40:39Z | closed 2026-03-29T17:41:27Z | merged no | merge —.
- body: ## Summary - **#625**: 프로덕션 코드의 `System.out.println()` 6건을 SLF4J `companion object + LoggerFactory` 로 교체 (PrometheusSecurityFilter…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 14 [6756cb7, 979ca5f, a48475c]; linked issues: 0 [].
- file evidence: 49 [ADDED .env.bak.euc-kr +34/-0; ADDED docs/adr/029-like-direct-db-approach.md +119/-0; ADDED docs/adr/030-sync-fanout-cqrs-separation.md +274/-0].
- resolution: closed, not merged; no application claim. Portfolio: 결함·보안.

### PR #670 — Worktree issue624 627
- author/state/dates: zbnerd | MERGED | created 2026-03-29T17:42:54Z | closed 2026-03-29T17:43:04Z | merged yes at 2026-03-29T17:43:03Z | merge f6aaff5f506dd076e63a3c2ecfdd149593da19d8.
- body: none
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [b02ba8b, 1f8cb5b, 8cff721]; linked issues: 0 [].
- file evidence: 6 [MODIFIED gradle.properties +1/-1; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorage.kt +12/-4; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/security/cors/CorsValidationFilter.kt +6/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #671 — fix(lock): P0 null-safety & advisory lock scope fixes (#628-631)
- author/state/dates: zbnerd | MERGED | created 2026-03-29T18:02:50Z | closed 2026-03-29T19:45:58Z | merged yes at 2026-03-29T19:45:58Z | merge 1095c31583caf2a6969fe1813fb82f1c04deaba4.
- body: ## Summary 4개 P0 이슈 null-safety 및 아키텍처 위반 수정: - **#629**: `OrderedLockExecutor.kt:228` unsafe `!!` → 명시적 null 체크 + `IllegalStateEx…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [a13587d]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +3/-1; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/PresetCalculationHelper.java +8/-1; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +8/-7].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #673 — FIX : Resolve DIP violation and null safety issues (#637-#644)
- author/state/dates: zbnerd | MERGED | created 2026-03-30T13:39:28Z | closed 2026-03-30T13:42:14Z | merged yes at 2026-03-30T13:42:14Z | merge c877b82f182b724aa486a3ea7862949e6be32798.
- body: ## 🔗 관련 이슈 Resolves : #637, #638, #639, #640, #641, #642, #643, #644 ## 🗣 개요 module-web이 module-infra 구현체에 직접 의존하는 DIP 위반을 해결하고, N…
- reviews/discussion: 0 []; 0.
- commits: 1 [7261bd8]; linked issues: 0 [].
- file evidence: 47 [ADDED docs/adr/2025-03-30-fix-639-dip-violation-module-web-to-infra.md +107/-0; ADDED docs/adr/2025-03-30-fix-644-god-object-cache-coordinator.md +109/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/EquipmentApplicationService.java +4/-9].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #674 — docs: comprehensive P0/P1/P2 cleanup + ADR consolidation
- author/state/dates: zbnerd | MERGED | created 2026-03-30T17:46:17Z | closed 2026-03-30T17:46:49Z | merged yes at 2026-03-30T17:46:49Z | merge 5c65c73c5ce45ff3cba3d62a53202bbe7df498b4.
- body: ## Summary - **P0**: Redis/Redisson 폐기 문서 9개 `docs/_archive/redis-deprecated/` 보관, 깨진 ADR 링크 전체 수정, Java→Kotlin 스니펫 22개 변환 - **P0*…
- reviews/discussion: 0 []; 0.
- commits: 2 [467d1c6, cffc179]; linked issues: 0 [].
- file evidence: 415 [MODIFIED CLAUDE.md +2/-2; MODIFIED docs/00_Start_Here/BUSINESS_MODEL.md +3/-3; RENAMED docs/00_Start_Here/CLAIM_EVIDENCE_MATRIX_root.md +14/-14].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #675 — fix(docs): comprehensive documentation cleanup — broken links, path refs, file reorganizat…
- author/state/dates: zbnerd | MERGED | created 2026-03-30T19:07:57Z | closed 2026-03-30T19:08:29Z | merged yes at 2026-03-30T19:08:29Z | merge 9933982446bc421bd8124599249eb61c1ca05dca.
- body: ## Summary - 9-agent consensus review로 식별된 docs/ 내 P0/P1/P2 이슈 전체 수정 (824 files, 4 commits) - 전역 경로 참조 수정 (85 files): 디렉토리 번호 변경 후…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 3 [eefece2, dbeb28e, 60c6fb0]; linked issues: 0 [].
- file evidence: 332 [MODIFIED docs/00_Start_Here/BUSINESS_MODEL.md +9/-9; MODIFIED docs/00_Start_Here/CLAIM_EVIDENCE_MATRIX.md +19/-19; MODIFIED docs/00_Start_Here/CLAIM_EVIDENCE_MATRIX_root.md +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #676 — fix(docs): comprehensive documentation audit — broken links, ADR conflicts, Redis→PostgreS…
- author/state/dates: zbnerd | MERGED | created 2026-03-30T19:35:56Z | closed 2026-03-30T19:36:20Z | merged yes at 2026-03-30T19:36:20Z | merge 0440928bd72ac5a5c4b3a5c5753bda920a803af5.
- body: ## Summary - **Resolve 13 ADR numbering conflicts** by renumbering to 367-383 series; restore ADR-022 filename (was ADR-339) - **F…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [95faaea]; linked issues: 0 [].
- file evidence: 107 [MODIFIED CLAUDE.md +6/-4; MODIFIED docs/00_Start_Here/BUSINESS_MODEL.md +3/-3; DELETED docs/00_Start_Here/CLAIM_EVIDENCE_MATRIX_root.md +0/-240].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #677 — refactor(docs): number all directories (09-21) and update references
- author/state/dates: zbnerd | MERGED | created 2026-03-30T19:45:35Z | closed 2026-03-30T19:47:51Z | merged yes at 2026-03-30T19:47:51Z | merge df6890a55420e30b61c660a4f6458cb59c9ce7ba.
- body: ## Summary - **Rename 12 unnumbered directories** to numbered folders (09-20) for consistent organization - **Resolve 06_Guides vs…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [2b91433]; linked issues: 0 [].
- file evidence: 192 [MODIFIED CLAUDE.md +1/-1; MODIFIED docs/01_ADR/ADR-022-redis-dependency-removal.md +1/-1; MODIFIED docs/01_ADR/ADR-314-postgresql-single-db-strategy.md +2/-2].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #678 — docs(claude-md): update documentation table with numbered directory paths
- author/state/dates: zbnerd | MERGED | created 2026-03-30T19:55:40Z | closed 2026-03-30T19:55:54Z | merged yes at 2026-03-30T19:55:54Z | merge 246b84cfd78869f0763f8b7d42a8212b89fcf28e.
- body: Update CLAUDE.md documentation reference table to reflect new numbered directory structure (09-21). Expand from 9 to 16 entries, r…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [1757f02]; linked issues: 0 [].
- file evidence: 1 [MODIFIED CLAUDE.md +8/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #679 — Merge develop into master
- author/state/dates: zbnerd | MERGED | created 2026-03-30T19:57:51Z | closed 2026-03-30T19:58:27Z | merged yes at 2026-03-30T19:58:27Z | merge bd9641dfbbeee39a5a18ae5ef6cdded808678925.
- body: Sync develop → master with documentation cleanup, ADR renumbering, directory numbering (09-21), and CLAUDE.md updates.
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 34 [6756cb7, 979ca5f, a48475c]; linked issues: 0 [].
- file evidence: 759 [ADDED .env.bak.euc-kr +34/-0; MODIFIED CLAUDE.md +16/-7; MODIFIED build.gradle +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #680 — docs: Like Domain Refactoring Journey (7장 + 부록)
- author/state/dates: zbnerd | MERGED | created 2026-03-31T09:17:24Z | closed 2026-03-31T09:45:35Z | merged yes at 2026-03-31T09:45:35Z | merge 8a092c375a02a7f67182700d57b4d4fb2f9c0239.
- body: ## Summary - **Like 도메인 123일 리팩토링 여정 문서** 추가 (7장 + 부록 4개, 총 11개 파일) - 2025.11 ~ 2026.03 Like 도메인 진화 과정: 동시성 제어 → Redis 원자성 → Scale…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 45 [6756cb7, 979ca5f, a48475c]; linked issues: 0 [].
- file evidence: 21 [ADDED docs/22_Like_Refactoring_Journey/README.md +61/-0; ADDED docs/22_Like_Refactoring_Journey/appendix-a-metrics.md +80/-0; ADDED docs/22_Like_Refactoring_Journey/appendix-b-timeline.md +98/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #681 — Update README.md
- author/state/dates: zbnerd | MERGED | created 2026-03-31T09:57:00Z | closed 2026-03-31T09:57:08Z | merged yes at 2026-03-31T09:57:08Z | merge 104d38f3fa03c291a0f7ce6e073f95382fecfc0b.
- body: none
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ee607e8]; linked issues: 0 [].
- file evidence: 1 [MODIFIED README.md +118/-497].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #682 — Update README.md
- author/state/dates: zbnerd | MERGED | created 2026-03-31T16:24:32Z | closed 2026-03-31T16:24:44Z | merged yes at 2026-03-31T16:24:44Z | merge dbc9741233a2a1c9fe37be137c929f9b108d6a14.
- body: none
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [005a542]; linked issues: 0 [].
- file evidence: 1 [MODIFIED README.md +32/-5].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #683 — Update README.md
- author/state/dates: zbnerd | MERGED | created 2026-03-31T16:58:06Z | closed 2026-03-31T16:59:06Z | merged yes at 2026-03-31T16:59:06Z | merge 8c3799c452cefd8a1e269569cd104696d4981b68.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 1 [e2c815b]; linked issues: 0 [].
- file evidence: 1 [MODIFIED README.md +4/-2].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #684 — docs: Performance Journey 09-12장 개정 + Like Refactoring Journey 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-31T18:10:59Z | closed 2026-03-31T18:11:36Z | merged yes at 2026-03-31T18:11:36Z | merge e692e289ec796eeb2a124d90764b7937162ba343.
- body: ## Summary - 09장: 10,994 RPS로 통일 (7,347 → 10,994 헤드라인, 113배 향상) - 10장: Fan-Out 병목 제거 → Cache Invalidation DB Fallback로 교체, Admissi…
- reviews/discussion: 0 []; 0.
- commits: 48 [6756cb7, 979ca5f, a48475c]; linked issues: 0 [].
- file evidence: 5 [MODIFIED docs/06_Performance_Journey/09_postgresql_notify.md +3/-3; MODIFIED docs/06_Performance_Journey/10_real_data_challenge.md +28/-44; ADDED docs/06_Performance_Journey/11_fanout_admission_control.md +249/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #685 — feat(pgmq): Phase 0 - Outbox to PGMQ migration foundation
- author/state/dates: zbnerd | MERGED | created 2026-03-31T22:44:02Z | closed 2026-03-31T22:45:46Z | merged yes at 2026-03-31T22:45:46Z | merge 7b129feb11ad11ff29af88f0a187462c35b9be5a.
- body: ## Summary - Add `setVisibilityTimeout()` to PgmqClient for Exponential Backoff support - Add conditional TX check in `PgmqClient.…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 6 [258ae00, 2ce0579, e933138]; linked issues: 0 [].
- file evidence: 12 [ADDED docs/09_Plans/outbox-to-pgmq-migration.md +1059/-0; MODIFIED module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqClientIntegrationTest.kt +16/-1; ADDED module-app/src/test/kotlin/maple/expectation/testinfra/pgmq/PgmqTransactionAtomicityTest.kt +134/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #686 — feat(pgmq): Phase 1 - Remove EventOutbox, publish directly to PGMQ
- author/state/dates: zbnerd | MERGED | created 2026-03-31T23:05:31Z | closed 2026-03-31T23:05:39Z | merged yes at 2026-03-31T23:05:39Z | merge e50018a195a1fffb6138745b3471c04b0fbae098.
- body: ## Summary - Remove EventOutbox bridge pattern (11 files, -1,544 lines) - Replace with direct PGMQ publishing inside `@Transaction…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [041eba4]; linked issues: 0 [].
- file evidence: 18 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/CalculationCompletedEventListener.java +9/-21; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/TransactionalEventPublisher.java +35/-60; DELETED module-app/src/main/resources/event_outbox_schema.sql +0/-62].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #687 — feat(pgmq): Phase 2 - Remove DonationOutbox, publish directly to PGMQ
- author/state/dates: zbnerd | MERGED | created 2026-03-31T23:45:41Z | closed 2026-03-31T23:45:50Z | merged yes at 2026-03-31T23:45:50Z | merge bacee4f54528693e648506f98647d9a8435790f9.
- body: ## Summary - Remove Donation Outbox pattern and DLQ infrastructure entirely (25 files, -2,917 lines) - `DonationPortAdapter` now p…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c1aca82]; linked issues: 0 [].
- file evidence: 26 [DELETED module-app/src/main/java/maple/expectation/application/service/donation/outbox/DlqAdminService.java +0/-225; DELETED module-app/src/main/java/maple/expectation/application/usecase/DlqPortAdapter.java +0/-52; MODIFIED module-app/src/main/java/maple/expectation/application/usecase/DonationPortAdapter.java +14/-6].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #688 — feat(pgmq): Phase 3 - Remove NexonApiOutbox, retry via PGMQ
- author/state/dates: zbnerd | MERGED | created 2026-04-01T00:23:07Z | closed 2026-04-01T01:28:10Z | merged yes at 2026-04-01T01:28:10Z | merge 34195c94d09e0c5ef18c456a4d6283774ad20b49.
- body: ## Summary - Replace Nexon API outbox table-based retry with PGMQ message queue (`nexon_retry_queue`) - `NexonApiPgmqProcessor` po…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [6db4b01]; linked issues: 0 [].
- file evidence: 30 [MODIFIED docker/postgres/init.sql +3/-0; DELETED module-app/src/main/java/maple/expectation/application/service/outbox/NexonApiRetryClient.java +0/-29; MODIFIED module-app/src/main/resources/application-local.yml +0/-6].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #689 — feat(pgmq): Phase 4 - Cleanup deprecated ports, drop outbox tables, add archive cleanup
- author/state/dates: zbnerd | MERGED | created 2026-04-01T01:43:01Z | closed 2026-04-01T02:07:16Z | merged yes at 2026-04-01T02:07:16Z | merge c1388a5a0574e6210bcf58b618d1886e220b98e5.
- body: ## Summary - `@Deprecated` NexonApiOutboxProcessorPort/MetricsPort (2주 운영 검증 후 삭제 예정) - Remove unused queues: `v4_buffer_queue`, `…
- reviews/discussion: 0 []; 0.
- commits: 1 [d57d76e]; linked issues: 0 [].
- file evidence: 14 [MODIFIED docker/postgres/init.sql +1/-10; MODIFIED module-app/src/main/resources/application-pglocal.yml +0/-14; MODIFIED module-app/src/test/kotlin/maple/expectation/testinfra/ContainerSingletonTest.kt +1/-3].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #690 — feat(pgmq): Phase 5 - Remove LikeSyncWorker and LikeSyncQueueProducer
- author/state/dates: zbnerd | MERGED | created 2026-04-01T02:14:46Z | closed 2026-04-01T18:22:25Z | merged yes at 2026-04-01T18:22:24Z | merge 5dacf53f279a3d84bce5060c32fc983313fe89cc.
- body: ## Summary - DB Trigger (`fn_like_count_trigger`, #664) already handles `like_count` auto-increment, making LikeSyncWorker stale -…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [87ee68c]; linked issues: 0 [].
- file evidence: 10 [MODIFIED docker/prometheus/rules/alert_rules.yml +1/-1; MODIFIED module-app/src/main/resources/application-pgprod.yml +0/-5; DELETED module-app/src/test/kotlin/maple/expectation/integration/worker/LikeSyncWorkerIntegrationTest.kt +0/-456].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #691 — FEAT : Fan-out batch worker with micro-batch coalescing for Nexon API
- author/state/dates: zbnerd | MERGED | created 2026-04-01T08:15:23Z | closed 2026-04-01T10:55:16Z | merged yes at 2026-04-01T10:55:16Z | merge 37fe71f5cdfa95e13b9b29289843d0acb4061f8f.
- body: ## 🔗 관련 이슈 See also : Fan-Out Explosion and Admission Control 설계 문서 ## 🗣 개요 Nexon API 429 Rate Limit 대응을 위한 Fan-Out Batch Worker 아…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [e9047e0, 1e5de12, 84de741]; linked issues: 0 [].
- file evidence: 18 [ADDED docs/09_Plans/2026-04-01-fanout-batch-worker-with-coalescing.md +318/-0; MODIFIED module-app/src/main/resources/application-pglocal.yml +6/-0; MODIFIED module-app/src/main/resources/application-pgprod.yml +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #692 — PERF : ADR-384 V5 엔드포인트 성능 튜닝
- author/state/dates: zbnerd | MERGED | created 2026-04-04T11:56:01Z | closed 2026-04-04T12:44:02Z | merged yes at 2026-04-04T12:44:02Z | merge ede2af107b08aa77c5e0ba5695347ed442fd5c90.
- body: ## Summary - **B1**: `ForkJoinPool.commonPool` → `expectationComputeExecutor` 전용 풀로 교체 (commonPool 고갈 방지) - **B2**: `NexonFanOutBa…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [2446dd9, 80cd04b]; linked issues: 0 [].
- file evidence: 3 [ADDED docs/01_ADR/ADR-384-v5-endpoint-performance-tuning.md +217/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/fanout/NexonFanOutBatchLoader.kt +2/-10; MODIFIED module-web/src/main/kotlin/maple/expectation/web/controller/v5/GameCharacterControllerV5.kt +24/-4].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #696 — FIX: Vultr 프로필 서버 기동 에러 4건 수정
- author/state/dates: zbnerd | MERGED | created 2026-04-04T18:05:05Z | closed 2026-04-04T18:08:16Z | merged yes at 2026-04-04T18:08:16Z | merge c5e7a6e39e5e5ac22a63266eaf61845eabea5d35.
- body: ## Summary - V5Config: Legacy polling workers 비활성화 (PGMQ workers가 소비 담당, `executor.start()` 주석 처리) - TransactionConfig: 명시적 JPA `t…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [8ae3fe9]; linked issues: 0 [].
- file evidence: 4 [MODIFIED module-app/src/main/java/maple/expectation/config/V5Config.java +4/-4; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TransactionConfig.kt +14/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/hotkey/HotKeyDetector.kt +6/-5].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #697 — FIX : V5 엔드포인트 인증 없이 접근 허용
- author/state/dates: zbnerd | MERGED | created 2026-04-05T00:29:54Z | closed 2026-04-05T00:52:56Z | merged yes at 2026-04-05T00:52:56Z | merge e28909a62343d2dd8c0ca3ae01abd7fb8719bfe1.
- body: ## Summary - SecurityConfig에 `/api/v5/**` permitAll 추가 - 기존 `/api/**` authenticated() 규칙이 V5 엔드포인트를 차단하여 401 반환 - 부하테스트 1000 요청 10…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [42926f4, b32764a]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/build.gradle +2/-1; MODIFIED module-app/src/main/resources/application-vultr.yml +8/-0; MODIFIED module-app/src/main/resources/logback-spring.xml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #698 — FIX : 확률 질량 보존 검증 예외 → 경고/정규화로 완화
- author/state/dates: zbnerd | MERGED | created 2026-04-05T01:22:57Z | closed 2026-04-05T01:23:16Z | merged yes at 2026-04-05T01:23:16Z | merge cefac579be0bfc16ab78612cb4f3c4e044ac84b5.
- body: ## Summary - 부동소수점 누적 오차(Σp=0.999996~1.000008)로 1e-12 tolerance에서 모든 Worker 계산 실패 - STRICT throw를 제거하고 정규화 후 계속 진행하도록 변경 - MASS_TO…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [76aac20]; linked issues: 0 [].
- file evidence: 3 [MODIFIED module-app/src/main/java/maple/expectation/application/service/cube/component/SlotDistributionBuilder.java +4/-3; MODIFIED module-core/src/main/kotlin/maple/expectation/core/domain/service/calculator/ProbabilityConverter.kt +2/-6; MODIFIED module-core/src/main/kotlin/maple/expectation/core/probability/ProbabilityConvolver.kt +2/-4].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #699 — FEAT : PgmqWorker 배치 병렬 처리로 처리량 3배 향상
- author/state/dates: zbnerd | MERGED | created 2026-04-05T01:35:13Z | closed 2026-04-05T02:06:39Z | merged yes at 2026-04-05T02:06:39Z | merge eb118dec681f2273a83718b0ab1a656b62faca91.
- body: ## Summary - PgmqWorker의 sequential `messages.forEach`를 `CompletableFuture.supplyAsync` + Virtual Thread Executor로 변경 - `.join()`이…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [8353552, 5dd38fe, 93a58de]; linked issues: 0 [].
- file evidence: 2 [MODIFIED module-app/src/main/resources/application-vultr.yml +15/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +13/-3].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #701 — FEAT : Worker 배치 Fan-out + Coalescing pre-warm (ADR-387)
- author/state/dates: zbnerd | MERGED | created 2026-04-05T04:45:08Z | closed 2026-04-05T04:54:25Z | merged yes at 2026-04-05T04:54:25Z | merge 2a363565e5672a4839e7cf189400b5e4b7374450.
- body: ## Summary - `PgmqWorker`에 `preWarmBatch()` open 훅 추가 — 배치 메시지 병렬 처리 전 호출 - `ExpectationCalcWorker` / `ExpectationCalcLowWorker`에서…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ad73a74]; linked issues: 0 [].
- file evidence: 5 [ADDED docs/01_ADR/ADR-387-worker-batch-fanout-coalescing.md +76/-0; ADDED docs/09_Plans/2026-04-05-worker-batch-fanout-coalescing.md +158/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt +19/-2].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #702 — FIX : 확률 > 1 감지 false positive 수정 (tolerance 불일치)
- author/state/dates: zbnerd | MERGED | created 2026-04-05T05:37:29Z | closed 2026-04-05T05:45:23Z | merged yes at 2026-04-05T05:45:23Z | merge cd56f8d5138ef33a26f64483de5be5523a0eeb8d.
- body: ## Summary - CSV 데이터 총 질량이 1.000001~1.000009로 미세 초과 시 `MASS_TOLERANCE(1e-5)` 내라 정규화 생략 → contribution=0 버킷이 allTotal 보유 → `hasValu…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [2a1b040]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/main/java/maple/expectation/application/service/cube/component/SlotDistributionBuilder.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #703 — FEAT : V5 Inline View Write — Async Materialized View 패턴 (ADR-388)
- author/state/dates: zbnerd | MERGED | created 2026-04-05T06:52:18Z | closed 2026-04-07T12:28:51Z | merged yes at 2026-04-07T12:28:51Z | merge a5b5afab80a9d0d00e75bc8d376d4cfec905fcac.
- body: ## Summary - Worker 계산 트랜잭션 내에서 `character_valuation_views`에 직접 upsert하는 Inline View Write 패턴 구현 - PGMQ payload에 `EquipmentExpecta…
- reviews/discussion: 0 []; 0.
- commits: 2 [1ec2b71, a5b5afa]; linked issues: 0 [].
- file evidence: 6 [ADDED docs/01_ADR/ADR-388-inline-view-write-precomputed-read-model.md +90/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +39/-2; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java +75/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #705 — MERGE : develop → master (56 commits)
- author/state/dates: zbnerd | MERGED | created 2026-04-07T12:52:07Z | closed 2026-04-07T16:02:55Z | merged yes at 2026-04-07T16:02:54Z | merge 565280665680d9fc56b17aa4c0d396e0134acafb.
- body: ## Summary - develop 브랜치의 56개 커밋을 master로 병합 - 주요 변경사항: - PGMQ Outbox 마이그레이션 (Phase 0~5 완료) - V5 엔드포인트 성능 튜닝 (ADR-384) - Worker 배치…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 107 [6756cb7, 979ca5f, a48475c]; linked issues: 0 [].
- file evidence: 192 [DELETED .github/workflows/load-test.yml +0/-396; MODIFIED docker/postgres/init.sql +3/-9; MODIFIED docker/prometheus/rules/alert_rules.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #706 — FIX : Issues #645-#650 신뢰성/성능 개선 (L2 인덱스, DLQ replay, Stampede, Shutdown, Pool 모니터링)
- author/state/dates: zbnerd | MERGED | created 2026-04-08T10:47:42Z | closed 2026-04-08T10:48:55Z | merged yes at 2026-04-08T10:48:55Z | merge a7725146a6645e93bb01af4b019f2abf59fae587.
- body: ## 🔗 관련 이슈 - Resolves #645 — L2 Cache LIKE 풀테이블 스캔 → 인덱스 활용 range query - Resolves #646 — DLQ 자동 Replay 메커니즘 - Resolves #647 — Cac…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [6232456]; linked issues: 0 [].
- file evidence: 17 [MODIFIED docker/prometheus/rules/alert_rules.yml +11/-0; MODIFIED docs/09_Plans/issues-645-650-resolution-plan.md +158/-52; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt +25/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #707 — FIX : Micro-batch Future lifecycle — batch 실패 즉시 종료 + in-flight backpressure
- author/state/dates: zbnerd | MERGED | created 2026-04-09T01:23:35Z | closed 2026-04-09T01:24:45Z | merged yes at 2026-04-09T01:24:45Z | merge 6535191e0e53c550ede1bf456660cc373a2e7435.
- body: ## 🔗 관련 이슈 ## 🗣 개요 AdaptiveMicroBatchUserService의 Future lifecycle 관리 개선. Batch 실패 시 Future가 무기한 대기하는 문제와 in-flight 무제한 증가를 방지. ##…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [b29f7c1]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserService.kt +46/-12].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #708 — FIX : Issues #651-#655 관측성, 보안, 동시성, 설정 외부화, Redis 잔여 코드 정리
- author/state/dates: zbnerd | MERGED | created 2026-04-09T03:40:51Z | closed 2026-04-15T05:37:55Z | merged yes at 2026-04-15T05:37:55Z | merge e8e68d12b628354226a1676b361c335299eec4fa.
- body: ## Summary 5개 P2 이슈 일괄 해결 (Consensus Review 3-Agent 검증 완료) - **#651** [Reliability] LockMetrics Map 기반 OCP 리팩토링 + PostgresAdvisory…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [726aea1]; linked issues: 0 [].
- file evidence: 15 [ADDED docs/09_Plans/issues-651-655-resolution-plan.md +346/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +12/-5; MODIFIED module-app/src/main/resources/application.yml +12/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #709 — Develop to master
- author/state/dates: zbnerd | MERGED | created 2026-04-15T05:38:16Z | closed 2026-04-15T05:38:27Z | merged yes at 2026-04-15T05:38:27Z | merge 978889c72a39379a6742f6d51a1026699a45bb6d.
- body: none
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 3 [a772514, 6535191, e8e68d1]; linked issues: 0 [].
- file evidence: 33 [MODIFIED docker/prometheus/rules/alert_rules.yml +11/-0; MODIFIED docs/09_Plans/issues-645-650-resolution-plan.md +158/-52; ADDED docs/09_Plans/issues-651-655-resolution-plan.md +346/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #710 — fix: harden v5 expectation task flow
- author/state/dates: zbnerd | MERGED | created 2026-04-16T05:38:08Z | closed 2026-04-16T05:40:58Z | merged yes at 2026-04-16T05:40:57Z | merge f1bdd7a7f4f9ba72e47d1c2815d94fbf4878d677.
- body: ## 관련 이슈 #634 #639 ## 개요 V5 expectation 요청 경로의 task 정합성, queue dedupe, query-side read consistency, worker 중복 로직을 함께 정리해 CQRS 플로우를…
- reviews/discussion: 0 []; 1 /  ### 💡 Codex Review https://github.com/zbnerd/probabili….
- commits: 1 [f1bdd7a]; linked issues: 0 [].
- file evidence: 19 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +37/-7; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/ViewTransformer.java +18/-6; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/queue/ExpectationCalculationQueue.java +32/-35].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #711 — Validate Nexon API account & OCIDs, fix rate limiter permit leak, improve builders and tes…
- author/state/dates: zbnerd | MERGED | created 2026-04-18T04:16:02Z | closed 2026-04-18T04:16:22Z | merged yes at 2026-04-18T04:16:22Z | merge 33446add66596af33532315d01f8190ddbead984.
- body: ### Motivation - Enforce stronger Nexon API key/account validation to prevent accepting malformed or unrelated API keys and to add…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [f92e685]; linked issues: 0 [].
- file evidence: 9 [ADDED docs/09_Plans/open-issues-remediation-plan-2026-04-17.md +41/-0; MODIFIED module-app/src/main/kotlin/maple/expectation/application/service/auth/ApiKeyValidator.kt +13/-4; ADDED module-app/src/test/kotlin/maple/expectation/application/service/auth/ApiKeyValidatorTest.kt +117/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #712 — FIX : Issues #656-#672 NPE, 복잡도, SingleFlight xact lock, N+1 회귀, 테스트 안정화
- author/state/dates: zbnerd | MERGED | created 2026-04-18T05:02:37Z | closed 2026-04-18T05:03:56Z | merged yes at 2026-04-18T05:03:56Z | merge 2d6d586012b1ad96ff423ba9fb1483c37cb986ff.
- body: ## Summary - **#656** Thread.sleep → Awaitility 마이그레이션 (AdmissionControl, PgmqClient, GlobalAdmissionControl) - **#657** CubeCalcu…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [7b54156]; linked issues: 0 [].
- file evidence: 12 [MODIFIED module-app/build.gradle +1/-0; MODIFIED module-app/src/test/kotlin/maple/expectation/application/service/auth/ApiKeyValidatorTest.kt +13/-15; MODIFIED module-app/src/test/kotlin/maple/expectation/integration/AdmissionControlIntegrationTest.kt +21/-4].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #713 — FIX : Codex review P0-P2 이슈 픽스 (DLQ, 계정 인증, SingleFlight, lifecycle, 백프레셔)
- author/state/dates: zbnerd | MERGED | created 2026-04-18T05:28:08Z | closed 2026-04-18T05:28:17Z | merged yes at 2026-04-18T05:28:17Z | merge 5ad39469c9aabc7c4107e42047a0cef8203bb9cf.
- body: ## Summary PR #712 이후 Codex 리뷰어가 발견한 P0~P2 이슈 전체 픽스: **P0** - DlqReplayWorker: `read_ct > 1` 필터로 성공 메시지 제외 (DLQ만 추적) **P1** - DlqR…
- reviews/discussion: 0 []; 0.
- commits: 1 [381ee87]; linked issues: 0 [].
- file evidence: 8 [MODIFIED module-app/src/main/kotlin/maple/expectation/application/service/auth/ApiKeyValidator.kt +11/-5; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserService.kt +18/-16; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/concurrency/PostgresSingleFlightStrategy.kt +11/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #714 — TEST : Issue #704 Multi-Instance Cache Invalidation Consistency Test
- author/state/dates: zbnerd | MERGED | created 2026-04-18T06:08:04Z | closed 2026-04-18T06:10:24Z | merged yes at 2026-04-18T06:10:24Z | merge c582c090d37e1eb1387c37537606006aa504e4a4.
- body: ## Summary - PostgreSQL LISTEN/NOTIFY 기반 분산 캐시 무효화가 3개 인스턴스 간에 정상 동작하는지 검증하는 통합 테스트 추가 - 6개 시나리오: evict 전파, burst 50키, stale versi…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [c88efd6]; linked issues: 0 [].
- file evidence: 3 [ADDED docs/01_ADR/ADR-704-multi-instance-cache-invalidation-test.md +62/-0; ADDED docs/09_Plans/issue-704-multi-instance-cache-invalidation-test.md +204/-0; ADDED module-app/src/test/kotlin/maple/expectation/integration/cache/MultiInstanceCacheInvalidationTest.kt +385/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #717 — FIX : Issues #715 #716 cache_storage 마이그레이션 누락 + 버전 카운터 충돌
- author/state/dates: zbnerd | MERGED | created 2026-04-18T06:30:45Z | closed 2026-04-18T06:30:55Z | merged yes at 2026-04-18T06:30:55Z | merge 57b7777b832e5ab4225be60db758bc8f388ee681.
- body: ## Summary - **#715 (P0)**: V110 마이그레이션으로 `cache_storage` UNLOGGED 테이블 CREATE 추가 (기존 V102, V107은 인덱스만 생성) - **#716 (P1)**: `Tiered…
- reviews/discussion: 0 []; 0.
- commits: 1 [8cc3d98]; linked issues: 0 [].
- file evidence: 6 [ADDED docs/01_ADR/ADR-715-716-cache-storage-migration-version-counter-fix.md +35/-0; ADDED docs/09_Plans/issues-715-716-cache-storage-migration-version-counter-fix.md +91/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt +6/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #718 — DOCS : 성능 최적화 포트폴리오 v2 (97→7,000+ RPS)
- author/state/dates: zbnerd | MERGED | created 2026-04-18T07:21:40Z | closed 2026-04-18T07:22:48Z | merged yes at 2026-04-18T07:22:48Z | merge ca0962d04b1679f8b328f8c7ecd9ba8f1866026f.
- body: ## Summary - 97 RPS → 7,000+ RPS 성능 최적화 여정을 `portfolio_example.md` 형식에 맞춰 재작성 - 9개 핵심 병목 해결 과정을 Problem → Options → Decision → Imp…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [a6fe335]; linked issues: 0 [].
- file evidence: 2 [ADDED docs/18_Portfolio/performance-optimization-portfolio-v2.md +763/-0; ADDED docs/18_Portfolio/portfolio_example.md +588/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #719 — DOCS: V5 엔드포인트 데이터 플로우 아키텍처 다이어그램
- author/state/dates: zbnerd | MERGED | created 2026-04-19T06:27:08Z | closed 2026-04-19T06:29:00Z | merged yes at 2026-04-19T06:29:00Z | merge 33a635ba48e9df04d68f33abde1fe7b2d2da6890.
- body: ## Summary - V5 CQRS + Async Materialized View 패턴의 전체 데이터 흐름을 Mermaid 다이어그램 4종으로 문서화 - 전체 아키텍처 플로우차트, Cache HIT/MISS 시퀀스, Worker 계…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [ea8c1dc]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/04_Sequence_Diagrams/v5-endpoint-data-flow-architecture.md +302/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #720 — DOCS: V5 하이레벨 아키텍처 다이어그램
- author/state/dates: zbnerd | MERGED | created 2026-04-19T06:37:05Z | closed 2026-04-19T06:37:07Z | merged yes at 2026-04-19T06:37:07Z | merge 66367e8999b89a0789e4156c887c53c266d4e427.
- body: ## Summary - 서브시스템 제목만으로 구성된 간소화된 V5 아키텍처 플로우차트 - Cache HIT/MISS, Worker Processing, Task Polling 4개 경로 요약 - 상세 다이어그램은 v5-endpoint…
- reviews/discussion: 0 []; 0.
- commits: 1 [166114e]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/04_Sequence_Diagrams/v5-highlevel-architecture.md +61/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #539 — refactor: ADR-004 Phase 5-E & 5-F - like/donation 패키지 application 계층 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-03T18:50:50Z | closed 2026-03-03T21:06:04Z | merged yes at 2026-03-03T21:06:04Z | merge 6e8a05b28f0ae360efe3220e984d79ba6e9650c9.
- body: ## 관련 이슈 ADR-004 Phase 5-E & 5-F ## 개요 ADR-004 Module-Core Boundary Establishment의 일환으로 like/donation 패키지를 service/v2/에서 applicati…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [a4696d5]; linked issues: 0 [].
- file evidence: 17 [RENAMED module-app/src/main/java/maple/expectation/application/service/donation/DonationProcessor.java +1/-2; RENAMED module-app/src/main/java/maple/expectation/application/service/donation/InternalPointPaymentStrategy.java +1/-1; RENAMED module-app/src/main/java/maple/expectation/application/service/donation/PaymentStrategy.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #540 — refactor: ADR-004 Phase 5-G/H - facade/worker/flame/starforce 패키지 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-03T21:22:05Z | closed 2026-03-04T11:33:18Z | merged yes at 2026-03-04T11:33:18Z | merge 04a5b505664596ad5e3b1a4af8fcd6759cb4ef06.
- body: ## Summary ADR-004 Sub-Phase 5-G & 5-H 구현을 완료했습니다: - **Phase 5-G**: facade + worker 패키지를 application 계층으로 이관 - **Phase 5-H**: flam…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [a4696d5, 0e58d10, 147088b]; linked issues: 0 [].
- file evidence: 106 [MODIFIED docs/05_Reports/api-backward-compatibility.md +1/-1; ADDED docs/05_Reports/session-report-2026-03-03-172430.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-03-183211.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #541 — refactor: consolidate module-core domain directories
- author/state/dates: zbnerd | MERGED | created 2026-03-04T13:23:50Z | closed 2026-03-04T13:36:25Z | merged yes at 2026-03-04T13:36:25Z | merge 0086e3b74a0cf98a3a79c80255cf8e3009d53503.
- body: ## 관련 이슈요 N/A (기술 부채 정리) ## 개요 module-core 내 domain 디렉토리 구조를 core/domain으로 통합합니다. ## 작업 내용 - [x] module-core/domain/* → module-cor…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [4368ad0]; linked issues: 0 [].
- file evidence: 94 [ADDED docs/05_Reports/session-report-2026-03-04-123713.md +49/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/EquipmentApplicationService.java +3/-3; MODIFIED module-app/src/main/java/maple/expectation/application/service/auth/AuthService.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #542 — test: add ArchUnit tests for module dependency verification
- author/state/dates: zbnerd | MERGED | created 2026-03-04T23:59:13Z | closed 2026-03-05T00:40:53Z | merged yes at 2026-03-05T00:40:53Z | merge 893b61dbe7e23e451d692e93c533ab2840bd6bda.
- body: ## 관련 이슈 Phase 6 Implementation ## 개요 Add ArchUnit tests to enforce clean module dependency direction and document technical debt.…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [e750421]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/test/java/architecture/ModuleDependencyTest.java +91/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #543 — refactor: introduce ExceptionClassifier for circuit breaker classification
- author/state/dates: zbnerd | MERGED | created 2026-03-05T01:06:48Z | closed 2026-03-05T01:23:23Z | merged yes at 2026-03-05T01:23:23Z | merge 174d93d1bf7baff2f1b31e0a1579b427b0abdb7b.
- body: ## 관련 이슈 ADR-008 ## 개요 도메인 예외에서 인프라 마커 인터페이스를 제거하고, 중앙화된 `ExceptionClassifier` 전략 패턴을 도입합니다. ## 작업 내용 - [x] `ExceptionClassifier` …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [9c74215, 686c905]; linked issues: 0 [].
- file evidence: 10 [ADDED docs/adr/ADR-008-exception-classifier.md +144/-0; MODIFIED module-common/src/main/kotlin/maple/expectation/error/exception/base/ClientBaseException.kt +7/-5; MODIFIED module-common/src/main/kotlin/maple/expectation/error/exception/base/ServerBaseException.kt +7/-5].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #544 — refactor: package structure cleanup for Kotlin migration preparation
- author/state/dates: zbnerd | MERGED | created 2026-03-05T02:43:21Z | closed 2026-03-05T04:01:39Z | merged yes at 2026-03-05T04:01:39Z | merge 13a0066bedb8d3dc56232989b83e7b2e21d98514.
- body: ## Summary - Move `StarforceLookupAdapter` from `infrastructure/adapter/starforce` to `application/service/starforce` - Lowered GR…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [f41a924, e67dcda]; linked issues: 0 [].
- file evidence: 8 [ADDED docs/05_Reports/session-report-2026-03-05-010035.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-05-022056.md +49/-0; MODIFIED docs/guardrails/INDEX.json +5/-4].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #545 — refactor: complete BatchWriter and NexonDataCollector migration to module-infra
- author/state/dates: zbnerd | MERGED | created 2026-03-05T05:52:31Z | closed 2026-03-05T21:11:48Z | merged yes at 2026-03-05T21:11:48Z | merge 4db91b3a54e733bdd1085b365996e97ed4f10384.
- body: ## 관련 이슈 N/A (기술 부채 해결) ## 개요 BatchWriter와 NexonDataCollector의 module-infra 이관 작업을 완료하고, 기존 마이그레이션 누락 사항을 수정 ## 작업 내용 - [x] BatchW…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [9a11911, cc64d9b]; linked issues: 0 [].
- file evidence: 9 [ADDED docs/05_Reports/session-report-2026-03-05-065429.md +49/-0; ADDED docs/rules/dto-ownership.md +287/-0; DELETED module-app/src/main/java/maple/expectation/service/ingestion/AclPipelineMetrics.java +0/-201].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #546 — feat: add Redis Streams reliability features to MongoDBSyncWorker
- author/state/dates: zbnerd | MERGED | created 2026-03-05T21:47:12Z | closed 2026-03-05T21:49:37Z | merged yes at 2026-03-05T21:49:37Z | merge 06cb9882fe3dcf276d6c32607346ae9c5e5b7268.
- body: ## Summary - PEL Recovery: 2-phase startup ( process pending messages first - Poison Pill DLQ: retry tracking and Dead Letter Queu…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 21 [7899315, 33387e2, c0b37b5]; linked issues: 0 [].
- file evidence: 508 [ADDED Note +0/-0; MODIFIED docs/05_Reports/api-backward-compatibility.md +1/-1; ADDED docs/05_Reports/session-report-2026-03-01-111627.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #569 — perf: P2-18 Component Scanning Optimization - Explicit scanBasePackages Configuration
- author/state/dates: zbnerd | CLOSED | created 2026-03-08T07:43:52Z | closed 2026-03-08T13:43:44Z | merged no | merge —.
- body: ## 관련 이슈 #P2-18 (Component Scanning Optimization) #P2-17 (Gradle Build Modernization) ## 개요 **이 PR은 2가지 개선 작업을 포함합니다:** 1. **Sprin…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 1 / Closing duplicate PR. Using PR #570 with develop base i….
- commits: 15 [893b61d, 174d93d, 13a0066]; linked issues: 0 [].
- file evidence: 107 [ADDED .agents/skills/gradle-build-performance/SKILL.md +346/-0; ADDED .agents/skills/kotlin-springboot/SKILL.md +70/-0; MODIFIED .claude/hooks/pre-tool-use.sh +39/-263].
- resolution: closed, not merged; no application claim. Portfolio: 성능.

### PR #570 — feat: implement code review units (P0/P1/P2) and fix pre-existing issues
- author/state/dates: zbnerd | MERGED | created 2026-03-08T13:16:54Z | closed 2026-03-08T13:44:51Z | merged yes at 2026-03-08T13:44:51Z | merge d51847267d1e0a2b1a6fc752b76349ce1f01942d.
- body: ## Overview Complete 5 code review units from 2026-03-06 comprehensive code review. All critical security vulnerabilities and oper…
- reviews/discussion: 2 [PENDING/zbnerd, COMMENTED/chatgpt-codex-connector]; 1 / ㄱㄷ.
- commits: 10 [2e393fb, 4f56b18, ef91256]; linked issues: 0 [].
- file evidence: 91 [ADDED .agents/skills/gradle-build-performance/SKILL.md +346/-0; ADDED .agents/skills/kotlin-springboot/SKILL.md +70/-0; MODIFIED CLAUDE.md +11/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #571 — feat: resolve P2 technical debt (5 units) - async executor, thread pools, N+1 queries, Hik…
- author/state/dates: zbnerd | MERGED | created 2026-03-08T14:35:11Z | closed 2026-03-08T14:36:16Z | merged yes at 2026-03-08T14:36:16Z | merge 3a1e296d71be811e6d58b1f019b68978b922553c.
- body: ## 관련 이슈 Technical Debt Resolution Plan - 5 P2 Issues ## 개요 Comprehensive P2 (Medium Priority) technical debt resolution to improv…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [3c5d2e9, 7029df5, ff7cd65]; linked issues: 0 [].
- file evidence: 14 [MODIFIED module-app/src/main/java/maple/expectation/ExpectationApplication.java +2/-2; MODIFIED module-app/src/main/java/maple/expectation/application/worker/MongoDBSyncWorker.java +33/-4; MODIFIED module-app/src/main/resources/application-ci.yml +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #572 — fix(units-6-7-8): Transaction Binding, Connection Pool, N+1 Query Optimization
- author/state/dates: zbnerd | MERGED | created 2026-03-08T15:00:06Z | closed 2026-03-09T02:00:01Z | merged yes at 2026-03-09T02:00:01Z | merge b93d3a18f2ec60f52e494844d719935b02ae9647.
- body: ## Summary Implements ADR-013: Multi-DataSource Transaction Strategy by adding explicit transaction manager binding to all `@Trans…
- reviews/discussion: 0 []; 2 / You have reached your Codex usage limits for code revie….
- commits: 22 [893b61d, 174d93d, 13a0066]; linked issues: 0 [].
- file evidence: 149 [ADDED .agents/skills/gradle-build-performance/SKILL.md +346/-0; ADDED .agents/skills/kotlin-springboot/SKILL.md +70/-0; MODIFIED .claude/hooks/pre-tool-use.sh +39/-263].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #573 — fix: Technical debt resolution - All 8 units (P0 security + P1 architecture)
- author/state/dates: zbnerd | MERGED | created 2026-03-08T15:01:50Z | closed 2026-03-09T04:25:38Z | merged yes at 2026-03-09T04:25:37Z | merge f3a644d3bbf6dc75202acf0e539c1a1e106a28e7.
- body: ## 관련 이슈 #283 Technical Debt Resolution - All 8 Units ## 개요 Comprehensive technical debt resolution covering all 8 units across P0…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 6 [57f9f75, 855dc89, 284861b]; linked issues: 0 [].
- file evidence: 8 [MODIFIED module-app/src/test/java/maple/expectation/scheduler/StreamJanitorSchedulerTest.java +11/-8; MODIFIED module-app/src/test/java/maple/expectation/service/v5/event/ViewTransformerTest.java +1/-0; MODIFIED module-app/src/test/java/maple/expectation/service/v5/worker/MongoDBSyncWorkerTest.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #574 — refactor: 명시적 @Transactional 속성 및 Event 버전 필드 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-09T00:57:42Z | closed 2026-03-09T00:58:00Z | merged yes at 2026-03-09T00:58:00Z | merge 36c569ef8ede9977c7bd0b57560f18f6606e4789.
- body: ## 관련 이슈 없음 (리팩토링) ## 개요 `@Transactional` 애노테이션 속성 명시화 및 Event 버전 필드 추가 ## 작업 내용 - [x] `@Transactional("value")` → `@Transactional…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 5 [57f9f75, 855dc89, 284861b]; linked issues: 0 [].
- file evidence: 50 [ADDED docs/05_Reports/06_09_Unit3_Query_Injection_Audit/SQL_INJECTION_AUDIT_REPORT.md +245/-0; ADDED docs/05_Reports/06_09_Unit3_Query_Injection_Audit/UNIT3_SUMMARY.md +206/-0; ADDED docs/05_Reports/06_09_Unit4_Event_Ordering/UNIT4_IMPLEMENTATION_SUMMARY.md +237/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #575 — fix: test failures - various fixes
- author/state/dates: zbnerd | MERGED | created 2026-03-09T01:54:20Z | closed 2026-03-09T01:54:40Z | merged yes at 2026-03-09T01:54:40Z | merge 8e64ffe73f40f1094b2a126e551b02fedd04795f.
- body: ## 관련 이슈 없음## 개요 테스트 실패 문제를 해결하는 다양한 수정사항 ## 변경 사항 ### StreamJanitorSchedulerTest - lenient stubbing 사용하여 mockito strict stubbing …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [5e9bc80]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #576 — feat: PostgreSQL Migration Phase 1 - Infrastructure Foundation
- author/state/dates: zbnerd | MERGED | created 2026-03-09T09:42:12Z | closed 2026-03-09T09:56:59Z | merged yes at 2026-03-09T09:56:59Z | merge 59ac7b738a4844e477279ed57a01e431cb90c179.
- body: ## Summary PostgreSQL Migration Phase 1 기반 작업 완료 (8 units). **Issues:** - #547: Docker Compose PostgreSQL + PGMQ - #548: Kotlin 변환…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [83c59f1]; linked issues: 0 [].
- file evidence: 484 [MODIFIED .editorconfig +7/-0; MODIFIED build.gradle +31/-2; MODIFIED docker-compose.yml +25/-1].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #577 — docs: add test infrastructure meta tests documentation
- author/state/dates: zbnerd | MERGED | created 2026-03-09T12:49:10Z | closed 2026-03-09T12:55:13Z | merged yes at 2026-03-09T12:55:13Z | merge 03e726efdc03b0b1573711f00e26398a8da3bc28.
- body: ## Summary - \`docs/integration-test/\` 폴더명 변경 (kebab-case) 및 문서화 - 테스트 인프라 메타 테스트 3개 추가 - 해결된 기술 부채 내용 업데이트 ## 작업 내용 ### 문서 정리 - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [48e928b]; linked issues: 0 [].
- file evidence: 1 [MODIFIED docs/integration-test/test-infra-verification.md +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #578 — feat: PostgreSQL Migration Foundation (#547, #548, #551)
- author/state/dates: zbnerd | MERGED | created 2026-03-09T14:01:06Z | closed 2026-03-09T14:11:49Z | merged yes at 2026-03-09T14:11:49Z | merge 30cd769324f2740821e2afe2333af5c1f7d4bb47.
- body: ## 관련 이슈 - #547 - PostgreSQL + PGMQ Docker Compose 설정 - #548 - 프로젝트 초기 설정 + Kotlin 변환 기반 - #551 - ADR-001 PostgreSQL 단일 DB 전략 ## 개…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 8 [57f9f75, 855dc89, 284861b]; linked issues: 0 [].
- file evidence: 7 [ADDED docker-compose.postgres.yml +57/-0; MODIFIED docker/postgres/init.sql +64/-6; MODIFIED docs/adr/001-postgresql-single-db-strategy.md +255/-98].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #579 — fix: PostgreSQL 테스트 환경 구성 및 테스트 격리 문제 해결
- author/state/dates: zbnerd | MERGED | created 2026-03-10T01:43:10Z | closed 2026-03-10T02:05:12Z | merged yes at 2026-03-10T02:05:12Z | merge 06a6541454b61be8c5012a845cffd4070dd52eb1.
- body: ## 관련 이슈 - #552 PGMQ Consumer/Worker 구현 - #553 ADR-002 PGMQ Integration ## 개요 PostgreSQL 마이그레이션 Phase 2 작업 중 발생한 테스트 실패 문제를 해결하고, …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 9 [57f9f75, 855dc89, 284861b]; linked issues: 0 [].
- file evidence: 37 [ADDED docker-compose.postgres.yml +57/-0; MODIFIED docker/postgres/init.sql +64/-6; MODIFIED docs/adr/001-postgresql-single-db-strategy.md +255/-98].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #580 — docs: PostgreSQL 기반 종합 데이터 흐름 다이어그램 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-10T05:32:34Z | closed 2026-03-10T05:42:23Z | merged yes at 2026-03-10T05:42:23Z | merge 6801c652d793a70314c2f46cc73a028fac493ffe.
- body: ## 관련 이슈 문서화 작업 ## 개요 실제 코드베이스를 심층 분석하여 15개의 Mermaid 데이터 흐름 다이어그램을 작성했습니다. PostgreSQL을 메인 DB로 반영했습니다. ## 작업 내용 - [x] 시스템 전체 데이터 흐름…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [714a820]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/04_Sequence_Diagrams/comprehensive-data-flow.md +801/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #581 — Docs/comprehensive data flow diagram
- author/state/dates: zbnerd | MERGED | created 2026-03-10T09:27:02Z | closed 2026-03-10T09:27:31Z | merged yes at 2026-03-10T09:27:31Z | merge 6a08c5440af60e15b28da34b8d1b15d002db01e8.
- body: none
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [8d0157e, 961572b]; linked issues: 0 [].
- file evidence: 39 [ADDED docs/04_Sequence_Diagrams/scheduler-data-flow-diagrams.md +979/-0; ADDED docs/adr/003-postgresql-redis-replacement.md +396/-0; ADDED docs/adr/004-collect-compute-serve-pipeline.md +610/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #582 — Docs/comprehensive data flow diagram
- author/state/dates: zbnerd | MERGED | created 2026-03-10T10:28:09Z | closed 2026-03-10T10:28:28Z | merged yes at 2026-03-10T10:28:28Z | merge 61bddd967d32ddbc8cece478cd8f25e637128128.
- body: none
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c1a6d6d]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/05_Reports/legacy-storage-migration-analysis.md +303/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #583 — refactor: LikeSync Scheduler 타이밍 정렬 및 PostgreSQL Migration 문서화
- author/state/dates: zbnerd | MERGED | created 2026-03-10T11:20:34Z | closed 2026-03-10T11:21:29Z | merged yes at 2026-03-10T11:21:29Z | merge 774b7595c79fa66d37d4928e45cbc25d03491614.
- body: ## 관련 이슈 - Scheduler Data Flow Diagrams 문서 분석 - PostgreSQL Migration Phase 3-4 (#554-#558) ## 개요 Scheduler Data Flow Diagrams 문서 분…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [0ca8cd6]; linked issues: 0 [].
- file evidence: 3 [MODIFIED docs/04_Sequence_Diagrams/scheduler-data-flow-diagrams.md +4/-4; MODIFIED module-app/src/main/resources/application.yml +7/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/LikeSyncScheduler.kt +22/-7].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #584 — feat: PostgreSQL scale-out migration for Redis-free operation
- author/state/dates: zbnerd | MERGED | created 2026-03-10T13:54:24Z | closed 2026-03-10T13:55:38Z | merged yes at 2026-03-10T13:55:38Z | merge e45a208ac0cd1937f50f77c17c50d43daedce4da.
- body: ## 관련 이슈 Closes #554, #559, #560, #561, #564 ## 개요 Redis 의존성 제거를 위한 PostgreSQL 기반 스케일아웃 마이그레이션 구현 ### ADR 문서 - **ADR-003**: Postgr…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [08f7067]; linked issues: 0 [].
- file evidence: 14 [ADDED docs/adr/003-postgresql-advisory-lock.md +559/-0; ADDED docs/adr/005-single-flight-hot-key.md +638/-0; ADDED docs/adr/006-scaleout-strategy.md +731/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #585 — feat: Event Contract Standards and Port Signature ArchUnit Test
- author/state/dates: zbnerd | CLOSED | created 2026-03-10T16:19:04Z | closed 2026-03-10T16:28:11Z | merged no | merge —.
- body: ## Summary Implements 4 related issues for event standardization and architecture enforcement: - **#500 [P0]**: Create ArchUnit te…
- reviews/discussion: 0 []; 2 / You have reached your Codex usage limits for code revie….
- commits: 56 [893b61d, 174d93d, 13a0066]; linked issues: 0 [].
- file evidence: 667 [ADDED .agents/skills/gradle-build-performance/SKILL.md +346/-0; ADDED .agents/skills/kotlin-springboot/SKILL.md +70/-0; MODIFIED .claude/hooks/pre-tool-use.sh +39/-263].
- resolution: closed, not merged; no application claim. Portfolio: 품질.

### PR #586 — feat: Event Contract Standards and Port Signature ArchUnit Test
- author/state/dates: zbnerd | MERGED | created 2026-03-10T16:28:32Z | closed 2026-03-10T16:28:42Z | merged yes at 2026-03-10T16:28:42Z | merge 9b58c9d264a5c693538ef49ac3e718b5c2e79c09.
- body: ## Summary Implements 4 related issues for event standardization and architecture enforcement: - **#500 [P0]**: Create ArchUnit te…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [670c2c0]; linked issues: 1 [#500/CLOSED].
- file evidence: 6 [ADDED docs/events/compatibility.md +316/-0; ADDED docs/events/contract-v1.md +182/-0; ADDED docs/events/samples/cache-invalidated.v1.md +219/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #587 — chore: sync develop to master
- author/state/dates: zbnerd | MERGED | created 2026-03-10T16:34:49Z | closed 2026-03-10T16:35:04Z | merged yes at 2026-03-10T16:35:04Z | merge 9b7dfa68a04cab064e6d2e844a67579bf798753a.
- body: Syncing develop branch with master after PR #586 merge.
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 57 [893b61d, 174d93d, 13a0066]; linked issues: 0 [].
- file evidence: 558 [MODIFIED .editorconfig +7/-0; MODIFIED CLAUDE.md +96/-554; MODIFIED build.gradle +30/-2].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #593 — [V5] PostgreSQL Migration - Infrastructure Layer
- author/state/dates: zbnerd | MERGED | created 2026-03-11T13:52:05Z | closed 2026-03-11T13:52:17Z | merged yes at 2026-03-11T13:52:17Z | merge 69d675cedb99f3e47037cc1e7eb708f238deb999.
- body: ## Summary - PostgreSQL migration을 위한 infrastructure layer 변경사항 - Redis/Redisson 의존성 제거 준비 및 PostgreSQL 대체 구현 추가 - MongoDB 의존성 제거 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c997421]; linked issues: 0 [].
- file evidence: 22 [MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/AdaptiveMicroBatchUserService.kt +2/-2; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/listener/BatchOptimisticLockListener.kt +1/-1; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/config/BatchConfig.kt +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #594 — feat: remove Redis/Redisson dependencies for PostgreSQL migration (#589)
- author/state/dates: zbnerd | MERGED | created 2026-03-11T21:19:53Z | closed 2026-03-11T21:20:01Z | merged yes at 2026-03-11T21:20:01Z | merge a31011c12f73eceb47390ef76c08f10db91e3252.
- body: ## Summary - Remove all Redis/Redisson dependencies from the codebase - Migrate to PostgreSQL-based alternatives as documented in …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c42d00c]; linked issues: 0 [].
- file evidence: 121 [ADDED docs/adr/022-redis-dependency-removal.md +241/-0; MODIFIED module-app/build.gradle +0/-4; DELETED module-app/src/main/java/maple/expectation/application/scheduler/StreamJanitorScheduler.java +0/-102].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #595 — [V5 Migration] MongoDB 의존성 완전 제거 (#590)
- author/state/dates: zbnerd | MERGED | created 2026-03-11T21:39:31Z | closed 2026-03-11T21:50:43Z | merged yes at 2026-03-11T21:50:43Z | merge 48c5e18207c13f3c8a21a4b258d45c5ab5e8b6dc.
- body: ## Summary - MongoDB 의존성 완전 제거 및 PostgreSQL로 마이그레이션 - 7개 MongoDB 소스 파일 삭제, 3개 테스트 파일 삭제 - ViewTransformer, CharacterViewQueryPortA…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [8eba84f, 6614a69]; linked issues: 0 [].
- file evidence: 26 [MODIFIED build.gradle +0/-1; ADDED docs/adr/023-mongodb-dependency-removal.md +120/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/event/CalculationCompletedEventListener.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #596 — [V5 Migration] MySQL 의존성 완전 제거 (#591)
- author/state/dates: zbnerd | MERGED | created 2026-03-11T22:13:56Z | closed 2026-03-11T22:28:04Z | merged yes at 2026-03-11T22:28:04Z | merge 6ca308e1e200433a34ffb5dce56a77ac999c2558.
- body: ## Summary - MySQL 의존성을 완전히 제거하고 PostgreSQL로 마이그레이션 - V5 마이그레이션의 완성 (Redis #589, MongoDB #590, MySQL #591) ## Changes ### Dependen…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [95fc612]; linked issues: 0 [].
- file evidence: 20 [MODIFIED build.gradle +0/-1; MODIFIED docker-compose.yml +3/-149; ADDED docs/adr/024-mysql-dependency-removal.md +146/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #597 — chore: remove unused Redis/MySQL/MongoDB legacy code (#591)
- author/state/dates: zbnerd | MERGED | created 2026-03-11T23:15:24Z | closed 2026-03-11T23:16:56Z | merged yes at 2026-03-11T23:16:56Z | merge cc7f26b4221c1e302a7e474976e813e489cc38df.
- body: ## Summary - PostgreSQL 통합 완료로 인한 미사용 코드 정리 - libs.versions.toml에서 MySQL, MongoDB, Redis 의존성 정의 제거 - 미사용 Exception 클래스 2개 삭제 (Redi…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [893d1f9]; linked issues: 0 [].
- file evidence: 12 [MODIFIED gradle/libs.versions.toml +2/-13; DELETED module-common/src/main/kotlin/maple/expectation/error/exception/CompensationSyncException.kt +0/-23; DELETED module-common/src/main/kotlin/maple/expectation/error/exception/RedisScriptExecutionException.kt +0/-27].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #598 — fix: add KotlinModule for Kotlin data class serialization
- author/state/dates: zbnerd | MERGED | created 2026-03-12T06:14:12Z | closed 2026-03-12T06:14:22Z | merged yes at 2026-03-12T06:14:22Z | merge e1f4842ba4f9f6d635388a70fcda25ecf017bfd9.
- body: ## Summary - Add `jackson-module-kotlin` and `kotlin-reflect` dependencies to `module-infra` - Register `KotlinModule` and `JavaTi…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [2284892]; linked issues: 0 [].
- file evidence: 21 [MODIFIED module-app/build.gradle +1/-0; MODIFIED module-app/src/main/java/maple/expectation/application/service/character/GameCharacterFacade.java +27/-87; ADDED module-app/src/main/java/maple/expectation/application/usecase/AdminPortAdapter.java +65/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #600 — Merge develop into master
- author/state/dates: zbnerd | MERGED | created 2026-03-15T07:34:51Z | closed 2026-03-15T07:35:02Z | merged yes at 2026-03-15T07:35:02Z | merge 922851c7a643a09837a330a5291d75e6fbc30c39.
- body: ## Summary - Kotlin data class serialization을 위한 KotlinModule 추가 - MySQL/Redis/MongoDB legacy code 정리 (PostgreSQL migration 완료) - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 14 [c4edd59, 255c0c9, cf00576]; linked issues: 0 [].
- file evidence: 239 [MODIFIED .github/workflows/ci.yml +3/-50; MODIFIED .github/workflows/gradle.yml +1/-11; MODIFIED .github/workflows/nightly-chaos.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #601 — chore: remove nightly-chaos workflow for redesign
- author/state/dates: zbnerd | MERGED | created 2026-03-15T07:45:41Z | closed 2026-03-15T07:45:49Z | merged yes at 2026-03-15T07:45:49Z | merge ccc5a0179d1208898dae8b6b90ac0010a47a8b55.
- body: ## Summary - nightly-chaos.yml 워크플로우 삭제 (나중에 처음부터 다시 설계 예정) ## Test plan - [x] 파일 삭제 확인 🤖 Generated with [Claude Code](https://cla…
- reviews/discussion: 0 []; 0.
- commits: 1 [58d0b5d]; linked issues: 0 [].
- file evidence: 1 [DELETED .github/workflows/nightly-chaos.yml +0/-232].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #602 — feat: Core Unit Test Template for Pure Domain Logic Testing (#508)
- author/state/dates: zbnerd | MERGED | created 2026-03-15T08:22:50Z | closed 2026-03-15T08:46:56Z | merged yes at 2026-03-15T08:46:56Z | merge 52bcc2d5cab93d159e0fe1253d06ba2a8b48a0b6.
- body: ## Summary Phase 1: Core Unit Test Template - Creates a standardized testing template for pure domain logic in module-core. ## Cha…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 2 [b9c546a, 94f0d7a]; linked issues: 0 [].
- file evidence: 4 [ADDED docs/05_Reports/event-architecture-verification.md +145/-0; ADDED module-core/src/test/kotlin/maple/expectation/test/CoreUnitTestTemplate.kt +159/-0; ADDED module-core/src/test/kotlin/maple/expectation/test/CoreUnitTestTemplateExample.kt +258/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #603 — feat(test): Implement standardized test templates for all architecture layers (#508-#512)
- author/state/dates: zbnerd | MERGED | created 2026-03-15T08:45:14Z | closed 2026-03-15T08:56:25Z | merged yes at 2026-03-15T08:56:25Z | merge eef957fb3a206a48f4c06f5a59bea33fca1a5285.
- body: ## Summary Implements comprehensive test template system addressing 5 interconnected GitHub issues for creating standardized test …
- reviews/discussion: 0 []; 0.
- commits: 3 [b9c546a, 51d1328, cd1832f]; linked issues: 0 [].
- file evidence: 22 [MODIFIED docs/03_Technical_Guides/testing-guide.md +259/-0; ADDED module-app/src/test/kotlin/maple/expectation/smoke/P0CharacterSmokeTest.kt +39/-0; ADDED module-app/src/test/kotlin/maple/expectation/smoke/P0ExpectationSmokeTest.kt +59/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #604 — docs: Add observability standards and module-common API manifest
- author/state/dates: zbnerd | MERGED | created 2026-03-15T10:04:14Z | closed 2026-03-15T10:07:30Z | merged yes at 2026-03-15T10:07:30Z | merge fc9873d113043524df773d1e873c8cb885a7838a.
- body: ## Summary - Create module-common API manifest documenting 62 public APIs with stability levels - Add ADR-025 for observability me…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [0aa8def]; linked issues: 0 [].
- file evidence: 3 [ADDED docs/03_Technical_Guides/module-common-api-manifest.md +874/-0; ADDED docs/adr/ADR-025-observability-metrics-rules.md +173/-0; ADDED docs/adr/ADR-026-observability-tracing-rules.md +179/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #605 — [P6-03] JWT 인증 (유지, Kotlin 변환)
- author/state/dates: zbnerd | MERGED | created 2026-03-15T10:33:44Z | closed 2026-03-15T10:37:00Z | merged yes at 2026-03-15T10:37:00Z | merge 3adc0aa64627373254b4b5c80b2225eed01a11ea.
- body: ## Summary - `AuthPortAdapter.java` → `AuthPortAdapter.kt` 변환 - `ApiKeyValidator.java` → `ApiKeyValidator.kt` 변환 - Kotlin 관용구 적용 (…
- reviews/discussion: 0 []; 0.
- commits: 1 [c84591e]; linked issues: 0 [].
- file evidence: 4 [DELETED module-app/src/main/java/maple/expectation/application/service/auth/ApiKeyValidator.java +0/-110; DELETED module-app/src/main/java/maple/expectation/application/usecase/AuthPortAdapter.java +0/-83; ADDED module-app/src/main/kotlin/maple/expectation/application/service/auth/ApiKeyValidator.kt +100/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #606 — feat(chaos): Add PostgreSQL chaos tests for PGMQ, Circuit Breaker, and Network (#567)
- author/state/dates: zbnerd | MERGED | created 2026-03-17T12:13:18Z | closed 2026-03-17T13:02:59Z | merged yes at 2026-03-17T13:02:58Z | merge 8066cd4547953bbb134070807078f9fdad2fe85a.
- body: ## Summary - Added 11 chaos tests for PostgreSQL resilience testing following the 5-Agent Council pattern - Removed 20+ legacy tes…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 22 [c4edd59, 255c0c9, cf00576]; linked issues: 0 [].
- file evidence: 88 [MODIFIED CLAUDE.md +3/-2; ADDED docs/03_Technical_Guides/module-common-api-manifest.md +874/-0; MODIFIED docs/03_Technical_Guides/testing-guide.md +259/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #607 — feat(test): PostgreSQL 통합 테스트 인프라 구축 (Issue #563)
- author/state/dates: zbnerd | MERGED | created 2026-03-18T14:12:59Z | closed 2026-03-18T14:13:54Z | merged yes at 2026-03-18T14:13:54Z | merge 04bd04fa5277feccac6342ce6358b17396d330f6.
- body: ## Summary - PostgreSQL + Testcontainers 기반 통합 테스트 인프라 구축 - Repository 통합 테스트 추가 (GameCharacter, Member, CharacterEquipment, Chara…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [f4b980c]; linked issues: 0 [].
- file evidence: 19 [ADDED docs/03_Technical_Guides/integration-testing-guide.md +987/-0; MODIFIED module-app/build.gradle +24/-4; MODIFIED module-app/src/main/java/maple/expectation/application/usecase/CharacterViewQueryPortAdapter.java +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #608 — feat(batch): Add micro-batching for GameCharacter and L2Cache queries (#588, #599)
- author/state/dates: zbnerd | MERGED | created 2026-03-19T06:54:27Z | closed 2026-03-19T07:00:08Z | merged yes at 2026-03-19T07:00:08Z | merge f4fcea5ba8b7868f3a728b9fb0d20b5a20469d30.
- body: ## Summary - Integrate `AdaptiveMicroBatchUserService` framework with `GameCharacter` and `PostgresL2Cache` repositories - Add bat…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [e0d3c50]; linked issues: 0 [].
- file evidence: 9 [MODIFIED module-infra/src/main/kotlin/maple/expectation/domain/repository/GameCharacterRepository.kt +10/-0; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/GameCharacterMicroBatchAdapter.kt +52/-0; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/batch/L2CacheMicroBatchAdapter.kt +72/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #609 — fix(cache): Fix LISTEN/NOTIFY cache invalidation (#278)
- author/state/dates: zbnerd | MERGED | created 2026-03-20T03:03:13Z | closed 2026-03-20T03:03:21Z | merged yes at 2026-03-20T03:03:21Z | merge 45307a46b98007681a273f570f08b5b054ad1be2.
- body: ## Summary - Add missing `doPublish()` method in `TransactionalCacheInvalidationListener` - Fix channel mismatch: Publisher와 Subsc…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [8c82419]; linked issues: 0 [].
- file evidence: 25 [ADDED .github/workflows/load-test.yml +396/-0; MODIFIED build.gradle +84/-0; MODIFIED docker/prometheus/prometheus.yml +3/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #612 — feat: 300k character bulk loading with bounded parallelism (#611)
- author/state/dates: zbnerd | MERGED | created 2026-03-22T05:23:59Z | closed 2026-03-23T09:05:26Z | merged yes at 2026-03-23T09:05:26Z | merge 57616f5cff96278b3cb4e48f73dfcdf40593566a.
- body: ## Summary Implemented bulk character loading system for realistic load test (Issue #610). ## What was implemented - BulkLoaderSer…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [561f807, 98f4640, a52794d]; linked issues: 0 [].
- file evidence: 18 [MODIFIED CLAUDE.md +49/-0; ADDED characters.csv +11/-0; ADDED docs/adr/028-bulk-loading-300k-characters.md +412/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #613 — Vultr Seoul KR Environment Setup
- author/state/dates: zbnerd | MERGED | created 2026-03-23T12:10:10Z | closed 2026-03-23T12:10:20Z | merged yes at 2026-03-23T12:10:19Z | merge 0e0248a1abb6e786be6f7f93cc9c8a06fdb738ad.
- body: # Vultr Seoul KR Environment Setup ## Summary Configured MapleExpectation application for Vultr Seoul KR infrastructure with remot…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [9dff8a5]; linked issues: 0 [].
- file evidence: 3 [MODIFIED CLAUDE.md +17/-196; MODIFIED module-app/src/main/resources/application-local.yml +1/-1; ADDED module-app/src/main/resources/application-vultr.yml +107/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #614 — perf(cache): fix ClassCastException and optimize bulk loading performance
- author/state/dates: zbnerd | MERGED | created 2026-03-23T14:12:41Z | closed 2026-03-23T14:12:48Z | merged yes at 2026-03-23T14:12:48Z | merge e91501d6a5d6922e503d16bbf579ae51f0e1839e.
- body: ## Summary Fixed ClassCastException in PostgreSQL L2 cache deserialization and optimized bulk loading performance by implementing …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 3 / ## CPU 병목 분석 **CPU 사용량 측정 결과:** - 메인 워커 스레드: 727.3% (8코….
- commits: 35 [c4edd59, 255c0c9, cf00576]; linked issues: 0 [].
- file evidence: 83 [ADDED .github/workflows/load-test.yml +396/-0; MODIFIED CLAUDE.md +44/-174; MODIFIED build.gradle +84/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #615 — refactor(core): replace BigDecimal with Double + Kahan summation
- author/state/dates: zbnerd | MERGED | created 2026-03-23T15:10:57Z | closed 2026-03-23T15:11:09Z | merged yes at 2026-03-23T15:11:09Z | merge d07f6253cafb18a8fc56c0029401d218a05557a4.
- body: ## Summary Replace arbitrary-precision BigDecimal arithmetic with primitive double and Kahan compensated summation to minimize flo…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [921ebc3, 9fd488d]; linked issues: 0 [].
- file evidence: 28 [DELETED module-app/checkpoint.json +0/-0; MODIFIED module-app/failed.csv +717/-1700; MODIFIED module-app/src/main/java/maple/expectation/application/service/StarforceApplicationService.java +19/-13].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #616 — fix(bulk): stabilize write path by fixing timeout, lock wait, and PostgreSQL dialect
- author/state/dates: zbnerd | MERGED | created 2026-03-24T03:23:10Z | closed 2026-03-24T03:25:38Z | merged yes at 2026-03-24T03:25:38Z | merge 150978ddf87f5b8219eb34591b448bf48e0152ab.
- body: Three-phase fix to unblock bulk write operations that were failing due to timeout mismatch, aggressive distributed lock policy, an…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ec0e04e]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/EquipmentExpectationServiceV4.java +5/-3; MODIFIED module-app/src/main/java/maple/expectation/scheduler/ExpectationBatchWriteScheduler.java +31/-29; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/config/NexonApiProperties.kt +24/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #618 — feat: Production-ready admission control & micro-batching (Issue #617)
- author/state/dates: zbnerd | MERGED | created 2026-03-25T10:24:54Z | closed 2026-03-25T10:25:10Z | merged yes at 2026-03-25T10:25:10Z | merge 0264e5f77d882a8569355d642284df597fdcb580.
- body: ## 🔥 Production-Ready Patches for Issue #617 This PR applies all production-ready patches to transform the system from "fake bound…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [6c2ff01]; linked issues: 0 [].
- file evidence: 14 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +64/-3; MODIFIED module-app/src/main/resources/application-prod.yml +43/-0; MODIFIED module-app/src/main/resources/application.yml +48/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #619 — fix(executor): use taskExecutor instead of ForkJoinPool for CompletableFuture ops
- author/state/dates: zbnerd | MERGED | created 2026-03-25T13:42:55Z | closed 2026-03-25T13:43:03Z | merged yes at 2026-03-25T13:43:03Z | merge e8afc92dfa99efa45d2d0b5ff12823278041cf5d.
- body: ## Summary Fixed ForkJoinPool.commonPool() usage in CompletableFuture operations that was bypassing Spring's ThreadPoolTaskExecuto…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [0672a52]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/application/service/expectation/cache/ExpectationCacheCoordinator.java +1/-0; MODIFIED module-app/src/main/resources/application-local.yml +36/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infrastructure/admission/GlobalAdmissionControl.kt +56/-4].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #453 — refactor: ADR-003 OutboxScheduler 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T16:51:57Z | closed 2026-02-28T16:51:59Z | merged yes at 2026-02-28T16:51:59Z | merge 0c5ac6835c01b3377a8f7a699da8138ba077f1e3.
- body: ## 관련 이슈 #424 ## 개요 OutboxScheduler가 Port 인터페이스를 사용하도록 리팩토링 ## 작업 내용 - [x] OutboxProcessorPort 인터페이스 추가 - [x] OutboxMetricsPort 인터…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [2f8b84d]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/OutboxScheduler.java +4/-4; MODIFIED module-app/src/main/java/maple/expectation/service/v2/donation/outbox/OutboxMetrics.java +5/-1; MODIFIED module-app/src/main/java/maple/expectation/service/v2/donation/outbox/OutboxProcessor.java +4/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #454 — refactor: ADR-003 NexonApiOutboxScheduler 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T17:19:45Z | closed 2026-02-28T20:33:05Z | merged yes at 2026-02-28T20:33:05Z | merge 2771c37a0b36a5dc0a7374034f4927824f614914.
- body: ## 개요 NexonApiOutboxScheduler가 Port 인터페이스를 사용하도록 리팩토링하여 의존성 역전 원칙(DIP)을 준수합니다. ## 작업 내용 - [x] NexonApiOutboxProcessorPort 인터페이스 생성…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [81188fc]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/NexonApiOutboxScheduler.java +5/-5; MODIFIED module-app/src/main/java/maple/expectation/scheduler/NexonDataCollectionScheduler.java +8/-8; MODIFIED module-app/src/main/java/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java +5/-5].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #455 — refactor: ADR-003 PopularCharacterWarmupScheduler 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T17:44:54Z | closed 2026-02-28T17:45:01Z | merged yes at 2026-02-28T17:45:01Z | merge 8b07dc4e4d2ba6f4de9f94b93c64e5cf0c1d675c.
- body: ## 관련 이슈 #454 ## 개요 PopularCharacterWarmupScheduler를 헥사고날 아키텍처 패턴으로 리팩토링하여 의존성 방향을 역전시키고 DIP를 준수합니다. ## 작업 내용 - [x] PopularCharact…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [81188fc, cfdc9df]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/NexonApiOutboxScheduler.java +5/-5; MODIFIED module-app/src/main/java/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java +5/-5; MODIFIED module-app/src/main/java/maple/expectation/service/v2/outbox/NexonApiOutboxMetrics.java +3/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #456 — refactor: ADR-003 NexonDataCollector 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T18:09:28Z | closed 2026-02-28T18:10:49Z | merged yes at 2026-02-28T18:10:49Z | merge 6c6014dea2a5da20cd3316a7fa88f20e1e943bdb.
- body: ## 관련 이슈 ADR-003 헥사고날 아키텍처 리팩토링 ## 개요 NexonDataCollectorPort 인터페이스를 생성하여 DIP(Dependency Inversion Principle)를 준수하는 헥사고날 아키텍처로 리팩토링…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [0c45710, dd6a850]; linked issues: 0 [].
- file evidence: 3 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/NexonDataCollectionScheduler.java +4/-4; MODIFIED module-app/src/main/java/maple/expectation/service/ingestion/NexonDataCollector.java +6/-4; ADDED module-core/src/main/kotlin/maple/expectation/core/port/out/NexonDataCollectorPort.kt +52/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #457 — test: ADR-003 PopularCharacterWarmupScheduler Port 기반 테스트 수정
- author/state/dates: zbnerd | MERGED | created 2026-02-28T18:27:27Z | closed 2026-02-28T18:41:51Z | merged yes at 2026-02-28T18:41:51Z | merge 5873b0779ac9431d3c16562c883bc3c3a3cf8bdf.
- body: ## 관련 이슈 #424 (ADR-003 Hexagonal Architecture) ## 개요 PopularCharacterWarmupScheduler 단위 테스트가 Port 인터페이스를 사용하도록 수정 ## 작업 내용 - [x] E…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [9c51c40]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/test/java/maple/expectation/scheduler/PopularCharacterWarmupSchedulerTest.java +28/-19].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #458 — refactor: ADR-004 module-core 도메인 이관 (Phase 1)
- author/state/dates: zbnerd | MERGED | created 2026-02-28T19:58:32Z | closed 2026-02-28T19:58:43Z | merged yes at 2026-02-28T19:58:43Z | merge 1471ead0058a6e953dca443f4cd9837ea373280c.
- body: ## 관련 이슈 #415, #416, #417, #418 ## 개요 ADR-004에 따라 순수 비즈니스 로직을 module-core로 이관 ## 작업 내용 - [x] Calculator 도메인 Port 및 Decorator 이관 - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [a0a359b]; linked issues: 0 [].
- file evidence: 33 [ADDED docs/01_ADR/ADR-004-starforce-migration-analysis.md +180/-0; ADDED docs/adr/ADR-004-calculator-migration-summary.md +152/-0; ADDED docs/adr/ADR-004-module-core-migration-cube-report.md +156/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #459 — refactor: ADR-004 Policy 도메인 이관 (Issue #419)
- author/state/dates: zbnerd | MERGED | created 2026-02-28T20:14:44Z | closed 2026-02-28T20:14:51Z | merged yes at 2026-02-28T20:14:51Z | merge 91193cca4efc21ba51dafc4c233910a30b1af274.
- body: ## 관련 이슈 #419 ## 개요 ADR-004에 따라 Policy 도메인을 module-core로 이관 ## 작업 내용 - [x] CostCalculationStrategy 인터페이스 이관 (Kotlin fun interface)…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [96f2c85]; linked issues: 0 [].
- file evidence: 8 [MODIFIED docs/adr/ADR-004-module-core-migration.md +28/-0; ADDED docs/adr/facade-migration-analysis.md +232/-0; MODIFIED module-app/src/main/java/maple/expectation/service/v2/policy/CubeCostPolicy.java +16/-3].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #460 — docs: ADR-005 모듈 의존성 그래프 및 이관 전략 수립
- author/state/dates: zbnerd | MERGED | created 2026-02-28T20:29:08Z | closed 2026-02-28T20:29:16Z | merged yes at 2026-02-28T20:29:16Z | merge 1f6d0214cd97233cdc47e14be89c22fd22ec6467.
- body: ## 관련 이슈 #410, #411 ## 개요 ADR-005 모듈 의존성 그래프 및 이관 전략 수립 ## 작업 내용 - [x] 의존성 그래프 정의 (web → app → core ← infra) - [x] 모듈 역할 정의 (core,…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [603c99c]; linked issues: 0 [].
- file evidence: 5 [ADDED docs/05_Reports/module-migration-progress-report.md +130/-0; ADDED docs/adr/ADR-005-module-dependency-strategy.md +149/-0; MODIFIED module-app/src/main/java/maple/expectation/dto/v4/EquipmentExpectationResponseV4.java +3/-14].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #461 — test: ADR-005 ArchUnit 모듈 의존성 규칙 검증 테스트 추가 (#410)
- author/state/dates: zbnerd | MERGED | created 2026-02-28T20:33:20Z | closed 2026-02-28T20:33:32Z | merged yes at 2026-02-28T20:33:32Z | merge 1c15ddc1c032d5fc6fd029fdfe2751e2486d18bf.
- body: ## 관련 이슈 #410 ## 개요 ADR-005 의존성 그래프 규칙을 검증하는 ArchUnit 테스트 추가 ## 추가된 테스트 - [x] core should not depend on infra or web or app - [x] …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [820dc73]; linked issues: 0 [].
- file evidence: 1 [ADDED module-web/src/test/kotlin/maple/expectation/web/arch/ModuleDependencyTest.kt +108/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #462 — test: ADR-005 CubeCostPolicyTest PolicyPort 호환성 수정 (#435)
- author/state/dates: zbnerd | MERGED | created 2026-02-28T21:14:09Z | closed 2026-02-28T21:14:19Z | merged yes at 2026-02-28T21:14:19Z | merge 89a1eac6269a86f9fb474cd74905ae55ee5f67ec.
- body: ## 관련 이슈 #435 ## 개요 module-common 모듈 검증 및 CubeCostPolicyTest 호환성 수정 ## 작업 내용 - [x] module-common Spring 의존성 검증 (0개 확인) - [x] Error…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ae5173f]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/test/java/maple/expectation/service/v2/policy/CubeCostPolicyTest.java +7/-6].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #463 — chore: stop hook에 세션 리포트 자동 생성 추가
- author/state/dates: zbnerd | MERGED | created 2026-02-28T21:22:51Z | closed 2026-02-28T21:23:01Z | merged yes at 2026-02-28T21:23:01Z | merge 2db44c50f54d33a35f8b67117c7271f8472fb2a1.
- body: ## 개요 stop hook에 세션 종료 시 자동으로 마크다운 리포트를 생성하는 기능 추가 ## 작업 내용 - [x] stop-validation.sh에 리포트 생성 로직 추가 - [x] 세션 종료 시 `docs/05_Reports/…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [dd6f8d3]; linked issues: 0 [].
- file evidence: 1 [MODIFIED .claude/hooks/stop-validation.sh +67/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #464 — feat: ADR-005 Web Controller Migration - Port 추출 1단계
- author/state/dates: zbnerd | MERGED | created 2026-02-28T22:09:26Z | closed 2026-02-28T22:13:46Z | merged yes at 2026-02-28T22:13:46Z | merge 3d0911f62a62a776bc6a9281d115dfc8ce71e405.
- body: ## 관련 이슈 #411, #412, #413 ## 개요 ADR-005 순환 의존성 해결을 위한 Port 추출 작업 ## 작업 내용 - [x] GameCharacterPort 인터페이스 및 어댑터 추가 - [x] AuthPort 인터…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [e5cb335, 30fa34e]; linked issues: 0 [].
- file evidence: 8 [ADDED module-app/src/main/java/maple/expectation/adapter/in/AuthPortAdapter.java +76/-0; ADDED module-app/src/main/java/maple/expectation/adapter/in/GameCharacterPortAdapter.java +64/-0; ADDED module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthCommand.kt +27/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #465 — refactor: module-infra 및 module-core 구조 정리
- author/state/dates: zbnerd | MERGED | created 2026-02-28T22:29:53Z | closed 2026-02-28T22:30:30Z | merged yes at 2026-02-28T22:30:30Z | merge 14a7500487b0716f04fd8263e8c85dd95a6cfe3d.
- body: ## 관련 이슈 - ADR-005 Web Controller Migration 지원 작업 ## 개요 module-infra와 module-core의 구조 정리 및 Kotlin 마이그레이션 ## 작업 내용 ### module-infra…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [295db2c]; linked issues: 0 [].
- file evidence: 27 [MODIFIED module-app/src/main/java/maple/expectation/adapter/in/AuthPortAdapter.java +2/-2; MODIFIED module-app/src/main/java/maple/expectation/adapter/in/GameCharacterPortAdapter.java +2/-2; DELETED module-core/src/main/java/maple/expectation/application/port/package-info.java +0/-16].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #466 — feat: ADR-005 DonationPort 인터페이스 및 어댑터 추가
- author/state/dates: zbnerd | MERGED | created 2026-02-28T22:37:54Z | closed 2026-02-28T22:40:35Z | merged yes at 2026-02-28T22:40:35Z | merge 4741b50f07e3286bcce9d50e161035f0032c2aa4.
- body: ## 관련 이슈 - ADR-005 Web Controller Migration ## 개요 DonationPort 인터페이스 및 어댑터 추가 ## 작업 내용 - [x] DonationPort 인터페이스 정의 (module-core) -…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [b798eb1]; linked issues: 0 [].
- file evidence: 3 [ADDED module-app/src/main/java/maple/expectation/adapter/in/DonationPortAdapter.java +44/-0; ADDED module-core/src/main/kotlin/maple/expectation/core/port/inbound/DonationCommand.kt +16/-0; ADDED module-core/src/main/kotlin/maple/expectation/core/port/inbound/DonationPort.kt +31/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #467 — feat: ADR-005 AuthController를 module-web으로 이관
- author/state/dates: zbnerd | MERGED | created 2026-02-28T23:14:50Z | closed 2026-02-28T23:15:32Z | merged yes at 2026-02-28T23:15:32Z | merge f05bd93b6572d7b78b06d1d6710b95781732b8cb.
- body: ## 관련 이슈 ADR-005 Web Controller Migration ## 개요 AuthController를 module-app에서 module-web으로 이관하고, Hexagonal Architecture에 따라 Port 인터…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [362f3bf]; linked issues: 0 [].
- file evidence: 11 [MODIFIED docs/adr/003-hexagonal-architecture-adoption.md +43/-13; MODIFIED docs/adr/ADR-004-module-core-migration.md +1/-1; MODIFIED docs/adr/ADR-005-module-dependency-strategy.md +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #468 — refactor: ADR-005 module-app에서 DonationController 제거
- author/state/dates: zbnerd | MERGED | created 2026-02-28T23:28:55Z | closed 2026-02-28T23:30:48Z | merged yes at 2026-02-28T23:30:48Z | merge 48ea13ef851c0ab05bc55e6f303957e3f1ed5042.
- body: ## 관련 이슈 ADR-005 Web Controller Migration ## 개요 module-app에서 이미 module-web으로 이관된 DonationController 및 DTO 제거 ## 작업 내용 - [x] Donati…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [c7ccd59, 50cc881]; linked issues: 0 [].
- file evidence: 14 [ADDED docs/05_Reports/PR_349-466_ADR_Analysis_Integrated_Report.md +305/-0; ADDED docs/05_Reports/PR_ADR_Analysis_349-365.md +296/-0; ADDED docs/05_Reports/PR_ADR_Analysis_390-396.md +261/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #469 — docs: PR 349-466 ADR 분석 및 신규 ADR 추가
- author/state/dates: zbnerd | MERGED | created 2026-02-28T23:32:56Z | closed 2026-02-28T23:33:06Z | merged yes at 2026-02-28T23:33:06Z | merge 455dbf519e83cd4bfaa05d8b32692b1d9057a75f.
- body: ## 관련 이슈 N/A (문서화 작업) ## 개요 PR #349-466 (51개 PR)에 대한 ADR 분석 및 신규 ADR 추가 ## 작업 내용 - [x] ADR-006: Java-to-Kotlin 마이그레이션 전략 문서화 - [x]…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [c7ccd59, 50cc881]; linked issues: 0 [].
- file evidence: 14 [ADDED docs/05_Reports/PR_349-466_ADR_Analysis_Integrated_Report.md +305/-0; ADDED docs/05_Reports/PR_ADR_Analysis_349-365.md +296/-0; ADDED docs/05_Reports/PR_ADR_Analysis_390-396.md +261/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #470 — feat: ADR-005 Port 추출 및 Controller 이관 (Admin, Alert)
- author/state/dates: zbnerd | MERGED | created 2026-02-28T23:55:41Z | closed 2026-02-28T23:55:43Z | merged yes at 2026-02-28T23:55:43Z | merge d9617cfac1874ce89094f7c65fdd7838175240f7.
- body: ## 완료한 Controller - AdminController → module-web (AdminPort 사용) - AlertTestController → module-web (AlertPort 사용) ## 새로 만든 Port - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [6407e10]; linked issues: 0 [].
- file evidence: 10 [ADDED docs/05_Reports/session-report-2026-03-01-003553.md +49/-0; ADDED module-app/src/main/java/maple/expectation/adapter/in/AdminPortAdapter.java +48/-0; ADDED module-app/src/main/java/maple/expectation/adapter/in/AlertPortAdapter.java +25/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #471 — fix: module-web DTO 패키지 정리
- author/state/dates: zbnerd | MERGED | created 2026-03-01T00:24:24Z | closed 2026-03-01T00:24:33Z | merged yes at 2026-03-01T00:24:33Z | merge 285288b63825823cae99cd6d29c8cf182dbe8ad4.
- body: ## 관련 이슈 #349 (ADR-005 Hexagonal Architecture) ## 개요 module-web DTO 패키지 중복 파일 정리 ## 작업 내용 - [x] web/dto/auth 중복 파일 삭제 (controller/…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [637d65f]; linked issues: 0 [].
- file evidence: 3 [MODIFIED module-web/build.gradle +3/-0; DELETED module-web/src/main/kotlin/maple/expectation/web/dto/auth/LoginResponse.kt +0/-74; DELETED module-web/src/main/kotlin/maple/expectation/web/dto/auth/TokenResponse.kt +0/-45].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #472 — feat: ADR-005 GameCharacterControllerV5 Port 추출 및 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T00:39:12Z | closed 2026-03-01T00:39:22Z | merged yes at 2026-03-01T00:39:22Z | merge daa301f89df80a2546ff277333e884174235d42b.
- body: ## 관련 이슈 #349 (ADR-005 Hexagonal Architecture) ## 개요 GameCharacterControllerV5 Port 추출 및 module-web 이관 ## 작업 내용 - [x] CharacterVie…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [657ab8f]; linked issues: 0 [].
- file evidence: 6 [ADDED module-app/src/main/java/maple/expectation/adapter/in/CalculationQueuePortAdapter.java +28/-0; ADDED module-app/src/main/java/maple/expectation/adapter/in/CharacterViewQueryPortAdapter.java +30/-0; ADDED module-core/src/main/kotlin/maple/expectation/core/port/inbound/CalculationQueuePort.kt +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #473 — feat: ADR-005 GameCharacterControllerV4 Port 추출 및 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T00:50:09Z | closed 2026-03-01T00:50:19Z | merged yes at 2026-03-01T00:50:19Z | merge f4db88b8a73cd9013d79e976a2ec5b71129fdd74.
- body: ## 관련 이슈 #349 (ADR-005 Hexagonal Architecture) ## 개요 GameCharacterControllerV4 Port 추출 및 module-web 이관 ## 작업 내용 - [x] ExpectationV…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c99efbf]; linked issues: 0 [].
- file evidence: 6 [ADDED module-app/src/main/java/maple/expectation/adapter/in/ExpectationV4PortAdapter.java +39/-0; ADDED module-app/src/main/java/maple/expectation/adapter/in/PopularCharacterTrackerPortAdapter.java +30/-0; DELETED module-app/src/main/java/maple/expectation/controller/GameCharacterControllerV4.java +0/-209].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #474 — feat: ADR-005 GameCharacterControllerV1 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T00:54:42Z | closed 2026-03-01T00:54:51Z | merged yes at 2026-03-01T00:54:51Z | merge bfda7a1605e0e73bb411f3475dcbcdc916c2253c.
- body: ## 관련 이슈 #349 (ADR-005 Hexagonal Architecture) ## 개요 GameCharacterControllerV1 module-web 이관 ## 작업 내용 - [x] GameCharacterControlle…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [2611de1]; linked issues: 0 [].
- file evidence: 2 [RENAMED module-web/src/main/java/maple/expectation/controller/v1/GameCharacterControllerV1.java +11/-13; ADDED module-web/src/main/kotlin/maple/expectation/web/dto/response/CharacterResponse.kt +43/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #475 — feat: ADR-005 DlqAdminController 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T01:43:08Z | closed 2026-03-01T01:53:47Z | merged yes at 2026-03-01T01:53:47Z | merge 67153c61b31c47d9352df6bdefd54876d2aedd20.
- body: ## 관련 이슈 #475 ## 개요 DlqAdminController를 module-app에서 module-web으로 이관하고 DlqPort 인터페이스만 의존하도록 변경 ## 작업 내용 - [x] DlqPort에 `findAllByC…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [59cbe8c]; linked issues: 0 [].
- file evidence: 6 [MODIFIED module-app/src/main/java/maple/expectation/adapter/in/DlqPortAdapter.java +7/-0; DELETED module-app/src/main/java/maple/expectation/controller/DlqAdminController.java +0/-185; MODIFIED module-core/src/main/kotlin/maple/expectation/core/port/inbound/DlqPort.kt +9/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #476 — docs: ADR-005 상태를 Completed로 업데이트
- author/state/dates: zbnerd | MERGED | created 2026-03-01T01:59:16Z | closed 2026-03-01T01:59:26Z | merged yes at 2026-03-01T01:59:26Z | merge bb35660c4f57a685271d0de525f1d4b0c7d9817e.
- body: ## 개요 ADR-005 모듈 의존성 그래프 및 이관 전략 문서를 완료 상태로 업데이트 ## 변경 사항 - 상태: In Progress → Completed - 모든 Phase 완료 표시 - 이관 완료된 Controller 목록 추가…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [cbe95a6]; linked issues: 0 [].
- file evidence: 1 [MODIFIED docs/adr/ADR-005-module-dependency-strategy.md +28/-8].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #477 — feat: ADR-005 WebConfig 이관 (#413)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:11:15Z | closed 2026-03-01T02:11:24Z | merged yes at 2026-03-01T02:11:24Z | merge 6b721c1443fa1715b63b5bd9c4b1154ecf037890.
- body: ## 관련 이슈 #413 ## 개요 ADR-005 Phase 2 Web 이관 작업의 일환으로 WebConfig, CorsProperties, OpenApiConfig를 module-app에서 module-web으로 이관 ## 작업 내…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [7a55cd8]; linked issues: 0 [].
- file evidence: 4 [DELETED module-app/src/main/java/maple/expectation/config/CorsProperties.java +0/-84; ADDED module-web/src/main/java/maple/expectation/web/config/CorsProperties.java +44/-0; RENAMED module-web/src/main/java/maple/expectation/web/config/OpenApiConfig.java +6/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #478 — refactor: 중복 BufferRecoveryScheduler 제거 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:36:05Z | closed 2026-03-01T02:36:15Z | merged yes at 2026-03-01T02:36:15Z | merge 8f76f93b6fb67078b3f09290ecc952fe525636ff.
- body: ## 관련 이슈 #424 ## 개요 중복된 BufferRecoveryScheduler 제거 ## 작업 내용 - [x] module-app의 Java 버전 삭제 - [x] module-infra의 Kotlin 버전 유지 (더 완전한 조…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [5b530b5]; linked issues: 0 [].
- file evidence: 1 [DELETED module-app/src/main/java/maple/expectation/scheduler/BufferRecoveryScheduler.java +0/-201].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #479 — feat: ADR-005 OutboxScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:41:46Z | closed 2026-03-01T02:41:55Z | merged yes at 2026-03-01T02:41:55Z | merge 7fc87084ada672907264964460f377ec5aa1d244.
- body: ## 관련 이슈 #424 ## 개요 OutboxScheduler를 module-app에서 module-infra로 이관 ## 작업 내용 - [x] Java → Kotlin 변환 - [x] 패키지 변경: scheduler → infra…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [196333a]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/OutboxScheduler.java +0/-119; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/OutboxScheduler.kt +83/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #480 — feat: ADR-005 NexonApiOutboxScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:44:49Z | closed 2026-03-01T02:44:51Z | merged yes at 2026-03-01T02:44:51Z | merge 933f788af6112f0e878102e8ab78b5cb2c10cd50.
- body: Java → Kotlin 변환, module-infra로 이관
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [1627b1f]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/NexonApiOutboxScheduler.java +0/-120; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/NexonApiOutboxScheduler.kt +67/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #481 — feat: ADR-005 LikeSyncScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:49:23Z | closed 2026-03-01T02:49:25Z | merged yes at 2026-03-01T02:49:25Z | merge 0979c84dd9f280d7a82cd72ee30d69d5fc260a95.
- body: Java → Kotlin 변환, module-infra로 이관
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [94ae81b]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +0/-200; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/LikeSyncScheduler.kt +143/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #482 — feat: ADR-005 NexonDataCollectionScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:54:25Z | closed 2026-03-01T02:54:26Z | merged yes at 2026-03-01T02:54:26Z | merge 3ce4461c33acf668b7dfab38be5f90572726e474.
- body: Java → Kotlin 변환, module-infra로 이관
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [1a453c1]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/NexonDataCollectionScheduler.java +0/-145; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/NexonDataCollectionScheduler.kt +97/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #483 — feat: ADR-005 PopularCharacterWarmupScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T02:57:39Z | closed 2026-03-01T02:57:40Z | merged yes at 2026-03-01T02:57:40Z | merge 40ff4128d38c3ca25e6621066054de9bab6613e0.
- body: Java → Kotlin 변환, module-infra로 이관
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [f99c3bc]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/PopularCharacterWarmupScheduler.java +0/-243; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/PopularCharacterWarmupScheduler.kt +165/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #484 — feat: ADR-005 ExpectationCalculationScheduler 이관 (#424)
- author/state/dates: zbnerd | MERGED | created 2026-03-01T03:00:45Z | closed 2026-03-01T03:00:47Z | merged yes at 2026-03-01T03:00:47Z | merge b197d949f0de905107d65c075e6ccb5ba4746618.
- body: Java → Kotlin 변환, module-infra로 이관
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [5ae6acb]; linked issues: 0 [].
- file evidence: 2 [DELETED module-app/src/main/java/maple/expectation/scheduler/ExpectationCalculationScheduler.java +0/-150; ADDED module-infra/src/main/kotlin/maple/expectation/infrastructure/scheduler/ExpectationCalculationScheduler.kt +95/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #485 — feat: ADR-005 ExpectationBufferPort 인터페이스 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-01T04:00:36Z | closed 2026-03-01T04:00:37Z | merged yes at 2026-03-01T04:00:37Z | merge 3fdd7433add43d7ef77cfe4ad15ebac82996e77a.
- body: Port 인터페이스를 통해 Buffer 결합도 낮춤. ExpectationWriteBackBuffer가 Port 구현.
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [b6a53ee]; linked issues: 0 [].
- file evidence: 2 [MODIFIED module-app/src/main/java/maple/expectation/service/v4/buffer/ExpectationWriteBackBuffer.java +2/-1; ADDED module-core/src/main/kotlin/maple/expectation/core/port/out/ExpectationBufferPort.kt +29/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #486 — feat: ADR-005 SystemMetricsPort 인터페이스 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-01T04:51:32Z | closed 2026-03-01T04:51:42Z | merged yes at 2026-03-01T04:51:42Z | merge 5b5154be12d317d70323eb0650d041acab7c4b5c.
- body: ## 관련 이슈 #441 ## 개요 MonitoringReportJob 이관을 위한 SystemMetricsPort 인터페이스 추출 ## 작업 내용 - [x] SystemMetricsPort 인터페이스 생성 (module-core/p…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [e74236a]; linked issues: 0 [].
- file evidence: 2 [MODIFIED module-app/src/main/java/maple/expectation/monitoring/context/SystemContextProvider.java +2/-1; ADDED module-core/src/main/kotlin/maple/expectation/core/port/out/SystemMetricsPort.kt +21/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #487 — feat: ADR-005 batch 패키지 module-infra 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T04:58:09Z | closed 2026-03-01T04:58:17Z | merged yes at 2026-03-01T04:58:17Z | merge 6f69de2a3bc65bb3db745b6e0d99e416bb6dfef8.
- body: ## 관련 이슈 #440 ## 개요 batch 패키지 5개 파일 module-infra로 이관 ## 작업 내용 - [x] BatchScheduler 이관 - [x] MonitoringReportJob 이관 (SystemMetricsP…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [9277c87]; linked issues: 0 [].
- file evidence: 5 [RENAMED module-infra/src/main/java/maple/expectation/infrastructure/batch/BatchScheduler.java +1/-1; RENAMED module-infra/src/main/java/maple/expectation/infrastructure/batch/MonitoringReportJob.java +6/-5; RENAMED module-infra/src/main/java/maple/expectation/infrastructure/batch/listener/BatchMetricsLogger.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #488 — feat: ADR-005 ExpectationWriteTask module-infra 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T05:09:53Z | closed 2026-03-01T05:10:03Z | merged yes at 2026-03-01T05:10:03Z | merge c82529b81c18492702b7c426314fcf63dbb83a3d.
- body: ## 관련 이슈 #441 ## 개요 ExpectationWriteTask를 module-infra로 이관하여 의존성 분리 ## 작업 내용 - [x] ExpectationWriteTask → module-infra/buffer 이동 -…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [6fb2aec]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/ExpectationBatchWriteScheduler.java +1/-1; MODIFIED module-app/src/main/java/maple/expectation/service/v4/buffer/ExpectationWriteBackBuffer.java +15/-1; MODIFIED module-app/src/main/kotlin/maple/expectation/scheduler/ExpectationBatchShutdownHandler.kt +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #525 — feat: ADR-005 monitoring-infra 이관 및 Port 인터페이스 추가
- author/state/dates: zbnerd | MERGED | created 2026-03-01T07:51:52Z | closed 2026-03-01T07:52:06Z | merged yes at 2026-03-01T07:52:06Z | merge 0757865aac25ac309116b62a5269ae856fec4b49.
- body: ## 관련 이슈 ADR-005 ## 개요 ADR-005 모니터링 인프라 이관 및 CI 컴파일 에러 수정 ## 작업 내용 - [x] MetricsQueryPort, AiAnalysisPort, AlertNotificationPort, …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [777be36]; linked issues: 0 [].
- file evidence: 21 [ADDED docs/adr/ADR-005-monitoring-infra-migration.md +129/-0; MODIFIED module-app/src/test/java/architecture/ArchTest.java +1/-0; MODIFIED module-app/src/test/java/architecture/SOLIDPrinciplesTest.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #526 — Merge develop into master
- author/state/dates: zbnerd | MERGED | created 2026-03-01T07:52:38Z | closed 2026-03-01T07:52:48Z | merged yes at 2026-03-01T07:52:48Z | merge 683a22f64b279edee0e2b2d2b0ffb1b5c3692ef4.
- body: ## 개요 develop 브랜치를 master로 머지 ## 포함된 변경사항 - ADR-005 monitoring-infra 이관 및 Port 인터페이스 추가 - CI 컴파일 에러 수정 및 테스트 import 업데이트 🤖 Generat…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 99 [3893bbc, 2890e5c, b292154]; linked issues: 0 [].
- file evidence: 1298 [ADDED .claude/hooks/pre-tool-use.sh +272/-0; ADDED .claude/hooks/stop-validation.sh +220/-0; ADDED .editorconfig +14/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #527 — fix: ADR-005 CursorPageResponse를 common에서 page 패키지로 이동
- author/state/dates: zbnerd | MERGED | created 2026-03-01T13:09:16Z | closed 2026-03-01T13:09:24Z | merged yes at 2026-03-01T13:09:24Z | merge dd7d7b3ea209bf84f43f28a9402aba7098175d0d.
- body: ## 관련 이슈 ADR-005 Monitoring Infra Migration ## 개요 ArchUnit 테스트 `common_should_not_depend_on_spring` 실패 해결 ## 작업 내용 - [x] CursorPag…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [31eb6ec]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/adapter/in/DlqPortAdapter.java +1/-1; RENAMED module-app/src/main/java/maple/expectation/controller/dto/page/CursorPageRequest.java +1/-1; ADDED module-app/src/main/java/maple/expectation/controller/dto/page/CursorPageResponse.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #528 — release: ADR-005 CursorPageResponse migration 및 최신 변경사항
- author/state/dates: zbnerd | MERGED | created 2026-03-01T13:18:33Z | closed 2026-03-01T13:18:43Z | merged yes at 2026-03-01T13:18:43Z | merge 503c2746a454bb45e7cead0b3f6b6d1099e02434.
- body: ## 개요 develop 브랜치의 최신 변경사항을 master에 반영 ## 포함된 PR들 - #527 fix: ADR-005 CursorPageResponse를 common에서 page 패키지로 이동 - #525 fix: ADR-00…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [dd7d7b3]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/adapter/in/DlqPortAdapter.java +1/-1; RENAMED module-app/src/main/java/maple/expectation/controller/dto/page/CursorPageRequest.java +1/-1; ADDED module-app/src/main/java/maple/expectation/controller/dto/page/CursorPageResponse.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #529 — refactor: module-infra, module-web Java → Kotlin 변환
- author/state/dates: zbnerd | MERGED | created 2026-03-01T16:15:33Z | closed 2026-03-01T20:07:37Z | merged yes at 2026-03-01T20:07:37Z | merge 789931584817707bfb384c48ae1442c2727ff50b.
- body: ## 관련 이슈 Java → Kotlin 마이그레이션 ## 개요 module-infra와 module-web의 Java 파일을 Kotlin으로 변환 ## 작업 내용 ### module-infra (6개 파일) - [x] BatchSc…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 7 [443a1a0, 02d6930, fffdbca]; linked issues: 0 [].
- file evidence: 125 [ADDED Note +0/-0; ADDED docs/05_Reports/session-report-2026-03-01-111627.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-01-141058.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #530 — refactor: ADR-008 Discord Alert Service를 module-infra로 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T20:30:15Z | closed 2026-03-02T22:52:27Z | merged yes at 2026-03-02T22:52:27Z | merge c28b8dd7779163ab9bf40b57f6825e8bfb79bf83.
- body: ## 관련 이슈 ADR-008 ## 개요 Discord Alert 관련 클래스를 `module-app/service/v2/alert/`에서 `module-infra/notification/discord/`로 이관 ## 작업 내용 - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ab65ddd]; linked issues: 0 [].
- file evidence: 11 [ADDED docs/05_Reports/session-report-2026-03-01-210241.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-01-210822.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-01-211526.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #531 — docs: ADR-010 Service Layer 모듈화 전략 수립
- author/state/dates: zbnerd | MERGED | created 2026-03-01T21:34:04Z | closed 2026-03-01T21:34:15Z | merged yes at 2026-03-01T21:34:15Z | merge 33387e23bb8576aac78030d4c8024d995dd69f05.
- body: ## 관련 이슈 - ADR-009 Cache 이관 작업의 연장선 ## 개요 service/v2 패키지의 다중 관심사 혼재 문제를 해결하기 위한 모듈화 전략 ADR 작성 ## 작업 내용 - [x] ADR-010 문서 작성 - [x] 기…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [af40961, e36dfbd]; linked issues: 0 [].
- file evidence: 13 [ADDED docs/05_Reports/session-report-2026-03-01-213359.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-01-220949.md +49/-0; ADDED docs/adr/009-cache-to-infra-migration.md +107/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #532 — refactor: ADR-011 EquipmentFingerprintGenerator를 module-infra로 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T21:59:22Z | closed 2026-03-02T22:52:38Z | merged yes at 2026-03-02T22:52:38Z | merge 36e6c1e5452844c0037c97d83fe5d3eb21823fef.
- body: ## 관련 이슈 ADR-011 ## 개요 Equipment 캐시 관련 인프라 코드를 `module-app/service`에서 `module-infra`로 이관하는 작업의 Phase 1 ## 작업 내용 - [x] ADR-011 문서 작…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [4b344f1]; linked issues: 0 [].
- file evidence: 4 [ADDED docs/05_Reports/session-report-2026-03-01-223747.md +49/-0; ADDED docs/adr/011-equipment-cache-infra-migration.md +93/-0; DELETED module-app/src/main/java/maple/expectation/service/v2/cache/EquipmentFingerprintGenerator.java +0/-95].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #533 — refactor: TotalExpectationCacheService를 module-infra로 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T22:10:55Z | closed 2026-03-02T22:52:46Z | merged yes at 2026-03-02T22:52:46Z | merge 93b0adbd92daf9301d3da45f3f0cb8c78e7e5b32.
- body: ## 관련 이슈 ADR-011 ## 개요 `TotalExpectationCacheService`를 `module-app/service`에서 `module-infra`로 이관 ## 작업 내용 - [x] `TotalExpectationC…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [c96f402]; linked issues: 0 [].
- file evidence: 4 [ADDED docs/05_Reports/session-report-2026-03-01-230159.md +49/-0; DELETED module-app/src/main/java/maple/expectation/service/v2/cache/TotalExpectationCacheService.java +0/-257; MODIFIED module-app/src/test/java/maple/expectation/service/v2/cache/TotalExpectationCacheServiceTest.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #534 — refactor: ADR-011 Cache/Worker module-infra 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-01T22:32:33Z | closed 2026-03-02T22:57:31Z | merged yes at 2026-03-02T22:57:31Z | merge 730dcef1ae7c905aef1fdf1c76b070b478268fa9.
- body: ## 관련 이슈 ADR-011 ## 개요 Cache 및 Worker 클래스를 module-app/service에서 module-infra로 이관하고 Kotlin으로 변환 ## 작업 내용 - [x] EquipmentDbWorker.ja…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 8 [e47a4cd, 3d30067, 09052e4]; linked issues: 0 [].
- file evidence: 12 [DELETED module-app/src/main/java/maple/expectation/service/v2/cache/AbstractTieredCacheService.java +0/-339; DELETED module-app/src/main/java/maple/expectation/service/v2/cache/EquipmentCacheService.java +0/-138; DELETED module-app/src/main/java/maple/expectation/service/v2/cache/EquipmentDataResolver.java +0/-199].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #535 — refactor: ADR-012 like 패키지 core/infra 분리 완료
- author/state/dates: zbnerd | MERGED | created 2026-03-02T22:51:59Z | closed 2026-03-02T22:52:09Z | merged yes at 2026-03-02T22:52:09Z | merge c0b37b542ca297f827fc171f9f40ee8f7362388a.
- body: ## 관련 이슈 ADR-012 ## 개요 Like 패키지의 core/infra 계층 분리 완료 ## 작업 내용 - [x] Core 계층: LikeEvent.kt DTO, LikeEventPublisher.kt 인터페이스 생성 - [x…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [a7a978d, d616b4b, 894ede0]; linked issues: 0 [].
- file evidence: 48 [ADDED docs/05_Reports/session-report-2026-03-02-135835.md +42/-0; ADDED docs/05_Reports/session-report-2026-03-02-144854.md +42/-0; ADDED docs/05_Reports/session-report-2026-03-02-154638.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #536 — refactor: ADR-004 Phase 2 - Module-Core Boundary Establishment
- author/state/dates: zbnerd | MERGED | created 2026-03-03T00:40:16Z | closed 2026-03-03T13:25:45Z | merged yes at 2026-03-03T13:25:45Z | merge c5f394f488736175f20576059839204720d67710.
- body: ## 관련 이슈 ADR-004 Multi-Module Architecture Migration ## 개요 module-core 경계를 확립하여 도메인 이벤트와 인터페이스를 core 모듈로 이관하고, 사용되지 않는 Java 클래스를 정…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [ae2cc3e]; linked issues: 0 [].
- file evidence: 26 [ADDED docs/05_Reports/session-report-2026-03-02-214341.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-02-214458.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-02-220954.md +49/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #537 — fix: ADR-004 Phase 2 테스트 수정 - Kotlin data class 역직렬화 문제 해결
- author/state/dates: zbnerd | MERGED | created 2026-03-03T15:53:02Z | closed 2026-03-03T15:55:06Z | merged yes at 2026-03-03T15:55:06Z | merge 3475f32ee460b67b41da8bba25fd6d7509e6f00d.
- body: ## 관련 이슈 ADR-004 Phase 2 ## 개요 ADR-004 Phase 2 리팩토링으로 `EquipmentExpectationResponseV4`가 Java에서 Kotlin data class로 변경됨에 따라 발생한 테스트 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 4 [ae2cc3e, 606cbcb, fc6891f]; linked issues: 0 [].
- file evidence: 105 [ADDED docs/05_Reports/session-report-2026-03-03-014249.md +49/-0; ADDED docs/05_Reports/session-report-2026-03-03-020623.md +49/-0; MODIFIED module-app/src/main/java/maple/expectation/adapter/in/AuthPortAdapter.java +3/-3].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #538 — refactor: ADR-004 Phase 5 - 빈 패키지 제거 및 adapter/in → application/usecase 이관
- author/state/dates: zbnerd | MERGED | created 2026-03-03T16:36:58Z | closed 2026-03-03T17:13:09Z | merged yes at 2026-03-03T17:13:09Z | merge 2a1d5766aa919be86a2e0695bf43d86864a39fad.
- body: ## 관련 이슈 ADR-004 Phase 5 ## 개요 ADR-004 Phase 5 수행 - 빈 패키지 제거 및 adapter/in → application/usecase, 패키지 구조 개선 ## 작업 내용 - [x] **Phase …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [c17592b, 53bea80, 233faef]; linked issues: 0 [].
- file evidence: 66 [DELETED module-app/src/main/java/maple/expectation/application/dto/package-info.java +0/-16; DELETED module-app/src/main/java/maple/expectation/application/mapper/package-info.java +0/-16; MODIFIED module-app/src/main/java/maple/expectation/application/service/PotentialApplicationService.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #327 — test: Calculator Domain Characterization Tests 수정
- author/state/dates: zbnerd | MERGED | created 2026-02-08T12:23:19Z | closed 2026-02-08T12:23:26Z | merged yes at 2026-02-08T12:23:26Z | merge 4c94fcb6c7d7cc08ecd08c1b1e7ce34108355853.
- body: ## 관련 이슈 #207 - Phase 3: 클린 코드 리팩토링 ## 개요 Calculator Domain Characterization Tests 수정하여 모든 테스트 통과 ## 작업 내용 - [x] @MockitoSettings(…
- reviews/discussion: 0 []; 0.
- commits: 1 [1310897]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/test/java/maple/expectation/characterization/CalculatorCharacterizationTest.java +17/-8].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #334 — refactor: CLAUDE.md 및 하위 문서 위반 사항 전면 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-08T18:47:51Z | closed 2026-02-08T18:47:59Z | merged yes at 2026-02-08T18:47:59Z | merge b4ef7dbe9f04a9e3a98500b37731655cf85e2733.
- body: ## 개요 CLAUDE.md 본문 및 하위 문서(infrastructure.md, async-concurrency.md, testing-guide.md)의 위반 사항을 전면 리팩토링했습니다. ## 변경 사항 요약 ### P0 위반 (…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [8f885c7]; linked issues: 0 [].
- file evidence: 21 [ADDED docs/04_Reports/CODE_QUALITY_ANALYSIS_2026-02-08.md +237/-0; MODIFIED src/main/java/maple/expectation/config/PresetCalculationExecutorConfig.java +20/-6; MODIFIED src/main/java/maple/expectation/controller/AdminController.java +36/-24].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #335 — refactor: CLAUDE.md 전면 리팩토링 및 비동기 Controller 전환
- author/state/dates: zbnerd | MERGED | created 2026-02-08T19:47:28Z | closed 2026-02-08T20:08:46Z | merged yes at 2026-02-08T20:08:46Z | merge 569bd9cd2b2e56fef238b0e6f8cb2403920968f5.
- body: ## 개요 CLAUDE.md 및 하위 문서의 위반 사항을 전면적으로 개선하고, Controller를 비동기 Non-Blocking 패턴으로 전환하여 시스템의 응답성과 확장성을 강화했습니다. ## 작업 내용 ### P0: 핵심 안티패턴…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 34 [a4ae159, 7cf1de4, 9114cc6]; linked issues: 0 [].
- file evidence: 123 [MODIFIED .github/workflows/ci.yml +25/-6; MODIFIED CLAUDE.md +1/-0; MODIFIED benchmarks/wrk/acl-benchmark.lua +23/-29].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #336 — refactor: Ultrawork Phase 2 - Issues #331, #332, #333
- author/state/dates: zbnerd | MERGED | created 2026-02-08T21:33:33Z | closed 2026-02-08T21:33:46Z | merged yes at 2026-02-08T21:33:46Z | merge e8966c9a9ea7607adfcd361b08e0e70b39c500fa.
- body: ## 관련 이슈 #331, #332, #333 ## 개요 Ultrawork Phase 2: 병렬 에이전트 팀을 통해 3개 이슈 동시 리팩토링 완료 ## 작업 내용 ### #331: NexonDataCollector Reactive 전…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [0dbfa24]; linked issues: 0 [].
- file evidence: 17 [ADDED src/main/java/maple/expectation/domain/v2/NexonApiDlq.java +108/-0; MODIFIED src/main/java/maple/expectation/external/NexonApiClient.java +13/-0; ADDED src/main/java/maple/expectation/external/dto/v2/CubeHistoryResponse.java +60/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #337 — refactor: Fix all P1 and P2 Codex review issues
- author/state/dates: zbnerd | MERGED | created 2026-02-08T23:48:49Z | closed 2026-02-08T23:48:57Z | merged yes at 2026-02-08T23:48:57Z | merge bc679ca566a23344fa43ebb5729f5a36e3eb1b9e.
- body: ## 관련 이슈 Codex Review - P1 & P2 Issues ## 개요 총 24개의 Codex 리뷰 이슈를 일괄 수정 (P1: 13개, P2: 11개) ## 작업 내용 ### P1 이슈 (13개) - ✅ EquipmentEx…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [bbf1f3b, f645dbb]; linked issues: 0 [].
- file evidence: 38 [ADDED docs/04_Reports/ULTRAWORK_ISSUES_331_333_COMPLETE.md +383/-0; MODIFIED src/main/java/maple/expectation/batch/MonitoringReportJob.java +4/-4; MODIFIED src/main/java/maple/expectation/config/CacheInvalidationConfig.java +14/-5].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #338 — refactor: ULTRAQA Cycle 2 - SOLID principles compliance and code quality improvements
- author/state/dates: zbnerd | MERGED | created 2026-02-09T23:49:45Z | closed 2026-02-09T23:50:02Z | merged yes at 2026-02-09T23:50:02Z | merge 56dc3667844a4109ae83bde7fc8279d2959f99c4.
- body: ## 관련 이슈 #328, #329, #330 (ULTRAQA Cycle 2) ## 개요 ULTRAQA Cycle 2의 5-Agent Council 검증을 통과한 SOLID 원칙 준수 및 코드 품질 개선 리팩토링입니다. Flaky T…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [cefd417]; linked issues: 0 [].
- file evidence: 30 [ADDED docs/04_Reports/ULTRAQA-CYCLE2-COMPREHENSIVE-REFACTORING-REPORT.md +369/-0; ADDED docs/04_Reports/ULTRAQA-CYCLE2-P1-REFACTORING-EXECUTION-REPORT.md +370/-0; ADDED docs/adr/ADR-019-ultraqa-cycle2-solid-refactoring.md +305/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #339 — fix: Resolve flaky test issues #328, #329, #330 with SOLID refactoring
- author/state/dates: zbnerd | MERGED | created 2026-02-10T02:40:04Z | closed 2026-02-10T02:40:15Z | merged yes at 2026-02-10T02:40:15Z | merge c5a9747e8239925bdd7bfdda8ffa3e47c81d7671.
- body: ## 관련 이슈 #328, #329, #330 ## 개요 이번 PR은 플래키 테스트(Flaky Test) 이슈 #328, #329, #330을 SOLID 원칙에 따라 리팩토링하여 근본적으로 해결합니다. 비동기 테스트 안정성을 위한 T…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [afed2d8]; linked issues: 0 [].
- file evidence: 12 [MODIFIED build.gradle +3/-0; ADDED docs/04_Reports/flaky-test-fixing-report-issues-328-330.md +281/-0; ADDED docs/04_Reports/monitoring-dashboard-flaky-tests.md +370/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #340 — feat(adr-017): Slice 1 - Equipment Domain Extraction Complete
- author/state/dates: zbnerd | MERGED | created 2026-02-10T04:59:00Z | closed 2026-02-10T07:08:36Z | merged yes at 2026-02-10T07:08:36Z | merge db7aff20ee005058eac48e994d0166b0adf34e7b.
- body: ## 관련 이슈 #282 ## 개요 ADR-017 Slice 1: CharacterEquipment 도메인을 JPA/Spring 의존성에서 분리하여 순수 도메인 모델로 추출 완료. Clean Architecture 및 Hexagona…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [9b0b5b1, 38e1295]; linked issues: 0 [].
- file evidence: 25 [ADDED docs/00_Start_Here/characterization-test-summary.md +235/-0; ADDED docs/04_Reports/Baseline/BASELINE_20260210.md +81/-0; ADDED docs/04_Reports/RED_AGENT_REVIEW_ADR-017.md +483/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #341 — feat: 모니터링 및 카오스 테스트 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-10T22:13:51Z | closed 2026-02-10T22:13:59Z | merged yes at 2026-02-10T22:13:59Z | merge 56849df0af75577f03fe1b2122694219b1cb5fa8.
- body: ## 개요 모니터링 및 카오스 엔지니어링 테스트 코드를 리팩토링하여 테스트 안정성과 가독성을 개선 ## 작업 내용 - [x] DiskFullChaosTest: LogicExecutor 패턴 적용 및 assertThrows로 예외 검증…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [4c5ce60, 4fee17f]; linked issues: 0 [].
- file evidence: 22 [ADDED docs/04_Reports/adr-014-analysis-and-recommendations.md +450/-0; MODIFIED module-app/build.gradle +21/-0; ADDED module-app/logs/trace.log +0/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #342 — test: Fix 34 failing tests and improve code style
- author/state/dates: zbnerd | MERGED | created 2026-02-11T06:37:38Z | closed 2026-02-11T06:37:50Z | merged yes at 2026-02-11T06:37:50Z | merge 5fbe6ebe01479251247a23b170473218ea72db6f.
- body: ## 개요 34개의 실패하던 테스트를 모두 수정하고, 코드 스타일을 개선했습니다. ## 작업 내용 - [x] OutboxProcessorTest 수정 (3개 테스트) - [x] ExpectationWriteBackBufferConcu…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [71457c0]; linked issues: 0 [].
- file evidence: 152 [ADDED MIGRATION_STATUS.md +252/-0; ADDED TEST-REWRITE-QUICK-REF.md +126/-0; ADDED ULTRAWORK-SESSION-COMPLETE.md +312/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #343 — fix: CI 워크플로우 Gradle Action 버전 및 health-cmd 수정
- author/state/dates: zbnerd | MERGED | created 2026-02-11T06:54:03Z | closed 2026-02-11T06:54:23Z | merged yes at 2026-02-11T06:54:23Z | merge ef03cabf55e019d90c9f4f8fc99b3570a1f04943.
- body: ## 관련 이슈 #342 이후 CI 실패 ## 개요 CI 워크플로우 'invalid workflow file' 에러 수정 ## 작업 내용 - [x] gradle/actions/setup-gradle@v4 → @v3 (최신 버전) - …
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [eb96567]; linked issues: 0 [].
- file evidence: 3 [MODIFIED .github/workflows/ci.yml +2/-2; MODIFIED .github/workflows/gradle.yml +1/-1; MODIFIED .github/workflows/nightly.yml +4/-4].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #346 — feat: ADR-0345 Stateless Alert System - Issue #345
- author/state/dates: zbnerd | MERGED | created 2026-02-11T22:23:46Z | closed 2026-02-13T00:08:37Z | merged yes at 2026-02-13T00:08:37Z | merge a9d5761360aa76b4cf9e4a056a2e380f044e9cb6.
- body: ## 📋 Related Issue #345 ## 🎯 Overview Connection pool exhaustion 시에도 동작하는 Stateless Alert System 구현 ## ✅ Implementation Summary ##…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 28 [26652ed, 6eb8c3b, abf7a29]; linked issues: 0 [].
- file evidence: 58 [MODIFIED .github/workflows/ci.yml +2/-5; MODIFIED .github/workflows/gradle.yml +18/-5; MODIFIED docker-compose.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #347 — feat: TaskScheduler pool configuration (Issue #344)
- author/state/dates: zbnerd | MERGED | created 2026-02-13T06:29:56Z | closed 2026-02-13T06:47:39Z | merged yes at 2026-02-13T06:47:39Z | merge 988e19c331d9eeaa448266446907069deb4ea26d.
- body: ## 관련 이슈 #344 ## 개요 `@Scheduled(fixedRate)`가 스케줄러 중복 시 무제한 스레드 생성을 유발하여 MySQL Connection Pool 고갈을 일으키는 문제를 해결합니다. ## 작업 내용 ### 1. …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 2 [8ca484b, baf47b9]; linked issues: 0 [].
- file evidence: 8 [ADDED docs/04_Reports/issue-344-grafana-dashboard.json +438/-0; ADDED docs/04_Reports/issue-344-implementation-report.md +783/-0; ADDED docs/adr/ADR-034-scheduler-task-pool-configuration.md +556/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #348 — fix: V5 CQRS P0/P1 이슈 전체 해결 - 우선순위 역전, 레이스 컨디션, 동기화 완료
- author/state/dates: zbnerd | MERGED | created 2026-02-15T04:36:48Z | closed 2026-02-16T01:04:44Z | merged yes at 2026-02-16T01:04:44Z | merge 2a7823d0f978f18c2b6c79873aebe473d59d1f21.
- body: ## 관련 이슈 #283 ## 개요 V5 CQRS 구현의 모든 P0 및 P1 차단 이슈를 해결했습니다. 우선순위 역전, 레이스 컨디션, 미완성된 변환 로직 등을 수정하고, 스레드 풀 격리와 모니터링 대시보드를 추가했습니다. ## 작업…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 9 [acc551a, 0fea130, b85d3cd]; linked issues: 0 [].
- file evidence: 199 [MODIFIED .gitignore +16/-0; DELETED build.gradle.bak +0/-309; MODIFIED docker-compose.yml +22/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #349 — fix: CI test failure fixes
- author/state/dates: zbnerd | MERGED | created 2026-02-16T01:20:19Z | closed 2026-02-16T01:21:24Z | merged yes at 2026-02-16T01:21:24Z | merge a97bbf06d08e1cfab490e98396df663b3750cad5.
- body: ## 관련 이슈 CI Test Failures after PR #348 merge ## 개요 CI 테스트 실패 수정 - CubeServiceTest, AuthServiceTest ## 작업 내용 - [x] Add TestLogicEx…
- reviews/discussion: 0 []; 1 /  ### 💡 Codex Review https://github.com/zbnerd/probabili….
- commits: 11 [acc551a, 0fea130, b85d3cd]; linked issues: 0 [].
- file evidence: 3 [MODIFIED module-app/src/test/java/maple/expectation/service/v2/CubeServiceTest.java +1/-1; MODIFIED module-app/src/test/java/maple/expectation/service/v2/auth/AuthServiceTest.java +8/-4; MODIFIED module-app/src/test/java/maple/expectation/support/TestLogicExecutors.java +13/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #350 — refactor: Migrate module-common from Java to Kotlin
- author/state/dates: zbnerd | MERGED | created 2026-02-18T05:27:16Z | closed 2026-02-18T05:27:31Z | merged yes at 2026-02-18T05:27:31Z | merge 41960097a03c70e1fdceba7305247f93c57cb96f.
- body: ## 개요 module-common을 Java에서 Kotlin으로 완전히 마이그레이션하여 Toss Securities 코틀린 기반 플랫폼 팀 면접 준비를 위한 실무 경험을 확보합니다. ## 작업 내용 - [x] 60개 Java 파일 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 2 [f718871, 4c7ec7e]; linked issues: 0 [].
- file evidence: 23 [ADDED .kotlin/errors/errors-1771390020699.log +155/-0; ADDED .kotlin/errors/errors-1771390020987.log +179/-0; ADDED .kotlin/errors/errors-1771390613948.log +179/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #351 — refactor: Complete module-common Kotlin migration (68 files → Kotlin)
- author/state/dates: zbnerd | MERGED | created 2026-02-18T06:02:10Z | closed 2026-02-18T06:02:18Z | merged yes at 2026-02-18T06:02:18Z | merge c1c1907fcbb2e2346b366a4ce1dcdbdb8df880b5.
- body: ## 개요 module-common의 모든 비즈니스 로직을 Java에서 Kotlin으로 완전히 마이그레이션했습니다. ## 작업 내용 - [x] LogicExecutor 계층 구조 (21개 파일) - [x] Exception 계층 구조…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [3e5f3b7]; linked issues: 0 [].
- file evidence: 34 [ADDED .kotlin/errors/errors-1771393948776.log +35/-0; MODIFIED module-app/src/main/java/maple/expectation/service/v2/CharacterCreationService.java +1/-1; MODIFIED module-app/src/main/java/maple/expectation/service/v2/outbox/impl/NexonApiRetryClientImpl.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #352 — docs: Chaos Engineering 문서 구조 개선 및 검증 기준 강화
- author/state/dates: zbnerd | MERGED | created 2026-02-18T13:47:22Z | closed 2026-02-18T13:49:29Z | merged yes at 2026-02-18T13:49:29Z | merge 0d97396e21753068b4ef86d90d6d7fb7723f595f.
- body: ## 관련 이슈 문서 개선 (별도 이슈 없음) ## 개요 Chaos Engineering 문서 구조를 개선하고 검증 기준을 강화했습니다. ## 작업 내용 - [x] Results 폴더 삭제 → Scenarios에 통합 (12개 파일 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 25 [4eebb85, 7dcfcf0, cfb5a79]; linked issues: 0 [].
- file evidence: 467 [ADDED .kotlin/errors/errors-1771388969046.log +54/-0; ADDED .kotlin/errors/errors-1771390020699.log +155/-0; ADDED .kotlin/errors/errors-1771390020987.log +179/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #355 — fix: Resolve Issue #354 - Redis Stream consumption and MongoDB idempotency
- author/state/dates: zbnerd | MERGED | created 2026-02-20T12:17:09Z | closed 2026-02-20T12:21:45Z | merged yes at 2026-02-20T12:21:45Z | merge c4d4dd5a133dd6370cc0b411ed91c70e0eec2752.
- body: ## 관련 이슈 #354 ## 개요 Redis Stream Consumer Group 초기화 문제와 MongoDB 멱등성 문제를 해결하여 V5 CQRS Read Side가 안정적으로 동작하도록 수정 ## 작업 내용 ### 1. Str…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 43 [4eebb85, 7dcfcf0, cfb5a79]; linked issues: 0 [].
- file evidence: 114 [MODIFIED .github/workflows/nightly.yml +12/-12; ADDED .kotlin/errors/errors-1771420612448.log +100/-0; ADDED .kotlin/errors/errors-1771420635515.log +126/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #363 — feat: Spring Batch로 전체 유저 장비 데이터 주기 갱신 - Issue #356
- author/state/dates: zbnerd | MERGED | created 2026-02-21T20:13:16Z | closed 2026-02-21T20:13:52Z | merged yes at 2026-02-21T20:13:52Z | merge 3893bbcab1d896fa8da1af80ab462f76f0623cbf.
- body: ## 관련 이슈 #356 ## 개요 Spring Batch를 도입하여 전체 유저의 장비 데이터를 매일 새벽 2시에 주기적으로 갱신하는 배치 작업을 구현합니다. ## 주요 변경 사항 ### 1. Spring Batch 구현 - **Ba…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 2 [ad1d591, 3893bbc]; linked issues: 0 [].
- file evidence: 20 [MODIFIED README.md +133/-0; ADDED docs/01_ADR/ADR-082-issue-356-batch-refresh.md +693/-0; ADDED evidence/N19/backlog.png.md +25/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #364 — feat: Fix P0/P1/P2 critical issues (13 tasks)
- author/state/dates: zbnerd | MERGED | created 2026-02-23T11:11:44Z | closed 2026-02-23T11:14:22Z | merged yes at 2026-02-23T11:14:22Z | merge 2890e5ca2b74bfe3b6710f6e75535f7e7b0d098e.
- body: ## 📋 Overview Fixes all P0, P1, and P2 critical issues identified in codebase review - 13 tasks, 29 issues resolved. ## ✅ Summary …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [e51c72f, ae672a5]; linked issues: 0 [].
- file evidence: 71 [MODIFIED .gitignore +3/-0; DELETED .kotlin/errors/errors-1771388969046.log +0/-54; DELETED .kotlin/errors/errors-1771390020699.log +0/-155].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #365 — fix: CI 컴파일/테스트 실패 해결 및 Stop hook 강화
- author/state/dates: zbnerd | MERGED | created 2026-02-23T20:58:45Z | closed 2026-02-23T21:01:04Z | merged yes at 2026-02-23T21:01:04Z | merge b292154ffbb90d3ce2c1ab3f6b7d309b7020b5ee.
- body: ## 관련 이슈 N/A (CI 안정화) ## 개요 CI 컴파일 및 테스트 실패를 해결하고 Stop hook을 강화하여 LLM 안티패턴을 기계적으로 감지하도록 개선했습니다. ## 작업 내용 - [x] GlobalTestConfig: 중…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [2f82f32]; linked issues: 0 [].
- file evidence: 27 [ADDED .claude/hooks/stop-validation.sh +153/-0; MODIFIED CLAUDE.md +31/-0; ADDED docs/01_Adr/ADR-085-jdbc-batch-migration.md +334/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #390 — feat: Java-to-Kotlin 마이그레이션 Phase 1-1, 2-1, 2-2 완료 (Issues #366, #367, #368)
- author/state/dates: zbnerd | MERGED | created 2026-02-24T14:23:21Z | closed 2026-02-24T14:23:35Z | merged yes at 2026-02-24T14:23:35Z | merge f9456442443fe7f73b64496fec97bb79266c9a3c.
- body: ## 관련 이슈 - Closes #366 - Closes #367 - Closes #368 ## 개요 Java-to-Kotlin 마이그레이션 Phase 1-1, 2-1, 2-2 완료 ## 작업 내용 ### Phase 1-1 (Issu…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [7ec2a72]; linked issues: 0 [].
- file evidence: 56 [ADDED MIGRATION_PLAN.md +270/-0; DELETED module-common/src/main/java/maple/expectation/shared/aop/package-info.java +0/-17; DELETED module-common/src/main/java/maple/expectation/shared/error/package-info.java +0/-16].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #391 — feat: Java-to-Kotlin 마이그레이션 Phase 2-3, 3-1, 3-2, 3-3, 3-4, 3-5 완료 (#369-374)
- author/state/dates: zbnerd | MERGED | created 2026-02-24T16:19:36Z | closed 2026-02-25T08:42:51Z | merged yes at 2026-02-25T08:42:51Z | merge fb787b678204c0d4601a6b33dedb44c131fce693.
- body: ## Summary Java-to-Kotlin 마이그레이션 Phase 2-3, 3-1, 3-2, 3-3, 3-4, 3-5 완료 ## 변환된 모듈 ### module-core (Phase 2-3) - 도메인 이벤트, 비용 포맷터, 포트…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 12 [0c40698, c241443, ecff195]; linked issues: 0 [].
- file evidence: 324 [MODIFIED module-app/build.gradle +7/-0; MODIFIED module-app/src/main/java/maple/expectation/application/dto/GameCharacterDto.java +25/-13; MODIFIED module-app/src/main/java/maple/expectation/application/service/EquipmentApplicationService.java +6/-7].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #392 — feat: Java to Kotlin migration for Cube decorators and DTOs (Issue #375, #376)
- author/state/dates: zbnerd | MERGED | created 2026-02-25T11:24:19Z | closed 2026-02-25T11:26:18Z | merged yes at 2026-02-25T11:26:18Z | merge 35b2a0b137654d3b309e51700384b187f91f91a3.
- body: ## 관련 이슈 - #375 - #376 ## 개요 Java 파일을 Kotlin으로 변환 - Cube 데코레이터 및 DTO 클래스 마이그레이션 ## 작업 내용 - [x] `CubeCalculationInput.java` → `Cube…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [7509952]; linked issues: 0 [].
- file evidence: 12 [DELETED module-app/src/main/java/maple/expectation/dto/CubeCalculationInput.java +0/-218; DELETED module-app/src/main/java/maple/expectation/service/v2/calculator/impl/BlackCubeDecorator.java +0/-50; DELETED module-app/src/main/java/maple/expectation/service/v2/calculator/v4/impl/AdditionalCubeDecoratorV4.java +0/-58].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #393 — docs: Add guardrails documentation with hook patterns
- author/state/dates: zbnerd | MERGED | created 2026-02-25T13:22:43Z | closed 2026-02-25T13:24:58Z | merged yes at 2026-02-25T13:24:58Z | merge 97ad062f447e4b4c76ef8cf4d25023379c6e117f.
- body: ## 관련 이슈 #xxx (Guardrails 문서화) ## 개요 CLAUDE.md와 docs 하위 문서(ADR, ChaosEngineering, Technical Guides)를 참조하여 가드레일 문서를 체계적으로 구축하고, Pre…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [ab8b127]; linked issues: 0 [].
- file evidence: 41 [ADDED docs/guardrails/INDEX.json +301/-0; ADDED docs/guardrails/INDEX.md +119/-0; ADDED docs/guardrails/architecture/INDEX.md +27/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #394 — feat: Add Layer 2 AI Context Injection to guardrails hook
- author/state/dates: zbnerd | MERGED | created 2026-02-25T13:45:25Z | closed 2026-02-25T13:54:18Z | merged yes at 2026-02-25T13:54:18Z | merge e1a004c84c70a3bb130a381c55de03452504f17c.
- body: ## 관련 이슈 Guardrails 훅 개선 ## 개요 Regex로 감지할 수 없는 복잡한 패턴을 AI가 판단할 수 있도록 **Layer 2 AI Context Injection**을 추가했습니다. ## 작업 내용 - [x] Laye…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [39ecb3d]; linked issues: 0 [].
- file evidence: 1 [ADDED .claude/hooks/pre-tool-use.sh +261/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #395 — feat: Java to Kotlin migration for issues #377, #378
- author/state/dates: zbnerd | MERGED | created 2026-02-25T14:37:30Z | closed 2026-02-25T14:38:09Z | merged yes at 2026-02-25T14:38:09Z | merge 3b92942ac90761f608ecdae4dbe5ae969af3208c.
- body: ## 관련 이슈 #377, #378 ## 개요 Java-to-Kotlin 마이그레이션 Phase 3-8, 3-9 배치 ## 작업 내용 - [x] AsyncResponseUtils.java → AsyncResponseUtils.kt 변…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [d45126a]; linked issues: 0 [].
- file evidence: 6 [DELETED module-app/src/main/java/maple/expectation/controller/util/AsyncResponseUtils.java +0/-61; DELETED module-app/src/main/java/maple/expectation/error/GlobalExceptionHandler.java +0/-336; ADDED module-app/src/main/kotlin/maple/expectation/controller/util/AsyncResponseUtils.kt +57/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #396 — feat: Guardrails INDEX v2.0.0 Kotlin-compatible upgrade
- author/state/dates: zbnerd | MERGED | created 2026-02-25T15:50:33Z | closed 2026-02-25T16:47:37Z | merged yes at 2026-02-25T16:47:37Z | merge 31914b7ca150f464b46f8aaa93bfd1a50f1504b9.
- body: ## 개요 Guardrails 시스템을 Kotlin 호환 v2.0.0으로 업그레이드합니다. Claude Code Hooks 연동을 위한 INDEX.json 개선과 88개 가드레일 패턴을 체계적으로 정리했습니다. ## 작업 내용 ###…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [ac8b9de, d37e842]; linked issues: 0 [].
- file evidence: 58 [MODIFIED .claude/hooks/pre-tool-use.sh +35/-98; ADDED docs/guardrails/HOOK_GUIDE.md +533/-0; MODIFIED docs/guardrails/INDEX.json +287/-61].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #397 — feat: Java to Kotlin migration test compatibility fixes (#397)
- author/state/dates: zbnerd | MERGED | created 2026-02-26T07:15:16Z | closed 2026-02-26T07:15:37Z | merged yes at 2026-02-26T07:15:37Z | merge 4e4e7ebacc0f273624b1a6afc5535674081825a8.
- body: ## 관련 이슈 #397 (Java to Kotlin migration test compatibility) ## 개요 Java에서 Kotlin으로 마이그레이션된 코드에 대한 테스트 호환성 수정 ## 작업 내용 - [x] JwtToke…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [7a20396]; linked issues: 0 [].
- file evidence: 349 [MODIFIED .claude/hooks/pre-tool-use.sh +75/-1; MODIFIED docs/guardrails/INDEX.json +69/-1; ADDED docs/guardrails/database/innodb-buffer-pool.md +276/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #398 — feat: Architecture refactoring Issues #383, #384 - BufferRecoveryScheduler migration
- author/state/dates: zbnerd | MERGED | created 2026-02-26T14:04:16Z | closed 2026-02-26T17:29:56Z | merged yes at 2026-02-26T17:29:56Z | merge e3c93143fcd5d0cc1dd0eab99ace7cb08431ddf4.
- body: ## 관련 이슈 #383, #384 ## 개요 아키텍처 리팩토링 작업 중 BufferRecoveryScheduler를 module-app에서 module-infra로 이관했습니다. 추가로 Java → Kotlin migration도 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [0f2bd08, b207565]; linked issues: 0 [].
- file evidence: 96 [ADDED docs/05_Reports/04_09_Scale_Out/troubleshooting-kotlin-interop-2026-02-26.md +371/-0; ADDED docs/05_Reports/codebase-comprehensive-analysis-report.md +822/-0; ADDED docs/adr/ADR-036-monitoring-config-infra-migration.md +268/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #399 — refactor: architecture realignment - monitoring/config migration to module-infra (#385, #3…
- author/state/dates: zbnerd | MERGED | created 2026-02-26T18:44:16Z | closed 2026-02-26T18:44:34Z | merged yes at 2026-02-26T18:44:34Z | merge 7db4b029601c4e0238c025f4ff1fe57ee08cac6d.
- body: ## 관련 이슈 - #385 - #386 ## 개요 ADR-036에서 식별된 인프라 코드 누수 문제를 해결하기 위한 아키텍처 재정렬 작업 ## 작업 내용 ### Phase 0: DIP 준수 - [x] BufferStatusQuery …
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [2dcbb1e]; linked issues: 0 [].
- file evidence: 36 [MODIFIED module-app/src/main/java/maple/expectation/batch/MonitoringReportJob.java +1/-1; DELETED module-app/src/main/java/maple/expectation/config/BatchConfig.java +0/-79; DELETED module-app/src/main/java/maple/expectation/config/BatchProperties.java +0/-47].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #400 — refactor: web module extraction - GlobalExceptionHandler migration (#387, #388)
- author/state/dates: zbnerd | MERGED | created 2026-02-26T21:21:00Z | closed 2026-02-26T21:21:21Z | merged yes at 2026-02-26T21:21:21Z | merge 2c921d8f2471b9450ae5e2a5f18351ade8f312b4.
- body: ## 관련 이슈 - #387 - #388 ## 개요 아키텍처 리팩토링 - 웹 레이어 분리 및 모듈 구조 개선 ## 작업 내용 - [x] GlobalExceptionHandler를 module-web으로 이관 (웹 레이어 격리) - […
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [d63ae19]; linked issues: 0 [].
- file evidence: 12 [MODIFIED CLAUDE.md +1/-0; MODIFIED module-app/build.gradle +6/-0; MODIFIED module-app/src/main/java/maple/expectation/monitoring/ai/AiPromptBuilder.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #401 — fix: Kotlin compiler warnings in BaseDto and CubeCalculationInput
- author/state/dates: zbnerd | MERGED | created 2026-02-26T22:23:31Z | closed 2026-02-26T23:21:28Z | merged yes at 2026-02-26T23:21:28Z | merge 668395079beb21d29e34e72ed711e99737af5c1f.
- body: ## 관련 이슈 #382, #389 ## 개요 Kotlin 컴파일러 경고 2건 수정 ## 작업 내용 - [x] BaseDto.kt: 중복 `open` modifier 제거 (`abstract`는 이미 `open`을 의미) - [x] …
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 1 [12a59dc]; linked issues: 0 [].
- file evidence: 2 [MODIFIED module-app/src/main/kotlin/maple/expectation/application/dto/BaseDto.kt +5/-5; MODIFIED module-app/src/main/kotlin/maple/expectation/dto/CubeCalculationInput.kt +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #402 — docs: ResilientLockStrategy 다이어그램 및 ADR 통합
- author/state/dates: zbnerd | MERGED | created 2026-02-27T11:59:11Z | closed 2026-02-27T11:59:20Z | merged yes at 2026-02-27T11:59:20Z | merge 552594dd615a56875dab90672db2b5fb332cb46e.
- body: ## 관련 이슈 N/A (문서화 작업) ## 개요 ResilientLockStrategy 구조 및 흐름을 시각화한 다이어그램 문서 추가 ## 작업 내용 - [x] Class Diagram: LockStrategy 계층 구조 - [x]…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [3ced4b1, c38cc11, 2b917b5]; linked issues: 0 [].
- file evidence: 53 [RENAMED docs/01_ADR/ADR-001-streaming-parser.md +0/-0; RENAMED docs/01_ADR/ADR-003-tiered-cache-singleflight.md +0/-0; RENAMED docs/01_ADR/ADR-004-logicexecutor-policy-pipeline.md +0/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #403 — docs: 포트폴리오 기술 심화 분석 문서 추가
- author/state/dates: zbnerd | MERGED | created 2026-02-27T12:53:00Z | closed 2026-02-27T12:53:08Z | merged yes at 2026-02-27T12:53:08Z | merge fbd73ba4897c9cd79421b6bbfdacddf810c5194f.
- body: ## 관련 이슈 N/A (문서화 작업) ## 개요 포트폴리오용 기술 심화 분석 문서 작성 ## 작업 내용 - [x] 7개 핵심 성과에 대한 Mermaid 다이어그램 작성 - [x] 각 항목별 5장 구조 (문제-선택지-결정-구현-결과)…
- reviews/discussion: 0 []; 0.
- commits: 1 [7229930]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/05_Reports/PORTFOLIO_TECHNICAL_DEEP_DIVE.md +646/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #404 — docs: Cache Stampede Before 다이어그램 추가
- author/state/dates: zbnerd | MERGED | created 2026-02-27T13:29:43Z | closed 2026-02-27T13:29:45Z | merged yes at 2026-02-27T13:29:45Z | merge 7064f24591014eca024c9088b3f6cdfa08a5bdd8.
- body: ## 관련 이슈 N/A (문서화 개선) ## 개요 Cache Stampede 섹션에 개선 전(Before) 다이어그램 추가 ## 작업 내용 - [x] Before: Cache Stampede 발생 다이어그램 추가 - [x] 100개 …
- reviews/discussion: 0 []; 0.
- commits: 1 [c28a2fe]; linked issues: 0 [].
- file evidence: 1 [MODIFIED docs/05_Reports/PORTFOLIO_TECHNICAL_DEEP_DIVE.md +42/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #405 — docs: Nexon API Outbox 다이어그램 16:9 개선
- author/state/dates: zbnerd | MERGED | created 2026-02-27T13:52:50Z | closed 2026-02-27T13:52:52Z | merged yes at 2026-02-27T13:52:52Z | merge a4e40e80d8b37b06e21f77a82b48ef5e8a4b8a90.
- body: ## 관련 이슈 N/A (문서화 개선) ## 개요 Nexon API Outbox 다이어그램: 16:9 비율로 개선 ## 작업 내용 - [x] Before/After 비교 다이어그램 - [x] Nexon API 호출 흐름 명확화 ## …
- reviews/discussion: 0 []; 0.
- commits: 2 [2ce6bef, 224c557]; linked issues: 0 [].
- file evidence: 1 [MODIFIED docs/05_Reports/PORTFOLIO_TECHNICAL_DEEP_DIVE.md +30/-23].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #406 — docs: 포트폴리오 다이어그램 TD+LR 혼합 레이아웃 적용
- author/state/dates: zbnerd | MERGED | created 2026-02-27T14:11:55Z | closed 2026-02-27T14:12:04Z | merged yes at 2026-02-27T14:12:04Z | merge 443159c8550b7c71f7b169122e26baf7ee82ae7f.
- body: ## 관련 이슈 - 포트폴리오 문서 가독성 개선 ## 개요 포트폴리오 문서의 Mermaid 다이어그램에 TD+LR 혼합 레이아웃을 적용하여 16:9 비율 최적화 ## 작업 내용 - [x] Testcontainers 다이어그램: TB …
- reviews/discussion: 0 []; 0.
- commits: 1 [f1f04f4]; linked issues: 0 [].
- file evidence: 1 [MODIFIED docs/05_Reports/PORTFOLIO_TECHNICAL_DEEP_DIVE.md +46/-42].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #407 — fix: Kotlin nullable receiver 컴파일 에러 수정
- author/state/dates: zbnerd | MERGED | created 2026-02-27T17:05:58Z | closed 2026-02-27T21:23:11Z | merged yes at 2026-02-27T21:23:11Z | merge 7b5226a5ce077f64689edca8dba02fc05419f97a.
- body: ## 관련 이슈 검증 작업 중 발견된 P0 CRITICAL 이슈 수정 ## 개요 `CubeCalculationInput.kt`에서 nullable receiver로 인한 Kotlin 컴파일 에러 수정 ## 작업 내용 - [x] `op…
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 1 [64e9269]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/main/kotlin/maple/expectation/dto/CubeCalculationInput.kt +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #408 — fix: P1 보안 이슈 수정 (@PreAuthorize, Kotlin nullable)
- author/state/dates: zbnerd | MERGED | created 2026-02-27T17:18:36Z | closed 2026-02-27T21:23:22Z | merged yes at 2026-02-27T21:23:22Z | merge aaab46ac5dca922e511164b67431a5564af0351f.
- body: ## 관련 이슈 검증 작업 중 발견된 P1 보안 이슈 수정 ## 개요 V5 컨트롤러 @PreAuthorize 명시화 및 Kotlin nullable receiver 수정 ## 작업 내용 - [x] GameCharacterControl…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [41aaa21]; linked issues: 0 [].
- file evidence: 2 [MODIFIED module-app/src/main/java/maple/expectation/controller/GameCharacterControllerV5.java +3/-2; MODIFIED module-app/src/main/kotlin/maple/expectation/dto/CubeCalculationInput.kt +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #444 — fix: Kotlin DTO nullability 및 생성자 이슈 수정
- author/state/dates: zbnerd | MERGED | created 2026-02-28T10:35:56Z | closed 2026-02-28T10:36:05Z | merged yes at 2026-02-28T10:36:05Z | merge 026c047a26d43adc4ce7f313f7e96173ebfef14f.
- body: ## 관련 이슈 모듈 분리 작업 중 발생한 컴파일 오류 수정 ## 개요 Kotlin 마이그레이션 과정에서 발생한 DTO nullability 및 생성자 인자 불일치 문제 수정 ## 작업 내용 - [x] PotentialApplicat…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 19 [41aaa21, b4721da, d264b9a]; linked issues: 0 [].
- file evidence: 136 [ADDED .editorconfig +14/-0; MODIFIED build.gradle +6/-0; ADDED docs/adr/002-module-separation-kotlin.md +31/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #445 — feat: Module separation Phase 2 - Gradual migration
- author/state/dates: zbnerd | MERGED | created 2026-02-28T11:38:10Z | closed 2026-02-28T11:38:20Z | merged yes at 2026-02-28T11:38:20Z | merge ca912a96d63e6d7dd5e7f2ec21f7e91a71c175ac.
- body: ## 관련 이슈 N/A - 모듈 분리 Phase 2 작업 ## 개요 Module separation Phase 2 - Gradual migration 작업 진행 ## 작업 내용 - [x] Migrate LikeProcessor.jav…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [aec7f72]; linked issues: 0 [].
- file evidence: 8 [ADDED module-app/src/main/java/maple/expectation/controller/dto/auth/LoginResponse.java +42/-0; ADDED module-app/src/main/java/maple/expectation/controller/dto/auth/TokenResponse.java +27/-0; MODIFIED module-common/build.gradle +6/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #446 — chore: add Kotlin compilation config and migrate AuthController
- author/state/dates: zbnerd | MERGED | created 2026-02-28T11:48:05Z | closed 2026-02-28T11:48:17Z | merged yes at 2026-02-28T11:48:17Z | merge d4100f2c429b9d1dba56b43ae30c5a813e2763d1.
- body: ## 관련 이슈 모듈 분리 Phase 2 작업의 일환 ## 개요 Kotlin 컴파일 설정 추가 및 AuthController 이관 ## 작업 내용 - [x] module-infra/build.gradle에 Kotlin 컴파일 설정 추…
- reviews/discussion: 0 []; 0.
- commits: 1 [31bfca9]; linked issues: 0 [].
- file evidence: 3 [DELETED module-app/src/main/java/maple/expectation/controller/dto/auth/LoginResponse.java +0/-42; DELETED module-app/src/main/java/maple/expectation/controller/dto/auth/TokenResponse.java +0/-27; MODIFIED module-infra/build.gradle +19/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #447 — refactor: 기술부채 해결 - BatchScheduler 및 DTO 패키지 정리
- author/state/dates: zbnerd | MERGED | created 2026-02-28T12:33:04Z | closed 2026-02-28T12:33:29Z | merged yes at 2026-02-28T12:33:29Z | merge 2b82a29c7861d045478c3fe48e196a06a436d4ae.
- body: ## 관련 이슈 #436, #437, #438 (선행 PR #444, #445, #446에서 완료) ## 개요 기술부채 해결 및 코드 품질 개선 ## 작업 내용 - [x] BatchScheduler: 불필요한 중첩 try-catch …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [aec7f72, bcc32c2, bca1ed5]; linked issues: 0 [].
- file evidence: 10 [MODIFIED module-app/src/main/java/maple/expectation/batch/BatchScheduler.java +10/-29; MODIFIED module-common/build.gradle +6/-0; MODIFIED module-core/build.gradle +6/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #448 — docs: ADR-003 Hexagonal Architecture 채택
- author/state/dates: zbnerd | MERGED | created 2026-02-28T13:50:04Z | closed 2026-02-28T14:37:14Z | merged yes at 2026-02-28T14:37:14Z | merge 6d2d199c67f3feb891fcfb4e0f4c2d62a1767d12.
- body: ## 개요 ADR-003 Hexagonal Architecture 채택 문서 추가 ## Context - module-infra → module-app 역참조 190개 파일 발견 - 순환 의존성으로 인한 모듈 분리 실패 - DIP(의…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [5403b4d, c569fb5, 0f9c420]; linked issues: 0 [].
- file evidence: 6 [ADDED docs/adr/003-hexagonal-architecture-adoption.md +181/-0; MODIFIED module-app/src/main/java/maple/expectation/batch/writer/LowPriorityQueueWriter.java +3/-3; MODIFIED module-app/src/main/java/maple/expectation/config/TemporaryAdapterConfig.java +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #449 — refactor: ADR-003 OcidReader 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T15:26:58Z | closed 2026-02-28T15:27:13Z | merged yes at 2026-02-28T15:27:13Z | merge d56ced3552bbe7d6c5ea8e8f44ed0fc5f279275c.
- body: ## 관련 이슈 #424 ## 개요 OcidReader에 Hexagonal Architecture (Ports & Adapters) 패턴 적용 ## 작업 내용 - [x] OcidQueryPort 인터페이스 추출 (module-core…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [1fe4260]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/batch/reader/OcidReader.java +10/-14; MODIFIED module-app/src/test/java/maple/expectation/batch/reader/OcidReaderTest.java +34/-39; ADDED module-core/src/main/kotlin/maple/expectation/core/domain/model/Page.kt +96/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #450 — refactor: ADR-003 MonitoringReportJob 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T15:50:44Z | closed 2026-02-28T15:50:55Z | merged yes at 2026-02-28T15:50:55Z | merge c668a73a6a780be88dcc3b36388229dab426a5a0.
- body: ## 관련 이슈 #424 ## 개요 MonitoringReportJob에 Hexagonal Architecture (Ports & Adapters) 패턴 적용 ## 작업 내용 - [x] DiscordAlertService → Aler…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [1936904]; linked issues: 0 [].
- file evidence: 1 [MODIFIED module-app/src/main/java/maple/expectation/batch/MonitoringReportJob.java +56/-54].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #451 — refactor: ADR-003 LikeSyncScheduler 헥사고날 아키텍처 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-02-28T16:22:34Z | closed 2026-02-28T16:22:50Z | merged yes at 2026-02-28T16:22:50Z | merge 9f8c2e32cbdfabcd5531cb1f7af39497055d4b5d.
- body: ## 관련 이슈 #424 ## 개요 ADR-003 Hexagonal Architecture에 따라 LikeSyncScheduler가 Port 인터페이스를 사용하도록 리팩토링 ## 작업 내용 - [x] LikeSyncPort 인터페이스…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [f6a9d42]; linked issues: 0 [].
- file evidence: 5 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +14/-10; MODIFIED module-app/src/main/java/maple/expectation/service/v2/LikeRelationSyncService.java +4/-5; MODIFIED module-app/src/main/java/maple/expectation/service/v2/LikeSyncService.java +2/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #452 — refactor: ADR-003 ExpectationCalculationScheduler QueueWriterPort 사용
- author/state/dates: zbnerd | MERGED | created 2026-02-28T16:36:46Z | closed 2026-02-28T16:36:50Z | merged yes at 2026-02-28T16:36:50Z | merge 49135063afe9995fb3b8be5dec7af11e7d36999c.
- body: ## 관련 이슈 #424 ## 개요 ExpectationCalculationScheduler가 기존 QueueWriterPort를 사용하도록 리팩토링 ## 작업 내용 - [x] QueueWriterPort에 size() 메서드 추가 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [a9a656f]; linked issues: 0 [].
- file evidence: 3 [MODIFIED module-app/src/main/java/maple/expectation/scheduler/ExpectationCalculationScheduler.java +4/-4; MODIFIED module-core/src/main/kotlin/maple/expectation/core/port/out/QueueWriterPort.kt +9/-0; MODIFIED module-infra/src/main/kotlin/maple/expectation/infra/adapter/QueueWriterAdapter.kt +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #248 — chore: CI 최적화 및 loki4j 호환성 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-21T19:23:18Z | closed 2026-01-21T19:23:26Z | merged yes at 2026-01-21T19:23:26Z | merge d429375d5cedc71e3cd49fa9a16caaf1cb512ea1.
- body: ## 관련 이슈 - CI 테스트 실패 수정 - Gradle 캐싱 최적화 - loki4j 1.4.x 호환성 ## 개요 CI 파이프라인 안정화 및 서버 시작 실패 문제 해결 ## 작업 내용 - [x] @Tag("integration") …
- reviews/discussion: 0 []; 0.
- commits: 3 [6bd373d, 0077c32, 6a9ca4e]; linked issues: 0 [].
- file evidence: 9 [MODIFIED .github/workflows/gradle.yml +3/-0; MODIFIED .github/workflows/nightly.yml +2/-0; MODIFIED src/main/resources/logback-spring.xml +2/-7].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #249 — fix: Codex 코드리뷰 12건 해결 (PR #186 ~ #248)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T23:25:36Z | closed 2026-01-21T23:25:44Z | merged yes at 2026-01-21T23:25:44Z | merge cc83f462503187396e3b915de14e9e76083596ef.
- body: ## 관련 이슈 #186, #187, #189, #192, #199, #206, #236, #238, #241, #242 ## 개요 Codex 코드리뷰 12건을 CLAUDE.md 가이드라인에 따라 해결합니다. ## 작업 내용 ### …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [cc8e593]; linked issues: 0 [].
- file evidence: 20 [MODIFIED docker/grafana/provisioning/datasources/loki.yml +1/-0; MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +5/-4; MODIFIED src/main/java/maple/expectation/controller/DonationController.java +14/-5].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #261 — docs: KPI-BSC 대시보드, BMC, 메트릭 증거 문서 추가 및 README 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-23T19:14:26Z | closed 2026-01-23T20:00:56Z | merged yes at 2026-01-23T20:00:56Z | merge 969e7714a68ffe822f0ed048924d716da2aafbd1.
- body: ## 관련 이슈 #252, #253, #254 ## 개요 KPI-BSC 대시보드 문서 작성, README 개선, BMC 문서화, 메트릭 수집 증거 문서화 ## 작업 내용 - [x] docs/04_Reports/METRIC_COLLEC…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [12248ae]; linked issues: 0 [].
- file evidence: 4 [MODIFIED README.md +45/-3; ADDED docs/00_Start_Here/BUSINESS_MODEL.md +284/-0; ADDED docs/04_Reports/KPI_BSC_DASHBOARD.md +244/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #263 — feat: V4 API Singleflight 패턴 적용 및 GZIP 응답 최적화 (#262)
- author/state/dates: zbnerd | MERGED | created 2026-01-24T00:46:54Z | closed 2026-01-24T00:47:58Z | merged yes at 2026-01-24T00:47:58Z | merge 418cc04dfe9db275ed2b5249f232689ba38825df.
- body: ## 관련 이슈 #262 ## 개요 V4 API Cache Stampede 해결 - TieredCache Singleflight 패턴 적용 ## 작업 내용 - [x] `getOrCalculateExpectation()` Singlef…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [1569b17, 579799f]; linked issues: 0 [].
- file evidence: 17 [MODIFIED .github/workflows/ci.yml +1/-1; MODIFIED .github/workflows/gradle.yml +2/-2; MODIFIED .github/workflows/nightly.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #265 — feat: V4 API L1 Fast Path 최적화 및 캐시 튜닝 (#264)
- author/state/dates: zbnerd | MERGED | created 2026-01-24T10:24:19Z | closed 2026-01-24T10:24:25Z | merged yes at 2026-01-24T10:24:25Z | merge 6fbadecc2f31dc2c9cfd7c7573b72413d00491a9.
- body: ## 관련 이슈 #264 ## 개요 V4 API 캐시 히트 시 RPS 병목 해결 - L1 Fast Path 패턴 적용 ## 작업 내용 - [x] TieredCacheManager: `getL1CacheDirect()` L1 직접 접근…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [5d60234]; linked issues: 0 [].
- file evidence: 12 [MODIFIED docs/04_Reports/KPI_BSC_DASHBOARD.md +15/-3; ADDED docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260124_V4_PHASE2.md +286/-0; MODIFIED locust/locustfile.py +12/-2].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #266 — feat: V4 API 병목 해소 - 프리셋 병렬 계산 + Write-Behind 버퍼 (#266)
- author/state/dates: zbnerd | MERGED | created 2026-01-25T13:16:28Z | closed 2026-01-25T13:17:18Z | merged yes at 2026-01-25T13:17:18Z | merge db7f3f99d5453f7249290a510a3aec1ec5316a05.
- body: ## 관련 이슈 #266 ## 개요 5-Agent Council 합의에 따라 V4 API의 두 가지 주요 병목을 해소합니다: 1. **프리셋 순차 계산** (300ms) → 병렬 계산 (100ms) 2. **동기 DB 저장** (15…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 3 [798cc1d, 7e2bc65, 53b93bb]; linked issues: 0 [].
- file evidence: 11 [MODIFIED README.md +15/-14; MODIFIED build.gradle +10/-1; MODIFIED docs/04_Reports/KPI_BSC_DASHBOARD.md +28/-8].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #267 — docs: 문서 코드베이스 정합성 수정 (#255)
- author/state/dates: zbnerd | MERGED | created 2026-01-25T14:33:02Z | closed 2026-01-25T14:33:09Z | merged yes at 2026-01-25T14:33:09Z | merge ba471ede2742457d66e42a988a38a3c556d19b89.
- body: ## 관련 이슈 #255 ## 개요 문서와 실제 코드베이스 간의 정합성 수정 ## 작업 내용 - [x] 문서를 실제 코드베이스 기반으로 수정 - [x] resilience.md 상단 잘못된 프롬프트 텍스트 제거 - [x] 문서 코드베…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [672a835, a77bb1c, 5219604]; linked issues: 0 [].
- file evidence: 7 [MODIFIED docs/00_Start_Here/architecture.md +5/-4; MODIFIED docs/02_Technical_Guides/SCENARIO_PLANNING.md +145/-162; MODIFIED docs/02_Technical_Guides/async-concurrency.md +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #268 — docs: README Fit Check, Adoption Guide, ADR 추가 (#252, #256, #257)
- author/state/dates: zbnerd | MERGED | created 2026-01-25T14:40:36Z | closed 2026-01-25T14:40:44Z | merged yes at 2026-01-25T14:40:44Z | merge 8f9ec3752865be9bdbc2af80440f9c8656e66c50.
- body: ## 관련 이슈 #252, #256, #257 ## 개요 문서화 이슈 3개를 일괄 처리합니다. ## 작업 내용 ### #252 README Product-Level Overhaul - [x] README에 Fit Check 섹션 추가…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 3 [672a835, 3bd9632, aa7b4f4]; linked issues: 0 [].
- file evidence: 5 [MODIFIED README.md +14/-0; ADDED docs/05_Guides/adoption.md +335/-0; ADDED docs/adr/ADR-001-streaming-parser.md +81/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #269 — docs: ADR 004~011 아키텍처 결정 문서 작성
- author/state/dates: zbnerd | MERGED | created 2026-01-25T14:57:57Z | closed 2026-01-25T15:00:10Z | merged yes at 2026-01-25T15:00:10Z | merge 246ccf2e7982448815f2c6fc93e936d007c7c36a.
- body: ## 관련 이슈 N/A (문서화 작업) ## 개요 시스템 아키텍처의 핵심 결정 사항을 ADR(Architecture Decision Records) 형식으로 문서화합니다. ## 작업 내용 - [x] **ADR-004**: LogicE…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [1993c7a, 116f80c]; linked issues: 0 [].
- file evidence: 10 [MODIFIED README.md +0/-5; ADDED docs/adr/ADR-004-logicexecutor-policy-pipeline.md +139/-0; ADDED docs/adr/ADR-005-resilience4j-scenario-abc.md +151/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #270 — release: ADR 문서 및 V4 API 스펙 릴리즈
- author/state/dates: zbnerd | MERGED | created 2026-01-25T15:00:24Z | closed 2026-01-25T15:00:31Z | merged yes at 2026-01-25T15:00:31Z | merge 72a87c3ab2e631a85ba17bce6cf212f3ea975532.
- body: ## 개요 develop 브랜치의 문서화 작업을 master로 릴리즈합니다. ## 포함된 변경사항 ### ADR (Architecture Decision Records) - **ADR-001**: Streaming Parser 설계 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 21 [969e771, 418cc04, 96893bd]; linked issues: 0 [].
- file evidence: 56 [MODIFIED .github/workflows/ci.yml +1/-1; MODIFIED .github/workflows/gradle.yml +2/-2; MODIFIED .github/workflows/nightly.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #272 — feat: #275 인기 캐릭터 자동 웜업 기능 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-27T06:44:36Z | closed 2026-01-27T06:45:47Z | merged yes at 2026-01-27T06:45:47Z | merge a98b6716d410c243fb3254d22ac92d66b1b6f56c.
- body: ## 관련 이슈 #275 ## 개요 V4 API의 Cold Cache 문제를 해결하기 위한 인기 캐릭터 자동 웜업 시스템 구현 ## 작업 내용 - [x] `PopularCharacterTracker`: Redis ZSET으로 일별 호…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [89c7d40]; linked issues: 0 [].
- file evidence: 8 [ADDED docs/02_Technical_Guides/auto-warmup.md +188/-0; ADDED docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260127_MULTI_INSTANCE_WARMUP.md +290/-0; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV4.java +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #273 — feat: #218 MySQL 장애 시 Resilience 강화 - Stateless 아키텍처
- author/state/dates: zbnerd | MERGED | created 2026-01-27T08:06:18Z | closed 2026-01-27T08:06:33Z | merged yes at 2026-01-27T08:06:33Z | merge 2378494238fca11f41fa214fd138035ef83e4084.
- body: ## 관련 이슈 #218 ## 개요 MySQL 장애 발생 시에도 서비스 가용성을 유지하기 위한 Stateless Resilience 아키텍처 구현 ## 작업 내용 ### 핵심 기능 (3가지) - **Dynamic TTL Managem…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [20f1766]; linked issues: 0 [].
- file evidence: 14 [MODIFIED src/main/java/maple/expectation/global/error/CommonErrorCode.java +5/-1; ADDED src/main/java/maple/expectation/global/error/exception/CompensationSyncException.java +28/-0; ADDED src/main/java/maple/expectation/global/error/exception/MySQLFallbackException.java +28/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #274 — test: #194 단위 테스트 커버리지 확대 (68개 테스트 추가)
- author/state/dates: zbnerd | MERGED | created 2026-01-27T09:54:51Z | closed 2026-01-27T12:46:03Z | merged yes at 2026-01-27T12:46:03Z | merge 4518bde86a33ad256f4f9f603481e3a824856b0d.
- body: ## 관련 이슈 #194 ## 개요 P0 영역 (Controller, JWT/Security, Scheduler)의 단위 테스트 커버리지를 확대했습니다. 총 68개의 새로운 테스트가 추가되었습니다. ## 작업 내용 ### Contro…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 3 [256ef46, 7525e42, 02cbb68]; linked issues: 0 [].
- file evidence: 40 [MODIFIED .github/workflows/nightly.yml +218/-43; MODIFIED build.gradle +18/-3; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV1.java +7/-2].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #275 — feat: #28 #251 Lock Strategy 문서화 + AI SRE 모니터링 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-27T14:25:50Z | closed 2026-01-27T14:25:58Z | merged yes at 2026-01-27T14:25:58Z | merge 00fca3a58edd94def74b855738818e4233d9a985.
- body: ## 관련 이슈 - #28: Pessimistic Lock vs Atomic Update 선택 근거 및 도메인별 적용 기준 정리 - #251: LangChain4j 기반 AI SRE 모니터링 에이전트 + OpenTelemetry 트레…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [76473c5]; linked issues: 0 [].
- file evidence: 25 [MODIFIED build.gradle +16/-0; ADDED docs/02_Technical_Guides/lock-strategy.md +270/-0; ADDED src/main/java/maple/expectation/batch/MonitoringReportJob.java +236/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #276 — feat: Repository 쿼리 패턴 분석 기반 DB 인덱스 최적화
- author/state/dates: zbnerd | MERGED | created 2026-01-27T21:48:15Z | closed 2026-01-27T21:48:21Z | merged yes at 2026-01-27T21:48:21Z | merge 14d6103f43bdee0917da5f0a3369e846a0668ab4.
- body: ## 관련 이슈 N/A (코드베이스 개선) ## 개요 Repository 쿼리 패턴을 분석하여 누락된 인덱스를 추가하고, 전체 인덱스 전략을 문서화했습니다. ## 작업 내용 - [x] 모든 Repository 파일 분석 (9개 Rep…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [ad754a2]; linked issues: 0 [].
- file evidence: 5 [MODIFIED src/main/java/maple/expectation/domain/CharacterLike.java +4/-1; MODIFIED src/main/java/maple/expectation/domain/v2/CharacterEquipment.java +2/-0; MODIFIED src/main/java/maple/expectation/domain/v2/DonationDlq.java +2/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #277 — feat: Nexon API character/basic 연동 및 캐릭터 기본 정보 캐싱
- author/state/dates: zbnerd | MERGED | created 2026-01-27T21:50:34Z | closed 2026-01-27T21:51:00Z | merged yes at 2026-01-27T21:51:00Z | merge 6edcaf481a5af7fa7c7a2842125bcb758f506e5b.
- body: ## 관련 이슈 N/A (신규 기능) ## 개요 Nexon API `/maplestory/v1/character/basic`을 연동하여 캐릭터의 월드명, 직업명, 캐릭터 이미지를 조회하고 DB/캐시에 저장하는 기능을 구현했습니다. #…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [e5a8f08]; linked issues: 0 [].
- file evidence: 16 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +11/-0; MODIFIED src/main/java/maple/expectation/domain/v2/GameCharacter.java +49/-0; MODIFIED src/main/java/maple/expectation/dto/CubeCalculationInput.java +18/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #280 — feat: #278 Scale-out 환경 실시간 좋아요 동기화
- author/state/dates: zbnerd | MERGED | created 2026-01-28T10:55:36Z | closed 2026-01-28T10:56:00Z | merged yes at 2026-01-28T10:56:00Z | merge 371377642885af35f26cf5514dfab8635ca5b2aa.
- body: ## 관련 이슈 #278 ## 개요 Scale-out 환경에서 인스턴스 간 좋아요 수를 실시간으로 동기화하는 Pub/Sub 기능 구현 ## 작업 내용 - [x] Redis RTopic 기반 이벤트 Pub/Sub 구현 - [x] `Li…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 13 [1d730ed, d350f06, 6447313]; linked issues: 0 [].
- file evidence: 169 [MODIFIED .github/workflows/nightly.yml +218/-43; MODIFIED CLAUDE.md +10/-2; MODIFIED build.gradle +34/-3].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #281 — Release: Refresh Token 도입 (#279)
- author/state/dates: zbnerd | MERGED | created 2026-01-28T13:05:19Z | closed 2026-01-28T13:19:55Z | merged yes at 2026-01-28T13:19:55Z | merge fe8cfafa954254ff686214d5e1a00a4083797bbf.
- body: ## Summary - #279 Refresh Token 도입으로 401 복구 경로 개선 - Access Token 15분, Refresh Token 7일로 토큰 전략 변경 - Token Rotation 패턴으로 보안 강화 - 탈취 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 14 [1d730ed, d350f06, 6447313]; linked issues: 0 [].
- file evidence: 186 [MODIFIED .github/workflows/nightly.yml +218/-43; MODIFIED CLAUDE.md +10/-2; MODIFIED build.gradle +34/-3].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #285 — feat: #285 V2 좋아요 엔드포인트 P0/P1 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-29T09:12:37Z | closed 2026-01-29T09:12:43Z | merged yes at 2026-01-29T09:12:43Z | merge 05dd9f6e06b1c944a68e8cbfd8e5f94dcf0fd77c.
- body: ## 관련 이슈\n#285\n\n## 개요\nV2 좋아요 엔드포인트에 대한 P0/P1 전수 분석 결과를 반영하여 원자적 토글, 서킷브레이커 예외, 버퍼 설정 등을 개선\n\n## 작업 내용\n- [x] AtomicLikeToggleE…
- reviews/discussion: 0 []; 0.
- commits: 1 [689ee4b]; linked issues: 0 [].
- file evidence: 16 [ADDED docs/04_Reports/like-endpoint-p0p1-analysis.md +308/-0; ADDED docs/adr/ADR-015-like-endpoint-p1-acceptance.md +116/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/BufferedLikeAspect.java +9/-10].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #286 — feat: #278 TieredCache L1 Cache Coherence Pub/Sub 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-29T10:13:20Z | closed 2026-01-29T10:13:54Z | merged yes at 2026-01-29T10:13:54Z | merge 685a1d5b3ce10dc4545c9e607c0098145e6ae59a.
- body: ## 관련 이슈\n#278\n\n## 개요\nScale-out 환경에서 인스턴스 간 L1(Caffeine) 캐시 일관성을 보장하기 위해 Redis Pub/Sub 기반 캐시 무효화 메커니즘을 구현합니다.\n\n## 작업 내용\n- [x…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [056bfe4]; linked issues: 0 [].
- file evidence: 14 [ADDED src/main/java/maple/expectation/config/CacheInvalidationConfig.java +125/-0; MODIFIED src/main/java/maple/expectation/global/cache/TieredCache.java +36/-0; MODIFIED src/main/java/maple/expectation/global/cache/TieredCacheManager.java +24/-2].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #287 — feat: V4 Expectation Endpoint P0/P1 전면 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-29T12:40:13Z | closed 2026-01-29T12:40:19Z | merged yes at 2026-01-29T12:40:19Z | merge fa8e29560187071dc7ff060cd79fc68cc5065515.
- body: ## 관련 이슈\nV4 Expectation Endpoint P0/P1 분석 리포트 기반\n\n## 개요\nV4 기대값 엔드포인트의 P0(긴급) 3건 + P1(중요) 7건 결함을 전면 수정하고, God Class를 분해하여 유지보수성…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [071665b]; linked issues: 0 [].
- file evidence: 16 [MODIFIED src/main/java/maple/expectation/config/EquipmentProcessingExecutorConfig.java +7/-4; ADDED src/main/java/maple/expectation/config/ExecutorProperties.java +49/-0; MODIFIED src/main/java/maple/expectation/config/PresetCalculationExecutorConfig.java +6/-6].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #288 — feat: V4 Expectation P0/P1 개선 및 좋아요 어뷰징 방지
- author/state/dates: zbnerd | MERGED | created 2026-01-29T18:33:38Z | closed 2026-01-29T18:35:04Z | merged yes at 2026-01-29T18:35:04Z | merge fc3f4d905c17e02dd834dec669a6e2685644a971.
- body: ## 관련 이슈 V4 Expectation Endpoint P0/P1 전면 개선 ## 개요 V4 기대값 엔드포인트 성능/구조 개선, 좋아요 어뷰징 방지(accountId 기반 dedup), 존재하지 않는 캐릭터 500→404 버그 수…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 4 [071665b, 6a59b3f, 02ccf7e]; linked issues: 0 [].
- file evidence: 28 [MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +4/-4; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +9/-4; MODIFIED src/main/java/maple/expectation/domain/CharacterLike.java +12/-12].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #289 — refactor: Resilience4j 장애 격리 모듈 P0/P1 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-01-29T23:42:19Z | closed 2026-01-29T23:42:26Z | merged yes at 2026-01-29T23:42:25Z | merge f0dd91a91ac7ed7aa48065e719a13008432a714c.
- body: ## 관련 이슈\n5-Agent Council 3차 최종 합의안 (Resilience4j P0/P1)\n\n## 개요\nResilience4j 장애 격리 모듈의 P0(즉시 수정) 5건 + P1(개선) 12건 리팩토링\n\n## 작업 …
- reviews/discussion: 0 []; 0.
- commits: 1 [9b2f4fa]; linked issues: 0 [].
- file evidence: 14 [MODIFIED src/main/java/maple/expectation/config/ExecutorConfig.java +44/-1; MODIFIED src/main/java/maple/expectation/config/ResilienceConfig.java +14/-14; MODIFIED src/main/java/maple/expectation/external/impl/ResilientNexonApiClient.java +23/-43].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #290 — refactor: LogicExecutor 파이프라인 아키텍처 개선 및 테스트 정비
- author/state/dates: zbnerd | MERGED | created 2026-01-29T23:44:01Z | closed 2026-01-29T23:44:09Z | merged yes at 2026-01-29T23:44:09Z | merge 9aac41d1ed5b69712416602a3cf5c033ca0e8524.
- body: ## 관련 이슈\nResilience4j P0/P1 리팩토링 후속 작업\n\n## 개요\nLogicExecutor 파이프라인 아키텍처 개선 및 관련 테스트 정비\n\n## 작업 내용\n\n### LogicExecutor 파이프라인\n…
- reviews/discussion: 0 []; 0.
- commits: 1 [6b42021]; linked issues: 0 [].
- file evidence: 17 [MODIFIED src/main/java/maple/expectation/config/ExecutorLoggingProperties.java +5/-19; MODIFIED src/main/java/maple/expectation/global/executor/DefaultCheckedLogicExecutor.java +14/-44; MODIFIED src/main/java/maple/expectation/global/executor/DefaultLogicExecutor.java +19/-23].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #291 — refactor: TieredCache P0/P1 리팩토링 — 동시성·Scale-out·메트릭 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-30T07:38:27Z | closed 2026-01-30T07:38:34Z | merged yes at 2026-01-30T07:38:34Z | merge 7bb57ba7d3b4fe45835c1eadda93e3f84785509d.
- body: ## 관련 이슈\n5-Agent Council TieredCache 분석 합의안\n\n## 개요\nTieredCache (L1/L2) + Singleflight 모듈의 P0 4건, P1 7건을 리팩토링하여 동시성 안전성, Scale-…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [e57af3c]; linked issues: 0 [].
- file evidence: 16 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +66/-90; MODIFIED src/main/java/maple/expectation/config/CacheInvalidationConfig.java +10/-20; ADDED src/main/java/maple/expectation/config/CacheProperties.java +144/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #292 — refactor: AOP+Async 비동기 파이프라인 P0/P1 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-01-30T09:35:33Z | closed 2026-01-30T09:35:39Z | merged yes at 2026-01-30T09:35:39Z | merge 0fd2a57e4172478cd16538d5340b863191ecbeb0.
- body: ## 관련 이슈\n5-Agent Council P0/P1 전수 분석\n\n## 개요\nTwo-Phase Snapshot(Light/Full) 비동기 파이프라인을 Single-Phase CharacterSnapshot으로 통합하여 DB…
- reviews/discussion: 0 []; 0.
- commits: 1 [4365ed0]; linked issues: 0 [].
- file evidence: 3 [ADDED docs/04_Reports/aop-async-pipeline-p0-p1-refactoring-report.md +242/-0; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +50/-95; MODIFIED src/test/java/maple/expectation/service/v2/EquipmentServiceTest.java +0/-6].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #293 — test: TieredCache P0/P1 플랜 Phase 4 테스트 완료
- author/state/dates: zbnerd | MERGED | created 2026-01-30T09:55:54Z | closed 2026-01-30T09:56:01Z | merged yes at 2026-01-30T09:56:01Z | merge fe77a18dbef08d0a2a10f75af535680f16964442.
- body: ## 관련 이슈\nTieredCache P0/P1 리팩토링 플랜 Phase 4 (#291 후속)\n\n## 개요\nTieredCache P0/P1 플랜의 Phase 4 마지막 미구현 테스트 케이스(#4) 추가\n\n## 작업 내용\n…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [3850b3b]; linked issues: 0 [].
- file evidence: 1 [ADDED src/test/java/maple/expectation/service/v2/cache/TotalExpectationCacheServiceTest.java +239/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #294 — refactor: Transactional Outbox P0/P1 리팩토링 — Zombie Loop·배치 TX·Safety Net 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-30T10:22:09Z | closed 2026-01-30T10:22:17Z | merged yes at 2026-01-30T10:22:17Z | merge 9727c07197d85c0a16a9d09623d892b2a778b615.
- body: ## 관련 이슈\n#80 (Transactional Outbox Pattern)\n\n## 개요\nTransactional Outbox 모듈의 동시성·대규모 트래픽·Scale-out 관점 P0/P1 전수 분석 및 리팩토링.\n5-Ag…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [7db9c38]; linked issues: 0 [].
- file evidence: 15 [ADDED docs/04_Reports/outbox-p0-p1-refactoring-report.md +277/-0; ADDED src/main/java/maple/expectation/config/OutboxProperties.java +90/-0; MODIFIED src/main/java/maple/expectation/domain/v2/DonationDlq.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #295 — refactor: Graceful Shutdown P0/P1 리팩토링 — 중첩 실행 평탄화·메트릭·설정 외부화
- author/state/dates: zbnerd | MERGED | created 2026-01-30T11:00:41Z | closed 2026-01-30T11:05:24Z | merged yes at 2026-01-30T11:05:24Z | merge 1b8c07b76c003e41053f191350c3e0c15d5d642b.
- body: ## 관련 이슈\n#295\n\n## 개요\nGraceful Shutdown (4단계 순차 종료) 모듈의 P0/P1 리팩토링. 동시성, 대규모 트래픽, Scale-out 관점에서 3건의 P0과 10건의 P1 이슈를 수정했습니다.\n\…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 2 [4f7a625, 0c6eae8]; linked issues: 0 [].
- file evidence: 10 [MODIFIED CLAUDE.md +6/-0; ADDED docs/02_Technical_Guides/service-modules.md +483/-0; ADDED docs/04_Reports/graceful-shutdown-p0-p1-refactoring-report.md +306/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #296 — refactor: 서비스 레이어 Critical 4건 + Major 12건 리팩토링
- author/state/dates: zbnerd | MERGED | created 2026-01-30T15:30:39Z | closed 2026-01-30T15:30:47Z | merged yes at 2026-01-30T15:30:47Z | merge fe5daa1379e01af9b008f5ebce641fc48e842632.
- body: ## 관련 이슈\n코드 리뷰 기반 서비스 레이어 품질 개선 (Critical 4건 + Major 12건)\n\n## 개요\n서비스 레이어 전반의 예외 처리, 마스킹 중복, God Class 분해 등 16건의 리팩토링을 4단계(Phas…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [380befe]; linked issues: 0 [].
- file evidence: 24 [MODIFIED src/main/java/maple/expectation/controller/AdminController.java +2/-12; ADDED src/main/java/maple/expectation/global/error/exception/CachePersistenceException.java +21/-0; ADDED src/main/java/maple/expectation/global/error/exception/InvalidAdminFingerprintException.java +20/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #297 — feat: Issue #284 DoD 미충족 2건 보완 — LockHikari Pool Size 외부화 + orTimeout 추가
- author/state/dates: zbnerd | MERGED | created 2026-01-30T16:53:14Z | closed 2026-01-30T16:53:42Z | merged yes at 2026-01-30T16:53:41Z | merge 807b487c3a27fa951d44ae8a723338bf5e2b7b18.
- body: ## 관련 이슈\n#284\n\n## 개요\nIssue #284 DoD 미충족 항목 2건(LockHikariConfig Pool Size 외부화, EquipmentFetchProvider orTimeout)을 보완합니다.\n\n## …
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [c019d74]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/config/LockHikariConfig.java +14/-5; MODIFIED src/main/java/maple/expectation/provider/EquipmentFetchProvider.java +6/-1; MODIFIED src/main/resources/application-prod.yml +10/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #298 — feat: #284 Executor 강화 + #278 RReliableTopic + PDCA 보고서
- author/state/dates: zbnerd | MERGED | created 2026-01-30T17:29:13Z | closed 2026-01-30T17:29:46Z | merged yes at 2026-01-30T17:29:46Z | merge 8e4d86e1d575b1cc22fab7c6e419cd09b681694f.
- body: ## 관련 이슈 #284, #278 ## 개요 Issue #284 Executor 메트릭/TaskDecorator 강화, Issue #278 RReliableTopic at-least-once 지원, PDCA 갭 분석 및 완료 보고서…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [d8946ac, 25efe8b]; linked issues: 0 [].
- file evidence: 26 [ADDED docs/.bkit-memory.json +8/-0; ADDED docs/.pdca-snapshots/snapshot-1769774901822.json +22/-0; ADDED docs/.pdca-snapshots/snapshot-1769783591817.json +22/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #299 — release: develop → master 릴리즈 머지
- author/state/dates: zbnerd | MERGED | created 2026-01-30T17:30:36Z | closed 2026-01-30T17:30:43Z | merged yes at 2026-01-30T17:30:43Z | merge 1a51bd6f44babc4e7f96c345a7d8c6998ebfdac4.
- body: ## Summary - #284 Executor 메트릭/TaskDecorator 강화 + LockHikari Pool Size 외부화 - #278 RReliableTopic at-least-once 지원 + TieredCache L1…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 19 [bd87d1f, 05dd9f6, 685a1d5]; linked issues: 0 [].
- file evidence: 166 [MODIFIED CLAUDE.md +12/-0; ADDED docs/.bkit-memory.json +8/-0; ADDED docs/.pdca-snapshots/snapshot-1769774901822.json +22/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #301 — feat: #283 Scale-out 조건부 빈 로딩 + Docker 29.x 호환
- author/state/dates: zbnerd | MERGED | created 2026-02-01T14:20:39Z | closed 2026-02-01T14:20:47Z | merged yes at 2026-02-01T14:20:47Z | merge b680e0d784fd7aed4122f9a765ff537b32daaf53.
- body: ## 관련 이슈 #283 ## 개요 Scale-out 지원을 위한 조건부 빈 로딩 전략 구현 및 Docker 29.x Testcontainers 호환성 수정 ## 작업 내용 - [x] Redis 버퍼 기본 활성화 (matchIfMis…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [cce180f]; linked issues: 0 [].
- file evidence: 13 [MODIFIED build.gradle +1/-1; MODIFIED src/main/java/maple/expectation/config/LockHikariConfig.java +2/-1; MODIFIED src/main/java/maple/expectation/config/RedisBufferConfig.java +2/-2].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #302 — feat: #283 Scale-out Sprint 2+3 — P0/P1 Stateful 컴포넌트 분산 전환
- author/state/dates: zbnerd | MERGED | created 2026-02-01T14:51:41Z | closed 2026-02-01T16:16:49Z | merged yes at 2026-02-01T16:16:49Z | merge b428af79abb84255c7579c0a95317e5c99bb34d8.
- body: ## 관련 이슈 #283 ## 개요 Scale-out 조건부 빈 로딩 Sprint 2+3 완료. P0 8개 + P1 14개 = 22개 항목 중 Sprint 1(PR #301)에서 처리된 인프라 기반 위에, 나머지 전체 Stateful…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [a95ea15]; linked issues: 0 [].
- file evidence: 23 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +12/-0; MODIFIED src/main/java/maple/expectation/config/ExecutorConfig.java +12/-5; MODIFIED src/main/java/maple/expectation/config/LookupTableInitializer.java +4/-2].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #304 — feat: #303 환생의 불꽃 동적 계산 — 하드코딩 제거
- author/state/dates: zbnerd | MERGED | created 2026-02-02T06:46:11Z | closed 2026-02-02T06:46:16Z | merged yes at 2026-02-02T06:46:16Z | merge a573771eb32dbf4bab160fd2bc0373a78f506faa.
- body: ## 관련 이슈 #303 ## 개요 환생의 불꽃 기대값을 하드코딩에서 Nexon API 장비 데이터 기반 동적 계산으로 전환 ## 작업 내용 - [x] `FlameInputResolver`: 장비 데이터에서 불꽃 계산 입력 5종 추출…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [6416801]; linked issues: 0 [].
- file evidence: 40 [ADDED docs/02_Technical_Guides/FLAME_LOGIC.md +1653/-0; MODIFIED src/main/java/maple/expectation/dto/CubeCalculationInput.java +27/-0; MODIFIED src/main/java/maple/expectation/dto/v4/EquipmentExpectationResponseV4.java +25/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #305 — docs: #303 스케줄러 분산 락 P1-7/8/9 분석 리포트
- author/state/dates: zbnerd | MERGED | created 2026-02-02T06:47:58Z | closed 2026-02-02T06:48:00Z | merged yes at 2026-02-02T06:48:00Z | merge 34c722b082ee5316954f5889f5f36e8b79ab2a14.
- body: ## 관련 이슈 #303 ## 개요 스케줄러 분산 락 P1-7/8/9 분석 리포트 추가 ## 작업 내용 - [x] `docs/04_Reports/P1-7-8-9-scheduler-distributed-lock.md` 리포트 문서 추가…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [39337e0]; linked issues: 0 [].
- file evidence: 1 [ADDED docs/04_Reports/P1-7-8-9-scheduler-distributed-lock.md +294/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #306 — docs: 🎯 ULTRAWORK - 탑티어 문서 무결성 강화 (Phase 1 & 2 완료)
- author/state/dates: zbnerd | MERGED | created 2026-02-05T22:45:28Z | closed 2026-02-05T22:46:45Z | merged yes at 2026-02-05T22:46:45Z | merge 98862cde802d7506354c4b1cf8a6bfb929d48419.
- body: ## 📋 Pull Request 개요 이 PR은 **ULTRAWORK Mode**를 통해 수행된 문서 무결성 강화 작업(Phase 1 & 2)을 master 브랜치에 머지하기 위한 PR입니다. --- ## 🎯 작업 개요 **목표**:…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 17 [cce180f, b680e0d, a95ea15]; linked issues: 0 [].
- file evidence: 242 [MODIFIED .gitignore +4/-0; MODIFIED README.md +74/-9; MODIFIED build.gradle +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #307 — docs: Improve project evaluation score from 49/100 to 82/100 (+33 points)
- author/state/dates: zbnerd | MERGED | created 2026-02-06T06:07:52Z | closed 2026-02-06T06:08:26Z | merged yes at 2026-02-06T06:08:26Z | merge 4fdc037c86a8e5b78c836e18c5ff1bb9229ab119.
- body: ## 📊 Score Improvement Summary **Baseline:** 49/100 → **Final:** 82/100 (+33 points, +67% improvement) --- ## ✅ Changes Overview #…
- reviews/discussion: 0 []; 0.
- commits: 1 [2753676]; linked issues: 0 [].
- file evidence: 9 [ADDED CONTRIBUTING.md +608/-0; MODIFIED README.md +36/-1; ADDED SCORE_IMPROVEMENT_SUMMARY.md +271/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #308 — feat: Add Monitoring Copilot - AI-powered SRE assistant with Deep Dive Textbook
- author/state/dates: zbnerd | MERGED | created 2026-02-06T06:10:46Z | closed 2026-02-06T06:11:00Z | merged yes at 2026-02-06T06:11:00Z | merge e6e015778152abf5025b6dd7673f460542133094.
- body: AI-powered operations copilot with comprehensive documentation **Features:** - AiSreService: Claude API integration for SRE querie…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [e0d3007]; linked issues: 0 [].
- file evidence: 38 [ADDED MONITORING_COPILOT_SUMMARY.md +372/-0; MODIFIED build.gradle +3/-3; ADDED docs/02_Technical_Guides/monitoring-copilot-implementation.md +557/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #309 — feat: Add ChatGPT integration and AI-powered incident analysis
- author/state/dates: zbnerd | MERGED | created 2026-02-06T15:25:00Z | closed 2026-02-06T15:30:27Z | merged yes at 2026-02-06T15:30:27Z | merge 8adfac13d32d1f9ca544e417f47e919ea0f081e0.
- body: ## 요약 ChatGPT(GPT-4o-mini) 기반 AI SRE 기능을 추가하여 인시던트 분석과 자동 완화 제안을 제공합니다. ## 주요 변경사항 ### 1. OpenAI 통합 - **OpenAIConfiguration.java**…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [53fbf27]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/config/LockHikariConfig.java +3/-2; MODIFIED src/main/java/maple/expectation/monitoring/ai/AiSreService.java +20/-3; ADDED src/main/java/maple/expectation/monitoring/ai/config/OpenAIConfiguration.java +77/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #315 — docs: AI SRE Policy-Guarded Autonomous Loop 문서 추가 (49/100 → 90/100)
- author/state/dates: zbnerd | MERGED | created 2026-02-06T16:34:54Z | closed 2026-02-06T16:35:23Z | merged yes at 2026-02-06T16:35:23Z | merge 9feab6ec1c95d54629a7de1356c32722f40567a0.
- body: ## 개요 AI SRE 시스템의 **Policy-Guarded Autonomous Loop** 문서를 추가하여 프로젝트 점수를 **49/100에서 90/100으로 개선**했습니다 (+41점, +84% 개선). ## 변경 사항 ### …
- reviews/discussion: 0 []; 0.
- commits: 7 [8a42e36, 2753676, 4fdc037]; linked issues: 0 [].
- file evidence: 72 [ADDED CONTRIBUTING.md +608/-0; ADDED MONITORING_COPILOT_SUMMARY.md +372/-0; MODIFIED README.md +155/-4].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #317 — docs: README 서류 통과용 최적화 + Evidence Pack 강화
- author/state/dates: zbnerd | MERGED | created 2026-02-06T17:01:55Z | closed 2026-02-06T17:03:40Z | merged yes at 2026-02-06T17:03:40Z | merge 2731b0b9a05d59825897ddc64789f60ff4655339.
- body: ## 개요 README를 서류 통과용(10~30초 스캔)으로 최적화하고, Evidence Pack에 증거 링크를 강화했습니다. --- ## 주요 변경 사항 ### 1. README 신뢰도/일관성 개선 - ✅ p99 모순 해결: Tar…
- reviews/discussion: 0 []; 1 /  ### 💡 Codex Review https://github.com/zbnerd/probabili….
- commits: 11 [8a42e36, 2753676, 4fdc037]; linked issues: 0 [].
- file evidence: 2 [ADDED PORTFOLIO.md +122/-0; MODIFIED README.md +116/-21].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #318 — feat: MySQL Named Lock → Redis 분산 락 마이그레이션 (Issue #310)
- author/state/dates: zbnerd | MERGED | created 2026-02-06T21:40:31Z | closed 2026-02-06T21:40:45Z | merged yes at 2026-02-06T21:40:45Z | merge 6a527afc79147611416a8b205beba5f6e945f39a.
- body: ## 관련 이슈 #310 ## 개요 MySQL Named Lock 기반의 `GlobalLockManager`에서 발생하는 MySQLLockPool 포화 문제를 해결하기 위해 Redis 분산 락(Redisson)으로 마이그레이션합니다.…
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 2 [ccc477d, 239e253]; linked issues: 0 [].
- file evidence: 10 [MODIFIED docs/02_Technical_Guides/lock-strategy.md +143/-2; ADDED docs/adr/ADR-310-redis-lock-migration.md +455/-0; ADDED src/main/java/maple/expectation/global/lock/LockFallbackMetrics.java +150/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #319 — feat: Issue #312 모니터링 인프라 강화 및 메트릭 수집 개선
- author/state/dates: zbnerd | MERGED | created 2026-02-06T23:06:24Z | closed 2026-02-06T23:06:37Z | merged yes at 2026-02-06T23:06:37Z | merge 0fc305aad5ad5fbc57e0021fcc560e1b0ea0b5c1.
- body: ## 관련 이슈 #312 ## 개요 모니터링 인프라 강화 및 락 메트릭 수집 기능을 추가하여 시스템 가시성을 확보합니다. ## 작업 내용 - [x] Prometheus/Grafana 인프라 추가 (docker-compose.yml) …
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 2 [9e04347, bbb1979]; linked issues: 0 [].
- file evidence: 17 [MODIFIED docker-compose.yml +41/-1; ADDED docs/03_Sequence_Diagrams/STAKEHOLDER_REVIEW.md +446/-0; ADDED docs/adr/ADR-312-signal-deduplication-evidence-evaluation.md +931/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #321 — refactor: SOLID 위반 100% 개선 및 MSA 전환 계획
- author/state/dates: zbnerd | MERGED | created 2026-02-07T06:08:34Z | closed 2026-02-07T06:09:07Z | merged yes at 2026-02-07T06:09:07Z | merge 42f4cfd85c1fd225dc4b1659aaae62514ea2b623.
- body: ## 관련 이슈 없음 (Phase 3 Preparation 완료) ## 개요 SOLID 원칙 위반 100% 개선 (7개 수정) 및 MSA 전환을 위한 Kafka/EDA 도입 설계 추가 ## 작업 내용 ### 1. SOLID 위반 수정…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [c893e15]; linked issues: 0 [].
- file evidence: 693 [MODIFIED build.gradle +40/-0; MODIFIED docs/00_Start_Here/ROADMAP.md +276/-0; ADDED docs/adr/ADR-017-domain-extraction-clean-architecture.md +975/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #323 — feat: ACL 전략 패턴 구현 (Issue #300)
- author/state/dates: zbnerd | MERGED | created 2026-02-07T15:23:35Z | closed 2026-02-07T15:25:44Z | merged yes at 2026-02-07T15:25:43Z | merge 36453d53ca54fdda5235b0723be580777e575c00.
- body: ## 관련 이슈 #300 ## 개요 Anti-Corruption Layer (ACL)를 Strategy Pattern으로 구현하여 외부 REST API 제약을 내부 시스템으로부터 격리했습니다. ## 작업 내용 - [x] EventPu…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 5 [b9271ee, 0aafc28, af9a543]; linked issues: 0 [].
- file evidence: 20 [ADDED docs/04_Reports/5-agent-council-review-acl-implementation.md +659/-0; ADDED docs/04_Reports/issue-300-completion-summary.md +477/-0; ADDED docs/adr/ADR-018-acl-strategy-pattern.md +629/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #324 — test: ACL Phase 2 Benchmark Results & Configuration
- author/state/dates: zbnerd | MERGED | created 2026-02-08T04:45:54Z | closed 2026-02-08T04:46:02Z | merged yes at 2026-02-08T04:46:02Z | merge a4ae1593ab06276a1f0569c58f2743b97523da13.
- body: ## 개요 ACL Phase 2 부하테스트 완료 및 설정 복원 ## 작업 내용 - [x] Baseline vs ACL Phase 2 성능 비교 완료 - [x] application-local.yml ACL 설정 복원 (enabled:…
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 12 [2731b0b, 330aeee, 34be9a3]; linked issues: 0 [].
- file evidence: 50 [MODIFIED README.md +5/-9; MODIFIED benchmarks/wrk/acl-benchmark.lua +23/-29; MODIFIED docker/grafana/provisioning/datasources/loki.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #325 — refactor: Phase 3 클린코드 리팩토링 - AsyncUtils 및 Cube Decorator 템플릿
- author/state/dates: zbnerd | MERGED | created 2026-02-08T06:35:13Z | closed 2026-02-08T06:35:22Z | merged yes at 2026-02-08T06:35:21Z | merge 1242c64bc2a8108c47364d1bae0cf2fc9a3560bf.
- body: ## 개요 Phase 3 클린코드 리팩토링을 완료하여 AsyncUtils 유틸리티 생성 및 Cube Decorator 제네릭 템플릿을 구현했습니다. ## 작업 내용 ### 1. AsyncUtils 유틸리티 생성 - **AsyncUti…
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 2 [7cf1de4, 9114cc6]; linked issues: 0 [].
- file evidence: 53 [ADDED docs/04_Reports/cleancode-analysis-2026-02-08.md +493/-0; ADDED docs/04_Reports/cube-decorator-refactoring-report.md +250/-0; ADDED docs/04_Reports/duplicated-code-analysis.md +864/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #326 — test: 통합 테스트를 가볍고 빠른 단위 테스트로 전환
- author/state/dates: zbnerd | MERGED | created 2026-02-08T11:35:15Z | closed 2026-02-08T11:35:23Z | merged yes at 2026-02-08T11:35:23Z | merge 176255792bc173fd09f18e10eddca219fceaea96.
- body: ## 관련 이슈 #207 - Phase 3: 클린 코드 리팩토링 ## 개요 Phase 3 클린 코드 작업의 일환으로 무거운 Spring Context 기반 통합 테스트를 가볍고 빠른 Mockito 기반 단위 테스트로 전환 ## 작업 …
- reviews/discussion: 0 []; 0.
- commits: 1 [66279b2]; linked issues: 0 [].
- file evidence: 3 [ADDED src/test/java/maple/expectation/controller/AdminControllerUnitTest.java +267/-0; ADDED src/test/java/maple/expectation/monitoring/AiSreServiceTest.java +152/-0; ADDED src/test/java/maple/expectation/monitoring/MonitoringAlertServiceUnitTest.java +169/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #163 — feat(#148): TieredCache Race Condition 제거 및 L1/L2 일관성 보장
- author/state/dates: zbnerd | MERGED | created 2026-01-11T15:54:22Z | closed 2026-01-11T15:55:32Z | merged yes at 2026-01-11T15:55:32Z | merge 9060ab10deb644ce00cc2ad74f2cc0b000326b10.
- body: ## 🔗 관련 이슈 #148 ## 🗣 개요 TieredCache(L1: Caffeine, L2: Redis)에서 발생하는 Race Condition을 제거하고, 분산 Single-flight 패턴을 적용하여 Cache Stampede…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [2658287, ae27975]; linked issues: 0 [].
- file evidence: 5 [MODIFIED CLAUDE.md +197/-1; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +17/-4; MODIFIED src/main/java/maple/expectation/global/cache/TieredCache.java +260/-31].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #164 — feat(#147): LikeSyncService Redis 원자성 보장 - Lua Script 기반 데이터 유실 방지
- author/state/dates: zbnerd | MERGED | created 2026-01-12T09:41:13Z | closed 2026-01-12T09:49:35Z | merged yes at 2026-01-12T09:49:35Z | merge 18141cd9975003a350de15e9dd75fdf2253092c2.
- body: ## 🔗 관련 이슈 #147 ## 🗣 개요 LikeSyncService의 Redis 연산을 Lua Script 기반 원자적 연산으로 전환하여 데이터 유실을 방지합니다. ### 문제점 (AS-IS) 구간 현재 코드 문제점 ------ …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 5 [9060ab1, 65430af, fbe1d43]; linked issues: 0 [].
- file evidence: 16 [MODIFIED CLAUDE.md +990/-2; ADDED docs/images/Sequence Diagram_ Character Equipment.png +0/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +47/-45].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #165 — feat(#146): BYOK 인증 시스템 및 Swagger UI 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-12T14:35:08Z | closed 2026-01-12T14:59:06Z | merged yes at 2026-01-12T14:59:06Z | merge cdf6a716714495ccddd6afac78c8c73237f135eb.
- body: ## 🔗 관련 이슈 #146 ## 🗣 개요 Nexon API Key 기반 BYOK(Bring Your Own Key) 인증 시스템과 Swagger UI를 구현합니다. ## 🛠 작업 내용 ### 인증 시스템 - [x] JwtTokenP…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [f2df260, bd7f0ec]; linked issues: 0 [].
- file evidence: 43 [MODIFIED .gitignore +1/-0; MODIFIED CLAUDE.md +259/-0; MODIFIED build.gradle +11/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #174 — feat(#147): LikeSyncService 원자성 및 보상 트랜잭션 구현
- author/state/dates: zbnerd | CLOSED | created 2026-01-12T20:54:25Z | closed 2026-01-12T21:02:52Z | merged no | merge —.
- body: ## 🔗 관련 이슈 #147 ## 🗣 개요 Redis → DB 좋아요 동기화 과정에서 **데이터 유실을 완전히 방지**하는 원자성 아키텍처 구현 ## 🛠 작업 내용 ### 원자적 Fetch 전략 (Strategy Pattern) - …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 6 [9060ab1, 65430af, fbe1d43]; linked issues: 0 [].
- file evidence: 61 [MODIFIED .gitignore +1/-0; MODIFIED CLAUDE.md +538/-689; MODIFIED build.gradle +11/-0].
- resolution: closed, not merged; no application claim. Portfolio: 기능.

### PR #175 — feat(#147): LikeSyncService 원자성 및 보상 트랜잭션 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-12T21:03:10Z | closed 2026-01-12T21:03:20Z | merged yes at 2026-01-12T21:03:20Z | merge 720ef59898c0b7ff12cdb87970f13131a37ea0be.
- body: ## 🔗 관련 이슈 #147 ## 🗣 개요 Redis → DB 좋아요 동기화 과정에서 **데이터 유실을 완전히 방지**하는 원자성 아키텍처 구현 ## 🛠 작업 내용 ### 원자적 Fetch 전략 (Strategy Pattern) - …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 3 [18141cd, a627ba1, c0be085]; linked issues: 0 [].
- file evidence: 28 [MODIFIED CLAUDE.md +392/-0; MODIFIED docs/resilience.md +5/-0; MODIFIED locust/locustfile.py +67/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #176 — feat(#168): Thread Pool Backpressure - CallerRunsPolicy 제거 및 AbortPolicy 적용
- author/state/dates: zbnerd | MERGED | created 2026-01-12T23:03:33Z | closed 2026-01-12T23:04:16Z | merged yes at 2026-01-12T23:04:16Z | merge 061fadc46f0e7334464eeb7fa6cdf86532f43a20.
- body: ## 🔗 관련 이슈 #168 ## 🗣 개요 CallerRunsPolicy로 인한 톰캣 스레드 고갈 문제를 해결하고, 적절한 Backpressure 메커니즘을 구현합니다. ## 🛠 작업 내용 - [x] CallerRunsPolicy →…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [7d00a8b]; linked issues: 0 [].
- file evidence: 6 [MODIFIED .gitignore +3/-1; MODIFIED CLAUDE.md +201/-0; MODIFIED src/main/java/maple/expectation/config/ExecutorConfig.java +132/-21].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #177 — feat(#118): V2 Controller 비동기 파이프라인 전환 및 .join() 제거
- author/state/dates: zbnerd | CLOSED | created 2026-01-12T23:27:58Z | closed 2026-01-12T23:31:15Z | merged no | merge —.
- body: ## 🔗 관련 이슈 #118 ## 🗣 개요 V2 Controller 엔드포인트를 완전 비동기로 전환하고, 불필요한 `.join()` 호출을 제거합니다. 톰캣 스레드가 즉시 반환되어 RPS 240+ 처리량을 유지할 수 있습니다. ## …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 7 [9060ab1, 65430af, fbe1d43]; linked issues: 0 [].
- file evidence: 78 [MODIFIED .gitignore +3/-0; MODIFIED CLAUDE.md +1040/-2; MODIFIED build.gradle +11/-0].
- resolution: closed, not merged; no application claim. Portfolio: 기능.

### PR #178 — feat(#118): V2 Controller 비동기 파이프라인 전환 및 .join() 제거
- author/state/dates: zbnerd | MERGED | created 2026-01-12T23:31:33Z | closed 2026-01-12T23:31:52Z | merged yes at 2026-01-12T23:31:52Z | merge 10b37b1b42dff65c9cc6d5718655f2d4ebe0f403.
- body: ## 🔗 관련 이슈 #118 ## 🗣 개요 V2 Controller 엔드포인트를 완전 비동기로 전환하고, 불필요한 `.join()` 호출을 제거합니다. 톰캣 스레드가 즉시 반환되어 RPS 240+ 처리량을 유지할 수 있습니다. ## …
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [d667ba8]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +14/-4; MODIFIED src/main/java/maple/expectation/provider/EquipmentFetchProvider.java +45/-1; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +74/-6].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #179 — feat(#166): NexonDataCacheAspect 예외 변환 체계 수정 및 Flaky 테스트 해결
- author/state/dates: zbnerd | MERGED | created 2026-01-13T05:15:19Z | closed 2026-01-13T05:16:38Z | merged yes at 2026-01-13T05:16:38Z | merge 5ec7f7c4ce689b786a177da650a578de0703990e.
- body: ## 🔗 관련 이슈 #166 ## 🗣 개요 `NexonDataCacheAspect.toRuntimeException()` 메서드가 Checked Exception을 `CompletionException`으로 래핑하여 원본 타입 정보를…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [527036d]; linked issues: 0 [].
- file evidence: 5 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +47/-6; MODIFIED src/main/java/maple/expectation/global/error/CommonErrorCode.java +1/-1; MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +6/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #180 — feat(#169, #173): TimeoutException 처리 개선 및 서킷브레이커 정상화
- author/state/dates: zbnerd | MERGED | created 2026-01-13T09:40:19Z | closed 2026-01-13T09:41:15Z | merged yes at 2026-01-13T09:41:15Z | merge c033044e9bbf1b7782e8be06bca496790c92c59b.
- body: ## 🔗 관련 이슈 - #169: GameCharacterFacade TimeoutException 분류 오류 - #173: EquipmentDataResolver orTimeout() Race Condition ## 🗣 개요 Tim…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 2 [4a1df07, a41eee8]; linked issues: 0 [].
- file evidence: 10 [MODIFIED src/main/java/maple/expectation/config/LockHikariConfig.java +12/-20; MODIFIED src/main/java/maple/expectation/global/error/CommonErrorCode.java +3/-1; MODIFIED src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java +20/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #181 — feat(#151, #172): Bean Validation 전면 적용 및 CORS 보안 강화
- author/state/dates: zbnerd | MERGED | created 2026-01-13T10:44:40Z | closed 2026-01-13T10:44:50Z | merged yes at 2026-01-13T10:44:50Z | merge 0725b90e17d223dd0e344dc17ab2db042c6315f4.
- body: ## 🔗 관련 이슈 - #151 [P0]: 입력값 검증(Validation) 전면 적용 - #170 [P1]: JwtAuthenticationFilter Redis Timeout 설정 (기존 구현 검증) - #172 [P1]: Sec…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [8cd0d43]; linked issues: 0 [].
- file evidence: 11 [ADDED src/main/java/maple/expectation/config/CorsProperties.java +68/-0; MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +31/-7; MODIFIED src/main/java/maple/expectation/controller/AdminController.java +22/-8].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #182 — docs: README 성능 벤치마크 개선 및 CI 워크플로우 추가
- author/state/dates: zbnerd | MERGED | created 2026-01-14T05:34:13Z | closed 2026-01-14T05:34:22Z | merged yes at 2026-01-14T05:34:22Z | merge 2fef9abcb7c8370b2b81d6b8c1a29c283a8fb537.
- body: ## 🔗 관련 이슈 README 성능 벤치마크 개선 및 CI/문서화 작업 ## 🗣 개요 README의 성능 수치를 실측 가능한 형태로 개선하고, CI 워크플로우 및 P0 테스트를 추가합니다. ## 🛠 작업 내용 ### README 개…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [3d88c2d]; linked issues: 0 [].
- file evidence: 13 [ADDED .github/workflows/ci.yml +341/-0; MODIFIED README.md +409/-51; ADDED docs/TEST_STRATEGY.md +311/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #183 — feat: Watchdog 모드 활성화 및 테스트 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-14T05:39:39Z | closed 2026-01-14T05:39:47Z | merged yes at 2026-01-14T05:39:46Z | merge 0d94d4a18ff0e7a09040d00c60e40f8febe5b8d6.
- body: ## 🔗 관련 이슈 Watchdog 모드 활성화 (CLAUDE.md 섹션 17) 및 테스트 개선 ## 🗣 개요 Redisson 분산 락의 Watchdog 모드를 활성화하고, 테스트 코드를 개선합니다. ## 🛠 작업 내용 ### Wat…
- reviews/discussion: 0 []; 0.
- commits: 1 [79f91e3]; linked issues: 0 [].
- file evidence: 9 [MODIFIED README.md +6/-5; MODIFIED src/main/java/maple/expectation/domain/Session.java +25/-0; MODIFIED src/main/java/maple/expectation/global/cache/TieredCacheManager.java +34/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #184 — release: v2.6.0 - README 벤치마크 개선, Watchdog 모드, CI 추가
- author/state/dates: zbnerd | MERGED | created 2026-01-14T06:20:21Z | closed 2026-01-14T06:23:04Z | merged yes at 2026-01-14T06:23:04Z | merge 53c9d7c6d8df2b39fd657e9a39ff687892f4584c.
- body: ## Release v2.6.0 ### 주요 변경사항 #### README 성능 벤치마크 개선 (#182) - p95/p99 응답 시간 추가 (p50만 → p50/p95/p99) - 테스트 조건 상세 명시 (Target API, Wa…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 15 [9060ab1, 65430af, fbe1d43]; linked issues: 0 [].
- file evidence: 106 [ADDED .github/workflows/ci.yml +341/-0; MODIFIED .gitignore +10/-0; MODIFIED CLAUDE.md +1040/-2].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #185 — hotfix: README QuickStart 섹션 추가
- author/state/dates: zbnerd | MERGED | created 2026-01-14T06:40:57Z | closed 2026-01-14T06:41:04Z | merged yes at 2026-01-14T06:41:04Z | merge fb8ee67a55bd49c5c12bdda76420dc64c1574bc6.
- body: ## 🔗 관련 이슈 README 개선 (QuickStart 가이드 추가) ## 🗣 개요 Performance와 Engineering Standards 사이에 **2-3분 QuickStart** 섹션 추가 ## 🛠 작업 내용 - [x]…
- reviews/discussion: 0 []; 0.
- commits: 1 [8b06b06]; linked issues: 0 [].
- file evidence: 1 [MODIFIED README.md +32/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #186 — fix(#144-185): Codex 리뷰 기반 핵심 버그 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-14T08:56:18Z | closed 2026-01-14T08:56:31Z | merged yes at 2026-01-14T08:56:31Z | merge 8bdbcb096845b60e9955eee03c3f4d586d821f1e.
- body: ## 🔗 관련 이슈 #144, #154, #157, #160, #164, #175, #176 ## 🗣 개요 PR #144~185의 모든 Codex 자동 리뷰 코멘트를 수집하여 5개 Agent(Blue, Green, Yellow, Pu…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [25007a7]; linked issues: 0 [].
- file evidence: 8 [MODIFIED src/main/java/maple/expectation/global/concurrency/SingleFlightExecutor.java +18/-1; MODIFIED src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java +23/-0; MODIFIED src/main/java/maple/expectation/global/redis/script/LikeAtomicOperations.java +4/-4].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #187 — feat(#80-81-127): Transactional Outbox 패턴 및 멱등성 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-16T09:36:34Z | closed 2026-01-16T10:04:54Z | merged yes at 2026-01-16T10:04:54Z | merge ecd5f53b1418ad4dbff30ae42017b43a06e75979.
- body: ## 🔗 관련 이슈 - #80 Transactional Outbox Pattern - #81 격리 수준 최적화 (RR → RC) - #127 데이터 복구 멱등성 ## 🗣 개요 Financial-Grade Transactional Ou…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 5 [8bdbcb0, 3ca4d74, b37cfc0]; linked issues: 0 [].
- file evidence: 24 [MODIFIED .gitignore +5/-0; MODIFIED README.md +43/-0; MODIFIED build.gradle +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #188 — chore: develop → master 동기화
- author/state/dates: zbnerd | MERGED | created 2026-01-16T10:07:18Z | closed 2026-01-16T10:07:25Z | merged yes at 2026-01-16T10:07:25Z | merge 701afbdeb2001252c1acb68dff4b6880129bd51f.
- body: ## 🔗 관련 이슈 - #80, #81, #127 완료 후 동기화 ## 🗣 개요 develop 브랜치를 master로 동기화합니다. 🤖 Generated with [Claude Code](https://claude.com/claude…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 4 [8bdbcb0, 3ca4d74, b37cfc0]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #189 — feat(#171-119-48): LikeSync 성능 최적화 및 순환 참조 제거
- author/state/dates: zbnerd | MERGED | created 2026-01-16T10:51:32Z | closed 2026-01-16T22:24:48Z | merged yes at 2026-01-16T22:24:48Z | merge ec32ac5a7452f86b93028599f68a527ca1e7f2d3.
- body: ## 🔗 관련 이슈 Closes #171, Closes #119, Closes #48 ## 🗣 개요 5-Agent Council 합의 기반 LikeSync 성능 최적화 및 코드 품질 개선 ## 🛠 작업 내용 ### Issue #171…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 7 [19e31da, 7473eda, 85f9327]; linked issues: 0 [].
- file evidence: 30 [MODIFIED .github/workflows/ci.yml +27/-5; ADDED locust/requirements.txt +3/-0; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #190 — Release: v2.7.0
- author/state/dates: zbnerd | MERGED | created 2026-01-16T22:27:53Z | closed 2026-01-16T22:28:17Z | merged yes at 2026-01-16T22:28:17Z | merge ab2c4371d2e7ae8a010393e5d7f4f0d79b672c34.
- body: ## 🚀 Release v2.7.0 ### 주요 변경 사항 #### 1. LikeSync 성능 최적화 및 순환 참조 제거 (#189) - 순환 참조 제거 및 비동기 처리 개선 - Donation 시스템 보안 강화 (Admin fing…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [ec32ac5]; linked issues: 0 [].
- file evidence: 30 [MODIFIED .github/workflows/ci.yml +27/-5; ADDED locust/requirements.txt +3/-0; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #191 — feat(#DLQ): DLQ 관리 Admin API 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-16T23:08:20Z | closed 2026-01-16T23:08:43Z | merged yes at 2026-01-16T23:08:43Z | merge 8d0534b0aacc2caf74bb63eeaa2c562d63beb64c.
- body: ## 🔗 관련 이슈 DLQ 수동 복구 프로세스 API화 ## 🗣 개요 Dead Letter Queue(DLQ) 항목을 Admin이 직접 관리할 수 있는 REST API 구현 ## 🛠 작업 내용 - [x] `DlqAdminControl…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [9b86724]; linked issues: 0 [].
- file evidence: 10 [ADDED src/main/java/maple/expectation/controller/DlqAdminController.java +122/-0; ADDED src/main/java/maple/expectation/controller/dto/dlq/DlqDetailResponse.java +40/-0; ADDED src/main/java/maple/expectation/controller/dto/dlq/DlqEntryResponse.java +48/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #192 — feat(#152,#153,#138): Rate Limiting 분산 시스템 구현 및 CI/CD 품질 게이트 개선
- author/state/dates: zbnerd | MERGED | created 2026-01-17T00:53:29Z | closed 2026-01-17T00:55:01Z | merged yes at 2026-01-17T00:55:01Z | merge e84f55a015f508b2a463473e929448adcfa282d9.
- body: ## 🔗 관련 이슈 - #152 Rate Limiting 구현 - #153 CI/CD Quality Gate - #138 Metrics Cardinality ## 🗣 개요 Bucket4j + Redisson 기반 분산 Rate Lim…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [71ebcc5, aea8eb0]; linked issues: 0 [].
- file evidence: 25 [MODIFIED build.gradle +4/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/ObservabilityAspect.java +25/-5; MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +36/-2].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #193 — feat(#56,#14,#64): JaCoCo 테스트 커버리지 + 미사용 코드 정리 + Runbook 문서화
- author/state/dates: zbnerd | MERGED | created 2026-01-17T01:49:12Z | closed 2026-01-17T01:49:19Z | merged yes at 2026-01-17T01:49:19Z | merge bf6fa4cc4bfdb923252897a48bc7a374e9862adf.
- body: ## 🔗 관련 이슈 - #56 JaCoCo 테스트 커버리지 설정 - #14 미사용 코드 정리 - #64 DTO 네이밍/Runbook 문서화 ## 🗣 개요 JaCoCo 테스트 커버리지 도구 설정, @Deprecated 코드 삭제, Lo…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [1d0de8c]; linked issues: 0 [].
- file evidence: 4 [MODIFIED build.gradle +56/-1; ADDED docs/runbook.md +101/-0; MODIFIED src/main/java/maple/expectation/config/LockHikariConfig.java +19/-14].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #199 — feat(#195,#196,#197,#198): 방어적 프로그래밍 및 논블로킹 전환
- author/state/dates: zbnerd | MERGED | created 2026-01-17T07:45:31Z | closed 2026-01-17T10:21:45Z | merged yes at 2026-01-17T10:21:45Z | merge 319c23cd4e9bac2c7fa1949c4a52f23cfecd57ca.
- body: ## 🔗 관련 이슈 - #195 (P0): Blocking Call 제거 - #196 (P0): WebClient Timeout 설정 누락 - #197 (P1): CubeCostPolicy 입력값 검증 누락 - #198 (P0): @…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [f221005]; linked issues: 0 [].
- file evidence: 18 [ADDED src/main/java/maple/expectation/domain/v2/PotentialGrade.java +51/-0; MODIFIED src/main/java/maple/expectation/external/NexonApiClient.java +9/-1; MODIFIED src/main/java/maple/expectation/external/impl/RealNexonApiClient.java +39/-9].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #203 — fix(#200): EquipmentServiceTest 타이밍 의존성 제거
- author/state/dates: zbnerd | MERGED | created 2026-01-17T08:22:32Z | closed 2026-01-17T09:43:03Z | merged yes at 2026-01-17T09:43:03Z | merge ef28c94cc6f125baf6e9d8aa30ffd8068eaba86d.
- body: ## 🔗 관련 이슈 #200 ## 🗣 개요 `EquipmentServiceTest.getEquipmentByUserIgnAsync_NonBlocking()` 테스트의 Flaky 원인을 제거합니다. ## 🛠 작업 내용 - [x] 타이밍…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 2 [5a92052, 3104d76]; linked issues: 0 [].
- file evidence: 2 [MODIFIED .gitignore +1/-0; MODIFIED src/test/java/maple/expectation/service/v2/EquipmentServiceTest.java +4/-3].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #204 — fix(#201): ShutdownDataRecoveryIntegrationTest Redis/파일시스템 격리 강화
- author/state/dates: zbnerd | MERGED | created 2026-01-17T08:30:36Z | closed 2026-01-17T09:43:05Z | merged yes at 2026-01-17T09:43:05Z | merge f5b324a0e44e4cc0021accd63bcf708c2db5c377.
- body: ## 🔗 관련 이슈 #201 ## 🗣 개요 `ShutdownDataRecoveryIntegrationTest`의 Flaky 원인(타임아웃 부족, 파일 시스템 Race Condition)을 제거합니다. ## 🛠 작업 내용 - [x] A…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [b7ff9b2]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/test/java/maple/expectation/service/v2/shutdown/ShutdownDataRecoveryIntegrationTest.java +10/-6].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #205 — fix(#202): ResilientNexonApiClientTest Flaky 테스트 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-17T09:35:53Z | closed 2026-01-17T09:43:07Z | merged yes at 2026-01-17T09:43:07Z | merge 6b137f041211f9cbfba75062b5a12c340d7d239c.
- body: ## 🔗 관련 이슈 #202 ## 🗣 개요 `ResilientNexonApiClientTest.retryLogicTest` Flaky 테스트 수정 ## 🛠 작업 내용 - [x] `application.yml`: nexonApi ret…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [ad76872]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/resources/application.yml +1/-0; MODIFIED src/test/java/maple/expectation/external/proxy/ResilientNexonApiClientTest.java +6/-1; MODIFIED src/test/resources/application-test.yml +24/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #206 — feat(#143): Observability 인프라 구축 (Loki + Grafana + MDC Tracing)
- author/state/dates: zbnerd | MERGED | created 2026-01-17T16:21:46Z | closed 2026-01-17T18:30:32Z | merged yes at 2026-01-17T18:30:32Z | merge e6655efb1c0d0680ff8e8fa0e8dde54f3e0e1e86.
- body: ## 🔗 관련 이슈 #143 ## 🗣 개요 DevOps 관측성 인프라를 구축하여 분산 로그 수집, 시각화, 요청 추적 기능을 제공합니다. ## 🛠 작업 내용 - [x] `build.gradle`: loki4j-logback-appen…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [7111912]; linked issues: 0 [].
- file evidence: 8 [MODIFIED build.gradle +3/-0; ADDED docker-compose.observability.yml +71/-0; ADDED docker/grafana/provisioning/dashboards/application.json +214/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #211 — fix(#207): CI 테스트 안정성 개선 및 Flaky 테스트 제거
- author/state/dates: zbnerd | MERGED | created 2026-01-18T06:43:03Z | closed 2026-01-18T06:43:12Z | merged yes at 2026-01-18T06:43:12Z | merge 7dcfbed60594e5aa5d3f7805dee5143d62941561.
- body: ## 🔗 관련 이슈 #207 ## 🗣 개요 CI 통합 테스트 시간 단축 및 Flaky 테스트 완전 제거를 위한 테스트 인프라 최적화 ## 🛠 작업 내용 ### 테스트 인프라 최적화 (Phase 1~3) - [x] `SimpleRedi…
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 1 [f2f2602]; linked issues: 0 [].
- file evidence: 31 [MODIFIED CLAUDE.md +114/-1022; MODIFIED build.gradle +31/-6; ADDED docs/async-concurrency.md +250/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #212 — feat(#210): Flaky Test 추적 및 Quarantine 운영 체계 구축
- author/state/dates: zbnerd | MERGED | created 2026-01-18T08:00:38Z | closed 2026-01-18T08:02:36Z | merged yes at 2026-01-18T08:02:36Z | merge 54086f231624ba18153d163ee63adb4a98eb46f1.
- body: ## 🔗 관련 이슈 #210 ## 🗣 개요 Flaky Test 추적/로깅 시스템 및 Quarantine 운영 체계를 구축합니다. ## 🛠 작업 내용 ### 1. Gradle test-retry 플러그인 추가 - [x] `org.gra…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [b60bcf2]; linked issues: 0 [].
- file evidence: 7 [MODIFIED .github/workflows/ci.yml +12/-0; MODIFIED build.gradle +36/-4; ADDED src/test/java/maple/expectation/support/flaky/FlakyEvent.java +46/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #213 — feat(#209): MySQL Slow Query Log + Prometheus 통합 Observability 확장
- author/state/dates: zbnerd | MERGED | created 2026-01-18T08:38:08Z | closed 2026-01-18T08:39:10Z | merged yes at 2026-01-18T08:39:10Z | merge 577d408d66d84bfd9de335181cac8ce41bf8618a.
- body: ## 관련 이슈 #209 ## 개요 MySQL Slow Query Log를 Grafana에서 시각화하고, Spring Boot Actuator 메트릭을 Prometheus로 수집하여 Observability 인프라를 확장합니다. ##…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [2eb38a6]; linked issues: 0 [].
- file evidence: 8 [MODIFIED docker-compose.observability.yml +66/-3; MODIFIED docker-compose.yml +2/-0; ADDED docker/grafana/provisioning/dashboards/prometheus-metrics.json +820/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #214 — feat(#209): Prometheus 메트릭 엔드포인트 IP 기반 접근 제한
- author/state/dates: zbnerd | MERGED | created 2026-01-18T09:08:27Z | closed 2026-01-18T09:08:36Z | merged yes at 2026-01-18T09:08:36Z | merge 5fae12cf054671ee33db4e2ebb397eca98c33f1c.
- body: ## 관련 이슈 #209 ## 개요 Prometheus가 Spring Boot `/actuator/prometheus` 엔드포인트를 스크래핑할 수 있도록 IP 기반 접근 제어 추가 ## 작업 내용 - [x] `/actuator/pro…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 1 [f1b1c5c]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +11/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #217 — docs(#209): 백엔드 시스템 아키텍처 다이어그램 문서화
- author/state/dates: zbnerd | MERGED | created 2026-01-18T09:33:04Z | closed 2026-01-18T09:33:11Z | merged yes at 2026-01-18T09:33:11Z | merge f8f6a166f3460d40a1fd52582ecb5deab23930f3.
- body: ## 관련 이슈 #209 ## 개요 백엔드 시스템 아키텍처를 Mermaid 다이어그램으로 문서화하여 시스템 이해도를 높이고 온보딩을 용이하게 함. ## 작업 내용 - [x] `docs/architecture.md` 신규 생성 (11개…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [412c06d]; linked issues: 0 [].
- file evidence: 2 [MODIFIED CLAUDE.md +2/-0; ADDED docs/architecture.md +620/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #220 — docs: 17개 Chaos Test Deep Dive 시나리오 문서화
- author/state/dates: zbnerd | MERGED | created 2026-01-19T10:49:28Z | closed 2026-01-19T10:50:20Z | merged yes at 2026-01-19T10:50:20Z | merge 1b4843c3492888aee7b972d05620e5bfefcee5e1.
- body: ## Summary 5-Agent Council 프레임워크 기반으로 **17개의 극한 카오스 테스트 시나리오**를 설계하고 문서화했습니다. ## 작업 내용 ### 문서 (20개) - `docs/CHAOS_REPORT_DEEP_DIVE…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [94cf71c]; linked issues: 0 [].
- file evidence: 31 [ADDED docs/CHAOS_REPORT_DEEP_DIVE.md +206/-0; ADDED docs/chaos-tests/_template.md +435/-0; ADDED docs/chaos-tests/connection/13-half-open-hell.md +152/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #223 — feat(#221,#222): Nightmare 카오스 테스트 3종 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-19T11:25:40Z | closed 2026-01-19T11:32:02Z | merged yes at 2026-01-19T11:32:02Z | merge 02ef79ebf70b8f6c9be07f39aba5b71f07eb05c1.
- body: ## 관련 이슈 - #221 [P0] Lock Ordering 미적용으로 인한 Deadlock 발생 - #222 [P1] CallerRunsPolicy로 인한 메인 스레드 블로킹 발생 ## 개요 5-Agent Council 기반 **…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [c14e7d4, 0937d0d]; linked issues: 0 [].
- file evidence: 11 [MODIFIED docs/CHAOS_REPORT_DEEP_DIVE.md +73/-6; ADDED docs/chaos-tests/nightmare/N01-thundering-herd.md +349/-0; ADDED docs/chaos-tests/nightmare/N02-deadlock-trap.md +376/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #224 — feat(#209): Nightmare Chaos Tests N04-N06 구현
- author/state/dates: zbnerd | MERGED | created 2026-01-19T12:22:19Z | closed 2026-01-19T12:25:28Z | merged yes at 2026-01-19T12:25:28Z | merge 4e979f2b5583850b73cc68ff677ef64d893d99fd.
- body: ## 관련 이슈 #209 ## 개요 5-Agent Council 방법론을 기반으로 3개의 새로운 Nightmare 레벨 카오스 테스트를 구현했습니다. ## 작업 내용 ### N04: Connection Vampire (DB Conne…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 2 [fbdead8, 2227874]; linked issues: 0 [].
- file evidence: 10 [MODIFIED docs/CHAOS_REPORT_DEEP_DIVE.md +44/-10; ADDED docs/chaos-tests/nightmare/N04-connection-vampire.md +562/-0; ADDED docs/chaos-tests/nightmare/N05-celebrity-problem.md +581/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #232 — feat: Nightmare Chaos Tests N07-N18 구현 및 문서화
- author/state/dates: zbnerd | MERGED | created 2026-01-19T21:24:50Z | closed 2026-01-19T21:25:00Z | merged yes at 2026-01-19T21:25:00Z | merge 88a7b5deb90882b12919f475efa4d6a3fe60d381.
- body: ## 관련 이슈 #227 #228 #229 #230 #231 ## 개요 5-Agent Council 회의를 통해 Nightmare Chaos Tests N07-N18까지 12개 구현 및 문서화 완료. ## 작업 내용 - [x] N07…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [d9a5a1b]; linked issues: 0 [].
- file evidence: 33 [MODIFIED docs/CHAOS_REPORT_DEEP_DIVE.md +673/-159; MODIFIED docs/chaos-tests/nightmare/N02-deadlock-trap.md +20/-0; MODIFIED docs/chaos-tests/nightmare/N03-thread-pool-exhaustion.md +221/-221].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #234 — docs: Load Test 결과 문서화 및 Prometheus 스크래핑 설정
- author/state/dates: zbnerd | MERGED | created 2026-01-20T01:03:22Z | closed 2026-01-20T01:04:23Z | merged yes at 2026-01-20T01:04:23Z | merge 5722f1fc7a59df5218303dbd49e7e9dcd8e0f47b.
- body: ## 관련 이슈 #233 ## 개요 750명 동시 사용자, 5분간 67,148 요청 부하테스트 결과 문서화 ## 작업 내용 - [x] Load Test 리포트 추가 (`docs/chaos-tests/LOAD_TEST_REPORT_20…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [f4e0d88]; linked issues: 0 [].
- file evidence: 3 [ADDED docs/chaos-tests/E2E_VALIDATION_REPORT.md +328/-0; ADDED docs/chaos-tests/LOAD_TEST_REPORT_20250120.md +316/-0; MODIFIED src/main/resources/application.yml +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #235 — refactor: docs 폴더 구조 재편 (떼끄 스타일 넘버링)
- author/state/dates: zbnerd | MERGED | created 2026-01-20T01:12:26Z | closed 2026-01-20T01:12:32Z | merged yes at 2026-01-20T01:12:32Z | merge 7b81df342d6d8fb8367204b331b704ace338a582.
- body: ## Summary 문서 폴더 구조를 떼끄 스타일(넘버링 + 템플릿 + 아카이브)로 재편하여 **면접관의 시선을 원하는 순서로 유도**합니다. ## 변경 사항 ### 새 폴더 구조 ``` docs/ ├── 00_Start_Here/ …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [4a540a0]; linked issues: 0 [].
- file evidence: 72 [MODIFIED .gitignore +2/-4; MODIFIED CLAUDE.md +47/-20; RENAMED docs/00_Start_Here/ROADMAP.md +0/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #236 — feat: P0 Nightmare Issues 해결 (N02, N07, N09)
- author/state/dates: zbnerd | MERGED | created 2026-01-20T08:04:56Z | closed 2026-01-20T08:08:35Z | merged yes at 2026-01-20T08:08:35Z | merge 61e08be3a34e5d7880eb0cb837c03d9f0aadb598.
- body: ## 관련 이슈 - Closes #221 (N02-Deadlock Trap) - Closes #227 (N07-MDL Freeze) - Closes #228 (N09-Circular Lock) ## 개요 P0 Nightmare Iss…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 2 [7ae2d12, 0dae84e]; linked issues: 0 [].
- file evidence: 14 [ADDED docker/grafana/provisioning/dashboards/lock-metrics.json +921/-0; MODIFIED docker/prometheus/prometheus.yml +5/-0; ADDED docker/prometheus/rules/lock-alerts.yml +112/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #237 — fix: P1 Nightmare Issues 해결 (#222, #225, #226)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T02:56:10Z | closed 2026-01-21T02:56:19Z | merged yes at 2026-01-21T02:56:19Z | merge 345f2f998a926654d57c27ebd3ed53902645312b.
- body: ## 관련 이슈 - #222 CallerRunsPolicy (RESOLVED - 이미 해결됨) - #225 Timeout Hierarchy 불일치 - #226 @Transactional + .join() Connection Pool …
- reviews/discussion: 0 []; 3 / You have reached your Codex usage limits for code revie….
- commits: 4 [5bce360, a11ced8, 3027dd1]; linked issues: 0 [].
- file evidence: 10 [MODIFIED .gitattributes +3/-0; MODIFIED .gitignore +4/-1; ADDED docs/04_Reports/P1_Nightmare_Issues_Resolution_Report.md +345/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #238 — feat: P1/P2 Performance & Stability Improvements (#230, #229, #233, #219, #208)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T03:32:56Z | closed 2026-01-21T06:57:27Z | merged yes at 2026-01-21T06:57:27Z | merge f5dda8640ed70d685e59825f4dad714861d34fd2.
- body: ## Summary 5-Agent Council 만장일치 승인으로 구현된 P1/P2 성능 및 안정성 개선 사항입니다. ### 해결된 이슈 Issue Priority Status Description ------- ---------- …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 2 / You have reached your Codex usage limits for code revie….
- commits: 7 [c027eb9, 4a7089b, 69d0194]; linked issues: 0 [].
- file evidence: 17 [MODIFIED docker/mysql/conf.d/my.cnf +27/-2; ADDED docs/04_Reports/p1-p2-performance-improvements-report.md +330/-0; ADDED src/main/java/maple/expectation/config/PerCacheExecutorConfig.java +101/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #239 — fix: #231 Nightmare-17 테스트 환경 및 Request ID 길이 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-21T07:39:19Z | closed 2026-01-21T11:22:41Z | merged yes at 2026-01-21T11:22:41Z | merge 628636a7418eb15824650412c23e147a90c2f59a.
- body: ## 관련 이슈 #231 ## 개요 Nightmare-17 (PoisonPillNightmareTest) 테스트 실패 원인 분석 및 수정 ## 문제점 ### 1. Bean 설정 문제 - `IntegrationTestSupport`가 …
- reviews/discussion: 0 []; 1 / ## 📌 추가 작업: Issue #240 Equipment Data Optimization V4 구….
- commits: 3 [a11ef63, ee1ad1b, d97c06a]; linked issues: 0 [].
- file evidence: 33 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +15/-2; ADDED src/main/java/maple/expectation/config/EquipmentProcessingExecutorConfig.java +112/-0; ADDED src/main/java/maple/expectation/config/LookupTableInitializer.java +140/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #241 — refactor: P1 이슈 수정 (메트릭 추가 및 Optional 패턴 개선)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T13:45:47Z | closed 2026-01-21T13:46:19Z | merged yes at 2026-01-21T13:46:19Z | merge 77b56797acf3493f93d110a7d857ae00c1722f1c.
- body: ## 관련 이슈 #241 ## 개요 CLAUDE.md 가이드라인 준수를 위한 P1 이슈 수정 및 메트릭 추가 ## 작업 내용 ### P1 이슈 수정 Issue Description 상태 ------- ------------- ----…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 31 [d69e8b4, e535a94, 8d0534b]; linked issues: 0 [].
- file evidence: 251 [MODIFIED .gitattributes +3/-0; MODIFIED .github/workflows/ci.yml +12/-0; MODIFIED .gitignore +6/-4].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #242 — feat: V4 GZIP L1/L2 캐싱 및 복합옵션 지원 (#240)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T18:14:18Z | closed 2026-01-21T18:14:27Z | merged yes at 2026-01-21T18:14:27Z | merge 468525c053c6bd424be7a10e824550ac9a5bbb06.
- body: ## 관련 이슈 #240 ## 개요 V4 API의 캐싱 전략을 개선하고 복합 옵션 계산을 지원합니다. ## 작업 내용 - [x] L1/L2 캐시에 GZIP 압축된 전체 응답 저장 (200KB → 15~20KB) - [x] 복합 옵션 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 37 [d69e8b4, e535a94, 8d0534b]; linked issues: 0 [].
- file evidence: 19 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +17/-0; MODIFIED src/main/java/maple/expectation/config/SecurityConfig.java +6/-0; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV4.java +12/-4].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #243 — release: V4 전체 릴리즈 (Observability, Chaos Tests, Rate Limiting, V4 API)
- author/state/dates: zbnerd | MERGED | created 2026-01-21T18:18:33Z | closed 2026-01-21T18:18:42Z | merged yes at 2026-01-21T18:18:42Z | merge 696ebf895cb216f0be5d2d072e80953b60f21708.
- body: ## 관련 이슈 #240, #241, #230, #229, #233, #219, #208, #222, #225, #226, #218, #221, #209, #210, #207, #143, #195, #196, #197, #198, #…
- reviews/discussion: 0 []; 0.
- commits: 38 [d69e8b4, e535a94, 8d0534b]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #244 — fix: gradle.yml 타임아웃 설정 추가
- author/state/dates: zbnerd | MERGED | created 2026-01-21T18:28:09Z | closed 2026-01-21T18:28:16Z | merged yes at 2026-01-21T18:28:16Z | merge d6f83dcb69459d7a9652bfca297dbf491f1590d5.
- body: ## 관련 이슈 #240 ## 개요 CI/CD 워크플로우 좀비 잡 방지를 위한 타임아웃 설정 추가 ## 작업 내용 - [x] build job: timeout-minutes: 20 추가 - [x] deploy job: timeout-…
- reviews/discussion: 0 []; 0.
- commits: 1 [271f312]; linked issues: 0 [].
- file evidence: 1 [MODIFIED .github/workflows/gradle.yml +7/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #245 — perf: CI 테스트 최적화 - PR은 fastTest 적용
- author/state/dates: zbnerd | MERGED | created 2026-01-21T18:37:40Z | closed 2026-01-21T18:40:03Z | merged yes at 2026-01-21T18:40:03Z | merge f9d641c37c17050ef1a034120cf2ece870d0d769.
- body: ## 관련 이슈 #240 ## 개요 CI/CD 테스트 시간 최적화 ## 작업 내용 - [x] ci.yml: PR → `-PfastTest` (slow, sentinel, quarantine 제외) - [x] ci.yml: Push t…
- reviews/discussion: 0 []; 0.
- commits: 2 [2eaaeb8, ce591da]; linked issues: 0 [].
- file evidence: 4 [MODIFIED .github/workflows/ci.yml +4/-1; MODIFIED .github/workflows/gradle.yml +5/-1; ADDED .github/workflows/nightly.yml +102/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #246 — perf: CI Gate Unit Only - Integration 테스트 Nightly 이동
- author/state/dates: zbnerd | MERGED | created 2026-01-21T18:45:46Z | closed 2026-01-21T18:45:54Z | merged yes at 2026-01-21T18:45:54Z | merge dabb2f369d31e7eaa9b9f65527039c75a4d43146.
- body: ## 관련 이슈 #240 ## 개요 CI Gate에서 Integration 테스트 제외하여 속도 대폭 개선 ## 변경 사항 구분 변경 전 변경 후 ------ -------- -------- CI Gate Unit + Integrat…
- reviews/discussion: 0 []; 0.
- commits: 1 [c2cb421]; linked issues: 0 [].
- file evidence: 3 [MODIFIED .github/workflows/ci.yml +3/-31; MODIFIED .github/workflows/gradle.yml +3/-11; MODIFIED build.gradle +2/-2].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #247 — fix: CI 최적화 - @Tag("integration") 및 Gradle 캐싱
- author/state/dates: zbnerd | MERGED | created 2026-01-21T19:05:41Z | closed 2026-01-21T19:05:53Z | merged yes at 2026-01-21T19:05:53Z | merge 33abe50b42d1a9d3a468f7d2f5d172df20626512.
- body: ## 관련 이슈 CI/CD 최적화 ## 개요 CI 테스트 실패 수정 및 빌드 시간 최적화 ## 작업 내용 - [x] Redis 의존성 테스트에 `@Tag("integration")` 추가 (6개 파일) - [x] Gradle 캐싱 최…
- reviews/discussion: 0 []; 0.
- commits: 2 [6bd373d, 0077c32]; linked issues: 0 [].
- file evidence: 8 [MODIFIED .github/workflows/gradle.yml +3/-0; MODIFIED .github/workflows/nightly.yml +2/-0; MODIFIED src/test/java/maple/expectation/EnvironmentIntegrationTest.java +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #86 — feat: 다중 계층 캐시(L1, L2) 도입 및 Redis 운영 최적화
- author/state/dates: zbnerd | MERGED | created 2025-12-28T03:55:26Z | closed 2025-12-28T03:55:35Z | merged yes at 2025-12-28T03:55:35Z | merge 86ad338be6af88d21bb94c515f7066f13a77e1d6.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 V2/V3 API의 응답 속도 향상과 넥슨 API 호출 부하 감소를 위해 Caffeine(로컬)과 Redis(원격)를 결합한 **Tiered Cache** 구조를 도입했습니다. 또한, 개발…
- reviews/discussion: 0 []; 0.
- commits: 1 [1f3489e]; linked issues: 0 [].
- file evidence: 19 [MODIFIED docker-compose.yml +7/-1; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +65/-15; MODIFIED src/main/java/maple/expectation/external/NexonApiClient.java +3/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #87 — release: 다중 계층 캐시 도입 및 시스템 성능/안정성 강화 (v2.0.0)
- author/state/dates: zbnerd | MERGED | created 2025-12-28T03:57:14Z | closed 2025-12-28T03:59:30Z | merged yes at 2025-12-28T03:59:30Z | merge 48142d52fc5cefd318563769f64882c1194f2a75.
- body: ## 🔗 관련 이슈 - #27 : 계층형 캐시 및 아키텍처 고도화 전체 작업 - (기타 병합된 PR들: #83, #84, #85, #86) ## 🗣 개요 `develop` 브랜치에서 완료된 다중 계층 캐시 도입, 데이터 동기화 아키텍…
- reviews/discussion: 0 []; 0.
- commits: 4 [c9fa703, 5530c29, 3b85bbd]; linked issues: 0 [].
- file evidence: 27 [MODIFIED docker-compose.yml +7/-1; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +75/-7; MODIFIED src/main/java/maple/expectation/external/NexonApiClient.java +3/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #88 — refactor: Scale-out 확장을 위한 동시성 제어 및 Graceful Shutdown 구조 리팩토링
- author/state/dates: zbnerd | MERGED | created 2025-12-28T04:25:30Z | closed 2025-12-28T04:25:43Z | merged yes at 2025-12-28T04:25:43Z | merge 32ee737c3bd2ccbe50cdfc43147358350404af89.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 서버 인스턴스 증설(Scale-out) 시 발생할 수 있는 데이터 정합성 문제와 운영상의 리스크(알림 폭풍, 데이터 유실)를 해결하기 위해 시스템 전반의 동시성 제어 및 종료 로직을 리팩토…
- reviews/discussion: 0 []; 0.
- commits: 1 [1ff8717]; linked issues: 0 [].
- file evidence: 5 [ADDED src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java +45/-0; MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +2/-16; MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +7/-34].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #89 — Feature/27 graceful shutdown like buffer
- author/state/dates: zbnerd | MERGED | created 2025-12-28T04:32:30Z | closed 2025-12-28T04:39:04Z | merged yes at 2025-12-28T04:39:04Z | merge 52f576a2e2c037d9ad83731b0fc25e0f49997903.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 3 [1ff8717, 98b6c76, 32dd1fa]; linked issues: 0 [].
- file evidence: 7 [ADDED src/main/java/maple/expectation/global/shutdown/GracefulShutdownCoordinator.java +45/-0; MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +2/-16; MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +7/-37].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #90 — refactor: 비동기 캐싱 파이프라인 및 분산 락 전략 고도화
- author/state/dates: zbnerd | MERGED | created 2025-12-28T09:21:42Z | closed 2025-12-28T09:21:50Z | merged yes at 2025-12-28T09:21:50Z | merge 04c24d9bd3658943838ceea2923a0afde4a5b449.
- body: ## 🔗 관련 이슈 - 관련 이슈 없음 (이슈 미생성 상태에서 리팩토링 진행) ## 🗣 개요 외부 API(넥슨) 데이터를 처리하는 비동기 파이프라인의 성능 병목을 제거하고, 분산 시스템 환경에서의 회복 탄력성(Resilience)을 …
- reviews/discussion: 0 []; 0.
- commits: 1 [b0b9944]; linked issues: 0 [].
- file evidence: 8 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +38/-73; ADDED src/main/java/maple/expectation/global/common/function/ThrowingSupplier.java +6/-0; MODIFIED src/main/java/maple/expectation/global/lock/GuavaLockStrategy.java +5/-9].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #92 — feat: 분산 락 기반 모니터링 알림 중복 방지
- author/state/dates: zbnerd | MERGED | created 2025-12-28T09:45:06Z | closed 2025-12-28T09:45:15Z | merged yes at 2025-12-28T09:45:15Z | merge a2cb1b92913b0f12feaa6f3d4c48d96f1861ec1e.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 서버 가용성 확장(Scale-out) 시 발생할 수 있는 '알림 폭풍(Alert Storm)' 문제를 해결하기 위해 분산 락을 이용한 리더 선출 로직을 모니터링 서비스에 도입했습니다. 또한…
- reviews/discussion: 0 []; 0.
- commits: 1 [a792689]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/mornitering/MonitoringAlertService.java +30/-9; MODIFIED src/test/java/maple/expectation/mornitering/MonitoringAlertServiceTest.java +50/-28].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #93 — refactor: 인메모리 성능 수집기를 표준 Micrometer 메트릭 체계로 전환
- author/state/dates: zbnerd | MERGED | created 2025-12-28T09:49:38Z | closed 2025-12-28T09:49:52Z | merged yes at 2025-12-28T09:49:52Z | merge 5964ac016fd8071f8eb1d6045cc52a3eb51e2d17.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 기존 JVM 메모리(`LongAdder`)에 의존하던 성능 통계 수집 방식은 서버 증설(Scale-out) 시 데이터가 인스턴스별로 고립되고 휘발되는 한계가 있습니다. 이를 해결하기 위해 …
- reviews/discussion: 0 []; 0.
- commits: 1 [89e3398]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +11/-3; MODIFIED src/main/java/maple/expectation/aop/collector/PerformanceStatisticsCollector.java +35/-24].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #94 — refactor: Scale-out 확장을 위한 전역 모니터링 전환 및 성능 메트릭 표준화
- author/state/dates: zbnerd | MERGED | created 2025-12-28T10:00:59Z | closed 2025-12-28T10:01:11Z | merged yes at 2025-12-28T10:01:10Z | merge f0e14ed551a88e1159aa7ae4d33483e2913db21b.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 단일 서버 환경에 고립되어 있던 로컬 상태(인메모리 캐시, 로컬 통계)를 외부 저장소 및 표준 메트릭 체계로 전환하였습니다. 이를 통해 서버 증설(Scale-out) 시에도 데이터 정합성을…
- reviews/discussion: 0 []; 0.
- commits: 1 [01f9ffc]; linked issues: 0 [].
- file evidence: 9 [MODIFIED src/main/java/maple/expectation/global/error/CommonErrorCode.java +2/-1; MODIFIED src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java +1/-1; MODIFIED src/main/java/maple/expectation/global/error/dto/ErrorResponse.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #95 — refactor: 분산 로그 추적을 위한 Correlation ID 연동 및 MDC 구조 개선
- author/state/dates: zbnerd | MERGED | created 2025-12-28T10:04:12Z | closed 2025-12-28T10:04:18Z | merged yes at 2025-12-28T10:04:18Z | merge cca2e03f178982647c6b754f5fc43b7b47162ebc.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 서버 인스턴스가 여러 대로 확장되는 환경에서 개별적으로 생성되던 Request ID의 한계를 극복하고, 요청의 시작부터 끝까지 관통하는 '분산 추적(Distributed Tracing)' …
- reviews/discussion: 0 []; 0.
- commits: 1 [3c35b4b]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/global/filter/MDCFilter.java +27/-18].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #96 — refactor: 분산 환경 대응을 위한 데이터 정합성 확보 및 가시성 고도화
- author/state/dates: zbnerd | MERGED | created 2025-12-28T10:40:47Z | closed 2025-12-28T10:40:53Z | merged yes at 2025-12-28T10:40:53Z | merge 27a8dffce90d5668c0b21b3822cfb53b0f432691.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 단일 서버 환경의 한계를 극복하고, Scale-out 환경에서도 데이터 정합성을 유지하며 시스템 전반의 상태를 관찰할 수 있도록 아키텍처를 고도화했습니다. 특히 동시성 테스트 중 발견된 데…
- reviews/discussion: 0 []; 0.
- commits: 1 [ae7d452]; linked issues: 0 [].
- file evidence: 8 [ADDED src/main/java/maple/expectation/global/resilience/DistributedCircuitBreakerManager.java +52/-0; MODIFIED src/main/java/maple/expectation/repository/v2/RedisBufferRepository.java +16/-8; MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +62/-11].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #97 — release: 분산 환경 확장성(Scale-out) 대응 및 시스템 안정화 통합 릴리즈
- author/state/dates: zbnerd | MERGED | created 2025-12-28T10:42:03Z | closed 2025-12-28T10:45:58Z | merged yes at 2025-12-28T10:45:58Z | merge 4b326926aa10dcb2c362c7e91f8c7301a94535e6.
- body: ## 🔗 관련 이슈 - #27 (통합 작업) 및 관련 서브 이슈들 ## 🗣 개요 기존 단일 인스턴스 환경에서 발생하던 데이터 정합성 리스크와 모니터링 사각지대를 완전히 해결하고, 서버 증설(Scale-out)에 즉시 대응 가능한 **…
- reviews/discussion: 0 []; 0.
- commits: 12 [c9fa703, 5530c29, 3b85bbd]; linked issues: 0 [].
- file evidence: 48 [MODIFIED docker-compose.yml +7/-1; MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +11/-3; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +38/-73].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #99 — fix: 분산 락 경합 최적화 및 DB 커넥션 풀 고갈 문제 해결
- author/state/dates: zbnerd | MERGED | created 2025-12-29T13:43:53Z | closed 2025-12-29T13:46:43Z | merged yes at 2025-12-29T13:46:43Z | merge d54cf428f1c65afad5715791f5df05e502d50331.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 500명 동시 접속 부하 테스트 중 발생한 시스템 마비 현상(커넥션 풀 고갈 및 락 타임아웃)을 해결하기 위한 긴급 성능 최적화 작업입니다. ## 🛠 작업 내용 - **AOP 로직 다이어트**…
- reviews/discussion: 0 []; 0.
- commits: 1 [e2f30d7]; linked issues: 0 [].
- file evidence: 5 [MODIFIED build.gradle +18/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +10/-15; MODIFIED src/main/resources/application.yml +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #100 — [SYNC] hotfix 수정 사항 develop 브랜치 반영
- author/state/dates: zbnerd | MERGED | created 2025-12-29T13:53:19Z | closed 2025-12-29T13:55:50Z | merged yes at 2025-12-29T13:55:50Z | merge 27c30c4793fc70088500444ea511fb61f96d5e6e.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 `master` 브랜치에 반영된 긴급 성능 최적화(hotfix) 사항을 `develop` 브랜치에 동기화하여, 향후 개발 시 발생할 수 있는 회귀 버그를 방지합니다. ## 🛠 작업 내용 - *…
- reviews/discussion: 0 []; 0.
- commits: 3 [27a8dff, e02a879, a57b6f2]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #101 — fix(aop): 분산 락 재시도 메커니즘 도입 및 커넥션 풀 병목 현상 해결
- author/state/dates: zbnerd | MERGED | created 2025-12-29T14:04:18Z | closed 2025-12-29T14:04:56Z | merged yes at 2025-12-29T14:04:56Z | merge 7f4a51deab3430af59d645946663dee54b622d4e.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 운영 환경(`master`)의 부하 테스트 중 확인된 V3 엔진의 락 경합(Lock Contention) 및 그로 인한 DB 커넥션 풀 고갈 문제를 해결하기 위한 긴급 패치입니다. ## 🛠 작…
- reviews/discussion: 0 []; 0.
- commits: 1 [8c0652f]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +2/-0; MODIFIED src/main/resources/application.yml +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #102 — hotfix 수정 사항 develop 브랜치 반영
- author/state/dates: zbnerd | MERGED | created 2025-12-29T14:07:50Z | closed 2025-12-29T14:07:56Z | merged yes at 2025-12-29T14:07:55Z | merge 6024a43e0aa6cd488398ef7b881a069a79570788.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 `master` 브랜치에 반영된 긴급 성능 최적화(hotfix) 사항을 `develop` 브랜치에 동기화하여, 차기 배포 시 발생할 수 있는 성능 회귀 버그를 방지합니다. ## 🛠 작업 내용 …
- reviews/discussion: 0 []; 0.
- commits: 4 [27a8dff, e02a879, a57b6f2]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #103 — fix(aop): 분산 락 경합 최적화 및 가용성 확보를 위한 Fallback 전략 도입
- author/state/dates: zbnerd | MERGED | created 2025-12-29T14:24:26Z | closed 2025-12-29T14:24:39Z | merged yes at 2025-12-29T14:24:39Z | merge 5e5a14c9ef4f077e2d23cf8a485e9dd4b400ca20.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 부하 테스트(500 CCU) 중 특정 캐릭터(Hot Key)에 요청이 몰릴 때 발생하는 락 타임아웃(S002) 문제를 해결하고, 시스템의 전체 가용성을 확보하기 위한 긴급 최적화 작업입니다. …
- reviews/discussion: 0 []; 0.
- commits: 1 [c37ced5]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +41/-22; MODIFIED src/main/java/maple/expectation/global/error/exception/base/BaseException.java +6/-0; MODIFIED src/main/java/maple/expectation/global/error/exception/base/ServerBaseException.java +10/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #104 — 분산 락 경합 최적화 및 가용성 확보를 위한 Fallback 전략 도입
- author/state/dates: zbnerd | MERGED | created 2025-12-29T14:25:44Z | closed 2025-12-29T14:25:50Z | merged yes at 2025-12-29T14:25:50Z | merge 8a63e563cb9d70f75840164469a840e1cafe97f5.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 부하 테스트(500 CCU) 중 특정 캐릭터(Hot Key)에 요청이 몰릴 때 발생하는 락 타임아웃(S002) 문제를 해결하고, 시스템의 전체 가용성을 확보하기 위한 긴급 최적화 작업입니다. …
- reviews/discussion: 0 []; 0.
- commits: 5 [27a8dff, e02a879, a57b6f2]; linked issues: 0 [].
- file evidence: 0 [].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #105 — [HOTFIX] 분산 락 병목 해결 및 큰손 유저 트래픽 대응을 위한 전략적 최적화
- author/state/dates: zbnerd | MERGED | created 2025-12-29T14:50:52Z | closed 2025-12-29T14:56:33Z | merged yes at 2025-12-29T14:56:33Z | merge 33ff205be1575b45c9ae51b6f0902ebb18761493.
- body: ## 🔗 관련 이슈 #98 ## 🗣 개요 운영 환경 부하 테스트 중 발견된 '슈퍼 핫 키(특정 유저 집중 조회)' 상황에서의 락 타임아웃 및 톰캣 스레드 고갈 문제를 해결하기 위한 긴급 최적화 패치입니다. ## 🛠 작업 내용 - **…
- reviews/discussion: 0 []; 0.
- commits: 2 [4c91fae, 57db72c]; linked issues: 0 [].
- file evidence: 5 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +33/-36; MODIFIED src/main/java/maple/expectation/global/lock/GuavaLockStrategy.java +22/-4; MODIFIED src/main/java/maple/expectation/global/lock/LockStrategy.java +8/-2].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #106 — Hotfix 98 distributed lock optimization
- author/state/dates: zbnerd | MERGED | created 2025-12-29T16:40:18Z | closed 2025-12-29T16:40:25Z | merged yes at 2025-12-29T16:40:25Z | merge f416ea19a3122c76278fcb0d63fe868bcebe416b.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 4 [4c91fae, 57db72c, 2fc2d94]; linked issues: 0 [].
- file evidence: 2 [MODIFIED .github/workflows/gradle.yml +69/-110; MODIFIED src/test/resources/application.yml +12/-8].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #107 — Hotfix 98 distributed lock optimization
- author/state/dates: zbnerd | MERGED | created 2025-12-29T16:42:26Z | closed 2025-12-29T16:43:51Z | merged yes at 2025-12-29T16:43:51Z | merge bb554858bfde92b021459c78018262e454650ded.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 6 [4c91fae, 57db72c, 2fc2d94]; linked issues: 0 [].
- file evidence: 1 [MODIFIED .github/workflows/gradle.yml +110/-69].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #108 — Hotfix 98 distributed lock optimization
- author/state/dates: zbnerd | MERGED | created 2025-12-29T16:49:42Z | closed 2025-12-29T16:49:48Z | merged yes at 2025-12-29T16:49:48Z | merge 2cf66873c12832d26d2f1fcdd5d244aa2f0622f3.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 7 [4c91fae, 57db72c, 2fc2d94]; linked issues: 0 [].
- file evidence: 2 [MODIFIED .github/workflows/gradle.yml +110/-69; MODIFIED src/test/java/maple/expectation/concurrency/LikeConcurrencyTest.java +7/-12].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #109 — Fix: 긴급 버그를 수정
- author/state/dates: zbnerd | MERGED | created 2025-12-29T17:20:20Z | closed 2025-12-29T17:20:26Z | merged yes at 2025-12-29T17:20:26Z | merge cb3a79dce9c776e08e33b28cbb19c76f26a1f788.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 1 [6435a6b]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java +6/-3; MODIFIED src/test/resources/application.yml +9/-13].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #110 — feat: 분산 락 도입 및 캐릭터 조회 성능 최적화
- author/state/dates: zbnerd | MERGED | created 2025-12-29T20:19:54Z | closed 2025-12-29T20:20:08Z | merged yes at 2025-12-29T20:20:08Z | merge 3a726191ef738e7b53d0191bde610230e63eee26.
- body: ## 🔗 관련 이슈 * 없음 ## 🗣 개요 * 메이플스토리 캐릭터 정보 조회 시 발생하는 동시성 문제를 해결하고, RPS 700 이상의 높은 부하에서도 시스템 안정성을 유지하기 위한 최적화 작업을 진행했습니다. ## 🛠 작업 내용 *…
- reviews/discussion: 0 []; 0.
- commits: 1 [3d44d5d]; linked issues: 0 [].
- file evidence: 16 [MODIFIED build.gradle +0/-5; MODIFIED src/main/java/maple/expectation/aop/aspect/LockAspect.java +18/-13; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +47/-28].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #111 — perf: 다계층 캐시 아키텍처 및 비동기 저장 도입을 통한 성능 최적화 (240 RPS 달성)
- author/state/dates: zbnerd | MERGED | created 2025-12-29T22:25:01Z | closed 2025-12-29T22:32:27Z | merged yes at 2025-12-29T22:32:27Z | merge b126da47a6d8b0a330cdf9a35bde52e4ace6b2e3.
- body: ## 🔗 관련 이슈 - #98 ## 🗣 개요 - 초당 요청 수(RPS)가 110에서 정체되는 병목 현상을 해결하기 위해 캐시 아키텍처를 재설계했습니다. - 로컬 캐시(L1)와 분산 캐시(L2)를 결합하고, 무거운 DB 쓰기 작업을 비…
- reviews/discussion: 0 []; 0.
- commits: 2 [1be7792, b0f9770]; linked issues: 0 [].
- file evidence: 7 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +41/-29; MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +1/-5; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #112 — perf: 장비 데이터 캐싱 최적화 및 비동기 정합성 보완
- author/state/dates: zbnerd | MERGED | created 2025-12-29T23:33:34Z | closed 2025-12-29T23:33:40Z | merged yes at 2025-12-29T23:33:40Z | merge 455420ca170a1f2040902e0a5212a004483d2af7.
- body: ## 🔗 관련 이슈 - X ## 🗣 개요 고부하 환경(240 RPS 이상)에서 발생하는 넥슨 API의 429(Too Many Requests) 에러와 비동기 저장 시점의 데이터 불일치(404) 문제를 해결하기 위해 캐싱 전략과 영속화…
- reviews/discussion: 0 []; 2 / @codex review.
- commits: 4 [1be7792, b0f9770, dd49c5e]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +38/-47; MODIFIED src/main/java/maple/expectation/service/v2/cache/EquipmentCacheService.java +30/-52; ADDED src/main/java/maple/expectation/service/v2/worker/EquipmentDbWorker.java +38/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #114 — perf: Redis Queue와 Pub/Sub을 활용한 Request Collapsing 도입 및 넥슨 API 제한 대응
- author/state/dates: zbnerd | MERGED | created 2025-12-30T03:59:35Z | closed 2025-12-30T03:59:45Z | merged yes at 2025-12-30T03:59:45Z | merge 4c3365bb4d7e7bedb623b1304e38927af6e7e76e.
- body: ## 🔗 관련 이슈 - #113 ## 🗣 개요 고부하 환경(500 RPS)에서 발생하는 중복 API 호출과 넥슨 API의 Rate Limit(5회/초) 문제를 해결하기 위해 아키텍처를 전면 개편했습니다. 기존 폴링 기반의 대기 방식을…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 1 / @codex review.
- commits: 1 [0738859]; linked issues: 0 [].
- file evidence: 7 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +40/-56; MODIFIED src/main/java/maple/expectation/external/impl/RealNexonApiClient.java +1/-1; MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +45/-13].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #115 — [Release] v2.1.0 아키텍처 고도화 및 고부하 대응 성능 최적화 통합
- author/state/dates: zbnerd | MERGED | created 2025-12-30T05:11:47Z | closed 2025-12-30T05:15:51Z | merged yes at 2025-12-30T05:15:51Z | merge 57880f64deaf813b3bc9f03ac58d2f8f872f464d.
- body: ## 🔗 관련 이슈 - #110, #111, #112, #114 ## 🗣 개요 `develop` 브랜치에서 진행된 대규모 성능 최적화 및 아키텍처 개선 작업을 `master` 브랜치로 통합하여 정식 배포를 준비합니다. 500 RPS …
- reviews/discussion: 0 []; 0.
- commits: 9 [27a8dff, e02a879, a57b6f2]; linked issues: 0 [].
- file evidence: 22 [MODIFIED build.gradle +0/-5; MODIFIED src/main/java/maple/expectation/aop/aspect/LockAspect.java +18/-13; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +41/-35].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #116 — Rename monitoring package
- author/state/dates: zbnerd | MERGED | created 2025-12-30T21:28:04Z | closed 2025-12-30T21:28:24Z | merged yes at 2025-12-30T21:28:24Z | merge 8fd4ee42b83c296abaf8bf905428ed2242c11c9a.
- body: ## Summary - rename the misspelled monitoring package directory and update package declarations - adjust imports, pointcuts, and t…
- reviews/discussion: 0 []; 0.
- commits: 1 [9b61560]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +1/-1; RENAMED src/main/java/maple/expectation/monitoring/MonitoringAlertService.java +1/-1; MODIFIED src/test/java/maple/expectation/global/resilience/DistributedCircuitBreakerTest.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #117 — Handle null in gzip string converter
- author/state/dates: zbnerd | MERGED | created 2025-12-30T21:31:15Z | closed 2025-12-30T21:31:47Z | merged yes at 2025-12-30T21:31:47Z | merge 887164837faa0de19c2a95891dd36b61e2af6a38.
- body: ## Summary - return null from `GzipStringConverter` when given null attribute or database data - add unit tests to verify null han…
- reviews/discussion: 0 []; 0.
- commits: 1 [7f752c5]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/util/converter/GzipStringConverter.java +2/-0; ADDED src/test/java/maple/expectation/util/converter/GzipStringConverterTest.java +20/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #121 — feat: Redis 장애 대응 Graceful Shutdown 고도화 및 데이터 복구 메커니즘 구현 (#79)
- author/state/dates: zbnerd | MERGED | created 2026-01-02T06:03:42Z | closed 2026-01-02T06:03:49Z | merged yes at 2026-01-02T06:03:49Z | merge 57d359c99c0b737773258b61697c3070c9fd145d.
- body: ## 🔗 관련 이슈 #79 ## 🗣 개요 성능 향상을 위해 도입한 **Write-Behind 패턴**이 Redis 장애 상황에서 데이터 유실의 원인이 되지 않도록, 서버 종료 시점의 데이터 보호 레이어를 강화하고 자동 복구 시스템을 …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 2 [3c5aac2, 92d7f6a]; linked issues: 0 [].
- file evidence: 26 [MODIFIED .gitignore +2/-1; MODIFIED build.gradle +0/-1; ADDED claude.md +197/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #122 — Release v2.2.0: High Availability & Performance Boost
- author/state/dates: zbnerd | MERGED | created 2026-01-02T06:20:26Z | closed 2026-01-02T06:26:59Z | merged yes at 2026-01-02T06:26:59Z | merge a78652c54774041626a720dfc91a7e5fa319cd0f.
- body:  이번 버전에서는 MapleExpectation의 서비스 신뢰성을 엔터프라이즈급으로 끌어올리는 고가용성 메커니즘과 극한의 성능 최적화를 반영했습니다. ### 🌟 Major Changes 1. **Zero-Loss Graceful Sh…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 4 [4c3365b, 8fd4ee4, 8871648]; linked issues: 0 [].
- file evidence: 38 [MODIFIED .gitignore +2/-1; MODIFIED build.gradle +0/-1; ADDED claude.md +197/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #124 — fix: 장애 복구 프로세스 데이터 정합성 및 중복 결함 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-02T07:01:15Z | closed 2026-01-02T07:01:24Z | merged yes at 2026-01-02T07:01:24Z | merge efd37c6908fea286f43f28a1006df23331af96e2.
- body: ## 🔗 관련 이슈 #123 ## 🗣 개요 장애 상황(Shutdown 등) 및 복구 프로세스 중에 발생할 수 있는 2차 장애 시나리오를 방어하고, 데이터 무결성을 100% 보장하도록 로직을 보강했습니다. ## 🛠 작업 내용 - [x]…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [06431ac]; linked issues: 0 [].
- file evidence: 6 [ADDED locust/__pycache__/locustfile.cpython-38.pyc +0/-0; MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +19/-37; MODIFIED src/main/java/maple/expectation/service/v2/shutdown/ShutdownDataPersistenceService.java +39/-21].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #125 — Perf: JPA N+1 문제 해결 및 Fetch Join 기반 성능 최적화 (#57)
- author/state/dates: zbnerd | MERGED | created 2026-01-02T08:17:17Z | closed 2026-01-02T08:17:24Z | merged yes at 2026-01-02T08:17:24Z | merge 53004661e0f271dd4df63d348c7be3ffe5550872.
- body: ## 🔗 관련 이슈 - #57 ## 🗣 개요 `GameCharacter` 조회 시 연관된 장비 데이터를 지연 로딩(Lazy Loading)으로 가져오며 발생하던 N+1 문제를 `LEFT JOIN FETCH`로 해결했습니다. 또한, 대…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [b81a37f]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/domain/v2/GameCharacter.java +16/-12; MODIFIED src/main/java/maple/expectation/repository/v2/GameCharacterRepository.java +9/-0; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +65/-20].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #129 — feat: Redis 장애 격리 구현 - SSOT + Tiered Locking (#78)
- author/state/dates: zbnerd | MERGED | created 2026-01-02T10:52:03Z | closed 2026-01-02T10:54:16Z | merged yes at 2026-01-02T10:54:16Z | merge d9acdb873da1ca412a4d0c31d9e660098b29c1db.
- body: ## 🔗 관련 이슈 Closes #78 ## 🗣 개요 Redis 장애 시에도 서비스가 중단되지 않도록 **SSOT 정책**과 **Tiered Locking** 아키텍처를 구현했습니다. - **SSOT**: `@Locked` 어노테이션…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [3c11c52]; linked issues: 0 [].
- file evidence: 9 [MODIFIED src/main/java/maple/expectation/aop/annotation/Locked.java +18/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/LockAspect.java +9/-3; ADDED src/main/java/maple/expectation/config/LockHikariConfig.java +81/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #132 — [Issue #77] Redis HA 가용성 검증 회고
- author/state/dates: zbnerd | MERGED | created 2026-01-03T05:03:35Z | closed 2026-01-03T05:06:44Z | merged yes at 2026-01-03T05:06:44Z | merge 1c15d13c4553042ac23da57c266e9ef466196be8.
- body: ## 📝 ### 1. 개요 (Summary) Redis Sentinel 환경에서 Master 장애 시 시스템이 중단 없이 차선책(MySQL Fallback)으로 전환되고, 신규 Master 선출 시 자동으로 복구되는지 검증함. ###…
- reviews/discussion: 0 []; 0.
- commits: 2 [407828c, 10e9b6a]; linked issues: 0 [].
- file evidence: 17 [MODIFIED .gitignore +2/-0; MODIFIED claude.md +2/-0; MODIFIED docker-compose.yml +104/-9].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #134 — feat: Redis Sentinel 인프라 연결 안정화 및 테스트 결함 수정
- author/state/dates: zbnerd | MERGED | created 2026-01-03T06:35:55Z | closed 2026-01-03T06:36:04Z | merged yes at 2026-01-03T06:36:04Z | merge 287f7d6889d53c18e149a259fe422a5e16a1da04.
- body: ## 🔗 관련 이슈 #77 ## 🗣 개요 Redis Sentinel HA 도입 과정에서 발생한 인프라 연결 불안정성(NAT 매핑 이슈)과 Toxiproxy 제어 시 발생하는 404 에러 및 테스트 실패(Flaky Test) 문제를 해…
- reviews/discussion: 0 []; 0.
- commits: 3 [efce7b3, ddc9a0d, 9f9a925]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/config/RedissonConfig.java +59/-46; MODIFIED src/test/java/maple/expectation/support/AbstractSentinelContainerBaseTest.java +63/-99; ADDED src/test/java/maple/expectation/support/IntegrationTestSupport.java +109/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #135 — refactor: 테스트 인프라 최적화 및 Context Caching 적용
- author/state/dates: zbnerd | MERGED | created 2026-01-03T09:42:18Z | closed 2026-01-03T09:42:25Z | merged yes at 2026-01-03T09:42:25Z | merge 03d0e7eda6d6a74258f6c69b561fd6d35f5b3488.
- body: ## 🔗 관련 이슈 * Issues #133 ## 🗣 개요 * 테스트 실행 준비 단계(Instantiating)와 컨텍스트 재로딩으로 인한 개발 병목 지점을 해결했습니다. * 테스트 코드의 가독성을 저해하는 Checked Except…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [a560305]; linked issues: 0 [].
- file evidence: 23 [MODIFIED build.gradle +4/-2; MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +2/-2; MODIFIED src/test/java/maple/expectation/concurrency/LikeConcurrencyTest.java +41/-34].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #136 — [Release] v2.3.0 - Redis HA 가용성 강화 및 시스템 안정화
- author/state/dates: zbnerd | MERGED | created 2026-01-03T10:03:38Z | closed 2026-01-03T10:04:39Z | merged yes at 2026-01-03T10:04:39Z | merge 4dc6260a95935e9c1b63f37534327b3206a93cb5.
- body: ## 🗣 개요 * Redis Sentinel 도입을 통한 고가용성(HA) 환경 구축 완료. * 장애 복구 프로세스의 데이터 정합성 결함 수정 및 대규모 최적화 수행. * 테스트 인프라 구조 개선을 통해 개발 피드백 속도를 비약적으로 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 8 [efd37c6, 5300466, d9acdb8]; linked issues: 0 [].
- file evidence: 52 [MODIFIED .gitignore +2/-0; MODIFIED build.gradle +4/-2; MODIFIED claude.md +2/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #137 — Hotfix 2.3.1
- author/state/dates: zbnerd | MERGED | created 2026-01-03T10:15:13Z | closed 2026-01-03T10:15:23Z | merged yes at 2026-01-03T10:15:23Z | merge 612ed334a1206c8a53832a38c6ac0f61b32eea4c.
- body: ## 🔗 관련 이슈 * 로컬 환경 기동 시 Redis Sentinel 연결 타임아웃 이슈 ## 🗣 개요 * v2.3.0 배포 후 발견된 로컬 개발 환경의 연결성 문제와 테스트 코드의 설정 누락을 긴급 수정합니다. ## 🛠 작업 내용 …
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 10 [efd37c6, 5300466, d9acdb8]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/config/RedissonConfig.java +82/-74; MODIFIED src/test/resources/application-test.yml +4/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #140 — [Refactor] LogicExecutor 기반 예외 처리 구조화 완료 및 테스트 안정화 (Tests 82/82 Passed)
- author/state/dates: zbnerd | MERGED | created 2026-01-04T10:45:30Z | closed 2026-01-04T10:45:51Z | merged yes at 2026-01-04T10:45:51Z | merge c61e0fa0933b96f9412613e673b34e2290244057.
- body: ## 🔗 관련 이슈 - #131 ## 🗣 개요 `LogicExecutor` 도입을 통한 **Zero Try-Catch** 리팩토링을 최종 완료했습니다. 기존 서비스 레이어의 복잡한 예외 처리 로직을 함수형 템플릿으로 이관하여 가독성을…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 5 [867d54b, 5bfdd6d, ceefc62]; linked issues: 0 [].
- file evidence: 62 [RENAMED CLAUDE.md +0/-0; ADDED src/ROADMAP.md +54/-0; MODIFIED src/main/java/maple/expectation/aop/aspect/LockAspect.java +40/-43].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #141 — docs: 성능 벤치마크 리포트 추가 (RPS 235, Zero Failures)
- author/state/dates: zbnerd | MERGED | created 2026-01-04T11:41:46Z | closed 2026-01-04T11:41:57Z | merged yes at 2026-01-04T11:41:57Z | merge 814ae5d7f63537d2a7ea7eea1913423d63c670c4.
- body: ## 🔗 관련 이슈 - #131 (Refactoring: LogicExecutor & ResilientLockStrategy) - **Follow-up** ## 🗣 개요 `LogicExecutor` 도입 및 락 전략 고도화(#131,…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [4a5ee0b]; linked issues: 0 [].
- file evidence: 10 [MODIFIED CLAUDE.md +72/-5; MODIFIED README.md +5/-0; ADDED docs/PERFORMANCE.md +62/-0].
- resolution: merged; file/commit evidence above. Portfolio: 문서화.

### PR #144 —  feat: LogicExecutor Policy Pipeline 아키텍처 구현 (Issue #142)
- author/state/dates: zbnerd | MERGED | created 2026-01-07T05:31:34Z | closed 2026-01-07T14:42:54Z | merged yes at 2026-01-07T14:42:54Z | merge 5a5072945995cec24eb66e60c27cd433da3487c8.
- body: ## 🔗 관련 이슈 #142 ## 🗣 개요 LogicExecutor의 실행/관측/정리 로직을 **Policy Pipeline**으로 표준화하여, 예외 보존(Primary + suppressed), Error 우선, task-only …
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 4 [4c9fb94, 25281db, 9cd72b8]; linked issues: 0 [].
- file evidence: 54 [MODIFIED README.md +1/-1; ADDED docs/Deliberate-Over-Engineering.md +147/-0; RENAMED docs/PERFORMANCE_260105.md +0/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #154 — Release v2.4.0: Redis HA Isolation & LogicExecutor Pipeline Architecture
- author/state/dates: zbnerd | MERGED | created 2026-01-07T14:55:18Z | closed 2026-01-07T14:56:36Z | merged yes at 2026-01-07T14:56:36Z | merge baccd0a25234ade6a034655f37ccd197a7e5c51c.
- body: # Release v2.4.0: Redis HA Isolation & LogicExecutor Pipeline Architecture ## 📋 Summary 이번 **v2.4.0** 릴리스는 **시스템의 결함 내성(Fault Tole…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 16 [efd37c6, 5300466, d9acdb8]; linked issues: 0 [].
- file evidence: 90 [RENAMED CLAUDE.md +72/-5; MODIFIED README.md +5/-0; ADDED docs/Deliberate-Over-Engineering.md +147/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #155 — refactor: v1 레거시 코드 제거 (Dead Code Removal)
- author/state/dates: zbnerd | MERGED | created 2026-01-08T08:39:02Z | closed 2026-01-08T08:43:22Z | merged yes at 2026-01-08T08:43:22Z | merge fa2f4eab863e77219a44b1826caa71720fdf1da6.
- body: ## 🔗 관련 이슈 코드 품질 개선 (P0 - @Deprecated v1 API 제거) ## 🗣 개요 외부 참조가 없는 v1 레거시 코드를 전면 삭제하여 코드베이스를 정리합니다. ## 🛠 작업 내용 ### 삭제된 파일 (22개) - …
- reviews/discussion: 0 []; 0.
- commits: 1 [af4f414]; linked issues: 0 [].
- file evidence: 22 [DELETED src/main/java/maple/expectation/domain/v1/BaseTimeEntity.java +0/-24; DELETED src/main/java/maple/expectation/domain/v1/Equipment.java +0/-27; DELETED src/main/java/maple/expectation/domain/v1/Item.java +0/-43].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #156 — feat: Nexon WebClient 무한 대기 방지 및 Timeout 강제 (#145)
- author/state/dates: zbnerd | MERGED | created 2026-01-08T10:24:33Z | closed 2026-01-08T10:27:08Z | merged yes at 2026-01-08T10:27:08Z | merge 5a6823054b7a9dbda6de5b9c9206413d9a1315f7.
- body: ## 🔗 관련 이슈 #145 ## 🗣 개요 Nexon Open API WebClient에 타임아웃을 강제하여 무한 대기를 방지합니다. 금융급 상한 보장 정책을 적용하여 모든 타임아웃 값이 정합성을 유지합니다. ## 🛠 작업 내용 - …
- reviews/discussion: 0 []; 1 /  ### 💡 Codex Review https://github.com/zbnerd/MapleExpe….
- commits: 3 [baccd0a, d5a11b4, 64c0cd9]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +40/-21; MODIFIED src/main/java/maple/expectation/config/MaplestoryApiConfig.java +27/-6; ADDED src/main/java/maple/expectation/config/NexonApiProperties.java +133/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #157 — fix: Tiered Locking 비즈니스 예외 Fallback 오인 결함 수정 (#130)
- author/state/dates: zbnerd | MERGED | created 2026-01-08T12:37:36Z | closed 2026-01-08T12:38:58Z | merged yes at 2026-01-08T12:38:58Z | merge 26145977521a5406706b03dc4c3952125fa34db2.
- body: ## 🔗 관련 이슈 #130 ## 🗣 개요 Tiered Locking에서 비즈니스 예외(ClientBaseException)가 MySQL fallback을 잘못 트리거하는 정합성 결함을 수정합니다. ## 🛠 작업 내용 ### 예외 필…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 1 [dc35cab]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/global/common/function/ThrowingSupplier.java +49/-1; MODIFIED src/main/java/maple/expectation/global/lock/ResilientLockStrategy.java +183/-43; ADDED src/test/java/maple/expectation/global/lock/ResilientLockStrategyExceptionFilterTest.java +401/-0].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #159 — feat: DP 기반 큐브 기대값 엔진 구현 (#139)
- author/state/dates: zbnerd | MERGED | created 2026-01-09T08:36:13Z | closed 2026-01-09T08:38:06Z | merged yes at 2026-01-09T08:38:06Z | merge 16ed3af3a5e8e86032c5bc0d6d0c130504ac485d.
- body: ## 🔗 관련 이슈 #139 ## 🗣 개요 O(N!) 순열 기반 계산을 O(slots × target × K) DP Convolution 알고리즘으로 개선하여 큐브 기대값 계산 성능을 혁신적으로 향상시켰습니다. ## 🛠 작업 내용 #…
- reviews/discussion: 1 [COMMENTED/chatgpt-codex-connector]; 0.
- commits: 3 [8d1e4e3, 65eac1b, f5f887a]; linked issues: 0 [].
- file evidence: 29 [MODIFIED .gitignore +1/-0; MODIFIED CLAUDE.md +283/-34; MODIFIED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +51/-31].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #160 — feat(#158): Expectation API 캐시 리팩토링 및 SRP 분리
- author/state/dates: zbnerd | MERGED | created 2026-01-10T08:28:37Z | closed 2026-01-10T10:04:02Z | merged yes at 2026-01-10T10:04:02Z | merge e29e36ae7d3d440886c4c903ad99da9cfb141f57.
- body: ## 🔗 관련 이슈 #158 Expectation API 캐시 타겟 전환 #118 비동기 파이프라인 전환 ## 🗣 개요 EquipmentService의 SRP 위반을 해결하고, Expectation 결과 캐싱을 구현하여 성능을 최적화…
- reviews/discussion: 2 [COMMENTED/chatgpt-codex-connector, COMMENTED/chatgpt-codex-connector]; 1 / @codex review.
- commits: 2 [18f68ee, dddbbf3]; linked issues: 0 [].
- file evidence: 25 [MODIFIED CLAUDE.md +165/-1; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +63/-15; MODIFIED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +15/-5].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #161 — fix: Codex 리뷰 버그 수정 및 develop 브랜치 결함 해결
- author/state/dates: zbnerd | MERGED | created 2026-01-10T12:11:35Z | closed 2026-01-10T12:11:56Z | merged yes at 2026-01-10T12:11:56Z | merge fa3dcbf4505ba0c4965ce3fba9e44fb1d6a0b32f.
- body: ## 🔗 관련 이슈 - #114-#160 Codex 리뷰 버그 수정 - develop 브랜치 컴파일 결함 해결 ## 🗣 개요 PR #160 머지 후 발생한 develop 브랜치 컴파일 에러를 수정하고, 전체 PR(#114-#160)에…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 1 [65a5bce]; linked issues: 0 [].
- file evidence: 13 [MODIFIED .gitignore +2/-4; MODIFIED CLAUDE.md +43/-0; MODIFIED src/main/java/maple/expectation/config/TransactionConfig.java +18/-3].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #162 — release: v2.5.0 - Expectation 캐시 리팩토링 및 운영 안정성 강화
- author/state/dates: zbnerd | MERGED | created 2026-01-10T12:12:57Z | closed 2026-01-10T12:26:16Z | merged yes at 2026-01-10T12:26:16Z | merge dfe09b7fd4e345cd34c0b6bfca60172e730aa392.
- body: ## 🚀 Release v2.5.0 ### 주요 변경사항 #### ✨ 새로운 기능 - **DP 기반 큐브 기대값 엔진** (#159): 동적 프로그래밍 기반 큐브 확률 계산 - **Expectation API 캐시 리팩토링** (#1…
- reviews/discussion: 0 []; 1 / You have reached your Codex usage limits for code revie….
- commits: 25 [efd37c6, 5300466, d9acdb8]; linked issues: 0 [].
- file evidence: 89 [MODIFIED .gitignore +2/-3; ADDED docs/PORTFOLIO.md +770/-0; MODIFIED docs/logic_executor_policy_pipeline.md +23/-23].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #1 — test: verify branch protection rule failure
- author/state/dates: zbnerd | MERGED | created 2025-12-07T12:01:59Z | closed 2025-12-07T12:09:02Z | merged yes at 2025-12-07T12:09:02Z | merge 334953c7d58d57bc4cf1aa4f25401f6f91df6078.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 3 [7731268, 9528e03, 504109c]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +12/-1].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #2 — Test/ci fail check
- author/state/dates: zbnerd | MERGED | created 2025-12-07T12:14:14Z | closed 2025-12-07T12:18:13Z | merged yes at 2025-12-07T12:18:13Z | merge a9c644bb61722dfec088f693e9142a3a5b55be57.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 2 [b0c9d61, 027b884]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +1/-11].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #3 — [Perf] 좋아요 기능 성능 대폭 향상 (Caffeine Cache + Write-Behind 패턴 적용)
- author/state/dates: zbnerd | MERGED | created 2025-12-07T14:13:54Z | closed 2025-12-07T14:23:17Z | merged yes at 2025-12-07T14:23:17Z | merge be5f0cc943dba6adf22422f7d0f17aa653b33848.
- body: 1. 🚀 작업 배경 (Background) 기존 비관적 락(Pessimistic Lock) 방식은 데이터 정합성은 완벽하게 보장했지만, DB 레벨의 직렬화(Serialization)로 인해 동시성 처리에 물리적 한계가 있었습니다. 기…
- reviews/discussion: 0 []; 0.
- commits: 2 [0f31d8a, 95a0f87]; linked issues: 0 [].
- file evidence: 6 [MODIFIED build.gradle +2/-0; MODIFIED src/main/java/maple/expectation/ExpectationApplication.java +2/-0; ADDED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +44/-0].
- resolution: merged; file/commit evidence above. Portfolio: 성능.

### PR #4 — [Refactor] 성능 최적화를 위한 컨트롤러 버전 분리 (V1~V3)
- author/state/dates: zbnerd | MERGED | created 2025-12-07T20:27:57Z | closed 2025-12-07T20:33:10Z | merged yes at 2025-12-07T20:33:09Z | merge 994b9bb23753d1467ee72a1d86bb796b23cd383e.
- body: Why: 기존 단일 컨트롤러의 복잡도를 낮추고, 단계별 최적화 전략(Lock -> Cache -> Streaming)을 명확히 하기 위함. Changes: V1: 비관적 락을 통한 정합성 보장, 단순 DB조회 V2: Caffeine …
- reviews/discussion: 0 []; 0.
- commits: 1 [ff4fdbb]; linked issues: 0 [].
- file evidence: 4 [DELETED src/main/java/maple/expectation/controller/GameCharacterController.java +0/-172; ADDED src/main/java/maple/expectation/controller/GameCharacterControllerV1.java +52/-0; ADDED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +113/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #5 — [Refactor] 핵심 코어 아키텍처 재설계: 파싱 로직 고도화 및 캐싱 트랜잭션 격리 적용
- author/state/dates: zbnerd | MERGED | created 2025-12-11T13:13:13Z | closed 2025-12-11T13:17:17Z | merged yes at 2025-12-11T13:17:17Z | merge 42ae7a3a435a4c8a30af254667508dc667f05acd.
- body: 🚀 작업 배경 (Background) 기존 시스템은 크게 세 가지 구조적 한계가 있었습니다. 1. 파싱 취약성: JSON 필드 순서에 의존적인 switch-case 로직으로 인해, 특정 순서로 데이터가 들어오지 않으면 객체가 초기화되…
- reviews/discussion: 0 []; 0.
- commits: 4 [5d5c0d5, ec0c37c, 553a8ff]; linked issues: 0 [].
- file evidence: 22 [ADDED src/main/java/maple/expectation/aop/annotation/TraceLog.java +11/-0; ADDED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +89/-0; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +33/-31].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #6 — [Refactor] GameCharacterControllerV2 구조 개선: 비즈니스 로직을 Service로 이관
- author/state/dates: zbnerd | MERGED | created 2025-12-11T13:39:21Z | closed 2025-12-11T14:01:13Z | merged yes at 2025-12-11T14:01:13Z | merge 13af9cf9d8df31625eadef50798606f1010adb8e.
- body: 🚀 작업 배경 (Background) 기존 GameCharacterControllerV2는 HTTP 요청 처리뿐만 아니라, 데이터 변환(DTO Mapping), 흐름 제어(Loop), 비용 계산 호출 등 과도한 책임을 지고 있었습니다…
- reviews/discussion: 0 []; 0.
- commits: 3 [489ea1d, 6db585f, f29b79a]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +11/-79; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +59/-2; MODIFIED src/test/java/maple/expectation/service/v2/EquipmentServiceTest.java +12/-21].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #7 — [Refactor] DonationService 동시성 제어 개선: 비관적 락 제거 및 Atomic Update 적용
- author/state/dates: zbnerd | MERGED | created 2025-12-15T18:16:07Z | closed 2025-12-15T19:05:51Z | merged yes at 2025-12-15T19:05:51Z | merge 85b87e9dea1ac243d659c81c1c04920414a0ccc9.
- body: 🚀 작업 배경 (Background) 기존 DonationService는 데이터 정합성을 위해 **비관적 락(Pessimistic Lock)**을 사용했습니다. 하지만 100명의 유저가 동시에 요청하는 Hotspot 테스트를 진행한 …
- reviews/discussion: 1 [PENDING/zbnerd]; 2 / 🐛 Test Failure Troubleshooting Report Issue: LikeConcur….
- commits: 2 [77cf11c, 62ed267]; linked issues: 0 [].
- file evidence: 12 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +13/-8; ADDED src/main/java/maple/expectation/config/DataInitializer.java +32/-0; MODIFIED src/main/java/maple/expectation/domain/v2/GameCharacter.java +3/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #15 — feat: 장비 데이터 조회 동시성 제어(Lock) 도입 및 V3 성능 최적화
- author/state/dates: zbnerd | MERGED | created 2025-12-15T21:47:31Z | closed 2025-12-17T06:24:04Z | merged yes at 2025-12-17T06:24:04Z | merge 1dbcec1518b5b25f06851bb9dc6d1b74ae1a3713.
- body: ## 🚀 작업 배경 (Background) 기존 장비 조회 로직에서 동일한 유저(OCID)에 대해 다수의 요청이 동시에 들어올 경우, 캐시가 만료되었음에도 불구하고 모든 스레드가 외부 API를 중복 호출하는 **Thundering H…
- reviews/discussion: 0 []; 0.
- commits: 3 [8511883, 1fcb630, c7ad375]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/provider/EquipmentDataProvider.java +47/-39; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +62/-76; ADDED src/test/java/maple/expectation/provider/EquipmentDataProviderConcurrencyTest.java +105/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #19 — [Refactor/Reliability] requestId 기반 도네이션 요청 멱등성 보장 및 로직 개선 #16
- author/state/dates: zbnerd | MERGED | created 2025-12-16T12:37:21Z | closed 2025-12-16T13:18:55Z | merged yes at 2025-12-16T13:18:55Z | merge 229fd878d05efd505f21734a0494ea090fc5d196.
- body: ## 📝 개요 (Description) 본 PR은 도네이션 요청 시 발생할 수 있는 중복 결제 및 처리 오류를 방지하기 위해 `requestId`를 기반으로 멱등성(Idempotency)을 보장하는 로직을 리팩토링했습니다. 네트워크 …
- reviews/discussion: 0 []; 0.
- commits: 2 [9fc09b7, 18d90bf]; linked issues: 1 [#16/CLOSED].
- file evidence: 7 [ADDED src/main/java/maple/expectation/domain/v2/DonationHistory.java +51/-0; MODIFIED src/main/java/maple/expectation/domain/v2/Member.java +3/-1; ADDED src/main/java/maple/expectation/exception/DeveloperNotFoundException.java +7/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #20 — [Infra] Spring Profiles 도입으로 Local/Prod 환경 격리 #18
- author/state/dates: zbnerd | MERGED | created 2025-12-16T13:43:19Z | closed 2025-12-16T14:45:53Z | merged yes at 2025-12-16T14:45:53Z | merge c18da480ee2de254c80c7fbed869dbb96a821db2.
- body: ## 📝 개요 (Description) 기존 로컬 개발 환경에서 운영 DB(AWS RDS)를 직접 바라보던 구조를 개선하여, 실수로 인한 데이터 손실 리스크를 제거하고 환경별(Local/Prod) 격리성을 확보했습니다. ## 🛠️ 작…
- reviews/discussion: 0 []; 1 / <img width="743" height="98" alt="image" src="https://g….
- commits: 7 [a971cea, db97b78, 144f274]; linked issues: 1 [#18/CLOSED].
- file evidence: 7 [MODIFIED .github/workflows/gradle.yml +9/-10; MODIFIED build.gradle +1/-1; ADDED src/main/resources/application-local.yml +18/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #22 — [Feature/Alert] 도네이션 핵심 트랜잭션 실패 시 모니터링 알림 구축
- author/state/dates: zbnerd | MERGED | created 2025-12-17T05:30:08Z | closed 2025-12-17T05:33:27Z | merged yes at 2025-12-17T05:33:27Z | merge b8445966f792e33bef6466bef3a46c93f685f4bc.
- body: ## 🔗 관련 이슈 - Closes #17 ## 🗣 개요 도네이션 프로세스(`sendCoffee`) 중 DB 연결 끊김이나 락 타임아웃 등 치명적인 예외가 발생했을 때, 단순 로그 기록을 넘어 운영팀이 즉시 인지할 수 있도록 **알림…
- reviews/discussion: 0 []; 2 / 실제로 배포가되었을때 테스트할수있는 API 추가 바랍니다..
- commits: 10 [a971cea, db97b78, 144f274]; linked issues: 1 [#17/CLOSED].
- file evidence: 8 [MODIFIED .github/workflows/gradle.yml +3/-0; ADDED src/main/java/maple/expectation/controller/AlertTestController.java +30/-0; ADDED src/main/java/maple/expectation/exception/CriticalTransactionFailureException.java +14/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #23 — Refactor/8 key based lock
- author/state/dates: zbnerd | MERGED | created 2025-12-17T06:30:22Z | closed 2025-12-17T06:38:25Z | merged yes at 2025-12-17T06:38:25Z | merge 8da72d5ea7ab6ea702063fe97e5d3ce669b4371b.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 5 [8511883, 1fcb630, c7ad375]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/provider/EquipmentDataProvider.java +3/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #24 — redeploy
- author/state/dates: zbnerd | MERGED | created 2025-12-17T06:49:56Z | closed 2025-12-17T06:51:22Z | merged yes at 2025-12-17T06:51:21Z | merge a2e344c8d3ef3f456293d709dc4e931178577929.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 6 [8511883, 1fcb630, c7ad375]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/provider/EquipmentDataProvider.java +3/-0; MODIFIED src/test/java/maple/expectation/concurrency/LikeConcurrencyTest.java +1/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #25 — Refactor/8 key based lock
- author/state/dates: zbnerd | MERGED | created 2025-12-17T07:13:13Z | closed 2025-12-17T07:14:40Z | merged yes at 2025-12-17T07:14:39Z | merge 040abeab64e5da0baafc7b170a47a1d66ab48e62.
- body: ## 💥 문제 상황 (Trouble) - 운영 배포 후 캐릭터 조회 시 `java.sql.SQLException: Connection is read-only` 에러 발생. - `GameCharacterService`가 읽기 전용 트랜…
- reviews/discussion: 0 []; 0.
- commits: 7 [8511883, 1fcb630, c7ad375]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/provider/EquipmentDataProvider.java +3/-0; MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +1/-1; MODIFIED src/main/resources/application-local.yml +4/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #33 — Infra: Docker Compose 도입 및 환경별 프로파일 분리 (#32)
- author/state/dates: zbnerd | MERGED | created 2025-12-18T11:15:22Z | closed 2025-12-18T11:44:34Z | merged yes at 2025-12-18T11:44:34Z | merge bf045f0d1532ea41a6869921630eae8cab48c290.
- body: ## 🔗 관련 이슈 - Closes #32 ## 🗣 개요 ### 📌 배경 기존 Host OS에 직접 DB를 설치하는 방식은 윈도우/WSL 환경 충돌 시 데이터 유실 위험이 있고, 개발자 간 환경 불일치 문제를 야기했습니다. 이를 해결…
- reviews/discussion: 0 []; 0.
- commits: 1 [24931f9]; linked issues: 1 [#32/CLOSED].
- file evidence: 10 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #34 — Infra/32 docker db setup
- author/state/dates: zbnerd | MERGED | created 2025-12-18T11:53:46Z | closed 2025-12-18T11:53:59Z | merged yes at 2025-12-18T11:53:59Z | merge f292cdddfcd5cb080a263f93e69eae513924c256.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 2 [24931f9, 9c86523]; linked issues: 0 [].
- file evidence: 10 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #35 — Infra/32 docker db setup
- author/state/dates: zbnerd | MERGED | created 2025-12-18T12:05:17Z | closed 2025-12-18T12:06:26Z | merged yes at 2025-12-18T12:06:26Z | merge 35eac24984713219bc64106fa1866569d225a569.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 3 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 10 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #36 — Infra/32 docker db setup
- author/state/dates: zbnerd | MERGED | created 2025-12-18T12:12:20Z | closed 2025-12-18T12:14:02Z | merged yes at 2025-12-18T12:14:02Z | merge a686646e18a131bf6804b77381592d6c6b1d84f3.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 4 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 10 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #37 — Infra/32 docker db setup
- author/state/dates: zbnerd | MERGED | created 2025-12-18T12:36:51Z | closed 2025-12-18T12:36:59Z | merged yes at 2025-12-18T12:36:59Z | merge 47b21834d87f896e6792f56d49d48984be21e8cb.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 6 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 10 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #38 — Infra/32 docker db setup
- author/state/dates: zbnerd | MERGED | created 2025-12-18T12:45:26Z | closed 2025-12-18T12:45:52Z | merged yes at 2025-12-18T12:45:52Z | merge e0303653a7d377629bd4c2dee14ef3ef16ea47e2.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 7 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 11 [MODIFIED .gitignore +4/-0; MODIFIED build.gradle +1/-0; ADDED docker-compose.yml +23/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #40 — refactor: #39 Proxy 패턴 도입을 통한 캐싱 계층 분리 및 동시성 제어 개선
- author/state/dates: zbnerd | MERGED | created 2025-12-18T19:41:32Z | closed 2025-12-19T13:53:18Z | merged yes at 2025-12-19T13:53:18Z | merge ac1bf6d44c44eecf7eada3de9da61ea6b2e81aff.
- body: ## 🔗 관련 이슈 - #39 ## 🗣 개요 프로젝트 전반의 유지보수성과 확장성을 높이기 위해 두 가지 핵심 계층에 대한 리팩토링을 진행했습니다. 1. **데이터 계층**: Proxy 패턴을 도입하여 API 호출, 캐싱, 동시성 제어…
- reviews/discussion: 0 []; 2 / # PR: 데이터 계층(Proxy) 및 계산 엔진(Decorator) 통합 고도화 ## 🔗 관련 이….
- commits: 12 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 42 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +21/-36; ADDED src/main/java/maple/expectation/aop/collector/PerformanceStatisticsCollector.java +46/-0; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV1.java +6/-23].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #41 — 최초 캐릭터 생성 시 Race Condition 해결 및 동기화 고도화
- author/state/dates: zbnerd | MERGED | created 2025-12-19T14:50:56Z | closed 2025-12-19T15:10:28Z | merged yes at 2025-12-19T15:10:28Z | merge e9c19b0d8ac8d2fef0ef78ac8aa97f5b4c5bf5d1.
- body: ## PR: 최초 캐릭터 생성 시 Race Condition 해결 및 동기화 고도화 ### 🔗 관련 이슈 - [Bug/Concurrency] 최초 캐릭터 생성 시 Race Condition으로 인한 Unique 제약 위반 방지 #9 …
- reviews/discussion: 0 []; 0.
- commits: 1 [345037c]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +21/-24].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #42 — Refactor/39 proxy decorator refining
- author/state/dates: zbnerd | CLOSED | created 2025-12-19T15:11:17Z | closed 2025-12-19T15:11:28Z | merged no | merge —.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 12 [24931f9, 9c86523, f755be2]; linked issues: 0 [].
- file evidence: 42 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +21/-36; ADDED src/main/java/maple/expectation/aop/collector/PerformanceStatisticsCollector.java +46/-0; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV1.java +6/-23].
- resolution: closed, not merged; no application claim. Portfolio: 설계.

### PR #43 — Fix/9 character creation concurrency
- author/state/dates: zbnerd | MERGED | created 2025-12-19T15:15:34Z | closed 2025-12-19T15:31:03Z | merged yes at 2025-12-19T15:31:03Z | merge d4b8f3cf6eee9d27371c0bc12e9fec3516e870d6.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 4 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +21/-24; MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +16/-9].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #44 — Fix/9 character creation concurrency
- author/state/dates: zbnerd | MERGED | created 2025-12-19T15:42:38Z | closed 2025-12-19T15:44:14Z | merged yes at 2025-12-19T15:44:14Z | merge 937d4f094092db99d6c133a44fa1d916471123e4.
- body: none
- reviews/discussion: 0 []; 0.
- commits: 5 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +21/-24; MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +16/-9].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #45 — [CI/CD] 배포 시 기존 프로세스 자동 종료 로직 추가
- author/state/dates: zbnerd | MERGED | created 2025-12-19T15:49:39Z | closed 2025-12-19T15:51:04Z | merged yes at 2025-12-19T15:51:04Z | merge 3f07e63e9dd624737a76a7b97f6217ded9027712.
- body: ### 🔍 변경 배경 수동(SSH)으로 서버 프로세스를 재실행할 경우, GitHub Actions(CI/CD)에서 기존 프로세스의 PID를 추적하지 못하는 문제가 발생했습니다. 이로 인해 새로운 JAR 파일을 복사할 때 Text fi…
- reviews/discussion: 0 []; 0.
- commits: 6 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 3 [MODIFIED .github/workflows/gradle.yml +23/-14; MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +21/-24; MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +16/-9].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #50 — [1/3][Draft][Design] External Client Boundary + Resilience Policy Skeleton
- author/state/dates: zbnerd | MERGED | created 2025-12-19T21:19:10Z | closed 2025-12-22T14:11:33Z | merged yes at 2025-12-22T14:11:32Z | merge 225509bc5af0c3e98c322103f16dbe4c16116c52.
- body: # [Draft][Design] External Client Boundary + Resilience Policy Skeleton ## 관련 이슈 #49 ## 📌 Background 현재 외부 의존성 호출(예: Nexon Open AP…
- reviews/discussion: 0 []; 2 / > Draft PR로 먼저 구조/경계를 확정합니다. > 다음 PR부터 Timeout/Fallback….
- commits: 8 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 9 [MODIFIED .github/workflows/gradle.yml +23/-14; MODIFIED src/main/java/maple/expectation/external/impl/RealNexonApiClient.java +1/-1; MODIFIED src/main/java/maple/expectation/external/proxy/NexonApiCachingProxy.java +1/-2].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #51 — [2/3][Observability] 핵심 트랜잭션 가시성 확보: 메트릭, 로그 컨텍스트, 실패 시그널 도입
- author/state/dates: zbnerd | MERGED | created 2025-12-19T21:29:55Z | closed 2025-12-22T18:45:57Z | merged yes at 2025-12-22T18:45:57Z | merge 410e0f7575797f6179648df0c4f03260f6346a2a.
- body: ## 관련이슈 #49 ## 📌 Context 현재 시스템은 동시성, 정합성, 성능 측면에서 구조적 개선(PR1)을 완료했지만, 운영 관점에서 다음과 같은 한계가 존재합니다. - 장애/지연 발생 시 **어디서, 왜 느려졌는지 즉시 파악…
- reviews/discussion: 0 []; 1 / ## 📌 관련 이슈 - #49 : [Observability] requestId 기반 로그 컨텍스트….
- commits: 12 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 28 [ADDED src/main/java/maple/expectation/config/WebConfig.java +20/-0; MODIFIED src/main/java/maple/expectation/exception/CharacterNotFoundException.java +6/-8; MODIFIED src/main/java/maple/expectation/exception/CriticalTransactionFailureException.java +6/-9].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #52 — [3/3][Refactor/Scale-out] LockStrategy 추상화로 동시성 제어 구조 정리 (synchronized 제거 기반)
- author/state/dates: zbnerd | MERGED | created 2025-12-19T21:40:07Z | closed 2025-12-22T21:57:43Z | merged yes at 2025-12-22T21:57:43Z | merge b6f7c0150ab306571e0e2aa3447ed67b689b2f62.
- body: ## 🔗 관련 이슈 - 해당 사항 없음 ## 🗣 개요 기존 코드베이스의 분산된 동시성 제어 로직(`synchronized`, `intern()`, `ConcurrentHashMap`)을 **전략 패턴(Strategy Pattern)*…
- reviews/discussion: 0 []; 0.
- commits: 9 [345037c, 8283796, 875659f]; linked issues: 0 [].
- file evidence: 3 [MODIFIED .github/workflows/gradle.yml +23/-14; MODIFIED src/main/java/maple/expectation/service/v2/GameCharacterService.java +21/-24; MODIFIED src/test/java/maple/expectation/service/CubeServiceTest.java +16/-9].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #53 — [feat] 예외 계층 구조 개편 및 Global Exception Handler 구축 (#21)
- author/state/dates: zbnerd | MERGED | created 2025-12-20T13:28:38Z | closed 2025-12-20T13:30:45Z | merged yes at 2025-12-20T13:30:44Z | merge c08970f6d7347ed3f9cb49a9a2898cdf256fa726.
- body: ### 🔗 관련 이슈 * #21 ### 📌 Problem Definition (문제 정의) * 프로젝트 내 커스텀 예외들이 파편화되어 있어 일관된 에러 응답 제공이 어려움. * 외부 API(넥슨) 호출 시 발생하는 에러(400 Bad…
- reviews/discussion: 0 []; 0.
- commits: 1 [6b35e91]; linked issues: 0 [].
- file evidence: 21 [MODIFIED src/main/java/maple/expectation/exception/CharacterNotFoundException.java +6/-8; MODIFIED src/main/java/maple/expectation/exception/CriticalTransactionFailureException.java +6/-9; MODIFIED src/main/java/maple/expectation/exception/CubeDataInitializationException.java +6/-8].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #54 — #46 : [Reliability] 외부 의존성 장애 격리를 위한 Circuit Breaker 도입
- author/state/dates: zbnerd | MERGED | created 2025-12-22T19:06:59Z | closed 2025-12-22T19:32:23Z | merged yes at 2025-12-22T19:32:23Z | merge 44027ed3bf7a63ac8a8a8f429db941599cde2393.
- body: ## 📌 관련 이슈 - #46 : [Reliability] 외부 의존성 장애 격리를 위한 Circuit Breaker 도입 ## 📖 변경 배경 외부 API(Nexon)의 응답 지연이나 장애 발생 시, 우리 시스템의 워커 스레드가 타임…
- reviews/discussion: 0 []; 1 / ## 🎯 목적 시스템의 회복 탄력성(Circuit Breaker)을 확보하고, 장애 추적(MDC) ….
- commits: 4 [d1aec86, eb39758, cb6708a]; linked issues: 0 [].
- file evidence: 34 [MODIFIED build.gradle +14/-0; ADDED src/main/java/maple/expectation/aop/annotation/ObservedTransaction.java +12/-0; ADDED src/main/java/maple/expectation/aop/aspect/ObservabilityAspect.java +52/-0].
- resolution: merged; file/commit evidence above. Portfolio: 품질.

### PR #55 — [feat] 시스템 관찰 가능성(Observability) 확보 및 장애 자가 진단 알림 기능 도입
- author/state/dates: zbnerd | MERGED | created 2025-12-22T20:28:58Z | closed 2025-12-22T20:30:27Z | merged yes at 2025-12-22T20:30:27Z | merge f2323727bdb31495622e9974421ddb0e9c70bf73.
- body: ### 🔗 관련 이슈 - #29 ### 📌 Problem Definition (문제 정의) - **현상:** 외부 부하 테스트(Locust) 결과에만 의존하여 실제 운영 환경에서의 내부 상태(White-box monitoring) 파…
- reviews/discussion: 0 []; 0.
- commits: 1 [d9494d1]; linked issues: 0 [].
- file evidence: 6 [ADDED src/main/java/maple/expectation/mornitering/MonitoringAlertService.java +30/-0; MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +15/-5; MODIFIED src/main/java/maple/expectation/service/v2/cache/LikeBufferStorage.java +13/-5].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #59 — feat: Guava Striped 기반 LockStrategy 도입 및 핵심 서비스 동시성 제어 리팩토링
- author/state/dates: zbnerd | MERGED | created 2025-12-22T22:06:45Z | closed 2025-12-22T22:08:30Z | merged yes at 2025-12-22T22:08:30Z | merge dcf5a2ffa357fed0883adb1f7f06659e45d6c1de.
- body: ## 🔗 관련 이슈 - 해당 사항 없음 ## 🗣 개요 기존 코드베이스의 분산된 동시성 제어 로직(`synchronized`, `intern()`, `ConcurrentHashMap`)을 **전략 패턴(Strategy Pattern)*…
- reviews/discussion: 0 []; 0.
- commits: 1 [fce348e]; linked issues: 0 [].
- file evidence: 10 [MODIFIED build.gradle +2/-0; ADDED src/main/java/maple/expectation/aop/annotation/Locked.java +15/-0; ADDED src/main/java/maple/expectation/aop/aspect/LockAspect.java +53/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #60 — [Ops] 서버 재시작 시 데이터 유실 방지를 위한 Graceful Shutdown 구현 (#26)
- author/state/dates: zbnerd | MERGED | created 2025-12-22T22:51:13Z | closed 2025-12-22T22:52:42Z | merged yes at 2025-12-22T22:52:42Z | merge d8840de818b7f3cb3bfb690ab044658d6d06ea58.
- body: ## 🔗 관련 이슈 * Resolved #26 ## 🗣 개요 `Write-Behind` 패턴의 가장 큰 취약점인 '비정기적 서버 종료 시 데이터 유실' 문제를 해결했습니다. 스프링 부트의 **우아한 종료(Graceful Shutdow…
- reviews/discussion: 0 []; 1 / <img width="1259" height="289" alt="image" src="https:/….
- commits: 1 [65e1cef]; linked issues: 1 [#26/CLOSED].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +19/-30; ADDED src/main/java/maple/expectation/service/v2/LikeSyncService.java +51/-0; MODIFIED src/main/resources/application.yml +5/-0].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #61 — [Ops] 분산 환경에서의 스케줄러 중복 실행 방지 (Distributed Lock) (#47)
- author/state/dates: zbnerd | MERGED | created 2025-12-23T14:58:53Z | closed 2025-12-23T16:23:04Z | merged yes at 2025-12-23T16:23:04Z | merge 09cf532d9196f1b4bee53d15dc3bdb327391d323.
- body: ## 🔗 관련 이슈 * #47 ## 🗣 개요 서버가 2대 이상인 분산 환경에서 `LikeSyncScheduler`가 중복 실행되어 발생하는 데이터 정합성 이슈와 DB 부하 문제를 해결했습니다. Redis 기반의 **분산 락(Distr…
- reviews/discussion: 0 []; 1 / <img width="1770" height="917" alt="image" src="https:/….
- commits: 3 [44fda94, 71bdcc7, 54e61ff]; linked issues: 0 [].
- file evidence: 9 [MODIFIED .github/workflows/gradle.yml +11/-0; MODIFIED build.gradle +2/-0; MODIFIED docker-compose.yml +12/-2].
- resolution: merged; file/commit evidence above. Portfolio: 운영.

### PR #65 — [Architecture/Security] 순환 참조 해결 및 관리자 API 보안 격리 (#62)
- author/state/dates: zbnerd | MERGED | created 2025-12-23T16:46:32Z | closed 2025-12-23T16:48:32Z | merged yes at 2025-12-23T16:48:32Z | merge 005d4e95536fd53eacf24e8483eb8e4d9fc41554.
- body: ## 🔗 관련 이슈 - #62 ## 🗣 개요 시스템 기동 시 발생할 수 있는 DI 순환 참조 결함을 제거하고, 운영 환경에서 관리자 전용 API 및 민감 정보(Webhook URL)가 노출되는 보안 취약점을 개선했습니다. ## 🛠 작…
- reviews/discussion: 0 []; 0.
- commits: 1 [71b2e72]; linked issues: 0 [].
- file evidence: 4 [MODIFIED src/main/java/maple/expectation/controller/AlertTestController.java +2/-0; MODIFIED src/main/java/maple/expectation/service/v2/alert/DiscordAlertService.java +7/-3; MODIFIED src/main/java/maple/expectation/service/v2/impl/CubeServiceImpl.java +1/-1].
- resolution: merged; file/commit evidence above. Portfolio: 결함·보안.

### PR #66 — [Stability] LoggingAspect 성능 통계 메모리 누수 해결 및 O(1) 구조 개선 (#10)
- author/state/dates: zbnerd | MERGED | created 2025-12-23T16:59:00Z | closed 2025-12-23T17:02:21Z | merged yes at 2025-12-23T17:02:21Z | merge 3225c550aa9f5415687af4e644d44cc161a24609.
- body: ## 🔗 관련 이슈 - #10 ## 🗣 개요 `LoggingAspect`와 `PerformanceStatisticsCollector`에서 모든 호출 내역을 메모리에 유지함에 따라 발생할 수 있는 OOM(Out Of Memory) 위험…
- reviews/discussion: 0 []; 0.
- commits: 1 [fecfce5]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/aop/aspect/LoggingAspect.java +7/-15; MODIFIED src/main/java/maple/expectation/aop/collector/PerformanceStatisticsCollector.java +24/-28; MODIFIED src/test/java/maple/expectation/aop/ConcurrencyStatsExtension.java +8/-16].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #67 — [Reliability] 좋아요 동기화 실패 대응을 위한 재시도 및 알림 시스템 구축 (#13)
- author/state/dates: zbnerd | MERGED | created 2025-12-23T17:45:50Z | closed 2025-12-23T17:48:01Z | merged yes at 2025-12-23T17:48:01Z | merge 9cb3a051747d9c1c3927baacca3940be19fb5a6d.
- body: ## 🔗 관련 이슈 - #13 ## 🗣 개요 좋아요 동기화 과정에서 발생할 수 있는 DB 장애 및 네트워크 이슈로부터 데이터를 보호하기 위해 재시도(Retry) 메커니즘과 실시간 장애 전파 체계를 구축했습니다. ## 🛠 작업 내용 -…
- reviews/discussion: 0 []; 0.
- commits: 1 [6b85c5b]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/service/v2/LikeSyncService.java +49/-12; ADDED src/test/java/maple/expectation/service/v2/LikeSyncServiceTest.java +95/-0].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #68 — [feat] 외부 API 장애 대응을 위한 회복 탄력성(Resilience) 구조 구현
- author/state/dates: zbnerd | MERGED | created 2025-12-23T18:37:28Z | closed 2025-12-23T18:41:54Z | merged yes at 2025-12-23T18:41:54Z | merge d1eaa1485f2174b51f63f7fcca2fa5d8f50cc61b.
- body: ## 🔗 관련 이슈 * Issues #58 ## 🗣 개요 외부 API(넥슨) 의존성 장애 시 서비스 전체가 마비되는 것을 방지하기 위해 **회복 탄력성(Resilience)** 설계를 도입했습니다. 비동기 파이프라인과 서킷 브레이커를…
- reviews/discussion: 0 []; 0.
- commits: 2 [bbbe5df, 85f3ddd]; linked issues: 0 [].
- file evidence: 12 [ADDED docs/resilience.md +31/-0; MODIFIED src/main/java/maple/expectation/external/NexonApiClient.java +2/-4; MODIFIED src/main/java/maple/expectation/external/impl/RealNexonApiClient.java +7/-19].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #69 — refactor: AOP 기반 캐싱 전략 도입 및 예외 처리 체계 고도화
- author/state/dates: zbnerd | MERGED | created 2025-12-25T23:13:50Z | closed 2025-12-25T23:15:59Z | merged yes at 2025-12-25T23:15:59Z | merge 57ba1eca9ad603d4f9de329fc206fdbaf6db2379.
- body: ## 🔗 관련 이슈 - 없음 ## 🗣 개요 프로젝트 전반에 흩어져 있던 수동 캐싱 프록시 로직을 스프링 AOP와 세분화된 도메인 예외 체계로 전면 리팩토링했습니다. 이를 통해 비즈니스 로직의 순수성을 높이고 유지보수성을 극대화했습니다…
- reviews/discussion: 0 []; 0.
- commits: 1 [16c8122]; linked issues: 0 [].
- file evidence: 22 [ADDED src/main/java/maple/expectation/aop/annotation/BufferedLike.java +15/-0; ADDED src/main/java/maple/expectation/aop/annotation/NexonDataCache.java +14/-0; ADDED src/main/java/maple/expectation/aop/aspect/BufferedLikeAspect.java +32/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #70 — refactor: 분산 락 AOP 가독성 개선
- author/state/dates: zbnerd | MERGED | created 2025-12-25T23:31:11Z | closed 2025-12-25T23:33:50Z | merged yes at 2025-12-25T23:33:49Z | merge 48aad9f38f19b50c324d2d2c70f8c5f12fa4a0b4.
- body: ## 🔗 관련 이슈 ## 🗣 개요 프로젝트 전반의 공통 관심사(AOP)를 정리하는 최종 단계로, 분산 락 관련 `@Locked` 에스펙트와 예외 클래스를 리팩토링했습니다. 이를 통해 전체 시스템의 에러 응답 규격을 통일하고 인프라 로…
- reviews/discussion: 0 []; 0.
- commits: 2 [16c8122, 8a1711f]; linked issues: 0 [].
- file evidence: 24 [ADDED src/main/java/maple/expectation/aop/annotation/BufferedLike.java +15/-0; ADDED src/main/java/maple/expectation/aop/annotation/NexonDataCache.java +14/-0; ADDED src/main/java/maple/expectation/aop/aspect/BufferedLikeAspect.java +32/-0].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #71 — [Refactor] 도메인 엔티티 캡슐화 및 객체 생성 무결성 확보
- author/state/dates: zbnerd | MERGED | created 2025-12-26T05:34:23Z | closed 2025-12-26T05:38:07Z | merged yes at 2025-12-26T05:38:07Z | merge 5020883578caf86e491a7f4b3bdfa4f7f5e3c1ff.
- body: ## 🔗 관련 이슈 - 관련 이슈 없음 (리팩토링 목적) ## 🗣 개요 도메인 모델의 무결성을 보장하기 위해 엔티티의 캡슐화를 강화하고, 불완전한 객체가 생성되는 것을 방지하기 위한 전반적인 구조 리팩토링을 수행했습니다. ## 🛠 작…
- reviews/discussion: 0 []; 0.
- commits: 1 [a7ddc96]; linked issues: 0 [].
- file evidence: 11 [MODIFIED src/main/java/maple/expectation/config/DataInitializer.java +23/-13; MODIFIED src/main/java/maple/expectation/domain/v2/CubeProbability.java +6/-5; MODIFIED src/main/java/maple/expectation/domain/v2/DonationHistory.java +7/-18].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #72 — [Refactor] 도메인 엔티티 무결성 확보 및 JPA Converter 기반 압축 자동화
- author/state/dates: zbnerd | MERGED | created 2025-12-26T05:50:19Z | closed 2025-12-26T05:52:19Z | merged yes at 2025-12-26T05:52:19Z | merge 81f5e5f41e31e5b118432ca53c0fb6a370d30149.
- body: ## 🔗 관련 이슈 - 관련 이슈 없음 (도메인 고도화 및 리팩토링) ## 🗣 개요 도메인 엔티티의 캡슐화를 강화하고, 인프라 로직(Gzip 압축)을 비즈니스 로직에서 분리하기 위한 대대적인 리팩토링을 진행했습니다. ## 🛠 작업 내…
- reviews/discussion: 0 []; 0.
- commits: 1 [3d3affc]; linked issues: 0 [].
- file evidence: 6 [MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +20/-25; MODIFIED src/main/java/maple/expectation/domain/v2/CharacterEquipment.java +11/-11; MODIFIED src/main/java/maple/expectation/external/impl/ResilientNexonApiClient.java +10/-12].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #73 — refactor: 비대한 서비스 코드 리팩토링 및 관심사 분리
- author/state/dates: zbnerd | MERGED | created 2025-12-26T06:59:35Z | closed 2025-12-26T07:04:10Z | merged yes at 2025-12-26T07:04:10Z | merge 023dd2a941baf51dfe9e92fb0879020e20498874.
- body: ## 🔗 관련 이슈 - 없음 ## 🗣 개요 서비스 레이어에 집중되었던 과도한 책임을 기능별 컴포넌트(Factory, Mapper, Executor)로 분리하고, 이벤트 기반 아키텍처를 도입하여 전반적인 코드 품질과 유지보수성을 개선했…
- reviews/discussion: 0 []; 0.
- commits: 1 [e444b95]; linked issues: 0 [].
- file evidence: 21 [ADDED src/main/java/maple/expectation/config/ResilienceConfig.java +26/-0; MODIFIED src/main/java/maple/expectation/provider/EquipmentDataProvider.java +13/-0; DELETED src/main/java/maple/expectation/service/v2/CubeService.java +0/-93].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #74 — refactor: AOP 기반 트레이스 로그 최적화 및 실시간 관측성(Observability) 강화
- author/state/dates: zbnerd | MERGED | created 2025-12-26T15:36:13Z | closed 2025-12-26T15:52:12Z | merged yes at 2025-12-26T15:52:12Z | merge 6543f06bc1b070c6af374888c7c3a22aae518d62.
- body: ## 🔗 관련 이슈 ## 🗣 개요 기존에 수동으로 관리하던 `@TraceLog` 어노테이션 방식의 한계를 극복하고, Spring AOP의 포인트컷 지시자(PCD)를 활용하여 시스템 전체의 가시성을 자동화하고 운영 효율성을 극대화했습니…
- reviews/discussion: 0 []; 0.
- commits: 1 [249e438]; linked issues: 0 [].
- file evidence: 14 [MODIFIED src/main/java/maple/expectation/aop/aspect/LockAspect.java +1/-1; MODIFIED src/main/java/maple/expectation/aop/aspect/TraceAspect.java +33/-21; MODIFIED src/main/java/maple/expectation/controller/GameCharacterControllerV2.java +0/-1].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #75 — [Refactor] AOP 매개변수 바인딩 적용 및 NexonDataCacheAspect 고도화
- author/state/dates: zbnerd | MERGED | created 2025-12-26T16:29:26Z | closed 2025-12-26T16:31:34Z | merged yes at 2025-12-26T16:31:34Z | merge ce7f2a5f51ad25ad3b72a8c0761a1a319bbb9f42.
- body: ## 🔗 관련 이슈 * 관련 이슈 없음 ## 🗣 개요 김영한 스프링 핵심원리 고급편 섹션 12에서 학습한 '매개변수 전달' 기술을 실제 프로젝트의 캐시 로직에 적용했습니다. ## 🛠 작업 내용 * **AOP 매개변수 바인딩 적용**:…
- reviews/discussion: 0 []; 0.
- commits: 1 [2ac20f9]; linked issues: 0 [].
- file evidence: 2 [MODIFIED src/main/java/maple/expectation/aop/aspect/BufferedLikeAspect.java +7/-8; MODIFIED src/main/java/maple/expectation/aop/aspect/NexonDataCacheAspect.java +11/-15].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #76 — refactor: JsonField 조회 로직 최적화 (O(N) -> O(1))
- author/state/dates: zbnerd | MERGED | created 2025-12-26T19:45:30Z | closed 2025-12-26T19:47:34Z | merged yes at 2025-12-26T19:47:34Z | merge 845b0740d03e451cd53bce6bdeab8d32232a31a3.
- body: ## 🔗 관련 이슈 * 관련 이슈 없음 ## 🗣 개요 스트리밍 파서에서 JSON 필드를 매칭할 때 발생하는 반복적인 루프 탐색 비효율을 해결하기 위해 조회를 최적화했습니다. ## 🛠 작업 내용 * **정적 캐시 구조 도입**: `Js…
- reviews/discussion: 0 []; 0.
- commits: 1 [8933034]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/parser/EquipmentStreamingParser.java +19/-11].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #83 — [feat] #11 장비 조회 응답 계층형 캐시(L1+L2) 도입 및 성능 개선
- author/state/dates: zbnerd | MERGED | created 2025-12-27T22:10:52Z | closed 2025-12-27T22:12:52Z | merged yes at 2025-12-27T22:12:52Z | merge c9fa7032bcc8f7f04412ce72cc81892ce516fb2a.
- body: ## 🔗 관련 이슈 - #11 ## 🗣 개요 - 동일 캐릭터에 대한 빈번한 장비 조회 시 발생하는 불필요한 DB 트래픽과 CPU 연산(JSON 역직렬화) 낭비를 해결하기 위해 다중 계층 캐시 구조를 설계하고 적용했습니다. ## 🛠 작…
- reviews/discussion: 0 []; 0.
- commits: 1 [c357ac3]; linked issues: 0 [].
- file evidence: 3 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +6/-3; MODIFIED src/main/java/maple/expectation/service/v2/EquipmentService.java +3/-0; MODIFIED src/test/java/maple/expectation/service/v2/EquipmentServiceTest.java +40/-12].
- resolution: merged; file/commit evidence above. Portfolio: 기능.

### PR #84 — refactor: #12 캐시 영역별 개별 정책(TTL/Max) 적용 및 명칭 최적화
- author/state/dates: zbnerd | MERGED | created 2025-12-27T22:33:45Z | closed 2025-12-27T22:34:29Z | merged yes at 2025-12-27T22:34:29Z | merge 5530c297c4078292c8a0d7f43e3cdd8d9ef05416.
- body: ## 🔗 관련 이슈 - #12 ## 🗣 개요 - 모든 캐시에 일괄 적용되던 전역 정책을 폐기하고, 데이터의 성격(정합성 중요도 및 연산 비용)에 따라 캐시 영역별로 최적화된 개별 정책을 수립하고 적용했습니다. ## 🛠 작업 내용 - …
- reviews/discussion: 0 []; 0.
- commits: 1 [78fbc96]; linked issues: 0 [].
- file evidence: 1 [MODIFIED src/main/java/maple/expectation/config/CacheConfig.java +22/-7].
- resolution: merged; file/commit evidence above. Portfolio: 설계.

### PR #85 — [Refactor] #27 계층형 좋아요 동기화(L1-L2-L3) 아키텍처 도입 및 장애 내성 강화
- author/state/dates: zbnerd | MERGED | created 2025-12-27T23:12:26Z | closed 2025-12-27T23:13:02Z | merged yes at 2025-12-27T23:13:02Z | merge 3b85bbd5bc9238705fe5f9958f42ee9bf9017cc0.
- body: ## 🔗 관련 이슈 - #27 ## 🗣 개요 - 단일 서버에 종속적이었던 로컬 메모리 버퍼 구조를 서버 증설(Scale-out)이 가능한 분산 계층형 구조로 리팩토링했습니다. - 핵심 인프라(Redis, DB) 장애 상황에서도 데이터…
- reviews/discussion: 0 []; 0.
- commits: 1 [8e10f16]; linked issues: 0 [].
- file evidence: 8 [MODIFIED src/main/java/maple/expectation/global/lock/GuavaLockStrategy.java +2/-0; MODIFIED src/main/java/maple/expectation/global/lock/RedisDistributedLockStrategy.java +2/-0; MODIFIED src/main/java/maple/expectation/scheduler/LikeSyncScheduler.java +18/-23].
- resolution: merged; file/commit evidence above. Portfolio: 설계.
