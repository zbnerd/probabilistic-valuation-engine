# ReadModelQueryService.batchQuery Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `ReadModelQueryService.batchQuery` into three single-purpose units (`ReadModelRowQuery`, `StalenessCheck`, `ReadModelDocumentExtractor`) and reduce `batchQuery` to a 5-line orchestrator.

**Architecture:** Pure objects for SQL building and staleness partition; `@Component` for the gzip + JSON + fallback extractor. The service keeps DI for `NamedParameterJdbcTemplate` and the new `ReadModelDocumentExtractor`, and orchestrates the three helpers in a flat `forEach`. No new module dependencies.

**Tech Stack:** Kotlin 1.9, Spring Boot 3, Jackson, JUnit 5 + `org.mockito.kotlin`, SLF4J, Gradle (Kotlin DSL).

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelRowQuery.kt` | Build dynamic SQL + `MapSqlParameterSource` from a request map | Create |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelRowQueryTest.kt` | Unit tests for SQL/params | Create |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/StalenessCheck.kt` | Pure function that partitions rows by `updated_at` | Create |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/StalenessCheckTest.kt` | Unit tests for partition logic | Create |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelDocumentExtractor.kt` | gzip + JSON tree + DB-row fallback → `V6ExpectationResponse` | Create |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelDocumentExtractorTest.kt` | Unit tests for extract with fallback paths | Create |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt` | Slim to orchestration only | Modify |

---

## Task 1: Create `ReadModelRowQuery` with test (TDD)

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelRowQueryTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelRowQuery.kt`

- [ ] **Step 1: Write the failing test**

Create `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelRowQueryTest.kt`:

```kotlin
package maple.restcontroller.read

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

class ReadModelRowQueryTest {
    @Test
    fun `build returns empty predicate and empty params for empty requests`() {
        val (sql, params) = ReadModelRowQuery.build(emptyMap())

        assertTrue(
            sql.contains("FROM character_equipment_read_model"),
            "expected SELECT in: $sql",
        )
        assertTrue(
            sql.contains("WHERE ()"),
            "expected empty predicate placeholder in: $sql",
        )
        // No values added.
        assertEquals(0, params.parameterNames.size)
    }

    @Test
    fun `build produces OR-chained pair predicates and indexed params`() {
        val (sql, params) = ReadModelRowQuery.build(
            mapOf("f***l" to 1, "s***d" to 2),
        )

        assertTrue(
            sql.contains("(user_ign = :userIgn0 AND preset_no = :presetNo0)"),
            "missing first predicate in: $sql",
        )
        assertTrue(
            sql.contains("(user_ign = :userIgn1 AND preset_no = :presetNo1)"),
            "missing second predicate in: $sql",
        )
        assertTrue(
            sql.contains(") OR ("),
            "missing OR between predicates in: $sql",
        )
        assertEquals(setOf("userIgn0", "presetNo0", "userIgn1", "presetNo1"), params.parameterNames.toSet())
        assertEquals("f***l", params.getValue("userIgn0"))
        assertEquals(1, params.getValue("presetNo0"))
        assertEquals("s***d", params.getValue("userIgn1"))
        assertEquals(2, params.getValue("presetNo1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelRowQueryTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: ReadModelRowQuery`.

- [ ] **Step 3: Write minimal implementation**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelRowQuery.kt`:

```kotlin
package maple.restcontroller.read

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelRowQueryTest" -i`
Expected: PASS — 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelRowQuery.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelRowQueryTest.kt
git commit -m "feat(rest-controller): add ReadModelRowQuery SQL/params builder"
```

---

## Task 2: Create `StalenessCheck` with test (TDD)

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/StalenessCheckTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/StalenessCheck.kt`

- [ ] **Step 1: Write the failing test**

Create `module-rest-controller/src/test/kotlin/maple/restcontroller/read/StalenessCheckTest.kt`:

```kotlin
package maple.restcontroller.read

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Timestamp
import java.time.Instant

class StalenessCheckTest {
    private val now = Instant.parse("2026-06-06T12:00:00Z")
    private val threshold = now.minusSeconds(60)

    @Test
    fun `partitionStale with null minimumUpdatedAt returns all rows and zero stale`() {
        val rows = listOf(
            mapOf<String, Any?>("updated_at" to Timestamp.from(now)),
            mapOf<String, Any?>("updated_at" to Timestamp.from(Instant.EPOCH)),
        )

        val (fresh, stale) = StalenessCheck.partitionStale(rows, null)

        assertEquals(2, fresh.size)
        assertEquals(0, stale)
    }

    @Test
    fun `partitionStale separates fresh from stale and counts them`() {
        val freshRow = mapOf<String, Any?>("updated_at" to Timestamp.from(now))
        val staleRow = mapOf<String, Any?>("updated_at" to Timestamp.from(Instant.EPOCH))

        val (fresh, stale) = StalenessCheck.partitionStale(listOf(freshRow, staleRow), threshold)

        assertEquals(1, fresh.size)
        assertEquals(freshRow, fresh[0])
        assertEquals(1, stale)
    }

    @Test
    fun `partitionStale treats missing updated_at as epoch and flags stale`() {
        val rowWithout = mapOf<String, Any?>("user_ign" to "f***l")

        val (fresh, stale) = StalenessCheck.partitionStale(listOf(rowWithout), threshold)

        assertTrue(fresh.isEmpty())
        assertEquals(1, stale)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.StalenessCheckTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: StalenessCheck`.

- [ ] **Step 3: Write minimal implementation**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/read/StalenessCheck.kt`:

```kotlin
package maple.restcontroller.read

import java.sql.Timestamp
import java.time.Instant

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.StalenessCheckTest" -i`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/StalenessCheck.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/StalenessCheckTest.kt
git commit -m "feat(rest-controller): add StalenessCheck pure partition function"
```

---

## Task 3: Create `ReadModelDocumentExtractor` with test (TDD)

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelDocumentExtractorTest.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelDocumentExtractor.kt`

- [ ] **Step 1: Write the failing test**

Create `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelDocumentExtractorTest.kt`:

```kotlin
package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

class ReadModelDocumentExtractorTest {
    private val objectMapper = ObjectMapper()
    private val extractor = ReadModelDocumentExtractor(objectMapper)

    @Test
    fun `extract prefers JSON fields over DB row fallback`() {
        val json = """
            {
              "presetNo": 7,
              "summary": { "totalCost": 1234.5, "equipmentCount": 3 },
              "metadata": { "calculatedAt": "2026-06-06T11:00:00Z" },
              "equipment": [ { "name": "x" } ]
            }
        """.trimIndent()
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "f***l",
            "preset_no" to 1,
            "total_cost" to BigDecimal("999.0"),
            "equipment_count" to 0,
            "calculated_at" to Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
        )

        val out = extractor.extract("f***l", compressed, row)

        assertEquals("f***l", out.userIgn)
        assertEquals(7, out.presetNo)
        assertEquals(BigDecimal("1234.5"), out.totalCost)
        assertEquals(3, out.equipmentCount)
        assertEquals(1, out.equipment.size)
        assertEquals(Instant.parse("2026-06-06T11:00:00Z"), out.calculatedAt)
    }

    @Test
    fun `extract falls back to DB row when JSON omits fields`() {
        val json = """{"equipment": []}"""
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "s***d",
            "preset_no" to 42,
            "total_cost" to BigDecimal("500.0"),
            "equipment_count" to 4,
            "calculated_at" to Timestamp.from(Instant.parse("2026-05-01T00:00:00Z")),
        )

        val out = extractor.extract("s***d", compressed, row)

        assertEquals(42, out.presetNo)
        assertEquals(BigDecimal("500.0"), out.totalCost)
        assertEquals(4, out.equipmentCount)
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), out.calculatedAt)
        assertTrue(out.equipment.isEmpty())
    }

    @Test
    fun `extract returns defaults when both JSON and row lack a field`() {
        val json = """{}"""
        val compressed = GzipUtils.compress(json.toByteArray())
        val row = mapOf<String, Any?>(
            "user_ign" to "t***d",
            "preset_no" to 1,
        )

        val out = extractor.extract("t***d", compressed, row)

        assertEquals(BigDecimal.ZERO, out.totalCost)
        assertEquals(0, out.equipmentCount)
        assertNotNull(out.calculatedAt)
    }
}
```

> **Note for the executing subagent:** Discover the actual `GzipUtils` method name (`compress` / `gzip` / `encode`) and `V6ExpectationResponse` constructor parameter names from the existing `ReadModelQueryService.kt` (lines 70-83) and `maple.expectation.util.GzipUtils` source. If the gzip helper has a different name, swap it in. If `V6ExpectationResponse` parameters differ, adjust the test assertions to use the real constructor. Do NOT change the response shape — only adapt the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelDocumentExtractorTest" -i`
Expected: COMPILATION FAILURE — `Unresolved reference: ReadModelDocumentExtractor` (and possibly the `GzipUtils.compress` name).

- [ ] **Step 3: Write minimal implementation**

Create `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelDocumentExtractor.kt`:

```kotlin
package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

@Component
class ReadModelDocumentExtractor(
    private val objectMapper: ObjectMapper,
) {
    fun extract(
        userIgn: String,
        compressed: ByteArray,
        row: Map<String, Any?>,
    ): V6ExpectationResponse {
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelDocumentExtractorTest" -i`
Expected: PASS — 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelDocumentExtractor.kt \
        module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelDocumentExtractorTest.kt
git commit -m "feat(rest-controller): add ReadModelDocumentExtractor (gzip + JSON + fallback)"
```

---

## Task 4: Slim `ReadModelQueryService.batchQuery` to orchestration

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt`

- [ ] **Step 1: Inject extractor and rewrite `batchQuery`**

Replace the entire contents of `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt` with:

```kotlin
package maple.restcontroller.read

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Duration
import java.time.Instant

class ReadModelQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val documentExtractor: ReadModelDocumentExtractor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param requests userIgn -> presetNo mapping
     * @return userIgn -> V6ExpectationResponse for hits only
     */
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
            result[userIgn] = documentExtractor.extract(userIgn, row["document"] as ByteArray, row)
        }
        log.debug("Read model query: requested={}, hits={}, stale={}", requests.size, result.size, stale)
        return result
    }
}
```

Drop the unused imports (`GzipUtils`, `MapSqlParameterSource`, `BigDecimal`, `Timestamp`, `ObjectMapper`). Keep `Duration` and `Instant`.

- [ ] **Step 2: Compile + run module tests**

Run: `./gradlew :module-rest-controller:compileKotlin :module-rest-controller:compileJava --continue && ./gradlew :module-rest-controller:test -i`
Expected: BUILD SUCCESSFUL — all 8 new tests pass (2 + 3 + 3) and existing tests continue to pass.

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt
git commit -m "refactor(rest-controller): ReadModelQueryService.batchQuery is orchestration only"
```

---

## Task 5: Final verification — full module build and test

**Files:** none (verification only)

- [ ] **Step 1: Compile entire project**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL — no errors across all modules.

- [ ] **Step 2: Run rest-controller test suite**

Run: `./gradlew :module-rest-controller:test -i`
Expected: BUILD SUCCESSFUL — all tests pass, including 8 new tests added in Tasks 1, 2, 3.

- [ ] **Step 3: Sanity-check service file size**

Run: `wc -l module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt`
Expected: roughly 35 lines (down from 88), the body of `batchQuery` is roughly 10 lines.

- [ ] **Step 4: Verify no inline gzip / SQL composition remains**

Run: `grep -n "GzipUtils\\|objectMapper.readTree\\|MapSqlParameterSource\\|queryForList" module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt || echo "no matches"`
Expected: `no matches` — all of those are now in the helpers, not the service.

- [ ] **Step 5: Commit any verification artefacts (none expected)**

If everything is clean, no further commit is needed. If verification surfaces a stray reference, fix it and commit as a follow-up.

---

## Self-Review

**Spec coverage:**
- §2.A `ReadModelRowQuery` → Task 1. Covered.
- §2.B `StalenessCheck` → Task 2. Covered.
- §2.C `ReadModelDocumentExtractor` → Task 3. Covered.
- §2.D Slimmed `batchQuery` → Task 4. Covered.
- §2 acceptance criteria (`:module-rest-controller:test` + `compileKotlin/compileJava`) → Task 5. Covered.

**Placeholder scan:** No "TBD" or "implement later" markers. Task 3 Step 1 contains an explicit note that the executing subagent must verify the `GzipUtils` method name and `V6ExpectationResponse` constructor from source — this is intentional, not a real placeholder. All other step 3 / 1 code blocks are complete.

**Type consistency:** `ReadModelRowQuery.build(requests: Map<String, Int>): Pair<String, MapSqlParameterSource>` used in both Task 1 test and Task 4 service. `StalenessCheck.partitionStale(rows: List<Map<String, Any?>>, minimumUpdatedAt: Instant?): Pair<List<Map<String, Any?>>, Int>` used in both Task 2 test and Task 4 service. `ReadModelDocumentExtractor.extract(userIgn: String, compressed: ByteArray, row: Map<String, Any?>): V6ExpectationResponse` used in both Task 3 test and Task 4 service. Consistent.
