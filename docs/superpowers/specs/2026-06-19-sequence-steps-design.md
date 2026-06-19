# Airflow Sequence Steps DAG — Design

- Date: 2026-06-19
- Status: Draft (pending user review)
- Blocked-by: #1292 (merged; introduces `branch_on_scope`)
- Shape: A (extend `per_phase_tasks.py` + `daily_collection_pipeline.py` + `pipeline-test` skill)

---

## 1.1. Skill Entry-Point Parameterization

The `pipeline-test` skill (`.claude/skills/pipeline-test/SKILL.md`) currently runs the full pipeline with no way to scope which phases to execute. Operators who want to test only a subset (e.g. just `OCID_LOOKUP`, or `CHARACTER_BASIC` followed by `ITEM_EQUIPMENT_LOOP`) have to invoke `airflow dags trigger` directly with a hand-written `dag_run.conf`.

**Goal:** accept a `--steps` (or positional) argument at skill entry, parse it into the new `steps` JSON format, and forward it to `airflow dags trigger daily_collection_pipeline -c '<conf>'`.

**Skill arg syntax:**

```
/pipeline-test steps:RANKING_FETCH,OCID_LOOKUP,ITEM_EQUIPMENT_LOOP
/pipeline-test steps:CHARACTER_BASIC_LOOP
```

- Comma-separated phase names. No action keyword — `LOOP` suffix on the phase name (`CHARACTER_BASIC_LOOP`, `ITEM_EQUIPMENT_LOOP`) maps to `action=loop`; everything else maps to `action=trigger`.
- Default (no arg): run the full daily pipeline as today (no `dag_run.conf`, equivalent to FULL_DAILY).

**Mapping table** (skill arg → dag_run.conf):

| Skill arg | dag_run.conf step |
|-----------|-------------------|
| `RANKING_FETCH` | `{"action":"trigger","phase":"RANKING_FETCH"}` |
| `OCID_LOOKUP` | `{"action":"trigger","phase":"OCID_LOOKUP"}` |
| `CHARACTER_BASIC` | `{"action":"trigger","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT` | `{"action":"trigger","phase":"ITEM_EQUIPMENT"}` |
| `CHARACTER_BASIC_LOOP` | `{"action":"loop","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT_LOOP` | `{"action":"loop","phase":"ITEM_EQUIPMENT"}` |

**Skill workflow changes** (skill step 5, "Trigger DAG manually"):

```
# Existing (unchanged — full pipeline default):
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline

# New (skill args forwarded):
STEPS="RANKING_FETCH,OCID_LOOKUP,CHARACTER_BASIC,ITEM_EQUIPMENT_LOOP"
DAG_CONF=$(python3 -c "
import json, sys
phases = sys.argv[1].split(',')
LOOP_SUFFIX = '_LOOP'
LOOP_PHASES = {'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
def to_step(p):
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        return {'action':'loop','phase':base} if base in LOOP_PHASES else None
    return {'action':'trigger','phase':p}
steps = [s for s in (to_step(p) for p in phases) if s]
print(json.dumps({'steps': steps}))
" "$STEPS")
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline -c "$DAG_CONF"
```

If a phase name has the `_LOOP` suffix but the base phase isn't in `LOOP_PHASES` (e.g. `OCID_LOOKUP_LOOP`), the skill exits with a clear error before triggering Airflow — fail-fast per the same validation rule as `parse_steps` in the DAG.

---

## 1. Goal

Extend the Airflow control plane so operators can declare an **ordered sequence** of phase triggers and loops via `dag_run.conf`. Each step waits for the previous step's phase to reach terminal state, except loop steps which are fire-and-forget. The existing flat `scope` list (issue #1292) stays untouched for back-compat.

**Use cases (none of which the current flat `scope` supports)**
- Run phases in order without manual intervention: `RANKING_FETCH → OCID_LOOKUP → CHARACTER_BASIC → ITEM_EQUIPMENT_LOOP`, each gating on the previous step's completion.
- Inject a continuous loop as the last step of a chain, then run cleanup while the loop runs in the background.
- Re-run a single failed phase without re-running the upstream phases that already succeeded.

**Out of scope**
- Conditional branching between steps (`if phase.X failed then step.Y`).
- Per-step timeout / max-duration.
- Auto-stop on Kafka event or DB count threshold.
- Loop steps that block the DAG (loop steps are always fire-and-forget; manual STOP only).

---

## 2. DAG Topology

`branch_on_scope` gains a third route:

```
check_external_api
  └── branch_on_scope (BranchPythonOperator)
        ├── trigger_daily_collection → wait_for_completion → wait_ie_cycle → trigger_cleanup   (FULL_DAILY, unchanged)
        ├── per_phase_join (EmptyOperator)                                                        (scope list, existing #1292 path)
        └── sequence_steps (EmptyOperator)                                                         (NEW: ordered steps path)
              └── step_<i>_<action>_<phase> (TaskGroup) × N
                    ├── trigger_task (PythonOperator)
                    └── wait_terminal_sensor (PythonSensor, mode=reschedule)  ← omitted for action=loop
        (sequence_steps chain) >> trigger_cleanup_pipeline
```

The `sequence_steps` EmptyOperator acts as a join point so existing downstream operators (`trigger_cleanup_pipeline`) keep the same wiring regardless of which path the branch picked. After the last step's TaskGroup completes, control flows to `trigger_cleanup_pipeline` via `>>`.

---

## 3. Configuration

### 3.1 Back-compat: existing `scope`

Unchanged. See `docs/superpowers/specs/2026-06-18-issue-1292-per-phase-dag-design.md` §2.3.

```json
{ "scope": ["CHARACTER_BASIC", "ITEM_EQUIPMENT_LOOP"] }
```

### 3.2 New: `steps`

Ordered list. Each item is `{ "action": "trigger"|"loop", "phase": "<PHASE>" }`. `loop` is only valid for `CHARACTER_BASIC` and `ITEM_EQUIPMENT` (matches `PhaseLoopController.loopablePhases`).

```json
{
  "steps": [
    { "action": "trigger", "phase": "RANKING_FETCH" },
    { "action": "trigger", "phase": "OCID_LOOKUP" },
    { "action": "trigger", "phase": "CHARACTER_BASIC" },
    { "action": "loop", "phase": "ITEM_EQUIPMENT" }
  ]
}
```

**Validation rules** (parser raises `AirflowException` on violation):
- `phase` ∈ `{RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC, ITEM_EQUIPMENT}`.
- `action=loop` requires `phase` ∈ `{CHARACTER_BASIC, ITEM_EQUMENT}` (else 400 INVALID_PHASE from ext-api would fail mid-DAG — fail-fast in parser instead).
- `steps` and `scope` are mutually exclusive — exactly one of the two keys must be set. Neither → `FULL_DAILY`. Both → `AirflowException`.

---

## 4. Step Semantics

### 4.1 `action=trigger`

**Trigger task (PythonOperator):** POST `/api/internal/phase/{phase}`. 200/202 → return `{runId, phase}` for xcom. 409 ALREADY_ACTIVE → idempotent; fetch `/run-status` and return active runId. 400 INVALID_PHASE → `AirflowException`. Other 4xx/5xx → `AirflowException`.

**Wait terminal sensor (PythonSensor):** `_is_phase_terminal(phase)` — same logic as the existing `_is_run_terminal` (run-group prefix match on `lastCompletedByPhase[phase]`), filtered to `phase`. Mode=`reschedule`, timeout=4h (matches existing).

### 4.2 `action=loop`

**Trigger task (PythonOperator):** POST `/api/internal/loop/phase/{phase}`. 200/202 → return loopId. 409 ALREADY_RUNNING → idempotent; fetch `/run-status.loopSummaries[phase]` and return existing loopId. 400 INVALID_PHASE → `AirflowException`.

**No wait sensor.** Loop step's TaskGroup ends as soon as the trigger task returns. The DAG advances to `trigger_cleanup_pipeline` immediately. The loop continues running in ext-api until manually stopped via `POST /stop/loop/phase/{phase}` (or `POST /stop/phase/{phase}` for graceful chunk-boundary halt).

### 4.3 Sequential dependency

Each step's TaskGroup depends on the previous step's TaskGroup. Implementation: materialize as `task_group_list = [step_0, step_1, ..., step_N]` then `task_group_list[0] >> task_group_list[1] >> ... >> task_group_list[N-1] >> trigger_cleanup_pipeline`. The `sequence_steps` EmptyOperator sits between `branch_on_scope` and `task_group_list[0]` so the join point is wired the same way regardless of which path the branch picked.

---

## 5. Branch Routing

```python
def route_scope(**ctx) -> str:
    conf = ctx["dag_run"].conf or {}
    if "steps" in conf:
        return "sequence_steps"
    scope = parse_scope(conf)
    return "trigger_daily_collection" if scope == ["FULL_DAILY"] else "per_phase_join"
```

`sequence_steps` is a single EmptyOperator with `trigger_rule="none_failed_min_one_success"` to tolerate upstream skips from the BranchPythonOperator.

---

## 6. Error Handling

| Failure | Behavior |
|---------|----------|
| `parse_steps` raises `AirflowException` (invalid phase, action mismatch, mutually-exclusive fields) | `branch_on_scope` task fails immediately; DAG marked failed. |
| Trigger returns 400 INVALID_PHASE | Trigger task raises `AirflowException`; step fails; DAG fails. |
| Trigger returns 5xx or network error | Same as 400 — `AirflowException` raised. |
| Wait sensor timeout (4h) | Sensor fails; step fails; DAG fails. |
| Loop step runs but ext-api later crashes | Loop dies with ext-api; cleanup_pipeline still runs (artifact GC unaffected). DAG succeeds. |
| Steps contain duplicate phases | Allowed. Operator may want `RANKING_FETCH → RANKING_FETCH` to re-fetch after config change. No de-dup in parser. |

---

## 7. Verification

### 7.1 Unit tests (Python, `tests/`)

`tests/test_sequence_steps.py`:
- `parse_steps` accepts valid 4-step chain; rejects `OCID_LOOKUP_LOOP`; rejects mutually-exclusive `scope` + `steps`; rejects missing phase.
- `make_sequence_task_group(steps)` returns N TaskGroups; loop steps have no wait sensor; trigger steps do.

### 7.2 Manual DAG runs

```bash
# Sequence: 3 trigger steps + 1 loop (fire-and-forget)
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"steps": [{"action":"trigger","phase":"RANKING_FETCH"},{"action":"trigger","phase":"OCID_LOOKUP"},{"action":"trigger","phase":"CHARACTER_BASIC"},{"action":"loop","phase":"ITEM_EQUIPMENT"}]}'

# Back-compat: existing flat scope still works
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope":["CHARACTER_BASIC","ITEM_EQUIPMENT_LOOP"]}'

# Mutually exclusive rejection
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope":["RANKING_FETCH"],"steps":[{"action":"trigger","phase":"OCID_LOOKUP"}]}'
# Expected: branch_on_scope fails immediately

# Invalid action+phase combo
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"steps":[{"action":"loop","phase":"RANKING_FETCH"}]}'
# Expected: branch_on_scope fails (loop not allowed on RANKING_FETCH)
```

### 7.3 Live verification

After Airflow DAG run completes:
- `airflow tasks states-for-dag-run daily_collection_pipeline <run_id>` shows each `step_<i>_*` task as `success`.
- Loop step's `trigger_task` succeeded; no wait sensor in the graph (verify by checking task list).
- `trigger_cleanup_pipeline` ran after the last step's TaskGroup.

---

## 8. Critical Files

| File | Change |
|------|--------|
| `docker/airflow/dags/per_phase_tasks.py` | Add `parse_steps(conf)`, `make_sequence_task_group(steps)`, `make_is_phase_terminal(phase)`. Reuse existing `_is_run_terminal` style for the per-phase sensor (run-group prefix match). |
| `docker/airflow/dags/daily_collection_pipeline.py` | Update `route_scope` to detect `steps` field; add `sequence_steps` EmptyOperator; materialize TaskGroups at DAG construction; chain via `>>` to `trigger_cleanup_pipeline`. |
| `docker/airflow/dags/tests/test_sequence_steps.py` | New unit tests for `parse_steps` + TaskGroup generation. |
| `.claude/skills/pipeline-test/SKILL.md` | Document `steps` field in step 5 (trigger) and step 10a (verification). Add skill-arg parsing: `--steps PHASE[,PHASE,...]` or `steps:PHASE,PHASE` → `dag_run.conf['steps']` JSON. |

## 9. Reused Symbols

- `parse_scope(conf)` (`per_phase_tasks.py:36`) — unchanged; still handles back-compat path.
- `make_trigger_task(phase)` (`per_phase_tasks.py`) — reused inside `make_sequence_task_group` for each `action=trigger` step.
- `get_external_api_base()` (`per_phase_tasks.py:32`) — reused by new loop-step trigger.
- `_is_run_terminal` (`daily_collection_pipeline.py`) — refactored into a parameterized `make_is_phase_terminal(phase)` factory; existing call site becomes `make_is_phase_terminal(None)` for full-run detection.