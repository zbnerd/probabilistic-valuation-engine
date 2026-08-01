"""Exhaustively account for frozen local AI trace artifacts.

Trace prose is never promoted to project fact.  The collector preserves a
complete redacted representation for review while keeping commands inert and
the original local-only bytes out of generated archives.
"""

from __future__ import annotations

import codecs
import gzip
import hashlib
import io
import json
import os
import stat
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

from .models import SnapshotManifest, SourceRecord, StoredArtifactMember
from .redaction import RedactionResult, redact_text


_TRACE_ROOT = "docs/ai-traces/"
_PART_BYTES = 8_000_000
_VOLUME_BYTES = 50_000_000
_READ_CHUNK = 1024 * 1024
_MAX_DECOMPRESSED_FILE_BYTES = 64_000_000
_JSON_STARTS = frozenset('{[')


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _source_id(path: str, suffix: str) -> str:
    identity = f"{path}\0{suffix}".encode("utf-8")
    return "AIT-" + hashlib.sha256(identity).hexdigest()


def _safe_path(repo: Path, relative_path: str) -> Path:
    if not relative_path.startswith(_TRACE_ROOT):
        raise ValueError(f"AI trace path is outside trace root: {relative_path}")
    root = repo.resolve(strict=True)
    candidate = repo / relative_path
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise ValueError(f"AI trace is missing or unreadable: {relative_path}") from error
    if root != resolved and root not in resolved.parents:
        raise ValueError(f"AI trace is outside repository: {relative_path}")
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"AI trace is not a regular file: {relative_path}")
    return resolved


def _read_frozen(path: Path, expected_size: int, expected_hash: str) -> bytes:
    value = bytearray()
    digest = hashlib.sha256()
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as stream:
        for chunk in iter(lambda: stream.read(_READ_CHUNK), b""):
            digest.update(chunk)
            value.extend(chunk)
    if len(value) != expected_size or digest.hexdigest() != expected_hash:
        raise ValueError(f"AI trace identity mismatch: {path.name}")
    return bytes(value)


def _decompress(raw: bytes, compressed: bool) -> tuple[bytes | None, bool | None]:
    if not compressed:
        return raw, None
    output = bytearray()
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(raw), mode="rb") as stream:
            for chunk in iter(lambda: stream.read(_READ_CHUNK), b""):
                output.extend(chunk)
                if len(output) > _MAX_DECOMPRESSED_FILE_BYTES:
                    raise ValueError("decompressed AI trace exceeds per-file limit")
    except (EOFError, OSError, gzip.BadGzipFile):
        return None, False
    return bytes(output), True


def _decode_utf8_with_offsets(value: bytes) -> tuple[str, tuple[int, ...]] | None:
    """Decode UTF-8 and return an exact character-boundary to byte map."""
    decoder = codecs.getincrementaldecoder("utf-8")(errors="strict")
    decoded_chunks: list[str] = []
    try:
        for offset in range(0, len(value), _READ_CHUNK):
            decoded_chunks.append(decoder.decode(value[offset : offset + _READ_CHUNK]))
        decoded_chunks.append(decoder.decode(b"", final=True))
    except UnicodeDecodeError:
        return None
    text = "".join(decoded_chunks)
    offsets = [0]
    byte_offset = 0
    for character in text:
        byte_offset += len(character.encode("utf-8"))
        offsets.append(byte_offset)
    return text, tuple(offsets)


@dataclass(frozen=True, slots=True)
class _Span:
    start: int
    end: int
    value: object | None
    parse_status: str


def _skip_whitespace(text: str, start: int) -> int:
    cursor = start
    while cursor < len(text) and text[cursor].isspace():
        cursor += 1
    return cursor


def _next_valid_top_level(
    text: str, decoder: json.JSONDecoder, start: int
) -> int | None:
    """Find a lexer-safe object/array start outside strings after bad bytes.

    Repository JSONL shapes use top-level objects.  Restricting recovery to
    object/array starts prevents a pretty object's nested string/scalar values
    from being mistaken for independent records.
    """
    in_string = False
    escaped = False
    for cursor in range(start, len(text)):
        current = text[cursor]
        if in_string:
            if escaped:
                escaped = False
            elif current == "\\":
                escaped = True
            elif current == '"':
                in_string = False
            continue
        if current == '"':
            in_string = True
            continue
        if current not in _JSON_STARTS:
            continue
        try:
            decoder.raw_decode(text, cursor)
        except json.JSONDecodeError:
            continue
        return cursor
    return None


def _parse_json_spans(value: bytes) -> tuple[_Span, ...] | None:
    decoded = _decode_utf8_with_offsets(value)
    if decoded is None:
        return None
    text, byte_offsets = decoded
    decoder = json.JSONDecoder()
    spans: list[_Span] = []
    cursor = 0
    while cursor < len(text):
        semantic_start = _skip_whitespace(text, cursor)
        if semantic_start == len(text):
            break
        try:
            parsed, end = decoder.raw_decode(text, semantic_start)
        except json.JSONDecodeError:
            recovered = _next_valid_top_level(text, decoder, semantic_start + 1)
            malformed_end = len(text) if recovered is None else recovered
            spans.append(
                _Span(
                    start=byte_offsets[cursor],
                    end=byte_offsets[malformed_end],
                    value=None,
                    parse_status="partial",
                )
            )
            cursor = malformed_end
            continue
        spans.append(
            _Span(
                start=byte_offsets[semantic_start],
                end=byte_offsets[end],
                value=parsed,
                parse_status="parsed",
            )
        )
        cursor = end
    return tuple(spans)


def _file_kind(path: str, is_json: bool, is_binary: bool = False) -> str:
    if is_binary:
        return "binary"
    name = Path(path.removesuffix(".gz")).name
    if is_json:
        return "json-stream"
    if name == "summary.md" or name.endswith(".md"):
        return "markdown-summary"
    if name.endswith(".patch") or name.endswith(".diff"):
        return "git-patch"
    if name == "git-log.txt" or name.endswith(".log") or name.endswith(".txt"):
        return "git-log"
    return "text-record"


def _meaningful_result(value: dict[str, object]) -> bool:
    for key in ("result", "output", "result_preview", "stdout", "stderr"):
        if key in value and value[key] not in (None, "", [], {}):
            return True
    exit_code = value.get("exit_code")
    if isinstance(exit_code, int) and not isinstance(exit_code, bool):
        return True
    error = value.get("error")
    return error not in (None, "", "null", "None", False)


def _explicit_truncation(value: object) -> bool:
    if isinstance(value, dict):
        for key, member in value.items():
            if key.lower() in {"truncated", "is_truncated", "output_truncated"} and member is True:
                return True
            if _explicit_truncation(member):
                return True
    elif isinstance(value, list):
        return any(_explicit_truncation(member) for member in value)
    elif isinstance(value, str):
        lowered = value.lower()
        return "[truncated" in lowered or "output truncated" in lowered
    return False


def _safe_label(value: object, fallback: str) -> str:
    if not isinstance(value, str) or not value:
        return fallback
    redacted = redact_text(value.encode("utf-8")).value.decode("utf-8", errors="replace")
    return redacted[:160]


def _entry_metadata(
    parsed: object | None,
    file_kind: str,
    parse_status: str,
) -> tuple[str, str, str, list[str], dict[str, object]]:
    if parse_status != "parsed" or parsed is None:
        limitation = "utf8-decode-failed" if parse_status == "binary-recorded" else "malformed-json-span"
        return (
            "ai-assertion",
            "recorded-only",
            "malformed-entry" if parse_status == "partial" else "binary-entry",
            [limitation],
            {},
        )
    if file_kind == "markdown-summary":
        return "ai-assertion", "asserted", "assistant-summary", [], {}
    if file_kind in {"git-patch", "git-log"}:
        return "trace-observation", "captured", file_kind, [], {}
    if not isinstance(parsed, dict):
        return "ai-assertion", "input-only", "json-value", ["non-object-entry"], {}

    role = _safe_label(parsed.get("role"), "")
    tool = _safe_label(parsed.get("tool"), "")
    event = _safe_label(parsed.get("event"), "")
    observed = _meaningful_result(parsed) or event in {
        "tool_result",
        "tool_error",
        "command_result",
        "session_start",
        "session_end",
    }
    truncated = _explicit_truncation(parsed)
    limitations: list[str] = []
    if truncated:
        limitations.append("result-truncated")
    if role == "assistant":
        authority, status, event_type = "ai-assertion", "asserted", "assistant-message"
    elif tool and not observed:
        authority, status, event_type = "ai-assertion", "attempted", "tool-input"
        limitations.append("result-missing")
    elif observed:
        authority, status = "trace-observation", "captured"
        event_type = event or (f"{tool}-result" if tool else "recorded-event")
    else:
        authority, status = "ai-assertion", "input-only"
        event_type = event or (f"{role}-message" if role else "json-record")
        limitations.append("result-missing" if "input" in parsed else "input-only")
    exit_code = parsed.get("exit_code")
    safe_fields: dict[str, object] = {
        "tool_type": tool or None,
        "truncated": truncated,
        "has_error": parsed.get("error") not in (None, "", "null", "None", False),
    }
    if isinstance(exit_code, int) and not isinstance(exit_code, bool):
        safe_fields["exit_code"] = exit_code
    return authority, status, event_type, limitations, safe_fields


@dataclass(frozen=True, slots=True)
class _Draft:
    source_id: str
    source_type: str
    source_locator: str
    title: str
    raw_hash: str
    claim_authority: str
    recorded_status: str
    classification: str
    record_only_reason: str | None
    privacy_redactions: tuple[str, ...]
    parse_status: str
    payload: dict[str, object]
    representation: bytes


def _representation(
    *,
    source_id: str,
    path: str,
    start: int,
    end: int,
    raw_value: bytes,
    metadata: dict[str, object],
    binary: bool = False,
) -> tuple[bytes, RedactionResult]:
    if binary:
        safe = RedactionResult(
            value=b"[BINARY CONTENT RECORDED BY HASH ONLY]",
            raw_hash=_sha256(raw_value),
            stored_hash=_sha256(b"[BINARY CONTENT RECORDED BY HASH ONLY]"),
            kinds=("binary-record-only",),
        )
    else:
        safe = redact_text(raw_value)
    result = _canonical_json(
        {
            "schema_version": 1,
            "source_id": source_id,
            "source_path": path,
            "byte_start": start,
            "byte_end": end,
            "metadata": metadata,
            "stored_text": safe.value.decode("utf-8", errors="strict"),
        }
    )
    return result, safe


def _logical_draft(
    *,
    path: str,
    content: bytes,
    span: _Span,
    ordinal: int,
    file_kind: str,
) -> _Draft:
    raw_value = content[span.start : span.end]
    authority, status, event_type, limitations, safe_fields = _entry_metadata(
        span.value, file_kind, span.parse_status
    )
    source_id = _source_id(path, f"entry:{ordinal}:{span.start}:{span.end}")
    metadata = {
        "entry_ordinal": ordinal,
        "event_type": event_type,
        "file_kind": file_kind,
        "input_result_role": status,
        "limitations": limitations,
        "parse_status": span.parse_status,
        **safe_fields,
    }
    representation, redaction = _representation(
        source_id=source_id,
        path=path,
        start=span.start,
        end=span.end,
        raw_value=raw_value,
        metadata=metadata,
        binary=span.parse_status == "binary-recorded",
    )
    classification = "record-only" if span.parse_status != "parsed" else "unreviewed"
    reason = "unsafe-or-unparsed-ai-trace-content" if classification == "record-only" else None
    return _Draft(
        source_id=source_id,
        source_type="ai-trace-entry",
        source_locator=f"ai-trace:{path}#bytes={span.start}-{span.end}",
        title=f"AI trace entry {ordinal}: {Path(path).name}",
        raw_hash=_sha256(raw_value),
        claim_authority=authority,
        recorded_status=status,
        classification=classification,
        record_only_reason=reason,
        privacy_redactions=redaction.kinds,
        parse_status=span.parse_status,
        payload={
            "source_path": path,
            "entry_ordinal": ordinal,
            "byte_start": span.start,
            "byte_end": span.end,
            "event_type": event_type,
            "file_kind": file_kind,
            "input_result_role": status,
            "limitations": limitations,
            **safe_fields,
        },
        representation=representation,
    )


def _file_drafts(path: str, raw: bytes) -> tuple[_Draft, tuple[_Draft, ...]]:
    compressed = path.endswith(".gz")
    content, gzip_valid = _decompress(raw, compressed)
    effective = raw if content is None else content
    json_stream = path.removesuffix(".gz").endswith(".jsonl")
    spans = _parse_json_spans(effective) if json_stream and content is not None else None
    if content is None or (json_stream and spans is None):
        parse_status = "binary-recorded"
        spans = (_Span(0, len(effective), None, parse_status),)
        kind = _file_kind(path, json_stream, is_binary=True)
    elif json_stream:
        assert spans is not None
        parse_status = "partial" if any(span.parse_status == "partial" for span in spans) else "parsed"
        kind = _file_kind(path, True)
    else:
        parse_status = "parsed"
        spans = (_Span(0, len(content), content.decode("utf-8", errors="strict"), "parsed"),) if _decode_utf8_with_offsets(content) is not None else (_Span(0, len(content), None, "binary-recorded"),)
        if spans[0].parse_status == "binary-recorded":
            parse_status = "binary-recorded"
        kind = _file_kind(path, False, is_binary=parse_status == "binary-recorded")

    children = tuple(
        _logical_draft(path=path, content=effective, span=span, ordinal=index, file_kind=kind)
        for index, span in enumerate(spans, start=1)
    )
    container_id = _source_id(path, "file")
    metadata = {
        "compression": "gzip" if compressed else "plain",
        "content_byte_count": len(effective),
        "entry_count": len(children),
        "file_kind": kind,
        "gzip_valid": gzip_valid,
        "parse_status": parse_status,
        "raw_byte_count": len(raw),
    }
    container_representation, redaction = _representation(
        source_id=container_id,
        path=path,
        start=0,
        end=len(effective),
        raw_value=effective,
        metadata=metadata,
        binary=parse_status == "binary-recorded",
    )
    container = _Draft(
        source_id=container_id,
        source_type="ai-trace-file",
        source_locator=f"ai-trace:{path}",
        title=f"AI trace file: {path}",
        raw_hash=_sha256(raw),
        claim_authority="ai-assertion",
        recorded_status="captured",
        classification="record-only" if parse_status == "binary-recorded" else "unreviewed",
        record_only_reason="binary-ai-trace-recorded-by-hash" if parse_status == "binary-recorded" else None,
        privacy_redactions=redaction.kinds,
        parse_status=parse_status,
        payload={"source_path": path, **metadata},
        representation=container_representation,
    )
    return container, children


@dataclass(frozen=True, slots=True)
class _Part:
    source_id: str
    ordinal: int
    total: int
    whole_byte_count: int
    whole_hash: str
    value: bytes

    @property
    def name(self) -> str:
        return f"records/{self.source_id}-part-{self.ordinal:03d}.json"


def _parts(drafts: tuple[_Draft, ...]) -> tuple[_Part, ...]:
    parts: list[_Part] = []
    for draft in sorted(drafts, key=lambda item: item.source_id.encode("utf-8")):
        chunks = tuple(
            draft.representation[offset : offset + _PART_BYTES]
            for offset in range(0, len(draft.representation), _PART_BYTES)
        ) or (b"",)
        for ordinal, chunk in enumerate(chunks, start=1):
            parts.append(
                _Part(
                    draft.source_id,
                    ordinal,
                    len(chunks),
                    len(draft.representation),
                    _sha256(draft.representation),
                    chunk,
                )
            )
    return tuple(parts)


def _groups(parts: tuple[_Part, ...]) -> tuple[tuple[_Part, ...], ...]:
    groups: list[tuple[_Part, ...]] = []
    current: list[_Part] = []
    byte_count = 0
    for part in parts:
        if current and byte_count + len(part.value) > _VOLUME_BYTES:
            groups.append(tuple(current))
            current = []
            byte_count = 0
        current.append(part)
        byte_count += len(part.value)
    if current:
        groups.append(tuple(current))
    return tuple(groups)


def _tar_info(name: str, size: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.mode = 0o644
    return info


def _render_volume(
    filename: str, parts: tuple[_Part, ...]
) -> tuple[bytes, dict[str, tuple[StoredArtifactMember, ...]]]:
    members: dict[str, list[StoredArtifactMember]] = {}
    manifest_entries: list[dict[str, object]] = []
    for source_id in sorted({part.source_id for part in parts}, key=lambda value: value.encode("utf-8")):
        source_parts = [part for part in parts if part.source_id == source_id]
        source_members = [
            StoredArtifactMember(
                member_id=f"{source_id}-part-{part.ordinal:03d}",
                locator=f"{filename}#{part.name}",
                ordinal=part.ordinal,
                total=part.total,
                byte_count=len(part.value),
                sha256=_sha256(part.value),
            )
            for part in source_parts
        ]
        members[source_id] = source_members
        manifest_entries.append(
            {
                "source_id": source_id,
                "whole_byte_count": source_parts[0].whole_byte_count,
                "whole_sha256": source_parts[0].whole_hash,
                "parts": [member.to_dict() for member in source_members],
            }
        )
    manifest = {"schema_version": 1, "volume": filename, "entries": manifest_entries}
    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for part in parts:
            archive.addfile(_tar_info(part.name, len(part.value)), io.BytesIO(part.value))
        manifest_bytes = _canonical_json(manifest)
        archive.addfile(_tar_info("reassembly-manifest.json", len(manifest_bytes)), io.BytesIO(manifest_bytes))
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", compresslevel=9, mtime=0) as stream:
        stream.write(tar_buffer.getvalue())
    return output.getvalue(), {key: tuple(value) for key, value in members.items()}


def _write_archives(
    archive_dir: Path, drafts: tuple[_Draft, ...]
) -> dict[str, tuple[StoredArtifactMember, ...]]:
    rendered = []
    for index, group in enumerate(_groups(_parts(drafts)), start=1):
        filename = f"ai-trace-records-{index:03d}.tar.gz"
        data, members = _render_volume(filename, group)
        rendered.append((filename, data, members))
    archive_dir.mkdir(parents=True, exist_ok=True)
    temporary_paths: list[Path] = []
    try:
        for filename, data, _ in rendered:
            descriptor, name = tempfile.mkstemp(prefix=f".{filename}.", suffix=".tmp", dir=archive_dir)
            temporary = Path(name)
            temporary_paths.append(temporary)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        for temporary, (filename, _, _) in zip(temporary_paths, rendered, strict=True):
            temporary.replace(archive_dir / filename)
        expected = {filename for filename, _, _ in rendered}
        for stale in archive_dir.glob("ai-trace-records-*.tar.gz"):
            if stale.name not in expected:
                stale.unlink()
    finally:
        for temporary in temporary_paths:
            temporary.unlink(missing_ok=True)
    combined: dict[str, list[StoredArtifactMember]] = {}
    for _, _, volume_members in rendered:
        for source_id, values in volume_members.items():
            combined.setdefault(source_id, []).extend(values)
    return {source_id: tuple(values) for source_id, values in combined.items()}


def _record(
    snapshot: SnapshotManifest,
    draft: _Draft,
    members: tuple[StoredArtifactMember, ...],
) -> SourceRecord:
    return SourceRecord(
        source_id=draft.source_id,
        source_type=draft.source_type,
        source_locator=draft.source_locator,
        snapshot_id=snapshot.snapshot_id,
        title=draft.title,
        evidence_scope="project-evidence",
        claim_authority=draft.claim_authority,  # type: ignore[arg-type]
        recorded_status=draft.recorded_status,
        recorded_at=None,
        raw_hash=draft.raw_hash,
        stored_hash=_sha256(draft.representation),
        raw_archive_locator=None,
        stored_members=members,
        explicit_relations=(),
        case_ids=(),
        classification=draft.classification,
        record_only_reason=draft.record_only_reason,
        availability_status="available",
        privacy_redactions=draft.privacy_redactions,
        parse_status=draft.parse_status,
        payload=draft.payload,
    )


def collect_ai_traces(
    repo: str | Path,
    snapshot: SnapshotManifest,
    archive_dir: str | Path,
) -> Iterator[SourceRecord]:
    """Yield one container and every logical entry from the frozen manifest."""
    repository = Path(repo)
    manifest = tuple(sorted(snapshot.ai_trace_files, key=lambda item: item.path.encode("utf-8")))
    paths = [item.path for item in manifest]
    if len(paths) != len(set(paths)):
        raise ValueError("duplicate AI trace path in snapshot")

    drafts: list[_Draft] = []
    for item in manifest:
        path = _safe_path(repository, item.path)
        raw = _read_frozen(path, item.byte_count, item.sha256)
        container, children = _file_drafts(item.path, raw)
        drafts.append(container)
        drafts.extend(children)
    frozen = tuple(drafts)
    members = _write_archives(Path(archive_dir), frozen)
    for draft in frozen:
        yield _record(snapshot, draft, members[draft.source_id])
