# Legacy Storage Migration Analysis (PostgreSQL 통합)

> 전체 레거시 스토리지(MySQL, Redis, MongoDB) 사용처 분석 및 PostgreSQL 통합 마이그레이션 대상 식별

## Executive Summary

| 스토리지 | 사용 파일 수 | 마이그레이션 상태 | 비고 |
|---------|-------------|-----------------|------|
| **Redis** | 148개 | ⚠️ 진행 중 | 분산락, 캐시, 큐, 좋아요 버퍼 |
| **MongoDB** | 21개 | ⚠️ V5 전용 | CQRS Read Model (v5.enabled=true) |
| **MySQL** | 35개 | ✅ 유지 | 메인 RDBMS (PostgreSQL로 마이그레이션 완료) |

---

## 1. Redis 사용처 분석 (148개 파일)

### 1.1 분산 락 (Distributed Lock)

**현재 상태:** Redis → PostgreSQL Advisory Lock 마이그레이션 진행 중

| 파일 | 역할 | 마이그레이션 상태 |
|------|------|-----------------|
| `RedisDistributedLockStrategy.kt` | Redis 기반 분산 락 | ⚠️ PostgresAdvisoryLockStrategy로 교체 필요 |
| `ResilientLockStrategy.kt` | 락 폴백 래퍼 | ✅ 유지 (내부적으로 전략 교체) |
| `LockStrategyConfiguration.kt` | 락 전략 선택 | ✅ `lock.impl=postgres` 설정 필요 |
| `PostgresAdvisoryLockStrategy.kt` | PostgreSQL Advisory Lock | ✅ 이미 구현됨 |
| `PostgresLockStrategy.kt` | PostgreSQL 락 (기존) | ✅ 이미 구현됨 |

**설정 변경:**
```yaml
# application.yml
lock:
  impl: postgres  # redis → postgres
```

### 1.2 캐시 (TieredCache L2)

**현재 상태:** Redis L2 → PostgreSQL L2 마이그레이션 진행 중

| 파일 | 역할 | 마이그레이션 상태 |
|------|------|-----------------|
| `TieredCache.kt` | L1(Caffeine) + L2(Redis) 계층형 캐시 | ⚠️ L2를 PostgreSQL로 교체 |
| `PostgresL2CacheFactory.kt` | PostgreSQL 기반 L2 캐시 | ✅ 이미 구현됨 |
| `FallbackCacheRepository.kt` | PostgreSQL 캐시 폴백 | ✅ 이미 구현됨 |
| `CaffeineOnlyCacheManager.kt` | L1 전용 모드 | ✅ 대안으로 사용 가능 |

**설정 변경:**
```yaml
cache:
  l2:
    enabled: true
    impl: postgres  # redis → postgres
```

### 1.3 좋아요 버퍼 (Like Buffer)

**현재 상태:** Redis 기반, PostgreSQL 마이그레이션 필요

| 파일 | 역할 | 비고 |
|------|------|------|
| `RedisLikeBufferStorage.kt` | 좋아요 카운트 버퍼 | ZSET 기반 |
| `RedisLikeRelationBuffer.kt` | 좋아요 관계 버퍼 | SET 기반 |
| `HybridLikeRelationBuffer.kt` | 하이브리드 버퍼 | In-Memory + Redis |
| `LikeSyncScheduler.kt` | 3계층 동기화 스케줄러 | L1→L2→L3 |
| `PartitionedFlushStrategy.kt` | 파티션별 Flush | Redis 모드 전용 |
| `RedisLikeEventPublisher/Subscriber.kt` | 실시간 동기화 Pub/Sub | RTopic 기반 |

**마이그레이션 방안:**
- Redis ZSET → PostgreSQL 테이블 (`like_count_buffer`)
- Redis SET → PostgreSQL 테이블 (`like_relation_buffer`)
- Redis Pub/Sub → PostgreSQL NOTIFY/LISTEN

### 1.4 메시지 큐 (Message Queue)

**현재 상태:** Redis → PGMQ 마이그레이션 진행 중

| 파일 | 역할 | 마이그레이션 상태 |
|------|------|-----------------|
| `RedisMessageQueue.kt` | Redis 기반 메시지 큐 | ⚠️ PGMQ로 교체 |
| `RedisMessageTopic.kt` | Redis Pub/Sub 토픽 | ⚠️ PostgreSQL NOTIFY로 교체 |
| `RedisStreamPublisher/Consumer.kt` | Redis Streams | ⚠️ PGMQ로 교체 |
| `PgmqClient.kt` | PGMQ 클라이언트 | ✅ 이미 구현됨 |
| `PgmqWorker.kt` | PGMQ 워커 추상 클래스 | ✅ 이미 구현됨 |

### 1.5 Rate Limiter

**현재 상태:** Redis (Bucket4j) → PostgreSQL 마이그레이션 필요

| 파일 | 역할 | 비고 |
|------|------|------|
| `PostgresRateLimiter.kt` | PostgreSQL 기반 Rate Limiter | ✅ 이미 구현됨 |
| `AbstractBucket4jRateLimiter.kt` | Bucket4j 추상 클래스 | ⚠️ 제거 대상 |
| `IpBasedRateLimiter.kt` | IP 기반 제한 | Bucket4j 사용 |
| `UserBasedRateLimiter.kt` | 사용자 기반 제한 | Bucket4j 사용 |
| `Bucket4jConfig.kt` | Bucket4j 설정 | ⚠️ 제거 대상 |

### 1.6 세션/토큰 저장소

| 파일 | 역할 | 비고 |
|------|------|------|
| `RedisSessionRepositoryImpl.kt` | 세션 저장소 | Redis 기반 |
| `RedisRefreshTokenRepositoryImpl.kt` | Refresh 토큰 저장소 | Redis 기반 |

**마이그레이션 방안:** PostgreSQL 테이블로 이관

### 1.7 캐시 무효화 (Pub/Sub)

| 파일 | 역할 | 비고 |
|------|------|------|
| `RedisCacheInvalidationPublisher.kt` | 캐시 무효화 발행 | RTopic 기반 |
| `RedisCacheInvalidationSubscriber.kt` | 캐시 무효화 구독 | RTopic 기반 |
| `PostgresNotifyPublisher.kt` | PostgreSQL NOTIFY 발행 | ✅ 이미 구현됨 |
| `PostgresNotifySubscriber.kt` | PostgreSQL LISTEN 구독 | ✅ 이미 구현됨 |

### 1.8 기타 Redis 사용처

| 파일 | 역할 | 비고 |
|------|------|------|
| `RedissonConfig.kt` | Redisson 클라이언트 설정 | Sentinel/Single 모드 |
| `RedisMetricsCollector.kt` | Redis 메트릭 수집 | 모니터링 |
| `AlertThrottler.kt` | 알림 스로틀링 | Redis 기반 |
| `DistributedSingleFlightExecutor.kt` | 분산 SingleFlight | Redis 기반 |

---

## 2. MongoDB 사용처 분석 (21개 파일)

### 2.1 CQRS Read Model (V5 전용)

**활성화 조건:** `v5.enabled=true`

| 파일 | 역할 | 비고 |
|------|------|------|
| `MongoDBConfig.kt` | MongoDB 설정 | @ConditionalOnProperty("v5.enabled") |
| `MongoDBHealthIndicator.kt` | 헬스 체크 | |
| `CharacterValuationView.kt` | 읽기 전용 뷰 Document | TTL 24시간 |
| `CharacterValuationRepository.kt` | MongoDB Repository | |
| `CharacterViewQueryService.kt` | 조회 서비스 | 낙관적 잠금 포함 |
| `BatchCharacterViewService.kt` | 배치 처리 서비스 | |
| `HealthCheck.kt` | 헬스 체크 유틸리티 | |

### 2.2 V5 컨트롤러

| 파일 | 역할 | 비고 |
|------|------|------|
| `GameCharacterControllerV5.kt` | V5 API 컨트롤러 | MongoDB 조회 사용 |

### 2.3 마이그레이션 방안

**옵션 1: PostgreSQL Materialized View**
```sql
CREATE MATERIALIZED VIEW character_valuation_view AS
SELECT * FROM equipment_expectation_summary;

CREATE INDEX idx_user_ign ON character_valuation_view(user_ign);
```

**옵션 2: PostgreSQL 테이블 + 트리거**
```sql
CREATE TABLE character_valuation_view (
    id SERIAL PRIMARY KEY,
    user_ign VARCHAR(50) UNIQUE NOT NULL,
    character_ocid VARCHAR(100),
    -- ... 기타 필드
    calculated_at TIMESTAMPTZ DEFAULT NOW(),
    version BIGINT DEFAULT 1
);

-- 동기화 트리거
CREATE TRIGGER sync_valuation_view
AFTER INSERT OR UPDATE ON equipment_expectation_summary
FOR EACH ROW EXECUTE FUNCTION sync_to_valuation_view();
```

---

## 3. MySQL 사용처 분석 (35개 파일)

### 3.1 JPA Repository

**현재 상태:** PostgreSQL로 마이그레이션 완료 (드라이버만 변경)

| 파일 | 역할 | 비고 |
|------|------|------|
| `GameCharacterRepositoryImpl.kt` | 캐릭터 저장소 | ✅ PostgreSQL 사용 중 |
| `CharacterEquipmentRepositoryImpl.kt` | 장비 저장소 | ✅ PostgreSQL 사용 중 |
| `CharacterLikeRepositoryImpl.kt` | 좋아요 저장소 | ✅ PostgreSQL 사용 중 |
| `EquipmentExpectationSummaryRepository.kt` | Expectation 저장소 | ✅ PostgreSQL 사용 중 |
| `EventOutboxRepository.kt` | 이벤트 아웃박스 | ✅ PostgreSQL 사용 중 |
| `NexonApiOutboxRepository.kt` | Nexon API 아웃박스 | ✅ PostgreSQL 사용 중 |
| `DonationOutboxRepository.kt` | 기부 아웃박스 | ✅ PostgreSQL 사용 중 |

### 3.2 설정

| 파일 | 역할 | 비고 |
|------|------|------|
| `application.yml` | `driver-class-name: com.mysql.cj.jdbc.Driver` | ⚠️ PostgreSQL 드라이버로 변경 필요 |
| `LockHikariConfig.kt` | 락용 DataSource | MySQL → PostgreSQL |

---

## 4. 마이그레이션 우선순위

### P0: 즉시 마이그레이션 (Scale-out 필수)

1. **분산 락** - `lock.impl=postgres` 설정만으로 전환 가능
2. **L2 캐시** - `cache.l2.impl=postgres` 설정만으로 전환 가능
3. **좋아요 버퍼** - PostgreSQL 테이블 기반으로 재구현 필요

### P1: 단기 마이그레이션 (안정성)

4. **Rate Limiter** - `PostgresRateLimiter`로 전환
5. **세션/토큰 저장소** - PostgreSQL 테이블로 이관
6. **캐시 무효화 Pub/Sub** - PostgreSQL NOTIFY/LISTEN 사용

### P2: 중기 마이그레이션 (단순화)

7. **메시지 큐** - PGMQ로 완전 전환 (Redis Queue 제거)
8. **MongoDB CQRS** - PostgreSQL Materialized View 또는 테이블로 대체

---

## 5. 설정 변경 가이드

### 5.1 application.yml 변경

```yaml
# 드라이버 변경
spring:
  datasource:
    driver-class-name: org.postgresql.Driver  # com.mysql.cj.jdbc.Driver → 변경

# 분산 락
lock:
  impl: postgres  # redis → postgres

# L2 캐시
cache:
  l2:
    enabled: true
    impl: postgres  # redis → postgres

# V5 비활성화 (MongoDB 사용 안 함)
v5:
  enabled: false  # MongoDB 의존성 제거

# 좋아요 버퍼
app:
  buffer:
    redis:
      enabled: false  # In-Memory 또는 PostgreSQL 모드 사용
```

### 5.2 의존성 변경 (build.gradle.kts)

```kotlin
// 제거 대상
// implementation("org.redisson:redisson-spring-boot-starter:...")
// implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
// implementation("com.github.vladimir-bukhtoyarov:bucket4j-redis:...")

// 유지
implementation("org.postgresql:postgresql:42.7.2")
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

---

## 6. 마이그레이션 체크리스트

### Phase 1: 설정 전환 (즉시)

- [ ] `lock.impl=postgres` 설정
- [ ] `cache.l2.impl=postgres` 설정
- [ ] `v5.enabled=false` 설정
- [ ] MySQL 드라이버 → PostgreSQL 드라이버

### Phase 2: 코드 정리 (단기)

- [ ] RedisDistributedLockStrategy 제거
- [ ] Bucket4j 관련 코드 제거
- [ ] RedisSessionRepository/RefreshTokenRepository 제거
- [ ] MongoDB 관련 코드 제거

### Phase 3: 기능 재구현 (중기)

- [ ] 좋아요 버퍼 PostgreSQL 구현
- [ ] 세션/토큰 PostgreSQL 테이블 생성
- [ ] CQRS Read Model PostgreSQL Materialized View

### Phase 4: 의존성 정리 (장기)

- [ ] Redisson 의존성 제거
- [ ] MongoDB 의존성 제거
- [ ] Bucket4j-Redis 의존성 제거

---

## 참고 문서

- [ADR-002: PGMQ Integration](../01_ADR/ADR-002-pgmq-integration.md)
- [ADR-007: PostgreSQL MongoDB Replacement](../01_ADR/ADR-007-postgresql-mongodb-replacement.md)
- [PostgreSQL Migration Phase 2 Plan](../plan/postgresql-migration-phase2-plan.md)
