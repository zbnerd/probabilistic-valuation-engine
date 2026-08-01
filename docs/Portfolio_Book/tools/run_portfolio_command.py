#!/usr/bin/env python3
"""Run portfolio commands without writing dependency artifacts into the repository."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path
from typing import Sequence


BOOK_ROOT = Path(__file__).resolve().parents[1]
TEMP_PREFIX = "portfolio-book-"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--with-node", action="store_true")
    mode.add_argument("--refresh-node-lock", action="store_true")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser


def _validated_temporary_root() -> Path:
    root = Path(tempfile.mkdtemp(prefix=TEMP_PREFIX)).resolve()
    if not root.is_dir() or not root.name.startswith(TEMP_PREFIX) or BOOK_ROOT in root.parents:
        raise RuntimeError(f"refusing unsafe temporary root: {root}")
    return root


def _environment(root: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment.update(
        {
            "UV_PROJECT_ENVIRONMENT": str(root / "uv-environment"),
            "UV_CACHE_DIR": str(root / "uv-cache"),
            "npm_config_cache": str(root / "npm-cache"),
            "PUPPETEER_CACHE_DIR": str(root / "puppeteer-cache"),
        }
    )
    return environment


def _tree_fingerprint(path: Path) -> str | None:
    try:
        root_metadata = path.lstat()
    except FileNotFoundError:
        return None
    digest = hashlib.sha256()

    def update_entry(relative_path: bytes, metadata: os.stat_result) -> None:
        digest.update(len(relative_path).to_bytes(8, "big"))
        digest.update(relative_path)
        digest.update(stat.S_IFMT(metadata.st_mode).to_bytes(4, "big"))
        digest.update(stat.S_IMODE(metadata.st_mode).to_bytes(4, "big"))

    def update_regular_file(member: Path) -> None:
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(member, flags)
        content_digest = hashlib.sha256()
        with os.fdopen(descriptor, "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                content_digest.update(chunk)
        digest.update(content_digest.digest())

    def walk(directory: Path) -> None:
        with os.scandir(directory) as iterator:
            entries = sorted(iterator, key=lambda entry: os.fsencode(entry.name))
        for entry in entries:
            member = Path(entry.path)
            metadata = entry.stat(follow_symlinks=False)
            relative_path = os.fsencode(member.relative_to(path).as_posix())
            update_entry(relative_path, metadata)
            if stat.S_ISLNK(metadata.st_mode):
                link_target = os.readlink(os.fsencode(member))
                digest.update(len(link_target).to_bytes(8, "big"))
                digest.update(link_target)
            elif stat.S_ISREG(metadata.st_mode):
                update_regular_file(member)
            elif stat.S_ISDIR(metadata.st_mode):
                walk(member)

    update_entry(b".", root_metadata)
    if stat.S_ISLNK(root_metadata.st_mode):
        link_target = os.readlink(os.fsencode(path))
        digest.update(len(link_target).to_bytes(8, "big"))
        digest.update(link_target)
    elif stat.S_ISREG(root_metadata.st_mode):
        update_regular_file(path)
    elif stat.S_ISDIR(root_metadata.st_mode):
        walk(path)
    return digest.hexdigest()


def _node_state() -> tuple[bool, str | None]:
    node_modules = BOOK_ROOT / "node_modules"
    return os.path.lexists(node_modules), _tree_fingerprint(node_modules)


def _assert_node_state_unchanged(before: tuple[bool, str | None]) -> None:
    if _node_state() != before:
        raise RuntimeError("runner created or changed repository node_modules")


def _copy_node_manifests(root: Path) -> None:
    for name in ("package.json", "package-lock.json"):
        source = BOOK_ROOT / name
        if not source.is_file():
            raise FileNotFoundError(f"missing committed node manifest: {source}")
        shutil.copy2(source, root / name)


def _refresh_node_lock(root: Path, environment: dict[str, str]) -> int:
    package_json = BOOK_ROOT / "package.json"
    if not package_json.is_file():
        raise FileNotFoundError(f"missing committed node manifest: {package_json}")
    shutil.copy2(package_json, root / "package.json")
    completed = subprocess.run(
        ["npm", "install", "--package-lock-only", "--ignore-scripts"],
        cwd=root,
        env=environment,
        check=False,
    )
    if completed.returncode == 0:
        shutil.copy2(root / "package-lock.json", BOOK_ROOT / "package-lock.json")
    return completed.returncode


def _run_command(
    root: Path,
    environment: dict[str, str],
    command: list[str],
    with_node: bool,
) -> int:
    synced = subprocess.run(
        ["uv", "sync", "--frozen"], cwd=BOOK_ROOT, env=environment, check=False
    )
    if synced.returncode != 0:
        return synced.returncode

    if with_node:
        _copy_node_manifests(root)
        installed = subprocess.run(["npm", "ci"], cwd=root, env=environment, check=False)
        if installed.returncode != 0:
            return installed.returncode
        mmdc = root / "node_modules" / ".bin" / "mmdc"
        if not mmdc.is_file():
            raise FileNotFoundError(f"npm ci did not install mmdc: {mmdc}")
        environment["MMDC_PATH"] = str(mmdc)
        environment["PATH"] = str(mmdc.parent) + os.pathsep + environment.get("PATH", "")

    completed = subprocess.run(command, cwd=BOOK_ROOT, env=environment, check=False)
    return completed.returncode


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    command = list(arguments.command)
    if command and command[0] == "--":
        command.pop(0)
    if not arguments.refresh_node_lock and not command:
        return 2

    node_state = _node_state()
    root = _validated_temporary_root()
    try:
        environment = _environment(root)
        if arguments.refresh_node_lock:
            return _refresh_node_lock(root, environment)
        return _run_command(root, environment, command, arguments.with_node)
    finally:
        try:
            _assert_node_state_unchanged(node_state)
        finally:
            shutil.rmtree(root)


if __name__ == "__main__":
    raise SystemExit(main())
