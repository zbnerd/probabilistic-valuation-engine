from __future__ import annotations

import gzip
import hashlib
import io
import json
import tarfile
from dataclasses import replace
from pathlib import Path

import pytest

from portfolio_builder.ai_trace_collector import collect_ai_traces
from portfolio_builder.models import FileSnapshot, SnapshotManifest


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _snapshot(files: tuple[FileSnapshot, ...]) -> SnapshotManifest:
    return SnapshotManifest(
        snapshot_id="SNAP-AI-TRACE-TEST",
        started_at="2026-08-01T00:00:00Z",
        local_completed_at="2026-08-01T00:00:01Z",
        finalized_at=None,
        source_boundary_sha256="0" * 64,
        source_snapshot_head="1" * 40,
        source_snapshot_tree="2" * 40,
        first_excluded_commit="3" * 40,
        first_excluded_parent="1" * 40,
        workflow_ref="refs/heads/test",
        observed_head_sha="3" * 40,
        observed_head_symbolic_target="refs/heads/test",
        observed_refs=(),
        semantic_refs=(),
        excluded_workflow_commit_shas_at_capture=(),
        external_input_files=(),
        legacy_owned_outputs=(),
        tracked_files=(),
        ai_trace_files=files,
        github_window=None,
    )


def _manifest_file(path: str, value: bytes) -> FileSnapshot:
    return FileSnapshot(path=path, byte_count=len(value), sha256=_sha256(value))


def _write(repo: Path, relative: str, value: bytes) -> None:
    target = repo / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(value)


def _archived_members(archive_dir: Path) -> dict[str, bytes]:
    members: dict[str, bytes] = {}
    for path in sorted(archive_dir.glob("ai-trace-records-*.tar.gz")):
        with tarfile.open(path, "r:gz") as archive:
            for member in archive.getmembers():
                if not member.isfile() or member.name == "reassembly-manifest.json":
                    continue
                stream = archive.extractfile(member)
                assert stream is not None
                members[member.name] = stream.read()
    return members


def test_collects_plain_and_gzip_json_with_authority_and_safe_complete_storage(
    tmp_path: Path,
):
    repo = tmp_path / "repo"
    repo.mkdir()
    values = (
        {
            "timestamp": "2026-06-09T00:00:00Z",
            "role": "assistant",
            "content": "Completed 999 RPS; contact third.party@example.com",
        },
        {
            "timestamp": "2026-06-09T00:00:01Z",
            "tool": "Bash",
            "input": {"command": "./gradlew test", "token": "secret-value"},
        },
        {
            "timestamp": "2026-06-09T00:00:02Z",
            "tool": "Bash",
            "input": {"command": "./gradlew test"},
            "result": "BUILD SUCCESSFUL",
            "exit_code": 0,
            "error": None,
        },
        {
            "timestamp": "2026-06-09T00:00:03Z",
            "tool": "Read",
            "input": {"path": "large.log"},
            "result_preview": "first bytes",
            "truncated": True,
        },
        {
            "timestamp": "2026-06-09T00:00:04Z",
            "tool": "Bash",
            "input": {"command": "false"},
            "error": "command failed",
            "exit_code": 1,
        },
    )
    pretty = b"\n".join(
        json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8") for value in values
    )
    compressed_buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed_buffer, mode="wb", mtime=0) as stream:
        stream.write(pretty)
    compressed = compressed_buffer.getvalue()
    plain_path = "docs/ai-traces/session/tool-use.jsonl"
    gzip_path = "docs/ai-traces/session/tool-use-copy.jsonl.gz"
    ndjson_path = "docs/ai-traces/session/prompts.jsonl"
    ndjson = b"\n".join(
        json.dumps(value, separators=(",", ":")).encode("utf-8")
        for value in values[:2]
    )
    ignored_path = "docs/ai-traces/session/not-in-snapshot.jsonl"
    _write(repo, plain_path, pretty)
    _write(repo, gzip_path, compressed)
    _write(repo, ndjson_path, ndjson)
    _write(repo, ignored_path, b'{"must":"not be read"}')
    snapshot = _snapshot(
        (
            _manifest_file(plain_path, pretty),
            _manifest_file(gzip_path, compressed),
            _manifest_file(ndjson_path, ndjson),
        )
    )

    first_archive = tmp_path / "archives-1"
    records = list(collect_ai_traces(repo, snapshot, first_archive))

    containers = [record for record in records if record.source_type == "ai-trace-file"]
    children = [record for record in records if record.source_type == "ai-trace-entry"]
    assert len(containers) == 3
    assert len(children) == 12
    assert {record.payload["entry_ordinal"] for record in children} == {1, 2, 3, 4, 5}
    assert all(record.parse_status == "parsed" for record in records)
    gzip_container = next(
        record for record in containers if record.source_locator.endswith(".gz")
    )
    assert gzip_container.raw_hash == _sha256(compressed)
    assert gzip_container.payload["gzip_valid"] is True
    assert gzip_container.payload["compression"] == "gzip"

    plain_children = [
        record for record in children if "tool-use.jsonl#bytes=" in record.source_locator
    ]
    by_ordinal = {record.payload["entry_ordinal"]: record for record in plain_children}
    assert by_ordinal[1].claim_authority == "ai-assertion"
    assert by_ordinal[2].recorded_status == "attempted"
    assert by_ordinal[2].claim_authority == "ai-assertion"
    assert by_ordinal[2].payload["limitations"] == ["result-missing"]
    assert by_ordinal[3].claim_authority == "trace-observation"
    assert by_ordinal[3].payload["exit_code"] == 0
    assert by_ordinal[4].claim_authority == "trace-observation"
    assert by_ordinal[4].payload["truncated"] is True
    assert "result-truncated" in by_ordinal[4].payload["limitations"]
    assert by_ordinal[5].claim_authority == "trace-observation"
    assert by_ordinal[5].payload["has_error"] is True
    assert by_ordinal[5].payload["exit_code"] == 1

    archived = b"\n".join(_archived_members(first_archive).values())
    assert b"Completed 999 RPS" in archived
    assert b"BUILD SUCCESSFUL" in archived
    assert b"third.party@example.com" not in archived
    assert b"secret-value" not in archived
    assert b"[REDACTED:third-party-email]" in archived
    assert b"[REDACTED:credential-value]" in archived
    assert ignored_path.encode() not in archived
    assert all(record.stored_members for record in records)
    assert all(record.raw_archive_locator is None for record in records)

    second_archive = tmp_path / "archives-2"
    second = list(collect_ai_traces(repo, snapshot, second_archive))
    assert [record.to_dict() for record in records] == [record.to_dict() for record in second]
    assert [path.read_bytes() for path in sorted(first_archive.iterdir())] == [
        path.read_bytes() for path in sorted(second_archive.iterdir())
    ]


def test_preserves_valid_objects_around_two_malformed_spans_with_byte_offsets(
    tmp_path: Path,
):
    repo = tmp_path / "repo"
    repo.mkdir()
    first = json.dumps({"event": "session_start", "value": "한글"}, indent=2).encode()
    second = json.dumps({"event": "tool_result", "result": "ok"}, indent=2).encode()
    third = json.dumps({"role": "assistant", "content": "done"}, indent=2).encode()
    malformed_one = b"\nBROKEN one\n"
    malformed_two = b"\n{broken two]\n"
    content = first + malformed_one + second + malformed_two + third
    path = "docs/ai-traces/session/session.jsonl"
    _write(repo, path, content)
    snapshot = _snapshot((_manifest_file(path, content),))

    records = list(collect_ai_traces(repo, snapshot, tmp_path / "archives"))

    container = next(record for record in records if record.source_type == "ai-trace-file")
    children = [record for record in records if record.source_type == "ai-trace-entry"]
    assert container.parse_status == "partial"
    assert len(children) == 5
    assert [record.parse_status for record in children] == [
        "parsed",
        "partial",
        "parsed",
        "partial",
        "parsed",
    ]
    expected_spans = []
    cursor = len(first)
    expected_spans.append((cursor, cursor + len(malformed_one)))
    cursor += len(malformed_one) + len(second)
    expected_spans.append((cursor, cursor + len(malformed_two)))
    malformed = [record for record in children if record.parse_status == "partial"]
    assert [
        (record.payload["byte_start"], record.payload["byte_end"])
        for record in malformed
    ] == expected_spans
    assert all(record.classification == "record-only" for record in malformed)
    assert [record.payload["event_type"] for record in children if record.parse_status == "parsed"] == [
        "session_start",
        "tool_result",
        "assistant-message",
    ]


def test_records_markdown_patch_log_binary_and_rejects_manifest_drift(tmp_path: Path):
    repo = tmp_path / "repo"
    repo.mkdir()
    values = {
        "docs/ai-traces/session/summary.md": b"# Summary\n\nCompleted everything.\n",
        "docs/ai-traces/session/git-diff.patch": b"diff --git a/a b/a\n+observed patch\n",
        "docs/ai-traces/session/git-log.txt": b"abc123 immutable log\n",
        "docs/ai-traces/session/binary.log": b"\xff\xfe\x00payload",
    }
    for path, value in values.items():
        _write(repo, path, value)
    snapshot = _snapshot(tuple(_manifest_file(path, value) for path, value in values.items()))

    records = list(collect_ai_traces(repo, snapshot, tmp_path / "archives"))

    children = [record for record in records if record.source_type == "ai-trace-entry"]
    by_name = {record.payload["file_kind"]: record for record in children}
    assert by_name["markdown-summary"].claim_authority == "ai-assertion"
    assert by_name["git-patch"].claim_authority == "trace-observation"
    assert by_name["git-log"].claim_authority == "trace-observation"
    assert by_name["binary"].parse_status == "binary-recorded"
    assert by_name["binary"].classification == "record-only"
    assert by_name["binary"].payload["limitations"] == ["utf8-decode-failed"]

    changed_path = next(iter(values))
    (repo / changed_path).write_bytes(b"changed")
    with pytest.raises(ValueError, match="identity mismatch"):
        list(collect_ai_traces(repo, snapshot, tmp_path / "changed"))

    duplicate = replace(snapshot, ai_trace_files=snapshot.ai_trace_files * 2)
    with pytest.raises(ValueError, match="duplicate AI trace path"):
        list(collect_ai_traces(repo, duplicate, tmp_path / "duplicate"))
