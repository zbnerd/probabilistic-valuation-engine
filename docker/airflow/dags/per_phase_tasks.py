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


def make_trigger_task(phase: str) -> PythonOperator:
    """Single-shot phase trigger via /trigger/phase/{phase}.

    Gates on scope: returns None (Airflow skip) if bare phase not in scope.
    200/202 → return response JSON for xcom correlation.
    409 → idempotent: discover active runId from /run-status, mark ALREADY_ACTIVE.
    Other → AirflowException.
    """
    def _trigger(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if phase not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Trigger {phase} failed: {exc}") from exc

        if resp.status_code in (200, 202):
            return resp.json()

        if resp.status_code == 409:
            try:
                status_resp = requests.get(
                    f"{base}/api/internal/run-status", timeout=10
                )
                status_resp.raise_for_status()
                data = status_resp.json()
            except (requests.RequestException, ValueError) as exc:
                raise AirflowException(
                    f"409 from trigger {phase} but /run-status fetch failed: {exc}"
                ) from exc
            current = data.get("current") or {}
            return {
                "runId": current.get("runId"),
                "phase": phase,
                "status": "ALREADY_ACTIVE",
            }

        raise AirflowException(
            f"Trigger {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return PythonOperator(
        task_id=f"per_phase_trigger_{phase.lower()}",
        python_callable=_trigger,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def make_loop_task(phase: str) -> PythonOperator:
    """Start loop via /loop/phase/{phase}.

    Gates on scope: returns None if {phase}_LOOP not in scope.
    202 → return response JSON (loopId, iterationCount).
    409 → idempotent: mark ALREADY_LOOPING, preserve existing loopId.
    400 → AirflowException (config error, e.g. RANKING_FETCH_LOOP).
    Other → AirflowException.
    """
    def _loop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_LOOP" not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/loop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Loop start {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return resp.json()

        if resp.status_code == 409:
            body = resp.json()
            return {**body, "status": "ALREADY_LOOPING"}

        if resp.status_code == 400:
            raise AirflowException(
                f"Loop start {phase} rejected (INVALID_PHASE): {resp.text[:500]}"
            )

        raise AirflowException(
            f"Loop start {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return PythonOperator(
        task_id=f"per_phase_loop_{phase.lower()}",
        python_callable=_loop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )