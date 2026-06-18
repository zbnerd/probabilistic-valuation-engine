"""Unit tests for per_phase_tasks.parse_scope — RED phase (Task 4).

Per TDD anti-pattern: tests for ONE behavior at a time.
Other factories (trigger/loop/stop/sensor) get their own RED→GREEN cycles.
"""
from unittest.mock import MagicMock, patch

import pytest
import requests
from airflow.exceptions import AirflowException

from per_phase_tasks import (
    parse_scope, ALLOWED_SCOPES,
    make_trigger_task, make_loop_task, make_stop_task,
    make_is_phase_terminal,
)


@pytest.mark.parametrize(
    "conf,expected",
    [
        ({}, ["FULL_DAILY"]),
        ({"scope": "FULL_DAILY"}, ["FULL_DAILY"]),
        ({"scope": "ITEM_EQUIPMENT"}, ["ITEM_EQUIPMENT"]),
        (
            {"scope": ["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"]},
            ["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"],
        ),
        ({"scope": ["RANKING_FETCH", "OCID_LOOKUP"]}, ["RANKING_FETCH", "OCID_LOOKUP"]),
    ],
)
def test_parse_scope_valid(conf, expected):
    assert parse_scope(conf) == expected


@pytest.mark.parametrize(
    "conf",
    [
        {"scope": "RANKING_FETCH_LOOP"},          # not in loopable set
        {"scope": ["RANKING_FETCH_LOOP"]},        # list with invalid
        {"scope": "INVALID"},                     # unknown value
        {"scope": ["INVALID"]},                   # list with unknown
        {"scope": ["ITEM_EQUIPMENT", "FOO"]},     # partial list with one invalid
    ],
)
def test_parse_scope_invalid(conf):
    with pytest.raises(AirflowException):
        parse_scope(conf)


def test_allowed_scopes_constant_matches_spec():
    """Guard: if spec adds a scope value, this test forces an update."""
    expected = {
        "RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT",
        "OCID_LOOKUP_LOOP", "CHARACTER_BASIC_LOOP", "ITEM_EQUIPMENT_LOOP",
        "RANKING_FETCH_STOP", "OCID_LOOKUP_STOP",
        "CHARACTER_BASIC_STOP", "ITEM_EQUIPMENT_STOP",
    }
    assert set(ALLOWED_SCOPES) == expected


# ─── make_trigger_task (Task 6 RED) ───────────────────────────────────────


def _make_ctx(conf, xcom_value=None):
    """Build a minimal Airflow task context dict."""
    dag_run = MagicMock()
    dag_run.conf = conf
    ti = MagicMock()
    ti.xcom_pull.return_value = xcom_value
    return {"dag_run": dag_run, "ti": ti}


def test_make_trigger_task_skips_when_scope_empty(mock_external_api_conn):
    """If phase not in scope, callable returns None (Airflow marks skipped)."""
    task = make_trigger_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["OCID_LOOKUP"]})
    assert task.python_callable(**ctx) is None


def test_make_trigger_task_happy_path(mock_external_api_conn):
    """200 → returns response JSON, no exception."""
    task = make_trigger_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})
    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 202
        mock_resp.json.return_value = {"runId": "r-1", "phase": "ITEM_EQUIPMENT"}
        mock_post.return_value = mock_resp

        result = task.python_callable(**ctx)

    assert result == {"runId": "r-1", "phase": "ITEM_EQUIPMENT"}
    mock_post.assert_called_once()
    assert "/trigger/phase/ITEM_EQUIPMENT" in mock_post.call_args[0][0]


def test_make_trigger_task_409_discovers_active_run(mock_external_api_conn):
    """409 → fetch /run-status, return active runId with ALREADY_ACTIVE status."""
    task = make_trigger_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})

    with patch("per_phase_tasks.requests.post") as mock_post, \
         patch("per_phase_tasks.requests.get") as mock_get:
        trigger_resp = MagicMock()
        trigger_resp.status_code = 409
        mock_post.return_value = trigger_resp

        status_resp = MagicMock()
        status_resp.status_code = 200
        status_resp.json.return_value = {"current": {"runId": "r-active"}}
        status_resp.raise_for_status = MagicMock()
        mock_get.return_value = status_resp

        result = task.python_callable(**ctx)

    assert result == {
        "runId": "r-active",
        "phase": "ITEM_EQUIPMENT",
        "status": "ALREADY_ACTIVE",
    }


def test_make_trigger_task_500_raises(mock_external_api_conn):
    """500 → AirflowException."""
    task = make_trigger_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 500
        mock_resp.text = "boom"
        mock_post.return_value = mock_resp

        with pytest.raises(AirflowException):
            task.python_callable(**ctx)


def test_make_trigger_task_network_error_raises(mock_external_api_conn):
    """requests.RequestException → AirflowException with chained cause."""
    task = make_trigger_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_post.side_effect = requests.RequestException("connection refused")

        with pytest.raises(AirflowException, match="Trigger ITEM_EQUIPMENT failed"):
            task.python_callable(**ctx)


# ─── make_loop_task (Task 8 RED) ───────────────────────────────────────────


def test_make_loop_task_skips_when_scope_empty(mock_external_api_conn):
    """If {phase}_LOOP not in scope, returns None."""
    task = make_loop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})  # bare, not _LOOP
    assert task.python_callable(**ctx) is None


def test_make_loop_task_happy_path(mock_external_api_conn):
    """202 → returns response JSON with loopId."""
    task = make_loop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_LOOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 202
        mock_resp.json.return_value = {
            "status": "LOOP_STARTED",
            "phase": "ITEM_EQUIPMENT",
            "loopId": "loop-1",
            "iterationCount": 0,
        }
        mock_post.return_value = mock_resp

        result = task.python_callable(**ctx)

    assert result["loopId"] == "loop-1"
    assert "/loop/phase/ITEM_EQUIPMENT" in mock_post.call_args[0][0]


def test_make_loop_task_409_idempotent(mock_external_api_conn):
    """409 → push existing loopId with ALREADY_LOOPING status."""
    task = make_loop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_LOOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 409
        mock_resp.json.return_value = {
            "status": "LOOP_ALREADY_ACTIVE",
            "phase": "ITEM_EQUIPMENT",
            "loopId": "loop-existing",
        }
        mock_post.return_value = mock_resp

        result = task.python_callable(**ctx)

    assert result["loopId"] == "loop-existing"
    assert result["status"] == "ALREADY_LOOPING"


def test_make_loop_task_400_invalid_phase_raises(mock_external_api_conn):
    """400 → AirflowException (config error)."""
    task = make_loop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_LOOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 400
        mock_resp.json.return_value = {"error": "INVALID_PHASE"}
        mock_post.return_value = mock_resp

        with pytest.raises(AirflowException, match="INVALID_PHASE"):
            task.python_callable(**ctx)


def test_make_loop_task_500_raises(mock_external_api_conn):
    """500 → AirflowException (not config error)."""
    task = make_loop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_LOOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 500
        mock_resp.text = "boom"
        mock_post.return_value = mock_resp

        with pytest.raises(AirflowException, match="Loop start ITEM_EQUIPMENT failed"):
            task.python_callable(**ctx)


# ─── make_stop_task (Task 10 RED) ──────────────────────────────────────────


def test_make_stop_task_skips_when_scope_empty(mock_external_api_conn):
    """If {phase}_STOP not in scope, returns None."""
    task = make_stop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT"]})
    assert task.python_callable(**ctx) is None


def test_make_stop_task_happy_path_202(mock_external_api_conn):
    """202 STOP_REQUESTED → returns status."""
    task = make_stop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_STOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 202
        mock_resp.json.return_value = {
            "status": "STOP_REQUESTED",
            "phase": "ITEM_EQUIPMENT",
            "loopId": "loop-1",
        }
        mock_post.return_value = mock_resp

        result = task.python_callable(**ctx)

    assert result["status"] == "STOP_REQUESTED"
    assert "/stop/phase/ITEM_EQUIPMENT" in mock_post.call_args[0][0]


def test_make_stop_task_200_not_running(mock_external_api_conn):
    """200 NOT_RUNNING → idempotent success."""
    task = make_stop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_STOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 200
        mock_resp.json.return_value = {"status": "NOT_RUNNING"}
        mock_post.return_value = mock_resp

        result = task.python_callable(**ctx)

    assert result == {"phase": "ITEM_EQUIPMENT", "status": "NOT_RUNNING"}


def test_make_stop_task_500_raises(mock_external_api_conn):
    """500 → AirflowException."""
    task = make_stop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_STOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_resp = MagicMock()
        mock_resp.status_code = 500
        mock_resp.text = "boom"
        mock_post.return_value = mock_resp

        with pytest.raises(AirflowException, match="Stop ITEM_EQUIPMENT failed"):
            task.python_callable(**ctx)


def test_make_stop_task_network_error_raises(mock_external_api_conn):
    """requests.RequestException → AirflowException with chained cause."""
    task = make_stop_task("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["ITEM_EQUIPMENT_STOP"]})

    with patch("per_phase_tasks.requests.post") as mock_post:
        mock_post.side_effect = requests.RequestException("connection refused")

        with pytest.raises(AirflowException, match="Stop ITEM_EQUIPMENT failed"):
            task.python_callable(**ctx)


# ─── make_is_phase_terminal (Task 12 RED) ──────────────────────────────────


def test_sensor_returns_true_when_scope_excludes_phase(mock_external_api_conn):
    """If phase not in scope, sensor returns True (skip)."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx({"scope": ["OCID_LOOKUP"]})
    assert sensor(**ctx) is True


def test_sensor_returns_true_when_terminal(mock_external_api_conn):
    """runId matches + current.terminal=True → True."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx(
        {"scope": ["ITEM_EQUIPMENT"]},
        xcom_value={"runId": "r-1", "phase": "ITEM_EQUIPMENT"},
    )

    with patch("per_phase_tasks.requests.get") as mock_get:
        status_resp = MagicMock()
        status_resp.status_code = 200
        status_resp.json.return_value = {
            "current": {
                "runId": "r-1",
                "phase": "ITEM_EQUIPMENT",
                "terminal": True,
            }
        }
        status_resp.raise_for_status = MagicMock()
        mock_get.return_value = status_resp

        assert sensor(**ctx) is True


def test_sensor_raises_when_run_failed(mock_external_api_conn):
    """current.phase == FAILED → RuntimeError."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx(
        {"scope": ["ITEM_EQUIPMENT"]},
        xcom_value={"runId": "r-1"},
    )

    with patch("per_phase_tasks.requests.get") as mock_get:
        status_resp = MagicMock()
        status_resp.status_code = 200
        status_resp.json.return_value = {
            "current": {
                "runId": "r-1",
                "phase": "FAILED",
                "errorMessage": "boom",
            }
        }
        status_resp.raise_for_status = MagicMock()
        mock_get.return_value = status_resp

        with pytest.raises(RuntimeError, match="Run r-1 failed"):
            sensor(**ctx)


def test_sensor_returns_false_on_transient_http(mock_external_api_conn):
    """Network error → False (reschedule)."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx(
        {"scope": ["ITEM_EQUIPMENT"]},
        xcom_value={"runId": "r-1"},
    )

    with patch("per_phase_tasks.requests.get") as mock_get:
        mock_get.side_effect = requests.RequestException("connection refused")

        assert sensor(**ctx) is False


def test_sensor_returns_false_when_runid_mismatch(mock_external_api_conn):
    """current.runId != trigger's runId → False."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx(
        {"scope": ["ITEM_EQUIPMENT"]},
        xcom_value={"runId": "r-1"},
    )

    with patch("per_phase_tasks.requests.get") as mock_get:
        status_resp = MagicMock()
        status_resp.status_code = 200
        status_resp.json.return_value = {
            "current": {"runId": "r-other", "phase": "ITEM_EQUIPMENT"}
        }
        status_resp.raise_for_status = MagicMock()
        mock_get.return_value = status_resp

        assert sensor(**ctx) is False


def test_sensor_raises_when_xcom_missing_runid(mock_external_api_conn):
    """If xcom has no runId, RuntimeError (config error, fail-fast)."""
    sensor = make_is_phase_terminal("ITEM_EQUIPMENT")
    ctx = _make_ctx(
        {"scope": ["ITEM_EQUIPMENT"]},
        xcom_value={"phase": "ITEM_EQUIPMENT"},  # no runId
    )

    with pytest.raises(RuntimeError, match="no runId"):
        sensor(**ctx)


# ─── DAG loader integration (Task 14 RED) ─────────────────────────────────


def test_daily_collection_pipeline_parses():
    """DAG parses without import errors (regression guard for rewire)."""
    from airflow.models import DagBag
    import os
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    assert "daily_collection_pipeline" in dagbag.dags, (
        f"DAG missing. Errors: {dagbag.import_errors}"
    )
    assert dagbag.import_errors == {}, f"Import errors: {dagbag.import_errors}"


def test_branch_on_scope_task_exists():
    from airflow.models import DagBag
    import os
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    dag = dagbag.dags["daily_collection_pipeline"]
    assert "branch_on_scope" in dag.task_ids


def test_all_per_phase_tasks_present():
    """11 per-phase task definitions expected."""
    from airflow.models import DagBag
    import os
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    dag = dagbag.dags["daily_collection_pipeline"]
    expected = {
        # 4 trigger
        "per_phase_trigger_ranking_fetch",
        "per_phase_trigger_ocid_lookup",
        "per_phase_trigger_character_basic",
        "per_phase_trigger_item_equipment",
        # 3 loop (RANKING_FETCH_LOOP excluded)
        "per_phase_loop_ocid_lookup",
        "per_phase_loop_character_basic",
        "per_phase_loop_item_equipment",
        # 4 stop
        "per_phase_stop_ranking_fetch",
        "per_phase_stop_ocid_lookup",
        "per_phase_stop_character_basic",
        "per_phase_stop_item_equipment",
    }
    assert expected.issubset(set(dag.task_ids)), (
        f"Missing tasks: {expected - set(dag.task_ids)}"
    )


def test_branch_downstream_includes_both_paths():
    """branch_on_scope must route to both trigger_daily_collection AND per_phase_join."""
    from airflow.models import DagBag
    import os
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    dag = dagbag.dags["daily_collection_pipeline"]
    branch_task = dag.get_task("branch_on_scope")
    downstream_ids = {t.task_id for t in branch_task.downstream_list}
    assert "trigger_daily_collection" in downstream_ids
    assert "per_phase_join" in downstream_ids