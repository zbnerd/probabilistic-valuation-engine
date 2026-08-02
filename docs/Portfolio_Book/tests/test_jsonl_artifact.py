from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
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
        ).encode("utf-8")
        + b"\n"
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


def test_indexed_reader_rejects_owned_symlink(tmp_path: Path):
    target = _make_two_shard_artifact(tmp_path)
    payload = json.loads(target.read_text(encoding="utf-8"))
    first = target.parent / payload["shards"][0]["path"]
    preserved = first.read_bytes()
    first.unlink()
    outside = tmp_path / "outside.jsonl.gz"
    outside.write_bytes(preserved)
    os.symlink(outside, first)

    with pytest.raises(JsonlArtifactError, match="symlink"):
        _read(target)


def test_indexed_reader_rejects_mixed_index_and_record_content(tmp_path: Path):
    target = _make_two_shard_artifact(tmp_path)
    target.write_bytes(target.read_bytes() + _line("source-c")[1])

    with pytest.raises(JsonlArtifactError, match="mixed index/record"):
        _read(target)


def test_publication_preserves_unowned_names_through_storage_transitions(tmp_path: Path):
    target = _make_two_shard_artifact(tmp_path)
    preserved = {
        "other-part-001.jsonl.gz": b"other",
        "records-part-1000.jsonl.gz": b"four-digits",
        "unrelated.tar.gz": b"archive",
        ".github-checkpoints": b"checkpoint",
    }
    for name, value in preserved.items():
        (tmp_path / name).write_bytes(value)

    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=[_line("plain")],
        target_bytes=10_000,
        max_compressed_bytes=256,
    )
    assert target.read_bytes() == _line("plain")[1]
    assert not (tmp_path / "records-part-001.jsonl.gz").exists()
    assert not (tmp_path / "records-part-002.jsonl.gz").exists()
    assert {name: (tmp_path / name).read_bytes() for name in preserved} == preserved

    three = [_line(f"source-{number}", "x" * 35) for number in range(3)]
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=three,
        target_bytes=len(three[0][1]),
        max_compressed_bytes=256,
    )
    assert (tmp_path / "records-part-003.jsonl.gz").exists()
    two = [_line(f"replacement-{number}", "x" * 35) for number in range(2)]
    publish_jsonl_artifact(
        target,
        record_type="SourceRecord",
        records=two,
        target_bytes=len(two[0][1]),
        max_compressed_bytes=256,
    )
    assert not (tmp_path / "records-part-003.jsonl.gz").exists()
    assert _read(target)[1] == b"".join(line for _, line in two)
    assert {name: (tmp_path / name).read_bytes() for name in preserved} == preserved


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


def _files(path: Path) -> dict[str, bytes]:
    return {item.name: item.read_bytes() for item in path.iterdir() if item.is_file()}


def test_late_backup_cleanup_failure_restores_deleted_old_names(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = _make_existing_sharded_artifact(tmp_path)
    before = _files(tmp_path)
    original_unlink = artifact_module._publication_unlink
    cleanup_count = 0

    def unlink(path: Path) -> None:
        nonlocal cleanup_count
        if ".bak" in path.name:
            cleanup_count += 1
            if cleanup_count == 2:
                raise OSError("late cleanup injected")
        original_unlink(path)

    monkeypatch.setattr(artifact_module, "_publication_unlink", unlink)
    with pytest.raises(OSError, match="late cleanup injected"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("replacement", "x" * 80)],
            target_bytes=32,
            max_compressed_bytes=256,
        )

    assert _files(tmp_path) == before
    assert not any(".tmp" in item.name or ".bak" in item.name for item in tmp_path.iterdir())


def test_staged_bytes_are_reread_before_publishing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = _make_existing_sharded_artifact(tmp_path)
    before = _files(tmp_path)
    original_temporary_bytes = artifact_module._temporary_bytes

    def corrupt(target_path: Path, value: bytes) -> Path:
        staged = original_temporary_bytes(target_path, value)
        staged.write_bytes(value[:-1])
        return staged

    monkeypatch.setattr(artifact_module, "_temporary_bytes", corrupt)
    with pytest.raises(JsonlArtifactError, match="staged bytes"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("replacement", "x" * 80)],
            target_bytes=32,
            max_compressed_bytes=256,
        )

    assert _files(tmp_path) == before
    assert not any(".tmp" in item.name or ".bak" in item.name for item in tmp_path.iterdir())


def test_public_limits_and_blob_limit_are_hard_bounded(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = tmp_path / "records.jsonl"
    with pytest.raises(JsonlArtifactError, match="target_bytes"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("source")],
            target_bytes=50_000_001,
        )
    with pytest.raises(JsonlArtifactError, match="max_compressed_bytes"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("source")],
            max_compressed_bytes=90_000_001,
        )

    monkeypatch.setattr(artifact_module, "_MAX_PHYSICAL_BLOB_BYTES", 24)
    with pytest.raises(JsonlArtifactError, match="physical blob limit"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("source", "x" * 40)],
            target_bytes=128,
            max_compressed_bytes=128,
        )
    assert not list(tmp_path.iterdir())


def test_completed_shards_are_staged_before_iterator_advances(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    calls: list[bytes] = []
    original_gzip_bytes = artifact_module._gzip_bytes

    def gzip_bytes(value: bytes) -> bytes:
        calls.append(value)
        return original_gzip_bytes(value)

    def records():
        first = _line("source-a", "a" * 35)
        second = _line("source-b", "b" * 35)
        yield first
        yield second
        assert calls, "the completed first shard was retained instead of staged"
        yield _line("source-c", "c" * 35)

    monkeypatch.setattr(artifact_module, "_gzip_bytes", gzip_bytes)
    publish_jsonl_artifact(
        tmp_path / "records.jsonl",
        record_type="SourceRecord",
        records=records(),
        target_bytes=len(_line("source-a", "a" * 35)[1]),
        max_compressed_bytes=256,
    )


def test_prepublication_staging_failures_leave_no_residue(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = _make_existing_sharded_artifact(tmp_path)
    before = _files(tmp_path)
    original_gzip_bytes = artifact_module._gzip_bytes
    gzip_calls = 0

    def too_large_second_shard(value: bytes) -> bytes:
        nonlocal gzip_calls
        gzip_calls += 1
        result = original_gzip_bytes(value)
        return result if gzip_calls == 1 else result + b"x" * 300

    monkeypatch.setattr(artifact_module, "_gzip_bytes", too_large_second_shard)
    with pytest.raises(JsonlArtifactError, match="compressed byte limit"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("first", "a" * 35), _line("second", "b" * 35)],
            target_bytes=len(_line("first", "a" * 35)[1]),
            max_compressed_bytes=256,
        )
    assert _files(tmp_path) == before
    assert not any(".tmp" in item.name or ".bak" in item.name for item in tmp_path.iterdir())

    original_temporary_bytes = artifact_module._temporary_bytes
    temporary_calls = 0

    def fail_second_temporary(target_path: Path, value: bytes) -> Path:
        nonlocal temporary_calls
        temporary_calls += 1
        if temporary_calls == 2:
            raise OSError("temporary write injected")
        return original_temporary_bytes(target_path, value)

    monkeypatch.setattr(artifact_module, "_gzip_bytes", original_gzip_bytes)
    monkeypatch.setattr(artifact_module, "_temporary_bytes", fail_second_temporary)
    with pytest.raises(OSError, match="temporary write injected"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("first", "a" * 35), _line("second", "b" * 35)],
            target_bytes=len(_line("first", "a" * 35)[1]),
            max_compressed_bytes=256,
        )
    assert _files(tmp_path) == before
    assert not any(".tmp" in item.name or ".bak" in item.name for item in tmp_path.iterdir())


def test_temporary_write_failure_removes_the_newly_created_sibling(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = _make_existing_sharded_artifact(tmp_path)
    before = _files(tmp_path)
    original_write = artifact_module._temporary_write

    def fail_after_write(stream, value: bytes) -> None:
        original_write(stream, value)
        raise OSError("temporary flush injected")

    monkeypatch.setattr(artifact_module, "_temporary_write", fail_after_write)
    with pytest.raises(OSError, match="temporary flush injected"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("replacement", "x" * 80)],
            target_bytes=32,
            max_compressed_bytes=256,
        )

    assert _files(tmp_path) == before
    assert not any(".tmp" in item.name or ".bak" in item.name for item in tmp_path.iterdir())


def test_recovery_container_survives_its_final_removal_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    import portfolio_builder.jsonl_artifact as artifact_module

    target = _make_existing_sharded_artifact(tmp_path)
    before = _files(tmp_path)
    original_unlink = artifact_module._publication_unlink
    backup_deletions = 0
    recovery_removals = 0

    def unlink(path: Path) -> None:
        nonlocal backup_deletions, recovery_removals
        if ".bak" in path.name:
            backup_deletions += 1
        if ".recovery" in path.name:
            recovery_removals += 1
            if recovery_removals == 1:
                raise OSError("recovery removal injected")
        original_unlink(path)

    monkeypatch.setattr(artifact_module, "_publication_unlink", unlink)
    with pytest.raises(OSError, match="recovery removal injected"):
        publish_jsonl_artifact(
            target,
            record_type="SourceRecord",
            records=[_line("replacement", "x" * 80)],
            target_bytes=32,
            max_compressed_bytes=256,
        )

    assert backup_deletions >= 1
    assert _files(tmp_path) == before
    assert not any(
        ".tmp" in item.name or ".bak" in item.name or ".recovery" in item.name
        for item in tmp_path.iterdir()
    )
