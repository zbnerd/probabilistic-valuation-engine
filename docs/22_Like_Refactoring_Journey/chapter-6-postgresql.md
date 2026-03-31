# 6장: Redis에서 PostgreSQL로 — 인프라의 근본적 전환

> *2026년 3월 10일 ~ 2026년 3월 23일*

---

## 6.1 결정의 배경

2026년 3월, 프로젝트 전체의 중대한 전환이 결정되었다 — **Redis 의존성 완전 제거**.

이유는 다각적이었다:

| 측면 | Redis | PostgreSQL |
|------|-------|------------|
| 운영 복잡도 | 별도 클러스터 운영 | 이미 사용 중 |
| 데이터 안전성 | AOF/RDB 있지만 비영구 | WAL 기반 영속성 |
| 인프라 비용 | 추가 인스턴스 | 기존 DB 활용 |
| Scale-out | 클러스터 모드 복잡 | Primary-Replica 단순 |
| 장애 도메인 | Redis + DB 이중 | DB 단일 |

이 결정은 Like 도메인에게 **큰 기회**였다. 5장에서 세운 헥사고날 아키텍처 덕분에, 인프라 교체가 Port의 구현체만 바꾸면 되는 일이 되었다.

---

## 6.2 LikeSync Scheduler 타이밍 정렬

3월 10일, 첫 번째 단계로 스케줄러 타이밍이 재조정되었다.

```text
774b7595 refactor: LikeSync Scheduler 타이밍 정렬 및 PostgreSQL Migration 문서화 (#583)
```

### 타이밍 재설계

```text
Before (Redis):
  L1→L2 Flush:  1초
  Count Sync:   3초
  Relation Sync: 5초

After (PostgreSQL):
  Buffer Sync:   2초 (PGMQ 기반)
  Relation Sync: 4초 (UNLOGGED 테이블)
```

**설계 원칙**: 2x multiplier. 각 단계가 이전 단계의 2배 시간 간격을 가진다. 이것은 한 단계가 완료되기 전에 다음 단계가 시작되는 것을 방지한다.

---

## 6.3 PostgreSQL Scale-out Migration

3월 10일, 본격적인 마이그레이션이 진행되었다.

```text
e45a208a feat: PostgreSQL scale-out migration for Redis-free operation (#584)
```

### Redis 기능 → PostgreSQL 대체

| Redis 기능 | PostgreSQL 대체 | 비고 |
|-------------|-----------------|------|
| `HASH {likes}:buffer` | `UNLOGGED TABLE` | 버퍼용 빠른 테이블 |
| `SET {likes}:relations` | `character_like` 테이블 | 직접 DB 쓰기 |
| `PUBLISH {likes}:events` | `PGMQ` (PostgreSQL Message Queue) | 메시지 큐 |
| `Lua Script Atomic` | `SQL Transaction` | DB 레벨 원자성 |
| `Caffeine L1 Cache` | 그대로 유지 | L1은 로컬 |

### PGMQ (PostgreSQL Message Queue)

이슈 #552에서 구현된 PGMQ:

```text
LikeToggle → PGMQ.send('like_events', payload)
                ↓
LikeSyncConsumer → PGMQ.read('like_events')
                ↓
           DB Batch Processing
```

PGMQ는 PostgreSQL 확장으로, Redis Streams와 유사한 메시지 큐 기능을 제공한다. 별도 인프라 없이 DB 안에서 동작한다.

### UNLOGGED TABLE

```sql
CREATE UNLOGGED TABLE IF NOT EXISTS like_buffer (
    user_ign VARCHAR(50) PRIMARY KEY,
    delta BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

UNLOGGED TABLE은 WAL(Write-Ahead Log)을 기록하지 않아 일반 테이블보다 **2-5x 빠르다**. 서버 크래시 시 데이터가 유실될 수 있지만, Like 버퍼는 어차피 임시 데이터이므로 감수 가능하다.

---

## 6.4 Redis/Redisson 의존성 완전 제거

3월 11일, 역사적인 커밋이 들어왔다.

```text
c42d00c5 feat: remove Redis/Redisson dependencies for PostgreSQL migration (#589)
```

이슈 #589:
> *"[V5 Migration] Redis/Redisson 의존성 완전 제거"*

### 삭제된 것들

```text
build.gradle.kts:
- implementation("org.redisson:redisson-spring-boot-starter:...")

application.yml:
- spring.redis.* 설정 전체 삭제
- spring.data.redis.* 설정 전체 삭제

코드:
- RedisLikeBufferStorage.kt          → InMemoryLikeBufferStorage.kt
- RedisLikeEventPublisher.java       → PGMQ 기반으로 대체
- RedisLikeEventSubscriber.java      → PGMQ Consumer로 대체
- RedissonClient Bean                → 제거
- AtomicLikeToggleExecutor (Redis)   → DB 트랜잭션 기반으로 대체
```

### 헥사고날 아키텍처의 위력

이 마이그레이션이 가능했던 이유는 5장에서 세운 **Port/Adapter 분리** 덕분이었다:

```kotlin
// Port (변경 없음)
interface LikeAtomicFetchStrategy {
    fun fetchAndClear(): Map<String, Long>
    fun increment(userIgn: String, delta: Long)
}

// Redis Adapter (삭제)
// class RedisLikeBufferStorage : LikeAtomicFetchStrategy { ... }

// PostgreSQL Adapter (신규)
class InMemoryLikeBufferStorage : LikeAtomicFetchStrategy { ... }
```

module-core와 module-app의 코드는 **단 한 줄도 변경하지 않고** 인프라를 교체할 수 있었다.

---

## 6.5 PostgreSQL 통합 테스트

3월 17~18일, 새 인프라에 대한 테스트가 추가되었다.

```text
8066cd45 feat(chaos): Add PostgreSQL chaos tests for PGMQ, Circuit Breaker, and Network (#567) (#606)
04bd04fa feat(test): Add PostgreSQL integration tests with Testcontainers (#563) (#607)
```

### 카오스 테스트 시나리오

| 시나리오 | 테스트 내용 | 기대 결과 |
|----------|-------------|-----------|
| PGMQ 장애 | 메시지 큐 응답 없음 | Circuit Breaker 오픈 → 버퍼 적재 |
| 네트워크 분할 | DB 연결 끊김 | Buffer에 데이터 보관 → 복구 후 동기화 |
| 동시 쓰기 | 다중 인스턴스 동시 토글 | Advisory Lock으로 직렬화 |
| 장애 복구 | UNLOGGED 테이블 초기화 | 버퍼 복구 메커니즘 동작 |

---

## 6.6 성능 최적화

3월 23일, PostgreSQL 마이그레이션 후 성능 튜닝이 이루어졌다.

```text
e91501d6 perf(cache): fix ClassCastException and optimize bulk loading performance (#614)
```

### 해결된 문제

L2 Cache에서 `ClassCastException`이 발생했다. PostgreSQL UNLOGGED 테이블에서 읽은 데이터를 Caffeine L1에 넣을 때 타입 불일치.

```text
UNLOGGED TABLE → Long
Caffeine Cache → Integer (역직렬화 시 축소)
→ ClassCastException
```

해결: L2 Serializer를 `json`에서 `jdk`에서 `json`으로 전환, 타입 안전한 매핑 추가.

---

## 6.7 이 시점의 아키텍처 (PostgreSQL)

```text
┌──────────────────────────────────────────────────────────┐
│                     module-app                            │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  LikeToggleService                                   │ │
│  │  LikeProcessor (interface)                           │ │
│  │  DatabaseLikeProcessor (implementation)              │ │
│  │  OcidResolutionService                               │ │
│  └──────────┬──────────────────────┬───────────────────┘ │
│             │                      │                     │
├─────────────┼──────────────────────┼─────────────────────┤
│  module-core│                      │  module-infra       │
│  ┌──────────▼──────────┐  ┌───────▼──────────────────┐  │
│  │   Domain Model      │  │   Adapters               │  │
│  │  CharacterLike      │  │  InMemoryLikeBuffer      │  │
│  │  LikeToggleResult   │  │  LikeSyncExecutor         │  │
│  │  Ports (Out)        │  │                           │  │
│  │  LikeAtomicFetch    │  │   PostgreSQL              │  │
│  └─────────────────────┘  │  ├─ UNLOGGED TABLE (buf)  │  │
│                            │  ├─ PGMQ (events)         │  │
│                            │  ├─ character_like        │  │
│                            │  └─ Advisory Lock         │  │
│                            └───────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### Redis → PostgreSQL 전환의 교훈

> **"헥사고날 아키텍처는 선택이지, 강요가 아니다. 하지만 인프라를 교체해야 할 때 그 가치가 증명된다."**

| 측면 | 효과 |
|------|------|
| module-core 변경 | 0줄 |
| module-app 변경 | 설정 파일만 |
| module-infra 변경 | Adapter 구현체 교체 |
| 테스트 | Unit 테스트 변경 없음 |
| 새로운 통합 테스트 | Testcontainers 기반 추가 |

Redis에서 PostgreSQL로의 전환은 **헥사고날 아키텍처의 결정적 증거**가 되었다.

---

## 6.8 Phase 6 요약

| 항목 | 내용 |
|------|------|
| 기간 | 2026-03-10 ~ 2026-03-23 |
| 핵심 변경 | Redis → PostgreSQL 전면 교체 |
| 삭제된 의존성 | Redis, Redisson |
| 새로운 인프라 | PGMQ, UNLOGGED TABLE, Advisory Lock |
| module-core 변경 | 0줄 |
| 핵심 PR | #583, #584, #589, #602, #606, #607, #614 |

> *참고: PR #589(Redis/Redisson 의존성 제거)는 커밋 `c42d00c5`로 존재하나, GitHub PR 페이지는 비공개 상태일 수 있습니다.*
