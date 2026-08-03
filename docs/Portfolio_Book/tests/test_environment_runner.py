import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest


BOOK_ROOT = Path(__file__).resolve().parents[1]
RUNNER = BOOK_ROOT / "tools" / "run_portfolio_command.py"


def load_runner():
    spec = importlib.util.spec_from_file_location("run_portfolio_command", RUNNER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def assert_no_runner_artifacts():
    for name in (".venv", ".cache", "node_modules"):
        assert not (BOOK_ROOT / name).exists()


def test_runner_success_and_failure_leave_no_repository_artifacts():
    success = subprocess.run(
        [sys.executable, str(RUNNER), "--", "python3", "-c", "print('ok')"],
        cwd=BOOK_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    assert success.returncode == 0, success.stderr
    assert "ok" in success.stdout
    assert_no_runner_artifacts()

    failure = subprocess.run(
        [sys.executable, str(RUNNER), "--", "python3", "-c", "raise SystemExit(7)"],
        cwd=BOOK_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    assert failure.returncode == 7
    assert_no_runner_artifacts()


def test_runner_environment_is_fully_inside_validated_temp_root(monkeypatch, tmp_path):
    runner = load_runner()
    temporary_root = tmp_path / "portfolio-book-test"
    temporary_root.mkdir()
    monkeypatch.setattr(runner.tempfile, "mkdtemp", lambda **_: str(temporary_root))
    calls = []

    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    assert runner.main(["--", "python3", "-c", "pass"]) == 0

    sync_command, sync_kwargs = calls[0]
    assert sync_command == ["uv", "sync", "--frozen"]
    environment = sync_kwargs["env"]
    for key in ("UV_PROJECT_ENVIRONMENT", "UV_CACHE_DIR", "npm_config_cache", "PUPPETEER_CACHE_DIR"):
        assert Path(environment[key]).is_relative_to(temporary_root)
    assert not temporary_root.exists()


def test_cli_version_and_unknown_command():
    version = subprocess.run(
        ["uv", "run", "portfolio-book", "--version"],
        cwd=BOOK_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    assert version.returncode == 0
    assert version.stdout.strip() == "portfolio-book 0.1.0"

    unknown = subprocess.run(
        ["uv", "run", "portfolio-book", "not-a-command"],
        cwd=BOOK_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    assert unknown.returncode == 2
    assert "Traceback" not in unknown.stderr


def prepare_mocked_runner(monkeypatch, tmp_path):
    runner = load_runner()
    book_root = tmp_path / "book"
    book_root.mkdir()
    temporary_root = tmp_path / "portfolio-book-test"
    temporary_root.mkdir()
    monkeypatch.setattr(runner, "BOOK_ROOT", book_root)
    monkeypatch.setattr(runner.tempfile, "mkdtemp", lambda **_: str(temporary_root))
    return runner, book_root, temporary_root


def test_with_node_installs_in_temp_and_exports_exact_mmdc(monkeypatch, tmp_path):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)
    (book_root / "package.json").write_text('{"name":"test"}\n', encoding="utf-8")
    (book_root / "package-lock.json").write_text("{}\n", encoding="utf-8")
    calls = []

    def fake_run(command, **kwargs):
        calls.append((command, kwargs))
        if command == ["npm", "ci"]:
            mmdc = temporary_root / "node_modules" / ".bin" / "mmdc"
            mmdc.parent.mkdir(parents=True)
            mmdc.write_text("#!/bin/sh\n", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    assert runner.main(["--with-node", "--", "python3", "-c", "pass"]) == 0

    command, options = calls[-1]
    expected_mmdc = temporary_root / "node_modules" / ".bin" / "mmdc"
    assert command == ["python3", "-c", "pass"]
    assert options["env"]["MMDC_PATH"] == str(expected_mmdc)
    assert options["env"]["PATH"].split(":", 1)[0] == str(expected_mmdc.parent)
    assert not temporary_root.exists()
    assert not (book_root / "node_modules").exists()


def test_refresh_node_lock_copies_only_generated_lock(monkeypatch, tmp_path):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)
    (book_root / "package.json").write_text('{"name":"test"}\n', encoding="utf-8")

    def fake_run(command, **kwargs):
        assert command == ["npm", "install", "--package-lock-only", "--ignore-scripts"]
        (temporary_root / "package-lock.json").write_text(
            '{"lockfileVersion":3}\n', encoding="utf-8"
        )
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    assert runner.main(["--refresh-node-lock"]) == 0
    assert (book_root / "package-lock.json").read_text(encoding="utf-8") == (
        '{"lockfileVersion":3}\n'
    )
    assert not temporary_root.exists()
    assert not (book_root / "node_modules").exists()


def test_node_modules_mutation_fails_and_still_removes_temp(monkeypatch, tmp_path):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)

    def fake_run(command, **kwargs):
        if command != ["uv", "sync", "--frozen"]:
            node_modules = book_root / "node_modules"
            node_modules.mkdir()
            (node_modules / "unexpected").write_text("created", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="created or changed"):
        runner.main(["--", "python3", "-c", "pass"])
    assert not temporary_root.exists()


def test_existing_node_modules_executable_mode_mutation_is_detected(
    monkeypatch, tmp_path
):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)
    executable = book_root / "node_modules" / ".bin" / "tool"
    executable.parent.mkdir(parents=True)
    executable.write_text("#!/bin/sh\n", encoding="utf-8")
    executable.chmod(0o644)

    def fake_run(command, **kwargs):
        if command != ["uv", "sync", "--frozen"]:
            executable.chmod(0o755)
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="created or changed"):
        runner.main(["--", "python3", "-c", "pass"])
    assert executable.stat().st_mode & 0o777 == 0o755
    assert not temporary_root.exists()


def test_existing_node_modules_symlink_retarget_is_detected(monkeypatch, tmp_path):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)
    node_modules = book_root / "node_modules"
    node_modules.mkdir()
    (node_modules / "target-a").write_text("same bytes", encoding="utf-8")
    (node_modules / "target-b").write_text("same bytes", encoding="utf-8")
    current = node_modules / "current"
    current.symlink_to("target-a")

    def fake_run(command, **kwargs):
        if command != ["uv", "sync", "--frozen"]:
            current.unlink()
            current.symlink_to("target-b")
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="created or changed"):
        runner.main(["--", "python3", "-c", "pass"])
    assert current.readlink() == Path("target-b")
    assert not temporary_root.exists()


def test_existing_node_modules_entry_type_replacement_is_detected(
    monkeypatch, tmp_path
):
    runner, book_root, temporary_root = prepare_mocked_runner(monkeypatch, tmp_path)
    entry = book_root / "node_modules" / "entry"
    entry.parent.mkdir()
    entry.touch()

    def fake_run(command, **kwargs):
        if command != ["uv", "sync", "--frozen"]:
            entry.unlink()
            entry.mkdir()
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    with pytest.raises(RuntimeError, match="created or changed"):
        runner.main(["--", "python3", "-c", "pass"])
    assert entry.is_dir()
    assert not temporary_root.exists()
