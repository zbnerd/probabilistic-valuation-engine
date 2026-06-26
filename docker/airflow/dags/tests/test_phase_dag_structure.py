"""DAG-structure tests via DagBag parse.

These run against the actual DAG files in docker/airflow/dags/, not the
in-memory factory. They catch import errors and topology regressions.
"""
import pytest
from airflow.models import DagBag


DAG_FOLDER = "/home/maple/probabilistic-valuation-engine/docker/airflow/dags"


@pytest.fixture(scope="module")
def dagbag():
    return DagBag(dag_folder=DAG_FOLDER, include_examples=False)


def test_character_basic_pipeline_parses(dagbag):
    dag = dagbag.get_dag("character_basic_pipeline")
    assert dag is not None
    ids = {t.task_id for t in dag.tasks}
    assert "branch_on_mode" in ids
    assert "trigger_character_basic" in ids
    assert "trigger_loop_infinite_character_basic" in ids


def test_item_equipment_pipeline_parses(dagbag):
    dag = dagbag.get_dag("item_equipment_pipeline")
    assert dag is not None
    ids = {t.task_id for t in dag.tasks}
    assert "trigger_item_equipment" in ids
    assert "trigger_loop_infinite_item_equipment" in ids
    assert "count_sensor_item_equipment" in ids


def test_daily_full_pipeline_parses(dagbag):
    dag = dagbag.get_dag("daily_full_pipeline")
    assert dag is not None
    trigger_ids = [t.task_id for t in dag.tasks if t.task_id.startswith("trigger_")]
    assert len(trigger_ids) == 4


def test_stop_loop_pipeline_parses(dagbag):
    dag = dagbag.get_dag("stop_loop_pipeline")
    assert dag is not None
    ids = {t.task_id for t in dag.tasks}
    assert "stop_loop_character_basic" in ids
    assert "stop_loop_item_equipment" in ids
    assert "wait_loop_stopped_character_basic" in ids
    assert "wait_loop_stopped_item_equipment" in ids


def test_ranking_ocid_lookup_pipeline_parses(dagbag):
    dag = dagbag.get_dag("ranking_ocid_lookup_pipeline")
    assert dag is not None
    ids = {t.task_id for t in dag.tasks}
    assert "trigger_ranking_fetch" in ids
    assert "wait_ranking_fetch_terminal" in ids
    assert "trigger_ocid_lookup" in ids
    assert "wait_ocid_lookup_terminal" in ids
    assert "upstream_run_id" in ids


def test_legacy_dag_still_parses(dagbag):
    """daily_collection_pipeline must still parse for one release cycle."""
    dag = dagbag.get_dag("daily_collection_pipeline")
    assert dag is not None
    assert "deprecated" in dag.tags
