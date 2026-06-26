"""Daily full-pipeline wrapper.

Chains ranking_ocid_lookup_pipeline → character_basic_pipeline(mode=once) →
item_equipment_pipeline(mode=once) → daily_cleanup_pipeline via
TriggerDagRunOperator (wait_for_completion=True). Scheduled at 18:00 UTC
(KST 03:00) — same cron as legacy daily_collection_pipeline.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.3
"""
import airflow  # noqa: F401  (required for DagBag safe_mode heuristic)
from airflow import DAG
from airflow.providers.http.sensors.http import HttpSensor
from airflow.operators.trigger_dagrun import TriggerDagRunOperator

from datetime import datetime


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="daily_full_pipeline",
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
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    trigger_ranking_ocid = TriggerDagRunOperator(
        task_id="trigger_ranking_ocid_lookup",
        trigger_dag_id="ranking_ocid_lookup_pipeline",
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_character_basic = TriggerDagRunOperator(
        task_id="trigger_character_basic",
        trigger_dag_id="character_basic_pipeline",
        conf={"mode": "once"},
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_item_equipment = TriggerDagRunOperator(
        task_id="trigger_item_equipment",
        trigger_dag_id="item_equipment_pipeline",
        conf={"mode": "once"},
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_cleanup = TriggerDagRunOperator(
        task_id="trigger_cleanup",
        trigger_dag_id="daily_cleanup_pipeline",
        wait_for_completion=True,  # fail loud if cleanup errors (Bug #6 fix)
        reset_dag_run=True,
    )

    check_external_api >> trigger_ranking_ocid
    trigger_ranking_ocid >> trigger_character_basic
    trigger_character_basic >> trigger_item_equipment
    trigger_item_equipment >> trigger_cleanup