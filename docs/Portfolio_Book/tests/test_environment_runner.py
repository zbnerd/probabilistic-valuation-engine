import importlib.util
import subprocess
import sys
from pathlib import Path


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
