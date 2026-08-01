"""Exhaustive Git commit and per-parent diff capture from a frozen snapshot."""

from __future__ import annotations

import hashlib
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Sequence

from .archive import ArchiveVolume, PatchEntry, write_patch_volumes
from .models import SnapshotManifest, SourceRecord, StoredArtifactMember
from .redaction import RedactionResult, redact_binary_patch, redact_text


EMPTY_TREE_SHA = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
_FULL_INDEX = re.compile(rb"^index ([0-9a-f]{40})\.\.([0-9a-f]{40})", re.MULTILINE)


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


class _Git:
    def __init__(self, repo: Path):
        self.repo = repo.resolve(strict=True)

    def run(self, args: Sequence[str], input_bytes: bytes | None = None) -> bytes:
        return subprocess.run(
            list(args),
            cwd=self.repo,
            input=input_bytes,
            check=True,
            capture_output=True,
        ).stdout


@dataclass(frozen=True, slots=True)
class GitCapture:
    records: tuple[SourceRecord, ...]
    commit_records: tuple[SourceRecord, ...]
    diff_records: tuple[SourceRecord, ...]
    archive_volumes: tuple[ArchiveVolume, ...]
    semantic_commit_shas: tuple[str, ...]
    excluded_workflow_commit_shas_at_capture: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class _CommitMetadata:
    sha: str
    raw: bytes
    tree: str
    parents: tuple[str, ...]
    author: bytes
    committer: bytes
    message: bytes
    signature_status: str


@dataclass(frozen=True, slots=True)
class _DiffEvidence:
    source_id: str
    child_sha: str
    parent_sha: str | None
    parent_ordinal: int
    parent_total: int
    raw_patch: bytes
    stored_patch: bytes
    raw_hash: str
    stored_hash: str
    privacy_redactions: tuple[str, ...]
    file_statuses: tuple[dict[str, object], ...]
    numstat: tuple[dict[str, object], ...]
    contains_binary: bool


def _decode_ascii(value: bytes, label: str) -> str:
    try:
        return value.decode("ascii")
    except UnicodeDecodeError as error:
        raise ValueError(f"non-ASCII {label} in Git object") from error


def _safe_text(value: bytes) -> tuple[str, bool]:
    try:
        return value.decode("utf-8"), True
    except UnicodeDecodeError:
        return value.decode("utf-8", errors="replace"), False


def _path_fields(raw: bytes, field: str = "path") -> dict[str, object]:
    decoded, valid_utf8 = _safe_text(raw)
    return {
        field: decoded if valid_utf8 else None,
        f"{field}_hex": raw.hex(),
        f"{field}_utf8": valid_utf8,
    }


def _parse_headers(raw: bytes) -> tuple[dict[bytes, list[bytes]], bytes]:
    header_bytes, separator, message = raw.partition(b"\n\n")
    if not separator:
        raise ValueError("malformed Git commit object")
    headers: dict[bytes, list[bytes]] = {}
    current_key: bytes | None = None
    for line in header_bytes.split(b"\n"):
        if line.startswith(b" ") and current_key is not None:
            headers[current_key][-1] += b"\n" + line
            continue
        key, space, value = line.partition(b" ")
        if not space:
            raise ValueError("malformed Git commit header")
        headers.setdefault(key, []).append(value)
        current_key = key
    return headers, message


def _commit_metadata(git: _Git, sha: str) -> _CommitMetadata:
    raw = git.run(("git", "cat-file", "commit", sha))
    headers, message = _parse_headers(raw)
    required = (b"tree", b"author", b"committer")
    if any(len(headers.get(key, ())) != 1 for key in required):
        raise ValueError(f"commit metadata fields are incomplete: {sha}")
    parents = tuple(_decode_ascii(value, "parent SHA") for value in headers.get(b"parent", ()))
    return _CommitMetadata(
        sha=sha,
        raw=raw,
        tree=_decode_ascii(headers[b"tree"][0], "tree SHA"),
        parents=parents,
        author=headers[b"author"][0],
        committer=headers[b"committer"][0],
        message=message,
        signature_status="present-unverified" if b"gpgsig" in headers else "unsigned",
    )


def _identity_timestamp(identity: bytes) -> str | None:
    match = re.search(rb" ([0-9]+) ([+-][0-9]{4})\Z", identity)
    if match is None:
        return None
    seconds = int(match.group(1))
    offset_value = match.group(2).decode("ascii")
    sign = 1 if offset_value[0] == "+" else -1
    offset = timedelta(
        hours=sign * int(offset_value[1:3]),
        minutes=sign * int(offset_value[3:5]),
    )
    return datetime.fromtimestamp(seconds, timezone(offset)).isoformat(timespec="seconds")


def _semantic_revision_inputs(snapshot: SnapshotManifest) -> tuple[str, ...]:
    revisions = {snapshot.source_snapshot_head}
    for ref in snapshot.semantic_refs:
        revisions.add(ref.object_sha)
        if ref.peeled_sha is not None:
            revisions.add(ref.peeled_sha)
    return tuple(sorted(revisions))


def _enumerate_commits(git: _Git, snapshot: SnapshotManifest) -> tuple[str, ...]:
    revisions = _semantic_revision_inputs(snapshot)
    stdin = b"".join(revision.encode("ascii") + b"\n" for revision in revisions)
    output = git.run(("git", "rev-list", "--stdin", "--topo-order"), stdin)
    commits = tuple(
        _decode_ascii(line, "commit SHA") for line in output.splitlines() if line
    )
    if len(commits) != len(set(commits)):
        raise ValueError("git rev-list returned duplicate commits")
    excluded = set(snapshot.excluded_workflow_commit_shas_at_capture)
    intersection = excluded.intersection(commits)
    if intersection:
        raise ValueError(
            "excluded workflow commit present in semantic Git source set: "
            + ",".join(sorted(intersection))
        )
    return commits


def _parse_name_status(output: bytes) -> tuple[dict[str, object], ...]:
    tokens = output.split(b"\0")
    if tokens and tokens[-1] == b"":
        tokens.pop()
    records: list[dict[str, object]] = []
    index = 0
    while index < len(tokens):
        status = _decode_ascii(tokens[index], "name-status code")
        index += 1
        if not status:
            raise ValueError("empty Git name-status code")
        path_count = 2 if status[0] in {"R", "C"} else 1
        if index + path_count > len(tokens):
            raise ValueError("truncated NUL-delimited Git name-status output")
        record: dict[str, object] = {"status": status}
        if status[0] in {"R", "C"}:
            record.update(_path_fields(tokens[index], "old_path"))
            record.update(_path_fields(tokens[index + 1]))
            record["similarity_score"] = int(status[1:]) if status[1:].isdigit() else None
        else:
            record.update(_path_fields(tokens[index]))
        records.append(record)
        index += path_count
    return tuple(records)


def _stat_value(raw: bytes) -> int | str:
    if raw == b"-":
        return "-"
    try:
        return int(raw)
    except ValueError as error:
        raise ValueError("invalid Git numstat count") from error


def _parse_numstat(output: bytes) -> tuple[dict[str, object], ...]:
    tokens = output.split(b"\0")
    if tokens and tokens[-1] == b"":
        tokens.pop()
    records: list[dict[str, object]] = []
    index = 0
    while index < len(tokens):
        fields = tokens[index].split(b"\t", 2)
        index += 1
        if len(fields) != 3:
            raise ValueError("malformed NUL-delimited Git numstat output")
        additions, deletions, path = fields
        record: dict[str, object] = {
            "additions": _stat_value(additions),
            "deletions": _stat_value(deletions),
        }
        if path:
            record.update(_path_fields(path))
        else:
            if index + 2 > len(tokens):
                raise ValueError("truncated rename/copy Git numstat output")
            record.update(_path_fields(tokens[index], "old_path"))
            record.update(_path_fields(tokens[index + 1]))
            index += 2
        records.append(record)
    return tuple(records)


def _redact_patch(raw_patch: bytes, contains_binary: bool) -> RedactionResult:
    if not contains_binary:
        return redact_text(raw_patch)
    match = _FULL_INDEX.search(raw_patch)
    metadata: dict[str, object] = {}
    if match is not None:
        metadata = {
            "old_blob": match.group(1).decode("ascii"),
            "new_blob": match.group(2).decode("ascii"),
        }
    return redact_binary_patch(raw_patch, metadata)


def _collect_diff(
    git: _Git,
    metadata: _CommitMetadata,
    parent_sha: str | None,
    parent_ordinal: int,
) -> _DiffEvidence:
    base = parent_sha or EMPTY_TREE_SHA
    suffix = "ROOT" if parent_sha is None else f"P{parent_ordinal:02d}"
    source_id = f"GIT-{metadata.sha}-{suffix}"
    common = (base, metadata.sha)
    raw_patch = git.run(
        ("git", "diff", "--binary", "--full-index", "-M", "-C", *common)
    )
    file_statuses = _parse_name_status(
        git.run(("git", "diff", "--name-status", "-z", "-M", "-C", *common))
    )
    numstat = _parse_numstat(
        git.run(("git", "diff", "--numstat", "-z", "-M", "-C", *common))
    )
    contains_binary = any(
        row["additions"] == "-" or row["deletions"] == "-" for row in numstat
    )
    redacted = _redact_patch(raw_patch, contains_binary)
    return _DiffEvidence(
        source_id=source_id,
        child_sha=metadata.sha,
        parent_sha=parent_sha,
        parent_ordinal=parent_ordinal,
        parent_total=len(metadata.parents),
        raw_patch=raw_patch,
        stored_patch=redacted.value,
        raw_hash=redacted.raw_hash,
        stored_hash=redacted.stored_hash,
        privacy_redactions=redacted.kinds,
        file_statuses=file_statuses,
        numstat=numstat,
        contains_binary=contains_binary,
    )


def _commit_record(snapshot: SnapshotManifest, metadata: _CommitMetadata) -> SourceRecord:
    redacted = redact_text(metadata.raw)
    safe_author = redact_text(metadata.author)
    safe_committer = redact_text(metadata.committer)
    safe_message = redact_text(metadata.message)
    message_text, message_utf8 = _safe_text(safe_message.value)
    subject, _, body = message_text.partition("\n")
    author_text, author_utf8 = _safe_text(safe_author.value)
    committer_text, committer_utf8 = _safe_text(safe_committer.value)
    privacy = tuple(
        sorted(
            set(redacted.kinds)
            | set(safe_author.kinds)
            | set(safe_committer.kinds)
            | set(safe_message.kinds)
        )
    )
    committed_at = _identity_timestamp(metadata.committer)
    return SourceRecord(
        source_id=f"GIT-{metadata.sha}",
        source_type="git-commit",
        source_locator=f"git:{metadata.sha}",
        snapshot_id=snapshot.snapshot_id,
        title=subject or metadata.sha,
        evidence_scope="project-evidence",
        claim_authority="primary-record",
        recorded_status="captured",
        recorded_at=committed_at,
        raw_hash=_sha256(metadata.raw),
        stored_hash=redacted.stored_hash,
        raw_archive_locator=None,
        stored_members=(),
        explicit_relations=(),
        case_ids=(),
        classification="unreviewed",
        record_only_reason=None,
        availability_status="available",
        privacy_redactions=privacy,
        parse_status="parsed" if message_utf8 and author_utf8 and committer_utf8 else "decoded-with-replacement",
        payload={
            "commit_sha": metadata.sha,
            "tree_sha": metadata.tree,
            "parent_shas": list(metadata.parents),
            "author": author_text,
            "committer": committer_text,
            "authored_at": _identity_timestamp(metadata.author),
            "committed_at": committed_at,
            "subject": subject,
            "body": body,
            "signature_status": metadata.signature_status,
            "metadata_raw_sha256": _sha256(metadata.raw),
            "metadata_stored_sha256": redacted.stored_hash,
        },
    )


def _diff_record(
    snapshot: SnapshotManifest,
    evidence: _DiffEvidence,
    members: tuple[StoredArtifactMember, ...],
) -> SourceRecord:
    suffix = "ROOT" if evidence.parent_sha is None else f"P{evidence.parent_ordinal:02d}"
    base = evidence.parent_sha or EMPTY_TREE_SHA
    return SourceRecord(
        source_id=evidence.source_id,
        source_type="git-diff",
        source_locator=f"git:{base}..{evidence.child_sha}",
        snapshot_id=snapshot.snapshot_id,
        title=f"{evidence.child_sha} {suffix}",
        evidence_scope="project-evidence",
        claim_authority="primary-record",
        recorded_status="captured",
        recorded_at=None,
        raw_hash=evidence.raw_hash,
        stored_hash=evidence.stored_hash,
        raw_archive_locator=None,
        stored_members=members,
        explicit_relations=(),
        case_ids=(),
        classification="unreviewed",
        record_only_reason=None,
        availability_status="available",
        privacy_redactions=evidence.privacy_redactions,
        parse_status="parsed",
        payload={
            "child_sha": evidence.child_sha,
            "parent_sha": evidence.parent_sha,
            "comparison_base_sha": base,
            "parent_ordinal": evidence.parent_ordinal,
            "parent_total": evidence.parent_total,
            "file_statuses": list(evidence.file_statuses),
            "numstat": list(evidence.numstat),
            "contains_binary": evidence.contains_binary,
            "patch_raw_sha256": evidence.raw_hash,
            "patch_stored_sha256": evidence.stored_hash,
        },
    )


def collect_git_evidence(
    repo: str | Path,
    snapshot: SnapshotManifest,
    *,
    max_archive_bytes: int = 90_000_000,
) -> GitCapture:
    """Capture every semantic commit and one exact diff for each of its parents."""
    git = _Git(Path(repo))
    commit_shas = _enumerate_commits(git, snapshot)
    metadata = tuple(_commit_metadata(git, sha) for sha in commit_shas)
    commit_records = tuple(_commit_record(snapshot, item) for item in metadata)

    diffs: list[_DiffEvidence] = []
    for item in metadata:
        if not item.parents:
            diffs.append(_collect_diff(git, item, None, 1))
            continue
        for ordinal, parent_sha in enumerate(item.parents, start=1):
            diffs.append(_collect_diff(git, item, parent_sha, ordinal))
    diff_evidence = tuple(diffs)
    volumes = write_patch_volumes(
        tuple(
            PatchEntry(item.source_id, item.stored_patch, item.stored_hash)
            for item in diff_evidence
        ),
        max_bytes=max_archive_bytes,
    )
    members_by_source: dict[str, list[StoredArtifactMember]] = {}
    for volume in volumes:
        for member in volume.members:
            source_id, separator, _ = member.member_id.rpartition("-part-")
            if not separator:
                raise ValueError(f"invalid archive member ID: {member.member_id}")
            members_by_source.setdefault(source_id, []).append(member)
    diff_records = tuple(
        _diff_record(
            snapshot,
            item,
            tuple(sorted(members_by_source[item.source_id], key=lambda member: member.ordinal)),
        )
        for item in diff_evidence
    )
    records = tuple(
        sorted(
            (*commit_records, *diff_records),
            key=lambda record: record.source_id.encode("utf-8"),
        )
    )
    return GitCapture(
        records=records,
        commit_records=commit_records,
        diff_records=diff_records,
        archive_volumes=volumes,
        semantic_commit_shas=commit_shas,
        excluded_workflow_commit_shas_at_capture=(
            snapshot.excluded_workflow_commit_shas_at_capture
        ),
    )
