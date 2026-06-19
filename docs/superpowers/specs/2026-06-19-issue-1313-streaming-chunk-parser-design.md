# Issue #1313 — Streaming JSONL Chunk Parser (Phase 4 of offheap-streaming)

- Date: 2026-06-19
- Status: Draft (pending user review)
- Owner: maple-pipeline
- Parent spec: `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 4
- Parent plan: `docs/superpowers/plans/2026-06-19-offheap-streaming.md` Task 4
- Issue: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1313

---

## 1. Background / Problem

### Background

Parent spec §2 established ext-api baseline at **410MB heap + 1311MB RSS**. One of the hot-path heap consumers is line-buffered JSONL parsing — `CharacterNameReader`, `OcidCacheProvider` (ext-api), and `GzipJsonlSnapshotRecordReader` (calculator) all do `bufferedReader().useLines { ... }` or `readTree(line)` per line. Each line allocates a `String` + `JsonNode`, which becomes a `Map<String, Any>` allocation downstream. For ~10MB gz chunks (≈30-50MB uncompressed JSONL), this is ~50-100MB peak transient heap.

### Problem

The issue body described a pattern (`ObjectMapper.readValue(byte[])` → `List<Map<String, Any>>`) that does not exist in the codebase. Existing readers are already line-buffered, but they still allocate per-line. A true Jackson `JsonParser` token-stream parser that emits `Flow<Map<String, Any>>` saves both the per-line String allocation and the intermediate JsonNode map.

### Goal

- ext-api heap < 200MB sustained (from 410MB baseline).
- Throughput unchanged within ±5% (`external_api_users_fetched_total` rate).
- API surface preserved: `CharacterNameReader.readDistinctKeys(): List<String>` and `OcidCacheProvider.refresh(): Map<String, String>` keep current signatures; internal implementation swaps to `Flow`-based parser.
- `StreamingChunkParser` reused by both modules.

---

## 2. Resolved Ambiguities (vs. issue body)

| Ambiguity | Resolution |
|-----------|------------|
| Issue body says parser lives in `module-external-api/.../parser/` | Reject — calculator would need reverse dependency on ext-api. Place in `module-common/.../parser/`. |
| Issue body names `NexonAdapter` | Class does not exist. Call sites are `CharacterNameReader`, `OcidCacheProvider`, `GzipJsonlSnapshotRecordReader`. |
| Issue body says output `Flow<Map<String, Any>>`, parent spec §5.3 says `Flow<ItemRecord>` | Use `Flow<Map<String, Any>>` (issue body). Domain mapping remains the caller's responsibility. |
| Pattern to replace not in codebase | Reframe: replace per-line `readTree` with token-stream `JsonParser` via shared util. Same outcome, no fabricated prior pattern. |

---

## 3. Decision

> Place `StreamingChunkParser` in `module-common`, return `Flow<Map<String, Any>>`, swap three call sites to consume the Flow while preserving their public APIs.

```
┌────────────────────────────────────────────────────────┐
│ module-common/.../parser/StreamingChunkParser          │
│   parse(input: InputStream, skipMalformed=true)        │
│     : Flow<Map<String, Any>>                           │
│   parseToList(input): List<Map<String, Any>> (helper)  │
└────────────────────────────────────────────────────────┘
        ▲                       ▲
        │                       │
        │ @Bean                 │ @Bean
        │                       │
┌────────────────────┐  ┌──────────────────────────────┐
│ module-external-api│  │ module-calculator            │
│  CharacterNameReader│  │ GzipJsonlSnapshotRecordReader│
│  OcidCacheProvider │  │                              │
│   .readDistinctKeys│  │   (consumer of chunks)       │
│   .refresh         │  │                              │
└────────────────────┘  └──────────────────────────────┘
```

**Tech choice:** Jackson `JsonParser` token stream + `JsonParser.readValueAsTree<JsonNode>()` per record (single allocation, simpler than manual token loop). GZIP via JDK `GZIPInputStream`. Flow via `kotlinx.coroutines.flow.flow { ... }`.

**Why not full manual token loop:** `readValueAsTree()` per record trades a small allocation for code simplicity. Heap target is < 200MB (not < 100MB) — manual loop not justified.

**Design revision (2026-06-19 implementation feedback):**
The token-stream approach above failed during implementation: Jackson `JsonParser` cannot reliably resync after `nextToken()` throws on top-level garbage input — there is no robust way to advance to the next `START_OBJECT` once the parser is in an inconsistent state. The actual committed implementation (§4.1.1) uses **line-bounded JSONL parsing** (`BufferedReader.lineSequence()`) which uses NDJSON's natural line boundary as the recovery point — the canonical approach for JSONL streams. See §4.1.1 below for the implementation that was actually shipped.

**Heap impact (revised, smaller than originally projected):**
- Eliminates intermediate `JsonNode.toMap()` indirection at the call site; converts directly to `Map<String, Any>`.
- Eliminates duplicate per-line `readTree` followed by `convertValue` round-trip at hot-path call sites.
- Per-line `String` allocation remains (unavoidable for line-bounded JSONL).
- Originally-projected heap reduction of ~50MB on ext-api peak should be re-measured at runtime; expected actual reduction is ~10-20MB.

---

## 4. Components

### 4.1 `StreamingChunkParser` (new, in `module-common`)

Signature:
```kotlin
class StreamingChunkParser(
    private val objectMapper: ObjectMapper,
    private val skipMalformed: Boolean = true,
) {
    fun parse(input: InputStream): Flow<Map<String, Any>>
    suspend fun parseToList(input: InputStream): List<Map<String, Any>>
}
```

Implementation outline:
```kotlin
fun parse(input: InputStream): Flow<Map<String, Any>> = flow {
    GZIPInputStream(BufferedInputStream(input)).use { gz ->
        objectMapper.factory.createParser(gz).use { parser ->
            var records = 0L
            var skipped = 0L
            while (parser.nextToken() != null) {
                if (parser.currentToken != JsonToken.START_OBJECT) continue
                val loc = parser.currentLocation
                try {
                    val node = parser.readValueAsTree<JsonNode>()
                    @Suppress("UNCHECKED_CAST")
                    emit((node as ObjectNode).toMap() as Map<String, Any>)
                    records++
                } catch (ex: Exception) {
                    if (!skipMalformed) {
                        throw ex
                    }
                    parser.skipChildren()
                    skipped++
                    log.error("[ChunkParser] skipped malformed record line={} col={}: {}",
                        loc.lineNr, loc.columnNr, ex.message)
                }
            }
            log.info("[ChunkParser] done records={} skipped={}", records, skipped)
        }
    }
}
```

### 4.2 `ChunkParserConfig` (new, per-module)

Each consuming module exposes the parser as a Spring bean:
```kotlin
@Configuration
class ChunkParserConfig {
    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser =
        StreamingChunkParser(objectMapper, skipMalformed = true)
}
```

Files:
- `module-external-api/.../config/ChunkParserConfig.kt`
- `module-calculator/.../config/ChunkParserConfig.kt`

### 4.3 Call-site refactors

| File | Path | Change |
|------|------|--------|
| `OcidLookupPhase.readCharacterNamesFromChunks` | `module-external-api/.../scheduler/phase/OcidLookupPhase.kt:181` | Hot path. Already `suspend fun ... = withContext(Dispatchers.Default)`. Replace inlined `GZIPInputStream + lineSequence + readTree` loop with `streamingChunkParser.parse(stream).toList()`. Inject `StreamingChunkParser` + `ChunkParserMetrics`. Signature unchanged: `suspend fun ... : List<String>`. |
| `OcidCacheProvider.refresh` / `loadFromRun` | `module-external-api/.../cache/OcidCacheProvider.kt` | Cold path. Replace per-line `parseLine(line)` with `runBlocking { parser.parse(stream).toList() }`. `runBlocking` here mirrors `ExternalApiScheduler.kt:188` pattern (cold / non-VT trigger). Public API unchanged. |
| `GzipJsonlSnapshotRecordReader.readRecords` | `module-calculator/.../reader/GzipJsonlSnapshotRecordReader.kt` | Switch from `Flow<String>` to `Flow<Map<String, Any>>`. Downstream consumers of this reader (search for `GzipJsonlSnapshotRecordReader` injection sites) extract fields directly from `Map` instead of re-parsing each line via `objectMapper.readTree`. |

**Not modified:** `module-external-api/.../reader/CharacterNameReader.kt` is pre-existing dead code (no production injection; only a code comment in `OcidResponseParser.kt:27` references it). Per CLAUDE.md §3 ("Touch only what you must"), it is preserved as-is. A follow-up cleanup issue may delete it.

### 4.4 Feature flag

The ext-api hot-path swap is gated behind a feature flag for rollback:

```yaml
externalapi:
  parser:
    streaming:
      enabled: true   # default true post-deploy; set false to fall back to inlined readTree
```

```kotlin
@Configuration
@ConditionalOnProperty(
    name = ["externalapi.parser.streaming.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StreamingChunkParserConfig {
    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser =
        StreamingChunkParser(objectMapper, skipMalformed = true)
}
```

When `enabled=false`, the bean is not created. `OcidLookupPhase` and `OcidCacheProvider` must check bean presence (or the configuration must gate the constructor injection via `@Autowired(required=false)`). Implementation choice: use `@Autowired(required=false) lateinit var streamingChunkParser` with fallback to legacy inline path, OR refactor hot path to delegate to a strategy bean selected by the flag.

Calculator has no feature flag (no `runBlocking` introduction; if regression occurs, revert is a one-line change in `GzipJsonlSnapshotRecordReader`).

---

## 5. Data Flow

### Before
```
File/Stream (gz+JSONL)
  → GZIPInputStream → BufferedReader
  → useLines { line → objectMapper.readTree(line) → ... }
  → List<Map> / Map<K,V>
```

### After
```
File/Stream (gz+JSONL)
  → GZIPInputStream
  → JsonParser token loop
  → per START_OBJECT: readValueAsTree → toMap → emit
  → caller collects to List / Map as needed
```

### Skip-malformed (skipMalformed=true)
1. `nextToken()` → START_OBJECT
2. `readValueAsTree()` throws
3. `skipChildren()` advances past current object
4. Outer `while` continues — finds next START_OBJECT via `nextToken()`
5. Line/column from `parser.currentLocation` for ERROR log

### Skip-malformed (skipMalformed=false)
1. Throw propagates → Flow fails → caller decides

---

## 6. Error Handling

| Failure | Behavior | Recovery |
|---------|----------|----------|
| `GZIPInputStream` header invalid | `ZipException` propagates | Caller retry / dead-letter |
| record parse error (skip=true) | ERROR log with line:col, skipChildren, continue | Self-healing |
| record parse error (skip=false) | throw | Caller handles |
| IOException mid-stream | `parser.use {}` close, Flow fails | Caller retry |
| ObjectMapper not configured with `JsonNodeFactory` | startup fails fast | Bean wiring check |

---

## 7. Metrics

New counters/timer (per module, distinct application label):

| Metric | Type | Labels | Purpose |
|--------|------|--------|---------|
| `chunk_parser_records_emitted_total` | counter | `application,source` | records successfully emitted |
| `chunk_parser_records_skipped_total` | counter | `application,source` | records skipped due to malformed JSON |
| `chunk_parser_duration_seconds` | timer | `application,source` | end-to-end parse latency |

`source` ∈ {`character_name`, `ocid_mapping`, `snapshot_record`} — bound cardinality.

Existing metrics untouched.

---

## 8. Testing

### Unit (`StreamingChunkParserTest`, in module-common test):
1. valid 2 records → emits 2, skipped 0
2. malformed middle (skip=true) → emits 2, skipped 1
3. malformed middle (skip=false) → Flow throws
4. gzip header corrupt → throws `ZipException`
5. empty stream → emits 0
6. nested object → emit preserves nested Map/List
7. large record (100KB × 10) → heap < 50MB during parse

### Call-site regression:
- `CharacterNameReaderTest` — `readDistinctKeys` returns identical set vs. baseline
- `OcidCacheProviderTest` — `refresh()` returns identical map
- `GzipJsonlSnapshotRecordReaderTest` (or downstream consumer test) — Flow contents identical

### Runtime (pre-PR, mandatory per workflow-rules.md §10):
```bash
./gradlew :module-common:test --tests "*StreamingChunkParser*"
./gradlew :module-external-api:test --tests "*Reader*"
./gradlew :module-external-api:test --tests "*Cache*"
./gradlew :module-calculator:test
set -a && source .env && set +a
./gradlew :module-external-api:bootRun &
sleep 60
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="external-api"}'
curl -s 'http://localhost:9090/api/v1/query?query=rate(external_api_users_fetched_total[5m])'
# Run 1hr pipeline then compare:
# - heap < 200MB sustained
# - throughput within ±5% of pre-change baseline
```

---

## 9. Critical Files

| File | Action | Notes |
|------|--------|-------|
| `module-common/src/main/kotlin/maple/common/parser/StreamingChunkParser.kt` | NEW | parser |
| `module-common/src/test/kotlin/maple/common/parser/StreamingChunkParserTest.kt` | NEW | unit tests |
| `module-external-api/src/main/kotlin/maple/externalapi/config/StreamingChunkParserConfig.kt` | NEW | `@Bean` exposure, gated by `@ConditionalOnProperty` |
| `module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt` | NEW | counters + timer (ext-api) |
| `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` | MODIFY | replace `readCharacterNamesFromChunks` body (line 181) |
| `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` | MODIFY | swap to parser (cold path) |
| `module-calculator/src/main/kotlin/maple/calculator/config/ChunkParserConfig.kt` | NEW | @Bean exposure |
| `module-calculator/src/main/kotlin/maple/calculator/metrics/ChunkParserMetrics.kt` | NEW | counters + timer (calculator) |
| `module-calculator/src/main/kotlin/maple/calculator/reader/GzipJsonlSnapshotRecordReader.kt` | MODIFY | Flow<Map> signature change |
| `module-calculator/src/main/kotlin/.../<downstream consumer>.kt` | MODIFY | update for Flow<Map> |
| `module-external-api/src/main/resources/application.yml` | MODIFY | add `externalapi.parser.streaming.enabled: true` |

**Pre-existing dead code, NOT modified:** `module-external-api/.../reader/CharacterNameReader.kt` (no production injection). Follow-up cleanup issue may delete.

(Final downstream consumer file in calculator to be identified during implementation — search for `GzipJsonlSnapshotRecordReader` injections.)

---

## 10. Reused Symbols

- `com.fasterxml.jackson.core.JsonParser` (already on classpath via Jackson Databind)
- `com.fasterxml.jackson.databind.node.ObjectNode.toMap()` (Jackson standard)
- `java.util.zip.GZIPInputStream` (JDK)
- `kotlinx.coroutines.flow.flow {}` + `.toList()` (coroutines already on classpath)
- `ObjectMapper` (Spring Boot auto-configured, injected)

---

## 11. Out of Scope

- Replacing Netty HTTP client.
- Replacing Kafka.
- Compressing chunk payload differently.
- Streaming gzip → S3 upload (covered by Phase 3, issue #1312).
- Off-heap OCID cache (covered by Phase 2, issue #1311).
- Direct buffer pool tuning (covered by Phase 5, issue #1314).

---

## 12. Trade-offs

### Sensitivity

- **gzip chunk size** — larger chunks = bigger absolute heap saving (the whole point of Phase 4).
- **Jackson `JsonNode` allocation cost** — `readValueAsTree()` allocates one node tree per record. Manual token loop would save this but adds code complexity. Trade-off: simpler code vs. ~10-20% lower peak heap. Not justified given heap target < 200MB.
- **Flow vs. List materialization** — `Flow` is cold/lazy, but call sites use `.toList()` / `.toMap()`. The win is that **during** the parse, no full-list intermediate; only after collect do we hold the materialized collection.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
|------|---------|----------|
| module-common 위치 | 두 모듈이 의존성 그래프 깨지 않고 공유 | module-common에 새 코드 추가 (현재 파서 없음, 도메인 무관 유틸) |
| `Flow<Map>` 출력 | 호출부 매핑 책임 유지, 도메인 경계 보존 | `Map<String, Any>` type-safety 약함 |
| `readValueAsTree` per record | 단순성, 버그 가능성 ↓ | 수동 루프 대비 record당 ~1회 tree allocation |

### Risk

- `JsonParser.readValueAsTree()` throw 후 cursor 위치 정확도 — Jackson 버전 의존. **Mitigation**: unit test with malformed middle record (test case 2) asserts skip 동작. `skipChildren()` 후 명시적 resync.
- downstream calculator consumer `GzipJsonlSnapshotRecordReader` 변경이 다른 호출부에 영향 — **Mitigation**: implementation 단계에서 모든 injection site grep 후 함께 업데이트.
- 메트릭 라벨 cardinality 증가 — `source` 라벨을 enum으로 제한.
- 핫 경로 (`OcidLookupPhase`) 회귀 시 즉시 롤백 필요 — **Mitigation**: §4.4 feature flag. `externalapi.parser.streaming.enabled=false`로 legacy inline `readTree` 경로 복귀 가능, 코드 revert 불필요.
- `OcidLookupPhase` constructor param 추가 (`streamingChunkParser`, `chunkParserMetrics`)로 테스트 mock 깨짐 — **Mitigation**: 구현 시 `ExternalApiSchedulerTest` 등 mock 추가.

### Non-Risk

- gz 형식 호환성 — `GZIPInputStream` 표준 JDK. chunk writer(`GzipJsonlChunkWriter`)는 단순 `writeValueAsBytes + '\n'`, 형식 변경 없음.
- Memory leak — `GZIPInputStream.use {}` + `JsonParser.use {}` 둘 다 close 보장. Flow cancellation 시 자동으로 `flow {}` 블록 종료 → `use {}` finally 발동.

---

## 13. Result / Evidence (post-implementation)

TBD after implementation:

| Metric | Baseline | Target | Notes |
|--------|----------|--------|-------|
| ext-api heap | 410MB | < 200MB | `jvm_memory_used_bytes{application="external-api"}` |
| ext-api throughput (users fetched) | baseline | within ±5% | `rate(external_api_users_fetched_total[5m])` |
| `chunk_parser_records_emitted_total` | n/a | per-pipeline | new |
| `chunk_parser_records_skipped_total` | n/a | < 0.1% of emitted | new |

---

## 14. Summary

> Token-stream `JsonParser` exposed as a shared `module-common` utility, returning `Flow<Map<String, Any>>`; three call sites swap to it without changing their public APIs, preserving ext-api heap < 200MB target with zero throughput regression.
