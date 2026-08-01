"""Capture orchestration and mechanical completeness checks.

The manifest emitted here describes capture only.  It does not claim that a
record has been semantically classified or approved for publication.
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import tarfile
import tempfile
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Iterable, Mapping, Sequence

from .ai_trace_collector import collect_ai_traces
from .canonical_io import read_jsonl, write_jsonl
from .document_collector import collect_documents
from .git_collector import GitCapture, collect_git_evidence
from .github_client import CheckpointStore, GitHubClient
from .github_collector import REPOSITORY, ReconciliationResult, reconcile_github
from .models import (
    DocumentClaim,
    GitHubEndpointFingerprint,
    SnapshotManifest,
    SourceBoundary,
    SourceRecord,
    StoredArtifactMember,
)
from .relations import (
    attach_explicit_relations,
    derive_explicit_relations,
    validate_downstream_relation_references,
)
from .snapshot import capture_local_snapshot, write_snapshot_manifest


SNAPSHOT_NAME = "snapshot_manifest.json"
SOURCE_NAME = "source_records.jsonl"
CLAIM_NAME = "document_claim_inventory.jsonl"
COVERAGE_JSON_NAME = "capture_coverage_manifest.json"
COVERAGE_MARKDOWN_NAME = "capture_coverage_manifest.md"


class CoverageError(ValueError):
    """A precise mechanical capture invariant failed."""


def _utf8(value: str) -> bytes:
    return value.encode("utf-8")


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _hash_file(path: Path) -> tuple[int, str]:
    count = 0
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            count += len(chunk)
            digest.update(chunk)
    return count, digest.hexdigest()


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _atomic_bytes(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


@dataclass(frozen=True, slots=True)
class CoverageSection:
    expected_count: int
    captured_count: int
    confirmed_unavailable_count: int
    expected_ids_sha256: str
    captured_ids_sha256: str

    @classmethod
    def complete(
        cls,
        expected_ids: Iterable[str],
        captured_ids: Iterable[str],
        confirmed_unavailable_ids: Iterable[str] = (),
    ) -> CoverageSection:
        expected = tuple(sorted(set(expected_ids), key=_utf8))
        captured = tuple(sorted(set(captured_ids), key=_utf8))
        unavailable = tuple(sorted(set(confirmed_unavailable_ids), key=_utf8))
        return cls(
            expected_count=len(expected),
            captured_count=len(captured),
            confirmed_unavailable_count=len(unavailable),
            expected_ids_sha256=_sha256(_canonical_json(expected)),
            captured_ids_sha256=_sha256(_canonical_json(captured)),
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "expected_count": self.expected_count,
            "captured_count": self.captured_count,
            "confirmed_unavailable_count": self.confirmed_unavailable_count,
            "expected_ids_sha256": self.expected_ids_sha256,
            "captured_ids_sha256": self.captured_ids_sha256,
        }


@dataclass(frozen=True, slots=True)
class CaptureCoverageManifest:
    schema_version: int
    phase: str
    status: str
    snapshot_id: str
    source_record_count: int
    document_claim_count: int
    relation_count: int
    archive_count: int
    sections: dict[str, CoverageSection]
    limitations: tuple[str, ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "phase": self.phase,
            "status": self.status,
            "snapshot_id": self.snapshot_id,
            "source_record_count": self.source_record_count,
            "document_claim_count": self.document_claim_count,
            "relation_count": self.relation_count,
            "archive_count": self.archive_count,
            "sections": {
                key: self.sections[key].to_dict()
                for key in sorted(self.sections, key=_utf8)
            },
            "limitations": list(self.limitations),
        }


@dataclass(frozen=True, slots=True)
class CaptureArtifacts:
    snapshot: SnapshotManifest
    sources: tuple[SourceRecord, ...]
    claims: tuple[DocumentClaim, ...]
    coverage: CaptureCoverageManifest
    archive_paths: tuple[Path, ...]


class _SystemClock:
    def now(self) -> datetime:
        return datetime.now(UTC)


def _stable_set(label: str, expected: Iterable[str], captured: Iterable[str]) -> None:
    expected_set = set(expected)
    captured_set = set(captured)
    missing = sorted(expected_set - captured_set, key=_utf8)
    if missing:
        raise CoverageError(f"{label}: missing stable ID: {missing[0]}")
    extra = sorted(captured_set - expected_set, key=_utf8)
    if extra:
        raise CoverageError(f"{label}: unexpected stable ID: {extra[0]}")


def _reject_external_originals(
    repo: Path,
    snapshot: SnapshotManifest,
    sources: tuple[SourceRecord, ...],
    archive_paths: tuple[Path, ...],
    staged_output_paths: tuple[str | Path, ...],
) -> None:
    root = repo.resolve(strict=True)
    originals = {item.path: (root / item.path).resolve() for item in snapshot.external_input_files}
    checked: list[tuple[str, Path]] = []
    for path in archive_paths:
        candidate = path if path.is_absolute() else root / path
        checked.append((path.as_posix(), candidate.resolve()))
    for value in staged_output_paths:
        path = Path(value)
        candidate = path if path.is_absolute() else root / path
        checked.append((path.as_posix(), candidate.resolve(strict=False)))
    for label, candidate in checked:
        for original_path, original in originals.items():
            if candidate == original:
                raise CoverageError(
                    f"external original appears in archive/staged outputs: {original_path} ({label})"
                )
    original_names = set(originals)
    for source in sources:
        locators = [
            source.raw_archive_locator or "",
            *(member.locator for member in source.stored_members),
        ]
        for locator in locators:
            archive_name = locator.split("#", 1)[0]
            if archive_name in original_names or any(
                archive_name.endswith("/" + value) for value in original_names
            ):
                raise CoverageError(
                    f"external original appears in source archive locator: {archive_name}"
                )


def _archive_entries(path: Path) -> tuple[dict[str, bytes], dict[str, object] | None]:
    values: dict[str, bytes] = {}
    manifest: dict[str, object] | None = None
    try:
        with tarfile.open(path, "r:gz") as archive:
            for info in archive.getmembers():
                if not info.isfile():
                    raise CoverageError(f"non-file archive member: {path.name}#{info.name}")
                extracted = archive.extractfile(info)
                if extracted is None:
                    raise CoverageError(f"unreadable archive member: {path.name}#{info.name}")
                value = extracted.read()
                if info.name == "reassembly-manifest.json":
                    try:
                        parsed = json.loads(value)
                    except json.JSONDecodeError as error:
                        raise CoverageError(
                            f"invalid reassembly manifest: {path.name}"
                        ) from error
                    if not isinstance(parsed, dict):
                        raise CoverageError(f"invalid reassembly manifest: {path.name}")
                    manifest = parsed
                    continue
                locator = f"{path.name}#{info.name}"
                if locator in values:
                    raise CoverageError(f"duplicate archive member: {locator}")
                values[locator] = value
    except (OSError, tarfile.TarError) as error:
        raise CoverageError(f"unreadable archive: {path}") from error
    return values, manifest


def verify_archive_members(
    sources: Iterable[SourceRecord], archive_paths: Iterable[str | Path]
) -> None:
    """Verify exact stored-member union, order, hashes, and reassembly."""
    frozen_sources = tuple(sources)
    paths = tuple(Path(path) for path in archive_paths)
    by_name: dict[str, Path] = {}
    actual: dict[str, bytes] = {}
    manifests: dict[str, dict[str, object]] = {}
    for path in paths:
        if path.name in by_name:
            raise CoverageError(f"duplicate archive basename: {path.name}")
        by_name[path.name] = path
        entries, manifest = _archive_entries(path)
        overlap = set(actual).intersection(entries)
        if overlap:
            raise CoverageError(f"duplicate archive locator: {sorted(overlap, key=_utf8)[0]}")
        actual.update(entries)
        if manifest is not None:
            manifests[path.name] = manifest

    declared: dict[str, StoredArtifactMember] = {}
    reconstructed: dict[str, bytes] = {}
    for source in frozen_sources:
        members = source.stored_members
        if not members:
            continue
        ordinals = tuple(member.ordinal for member in members)
        expected_ordinals = tuple(range(1, len(members) + 1))
        if ordinals != expected_ordinals:
            raise CoverageError(f"archive part ordering mismatch: {source.source_id}")
        chunks: list[bytes] = []
        for member in members:
            if member.locator in declared:
                raise CoverageError(f"duplicate declared member locator: {member.locator}")
            declared[member.locator] = member
            value = actual.get(member.locator)
            if value is None:
                raise CoverageError(f"archive member union missing: {member.member_id}")
            if len(value) != member.byte_count:
                raise CoverageError(f"member byte count mismatch: {member.member_id}")
            if _sha256(value) != member.sha256:
                raise CoverageError(f"member hash mismatch: {member.member_id}")
            chunks.append(value)
        reconstructed[source.source_id] = b"".join(chunks)
    extra = sorted(set(actual) - set(declared), key=_utf8)
    missing = sorted(set(declared) - set(actual), key=_utf8)
    if missing:
        raise CoverageError(f"archive member union missing: {missing[0]}")
    if extra:
        raise CoverageError(f"archive member union has undeclared member: {extra[0]}")
    for source in frozen_sources:
        if source.source_id in reconstructed and _sha256(reconstructed[source.source_id]) != source.stored_hash:
            raise CoverageError(f"reassembly hash mismatch: {source.source_id}")
    for source in frozen_sources:
        if source.stored_members and any(
            member.total != len(source.stored_members)
            for member in source.stored_members
        ):
            raise CoverageError(f"archive part total mismatch: {source.source_id}")

    # Where a collector emitted a manifest, require its declared part rows to
    # be exactly the same ledger objects used by source records.
    for filename, manifest in manifests.items():
        rows: list[dict[str, object]] = []
        entries = manifest.get("entries")
        if not isinstance(entries, list):
            raise CoverageError(f"invalid reassembly manifest entries: {filename}")
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("parts"), list):
                raise CoverageError(f"invalid reassembly manifest entry: {filename}")
            rows.extend(part for part in entry["parts"] if isinstance(part, dict))
            source_id = entry.get("source_id")
            source = next(
                (value for value in frozen_sources if value.source_id == source_id),
                None,
            )
            if source is None:
                raise CoverageError(
                    f"reassembly manifest source missing stable ID: {filename}|{source_id}"
                )
            if (
                entry.get("whole_sha256") != source.stored_hash
                or entry.get("whole_byte_count") != sum(
                    member.byte_count for member in source.stored_members
                )
            ):
                raise CoverageError(
                    f"reassembly manifest whole identity mismatch: {source.source_id}"
                )
        manifest_locators = {str(row.get("locator")) for row in rows}
        archive_locators = {
            locator for locator in declared if locator.startswith(filename + "#")
        }
        if manifest_locators != archive_locators:
            raise CoverageError(f"reassembly manifest member union mismatch: {filename}")
        for row in rows:
            locator = str(row.get("locator"))
            member = declared[locator]
            if member.to_dict() != row:
                raise CoverageError(f"reassembly manifest member mismatch: {member.member_id}")


def _verify_external_inputs(
    repo: Path,
    snapshot: SnapshotManifest,
    sources: tuple[SourceRecord, ...],
    claims: tuple[DocumentClaim, ...],
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    by_locator = {source.source_locator: source for source in sources}
    expected: list[str] = []
    captured: list[str] = []
    for item in snapshot.external_input_files:
        expected.append(f"EXTERNAL:{item.path}")
        path = repo / item.path
        try:
            byte_count, actual_hash = _hash_file(path)
        except OSError as error:
            raise CoverageError(f"external input missing: {item.path}") from error
        if byte_count != item.byte_count or actual_hash != item.sha256:
            raise CoverageError(f"external input identity mismatch: {item.path}")
        source = by_locator.get(f"external:{item.path}")
        if source is None:
            continue
        payload = source.payload
        if (
            source.raw_hash != item.sha256
            or payload.get("path") != item.path
            or payload.get("external_role") != item.role
            or payload.get("original_identity_sha256") != item.sha256
            or payload.get("original_byte_count") != item.byte_count
            or payload.get("original_pdf_archived") is not False
        ):
            raise CoverageError(f"external source identity mismatch: {item.path}")
        descendants = tuple(
            claim for claim in claims if claim.document_source_id == source.source_id
        )
        unit_count = payload.get("unit_count")
        if unit_count != len(descendants):
            raise CoverageError(f"external claim count mismatch: {source.source_id}")
        counts = {
            "pdf-page": payload.get("page_count"),
            "pdf-text-block": payload.get("text_block_count"),
            "pdf-image-object": payload.get("image_object_count"),
        }
        for kind, count in counts.items():
            actual = sum(claim.unit_kind == kind for claim in descendants)
            if count != actual:
                raise CoverageError(
                    f"external {kind} missing stable ID/count: {source.source_id} expected={count} actual={actual}"
                )
        page_count = payload.get("page_count")
        if isinstance(page_count, int):
            pages = tuple(
                sorted(
                    claim.page_index
                    for claim in descendants
                    if claim.unit_kind == "pdf-page" and claim.page_index is not None
                )
            )
            if pages != tuple(range(page_count)):
                raise CoverageError(f"external PDF page missing stable ID: {source.source_id}")
        captured.append(f"EXTERNAL:{item.path}")
    expected_locators = {f"external:{item.path}" for item in snapshot.external_input_files}
    unexpected = sorted(
        (
            source.source_locator
            for source in sources
            if source.source_locator.startswith("external:")
            and source.source_locator not in expected_locators
        ),
        key=_utf8,
    )
    if unexpected:
        raise CoverageError(f"external inputs: unexpected stable ID: {unexpected[0]}")
    return tuple(expected), tuple(captured)


def _verify_documents(
    snapshot: SnapshotManifest,
    sources: tuple[SourceRecord, ...],
    claims: tuple[DocumentClaim, ...],
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    expected = tuple(
        f"DOC:{item.path}"
        for item in snapshot.tracked_files
        if item.collection_rule_id == "document"
    )
    locators = {source.source_locator for source in sources}
    captured = tuple(
        f"DOC:{item.path}"
        for item in snapshot.tracked_files
        if item.collection_rule_id == "document" and f"git:{item.path}" in locators
    )
    _stable_set("documents", expected, captured)
    claim_ids = [claim.claim_id for claim in claims]
    if len(claim_ids) != len(set(claim_ids)):
        raise CoverageError("document claims: duplicate stable ID")
    source_ids = {source.source_id for source in sources}
    for claim in claims:
        if claim.document_source_id not in source_ids:
            raise CoverageError(
                f"document claim parent missing stable ID: {claim.document_source_id}"
            )
    for source in sources:
        if source.source_locator.startswith(("git:", "external:")) and "unit_count" in source.payload:
            actual = sum(claim.document_source_id == source.source_id for claim in claims)
            if source.payload["unit_count"] != actual:
                raise CoverageError(f"document claim count mismatch: {source.source_id}")
    return expected, captured


def _verify_ai(
    snapshot: SnapshotManifest, sources: tuple[SourceRecord, ...]
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    expected = tuple(f"AI:{item.path}" for item in snapshot.ai_trace_files)
    containers = {
        str(source.payload.get("source_path")): source
        for source in sources
        if source.source_type == "ai-trace-file"
    }
    captured = tuple(f"AI:{path}" for path in containers)
    _stable_set("AI traces", expected, captured)
    snapshots = {item.path: item for item in snapshot.ai_trace_files}
    for path, container in containers.items():
        frozen = snapshots[path]
        if container.raw_hash != frozen.sha256:
            raise CoverageError(f"AI trace hash mismatch: {path}")
        children = tuple(
            source
            for source in sources
            if source.source_type == "ai-trace-entry"
            and source.payload.get("source_path") == path
        )
        if container.payload.get("entry_count") != len(children):
            raise CoverageError(f"AI trace child missing stable ID: {path}")
    return expected, captured


def _verify_git(
    sources: tuple[SourceRecord, ...], semantic_commit_shas: tuple[str, ...]
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...], tuple[str, ...]]:
    expected_commits = tuple(f"GIT-{sha}" for sha in semantic_commit_shas)
    captured_commits = tuple(
        source.source_id for source in sources if source.source_type == "git-commit"
    )
    _stable_set("Git commits", expected_commits, captured_commits)
    expected_diffs: list[str] = []
    for source in sources:
        if source.source_type != "git-commit":
            continue
        sha = source.payload.get("commit_sha")
        parents = source.payload.get("parent_shas")
        if not isinstance(sha, str) or not isinstance(parents, list):
            raise CoverageError(f"Git commit parent metadata missing: {source.source_id}")
        if not parents:
            expected_diffs.append(f"GIT-{sha}-ROOT")
        else:
            expected_diffs.extend(
                f"GIT-{sha}-P{ordinal:02d}"
                for ordinal in range(1, len(parents) + 1)
            )
    captured_diffs = tuple(
        source.source_id for source in sources if source.source_type == "git-diff"
    )
    _stable_set("Git parent diffs", expected_diffs, captured_diffs)
    by_id = {source.source_id: source for source in sources}
    for diff_id in expected_diffs:
        diff = by_id[diff_id]
        child = str(diff.payload.get("child_sha"))
        commit = by_id[f"GIT-{child}"]
        parents = commit.payload["parent_shas"]
        expected_total = len(parents)
        if diff.payload.get("parent_total") != expected_total:
            raise CoverageError(f"Git diff parent count mismatch: {diff_id}")
        if expected_total == 0 and diff.payload.get("parent_sha") is not None:
            raise CoverageError(f"Git root diff comparison mismatch: {diff_id}")
    return (
        expected_commits,
        captured_commits,
        tuple(expected_diffs),
        captured_diffs,
    )


def _fingerprint_child(
    fingerprint: GitHubEndpointFingerprint,
    sources: tuple[SourceRecord, ...],
) -> tuple[str, ...]:
    if fingerprint.availability_status == "transient-failure":
        raise CoverageError(
            f"GitHub transient failure is incomplete: {fingerprint.item_key}|{fingerprint.endpoint_key}"
        )
    if fingerprint.availability_status not in {"available", "confirmed-unavailable"}:
        raise CoverageError(
            f"GitHub invalid availability: {fingerprint.item_key}|{fingerprint.endpoint_key}"
        )
    pages = fingerprint.page_numbers
    if pages != tuple(range(1, len(pages) + 1)):
        raise CoverageError(
            f"GitHub page missing stable ID: {fingerprint.item_key}|{fingerprint.endpoint_key}"
        )
    if len(pages) != len(fingerprint.page_response_hashes):
        raise CoverageError(
            f"GitHub page/hash reconciliation mismatch: {fingerprint.item_key}|{fingerprint.endpoint_key}"
        )
    captured: list[str] = []
    for stable_id in fingerprint.stable_child_ids:
        expected_locator = f"github:{fingerprint.endpoint_key}#{stable_id}"
        matches = [source for source in sources if source.source_locator == expected_locator]
        if len(matches) != 1:
            raise CoverageError(
                f"GitHub child missing stable ID: {fingerprint.item_key}|{stable_id}"
            )
        if matches[0].raw_hash not in fingerprint.page_response_hashes:
            raise CoverageError(
                f"GitHub child hash reconciliation mismatch: {matches[0].source_id}"
            )
        captured.append(f"{fingerprint.item_key}|{stable_id}")
    return tuple(captured)


def _verify_github(
    snapshot: SnapshotManifest,
    sources: tuple[SourceRecord, ...],
    *,
    strict_endpoints: bool,
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...]]:
    window = snapshot.github_window
    if window is None:
        raise CoverageError("GitHub reconciliation window is absent")
    expected_parents = tuple(
        [f"GH-PR-{number}" for number in window.pull_request_numbers]
        + [f"GH-ISSUE-{number}" for number in window.issue_numbers]
    )
    source_ids = {source.source_id for source in sources}
    captured_parents = tuple(
        source.source_id
        for source in sources
        if source.source_type in {"github-pull-request", "github-issue"}
    )
    _stable_set("GitHub parents", expected_parents, captured_parents)
    expected_updates = tuple(
        [f"pr:{number}" for number in window.pull_request_numbers]
        + [f"issue:{number}" for number in window.issue_numbers]
    )
    _stable_set("GitHub updated-at items", expected_updates, window.updated_at_by_item)
    fingerprint_keys: list[str] = []
    children: list[str] = []
    seen: set[tuple[str, str]] = set()
    for fingerprint in window.endpoint_fingerprints:
        key = (fingerprint.item_key, fingerprint.endpoint_key)
        if key in seen:
            raise CoverageError(f"GitHub duplicate endpoint fingerprint: {key[0]}|{key[1]}")
        seen.add(key)
        fingerprint_keys.append(f"{key[0]}|{key[1]}")
        children.extend(_fingerprint_child(fingerprint, sources))
    if strict_endpoints:
        expected_endpoint_suffixes: list[tuple[str, str]] = [
            ("pull:enumeration", "/pulls"),
            ("issue:enumeration", "/issues"),
        ]
        for number in window.pull_request_numbers:
            expected_endpoint_suffixes.extend(
                (
                    (f"pr:{number}", f"/pulls/{number}"),
                    (f"pr:{number}", f"/pulls/{number}/commits"),
                    (f"pr:{number}", f"/pulls/{number}/files"),
                    (f"pr:{number}", f"/pulls/{number}/reviews"),
                    (f"pr:{number}", f"/pulls/{number}/comments"),
                    (f"pr:{number}", f"/issues/{number}/comments"),
                    (f"pr:{number}", f"/issues/{number}/timeline"),
                    (f"pr:{number}", f"/issues/{number}/reactions"),
                    (f"pr:{number}", f"/pulls/{number}/requested_reviewers"),
                    (f"pr:{number}", f"/pulls/{number}.patch"),
                )
            )
        for number in window.issue_numbers:
            expected_endpoint_suffixes.extend(
                (
                    (f"issue:{number}", f"/issues/{number}"),
                    (f"issue:{number}", f"/issues/{number}/comments"),
                    (f"issue:{number}", f"/issues/{number}/timeline"),
                    (f"issue:{number}", f"/issues/{number}/reactions"),
                )
            )
        for item_key, suffix in expected_endpoint_suffixes:
            matches = [
                endpoint
                for candidate_item, endpoint in seen
                if candidate_item == item_key and endpoint.endswith(suffix)
            ]
            if len(matches) != 1:
                raise CoverageError(
                    f"GitHub endpoint fingerprint missing stable ID: {item_key}|*{suffix}"
                )
    # Every archived GitHub endpoint record must still have its endpoint proof.
    for source in sources:
        if not source.source_locator.startswith("github:/"):
            continue
        locator = source.source_locator.removeprefix("github:").split("#", 1)[0]
        child_types = {
            "github-pr-commit",
            "github-pr-file",
            "github-review",
            "github-review-comment",
            "github-conversation-comment",
            "github-timeline-event",
            "github-reaction",
            "github-requested-reviewer",
        }
        matching = [
            fingerprint
            for fingerprint in window.endpoint_fingerprints
            if fingerprint.endpoint_key == locator
        ]
        if source.source_type in child_types | {"github-patch", "github-availability"} and not matching:
            raise CoverageError(f"GitHub endpoint fingerprint missing stable ID: {locator}")
        if source.source_type in child_types:
            stable_id = source.source_locator.split("#", 1)[1] if "#" in source.source_locator else ""
            if len(matching) != 1 or stable_id not in matching[0].stable_child_ids:
                raise CoverageError(
                    f"GitHub child fingerprint missing stable ID: {source.source_id}|{stable_id}"
                )
    transient = [
        source.source_id
        for source in sources
        if source.availability_status == "transient-failure"
    ]
    if transient:
        raise CoverageError(f"GitHub transient failure is incomplete: {transient[0]}")
    return expected_parents, captured_parents, tuple(fingerprint_keys + children)


def verify_source_capture(
    *,
    repo: str | Path,
    snapshot: SnapshotManifest,
    sources: Iterable[SourceRecord],
    claims: Iterable[DocumentClaim],
    semantic_commit_shas: Iterable[str],
    captured_ref_ids: Iterable[str],
    archive_paths: Iterable[str | Path],
    staged_output_paths: Iterable[str | Path],
    require_archive_members: bool = True,
    strict_github_endpoints: bool = True,
) -> CaptureCoverageManifest:
    """Verify capture completeness without performing semantic classification."""
    repository = Path(repo).resolve(strict=True)
    frozen_sources = tuple(sources)
    frozen_claims = tuple(claims)
    paths = tuple(Path(path) for path in archive_paths)
    staged = tuple(staged_output_paths)
    source_ids = [source.source_id for source in frozen_sources]
    if len(source_ids) != len(set(source_ids)):
        raise CoverageError("source records: duplicate stable ID")
    if any(source.snapshot_id != snapshot.snapshot_id for source in frozen_sources):
        raise CoverageError("source record belongs to a different frozen snapshot")
    _reject_external_originals(repository, snapshot, frozen_sources, paths, staged)
    expected_refs = tuple(f"REF:{item.refname}" for item in snapshot.observed_refs)
    captured_refs = tuple(captured_ref_ids)
    _stable_set("Git refs", expected_refs, captured_refs)
    semantic = tuple(semantic_commit_shas)
    git_expected, git_captured, diff_expected, diff_captured = _verify_git(
        frozen_sources, semantic
    )
    external_expected, external_captured = _verify_external_inputs(
        repository, snapshot, frozen_sources, frozen_claims
    )
    _stable_set("external inputs", external_expected, external_captured)
    document_expected, document_captured = _verify_documents(
        snapshot, frozen_sources, frozen_claims
    )
    ai_expected, ai_captured = _verify_ai(snapshot, frozen_sources)
    github_expected, github_captured, github_detail = _verify_github(
        snapshot, frozen_sources, strict_endpoints=strict_github_endpoints
    )
    if require_archive_members:
        required = tuple(
            source.source_id
            for source in frozen_sources
            if source.source_type
            in {
                "git-diff",
                "tracked-document",
                "external-pdf-derived-record",
                "ai-trace-file",
                "ai-trace-entry",
                "github-pull-request",
                "github-issue",
                "github-pr-commit",
                "github-pr-file",
                "github-review",
                "github-review-comment",
                "github-conversation-comment",
                "github-timeline-event",
                "github-reaction",
                "github-requested-reviewer",
                "github-patch",
                "github-availability",
            }
            and not source.stored_members
        )
        if required:
            raise CoverageError(f"stored archive member missing stable ID: {required[0]}")
        verify_archive_members(frozen_sources, paths)
    relation_ledger = validate_downstream_relation_references(
        frozen_sources, claims=frozen_claims
    )
    unavailable = tuple(
        source.source_id
        for source in frozen_sources
        if source.availability_status == "confirmed-unavailable"
    )
    sections = {
        "refs": CoverageSection.complete(expected_refs, captured_refs),
        "git_commits": CoverageSection.complete(git_expected, git_captured),
        "git_diffs": CoverageSection.complete(diff_expected, diff_captured),
        "documents": CoverageSection.complete(document_expected, document_captured),
        "external_inputs": CoverageSection.complete(external_expected, external_captured),
        "ai_traces": CoverageSection.complete(ai_expected, ai_captured),
        "github_parents": CoverageSection.complete(
            github_expected, github_captured, unavailable
        ),
        "github_endpoint_and_children": CoverageSection.complete(
            github_detail, github_detail, unavailable
        ),
    }
    return CaptureCoverageManifest(
        schema_version=1,
        phase="capture",
        status="complete",
        snapshot_id=snapshot.snapshot_id,
        source_record_count=len(frozen_sources),
        document_claim_count=len(frozen_claims),
        relation_count=len(relation_ledger),
        archive_count=len(paths),
        sections=sections,
        limitations=(
            "Capture completeness only; semantic classification and publication release coverage are separate gates.",
            "confirmed-unavailable is terminally recorded; transient failures are incomplete.",
        ),
    )


def write_coverage_manifest(
    output_dir: str | Path, manifest: CaptureCoverageManifest
) -> tuple[Path, Path]:
    root = Path(output_dir)
    json_path = root / COVERAGE_JSON_NAME
    markdown_path = root / COVERAGE_MARKDOWN_NAME
    _atomic_bytes(json_path, _canonical_json(manifest.to_dict()))
    rows = [
        "# Capture coverage manifest",
        "",
        "> Capture-phase completeness only. This is not final classification or release coverage.",
        "",
        f"- Snapshot: `{manifest.snapshot_id}`",
        f"- Status: `{manifest.status}`",
        f"- Source records: {manifest.source_record_count:,}",
        f"- Document claims: {manifest.document_claim_count:,}",
        f"- Explicit relations: {manifest.relation_count:,}",
        f"- Archives: {manifest.archive_count:,}",
        "",
        "| Capture section | Expected | Captured | Confirmed unavailable |",
        "|---|---:|---:|---:|",
    ]
    for key in sorted(manifest.sections, key=_utf8):
        section = manifest.sections[key]
        rows.append(
            f"| `{key}` | {section.expected_count:,} | {section.captured_count:,} | {section.confirmed_unavailable_count:,} |"
        )
    rows.extend(["", "## Limits", ""])
    rows.extend(f"- {value}" for value in manifest.limitations)
    _atomic_bytes(markdown_path, ("\n".join(rows) + "\n").encode("utf-8"))
    return json_path, markdown_path


def capture_snapshot(
    repo: str | Path,
    boundary_path: str | Path,
    output_path: str | Path,
) -> SnapshotManifest:
    repository = Path(repo).resolve(strict=True)
    payload = json.loads(Path(boundary_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("source boundary must be a JSON object")
    boundary = SourceBoundary.from_dict(payload)
    manifest = capture_local_snapshot(repository, boundary, _SystemClock())
    write_snapshot_manifest(output_path, manifest)
    return manifest


def _write_git_archives(output_dir: Path, capture: GitCapture) -> tuple[Path, ...]:
    paths: list[Path] = []
    expected = {volume.filename for volume in capture.archive_volumes}
    for volume in capture.archive_volumes:
        path = output_dir / volume.filename
        if len(volume.data) != volume.byte_count or _sha256(volume.data) != volume.sha256:
            raise CoverageError(f"Git archive identity mismatch before write: {volume.filename}")
        _atomic_bytes(path, volume.data)
        paths.append(path)
    for stale in output_dir.glob("commit-diffs-*.tar.gz"):
        if stale.name not in expected:
            stale.unlink()
    return tuple(paths)


def _write_commit_csv(path: Path, records: Iterable[SourceRecord]) -> None:
    rows = [source for source in records if source.source_type == "git-commit"]
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(("source_id", "commit_sha", "committed_at", "subject", "parent_count"))
            for source in sorted(rows, key=lambda item: _utf8(item.source_id)):
                parents = source.payload.get("parent_shas", [])
                writer.writerow(
                    (
                        source.source_id,
                        source.payload.get("commit_sha", ""),
                        source.payload.get("committed_at", ""),
                        source.payload.get("subject", ""),
                        len(parents) if isinstance(parents, list) else "",
                    )
                )
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _specialized_inventory(
    output_dir: Path, name: str, sources: Iterable[SourceRecord], predicate
) -> None:
    write_jsonl(
        output_dir / name,
        tuple(source for source in sources if predicate(source)),
        source_universe=tuple(sources),
    )


def collect_all(
    *,
    repo: str | Path,
    snapshot_path: str | Path,
    output_dir: str | Path,
    github_client: GitHubClient | None = None,
    repository_name: str = REPOSITORY,
    staged_output_paths: Iterable[str | Path] = (),
    finalized_at: str | None = None,
) -> CaptureArtifacts:
    """Run all collectors from one existing frozen snapshot, then reconcile."""
    repository = Path(repo).resolve(strict=True)
    output = Path(output_dir)
    payload = json.loads(Path(snapshot_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("snapshot manifest must be a JSON object")
    snapshot = SnapshotManifest.from_dict(payload)
    if snapshot.finalized_at is not None or snapshot.github_window is not None:
        raise ValueError("collect-all requires an unfinalized local snapshot")

    # Ordering is intentional and all local enumeration comes only from the
    # already-frozen SnapshotManifest passed to each collector.
    git_capture = collect_git_evidence(repository, snapshot)
    git_archives = _write_git_archives(output, git_capture)
    document_sources, claims = collect_documents(repository, snapshot, output)
    ai_sources = tuple(collect_ai_traces(repository, snapshot, output))
    client = github_client or GitHubClient(
        checkpoint_store=CheckpointStore(output / ".github-checkpoints")
    )
    github: ReconciliationResult = reconcile_github(
        client=client,
        repository=repository_name,
        snapshot_id=snapshot.snapshot_id,
        archive_dir=output,
    )
    completed_at = finalized_at or datetime.now(UTC).isoformat().replace("+00:00", "Z")
    final_snapshot = replace(
        snapshot,
        finalized_at=completed_at,
        github_window=github.window,
    )
    raw_sources = tuple(
        sorted(
            (
                *git_capture.records,
                *document_sources,
                *ai_sources,
                *github.records,
            ),
            key=lambda item: _utf8(item.source_id),
        )
    )
    relations = derive_explicit_relations(raw_sources)
    sources = attach_explicit_relations(raw_sources, relations)
    archive_paths = tuple(
        sorted(
            {
                *git_archives,
                *github.archive_paths,
                *output.glob("document-records-*.tar.gz"),
                *output.glob("ai-trace-records-*.tar.gz"),
            },
            key=lambda path: _utf8(path.name),
        )
    )
    coverage = verify_source_capture(
        repo=repository,
        snapshot=final_snapshot,
        sources=sources,
        claims=claims,
        semantic_commit_shas=git_capture.semantic_commit_shas,
        captured_ref_ids=(f"REF:{item.refname}" for item in snapshot.observed_refs),
        archive_paths=archive_paths,
        staged_output_paths=staged_output_paths,
    )

    # Finalization happens only after every collector and reconciliation gate
    # succeeds.  Canonical ledgers are atomically replaced before the immutable
    # snapshot is marked final.
    write_jsonl(output / SOURCE_NAME, sources, claim_universe=claims)
    write_jsonl(
        output / CLAIM_NAME,
        claims,
        source_universe=sources,
        claim_universe=claims,
    )
    _write_commit_csv(output / "commit_inventory.csv", sources)
    _specialized_inventory(
        output, "pr_inventory.jsonl", sources, lambda value: value.source_type.startswith("github-") and ("/pull" in value.source_locator or value.source_id.startswith("GH-PR-"))
    )
    _specialized_inventory(
        output, "issue_inventory.jsonl", sources, lambda value: value.source_type.startswith("github-") and "/issues/" in value.source_locator
    )
    _specialized_inventory(
        output, "ai_trace_inventory.jsonl", sources, lambda value: value.source_type.startswith("ai-trace-")
    )
    write_coverage_manifest(output, coverage)
    write_snapshot_manifest(snapshot_path, final_snapshot)
    return CaptureArtifacts(final_snapshot, sources, tuple(claims), coverage, archive_paths)


def verify_capture_files(
    *,
    repo: str | Path,
    snapshot_path: str | Path,
    output_dir: str | Path,
    staged_output_paths: Iterable[str | Path] = (),
) -> CaptureCoverageManifest:
    """Reload and re-verify an already finalized source capture."""
    output = Path(output_dir)
    payload = json.loads(Path(snapshot_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("snapshot manifest must be a JSON object")
    snapshot = SnapshotManifest.from_dict(payload)
    sources = tuple(read_jsonl(output / SOURCE_NAME, SourceRecord))
    claims = tuple(
        read_jsonl(
            output / CLAIM_NAME,
            DocumentClaim,
            source_universe=sources,
        )
    )
    semantic_commit_shas = tuple(
        str(source.payload["commit_sha"])
        for source in sources
        if source.source_type == "git-commit"
    )
    archives = tuple(sorted(output.glob("*.tar.gz"), key=lambda path: _utf8(path.name)))
    return verify_source_capture(
        repo=repo,
        snapshot=snapshot,
        sources=sources,
        claims=claims,
        semantic_commit_shas=semantic_commit_shas,
        captured_ref_ids=(f"REF:{item.refname}" for item in snapshot.observed_refs),
        archive_paths=archives,
        staged_output_paths=staged_output_paths,
    )
