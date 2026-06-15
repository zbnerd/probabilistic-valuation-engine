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
