#!/usr/bin/env bash
# 5-module /actuator/health polling with MinioHealthIndicator verification.
# All functions exit 0 on pass; non-zero on fail.

set -euo pipefail

# Discovered during pre-flight (Task 0 step 1).
# Confirmed: Spring derives bean name in lowerCamelCase from
# @Component class MinioHealthIndicator. See:
# module-infra/src/main/kotlin/maple/expectation/infrastructure/storage/MinioHealthIndicator.kt
MINIO_HEALTH_KEY="${MINIO_HEALTH_KEY:-minioHealthIndicator}"

# Module -> port map
declare -A MODULE_PORTS=(
  [external-api]=8081
  [calculator]=8082
  [synchronizer]=8083
  [rest-controller]=8080
  [cleanup]=8084
)

# Poll /actuator/health until status=UP and minio component=UP, or timeout.
# Args: <module-name> <timeout-seconds>
check_module_health() {
  local module="$1"
  local timeout="${2:-120}"
  local port="${MODULE_PORTS[${module}]}"
  local url="http://localhost:${port}/actuator/health"
  local deadline=$((SECONDS + timeout))

  while [ "${SECONDS}" -lt "${deadline}" ]; do
    local body
    body=$(curl -s "${url}" 2>/dev/null || echo "")
    if [ -n "${body}" ]; then
      local overall
      overall=$(echo "${body}" | jq -r '.status // "UNKNOWN"')
      local minio
      minio=$(echo "${body}" | jq -r ".components.${MINIO_HEALTH_KEY}.status // \"MISSING\"")

      if [ "${overall}" = "UP" ] && [ "${minio}" = "UP" ]; then
        echo "✅ ${module}_health: status=UP, components.${MINIO_HEALTH_KEY}.status=UP"
        return 0
      fi
    fi
    sleep 2
  done

  echo "❌ ${module}_health: timeout after ${timeout}s. Last body:"
  curl -s "${url}" || echo "(no response)"
  return 4
}

# Returns 0 if all 5 modules are healthy; non-zero with summary on first failure.
check_all_modules_health() {
  local failed=()
  for module in external-api calculator synchronizer rest-controller cleanup; do
    if ! check_module_health "${module}" 120; then
      failed+=("${module}")
    fi
  done
  if [ "${#failed[@]}" -eq 0 ]; then
    echo "✅ all_modules_health: 5/5 UP"
    return 0
  fi
  echo "❌ all_modules_health: failed modules: ${failed[*]}"
  return 4
}
