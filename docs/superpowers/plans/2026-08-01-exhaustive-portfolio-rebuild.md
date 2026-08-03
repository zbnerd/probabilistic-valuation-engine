# Exhaustive Portfolio Rebuild Implementation Plan Set

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the resume, portfolio, and evidence book from every Git diff, GitHub PR/issue, repository document, and AI trace without omitting records or inventing facts.

**Architecture:** Implement three independently reviewable slices in order: immutable evidence capture, exhaustive case/content generation, then Mermaid/PDF rendering and zero-omission verification. A normalized JSONL ledger and stable source IDs are the only interface between slices, so collection can be rerun without rewriting the document renderer.

**Tech Stack:** Python 3.12, uv 0.11+, pytest 9.1.1, pypdf 6.14.2, Pillow 12.3.0, markdown-it-py 4.2.0, ReportLab 5.0.0, Node.js 22, Mermaid CLI 11.16.0, Git, GitHub REST API, Ghostscript.

**Spec:** `docs/superpowers/specs/2026-08-01-exhaustive-portfolio-rebuild-design.md`

**Detailed plans:**

1. `docs/superpowers/plans/2026-08-01-exhaustive-portfolio-evidence-capture.md`
2. `docs/superpowers/plans/2026-08-01-exhaustive-portfolio-case-content.md`
3. `docs/superpowers/plans/2026-08-01-exhaustive-portfolio-rendering-verification.md`

## Global Constraints

Every fenced shell block starts from `/home/maple/probabilistic-valuation-engine`; working-directory changes never carry into a later block.

- Enumerate every ref returned by `git for-each-ref`, HEAD, every reachable commit, and every parent-specific diff; root commits have one empty-tree diff.
- Enumerate every GitHub PR and issue plus every paginated child endpoint, then complete a reconciliation pass with zero container or child page/ID/hash delta.
- Record every `docs/ai-traces/` file and every tracked document/PDF block; never silently discard corrupt or unavailable records.
- Preserve every source/document target and put every independently reviewed observation in exactly one verified case or one explicit `record-only` reason; mixed dispositions under one target must both survive.
- Include every verified achievement and problem-solving case in both the resume and portfolio; do not cap projects, cases, pages, or volumes.
- Use repository measurement documents as facts. Preserve the verbatim raw label and distinct normalized categories for `measured`, `target`, `expected`, `estimated`, `failed`, `reverted`, `rolled-back`, and `unverified`; do not derive replacement values.
- The resume case title and portfolio case title must be byte-for-byte identical.
- Every portfolio case must have a fact-only Mermaid diagram plus problem, solution, result, and source sections; missing source fields must say they were not recorded. The portfolio's complete visual-asset set contains only those catalog-derived Mermaid renders.
- Reuse the embedded ID photo from `docs/Portfolio_Book/이력서.pdf`; do not generate or retouch a face.
- Never reproduce credentials, tokens, private keys, cookies, or third-party contact information. Preserve the record with locator/hash and `[REDACTED]` instead.
- Do not modify `.env`, run servers, run load tests, or execute destructive database operations.
- Preserve the original PDFs and the user's unstaged `.gitignore` change.
- Treat `6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd` as the frozen semantic source HEAD and `aa2338c54291e5ad2d81673c0bc4fabf4577cec4` as the first excluded workflow commit. Commit `source_boundary.json` before tooling, record observed refs separately, and record every later workflow commit and parent-diff hash through the publication parent in the final boundary manifest; only the final self-referential publication commit remains for the next snapshot.
- Before every implementation commit, run the focused tests from the detailed task and `git diff --check`.

## Planning Baseline

The planning-time workspace contained 2,345 unique commits across 44 refs, one root commit, 709 GitHub PRs, 752 GitHub issues, 166 AI-trace sessions/882 files, and 1,401 Markdown/text documents. These numbers are orientation only. Acceptance uses the later execution snapshot manifest, not these frozen counts.

## Locked File Structure

```text
docs/Portfolio_Book/
├── source_boundary.json
├── pyproject.toml
├── uv.lock
├── package.json
├── package-lock.json
├── mermaid-config.json
├── tools/run_portfolio_command.py
├── tools/portfolio_builder/
│   ├── __init__.py
│   ├── cli.py
│   ├── models.py
│   ├── canonical_io.py
│   ├── snapshot.py
│   ├── redaction.py
│   ├── git_collector.py
│   ├── archive.py
│   ├── github_client.py
│   ├── github_collector.py
│   ├── document_collector.py
│   ├── ai_trace_collector.py
│   ├── relations.py
│   ├── coverage.py
│   ├── review_batches.py
│   ├── review_validation.py
│   ├── case_catalog.py
│   ├── required_claims.json
│   ├── content_writer.py
│   ├── content_verifier.py
│   ├── photo.py
│   ├── content_contract.py
│   ├── mermaid.py
│   ├── pdf_renderer.py
│   ├── volume_planner.py
│   ├── visual_audit.py
│   └── verifier.py
├── tools/portfolio_builder/reviewer_protocol.md
├── tests/
├── output/assets/
├── output/research/
├── output/diagrams/
└── output/final/
```

Each Python module has one responsibility. Compatibility entry points `output/build_source_inventory.py` and `output/generate_pdfs.py` delegate to the package CLI after the migration; they contain no collection or rendering logic.

---

### Task 0: Materialize the approved source boundary

- [ ] Execute Task 0 of the evidence-capture plan before creating or committing any collector tooling.
- [ ] Verify the locked source commit/tree, first excluded commit/parent, workflow ref, three external PDF identities, and six bootstrap legacy-owned output identities exactly; do not derive replacements from the later workflow HEAD.
- [ ] Commit only `docs/Portfolio_Book/source_boundary.json` and confirm that commit itself remains in the excluded workflow chain.

---

### Task 1: Capture and reconcile the complete evidence snapshot

**Files:**

- Implement: every file listed by `2026-08-01-exhaustive-portfolio-evidence-capture.md`
- Produce: `docs/Portfolio_Book/output/research/source_records.jsonl`
- Produce: `docs/Portfolio_Book/output/research/document_claim_inventory.jsonl`
- Produce: `docs/Portfolio_Book/output/research/capture_coverage_manifest.json`
- Produce: `docs/Portfolio_Book/output/research/capture_coverage_manifest.md`

**Interfaces:**

- Consumes: the exact Git/GitHub/document/AI state visible at collection start.
- Produces: canonical `SourceRecord` JSONL, immutable safe archives with original-byte hashes, `SnapshotManifest`, and mechanically complete source coverage. Semantic classification intentionally remains `unreviewed` for the next slice.

- [ ] **Step 1: Execute the evidence-capture plan task by task**

Use the first detailed plan and stop at each task's test/commit gate. Do not start content authoring while its final source-coverage command is non-zero.

- [ ] **Step 2: Verify the slice contract**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-source-capture \
  --manifest output/research/snapshot_manifest.json \
  --root output/research
```

Expected: exit 0; every ref/commit/parent diff/PR/issue/child record/document/PDF unit/AI-trace record has zero enumeration delta, `stale_github_records=0`, transient failures 0, and all stored hashes/archive locators resolve. Counts of semantic `unreviewed` source IDs and document claims are reported as the exact Task 2 work queue, not treated as capture failures.

---

### Task 2: Build the exhaustive case catalog and both Markdown documents

**Files:**

- Implement: every file listed by `2026-08-01-exhaustive-portfolio-case-content.md`
- Produce: `docs/Portfolio_Book/output/research/case_catalog.jsonl`
- Produce: `docs/Portfolio_Book/output/research/case_source_map.csv`
- Produce: `docs/Portfolio_Book/output/research/classified_source_records.jsonl`
- Produce: `docs/Portfolio_Book/output/research/source_conflicts.jsonl`
- Produce: `docs/Portfolio_Book/output/research/release_coverage_manifest.json`
- Produce: `docs/Portfolio_Book/output/research/release_coverage_manifest.md`
- Replace: `docs/Portfolio_Book/output/final/이력서_완성본.md`
- Replace: `docs/Portfolio_Book/output/final/포트폴리오_완성본.md`
- Replace: `docs/Portfolio_Book/output/final/전수증거장부.md`

**Interfaces:**

- Consumes: Task 1's canonical ledger and explicit relations only.
- Produces: one `CaseRecord` for every achievement/problem-solving observation, observation-level record-only indices, a verified source-conflict registry, the exhaustive human-readable evidence book, identical case titles in both documents, and source IDs on every factual sentence.

- [ ] **Step 1: Execute the case/content plan task by task**

Use the second detailed plan. Review work is split into deterministic, byte-bounded safe parts; batches are complete only when every target's member/part/byte-range union has independent primary and verifier coverage and every observation has an exact disposition. No reviewer may infer a missing problem, solution, result, relation, metric, or conflict resolution.

- [ ] **Step 2: Verify the slice contract**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-content \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --classified-sources output/research/classified_source_records.jsonl \
  --source-conflicts output/research/source_conflicts.jsonl \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --release-coverage output/research/release_coverage_manifest.json \
  --cases output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv \
  --observation-relations output/research/observation_relations.jsonl \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --resume output/final/이력서_완성본.md \
  --portfolio output/final/포트폴리오_완성본.md \
  --evidence-book output/final/전수증거장부.md
```

Expected: exit 0; equal case sets and titles, zero unmapped factual sentences, exact target and observation unions, every observation assigned to one case or record-only reason, every raw status label and source-conflict side preserved, every case has a source-backed `DiagramSpec`, all required documented measurements present, and zero unapproved derived claims.

---

### Task 3: Render every diagram/PDF and perform final verification

**Files:**

- Implement: every file listed by `2026-08-01-exhaustive-portfolio-rendering-verification.md`
- Produce: `docs/Portfolio_Book/output/diagrams/*.mmd`
- Produce: `docs/Portfolio_Book/output/diagrams/rendered/*.{svg,png}`
- Replace: exact manifest-listed PDF families rooted at `이력서_완성본.pdf`, `포트폴리오_완성본.pdf`, and `전수증거장부.pdf`, plus only their required `-<NNN>.pdf` bodies
- Produce: `docs/Portfolio_Book/output/final/검토필요사항.md`

**Interfaces:**

- Consumes: Task 2's `CaseRecord` JSONL and Markdown documents.
- Produces: one compiled Mermaid source/render pair per case, A4 PDFs or deterministic PDF volume sets, master indices, and final verification evidence.

- [ ] **Step 1: Execute the rendering/verification plan task by task**

Use the third detailed plan. A Mermaid compile failure, PDF overflow, title mismatch, missing source link, original-PDF hash change, or secret scan finding fails the build.

- [ ] **Step 2: Run the complete final gate**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-all \
  --root . \
  --phase post-publication \
  --publication-manifest output/research/publication_manifest.json \
  --require-visual-review output/research/visual_audit_manifest.json
git -C ../.. diff --check
git -C ../.. status --short --branch
```

Expected: `VERIFICATION_OK`; capture/release source, observation, conflict, case, Mermaid-only portfolio visual, required document-unit, PDF, and page coverage all report zero missing IDs; original PDF hashes match the snapshot; `HEAD` is exactly one single-parent publication commit after `publication_parent`; the committed tree bytes match every current artifact/manifest hash; retired paths are absent; task-owned staged and unstaged path counts are zero; and only the user's pre-existing `.gitignore` change remains unstaged.

- [ ] **Step 3: Request an independent artifact review**

Give the reviewer the design spec, the four-plan set, `capture_coverage_manifest.md`, `release_coverage_manifest.md`, `검토필요사항.md`, and every final PDF volume. Record the exact current `HEAD` SHA and SHA-256 of `publication_manifest.json` in the request. Stop here until a fresh independent reviewer returns explicit `PASS head=<exact SHA> publication_manifest_sha256=<exact SHA-256>` for both source coverage and visual output. Any finding, missing verdict, or nonmatching SHA blocks Task 4 and sends the affected slice back through rebuild, both page-audit roles, publication commit, and independent review. Immediately before Task 4, recompute both values and require byte-for-byte equality with the PASS; do not treat a review request or silence as approval.

---

### Task 4: Publish the verified feature branch and stop before `develop` integration

The user requested a push to `develop`, but repository publication rules prohibit committing or pushing directly to `develop`. This plan can therefore publish only `docs/exhaustive-portfolio-rebuild`; it must report that the requested `develop` integration remains incomplete. After the feature-branch push, request authorization to open a draft PR targeting `develop`. Opening that PR does not authorize merge: after checks and review state are known, request a second, separate merge authorization. Never describe a feature-branch push or draft PR as a completed `develop` push.

- [ ] **Step 1: Confirm the final local publication state**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
test "$(git branch --show-current)" = "docs/exhaustive-portfolio-rebuild"
git status --short --branch
git diff --cached --check
git log -1 --oneline
git rev-parse HEAD
sha256sum docs/Portfolio_Book/output/research/publication_manifest.json
```

Expected: the exhaustive publication commit is `HEAD`; the printed HEAD/manifest hashes equal the independent review PASS exactly; no task-owned artifact remains staged, unstaged, or untracked; the user's `.gitignore` modification remains unstaged; and no original external PDF is tracked or staged.

- [ ] **Step 2: Refresh the target ref without rewriting history**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git rev-list --left-right --count origin/develop...HEAD
git merge-base HEAD origin/develop
```

Record the output. Do not rebase, merge, or force-push automatically if `origin/develop` advanced; the feature branch can still be published, and any integration decision belongs to a later PR/merge request.

- [ ] **Step 3: Push and verify the exact remote feature-branch SHA**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git push --set-upstream origin docs/exhaustive-portfolio-rebuild
test "$(git rev-parse HEAD)" = "$(git ls-remote --heads origin docs/exhaustive-portfolio-rebuild | awk '{print $1}')"
```

Expected: normal non-force push succeeds and the remote feature ref equals local `HEAD`. Report the exact local/remote SHA and the still-pending `develop` integration blocker. Do not run `git push origin HEAD:develop`, open a PR, merge, or deploy in this task.
