"""Collect immutable tracked documents and boundary-verified PDF claim units.

Tracked bytes always come from the frozen Git object database.  External PDF
bytes are accepted only when their path, size, and SHA-256 match the snapshot;
the PDFs themselves are never copied into the derived review archives.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import tarfile
import tempfile
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Sequence

import pymupdf
from markdown_it import MarkdownIt

from .models import (
    DocumentClaim,
    ExternalInputFile,
    SnapshotManifest,
    SourceRecord,
    StoredArtifactMember,
    TrackedFileSnapshot,
)
from .redaction import RedactionResult, redact_text


_ARCHIVE_PART_BYTES = 8_000_000
_ARCHIVE_VOLUME_BYTES = 50_000_000
_LARGE_TEXT_BYTES = 8_000_000
_LINE_BATCH_BYTES = 64_000
_SENTENCE_END = re.compile(r"(?<=[.!?。！？])(?=\s|$)")
_SAFE_MEMBER = re.compile(r"[^A-Za-z0-9._-]")
_REDACTION_KIND = re.compile(r"\[REDACTED:([a-z0-9-]+)\]")
_MARKDOWN_SUFFIXES = frozenset({".md", ".mdx", ".markdown"})
_EXTERNAL_MAPPING = {
    "renewal-guide": ("structural-reference", "structural-reference"),
    "id-photo-source-resume": ("personal-evidence", "personal-record"),
    "legacy-portfolio-reference": ("personal-evidence", "personal-record"),
}


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


def _utf8_key(value: str) -> bytes:
    return value.encode("utf-8")


class _Git:
    def __init__(self, repo: Path):
        self.repo = repo.resolve(strict=True)

    def run(self, args: Sequence[str]) -> bytes:
        return subprocess.run(
            list(args), cwd=self.repo, check=True, capture_output=True
        ).stdout


@dataclass(frozen=True, slots=True)
class _DraftDocument:
    source_id: str
    source_type: str
    source_locator: str
    title: str
    evidence_scope: str
    claim_authority: str
    raw_hash: str
    privacy_redactions: tuple[str, ...]
    parse_status: str
    classification: str
    record_only_reason: str | None
    availability_status: str
    payload: dict[str, object]
    claims: tuple[DocumentClaim, ...]


@dataclass(frozen=True, slots=True)
class _ArchivePart:
    source_id: str
    ordinal: int
    total: int
    whole_hash: str
    whole_size: int
    value: bytes

    @property
    def member_id(self) -> str:
        return f"{self.source_id}-part-{self.ordinal:03d}"

    @property
    def member_name(self) -> str:
        return f"records/{self.member_id}.json.part"


def _parse_tree(output: bytes) -> dict[str, TrackedFileSnapshot]:
    entries: dict[str, TrackedFileSnapshot] = {}
    for raw_record in output.split(b"\0"):
        if not raw_record:
            continue
        metadata, separator, raw_path = raw_record.partition(b"\t")
        fields = metadata.split(b" ")
        if not separator or len(fields) != 3:
            raise ValueError("malformed git ls-tree output")
        try:
            mode, kind, oid = (field.decode("ascii") for field in fields)
            path = raw_path.decode("utf-8")
        except UnicodeDecodeError as error:
            raise ValueError("git tree entry is not decodable") from error
        if path in entries:
            raise ValueError(f"duplicate git tree path: {path}")
        entries[path] = TrackedFileSnapshot(path, mode, kind, oid, "non-document")
    return entries


def _tracked_source_id(item: TrackedFileSnapshot) -> str:
    path_hash = _sha256(item.path.encode("utf-8"))[:16]
    return f"DOC-GIT-{item.object_sha}-{path_hash}"


def _external_source_id(item: ExternalInputFile) -> str:
    role = _SAFE_MEMBER.sub("-", item.role.upper())
    identity = _sha256(
        _canonical_json(
            {
                "role": item.role,
                "path": item.path,
                "byte_count": item.byte_count,
                "sha256": item.sha256,
            }
        )
    )[:20]
    return f"DOC-PDF-{role}-{identity}"


def _line_offsets(text: str) -> tuple[int, ...]:
    offsets = [0]
    for match in re.finditer(r"\n", text):
        offsets.append(match.end())
    offsets.append(len(text) + 1)
    return tuple(offsets)


def _line_for_offset(offsets: tuple[int, ...], offset: int) -> int:
    low = 0
    high = len(offsets) - 1
    while low + 1 < high:
        midpoint = (low + high) // 2
        if offsets[midpoint] <= offset:
            low = midpoint
        else:
            high = midpoint
    return low + 1


def _safe_text(raw: bytes) -> tuple[str, str, tuple[str, ...]]:
    redacted = redact_text(raw)
    return (
        redacted.value.decode("utf-8", errors="replace"),
        redacted.stored_hash,
        redacted.kinds,
    )


def _claim(
    *,
    source_id: str,
    source_path: str,
    scope: str,
    authority: str,
    unit_kind: str,
    raw: bytes,
    text: str,
    line_start: int | None,
    line_end: int | None,
    page_index: int | None,
    block_index: int,
    suffix: str,
    already_safe: bool = False,
) -> DocumentClaim:
    if already_safe:
        safe = text
        stored_hash = _sha256(text.encode("utf-8"))
    else:
        safe, stored_hash, _ = _safe_text(raw)
    if not already_safe and text != raw.decode("utf-8", errors="replace"):
        safe, stored_hash, _ = _safe_text(text.encode("utf-8"))
    return DocumentClaim(
        claim_id=f"{source_id}-{suffix}",
        document_source_id=source_id,
        source_path=source_path,
        evidence_scope=scope,  # type: ignore[arg-type]
        claim_authority=authority,  # type: ignore[arg-type]
        unit_kind=unit_kind,
        line_start=line_start,
        line_end=line_end,
        page_index=page_index,
        block_index=block_index,
        raw_hash=_sha256(raw),
        stored_hash=stored_hash,
        stored_members=(),
        text=safe,
        classification="unreviewed",
        parse_status="parsed",
    )


def _sentence_spans(text: str, start: int, end: int) -> tuple[tuple[int, int], ...]:
    spans: list[tuple[int, int]] = []
    cursor = start
    for match in _SENTENCE_END.finditer(text, start, end):
        boundary = match.start()
        left = cursor
        right = boundary
        while left < right and text[left].isspace():
            left += 1
        while right > left and text[right - 1].isspace():
            right -= 1
        if left < right:
            spans.append((left, right))
        cursor = match.end()
    left = cursor
    right = end
    while left < right and text[left].isspace():
        left += 1
    while right > left and text[right - 1].isspace():
        right -= 1
    if left < right:
        spans.append((left, right))
    return tuple(spans)


def _markdown_claims(
    source_id: str,
    path: str,
    text: str,
    scope: str,
    authority: str,
    already_safe: bool,
) -> tuple[DocumentClaim, ...]:
    parser = MarkdownIt("commonmark").enable("table")
    tokens = parser.parse(text)
    lines = text.splitlines(keepends=True)
    offsets = _line_offsets(text)
    units: list[tuple[str, int, int]] = []
    token_kinds = {
        "heading_open": "heading",
        "paragraph_open": "paragraph",
        "list_item_open": "list-item",
        "tr_open": "table-row",
    }
    for token in tokens:
        if token.map is None:
            continue
        start, end = token.map
        if token.type == "fence":
            kind = "fenced-diagram" if token.info.strip().lower() == "mermaid" else "fenced-code"
        else:
            kind = token_kinds.get(token.type)
        if kind is not None and end > start:
            units.append((kind, start + 1, end))

    seen_units: set[tuple[str, int, int]] = set()
    seen_sentence_spans: set[tuple[int, int]] = set()
    claims: list[DocumentClaim] = []
    for kind, line_start, line_end in units:
        key = (kind, line_start, line_end)
        if key in seen_units:
            continue
        seen_units.add(key)
        raw_text = "".join(lines[line_start - 1 : line_end]).rstrip("\r\n")
        raw = raw_text.encode("utf-8")
        ordinal = sum(
            previous.unit_kind == kind
            and previous.line_start == line_start
            and previous.line_end == line_end
            for previous in claims
        ) + 1
        suffix = f"L{line_start:06d}-L{line_end:06d}-{kind.upper().replace('-', '_')}-{ordinal:03d}"
        claims.append(
            _claim(
                source_id=source_id,
                source_path=path,
                scope=scope,
                authority=authority,
                unit_kind=kind,
                raw=raw,
                text=raw_text,
                line_start=line_start,
                line_end=line_end,
                page_index=None,
                block_index=len(claims),
                suffix=suffix,
                already_safe=already_safe,
            )
        )

        absolute_start = offsets[line_start - 1]
        absolute_end = min(offsets[line_end], len(text))
        first_line_end = text.find("\n", absolute_start, absolute_end)
        if first_line_end < 0:
            first_line_end = absolute_end
        first_line = text[absolute_start:first_line_end]
        marker = None
        if kind == "heading":
            marker = re.match(r"\s{0,3}#{1,6}\s+", first_line)
        elif kind == "list-item":
            marker = re.match(r"\s*(?:[-+*]|\d+[.)])\s+", first_line)
        if marker is not None:
            absolute_start += marker.end()
        sentence_ordinal = 0
        for sentence_start, sentence_end in _sentence_spans(
            text, absolute_start, absolute_end
        ):
            sentence = text[sentence_start:sentence_end]
            if not sentence or kind in {"fenced-code", "fenced-diagram", "table-row"}:
                continue
            if (sentence_start, sentence_end) in seen_sentence_spans:
                continue
            seen_sentence_spans.add((sentence_start, sentence_end))
            sentence_ordinal += 1
            sentence_line_start = _line_for_offset(offsets, sentence_start)
            sentence_line_end = _line_for_offset(offsets, max(sentence_start, sentence_end - 1))
            claims.append(
                _claim(
                    source_id=source_id,
                    source_path=path,
                    scope=scope,
                    authority=authority,
                    unit_kind="sentence",
                    raw=sentence.encode("utf-8"),
                    text=sentence,
                    line_start=sentence_line_start,
                    line_end=sentence_line_end,
                    page_index=None,
                    block_index=len(claims),
                    suffix=(
                        f"L{sentence_line_start:06d}-L{sentence_line_end:06d}"
                        f"-SENTENCE-{sentence_ordinal:03d}-{_sha256(sentence.encode('utf-8'))[:12]}"
                    ),
                    already_safe=already_safe,
                )
            )
    return tuple(claims)


def _plain_claims(
    source_id: str,
    path: str,
    text: str,
    scope: str,
    authority: str,
    already_safe: bool,
) -> tuple[DocumentClaim, ...]:
    offsets = _line_offsets(text)
    claims: list[DocumentClaim] = []
    lines_with_endings = text.splitlines(keepends=True)
    if len(text.encode("utf-8")) > _LARGE_TEXT_BYTES:
        batch_start = 0
        batch_bytes = 0
        for line_offset, line in enumerate(lines_with_endings):
            line_bytes = len(line.encode("utf-8"))
            if batch_bytes and batch_bytes + line_bytes > _LINE_BATCH_BYTES:
                raw_text = "".join(lines_with_endings[batch_start:line_offset])
                line_start = batch_start + 1
                line_end = line_offset
                claims.append(
                    _claim(
                        source_id=source_id,
                        source_path=path,
                        scope=scope,
                        authority=authority,
                        unit_kind="line-batch",
                        raw=raw_text.encode("utf-8"),
                        text=raw_text,
                        line_start=line_start,
                        line_end=line_end,
                        page_index=None,
                        block_index=len(claims),
                        suffix=f"L{line_start:06d}-L{line_end:06d}-LINE_BATCH-001",
                        already_safe=already_safe,
                    )
                )
                batch_start = line_offset
                batch_bytes = 0
            batch_bytes += line_bytes
        if batch_start < len(lines_with_endings):
            raw_text = "".join(lines_with_endings[batch_start:])
            line_start = batch_start + 1
            line_end = len(lines_with_endings)
            claims.append(
                _claim(
                    source_id=source_id,
                    source_path=path,
                    scope=scope,
                    authority=authority,
                    unit_kind="line-batch",
                    raw=raw_text.encode("utf-8"),
                    text=raw_text,
                    line_start=line_start,
                    line_end=line_end,
                    page_index=None,
                    block_index=len(claims),
                    suffix=f"L{line_start:06d}-L{line_end:06d}-LINE_BATCH-001",
                    already_safe=already_safe,
                )
            )
        return tuple(claims)

    for line_index, raw_line_with_ending in enumerate(lines_with_endings, start=1):
        raw_line = raw_line_with_ending.rstrip("\r\n")
        if not raw_line.strip():
            continue
        claims.append(
            _claim(
                source_id=source_id,
                source_path=path,
                scope=scope,
                authority=authority,
                unit_kind="line",
                raw=raw_line.encode("utf-8"),
                text=raw_line,
                line_start=line_index,
                line_end=line_index,
                page_index=None,
                block_index=len(claims),
                suffix=f"L{line_index:06d}-L{line_index:06d}-LINE-001",
                already_safe=already_safe,
            )
        )
        if not re.search(r"[.!?。！？]", raw_line):
            continue
        line_start = offsets[line_index - 1]
        line_end = min(offsets[line_index], len(text))
        for sentence_index, (start, end) in enumerate(
            _sentence_spans(text, line_start, line_end), start=1
        ):
            sentence = text[start:end]
            claims.append(
                _claim(
                    source_id=source_id,
                    source_path=path,
                    scope=scope,
                    authority=authority,
                    unit_kind="sentence",
                    raw=sentence.encode("utf-8"),
                    text=sentence,
                    line_start=line_index,
                    line_end=line_index,
                    page_index=None,
                    block_index=len(claims),
                    suffix=(
                        f"L{line_index:06d}-L{line_index:06d}-SENTENCE-{sentence_index:03d}"
                        f"-{_sha256(sentence.encode('utf-8'))[:12]}"
                    ),
                    already_safe=already_safe,
                )
            )
    return tuple(claims)


def _tracked_document(
    git: _Git,
    snapshot: SnapshotManifest,
    item: TrackedFileSnapshot,
    tree: dict[str, TrackedFileSnapshot],
    redaction_cache: dict[str, RedactionResult],
) -> _DraftDocument:
    observed = tree.get(item.path)
    if observed is None or (
        observed.git_mode,
        observed.object_type,
        observed.object_sha,
    ) != (item.git_mode, item.object_type, item.object_sha):
        raise ValueError(f"tracked snapshot entry mismatch: {item.path}")
    source_id = _tracked_source_id(item)
    scope = "project-evidence"
    authority = (
        "legacy-derived-record"
        if item.path.startswith("docs/Portfolio_Book/output/")
        else "primary-record"
    )
    if item.object_type != "blob":
        metadata = _canonical_json(
            {
                "path": item.path,
                "git_mode": item.git_mode,
                "object_type": item.object_type,
                "object_sha": item.object_sha,
            }
        )
        return _DraftDocument(
            source_id=source_id,
            source_type="tracked-document-non-blob",
            source_locator=f"git:{item.path}",
            title=item.path,
            evidence_scope=scope,
            claim_authority=authority,
            raw_hash=_sha256(metadata),
            privacy_redactions=(),
            parse_status="non-blob-recorded",
            classification="unreviewed",
            record_only_reason="tracked entry has no blob bytes",
            availability_status="confirmed-unavailable",
            payload={
                "path": item.path,
                "git_mode": item.git_mode,
                "object_type": item.object_type,
                "object_sha": item.object_sha,
                "unit_count": 0,
            },
            claims=(),
        )

    try:
        raw = git.run(("git", "cat-file", "blob", item.object_sha))
    except subprocess.CalledProcessError as error:
        raise ValueError(f"frozen tracked blob unavailable: {item.path}") from error
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        marker = "[RECORD-ONLY: document is not valid UTF-8]"
        claim = _claim(
            source_id=source_id,
            source_path=item.path,
            scope=scope,
            authority=authority,
            unit_kind="non-utf8-record",
            raw=raw,
            text=marker,
            line_start=None,
            line_end=None,
            page_index=None,
            block_index=0,
            suffix="NONUTF8-001",
        )
        return _DraftDocument(
            source_id=source_id,
            source_type="tracked-document",
            source_locator=f"git:{item.path}",
            title=item.path,
            evidence_scope=scope,
            claim_authority=authority,
            raw_hash=_sha256(raw),
            privacy_redactions=(),
            parse_status="non-utf8-record-only",
            classification="record-only",
            record_only_reason="document bytes are not valid UTF-8",
            availability_status="available",
            payload={
                "path": item.path,
                "git_mode": item.git_mode,
                "object_type": item.object_type,
                "object_sha": item.object_sha,
                "safe_text": marker,
                "unit_count": 1,
            },
            claims=(replace(claim, classification="record-only"),),
        )

    safe_document = redaction_cache.get(item.object_sha)
    if safe_document is None:
        safe_document = redact_text(raw)
        redaction_cache[item.object_sha] = safe_document
    elif safe_document.raw_hash != _sha256(raw):
        raise ValueError(f"frozen blob identity changed during collection: {item.path}")
    safe_text = safe_document.value.decode("utf-8")
    already_safe = not safe_document.kinds
    claims = (
        _markdown_claims(
            source_id, item.path, text, scope, authority, already_safe
        )
        if Path(item.path).suffix.lower() in _MARKDOWN_SUFFIXES
        else _plain_claims(
            source_id, item.path, text, scope, authority, already_safe
        )
    )
    return _DraftDocument(
        source_id=source_id,
        source_type="tracked-document",
        source_locator=f"git:{item.path}",
        title=item.path,
        evidence_scope=scope,
        claim_authority=authority,
        raw_hash=_sha256(raw),
        privacy_redactions=safe_document.kinds,
        parse_status="parsed",
        classification="unreviewed",
        record_only_reason=None,
        availability_status="available",
        payload={
            "path": item.path,
            "git_mode": item.git_mode,
            "object_type": item.object_type,
            "object_sha": item.object_sha,
            "safe_text": safe_text,
            "unit_count": len(claims),
        },
        claims=claims,
    )


def _verified_external_bytes(repo: Path, item: ExternalInputFile) -> bytes:
    root = repo.resolve(strict=True)
    candidate = repo / item.path
    try:
        resolved = candidate.resolve(strict=True)
        metadata = resolved.stat()
    except (OSError, RuntimeError) as error:
        raise ValueError(f"external input is missing or unreadable: {item.path}") from error
    if root != resolved and root not in resolved.parents:
        raise ValueError(f"external input is outside repository: {item.path}")
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"external input is not a regular file: {item.path}")
    digest = hashlib.sha256()
    chunks: list[bytes] = []
    byte_count = 0
    with resolved.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            chunks.append(chunk)
            digest.update(chunk)
            byte_count += len(chunk)
    if byte_count != item.byte_count or digest.hexdigest() != item.sha256:
        raise ValueError(f"external input identity mismatch: {item.path}")
    return b"".join(chunks)


def _pdf_claims(
    source_id: str,
    item: ExternalInputFile,
    raw_pdf: bytes,
    scope: str,
    authority: str,
) -> tuple[DocumentClaim, ...]:
    try:
        document = pymupdf.open(stream=raw_pdf, filetype="pdf")
    except Exception as error:
        raise ValueError(f"external input is not a readable PDF: {item.path}") from error
    claims: list[DocumentClaim] = []
    try:
        if item.role == "renewal-guide" and document.page_count != 31:
            raise ValueError("renewal-guide must contain exactly 31 pages")
        for page_index in range(document.page_count):
            page = document.load_page(page_index)
            page_number = page_index + 1
            page_text = page.get_text("text", sort=True)
            claims.append(
                _claim(
                    source_id=source_id,
                    source_path=item.path,
                    scope=scope,
                    authority=authority,
                    unit_kind="pdf-page",
                    raw=page_text.encode("utf-8"),
                    text=page_text,
                    line_start=None,
                    line_end=None,
                    page_index=page_index,
                    block_index=0,
                    suffix=f"P{page_number:04d}-PAGE",
                )
            )
            blocks = page.get_text("blocks", sort=True)
            for block_index, block in enumerate(blocks, start=1):
                block_text = str(block[4])
                claims.append(
                    _claim(
                        source_id=source_id,
                        source_path=item.path,
                        scope=scope,
                        authority=authority,
                        unit_kind="pdf-text-block",
                        raw=block_text.encode("utf-8"),
                        text=block_text,
                        line_start=None,
                        line_end=None,
                        page_index=page_index,
                        block_index=block_index,
                        suffix=f"P{page_number:04d}-B{block_index:04d}",
                    )
                )
            for image_index, image in enumerate(page.get_images(full=True), start=1):
                xref = int(image[0])
                extracted = document.extract_image(xref)
                image_bytes = extracted.get("image")
                if not isinstance(image_bytes, bytes):
                    raise ValueError(
                        f"PDF image object is not extractable: {item.path} page {page_number}"
                    )
                safe_metadata = {
                    "page_number": page_number,
                    "image_index": image_index,
                    "width": int(image[2]),
                    "height": int(image[3]),
                    "bits_per_component": int(image[4]),
                    "colorspace": str(image[5]),
                    "extension": str(extracted.get("ext", "unknown")),
                    "image_byte_count": len(image_bytes),
                    "image_sha256": _sha256(image_bytes),
                }
                metadata_bytes = _canonical_json(safe_metadata)
                claims.append(
                    _claim(
                        source_id=source_id,
                        source_path=item.path,
                        scope=scope,
                        authority=authority,
                        unit_kind="pdf-image-object",
                        raw=image_bytes,
                        text=metadata_bytes.decode("utf-8").rstrip("\n"),
                        line_start=None,
                        line_end=None,
                        page_index=page_index,
                        block_index=image_index,
                        suffix=f"P{page_number:04d}-I{image_index:04d}",
                    )
                )
    finally:
        document.close()
    return tuple(claims)


def _external_document(
    repo: Path, snapshot: SnapshotManifest, item: ExternalInputFile
) -> _DraftDocument:
    mapping = _EXTERNAL_MAPPING.get(item.role)
    if mapping is None:
        raise ValueError(f"unsupported external role: {item.role}")
    scope, authority = mapping
    raw = _verified_external_bytes(repo, item)
    source_id = _external_source_id(item)
    claims = _pdf_claims(source_id, item, raw, scope, authority)
    privacy = tuple(
        sorted(
            {
                match.group(1)
                for claim in claims
                for match in _REDACTION_KIND.finditer(claim.text)
            }
        )
    )
    return _DraftDocument(
        source_id=source_id,
        source_type="external-pdf-derived-record",
        source_locator=f"external:{item.path}",
        title=item.role,
        evidence_scope=scope,
        claim_authority=authority,
        raw_hash=item.sha256,
        privacy_redactions=privacy,
        parse_status="parsed",
        classification="unreviewed",
        record_only_reason=None,
        availability_status="available",
        payload={
            "external_role": item.role,
            "path": item.path,
            "original_identity_sha256": item.sha256,
            "original_byte_count": item.byte_count,
            "page_count": sum(claim.unit_kind == "pdf-page" for claim in claims),
            "text_block_count": sum(
                claim.unit_kind == "pdf-text-block" for claim in claims
            ),
            "image_object_count": sum(
                claim.unit_kind == "pdf-image-object" for claim in claims
            ),
            "unit_count": len(claims),
            "original_pdf_archived": False,
        },
        claims=claims,
    )


def _safe_representation(draft: _DraftDocument) -> bytes:
    return _canonical_json(
        {
            "schema_version": 1,
            "source": {
                "source_id": draft.source_id,
                "source_type": draft.source_type,
                "source_locator": draft.source_locator,
                "title": draft.title,
                "evidence_scope": draft.evidence_scope,
                "claim_authority": draft.claim_authority,
                "raw_hash": draft.raw_hash,
                "privacy_redactions": list(draft.privacy_redactions),
                "parse_status": draft.parse_status,
                "classification": draft.classification,
                "record_only_reason": draft.record_only_reason,
                "availability_status": draft.availability_status,
                "payload": draft.payload,
            },
            "claims": [claim.to_dict() for claim in draft.claims],
        }
    )


def _archive_parts(
    representations: dict[str, bytes]
) -> tuple[_ArchivePart, ...]:
    parts: list[_ArchivePart] = []
    for source_id in sorted(representations, key=_utf8_key):
        value = representations[source_id]
        chunks = tuple(
            value[offset : offset + _ARCHIVE_PART_BYTES]
            for offset in range(0, len(value), _ARCHIVE_PART_BYTES)
        ) or (b"",)
        for ordinal, chunk in enumerate(chunks, start=1):
            parts.append(
                _ArchivePart(
                    source_id=source_id,
                    ordinal=ordinal,
                    total=len(chunks),
                    whole_hash=_sha256(value),
                    whole_size=len(value),
                    value=chunk,
                )
            )
    return tuple(parts)


def _archive_groups(parts: tuple[_ArchivePart, ...]) -> tuple[tuple[_ArchivePart, ...], ...]:
    groups: list[tuple[_ArchivePart, ...]] = []
    current: list[_ArchivePart] = []
    current_bytes = 0
    for part in parts:
        if current and current_bytes + len(part.value) > _ARCHIVE_VOLUME_BYTES:
            groups.append(tuple(current))
            current = []
            current_bytes = 0
        current.append(part)
        current_bytes += len(part.value)
    if current:
        groups.append(tuple(current))
    return tuple(groups)


def _tar_info(name: str, size: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = size
    info.mtime = 0
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mode = 0o644
    return info


def _render_archive(
    filename: str, parts: tuple[_ArchivePart, ...]
) -> tuple[bytes, dict[str, tuple[StoredArtifactMember, ...]]]:
    members_by_source: dict[str, list[StoredArtifactMember]] = {}
    for part in parts:
        members_by_source.setdefault(part.source_id, []).append(
            StoredArtifactMember(
                member_id=part.member_id,
                locator=f"{filename}#{part.member_name}",
                ordinal=part.ordinal,
                total=part.total,
                byte_count=len(part.value),
                sha256=_sha256(part.value),
            )
        )
    manifest = {
        "schema_version": 1,
        "volume": filename,
        "content_kind": "safe-derived-document-records",
        "entries": [
            {
                "source_id": source_id,
                "whole_byte_count": next(
                    part.whole_size for part in parts if part.source_id == source_id
                ),
                "whole_sha256": next(
                    part.whole_hash for part in parts if part.source_id == source_id
                ),
                "parts": [member.to_dict() for member in members_by_source[source_id]],
            }
            for source_id in sorted(members_by_source, key=_utf8_key)
        ],
    }
    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for part in parts:
            archive.addfile(
                _tar_info(part.member_name, len(part.value)), io.BytesIO(part.value)
            )
        manifest_bytes = _canonical_json(manifest)
        archive.addfile(
            _tar_info("reassembly-manifest.json", len(manifest_bytes)),
            io.BytesIO(manifest_bytes),
        )
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", compresslevel=9, mtime=0) as stream:
        stream.write(tar_buffer.getvalue())
    return compressed.getvalue(), {
        source_id: tuple(values) for source_id, values in members_by_source.items()
    }


def _write_archives(
    archive_dir: Path, representations: dict[str, bytes]
) -> dict[str, tuple[StoredArtifactMember, ...]]:
    rendered: list[tuple[str, bytes, dict[str, tuple[StoredArtifactMember, ...]]]] = []
    for index, group in enumerate(_archive_groups(_archive_parts(representations)), start=1):
        filename = f"document-records-{index:03d}.tar.gz"
        data, members = _render_archive(filename, group)
        rendered.append((filename, data, members))
    archive_dir.mkdir(parents=True, exist_ok=True)
    temporary_paths: list[Path] = []
    try:
        for filename, data, _ in rendered:
            descriptor, temporary_name = tempfile.mkstemp(
                prefix=f".{filename}.", suffix=".tmp", dir=archive_dir
            )
            temporary = Path(temporary_name)
            temporary_paths.append(temporary)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
        for temporary, (filename, _, _) in zip(temporary_paths, rendered, strict=True):
            temporary.replace(archive_dir / filename)
        expected = {filename for filename, _, _ in rendered}
        for stale in archive_dir.glob("document-records-*.tar.gz"):
            if stale.name not in expected:
                stale.unlink()
    finally:
        for temporary in temporary_paths:
            temporary.unlink(missing_ok=True)
    combined: dict[str, list[StoredArtifactMember]] = {}
    for _, _, member_map in rendered:
        for source_id, members in member_map.items():
            combined.setdefault(source_id, []).extend(members)
    return {source_id: tuple(members) for source_id, members in combined.items()}


def _source_record(
    snapshot: SnapshotManifest,
    draft: _DraftDocument,
    representation: bytes,
    members: tuple[StoredArtifactMember, ...],
) -> SourceRecord:
    return SourceRecord(
        source_id=draft.source_id,
        source_type=draft.source_type,
        source_locator=draft.source_locator,
        snapshot_id=snapshot.snapshot_id,
        title=draft.title,
        evidence_scope=draft.evidence_scope,  # type: ignore[arg-type]
        claim_authority=draft.claim_authority,  # type: ignore[arg-type]
        recorded_status="captured",
        recorded_at=None,
        raw_hash=draft.raw_hash,
        stored_hash=_sha256(representation),
        raw_archive_locator=None,
        stored_members=members,
        explicit_relations=(),
        case_ids=(),
        classification=draft.classification,
        record_only_reason=draft.record_only_reason,
        availability_status=draft.availability_status,
        privacy_redactions=draft.privacy_redactions,
        parse_status=draft.parse_status,
        payload=draft.payload,
    )


def collect_documents(
    repo: str | Path,
    snapshot: SnapshotManifest,
    archive_dir: str | Path,
) -> tuple[list[SourceRecord], list[DocumentClaim]]:
    """Collect every snapshot-selected document and its stable claim units."""
    repository = Path(repo)
    git = _Git(repository)
    try:
        tree_output = git.run(
            (
                "git",
                "ls-tree",
                "-r",
                "-z",
                "--full-tree",
                snapshot.source_snapshot_head,
            )
        )
    except subprocess.CalledProcessError as error:
        raise ValueError("frozen source tree is unavailable") from error
    tree = _parse_tree(tree_output)

    tracked = tuple(
        sorted(
            (
                item
                for item in snapshot.tracked_files
                if item.collection_rule_id == "document"
            ),
            key=lambda item: _utf8_key(item.path),
        )
    )
    if len({item.path for item in tracked}) != len(tracked):
        raise ValueError("duplicate tracked document path in snapshot")

    external = tuple(
        sorted(snapshot.external_input_files, key=lambda item: _utf8_key(item.path))
    )
    roles = [item.role for item in external]
    if set(roles) != set(_EXTERNAL_MAPPING) or len(roles) != len(_EXTERNAL_MAPPING):
        unsupported = sorted(set(roles) - set(_EXTERNAL_MAPPING))
        if unsupported:
            raise ValueError(f"unsupported external role: {unsupported[0]}")
        raise ValueError("external role set is incomplete or duplicated")
    if len({item.path for item in external}) != len(external):
        raise ValueError("duplicate external input path in snapshot")

    redaction_cache: dict[str, RedactionResult] = {}
    drafts = [
        _tracked_document(git, snapshot, item, tree, redaction_cache)
        for item in tracked
    ] + [_external_document(repository, snapshot, item) for item in external]
    drafts.sort(key=lambda item: _utf8_key(item.source_id))
    source_ids = [item.source_id for item in drafts]
    if len(source_ids) != len(set(source_ids)):
        raise ValueError("duplicate document source ID")
    claim_ids = [claim.claim_id for draft in drafts for claim in draft.claims]
    if len(claim_ids) != len(set(claim_ids)):
        raise ValueError("duplicate document claim ID")

    representations = {
        draft.source_id: _safe_representation(draft) for draft in drafts
    }
    members_by_source = _write_archives(Path(archive_dir), representations)
    sources = [
        _source_record(
            snapshot,
            draft,
            representations[draft.source_id],
            members_by_source[draft.source_id],
        )
        for draft in drafts
    ]
    claims = [claim for draft in drafts for claim in draft.claims]
    claims.sort(key=lambda item: _utf8_key(item.claim_id))
    return sources, claims
