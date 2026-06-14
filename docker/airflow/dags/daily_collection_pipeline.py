"""
Daily Nexon data collection pipeline.

Trigger → Poll run-status with run_id correlation → Wait for synchronizer chunk consumed event → Trigger cleanup.

Control Plane: Airflow triggers and monitors.
Data Plane: Kafka handles chunk processing, retry, backpressure.
"""

from datetime import datetime, timedelta

import json

import requests
from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.sensors.http import HttpSensor
from airflow.sensors.python import PythonSensor


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
    """
    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = json.loads(trigger_response)
    run_id = trigger_response["runId"]

    try:
        resp = requests.get(
            f"{get_external_api_base()}/api/internal/run-status", timeout=10
        )
        resp.raise_for_status()
        data = resp.json()
    except (requests.RequestException, ValueError):
        return False  # transient — poke again

    current = data.get("current")
    if not current or current.get("runId") != run_id:
        return False  # not yet our run, or runId mismatch

    if current.get("phase") == "FAILED":
        raise RuntimeError(
            f"Run {run_id} failed: {current.get('errorMessage', 'unknown')}"
        )

    return bool(current.get("terminal", False))


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
        bootstrap_servers="host.docker.internal:9092",
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

    check_external_api >> trigger_daily_collection >> wait_for_completion >> wait_ie_cycle >> trigger_cleanup
