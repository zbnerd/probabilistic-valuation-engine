# Refactor Batch 1 — Six Extraction Refactors

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement these six issues task-by-task.

**Goal:** Resolve six ready-for-agent extraction refactors (#1083, #1080, #1074, #1061, #1087, #1084) by separating infrastructure concerns (Redis/JSON/Kafka/file I/O) from business logic, across the four active service modules.

**Architecture:** Each issue = one bounded extraction. Extract infrastructure touch points (Redis ZSET ops, JSON parse/serialize, file I/O, Kafka publish) into dedicated adapter/parser/reader classes; keep business decisions (degradation, routing, validation) in the original service. Hexagonal port/adapter pattern preserved — controllers/services depend on port interfaces; adapters in `module-infra`/consumer module implement the ports.

**Tech Stack:** Kotlin, Spring Boot, Lettuce (Redis), Kafka, Jackson, GZIP, JPA, Gradle multi-module.

---

## Issue #1083 — Rest-Controller: Separate PopularCharacterService from Redis operations

### Background

`PopularCharacterService` (module-rest-controller) mixes business policy (window clamping, degradation judgment, rolling key generation) with Redis ZSET operations (`opsForZSet().incrementScore`, `expire`, `unionAndStore`).

### Decision

Extract a `PopularCharacterRedisPort` interface (in `module-rest-controller/.../port/out/`) and a `PopularCharacterRedisAdapter` implementation. Service depends on the port; adapter is the only code that touches `redisTemplate`.

### Components

| Class | Location | Responsibility |
|---|---|---|
| `PopularCharacterService` (existing, modified) | `restcontroller/character/popular/` | Business policy: window clamping, degradation check, validate input, format response |
| `PopularCharacterRedisPort` (NEW) | `restcontroller/character/popular/port/out/` | Outbound port: `incrementScore(ign, weight)`, `expireAt(ign, instant)`, `unionAndStore(dst, srcs)` |
| `PopularCharacterRedisAdapter` (NEW) | `restcontroller/character/popular/adapter/out/` | Adapter: implements the port using `StringRedisTemplate.opsForZSet()` |

### Constraints

- Service must not import `org.springframework.data.redis.core.StringRedisTemplate` or `ZSetOperations` after refactor
- Rolling key generation logic (`rollingReadKey` function) moves into the adapter — service passes intent ("read at time T") not pre-computed keys
- Public service method signatures preserved — callers do not change
- No behavior change: increment weights, expire windows, union/aggregate behavior identical

---

## Issue #1080 — Calculator: Extract JSON parsing from SnapshotChunkProcessor

### Background

`SnapshotChunkProcessor.parseLines` and `calculateItem` mix JSON parsing (`objectMapper.readTree`, `writeValueAsString`) with business decisions (status check, dispatch, log sampling). `KafkaSnapshotChunkReadyConsumer` similarly mixes deserialization with ACK/retry policy.

### Decision

Extract a `SnapshotLineParser` that takes a raw JSON line string and returns a typed result or null (skipped). Extract a `SampleLogSerializer` for the debug log formatting. `KafkaSnapshotChunkReadyConsumer` is split into a thin transport wrapper and a service that handles domain decisions.

### Components

| Class | Location | Responsibility |
|---|---|---|
| `SnapshotChunkProcessor` (existing, modified) | `calculator/processor/` | Orchestration: channels, coroutines, calls parser, dispatches calc |
| `SnapshotLineParser` (NEW) | `calculator/parser/` | Parse raw line → `SnapshotRecord` (or null if `status != SUCCESS`) |
| `SampleLogSerializer` (NEW) | `calculator/processor/` (companion) | Format `CalculationResult` for sample log via `ObjectMapper` |
| `KafkaSnapshotChunkReadyConsumer` (existing, modified) | `calculator/consumer/` | Deserialization only: extract envelope, hand to dispatcher |
| `SnapshotDispatchService` (NEW) | `calculator/consumer/` | Domain decisions: ACK/retry based on outcome |

---

## Issue #1074 — Rest-Controller: Extract BatchResolver from BatchReadScheduler

### Background

`BatchReadScheduler` (161 lines, 7 deps) mixes SmartLifecycle, scheduling, and 65-line `resolveBatch` method touching 6 fields.

### Decision

Extract `BatchResolver` owning the 6 dependencies (`cacheService`, `queryService`, `urgentPublisher`, `properties`, `metrics`, `log`). `BatchReadScheduler` shrinks to lifecycle + scheduling + delegation.

### Components

| Class | Location | Responsibility |
|---|---|---|
| `BatchReadScheduler` (modified) | `restcontroller/read/` | SmartLifecycle: start/stop/phase + scheduledDrain |
| `BatchResolver` (NEW) | `restcontroller/read/` | Single `resolveBatch()` method: cache lookup → DB fallback → negative cache → urgent pipeline |

---

## Issue #1061 — External-API: Extract event publishing from ChunkedSnapshotSink

### Background

`ChunkedSnapshotSink` (292 lines) has 3 reasons to change: queue/thread lifecycle, chunk rotation/file I/O, event publishing. Three publish methods share the same try-catch + exceptionally pattern.

### Decision

Extract `SinkEventPublisher` with the 3 publish methods + a `publishSafely(event, name)` helper that absorbs the try-catch + exceptionally duplication.

### Components

| Class | Location | Responsibility |
|---|---|---|
| `ChunkedSnapshotSink` (modified) | `externalapi/snapshot/` | Queue lifecycle + write orchestration only |
| `SinkEventPublisher` (NEW) | `externalapi/snapshot/` | `publishChunkReady`, `publishRunCompleted`, `publishRunFailed` + `publishSafely` helper |

---

## Issue #1087 — External-API: Extract JSON parsing from OcidLookupPhase and RankingFetchPhase

### Background

`OcidLookupPhase.fetchAndCollectOcidAsync` (HTTP → readTree → re-serialize), `readCharacterNamesFromChunks` (file I/O + GZIP + readTree), and `RankingFetchPhase.submitRankingEntries` (readTree + writeValueAsBytes) mix parsing with orchestration.

### Decision

Extract 3 classes: `OcidResponseParser` (HTTP response → OCID), `CharacterNameReader` (GZIP file → name list), `RankingEntryParser` (HTTP body → entries).

### Components

| Class | Location | Responsibility |
|---|---|---|
| `OcidResponseParser` (NEW) | `externalapi/parser/` | Parse Nexon HTTP response → `OcidLookup` |
| `CharacterNameReader` (NEW) | `externalapi/reader/` | Read GZIP file → distinct character names |
| `RankingEntryParser` (NEW) | `externalapi/parser/` | Parse ranking response → `RankingEntry` list |
| `OcidLookupPhase` (modified) | `externalapi/snapshot/phase/` | Orchestration only — calls parsers |
| `RankingFetchPhase` (modified) | `externalapi/snapshot/phase/` | Orchestration only |

---

## Issue #1084 — External-API: Extract JSON/file/MQ from UrgentCharacterRequestConsumer

### Background

`UrgentCharacterRequestConsumer` mixes HTTP/JSON/file/Kafka concerns. `processUrgentCharacterAsync` (HTTP + readTree), `publishUrgentChunkAsync` (file write + GZIP + JSON + Kafka), `publishNotFoundAsync` (event + JSON + Kafka) all do too much.

### Decision

Extract `UrgentOcidResponseParser`, `UrgentChunkArtifactWriter` (delegates to existing `ArtifactStorePort`), and `UrgentEventPublisher`.

### Components

| Class | Location | Responsibility |
|---|---|---|
| `UrgentOcidResponseParser` (NEW) | `externalapi/parser/` | Nexon response → OCID |
| `UrgentChunkArtifactWriter` (NEW) | `externalapi/artifact/` | Wrap file write + GZIP via `ArtifactStorePort` |
| `UrgentEventPublisher` (NEW) | `externalapi/event/` | Domain event → JSON → Kafka |
| `UrgentCharacterRequestConsumer` (modified) | `externalapi/consumer/` | Orchestration only |

---

## Execution Order

Order chosen to minimize cross-issue conflicts (different modules / files per issue):

| # | Issue | Module | Files affected |
|---|---|---|---|
| 1 | #1083 | module-rest-controller | popular/ package (2 new + 1 modified) |
| 2 | #1080 | module-calculator | processor/, parser/, consumer/ |
| 3 | #1074 | module-rest-controller | read/ (1 new + 1 modified — different package from #1083) |
| 4 | #1061 | module-external-api | snapshot/ (1 new + 1 modified) |
| 5 | #1087 | module-external-api | phase/ + new parser/reader classes (different files from #1061) |
| 6 | #1084 | module-external-api | consumer/ + new parser/writer/publisher (different files from #1061/#1087) |

#1083 and #1074 are both in module-rest-controller but in different packages (`character/popular/` vs `read/`), so no overlap.

## Out of Scope

- `module-app` legacy code (per repo convention)
- Changing public method signatures of the modified services
- New tests for unchanged behavior — preserve existing test coverage
- Refactoring issues not in this batch

## Self-Review

- **Spec coverage:** Each of 6 issues has a dedicated section with: extracted class name, location, responsibility. Acceptance criteria from each issue body preserved.
- **Placeholder scan:** No TBD/TODO. Each component has clear single responsibility.
- **Internal consistency:** No conflict between modules (#1083+#1074 in different packages; #1061/#1087/#1084 in different source files).
- **Scope check:** 6 small extractions; each is one file pair (new adapter + modified owner).
- **Ambiguity:** Component names unique across all 6 refactors.
