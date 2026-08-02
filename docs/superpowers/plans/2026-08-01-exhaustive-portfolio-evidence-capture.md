# Exhaustive Portfolio Evidence Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture every Git parent diff, GitHub PR/issue record, tracked document/PDF unit, and AI-trace artifact into a reproducible source ledger and immutable archive set.

**Architecture:** A committed bootstrap boundary fixes the semantic source HEAD before any tooling commit. A later snapshot manifest records both the exact refs observed while the collector runs and the semantic ref set with only the workflow ref pinned to that boundary, plus tracked files, AI-trace hashes, and the GitHub reconciliation window. Independent collectors stream canonical `SourceRecord` JSONL and safe deterministic archives while retaining hashes of the original bytes; a coverage verifier compares enumeration sets and hashes without attempting portfolio prose classification.

**Tech Stack:** Python 3.12, uv 0.11+, pytest 9.1.1, markdown-it-py 4.2.0, PyMuPDF 1.28.0, pypdf 6.14.2, Pillow 12.3.0, Git CLI, GitHub REST API.

**Spec:** `docs/superpowers/specs/2026-08-01-exhaustive-portfolio-rebuild-design.md`

**Depends on:** none. Complete before `2026-08-01-exhaustive-portfolio-case-content.md`.

## Global Constraints

Every fenced shell block starts from `/home/maple/probabilistic-valuation-engine`; working-directory changes never carry into a later block.

- Capture all refs returned by `git for-each-ref` plus HEAD as `observed_refs`. Derive `semantic_refs` only from the committed source boundary; never adopt the later tooling HEAD as the semantic cutoff.
- Create one root empty-tree diff or one diff per parent for every non-root commit.
- Generate every textual/binary parent patch to compute its original hash. Store a lossless redacted textual patch; replace unscannable binary payload bodies with blob IDs, sizes, and `[REDACTED BINARY PAYLOAD]` while retaining the original patch hash. Never put patch bodies into CSV cells.
- Use GitHub enumeration/reconciliation sets, not assumptions about continuous issue numbers. Every container and child endpoint must finish a full Link-pagination pass and a later zero-delta fingerprint pass.
- A GitHub field is complete when returned or explicitly `confirmed-unavailable`; transient failures and count mismatches remain blockers.
- Record all tracked text/PDF units, all `docs/ai-traces/` files, and all three boundary-locked external PDF inputs even when parsing fails.
- Redact copied secret values and third-party contacts while preserving source locator, hash, and redaction type.
- Emit separate records for commit metadata and every parent diff; for GitHub/AI collections, emit the container and every child item as separate records.
- Do not classify a captured record as a portfolio case in this plan. Initial `classification` is `unreviewed`; the next plan reads and classifies every record and document claim.
- Use `tmp_path` and temporary Git repositories in tests; do not mutate repository history or external GitHub state.
- Do not touch `.env`, `.gitignore`, databases, servers, queues, or the original PDFs.
- Run `git diff --check` before every commit.

`raw_hash` always identifies the original bytes observed at the frozen source. `stored_hash` identifies the safe representation written to generated output. The schema name `raw_archive_locator` is retained for compatibility with the approved ledger, but its member is the safe stored representation whenever `privacy_redactions` is non-empty; the original secret or binary value is never republished.

`evidence_scope` and `claim_authority` are mandatory and inherited by every child claim/record. Git, GitHub, and tracked repository documents are normally `project-evidence/primary-record`; tracked prior generated material under `docs/Portfolio_Book/output/` is instead `project-evidence/legacy-derived-record`. AI-trace prose proposals/summaries/completion claims are `project-evidence/ai-assertion`, while exact captured tool results/exits/errors and immutable trace patch/log bytes are `project-evidence/trace-observation`; a command input without its result proves only an attempt, never success. The ignored existing resume/portfolio PDFs are `personal-evidence/personal-record`; the 31-page renewal guide is `structural-reference/structural-reference`. Collectors and coverage validators reject any other mapping or missing scope/authority. AI assertions and legacy-derived records remain individually recorded, but a later reviewer may use one as an implementation/result fact only through an exact explicit relation to a non-derived primary/personal/trace-observation record supporting that same assertion; neither may be the sole fact authority. A trace observation supports only the exact recorded bytes and status; truncation or a missing result cannot be filled in.

The document path set is deterministic. Include every tracked path under `docs/` except `docs/ai-traces/`, which is assigned to the dedicated AI collector; every tracked PDF anywhere; root/project instruction or prose files named `README*`, `CHANGELOG*`, `LICENSE*`, `CONTRIBUTING*`, `AGENTS.md`, or `CLAUDE.md`; and every tracked file outside `docs/` with extension `.md`, `.mdx`, `.markdown`, `.rst`, `.adoc`, `.txt`, `.csv`, `.tsv`, `.json`, `.jsonl`, `.yaml`, `.yml`, `.toml`, `.ini`, `.cfg`, `.properties`, or `.sql`. Add the exact three `external_input_files` from `source_boundary.json` regardless of Git ignore/tracking state. Unknown extensions under tracked `docs/` are UTF-8 probed: decodable files receive text units and undecodable files receive a binary file record. The snapshot assigns every tracked path one rule ID (`document`, `ai-trace`, or `non-document`) and every external input one role, so coverage compares against the Git tree plus the independent boundary list rather than the collector's own output.

The capture manifest has two immutable stages. `capture-snapshot` consumes and validates the already committed `source_boundary.json`, writes a local cutoff with `github_window=None`, and records the exact observed/semantic ref pair; `collect-all` consumes that exact manifest, never recaptures Git/files, and atomically writes a finalized manifest containing the GitHub enumeration/reconciliation window. The `snapshot_id` is fixed by the boundary plus local cutoff, while `finalized_at` and the GitHub window prove remote completion. The active workflow chain from `first_excluded_commit` through the observed HEAD must be a linear descendant chain, its first parent must be the locked source HEAD, and no other observed ref may point into that excluded chain. Any violation is a blocker, not an invitation to rewrite the boundary.

---

### Task 0: Commit the approved semantic source boundary before tooling work

**Files:**

- Create: `docs/Portfolio_Book/source_boundary.json`

**Interfaces:**

- Produces: the immutable, human-reviewable input used by every later snapshot and publication-boundary validator.
- Consumes: the exact source/design commits already locked in the approved design.

- [ ] **Step 1: Add the exact boundary file with `apply_patch`**

```json
{
  "schema_version": 1,
  "source_snapshot_head": "6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd",
  "source_snapshot_tree": "a7e29167925c83eb14572051a8da7ae9ab37f44b",
  "first_excluded_commit": "aa2338c54291e5ad2d81673c0bc4fabf4577cec4",
  "first_excluded_parent": "6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd",
  "workflow_ref": "refs/heads/docs/exhaustive-portfolio-rebuild",
  "external_input_files": [
    {
      "role": "renewal-guide",
      "path": "docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf",
      "byte_count": 3317790,
      "sha256": "e67b747879168c5864eef1ea85cde54a658c0ea42f58d08e5ef752b706a59a7b"
    },
    {
      "role": "id-photo-source-resume",
      "path": "docs/Portfolio_Book/이력서.pdf",
      "byte_count": 661091,
      "sha256": "050ebd6dc8d02e1969d9829d0d93055075fe662d75819d95802a1074b543db2e"
    },
    {
      "role": "legacy-portfolio-reference",
      "path": "docs/Portfolio_Book/포트폴리오.pdf",
      "byte_count": 250265,
      "sha256": "fb2104e6e9167c9162b865c25ca9a9afbe238250ec4af252f91659729aadba7b"
    }
  ],
  "legacy_owned_outputs": [
    {
      "path": "docs/Portfolio_Book/output/research/ai_traces_summary.md",
      "git_blob_oid": "b0eca5493ee0b8ab9fa8880108bacf9477524e59",
      "sha256": "d49d4a7b2238b161372752923d37b8577232dba1ac9f18cb2ae5ecc9286996cb"
    },
    {
      "path": "docs/Portfolio_Book/output/research/evidence_ledger.md",
      "git_blob_oid": "ab1cbb69e42e2815a8792ca07627db0810560c0d",
      "sha256": "a3ce843d1f7f2a5f6659e4f5ec6212651ed1980468ed19f2f2dd2298812f06fb"
    },
    {
      "path": "docs/Portfolio_Book/output/research/issue_inventory.md",
      "git_blob_oid": "27cf757f5fbf2875c32af636bc8840414e6cfb4f",
      "sha256": "25d7f8c1419560f8bd41841b7a1c6079371203438f760f73f52638037468ea13"
    },
    {
      "path": "docs/Portfolio_Book/output/research/pr_inventory.md",
      "git_blob_oid": "97e5261f8b02ffc0c7f45d2c706ba02466a5cad8",
      "sha256": "afdc9df3c09cff84ec406bf066852393ddd9605f44c94afb4dcafbd99c3fb8b1"
    },
    {
      "path": "docs/Portfolio_Book/output/research/source_inventory.md",
      "git_blob_oid": "812a555b568be3bfe2deedf2be7b1c7075c8e7f4",
      "sha256": "49156b16d39cc3fb3db1235659ee3192c9cefabe10577f325323b35ab734ff7b"
    },
    {
      "path": "docs/Portfolio_Book/output/research/technical_evidence_candidates.md",
      "git_blob_oid": "4544aa93c0c9de7f2fbf2e9cb2cb241948e12a0b",
      "sha256": "e0fc568582270a21029ab354a6d40e3cf0f9242601ca51ea5a1e9d54bab0539f"
    }
  ]
}
```

- [ ] **Step 2: Validate the immutable objects and ancestry**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
test "$(git rev-parse 6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd^{tree})" = "a7e29167925c83eb14572051a8da7ae9ab37f44b"
test "$(git rev-parse aa2338c54291e5ad2d81673c0bc4fabf4577cec4^)" = "6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd"
git merge-base --is-ancestor aa2338c54291e5ad2d81673c0bc4fabf4577cec4 HEAD
test "$(git symbolic-ref HEAD)" = "refs/heads/docs/exhaustive-portfolio-rebuild"
```

Expected: all commands exit 0. Do not regenerate either locked SHA from the current HEAD.

- [ ] **Step 3: Commit only the boundary file**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add -- docs/Portfolio_Book/source_boundary.json
git diff --cached --check
git commit -m "chore(portfolio): lock semantic source boundary"
```

This commit is itself in the excluded workflow chain that begins at `aa2338c54291e5ad2d81673c0bc4fabf4577cec4`.

---

### Task 1: Create the package, canonical schema, and JSONL I/O

**Files:**

- Create: `docs/Portfolio_Book/pyproject.toml`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/__init__.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/models.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/canonical_io.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tools/run_portfolio_command.py`
- Create: `docs/Portfolio_Book/tests/test_models.py`
- Create: `docs/Portfolio_Book/tests/test_canonical_io.py`
- Create: `docs/Portfolio_Book/tests/test_environment_runner.py`
- Generate: `docs/Portfolio_Book/uv.lock`

**Interfaces:**

- Produces: `SourceBoundary`, `SourceRecord`, `DocumentClaim`, `SnapshotManifest`, `RefSnapshot`, `AvailabilityStatus`, `Classification`, `read_jsonl()`, `write_jsonl()`, the `portfolio-book` CLI entry point, and an isolated command runner that leaves no repository `.venv`, `.cache`, or `node_modules`.
- Consumes: only Python standard-library values and UTF-8 JSON-compatible payloads.

- [ ] **Step 1: Write failing round-trip and canonical-order tests**

```python
def test_source_record_round_trip(tmp_path):
    record = SourceRecord(
        source_id="GIT-a" + "0" * 39 + "-ROOT",
        source_type="git-diff",
        source_locator="git:a",
        snapshot_id="snap-1",
        title="root",
        evidence_scope="project-evidence",
        claim_authority="primary-record",
        recorded_status="captured",
        recorded_at="2026-08-01T00:00:00Z",
        raw_hash="0" * 64,
        stored_hash="1" * 64,
        raw_archive_locator="commit-diffs-001.tar.gz#root.patch",
        stored_members=(StoredArtifactMember(
            member_id="GIT-root-P01-part-001",
            locator="commit-diffs-001.tar.gz#root.patch",
            ordinal=1,
            total=1,
            byte_count=10,
            sha256="1" * 64,
        ),),
        explicit_relations=(),
        case_ids=(),
        classification="unreviewed",
        record_only_reason=None,
        availability_status="available",
        privacy_redactions=(),
        parse_status="parsed",
        payload={"z": 1, "a": 2},
    )
    target = tmp_path / "records.jsonl"
    write_jsonl(target, [record])
    assert read_jsonl(target, SourceRecord) == [record]
    assert target.read_text().index('"a"') < target.read_text().index('"z"')
```

Run: `cd docs/Portfolio_Book && uv run --isolated --with pytest pytest tests/test_models.py tests/test_canonical_io.py tests/test_environment_runner.py -q`

Expected: FAIL because the package and models do not exist.

- [ ] **Step 2: Add the exact project configuration**

```toml
[build-system]
requires = ["setuptools>=75"]
build-backend = "setuptools.build_meta"

[project]
name = "portfolio-book-builder"
version = "0.1.0"
requires-python = ">=3.12,<3.13"
dependencies = [
  "markdown-it-py==4.2.0",
  "pillow==12.3.0",
  "pymupdf==1.28.0",
  "pypdf==6.14.2",
  "reportlab==5.0.0",
]

[project.scripts]
portfolio-book = "portfolio_builder.cli:main"

[dependency-groups]
dev = ["pytest==9.1.1"]

[tool.setuptools]
package-dir = {"" = "tools"}

[tool.setuptools.packages.find]
where = ["tools"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

Run: `cd docs/Portfolio_Book && uv lock`

Expected: `uv.lock` resolves the exact runtime/test dependencies.

- [ ] **Step 3: Implement immutable models and canonical I/O**

Use frozen dataclasses with explicit `to_dict()`/`from_dict()` methods. `write_jsonl()` must write UTF-8, `sort_keys=True`, `ensure_ascii=False`, compact separators, one trailing newline per record, and atomic `Path.replace()` from a sibling temporary file. `read_jsonl()` must report `path:line` on malformed JSON.

```python
class AvailabilityStatus(StrEnum):
    AVAILABLE = "available"
    CONFIRMED_UNAVAILABLE = "confirmed-unavailable"
    TRANSIENT_FAILURE = "transient-failure"

class Classification(StrEnum):
    UNREVIEWED = "unreviewed"
    CASE = "case"
    RECORD_ONLY = "record-only"

@dataclass(frozen=True, slots=True)
class StoredArtifactMember:
    member_id: str
    locator: str
    ordinal: int
    total: int
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class ExplicitRelation:
    relation_id: str
    relation_type: str
    target_source_id: str
    evidence_locator: str
    evidence_hash: str

@dataclass(frozen=True, slots=True)
class RefSnapshot:
    refname: str
    object_sha: str
    object_type: str
    peeled_sha: str | None
    peeled_type: str | None
    symbolic_target: str | None

@dataclass(frozen=True, slots=True)
class ExternalInputFile:
    role: Literal[
        "renewal-guide", "id-photo-source-resume", "legacy-portfolio-reference"
    ]
    path: str
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class LegacyOwnedOutput:
    path: str
    git_blob_oid: str
    sha256: str

@dataclass(frozen=True, slots=True)
class SourceBoundary:
    schema_version: int
    source_snapshot_head: str
    source_snapshot_tree: str
    first_excluded_commit: str
    first_excluded_parent: str
    workflow_ref: str
    external_input_files: tuple[ExternalInputFile, ...]
    legacy_owned_outputs: tuple[LegacyOwnedOutput, ...]

@dataclass(frozen=True, slots=True)
class FileSnapshot:
    path: str
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class TrackedFileSnapshot:
    path: str
    git_mode: str
    object_type: str
    object_sha: str
    collection_rule_id: Literal["document", "ai-trace", "non-document"]

@dataclass(frozen=True, slots=True)
class GitHubEndpointFingerprint:
    item_key: str
    endpoint_key: str
    request_params_sha256: str
    accept: str
    page_numbers: tuple[int, ...]
    page_response_hashes: tuple[str, ...]
    stable_child_ids: tuple[str, ...]
    availability_status: str

@dataclass(frozen=True, slots=True)
class GitHubSnapshotWindow:
    enumeration_started_at: str
    enumeration_completed_at: str
    reconciled_at: str
    pull_request_numbers: tuple[int, ...]
    issue_numbers: tuple[int, ...]
    updated_at_by_item: dict[str, str]
    endpoint_fingerprints: tuple[GitHubEndpointFingerprint, ...]

@dataclass(frozen=True, slots=True)
class SnapshotManifest:
    snapshot_id: str
    started_at: str
    local_completed_at: str
    finalized_at: str | None
    source_boundary_sha256: str
    source_snapshot_head: str
    source_snapshot_tree: str
    first_excluded_commit: str
    first_excluded_parent: str
    workflow_ref: str
    observed_head_sha: str
    observed_head_symbolic_target: str | None
    observed_refs: tuple[RefSnapshot, ...]
    semantic_refs: tuple[RefSnapshot, ...]
    excluded_workflow_commit_shas_at_capture: tuple[str, ...]
    external_input_files: tuple[ExternalInputFile, ...]
    legacy_owned_outputs: tuple[LegacyOwnedOutput, ...]
    tracked_files: tuple[TrackedFileSnapshot, ...]
    ai_trace_files: tuple[FileSnapshot, ...]
    github_window: GitHubSnapshotWindow | None

@dataclass(frozen=True, slots=True)
class DocumentClaim:
    claim_id: str
    document_source_id: str
    source_path: str
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    unit_kind: str
    line_start: int | None
    line_end: int | None
    page_index: int | None
    block_index: int
    raw_hash: str
    stored_hash: str
    stored_members: tuple[StoredArtifactMember, ...]
    text: str
    classification: str
    parse_status: str

@dataclass(frozen=True, slots=True)
class SourceRecord:
    source_id: str
    source_type: str
    source_locator: str
    snapshot_id: str
    title: str
    evidence_scope: Literal[
        "project-evidence", "personal-evidence", "structural-reference"
    ]
    claim_authority: Literal[
        "primary-record", "personal-record", "structural-reference",
        "ai-assertion", "trace-observation", "legacy-derived-record"
    ]
    recorded_status: str
    recorded_at: str | None
    raw_hash: str
    stored_hash: str
    raw_archive_locator: str | None
    stored_members: tuple[StoredArtifactMember, ...]
    explicit_relations: tuple[ExplicitRelation, ...]
    case_ids: tuple[str, ...]
    classification: str
    record_only_reason: str | None
    availability_status: str
    privacy_redactions: tuple[str, ...]
    parse_status: str
    payload: dict[str, object]
```

`ExplicitRelation.relation_id` is deterministic and globally resolvable: `REL-` plus the SHA-256 of canonical UTF-8 JSON containing the owning `source_id`, `relation_type`, full `target_source_id`, exact `evidence_locator`, and `evidence_hash`. Canonical I/O rejects duplicate IDs, one ID with different fields, a target absent from the frozen source/claim universe, or any downstream relation ID that does not resolve byte-for-byte to this ledger object.

- [ ] **Step 4: Implement CLI dispatch without collection logic**

`main(argv: Sequence[str] | None = None) -> int` uses `argparse`, returns integer exit codes, and imports command handlers lazily. Add `--version`; unknown commands must exit 2 without a traceback.

Implement `run_portfolio_command.py [--with-node] -- <command...>` with only the standard library. It creates a validated `tempfile.mkdtemp(prefix="portfolio-book-")`, points `UV_PROJECT_ENVIRONMENT`, `UV_CACHE_DIR`, npm cache, and Puppeteer cache inside it, runs `uv sync --frozen`, and executes the requested command from `docs/Portfolio_Book`. With `--with-node`, it copies the committed `package.json`/lock into the temporary root, runs `npm ci` there, and exports the exact temporary `mmdc` path. A separate `--refresh-node-lock` mode runs `npm install --package-lock-only --ignore-scripts` with all caches outside the repository and fails if it creates or changes a repository `node_modules`. A `finally` block removes only the validated temporary directory. Tests assert success and failure both leave the repository free of `.venv`, `.cache`, and `node_modules` created by the runner.

- [ ] **Step 5: Run the tests and commit**

Run: `cd docs/Portfolio_Book && python3 tools/run_portfolio_command.py -- uv run pytest tests/test_models.py tests/test_canonical_io.py tests/test_environment_runner.py -q`

Expected: PASS.

```bash
set -euo pipefail
git add docs/Portfolio_Book/pyproject.toml docs/Portfolio_Book/uv.lock docs/Portfolio_Book/tools/portfolio_builder docs/Portfolio_Book/tools/run_portfolio_command.py docs/Portfolio_Book/tests/test_models.py docs/Portfolio_Book/tests/test_canonical_io.py docs/Portfolio_Book/tests/test_environment_runner.py
git diff --cached --check
git commit -m "build(portfolio): add evidence tooling package"
```

---

### Task 2: Add deterministic redaction and copied-content safety

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/redaction.py`
- Create: `docs/Portfolio_Book/tests/test_redaction.py`

**Interfaces:**

- Produces: `RedactionResult(value: bytes, raw_hash: str, stored_hash: str, kinds: tuple[str, ...])`, `redact_text(data, allowed_contacts)`, and `redact_binary_patch(data, blob_metadata)`.
- Consumes: bytes copied from Git/GitHub, documents, and AI traces; the owner's allowed public email is `mps756@gmail.com`.

- [ ] **Step 1: Write failing secret/contact tests**

Test GitHub tokens, AWS access keys, PEM headers, bearer tokens, cookies, URLs with credentials, a third-party email, and a Git binary-patch payload. Assert the owner email remains, textual values become `[REDACTED:<kind>]`, binary bytes become blob metadata plus `[REDACTED BINARY PAYLOAD]`, and original/stored hashes remain distinct and reproducible.

Run: `cd docs/Portfolio_Book && python3 tools/run_portfolio_command.py -- uv run pytest tests/test_redaction.py -q`

Expected: FAIL because `redaction.py` does not exist.

- [ ] **Step 2: Implement ordered, idempotent redaction**

Compile named regex rules once, apply longest/specific token rules before generic email rules, and return sorted unique redaction kinds. Assert applying redaction to an already stored representation is byte-idempotent. Hash original bytes before replacement, hash stored bytes afterward, and never log the pre-redaction match.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_redaction.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/redaction.py docs/Portfolio_Book/tests/test_redaction.py
git diff --cached --check
git commit -m "feat(portfolio): redact copied evidence safely"
```

---

### Task 3: Freeze refs, tracked files, and AI-trace hashes

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/snapshot.py`
- Create: `docs/Portfolio_Book/tests/test_snapshot.py`

**Interfaces:**

- Produces: `capture_local_snapshot(repo: Path, boundary: SourceBoundary, clock: Clock) -> SnapshotManifest` and `write_snapshot_manifest()` with an exact `TrackedFileSnapshot` for every source-boundary tree entry and exact verified `ExternalInputFile` entries.
- Consumes: committed `source_boundary.json`, `git for-each-ref`, `git symbolic-ref`, `git rev-parse HEAD`, `git rev-list`, `git ls-tree -r -z`, filesystem bytes under `docs/ai-traces/`, and the three boundary-listed external PDFs opened read-only.

- [ ] **Step 1: Write a failing snapshot test with branch, tag, note, and symbolic remote HEAD**

Create a temporary Git repository, lock a source commit, add two tooling commits on the workflow branch, and add an unrelated branch/tag/note plus symbolic remote HEAD. Assert the manifest records exact observed ref names/object SHAs and observed HEAD, pins only the workflow entry in `semantic_refs` to the locked source commit, excludes both tooling commits from the semantic reachable set, retains the unrelated branch commit, records the exact ordered excluded chain, and captures NUL-safe source-tree paths plus Git mode/type/blob SHA/collection rule, AI file size/SHA-256, three read-only external input paths/sizes/SHA-256 values, exact source-tree blob/SHA identities for every bootstrap legacy-owned output, and UTC `started_at`/`completed_at` supplied by a fake clock. Include a file whose mutable worktree bytes differ from the frozen blob and require the snapshot to retain the source-boundary blob SHA. Tests must also reject a wrong first parent, a merge in the excluded chain, a second ref pointing into that chain, an external path that escapes the repository, a missing external PDF, one altered external byte, or a legacy ownership entry absent/mismatched at the source tree.

- [ ] **Step 2: Implement an injected command runner and byte-safe snapshot capture**

Use `subprocess.run(..., check=True, capture_output=True)` behind `CommandRunner.run(args: Sequence[str]) -> bytes`. Parse and canonical-hash the boundary file, verify its objects/tree/parent/workflow ref, record all current refs without alteration as `observed_refs`, and derive `semantic_refs` by changing exactly the workflow ref's commit target to `source_snapshot_head`. Require a strictly linear `first_excluded_commit..observed_head` chain and reject any other ref whose direct or peeled target is in it. Parse `git ls-tree -r -z --full-tree <source_snapshot_head>` into exact mode/type/object-SHA/path tuples, assign every tuple one collection rule, and sort refs/paths by UTF-8 byte representation. Resolve each external input as a repository-contained regular file without following an escaping symlink, stream it read-only, and require its exact boundary size/SHA before copying the identity row into the snapshot; never add the original PDF to Git or an archive. For every `legacy_owned_outputs` entry, require an exact tracked blob at `source_snapshot_head`, match both Git object ID and SHA-256 of `git cat-file blob` bytes, and carry the immutable row into the snapshot for the one-time publication migration. Do not read `.env` or follow symlinks outside the repository.

- [ ] **Step 3: Verify deterministic output and commit**

Run the test twice and assert byte-identical manifests after normalizing the injected clock.

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_snapshot.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/snapshot.py docs/Portfolio_Book/tests/test_snapshot.py
git diff --cached --check
git commit -m "feat(portfolio): freeze evidence snapshot boundary"
```

---

### Task 4: Capture every root/parent Git diff and deterministic archives

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/git_collector.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/archive.py`
- Create: `docs/Portfolio_Book/tests/test_git_collector.py`
- Create: `docs/Portfolio_Book/tests/test_archive.py`

**Interfaces:**

- Produces: one `GIT-<sha>` commit `SourceRecord`, root/parent diff records, and `write_patch_volumes(entries, max_bytes=90_000_000) -> tuple[ArchiveVolume, ...]`.
- Consumes: the snapshot ref manifest and Git objects only.

- [ ] **Step 1: Write failing root, normal, rename, binary, detached-HEAD, and two-parent merge tests**

Build a temporary history with one root, one normal commit, two diverged branches, a merge, and a final detached commit unreachable from every named ref. Assert the detached frozen HEAD is still enumerated, diff count is `1` for the root and `len(parents)` otherwise, IDs end in `ROOT`, `P01`, `P02`, and each patch hash/archive member resolves.

- [ ] **Step 2: Implement byte-safe Git enumeration**

Use `git rev-list --stdin --topo-order` from the frozen `snapshot.semantic_refs` object/peeled SHA set **plus** the frozen `snapshot.source_snapshot_head`, never from `observed_refs`; use `git cat-file` for metadata and `git diff --binary --full-index -M -C` with the exact parent/child arguments; use empty tree `4b825dc642cb6eb9a060e54bf8d69288fbee4904` for roots. Parse `--name-status -z` and `--numstat -z`; never split filenames on whitespace. Do not call `git rev-list --all` after the snapshot. Coverage must assert that every `excluded_workflow_commit_shas_at_capture` value is absent from semantic source records while remaining preserved in the boundary fields.

- [ ] **Step 3: Implement deterministic split archives**

Write safe stored patch members sorted by source ID with `mtime=uid=gid=0`, empty owner names, mode `0o644`, and a gzip header `mtime=0`. Split a stored patch into ordered 8,000,000-byte parts before archiving and record a reassembly manifest with whole/part hashes. Group at most 50,000,000 uncompressed bytes per volume and recursively bisect any compressed volume above 90,000,000 bytes. No committed volume may exceed 90,000,000 bytes.

- [ ] **Step 4: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_git_collector.py tests/test_archive.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/git_collector.py docs/Portfolio_Book/tools/portfolio_builder/archive.py docs/Portfolio_Book/tests/test_git_collector.py docs/Portfolio_Book/tests/test_archive.py
git diff --cached --check
git commit -m "feat(portfolio): capture every git parent diff"
```

---

### Task 5: Build a paginated, resumable GitHub client

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/github_client.py`
- Create: `docs/Portfolio_Book/tests/fixtures/github/`
- Create: `docs/Portfolio_Book/tests/test_github_client.py`

**Interfaces:**

- Produces: `GitHubClient.get_pages(path, params, accept)`, `get_json()`, `get_bytes()`, `RateLimitState`, and `CheckpointStore`.
- Consumes: `GITHUB_TOKEN`, falling back to captured `gh auth token`; the token value is never returned or logged.

- [ ] **Step 1: Write failing fixture-transport tests**

Test Link-header pagination, `ETag`/304 resume, 403 rate-limit reset, 502 exponential backoff, 404 `confirmed-unavailable`, and redacted error output. Inject a `Transport` and fake clock; do not call GitHub in unit tests.

- [ ] **Step 2: Implement the client and checkpoint contract**

Use `urllib.request` with `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`. Checkpoints contain endpoint, params, ETag, fetched_at, response hash, page number, and availability status. Sleep only in the production clock implementation; tests advance a fake clock.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_github_client.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/github_client.py docs/Portfolio_Book/tests/fixtures/github docs/Portfolio_Book/tests/test_github_client.py
git diff --cached --check
git commit -m "feat(portfolio): add resumable github evidence client"
```

---

### Task 6: Enumerate, hydrate, and reconcile every PR and issue

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/github_collector.py`
- Create: `docs/Portfolio_Book/tests/test_github_collector.py`

**Interfaces:**

- Produces: `collect_pull_requests(client, repository, snapshot_id, archive_dir)`, `collect_issues(client, repository, snapshot_id, archive_dir)`, `reconcile_github() -> ReconciliationResult`, deterministic `github-records-*.tar.gz` safe-response volumes, and separate `SourceRecord` values for each PR/issue plus every commit membership, changed file, review, review comment, conversation comment, timeline event, reaction, requested-reviewer event, and availability record.
- Consumes: `GitHubClient`, repository `zbnerd/probabilistic-valuation-engine`, and snapshot ID.

- [ ] **Step 1: Write failing container/child mutation and reconciliation tests**

Fixture pass one returns PRs 1–2 and issues 3–4; the detail phase changes PR 2 and adds issue 5; reconciliation must refetch PR 2/issue 5 and finish only after the next enumeration has zero delta. Give comments/reviews/reactions more than one page and mutate a child page without changing its parent `updated_at`; this must also force full item rehydration and another pass. Assert comments, reviews, review comments, commits, files, timeline, reactions, requested reviewers, and `.patch` bytes are separately hashed and linked.

- [ ] **Step 2: Implement PR/issue hydration**

Paginate `/pulls?state=all&per_page=100` and `/issues?state=all&per_page=100` through every Link page; filter PR-shaped objects from `/issues` and never use GitHub Search's capped result set. Independently paginate every applicable commits/files/reviews/review-comments/conversation-comments/timeline/reactions/requested-reviewers endpoint to its final Link page, and fetch `.patch`/non-paginated detail endpoints too. Hash original response bytes, store redacted JSON/patch representations, normalize every child to a stable ID, and persist an endpoint fingerprint containing the exact page-number set, ordered page hashes, stable-child-ID set, params/Accept variant, and availability state. Treat a final 404/410/451 as `confirmed-unavailable`; keep rate limits, 403/429/5xx, malformed responses, capped/incomplete pagination, and count mismatches transient until retry policy succeeds or reports a blocker.

- [ ] **Step 3: Implement stable reconciliation**

Record `enumeration_started_at`, `enumeration_completed_at`, item `fetched_at`, the exact hydrated response's captured `updated_at`, every container/child endpoint fingerprint, and final `reconciled_at`. Re-enumerate the number set and `updated_at` map **and** conditionally re-fetch every endpoint fingerprint. Rehydrate the union of new numbers, changed parent values, changed page sets/hashes/child-ID sets, and changed availability states. Require a following complete pass with number-set, parent, and child-endpoint deltas all empty. Never use parent `updated_at` as proof that children are unchanged and never compare it to wall-clock `fetched_at`.

- [ ] **Step 4: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_github_collector.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/github_collector.py docs/Portfolio_Book/tests/test_github_collector.py
git diff --cached --check
git commit -m "feat(portfolio): reconcile all github records"
```

---

### Task 7: Enumerate every text/PDF document unit and claim candidate

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/document_collector.py`
- Create: `docs/Portfolio_Book/tests/fixtures/documents/sample.md`
- Create: `docs/Portfolio_Book/tests/test_document_collector.py`

**Interfaces:**

- Produces: `collect_documents(repo, snapshot, archive_dir) -> tuple[list[SourceRecord], list[DocumentClaim]]` plus deterministic `document-records-*.tar.gz` volumes containing complete redacted review representations.
- Consumes: tracked document bytes at the frozen Git SHA and only the snapshot-verified external PDF bytes; it does not treat any other mutable/ignored worktree file as evidence.

- [ ] **Step 1: Write failing Markdown/PDF unit tests**

Assert heading, paragraph, list item, table row, fenced diagram, and independent sentences get stable line-based IDs. Commit one normal document and one prior generated document under `docs/Portfolio_Book/output/`, change their worktree bytes without committing, and require collection to return only frozen blob bytes with respective `project-evidence/primary-record` and `project-evidence/legacy-derived-record` mappings. Generate ignored external guide/resume/portfolio fixtures with boundary-locked paths/sizes/SHAs: guide records must be `structural-reference/structural-reference`, the two personal PDFs `personal-evidence/personal-record`, and page text blocks/image objects must get stable page/block IDs and hashes. An unlisted ignored PDF, a role/scope/authority mismatch, or post-snapshot byte change must be rejected rather than collected.

- [ ] **Step 2: Implement frozen-content loading and unit splitting**

Resolve each selected tracked path from the immutable `snapshot.tracked_files` entry, require its recorded type to be `blob`, verify that entry against `git ls-tree <snapshot.source_snapshot_head>`, then read bytes with the single object argument `git cat-file blob <tracked_file.object_sha>`; never read mutable worktree bytes for a tracked source or pass SHA/path as separate `cat-file` arguments. Mark normal records `project-evidence/primary-record`, but mark every tracked prior generated document under `docs/Portfolio_Book/output/` `project-evidence/legacy-derived-record`. Separately open only `snapshot.external_input_files`, re-stream and require the recorded size/SHA immediately before parsing, and emit external-input source IDs that retain role/path/original identity hash without archiving the original PDF bytes. Map `renewal-guide` only to `structural-reference/structural-reference`, and both legacy personal PDFs to `personal-evidence/personal-record`; preserve scope/authority on every descendant `DocumentClaim`. Record non-blob entries explicitly rather than guessing bytes. Use markdown-it token line maps, sentence splitting that preserves exact source spans, and PyMuPDF page text-block/image extraction. Capture all 31 guide pages, every page/block from the two legacy PDFs, and the existing resume photo image object. Redact sensitive substrings in stored text/metadata, archive the complete safe derived block/claim representations with member hashes, and set initial semantic classification to `unreviewed`.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_document_collector.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/document_collector.py docs/Portfolio_Book/tests/fixtures/documents/sample.md docs/Portfolio_Book/tests/test_document_collector.py
git diff --cached --check
git commit -m "feat(portfolio): inventory every document claim unit"
```

---

### Task 8: Read every AI-trace artifact without dropping corrupt records

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/ai_trace_collector.py`
- Create: `docs/Portfolio_Book/tests/test_ai_trace_collector.py`

**Interfaces:**

- Produces: `collect_ai_traces(repo, snapshot, archive_dir) -> Iterator[SourceRecord]`, one file container plus a separately scoped/authorized child `SourceRecord` for every parsed or malformed JSON/JSONL/tool/patch/logical entry, and deterministic `ai-trace-records-*.tar.gz` volumes.
- Consumes: only files listed in the frozen AI-trace manifest.

- [ ] **Step 1: Write failing plain/gzip/malformed/interleaved tests**

Create ordinary NDJSON, pretty-printed whitespace-separated concatenated JSON objects matching the repository's `tool-use.jsonl`/`prompts.jsonl` shape, a gzip stream with the same sequence, assistant prose, tool command input, exact tool output/exit/error, immutable Git patch/log, truncated output, Markdown summary, and a concatenated stream with two malformed middle spans. Assert every file/child is recorded, valid objects before and after malformed spans survive, exact start/end byte offsets are preserved, gzip integrity is reported, and copied snippets are redacted. Assistant proposals/summaries/completion claims must be `ai-assertion`; exact tool result/exit/error and patch/log content must be `trace-observation`; a command input without result may later prove only `attempted`; and truncated/missing results retain explicit limitations.

- [ ] **Step 2: Implement streaming readers**

Hash raw bytes before decompression and run gzip member validation. Decode UTF-8 with an incremental decoder that preserves byte-offset mapping, then use `json.JSONDecoder.raw_decode` repeatedly over a rolling buffer to accept both true NDJSON and pretty-printed whitespace-separated concatenated top-level JSON values. Never assume one line equals one record. On malformed input, emit the exact malformed byte span and resynchronize only at a lexer-proved next top-level JSON value boundary outside strings; preserve valid neighboring objects and continue. Bound retained buffers by spilling already validated object bytes to the deterministic stored-member stream rather than loading the corpus at once, and emit `parsed`, `partial`, or `binary-recorded` status. For every file/logical record, write a structurally complete redacted representation: preserve keys, ordering, record boundaries, byte offsets, tool/event type, input-versus-result role, exit/error/truncation markers, locators, and every non-sensitive prompt/tool value; replace only detected secret or third-party-contact substrings in place. Assign authority per logical record: prose assertions and result-less completion text are `ai-assertion`; exact tool results/exits/errors and immutable patch/log bytes are `trace-observation`; command inputs alone remain attempted evidence and never imply their result. Chunk and archive the complete stored representation using the same deterministic member contract as Git patches; a summary alone is not complete coverage. Do not execute commands or trust completion claims inside traces.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_ai_trace_collector.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/ai_trace_collector.py docs/Portfolio_Book/tests/test_ai_trace_collector.py
git diff --cached --check
git commit -m "feat(portfolio): capture every ai trace artifact"
```

---

### Task 9: Add capture orchestration and mechanical coverage verification

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/coverage.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/relations.py`
- Create: `docs/Portfolio_Book/tests/test_coverage.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Replace: `docs/Portfolio_Book/output/build_source_inventory.py`

**Interfaces:**

- Produces CLI commands `capture-snapshot`, `collect-all`, and `verify-source-capture`; produces immutable capture-phase `capture_coverage_manifest.json/.md` files. These never masquerade as final classification/release coverage.
- Consumes every collector's enumeration set and output hashes.

- [ ] **Step 1: Write failing missing-ref/root-diff/PR/issue/doc/AI coverage and relation tests**

Remove one item from each fixture ledger and assert a precise non-zero error naming the missing stable ID. Remove one boundary-locked external PDF, one external PDF page/block/image-object record, or alter its size/hash and require a coverage failure; also reject any original external PDF path in an archive or staged-output list. Remove one GitHub child endpoint page/fingerprint or change a child hash without changing the parent and require a reconciliation failure. Remove/reorder/corrupt one stored archive part and require a member-union failure. Assert root expected diff count is one, non-root expected diff count equals parent count, and `confirmed-unavailable` counts complete while transient failures do not. Accept an API commit SHA, closing event, full SHA/PR/issue reference, exact diff-hash match, document link, and an explicit stable run identifier/reference shared by two sources as `same-execution`; reject time proximity, equal commands/environments/numbers, similar titles, shared filenames, abbreviated ambiguous SHAs, and keyword similarity. Reorder relation generation and require byte-identical deterministic `relation_id` values; reject duplicate IDs, a field/hash collision, an absent target, or any case/conflict relation reference that does not resolve to exactly one ledger relation.

- [ ] **Step 2: Implement orchestration and compatibility wrapper**

`collect-all` consumes the existing local snapshot and runs Git → documents → AI → GitHub → final reconciliation, then derives only exact-reference relations, writes canonical JSONL, and finalizes the manifest atomically. It must never recapture observed/semantic refs, HEAD, tracked paths, or AI paths after the cutoff, and Git enumeration must use only the frozen semantic ref set. Relation rules are limited to GitHub closing/cross-reference events, PR API commit SHAs, full commit/PR/issue references in source text, exact AI diff-hash equality, document links, and `same-execution` only when every linked source explicitly carries the same stable run identifier or direct reference. Time/topic/file/date/command/environment/value similarity is forbidden. The old script becomes a small CLI wrapper; it must not duplicate logic.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_coverage.py -q
python3 tools/run_portfolio_command.py -- uv run pytest -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/coverage.py docs/Portfolio_Book/tools/portfolio_builder/relations.py docs/Portfolio_Book/tools/portfolio_builder/cli.py docs/Portfolio_Book/tests/test_coverage.py docs/Portfolio_Book/output/build_source_inventory.py
git diff --cached --check
git commit -m "feat(portfolio): verify exhaustive source capture"
```

---

### Task 10: Capture the real immutable evidence snapshot

**Files:**

- Generate: `docs/Portfolio_Book/output/research/snapshot_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/source_records.jsonl`
- Generate: `docs/Portfolio_Book/output/research/document_claim_inventory.jsonl`
- Generate: `docs/Portfolio_Book/output/research/commit_inventory.csv`
- Generate: `docs/Portfolio_Book/output/research/pr_inventory.jsonl`
- Generate: `docs/Portfolio_Book/output/research/issue_inventory.jsonl`
- Generate: `docs/Portfolio_Book/output/research/ai_trace_inventory.jsonl`
- Generate: `docs/Portfolio_Book/output/research/capture_coverage_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/capture_coverage_manifest.md`
- Generate: `docs/Portfolio_Book/output/research/*-part-[0-9][0-9][0-9].jsonl.gz`
- Generate: `docs/Portfolio_Book/output/research/commit-diffs-*.tar.gz`
- Generate: `docs/Portfolio_Book/output/research/github-records-*.tar.gz`
- Generate: `docs/Portfolio_Book/output/research/ai-trace-records-*.tar.gz`
- Generate: `docs/Portfolio_Book/output/research/document-records-*.tar.gz`

**Interfaces:**

- Consumes: the committed semantic source boundary, the exact observed refs/HEAD at capture, and the reconciled GitHub state.
- Produces: the immutable input to the case/content plan.

- [ ] **Step 1: Record the clean cutoff and run the resumable capture**

**Resume note:** The present run must not execute `capture-snapshot`; resume `collect-all` from the existing unfinalized `snapshot_manifest.json` and its existing 20,656 checkpoints and archive volumes without deleting or rebuilding them. The snapshot-creation command below remains only as the historical record of how this run began.

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book capture-snapshot \
  --repo ../.. \
  --boundary source_boundary.json \
  --output output/research/snapshot_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book collect-all \
  --repo ../.. \
  --repository zbnerd/probabilistic-valuation-engine \
  --manifest output/research/snapshot_manifest.json \
  --output output/research
```

Expected: the manifest reports semantic HEAD `6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd`, first excluded commit `aa2338c54291e5ad2d81673c0bc4fabf4577cec4`, the exact observed workflow tip, and a semantic ref set that excludes every workflow commit. Long-running collection emits progress counts but no tokens, prompts, or secret values; it resumes from checkpoints after rate-limit waits.

- [ ] **Step 2: Run the mechanical coverage gate**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-source-capture \
  --manifest output/research/snapshot_manifest.json \
  --root output/research
```

Expected: exit 0 with observed/semantic ref validation, semantic commit/diff/PR/issue/document/AI enumeration deltas all zero, the three external input identities and every required PDF page/block/image object accounted for, excluded workflow/source intersection zero, final GitHub container and child page/hash/ID fingerprint deltas zero, no transient failures, and every safe derived archive hash/size valid. Semantic `unreviewed` counts are reported for the next plan and are not a capture failure.

- [ ] **Step 3: Inspect size, redaction, and staged scope**

Run secret scanning over generated text/JSONL and every safe archive member, verify each archive volume is at most 90,000,000 bytes and every chunk sequence reassembles to its recorded stored hash, and stage only exact generated `docs/Portfolio_Book` task outputs. Confirm every available review target has a complete `stored_members` union, none of the three original external PDF paths appears in any archive/staged path list, and `.gitignore` is not staged.

- [ ] **Step 4: Commit the snapshot**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine/.worktrees/exhaustive-portfolio-rebuild
git add docs/Portfolio_Book/output/research/snapshot_manifest.json \
  docs/Portfolio_Book/output/research/source_records.jsonl \
  docs/Portfolio_Book/output/research/document_claim_inventory.jsonl \
  docs/Portfolio_Book/output/research/commit_inventory.csv \
  docs/Portfolio_Book/output/research/pr_inventory.jsonl \
  docs/Portfolio_Book/output/research/issue_inventory.jsonl \
  docs/Portfolio_Book/output/research/ai_trace_inventory.jsonl \
  docs/Portfolio_Book/output/research/capture_coverage_manifest.json \
  docs/Portfolio_Book/output/research/capture_coverage_manifest.md
python3 docs/Portfolio_Book/tools/run_portfolio_command.py -- uv run portfolio-book \
  list-locked-jsonl-shards \
  --coverage docs/Portfolio_Book/output/research/capture_coverage_manifest.json \
  --repo . \
  | git add -f --pathspec-from-file=- --pathspec-file-nul
find docs/Portfolio_Book/output/research -maxdepth 1 \
  -type f \( -name 'commit-diffs-*.tar.gz' \
  -o -name 'github-records-*.tar.gz' \
  -o -name 'ai-trace-records-*.tar.gz' \
  -o -name 'document-records-*.tar.gz' \) \
  -print0 | xargs -0 -r git add -f --
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-source-capture \
  --manifest output/research/snapshot_manifest.json \
  --root output/research
cd ../..
git diff --cached --check
git commit -m "docs(portfolio): capture exhaustive evidence snapshot"
```

This capture commit and every later implementation/checkpoint/output commit are outside the immutable source snapshot by design. The final publication manifest records the exact post-cutoff commit/parent-diff boundary; it must not describe this capture commit as the only excluded commit.
