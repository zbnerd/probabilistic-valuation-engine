# Synchronizer & Airflow Reliability Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 reliability bugs — Airflow Kafka networking/correctness (#878), synchronizer OCID upsert atomicity (#876), synchronizer Redis write atomicity (#875).

**Architecture:** Three independent PRs. PR-1 fixes Airflow DAG runId filtering, group_id uniqueness, and connections.sh double scheme. PR-2 converts BasicSnapshot row-by-row OCID upsert to batch COPY→merge, wraps DB+Redis in compensating error handling, removes dead code. PR-3 makes OcidMapping Redis writes atomic via RENAME pattern and defers EquipmentRanking trim to after all batches.

**Tech Stack:** Kotlin 2.0, Python 3 (Airflow DAGs), Redis, PostgreSQL COPY→merge, Spring Kafka

---

## File Structure

| PR | Action | File | Change |
|----|--------|------|--------|
| 1 | Modify | `docker/airflow/dags/daily_collection_pipeline.py` | runId filter, group_id per-run |
| 1 | Modify | `docker/airflow/connections.sh` | Remove double `http://` scheme |
| 2 | Modify | `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt` | Batch OCID upsert |
| 2 | Modify | `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt` | Redis write error handling |
| 2 | Delete | `module-synchronizer/.../repository/SynchronizerChunkStatusRepository.kt` | Dead code removal |
| 3 | Modify | `module-synchronizer/.../repository/OcidMappingRepository.kt` | RENAME pattern for atomic writes |
| 3 | Modify | `module-synchronizer/.../ranking/EquipmentRankingRedisWriter.kt` | Trim once after all batches |

---

## PR-1: #878 — Airflow Kafka Networking Fixes

### Task 1: Fix connections.sh double http:// scheme

**Files:**
- Modify: `docker/airflow/connections.sh:12,19`

- [ ] **Step 1: Fix `--conn-host` values**

`--conn-host http://host.docker.internal` has `http://` prefix while `--conn-schema http` already provides the scheme. This produces `http://http://host.docker.internal:8081`.

Replace `docker/airflow/connections.sh` lines 10-14:

```bash
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http \
  --conn-host host.docker.internal \
  --conn-port 8081 \
  --conn-schema http
```

Replace lines 17-21:

```bash
docker exec maple-airflow-scheduler airflow connections add calculator \
  --conn-type http \
  --conn-host host.docker.internal \
  --conn-port 8082 \
  --conn-schema http
```

- [ ] **Step 2: Commit**

```bash
git checkout -b fix/airflow-kafka-networking develop
git add docker/airflow/connections.sh
git commit -m "fix(airflow): remove double http:// scheme in connections.sh

--conn-host had http:// prefix while --conn-schema already provided the scheme,
producing http://http://host.docker.internal:8081 in resolved URLs.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2: Add runId filtering and per-run group_id to Kafka consumer

**Files:**
- Modify: `docker/airflow/dags/daily_collection_pipeline.py:65-93`

- [ ] **Step 1: Replace `wait_for_item_equipment_cycle` function**

Current function consumes the first `item-equipment` event from any run. Fix: filter by runId from the trigger response, use unique group_id per run.

Replace the entire `wait_for_item_equipment_cycle` function (lines 65-93) with:

```python
def wait_for_item_equipment_cycle(**context):
    """Wait for item-equipment chunk consumed event from synchronizer via Kafka.

    Consumes from synchronizer.chunk.consumed topic. Filters by runId
    to only accept events from the run triggered by this pipeline invocation.
    Uses per-run group_id to avoid partition rebalancing on overlapping runs.
    """
    import json as _json
    from kafka import KafkaConsumer

    trigger_response = context["ti"].xcom_pull(task_ids="trigger_daily_collection")
    if isinstance(trigger_response, str):
        trigger_response = _json.loads(trigger_response)
    run_id = trigger_response["runId"]

    consumer = KafkaConsumer(
        "synchronizer.chunk.consumed",
        bootstrap_servers="host.docker.internal:9092",
        auto_offset_reset="latest",
        enable_auto_commit=False,
        group_id=f"airflow-ie-cycle-waiter-{run_id[:8]}",
        value_deserializer=lambda m: _json.loads(m.decode("utf-8")),
        consumer_timeout_ms=120 * 60 * 1000,  # 2 hours
    )

    try:
        for message in consumer:
            event = message.value
            if event.get("endpoint") == "item-equipment" and event.get("runId") == run_id:
                return True
    finally:
        consumer.close()

    raise RuntimeError("Timed out waiting for item-equipment consumed event")
```

Key changes:
- Pulls `run_id` from XCom (same as `poll_run_completion`)
- `group_id` includes `run_id[:8]` for per-run uniqueness
- Added `event.get("runId") == run_id` filter alongside endpoint check

**Note on consumer group accumulation:** Per-run `group_id` creates a new consumer group each DAG run. Kafka's default `offsets.retention.minutes` (10080 = 7 days) auto-cleans inactive groups. With daily runs, at most 7 stale groups exist at any time — acceptable overhead.

- [ ] **Step 2: Commit and create PR**

```bash
git add docker/airflow/dags/daily_collection_pipeline.py
git commit -m "fix(airflow): add runId filtering and per-run group_id to Kafka consumer

- Filter consumed events by runId to avoid accepting events from wrong run
- Use per-run group_id (airflow-ie-cycle-waiter-{runId[:8]}) to prevent
  partition rebalancing on overlapping DAG runs

Fixes #878

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/airflow-kafka-networking
gh pr create --base develop --title "fix(airflow): Kafka networking and correctness fixes" --body 'Fixes #878

## Changes
- **connections.sh**: Remove double `http://` scheme in `--conn-host` (scheme already from `--conn-schema`)
- **runId filtering**: Kafka consumer now filters by `runId` to only accept events from the triggered run
- **group_id**: Per-run unique consumer group (`airflow-ie-cycle-waiter-{runId[:8]}`) prevents partition rebalancing

**Not changed:** Kafka bootstrap address (`host.docker.internal:9092`) — will be addressed when containerization strategy is decided.

## Files Changed (2)
- `docker/airflow/connections.sh`
- `docker/airflow/dags/daily_collection_pipeline.py`

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## PR-2: #876 — OCID Upsert Transaction and Batch Operations

### Task 3: Convert BasicSnapshot row-by-row OCID upsert to batch COPY→merge

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt:163-180`

- [ ] **Step 1: Replace `upsertOcidFromBasicRecords` with batch COPY→merge**

Current implementation does per-row DELETE+INSERT (2 SQL round-trips per record, no transaction). Replace with `OcidMappingRepository.batchUpsert()` which uses COPY→merge pattern.

Add imports at the top of `BasicSnapshotChunkConsumer.kt`:

```kotlin
import maple.synchronizer.repository.OcidMappingRepository
import maple.synchronizer.storage.OcidMapping
```

Remove unused imports:

```kotlin
// Remove these:
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
```

Add `OcidMappingRepository` to constructor:

```kotlin
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val ocidMappingRepository: OcidMappingRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
) : ManagedLifecycle {
```

Replace the `upsertOcidFromBasicRecords` method (lines 163-180) with:

```kotlin
private fun upsertOcidFromBasicRecords(records: List<BasicRecord>) {
    val mappings = records.map { OcidMapping(userIgn = it.userIgn, ocid = it.ocid) }
    ocidMappingRepository.batchUpsert(mappings)
    log.info("[BasicSync] batch upserted OCID mappings: count={}", mappings.size)
}
```

Also remove the now-unused imports:

```kotlin
// Remove these imports:
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
```

Remove `jdbc` from the constructor since `upsertOcidFromBasicRecords` was its only consumer in this class. Check that no other method in the class uses `jdbc` — it doesn't, so remove it:

```kotlin
class BasicSnapshotChunkConsumer(
    private val objectMapper: ObjectMapper,
    private val fileReader: BasicChunkFileReader,
    private val repository: CharacterBasicRepository,
    private val ocidMappingRepository: OcidMappingRepository,
    private val chunkConsumerTemplate: ChunkConsumerTemplate,
    private val consumedEventPublisher: KafkaChunkConsumedEventPublisher,
) : ManagedLifecycle {
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git checkout -b fix/synchronizer-ocid-upsert-batch develop
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt
git commit -m "fix(synchronizer): convert OCID upsert from row-by-row to batch COPY→merge

Replace per-row DELETE+INSERT loop in upsertOcidFromBasicRecords() with
OcidMappingRepository.batchUpsert() which uses PostgreSQL COPY into temp table
followed by single DELETE+INSERT...ON CONFLICT merge.

Before: 2 SQL round-trips per record, no transaction boundary
After: Single transactional COPY→merge, same pattern as OcidLookupRunConsumer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 4: Add compensating error handling for DB+Redis writes in OcidLookupRunConsumer

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt:50-53`

- [ ] **Step 1: Wrap Redis write with error handling**

Current code: `batchUpsert` succeeds → `writeOcidToRedis` fails → Redis stale, no compensation. Fix: log error on Redis failure so operations is aware, acknowledge only after both complete.

Replace lines 50-53 in `OcidLookupRunConsumer.kt`:

```kotlin
        repository.batchUpsert(mappings)
        runCatching {
            repository.writeOcidToRedis(mappings)
        }.onFailure { ex ->
            log.error(
                "[OcidConsumer] Redis write failed after DB upsert: runId={} mappings={} - {}. Redis may be stale until next run.",
                event.runId, mappings.size, ex.message, ex,
            )
        }

        log.info("[OcidConsumer] completed: runId={} processed={}", event.runId, mappings.size)
        acknowledgment.acknowledge()
```

Note: Full atomicity (DB rollback on Redis failure) is not practical — Redis has no transaction coordination with PostgreSQL. The RENAME pattern in PR-3 (#875) makes the Redis write itself atomic. This error handling ensures the failure is visible and the Kafka message is still acknowledged (preventing redelivery loop for a non-recoverable Redis error).

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt
git commit -m "fix(synchronizer): add error handling for Redis write after DB upsert

Wrap writeOcidToRedis in runCatching to prevent Kafka redelivery loop on
Redis failure. Log error with run context for operations visibility.
DB write succeeds; Redis will catch up on next run.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 5: Delete dead SynchronizerChunkStatusRepository

**Files:**
- Delete: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/SynchronizerChunkStatusRepository.kt`

- [ ] **Step 1: Delete the file**

Confirmed dead code: only reference is its own class declaration and logger. No callers, no tests, superseded by `ChunkConsumerTemplate`.

```bash
rm module-synchronizer/src/main/kotlin/maple/synchronizer/repository/SynchronizerChunkStatusRepository.kt
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit and create PR**

```bash
git add -u module-synchronizer/src/main/kotlin/maple/synchronizer/repository/SynchronizerChunkStatusRepository.kt
git commit -m "fix(synchronizer): remove dead SynchronizerChunkStatusRepository

No callers, no tests. Chunk status tracking now handled by ChunkConsumerTemplate.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/synchronizer-ocid-upsert-batch
gh pr create --base develop --title "fix(synchronizer): OCID upsert batch, error handling, dead code removal" --body 'Fixes #876

## Changes
- **Batch OCID upsert**: Replace per-row DELETE+INSERT loop with `OcidMappingRepository.batchUpsert()` (COPY→merge)
- **Redis error handling**: `OcidLookupRunConsumer` now catches Redis write failures after DB upsert instead of propagating
- **Dead code**: Remove `SynchronizerChunkStatusRepository` (no callers, superseded by `ChunkConsumerTemplate`)

## Files Changed (3)
- `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt`
- `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt`
- `module-synchronizer/.../repository/SynchronizerChunkStatusRepository.kt` (deleted)

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## PR-3: #875 — Atomic Redis Writes

### Task 6: OcidMapping — atomic RENAME pattern

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt:70-83`

- [ ] **Step 1: Replace delete+hSet with RENAME pattern**

Current: `delete(REDIS_KEY)` then pipelined `hSet` — readers see empty hash between operations. Fix: write to temp key, then `RENAME` atomically.

Replace the `writeOcidToRedis` method (lines 70-83) in `OcidMappingRepository.kt`:

```kotlin
    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        if (mappings.isEmpty()) {
            redisTemplate.delete(REDIS_KEY)
            log.info("[OcidMapping] Redis cleared: empty mappings")
            return
        }
        val tempKey = "$REDIS_KEY:tmp:${System.nanoTime()}"
        redisTemplate.executePipelined { connection ->
            for (mapping in mappings) {
                connection.hashCommands().hSet(
                    tempKey.toByteArray(),
                    mapping.userIgn.toByteArray(),
                    mapping.ocid.toByteArray(),
                )
            }
            null
        }
        redisTemplate.rename(tempKey, REDIS_KEY)
        log.info("[OcidMapping] Redis written atomically via RENAME: {} mappings to {}", mappings.size, REDIS_KEY)
    }
```

Key changes:
- Writes to temp key (`ocid:mapping:tmp:<nanos>`) in pipeline
- `RENAME temp → ocid:mapping` is atomic in Redis
- Readers always see complete hash or previous hash — never empty/partial
- No `delete` needed — `RENAME` overwrites the target atomically

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git checkout -b fix/synchronizer-atomic-redis-writes develop
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt
git commit -m "fix(synchronizer): atomic OcidMapping Redis writes via RENAME pattern

Replace delete(REDIS_KEY) + pipelined hSet with write-to-temp + RENAME.
RENAME is atomic in Redis — readers never see empty or partial hash.

Before: delete → pipeline hSet (readers see empty hash between operations)
After: pipeline hSet to temp key → RENAME temp to real key (atomic swap)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 7: EquipmentRanking — trim once after all batches

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt:39-58`

- [ ] **Step 1: Move zRemRange outside batch loop**

Current: `zRemRange` runs inside each batch iteration, evicting entries from previous partial batches. Fix: add all entries in batched pipelines, then trim once after all batches complete.

Replace the `updatePreset` method (lines 39-58) in `EquipmentRankingRedisWriter.kt`:

```kotlin
    private fun updatePreset(documents: List<PreppedDocument>): Int {
        var updated = 0
        val key = rankingKey(documents.first().presetNo.toInt()).toByteArray(StandardCharsets.UTF_8)
        val keepFrom = properties.topSize.coerceAtLeast(1).toLong()

        // Add all entries in batched pipelines — no trimming per batch
        documents.chunked(properties.batchSize.coerceAtLeast(1)).forEach { batch ->
            redisTemplate.executePipelined { connection ->
                batch.forEach { document ->
                    val userIgn = document.userIgn ?: return@forEach
                    connection.zSetCommands().zAdd(
                        key,
                        document.totalCost.toDouble(),
                        userIgn.toByteArray(StandardCharsets.UTF_8),
                    )
                    updated += 1
                }
                null
            }
        }

        // Trim once after all batches — removes all members ranked below top N
        redisTemplate.executePipelined { connection ->
            connection.zSetCommands().zRemRange(key, 0, -(keepFrom + 1))
            null
        }

        return updated
    }
```

Key changes:
- `zAdd` calls batched as before, but `zRemRange` removed from inside loop
- Single `zRemRange` after all batches: removes all members ranked below top N
- `zRemRange(key, 0, -(keepFrom + 1))` removes ranks 0 through -(topSize+1) — i.e., everything below the top `topSize` members. In a sorted set with highest-first ordering (DESC), the lowest-ranked members are at negative indices.

**Wait — verify zRemRange semantics.** Redis sorted sets are ordered by score ascending by default. `zRemRange(key, 0, -(topSize+1))` removes the lowest-scored members, keeping only the top `topSize` highest scores. But the current code uses `zRemRange(key, 0, -properties.topSize.coerceAtLeast(1).toLong() - 1)` which has the same logic. The behavior is preserved — just moved outside the loop.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-synchronizer:compileKotlin --continue 2>&1 | grep -E "FAILED|BUILD" | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit and create PR**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/ranking/EquipmentRankingRedisWriter.kt
git commit -m "fix(synchronizer): trim EquipmentRanking once after all batches, not per-batch

Move zRemRange outside the batch loop in updatePreset(). Previous batch
entries were being evicted by zRemRange before subsequent batches could
supersede them with higher scores. Now all zAdd calls complete first,
then a single trim keeps only the correct top-N.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin fix/synchronizer-atomic-redis-writes
gh pr create --base develop --title "fix(synchronizer): atomic Redis writes" --body 'Fixes #875

## Changes
- **OcidMapping atomic writes**: Replace `delete + hSet` with `write-to-temp + RENAME` pattern. RENAME is atomic — readers never see empty/partial hash.
- **EquipmentRanking correct trim**: Move `zRemRange` outside batch loop. Trim once after all `zAdd` calls complete, preserving correct top-N.

## Files Changed (2)
- `module-synchronizer/.../repository/OcidMappingRepository.kt`
- `module-synchronizer/.../ranking/EquipmentRankingRedisWriter.kt`

🤖 Generated with [Claude Code](https://claude.com/claude-code)'
```

---

## Final: Close Issues

After all 3 PRs are merged, close:

```bash
gh issue close 878 --comment "Fixed by PR (runId filter + per-run group_id + connections.sh scheme fix)"
gh issue close 876 --comment "Fixed by PR (batch COPY→merge + Redis error handling + dead code removal)"
gh issue close 875 --comment "Fixed by PR (RENAME pattern + trim once after all batches)"
```
