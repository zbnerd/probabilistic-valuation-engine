from __future__ import annotations

import gzip
import hashlib
import io
import json
import tarfile
from dataclasses import replace
from pathlib import Path

import pytest

from portfolio_builder.coverage import (
    CoverageError,
    verify_archive_members,
    verify_source_capture,
)
from portfolio_builder.models import (
    DocumentClaim,
    ExplicitRelation,
    ExternalInputFile,
    FileSnapshot,
    GitHubEndpointFingerprint,
    GitHubSnapshotWindow,
    RefSnapshot,
    SnapshotManifest,
    SourceRecord,
    StoredArtifactMember,
    TrackedFileSnapshot,
)
from portfolio_builder.relations import (
    RelationCandidate,
    derive_explicit_relations,
    validate_downstream_relation_references,
)


def _hash(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _source(
    source_id: str,
    source_type: str,
    *,
    locator: str | None = None,
    payload: dict[str, object] | None = None,
    raw_hash: str | None = None,
    stored_hash: str | None = None,
    authority: str = "primary-record",
    availability: str = "available",
    members: tuple[StoredArtifactMember, ...] = (),
) -> SourceRecord:
    return SourceRecord(
        source_id=source_id,
        source_type=source_type,
        source_locator=locator or f"fixture:{source_id}",
        snapshot_id="SNAP-test",
        title=source_id,
        evidence_scope="project-evidence",
        claim_authority=authority,  # type: ignore[arg-type]
        recorded_status="captured",
        recorded_at=None,
        raw_hash=raw_hash or "1" * 64,
        stored_hash=stored_hash or "2" * 64,
        raw_archive_locator=None,
        stored_members=members,
        explicit_relations=(),
        case_ids=(),
        classification="unreviewed",
        record_only_reason=None,
        availability_status=availability,
        privacy_redactions=(),
        parse_status="parsed",
        payload=payload or {},
    )


def _snapshot(tmp_path: Path) -> tuple[SnapshotManifest, Path]:
    external_path = tmp_path / "private/resume.pdf"
    external_path.parent.mkdir()
    external_path.write_bytes(b"locked-pdf")
    endpoint = GitHubEndpointFingerprint(
        item_key="pr:7",
        endpoint_key="/repos/o/r/pulls/7/commits",
        request_params_sha256="3" * 64,
        accept="application/vnd.github+json",
        page_numbers=(1,),
        page_response_hashes=("4" * 64,),
        stable_child_ids=("commits:" + "a" * 40,),
        availability_status="available",
    )
    window = GitHubSnapshotWindow(
        enumeration_started_at="2026-08-01T00:00:00Z",
        enumeration_completed_at="2026-08-01T00:00:01Z",
        reconciled_at="2026-08-01T00:00:02Z",
        pull_request_numbers=(7,),
        issue_numbers=(),
        updated_at_by_item={"pr:7": "2026-08-01T00:00:01Z"},
        endpoint_fingerprints=(endpoint,),
    )
    return (
        SnapshotManifest(
            snapshot_id="SNAP-test",
            started_at="2026-08-01T00:00:00Z",
            local_completed_at="2026-08-01T00:00:00Z",
            finalized_at="2026-08-01T00:00:03Z",
            source_boundary_sha256="5" * 64,
            source_snapshot_head="a" * 40,
            source_snapshot_tree="b" * 40,
            first_excluded_commit="c" * 40,
            first_excluded_parent="a" * 40,
            workflow_ref="refs/heads/docs/test",
            observed_head_sha="c" * 40,
            observed_head_symbolic_target="refs/heads/docs/test",
            observed_refs=(RefSnapshot("refs/heads/main", "a" * 40, "commit", None, None, None),),
            semantic_refs=(RefSnapshot("refs/heads/main", "a" * 40, "commit", None, None, None),),
            excluded_workflow_commit_shas_at_capture=("c" * 40,),
            external_input_files=(
                ExternalInputFile(
                    "id-photo-source-resume",
                    "private/resume.pdf",
                    len(b"locked-pdf"),
                    _hash(b"locked-pdf"),
                ),
            ),
            legacy_owned_outputs=(),
            tracked_files=(
                TrackedFileSnapshot("docs/a.md", "100644", "blob", "d" * 40, "document"),
            ),
            ai_trace_files=(FileSnapshot("docs/ai-traces/run/a.jsonl", 2, _hash(b"{}")),),
            github_window=window,
        ),
        external_path,
    )


def test_relation_derivation_accepts_only_exact_evidence_and_is_deterministic():
    sha = "a" * 40
    diff_hash = "d" * 64
    sources = (
        _source(f"GIT-{sha}", "git-commit", payload={"commit_sha": sha}),
        _source("GIT-diff-P01", "git-diff", payload={"patch_raw_sha256": diff_hash}, raw_hash=diff_hash),
        _source("GH-PR-7", "github-pull-request"),
        _source("GH-ISSUE-9", "github-issue"),
        _source("GH-COMMIT", "github-pr-commit", payload={"value": {"sha": sha}}),
        _source(
            "GH-CLOSE",
            "github-timeline-event",
            payload={"value": {"event": "closed", "commit_id": sha}},
        ),
        _source(
            "DOC-A",
            "tracked-document",
            locator="git:docs/a.md",
            payload={
                "safe_text": (
                    f"commit {sha}; PR #7; issue #9; "
                    "[design](docs/design.md)"
                ),
                "stable_run_id": "RUN-2026-08-01-001",
            },
        ),
        _source("DOC-DESIGN", "tracked-document", locator="git:docs/design.md"),
        _source(
            "AI-1",
            "ai-trace-entry",
            authority="trace-observation",
            payload={"diff_sha256": diff_hash, "stable_run_id": "RUN-2026-08-01-001"},
        ),
        _source(
            "NO-GUESS",
            "ai-trace-entry",
            payload={
                "short_sha": sha[:8],
                "command": "same command",
                "filename": "a.md",
                "metric": 7347,
                "title": "similar title",
            },
        ),
        _source(
            "NO-GUESS-2",
            "tracked-document",
            payload={
                "short_sha": sha[:8],
                "command": "same command",
                "filename": "a.md",
                "metric": 7347,
                "title": "similar title",
            },
        ),
    )
    first = derive_explicit_relations(sources)
    second = derive_explicit_relations(tuple(reversed(sources)))

    assert [item.to_dict() for item in first] == [item.to_dict() for item in second]
    types = {item.relation.relation_type for item in first}
    assert types >= {
        "api-commit-sha",
        "github-closing-event",
        "explicit-commit-reference",
        "explicit-pr-reference",
        "explicit-issue-reference",
        "exact-diff-hash",
        "document-link",
        "same-execution",
    }
    assert all(item.owner_source_id != "NO-GUESS" for item in first)
    assert all(item.relation.target_source_id != "NO-GUESS" for item in first)
    assert all(item.owner_source_id != "NO-GUESS-2" for item in first)
    assert all(item.relation.target_source_id != "NO-GUESS-2" for item in first)


def test_relation_ledger_rejects_collision_absent_target_and_unresolved_downstream():
    target = _source("TARGET", "git-commit")
    relation = ExplicitRelation.create(
        owner_source_id="OWNER",
        relation_type="explicit-reference",
        target_source_id="TARGET",
        evidence_locator="payload.ref",
        evidence_hash="a" * 64,
    )
    owner = replace(_source("OWNER", "tracked-document"), explicit_relations=(relation,))
    validate_downstream_relation_references((owner, target), (relation,))

    absent = replace(relation, target_source_id="ABSENT")
    with pytest.raises(ValueError, match="target absent"):
        validate_downstream_relation_references((replace(owner, explicit_relations=(absent,)), target))
    collision = replace(relation, evidence_hash="b" * 64)
    with pytest.raises(ValueError, match="does not resolve byte-for-byte"):
        validate_downstream_relation_references((owner, target), (collision,))
    with pytest.raises(ValueError, match="duplicate relation_id"):
        validate_downstream_relation_references(
            (replace(owner, explicit_relations=(relation, relation)), target)
        )


def test_archive_verification_rejects_missing_reordered_corrupt_and_external_original(tmp_path: Path):
    values = (b"first", b"second")
    filename = "safe.tar.gz"
    members = tuple(
        StoredArtifactMember(
            f"S-part-{index:03d}",
            f"{filename}#records/S-part-{index:03d}.json",
            index,
            2,
            len(value),
            _hash(value),
        )
        for index, value in enumerate(values, start=1)
    )
    with tarfile.open(tmp_path / filename, "w:gz") as archive:
        for member, value in zip(members, values, strict=True):
            info = tarfile.TarInfo(member.locator.split("#", 1)[1])
            info.size = len(value)
            archive.addfile(info, io.BytesIO(value))
    source = _source("S", "fixture", stored_hash=_hash(b"".join(values)), members=members)
    verify_archive_members((source,), (tmp_path / filename,))

    with pytest.raises(CoverageError, match="part ordering.*S"):
        verify_archive_members((replace(source, stored_members=tuple(reversed(members))),), (tmp_path / filename,))
    with pytest.raises(CoverageError, match="member union"):
        verify_archive_members((replace(source, stored_members=members[:1]),), (tmp_path / filename,))
    broken = replace(members[0], sha256="0" * 64)
    with pytest.raises(CoverageError, match="member hash.*S-part-001"):
        verify_archive_members((replace(source, stored_members=(broken, members[1])),), (tmp_path / filename,))


def test_capture_coverage_names_missing_stable_ids_and_treats_only_terminal_unavailable_complete(tmp_path: Path):
    snapshot, _ = _snapshot(tmp_path)
    sha = "a" * 40
    pdf = _source(
        "PDF",
        "external-pdf-derived-record",
        locator="external:private/resume.pdf",
        payload={
            "external_role": "id-photo-source-resume",
            "path": "private/resume.pdf",
            "original_identity_sha256": snapshot.external_input_files[0].sha256,
            "original_byte_count": snapshot.external_input_files[0].byte_count,
            "page_count": 1,
            "text_block_count": 0,
            "image_object_count": 0,
            "unit_count": 1,
            "original_pdf_archived": False,
        },
        raw_hash=snapshot.external_input_files[0].sha256,
    )
    document = _source("DOC", "tracked-document", locator="git:docs/a.md", payload={"unit_count": 0})
    ai_file = _source(
        "AI-FILE",
        "ai-trace-file",
        locator="ai-trace:docs/ai-traces/run/a.jsonl",
        payload={"source_path": "docs/ai-traces/run/a.jsonl", "entry_count": 1},
        raw_hash=snapshot.ai_trace_files[0].sha256,
    )
    ai_child = _source(
        "AI-ENTRY",
        "ai-trace-entry",
        locator="ai-trace:docs/ai-traces/run/a.jsonl#bytes=0-2",
        payload={"source_path": "docs/ai-traces/run/a.jsonl"},
    )
    github_child = _source(
        "GH-CHILD",
        "github-pr-commit",
        locator="github:/repos/o/r/pulls/7/commits#commits:" + sha,
        raw_hash="4" * 64,
    )
    sources = (
        _source(f"GIT-{sha}", "git-commit", payload={"commit_sha": sha, "parent_shas": []}),
        _source(
            f"GIT-{sha}-ROOT",
            "git-diff",
            payload={"child_sha": sha, "parent_sha": None, "parent_total": 0, "parent_ordinal": 1},
        ),
        document,
        pdf,
        ai_file,
        ai_child,
        _source("GH-PR-7", "github-pull-request"),
        github_child,
    )
    claims = (
        DocumentClaim(
            "PDF-P0001-PAGE",
            "PDF",
            "private/resume.pdf",
            "personal-evidence",
            "personal-record",
            "pdf-page",
            None,
            None,
            0,
            0,
            "7" * 64,
            "8" * 64,
            (),
            "page",
            "unreviewed",
            "parsed",
        ),
    )
    manifest = verify_source_capture(
        repo=tmp_path,
        snapshot=snapshot,
        sources=sources,
        claims=claims,
        semantic_commit_shas=(sha,),
        captured_ref_ids=("REF:refs/heads/main",),
        archive_paths=(),
        staged_output_paths=(),
        require_archive_members=False,
        strict_github_endpoints=False,
    )
    assert manifest.phase == "capture"
    assert manifest.status == "complete"
    assert manifest.sections["git_diffs"].expected_count == 1

    def verify_mutation(
        mutated_sources=sources,
        mutated_claims=claims,
        refs=("REF:refs/heads/main",),
        mutated_snapshot=snapshot,
    ):
        return verify_source_capture(
            repo=tmp_path,
            snapshot=mutated_snapshot,
            sources=mutated_sources,
            claims=mutated_claims,
            semantic_commit_shas=(sha,),
            captured_ref_ids=refs,
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )

    with pytest.raises(CoverageError, match="Git refs.*REF:refs/heads/main"):
        verify_mutation(refs=())
    with pytest.raises(CoverageError, match="documents.*DOC:docs/a.md"):
        verify_mutation(tuple(item for item in sources if item.source_id != "DOC"))
    with pytest.raises(CoverageError, match="AI trace child missing stable ID"):
        verify_mutation(tuple(item for item in sources if item.source_id != "AI-ENTRY"))
    with pytest.raises(CoverageError, match="GitHub child missing stable ID"):
        verify_mutation(tuple(item for item in sources if item.source_id != "GH-CHILD"))
    with pytest.raises(CoverageError, match="external claim count mismatch"):
        verify_mutation(mutated_claims=())
    with pytest.raises(CoverageError, match="GitHub parents.*GH-PR-7"):
        verify_mutation(tuple(item for item in sources if item.source_id != "GH-PR-7"))

    changed_fingerprint = replace(
        snapshot.github_window.endpoint_fingerprints[0],
        page_response_hashes=("9" * 64,),
    )
    changed_window = replace(
        snapshot.github_window, endpoint_fingerprints=(changed_fingerprint,)
    )
    with pytest.raises(CoverageError, match="child hash reconciliation.*GH-CHILD"):
        verify_mutation(mutated_snapshot=replace(snapshot, github_window=changed_window))

    with pytest.raises(CoverageError, match="missing stable ID.*GIT-.*-ROOT"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=tuple(item for item in sources if not item.source_id.endswith("-ROOT")),
            claims=claims,
            semantic_commit_shas=(sha,),
            captured_ref_ids=("REF:refs/heads/main",),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    transient = replace(github_child, availability_status="transient-failure")
    with pytest.raises(CoverageError, match="transient failure.*GH-CHILD"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=tuple(transient if item.source_id == "GH-CHILD" else item for item in sources),
            claims=claims,
            semantic_commit_shas=(sha,),
            captured_ref_ids=("REF:refs/heads/main",),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    terminal = replace(
        github_child,
        source_type="github-availability",
        source_locator="github:/repos/o/r/pulls/7/commits",
        availability_status="confirmed-unavailable",
    )
    terminal_snapshot = replace(
        snapshot,
        github_window=replace(
            snapshot.github_window,
            endpoint_fingerprints=(
                replace(
                    snapshot.github_window.endpoint_fingerprints[0],
                    stable_child_ids=(),
                    page_response_hashes=(terminal.raw_hash,),
                    availability_status="confirmed-unavailable",
                ),
            ),
        ),
    )
    verify_source_capture(
        repo=tmp_path,
        snapshot=terminal_snapshot,
        sources=tuple(terminal if item.source_id == "GH-CHILD" else item for item in sources),
        claims=claims,
        semantic_commit_shas=(sha,),
        captured_ref_ids=("REF:refs/heads/main",),
        archive_paths=(),
        staged_output_paths=(),
        require_archive_members=False,
        strict_github_endpoints=False,
    )


def test_external_identity_and_original_path_are_enforced(tmp_path: Path):
    snapshot, external_path = _snapshot(tmp_path)
    with pytest.raises(CoverageError, match="external input identity mismatch.*private/resume.pdf"):
        external_path.write_bytes(b"changed")
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(),
            claims=(),
            semantic_commit_shas=(),
            captured_ref_ids=("REF:refs/heads/main",),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )

    external_path.write_bytes(b"locked-pdf")
    with pytest.raises(CoverageError, match="external original.*private/resume.pdf"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(),
            claims=(),
            semantic_commit_shas=(),
            captured_ref_ids=("REF:refs/heads/main",),
            archive_paths=(external_path,),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    with pytest.raises(CoverageError, match="external original.*private/resume.pdf"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(),
            claims=(),
            semantic_commit_shas=(),
            captured_ref_ids=("REF:refs/heads/main",),
            archive_paths=(),
            staged_output_paths=("private/resume.pdf",),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
