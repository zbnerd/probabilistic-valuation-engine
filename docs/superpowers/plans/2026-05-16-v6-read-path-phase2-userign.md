# V6 Read Path Phase 2: userIGN Batch Query Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V6 read path가 userIgn으로 read model을 직접 batch 조회하여 DeferredResult에 결과를 반환한다. Miss는 기존처럼 timeout 202.

**Architecture:** Synchronizer가 game_character 테이블 조회로 ocid→userIgn을 획득하여 read model에 user_ign 컬럼으로 저장. V6 BatchReadScheduler가 buffer에서 drain한 후 userIgn으로 read model을 batch 조회하고, hit는 DeferredResult에 200 결과 설정, miss는 timeout으로 202 반환.

**Tech Stack:** Kotlin, Spring MVC (DeferredResult), Spring JDBC (NamedParameterJdbcTemplate), PostgreSQL, JUnit 5, AssertJ, Mockito

**Scope:** Phase 2a (schema + synchronizer userIgn) + Phase 2a.5 (V6 fast path). 3-layer miss handling과 daily sync는 별도 plan.

**Spec:** `docs/superpowers/specs/2026-05-16-v6-read-model-userign-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `module-infra/src/main/resources/db/migration/V125__read_model_add_user_ign.sql` | user_ign 컬럼 추가 + backfill + 인덱스 |
| Modify | `module-synchronizer/.../domain/CalculatedEquipmentItem.kt` | GroupedEquipmentResult, EquipmentReadDocument, PreppedDocument에 userIgn 추가 |
| Create | `module-synchronizer/.../resolver/OcidUserIgnResolver.kt` | batch ocid→userIgn lookup |
| Modify | `module-synchronizer/.../builder/EquipmentDocumentBuilder.kt` | userIgn 파라미터 추가 |
| Modify | `module-synchronizer/.../preparer/EquipmentDocumentPreparer.kt` | PreppedDocument에 userIgn 포함 |
| Modify | `module-synchronizer/.../repository/EquipmentReadModelRepository.kt` | SQL에 user_ign 추가 |
| Modify | `module-synchronizer/.../processor/DefaultChunkProcessor.kt` | resolver 연동 |
| Modify | `module-rest-controller/build.gradle` | JDBC + PostgreSQL 의존성 추가 |
| Modify | `module-rest-controller/.../resources/application.yml` | datasource 설정 |
| Modify | `module-rest-controller/.../resources/application-local.yml` | local datasource 설정 |
| Modify | `module-rest-controller/.../read/ReadRequest.kt` | presetNo 필드 추가 |
| Modify | `module-rest-controller/.../controller/ExpectationV6Controller.kt` | presetNo query param 추가 |
| Modify | `module-rest-controller/.../read/ExpectationReadFacade.kt` | presetNo 전달 |
| Create | `module-rest-controller/.../read/ReadModelQueryService.kt` | read model batch 조회 + gzip 해제 |
| Create | `module-rest-controller/.../read/V6ExpectationResponse.kt` | V6 응답 DTO |
| Modify | `module-rest-controller/.../read/BatchReadScheduler.kt` | batch query + DeferredResult resolve |
| Modify | `module-rest-controller/.../config/V6ReadConfig.kt` | 새 bean wiring |
| Create | test files per component | TDD |

---

### Task 1: DB Migration V125 — user_ign 컬럼 추가

**Files:**
- Create: `module-infra/src/main/resources/db/migration/V125__read_model_add_user_ign.sql`

- [ ] **Step 1: migration 파일 작성**

```sql
-- V125__read_model_add_user_ign.sql

-- Step 1: 컬럼 추가 (nullable)
ALTER TABLE character_equipment_read_model
    ADD COLUMN user_ign TEXT;

-- Step 2: Backfill — game_character에서 ocid로 userIgn 역조회
UPDATE character_equipment_read_model r
SET user_ign = gc.user_ign
FROM game_character gc
WHERE r.ocid = gc.ocid;

-- Step 3: NOT NULL 제약
ALTER TABLE character_equipment_read_model
    ALTER COLUMN user_ign SET NOT NULL;

-- Step 4: V6 batch 조회용 인덱스
CREATE INDEX idx_equipment_read_model_user_ign_preset
    ON character_equipment_read_model (user_ign, preset_no);
```

- [ ] **Step 2: 컴파일 검증**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/resources/db/migration/V125__read_model_add_user_ign.sql
git commit -m "feat(db): add user_ign column to character_equipment_read_model with backfill"
```

---

### Task 2: Synchronizer — Domain model + OcidUserIgnResolver

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/domain/CalculatedEquipmentItem.kt`
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt`
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/resolver/OcidUserIgnResolverTest.kt`

- [ ] **Step 1: OcidUserIgnResolver failing test 작성**

```kotlin
package maple.synchronizer.resolver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.RowMapper

class OcidUserIgnResolverTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val resolver = OcidUserIgnResolver(jdbc)

    @Test
    fun `should return empty map for empty ocids`() {
        val result = resolver.resolve(emptySet())
        assertThat(result).isEmpty()
    }

    @Test
    fun `should resolve ocids to userIgn map`() {
        val ocids = setOf("ocid1", "ocid2")
        whenever(jdbc.query(any<String>(), any<RowMapper<Pair<String, String>>>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf("ocid1" to "아델", "ocid2" to "강은호"))

        val result = resolver.resolve(ocids)
        assertThat(result).hasSize(2)
        assertThat(result["ocid1"]).isEqualTo("아델")
        assertThat(result["ocid2"]).isEqualTo("강은호")
    }

    @Test
    fun `should handle partial miss — return only found mappings`() {
        val ocids = setOf("ocid1", "ocid_not_found")
        whenever(jdbc.query(any<String>(), any<RowMapper<Pair<String, String>>>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf("ocid1" to "아델"))

        val result = resolver.resolve(ocids)
        assertThat(result).containsEntry("ocid1", "아델")
        assertThat(result).hasSize(1)
    }
}
```

- [ ] **Step 2: test가 fail하는지 확인**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.resolver.OcidUserIgnResolverTest" 2>&1 | tail -10`
Expected: FAIL (class not found)

- [ ] **Step 3: OcidUserIgnResolver 구현**

```kotlin
package maple.synchronizer.resolver

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

@Component
class OcidUserIgnResolver(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(ocids: Set<String>): Map<String, String> {
        if (ocids.isEmpty()) return emptyMap()

        val sql = """
            SELECT ocid, user_ign FROM game_character WHERE ocid IN (:ocids)
        """.trimIndent()

        val params = MapSqlParameterSource("ocids", ocids.toList())

        val results = jdbc.query(sql, params) { rs, _ ->
            rs.getString("ocid") to rs.getString("user_ign")
        }

        val mapping = results.toMap()
        log.debug("Resolved {} of {} ocids to userIgn", mapping.size, ocids.size)
        return mapping
    }
}
```

- [ ] **Step 4: Domain model에 userIgn 추가**

`CalculatedEquipmentItem.kt`의 `GroupedEquipmentResult`, `EquipmentReadDocument`, `PreppedDocument`에 userIgn 필드 추가:

```kotlin
// GroupedEquipmentResult에 userIgn 추가
data class GroupedEquipmentResult(
    val readKey: String,
    val ocid: String,
    val presetNo: Int,
    val userIgn: String = "",  // Default for backward compat during migration
    val items: List<CalculatedEquipmentItem>,
)

// EquipmentReadDocument에 userIgn 추가
data class EquipmentReadDocument(
    val ocid: String,
    val presetNo: Int,
    val userIgn: String = "",  // Default for backward compat
    val summary: EquipmentSummary,
    val equipment: List<Map<String, Any?>>,
    val metadata: EquipmentReadMetadata,
)
```

`EquipmentDocumentPreparer.kt`의 `PreppedDocument`에 userIgn 추가:

```kotlin
data class PreppedDocument(
    val readKey: String,
    val ocid: String,
    val presetNo: Short,
    val userIgn: String,       // NEW
    val compressed: ByteArray,
    val documentHash: String,
    val totalCost: java.math.BigDecimal,
    val equipmentCount: Int,
    val calculatedAt: java.sql.Timestamp,
)
```

- [ ] **Step 5: test 통과 확인**

Run: `./gradlew :module-synchronizer:test --tests "maple.synchronizer.resolver.OcidUserIgnResolverTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/ module-synchronizer/src/main/kotlin/maple/synchronizer/domain/ module-synchronizer/src/test/
git commit -m "feat(synchronizer): add OcidUserIgnResolver and userIgn to domain models"
```

---

### Task 3: Synchronizer — Pipeline wiring (builder, preparer, repository)

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/builder/EquipmentDocumentBuilder.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/builder/EquipmentDocumentBuilderTest.kt` (create if needed)
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparerTest.kt` (create if needed)

- [ ] **Step 1: EquipmentDocumentBuilder failing test**

```kotlin
package maple.synchronizer.builder

import maple.synchronizer.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EquipmentDocumentBuilderTest {

    private val builder = EquipmentDocumentBuilder()

    @Test
    fun `should build document with userIgn`() {
        val grouped = GroupedEquipmentResult(
            readKey = "ocid1:1",
            ocid = "ocid1",
            presetNo = 1,
            userIgn = "진격캐넌",
            items = listOf(
                CalculatedEquipmentItem(
                    ocid = "ocid1", presetNo = 1, itemName = "item1",
                    itemLevel = 200, itemPart = "Weapon", itemEquipmentPart = null,
                    potentialGrade = null, potentialOptions = null,
                    additionalGrade = null, additionalOptions = null,
                    currentStar = 0, targetStar = 0, status = "SKIPPED",
                    totalCost = BigDecimal.ZERO, blackCubeCost = BigDecimal.ZERO,
                    additionalCubeCost = BigDecimal.ZERO, starforceCost = BigDecimal.ZERO,
                    errorMessage = null,
                )
            ),
        )

        val doc = builder.build("run1", "chunk1", grouped)
        assertThat(doc.userIgn).isEqualTo("진격캐넌")
        assertThat(doc.ocid).isEqualTo("ocid1")
        assertThat(doc.presetNo).isEqualTo(1)
    }
}
```

- [ ] **Step 2: EquipmentDocumentBuilder 구현**

`EquipmentDocumentBuilder.kt`의 `build` 메서드 수정:

```kotlin
fun build(runId: String, chunkId: String, grouped: GroupedEquipmentResult): EquipmentReadDocument {
    val totalCost = grouped.items.fold(BigDecimal.ZERO) { acc, item -> acc + item.totalCost }
    val equipmentCount = grouped.items.count { it.status != "SKIPPED" }

    return EquipmentReadDocument(
        ocid = grouped.ocid,
        presetNo = grouped.presetNo,
        userIgn = grouped.userIgn,
        summary = EquipmentSummary(
            totalCost = totalCost,
            equipmentCount = equipmentCount,
        ),
        equipment = grouped.items.map { it.toMap() },
        metadata = EquipmentReadMetadata(
            sourceRunId = runId,
            sourceChunkId = chunkId,
            calculatedAt = Instant.now(),
        ),
    )
}
```

- [ ] **Step 3: EquipmentDocumentPreparer 수정**

`EquipmentDocumentPreparer.prepareOne` 수정:

```kotlin
private fun prepareOne(doc: EquipmentReadDocument): PreppedDocument {
    val json = objectMapper.writeValueAsString(doc)
    return PreppedDocument(
        readKey = "${doc.ocid}:${doc.presetNo}",
        ocid = doc.ocid,
        presetNo = doc.presetNo.toShort(),
        userIgn = doc.userIgn,
        compressed = GzipUtils.compress(json),
        documentHash = sha256Hex(json),
        totalCost = doc.summary.totalCost,
        equipmentCount = doc.summary.equipmentCount,
        calculatedAt = Timestamp.from(doc.metadata.calculatedAt),
    )
}
```

- [ ] **Step 4: EquipmentReadModelRepository SQL에 user_ign 추가**

`upsertBatch` 메서드의 SQL 수정:

```kotlin
private fun upsertBatch(runId: String, chunkId: String, batch: List<PreppedDocument>): Int {
    val sql = """
        INSERT INTO character_equipment_read_model (
            read_key, ocid, preset_no, user_ign, document, document_hash,
            total_cost, equipment_count, calculated_at,
            source_run_id, source_chunk_id, updated_at
        )
        SELECT
            unnest(:readKeys), unnest(:ocids), unnest(:presetNos),
            unnest(:userIgns), unnest(:documents), unnest(:documentHashes),
            unnest(:totalCosts), unnest(:equipmentCounts), unnest(:calculatedAts),
            :runId, :chunkId, now()
        ON CONFLICT (read_key) DO UPDATE SET
            user_ign = excluded.user_ign,
            document = excluded.document,
            document_hash = excluded.document_hash,
            total_cost = excluded.total_cost,
            equipment_count = excluded.equipment_count,
            calculated_at = excluded.calculated_at,
            source_run_id = excluded.source_run_id,
            source_chunk_id = excluded.source_chunk_id,
            updated_at = now()
        WHERE character_equipment_read_model.document_hash IS DISTINCT FROM excluded.document_hash
    """.trimIndent()

    return jdbc.update(sql, MapSqlParameterSource()
        .addValue("runId", runId)
        .addValue("chunkId", chunkId)
        .addValue("readKeys", batch.map { it.readKey }.toTypedArray())
        .addValue("ocids", batch.map { it.ocid }.toTypedArray())
        .addValue("presetNos", batch.map { it.presetNo }.toTypedArray())
        .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
        .addValue("documents", batch.map { it.compressed }.toTypedArray())
        .addValue("documentHashes", batch.map { it.documentHash }.toTypedArray())
        .addValue("totalCosts", batch.map { it.totalCost }.toTypedArray())
        .addValue("equipmentCounts", batch.map { it.equipmentCount }.toTypedArray())
        .addValue("calculatedAts", batch.map { it.calculatedAt }.toTypedArray())
    )
}
```

- [ ] **Step 5: 기존 테스트 통과 확인**

Run: `./gradlew :module-synchronizer:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 컴파일 전체 검증**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/builder/ module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/ module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ module-synchronizer/src/test/
git commit -m "feat(synchronizer): wire userIgn through builder, preparer, repository pipeline"
```

---

### Task 4: Synchronizer — DefaultChunkProcessor에 OcidUserIgnResolver 연동

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt`
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/processor/DefaultChunkProcessorTest.kt` (create if needed)

- [ ] **Step 1: DefaultChunkProcessor 수정**

```kotlin
@Component
class DefaultChunkProcessor(
    private val resultFileReader: ResultFileReader,
    private val readModelRepository: EquipmentReadModelRepository,
    private val ocidUserIgnResolver: OcidUserIgnResolver,   // NEW
    private val metrics: SynchronizerMetrics,
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) : ChunkProcessor {

    private val documentBuilder = EquipmentDocumentBuilder()
    private val preparer = EquipmentDocumentPreparer(objectMapper)

    private val log = LoggerFactory.getLogger(DefaultChunkProcessor::class.java)

    override fun process(input: ChunkProcessInput): ChunkProcessResult {
        val grouped = timed(metrics.fileReadTimer()) {
            resultFileReader.readAndGroupByCompositeKey(input.objectKey)
        }

        // NEW: Resolve ocid → userIgn
        val ocids = grouped.map { it.ocid }.toSet()
        val ocidToUserIgn = ocidUserIgnResolver.resolve(ocids)

        val documents = timed(metrics.documentBuildTimer()) {
            grouped.map { g ->
                val userIgn = ocidToUserIgn[g.ocid] ?: ""
                val withUserIgn = g.copy(userIgn = userIgn)
                documentBuilder.build(input.sourceRunId, input.sourceChunkId, withUserIgn)
            }
        }

        val itemsCount = grouped.sumOf { it.items.size.toLong() }

        log.info("[Synchronizer] grouped {} results into {} documents", input.resultCount, documents.size)

        metrics.incrementDocuments(documents.size)
        metrics.incrementItems(itemsCount)
        metrics.recordChunkSize(documents.size, itemsCount)
        documents.forEach { metrics.recordDocumentEquipment(it.summary.equipmentCount) }

        val prepped = preparer.prepare(documents)

        metrics.mainUpsertTimer().record(Runnable {
            readModelRepository.bulkUpsert(input.sourceRunId, input.sourceChunkId, prepped)
        })

        return ChunkProcessResult(
            documentCount = documents.size,
            itemCount = itemsCount,
            jsonRowCount = input.resultCount.toLong(),
        )
    }

    private inline fun <T> timed(timer: Timer, block: () -> T): T {
        val sample = Timer.start()
        return block().also { sample.stop(timer) }
    }
}
```

- [ ] **Step 2: 기존 테스트 통과 + 컴파일 확인**

Run: `./gradlew :module-synchronizer:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/processor/DefaultChunkProcessor.kt
git commit -m "feat(synchronizer): integrate OcidUserIgnResolver into DefaultChunkProcessor"
```

---

### Task 5: V6 — JDBC 의존성 + Datasource 설정

**Files:**
- Modify: `module-rest-controller/build.gradle`
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application-local.yml`

- [ ] **Step 1: build.gradle에 JDBC + PostgreSQL 추가**

`dependencies` 블록에 추가:

```groovy
    // JDBC for read model queries
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)
```

- [ ] **Step 2: application.yml에 datasource 설정**

`spring:` 블록 내에 추가:

```yaml
  datasource:
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2
```

- [ ] **Step 3: application-local.yml에 local DB 연결 추가**

기존 내용에 추가:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :module-rest-controller:compileKotlin --continue 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/build.gradle module-rest-controller/src/main/resources/
git commit -m "feat(rest-controller): add JDBC dependency and datasource config for V6 read path"
```

---

### Task 6: V6 — ReadRequest presetNo + Controller 변경

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadRequest.kt`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt`
- Modify: `module-rest-controller/src/test/kotlin/maple/restcontroller/controller/ExpectationV6ControllerTest.kt`

- [ ] **Step 1: ReadRequest에 presetNo 추가**

```kotlin
package maple.restcontroller.read

import java.util.UUID

data class ReadRequest(
    val requestId: UUID = UUID.randomUUID(),
    val userIgn: String,
    val presetNo: Int = 1
)
```

- [ ] **Step 2: Controller에 presetNo query param 추가**

```kotlin
@GetMapping("/{userIgn}/expectation")
fun getExpectation(
    @PathVariable @ValidUserIgn userIgn: String,
    @RequestParam(defaultValue = "1") presetNo: Int
): DeferredResult<ResponseEntity<*>> {
    log.debug("V6 read request userIgn={} presetNo={}", maskIgn(userIgn), presetNo)
    val deferred = DeferredResult<ResponseEntity<*>>(
        properties.requestTimeoutMs
    )
    facade.enqueue(userIgn, presetNo, deferred)
    return deferred
}
```

- [ ] **Step 3: Facade에 presetNo 파라미터 추가**

`enqueue` 메서드 시그니처 변경:

```kotlin
fun enqueue(userIgn: String, presetNo: Int, deferred: DeferredResult<ResponseEntity<*>>) {
    metrics.requestTotal.increment()

    val isFirst = registry.register(userIgn, deferred)

    if (isFirst) {
        metrics.dedupMissTotal.increment()
        val request = ReadRequest(userIgn = userIgn, presetNo = presetNo)

        if (!buffer.offer(request)) {
            metrics.bufferRejectedTotal.increment()
            registry.cleanup(userIgn, deferred)
            log.warn("Buffer full, rejecting request userIgn={}", maskIgn(userIgn))
            deferred.setErrorResult(
                ResponseEntity.status(503)
                    .header("Retry-After", "1")
                    .build<Any>()
            )
            return
        }
        log.debug("Buffered read request userIgn={}", maskIgn(userIgn))
    } else {
        metrics.dedupHitTotal.increment()
        log.debug("Dedup hit for userIgn={}", maskIgn(userIgn))
    }

    deferred.onTimeout {
        metrics.timeoutTotal.increment()
        deferred.setErrorResult(
            ResponseEntity.accepted().build<Any>()
        )
    }

    deferred.onCompletion {
        registry.cleanup(userIgn, deferred)
    }
}
```

- [ ] **Step 4: Controller test 수정**

기존 테스트의 mock 호출 경로를 업데이트하고 presetNo 파라미터 추가:

```kotlin
@Test
fun `should buffer valid request`() {
    mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌")
        .param("presetNo", "1"))
        .andExpect(status().isOk)

    assertThat(buffer.size()).isEqualTo(1)
    assertThat(registry.size()).isEqualTo(1)
}

@Test
fun `should use default presetNo when not specified`() {
    mockMvc.perform(get("/api/v6/characters/{userIgn}/expectation", "진격캐넌"))
        .andExpect(status().isOk)

    assertThat(buffer.size()).isEqualTo(1)
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :module-rest-controller:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadRequest.kt module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt module-rest-controller/src/main/kotlin/maple/restcontroller/read/ExpectationReadFacade.kt module-rest-controller/src/test/
git commit -m "feat(rest-controller): add presetNo parameter to V6 read path"
```

---

### Task 7: V6 — ReadModelQueryService

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/V6ExpectationResponse.kt`
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelQueryServiceTest.kt`

- [ ] **Step 1: V6ExpectationResponse DTO**

```kotlin
package maple.restcontroller.read

import java.math.BigDecimal
import java.time.Instant

data class V6ExpectationResponse(
    val userIgn: String,
    val presetNo: Int,
    val totalCost: BigDecimal,
    val equipmentCount: Int,
    val equipment: List<Map<String, Any?>>,
    val calculatedAt: Instant,
)
```

- [ ] **Step 2: ReadModelQueryService failing test**

```kotlin
package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

class ReadModelQueryServiceTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val objectMapper = ObjectMapper()
    private val service = ReadModelQueryService(jdbc, objectMapper)

    @Test
    fun `should return empty map for empty userIgns`() {
        val result = service.batchQuery(emptySet())
        assertThat(result).isEmpty()
    }

    @Test
    fun `should decompress and parse read model rows`() {
        val response = mapOf(
            "userIgn" to "아델",
            "presetNo" to 1,
            "summary" to mapOf("totalCost" to 1000, "equipmentCount" to 5),
            "equipment" to emptyList<Any>(),
            "metadata" to mapOf("calculatedAt" to "2026-01-01T00:00:00Z")
        )
        val json = objectMapper.writeValueAsBytes(response)
        val compressed = GzipUtils.compress(String(json))

        whenever(jdbc.query(any<String>(), any<RowMapper<Pair<String, ByteArray>>>(), any<MapSqlParameterSource>()))
            .thenReturn(listOf("아델" to compressed))

        val result = service.batchQuery(setOf("아델"))
        assertThat(result).containsKey("아델")
        assertThat(result["아델"]!!.userIgn).isEqualTo("아델")
    }
}
```

- [ ] **Step 3: test가 fail하는지 확인**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelQueryServiceTest" 2>&1 | tail -5`
Expected: FAIL

- [ ] **Step 4: ReadModelQueryService 구현**

```kotlin
package maple.restcontroller.read

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class ReadModelQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun batchQuery(userIgns: Set<String>): Map<String, V6ExpectationResponse> {
        if (userIgns.isEmpty()) return emptyMap()

        val sql = """
            SELECT user_ign, preset_no, document, total_cost, equipment_count, calculated_at
            FROM character_equipment_read_model
            WHERE user_ign IN (:userIgns)
        """.trimIndent()

        val params = MapSqlParameterSource("userIgns", userIgns.toList())

        val rows = jdbc.query(sql, params) { rs, _ ->
            rs.getString("user_ign") to rs.getBytes("document")
        }

        return rows.associate { (userIgn, compressed) ->
            val json = GzipUtils.decompress(compressed)
            val tree = objectMapper.readTree(json)

            userIgn to V6ExpectationResponse(
                userIgn = userIgn,
                presetNo = tree.get("presetNo")?.asInt() ?: 1,
                totalCost = tree.get("summary")?.get("totalCost")?.decimalValue() ?: BigDecimal.ZERO,
                equipmentCount = tree.get("summary")?.get("equipmentCount")?.asInt() ?: 0,
                equipment = objectMapper.readValue(
                    tree.get("equipment").toString(),
                    objectMapper.typeFactory.constructCollectionType(List::class.java, Map::class.java)
                ),
                calculatedAt = java.time.Instant.parse(
                    tree.get("metadata")?.get("calculatedAt")?.asText() ?: java.time.Instant.now().toString()
                ),
            )
        }
    }
}
```

- [ ] **Step 5: test 통과 확인**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.ReadModelQueryServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelQueryService.kt module-rest-controller/src/main/kotlin/maple/restcontroller/read/V6ExpectationResponse.kt module-rest-controller/src/test/kotlin/maple/restcontroller/read/ReadModelQueryServiceTest.kt
git commit -m "feat(rest-controller): add ReadModelQueryService for V6 batch read model queries"
```

---

### Task 8: V6 — BatchReadScheduler batch processing + Config wiring

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/config/V6ReadConfig.kt`
- Modify: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/ExpectationReadFacadeTest.kt`

- [ ] **Step 1: BatchReadScheduler에 ReadModelQueryService 연동**

```kotlin
class BatchReadScheduler(
    private val buffer: LocalRequestBuffer,
    private val registry: InflightRequestRegistry,
    private val queryService: ReadModelQueryService,     // NEW
    private val properties: V6ReadProperties
) : SmartLifecycle {

    // ... start(), stop() unchanged ...

    @Scheduled(fixedDelayString = "\${expectation.v6.batch-window-ms:10}")
    fun scheduledDrain() {
        if (!running) return
        val batch = buffer.drain(properties.maxBatchSize)
        if (batch.isEmpty()) return

        val userIgns = batch.map { it.userIgn }.toSet()
        val results = queryService.batchQuery(userIgns)

        batch.forEach { request ->
            val deferreds = registry.getAndRemove(request.userIgn)
            val response = results[request.userIgn]

            if (response != null) {
                deferreds.forEach { deferred ->
                    deferred.setResult(
                        ResponseEntity.ok(response)
                    )
                }
            } else {
                // Miss: DeferredResult는 timeout으로 202 처리됨
                // registry에서 이미 제거했으므로 re-register하지 않음
                // timeout 콜백이 이미 facade에서 설정됨
            }
        }
    }
}
```

- [ ] **Step 2: V6ReadConfig에 새 bean wiring**

`V6ReadConfig`에 `ReadModelQueryService` bean 추가:

```kotlin
@Bean
fun readModelQueryService(
    jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper
): ReadModelQueryService = ReadModelQueryService(jdbc, objectMapper)

@Bean
fun batchReadScheduler(
    buffer: LocalRequestBuffer,
    registry: InflightRequestRegistry,
    queryService: ReadModelQueryService
): BatchReadScheduler = BatchReadScheduler(buffer, registry, queryService, properties)
```

`ObjectMapper` bean 추가 (Spring Boot auto-configured):

```kotlin
// ObjectMapper는 Spring Boot가 자동 설정하므로 주입만 하면 됨
// 별도 bean 정의 불필요
```

기존 `batchReadScheduler` bean 정의를 위로 변경.

- [ ] **Step 3: 전체 테스트 실행**

Run: `./gradlew :module-rest-controller:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 전체 컴파일 검증**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/
git commit -m "feat(rest-controller): wire BatchReadScheduler with ReadModelQueryService for V6 batch queries"
```

---

### Task 9: 전체 테스트 + 컴파일 검증

- [ ] **Step 1: Synchronizer 테스트**

Run: `./gradlew :module-synchronizer:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Rest-controller 테스트**

Run: `./gradlew :module-rest-controller:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 전체 프로젝트 컴파일**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "BUILD|FAIL|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 런타임 검증 — 서버 시작 + API 호출**

```bash
set -a && source .env && set +a && ./gradlew :module-rest-controller:bootRun --args="--spring.profiles.active=local"
```

```bash
# health check
curl -s -o /dev/null -w "%{http_code}" http://localhost:8084/actuator/health

# V6 endpoint (should return 202 if no data, 200 if data exists)
time curl -s -w "\nHTTP %{http_code} time=%{time_total}s" "http://localhost:8084/api/v6/characters/진격캐넌/expectation?presetNo=1"
```

Expected: 서버 정상 시작, V6 엔드포인트 응답 (202 또는 200)

- [ ] **Step 5: 최종 Commit (runtime 검증 후 수정사항이 있으면)**
