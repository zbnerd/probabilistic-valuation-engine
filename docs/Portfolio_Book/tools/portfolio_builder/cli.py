"""Command-line entry point for immutable portfolio evidence capture."""

from __future__ import annotations

import argparse
import subprocess
from collections.abc import Sequence
from pathlib import Path

from . import __version__
from .coverage import capture_snapshot, collect_all, verify_capture_files


def _invocation_path(value: str) -> Path:
    """Resolve a CLI path from the directory where portfolio-book was invoked."""
    return Path(value).resolve()


def _staged_paths(repo: Path) -> tuple[str, ...]:
    output = subprocess.run(
        ("git", "diff", "--cached", "--name-only", "-z"),
        cwd=repo,
        check=True,
        capture_output=True,
    ).stdout
    return tuple(
        value.decode("utf-8") for value in output.split(b"\0") if value
    )


def _capture_snapshot(arguments: argparse.Namespace) -> int:
    repo = _invocation_path(arguments.repo).resolve(strict=True)
    output = _invocation_path(arguments.output)
    boundary = _invocation_path(arguments.boundary)
    capture_snapshot(repo, boundary, output)
    return 0


def _collect_all(arguments: argparse.Namespace) -> int:
    repo = _invocation_path(arguments.repo).resolve(strict=True)
    snapshot = _invocation_path(arguments.snapshot)
    output = _invocation_path(arguments.output_dir)
    collect_all(
        repo=repo,
        snapshot_path=snapshot,
        output_dir=output,
        repository_name=arguments.repository,
        staged_output_paths=_staged_paths(repo),
    )
    return 0


def _verify_capture(arguments: argparse.Namespace) -> int:
    repo = _invocation_path(arguments.repo).resolve(strict=True)
    snapshot = _invocation_path(arguments.snapshot)
    output = _invocation_path(arguments.output_dir)
    verify_capture_files(
        repo=repo,
        snapshot_path=snapshot,
        output_dir=output,
        staged_output_paths=_staged_paths(repo),
    )
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="portfolio-book")
    parser.add_argument("--version", action="version", version=f"portfolio-book {__version__}")
    commands = parser.add_subparsers(dest="command", required=True)

    snapshot = commands.add_parser(
        "capture-snapshot", help="freeze local refs, source tree, PDFs, and AI paths"
    )
    snapshot.add_argument(
        "--repo",
        default="../..",
        help="repository root (relative paths use the invocation directory)",
    )
    snapshot.add_argument(
        "--boundary",
        default="source_boundary.json",
        help="source boundary (relative paths use the invocation directory)",
    )
    snapshot.add_argument(
        "--output",
        default="output/research/snapshot_manifest.json",
        help="snapshot destination (relative paths use the invocation directory)",
    )
    snapshot.set_defaults(handler=_capture_snapshot)

    collect = commands.add_parser(
        "collect-all", help="collect from one frozen snapshot and reconcile GitHub"
    )
    collect.add_argument(
        "--repo",
        default="../..",
        help="repository root (relative paths use the invocation directory)",
    )
    collect.add_argument(
        "--snapshot",
        "--manifest",
        dest="snapshot",
        default="output/research/snapshot_manifest.json",
        help="snapshot manifest (relative paths use the invocation directory)",
    )
    collect.add_argument(
        "--output-dir",
        "--output",
        dest="output_dir",
        default="output/research",
        help="capture output directory (relative paths use the invocation directory)",
    )
    collect.add_argument(
        "--repository", default="zbnerd/probabilistic-valuation-engine"
    )
    collect.set_defaults(handler=_collect_all)

    verify = commands.add_parser(
        "verify-source-capture", help="reconcile every stored capture ledger and archive"
    )
    verify.add_argument(
        "--repo",
        default="../..",
        help="repository root (relative paths use the invocation directory)",
    )
    verify.add_argument(
        "--snapshot",
        "--manifest",
        dest="snapshot",
        default="output/research/snapshot_manifest.json",
        help="snapshot manifest (relative paths use the invocation directory)",
    )
    verify.add_argument(
        "--output-dir",
        "--root",
        dest="output_dir",
        default="output/research",
        help="capture root (relative paths use the invocation directory)",
    )
    verify.set_defaults(handler=_verify_capture)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = _parser().parse_args(argv)
    except SystemExit as error:
        return int(error.code)

    handler = getattr(arguments, "handler", None)
    if handler is None:
        return 2
    return int(handler(arguments))
