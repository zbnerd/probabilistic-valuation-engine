"""DAG import smoke test for CI.

Asserts all expected DAGs (new + legacy) parse without import errors.
"""
from airflow.models import DagBag


DAG_FOLDER = "/home/maple/probabilistic-valuation-engine/docker/airflow/dags"


EXPECTED_DAG_IDS = {
    # New phase-separated DAGs (2026-06-22 restructure)
    "ranking_ocid_lookup_pipeline",
    "character_basic_pipeline",
    "item_equipment_pipeline",
    "daily_full_pipeline",
    "stop_loop_pipeline",
    # Existing DAGs (unchanged)
    "daily_cleanup_pipeline",
    # Legacy (deprecated; must still parse)
    "daily_collection_pipeline",
}


def test_all_expected_dags_import():
    dagbag = DagBag(dag_folder=DAG_FOLDER, include_examples=False)
    assert dagbag.import_errors == {}, (
        f"DAG import errors: {dagbag.import_errors}"
    )
    for dag_id in EXPECTED_DAG_IDS:
        assert dag_id in dagbag.dags, (
            f"Expected DAG '{dag_id}' missing from DagBag. "
            f"Loaded: {sorted(dagbag.dags.keys())}"
        )


def test_no_unexpected_dags():
    """Sanity: only known DAGs in the folder."""
    dagbag = DagBag(dag_folder=DAG_FOLDER, include_examples=False)
    loaded = set(dagbag.dags.keys())
    unexpected = loaded - EXPECTED_DAG_IDS
    assert not unexpected, f"Unexpected DAGs in folder: {unexpected}"