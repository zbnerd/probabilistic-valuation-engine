# 3장: Scale-out의 벽

> "인스턴스를 늘릴수록 RPS가 떨어지는 역설."

## Scale-out 테스트

2026년 3월 초, 풀 정렬 후 다시 Scale-out을 시도했다.

```
단일 인스턴스:  940 RPS  ← 7장 기준
3대 인스턴스:  ~940 RPS  ← 거의 동일
5대 인스턴스:  ~900 RPS  ← 오히려 하락
```

인스턴스를 늘려도 RPS가 오르지 않았다. 오히려 5대에서는 떨어졌다.

## 원인: 커넥션의 선형 증가

문제는 **인스턴스마다 각 DB에 커넥션을 맺어야 한다는 것**이었다.

```
[실측] 인스턴스당 커넥션:
  [실측] HikariCP (MySQL):      max 25
  [실측] Redisson (Redis):      max 64
  [미확인] MongoClient (MongoDB): pool size ?
  ─────────────────────────────
  [추정] 합계: 89+ connections/instance (MongoDB 제외)

[추정] 5대 인스턴스:
  [실측] MySQL:     25 × 5 = 125 connections
  [실측] Redis:     64 × 5 = 320 connections
  [미확인] MongoDB: ? × 5 = ? connections
  ────────────────────────────────
  [추정] 총합: 445+ connections → DB 서버 과부하
```

### PostgreSQL의 경우 (t3.small)

PostgreSQL의 `max_connections` 기본값은 100. t3.small(2 vCPU, 2GB RAM)에서 실질적으로 처리 가능한 동시 커넥션은 약 50~80개.

```
PostgreSQL max_connections: 100 (기본값)
유효 한계 (t3.small):       ~50-80 (메모리 제약)

5대 인스턴스 × 25 connections = 125 > 100 ← 이미 초과!
```

커넥션이 초과되면 PostgreSQL은 새 커넥션을 거부한다:

```
FATAL: sorry, too many clients already
```

## 세 개의 데이터베이스가 만든 병목

각 데이터베이스가 Scale-out의 한계를 만들었다:

### MySQL

```
인스턴스 5대 × HikariCP 25 = 125 connections
MySQL max_connections = 151 (기본값)
여유: 26 connections ← SLO 쿼리, 모니터링, 백업도 커넥션 필요
```

### Redis

```
인스턴스 5대 × Redisson 64 = 320 connections
Redis maxclients = 10,000 (기본값, 메모리 제약)
→ Redis 자체는 여유, but 커넥션당 메모리 소모 + 네트워크 오버헤드
```

### MongoDB

```
인스턴스 5대 × MongoClient 20 = 100 connections
MongoDB default connections = 100,000
→ MongoDB은 여유, but 관리 포인트 증가
```

## Redis가 만든 SPOF

가장 큰 문제는 Redis였다. Master + Slave + 3 Sentinel 구성이 **Single Point of Failure**였다.

```
Redis Master 장애 시나리오:
1. Sentinel이 Master 장애 감지 (30초)
2. 투표로 새 Master 선출 (5~10초)
3. 모든 인스턴스가 새 Master로 재연결 (10~30초)
4. 동안 캐시 미스 폭발 → MySQL 커넥션 풀 고갈

총 장애 시간: 45~70초
영향: 전체 서비스 응답 불가
```

Redis 장애 → 캐시 미스 폭발 → MySQL 커넥션 고갈 → 서비스 장애. **3개 DB 중 1개만 장애나도 전체가 무너지는 구조**였다.

## 결정적 질문

2026년 3월 초, 한 가지 질문이 모든 것을 바꿨다:

> **"Redis, MySQL, MongoDB가 각각 하는 일을 PostgreSQL 하나로 할 수 없나?"**

분석 결과:

| 기능 | 현재 | PostgreSQL 대체 | 커넥션 절약 |
|------|------|-----------------|-------------|
| 캐시 (K/V) | Redis (64 conn) | Caffeine L1 + PG UNLOGGED L2 | **-64** |
| 분산락 | Redis (Redisson) | PG Advisory Lock | **포함** |
| Pub/Sub | Redis Pub/Sub | PG LISTEN/NOTIFY | **포함** |
| 영속성 | MySQL (25 conn) | PG 테이블 | **통합** |
| 이벤트 스토어 | MongoDB (20 conn) | PG JSONB | **-20** |
| 메시지 큐 | Redis Stream | PGMQ | **포함** |
| Rate Limiting | Bucket4j + Redis | Bucket4j + Caffeine | **포함** |
| 세션 | Redis Session | Stateless JWT | **포함** |

**모든 기능에 PostgreSQL 대안이 존재했다.**

```
Before: [실측] (25+64) + [미확인] MongoDB = 89+ connections × 3 databases
After:  [실측] 25 connections × 1 database  = 25 관리 포인트

[추정] 절감: 72%+ 커넥션 감소 (MongoDB 제외 추정)
```

## 배운 점

> **"Scale-out은 추가한다고 되는 것이 아니다. 공유 자원(커넥션)이 병목이면, 추가할수록 오히려 느려진다."**

커넥션 풀 정렬(2장)은 필요조건이었다. 하지만 충분조건이 아니었다. 근본적인 해결을 위해서는 **데이터베이스 자체를 하나로 통합**해야 했다.

---

**다음 장**: [4장 — 대이주: 3개 DB에서 1개 PostgreSQL로](./04_great_migration.md)
