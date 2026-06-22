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
