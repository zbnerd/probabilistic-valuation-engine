from __future__ import annotations

import gzip
import hashlib
import io
import json
import subprocess
import tarfile
from dataclasses import replace
from pathlib import Path

import pytest

import portfolio_builder.cli as cli_module
import portfolio_builder.coverage as coverage_module
import portfolio_builder.github_collector as github_collector
from portfolio_builder.coverage import (
    CaptureCoverageManifest,
    CoverageSection,
    CoverageError,
    capture_ref_ids,
    collect_all,
    verify_archive_members,
    verify_source_capture,
)
from portfolio_builder.git_collector import GitCapture
from portfolio_builder.github_collector import ReconciliationPass, ReconciliationResult
from portfolio_builder.github_client import GitHubPage
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


def test_cli_resolves_task_10_paths_from_invocation_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    repo = tmp_path / "repo"
    book = repo / "docs" / "Portfolio_Book"
    book.mkdir(parents=True)
    monkeypatch.chdir(book)

    calls: list[tuple[str, tuple[object, ...], dict[str, object]]] = []

    def fake_capture(*args: object, **kwargs: object) -> None:
        calls.append(("capture", args, kwargs))

    def fake_collect(*args: object, **kwargs: object) -> None:
        calls.append(("collect", args, kwargs))

    def fake_verify(*args: object, **kwargs: object) -> None:
        calls.append(("verify", args, kwargs))

    monkeypatch.setattr(cli_module, "capture_snapshot", fake_capture)
    monkeypatch.setattr(cli_module, "collect_all", fake_collect)
    monkeypatch.setattr(cli_module, "verify_capture_files", fake_verify)
    monkeypatch.setattr(cli_module, "_staged_paths", lambda _: ())

    assert cli_module.main(
        [
            "capture-snapshot",
            "--repo",
            "../..",
            "--boundary",
            "source_boundary.json",
            "--output",
            "output/research/snapshot_manifest.json",
        ]
    ) == 0
    assert cli_module.main(
        [
            "collect-all",
            "--repo",
            "../..",
            "--repository",
            "zbnerd/probabilistic-valuation-engine",
            "--manifest",
            "output/research/snapshot_manifest.json",
            "--output",
            "output/research",
        ]
    ) == 0
    assert cli_module.main(
        [
            "verify-source-capture",
            "--manifest",
            "output/research/snapshot_manifest.json",
            "--root",
            "output/research",
        ]
    ) == 0

    manifest = book / "output/research/snapshot_manifest.json"
    output = book / "output/research"
    assert calls == [
        (
            "capture",
            (repo.resolve(), book / "source_boundary.json", manifest),
            {},
        ),
        (
            "collect",
            (),
            {
                "repo": repo.resolve(),
                "snapshot_path": manifest,
                "output_dir": output,
                "repository_name": "zbnerd/probabilistic-valuation-engine",
                "staged_output_paths": (),
            },
        ),
        (
            "verify",
            (),
            {
                "repo": repo.resolve(),
                "snapshot_path": manifest,
                "output_dir": output,
                "staged_output_paths": (),
            },
        ),
    ]


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
        item_key="pull:7",
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
        updated_at_by_item={"pull:7": "2026-08-01T00:00:01Z"},
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
            "RUN-CROSS-FIELD",
            "ai-trace-entry",
            payload={"execution_id": "RUN-2026-08-01-001"},
        ),
        _source(
            "DIRECT-REF",
            "ai-trace-entry",
            payload={"source_reference": "AI-1"},
        ),
        _source(
            "FOREIGN-URL",
            "tracked-document",
            payload={"safe_text": "https://github.com/other/repository/pull/7"},
        ),
        _source(
            "LOCAL-REF",
            "tracked-document",
            payload={"safe_text": "PR #7"},
        ),
        _source(
            "LOCAL-URL",
            "tracked-document",
            payload={
                "safe_text": (
                    "https://github.com/ZBNERD/Probabilistic-Valuation-Engine/issues/9"
                )
            },
        ),
        _source(
            "FOREIGN-NESTED",
            "github-timeline-event",
            payload={
                "value": {
                    "event": "cross-referenced",
                    "source": {
                        "issue": {
                            "number": 9,
                            "repository": {"full_name": "other/repository"},
                        }
                    },
                }
            },
        ),
        _source(
            "LOCAL-NESTED",
            "github-timeline-event",
            payload={
                "value": {
                    "event": "cross-referenced",
                    "source": {
                        "issue": {
                            "number": 9,
                            "repository_url": (
                                "https://api.github.com/repos/"
                                "ZBNERD/Probabilistic-Valuation-Engine"
                            ),
                        }
                    },
                }
            },
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
    assert any(
        item.owner_source_id == "RUN-CROSS-FIELD"
        and item.relation.relation_type == "same-execution"
        for item in first
    )
    assert any(
        item.owner_source_id == "DIRECT-REF"
        and item.relation.target_source_id == "AI-1"
        and item.relation.relation_type == "same-execution"
        for item in first
    )
    assert all(item.owner_source_id != "FOREIGN-URL" for item in first)
    assert all(item.owner_source_id != "FOREIGN-NESTED" for item in first)
    assert any(
        item.owner_source_id == "LOCAL-REF"
        and item.relation.target_source_id == "GH-PR-7"
        for item in first
    )
    assert any(
        item.owner_source_id == "LOCAL-NESTED"
        and item.relation.target_source_id == "GH-ISSUE-9"
        for item in first
    )
    assert any(
        item.owner_source_id == "LOCAL-URL"
        and item.relation.target_source_id == "GH-ISSUE-9"
        for item in first
    )


def test_explicit_diff_hash_relations_ignore_ambiguous_hashes_without_guessing():
    ambiguous_hash = "a" * 64
    unique_hash = "b" * 64
    sources = (
        _source(
            "DIFF-AMBIGUOUS-A",
            "git-diff",
            raw_hash=ambiguous_hash,
            stored_hash=ambiguous_hash,
        ),
        _source(
            "DIFF-AMBIGUOUS-B",
            "git-diff",
            raw_hash=ambiguous_hash,
            stored_hash=ambiguous_hash,
        ),
        _source(
            "DIFF-UNIQUE",
            "git-diff",
            raw_hash=unique_hash,
            stored_hash=unique_hash,
        ),
        _source(
            "AI-AMBIGUOUS",
            "ai-trace-entry",
            payload={"patch_sha256": ambiguous_hash},
        ),
        _source(
            "AI-UNIQUE",
            "ai-trace-entry",
            payload={"patch_sha256": unique_hash},
        ),
    )

    forward = derive_explicit_relations(sources)
    reversed_order = derive_explicit_relations(tuple(reversed(sources)))

    assert [item.to_dict() for item in forward] == [
        item.to_dict() for item in reversed_order
    ]
    assert len(forward) == 1
    candidate = forward[0]
    assert candidate.owner_source_id == "AI-UNIQUE"
    assert candidate.relation.relation_type == "exact-diff-hash"
    assert candidate.relation.target_source_id == "DIFF-UNIQUE"
    assert candidate.relation.evidence_locator == "payload.patch_sha256"


def test_direct_execution_reference_rejects_missing_target():
    with pytest.raises(ValueError, match="direct execution/source reference.*MISSING"):
        derive_explicit_relations(
            (_source("OWNER", "ai-trace-entry", payload={"source_reference": "MISSING"}),)
        )
    with pytest.raises(ValueError, match="direct execution/source reference.*ambiguous"):
        derive_explicit_relations(
            (
                _source(
                    "OWNER",
                    "ai-trace-entry",
                    payload={"source_reference": "fixture:shared"},
                ),
                _source("TARGET-A", "ai-trace-entry", locator="fixture:shared"),
                _source("TARGET-B", "tracked-document", locator="fixture:shared"),
            )
        )


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


def test_document_claims_are_bound_byte_for_byte_to_archived_safe_representation(
    tmp_path: Path,
):
    claim = DocumentClaim(
        "DOC-C1",
        "DOC",
        "docs/a.md",
        "project-evidence",
        "primary-record",
        "sentence",
        3,
        3,
        None,
        1,
        "1" * 64,
        "2" * 64,
        (),
        "safe claim",
        "unreviewed",
        "parsed",
    )
    representation = (
        json.dumps(
            {
                "schema_version": 1,
                "source": {"source_id": "DOC"},
                "claims": [claim.to_dict()],
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode()
    archive = tmp_path / "document-records-001.tar.gz"
    member_name = "records/DOC-part-001.json.part"
    with tarfile.open(archive, "w:gz") as stream:
        info = tarfile.TarInfo(member_name)
        info.size = len(representation)
        stream.addfile(info, io.BytesIO(representation))
    member = StoredArtifactMember(
        "DOC-part-001",
        f"{archive.name}#{member_name}",
        1,
        1,
        len(representation),
        _hash(representation),
    )
    source = _source(
        "DOC",
        "tracked-document",
        locator="git:docs/a.md",
        payload={"unit_count": 1},
        stored_hash=_hash(representation),
        members=(member,),
    )
    reconstructed = verify_archive_members((source,), (archive,))
    coverage_module.verify_document_claim_archive_binding(
        (source,), (claim,), reconstructed
    )
    with pytest.raises(CoverageError, match="archived document claim union.*DOC-C1"):
        coverage_module.verify_document_claim_archive_binding(
            (source,), (), reconstructed
        )
    changed = replace(claim, claim_id="DOC-CHANGED", stored_hash="3" * 64)
    with pytest.raises(CoverageError, match="archived document claim union"):
        coverage_module.verify_document_claim_archive_binding(
            (source,), (changed,), reconstructed
        )


def test_confirmed_unavailable_fingerprint_validates_metadata_record_contract():
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/issues/7/comments"
    body = b'{"message":"gone"}'
    page = GitHubPage(
        endpoint=endpoint,
        params={"per_page": 100},
        page_number=1,
        body=body,
        json=None,
        response_hash=_hash(body),
        availability_status="confirmed-unavailable",
        status_code=451,
        fetched_at="2026-08-01T00:00:00Z",
    )
    safe = github_collector._availability_record(
        item_key="issue:7",
        endpoint=endpoint,
        snapshot_id="SNAP-test",
        page=page,
        params={"per_page": 100},
        accept="application/vnd.github+json",
    )
    fingerprint = github_collector._fingerprint(
        item_key="issue:7",
        endpoint=endpoint,
        params={"per_page": 100},
        accept="application/vnd.github+json",
        pages=(page,),
    )
    assert fingerprint.stable_child_ids == ("status-code:451",)
    assert coverage_module._fingerprint_child(fingerprint, (safe.record,)) == (
        "issue:7|status-code:451",
    )
    bad_status = replace(
        safe.record,
        payload={**safe.record.payload, "status_code": 410},
    )
    with pytest.raises(CoverageError, match="availability metadata mismatch"):
        coverage_module._fingerprint_child(fingerprint, (bad_status,))
    bad_hash = replace(safe.record, raw_hash="0" * 64)
    with pytest.raises(CoverageError, match="availability body hash mismatch"):
        coverage_module._fingerprint_child(fingerprint, (bad_hash,))


def test_patch_406_coverage_is_terminal_but_json_406_is_rejected():
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/241.patch"
    body = b'{"message":"unsafe"}'
    page = GitHubPage(
        endpoint=endpoint,
        params={},
        page_number=1,
        body=body,
        json=None,
        response_hash=_hash(body),
        availability_status="confirmed-unavailable",
        status_code=406,
        fetched_at="2026-08-01T00:00:00Z",
    )
    safe = github_collector._availability_record(
        item_key="pull:241",
        endpoint=endpoint,
        snapshot_id="SNAP-test",
        page=page,
        params={},
        accept=github_collector.PATCH_ACCEPT,
    )
    fingerprint = github_collector._fingerprint(
        item_key="pull:241",
        endpoint=endpoint,
        params={},
        accept=github_collector.PATCH_ACCEPT,
        pages=(page,),
    )

    assert coverage_module._fingerprint_child(fingerprint, (safe.record,)) == (
        "pull:241|status-code:406",
    )

    json_fingerprint = replace(fingerprint, accept="application/vnd.github+json")
    json_record = replace(
        safe.record,
        payload={**safe.record.payload, "accept": "application/vnd.github+json"},
    )
    with pytest.raises(CoverageError, match="406 availability is not a patch variant"):
        coverage_module._fingerprint_child(json_fingerprint, (json_record,))


def test_confirmed_unavailable_fingerprint_rejects_coherently_reidentified_archive(
    tmp_path: Path,
):
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/issues/7/comments"
    body = b'{"message":"gone"}'
    page = GitHubPage(
        endpoint=endpoint,
        params={"per_page": 100},
        page_number=1,
        body=body,
        json=None,
        response_hash=_hash(body),
        availability_status="confirmed-unavailable",
        status_code=451,
        fetched_at="2026-08-01T00:00:00Z",
    )
    safe = github_collector._availability_record(
        item_key="issue:7",
        endpoint=endpoint,
        snapshot_id="SNAP-test",
        page=page,
        params={"per_page": 100},
        accept="application/vnd.github+json",
    )
    fingerprint = github_collector._fingerprint(
        item_key="issue:7",
        endpoint=endpoint,
        params={"per_page": 100},
        accept="application/vnd.github+json",
        pages=(page,),
    )
    reidentified = replace(
        safe,
        record=replace(safe.record, source_id="GH-AVAIL-" + "f" * 24),
    )
    records, archives = github_collector._write_archive(
        tmp_path, "issues", (reidentified,)
    )
    verify_archive_members(records, archives)
    with pytest.raises(CoverageError, match="availability record stable ID mismatch"):
        coverage_module._fingerprint_child(fingerprint, records)


def test_count_gap_coverage_binds_parent_count_detail_fingerprint_and_identity(tmp_path: Path):
    endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/7/comments"
    detail_endpoint = "/repos/zbnerd/probabilistic-valuation-engine/pulls/7"
    detail = {
        "id": 1007,
        "number": 7,
        "updated_at": "2026-01-01T00:00:00Z",
        "review_comments": 6,
    }
    detail_body = json.dumps(detail, sort_keys=True, separators=(",", ":")).encode()
    detail_page = GitHubPage(
        detail_endpoint,
        {},
        1,
        detail_body,
        detail,
        _hash(detail_body),
        "available",
        200,
        "2026-01-02T03:04:05Z",
    )
    child_body = b"[]"
    child_page = GitHubPage(
        endpoint,
        {"per_page": 100},
        1,
        child_body,
        [],
        _hash(child_body),
        "available",
        200,
        "2026-01-02T03:04:06Z",
    )
    gap, token = github_collector._count_gap_record(
        item_key="pull:7",
        endpoint=endpoint,
        endpoint_kind="review-comments",
        snapshot_id="SNAP-test",
        pages=(child_page,),
        params={"per_page": 100},
        accept="application/vnd.github+json",
        child_ids=(),
        expected_count=6,
        parent_detail_page=detail_page,
        parent_updated_at=detail["updated_at"],
    )
    fingerprint = github_collector._fingerprint(
        item_key="pull:7",
        endpoint=endpoint,
        params={"per_page": 100},
        accept="application/vnd.github+json",
        pages=(child_page,),
        child_ids=(token,),
    )
    detail_fingerprint = github_collector._fingerprint(
        item_key="pull:7",
        endpoint=detail_endpoint,
        params={},
        accept="application/vnd.github+json",
        pages=(detail_page,),
    )
    fingerprints = (detail_fingerprint, fingerprint)
    parent = _source(
        "GH-PR-7",
        "github-pull-request",
        locator="github:zbnerd/probabilistic-valuation-engine/pull/7",
        raw_hash=detail_page.response_hash,
        payload={
            "captured_updated_at": detail["updated_at"],
            "endpoint_response_raw_sha256": detail_page.response_hash,
            "response_raw_sha256": detail_page.response_hash,
            "value": detail,
        },
    )
    sources = (parent, gap.record)

    assert coverage_module._fingerprint_child(
        fingerprint,
        sources,
        endpoint_fingerprints=fingerprints,
    ) == (
        f"pull:7|{token}",
    )
    snapshot, _ = _snapshot(tmp_path)
    window = replace(
        snapshot.github_window,
        updated_at_by_item={"pull:7": detail["updated_at"]},
        endpoint_fingerprints=fingerprints,
    )
    expected, captured, covered = coverage_module._verify_github(
        replace(snapshot, github_window=window),
        sources,
        strict_endpoints=False,
    )
    assert expected == captured == ("GH-PR-7",)
    assert f"pull:7|{token}" in covered
    with pytest.raises(CoverageError, match="count gap record missing stable ID"):
        coverage_module._fingerprint_child(
            fingerprint,
            (parent,),
            endpoint_fingerprints=fingerprints,
        )
    with pytest.raises(CoverageError, match="count gap record stable ID mismatch"):
        coverage_module._fingerprint_child(
            fingerprint,
            (parent, replace(gap.record, source_id="GH-COUNT-GAP-" + "f" * 24)),
            endpoint_fingerprints=fingerprints,
        )
    changed_count = replace(
        gap.record,
        payload={**gap.record.payload, "missing_count": 5},
    )
    with pytest.raises(CoverageError, match="count gap arithmetic mismatch"):
        coverage_module._fingerprint_child(
            fingerprint,
            (parent, changed_count),
            endpoint_fingerprints=fingerprints,
        )
    changed_parent = replace(parent, raw_hash="0" * 64)
    with pytest.raises(CoverageError, match="count gap parent detail hash mismatch"):
        coverage_module._fingerprint_child(
            fingerprint,
            (changed_parent, gap.record),
            endpoint_fingerprints=fingerprints,
        )
    changed_status = replace(gap.record, availability_status="available")
    with pytest.raises(CoverageError, match="count gap record-only mismatch"):
        coverage_module._fingerprint_child(
            fingerprint,
            (parent, changed_status),
            endpoint_fingerprints=fingerprints,
        )
    changed_fingerprint = replace(
        fingerprint,
        page_response_hashes=("0" * 64,),
    )
    with pytest.raises(CoverageError, match="count gap endpoint fingerprint mismatch"):
        coverage_module._fingerprint_child(
            changed_fingerprint,
            sources,
            endpoint_fingerprints=fingerprints,
        )
    rewritten_detail = {**detail, "coherent_rewrite": True}
    rewritten_body = json.dumps(
        rewritten_detail, sort_keys=True, separators=(",", ":")
    ).encode()
    rewritten_page = replace(
        detail_page,
        body=rewritten_body,
        json=rewritten_detail,
        response_hash=_hash(rewritten_body),
    )
    rewritten_gap, rewritten_token = github_collector._count_gap_record(
        item_key="pull:7",
        endpoint=endpoint,
        endpoint_kind="review-comments",
        snapshot_id="SNAP-test",
        pages=(child_page,),
        params={"per_page": 100},
        accept="application/vnd.github+json",
        child_ids=(),
        expected_count=6,
        parent_detail_page=rewritten_page,
        parent_updated_at=rewritten_detail["updated_at"],
    )
    rewritten_child_fingerprint = github_collector._fingerprint(
        item_key="pull:7",
        endpoint=endpoint,
        params={"per_page": 100},
        accept="application/vnd.github+json",
        pages=(child_page,),
        child_ids=(rewritten_token,),
    )
    rewritten_parent = _source(
        "GH-PR-7",
        "github-pull-request",
        locator="github:zbnerd/probabilistic-valuation-engine/pull/7",
        raw_hash=rewritten_page.response_hash,
        payload={
            "captured_updated_at": rewritten_detail["updated_at"],
            "endpoint_response_raw_sha256": rewritten_page.response_hash,
            "response_raw_sha256": rewritten_page.response_hash,
            "value": rewritten_detail,
        },
    )

    with pytest.raises(CoverageError, match="count gap detail fingerprint mismatch"):
        coverage_module._fingerprint_child(
            rewritten_child_fingerprint,
            (rewritten_parent, rewritten_gap.record),
            endpoint_fingerprints=(
                detail_fingerprint,
                rewritten_child_fingerprint,
            ),
        )


def test_terminal_unavailable_detail_and_enumeration_metadata_count_complete(
    tmp_path: Path,
):
    base = "/repos/zbnerd/probabilistic-valuation-engine"
    pull_body = b'[{"number":7}]'
    issue_body = b"[]"
    pull_page = GitHubPage(
        f"{base}/pulls",
        {"state": "all", "per_page": 100},
        1,
        pull_body,
        [{"number": 7}],
        _hash(pull_body),
        "available",
        200,
        "2026-08-01T00:00:00Z",
    )
    issue_page = GitHubPage(
        f"{base}/issues",
        {"state": "all", "per_page": 100},
        1,
        issue_body,
        [],
        _hash(issue_body),
        "available",
        200,
        "2026-08-01T00:00:00Z",
    )
    detail_endpoint = f"{base}/pulls/7"
    detail_body = b'{"message":"unavailable"}'
    detail_page = GitHubPage(
        detail_endpoint,
        {},
        1,
        detail_body,
        None,
        _hash(detail_body),
        "confirmed-unavailable",
        451,
        "2026-08-01T00:00:01Z",
    )
    fingerprints = (
        github_collector._fingerprint(
            item_key="pull:enumeration",
            endpoint=f"{base}/pulls",
            params={"state": "all", "per_page": 100},
            accept="application/vnd.github+json",
            pages=(pull_page,),
            child_ids=("pull:7",),
        ),
        github_collector._fingerprint(
            item_key="issue:enumeration",
            endpoint=f"{base}/issues",
            params={"state": "all", "per_page": 100},
            accept="application/vnd.github+json",
            pages=(issue_page,),
            child_ids=(),
        ),
        github_collector._fingerprint(
            item_key="pull:7",
            endpoint=detail_endpoint,
            params={},
            accept="application/vnd.github+json",
            pages=(detail_page,),
        ),
    )
    availability = github_collector._availability_record(
        item_key="pull:7",
        endpoint=detail_endpoint,
        snapshot_id="SNAP-test",
        page=detail_page,
        params={},
        accept="application/vnd.github+json",
    ).record
    snapshot, _ = _snapshot(tmp_path)
    window = GitHubSnapshotWindow(
        "s",
        "e",
        "r",
        (7,),
        (),
        {"pull:7": "2026-08-01T00:00:00Z"},
        fingerprints,
    )
    expected, captured, detail = coverage_module._verify_github(
        replace(snapshot, github_window=window),
        (availability,),
        strict_endpoints=True,
    )
    assert expected == captured == ("GH-PR-7",)
    assert "pull:enumeration|pull:7" in detail


def test_external_original_is_rejected_by_full_member_name_basename_and_identity(tmp_path: Path):
    snapshot, external_path = _snapshot(tmp_path)
    original = snapshot.external_input_files[0]
    value = b"safe-derived"
    disguised = StoredArtifactMember(
        "S-part-001",
        "safe.tar.gz#docs/Portfolio_Book/resume.pdf",
        1,
        1,
        len(value),
        _hash(value),
    )
    source = _source("S", "fixture", stored_hash=_hash(value), members=(disguised,))
    with pytest.raises(CoverageError, match="external original.*resume.pdf"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(source,),
            claims=(),
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )

    hash_disguised = replace(
        disguised,
        locator="safe.tar.gz#records/derived.bin",
        sha256=original.sha256,
    )
    with pytest.raises(CoverageError, match="external original identity hash"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(replace(source, stored_members=(hash_disguised,)),),
            claims=(),
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    archive = tmp_path / "safe.tar.gz"
    with tarfile.open(archive, "w:gz") as stream:
        info = tarfile.TarInfo("nested/resume.pdf")
        info.size = len(value)
        stream.addfile(info, io.BytesIO(value))
    with pytest.raises(CoverageError, match="external original path/basename"):
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(),
            claims=(),
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(archive,),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    assert external_path.is_file()


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
                payload={
                    "child_sha": sha,
                    "parent_sha": None,
                    "comparison_base_sha": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                    "parent_total": 0,
                    "parent_ordinal": 1,
                },
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
        expected_semantic_commit_shas=(sha,),
        captured_ref_ids=capture_ref_ids(snapshot),
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
        refs=capture_ref_ids(snapshot),
        mutated_snapshot=snapshot,
    ):
        return verify_source_capture(
            repo=tmp_path,
            snapshot=mutated_snapshot,
            sources=mutated_sources,
            claims=mutated_claims,
            expected_semantic_commit_shas=(sha,),
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
        verify_mutation(
            tuple(item for item in sources if not item.source_id.endswith("-ROOT"))
        )
    transient = replace(github_child, availability_status="transient-failure")
    with pytest.raises(CoverageError, match="transient failure.*GH-CHILD"):
        verify_mutation(
            tuple(
                transient if item.source_id == "GH-CHILD" else item
                for item in sources
            )
        )
    terminal_body = b'{"message":"gone"}'
    terminal_page = GitHubPage(
        endpoint="/repos/o/r/pulls/7/commits",
        params={"per_page": 100},
        page_number=1,
        body=terminal_body,
        json=None,
        response_hash=_hash(terminal_body),
        availability_status="confirmed-unavailable",
        status_code=410,
        fetched_at="2026-08-01T00:00:00Z",
    )
    terminal = github_collector._availability_record(
        item_key="pull:7",
        endpoint=terminal_page.endpoint,
        snapshot_id=snapshot.snapshot_id,
        page=terminal_page,
        params={"per_page": 100},
        accept="application/vnd.github+json",
    ).record
    terminal_fingerprint = github_collector._fingerprint(
        item_key="pull:7",
        endpoint=terminal_page.endpoint,
        params={"per_page": 100},
        accept="application/vnd.github+json",
        pages=(terminal_page,),
    )
    terminal_snapshot = replace(
        snapshot,
        github_window=replace(
            snapshot.github_window,
            endpoint_fingerprints=(
                terminal_fingerprint,
            ),
        ),
    )
    verify_mutation(
        tuple(item for item in sources if item.source_id != "GH-CHILD") + (terminal,),
        mutated_snapshot=terminal_snapshot,
        refs=capture_ref_ids(terminal_snapshot),
    )


def test_parent_diff_identity_is_exact_for_two_parent_commit(tmp_path: Path):
    snapshot, _ = _snapshot(tmp_path)
    child = "1" * 40
    parents = ["2" * 40, "3" * 40]
    commits = tuple(
        _source(
            f"GIT-{sha}",
            "git-commit",
            payload={"commit_sha": sha, "parent_shas": []},
        )
        for sha in parents
    ) + (
        _source(
            f"GIT-{child}",
            "git-commit",
            payload={"commit_sha": child, "parent_shas": parents},
        ),
    )
    root_diffs = tuple(
        _source(
            f"GIT-{sha}-ROOT",
            "git-diff",
            payload={
                "child_sha": sha,
                "parent_sha": None,
                "comparison_base_sha": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                "parent_total": 0,
                "parent_ordinal": 1,
            },
        )
        for sha in parents
    )
    merge_diffs = tuple(
        _source(
            f"GIT-{child}-P{ordinal:02d}",
            "git-diff",
            payload={
                "child_sha": child,
                "parent_sha": parent,
                "comparison_base_sha": parent,
                "parent_total": 2,
                "parent_ordinal": ordinal,
            },
        )
        for ordinal, parent in enumerate(parents, start=1)
    )
    sources = commits + root_diffs + merge_diffs
    # Exercise only the Git verifier through the public gate's internal helper.
    parent_truth = {parents[0]: (), parents[1]: (), child: tuple(parents)}
    coverage_module._verify_git(sources, (*parents, child), parent_truth)
    wrong = replace(
        merge_diffs[1],
        payload={**merge_diffs[1].payload, "parent_sha": parents[0]},
    )
    with pytest.raises(CoverageError, match="parent identity mismatch.*P02"):
        coverage_module._verify_git(
            commits + root_diffs + (merge_diffs[0], wrong),
            (*parents, child),
            parent_truth,
        )


def test_independent_frozen_git_universe_detects_missing_commit_and_ref_corruption(
    tmp_path: Path,
):
    repo = tmp_path / "repo"
    repo.mkdir()

    def git(*args: str) -> str:
        return subprocess.run(
            ("git", *args),
            cwd=repo,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    git("init", "-b", "main")
    git("config", "user.name", "Coverage Test")
    git("config", "user.email", "coverage@example.invalid")
    (repo / "a.txt").write_text("root\n", encoding="utf-8")
    git("add", "a.txt")
    git("commit", "-m", "root")
    root = git("rev-parse", "HEAD")
    (repo / "a.txt").write_text("next\n", encoding="utf-8")
    git("commit", "-am", "next")
    head = git("rev-parse", "HEAD")
    ref = RefSnapshot("refs/heads/main", head, "commit", None, None, None)
    snapshot = SnapshotManifest(
        snapshot_id="SNAP-test",
        started_at="s",
        local_completed_at="l",
        finalized_at="f",
        source_boundary_sha256="0" * 64,
        source_snapshot_head=head,
        source_snapshot_tree=git("rev-parse", f"{head}^{{tree}}"),
        first_excluded_commit="f" * 40,
        first_excluded_parent=head,
        workflow_ref="refs/heads/docs/test",
        observed_head_sha=head,
        observed_head_symbolic_target="refs/heads/main",
        observed_refs=(ref,),
        semantic_refs=(ref,),
        excluded_workflow_commit_shas_at_capture=(),
        external_input_files=(),
        legacy_owned_outputs=(),
        tracked_files=(),
        ai_trace_files=(),
        github_window=GitHubSnapshotWindow("s", "e", "r", (), (), {}, ()),
    )
    sources = (
        _source(f"GIT-{root}", "git-commit", payload={"commit_sha": root, "parent_shas": []}),
        _source(
            f"GIT-{root}-ROOT",
            "git-diff",
            payload={
                "child_sha": root,
                "parent_sha": None,
                "comparison_base_sha": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                "parent_total": 0,
                "parent_ordinal": 1,
            },
        ),
        _source(f"GIT-{head}", "git-commit", payload={"commit_sha": head, "parent_shas": [root]}),
        _source(
            f"GIT-{head}-P01",
            "git-diff",
            payload={
                "child_sha": head,
                "parent_sha": root,
                "comparison_base_sha": root,
                "parent_total": 1,
                "parent_ordinal": 1,
            },
        ),
    )
    manifest = verify_source_capture(
        repo=repo,
        snapshot=snapshot,
        sources=sources,
        claims=(),
        captured_ref_ids=capture_ref_ids(snapshot),
        archive_paths=(),
        staged_output_paths=(),
        require_archive_members=False,
        strict_github_endpoints=False,
    )
    with pytest.raises(CoverageError, match=f"Git commits.*GIT-{head}"):
        verify_source_capture(
            repo=repo,
            snapshot=snapshot,
            sources=sources[:2],
            claims=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    coordinated_corruption = (
        *sources[:2],
        replace(
            sources[2],
            payload={**sources[2].payload, "parent_shas": []},
        ),
        replace(
            sources[3],
            source_id=f"GIT-{head}-ROOT",
            payload={
                **sources[3].payload,
                "parent_sha": None,
                "comparison_base_sha": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                "parent_total": 0,
                "parent_ordinal": 1,
            },
        ),
    )
    with pytest.raises(CoverageError, match=f"frozen commit parent metadata mismatch.*GIT-{head}"):
        verify_source_capture(
            repo=repo,
            snapshot=snapshot,
            sources=coordinated_corruption,
            claims=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
    corrupted = replace(
        snapshot,
        observed_refs=(replace(ref, object_sha=root),),
    )
    with pytest.raises(CoverageError, match="locked capture expected universe mismatch: refs"):
        verify_source_capture(
            repo=repo,
            snapshot=corrupted,
            sources=sources,
            claims=(),
            captured_ref_ids=capture_ref_ids(corrupted),
            archive_paths=(),
            staged_output_paths=(),
            require_archive_members=False,
            strict_github_endpoints=False,
            locked_coverage=manifest.to_dict(),
        )


def test_collect_all_uses_existing_snapshot_orders_stages_and_finalizes_last(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    snapshot, _ = _snapshot(tmp_path)
    snapshot = replace(snapshot, finalized_at=None, github_window=None)
    snapshot_path = tmp_path / "snapshot.json"
    snapshot_path.write_text(json.dumps(snapshot.to_dict()), encoding="utf-8")
    order: list[str] = []
    git_capture = GitCapture((), (), (), (), (), ())
    window = GitHubSnapshotWindow(
        "s", "e", "r", (), (), {}, ()
    )
    github = ReconciliationResult((), window, (ReconciliationPass(0, (), ()),), ())
    section = CoverageSection.complete((), ())
    manifest = CaptureCoverageManifest(1, "capture", "complete", snapshot.snapshot_id, 0, 0, 0, 0, {"refs": section}, ())

    monkeypatch.setattr(coverage_module, "capture_local_snapshot", lambda *args: (_ for _ in ()).throw(AssertionError("recaptured")))
    monkeypatch.setattr(coverage_module, "collect_git_evidence", lambda *args: order.append("git") or git_capture)
    monkeypatch.setattr(coverage_module, "_write_git_archives", lambda *args: ())
    monkeypatch.setattr(coverage_module, "collect_documents", lambda *args: order.append("documents") or ([], []))

    def ai(*args):
        order.append("ai")
        return iter(())

    monkeypatch.setattr(coverage_module, "collect_ai_traces", ai)
    monkeypatch.setattr(coverage_module, "reconcile_github", lambda **kwargs: order.append("github") or github)
    monkeypatch.setattr(coverage_module, "derive_explicit_relations", lambda sources: order.append("relations") or ())
    monkeypatch.setattr(coverage_module, "attach_explicit_relations", lambda sources, relations: tuple(sources))
    monkeypatch.setattr(coverage_module, "verify_source_capture", lambda **kwargs: order.append("coverage") or manifest)
    monkeypatch.setattr(coverage_module, "write_jsonl", lambda *args, **kwargs: order.append("jsonl"))
    monkeypatch.setattr(coverage_module, "_write_commit_csv", lambda *args: order.append("csv"))
    monkeypatch.setattr(coverage_module, "_specialized_inventory", lambda *args: order.append("inventory"))
    monkeypatch.setattr(coverage_module, "write_coverage_manifest", lambda *args: order.append("coverage-write") or ())
    monkeypatch.setattr(coverage_module, "write_snapshot_manifest", lambda *args: order.append("finalize"))

    collect_all(
        repo=tmp_path,
        snapshot_path=snapshot_path,
        output_dir=tmp_path / "out",
        github_client=object(),  # type: ignore[arg-type]
        finalized_at="2026-08-01T00:00:03Z",
    )
    assert order[:6] == ["git", "documents", "ai", "github", "relations", "coverage"]
    assert order[-1] == "finalize"

    order.clear()
    monkeypatch.setattr(
        coverage_module,
        "verify_source_capture",
        lambda **kwargs: (_ for _ in ()).throw(CoverageError("stop")),
    )
    with pytest.raises(CoverageError, match="stop"):
        collect_all(
            repo=tmp_path,
            snapshot_path=snapshot_path,
            output_dir=tmp_path / "out",
            github_client=object(),  # type: ignore[arg-type]
            finalized_at="2026-08-01T00:00:03Z",
        )
    assert "finalize" not in order

def test_external_identity_and_original_path_are_enforced(tmp_path: Path):
    snapshot, external_path = _snapshot(tmp_path)
    with pytest.raises(CoverageError, match="external input identity mismatch.*private/resume.pdf"):
        external_path.write_bytes(b"changed")
        verify_source_capture(
            repo=tmp_path,
            snapshot=snapshot,
            sources=(),
            claims=(),
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
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
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
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
            expected_semantic_commit_shas=(),
            captured_ref_ids=capture_ref_ids(snapshot),
            archive_paths=(),
            staged_output_paths=("private/resume.pdf",),
            require_archive_members=False,
            strict_github_endpoints=False,
        )
