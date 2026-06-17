#!/usr/bin/env bash
# scripts/airflow-ensure-connections.sh
#
# Idempotently create the HTTP connections the Airflow DAGs (daily_collection_pipeline,
# daily_cleanup_pipeline) need to reach Spring Boot services.
#
# Why this exists: Airflow container metadata is ephemeral on restart. Connections
# are stored in the Airflow metadata DB (not lost on container restart), but new
# images or `airflow db reset` deletes them. The pipeline-test SKILL.md step 5
# creates them, but only on first-run init. If the scheduler container is
# recreated without running that step, HttpSensors pokes fail and the DAGs mark
# failed. Verified 2026-06-17: 6 consecutive daily_cleanup_pipeline runs failed
# silently because the `cleanup` connection did not exist.
#
# Host choice: `localhost` because maple-airflow-* uses `network_mode: host`.
# In host network mode the container shares the host's network namespace, so
# `host.docker.internal` (a bridge-only DNS entry) does NOT resolve.
#
# Usage:
#   scripts/airflow-ensure-connections.sh                       # create all 3
#   SCHEDULER_CTN=maple-airflow-scheduler scripts/airflow-ensure-connections.sh
#   scripts/airflow-ensure-connections.sh --verify              # check only, no create
#
# Exit codes:
#   0 = all connections present
#   1 = scheduler container not running
#   2 = one or more connections failed to create

set -euo pipefail

SCHEDULER_CTN="${SCHEDULER_CTN:-maple-airflow-scheduler}"
VERIFY_ONLY=0
[[ "${1:-}" == "--verify" ]] && VERIFY_ONLY=1

if ! docker ps --format '{{.Names}}' | grep -q "^${SCHEDULER_CTN}$"; then
  echo "ERROR: ${SCHEDULER_CTN} not running. Start Airflow first." >&2
  exit 1
fi

# Connection definitions: name|host|port
CONNECTIONS=(
  "external_api|localhost|8081"
  "calculator|localhost|8082"
  "cleanup|localhost|8084"
)

for entry in "${CONNECTIONS[@]}"; do
  IFS='|' read -r conn host port <<< "$entry"
  if [ "$VERIFY_ONLY" -eq 1 ]; then
    if docker exec "${SCHEDULER_CTN}" airflow connections get "${conn}" >/dev/null 2>&1; then
      echo "  ok: ${conn} (${host}:${port})"
    else
      echo "  MISSING: ${conn} (${host}:${port})" >&2
      exit 2
    fi
  else
    # Idempotent: delete first, then add. Re-running is safe.
    docker exec "${SCHEDULER_CTN}" airflow connections delete "${conn}" >/dev/null 2>&1 || true
    docker exec "${SCHEDULER_CTN}" airflow connections add "${conn}" \
      --conn-type http --conn-host "${host}" --conn-port "${port}" --conn-schema http \
      >/dev/null
    echo "  set: ${conn} -> http://${host}:${port}"
  fi
done

if [ "$VERIFY_ONLY" -eq 1 ]; then
  echo "all 3 connections present"
fi
