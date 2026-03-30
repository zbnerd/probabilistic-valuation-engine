# 8장: 대이주 — Redis, MySQL, MongoDB를 버리다

> "세 개의 데이터베이스가 성능의 천장이었다. 하나를 버릴 때마다 하늘이 열렸다."

## 문제: 인프라가 성능의 감옥

7장에서 940 RPS를 달성했다. 하지만 그 한계도 명확했다. 5대 인스턴스부터 오히려 RPS가 떨어졌다. 원인은 **HikariCP 커넥션 풀 고갈**이었고, 그 아래에는 더 근본적인 문제가 있었다.

```
Redis:      캐시 + 분산락 + Pub/Sub + Rate Limiting
MySQL:      영속성 저장 + Named Lock
MongoDB:    이벤트 스토어 + CQRS Read Side

3개 데이터베이스 = 3배의 장애 포인트 × 3배의 운영 복잡도 × 3배의 네트워크 왕복
```

요청 하나가 처리되는 동안 Redis를 3~5번 거치고, MySQL에 쓰고, MongoDB에 이벤트를 발행한다. 각 왕복이 1~5ms면, 데이터베이스 왕복만으로 20~40ms가 누적된다.

더 큰 문제는 **Scale-out**이었다. Redis는 SPOF(Single Point of Failure)이고, MySQL은 커넥션 풀 한계가 있고, MongoDB는 클러스터 구성 비용이 든다. 세 개를 모두 Scale-out 가능하게 만들면 운영 비용이 5배 이상 뛴다.

## 질문: 하나로 줄이면 안 되나?

2026년 2월, 한 가지 질문이 모든 것을 바꿨다.

> **"Redis, MySQL, MongoDB가 각각 하는 일을 PostgreSQL 하나로 할 수 없나?"**

PostgreSQL은 이미 L2 캐시용 UNLOGGED 테이블로 사용 중이었다. 분산락은 `pg_try_advisory_xact_lock`으로 구현되어 있었다. 그렇다면 나머지도 옮길 수 있지 않을까?

분석 결과:

| 기능 | 현재 | PostgreSQL 대체 |
|------|------|-----------------|
| 캐시 (K/V) | Redis | Caffeine L1 + PG UNLOGGED L2 |
| 분산락 | Redis Named Lock | PG Advisory Lock |
| Pub/Sub | Redis Pub/Sub | PG LISTEN/NOTIFY |
| 영속성 | MySQL | PG 테이블 |
| 이벤트 스토어 | MongoDB | PG JSONB |
| 메시지 큐 | Redis Stream | PGMQ |
| Rate Limiting | Bucket4j + Redis | Bucket4j + Caffeine |
| 세션 | Redis Session | Stateless JWT |

**모든 기능에 PostgreSQL 대안이 이미 존재했다.** 구현만 남은 상태.

## 대이주: 3개 제거, 1개로 통합

2026년 3월 11일, 3일간의 스프린트로 세 개의 데이터베이스 의존성을 대부분 제거했다. Session 저장소, Refresh Token 등 일부 기능은 후속 스프린트에서 완료 예정이다.

### Phase 1: Redis 제거 (Issue #589)

가장 영향이 컸다. Redis는 7가지 기능에 사용 중이었다.

```
제거한 것:
├── RedisDistributedLockStrategy.kt     → PostgresAdvisoryLockStrategy
├── RedisBufferStrategy.kt             → PGMQ (PostgreSQL Message Queue)
├── RedisMessageQueue.kt               → PGMQ
├── RedisStreamPublisher/Consumer.kt   → PgmqStreamPublisher
├── RedisCacheInvalidation*.kt         → PostgresNotifySubscriber
├── RedisLikeBuffer*.kt                → PostgreSQL 기반
├── TwoBucketRateLimiter.kt            → Caffeine 전용
└── RedissonConfig.kt                  → 삭제
```

PGMQ(PostgreSQL Message Queue)는 PostgreSQL 익스텐션 기반 메시지 큐다. Redis Stream을 대체하여 Write-Behind Buffer의 비동기 쓰기 경로에 사용된다. 영속성이 보장되고, PostgreSQL 트랜잭션 내에서 큐 작업이 원자적으로 처리된다.

`build.gradle`에서 `redisson-spring-boot-starter`, `bucket4j-redisson` 의존성을 제거했다. Redisson 관련 코드 파일 **28개**가 삭제되었다.

`docker-compose.yml`에서 Redis Master + Slave + 3 Sentinel 서비스가 사라졌다.

### Phase 2: MongoDB 제거 (Issue #590)

CQRS Read Side로 사용하던 MongoDB를 PostgreSQL JSONB로 교체했다.

```
Before:  CharacterValuationView (MongoDB Document)
After:   CharacterValuationViewEntity (JPA Entity + @Column jsonb)
```

MongoDB의 `BatchCharacterViewService`에서 제공하던 Stage & Swap 패턴은 PostgreSQL에서 불가능했지만, 트랜잭션 기반 배치 처리로 대체했다.

### Phase 3: MySQL 제거 (Issue #591)

마지막 남은 MySQL. Named Lock에서 Advisory Lock으로의 전환은 이미 완료되어 있었다.

```yaml
# Before
spring.datasource.driver-class-name: com.mysql.cj.jdbc.Driver
lock.impl: redis

# After
spring.datasource.driver-class-name: org.postgresql.Driver
lock.impl: postgres
```

`MySqlNamedLockStrategy.kt`, `MySQLFallbackProperties.kt`, `MySQLHealthState.kt` 등 **6개 파일**이 삭제되었다.

## 결과: 단일 인프라

```
Before (3 databases):
Client → Spring Boot
           ├── Redis 7.0 (Master + Slave + 3 Sentinel)
           ├── MySQL 8.0
           ├── MongoDB
           └── Nexon API

After (1 database):
Client → Spring Boot
           ├── PostgreSQL (캐시, 락, Pub/Sub, 영속성, 큐)
           └── Nexon API
```

docker-compose.yml이 절반 이하로 줄었다. 운영 포인트가 3개에서 1개로. 장애 대응 시나리오도 단순해졌다.

### 커넥션 풀의 변화

7장에서 HikariCP 커넥션 풀 고갈이 Scale-out 한계였다. 3개 DB → 1개 전환 후, 상황이 근본적으로 바뀌었다.

```
Before (3 databases):
HikariCP Pool (MySQL):     max 20 connections
Redis Pool (Redisson):     max 64 connections
Mongo Pool (MongoClient):  max 20 connections
→ 총 104개 커넥션, 3개 DB로 분산

After (PostgreSQL only):
HikariCP Pool (PostgreSQL): max 30 connections
→ 단일 풀로 집중, Redis/MySQL/MongoDB 커넥션 불필요
→ 7장의 병목이었던 "풀 고갈" 문제 해소
```

### 제거된 의존성

```
build.gradle에서 제거:
- redisson-spring-boot-starter
- bucket4j-redisson
- spring-boot-starter-data-mongodb (이미 제거됨)
- mysql-connector-j
- testcontainers.mysql
- testcontainers.mongodb
```

### 트레이드오프

| 항목 | 이점 | 대가 |
|------|------|------|
| **운영 단순성** | DB 1개 관리 = 장애 포인트 1개 | PostgreSQL 장애 시 전체 서비스 영향 |
| **비용** | Redis 인스턴스 불필요 | PostgreSQL 메모리/디스크 요구 증가 |
| **일관성** | 트랜잭션 내 원자적 처리 | Redis만큼의 sub-ms 응답 불가 |
| **Scale-out** | 노드 추가만으로 확장 | PG 커넥션 수가 노드당 1개 증가 |

## 배운 점

> **"더 많은 기술을 도입하는 것이 아니라, 더 적은 기술로 더 많은 것을 하는 것이 진정한 엔지니어링이다."**

Redis는 확실히 빠르다. 하지만 빠른 것이 항상 정답은 아니다. 우리 시스템의 캐시 히트율은 99.99%다. 10,000건 중 1건만 캐시 미스다. 그 1건을 위해 Redis 전체 인프라를 유지하는 것은 과투자였다.

PostgreSQL Advisory Lock은 Redis 분산락보다 느리지만, 트랜잭션 스코프에서 자동 해제된다. 세션 스코프 락의 위험(HikariCP에서 커넥션 반환 시 락이 풀리지 않는 문제)도 없다.

MongoDB의 문서 지향 쿼리는 편했지만, PostgreSQL JSONB + 인덱스로도 충분했다. 오히려 ACID 트랜잭션을 무료로 얻었다.

이 대이주가 없었다면 다음 장의 **PostgreSQL NOTIFY**도 없었을 것이다. Redis를 버리지 않았으면 영원히 Redis Pub/Sub에 종속되었을 것이다.

---

> **이 시점의 RPS: 변화 없음 (인프라 단순화에 집중)**
> **관련 이슈**: #589 (Redis 제거, 일부 CLOSED), #590 (MongoDB 제거), #591 (MySQL 제거)
> **관련 ADR**: ADR-022, ADR-023, ADR-024

**다음 장**: [9장 — 최후의 도약: PostgreSQL NOTIFY](./09_postgresql_notify.md)
