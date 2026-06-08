"""
Daily cleanup pipeline.

Runs every 6 hours and after daily_collection_pipeline completion.
Triggers module-cleanup to GC artifact runs, consumed chunks, and calculator results.

Control Plane: Airflow schedules and triggers.
Data Plane: module-cleanup (port 8084) executes cleanup on virtual threads.
"""

from datetime import datetime, timedelta

from airflow import DAG
from airflow.providers.http.operators.http import HttpOperator
from airflow.providers.http.sensors.http import HttpSensor


def is_cleanup_success(response):
    """Cleanup module returns 200 + JSON body for any successful invocation.

    module-cleanup is synchronous (not trigger-then-poll like daily_collection),
    so any 2xx response means the cleanup completed. 409 is treated as success
    in case a previous run is still draining the inbox.
    """
    return response.status_code in (200, 409)


default_args = {
    "owner": "maple-pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="daily_cleanup_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 */6 * * *",  # every 6 hours + triggered after collection
    catchup=False,
    tags=["pipeline", "cleanup"],
) as dag:

    check_cleanup_module = HttpSensor(
        task_id="check_cleanup_module",
        http_conn_id="cleanup",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    cleanup_artifact_runs = HttpOperator(
        task_id="cleanup_artifact_runs",
        http_conn_id="cleanup",
        endpoint="api/internal/cleanup/runs",
        method="POST",
        execution_timeout=timedelta(seconds=600),
        response_check=is_cleanup_success,
    )

    cleanup_calculator_runs = HttpOperator(
        task_id="cleanup_calculator_runs",
        http_conn_id="cleanup",
        endpoint="api/internal/cleanup/calculator-runs",
        method="POST",
        execution_timeout=timedelta(seconds=600),
        response_check=is_cleanup_success,
    )

    cleanup_consumed_inbox = HttpOperator(
        task_id="cleanup_consumed_inbox",
        http_conn_id="cleanup",
        endpoint="api/internal/cleanup/inbox",
        method="POST",
        execution_timeout=timedelta(seconds=300),
        response_check=is_cleanup_success,
    )

    check_cleanup_module >> [cleanup_artifact_runs, cleanup_calculator_runs, cleanup_consumed_inbox]
