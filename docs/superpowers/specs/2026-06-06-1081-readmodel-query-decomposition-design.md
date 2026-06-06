# Design: Rest-Controller ReadModelQueryService.batchQuery decomposition (issue #1081)

- Status: Accepted
- Date: 2026-06-06
- Owner: zbnerd
- Issue: #1081
- Note: extends the "parse → delegate → orchestration" discipline to the read-model query layer. Same surgical pattern as #1088.

---

## 1. Background / Problem

### Background

`ReadModelQueryService.batchQuery` (module-rest-controller, lines 23-87) is a 65-line method that mixes three structurally different concerns inside one `forEach` body:

1. **SQL/DB layer** (lines 29-44): dynamic `OR`-chained pair-predicate construction, `MapSqlParameterSource` population, `NamedParameterJdbcTemplate.queryForList` call.
2. **JSON / deserialization layer** (lines 56-68): gzip decompression via `GzipUtils`, `ObjectMapper.readTree` traversal, equipment-list re-serialization, field extraction.
3. **Business / domain layer** (lines 46-83): staleness filtering (`updatedAt` vs `minimumUpdatedAt`), JSON-first-with-DB-fallback field resolution (`summary.totalCost` else `total_cost`).

The three layers run in series but are interleaved. The DB row, the JSON tree, and the domain response all share the same closure.

### Problem

- SQL composition is hidden inside a method whose name says nothing about SQL.
- JSON tree walking + domain fallback is repeated inline; the fallback rule (`json ?? row["col"]`) is implicit.
- Staleness logic is a one-line predicate that cannot be unit-tested without mocking `NamedParameterJdbcTemplate` and serializing gzip bytes.
- `batchQuery` reads as one long procedure instead of an orchestrator over named steps.

### Goal

Split `batchQuery` into three named units, each with a single testable concern, and reduce `batchQuery` to a 5-line orchestrator.

---

## 2. Decision

Three new types, one slimmed consumer.

### A) `ReadModelRowQuery` (object)

Pure builder for the dynamic SQL + parameter source. No Spring, no I/O.

```kotlin
object ReadModelRowQuery {
    fun build(requests: Map<String, Int>): Pair<String, MapSqlParameterSource> {
        val params = MapSqlParameterSource()
        val predicates = requests.entries.mapIndexed { i, (userIgn, presetNo) ->
            params
                .addValue("userIgn$i", userIgn)
                .addValue("presetNo$i", presetNo)
            "(user_ign = :userIgn$i AND preset_no = :presetNo$i)"
        }.joinToString(" OR ")

        val sql = """
            SELECT user_ign, preset_no, document, total_cost, equipment_count, calculated_at, updated_at
            FROM character_equipment_read_model
            WHERE ($predicates)
              AND user_ign IS NOT NULL
        """.trimIndent()
        return sql to params
    }
}
```

### B) `StalenessCheck` (object, pure)

Pure function over the row list. No dependencies.

```kotlin
object StalenessCheck {
    fun partitionStale(
        rows: List<Map<String, Any?>>,
        minimumUpdatedAt: Instant?,
    ): Pair<List<Map<String, Any?>>, Int> {
        if (minimumUpdatedAt == null) return rows to 0
        val fresh = mutableListOf<Map<String, Any?>>()
        var stale = 0
        rows.forEach { row ->
            val updatedAt = (row["updated_at"] as? Timestamp)?.toInstant() ?: Instant.EPOCH
            if (updatedAt.isBefore(minimumUpdatedAt)) stale++ else fresh.add(row)
        }
        return fresh to stale
    }
}
```

### C) `ReadModelDocumentExtractor` (`@Component`)

Wraps gzip + JSON tree + domain fallback. Takes the row + the compressed document, returns a `V6ExpectationResponse`. JSON-first with DB-row fallback lives here.

```kotlin
@Component
class ReadModelDocumentExtractor(
    private val objectMapper: ObjectMapper,
) {
    fun extract(userIgn: String, compressed: ByteArray, row: Map<String, Any?>): V6ExpectationResponse {
        val json = GzipUtils.decompress(compressed)
        val tree = objectMapper.readTree(json)

        val equipmentNode = tree["equipment"]
        @Suppress("UNCHECKED_CAST")
        val equipment: List<Map<String, Any?>> = if (equipmentNode != null && !equipmentNode.isNull) {
            objectMapper.readValue(
                equipmentNode.toString(),
                objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java),
            ) as List<Map<String, Any?>>
        } else emptyList()

        return V6ExpectationResponse(
            userIgn = userIgn,
            presetNo = tree["presetNo"]?.asInt() ?: (row["preset_no"] as Number).toInt(),
            totalCost = tree["summary"]?.get("totalCost")?.decimalValue()
                ?: row["total_cost"] as? BigDecimal
                ?: BigDecimal.ZERO,
            equipmentCount = tree["summary"]?.get("equipmentCount")?.asInt()
                ?: (row["equipment_count"] as? Number)?.toInt()
                ?: 0,
            equipment = equipment,
            calculatedAt = tree["metadata"]?.get("calculatedAt")?.asText()?.let(Instant::parse)
                ?: (row["calculated_at"] as? Timestamp)?.toInstant()
                ?: Instant.now(),
        )
    }
}
```

### D) Slimmed `batchQuery`

```kotlin
fun batchQuery(
    requests: Map<String, Int>,
    maxAge: Duration? = null,
): Map<String, V6ExpectationResponse> {
    if (requests.isEmpty()) return emptyMap()

    val (sql, params) = ReadModelRowQuery.build(requests)
    val raw = jdbc.queryForList(sql, params)
    val minimumUpdatedAt = maxAge?.let { Instant.now().minus(it) }
    val (fresh, stale) = StalenessCheck.partitionStale(raw, minimumUpdatedAt)

    val result = LinkedHashMap<String, V6ExpectationResponse>(fresh.size)
    fresh.forEach { row ->
        val userIgn = row["user_ign"].toString()
        result[userIgn] = extractor.extract(userIgn, row["document"] as ByteArray, row)
    }
    log.debug("Read model query: requested={}, hits={}, stale={}", requests.size, result.size, stale)
    return result
}
```

`batchQuery` now reads as: guard, build SQL, run SQL, partition, extract, log.

---

## 3. Trade-offs

### Sensitivity

- **JSON schema:** `equipmentNode` / `summary.totalCost` / `metadata.calculatedAt` field names are baked into the extractor. A schema change requires an extractor update. Same as before — no change in coupling.
- **DB column shape:** `user_ign`, `preset_no`, `document`, `total_cost`, `equipment_count`, `calculated_at`, `updated_at`. These are still referenced in `ReadModelRowQuery` (SQL) and `StalenessCheck` (`updated_at`). Splitting spreads the column-name knowledge across two files, but each file references only the columns it needs (`StalenessCheck` only `updated_at`).
- **Test coverage:** `StalenessCheck` and `ReadModelRowQuery` are now directly unit-testable. The orchestrator test stays in `ReadModelQueryService`.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| `ReadModelRowQuery` as `object` (not `@Component`) | No DI, pure function, trivial test | One global point of access; OK for stateless builder |
| `StalenessCheck` as pure function | No mocking, table-driven test | Returns `Pair<list, count>` — slight API awkwardness vs. two collections |
| `ReadModelDocumentExtractor` as `@Component` | Spring-injected `ObjectMapper`, follows project convention | One extra bean |
| Keep `ByteArray` extraction + DB fallback in `extract` | Single source of fallback rule | Method is ~25 lines; manageable |
| Skip introducing a `Document`-shaped value object | YAGNI — current shape is row + bytes, no new type | None |

### Risk

- **Staleness semantics drift:** The original code increments `stale++` and `return@forEach` for stale rows, then logs `hits = result.size` and `stale = stale`. The new `partitionStale` + `forEach` produces the same counts (verified by re-reading the loop). The log line at the end is unchanged.
- **Parameter binding order:** `MapSqlParameterSource` accepts out-of-order `addValue` calls; the SQL refers to `:userIgn$i` / `:presetNo$i` and the order inside the builder matches the index. No risk of mis-binding.
- **Empty request path:** `if (requests.isEmpty()) return emptyMap()` is preserved verbatim.
- **`ObjectMapper` reuse:** `ReadModelDocumentExtractor` is a `@Component`; the existing primary `ObjectMapper` is injected. No new `ObjectMapper()`.
- **Pair return from `partitionStale`:** Slightly less ergonomic than two collections. Acceptable — the call site consumes both immediately.

### Non-Risk

- DB schema: unchanged.
- Wire format: unchanged.
- `V6ExpectationResponse` shape: unchanged.
- Spring DI: only one new `@Component`. No new `@Configuration` or wiring.
- Module boundary (`module-rest-controller`): all new classes stay inside the module.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
|--------|------:|-------|
| `batchQuery` line count | 65 → ~20 | Orchestrator only |
| Inline gzip + JSON tree in `batchQuery` | Yes → No | Moved to `ReadModelDocumentExtractor.extract` |
| Inline dynamic SQL in `batchQuery` | Yes → No | Moved to `ReadModelRowQuery.build` |
| Inline staleness check in `batchQuery` | Yes → No | Moved to `StalenessCheck.partitionStale` |
| New top-level types | 3 | `ReadModelRowQuery`, `StalenessCheck`, `ReadModelDocumentExtractor` |
| New unit tests | ~6 | SQL string snapshot, partition no-maxAge, partition with-maxAge, partition empty, extract happy, extract fallback |
| `batchQuery` cyclomatic complexity | ~7 → ~3 | `forEach` is now one body line per row |

### Observed Result

Post-implementation:
- `ReadModelQueryService.batchQuery` reads top-to-bottom: empty-guard → build SQL → run → partition → forEach{extract} → log → return
- `ReadModelRowQuery.build` is independently testable as a string + param-source snapshot
- `StalenessCheck.partitionStale` is independently testable with a synthetic `List<Map<String, Any?>>`
- `ReadModelDocumentExtractor.extract` is independently testable with a hand-crafted gzip byte array
- All existing `module-rest-controller` tests still pass
- `./gradlew :module-rest-controller:test` passes
- `./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue` passes

---

## 5. Summary

> Split `ReadModelQueryService.batchQuery` into three single-purpose units — `ReadModelRowQuery` (SQL), `StalenessCheck` (pure), `ReadModelDocumentExtractor` (gzip + JSON + fallback) — and reduce `batchQuery` to a 5-line orchestrator.

---

## 6. Implementation Outline (reference for writing-plans)

1. Create `ReadModelRowQuery` (`module-rest-controller/.../read/ReadModelRowQuery.kt`) as a `Kotlin object` with `build(requests: Map<String, Int>): Pair<String, MapSqlParameterSource>`.
2. Create `StalenessCheck` (`module-rest-controller/.../read/StalenessCheck.kt`) as a `Kotlin object` with `partitionStale(rows, minimumUpdatedAt): Pair<List<Map<String, Any?>>, Int>`.
3. Create `ReadModelDocumentExtractor` (`module-rest-controller/.../read/ReadModelDocumentExtractor.kt`) as a `@Component` with `extract(userIgn, compressed, row): V6ExpectationResponse`.
4. Inject `ReadModelDocumentExtractor` into `ReadModelQueryService`. Rewrite `batchQuery` to use all three helpers.
5. Add unit tests:
   - `ReadModelRowQueryTest`: 0 / 1 / N requests, snapshot SQL string + non-empty params
   - `StalenessCheckTest`: null `minimumUpdatedAt` (no filter, stale=0), mixed fresh+stale rows, all stale, all fresh
   - `ReadModelDocumentExtractorTest`: happy path (JSON has all fields, fallback unused), JSON-missing presetNo falls back to row, gzip decompression failure
6. Run `./gradlew :module-rest-controller:test` and `./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue`.
