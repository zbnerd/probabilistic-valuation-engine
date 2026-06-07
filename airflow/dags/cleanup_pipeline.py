"""
Pipeline cleanup DAG (replaces Spring @Scheduled).

Triggers module-cleanup HTTP endpoints every 1h. Module-cleanup must be
reachable via host.docker.internal:8084 from the Airflow scheduler container.

Three independent tasks; each is fire-and-forget. Failures in one do not
block the others.
"""
from datetime import datetime, timedelta
from airflow import DAG
from airflow.providers.http.operators.http import SimpleHttpOperator

default_args = {
    "owner": "pipeline",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="cleanup_pipeline",
    description="Triggers module-cleanup endpoints (runs/, calculator-runs/, inbox)",
    default_args=default_args,
    schedule_interval="0 * * * *",  # hourly
    start_date=datetime(2026, 6, 7),
    catchup=False,
    tags=["cleanup", "pipeline"],
) as dag:

    cleanup_runs = SimpleHttpOperator(
        task_id="cleanup_runs",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/runs",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    cleanup_calculator_runs = SimpleHttpOperator(
        task_id="cleanup_calculator_runs",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/calculator-runs",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    cleanup_inbox = SimpleHttpOperator(
        task_id="cleanup_inbox",
        http_conn_id="module_cleanup",
        endpoint="/api/internal/cleanup/inbox",
        method="POST",
        response_check=lambda r: r.status_code == 200,
        log_response=True,
    )

    [cleanup_runs, cleanup_calculator_runs, cleanup_inbox]
