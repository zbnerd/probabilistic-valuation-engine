# ADR-314: PostgreSQL 단일 DB 전략

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-09 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551 |

---

## 1. 배경 (Context)

### 현재 아키텍처

probabilistic-valuation-engine 프로젝트는 현재 3개의 데이터베이스를 사용:

1. **MySQL 8.0**: 영구 데이터 저장 (사용자, 장비, 계산 결과)
2. **MongoDB 7.0**: 비정형 장비 데이터 (V5 CQRS Read Side)
3. **Redis 7.0**: 분산 캐시, 세션, 버퍼, 메시지 큐

### 문제점

| 문제 | 영향 |
|------|------|
| **복잡한 운영** | 3개 DB 인스턴스 모니터링, 백업, 장애 대응 필요 |
| **리소스 낭비** | 저사양 인프라(t3.small)에서 과도한 메모리 사용 |
| **데이터 일관성** | 분산 트랜잭션 없이 3개 DB 간 일관성 유지 어려움 |
| **확장성 제약** | Redis Cluster, MongoDB Sharding은 과도한 복잡성 유발 |
| **개발 생산성** | 3개 DB 스키마, ORM, 쿼리 최적화 학습 비용 |

### 트래픽 패턴 분석

| 시나리오 | 동시 사용자 | QPS | 현재 대응 |
|----------|-------------|-----|----------|
| 일반 | 1,000 | 0.5 | 단일 인스턴스 충분 |
| 패치데이 | 50,000 | 500 | Redis 캐시 + 커넥션 풀 |
| 버럴 | 200,000+ | 2,000+ | 확장 트리거 필요 |

---

## 2. 결정 (Decision)

**MySQL + MongoDB를 PostgreSQL 단일 DB로 통합한다. (Redis 캐시는 유지)**

### 핵심 원칙

1. **PostgreSQL로 데이터 계층 통합**
   - MySQL → PostgreSQL (jsonb, 고급 인덱싱)
   - MongoDB → PostgreSQL jsonb

2. **Redis는 캐시 전용으로 유지**
   - 분산 캐시 (Caffeine L1 + Redis L2)
   - 분산 락 (Redisson)
   - 세션 저장소
   - 캐시 무효화 (Pub/Sub)

3. **메시지 큐는 PGMQ로 마이그레이션**
   - Redis Streams → PGMQ
   - Outbox 패턴 → PGMQ 기반

4. **단계적 마이그레이션**
   - Phase 0~9로 나누어 점진적 전환
   - 각 Phase마다 검증 후 진행

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (MySQL + MongoDB + Redis)

**장점:**
- 변경 비용 없음
- 검증된 아키텍처

**단점:**
- 운영 복잡도 지속
- 리소스 낭비
- 확장성 제약

**평가:** ❌ 기술 부채 증가, 운영 비용 증가

### B. PostgreSQL + Redis 하이브리드 (선택됨)

**장점:**
- 캐시/세션/락은 Redis 유지
- DB 복잡도 감소 (3개 → 2개)
- 검증된 Redis 캐시 성능 유지

**단점:**
- 여전히 2개 DB 운영
- Redis 장애 시 영향 범위 큼

**평가:** ✅ 점진적 개선, 리스크 최소화

### C. PostgreSQL 완전 단일 DB

**장점:**
- 운영 최대 단순화
- 리소스 최대 효율화

**단점:**
- Redis만큼 빠른 캐시 아님
- 분산 락 성능 저하 가능
- 세션 관리 복잡도 증가

**평가:** ⚠️ 이상적이나 현실적 제약 존재

---

## 4. 기술적 구현 (Implementation)

### PostgreSQL 기능 매핑

| 기존 기능 | PostgreSQL 대체 | 구현 방식 |
|----------|----------------|----------|
| MySQL 테이블 | PostgreSQL 테이블 | JPA Entity, jsonb 컬럼 |
| MongoDB 문서 | PostgreSQL jsonb | `@Column(columnDefinition = "jsonb")` |
| Redis Streams | PGMQ Extension | 메시지 큐 |
| Redis Buffer | UNLOGGED TABLE | 크래시 시 손실 허용 |

### Redis 유지 기능

| 기능 | Redis 활용 | 이유 |
|------|----------|------|
| 분산 캐시 | TieredCache (Caffeine L1 + Redis L2) | 빠른 응답 시간 |
| 분산 락 | Redisson | 검증된 성능 |
| 세션 저장소 | Redis Session | TTL 관리 용이 |
| 캐시 무효화 | Redis Pub/Sub | 실시간 전파 |

### PGMQ 큐 설계

```sql
-- V4 Buffer Queue
SELECT pgmq.create('v4_buffer_queue');

-- V5 Event Queue
SELECT pgmq.create('v5_event_queue');

-- Donation Outbox Queue
SELECT pgmq.create('donation_outbox_queue');
```

### UNLOGGED TABLE 설계

```sql
-- Equipment Buffer (Redis Buffer 대체)
CREATE UNLOGGED TABLE equipment_expectation_buffer (
    character_name VARCHAR(50) PRIMARY KEY,
    expectation_value BIGINT NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **운영 단순화** | DB 인스턴스 3개 → 2개 (PostgreSQL + Redis) |
| **비용 절감** | MySQL, MongoDB 인스턴스 제거 |
| **일관성 보장** | ACID 트랜잭션으로 데이터 무결성 |
| **개발 생산성** | 단일 RDBMS 스택 학습 |
| **인프라 호환성** | PostgreSQL은 범용 RDBMS |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **마이그레이션 비용** | 단계적 전환, 충분한 테스트 |
| **PGMQ 학습 곡선** | Redis Streams와 유사한 API |
| **Redis 유지 필요** | 캐시/락은 검증된 Redis 사용 |

---

## 6. 스케일아웃 트리거 조건

PostgreSQL + Redis에서 추가 조치를 고려하는 조건:

| 지표 | 임계값 | 조치 |
|------|--------|------|
| QPS | > 2,000 | Redis 캐시 확장 |
| 응답 시간 p99 | > 200ms | 읽기 복제본 추가 |
| DB CPU | > 80% | 커넥션 풀 튜닝, 쿼리 최적화 |
| 동시 연결 | > 500 | PgBouncer 도입 |

---

## 7. 마이그레이션 계획

### Phase 개요

```
Phase 0: Foundation (2 Issues) ✅ In Progress
  ├── P0-01: Project Setup + Kotlin Conversion ✅
  └── P0-02: PostgreSQL + PGMQ Docker Compose ✅

Phase 1: Core Data Layer (3 Issues)
  ├── P1-01: ADR-001 PostgreSQL Single DB Strategy ✅
  ├── P1-02: Domain Entities (PostgreSQL + jsonb)
  └── P1-03: Repository Layer + Ports

Phase 2: Message Queue (2 Issues)
  ├── P2-01: ADR-002 PGMQ Integration
  └── P2-02: PGMQ Producers & Consumers

Phase 3-4: Data Pipeline & API
Phase 5-6: Features & Testing
Phase 7-8: Performance & Deployment
```

### 롤백 전략

1. **`develop` 브랜치 보존**: 언제든 롤백 가능
2. **기능 플래그**: PostgreSQL/MySQL 전환 가능한 구조
3. **데이터 마이그레이션 없음**: 프로덕션 데이터 이관 없음 (신규 시작)

---

## 8. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 빌드 시간 | < 5분 |
| 테스트 통과율 | 100% |
| 로컬 시작 시간 | < 30초 |
| API 응답 시간 p99 | < 200ms (500 QPS) |

### 모니터링 대시보드

- PostgreSQL 커넥션 풀 상태
- PGMQ 큐 길이/지연 시간
- UNLOGGED TABLE 크기
- Redis 캐시 적중률

---

## 9. 참고 자료

- [PostgreSQL 16 Documentation](https://www.postgresql.org/docs/16/)
- [PGMQ GitHub](https://github.com/tembo-io/pgmq)
- [PostgreSQL Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [PostgreSQL jsonb](https://www.postgresql.org/docs/current/datatype-json.html)
- [Design Document](../plans/2026-03-06-postgresql-migration-design.md)
- [Deletion Targets](../migration/deletion-targets.md)

---

## 10. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-09 | ADR 초안 작성 | probabilistic-valuation-engine Team |
| 2026-03-09 | 상태를 "수락됨"으로 변경 | probabilistic-valuation-engine Team |
