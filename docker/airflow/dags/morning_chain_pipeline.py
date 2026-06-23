"""morning_chain_pipeline — 03:00 KST chain orchestration.

Schedule: 0 18 * * * UTC (03:00 KST year-round, UTC+9 no DST).
Composes existing per-phase DAGs via factory helpers:
  1. stop_loop_pipeline (loop auto-stops if none active — idempotent)
  2. ranking_ocid_lookup_pipeline
  3. character_basic_pipeline (mode=once)
  4. item_equipment_pipeline (mode=infinite)

Sensors between triggers use the factory:
  - make_wait_loop_stopped_sensor  : gates step 1
  - make_wait_phase_terminal_sensor: gates steps 2-3
  - custom iteration-started check  : gates step 4

Refs: docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md
"""
from datetime import datetime

from airflow import DAG
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.sensors.http import HttpSensor

from phase_pipeline_factory import (
    make_wait_loop_stopped_sensor,
    make_wait_phase_terminal_sensor,
)


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="morning_chain_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # 03:00 KST
    catchup=False,
    tags=["pipeline", "chain", "morning"],
) as dag:

    check_ext_api_health = HttpSensor(
        task_id="check_ext_api_health",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    trigger_stop_loop = TriggerDagRunOperator(
        task_id="trigger_stop_loop",
        trigger_dag_id="stop_loop_pipeline",
        conf={"phase": "ITEM_EQUIPMENT"},
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Factory sensor — idempotent: returns True if no loop is active
    # (sensor task_id auto-generated: wait_loop_stopped_item_equipment).
    wait_loop_stopped = make_wait_loop_stopped_sensor("ITEM_EQUIPMENT")

    trigger_ranking_ocid = TriggerDagRunOperator(
        task_id="trigger_ranking_ocid",
        trigger_dag_id="ranking_ocid_lookup_pipeline",
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Factory sensor — returns True when current.phase progresses past OCID_LOOKUP
    # (auto-generated task_id: wait_upstream_terminal_ocid_lookup).
    wait_ocid_lookup_terminal = make_wait_phase_terminal_sensor("OCID_LOOKUP")

    check_ext_api_health >> trigger_stop_loop
    trigger_stop_loop >> wait_loop_stopped >> trigger_ranking_ocid
    trigger_ranking_ocid >> wait_ocid_lookup_terminal
