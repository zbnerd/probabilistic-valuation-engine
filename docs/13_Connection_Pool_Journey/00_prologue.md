# 프롤로그: 세 개의 데이터베이스, 분산된 커넥션 풀

> "커넥션은 무한하지 않다. 이 진리를 깨닫는 데 3개월이 걸렸다."

## 2026년 1월, 인프라의 풍경

probabilistic-valuation-engine의 아키텍처는 '적절한 도구로 적절한 일을'이라는 철학 위에 세워져 있었다.

```
Client → Spring Boot (Java 21, Virtual Threads)
           ├── Redis 7.0 (Master + Slave + 3 Sentinel)
           │     ├── 캐시 (Caffeine L1 + Redis L2)
           │     ├── 분산락 (Redisson)
           │     ├── Pub/Sub (캐시 무효화)
           │     ├── Rate Limiting (Bucket4j + Redis)
           │     ├── 메시지 큐 (Redis Stream)
           │     └── 세션 저장소
           │
           ├── MySQL 8.0
           │     ├── 영속성 저장
           │     ├── Named Lock
           │     └── Outbox 테이블 (Donation, Event)
           │
           ├── MongoDB
           │     ├── CQRS Read Side
           │     └── 이벤트 스토어
           │
           └── Nexon API (외부)
```

각 데이터베이스는 저마다의 이유가 있었다. Redis는 빠른 캐시와 분산락, MySQL은 관계형 영속성, MongoDB는 유연한 문서 저장. 합리적인 선택이었다.

## 보이지 않는 비용: 커넥션

문제는 **커넥션**이었다. 각 데이터베이스마다 독립적인 커넥션 풀이 필요했다.

```
[실측] HikariCP (MySQL):      max-pool-size: 25   → 25 connections
[실측] Redisson (Redis):      connection-pool: 64  → 64 connections
[미확인] MongoClient (MongoDB): pool size 미확인    → ? connections
────────────────────────────────────────────────────────────
총합: 89+ connections (단일 인스턴스 기준, MongoDB 제외)
```

단일 인스턴스에서는 문제가 없었다. 89+개 커넥션쯤이야, 서버가 충분히 감당했다.

하지만 **Scale-out**을 시작하는 순간, 수학이 달라진다.

```
[추정] 1대 인스턴스:  89+ connections → 총 89+개
[추정] 3대 인스턴스:  (25+64) × 3 = 267+ connections (MongoDB 제외)
[추정] 5대 인스턴스:  (25+64) × 5 = 445+ connections → DB 서버 한계 도달
```

MySQL `max_connections`의 기본값은 151. Redis는 10,000개까지 가능하지만, 커넥션마다 메모리를 차지한다. MongoDB도 마찬가지.

## 첫 번째 징후

2026년 1월 성능 여정에서 7장까지 940 RPS를 달성했다. 그리고 Scale-out을 시도했다.

결과는 **역설적**이었다. 5대 인스턴스에서 RPS가 떨어졌다.

Grafana 대시보드에 새로운 패턴이 나타났다.

```
[예시] HikariCP Metrics:
  connections.active: ████████████████████ 20/20  ← POOL EXHAUSTED
  connections.pending: ++++++++++ 47 threads waiting
  connections.timeout: 12 in last 5 minutes

  (이 Grafana 출력은 설명을 위한 예시입니다)
```

**커넥션 풀 고갈(Connection Pool Exhaustion).** HikariCP의 20개 커넥션이 모두 사용 중이었고, 47개의 스레드가 커넥션을 기다리고 있었다.

이것이 3개월간의 커넥션 전쟁의 시작이었다.

## 세 가지 근본 원인

분석 결과, 커넥션 부족의 원인은 세 가지였다.

### 1. 풀 사이즈 정렬 실패

```
HikariCP:  maximum-pool-size = 25 (Production)
Tomcat:    threads.max = 200
비율:      25 / 200 = 12.5%
```

200개의 스레드가 동시에 요청을 처리하는데, 커넥션은 20개뿐. 180개의 스레드가 커넥션을 기다려야 했다.

### 2. 세 개의 분산된 풀

하나의 요청이 처리되는 동안:

```
[예시] 요청 하나의 커넥션 여정:
1. Redis에서 캐시 조회         → Redisson 커넥션 1개
2. Cache miss → MySQL 조회     → HikariCP 커넥션 1개
3. 결과 MySQL 저장             → HikariCP 커넥션 (재사용)
4. Redis 캐시 업데이트         → Redisson 커넥션 1개
5. Outbox에 이벤트 저장        → HikariCP 커넥션 1개
6. MongoDB Read Model 업데이트  → MongoClient 커넥션 1개

최대 6개 커넥션이 하나의 요청에 필요 (실제 패턴은 상이할 수 있음)
```

### 3. 스케줄러의 숨은 소비

3개의 Outbox 스케줄러가 각각 **개별 커넥션**으로 폴링:

```
[예시] OutboxScheduler (15s):        → HikariCP 커넥션 점유
[예시] EventOutboxScheduler (10s):   → HikariCP 커넥션 점유
[예시] NexonApiOutboxScheduler (10s): → HikariCP 커넥션 점유

[추정] 스케줄러만 3개 커넥션 상시 점유 → 실제 비즈니스 로직에 22개 남음
```

## 이 책이 묻는 질문

> **"커넥션 풀을 어떻게 최적화했는가?"**

답은 단순하지 않았다. 풀 사이즈를 키우는 것으로 시작해, 데이터베이스를 통합하고, 락을 바꾸고, 결국 모든 메시지 처리를 PGMQ로 통합하는 여정이었다.

---

**다음 장**: [1장 — 첫 번째 경고: HikariCP 정렬 실패](./01_misalignment.md)
