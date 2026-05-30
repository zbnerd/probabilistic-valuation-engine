"""
Daily cleanup pipeline.

Runs every 6 hours and after daily_collection_pipeline completion.
Cleans up artifact runs, consumed chunks, and calculator results in parallel.

Control Plane: Airflow schedules and triggers.
Data Plane: Modules execute cleanup on virtual threads.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor

default_args = {
    "owner": "maple-pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="daily_cleanup_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,
    catchup=False,
    tags=["pipeline", "cleanup"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.json().get("status") == "UP",
        poke_interval=30,
        timeout=120,
    )

    trigger_artifact_cleanup = HttpOperator(
        task_id="trigger_artifact_cleanup",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/artifact-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    trigger_consumed_cleanup = HttpOperator(
        task_id="trigger_consumed_cleanup",
        http_conn_id="external_api",
        endpoint="api/internal/trigger/consumed-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    trigger_result_cleanup = HttpOperator(
        task_id="trigger_result_cleanup",
        http_conn_id="calculator",
        endpoint="api/internal/trigger/result-cleanup",
        method="POST",
        execution_timeout=timedelta(seconds=30),
        response_check=lambda r: r.json().get("status") == "STARTED",
    )

    check_external_api >> [trigger_artifact_cleanup, trigger_consumed_cleanup, trigger_result_cleanup]
