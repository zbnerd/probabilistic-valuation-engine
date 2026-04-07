# 9장: 대이주 — Redis Outbox에서 PGMQ로

> ADR-316: PGMQ 메시지 큐 통합
>
> "42개의 파일이 삭제되었다. 그리고 시스템은 더 단순해지고 더 강해졌다."

---

## 배경: 이중 쓰기의 고통

좋아요, 캐릭터 계산, 이벤트 발행 — 시스템의 모든 쓰기 작업은 Outbox 패턴을 사용했다.

```
Before (Redis Streams + Outbox):

Service → Outbox Table (MySQL) → Redis Streams → Consumer
              ↑                        ↑
           트랜잭션 1              트랜잭션 2 (별도)
```

**문제는 "별도"였다.**

### 이중 쓰기의 딜레마

```java
// Anti-Pattern: 두 번의 쓰기
@Transactional
public void processLike(LikeRequest request) {
    // 1. DB에 데이터 저장
    likeRepository.save(request);

    // 2. Outbox에 이벤트 저장 (같은 트랜잭션)
    outboxRepository.save(new OutboxEvent("like", request));

    // 3. Redis Streams에 발행 (별도 트랜잭션!)
    redisTemplate.convertAndSend("like-events", request);
}
```

1번과 2번은 같은 트랜잭션이다. 둘 다 성공하거나 둘 다 실패한다. 하지만 3번은 별도다. 1, 2가 성공하고 3번이 실패하면?

- DB에는 데이터가 있다
- Redis에는 이벤트가 없다
- Consumer는 이벤트를 모른다
- → 데이터는 있지만 처리되지 않은 상태

반대로 1, 2가 실패하고 3번이 실행되면?

- DB에는 데이터가 없다
- Redis에는 이벤트가 있다
- Consumer가 존재하지 않는 데이터를 처리하려 한다
- → NPE 또는 데이터 오염

---

## 분석: Outbox의 3가지 골칫거리

### 골칫거리 1: 스케줄러 3개

Outbox를 처리하기 위해 3개의 스케줄러가 필요했다.

```
Outbox Scheduler 1: like_outbox 테이블 폴링 (2-3 connections 항시 점유)
Outbox Scheduler 2: calculation_outbox 테이블 폴링 (2-3 connections)
Outbox Scheduler 3: event_outbox 테이블 폴링 (2-3 connections)
─────────────────────────────────────
총 6-9 connections이 항상 점유 (25개 중 6-9개 = 24-36%)
```

3장에서 다룬 커넥션 풀 문제의 원인 중 하나였다.

### 골칫거리 2: 42개의 파일

Outbox 패턴 구현을 위해 42개의 파일이 필요했다.

```
OutboxEvent.java
OutboxRepository.java
OutboxScheduler.java (× 3)
OutboxProcessor.java
OutboxRetryPolicy.java
OutboxDeduplicator.java
RedisStreamPublisher.java
RedisStreamConsumer.java
... (총 42개)
```

42개의 파일. 모두 테스트해야 하고, 모두 유지보수해야 하고, 모두 장애 가능성이 있다.

### 골칫거리 3: 좀비 메시지

장애대응 테스트 N13 — **Zombie Outbox**.

```
장애대응 테스트 — Zombie Outbox:

시나리오: Consumer가 메시지를 읽고 크래시
결과:
  메시지 상태: "읽음" (processing)
  실제 처리: 안 됨
  재처리: 불가 (상태가 "읽음"이므로)
  → 영구적으로 처리되지 않는 메시지
```

Outbox는 "읽음" 상태로 표시되었지만, Consumer가 크래시되어 실제로는 처리되지 않았다. 이 메시지는 다시 처리될 수 없다.

---

## 대응: PGMQ — PostgreSQL 네이티브 큐

### ADR-316의 결정

```
Before: Service → Outbox (MySQL) → Redis Streams → Consumer
After:  Service → PostgreSQL + PGMQ (Same Transaction) → Worker
```

**PGMQ**는 PostgreSQL 확장 프로그램이다. 큐 메시지를 PostgreSQL 테이블에 저장한다. 그래서 비즈니스 데이터와 큐 메시지가 **같은 트랜잭션** 안에 있을 수 있다.

```sql
-- 비즈니스 데이터와 메시지 발행이 같은 트랜잭션
BEGIN;
  -- 좋아요 저장
  INSERT INTO character_like (user_id, character_id) VALUES (1234, 5678);

  -- 메시지 발행 (같은 트랜잭션!)
  SELECT pgmq.send('calculation_queue',
    '{"ocid":"abc123","preset_no":1}'::jsonb);
COMMIT;
-- 둘 다 성공하거나 둘 다 실패. 불일치 불가능.
```

### 5단계 마이그레이션

PGMQ 전환은 5단계에 걸쳐 진행되었다.

```
Phase 0: PGMQ 확장 설치 및 기본 설정
Phase 1: PGMQ 큐 생성, 발행 로직 전환
Phase 2: Consumer를 PGMQ read()로 전환
Phase 3: Outbox 스케줄러 제거
Phase 4: Redis Streams 관련 코드 완전 삭제 (42개 파일)
Phase 5: PgmqWorker 통합 — 단일 워커로 모든 큐 소비
```

### PGMQ의 핵심 연산

```sql
-- 메시지 발행
SELECT pgmq.send('calculation_queue', '{"task":"calc"}'::jsonb);
-- → message_id: 123

-- 메시지 소비 (SKIP LOCKED — 경합 없이 안전)
SELECT * FROM pgmq.read('calculation_queue', 10, 30);
-- → 한 번에 최대 10개, 30초 타임아웃

-- 메시지 처리 완료 (보관)
SELECT pgmq.archive('calculation_queue', 123);
-- → 처리된 메시지는 보관 테이블로 이동
```

### 같은 트랜잭션의 위력

```java
// Before: 두 번의 쓰기 (불일치 가능)
@Transactional
public void process(LikeRequest req) {
    likeRepo.save(req);
    outboxRepo.save(event);     // 트랜잭션 1
    redisTemplate.send(event);  // 트랜잭션 2 ← 위험!
}

// After: 한 번의 쓰기 (원자적)
@Transactional
public void process(LikeRequest req) {
    likeRepo.save(req);
    pgmqService.send("like-queue", event);  // 같은 트랜잭션! ← 안전
}
```

---

## 장애대응 테스트: PGMQ 시나리오

### Scenario: PostgreSQL PGMQ 장애

```
장애대응 테스트: PGMQ 큐 장애 유도

테스트: 큐 읽기 실패
결과:
  서킷 브레이커 likeSyncDb: TRIPPED ✅
  Like Buffer: 보존됨 (메모리에 안전하게 보관)
  복구 후: Buffer → DB 동기화 완료
  데이터 유실: 0건 ✅
```

### Scenario: 메시지 중복 (N14)

```
장애대응 테스트: 메시지 중복 전달

요청 ID: order-12345
총 전달: 5회
처리: 1회 (첫 번째)
중복 차단: 4회 ✅
최종 데이터: 1건 (정확)
```

### Scenario: Outbox Replay (N19)

```
장애대응 테스트: Outbox 재실행

시나리오: 동일 메시지 재소비
결과:
  멱원성 키로 중복 차단 ✅
  데이터 무결성 유지 ✅
  사이드 이펙트: 없음
```

### Compound Failures (N19+)

가장 복잡한 시나리오 — 여러 장애가 동시에 발생.

```
장애대응 테스트: 복합 장애 (CF-1, CF-2, CF-3)

CF-1: PGMQ 장애 + 서킷 브레이커 + 네트워크 분할
CF-2: DB 장애 + 커넥션 풀 고갈 + 알림 격리
CF-3: 전체 장애 + 복구 + 데이터 무결성 검증

결과:
  모든 시나리오에서 데이터 무결성 100% ✅
  서킷 브레이커 정상 동작 ✅
  복구 후 자동 동기화 ✅
```

---

## 결과: 42개에서 1개로

```
Before:
  42개의 Outbox/Redis 파일
  3개의 스케줄러 (6-9 connections 항시 점유)
  이중 쓰기로 인한 불일치 가능성
  좀비 메시지 위험

After:
  1개의 PgmqWorker
  1개의 커넥션
  단일 트랜잭션으로 원자적 보장
  SKIP LOCKED로 안전한 소비
```

---

## 교훈

**1. 복잡성은 장애의 온상이다.**

42개의 파일, 3개의 스케줄러, 이중 쓰기 — 복잡할수록 장애 포인트가 많아진다.

**2. 같은 트랜잭션이 최고다.**

비즈니스 로직과 메시지 발행을 같은 트랜잭션에 넣으면 불일치가 원천적으로 불가능하다.

**3. PostgreSQL은 만능이다.**

관계형 DB, 캐시(UNLOGGED), 락(advisory), 큐(PGMQ), 알림(NOTIFY) — PostgreSQL 하나로 다 된다.

**4. 단순화가 최고의 최적화다.**

42개의 파일을 지우고 1개로 바꾸는 것이, 어떤 알고리즘 최적화보다 시스템을 안정적으로 만들었다.

---

> **다음 장:** [10장: 시험장 — 장애대응 테스트의 탄생](10_test_strategy.md)
