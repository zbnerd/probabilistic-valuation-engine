"""Deterministic physical storage for canonical JSONL evidence ledgers."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import shutil
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping


SHARDED_JSONL_FORMAT = "canonical-jsonl-gzip-shards-v1"
PLAIN_JSONL_MODE = "plain"
SCHEMA_VERSION = 1
DEFAULT_TARGET_BYTES = 50_000_000
DEFAULT_MAX_COMPRESSED_BYTES = 90_000_000
MAX_SHARDS = 999
_MAX_PHYSICAL_BLOB_BYTES = 95_000_000

_SHARD_KEYS = frozenset({
    "ordinal", "path", "record_count", "first_identity", "last_identity",
    "compressed_byte_count", "compressed_sha256",
    "uncompressed_byte_count", "uncompressed_sha256",
})
_ARTIFACT_KEYS = frozenset({
    "schema_version", "storage_mode", "logical_path",
    "logical_file_byte_count", "logical_file_sha256", "record_type",
    "record_count", "canonical_byte_count", "canonical_sha256", "shards",
})
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


class JsonlArtifactError(ValueError):
    """A physical canonical-JSONL artifact violates its locked contract."""


def _require_exact_keys(
    value: Mapping[str, object], expected: frozenset[str], label: str
) -> None:
    if not isinstance(value, Mapping):
        raise JsonlArtifactError(f"{label} must be an object")
    if set(value) != expected:
        raise JsonlArtifactError(f"{label} has unexpected keys")


def _required_string(value: Mapping[str, object], field: str) -> str:
    result = value[field]
    if not isinstance(result, str) or not result:
        raise JsonlArtifactError(f"{field} must be a nonempty string")
    return result


def _required_nonnegative_int(value: Mapping[str, object], field: str) -> int:
    result = value[field]
    if isinstance(result, bool) or not isinstance(result, int) or result < 0:
        raise JsonlArtifactError(f"{field} must be a nonnegative integer")
    return result


def _required_positive_int(value: Mapping[str, object], field: str) -> int:
    result = _required_nonnegative_int(value, field)
    if result == 0:
        raise JsonlArtifactError(f"{field} must be positive")
    return result


def _required_exact_int(
    value: Mapping[str, object], field: str, expected: int
) -> int:
    result = _required_nonnegative_int(value, field)
    if result != expected:
        raise JsonlArtifactError(f"{field} must equal {expected}")
    return result


def _required_sha256(value: Mapping[str, object], field: str) -> str:
    result = _required_string(value, field)
    if _SHA256.fullmatch(result) is None:
        raise JsonlArtifactError(f"{field} must be lowercase SHA-256")
    return result


def _required_storage_mode(value: Mapping[str, object]) -> str:
    result = _required_string(value, "storage_mode")
    if result not in {PLAIN_JSONL_MODE, SHARDED_JSONL_FORMAT}:
        raise JsonlArtifactError(f"unknown storage mode: {result}")
    return result


@dataclass(frozen=True, slots=True)
class JsonlShardDescriptor:
    ordinal: int
    path: str
    record_count: int
    first_identity: str
    last_identity: str
    compressed_byte_count: int
    compressed_sha256: str
    uncompressed_byte_count: int
    uncompressed_sha256: str

    def to_dict(self) -> dict[str, object]:
        return {
            "ordinal": self.ordinal,
            "path": self.path,
            "record_count": self.record_count,
            "first_identity": self.first_identity,
            "last_identity": self.last_identity,
            "compressed_byte_count": self.compressed_byte_count,
            "compressed_sha256": self.compressed_sha256,
            "uncompressed_byte_count": self.uncompressed_byte_count,
            "uncompressed_sha256": self.uncompressed_sha256,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, object]) -> "JsonlShardDescriptor":
        _require_exact_keys(value, _SHARD_KEYS, "shard descriptor")
        return cls(
            ordinal=_required_positive_int(value, "ordinal"),
            path=_required_string(value, "path"),
            record_count=_required_positive_int(value, "record_count"),
            first_identity=_required_string(value, "first_identity"),
            last_identity=_required_string(value, "last_identity"),
            compressed_byte_count=_required_positive_int(value, "compressed_byte_count"),
            compressed_sha256=_required_sha256(value, "compressed_sha256"),
            uncompressed_byte_count=_required_positive_int(value, "uncompressed_byte_count"),
            uncompressed_sha256=_required_sha256(value, "uncompressed_sha256"),
        )


@dataclass(frozen=True, slots=True)
class JsonlArtifactDescriptor:
    schema_version: int
    storage_mode: str
    logical_path: str
    logical_file_byte_count: int
    logical_file_sha256: str
    record_type: str
    record_count: int
    canonical_byte_count: int
    canonical_sha256: str
    shards: tuple[JsonlShardDescriptor, ...]

    @property
    def physical_paths(self) -> tuple[str, ...]:
        return (self.logical_path, *(item.path for item in self.shards))

    def to_dict(self) -> dict[str, object]:
        return {
            "schema_version": self.schema_version,
            "storage_mode": self.storage_mode,
            "logical_path": self.logical_path,
            "logical_file_byte_count": self.logical_file_byte_count,
            "logical_file_sha256": self.logical_file_sha256,
            "record_type": self.record_type,
            "record_count": self.record_count,
            "canonical_byte_count": self.canonical_byte_count,
            "canonical_sha256": self.canonical_sha256,
            "shards": [item.to_dict() for item in self.shards],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, object]) -> "JsonlArtifactDescriptor":
        _require_exact_keys(value, _ARTIFACT_KEYS, "artifact descriptor")
        raw_shards = value["shards"]
        if not isinstance(raw_shards, list):
            raise JsonlArtifactError("artifact descriptor shards must be a list")
        return cls(
            schema_version=_required_exact_int(value, "schema_version", SCHEMA_VERSION),
            storage_mode=_required_storage_mode(value),
            logical_path=_required_string(value, "logical_path"),
            logical_file_byte_count=_required_nonnegative_int(value, "logical_file_byte_count"),
            logical_file_sha256=_required_sha256(value, "logical_file_sha256"),
            record_type=_required_string(value, "record_type"),
            record_count=_required_nonnegative_int(value, "record_count"),
            canonical_byte_count=_required_nonnegative_int(value, "canonical_byte_count"),
            canonical_sha256=_required_sha256(value, "canonical_sha256"),
            shards=tuple(JsonlShardDescriptor.from_dict(item) for item in raw_shards),
        )


def _gzip_bytes(value: bytes) -> bytes:
    destination = io.BytesIO()
    with gzip.GzipFile(
        filename="", mode="wb", compresslevel=9, fileobj=destination, mtime=0
    ) as stream:
        stream.write(value)
    return destination.getvalue()


def _canonical_json_line(value: Mapping[str, object]) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"


_INDEX_KEYS = frozenset({
    "artifact_format", "canonical_byte_count", "canonical_sha256", "compression",
    "logical_path", "record_count", "record_type", "schema_version", "shards",
})
_IDENTITY_FIELDS = ("source_id", "claim_id", "relation_id", "member_id")


class _NotIndex(Exception):
    pass


def _error(target: Path, invariant: str) -> JsonlArtifactError:
    return JsonlArtifactError(f"{target}: {invariant}")


def _identity_from_line(target: Path, line: bytes) -> str:
    if not line.endswith(b"\n") or b"\n" in line[:-1]:
        raise _error(target, "canonical line must end in exactly one LF")
    try:
        value = json.loads(line)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise _error(target, "record must be JSON") from error
    if not isinstance(value, dict):
        raise _error(target, "record must be an object")
    for field in _IDENTITY_FIELDS:
        identity = value.get(field)
        if isinstance(identity, str) and identity:
            return identity
    raise _error(target, "record has no stable identity")


def _validate_record(identity: str, line: bytes, seen: set[str], target: Path) -> None:
    if not isinstance(identity, str) or not identity:
        raise _error(target, "stable identity must be a nonempty string")
    decoded = _identity_from_line(target, line)
    if decoded != identity:
        raise _error(target, "stable identity does not match canonical line")
    if identity in seen:
        raise _error(target, f"duplicate stable identity: {identity}")
    seen.add(identity)


def _shard_descriptor(
    ordinal: int, logical_stem: str, value: bytes, compressed: bytes
) -> JsonlShardDescriptor:
    lines = value.splitlines(keepends=True)
    identities = [_identity_from_line(Path(logical_stem), line) for line in lines]
    return JsonlShardDescriptor(
        ordinal=ordinal,
        path=f"{logical_stem}-part-{ordinal:03d}.jsonl.gz",
        record_count=len(lines),
        first_identity=identities[0],
        last_identity=identities[-1],
        compressed_byte_count=len(compressed),
        compressed_sha256=hashlib.sha256(compressed).hexdigest(),
        uncompressed_byte_count=len(value),
        uncompressed_sha256=hashlib.sha256(value).hexdigest(),
    )


def _index_bytes(
    target: Path,
    record_type: str,
    canonical_byte_count: int,
    canonical_sha256: str,
    shards: tuple[JsonlShardDescriptor, ...],
) -> bytes:
    return _canonical_json_line({
        "artifact_format": SHARDED_JSONL_FORMAT,
        "canonical_byte_count": canonical_byte_count,
        "canonical_sha256": canonical_sha256,
        "compression": "gzip",
        "logical_path": target.name,
        "record_count": sum(item.record_count for item in shards),
        "record_type": record_type,
        "schema_version": SCHEMA_VERSION,
        "shards": [item.to_dict() for item in shards],
    })


def _temporary_bytes(target: Path, value: bytes) -> Path:
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=target.parent)
    temporary = Path(name)
    with open(descriptor, "wb", closefd=True) as stream:
        stream.write(value)
        stream.flush()
    return temporary


def _stage_bytes(target: Path, value: bytes) -> Path:
    if len(value) >= _MAX_PHYSICAL_BLOB_BYTES:
        raise _error(target, "physical blob limit")
    temporary: Path | None = None
    try:
        temporary = _temporary_bytes(target, value)
        if temporary.is_symlink() or not temporary.is_file() or temporary.read_bytes() != value:
            raise _error(target, "staged bytes")
        return temporary
    except Exception:
        if temporary is not None:
            _cleanup_after_recovery((temporary,))
        raise


def _validate_staged_shard(
    target: Path, temporary: Path, descriptor: JsonlShardDescriptor
) -> None:
    compressed = temporary.read_bytes()
    if len(compressed) != descriptor.compressed_byte_count:
        raise _error(target, "staged compressed byte count")
    if hashlib.sha256(compressed).hexdigest() != descriptor.compressed_sha256:
        raise _error(target, "staged compressed SHA-256")
    if len(compressed) < 10 or compressed[4:8] != b"\0\0\0\0" or compressed[3] & 0x08:
        raise _error(target, "staged gzip header")
    with gzip.GzipFile(fileobj=io.BytesIO(compressed), mode="rb") as stream:
        lines = list(stream)
    raw = b"".join(lines)
    if len(raw) != descriptor.uncompressed_byte_count:
        raise _error(target, "staged uncompressed byte count")
    if hashlib.sha256(raw).hexdigest() != descriptor.uncompressed_sha256:
        raise _error(target, "staged uncompressed SHA-256")
    if len(lines) != descriptor.record_count:
        raise _error(target, "staged record count")
    identities = [_identity_from_line(target, line) for line in lines]
    if identities[0] != descriptor.first_identity or identities[-1] != descriptor.last_identity:
        raise _error(target, "staged identity boundary")


def _stage_shard(
    target: Path, ordinal: int, value: bytes, max_compressed_bytes: int
) -> tuple[JsonlShardDescriptor, tuple[Path, Path]]:
    compressed = _gzip_bytes(value)
    if len(compressed) > max_compressed_bytes:
        raise _error(target, "compressed byte limit")
    descriptor = _shard_descriptor(ordinal, target.stem, value, compressed)
    destination = target.parent / descriptor.path
    temporary = _stage_bytes(destination, compressed)
    try:
        _validate_staged_shard(target, temporary, descriptor)
    except Exception:
        _cleanup_after_recovery((temporary,))
        raise
    return descriptor, (temporary, destination)


def _publication_replace(source: Path, destination: Path) -> None:
    source.replace(destination)


def _publication_unlink(path: Path) -> None:
    path.unlink(missing_ok=True)


def _publication_copy(source: Path, destination: Path) -> None:
    shutil.copyfile(source, destination)


def _owned_shard_pattern(target: Path) -> re.Pattern[str]:
    return re.compile(rf"{re.escape(target.stem)}-part-[0-9]{{3}}\.jsonl\.gz\Z")


def _owned_names(target: Path) -> list[Path]:
    pattern = _owned_shard_pattern(target)
    return sorted(
        (candidate for candidate in target.parent.iterdir() if pattern.fullmatch(candidate.name)),
        key=lambda candidate: candidate.name,
    )


def _cleanup(paths: Iterable[Path]) -> None:
    for path in paths:
        _publication_unlink(path)


def _cleanup_after_recovery(paths: Iterable[Path]) -> None:
    for path in paths:
        try:
            _publication_unlink(path)
        except OSError:
            _publication_unlink(path)


def _publish_transaction(target: Path, staged: list[tuple[Path, Path]]) -> None:
    old = ([target] if target.exists() or target.is_symlink() else []) + _owned_names(target)
    for path in old:
        if path.is_symlink() or not path.is_file():
            raise _error(target, "published owned name is not a regular file")
    token = uuid.uuid4().hex
    backups = [(path, path.with_name(f".{path.name}.{token}.bak")) for path in old]
    recoveries = [
        (source, backup, source.with_name(f".{source.name}.{token}.restore"))
        for source, backup in backups
    ]
    published: list[Path] = []
    try:
        for source, backup in backups:
            _publication_replace(source, backup)
        for _, backup, recovery in recoveries:
            _publication_copy(backup, recovery)
        for temporary, destination in staged:
            _publication_replace(temporary, destination)
            published.append(destination)
        _cleanup(backup for _, backup in backups)
        _cleanup_after_recovery(recovery for _, _, recovery in recoveries)
    except OSError:
        _cleanup_after_recovery([*published, *(temporary for temporary, _ in staged)])
        for source, backup, recovery in reversed(recoveries):
            restore = backup if backup.exists() or backup.is_symlink() else recovery
            if restore.exists() or restore.is_symlink():
                _publication_replace(restore, source)
        _cleanup_after_recovery([
            *(temporary for temporary, _ in staged),
            *(backup for _, backup in backups),
            *(recovery for _, _, recovery in recoveries),
        ])
        raise
    finally:
        _cleanup_after_recovery(temporary for temporary, _ in staged if temporary.exists())


def _descriptor_for_plain(target: Path, record_type: str, raw: bytes, record_count: int) -> JsonlArtifactDescriptor:
    digest = hashlib.sha256(raw).hexdigest()
    return JsonlArtifactDescriptor(
        SCHEMA_VERSION, PLAIN_JSONL_MODE, target.name, len(raw), digest, record_type,
        record_count, len(raw), digest, (),
    )


def publish_jsonl_artifact(
    path: str | Path,
    *,
    record_type: str,
    records: Iterable[tuple[str, bytes]],
    target_bytes: int = DEFAULT_TARGET_BYTES,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> JsonlArtifactDescriptor:
    """Render, validate, and atomically publish a deterministic JSONL artifact."""
    if (
        isinstance(target_bytes, bool)
        or not isinstance(target_bytes, int)
        or target_bytes <= 0
        or target_bytes > DEFAULT_TARGET_BYTES
    ):
        raise JsonlArtifactError("target_bytes must be positive and at most 50_000_000")
    if (
        isinstance(max_compressed_bytes, bool)
        or not isinstance(max_compressed_bytes, int)
        or max_compressed_bytes <= 0
        or max_compressed_bytes > DEFAULT_MAX_COMPRESSED_BYTES
    ):
        raise JsonlArtifactError("max_compressed_bytes must be positive and at most 90_000_000")
    if not isinstance(record_type, str) or not record_type:
        raise JsonlArtifactError("record_type must be a nonempty string")
    target = Path(path)
    seen: set[str] = set()
    canonical_digest = hashlib.sha256()
    canonical_byte_count = 0
    descriptors: list[JsonlShardDescriptor] = []
    staged: list[tuple[Path, Path]] = []
    current = bytearray()
    sharded = False

    def flush_current() -> None:
        nonlocal current
        if not current:
            return
        ordinal = len(descriptors) + 1
        if ordinal > MAX_SHARDS:
            raise _error(target, "shard count exceeds 999")
        descriptor, physical = _stage_shard(
            target, ordinal, bytes(current), max_compressed_bytes
        )
        descriptors.append(descriptor)
        staged.append(physical)
        current.clear()

    try:
        for identity, line in records:
            _validate_record(identity, line, seen, target)
            if current and len(current) + len(line) > target_bytes:
                flush_current()
                sharded = True
            current.extend(line)
            canonical_digest.update(line)
            canonical_byte_count += len(line)
        canonical_sha256 = canonical_digest.hexdigest()
        if not sharded and canonical_byte_count <= target_bytes:
            plain_bytes = bytes(current)
            staged_logical = _stage_bytes(target, plain_bytes)
            descriptor = _descriptor_for_plain(target, record_type, plain_bytes, len(seen))
            _publish_transaction(target, [(staged_logical, target)])
            return descriptor
        flush_current()
        shard_tuple = tuple(descriptors)
        index = _index_bytes(
            target, record_type, canonical_byte_count, canonical_sha256, shard_tuple
        )
        try:
            parsed = json.loads(index)
        except json.JSONDecodeError as error:
            raise _error(target, "generated index JSON") from error
        _require_exact_keys(parsed, _INDEX_KEYS, "index")
        staged.append((_stage_bytes(target, index), target))
        descriptor = JsonlArtifactDescriptor(
            SCHEMA_VERSION, SHARDED_JSONL_FORMAT, target.name, len(index),
            hashlib.sha256(index).hexdigest(), record_type, len(seen), canonical_byte_count,
            canonical_sha256, shard_tuple,
        )
        _publish_transaction(target, staged)
        return descriptor
    except Exception:
        _cleanup_after_recovery(temporary for temporary, _ in staged)
        raise


def _parse_index(target: Path, raw: bytes, expected_record_type: str) -> tuple[tuple[JsonlShardDescriptor, ...], Mapping[str, object]]:
    try:
        payload = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        if b'"artifact_format"' in raw:
            raise _error(target, "mixed index/record content") from error
        raise _NotIndex from error
    if not isinstance(payload, Mapping) or "artifact_format" not in payload:
        raise _NotIndex
    if _canonical_json_line(payload) != raw:
        raise _error(target, "mixed index/record content")
    _require_exact_keys(payload, _INDEX_KEYS, "index")
    if _required_string(payload, "artifact_format") != SHARDED_JSONL_FORMAT:
        raise _error(target, "unknown artifact format")
    if _required_exact_int(payload, "schema_version", SCHEMA_VERSION) != SCHEMA_VERSION:
        raise _error(target, "schema version")
    if _required_string(payload, "compression") != "gzip":
        raise _error(target, "unknown compression")
    if _required_string(payload, "logical_path") != target.name:
        raise _error(target, "logical basename")
    if _required_string(payload, "record_type") != expected_record_type:
        raise _error(target, "record type")
    _required_nonnegative_int(payload, "record_count")
    _required_nonnegative_int(payload, "canonical_byte_count")
    _required_sha256(payload, "canonical_sha256")
    raw_shards = payload["shards"]
    if not isinstance(raw_shards, list) or not raw_shards:
        raise _error(target, "shards must be a nonempty list")
    if len(raw_shards) > MAX_SHARDS:
        raise _error(target, "shard count exceeds 999")
    try:
        shards = tuple(JsonlShardDescriptor.from_dict(item) for item in raw_shards)
    except JsonlArtifactError as error:
        raise _error(target, str(error)) from error
    pattern = _owned_shard_pattern(target)
    expected = {item.path for item in shards}
    if len(expected) != len(shards):
        raise _error(target, "unique shard paths")
    for ordinal, shard in enumerate(shards, start=1):
        if shard.ordinal != ordinal:
            raise _error(target, "contiguous shard ordinal")
        if Path(shard.path).name != shard.path or "/" in shard.path or "\\" in shard.path or ".." in shard.path:
            raise _error(target, "relative basename")
        if not pattern.fullmatch(shard.path) or shard.path != f"{target.stem}-part-{ordinal:03d}.jsonl.gz":
            raise _error(target, "relative basename")
    actual_paths = _owned_names(target)
    actual = {item.name for item in actual_paths}
    if actual - expected:
        raise _error(target, "extra shard")
    if expected - actual:
        raise _error(target, "missing shard")
    if any(item.is_symlink() for item in actual_paths):
        raise _error(target, "symlink shard")
    return shards, payload


def _indexed_pass(target: Path, shards: tuple[JsonlShardDescriptor, ...], payload: Mapping[str, object], max_compressed_bytes: int, consume: Callable[[Path, int, bytes], None] | None) -> tuple[int, int, str]:
    all_seen: set[str] = set()
    total_bytes = 0
    total_records = 0
    digest = hashlib.sha256()
    for shard in shards:
        physical = target.parent / shard.path
        if physical.is_symlink():
            raise _error(target, "symlink shard")
        if not physical.is_file():
            raise _error(target, "missing shard")
        compressed = physical.read_bytes()
        if len(compressed) >= _MAX_PHYSICAL_BLOB_BYTES or len(compressed) > max_compressed_bytes:
            raise _error(target, "compressed byte limit")
        if hashlib.sha256(compressed).hexdigest() != shard.compressed_sha256:
            raise _error(target, "compressed SHA-256")
        if len(compressed) != shard.compressed_byte_count:
            raise _error(target, "compressed byte count")
        if len(compressed) < 10 or compressed[4:8] != b"\0\0\0\0":
            raise _error(target, "gzip mtime")
        if compressed[3] & 0x08:
            raise _error(target, "gzip filename")
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(compressed), mode="rb") as stream:
                lines = list(stream)
        except (EOFError, OSError) as error:
            raise _error(target, "gzip member") from error
        uncompressed = b"".join(lines)
        if len(uncompressed) != shard.uncompressed_byte_count:
            raise _error(target, "uncompressed byte count")
        if hashlib.sha256(uncompressed).hexdigest() != shard.uncompressed_sha256:
            raise _error(target, "uncompressed SHA-256")
        if len(lines) != shard.record_count:
            raise _error(target, "shard record count")
        identities = [_identity_from_line(target, line) for line in lines]
        if identities[0] != shard.first_identity or identities[-1] != shard.last_identity:
            raise _error(target, "shard stable identity boundary")
        for number, (identity, line) in enumerate(zip(identities, lines, strict=True), start=1):
            if identity in all_seen:
                raise _error(target, "duplicate stable identity")
            all_seen.add(identity)
            digest.update(line)
            total_bytes += len(line)
            total_records += 1
            if consume is not None:
                consume(physical, number, line)
    result_hash = digest.hexdigest()
    if total_records != _required_nonnegative_int(payload, "record_count"):
        raise _error(target, "record count")
    if total_bytes != _required_nonnegative_int(payload, "canonical_byte_count"):
        raise _error(target, "canonical byte count")
    if result_hash != _required_sha256(payload, "canonical_sha256"):
        raise _error(target, "canonical SHA-256")
    return total_records, total_bytes, result_hash


def _plain_pass(target: Path, raw: bytes, consume: Callable[[Path, int, bytes], None] | None) -> int:
    seen: set[str] = set()
    lines = raw.splitlines(keepends=True)
    for number, line in enumerate(lines, start=1):
        identity = _identity_from_line(target, line)
        if identity in seen:
            raise _error(target, "duplicate stable identity")
        seen.add(identity)
        if consume is not None:
            consume(target, number, line)
    if b"\n" in raw and (not lines or not lines[-1].endswith(b"\n")):
        raise _error(target, "canonical line must end in exactly one LF")
    return len(lines)


def read_jsonl_artifact(
    path: str | Path,
    *,
    expected_record_type: str,
    consume: Callable[[Path, int, bytes], None],
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> JsonlArtifactDescriptor:
    """Perform a complete validation pass before exposing artifact records."""
    if not isinstance(expected_record_type, str) or not expected_record_type:
        raise JsonlArtifactError("expected_record_type must be a nonempty string")
    if (
        isinstance(max_compressed_bytes, bool)
        or not isinstance(max_compressed_bytes, int)
        or max_compressed_bytes <= 0
        or max_compressed_bytes > DEFAULT_MAX_COMPRESSED_BYTES
    ):
        raise JsonlArtifactError("max_compressed_bytes must be positive and at most 90_000_000")
    target = Path(path)
    if target.is_symlink() or not target.is_file():
        raise _error(target, "logical artifact is not a regular file")
    raw = target.read_bytes()
    if len(raw) >= _MAX_PHYSICAL_BLOB_BYTES:
        raise _error(target, "physical blob limit")
    try:
        shards, payload = _parse_index(target, raw, expected_record_type)
    except _NotIndex:
        count = _plain_pass(target, raw, None)
        descriptor = _descriptor_for_plain(target, expected_record_type, raw, count)
        _plain_pass(target, raw, consume)
        return descriptor
    count, byte_count, digest = _indexed_pass(target, shards, payload, max_compressed_bytes, None)
    descriptor = JsonlArtifactDescriptor(
        SCHEMA_VERSION, SHARDED_JSONL_FORMAT, target.name, len(raw),
        hashlib.sha256(raw).hexdigest(), expected_record_type, count, byte_count, digest, shards,
    )
    _indexed_pass(target, shards, payload, max_compressed_bytes, consume)
    return descriptor
