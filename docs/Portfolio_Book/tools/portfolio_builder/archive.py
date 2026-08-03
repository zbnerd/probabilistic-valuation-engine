"""Deterministic, size-bounded archives for safe stored Git patches."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import tarfile
from dataclasses import dataclass

from .models import StoredArtifactMember


PATCH_PART_BYTES = 8_000_000
VOLUME_UNCOMPRESSED_BYTES = 50_000_000
DEFAULT_MAX_VOLUME_BYTES = 90_000_000
_SAFE_SOURCE_ID = re.compile(r"[A-Za-z0-9._-]+\Z")


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


@dataclass(frozen=True, slots=True)
class PatchEntry:
    """One already-safe patch representation ready for durable storage."""

    source_id: str
    stored_bytes: bytes
    stored_hash: str | None = None

    def __post_init__(self) -> None:
        if not _SAFE_SOURCE_ID.fullmatch(self.source_id):
            raise ValueError(f"unsafe patch source_id: {self.source_id!r}")
        if not isinstance(self.stored_bytes, bytes):
            raise TypeError("stored patch bytes must be bytes")
        actual = _sha256(self.stored_bytes)
        if self.stored_hash is not None and self.stored_hash != actual:
            raise ValueError(f"stored hash mismatch for {self.source_id}")


@dataclass(frozen=True, slots=True)
class ArchiveVolume:
    """One complete deterministic ``tar.gz`` volume held in memory."""

    filename: str
    data: bytes
    sha256: str
    byte_count: int
    uncompressed_patch_bytes: int
    members: tuple[StoredArtifactMember, ...]
    reassembly_manifest: dict[str, object]


@dataclass(frozen=True, slots=True)
class _PatchPart:
    source_id: str
    whole_byte_count: int
    whole_sha256: str
    ordinal: int
    total: int
    value: bytes

    @property
    def member_id(self) -> str:
        return f"{self.source_id}-part-{self.ordinal:03d}"

    @property
    def member_name(self) -> str:
        return f"patches/{self.member_id}.patch"

    @property
    def sha256(self) -> str:
        return _sha256(self.value)


def _split_entries(entries: tuple[PatchEntry, ...]) -> tuple[_PatchPart, ...]:
    parts: list[_PatchPart] = []
    for entry in entries:
        chunks = tuple(
            entry.stored_bytes[offset : offset + PATCH_PART_BYTES]
            for offset in range(0, len(entry.stored_bytes), PATCH_PART_BYTES)
        ) or (b"",)
        whole_hash = _sha256(entry.stored_bytes)
        for ordinal, chunk in enumerate(chunks, start=1):
            parts.append(
                _PatchPart(
                    source_id=entry.source_id,
                    whole_byte_count=len(entry.stored_bytes),
                    whole_sha256=whole_hash,
                    ordinal=ordinal,
                    total=len(chunks),
                    value=chunk,
                )
            )
    return tuple(parts)


def _initial_groups(parts: tuple[_PatchPart, ...]) -> list[tuple[_PatchPart, ...]]:
    groups: list[tuple[_PatchPart, ...]] = []
    current: list[_PatchPart] = []
    current_bytes = 0
    for part in parts:
        if current and current_bytes + len(part.value) > VOLUME_UNCOMPRESSED_BYTES:
            groups.append(tuple(current))
            current = []
            current_bytes = 0
        current.append(part)
        current_bytes += len(part.value)
    if current:
        groups.append(tuple(current))
    return groups


def _tar_info(name: str, byte_count: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.size = byte_count
    info.mtime = 0
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mode = 0o644
    return info


def _manifest_for(
    filename: str, parts: tuple[_PatchPart, ...]
) -> tuple[dict[str, object], tuple[StoredArtifactMember, ...]]:
    members = tuple(
        StoredArtifactMember(
            member_id=part.member_id,
            locator=f"{filename}#{part.member_name}",
            ordinal=part.ordinal,
            total=part.total,
            byte_count=len(part.value),
            sha256=part.sha256,
        )
        for part in parts
    )
    by_source: dict[str, list[StoredArtifactMember]] = {}
    identities: dict[str, tuple[int, str]] = {}
    for part, member in zip(parts, members, strict=True):
        by_source.setdefault(part.source_id, []).append(member)
        identities[part.source_id] = (part.whole_byte_count, part.whole_sha256)
    entries = []
    for source_id in sorted(by_source, key=lambda value: value.encode("utf-8")):
        whole_byte_count, whole_sha256 = identities[source_id]
        entries.append(
            {
                "source_id": source_id,
                "whole_byte_count": whole_byte_count,
                "whole_sha256": whole_sha256,
                "parts": [
                    member.to_dict()
                    for member in sorted(
                        by_source[source_id], key=lambda item: item.ordinal
                    )
                ],
            }
        )
    return (
        {
            "schema_version": 1,
            "volume": filename,
            "entries": entries,
        },
        members,
    )


def _render_volume(
    filename: str, parts: tuple[_PatchPart, ...]
) -> ArchiveVolume:
    manifest, members = _manifest_for(filename, parts)
    tar_buffer = io.BytesIO()
    with tarfile.open(fileobj=tar_buffer, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for part in parts:
            archive.addfile(_tar_info(part.member_name, len(part.value)), io.BytesIO(part.value))
        manifest_bytes = _canonical_json(manifest)
        archive.addfile(
            _tar_info("reassembly-manifest.json", len(manifest_bytes)),
            io.BytesIO(manifest_bytes),
        )
    compressed = io.BytesIO()
    with gzip.GzipFile(
        fileobj=compressed,
        mode="wb",
        compresslevel=9,
        mtime=0,
    ) as stream:
        stream.write(tar_buffer.getvalue())
    data = compressed.getvalue()
    return ArchiveVolume(
        filename=filename,
        data=data,
        sha256=_sha256(data),
        byte_count=len(data),
        uncompressed_patch_bytes=sum(len(part.value) for part in parts),
        members=members,
        reassembly_manifest=manifest,
    )


def write_patch_volumes(
    entries,
    max_bytes: int = DEFAULT_MAX_VOLUME_BYTES,
) -> tuple[ArchiveVolume, ...]:
    """Build canonical archives, recursively bisecting compressed oversize groups.

    Input bytes must already be safe to publish.  Whole patches are divided at
    exactly 8,000,000 stored bytes, groups contain at most 50,000,000 patch
    bytes, and every returned gzip is bounded by ``max_bytes``.
    """
    if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or max_bytes <= 0:
        raise ValueError("max_bytes must be a positive integer")
    normalized = tuple(sorted(tuple(entries), key=lambda item: item.source_id.encode("utf-8")))
    if not all(isinstance(entry, PatchEntry) for entry in normalized):
        raise TypeError("archive entries must be PatchEntry values")
    source_ids = [entry.source_id for entry in normalized]
    if len(source_ids) != len(set(source_ids)):
        raise ValueError("duplicate patch source_id")
    if not normalized:
        return ()

    groups = _initial_groups(_split_entries(normalized))
    while True:
        rendered = tuple(
            _render_volume(f"commit-diffs-{index:03d}.tar.gz", group)
            for index, group in enumerate(groups, start=1)
        )
        oversized = next(
            (index for index, volume in enumerate(rendered) if volume.byte_count > max_bytes),
            None,
        )
        if oversized is None:
            return rendered
        group = groups[oversized]
        if len(group) == 1:
            raise ValueError(
                "single archive member exceeds compressed volume limit: "
                f"{group[0].member_id}"
            )
        midpoint = len(group) // 2
        groups[oversized : oversized + 1] = [group[:midpoint], group[midpoint:]]
