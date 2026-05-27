# Operations Manual

Runtime procedures for the Probabilistic Valuation Engine pipeline.

---

## 1. Startup

### Prerequisites

- `.env` file configured (DB_URL, NEXON_API_KEY, etc.)
- PostgreSQL accessible at `localhost:5432/maple_expectation` (local profile)
- No existing processes on ports 8081, 8082, 8083
- `logs/` directory exists

### Quick Start (JAR mode, recommended for long runs)

```bash
# Build
./gradlew :module-external-api:bootJar :module-calculator:bootJar :module-synchronizer:bootJar --parallel

# Load env + start
set -a && source .env && set +a
export SPRING_PROFILES_ACTIVE=local MALLOC_ARENA_MAX=1
mkdir -p logs

# Sequential start with health check
nohup java -Xms512m -Xmx1g -jar module-external-api/build/libs/module-external-api-0.0.1-SNAPSHOT.jar > logs/pipeline-test-external-api.log 2>&1 &
until curl -sf http://localhost:8081/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "external-api ready on 8081"

nohup java -Xms512m -Xmx1g -jar module-calculator/build/libs/module-calculator-0.0.1-SNAPSHOT.jar > logs/pipeline-test-calculator.log 2>&1 &
until curl -sf http://localhost:8082/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "calculator ready on 8082"

nohup java -Xms512m -Xmx1g -jar module-synchronizer/build/libs/module-synchronizer-0.0.1-SNAPSHOT.jar > logs/pipeline-test-synchronizer.log 2>&1 &
until curl -sf http://localhost:8083/actuator/health > /dev/null 2>&1; do sleep 2; done
echo "synchronizer ready on 8083"
```

### Dev Mode (bootRun, for development only)

```bash
set -a && source .env && set +a
./gradlew :module-external-api:bootRun    # Terminal 1
./gradlew :module-calculator:bootRun       # Terminal 2
./gradlew :module-synchronizer:bootRun     # Terminal 3
```

**Warning:** `bootRun` inherits Gradle daemon lifecycle. Long-running pipelines may get SIGKILL (exit 137). Use JAR mode for runs > 1 hour.

---

## 2. Shutdown

```bash
for port in 8081 8082 8083; do
  kill $(lsof -ti:$port) 2>/dev/null
done
```

SIGTERM is sufficient — Spring Boot graceful shutdown handles in-flight requests. If process won't stop:

```bash
kill -9 $(lsof -ti:$port)
```

---

## 3. Health Check

```bash
# All 3 modules
for port in 8081 8082 8083; do
  status=$(curl -sf http://localhost:$port/actuator/health 2>/dev/null && echo "UP" || echo "DOWN")
  echo "port=$port $status"
done
```

---

## 4. Metrics Check

### Calculator (port 8082)

```bash
# Throughput
curl -s http://localhost:8082/actuator/prometheus | grep -E "calculator_chunk_(users|items)_per_second" | grep -v '^#'

# Cumulative totals
curl -s http://localhost:8082/actuator/prometheus | grep -E "calculator_(users_processed|items_calculated|chunks_processed|chunks_failed)_total" | grep -v '^#'

# Data volume
curl -s http://localhost:8082/actuator/prometheus | grep -E "calculator_(input|result).*uncompressed_bytes" | grep -v '^#'
```

### Synchronizer (port 8083)

```bash
# Sync progress
curl -s http://localhost:8083/actuator/prometheus | grep -E "synchronizer_(chunk_documents_count|pre_upsert_json_rows|chunks_processed|chunks_failed)" | grep -v '^#'
```

### External API (port 8081, log-based)

```bash
# Current phase throughput
grep "rate=" logs/pipeline-test-external-api.log | tail -5
```

---

## 5. RSS Memory Check

```bash
for port in 8081 8082 8083; do
  pid=$(lsof -ti:$port 2>/dev/null)
  if [ -n "$pid" ]; then
    rss=$(ps -o rss= -p $pid 2>/dev/null)
    echo "port=$port pid=$pid rss=$((${rss:-0}/1024))MB"
  fi
done
```

**Normal range:** 900-1,400 MB per module. Total ~3.7 GB.
**Alert threshold:** > 2 GB per module or > 5 GB total.

---

## 6. Disk Check

```bash
df -h / | tail -1
du -sh ../data
```

**Normal range:** Data dir 9-21 GB (oscillates with cleanup cycle).
**Alert threshold:** Data dir > 50 GB (cleanup may be failing).

---

## 7. Cleanup Verification

```bash
# Total deletions
grep -c 'ConsumedChunkCleanup.*deleted' logs/pipeline-test-external-api.log

# Recent deletions
grep "ConsumedChunkCleanup" logs/pipeline-test-external-api.log | tail -5

# Verify no stale runs (runs with no recent activity)
ls -lt ../data/runs/ | head -10
```

---

## 8. Cron Status Check

```bash
# Full pipeline cycles (Ranking + OCID + Basic)
grep -E "RankingFetch complete|OCID lookup complete|character-basic complete" logs/pipeline-test-external-api.log

# Equipment cycles count
grep -c "item-equipment complete" logs/pipeline-test-external-api.log

# Run ID dates (verify daily rollover)
grep -oP 'runId=\K202605[0-9]{2}' logs/pipeline-test-external-api.log | sort -u
```

Expected: One full pipeline cycle per day ( Ranking → OCID → Basic), then equipment cycles every ~47 minutes.

---

## 9. DB Row Counts

```bash
PGPASSWORD=maple123 psql "host=localhost port=5432 user=maple dbname=maple_expectation" -t -A -c "
SELECT
  (SELECT count(*) FROM character_basic_read_model) as basic,
  (SELECT count(*) FROM character_equipment_read_model) as equip,
  (SELECT count(*) FROM game_character WHERE ocid IS NOT NULL) as game_char;"
```

---

## 10. Log Error Check

```bash
for m in external-api calculator synchronizer; do
  count=$(grep -c 'ERROR' logs/pipeline-test-${m}.log 2>/dev/null)
  echo "$m: $count errors"
  if [ "$count" -gt 0 ]; then
    grep "ERROR" logs/pipeline-test-${m}.log | tail -5
  fi
done
```

---

## 11. Troubleshooting

| Symptom | Check | Resolution |
|---------|-------|------------|
| Port already in use | `lsof -ti:PORT` | Kill stale process |
| Health check timeout (>60s) | Check startup logs | Missing DB, Kafka, or config |
| Pipeline stuck at OCID | Verify NEXON_API_KEY in .env | API key expired or rate-limited |
| Calculator not processing | Check Kafka queue depth | Consumer lag or backlog |
| 202 but never 200 (REST API) | Synchronizer health | Read model not synced |
| OOM / slow startup | Check JVM heap | Reduce data volume or increase -Xmx |
| Data dir growing unboundedly | Cleanup scheduler logs | Check Kafka connectivity for consumed events |
| RSS steadily increasing | Monitor over hours | Potential leak — check native memory |

---

## 12. Replay Procedure

To replay a specific run:

1. Identify the runId from logs: `grep "RankingFetch complete" logs/pipeline-test-external-api.log`
2. Delete result artifacts: `rm -rf ../data/calculator/runs/{runId}`
3. Delete source artifacts (optional): `rm -rf ../data/runs/{runId}`
4. Re-trigger: Restart external-api (run-on-startup) or wait for next cron cycle

To replay a single chunk:

1. Find the chunk's Kafka event in the topic
2. Re-publish the event with the same key
3. Calculator will reprocess; skip logic prevents duplicate if result exists

---

## 13. Artifact Inspection

```bash
# List recent runs
ls -lt ../data/runs/ | head -10

# Inspect a specific run
ls ../data/runs/{runId}/item-equipment/chunks/ | head -5

# Read a chunk (decompress + pretty-print first 2 records)
zcat ../data/runs/{runId}/item-equipment/chunks/part-0001.jsonl.gz | head -2 | jq .
```
