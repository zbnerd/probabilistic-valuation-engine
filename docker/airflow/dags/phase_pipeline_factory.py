"""Helpers for phase-separated Airflow DAGs.

Used by character_basic_pipeline.py and item_equipment_pipeline.py to build
once/count=N/infinite mode DAGs from a single factory. Also used by
stop_loop_pipeline.py for the loop-stop sensor.

Ref: docs/superpowers/specs/2026-06-22-dag-restructure-design.md
"""
from __future__ import annotations

from typing import Tuple

from airflow.exceptions import AirflowException


_VALID_MODES = frozenset({"once", "count", "infinite"})


def parse_mode(conf: dict) -> Tuple[str, int]:
    """Validate dag_run.conf for phase DAGs.

    Returns:
        (mode, count): mode ∈ {"once","count","infinite"}; count is the
            integer for mode=count, else 0.

    Raises:
        AirflowException: missing/invalid mode, or mode=count without a
            valid count.
    """
    if not isinstance(conf, dict):
        raise AirflowException(
            f"dag_run.conf must be a dict, got {type(conf).__name__}"
        )

    mode = conf.get("mode")
    if mode is None:
        raise AirflowException(
            "mode is required. Allowed: 'once', 'count', 'infinite'"
        )
    if not isinstance(mode, str):
        raise AirflowException(
            f"mode must be a string, got {type(mode).__name__}"
        )
    if mode not in _VALID_MODES:
        raise AirflowException(
            f"invalid mode='{mode}'. Allowed: {sorted(_VALID_MODES)}"
        )

    if mode != "count":
        return (mode, 0)

    count = conf.get("count")
    if count is None:
        raise AirflowException(
            "count is required when mode='count'"
        )
    if not isinstance(count, int) or isinstance(count, bool):
        raise AirflowException(
            f"count must be an integer, got {type(count).__name__}"
        )
    if count < 1:
        raise AirflowException(
            f"count must be >= 1, got {count}"
        )
    return ("count", count)
