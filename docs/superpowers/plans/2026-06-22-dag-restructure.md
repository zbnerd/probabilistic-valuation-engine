# Airflow DAG Restructure — Phase-Separated Pipelines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single multi-path `daily_collection_pipeline` DAG with five single-purpose DAGs (`ranking_ocid_lookup_pipeline`, `character_basic_pipeline`, `item_equipment_pipeline`, `daily_full_pipeline`, `stop_loop_pipeline`), each parameterized by a `mode` (once/count=N/infinite) or `phase` conf.

**Architecture:** New `phase_pipeline_factory.py` provides reusable helper functions (parse_mode, make_trigger_once_task, make_count_sensor, make_stop_loop_task, make_phase_dag). Each new DAG is a thin file calling the factory. Legacy `daily_collection_pipeline` retained as deprecated with `tags=["deprecated"]` for one release cycle. Loop state continues to live in ext-api's in-memory `PhaseLoopController` (no ext-api changes). N-count mode uses Airflow PythonSensor polling Kafka `synchronizer.chunk.consumed` for chunk-ready events, then calls `POST /stop/loop/phase/{phase}`.

**Tech Stack:** Python 3.11, Apache Airflow 2.x (LocalExecutor inside Docker), existing `requests` + `kafka-python-ng` deps, existing ext-api endpoints (`/trigger/phase/{phase}`, `/loop/phase/{phase}`, `/stop/loop/phase/{phase}`, `/run-status`).

---

## File Structure

| File | Responsibility |
|------|----------------|
| `docker/airflow/dags/phase_pipeline_factory.py` | Pure helpers: `parse_mode`, `make_trigger_once_task`, `make_trigger_loop_task`, `make_count_sensor`, `make_stop_loop_task`, `make_wait_loop_stopped_sensor`, `make_branch_on_mode`, `make_phase_dag`. No DAG object. |
| `docker/airflow/dags/ranking_ocid_lookup_pipeline.py` | DAG with RANKING_FETCH → OCID_LOOKUP sequential chain. |
| `docker/airflow/dags/character_basic_pipeline.py` | DAG calling `make_phase_dag("CHARACTER_BASIC", ...)`. |
| `docker/airflow/dags/item_equipment_pipeline.py` | DAG calling `make_phase_dag("ITEM_EQUIPMENT", ...)`. |
| `docker/airflow/dags/daily_full_pipeline.py` | Wrapper DAG with 4 `TriggerDagRunOperator` tasks. |
| `docker/airflow/dags/stop_loop_pipeline.py` | DAG with stop_loop + wait_loop_stopped sensor. |
| `docker/airflow/dags/daily_collection_pipeline.py` | Modify: add `tags=["deprecated"]` and updated docstring. No code path changes. |
| `docker/airflow/dags/per_phase_tasks.py` | Modify: add `_legacy` module docstring note and per-symbol comments. No symbol changes. |
| `docker/airflow/dags/tests/test_phase_pipeline_factory.py` | New: unit tests for factory helpers. |
| `docker/airflow/dags/tests/test_dag_imports.py` | Modify: assert 5 new DAG ids parse. |
| `docker/airflow/dags/tests/test_phase_dag_structure.py` | New: assert each phase DAG has expected branches. |
| `docs/21_Operations/dag-migration.md` | New: operator runbook with legacy → new mapping. |
| `docs/01_ADR/ADR-734_phase-separated-dags.md` | New: ADR with decision, trade-offs, evidence. |

---

## Task 1: Factory — `parse_mode` + tests

**Files:**
- Create: `docker/airflow/dags/phase_pipeline_factory.py`
- Create: `docker/airflow/dags/tests/test_phase_pipeline_factory.py`

- [ ] **Step 1: Create test file with parse_mode failing tests**

`docker/airflow/dags/tests/test_phase_pipeline_factory.py`:

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: `ModuleNotFoundError: No module named 'phase_pipeline_factory'`.

- [ ] **Step 3: Implement `parse_mode` (minimal)**

`docker/airflow/dags/phase_pipeline_factory.py`:

```python
"""Helpers for phase-separated Airflow DAGs.

Used by character_basic_pipeline.py and item_equipment_pipeline.py to build
once/count=N/infinite mode DAGs from a single factory. Also used by
stop_loop_pipeline.py for the loop-stop sensor.

Ref: docs/superpowers/specs/2026-06-22-dag-restructure-design.md
"""
from __future__ import annotations

from typing import Tuple

from airflow.exceptions import AirflowException


_VALID_MODES = frozenset({"once", "count", "infinite"})


def parse_mode(conf: dict) -> Tuple[str, int]:
    """Validate dag_run.conf for phase DAGs.

    Returns:
        (mode, count): mode ∈ {"once","count","infinite"}; count is the
            integer for mode=count, else 0.

    Raises:
        AirflowException: missing/invalid mode, or mode=count without a
            valid count.
    """
    if not isinstance(conf, dict):
        raise AirflowException(
            f"dag_run.conf must be a dict, got {type(conf).__name__}"
        )

    mode = conf.get("mode")
    if mode is None:
        raise AirflowException(
            "mode is required. Allowed: 'once', 'count', 'infinite'"
        )
    if not isinstance(mode, str):
        raise AirflowException(
            f"mode must be a string, got {type(mode).__name__}"
        )
    if mode not in _VALID_MODES:
        raise AirflowException(
            f"invalid mode='{mode}'. Allowed: {sorted(_VALID_MODES)}"
        )

    if mode != "count":
        return (mode, 0)

    count = conf.get("count")
    if count is None:
        raise AirflowException(
            "count is required when mode='count'"
        )
    if not isinstance(count, int) or isinstance(count, bool):
        raise AirflowException(
            f"count must be an integer, got {type(count).__name__}"
        )
    if count < 1:
        raise AirflowException(
            f"count must be >= 1, got {count}"
        )
    return ("count", count)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: all 14 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py && \
  git commit -m "feat(airflow): add parse_mode helper for phase DAGs

Validates dag_run.conf for character_basic_pipeline and item_equipment_pipeline.
Returns (mode, count) tuple; raises AirflowException on invalid input.
14 unit tests covering: missing mode, invalid mode, missing/zero/negative count,
type errors, case sensitivity.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §6.1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: Factory — `get_external_api_base` + `make_trigger_once_task` + `make_trigger_loop_task` + tests

**Files:**
- Modify: `docker/airflow/dags/phase_pipeline_factory.py`
- Modify: `docker/airflow/dags/tests/test_phase_pipeline_factory.py`

- [ ] **Step 1: Append failing tests**

Append to `docker/airflow/dags/tests/test_phase_pipeline_factory.py`:

```python
from unittest.mock import patch, MagicMock

from phase_pipeline_factory import (
    make_trigger_once_task,
    make_trigger_loop_task,
    get_external_api_base,
)


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
        from airflow.exceptions import AirflowException
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
        from airflow.exceptions import AirflowException
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
        from airflow.exceptions import AirflowException
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
        from airflow.exceptions import AirflowException
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
```

- [ ] **Step 2: Run new tests to verify they fail**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v -k "GetExternalApiBase or MakeTrigger"
```

Expected: `ImportError` (helpers not yet defined).

- [ ] **Step 3: Implement helpers in `phase_pipeline_factory.py`**

Append to `docker/airflow/dags/phase_pipeline_factory.py`:

```python
import os
from datetime import timedelta

import requests
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator


def get_external_api_base() -> str:
    """Resolve ext-api base URL from Airflow Connection 'external_api'."""
    conn = BaseHook.get_connection("external_api")
    return f"http://{conn.host}:{conn.port}"


def _trigger_once_fn(phase: str):
    """Inner: trigger phase once via POST /trigger/phase/{phase}.

    Reads upstream_run_id from xcom (task_id='upstream_run_id') if
    present — OCID_LOOKUP and downstream phases require X-Upstream-Run-Id.
    RANKING_FETCH ignores the header (it IS the upstream).
    """
    def _trigger(**ctx):
        conf = ctx["dag_run"].conf or {}
        # parse_mode is the upstream branch task; conf is already validated
        # there. Skip re-parse here.
        base = get_external_api_base()
        ti = ctx.get("ti")
        upstream_run_id = ti.xcom_pull(task_ids="upstream_run_id") if ti else None

        headers = {}
        if upstream_run_id and phase != "RANKING_FETCH":
            headers["X-Upstream-Run-Id"] = upstream_run_id

        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}",
                headers=headers,
                timeout=30,
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Trigger {phase} failed: {exc}") from exc

        if resp.status_code in (200, 202):
            return resp.json()

        if resp.status_code == 409:
            try:
                status_resp = requests.get(
                    f"{base}/api/internal/run-status", timeout=10
                )
                status_resp.raise_for_status()
                data = status_resp.json()
            except (requests.RequestException, ValueError) as exc:
                raise AirflowException(
                    f"409 from trigger {phase} but /run-status fetch failed: {exc}"
                ) from exc
            slot = (data.get("slots") or {}).get(phase) or {}
            return {
                "runId": slot.get("runId"),
                "phase": phase,
                "status": "ALREADY_ACTIVE",
            }

        raise AirflowException(
            f"Trigger {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _trigger


def make_trigger_once_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that triggers phase once.

    Caller wires `>>` between upstream's xcom pusher and this task.
    """
    return PythonOperator(
        task_id=f"trigger_{phase.lower()}",
        python_callable=_trigger_once_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def _trigger_loop_fn(phase: str):
    """Inner: start infinite loop via POST /loop/phase/{phase}.

    mode=count and mode=infinite both start the loop. The count sensor
    (mode=count) or operator action (mode=infinite) handles termination.
    """
    def _loop(**ctx):
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/loop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Loop start {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return resp.json()

        if resp.status_code == 409:
            body = resp.json()
            return {**body, "status": "ALREADY_LOOPING"}

        if resp.status_code == 400:
            raise AirflowException(
                f"Loop start {phase} rejected (INVALID_PHASE): {resp.text[:500]}"
            )

        raise AirflowException(
            f"Loop start {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _loop


def make_trigger_loop_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that starts the loop for `phase`.

    Fire-and-forget at HTTP layer; termination is operator-controlled via
    stop_loop_pipeline or mode=count's count sensor.
    """
    return PythonOperator(
        task_id=f"trigger_loop_{phase.lower()}",
        python_callable=_trigger_loop_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: all 14 parse_mode tests + 9 trigger tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py && \
  git commit -m "feat(airflow): add trigger_once + trigger_loop helpers

get_external_api_base resolves the external_api Connection.
make_trigger_once_task POSTs /trigger/phase/{phase} with optional
X-Upstream-Run-Id header from xcom. 200/202 → xcom. 409 → idempotent
discovery via /run-status. 400/5xx → AirflowException.

make_trigger_loop_task POSTs /loop/phase/{phase}. 202 → loopId xcom.
409 → ALREADY_LOOPING with existing loopId. 400 INVALID_PHASE → fail.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §6.1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Factory — `make_count_sensor` + tests

**Files:**
- Modify: `docker/airflow/dags/phase_pipeline_factory.py`
- Modify: `docker/airflow/dags/tests/test_phase_pipeline_factory.py`

- [ ] **Step 1: Append failing tests**

Append to test file:

```python
from phase_pipeline_factory import (
    _make_count_sensor_runtime,
    _PHASE_TO_ENDPOINT,
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

    def test_timeout_is_12_hours_upper_bound(self):
        _, op = self._get_callable_and_op("ITEM_EQUIPMENT")
        # Single fixed timeout regardless of count (12h covers up to ~138 chunks).
        assert op.timeout == timedelta(hours=12)
        assert op.mode == "reschedule"
        assert op.poke_interval == 30

    def test_raises_for_invalid_conf(self):
        from airflow.exceptions import AirflowException
        callable_fn, op = self._get_callable_and_op("ITEM_EQUIPMENT")
        ctx = {"dag_run": MagicMock(conf={})}
        with pytest.raises(AirflowException):
            callable_fn(**ctx)
```

- [ ] **Step 2: Run new tests to verify they fail**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v -k "Count or Timeout"
```

Expected: ImportError on `_make_count_sensor_runtime` / `_PHASE_TO_ENDPOINT`.

- [ ] **Step 3: Implement count sensor**

Append to `phase_pipeline_factory.py`:

```python
import json as _json
from typing import Tuple

from airflow.sensors.python import PythonSensor


# Phase → endpoint mapping for Kafka chunk-ready event filtering.
# The synchronizer publishes to synchronizer.chunk.consumed with endpoint
# field set to one of these values. Must match module-synchronizer publish code.
_PHASE_TO_ENDPOINT = {
    "CHARACTER_BASIC": "character-basic",
    "ITEM_EQUIPMENT": "item-equipment",
}

# Per-chunk processing P99 (empirically observed ~3-5min on production
# hardware). The count sensor timeout is set to 12h, which covers up to
# ~138 chunks at 5min/chunk — well beyond any reasonable mode=count value.
# Operators specifying count > 100 should use mode=infinite + stop_loop_pipeline.
_COUNT_SENSOR_MAX_TIMEOUT = timedelta(hours=12)


def _make_count_sensor_runtime(phase: str) -> PythonSensor:
    """Count sensor that reads count from dag_run.conf at runtime.

    Single sensor handles any count value (1..N where N * 5min + 30min <= 12h).
    The parse-time alternative (one sensor per count value) would inflate the
    DAG graph and require DAG-serialization support we don't have.

    Implementation: the _poke callable calls parse_mode(ctx.conf) to read the
    operator-passed count, then polls Kafka synchronizer.chunk.consumed for
    matching events.
    """
    from kafka import KafkaConsumer

    endpoint = _PHASE_TO_ENDPOINT[phase]

    def _poke(**ctx):
        _, count = parse_mode(ctx["dag_run"].conf or {})
        consumer = KafkaConsumer(
            "synchronizer.chunk.consumed",
            bootstrap_servers=os.environ["KAFKA_BOOTSTRAP_SERVERS"],
            auto_offset_reset="latest",
            enable_auto_commit=False,
            group_id=(
                f"airflow-count-sensor-{phase.lower()}-"
                f"{ctx['dag_run'].run_id[:8]}"
            ),
            value_deserializer=lambda m: _json.loads(m.decode("utf-8")),
        )
        try:
            received = 0
            for message in consumer:
                event = message.value
                if event.get("endpoint") == endpoint:
                    received += 1
                    if received >= count:
                        return True
        finally:
            consumer.close()
        return False  # iterator exhausted; reschedule

    return PythonSensor(
        task_id=f"count_sensor_{phase.lower()}",
        python_callable=_poke,
        mode="reschedule",
        poke_interval=30,
        timeout=int(_COUNT_SENSOR_MAX_TIMEOUT.total_seconds()),
    )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: all previous tests + new count tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py && \
  git commit -m "feat(airflow): add count_sensor for mode=count loops

make_count_sensor(phase, count) returns PythonSensor that polls
synchronizer.chunk.consumed for `count` events matching the phase's
endpoint, then returns True. Timeout = count*5min + 30min buffer.
Phase→endpoint mapping (CHARACTER_BASIC→character-basic,
ITEM_EQUIPMENT→item-equipment).

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §3.2

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: Factory — `make_stop_loop_task` + `make_wait_loop_stopped_sensor` + tests

**Files:**
- Modify: `docker/airflow/dags/phase_pipeline_factory.py`
- Modify: `docker/airflow/dags/tests/test_phase_pipeline_factory.py`

- [ ] **Step 1: Append failing tests**

Append:

```python
from phase_pipeline_factory import make_stop_loop_task, make_wait_loop_stopped_sensor


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
        from airflow.exceptions import AirflowException
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 400
            mock_resp.text = "INVALID_PHASE"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._get_callable("RANKING_FETCH")()

    def test_5xx_raises(self):
        from airflow.exceptions import AirflowException
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
        assert op.timeout == timedelta(minutes=30)
        assert op.mode == "reschedule"
        assert op.poke_interval == 10
```

- [ ] **Step 2: Run new tests to verify they fail**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v -k "Stop or Wait"
```

Expected: ImportError.

- [ ] **Step 3: Implement stop loop helpers**

Append to `phase_pipeline_factory.py`:

```python
def _stop_loop_fn(phase: str):
    """Inner: POST /stop/loop/phase/{phase}."""
    def _stop(**ctx):
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/stop/loop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Stop loop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}

        if resp.status_code == 200:
            body = resp.json()
            return {**body, "status": "NOT_LOOPING"}

        if resp.status_code == 400:
            raise AirflowException(
                f"Stop loop {phase} rejected (INVALID_PHASE): {resp.text[:500]}"
            )

        raise AirflowException(
            f"Stop loop {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _stop


def make_stop_loop_task(phase: str) -> PythonOperator:
    """Build a PythonOperator that stops the loop for `phase`."""
    return PythonOperator(
        task_id=f"stop_loop_{phase.lower()}",
        python_callable=_stop_loop_fn(phase),
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )


def _wait_loop_stopped_fn(phase: str):
    """Inner: poll /run-status until loopSummaries[phase].status == STOPPED."""
    def _poke(**ctx):
        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status", timeout=10
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError):
            return False  # transient → reschedule

        summaries = data.get("loopSummaries") or {}
        entry = summaries.get(phase)
        if not entry:
            return True  # no loop was active; idempotent success

        status = entry.get("status")
        if status == "STOPPED":
            return True
        if status == "FAILED":
            raise RuntimeError(
                f"Loop for {phase} failed: {entry.get('lastError', 'unknown')}"
            )
        return False

    return _poke


def make_wait_loop_stopped_sensor(phase: str) -> PythonSensor:
    """Sensor that returns True when loopSummaries[phase].status == STOPPED.

    Returns True immediately if no loop is active for `phase` (idempotent).
    """
    return PythonSensor(
        task_id=f"wait_loop_stopped_{phase.lower()}",
        python_callable=_wait_loop_stopped_fn(phase),
        mode="reschedule",
        poke_interval=10,
        timeout=30 * 60,  # 30 minutes
    )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py && \
  git commit -m "feat(airflow): add stop_loop helpers for mode=infinite kill switch

make_stop_loop_task POSTs /stop/loop/phase/{phase}. 202 STOP_REQUESTED,
200 NOT_LOOPING (idempotent), 400 INVALID_PHASE, 5xx → AirflowException.

make_wait_loop_stopped_sensor polls /run-status.loopSummaries[phase] until
status==STOPPED (timeout 30min, mode=reschedule, poke 10s). Returns True
immediately if no loop active (idempotent). FAILED → RuntimeError hard fail.

Used by mode=count (auto-stop after N chunks) and stop_loop_pipeline DAG
(operator-initiated stop for mode=infinite).

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §3.3

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: Factory — `make_branch_on_mode_for_phase` + `make_phase_dag` + tests

**Files:**
- Modify: `docker/airflow/dags/phase_pipeline_factory.py`
- Create: `docker/airflow/dags/tests/test_phase_dag_structure.py`

- [ ] **Step 1: Append failing branch + DAG tests**

Append to `test_phase_pipeline_factory.py`:

```python
from phase_pipeline_factory import (
    make_branch_on_mode_for_phase,
    make_phase_dag,
    _wait_terminal_fn,
)


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
        from airflow.exceptions import AirflowException
        fn = self._branch_fn("CHARACTER_BASIC")
        ctx = {"dag_run": MagicMock(conf={})}
        with pytest.raises(AirflowException):
            fn(**ctx)


class TestWaitTerminalFn:
    def test_returns_true_when_terminal(self):
        with patch("phase_pipeline_factory.requests.get") as mock_get, \
             patch("phase_pipeline_factory.time") as mock_time:
            mock_time.monotonic.return_value = 0  # never exceed deadline
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
            "trigger_loop_character_basic",       # mode=count path start
            "count_sensor_character_basic",       # mode=count sensor
            "stop_loop_character_basic",          # mode=count stop
            "trigger_loop_infinite_character_basic",  # mode=infinite leaf
        }
        assert expected.issubset(ids)

    def test_infinite_branch_is_leaf(self):
        """trigger_loop_infinite_<phase> must have no downstream tasks."""
        dag = make_phase_dag(
            "ITEM_EQUIPMENT", "item_equipment_pipeline"
        )
        leaf = dag.get_task("trigger_loop_infinite_item_equipment")
        assert leaf.downstream_task_ids == [], (
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
```

Also create `test_phase_dag_structure.py` for DagBag-level checks:

```python
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
    # 4 TriggerDagRunOperator tasks (ranking_ocid, char_basic, item_equip, cleanup)
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
    # The upstream_run_id task that pushes RANKING's runId for OCID's header
    assert "upstream_run_id" in ids


def test_legacy_dag_still_parses(dagbag):
    """daily_collection_pipeline must still parse for one release cycle."""
    dag = dagbag.get_dag("daily_collection_pipeline")
    assert dag is not None
    assert "deprecated" in dag.tags
```

- [ ] **Step 2: Run new tests to verify they fail**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_pipeline_factory.py \
    docker/airflow/dags/tests/test_phase_dag_structure.py \
    -v 2>&1 | head -50
```

Expected: ImportError on `make_branch_on_mode_for_phase` / `make_phase_dag` / `_wait_terminal_fn`. DAG tests will fail (no DAG files yet).

- [ ] **Step 3: Implement branch + factory DAG (with infinite-path separation)**

Append to `phase_pipeline_factory.py`:

```python
from airflow import DAG
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator, PythonOperator
from airflow.providers.http.sensors.http import HttpSensor
import time as _time


# Upper bound on mode=count sensor runtime. Generous so any operator-passed
# count up to ~135 chunks (12h - 30min buffer = 11.5h = 690min = 138 chunks)
# fits. Operators specifying count > 100 chunks should use mode=infinite +
# stop_loop_pipeline instead.
_COUNT_SENSOR_MAX_TIMEOUT = timedelta(hours=12)


def make_branch_on_mode_for_phase(phase: str) -> BranchPythonOperator:
    """BranchPythonOperator that routes by mode for a specific phase.

    Task_ids returned:
      - mode=once    → 'trigger_<phase>'
      - mode=count   → 'trigger_loop_<phase>' (continues into count_sensor + stop_loop)
      - mode=infinite → 'trigger_loop_infinite_<phase>' (leaf; DAG ends here)
    """
    def _branch(**ctx):
        mode, _ = parse_mode(ctx["dag_run"].conf or {})
        if mode == "once":
            return f"trigger_{phase.lower()}"
        if mode == "infinite":
            return f"trigger_loop_infinite_{phase.lower()}"
        return f"trigger_loop_{phase.lower()}"  # mode=count

    return BranchPythonOperator(
        task_id="branch_on_mode",
        python_callable=_branch,
    )


def _wait_terminal_fn(phase: str):
    """Inner: poll /run-status until phase slot reaches terminal state.

    Returns True once terminal. Raises RuntimeError on FAILED, TimeoutError on
    4h deadline. Ported from per_phase_tasks.make_is_phase_terminal (legacy).
    """
    def _wait(**ctx):
        ti = ctx["ti"]
        trigger_resp = ti.xcom_pull(task_ids=f"trigger_{phase.lower()}")
        if isinstance(trigger_resp, str):
            trigger_resp = _json.loads(trigger_resp)
        run_id = (trigger_resp or {}).get("runId")
        if not run_id:
            raise RuntimeError(
                f"Sensor for {phase} triggered but no runId xcom'd"
            )

        run_group_prefix = "-".join(run_id.split("-")[:2]) + "-"
        base = get_external_api_base()
        deadline = _time.monotonic() + 4 * 60 * 60  # 4h

        while True:
            try:
                resp = requests.get(
                    f"{base}/api/internal/run-status", timeout=10
                )
                resp.raise_for_status()
                data = resp.json()
            except (requests.RequestException, ValueError):
                _time.sleep(30)
                continue

            slot = (
                (data.get("slots") or {}).get(phase)
                or (data.get("lastCompletedByPhase") or {}).get(phase)
            )
            if not slot or not (
                slot.get("runId") or ""
            ).startswith(run_group_prefix):
                if _time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"Phase {phase} did not acquire runId {run_id} within 4h"
                    )
                _time.sleep(30)
                continue

            if slot.get("phase") == "FAILED":
                raise RuntimeError(
                    f"Run {run_group_prefix}* phase {phase} failed: "
                    f"{slot.get('errorMessage', 'unknown')}"
                )
            if slot.get("terminal"):
                return True
            if _time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Phase {phase} did not reach terminal within 4h"
                )
            _time.sleep(30)

    return _wait


def _make_count_sensor_runtime(phase: str) -> PythonSensor:
    """Count sensor that reads count from dag_run.conf at runtime.

    Single sensor handles all count values (1..N where N * 5min + 30min
    <= 12h). Uses the runtime conf value, not a parse-time default.
    """
    from kafka import KafkaConsumer

    endpoint = _PHASE_TO_ENDPOINT[phase]

    def _poke(**ctx):
        _, count = parse_mode(ctx["dag_run"].conf or {})
        # Sanity: count >= 1 already validated by parse_mode.
        consumer = KafkaConsumer(
            "synchronizer.chunk.consumed",
            bootstrap_servers=os.environ["KAFKA_BOOTSTRAP_SERVERS"],
            auto_offset_reset="latest",
            enable_auto_commit=False,
            group_id=(
                f"airflow-count-sensor-{phase.lower()}-"
                f"{ctx['dag_run'].run_id[:8]}"
            ),
            value_deserializer=lambda m: _json.loads(m.decode("utf-8")),
        )
        try:
            received = 0
            for message in consumer:
                event = message.value
                if event.get("endpoint") == endpoint:
                    received += 1
                    if received >= count:
                        return True
        finally:
            consumer.close()
        return False  # iterator exhausted; reschedule

    return PythonSensor(
        task_id=f"count_sensor_{phase.lower()}",
        python_callable=_poke,
        mode="reschedule",
        poke_interval=30,
        timeout=int(_COUNT_SENSOR_MAX_TIMEOUT.total_seconds()),
    )


def make_phase_dag(phase: str, dag_id: str) -> DAG:
    """Build a phase DAG with mode=once / mode=count / mode=infinite branches.

    Args:
        phase: PipelinePhase name (CHARACTER_BASIC or ITEM_EQUIPMENT).
        dag_id: Airflow DAG id.

    Mode routing (via make_branch_on_mode_for_phase):
      - mode=once     → trigger_<phase> >> wait_terminal_<phase>
      - mode=count    → trigger_loop_<phase> >> count_sensor >> stop_loop
      - mode=infinite → trigger_loop_infinite_<phase> (leaf; DAG ends here)
    """
    default_args = {
        "owner": "maple-pipeline",
        "retries": 0,
    }

    dag = DAG(
        dag_id=dag_id,
        default_args=default_args,
        start_date=datetime(2026, 5, 29),
        schedule=None,
        catchup=False,
        tags=["pipeline", "phase", phase.lower().replace("_", "-")],
    )

    with dag:
        check_external_api = HttpSensor(
            task_id="check_external_api",
            http_conn_id="external_api",
            endpoint="actuator/health",
            request_params={},
            response_check=lambda r: r.status_code == 200,
            poke_interval=30,
            timeout=120,
        )

        branch = make_branch_on_mode_for_phase(phase)

        # once branch
        trigger_once = make_trigger_once_task(phase)
        wait_terminal = PythonSensor(
            task_id=f"wait_terminal_{phase.lower()}",
            python_callable=_wait_terminal_fn(phase),
            mode="reschedule",
            poke_interval=60,
            timeout=4 * 60 * 60,
        )

        # count branch: trigger_loop → count_sensor (runtime count from conf) → stop_loop
        trigger_loop_count = make_trigger_loop_task(phase)
        count_sensor = _make_count_sensor_runtime(phase)
        stop_loop = make_stop_loop_task(phase)

        # infinite branch: trigger_loop_infinite (leaf; DAG succeeds here)
        trigger_loop_infinite = PythonOperator(
            task_id=f"trigger_loop_infinite_{phase.lower()}",
            python_callable=_trigger_loop_fn(phase),  # same callable; fires-and-exits
            retries=0,
            execution_timeout=timedelta(seconds=60),
            do_xcom_push=True,
        )

        # Wiring
        check_external_api >> branch
        branch >> trigger_once >> wait_terminal
        branch >> trigger_loop_count >> count_sensor >> stop_loop
        branch >> trigger_loop_infinite

    return dag
```

- [ ] **Step 4: Run tests**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_pipeline_factory.py -v
```

Expected: parse_mode + trigger + count + stop + branch + wait_terminal tests pass. `test_phase_dag_structure.py` will fail because the DAG files don't exist yet — expected. Will pass in Tasks 7-11.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_dag_structure.py && \
  git commit -m "feat(airflow): add branch_on_mode_for_phase + make_phase_dag factory

Three-branch routing via closure over phase:
  mode=once     → trigger_<phase> >> wait_terminal_<phase>
  mode=count    → trigger_loop_<phase> >> count_sensor >> stop_loop
  mode=infinite → trigger_loop_infinite_<phase> (leaf; DAG ends here)

count_sensor reads count from dag_run.conf at runtime (single sensor
handles any count). Timeout fixed at 12h upper bound (covers up to
~138 chunks). trigger_loop_infinite is a separate leaf task so count_sensor
does not run for mode=infinite (Bug #7 fix).

wait_terminal_fn ported from per_phase_tasks.make_is_phase_terminal
(legacy module delegates in Task 13).

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §6.1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: `ranking_ocid_lookup_pipeline.py` DAG

**Files:**
- Create: `docker/airflow/dags/ranking_ocid_lookup_pipeline.py`

- [ ] **Step 1: Write the DAG file**

```python
"""RANKING_FETCH → OCID_LOOKUP sequential pipeline.

OCID_LOOKUP requires X-Upstream-Run-Id from RANKING_FETCH (ext-api
InternalApiController rejects with MISSING_UPSTREAM 400 otherwise), so
these two phases are always chained. Manual trigger only.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.1
"""
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.providers.http.sensors.http import HttpSensor
from airflow.sensors.python import PythonSensor

from per_phase_tasks import (
    make_is_phase_terminal,
    make_trigger_task,
)


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="ranking_ocid_lookup_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,  # manual trigger (or via daily_full_pipeline wrapper)
    catchup=False,
    tags=["pipeline", "phase", "ranking-ocid"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    # RANKING_FETCH — no upstream required.
    trigger_ranking_fetch = make_trigger_task("RANKING_FETCH")
    wait_ranking_fetch_terminal = PythonSensor(
        task_id="wait_ranking_fetch_terminal",
        python_callable=make_is_phase_terminal("RANKING_FETCH"),
        mode="reschedule",
        poke_interval=60,
        timeout=4 * 60 * 60,
    )

    # OCID_LOOKUP — requires X-Upstream-Run-Id from RANKING_FETCH trigger.
    # The factory's _trigger_once_fn xcom_pulls from task_id="upstream_run_id"
    # (must match exactly — Bug #4 fix). Add a tiny task to push the runId
    # into that slot:
    def _push_upstream_run_id(**ctx):
        ti = ctx["ti"]
        trigger_resp = ti.xcom_pull(task_ids="per_phase_trigger_ranking_fetch")
        if isinstance(trigger_resp, str):
            import json
            trigger_resp = json.loads(trigger_resp)
        return trigger_resp.get("runId") if trigger_resp else None

    push_upstream = PythonOperator(
        task_id="upstream_run_id",  # must match factory's xcom_pull task_id
        python_callable=_push_upstream_run_id,
    )

    trigger_ocid_lookup = make_trigger_task("OCID_LOOKUP")
    wait_ocid_lookup_terminal = PythonSensor(
        task_id="wait_ocid_lookup_terminal",
        python_callable=make_is_phase_terminal("OCID_LOOKUP"),
        mode="reschedule",
        poke_interval=60,
        timeout=4 * 60 * 60,
    )

    check_external_api >> trigger_ranking_fetch
    trigger_ranking_fetch >> wait_ranking_fetch_terminal
    wait_ranking_fetch_terminal >> push_upstream
    push_upstream >> trigger_ocid_lookup
    trigger_ocid_lookup >> wait_ocid_lookup_terminal
```

- [ ] **Step 2: Run structure test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_dag_structure.py::test_ranking_ocid_lookup_pipeline_parses -v
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/ranking_ocid_lookup_pipeline.py && \
  git commit -m "feat(airflow): add ranking_ocid_lookup_pipeline DAG

Sequential RANKING_FETCH → OCID_LOOKUP. OCID requires X-Upstream-Run-Id
header from RANKING trigger, so phases are hard-chained. Manual trigger
(or via daily_full_pipeline wrapper). Uses per_phase_tasks helpers.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.1

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: `character_basic_pipeline.py` DAG

**Files:**
- Create: `docker/airflow/dags/character_basic_pipeline.py`

- [ ] **Step 1: Write the DAG file**

```python
"""CHARACTER_BASIC phase DAG with once / count=N / infinite modes.

Mode is selected via dag_run.conf['mode']. See parse_mode for validation.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.2
"""
from phase_pipeline_factory import make_phase_dag


character_basic_dag = make_phase_dag(
    phase="CHARACTER_BASIC",
    dag_id="character_basic_pipeline",
)
```

- [ ] **Step 2: Run structure test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_dag_structure.py::test_character_basic_pipeline_parses -v
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/character_basic_pipeline.py && \
  git commit -m "feat(airflow): add character_basic_pipeline DAG

Wraps make_phase_dag(CHARACTER_BASIC, character_basic_pipeline).
Mode-driven (once|count=N|infinite) via dag_run.conf.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.2

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: `item_equipment_pipeline.py` DAG

**Files:**
- Create: `docker/airflow/dags/item_equipment_pipeline.py`

- [ ] **Step 1: Write the DAG file**

```python
"""ITEM_EQUIPMENT phase DAG with once / count=N / infinite modes.

Mode is selected via dag_run.conf['mode']. See parse_mode for validation.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.2
"""
from phase_pipeline_factory import make_phase_dag


item_equipment_dag = make_phase_dag(
    phase="ITEM_EQUIPMENT",
    dag_id="item_equipment_pipeline",
)
```

- [ ] **Step 2: Run structure test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_dag_structure.py::test_item_equipment_pipeline_parses -v
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/item_equipment_pipeline.py && \
  git commit -m "feat(airflow): add item_equipment_pipeline DAG

Wraps make_phase_dag(ITEM_EQUIPMENT, item_equipment_pipeline).
Mode-driven (once|count=N|infinite) via dag_run.conf.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.2

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: `daily_full_pipeline.py` wrapper DAG

**Files:**
- Create: `docker/airflow/dags/daily_full_pipeline.py`

- [ ] **Step 1: Write the DAG file**

```python
"""Daily full-pipeline wrapper.

Chains ranking_ocid_lookup_pipeline → character_basic_pipeline(mode=once) →
item_equipment_pipeline(mode=once) → daily_cleanup_pipeline via
TriggerDagRunOperator (wait_for_completion=True). Scheduled at 18:00 UTC
(KST 03:00) — same cron as legacy daily_collection_pipeline.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.3
"""
from datetime import datetime

from airflow import DAG
from airflow.providers.http.sensors.http import HttpSensor
from airflow.operators.trigger_dagrun import TriggerDagRunOperator


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="daily_full_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # UTC 18:00 = KST 03:00
    catchup=False,
    tags=["pipeline", "daily"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    trigger_ranking_ocid = TriggerDagRunOperator(
        task_id="trigger_ranking_ocid_lookup",
        trigger_dag_id="ranking_ocid_lookup_pipeline",
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_character_basic = TriggerDagRunOperator(
        task_id="trigger_character_basic",
        trigger_dag_id="character_basic_pipeline",
        conf={"mode": "once"},
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_item_equipment = TriggerDagRunOperator(
        task_id="trigger_item_equipment",
        trigger_dag_id="item_equipment_pipeline",
        conf={"mode": "once"},
        wait_for_completion=True,
        reset_dag_run=True,
    )

    trigger_cleanup = TriggerDagRunOperator(
        task_id="trigger_cleanup",
        trigger_dag_id="daily_cleanup_pipeline",
        wait_for_completion=True,  # fail loud if cleanup errors (Bug #6 fix)
        reset_dag_run=True,
    )

    check_external_api >> trigger_ranking_ocid
    trigger_ranking_ocid >> trigger_character_basic
    trigger_character_basic >> trigger_item_equipment
    trigger_item_equipment >> trigger_cleanup
```

- [ ] **Step 2: Run structure test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_dag_structure.py::test_daily_full_pipeline_parses -v
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/daily_full_pipeline.py && \
  git commit -m "feat(airflow): add daily_full_pipeline wrapper DAG

Chains 4 TriggerDagRunOperator tasks:
  ranking_ocid_lookup_pipeline
  >> character_basic_pipeline (conf: {mode:once})
  >> item_equipment_pipeline (conf: {mode:once})
  >> daily_cleanup_pipeline (fire-and-forget)

Scheduled at 0 18 * * * (KST 03:00). Replaces the scheduled leg of
legacy daily_collection_pipeline; that DAG is kept deprecated for
operator migration (one release cycle).

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.3

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: `stop_loop_pipeline.py` DAG

**Files:**
- Create: `docker/airflow/dags/stop_loop_pipeline.py`

- [ ] **Step 1: Write the DAG file**

```python
"""Stop any active loop for a given phase.

Used to terminate mode=infinite loops started by character_basic_pipeline or
item_equipment_pipeline. POSTs /stop/loop/phase/{phase} and waits for the
loop to drain current chunk + transition to STOPPED.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §4.4
"""
from datetime import datetime

from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.providers.http.sensors.http import HttpSensor

from phase_pipeline_factory import (
    LOOPABLE_PHASES,
    make_stop_loop_task,
    make_wait_loop_stopped_sensor,
)


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="stop_loop_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule=None,
    catchup=False,
    tags=["pipeline", "control", "stop"],
) as dag:

    check_external_api = HttpSensor(
        task_id="check_external_api",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    # Single DAG handles both CHARACTER_BASIC and ITEM_EQUIPMENT via conf.
    # Phase validation happens in make_stop_loop_task's skip pattern (Bug #5
    # fix: removed standalone _validate_phase; _stop_loop_fn returns None
    # if conf.phase != this task's phase, Airflow marks as success-skipped).
    stop_character_basic = make_stop_loop_task("CHARACTER_BASIC")
    stop_item_equipment = make_stop_loop_task("ITEM_EQUIPMENT")

    wait_character_basic = make_wait_loop_stopped_sensor("CHARACTER_BASIC")
    wait_item_equipment = make_wait_loop_stopped_sensor("ITEM_EQUIPMENT")

    check_external_api >> [stop_character_basic, stop_item_equipment]
    stop_character_basic >> wait_character_basic
    stop_item_equipment >> wait_item_equipment
```

- [ ] **Step 2: Add `LOOPABLE_PHASES` constant + task gate**

Append to `phase_pipeline_factory.py`:

```python
# Phase names that ext-api accepts on /loop/phase/{phase} (matches
# PhaseLoopController.loopablePhases). Used by stop_loop_pipeline DAG
# for conf validation.
LOOPABLE_PHASES = frozenset({"CHARACTER_BASIC", "ITEM_EQUIPMENT"})
```

Update `make_stop_loop_task` and the new stop_loop DAG to add skip pattern:

In `phase_pipeline_factory.py`, modify `_stop_loop_fn`:

```python
def _stop_loop_fn(phase: str):
    """Inner: POST /stop/loop/phase/{phase}. Skips if conf doesn't target this phase."""
    def _stop(**ctx):
        conf = ctx.get("dag_run", MagicMock(conf={})).conf or {}
        target_phase = conf.get("phase")
        if target_phase != phase:
            return None  # skip; this task is for another phase
        # ... rest of original logic
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/stop/loop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Stop loop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}
        if resp.status_code == 200:
            return {**resp.json(), "status": "NOT_LOOPING"}
        if resp.status_code == 400:
            raise AirflowException(
                f"Stop loop {phase} rejected: {resp.text[:500]}"
            )
        raise AirflowException(
            f"Stop loop {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return _stop
```

Update `test_phase_pipeline_factory.py::TestMakeStopLoopTask::test_202_stop_requested` and others to pass `dag_run` arg with conf matching the phase. Update tests accordingly:

```python
class TestMakeStopLoopTask:
    def _call(self, phase, conf):
        op = make_stop_loop_task(phase)
        return op.python_callable(dag_run=MagicMock(conf=conf))

    def test_202_stop_requested(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 202
            mock_resp.json.return_value = {
                "status": "STOP_REQUESTED", "phase": "ITEM_EQUIPMENT",
                "loopId": "loop-1", "iterationCount": 42,
            }
            mock_post.return_value = mock_resp
            result = self._call("ITEM_EQUIPMENT", {"phase": "ITEM_EQUIPMENT"})
        assert result["status"] == "STOP_REQUESTED"

    def test_200_not_looping_idempotent(self):
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = {"status": "NOT_LOOPING"}
            mock_post.return_value = mock_resp
            result = self._call("ITEM_EQUIPMENT", {"phase": "ITEM_EQUIPMENT"})
        assert result["status"] == "NOT_LOOPING"

    def test_skip_when_conf_targets_other_phase(self):
        """If conf.phase != this task's phase, return None (skip)."""
        result = self._call("CHARACTER_BASIC", {"phase": "ITEM_EQUIPMENT"})
        assert result is None

    def test_400_invalid_phase_raises(self):
        from airflow.exceptions import AirflowException
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 400
            mock_resp.text = "INVALID_PHASE"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._call("ITEM_EQUIPMENT", {"phase": "ITEM_EQUIPMENT"})

    def test_5xx_raises(self):
        from airflow.exceptions import AirflowException
        with patch("phase_pipeline_factory.requests.post") as mock_post:
            mock_resp = MagicMock()
            mock_resp.status_code = 502
            mock_resp.text = "bad gateway"
            mock_resp.reason = "Bad Gateway"
            mock_post.return_value = mock_resp
            with pytest.raises(AirflowException):
                self._call("ITEM_EQUIPMENT", {"phase": "ITEM_EQUIPMENT"})
```

- [ ] **Step 3: Run structure test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_phase_dag_structure.py::test_stop_loop_pipeline_parses -v
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/stop_loop_pipeline.py \
          docker/airflow/dags/phase_pipeline_factory.py \
          docker/airflow/dags/tests/test_phase_pipeline_factory.py && \
  git commit -m "feat(airflow): add stop_loop_pipeline DAG + LOOPABLE_PHASES

Single DAG with stop_loop_character_basic and stop_loop_item_equipment
tasks, gated by dag_run.conf['phase'] (skip if not matching). Each
stop is followed by a wait_loop_stopped_<phase> sensor (30min timeout).

Use:
  airflow dags trigger stop_loop_pipeline -c '{\"phase\":\"ITEM_EQUIPMENT\"}'

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 11: Update `test_dag_imports.py` to assert new DAGs

**Files:**
- Modify: `docker/airflow/dags/tests/test_dag_imports.py`

- [ ] **Step 1: Read existing test file**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  cat docker/airflow/dags/tests/test_dag_imports.py
```

- [ ] **Step 2: Modify to include all expected DAGs**

Replace contents with:

```python
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
```

- [ ] **Step 3: Run the test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_dag_imports.py -v
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/tests/test_dag_imports.py && \
  git commit -m "test(airflow): update test_dag_imports for new DAG ids

Asserts all 5 new phase-separated DAGs + cleanup + legacy still parse.
Catches broken imports in CI before runtime.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 12: Deprecate legacy `daily_collection_pipeline.py`

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 1: Update module docstring + DAG tags**

Edit `docker/airflow/dags/daily_collection_pipeline.py`. At the top of the file, replace the docstring (lines 1-8) with:

```python
"""Daily Nexon data collection pipeline (DEPRECATED 2026-06-22).

DEPRECATED: Use the phase-separated DAGs instead:
  - daily_full_pipeline              — scheduled daily chain (mode=once for all phases)
  - ranking_ocid_lookup_pipeline     — manual RANKING + OCID chain
  - character_basic_pipeline         — CHARACTER_BASIC with mode=once|count=N|infinite
  - item_equipment_pipeline          — ITEM_EQUIPMENT with mode=once|count=N|infinite
  - stop_loop_pipeline               — graceful stop for mode=infinite loops

Removal target: next release cycle. See docs/21_Operations/dag-migration.md
for operator migration guide.

This DAG remains parseable for one release cycle to avoid breaking operators
who trigger it directly. Its FULL_DAILY path duplicates daily_full_pipeline's
behavior; its scope/run_steps paths are superseded by phase DAGs.
"""
```

Then in the `with DAG(...)` block, modify the `tags=` argument:

```python
with DAG(
    dag_id="daily_collection_pipeline",
    default_args={
        "owner": "maple-pipeline",
        "retries": 0,
    },
    start_date=datetime(2026, 5, 29),
    schedule=None,  # schedule moved to daily_full_pipeline; legacy is manual-only
    catchup=False,
    tags=["pipeline", "daily", "deprecated"],
) as dag:
```

(Set `schedule=None` so the legacy DAG doesn't double-trigger at 18:00 UTC; `daily_full_pipeline` owns that schedule.)

- [ ] **Step 2: Run DAG import test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/test_dag_imports.py -v
```

Expected: PASS (legacy still parses, tagged deprecated, schedule=None).

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/daily_collection_pipeline.py && \
  git commit -m "feat(airflow): deprecate legacy daily_collection_pipeline

Add 'deprecated' tag, set schedule=None (daily_full_pipeline owns the
18:00 UTC schedule now). Docstring redirects operators to phase DAGs.
Legacy DAG still parseable + runnable for one release cycle to allow
gradual operator migration.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §3.4

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 13: Update `per_phase_tasks.py` with `_legacy` notes

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Update module docstring**

Replace the existing docstring (lines 1-8) with:

```python
"""Per-phase Airflow task factories (LEGACY — used by deprecated daily_collection_pipeline).

This module is retained for backward compatibility with operators still
triggering daily_collection_pipeline -c '{"scope":[...]}' directly.
Removal target: next release cycle after operators migrate to the
phase-separated DAGs.

New DAGs (ranking_ocid_lookup_pipeline, character_basic_pipeline,
item_equipment_pipeline, daily_full_pipeline, stop_loop_pipeline) use
phase_pipeline_factory instead.

Ref: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §6.6
"""
```

- [ ] **Step 2: Add legacy note above `parse_scope`**

Add comment block above `def parse_scope`:

```python
# LEGACY: superseded by phase_pipeline_factory.parse_mode.
# Kept for daily_collection_pipeline scope path.
def parse_scope(conf: dict) -> list:
```

- [ ] **Step 3: Add legacy note above `parse_steps`**

```python
# LEGACY: superseded by phase_pipeline_factory + ordered TaskGroup in v2.
# Kept for daily_collection_pipeline run_steps path.
def parse_steps(conf: dict) -> list:
```

- [ ] **Step 4: Add legacy notes to factory functions**

```python
# LEGACY: superseded by phase_pipeline_factory.make_trigger_once_task.
def make_trigger_task(phase: str) -> PythonOperator:

# LEGACY: superseded by phase_pipeline_factory.make_trigger_loop_task.
def make_loop_task(phase: str) -> PythonOperator:

# LEGACY: superseded by phase_pipeline_factory.make_stop_loop_task.
def make_stop_task(phase: str) -> PythonOperator:

# LEGACY: superseded by phase_pipeline_factory._wait_terminal_fn (in make_phase_dag).
def make_is_phase_terminal(phase: str):
```

- [ ] **Step 5: Run DAG import test**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest \
    docker/airflow/dags/tests/ -v
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docker/airflow/dags/per_phase_tasks.py && \
  git commit -m "docs(airflow): mark per_phase_tasks as legacy

Add _legacy module docstring + per-symbol comments. Symbols unchanged.
Removal target: next release cycle.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §6.6

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 14: Write operator runbook `docs/21_Operations/dag-migration.md`

**Files:**
- Create: `docs/21_Operations/dag-migration.md`

- [ ] **Step 1: Write the runbook**

```markdown
# DAG Migration Guide — Phase-Separated Pipelines

As of 2026-06-22, the legacy `daily_collection_pipeline` is deprecated.
This guide maps common operator workflows to the new phase-separated DAGs.

## Mapping table

| Old (legacy) | New | Trigger command |
|--------------|-----|-----------------|
| `daily_collection_pipeline -c '{}'` (scheduled daily) | `daily_full_pipeline` (scheduled at KST 03:00) | Auto. No operator action needed. |
| `daily_collection_pipeline -c '{"scope":["ITEM_EQUIPMENT_LOOP"]}'` (start loop) | `item_equipment_pipeline -c '{"mode":"infinite"}'` | `airflow dags trigger item_equipment_pipeline -c '{"mode":"infinite"}'` |
| `daily_collection_pipeline -c '{"scope":["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"]}'` (mixed) | (compose) Trigger each phase DAG separately | `airflow dags trigger item_equipment_pipeline -c '{"mode":"infinite"}'` then `airflow dags trigger ranking_ocid_lookup_pipeline -c '{}'` (note: no current way to stop OCID via new DAGs — use ext-api directly or open an issue) |
| `daily_collection_pipeline -c '{"scope":["ITEM_EQUIPMENT"]}'` (run once) | `item_equipment_pipeline -c '{"mode":"once"}'` | `airflow dags trigger item_equipment_pipeline -c '{"mode":"once"}'` |
| `daily_collection_pipeline -c '{"steps":[{...},{...}]}'` (ordered sequence) | Compose phase DAGs sequentially | (no direct equivalent — see "Ordered sequences" below) |
| `daily_collection_pipeline -c '{"scope":["ITEM_EQUIPMENT_STOP"]}'` (stop loop) | `stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'` | `airflow dags trigger stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'` |

## Mode parameter reference

For `character_basic_pipeline` and `item_equipment_pipeline`:

| Mode | Behavior | DAG duration |
|------|----------|--------------|
| `{"mode":"once"}` | Trigger phase, wait terminal | Until phase completes (typically 30-90min for once) |
| `{"mode":"count","count":N}` | Trigger loop, count N chunk-ready events, stop | `N × ~5min + 30min buffer` (e.g., count=3 → ~45min) |
| `{"mode":"infinite"}` | Trigger loop, DAG succeeds immediately | <1min (loop continues in ext-api) |

`count` must be an integer >= 1. `mode` must be one of `once`, `count`, `infinite` (case-sensitive).

## Ordered sequences

The old `steps` config (e.g., `RANKING_FETCH → OCID_LOOKUP → ITEM_EQUIPMENT_LOOP`) is replaced by **explicit sequential triggering**:

```bash
# Trigger ranking_ocid first; wait for it to finish; then trigger item_equipment loop
airflow dags trigger ranking_ocid_lookup_pipeline -c '{}'
# ... wait for completion ...
airflow dags trigger item_equipment_pipeline -c '{"mode":"infinite"}'
```

For an automated sequential chain, use the wrapper:

```bash
airflow dags trigger daily_full_pipeline -c '{}'
```

This runs `ranking_ocid → character_basic(once) → item_equipment(once) → cleanup`. There is no built-in way to chain with `mode=count` or `mode=infinite` — those require operator judgment.

## Stopping an infinite loop

```bash
# Check active loops
curl -s http://localhost:8081/api/internal/run-status | jq '.loopSummaries'

# Stop a loop
airflow dags trigger stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'
# Waits up to 30min for current chunk to drain + loop to finalize.
```

`stop_loop_pipeline` is idempotent: triggering it when no loop is active for the given phase succeeds immediately.

## Verification

After triggering any new DAG, verify in Airflow UI:
1. DAG run shows in DAG's "Runs" tab.
2. Task graph shows expected branch (once/count/infinite).
3. For mode=count, the `count_N_<phase>` sensor task shows progress in logs.
4. For mode=infinite, DAG run succeeds at `trigger_loop_<phase>` task; loop continues in ext-api.

## Migration timeline

- **Now (2026-06-22):** New DAGs active; legacy `daily_collection_pipeline` parseable + manually triggerable. Legacy DAG tagged `deprecated` in UI.
- **One release cycle later:** Remove legacy `daily_collection_pipeline` + `per_phase_tasks.py` legacy symbols. Migrate any remaining operators first.

## Questions?

Open a GitHub issue with the `airflow` label.
```

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docs/21_Operations/dag-migration.md && \
  git commit -m "docs(operations): add DAG migration guide

Maps legacy daily_collection_pipeline triggers to phase-separated DAGs.
Documents mode parameter semantics, ordered sequence workaround, and
stop_loop_pipeline usage. One release cycle deprecation timeline.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md §14

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 15: Write ADR-734

**Files:**
- Create: `docs/01_ADR/ADR-734_phase-separated-dags.md`

- [ ] **Step 1: Write the ADR following project conventions**

Per `.claude/rules/adr-conventions.md`:

```markdown
# ADR-734: Phase-Separated Airflow DAGs

- Status: Accepted
- Date: 2026-06-22
- Owner: pipeline

---

## 1. Background / Problem

### Background

The single `daily_collection_pipeline` DAG dispatched 3 fundamentally different
workflows (full daily chain, per-phase parallel fan-out, ordered steps) via a
`branch_on_scope` operator that parsed JSON `scope` / `steps` config at
runtime. Operators reading the Airflow UI could not tell what a DAG run would
do without inspecting `dag_run.conf`. The DAG graph materialized 17 task
definitions even when 14 were skipped.

### Problem

DAG ergonomics for operators: each workflow intent (run once, run N times,
run forever) required writing a JSON scope config rather than selecting a
pre-built DAG.

### Goal

Each Airflow DAG has a single workflow intent visible from its DAG id and
tags. Operator selects intent by DAG id, not by parsing JSON.

---

## 2. Decision

> Replace `daily_collection_pipeline` with five single-purpose DAGs.

```text
ranking_ocid_lookup_pipeline (manual; RANKING → OCID sequential)
character_basic_pipeline      (manual; mode=once|count=N|infinite)
item_equipment_pipeline       (manual; mode=once|count=N|infinite)
daily_full_pipeline           (cron; chains the 3 above + cleanup)
stop_loop_pipeline            (manual; stops mode=infinite loops)
```

Loop state continues to live in ext-api's in-memory `PhaseLoopController`.
Airflow sensors count chunk-ready events to bound `mode=count`; `stop_loop_pipeline`
provides the kill switch for `mode=infinite`. Legacy `daily_collection_pipeline`
retained as deprecated for one release cycle.

---

## 3. Trade-offs

### Sensitivity

* DAG count: 5 new + 1 legacy + 1 cleanup = 7 DAGs vs 1 today.
* Operator JSON scope parsing: eliminated at trigger time (mode parameter on
  conf is the only free-form input).
* Loop termination latency: `stop_loop_pipeline` waits up to 30min for current
  chunk to drain. Operators wanting immediate stop use ext-api's
  `POST /stop/loop/phase/{phase}` directly.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 5 DAGs vs 1 | Single-responsibility per DAG, clean Airflow UI, intent visible from DAG id, no JSON parsing | 4 extra DAG parses per scheduler cycle (~30s/parse), slight cognitive load from choosing the right DAG |
| Airflow sensor counting chunks (mode=count) | Zero ext-api changes, observable in Airflow logs, bounded slot occupancy | DAG occupies worker slot for `count*5min+30min` |
| Ext-api keeps loop state | Reuses existing `PhaseLoopController`, no behavior change | Loop dies with ext-api restart (pre-existing limitation per #1291 §13) |

### Risk

* Legacy `daily_collection_pipeline` bit rot if not exercised by CI: mitigated
  by `test_dag_imports.py` asserting legacy DAG still parses.
* 5 DAGs → operator confusion about which to trigger: mitigated by runbook
  `docs/21_Operations/dag-migration.md`.
* `count_sensor` timeout mis-tuned (5min/chunk P99 wrong): verified during
  pipeline-test; constant documented in runbook.

### Non-Risk

* `daily_full_pipeline` cron duplicate (both legacy + new trigger at 18:00 UTC):
  mitigated by setting legacy `schedule=None`.
* DAG graph complexity from branch operators: each phase DAG has 1 branch +
  ~6 tasks, well under any Airflow UI rendering limit.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| DAG count | 7 (5 new + 1 legacy + 1 cleanup) | vs 1 before |
| Task count per phase DAG | ~7 (1 health + 1 branch + 2-3 once + 1-3 count + 1-2 stop) | vs 17 in legacy |
| `parse_mode` test coverage | 14 cases | empty/invalid/missing count/zero/negative/case |
| Operator migration window | 1 release cycle | legacy DAG tagged deprecated |

### Observed Result

Pending — measured after pipeline-test skill E2E run.

---

## 5. Summary

> Five single-purpose DAGs replace one overloaded DAG; loop state stays in ext-api;
> operators select workflow by DAG id, not by JSON config.
```

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git add docs/01_ADR/ADR-734_phase-separated-dags.md && \
  git commit -m "docs(adr): ADR-734 phase-separated Airflow DAGs

Decision: replace overloaded daily_collection_pipeline with 5 single-purpose
DAGs. Loop state stays in ext-api (no behavior change). Legacy DAG deprecated
for one release cycle. Runbook + factory helpers + tests included.

Refs: docs/superpowers/specs/2026-06-22-dag-restructure-design.md

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 16: Run full DAG test suite

**Files:** none (verification only)

- [ ] **Step 1: Run all DAG tests**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  PYTHONPATH=docker/airflow/dags python3 -m pytest docker/airflow/dags/tests/ -v 2>&1 | tail -50
```

Expected: all tests pass. Approximate count: ~40 tests across parse_mode (14), trigger (9), count (8), stop (8), wait (5), branch (4), DAG structure (6), imports (2).

- [ ] **Step 2: Verify no leftover placeholders**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  grep -rnE "TODO|FIXME|XXX|TBD" docker/airflow/dags/phase_pipeline_factory.py \
    docker/airflow/dags/ranking_ocid_lookup_pipeline.py \
    docker/airflow/dags/character_basic_pipeline.py \
    docker/airflow/dags/item_equipment_pipeline.py \
    docker/airflow/dags/daily_full_pipeline.py \
    docker/airflow/dags/stop_loop_pipeline.py
```

Expected: no output.

---

## Task 17: Run pipeline-test skill E2E verification

This task is performed by the operator (you) using the existing
`.claude/skills/pipeline-test/SKILL.md` skill. Verification table per
spec §9.4.

- [ ] **Step 1: Start the stack**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  ./claude/skills/pipeline-test/scripts/start.sh
```

(Or follow the skill's workflow manually per `.claude/skills/pipeline-test/SKILL.md`.)

- [ ] **Step 2: Trigger each new DAG and verify**

Per spec §9.4, trigger each:

```bash
# 1. ranking_ocid_lookup_pipeline (sequential RANKING + OCID)
docker exec maple-airflow-scheduler airflow dags trigger ranking_ocid_lookup_pipeline

# 2. character_basic_pipeline mode=once
docker exec maple-airflow-scheduler airflow dags trigger character_basic_pipeline \
  -c '{"mode":"once"}'

# 3. item_equipment_pipeline mode=count
docker exec maple-airflow-scheduler airflow dags trigger item_equipment_pipeline \
  -c '{"mode":"count","count":3}'

# 4. item_equipment_pipeline mode=infinite, then stop
docker exec maple-airflow-scheduler airflow dags trigger item_equipment_pipeline \
  -c '{"mode":"infinite"}'
# Wait for DAG to succeed (~1min); verify loop still active
curl -s http://localhost:8081/api/internal/run-status | jq '.loopSummaries'
docker exec maple-airflow-scheduler airflow dags trigger stop_loop_pipeline \
  -c '{"phase":"ITEM_EQUIPMENT"}'

# 5. daily_full_pipeline (full chain)
docker exec maple-airflow-scheduler airflow dags trigger daily_full_pipeline

# 6. Legacy daily_collection_pipeline still works
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{}'
```

- [ ] **Step 3: Verify each run in Airflow UI**

Open http://localhost:8180 (admin/admin). For each DAG run, verify:
- DAG run shows correct task graph (branch resolved per conf).
- For mode=count, `count_sensor_item_equipment` sensor task runs and exits True.
- For mode=infinite, DAG succeeds at `trigger_loop_item_equipment` task.
- For stop_loop, `wait_loop_stopped_item_equipment` sensor exits True after loop finalizes.
- Legacy `daily_collection_pipeline` triggers the existing FULL_DAILY chain.

- [ ] **Step 4: Document results in PR description**

Note which DAGs verified successfully. If any fail, capture error output and fix before PR.

---

## Task 18: Create PR + merge to develop

- [ ] **Step 1: Push branch**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git push -u origin feat/dag-restructure
```

- [ ] **Step 2: Create PR**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  gh pr create \
    --base develop \
    --title "feat(airflow): phase-separated DAGs (5 single-purpose DAGs)" \
    --body "$(cat <<'EOF'
## Summary

Replace `daily_collection_pipeline` (one DAG, 3 workflow intents via JSON `scope`/`steps`) with 5 single-purpose DAGs:

- `ranking_ocid_lookup_pipeline` — sequential RANKING + OCID
- `character_basic_pipeline` — mode=once|count=N|infinite
- `item_equipment_pipeline` — mode=once|count=N|infinite
- `daily_full_pipeline` — wrapper cron DAG (KST 03:00)
- `stop_loop_pipeline` — graceful stop for mode=infinite

Loop state stays in ext-api (no ext-api changes). N-count mode uses Airflow PythonSensor polling Kafka chunk-ready events; mode=infinite is fire-and-forget with separate stop DAG.

Legacy `daily_collection_pipeline` retained as `tags=["deprecated"]` for one release cycle. `per_phase_tasks.py` marked `_legacy`.

## Test plan

- [ ] All DAG imports parse (CI test_dag_imports.py)
- [ ] `parse_mode` unit tests (14 cases)
- [ ] trigger / count / stop helpers unit tests (~25 cases)
- [ ] Branch + DAG structure tests (DagBag-level)
- [ ] Manual pipeline-test E2E per spec §9.4 (ranking_ocid, character_basic mode=once, item_equipment mode=count, mode=infinite + stop_loop, daily_full, legacy still works)

## Refs

- Spec: `docs/superpowers/specs/2026-06-22-dag-restructure-design.md`
- Plan: `docs/superpowers/plans/2026-06-22-dag-restructure.md`
- ADR: `docs/01_ADR/ADR-734_phase-separated-dags.md`
- Runbook: `docs/21_Operations/dag-migration.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for CI**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  gh pr checks --watch
```

Expected: all checks green.

- [ ] **Step 4: Merge to develop**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  gh pr merge --squash --delete-branch
```

- [ ] **Step 5: Verify merged**

```bash
cd /home/maple/probabilistic-valuation-engine && \
  git log --oneline develop | head -5
```

Expected: merge commit visible on develop.
