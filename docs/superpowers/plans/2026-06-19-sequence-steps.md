# Airflow Sequence Steps DAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `steps` field to `dag_run.conf` of `daily_collection_pipeline` so operators can declare an ordered sequence of phase triggers/loops. Each step waits for the previous step's phase to reach terminal state, except loop steps which are fire-and-forget. Wire the `pipeline-test` skill to accept `steps:PHASE,PHASE_LOOP,...` arg.

**Architecture (revised after grill-me):** Single PythonOperator (`run_steps`) walks the `steps` array at runtime. Each iteration: POST trigger/loop, then for trigger steps only, wait for terminal state via a `wait_for_terminal(phase)` helper that polls `/api/internal/run-status`. Loop steps exit the loop immediately (fire-and-forget; DAG advances to `trigger_cleanup_pipeline`). Replaces the originally proposed TaskGroup chain — that approach couldn't dynamically materialize at parse time.

**Tech Stack:** Python 3.12, Airflow 2.10.5, Bash, Markdown skill docs.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `docker/airflow/dags/per_phase_tasks.py` | Add `parse_steps(conf)` validator + `wait_for_phase_terminal(phase, run_id)` polling helper. |
| `docker/airflow/dags/daily_collection_pipeline.py` | Add `run_steps(**ctx)` single PythonOperator; update `branch_on_scope` to route `steps` field to it; wire `run_steps >> trigger_cleanup_pipeline`. |
| `docker/airflow/dags/tests/test_sequence_steps.py` | Unit tests for `parse_steps` + `wait_for_phase_terminal`. |
| `.claude/skills/pipeline-test/SKILL.md` | Document `steps` field and `steps:PHASE,...` skill arg. |

---

## Task 1: `parse_steps` validator + tests

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`
- Create: `docker/airflow/dags/tests/test_sequence_steps.py`

- [ ] **Step 1.1: Write the failing tests**

Create `docker/airflow/dags/tests/test_sequence_steps.py`:

```python
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
```

- [ ] **Step 1.2: Run the tests to verify they fail**

```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python3 -m pytest tests/test_sequence_steps.py -v"
```

Expected: `ImportError` — `parse_steps` does not exist yet.

- [ ] **Step 1.3: Implement `parse_steps`**

Add to `docker/airflow/dags/per_phase_tasks.py` immediately after `parse_scope`:

```python
# Phase sets for sequence-steps validation (spec §3.2)
_TRIGGERABLE_PHASES = {"RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"}
_LOOPABLE_PHASES = {"CHARACTER_BASIC", "ITEM_EQUIPMENT"}
_STEP_ACTIONS = {"trigger", "loop"}


def parse_steps(conf: dict) -> list:
    """Validate dag_run.conf['steps']. Returns list of step dicts.

    Rules (per spec §3.2):
      - 'steps' missing → return [] (caller falls back to FULL_DAILY / scope path).
      - 'steps' and 'scope' both set → AirflowException (mutually exclusive).
      - Each step is {action, phase}. action ∈ {trigger, loop}; phase ∈ _TRIGGERABLE_PHASES.
      - action=loop requires phase ∈ _LOOPABLE_PHASES (else fail-fast).
      - Empty list → AirflowException.
      - Non-list, non-dict → AirflowException.
    """
    if "scope" in conf and "steps" in conf:
        raise AirflowException(
            "dag_run.conf 'scope' and 'steps' are mutually exclusive. "
            "Use one or the other."
        )
    if "steps" not in conf:
        return []

    steps = conf["steps"]
    if not isinstance(steps, list):
        raise AirflowException(
            f"'steps' must be a list, got {type(steps).__name__}"
        )
    if not steps:
        raise AirflowException("'steps' is empty — omit the field for FULL_DAILY")

    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            raise AirflowException(
                f"steps[{i}] must be a dict, got {type(step).__name__}"
            )
        action = step.get("action")
        phase = step.get("phase")
        if action not in _STEP_ACTIONS:
            raise AirflowException(
                f"steps[{i}].action='{action}' invalid. "
                f"Allowed: {sorted(_STEP_ACTIONS)}"
            )
        if phase not in _TRIGGERABLE_PHASES:
            raise AirflowException(
                f"steps[{i}].phase='{phase}' invalid. "
                f"Allowed: {sorted(_TRIGGERABLE_PHASES)}"
            )
        if action == "loop" and phase not in _LOOPABLE_PHASES:
            raise AirflowException(
                f"steps[{i}]: loop not allowed on {phase}. "
                f"Loopable: {sorted(_LOOPABLE_PHASES)}"
            )
    return list(steps)
```

- [ ] **Step 1.4: Run the tests to verify they pass**

```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python3 -m pytest tests/test_sequence_steps.py -v"
```

Expected: 12 tests pass.

- [ ] **Step 1.5: Commit**

```bash
git add docker/airflow/dags/per_phase_tasks.py docker/airflow/dags/tests/test_sequence_steps.py
git commit -m "feat(airflow): add parse_steps validator with mutual-exclusion check"
```

---

## Task 2: `wait_for_phase_terminal` polling helper + tests

**Files:**
- Modify: `docker/airflow/dags/per_phase_tasks.py`
- Modify: `docker/airflow/dags/tests/test_sequence_steps.py`

- [ ] **Step 2.1: Write the failing tests for `wait_for_phase_terminal`**

Append to `docker/airflow/dags/tests/test_sequence_steps.py`:

```python
import time
from unittest.mock import MagicMock, patch
import requests
from per_phase_tasks import wait_for_phase_terminal


def test_wait_for_phase_terminal_returns_on_terminal_true():
    """Polls once, finds terminal=True, returns."""
    with patch("per_phase_tasks.requests.get") as mock_get:
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
    with patch("per_phase_tasks.requests.get") as mock_get:
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
    with patch("per_phase_tasks.requests.get") as mock_get:
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
    with patch("per_phase_tasks.requests.get") as mock_get:
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
```

- [ ] **Step 2.2: Run the tests to verify they fail**

```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python3 -m pytest tests/test_sequence_steps.py -v"
```

Expected: ImportError on `wait_for_phase_terminal`.

- [ ] **Step 2.3: Implement `wait_for_phase_terminal`**

Add to `docker/airflow/dags/per_phase_tasks.py` after `parse_steps`:

```python
def wait_for_phase_terminal(
    phase: str,
    run_id: str,
    timeout_seconds: int = 60 * 60 * 4,  # 4h per spec §4.1
    poll_interval: int = 30,
) -> None:
    """Poll /run-status until the specified phase's runId reaches terminal.

    The runId match uses the run-group PREFIX (date-time portion), because
    each phase of a daily run gets its own runId with a different nano-time
    suffix. The trigger response carries the first phase's runId; we derive
    the prefix once and match any phase whose runId starts with it.

    Args:
      phase: pipeline phase whose slot/lastCompleted we check.
      run_id: any runId from the run group (typically from trigger response).
      timeout_seconds: max time to wait before raising TimeoutError.
      poll_interval: seconds between polls.

    Raises:
      RuntimeError: phase reached FAILED with errorMessage.
      TimeoutError: phase did not reach terminal within timeout_seconds.
      requests.RequestException: network error not retried (caller decides).
    """
    run_group_prefix = "-".join(run_id.split("-")[:2]) + "-"
    base = get_external_api_base()
    deadline = time.monotonic() + timeout_seconds

    def _belongs(phase_status):
        if not phase_status:
            return False
        rid = phase_status.get("runId") or ""
        return rid.startswith(run_group_prefix)

    while True:
        resp = requests.get(f"{base}/api/internal/run-status", timeout=10)
        resp.raise_for_status()
        data = resp.json()

        # Check slots first (active), then lastCompletedByPhase (cleared).
        phase_status = (
            (data.get("slots") or {}).get(phase)
            or (data.get("lastCompletedByPhase") or {}).get(phase)
        )

        if not _belongs(phase_status):
            # Phase hasn't acquired our runId yet (still on a prior run).
            if time.monotonic() >= deadline:
                raise TimeoutError(
                    f"Phase {phase} did not acquire runId {run_id} "
                    f"(prefix {run_group_prefix}) within {timeout_seconds}s"
                )
            time.sleep(poll_interval)
            continue

        if phase_status.get("phase") == "FAILED":
            raise RuntimeError(
                f"Run {run_group_prefix}* phase {phase} failed: "
                f"{phase_status.get('errorMessage', 'unknown')}"
            )

        if phase_status.get("terminal"):
            return

        if time.monotonic() >= deadline:
            raise TimeoutError(
                f"Phase {phase} did not reach terminal within "
                f"{timeout_seconds}s (run group {run_group_prefix})"
            )
        time.sleep(poll_interval)
```

- [ ] **Step 2.4: Run the tests to verify they pass**

```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python3 -m pytest tests/test_sequence_steps.py -v"
```

Expected: 16 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add docker/airflow/dags/per_phase_tasks.py docker/airflow/dags/tests/test_sequence_steps.py
git commit -m "feat(airflow): add wait_for_phase_terminal helper with run-group prefix"
```

---

## Task 3: `run_steps` task in the DAG

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py`

- [ ] **Step 3.1: Add the `run_steps` task callable**

Add to `docker/airflow/dags/daily_collection_pipeline.py` (location: just below `_is_run_terminal`, before `wait_for_item_equipment_cycle`):

```python
def run_steps(**ctx):
    """Walk dag_run.conf['steps'] sequentially.

    For each step:
      - action=trigger: POST /trigger/phase/{phase}, wait for that phase
        to reach terminal via wait_for_phase_terminal.
      - action=loop: POST /loop/phase/{phase}, exit immediately
        (fire-and-forget; DAG advances to trigger_cleanup_pipeline).

    Raises AirflowException on:
      - parse_steps validation failure (invalid phase, mutually exclusive
        scope+steps, etc.)
      - 400 INVALID_PHASE from ext-api trigger/loop
      - 5xx trigger errors
      - RuntimeError from wait_for_phase_terminal on FAILED phase
      - TimeoutError from wait_for_phase_terminal on 4h timeout

    The whole task has a 12h execution_timeout (3 trigger steps × 4h worst
    case). Most runs complete well within this — the timeout is a safety net.
    """
    from per_phase_tasks import (
        parse_steps,
        wait_for_phase_terminal,
        get_external_api_base,
    )

    conf = ctx["dag_run"].conf or {}
    steps = parse_steps(conf)  # raises AirflowException on invalid
    base = get_external_api_base()

    for step in steps:
        action = step["action"]
        phase = step["phase"]

        if action == "loop":
            # Fire-and-forget. POST + return. DAG advances.
            try:
                resp = requests.post(
                    f"{base}/api/internal/loop/phase/{phase}", timeout=30
                )
            except requests.RequestException as exc:
                raise AirflowException(
                    f"Loop start {phase} failed: {exc}"
                ) from exc
            if resp.status_code == 202:
                log.info("[run_steps] loop started phase=%s", phase)
            elif resp.status_code == 409:
                log.info("[run_steps] loop already running phase=%s", phase)
            elif resp.status_code == 400:
                raise AirflowException(
                    f"Loop start {phase} rejected (INVALID_PHASE): "
                    f"{resp.text[:500]}"
                )
            else:
                raise AirflowException(
                    f"Loop start {phase} failed: HTTP {resp.status_code} "
                    f"{resp.reason}: {resp.text[:500]}"
                )
            return  # loop is fire-and-forget; DAG advances

        # action == "trigger"
        try:
            resp = requests.post(
                f"{base}/api/internal/trigger/phase/{phase}", timeout=30
            )
        except requests.RequestException as exc:
            raise AirflowException(
                f"Trigger {phase} failed: {exc}"
            ) from exc

        if resp.status_code in (200, 202):
            body = resp.json()
        elif resp.status_code == 409:
            body = {"runId": None, "status": "ALREADY_ACTIVE"}
        elif resp.status_code == 400:
            raise AirflowException(
                f"Trigger {phase} rejected (INVALID_PHASE): "
                f"{resp.text[:500]}"
            )
        else:
            raise AirflowException(
                f"Trigger {phase} failed: HTTP {resp.status_code} "
                f"{resp.reason}: {resp.text[:500]}"
            )

        run_id = body.get("runId")
        if not run_id:
            log.warning(
                "[run_steps] trigger %s returned no runId (status=%s); skipping wait",
                phase, body.get("status"),
            )
            continue

        log.info("[run_steps] waiting for %s runId=%s", phase, run_id)
        wait_for_phase_terminal(phase, run_id)
        log.info("[run_steps] %s reached terminal", phase)
```

Also add to the existing import block at the top of `daily_collection_pipeline.py` (after `from per_phase_tasks import (...)`):

```python
from per_phase_tasks import parse_steps, wait_for_phase_terminal
```

Make sure the `log = ...` reference exists. If the existing file uses a different logging style (e.g., top-level `private val log`), reuse it. If not, add at module top:

```python
import logging
log = logging.getLogger(__name__)
```

- [ ] **Step 3.2: Wire `run_steps` task into the DAG**

In the DAG body (inside `with DAG(...) as dag:`), add:

```python
run_steps_task = PythonOperator(
    task_id="run_steps",
    python_callable=run_steps,
    execution_timeout=timedelta(hours=12),
    retries=0,
)
```

- [ ] **Step 3.3: Update `branch_on_scope` to detect `steps` and route**

Replace the `branch_on_scope` lambda body with:

```python
def route_scope(**ctx) -> str:
    """Branch decision (spec §5).

    Returns the task_id to follow after branch_on_scope:
      - 'run_steps_task' when 'steps' field is present (ordered sequence).
      - 'trigger_daily_collection' when scope == ['FULL_DAILY'] (default).
      - 'per_phase_join' for any flat 'scope' list (existing #1292 path).
    """
    conf = ctx["dag_run"].conf or {}
    if "steps" in conf:
        return "run_steps_task"
    scope = parse_scope(conf)
    return "trigger_daily_collection" if scope == ["FULL_DAILY"] else "per_phase_join"


branch_on_scope = BranchPythonOperator(
    task_id="branch_on_scope",
    python_callable=route_scope,
)
```

- [ ] **Step 3.4: Wire `run_steps_task >> trigger_cleanup_pipeline`**

```python
run_steps_task >> trigger_cleanup_pipeline
```

- [ ] **Step 3.5: Verify DAG parses**

```bash
docker exec maple-airflow-scheduler bash -c "cd /opt/airflow/dags && python3 -c 'import daily_collection_pipeline; print(\"DAG OK\")'"
docker exec maple-airflow-scheduler airflow dags list daily_collection_pipeline
```

Expected: DAG listed; no import errors.

- [ ] **Step 3.6: Commit**

```bash
git add docker/airflow/dags/daily_collection_pipeline.py
git commit -m "feat(airflow): add run_steps single-task sequential executor"
```

---

## Task 4: Update `pipeline-test` skill with `steps:...` arg

**Files:**
- Modify: `.claude/skills/pipeline-test/SKILL.md`

- [ ] **Step 4.1: Add the step 5b section**

In `.claude/skills/pipeline-test/SKILL.md`, locate the existing `docker exec ... airflow dags trigger daily_collection_pipeline` line near the end of section 5. Replace it with the conditional block below:

Replace:
```bash
# Trigger DAG manually
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline
```

With:
```bash
# Trigger DAG manually — full pipeline (default):
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline

# Trigger with steps:PHASE[,PHASE,...] skill arg — ordered sequence:
# Skill args: RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC, ITEM_EQUIPMENT,
#             CHARACTER_BASIC_LOOP, ITEM_EQUIPMENT_LOOP.
# Mapping: _LOOP suffix → action=loop; bare phase → action=trigger.
# Default (PIPELINE_STEPS unset) → full pipeline as above.
STEPS="${PIPELINE_STEPS:-}"
if [ -n "$STEPS" ]; then
  DAG_CONF=$(python3 -c "
import json, sys
LOOP_SUFFIX = '_LOOP'
LOOP_PHASES = {'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
TRIGGER_PHASES = {'RANKING_FETCH', 'OCID_LOOKUP', 'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
def to_step(p):
    p = p.strip()
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        if base not in LOOP_PHASES:
            sys.stderr.write(f'ERROR: loop not allowed on {base}. Loopable: {sorted(LOOP_PHASES)}\n')
            sys.exit(2)
        return {'action':'loop','phase':base}
    if p not in TRIGGER_PHASES:
        sys.stderr.write(f'ERROR: unknown phase {p}. Allowed: {sorted(TRIGGER_PHASES | {p+LOOP_SUFFIX for p in LOOP_PHASES})}\n')
        sys.exit(2)
    return {'action':'trigger','phase':p}
steps = [s for s in (to_step(p) for p in sys.argv[1].split(',')) if s]
print(json.dumps({'steps': steps}))
" "$STEPS")
  docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline -c "$DAG_CONF"
fi
```

Then add a new subsection immediately after:

```markdown
#### 5b. Skill arg `steps:PHASE[,PHASE,...]`

Accept a comma-separated phase list at skill entry. The skill forwards it as `dag_run.conf['steps']` JSON to the DAG. Default (no arg): run the full daily pipeline as today.

Mapping:

| Skill arg | dag_run.conf step |
|-----------|-------------------|
| `RANKING_FETCH` | `{"action":"trigger","phase":"RANKING_FETCH"}` |
| `OCID_LOOKUP` | `{"action":"trigger","phase":"OCID_LOOKUP"}` |
| `CHARACTER_BASIC` | `{"action":"trigger","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT` | `{"action":"trigger","phase":"ITEM_EQUIPMENT"}` |
| `CHARACTER_BASIC_LOOP` | `{"action":"loop","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT_LOOP` | `{"action":"loop","phase":"ITEM_EQUIPMENT"}` |

Each step runs sequentially: trigger steps wait for terminal state, loop steps are fire-and-forget (DAG advances to cleanup_pipeline after the loop step). The skill fails fast (exit 2) before triggering Airflow if an invalid phase or `OCID_LOOKUP_LOOP` style is supplied.
```

- [ ] **Step 4.2: Verify the bash parser locally**

```bash
STEPS="RANKING_FETCH,OCID_LOOKUP,CHARACTER_BASIC,ITEM_EQUIPMENT_LOOP"
python3 -c "
import json, sys
LOOP_SUFFIX = '_LOOP'
LOOP_PHASES = {'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
TRIGGER_PHASES = {'RANKING_FETCH', 'OCID_LOOKUP', 'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
def to_step(p):
    p = p.strip()
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        if base not in LOOP_PHASES:
            sys.stderr.write(f'ERROR: loop not allowed on {base}\n')
            sys.exit(2)
        return {'action':'loop','phase':base}
    if p not in TRIGGER_PHASES:
        sys.stderr.write(f'ERROR: unknown phase {p}\n')
        sys.exit(2)
    return {'action':'trigger','phase':p}
steps = [s for s in (to_step(p) for p in sys.argv[1].split(',')) if s]
print(json.dumps({'steps': steps}))
" "$STEPS"
```

Expected:
```json
{"steps": [{"action": "trigger", "phase": "RANKING_FETCH"}, {"action": "trigger", "phase": "OCID_LOOKUP"}, {"action": "trigger", "phase": "CHARACTER_BASIC"}, {"action": "loop", "phase": "ITEM_EQUIPMENT"}]}
```

Failure path:

```bash
STEPS="OCID_LOOKUP_LOOP"
python3 -c "
import json, sys
LOOP_SUFFIX = '_LOOP'
LOOP_PHASES = {'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
def to_step(p):
    p = p.strip()
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        if base not in LOOP_PHASES:
            sys.stderr.write(f'ERROR: loop not allowed on {base}\n')
            sys.exit(2)
        return {'action':'loop','phase':base}
steps = [s for s in (to_step(p) for p in sys.argv[1].split(',')) if s]
print(json.dumps({'steps': steps}))
" "$STEPS"; echo "exit=$?"
```

Expected: `ERROR: loop not allowed on OCID_LOOKUP` and `exit=2`.

- [ ] **Step 4.3: Commit**

```bash
git add .claude/skills/pipeline-test/SKILL.md
git commit -m "feat(skill): accept steps:PHASE,... arg, forward to dag_run.conf"
```

---

## Task 5: Live DAG verification

**Files:** none (smoke test only)

- [ ] **Step 5.1: Trigger back-compat path (existing flat scope)**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope":["CHARACTER_BASIC"]}'
```

Verify the run goes to `per_phase_join` (existing #1292 path). Check `airflow tasks states-for-dag-run <run_id> | grep branch_on_scope`.

- [ ] **Step 5.2: Trigger new steps path**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"steps":[{"action":"trigger","phase":"RANKING_FETCH"}]}'
```

Verify the run routes to `run_steps_task`. Check `airflow tasks states-for-dag-run <run_id> | grep -E "branch_on_scope|run_steps"`.

- [ ] **Step 5.3: Trigger mutually-exclusive error case**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope":["CHARACTER_BASIC"],"steps":[{"action":"trigger","phase":"OCID_LOOKUP"}]}'
```

Verify `branch_on_scope` task fails immediately (state=failed) with AirflowException about mutual exclusion.

- [ ] **Step 5.4: Trigger invalid loop phase error case**

```bash
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"steps":[{"action":"loop","phase":"RANKING_FETCH"}]}'
```

Verify `branch_on_scope` task fails with "loop not allowed on RANKING_FETCH".

- [ ] **Step 5.5: Skill arg → DAG trigger round-trip**

```bash
PIPELINE_STEPS="RANKING_FETCH,ITEM_EQUIPMENT_LOOP" /bin/bash -c '
STEPS="${PIPELINE_STEPS:-}"
DAG_CONF=$(python3 -c "
import json, sys
LOOP_SUFFIX = \"_LOOP\"
LOOP_PHASES = {\"CHARACTER_BASIC\", \"ITEM_EQUIPMENT\"}
TRIGGER_PHASES = {\"RANKING_FETCH\", \"OCID_LOOKUP\", \"CHARACTER_BASIC\", \"ITEM_EQUIPMENT\"}
def to_step(p):
    p = p.strip()
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        if base not in LOOP_PHASES:
            sys.stderr.write(f\"ERROR: loop not allowed on {base}\n\")
            sys.exit(2)
        return {\"action\":\"loop\",\"phase\":base}
    if p not in TRIGGER_PHASES:
        sys.stderr.write(f\"ERROR: unknown phase {p}\n\")
        sys.exit(2)
    return {\"action\":\"trigger\",\"phase\":p}
steps = [s for s in (to_step(p) for p in sys.argv[1].split(\",\")) if s]
print(json.dumps({\"steps\": steps}))
" "$STEPS")
echo "DAG_CONF=$DAG_CONF"
'
```

Expected: prints the JSON conf. (Don't actually trigger — the loop step would need ext-api to be running cleanly; the smoke check is that the bash parses correctly.)

---

## Self-Review

**Spec coverage:**
- §1.1 (skill args) — Task 4 ✓
- §2 (DAG topology) — Task 3 (single task replaces TaskGroup chain) ✓
- §3.1 (back-compat scope) — Task 3.3 preserves existing branch ✓
- §3.2 (steps validation) — Task 1 ✓
- §4.1 (trigger step semantics) — Task 3.1 (trigger + wait_for_phase_terminal) ✓
- §4.2 (loop step semantics) — Task 3.1 (loop returns immediately) ✓
- §4.3 (sequential dependency) — Task 3.1 (Python for-loop with wait between) ✓
- §5 (branch routing) — Task 3.3 ✓
- §6 (error handling) — Task 1 (parser), Task 3 (HTTP errors → AirflowException, FAILED → RuntimeError) ✓
- §7 (verification) — Task 1 (unit tests), Task 2 (helper tests), Task 5 (live) ✓
- §8 (critical files) — Tasks 1, 2, 3, 4 cover all 4 files ✓

**Placeholder scan:** No TBD/TODO. All bash commands and Python code blocks are complete.

**Type consistency:** `parse_steps` defined Task 1, used Task 3. `wait_for_phase_terminal` defined Task 2, used Task 3. `run_steps` defined Task 3, wired to DAG. All consistent.

**Architectural shift acknowledged:** Plan now uses a single `PythonOperator` with internal sequential execution instead of a chain of `TaskGroup`s. This was selected during grill-me because Airflow's DAG graph is static at parse time and cannot materialize ordered steps from runtime `dag_run.conf`. Trade-off: per-step retry and per-step sensor visibility are lost (one task = one slot in the UI), but sequential dependency with terminal-state waits is preserved.