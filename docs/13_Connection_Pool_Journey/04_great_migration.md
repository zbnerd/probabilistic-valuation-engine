# 4장: 대이주 — 3개 DB에서 1개 PostgreSQL로

> "세 개의 커넥션 풀이 하나로 합쳐지는 순간, 숨통이 트였다."

## 2026년 3월 9~11일, 3일간의 스프린트

Scale-out의 벽(3장)을 마주한 후, PostgreSQL 단일 DB로의 통합을 결정했다.

### 타임라인

```
3월 9일 — PostgreSQL Migration Foundation (PR #578)
           Docker Compose + PGMQ + 로컬 프로필 설정

3월 9일 — Technical Debt Resolution (PR #573)
           Unit 7: Connection Pool Alignment 적용

3월 10일 — PostgreSQL Scale-out Migration (PR #584)
           Advisory Lock, Single Flight, Hot Key, PGMQ 워커

3월 11일 — Redis/MySQL/MongoDB 제거 (Issues #589, #590, #591)
           의존성 28개 삭제, docker-compose 절반 축소
```

## Phase 1: Redis 제거 (Issue #589)

가장 영향이 컸다. Redis는 7가지 기능에 사용 중이었다.

### 커넥션 관점에서의 변화

```
Before:
  Redisson Pool: max 64 connections
  ├── 캐시 조회/저장 (L2)
  ├── 분산락 (Redisson RLock)
  ├── Pub/Sub (캐시 무효화)
  ├── Rate Limiting (Bucket4j + Redis)
  ├── 메시지 큐 (Redis Stream)
  ├── 세션 저장소
  └── Write-Behind Buffer

After:
  Redisson Pool: DELETED ← 64 connections 절약
  ├── Caffeine L1 + PG UNLOGGED L2
  ├── pg_try_advisory_xact_lock
  ├── PG LISTEN/NOTIFY
  ├── Bucket4j + Caffeine
  ├── PGMQ
  ├── Stateless JWT
  └── PGMQ
```

Redisson 관련 코드 파일 **28개**가 삭제되었다. `docker-compose.yml`에서 Redis Master + Slave + 3 Sentinel 서비스가 사라졌다.

## Phase 2: MongoDB 제거 (Issue #590)

CQRS Read Side로 사용하던 MongoDB를 PostgreSQL JSONB로 교체.

```
Before:
  MongoClient Pool: max 20 connections
  └── CharacterValuationView (MongoDB Document)

After:
  MongoClient Pool: DELETED ← 20 connections 절약
  └── CharacterValuationViewEntity (JPA + @Column jsonb)
```

MongoDB의 Stage & Swap 패턴은 PostgreSQL에서 불가능했지만, 트랜잭션 기반 배치 처리로 대체했다. 오히려 ACID를 무료로 얻었다.

## Phase 3: MySQL 제거 (Issue #591)

마지막 남은 MySQL. Named Lock에서 Advisory Lock으로의 전환은 이미 완료되어 있었다.

```
Before:
  HikariCP → MySQL: max 20 connections
  └── Named Lock (GET_LOCK/RELEASE_LOCK)

After:
  HikariCP → PostgreSQL: max 25 connections
  └── Advisory Lock (pg_try_advisory_xact_lock)
```

`MySqlNamedLockStrategy.kt`, `MySQLFallbackProperties.kt` 등 **6개 파일**이 삭제되었다.

## 커넥션 풀의 근적외선 변화

3개 DB → 1개 전환 후, 커넥션 구조가 근본적으로 바뀌었다.

```
Before (3 databases):
  HikariCP Pool (MySQL):     max 20 connections
  Redisson Pool (Redis):     max 64 connections
  Mongo Pool (MongoDB):      max 20 connections
  ────────────────────────────────────────────────
  총 104 connections, 3개 DB로 분산
  각 풀이 독립적으로 동작 → 한 풀의 여유가 다른 풀의 고갈을 막지 못함

After (PostgreSQL only):
  HikariCP Pool (PostgreSQL): max 30 connections
  ────────────────────────────────────────────────
  총 30 connections, 단일 풀
  모든 작업이 동일 풀에서 → 유휴 커넥션을 다른 작업이 재사용
```

### 왜 30인가

PostgreSQL 하나에 모든 기능이 집중되므로, 기존 MySQL 풀(20)보다 약간 크게 설정:

```
Business queries:     ~15 connections (기존 MySQL 역할)
PGMQ operations:      ~5 connections (기존 Redis Stream 역할)
LISTEN/NOTIFY:        ~3 connections (기존 Redis Pub/Sub 역할)
Advisory Lock:        ~2 connections (기존 Redis Lock 역할)
Buffer operations:    ~5 connections (기존 Redis Buffer 역할)
───────────────────────────────────────────────────
합계:                 ~30 connections

Prod에서는 25로 운영 (메모리 절약)
```

## docker-compose의 변화

```yaml
# Before: 150줄+
services:
  redis-master:
  redis-slave-1:
  redis-slave-2:
  redis-sentinel-1:
  redis-sentinel-2:
  redis-sentinel-3:
  mysql:
  mongodb:
  app:

# After: 40줄
services:
  postgres:   # PGMQ 확장 포함
  app:
```

## 제거된 의존성

```
build.gradle에서 제거:
- redisson-spring-boot-starter     ← Redisson Pool 삭제
- bucket4j-redisson                ← Redis Rate Limit 삭제
- spring-boot-starter-data-mongodb ← MongoClient Pool 삭제
- mysql-connector-j                ← MySQL Driver 삭제
- testcontainers.mysql             ← MySQL 테스트 컨테이너 삭제
- testcontainers.mongodb           ← MongoDB 테스트 컨테이너 삭제
```

## 트레이드오프

| 항목 | 이점 | 대가 |
|------|------|------|
| **커넥션** | 104 → 30 (71% 절감) | PostgreSQL 장애 시 전체 영향 |
| **운영** | DB 1개 관리 = 장애 포인트 1개 | PostgreSQL 메모리/디스크 요구 증가 |
| **일관성** | 트랜잭션 내 원자적 처리 | Redis만큼의 sub-ms 응답 불가 |
| **Scale-out** | 노드 추가만으로 확장 | PG 커넥션 수가 노드당 1세트 증가 |

## 결과: 커넥션 문제의 일시 해소

3개 DB를 1개로 통합하면서:

- 커넥션 수 104 → 30으로 감소
- Scale-out 시 인스턴스당 30 connections → 5대 × 30 = 150 (기존 545 대비 72% 절감)
- Redis SPOF 제거
- 모니터링 단순화 (HikariCP 하나만 보면 됨)

**하지만** 30 connections로 충분했을까? 아니었다. 새로운 병목이 기다리고 있었다.

---

**다음 장**: [5장 — 숨은 병목: Advisory Lock이 훔친 커넥션](./05_advisory_lock.md)
