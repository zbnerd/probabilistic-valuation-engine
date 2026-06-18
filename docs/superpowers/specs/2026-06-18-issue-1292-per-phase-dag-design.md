# Issue #1292: Airflow Per-Phase DAG — Design

- Date: 2026-06-18
- Branch: `feature/issue-1292-per-phase-dag`
- Status: Draft (pending user review)
- Blocked-by: #1289 (merged), #1290 (merged), #1291 (merged)
- Shape: A (extend `daily_collection_pipeline.py`) — per issue clarification

---

## 1. Goal

Extend the Airflow control plane so operators can trigger / loop / stop a single phase of `module-external-api` from the Airflow UI / CLI without running the full daily pipeline. Operators pass a `scope` list via `dag_run.conf`; the DAG routes to either the existing daily chain or a per-phase fan-out depending on `scope`.

**Use cases**
- Hot-loop `ITEM_EQUIPMENT` to pick up fresh gear data without waiting for the daily 18:00 UTC trigger.
- Stop a runaway `CHARACTER_BASIC_LOOP` / `ITEM_EQUIPMENT_LOOP` without restarting ext-api.
- Run a single phase (e.g. `CHARACTER_BASIC`) ad-hoc after a config change.

---

## 2. Components

### 2.1 `per_phase_tasks.py` (new file)

Pure helpers — no DAG object. Three factories + one parser:

| Symbol | Purpose |
|--------|---------|
| `parse_scope(conf: dict) -> list[str]` | Validates `dag_run.conf['scope']`. Returns `["FULL_DAILY"]` for missing / default. Raises `AirflowException` on invalid scope values. |
| `make_trigger_task(phase: str) -> PythonOperator` | Single-shot phase trigger via `/trigger/phase/{phase}`. 409 → idempotent. |
| `make_loop_task(phase: str) -> PythonOperator` | Start loop via `/loop/phase/{phase}`. 409 → idempotent. 400 INVALID_PHASE → fail. |
| `make_stop_task(phase: str) -> PythonOperator` | Stop via `/stop/phase/{phase}`. 200 NOT_RUNNING → idempotent success. |
| `make_is_phase_terminal(phase: str) -> Callable` | PythonSensor callable — same logic as existing `_is_run_terminal` but phase-filtered. |
| `TRIGGER_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]` | Frozen list. |
| `LOOP_PHASES = ["CHARACTER_BASIC", "ITEM_EQUIPMENT"]` | Matches ext-api `loopablePhases` (verified 2026-06-18 against `PhaseLoopController.kt`). |
| `STOP_PHASES = ["RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT"]` | All 4 phases; single-phase stop endpoint from #1290 halts loops too. |

### 2.2 `daily_collection_pipeline.py` (extend)

Add branch routing + per-phase fan-out:

```
check_external_api
  └── branch_on_scope (BranchPythonOperator)
        ├── trigger_daily_collection → ... → trigger_cleanup   (FULL_DAILY path; unchanged)
        └── per_phase_join (EmptyOperator, trigger_rule=none_failed_min_one_success)
              ├── per_phase_trigger_<PHASE>    (×4) → per_phase_sensor_<PHASE> (×4)
              ├── per_phase_loop_<PHASE>       (×3) → (no sensor; fire-and-forget)
              └── per_phase_stop_<PHASE>       (×4) → (no sensor; fire-and-forget)
```

10 per-phase tasks materialized in graph: 4 trigger (`RANKING_FETCH`, `OCID_LOOKUP`, `CHARACTER_BASIC`, `ITEM_EQUIPMENT`) + 2 loop (`CHARACTER_BASIC_LOOP`, `ITEM_EQUIPMENT_LOOP`; OCID_LOOKUP_LOOP rejected by ext-api despite earlier spec mention) + 4 stop (`RANKING_FETCH_STOP`, `OCID_LOOKUP_STOP`, `CHARACTER_BASIC_STOP`, `ITEM_EQUIPMENT_STOP`). Per-task callable gates execution on `dag_run.conf['scope']`.

### 2.3 Routing rule

```python
def route_scope(**ctx) -> str:
    scope = parse_scope(ctx["dag_run"].conf or {})
    return "trigger_daily_collection" if scope == ["FULL_DAILY"] else "per_phase_join"
```

`trigger_daily_collection` and `per_phase_join` are mutually exclusive downstream of `branch_on_scope`.

---

## 3. Scope config schema

### 3.1 Allowed scope values

```python
ALLOWED_SCOPES = frozenset({
    # bare phase → single-shot trigger
    "RANKING_FETCH", "OCID_LOOKUP", "CHARACTER_BASIC", "ITEM_EQUIPMENT",
    # _LOOP suffix → start loop (only ext-api loopablePhases)
    "CHARACTER_BASIC_LOOP", "ITEM_EQUIPMENT_LOOP",
    # _STOP suffix → graceful stop
    "RANKING_FETCH_STOP", "OCID_LOOKUP_STOP", "CHARACTER_BASIC_STOP", "ITEM_EQUIPMENT_STOP",
})
```

Total 10 valid scope values (4 + 2 + 4).

### 3.2 Examples

| `dag_run.conf` | Behavior |
|----------------|----------|
| `{}` (no conf) | `FULL_DAILY` → existing daily chain |
| `{"scope": "FULL_DAILY"}` | explicit full daily |
| `{"scope": ["ITEM_EQUIPMENT"]}` | trigger ITEM_EQUIPMENT only |
| `{"scope": ["ITEM_EQUIPMENT_LOOP"]}` | start ITEM_EQUIPMENT loop |
| `{"scope": ["ITEM_EQUIPMENT_STOP"]}` | stop ITEM_EQUIPMENT (loop or single) |
| `{"scope": ["RANKING_FETCH", "OCID_LOOKUP"]}` | trigger 2 phases in parallel |
| `{"scope": ["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"]}` | mixed actions in parallel |
| `{"scope": "INVALID"}` | raise `AirflowException` |
| `{"scope": ["RANKING_FETCH_LOOP"]}` | raise `AirflowException` (not in ext-api `loopablePhases`) |
| `{"scope": ["OCID_LOOKUP_LOOP"]}` | raise `AirflowException` (also not in ext-api `loopablePhases` despite spec §2.3 mention) |

---

## 4. Per-phase task factories

### 4.1 `make_trigger_task(phase)`

```python
def make_trigger_task(phase: str) -> PythonOperator:
    def _trigger(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        bare = [s for s in scope if s == phase]                       # gate
        if not bare:
            return None  # Airflow marks as success-skipped
        base = get_external_api_base()
        try:
            resp = requests.post(f"{base}/api/internal/trigger/phase/{phase}", timeout=30)
        except requests.RequestException as exc:
            raise AirflowException(f"Trigger {phase} failed: {exc}") from exc

        if resp.status_code in (200, 202):
            return resp.json()
        if resp.status_code == 409:
            # idempotent: discover active runId
            status_resp = requests.get(f"{base}/api/internal/run-status", timeout=10)
            status_resp.raise_for_status()
            current = status_resp.json().get("current") or {}
            return {"runId": current.get("runId"), "phase": phase, "status": "ALREADY_ACTIVE"}
        raise AirflowException(
            f"Trigger {phase} failed: HTTP {resp.status_code} {resp.text[:500]}"
        )
    return PythonOperator(
        task_id=f"per_phase_trigger_{phase.lower()}",
        python_callable=_trigger,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

### 4.2 `make_loop_task(phase)`

```python
def make_loop_task(phase: str) -> PythonOperator:
    def _loop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_LOOP" not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(f"{base}/api/internal/loop/phase/{phase}", timeout=30)
        except requests.RequestException as exc:
            raise AirflowException(f"Loop start {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return resp.json()
        if resp.status_code == 409:
            return {**resp.json(), "status": "ALREADY_LOOPING"}
        if resp.status_code == 400:
            raise AirflowException(
                f"Loop start {phase} rejected: {resp.json().get('error')}"
            )  # config error — fail task
        raise AirflowException(
            f"Loop start {phase} failed: HTTP {resp.status_code} {resp.text[:500]}"
        )
    return PythonOperator(
        task_id=f"per_phase_loop_{phase.lower()}",
        python_callable=_loop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

### 4.3 `make_stop_task(phase)`

```python
def make_stop_task(phase: str) -> PythonOperator:
    def _stop(**ctx):
        scope = parse_scope(ctx["dag_run"].conf or {})
        if f"{phase}_STOP" not in scope:
            return None
        base = get_external_api_base()
        try:
            resp = requests.post(f"{base}/api/internal/stop/phase/{phase}", timeout=30)
        except requests.RequestException as exc:
            raise AirflowException(f"Stop {phase} failed: {exc}") from exc

        if resp.status_code == 202:
            return {**resp.json(), "status": "STOP_REQUESTED"}
        if resp.status_code == 200:
            return {"phase": phase, "status": "NOT_RUNNING"}
        raise AirflowException(
            f"Stop {phase} failed: HTTP {resp.status_code} {resp.text[:500]}"
        )
    return PythonOperator(
        task_id=f"per_phase_stop_{phase.lower()}",
        python_callable=_stop,
        retries=0,
        execution_timeout=timedelta(seconds=60),
        do_xcom_push=True,
    )
```

### 4.4 `make_is_phase_terminal(phase)`

Reuses existing `_is_run_terminal` logic with phase filter:

```python
def make_is_phase_terminal(phase: str):
    def _poke(**ctx):
        # Gate: if trigger was skipped (scope didn't include bare phase), succeed immediately
        scope = parse_scope(ctx["dag_run"].conf or {})
        if phase not in scope:
            return True
        trigger_resp = ctx["ti"].xcom_pull(task_ids=f"per_phase_trigger_{phase.lower()}")
        if isinstance(trigger_resp, str):
            trigger_resp = json.loads(trigger_resp)
        run_id = trigger_resp.get("runId") if trigger_resp else None
        if not run_id:
            raise RuntimeError(
                f"Sensor for {phase} triggered but trigger xcom returned no runId — config error"
            )
        try:
            resp = requests.get(
                f"{get_external_api_base()}/api/internal/run-status", timeout=10
            )
            resp.raise_for_status()
            data = resp.json()
        except (requests.RequestException, ValueError):
            return False
        current = data.get("current")
        if not current or current.get("runId") != run_id:
            return False
        if current.get("phase") == "FAILED":
            raise RuntimeError(f"Run {run_id} failed: {current.get('errorMessage', 'unknown')}")
        return bool(current.get("terminal", False))
    return _poke
```

### 4.5 DAG wiring patch in `daily_collection_pipeline.py`

```python
from docker.airflow.dags.per_phase_tasks import (
    parse_scope, make_trigger_task, make_loop_task, make_stop_task,
    make_is_phase_terminal, TRIGGER_PHASES, LOOP_PHASES, STOP_PHASES,
)
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator

# After existing chain definitions:

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
        timeout=60*60*4,
    ) for p in TRIGGER_PHASES
]

# Rewire existing root:
# check_external_api >> trigger_daily_collection >> ...   (no change to existing edges)
check_external_api >> branch_on_scope
branch_on_scope >> trigger_daily_collection        # FULL_DAILY path (existing chain)
branch_on_scope >> per_phase_join                  # scope path
per_phase_join >> per_phase_trigger_tasks >> per_phase_trigger_sensors
per_phase_join >> per_phase_loop_tasks
per_phase_join >> per_phase_stop_tasks
```

Total task count in DAG graph: 4 trigger + 4 sensor + 2 loop + 4 stop + 1 branch + 1 join = 16 new task definitions added.

---

## 5. Error handling matrix

| Endpoint | 2xx | 409 | 400 | Other |
|----------|-----|-----|-----|-------|
| `/trigger/phase/...` | xcom push + sensor wait | idempotent: discover active runId | n/a (phase validated upstream) | `AirflowException` |
| `/loop/phase/...` | xcom push loopId | idempotent: push existing loopId | config error: `AirflowException` | `AirflowException` |
| `/stop/phase/...` | xcom push status | n/a | n/a | `AirflowException` |
| Sensor: `GET /run-status` | match runId → terminal check | n/a | n/a | transient: return False (reschedule) |
| Sensor: FAILED phase | raise `RuntimeError` (hard fail DAG) | n/a | n/a | n/a |

Network errors → `AirflowException` (transient: task retries per Airflow defaults; sensor retries infinitely until 4h timeout).

---

## 6. Test plan

### 6.1 Unit tests (`docker/airflow/dags/tests/test_per_phase_tasks.py`)

**Pure-function tests (no Airflow runtime needed):**
1. `parse_scope({})` → `["FULL_DAILY"]`
2. `parse_scope({"scope": "FULL_DAILY"})` → `["FULL_DAILY"]`
3. `parse_scope({"scope": "ITEM_EQUIPMENT"})` → `["ITEM_EQUIPMENT"]`
4. `parse_scope({"scope": ["ITEM_EQUIPMENT_LOOP", "OCID_LOOKUP_STOP"]})` → 2-element list
5. `parse_scope({"scope": ["RANKING_FETCH_LOOP"]})` → raises `AirflowException`
6. `parse_scope({"scope": ["INVALID"]})` → raises `AirflowException`
7. `parse_scope({"scope": "RANKING_FETCH_LOOP"})` → raises `AirflowException`

**DAG loader tests** (using `airflow.models.DagBag`):
8. `daily_collection_pipeline` DAG parses without error after changes
9. `branch_on_scope` task exists; downstream task_ids include `trigger_daily_collection` AND `per_phase_join`
10. All 11 per-phase task definitions exist (4 trigger + 3 loop + 4 stop)
11. `parse_scope` coverage in pytest parametrize

### 6.2 DAG import smoke (CI gate)

```python
def test_dag_imports():
    from airflow.models import DagBag
    dagbag = DagBag(dag_folder="docker/airflow/dags/", include_examples=False)
    assert "daily_collection_pipeline" in dagbag.dags
    assert dagbag.import_errors == {}
```

### 6.3 Manual smoke (out-of-band, uses `pipeline-test` skill)

Verification harness: existing `pipeline-test` skill (end-to-end pipeline runtime test). Run against local stack:

| Scope | Verify |
|-------|--------|
| `["ITEM_EQUIPMENT"]` | ext-api `/run-status` shows ITEM_EQUIPMENT ACTIVE; sensor pokes until terminal |
| `["ITEM_EQUIPMENT_LOOP"]` | ext-api `/run-status.loopSummaries["ITEM_EQUIPMENT"].loopId` set; `iterationCount > 0` after 60s |
| `["ITEM_EQUIPMENT_STOP"]` | existing loop terminates within 30s; `loopSummaries["ITEM_EQUIPMENT"].status == "STOPPED"` |
| absent (FULL_DAILY) | existing daily chain runs unchanged (4 phases + cleanup) |

---

## 7. Acceptance criteria mapping

| AC | Section |
|----|---------|
| `airflow dags trigger per_phase_pipeline -c '{"scope":"ITEM_EQUIPMENT"}'` triggers only ITEM_EQUIPMENT on ext-api | §4.1 + §4.5 branch + gate |
| Same for `ITEM_EQUIPMENT_LOOP` starts loop | §4.2 + §4.5 |
| Same for `ITEM_EQUIPMENT_STOP` stops loop | §4.3 + §4.5 |
| Sensor layer correlates runId / loopId and reaches terminal state | §4.4 xcom push + sensor |
| 409 / NOT_RUNNING surfaces as Airflow task success (idempotent) | §5 error matrix |
| Manual smoke test against local stack for each scope value | §6.3 pipeline-test skill |
| Existing `daily_collection_pipeline` runs unchanged | §4.5 default `FULL_DAILY` routing + unchanged edges |

---

## 8. Out-of-scope

- Cross-phase sequential ordering (multi-action scope runs in parallel)
- Per-phase timeout overrides (default 4h sensor, 60s callable timeouts)
- Loop iteration timeout (same 4h sensor applies; operators restart if stuck)
- Auto-retry on 5xx beyond Airflow defaults
- Loop restart on iteration failure (per #1291 §11, iteration fail stops loop)
- Cross-restart loop state recovery (in-memory only, per #1291 §13)
- OAuth / API-key auth (matches existing pattern via Airflow connection)

---

## 9. Risks

| Risk | Mitigation |
|------|------------|
| Loop + multi-action: 2+ loops at once, contention. | Operators self-coordinate via scope config. Documented in DAG docstring. |
| BranchPythonOperator misroutes when `dag_run.conf` malformed. | `parse_scope` raises `AirflowException` immediately; fail-fast. |
| 17 task definitions materialized even when unused → cluttered graph view. | Per-task gating keeps behavior correct; graph view is documentation cost only. |
| 4h sensor timeout too short for cold-cache OCID_LOOKUP. | Matches existing pattern; operator override via `execution_timeout`. |
| `RANKING_FETCH_LOOP` invalid scope only caught at task-run, not DAG-parse. | Acceptable: validation at runtime before HTTP call; faster fail than DAG rejection. |
| 11 task definitions exceed CLAUDE.md 300-line coordinator limit? | Each per-phase task is small (~30 lines); main DAG file grows by ~60 lines only. |

---

## 10. Files touched

| File | Change type | Lines (est.) |
|------|-------------|--------------|
| `docker/airflow/dags/per_phase_tasks.py` | new | ~250 |
| `docker/airflow/dags/daily_collection_pipeline.py` | extend | +60 |
| `docker/airflow/dags/tests/test_per_phase_tasks.py` | new | ~120 |

Total: ~430 lines.