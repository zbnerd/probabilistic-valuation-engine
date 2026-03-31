# 3장: Scale-out — 여러 서버가 하나처럼

> *2026년 1월 27일 ~ 2026년 2월 1일*

---

## 3.1 V5 Stateless 아키텍처 선언

2026년 1월 27일, 이슈 #271이 해결되었다.

```
2026c579 feat: #271 V5 Stateless 아키텍처 전환 구현
```

이슈 #271:
> *"[Arch] V5: Stateless 아키텍처 전환 - Redis External Buffer + 무한 Scale-out"*

### 왜 Stateless인가

기존 Like 버퍼는 **인메모리(Caffeine)**에 저장되었다. Scale-out 환경에서:

```
[Instance A] 좋아요 +1 → A의 메모리 버퍼에만 존재
[Instance B] 좋아요 수 조회 → B의 버퍼에는 없음 → DB에서 읽음 (부정확)
```

각 인스턴스가 자신만의 버퍼를 가지면, **인스턴스 간 좋아요 수가 다르다**.

### 설계 결정: Redis External Buffer

```
Before:  Client → Instance A → [In-Memory Buffer A]
After:   Client → Instance A → [Redis Shared Buffer] ← Instance B도 읽음
```

---

## 3.2 DB 인덱스 최적화

1월 27일, 쿼리 성능 개선도 함께 이루어졌다.

```
14d6103f feat: Repository 쿼리 패턴 분석 기반 DB 인덱스 최적화 (#276)
```

이슈 #276에서 분석된 핵심 쿼리:

```sql
-- character_like 중복 체크 (매 토글마다 실행)
SELECT 1 FROM character_like
WHERE target_ocid = ? AND liker_account_id = ?

-- game_character 좋아요 수 조회
SELECT like_count FROM game_character WHERE user_ign = ?
```

인덱스 추가:

```sql
CREATE INDEX idx_character_like_target_liker
    ON character_like (target_ocid, liker_account_id);
```

---

## 3.3 좋아요 토글 기능 구현

1월 30일, 토글 기능이 완성되었다.

```
195cc551 feat: 좋아요 토글 기능 구현 (Like ↔ Unlike)
```

기존에는 좋아요(Like)와 좋아요 취소(Unlike)가 **별도의 엔드포인트**였다. 토글 패턴으로 통합:

```java
@PostMapping("/{userIgn}/like")
public ResponseEntity<ApiResponse<LikeToggleResponse>> toggleLike(
    @PathVariable String userIgn,
    @AuthenticationPrincipal AuthenticatedUser user) {

    // 이미 좋아요 → 취소
    // 아니면 → 좋아요
    LikeToggleResult result = characterLikeService.toggleLike(userIgn, user);
    return ResponseEntity.ok(
        ApiResponse.success(new LikeToggleResponse(result.liked(), result.likeCount())));
}
```

---

## 3.4 P0/P1 전수 분석 — 5-Agent Council

1월 29일, Like 엔드포인트의 **전면 분석**이 이루어졌다. 이것은 이 프로젝트에서 가장 체계적인 리뷰 중 하나였다.

```
fc3f4d90 feat: V4 Expectation P0/P1 개선 및 좋아요 어뷰징 방지 (#288)
```

### 5-Agent Council 구성

> *참고: 5-Agent Council은 이슈 #285 P0/P1 전수 분석을 위해 구성된 1회성 분석 프레임워크입니다. 현재 프로젝트의 일반 리뷰는 Architect + Critic + Code-Reviewer 3에이전트 합의 방식을 사용합니다.*

| Agent | 역할 | 색상 |
|-------|------|------|
| Blue | Architect (아키텍처) | 🔵 |
| Green | Performance (성능) | 🟢 |
| Red | SRE (안정성) | 🔴 |
| Purple | Financial-Grade Auditor (감사) | 🟣 |
| Yellow | QA / Dead Code | 🟡 |

### 발견된 P0 이슈 8건

| P0 | 문제 | Agent | 영향 |
|----|------|-------|------|
| P0-1 | Check-Then-Act TOCTOU 레이스 컨디션 | Purple | 좋아요 수 영구 드리프트 |
| P0-2 | Relation + Counter 비원자적 이중 쓰기 | Purple | JVM 크래시 시 불일치 |
| P0-3 | Unlike 3-way 비원자적 연산 | Purple+Red | 유령 관계 / count 인플레이션 |
| P0-4 | Controller Double-Read Race | Purple | 이중 카운트/누락 |
| P0-5 | Unlike 시 동기 DB DELETE | Green | 500 DB writes/sec → HikariCP 포화 |
| P0-6 | 불필요한 JOIN FETCH | Green | 1000 불필요 쿼리/초 |
| P0-7 | Redis SPOF — DB Fallback 없음 | Red | Redis 장애 = 100% 서비스 중단 |
| P0-8 | Controller 비즈니스 로직 포함 | Blue | SRP/DIP 위반 |

### P0-1의 심각성: TOCTOU 레이스 컨디션

이것은 Like 도메인의 **가장 심각한 버그**였다.

```
Thread A: checkLikeStatus(ocid) → false (아직 안 누름)
Thread B: checkLikeStatus(ocid) → false (아직 안 누름)
Thread A: addToBuffer(ocid)     → +1
Thread B: addToBuffer(ocid)     → +1  ← 같은 사람이 두 번 좋아요!
```

두 스레드가 거의 동시에 "아직 안 눌렀네"라고 판단하고, 둘 다 좋아요를 실행. 좋아요 수가 +2가 된다.

### 해결: Atomic Toggle Lua Script

P0-1~P0-3, P1-1, P1-2, P1-5를 **하나의 Lua Script**로 해결:

```lua
-- KEYS[1] = {likes}:relations (SET)
-- KEYS[2] = {likes}:relations:pending (SET)
-- KEYS[3] = {likes}:buffer (HASH)
-- KEYS[4] = {likes}:relations:unliked (SET)
-- ARGV[1] = accountId:targetOcid
-- ARGV[2] = userIgn

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])

if exists == 1 then
    -- UNLIKE: Remove + Decrement
    redis.call('SREM', KEYS[1], ARGV[1])
    redis.call('SREM', KEYS[2], ARGV[1])
    redis.call('SADD', KEYS[4], ARGV[1])
    local new_delta = redis.call('HINCRBY', KEYS[3], ARGV[2], -1)
    return {-1, new_delta}
else
    -- LIKE: Add + Increment
    redis.call('SADD', KEYS[1], ARGV[1])
    redis.call('SADD', KEYS[2], ARGV[1])
    redis.call('SREM', KEYS[4], ARGV[1])
    local new_delta = redis.call('HINCRBY', KEYS[3], ARGV[2], 1)
    return {1, new_delta}
end
```

**하나의 Lua Script 안에서 4개의 Redis 자료구조를 원자적으로 조작**한다.

### P0-5 해결: Unlike DB DELETE를 배치로 이관

```
Before: 좋아요 → Write-Behind (비동기) / 취소 → 동기 DB DELETE
After:  좋아요 → Write-Behind (비동기) / 취소 → Write-Behind (비동기)
```

좋아요 취소도 이제 배치 스케줄러가 처리한다. Hot path에서 DB 호출이 완전히 사라졌다.

### P0-6 해결: JOIN FETCH 제거

```
Before: Controller → findByUserIgnWithEquipment(ign) → JOIN FETCH (3-8ms)
After:  Service → calculateEffectiveLikeCount(ign, delta) → 메모리 계산 (0ms)
```

### Before/After 성능 비교

> *출처: [Like Endpoint P0/P1 종합 분석 리포트](../05_Reports/05_08_Refactor/like-endpoint-p0p1-analysis.md) — 부하 테스트 기준*

| 메트릭 | Before | After | 개선율 |
|--------|--------|-------|--------|
| DB QPS (like endpoint) | 2,500-3,500/s | <200/s | **12-17x 감소** |
| P99 Latency (unlike) | 22-35ms | 8-12ms | **3x 개선** |
| P99 Latency (like) | 10-15ms | 3-5ms | **3x 개선** |
| Redis RTT per request | 3-4회 | 1회 | **3-4x 감소** |
| HikariCP 사용률 | 75-125% | 10-15% | **7x 감소** |

---

## 3.5 실시간 동기화 — Pub/Sub

1월 28일, Scale-out 환경의 핵심 기능이 구현되었다.

```
69f87c60 feat: #278 Scale-out 환경 실시간 좋아요 동기화 (Pub/Sub)
37137764 feat: #278 Scale-out 환경 실시간 좋아요 동기화
```

이슈 #278:
> *"feat: Scale-out 환경 실시간 좋아요 동기화 (Redis Pub/Sub 또는 Streams)"*

### 아키텍처

```
[Instance A] 좋아요 토글
     │
     ├─1─→ HINCRBY {likes}:buffer → newDelta
     │
     └─2─→ PUBLISH {likes}:events {userIgn, newDelta, eventType}
                    │
     ┌──────────────┘
     ▼
[Instance B] RTopic Listener
     │
     └─3─→ L1 Cache Evict (character 캐시)
```

### Self-skip 메커니즘

Pub/Sub은 발행자 자신도 수신한다. 자신이 방금 변경한 캐시를 굳이 무효화할 필요가 없다.

```
Instance A → PUBLISH (sourceInstanceId=A)
Instance A → 수신 → sourceInstanceId == myInstanceId → 무시 (Self-skip)
Instance B → 수신 → sourceInstanceId != myInstanceId → L1 Cache Evict
Instance C → 수신 → sourceInstanceId != myInstanceId → L1 Cache Evict
```

### Graceful Degradation

Pub/Sub은 at-most-once 전달이다. 메시지가 유실될 수 있다.

```
Redis 정상: 즉시 캐시 무효화 → 정확한 좋아요 수
Redis 장애: 메시지 유실 → 캐시에 이전 값
                     → 5분 뒤 Caffeine TTL 만료 → 자연 복구
```

이 트레이드오프는 ADR-015에 문서화되었다.

---

## 3.6 좋아요 어뷰징 방지

1월 29일, 어뷰징 방지가 추가되었다.

```
fc3f4d90 feat: V4 Expectation P0/P1 개선 및 좋아요 어뷰징 방지 (#288)
```

### Self-Like 방지

자기 자신의 캐릭터에 좋아요를 누르는 것을 방지:

```java
private void validateNotSelfLike(Set<String> myOcids, String targetOcid) {
    if (myOcids.contains(targetOcid)) {
        throw new BusinessException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
    }
}
```

JWT 인증 시 사용자의 OCID 목록을 미리 로드하여, 토글 시 메모리에서 즉시 확인.

---

## 3.7 LogicExecutor Pipeline 아키텍처

1월 29일, LogicExecutor가 Policy Pipeline 패턴으로 발전했다.

```
9aac41d1 refactor: LogicExecutor 파이프라인 아키텍처 개선 및 테스트 정비 (#290)
8e4d86e1 feat: #284 Executor 강화 + #278 RReliableTopic + PDCA 보고서 (#298)
```

이제 Like 도메인의 모든 연산이 Pipeline을 거친다:

```
Request → [Logging Policy] → [Metrics Policy] → [Circuit Breaker] → [Business Logic]
```

### 이 시점의 아키텍처 (Redis Mode)

```
┌──────────────────────────────────────────────────┐
│                    Controller                     │
│               (JWT + HTTP only)                   │
├──────────────────────────────────────────────────┤
│            CharacterLikeService                   │
│        (Business Logic + LogicExecutor)           │
├──────────────────────────────────────────────────┤
│         AtomicLikeToggleExecutor                  │
│   ┌────────────────────────────────────────────┐ │
│   │  Lua Script (Atomic):                      │ │
│   │  SISMEMBER + SADD/SREM + HINCRBY           │ │
│   └────────────────────────────────────────────┘ │
├──────────────────────────────────────────────────┤
│  L1 (Caffeine)    │    L2 (Redis Shared Buffer)  │
│  [Pub/Sub Evict]  │    {likes}:buffer (HASH)     │
│                   │    {likes}:relations (SET)    │
├───────────────────┴──────────────────────────────┤
│            LikeSyncScheduler                      │
│    1s: L1→L2 flush                               │
│    3s: L2→DB count sync (분산 락)               │
│    5s: L2→DB relation sync (분산 락)            │
├──────────────────────────────────────────────────┤
│                   Database                        │
│     game_character.like_count                     │
│     character_like (relations)                    │
│     compensation_log (감사 추적)                  │
└──────────────────────────────────────────────────┘
```

**Phase 3 요약**: Like 도메인이 단일 인스턴스 한계를 벗어나 Scale-out 가능한 구조가 되었다. Lua Script로 원자성을 확보하고, Pub/Sub으로 인스턴스 간 캐시 일관성을 유지하며, 보상 트랜잭션으로 장애 시에도 데이터를 보호한다.
