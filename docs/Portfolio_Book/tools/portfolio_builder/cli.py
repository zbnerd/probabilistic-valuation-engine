"""Command-line entry point for immutable portfolio evidence capture."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

from . import __version__
from .coverage import (
    CoverageError,
    _locked_ledger_artifacts,
    capture_snapshot,
    collect_all,
    verify_capture_files,
)


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


def _list_locked_jsonl_shards(arguments: argparse.Namespace) -> int:
    repo = _invocation_path(arguments.repo).resolve(strict=True)
    coverage = _invocation_path(arguments.coverage)
    try:
        coverage = coverage.resolve(strict=True)
    except OSError as error:
        raise CoverageError("locked coverage manifest is unavailable") from error
    try:
        coverage.relative_to(repo)
    except ValueError as error:
        raise CoverageError("coverage path is outside repository") from error
    try:
        payload = json.loads(coverage.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CoverageError("locked capture coverage manifest is unreadable") from error
    if not isinstance(payload, dict):
        raise CoverageError("locked capture coverage manifest must be an object")
    artifacts = _locked_ledger_artifacts(payload)
    root = coverage.parent.resolve(strict=True)
    paths: list[str] = []
    for descriptor in artifacts:
        for shard in descriptor.shards:
            relative = Path(shard.path)
            if relative.is_absolute() or relative.parent != Path("."):
                raise CoverageError(f"locked shard path is not a basename: {shard.path}")
            candidate = root / relative
            if candidate.is_symlink():
                raise CoverageError(f"locked shard path escapes coverage root: {shard.path}")
            try:
                physical = candidate.resolve(strict=True)
            except OSError as error:
                raise CoverageError(f"locked shard is unavailable: {shard.path}") from error
            if physical.parent != root:
                raise CoverageError(f"locked shard path escapes coverage root: {shard.path}")
            digest = hashlib.sha256()
            count = 0
            try:
                with physical.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        count += len(chunk)
                        digest.update(chunk)
            except OSError as error:
                raise CoverageError(f"locked shard is unavailable: {shard.path}") from error
            if (
                count != shard.compressed_byte_count
                or digest.hexdigest() != shard.compressed_sha256
            ):
                raise CoverageError(f"locked shard identity mismatch: {shard.path}")
            paths.append(physical.relative_to(repo).as_posix())
    if len(paths) != len(set(paths)):
        raise CoverageError("duplicate locked shard path")
    locked = tuple(sorted(paths, key=lambda value: value.encode("utf-8")))
    if locked:
        sys.stdout.buffer.write(
            b"\0".join(value.encode("utf-8") for value in locked) + b"\0"
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

    list_shards = commands.add_parser(
        "list-locked-jsonl-shards",
        help="list descriptor-owned JSONL shards after physical verification",
    )
    list_shards.add_argument(
        "--coverage",
        required=True,
        help="locked capture coverage manifest",
    )
    list_shards.add_argument(
        "--repo",
        required=True,
        help="repository root",
    )
    list_shards.set_defaults(handler=_list_locked_jsonl_shards)
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
