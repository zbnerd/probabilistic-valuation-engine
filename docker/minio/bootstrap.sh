#!/usr/bin/env bash
# docker/minio/bootstrap.sh
# One-shot MinIO bootstrap: bucket, ILM, 4 service accounts, 4 policies, attach.
# Idempotent — safe to re-run.
# Invariant: exactly 1 ILM rule per managed prefix after every run.
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_ENDPOINT,
#   SA_EXT_API_SECRET_KEY, SA_CALCULATOR_SECRET_KEY,
#   SA_SYNCHRONIZER_SECRET_KEY, SA_CLEANUP_SECRET_KEY.

set -euo pipefail

mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing local/maple-expectation
mc anonymous set none local/maple-expectation

# ILM: list, remove all existing rules for the prefix, add one fresh rule.
# mc ilm add is NOT idempotent — without this loop, re-runs duplicate rules.
for prefix in snapshots/ runs/ calculator/ ocid-mapping/; do
  existing=$(mc ilm ls --json local/maple-expectation 2>/dev/null | \
    jq -r --arg p "$prefix" '.["maple-expectation"][]? | select(.Prefix == $p) | .ID' || true)
  for rule_id in $existing; do
    [ -n "$rule_id" ] && mc ilm rm --id "$rule_id" local/maple-expectation || true
  done
  mc ilm add --expiry-days 2 --prefix "$prefix" local/maple-expectation
done

# Service accounts (idempotent on user/policy existence; attach is a no-op if already attached)
declare -A sa_secret_keys=(
  [ext-api]="$SA_EXT_API_SECRET_KEY"
  [calculator]="$SA_CALCULATOR_SECRET_KEY"
  [synchronizer]="$SA_SYNCHRONIZER_SECRET_KEY"
  [cleanup]="$SA_CLEANUP_SECRET_KEY"
)

for sa in "${!sa_secret_keys[@]}"; do
  if ! mc admin user info local "$sa" >/dev/null 2>&1; then
    mc admin user add local "$sa" "${sa_secret_keys[$sa]}"
  fi
  if ! mc admin policy info local "policy-$sa" >/dev/null 2>&1; then
    mc admin policy create local "policy-$sa" "/scripts/policies/$sa.json"
  fi
  mc admin policy attach local "policy-$sa" --user "$sa"
done

echo "[bootstrap] complete"
