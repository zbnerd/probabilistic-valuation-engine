"""Stop any active loop for a given phase.

Used to terminate mode=infinite loops started by character_basic_pipeline or
item_equipment_pipeline. POSTs /stop/loop/phase/{phase} and waits for the
loop to drain current chunk + transition to STOPPED.

Use:
  airflow dags trigger stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'

The conf['phase'] is consumed by make_stop_loop_task's skip pattern
(_stop_loop_fn gates on conf.phase); the unmatched sibling returns None
(Airflow skip) so both branches can run in parallel without an extra
_validate_phase task.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.4
"""
import airflow  # noqa: F401  (required for DagBag safe_mode heuristic)
from datetime import datetime

from airflow import DAG
from airflow.providers.http.sensors.http import HttpSensor

from phase_pipeline_factory import (
    make_stop_loop_task,
    make_wait_loop_stopped_sensor,
)


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="stop_loop_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,
    catchup=False,
    tags=["pipeline", "control", "stop"],
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

    # Single DAG handles both CHARACTER_BASIC and ITEM_EQUIPMENT via conf.
    # Each task's _stop_loop_fn checks conf.phase and returns None (skip) if
    # it doesn't match — so only the targeted phase actually stops.
    stop_character_basic = make_stop_loop_task("CHARACTER_BASIC")
    stop_item_equipment = make_stop_loop_task("ITEM_EQUIPMENT")

    wait_character_basic = make_wait_loop_stopped_sensor("CHARACTER_BASIC")
    wait_item_equipment = make_wait_loop_stopped_sensor("ITEM_EQUIPMENT")

    check_external_api >> [stop_character_basic, stop_item_equipment]
    stop_character_basic >> wait_character_basic
    stop_item_equipment >> wait_item_equipment