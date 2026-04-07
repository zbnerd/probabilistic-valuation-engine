# 6장: 아웃박스의 대가 — 스케줄러가 훔친 커넥션

> "10초마다 3개의 스케줄러가 커넥션을 요구한다. 1분에 18번. 1시간에 1,080번."

## 발견: 모니터링의 이상 패턴

Advisory Lock 문제(5장)를 해결한 후에도, 모니터링에서 여전히 이상한 패턴이 보였다.

```
HikariCP Connections Over Time (Production):

  25 ┤  △    △    △    △    △    △    △    △    △
     │ / \  / \  / \  / \  / \  / \  / \  / \  / \
  18 ┤/   \/   \/   \/   \/   \/   \/   \/   \/   \
     │                                                ← 정상 트래픽
  12 ┤
     └──────┬────┬────┬────┬────┬────┬────┬────┬────
          10s  10s  10s  10s  15s  10s  10s  15s
          ↑ Event  ↑ Nexon  ↑ Event  ↑ Donation

     △ = 스케줄러 폴링 시 커넥션 스파이크
```

**10~15초마다 커넥션이 급증**했다. 3개의 Outbox 스케줄러가 각각 폴링하고 있었다.

## 세 개의 아웃박스 스케줄러

당시 3개의 Outbox 패턴이 각각 독립적인 스케줄러를 가지고 있었다:

```
1. EventOutboxScheduler (@Scheduled fixedRate = 10_000)
   └── EventOutboxProcessor
       └── OutboxFetchFacade (JPA SKIP LOCKED)
       └── PgmqStreamPublisher
       → 10초마다 커넥션 1~3개 점유 (SELECT + UPDATE + NOTIFY)

2. NexonApiOutboxScheduler (@Scheduled fixedRate = 10_000)
   └── NexonApiOutboxProcessor
       └── NexonApiOutboxFetchFacade (JPA SKIP LOCKED)
       └── NexonApiRetryClient
       → 10초마다 커넥션 1~3개 점유 (SELECT + retry logic)

3. OutboxScheduler (@Scheduled fixedRate = 15_000)
   └── OutboxProcessor (Donation)
       └── OutboxFetchFacade (JPA SKIP LOCKED)
       └── DlqHandler
       → 15초마다 커넥션 1~3개 점유 (SELECT + notification)
```

### 커넥션 비용 계산

```
스케줄러당 커넥션 비용:
  폴링 쿼리 (SELECT ... SKIP LOCKED): 1 connection
  상태 업데이트 (UPDATE ... SET status): 1 connection (같은 TX)
  메시지 발행 (pgmq.send or notify): 1 connection (같은 TX)

스케줄러 3개의 총 비용:
  상시 점유: 3 connections (폴링 대기)
  처리 시:   3-9 connections (동시 폴링 시)
  → 최대 9/25 = 36% of pool을 스케줄러가 소비!
```

## 문제의 본질: Outbox 패턴의 커넥션 중복

Outbox 패턴은 "메시지를 잃지 않기 위해" 테이블에 먼저 쓰고, 스케줄러가 나중에 읽어서 발행하는 방식이다.

```
현재 흐름 (Outbox 패턴):

1. 비즈니스 로직:
   BEGIN;
     INSERT INTO event_outbox (status='PENDING');   ← 커넥션 1
   COMMIT;

2. 10초 후 스케줄러:
   BEGIN;
     SELECT * FROM event_outbox WHERE status='PENDING'
       FOR UPDATE SKIP LOCKED;                       ← 커넥션 2
     UPDATE event_outbox SET status='PROCESSING';    ← 커넥션 2 (same TX)
     pgmq.send('queue', message);                    ← 커넥션 2 (same TX)
     UPDATE event_outbox SET status='COMPLETED';     ← 커넥션 2 (same TX)
   COMMIT;

커넥션 사용: INSERT(1) + 스케줄러(1) = 2 connections
```

### PGMQ로 직접 발행하면?

```
목표 흐름 (PGMQ 직접 발행):

1. 비즈니스 로직:
   BEGIN;
     INSERT INTO business_table (...);                ← 커넥션 1
     SELECT pgmq.send('queue', message);              ← 커넥션 1 (same TX!)
   COMMIT;

커넥션 사용: INSERT + pgmq.send = 1 connection!
→ Outbox 테이블 불필요, 스케줄러 불필요
```

**Outbox 3개 → PGMQ 직접 발행으로 전환하면:**

```
절감되는 커넥션:
  Outbox INSERT (3 tables × N rows):      절감 (PGMQ send로 대체)
  Outbox 스케줄러 (3 schedulers):         절감 (삭제)
  Outbox 상태 업데이트 (3 processors):    절감 (불필요)
  ──────────────────────────────────────
  총 절감: ~6-9 connections → 풀의 24-36%

대신 PGMQ Worker가 기존 풀에서 폴링:
  PgmqWorker.processMessages() (@Scheduled fixedDelay 1000ms)
  → read() → process() → archive()
  → 동일 HikariCP 풀 사용, but 유휴 시 커넥션 반납
```

## Outbox와 PGMQ의 병존 문제

당시 Outbox 3개와 PGMQ 5개 큐가 **병존**하고 있었다:

```
┌─ Outbox 기반 (기존) ────────────────────────┐
│                                              │
│  Event Outbox                                │
│    INSERT → 10s poll → SELECT → UPDATE       │
│    → PgmqStreamPublisher → PGMQ 발행         │
│                                              │
│  Donation Outbox                             │
│    INSERT → 15s poll → SELECT → UPDATE       │
│    → sendNotification()                      │
│                                              │
│  Nexon API Outbox                            │
│    INSERT → 10s poll → SELECT → UPDATE       │
│    → NexonApiRetryClient → 외부 API 호출     │
│                                              │
└──────────────────────────────────────────────┘

┌─ PGMQ 기반 (신규, 이미 구현됨) ────────────┐
│                                              │
│  calculation_queue                           │
│    CalculationQueueProducer → CalculationWorker│
│                                              │
│  donation_queue                              │
│    DonationQueueProducer → DonationWorker     │
│                                              │
│  like_sync_queue                             │
│    LikeSyncQueueProducer → LikeSyncWorker     │
│                                              │
│  expectation_calc_high/low                   │
│    → ExpectationCalcWorker                   │
│                                              │
└──────────────────────────────────────────────┘
```

**중복**: Donation은 Outbox와 PGMQ 양쪽에 구현이 존재했다. Event Outbox는 결국 PGMQ에 발행하는 브릿지 역할만 했다. Nexon API Outbox만이 독자적인 재시도 로직을 가지고 있었다.

## 운영 복잡도

Outbox 3개가 만든 관리 포인트:

```
Outbox 테이블: 3개 (event_outbox, donation_outbox, nexon_api_outbox)
Outbox 스케줄러: 3개 (각각 다른 폴링 간격)
Outbox 프로세서: 3개 (각각 다른 처리 로직)
Outbox FetchFacade: 3개 (JPA SKIP LOCKED)
DLQ 핸들러: 3개
메트릭: 3개
Outbox 테이블 정리: 별도 배치
───
총 관리 파일: ~42개
```

## ADR-316의 결정

ADR-316에서 "Outbox 제거, PGMQ로 통일"이 이미 결정되어 있었다. 하지만 구현이 남아 있었다.

핵심 원칙:

```sql
-- 목표: 모든 메시지 발행을 @Transactional 안에서 수행
BEGIN;
  INSERT INTO business_table ...;
  SELECT pgmq.send('queue_name', '...'::jsonb);
COMMIT;
-- 둘 다 성공하거나 둘 다 롤백 → Outbox 불필요
```

**조건**: `pgmq.send()`가 호출자의 트랜잭션에 참여해야 함. 그렇지 않으면 롤백 시 메시지가 이미 발행된 상태가 됨.

## 배운 점

> **"Outbox 패턴은 '안전한 메시지 발행'을 보장하지만, 그 대가로 커넥션과 복잡도를 지속적으로 소모한다. PGMQ의 트랜잭션 참여가 확인된다면, Outbox는 제거해도 된."**

- Outbox 3개 = 커넥션 6~9개 상시 소모 = 풀의 24~36%
- PGMQ 직접 발행 = 같은 트랜잭션, 같은 커넥션 = 추가 비용 없음
- 핵심 전제: `pgmq.send()`가 호출자 TX에 참여하는지 검증 필수

---

**다음 장**: [7장 — PGMQ 통합: 하나의 풀로 모든 것을](./07_pgmq_unification.md)
