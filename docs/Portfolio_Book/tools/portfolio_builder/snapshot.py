"""Freeze the local Git and file-system evidence boundary."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import subprocess
import tempfile
from collections.abc import Sequence
from datetime import datetime, timedelta
from pathlib import Path
from typing import Protocol

from .models import (
    ExternalInputFile,
    FileSnapshot,
    LegacyOwnedOutput,
    RefSnapshot,
    SnapshotManifest,
    SourceBoundary,
    TrackedFileSnapshot,
)


BOUNDARY_PATH = Path("docs/Portfolio_Book/source_boundary.json")
AI_TRACE_ROOT = Path("docs/ai-traces")
DOCUMENT_EXTENSIONS = frozenset(
    {
        ".md",
        ".mdx",
        ".markdown",
        ".rst",
        ".adoc",
        ".txt",
        ".csv",
        ".tsv",
        ".json",
        ".jsonl",
        ".yaml",
        ".yml",
        ".toml",
        ".ini",
        ".cfg",
        ".properties",
        ".sql",
    }
)
PROSE_NAMES = ("README", "CHANGELOG", "LICENSE", "CONTRIBUTING")


class Clock(Protocol):
    def now(self) -> datetime: ...


class CommandRunner:
    """Run a command in one repository and return its unmodified stdout bytes."""

    def __init__(self, repo: Path):
        self._repo = repo

    def run(self, args: Sequence[str]) -> bytes:
        return subprocess.run(
            list(args),
            cwd=self._repo,
            check=True,
            capture_output=True,
        ).stdout


def _canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _utf8_key(value: str) -> bytes:
    return value.encode("utf-8")


def _utc_timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ValueError("snapshot clock must return a UTC-aware datetime")
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def _decode_line(value: bytes, label: str) -> str:
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"{label} is not UTF-8") from error


def _read_boundary(repo: Path, expected: SourceBoundary) -> tuple[SourceBoundary, str]:
    path = repo / BOUNDARY_PATH
    try:
        payload = json.loads(path.read_bytes())
        if not isinstance(payload, dict):
            raise TypeError("boundary must be a JSON object")
        parsed = SourceBoundary.from_dict(payload)
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        raise ValueError(f"invalid source boundary: {path}") from error
    if parsed != expected:
        raise ValueError("source boundary argument differs from source_boundary.json")
    canonical = _canonical_json_bytes(parsed.to_dict())
    return parsed, _sha256(canonical)


def _git_text(runner: CommandRunner, *args: str) -> str:
    return _decode_line(runner.run(("git", *args)), "Git output").strip()


def _verify_boundary_objects(runner: CommandRunner, boundary: SourceBoundary) -> None:
    try:
        source_tree = _git_text(
            runner, "rev-parse", f"{boundary.source_snapshot_head}^{{tree}}"
        )
        first_line = _git_text(
            runner, "rev-list", "--parents", "-n", "1", boundary.first_excluded_commit
        ).split()
    except subprocess.CalledProcessError as error:
        raise ValueError("source boundary references a missing Git object") from error
    if source_tree != boundary.source_snapshot_tree:
        raise ValueError("source snapshot tree does not match source boundary")
    if boundary.first_excluded_parent != boundary.source_snapshot_head:
        raise ValueError("first excluded parent must equal source snapshot head")
    if len(first_line) != 2 or first_line[1] != boundary.first_excluded_parent:
        raise ValueError("first excluded parent does not match Git history")


def _parse_refs(output: bytes) -> tuple[RefSnapshot, ...]:
    refs: list[RefSnapshot] = []
    for raw_line in output.splitlines():
        if not raw_line:
            continue
        fields = raw_line.split(b"\0")
        if len(fields) != 6:
            raise ValueError("malformed git for-each-ref output")
        values = [_decode_line(field, "Git ref field") for field in fields]
        refs.append(
            RefSnapshot(
                refname=values[0],
                object_sha=values[1],
                object_type=values[2],
                peeled_sha=values[3] or None,
                peeled_type=values[4] or None,
                symbolic_target=values[5] or None,
            )
        )
    return tuple(sorted(refs, key=lambda item: _utf8_key(item.refname)))


def _capture_refs(
    runner: CommandRunner, boundary: SourceBoundary
) -> tuple[str, str, tuple[RefSnapshot, ...], tuple[RefSnapshot, ...]]:
    refs = _parse_refs(
        runner.run(
            (
                "git",
                "for-each-ref",
                "--format=%(refname)%00%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00%(symref)",
            )
        )
    )
    observed_head = _git_text(runner, "rev-parse", "HEAD")
    try:
        head_symbolic_target = _git_text(runner, "symbolic-ref", "HEAD")
    except subprocess.CalledProcessError as error:
        raise ValueError("HEAD must be symbolic during snapshot capture") from error
    head = RefSnapshot(
        refname="HEAD",
        object_sha=observed_head,
        object_type=_git_text(runner, "cat-file", "-t", observed_head),
        peeled_sha=None,
        peeled_type=None,
        symbolic_target=head_symbolic_target,
    )
    observed_refs = tuple(
        sorted((*refs, head), key=lambda item: _utf8_key(item.refname))
    )
    by_name = {item.refname: item for item in refs}
    workflow = by_name.get(boundary.workflow_ref)
    if workflow is None:
        raise ValueError("workflow ref is absent")
    if (
        head_symbolic_target != boundary.workflow_ref
        or workflow.object_sha != observed_head
        or workflow.object_type != "commit"
    ):
        raise ValueError("workflow ref does not identify observed HEAD")
    semantic_refs = tuple(
        RefSnapshot(
            refname=item.refname,
            object_sha=boundary.source_snapshot_head,
            object_type="commit",
            peeled_sha=None,
            peeled_type=None,
            symbolic_target=item.symbolic_target,
        )
        if item.refname == boundary.workflow_ref
        else item
        for item in refs
    )
    return observed_head, head_symbolic_target, observed_refs, semantic_refs


def _excluded_chain(
    runner: CommandRunner,
    boundary: SourceBoundary,
    observed_head: str,
    observed_refs: tuple[RefSnapshot, ...],
) -> tuple[str, ...]:
    try:
        lines = _git_text(
            runner,
            "rev-list",
            "--reverse",
            "--topo-order",
            f"{boundary.first_excluded_parent}..{observed_head}",
        ).splitlines()
    except subprocess.CalledProcessError as error:
        raise ValueError("observed HEAD is not a valid excluded workflow chain") from error
    if not lines or lines[0] != boundary.first_excluded_commit or lines[-1] != observed_head:
        raise ValueError("excluded workflow chain does not match its locked endpoints")
    expected_parent = boundary.first_excluded_parent
    for commit in lines:
        parents = _git_text(runner, "rev-list", "--parents", "-n", "1", commit).split()
        if len(parents) != 2 or parents[1] != expected_parent:
            raise ValueError("excluded workflow chain must be strictly linear")
        expected_parent = commit
    chain = tuple(lines)
    chain_set = set(chain)
    for ref in observed_refs:
        if ref.refname in {"HEAD", boundary.workflow_ref}:
            continue
        if ref.object_sha in chain_set or ref.peeled_sha in chain_set:
            raise ValueError(
                f"ref points into excluded workflow chain: {ref.refname}"
            )
    return chain


def _collection_rule(path: str) -> str:
    if path == AI_TRACE_ROOT.as_posix() or path.startswith(
        AI_TRACE_ROOT.as_posix() + "/"
    ):
        return "ai-trace"
    if path == "docs" or path.startswith("docs/"):
        return "document"
    name = Path(path).name
    upper_name = name.upper()
    if Path(path).suffix.lower() == ".pdf":
        return "document"
    if upper_name in {"AGENTS.MD", "CLAUDE.MD"} or upper_name.startswith(PROSE_NAMES):
        return "document"
    if Path(path).suffix.lower() in DOCUMENT_EXTENSIONS:
        return "document"
    return "non-document"


def _tracked_files(
    runner: CommandRunner, boundary: SourceBoundary
) -> tuple[TrackedFileSnapshot, ...]:
    output = runner.run(
        (
            "git",
            "ls-tree",
            "-r",
            "-z",
            "--full-tree",
            boundary.source_snapshot_head,
        )
    )
    snapshots: list[TrackedFileSnapshot] = []
    for record in output.split(b"\0"):
        if not record:
            continue
        metadata, separator, raw_path = record.partition(b"\t")
        fields = metadata.split(b" ")
        if not separator or len(fields) != 3:
            raise ValueError("malformed git ls-tree output")
        mode, object_type, object_sha = (
            _decode_line(field, "Git tree metadata") for field in fields
        )
        path = _decode_line(raw_path, "Git tree path")
        snapshots.append(
            TrackedFileSnapshot(
                path=path,
                git_mode=mode,
                object_type=object_type,
                object_sha=object_sha,
                collection_rule_id=_collection_rule(path),
            )
        )
    return tuple(sorted(snapshots, key=lambda item: _utf8_key(item.path)))


def _contained_regular_file(repo: Path, relative_path: str, label: str) -> Path:
    root = repo.resolve(strict=True)
    candidate = repo / relative_path
    try:
        resolved = candidate.resolve(strict=True)
        metadata = resolved.stat()
    except (OSError, RuntimeError) as error:
        raise ValueError(f"{label} is missing or unreadable: {relative_path}") from error
    if root != resolved and root not in resolved.parents:
        raise ValueError(f"{label} is outside repository: {relative_path}")
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"{label} is not a regular file: {relative_path}")
    return resolved


def _hash_file(path: Path) -> tuple[int, str]:
    byte_count = 0
    digest = hashlib.sha256()
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            byte_count += len(chunk)
            digest.update(chunk)
    return byte_count, digest.hexdigest()


def _external_inputs(
    repo: Path, boundary: SourceBoundary
) -> tuple[ExternalInputFile, ...]:
    verified: list[ExternalInputFile] = []
    for item in boundary.external_input_files:
        path = _contained_regular_file(repo, item.path, "external input")
        byte_count, sha256 = _hash_file(path)
        if byte_count != item.byte_count or sha256 != item.sha256:
            raise ValueError(f"external input identity mismatch: {item.path}")
        verified.append(item)
    return tuple(verified)


def _legacy_outputs(
    runner: CommandRunner,
    boundary: SourceBoundary,
    tracked_files: tuple[TrackedFileSnapshot, ...],
) -> tuple[LegacyOwnedOutput, ...]:
    source_tree = {item.path: item for item in tracked_files}
    verified: list[LegacyOwnedOutput] = []
    for item in boundary.legacy_owned_outputs:
        tracked = source_tree.get(item.path)
        if (
            tracked is None
            or tracked.object_type != "blob"
            or tracked.object_sha != item.git_blob_oid
        ):
            raise ValueError(f"legacy owned output identity mismatch: {item.path}")
        try:
            content = runner.run(("git", "cat-file", "blob", item.git_blob_oid))
        except subprocess.CalledProcessError as error:
            raise ValueError(f"legacy owned output blob is absent: {item.path}") from error
        if _sha256(content) != item.sha256:
            raise ValueError(f"legacy owned output SHA-256 mismatch: {item.path}")
        verified.append(item)
    return tuple(verified)


def _ai_trace_files(repo: Path) -> tuple[FileSnapshot, ...]:
    root = repo.resolve(strict=True)
    trace_root = repo / AI_TRACE_ROOT
    if not os.path.lexists(trace_root):
        return ()
    try:
        trace_root_metadata = trace_root.lstat()
        resolved_trace_root = trace_root.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise ValueError("AI trace root is unreadable") from error
    if stat.S_ISLNK(trace_root_metadata.st_mode):
        raise ValueError("AI trace root must not be a symlink")
    if root != resolved_trace_root and root not in resolved_trace_root.parents:
        raise ValueError("AI trace root is outside repository")
    if not stat.S_ISDIR(trace_root_metadata.st_mode):
        raise ValueError("AI trace root must be a directory")

    snapshots: list[FileSnapshot] = []

    def walk(directory: Path) -> None:
        with os.scandir(directory) as iterator:
            entries = sorted(iterator, key=lambda entry: os.fsencode(entry.name))
        for entry in entries:
            member = Path(entry.path)
            metadata = entry.stat(follow_symlinks=False)
            if stat.S_ISLNK(metadata.st_mode):
                continue
            if stat.S_ISDIR(metadata.st_mode):
                walk(member)
                continue
            if not stat.S_ISREG(metadata.st_mode) or entry.name == ".env":
                continue
            relative_path = member.relative_to(repo).as_posix()
            byte_count, sha256 = _hash_file(member)
            snapshots.append(FileSnapshot(relative_path, byte_count, sha256))

    walk(trace_root)
    return tuple(sorted(snapshots, key=lambda item: _utf8_key(item.path)))


def _snapshot_id(
    *,
    source_boundary_sha256: str,
    boundary: SourceBoundary,
    observed_head: str,
    observed_refs: tuple[RefSnapshot, ...],
    semantic_refs: tuple[RefSnapshot, ...],
    chain: tuple[str, ...],
    external_inputs: tuple[ExternalInputFile, ...],
    legacy_outputs: tuple[LegacyOwnedOutput, ...],
    tracked_files: tuple[TrackedFileSnapshot, ...],
    ai_trace_files: tuple[FileSnapshot, ...],
) -> str:
    identity = {
        "source_boundary_sha256": source_boundary_sha256,
        "source_boundary": boundary.to_dict(),
        "observed_head_sha": observed_head,
        "observed_refs": [item.to_dict() for item in observed_refs],
        "semantic_refs": [item.to_dict() for item in semantic_refs],
        "excluded_workflow_commit_shas_at_capture": list(chain),
        "external_input_files": [item.to_dict() for item in external_inputs],
        "legacy_owned_outputs": [item.to_dict() for item in legacy_outputs],
        "tracked_files": [item.to_dict() for item in tracked_files],
        "ai_trace_files": [item.to_dict() for item in ai_trace_files],
    }
    return "SNAP-" + _sha256(_canonical_json_bytes(identity))


def capture_local_snapshot(
    repo: Path, boundary: SourceBoundary, clock: Clock
) -> SnapshotManifest:
    """Validate and capture the immutable local source cutoff."""
    started_at = _utc_timestamp(clock.now())
    repository = repo.resolve(strict=True)
    parsed_boundary, boundary_sha256 = _read_boundary(repository, boundary)
    runner = CommandRunner(repository)
    _verify_boundary_objects(runner, parsed_boundary)
    observed_head, symbolic_head, observed_refs, semantic_refs = _capture_refs(
        runner, parsed_boundary
    )
    chain = _excluded_chain(
        runner, parsed_boundary, observed_head, observed_refs
    )
    tracked_files = _tracked_files(runner, parsed_boundary)
    external_inputs = _external_inputs(repository, parsed_boundary)
    legacy_outputs = _legacy_outputs(runner, parsed_boundary, tracked_files)
    ai_trace_files = _ai_trace_files(repository)
    completed_at = _utc_timestamp(clock.now())
    snapshot_id = _snapshot_id(
        source_boundary_sha256=boundary_sha256,
        boundary=parsed_boundary,
        observed_head=observed_head,
        observed_refs=observed_refs,
        semantic_refs=semantic_refs,
        chain=chain,
        external_inputs=external_inputs,
        legacy_outputs=legacy_outputs,
        tracked_files=tracked_files,
        ai_trace_files=ai_trace_files,
    )
    return SnapshotManifest(
        snapshot_id=snapshot_id,
        started_at=started_at,
        local_completed_at=completed_at,
        finalized_at=None,
        source_boundary_sha256=boundary_sha256,
        source_snapshot_head=parsed_boundary.source_snapshot_head,
        source_snapshot_tree=parsed_boundary.source_snapshot_tree,
        first_excluded_commit=parsed_boundary.first_excluded_commit,
        first_excluded_parent=parsed_boundary.first_excluded_parent,
        workflow_ref=parsed_boundary.workflow_ref,
        observed_head_sha=observed_head,
        observed_head_symbolic_target=symbolic_head,
        observed_refs=observed_refs,
        semantic_refs=semantic_refs,
        excluded_workflow_commit_shas_at_capture=chain,
        external_input_files=external_inputs,
        legacy_owned_outputs=legacy_outputs,
        tracked_files=tracked_files,
        ai_trace_files=ai_trace_files,
        github_window=None,
    )


def write_snapshot_manifest(path: str | Path, manifest: SnapshotManifest) -> None:
    """Atomically write one canonical UTF-8 snapshot manifest."""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.name}.", suffix=".tmp", dir=target.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(_canonical_json_bytes(manifest.to_dict()))
            stream.write(b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
