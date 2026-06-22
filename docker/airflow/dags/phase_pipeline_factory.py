"""Helpers for phase-separated Airflow DAGs.

Used by character_basic_pipeline.py and item_equipment_pipeline.py to build
once/count=N/infinite mode DAGs from a single factory. Also used by
stop_loop_pipeline.py for the loop-stop sensor.

Ref: docs/superpowers/specs/2026-06-22-dag-restructure-design.md
"""
from __future__ import annotations

import json as _json
import os
from datetime import timedelta
from typing import Tuple

import requests
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator
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
    """Inner: POST /stop/loop/phase/{phase}."""
    def _stop(**ctx):
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
