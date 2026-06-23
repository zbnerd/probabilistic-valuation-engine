# 3am Pipeline Chain — Design Spec

- Date: 2026-06-23
- Owner: pipeline / zbnerd
- Status: Approved (brainstorming complete)

## 1. Background / Problem

### Background

The current pipeline runs in two scheduled patterns:

1. `daily_collection_pipeline` (manual, 2h28m) — full chain once.
2. `item_equipment_pipeline` (manual, `mode=infinite`) — continuous loop.

There is no automated bridge between the two. The `item_equipment` loop runs forever once started; nothing tells it to stop, refresh upstream data (ranking → ocid-lookup → character-basic), and resume.

### Problem

The character_basic read model goes stale over time (new characters, changed IGN, retired characters). Running `item_equipment` indefinitely on stale data produces outdated valuations. The user needs:

- Every 03:00 KST, stop the running `item_equipment` loop.
- Refresh upstream data: ranking → ocid-lookup → character-basic (one-shot).
- Resume the `item_equipment` loop with fresh data.

### Goal

A single scheduled Airflow DAG that runs the sequence above unattended. Idempotent (safe to re-trigger). Failure of any step halts the chain for operator investigation.

## 2. Decision

> A new Airflow master DAG `morning_chain_pipeline` triggers the four existing per-phase DAGs in sequence via `TriggerDagRunOperator` + `lastCompletedByPhase` sensors. No Kotlin or endpoint changes.

```
morning_chain_pipeline (schedule: 0 18 * * * UTC = 03:00 KST)
  │
  ├─ check_ext_api_health (HttpSensor)
  ├─ check_loop_active (PythonOperator → /run-status)
  ├─ branch_on_loop_active (BranchPythonOperator)
  │    ├─ if loop active:   trigger_stop_loop → wait_stop_loop_complete
  │    └─ if loop inactive: skip directly to trigger_ranking_ocid
  ├─ trigger_ranking_ocid (TriggerDagRunOperator → ranking_ocid_lookup_pipeline)
  ├─ wait_ranking_ocid_complete (lastCompletedByPhase sensor, OCID_LOOKUP)
  ├─ trigger_character_basic_once (TriggerDagRunOperator, conf={mode:once})
  ├─ wait_character_basic_complete (lastCompletedByPhase sensor)
  ├─ trigger_item_equipment_infinite (TriggerDagRunOperator, conf={mode:infinite})
  └─ wait_first_iteration_started (PythonSensor, loopId present)
```

## 3. Trade-offs

### Sensitivity

- **Phase duration variance**: ranking+ocid can take 30m-2h depending on new characters. Character_basic 1-3h for 595K users. Item_equipment 2-3h per iteration.
- **Ext-api availability**: any 5xx during a sensor poke delays the chain; sustained outage halts it.
- **Loop stop latency**: `/stop/loop/phase/{phase}` waits for in-flight chunk boundary. Max one chunk (~5min at 154 files/s).
- **Airflow scheduler slot**: 10 tasks, 4 TriggerDagRunOperators, 5 sensors. Fits in default pool.

### Trade-off

| Choice | Gain | Cost |
| ------ | ---- | ---- |
| Compose existing per-phase DAGs | Reuses 6 PRs of DAG factory work; zero Kotlin risk; each phase's sensor already battle-tested | Cross-DAG run-history navigation in UI; 1 extra `TriggerDagRunOperator` hop per phase |
| Schedule via Airflow cron | UI visibility, retries, sensor framework | Requires Airflow scheduler running (already required for cleanup DAGs) |
| Idempotent branch on loop-active | Safe to re-trigger manually; survives missed runs | One extra PythonOperator (~5s cost) per run |
| `retries=0` strict halt | Forces operator attention on real failures | No auto-recovery from transient blips |

### Risk

- **Cross-DAG XCom**: `lastCompletedByPhase` reads from ext-api `/run-status`, not Airflow XCom. Sensors are independent of master DAG's xcom_pull. Failure mode: master DAG succeeds, sub-DAG fails. Mitigated by Airflow's sub-DAG run-status linkage (master task instance tracks sub-DAG state).
- **Cron drift**: Airflow uses UTC. KST = UTC+9 with no DST. Cron `0 18 * * *` UTC = 03:00 KST year-round. Document this in DAG file header.
- **Mid-day 3am rerun**: if operator manually re-triggers master at 11:00, it will run a full refresh. Acceptable but worth a comment in the spec.

### Non-Risk

- **Kotlin regression**: zero Kotlin changes.
- **Endpoint breakage**: zero new endpoints; zero contract changes.
- **Migration risk**: zero DB migrations.

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| New files | 2 | `morning_chain_pipeline.py`, `test_morning_chain_pipeline.py` |
| Modified files | 0 | — |
| New endpoints | 0 | — |
| Kotlin changes | 0 | — |
| New tasks per master DAG run | 9 | 1 health + 4 trigger + 3 factory sensors + 1 custom sensor |
| Total wall-clock (first run) | ~3-5h | dominated by character_basic (1-3h) |

### Observed Result

Pending implementation. Success criteria:

- Master DAG triggers at 03:00 KST without operator action.
- Existing item_equipment loop transitions to STOPPED within 5m of stop signal.
- ranking → ocid → char_basic → item_equipment all turn green in Airflow UI.
- 1st item_equipment iteration starts within 10m of character_basic completion.
- `Calculation completed with result saved` log appears for ≥1 chunk.

## 5. Summary

> One new Airflow DAG file, ~80 lines, composes the four existing per-phase DAGs into a 03:00 KST chain. Zero backend changes. Idempotent on the stop step. Strict halt on failure.
