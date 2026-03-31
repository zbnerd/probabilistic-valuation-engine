# 7장: Direct DB — 마지막 퍼즐

> *2026년 3월 24일 ~ 2026년 3월 31일*

---

## 7.1 V3 Like Endpoint Review — 예측과 현실

3월 28일 이전, V3 Like 엔드포인트의 사전 리뷰에서 6개의 예측이 등록되었다:

| # | 예측 | 실제 | 결과 |
|---|------|------|------|
| 1 | Caffeine relation maps diverge across instances | 해결됨 (PostgreSQL 직접 쓰기) | ✅ |
| 2 | PGMQ cold restart: messages lost or replayed | 해결됨 (UNLOGGED + 보상) | ✅ |
| 3 | No transaction: Buffer mutate → PGMQ publish gap | Direct DB 토글로 우회 | ✅ |
| 4 | LikeSyncRequest backward compat: NPE | OcidResolutionService로 해결 | ✅ |
| 5 | OCID resolution async but sync needed | OcidResolutionService 동기 설계 | ✅ |
| 6 | No idempotency key → double-toggle on retry | DB UNIQUE constraint + Fingerprint | ✅ |

이 예측들은 7장의 모든 작업을 견인했다.

---

## 7.2 ADR-344: Direct DB 토글 서비스

2026년 3월 28일, Like 도메인의 **가장 최근의 아키텍처 결정**이 이루어졌다.

```
6756cb75 feat(like): Direct DB 토글 서비스 (ADR-344) (#622)
```

### 배경

Redis를 제거한 후, Like 토글은 더 이상 Lua Script를 사용하지 않았다. 대신 **In-Memory Buffer + PGMQ + DB 동기화**의 조합이었는데, 여전히 복잡했다:

```
Before (PostgreSQL with PGMQ):
  Toggle → InMemory Buffer → PGMQ Publish → Consumer → DB Write
           ↕                             ↕
       로컬 상태                     메시지 유실 가능
```

### 새로운 접근: Direct DB Toggle

```
After (Direct DB):
  Toggle → 단일 DB 트랜잭션 → 완료
           │
           ├─ INSERT character_like (좋아요)
           │  ON CONFLICT DO NOTHING
           │
           ├─ 또는 DELETE character_like (취소)
           │
           └─ UPDATE game_character.like_count
              (DB Trigger로 자동)
```

**핵심 아이디어**: 인프라가 PostgreSQL 하나라면, 왜 버퍼와 메시지 큐를 거치는가? DB에 직접 쓰는 것이 **가장 단순하고 가장 정확**하다.

### LikeToggleService 설계

```java
@Transactional
public LikeToggleWithCount toggleLike(String targetUserIgn, AuthenticatedUser user) {
    // 1. OCID 해석
    String targetOcid = ocidResolutionService.resolve(targetUserIgn);

    // 2. Self-like 방지
    validateNotSelfLike(user.myOcids(), targetOcid);

    // 3. 토글 실행 (단일 트랜잭션)
    boolean liked = executeToggle(user.accountId(), targetOcid, targetUserIgn);

    // 4. 현재 카운트 조회 (Trigger 보장)
    long likeCount = getLikeCount(targetUserIgn);

    return new LikeToggleWithCount(liked, likeCount);
}
```

### Port Abstraction

```java
// Port (interface)
public interface LikeProcessor {
    boolean like(String accountId, String targetOcid, String userIgn);
    boolean unlike(String accountId, String targetOcid, String userIgn);
}

// Adapter (implementation)
@Service
public class DatabaseLikeProcessor implements LikeProcessor {

    @Override
    public boolean like(String accountId, String targetOcid, String userIgn) {
        try {
            characterLikeRepository.save(
                new CharacterLike(targetOcid, accountId));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // 이미 좋아요 상태
        }
    }

    @Override
    public boolean unlike(String accountId, String targetOcid, String userIgn) {
        return characterLikeRepository
            .deleteByTargetOcidAndLikerAccountId(targetOcid, accountId) > 0;
    }
}
```

---

## 7.3 OCID Resolution 안정화

3월 28일, OCID 캐시 버그와 인증 문제가 해결되었다.

```
672d3690 fix(like): OCID cache bug + 401 auth + E2E test (#661)
```

### 발견된 문제들

| 문제 | 원인 | 해결 |
|------|------|------|
| OCID resolution이 GZIP blob 반환 | L2 Cache에 GZIP 압축된 데이터가 저장 | Serializer 수정 |
| 401 Unauthorized 미처리 | JWT filter가 예외를 던지지 않음 | `sendError(401)` 추가 |
| ThreadLocal leak | L2 cache 조회 시 ThreadLocal 미정리 | `finally` 블록에서 정리 |

### E2E 테스트 추가

실제 서버를 기동하고 좋아요 토글을 테스트하는 E2E 스크립트가 추가되었다:

```bash
# E2E Like Toggle Test
# Step 1: 좋아요 토글 (Like)
# Step 2: 좋아요 수 확인 (+1)
# Step 3: 좋아요 토글 (Unlike)
# Step 4: 좋아요 수 확인 (원래대로)
```

---

## 7.4 Fingerprint Identity & DB Trigger

3월 29일, Like 도메인의 **가장 최신**이자 **가장 중요한** 보안/정합성 개선이 이루어졌다.

```
06952c40 feat(like): fingerprint identity + DB trigger for like_count atomicity (#662-#665) (#666)
```

이것은 **4개의 이슈를 동시에 해결**하는 대형 PR이었다.

### 이슈 #662 (P0): Fingerprint 컬럼

> *"[P0][Security] game_character fingerprint 컬럼 추가 — self-like 방지 정확도 향상"*

**문제**: 기존 self-like 방지는 사용자의 **현재 로그인 캐릭터 1개**만 차단했다. 한 계정이 여러 캐릭터를 가질 수 있으므로, 다른 캐릭터로 자기 자신에게 좋아요를 누를 수 있었다.

**해결**: `fingerprint` 컬럼으로 같은 기기의 모든 캐릭터를 식별:

```sql
ALTER TABLE game_character ADD COLUMN fingerprint VARCHAR(64);
ALTER TABLE game_character ADD COLUMN account_id VARCHAR(64);
```

JWT에서 fingerprint를 추출하고, 같은 fingerprint의 모든 OCID를 차단:

```kotlin
// JWT Filter에서:
val fingerprintOcids = characterOcidPort.resolveOcidsByFingerprint(fingerprint)
val allMyOcids = fingerprintOcids + myOcid

// LikeToggleService에서:
validateNotSelfLike(allMyOcids, targetOcid)
```

### 이슈 #664 (P1): like_count 원자성

> *"[P1][Data-Integrity] like_count와 character_like 테이블 불일치 — DB Trigger 도입 필요"*

**문제**: `INSERT character_like`와 `UPDATE game_character.like_count`가 별도 SQL이었다. 둘 사이에 실패하면 count가 틀어진다.

**해결**: DB Trigger로 원자성 보장:

```sql
CREATE OR REPLACE FUNCTION fn_like_count_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) + 1, 0)
        WHERE ocid = NEW.target_ocid;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) - 1, 0)
        WHERE ocid = OLD.target_ocid;
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_like_count
    AFTER INSERT OR DELETE ON character_like
    FOR EACH ROW EXECUTE FUNCTION fn_like_count_trigger();
```

이 Trigger 덕분에 애플리케이션 코드에서 `incrementLikeCount()`를 완전히 제거할 수 있었다. **DB가 정합성을 보장**한다.

### 이슈 #665: 캐시 정합성

> *"arch(like): cache coherency failure — liked/likeCount split-brain state"*

Direct DB 방식에서는 캐시가 관여하지 않으므로, 이 이슈는 #664의 Trigger로 자동 해결되었다.

### 3차 Consensus Review

이 PR은 **3차례의 다중 에이전트 리뷰**를 거쳤다:

| 차수 | Architect | Critic | Code-Reviewer |
|------|-----------|--------|---------------|
| 1차 | REVISE | REVISE | REVISE |
| 2차 | APPROVE | REVISE | APPROVE |
| 3차 | APPROVE | REVISE(수용) | APPROVE |

**주요 논쟁점**:

1. **DIP 위반**: Filter→Repository 직접 의존 → `CharacterOcidPort`로 이동 (만장일치)
2. **ISP**: CharacterOcidPort에 write 메서드 포함 → 실용적 타협 (2/3 APPROVE)
3. **Trigger+앱 double-count**: Rolling update 중 일시적 이중 카운트 → reconciliation으로 해결
4. **TOCTOU**: fingerprintOcids 빈 집합 → 기존보다 나빠지지 않음 (회귀 아님)

---

## 7.5 Nexon API 인증 검증

3월 29일, 보안 강화의 마지막 조각이 추가되었다.

```
4c9b3652 fix(auth): validate API key via Nexon API on login (#668)
```

이슈 #667 (P0), PR #668:
> *"[P0][Auth] Login 시 Nexon API 계정 검증 누락 — API Key 무제한 수용으로 Like 시스템 무력화 가능"*

**문제**: API Key를 아무나 생성할 수 있었다. 유효한 Nexon 계정인지 확인하지 않았기 때문에, 가짜 계정으로 self-like를 우회할 수 있었다.

**해결**: Login 시 Nexon API를 호출하여 실제 계정인지 검증.

---

## 7.6 Scale-out Data Integrity

3월 29일, 4개의 P1 이슈가 해결되었다.

```
542f69b4 feat: scale-out data integrity for 4 P1 issues (#632-#635)
```

| 이슈 | 문제 | 해결 |
|------|------|------|
| #626 (P0) | Like Buffer Race Condition — fetchAndClear 비원자적 스냅샷 | Synchronized + ConcurrentMap |
| #627 (P0) | Carrier Thread Pinning — Caffeine synchronized + Virtual Thread | ReentrantLock 교체 |
| #633 (P1) | EquipmentPersistenceTracker 인메모리 상태 → Scale-out 불가 | PostgreSQL 전환 |
| #635 (P1) | Circuit Breaker 오픈 시 Like 카운트 유실 | Buffer 복구 메커니즘 |

---

## 7.7 최종 아키텍처 (2026년 3월 31일)

```
┌──────────────────────────────────────────────────────────────┐
│                       module-app                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │  LikeToggleService                                       ││
│  │  ├─ OCID Resolution (OcidResolutionService)              ││
│  │  ├─ Self-Like Prevention (fingerprint-based)             ││
│  │  ├─ Atomic Toggle (DB Transaction)                       ││
│  │  └─ Count Query (Trigger-guaranteed)                     ││
│  │                                                          ││
│  │  DatabaseLikeProcessor                                   ││
│  │  ├─ INSERT ON CONFLICT DO NOTHING                        ││
│  │  └─ DELETE + Trigger auto-decrement                      ││
│  └────────────────────┬─────────────────────────────────────┘│
│                       │ (Port)                                │
├───────────────────────┼──────────────────────────────────────┤
│  module-core          │              module-infra             │
│  ┌────────────────────▼─────────────────────────────────────┐│
│  │  Domain Model          │  Adapters                       ││
│  │  CharacterLike         │  InMemoryLikeBufferStorage      ││
│  │  LikeToggleResult      │  LikeSyncExecutor                ││
│  │  LikeToggleWithCount   │  OcidResolutionAdapter           ││
│  │                        │  CharacterOcidAdapter            ││
│  │  Ports (Out)           │                                  ││
│  │  LikeAtomicFetch       │  PostgreSQL                      ││
│  │  CharacterOcidPort     │  ├─ character_like (relations)   ││
│  │  CompensationCommand   │  ├─ game_character (counts)      ││
│  │                        │  ├─ like_buffer (UNLOGGED)       ││
│  │                        │  ├─ fn_like_count_trigger()      ││
│  │                        │  └─ PGMQ (events)                ││
│  └────────────────────────┴──────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

### Like 토글의 최종 흐름

```
POST /api/v2/characters/{userIgn}/like
  │
  ├─1─→ JWT Authentication (fingerprint + allMyOcids)
  │
  ├─2─→ OCID Resolution (L1 Cache → L2 Cache → DB → Nexon API)
  │
  ├─3─→ Self-Like Check (fingerprintOcids ∋ targetOcid?)
  │
  ├─4─→ DB Transaction:
  │     ├─ INSERT character_like ON CONFLICT DO NOTHING → liked=true
  │     └─ 또는 DELETE character_like → liked=false
  │
  ├─5─→ DB Trigger: like_count 자동 ±1
  │
  └─6─→ Response: { liked: boolean, likeCount: long }
```

**단일 DB 트랜잭션 안에서 모든 것이 원자적으로 처리된다.** Lua Script도, Redis도, PGMQ도 필요 없다.

---

## 7.8 133일의 여정 요약

| 단계 | 기간 | 아키텍처 | 인프라 |
|------|------|----------|--------|
| 탄생 | 2025.11-12 | 모놀리식 | MySQL + Caffeine |
| 원자성 | 2026.01 초 | 모놀리식 + Proxy | MySQL + Redis Lua |
| Scale-out | 2026.01 말 | 모놀리식 | MySQL + Redis Pub/Sub |
| 모듈 분리 | 2026.02 | 멀티모듈 | MySQL + Redis + Kotlin |
| 헥사고날 | 2026.03 초 | Hexagonal | MySQL + Redis + Kotlin |
| PostgreSQL | 2026.03 중 | Hexagonal | PostgreSQL + PGMQ + Kotlin |
| Direct DB | 2026.03 말 | Hexagonal | PostgreSQL (Trigger) + Kotlin |

### 근본적 변화

```
2025년 11월: Controller → DB (동기, 원자성 없음)
2026년 1월:  Controller → Redis Lua Script → Scheduler → DB
2026년 3월:  Controller → DB Transaction + Trigger → 완료
```

**결국 가장 단순한 형태로 회귀했다.** 하지만 그 회귀는 가능했다 — 133일간의 학습, 실패, 그리고 아키텍처의 진화가 있었기에.
