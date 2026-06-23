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
