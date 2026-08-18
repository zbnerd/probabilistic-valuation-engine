# Sharded Evidence Ledgers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store every oversized canonical evidence ledger as deterministic, strictly verified gzip shards behind its unchanged logical `.jsonl` path, while preserving exhaustive record order and exact coverage/staging traceability.

**Architecture:** A new model-independent `jsonl_artifact` module owns deterministic shard rendering, strict index validation, and rollback-safe multi-file publication. `canonical_io` remains the only model-aware entry point: it validates the complete source/relation universe, renders canonical lines into the artifact layer, and reconstructs the same ordered models from plain or indexed storage. Capture orchestration records each physical artifact descriptor in the coverage manifest and verifies that the staged shard union is exactly the union owned by the five logical capture ledgers.

**Tech Stack:** Python 3.12, standard-library `dataclasses`, `gzip`, `hashlib`, `json`, `os`, `pathlib`, `re`, and `tempfile`; pytest 9.1.1; existing `portfolio_builder` immutable models and CLI.

## Global Constraints

- Preserve every canonical record and its exact global order; never sample, summarize, omit, or reclassify evidence in this storage change.
- Keep the logical paths `source_records.jsonl`, `document_claim_inventory.jsonl`, `pr_inventory.jsonl`, `issue_inventory.jsonl`, and `ai_trace_inventory.jsonl` unchanged for every caller.
- Use plain canonical JSONL when the complete stream is at most `50_000_000` bytes; use `canonical-jsonl-gzip-shards-v1` only when it is larger.
- Use whole record lines, a `50_000_000`-byte uncompressed shard target, gzip level 9 with an empty filename and `mtime=0`, at most 999 shards, and a `90_000_000`-byte compressed limit per shard.
- Every generated Git blob must be smaller than `95_000_000` bytes.
- The one-line logical index, every model line, and every coverage JSON object use UTF-8, sorted keys, compact separators, and exactly one trailing LF.
- Reject missing, duplicated, extra, reordered, corrupted, substituted, wrong-model, path-traversing, symlinked, nonzero-mtime, or oversized shards before returning a trusted ledger.
- Publish a logical ledger as one rollback-safe transaction and touch only the logical file plus `<stem>-part-[0-9][0-9][0-9].jsonl.gz` files owned by that stem.
- Do not add Git LFS, third-party compression dependencies, raw external PDFs, secrets, contact details, raw patches, or raw AI-trace payloads.
- Keep the frozen snapshot, 20,656 GitHub checkpoints, 13 GitHub archive volumes, 11 commit-diff volumes, 12 document volumes, and 2 AI-trace volumes unchanged; do not run `capture-snapshot` again.
- Tests must inject small byte limits and use temporary paths; unit tests must not allocate production-sized ledgers.
- Implement each task test-first, run its focused tests and the full `docs/Portfolio_Book` suite, then commit only the named tracked files. Never stage `.github-checkpoints` or the real untracked capture outputs during Tasks 1–3.

---

## File Structure

- `docs/Portfolio_Book/tools/portfolio_builder/jsonl_artifact.py`: physical JSONL artifact schema, deterministic gzip codec, strict reader, owned-path discovery, and multi-file transaction.
- `docs/Portfolio_Book/tests/test_jsonl_artifact.py`: storage-format, corruption, confinement, transition, and rollback tests independent of portfolio models.
- `docs/Portfolio_Book/tools/portfolio_builder/canonical_io.py`: model validation, canonical line rendering, transparent plain/sharded model reads, and stable descriptors returned to callers.
- `docs/Portfolio_Book/tests/test_canonical_io.py`: model round trips and identity/relation validation across physical shard boundaries.
- `docs/Portfolio_Book/tools/portfolio_builder/coverage.py`: five-ledger artifact locking, specialized-inventory reconstruction, staged-scope ownership, and capture finalization order.
- `docs/Portfolio_Book/tools/portfolio_builder/cli.py`: read-only NUL-delimited listing of the shard paths locked by a coverage manifest.
- `docs/Portfolio_Book/tests/test_coverage.py`: coverage descriptor, exact staged-union, semantic inventory, and finalize-last integration tests.
- `docs/superpowers/plans/2026-08-01-exhaustive-portfolio-evidence-capture.md`: Task 10 generation/staging commands amended for index-owned shards without recapturing the snapshot.

---

### Task 1: Deterministic physical JSONL artifacts

**Files:**

- Create: `docs/Portfolio_Book/tools/portfolio_builder/jsonl_artifact.py`
- Create: `docs/Portfolio_Book/tests/test_jsonl_artifact.py`

**Interfaces:**

- Consumes: an ordered `Iterable[tuple[str, bytes]]` of `(stable_identity, canonical_line)` values; each line already ends in exactly one `b"\n"`.
- Produces: `JsonlShardDescriptor`, `JsonlArtifactDescriptor`, `publish_jsonl_artifact(...)`, and `read_jsonl_artifact(...)` with the exact signatures below.
- `read_jsonl_artifact` invokes its callback only while performing a complete validation pass and returns a descriptor only after the full physical artifact validates.

- [ ] **Step 1: Write failing deterministic layout and round-trip tests**

Create the test module with a canonical fixture helper and assert both storage modes, boundary preservation, byte identity, and repeatability:

```python
from __future__ import annotations

import gzip
import hashlib
import io
import json
from pathlib import Path

import pytest

from portfolio_builder.jsonl_artifact import (
    SHARDED_JSONL_FORMAT,
    JsonlArtifactError,
    publish_jsonl_artifact,
    read_jsonl_artifact,
)


def _line(identity: str, padding: str = "") -> tuple[str, bytes]:
    value = {"padding": padding, "source_id": identity}
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"
    return identity, encoded


def _read(path: Path, record_type: str = "SourceRecord"):
    seen: list[bytes] = []
    descriptor = read_jsonl_artifact(
        path,
        expected_record_type=record_type,
        consume=lambda _physical, _line_number, line: seen.append(line),
        max_compressed_bytes=256,
    )
    return descriptor, b"".join(seen)


def test_small_artifact_is_exact_plain_jsonl(tmp_path: Path):
    records = [_line("source-a"), _line("source-b")]
    target = tmp_path / "records.jsonl"

    descriptor = publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=records,
        target_bytes=10_000,
        max_compressed_bytes=10_000,
    )

    expected = b"".join(line for _, line in records)
    assert target.read_bytes() == expected
    assert descriptor.storage_mode == "plain"
    assert descriptor.physical_paths == ("records.jsonl",)
    assert _read(target, "SourceRecord")[1] == expected


def test_large_artifact_is_deterministic_indexed_gzip(tmp_path: Path):
    records = [_line("source-a", "a" * 35), _line("source-b", "b" * 35)]
    first = tmp_path / "one" / "records.jsonl"
    second = tmp_path / "two" / "records.jsonl"

    first_descriptor = publish_jsonl_artifact(
        first,
        record_type="SourceRecord",
        records=records,
        target_bytes=len(records[0][1]),
        max_compressed_bytes=256,
    )
    second_descriptor = publish_jsonl_artifact(
        second,
        record_type="SourceRecord",
        records=records,
        target_bytes=len(records[0][1]),
        max_compressed_bytes=256,
    )

    payload = json.loads(first.read_text(encoding="utf-8"))
    assert payload["artifact_format"] == SHARDED_JSONL_FORMAT
    assert [item["path"] for item in payload["shards"]] == [
        "records-part-001.jsonl.gz",
        "records-part-002.jsonl.gz",
    ]
    assert first.read_bytes() == second.read_bytes()
    for name in first_descriptor.physical_paths[1:]:
        assert (first.parent / name).read_bytes() == (second.parent / name).read_bytes()
        assert (first.parent / name).read_bytes()[4:8] == b"\0\0\0\0"
    expected = b"".join(line for _, line in records)
    assert _read(first)[1] == expected
    assert first_descriptor.canonical_sha256 == hashlib.sha256(expected).hexdigest()
    assert first_descriptor.to_dict() == second_descriptor.to_dict()
```

- [ ] **Step 2: Run the focused tests and preserve the RED evidence**

Run:

```bash
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest \
  tests/test_jsonl_artifact.py::test_small_artifact_is_exact_plain_jsonl \
  tests/test_jsonl_artifact.py::test_large_artifact_is_deterministic_indexed_gzip -q
```

Expected: collection fails because `portfolio_builder.jsonl_artifact` does not exist. Record that exact failure in the SDD task ledger before implementation.

- [ ] **Step 3: Implement the immutable schemas and canonical index parser**

Add these public constants, dataclasses, exception, and signatures. `from_dict` must require the exact documented key sets, reject booleans where integers are required, require lowercase 64-character SHA-256 values, and normalize nothing:

```python
SHARDED_JSONL_FORMAT = "canonical-jsonl-gzip-shards-v1"
PLAIN_JSONL_MODE = "plain"
SCHEMA_VERSION = 1
DEFAULT_TARGET_BYTES = 50_000_000
DEFAULT_MAX_COMPRESSED_BYTES = 90_000_000
MAX_SHARDS = 999

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


class JsonlArtifactError(ValueError):
    """A physical canonical-JSONL artifact violates its locked contract."""


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
    def from_dict(cls, value: Mapping[str, object]) -> JsonlShardDescriptor:
        _require_exact_keys(value, _SHARD_KEYS, "shard descriptor")
        result = cls(
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
        return result


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
    def from_dict(cls, value: Mapping[str, object]) -> JsonlArtifactDescriptor:
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
```

Define `publish_jsonl_artifact(path: str | Path, *, record_type: str, records: Iterable[tuple[str, bytes]], target_bytes: int = DEFAULT_TARGET_BYTES, max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES) -> JsonlArtifactDescriptor` and `read_jsonl_artifact(path: str | Path, *, expected_record_type: str, consume: Callable[[Path, int, bytes], None], max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES) -> JsonlArtifactDescriptor`. These functions are the only public filesystem entry points; the following steps supply their complete rendering, validation, and transaction behavior.

The one-line index contains exactly `artifact_format`, `canonical_byte_count`, `canonical_sha256`, `compression`, `logical_path`, `record_count`, `record_type`, `schema_version`, and `shards`. Coverage descriptors additionally contain `storage_mode`, `logical_file_byte_count`, and `logical_file_sha256`; do not place those self-referential physical-index fields inside the index.

- [ ] **Step 4: Implement the minimum deterministic renderer and reader**

Use a bounded `bytearray` for one current shard, flush only whole lines, and render gzip bytes with an empty embedded filename:

```python
def _gzip_bytes(value: bytes) -> bytes:
    destination = io.BytesIO()
    with gzip.GzipFile(
        filename="",
        mode="wb",
        compresslevel=9,
        fileobj=destination,
        mtime=0,
    ) as stream:
        stream.write(value)
    return destination.getvalue()


def _canonical_json_line(value: Mapping[str, object]) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"
```

Validate `target_bytes > 0`, `max_compressed_bytes > 0`, nonempty `record_type`, nonempty unique identities, and lines that end in one LF with no embedded record split. A single line larger than `target_bytes` becomes one shard. Fail before publication when a compressed shard exceeds the limit or the final shard count exceeds 999. For this first GREEN, publish into an empty directory, parse the exact index generated by the writer, and verify the recorded byte/hash/count values; the next RED adds adversarial filesystem layouts and replacement transactions.

For indexed reads, require the exact logical basename and model type and stream each named gzip member; verify its recorded compressed/uncompressed byte counts and hashes, first/last decoded stable identity, and the global record/count/hash before returning the descriptor. For plain reads, stream the logical bytes unchanged and calculate the equivalent `plain` descriptor.

Run the two Step 1 tests again. Expected: PASS.

- [ ] **Step 5: Write failing trust-boundary, transition, and rollback tests**

Add table-driven mutations after creating a two-shard artifact. Each mutation must raise `JsonlArtifactError` and the message must name the logical artifact plus the failed invariant:

```python
def _make_two_shard_artifact(tmp_path: Path) -> Path:
    target = tmp_path / "records.jsonl"
    records = [_line("source-a", "a" * 35), _line("source-b", "b" * 35)]
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=records,
        target_bytes=len(records[0][1]),
        max_compressed_bytes=256,
    )
    return target


def _write_index(target: Path, payload: dict[str, object]) -> None:
    target.write_bytes(
        json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8") + b"\n"
    )


def _mutate_artifact(target: Path, mutation: str) -> None:
    payload = json.loads(target.read_text(encoding="utf-8"))
    shards = payload["shards"]
    first = target.parent / shards[0]["path"]
    if mutation == "missing":
        first.unlink()
        return
    if mutation == "extra":
        (target.parent / "records-part-003.jsonl.gz").write_bytes(first.read_bytes())
        return
    if mutation == "corrupt":
        first.write_bytes(first.read_bytes() + b"corrupt")
        return
    if mutation == "reordered":
        shards[:] = list(reversed(shards))
    elif mutation == "traversal":
        shards[0]["path"] = "../records-part-001.jsonl.gz"
    elif mutation == "wrong-model":
        payload["record_type"] = "DocumentClaim"
    elif mutation == "wrong-count":
        payload["record_count"] += 1
    elif mutation == "wrong-hash":
        payload["canonical_sha256"] = "f" * 64
    elif mutation == "mtime":
        raw = gzip.decompress(first.read_bytes())
        destination = io.BytesIO()
        with gzip.GzipFile(
            filename="", mode="wb", compresslevel=9, fileobj=destination, mtime=1
        ) as stream:
            stream.write(raw)
        changed = destination.getvalue()
        first.write_bytes(changed)
        shards[0]["compressed_byte_count"] = len(changed)
        shards[0]["compressed_sha256"] = hashlib.sha256(changed).hexdigest()
    elif mutation == "oversized":
        changed = first.read_bytes() + b"x" * 300
        first.write_bytes(changed)
        shards[0]["compressed_byte_count"] = len(changed)
        shards[0]["compressed_sha256"] = hashlib.sha256(changed).hexdigest()
    else:
        raise AssertionError(mutation)
    _write_index(target, payload)


@pytest.mark.parametrize(
    "mutation, expected",
    [
        ("missing", "missing shard"),
        ("extra", "extra shard"),
        ("corrupt", "compressed SHA-256"),
        ("reordered", "contiguous shard ordinal"),
        ("traversal", "relative basename"),
        ("wrong-model", "record type"),
        ("wrong-count", "record count"),
        ("wrong-hash", "canonical SHA-256"),
        ("mtime", "gzip mtime"),
        ("oversized", "compressed byte limit"),
    ],
)
def test_indexed_reader_rejects_untrusted_physical_layout(
    tmp_path: Path, mutation: str, expected: str
):
    target = _make_two_shard_artifact(tmp_path)
    _mutate_artifact(target, mutation)
    with pytest.raises(JsonlArtifactError, match=expected):
        _read(target)
```

The helper above rewrites canonical index JSON for metadata mutations, renames/deletes/creates the exact numbered file for layout mutations, and decompresses/recompresses with `mtime=1` for the time mutation. Add separate symlink and mixed-index-plus-record tests because they require different setup.

Also exercise plain-to-sharded, sharded-to-plain, and three-shards-to-two transitions. Place `other-part-001.jsonl.gz`, `records-part-1000.jsonl.gz`, an archive tarball, and `.github-checkpoints` beside the ledger and assert byte-for-byte preservation. Parameterize one-shot failures for old-name backup, new shard publication, logical-index publication, and stale-backup cleanup:

```python
def _make_existing_sharded_artifact(tmp_path: Path) -> Path:
    target = _make_two_shard_artifact(tmp_path)
    (tmp_path / "unrelated.tar.gz").write_bytes(b"unrelated")
    return target


def _inject_one_shot_publication_failure(
    monkeypatch: pytest.MonkeyPatch, fail_operation: str
) -> None:
    import portfolio_builder.jsonl_artifact as artifact_module

    original_replace = artifact_module._publication_replace
    original_unlink = artifact_module._publication_unlink
    failed = False

    def replace(source: Path, destination: Path) -> None:
        nonlocal failed
        is_backup = ".bak" in destination.name
        is_shard = destination.name.endswith(".jsonl.gz") and ".tmp" not in destination.name
        is_index = destination.name == "records.jsonl"
        selected = (
            (fail_operation == "backup" and is_backup)
            or (fail_operation == "shard-publish" and is_shard)
            or (fail_operation == "index-publish" and is_index)
        )
        if selected and not failed:
            failed = True
            raise OSError("injected")
        original_replace(source, destination)

    def unlink(path: Path) -> None:
        nonlocal failed
        if fail_operation == "backup-cleanup" and ".bak" in path.name and not failed:
            failed = True
            raise OSError("injected")
        original_unlink(path)

    monkeypatch.setattr(artifact_module, "_publication_replace", replace)
    monkeypatch.setattr(artifact_module, "_publication_unlink", unlink)


@pytest.mark.parametrize(
    "fail_operation",
    ["backup", "shard-publish", "index-publish", "backup-cleanup"],
)
def test_publication_failure_restores_exact_previous_union(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, fail_operation: str
):
    target = _make_existing_sharded_artifact(tmp_path)
    before = {path.name: path.read_bytes() for path in tmp_path.iterdir() if path.is_file()}
    _inject_one_shot_publication_failure(monkeypatch, fail_operation)

    with pytest.raises(OSError, match="injected"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("replacement", "x" * 80)],
            target_bytes=32,
            max_compressed_bytes=256,
        )

    after = {path.name: path.read_bytes() for path in tmp_path.iterdir() if path.is_file()}
    assert after == before
    assert not any(".tmp" in path.name or ".bak" in path.name for path in tmp_path.iterdir())
```

Run `python3 tools/run_portfolio_command.py -- uv run pytest tests/test_jsonl_artifact.py -q`. Expected: the adversarial cases fail because strict path/layout checks and old-ledger transactions are not implemented yet.

- [ ] **Step 6: Implement strict validation and rollback-safe owned-name publication**

Complete indexed validation by requiring contiguous ordinals from one, unique relative basenames matching `<logical-stem>-part-<ordinal:03d>.jsonl.gz`, gzip header `mtime=0`, and an exact filesystem match between the index list and the owned-name grammar. Reject absolute names, separators, `..`, symlinks, missing files, extra owned shards, mixed index/record content, unknown format/version/compression, and every mutated count/hash before invoking the final successful return.

Render all new bytes to sibling temporary files and validate their hashes/counts before moving an existing published name. Discover old ownership with one anchored regular expression derived from the logical filename. Move the old logical file and all old owned shards to unique sibling backup names, publish new shards in ordinal order, and publish the logical file last. On any `Path.replace` or owned-backup cleanup exception, remove the new-name union, restore every old name, remove transaction residue, and re-raise.

Keep filesystem operations patchable through these private wrappers so tests can inject exactly one failure without replacing `Path` globally:

```python
def _publication_replace(source: Path, destination: Path) -> None:
    source.replace(destination)


def _publication_unlink(path: Path) -> None:
    path.unlink(missing_ok=True)
```

The transaction's recovery path retries cleanup after restoration, and a cleanup error never changes which old names or bytes are restored. Files not matching the anchored ownership grammar are never moved or removed.

- [ ] **Step 7: Run the trust-boundary and transaction tests to verify GREEN**

Run:

```bash
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_jsonl_artifact.py -q
```

Expected: PASS, including every mutation, ownership transition, and injected one-shot failure.

- [ ] **Step 8: Run focused and full verification, then commit**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_jsonl_artifact.py -q
python3 tools/run_portfolio_command.py -- uv run pytest -q
cd ../..
git diff --check
git add docs/Portfolio_Book/tools/portfolio_builder/jsonl_artifact.py \
  docs/Portfolio_Book/tests/test_jsonl_artifact.py
git diff --cached --check
git commit -m "feat(portfolio): add sharded jsonl artifacts"
```

Expected: all focused tests and the full suite pass; the commit contains only the new module and its tests.

---

### Task 2: Transparent canonical model integration

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/canonical_io.py`
- Modify: `docs/Portfolio_Book/tests/test_canonical_io.py`

**Interfaces:**

- Consumes: Task 1's `publish_jsonl_artifact(...) -> JsonlArtifactDescriptor` and callback-based `read_jsonl_artifact(...) -> JsonlArtifactDescriptor`.
- Produces: backward-compatible `read_jsonl(...) -> list[ModelT]`, descriptor-returning `read_jsonl_with_descriptor(...) -> tuple[list[ModelT], JsonlArtifactDescriptor]`, and `write_jsonl(...) -> JsonlArtifactDescriptor`.
- An empty iterable must supply `model_type`; a nonempty homogeneous iterable may infer it. All capture callers in Task 3 pass the model type explicitly.

- [ ] **Step 1: Write failing transparent round-trip tests**

Extend the existing test module with imports for the shard format and descriptor-aware reader, then force physical boundaries with small limits:

```python
def test_write_and_read_large_models_through_unchanged_logical_path(tmp_path):
    records = [
        make_record(source_id=f"source-{index}", stored_members=(), title="x" * 60)
        for index in range(3)
    ]
    target = tmp_path / "source_records.jsonl"

    written = write_jsonl(
        target,
        records,
        model_type=SourceRecord,
        target_bytes=300,
        max_compressed_bytes=10_000,
    )
    loaded, inspected = read_jsonl_with_descriptor(
        target,
        SourceRecord,
        max_compressed_bytes=10_000,
    )

    assert written.storage_mode == "canonical-jsonl-gzip-shards-v1"
    assert loaded == records
    assert inspected == written
    assert read_jsonl(target, SourceRecord, max_compressed_bytes=10_000) == records


def test_small_model_output_remains_byte_identical(tmp_path):
    record = make_record()
    target = tmp_path / "records.jsonl"
    expected = json.dumps(
        record.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"

    descriptor = write_jsonl(target, [record], model_type=SourceRecord)

    assert descriptor.storage_mode == "plain"
    assert target.read_bytes() == expected
```

In the same RED set, add invalid duplicate top-level ID, duplicate nested member ID, absent relation target, changed relation field, and wrong declared-model cases. Add this valid cross-ledger relation case so the implementation cannot validate each physical shard as an isolated universe:

```python
def test_relation_validation_spans_indexed_source_and_claim_ledgers(tmp_path):
    claim = make_claim(claim_id="claim-b", text="x" * 100)
    relation = ExplicitRelation.create(
        owner_source_id="source-a",
        relation_type="supports",
        target_source_id=claim.claim_id,
        evidence_locator="docs/evidence.md#L1-L2",
        evidence_hash=claim.raw_hash,
    )
    source = make_record(
        source_id="source-a",
        stored_members=(),
        explicit_relations=(relation,),
        title="x" * 100,
    )
    source_path = tmp_path / "source_records.jsonl"
    claim_path = tmp_path / "document_claim_inventory.jsonl"

    write_jsonl(
        source_path,
        [source],
        model_type=SourceRecord,
        claim_universe=[claim],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )
    write_jsonl(
        claim_path,
        [claim],
        model_type=DocumentClaim,
        source_universe=[source],
        claim_universe=[claim],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )

    assert read_jsonl(
        source_path,
        SourceRecord,
        claim_universe=[claim],
        max_compressed_bytes=10_000,
    ) == [source]
    assert read_jsonl(
        claim_path,
        DocumentClaim,
        source_universe=[source],
        claim_universe=[claim],
        max_compressed_bytes=10_000,
    ) == [claim]
```

- [ ] **Step 2: Run the focused tests and preserve the RED evidence**

Run:

```bash
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest \
  tests/test_canonical_io.py::test_write_and_read_large_models_through_unchanged_logical_path \
  tests/test_canonical_io.py::test_small_model_output_remains_byte_identical -q
```

Expected: failure because the current API lacks `model_type`, byte-limit parameters, descriptors, and `read_jsonl_with_descriptor`.

- [ ] **Step 3: Replace direct file writes with canonical artifact publication**

Keep `_validate`, `_validate_identity_ledger`, and `validate_relation_ledger` as the single semantic gate. Add a canonical renderer and stable model-identity extractor:

```python
def _canonical_line(item: CanonicalModel) -> bytes:
    return json.dumps(
        item.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8") + b"\n"


def _required_identity(item: object) -> str:
    identity = _identity(item)
    if identity is None:
        raise ValueError(f"canonical model has no stable identity: {type(item).__name__}")
    return identity[1]
```

Change the writer to this exact public shape and return Task 1's descriptor:

```python
def write_jsonl(
    path: str | Path,
    records: Iterable[CanonicalModel],
    *,
    model_type: type[CanonicalModel] | None = None,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    target_bytes: int = DEFAULT_TARGET_BYTES,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> JsonlArtifactDescriptor:
```

Materialize the models once because global relation/identity validation already requires the complete universe, but pass canonical lines to `publish_jsonl_artifact` as a generator so the implementation never retains a second production-sized byte copy. Reject a declared model type that differs from any item. For an empty list, require `model_type` and publish an exact zero-byte plain artifact with count zero.

- [ ] **Step 4: Implement callback-based transparent model reads**

Add this API and make the existing reader return only element zero:

```python
def read_jsonl_with_descriptor(
    path: str | Path,
    model_type: type[ModelT],
    *,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> tuple[list[ModelT], JsonlArtifactDescriptor]:


def read_jsonl(
    path: str | Path,
    model_type: type[ModelT],
    *,
    source_universe: list[SourceRecord] | tuple[SourceRecord, ...] | None = None,
    claim_universe: list[DocumentClaim] | tuple[DocumentClaim, ...] | None = None,
    max_compressed_bytes: int = DEFAULT_MAX_COMPRESSED_BYTES,
) -> list[ModelT]:
    return read_jsonl_with_descriptor(
        path,
        model_type,
        source_universe=source_universe,
        claim_universe=claim_universe,
        max_compressed_bytes=max_compressed_bytes,
    )[0]
```

The callback decodes each UTF-8 line, requires one JSON object, calls `model_type.from_dict`, and appends the model. Wrap parse errors as `ValueError(f"{physical_path}:{line_number}: {error}")`. Only after the physical reader returns its validated descriptor may the function call `_validate(records, source_universe, claim_universe)` and return to the caller.

- [ ] **Step 5: Run global identity and relation validation to verify GREEN**

The tests written in Step 1 first publish valid multi-shard source/claim universes, then construct physically valid but semantically invalid artifacts through the low-level Task 1 publisher. Run:

```bash
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_canonical_io.py -q
```

Expected: PASS for the valid cross-ledger relation and precise failures for every duplicate, absent target, changed field, and model mismatch.

- [ ] **Step 6: Run focused and full verification, then commit**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_canonical_io.py -q
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_jsonl_artifact.py -q
python3 tools/run_portfolio_command.py -- uv run pytest -q
cd ../..
git diff --check
git add docs/Portfolio_Book/tools/portfolio_builder/canonical_io.py \
  docs/Portfolio_Book/tests/test_canonical_io.py
git diff --cached --check
git commit -m "feat(portfolio): resolve sharded canonical ledgers"
```

Expected: old callers still pass, malformed-line errors retain physical path and line information, and the commit contains only canonical model integration and its tests.

---

### Task 3: Lock capture coverage and exact staged ownership

**Files:**

- Modify: `docs/Portfolio_Book/tools/portfolio_builder/coverage.py`
- Modify: `docs/Portfolio_Book/tools/portfolio_builder/cli.py`
- Modify: `docs/Portfolio_Book/tests/test_coverage.py`
- Modify: `docs/superpowers/plans/2026-08-01-exhaustive-portfolio-evidence-capture.md`

**Interfaces:**

- Consumes: Task 2's descriptor-returning `write_jsonl` and `read_jsonl_with_descriptor` APIs.
- Produces: `CaptureCoverageManifest.ledger_artifacts`, five exact `CAPTURE_LEDGER_SPECS`, semantic reconstruction of the three specialized inventories, and `_verify_staged_capture_scope(...)`.
- The final locked descriptor order is UTF-8 basename order, independent of filesystem enumeration.

- [ ] **Step 1: Write failing coverage-descriptor and specialized-ledger tests**

Add a helper that writes all five logical ledgers with a tiny threshold, then assert `collect_all` gathers their returned descriptors before writing coverage and before finalizing the snapshot. Lock the serialized descriptor field and re-read every specialized inventory:

```python
def _plain_descriptor(name: str, record_type: str, record_count: int):
    digest = hashlib.sha256(b"").hexdigest()
    return JsonlArtifactDescriptor(
        schema_version=1,
        storage_mode="plain",
        logical_path=name,
        logical_file_byte_count=0,
        logical_file_sha256=digest,
        record_type=record_type,
        record_count=record_count,
        canonical_byte_count=0,
        canonical_sha256=digest,
        shards=(),
    )


def test_capture_coverage_locks_all_logical_ledger_artifacts(tmp_path: Path):
    artifacts = (
        _plain_descriptor("source_records.jsonl", "SourceRecord", 0),
        _plain_descriptor("document_claim_inventory.jsonl", "DocumentClaim", 0),
        _plain_descriptor("pr_inventory.jsonl", "SourceRecord", 0),
        _plain_descriptor("issue_inventory.jsonl", "SourceRecord", 0),
        _plain_descriptor("ai_trace_inventory.jsonl", "SourceRecord", 0),
    )
    manifest = CaptureCoverageManifest(
        schema_version=1,
        phase="capture",
        status="complete",
        snapshot_id="snap-1",
        source_record_count=3,
        document_claim_count=1,
        relation_count=0,
        archive_count=0,
        sections={},
        limitations=(),
        ledger_artifacts=tuple(sorted(artifacts, key=lambda item: item.logical_path.encode("utf-8"))),
    )

    payload = manifest.to_dict()
    assert [item["logical_path"] for item in payload["ledger_artifacts"]] == [
        "ai_trace_inventory.jsonl",
        "document_claim_inventory.jsonl",
        "issue_inventory.jsonl",
        "pr_inventory.jsonl",
        "source_records.jsonl",
    ]
    assert all(item["logical_file_sha256"] for item in payload["ledger_artifacts"])
```

Add a verification test that changes one valid PR inventory record to a valid non-PR source record and updates its index/hashes through `write_jsonl`; `verify_capture_files` must fail with `specialized inventory mismatch: pr_inventory.jsonl`, proving that physical integrity alone does not establish semantic completeness.

- [ ] **Step 2: Extend coverage models and collect all five descriptors before locking**

Import `JsonlArtifactDescriptor` and `read_jsonl_with_descriptor`. Define the exact ledger contract:

```python
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
```

Append a defaulted field after `limitations` so existing positional fixtures remain valid:

```python
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
```

Serialize it with `"ledger_artifacts": [item.to_dict() for item in self.ledger_artifacts]`. Make `_specialized_inventory(output_dir: Path, name: str, sources: Iterable[SourceRecord], predicate: Callable[[SourceRecord], bool]) -> JsonlArtifactDescriptor`, pass `model_type=SourceRecord`, and preserve its filtered source order. In `collect_all`, first perform semantic coverage, then write source, claim, PR, issue, and AI ledgers and gather every returned descriptor. Sort descriptors by `logical_path.encode("utf-8")`, use `replace(coverage, ledger_artifacts=tuple(descriptors))`, write commit CSV and the updated coverage manifests, and finalize the snapshot last. Pass `model_type=SourceRecord` or `DocumentClaim` explicitly even for empty outputs.

- [ ] **Step 3: Reconstruct and compare all five ledgers during verification**

In `verify_capture_files`, read source and claim through `read_jsonl_with_descriptor`, then read each specialized ledger through the same function with the complete frozen source/claim universe. Compare each ordered specialized list to the exact predicate used by `collect_all`:

```python
def _is_pr_inventory_source(value: SourceRecord) -> bool:
    return value.source_type.startswith("github-") and (
        "/pull" in value.source_locator or value.source_id.startswith("GH-PR-")
    )


def _is_issue_inventory_source(value: SourceRecord) -> bool:
    return value.source_type.startswith("github-") and "/issues/" in value.source_locator


def _is_ai_inventory_source(value: SourceRecord) -> bool:
    return value.source_type.startswith("ai-trace-")
```

Parse locked artifact values only with `JsonlArtifactDescriptor.from_dict`, require exactly the five logical names, and compare `tuple(item.to_dict() for item in locked_artifacts)` byte-for-byte with `tuple(item.to_dict() for item in current_descriptors)`. Reject missing/duplicate/extra descriptor names and any mismatch in storage mode, index physical hash/size, canonical count/hash, record type, ordered shard paths, or shard metadata. Return `replace(recomputed_coverage, ledger_artifacts=current_descriptors)` only after both semantic and physical comparisons pass.

- [ ] **Step 4: Write failing exact staged-union and Git-blob-limit tests**

Build staged paths from `descriptor.physical_paths`. Test success with the full exact set and failures for one omitted shard, one unindexed matching shard, an external PDF, `.gitignore`, `.github-checkpoints/cache.json`, an unowned research file, and a file of exactly `95_000_000` bytes. Use a sparse file for the size case:

```python
def test_staged_scope_requires_exact_index_owned_shard_union(tmp_path: Path):
    output = tmp_path / "docs/Portfolio_Book/output/research"
    output.mkdir(parents=True)
    source = _source(source_id="source-a", source_type="git-commit", members=())
    descriptors = [
        write_jsonl(
            output / SOURCE_NAME,
            [source],
            model_type=SourceRecord,
            target_bytes=32,
            max_compressed_bytes=10_000,
        )
    ]
    for name, model_type in CAPTURE_LEDGER_SPECS:
        if name == SOURCE_NAME:
            continue
        descriptors.append(
            write_jsonl(output / name, [], model_type=model_type)
        )
    archive = output / "commit-diffs-001.tar.gz"
    archive.write_bytes(b"safe archive fixture")
    for name in (
        SNAPSHOT_NAME,
        "commit_inventory.csv",
        COVERAGE_JSON_NAME,
        COVERAGE_MARKDOWN_NAME,
    ):
        (output / name).write_bytes(b"fixture")
    required = tuple(
        path.relative_to(tmp_path).as_posix()
        for path in sorted(output.iterdir(), key=lambda item: item.name.encode("utf-8"))
        if path.is_file()
    )

    _verify_staged_capture_scope(
        repo=tmp_path,
        output_dir=output,
        staged_output_paths=required,
        ledger_artifacts=tuple(descriptors),
        archive_paths=(archive,),
    )

    missing = tuple(path for path in required if not path.endswith("part-001.jsonl.gz"))
    with pytest.raises(CoverageError, match="required staged artifact is absent"):
        _verify_staged_capture_scope(
            repo=tmp_path,
            output_dir=output,
            staged_output_paths=missing,
            ledger_artifacts=tuple(descriptors),
            archive_paths=(archive,),
        )

    extra = output / "source_records-part-999.jsonl.gz"
    extra.write_bytes(b"extra")
    with pytest.raises(CoverageError, match="unindexed shard"):
        _verify_staged_capture_scope(
            repo=tmp_path,
            output_dir=output,
            staged_output_paths=(*required, extra.relative_to(tmp_path).as_posix()),
            ledger_artifacts=tuple(descriptors),
            archive_paths=(archive,),
        )
```

- [ ] **Step 5: Implement the staged capture ownership gate**

Add this helper and call it from `verify_capture_files` after locked descriptors and archives validate:

```python
def _verify_staged_capture_scope(
    *,
    repo: Path,
    output_dir: Path,
    staged_output_paths: tuple[str | Path, ...],
    ledger_artifacts: tuple[JsonlArtifactDescriptor, ...],
    archive_paths: tuple[Path, ...],
) -> None:
```

When the staged tuple is empty, preserve the existing unstaged verification workflow. Otherwise resolve every path relative to `repo` without following ownership outside the repository. The required capture set is the five logical files and every descriptor-owned shard, the snapshot, commit CSV, both coverage manifests, and every validated archive volume. Reject a missing required path or any staged path under `docs/Portfolio_Book/output/research/` outside that exact set. Independently reject any staged `.gitignore`, external input basename/path, `.github-checkpoints` member, or file with `stat().st_size >= 95_000_000`. Do not treat the shard filename glob as ownership; descriptor membership is the only grant.

- [ ] **Step 6: Write failing locked-shard list CLI tests**

Add a CLI test that invokes `main(["list-locked-jsonl-shards", ...])`, captures binary output, and requires exactly the descriptor-owned shard set with no logical files, archives, `.github-checkpoints`, or glob-discovered extras. Add failure cases for a missing shard, a hash/size mismatch, a coverage path outside the repository, and a malformed descriptor:

```python
def test_list_locked_jsonl_shards_emits_only_verified_nul_delimited_paths(
    tmp_path: Path, capfdbinary: pytest.CaptureFixture[bytes]
):
    output = tmp_path / "docs/Portfolio_Book/output/research"
    output.mkdir(parents=True)
    source_line = json.dumps(
        {"padding": "a" * 80, "source_id": "source-a"},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8") + b"\n"
    indexed = publish_jsonl_artifact(
        output / SOURCE_NAME,
        record_type="SourceRecord",
        records=[("source-a", source_line)],
        target_bytes=32,
        max_compressed_bytes=10_000,
    )
    descriptors = [indexed]
    for name, model_type in CAPTURE_LEDGER_SPECS:
        if name == SOURCE_NAME:
            continue
        descriptors.append(
            publish_jsonl_artifact(
                output / name,
                record_type=model_type.__name__,
                records=(),
            )
        )
    coverage_path = output / COVERAGE_JSON_NAME
    coverage_payload = {
        "ledger_artifacts": [
            item.to_dict()
            for item in sorted(
                descriptors, key=lambda value: value.logical_path.encode("utf-8")
            )
        ]
    }
    coverage_path.write_bytes(
        json.dumps(
            coverage_payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8") + b"\n"
    )
    expected = tuple(
        (output / shard.path).relative_to(tmp_path).as_posix()
        for descriptor in descriptors
        for shard in descriptor.shards
    )

    assert cli_module.main([
        "list-locked-jsonl-shards",
        "--coverage", str(coverage_path),
        "--repo", str(tmp_path),
    ]) == 0

    output = capfdbinary.readouterr().out
    assert tuple(value.decode("utf-8") for value in output.split(b"\0") if value) \
        == tuple(sorted(expected, key=lambda value: value.encode("utf-8")))
```

Run `python3 tools/run_portfolio_command.py -- uv run pytest tests/test_coverage.py -q`. Expected: failure because the CLI parser does not know `list-locked-jsonl-shards`.

- [ ] **Step 7: Implement the list command and amend Task 10 staging**

Add `list-locked-jsonl-shards --coverage PATH --repo PATH` to `cli.py`. Its handler parses the coverage JSON through `JsonlArtifactDescriptor.from_dict`, requires exactly the five capture descriptors, checks that each listed shard is a confined relative basename under the coverage file's directory, requires the file to exist and match its locked compressed hash/size, converts each path to a repository-relative POSIX path, sorts by UTF-8 bytes, writes NUL-delimited bytes to `sys.stdout.buffer`, and performs no Git mutation. Run the Step 6 CLI tests again; expected: PASS.

In the evidence-capture plan, keep Step 1's existing snapshot creation text as historical record but add a bold resume note immediately before it: the present run must not execute `capture-snapshot`; it resumes `collect-all` from the existing unfinalized `snapshot_manifest.json` and checkpoint/archive set. Extend the generated-file list with:

```text
docs/Portfolio_Book/output/research/*-part-[0-9][0-9][0-9].jsonl.gz
```

Replace the ledger shard staging portion with this exact index-driven extraction rather than a permissive glob:

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine/.worktrees/exhaustive-portfolio-rebuild
git add docs/Portfolio_Book/output/research/snapshot_manifest.json \
  docs/Portfolio_Book/output/research/source_records.jsonl \
  docs/Portfolio_Book/output/research/document_claim_inventory.jsonl \
  docs/Portfolio_Book/output/research/commit_inventory.csv \
  docs/Portfolio_Book/output/research/pr_inventory.jsonl \
  docs/Portfolio_Book/output/research/issue_inventory.jsonl \
  docs/Portfolio_Book/output/research/ai_trace_inventory.jsonl \
  docs/Portfolio_Book/output/research/capture_coverage_manifest.json \
  docs/Portfolio_Book/output/research/capture_coverage_manifest.md
python3 docs/Portfolio_Book/tools/run_portfolio_command.py -- uv run portfolio-book \
  list-locked-jsonl-shards \
  --coverage docs/Portfolio_Book/output/research/capture_coverage_manifest.json \
  --repo . \
  | git add -f --pathspec-from-file=- --pathspec-file-nul
```

After staging all logical files, descriptor-owned shards, and archive volumes, rerun `verify-source-capture` with the repository's staged path set so `_verify_staged_capture_scope` exercises the publication gate before committing.

- [ ] **Step 8: Run focused, CLI, and full verification, then commit**

Run:

```bash
set -euo pipefail
cd docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_coverage.py -q
python3 tools/run_portfolio_command.py -- uv run pytest tests/test_canonical_io.py tests/test_jsonl_artifact.py -q
python3 tools/run_portfolio_command.py -- uv run pytest -q
cd ../..
git diff --check
git add docs/Portfolio_Book/tools/portfolio_builder/coverage.py \
  docs/Portfolio_Book/tools/portfolio_builder/cli.py \
  docs/Portfolio_Book/tests/test_coverage.py \
  docs/superpowers/plans/2026-08-01-exhaustive-portfolio-evidence-capture.md
git diff --cached --check
git commit -m "feat(portfolio): lock sharded capture ledgers"
```

Expected: all five ledgers round-trip by unchanged logical path, coverage locks their exact physical union, staged-scope failures are precise, snapshot finalization remains last, and all  existing tests pass.

---

## Post-implementation integration gate

After all three task commits pass their independent spec and quality reviews, resume the existing Task 10 from the already-unfinalized frozen snapshot. Do not execute `capture-snapshot` and do not delete or rebuild safe archives/checkpoints.

Run:

```bash
set -euo pipefail
cd /home/maple/probabilistic-valuation-engine/.worktrees/exhaustive-portfolio-rebuild/docs/Portfolio_Book
python3 tools/run_portfolio_command.py -- uv run portfolio-book collect-all \
  --repo ../.. \
  --repository zbnerd/probabilistic-valuation-engine \
  --manifest output/research/snapshot_manifest.json \
  --output output/research
python3 tools/run_portfolio_command.py -- uv run portfolio-book verify-source-capture \
  --repo ../.. \
  --manifest output/research/snapshot_manifest.json \
  --output output/research
```

The integration gate passes only when:

- the finalized snapshot still locks semantic HEAD `6ca9f890f3fa2d22ebd2d5480fe8775308f2ebbd` and first excluded commit `aa2338c54291e5ad2d81673c0bc4fabf4577cec4`;
- exactly 520,127 document claims reconstruct in their original canonical order;
- every source, relation, PR, issue, document, AI-trace, archive-member, and terminal-unavailable coverage delta is zero;
- all five coverage descriptors match the on-disk logical/index/shard bytes;
- every compressed shard is at most `90_000_000` bytes and every staged Git blob is smaller than `95_000_000` bytes;
- the Critical privacy scan passes without staging raw inputs or `.github-checkpoints`;
- `git diff --cached --check` passes on the exact Task 10 output set before the snapshot commit.

If the real integration exposes a storage defect, add the smallest reproducing unit test to the owning task's test module, fix it there, rerun the focused and full suites, and obtain a fresh independent review before resuming Task 10.

## Plan self-review record

- **Spec coverage:** Task 1 covers representation, deterministic compression, strict physical validation, confinement, ownership transitions, and rollback. Task 2 covers unchanged logical APIs plus global model/identity/relation validation. Task 3 covers all five capture ledgers, locked coverage descriptors, exact staging ownership, Git blob limits, and the frozen-snapshot resume gate.
- **Placeholder scan:** Every named public interface has an exact signature and every test helper referenced by a code sample is either defined in that sample or already exists in the named test file.
- **Type consistency:** `JsonlArtifactDescriptor` is the sole descriptor type returned by both physical and canonical writers/readers; `CaptureCoverageManifest.ledger_artifacts` stores the same type; all source/claim universe parameters retain the existing concrete model types.
