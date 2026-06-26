---
name: pipeline-test
description: End-to-end pipeline runtime test across external-api, calculator, synchronizer, and Airflow. Use when user says "pipeline test", "e2e test", "run the pipeline", "test the flow", or wants to verify the full data pipeline works from external API fetch through calculation to read model sync, including Airflow control plane integration.
---

# Pipeline Test

Runtime verification of the full data pipeline: Airflow (Control Plane) → External API → Calculator → Synchronizer → Cleanup.

## Modules

| Module | Port | Purpose |
|--------|------|---------|
| module-external-api | 8081 | External API call pipeline (ranking → OCID → character basic → item equipment) |
| module-calculator | 8082 | Expectation calculation pipeline |
| module-synchronizer | 8083 | Read model synchronization |
| module-cleanup | 8084 | Chunk-consumed event consumer + artifact GC (replaces legacy ext-api/calculator schedulers) |
| Airflow webserver | 8180 | DAG UI, manual trigger, run history |
| Airflow scheduler | — | DAG scheduling, sensor polling |

## Architecture

```
Airflow (Control Plane)          Spring Boot Services (Data Plane)
├── DAG: daily_collection        ├── external-api (8081)
│   ├── Health check             │   ├── GET /api/internal/run-status
│   ├── POST /trigger/daily      │   ├── POST /api/internal/trigger/daily
│   └── Poll run-status          │   └── Kafka producers
└── Sensor: 60s interval         ├── calculator (8082)
                                  ├── synchronizer (8083)
                                  └── cleanup (8084)
                                     └── consumes synchronizer.chunk.consumed
                                     └── Airflow daily_cleanup_pipeline triggers
                                         /api/internal/cleanup/{runs,calculator-runs,inbox}
```

- **Airflow** triggers pipelines and monitors completion via `/api/internal/run-status`
- **Kafka** handles chunk processing, event routing, retry, backpressure
- **run-on-startup** enabled in local profile — pipeline starts immediately on boot (no Airflow needed for local testing)

## Prerequisites

- `.env` file exists with DB_URL, NEXON_API_KEY, STORAGE_BACKEND, MINIO_* vars
- No existing processes on ports 8081, 8082, 8083, 8084, 8180
- **Step 1b starts docker services automatically** (Airflow DB, webserver, scheduler, MinIO, Kafka). Manual `docker compose up` is no longer required before invoking this skill.
- PostgreSQL accessible at the URL in `.env` `DB_URL` (uses `.env`, not hardcoded local)
- Docker Compose v2 for MinIO + Airflow services
- Data directory `../data` clean for fresh runs

**Storage backend**: pipeline test uses MinIO by default (`STORAGE_BACKEND=minio`). MinIO is required because the VS2 ObjectStorage migration consolidated all artifact storage to a single backend, and the pipeline test verifies that backend end-to-end.

## Startup mode

The skill supports two ways to start the Spring Boot modules:

- **`docker` (default)**: `docker compose -f docker-compose.yml -f docker-compose.services.yml up -d`. Services run as containers on `maple-network`. Per-service MinIO SA is mounted as `/run/secrets/sa-<module>` and `StorageConfig` reads it directly via `MINIO_SECRET_KEY_FILE` (no env var, no `entrypoint-wrapper.sh`). Lowest foot-gun surface.
- **`nohup`**: spawns `java -jar` directly via `nohup`. Operator sets `MINIO_ACCESS_KEY` + `MINIO_SECRET_KEY_FILE` (pointing at `docker/services/secrets/sa-<module>.key`) per launch. Used when Docker is unavailable or for ad-hoc debugging.
- **`systemd`**: uses the pre-installed `maple-{module}.service` units. Modules run as `maple` user, log to `/var/log/maple/`. Best for persistent / production-like environments.

Set `START_MODE=systemd` to switch. Default is `docker`. The systemd units assume a previous `scripts/install-systemd-units.sh` run; see `scripts/systemd/` for the unit files.

## Workflow

### 1. Pre-check

```bash
# Stop stale docker app containers first. Their published ports (8081-8084)
# are iptables DNAT, not host bind() — lsof below won't see them, so a stale
# container would silently hold the port and the new `compose up` would fail
# or attach to the old one. `stop` (not `down`) preserves infra containers.
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qE '^maple-(external-api|calculator|synchronizer|cleanup)$'; then
  echo "Stopping stale docker app containers"
  docker compose -f docker-compose.yml -f docker-compose.services.yml stop \
    external-api calculator synchronizer cleanup 2>/dev/null
fi

# Kill any stale HOST processes on required ports (nohup/systemd mode; docker
# containers handled above).
# -sTCP:LISTEN filters to the listener only — bare `lsof -ti:PORT` returns
# every PID with any TCP connection to that port (including clients on
# other ports that happen to be connected to PORT), and `kill $pid` would
# SIGTERM those too. Verified 2026-06-19: calc (port 8082) had an HTTP
# connection to ext-api (port 8081), so `lsof -ti:8081` returned both
# PIDs and `kill` took out calc alongside ext-api.
for port in 8081 8082 8083 8084 8180; do
  pid=$(lsof -ti:$port -sTCP:LISTEN 2>/dev/null)
  if [ -n "$pid" ]; then
    echo "Killing stale process on port $port: $pid"
    kill -9 $pid 2>/dev/null
  fi
done

# Verify .env exists
test -f .env || { echo "ERROR: .env not found"; exit 1; }
```

#### 1a. MinIO pre-check (required)

Pipeline test uses MinIO. All four env vars must be set and MinIO must be reachable.

```bash
# MinIO env vars must be set
: "${MINIO_ENDPOINT:?MINIO_ENDPOINT required for pipeline test}"
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER required for pipeline test}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD required for pipeline test}"
: "${MINIO_BUCKET:?MINIO_BUCKET required for pipeline test}"

# MinIO ready
curl -sf "${MINIO_ENDPOINT}/minio/health/ready" > /dev/null || { echo "MinIO not ready"; exit 2; }

# Bucket + lifecycle
mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
mc ls "local/${MINIO_BUCKET}/" >/dev/null || { echo "Bucket ${MINIO_BUCKET} missing"; exit 2; }
rule_count=$(mc ilm ls "local/${MINIO_BUCKET}/" 2>&1 | grep -cE "(Enabled|Disabled)" || true)
[ "${rule_count}" -ge 4 ] || { echo "Need >= 4 lifecycle rules, found ${rule_count}"; exit 2; }
```

**Local storage (legacy)**: Set `STORAGE_BACKEND=local` in `.env` to fall back to the local filesystem backend. The MinIO pre-check above is skipped, and module health will not show `minioHealthIndicator`. The MinIO prefix + storage-error verifications (steps 4a, 9a) are also skipped.

### 1b. Start docker services (docker-first)

All control-plane + storage infrastructure runs in Docker (Airflow, Kafka, MinIO, Airflow DB). Bring these up **before** Spring Boot modules boot so MinIO healthchecks pass at Spring Boot startup and Airflow is ready to trigger.

```bash
# Compose order matters: airflow-db → airflow-webserver/scheduler (healthcheck-gated);
# minio + kafka are independent.
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d \
  airflow-db airflow-webserver airflow-scheduler minio kafka

# Wait for Airflow DB
until docker inspect maple-airflow-db --format '{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; do
  sleep 2
done

# Wait for Airflow webserver
until curl -sf http://localhost:8180/health > /dev/null 2>&1; do sleep 3; done
echo "Airflow ready on 8180"

# Wait for MinIO (only if STORAGE_BACKEND=minio)
if [ "${STORAGE_BACKEND:-minio}" != "local" ]; then
  : "${MINIO_ENDPOINT:?MINIO_ENDPOINT required}"
  until curl -sf "${MINIO_ENDPOINT}/minio/health/ready" > /dev/null 2>&1; do sleep 2; done
  echo "MinIO ready at ${MINIO_ENDPOINT}"
fi
```

**Why docker-first:** Spring Boot modules' `MinioHealthIndicator` (step 4a) probes MinIO at boot. If MinIO is down, modules boot but report DOWN → fail-fast loop. Booting docker first guarantees clean Spring Boot startup. Airflow is also up before step 5 DAG trigger, so trigger / sensor / cleanup_pipeline chain has no cold-start gap.

### 2. Build JARs

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar :module-cleanup:bootJar --parallel
```

JAR locations: `module-{name}/build/libs/module-{name}-0.0.1-SNAPSHOT.jar`

### 3. Start modules (sequential, wait for health check)

```bash
START_MODE="${START_MODE:-docker}"

if [ "${START_MODE}" = "docker" ]; then
  set -a && source .env && set +a

  # Ensure SA secrets exist (bootstrap writes them); fail early if not.
  if ! ls docker/services/secrets/sa-*.key >/dev/null 2>&1; then
    echo "ERROR: docker/services/secrets/ empty. Run 'docker compose -f docker-compose.yml run --rm minio-bootstrap' first." >&2
    exit 7
  fi

  # Ensure docker-mode infra files exist. Added by PR #1324 (commit 556017399,
  # 2026-06-22). A working tree on an older commit (e.g. detached HEAD before
  # 556017399) has no `docker-compose.services.yml` and `docker compose ... up`
  # below silently fails with "no such file" instead of a clear error.
  # Verified 2026-06-23: main worktree on 4be816e99 hit this after PR #1331
  # merged because the local `develop` ref was stale relative to origin/develop.
  for f in docker-compose.services.yml docker/services/build.sh; do
    if [ ! -f "$f" ]; then
      echo "ERROR: $f missing. PR #1324 (commit 556017399) added docker-mode infra." >&2
      echo "Fix one of:" >&2
      echo "  - Switch to latest develop:  git checkout origin/develop" >&2
      echo "  - Use nohup mode instead:    START_MODE=nohup $0" >&2
      exit 7
    fi
  done

  # Build images if missing or stale.
  ./docker/services/build.sh

  # Bring up only the 4 services; infra (minio, postgres, kafka, etc.)
  # is owned by the base docker-compose.yml and starts independently.
  docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
    external-api calculator synchronizer cleanup

  # Health check wait.
  for port in 8081 8082 8083 8084; do
    until curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; do sleep 2; done
    echo "${port} ready"
  done

elif [ "${START_MODE}" = "nohup" ]; then
  set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local && export MALLOC_ARENA_MAX=1

  # Use .env DB_URL directly — VS3 / MinIO pipeline test runs against whichever DB
  # .env points at (typically the dev cloud DB for shared MinIO validation).
  # No local PostgreSQL override.

  # Per-module MinIO SA secret. StorageConfig reads the file directly via
  # MINIO_SECRET_KEY_FILE (default /run/secrets/sa-<module>, which doesn't
  # exist on a nohup host — we override per launch below). The SA name
  # (MINIO_ACCESS_KEY) is a public identifier. Secret files are written by
  # docker/minio/bootstrap.sh to docker/services/secrets/sa-<module>.key.
  SECRETS_DIR=docker/services/secrets
  # Sanity check: bootstrap must have run.
  for mod in ext-api calculator synchronizer cleanup; do
    if [ ! -f "${SECRETS_DIR}/sa-${mod}.key" ]; then
      echo "ERROR: ${SECRETS_DIR}/sa-${mod}.key missing — run docker compose run --rm minio-bootstrap first" >&2
      exit 1
    fi
  done

  # RUN_VIA_AIRFLOW=1 disables ext-api run-on-startup so Airflow is the only
  # trigger source. Default (unset) keeps run-on-startup=true (local profile
  # default) — pipeline starts immediately on boot. Use the flag for explicit
  # Airflow-driven test runs to avoid a race between bootRun and DAG trigger.
  RUN_VIA_AIRFLOW_FLAG=""
  if [ "${RUN_VIA_AIRFLOW:-0}" = "1" ]; then
    RUN_VIA_AIRFLOW_FLAG="-Dexternal-api.schedule.run-on-startup=false -Dexternal-api.schedule.enabled=true"
    echo "run-on-startup disabled — Airflow will trigger"
  fi

  # 1) External API (8081) — heap budget is the tightest of the four modules.
  # char-basic + item-equipment phases run concurrently with the OCID lookup
  # cache. Heap benchmark: -Xmx1g → major GC fires every 2.4s, 22% CPU on GC,
  # 102 files/s; -Xmx2g → GC 7%, 150 files/s. Verified 2026-06-16.
  MINIO_ACCESS_KEY=ext-api MINIO_SECRET_KEY_FILE="${SECRETS_DIR}/sa-ext-api.key" \
    nohup java -Xms512m -Xmx2g ${RUN_VIA_AIRFLOW_FLAG} -jar module-external-api/build/libs/module-external-api-0.0.1-SNAPSHOT.jar > logs/pipeline-test-external-api.log 2>&1 &
  until curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; do sleep 2; done
  echo "external-api ready on 8081"

  # 2) Calculator (8082)
  MINIO_ACCESS_KEY=calculator MINIO_SECRET_KEY_FILE="${SECRETS_DIR}/sa-calculator.key" \
    nohup java -Xms512m -Xmx1g -jar module-calculator/build/libs/module-calculator-0.0.1-SNAPSHOT.jar > logs/pipeline-test-calculator.log 2>&1 &
  until curl -sf http://localhost:8082/actuator/health > /dev/null 2>&1; do sleep 2; done
  echo "calculator ready on 8082"

  # 3) Synchronizer (8083)
  MINIO_ACCESS_KEY=synchronizer MINIO_SECRET_KEY_FILE="${SECRETS_DIR}/sa-synchronizer.key" \
    nohup java -Xms512m -Xmx1g -jar module-synchronizer/build/libs/module-synchronizer-0.0.1-SNAPSHOT.jar > logs/pipeline-test-synchronizer.log 2>&1 &
  until curl -sf http://localhost:8083/actuator/health > /dev/null 2>&1; do sleep 2; done
  echo "synchronizer ready on 8083"

  # 4) Cleanup (8084) — consumes synchronizer.chunk.consumed + runs artifact GC
  # -Dstorage.backend=minio is REQUIRED: StorageConfig's @ConditionalOnProperty
  # has matchIfMissing=true, so a missing/unset property silently falls back to
  # LocalFsObjectStorage which reads from ../data/runs/ (empty when MinIO is
  # active). Symptom: cleanup endpoint returns runsDeleted=0 / "no runs found"
  # while the MinIO bucket actually holds hundreds of old runs. The explicit
  # -D flag prevents env-var propagation failures from breaking cleanup silently.
  MINIO_ACCESS_KEY=cleanup MINIO_SECRET_KEY_FILE="${SECRETS_DIR}/sa-cleanup.key" \
    nohup java -Xms512m -Xmx1g -Dstorage.backend=minio -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/pipeline-test-cleanup.log 2>&1 &
  until curl -sf http://localhost:8084/actuator/health > /dev/null 2>&1; do sleep 2; done
  echo "cleanup ready on 8084"
else
  # systemd mode — units are pre-installed (see scripts/systemd/ + install-systemd-units.sh)
  # -Dstorage.backend=minio is baked into maple-cleanup.service ExecStart, so not passed here.
  for svc in maple-external-api maple-calculator maple-synchronizer maple-cleanup; do
    sudo systemctl start "${svc}"
  done
  for port in 8081 8082 8083 8084; do
    until curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; do sleep 2; done
  done
  echo "all 4 modules ready (systemd mode)"
fi
```

In systemd mode, the `-Dstorage.backend=minio` flag is baked into the `maple-cleanup.service` unit (ExecStart), so it's not passed at runtime.

**Why `java -jar` not `bootRun`:** `bootRun` inherits Gradle daemon lifecycle — can SIGKILL after long runs (exit 137). `java -jar` is stable for multi-hour pipeline runs. Heap budget: ext-api gets `-Xmx2g` (GC-bound at 1g, verified 2026-06-16), the other three stay at `-Xmx1g`. Total ~5GB across 4 JVMs vs default ~17GB.

### 4. Verify internal API endpoints

After modules are up, verify the Airflow integration endpoints before monitoring:

```bash
# Check run-status (should show current run from run-on-startup)
curl -s http://localhost:8081/api/internal/run-status | python3 -m json.tool

# Expected response structure:
# {
#   "current": {
#     "runId": "uuid",
#     "phase": "RANKING_FETCH",
#     "terminal": false,
#     ...
#   },
#   "lastCompleted": null
# }
```

**Pipeline phases in order:**
1. `RANKING_FETCH` — fetch ranking data from Nexon API
2. `OCID_LOOKUP` — resolve OCIDs for ranked characters
3. `CHARACTER_BASIC` — fetch character basic info
4. `COMPLETED` / `FAILED` — terminal states (`terminal: true`)

**Trigger endpoint** (for Airflow or manual trigger):
```bash
# Fire-and-forget trigger with run ID correlation
curl -s -X POST http://localhost:8081/api/internal/trigger/daily \
  -H "X-Airflow-Run-Id: test-run-001" | python3 -m json.tool

# Expected: {"status": "STARTED", "runId": "test-run-001"}
# 409 if already running: {"status": "ALREADY_RUNNING", "runId": "..."}
```

**Trigger header semantics — ext-api** (`InternalApiController.kt:62`):
```kotlin
@PostMapping("/trigger/daily")
fun triggerDailyRefresh(
    @RequestHeader("X-Airflow-Run-Id", required = false) airflowRunId: String?,
): ResponseEntity<Map<String, String>> {
    ...
    val runId = airflowRunId ?: UUID.randomUUID().toString()
    executor.submit { scheduler.triggerDailyRefresh(runId) }
    return ResponseEntity.accepted().body(mapOf("status" to "STARTED", "runId" to runId))
}
```

- `X-Airflow-Run-Id` is **optional**. If present, ext-api uses it as the runId; if absent, ext-api generates a UUID. The header is meant for **manual curl correlation** in tests like the example above.
- The Airflow DAG itself does **not** send the header (see `trigger_daily_collection_fn` in `docker/airflow/dags/daily_collection_pipeline.py`). It calls `requests.post(f"{base}/api/internal/trigger/daily")` with no header, so ext-api generates a UUID. The DAG then captures the runId from the response body via xcom and uses it for downstream correlation (wait_for_completion sensor + wait_for_item_equipment_cycle Kafka filter).
- Consequence: do not assume the Airflow runId (`manual__2026-06-19T...`) equals the ext-api runId (`20260619-152651-...`). They are independent IDs. To correlate, use the runId returned in the trigger response body, not the Airflow runId.

**Disabling run-on-startup** (for Airflow-only trigger tests):
- `local` profile default: `external-api.schedule.run-on-startup=true` — ext-api starts a run automatically on boot.
- To make Airflow the only trigger source, restart ext-api with `-Dexternal-api.schedule.run-on-startup=false` and `-Dexternal-api.schedule.enabled=true` (the latter keeps the scheduler bean alive so Airflow can still trigger via `POST /api/internal/trigger/daily`).
- The skill's `START_MODE=nohup` block sources this via `RUN_VIA_AIRFLOW=1` env var (added 2026-06-19).
- Symptom of missing override: `trigger_daily_collection` task returns 409 ALREADY_RUNNING because the bootRun run is already active when the DAG tries to trigger. Verify by checking `curl /api/internal/run-status` right after ext-api boots — if `current` is non-null, run-on-startup fired.

#### 4a. MinioHealthIndicator verification (required)

For each of the 5 modules (8081, 8082, 8083, 8080, 8084), the `/actuator/health` response must contain `status: "UP"` AND a `minioHealthIndicator` component with `status: "UP"`. The JSON key is verified at pre-flight (Spring derives the bean name in lowerCamelCase from `@Component class MinioHealthIndicator`).

```bash
for port in 8081 8082 8083 8080 8084; do
  body=$(curl -s "http://localhost:${port}/actuator/health")
  overall=$(echo "${body}" | jq -r '.status')
  # When @Import'd, the bean name is FQCN-based and Spring trims "HealthIndicator"
  # suffix for the JSON key. Look up the actual key dynamically instead of hardcoding.
  minio_key=$(echo "${body}" | jq -r '.components | keys[] | select(test("Minio"))' 2>/dev/null | head -1)
  minio_status=$(echo "${body}" | jq -r ".components[\"${minio_key}\"].status // \"MISSING\"")
  if [ "${overall}" != "UP" ] || [ "${minio_status}" != "UP" ]; then
    echo "Module on port ${port}: overall=${overall}, minio=${minio_status}"; exit 4
  fi
done
```

If `STORAGE_BACKEND=local`, this step is skipped.

#### 4b. Cleanup module storage backend verification (required)

`module-cleanup` can silently fall back to `LocalFsObjectStorage` (which reads from an empty `../data/runs/`) even when the rest of the pipeline writes to MinIO. After step 4a, hit the cleanup endpoint and inspect the log — `runsDeleted=0` alone is NOT a failure (keep-recent=5 may legitimately keep everything), but `no runs found at prefix=runs` in the log is the LocalFs fallback signature.

```bash
# Trigger one cleanup cycle, then inspect the log for the fallback signature.
curl -s -X POST http://localhost:8084/api/internal/cleanup/runs > /dev/null
sleep 3
# Healthy: log shows "started prefix=runs dryRun=false" followed by either
#          "candidates: N of M scanned" (M >= 1) or "no runs to delete"
#          (M > 0 scanned, keep-recent=5 holds everything — normal).
# Broken:  log shows "no runs found at prefix=runs" — LocalFs reading empty
#          ../data/runs/ (StorageConfig matchIfMissing=true default).
last=$(grep -E "started prefix=|candidates:|no runs" logs/pipeline-test-cleanup.log | tail -3)
echo "${last}"
if echo "${last}" | grep -q "no runs found at prefix=runs"; then
  echo "Cleanup fell back to LocalFsObjectStorage (StorageConfig matchIfMissing=true)"
  echo "Fix: restart module-cleanup with -Dstorage.backend=minio (step 3)"
  exit 4
fi
```

### 5. Configure Airflow + trigger DAG

Airflow is the control plane for pipeline scheduling and monitoring. **Already running** from step 1b (docker-first) — this step only configures connections and triggers the DAG.

```bash
# Install Kafka Python client (needed for SNAPSHOT_RUN_COMPLETED event consumption).
# Both scheduler AND webserver: webserver scans DAGS_FOLDER at request time and
# imports each .py to list DAGs; per-phase DAGs (character_basic_pipeline,
# item_equipment_pipeline, etc.) import kafka via phase_pipeline_factory, so
# missing kafka in webserver → 5 import errors → those DAGs invisible in web UI
# (scheduler still shows them via serialized_dag table). Verified 2026-06-23.
docker exec maple-airflow-scheduler python3 -m pip install kafka-python-ng --quiet
docker exec maple-airflow-webserver python3 -m pip install kafka-python-ng --quiet

# Initialize Airflow DB and connections (first run only)
docker exec maple-airflow-scheduler airflow db migrate
docker exec maple-airflow-scheduler airflow users create \
  --username admin --password admin --firstname Admin --lastname Admin --role Admin --email admin@example.com

# Connections (idempotent — delete first to avoid duplicates)
#
# Why `localhost` not `host.docker.internal`: maple-airflow-* containers use
# `network_mode: host` (see docker-compose.airflow.yml). In host network mode
# the container shares the host's network namespace, so `localhost` IS the
# host. `host.docker.internal` is a Docker-bridge-only DNS entry and does not
# resolve in host-mode containers. Verified 2026-06-17: setting host to
# host.docker.internal makes HttpSensor return HTTP 000 → DAG fails. Setting
# to localhost returns HTTP 200 → DAG succeeds.
docker exec maple-airflow-scheduler airflow connections delete external_api 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http --conn-host external-api --conn-port 8081 --conn-schema http
docker exec maple-airflow-scheduler airflow connections delete calculator 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add calculator \
  --conn-type http --conn-host calculator --conn-port 8082 --conn-schema http
# cleanup connection — REQUIRED for daily_cleanup_pipeline's HttpSensor.
# Without it, the sensor pokes fail and the entire DAG marks failed
# (all 3 cleanup tasks go upstream_failed). Verified 2026-06-17.
docker exec maple-airflow-scheduler airflow connections delete cleanup 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add cleanup \
  --conn-type http --conn-host cleanup --conn-port 8084 --conn-schema http

# Unpause all DAGs
docker exec maple-airflow-scheduler airflow dags unpause daily_collection_pipeline
docker exec maple-airflow-scheduler airflow dags unpause daily_cleanup_pipeline

# Verify #1292 per-phase branch is loaded (only after branch_on_scope merged).
# Without this guard the section silently no-ops and operators think the
# per-phase wiring is broken. Fail-fast on missing branch_on_scope task.
if docker exec maple-airflow-scheduler airflow tasks list daily_collection_pipeline 2>/dev/null | grep -q '^branch_on_scope$'; then
  echo "per-phase branch loaded: branch_on_scope present"
else
  echo "WARNING: branch_on_scope not in daily_collection_pipeline — issue #1292 branch not deployed"
fi

# Trigger DAG manually — full pipeline (default):
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline

# Trigger with steps:PHASE[,PHASE,...] skill arg — ordered sequence:
# Skill args: RANKING_FETCH, OCID_LOOKUP, CHARACTER_BASIC, ITEM_EQUIPMENT,
#             CHARACTER_BASIC_LOOP, ITEM_EQUIPMENT_LOOP.
# Mapping: _LOOP suffix → action=loop; bare phase → action=trigger.
# Default (PIPELINE_STEPS unset) → full pipeline as above.
STEPS="${PIPELINE_STEPS:-}"
if [ -n "$STEPS" ]; then
  DAG_CONF=$(python3 -c "
import json, sys
LOOP_SUFFIX = '_LOOP'
LOOP_PHASES = {'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
TRIGGER_PHASES = {'RANKING_FETCH', 'OCID_LOOKUP', 'CHARACTER_BASIC', 'ITEM_EQUIPMENT'}
def to_step(p):
    p = p.strip()
    if p.endswith(LOOP_SUFFIX):
        base = p[:-len(LOOP_SUFFIX)]
        if base not in LOOP_PHASES:
            sys.stderr.write(f'ERROR: loop not allowed on {base}. Loopable: {sorted(LOOP_PHASES)}\n')
            sys.exit(2)
        return {'action':'loop','phase':base}
    if p not in TRIGGER_PHASES:
        sys.stderr.write(f'ERROR: unknown phase {p}. Allowed: {sorted(TRIGGER_PHASES | {p+LOOP_SUFFIX for p in LOOP_PHASES})}\n')
        sys.exit(2)
    return {'action':'trigger','phase':p}
steps = [s for s in (to_step(p) for p in sys.argv[1].split(',')) if s]
print(json.dumps({'steps': steps}))
" "$STEPS")
  docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline -c "$DAG_CONF"
fi

# Monitor DAG run
docker exec maple-airflow-scheduler airflow dags list-runs -d daily_collection_pipeline
```

#### 5b. Skill arg `steps:PHASE[,PHASE,...]`

Accept a comma-separated phase list at skill entry. The skill forwards it as `dag_run.conf['steps']` JSON to the DAG. Default (no arg): run the full daily pipeline as today.

Mapping:

| Skill arg | dag_run.conf step |
|-----------|-------------------|
| `RANKING_FETCH` | `{"action":"trigger","phase":"RANKING_FETCH"}` |
| `OCID_LOOKUP` | `{"action":"trigger","phase":"OCID_LOOKUP"}` |
| `CHARACTER_BASIC` | `{"action":"trigger","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT` | `{"action":"trigger","phase":"ITEM_EQUIPMENT"}` |
| `CHARACTER_BASIC_LOOP` | `{"action":"loop","phase":"CHARACTER_BASIC"}` |
| `ITEM_EQUIPMENT_LOOP` | `{"action":"loop","phase":"ITEM_EQUIPMENT"}` |

Each step runs sequentially: trigger steps wait for terminal state, loop steps are fire-and-forget (DAG advances to cleanup_pipeline after the loop step). The skill fails fast (exit 2) before triggering Airflow if an invalid phase or `OCID_LOOKUP_LOOP` style is supplied.

**Note:** Services are now containerized (`docker compose ... services.yml`, default `START_MODE=docker`). Airflow containers run `network_mode: host`, so they reach the app containers via the published host ports (`localhost:8081` etc.), not Docker bridge DNS. Step 5 sets Airflow connections to `--conn-host external-api` (Docker DNS) — that only resolves if Airflow is on `maple-network` bridge, which conflicts with its host-network mode. See issue #1435 (airflow host/bridge network reconcile) for the unresolved gap.

#### 5a. Airflow trigger flow (daily_collection_pipeline)

The DAG does **not** use `HttpOperator` for the trigger step. It uses a `PythonOperator` because ext-api returns 409 CONFLICT as an idempotent success, and `HttpOperator`'s `HttpHook.run()` calls `response.raise_for_status()` BEFORE the `response_check` callback is reachable. The PythonOperator uses `requests` directly so 200/202/409 can all be accepted as success. Source: `docker/airflow/dags/daily_collection_pipeline.py:61-115`.

```text
airflow dags trigger daily_collection_pipeline
  └─► check_external_api (HttpSensor, /actuator/health)
        └─► trigger_daily_collection (PythonOperator)
              ├─ POST /api/internal/trigger/daily   (no X-Airflow-Run-Id header)
              │     └─► ext-api generates UUID runId, returns {"status":"STARTED","runId":"<UUID>"}
              ├─ xcom push: trigger response body (runId captured)
              └─ 409 ALREADY_RUNNING?  GET /run-status, capture current.runId
        └─► wait_for_completion (PythonSensor, mode=reschedule, 4h timeout)
              └─► Polls /run-status every 60s. pokes True when:
                    current.runId == xcom.runId  AND  current.terminal == true
                    (avoids matching a stale run if ext-api rotated to a new one)
        └─► wait_for_item_equipment_cycle (PythonOperator, 2h timeout)
              └─► Consumes Kafka topic synchronizer.chunk.consumed
                    Filters by event.runId == xcom.runId
                    Returns when event.endpoint == "item-equipment"
                    (one cycle = one full item-equipment sweep)
        └─► trigger_cleanup_pipeline (TriggerDagRunOperator)
              └─► Triggers daily_cleanup_pipeline (artifact GC) and unblocks
```

Key correlation rules:
- The `xcom.runId` is the **ext-api runId** (UUID generated server-side), NOT the Airflow runId. Use the ext-api runId to query `curl /api/internal/run-status | jq .current.runId`.
- The `wait_for_completion` sensor reads `current.runId` from `/run-status` and compares against xcom — so even if a previous run is still active, the sensor will only declare success when THIS runId hits terminal.
- `wait_for_item_equipment_cycle` does NOT call ext-api — it consumes from the Kafka `synchronizer.chunk.consumed` topic, which is published by `module-synchronizer` after each chunk lands in the read model. The runId filter ensures we wait for the cycle that belongs to the run we just triggered, not an earlier leftover.
- If `trigger_daily_collection` returns 409, the DAG still treats the trigger as success (idempotent) and uses the active runId from `/run-status`. This is intentional: rerunning a DAG that already triggered a run should not fail the pipeline.

Per-phase branch (issue #1292) is parallel-wired via `branch_on_scope` → `per_phase_*` tasks; see step 10a for verification.

### 6. Monitor pipeline progress

**Using run-status endpoint** (preferred — structured JSON):
```bash
curl -s http://localhost:8081/api/internal/run-status | python3 -c "
import sys, json
d = json.load(sys.stdin)
c = d.get('current') or {}
print(f'Phase: {c.get(\"phase\",\"IDLE\")}  RunId: {c.get(\"runId\",\"N/A\")[:8]}...  Terminal: {c.get(\"terminal\",False)}')
if c.get('chunksProcessed'): print(f'Chunks: {c[\"chunksProcessed\"]}  Records: {c[\"recordsProcessed\"]}')
if c.get('errorMessage'): print(f'Error: {c[\"errorMessage\"]}')
lc = d.get('lastCompleted')
if lc: print(f'Last completed: {lc[\"runId\"][:8]}... phase={lc[\"phase\"]}')
"
```

**Log-based monitoring** (fallback):

Phase 1: Ranking fetch (~4 min)
```bash
grep "RankingFetch.*progress\|RankingFetch.*complete" <(docker logs maple-external-api 2>&1) | tail -5
```

Phase 2: OCID lookup (~25 min)
```bash
grep "OCID lookup.*elapsed\|OCID lookup.*complete" <(docker logs maple-external-api 2>&1) | tail -3
# Typical: rate=400files/s
```

Phase 3: Character basic fetch (~40 min)
```bash
grep "character-basic.*elapsed\|character-basic.*run-completed" <(docker logs maple-external-api 2>&1) | tail -3
# Typical: rate=250files/s
```

Phase 4: Item equipment fetch (~35 min)
```bash
grep "item-equipment.*elapsed\|item-equipment.*run-completed" <(docker logs maple-external-api 2>&1) | tail -3
# Typical: rate=150files/s
```

Calculator processing (~5 min)
```bash
grep "processed chunk" <(docker logs maple-calculator 2>&1) | tail -5
```

Synchronizer sync (concurrent with phases 3-4)
```bash
grep "BasicSync.*chunk processed\|upsert done" <(docker logs maple-synchronizer 2>&1) | tail -5
```

### 7. Prometheus metrics & throughput monitoring

Poll metrics at regular intervals (every 5-10 min) during pipeline execution.

**Note:** external-api Prometheus endpoint requires auth (401). Use run-status endpoint or log-based metrics instead.

**Calculator metrics (port 8082):**
```bash
# Total users processed (cumulative)
curl -s http://localhost:8082/actuator/prometheus | grep calculator_users_processed_total | grep -v '^#'

# Total items processed & calculated (cumulative)
curl -s http://localhost:8082/actuator/prometheus | grep calculator_items_processed_total | grep -v '^#'
curl -s http://localhost:8082/actuator/prometheus | grep calculator_items_calculated_total | grep -v '^#'
curl -s http://localhost:8082/actuator/prometheus | grep calculator_items_errored_total | grep -v '^#'

# Users per second (real-time gauge)
curl -s http://localhost:8082/actuator/prometheus | grep calculator_chunk_users_per_second | grep -v '^#'

# Items per second (real-time gauge)
curl -s http://localhost:8082/actuator/prometheus | grep calculator_chunk_items_per_second | grep -v '^#'

# Total chunks processed / failed / skipped
curl -s http://localhost:8082/actuator/prometheus | grep calculator_chunks_ | grep -v '^#'

# Result JSON rows (cumulative)
curl -s http://localhost:8082/actuator/prometheus | grep calculator_result_json_rows_total | grep -v '^#'
```

**Synchronizer metrics (port 8083):**
```bash
# Total documents synced
curl -s http://localhost:8083/actuator/prometheus | grep synchronizer_chunk_documents_count | grep -v '^#'

# Total sync duration (seconds)
curl -s http://localhost:8083/actuator/prometheus | grep synchronizer_chunk_duration_seconds_sum | grep -v '^#'

# Total JSON rows pre-upsert
curl -s http://localhost:8083/actuator/prometheus | grep synchronizer_pre_upsert_json_rows_total | grep -v '^#'
```

**External API throughput (from logs):**
```bash
# Per-phase rate from scheduler logs
grep "rate=" <(docker logs maple-external-api 2>&1) | tail -5
```

**DB row counts (parse `.env` `DB_URL` — do NOT hardcode host/creds):**
```bash
# Read DB_URL literally via grep (do NOT source .env — DB_ROOT_PASSWORD contains
# an unquoted '$' that bash corrupts). DB_URL has no '$', safe to parse.
DB_URL=$(grep -E '^DB_URL=' .env | head -1 | sed "s/^DB_URL=//; s/^'//; s/'$//")
H=$(echo "$DB_URL"|sed -n 's|.*://\([^:/]*\).*|\1|p')
P=$(echo "$DB_URL"|sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
N=$(echo "$DB_URL"|sed -n 's|.*/\([^?]*\).*|\1|p')
U=$(echo "$DB_URL"|sed -n 's|.*user=\([^&]*\).*|\1|p')
W=$(echo "$DB_URL"|sed -n 's|.*password=\([^&]*\).*|\1|p')
PGPASSWORD="$W" psql "host=$H port=$P user=$U dbname=$N sslmode=disable" -t -A -c "
SELECT
  (SELECT count(*) FROM character_basic_read_model) as basic,
  (SELECT count(*) FROM character_equipment_read_model) as equip,
  (SELECT count(*) FROM game_character WHERE ocid IS NOT NULL) as game_char;"
```

**Throughput reference (typical):**

| Module | Metric | Typical Rate |
|--------|--------|-------------|
| External API | OCID lookup | ~250-400 users/s |
| External API | character-basic fetch | ~250 users/s |
| External API | item-equipment fetch | ~150-160 users/s |
| Calculator | users/s | ~170 users/s |
| Calculator | items/s | ~11,000 items/s |
| Synchronizer | docs/s | ~200 docs/s |

### 8. Periodic status report

Every 5-10 minutes during pipeline execution, report:

**RSS monitoring (per poll):**
```bash
for port in 8081 8082 8083; do
  pid=$(lsof -ti:$port -sTCP:LISTEN 2>/dev/null)
  if [ -n "$pid" ]; then
    rss=$(ps -o rss= -p $pid 2>/dev/null)
    echo "port=$port pid=$pid rss=${rss}KB"
  fi
done
```

```
=== Pipeline Status Report ===
RunId: [from run-status endpoint]
Phase: [ranking | ocid-lookup | character-basic | item-equipment | calculator | complete]
Terminal: [true/false]

External API: [progress] (e.g., "OCID 338K/600K, rate=400/s")
Calculator: [users/s] [items/s] [chunks processed]
Synchronizer: [docs synced] [duration]
DB: basic=[N] equip=[N] game_char=[N]
RSS: external-api=[MB] calculator=[MB] synchronizer=[MB]
Errors: [0 or list]
```

### 9. Verify end-to-end result

```bash
# Query REST API for a known character
curl -s -w "\nHTTP %{http_code}" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

- 200 = success (data in read model)
- 202 = accepted (still processing, wait and retry)
- 404 = data not yet available

**Verify run-status shows completion:**
```bash
curl -s http://localhost:8081/api/internal/run-status | python3 -m json.tool
# Expected: current.phase = "COMPLETED", current.terminal = true
```

**Check logs for errors:**
```bash
for module in external-api calculator synchronizer; do
  errors=$(grep "ERROR" <(docker logs maple-${module} 2>&1) | tail -5)
  if [ -n "$errors" ]; then
    echo "=== ERRORS in ${module} ==="
    echo "$errors"
  fi
done
```

#### 9a. MinIO prefix + storage-error verification (required)

After the E2E returns 200/202, verify the expected objects exist under MinIO prefixes:

```bash
for prefix in snapshots runs ocid-mapping calculator/runs; do
  count=$(mc ls --recursive "local/${MINIO_BUCKET}/${prefix}/" 2>/dev/null | wc -l)
  [ "${count}" -gt 0 ] || { echo "Empty prefix ${prefix}/"; exit 5; }
done

# Storage-error scan
for module in external-api calculator synchronizer; do
  errs=$(grep -E "ObjectStorage|MinIO|S3" <(docker logs maple-${module} 2>&1) 2>/dev/null | grep -i "ERROR" | tail -5)
  [ -z "${errs}" ] || { echo "ObjectStorage ERROR in ${module}: ${errs}"; exit 5; }
done
```

### 10. Cleanup

**사용자가 명시적으로 종료를 요청할 때만 실행.** 파이프라인은 장시간 실행(~2시간)이므로 자동 종료하지 않음. 사용자가 "종료", "stop", "cleanup" 등을 말하면 실행.

### 10a. Per-phase scope verification (issue #1292)

Smoke verification that the per-phase Airflow branch (added in #1292) drives the ext-api per-phase endpoints end-to-end. Runs **after** the main daily E2E completes successfully. Skipped if `branch_on_scope` task is not present (pre-#1292 deployments).

**Prereq check:**
```bash
# Detect whether the per-phase branch is deployed in this DAG file.
# Without this guard the section silently no-ops and operators think the
# per-phase wiring is broken. Fail-fast on missing branch_on_scope task.
if ! docker exec maple-airflow-scheduler airflow tasks list daily_collection_pipeline 2>/dev/null | grep -q '^branch_on_scope$'; then
  echo "SKIP: branch_on_scope not in daily_collection_pipeline — issue #1292 branch not deployed"
  return 0 2>/dev/null || exit 0
fi
echo "Per-phase branch loaded; running scope verification"
```

**10a.1 — Single-phase trigger:**
```bash
# Trigger only ITEM_EQUIPMENT (skips the rest of the daily chain)
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT"]}'

# Wait up to 60s for ITEM_EQUIPMENT to become ACTIVE
for i in $(seq 1 30); do
  phase=$(curl -s http://localhost:8081/api/internal/run-status | jq -r '.current.phase // ""')
  if [ "${phase}" = "ITEM_EQUIPMENT" ]; then
    echo "ITEM_EQUIPMENT ACTIVE after ${i}*2s"; break
  fi
  sleep 2
done
[ "${phase}" = "ITEM_EQUIPMENT" ] || { echo "ITEM_EQUIPMENT scope trigger failed"; exit 6; }
```

****10a.2 — Loop start:**
```bash
# Start a continuous loop for ITEM_EQUIPMENT.
# Note: OCID_LOOKUP_LOOP is rejected by ext-api (400 INVALID_PHASE) despite
# earlier #1291 spec mention — only CHARACTER_BASIC and ITEM_EQUIPMENT are
# accepted by PhaseLoopController.loopablePhases (verified 2026-06-18).
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT_LOOP"]}'

# Wait up to 30s for loopId to appear in /run-status.loopSummaries
loop_id=""
for i in $(seq 1 15); do
  loop_id=$(curl -s http://localhost:8081/api/internal/run-status \
    | jq -r '.loopSummaries.ITEM_EQUIPMENT.loopId // ""')
  if [ -n "${loop_id}" ] && [ "${loop_id}" != "null" ]; then
    echo "Loop started: loopId=${loop_id}"; break
  fi
  sleep 2
done
[ -n "${loop_id}" ] && [ "${loop_id}" != "null" ] || { echo "Loop start failed"; exit 6; }
```

**10a.3 — Loop iteration progress:**
```bash
# Wait up to 90s for iterationCount > 0 (proves the loop is actually iterating)
iter=""
for i in $(seq 1 45); do
  iter=$(curl -s http://localhost:8081/api/internal/run-status \
    | jq -r '.loopSummaries.ITEM_EQUIPMENT.iterationCount // 0')
  if [ "${iter}" -gt 0 ] 2>/dev/null; then
    echo "Iteration count = ${iter} after ${i}*2s"; break
  fi
  sleep 2
done
[ "${iter}" -gt 0 ] 2>/dev/null || { echo "Loop did not iterate within 90s"; exit 6; }
```

**10a.4 — Loop stop:**
```bash
# Graceful stop via per-phase scope
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["ITEM_EQUIPMENT_STOP"]}'

# Wait up to 45s for status = STOPPED
status=""
for i in $(seq 1 23); do
  status=$(curl -s http://localhost:8081/api/internal/run-status \
    | jq -r '.loopSummaries.ITEM_EQUIPMENT.status // ""')
  if [ "${status}" = "STOPPED" ]; then
    echo "Loop STOPPED after ${i}*2s"; break
  fi
  sleep 2
done
[ "${status}" = "STOPPED" ] || { echo "Loop stop failed; status=${status}"; exit 6; }
```

**10a.5 — Invalid scope rejection:**
```bash
# RANKING_FETCH_LOOP must be rejected (not in #1291 loopablePhases)
trigger_output=$(docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline \
  -c '{"scope": ["RANKING_FETCH_LOOP"]}' 2>&1)
# The trigger CLI itself accepts the run; the parse_scope failure surfaces
# as branch_on_scope → AirflowException. Verify via task instance state:
sleep 10
task_state=$(docker exec maple-airflow-scheduler airflow tasks state-for-ti \
  daily_collection_pipeline "$(date -u +%Y-%m-%dT%H:%M:%S)" branch_on_scope 2>/dev/null \
  | jq -r '.state // "unknown"')
# Expected: "failed" (AirflowException raised by parse_scope)
[ "${task_state}" = "failed" ] || { echo "Invalid scope not rejected; state=${task_state}"; exit 6; }
echo "Invalid scope correctly rejected"
```

**10a.6 — Cleanup loop artifacts:**
```bash
# Ensure no orphan loop survives past this section (defensive — step 10a.4
# should have stopped it, but verify before reporting PASS).
loop_state=$(curl -s http://localhost:8081/api/internal/run-status \
  | jq -r '.loopSummaries.ITEM_EQUIPMENT.status // "NONE"')
case "${loop_state}" in
  STOPPED|NONE|"") echo "Loop cleanly stopped";;
  *) echo "WARNING: loop in unexpected state ${loop_state} after stop scope";;
esac
```

**Exit codes:**
- 0: all 6 sub-steps PASS
- 6: any sub-step FAILED (see error message above the exit)

```bash
# Stop Spring Boot services.
# -sTCP:LISTEN: see step 1 comment. Bare `lsof -ti:PORT` would also kill
# any client process connected to that port.
if [ "${START_MODE:-nohup}" = "nohup" ]; then

# Stop Spring Boot services
if [ "${START_MODE:-docker}" = "docker" ]; then
  docker compose -f docker-compose.yml -f docker-compose.services.yml stop \
    external-api calculator synchronizer cleanup
elif [ "${START_MODE:-docker}" = "nohup" ]; then
  for port in 8081 8082 8083 8084; do
    kill $(lsof -ti:$port -sTCP:LISTEN) 2>/dev/null
  done
else
  for svc in maple-external-api maple-calculator maple-synchronizer maple-cleanup; do
    sudo systemctl stop "${svc}"
  done
fi

# Stop Airflow
docker compose -f docker-compose.yml -f docker-compose.airflow.yml stop airflow-webserver airflow-scheduler
```

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Port already in use | `lsof -ti:PORT -sTCP:LISTEN` and kill (bare `-ti:PORT` returns client PIDs too — see step 1 comment) |
| Health check timeout (60s+) | Check logs for startup errors |
| Pipeline stuck at OCID lookup | Verify NEXON_API_KEY in .env, check rate limits |
| Calculator not processing | Check PGMQ queue depth, calculator logs |
| 202 but never 200 | Synchronizer may be down, check port 8083 |
| OOM / slow startup | Check JVM heap, reduce data volume |
| `uq_basic_read_model_ocid` error | Character rename causes same ocid under different user_ign — CharacterBasicRepository handles dedup |
| DB count = 0 but logs show success | Check the right DB — docker modules use `.env` `DB_URL` (services.yml interpolates it to `postgres:5432`); nohup `STORAGE_BACKEND=local` uses `localhost:5432/maple_expectation`. Parse via grep, don't hardcode. |
| External API 401 on Prometheus | Use run-status endpoint or log-based metrics |
| Trigger returns 409 | Pipeline already running — check run-status for current phase |
| Airflow can't reach services | Verify `host.docker.internal` in docker-compose.airflow.yml, check services are running on host |
| Airflow sensor false positive | Verify runId correlation — sensor checks `current.runId` matches trigger response |
| Airflow DB connection failed | Ensure maple-network exists: `docker network create maple-network` |
| Airflow webserver/scheduler restart-loop, logs show `connection to server at "172.20.0.X" port 5432 failed` | `docker-compose.airflow.yml` hardcodes `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` (and `KAFKA_BOOTSTRAP_SERVERS`) at a specific bridge IP (e.g. `172.20.0.2`). When `maple-airflow-db` is recreated or the bridge subnet shifts, the IP changes. `docker compose restart` does NOT pick up the new IP — env vars are baked into the running container. Fix: edit the compose file to the current IP, then `docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate airflow-webserver airflow-scheduler`. Verify current IP via `docker inspect maple-airflow-db --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'`. Verified 2026-06-19. |
| `trigger_daily_collection` task returns 409 ALREADY_RUNNING but no run was just triggered | ext-api's `run-on-startup=true` (local profile default) started a run on boot. Either wait for it to finish, or restart ext-api with `-Dexternal-api.schedule.run-on-startup=false -Dexternal-api.schedule.enabled=true` (the skill's `RUN_VIA_AIRFLOW=1` env var does this automatically). Verified 2026-06-19. |
| Step 10a loop never iterates | Verify `loopSummaries.ITEM_EQUIPMENT.loopId` set; if null, `/loop/phase/ITEM_EQUIPMENT` returned non-202 — check ext-api logs for `PhaseLoopController` errors |
| Step 10a loop stop timeout | `/stop/phase/ITEM_EQUIPMENT` did not propagate `PhaseStoppedException`; check ext-api logs for `PhaseStopSignal.requestStop` and downstream chunk boundary halt |
| Cleanup returns 0 / "no runs found" while MinIO has old runs | `module-cleanup` defaulted to `LocalFsObjectStorage` (reads empty `../data/runs/`). Restart with `-Dstorage.backend=minio` JVM flag. StorageConfig's `@ConditionalOnProperty` has `matchIfMissing=true` so an unset property silently picks local. |

## Notes

- JVM timezone is KST (UTC+9). Cron expressions in application.yml use KST.
- Airflow DAG uses UTC cron: `0 18 * * *` = KST 03:00.
- OCID JSONL file written to `../data/ocid-mapping/` after OCID lookup completes (~594K entries).
- Item equipment cycles take ~35 minutes per full run. Lock timeout is 1 hour.
- Do NOT run load tests alongside this pipeline test.
- Local profile DB: when `STORAGE_BACKEND=local`, the hardcoded `localhost:5432/maple_expectation` from `application-local.yml` is used. When `STORAGE_BACKEND=minio`, `.env` `DB_URL` is used directly (typically the dev cloud DB).
- `run-on-startup: true` in local profile starts pipeline immediately. When Airflow controls scheduling in production, set `run-on-startup: false` and `external-api.schedule.enabled: true` to keep the bean but disable auto-trigger.
- Services are containerized by default (`START_MODE=docker`). Airflow (`network_mode: host`) reaches them via `localhost:<published-port>`. The `host.docker.internal` / Docker-DNS path is not used in the current docker deploy; see #1435 for the airflow network reconcile gap.
- Per-phase scope verification (step 10a) only runs when the #1292 branch (`branch_on_scope` task) is present in `daily_collection_pipeline`. Pre-#1292 deployments skip the section silently — see step 10a prereq check for the gate.
- Per-phase verification adds ~5min (steps 10a.1–10a.5) to the full pipeline test. Run after the main E2E (step 9) succeeds; isolated failures don't affect main E2E pass/fail.

## Storage backend

Pipeline test uses MinIO by default (`STORAGE_BACKEND=minio`). The flow:

1. **MinIO pre-check** (step 1a) — `mc ready`, bucket existence, lifecycle rules count >= 4.
2. **MinioHealthIndicator in module health** (step 4a) — all 5 modules must report UP for the `minioHealthIndicator` component (key confirmed at pre-flight).
3. **MinIO prefixes after the E2E** (step 9a) — `snapshots/`, `runs/`, `ocid-mapping/`, `calculator/runs/` must be non-empty.
4. **Boots `module-cleanup`** (port 8084) — cleanup logic is consolidated there.
5. **Airflow is part of the workflow** (step 5) — the control plane still runs. `run-on-startup: true` in local profile starts the pipeline immediately on boot, sufficient for both smoke and full E2E.
6. **`.env` `DB_URL`** is used directly (no local PostgreSQL override) — VS3 / MinIO pipeline test runs against whichever DB `.env` points at (typically the dev cloud DB for shared MinIO validation).

### Local mode (legacy fallback)

Set `STORAGE_BACKEND=local` in `.env` to opt out of MinIO. All MinIO-specific steps (1a, 4a, 9a) are skipped, and module health will not show `minioHealthIndicator`. Local mode exists for the legacy pre-VS2 workflow; for the canonical pipeline test, use MinIO.

Detection: `STORAGE_BACKEND` env var. Default = `minio` for pipeline test, no flag needed.
