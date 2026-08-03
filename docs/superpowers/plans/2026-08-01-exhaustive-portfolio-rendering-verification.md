# Exhaustive Portfolio Rendering and Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert every approved case into a compiled Mermaid diagram and render the exhaustive resume, portfolio, and evidence book as verified A4 PDF families while preserving the original ID photo and every source/case mapping.

**Architecture:** A content-contract loader treats the case catalog and three Markdown files as immutable inputs. It serializes structured, source-backed `DiagramSpec` values to Mermaid, compiles all diagrams in a temporary tree, renders atomic document units through ReportLab, splits oversized families deterministically, and publishes only after mechanical and page-by-page visual gates pass.

**Tech Stack:** Python 3.12, uv 0.11+, pytest 9.1.1, ReportLab 5.0.0, PyMuPDF 1.28.0, pypdf 6.14.2, Pillow 12.3.0, svglib 1.6.0, Node.js 22, `@mermaid-js/mermaid-cli` 11.16.0, Mermaid 11.16.0, Chromium/Puppeteer, Ghostscript.

**Spec:** `docs/superpowers/specs/2026-08-01-exhaustive-portfolio-rebuild-design.md`

**Depends on:** `2026-08-01-exhaustive-portfolio-case-content.md` completed with `verify-content` passing.

## Global Constraints

Every fenced shell block starts from `/home/maple/probabilistic-valuation-engine`; working-directory changes never carry into a later block.

- Render one Mermaid source, SVG, and high-resolution PNG for every catalog case. The new temporary/build-manifest diagram closure must be exact. In the published tree before finalization, an extra path is tolerated only when it is a hash-valid previous/bootstrap-owned retirement candidate and is never consumed; unknown, modified, missing-current, or uncompiled diagrams fail immediately. Post-finalization, every retired path must be absent.
- Diagram labels, components, edges, ordering, and causal language come only from cited `DiagramSpec` evidence.
- Do not generate, retouch, crop, recolor, or re-encode the face image; extract the existing JPEG bytes from `이력서.pdf` exactly.
- Keep the original three PDFs byte-identical. Open them read-only and verify their SHA-256 before and after every real build.
- Render A4 pages with an embedded Korean-capable font, navigable TOC/bookmarks, visible source references, and no clipped content.
- Never split a case across PDF volumes. Resume/portfolio cases and evidence-book source records are atomic publication units.
- Volume size/page thresholds are technical packaging boundaries, not content caps; every unit remains in exactly one volume.
- Use a temporary sibling build tree. Do not replace any published artifact unless every diagram, PDF, security, coverage, and syntax check succeeds.
- A generated PDF or archive must remain below 90,000,000 bytes. A single atomic unit over that limit is a blocker, not permission to omit it.
- Do not modify `.gitignore`; force-add only the exact manifest-owned ignored artifacts returned by the validated staging command, never a wildcard or directory.
- This rendering slice does not push or open a PR; the already authorized feature-branch push is performed only by the umbrella plan after the publication commit and independent review. Do not run a load test, start application services, or mutate external systems here.

## Verified Source Constants

```text
docs/Portfolio_Book/2026년 이력서 포트폴리오 리뉴얼.pdf
  SHA-256 e67b747879168c5864eef1ea85cde54a658c0ea42f58d08e5ef752b706a59a7b
  page count 31

docs/Portfolio_Book/이력서.pdf
  SHA-256 050ebd6dc8d02e1969d9829d0d93055075fe662d75819d95802a1074b543db2e
  page 1 image xref 7, DCT JPEG, 360x400, 26,003 bytes
  image SHA-256 ffb35d203301e5c466a1243a57a0554d19af33536aaa8435ca1572ce8ab5f3e8

docs/Portfolio_Book/포트폴리오.pdf
  SHA-256 fb2104e6e9167c9162b865c25ca9a9afbe238250ec4af252f91659729aadba7b

/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc
  SHA-256 79c18ebe7b811951e8311bad7103ebeae8c337ed9988ea69e8a78a66cfe029b9
```

The three PDF identities are consumed from the verified `SnapshotManifest.external_input_files`/source-boundary hash, then checked against these audited constants; the build never treats an ignored PDF as a tracked source or stages it. The build records the actual tool and font hashes in its manifest and refuses a mismatch; it does not silently substitute a different font or image.

## Shared Rendering Contracts

```python
@dataclass(frozen=True, slots=True)
class PhotoAsset:
    source_pdf_sha256: str
    page_index: int
    xref: int
    width: int
    height: int
    byte_count: int
    image_sha256: str
    path: Path

@dataclass(frozen=True, slots=True)
class DiagramArtifact:
    case_id: str
    source_path: Path
    source_sha256: str
    svg_path: Path
    svg_sha256: str
    png_path: Path
    png_sha256: str
    mermaid_cli_version: str
    mermaid_core_version: str
    viewport_width: float
    viewport_height: float

@dataclass(frozen=True, slots=True)
class DiagramSourceArtifact:
    case_id: str
    path: Path
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class DiagramSourceManifest:
    case_catalog_sha256: str
    artifacts: tuple[DiagramSourceArtifact, ...]

@dataclass(frozen=True, slots=True)
class DiagramRenderManifest:
    source_manifest_sha256: str
    artifacts: tuple[DiagramArtifact, ...]

@dataclass(frozen=True, slots=True)
class DocumentUnit:
    family: Literal["resume", "portfolio", "evidence"]
    unit_id: str
    unit_kind: Literal[
        "cover", "toc", "profile", "project-metadata", "result-index-row",
        "case", "record-only-row", "source-conflict", "coverage-entry", "evidence-target"
    ]
    sort_key: tuple[str, ...]
    source_ids: tuple[str, ...]
    required_once: bool
    flowables: tuple[Flowable, ...]

@dataclass(frozen=True, slots=True)
class VolumeArtifact:
    family: str
    ordinal: int
    unit_ids: tuple[str, ...]
    first_id: str
    last_id: str
    page_count: int
    byte_count: int
    sha256: str
    path: Path

@dataclass(frozen=True, slots=True)
class ToolchainReport:
    python_versions: dict[str, str]
    node_versions: dict[str, str]
    browser_version: str
    font_path: str
    font_sha256: str

@dataclass(frozen=True, slots=True)
class MarkdownCaseBlock:
    case_id: str
    rendered_title: str
    start_line: int
    end_line: int
    source_ids: tuple[str, ...]
    image_path: str | None

@dataclass(frozen=True, slots=True)
class DocumentContract:
    snapshot_id: str
    case_catalog_sha256: str
    ordered_case_ids: tuple[str, ...]
    resume_cases: tuple[MarkdownCaseBlock, ...]
    portfolio_cases: tuple[MarkdownCaseBlock, ...]
    portfolio_image_paths: tuple[str, ...]
    evidence_target_ids: tuple[str, ...]
    required_unit_ids_by_family: dict[str, tuple[str, ...]]

@dataclass(frozen=True, slots=True)
class PdfProbe:
    path: Path
    page_count: int
    byte_count: int
    sha256: str

@dataclass(frozen=True, slots=True)
class VolumePlan:
    family: str
    ordered_unit_groups: tuple[tuple[str, ...], ...]
    probe_results: tuple[PdfProbe, ...]

@dataclass(frozen=True, slots=True)
class PdfDocumentSpec:
    family: Literal["resume", "portfolio", "evidence"]
    title: str
    units: tuple[DocumentUnit, ...]
    photo: PhotoAsset | None
    diagrams: tuple[DiagramArtifact, ...]
    font_path: Path
    font_sha256: str

@dataclass(frozen=True, slots=True)
class PdfFamilySpec:
    family: Literal["resume", "portfolio", "evidence"]
    base_filename: str
    ordered_units: tuple[DocumentUnit, ...]
    max_bytes: int
    max_pages: int

PDF_BASE_FILENAMES: Final[dict[str, str]] = {
    "resume": "이력서_완성본.pdf",
    "portfolio": "포트폴리오_완성본.pdf",
    "evidence": "전수증거장부.pdf",
}

@dataclass(frozen=True, slots=True)
class PdfFamilyManifest:
    family: str
    base_path: Path
    base_is_master_index: bool
    volumes: tuple[VolumeArtifact, ...]
    all_unit_ids: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class PdfManifest:
    snapshot_id: str
    case_catalog_sha256: str
    families: tuple[PdfFamilyManifest, ...]
    generated_paths: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class ValidationReport:
    checks: dict[str, int]
    findings: tuple[ValidationFinding, ...]

@dataclass(frozen=True, slots=True)
class BuildConfig:
    root: Path
    max_pdf_bytes: int
    max_pdf_pages: int
    mermaid_cli: Path
    mermaid_config: Path

@dataclass(frozen=True, slots=True)
class OwnershipContext:
    source_boundary: Path
    snapshot_manifest: Path
    previous_publication_manifest: Path | None

@dataclass(frozen=True, slots=True)
class RetiredArtifact:
    path: str
    ownership_basis: Literal[
        "previous-publication-manifest", "bootstrap-legacy-boundary"
    ]
    ownership_record_sha256: str
    head_blob_sha256: str | None

@dataclass(frozen=True, slots=True)
class BuildManifest:
    snapshot_id: str
    publication_root: str
    input_hashes: dict[str, str]
    generated_paths: tuple[str, ...]
    artifact_hashes: dict[str, str]
    retirement_candidate_paths: tuple[str, ...]

@dataclass(frozen=True, slots=True)
class ExcludedParentDiff:
    commit_sha: str
    parent_sha: str | None
    ordinal: int
    raw_diff_sha256: str

@dataclass(frozen=True, slots=True)
class PublicationBoundary:
    source_snapshot_head: str
    first_excluded_commit: str
    publication_parent: str
    excluded_commit_shas: tuple[str, ...]
    excluded_parent_diffs: tuple[ExcludedParentDiff, ...]

@dataclass(frozen=True, slots=True)
class PublicationManifest:
    snapshot_id: str
    build_manifest_sha256: str
    pdf_manifest_sha256: str
    visual_audit_manifest_sha256: str
    capture_coverage_manifest_sha256: str
    release_coverage_manifest_sha256: str
    previous_publication_manifest_sha256: str | None
    boundary: PublicationBoundary
    generated_paths: tuple[str, ...]
    artifact_hashes: dict[str, str]
    retired_artifacts: tuple[RetiredArtifact, ...]

@dataclass(frozen=True, slots=True)
class EvidenceBundle:
    snapshot_manifest: Path
    source_records: Path
    document_claims: Path
    classifications: Path
    classified_source_records: Path
    source_conflicts: Path
    observation_relations: Path
    case_catalog: Path
    case_source_map: Path
    profile_facts: Path
    profile_fact_verifications: Path
    project_catalog: Path
    project_fact_verifications: Path
    fact_adjudications: Path
    fact_adjudication_receipt: Path
    capture_coverage: Path
    release_coverage: Path

@dataclass(frozen=True, slots=True)
class VisualPageReview:
    audit_run_id: str
    reviewer_role: Literal["primary", "independent"]
    pdf_sha256: str
    page_number: int
    raster_sha256: str
    decision: Literal["pass", "finding"]
    finding: str | None
    reviewed_at: str

@dataclass(frozen=True, slots=True)
class VisualPageArtifact:
    pdf_path: str
    pdf_sha256: str
    page_number: int
    raster_path: str
    raster_sha256: str
    contact_sheet_path: str
    contact_sheet_sha256: str
    contact_sheet_cell: int
    full_page_path: str | None
    full_page_sha256: str | None

@dataclass(frozen=True, slots=True)
class VisualAuditManifest:
    pdf_sha256_by_path: dict[str, str]
    pages: tuple[VisualPageArtifact, ...]
    primary_audit_run_id: str | None
    independent_audit_run_id: str | None
    reviews: tuple[VisualPageReview, ...]
    generated_paths: tuple[str, ...]
    artifact_hashes: dict[str, str]

def load_document_contract(
    evidence: EvidenceBundle,
    resume_md: Path,
    portfolio_md: Path,
    evidence_md: Path,
) -> DocumentContract: ...

def resolve_evidence_bundle(
    root: Path,
    exact_paths: Mapping[str, Path],
) -> EvidenceBundle: ...

def split_units(
    ordered_units: tuple[DocumentUnit, ...],
    render_probe: Callable[[tuple[DocumentUnit, ...], Path], PdfProbe],
    max_bytes: int = 90_000_000,
    max_pages: int = 250,
) -> VolumePlan: ...
```

---

### Task 1: Pin the publication toolchain and content contract

**Files:**

- Modify: `docs/Portfolio_Book/pyproject.toml`
- Modify: `docs/Portfolio_Book/uv.lock`
- Create: `docs/Portfolio_Book/package.json`
- Create: `docs/Portfolio_Book/package-lock.json`
- Create: `docs/Portfolio_Book/mermaid-config.json`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/content_contract.py`
- Create: `docs/Portfolio_Book/tests/test_render_toolchain.py`
- Create: `docs/Portfolio_Book/tests/test_content_contract.py`

**Interfaces:**

```python
def verify_render_toolchain(root: Path) -> ToolchainReport
def load_document_contract(
    evidence: EvidenceBundle,
    resume_md: Path,
    portfolio_md: Path,
    evidence_md: Path,
) -> DocumentContract
def resolve_evidence_bundle(
    root: Path,
    exact_paths: Mapping[str, Path],
) -> EvidenceBundle
def parse_case_markers(markdown: str) -> tuple[MarkdownCaseBlock, ...]
```

- [ ] **Step 1: Write failing version/config and document-contract tests**

Assert exact Python/Node dependency versions, the fixed font path/hash, and Mermaid config. `resolve_evidence_bundle` accepts only the explicit canonical path map, rejects an omitted/substituted/outside-root member, and hash-checks every bundle member against snapshot/capture/release coverage before returning it. Contract tests reject missing/duplicate/out-of-order markers, unknown source IDs, case-set deltas, title byte differences, absent section headings, wrong image paths, conflict-side loss/rewrite, and missing/duplicate all-result, record-only-observation, source-conflict, coverage, profile, or project-metadata unit IDs. They hash-check all six fact-chain inputs against release coverage and prove that profile/project primaries plus independent verifications and adjudications resolve to exactly the `FactAdjudicationReceipt` effective candidate/fact union; stale, disputed, duplicate, missing, or mutated fact bytes fail before rendering. Missing-field mode is deterministic: an unsplit complete logical source with result-only evidence must use `원문에 문제·해결 과정 미기록`; an artificially split case with nonempty `continuation_group_ids` must instead use `이 bounded 관찰 범위에 <필드> 미기록` and render every continuation ID, and must not claim whole-source absence. The complete portfolio Markdown image-reference set must equal the catalog-derived Mermaid SVG set; reject the resume photo, remote/data URLs, icons, screenshots, extra local images, and duplicate Mermaid references.

Run: `cd docs/Portfolio_Book && python3 tools/run_portfolio_command.py -- uv run pytest tests/test_render_toolchain.py tests/test_content_contract.py -q`

Expected: FAIL because rendering dependencies/config and contract module do not exist.

- [ ] **Step 2: Pin Python rendering dependencies**

Retain PyMuPDF from the evidence plan, add the exact SVG dependency to `pyproject.toml`, and regenerate `uv.lock`:

```toml
"svglib==1.6.0",
```

Retain the already pinned ReportLab, pypdf, Pillow, markdown-it-py, and pytest versions.

- [ ] **Step 3: Pin Mermaid CLI and core in an isolated package**

```json
{
  "name": "portfolio-book-renderer",
  "private": true,
  "version": "1.0.0",
  "devDependencies": {
    "@mermaid-js/mermaid-cli": "11.16.0",
    "mermaid": "11.16.0"
  }
}
```

`mermaid-config.json` sets `securityLevel` to `strict`, `htmlLabels` to `false`, deterministic IDs/seed, a fixed neutral theme, and the fixed Korean font family. Do not reference remote CSS, fonts, icons, or images.

- [ ] **Step 4: Implement the contract loader**

Load catalog/classified-ledger/source-conflict/release-coverage/Markdown IDs, preserve canonical order for every bounded and unbounded section, and require the resume heading and each portfolio marker's first H2 to equal `rendered_case_title(case)` byte-for-byte. That shared function renders `canonical_title.text` and its complete source-ID citation identically in both files. Require every conflict side/ID to be byte-identical across the registry and all documents. Derive stable required IDs for every profile/project field, all-result row, case, record-only observation row, source-conflict entry, coverage entry, and evidence target; exact family unions are part of `DocumentContract`. The expected image is derived exactly as `![{case.case_id}](../diagrams/rendered/{case.case_id}.svg)`, and the parser compares the global portfolio image set—not only images inside case markers—to that exact set. This stage validates structured diagram specs but does not require rendered files yet.

- [ ] **Step 5: Install, verify, and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
uv lock
python3 tools/run_portfolio_command.py --refresh-node-lock
python3 tools/run_portfolio_command.py --with-node -- \
  uv run pytest tests/test_render_toolchain.py tests/test_content_contract.py -q
cd ../..
git add docs/Portfolio_Book/pyproject.toml docs/Portfolio_Book/uv.lock \
  docs/Portfolio_Book/package.json docs/Portfolio_Book/package-lock.json \
  docs/Portfolio_Book/mermaid-config.json \
  docs/Portfolio_Book/tools/portfolio_builder/content_contract.py \
  docs/Portfolio_Book/tests/test_render_toolchain.py \
  docs/Portfolio_Book/tests/test_content_contract.py
git diff --cached --check
git commit -m "build(portfolio): pin deterministic publication tools"
```

---

### Task 2: Extract the existing ID photo without altering its bytes

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/photo.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_photo.py`
- Generate: `docs/Portfolio_Book/output/assets/profile-photo.jpg`
- Generate: `docs/Portfolio_Book/output/assets/profile-photo.json`

**Interfaces:**

```python
def extract_profile_photo(
    repo: Path,
    source_identity: ExternalInputFile,
    destination: Path,
    page_index: int = 0,
    xref: int = 7,
) -> PhotoAsset
```

- [ ] **Step 1: Write failing source-hash, page/xref, and raw-byte tests**

Test a wrong/missing snapshot role/path/size/PDF hash, wrong page, wrong xref, unexpected image count/type, and altered extracted bytes. The real-fixture assertion resolves only `id-photo-source-resume` from the snapshot-bound external input list, uses the constants above, and checks `360x400`, ratio `0.9`, `26,003` bytes, and the exact image SHA-256.

- [ ] **Step 2: Implement read-only PyMuPDF extraction**

Resolve the repository-contained path only from the verified `ExternalInputFile(role="id-photo-source-resume")`, open it read-only, stream-verify its exact size/SHA immediately before parsing, confirm page 0 references unique image xref 7, call `extract_image(7)`, and write the returned JPEG bytes directly through an atomic sibling temp file. Do not pass them through Pillow or any encoder. Write a canonical JSON provenance sidecar containing the snapshot/boundary identity hash.

Register `extract-profile-photo` in `cli.py` and cover its required arguments/error exit in `test_photo.py`.

- [ ] **Step 3: Generate, verify, and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_photo.py -q
python3 tools/run_portfolio_command.py -- uv run portfolio-book extract-profile-photo \
  --repo ../.. \
  --snapshot output/research/snapshot_manifest.json \
  --external-input-role id-photo-source-resume \
  --output output/assets/profile-photo.jpg \
  --metadata output/assets/profile-photo.json
sha256sum output/assets/profile-photo.jpg
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/photo.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_photo.py \
  docs/Portfolio_Book/output/assets/profile-photo.jpg \
  docs/Portfolio_Book/output/assets/profile-photo.json
git diff --cached --check
git commit -m "feat(portfolio): extract verified resume photo"
```

Expected image hash: `ffb35d203301e5c466a1243a57a0554d19af33536aaa8435ca1572ce8ab5f3e8`.

---

### Task 3: Serialize every structured case diagram to Mermaid source

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/mermaid.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_mermaid.py`
- Create: `docs/Portfolio_Book/tests/fixtures/mermaid/`
- Generate: `docs/Portfolio_Book/output/diagrams/*.mmd`
- Generate: `docs/Portfolio_Book/output/research/diagram_source_manifest.json`

**Interfaces:**

```python
def serialize_diagram(case: CaseRecord) -> str
def write_mermaid_sources(cases: Sequence[CaseRecord], output: Path) -> DiagramSourceManifest
def validate_diagram_evidence(case: CaseRecord) -> tuple[ValidationFinding, ...]
def list_diagram_artifacts(manifest: Path, phase: Literal["source", "rendered"]) -> tuple[str, ...]
```

- [ ] **Step 1: Write failing fact-only serialization tests**

Cover flowchart, two-subgraph before/after, sequence, state, and timeline specs. Reject an uncited node/edge label, unknown node, duplicate node ID, raw Mermaid injection, icon/image directive, remote URL, HTML label, invented relation, and result-only diagrams with anything other than source-to-recorded-result flow. Listing tests reject an empty source manifest, an empty rendered manifest, a manifest with no self/member output, and any non-NUL or outside-root path before a staging pipeline can start.

- [ ] **Step 2: Implement deterministic safe serialization**

Escape labels according to Mermaid grammar, generate stable local node IDs rather than reusing prose, use rectangles and explicit text, and include source IDs in Mermaid comments. Output one UTF-8 LF file per case in canonical case order. Write all sources to a temporary directory; publish only when the source/case set is exact and every `DiagramSpec` passes evidence validation.

Register `write-mermaid`, `verify-mermaid-sources`, and the NUL-output-capable `list-diagram-artifacts` in `cli.py` in this task. The listing command emits the manifest path itself plus exactly its member paths as normalized repository-root-relative NUL records. The source manifest stores the exact catalog hash and new `.mmd` path/hash set. Intermediate source verification reads only that manifest-fixed set and rejects any missing or changed member; it never certifies a directory extra as owned. A directory extra may survive untouched until the final build, where it is tolerated only when the typed previous-publication/bootstrap ownership context proves it is a retirement candidate; any unknown or modified extra blocks publication.

- [ ] **Step 3: Generate the real complete source set**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_mermaid.py -q
python3 tools/run_portfolio_command.py -- uv run portfolio-book write-mermaid \
  --catalog output/research/case_catalog.jsonl \
  --output output/diagrams \
  --manifest output/research/diagram_source_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-mermaid-sources \
  --catalog output/research/case_catalog.jsonl \
  --manifest output/research/diagram_source_manifest.json
```

Expected: catalog IDs equal the new manifest's `.mmd` stems and hashes, with no missing spec or uncited diagram text. Directory extras are outside the new set and receive no ownership status here; the final build must either prove them as typed retirement candidates or fail.

- [ ] **Step 4: Commit**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/tools/portfolio_builder/mermaid.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_mermaid.py \
  docs/Portfolio_Book/tests/fixtures/mermaid
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book list-diagram-artifacts \
  --manifest output/research/diagram_source_manifest.json \
  --phase source \
  --format nul | git -C ../.. add --pathspec-from-file=- --pathspec-file-nul
cd ../..
git diff --cached --check
git commit -m "docs(portfolio): write every mermaid case diagram"
```

---

### Task 4: Compile every Mermaid source atomically

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/mermaid.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Modify: `docs/Portfolio_Book/tests/test_mermaid.py`
- Generate: `docs/Portfolio_Book/output/diagrams/rendered/*.svg`
- Generate: `docs/Portfolio_Book/output/diagrams/rendered/*.png`
- Generate: `docs/Portfolio_Book/output/research/diagram_render_manifest.json`

**Interfaces:**

```python
def render_all_diagrams(
    cases: Sequence[CaseRecord],
    sources: DiagramSourceManifest,
    mermaid_cli: Path,
    config: Path,
    output: Path,
) -> tuple[DiagramArtifact, ...]
```

- [ ] **Step 1: Add failing compile, atomicity, and parse tests**

Compile valid flowchart/sequence/state/timeline fixtures and one intentional syntax error. Assert a single failure leaves the previously published render directory byte-identical. Parse every SVG with `svglib.svg2rlg`, require positive viewBox dimensions and local-only references, and open every PNG with Pillow at width at least 1,600 pixels.

- [ ] **Step 2: Implement all-or-nothing compilation**

Before rendering, verify both CLI and core versions are `11.16.0`. Invoke the local executable under `node_modules/.bin`, never a global `mmdc` or floating `npx` package. Render SVG and PNG with the fixed config/background/scale into a temporary tree. Normalize only known nondeterministic metadata, then assert a second fixture render is byte-identical.

If any case fails, report its case ID, `.mmd` path, and compiler stderr, retain all sources, and publish no partial render set.

Register `render-mermaid` and `verify-mermaid-render` in `cli.py` in this task.

- [ ] **Step 3: Compile and verify the real set**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py --with-node -- \
  uv run portfolio-book render-mermaid \
  --catalog output/research/case_catalog.jsonl \
  --source-manifest output/research/diagram_source_manifest.json \
  --output output/diagrams/rendered \
  --manifest output/research/diagram_render_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-mermaid-render \
  --catalog output/research/case_catalog.jsonl \
  --manifest output/research/diagram_render_manifest.json
```

Expected: `case_count=mmd_count=svg_count=png_count`, all hashes valid, and compile failures 0.

- [ ] **Step 4: Commit**

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add docs/Portfolio_Book/tools/portfolio_builder/mermaid.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_mermaid.py
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book list-diagram-artifacts \
  --manifest output/research/diagram_render_manifest.json \
  --phase rendered \
  --format nul | git -C ../.. add --pathspec-from-file=- --pathspec-file-nul
cd ../..
git diff --cached --check
git commit -m "docs(portfolio): render every mermaid case diagram"
```

---

### Task 5: Implement the linked A4 ReportLab renderer

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/pdf_renderer.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_pdf_renderer.py`
- Create: `docs/Portfolio_Book/tests/fixtures/pdf/`
- Replace: `docs/Portfolio_Book/output/generate_pdfs.py`

**Interfaces:**

```python
def build_document_units(contract: DocumentContract, family: str) -> tuple[DocumentUnit, ...]
def render_pdf(spec: PdfDocumentSpec, destination: Path) -> PdfProbe
def make_invariant_canvas(*args: object, **kwargs: object) -> Canvas
```

- [ ] **Step 1: Write failing A4, photo, diagram, navigation, and overflow tests**

Test all page media boxes are A4 within 0.5 point, the resume embeds the exact photo hash at 0.9 aspect ratio, and every portfolio case embeds its approved Mermaid SVG. Require `photo is None` for the portfolio family and an exact PDF visual-resource provenance set derived only from the compiled Mermaid render manifest; inject a photo, arbitrary image XObject, unapproved Form XObject, icon, screenshot, and duplicate/missing Mermaid form and require failure. Also test Korean font streams are embedded, TOC links/bookmarks resolve, source links are present, long Korean paragraphs split without loss, a case starts at a valid boundary, and an unfit diagram produces an explicit failure rather than clipping.

- [ ] **Step 2: Implement page templates and structured flowables**

Use `BaseDocTemplate`, `PageTemplate`, `multiBuild`, and `afterFlowable` for TOC/bookmark registration. Use the verified WQY font and source-ID anchors/links. Render the resume photo at original ratio, never as a background, and reject it for portfolio/evidence specs. Convert only manifest-approved Mermaid SVGs with svglib, record the resulting PDF Form/XObject provenance, and scale proportionally within the frame; the portfolio renderer accepts no other visual-asset input. Keep the diagram with its case heading where feasible but allow long text to flow across pages without truncation.

Use an invariant ReportLab canvas, fixed metadata, stable input order, and normalized timestamps so identical inputs/toolchain yield byte-identical PDFs.

- [ ] **Step 3: Preserve the compatibility wrapper**

Replace `output/generate_pdfs.py` with a small wrapper that calls `portfolio_builder.cli.main(["build-pdfs", *sys.argv[1:]])`. It contains no independent parsing, styling, or rendering logic.

Register the `build-pdfs` handler in `cli.py` before adding the wrapper; test both paths produce the same manifest.

- [ ] **Step 4: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_pdf_renderer.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/pdf_renderer.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_pdf_renderer.py \
  docs/Portfolio_Book/tests/fixtures/pdf \
  docs/Portfolio_Book/output/generate_pdfs.py
git diff --cached --check
git commit -m "feat(portfolio): render linked A4 documents"
```

---

### Task 6: Add deterministic volume planning and master indices

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/volume_planner.py`
- Create: `docs/Portfolio_Book/tests/test_volume_planner.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/pdf_renderer.py`
- Modify: `docs/Portfolio_Book/tests/test_pdf_renderer.py`

**Interfaces:**

```python
def split_units(
    ordered_units: tuple[DocumentUnit, ...],
    render_probe: Callable[[tuple[DocumentUnit, ...], Path], PdfProbe],
    max_bytes: int = 90_000_000,
    max_pages: int = 250,
) -> VolumePlan

def render_pdf_family(spec: PdfFamilySpec, destination: Path) -> PdfFamilyManifest
def render_master_index(manifest: PdfFamilyManifest, destination: Path) -> VolumeArtifact
```

- [ ] **Step 1: Write failing boundary, union, and determinism tests**

Use a fake probe plus real small PDFs to test exact 90,000,000-byte and 250-page boundaries, a one-byte/page overflow, an oversized-byte atomic unit, a page-heavy singleton, repeated byte-identical plans, empty input rejection, and stale generated volume handling. Include thousands of result-index and record-only-observation rows plus coverage/profile/project units. Assert the exact base mapping resume=`이력서_완성본.pdf`, portfolio=`포트폴리오_완성본.pdf`, evidence=`전수증거장부.pdf`, with numbered bodies formed only by inserting `-001`, `-002`, and so on before `.pdf`. Assert a unit over 90,000,000 bytes is a blocker, while a single unit over the soft 250-page packaging threshold is emitted alone without omission. Assert every required case and non-case unit union is exact, intersection is empty, order is preserved, and no `CASE:START/END` pair crosses volumes.

- [ ] **Step 2: Implement greedy maximal-prefix partitioning**

First materialize every unbounded section entry as a stable `DocumentUnit`: each profile/project metadata field, all-result index row, complete case block, record-only observation row keyed by target/observation ID, source-conflict entry, release-coverage entry, and evidence source/document-claim entry. Probe-render the largest ordered prefix that satisfies both limits, emit it, and continue. The 250-page threshold is a soft volume boundary, not a content cap: if the next single atomic unit alone exceeds it but remains below the hard byte limit, emit that unit as its own volume. Cover/TOC labels may repeat only when `required_once=False`; every required case and non-case unit must occur exactly once across the family.

Reject any `PdfFamilySpec.base_filename` that differs from `PDF_BASE_FILENAMES[family]`. When one volume is sufficient, write the body under that base filename. With multiple volumes, write bodies by inserting `-001`, `-002`, and so on before `.pdf`, then make the base filename a master index containing ordinal, first/last unit IDs, count by unit kind, pages, bytes, SHA-256, and relative link. Body hashes are finalized before the master is rendered. The validator compares `PdfFamilyManifest.all_unit_ids` to `DocumentContract.required_unit_ids_by_family`, not only to case IDs.

- [ ] **Step 3: Verify and commit**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_volume_planner.py tests/test_pdf_renderer.py -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/volume_planner.py \
  docs/Portfolio_Book/tools/portfolio_builder/pdf_renderer.py \
  docs/Portfolio_Book/tests/test_volume_planner.py \
  docs/Portfolio_Book/tests/test_pdf_renderer.py
git diff --cached --check
git commit -m "feat(portfolio): split exhaustive PDF families deterministically"
```

---

### Task 7: Add atomic build orchestration and mechanical publication gates

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/verifier.py`
- Create: `docs/Portfolio_Book/tools/portfolio_builder/visual_audit.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Create: `docs/Portfolio_Book/tests/test_verifier.py`
- Create: `docs/Portfolio_Book/tests/test_build_all.py`
- Generate: `docs/Portfolio_Book/output/research/pdf_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/pdf_validation.json`
- Generate: `docs/Portfolio_Book/output/research/build_manifest.json`
- Generate: `docs/Portfolio_Book/output/final/검토필요사항.md`

**Interfaces:**

```python
def validate_publication(root: Path, manifest: PdfManifest) -> ValidationReport
def build_all(
    config: BuildConfig,
    evidence: EvidenceBundle,
    ownership: OwnershipContext,
) -> BuildManifest
def publish_atomically(staging: Path, destination: Path, manifest: BuildManifest) -> None
def render_review_needs(evidence: EvidenceBundle, snapshot: SnapshotManifest) -> str
def start_visual_review(manifest: Path, role: Literal["primary", "independent"]) -> str
def record_visual_page(manifest: Path, review: VisualPageReview) -> None
def verify_visual_audit(manifest: VisualAuditManifest, pdfs: PdfManifest) -> ValidationReport
def make_visual_audit(
    pdfs: PdfManifest,
    ownership: OwnershipContext,
    output: Path,
    dpi: int = 144,
    pages_per_sheet: int = 12,
) -> VisualAuditManifest
def finalize_publication_manifest(
    root: Path,
    snapshot: SnapshotManifest,
    build: BuildManifest,
    pdfs: PdfManifest,
    visual: VisualAuditManifest,
    ownership: OwnershipContext,
    capture_coverage: Path,
    release_coverage: Path,
    output: Path,
) -> PublicationManifest
def list_generated_artifacts(
    root: Path,
    manifest: PublicationManifest,
    mode: Literal["regular", "ignored", "retired-tracked"],
) -> tuple[str, ...]
def list_pdf_artifacts(manifest: PdfManifest) -> tuple[str, ...]
def stage_publication_artifacts(root: Path, manifest_path: Path) -> ValidationReport
def verify_staged_artifacts(root: Path, manifest_path: Path) -> ValidationReport
def verify_all(
    root: Path,
    phase: Literal["pre-final", "post-final-precommit", "post-publication"],
    publication_manifest: Path | None,
) -> ValidationReport
```

- [ ] **Step 1: Write failing completeness, PDF, security, and rollback tests**

Inject one failure at each build phase and assert every previously published file remains byte-identical. Fixtures must catch a missing/extra diagram, wrong case title, uncited sentence, absent source anchor, missing/duplicate case or non-case required unit, wrong page size, unembedded font, empty page, NUL/replacement character, malformed outline/link, stale capture/release manifest hash, original-PDF hash change, secret/third-party contact, and Ghostscript failure. `list-pdf-artifacts` must reject an empty manifest, a missing family, an empty family, duplicate/outside-root paths, or a set without all three exact base PDFs; it never emits an empty successful stream. Every resolved `EvidenceBundle` member and ownership input must appear in `BuildManifest.input_hashes`; a substituted path, changed byte, missing hash, or hash disagreement with release coverage fails. Publication tests require the manifest-owned closure to exclude the publication manifest itself, require that path to be added to the index separately, and reject a post-cutoff commit/diff boundary that differs from the exact linear `first_excluded_commit..publication_parent` chain and every parent-specific diff hash. `build-all` and `make-visual-audit` tests must prove they only report retirement candidates and leave every stale byte present; `finalize-publication-manifest` is the sole deletion authority. Staging tests include an unchanged closure member already present at `HEAD`: it must be validated from the index but must not be expected in `git diff --cached`. They retire one tracked and one untracked artifact from a valid previous manifest: both must disappear safely only during finalization, the tracked deletion must occur in the cached diff, the untracked deletion must not fabricate an index entry, and a stale path with a previous-manifest or HEAD-hash mismatch must block deletion. Add a first-publication fixture with no prior publication manifest and the exact boundary-locked legacy ownership rows: only matching tracked legacy outputs may be retired under `bootstrap-legacy-boundary`; a missing list entry, modified worktree byte, different current `HEAD` blob, wrong source-tree blob OID/SHA, outside path, or extra legacy-looking filename must block adoption/deletion. Add separate state tests: `post-final-precommit` requires `HEAD == publication_parent`, while `post-publication` requires a single-parent `HEAD`, `HEAD^ == publication_parent`, exactly one final publication commit after that parent, manifest/current artifact bytes equal the committed `HEAD` tree, every retirement is absent from that tree, and task-owned index/worktree deltas are zero. Any extra intervening commit fails.

- [ ] **Step 2: Implement `build-all` in a temporary sibling tree**

Resolve the caller's exact `EvidenceBundle` and `OwnershipContext`, record every input byte hash in `BuildManifest.input_hashes`, and compare them to the snapshot/capture/release manifests. Then run: originals/photo checks → capture/release coverage checks → six-input fact-chain hash/effective-union checks → content contract → Mermaid source/render checks → three PDF families → manifests → mechanical validation → security scan. Transient sibling staging paths are never serialized; the manifest stores only the normalized repository-relative `publication_root`. Only after all pass, replace files named by the new build manifest. Compute and record `retirement_candidate_paths`, but neither `build-all` nor `make-visual-audit` deletes or renames any previous path. Pre-final validation compares exact current inputs to the new temporary/manifest closure and permits only separately identified, hash-valid previous/bootstrap-owned retirement candidates in the working tree; it never treats them as new diagrams/PDFs/audit pages. Any other extra path fails. Publish the build manifest last. Never recursively replace `docs/Portfolio_Book/output` or touch unlisted user files. Implement CLI commands `build-all`, `validate`, `make-visual-audit`, `start-visual-review`, `record-visual-page`, `verify-visual-audit`, `verify-all`, `finalize-publication-manifest`, `list-generated-artifacts`, `list-pdf-artifacts`, `stage-publication-artifacts`, and `verify-staged-artifacts` over the typed contracts above. `list-pdf-artifacts` emits exactly the manifest-owned base/body PDF paths as NUL records, never a directory scan. `verify-all` has three non-interchangeable states: `pre-final`, `post-final-precommit`, and `post-publication`; each enforces the HEAD/index/worktree relationship defined by its tests.

`finalize-publication-manifest` consumes the same typed `OwnershipContext`, freezes the current HEAD as `publication_parent`, starts at `snapshot.first_excluded_commit`, requires its parent to equal `snapshot.source_snapshot_head`, enumerates the exact linear excluded workflow chain through `publication_parent`, hashes every root/parent-specific diff, and verifies that chain against the snapshot boundary. After assembling the complete current closure—including the final visual-audit files—it is the sole component allowed to retire files. With a valid previous publication manifest, candidates are exactly `previous_publication_manifest.generated_paths - current_generated_paths`. On the first publication only, when no previous manifest exists, candidates are exactly `snapshot.legacy_owned_outputs - current_generated_paths`; each must match its boundary source-tree blob OID/SHA, current working-tree bytes, and current tracked `HEAD` blob before receiving `ownership_basis="bootstrap-legacy-boundary"`. No name pattern, directory scan, or similarity can adopt another file. The finalizer first validates the entire candidate set and stages recoverable sibling moves, then either restores all candidates on failure or atomically completes all removals and records the exact `RetiredArtifact` set; this catches stale visual pages, build outputs, and the six explicit first-run legacy files without touching unlisted user files.

`--previous-publication-manifest-if-present` has one strict meaning: absence at the exact path selects the boundary-locked first-publication bootstrap, while presence requires a fully valid previous manifest that is loaded and hashed before any output mutation. A malformed, modified, or partially written file never falls back to bootstrap. When the previous and new manifest paths are the same, the previous bytes remain in memory until the new manifest is atomically installed; their hash is retained as `BuildManifest.input_hashes["previous_publication_manifest@build-start"]` and `PublicationManifest.previous_publication_manifest_sha256`, and later validators do not mistake the newly installed bytes for the previous input.

`PublicationManifest.generated_paths` and `artifact_hashes` are the complete current task-owned artifact closure but deliberately exclude `publication_manifest.json` itself because a canonical self-hash is impossible; `retired_artifacts` is a disjoint previous/bootstrap-owned set. The listing CLI partitions the current closure into regular and ignored paths using Git's own ignore check and lists only HEAD-tracked retirements in `retired-tracked` mode. The caller adds the manifest path separately; `stage-publication-artifacts` invokes normal add, forced add, or tracked deletion add only for nonempty validated exact path lists and never broadens to a directory or empty pathspec. `verify-staged-artifacts` then requires every current closure path to have an index entry, SHA-256-hashes both the index-stage blob bytes and current working-tree bytes against the manifest, requires the separately added publication-manifest bytes to match between index and working tree, and requires every retirement to be absent from both working tree and index. It computes the expected cached-diff set as exactly (a) current closure/manifest paths whose index entry differs from or is absent at `HEAD` plus (b) HEAD-tracked retired paths as deletions; `git diff --cached --name-status` must equal that set and status, contain no outside path or `.gitignore`, and leave no unstaged/untracked manifest-owned artifact. Unchanged artifacts committed by earlier tasks remain part of the verified index closure without being falsely required in the cached diff.

- [ ] **Step 3: Implement complete mechanical validation**

Validate:

- immutable capture coverage, classified overlay, release coverage, source, document-claim, case, title, diagram, Markdown, and every case/non-case PDF-unit set equality;
- the release-manifest hashes of profile/project primaries, both verification ledgers, adjudications, and receipt, plus their exact accepted/adjudicated effective candidate/fact union with no stale disputed value;
- all factual sentence source IDs and required documented metrics/statuses;
- `.mmd`/SVG/PNG one-to-one hashes and SVG/PNG parsability, plus exact portfolio Markdown-image and PDF visual-resource provenance equality to those Mermaid artifacts with no other image/form asset;
- A4 page size, embedded Korean font, photo provenance/aspect, nonempty pages, outlines, links, and bookmarks;
- no overflow markers, LayoutError suppression, NUL, U+FFFD, or missing glyph reports;
- volume exact required-unit union/no duplicates/order, base/master/body hashes, and every file below 90,000,000 bytes;
- the three original PDF hashes and 31-page guide count;
- generated-tree secret/contact scan;
- Ghostscript `nullpage` validation for every PDF.

The review-needs file may contain only confirmed-unavailable API fields, damaged source locators, privacy redactions, and the publication snapshot boundary. It must not question documented performance facts.

- [ ] **Step 4: Verify and commit the tooling**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_verifier.py tests/test_build_all.py -q
python3 tools/run_portfolio_command.py -- uv run pytest -q
cd ../..
git add docs/Portfolio_Book/tools/portfolio_builder/verifier.py \
  docs/Portfolio_Book/tools/portfolio_builder/visual_audit.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_verifier.py \
  docs/Portfolio_Book/tests/test_build_all.py
git diff --cached --check
git commit -m "test(portfolio): enforce exhaustive publication gates"
```

---

### Task 8: Build all final artifacts and review every rendered page

**Files:**

- Generate/replace: `docs/Portfolio_Book/output/final/이력서_완성본.pdf` and manifest-listed `이력서_완성본-<NNN>.pdf` bodies
- Generate/replace: `docs/Portfolio_Book/output/final/포트폴리오_완성본.pdf` and manifest-listed `포트폴리오_완성본-<NNN>.pdf` bodies
- Generate/replace: `docs/Portfolio_Book/output/final/전수증거장부.pdf` and manifest-listed `전수증거장부-<NNN>.pdf` bodies
- Generate/replace: `docs/Portfolio_Book/output/final/검토필요사항.md`
- Generate: `docs/Portfolio_Book/output/research/pdf_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/pdf_validation.json`
- Generate: `docs/Portfolio_Book/output/research/build_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/visual_audit_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/publication_manifest.json`
- Generate: `docs/Portfolio_Book/output/research/visual-audit/contact-sheets/*.png`
- Generate: `docs/Portfolio_Book/output/research/visual-audit/pages/*.png`
- Generate as needed: `docs/Portfolio_Book/output/research/visual-audit/full-detail/*.png`

**Interfaces:**

- Consumes the complete content and diagram artifacts.
- Produces the final PDF family set and proof that every page was reviewed.

- [ ] **Step 1: Run the atomic real build**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py --with-node -- \
  uv run portfolio-book build-all \
  --root . \
  --source-boundary source_boundary.json \
  --snapshot output/research/snapshot_manifest.json \
  --sources output/research/source_records.jsonl \
  --claims output/research/document_claim_inventory.jsonl \
  --decisions output/research/classification_decisions.jsonl \
  --classified-sources output/research/classified_source_records.jsonl \
  --source-conflicts output/research/source_conflicts.jsonl \
  --observation-relations output/research/observation_relations.jsonl \
  --catalog output/research/case_catalog.jsonl \
  --source-map output/research/case_source_map.csv \
  --profile output/research/profile_facts.jsonl \
  --profile-verifications output/research/profile_fact_verifications.jsonl \
  --projects output/research/project_catalog.jsonl \
  --project-verifications output/research/project_fact_verifications.jsonl \
  --fact-adjudications output/research/fact_adjudications.jsonl \
  --fact-receipt output/research/fact_adjudication_receipt.json \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --release-coverage output/research/release_coverage_manifest.json \
  --previous-publication-manifest-if-present output/research/publication_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book validate \
  --root . \
  --snapshot output/research/snapshot_manifest.json \
  --build-manifest output/research/build_manifest.json \
  --previous-publication-manifest-if-present output/research/publication_manifest.json \
  --phase pre-final
```

Expected: `VERIFICATION_OK`, zero coverage deltas, zero compile/layout/security findings, and all original hashes unchanged.

- [ ] **Step 2: Run Ghostscript independently over every final PDF**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book list-pdf-artifacts \
  --manifest output/research/pdf_manifest.json \
  --format nul | xargs -0 -r -n1 gs -q -dSAFER -dBATCH -dNOPAUSE -sDEVICE=nullpage
```

Expected: exit 0 for every file.

- [ ] **Step 3: Generate page review material**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book make-visual-audit \
  --pdf-manifest output/research/pdf_manifest.json \
  --source-boundary source_boundary.json \
  --snapshot output/research/snapshot_manifest.json \
  --previous-publication-manifest-if-present output/research/publication_manifest.json \
  --output-dir output/research/visual-audit \
  --manifest output/research/visual_audit_manifest.json \
  --dpi 144 \
  --pages-per-sheet 12
```

The freshly generated manifest contains one `VisualPageArtifact` per current page, including PDF path/hash, page number, raster path/hash, contact-sheet path/hash/cell, and optional full-page follow-up path/hash. Its `generated_paths`/`artifact_hashes` are the exact contact-sheet/raster closure, it has two empty independent review slots, and both audit-run IDs are initially `null`. Previously owned audit rasters absent from this new set are reported as retirement candidates and left untouched/unread until finalization; any unknown or modified extra still fails. Start the primary run with `start-visual-review --manifest output/research/visual_audit_manifest.json --role primary`; after every current page has a primary row, start the fresh independent run with the same command and `--role independent`. Each invocation atomically fills only its role's previously empty run ID, creates a distinct immutable ID, and refuses overwrite or reuse across roles. Regenerating PDFs/page rasters creates a new audit manifest and invalidates all prior run IDs and review rows.

- [ ] **Step 4: Inspect every contact sheet and questionable page**

Use `view_image` on every contact sheet in manifest order. Check photo proportion, Korean glyphs, headings, diagrams, tables, margins, page breaks, footers, and blank/clipped/overlapping elements. For any cell that cannot be judged at contact-sheet scale, render that page at original/high detail and inspect it separately. Immediately after viewing a page, call `record-visual-page` with exactly these required arguments: `--manifest`, `--role`, `--pdf-sha256`, `--page-number`, `--raster-sha256`, `--decision`, and `--finding` when the decision is `finding`. The values must be copied from that page's current manifest row; the CLI has no range or bulk-pass option.

A fresh independent reviewer then starts the independent audit run, checks every sheet and every corrected page, and records its own per-page rows. Rebuild affected artifacts and repeat both audit runs until every current `(pdf_sha256, page_number, raster_sha256)` has exactly one primary and one independent `pass` row and no finding row.

- [ ] **Step 5: Run the final all-layer gate**

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-all \
  --root . \
  --phase pre-final \
  --require-visual-review output/research/visual_audit_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book finalize-publication-manifest \
  --root . \
  --source-boundary source_boundary.json \
  --snapshot output/research/snapshot_manifest.json \
  --previous-publication-manifest-if-present output/research/publication_manifest.json \
  --build-manifest output/research/build_manifest.json \
  --pdf-manifest output/research/pdf_manifest.json \
  --visual-manifest output/research/visual_audit_manifest.json \
  --capture-coverage output/research/capture_coverage_manifest.json \
  --release-coverage output/research/release_coverage_manifest.json \
  --output output/research/publication_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-all \
  --root . \
  --phase post-final-precommit \
  --publication-manifest output/research/publication_manifest.json \
  --require-visual-review output/research/visual_audit_manifest.json
cd ../..
git diff --check
git status --short --branch
```

Expected: every source/claim/case/required non-case unit/diagram/PDF/page accounted for, visual `primary_unreviewed_pages=0`, `independent_unreviewed_pages=0`, duplicate review rows 0, visual findings 0, original hashes unchanged, exact post-cutoff commit/parent-diff boundary, all recorded retirement paths absent at the `post-final-precommit` gate, and the user's pre-existing `.gitignore` change remains unstaged.

- [ ] **Step 6: Stage manifest-owned artifacts and commit**

Use the verified `publication_manifest.json` to enumerate every current generated artifact: photo/provenance assets; capture ledgers and safe archives; review batches, annotations, verifications, adjudications, classified overlay, source-conflict registry, case/profile/project catalogs and source maps; final Markdown/PDF files; diagram sources/renders; capture/release/PDF/build/validation/visual manifests; review-needs file; contact sheets; and full-page audit rasters. The current closure excludes `publication_manifest.json` itself by contract; validated `retired_artifacts` separately enumerate files owned by a previous publication manifest or the exact one-time bootstrap legacy boundary but absent from the new closure. The validator rejects `.gitignore`, original external PDFs, paths outside `docs/Portfolio_Book`, missing current files, present retired files, index/working-tree/manifest hash mismatches, previous/bootstrap-ownership or HEAD retirement mismatches, unknown files, transient checkpoint/cache paths, unstaged manifest-owned changes/deletions, and any cached-diff path outside the current-or-retired manifest-owned set. A closure member already committed unchanged is verified through its index blob and is intentionally absent from the expected cached diff.

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine
git add -- docs/Portfolio_Book/output/research/publication_manifest.json
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book stage-publication-artifacts \
  --root ../.. \
  --manifest output/research/publication_manifest.json
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-staged-artifacts \
  --root ../.. \
  --manifest output/research/publication_manifest.json
cd ../..
git diff --cached --check
git commit -m "docs(portfolio): publish exhaustive resume and portfolio"
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-all \
  --root . \
  --phase post-publication \
  --publication-manifest output/research/publication_manifest.json \
  --require-visual-review output/research/visual_audit_manifest.json
```

After this rendering commit, return to the umbrella plan and stop at its SHA-bound independent artifact review gate. Only an exact PASS permits the already authorized feature-branch push. Do not open a PR, merge into `develop`, or deploy without the later, separately scoped explicit requests defined there.
