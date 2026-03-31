# 2장: Redis 원자성 — Lua Script의 등장

> *2026년 1월 2일 ~ 2026년 1월 17일*

---

## 2.1 데이터 정합성의 첫 번째 균열

2026년 1월 2일, 새해 첫 업무일에 심각한 버그가 보고되었다.

```text
efd37c69 fix: 장애 복구 프로세스 데이터 정합성 및 중복 결함 수정 (#124)
```

### 무슨 일이 있었나

장애 복구 과정에서 좋아요 수가 **중복 카운트**되는 현상이 발견되었다. 복구 로직이 실패한 배치를 재실행하면서, 이미 처리된 좋아요가 다시 DB에 반영된 것이다.

원인은 명확했다 — **비원자적 연산**:

```text
1. Buffer에서 데이터 읽기 (fetch)
2. Buffer에서 데이터 삭제 (clear)
3. DB에 데이터 쓰기 (persist)

← 2번과 3번 사이에 장애 발생 시, 데이터 유실 또는 중복
```

---

## 2.2 LogicExecutor — 예외 처리의 기둥

1월 3~4일, 예외 처리 체계의 골격이 세워졌다.

```text
4dc6260a [Release] v2.3.0 - Redis HA 가용성 강화 및 시스템 안정화 (#136)
c61e0fa0 [Refactor] LogicExecutor 기반 예외 처리 구조화 완료 (Tests 82/82 Passed) (#140)
```

### LogicExecutor의 7가지 패턴

| 패턴 | 메서드 | Like 도메인 적용 |
|------|--------|-----------------|
| 기본 실행 | `execute()` | 토글, 동기화 |
| Void 실행 | `executeVoid()` | 이벤트 발행 |
| 기본값 반환 | `executeOrDefault()` | Redis 장애 시 fallback |
| 복구 로직 | `executeOrCatch()` | 보상 트랜잭션 |
| 폴백 | `executeWithFallback()` | 캐시 미스 시 DB 조회 |
| Finally | `executeWithFinally()` | 자원 해제 |
| 예외 변환 | `executeWithTranslation()` | 기술적 예외 → 도메인 예외 |

이 패턴들 덕분에 Like 도메인의 모든 코드에서 `try-catch`가 사라졌다.

---

## 2.3 Redis 원자성 — Lua Script의 도입

1월 12일, Like 도메인의 가장 중요한 전환점 중 하나가 되는 커밋이 들어왔다.

```text
18141cd9 feat(#147): LikeSyncService Redis 원자성 보장 - Lua Script 기반 데이터 유실 방지 (#164)
```

### 문제의 본질: fetchAndClear의 비원자성

기존 LikeSyncService의 동기화 로직:

```java
// Step 1: Buffer의 모든 엔트리 조회
Map<String, Long> entries = redis.opsForHash().entries("{likes}:buffer");

// Step 2: DB에 배치 업데이트
for (Map.Entry<String, Long> e : entries.entrySet()) {
    repository.incrementLikeCount(e.getKey(), e.getValue());
}

// Step 3: Redis에서 삭제
redis.opsForHash().delete("{likes}:buffer", entries.keySet().toArray(new String[0]));
```

**문제**: Step 1~3이 원자적이지 않다. Step 2와 3 사이에 서버가 크래시하면?

```text
→ DB에는 이미 반영됨
→ Redis에는 여전히 남아있음
→ 다음 sync 사이클에서 재실행 → 중복 카운트!
```

### 해결: Lua Script로 fetchAndClear를 원자화

```lua
-- KEYS[1] = {likes}:buffer
-- 단일 Lua Script로 읽기+삭제를 원자 실행
local entries = redis.call('HGETALL', KEYS[1])
if #entries > 0 then
    redis.call('DEL', KEYS[1])
end
return entries
```

Redis의 Lua Script는 **단일 스레드에서 원자적으로 실행**된다. 스크립트가 실행 중일 때 다른 클라이언트의 명령은 대기한다.

**결과**: fetch와 clear 사이에 끼어들 수 있는 간격이 **0**이 되었다.

---

## 2.4 보상 트랜잭션 — 실패해도 안전하게

같은 날, Lua Script에 이어 보상 트랜잭션이 추가되었다.

```text
720ef598 feat(#147): LikeSyncService 원자성 및 보상 트랜잭션 구현 (#175)
```

### Lua Script로 해결하지 못하는 것

Lua Script는 Redis 내의 원자성은 보장하지만, **Redis→DB 간의 원자성**은 보장하지 않는다.

```text
Lua Script: HGETALL + DEL (원자적) ✓
DB Batch: UPDATE game_character SET like_count = like_count + ? ← 여기서 실패?
```

DB 배치 업데이트가 실패하면, Redis에서는 이미 삭제된 상태. 데이터가 **어디에도 없는** 상태가 된다.

### 보상 트랜잭션 패턴

```text
┌──────────────────────────────────────────────────┐
│                LikeSyncExecutor                    │
├──────────────────────────────────────────────────┤
│  1. Lua Script: fetchAndClear()                   │
│     → 원자적으로 버퍼 데이터 획득 + 삭제         │
│                                                    │
│  2. DB Batch: UPDATE like_count                    │
│     → 성공 시: 완료                               │
│     → 실패 시: RedisCompensationCommand 실행      │
│        → 획득한 데이터를 Redis에 복원             │
│        → 다음 sync 사이클에서 재시도              │
│                                                    │
│  3. CompensationLog 기록                           │
│     → 복구 이력을 DB에 저장 (감사 추적)          │
└──────────────────────────────────────────────────┘
```

### 보상 로그 테이블

```sql
CREATE TABLE compensation_log (
    id BIGSERIAL PRIMARY KEY,
    task_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,  -- PENDING, COMPLETED, FAILED
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);
```

---

## 2.5 인증 시스템 — BYOK (Bring Your Own Key)

1월 12일, 인증 시스템이 추가되었다.

```text
cdf6a716 feat(#146): BYOK 인증 시스템 및 Admin 관리 API 구현
```

이슈 #146의 내용:
> *"[P0] Admin/핵심 API 인증·인가 최소선 구축"*

Like 토글 API는 이제 인증된 사용자만 호출할 수 있다. 이것은 **self-like 방지**의 전제 조건이 된다 — 누가 누구를 좋아하는지 알아야 자기 자신을 좋아하는지 확인할 수 있다.

---

## 2.6 성능 최적화와 순환 참조

1월 16일, LikeSync의 성능과 구조적 문제가 해결되었다.

```text
ec32ac5a feat(#171-119-48): LikeSync 성능 최적화 및 순환 참조 제거 (#189)
```

### 순환 참조 문제

```text
LikeSyncService → LikeBufferStorage → LikeSyncService
                      ↑___________________↓
```

Spring이 Bean을 생성할 때 순환 의존성으로 인해 `BeanCurrentlyInCreationException`이 발생했다.

### 해결: 인터페이스 분리

```text
Before:
LikeSyncService → LikeBufferStorage (구체 클래스)

After:
LikeSyncService → LikeBufferStrategy (인터페이스)
                    ↑
              LikeBufferStorage (구현체)
```

이 분리는 향후 Redis 버퍼와 In-Memory 버퍼를 **전략 패턴**으로 교체할 수 있는 기반이 된다.

### 성능 개선

| 항목 | Before | After |
|------|--------|-------|
| Sync 소요 시간 | 200-500ms | 50-150ms |
| Redis 명령 수 | 15-20 | 5-8 |
| DB 쿼리 | N+1 | Batch |

---

## 2.7 이 시점의 아키텍처

```text
┌─────────────────────────────────────────────┐
│                  Controller                  │
│          (JWT Authentication)                │
├─────────────────────────────────────────────┤
│          CharacterLikeService                │
│      (Business Logic + LogicExecutor)        │
├─────────────────────────────────────────────┤
│         AtomicLikeToggleExecutor             │
│     (Lua Script: SISMEMBER+SADD+HINCRBY)    │
├─────────┬───────────────────────────────────┤
│  Redis  │       In-Memory Buffer            │
│ (L2)    │         (L1)                      │
├─────────┴───────────────────────────────────┤
│          LikeSyncScheduler                   │
│    (fetchAndClear → DB Batch → Compensate)   │
├─────────────────────────────────────────────┤
│                 Database                     │
│     (MySQL + JPA + CompensationLog)          │
└─────────────────────────────────────────────┘
```

### 해결된 핵심 문제들

| 문제 | 해결 | 근거 |
|------|------|------|
| 비원자적 fetchAndClear | Lua Script | PR #164 |
| 장애 시 데이터 유실 | 보상 트랜잭션 | PR #175 |
| 순환 참조 | 인터페이스 분리 | PR #189 |
| 예외 처리 불일치 | LogicExecutor | PR #140 |
| 인증 없는 API | BYOK JWT | PR #146 |

### 여전히 남은 문제

- Check-Then-Act TOCTOU (toggle 자체의 원자성)
- unlike 시 동기 DB DELETE
- Scale-out 환경에서 L1 캐시 불일치
- 여전히 In-Memory 단일 인스턴스 한계

이 문제들은 3장에서 다룬다.
