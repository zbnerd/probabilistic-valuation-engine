#!/usr/bin/env bash
# Wrapper for VS3 dev e2e MinIO validation (issue #1218).
# Subcommands: env, smoke, chaos, all.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="${SCRIPT_DIR}/lib"

# .env load (fail-fast if missing)
if [ ! -f .env ]; then
  echo "❌ .env not found in $(pwd)"; exit 1
fi
set -a; source .env; set +a

# Pre-flight: STORAGE_BACKEND must be minio
if [ "${STORAGE_BACKEND:-local}" != "minio" ]; then
  echo "❌ STORAGE_BACKEND=${STORAGE_BACKEND:-unset}; VS3 requires 'minio'."; exit 1
fi

# Load preflight env (MINIO_HEALTH_KEY, SNAPSHOT_RESUME_CMD, CLEANUP_TRIGGER_CMD)
if [ -f /tmp/vs3-preflight.env ]; then
  source /tmp/vs3-preflight.env
fi

# Report directory + timestamp
TS=$(date +%Y-%m-%dT%H-%M-%S)
REPORT_DIR="docs/reports"
REPORT_JSON="${REPORT_DIR}/vs3-validation-${TS}.json"
REPORT_MD="${REPORT_DIR}/vs3-validation-${TS}.md"
LOG_DIR="logs"
mkdir -p "${REPORT_DIR}" "${LOG_DIR}"

CHECKS_JSON="[]"

record_check() {
  local name="$1" status="$2" duration_ms="$3" extra="${4:-}"
  CHECKS_JSON=$(echo "${CHECKS_JSON}" | jq \
    --arg name "${name}" \
    --arg status "${status}" \
    --argjson duration "${duration_ms}" \
    --arg extra "${extra}" \
    '. + [{name: $name, status: $status, durationMs: $duration, extra: $extra}]')
}

cmd_env() {
  echo "=== env: MinIO + bucket + lifecycle ==="
  source "${LIB_DIR}/minio-checks.sh"

  local start_ms
  start_ms=$(date +%s%3N); check_minio_ready && record_check "mc_ready" "pass" "$(( $(date +%s%3N) - start_ms ))" || { record_check "mc_ready" "fail" "$(( $(date +%s%3N) - start_ms ))"; exit 2; }
  start_ms=$(date +%s%3N); check_bucket && record_check "bucket_exists" "pass" "$(( $(date +%s%3N) - start_ms ))" || { record_check "bucket_exists" "fail" "$(( $(date +%s%3N) - start_ms ))"; exit 2; }
  start_ms=$(date +%s%3N); check_lifecycle_rules && record_check "lifecycle_rules_4" "pass" "$(( $(date +%s%3N) - start_ms ))" || { record_check "lifecycle_rules_4" "fail" "$(( $(date +%s%3N) - start_ms ))"; exit 2; }

  echo "✅ env checks passed"
}

cmd_smoke() {
  echo "=== smoke: 5 modules + MinIO E2E ==="
  source "${LIB_DIR}/module-health.sh"

  echo "⏳ smoke: waiting for 5 modules to be healthy..."
  check_all_modules_health || { record_check "modules_health" "fail" 0 "see logs"; exit 4; }
  record_check "modules_health" "pass" 0 "5/5 UP"

  # Run a single E2E via the existing /api/v5/characters endpoint.
  # Use 아델 as a known-good IGN.
  local ign="아델"
  echo "⏳ smoke: triggering expectation for ${ign}..."
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/v5/characters/${ign}/expectation" || echo "000")
  if [ "${code}" != "202" ] && [ "${code}" != "200" ]; then
    echo "❌ smoke: expectation returned ${code} (expected 202 or 200)"; exit 5
  fi
  record_check "expectation_api" "pass" 0 "ign=${ign} http=${code}"

  # Wait up to 5m for "Calculation completed with result saved" in calculator log
  echo "⏳ smoke: waiting up to 300s for 'Calculation completed with result saved'..."
  local deadline=$((SECONDS + 300))
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    if grep -q "Calculation completed with result saved" "${LOG_DIR}"/vs3-validation-*-calculator.log 2>/dev/null; then
      echo "✅ smoke: calculation completed"
      record_check "calculation_completed" "pass" 0 ""
      break
    fi
    sleep 5
  done
  if ! grep -q "Calculation completed with result saved" "${LOG_DIR}"/vs3-validation-*-calculator.log 2>/dev/null; then
    echo "❌ smoke: calculation did not complete within 300s"; exit 5
  fi

  # ERROR scan
  local errs
  errs=$(grep -h "ERROR" "${LOG_DIR}"/vs3-validation-*-*.log 2>/dev/null | wc -l)
  if [ "${errs}" -gt 0 ]; then
    echo "❌ smoke: ${errs} ERROR lines found in logs"; exit 5
  fi
  record_check "no_error_logs" "pass" 0 "errs=${errs}"

  # MinIO prefix non-empty
  source "${LIB_DIR}/minio-checks.sh"
  for prefix in snapshots runs ocid-mapping calculator/runs; do
    start_ms=$(date +%s%3N)
    list_prefix_nonempty "${prefix}" && record_check "prefix_${prefix}" "pass" "$(( $(date +%s%3N) - start_ms ))" || { record_check "prefix_${prefix}" "fail" "$(( $(date +%s%3N) - start_ms ))"; exit 5; }
  done

  echo "✅ smoke passed"
}

cmd_chaos() {
  echo "=== chaos: MinIO stop/down/start/up ==="
  source "${LIB_DIR}/chaos-minio.sh"
  local start_ms
  start_ms=$(date +%s%3N)
  chaos_test 30 120 && record_check "chaos" "pass" "$(( $(date +%s%3N) - start_ms ))" "" || { record_check "chaos" "fail" "$(( $(date +%s%3N) - start_ms ))" "see logs"; exit 6; }
  echo "✅ chaos passed"
}

write_report() {
  local exit_code="$1"
  local result="pass"
  [ "${exit_code}" -ne 0 ] && result="fail"

  jq -n \
    --arg validationId "vs3-${TS}" \
    --arg gitSha "$(git rev-parse --short HEAD)" \
    --arg storageBackend "${STORAGE_BACKEND}" \
    --arg endpoint "${MINIO_ENDPOINT}" \
    --arg bucket "${MINIO_BUCKET}" \
    --arg consoleUrl "http://localhost:9001" \
    --argjson checks "${CHECKS_JSON}" \
    --arg result "${result}" \
    --argjson exitCode "${exit_code}" \
    '{
      validationId: $validationId,
      gitSha: $gitSha,
      storageBackend: $storageBackend,
      minio: { endpoint: $endpoint, bucket: $bucket, consoleUrl: $consoleUrl },
      checks: $checks,
      loadTest: { status: "manual_pending", rps: null, p99Ms: null, note: "Baseline comparison N/A" },
      manualSteps: ["minio_console_visual_inspect", "snapshot_resume_retry", "load_test_run"],
      result: $result,
      exitCode: $exitCode
    }' > "${REPORT_JSON}"

  # Markdown report (simple)
  {
    echo "# VS3 Validation Report — ${TS}"
    echo
    echo "- Git SHA: $(git rev-parse --short HEAD)"
    echo "- Storage backend: ${STORAGE_BACKEND}"
    echo "- MinIO endpoint: ${MINIO_ENDPOINT}"
    echo "- Bucket: ${MINIO_BUCKET}"
    echo "- Result: **${result}** (exit ${exit_code})"
    echo
    echo "## Checks"
    echo
    echo "| Name | Status | Duration (ms) | Extra |"
    echo "|------|--------|---------------|-------|"
    echo "${CHECKS_JSON}" | jq -r '.[] | "| \(.name) | \(.status) | \(.durationMs) | \(.extra) |"'
    echo
    echo "## Manual steps"
    echo
    echo "- [ ] MinIO console visual inspect: ${MINIO_BUCKET}/{snapshots,runs,ocid-mapping,calculator/runs}/"
    echo "- [ ] Snapshot resume retry: \`${SNAPSHOT_RESUME_CMD:-TBD}\`"
    echo "- [ ] Load-test run + raw RPS/p99 recorded: \`./load-test/run-v5-db-throughput.sh\`"
  } > "${REPORT_MD}"

  echo "📄 reports: ${REPORT_JSON} + ${REPORT_MD}"
}

cmd_all() {
  # Trap ERR + EXIT so the report is written on failure too.
  # On success, exit_code stays 0; on any earlier exit, EXIT trap fires
  # with the actual failure code (2/4/5/6/7).
  local exit_code=0
  trap 'exit_code=$?; write_report "${exit_code}"; exit "${exit_code}"' EXIT
  trap 'exit_code=$?; write_report "${exit_code}"; exit "${exit_code}"' ERR

  cmd_env
  cmd_smoke
  cmd_chaos

  # Success path: clear traps and write the report explicitly
  trap - EXIT ERR
  write_report 0
}

# Subcommand dispatch
case "${1:-help}" in
  env)   cmd_env ;;
  smoke) cmd_smoke ;;
  chaos) cmd_chaos ;;
  all)   cmd_all ;;
  *)
    echo "Usage: $0 {env|smoke|chaos|all}"
    echo "  env   - MinIO + bucket + lifecycle (issue #1218 items 1-3)"
    echo "  smoke - 5-module health + E2E + MinIO prefix (items 4-6, 8)"
    echo "  chaos - stop/down/start/up verification (item 11)"
    echo "  all   - env + smoke + chaos in sequence"
    exit 1
    ;;
esac
