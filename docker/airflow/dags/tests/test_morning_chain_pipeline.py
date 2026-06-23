"""Tests for morning_chain_pipeline DAG.

Validates structural invariants: dag_id, schedule, catchup, retries,
task count, factory wiring. Does not exercise the chain end-to-end
(manual Airflow run required for integration).
"""
import pytest


DAG_FOLDER = "/opt/airflow/dags"


@pytest.fixture(scope="module")
def dag():
    from airflow.models import DagBag
    # Airflow 2.10.5 DagBag parses all .py files in dag_folder at construction.
    # We point it at the live dags directory the scheduler uses.
    bag = DagBag(dag_folder=DAG_FOLDER, include_examples=False)
    assert not bag.import_errors, f"import errors: {bag.import_errors}"
    assert "morning_chain_pipeline" in bag.dags, (
        f"morning_chain_pipeline not in dags; available: {sorted(bag.dags)[:5]}..."
    )
    return bag.dags["morning_chain_pipeline"]


def test_dag_id(dag):
    assert dag.dag_id == "morning_chain_pipeline"


def test_schedule_is_3am_kst(dag):
    # 0 18 * * * UTC = 03:00 KST year-round (UTC+9, no DST).
    # Airflow 2.10.5 exposes the cron as `schedule_interval` (not `schedule`,
    # which is the Airflow 3.x name). Match every other DAG in this repo.
    assert dag.schedule_interval == "0 18 * * *"


def test_no_catchup(dag):
    assert dag.catchup is False


def test_start_date_set(dag):
    assert dag.start_date is not None


def test_no_retries(dag):
    assert dag.default_args.get("retries") == 0


def test_has_check_ext_api_health(dag):
    assert "check_ext_api_health" in {t.task_id for t in dag.tasks}


def test_has_trigger_stop_loop(dag):
    assert "trigger_stop_loop" in {t.task_id for t in dag.tasks}


def test_trigger_stop_loop_targets_correct_dag_with_phase_conf(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_stop_loop")
    assert t.trigger_dag_id == "stop_loop_pipeline"
    assert t.conf == {"phase": "ITEM_EQUIPMENT"}


def test_has_wait_loop_stopped_sensor(dag):
    """Factory's loop-stopped sensor: idempotent (True if no loop active)."""
    assert "wait_loop_stopped_item_equipment" in {t.task_id for t in dag.tasks}


def test_has_trigger_ranking_ocid(dag):
    assert "trigger_ranking_ocid" in {t.task_id for t in dag.tasks}


def test_trigger_ranking_ocid_targets_correct_dag(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_ranking_ocid")
    assert t.trigger_dag_id == "ranking_ocid_lookup_pipeline"


def test_has_wait_upstream_terminal_ocid_lookup(dag):
    assert "wait_upstream_terminal_ocid_lookup" in {t.task_id for t in dag.tasks}


def test_trigger_character_basic_conf_is_once(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_character_basic_once")
    assert t.trigger_dag_id == "character_basic_pipeline"
    assert t.conf == {"mode": "once"}


def test_trigger_item_equipment_conf_is_infinite(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_item_equipment_infinite")
    assert t.trigger_dag_id == "item_equipment_pipeline"
    assert t.conf == {"mode": "infinite"}


def test_has_wait_first_iteration_started(dag):
    assert "wait_first_iteration_started" in {t.task_id for t in dag.tasks}


def test_has_wait_upstream_terminal_character_basic(dag):
    assert "wait_upstream_terminal_character_basic" in {t.task_id for t in dag.tasks}
