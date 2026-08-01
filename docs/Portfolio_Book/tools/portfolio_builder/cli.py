"""Command-line entry point for immutable portfolio evidence capture."""

from __future__ import annotations

import argparse
import subprocess
from collections.abc import Sequence
from pathlib import Path

from . import __version__
from .coverage import capture_snapshot, collect_all, verify_capture_files


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
    repo = Path(arguments.repo).resolve(strict=True)
    output = Path(arguments.output)
    if not output.is_absolute():
        output = repo / output
    boundary = Path(arguments.boundary)
    if not boundary.is_absolute():
        boundary = repo / boundary
    capture_snapshot(repo, boundary, output)
    return 0


def _collect_all(arguments: argparse.Namespace) -> int:
    repo = Path(arguments.repo).resolve(strict=True)
    snapshot = Path(arguments.snapshot)
    output = Path(arguments.output_dir)
    if not snapshot.is_absolute():
        snapshot = repo / snapshot
    if not output.is_absolute():
        output = repo / output
    collect_all(
        repo=repo,
        snapshot_path=snapshot,
        output_dir=output,
        repository_name=arguments.repository,
        staged_output_paths=_staged_paths(repo),
    )
    return 0


def _verify_capture(arguments: argparse.Namespace) -> int:
    repo = Path(arguments.repo).resolve(strict=True)
    snapshot = Path(arguments.snapshot)
    output = Path(arguments.output_dir)
    if not snapshot.is_absolute():
        snapshot = repo / snapshot
    if not output.is_absolute():
        output = repo / output
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
    snapshot.add_argument("--repo", default=".")
    snapshot.add_argument(
        "--boundary", default="docs/Portfolio_Book/source_boundary.json"
    )
    snapshot.add_argument(
        "--output",
        default="docs/Portfolio_Book/output/research/snapshot_manifest.json",
    )
    snapshot.set_defaults(handler=_capture_snapshot)

    collect = commands.add_parser(
        "collect-all", help="collect from one frozen snapshot and reconcile GitHub"
    )
    collect.add_argument("--repo", default=".")
    collect.add_argument(
        "--snapshot",
        default="docs/Portfolio_Book/output/research/snapshot_manifest.json",
    )
    collect.add_argument(
        "--output-dir", default="docs/Portfolio_Book/output/research"
    )
    collect.add_argument(
        "--repository", default="zbnerd/probabilistic-valuation-engine"
    )
    collect.set_defaults(handler=_collect_all)

    verify = commands.add_parser(
        "verify-source-capture", help="reconcile every stored capture ledger and archive"
    )
    verify.add_argument("--repo", default=".")
    verify.add_argument(
        "--snapshot",
        default="docs/Portfolio_Book/output/research/snapshot_manifest.json",
    )
    verify.add_argument(
        "--output-dir", default="docs/Portfolio_Book/output/research"
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
