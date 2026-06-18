# Issue #1292 Per-Phase Airflow DAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend Airflow `daily_collection_pipeline.py` with a `scope`-driven branch that triggers, loops, or stops a single ext-api phase without running the full daily pipeline.

**Architecture:** New helper module `docker/airflow/dags/per_phase_tasks.py` exposes three task factories (`make_trigger_task`, `make_loop_task`, `make_stop_task`) plus `parse_scope` validator and `make_is_phase_terminal` sensor. `daily_collection_pipeline.py` adds a `BranchPythonOperator` that routes `dag_run.conf['scope']` to either the existing daily chain or a per-phase fan-out join.

**Tech Stack:** Airflow 2.10.5 (python3.12), `apache/airflow:2.10.5-python3.12` image, `requests`, `pytest` (Airflow-bundled), `unittest.mock` for HTTP mocking, `DagBag` for DAG loader tests.

**Spec:** `docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md`

---

## File Structure

| File | Responsibility | Lines (est.) |
|------|----------------|--------------|
| `docker/airflow/dags/per_phase_tasks.py` (new) | Pure helpers: `parse_scope`, 3 task factories, sensor factory, 3 frozen phase lists | ~270 |
| `docker/airflow/dags/daily_collection_pipeline.py` (extend) | Add imports + `branch_on_scope` + `per_phase_join` + 11 per-phase tasks + 4 sensors + rewire `check_external_api` | +60 |
| `docker/airflow/dags/tests/__init__.py` (new) | Test package marker | 1 |
| `docker/airflow/dags/tests/conftest.py` (new) | Pytest fixtures (mock dag_run context, mock Airflow connection) | ~30 |
| `docker/airflow/dags/tests/test_per_phase_tasks.py` (new) | Unit tests for `parse_scope` + factory gates + DAG loader test | ~180 |
| `docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md` (new) | Short ADR summarizing decision (refs spec) | ~80 |

Total: ~620 lines.

---

## Task 1: Create feature branch

**Files:** none

- [ ] **Step 1: Confirm clean working tree**

Run: `git status`
Expected: `nothing to commit, working tree clean`

- [ ] **Step 2: Create branch from develop**

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feature/issue-1292-per-phase-dag
```

Expected: branch created.

- [ ] **Step 3: Verify branch**

Run: `git branch --show-current`
Expected: `feature/issue-1292-per-phase-dag`

---

## Task 2: Write ADR-XXX documenting decision

**Files:**
- Create: `docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md`

Per project `.claude/rules/adr-conventions.md`: 5-section ADR template (Background / Decision / Trade-offs / Result / Summary). This is implementation work, so ADR is mandatory per `rpi-workflow.md`.

- [ ] **Step 1: Determine ADR number**

Run: `ls docs/01_ADR/ | grep '^ADR-' | sort | tail -5`
Expected: list of existing ADRs. Pick next number (e.g. if highest is `ADR-021`, use `ADR-022`).

- [ ] **Step 2: Create ADR file**

Create `docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md` with this content (replace `XXX` with chosen number):

```markdown
# ADR-XXX: Per-Phase Airflow DAG for ext-api Phase Endpoints

- Status: Accepted
- Date: 2026-06-18
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- ext-api added per-phase endpoints in #1289/1290/1291: `POST /trigger/phase/{name}`, `POST /stop/phase/{name}`, `POST /loop/phase/{name}`, `POST /stop/loop/phase/{name}`.
- Operators currently can only drive ext-api from the full daily pipeline. No way to hot-loop a phase or stop a runaway loop without restarting ext-api.

### Problem

- Need a UI / CLI-driven path to invoke per-phase endpoints without standing up the full daily chain.

### Goal

- Add Airflow support for per-phase trigger / loop / stop via `dag_run.conf['scope']`.

---

## 2. Decision

> We extend the existing `daily_collection_pipeline.py` with a `BranchPythonOperator` that reads `dag_run.conf['scope']`. When `scope` is `FULL_DAILY` (default) → existing chain. Otherwise → per-phase fan-out.

```text
check_external_api
  └── branch_on_scope
        ├── trigger_daily_collection → ... → trigger_cleanup  (FULL_DAILY)
        └── per_phase_join
              ├── per_phase_trigger_<PHASE> (×4) → per_phase_wait_<PHASE> (×4)
              ├── per_phase_loop_<PHASE> (×3)    [fire-and-forget]
              └── per_phase_stop_<PHASE> (×4)    [fire-and-forget]
```

Helper module `per_phase_tasks.py` owns task factories; DAG file stays thin.

---

## 3. Trade-offs

### Sensitivity

* DAG run volume (operators may spam scope triggers)
* ext-api connection availability at DAG-parse time (DagBag loads all DAGs on scheduler startup)
* Airflow version (2.10.5; `BranchPythonOperator` requires 2.0+)

### Trade-off

| Choice | Gain | Lose |
|--------|------|------|
| Shape A (extend daily DAG) | One DAG file; reuses existing connection + health-check tasks | Daily DAG graph view grows by 17 task definitions |
| Shape B (new DAG file) | Cleaner separation | Operators must know which DAG to trigger; duplicatestask definitions |
| Per-task gate via `dag_run.conf` check inside callable | DAG parses without per-runtime graph | Unused task definitions show in graph view |
| `_STOP` reuses `/stop/phase` (single endpoint) | One endpoint, simpler | Operators cannot distinguish loop-stop vs single-shot stop in API |

We picked **Shape A + per-task gate + `/stop/phase` reuse**.

### Risk

* 17 unused task definitions cluttering graph view — mitigated by per-task gating; documentation cost only.
* `/stop/phase` halts both loops and single-shots — operators must check `loopSummaries` post-stop to confirm; documented in DAG docstring.

### Non-Risk

* Cross-phase ordering — explicitly out-of-scope; operators use TriggerDagRunOperator for chains.
* Loop auto-restart — explicitly out-of-scope per #1291 §11.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|-------|-------|
| New task definitions | 17 | 4 trigger + 4 sensor + 3 loop + 4 stop + 1 branch + 1 join |
| New files | 3 | per_phase_tasks.py, tests/__init__.py, tests/test_per_phase_tasks.py |
| Modified files | 1 | daily_collection_pipeline.py (+60 lines) |
| Existing daily chain | unchanged | verified by DagBag parse test |

### Observed Result

* (Filled in post-implementation via manual smoke test from `pipeline-test` skill)

---

## 5. Summary

> Extend the existing Airflow daily DAG with a `scope`-driven branch that routes `dag_run.conf['scope']` to either the existing daily chain or a per-phase fan-out via 11 task definitions backed by 3 helper factories.
```

- [ ] **Step 3: Commit ADR**

```bash
git add docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md
git commit -m "docs(adr): per-phase Airflow DAG decision (issue #1292)"
```

---

## Task 3: Create test scaffolding + pytest fixtures

**Files:**
- Create: `docker/airflow/dags/tests/__init__.py`
- Create: `docker/airflow/dags/tests/conftest.py`

- [ ] **Step 1: Create tests package marker**

Write `docker/airflow/dags/tests/__init__.py` (empty file).

Run: `touch docker/airflow/dags/tests/__init__.py`

- [ ] **Step 2: Create conftest.py**

Write `docker/airflow/dags/tests/conftest.py`:

```python
"""Pytest fixtures for per_phase_tasks unit tests."""
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# Ensure dags/ is importable
DAGS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(DAGS_DIR))


@pytest.fixture
def mock_dag_run_conf():
    """Factory: mock context['dag_run'].conf for parse_scope."""

    def _make(conf):
        dag_run = MagicMock()
        dag_run.conf = conf
        return {"dag_run": dag_run}

    return _make


@pytest.fixture
def mock_external_api_conn():
    """Mock the Airflow 'external_api' connection to localhost."""
    with patch("per_phase_tasks.BaseHook") as base_hook:
        conn = MagicMock()
        conn.host = "localhost"
        conn.port = 8081
        base_hook.get_connection.return_value = conn
        yield base_hook
```

- [ ] **Step 3: Verify pytest discovery works**

Run: `docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/ --collect-only -q"`

Expected: `no tests ran` or `0 tests collected` (no tests yet). Confirms pytest is available.

If scheduler container is not running:
```bash
docker compose -f docker-compose.airflow.yml up -d airflow-scheduler
```

- [ ] **Step 4: Commit scaffolding**

```bash
git add docker/airflow/dags/tests/__init__.py docker/airflow/dags/tests/conftest.py
git commit -m "test(airflow): pytest scaffolding for per_phase_tasks"
```

---

## Task 4: Write failing tests for parse_scope (TDD red)

**Files:**
- Create: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Write parse_scope test file**

Write `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
"""Unit tests for per_phase_tasks.parse_scope."""
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
```

- [ ] **Step 2: Run tests to verify they fail (RED)**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v"
```

Expected: `ModuleNotFoundError: No module named 'per_phase_tasks'` (since the module doesn't exist yet) for all 7 tests.

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing tests for parse_scope"
```

---

## Task 5: Implement parse_scope + ALLOWED_SCOPES (TDD green)

**Files:**
- Create: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Implement parse_scope and constants**

Write the top of `docker/airflow/dags/per_phase_tasks.py`:

```python
"""Per-phase Airflow task factories for ext-api.

Drives the per-phase endpoints from #1289/1290/1291 via Airflow's
BranchPythonOperator in daily_collection_pipeline.py.

Spec: docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md
"""
from datetime import timedelta
import json

import requests
from airflow.exceptions import AirflowException
from airflow.hooks.base import BaseHook
from airflow.operators.python import PythonOperator
from airflow.sensors.python import PythonSensor


# Allowed scope values. RANKING_FETCH_LOOP intentionally excluded — ext-api
# PhaseLoopController.loopablePhases from #1291 excludes RANKING_FETCH.
ALLOWED_SCOPES = frozenset({
    "RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT",
    "OCID_LOOKUP_LOOP", "CHARACTER_BASIC_LOOP", "ITEM_EQUIPMENT_LOOP",
    "RANKING_FETCH_STOP", "OCID_LOOKUP_STOP",
    "CHARACTER_BASIC_STOP", "ITEM_EQUIPMENT_STOP",
})

# Phase lists for fan-out
TRIGGER_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]
LOOP_PHASES = ["OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]
STOP_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]


def get_external_api_base() -> str:
    """Resolve ext-api base URL from Airflow Connection 'external_api'."""
    conn = BaseHook.get_connection("external_api")
    return f"http://{conn.host}:{conn.port}"


def parse_scope(conf: dict) -> list:
    """Validate dag_run.conf['scope']. Returns list of scope values.

    - Missing or 'FULL_DAILY' → ['FULL_DAILY']
    - String → wrap in list
    - List → validate every value against ALLOWED_SCOPES
    - Any invalid value → raise AirflowException
    """
    scope = conf.get("scope", "FULL_DAILY")
    if scope == "FULL_DAILY":
        return ["FULL_DAILY"]
    if isinstance(scope, str):
        scope = [scope]
    if not isinstance(scope, list):
        raise AirflowException(
            f"scope must be string or list, got {type(scope).__name__}"
        )
    invalid = [s for s in scope if s not in ALLOWED_SCOPES]
    if invalid:
        raise AirflowException(
            f"Invalid scope values: {invalid}. "
            f"Allowed: {sorted(ALLOWED_SCOPES)}"
        )
    return list(scope)
```

- [ ] **Step 2: Run tests to verify they pass (GREEN)**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v"
```

Expected: all 13 tests pass (5 valid parametrize × 1 + 5 invalid parametrize × 1 + 1 constant guard + 1 of constants = 12 parametrized cases + 1 constant).

If any fail, fix and re-run.

- [ ] **Step 3: Commit parse_scope**

```bash
git add docker/airflow/dags/per_phase_tasks.py
git commit -m "feat(airflow): parse_scope validator + ALLOWED_SCOPES"
```

---

## Task 6: Write failing tests for make_trigger_task (TDD red)

**Files:**
- Modify: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Append trigger factory tests**

Append to `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
from unittest.mock import MagicMock, patch
from per_phase_tasks import make_trigger_task, make_loop_task, make_stop_task


def _make_ctx(conf, xcom_value=None):
    """Build a minimal Airflow task context dict."""
    dag_run = MagicMock()
    dag_run.conf = conf
    ti = MagicMock()
    if xcom_value is not None:
        ti.xcom_pull.return_value = xcom_value
    else:
        ti.xcom_pull.return_value = None
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
```

- [ ] **Step 2: Run new tests to verify they fail (RED)**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py::test_make_trigger_task_skips_when_scope_empty -v"
```

Expected: `ImportError: cannot import name 'make_trigger_task' from 'per_phase_tasks'`.

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing tests for make_trigger_task"
```

---

## Task 7: Implement make_trigger_task (TDD green)

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Append make_trigger_task implementation**

Append to `docker/airflow/dags/per_phase_tasks.py`:

```python
def make_trigger_task(phase: str) -> PythonOperator:
    """Single-shot phase trigger via /trigger/phase/{phase}.

    Gates on scope: returns None (Airflow skip) if bare phase not in scope.
    """
    def _trigger(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if phase not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}", timeout=30
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
            current = data.get("current") or {}
            return {
                "runId": current.get("runId"),
                "phase": phase,
                "status": "ALREADY_ACTIVE",
            }

        raise AirflowException(
            f"Trigger {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return PythonOperator(
        task_id=f"per_phase_trigger_{phase.lower()}",
        python_callable=_trigger,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

- [ ] **Step 2: Run trigger tests to verify they pass (GREEN)**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k trigger"
```

Expected: all 4 trigger tests pass.

- [ ] **Step 3: Commit make_trigger_task**

```bash
git add docker/airflow/dags/per_phase_tasks.py
git commit -m "feat(airflow): make_trigger_task factory"
```

---

## Task 8: Write failing tests for make_loop_task (TDD red)

**Files:**
- Modify: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Append loop factory tests**

Append to `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
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

        with pytest.raises(AirflowException):
            task.python_callable(**ctx)
```

- [ ] **Step 2: Run loop tests to verify they fail (RED)**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k loop"
```

Expected: `ImportError: cannot import name 'make_loop_task'`.

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing tests for make_loop_task"
```

---

## Task 9: Implement make_loop_task (TDD green)

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Append make_loop_task**

Append to `docker/airflow/dags/per_phase_tasks.py`:

```python
def make_loop_task(phase: str) -> PythonOperator:
    """Start loop via /loop/phase/{phase}.

    Gates on scope: returns None if {phase}_LOOP not in scope.
    409 → idempotent success with ALREADY_LOOPING status.
    400 → AirflowException (config error, e.g. RANKING_FETCH_LOOP).
    """
    def _loop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_LOOP" not in scope:
            return None
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

    return PythonOperator(
        task_id=f"per_phase_loop_{phase.lower()}",
        python_callable=_loop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

- [ ] **Step 2: Run loop tests to verify GREEN**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k loop"
```

Expected: all 4 loop tests pass.

- [ ] **Step 3: Commit make_loop_task**

```bash
git add docker/airflow/dags/per_phase_tasks.py
git commit -m "feat(airflow): make_loop_task factory"
```

---

## Task 10: Write failing tests for make_stop_task (TDD red)

**Files:**
- Modify: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Append stop factory tests**

Append to `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
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

        with pytest.raises(AirflowException):
            task.python_callable(**ctx)
```

- [ ] **Step 2: Run stop tests to verify RED**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k stop"
```

Expected: `ImportError: cannot import name 'make_stop_task'`.

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing tests for make_stop_task"
```

---

## Task 11: Implement make_stop_task (TDD green)

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Append make_stop_task**

Append to `docker/airflow/dags/per_phase_tasks.py`:

```python
def make_stop_task(phase: str) -> PythonOperator:
    """Stop via /stop/phase/{phase}.

    Single endpoint halts both single-shot runs and loops (per #1290 spec §5.3).
    Gates on scope: returns None if {phase}_STOP not in scope.
    """
    def _stop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_STOP" not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(
                f"{base}/api/internal/stop/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(f"Stop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}

        if resp.status_code == 200:
            return {"phase": phase, "status": "NOT_RUNNING"}

        raise AirflowException(
            f"Stop {phase} failed: HTTP {resp.status_code} "
            f"{resp.reason}: {resp.text[:500]}"
        )

    return PythonOperator(
        task_id=f"per_phase_stop_{phase.lower()}",
        python_callable=_stop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

- [ ] **Step 2: Run stop tests to verify GREEN**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v"
```

Expected: all parse_scope + trigger + loop + stop tests pass (5 valid + 5 invalid + 1 constant + 4 trigger + 4 loop + 4 stop = 23 tests).

- [ ] **Step 3: Commit make_stop_task**

```bash
git add docker/airflow/dags/per_phase_tasks.py
git commit -m "feat(airflow): make_stop_task factory"
```

---

## Task 12: Write failing test for make_is_phase_terminal (TDD red)

**Files:**
- Modify: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Append sensor tests**

Append to `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
from per_phase_tasks import make_is_phase_terminal


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
```

- [ ] **Step 2: Run sensor tests to verify RED**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k sensor"
```

Expected: `ImportError: cannot import name 'make_is_phase_terminal'`.

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing tests for make_is_phase_terminal"
```

---

## Task 13: Implement make_is_phase_terminal (TDD green)

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`

- [ ] **Step 1: Append make_is_phase_terminal**

Append to `docker/airflow/dags/per_phase_tasks.py`:

```python
def make_is_phase_terminal(phase: str):
    """PythonSensor callable: returns True when triggered runId reaches terminal.

    Gates on scope first: if phase not in scope, returns True (skip).
    FAILED phase → RuntimeError (hard-fail DAG).
    Transient HTTP → False (reschedule).
    """
    task_id = f"per_phase_trigger_{phase.lower()}"

    def _poke(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if phase not in scope:
            return True

        xcom_val = ctx["ti"].xcom_pull(task_ids=task_id)
        if isinstance(xcom_val, str):
            xcom_val = json.loads(xcom_val)
        run_id = (xcom_val or {}).get("runId")
        if not run_id:
            raise RuntimeError(
                f"Sensor for {phase} triggered but trigger xcom returned no runId "
                f"— config error"
            )

        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status",
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError):
            return False

        current = data.get("current")
        if not current or current.get("runId") != run_id:
            return False

        if current.get("phase") == "FAILED":
            raise RuntimeError(
                f"Run {run_id} failed: {current.get('errorMessage', 'unknown')}"
            )

        return bool(current.get("terminal", False))

    return _poke
```

- [ ] **Step 2: Run all tests to verify GREEN**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v"
```

Expected: all 28 tests pass (parse_scope 11 + trigger 4 + loop 4 + stop 4 + sensor 5).

- [ ] **Step 3: Commit make_is_phase_terminal**

```bash
git add docker/airflow/dags/per_phase_tasks.py
git commit -m "feat(airflow): make_is_phase_terminal sensor factory"
```

---

## Task 14: Write failing DAG loader test (TDD red)

**Files:**
- Modify: `docker/airflow/dags/tests/test_per_phase_tasks.py`

- [ ] **Step 1: Append DAG loader tests**

Append to `docker/airflow/dags/tests/test_per_phase_tasks.py`:

```python
import os
from airflow.models import DagBag


def test_daily_collection_pipeline_parses():
    """DAG parses without import errors (regression guard for rewire)."""
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    assert "daily_collection_pipeline" in dagbag.dags, (
        f"DAG missing. Errors: {dagbag.import_errors}"
    )
    assert dagbag.import_errors == {}, f"Import errors: {dagbag.import_errors}"


def test_branch_on_scope_task_exists():
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    dag = dagbag.dags["daily_collection_pipeline"]
    assert "branch_on_scope" in dag.task_ids


def test_all_per_phase_tasks_present():
    """11 per-phase task definitions expected."""
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
    dag_folder = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dagbag = DagBag(dag_folder=dag_folder, include_examples=False)
    dag = dagbag.dags["daily_collection_pipeline"]
    branch_task = dag.get_task("branch_on_scope")
    downstream_ids = set(branch_task.downstream_list)
    assert "trigger_daily_collection" in downstream_ids
    assert "per_phase_join" in downstream_ids
```

- [ ] **Step 2: Run DAG loader tests to verify RED**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k 'dag' 2>&1 | tail -30"
```

Expected: `dag_branch_on_scope_task_exists` fails with `KeyError: 'branch_on_scope'` (task not yet wired).

- [ ] **Step 3: Commit failing tests**

```bash
git add docker/airflow/dags/tests/test_per_phase_tasks.py
git commit -m "test(airflow): failing DAG loader tests for branch_on_scope"
```

---

## Task 15: Wire branch_on_scope into daily_collection_pipeline.py

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 1: Add imports at top of file**

Open `docker/airflow/dags/daily_collection_pipeline.py` and add these lines after the existing import block (after `from airflow.sensors.python import PythonSensor`):

```python
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator

from per_phase_tasks import (
    parse_scope,
    make_trigger_task,
    make_loop_task,
    make_stop_task,
    make_is_phase_terminal,
    TRIGGER_PHASES,
    LOOP_PHASES,
    STOP_PHASES,
)
```

- [ ] **Step 2: Add new task definitions inside DAG context**

Find the line `check_external_api >> trigger_daily_collection >> wait_for_completion >> wait_ie_cycle >> trigger_cleanup` at the end of the DAG context (around line 238). Insert **before** that line:

```python
    # Per-phase branch (issue #1292). Activated when dag_run.conf['scope']
    # is set to a list of action values (see per_phase_tasks.ALLOWED_SCOPES).
    branch_on_scope = BranchPythonOperator(
        task_id="branch_on_scope",
        python_callable=lambda **ctx: (
            "trigger_daily_collection"
            if parse_scope(ctx["dag_run"].conf or {}) == ["FULL_DAILY"]
            else "per_phase_join"
        ),
    )

    per_phase_join = EmptyOperator(
        task_id="per_phase_join",
        trigger_rule="none_failed_min_one_success",
    )

    per_phase_trigger_tasks = [make_trigger_task(p) for p in TRIGGER_PHASES]
    per_phase_loop_tasks = [make_loop_task(p) for p in LOOP_PHASES]
    per_phase_stop_tasks = [make_stop_task(p) for p in STOP_PHASES]

    per_phase_trigger_sensors = [
        PythonSensor(
            task_id=f"per_phase_wait_{p.lower()}_completion",
            python_callable=make_is_phase_terminal(p),
            mode="reschedule",
            poke_interval=60,
            timeout=60 * 60 * 4,
        )
        for p in TRIGGER_PHASES
    ]
```

- [ ] **Step 3: Rewire the root + add per-phase edges**

Replace the final line `check_external_api >> trigger_daily_collection >> ...` with:

```python
    check_external_api >> branch_on_scope
    branch_on_scope >> trigger_daily_collection
    branch_on_scope >> per_phase_join
    per_phase_join >> per_phase_trigger_tasks >> per_phase_trigger_sensors
    per_phase_join >> per_phase_loop_tasks
    per_phase_join >> per_phase_stop_tasks
    trigger_daily_collection >> wait_for_completion >> wait_ie_cycle >> trigger_cleanup
```

- [ ] **Step 4: Run DAG loader tests to verify GREEN**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v -k dag"
```

Expected: all 4 DAG loader tests pass.

- [ ] **Step 5: Run full test suite**

Run:
```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python -m pytest tests/test_per_phase_tasks.py -v"
```

Expected: all 32 tests pass (28 unit + 4 DAG loader).

- [ ] **Step 6: Verify DAG parses in scheduler**

```bash
docker exec maple-airflow-scheduler airflow dags list | grep daily_collection_pipeline
```

Expected: `daily_collection_pipeline` listed.

- [ ] **Step 7: Verify all 11 per-phase tasks visible**

```bash
docker exec maple-airflow-scheduler airflow tasks list daily_collection_pipeline | grep per_phase
```

Expected: 11 lines (4 trigger + 3 loop + 4 stop).

- [ ] **Step 8: Commit DAG wiring**

```bash
git add docker/airflow/dags/daily_collection_pipeline.py
git commit -m "feat(airflow): wire branch_on_scope + per-phase fan-out into daily DAG"
```

---

## Task 16: Manual smoke test via pipeline-test skill

**Files:** none (runtime verification only)

- [ ] **Step 1: Confirm module-external-api is running locally**

Run: `curl -s http://localhost:8081/actuator/health | jq '.status'`
Expected: `"UP"`

If DOWN: `./gradlew :module-external-api:bootRun` (background).

- [ ] **Step 2: Confirm airflow scheduler is running**

Run: `docker exec maple-airflow-scheduler airflow dags list | grep daily_collection_pipeline`
Expected: DAG listed.

If not running: `docker compose -f docker-compose.airflow.yml up -d`.

- [ ] **Step 3: Test FULL_DAILY path (default)**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline
```

Expected: DAG run created, executes existing daily chain (4 phases + cleanup). Verify via `airflow dags list-runs daily_collection_pipeline`.

- [ ] **Step 4: Test single-phase trigger scope**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT"]}'
```

Expected: only `per_phase_trigger_item_equipment` + `per_phase_wait_item_equipment_completion` execute. Other 10 per-phase tasks skipped (their callables return None).

Verify via:
```bash
curl -s http://localhost:8081/api/internal/run-status | jq '.current.phase'
```
Expected: `"ITEM_EQUIPMENT"` (within 5s).

- [ ] **Step 5: Test loop scope**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT_LOOP"]}'
```

Expected: `per_phase_loop_item_equipment` succeeds, returns 202 with loopId.

Verify after 60s:
```bash
curl -s http://localhost:8081/api/internal/run-status | jq '.loopSummaries.ITEM_EQUIPMENT.iterationCount'
```
Expected: integer > 0.

- [ ] **Step 6: Test stop scope**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT_STOP"]}'
```

Expected: `per_phase_stop_item_equipment` returns 202 STOP_REQUESTED.

Verify within 30s:
```bash
curl -s http://localhost:8081/api/internal/run-status | jq '.loopSummaries.ITEM_EQUIPMENT.status'
```
Expected: `"STOPPED"`.

- [ ] **Step 7: Test invalid scope fails fast**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["RANKING_FETCH_LOOP"]}'
```

Expected: DAG run fails immediately with `AirflowException: Invalid scope values: ['RANKING_FETCH_LOOP']`.

- [ ] **Step 8: Test multi-action scope**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"]}'
```

Expected: both `per_phase_loop_item_equipment` + `per_phase_stop_ocid_lookup` execute in parallel.

- [ ] **Step 9: Document smoke test results**

Append to `docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md` §4 Result / Evidence table:

```markdown
### Observed Result

* FULL_DAILY scope: existing daily chain ran unchanged (4 phases + cleanup completed)
* ITEM_EQUIPMENT scope: triggered only ITEM_EQUIPMENT; /run-status showed ACTIVE within 5s
* ITEM_EQUIPMENT_LOOP scope: loopId returned; iterationCount > 0 after 60s
* ITEM_EQUIPMENT_STOP scope: loop status transitioned to STOPPED within 30s
* Invalid scope (RANKING_FETCH_LOOP): DAG run failed fast with AirflowException
* Multi-action scope: parallel execution confirmed via task log
```

- [ ] **Step 10: Commit smoke test results**

```bash
git add docs/01_ADR/ADR-XXX-airflow-per-phase-dag.md
git commit -m "docs(adr): record manual smoke test results for #1292"
```

---

## Task 17: Create PR

**Files:** none

- [ ] **Step 1: Push branch**

```bash
git push origin feature/issue-1292-per-phase-dag
```

- [ ] **Step 2: Open PR to develop**

```bash
gh pr create --base develop --head feature/issue-1292-per-phase-dag \
  --title "feat(airflow): per-phase scope-driven branch in daily DAG (issue #1292)" \
  --body "$(cat <<'EOF'
Adds per-phase scope-driven branch to daily_collection_pipeline.py. Operators can trigger, loop, or stop a single ext-api phase via `airflow dags trigger -c '{"scope": ["..."]}'` without running the full daily chain.

New helper module `per_phase_tasks.py` exposes 3 task factories (trigger/loop/stop) + parse_scope validator + phase-filtered sensor factory. 11 per-phase task definitions fan out from a BranchPythonOperator.

Acceptance criteria all covered via unit tests + manual smoke test against local stack.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Verify PR created**

Run: `gh pr list --head feature/issue-1292-per-phase-dag`
Expected: PR shown with `develop` base.

---

## Self-Review Checklist (run after writing plan)

- [ ] **Spec coverage**: §3.2 (allowed values) → Task 5; §4.1 (make_trigger_task) → Tasks 6+7; §4.2 (make_loop_task) → Tasks 8+9; §4.3 (make_stop_task) → Tasks 10+11; §4.4 (make_is_phase_terminal) → Tasks 12+13; §4.5 (DAG wiring) → Tasks 14+15; §5 (error handling) → covered in each factory's tests; §6 (test plan) → Tasks 3-15; §7 (AC mapping) → Tasks 16+17.
- [ ] **Placeholder scan**: no "TBD" / "TODO" / "fill in details" anywhere.
- [ ] **Type consistency**: `parse_scope` returns `list` everywhere; `make_trigger_task(phase: str)` matches across tests + impl + DAG wiring; `ALLOWED_SCOPES` defined once in Task 5, referenced in tests + impl.
- [ ] **Commit granularity**: 17 commits across 17 tasks — each task ends with `git commit`.