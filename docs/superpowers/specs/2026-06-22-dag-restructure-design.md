# Airflow DAG Restructure — Phase-Separated Pipelines

- Date: 2026-06-22
- Status: Draft (pending grill-me review)
- Shape: B (replace branch_on_scope with phase-separated DAGs)
- Blocked-by: none (additive; legacy DAG retained as deprecated)

---

## 1. Goal

Replace the single multi-path `daily_collection_pipeline` DAG (FULL_DAILY / scope / run_steps) with **5 small DAGs**, each with a single responsibility. Operators trigger the DAG matching their intent directly from the UI or CLI without parsing scope configs.

**Why:** The current `branch_on_scope` operator dispatches 3 fundamentally different workflows (full daily chain, per-phase parallel fan-out, ordered steps) through one DAG with JSON `scope` parsing at runtime. The DAG graph materializes 17 task definitions even when 14 are skipped. Operators reading the Airflow UI cannot tell what a DAG run will do without inspecting the conf JSON.

**Use cases:**
- Daily cron at 03:00 KST: full chain RANKING → OCID → CHARACTER_BASIC → ITEM_EQUIPMENT (once each).
- Gap-fill run: trigger CHARACTER_BASIC 3× (count=3) to catch up on a backlog.
- Steady-state hot loop: trigger ITEM_EQUIPMENT with mode=infinite; operator stops later via `stop_loop_pipeline`.
- Stop any active loop: `stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'`.

---

## 2. DAG inventory

| DAG id | Schedule | Trigger conf | Purpose |
|--------|----------|--------------|---------|
| `ranking_ocid_lookup_pipeline` | manual | `{}` | Sequential RANKING_FETCH → OCID_LOOKUP. OCID depends on RANKING output (upstream_runId header chain), so always chained. |
| `character_basic_pipeline` | manual | `{mode: "once"\|"count"\|"infinite", count?: int}` | CHARACTER_BASIC phase in chosen mode. |
| `item_equipment_pipeline` | manual | `{mode: "once"\|"count"\|"infinite", count?: int}` | ITEM_EQUIPMENT phase in chosen mode. |
| `daily_full_pipeline` | `0 18 * * *` (UTC = KST 03:00) | `{}` | Chains `ranking_ocid_lookup_pipeline` → `character_basic_pipeline`(mode=once) → `item_equipment_pipeline`(mode=once) → `daily_cleanup_pipeline`. Primary scheduled workflow. |
| `stop_loop_pipeline` | manual | `{phase: "CHARACTER_BASIC"\|"ITEM_EQUIPMENT"}` | Calls `POST /stop/loop/phase/{phase}` and waits for `loopSummaries[phase].status == "STOPPED"`. |

`daily_cleanup_pipeline` (existing, schedule `0 */6 * * *`) unchanged.

`daily_collection_pipeline` (legacy) **retained**, marked `tags=["deprecated"]`, docstring warns removal in next release. Operator migration runbook: `docs/21_Operations/dag-migration.md`.

---

## 3. Architecture

### 3.1 Loop location: ext-api (unchanged)

Per Q1 decision. `POST /loop/phase/{phase}` starts an infinite loop in ext-api's `PhaseLoopController` (in-memory `LoopState`). Airflow DAGs do not reimplement loop logic. Restart of ext-api loses loop state — pre-existing behavior per #1291 §13.

### 3.2 N-count mechanism: Airflow sensor counting chunk-ready events

Per Q2 decision. `character_basic_pipeline` and `item_equipment_pipeline` with `mode=count`:

```
trigger_loop (POST /loop/phase/{phase}) →
  count_sensor (PythonSensor, mode=reschedule, poke Kafka synchronizer.chunk.consumed):
    for i in 1..N:
      wait one item-equipment or character-basic chunk-ready event from synchronizer
    return True
  stop_loop (POST /stop/loop/phase/{phase}) → DAG success
```

Loop state continues running in ext-api during the sensor poke window. `iterationCount` is observable via `GET /api/internal/run-status.loopSummaries[phase].iterationCount`.

Trade-off: DAG occupies an Airflow worker slot during the count window. N-count runs are bounded (operator-specified), so slot occupancy is finite and predictable. No ext-api changes.

### 3.3 Infinite loop kill switch: dedicated `stop_loop_pipeline` DAG

Per Q3 decision. Operator triggers from Airflow UI or CLI:
```bash
docker exec maple-airflow-scheduler airflow dags trigger stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'
```

DAG calls `POST /stop/loop/phase/{phase}` (graceful chunk-boundary halt), waits for `loopSummaries[phase].status == "STOPPED"` (sensor mode=reschedule, timeout 30min), then DAG success. If no loop is active, endpoint returns 200 NOT_LOOPING — DAG succeeds immediately (idempotent).

### 3.4 Backward compatibility: deprecated legacy DAG

Per Q4 decision. `daily_collection_pipeline` retained with `tags=["deprecated","pipeline"]`. UI shows yellow warning in DAG docstring:
> DEPRECATED 2026-06-22. Use `daily_full_pipeline` for scheduled daily chain, or trigger phase DAGs directly for ad-hoc. Removal in next release. See `docs/21_Operations/dag-migration.md`.

`per_phase_tasks.py` (parse_scope, parse_steps, route_scope, run_steps_task) retained — legacy path depends on them. Marked internal `_legacy` in module docstring.

---

## 4. DAG topology (canonical shape)

Each new DAG follows the same template:

```
[health_check_sensor] >> [phase_action_task(s)] >> [terminal_or_count_sensor] >> [cleanup_task?]
```

### 4.1 `ranking_ocid_lookup_pipeline`

```
check_external_api (HttpSensor)
  >> trigger_ranking_fetch (PythonOperator)
  >> wait_ranking_terminal (PythonSensor, mode=reschedule, timeout=4h)
  >> trigger_ocid_lookup (PythonOperator, X-Upstream-Run-Id header)
  >> wait_ocid_terminal (PythonSensor, mode=reschedule, timeout=4h)
```

RANKING_FETCH requires no upstream_runId header (it's the first phase). OCID_LOOKUP requires `X-Upstream-Run-Id` header set to the runId xcom'd from the RANKING_FETCH trigger. InternalApiController rejects without header (`MISSING_UPSTREAM` 400).

### 4.2 `character_basic_pipeline` (and `item_equipment_pipeline`)

```
check_external_api (HttpSensor)
  >> branch_on_mode (BranchPythonOperator)
        ├── mode_once:
        │     trigger_phase (PythonOperator)
        │     >> wait_terminal (PythonSensor, mode=reschedule, timeout=4h)
        ├── mode_count:
        │     trigger_loop (PythonOperator)
        │     >> count_sensor (PythonSensor, mode=reschedule, timeout=N*5min+30min buffer)
        │     >> stop_loop (PythonOperator)
        └── mode_infinite:
              trigger_loop (PythonOperator)  # fire-and-forget; DAG succeeds
```

`branch_on_mode` validates `dag_run.conf`:
- `mode` ∈ `{once, count, infinite}` else `AirflowException`.
- `mode=count` requires integer `count >= 1` else `AirflowException`.
- Other modes ignore `count`.

The two phase DAGs share a single implementation parameterized by `phase` constant in a factory. Same shape, same sensors, same conf validation — only the phase name and the chunk-ready event filter differ.

### 4.3 `daily_full_pipeline`

```
check_external_api (HttpSensor)
  >> TriggerDagRunOperator(trigger_dag_id="ranking_ocid_lookup_pipeline", wait_for_completion=True)
  >> TriggerDagRunOperator(trigger_dag_id="character_basic_pipeline", conf='{"mode":"once"}', wait_for_completion=True)
  >> TriggerDagRunOperator(trigger_dag_id="item_equipment_pipeline", conf='{"mode":"once"}', wait_for_completion=True)
  >> TriggerDagRunOperator(trigger_dag_id="daily_cleanup_pipeline", wait_for_completion=True)
```

`wait_for_completion=True` blocks until the triggered DAG reaches terminal state. Failure of any step aborts the chain.

### 4.4 `stop_loop_pipeline`

```
check_external_api (HttpSensor)
  >> stop_loop (PythonOperator, POST /stop/loop/phase/{phase})
  >> wait_loop_stopped (PythonSensor, polls loopSummaries[phase].status == "STOPPED", timeout=30min)
```

`wait_loop_stopped` returns True immediately if `loopSummaries[phase]` is absent (no loop was running) — idempotent.

---

## 5. Configuration schema

### 5.1 `character_basic_pipeline` / `item_equipment_pipeline` conf

```json
{
  "mode": "once" | "count" | "infinite",
  "count": <integer, required if mode=count, >= 1>
}
```

| conf | Behavior |
|------|----------|
| `{}` or missing | `AirflowException` (must specify mode) |
| `{"mode": "once"}` | trigger phase, wait terminal |
| `{"mode": "count", "count": 3}` | trigger loop, count 3 chunk-ready events, stop loop |
| `{"mode": "infinite"}` | trigger loop, DAG succeeds (operator stops later) |
| `{"mode": "INVALID"}` | `AirflowException` |
| `{"mode": "count"}` (missing count) | `AirflowException` |
| `{"mode": "count", "count": 0}` | `AirflowException` |

### 5.2 `ranking_ocid_lookup_pipeline` conf

```json
{}
```

No parameters. RANKING + OCID chain is deterministic.

### 5.3 `daily_full_pipeline` conf

```json
{}
```

Hardcoded mode=once for both CHARACTER_BASIC and ITEM_EQUIPMENT. Override scheduled behavior via `airflow dags trigger daily_full_pipeline -c '{"override": ...}'` (out of scope for v1; v2 if requested).

### 5.4 `stop_loop_pipeline` conf

```json
{
  "phase": "CHARACTER_BASIC" | "ITEM_EQUIPMENT"
}
```

Only loopable phases. RANKING_FETCH / OCID_LOOKUP cannot loop (ext-api rejects with 400 INVALID_PHASE).

---

## 6. Components

### 6.1 New: `docker/airflow/dags/phase_pipeline_factory.py`

Pure helpers — no DAG object. Reused by `character_basic_pipeline.py` and `item_equipment_pipeline.py`:

| Symbol | Purpose |
|--------|---------|
| `parse_mode(conf: dict) -> tuple[str, int]` | Returns `(mode, count)`. Validates `mode ∈ {once,count,infinite}` and `count` rules. Raises `AirflowException`. |
| `make_trigger_once_task(phase) -> PythonOperator` | POST `/trigger/phase/{phase}` with `X-Upstream-Run-Id` from run-status if needed. 200/202 → xcom. 409 → idempotent. |
| `make_trigger_loop_task(phase) -> PythonOperator` | POST `/loop/phase/{phase}`. 202 → xcom loopId. 409 → idempotent. |
| `make_count_sensor(phase, count) -> PythonSensor` | Polls Kafka `synchronizer.chunk.consumed` for the phase's endpoint, returns True after `count` events. mode=reschedule, timeout=`count*5min+30min` (5min per chunk P99 + 30min buffer). |
| `make_stop_loop_task(phase) -> PythonOperator` | POST `/stop/loop/phase/{phase}`. 202 → STOP_REQUESTED. 200 → NOT_LOOPING (idempotent). |
| `make_wait_loop_stopped_sensor(phase) -> PythonSensor` | Polls `/run-status.loopSummaries[phase].status == "STOPPED"`. Returns True immediately if no loop active. mode=reschedule, timeout=30min. |
| `make_branch_on_mode()` | BranchPythonOperator callable. Reads conf, returns task_id of chosen branch. |
| `make_phase_dag(phase: str, dag_id: str) -> DAG` | Factory: builds the once/count/infinite DAG for a given phase. Used by both phase DAGs. |

### 6.2 New: `docker/airflow/dags/ranking_ocid_lookup_pipeline.py`

DAG with RANKING_FETCH → OCID_LOOKUP sequential tasks. Uses existing helpers from `per_phase_tasks.py` (make_trigger_task, make_is_phase_terminal) since they already implement the right shape; adds `X-Upstream-Run-Id` header plumbing.

### 6.3 New: `docker/airflow/dags/daily_full_pipeline.py`

DAG with 4 TriggerDagRunOperator tasks. No new helpers; reuses existing DAG ids.

### 6.4 New: `docker/airflow/dags/stop_loop_pipeline.py`

DAG with health check + stop_loop + wait_stopped sensor. Uses `phase_pipeline_factory.make_stop_loop_task` and `make_wait_loop_stopped_sensor`.

### 6.5 Modify: `docker/airflow/dags/daily_collection_pipeline.py`

Add `tags=["deprecated"]` (existing tags=["pipeline","daily"]). Update docstring to reference migration runbook. **No code path changes** — legacy FULL_DAILY / scope / run_steps paths continue to work for one release cycle.

### 6.6 Modify: `docker/airflow/dags/per_phase_tasks.py`

Module docstring updated to mark symbols as `_legacy`. `parse_scope`, `parse_steps`, `route_scope`, `make_trigger_task`, `make_loop_task`, `make_stop_task`, `make_is_phase_terminal`, `run_steps_task` all marked with `# Legacy: see daily_full_pipeline` comments. Imports retained.

---

## 7. Data flow

### 7.1 Daily scheduled run (canonical happy path)

```
[03:00 KST cron]
  daily_full_pipeline (schedule)
    >> TriggerDagRunOperator(ranking_ocid_lookup_pipeline)
         >> RANKING_FETCH trigger → wait terminal → OCID_LOOKUP trigger (X-Upstream-Run-Id) → wait terminal
    >> TriggerDagRunOperator(character_basic_pipeline, conf={mode:once})
         >> trigger CHARACTER_BASIC → wait terminal
    >> TriggerDagRunOperator(item_equipment_pipeline, conf={mode:once})
         >> trigger ITEM_EQUIPMENT → wait terminal
    >> TriggerDagRunOperator(daily_cleanup_pipeline)
         >> 3 cleanup tasks (artifact runs / calculator runs / inbox)
```

### 7.2 Gap-fill run (operator ad-hoc)

```
docker exec maple-airflow-scheduler airflow dags trigger item_equipment_pipeline \
  -c '{"mode":"count","count":3}'
```

```
trigger_loop ITEM_EQUIPMENT (loopId=x)
  >> count_sensor: wait for 3 item-equipment chunk-ready events from synchronizer
  >> stop_loop (POST /stop/loop/phase/ITEM_EQUIPMENT)
DAG success after ~3 chunks (≈30-90min depending on chunk rate)
```

### 7.3 Steady-state loop + stop

```
# Start
docker exec maple-airflow-scheduler airflow dags trigger item_equipment_pipeline \
  -c '{"mode":"infinite"}'
# DAG returns success in <1min. Loop continues in ext-api indefinitely.

# ... hours later ...

# Stop
docker exec maple-airflow-scheduler airflow dags trigger stop_loop_pipeline \
  -c '{"phase":"ITEM_EQUIPMENT"}'
# Waits up to 30min for loop to drain current chunk + final stop.
```

---

## 8. Error handling

| Failure | Behavior |
|---------|----------|
| `parse_mode` raises (invalid mode, missing count, count=0) | branch_on_mode task fails; DAG fails before any HTTP call. |
| `mode=count` sensor times out | Sensor fails after `count*5min+30min`; DAG fails. Loop may still be active in ext-api. Operator must call `stop_loop_pipeline` to clean up. |
| `mode=infinite` triggered while another loop active for same phase | ext-api returns 409 ALREADY_LOOPING; DAG succeeds with idempotent note. (Existing PhaseLoopController behavior.) |
| `mode=count` triggered while loop active | Same 409 path; idempotent success. |
| Trigger response 400 INVALID_PHASE | `AirflowException`; step fails; DAG fails. Config error. |
| Trigger response 5xx / network | `AirflowException`; Airflow task retries (default_args.retries=0 for triggers; up to operator override). |
| Sensor times out (terminal wait) | `AirflowException` after 4h; DAG fails. |
| `stop_loop_pipeline` triggered when no loop active | ext-api returns 200 NOT_LOOPING; wait_loop_stopped sensor returns True immediately; DAG success. |
| Legacy `daily_collection_pipeline` still in use | Continues to work unchanged. UI tag=deprecated surfaces warning. |

---

## 9. Test plan

### 9.1 Unit tests (`docker/airflow/dags/tests/test_phase_pipeline_factory.py`)

| Test | Input | Expected |
|------|-------|----------|
| `parse_mode` default | `{}` | `AirflowException` |
| `parse_mode` once | `{"mode":"once"}` | `("once", 0)` |
| `parse_mode` count valid | `{"mode":"count","count":5}` | `("count", 5)` |
| `parse_mode` count missing | `{"mode":"count"}` | `AirflowException` |
| `parse_mode` count zero | `{"mode":"count","count":0}` | `AirflowException` |
| `parse_mode` infinite | `{"mode":"infinite"}` | `("infinite", 0)` |
| `parse_mode` invalid | `{"mode":"foo"}` | `AirflowException` |
| `make_phase_dag` task_ids | (factory call) | All branch + terminal task_ids present |
| `make_count_sensor` timeout | `count=3` | `timeout=15min+30min=45min` |

### 9.2 DAG import smoke (CI gate)

```python
def test_all_dags_import():
    from airflow.models import DagBag
    dagbag = DagBag(dag_folder="docker/airflow/dags/", include_examples=False)
    for dag_id in (
        "ranking_ocid_lookup_pipeline",
        "character_basic_pipeline",
        "item_equipment_pipeline",
        "daily_full_pipeline",
        "stop_loop_pipeline",
        "daily_collection_pipeline",   # legacy; must still parse
        "daily_cleanup_pipeline",
    ):
        assert dag_id in dagbag.dags, f"{dag_id} missing from DagBag"
    assert dagbag.import_errors == {}
```

### 9.3 DAG structure tests

```python
def test_phase_dag_has_three_branches():
    dag = DagBag(...).get_dag("character_basic_pipeline")
    branch_task = dag.get_task("branch_on_mode")
    downstream_ids = set(branch_task.downstream_task_ids)
    # once branch: trigger + sensor
    assert "trigger_once" in downstream_ids
    # count branch: trigger + sensor + stop
    assert "trigger_loop" in downstream_ids and "stop_loop" in downstream_ids
    # infinite branch shares trigger_loop; DAG succeeds there
```

### 9.4 Manual smoke (uses pipeline-test skill)

| DAG | conf | Verify |
|-----|------|--------|
| `ranking_ocid_lookup_pipeline` | `{}` | RANKING + OCID slots transition active → terminal in order. |
| `character_basic_pipeline` | `{"mode":"once"}` | CHARACTER_BASIC slot goes active → terminal. DAG success. |
| `item_equipment_pipeline` | `{"mode":"count","count":3}` | Loop starts; `iterationCount` reaches 3; loop stops; DAG success. |
| `item_equipment_pipeline` | `{"mode":"infinite"}` | Loop starts; DAG success in <1min. Then `stop_loop_pipeline -c '{"phase":"ITEM_EQUIPMENT"}'` → loop terminates. |
| `daily_full_pipeline` | `{}` (or scheduled) | All 3 phase DAGs run in order; cleanup runs after. |
| `stop_loop_pipeline` | `{"phase":"ITEM_EQUIPMENT"}` with no active loop | DAG success in <30s. |
| Legacy `daily_collection_pipeline` | `{}` | Existing FULL_DAILY path still works. |

### 9.5 Backward-compat verification

Trigger `daily_collection_pipeline -c '{"scope":["ITEM_EQUIPMENT_LOOP"]}'` and confirm the legacy scope path still routes to the per_phase_loop task. Required for one release cycle of operator migration.

---

## 10. Acceptance criteria

| AC | Section |
|----|---------|
| Each new DAG has a single responsibility visible from its DAG id and tags | §2, §4 |
| No DAG requires JSON parsing of `scope` or `steps` conf to understand its behavior | §5 (mode/phase only) |
| `daily_full_pipeline` scheduled run produces the same end-to-end behavior as today's `daily_collection_pipeline -c '{}'` | §7.1 vs current |
| Operator can trigger `item_equipment_pipeline -c '{"mode":"count","count":3}'` from UI and see count progress in logs | §7.2 |
| Operator can stop an active loop via `stop_loop_pipeline` without restarting ext-api | §7.3 |
| Legacy `daily_collection_pipeline` continues to work unchanged for one release | §6.5, §9.5 |
| DAG import smoke test passes in CI | §9.2 |

---

## 11. Out of scope

- Conditional branching between phase DAGs (operator already controls via separate triggers).
- Per-phase timeout overrides (sensors use canonical 4h / `count*5min+30min`).
- Loop iteration timeouts (mode=infinite has no end; operator must call stop_loop_pipeline).
- Auto-retry on 5xx beyond Airflow defaults.
- Loop state recovery across ext-api restarts (acknowledged #1291 §13 limitation).
- OAuth / API-key auth on Airflow endpoints (matches existing pattern).
- `daily_full_pipeline` conf override (e.g., mode=infinite for hot chains) — v2 if requested.
- Parallel CHARACTER_BASIC + ITEM_EQUIPMENT inside daily_full_pipeline (current sequential preserved).
- Removal of `daily_collection_pipeline` (one release cycle of deprecation first).

---

## 12. Risks

| Risk | Mitigation |
|------|------------|
| 5 DAGs vs 1 → operators confused which to trigger | Runbook `docs/21_Operations/dag-migration.md` with mapping table; legacy DAG marked deprecated in UI. |
| `daily_full_pipeline` TriggerDagRunOperator chains fail mid-way (e.g., OCID OK, CHARACTER_BASIC fails) | Triggered child DAG run shows as failed in UI; operator re-triggers CHARACTER_BASIC alone with `mode=once`. Cleaner than today's monolithic failure. |
| count_sensor timeout mis-tuned (5min/chunk P99 wrong) | Verify P99 chunk time during pipeline-test; tune constant. Document in runbook. |
| Legacy `daily_collection_pipeline` kept in code → bit rot | Marked `_legacy` in docstrings; CI test asserts legacy DAG still parses (§9.2) so breakage surfaces immediately. |
| 5 DAGs vs 1 DAG → Airflow scheduler overhead | Trivial — 4 extra DAG parses, no impact at this scale. |
| Operator accidentally triggers `daily_full_pipeline` while another instance running | All DAGs idempotent (409 → success). No data corruption risk. |

---

## 13. Files touched

| File | Change type | Lines (est.) |
|------|-------------|--------------|
| `docker/airflow/dags/phase_pipeline_factory.py` | new | ~280 |
| `docker/airflow/dags/ranking_ocid_lookup_pipeline.py` | new | ~120 |
| `docker/airflow/dags/character_basic_pipeline.py` | new | ~70 |
| `docker/airflow/dags/item_equipment_pipeline.py` | new | ~70 |
| `docker/airflow/dags/daily_full_pipeline.py` | new | ~90 |
| `docker/airflow/dags/stop_loop_pipeline.py` | new | ~80 |
| `docker/airflow/dags/daily_collection_pipeline.py` | modify | +10 (tags + docstring) |
| `docker/airflow/dags/per_phase_tasks.py` | modify | +15 (legacy docstring/comments) |
| `docker/airflow/dags/tests/test_phase_pipeline_factory.py` | new | ~120 |
| `docker/airflow/dags/tests/test_dag_imports.py` | modify | +10 (5 new dag_ids) |
| `docker/airflow/dags/tests/test_phase_dag_structure.py` | new | ~60 |
| `docs/21_Operations/dag-migration.md` | new | ~80 (operator runbook) |
| `docs/01_ADR/ADR-734_phase-separated-dags.md` | new | ~80 (decision record) |

Total new code: ~890 lines. Total modified: ~25 lines.

---

## 14. Rollout

1. Merge to develop. Legacy `daily_collection_pipeline` runs continue unaltered.
2. Update `docker-compose.airflow.yml` if any new DAG-level env vars needed (none expected — reuses existing `KAFKA_BOOTSTRAP_SERVERS`, `external_api` connection).
3. Airflow scheduler picks up new DAGs on next parse (≤30s by default).
4. Operators test new DAGs against local stack per `pipeline-test` skill §9.4.
5. Schedule `daily_full_pipeline` at the same cron (replaces scheduled `daily_collection_pipeline` in Airflow UI; turn off `daily_collection_pipeline` schedule via UI toggle, leave DAG definition).
6. One release cycle later: remove `daily_collection_pipeline` + `per_phase_tasks.py` legacy symbols.

---

## 15. Summary

Replace one overloaded DAG with five small DAGs, each with a single workflow. Operators select intent by DAG id, not by parsing JSON scope. N-count and infinite-loop modes are first-class via `mode` parameter. Loop state stays in ext-api (current pattern); Airflow sensors count chunk-ready events to bound loop mode. Legacy `daily_collection_pipeline` retained as deprecated for one release cycle.
