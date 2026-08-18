from __future__ import annotations

import hashlib
import json
import os
import subprocess
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path

import pytest

from portfolio_builder.models import ExternalInputFile, LegacyOwnedOutput, SourceBoundary
from portfolio_builder.snapshot import capture_local_snapshot, write_snapshot_manifest


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


def _write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _commit(repo: Path, message: str) -> str:
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", message)
    return _git(repo, "rev-parse", "HEAD")


class FakeClock:
    def __init__(self, *values: datetime):
        self._values = iter(values)

    def now(self) -> datetime:
        return next(self._values)


class SnapshotRepository:
    def __init__(self, root: Path):
        self.root = root
        root.mkdir(parents=True)
        _git(root, "init", "-b", "docs/workflow")
        _git(root, "config", "user.name", "Snapshot Test")
        _git(root, "config", "user.email", "snapshot@example.invalid")

        legacy_a = b"legacy-a\n"
        legacy_b = b"legacy-b\n"
        self.frozen_document = b"frozen document\n"
        _write(
            root / ".gitignore",
            (
                "docs/Portfolio_Book/guide.pdf\n"
                "docs/Portfolio_Book/resume.pdf\n"
                "docs/Portfolio_Book/portfolio.pdf\n"
                "docs/ai-traces/**\n"
            ).encode(),
        )
        _write(root / "README.md", b"root readme\n")
        _write(root / "docs" / "frozen.md", self.frozen_document)
        _write(root / "docs" / "nul-safe\nname\tunit.md", b"odd path\n")
        _write(root / "src" / "program.py", b"print('source')\n")
        _write(root / "config" / "settings.yaml", b"enabled: true\n")
        _write(
            root / "docs/Portfolio_Book/output/research/legacy-a.md", legacy_a
        )
        _write(
            root / "docs/Portfolio_Book/output/research/legacy-b.md", legacy_b
        )
        self.source = _commit(root, "source")
        self.source_tree = _git(root, "rev-parse", f"{self.source}^{{tree}}")

        _git(root, "checkout", "-b", "unrelated", self.source)
        _write(root / "unrelated.txt", b"unrelated\n")
        self.unrelated = _commit(root, "unrelated")
        _git(root, "tag", "-a", "v-test", "-m", "annotated", self.unrelated)
        _git(root, "notes", "add", "-m", "source note", self.source)
        _git(root, "update-ref", "refs/remotes/origin/main", self.unrelated)
        _git(
            root,
            "symbolic-ref",
            "refs/remotes/origin/HEAD",
            "refs/remotes/origin/main",
        )

        _git(root, "checkout", "docs/workflow")
        _write(root / "tool-one.txt", b"tool one\n")
        self.first_excluded = _commit(root, "tool one")

        self.external_contents = {
            "guide.pdf": b"guide-pdf\x00bytes",
            "resume.pdf": b"resume-pdf\x00bytes",
            "portfolio.pdf": b"portfolio-pdf\x00bytes",
        }
        for name, content in self.external_contents.items():
            path = root / "docs/Portfolio_Book" / name
            _write(path, content)
            path.chmod(0o444)

        self.legacy_rows = (
            self._legacy("docs/Portfolio_Book/output/research/legacy-a.md", legacy_a),
            self._legacy("docs/Portfolio_Book/output/research/legacy-b.md", legacy_b),
        )
        self.boundary = SourceBoundary(
            schema_version=1,
            source_snapshot_head=self.source,
            source_snapshot_tree=self.source_tree,
            first_excluded_commit=self.first_excluded,
            first_excluded_parent=self.source,
            workflow_ref="refs/heads/docs/workflow",
            external_input_files=tuple(
                ExternalInputFile(
                    role=role,
                    path=f"docs/Portfolio_Book/{name}",
                    byte_count=len(self.external_contents[name]),
                    sha256=_sha256(self.external_contents[name]),
                )
                for role, name in (
                    ("renewal-guide", "guide.pdf"),
                    ("id-photo-source-resume", "resume.pdf"),
                    ("legacy-portfolio-reference", "portfolio.pdf"),
                )
            ),
            legacy_owned_outputs=self.legacy_rows,
        )
        self.write_boundary(self.boundary)
        _write(root / "tool-two.txt", b"tool two\n")
        self.observed_head = _commit(root, "tool two")

        _write(root / "docs/frozen.md", b"mutable worktree bytes\n")
        _write(root / "docs/ai-traces/session/result.log", b"exit=0\n")
        _write(root / "docs/ai-traces/session/.env", b"SECRET=must-not-read\n")
        escaping_target = root.parent / "outside-trace.txt"
        _write(escaping_target, b"outside\n")
        os.symlink(escaping_target, root / "docs/ai-traces/session/outside-link")

    def _legacy(self, path: str, content: bytes) -> LegacyOwnedOutput:
        oid = _git(self.root, "rev-parse", f"{self.source}:{path}")
        return LegacyOwnedOutput(path, oid, _sha256(content))

    def write_boundary(self, boundary: SourceBoundary) -> None:
        path = self.root / "docs/Portfolio_Book/source_boundary.json"
        _write(
            path,
            (
                json.dumps(boundary.to_dict(), ensure_ascii=False, indent=2) + "\n"
            ).encode("utf-8"),
        )

    def clock(self) -> FakeClock:
        return FakeClock(
            datetime(2026, 8, 1, 10, 0, tzinfo=UTC),
            datetime(2026, 8, 1, 10, 1, tzinfo=UTC),
        )


@pytest.fixture
def snapshot_repo(tmp_path: Path) -> SnapshotRepository:
    return SnapshotRepository(tmp_path / "repo")


def test_capture_freezes_refs_tree_external_inputs_legacy_and_ai_traces(
    snapshot_repo: SnapshotRepository,
):
    repository = snapshot_repo
    before_status = _git(repository.root, "status", "--porcelain=v1", "-uall")
    before_modes = {
        item.path: (repository.root / item.path).stat().st_mode
        for item in repository.boundary.external_input_files
    }

    manifest = capture_local_snapshot(
        repository.root, repository.boundary, repository.clock()
    )

    assert manifest.started_at == "2026-08-01T10:00:00Z"
    assert manifest.local_completed_at == "2026-08-01T10:01:00Z"
    assert manifest.finalized_at is None
    assert manifest.github_window is None
    assert manifest.observed_head_sha == repository.observed_head
    assert manifest.observed_head_symbolic_target == "refs/heads/docs/workflow"
    assert manifest.excluded_workflow_commit_shas_at_capture == (
        repository.first_excluded,
        repository.observed_head,
    )

    observed = {item.refname: item for item in manifest.observed_refs}
    assert [item.refname for item in manifest.observed_refs] == sorted(
        observed, key=lambda value: value.encode("utf-8")
    )
    assert observed["HEAD"].object_sha == repository.observed_head
    assert observed["HEAD"].symbolic_target == "refs/heads/docs/workflow"
    assert observed["refs/heads/docs/workflow"].object_sha == repository.observed_head
    assert observed["refs/heads/unrelated"].object_sha == repository.unrelated
    assert observed["refs/remotes/origin/HEAD"].symbolic_target == (
        "refs/remotes/origin/main"
    )
    assert observed["refs/remotes/origin/main"].object_sha == repository.unrelated
    assert observed["refs/tags/v-test"].object_type == "tag"
    assert observed["refs/tags/v-test"].peeled_sha == repository.unrelated
    assert observed["refs/notes/commits"].object_type == "commit"

    semantic = {item.refname: item for item in manifest.semantic_refs}
    assert "HEAD" not in semantic
    assert semantic["refs/heads/docs/workflow"].object_sha == repository.source
    for refname, snapshot in semantic.items():
        if refname != "refs/heads/docs/workflow":
            assert snapshot == observed[refname]
    semantic_reachable = set(
        _git(
            repository.root,
            "rev-list",
            *[snapshot.object_sha for snapshot in manifest.semantic_refs],
        ).splitlines()
    )
    assert repository.first_excluded not in semantic_reachable
    assert repository.observed_head not in semantic_reachable
    assert repository.unrelated in semantic_reachable

    tracked = {item.path: item for item in manifest.tracked_files}
    frozen = tracked["docs/frozen.md"]
    assert frozen.object_sha == _git(
        repository.root, "rev-parse", f"{repository.source}:docs/frozen.md"
    )
    assert frozen.collection_rule_id == "document"
    assert tracked["docs/nul-safe\nname\tunit.md"].collection_rule_id == "document"
    assert tracked["README.md"].collection_rule_id == "document"
    assert tracked["config/settings.yaml"].collection_rule_id == "document"
    assert tracked["src/program.py"].collection_rule_id == "non-document"
    assert list(tracked) == sorted(tracked, key=lambda value: value.encode("utf-8"))
    expected_tree_count = len(
        _run(
            repository.root,
            "git",
            "ls-tree",
            "-r",
            "-z",
            "--full-tree",
            repository.source,
        ).split(b"\0")
    ) - 1
    assert len(manifest.tracked_files) == expected_tree_count

    assert manifest.external_input_files == repository.boundary.external_input_files
    assert manifest.legacy_owned_outputs == repository.boundary.legacy_owned_outputs
    assert len(manifest.ai_trace_files) == 1
    ai_file = manifest.ai_trace_files[0]
    assert ai_file.path == "docs/ai-traces/session/result.log"
    assert ai_file.byte_count == len(b"exit=0\n")
    assert ai_file.sha256 == _sha256(b"exit=0\n")
    assert _git(repository.root, "status", "--porcelain=v1", "-uall") == before_status
    assert {
        item.path: (repository.root / item.path).stat().st_mode
        for item in repository.boundary.external_input_files
    } == before_modes


def test_snapshot_manifest_bytes_are_deterministic(snapshot_repo: SnapshotRepository):
    first = capture_local_snapshot(
        snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
    )
    second = capture_local_snapshot(
        snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
    )
    first_path = snapshot_repo.root / "first.json"
    second_path = snapshot_repo.root / "second.json"

    write_snapshot_manifest(first_path, first)
    write_snapshot_manifest(second_path, second)

    assert first == second
    assert first_path.read_bytes() == second_path.read_bytes()
    assert first_path.read_bytes().endswith(b"\n")


def test_rejects_wrong_first_parent(snapshot_repo: SnapshotRepository):
    _git(snapshot_repo.root, "checkout", "unrelated")
    _write(snapshot_repo.root / "wrong-parent.txt", b"wrong parent\n")
    wrong_first = _commit(snapshot_repo.root, "wrong first parent")
    _git(snapshot_repo.root, "checkout", "docs/workflow")
    boundary = replace(
        snapshot_repo.boundary, first_excluded_commit=wrong_first
    )
    snapshot_repo.write_boundary(boundary)

    with pytest.raises(ValueError, match="first excluded parent"):
        capture_local_snapshot(snapshot_repo.root, boundary, snapshot_repo.clock())


def test_rejects_merge_in_excluded_chain(snapshot_repo: SnapshotRepository):
    _git(
        snapshot_repo.root,
        "merge",
        "--no-ff",
        "--no-edit",
        "refs/heads/unrelated",
    )

    with pytest.raises(ValueError, match="strictly linear"):
        capture_local_snapshot(
            snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
        )


def test_rejects_second_ref_into_excluded_chain(snapshot_repo: SnapshotRepository):
    _git(
        snapshot_repo.root,
        "branch",
        "forbidden",
        snapshot_repo.first_excluded,
    )

    with pytest.raises(ValueError, match="excluded workflow chain"):
        capture_local_snapshot(
            snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
        )


def test_rejects_external_path_escaping_repository(snapshot_repo: SnapshotRepository):
    outside = snapshot_repo.root.parent / "outside.pdf"
    _write(outside, b"outside-pdf")
    external = replace(
        snapshot_repo.boundary.external_input_files[0],
        path="../outside.pdf",
        byte_count=len(b"outside-pdf"),
        sha256=_sha256(b"outside-pdf"),
    )
    boundary = replace(
        snapshot_repo.boundary,
        external_input_files=(
            external,
            *snapshot_repo.boundary.external_input_files[1:],
        ),
    )
    snapshot_repo.write_boundary(boundary)

    with pytest.raises(ValueError, match="outside repository"):
        capture_local_snapshot(snapshot_repo.root, boundary, snapshot_repo.clock())


def test_rejects_missing_external_pdf(snapshot_repo: SnapshotRepository):
    path = snapshot_repo.root / snapshot_repo.boundary.external_input_files[0].path
    path.chmod(0o644)
    path.unlink()

    with pytest.raises(ValueError, match="external input"):
        capture_local_snapshot(
            snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
        )


def test_rejects_altered_external_pdf(snapshot_repo: SnapshotRepository):
    path = snapshot_repo.root / snapshot_repo.boundary.external_input_files[0].path
    path.chmod(0o644)
    path.write_bytes(b"altered")

    with pytest.raises(ValueError, match="external input"):
        capture_local_snapshot(
            snapshot_repo.root, snapshot_repo.boundary, snapshot_repo.clock()
        )


@pytest.mark.parametrize("failure", ["absent", "oid", "sha256"])
def test_rejects_absent_or_mismatched_legacy_source_blob(
    snapshot_repo: SnapshotRepository, failure: str
):
    legacy = snapshot_repo.boundary.legacy_owned_outputs[0]
    if failure == "absent":
        legacy = replace(legacy, path="docs/missing-legacy.md")
    elif failure == "oid":
        legacy = replace(legacy, git_blob_oid="0" * 40)
    else:
        legacy = replace(legacy, sha256="0" * 64)
    boundary = replace(
        snapshot_repo.boundary,
        legacy_owned_outputs=(legacy, *snapshot_repo.boundary.legacy_owned_outputs[1:]),
    )
    snapshot_repo.write_boundary(boundary)

    with pytest.raises(ValueError, match="legacy owned output"):
        capture_local_snapshot(snapshot_repo.root, boundary, snapshot_repo.clock())
