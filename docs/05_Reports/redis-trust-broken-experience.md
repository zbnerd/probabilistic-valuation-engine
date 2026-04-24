# Redis를 믿었다가 깨진 경험 — 전체 회고

> **프로젝트**: probabilistic-valuation-engine (zbnerd)
> **조사 범위**: 2026-01 ~ 2026-04 커밋로그, PR, 이슈, ADR, 코드 레벨
> **작성일**: 2026-04-24

---

## 1. 처음에 Redis를 어떻게 썼는가

이 프로젝트는 **7가지 목적**으로 Redis(Redisson)를 사용했다 (ADR-319).

| 기능 | Redis 데이터 구조 | 구현체 |
|------|-------------------|--------|
| 분산 락 | RLock | `RedisDistributedLockStrategy` |
| L2 캐시 | String, Hash | `RedissonOperationAdapter` |
| Pub/Sub (캐시 무효화) | Redis Pub/Sub | `RedisCacheInvalidationPublisher/Subscriber` |
| Like 버퍼 | RBucket, RSet, RScoredSortedSet | `RedisLikeBufferStorage`, `RedisLikeRelationBuffer` |
| Rate Limiting | Counter + TTL | `TwoBucketRateLimiter` (Bucket4j + Redis) |
| 세션 저장소 | Redis Session | `RedisSessionRepository` |
| 메시지 큐 | Redis Streams | `RedisStreamPublisher/EventConsumer` |

인프라는 Redis 7.0.15 **Master-Slave + Sentinel** 구성. `docker-compose.yml`에 `redis-master`, `redis-slave`, Redisson 3.48.0.

---

## 2. 사건 1: Redis가 죽었을 때 — 9.5초 타임아웃 지옥 (2026-01-19)

**관련 자료**: `docs/_archive/redis-deprecated/01-redis-death.md`

### 무슨 일이 있었나

Chaos Test에서 Redis Master + Slave를 강제 종료했다. API가 **9.5초 동안 응답 없다가 500 에러**를 반환했다.

```bash
# Redis 장애 주입
docker stop redis-master redis-slave

# API 응답
Status: 500, Time: 9.622078s   # 첫 번째 요청
Status: 500, Time: 9.532207s   # 두 번째 요청
Status: 500, Time: 9.592526s   # 세 번째 요청
... (10회 연속 500 에러, 평균 9.5초)
```

### 근본 원인

Redisson이 Redis 연결 실패 시 **기본 4.8초 타임아웃 + 자동 재시도**를 수행한다. 두 번의 재시도가 이어지면서 총 **9.5초의 Fail Slow** 상태가 되었다.

```text
18:37:01.224 INFO  [scheduling-1] ResilientLock:TryLock:lock, elapsed=4799ms
18:37:20.423 ERROR ResilientLockStrategy : Unknown exception -> propagate
org.redisson.client.RedisException: Unexpected exception
Caused by: io.netty.channel.StacklessClosedChannelException
```

### 이때 알게 된 것들

1. **MySQL Named Lock Fallback이 미구현** — `ResilientLockStrategy`가 Redis 장애를 감지해도 MySQL로 Fallback하지 않고 예외만 전파했다.
2. **L1 스킵 정책은 다행히 구현됨** — `TieredCache.java:154`에서 L2 실패 시 L1에도 저장하지 않아 데이터 불일치는 방지했다.
3. **Circuit Breaker가 OPEN이 되기 전까지는 계속 9.5초씩 블록**된다.

### 교훈

> Redis "신뢰"는 기본 설정에 대한 신뢰가 아니다. 타임아웃, 재시도, Fallback을 직접 튜닝해야 한다.

---

## 3. 사건 2: 96% 장애율 — btree(JSONB) 인덱스 폭주 (2026-04-19)

**관련 커밋**: `d1f86ff8`, `7e00c659`, `91ec2fc2`, `e3729672`
**관련 문서**: `docs/09_Plans/loadtest-postmortem-2026-04-19.md`

### 무슨 일이 있었나

L2 캐시(PostgreSQL UNLOGGED)에 저장하는 `character_valuation_views` 테이블에서:

```text
ERROR: index row size 2952 exceeds btree version 4 maximum 2704
```

btree(JSONB) 인덱스가 **2704바이트 행 크기 제한**을 초과하면서 INSERT가 실패했다. 실패한 데이터가 L2 캐시에 **오염(pollution)**되고, PGMQ 워커가 재시도하면서 오염된 캐시를 읽어 **GZIP 압축 해제 실패** → 전체 시스템 마비로 이어졌다.

### 연쇄 장애 체인

```text
btree(JSONB) 인덱스 오버플로우 (2952 > 2704 byte limit)
  → DB INSERT 실패
    → 캐시에 손상된 데이터 저장 (cache pollution)
      → PGMQ 워커 재시도 시 손상 데이터 읽음
        → GZIP decompress 실패
          → DLQ(Dead Letter Queue) 폭주
            → 96% 실패율
```

### 수정 내용

| 커밋 | 내용 |
|------|------|
| `e3729672` | btree(JSONB) 인덱스 제거 — cascade failure root cause 분석 ADR |
| `d1f86ff8` | btree 인덱스 제거 + L2 cache varargs 수정 + ddl-auto 설정 변경 |
| `91ec2fc2` | Cache defense 메커니즘 추가 — decompress 실패 시 오염 캐시 엔트리 자동 evict |

```java
// ExpectationCacheCoordinator.java:380-382 — 캐시 방어 로직
// decompress 실패 시 오염된 캐시 즉시 제거
cache.evict(corruptedKey);
log.warn("Cache defense: evicted corrupt entry, key={}", corruptedKey);
```

### 교훈

> 캐시에 데이터가 "들어간다"고 안심하지 마라. 직렬화/압축된 데이터가 오염되면 장애가 배로 커진다.

---

## 4. 사건 3: Jackson 직렬화 — L2 캐시 병목으로 30 RPS (2026-04-23)

**관련 PR**: #754 (`d8bae956`, `5076acc2`)

### 무슨 일이 있었나

L2 캐시(PostgreSQL)에서 데이터를 읽을 때 **KotlinModule이 등록되지 않은 ObjectMapper**를 사용해서 역직렬화 실패가 발생했다. 게다가 L2 캐시 조회 자체가 **평균 1,511ms**의 병목이었다.

```text
Cache:Get avg 1,511ms → 전체 RPS ~30 req/s
```

### 수정 내용

| 커밋 | 내용 |
|------|------|
| `5076acc2` | `@Primary` ObjectMapper에 KotlinModule 등록 + L2 캐시 비활성화 |
| `85c5bb21` | `@JsonIgnoreProperties` 추가로 JSONB 역직렬화 버그 수정 |
| `b5faa2b8` | 동일 수정 (merge resolution) |

```yaml
# application.yml — L2 캐시 꺼버림
cache:
  l2:
    enabled: false  # "Redis removed in Issue #589"
```

### 결과

| 지표 | L2 켜짐 | L2 꺼짐 |
|------|---------|---------|
| RPS | ~30 req/s | **585 req/s** |
| 에러 | 있음 | **0** |
| Cache:Get | 1,511ms avg | N/A |

**L2 캐시를 꺼야 성능이 19배 좋아지는 아이러니**가 벌어졌다.

### 교훈

> "L2 캐시가 있으니 빠르겠지"라는 믿음이 실제로는 병목일 수 있다. 측정 없이 최적화는 독이다.

---

## 5. 사건 4: 멀티 인스턴스 캐시 무효화 — Caffeine L1 분기 (2026-04-18~19)

**관련 이슈**: #704, #715, #716
**관련 PR**: #714, #717 (`8cc3d986`, `c88efd6a`)
**관련 문서**: `docs/23_Incident_Response_Journey/04_cache_stampede.md`

### 무슨 일이 있었나

스케일아웃(다중 인스턴스) 환경에서 각 인스턴스의 **Caffeine L1 캐시가 독립적**이라, 인스턴스 A에서 캐시 무효화가 인스턴스 B에 전파되지 않았다.

### 하위 이슈 1: 버전 카운터 충돌 (#716)

```kotlin
// 문제: 각 인스턴스가 AtomicLong(0)으로 시작
private val versionCounter = AtomicLong(0)

// evict()에서 get() 사용 → 증가하지 않음!
fun evict(key: Any) {
    keyVersions[key] = versionCounter.get()  // BUG: 항상 같은 값
}
```

인스턴스 A의 evict 버전 = 인스턴스 B의 evict 버전 → **B가 A의 무효화 이벤트를 스킵**했다.

```kotlin
// 수정: incrementAndGet 사용
fun evict(key: Any) {
    keyVersions[key] = versionCounter.incrementAndGet()  // FIX
}
```

### 하위 이슈 2: cache_storage 테이블 마이그레이션 누락 (#715)

V102, V107에서 인덱스만 만들고 **테이블 CREATE가 빠져있었다**. 새 환경에 배포하면 L2 캐시가 바로 실패.

```sql
-- V110_migration.sql — 누락된 테이블 추가
CREATE TABLE IF NOT EXISTS cache_storage (
    cache_key VARCHAR(500) NOT NULL PRIMARY KEY,
    cache_value BYTEA,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 하위 이슈 3: Cache Stampede (#647)

캐시 만료 시 **모든 인스턴스가 동시에 DB 조회** → 100개 이상의 동일 쿼리가 DB에 몰렸다.

```text
# Stampede 발생 시
SELECT * FROM equipment_expectation WHERE character_id = 'ABC123'  ← 100개 동시 실행
```

### 해결책

1. **PostgreSQL LISTEN/NOTIFY**로 크로스 인스턴스 캐시 무효화
2. **Leader/Follower 패턴** + Advisory Lock으로 Stampede 방지
3. **AtomicLong monotonic counter** + sidecar version map으로 stale 검출
4. `MultiInstanceCacheInvalidationTest`로 6개 시나리오 검증 (evict 전파, burst 처리, stale 버전 스킵 등)

### 교훈

> Redis Pub/Sub을 "PostgreSQL LISTEN/NOTIFY로 바꾸면 되겠지"라고 생각했지만, 버전 관리, 테이블 마이그레이션, 멀티 인스턴스 일관성까지 전부 새로 구현해야 했다.

---

## 6. 사건 5: PGMQ 커넥션 병목 — 메시지 1개당 DB 커넥션 4~5개 (2026-04)

**관련 이슈**: #726
**관련 PR**: #725, #727 (`2e46b7fc`, `01d2824f`, `51447e4c`)
**관련 ADR**: `docs/01_ADR/ADR-pgmq-kafka-migration.md`

### 무슨 일이 있었나

Redis Streams를 **PGMQ(PostgreSQL Message Queue)** 로 교체했는데, PGMQ가 **메시지 1개당 DB 커넥션 4~5개**를 소모했다.

- 폴링(poll) + 처리(process) + 아카이빙(archive) + 캐시 갱신(cache refresh)

```text
HikariCP acquire_seconds_max: 8.188s  (정상 < 1ms)
maxInFlight 100→400: throughput 73% 감소 (22.3 → 5.9 tasks/sec)
```

### 연쇄 효과

```text
PGMQ 폴링이 DB 커넥션 독점
  → HikariCP 풀 고갈
    → 일반 API 쿼리도 대기
      → 응답 시간 폭증
        → 전체 장애
```

### 수정 내용

| 커밋 | 내용 | 효과 |
|------|------|------|
| `51447e4c` | Head-of-line blocking 제거 | 3.3x 워커 처리량 향상 |
| `6b67b986` | Two-phase batch UPSERT | 개별 INSERT를 배치로 통합 |
| `36ce2960` | Time-window batch L2 writes | 97.7% 쓰기 시간 감소 (3,100s → 72s) |
| `4cf78ec6` | Time-window batch L2 lookup | 99% 조회 쿼리 감소 (5,434 → 53 calls) |

### 교훈

> Redis Streams를 "PostgreSQL 기반 큐로 교체하면 되겠지"라고 믿었지만, 메시지 큐는 커넥션 사용 패턴이 근본적으로 달라서 병목의 원인이 됐다. PgBouncer 도입까지 필요했다.

---

## 7. 사건 6: Like 버퍼 Race Condition (2026-03-30)

**관련 PR**: #708 (`edd27f03`, `8cff721f`)
**관련 이슈**: #644, #665

### 무슨 일이 있었나

Redis에서 `InMemoryLikeBufferStorage`로 교체한 후, `fetchAndClear()`에서 Caffeine의 `asMap()`을 반복(iterate)하는 동안 **다른 스레드가 동시에 increment**하면 카운트가 유실되었다.

```kotlin
// 문제 코드
fun fetchAndClear(): Map<String, Int> {
    val result = buffer.asMap()
        .filterValues { it.get() > 0 }
        .mapValues { it.value.getAndSet(0) }
    buffer.clear()
    return result  // 반복 중 누락 발생 가능
}
```

### 수정

```kotlin
// 수정: atomic snapshot + clear
fun fetchAndClear(): Map<String, Int> {
    val snapshot = buffer.asMap()
        .mapValues { it.value.getAndSet(0) }
        .filterValues { it > 0 }
    buffer.clear()
    return snapshot
}
```

### 교훈

> Redis의 원자적 연산(INCR, GETSET)을 믿었다가, In-Memory로 교체하면서 원자성 보장이 사라진 걸 놓쳤다.

---

## 8. 사건 7: 22개 스케일아웃 블로커 (2026-03-28)

**관련 이슈**: #632~#655 (P1 4건 + 하위 이슈들)
**관련 문서**: `docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md`

### 무슨 일이 있었나

Redis 제거 후 스케일아웃을 시도했더니 **22개의 블로커**가 발견되었다.

| 카테고리 | 이슈 | 근본 원인 |
|----------|------|-----------|
| 캐시 일관성 | #632 | Caffeine L1 인스턴스 간 분기 |
| L2 실패 전파 | #636 | L2 실패 시 L1도 스킵 → 캐시 불일치 |
| Stampede | #647 | 캐시 만료 시 동시 DB 폭주 |
| L2 인덱스 | #645 | LIKE full table scan |
| Cache 분리 | #640, #644 | God Object ExpectationCacheCoordinator |
| DLQ replay | #648 | Dead Letter Queue 재처리 누락 |
| Graceful Shutdown | #649 | 종료 시 버퍼 플러시 안 됨 |
| Pool 모니터링 | #650 | HikariCP 메트릭 부재 |

### 교훈

> Redis가 "숨겨주던" 복잡성(분산 락, Pub/Sub, 원자적 버퍼)이 제거되면서 인프라 문제가 애플리케이션 레이어로 전부 올라왔다.

---

## 9. Redis를 완전히 쫓아낸 이유

### 근본 원인 (ADR-022, ADR-319)

| 문제 | 상세 |
|------|------|
| **이중 DB 운영** | PostgreSQL + Redis 동시 관리, 장애 포인트 2배 |
| **저사양 서버 메모리** | Vultr 인스턴스에서 Redis 메모리 부담 |
| **커넥션 풀 병목** | Redis 커넥션 24개 + HikariCP = 총 104개 커넥션 관리 |
| **장애 복구 복잡성** | Redis Master/Slave/Sentinel 복구 시나리오 복잡 |
| **스케일아웃 제약** | Redis Cluster는 과도한 복잡성 |

### Redis → PostgreSQL 매핑

| Redis 기능 | PostgreSQL 대체 | 구현 파일 |
|-----------|-----------------|----------|
| RLock | `pg_try_advisory_xact_lock` | `PostgresAdvisoryLockStrategy` |
| Redis Pub/Sub | `LISTEN/NOTIFY` | `PostgresNotifySubscriber` |
| String/Hash | UNLOGGED TABLE | `PostgresL2CacheStrategy` |
| Redis Streams | PGMQ | `PgmqStreamPublisher` |
| RBucket/RSet | In-Memory + PostgreSQL | `InMemoryLikeBufferStorage` |
| Bucket4j Redis | Bucket4j Caffeine | `AbstractBucket4jRateLimiter` |
| Redis Session | Stateless (JWT) | 세션 저장소 제거 |

### 결과

| 지표 | Redis 있을 때 | Redis 제거 후 |
|------|--------------|--------------|
| DB 수 | 3개 (Redis + MySQL + MongoDB) | **1개 (PostgreSQL)** |
| 커넥션 | 104개 | **30개** |
| RPS | 940 (초기) | **7,347** |
| 삭제 파일 | - | **91+개** |
| Docker 서비스 | 다수 | **50% 감소** |

---

## 10. 전체 타임라인

```text
2026-01-19  Redis Death Chaos Test — 9.5초 타임아웃 지옥 발견
            "Redis가 죽으면 서비스가 죽는다"

2026-02     "PostgreSQL이 Redis/MySQL/MongoDB를 대체할 수 있나?" 질문 시작

2026-03-06  ADR-003, 005, 006 수립 — PostgreSQL 대체 전략
2026-03-10  ADR-319 — Redis 기능 PostgreSQL 대체 전략 승인
2026-03-11  ADR-022 — Redis/Redisson 의존성 완전 제거 결정
2026-03-11~15 Redis 제거 3일 스프린트 (PR #584, #594, #597)
            91+ 파일 삭제, build.gradle에서 Redis 의존성 제거

2026-03-19  PR #608 — Micro-batching 구현
2026-03-20  PR #609 — LISTEN/NOTIFY 캐시 무효화 수정 (RPS 6,543→7,347)
2026-03-28  Issue #632~#655 — 22개 스케일아웃 블로커 발견
            Like 버퍼 race condition, Cache stampede, L2 인덱스 문제 등
2026-03-30  docs/archive — Redis 관련 문서 아카이빙

2026-04-05  Issue #704 — 멀티 인스턴스 캐시 무효화 테스트 생성
2026-04-09  PR #708 — Issues #651~#655 수정 (Redis 잔여 코드 정리)
2026-04-18  Issue #715, #716 — cache_storage 테이블 누락 + 버전 카운터 충돌
2026-04-19  PR #717 — V110 마이그레이션 + 버전 카운터 수정
            Load test — 96% 장애율 (btree(JSONB) 인덱스 오버플로우)
            PR #722 — 캐시 TTL/키 버전 YAML 외부화

2026-04-22  PR #750 — Batch L2 lookup (99% 쿼리 감소)
            PR #751 — Batch L2 writes (97.7% 시간 감소)

2026-04-23  PR #754 — Jackson KotlinModule 수정 + L2 캐시 비활성화
            L2 끄니 RPS 30 → 585 (19배 향상)
```

---

## 11. 핵심 교훈

### 1. "Redis가 있으니 안전하다"는 착각

Redis의 원자적 연산(INCR, SETNX, Pub/Sub)에 의존하다가, 이를 제거하면서 **동시성 문제가 애플리케이션 코드로 넘어왔다**. Like 버퍼 race condition, 버전 카운터 충돌, Cache Stampede — 모두 Redis가 숨겨주던 문제였다.

### 2. 캐시는 "있다고" 빠른 게 아니다

L2 캐시를 켜둔 상태에서 **Jackson 직렬화 병목**으로 평균 1,511ms. 캐시를 꺼야 성능이 19배 좋아지는 모순이 발생했다. 측정 없는 최적화는 최악의 병목을 만든다.

### 3. 마이그레이션은 "기능 매핑"이 아니라 "패턴 재설계"

Redis Streams → PGMQ 교체는 단순 API 매핑이 아니었다. **커넥션 사용 패턴**이 근본적으로 달라서 HikariCP 풀 고갈이라는 새로운 병목이 발생했다. PgBouncer, 배치 처리, time-window 최적화까지 필요했다.

### 4. 멀티 인스턴스는 숨겨진 함정

단일 인스턴스에서는 완벽한 Caffeine 캐시가, **인스턴스가 2개 이상이 되면** 즉시 불일치 문제가 발생한다. 버전 카운터, LISTEN/NOTIFY, Leader/Follower 패턴까지 전부 새로 구현해야 했다.

### 5. "검증된 기술"에 대한 맹신

Redis는 검증된 기술이지만, **이 프로젝트의 트래픽 패턴**에는 과도한 인프라였다. 일반 트래픽 1~5 ops/sec, 패치데이 50~200 ops/sec. 이 수준은 PostgreSQL만으로 충분했고, 오히려 단일 DB로 단순화하는 게 성능에 유리했다.

---

## 결론

Redis를 "믿었다가 깨진" 경험은 Redis 자체의 문제가 아니라, **분산 시스템의 복잡성을 외부 서비스에 위임했다가 그 위임을 해제하는 과정에서 겪은 고통**이었다.

그 과정에서 다음을 모두 직접 겪고 해결했다:
- 96% 장애율 (btree 인덱스 오버플로우 → 캐시 오염 → GZIP 실패 → DLQ 폭주)
- 19배 성능 저하 (L2 캐시 Jackson 병목)
- 22개 스케일아웃 블로커
- 버전 카운터 충돌 (멀티 인스턴스 캐시 무효화)
- Race Condition (Like 버퍼 원자성 상실)
- HikariCP 커넥션 풀 고갈 (PGMQ 커넥션 과다 소모)

최종적으로는 **Redis 없이도 7,347 RPS를 달성**했고, 인프라는 3개 DB에서 1개 PostgreSQL로, 커넥션은 104개에서 30개로 단순화되었다.

---

*조사 기반 자료: git log 80+ commits, GitHub Issues #547~#754, ADR-003/005/006/022/319, 코드 74개 파일*
