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
from airflow.operators.python import PythonOperator
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor


def is_accepted_response(response):
    """Check if HTTP response indicates success or already-accepted state.
    Handles 409 CONFLICT (already running/started) as acceptance.
    """
    if response.status_code == 409:
        return True
    try:
        return response.json().get("status") in ("UP", "STARTED")
    except (ValueError, AttributeError):
        return False


def poll_run_completion(**context):
    """Poll run-status, return True when triggered run reaches terminal state."""
    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = json.loads(trigger_response)
    run_id = trigger_response["runId"]

    resp = requests.get("http://host.docker.internal:8081/api/internal/run-status", timeout=10)

    # 409 CONFLICT means a run is already active — treat as in-progress, not error
    if resp.status_code == 409:
        raise RuntimeError(f"Run {run_id} still in progress (409 CONFLICT - another run active)")

    resp.raise_for_status()
    data = resp.json()

    current = data.get("current")
    if not current or current.get("runId") != run_id:
        raise RuntimeError(f"Run {run_id} not yet started or runId mismatch")

    if not current.get("terminal", False):
        phase = current.get("phase", "UNKNOWN")
        raise RuntimeError(f"Run {run_id} still in progress: {phase}")

    if current.get("phase") == "FAILED":
        error = current.get("errorMessage", "unknown")
        raise RuntimeError(f"Run {run_id} failed: {error}")

    return True


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


default_args = {
    "owner": "maple-pipeline",
    "retries": 120,
    "retry_delay": timedelta(seconds=60),
}

with DAG(
    dag_id="daily_collection_pipeline",
    default_args=default_args,
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
        response_check=is_accepted_response,
        poke_interval=30,
        timeout=120,
    )

    trigger_daily_collection = HttpOperator(
        task_id="trigger_daily_collection",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/daily",
        method="POST",
        response_check=is_accepted_response,
    )

    wait_for_completion = PythonOperator(
        task_id="wait_for_completion",
        python_callable=poll_run_completion,
        execution_timeout=timedelta(hours=2),
    )

    wait_ie_cycle = PythonOperator(
        task_id="wait_for_item_equipment_cycle",
        python_callable=wait_for_item_equipment_cycle,
        execution_timeout=timedelta(hours=1),
        retries=1,
    )

    trigger_cleanup = TriggerDagRunOperator(
        task_id="trigger_cleanup_pipeline",
        trigger_dag_id="daily_cleanup_pipeline",
        wait_for_completion=False,
    )

    check_external_api >> trigger_daily_collection >> wait_for_completion >> wait_ie_cycle >> trigger_cleanup
