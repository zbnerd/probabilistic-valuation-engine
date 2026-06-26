#!/usr/bin/env bash
# docker/services/deploy-apps.sh
# Deploy 4 Spring Boot app services + autoheal + cadvisor via docker compose.
#
# Resolves the latest :sha-* image tag (:dev mutable tag may be absent),
# runs pre-flight checks (ports free, DNS resolves on maple-network, SA
# secrets present, rollback jars present, IDLE gate), then composes up the
# 4 app services + autoheal + cadvisor and polls /actuator/health.
#
# Idempotent: safe to re-run. Re-running will NOT recreate already-running
# app containers (no config drift on their side).
#
# Usage: ./docker/services/deploy-apps.sh
set -euo pipefail
cd "$(dirname "$0")/../.."  # repo root

MODULES=(external-api calculator synchronizer cleanup)
declare -A IMAGE_VAR=(
  [external-api]=IMAGE_EXTERNAL_API
  [calculator]=IMAGE_CALCULATOR
  [synchronizer]=IMAGE_SYNCHRONIZER
  [cleanup]=IMAGE_CLEANUP
)

# (1) Resolve image tag: prefer :dev, fall back to latest :sha-*
echo "==> Resolving image tags"
for mod in "${MODULES[@]}"; do
  if docker image inspect "maple/${mod}:dev" >/dev/null 2>&1; then
    img="maple/${mod}:dev"
  else
    sha_tag=$(docker images --format '{{.Tag}}' "maple/${mod}" 2>/dev/null | grep '^sha-' | sort -r | head -1)
    if [ -z "$sha_tag" ]; then
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
echo "==> Pre-flight: DNS from maple-network (postgres/kafka/minio)"
if ! docker run --rm --network maple-network alpine \
  sh -c 'nslookup postgres >/dev/null 2>&1 && nslookup kafka >/dev/null 2>&1 && nslookup minio >/dev/null 2>&1' 2>/dev/null; then
  cat >&2 <<'EOF'
ERROR: DNS not resolving on maple-network.
Run network reconcile first (the --alias is REQUIRED: docker network connect
connects by container name, but app services reference the compose service
alias 'postgres'/'kafka'/'minio', which does not transfer without --alias):
  docker network connect --alias postgres maple-network maple-postgres
  docker network connect --alias kafka maple-network maple-kafka
  docker network connect --alias minio maple-network probabilistic-valuation-engine-minio-1
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

# (6) IDLE gate: no non-terminal calculation_jobs
echo "==> Pre-flight: IDLE gate (calculation_jobs non-terminal)"
set -a; source .env; set +a
H=$(echo "$DB_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
P=$(echo "$DB_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
N=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
U=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
W=$(echo "$DB_URL" | sed -n 's|.*password=\([^&]*\).*|\1|p')
active=$(PGPASSWORD="$W" psql "host=$H port=$P user=$U dbname=$N sslmode=disable" -t -A -c \
  "SELECT count(*) FROM calculation_jobs WHERE status IN ('API_REQUESTED','RETRYING','CALCULATING');" 2>/dev/null || echo "?")
if [ "$active" != "0" ]; then
  echo "ERROR: $active non-terminal calculation_jobs — pipeline not IDLE, abort (wait for drain or stop pipeline)" >&2
  exit 1
fi

# (7) Start 4 app services (IMAGE_* resolved above overrides services.yml :dev default)
echo "==> Starting 4 app services"
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup

# (8) Start autoheal + cadvisor
echo "==> Starting autoheal + cadvisor"
docker compose -f docker-compose.yml up -d autoheal cadvisor

# (9) Wait for app health (max ~120s per service)
echo "==> Waiting for app health"
for entry in external-api:8081 calculator:8082 synchronizer:8083 cleanup:8084; do
  name="${entry%%:*}"; port="${entry##*:}"; ok=0
  for _ in $(seq 1 24); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      ok=1; break
    fi
    sleep 5
  done
  echo "  $name (port $port): $([ "$ok" -eq 1 ] && echo UP || echo FAILED)"
done

# (10) Status table
echo "==> Status"
docker ps --format 'table {{.Names}}\t{{.Status}}' \
  | grep -E 'maple-(external-api|calculator|synchronizer|cleanup|autoheal|cadvisor)|NAMES'
