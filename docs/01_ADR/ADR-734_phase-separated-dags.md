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
