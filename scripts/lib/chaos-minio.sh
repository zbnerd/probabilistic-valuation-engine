#!/usr/bin/env bash
# Chaos test: stop MinIO → verify all 5 modules' health turn DOWN → start
# MinIO → verify they recover to UP.

set -euo pipefail

source "$(dirname "$0")/module-health.sh"

# Args:
#   $1 = down timeout (default 30s) — how long to wait after stopping minio
#        before asserting all 5 modules are DOWN.
#   $2 = recovery timeout (default 120s) — how long to wait after starting
#        minio for all 5 modules to recover to UP.
chaos_test() {
  local down_timeout="${1:-30}"
  local recovery_timeout="${2:-120}"

  echo "🔥 chaos: stopping minio..."
  docker compose stop minio

  echo "⏳ chaos: waiting ${down_timeout}s for 5 modules to turn DOWN..."
  sleep "${down_timeout}"

  local down_count=0
  for module in external-api calculator synchronizer rest-controller cleanup; do
    local port="${MODULE_PORTS[${module}]}"
    local status
    status=$(curl -s "http://localhost:${port}/actuator/health" 2>/dev/null | jq -r '.status // "UNKNOWN"')
    if [ "${status}" = "DOWN" ] || [ "${status}" = "OUT_OF_SERVICE" ]; then
      down_count=$((down_count + 1))
    fi
    echo "  ${module}: status=${status}"
  done

  if [ "${down_count}" -ne 5 ]; then
    echo "❌ chaos_down: only ${down_count}/5 modules DOWN"
    docker compose start minio
    return 6
  fi
  echo "✅ chaos_down: 5/5 modules DOWN"

  echo "🔥 chaos: starting minio..."
  docker compose start minio

  echo "⏳ chaos: waiting up to ${recovery_timeout}s for 5 modules to recover..."
  local deadline=$((SECONDS + recovery_timeout))
  local up_count=0
  while [ "${SECONDS}" -lt "${deadline}" ] && [ "${up_count}" -lt 5 ]; do
    up_count=0
    for module in external-api calculator synchronizer rest-controller cleanup; do
      local port="${MODULE_PORTS[${module}]}"
      local status
      status=$(curl -s "http://localhost:${port}/actuator/health" 2>/dev/null | jq -r '.status // "UNKNOWN"')
      if [ "${status}" = "UP" ]; then
        up_count=$((up_count + 1))
      fi
    done
    if [ "${up_count}" -lt 5 ]; then
      sleep 5
    fi
  done

  if [ "${up_count}" -eq 5 ]; then
    echo "✅ chaos_recovery: 5/5 modules UP"
    return 0
  fi
  echo "❌ chaos_recovery: only ${up_count}/5 modules UP after ${recovery_timeout}s"
  return 6
}
