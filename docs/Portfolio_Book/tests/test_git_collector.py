from __future__ import annotations

import hashlib
import io
import subprocess
import tarfile
from pathlib import Path

import pytest

from portfolio_builder.git_collector import collect_git_evidence
from portfolio_builder.models import RefSnapshot, SnapshotManifest


def _run(repo: Path, *args: str, input_bytes: bytes | None = None) -> bytes:
    return subprocess.run(
        args,
        cwd=repo,
        input=input_bytes,
        check=True,
        capture_output=True,
    ).stdout


def _git(repo: Path, *args: str) -> str:
    return _run(repo, "git", *args).decode("utf-8").strip()


def _commit(repo: Path, message: str) -> str:
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", message)
    return _git(repo, "rev-parse", "HEAD")


class GitHistory:
    def __init__(self, root: Path):
        self.root = root
        root.mkdir()
        _git(root, "init", "-b", "main")
        _git(root, "config", "user.name", "Collector Test")
        _git(root, "config", "user.email", "collector@example.invalid")

        (root / "old name.txt").write_text("root\n", encoding="utf-8")
        self.root_commit = _commit(root, "root")

        _git(root, "mv", "old name.txt", "renamed name.txt")
        (root / "binary.dat").write_bytes(b"\x00\x01\xff\x00")
        (root / "line\nbreak\tname.txt").write_text("odd\n", encoding="utf-8")
        self.normal = _commit(root, "rename, binary, and odd path")

        _git(root, "checkout", "-b", "feature")
        (root / "feature.txt").write_text("feature\n", encoding="utf-8")
        self.feature = _commit(root, "feature")

        _git(root, "checkout", "main")
        (root / "main.txt").write_text("main\n", encoding="utf-8")
        self.main = _commit(root, "main")
        _git(root, "merge", "--no-ff", "--no-edit", "feature")
        self.merge = _git(root, "rev-parse", "HEAD")

        _git(root, "checkout", "--detach", self.merge)
        (root / "detached.txt").write_text("detached\n", encoding="utf-8")
        self.detached = _commit(root, "detached frozen head")

        _git(root, "checkout", "main")
        _git(root, "checkout", "-b", "docs/workflow")
        (root / "workflow-only.txt").write_text("excluded\n", encoding="utf-8")
        self.excluded = _commit(root, "workflow tooling")

    def snapshot(self) -> SnapshotManifest:
        semantic_main = RefSnapshot(
            "refs/heads/main", self.main, "commit", None, None, None
        )
        semantic_blob = RefSnapshot(
            "refs/custom/blob",
            _git(self.root, "rev-parse", f"{self.detached}:binary.dat"),
            "blob",
            None,
            None,
            None,
        )
        observed_workflow = RefSnapshot(
            "refs/heads/docs/workflow", self.excluded, "commit", None, None, None
        )
        return SnapshotManifest(
            snapshot_id="SNAP-test",
            started_at="2026-08-01T00:00:00Z",
            local_completed_at="2026-08-01T00:00:01Z",
            finalized_at=None,
            source_boundary_sha256="0" * 64,
            source_snapshot_head=self.detached,
            source_snapshot_tree=_git(
                self.root, "rev-parse", f"{self.detached}^{{tree}}"
            ),
            first_excluded_commit=self.excluded,
            first_excluded_parent=self.main,
            workflow_ref="refs/heads/docs/workflow",
            observed_head_sha=self.excluded,
            observed_head_symbolic_target="refs/heads/docs/workflow",
            observed_refs=(observed_workflow,),
            semantic_refs=(semantic_main, semantic_blob),
            excluded_workflow_commit_shas_at_capture=(self.excluded,),
            external_input_files=(),
            legacy_owned_outputs=(),
            tracked_files=(),
            ai_trace_files=(),
            github_window=None,
        )


@pytest.fixture
def history(tmp_path: Path) -> GitHistory:
    return GitHistory(tmp_path / "repo")


def _archive_member(capture, locator: str) -> bytes:
    volume_name, member_name = locator.split("#", 1)
    volume = next(item for item in capture.archive_volumes if item.filename == volume_name)
    with tarfile.open(fileobj=io.BytesIO(volume.data), mode="r:gz") as archive:
        extracted = archive.extractfile(member_name)
        assert extracted is not None
        return extracted.read()


def test_collects_every_root_parent_diff_and_detached_frozen_head(history: GitHistory):
    capture = collect_git_evidence(history.root, history.snapshot())
    commits = {record.source_id: record for record in capture.commit_records}
    diffs = {record.source_id: record for record in capture.diff_records}

    expected_commits = {
        history.root_commit,
        history.normal,
        history.feature,
        history.main,
        history.merge,
        history.detached,
    }
    assert set(commits) == {f"GIT-{sha}" for sha in expected_commits}
    assert f"GIT-{history.excluded}" not in commits
    assert all(history.excluded not in record.source_id for record in capture.records)
    assert capture.excluded_workflow_commit_shas_at_capture == (history.excluded,)

    assert f"GIT-{history.root_commit}-ROOT" in diffs
    assert f"GIT-{history.merge}-P01" in diffs
    assert f"GIT-{history.merge}-P02" in diffs
    assert f"GIT-{history.detached}-P01" in diffs
    assert len(diffs) == 7
    assert sum(
        record.source_id.startswith(f"GIT-{history.merge}-") for record in diffs.values()
    ) == 2

    for record in diffs.values():
        assert record.raw_hash == record.payload["patch_raw_sha256"]
        assert record.stored_hash == record.payload["patch_stored_sha256"]
        assert record.stored_members
        stored = b"".join(
            _archive_member(capture, member.locator)
            for member in sorted(record.stored_members, key=lambda item: item.ordinal)
        )
        assert hashlib.sha256(stored).hexdigest() == record.stored_hash
        assert sum(member.byte_count for member in record.stored_members) == len(stored)


def test_parses_rename_binary_and_whitespace_paths_without_line_splitting(
    history: GitHistory,
):
    capture = collect_git_evidence(history.root, history.snapshot())
    normal = next(
        record
        for record in capture.diff_records
        if record.source_id == f"GIT-{history.normal}-P01"
    )

    statuses = normal.payload["file_statuses"]
    assert {
        (entry["status"], entry.get("old_path"), entry.get("path"))
        for entry in statuses
    } >= {
        ("R100", "old name.txt", "renamed name.txt"),
        ("A", None, "binary.dat"),
        ("A", None, "line\nbreak\tname.txt"),
    }
    numstat = normal.payload["numstat"]
    assert any(entry.get("path") == "line\nbreak\tname.txt" for entry in numstat)
    assert any(
        entry.get("path") == "renamed name.txt"
        and entry.get("old_path") == "old name.txt"
        for entry in numstat
    )
    assert normal.payload["contains_binary"] is True
    assert "binary-patch" in normal.privacy_redactions


def test_uses_only_semantic_refs_and_source_snapshot_head_for_rev_list(
    history: GitHistory, monkeypatch: pytest.MonkeyPatch
):
    calls: list[tuple[tuple[str, ...], bytes | None]] = []
    original = subprocess.run

    def recording_run(args, *positional, **kwargs):
        if args[:2] == ["git", "rev-list"] or args[:2] == ("git", "rev-list"):
            calls.append((tuple(args), kwargs.get("input")))
        return original(args, *positional, **kwargs)

    monkeypatch.setattr(subprocess, "run", recording_run)
    snapshot = history.snapshot()
    collect_git_evidence(history.root, snapshot)

    enumeration = [call for call in calls if "--stdin" in call[0]]
    assert len(enumeration) == 1
    args, stdin = enumeration[0]
    assert args == ("git", "rev-list", "--stdin", "--topo-order")
    assert stdin is not None
    supplied = set(stdin.decode("ascii").splitlines())
    assert supplied == {
        history.main,
        history.detached,
        _git(history.root, "rev-parse", f"{history.detached}:binary.dat"),
    }
    assert history.excluded not in supplied
    assert all("--all" not in args for args, _ in calls)
