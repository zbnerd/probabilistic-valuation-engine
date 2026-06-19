"""Per-phase Airflow task factories for ext-api.

Drives the per-phase endpoints from #1289/1290/1291 via Airflow's
BranchPythonOperator in daily_collection_pipeline.py.

Spec: docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md
ADR: docs/01_ADR/ADR-393-airflow-per-phase-dag.md
"""
from datetime import timedelta
import json
import time

import requests
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator
from airflow.sensors.python import PythonSensor


# Allowed scope values. RANKING_FETCH_LOOP and OCID_LOOKUP_LOOP excluded —
# ext-api PhaseLoopController.loopablePhases only allows CHARACTER_BASIC and
# ITEM_EQUIPMENT (verified against module-external-api/.../loop/PhaseLoopController.kt
# on 2026-06-18; spec §2.3 mentioned OCID_LOOKUP but impl didn't include it).
ALLOWED_SCOPES = frozenset({
    "RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT",
    "CHARACTER_BASIC_LOOP", "ITEM_EQUIPMENT_LOOP",
    "RANKING_FETCH_STOP", "OCID_LOOKUP_STOP",
    "CHARACTER_BASIC_STOP", "ITEM_EQUIPMENT_STOP",
})

# Phase lists for fan-out
TRIGGER_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]
LOOP_PHASES = ["CHARACTER_BASIC", "ITEM_EQUIPMENT"]
STOP_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]

# Phase sets for sequence-steps validation (spec §3.2).
# Derived from TRIGGER_PHASES/LOOP_PHASES so adding a new phase only requires
# updating the lists above.
_TRIGGERABLE_PHASES = frozenset(TRIGGER_PHASES)
_LOOPABLE_PHASES = frozenset(LOOP_PHASES)
_STEP_ACTIONS = frozenset({"trigger", "loop"})


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


def parse_steps(conf: dict) -> list:
    """Validate dag_run.conf['steps']. Returns list of step dicts.

    Rules (per spec §3.2):
      - 'steps' missing → return [] (caller falls back to FULL_DAILY / scope path).
      - 'steps' and 'scope' both set → AirflowException (mutually exclusive).
      - Each step is {action, phase}. action ∈ {trigger, loop}; phase ∈ _TRIGGERABLE_PHASES.
      - action=loop requires phase ∈ _LOOPABLE_PHASES (else fail-fast).
      - Empty list → AirflowException.
      - Non-list, non-dict → AirflowException.
    """
    if "scope" in conf and "steps" in conf:
        raise AirflowException(
            "dag_run.conf 'scope' and 'steps' are mutually exclusive. "
            "Use one or the other."
        )
    if "steps" not in conf:
        return []

    steps = conf["steps"]
    if not isinstance(steps, list):
        raise AirflowException(
            f"'steps' must be a list, got {type(steps).__name__}"
        )
    if not steps:
        raise AirflowException("'steps' is empty — omit the field for FULL_DAILY")

    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            raise AirflowException(
                f"steps[{i}] must be a dict, got {type(step).__name__}"
            )
        action = step.get("action")
        phase = step.get("phase")
        if action not in _STEP_ACTIONS:
            raise AirflowException(
                f"steps[{i}].action='{action}' invalid. "
                f"Allowed: {sorted(_STEP_ACTIONS)}"
            )
        if phase not in _TRIGGERABLE_PHASES:
            raise AirflowException(
                f"steps[{i}].phase='{phase}' invalid. "
                f"Allowed: {sorted(_TRIGGERABLE_PHASES)}"
            )
        if action == "loop" and phase not in _LOOPABLE_PHASES:
            raise AirflowException(
                f"steps[{i}]: loop not allowed on {phase}. "
                f"Loopable: {sorted(_LOOPABLE_PHASES)}"
            )
    return list(steps)


def wait_for_phase_terminal(
    phase: str,
    run_id: str,
    timeout_seconds: int = 60 * 60 * 4,  # 4h per spec §4.1
    poll_interval: int = 30,
) -> None:
    """Poll /run-status until the specified phase's runId reaches terminal.

    The runId match uses the run-group PREFIX (date-time portion), because
    each phase of a daily run gets its own runId with a different nano-time
    suffix. The trigger response carries the first phase's runId; we derive
    the prefix once and match any phase whose runId starts with it.

    Args:
      phase: pipeline phase whose slot/lastCompleted we check.
      run_id: any runId from the run group (typically from trigger response).
      timeout_seconds: max time to wait before raising TimeoutError.
      poll_interval: seconds between polls.

    Raises:
      RuntimeError: phase reached FAILED with errorMessage.
      TimeoutError: phase did not reach terminal within timeout_seconds.
      requests.RequestException: network error not retried (caller decides).
    """
    run_group_prefix = "-".join(run_id.split("-")[:2]) + "-"
    base = get_external_api_base()
    deadline = time.monotonic() + timeout_seconds

    def _belongs(phase_status):
        if not phase_status:
            return False
        rid = phase_status.get("runId") or ""
        return rid.startswith(run_group_prefix)

    while True:
        resp = requests.get(f"{base}/api/internal/run-status", timeout=10)
        resp.raise_for_status()
        data = resp.json()

        # Check slots first (active), then lastCompletedByPhase (cleared).
        phase_status = (
            (data.get("slots") or {}).get(phase)
            or (data.get("lastCompletedByPhase") or {}).get(phase)
        )

        if not _belongs(phase_status):
            # Phase hasn't acquired our runId yet (still on a prior run).
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Phase {phase} did not acquire runId {run_id} "
                    f"(prefix {run_group_prefix}) within {timeout_seconds}s"
                )
            time.sleep(poll_interval)
            continue

        if phase_status.get("phase") == "FAILED":
            raise RuntimeError(
                f"Run {run_group_prefix}* phase {phase} failed: "
                f"{phase_status.get('errorMessage', 'unknown')}"
            )

        if phase_status.get("terminal"):
            return

        if time.monotonic() >= deadline:
            raise TimeoutError(
                f"Phase {phase} did not reach terminal within "
                f"{timeout_seconds}s (run group {run_group_prefix})"
            )
        time.sleep(poll_interval)


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


def make_stop_task(phase: str) -> PythonOperator:
    """Stop via /stop/phase/{phase}.

    Single endpoint halts both single-shot runs and loops (per #1290 spec §5.3).
    Gates on scope: returns None if {phase}_STOP not in scope.
    202 → STOP_REQUESTED; 200 → NOT_RUNNING (idempotent).
    """
    def _stop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_STOP" not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/stop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Stop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}

        if resp.status_code == 200:
            return {"phase": phase, "status": "NOT_RUNNING"}

        raise AirflowException(
            f"Stop {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return PythonOperator(
        task_id=f"per_phase_stop_{phase.lower()}",
        python_callable=_stop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def make_is_phase_terminal(phase: str):
    """PythonSensor callable: returns True when triggered runId reaches terminal.

    Gates on scope first: if phase not in scope, returns True (skip).
    FAILED phase → RuntimeError (hard-fail DAG).
    Transient HTTP → False (reschedule).
    """
    task_id = f"per_phase_trigger_{phase.lower()}"

    def _poke(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if phase not in scope:
            return True

        xcom_val = ctx["ti"].xcom_pull(task_ids=task_id)
        if isinstance(xcom_val, str):
            xcom_val = json.loads(xcom_val)
        run_id = (xcom_val or {}).get("runId")
        if not run_id:
            raise RuntimeError(
                f"Sensor for {phase} triggered but trigger xcom returned no runId "
                f"— config error"
            )

        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status",
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError):
            return False

        current = data.get("current")
        if not current or current.get("runId") != run_id:
            return False

        if current.get("phase") == "FAILED":
            raise RuntimeError(
                f"Run {run_id} failed: {current.get('errorMessage', 'unknown')}"
            )

        return bool(current.get("terminal", False))

    return _poke