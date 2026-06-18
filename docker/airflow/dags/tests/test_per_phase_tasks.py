"""Unit tests for per_phase_tasks.parse_scope — RED phase (Task 4).

Per TDD anti-pattern: tests for ONE behavior at a time.
Other factories (trigger/loop/stop/sensor) get their own RED→GREEN cycles.
"""
import pytest
from airflow.exceptions import AirflowException

from per_phase_tasks import parse_scope, ALLOWED_SCOPES


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