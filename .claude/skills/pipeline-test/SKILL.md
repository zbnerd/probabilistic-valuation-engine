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

- `.env` file exists with DB_URL, NEXON_API_KEY, etc.
- No existing processes on ports 8081, 8082, 8083, 8084, 8180
- PostgreSQL accessible at `localhost:5432/maple_expectation` (local profile)
- Docker Compose v2 for Airflow services
- Data directory `../data` clean for fresh runs

## Workflow

### 1. Pre-check

```bash
# Kill any stale processes on required ports
for port in 8081 8082 8083 8084 8180; do
  pid=$(lsof -ti:$port 2>/dev/null)
  if [ -n "$pid" ]; then
    echo "Killing stale process on port $port: $pid"
    kill -9 $pid 2>/dev/null
  fi
done

# Verify .env exists
test -f .env || { echo "ERROR: .env not found"; exit 1; }
```

#### 1a. MinIO pre-check (only when `STORAGE_BACKEND=minio`)

Skip this section entirely if `STORAGE_BACKEND` is unset or `local`.

```bash
# MinIO env vars must be set
: "${MINIO_ENDPOINT:?MINIO_ENDPOINT required when STORAGE_BACKEND=minio}"
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER required when STORAGE_BACKEND=minio}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD required when STORAGE_BACKEND=minio}"
: "${MINIO_BUCKET:?MINIO_BUCKET required when STORAGE_BACKEND=minio}"

# MinIO ready
curl -sf "${MINIO_ENDPOINT}/minio/health/ready" > /dev/null || { echo "MinIO not ready"; exit 2; }

# Bucket + lifecycle
mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null
mc ls "local/${MINIO_BUCKET}/" >/dev/null || { echo "Bucket ${MINIO_BUCKET} missing"; exit 2; }
rule_count=$(mc ilm ls "local/${MINIO_BUCKET}/" 2>&1 | grep -cE "(Enabled|Disabled)" || true)
[ "${rule_count}" -ge 4 ] || { echo "Need >= 4 lifecycle rules, found ${rule_count}"; exit 2; }
```

### 2. Build JARs

```bash
./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar :module-cleanup:bootJar --parallel
```

JAR locations: `module-{name}/build/libs/module-{name}-0.0.1-SNAPSHOT.jar`

### 3. Start modules (sequential, wait for health check)

```bash
set -a && source .env && set +a && export SPRING_PROFILES_ACTIVE=local && export MALLOC_ARENA_MAX=1

# Force local DB regardless of .env (dev cloud DB is for prod/prod-like envs only).
# Pipeline test runs against the local dev PostgreSQL on this host.
export DB_URL='jdbc:postgresql://localhost:5432/maple_expectation'
export SPRING_DATASOURCE_URL="$DB_URL"
export SPRING_DATASOURCE_USERNAME=maple
export SPRING_DATASOURCE_PASSWORD=maple123

# 1) External API (8081)
nohup java -Xms512m -Xmx1g -jar module-external-api/build/libs/module-external-api-0.0.1-SNAPSHOT.jar > logs/pipeline-test-external-api.log 2>&1 &
until curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "external-api ready on 8081"

# 2) Calculator (8082)
nohup java -Xms512m -Xmx1g -jar module-calculator/build/libs/module-calculator-0.0.1-SNAPSHOT.jar > logs/pipeline-test-calculator.log 2>&1 &
until curl -sf http://localhost:8082/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "calculator ready on 8082"

# 3) Synchronizer (8083)
nohup java -Xms512m -Xmx1g -jar module-synchronizer/build/libs/module-synchronizer-0.0.1-SNAPSHOT.jar > logs/pipeline-test-synchronizer.log 2>&1 &
until curl -sf http://localhost:8083/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "synchronizer ready on 8083"

# 4) Cleanup (8084) — consumes synchronizer.chunk.consumed + runs artifact GC
nohup java -Xms512m -Xmx1g -jar module-cleanup/build/libs/module-cleanup-0.0.1-SNAPSHOT.jar > logs/pipeline-test-cleanup.log 2>&1 &
until curl -sf http://localhost:8084/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "cleanup ready on 8084"
```

**Why `java -jar` not `bootRun`:** `bootRun` inherits Gradle daemon lifecycle — can SIGKILL after long runs (exit 137). `java -jar` is stable for multi-hour pipeline runs. `-Xmx1g` prevents OOM when running 4 JVMs concurrently (~4GB total vs default ~17GB).

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

#### 4a. MinioHealthIndicator verification (only when `STORAGE_BACKEND=minio`)

For each of the 5 modules (8081, 8082, 8083, 8080, 8084), the `/actuator/health` response must contain `status: "UP"` AND a `minioHealthIndicator` component with `status: "UP"`. The JSON key is verified at pre-flight (Spring derives the bean name in lowerCamelCase from `@Component class MinioHealthIndicator`).

```bash
for port in 8081 8082 8083 8080 8084; do
  body=$(curl -s "http://localhost:${port}/actuator/health")
  overall=$(echo "${body}" | jq -r '.status')
  minio_status=$(echo "${body}" | jq -r '.components.minioHealthIndicator.status // "MISSING"')
  if [ "${overall}" != "UP" ] || [ "${minio_status}" != "UP" ]; then
    echo "Module on port ${port}: overall=${overall}, minioHealthIndicator=${minio_status}"; exit 4
  fi
done
```

### 5. Start Airflow

Airflow is the control plane for pipeline scheduling and monitoring. Always start it as part of the pipeline test.

```bash
# Start Airflow services (requires docker compose + maple-network)
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d airflow-webserver airflow-scheduler

# Wait for Airflow webserver
until curl -sf http://localhost:8180/health > /dev/null 2>&1; do sleep 3; done
echo "Airflow ready on 8180"

# Install Kafka Python client (needed for SNAPSHOT_RUN_COMPLETED event consumption)
docker exec maple-airflow-scheduler python3 -m pip install kafka-python-ng --quiet

# Initialize Airflow DB and connections (first run only)
docker exec maple-airflow-scheduler airflow db migrate
docker exec maple-airflow-scheduler airflow users create \
  --username admin --password admin --firstname Admin --lastname Admin --role Admin --email admin@example.com

# Connections (idempotent — delete first to avoid duplicates)
docker exec maple-airflow-scheduler airflow connections delete external_api 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http --conn-host host.docker.internal --conn-port 8081 --conn-schema http
docker exec maple-airflow-scheduler airflow connections delete calculator 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add calculator \
  --conn-type http --conn-host host.docker.internal --conn-port 8082 --conn-schema http

# Unpause all DAGs
docker exec maple-airflow-scheduler airflow dags unpause daily_collection_pipeline
docker exec maple-airflow-scheduler airflow dags unpause daily_cleanup_pipeline

# Trigger DAG manually
docker exec maple-airflow-scheduler airflow dags trigger daily_collection_pipeline

# Monitor DAG run
docker exec maple-airflow-scheduler airflow dags list-runs -d daily_collection_pipeline
```

**Note:** Airflow connects to Spring Boot services via `host.docker.internal`. Services run on the host, Airflow runs in Docker. When services are containerized (Phase 3+), switch to Docker network DNS.

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
grep "RankingFetch.*progress\|RankingFetch.*complete" logs/pipeline-test-external-api.log | tail -5
```

Phase 2: OCID lookup (~25 min)
```bash
grep "OCID lookup.*elapsed\|OCID lookup.*complete" logs/pipeline-test-external-api.log | tail -3
# Typical: rate=400files/s
```

Phase 3: Character basic fetch (~40 min)
```bash
grep "character-basic.*elapsed\|character-basic.*run-completed" logs/pipeline-test-external-api.log | tail -3
# Typical: rate=250files/s
```

Phase 4: Item equipment fetch (~35 min)
```bash
grep "item-equipment.*elapsed\|item-equipment.*run-completed" logs/pipeline-test-external-api.log | tail -3
# Typical: rate=150files/s
```

Calculator processing (~5 min)
```bash
grep "processed chunk" logs/pipeline-test-calculator.log | tail -5
```

Synchronizer sync (concurrent with phases 3-4)
```bash
grep "BasicSync.*chunk processed\|upsert done" logs/pipeline-test-synchronizer.log | tail -5
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
grep "rate=" logs/pipeline-test-external-api.log | tail -5
```

**DB row counts (local PostgreSQL):**
```bash
PGPASSWORD=maple123 psql "host=localhost port=5432 user=maple dbname=maple_expectation" -t -A -c "
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
  pid=$(lsof -ti:$port 2>/dev/null)
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
  errors=$(grep "ERROR" logs/pipeline-test-${module}.log | tail -5)
  if [ -n "$errors" ]; then
    echo "=== ERRORS in ${module} ==="
    echo "$errors"
  fi
done
```

#### 9a. MinIO prefix + storage-error verification (only when `STORAGE_BACKEND=minio`)

After the E2E returns 200/202, verify the expected objects exist under MinIO prefixes:

```bash
for prefix in snapshots runs ocid-mapping calculator/runs; do
  count=$(mc ls --recursive "local/${MINIO_BUCKET}/${prefix}/" 2>/dev/null | wc -l)
  [ "${count}" -gt 0 ] || { echo "Empty prefix ${prefix}/"; exit 5; }
done

# Storage-error scan
for module in external-api calculator synchronizer; do
  errs=$(grep -E "ObjectStorage|MinIO|S3" logs/pipeline-test-${module}.log 2>/dev/null | grep -i "ERROR" | tail -5)
  [ -z "${errs}" ] || { echo "ObjectStorage ERROR in ${module}: ${errs}"; exit 5; }
done
```

### 10. Cleanup

**사용자가 명시적으로 종료를 요청할 때만 실행.** 파이프라인은 장시간 실행(~2시간)이므로 자동 종료하지 않음. 사용자가 "종료", "stop", "cleanup" 등을 말하면 실행.

```bash
# Stop Spring Boot services
for port in 8081 8082 8083 8084; do
  kill $(lsof -ti:$port) 2>/dev/null
done

# Stop Airflow
docker compose -f docker-compose.yml -f docker-compose.airflow.yml stop airflow-webserver airflow-scheduler
```

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Port already in use | `lsof -ti:PORT` and kill |
| Health check timeout (60s+) | Check logs for startup errors |
| Pipeline stuck at OCID lookup | Verify NEXON_API_KEY in .env, check rate limits |
| Calculator not processing | Check PGMQ queue depth, calculator logs |
| 202 but never 200 | Synchronizer may be down, check port 8083 |
| OOM / slow startup | Check JVM heap, reduce data volume |
| `uq_basic_read_model_ocid` error | Character rename causes same ocid under different user_ign — CharacterBasicRepository handles dedup |
| DB count = 0 but logs show success | Check correct DB — local profile uses `localhost:5432/maple_expectation`, not .env DB_URL |
| External API 401 on Prometheus | Use run-status endpoint or log-based metrics |
| Trigger returns 409 | Pipeline already running — check run-status for current phase |
| Airflow can't reach services | Verify `host.docker.internal` in docker-compose.airflow.yml, check services are running on host |
| Airflow sensor false positive | Verify runId correlation — sensor checks `current.runId` matches trigger response |
| Airflow DB connection failed | Ensure maple-network exists: `docker network create maple-network` |

## Notes

- JVM timezone is KST (UTC+9). Cron expressions in application.yml use KST.
- Airflow DAG uses UTC cron: `0 18 * * *` = KST 03:00.
- OCID JSONL file written to `../data/ocid-mapping/` after OCID lookup completes (~594K entries).
- Item equipment cycles take ~35 minutes per full run. Lock timeout is 1 hour.
- Do NOT run load tests alongside this pipeline test.
- Local profile DB is `localhost:5432/maple_expectation` (hardcoded in application-local.yml), NOT the remote DB from .env.
- `run-on-startup: true` in local profile starts pipeline immediately. When Airflow controls scheduling in production, set `run-on-startup: false` and `external-api.schedule.enabled: true` to keep the bean but disable auto-trigger.
- Airflow connects via `host.docker.internal` (Docker→host). This is transitional until services are containerized.

## MinIO mode (storage-backend awareness)

When `STORAGE_BACKEND=minio` is set, this skill:

1. **Runs the MinIO pre-check** (step 1a above) — `mc ready`, bucket existence, lifecycle rules count >= 4.
2. **Verifies `MinioHealthIndicator` in module health** (step 4a above) — all 5 modules must report UP for the `minioHealthIndicator` component (key confirmed at pre-flight).
3. **Inspects MinIO prefixes after the E2E** (step 9a above) — `snapshots/`, `runs/`, `ocid-mapping/`, `calculator/runs/` must be non-empty.
4. **Boots `module-cleanup`** (port 8084) — cleanup logic is consolidated there; the VS3 wrapper exercises the cleanup endpoint in step 7. (Original plan assumed cleanup was in the 4 data modules; pre-flight code analysis revealed the consolidation into module-cleanup.)
5. **Skips Airflow** (port 8180) — storage validation does not require the control plane. `run-on-startup: true` in local profile starts the pipeline immediately on boot, sufficient for the smoke E2E.
6. **Uses `.env` `DB_URL`** (not the local `localhost:5432/maple_expectation` hardcoded path) — VS3 runs against the dev cloud DB, not local. This split prevents local-only test runs from polluting the dev cloud DB.

Detection: `STORAGE_BACKEND=minio` env var is the trigger. No new flag is introduced.

When `STORAGE_BACKEND` is unset or `local`: all new MinIO checks are skipped; the existing local behavior (5 modules + Airflow + local PostgreSQL) is unchanged. Backward compatible.
