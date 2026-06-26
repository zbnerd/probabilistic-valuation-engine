"""Unit tests for parse_steps (sequence validator)."""
import time
from unittest.mock import MagicMock, patch

import pytest
import requests
from airflow.exceptions import AirflowException
from per_phase_tasks import parse_steps, wait_for_phase_terminal


def test_parse_steps_valid_4step_chain():
    conf = {
        "steps": [
            {"action": "trigger", "phase": "RANKING_FETCH"},
            {"action": "trigger", "phase": "OCID_LOOKUP"},
            {"action": "trigger", "phase": "CHARACTER_BASIC"},
            {"action": "loop", "phase": "ITEM_EQUIPMENT"},
        ]
    }
    assert parse_steps(conf) == conf["steps"]


def test_parse_steps_rejects_ocid_lookup_loop():
    conf = {"steps": [{"action": "loop", "phase": "OCID_LOOKUP"}]}
    with pytest.raises(AirflowException, match="loop not allowed on OCID_LOOKUP"):
        parse_steps(conf)


def test_parse_steps_rejects_ranking_fetch_loop():
    conf = {"steps": [{"action": "loop", "phase": "RANKING_FETCH"}]}
    with pytest.raises(AirflowException, match="loop not allowed on RANKING_FETCH"):
        parse_steps(conf)


def test_parse_steps_rejects_mutually_exclusive_scope_and_steps():
    conf = {
        "scope": ["CHARACTER_BASIC"],
        "steps": [{"action": "trigger", "phase": "OCID_LOOKUP"}],
    }
    with pytest.raises(AirflowException, match="mutually exclusive"):
        parse_steps(conf)


def test_parse_steps_rejects_unknown_phase():
    conf = {"steps": [{"action": "trigger", "phase": "FOOBAR"}]}
    with pytest.raises(AirflowException, match="FOOBAR"):
        parse_steps(conf)


def test_parse_steps_rejects_unknown_action():
    conf = {"steps": [{"action": "fly", "phase": "RANKING_FETCH"}]}
    with pytest.raises(AirflowException, match="fly"):
        parse_steps(conf)


def test_parse_steps_allows_character_basic_loop():
    conf = {"steps": [{"action": "loop", "phase": "CHARACTER_BASIC"}]}
    assert parse_steps(conf) == conf["steps"]


def test_parse_steps_allows_item_equipment_loop():
    conf = {"steps": [{"action": "loop", "phase": "ITEM_EQUIPMENT"}]}
    assert parse_steps(conf) == conf["steps"]


def test_parse_steps_rejects_empty_steps_list():
    conf = {"steps": []}
    with pytest.raises(AirflowException, match="empty"):
        parse_steps(conf)


def test_parse_steps_returns_empty_when_steps_field_missing():
    """Caller (branch_on_scope) routes on this; absent means full-daily fallback."""
    assert parse_steps({}) == []


def test_parse_steps_rejects_non_list_steps():
    conf = {"steps": "RANKING_FETCH,OCID_LOOKUP"}
    with pytest.raises(AirflowException, match="must be a list"):
        parse_steps(conf)


def test_parse_steps_rejects_non_dict_step():
    conf = {"steps": ["RANKING_FETCH"]}
    with pytest.raises(AirflowException, match="must be a dict"):
        parse_steps(conf)


def test_wait_for_phase_terminal_returns_on_terminal_true():
    """Polls once, finds terminal=True, returns."""
    with patch("per_phase_tasks.get_external_api_base", return_value="http://test:8081"), \
         patch("per_phase_tasks.requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.json.return_value = {
            "slots": {
                "RANKING_FETCH": {
                    "runId": "20260619-100000-100",
                    "phase": "COMPLETED",
                    "terminal": True,
                }
            },
            "lastCompletedByPhase": {},
        }
        mock_resp.raise_for_status = MagicMock()
        mock_get.return_value = mock_resp
        wait_for_phase_terminal("RANKING_FETCH", "20260619-100000-100")


def test_wait_for_phase_terminal_uses_run_group_prefix():
    """runId's date-time prefix identifies the run across 4 phases."""
    with patch("per_phase_tasks.get_external_api_base", return_value="http://test:8081"), \
         patch("per_phase_tasks.requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.json.return_value = {
            "slots": {
                "RANKING_FETCH": {
                    "runId": "20260619-100000-200",  # different phase same prefix
                    "phase": "IN_PROGRESS",
                    "terminal": False,
                },
                "OCID_LOOKUP": {
                    "runId": "20260619-100000-100",  # our run
                    "phase": "COMPLETED",
                    "terminal": True,
                },
            },
            "lastCompletedByPhase": {},
        }
        mock_resp.raise_for_status = MagicMock()
        mock_get.return_value = mock_resp
        wait_for_phase_terminal("OCID_LOOKUP", "20260619-100000-100")


def test_wait_for_phase_terminal_raises_on_failed():
    """FAILED phase → RuntimeError."""
    with patch("per_phase_tasks.get_external_api_base", return_value="http://test:8081"), \
         patch("per_phase_tasks.requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.json.return_value = {
            "slots": {
                "RANKING_FETCH": {
                    "runId": "20260619-100000-100",
                    "phase": "FAILED",
                    "terminal": True,
                    "errorMessage": "boom",
                }
            },
            "lastCompletedByPhase": {},
        }
        mock_resp.raise_for_status = MagicMock()
        mock_get.return_value = mock_resp
        with pytest.raises(RuntimeError, match="boom"):
            wait_for_phase_terminal("RANKING_FETCH", "20260619-100000-100")


def test_wait_for_phase_terminal_times_out():
    """If phase never reaches terminal within timeout, raise."""
    with patch("per_phase_tasks.get_external_api_base", return_value="http://test:8081"), \
         patch("per_phase_tasks.requests.get") as mock_get:
        mock_resp = MagicMock()
        mock_resp.json.return_value = {
            "slots": {
                "RANKING_FETCH": {
                    "runId": "20260619-100000-100",
                    "phase": "IN_PROGRESS",
                    "terminal": False,
                }
            },
            "lastCompletedByPhase": {},
        }
        mock_resp.raise_for_status = MagicMock()
        mock_get.return_value = mock_resp
        # poll_interval=0 so we don't actually wait; just check the loop logic.
        with pytest.raises(TimeoutError, match="did not reach terminal"):
            wait_for_phase_terminal(
                "RANKING_FETCH",
                "20260619-100000-100",
                timeout_seconds=0.1,
                poll_interval=0.05,
            )