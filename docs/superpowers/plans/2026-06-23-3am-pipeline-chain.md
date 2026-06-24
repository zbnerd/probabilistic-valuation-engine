# 3am Pipeline Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Airflow master DAG `morning_chain_pipeline` that triggers the four existing per-phase DAGs in sequence at 03:00 KST, with idempotent loop-stop handling and strict failure halt.

**Architecture:** Single new Airflow DAG composes existing per-phase DAGs via `TriggerDagRunOperator` and factory-provided sensors. `make_wait_loop_stopped_sensor` handles the idempotent stop (returns True if no loop active). `make_wait_phase_terminal_sensor` gates each subsequent phase. A small custom sensor confirms the final infinite loop's first iteration has started. Zero Kotlin or endpoint changes.

**Tech Stack:** Python 3.12, Airflow 2.10.5, `phase_pipeline_factory` helpers (all pre-existing).

**Spec:** `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md`

**Cron note:** Schedule `0 18 * * *` UTC = 03:00 KST year-round (KST = UTC+9, no DST).

---

## File Structure

| File | Status | Responsibility |
| ---- | ------ | -------------- |
| `docker/airflow/dags/morning_chain_pipeline.py` | Create | Master DAG: 8 tasks, chain orchestration |
| `docker/airflow/dags/tests/test_morning_chain_pipeline.py` | Create | Unit tests for DAG structure, schedule, conf, dependencies, factory wiring |

No other files modified. No Kotlin, no endpoints, no migrations.

---

## Task 1: Test scaffold + DAG id/schedule

**Files:**
- Create: `docker/airflow/dags/tests/test_morning_chain_pipeline.py`

- [ ] **Step 1: Write the failing test file**

```python
"""Tests for morning_chain_pipeline DAG.

Validates structural invariants: dag_id, schedule, catchup, retries,
task count, factory wiring. Does not exercise the chain end-to-end
(manual Airflow run required for integration).
"""
import pytest


@pytest.fixture(scope="module")
def dag():
    from airflow.models import DagBag
    bag = DagBag(dag_folder="/dev/null", include_examples=False)
    # Load directly from the known path. The factory imports require
    # Airflow's DAG context but DagBag parses files for us.
    bag._load_dag(
        "morning_chain_pipeline",
        "/opt/airflow/dags/morning_chain_pipeline.py",
    )
    return bag.dags["morning_chain_pipeline"]


def test_dag_id(dag):
    assert dag.dag_id == "morning_chain_pipeline"


def test_schedule_is_3am_kst(dag):
    # 0 18 * * * UTC = 03:00 KST year-round (UTC+9, no DST).
    assert dag.schedule == "0 18 * * *"


def test_no_catchup(dag):
    assert dag.catchup is False


def test_start_date_set(dag):
    assert dag.start_date is not None


def test_no_retries(dag):
    assert dag.default_args.get("retries") == 0
```

- [ ] **Step 2: Run the test, expect failure**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 5 failures with `KeyError: 'morning_chain_pipeline'`.

- [ ] **Step 3: Skip (no commit — next task adds the DAG)**

---

## Task 2: Minimal DAG skeleton (dag_id + schedule only)

**Files:**
- Create: `docker/airflow/dags/morning_chain_pipeline.py`

- [ ] **Step 1: Write the skeleton**

```python
"""morning_chain_pipeline — 03:00 KST chain orchestration.

Schedule: 0 18 * * * UTC (03:00 KST year-round, UTC+9 no DST).
Composes existing per-phase DAGs via factory helpers:
  1. stop_loop_pipeline (loop auto-stops if none active — idempotent)
  2. ranking_ocid_lookup_pipeline
  3. character_basic_pipeline (mode=once)
  4. item_equipment_pipeline (mode=infinite)

Sensors between triggers use the factory:
  - make_wait_loop_stopped_sensor  : gates step 1
  - make_wait_phase_terminal_sensor: gates steps 2-3
  - custom iteration-started check  : gates step 4

Refs: docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md
"""
from datetime import datetime

from airflow import DAG


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="morning_chain_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # 03:00 KST
    catchup=False,
    tags=["pipeline", "chain", "morning"],
) as dag:
    pass  # tasks added in subsequent tasks
```

- [ ] **Step 2: Re-run the test, expect pass**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 5 passed.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add docker/airflow/dags/morning_chain_pipeline.py \
        docker/airflow/dags/tests/test_morning_chain_pipeline.py
git commit -m "feat(airflow): morning_chain_pipeline skeleton with 03:00 KST schedule"
```

---

## Task 3: Health check + stop_loop trigger + factory sensors for steps 1-2

**Files:**
- Modify: `docker/airflow/dags/morning_chain_pipeline.py`
- Modify: `docker/airflow/dags/tests/test_morning_chain_pipeline.py`

- [ ] **Step 1: Add failing tests for new tasks**

Append to `test_morning_chain_pipeline.py`:

```python
def test_has_check_ext_api_health(dag):
    assert "check_ext_api_health" in {t.task_id for t in dag.tasks}


def test_has_trigger_stop_loop(dag):
    assert "trigger_stop_loop" in {t.task_id for t in dag.tasks}


def test_trigger_stop_loop_targets_correct_dag_with_phase_conf(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_stop_loop")
    assert t.trigger_dag_id == "stop_loop_pipeline"
    assert t.conf == {"phase": "ITEM_EQUIPMENT"}


def test_has_wait_loop_stopped_sensor(dag):
    """Factory's loop-stopped sensor: idempotent (True if no loop active)."""
    assert "wait_loop_stopped_item_equipment" in {t.task_id for t in dag.tasks}


def test_has_trigger_ranking_ocid(dag):
    assert "trigger_ranking_ocid" in {t.task_id for t in dag.tasks}


def test_trigger_ranking_ocid_targets_correct_dag(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_ranking_ocid")
    assert t.trigger_dag_id == "ranking_ocid_lookup_pipeline"


def test_has_wait_upstream_terminal_ocid_lookup(dag):
    assert "wait_upstream_terminal_ocid_lookup" in {t.task_id for t in dag.tasks}
```

- [ ] **Step 2: Run tests, expect failure**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 7 of the 12 tests fail (none of the task assertions match).

- [ ] **Step 3: Implement the 5 tasks and 1 sensor**

Replace the body of `morning_chain_pipeline.py`:

```python
"""morning_chain_pipeline — 03:00 KST chain orchestration.

Schedule: 0 18 * * * UTC (03:00 KST year-round, UTC+9 no DST).
Composes existing per-phase DAGs via factory helpers:
  1. stop_loop_pipeline (loop auto-stops if none active — idempotent)
  2. ranking_ocid_lookup_pipeline
  3. character_basic_pipeline (mode=once)
  4. item_equipment_pipeline (mode=infinite)

Sensors between triggers use the factory:
  - make_wait_loop_stopped_sensor  : gates step 1
  - make_wait_phase_terminal_sensor: gates steps 2-3
  - custom iteration-started check  : gates step 4

Refs: docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md
"""
from datetime import datetime

from airflow import DAG
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.providers.http.sensors.http import HttpSensor

from phase_pipeline_factory import (
    make_wait_loop_stopped_sensor,
    make_wait_phase_terminal_sensor,
)


default_args = {
    "owner": "maple-pipeline",
    "retries": 0,
}


with DAG(
    dag_id="morning_chain_pipeline",
    default_args=default_args,
    start_date=datetime(2026, 5, 29),
    schedule="0 18 * * *",  # 03:00 KST
    catchup=False,
    tags=["pipeline", "chain", "morning"],
) as dag:

    check_ext_api_health = HttpSensor(
        task_id="check_ext_api_health",
        http_conn_id="external_api",
        endpoint="actuator/health",
        request_params={},
        response_check=lambda r: r.status_code == 200,
        poke_interval=30,
        timeout=120,
    )

    trigger_stop_loop = TriggerDagRunOperator(
        task_id="trigger_stop_loop",
        trigger_dag_id="stop_loop_pipeline",
        conf={"phase": "ITEM_EQUIPMENT"},
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Factory sensor — idempotent: returns True if no loop is active
    # (sensor task_id auto-generated: wait_loop_stopped_item_equipment).
    wait_loop_stopped = make_wait_loop_stopped_sensor("ITEM_EQUIPMENT")

    trigger_ranking_ocid = TriggerDagRunOperator(
        task_id="trigger_ranking_ocid",
        trigger_dag_id="ranking_ocid_lookup_pipeline",
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Factory sensor — returns True when current.phase progresses past OCID_LOOKUP
    # (auto-generated task_id: wait_upstream_terminal_ocid_lookup).
    wait_ocid_lookup_terminal = make_wait_phase_terminal_sensor("OCID_LOOKUP")

    check_ext_api_health >> trigger_stop_loop
    trigger_stop_loop >> wait_loop_stopped >> trigger_ranking_ocid
    trigger_ranking_ocid >> wait_ocid_lookup_terminal
```

- [ ] **Step 4: Re-run tests, expect pass**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 12 passed.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add docker/airflow/dags/morning_chain_pipeline.py \
        docker/airflow/dags/tests/test_morning_chain_pipeline.py
git commit -m "feat(airflow): morning_chain stop_loop + ranking+ocid with factory sensors"
```

---

## Task 4: Character basic once + item equipment infinite + first-iter sensor

**Files:**
- Modify: `docker/airflow/dags/morning_chain_pipeline.py`
- Modify: `docker/airflow/dags/tests/test_morning_chain_pipeline.py`

- [ ] **Step 1: Add failing tests**

```python
def test_trigger_character_basic_conf_is_once(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_character_basic_once")
    assert t.trigger_dag_id == "character_basic_pipeline"
    assert t.conf == {"mode": "once"}


def test_trigger_item_equipment_conf_is_infinite(dag):
    t = next(t for t in dag.tasks if t.task_id == "trigger_item_equipment_infinite")
    assert t.trigger_dag_id == "item_equipment_pipeline"
    assert t.conf == {"mode": "infinite"}


def test_has_wait_first_iteration_started(dag):
    assert "wait_first_iteration_started" in {t.task_id for t in dag.tasks}


def test_has_wait_upstream_terminal_character_basic(dag):
    assert "wait_upstream_terminal_character_basic" in {t.task_id for t in dag.tasks}
```

- [ ] **Step 2: Run tests, expect failure**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 4 of the 16 tests fail.

- [ ] **Step 3: Add the 4 tasks + 1 custom sensor**

Add to imports at the top of `morning_chain_pipeline.py`:

```python
from airflow.operators.python import PythonOperator
from airflow.sensors.python import PythonSensor
from phase_pipeline_factory import get_external_api_base
```

Append inside the `with DAG(...)` block (after the existing tasks):

```python
    trigger_character_basic_once = TriggerDagRunOperator(
        task_id="trigger_character_basic_once",
        trigger_dag_id="character_basic_pipeline",
        conf={"mode": "once"},
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Factory sensor — returns True when current.phase progresses past CHARACTER_BASIC.
    wait_character_basic_terminal = make_wait_phase_terminal_sensor("CHARACTER_BASIC")

    trigger_item_equipment_infinite = TriggerDagRunOperator(
        task_id="trigger_item_equipment_infinite",
        trigger_dag_id="item_equipment_pipeline",
        conf={"mode": "infinite"},
        reset_dag_run=True,
        wait_for_completion=False,
    )

    # Custom sensor: the infinite loop never reaches "terminal" so the
    # factory's make_wait_phase_terminal_sensor cannot gate it. Instead,
    # poll /run-status for loopSummaries[ITEM_EQUIPMENT].iterationCount >= 1
    # AND status == "RUNNING" (confirms the loop accepted the start signal
    # and at least one iteration has begun).
    def _is_iteration_started(**ctx) -> bool:
        import requests
        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status",
                params={"phase": "ITEM_EQUIPMENT"},
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()
        except Exception:
            return False  # transient → reschedule
        summary = (data.get("loopSummaries") or {}).get("ITEM_EQUIPMENT")
        if not summary:
            return False
        return (
            summary.get("status") == "RUNNING"
            and (summary.get("iterationCount") or 0) >= 1
        )

    wait_first_iteration_started = PythonSensor(
        task_id="wait_first_iteration_started",
        python_callable=_is_iteration_started,
        mode="reschedule",
        poke_interval=30,
        timeout=10 * 60,
    )

    wait_ocid_lookup_terminal >> trigger_character_basic_once
    trigger_character_basic_once >> wait_character_basic_terminal
    wait_character_basic_terminal >> trigger_item_equipment_infinite
    trigger_item_equipment_infinite >> wait_first_iteration_started
```

- [ ] **Step 4: Re-run tests, expect pass**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 16 passed.

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add docker/airflow/dags/morning_chain_pipeline.py \
        docker/airflow/dags/tests/test_morning_chain_pipeline.py
git commit -m "feat(airflow): morning_chain character_basic once + item_equipment infinite + iter sensor"
```

---

## Task 5: Final invariant tests + DagBag parse + spec correction

**Files:**
- Modify: `docker/airflow/dags/tests/test_morning_chain_pipeline.py`
- Modify: `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md`

- [ ] **Step 1: Add the final invariant tests**

```python
def test_exactly_8_tasks(dag):
    """1 health + 4 trigger + 1 loop-stopped + 2 phase-terminal + 1 iter-started = 9.

    Note: factory sensors generate their own task_ids (e.g.
    wait_loop_stopped_item_equipment), so the count includes those.
    """
    task_ids = {t.task_id for t in dag.tasks}
    assert len(task_ids) == 9, f"got {len(task_ids)}: {sorted(task_ids)}"


def test_all_trigger_dagrun_have_reset(dag):
    """TriggerDagRunOperators must reset_dag_run=True to avoid stale-run collisions."""
    for t in dag.tasks:
        if t.task_id.startswith("trigger_"):
            assert getattr(t, "reset_dag_run", False) is True


def test_all_sensors_use_reschedule(dag):
    """mode=reschedule frees worker slot between pokes."""
    for t in dag.tasks:
        if t.task_id.startswith("wait_") or t.task_id == "check_ext_api_health":
            assert getattr(t, "mode", None) == "reschedule"


def test_dependency_chain_linear(dag):
    """Topological order: health → stop_trigger → loop_stopped → ranking_trigger
    → ocid_terminal → char_trigger → char_terminal → item_trigger → iter_started."""
    task_ids = [t.task_id for t in dag.tasks]
    # Health must be first (no upstream).
    roots = [t for t in dag.tasks if not t.upstream_list]
    assert [r.task_id for r in roots] == ["check_ext_api_health"]
    # Iter-started must be last (no downstream).
    leaves = [t for t in dag.tasks if not t.downstream_list]
    assert [l.task_id for l in leaves] == ["wait_first_iteration_started"]
```

Note: the spec's "10 tasks" claim is corrected to 9 below.

- [ ] **Step 2: Run tests, expect pass**

```bash
cd docker/airflow/dags && python3 -m pytest tests/test_morning_chain_pipeline.py -v
```

Expected: 20 passed.

- [ ] **Step 3: Verify file is well-formed via DagBag**

```bash
cd /home/maple/probabilistic-valuation-engine
docker exec maple-airflow-scheduler bash -c \
  "cd /opt/airflow/dags && python3 -c \
   'from airflow.models import DagBag; b = DagBag(dag_folder=\"/opt/airflow/dags\", include_examples=False); print(\"errors:\", b.import_errors); print(\"morning_chain_pipeline in dags:\", \"morning_chain_pipeline\" in b.dags)'"
```

Expected: `errors: {}` and `morning_chain_pipeline in dags: True`.

- [ ] **Step 4: Correct the "10 tasks" claim in the spec**

In `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md` section 4, update the row:

```
| New tasks per master DAG run | 9 | 1 health + 4 trigger + 4 sensor (3 factory + 1 custom) |
```

Update section 3 (Trade-offs) to remove the now-irrelevant `BranchPythonOperator` and `check_loop_active` references — replace any mention with "factory sensors (idempotent)".

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add docker/airflow/dags/tests/test_morning_chain_pipeline.py \
        docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md
git commit -m "test(airflow): morning_chain final invariants + spec task count correction"
```

---

## Task 6: Spec design diagram update

**Files:**
- Modify: `docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md`

- [ ] **Step 1: Update the ASCII architecture diagram in the spec**

Replace the existing diagram in section 2 (Decision) with the simplified one:

```
morning_chain_pipeline (schedule: 0 18 * * * UTC = 03:00 KST)
  │
  ├─ check_ext_api_health (HttpSensor)
  ├─ trigger_stop_loop (TriggerDagRunOperator → stop_loop_pipeline, conf={phase:ITEM_EQUIPMENT})
  ├─ wait_loop_stopped_item_equipment (factory: make_wait_loop_stopped_sensor — idempotent)
  ├─ trigger_ranking_ocid (TriggerDagRunOperator → ranking_ocid_lookup_pipeline)
  ├─ wait_upstream_terminal_ocid_lookup (factory: make_wait_phase_terminal_sensor)
  ├─ trigger_character_basic_once (TriggerDagRunOperator, conf={mode:once})
  ├─ wait_upstream_terminal_character_basic (factory: make_wait_phase_terminal_sensor)
  ├─ trigger_item_equipment_infinite (TriggerDagRunOperator, conf={mode:infinite})
  └─ wait_first_iteration_started (custom: loopSummaries[ITEM_EQUIPMENT].iterationCount >= 1)
```

- [ ] **Step 2: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add docs/superpowers/specs/2026-06-23-3am-pipeline-chain-design.md
git commit -m "docs: morning_chain spec design diagram (simplified)"
```

---

## Self-Review

**1. Spec coverage:**
- Architecture (master DAG + factory sensors) — Task 2-4
- Components (1 new file + 1 test, no Kotlin) — Task 1-5
- Idempotent stop via factory sensor — Task 3 (`make_wait_loop_stopped_sensor`)
- Data flow (HTTP calls, conf, sensors) — Task 3-4
- Error handling (no retries, factory sensor timeouts) — Task 2 (default_args), Task 3-4 (factory handles timeouts)
- Testing (unit + DagBag parse + manual integration noted) — Task 5
- Schedule 0 18 * * * — Task 2

**2. Placeholder scan:** No "TBD", no "implement later", no "similar to Task N". All code blocks complete. Factory functions used: `make_wait_loop_stopped_sensor`, `make_wait_phase_terminal_sensor`, `get_external_api_base` — all exist in `phase_pipeline_factory.py` (verified by `grep`).

**3. Type consistency:** `get_external_api_base` defined in factory, imported in Task 4. `_is_iteration_started` defined and used in same task. No cross-task type drift.

**4. Spec gap:** Tasks 5-6 close the "10 vs 9 tasks" and diagram discrepancies. Idempotency is now explained correctly (factory sensor returns True if no loop active — verified by reading factory source).

**5. Grill findings applied:**
- Dropped `check_loop_active_for_item_equipment` custom helper (wrong field, `loopId` is in `loopSummaries`, not top-level).
- Dropped `BranchPythonOperator` + `skip_stop_loop_sequence` + `stop_loop_sequence` sentinels.
- Replaced `make_is_phase_terminal` (from `per_phase_tasks`, broken for cross-DAG) with `make_wait_phase_terminal_sensor` (from `phase_pipeline_factory`, no xcom dependency).
- Used `make_wait_loop_stopped_sensor` for the stop step (factory's idempotent version).

Plan complete and saved to `docs/superpowers/plans/2026-06-23-3am-pipeline-chain.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
