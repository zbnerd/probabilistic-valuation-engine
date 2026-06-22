"""ITEM_EQUIPMENT phase DAG with once / count=N / infinite modes.

Mode is selected via dag_run.conf['mode']. See parse_mode for validation.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.2
"""
import airflow  # noqa: F401  (required for DagBag safe_mode heuristic)

from phase_pipeline_factory import make_phase_dag


item_equipment_dag = make_phase_dag(
    phase="ITEM_EQUIPMENT",
    dag_id="item_equipment_pipeline",
    upstream_phase="CHARACTER_BASIC",
)