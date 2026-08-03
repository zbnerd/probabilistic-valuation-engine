#!/usr/bin/env python3
"""Compatibility wrapper for the canonical capture orchestrator."""

from __future__ import annotations

import sys
from pathlib import Path

BOOK_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BOOK_ROOT / "tools"))

from portfolio_builder.cli import main


if __name__ == "__main__":
    raise SystemExit(main(("collect-all", *sys.argv[1:])))
