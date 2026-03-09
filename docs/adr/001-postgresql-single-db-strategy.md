# ADR-001: PostgreSQL 단일 DB 전략

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-09 |
| 결정자 | Development Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #551 |

---

## 1. 배경 (Context)

### 현재 아키텍처

MapleExpectation 프로젝트는 현재 3개의 데이터베이스를 사용:

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

**MySQL + MongoDB + Redis를 PostgreSQL 단일 DB로 통합한다.**

### 핵심 원칙

1. **PostgreSQL로 모든 기능 통합**
   - MySQL → PostgreSQL (jsonb, 고급 인덱싱)
   - MongoDB → PostgreSQL jsonb
   - Redis → PostgreSQL (PGMQ, Advisory Lock, UNLOGGED TABLE)

2. **단계적 마이그레이션**
   - Phase 0~9로 나누어 점진적 전환
   - 각 Phase마다 검증 후 진행

3. **필요시 Redis 재도입**
   - 트래픽이 2,000+ QPS 도달 시 Redis 캐시 재도입 검토
   - 명확한 스케일아웃 트리거 조건 정의

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

### B. PostgreSQL + Redis 하이브리드

**장점:**
- 캐시/세션은 Redis 유지
- DB 복잡도 감소

**단점:**
- 여전히 2개 DB 운영
- Redis 장애 시 영향 범위 큼

**평가:** ⚠️ 부분 개선, 근본적 해결 안 됨

### C. PostgreSQL 단일 DB (선택됨)

**장점:**
- 운영 단순화
- 리소스 효율화
- ACID 트랜잭션 보장
- jsonb로 비정형 데이터 처리
- PGMQ로 메시지 큐 대체
- Advisory Lock로 분산 락 대체

**단점:**
- 초기 마이그레이션 비용
- Redis만큼 빠른 캐시 아님 (Caffeine으로 보완)

**평가:** ✅ 장기적 이익 > 단기 비용

---

## 4. 기술적 구현 (Implementation)

### PostgreSQL 기능 매핑

| 기존 기능 | PostgreSQL 대체 | 구현 방식 |
|----------|----------------|----------|
| MySQL 테이블 | PostgreSQL 테이블 | JPA Entity, jsonb 컬럼 |
| MongoDB 문서 | PostgreSQL jsonb | `@Column(columnDefinition = "jsonb")` |
| Redis 캐시 | Caffeine L1 + PostgreSQL | TieredCache 패턴 유지 |
| Redis 세션 | PostgreSQL 테이블 | 세션 TTL 관리 |
| Redis 버퍼 | UNLOGGED TABLE | 크래시 시 손실 허용 |
| Redis Streams | PGMQ Extension | 메시지 큐 |
| Redisson 락 | Advisory Lock | `pg_advisory_lock()` |

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
-- Equipment Buffer (Redis 대체)
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
| **운영 단순화** | 단일 DB 모니터링, 백업, 장애 대응 |
| **비용 절감** | DB 인스턴스 3개 → 1개 |
| **일관성 보장** | ACID 트랜잭션으로 데이터 무결성 |
| **개발 생산성** | 단일 스택 학습, JPA 일관성 |
| **인프라 호환성** | PostgreSQL은 범용 RDBMS |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **마이그레이션 비용** | 단계적 전환, 충분한 테스트 |
| **Redis만큼 빠르지 않음** | Caffeine L1 캐시로 보완 |
| **수평 확장 제약** | 읽기 복제본, 파티셔닝 고려 |
| **PGMQ 학습 곡선** | Redis Streams와 유사한 API |

---

## 6. 스케일아웃 트리거 조건

PostgreSQL 단일 DB에서 Redis 재도입을 고려하는 조건:

| 지표 | 임계값 | 조치 |
|------|--------|------|
| QPS | > 2,000 | Redis 캐시 도입 검토 |
| 응답 시간 p99 | > 200ms | 읽기 복제본 추가 |
| DB CPU | > 80% | 커넥션 풀 튜닝, 쿼리 최적화 |
| 동시 연결 | > 500 | PgBouncer 도입 |

---

## 7. 마이그레이션 계획

### Phase 개요

```
Phase 0: Foundation (2 Issues)
  ├── P0-01: Project Setup + Kotlin Conversion
  └── P0-02: PostgreSQL + PGMQ Docker Compose

Phase 1: Core Data Layer (3 Issues)
  ├── P1-01: ADR-001 PostgreSQL Single DB Strategy
  ├── P1-02: Domain Entities (PostgreSQL + jsonb)
  └── P1-03: Repository Layer + Ports

Phase 2-3: Message Queue & Locking
Phase 4-5: Data Pipeline & API
Phase 6: Features (Like, Donation, Auth)
Phase 7-8: Testing & Performance
Phase 9: Deployment
```

### 롤백 전략

1. **`develop` 브랜치 보존**: 언제든 롤백 가능
2. **기능 플래그**: PostgreSQL/Redis 전환 가능한 구조
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
- Advisory Lock 대기 시간

---

## 9. 참고 자료

- [PostgreSQL 16 Documentation](https://www.postgresql.org/docs/16/)
- [PGMQ GitHub](https://github.com/tembo-io/pgmq)
- [PostgreSQL Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- [PostgreSQL jsonb](https://www.postgresql.org/docs/current/datatype-json.html)
- [Design Document](../plans/2026-03-06-postgresql-migration-design.md)

---

## 10. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-09 | ADR 초안 작성 | Development Team |
| 2026-03-09 | 상태를 "수락됨"으로 변경 | Development Team |
