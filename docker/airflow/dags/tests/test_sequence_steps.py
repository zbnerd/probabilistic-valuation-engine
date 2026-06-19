"""Unit tests for parse_steps (sequence validator)."""
import pytest
from airflow.exceptions import AirflowException
from per_phase_tasks import parse_steps


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