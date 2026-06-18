# ADR-393: Per-Phase Airflow DAG for ext-api Phase Endpoints

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
              ├── per_phase_loop_<PHASE> (×2)    [fire-and-forget; CHARACTER_BASIC, ITEM_EQUIPMENT only]
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
| Shape B (new DAG file) | Cleaner separation | Operators must know which DAG to trigger; duplicates task definitions |
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
| New task definitions | 16 | 4 trigger + 4 sensor + 2 loop + 4 stop + 1 branch + 1 join |
| New files | 3 | per_phase_tasks.py, tests/__init__.py, tests/test_per_phase_tasks.py |
| Modified files | 2 | daily_collection_pipeline.py (+60 lines), pipeline-test/SKILL.md (step 10a added) |
| Existing daily chain | unchanged | verified by DagBag parse test |

> LOOP_PHASES reduced from 3 to 2 (OCID_LOOKUP removed) after runtime verification on 2026-06-18: ext-api PhaseLoopController.loopablePhases only accepts CHARACTER_BASIC + ITEM_EQUIPMENT despite the #1291 spec mentioning OCID_LOOKUP.

### Observed Result

* (Filled in post-implementation via manual smoke test from `pipeline-test` skill)

---

## 5. Summary

> Extend the existing Airflow daily DAG with a `scope`-driven branch that routes `dag_run.conf['scope']` to either the existing daily chain or a per-phase fan-out via 11 task definitions backed by 3 helper factories.