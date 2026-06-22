"""Unit tests for phase_pipeline_factory helpers."""
import pytest
from airflow.exceptions import AirflowException

from phase_pipeline_factory import parse_mode


class TestParseMode:
    def test_default_empty_conf_raises(self):
        with pytest.raises(AirflowException, match="mode is required"):
            parse_mode({})

    def test_mode_once(self):
        assert parse_mode({"mode": "once"}) == ("once", 0)

    def test_mode_count_valid(self):
        assert parse_mode({"mode": "count", "count": 5}) == ("count", 5)

    def test_mode_count_missing_count_raises(self):
        with pytest.raises(AirflowException, match="count is required"):
            parse_mode({"mode": "count"})

    def test_mode_count_zero_raises(self):
        with pytest.raises(AirflowException, match="count must be >= 1"):
            parse_mode({"mode": "count", "count": 0})

    def test_mode_count_negative_raises(self):
        with pytest.raises(AirflowException, match="count must be >= 1"):
            parse_mode({"mode": "count", "count": -1})

    def test_mode_infinite(self):
        assert parse_mode({"mode": "infinite"}) == ("infinite", 0)

    def test_mode_infinite_ignores_count(self):
        assert parse_mode({"mode": "infinite", "count": 999}) == ("infinite", 0)

    def test_mode_invalid_raises(self):
        with pytest.raises(AirflowException, match="invalid mode"):
            parse_mode({"mode": "foo"})

    def test_mode_wrong_type_raises(self):
        with pytest.raises(AirflowException):
            parse_mode({"mode": 123})

    @pytest.mark.parametrize(
        "conf",
        [
            {"mode": "count"},          # missing count
            {"mode": "count", "count": 0},
            {"mode": "count", "count": -3},
            {"mode": "INVALID"},
            {"mode": "ONCE"},            # case-sensitive
        ],
    )
    def test_invalid_confs_raise(self, conf):
        with pytest.raises(AirflowException):
            parse_mode(conf)
