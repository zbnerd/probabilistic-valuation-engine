# ADR-004: MongoDB CQRS Read Model → PostgreSQL JSONB 마이그레이션

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-10 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551, #583 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략 |

---

## 1. 배경 (Context)

### 현재 아키텍처 (V5 CQRS)

probabilistic-valuation-engine은 MongoDB를 **CQRS Read Side**로 활용:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Service   │───>│  MySQL DB   │    │  MongoDB    │
│  (Command)  │    │ (Write Side)│    │ (Read Side) │
└─────────────┘    └─────────────┘    └─────────────┘
                          │                    │
                          ▼                    ▼
                   ┌─────────────┐    ┌─────────────┐
                   │ Outbox      │───>│ Sync Worker │
                   └─────────────┘    └─────────────┘
```

### MongoDB 사용 현황

| 컬렉션 | 목적 | 문서 구조 |
|--------|------|----------|
| **character_valuation_view** | 장비 기대값 조회 결과 | Nested JSON, Presets 배열 |

### 문제점

| 문제 | 영향 |
|------|------|
| **이중 DB 운영** | MySQL + MongoDB 동기화 복잡성 |
| **Sync Worker 오버헤드** | Outbox 테이블 + MongoDBSyncWorker |
| **데이터 일관성** | MySQL과 MongoDB 간 지연 |
| **쿼리 복잡성** | MongoDB Aggregation 파이프라인 |
| **인덱스 관리** | MongoDB 인덱스와 MySQL 인덱스 이중 관리 |

### 데이터 볼륨 분석

| 지표 | 현재 | 예상 (1년후) |
|------|------|-------------|
| 문서 수 | ~10,000 | ~100,000 |
| 평균 문서 크기 | ~5KB | ~5KB |
| 조회 QPS | 10-50 | 50-200 |

---

## 2. 결정 (Decision)

**MongoDB CQRS Read Model을 PostgreSQL JSONB로 통합한다.**

### 핵심 원칙

1. **JSONB 컬럼 활용**
   - 중첩된 JSON 데이터를 단일 컬럼에 저장
   - GIN 인덱스로 JSONB 쿼리 성능 확보

2. **단일 DB 트랜잭션**
   - Write Side와 Read Side가 동일한 DB
   - Sync Worker 불필요

3. **점진적 마이그레이션**
   - CharacterValuationView → PostgreSQL 테이블
   - Optimistic Locking 유지

4. **쿼리 호환성**
   - 기존 MongoDB 쿼리 패턴을 JSONB 쿼리로 변환

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (MongoDB + MySQL)

**장점:**
- 변경 비용 없음
- CQRS 패턴 검증됨

**단점:**
- Sync Worker 유지 보수
- 이중 DB 운영
- 데이터 일관성 복잡성

**평가:** ❌ 기술 부채 지속

### B. PostgreSQL JSONB (선택됨)

**장점:**
- 단일 DB 운영
- Sync Worker 제거
- ACID 트랜잭션 보장
- GIN 인덱스로 빠른 JSONB 쿼리

**단점:**
- JSONB 쿼리 학습 곡선
- 대용량 JSONB 업데이트 비용

**평가:** ✅ 장기적 관점에서 최적

### C. Elasticsearch 통합

**장점:**
- 전문 검색 기능
- 높은 확장성

**단점:**
- 과도한 복잡성
- 추가 인프라 비용

**평가:** ⚠️ Over-engineering

---

## 4. 기술적 구현 (Implementation)

### 스키마 변환

#### MongoDB → PostgreSQL

```sql
-- Character Valuation View Table (PostgreSQL)
CREATE TABLE character_valuation_view (
    id VARCHAR(50) PRIMARY KEY,                    -- MongoDB ObjectId 대신 messageId 사용
    user_ign VARCHAR(50) NOT NULL,
    character_ocid VARCHAR(50) NOT NULL,
    character_class VARCHAR(50),
    character_level INTEGER,

    -- JSONB: Nested Presets (MongoDB Array)
    presets JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- Metadata
    total_expected_cost BIGINT,
    max_preset_no INTEGER,
    calculated_at TIMESTAMPTZ,
    last_api_sync_at TIMESTAMPTZ,
    from_cache BOOLEAN DEFAULT false,

    -- Optimistic Locking
    version BIGINT NOT NULL DEFAULT 1,
    last_applied_version BIGINT NOT NULL DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 인덱스
CREATE INDEX idx_character_view_user_ign ON character_valuation_view(user_ign);
CREATE INDEX idx_character_view_ocid ON character_valuation_view(character_ocid);
CREATE INDEX idx_character_view_presets_gin ON character_valuation_view USING gin(presets);
CREATE INDEX idx_character_view_updated_at ON character_valuation_view(updated_at DESC);
```

### JSONB 데이터 구조

```json
{
  "presets": [
    {
      "preset_no": 1,
      "total_expected_cost": 1234567890,
      "items": [
        {
          "item_name": "아케인심볼",
          "item_level": 20,
          "starforce": 20,
          "potential_tier": 3
        }
      ]
    }
  ]
}
```

### GIN 인덱스 활용 쿼리

```sql
-- Presets 배열에서 특정 조건 검색
SELECT user_ign, presets
FROM character_valuation_view
WHERE presets @> '[{"preset_no": 1}]';

-- Presets 배열 내부 필터링
SELECT user_ign,
       jsonb_array_elements(presets)->>'preset_no' as preset_no,
       jsonb_array_elements(presets)->>'total_expected_cost' as cost
FROM character_valuation_view
WHERE user_ign = '닉네임';

-- JSONB Path 쿼리 (PostgreSQL 12+)
SELECT user_ign,
       presets->0->>'total_expected_cost' as first_preset_cost
FROM character_valuation_view
WHERE user_ign = '닉네임';
```

### JPA Entity 구현

```kotlin
// module-infra/src/main/kotlin/.../postgresql/CharacterValuationViewEntity.kt
@Entity
@Table(name = "character_valuation_view")
class CharacterValuationViewEntity {

    @Id
    @Column(length = 50)
    var id: String? = null

    @Column(name = "user_ign", nullable = false, length = 50)
    var userIgn: String? = null

    @Column(name = "character_ocid", nullable = false, length = 50)
    var characterOcid: String? = null

    @Column(name = "presets", columnDefinition = "jsonb", nullable = false)
    var presets: String? = null  // JSON 문자열로 저장

    @Column(name = "version", nullable = false)
    var version: Long? = null

    @Column(name = "last_applied_version", nullable = false)
    var lastAppliedVersion: Long? = null

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
}
```

### Repository 변환

```kotlin
// Before: MongoDB
interface CharacterValuationRepository : MongoRepository<CharacterValuationView, String> {
    fun findByUserIgn(userIgn: String): CharacterValuationView?
}

// After: PostgreSQL
@Repository
interface CharacterValuationViewRepository : JpaRepository<CharacterValuationViewEntity, String> {
    fun findByUserIgn(userIgn: String): CharacterValuationViewEntity?

    @Query("""
        SELECT id, user_ign, presets, version
        FROM character_valuation_view
        WHERE presets @> :presetFilter
    """, nativeQuery = true)
    fun findByPresetCondition(@Param("presetFilter") presetFilter: String): List<CharacterValuationViewEntity>
}
```

### MongoDBSyncWorker 제거

**Before:**
```kotlin
@Component
class MongoDBSyncWorker(
    private val outboxRepository: DonationOutboxRepository,
    private val characterViewQueryService: CharacterViewQueryService,
) {
    @Scheduled(fixedDelay = 1000)
    fun syncToMongoDB() {
        // Outbox → MongoDB 동기화
    }
}
```

**After:**
```kotlin
// Sync Worker 불필요
// DB 트랜잭션 내에서 직접 INSERT/UPDATE
@Service
class CharacterValuationService(
    private val repository: CharacterValuationViewRepository,
) {
    @Transactional
    fun saveView(view: CharacterValuationView) {
        // 단일 트랜잭션으로 처리
        repository.save(view.toEntity())
    }
}
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **단일 DB 운영** | MySQL + MongoDB → PostgreSQL |
| **Sync Worker 제거** | 복잡한 동기화 로직 제거 |
| **ACID 보장** | 트랜잭션 내 일관성 보장 |
| **쿼리 단순화** | SQL로 통합 쿼리 가능 |
| **백업 단순화** | 단일 DB 백업 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **JSONB 업데이트 비용** | 전체 JSONB 교체 vs 부분 업데이트 튜닝 |
| **쿼리 복잡성** | JSONB 연산자 학습, 뷰 활용 |
| **대용량 처리** | 파티셔닝, 아카이빙 전략 |

---

## 6. 성능 비교

### 조회 성능

| 작업 | MongoDB | PostgreSQL JSONB |
|------|---------|------------------|
| 단일 문서 조회 (ID) | ~2ms | ~1-2ms |
| 단일 문서 조회 (Index) | ~5ms | ~3-5ms |
| 배열 내부 검색 | ~10ms | ~5-10ms |
| Aggregation | ~20ms | ~15-30ms |

### 저장 성능

| 작업 | MongoDB | PostgreSQL JSONB |
|------|---------|------------------|
| Insert | ~2ms | ~2-3ms |
| Update (전체) | ~3ms | ~3-5ms |
| Update (부분) | ~2ms | ~5-10ms (JSONB 재구성) |

---

## 7. 마이그레이션 계획

### Phase 1: 스키마 생성

- [x] character_valuation_view 테이블 생성
- [x] GIN 인덱스 생성
- [ ] JPA Entity 구현
- [ ] Repository 구현

### Phase 2: 데이터 마이그레이션

- [ ] MongoDB → PostgreSQL 데이터 추출
- [ ] 데이터 변환 스크립트
- [ ] 일관성 검증

### Phase 3: 코드 마이그레이션

- [ ] CharacterViewQueryService → PostgreSQL 버전
- [ ] MongoDBSyncWorker 제거
- [ ] Outbox 테이블 정리

### Phase 4: 검증

- [ ] 단위 테스트
- [ ] 통합 테스트
- [ ] 성능 테스트

---

## 8. 롤백 전략

### 롤백 트리거

| 조건 | 조치 |
|------|------|
| 쿼리 지연 > 500ms p99 | MongoDB 복원 |
| JSONB 업데이트 실패 | MongoDB 복원 |

### 롤백 절차

1. PostgreSQL 쓰기 차단
2. MongoDBSyncWorker 재활성화
3. 기능 플래그로 트래픽 전환

---

## 9. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 조회 지연 p99 | < 100ms |
| JSONB 쿼리 지연 | < 50ms |
| 저장 지연 p99 | < 50ms |

### 모니터링 쿼리

```sql
-- JSONB 컬럼 크기 분석
SELECT user_ign,
       pg_column_size(presets) as presets_size,
       pg_column_size(*) as total_size
FROM character_valuation_view
ORDER BY presets_size DESC
LIMIT 10;

-- GIN 인덱스 사용 통계
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read
FROM pg_stat_user_indexes
WHERE indexname = 'idx_character_view_presets_gin';
```

---

## 10. 참고 자료

- [PostgreSQL JSONB Documentation](https://www.postgresql.org/docs/current/datatype-json.html)
- [PostgreSQL GIN Index](https://www.postgresql.org/docs/current/indexes-types.html#INDEXES-TYPES-GIN)
- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [MongoDB to PostgreSQL Migration Guide](https://www.cdata.com/kb/tech/mongodb-postgresql-migration.rst)

---

## 11. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-10 | ADR 초안 작성 | probabilistic-valuation-engine Team |
