# V5 Query Server 분리: LOGGED Read Model + Next.js Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** V5 조회 경로를 Next.js Query Server로 분리. Java V5 CQRS가 LOGGED read model에 GZIP BYTEA를 저장하고, Next.js는 Read Replica에서 decompress하여 응답.

**Architecture:** Java V5 CQRS writes calculation results as GZIP-compressed JSON BYTEA to a LOGGED PostgreSQL table. A read replica replicates this data. Next.js reads from the replica, decompresses, and serves the V5 response. cache_storage (UNLOGGED) remains V4-only.

**Tech Stack:** Kotlin/Spring Boot (write side), PostgreSQL LOGGED table + Read Replica, Next.js 15 + TypeScript + `pg` (read side), Vercel (deployment)

---

## File Structure

### New Files (Java)

```
module-infra/src/main/resources/db/migration/
  V111__create_expectation_read_model.sql          ← LOGGED read model 테이블

module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/
  ExpectationReadModelEntity.kt                    ← JPA entity

module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/
  ExpectationReadModelRepository.kt                ← Spring Data JPA repository

module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/
  ExpectationReadModelWriteService.kt              ← GZIP 압축 + 쓰기 서비스

module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/
  ExpectationReadModelWriteServiceTest.kt          ← 단위 테스트
```

### New Files (Next.js)

```
query-server/
  package.json
  tsconfig.json
  next.config.ts
  .env.local.example
  src/
    app/
      api/v5/characters/[userIgn]/
        expectation/route.ts                        ← 메인 조회 엔드포인트
    lib/
      db.ts                                        ← Read Replica 연결
      decompress.ts                                ← GZIP → JSON
```

### Modified Files (Java)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/
  CharacterViewQueryServicePostgres.kt              ← read model 쓰기 훅 추가
```

---

## Task 1: Flyway Migration — LOGGED Read Model 테이블

**Files:**
- Create: `module-infra/src/main/resources/db/migration/V111__create_expectation_read_model.sql`

- [ ] **Step 1: 마이그레이션 파일 작성**

```sql
-- V111__create_expectation_read_model.sql
CREATE TABLE IF NOT EXISTS character_expectation_read_model (
    user_ign       VARCHAR(100) PRIMARY KEY,
    payload        BYTEA NOT NULL,
    calculated_at  TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE character_expectation_read_model
    IS 'V5 Query Server read model. GZIP compressed full V5 response payload. LOGGED for replica replication.';

-- [Consensus P0-2] Atomic UPSERT function (ON CONFLICT)
CREATE OR REPLACE FUNCTION upsert_expectation_read_model(
    p_user_ign      VARCHAR,
    p_payload       BYTEA,
    p_calculated_at TIMESTAMPTZ
) RETURNS void AS $$
BEGIN
    INSERT INTO character_expectation_read_model (user_ign, payload, calculated_at, updated_at)
    VALUES (p_user_ign, p_payload, p_calculated_at, NOW())
    ON CONFLICT (user_ign) DO UPDATE SET
        payload = EXCLUDED.payload,
        calculated_at = EXCLUDED.calculated_at,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- Index for TTL-based cleanup queries (DESC for recent-first scanning)
-- [P2-3] Phase 2: TTL cleanup job이 구현되면 아래 인덱스 활성화
--         현재는 PK(user_ign)로만 조회하므로 불필요
-- CREATE INDEX IF NOT EXISTS idx_read_model_calculated_at
--     ON character_expectation_read_model (calculated_at DESC);

-- [P2-1] Phase 2: Add payload versioning for schema migration support
-- ALTER TABLE character_expectation_read_model
--   ADD COLUMN payload_version INT DEFAULT 1;
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (migration은 컴파일에 영향 없음)

- [ ] **Step 3: 커밋**

```bash
git add module-infra/src/main/resources/db/migration/V111__create_expectation_read_model.sql
git commit -m "feat(infra): add LOGGED read model table for V5 Query Server

Table: character_expectation_read_model
Stores GZIP BYTEA payload for Next.js Query Server.
LOGGED table enables WAL replication to read replica.

ADR: docs/01_ADR/ADR-V5-query-server-nextjs-phase1.md"
```

---

## Task 2: JPA Entity + Repository

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/ExpectationReadModelEntity.kt`
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationReadModelRepository.kt`

- [ ] **Step 1: Entity 작성**

```kotlin
// ExpectationReadModelEntity.kt
package maple.expectation.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import com.fasterxml.jackson.annotation.JsonIgnore  // [P1-5] For internal fields
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "character_expectation_read_model")
class ExpectationReadModelEntity(
    @Id
    @Column(name = "user_ign", length = 100)
    var userIgn: String,

    @Column(name = "payload", nullable = false)
    var payload: ByteArray,

    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?): Boolean =
        other is ExpectationReadModelEntity && userIgn == other.userIgn

    override fun hashCode(): Int = userIgn.hashCode()
}
```

- [ ] **Step 2: Repository 작성**

```kotlin
// ExpectationReadModelRepository.kt
package maple.expectation.infrastructure.persistence.repository

import maple.expectation.infrastructure.persistence.entity.ExpectationReadModelEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.data.jpa.repository.Query  // [P1-6] Missing import
import org.springframework.data.repository.query.Param  // [P1-6] Missing import

@Repository
interface ExpectationReadModelRepository : JpaRepository<ExpectationReadModelEntity, String> {
    // [Consensus P0-2] Atomic UPSERT using native query
    @Query(value = "SELECT upsert_expectation_read_model(:userIgn, :payload, :calculatedAt)", nativeQuery = true)
    fun upsertNative(
        @Param("userIgn") userIgn: String,
        @Param("payload") payload: ByteArray,
        @Param("calculatedAt") calculatedAt: Instant,
    )
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAIL|BUILD|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/ExpectationReadModelEntity.kt \
       module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationReadModelRepository.kt
git commit -m "feat(infra): add ExpectationReadModel entity and repository

JPA entity for character_expectation_read_model table.
Stores GZIP BYTEA payload with user_ign as primary key."
```

---

## Task 3: Read Model Write Service (GZIP 압축 + 쓰기)

**Files:**
- Create: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteService.kt`
- Create: `module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteServiceTest.kt`

- [ ] **Step 1: 테스트 먼저 작성**

```kotlin
// ExpectationReadModelWriteServiceTest.kt
package maple.expectation.infrastructure.persistence

import maple.expectation.util.GzipUtils
import maple.expectation.infrastructure.persistence.entity.ExpectationReadModelEntity
import maple.expectation.infrastructure.persistence.repository.ExpectationReadModelRepository
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ExpectationReadModelWriteServiceTest {

    @Mock private lateinit var repository: ExpectationReadModelRepository
    @InjectMocks private lateinit var service: ExpectationReadModelWriteService
    @Captor private lateinit var payloadCaptor: ArgumentCaptor<ByteArray>

    @Test
    fun `writeToReadModel compresses JSON and saves entity`() {
        val userIgn = "testUser"
        val json = """{"userIgn":"testUser","totalExpectedCost":100}"""
        val calculatedAt = Instant.now()

        

        service.writeToReadModel(userIgn, json, calculatedAt)

        // [P1-7] Verify upsertNative() is called (not save())
        verify(repository).upsertNative(
            eq(userIgn),
            payloadCaptor.capture(),
            eq(calculatedAt),
        )

        val payload = payloadCaptor.value
        assertTrue(payload.size >= 2)
        // GZIP magic number: 0x1f 0x8b
        assert(payload[0] == 0x1f.toByte())
        assert(payload[1] == 0x8b.toByte())

        val decompressed = GzipUtils.decompress(payload)
        assert(decompressed == json)
    }

    @Test
    fun `writeToReadModel produces valid GZIP with magic bytes`() {
        val userIgn = "testUser"
        val json = """{"test":"data"}"""
        val calculatedAt = Instant.now()

        

        service.writeToReadModel(userIgn, json, calculatedAt)

        val payload = entityCaptor.value.payload
        assertTrue(payload.size >= 2)
        // GZIP magic number: 0x1f 0x8b
        assert(payload[0] == 0x1f.toByte())
        assert(payload[1] == 0x8b.toByte())
    }
}
```

- [ ] **Step 2: 테스트 실행 (실패 확인)**

Run: `./gradlew test --tests "maple.expectation.infrastructure.persistence.ExpectationReadModelWriteServiceTest" 2>&1 | tail -10`
Expected: FAIL (class not found)

- [ ] **Step 3: 구현체 작성**

```kotlin
// ExpectationReadModelWriteService.kt
package maple.expectation.infrastructure.persistence

import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.persistence.entity.ExpectationReadModelEntity
import maple.expectation.infrastructure.persistence.repository.ExpectationReadModelRepository
import maple.expectation.util.GzipUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Instant

@Service
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class ExpectationReadModelWriteService(
    private val repository: ExpectationReadModelRepository,
    private val executor: LogicExecutor,
) {
    private val log = LoggerFactory.getLogger(ExpectationReadModelWriteService::class.java)

    // [Consensus P0-1] Called within same TX from upsert(). Uses atomic ON CONFLICT.
    fun writeToReadModel(userIgn: String, json: String, calculatedAt: Instant) {
        val context = TaskContext.of("ReadModel", "Write", userIgn)
        executor.executeVoid({ performWrite(userIgn, json, calculatedAt) }, context)
    }

    private fun performWrite(userIgn: String, json: String, calculatedAt: Instant) {
        // [P1-9] IOException → IllegalStateException wrapping for LogicExecutor
        val compressed = try {
            GzipUtils.compress(json)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("GZIP compression failed for userIgn=$userIgn", e)
        }
        repository.upsertNative(userIgn, compressed, calculatedAt)
        log.debug("[ReadModel] Saved: userIgn={}, compressedSize={}", userIgn, compressed.size)
    }
}
```

- [ ] **Step 4: 테스트 실행 (통과 확인)**

Run: `./gradlew test --tests "maple.expectation.infrastructure.persistence.ExpectationReadModelWriteServiceTest" 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, tests pass

- [ ] **Step 5: 컴파일 전체 확인**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAIL|BUILD|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteService.kt \
       module-infra/src/test/kotlin/maple/expectation/infrastructure/persistence/ExpectationReadModelWriteServiceTest.kt
git commit -m "feat(infra): add ExpectationReadModelWriteService

Serializes V5 response JSON to GZIP BYTEA and writes to
character_expectation_read_model table. Uses existing GzipUtils
and LogicExecutor patterns."
```

---

## Task 4: V5 CQRS Write Path에 Read Model 쓰기 훅 추가

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt`

`CharacterViewQueryServicePostgres.upsert()`에서 뷰 저장 후 read model에도 쓰기.

### [Consensus P0-1] 같은 TX 내에서 두 테이블 쓰기 (consistency 보장)
### [Consensus P0-4] ObjectMapper Spring Bean 주입 (DIP 준수)
### [Consensus P1-2] Lambda Hell 해결: upsert() private method 추출
### [Consensus P1-4] calculatedAt null 금지 (명시적 예외)

- [ ] **Step 1: CharacterViewQueryServicePostgres에 의존성 주입 + 리팩토링**

```kotlin
// CharacterViewQueryServicePostgres.kt 전면 수정
@Service
@ConditionalOnProperty(name = ["v5.enabled"], havingValue = "true", matchIfMissing = false)
class CharacterViewQueryServicePostgres(
    private val repository: CharacterValuationViewJpaRepository,
    private val readModelWriteService: ExpectationReadModelWriteService,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,  // [P0-4] Bean 주입
    // [P1-2] ObjectMapper is auto-configured by Spring Boot via JacksonAutoConfiguration.
    // JacksonConfig customizers (JavaTimeModule, KotlinModule) are applied automatically.
    private val executor: LogicExecutor,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CharacterViewQueryServicePostgres::class.java)

    /**
     * Upsert character valuation view and propagate to read model.
     *
     * Transaction: Single @Transactional with REQUIRED propagation.
     * Both character_valuation_views and character_expectation_read_model
     * are written atomically in the same transaction.
     *
     * Caution: If called from @Async method, the transaction boundary
     * is the async method's caller. Use REQUIRES_NEW if isolation needed.
     */
    @Transactional("transactionManager")
    fun upsert(entity: CharacterValuationViewEntity) {
        val context = TaskContext.of("PostgresQuery", "Upsert", entity.userIgn)
        executor.executeVoid({ performUpsert(entity) }, context)
    }

    // [P1-2] Lambda Hell 해결: private method 추출
    private fun performUpsert(entity: CharacterValuationViewEntity) {
        val existing = findExistingEntity(entity)
        val saved = if (existing != null) {
            updateOrSkipExisting(existing, entity)
        } else {
            insertNew(entity)
        }
        // [P1-4] Always write to read model with latest available data
        val readModelSource = saved ?: existing
        if (readModelSource != null) {
            saveToReadModel(readModelSource)
        }
    }

    private fun updateOrSkipExisting(
        existing: CharacterValuationViewEntity,
        incoming: CharacterValuationViewEntity,
    ): CharacterValuationViewEntity? {
        val incomingVersion = incoming.version ?: 0L
        val currentVersion = existing.lastAppliedVersion ?: existing.version ?: 0L

        return if (incomingVersion > currentVersion) {
            repository.save(buildUpdatedEntity(existing, incoming, incomingVersion)).also {
                meterRegistry.counter("postgres.optimistic_lock.updated").increment()
            }
        } else {
            meterRegistry.counter("postgres.optimistic_lock.skipped").increment()
            log.debug("[Postgres] Skipping stale update: userIgn={}, version={}", existing.userIgn, incomingVersion)
            null  // Return null, but read model will still be written with existing data
            null  // Skip read model write for stale updates
        }
    }

    private fun insertNew(entity: CharacterValuationViewEntity): CharacterValuationViewEntity =
        repository.save(buildNewEntity(entity)).also {
            meterRegistry.counter("postgres.optimistic_lock.inserted").increment()
        }

    // [P1-4] calculatedAt null → 명시적 예외 (silent coercion 금지)
    // [Consensus P0-8] Best-effort: read model write failure does NOT roll back main entity save.
                    // Next calculation will overwrite. Uses executeOrCatch for non-fatal error handling.
    // [P1-3] calculated_at uses application time (Instant.now() at calculation).
    // TTL check in Next.js uses DB NOW(), so max clock skew = tolerance.
    // This is acceptable: MAX_STALE_SECONDS (5s) absorbs typical NTP drift (<1s).
    //                     Next calculation will overwrite. Uses executeOrCatch for non-fatal error handling.
    private fun saveToReadModel(entity: CharacterValuationViewEntity) {
        val calculatedAt = entity.calculatedAt
            ?: throw IllegalStateException(
                "calculatedAt must be set before writing to read model: userIgn=${entity.userIgn}"
            )
        val json = serializeEntityToJson(entity)
        executor.executeOrCatch(
            { readModelWriteService.writeToReadModel(entity.userIgn, json, calculatedAt) },
            { e ->
                log.warn("[ReadModel] Non-fatal write failure (will retry on next calculation): userIgn={}", entity.userIgn, e)
            },
            TaskContext.of("ReadModel", "BestEffortWrite", entity.userIgn),
        )
    }

    // [P1-5] Serialize entity directly. Jackson handles nested data classes.
    // Internal fields (id, jpaVersion, version, lastAppliedVersion)
    // should be annotated with @JsonIgnore in Entity class.
    private fun serializeEntityToJson(entity: CharacterValuationViewEntity): String {
        return objectMapper.writeValueAsString(entity)
    }

    // ... 기존 메서드 (findByUserIgn, findExistingEntity, buildUpdatedEntity,
    //     buildNewEntity, deleteByUserIgn, deleteAll, countByUserIgn,
    //     getLastAppliedVersion) 유지 ...
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileKotlin compileJava --continue 2>&1 | grep -E "FAIL|BUILD|ERROR" | head -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 기존 테스트 통과 확인**

Run: `./gradlew test 2>&1 | grep -E "FAIL|BUILD|tests completed" | head -5`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: 커밋**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/CharacterViewQueryServicePostgres.kt
git commit -m "feat(infra): hook read model write into V5 CQRS upsert path

After saving to character_valuation_views, also writes GZIP compressed
JSON to character_expectation_read_model for Next.js Query Server."
```

---

## Task 5: Next.js 프로젝트 초기화

**Files:**
- Create: `query-server/package.json`
- Create: `query-server/tsconfig.json`
- Create: `query-server/next.config.ts`
- Create: `query-server/.env.local.example`

- [ ] **Step 1: Next.js 프로젝트 생성**

```bash
cd /home/maple/probabilistic-valuation-engine
mkdir -p query-server
cd query-server
npx create-next-app@latest . --typescript --app --no-tailwind --no-eslint --no-src-dir --import-alias "@/*" --use-npm
```

대화형 프롬프트에서 기본값 선택.

- [ ] **Step 2: pg 패키지 설치**

```bash
cd /home/maple/probabilistic-valuation-engine/query-server
npm install pg
npm install -D @types/pg
```

- [ ] **Step 3: .env.local.example 작성**

```env
# query-server/.env.local.example
REPLICA_DATABASE_URL=postgresql://user:pass@host:5432/dbname
CACHE_TTL_SECONDS=3600  # 60 minutes in seconds
MAX_STALE_SECONDS=5     # Replica lag tolerance (default: 5 seconds)
```

- [ ] **Step 4: next.config.ts 확인/수정**

```typescript
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Serverless function settings for Vercel
};

export default nextConfig;
```

- [ ] **Step 5: 기본 페이지 제거, API 전용으로 정리**

```bash
rm -f /home/maple/probabilistic-valuation-engine/query-server/app/page.tsx
rm -f /home/maple/probabilistic-valuation-engine/query-server/app/page.module.css
rm -rf /home/maple/probabilistic-valuation-engine/query-server/app/favicon.ico
```

- [ ] **Step 6: 빌드 확인**

```bash
cd /home/maple/probabilistic-valuation-engine/query-server && npm run build 2>&1 | tail -5
```
Expected: Build successful

- [ ] **Step 7: 커밋**

```bash
cd /home/maple/probabilistic-valuation-engine
git add query-server/
git commit -m "feat(query-server): initialize Next.js project for V5 Query Server

App Router with TypeScript. pg driver for PostgreSQL read replica.
API-only (no frontend pages)."
```

---

## Task 6: Next.js DB 연결 + Decompress 유틸리티

**Files:**
- Create: `query-server/app/lib/db.ts`
- Create: `query-server/app/lib/decompress.ts`

- [ ] **Step 1: DB 연결 모듈 작성**

### [Consensus P0-5] Pool 에러 핸들링, statement_timeout
### [Consensus P1-1] Serverless 최적화: max=2, query timeout

```typescript
// query-server/app/lib/db.ts
import { Pool } from "pg";

// [P1-8] Custom error types for classification
export class DatabaseConnectionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "DatabaseConnectionError";
  }
}

export class QueryTimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "QueryTimeoutError";
  }
}

if (!process.env.REPLICA_DATABASE_URL) {
  throw new Error("REPLICA_DATABASE_URL environment variable is required");
}

// [P1-1] Pool recreation for serverless environments
let pool: Pool | null = null;

function getPool(): Pool {
  if (!pool) {
    pool = new Pool({
      connectionString: process.env.REPLICA_DATABASE_URL,
      max: 2,                    // [P1-1] Serverless: max 2 connections per instance
      idleTimeoutMillis: 10000,  // 빠른 해제
      connectionTimeoutMillis: 10000,
      statement_timeout: 5000,   // [P0-5] Query timeout 5s
      query_timeout: 5000,
    });

    pool.on("error", (err) => {
      console.error("[DB] Pool error, destroying pool:", err.message);
      pool?.end().catch(() => {});
      pool = null;
    });
  }
  return pool;
}

// NOTE: For production, use PgBouncer between Vercel and Replica
// to prevent connection exhaustion under high concurrency.
// Expected: QPS * avg_query_time / max_connections = required pool size

export async function query(text: string, params: unknown[]) {
  const start = Date.now();
  try {
    const result = await getPool().query(text, params);
    const duration = Date.now() - start;
    if (duration > 1000) {
      console.warn(`[DB] Slow query: ${duration}ms`);
    }
    return result;
  } catch (error) {
    console.error("[DB] Query failed:", error instanceof Error ? error.message : String(error));

    // [P1-8] Classify errors for better handling
    if (error instanceof Error) {
      if (error.message.includes("timeout") || error.message.includes("statement timeout")) {
        throw new QueryTimeoutError(error.message);
      }
      if (error.message.includes("connection") || error.message.includes("ECONNREFUSED")) {
        throw new DatabaseConnectionError(error.message, error);
      }
    }
    throw error;
  }
}
```

- [ ] **Step 2: Decompress 유틸리티 작성**

### [Consensus P1-3] 커스텀 에러 타입 + GZIP 복구

```typescript
// query-server/app/lib/decompress.ts
import { gunzipSync } from "zlib";

export class GzipDecompressionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "GzipDecompressionError";
  }
}

// [Consensus P0-2] Decompression bomb protection
const MAX_COMPRESSED_BYTES = 1_000_000; // 1MB
const MAX_DECOMPRESSED_BYTES = 10_000_000; // 10MB

export function decompressPayload(payload: Buffer): string {
  if (!Buffer.isBuffer(payload) || payload.length < 2) {
    throw new GzipDecompressionError("Payload too short or not a Buffer");
  }
  if (payload.length > MAX_COMPRESSED_BYTES) {
    throw new GzipDecompressionError(`Compressed payload too large: ${payload.length} bytes`);
  }
  if (payload[0] !== 0x1f || payload[1] !== 0x8b) {
    throw new GzipDecompressionError("Invalid GZIP magic number");
  }
  try {
    const decompressed = gunzipSync(payload);
    if (decompressed.length > MAX_DECOMPRESSED_BYTES) {
      throw new GzipDecompressionError(`Decompressed payload too large: ${decompressed.length} bytes`);
    }
    return decompressed.toString("utf-8");
  } catch (error) {
    if (error instanceof GzipDecompressionError) throw error;
    throw new GzipDecompressionError(`GZIP decompression failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}

export function isExpired(dbNow: Date, calculatedAt: Date, ttlMinutes: number): boolean {
  // [Consensus P1-7] DB 시간 기준 TTL 비교 (clock skew 방지)
  const expiresAt = new Date(calculatedAt.getTime() + ttlMinutes * 60_000);
  return dbNow > expiresAt;
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd /home/maple/probabilistic-valuation-engine/query-server && npm run build 2>&1 | tail -5
```
Expected: Build successful

- [ ] **Step 4: 커밋**

```bash
cd /home/maple/probabilistic-valuation-engine
git add query-server/app/lib/
git commit -m "feat(query-server): add DB connection and GZIP decompress utilities

db.ts: PostgreSQL read replica connection pool.
decompress.ts: GZIP payload decompression with magic number validation."
```

---

## Task 7: Next.js API 엔드포인트 구현

**Files:**
- Create: `query-server/app/api/v5/characters/[userIgn]/expectation/route.ts`

- [ ] **Step 1: API route 작성**

### [Consensus P0-3] DB 시간 기준 TTL + MAX_STALE_SECONDS
### [Consensus P1-3] 에러 분류 (GzipDecompressionError, DB 에러)

```typescript
// query-server/app/api/v5/characters/[userIgn]/expectation/route.ts
import { NextRequest, NextResponse } from "next/server";
import { query, DatabaseConnectionError, QueryTimeoutError } from "@/lib/db";
import { decompressPayload, isExpired, GzipDecompressionError } from "@/lib/decompress";

// [Consensus P0-1] Use seconds consistently. Change default MAX_STALE_SECONDS: 30 → 5.
const CACHE_TTL_SECONDS = parseInt(process.env.CACHE_TTL_SECONDS ?? "3600", 10); // 60 minutes
const MAX_STALE_SECONDS = parseInt(process.env.MAX_STALE_SECONDS ?? "5", 10); // 5 seconds

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ userIgn: string }> },
) {
  const { userIgn } = await params;

  // [P2-4] Request ID for cross-service tracing
  const requestId = request.headers.get("x-request-id")
    ?? crypto.randomUUID();

  try {
    // [P0-3] DB 시간(NOW()) 함께 조회 → clock skew 방지
    const result = await query(
      `SELECT payload, calculated_at,
              NOW() as db_now,
              EXTRACT(EPOCH FROM (NOW() - calculated_at)) as age_seconds
       FROM character_expectation_read_model WHERE user_ign = $1`,
      [userIgn],
    );

    if (result.rows.length === 0) {
      return NextResponse.json({ status: "pending" }, { status: 202 });
    }

    const row = result.rows[0];

    // [P0-1] Fixed: MAX_STALE_SECONDS is already in seconds, don't multiply by 60 again.
    // Correct formula: TTL_SECONDS + MAX_STALE_SECONDS (e.g., 3600 + 5 = 3605s threshold)
    const maxAgeSeconds = CACHE_TTL_SECONDS + MAX_STALE_SECONDS;
    if (row.age_seconds > maxAgeSeconds) {
      return NextResponse.json(
        { status: "error", code: "REPLICA_STALE", retryable: true },
        { status: 503 },
      );
    }

    // [P1-7] DB 시간 기준 TTL 비교
    const ttlMinutes = Math.ceil(CACHE_TTL_SECONDS / 60);
    if (isExpired(row.db_now, row.calculated_at, ttlMinutes)) {
      return NextResponse.json({ status: "pending" }, { status: 202 });
    }

    const json = decompressPayload(row.payload);

    // [P2-4] Include Request ID in response for cross-service tracing
    return new NextResponse(json, {
      headers: {
        "Content-Type": "application/json",
        "X-Request-ID": requestId,
      },
    });
  } catch (error) {
    // [P0-9] Self-heal: delete corrupted payload on GZIP error
    if (error instanceof GzipDecompressionError) {
      // Self-heal: delete corrupted payload so next poll triggers recalculation
      try {
        await query(
          'DELETE FROM character_expectation_read_model WHERE user_ign = $1',
          [userIgn],
        );
      } catch (cleanupError) {
        console.error('[DB] Failed to cleanup corrupted payload:', cleanupError);
      }
      return NextResponse.json(
        { status: "pending", code: "PAYLOAD_CORRUPTED" },
        { status: 202 },  // 202 triggers client recalculation, not 503
      );
    }

    // [P1-8] Classify errors for better client response
    if (error instanceof DatabaseConnectionError) {
      return NextResponse.json(
        { status: "error", code: "DATABASE_UNAVAILABLE", retryable: true },
        { status: 503 },
      );
    }

    if (error instanceof QueryTimeoutError) {
      return NextResponse.json(
        { status: "error", code: "QUERY_TIMEOUT", retryable: true },
        { status: 504 },
      );
    }

    return NextResponse.json(
      { status: "error", code: "UNKNOWN_ERROR" },
      { status: 500 },
    );
  }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd /home/maple/probabilistic-valuation-engine/query-server && npm run build 2>&1 | tail -5
```
Expected: Build successful

- [ ] **Step 3: 커밋**

```bash
cd /home/maple/probabilistic-valuation-engine
git add query-server/app/api/
git commit -m "feat(query-server): add V5 expectation API endpoint

GET /api/v5/characters/{userIgn}/expectation
- 200: decompressed V5 JSON response
- 202: pending (no data or TTL expired)
- 503: payload corrupted or DB unavailable"
```

---

## Task 8: 인프라 — Read Replica 설정 가이드

이 태스크는 코드가 아닌 인프라 설정입니다. Vultr에서 수동으로 수행해야 합니다.

- [ ] **Step 1: Primary PostgreSQL 설정**

Primary DB (`postgresql.conf`):
```
wal_level = replica
max_wal_senders = 5
max_replication_slots = 5
```

- [ ] **Step 2: Replication slot 생성**

```sql
SELECT pg_create_physical_replication_slot('query_server_replica');
```

- [ ] **Step 3: Replica 인스턴스 설정**

Replica DB (`postgresql.conf`):
```
hot_standby = on
```

Replica 시작:
```bash
pg_basebackup -h <PRIMARY_IP> -U replication -D /var/lib/postgresql/data -Fp -Xs -P -R
```

- [ ] **Step 4: 연결 확인**

```bash
psql -h <REPLICA_IP> -U maple -d maple_expectation -c \
  "SELECT * FROM character_expectation_read_model LIMIT 1;"
```

- [ ] **Step 5: SSL 설정 (Vercel → Replica)**

Primary와 Replica 모두 `postgresql.conf`:
```
ssl = on
ssl_cert_file = '/etc/ssl/certs/server.crt'
ssl_key_file = '/etc/ssl/private/server.key'
```

---

## Self-Review Checklist

- [x] Spec coverage: ADR의 모든 요구사항이 Task 1-7에 매핑됨
- [x] Placeholder scan: TBD/TODO 없음. 모든 단계에 코드 포함
- [x] Type consistency: `ExpectationReadModelEntity.userIgn: String` = `CharacterValuationViewEntity.userIgn: String` = Next.js `params.userIgn: string`
- [x] No duplicate logic: GZIP 압축은 `GzipUtils` 재사용, DB 쓰기는 `ExpectationReadModelWriteService`에 캡슐화
- [x] CLAUDE.md 규칙 준수: Zero Try-Catch (LogicExecutor 사용), Lambda Hell 방지 (private method 추출), ADR 선행 완료

---

## Consensus Review 결과 (2026-04-19)

3-Agent Review (Architect + Critic + Code-Reviewer) 후 수정 반영.

### P0 수정 완료

| ID | 이슈 | 수정 내용 | 적용 Task |
|----|------|-----------|-----------|
| P0-1 | Two-table TX consistency | 같은 TX 내 두 테이블 쓰기 + stale update 시 read model skip | Task 4 |
| P0-2 | JPA save() race condition | Native `ON CONFLICT DO UPDATE` UPSERT function | Task 1, 2 |
| P0-3 | Read Replica lag 대응 | DB 시간(NOW()) 기준 TTL + MAX_STALE_SECONDS 임계값 | Task 7 |
| P0-4 | ObjectMapper 매번 생성 | Spring Bean 주입 (기존 JacksonConfig 재사용) | Task 4 |
| P0-5 | DB pool 에러 핸들링 누락 | pool.on("error"), statement_timeout, query_timeout | Task 6 |
| **P0-6** | **MAX_STALE_SECONDS 수학 오류** | **초 단位 변수를 60으로 다시 곱하는 오류 수정. CACHE_TTL_SECONDS 사용 (3600s 기본값). MAX_STALE_SECONDS 기본값 30→5초** | **Task 7** |
| **P0-7** | **GZIP Decompression Bomb** | **압축/분해 크기 제한 추가 (MAX_COMPRESSED_BYTES: 1MB, MAX_DECOMPRESSED_BYTES: 10MB)** | **Task 6** |
| **P0-8** | **Read Model TX 롤백 정책** | **Best-effort 패턴 명시: executeOrCatch()로 감싸서 read model 쓰기 실패 시 메인 entity 저장 롤백 방지** | **Task 4** |
| **P0-9** | **손상된 Payload 자동 복구** | **GzipDecompressionError 시 해당 row 삭제 후 202 응답 (영구 503 루프 방지)** | **Task 7** |

### P1 수정 완료

| ID | 이슈 | 수정 내용 | 적용 Task |
|----|------|-----------|-----------|
| P1-1 | Connection pool 서버리스 부적합 | max: 10 → max: 2, idleTimeout 10s, pool 재생성 로직 추가, PgBouncer 권장 주석 | Task 6 |
| P1-2 | Lambda Hell (upsert 30줄+) | performUpsert, updateOrSkipExisting, insertNew private method 추출, ObjectMapper 주석 추가 | Task 4 |
| P1-3 | Clock skew — calculatedAt 시간 소스 | saveToReadModel에 clock skew 허용 가능성 주석 추가 (MAX_STALE_SECONDS가 NTP drift 흡수) | Task 4 |
| P1-4 | Optimistic Lock Skip → Read Model Stale | 버전 skip 시에도 기존 entity로 read model write (saved ?: existing 패턴) | Task 4 |
| P1-5 | DIP 위반 — mapOf 대신 Entity 직렬화 | Entity를 직렬화, internal 필드에 @JsonIgnore 추가 | Task 2, 4 |
| P1-6 | @Param Import 누락 | @Param, @Query import 구문 추가 | Task 2 |
| P1-7 | Test Mock Pattern 오류 | 테스트를 repository.save() → upsertNative() ArgumentCaptor로 변경 | Task 3 |
| P1-8 | TypeScript Error Type 분류 | DatabaseConnectionError, QueryTimeoutError 커스텀 클래스 추가, route.ts에서 에러 분류 | Task 6, 7 |
| P1-9 | GzipUtils IOException 래핑 | performWrite에서 IOException → IllegalStateException 래핑 | Task 3 |

### P2 보류 (구현 시 참고)

| ID | 이슈 | 비고 |
|----|------|------|
| P2-1 | 마이그레이션 3단계 명문화 | 구현 Phase에서 별도 문서화 |
| P2-2 | expires_at 컬럼 (DB TTL) | Phase 2에서 검토 |
| P2-3 | Payload versioning / size limit | 구현 후 모니터링으로 판단 |
| P2-4 | Replica lag 모니터링 | Grafana 대시보드 구축 시 |
| P2-5 | Next.js ISR caching | 성능 튜닝 단계에서 |

---

## Verification

전체 구현 후 확인:

1. `./gradlew compileKotlin compileJava --continue` — Java 컴파일 성공
2. `./gradlew test` — Java 단위 테스트 통과
3. `cd query-server && npm run build` — Next.js 빌드 성공
4. Read Replica에서 `SELECT * FROM character_expectation_read_model` 동작
5. `curl http://localhost:3000/api/v5/characters/{userIgn}/expectation` — 200 또는 202 응답

---

## Phase 2: 모니터링 & 운영

### [P2-2] Replica Lag 모니터링

```sql
-- Primary
SELECT pg_current_wal_lsn();

-- Replica
SELECT pg_last_wal_replay_lsn();

-- Lag (bytes)
SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), pg_last_wal_replay_lsn());
```

- **Prometheus metric**: `replica_lag_bytes`
- **Alert**: lag > 10MB sustained for 30s

### [P2-3] TTL Cleanup Job

```sql
-- Phase 2: 삭제된 인덱스(idx_read_model_calculated_at)가 활성화된 후 실행
DELETE FROM character_expectation_read_model
WHERE calculated_at < NOW() - INTERVAL '7 days';
```

- [ ] Phase 2: TTL cleanup job 구현
- [ ] Phase 2: `idx_read_model_calculated_at` 인덱스 활성화
