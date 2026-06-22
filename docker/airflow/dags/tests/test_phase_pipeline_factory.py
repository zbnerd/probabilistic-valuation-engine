"""Unit tests for phase_pipeline_factory helpers."""
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException

from phase_pipeline_factory import (
    _make_count_sensor_runtime,
    _PHASE_TO_ENDPOINT,
    _wait_terminal_fn,
    get_external_api_base,
    make_branch_on_mode_for_phase,
    make_phase_dag,
    make_stop_loop_task,
    make_trigger_loop_task,
    make_trigger_once_task,
    make_wait_loop_stopped_sensor,
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


class TestMakeStopLoopTask:
    def _get_callable(self, phase):
        op = make_stop_loop_task(phase)
        return op.python_callable

    def test_202_stop_requested(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 202
            mock_resp.json.return_value = {
                "status": "STOP_REQUESTED",
                "phase": "ITEM_EQUIPMENT",
                "loopId": "loop-1",
                "iterationCount": 42,
            }
            mock_post.return_value = mock_resp
            result = self._get_callable("ITEM_EQUIPMENT")()
        assert result["status"] == "STOP_REQUESTED"

    def test_200_not_looping_idempotent(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {"status": "NOT_LOOPING", "phase": "ITEM_EQUIPMENT"}
            mock_post.return_value = mock_resp
            result = self._get_callable("ITEM_EQUIPMENT")()
        assert result["status"] == "NOT_LOOPING"

    def test_400_invalid_phase_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 400
            mock_resp.text = "INVALID_PHASE"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("RANKING_FETCH")()

    def test_5xx_raises(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 502
            mock_resp.text = "bad gateway"
            mock_resp.reason = "Bad Gateway"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("ITEM_EQUIPMENT")()


class TestMakeWaitLoopStoppedSensor:
    def _get_callable(self, phase):
        op = make_wait_loop_stopped_sensor(phase)
        return op.python_callable

    def test_returns_true_when_no_loop_active(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {"loopSummaries": {}}
            mock_get.return_value = mock_resp
            assert self._get_callable("ITEM_EQUIPMENT")() is True

    def test_returns_true_when_status_stopped(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {
                "loopSummaries": {
                    "ITEM_EQUIPMENT": {"loopId": "x", "status": "STOPPED"}
                }
            }
            mock_get.return_value = mock_resp
            assert self._get_callable("ITEM_EQUIPMENT")() is True

    def test_returns_false_when_still_running(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {
                "loopSummaries": {
                    "ITEM_EQUIPMENT": {"loopId": "x", "status": "RUNNING"}
                }
            }
            mock_get.return_value = mock_resp
            assert self._get_callable("ITEM_EQUIPMENT")() is False

    def test_returns_false_on_transient_http_error(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get:
            mock_get.side_effect = Exception("connection refused")
            assert self._get_callable("ITEM_EQUIPMENT")() is False

    def test_timeout_is_30_minutes(self):
        op = make_wait_loop_stopped_sensor("ITEM_EQUIPMENT")
        assert op.timeout == 30 * 60
        assert op.mode == "reschedule"
        assert op.poke_interval == 10


class TestMakeBranchOnModeForPhase:
    def _branch_fn(self, phase):
        op = make_branch_on_mode_for_phase(phase)
        return op.python_callable

    def test_mode_once_routes_to_trigger_character_basic(self):
        fn = self._branch_fn("CHARACTER_BASIC")
        ctx = {"dag_run": MagicMock(conf={"mode": "once"})}
        assert fn(**ctx) == "trigger_character_basic"

    def test_mode_count_routes_to_trigger_loop(self):
        fn = self._branch_fn("ITEM_EQUIPMENT")
        ctx = {"dag_run": MagicMock(conf={"mode": "count", "count": 5})}
        assert fn(**ctx) == "trigger_loop_item_equipment"

    def test_mode_infinite_routes_to_trigger_loop_infinite(self):
        """mode=infinite must route to a separate leaf task_id so count_sensor
        does not run when count is not specified."""
        fn = self._branch_fn("ITEM_EQUIPMENT")
        ctx = {"dag_run": MagicMock(conf={"mode": "infinite"})}
        assert fn(**ctx) == "trigger_loop_infinite_item_equipment"

    def test_invalid_conf_raises(self):
        fn = self._branch_fn("CHARACTER_BASIC")
        ctx = {"dag_run": MagicMock(conf={})}
        with pytest.raises(AirflowException):
            fn(**ctx)


class TestWaitTerminalFn:
    def test_returns_true_when_terminal(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get, \
             patch("phase_pipeline_factory.time") as mock_time:
            mock_time.monotonic.return_value = 0
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {
                "slots": {
                    "CHARACTER_BASIC": {
                        "runId": "20260622-120000-x",
                        "phase": "CHARACTER_BASIC",
                        "terminal": True,
                    }
                },
                "lastCompletedByPhase": {},
            }
            mock_get.return_value = mock_resp
            ti = MagicMock()
            ti.xcom_pull.return_value = {"runId": "20260622-120000-x"}
            ctx = {"ti": ti}
            result = _wait_terminal_fn("CHARACTER_BASIC")(**ctx)
        assert result is True

    def test_raises_when_failed(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get, \
             patch("phase_pipeline_factory.time") as mock_time:
            mock_time.monotonic.return_value = 0
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {
                "slots": {
                    "CHARACTER_BASIC": {
                        "runId": "20260622-120000-x",
                        "phase": "FAILED",
                        "terminal": True,
                        "errorMessage": "boom",
                    }
                },
                "lastCompletedByPhase": {},
            }
            mock_get.return_value = mock_resp
            ti = MagicMock()
            ti.xcom_pull.return_value = {"runId": "20260622-120000-x"}
            with pytest.raises(RuntimeError, match="failed"):
                _wait_terminal_fn("CHARACTER_BASIC")(ti=ti)


class TestMakePhaseDag:
    """Test the DAG object structure."""

    def test_dag_has_expected_task_ids_for_character_basic(self):
        dag = make_phase_dag("CHARACTER_BASIC", "character_basic_pipeline")
        ids = {t.task_id for t in dag.tasks}
        expected = {
            "check_external_api",
            "branch_on_mode",
            "trigger_character_basic",
            "wait_terminal_character_basic",
            "trigger_loop_character_basic",
            "count_sensor_character_basic",
            "stop_loop_character_basic",
            "trigger_loop_infinite_character_basic",
        }
        assert expected.issubset(ids)

    def test_infinite_branch_is_leaf(self):
        """trigger_loop_infinite_<phase> must have no downstream tasks."""
        dag = make_phase_dag(
            "ITEM_EQUIPMENT", "item_equipment_pipeline"
        )
        leaf = dag.get_task("trigger_loop_infinite_item_equipment")
        # Airflow returns downstream_task_ids as a set; assert it's empty.
        assert not leaf.downstream_task_ids, (
            f"infinite branch should be leaf, got downstream={leaf.downstream_task_ids}"
        )

    def test_count_branch_wires_count_sensor_then_stop_loop(self):
        dag = make_phase_dag(
            "ITEM_EQUIPMENT", "item_equipment_pipeline"
        )
        trigger_loop = dag.get_task("trigger_loop_item_equipment")
        count_sensor = dag.get_task("count_sensor_item_equipment")
        stop_loop = dag.get_task("stop_loop_item_equipment")
        assert count_sensor.task_id in trigger_loop.downstream_task_ids
        assert stop_loop.task_id in count_sensor.downstream_task_ids
