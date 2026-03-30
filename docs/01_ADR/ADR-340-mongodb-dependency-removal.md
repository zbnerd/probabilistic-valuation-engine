# ADR-023: MongoDB Dependency Removal

## Status

**ACCEPTED** (2026-03-11)

## Context

Issue #590에서 MongoDB 의존성을 완전히 제거하고 PostgreSQL로 마이그레이션해야 합니다.

### Background

V5 CQRS 아키텍처에서 MongoDB를 Read Side로 사용해왔으나, PostgreSQL 통합 전략(ADR-001)에 따라 단일 데이터베이스로 통합이 필요합니다.

### 기존 MongoDB 구현

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/
├── CharacterValuationView.kt          # MongoDB Document
├── CharacterValuationRepository.kt    # Spring Data MongoDB Repository
├── CharacterViewQueryService.kt       # Query Service
├── BatchCharacterViewService.kt       # Batch Service (Stage & Swap)
├── MongoDBConfig.kt                   # MongoDB Configuration
├── MongoDBHealthIndicator.kt          # Health Check
└── HealthCheck.kt                     # Placeholder
```

### PostgreSQL 대체 구현 (이미 존재)

```
module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/
├── entity/CharacterValuationViewEntity.kt    # JPA Entity (JSONB)
├── repository/CharacterValuationViewJpaRepository.kt
└── CharacterViewQueryServicePostgres.kt      # PostgreSQL Query Service
```

## Decision

MongoDB 의존성을 완전히 제거하고 기존 PostgreSQL 구현으로 전환합니다.

### Migration Changes

1. **ViewTransformer.java**
   - `CharacterValuationView` (MongoDB) → `CharacterValuationViewEntity` (PostgreSQL)

2. **CharacterViewQueryPortAdapter.java**
   - `CharacterViewQueryService` (MongoDB) → `CharacterViewQueryServicePostgres`

3. **GameCharacterControllerV5.kt**
   - `CharacterValuationView` → `CharacterValuationViewEntity`

4. **CharacterViewMapper.kt**
   - MongoDB View → PostgreSQL Entity 매핑으로 변경

5. **삭제된 파일**
   - `module-infra/.../mongodb/` 디렉토리 전체 (7개 파일)
   - `module-infra/src/test/.../mongodb/` 테스트 디렉토리 (3개 파일)
   - `BatchOptimisticLockListener.kt` (MongoDB-specific)

6. **build.gradle**
   - `testcontainers.mongodb` 의존성 제거

### PostgreSQL Entity 구조

```kotlin
@Entity
@Table(name = "character_valuation_views")
class CharacterValuationViewEntity(
    @Id var id: Long? = null,
    var userIgn: String,
    var messageId: String?,
    var characterOcid: String?,
    var characterClass: String?,
    var characterLevel: Int?,
    var calculatedAt: Instant?,
    var lastApiSyncAt: Instant?,
    var version: Long?,
    var lastAppliedVersion: Long?,
    var totalExpectedCost: Long?,
    var maxPresetNo: Int?,
    @Column(columnDefinition = "jsonb")
    var presets: List<PresetView>?,
    var fromCache: Boolean?
)
```

### Conditional Loading

기존 MongoDB 서비스들은 `@ConditionalOnBean(MongoTemplate::class)`로 조건부 로딩되었습니다.
PostgreSQL 서비스는 `@ConditionalOnProperty(name = ["app.v5.enabled"])`로 전환됩니다.

## Consequences

### Positive

- 단일 데이터베이스(PostgreSQL)로 인프라 단순화
- 운영 복잡도 감소 (MongoDB 클러스터 관리 불필요)
- JSONB를 통한 유연한 데이터 구조 유지
- 트랜잭션 일관성 보장 (ACID)

### Negative

- MongoDB의 문서 지향 쿼리 기능 손실 (JSONB로 대체)
- 배치 Stage & Swap 패턴 미지원 (PostgreSQL은 컬렉션 rename 불가)

### Mitigation

- JSONB 인덱싱으로 쿼리 성능 확보
- 배치 작업은 PostgreSQL 트랜잭션 내에서 처리

## Related

- Issue #590: MongoDB 의존성 완전 제거
- ADR-001: PostgreSQL Single DB Strategy
- ADR-007: PostgreSQL MongoDB Replacement
- ADR-022: Redis Dependency Removal

## History

- 2026-03-11: Initial proposal and acceptance
