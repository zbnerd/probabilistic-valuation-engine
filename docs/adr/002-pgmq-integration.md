# ADR-002: PGMQ 메시지 큐 통합

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 수락됨 (Accepted) |
| 결정일 | 2026-03-09 |
| 결정자 | MapleExpectation Team |
| 검토자 | Architecture Review Board |
| 관련 이슈 | #547, #548, #551, #552 |
| 선행 ADR | ADR-001 PostgreSQL 단일 DB 전략 |

---

## 1. 배경 (Context)

### 현재 아키텍처

MapleExpectation은 메시지 큐를 위해 **Redis Streams + Outbox 패턴**을 사용:

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  Service    │───>│  Outbox     │───>│   Redis     │
│             │    │  (MySQL)    │    │  Streams    │
└─────────────┘    └─────────────┘    └─────────────┘
                         │
                         ▼
                  ┌─────────────┐
                  │   Worker    │
                  │  (Polling)  │
                  └─────────────┘
```

### 문제점

| 문제 | 영향 |
|------|------|
| **이중 쓰기** | DB + Redis에 메시지 저장으로 인한 일관성 복잡성 |
| **Outbox 오버헤드** | 추가 테이블, 폴링, 정리 작업 필요 |
| **트랜잭션 경계** | DB 트랜잭션과 Redis 발행이 분리됨 |
| **운영 복잡성** | Redis Streams 모니터링, 백업 별도 필요 |

### 트래픽 패턴

| 큐 유형 | 메시지/초 | 지연 요구사항 | 소비 패턴 |
|---------|-----------|---------------|-----------|
| 계산 큐 | 10-50 | < 5초 | 경쟁 소비 |
| 좋아요 동기화 | 5-20 | < 10초 | 배치 병합 |
| 기부 알림 | 1-5 | < 1초 | 순차 처리 |

---

## 2. 결정 (Decision)

**Redis Streams + Outbox를 PGMQ로 대체한다.**

### 핵심 원칙

1. **Same-Transaction Publishing**
   - 메시지 발행이 DB 트랜잭션 내에서 수행
   - Outbox 테이블 불필요
   - ACID 보장

2. **PostgreSQL Native Queue**
   - PGMQ는 PostgreSQL 확장 프로그램
   - 별도 인프라 없이 큐 기능 제공
   - SKIP LOCKED로 경쟁 소비 지원

3. **메시지 영속성**
   - 큐 메시지가 DB에 저장
   - 장애 복구 시 메시지 손실 없음
   - 정확히 한 번 처리 보장

### 아키텍처 비교

```
Before (Redis Streams + Outbox):
┌───────────┐    ┌───────────┐    ┌───────────┐
│ Service   │───>│ Outbox    │───>│ Redis     │
│           │    │ (MySQL)   │    │ Streams   │
└───────────┘    └───────────┘    └───────────┘

After (PGMQ):
┌───────────┐    ┌───────────────────────────┐
│ Service   │───>│ PostgreSQL + PGMQ         │
│           │    │ (Same Transaction)        │
└───────────┘    └───────────────────────────┘
```

---

## 3. 대안 (Alternatives)

### A. 현상 유지 (Redis Streams + Outbox)

**장점:**
- 변경 비용 없음
- 검증된 패턴

**단점:**
- 이중 쓰기 복잡성 지속
- Outbox 관리 오버헤드
- Redis 의존성 지속

**평가:** ❌ 기술 부채 증가

### B. PGMQ (선택됨)

**장점:**
- 단일 트랜잭션으로 메시지 발행
- Outbox 패턴 불필요
- PostgreSQL 네이티브
- SKIP LOCKED로 안전한 경쟁 소비

**단점:**
- PGMQ 학습 곡선
- PostgreSQL에 큐 부하 집중

**평가:** ✅ 일관성 보장, 운영 단순화

### C. Apache Kafka

**장점:**
- 높은 처리량
- 이벤트 소싱 지원

**단점:**
- 인프라 복잡성
- 오버엔지니어링 (현재 트래픽 대비)
- 별도 클러스터 필요

**평가:** ⚠️ 과도한 복잡성

---

## 4. 기술적 구현 (Implementation)

### PGMQ 기본 작업

```sql
-- 메시지 발행 (트랜잭션 내)
SELECT pgmq.send('calculation_queue', '{"ocid":"abc123","preset_no":1}'::jsonb);

-- 메시지 소비 (SKIP LOCKED)
SELECT * FROM pgmq.read('calculation_queue', 10, 30);

-- 메시지 보관 (처리 완료)
SELECT pgmq.archive('calculation_queue', 123);

-- 메시지 삭제 (DLQ)
SELECT pgmq.delete('calculation_queue', 123);
```

### 큐 스키마 정의

#### 1. calculation_queue
```sql
-- 장비 기대값 계산 요청
SELECT pgmq.create('calculation_queue');

-- 메시지 형식
{
  "ocid": "abc123",
  "user_ign": "닉네임",
  "preset_no": 1,
  "force_recalculation": false,
  "requested_at": "2026-03-09T10:00:00Z"
}
```

#### 2. like_sync_queue
```sql
-- 좋아요 카운트 동기화
SELECT pgmq.create('like_sync_queue');

-- 메시지 형식
{
  "character_name": "닉네임",
  "delta": 1,
  "requested_at": "2026-03-09T10:00:00Z"
}
```

#### 3. donation_queue
```sql
-- 기부 이벤트 알림
SELECT pgmq.create('donation_queue');

-- 메시지 형식
{
  "donation_id": 12345,
  "user_id": 1,
  "amount": 1000,
  "message": "응원합니다",
  "requested_at": "2026-03-09T10:00:00Z"
}
```

### PGMQ 클라이언트 구조

```kotlin
// module-infra/src/main/kotlin/.../pgmq/PgmqClient.kt
@Component
class PgmqClient(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {
    fun send(queueName: String, message: Any): Long
    fun <T> read(queueName: String, batchSize: Int, visibilityTimeout: Int): List<PgmqMessage<T>>
    fun archive(queueName: String, messageId: Long): Boolean
    fun delete(queueName: String, messageId: Long): Boolean
}
```

### Worker 패턴

```kotlin
@Component
class CalculationWorker(
    private val pgmqClient: PgmqClient,
    private val calculationService: CalculationService
) {
    @Scheduled(fixedDelay = 1000)
    fun processCalculations() {
        val messages = pgmqClient.read<CalculationRequest>(
            "calculation_queue",
            batchSize = 10,
            visibilityTimeout = 30
        )

        messages.forEach { msg ->
            try {
                calculationService.calculate(msg.payload)
                pgmqClient.archive("calculation_queue", msg.messageId)
            } catch (e: Exception) {
                // DLQ로 이동 또는 재시도
                pgmqClient.delete("calculation_queue", msg.messageId)
            }
        }
    }
}
```

### Resilience4j 통합

```kotlin
// PGMQ 작업에 서킷브레이커 적용
@Bean
fun pgmqCircuitBreaker(): CircuitBreaker {
    return CircuitBreakerRegistry.ofDefaults()
        .circuitBreaker("pgmq", CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .build())
}
```

---

## 5. 트레이드오프 (Trade-offs)

### ✅ 장점

| 항목 | 설명 |
|------|------|
| **일관성 보장** | DB 트랜잭션 내 메시지 발행 |
| **Outbox 제거** | 복잡한 Outbox 패턴 불필요 |
| **운영 단순화** | Redis Streams 모니터링 불필요 |
| **장애 복구** | DB 백업에 큐 메시지 포함 |
| **경쟁 소비** | SKIP LOCKED로 안전한 분산 처리 |

### ⚠️ 단점

| 항목 | 완화 방안 |
|------|----------|
| **PGMQ 학습 곡선** | Redis Streams와 유사한 API |
| **PostgreSQL 부하** | 큐 길이 모니터링, 배치 처리 |
| **메시지 크기 제한** | 큰 데이터는 참조 ID만 저장 |

---

## 6. 마이그레이션 계획

### Phase 2-1: PGMQ 인프라 구축

- [x] Docker Compose에 PGMQ 추가
- [x] Init 스크립트로 큐 생성
- [ ] PGMQ 클라이언트 구현
- [ ] 단위 테스트 작성

### Phase 2-2: 프로듀서 마이그레이션

- [ ] EventPublisher → PgmqProducer 교체
- [ ] Outbox 테이블 제거
- [ ] 기존 Redis Streams 비활성화

### Phase 2-3: 컨슈머 마이그레이션

- [ ] Worker를 PGMQ 기반으로 변경
- [ ] SKIP LOCKED 테스트
- [ ] DLQ 처리 로직 구현

### Phase 2-4: 검증

- [ ] 부하 테스트
- [ ] 장애 복구 테스트
- [ ] 모니터링 대시보드 구축

---

## 7. 모니터링 & 검증

### 성공 지표

| 지표 | 목표 |
|------|------|
| 메시지 발행 지연 | < 10ms |
| 메시지 처리 지연 (p99) | < 5초 |
| 큐 길이 | < 1,000 |
| 메시지 손실률 | 0% |

### 모니터링 쿼리

```sql
-- 큐 길이 조회
SELECT queue_name, queue_length
FROM pgmq.meta;

-- 메시지 처리 통계
SELECT queue_name, count(*) as pending
FROM pgmq.q_calculation_queue
WHERE vt < NOW()
GROUP BY queue_name;
```

### Grafana 대시보드

- 큐 길이 트렌드
- 메시지 처리 속도
- 에러율
- Worker 상태

---

## 8. 롤백 전략

### 롤백 트리거

| 조건 | 조치 |
|------|------|
| 메시지 손실 발생 | 즉시 Redis Streams 복원 |
| 처리 지연 > 30초 | 원인 분석 후 결정 |
| PostgreSQL 과부하 | Worker 스케일 다운 |

### 롤백 절차

1. PGMQ Worker 중지
2. Redis Streams 구독 재개
3. Outbox 테이블 복원
4. 기능 플래그로 트래픽 전환

---

## 9. 참고 자료

- [PGMQ 공식 문서](https://github.com/tembo-io/pgmq)
- [PostgreSQL SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [ADR-001 PostgreSQL 단일 DB 전략](001-postgresql-single-db-strategy.md)
- [Transactional Outbox 패턴](https://microservices.io/patterns/data/transactional-outbox.html)

---

## 10. 변경 이력

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-03-09 | ADR 초안 작성 | MapleExpectation Team |
| 2026-03-09 | 상태를 "수락됨"으로 변경 | MapleExpectation Team |
