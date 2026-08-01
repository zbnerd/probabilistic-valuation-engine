from __future__ import annotations

import hashlib
import io
import subprocess
import tarfile
from dataclasses import replace
from pathlib import Path

import pymupdf
import pytest

import portfolio_builder.document_collector as document_collector
from portfolio_builder.document_collector import collect_documents
from portfolio_builder.canonical_io import read_jsonl, write_jsonl
from portfolio_builder.models import (
    DocumentClaim,
    ExternalInputFile,
    SnapshotManifest,
    TrackedFileSnapshot,
)


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ("git", *args), cwd=repo, check=True, capture_output=True, text=True
    ).stdout.strip()


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _pdf(page_count: int, *, image: bool = False) -> bytes:
    document = pymupdf.open()
    for index in range(page_count):
        page = document.new_page()
        page.insert_text((72, 72), f"Page {index + 1}. Verified sentence.")
        if image and index == 0:
            pixmap = pymupdf.Pixmap(pymupdf.csRGB, (0, 0, 16, 20), False)
            pixmap.clear_with(0x336699)
            page.insert_image(pymupdf.Rect(72, 90, 88, 110), stream=pixmap.tobytes("png"))
    result = document.tobytes(garbage=4, deflate=True)
    document.close()
    return result


def _external(role: str, path: str, value: bytes) -> ExternalInputFile:
    return ExternalInputFile(role, path, len(value), _sha256(value))  # type: ignore[arg-type]


def _snapshot(
    repo: Path,
    head: str,
    tracked: tuple[TrackedFileSnapshot, ...],
    external: tuple[ExternalInputFile, ...],
) -> SnapshotManifest:
    return SnapshotManifest(
        snapshot_id="SNAP-DOCUMENT-TEST",
        started_at="2026-08-01T00:00:00Z",
        local_completed_at="2026-08-01T00:00:01Z",
        finalized_at=None,
        source_boundary_sha256="0" * 64,
        source_snapshot_head=head,
        source_snapshot_tree=_git(repo, "rev-parse", f"{head}^{{tree}}"),
        first_excluded_commit="1" * 40,
        first_excluded_parent=head,
        workflow_ref="refs/heads/test",
        observed_head_sha=head,
        observed_head_symbolic_target="refs/heads/test",
        observed_refs=(),
        semantic_refs=(),
        excluded_workflow_commit_shas_at_capture=(),
        external_input_files=external,
        legacy_owned_outputs=(),
        tracked_files=tracked,
        ai_trace_files=(),
        github_window=None,
    )


@pytest.fixture
def document_repo(tmp_path: Path):
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init", "-b", "test")
    _git(repo, "config", "user.name", "Document Test")
    _git(repo, "config", "user.email", "document@example.invalid")

    normal = Path(__file__).parent / "fixtures/documents/sample.md"
    normal_path = repo / "docs/evidence.md"
    legacy_path = repo / "docs/Portfolio_Book/output/research/legacy.md"
    normal_path.parent.mkdir(parents=True)
    legacy_path.parent.mkdir(parents=True)
    normal_path.write_bytes(normal.read_bytes())
    legacy_path.write_text("# Legacy\n\nGenerated result.\n", encoding="utf-8")
    (repo / "docs/large.csv").write_text(
        "metric,value\nalpha,1\nbeta,2\ngamma,3\n", encoding="utf-8"
    )
    _git(repo, "add", ".")
    _git(repo, "commit", "-m", "documents")
    head = _git(repo, "rev-parse", "HEAD")

    tracked = tuple(
        TrackedFileSnapshot(path, mode, kind, oid, "document")
        for mode, kind, oid, path in (
            line.split(maxsplit=3)
            for line in _git(repo, "ls-tree", "-r", "--full-tree", head).splitlines()
        )
    )
    normal_path.write_text("mutable worktree text\n", encoding="utf-8")
    legacy_path.write_text("mutable legacy text\n", encoding="utf-8")

    values = {
        "guide.pdf": _pdf(31),
        "resume.pdf": _pdf(2, image=True),
        "portfolio.pdf": _pdf(3),
    }
    roles = {
        "guide.pdf": "renewal-guide",
        "resume.pdf": "id-photo-source-resume",
        "portfolio.pdf": "legacy-portfolio-reference",
    }
    external = []
    for name, value in values.items():
        path = repo / "private" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
        external.append(_external(roles[name], f"private/{name}", value))

    return repo, _snapshot(repo, head, tracked, tuple(external)), values


def test_collects_frozen_markdown_units_and_pdf_descendants(document_repo, tmp_path: Path):
    repo, snapshot, _ = document_repo
    archive_dir = tmp_path / "archives"

    sources, claims = collect_documents(repo, snapshot, archive_dir)

    by_path = {source.source_locator: source for source in sources}
    normal = by_path["git:docs/evidence.md"]
    legacy = by_path["git:docs/Portfolio_Book/output/research/legacy.md"]
    assert (normal.evidence_scope, normal.claim_authority) == (
        "project-evidence",
        "primary-record",
    )
    assert legacy.claim_authority == "legacy-derived-record"
    assert "Frozen heading" in normal.payload["safe_text"]
    assert "mutable worktree" not in normal.payload["safe_text"]

    normal_claims = [claim for claim in claims if claim.document_source_id == normal.source_id]
    assert {claim.unit_kind for claim in normal_claims} >= {
        "heading",
        "paragraph",
        "list-item",
        "table-row",
        "fenced-diagram",
        "sentence",
    }
    assert all("-L" in claim.claim_id for claim in normal_claims)
    assert [claim.text for claim in normal_claims if claim.unit_kind == "sentence"][:2] == [
        "Frozen heading",
        "First sentence.",
    ]

    external = {source.payload.get("external_role"): source for source in sources}
    guide = external["renewal-guide"]
    resume = external["id-photo-source-resume"]
    portfolio = external["legacy-portfolio-reference"]
    assert (guide.evidence_scope, guide.claim_authority) == (
        "structural-reference",
        "structural-reference",
    )
    assert (resume.evidence_scope, resume.claim_authority) == (
        "personal-evidence",
        "personal-record",
    )
    assert portfolio.evidence_scope == "personal-evidence"
    assert sum(
        claim.unit_kind == "pdf-page" and claim.document_source_id == guide.source_id
        for claim in claims
    ) == 31
    image_claims = [
        claim
        for claim in claims
        if claim.document_source_id == resume.source_id
        and claim.unit_kind == "pdf-image-object"
    ]
    assert image_claims
    assert all(claim.claim_id.startswith(f"{resume.source_id}-P0001-I") for claim in image_claims)
    assert all(
        (claim.evidence_scope, claim.claim_authority)
        == (resume.evidence_scope, resume.claim_authority)
        for claim in claims
        if claim.document_source_id == resume.source_id
    )
    assert all(claim.classification == "unreviewed" for claim in claims)

    archives = sorted(archive_dir.glob("document-records-*.tar.gz"))
    assert archives
    archived_names: set[str] = set()
    for archive_path in archives:
        with tarfile.open(archive_path, "r:gz") as archive:
            archived_names.update(archive.getnames())
    assert all(".pdf" not in name.lower() for name in archived_names)
    assert all(source.raw_archive_locator is None for source in sources)
    assert all(source.stored_members for source in sources)
    assert all(not claim.stored_members for claim in claims)

    claim_ledger = tmp_path / "document-claims.jsonl"
    write_jsonl(
        claim_ledger,
        claims,
        source_universe=sources,
        claim_universe=claims,
    )
    assert read_jsonl(
        claim_ledger,
        DocumentClaim,
        source_universe=sources,
        claim_universe=claims,
    ) == claims

    second_dir = tmp_path / "archives-second"
    second_sources, second_claims = collect_documents(repo, snapshot, second_dir)
    assert sources == second_sources
    assert claims == second_claims
    assert [path.read_bytes() for path in archives] == [
        path.read_bytes() for path in sorted(second_dir.glob("document-records-*.tar.gz"))
    ]


def test_rejects_tree_metadata_role_and_external_byte_mismatches(document_repo, tmp_path: Path):
    repo, snapshot, values = document_repo
    tracked = snapshot.tracked_files[0]
    bad_tree = replace(
        snapshot,
        tracked_files=(replace(tracked, object_sha="f" * 40), *snapshot.tracked_files[1:]),
    )
    with pytest.raises(ValueError, match="tracked snapshot entry mismatch"):
        collect_documents(repo, bad_tree, tmp_path / "bad-tree")

    bad_role = replace(
        snapshot,
        external_input_files=(
            replace(snapshot.external_input_files[0], role="unexpected-role"),  # type: ignore[arg-type]
            *snapshot.external_input_files[1:],
        ),
    )
    with pytest.raises(ValueError, match="external role"):
        collect_documents(repo, bad_role, tmp_path / "bad-role")

    resume = repo / "private/resume.pdf"
    resume.write_bytes(values["resume.pdf"] + b"changed")
    with pytest.raises(ValueError, match="external input identity mismatch"):
        collect_documents(repo, snapshot, tmp_path / "changed")
    assert not (tmp_path / "changed").exists()


def test_unlisted_ignored_pdf_is_never_collected(document_repo, tmp_path: Path):
    repo, snapshot, _ = document_repo
    unlisted = repo / "private/unlisted.pdf"
    unlisted.write_bytes(_pdf(1))

    sources, _ = collect_documents(repo, snapshot, tmp_path / "archives")

    assert all(source.source_locator != "external:private/unlisted.pdf" for source in sources)


def test_large_structured_text_uses_complete_line_bounded_batches(
    document_repo, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    repo, snapshot, _ = document_repo
    monkeypatch.setattr(document_collector, "_LARGE_TEXT_BYTES", 10)
    monkeypatch.setattr(document_collector, "_LINE_BATCH_BYTES", 20)

    sources, claims = collect_documents(repo, snapshot, tmp_path / "archives")

    source = next(item for item in sources if item.source_locator == "git:docs/large.csv")
    batches = [
        claim
        for claim in claims
        if claim.document_source_id == source.source_id
        and claim.unit_kind == "line-batch"
    ]
    frozen = "metric,value\nalpha,1\nbeta,2\ngamma,3\n"
    assert len(batches) > 1
    assert "".join(claim.text for claim in batches) == frozen
    assert [(claim.line_start, claim.line_end) for claim in batches] == [
        (1, 1),
        (2, 3),
        (4, 4),
    ]
