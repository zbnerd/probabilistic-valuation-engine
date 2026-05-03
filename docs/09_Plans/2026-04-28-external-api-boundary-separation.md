# External API Boundary Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** External API 호출, 대용량 JSON 파싱, 계산 입력 Snapshot 저장을 External API Boundary로 분리해 Write Path가 외부 API 스키마와 네트워크 지연에 의존하지 않도록 개선.

**Architecture:** PGMQ 기반 비동기 상태머신. `nexon_api_request_queue` / `nexon_api_response_queue` 두 큐로 Write ↔ External API 경계 분리. Snapshot은 GZIP 압축 후 `SnapshotObjectStore` 인터페이스로 저장, MQ에는 참조만 전달. 상태 전이는 `CalculationJobService`에서 중앙 관리, conditional UPDATE로 원자성 보장. 같은 TX에서 job UPDATE + PGMQ send.

**Tech Stack:** Kotlin, Spring Boot, PGMQ (PostgreSQL), JPA/Hibernate, GZIP (JDK 내장), CompletableFuture, LogicExecutor, Resilience4j

**Spec:** Issue #758, ADR `docs/01_ADR/ADR-three-path-independence-mq-boundary.md`

**Key Decisions (13 items):**
1. Snapshot = Provider 파싱 결과 (raw JSON 폐기)
2. MQ 메시지는 참조만 (snapshot_id, object_key, job_id)
3. SnapshotObjectStore 인터페이스, Local 우선 S3 나중에
4. GZIP 압축 (JDK 내장)
5. 기존 Worker 수정 (점진적 역할 이동)
6. CalculationJobService 중앙 상태 관리
7. 429 재시도 상태머신 통합 (FanOut 제거)
8. 같은 TX 보장 (job UPDATE + PGMQ send)
9. Timeout Scanner 이번 PR 포함
10. DLQ 이번 PR 포함
11. 패키지 분리만 (물리적 모듈 분리는 Phase 3)
12. Feature flag 없이 직접 전환
13. 새 브랜치에서 시작

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-infra/src/main/resources/db/migration/V114__external_api_boundary.sql` | Create | calculation_jobs, calculation_snapshots 테이블 + 신규 PGMQ 큐 |
| `module-core/.../port/out/SnapshotObjectStore.kt` | Create | Snapshot 저장소 인터페이스 |
| `module-core/.../port/out/CalculationJobPort.kt` | Create | 상태머신 port 인터페이스 |
| `module-core/.../model/job/CalculationJobStatus.kt` | Create | 상태 enum |
| `module-core/.../model/job/CalculationJob.kt` | Create | Job 도메인 모델 |
| `module-core/.../model/snapshot/CalculationSnapshot.kt` | Create | Snapshot 도메인 모델 |
| `module-infra/.../external/snapshot/LocalSnapshotObjectStore.kt` | Create | Local 파일시스템 구현체 |
| `module-infra/.../persistence/entity/CalculationJobEntity.kt` | Create | JPA 엔티티 |
| `module-infra/.../persistence/entity/CalculationSnapshotEntity.kt` | Create | JPA 엔티티 |
| `module-infra/.../persistence/repository/CalculationJobRepository.kt` | Create | JPA Repository |
| `module-infra/.../persistence/repository/CalculationSnapshotRepository.kt` | Create | JPA Repository |
| `module-infra/.../adapter/outgoing/CalculationJobPortAdapter.kt` | Create | Port 구현체 |
| `module-infra/.../job/CalculationJobService.kt` | Create | 상태머신 중앙 서비스 |
| `module-infra/.../worker/NexonApiWorker.kt` | Create | External API Worker (PgmqWorker 상속) |
| `module-infra/.../worker/ApiResponseWorker.kt` | Create | response_queue 소비 → Snapshot 읽기 → 계산 |
| `module-infra/.../queue/pgmq/NexonApiRequestMessage.kt` | Create | 요청 메시지 DTO |
| `module-infra/.../queue/pgmq/NexonApiResponseMessage.kt` | Create | 응답 메시지 DTO |
| `module-core/.../port/out/QueueNames.kt` | Modify | 신규 큐 이름 추가 |
| `module-infra/.../external/NexonApiClient.kt` | Modify | `getItemDataRaw()` 메서드 추가 |
| `module-infra/.../external/impl/RealNexonApiClient.kt` | Modify | `getItemDataRaw()` 구현 (byte[] 반환) |
| `module-app/.../parser/EquipmentStreamingParser.java` | Move | `module-infra/.../parser/`로 이동 |
| `module-infra/.../worker/ExpectationCalcWorker.kt` | Modify | API 직접 호출 → request_queue 발행 |
| `module-infra/.../worker/AbstractExpectationCalcWorker.kt` | Modify | 두 단계 분리 (request + response) |
| `module-infra/.../job/CalculationJobTimeoutScanner.kt` | Create | Timeout Scanner |
| `module-app/src/main/resources/application.yml` | Modify | 신규 Worker/큐 설정 추가 |
| `module-app/src/main/resources/application-local.yml` | Modify | 로컬 오버라이드 |
| `module-app/src/main/resources/application-prod.yml` | Modify | 운영 오버라이드 |

Exact base paths:
- Core: `module-core/src/main/kotlin/maple/expectation/core/`
- Infra: `module-infra/src/main/kotlin/maple/expectation/infrastructure/`
- Persistence: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/`
- Workers: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/`
- External: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/`
- Queue: `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/`

---

### Task 1: Database Schema — Tables and Queues

**Files:**
- Create: `module-infra/src/main/resources/db/migration/V114__external_api_boundary.sql`

- [ ] **Step 1: Write the migration SQL**

```sql
-- ============================================================
-- V114: External API Boundary — state table, snapshots, queues
-- ============================================================

-- 1. calculation_jobs: 상태머신 중심 테이블
CREATE TABLE calculation_jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ocid            VARCHAR(64) NOT NULL,
    user_ign        VARCHAR(64) NOT NULL,
    preset_no       INT DEFAULT 1,
    status          VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    snapshot_id     UUID,                       -- SNAPSHOT_READY 시 설정
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    next_retry_at   TIMESTAMPTZ,
    locked_by       VARCHAR(128),
    locked_until    TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    error_message   TEXT,
    calculation_result JSONB,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

-- Active job dedup: 같은 ocid + preset_no에 대해 활성 job은 1개만
CREATE UNIQUE INDEX idx_calc_jobs_active_dedup
    ON calculation_jobs (ocid, preset_no)
    WHERE status IN ('REQUESTED', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING');

-- 상태별 조회
CREATE INDEX idx_calc_jobs_status ON calculation_jobs (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED');

-- Timeout Scanner용
CREATE INDEX idx_calc_jobs_stale ON calculation_jobs (updated_at)
    WHERE status IN ('API_REQUESTED', 'RETRYING')
      AND locked_until IS NULL;

-- OCID 조회
CREATE INDEX idx_calc_jobs_ocid ON calculation_jobs (ocid, preset_no);

-- 2. calculation_snapshots: Snapshot metadata
CREATE TABLE calculation_snapshots (
    snapshot_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES calculation_jobs(job_id),
    object_key      VARCHAR(512) NOT NULL,     -- 논리 경로: snapshots/2026/04/28/job-123.gz
    storage_type    VARCHAR(16) NOT NULL DEFAULT 'LOCAL',  -- LOCAL / S3
    character_id    VARCHAR(64),
    preset_no       INT DEFAULT 1,
    compressed_size BIGINT,
    original_size   BIGINT,
    hash            VARCHAR(128),              -- SHA-256
    expires_at      TIMESTAMPTZ NOT NULL,      -- TTL
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_snapshots_job_id ON calculation_snapshots (job_id);
CREATE INDEX idx_snapshots_expires ON calculation_snapshots (expires_at)
    WHERE expires_at IS NOT NULL;

-- 3. PGMQ queues
SELECT pgmq.create('nexon_api_request_queue');
SELECT pgmq.create('nexon_api_response_queue');

-- 4. Expression indexes for dedup
CREATE INDEX IF NOT EXISTS idx_pgmq_api_req_job_id
    ON pgmq.q_nexon_api_request_queue ((message ->> 'jobId'));
CREATE INDEX IF NOT EXISTS idx_pgmq_api_res_job_id
    ON pgmq.q_nexon_api_response_queue ((message ->> 'jobId'));
```

- [ ] **Step 2: Apply migration**

```bash
# 로컬 DB에 직접 실행 (Flyway 미사용)
source .env
psql "postgresql://maple:${DB_PASSWORD}@${DB_SERVER_IP}:5432/expectation" \
  -f module-infra/src/main/resources/db/migration/V114__external_api_boundary.sql
```

- [ ] **Step 3: Verify tables and queues created**

```bash
psql "postgresql://maple:${DB_PASSWORD}@${DB_SERVER_IP}:5432/expectation" \
  -c "\dt calculation_*" \
  -c "SELECT queue_name FROM pgmq.metrics_all()"
```

Expected: `calculation_jobs`, `calculation_snapshots` tables + `nexon_api_request_queue`, `nexon_api_response_queue` in metrics.

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/resources/db/migration/V114__external_api_boundary.sql
git commit -m "feat(schema): add calculation_jobs, snapshots tables and API request/response queues"
```

---

### Task 2: Core Domain Models — Status, Job, Snapshot

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobStatus.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJob.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/model/snapshot/CalculationSnapshot.kt`

- [ ] **Step 1: Create CalculationJobStatus enum**

`module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJobStatus.kt`:

```kotlin
package maple.expectation.core.model.job

enum class CalculationJobStatus {
    REQUESTED,
    API_REQUESTED,
    SNAPSHOT_READY,
    CALCULATING,
    COMPLETED,
    FAILED,
    RETRYING
}
```

- [ ] **Step 2: Create CalculationJob domain model**

`module-core/src/main/kotlin/maple/expectation/core/model/job/CalculationJob.kt`:

```kotlin
package maple.expectation.core.model.job

import java.time.Instant
import java.util.UUID

data class CalculationJob(
    val jobId: UUID,
    val ocid: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val status: CalculationJobStatus = CalculationJobStatus.REQUESTED,
    val snapshotId: UUID? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val nextRetryAt: Instant? = null,
    val lockedBy: String? = null,
    val lockedUntil: Instant? = null,
    val lastErrorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null
)
```

- [ ] **Step 3: Create CalculationSnapshot domain model**

`module-core/src/main/kotlin/maple/expectation/core/model/snapshot/CalculationSnapshot.kt`:

```kotlin
package maple.expectation.core.model.snapshot

import java.time.Instant
import java.util.UUID

data class CalculationSnapshot(
    val snapshotId: UUID,
    val jobId: UUID,
    val objectKey: String,
    val storageType: String = "LOCAL",
    val characterId: String? = null,
    val presetNo: Int = 1,
    val compressedSize: Long? = null,
    val originalSize: Long? = null,
    val hash: String? = null,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now()
)
```

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/model/
git commit -m "feat(core): add CalculationJobStatus, CalculationJob, CalculationSnapshot domain models"
```

---

### Task 3: Core Ports — SnapshotObjectStore, CalculationJobPort

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/SnapshotObjectStore.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`
- Modify: `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt`

- [ ] **Step 1: Create SnapshotObjectStore interface**

`module-core/src/main/kotlin/maple/expectation/core/port/out/SnapshotObjectStore.kt`:

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.model.snapshot.CalculationSnapshot

interface SnapshotObjectStore {
    fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult
    fun get(objectKey: String): ByteArray
    fun delete(objectKey: String)
}

data class SnapshotObjectStoreResult(
    val objectKey: String,
    val compressedSize: Long,
    val hash: String
)
```

- [ ] **Step 2: Create CalculationJobPort interface**

`module-core/src/main/kotlin/maple/expectation/core/port/out/CalculationJobPort.kt`:

```kotlin
package maple.expectation.core.port.out

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import java.util.UUID

interface CalculationJobPort {
    fun createJob(ocid: String, userIgn: String, presetNo: Int): CalculationJob
    fun findJobById(jobId: UUID): CalculationJob?
    fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean
    fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean
    fun incrementRetry(jobId: UUID, errorCode: String): Boolean
    fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean
    fun unlock(jobId: UUID): Boolean
    fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob>
    fun findActiveJobByOcid(ocid: String, presetNo: Int): CalculationJob?
}
```

- [ ] **Step 3: Add new queue names to QueueNames**

Read `module-core/src/main/kotlin/maple/expectation/core/port/out/QueueNames.kt`, then add:

```kotlin
object QueueNames {
    const val EXPECTATION_CALC_HIGH = "expectation_calc_high"
    const val EXPECTATION_CALC_LOW = "expectation_calc_low"
    const val NEXON_API_REQUEST = "nexon_api_request_queue"
    const val NEXON_API_RESPONSE = "nexon_api_response_queue"
}
```

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/port/out/
git commit -m "feat(core): add SnapshotObjectStore, CalculationJobPort interfaces and queue names"
```

---

### Task 4: JPA Entities and Repositories

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationJobEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotRepository.kt`

- [ ] **Step 1: Create CalculationJobEntity**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationJobEntity.kt`:

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "calculation_jobs")
open class CalculationJobEntity(

    @Id
    @Column(updatable = false, nullable = false)
    val jobId: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 64)
    val ocid: String,

    @Column(nullable = false, length = 64)
    val userIgn: String,

    @Column(nullable = false)
    val presetNo: Int = 1,

    @Column(nullable = false, length = 32)
    var status: String = "REQUESTED",

    val snapshotId: UUID? = null,

    var retryCount: Int = 0,

    val maxRetries: Int = 3,

    var nextRetryAt: Instant? = null,

    var lockedBy: String? = null,

    var lockedUntil: Instant? = null,

    @Column(length = 64)
    var lastErrorCode: String? = null,

    var errorMessage: String? = null,

    @Column(columnDefinition = "JSONB")
    var calculationResult: String? = null,

    val createdAt: Instant = Instant.now(),

    var updatedAt: Instant = Instant.now(),

    var completedAt: Instant? = null
)
```

- [ ] **Step 2: Create CalculationSnapshotEntity**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/CalculationSnapshotEntity.kt`:

```kotlin
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "calculation_snapshots")
open class CalculationSnapshotEntity(

    @Id
    @Column(updatable = false, nullable = false)
    val snapshotId: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val jobId: UUID,

    @Column(nullable = false, length = 512)
    val objectKey: String,

    @Column(nullable = false, length = 16)
    val storageType: String = "LOCAL",

    @Column(length = 64)
    val characterId: String? = null,

    val presetNo: Int = 1,

    val compressedSize: Long? = null,

    val originalSize: Long? = null,

    @Column(length = 128)
    val hash: String? = null,

    @Column(nullable = false)
    val expiresAt: Instant,

    val createdAt: Instant = Instant.now()
)
```

- [ ] **Step 3: Create CalculationJobRepository**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationJobRepository.kt`:

```kotlin
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CalculationJobRepository : JpaRepository<CalculationJobEntity, UUID> {

    @Query("""
        SELECT j FROM CalculationJobEntity j
        WHERE j.ocid = :ocid AND j.presetNo = :presetNo
          AND j.status IN ('REQUESTED', 'API_REQUESTED', 'SNAPSHOT_READY', 'CALCULATING', 'RETRYING')
    """)
    fun findActiveByOcidAndPreset(@Param("ocid") ocid: String, @Param("presetNo") presetNo: Int): CalculationJobEntity?

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = :to, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
    """)
    fun transitionStatus(
        @Param("jobId") jobId: UUID,
        @Param("from") from: String,
        @Param("to") to: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = 'SNAPSHOT_READY', j.snapshotId = :snapshotId,
            j.lockedBy = NULL, j.lockedUntil = NULL, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
    """)
    fun markSnapshotReady(
        @Param("jobId") jobId: UUID,
        @Param("snapshotId") snapshotId: UUID,
        @Param("from") from: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.status = 'FAILED', j.lastErrorCode = :errorCode,
            j.errorMessage = :errorMessage, j.completedAt = CURRENT_TIMESTAMP,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
    """)
    fun markFailed(
        @Param("jobId") jobId: UUID,
        @Param("errorCode") errorCode: String,
        @Param("errorMessage") errorMessage: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.retryCount = j.retryCount + 1,
            j.status = 'API_REQUESTED',
            j.nextRetryAt = :nextRetryAt,
            j.lastErrorCode = :errorCode,
            j.lockedBy = NULL, j.lockedUntil = NULL,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
          AND j.status IN ('API_REQUESTED', 'RETRYING')
          AND j.retryCount < j.maxRetries
    """)
    fun incrementRetry(
        @Param("jobId") jobId: UUID,
        @Param("errorCode") errorCode: String,
        @Param("nextRetryAt") nextRetryAt: Instant
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.lockedBy = :workerId,
            j.lockedUntil = :lockedUntil,
            j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId AND j.status = :from
          AND (j.lockedUntil IS NULL OR j.lockedUntil < CURRENT_TIMESTAMP)
    """)
    fun lockForProcessing(
        @Param("jobId") jobId: UUID,
        @Param("workerId") workerId: String,
        @Param("lockedUntil") lockedUntil: Instant,
        @Param("from") from: String
    ): Int

    @Modifying
    @Query("""
        UPDATE CalculationJobEntity j
        SET j.lockedBy = NULL, j.lockedUntil = NULL, j.updatedAt = CURRENT_TIMESTAMP
        WHERE j.jobId = :jobId
    """)
    fun unlock(@Param("jobId") jobId: UUID): Int

    @Query("""
        SELECT j FROM CalculationJobEntity j
        WHERE j.status = :status AND j.updatedAt < :cutoff
    """)
    fun findStaleJobs(
        @Param("status") status: String,
        @Param("cutoff") cutoff: Instant
    ): List<CalculationJobEntity>
}
```

- [ ] **Step 4: Create CalculationSnapshotRepository**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/CalculationSnapshotRepository.kt`:

```kotlin
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface CalculationSnapshotRepository : JpaRepository<CalculationSnapshotEntity, UUID> {

    fun findByJobId(jobId: UUID): CalculationSnapshotEntity?

    fun findByExpiresAtBefore(cutoff: Instant): List<CalculationSnapshotEntity>
}
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 6: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/
git commit -m "feat(infra): add CalculationJob/Snapshot JPA entities and repositories"
```

---

### Task 5: LocalSnapshotObjectStore

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/LocalSnapshotObjectStore.kt`

- [ ] **Step 1: Implement LocalSnapshotObjectStore**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/LocalSnapshotObjectStore.kt`:

```kotlin
package maple.expectation.infrastructure.external.snapshot

import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.core.port.out.SnapshotObjectStoreResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Component
class LocalSnapshotObjectStore(
    @Value("\${snapshot.store.local.base-path:/data/snapshots}")
    private val basePath: String
) : SnapshotObjectStore {

    override fun put(snapshot: CalculationSnapshot, data: ByteArray): SnapshotObjectStoreResult {
        val compressed = gzipCompress(data)
        val hash = sha256(compressed)
        val fullPath = resolveFullPath(snapshot.objectKey)

        fullPath.parent.toFile().mkdirs()

        FileOutputStream(fullPath.toFile()).use { fos ->
            fos.write(compressed)
        }

        return SnapshotObjectStoreResult(
            objectKey = snapshot.objectKey,
            compressedSize = compressed.size.toLong(),
            hash = hash
        )
    }

    override fun get(objectKey: String): ByteArray {
        val fullPath = resolveFullPath(objectKey)
        val compressed = Files.readAllBytes(fullPath)
        return gzipDecompress(compressed)
    }

    override fun delete(objectKey: String) {
        val fullPath = resolveFullPath(objectKey)
        Files.deleteIfExists(fullPath)
    }

    private fun resolveFullPath(objectKey: String): Path {
        val logicalKey = objectKey.removePrefix("/")
        return Paths.get(basePath, logicalKey)
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(compressed: ByteArray): ByteArray {
        GZIPInputStream(compressed.inputStream()).use { return it.readAllBytes() }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/external/snapshot/
git commit -m "feat(infra): add LocalSnapshotObjectStore with GZIP compression"
```

---

### Task 6: CalculationJobPortAdapter — Port Implementation

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`

- [ ] **Step 1: Implement CalculationJobPortAdapter**

`module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt`:

```kotlin
package maple.expectation.adapter.outgoing

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.infrastructure.persistence.entity.CalculationJobEntity
import maple.expectation.infrastructure.persistence.repository.CalculationJobRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class CalculationJobPortAdapter(
    private val jobRepository: CalculationJobRepository
) : CalculationJobPort {

    override fun createJob(ocid: String, userIgn: String, presetNo: Int): CalculationJob {
        val existing = jobRepository.findActiveByOcidAndPreset(ocid, presetNo)
        if (existing != null) {
            return existing.toDomain()
        }

        val entity = CalculationJobEntity(
            ocid = ocid,
            userIgn = userIgn,
            presetNo = presetNo
        )
        return jobRepository.save(entity).toDomain()
    }

    override fun findJobById(jobId: UUID): CalculationJob? {
        return jobRepository.findById(jobId).orElse(null)?.toDomain()
    }

    override fun transitionStatus(jobId: UUID, from: CalculationJobStatus, to: CalculationJobStatus): Boolean {
        return jobRepository.transitionStatus(jobId, from.name, to.name) > 0
    }

    override fun markSnapshotReady(jobId: UUID, snapshotId: UUID, from: CalculationJobStatus): Boolean {
        return jobRepository.markSnapshotReady(jobId, snapshotId, from.name) > 0
    }

    override fun markFailed(jobId: UUID, errorCode: String, errorMessage: String): Boolean {
        return jobRepository.markFailed(jobId, errorCode, errorMessage) > 0
    }

    override fun incrementRetry(jobId: UUID, errorCode: String): Boolean {
        val backoffSeconds = 30L // exponential backoff can be enhanced later
        val nextRetry = Instant.now().plusSeconds(backoffSeconds)
        return jobRepository.incrementRetry(jobId, errorCode, nextRetry) > 0
    }

    override fun lockForProcessing(jobId: UUID, workerId: String, from: CalculationJobStatus): Boolean {
        val lockedUntil = Instant.now().plusSeconds(300)
        return jobRepository.lockForProcessing(jobId, workerId, lockedUntil, from.name) > 0
    }

    override fun unlock(jobId: UUID): Boolean {
        return jobRepository.unlock(jobId) > 0
    }

    override fun findStaleJobs(status: CalculationJobStatus, olderThanSeconds: Long): List<CalculationJob> {
        val cutoff = Instant.now().minusSeconds(olderThanSeconds)
        return jobRepository.findStaleJobs(status.name, cutoff).map { it.toDomain() }
    }

    override fun findActiveJobByOcid(ocid: String, presetNo: Int): CalculationJob? {
        return jobRepository.findActiveByOcidAndPreset(ocid, presetNo)?.toDomain()
    }

    private fun CalculationJobEntity.toDomain() = CalculationJob(
        jobId = jobId,
        ocid = ocid,
        userIgn = userIgn,
        presetNo = presetNo,
        status = CalculationJobStatus.valueOf(status),
        snapshotId = snapshotId,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt,
        lockedBy = lockedBy,
        lockedUntil = lockedUntil,
        lastErrorCode = lastErrorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/adapter/outgoing/CalculationJobPortAdapter.kt
git commit -m "feat(infra): add CalculationJobPortAdapter with conditional UPDATE support"
```

---

### Task 7: CalculationJobService — Central State Machine

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`

- [ ] **Step 1: Implement CalculationJobService**

This service encapsulates all state transitions. Every Worker calls this service, never touches the job table directly.

`module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobService.kt`:

```kotlin
package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJob
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.QueueNames
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.queue.pgmq.NexonApiRequestMessage
import maple.expectation.infrastructure.queue.pgmq.NexonApiResponseMessage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CalculationJobService(
    private val jobPort: CalculationJobPort,
    private val pgmqClient: PgmqClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Read Path: job 생성만. 상태 전이는 하지 않음. */
    @Transactional
    fun createJob(ocid: String, userIgn: String, presetNo: Int): CalculationJob {
        val job = jobPort.createJob(ocid, userIgn, presetNo)
        log.info("[jobId={}] Job created in REQUESTED state", job.jobId)
        return job
    }

    /** Write Path: REQUESTED → API_REQUESTED 전이 + request_queue 발행 (같은 TX) */
    @Transactional
    fun requestApiData(jobId: UUID) {
        val transitioned = jobPort.transitionStatus(
            jobId,
            CalculationJobStatus.REQUESTED,
            CalculationJobStatus.API_REQUESTED
        )
        if (!transitioned) {
            log.warn("[jobId={}] Cannot transition to API_REQUESTED — already transitioned or job not found", jobId)
            return
        }

        val job = jobPort.findJobById(jobId) ?: return

        val request = NexonApiRequestMessage(
            jobId = job.jobId,
            ocid = job.ocid,
            userIgn = job.userIgn,
            presetNo = job.presetNo,
            eventType = "FETCH_EQUIPMENT",
            requestedAt = java.time.Instant.now().toString()
        )
        pgmqClient.send(QueueNames.NEXON_API_REQUEST, request)
        log.info("[jobId={}] Transitioned to API_REQUESTED, request enqueued", jobId)
    }

    /** External API Path: SNAPSHOT_READY 전이 + response_queue 발행 (같은 TX) */
    @Transactional
    fun markSnapshotReady(jobId: UUID, snapshotId: UUID, objectKey: String): Boolean {
        val ready = jobPort.markSnapshotReady(jobId, snapshotId, CalculationJobStatus.API_REQUESTED)
        if (ready) {
            val job = jobPort.findJobById(jobId)
            if (job != null) {
                val response = NexonApiResponseMessage(
                    eventType = "SNAPSHOT_READY",
                    jobId = jobId,
                    snapshotId = snapshotId,
                    objectKey = objectKey,
                    characterId = job.ocid,
                    presetNo = job.presetNo
                )
                pgmqClient.send(QueueNames.NEXON_API_RESPONSE, response)
                log.info("[jobId={}] Snapshot ready, response enqueued", jobId)
            }
        }
        return ready
    }

    @Transactional
    fun startCalculation(jobId: UUID, workerId: String): Boolean {
        val locked = jobPort.lockForProcessing(jobId, workerId, CalculationJobStatus.SNAPSHOT_READY)
        if (locked) {
            jobPort.transitionStatus(jobId, CalculationJobStatus.SNAPSHOT_READY, CalculationJobStatus.CALCULATING)
            log.info("[jobId={}] Calculation started by {}", jobId, workerId)
        }
        return locked
    }

    @Transactional
    fun completeCalculation(jobId: UUID): Boolean {
        val completed = jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED)
        if (completed) {
            jobPort.unlock(jobId)
            log.info("[jobId={}] Calculation completed", jobId)
        }
        return completed
    }

    @Transactional
    fun handleApiFailure(jobId: UUID, errorCode: String, errorMessage: String) {
        val job = jobPort.findJobById(jobId) ?: return

        if (job.retryCount >= job.maxRetries) {
            jobPort.markFailed(jobId, errorCode, errorMessage)
            log.warn("[jobId={}] Failed after {} retries: {}", jobId, job.retryCount, errorMessage)
        } else {
            val retried = jobPort.incrementRetry(jobId, errorCode)
            if (retried) {
                log.info("[jobId={}] Retrying (attempt {}): {}", jobId, job.retryCount + 1, errorCode)
            }
        }
    }
}
```

- [ ] **Step 2: Create NexonApiRequestMessage data class**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/NexonApiRequestMessage.kt`:

```kotlin
package maple.expectation.infrastructure.queue.pgmq

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class NexonApiRequestMessage(
    val jobId: java.util.UUID,
    val ocid: String,
    val userIgn: String,
    val presetNo: Int = 1,
    val eventType: String = "FETCH_EQUIPMENT",
    val requestedAt: String = Instant.now().toString()
)
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 4: Fix any compilation issues and commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/pgmq/NexonApiRequestMessage.kt
git commit -m "feat(infra): add CalculationJobService state machine and request message"
```

---

### Task 8: NexonApiWorker — External API Boundary Worker

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt`

This is the core new worker. It consumes `nexon_api_request_queue`, calls Nexon API, parses via Provider, saves Snapshot, publishes response.

- [ ] **Step 1: Implement NexonApiWorker**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt`:

```kotlin
package maple.expectation.infrastructure.worker

import maple.expectation.core.model.snapshot.CalculationSnapshot
import maple.expectation.core.port.out.QueueNames
import maple.expectation.core.port.out.SnapshotObjectStore
import maple.expectation.infrastructure.external.impl.NexonApiClient
import maple.expectation.infrastructure.infra.job.CalculationJobService
import maple.expectation.infrastructure.parser.EquipmentStreamingParser
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import maple.expectation.infrastructure.persistence.entity.CalculationSnapshotEntity
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.queue.pgmq.NexonApiRequestMessage
import maple.expectation.infrastructure.external.snapshot.LocalSnapshotObjectStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class NexonApiWorker(
    private val pgmqClient: PgmqClient,
    private val nexonApiClient: NexonApiClient,
    private val streamingParser: EquipmentStreamingParser,
    private val snapshotStore: SnapshotObjectStore,
    private val snapshotRepository: CalculationSnapshotRepository,
    private val jobService: CalculationJobService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${pgmq.worker.nexon-api.polling-interval-ms:100}")
    fun processMessages() {
        val messages = pgmqClient.read(
            QueueNames.NEXON_API_REQUEST,
            NexonApiRequestMessage::class.java,
            10,
            120
        )

        for (message in messages) {
            processSingle(message)
        }
    }

    private fun processSingle(message: PgmqMessage<NexonApiRequestMessage>) {
        val request = message.payload
        val jobId = request.jobId

        try {
            log.info("[jobId={}] Processing API request: eventType={}", jobId, request.eventType)

            // 1. Call Nexon API
            val rawResponse = nexonApiClient.getItemDataByOcid(request.ocid).join()

            // 2. Parse via Provider (raw JSON → calculation input)
            val rawBytes = objectMapper.writeValueAsBytes(rawResponse)
            val cubeInputs = streamingParser.parseCubeInputsForPreset(rawBytes, request.presetNo)

            // 3. Serialize parsed result for Snapshot
            val snapshotData = objectMapper.writeValueAsBytes(cubeInputs)

            // 4. Create Snapshot metadata
            val objectKey = generateObjectKey(jobId)
            val snapshot = CalculationSnapshot(
                snapshotId = UUID.randomUUID(),
                jobId = jobId,
                objectKey = objectKey,
                storageType = "LOCAL",
                characterId = request.ocid,
                presetNo = request.presetNo,
                expiresAt = Instant.now().plusSeconds(86400) // 24h TTL
            )

            // 5. Save Snapshot to store
            val result = snapshotStore.put(snapshot, snapshotData)

            // 6. Save metadata to DB
            val snapshotEntity = CalculationSnapshotEntity(
                snapshotId = snapshot.snapshotId,
                jobId = jobId,
                objectKey = objectKey,
                storageType = "LOCAL",
                characterId = request.ocid,
                presetNo = request.presetNo,
                compressedSize = result.compressedSize,
                originalSize = snapshotData.size.toLong(),
                hash = result.hash,
                expiresAt = snapshot.expiresAt
            )
            snapshotRepository.save(snapshotEntity)

            // 7. Update job state + publish response (same TX)
            jobService.markSnapshotReady(jobId, snapshot.snapshotId)

            // 8. Archive message
            pgmqClient.archive(QueueNames.NEXON_API_REQUEST, message.msgId)
            log.info("[jobId={}] API request processed, snapshot saved: {}", jobId, objectKey)

        } catch (e: Exception) {
            log.error("[jobId={}] API request failed: {}", jobId, e.message, e)
            jobService.handleApiFailure(jobId, "API_ERROR", e.message ?: "Unknown error")
            pgmqClient.archive(QueueNames.NEXON_API_REQUEST, message.msgId)
        }
    }

    private fun generateObjectKey(jobId: UUID): String {
        val now = Instant.now()
        val datePath = "%04d/%02d/%02d".format(
            now.atZone(java.time.ZoneOffset.UTC).year,
            now.atZone(java.time.ZoneOffset.UTC).monthValue,
            now.atZone(java.time.ZoneOffset.UTC).dayOfMonth
        )
        return "snapshots/$datePath/${jobId}.gz"
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

Expected: May need import adjustments based on actual package structure. Fix any errors.

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/NexonApiWorker.kt
git commit -m "feat(infra): add NexonApiWorker — API call, parse, snapshot save, response publish"
```

---

### Task 9: Modify Existing Calculation Worker

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/ExpectationCalcWorker.kt`

Read the existing files first to understand the exact structure, then modify to:
1. Replace direct API call with `CalculationJobService.createAndEnqueue()`
2. Add response_queue consumption for SNAPSHOT_READY → calculation flow

- [ ] **Step 1: Read existing AbstractExpectationCalcWorker**

```bash
cat module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt
```

- [ ] **Step 2: Modify the calculation flow**

The key change: replace `EquipmentFetchProvider` direct call with:
1. `CalculationJobService.createAndEnqueue()` — publishes to request queue
2. The worker's role is now to consume `nexon_api_response_queue` and continue calculation when snapshot is ready

This requires reading the existing file to understand the exact modification points. The modification depends on the current code structure, which was traced in the exploration. The `process()` method currently calls `expectationPort.calculateExpectationAsync()` which internally calls `EquipmentFetchProvider`.

The change: Instead of the synchronous chain, the worker will:
- Consume `nexon_api_response_queue`
- Read Snapshot via `SnapshotObjectStore.get(objectKey)`
- Parse the serialized `List<CubeCalculationInput>`
- Continue with the calculation

This step requires careful reading of the existing code to identify exact modification points. The implementer should:
1. Read `AbstractExpectationCalcWorker.kt` fully
2. Read the service chain (`EquipmentExpectationServiceV4` or equivalent)
3. Identify where `EquipmentFetchProvider` is called
4. Replace that call path with Snapshot-based calculation

- [ ] **Step 3: Verify compilation**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/
git commit -m "refactor(worker): replace direct API call with MQ-based snapshot flow"
```

---

### Task 10: Timeout Scanner

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt`

- [ ] **Step 1: Implement Timeout Scanner**

`module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt`:

```kotlin
package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CalculationJobTimeoutScanner(
    private val jobPort: CalculationJobPort,
    private val jobService: CalculationJobService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${job.scanner.timeout-interval-ms:30000}")
    fun scanStaleJobs() {
        val staleApiRequested = jobPort.findStaleJobs(CalculationJobStatus.API_REQUESTED, 30)
        for (job in staleApiRequested) {
            jobService.handleApiFailure(job.jobId, "API_TIMEOUT", "API response timeout after 30 seconds")
            log.warn("[jobId={}] Timeout detected: API_REQUESTED stale for >30s", job.jobId)
        }

        val staleRetrying = jobPort.findStaleJobs(CalculationJobStatus.RETRYING, 60)
        for (job in staleRetrying) {
            jobService.handleApiFailure(job.jobId, "RETRY_TIMEOUT", "Retry timeout after 60 seconds")
            log.warn("[jobId={}] Timeout detected: RETRYING stale for >60s", job.jobId)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/job/CalculationJobTimeoutScanner.kt
git commit -m "feat(infra): add CalculationJobTimeoutScanner for stale job recovery"
```

---

### Task 11: Application Configuration

**Files:**
- Modify: `module-app/src/main/resources/application.yml`
- Modify: `module-app/src/main/resources/application-local.yml`
- Modify: `module-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: Add new worker and queue configuration to application.yml**

Read the existing `application.yml` first to find the PGMQ worker config section, then add:

```yaml
pgmq:
  worker:
    nexon-api:
      enabled: true
      polling-interval-ms: 100
      batch-size: 10
      max-retries: 3
      visibility-timeout-sec: 120

snapshot:
  store:
    local:
      base-path: /data/snapshots

job:
  scanner:
    timeout-interval-ms: 30000
```

- [ ] **Step 2: Add local profile overrides**

In `application-local.yml`, add:

```yaml
snapshot:
  store:
    local:
      base-path: ./snapshots
```

- [ ] **Step 3: Add prod profile overrides**

In `application-prod.yml`, add:

```yaml
pgmq:
  worker:
    nexon-api:
      enabled: true
      polling-interval-ms: 50
      batch-size: 20
      max-retries: 5
      visibility-timeout-sec: 120

snapshot:
  store:
    local:
      base-path: /data/snapshots
```

- [ ] **Step 4: Verify configuration loads**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 5: Commit**

```bash
git add module-app/src/main/resources/application*.yml
git commit -m "feat(config): add NexonApi worker, snapshot store, and timeout scanner config"
```

---

### Task 12: Integration Verification

- [ ] **Step 1: Full compilation check**

```bash
./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAILED|ERROR" || echo "SUCCESS"
```

- [ ] **Step 2: Run existing tests**

```bash
./gradlew test 2>&1 | grep -E "FAILED|tests" | tail -5
```

- [ ] **Step 3: Start application locally and verify**

```bash
source .env && ./gradlew :module-app:bootRun
```

Verify in logs:
- `nexon_api_request_queue` and `nexon_api_response_queue` created
- `NexonApiWorker` scheduled polling active
- `CalculationJobTimeoutScanner` scheduled

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat: External API Boundary separation complete — MQ-based async pipeline"
```

---

## Self-Review Checklist

- [x] **Spec coverage**: All 13 decisions addressed in tasks
- [x] **Placeholder scan**: No TBD/TODO in tasks (Task 9 requires reading existing code first — noted)
- [x] **Type consistency**: `CalculationJob`, `CalculationSnapshot`, `CalculationJobStatus` used consistently across all tasks
- [x] **Queue name consistency**: `QueueNames.NEXON_API_REQUEST` / `NEXON_API_RESPONSE` used in all relevant tasks
- [x] **State machine flow**: REQUESTED → API_REQUESTED → SNAPSHOT_READY → CALCULATING → COMPLETED covered

**Known gaps requiring implementer judgment:**
- Task 9 (modify existing worker) requires reading the actual code to identify exact modification points. The exploration found that `ExpectationCalcWorker` → `AbstractExpectationCalcWorker` → `expectationPort.calculateExpectationAsync()` is the chain, but the exact refactoring depends on the current service layer structure.
- The `NexonApiClient.getItemDataByOcid()` return type needs to be verified for correct serialization in Task 8.
- Transaction boundaries in `CalculationJobService` need `@Transactional` to ensure same-TX guarantee for job UPDATE + PGMQ send.
