#!/usr/bin/env bash
# docker/minio/bootstrap.sh
# One-shot MinIO bootstrap: bucket, ILM, 4 service accounts, 4 policies, attach.
# Idempotent — safe to re-run.
# Invariant: exactly 1 ILM rule per managed prefix after every run.
# Flags:
#   --rotate  Force re-create of all SAs and policies (use after policy JSON edits
#             or when SA secrets have drifted from .env.bootstrap values).
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT,
#   SA_EXT_API_SECRET_KEY, SA_CALCULATOR_SECRET_KEY,
#   SA_SYNCHRONIZER_SECRET_KEY, SA_CLEANUP_SECRET_KEY.

set -euo pipefail

# Parse --rotate flag
ROTATE=0
for arg in "$@"; do
  [[ "$arg" == "--rotate" ]] && ROTATE=1
done

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/maple-expectation
mc anonymous set none local/maple-expectation

# ILM: list, remove all existing rules for the prefix, add one fresh rule.
# mc ilm add is NOT idempotent — without this loop, re-runs duplicate rules.
# The minio/mc Alpine image has no jq/awk/grep/sed. We strip the box-drawing
# chars with tr, then use bash read+positional params to extract (ID, PREFIX).
# We disable -u locally because some stripped lines have <3 tokens and
# accessing $2/$3 under set -u would fail.
for prefix in snapshots/ runs/ calculator/ ocid-mapping/; do
  while read -r rule_id matched_prefix; do
    [ "$matched_prefix" = "$prefix" ] && [ -n "$rule_id" ] && \
      mc ilm rm --id "$rule_id" local/maple-expectation
  done < <(mc ilm ls local/maple-expectation 2>/dev/null | \
           tr -d "│" | \
           ( set +u
             while read -r line; do
               set -- $line
               # Only lines with "Enabled" + a 3rd token (the PREFIX) are real rules.
               [ "${2:-}" = "Enabled" ] && [ -n "${3:-}" ] && echo "$1 $3"
             done
           ))
  mc ilm add --expiry-days 2 --prefix "$prefix" local/maple-expectation
done

# Service accounts
declare -A sa_secret_keys=(
  [ext-api]="$SA_EXT_API_SECRET_KEY"
  [calculator]="$SA_CALCULATOR_SECRET_KEY"
  [synchronizer]="$SA_SYNCHRONIZER_SECRET_KEY"
  [cleanup]="$SA_CLEANUP_SECRET_KEY"
)

for sa in "${!sa_secret_keys[@]}"; do
  if [[ $ROTATE -eq 1 ]] || ! mc admin user info local "$sa" >/dev/null 2>&1; then
    mc admin user remove local "$sa" 2>/dev/null || true
    mc admin user add local "$sa" "${sa_secret_keys[$sa]}"
  fi
  if [[ $ROTATE -eq 1 ]] || ! mc admin policy info local "policy-$sa" >/dev/null 2>&1; then
    mc admin policy remove local "policy-$sa" 2>/dev/null || true
    mc admin policy create local "policy-$sa" "/scripts/policies/$sa.json"
  fi
  mc admin policy attach local "policy-$sa" --user "$sa"
done

# Persist SA secret keys for the two startup paths:
#   1. docker compose path: docker/services/secrets/sa-<module>.key
#      (mounted as /run/secrets/sa-<module> via the services overlay).
#   2. nohup path: .env.<module> (MINIO_SECRET_KEY line).
# Both writes are idempotent.
#
# REPO_ROOT defaults to /workspace (the bind-mount path used by the
# minio-bootstrap compose service). For host-side runs (bash docker/minio/bootstrap.sh),
# fall back to the script's parent's parent.
REPO_ROOT="${REPO_ROOT:-/workspace}"
if [ ! -d "${REPO_ROOT}/docker" ]; then
  REPO_ROOT="$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd)"
fi
echo "[bootstrap] REPO_ROOT=${REPO_ROOT}"

declare -A SA_TO_MODULE=(
  [ext-api]=ext-api
  [calculator]=calculator
  [synchronizer]=synchronizer
  [cleanup]=cleanup
)

mkdir -p "${REPO_ROOT}/docker/services/secrets"
chmod 700 "${REPO_ROOT}/docker/services/secrets"

for sa in "${!sa_secret_keys[@]}"; do
  secret="${sa_secret_keys[$sa]}"
  module="${SA_TO_MODULE[$sa]:-$sa}"

  # 1. docker compose secret file (used by services overlay).
  # Mode 0444 so the container's non-root user (maple, UID 1000) can read
  # the secret. Compose v3.8 secret mounts preserve the source file's mode.
  printf '%s' "${secret}" > "${REPO_ROOT}/docker/services/secrets/sa-${module}.key"
  chmod 0444 "${REPO_ROOT}/docker/services/secrets/sa-${module}.key"
  echo "[bootstrap] wrote ${REPO_ROOT}/docker/services/secrets/sa-${module}.key"

  # 2. legacy .env.<module> for nohup path. .env files are gitignored.
  env_file="${REPO_ROOT}/.env.${module}"
  if [ -f "${env_file}" ] && grep -q "^MINIO_SECRET_KEY=" "${env_file}" 2>/dev/null; then
    sed -i.bak "s|^MINIO_SECRET_KEY=.*|MINIO_SECRET_KEY=${secret}|" "${env_file}" && rm -f "${env_file}.bak"
  else
    printf 'MINIO_ACCESS_KEY=%s\nMINIO_SECRET_KEY=%s\n' "${module}" "${secret}" >> "${env_file}"
  fi
  chmod 600 "${env_file}"
  echo "[bootstrap] wrote ${env_file}"
done

echo "[bootstrap] complete"
