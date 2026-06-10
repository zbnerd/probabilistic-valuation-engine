#!/usr/bin/env bash
# MinIO CLI wrappers used by validate-minio-vs3.sh.
# All functions: exit 0 on pass, exit non-zero on fail. Print a single
# ✅/❌ line on stdout for human readers.

set -euo pipefail

# Source this file from a caller that has already done:
#   set -a && source .env && set +a
# so MINIO_ENDPOINT, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_BUCKET are set.

mc_alias_set() {
  mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1
}

check_minio_ready() {
  local url="${MINIO_ENDPOINT%/}/minio/health/ready"
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "${url}" 2>/dev/null)
  code="${code:-000}"
  if [ "${code}" = "200" ]; then
    echo "✅ mc_ready: ${url} -> 200"
    return 0
  fi
  echo "❌ mc_ready: ${url} -> ${code}"
  return 2
}

check_bucket() {
  mc_alias_set
  if mc ls "local/${MINIO_BUCKET}/" >/dev/null 2>&1; then
    echo "✅ bucket_exists: local/${MINIO_BUCKET}/"
    return 0
  fi
  echo "❌ bucket_exists: local/${MINIO_BUCKET}/ not found"
  return 2
}

# Expects 4 lifecycle rules: snapshots/, runs/, calculator/, ocid-mapping/
# with 2-day expiry each. The `mc ilm ls` output is a Unicode box-drawing
# table; each rule row contains exactly one `Enabled` or `Disabled` token,
# so count those occurrences to derive the rule count.
check_lifecycle_rules() {
  mc_alias_set
  local out
  out=$(mc ilm ls "local/${MINIO_BUCKET}/" 2>&1)
  local count
  count=$(echo "${out}" | grep -cE "(Enabled|Disabled)" || true)
  if [ "${count}" -ge 4 ]; then
    echo "✅ lifecycle_rules: ${count} rules present (need >= 4)"
    return 0
  fi
  echo "❌ lifecycle_rules: only ${count} rules (need >= 4). Full output:"
  echo "${out}"
  return 2
}

# Args: <prefix> (e.g. "snapshots")
# Exits 0 if any object exists under the prefix; non-zero + message if empty.
list_prefix_nonempty() {
  local prefix="$1"
  mc_alias_set
  local count
  count=$(mc ls --recursive "local/${MINIO_BUCKET}/${prefix}/" 2>/dev/null | wc -l)
  if [ "${count}" -gt 0 ]; then
    echo "✅ prefix_nonempty: local/${MINIO_BUCKET}/${prefix}/ has ${count} objects"
    return 0
  fi
  echo "❌ prefix_nonempty: local/${MINIO_BUCKET}/${prefix}/ is empty"
  return 5
}
