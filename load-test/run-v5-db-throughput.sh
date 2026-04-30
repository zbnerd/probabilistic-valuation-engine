#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT_DIR"

load_env_file() {
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != *=* ]] && continue

    local key="${line%%=*}"
    local value="${line#*=}"
    key="$(echo "$key" | xargs)"
    value="${value%$'\r'}"
    value="${value%\"}"
    value="${value#\"}"
    value="${value%\'}"
    value="${value#\'}"
    export "$key=$value"
  done < .env
}

load_env_file

PSQL_DB_HOST=$(echo "${DB_URL:?DB_URL is required}" | sed -n 's|.*://\([^:/]*\).*|\1|p')
PSQL_DB_PORT=${DB_PORT:-6543}
PSQL_DB_NAME=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
PSQL_DB_USER=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
PSQL_DB_PASS=$(echo "$DB_URL" | sed -n 's|.*password=\(.*\)|\1|p')

COUNT=${COUNT:-10000}
CONCURRENCY=${CONCURRENCY:-50}
SAMPLE_INTERVAL=${SAMPLE_INTERVAL:-30}
POST_SAMPLE_COUNT=${POST_SAMPLE_COUNT:-2}
RESET_VIEWS=${RESET_VIEWS:-0}
MAX_DRAIN_WAIT=${MAX_DRAIN_WAIT:-200}

SERVER_PID=""
SERVER_STARTED=0
MONITOR_PID=""
BOOT_LOG="module-app/logs/load-test-bootrun-$(date +%Y%m%d_%H%M%S).log"

psql_db() {
  PGPASSWORD="$PSQL_DB_PASS" psql "host=$PSQL_DB_HOST port=$PSQL_DB_PORT user=$PSQL_DB_USER dbname=$PSQL_DB_NAME sslmode=require" "$@"
}

cleanup() {
  if [[ -n "$MONITOR_PID" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
  fi
  if [[ "$SERVER_STARTED" == "1" && -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

wait_for_server() {
  for _ in $(seq 1 90); do
    if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Server did not become healthy. See $BOOT_LOG" >&2
  return 1
}

db_counts() {
  psql_db -At -c "
    SELECT
      (SELECT count(*) FROM character_valuation_views),
      (SELECT count(*) FROM pgmq.q_expectation_calc_high);
  "
}

monitor_db() {
  local prev_views=""
  local prev_ts=""

  printf '%s\n' "timestamp,elapsed_s,views,queue_high,delta_views,views_per_sec"
  while true; do
    local ts
    local counts
    local views
    local queue_high
    local delta=0
    local rate=0

    ts=$(date +%s)
    counts=$(db_counts)
    views=$(echo "$counts" | awk -F'|' '{print $1}')
    queue_high=$(echo "$counts" | awk -F'|' '{print $2}')

    if [[ -n "$prev_ts" ]]; then
      local dt=$((ts - prev_ts))
      delta=$((views - prev_views))
      if [[ "$dt" -gt 0 ]]; then
        rate=$(awk -v d="$delta" -v t="$dt" 'BEGIN { printf "%.2f", d / t }')
      fi
    fi

    printf '%s,%s,%s,%s,%s,%s\n' "$(date -Is)" "$((ts - START_TS))" "$views" "$queue_high" "$delta" "$rate"

    prev_views=$views
    prev_ts=$ts
    sleep "$SAMPLE_INTERVAL"
  done
}

if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "Using existing server on localhost:8080"
else
  echo "Starting server in background. Log: $BOOT_LOG"
  load_env_file
  ./gradlew :module-app:bootRun >"$BOOT_LOG" 2>&1 &
  SERVER_PID=$!
  SERVER_STARTED=1
  wait_for_server
fi

if [[ "$RESET_VIEWS" == "1" ]]; then
  echo "Resetting character_valuation_views"
  psql_db -c "DELETE FROM character_valuation_views;"
else
  echo "Skipping view reset. Set RESET_VIEWS=1 to delete character_valuation_views before the run."
fi

echo "Initial DB state"
psql_db -c "SELECT count(*) AS character_valuation_views FROM character_valuation_views;"
psql_db -c "SELECT count(*) AS expectation_calc_high_queue FROM pgmq.q_expectation_calc_high;"

START_TS=$(date +%s)
monitor_db &
MONITOR_PID=$!

COUNT="$COUNT" CONCURRENCY="$CONCURRENCY" MAX_DRAIN_WAIT="$MAX_DRAIN_WAIT" python3 load_test_v5.py

for _ in $(seq 1 "$POST_SAMPLE_COUNT"); do
  sleep "$SAMPLE_INTERVAL"
done

echo "Final DB state"
psql_db -c "SELECT count(*) AS character_valuation_views FROM character_valuation_views;"
psql_db -c "SELECT count(*) AS expectation_calc_high_queue FROM pgmq.q_expectation_calc_high;"
