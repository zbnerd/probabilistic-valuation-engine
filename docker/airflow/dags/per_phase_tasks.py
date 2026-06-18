"""Per-phase Airflow task factories for ext-api.

Drives the per-phase endpoints from #1289/1290/1291 via Airflow's
BranchPythonOperator in daily_collection_pipeline.py.

Spec: docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md
ADR: docs/01_ADR/ADR-393-airflow-per-phase-dag.md
"""
from datetime import timedelta
import json

import requests
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator
from airflow.sensors.python import PythonSensor


# Allowed scope values. RANKING_FETCH_LOOP intentionally excluded — ext-api
# PhaseLoopController.loopablePhases from #1291 excludes RANKING_FETCH.
ALLOWED_SCOPES = frozenset({
    "RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT",
    "OCID_LOOKUP_LOOP", "CHARACTER_BASIC_LOOP", "ITEM_EQUIPMENT_LOOP",
    "RANKING_FETCH_STOP", "OCID_LOOKUP_STOP",
    "CHARACTER_BASIC_STOP", "ITEM_EQUIPMENT_STOP",
})

# Phase lists for fan-out
TRIGGER_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]
LOOP_PHASES = ["OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]
STOP_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]


def get_external_api_base() -> str:
    """Resolve ext-api base URL from Airflow Connection 'external_api'."""
    conn = BaseHook.get_connection("external_api")
    return f"http://{conn.host}:{conn.port}"


def parse_scope(conf: dict) -> list:
    """Validate dag_run.conf['scope']. Returns list of scope values.

    - Missing or 'FULL_DAILY' → ['FULL_DAILY']
    - String → wrap in list
    - List → validate every value against ALLOWED_SCOPES
    - Any invalid value → raise AirflowException
    """
    scope = conf.get("scope", "FULL_DAILY")
    if scope == "FULL_DAILY":
        return ["FULL_DAILY"]
    if isinstance(scope, str):
        scope = [scope]
    if not isinstance(scope, list):
        raise AirflowException(
            f"scope must be string or list, got {type(scope).__name__}"
        )
    invalid = [s for s in scope if s not in ALLOWED_SCOPES]
    if invalid:
        raise AirflowException(
            f"Invalid scope values: {invalid}. "
            f"Allowed: {sorted(ALLOWED_SCOPES)}"
        )
    return list(scope)