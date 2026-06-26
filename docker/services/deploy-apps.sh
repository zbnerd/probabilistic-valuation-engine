#!/usr/bin/env bash
# docker/services/deploy-apps.sh
# Deploy 4 Spring Boot app services + autoheal + cadvisor via docker compose.
#
# Resolves the newest :sha-* image tag (:dev mutable tag may be absent),
# runs pre-flight checks (ports free, DNS resolves on maple-network, SA
# secrets present, rollback jars present, IDLE gate), composes up the 4 app
# services, polls /actuator/health, and ONLY THEN starts autoheal + cadvisor
# (so autoheal cannot restart an app mid-cold-start).
#
# Idempotent: safe to re-run on already-running app containers.
#
# Usage: ./docker/services/deploy-apps.sh
set -euo pipefail
cd "$(dirname "$0")/../.."  # repo root

MODULES=(external-api calculator synchronizer cleanup)
# MINIO_ACCESS_KEY string per module. For 3 modules it equals the module name;
# external-api differs (ext-api). This must match the access key minio-bootstrap
# baked into MinIO, NOT necessarily the module name.
declare -A IMAGE_VAR=(
  [external-api]=IMAGE_EXTERNAL_API
  [calculator]=IMAGE_CALCULATOR
  [synchronizer]=IMAGE_SYNCHRONIZER
  [cleanup]=IMAGE_CLEANUP
)

# (1) Resolve image tag: prefer :dev, fall back to NEWEST :sha-* (by build time,
# not lexicographic order — git short SHAs are not sortable as strings).
echo "==> Resolving image tags"
for mod in "${MODULES[@]}"; do
  if docker image inspect "maple/${mod}:dev" >/dev/null 2>&1; then
    img="maple/${mod}:dev"
  else
    # Newest sha tag by CreatedAt (IMAGE ID col, format: Tag<TAB>CreatedAt).
    sha_tag=$(docker images --format '{{.Tag}}\t{{.CreatedAt}}' "maple/${mod}" 2>/dev/null \
      | awk -F'\t' '$1 ~ /^sha-/ {print}' \
      | sort -k2,2 -r \
      | head -1 | cut -f1)
    if [ -z "${sha_tag:-}" ]; then
      echo "ERROR: no image for maple/${mod} (:dev nor :sha-*) — run docker/services/build.sh" >&2
      exit 1
    fi
    img="maple/${mod}:${sha_tag}"
  fi
  export "${IMAGE_VAR[$mod]}=${img}"
  echo "  ${IMAGE_VAR[$mod]}=${img}"
done

# (2) Pre-flight: ports 8081-8084 must be free (split-brain prevention)
echo "==> Pre-flight: ports 8081-8084 free"
for port in 8081 8082 8083 8084; do
  if pid=$(lsof -ti:"$port" -sTCP:LISTEN 2>/dev/null) && [ -n "$pid" ]; then
    echo "ERROR: port $port occupied (pid $pid) — stop nohup modules first" >&2
    exit 1
  fi
done

# (3) Pre-flight: DNS resolves from maple-network (Task 0 network reconcile done?)
# Checks all 4 aliases the apps depend on (synchronizer needs redis too).
echo "==> Pre-flight: DNS from maple-network (postgres/kafka/minio/redis)"
if ! dns_missing=$(docker run --rm --network maple-network alpine sh -c '
  for h in postgres kafka minio redis; do
    nslookup "$h" >/dev/null 2>&1 || echo "$h"
  done' 2>/dev/null); then
  dns_missing="<docker run failed — is maple-network present?>"
fi
if [ -n "$dns_missing" ]; then
  cat >&2 <<EOF
ERROR: DNS not resolving on maple-network for: $(echo "$dns_missing" | tr '\n' ' ')
Run network reconcile first (--alias is REQUIRED: docker network connect
connects by container name, but app services reference the compose service
alias, which does not transfer without --alias):
  docker network connect --alias postgres maple-network maple-postgres
  docker network connect --alias kafka    maple-network maple-kafka
  docker network connect --alias minio    maple-network probabilistic-valuation-engine-minio-1
EOF
  exit 1
fi

# (4) Pre-flight: SA secrets present
echo "==> Pre-flight: SA secrets"
ls docker/services/secrets/sa-ext-api.key \
   docker/services/secrets/sa-calculator.key \
   docker/services/secrets/sa-synchronizer.key \
   docker/services/secrets/sa-cleanup.key >/dev/null

# (5) Pre-flight: rollback jars present
echo "==> Pre-flight: rollback jars"
for mod in "${MODULES[@]}"; do
  if [ ! -f "module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar" ]; then
    echo "ERROR: rollback jar missing: module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar" >&2
    exit 1
  fi
done

# (6) IDLE gate: no non-terminal calculation_jobs (distinguish parse-failure /
# DB-down from a genuinely busy pipeline — do NOT mask DB errors as "not IDLE").
echo "==> Pre-flight: IDLE gate (calculation_jobs non-terminal)"
set -a; source .env; set +a
H=$(echo "$DB_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
P=$(echo "$DB_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
N=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
U=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
W=$(echo "$DB_URL" | sed -n 's|.*password=\([^&]*\).*|\1|p')
if [ -z "$H" ] || [ -z "$U" ] || [ -z "$W" ]; then
  echo "ERROR: failed to parse DB_URL for IDLE gate (host/user/password empty)" >&2
  exit 1
fi
if ! active=$(PGPASSWORD="$W" psql "host=$H port=$P user=$U dbname=$N sslmode=disable" -t -A -c \
  "SELECT count(*) FROM calculation_jobs WHERE status IN ('API_REQUESTED','RETRYING','CALCULATING');" 2>&1); then
  echo "ERROR: IDLE gate DB query failed (DB unreachable?): $active" >&2
  exit 1
fi
case "$active" in
  ''|*[!0-9]*) echo "ERROR: IDLE gate returned non-numeric result: '$active'" >&2; exit 1 ;;
esac
if [ "$active" != "0" ]; then
  echo "ERROR: $active non-terminal calculation_jobs — pipeline not IDLE, abort (wait for drain or stop pipeline)" >&2
  exit 1
fi

# (7) Start 4 app services (IMAGE_* overrides services.yml :dev default).
# autoheal is intentionally NOT started here — it is started in step (9) AFTER
# health polling, so it cannot restart an app during its cold start.
echo "==> Starting 4 app services"
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup

# (8) Wait for app health (max ~120s per service). Spring Boot cold start under
# -Xmx2g can take 60-90s; if this times out, check `docker logs maple-<mod>`.
echo "==> Waiting for app health"
failed=()
for entry in external-api:8081 calculator:8082 synchronizer:8083 cleanup:8084; do
  name="${entry%%:*}"; port="${entry##*:}"; ok=0
  for _ in $(seq 1 24); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      ok=1; break
    fi
    sleep 5
  done
  if [ "$ok" -eq 1 ]; then
    echo "  $name (port $port): UP"
  else
    echo "  $name (port $port): FAILED (check docker logs maple-$name)"
    failed+=("$name")
  fi
done
if [ "${#failed[@]}" -gt 0 ]; then
  echo "ERROR: health check failed for: ${failed[*]}" >&2
  echo "  starting autoheal/cadvisor anyway (for diagnostics); exiting non-zero" >&2
  docker compose -f docker-compose.yml up -d autoheal cadvisor || true
  exit 1
fi

# (9) Start autoheal + cadvisor AFTER apps are healthy.
echo "==> Starting autoheal + cadvisor"
docker compose -f docker-compose.yml up -d autoheal cadvisor

# (10) Status table
echo "==> Status"
docker ps --format 'table {{.Names}}\t{{.Status}}' \
  | grep -E 'maple-(external-api|calculator|synchronizer|cleanup|autoheal|cadvisor)|NAMES' || true
