# Design: Synchronizer flat consumer decomposition (issue #1088)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #1088
- Note: extends the `ChunkConsumerTemplate` state-machine pattern from #983 to the three synchronizer consumers that still mix deserialization, domain decisions, infrastructure writes, and ACK in a single flat method.

---

## 1. Background / Problem

### Background

`ChunkConsumerTemplate` (#983) gives the synchronizer a clean state-machine for the `submit → process → succeed/fail` lifecycle, but it is used by `BasicSnapshotChunkConsumer` and `KafkaResultChunkConsumer` only for the *async chunk body*. The pre-template concerns — JSON deserialization, endpoint filtering, event-path templating, log policy — are still inline in each consumer's `consume(...)` method.

`OcidLookupRunConsumer` does not use the template at all. Its entire 34-line `consume` method deserializes the event, filters by endpoint, reads a file, batch-upserts, writes to Redis, and ACKs the Kafka message — all in one flat method.

### Problem

Three concerns remain mixed at the consumer boundary:

1. `OcidLookupRunConsumer.consume` (29-62): deserialization + endpoint filter + file read + DB upsert + Redis write + log policy + ACK
2. `BasicSnapshotChunkConsumer.consume` (42-62) / `consumeUrgentBasic` (68-88): deserialization + endpoint filter + ACK (template is used only for the body)
3. `KafkaResultChunkConsumer.consume` (40-116): deserialization + identity construction + event-path string template `runs/${runId}/${sourceEndpoint}/chunks/${chunkId}.jsonl.gz` (hardcoded) + onSuccess publish

The hardcoded path template is the worst offender: it embeds the storage layout inside a Kafka consumer.

### Goal

Make every synchronizer consumer responsible for only *deserialize → service delegate → ACK*. Move domain decisions (endpoint filter, empty check) and infrastructure side effects (DB upsert, Redis write, event-path construction) into dedicated services / builders. Reuse the existing `ChunkConsumerTemplate` for the async chunk body where it already fits.

---

## 2. Decision

Three sub-refactors in one PR. Each is small and self-contained.

### A) Extract `OcidLookupService`

New file: `module-synchronizer/.../service/OcidLookupService.kt`

```kotlin
@Component
class OcidLookupService(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val ocidMappingRedisWriter: OcidMappingRedisWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(event: SnapshotRunCompletedEvent) {
        if (event.endpoint != "ocid-lookup") return

        log.info("[OcidService] received: runId={} totalRecords={} manifestPath={}",
            event.runId, event.totalRecords, event.manifestPath)

        val mappings = fileReader.read(event.manifestPath)
        if (mappings.isEmpty()) {
            log.warn("[OcidService] no mappings found in: {}", event.manifestPath)
            return
        }

        repository.batchUpsert(mappings)
        runCatching {
            ocidMappingRedisWriter.writeOcidToRedis(mappings)
        }.onFailure { ex ->
            log.error(
                "[OcidService] Redis write failed after DB upsert: runId={} mappings={} - {}. Redis may be stale until next run.",
                event.runId, mappings.size, ex.message, ex,
            )
        }

        log.info("[OcidService] completed: runId={} processed={}", event.runId, mappings.size)
    }
}
```

`OcidLookupRunConsumer` shrinks to:

```kotlin
@KafkaListener(...)
fun consume(
    record: ConsumerRecord<String, String>,
    acknowledgment: Acknowledgment,
    @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) topic: String?,
) {
    val event = objectMapper.readValue(record.value(), SnapshotRunCompletedEvent::class.java)
    service.ingest(event)
    acknowledgment.acknowledge()
}
```

### B) Move endpoint filter into `BasicChunkIngestionService`

New file: `module-synchronizer/.../service/BasicChunkIngestionService.kt` with a single decision method:

```kotlin
@Component
class BasicChunkIngestionService {
    fun shouldHandle(event: SnapshotChunkReadyEvent): Boolean = event.endpoint == "character-basic"
}
```

`BasicSnapshotChunkConsumer.consume` and `consumeUrgentBasic` use the service. The `submitBasicChunk` body, urgent-flag decision, and `onSuccess`/`onFailure` callbacks stay in the consumer (they are template wiring, not domain logic).

### C) Extract `ResultChunkEventPathBuilder`

New file: `module-synchronizer/.../event/ResultChunkEventPathBuilder.kt`

```kotlin
@Component
class ResultChunkEventPathBuilder {
    fun sourceObjectKey(runId: String, sourceEndpoint: String, chunkId: String): String =
        "runs/${runId}/${sourceEndpoint}/chunks/${chunkId}.jsonl.gz"
}
```

`KafkaResultChunkConsumer.onSuccess` callback uses the builder. The endpoint default (`event.sourceEndpoint.ifBlank { "result" }`) stays at the consumer boundary because it shapes the `ChunkConsumedEvent` DTO, but the path template moves to the builder.

---

## 3. Trade-offs

### Sensitivity

- **Kafka message ordering:** All changes preserve the existing ACK pattern (success / non-retryable failure → ACK; retryable → leave unacked). No ordering change.
- **Log format:** Log lines keep the `[OcidConsumer]` / `[BasicSync]` / `[Synchronizer]` prefixes (renaming to `[OcidService]` etc. is a follow-up cleanup, not in this PR).
- **Test coverage:** Existing tests target the template; new unit tests should target the services directly. No consumer-level behavior changes.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Service layer for Ocid lookup | Single responsibility; testable without Kafka | 1 new file, 1 consumer shrinks |
| Service for endpoint filter | Filter logic in one place, unit-testable in isolation | Tiny indirection for a 1-line predicate |
| Builder for event path | Storage layout decoupled from consumer; future path-format changes isolated | 1 new file |
| Keep `BasicSnapshotChunkConsumer.submitBasicChunk` body | It is template wiring, not domain logic | Minor: still mixed style |
| Keep `[OcidConsumer]` log prefix | No log-format churn for downstream grep | Cosmetic inconsistency |

### Risk

- **Behavior drift in OcidLookupService:** The current consumer's Redis write is wrapped in `runCatching` so the message ACKs even if Redis fails. The service must preserve this — `ingest` returns normally and lets the consumer ACK. Mitigation: explicit `runCatching` in the service (mirrored 1:1), plus a unit test for the Redis-failure case.
- **Endpoint filter split:** `BasicChunkIngestionService.shouldHandle` is called *before* `submitBasicChunk`. The filter and ACK must stay in the consumer so the template is not called for non-character-basic events. Mitigation: keep the if-not-handle → ack-and-return branch in the consumer; only the predicate moves.
- **Path builder centralization:** Future storage layout changes (e.g. switch from `.jsonl.gz` to `.parquet`) now need a code change in the builder. This is the intended payoff — a single source of truth.
- **Naming overlap:** "Service" suffix is heavy for `BasicChunkIngestionService` whose only method is a predicate. Acceptable: it signals the *intent* (this is where ingestion decisions live), even if the surface is small.

### Non-Risk

- Kafka topic / group-id / payload format: unchanged.
- DB schema: unchanged.
- Redis key format: unchanged.
- `ChunkConsumerTemplate` interface: unchanged.
- Module boundary (`module-synchronizer` internal): all new classes stay inside the synchronizer module.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| Consumers with flat `consume` body | 3 → 0 | Ocid, Basic (×2 listeners), Result |
| New service / builder files | 3 | `OcidLookupService`, `BasicChunkIngestionService`, `ResultChunkEventPathBuilder` |
| Hardcoded path templates in consumers | 1 → 0 | Moved to `ResultChunkEventPathBuilder.sourceObjectKey` |
| Endpoint-filter predicates in consumers | 2 → 0 | One each in `BasicSnapshotChunkConsumer.consume` / `consumeUrgentBasic` |
| New unit tests | ~4 | Ocid happy path, Ocid Redis-failure, Basic filter true/false, Path builder |
| `OcidLookupRunConsumer.consume` line count | 34 → ~12 | |

### Observed Result

Post-implementation:
- `OcidLookupRunConsumer.consume` is `deserialize → service.ingest → ACK` only
- `BasicSnapshotChunkConsumer.consume` / `consumeUrgentBasic` consult `BasicChunkIngestionService.shouldHandle` before submitting
- `KafkaResultChunkConsumer.onSuccess` builds the source object key via `ResultChunkEventPathBuilder`
- All existing tests still pass
- `./gradlew :module-synchronizer:test` passes
- `./gradlew compileKotlin compileJava --continue` passes

---

## 5. Summary

> Apply the `ChunkConsumerTemplate` discipline (deserialize → service → ACK) to the three synchronizer consumers that still mix concerns in a flat `consume` method: extract `OcidLookupService`, `BasicChunkIngestionService`, and `ResultChunkEventPathBuilder`.

---

## 6. Implementation Outline (reference for writing-plans)

1. Create `OcidLookupService` (`module-synchronizer/.../service/OcidLookupService.kt`) with `ingest(event: SnapshotRunCompletedEvent)`. Move file read, batch upsert, Redis write, and all log lines from the consumer.
2. Refactor `OcidLookupRunConsumer.consume` to: parse → call `service.ingest(event)` → `acknowledgment.acknowledge()`. Drop now-unused imports.
3. Create `BasicChunkIngestionService` with `shouldHandle(event: SnapshotChunkReadyEvent): Boolean = event.endpoint == "character-basic"`.
4. Inject `BasicChunkIngestionService` into `BasicSnapshotChunkConsumer`; replace the inline `if (event.endpoint != "character-basic")` in both `consume` and `consumeUrgentBasic` with `if (!ingestionService.shouldHandle(event))`.
5. Create `ResultChunkEventPathBuilder` with `sourceObjectKey(runId, sourceEndpoint, chunkId)`. Use the existing endpoint default (`ifBlank { "result" }`) at the call site (the consumer).
6. Inject `ResultChunkEventPathBuilder` into `KafkaResultChunkConsumer`; replace the hardcoded string in the `onSuccess` callback with `eventPathBuilder.sourceObjectKey(runId, event.sourceEndpoint.ifBlank { "result" }, chunkId)`.
7. Add unit tests:
   - `OcidLookupServiceTest`: happy path (file read + DB upsert + Redis write), empty file (no DB / Redis calls), Redis failure (DB still called, no exception thrown).
   - `BasicChunkIngestionServiceTest`: `shouldHandle` returns true for `"character-basic"`, false for other endpoints.
   - `ResultChunkEventPathBuilderTest`: format match for known input.
8. Run `./gradlew :module-synchronizer:test` and `./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava --continue`.
