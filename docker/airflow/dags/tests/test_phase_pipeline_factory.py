"""Unit tests for phase_pipeline_factory helpers."""
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException

from phase_pipeline_factory import (
    _make_count_sensor_runtime,
    _PHASE_TO_ENDPOINT,
    get_external_api_base,
    make_trigger_loop_task,
    make_trigger_once_task,
    parse_mode,
)


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


class TestGetExternalApiBase:
    def test_returns_http_url(self):
        with patch("phase_pipeline_factory.BaseHook") as mock_hook:
            mock_conn = MagicMock()
            mock_conn.host = "external-api"
            mock_conn.port = 8081
            mock_hook.get_connection.return_value = mock_conn
            url = get_external_api_base()
        assert url == "http://external-api:8081"


class TestMakeTriggerOnceTask:
    """Tests via the underlying PythonOperator callable (callable._trigger)."""

    def _get_callable(self, phase):
        op = make_trigger_once_task(phase)
        return op.python_callable

    def test_success_returns_run_id(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 202
            mock_resp.json.return_value = {"runId": "abc-123", "status": "STARTED"}
            mock_post.return_value = mock_resp
            result = self._get_callable("CHARACTER_BASIC")(
                dag_run=MagicMock(conf={})
            )
        assert result == {"runId": "abc-123", "status": "STARTED"}

    def test_409_already_active_returns_active_run_id(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post, \
             patch("phase_pipeline_factory.requests.get") as mock_get:
            post_resp = MagicMock()
            post_resp.status_code = 409
            mock_post.return_value = post_resp
            get_resp = MagicMock()
            get_resp.status_code = 200
            get_resp.json.return_value = {
                "slots": {"CHARACTER_BASIC": {"runId": "active-run", "phase": "CHARACTER_BASIC"}},
                "lastCompletedByPhase": {},
            }
            mock_get.return_value = get_resp
            result = self._get_callable("CHARACTER_BASIC")(
                dag_run=MagicMock(conf={})
            )
        assert result["status"] == "ALREADY_ACTIVE"
        assert result["runId"] == "active-run"

    def test_400_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 400
            mock_resp.text = "INVALID_PHASE"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException, match="rejected"):
                self._get_callable("CHARACTER_BASIC")(
                    dag_run=MagicMock(conf={})
                )

    def test_500_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 500
            mock_resp.text = "internal error"
            mock_resp.reason = "Internal Server Error"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("CHARACTER_BASIC")(
                    dag_run=MagicMock(conf={})
                )

    def test_includes_upstream_header_when_run_id_provided(self):
        """When upstream_run_id xcom is passed, send X-Upstream-Run-Id header."""
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 202
            mock_resp.json.return_value = {"runId": "child"}
            mock_post.return_value = mock_resp
            self._get_callable("OCID_LOOKUP")(
                dag_run=MagicMock(conf={}),
                ti=MagicMock(xcom_pull=MagicMock(return_value="upstream-123")),
            )
        call_kwargs = mock_post.call_args.kwargs
        assert call_kwargs["headers"]["X-Upstream-Run-Id"] == "upstream-123"


class TestMakeTriggerLoopTask:
    def _get_callable(self, phase):
        op = make_trigger_loop_task(phase)
        return op.python_callable

    def test_success_returns_loop_id(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 202
            mock_resp.json.return_value = {
                "loopId": "loop-1", "phase": "ITEM_EQUIPMENT", "iterationCount": 0,
            }
            mock_post.return_value = mock_resp
            result = self._get_callable("ITEM_EQUIPMENT")(
                dag_run=MagicMock(conf={})
            )
        assert result["loopId"] == "loop-1"

    def test_409_already_looping(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 409
            mock_resp.json.return_value = {"loopId": "existing", "phase": "ITEM_EQUIPMENT"}
            mock_post.return_value = mock_resp
            result = self._get_callable("ITEM_EQUIPMENT")(
                dag_run=MagicMock(conf={})
            )
        assert result["status"] == "ALREADY_LOOPING"
        assert result["loopId"] == "existing"

    def test_400_invalid_phase_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 400
            mock_resp.text = "INVALID_PHASE"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("RANKING_FETCH")(
                    dag_run=MagicMock(conf={})
                )

    def test_500_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 503
            mock_resp.text = "service unavailable"
            mock_resp.reason = "Service Unavailable"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("ITEM_EQUIPMENT")(
                    dag_run=MagicMock(conf={})
                )


class TestPhaseToEndpoint:
    def test_character_basic(self):
        assert _PHASE_TO_ENDPOINT["CHARACTER_BASIC"] == "character-basic"

    def test_item_equipment(self):
        assert _PHASE_TO_ENDPOINT["ITEM_EQUIPMENT"] == "item-equipment"


class TestMakeCountSensorRuntime:
    """Tests the runtime count sensor (reads count from dag_run.conf)."""

    def _get_callable_and_op(self, phase):
        op = _make_count_sensor_runtime(phase)
        return op.python_callable, op

    def test_returns_true_after_count_events(self):
        """Sensor returns True after `count` matching events received."""
        from datetime import timedelta

        callable_fn, op = self._get_callable_and_op("ITEM_EQUIPMENT")

        fake_msg1 = MagicMock()
        fake_msg1.value = {"endpoint": "item-equipment", "runId": "r1"}
        fake_msg2 = MagicMock()
        fake_msg2.value = {"endpoint": "item-equipment", "runId": "r2"}
        fake_msg3 = MagicMock()
        fake_msg3.value = {"endpoint": "item-equipment", "runId": "r3"}

        ctx = {
            "dag_run": MagicMock(conf={"mode": "count", "count": 2}, run_id="20260622-x"),
        }
        with patch("phase_pipeline_factory.KafkaConsumer") as mock_consumer_cls:
            instance = MagicMock()
            instance.__iter__ = MagicMock(
                return_value=iter([fake_msg1, fake_msg2, fake_msg3])
            )
            mock_consumer_cls.return_value = instance
            result = callable_fn(**ctx)
        assert result is True
        # Airflow stores sensor timeout as float seconds internally.
        assert op.timeout == 12 * 60 * 60
        assert op.mode == "reschedule"
        assert op.poke_interval == 30

    def test_filters_by_endpoint(self):
        callable_fn, op = self._get_callable_and_op("CHARACTER_BASIC")

        fake_msg_other = MagicMock()
        fake_msg_other.value = {"endpoint": "item-equipment", "runId": "r1"}
        fake_msg_match = MagicMock()
        fake_msg_match.value = {"endpoint": "character-basic", "runId": "r2"}

        ctx = {
            "dag_run": MagicMock(
                conf={"mode": "count", "count": 1}, run_id="20260622-x"
            ),
        }
        with patch("phase_pipeline_factory.KafkaConsumer") as mock_consumer_cls:
            instance = MagicMock()
            instance.__iter__ = MagicMock(
                return_value=iter([fake_msg_other, fake_msg_match])
            )
            mock_consumer_cls.return_value = instance
            result = callable_fn(**ctx)
        assert result is True

    def test_raises_for_invalid_conf(self):
        callable_fn, op = self._get_callable_and_op("ITEM_EQUIPMENT")
        ctx = {"dag_run": MagicMock(conf={})}
        with pytest.raises(AirflowException):
            callable_fn(**ctx)
