"""RANKING_FETCH → OCID_LOOKUP sequential pipeline.

OCID_LOOKUP requires X-Upstream-Run-Id from RANKING_FETCH (ext-api
InternalApiController rejects with MISSING_UPSTREAM 400 otherwise), so
these two phases are always chained. Manual trigger only.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.1
"""
from datetime import datetime

from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.providers.http.sensors.http import HttpSensor
from airflow.sensors.python import PythonSensor

from per_phase_tasks import make_is_phase_terminal
from phase_pipeline_factory import make_trigger_once_task


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="ranking_ocid_lookup_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,  # manual trigger (or via daily_full_pipeline wrapper)
    catchup=False,
    tags=["pipeline", "phase", "ranking-ocid"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    # RANKING_FETCH — no upstream required. Uses factory helper so its
    # task_id is `trigger_ranking_fetch` (matches factory convention).
    trigger_ranking_fetch = make_trigger_once_task("RANKING_FETCH")
    wait_ranking_fetch_terminal = PythonSensor(
        task_id="wait_ranking_fetch_terminal",
        python_callable=make_is_phase_terminal("RANKING_FETCH"),
        mode="reschedule",
        poke_interval=60,
        timeout=4 * 60 * 60,
    )

    # OCID_LOOKUP — requires X-Upstream-Run-Id from RANKING_FETCH trigger.
    # The factory's _trigger_once_fn xcom_pulls from task_id="upstream_run_id"
    # (must match exactly — Bug #4 fix). Add a tiny task to push the runId
    # into that slot:
    def _push_upstream_run_id(**ctx):
        import json

        ti = ctx["ti"]
        trigger_resp = ti.xcom_pull(task_ids="trigger_ranking_fetch")
        if isinstance(trigger_resp, str):
            trigger_resp = json.loads(trigger_resp)
        return trigger_resp.get("runId") if trigger_resp else None

    push_upstream = PythonOperator(
        task_id="upstream_run_id",  # must match factory's xcom_pull task_id
        python_callable=_push_upstream_run_id,
    )

    trigger_ocid_lookup = make_trigger_once_task("OCID_LOOKUP")
    wait_ocid_lookup_terminal = PythonSensor(
        task_id="wait_ocid_lookup_terminal",
        python_callable=make_is_phase_terminal("OCID_LOOKUP"),
        mode="reschedule",
        poke_interval=60,
        timeout=4 * 60 * 60,
    )

    check_external_api >> trigger_ranking_fetch
    trigger_ranking_fetch >> wait_ranking_fetch_terminal
    wait_ranking_fetch_terminal >> push_upstream
    push_upstream >> trigger_ocid_lookup
    trigger_ocid_lookup >> wait_ocid_lookup_terminal