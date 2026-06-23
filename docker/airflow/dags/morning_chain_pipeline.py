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
    pass  # tasks added in subsequent tasks
