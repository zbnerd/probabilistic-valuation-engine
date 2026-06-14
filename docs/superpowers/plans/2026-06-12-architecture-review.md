# Architecture Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce 3 architectural review phase specs + Critical/High finding specs that define platform issues for GitHub Issue creation.

**Architecture:** Targeted code dive → evidence-gathering per section → spec writing → self-review → user review → commit. 3 phases, 1 plan, sequential (Phase 3 depends on Phase 1+2 evidence).

**Tech Stack:** Markdown specs, Git, GitHub Issues. No code changes. Read-only investigation + writing.

**Reference spec:** `docs/superpowers/specs/2026-06-12-architecture-review-design.md`

---

## File Structure

```
docs/superpowers/specs/
├── 2026-06-12-arch-review-phase1-control-data-plane.md      (CREATE in Task 1)
├── 2026-06-12-arch-review-phase2-ops-evolution.md          (CREATE in Task 2)
├── 2026-06-12-arch-review-phase3-summary-maturity.md       (CREATE in Task 3)
└── 2026-06-12-finding-<slug>.md × N                        (CREATE in Tasks 1, 2; Critical/High only)

docs/superpowers/plans/
└── 2026-06-12-architecture-review.md                       (this file, exists)
```

GitHub Issues: deferred to `to-issues` skill invocation after all 3 tasks done.

---

## Task 0: Worktree + Branch Setup

**Files:** none created (git ops only)

- [ ] **Step 0.1: Create branch from develop**

```bash
cd /home/maple/probabilistic-valuation-engine
git checkout develop
git pull origin develop
git checkout -b review/architecture-2026-06-12
```

Expected: branch created, no conflicts.

- [ ] **Step 0.2: Verify worktree (optional)**

If worktree desired for isolation:
```bash
git worktree add ../pve-arch-review review/architecture-2026-06-12
cd ../pve-arch-review
```

Expected: new worktree at sibling path.

Skip this step if working in main checkout. Solo dev bias: stay in main checkout, no worktree.

---

## Task 1: Phase 1 Review (sections 1-7)

**Files:**
- Create: `docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md`
- Create: `docs/superpowers/specs/2026-06-12-finding-<slug>.md` × N (Critical/High only)

**Sections covered:**
1. Control Plane vs Data Plane 분리
2. Airflow 역할
3. Kafka 사용 방식
4. MinIO Claim Check 설계
5. Replay 및 재해 복구 전략
6. Synchronizer 병목
7. Urgent Path

- [ ] **Step 1.1: Generate 1-3 falsifiable claims per section**

For each of 7 sections, write claims as hypothesis. Example for Section 6:
- Claim A: Synchronizer is single-threaded
- Claim B: `main_upsert` is the bottleneck
- Claim C: MinIO file read is the bottleneck

Output: inline notes in scratch file or just keep in context. Don't write to docs.

- [ ] **Step 1.2: Targeted code dive — Airflow + DAGs (sections 1, 2, 5)**

```bash
# Section 1: Control/Data plane separation
grep -rE "Airflow|kafka" /home/maple/probabilistic-valuation-engine/docker/airflow/dags/
grep -rE "@Scheduled" /home/maple/probabilistic-valuation-engine/module-external-api/src/main/kotlin | head -20

# Section 2: Airflow role
cat /home/maple/probabilistic-valuation-engine/docker/airflow/dags/daily_collection_pipeline.py
cat /home/maple/probabilistic-valuation-engine/docker/airflow/dags/daily_cleanup_pipeline.py
ls /home/maple/probabilistic-valuation-engine/airflow/dags/  # check for stale dup
cat /home/maple/probabilistic-valuation-engine/docker/airflow/connections.sh

# Section 5: Replay / DR
grep -rE "replay|objectKey|chunk-ready" /home/maple/probabilistic-valuation-engine/module-calculator/src/main/kotlin | head -30
```

Expected: evidence captured in notes. Look for: stale `airflow/dags/cleanup_pipeline.py`, dual `@Scheduled`, connection drift.

- [ ] **Step 1.3: Targeted code dive — Kafka topics + consumers (sections 1, 3, 5)**

```bash
# Topic inventory
grep -rE "topic:|@KafkaListener" /home/maple/probabilistic-valuation-engine/module-*/src/main/resources/application.yml
grep -rE "chunk-ready|urgent|consumed" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -40

# Concurrency / parallelism
grep -rE "concurrency|Concurrency" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -30
grep -rE "concurrency" /home/maple/probabilistic-valuation-engine/module-*/src/main/resources/application.yml

# Idempotency
grep -rE "resultObjectKey|readKey|ON CONFLICT" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20
```

Expected: topic list, consumer concurrency per service, idempotency check locations.

- [ ] **Step 1.4: Targeted code dive — MinIO / Claim Check (section 4)**

```bash
# MinIO usage
grep -rE "minio|s3|S3|ObjectStorage" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -40
cat /home/maple/probabilistic-valuation-engine/module-external-api/src/main/resources/application.yml | grep -A 5 minio
cat /home/maple/probabilistic-valuation-engine/module-calculator/src/main/resources/application.yml | grep -A 5 minio
cat /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/resources/application.yml | grep -A 5 minio

# Bucket policy / lifecycle
grep -rE "lifecycle|retention|Bucket" /home/maple/probabilistic-valuation-engine/module-infra/src/main/kotlin 2>/dev/null | head -20
```

Expected: minio client config, bucket names, lifecycle policy locations, access key handling.

- [ ] **Step 1.5: Targeted code dive — Synchronizer bottleneck (section 6)**

```bash
# Read key consumer + upsert code
cat /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/KafkaResultChunkConsumer.kt
cat /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/ChunkConsumerTemplate.kt
cat /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/resources/application.yml

# HikariCP / pool sizing
grep -rE "hikari|HikariCP|maximum-pool-size" /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/resources/ | head -10

# ON CONFLICT / batch / staging
grep -rE "INSERT.*ON CONFLICT|upsert|staging|batch" /home/maple/probabilistic-valuation-engine/module-synchronizer/src/main/kotlin | head -30
```

Expected: confirm sequential vs parallel, find pool size, identify upsert SQL location.

- [ ] **Step 1.6: Targeted code dive — Urgent path (section 7)**

```bash
cat /home/maple/probabilistic-valuation-engine/module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt
grep -rE "urgent-character-request|urgent-character-not-found" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20
grep -rE "character-fetch-request|character-fetch-response" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20
```

Expected: REST → Kafka producers/consumers, urgent path routing.

- [ ] **Step 1.7: Write Phase 1 spec**

Create `docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md` using the template in `2026-06-12-architecture-review-design.md` Section 1 ("Per-phase spec structure").

Required content per spec design:
- Frontmatter: date, phase, status=under-review
- Evidence base section listing all 6 sub-step artifacts
- 7 section subsections (1-7), each with findings
- Critical/High findings include "→ see finding-<slug>.md" pointer
- Medium/Low findings inline (5-10 lines)
- ≥1 mermaid diagram showing current state
- Calibrated severity per finding
- 30 min cap respected per section (if a section exceeded, demote to "needs deeper investigation" issue)

If a section finds no issue: write "Section X: no issue found" with 1-line evidence.

- [ ] **Step 1.8: Self-review Phase 1 spec against quality gates**

Run self-review per spec design Section 4.1:
```bash
# Check no placeholders
grep -nE "TBD|TODO|\\?\\?\\?|<fill>" /home/maple/probabilistic-valuation-engine/docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md
# Expected: no matches (or only intentional template TBD in finding spec, not phase spec)

# Check mermaid present
grep -c "mermaid" /home/maple/probabilistic-valuation-engine/docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md
# Expected: ≥1

# Check every Critical/High has file:line evidence
grep -E "^### Finding" /home/maple/probabilistic-valuation-engine/docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md
# Cross-check evidence section
```

Fix any failures inline before proceeding. Do NOT proceed if self-review fails.

- [ ] **Step 1.9: Write Critical/High finding specs**

For each Critical/High from Step 1.7, create `docs/superpowers/specs/2026-06-12-finding-<slug>.md` using the finding-spec template from spec design Section "Per-finding spec structure".

Slug convention: kebab-case, descriptive (e.g., `cleanup-dag-duplicate`, `synchronizer-sequential-bottleneck`, `airflow-connection-drift`).

- [ ] **Step 1.10: Self-review finding specs**

```bash
# Verify each finding spec has all required sections
for f in /home/maple/probabilistic-valuation-engine/docs/superpowers/specs/2026-06-12-finding-*.md; do
  echo "=== $f ==="
  grep -cE "^## (Problem|Evidence|Failure scenario|Fix options|Recommendation|Solo-dev cost-benefit)" "$f"
done
# Expected: 6 per file
```

Fix any missing sections.

- [ ] **Step 1.11: User review**

Show user the spec path + summary of findings. Wait for approval before commit.

- [ ] **Step 1.12: Commit Phase 1 artifacts**

```bash
git add docs/superpowers/specs/2026-06-12-arch-review-phase1-control-data-plane.md
git add docs/superpowers/specs/2026-06-12-finding-*.md
git commit -m "docs(review): phase 1 architectural review (sections 1-7)"
```

Expected: commit with phase-1 spec + finding specs.

---

## Task 2: Phase 2 Review (sections 8-15)

**Files:**
- Create: `docs/superpowers/specs/2026-06-12-arch-review-phase2-ops-evolution.md`
- Create: `docs/superpowers/specs/2026-06-12-finding-<slug>.md` × N (Critical/High only, additional)

**Sections covered:**
8. Multi-node 진화 전략
9. Observability 및 SRE 준비도
10. Backfill 전략
11. 비용 최적화
12. 보안 및 접근 제어
13. 기술 부채
14. Single Point of Failure
15. Spark / Flink / Kubernetes 진화 경로

- [ ] **Step 2.1: Generate 1-3 falsifiable claims per section**

For each of 8 sections, write claims as hypothesis. Example for Section 9 (Observability):
- Claim A: No alerting on consumer lag = 0
- Claim B: No SLA documented
- Claim C: No run dashboard exists

Keep in context/scratch, not in docs.

- [ ] **Step 2.2: Targeted code dive — Multi-node + K8s readiness (section 8)**

```bash
# Multi-instance awareness
grep -rE "instanceId|hostname|HOSTNAME" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20

# Config externalization
ls /home/maple/probabilistic-valuation-engine/config/
find /home/maple/probabilistic-valuation-engine -name "*.yml" -path "*/config/*" | head -20

# K8s manifests
find /home/maple/probabilistic-valuation-engine -name "*.yaml" -path "*k8s*" -o -name "deployment.yaml" 2>/dev/null | head -10
```

Expected: how much is hardcoded vs env-driven, K8s readiness gaps.

- [ ] **Step 2.3: Targeted code dive — Observability (section 9)**

```bash
# Prometheus metrics
grep -rE "Micrometer|MeterRegistry|@Timed|Counter\(" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -40

# Alerting
find /home/maple/probabilistic-valuation-engine -name "alert*.yml" 2>/dev/null
find /home/maple/probabilistic-valuation-engine -name "alertmanager*" 2>/dev/null

# Grafana dashboards
ls /home/maple/probabilistic-valuation-engine/grafana/ 2>/dev/null
ls /home/maple/probabilistic-valuation-engine/grafana/dashboards/ 2>/dev/null

# Tracing
grep -rE "tracer|Trace|@WithSpan|opentelemetry" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin 2>/dev/null | head -10
```

Expected: existing metrics list, alerting rules, dashboard inventory, trace presence/absence.

- [ ] **Step 2.4: Targeted code dive — Backfill (section 10)**

```bash
# Backfill commands / endpoints
grep -rE "backfill|replay|resnapshot" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20
grep -rE "trigger.*backfill|backfill.*endpoint" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -10

# Existing run-id replay support
grep -rE "runId.*replay|replay.*runId" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -10
```

Expected: current replay capability, what's missing for date/run/chunk granularity.

- [ ] **Step 2.5: Targeted code dive — Cost + Security (sections 11, 12)**

```bash
# Cost
grep -rE "tier|sku|machine-type|instance" /home/maple/probabilistic-valuation-engine/docker-compose*.yml | head -20

# Security
grep -rE "API_KEY|password|secret|credentials" /home/maple/probabilistic-valuation-engine/module-*/src/main/resources/application.yml | head -20
find /home/maple/probabilistic-valuation-engine -name ".env*" 2>/dev/null | head -5

# MinIO access
grep -rE "MINIO_ACCESS_KEY|MINIO_SECRET|MINIO_ROOT" /home/maple/probabilistic-valuation-engine/docker-compose*.yml 2>/dev/null
grep -rE "minio.access|minio.secret" /home/maple/probabilistic-valuation-engine/module-*/src/main/resources/application.yml | head -10

# Kafka ACL
grep -rE "KAFKA_LISTENERS|KAFKA_ADVERTISED|KAFKA_SASL" /home/maple/probabilistic-valuation-engine/docker-compose*.yml 2>/dev/null
```

Expected: cost sources, secret handling, MinIO/Kafka/PG auth model.

- [ ] **Step 2.6: Targeted code dive — Tech debt + SPOF (sections 13, 14)**

```bash
# Tech debt
ls /home/maple/probabilistic-valuation-engine/airflow/dags/  # stale dup?
git -C /home/maple/probabilistic-valuation-engine log --oneline -20  # see recent activity

# Module-app / module-web deletion plan (from prior commit)
git -C /home/maple/probabilistic-valuation-engine show --stat acd09e1e7 | head -30

# SPOF candidates
grep -rE "static |@PostConstruct|database =|primary" /home/maple/probabilistic-valuation-engine/module-*/src/main/kotlin | head -20
```

Expected: legacy code locations, scheduler duplication, hardcoded SPOFs.

- [ ] **Step 2.7: Targeted code dive — Spark/Flink/K8s path (section 15)**

```bash
# Search for any prior Spark/Flink/K8s discussion
ls /home/maple/probabilistic-valuation-engine/docs/09_Plans/ 2>/dev/null
ls /home/maple/probabilistic-valuation-engine/docs/01_ADR/ | grep -iE "spark|flink|k8s|kubernetes" 2>/dev/null
grep -rE "spark|flink|kubernetes|k8s" /home/maple/probabilistic-valuation-engine/build.gradle /home/maple/probabilistic-valuation-engine/settings.gradle 2>/dev/null
```

Expected: any prior eval, current scale numbers that justify/defer.

- [ ] **Step 2.8: Write Phase 2 spec**

Create `docs/superpowers/specs/2026-06-12-arch-review-phase2-ops-evolution.md` per template.

Required: frontmatter, evidence base (8 sub-step artifacts), 8 section subsections, mermaid diagram, calibrated severity.

Solo-dev bias: section 11 (cost), 13 (tech debt), 15 (Spark/Flink) likely yield "YAGNI" or "later" verdicts — call this out explicitly.

- [ ] **Step 2.9: Self-review Phase 2 spec**

Same checks as Task 1.8. Fix inline.

- [ ] **Step 2.10: Write Critical/High finding specs**

Same template. Slugs: `multi-node-config-externalization`, `alerting-on-lag-missing`, `minio-access-key-strategy`, etc.

- [ ] **Step 2.11: Self-review finding specs**

Same checks as Task 1.10.

- [ ] **Step 2.12: User review**

Show user the spec path + summary. Wait for approval.

- [ ] **Step 2.13: Commit Phase 2 artifacts**

```bash
git add docs/superpowers/specs/2026-06-12-arch-review-phase2-ops-evolution.md
git add docs/superpowers/specs/2026-06-12-finding-*.md
git commit -m "docs(review): phase 2 architectural review (sections 8-15)"
```

---

## Task 3: Phase 3 Summary + Maturity

**Files:**
- Create: `docs/superpowers/specs/2026-06-12-arch-review-phase3-summary-maturity.md`

**No code dive needed.** Pure synthesis from Phase 1+2 evidence.

- [ ] **Step 3.1: Compile TOP 5 strengths**

Read both phase specs. Identify 5 strengths with evidence (file:line ref or metric).

Output: section "TOP 5 강점" in Phase 3 spec.

Avoid cheerleading. Each strength = concrete evidence, not "well-designed" platitudes.

- [ ] **Step 3.2: Compile TOP 5 risks**

Identify 5 risks with current mitigation status. Cross-reference findings from Phase 1+2.

Output: section "TOP 5 위험" with severity + mitigation status per risk.

- [ ] **Step 3.3: Compile TOP 5 over-engineering for solo dev**

For each: name the over-engineering, "delete this" recommendation, effort to delete.

Likely candidates: legacy `airflow/dags/cleanup_pipeline.py`, dual `@Scheduled`, MinIO bucket sprawl, over-broad metrics, complex lock strategies.

Output: section "Solo-dev 과도한 설계 TOP 5".

- [ ] **Step 3.4: Compile TOP 5 quick wins**

Effort ≤ 1 day each, concrete change, measurable impact.

Output: section "당장 효과 큰 개선 TOP 5" with effort per item.

- [ ] **Step 3.5: Compile TOP 5 1-year-regret items**

Things user will regret NOT doing in 12 months. Evidence-based, not speculation.

Output: section "1년 후 후회할 선택 TOP 5".

- [ ] **Step 3.6: "Over-worrying" list**

Things user is right to ignore. Explicit "you are spending attention on X but it doesn't matter because Y".

Output: section "과하게 걱정하는 부분".

- [ ] **Step 3.7: Blind spots**

List 3-5 things this review couldn't see. Ask user to verify.

Examples: real production traffic patterns, cost actuals (only estimated), specific user pain points, integration with external services not in repo.

Output: section "Blind Spots" with "user to verify" callouts.

- [ ] **Step 3.8: Maturity assessment**

Read evidence from Phase 1+2 + 7 summary items. Pick maturity level (1-5) with rationale per level criteria:

- Level 1: Toy Project
- Level 2: Production-inspired
- Level 3: Production-ready for SMB
- Level 4: Enterprise-grade
- Level 5: Large-scale Platform Team

State: "Current: Level N" with evidence.
Then: "Min change to L4" = 3-5 highest-leverage items.

Output: section "성숙도 평가".

- [ ] **Step 3.9: Self-review Phase 3 spec**

Per quality gates:
- All 7 mandatory sections present (TOP 5 strengths, risks, over-eng, quick wins, regret, over-worrying, blind spots + maturity)
- Every claim has evidence ref to Phase 1 or 2 spec
- Mermaid diagram for maturity progression

Fix inline if fails.

- [ ] **Step 3.10: User review (final)**

Show user the Phase 3 spec. This is the synthesis — confirm before commit + issue conversion.

- [ ] **Step 3.11: Commit Phase 3 spec**

```bash
git add docs/superpowers/specs/2026-06-12-arch-review-phase3-summary-maturity.md
git commit -m "docs(review): phase 3 summary + maturity assessment"
```

---

## Task 4: GitHub Issue Conversion (deferred to to-issues skill)

**Files:** none created (issue creation, not file).

- [ ] **Step 4.1: List all Critical/High finding specs**

```bash
ls /home/maple/probabilistic-valuation-engine/docs/superpowers/specs/2026-06-12-finding-*.md
```

Expected: 5-10 files (depending on findings).

- [ ] **Step 4.2: Invoke to-issues skill**

Use the Skill tool to invoke `to-issues` skill with context: "Convert Critical/High finding specs to GitHub Issues per architecture review design."

The to-issues skill will handle:
- Issue body generation from spec
- Label application per `docs/agents/triage-labels.md`
- Title format `[arch-review][Critical|High] <slug>`

- [ ] **Step 4.3: Final commit (if any uncommitted) + push**

```bash
git status
# If any uncommitted artifacts, add + commit
git push origin review/architecture-2026-06-12
```

Expected: branch pushed, ready for PR to develop per project CLAUDE.md "PR develop base로 생성할것".

---

## Self-Review (post-plan)

**Spec coverage:**
- [x] Phase 1 spec → Task 1
- [x] Phase 2 spec → Task 2
- [x] Phase 3 spec → Task 3
- [x] Critical/High finding specs → Tasks 1.9, 2.10
- [x] Self-review per task → Steps 1.8, 1.10, 2.9, 2.11, 3.9
- [x] User review per task → Steps 1.11, 2.12, 3.10
- [x] Commit per task → Steps 1.12, 2.13, 3.11
- [x] GitHub issue conversion → Task 4
- [x] All 15 sections covered (1-7 in Task 1, 8-15 in Task 2)
- [x] 7 summary items covered in Task 3 (Steps 3.1-3.7)
- [x] Maturity assessment → Step 3.8

**Placeholder scan:** No "TBD", "TODO", "implement later", "fill in details" in steps. Each grep command is concrete. Each step has expected output.

**Type consistency:** Finding spec slug pattern consistent across Tasks 1, 2. Commit message pattern consistent. Frontmatter consistent.

**Bite-size check:** Each step is 2-5 min (most are single bash commands or single file writes).

No fixes needed.
