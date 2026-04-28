# Write Path — Snapshot Calculator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write Path가 External API DTO를 완전히 차단하고, typed CalculationInput만 소비해서 계산 결과를 gzip artifact로 영속화하도록 만든다.

**Architecture:** External API Path가 EquipmentResponse → CalculationInput 변환을 담당하고 DB에 저장. Write Path는 CalculationInput만 조회해서 pure calculation 수행. 결과는 gzip 압축 후 calculation_results에 저장. Outbox 패턴으로 result_ready 이벤트 발행 보장.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.x, PostgreSQL, PGMQ, Flyway, JUnit 5, Mockito, AssertJ, Awaitility

**Pre-requisite:** Branch `feat/write-path-snapshot-calculator` created from `develop`.

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `module-infra/src/main/resources/db/migration/V117__write_path_tables.sql` | calculation_snapshot_inputs, calculation_results, outbox_events 테이블 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/CalculationInput.kt` | Write Path 계약 모델 (typed value objects) |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItem.kt` | 장비 아이템 값 객체 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/PotentialLines.kt` | 잠재능력 3-line 고정 구조 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/AddOption.kt` | 환생의 불꽃 add-option 값 객체 |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentSlot.kt` | 장비 슬롯 enum |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentPart.kt` | 보조무기 분류 enum |
| `module-core/src/main/kotlin/maple/expectation/core/dto/v4/StarforceScrollFlag.kt` | 스타포스 스크롤 flag enum |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt` | result 저장/조회 포트 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt` | outbox 이벤트 저장/조회 포트 |
| `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationInputPort.kt` | CalculationInput 저장/조회 포트 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/ResultReadyTopic.kt` | result_ready PGMQ topic |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/ResultReadyEventFactory.kt` | CALCULATION_COMPLETED 이벤트 팩토리 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationResultEntity.kt` | result JPA entity |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/OutboxEventEntity.kt` | outbox JPA entity |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotInputEntity.kt` | snapshot input JPA entity |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationResultRepository.kt` | result JPA repository |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/OutboxEventRepository.kt` | outbox JPA repository |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotInputRepository.kt` | snapshot input JPA repository |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt` | result port adapter |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt` | outbox port adapter |
| `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationInputPortAdapter.kt` | input port adapter |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverter.kt` | External API DTO → CalculationInput 변환기 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OutboxRelayWorker.kt` | outbox → MQ relay |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt` | 유실 이벤트 복구 스캐너 |

### Modified Files

| File | Change |
|------|--------|
| `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt` | RESULT_READY 상수 추가 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt` | completeCalculation에 result 저장 + outbox insert 추가 |
| `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt` | EquipmentResponse → CalculationInput 소비로 전환 |
| `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt` | CalculationInput 변환 + 저장 추가 |

### Test Files

| File | Tests |
|------|-------|
| `module-core/src/test/kotlin/maple/expectation/core/dto/v4/CalculationInputTest.kt` | CalculationInput 직렬화/역직렬화 |
| `module-core/src/test/kotlin/maple/expectation/core/dto/v4/PotentialLinesTest.kt` | nullable 규칙 검증 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverterTest.kt` | 변환 로직 |
| `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt` | completeCalculation with result + outbox |
| `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt` | outbox insert + idempotency |
| `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapterTest.kt` | result upsert + hash 비교 |

---

## Task 1: DB Migration — Write Path Tables

**Files:**
- Create: `module-infra/src/main/resources/db/migration/V117__write_path_tables.sql`

- [ ] **Step 1: Write migration SQL**

```sql
-- calculation_snapshot_inputs: External API Path가 저장하는 CalculationInput
CREATE TABLE calculation_snapshot_inputs (
    input_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    schema_version  INT DEFAULT 1,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_snapshot_inputs_job ON calculation_snapshot_inputs (job_id);

-- calculation_results: Write Path가 저장하는 gzip 압축 계산 결과
CREATE TABLE calculation_results (
    result_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL UNIQUE REFERENCES calculation_jobs(job_id),
    character_class  VARCHAR(64),
    preset_no        INT DEFAULT 1,
    schema_version   INT DEFAULT 1,
    content_type     VARCHAR(64) DEFAULT 'application/json',
    content_encoding VARCHAR(16) DEFAULT 'gzip',
    response_body    BYTEA,
    original_size    INT,
    compressed_size  INT,
    hash             VARCHAR(128),
    status           VARCHAR(16) DEFAULT 'SUCCESS',
    created_at       TIMESTAMPTZ DEFAULT now(),
    expires_at       TIMESTAMPTZ
);

CREATE INDEX idx_calc_results_job ON calculation_results (job_id);
CREATE INDEX idx_calc_results_char ON calculation_results (character_class, preset_no);
CREATE INDEX idx_calc_results_expires ON calculation_results (expires_at) WHERE expires_at IS NOT NULL;

-- outbox_events: 이벤트 발행 보장
CREATE TABLE outbox_events (
    event_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type       VARCHAR(64) NOT NULL,
    job_id           UUID NOT NULL,
    payload          JSONB,
    published        BOOLEAN DEFAULT false,
    publish_attempts INT DEFAULT 0,
    created_at       TIMESTAMPTZ DEFAULT now(),
    published_at     TIMESTAMPTZ,
    UNIQUE (job_id, event_type)
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at) WHERE published = false;
```

- [ ] **Step 2: Run compile to verify migration**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/resources/db/migration/V117__write_path_tables.sql
git commit -m "feat(db): add calculation_snapshot_inputs, calculation_results, outbox_events tables"
```

---

## Task 2: CalculationInput Typed Contract Model

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/AddOption.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentSlot.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentPart.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/StarforceScrollFlag.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/PotentialLines.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/EquipmentItem.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/dto/v4/CalculationInput.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/dto/v4/PotentialLinesTest.kt`
- Create: `module-core/src/test/kotlin/maple/expectation/core/dto/v4/CalculationInputTest.kt`

- [ ] **Step 1: Write test for AddOption defaults and equality**

```kotlin
package maple.expectation.core.dto.v4

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AddOptionTest {
    @Test
    fun `all fields populated correctly`() {
        val opt = AddOption(
            str = 10, dex = 20, int = 30, luk = 40,
            maxHp = 100, allStat = 5,
            attackPower = 50, magicPower = 60,
            bossDamage = 30, damage = 40
        )
        assertThat(opt.str).isEqualTo(10)
        assertThat(opt.allStat).isEqualTo(5)
    }
}
```

- [ ] **Step 2: Write AddOption data class**

```kotlin
package maple.expectation.core.dto.v4

data class AddOption(
    val str: Int,
    val dex: Int,
    val int: Int,
    val luk: Int,
    val maxHp: Int,
    val allStat: Int,
    val attackPower: Int,
    val magicPower: Int,
    val bossDamage: Int,
    val damage: Int
)
```

- [ ] **Step 3: Write typed enums**

EquipmentSlot:
```kotlin
package maple.expectation.core.dto.v4

enum class EquipmentSlot(val koreanName: String) {
    HAT("모자"),
    TOP("상의"),
    BOTTOM("하의"),
    SHOES("신발"),
    GLOVES("장갑"),
    CAPE("망토"),
    WEAPON("무기"),
    SECONDARY_WEAPON("보조무기"),
    EARRING("귀고리"),
    RING1("반지1"),
    RING2("반지2"),
    RING3("반지3"),
    RING4("반지4"),
    PENDANT1("펜던트1"),
    PENDANT2("펜던트2"),
    BELT("벨트"),
    MEDAL("훈장"),
    BADGE("뱃지"),
    EMBLEM("엠블렘"),
    POCKET("포켓 아이템"),
    SHOULDER("어깨장식"),
    HEART("기계심장"),
    FACE("얼굴장식"),
    EYE("눈장식"),
    POWER_SOURCE("파워 소스"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(name: String): EquipmentSlot =
            entries.find { it.koreanName == name } ?: UNKNOWN
    }
}
```

EquipmentPart:
```kotlin
package maple.expectation.core.dto.v4

enum class EquipmentPart(val koreanName: String) {
    WEAPON("무기"),
    SECONDARY_WEAPON("보조무기"),
    ARMOR("방어구"),
    ACCESSORY("장신구"),
    ETC("기타"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(name: String): EquipmentPart =
            entries.find { it.koreanName == name } ?: UNKNOWN
    }
}
```

StarforceScrollFlag:
```kotlin
package maple.expectation.core.dto.v4

enum class StarforceScrollFlag(val koreanValue: String) {
    USED("사용"),
    NOT_USED("미사용"),
    UNKNOWN("알 수 없음");

    companion object {
        fun fromKorean(value: String?): StarforceScrollFlag =
            if (value == null) NOT_USED
            else entries.find { it.koreanValue == value } ?: UNKNOWN
    }
}
```

- [ ] **Step 4: Write test for PotentialLines validation**

```kotlin
package maple.expectation.core.dto.v4

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PotentialLinesTest {
    @Test
    fun `grade is required`() {
        assertThat(PotentialLines(
            grade = PotentialGrade.LEGENDARY,
            line1 = "공격력 +12%",
            line2 = "보스 공격 시 데미지 +40%",
            line3 = "크리티컬 데미지 +8%"
        ).grade).isEqualTo(PotentialGrade.LEGENDARY)
    }

    @Test
    fun `lines can be null for empty options`() {
        val lines = PotentialLines(
            grade = PotentialGrade.RARE,
            line1 = null,
            line2 = null,
            line3 = null
        )
        assertThat(lines.line1).isNull()
    }

    @Test
    fun `all three lines are accessible`() {
        val lines = PotentialLines(
            grade = PotentialGrade.EPIC,
            line1 = "A",
            line2 = "B",
            line3 = "C"
        )
        assertThat(lines.asList()).containsExactly("A", "B", "C")
    }
}
```

- [ ] **Step 5: Write PotentialLines data class**

```kotlin
package maple.expectation.core.dto.v4

data class PotentialLines(
    val grade: PotentialGrade,
    val line1: PotentialOption?,
    val line2: PotentialOption?,
    val line3: PotentialOption?
) {
    fun asList(): List<String?> = listOf(line1, line2, line3)
}

typealias PotentialOption = String
```

Note: `PotentialGrade` already exists at `module-core/src/main/kotlin/maple/expectation/core/domain/model/PotentialGrade.kt`. Reuse it.

- [ ] **Step 6: Write EquipmentItem data class**

```kotlin
package maple.expectation.core.dto.v4

data class EquipmentItem(
    val part: EquipmentSlot,
    val equipmentPart: EquipmentPart,
    val itemName: String,
    val level: Int,
    val potential: PotentialLines?,
    val additionalPotential: PotentialLines?,
    val starforce: Int,
    val starforceScrollFlag: StarforceScrollFlag,
    val addOption: AddOption,
    val baseAttackPower: Int,
    val baseMagicPower: Int
)
```

- [ ] **Step 7: Write CalculationInput data class**

```kotlin
package maple.expectation.core.dto.v4

data class CalculationInput(
    val schemaVersion: Int = 1,
    val jobId: String,
    val userIgn: String,
    val characterClass: String,
    val presetNo: Int,
    val items: List<EquipmentItem>
)
```

- [ ] **Step 8: Write test for CalculationInput serialization round-trip**

```kotlin
package maple.expectation.core.dto.v4

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalculationInputTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    private fun sampleInput() = CalculationInput(
        schemaVersion = 1,
        jobId = "test-job-id",
        userIgn = "testUser",
        characterClass = "hero",
        presetNo = 1,
        items = listOf(
            EquipmentItem(
                part = EquipmentSlot.WEAPON,
                equipmentPart = EquipmentPart.WEAPON,
                itemName = "테스트 무기",
                level = 200,
                potential = PotentialLines(
                    grade = PotentialGrade.LEGENDARY,
                    line1 = "공격력 +12%",
                    line2 = "보스 공격 시 데미지 +40%",
                    line3 = "크리티컬 데미지 +8%"
                ),
                additionalPotential = PotentialLines(
                    grade = PotentialGrade.UNIQUE,
                    line1 = "크리티컬 확률 +12%",
                    line2 = null,
                    line3 = null
                ),
                starforce = 22,
                starforceScrollFlag = StarforceScrollFlag.USED,
                addOption = AddOption(
                    str = 10, dex = 20, int = 0, luk = 0,
                    maxHp = 0, allStat = 5,
                    attackPower = 50, magicPower = 0,
                    bossDamage = 30, damage = 0
                ),
                baseAttackPower = 300,
                baseMagicPower = 0
            )
        )
    )

    @Test
    fun `serialization round-trip preserves all fields`() {
        val original = sampleInput()
        val json = mapper.writeValueAsString(original)
        val deserialized = mapper.readValue(json, CalculationInput::class.java)
        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun `null potential is preserved`() {
        val input = sampleInput().copy(items = listOf(
            sampleInput().items[0].copy(potential = null, additionalPotential = null)
        ))
        val json = mapper.writeValueAsString(input)
        val deserialized = mapper.readValue(json, CalculationInput::class.java)
        assertThat(deserialized.items[0].potential).isNull()
        assertThat(deserialized.items[0].additionalPotential).isNull()
    }
}
```

- [ ] **Step 9: Run tests**

Run: `./gradlew :module-core:test --tests "maple.expectation.core.dto.v4.*" 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 10: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/dto/v4/ module-core/src/test/kotlin/maple/expectation/core/dto/v4/
git commit -m "feat(core): add CalculationInput typed contract model with tests"
```

---

## Task 3: Port Interfaces for New Tables

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationInputPort.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt`

- [ ] **Step 1: Write CalculationInputPort**

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.dto.v4.CalculationInput
import java.util.UUID

interface CalculationInputPort {
    fun save(input: CalculationInput): CalculationInput
    fun findByJobId(jobId: UUID): CalculationInput?
}
```

- [ ] **Step 2: Write CalculationResultPort**

```kotlin
package maple.expectation.core.port.out

import java.util.UUID

data class CalculationResultData(
    val resultId: UUID,
    val jobId: UUID,
    val characterClass: String?,
    val presetNo: Int,
    val schemaVersion: Int,
    val contentType: String,
    val contentEncoding: String,
    val responseBody: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val hash: String,
    val status: String
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

interface CalculationResultPort {
    fun save(result: CalculationResultData): CalculationResultData
    fun findByJobId(jobId: UUID): CalculationResultData?
    fun existsByJobId(jobId: UUID): Boolean
}
```

- [ ] **Step 3: Write OutboxEventPort**

```kotlin
package maple.expectation.core.port.out

import java.util.UUID

data class OutboxEvent(
    val eventId: UUID,
    val eventType: String,
    val jobId: UUID,
    val payload: String?,
    val published: Boolean,
    val publishAttempts: Int
)

interface OutboxEventPort {
    fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean
    fun findUnpublished(limit: Int): List<OutboxEvent>
    fun markPublished(eventId: UUID)
    fun incrementPublishAttempts(eventId: UUID)
}
```

- [ ] **Step 4: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationInputPort.kt module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationResultPort.kt module-core/src/main/kotlin/maple/expectation/core/port/out/OutboxEventPort.kt
git commit -m "feat(core): add port interfaces for CalculationInput, CalculationResult, OutboxEvent"
```

---

## Task 4: JPA Entities and Repositories

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotInputEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationResultEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/OutboxEventEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotInputRepository.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationResultRepository.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/OutboxEventRepository.kt`

- [ ] **Step 1: Write CalculationSnapshotInputEntity**

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "calculation_snapshot_inputs")
class CalculationSnapshotInputEntity(
    @Id val inputId: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val jobId: UUID,
    @Column(nullable = false) val schemaVersion: Int = 1,
    @Column(nullable = false, columnDefinition = "jsonb") val payload: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now()
)
```

- [ ] **Step 2: Write CalculationResultEntity**

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "calculation_results")
class CalculationResultEntity(
    @Id val resultId: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) val jobId: UUID,
    val characterClass: String? = null,
    @Column(nullable = false) val presetNo: Int = 1,
    @Column(nullable = false) val schemaVersion: Int = 1,
    @Column(nullable = false) val contentType: String = "application/json",
    @Column(nullable = false) val contentEncoding: String = "gzip",
    @Column(columnDefinition = "bytea") val responseBody: ByteArray = ByteArray(0),
    val originalSize: Int = 0,
    val compressedSize: Int = 0,
    val hash: String? = null,
    @Column(nullable = false) val status: String = "SUCCESS",
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val expiresAt: OffsetDateTime? = null
)
```

- [ ] **Step 3: Write OutboxEventEntity**

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "outbox_events")
class OutboxEventEntity(
    @Id val eventId: UUID = UUID.randomUUID(),
    @Column(nullable = false) val eventType: String,
    @Column(nullable = false) val jobId: UUID,
    @Column(columnDefinition = "jsonb") val payload: String? = null,
    @Column(nullable = false) val published: Boolean = false,
    @Column(nullable = false) val publishAttempts: Int = 0,
    @Column(nullable = false) val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val publishedAt: OffsetDateTime? = null
)
```

- [ ] **Step 4: Write repositories**

CalculationSnapshotInputRepository:
```kotlin
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalculationSnapshotInputRepository : JpaRepository<CalculationSnapshotInputEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationSnapshotInputEntity?
}
```

CalculationResultRepository:
```kotlin
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CalculationResultRepository : JpaRepository<CalculationResultEntity, UUID> {
    fun findByJobId(jobId: UUID): CalculationResultEntity?
    fun existsByJobId(jobId: UUID): Boolean
}
```

OutboxEventRepository:
```kotlin
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.OutboxEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEventEntity, UUID> {

    @Query("SELECT e FROM OutboxEventEntity e WHERE e.published = false ORDER BY e.createdAt")
    fun findUnpublished(limit: Int): List<OutboxEventEntity>

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.published = true, e.publishedAt = :now WHERE e.eventId = :eventId")
    fun markPublished(@Param("eventId") eventId: UUID, @Param("now") now: OffsetDateTime = OffsetDateTime.now())

    @Modifying
    @Query("UPDATE OutboxEventEntity e SET e.publishAttempts = e.publishAttempts + 1 WHERE e.eventId = :eventId")
    fun incrementPublishAttempts(@Param("eventId") eventId: UUID)

    @Query("SELECT COUNT(e) > 0 FROM OutboxEventEntity e WHERE e.jobId = :jobId AND e.eventType = :eventType")
    fun existsByJobIdAndEventType(@Param("jobId") jobId: UUID, @Param("eventType") eventType: String): Boolean
}
```

- [ ] **Step 5: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/ module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/
git commit -m "feat(infra): add JPA entities and repositories for Write Path tables"
```

---

## Task 5: Port Adapters

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationInputPortAdapter.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapterTest.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapterTest.kt`

- [ ] **Step 1: Write OutboxEventPortAdapter test**

```kotlin
package maple.expectation.adapter.outgoing

import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat

@ExtendWith(MockitoExtension::class)
class OutboxEventPortAdapterTest {

    @Mock lateinit var repo: OutboxEventRepository
    @InjectMocks lateinit var adapter: OutboxEventPortAdapter

    @Test
    fun `insertIfAbsent returns false when event already exists`() {
        val jobId = UUID.randomUUID()
        whenever(repo.existsByJobIdAndEventType(jobId, "CALCULATION_COMPLETED")).thenReturn(true)

        val result = adapter.insertIfAbsent("CALCULATION_COMPLETED", jobId, "{}")

        assertThat(result).isFalse()
    }
}
```

- [ ] **Step 2: Write OutboxEventPortAdapter**

```kotlin
package maple.expectation.adapter.outgoing

import maple.expectation.core.port.out.OutboxEvent
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.infrastructure.persistence.entity.OutboxEventEntity
import maple.expectation.infrastructure.persistence.repository.OutboxEventRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
class OutboxEventPortAdapter(
    private val repo: OutboxEventRepository
) : OutboxEventPort {

    override fun insertIfAbsent(eventType: String, jobId: UUID, payload: String?): Boolean {
        if (repo.existsByJobIdAndEventType(jobId, eventType)) {
            return false
        }
        repo.save(OutboxEventEntity(eventType = eventType, jobId = jobId, payload = payload))
        return true
    }

    override fun findUnpublished(limit: Int): List<OutboxEvent> {
        return repo.findUnpublished(limit).map {
            OutboxEvent(it.eventId, it.eventType, it.jobId, it.payload, it.published, it.publishAttempts)
        }
    }

    override fun markPublished(eventId: UUID) {
        repo.markPublished(eventId)
    }

    override fun incrementPublishAttempts(eventId: UUID) {
        repo.incrementPublishAttempts(eventId)
    }
}
```

- [ ] **Step 3: Write CalculationResultPortAdapter**

```kotlin
package maple.expectation.adapter.outgoing

import maple.expectation.core.port.out.CalculationResultData
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.infrastructure.persistence.entity.CalculationResultEntity
import maple.expectation.infrastructure.persistence.repository.CalculationResultRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CalculationResultPortAdapter(
    private val repo: CalculationResultRepository
) : CalculationResultPort {

    override fun save(result: CalculationResultData): CalculationResultData {
        val existing = repo.findByJobId(result.jobId)
        val entity = if (existing != null && existing.hash == result.hash) {
            existing
        } else {
            repo.save(CalculationResultEntity(
                resultId = result.resultId,
                jobId = result.jobId,
                characterClass = result.characterClass,
                presetNo = result.presetNo,
                schemaVersion = result.schemaVersion,
                contentType = result.contentType,
                contentEncoding = result.contentEncoding,
                responseBody = result.responseBody,
                originalSize = result.originalSize,
                compressedSize = result.compressedSize,
                hash = result.hash,
                status = result.status
            ))
        }
        return CalculationResultData(
            resultId = entity.resultId,
            jobId = entity.jobId,
            characterClass = entity.characterClass,
            presetNo = entity.presetNo,
            schemaVersion = entity.schemaVersion,
            contentType = entity.contentType,
            contentEncoding = entity.contentEncoding,
            responseBody = entity.responseBody,
            originalSize = entity.originalSize,
            compressedSize = entity.compressedSize,
            hash = entity.hash ?: "",
            status = entity.status
        )
    }

    override fun findByJobId(jobId: UUID): CalculationResultData? {
        return repo.findByJobId(jobId)?.toData()
    }

    override fun existsByJobId(jobId: UUID): Boolean = repo.existsByJobId(jobId)

    private fun CalculationResultEntity.toData() = CalculationResultData(
        resultId = resultId, jobId = jobId, characterClass = characterClass,
        presetNo = presetNo, schemaVersion = schemaVersion,
        contentType = contentType, contentEncoding = contentEncoding,
        responseBody = responseBody, originalSize = originalSize,
        compressedSize = compressedSize, hash = hash ?: "", status = status
    )
}
```

- [ ] **Step 4: Write CalculationInputPortAdapter**

```kotlin
package maple.expectation.adapter.outgoing

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotInputEntity
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotInputRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CalculationInputPortAdapter(
    private val repo: CalculationSnapshotInputRepository,
    private val objectMapper: ObjectMapper
) : CalculationInputPort {

    override fun save(input: CalculationInput): CalculationInput {
        val payload = objectMapper.writeValueAsString(input)
        val entity = repo.save(CalculationSnapshotInputEntity(
            jobId = UUID.fromString(input.jobId),
            schemaVersion = input.schemaVersion,
            payload = payload
        ))
        return input
    }

    override fun findByJobId(jobId: UUID): CalculationInput? {
        val entity = repo.findByJobId(jobId) ?: return null
        return objectMapper.readValue(entity.payload, CalculationInput::class.java)
    }
}
```

- [ ] **Step 5: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run tests**

Run: `./gradlew :module-infra:test --tests "maple.expectation.adapter.outgoing.*" 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 7: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationInputPortAdapter.kt module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationResultPortAdapter.kt module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/OutboxEventPortAdapter.kt module-infra/src/test/kotlin/maple/expectation/adapter/outgoing/
git commit -m "feat(infra): add port adapters for CalculationInput, CalculationResult, OutboxEvent"
```

---

## Task 6: Result Ready Topic + Event Factory

**Files:**
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/ResultReadyTopic.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/ResultReadyEventFactory.kt`

- [ ] **Step 1: Add RESULT_READY to QueueNames**

Add constant to existing `QueueNames` object:

```kotlin
const val RESULT_READY = "result_ready_queue"
```

- [ ] **Step 2: Write ResultReadyTopic**

Follow existing pattern (see `NexonApiResponseTopic.kt`):

```kotlin
package maple.expectation.infrastructure.mq.pgmq.topic

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.lifecycle.ScheduledTaskLifecycleWrapper
import maple.expectation.infrastructure.mq.pgmq.PgmqClient
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicConfig
import maple.expectation.infrastructure.mq.pgmq.PgmqTopicGroup
import maple.expectation.infrastructure.mq.pgmq.WorkerQueueMetrics
import org.springframework.stereotype.Component

@Component
class ResultReadyTopic(
    pgmqClient: PgmqClient,
    objectMapper: ObjectMapper,
    executor: LogicExecutor,
    lifecycleWrapper: ScheduledTaskLifecycleWrapper,
    queueMetrics: WorkerQueueMetrics
) : PgmqTopicGroup(
    pgmqClient, objectMapper, executor, lifecycleWrapper,
    PgmqTopicConfig(batchSize = 10, visibilityTimeoutSec = 30),
    queueMetrics
) {
    override val name: String = QueueNames.RESULT_READY
}
```

- [ ] **Step 3: Write ResultReadyEventFactory**

Follow existing pattern (see `NexonApiResponseEventFactory.kt`):

```kotlin
package maple.expectation.infrastructure.mq.event

import maple.expectation.core.domain.event.IntegrationEvent

object ResultReadyEventFactory {
    fun create(
        jobId: String,
        resultId: String,
        characterId: String,
        presetNo: Int,
        contentEncoding: String = "gzip",
        schemaVersion: Int = 1
    ): IntegrationEvent<Map<String, Any>> {
        return IntegrationEvent.of(
            "CALCULATION_COMPLETED",
            mapOf(
                "jobId" to jobId,
                "resultId" to resultId,
                "characterId" to characterId,
                "presetNo" to presetNo,
                "contentEncoding" to contentEncoding,
                "schemaVersion" to schemaVersion
            )
        ).copy(schemaVersion = 1, jobId = jobId)
    }
}
```

- [ ] **Step 4: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/pgmq/topic/ResultReadyTopic.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/mq/event/ResultReadyEventFactory.kt
git commit -m "feat(mq): add ResultReadyTopic and CALCULATION_COMPLETED event factory"
```

---

## Task 7: EquipmentResponse → CalculationInput Converter

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverter.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/converter/EquipmentResponseToCalculationInputConverterTest.kt`

This is the conversion logic that runs in External API Path.

- [ ] **Step 1: Write converter test**

Read the existing `EquipmentStreamingParser.java` fields to construct test fixtures. The converter must map Nexon API `ItemEquipment` fields to typed `EquipmentItem` values.

```kotlin
package maple.expectation.infrastructure.converter

import maple.expectation.core.dto.v4.*
import maple.expectation.core.domain.model.PotentialGrade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquipmentResponseToCalculationInputConverterTest {

    private val converter = EquipmentResponseToCalculationInputConverter()

    @Test
    fun `converts weapon item with all fields`() {
        val nexonItem = mapOf(
            "item_equipment_slot" to "무기",
            "item_equipment_part" to "무기",
            "item_name" to "아케인셰이드 소드",
            "item_base_option" to mapOf(
                "base_equipment_level" to 200,
                "attack_power" to 293,
                "magic_power" to 0
            ),
            "potential_option_grade" to "레전드리",
            "potential_option_1" to "공격력 +12%",
            "potential_option_2" to "보스 공격 시 데미지 +40%",
            "potential_option_3" to "크리티컬 데미지 +8%",
            "additional_potential_option_grade" to "유니크",
            "additional_potential_option_1" to "크리티컬 확률 +12%",
            "additional_potential_option_2" to null,
            "additional_potential_option_3" to null,
            "starforce" to 22,
            "starforce_scroll_flag" to "사용",
            "item_add_option" to mapOf(
                "str" to 10, "dex" to 20, "int" to 0, "luk" to 0,
                "max_hp" to 0, "all_stat" to 5,
                "attack_power" to 50, "magic_power" to 0,
                "boss_damage" to 30, "damage" to 0
            )
        )

        val item = converter.convertItem(nexonItem)

        assertThat(item.part).isEqualTo(EquipmentSlot.WEAPON)
        assertThat(item.equipmentPart).isEqualTo(EquipmentPart.WEAPON)
        assertThat(item.itemName).isEqualTo("아케인셰이드 소드")
        assertThat(item.level).isEqualTo(200)
        assertThat(item.potential).isNotNull
        assertThat(item.potential!!.grade).isEqualTo(PotentialGrade.LEGENDARY)
        assertThat(item.potential!!.line1).isEqualTo("공격력 +12%")
        assertThat(item.starforce).isEqualTo(22)
        assertThat(item.starforceScrollFlag).isEqualTo(StarforceScrollFlag.USED)
        assertThat(item.baseAttackPower).isEqualTo(293)
        assertThat(item.addOption.attackPower).isEqualTo(50)
    }

    @Test
    fun `null grade produces null potential`() {
        val nexonItem = mapOf(
            "item_equipment_slot" to "모자",
            "item_equipment_part" to "방어구",
            "item_name" to "테스트 모자",
            "item_base_option" to mapOf("base_equipment_level" to 150, "attack_power" to 0, "magic_power" to 0),
            "potential_option_grade" to null,
            "potential_option_1" to null,
            "potential_option_2" to null,
            "potential_option_3" to null,
            "additional_potential_option_grade" to null,
            "additional_potential_option_1" to null,
            "additional_potential_option_2" to null,
            "additional_potential_option_3" to null,
            "starforce" to 0,
            "starforce_scroll_flag" to null,
            "item_add_option" to mapOf(
                "str" to 0, "dex" to 0, "int" to 0, "luk" to 0,
                "max_hp" to 0, "all_stat" to 0,
                "attack_power" to 0, "magic_power" to 0,
                "boss_damage" to 0, "damage" to 0
            )
        )

        val item = converter.convertItem(nexonItem)
        assertThat(item.potential).isNull()
        assertThat(item.additionalPotential).isNull()
    }
}
```

- [ ] **Step 2: Write converter implementation**

```kotlin
package maple.expectation.infrastructure.converter

import maple.expectation.core.dto.v4.*
import maple.expectation.core.domain.model.PotentialGrade
import org.springframework.stereotype.Component

@Component
class EquipmentResponseToCalculationInputConverter {

    fun convertItem(item: Map<*, *>): EquipmentItem {
        val baseOption = item["item_base_option"] as? Map<*, *>
        val addOption = item["item_add_option"] as? Map<*, *>

        return EquipmentItem(
            part = EquipmentSlot.fromKorean(item["item_equipment_slot"] as? String ?: ""),
            equipmentPart = EquipmentPart.fromKorean(item["item_equipment_part"] as? String ?: ""),
            itemName = item["item_name"] as? String ?: "",
            level = (baseOption?.get("base_equipment_level") as? Number)?.toInt() ?: 0,
            potential = buildPotentialLines(item, "potential_option_grade", "potential_option_"),
            additionalPotential = buildPotentialLines(item, "additional_potential_option_grade", "additional_potential_option_"),
            starforce = (item["starforce"] as? Number)?.toInt() ?: 0,
            starforceScrollFlag = StarforceScrollFlag.fromKorean(item["starforce_scroll_flag"] as? String),
            addOption = AddOption(
                str = (addOption?.get("str") as? Number)?.toInt() ?: 0,
                dex = (addOption?.get("dex") as? Number)?.toInt() ?: 0,
                int = (addOption?.get("int") as? Number)?.toInt() ?: 0,
                luk = (addOption?.get("luk") as? Number)?.toInt() ?: 0,
                maxHp = (addOption?.get("max_hp") as? Number)?.toInt() ?: 0,
                allStat = (addOption?.get("all_stat") as? Number)?.toInt() ?: 0,
                attackPower = (addOption?.get("attack_power") as? Number)?.toInt() ?: 0,
                magicPower = (addOption?.get("magic_power") as? Number)?.toInt() ?: 0,
                bossDamage = (addOption?.get("boss_damage") as? Number)?.toInt() ?: 0,
                damage = (addOption?.get("damage") as? Number)?.toInt() ?: 0
            ),
            baseAttackPower = (baseOption?.get("attack_power") as? Number)?.toInt() ?: 0,
            baseMagicPower = (baseOption?.get("magic_power") as? Number)?.toInt() ?: 0
        )
    }

    private fun buildPotentialLines(item: Map<*, *>, gradeKey: String, optionPrefix: String): PotentialLines? {
        val gradeStr = item[gradeKey] as? String ?: return null
        val grade = PotentialGrade.fromKorean(gradeStr) ?: return null
        return PotentialLines(
            grade = grade,
            line1 = item["${optionPrefix}1"] as? String,
            line2 = item["${optionPrefix}2"] as? String,
            line3 = item["${optionPrefix}3"] as? String
        )
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.converter.*" 2>&1 | tail -10`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/converter/ module-infra/src/test/kotlin/maple/expectation/infrastructure/converter/
git commit -m "feat(infra): add EquipmentResponse to CalculationInput converter"
```

---

## Task 8: CalculationJobService — completeCalculation with Result + Outbox

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt`

- [ ] **Step 1: Write test for completeCalculation with result save + outbox**

```kotlin
package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CalculationJobServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var resultPort: CalculationResultPort
    @Mock lateinit var outboxPort: OutboxEventPort
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository
    @Mock lateinit var ocidResolveTopic: OcidResolveTopic
    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic
    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    private val objectMapper = ObjectMapper()

    private lateinit var service: CalculationJobService

    @BeforeEach
    fun setUp() {
        service = CalculationJobService(
            jobPort = jobPort,
            eventAppender = eventAppender,
            ocidResolveTopic = ocidResolveTopic,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
            snapshotRepository = snapshotRepository,
            resultPort = resultPort,
            outboxPort = outboxPort,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `completeCalculationWithResult saves result and creates outbox event`() {
        val jobId = UUID.randomUUID()
        val resultJson = """{"totalExpectedCost":1000000}"""

        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED))
            .thenReturn(true)
        whenever(jobPort.unlock(jobId)).thenReturn(true)
        whenever(outboxPort.insertIfAbsent(eq("CALCULATION_COMPLETED"), eq(jobId), any()))
            .thenReturn(true)

        val result = service.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = "hero",
            presetNo = 1,
            characterId = "test-char"
        )

        assertThat(result).isTrue

        verify(resultPort).save(argThat { r ->
            r.jobId == jobId && r.contentEncoding == "gzip"
        })
        verify(outboxPort).insertIfAbsent(eq("CALCULATION_COMPLETED"), eq(jobId), any())
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }
}
```

Note: The test assumes `CalculationJobService` is refactored to accept `resultPort` and `outboxPort`. The existing constructor needs to be updated.

- [ ] **Step 2: Modify CalculationJobService to add result save + outbox insert**

Add `CalculationResultPort`, `OutboxEventPort` to constructor (keep all existing deps). Add new method `completeCalculationWithResult()`. The constructor becomes:

```kotlin
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val eventAppender: DomainEventAppender,
    private val ocidResolveTopic: OcidResolveTopic,
    private val nexonApiRequestTopic: NexonApiRequestTopic,
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val resultPort: CalculationResultPort,
    private val outboxPort: OutboxEventPort
) {
```

New method `completeCalculationWithResult()`:

```kotlin
@Transactional
fun completeCalculationWithResult(
    jobId: UUID,
    resultJson: String,
    characterClass: String,
    presetNo: Int,
    characterId: String
): Boolean {
    val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
    if (!completed) return false

    val gzipData = gzipCompress(resultJson.toByteArray())
    val hash = sha256Hex(gzipData)

    resultPort.save(CalculationResultData(
        resultId = UUID.randomUUID(),
        jobId = jobId,
        characterClass = characterClass,
        presetNo = presetNo,
        schemaVersion = 1,
        contentType = "application/json",
        contentEncoding = "gzip",
        responseBody = gzipData,
        originalSize = resultJson.toByteArray().size,
        compressedSize = gzipData.size,
        hash = hash,
        status = "SUCCESS"
    ))

    val eventPayload = objectMapper.writeValueAsString(mapOf(
        "jobId" to jobId.toString(),
        "characterId" to characterId,
        "presetNo" to presetNo,
        "contentEncoding" to "gzip",
        "schemaVersion" to 1
    ))
    outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, eventPayload)

    jobPort.unlock(jobId)
    log.info("[jobId={}] Calculation completed with result saved", jobId)
    return true
}

private fun gzipCompress(data: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
    return bos.toByteArray()
}

private fun sha256Hex(data: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run test**

Run: `./gradlew :module-infra:test --tests "maple.expectation.infrastructure.job.CalculationJobServiceTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt module-infra/src/test/kotlin/maple/expectation/infrastructure/job/CalculationJobServiceTest.kt
git commit -m "feat(write-path): add completeCalculationWithResult with gzip + outbox"
```

---

## Task 9: ApiResponseWorker — Switch to CalculationInput

**Files:**
- Modify: `module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt`

This is the critical change. ApiResponseWorker stops reading `EquipmentResponse` from snapshot and instead reads `CalculationInput` from DB.

- [ ] **Step 1: Modify ApiResponseWorker**

Replace `populateEquipmentCacheFromSnapshot()` with `CalculationInput` consumption:

```kotlin
package maple.expectation.application.worker

import maple.expectation.core.dto.v4.CalculationInput
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.core.port.out.CalculationInputPort
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.mq.ConsumeResult
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.job.CalculationJobService
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ApiResponseWorker(
    private val nexonApiResponseTopic: NexonApiResponseTopic,
    private val expectationPort: ExpectationV4Port,
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService,
    private val calculationInputPort: CalculationInputPort,
    private val executor: LogicExecutor
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val terminalStatuses = setOf(
        CalculationJobStatus.COMPLETED,
        CalculationJobStatus.FAILED
    )

    init {
        nexonApiResponseTopic.subscribe { envelope, _ -> handleApiResponse(envelope) }
    }

    private fun handleApiResponse(envelope: maple.expectation.core.domain.event.IntegrationEvent<*>): ConsumeResult {
        val payload = envelope.payload as Map<*, *>
        val jobId = UUID.fromString(payload["jobId"].toString())
        val userIgn = payload["userIgn"].toString()
        val context = TaskContext.of("ApiResponseWorker", "Process", userIgn)
        return executor.executeOrDefault({
            processApiResponse(payload, jobId, userIgn)
        }, ConsumeResult.Ack, context)
    }

    private fun processApiResponse(payload: Map<*, *>, jobId: UUID, userIgn: String): ConsumeResult {
        val job = jobPort.findJobById(jobId)
        if (job == null) {
            log.warn("[jobId={}] Job not found, archiving", jobId)
            return ConsumeResult.Ack
        }

        if (job.status in terminalStatuses) {
            log.info("[jobId={}] Already in terminal state: {}, skipping", jobId, job.status)
            return ConsumeResult.Ack
        }

        if (job.status == CalculationJobStatus.CALCULATING) {
            log.warn("[jobId={}] Stuck in CALCULATING on redelivery, marking as failed", jobId)
            jobPort.markFailed(jobId, "CALCULATION_STUCK", "Calculation stuck after redelivery")
            return ConsumeResult.Ack
        }

        val started = jobService.startCalculation(jobId, "ApiResponseWorker")
        if (!started) {
            log.warn("[jobId={}] Could not start calculation, archiving", jobId)
            return ConsumeResult.Ack
        }

        val presetNo = (payload["presetNo"] as Number).toInt()
        val characterId = payload["characterId"]?.toString() ?: ""

        val input = calculationInputPort.findByJobId(jobId)
        if (input == null) {
            log.error("[jobId={}] CalculationInput not found, cannot proceed", jobId)
            jobPort.markFailed(jobId, "INPUT_NOT_FOUND", "CalculationInput not found for job")
            return ConsumeResult.Ack
        }

        val result = expectationPort.calculateExpectationAsync(
            userIgn, false, jobId.toString(), presetNo
        ).join()

        val resultJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)

        jobService.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = input.characterClass,
            presetNo = presetNo,
            characterId = characterId
        )

        log.info("[jobId={}] Calculation completed from CalculationInput", jobId)
        return ConsumeResult.Ack
    }
}
```

Key changes:
- Removed `SnapshotObjectStore`, `ObjectMapper`, `CacheManager` dependencies
- Removed `populateEquipmentCacheFromSnapshot()` method
- Added `CalculationInputPort` dependency
- Reads CalculationInput from DB, not EquipmentResponse from snapshot
- Calls `completeCalculationWithResult()` instead of `completeCalculation()`

- [ ] **Step 2: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-app/src/main/kotlin/maple/expectation/application/worker/ApiResponseWorker.kt
git commit -m "feat(write-path): ApiResponseWorker consumes CalculationInput instead of EquipmentResponse"
```

---

## Task 10: NexonApiWorker — Add CalculationInput Conversion

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt`

External API Path now converts EquipmentResponse → CalculationInput and saves to DB.

- [ ] **Step 1: Add CalculationInput conversion to NexonApiWorker**

Add `EquipmentResponseToCalculationInputConverter` and `CalculationInputPort` as dependencies. After saving snapshot, also convert and save CalculationInput:

```kotlin
// Add to constructor:
private val converter: EquipmentResponseToCalculationInputConverter,
private val calculationInputPort: CalculationInputPort

// After snapshotStore.put() and before markSnapshotReady:
val inputItems = response.itemEquipment.map { item ->
    converter.convertItem(objectMapper.convertValue(item, Map::class.java))
}
val calcInput = CalculationInput(
    jobId = jobId.toString(),
    userIgn = userIgn,
    characterClass = character.characterClass ?: "",
    presetNo = presetNo,
    items = inputItems
)
calculationInputPort.save(calcInput)
```

- [ ] **Step 2: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt
git commit -m "feat(external-api): NexonApiWorker converts and saves CalculationInput"
```

---

## Task 11: Outbox Relay Worker

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OutboxRelayWorker.kt`

- [ ] **Step 1: Write OutboxRelayWorker**

```kotlin
package maple.expectation.infrastructure.worker

import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.ResultReadyTopic
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxRelayWorker(
    private val outboxPort: OutboxEventPort,
    private val resultReadyTopic: ResultReadyTopic,
    private val eventAppender: DomainEventAppender
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    fun relay() {
        val events = outboxPort.findUnpublished(50)
        if (events.isEmpty()) return

        if (events.size >= 10) {
            log.info("Relaying {} outbox events", events.size)
        }

        for (event in events) {
            try {
                resultReadyTopic.publish(event.jobId.toString(), event.payload ?: "{}")
                outboxPort.markPublished(event.eventId)
            } catch (e: Exception) {
                log.warn("[eventId={}] Publish failed: {}", event.eventId, e.message)
                outboxPort.incrementPublishAttempts(event.eventId)
            }
        }
    }
}
```

- [ ] **Step 2: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/OutboxRelayWorker.kt
git commit -m "feat(write-path): add OutboxRelayWorker for event publishing"
```

---

## Task 12: Compensating Scanner

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt`

- [ ] **Step 1: Write OutboxCompensatingScanner**

```kotlin
package maple.expectation.infrastructure.job

import maple.expectation.core.port.out.OutboxEventPort
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OutboxCompensatingScanner(
    private val jdbc: NamedParameterJdbcTemplate,
    private val outboxPort: OutboxEventPort
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    fun scan() {
        val sql = """
            SELECT j.job_id
            FROM calculation_jobs j
            WHERE j.status = 'COMPLETED'
              AND j.completed_at < now() - INTERVAL '1 minute'
              AND NOT EXISTS (
                SELECT 1 FROM outbox_events o
                WHERE o.job_id = j.job_id AND o.event_type = 'CALCULATION_COMPLETED'
              )
            LIMIT 50
        """.trimIndent()

        val orphaned = jdbc.queryForList(sql, emptyMap<String, Any>(), java.util.UUID::class.java)
        if (orphaned.isEmpty()) return

        log.warn("Found {} orphaned completed jobs without outbox events", orphaned.size)
        for (jobId in orphaned) {
            val payload = """{"jobId":"$jobId","orphanRecovery":true}"""
            outboxPort.insertIfAbsent("CALCULATION_COMPLETED", jobId, payload)
            log.info("[jobId={}] Compensating: created outbox event", jobId)
        }
    }
}
```

- [ ] **Step 2: Run compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/OutboxCompensatingScanner.kt
git commit -m "feat(write-path): add Compensating Scanner for orphaned events"
```

---

## Task 13: Full Compile + Test Verification

- [ ] **Step 1: Full compile**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `./gradlew test 2>&1 | grep -E "BUILD|FAIL|ERROR" | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Fix any failures**

If tests fail, read the error output, fix the issue, re-run.

- [ ] **Step 4: Final commit with all fixes**

```bash
git add -A
git commit -m "fix: address test failures from Write Path integration"
```

---

## Task 14: Update ADR Status

**Files:**
- Modify: `docs/01_ADR/ADR-write-path-snapshot-calculator.md`

- [ ] **Step 1: Update status from Proposed to Approved**

Change line 3:
```
**Status**: Approved
```

- [ ] **Step 2: Commit**

```bash
git add docs/01_ADR/ADR-write-path-snapshot-calculator.md
git commit -m "docs: update Write Path ADR status to Approved"
```

---

## Task 15: Create PR

- [ ] **Step 1: Push branch**

```bash
git push -u origin feat/write-path-snapshot-calculator
```

- [ ] **Step 2: Create PR**

```bash
gh pr create --base develop --title "feat: Write Path Snapshot Calculator" --body "$(cat <<'EOF'
## Summary
- Write Path가 External API DTO(EquipmentResponse)를 완전히 차단
- Typed CalculationInput 계약 모델 도입 (pure function 목표)
- Outbox 패턴으로 result_ready 이벤트 발행 보장
- calculation_results 테이블에 gzip 압축 결과 저장
- Compensating Scanner로 유실 이벤트 복구

## Design Spec
`docs/superpowers/specs/2026-04-28-write-path-snapshot-calculator-design.md`

## Test plan
- [ ] CalculationInput 직렬화/역직렬화 round-trip
- [ ] PotentialLines nullable 규칙 검증
- [ ] EquipmentResponse → CalculationInput 변환 테스트
- [ ] CalculationJobService.completeCalculationWithResult 단위 테스트
- [ ] OutboxEventPortAdapter idempotency 테스트
- [ ] 전체 컴파일 + 단위 테스트 통과

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 Scope (Not in this plan)

The following items from the design spec are deferred to a follow-up plan:

| Item | Spec Section | Reason |
|------|-------------|--------|
| Write Path retry with exponential backoff + jitter | Gap 2 | Requires `next_retry_at` column and scheduling infrastructure |
| DLQ for max-retry-exceeded / irrecoverable errors | Gap 2 | Requires separate table and monitoring/alerting |
| `CalculationEngine` interface with `supports(version)` | Gap 4 | Step 4 of gradual migration; current calculation still works via existing path |
| `characterClass` as typed enum (not String) | Gap 1 | No existing `CharacterClass` enum; String is pragmatic for Phase 1 |
| Legacy worker deactivation (ExpectationCalcWorker/Low) | Phase 4 | Feature-flag gated; run in parallel first |
| `batchL2CachePut`/`batchViewUpsert` migration to Read Path | Phase 4 | Read Path must be ready to consume first |
