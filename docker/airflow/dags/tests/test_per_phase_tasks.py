"""Unit tests for per_phase_tasks.parse_scope — RED phase (Task 4).

Per TDD anti-pattern: tests for ONE behavior at a time.
Other factories (trigger/loop/stop/sensor) get their own RED→GREEN cycles.
"""
from unittest.mock import MagicMock, patch

import pytest
import requests
from airflow.exceptions import AirflowException

from per_phase_tasks import parse_scope, ALLOWED_SCOPES, make_trigger_task, make_loop_task


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