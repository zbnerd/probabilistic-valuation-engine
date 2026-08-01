"""Command-line entry point; collectors and renderers register in later stages."""

from __future__ import annotations

import argparse
from collections.abc import Sequence

from . import __version__


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="portfolio-book")
    parser.add_argument("--version", action="version", version=f"portfolio-book {__version__}")
    parser.add_subparsers(dest="command", required=True)
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
