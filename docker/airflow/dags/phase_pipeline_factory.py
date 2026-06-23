"""Helpers for phase-separated Airflow DAGs.

Used by character_basic_pipeline.py and item_equipment_pipeline.py to build
once/count=N/infinite mode DAGs from a single factory. Also used by
stop_loop_pipeline.py for the loop-stop sensor.

Ref: docs/superpowers/specs/2026-06-22-dag-restructure-design.md
"""
from __future__ import annotations

import json as _json
import os
import time
from datetime import datetime, timedelta
from typing import Tuple

import requests
from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import BranchPythonOperator, PythonOperator
from airflow.providers.http.sensors.http import HttpSensor
from airflow.sensors.python import PythonSensor
from kafka import KafkaConsumer


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


def get_external_api_base() -> str:
    """Resolve ext-api base URL from Airflow Connection 'external_api'."""
    conn = BaseHook.get_connection("external_api")
    return f"http://{conn.host}:{conn.port}"


def _trigger_once_fn(phase: str):
    """Inner: trigger phase once via POST /trigger/phase/{phase}.

    Reads upstream_run_id from xcom (task_id='upstream_run_id') if
    present — OCID_LOOKUP and downstream phases require X-Upstream-Run-Id.
    RANKING_FETCH ignores the header (it IS the upstream).
    """
    def _trigger(**ctx):
        conf = ctx["dag_run"].conf or {}
        base = get_external_api_base()
        ti = ctx.get("ti")
        upstream_run_id = ti.xcom_pull(task_ids="upstream_run_id") if ti else None

        headers = {}
        if upstream_run_id and phase != "RANKING_FETCH":
            headers["X-Upstream-Run-Id"] = upstream_run_id

        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}",
                headers=headers,
                timeout=30,
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
            slot = (data.get("slots") or {}).get(phase) or {}
            return {
                "runId": slot.get("runId"),
                "phase": phase,
                "status": "ALREADY_ACTIVE",
            }

        if resp.status_code == 400:
            raise AirflowException(
                f"Trigger {phase} rejected (INVALID_PHASE): {resp.text[:500]}"
            )

        raise AirflowException(
            f"Trigger {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _trigger


def make_trigger_once_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that triggers phase once.

    Caller wires `>>` between upstream's xcom pusher and this task.
    """
    return PythonOperator(
        task_id=f"trigger_{phase.lower()}",
        python_callable=_trigger_once_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def _trigger_loop_fn(phase: str):
    """Inner: start infinite loop via POST /loop/phase/{phase}.

    mode=count and mode=infinite both start the loop. The count sensor
    (mode=count) or operator action (mode=infinite) handles termination.
    """
    def _loop(**ctx):
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

    return _loop


def make_trigger_loop_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that starts the loop for `phase`.

    Fire-and-forget at HTTP layer; termination is operator-controlled via
    stop_loop_pipeline or mode=count's count sensor.
    """
    return PythonOperator(
        task_id=f"trigger_loop_{phase.lower()}",
        python_callable=_trigger_loop_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


# Phase → endpoint mapping for Kafka chunk-ready event filtering.
# The synchronizer publishes to synchronizer.chunk.consumed with endpoint
# field set to one of these values. Must match module-synchronizer publish code.
_PHASE_TO_ENDPOINT = {
    "CHARACTER_BASIC": "character-basic",
    "ITEM_EQUIPMENT": "item-equipment",
}

# Per-chunk processing P99 (empirically observed ~3-5min on production
# hardware). The count sensor timeout is set to 12h, which covers up to
# ~138 chunks at 5min/chunk — well beyond any reasonable mode=count value.
# Operators specifying count > 100 should use mode=infinite + stop_loop_pipeline.
_COUNT_SENSOR_MAX_TIMEOUT = timedelta(hours=12)


def _make_count_sensor_runtime(phase: str) -> PythonSensor:
    """Count sensor that reads count from dag_run.conf at runtime.

    Single sensor handles any count value (1..N where N * 5min + 30min <= 12h).
    The parse-time alternative (one sensor per count value) would inflate the
    DAG graph and require DAG-serialization support we don't have.

    Implementation: the _poke callable calls parse_mode(ctx.conf) to read the
    operator-passed count, then polls Kafka synchronizer.chunk.consumed for
    matching events.
    """
    endpoint = _PHASE_TO_ENDPOINT[phase]

    def _poke(**ctx):
        _, count = parse_mode(ctx["dag_run"].conf or {})
        consumer = KafkaConsumer(
            "synchronizer.chunk.consumed",
            bootstrap_servers=os.environ["KAFKA_BOOTSTRAP_SERVERS"],
            auto_offset_reset="latest",
            enable_auto_commit=False,
            group_id=(
                f"airflow-count-sensor-{phase.lower()}-"
                f"{ctx['dag_run'].run_id[:8]}"
            ),
            value_deserializer=lambda m: _json.loads(m.decode("utf-8")),
        )
        try:
            received = 0
            for message in consumer:
                event = message.value
                if event.get("endpoint") == endpoint:
                    received += 1
                    if received >= count:
                        return True
        finally:
            consumer.close()
        return False  # iterator exhausted; reschedule

    return PythonSensor(
        task_id=f"count_sensor_{phase.lower()}",
        python_callable=_poke,
        mode="reschedule",
        poke_interval=30,
        timeout=_COUNT_SENSOR_MAX_TIMEOUT,
    )


def _stop_loop_fn(phase: str):
    """Inner: POST /stop/loop/phase/{phase}.

    Skips (returns None) when dag_run.conf['phase'] is set but does not
    match `phase` — single DAG handles both CHARACTER_BASIC and
    ITEM_EQUIPMENT, gated by conf. Operators trigger with
    ``-c '{"phase":"ITEM_EQUIPMENT"}'`` to stop only one.
    """
    def _stop(**ctx):
        dag_run = ctx.get("dag_run")
        conf = (dag_run.conf if dag_run else None) or {}
        target_phase = conf.get("phase")
        if target_phase is not None and target_phase != phase:
            return None  # skip; this task is for another phase

        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/stop/loop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Stop loop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}

        if resp.status_code == 200:
            body = resp.json()
            return {**body, "status": "NOT_LOOPING"}

        if resp.status_code == 400:
            raise AirflowException(
                f"Stop loop {phase} rejected (INVALID_PHASE): {resp.text[:500]}"
            )

        raise AirflowException(
            f"Stop loop {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _stop


def make_stop_loop_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that stops the loop for `phase`."""
    return PythonOperator(
        task_id=f"stop_loop_{phase.lower()}",
        python_callable=_stop_loop_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def _wait_loop_stopped_fn(phase: str):
    """Inner: poll /run-status until loopSummaries[phase].status == STOPPED."""
    def _poke(**ctx):
        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status", timeout=10
            )
            resp.raise_for_status()
            data = resp.json()
        except Exception:
            return False  # transient → reschedule

        summaries = data.get("loopSummaries") or {}
        entry = summaries.get(phase)
        if not entry:
            return True  # no loop was active; idempotent success

        status = entry.get("status")
        if status == "STOPPED":
            return True
        if status == "FAILED":
            raise RuntimeError(
                f"Loop for {phase} failed: {entry.get('lastError', 'unknown')}"
            )
        return False

    return _poke


def make_wait_loop_stopped_sensor(phase: str) -> PythonSensor:
    """Sensor that returns True when loopSummaries[phase].status == STOPPED.

    Returns True immediately if no loop is active for `phase` (idempotent).
    """
    return PythonSensor(
        task_id=f"wait_loop_stopped_{phase.lower()}",
        python_callable=_wait_loop_stopped_fn(phase),
        mode="reschedule",
        poke_interval=10,
        timeout=30 * 60,  # 30 minutes
    )


def make_branch_on_mode_for_phase(phase: str) -> BranchPythonOperator:
    """BranchPythonOperator that routes by mode for a specific phase.

    Task_ids returned:
      - mode=once    → 'trigger_<phase>'
      - mode=count   → 'trigger_loop_<phase>' (continues into count_sensor + stop_loop)
      - mode=infinite → 'trigger_loop_infinite_<phase>' (leaf; DAG ends here)
    """
    def _branch(**ctx):
        mode, _ = parse_mode(ctx["dag_run"].conf or {})
        if mode == "once":
            return f"trigger_{phase.lower()}"
        if mode == "infinite":
            return f"trigger_loop_infinite_{phase.lower()}"
        return f"trigger_loop_{phase.lower()}"  # mode=count

    return BranchPythonOperator(
        task_id="branch_on_mode",
        python_callable=_branch,
    )


def _wait_terminal_fn(phase: str):
    """Inner: poll /run-status until phase slot reaches terminal state.

    Returns True once terminal. Raises RuntimeError on FAILED, TimeoutError on
    4h deadline. Ported from per_phase_tasks.make_is_phase_terminal (legacy).
    """
    def _wait(**ctx):
        ti = ctx["ti"]
        trigger_resp = ti.xcom_pull(task_ids=f"trigger_{phase.lower()}")
        if isinstance(trigger_resp, str):
            trigger_resp = _json.loads(trigger_resp)
        run_id = (trigger_resp or {}).get("runId")
        if not run_id:
            raise RuntimeError(
                f"Sensor for {phase} triggered but no runId xcom'd"
            )

        run_group_prefix = "-".join(run_id.split("-")[:2]) + "-"
        base = get_external_api_base()
        deadline = time.monotonic() + 4 * 60 * 60  # 4h

        while True:
            try:
                resp = requests.get(
                    f"{base}/api/internal/run-status", timeout=10
                )
                resp.raise_for_status()
                data = resp.json()
            except Exception:
                time.sleep(30)
                continue

            slot = (
                (data.get("slots") or {}).get(phase)
                or (data.get("lastCompletedByPhase") or {}).get(phase)
            )
            if not slot or not (
                slot.get("runId") or ""
            ).startswith(run_group_prefix):
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Phase {phase} did not acquire runId {run_id} within 4h"
                    )
                time.sleep(30)
                continue

            if slot.get("phase") == "FAILED":
                raise RuntimeError(
                    f"Run {run_group_prefix}* phase {phase} failed: "
                    f"{slot.get('errorMessage', 'unknown')}"
                )
            if slot.get("terminal"):
                return True
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Phase {phase} did not reach terminal within 4h"
                )
            time.sleep(30)

    return _wait


_PHASE_ORDER = (
    "RANKING_FETCH",
    "OCID_LOOKUP",
    "CHARACTER_BASIC",
    "ITEM_EQUIPMENT",
    "COMPLETED",
)


def _wait_phase_terminal_fn(phase: str):
    """Inner: poll /run-status until ext-api has progressed PAST `phase`.

    Uses phase-progression gating: waits for `current.phase` to be any
    phase strictly after `phase` (or `COMPLETED`). When ext-api advances
    from OCID_LOOKUP to CHARACTER_BASIC, the upstream phase OCID_LOOKUP
    is by definition terminal — phase transitions only happen on terminal.

    Raises RuntimeError if the active run's phase equals `phase` and the
    slot is FAILED. TimeoutError after 4h.

    No xcom or runId correlation needed — phase ordering is sufficient
    because ext-api enforces strict sequential phase transitions.
    """
    if phase not in _PHASE_ORDER:
        raise ValueError(f"Unknown phase {phase!r}")
    target_idx = _PHASE_ORDER.index(phase)

    def _wait(**ctx):
        base = get_external_api_base()
        deadline = time.monotonic() + 4 * 60 * 60  # 4h
        while True:
            try:
                resp = requests.get(
                    f"{base}/api/internal/run-status", timeout=10
                )
                resp.raise_for_status()
                data = resp.json()
            except Exception:
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Upstream phase {phase} status unreachable for 4h"
                    )
                time.sleep(30)
                continue

            current = data.get("current") or {}
            current_phase = current.get("phase")
            current_run_id = current.get("runId")

            # No active run yet → not progressed past `phase`
            if not current_run_id or not current_phase:
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Upstream phase {phase} did not reach terminal within 4h"
                    )
                time.sleep(30)
                continue

            if current_phase == "FAILED":
                raise RuntimeError(
                    f"Active run failed before reaching phase {phase}: "
                    f"{current.get('errorMessage', 'unknown')}"
                )

            # current_phase strictly after `phase` in _PHASE_ORDER → upstream
            # is terminal (phase transitions only happen on terminal).
            current_idx = (
                _PHASE_ORDER.index(current_phase)
                if current_phase in _PHASE_ORDER
                else -1
            )
            if current_idx > target_idx:
                return True

            # No active run (current=null after daily chain finished) but
            # a prior run reached `phase` terminal — accept as gate pass.
            # Without this, standalone per-phase DAGs triggered after the
            # daily chain completes can't pass the upstream sensor because
            # `current` is null and `current_idx > target_idx` never holds.
            # Verified 2026-06-23: item_equipment_pipeline stuck on
            # wait_upstream_terminal_character_basic for 4h timeout because
            # /run-status returned current=null after daily success.
            if not current_run_id:
                lcbp = data.get("lastCompletedByPhase") or {}
                slot = lcbp.get(phase) or {}
                if slot.get("terminal"):
                    return True

            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Upstream phase {phase} did not reach terminal within 4h "
                    f"(current.phase={current_phase})"
                )
            time.sleep(30)

    return _wait


def make_wait_phase_terminal_sensor(phase: str) -> PythonSensor:
    """Sensor that returns True when ext-api's active run has progressed
    past `phase` (phase ordering is the gate).

    Raises RuntimeError if the active run is FAILED. TimeoutError after 4h.
    No xcom dependency.
    """
    return PythonSensor(
        task_id=f"wait_upstream_terminal_{phase.lower()}",
        python_callable=_wait_phase_terminal_fn(phase),
        mode="reschedule",
        poke_interval=60,
        timeout=4 * 60 * 60,
    )


def make_phase_dag(
    phase: str,
    dag_id: str,
    upstream_phase: str | None = None,
) -> DAG:
    """Build a phase DAG with mode=once / mode=count / mode=infinite branches.

    Args:
        phase: PipelinePhase name (CHARACTER_BASIC or ITEM_EQUIPMENT).
        dag_id: Airflow DAG id.
        upstream_phase: If set, all branches wait for this phase to reach
            terminal in /run-status before proceeding. Required when the
            active ext-api run enforces upstream-phase order (rejects
            MISSING_UPSTREAM 400 otherwise). Set to None for standalone
            triggers where the prior phase is already complete or not
            applicable.

    Mode routing (via make_branch_on_mode_for_phase):
      - mode=once     → trigger_<phase> >> wait_terminal_<phase>
      - mode=count    → trigger_loop_<phase> >> count_sensor >> stop_loop
      - mode=infinite → trigger_loop_infinite_<phase> (leaf; DAG ends here)

    Wiring with upstream_phase:
      check_external_api >> wait_upstream_terminal_<phase> >> branch >> ...
    """
    default_args = {
        "owner": "maple-pipeline",
        "retries": 0,
    }

    dag = DAG(
        dag_id=dag_id,
        default_args=default_args,
        start_date=datetime(2026, 5, 29),
        schedule=None,
        catchup=False,
        tags=["pipeline", "phase", phase.lower().replace("_", "-")],
    )

    with dag:
        check_external_api = HttpSensor(
            task_id="check_external_api",
            http_conn_id="external_api",
            endpoint="actuator/health",
            request_params={},
            response_check=lambda r: r.status_code == 200,
            poke_interval=30,
            timeout=120,
        )

        # Optional upstream-phase gate (gates all 3 branches below).
        # Sensor polls /run-status for the prior phase's terminal entry
        # without requiring xcom runId correlation.
        pre_branch_tail = check_external_api
        if upstream_phase is not None:
            wait_upstream = make_wait_phase_terminal_sensor(upstream_phase)
            check_external_api >> wait_upstream
            pre_branch_tail = wait_upstream

        branch = make_branch_on_mode_for_phase(phase)

        # once branch
        trigger_once = make_trigger_once_task(phase)
        wait_terminal = PythonSensor(
            task_id=f"wait_terminal_{phase.lower()}",
            python_callable=_wait_terminal_fn(phase),
            mode="reschedule",
            poke_interval=60,
            timeout=4 * 60 * 60,
        )

        # count branch: trigger_loop → count_sensor (runtime count from conf) → stop_loop
        trigger_loop_count = make_trigger_loop_task(phase)
        count_sensor = _make_count_sensor_runtime(phase)
        stop_loop = make_stop_loop_task(phase)

        # infinite branch: trigger_loop_infinite (leaf; DAG succeeds here)
        trigger_loop_infinite = PythonOperator(
            task_id=f"trigger_loop_infinite_{phase.lower()}",
            python_callable=_trigger_loop_fn(phase),
            retries=0,
            execution_timeout=timedelta(seconds=60),
            do_xcom_push=True,
        )

        # Wiring
        pre_branch_tail >> branch
        branch >> trigger_once >> wait_terminal
        branch >> trigger_loop_count >> count_sensor >> stop_loop
        branch >> trigger_loop_infinite

    return dag
