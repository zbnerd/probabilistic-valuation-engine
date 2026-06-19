"""
Daily Nexon data collection pipeline.

Trigger → Poll run-status with run_id correlation → Wait for synchronizer chunk consumed event → Trigger cleanup.

Control Plane: Airflow triggers and monitors.
Data Plane: Kafka handles chunk processing, retry, backpressure.
"""

from datetime import datetime, timedelta
import json
import logging
import os

import requests
from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator, PythonOperator
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.sensors.http import HttpSensor
from airflow.sensors.python import PythonSensor

from per_phase_tasks import (
    LOOP_PHASES,
    STOP_PHASES,
    TRIGGER_PHASES,
    make_is_phase_terminal,
    make_loop_task,
    make_stop_task,
    make_trigger_task,
    parse_scope,
    parse_steps,
    wait_for_phase_terminal,
)

log = logging.getLogger(__name__)


def is_health_up(response):
    """HttpSensor response_check: ext-api /actuator/health returns {"status": "UP"}.

    Only used for the health-check sensor. The trigger task uses a dedicated
    PythonOperator (see trigger_daily_collection_fn) because HttpOperator's
    response_check is unreachable for 4xx — the HttpHook raises on
    response.raise_for_status() BEFORE invoking response_check.
    """
    try:
        return response.json().get("status") == "UP"
    except (ValueError, AttributeError):
        return False


def get_external_api_base():
    """Resolve ext-api base URL from the Airflow Connection 'external_api'.

    Replaces the hardcoded http://host.docker.internal:8081 string so the
    connection host/port can be reconfigured without editing DAG code.
    """
    conn = BaseHook.get_connection("external_api")
    return f"http://{conn.host}:{conn.port}"


def trigger_daily_collection_fn(**context):
    """POST /api/internal/trigger/daily as a fire-and-forget idempotent call.

    Why PythonOperator instead of HttpOperator:
      HttpOperator → HttpHook.run() → run_and_check() → check_response() →
      response.raise_for_status() raises AirflowException for any 4xx/5xx
      BEFORE the HttpOperator's response_check callback is ever called.
      So 409 CONFLICT (a legitimate idempotent success here, meaning another
      run is already active) cannot be intercepted via response_check.
      Using requests directly lets us explicitly accept 200/202/409 as
      success and return the runId via xcom for downstream correlation.

    200/202 → new run started; response body pushed to xcom (runId).
    409    → another run already in progress; returns the active runId from
              ext-api's run-status so downstream wait_for_completion can
              correlate against it.
    Other  → real failure (raised as AirflowException → task failure).
    """
    base = get_external_api_base()

    try:
        response = requests.post(
            f"{base}/api/internal/trigger/daily", timeout=30
        )
    except requests.RequestException as exc:
        raise AirflowException(f"Trigger request failed: {exc}") from exc

    if response.status_code in (200, 202):
        return response.json()

    if response.status_code == 409:
        # Idempotent success — discover the currently active runId from
        # run-status so downstream sensors correlate against it.
        try:
            status_resp = requests.get(
                f"{base}/api/internal/run-status", timeout=10
            )
            status_resp.raise_for_status()
            data = status_resp.json()
            current = data.get("current") or {}
            return {
                "runId": current.get("runId"),
                "status": "ALREADY_RUNNING",
            }
        except (requests.RequestException, ValueError) as exc:
            raise AirflowException(
                f"409 received but failed to fetch active runId: {exc}"
            ) from exc

    raise AirflowException(
        f"Trigger failed: HTTP {response.status_code} {response.reason}: "
        f"{response.text[:500]}"
    )


def _is_run_terminal(**context):
    """Sensor poke: return True when triggered runId reaches terminal state.

    - True  → sensor succeeds, DAG advances
    - False → sensor reschedules (mode='reschedule' on the operator)
    - RuntimeError(FAILED) → sensor hard-fails the DAG immediately
    - Transient HTTP/JSON errors → False (poke again, don't fail)

    A daily run spans 4 phases (RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC,
    ITEM_EQUIPMENT) and each phase has its OWN runId (the suffix is a
    nanos-time per phase, not a stable run-group id). The trigger response
    only carries the first phase's runId (RANKING_FETCH), so we match by
    the runId PREFIX (date-time portion: e.g. "20260619-152651-") which
    identifies the run group across all 4 phases.

    Phase completion order: we treat the run as terminal when the LAST
    phase (ITEM_EQUIPMENT) reaches terminal state. Earlier phases may
    already be terminal while later ones are still active.

    Why we cannot rely on the legacy `current` field: ext-api sets
    `current = null` when no phase is active (i.e. between runs, or after
    a daily run fully completes and clears). The deprecated single-slot
    `current` was used before the slots-map refactor. Verified 2026-06-19:
    after a daily run finishes, sensor pokes for 4h before timing out
    because `current` stays null forever.
    """
    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = json.loads(trigger_response)
    run_id = trigger_response["runId"]
    # First two segments of the runId (e.g. "20260619-152651") form the
    # run-group prefix shared across all 4 phases of the same daily run.
    run_group_prefix = "-".join(run_id.split("-")[:2]) + "-"

    def _belongs_to_run(phase_status):
        """True if the phase_status's runId belongs to our run group."""
        if not phase_status:
            return False
        rid = phase_status.get("runId") or ""
        return rid.startswith(run_group_prefix)

    try:
        resp = requests.get(
            f"{get_external_api_base()}/api/internal/run-status", timeout=10
        )
        resp.raise_for_status()
        data = resp.json()
    except (requests.RequestException, ValueError):
        return False  # transient — poke again

    # Check FAILED first across all phases (active + lastCompleted) — a
    # hard failure on ANY phase should abort the run.
    for container in (data.get("slots") or {}, data.get("lastCompletedByPhase") or {}):
        for phase_status in container.values():
            if _belongs_to_run(phase_status) and phase_status.get("phase") == "FAILED":
                raise RuntimeError(
                    f"Run {run_group_prefix}* failed: "
                    f"{phase_status.get('errorMessage', 'unknown')}"
                )

    # The run is terminal when ITEM_EQUIPMENT (last phase) is terminal
    # AND belongs to our run group. Check both `slots` (active) and
    # `lastCompletedByPhase` (cleared) to handle the case where the run
    # fully completed and `current` is null.
    item_equipment_status = (
        (data.get("slots") or {}).get("ITEM_EQUIPMENT")
        or (data.get("lastCompletedByPhase") or {}).get("ITEM_EQUIPMENT")
    )
    if not _belongs_to_run(item_equipment_status):
        return False  # our run hasn't started yet, or wrong runId

    return bool(item_equipment_status.get("terminal", False))


def run_steps(**ctx):
    """Walk dag_run.conf['steps'] sequentially.

    For each step:
      - action=trigger: POST /trigger/phase/{phase}, wait for that phase
        to reach terminal via wait_for_phase_terminal.
      - action=loop: POST /loop/phase/{phase}, exit immediately
        (fire-and-forget; DAG advances to trigger_cleanup_pipeline).

    Raises AirflowException on:
      - parse_steps validation failure (invalid phase, mutually exclusive
        scope+steps, etc.)
      - 400 INVALID_PHASE from ext-api trigger/loop
      - 5xx trigger errors
      - RuntimeError from wait_for_phase_terminal on FAILED phase
      - TimeoutError from wait_for_phase_terminal on 4h timeout

    The whole task has a 12h execution_timeout (3 trigger steps × 4h worst
    case). Most runs complete well within this — the timeout is a safety net.
    """
    from per_phase_tasks import (
        get_external_api_base,
    )

    conf = ctx["dag_run"].conf or {}
    steps = parse_steps(conf)  # raises AirflowException on invalid
    base = get_external_api_base()

    for step in steps:
        action = step["action"]
        phase = step["phase"]

        if action == "loop":
            # Fire-and-forget. POST + return. DAG advances.
            try:
                resp = requests.post(
                    f"{base}/api/internal/loop/phase/{phase}", timeout=30
                )
            except requests.RequestException as exc:
                raise AirflowException(
                    f"Loop start {phase} failed: {exc}"
                ) from exc
            if resp.status_code == 202:
                log.info("[run_steps] loop started phase=%s", phase)
            elif resp.status_code == 409:
                log.info("[run_steps] loop already running phase=%s", phase)
            elif resp.status_code == 400:
                raise AirflowException(
                    f"Loop start {phase} rejected (INVALID_PHASE): "
                    f"{resp.text[:500]}"
                )
            else:
                raise AirflowException(
                    f"Loop start {phase} failed: HTTP {resp.status_code} "
                    f"{resp.reason}: {resp.text[:500]}"
                )
            return  # loop is fire-and-forget; DAG advances

        # action == "trigger"
        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(
                f"Trigger {phase} failed: {exc}"
            ) from exc

        if resp.status_code in (200, 202):
            body = resp.json()
        elif resp.status_code == 409:
            body = {"runId": None, "status": "ALREADY_ACTIVE"}
        elif resp.status_code == 400:
            raise AirflowException(
                f"Trigger {phase} rejected (INVALID_PHASE): "
                f"{resp.text[:500]}"
            )
        else:
            raise AirflowException(
                f"Trigger {phase} failed: HTTP {resp.status_code} "
                f"{resp.reason}: {resp.text[:500]}"
            )

        run_id = body.get("runId")
        if not run_id:
            log.warning(
                "[run_steps] trigger %s returned no runId (status=%s); skipping wait",
                phase, body.get("status"),
            )
            continue

        log.info("[run_steps] waiting for %s runId=%s", phase, run_id)
        wait_for_phase_terminal(phase, run_id)
        log.info("[run_steps] %s reached terminal", phase)


def route_scope(**ctx) -> str:
    """Branch decision (spec §5).

    Returns the task_id to follow after branch_on_scope:
      - 'run_steps_task' when 'steps' field is present (ordered sequence).
      - 'trigger_daily_collection' when scope == ['FULL_DAILY'] (default).
      - 'per_phase_join' for any flat 'scope' list (existing #1292 path).
    """
    conf = ctx["dag_run"].conf or {}
    if "steps" in conf:
        return "run_steps_task"
    scope = parse_scope(conf)
    return "trigger_daily_collection" if scope == ["FULL_DAILY"] else "per_phase_join"


def wait_for_item_equipment_cycle(**context):
    """Wait for item-equipment chunk consumed event from synchronizer via Kafka.

    Consumes from synchronizer.chunk.consumed topic. Filters by runId
    to only accept events from the run triggered by this pipeline invocation.
    Uses per-run group_id to avoid partition rebalancing on overlapping runs.
    """
    import json as _json
    from kafka import KafkaConsumer

    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = _json.loads(trigger_response)
    run_id = trigger_response["runId"]

    consumer = KafkaConsumer(
        "synchronizer.chunk.consumed",
        bootstrap_servers=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        auto_offset_reset="latest",
        enable_auto_commit=False,
        group_id=f"airflow-ie-cycle-waiter-{run_id[:8]}",
        value_deserializer=lambda m: _json.loads(m.decode("utf-8")),
        consumer_timeout_ms=120 * 60 * 1000,  # 2 hours
    )

    try:
        for message in consumer:
            event = message.value
            if event.get("endpoint") == "item-equipment" and event.get("runId") == run_id:
                return True
    finally:
        consumer.close()

    raise RuntimeError("Timed out waiting for item-equipment consumed event")


with DAG(
    dag_id="daily_collection_pipeline",
    # Per-task retry budget overrides default_args below:
    #   trigger_daily_collection:    retries=0  (idempotent, 409=success)
    #   wait_for_completion:         retries=0  (sensor reschedules itself)
    #   wait_ie_cycle:               retries=1  (one safety retry for Kafka rebalance)
    default_args={
        "owner": "maple-pipeline",
        "retries": 0,
    },
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # UTC 18:00 = KST 03:00
    catchup=False,
    tags=["pipeline", "daily"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=is_health_up,
        poke_interval=30,
        timeout=120,
    )

    # PythonOperator (not HttpOperator) because ext-api returns 409
    # CONFLICT as an idempotent success when a run is already active, and
    # HttpOperator's HttpHook raises on any 4xx BEFORE response_check is
    # reachable. See trigger_daily_collection_fn for full rationale.
    trigger_daily_collection = PythonOperator(
        task_id="trigger_daily_collection",
        python_callable=trigger_daily_collection_fn,
        retries=0,  # trigger is idempotent; 409 is success
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,  # capture runId for downstream correlation
    )

    # mode='reschedule' frees the LocalExecutor worker slot between pokes,
    # so a 4h poll does not block other tasks. The sensor self-loops until
    # the triggered runId hits terminal state (or 4h timeout, or FAILED).
    wait_for_completion = PythonSensor(
        task_id="wait_for_completion",
        python_callable=_is_run_terminal,
        mode="reschedule",
        poke_interval=60,
        timeout=60 * 60 * 4,  # 4h
    )

    wait_ie_cycle = PythonOperator(
        task_id="wait_for_item_equipment_cycle",
        python_callable=wait_for_item_equipment_cycle,
        # item-equipment cycle can take ~35min on a fresh OCID cache;
        # doubling the buffer for slow days.
        execution_timeout=timedelta(hours=2),
        retries=1,  # safety net for Kafka rebalance / consumer crash
    )

    trigger_cleanup = TriggerDagRunOperator(
        task_id="trigger_cleanup_pipeline",
        trigger_dag_id="daily_cleanup_pipeline",
        wait_for_completion=False,
    )

    # Per-phase branch (issue #1292). Activated when dag_run.conf['scope']
    # is set to a list of action values (see per_phase_tasks.ALLOWED_SCOPES).
    # When dag_run.conf['steps'] is present, routes to run_steps_task for
    # ordered sequential execution (spec §5).
    branch_on_scope = BranchPythonOperator(
        task_id="branch_on_scope",
        python_callable=route_scope,
    )

    per_phase_join = EmptyOperator(
        task_id="per_phase_join",
        trigger_rule="none_failed_min_one_success",
    )

    per_phase_trigger_tasks = [make_trigger_task(p) for p in TRIGGER_PHASES]
    per_phase_loop_tasks = [make_loop_task(p) for p in LOOP_PHASES]
    per_phase_stop_tasks = [make_stop_task(p) for p in STOP_PHASES]

    # Sequence-steps path (spec §5). When dag_run.conf['steps'] is set,
    # branch_on_scope routes here. The task walks steps sequentially:
    # trigger steps block on wait_for_phase_terminal; loop steps fire-and-forget.
    run_steps_task = PythonOperator(
        task_id="run_steps",
        python_callable=run_steps,
        execution_timeout=timedelta(hours=12),
        retries=0,
    )

    per_phase_trigger_sensors = [
        PythonSensor(
            task_id=f"per_phase_wait_{p.lower()}_completion",
            python_callable=make_is_phase_terminal(p),
            mode="reschedule",
            poke_interval=60,
            timeout=60 * 60 * 4,
        )
        for p in TRIGGER_PHASES
    ]

    check_external_api >> branch_on_scope
    branch_on_scope >> trigger_daily_collection
    branch_on_scope >> per_phase_join
    branch_on_scope >> run_steps_task
    per_phase_join >> per_phase_trigger_tasks
    per_phase_join >> per_phase_loop_tasks
    per_phase_join >> per_phase_stop_tasks
    for trig, sens in zip(per_phase_trigger_tasks, per_phase_trigger_sensors):
        trig >> sens
    trigger_daily_collection >> wait_for_completion >> wait_ie_cycle >> trigger_cleanup
    run_steps_task >> trigger_cleanup
