"""Capture orchestration and mechanical completeness checks.

The manifest emitted here describes capture only.  It does not claim that a
record has been semantically classified or approved for publication.
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import subprocess
import tarfile
import tempfile
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable, Iterable, Mapping, Sequence

from .ai_trace_collector import collect_ai_traces
from .canonical_io import read_jsonl_with_descriptor, write_jsonl
from .document_collector import collect_documents
from .git_collector import EMPTY_TREE_SHA, GitCapture, collect_git_evidence
from .github_client import CheckpointStore, GitHubClient, is_exact_patch_variant
from .github_collector import REPOSITORY, ReconciliationResult, reconcile_github
from .jsonl_artifact import JsonlArtifactDescriptor
from .models import (
    DocumentClaim,
    GitHubEndpointFingerprint,
    RefSnapshot,
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
PR_INVENTORY_NAME = "pr_inventory.jsonl"
ISSUE_INVENTORY_NAME = "issue_inventory.jsonl"
AI_INVENTORY_NAME = "ai_trace_inventory.jsonl"

CAPTURE_LEDGER_SPECS = (
    (AI_INVENTORY_NAME, SourceRecord),
    (CLAIM_NAME, DocumentClaim),
    (ISSUE_INVENTORY_NAME, SourceRecord),
    (PR_INVENTORY_NAME, SourceRecord),
    (SOURCE_NAME, SourceRecord),
)


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
    ledger_artifacts: tuple[JsonlArtifactDescriptor, ...] = ()

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
            "ledger_artifacts": [item.to_dict() for item in self.ledger_artifacts],
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


def _ref_id(ref: RefSnapshot) -> str:
    identity = _sha256(_canonical_json(ref.to_dict()))
    return f"REF:{ref.refname}@{identity}"


def capture_ref_ids(snapshot: SnapshotManifest) -> tuple[str, ...]:
    """Return identity-bearing IDs for every ref frozen by the snapshot."""
    return tuple(_ref_id(ref) for ref in snapshot.observed_refs)


def _snapshot_for_git_collection(snapshot: SnapshotManifest) -> SnapshotManifest:
    revisions = {
        value
        for ref in snapshot.semantic_refs
        for value in (ref.object_sha, ref.peeled_sha)
        if value is not None
    }
    if snapshot.source_snapshot_head in revisions:
        return snapshot
    source_ref = RefSnapshot(
        refname="refs/frozen/source-snapshot-head",
        object_sha=snapshot.source_snapshot_head,
        object_type="commit",
        peeled_sha=None,
        peeled_type=None,
        symbolic_target=None,
    )
    return replace(
        snapshot,
        semantic_refs=tuple((*snapshot.semantic_refs, source_ref)),
    )


def enumerate_frozen_semantic_commits(
    repo: str | Path, snapshot: SnapshotManifest
) -> tuple[str, ...]:
    """Enumerate commits from frozen semantic refs and source HEAD only."""
    frozen = _snapshot_for_git_collection(snapshot)
    revisions = {
        value
        for ref in frozen.semantic_refs
        for value in (ref.object_sha, ref.peeled_sha)
        if value is not None
    }
    revisions.add(snapshot.source_snapshot_head)
    stdin = b"".join(value.encode("ascii") + b"\n" for value in sorted(revisions))
    try:
        output = subprocess.run(
            ("git", "rev-list", "--stdin", "--topo-order"),
            cwd=Path(repo),
            input=stdin,
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError, UnicodeEncodeError) as error:
        raise CoverageError("frozen semantic Git universe is unavailable") from error
    commits: list[str] = []
    for raw in output.splitlines():
        try:
            sha = raw.decode("ascii")
        except UnicodeDecodeError as error:
            raise CoverageError("frozen semantic Git commit ID is not ASCII") from error
        if len(sha) != 40 or any(value not in "0123456789abcdef" for value in sha):
            raise CoverageError(f"invalid frozen semantic Git commit ID: {sha}")
        commits.append(sha)
    if len(commits) != len(set(commits)):
        raise CoverageError("duplicate frozen semantic Git commit ID")
    excluded = set(snapshot.excluded_workflow_commit_shas_at_capture)
    leaked = excluded.intersection(commits)
    if leaked:
        raise CoverageError(
            f"excluded workflow commit entered semantic universe: {sorted(leaked)[0]}"
        )
    return tuple(commits)


def frozen_commit_parents(
    repo: str | Path, commit_shas: Iterable[str]
) -> dict[str, tuple[str, ...]]:
    """Read ordered parent truth directly from each frozen commit object."""
    parents_by_sha: dict[str, tuple[str, ...]] = {}
    for sha in commit_shas:
        try:
            raw = subprocess.run(
                ("git", "cat-file", "commit", sha),
                cwd=Path(repo),
                check=True,
                capture_output=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError) as error:
            raise CoverageError(f"frozen commit object is unavailable: {sha}") from error
        header, separator, _ = raw.partition(b"\n\n")
        if not separator:
            raise CoverageError(f"frozen commit object has no header boundary: {sha}")
        parents: list[str] = []
        for line in header.splitlines():
            if not line.startswith(b"parent "):
                continue
            try:
                parent = line.removeprefix(b"parent ").decode("ascii")
            except UnicodeDecodeError as error:
                raise CoverageError(f"frozen commit parent is not ASCII: {sha}") from error
            if len(parent) != 40 or any(value not in "0123456789abcdef" for value in parent):
                raise CoverageError(f"invalid frozen commit parent ID: {sha}|{parent}")
            parents.append(parent)
        parents_by_sha[sha] = tuple(parents)
    return parents_by_sha


def _reject_external_originals(
    repo: Path,
    snapshot: SnapshotManifest,
    sources: tuple[SourceRecord, ...],
    archive_paths: tuple[Path, ...],
    staged_output_paths: tuple[str | Path, ...],
) -> None:
    root = repo.resolve(strict=True)
    locked_paths = {
        Path(item.path).as_posix().lstrip("./"): item
        for item in snapshot.external_input_files
    }
    locked_basenames = {Path(path).name for path in locked_paths}
    locked_hashes = {item.sha256: item.path for item in snapshot.external_input_files}

    def reject_name(label: str) -> None:
        member = label.split("#", 1)[-1].replace("\\", "/").lstrip("./")
        if member in locked_paths or Path(member).name in locked_basenames:
            raise CoverageError(f"external original path/basename is forbidden: {label}")

    for value in staged_output_paths:
        reject_name(Path(value).as_posix())
    for path in archive_paths:
        reject_name(path.as_posix())
        candidate = path if path.is_absolute() else root / path
        if candidate.is_file() and tarfile.is_tarfile(candidate):
            entries, _ = _archive_entries(candidate)
            for locator, stored in entries.items():
                reject_name(locator)
                digest = _sha256(stored)
                if digest in locked_hashes:
                    raise CoverageError(
                        f"external original identity hash is forbidden: {locked_hashes[digest]}"
                    )
    for source in sources:
        if source.raw_archive_locator:
            reject_name(source.raw_archive_locator)
        for member in source.stored_members:
            reject_name(member.locator)
            if member.sha256 in locked_hashes:
                raise CoverageError(
                    f"external original identity hash is forbidden: {locked_hashes[member.sha256]}"
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
) -> dict[str, bytes]:
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
    return reconstructed


def verify_document_claim_archive_binding(
    sources: Iterable[SourceRecord],
    claims: Iterable[DocumentClaim],
    reconstructed: Mapping[str, bytes],
) -> None:
    """Bind every document claim to its exact archived safe representation."""
    frozen_claims = tuple(claims)
    for source in sources:
        if source.source_type not in {
            "tracked-document",
            "external-pdf-derived-record",
        }:
            continue
        stored = reconstructed.get(source.source_id)
        if stored is None:
            raise CoverageError(
                f"archived document representation missing stable ID: {source.source_id}"
            )
        try:
            representation = json.loads(stored)
        except json.JSONDecodeError as error:
            raise CoverageError(
                f"archived document representation is invalid: {source.source_id}"
            ) from error
        if not isinstance(representation, dict):
            raise CoverageError(
                f"archived document representation is invalid: {source.source_id}"
            )
        archived_source = representation.get("source")
        archived_claims = representation.get("claims")
        if (
            not isinstance(archived_source, dict)
            or archived_source.get("source_id") != source.source_id
            or not isinstance(archived_claims, list)
            or not all(isinstance(claim, dict) for claim in archived_claims)
        ):
            raise CoverageError(
                f"archived document representation is invalid: {source.source_id}"
            )
        archived_by_id = {
            claim.get("claim_id"): claim for claim in archived_claims
        }
        if (
            None in archived_by_id
            or len(archived_by_id) != len(archived_claims)
        ):
            raise CoverageError(
                f"archived document claim IDs are invalid: {source.source_id}"
            )
        captured = tuple(
            claim
            for claim in frozen_claims
            if claim.document_source_id == source.source_id
        )
        captured_by_id = {claim.claim_id: claim.to_dict() for claim in captured}
        if len(captured_by_id) != len(captured):
            raise CoverageError(
                f"captured document claim IDs are duplicated: {source.source_id}"
            )
        missing = sorted(set(archived_by_id) - set(captured_by_id), key=_utf8)
        extra = sorted(set(captured_by_id) - set(archived_by_id), key=_utf8)
        if missing:
            raise CoverageError(
                f"archived document claim union missing stable ID: {missing[0]}"
            )
        if extra:
            raise CoverageError(
                f"archived document claim union has unexpected stable ID: {extra[0]}"
            )
        for claim_id in sorted(archived_by_id, key=_utf8):
            if archived_by_id[claim_id] != captured_by_id[claim_id]:
                raise CoverageError(
                    f"archived document claim identity mismatch: {claim_id}"
                )


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
    sources: tuple[SourceRecord, ...],
    semantic_commit_shas: tuple[str, ...],
    expected_parent_shas: Mapping[str, tuple[str, ...]],
) -> tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...], tuple[str, ...]]:
    expected_commits = tuple(f"GIT-{sha}" for sha in semantic_commit_shas)
    captured_commits = tuple(
        source.source_id for source in sources if source.source_type == "git-commit"
    )
    _stable_set("Git commits", expected_commits, captured_commits)
    expected_diffs: list[str] = []
    by_id = {source.source_id: source for source in sources}
    for sha in semantic_commit_shas:
        source = by_id[f"GIT-{sha}"]
        parents = expected_parent_shas.get(sha)
        if parents is None:
            raise CoverageError(f"frozen commit parent truth is absent: GIT-{sha}")
        recorded_parents = source.payload.get("parent_shas")
        if (
            source.payload.get("commit_sha") != sha
            or not isinstance(recorded_parents, list)
            or tuple(recorded_parents) != parents
        ):
            raise CoverageError(f"frozen commit parent metadata mismatch: GIT-{sha}")
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
    for diff_id in expected_diffs:
        diff = by_id[diff_id]
        child = diff_id.removeprefix("GIT-").rsplit("-", 1)[0]
        parents = expected_parent_shas[child]
        expected_total = len(parents)
        if diff.payload.get("parent_total") != expected_total:
            raise CoverageError(f"Git diff parent count mismatch: {diff_id}")
        if expected_total == 0:
            expected_ordinal = 1
            expected_parent = None
            expected_base = EMPTY_TREE_SHA
        else:
            suffix = diff_id.rsplit("-P", 1)[-1]
            expected_ordinal = int(suffix)
            expected_parent = parents[expected_ordinal - 1]
            expected_base = expected_parent
        identity = (
            diff.payload.get("child_sha"),
            diff.payload.get("parent_ordinal"),
            diff.payload.get("parent_sha"),
            diff.payload.get("comparison_base_sha"),
        )
        expected_identity = (
            child,
            expected_ordinal,
            expected_parent,
            expected_base,
        )
        if identity != expected_identity:
            raise CoverageError(f"Git diff parent identity mismatch: {diff_id}")
    return (
        expected_commits,
        captured_commits,
        tuple(expected_diffs),
        captured_diffs,
    )


def _fingerprint_child(
    fingerprint: GitHubEndpointFingerprint,
    sources: tuple[SourceRecord, ...],
    metadata_tokens: tuple[str, ...] | None = None,
    *,
    endpoint_fingerprints: tuple[GitHubEndpointFingerprint, ...] = (),
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
    if metadata_tokens is not None:
        _stable_set(
            f"GitHub fingerprint metadata {fingerprint.item_key}",
            metadata_tokens,
            fingerprint.stable_child_ids,
        )
        return tuple(
            f"{fingerprint.item_key}|{token}" for token in metadata_tokens
        )
    if fingerprint.availability_status == "confirmed-unavailable":
        if pages != (1,) or len(fingerprint.stable_child_ids) != 1:
            raise CoverageError(
                f"GitHub unavailable fingerprint metadata mismatch: {fingerprint.item_key}|{fingerprint.endpoint_key}"
            )
        token = fingerprint.stable_child_ids[0]
        prefix, separator, raw_status = token.partition(":")
        if prefix != "status-code" or not separator or not raw_status.isdigit():
            raise CoverageError(
                f"GitHub unavailable fingerprint metadata mismatch: {fingerprint.item_key}|{token}"
            )
        exact_patch_406 = is_exact_patch_variant(
            fingerprint.endpoint_key, {}, pages[0], fingerprint.accept
        ) and fingerprint.request_params_sha256 == _sha256(_canonical_json({}))
        if raw_status == "406" and not exact_patch_406:
            raise CoverageError(
                f"GitHub 406 availability is not a patch variant: {fingerprint.item_key}|{fingerprint.endpoint_key}"
            )
        locator = f"github:{fingerprint.endpoint_key}"
        matches = [
            source
            for source in sources
            if source.source_type == "github-availability"
            and source.source_locator == locator
        ]
        if len(matches) != 1:
            raise CoverageError(
                f"GitHub availability record missing stable ID: {fingerprint.item_key}|{token}"
            )
        record = matches[0]
        expected_source_id = "GH-AVAIL-" + _sha256(
            f"{fingerprint.item_key}:{fingerprint.endpoint_key}".encode()
        )[:24]
        if record.source_id != expected_source_id:
            raise CoverageError(
                f"GitHub availability record stable ID mismatch: {record.source_id}"
            )
        payload = record.payload
        params = payload.get("request_params")
        params_hash = (
            _sha256(_canonical_json(dict(params)))
            if isinstance(params, Mapping)
            else None
        )
        expected_hash = fingerprint.page_response_hashes[0]
        if (
            record.availability_status != "confirmed-unavailable"
            or payload.get("availability_status") != "confirmed-unavailable"
            or payload.get("endpoint") != fingerprint.endpoint_key
            or payload.get("accept") != fingerprint.accept
            or payload.get("status_code") != int(raw_status)
            or params_hash != fingerprint.request_params_sha256
        ):
            raise CoverageError(
                f"GitHub availability metadata mismatch: {record.source_id}"
            )
        if (
            record.raw_hash != expected_hash
            or payload.get("observed_body_sha256") != expected_hash
            or payload.get("response_raw_sha256") != expected_hash
        ):
            raise CoverageError(
                f"GitHub availability body hash mismatch: {record.source_id}"
            )
        return (f"{fingerprint.item_key}|{token}",)
    gap_tokens = tuple(
        value
        for value in fingerprint.stable_child_ids
        if value.startswith("count-gap:")
    )
    if len(gap_tokens) > 1:
        raise CoverageError(
            f"GitHub count gap fingerprint mismatch: {fingerprint.item_key}|{fingerprint.endpoint_key}"
        )
    gap_captured: tuple[str, ...] = ()
    if gap_tokens:
        gap_captured = _verify_count_gap(
            fingerprint,
            sources,
            gap_tokens[0],
            endpoint_fingerprints,
        )
    captured: list[str] = []
    for stable_id in fingerprint.stable_child_ids:
        if stable_id.startswith("count-gap:"):
            continue
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
    return tuple(captured) + gap_captured


def _verify_count_gap(
    fingerprint: GitHubEndpointFingerprint,
    sources: tuple[SourceRecord, ...],
    token: str,
    endpoint_fingerprints: tuple[GitHubEndpointFingerprint, ...],
) -> tuple[str, ...]:
    locator = f"github:{fingerprint.endpoint_key}#{token}"
    matches = [
        source
        for source in sources
        if source.source_type == "github-count-gap"
        and source.source_locator == locator
    ]
    if len(matches) != 1:
        raise CoverageError(
            f"GitHub count gap record missing stable ID: {fingerprint.item_key}|{token}"
        )
    record = matches[0]
    expected_source_id = "GH-COUNT-GAP-" + _sha256(
        f"{fingerprint.item_key}:{fingerprint.endpoint_key}".encode()
    )[:24]
    if record.source_id != expected_source_id:
        raise CoverageError(
            f"GitHub count gap record stable ID mismatch: {record.source_id}"
        )
    reason = "parent-reported count exceeds accessible endpoint enumeration"
    if (
        record.classification != "record-only"
        or record.availability_status != "confirmed-unavailable"
        or record.record_only_reason != reason
    ):
        raise CoverageError(f"GitHub count gap record-only mismatch: {record.source_id}")
    payload = record.payload
    allowed_keys = {
        "contract",
        "item_key",
        "endpoint",
        "endpoint_kind",
        "expected_count",
        "observed_count",
        "missing_count",
        "parent_detail_response_sha256",
        "parent_updated_at",
        "child_accept",
        "child_request_params",
        "child_request_params_sha256",
        "child_page_numbers",
        "child_page_response_sha256",
        "child_stable_ids",
        "parent_detail_fetched_at",
        "child_page_fetched_at",
        "evidence_chain_sha256",
        "gap_token",
        "reason",
        "fetched_at",
        "captured_updated_at",
        "response_raw_sha256",
    }
    if set(payload) != allowed_keys:
        raise CoverageError(f"GitHub count gap safe metadata mismatch: {record.source_id}")
    actual_child_ids = tuple(
        sorted(
            value
            for value in fingerprint.stable_child_ids
            if not value.startswith("count-gap:")
        )
    )
    expected_count = payload.get("expected_count")
    observed_count = payload.get("observed_count")
    missing_count = payload.get("missing_count")
    valid_counts = all(
        isinstance(value, int) and not isinstance(value, bool) and value >= 0
        for value in (expected_count, observed_count, missing_count)
    )
    if (
        not valid_counts
        or observed_count != len(actual_child_ids)
        or expected_count <= observed_count
        or observed_count + missing_count != expected_count
    ):
        raise CoverageError(f"GitHub count gap arithmetic mismatch: {record.source_id}")
    params = payload.get("child_request_params")
    page_numbers = payload.get("child_page_numbers")
    page_hashes = payload.get("child_page_response_sha256")
    child_ids = payload.get("child_stable_ids")
    child_fetched = payload.get("child_page_fetched_at")
    if (
        payload.get("contract") != "github-count-gap-v1"
        or payload.get("item_key") != fingerprint.item_key
        or payload.get("endpoint") != fingerprint.endpoint_key
        or payload.get("child_accept") != fingerprint.accept
        or not isinstance(params, Mapping)
        or _sha256(_canonical_json(dict(params))) != fingerprint.request_params_sha256
        or payload.get("child_request_params_sha256") != fingerprint.request_params_sha256
        or page_numbers != list(fingerprint.page_numbers)
        or page_hashes != list(fingerprint.page_response_hashes)
        or child_ids != list(actual_child_ids)
        or not isinstance(child_fetched, list)
        or not child_fetched
        or len(child_fetched) != len(fingerprint.page_numbers)
        or not all(isinstance(value, str) for value in child_fetched)
    ):
        raise CoverageError(f"GitHub count gap endpoint fingerprint mismatch: {record.source_id}")
    endpoint_kind = payload.get("endpoint_kind")
    count_field = {
        "commits": "commits",
        "files": "changed_files",
        "review-comments": "review_comments",
        "conversation-comments": "comments",
    }.get(endpoint_kind)
    item_kind, separator, raw_number = fingerprint.item_key.partition(":")
    if count_field is None or not separator or item_kind not in {"pull", "pr", "issue"} or not raw_number.isdigit():
        raise CoverageError(f"GitHub count gap parent locator mismatch: {record.source_id}")
    prefix = "GH-PR" if item_kind in {"pull", "pr"} else "GH-ISSUE"
    parent_matches = [source for source in sources if source.source_id == f"{prefix}-{raw_number}"]
    if len(parent_matches) != 1:
        raise CoverageError(f"GitHub count gap parent missing stable ID: {record.source_id}")
    parent = parent_matches[0]
    parent_value = parent.payload.get("value")
    parent_hash = payload.get("parent_detail_response_sha256")
    parent_updated_at = payload.get("parent_updated_at")
    if (
        not isinstance(parent_value, Mapping)
        or parent_value.get(count_field) != expected_count
        or parent_value.get("updated_at") != parent_updated_at
        or parent.payload.get("captured_updated_at") != parent_updated_at
    ):
        raise CoverageError(f"GitHub count gap parent count mismatch: {record.source_id}")
    if (
        parent.raw_hash != parent_hash
        or parent.payload.get("endpoint_response_raw_sha256") != parent_hash
        or parent.payload.get("response_raw_sha256") != parent_hash
    ):
        raise CoverageError(f"GitHub count gap parent detail hash mismatch: {record.source_id}")
    endpoint_parts = fingerprint.endpoint_key.split("/")
    if len(endpoint_parts) < 5 or endpoint_parts[1] != "repos":
        raise CoverageError(f"GitHub count gap detail fingerprint mismatch: {record.source_id}")
    repository_base = "/".join(endpoint_parts[:4])
    detail_kind = "pulls" if item_kind in {"pull", "pr"} else "issues"
    detail_endpoint = f"{repository_base}/{detail_kind}/{raw_number}"
    detail_matches = [
        candidate
        for candidate in endpoint_fingerprints
        if candidate.item_key == fingerprint.item_key
        and candidate.endpoint_key == detail_endpoint
    ]
    empty_params_hash = _sha256(_canonical_json({}))
    if len(detail_matches) != 1:
        raise CoverageError(f"GitHub count gap detail fingerprint mismatch: {record.source_id}")
    detail_fingerprint = detail_matches[0]
    if (
        detail_fingerprint.availability_status != "available"
        or detail_fingerprint.request_params_sha256 != empty_params_hash
        or detail_fingerprint.accept != "application/vnd.github+json"
        or detail_fingerprint.page_numbers != (1,)
        or detail_fingerprint.page_response_hashes != (parent_hash,)
        or detail_fingerprint.stable_child_ids
    ):
        raise CoverageError(f"GitHub count gap detail fingerprint mismatch: {record.source_id}")
    stable_evidence = {
        "contract": "github-count-gap-v1",
        "item_key": fingerprint.item_key,
        "endpoint": fingerprint.endpoint_key,
        "endpoint_kind": endpoint_kind,
        "expected_count": expected_count,
        "observed_count": observed_count,
        "missing_count": missing_count,
        "parent_detail_response_sha256": parent_hash,
        "parent_updated_at": parent_updated_at,
        "child_accept": fingerprint.accept,
        "child_request_params": dict(sorted(params.items())),
        "child_request_params_sha256": fingerprint.request_params_sha256,
        "child_page_numbers": list(fingerprint.page_numbers),
        "child_page_response_sha256": list(fingerprint.page_response_hashes),
        "child_stable_ids": list(actual_child_ids),
    }
    expected_token = "count-gap:" + _sha256(_canonical_json(stable_evidence))
    if token != expected_token or payload.get("gap_token") != expected_token:
        raise CoverageError(f"GitHub count gap token mismatch: {record.source_id}")
    parent_fetched = payload.get("parent_detail_fetched_at")
    if not isinstance(parent_fetched, str):
        raise CoverageError(f"GitHub count gap observation time mismatch: {record.source_id}")
    evidence_chain = {
        **stable_evidence,
        "parent_detail_fetched_at": parent_fetched,
        "child_page_fetched_at": child_fetched,
    }
    evidence_hash = _sha256(_canonical_json(evidence_chain))
    safe_payload = {
        **evidence_chain,
        "evidence_chain_sha256": evidence_hash,
        "gap_token": expected_token,
        "reason": reason,
    }
    if (
        payload.get("evidence_chain_sha256") != evidence_hash
        or payload.get("response_raw_sha256") != evidence_hash
        or record.raw_hash != evidence_hash
        or record.stored_hash != _sha256(_canonical_json(safe_payload))
        or payload.get("reason") != reason
        or payload.get("fetched_at") != child_fetched[-1]
        or payload.get("captured_updated_at") != parent_updated_at
    ):
        raise CoverageError(f"GitHub count gap evidence hash mismatch: {record.source_id}")
    return (f"{fingerprint.item_key}|{token}",)


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
    captured_parent_ids = [
        source.source_id
        for source in sources
        if source.source_type in {"github-pull-request", "github-issue"}
    ]
    terminal_detail_items: set[str] = set()
    for item_key, number, suffix in (
        *(
            (f"pull:{number}", number, f"/pulls/{number}")
            for number in window.pull_request_numbers
        ),
        *(
            (f"issue:{number}", number, f"/issues/{number}")
            for number in window.issue_numbers
        ),
    ):
        terminal = [
            fingerprint
            for fingerprint in window.endpoint_fingerprints
            if fingerprint.item_key == item_key
            and fingerprint.endpoint_key.endswith(suffix)
            and fingerprint.availability_status == "confirmed-unavailable"
        ]
        if len(terminal) == 1:
            terminal_detail_items.add(item_key)
            prefix = "GH-PR" if item_key.startswith("pull:") else "GH-ISSUE"
            captured_parent_ids.append(f"{prefix}-{number}")
    captured_parents = tuple(captured_parent_ids)
    _stable_set("GitHub parents", expected_parents, captured_parents)
    expected_updates = tuple(
        [f"pull:{number}" for number in window.pull_request_numbers]
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
        metadata_tokens: tuple[str, ...] | None = None
        if fingerprint.item_key == "pull:enumeration":
            metadata_tokens = tuple(
                f"pull:{number}" for number in window.pull_request_numbers
            )
        elif fingerprint.item_key == "issue:enumeration":
            metadata_tokens = tuple(
                f"issue:{number}" for number in window.issue_numbers
            )
        children.extend(
            _fingerprint_child(
                fingerprint,
                sources,
                metadata_tokens,
                endpoint_fingerprints=window.endpoint_fingerprints,
            )
        )
    if strict_endpoints:
        expected_endpoint_suffixes: list[tuple[str, str]] = [
            ("pull:enumeration", "/pulls"),
            ("issue:enumeration", "/issues"),
        ]
        for number in window.pull_request_numbers:
            item_key = f"pull:{number}"
            expected_endpoint_suffixes.append((item_key, f"/pulls/{number}"))
            if item_key not in terminal_detail_items:
                expected_endpoint_suffixes.extend(
                    (
                        (f"pull:{number}", f"/pulls/{number}/commits"),
                        (f"pull:{number}", f"/pulls/{number}/files"),
                        (f"pull:{number}", f"/pulls/{number}/reviews"),
                        (f"pull:{number}", f"/pulls/{number}/comments"),
                        (f"pull:{number}", f"/issues/{number}/comments"),
                        (f"pull:{number}", f"/issues/{number}/timeline"),
                        (f"pull:{number}", f"/issues/{number}/reactions"),
                        (
                            f"pull:{number}",
                            f"/pulls/{number}/requested_reviewers",
                        ),
                        (f"pull:{number}", f"/pulls/{number}.patch"),
                    )
                )
        for number in window.issue_numbers:
            item_key = f"issue:{number}"
            expected_endpoint_suffixes.append((item_key, f"/issues/{number}"))
            if item_key not in terminal_detail_items:
                expected_endpoint_suffixes.extend(
                    (
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
            "github-count-gap",
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
    captured_ref_ids: Iterable[str],
    archive_paths: Iterable[str | Path],
    staged_output_paths: Iterable[str | Path],
    require_archive_members: bool = True,
    strict_github_endpoints: bool = True,
    expected_semantic_commit_shas: Iterable[str] | None = None,
    locked_coverage: Mapping[str, object] | None = None,
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
    expected_refs = capture_ref_ids(snapshot)
    captured_refs = tuple(captured_ref_ids)
    _stable_set("Git refs", expected_refs, captured_refs)
    semantic = (
        tuple(expected_semantic_commit_shas)
        if expected_semantic_commit_shas is not None
        else enumerate_frozen_semantic_commits(repository, snapshot)
    )
    if expected_semantic_commit_shas is None:
        parent_truth = frozen_commit_parents(repository, semantic)
    else:
        parent_truth = {
            str(source.payload.get("commit_sha")): tuple(
                source.payload.get("parent_shas", ())
            )
            for source in frozen_sources
            if source.source_type == "git-commit"
            and isinstance(source.payload.get("parent_shas"), list)
        }
    git_expected, git_captured, diff_expected, diff_captured = _verify_git(
        frozen_sources, semantic, parent_truth
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
        reconstructed = verify_archive_members(frozen_sources, paths)
        verify_document_claim_archive_binding(
            frozen_sources, frozen_claims, reconstructed
        )
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
    if locked_coverage is not None:
        if (
            locked_coverage.get("phase") != "capture"
            or locked_coverage.get("snapshot_id") != snapshot.snapshot_id
        ):
            raise CoverageError("locked capture coverage identity mismatch")
        locked_sections = locked_coverage.get("sections")
        if not isinstance(locked_sections, Mapping):
            raise CoverageError("locked capture coverage sections are absent")
        for key in ("refs", "git_commits"):
            locked = locked_sections.get(key)
            if not isinstance(locked, Mapping):
                raise CoverageError(f"locked capture coverage section is absent: {key}")
            current = sections[key].to_dict()
            for field in ("expected_count", "expected_ids_sha256"):
                if locked.get(field) != current[field]:
                    raise CoverageError(
                        f"locked capture expected universe mismatch: {key}.{field}"
                    )
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


def _is_pr_inventory_source(value: SourceRecord) -> bool:
    return value.source_type.startswith("github-") and (
        "/pull" in value.source_locator or value.source_id.startswith("GH-PR-")
    )


def _is_issue_inventory_source(value: SourceRecord) -> bool:
    return (
        value.source_type.startswith("github-")
        and "/issues/" in value.source_locator
    )


def _is_ai_inventory_source(value: SourceRecord) -> bool:
    return value.source_type.startswith("ai-trace-")


def _specialized_inventory(
    output_dir: Path,
    name: str,
    sources: Iterable[SourceRecord],
    predicate: Callable[[SourceRecord], bool],
    *,
    claim_universe: Iterable[DocumentClaim],
) -> JsonlArtifactDescriptor:
    frozen_sources = tuple(sources)
    frozen_claims = tuple(claim_universe)
    return write_jsonl(
        output_dir / name,
        tuple(source for source in frozen_sources if predicate(source)),
        model_type=SourceRecord,
        source_universe=frozen_sources,
        claim_universe=frozen_claims,
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
    git_capture = collect_git_evidence(
        repository, _snapshot_for_git_collection(snapshot)
    )
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
        captured_ref_ids=capture_ref_ids(snapshot),
        archive_paths=archive_paths,
        staged_output_paths=staged_output_paths,
    )

    # Finalization happens only after every collector and reconciliation gate
    # succeeds.  Canonical ledgers are atomically replaced before the immutable
    # snapshot is marked final.
    descriptors = [
        write_jsonl(
            output / SOURCE_NAME,
            sources,
            model_type=SourceRecord,
            claim_universe=claims,
        ),
        write_jsonl(
            output / CLAIM_NAME,
            claims,
            model_type=DocumentClaim,
            source_universe=sources,
            claim_universe=claims,
        ),
        _specialized_inventory(
            output,
            PR_INVENTORY_NAME,
            sources,
            _is_pr_inventory_source,
            claim_universe=claims,
        ),
        _specialized_inventory(
            output,
            ISSUE_INVENTORY_NAME,
            sources,
            _is_issue_inventory_source,
            claim_universe=claims,
        ),
        _specialized_inventory(
            output,
            AI_INVENTORY_NAME,
            sources,
            _is_ai_inventory_source,
            claim_universe=claims,
        ),
    ]
    coverage = replace(
        coverage,
        ledger_artifacts=tuple(
            sorted(descriptors, key=lambda item: _utf8(item.logical_path))
        ),
    )
    _write_commit_csv(output / "commit_inventory.csv", sources)
    write_coverage_manifest(output, coverage)
    write_snapshot_manifest(snapshot_path, final_snapshot)
    return CaptureArtifacts(final_snapshot, sources, tuple(claims), coverage, archive_paths)


def _locked_ledger_artifacts(
    locked_coverage: Mapping[str, object],
) -> tuple[JsonlArtifactDescriptor, ...]:
    raw_artifacts = locked_coverage.get("ledger_artifacts")
    if not isinstance(raw_artifacts, list):
        raise CoverageError("locked capture ledger artifacts are absent")
    try:
        artifacts = tuple(
            JsonlArtifactDescriptor.from_dict(item) for item in raw_artifacts
        )
    except (KeyError, TypeError, ValueError) as error:
        raise CoverageError("locked capture ledger artifact is malformed") from error
    names = tuple(item.logical_path for item in artifacts)
    expected_names = tuple(name for name, _model_type in CAPTURE_LEDGER_SPECS)
    if names != expected_names or len(set(names)) != len(expected_names):
        raise CoverageError("locked capture ledger artifact names mismatch")
    return artifacts


def _verify_staged_capture_scope(
    *,
    repo: Path,
    output_dir: Path,
    staged_output_paths: tuple[str | Path, ...],
    ledger_artifacts: tuple[JsonlArtifactDescriptor, ...],
    archive_paths: tuple[Path, ...],
) -> None:
    if not staged_output_paths:
        return
    repository = repo.resolve(strict=True)
    capture_root = output_dir.resolve(strict=True)
    try:
        capture_root.relative_to(repository)
    except ValueError as error:
        raise CoverageError("capture output is outside repository") from error

    def lexical(path: Path) -> Path:
        return Path(os.path.abspath(path))

    def repository_key(path: Path) -> str:
        try:
            return lexical(path).relative_to(repository).as_posix()
        except ValueError as error:
            raise CoverageError(f"staged path is outside repository: {path}") from error

    def verify_confinement(path: Path) -> Path:
        try:
            resolved = path.resolve(strict=True)
        except OSError as error:
            raise CoverageError(f"staged path is unavailable: {path}") from error
        try:
            resolved.relative_to(repository)
        except ValueError as error:
            raise CoverageError(f"staged path is outside repository: {path}") from error
        return resolved

    required_paths: list[str] = []
    for descriptor in ledger_artifacts:
        for physical in descriptor.physical_paths:
            relative = Path(physical)
            if relative.is_absolute() or relative.parent != Path("."):
                raise CoverageError(
                    f"capture ledger physical path is not a basename: {physical}"
                )
            required_path = lexical(capture_root / relative)
            verify_confinement(required_path)
            required_paths.append(repository_key(required_path))
    for name in (
        SNAPSHOT_NAME,
        "commit_inventory.csv",
        COVERAGE_JSON_NAME,
        COVERAGE_MARKDOWN_NAME,
    ):
        required_path = lexical(capture_root / name)
        verify_confinement(required_path)
        required_paths.append(repository_key(required_path))
    for path in archive_paths:
        required_path = lexical(path)
        verify_confinement(required_path)
        required_paths.append(repository_key(required_path))
    required = set(required_paths)

    staged: list[str] = []
    for raw in staged_output_paths:
        supplied = Path(raw)
        candidate = supplied if supplied.is_absolute() else repository / supplied
        staged_path = lexical(candidate)
        staged_key = repository_key(staged_path)
        relative = Path(staged_key)
        if relative.name == ".gitignore":
            raise CoverageError(f"staged .gitignore is forbidden: {staged_key}")
        if ".github-checkpoints" in relative.parts:
            raise CoverageError(
                f".github-checkpoints member is forbidden: {staged_key}"
            )
        if relative.suffix.lower() == ".pdf":
            raise CoverageError(
                f"external original path/basename is forbidden: {staged_key}"
            )
        member = repository
        for index, part in enumerate(relative.parts):
            member /= part
            if not member.is_symlink():
                continue
            if index == len(relative.parts) - 1:
                raise CoverageError(f"staged symlink is forbidden: {staged_key}")
            raise CoverageError(
                f"staged symlinked parent is forbidden: {staged_key}"
            )
        verify_confinement(staged_path)
        if staged_path.stat().st_size >= 95_000_000:
            raise CoverageError(f"staged Git blob limit: {staged_key}")
        staged.append(staged_key)

    if len(staged) != len(set(staged)):
        raise CoverageError("duplicate staged capture path")
    staged_set = set(staged)
    missing = sorted(required - staged_set, key=_utf8)
    if missing:
        raise CoverageError(f"required staged artifact is absent: {missing[0]}")
    extras = sorted(staged_set - required, key=_utf8)
    if extras:
        extra = extras[0]
        capture_key = repository_key(capture_root)
        under_capture = extra == capture_key or extra.startswith(capture_key + "/")
        if under_capture and re.fullmatch(
            r".+-part-[0-9]{3}\.jsonl\.gz", Path(extra).name
        ):
            raise CoverageError(f"unindexed shard: {extra}")
        if under_capture:
            raise CoverageError(f"staged capture artifact is unowned: {extra}")
        raise CoverageError(f"unexpected staged path: {extra}")


def verify_capture_files(
    *,
    repo: str | Path,
    snapshot_path: str | Path,
    output_dir: str | Path,
    staged_output_paths: Iterable[str | Path] = (),
) -> CaptureCoverageManifest:
    """Reload and re-verify an already finalized source capture."""
    output = Path(output_dir)
    staged = tuple(staged_output_paths)
    payload = json.loads(Path(snapshot_path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("snapshot manifest must be a JSON object")
    snapshot = SnapshotManifest.from_dict(payload)
    claim_values, claim_descriptor = read_jsonl_with_descriptor(
        output / CLAIM_NAME,
        DocumentClaim,
    )
    claims = tuple(claim_values)
    source_values, source_descriptor = read_jsonl_with_descriptor(
        output / SOURCE_NAME,
        SourceRecord,
        claim_universe=claims,
    )
    sources = tuple(source_values)
    coverage_path = output / COVERAGE_JSON_NAME
    try:
        locked_coverage = json.loads(coverage_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CoverageError("locked capture coverage manifest is unreadable") from error
    if not isinstance(locked_coverage, dict):
        raise CoverageError("locked capture coverage manifest must be an object")
    locked_artifacts = _locked_ledger_artifacts(locked_coverage)
    current_descriptors: list[JsonlArtifactDescriptor] = [
        source_descriptor,
        claim_descriptor,
    ]
    specialized = (
        (PR_INVENTORY_NAME, _is_pr_inventory_source),
        (ISSUE_INVENTORY_NAME, _is_issue_inventory_source),
        (AI_INVENTORY_NAME, _is_ai_inventory_source),
    )
    for name, predicate in specialized:
        values, descriptor = read_jsonl_with_descriptor(
            output / name,
            SourceRecord,
            source_universe=sources,
            claim_universe=claims,
        )
        expected = [source for source in sources if predicate(source)]
        if values != expected:
            raise CoverageError(f"specialized inventory mismatch: {name}")
        current_descriptors.append(descriptor)
    current_artifacts = tuple(
        sorted(current_descriptors, key=lambda item: _utf8(item.logical_path))
    )
    if tuple(item.to_dict() for item in locked_artifacts) != tuple(
        item.to_dict() for item in current_artifacts
    ):
        raise CoverageError("locked capture ledger artifact mismatch")
    archives = tuple(sorted(output.glob("*.tar.gz"), key=lambda path: _utf8(path.name)))
    recomputed = verify_source_capture(
        repo=repo,
        snapshot=snapshot,
        sources=sources,
        claims=claims,
        captured_ref_ids=capture_ref_ids(snapshot),
        archive_paths=archives,
        staged_output_paths=staged,
        locked_coverage=locked_coverage,
    )
    _verify_staged_capture_scope(
        repo=Path(repo),
        output_dir=output,
        staged_output_paths=staged,
        ledger_artifacts=current_artifacts,
        archive_paths=archives,
    )
    return replace(recomputed, ledger_artifacts=current_artifacts)
