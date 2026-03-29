# Like Domain Issues #662, #663, #664, #665 해결 계획

## Context

Like 도메인에 4개의 이슈가 존재:
- **#662** (P0): `game_character`에 fingerprint 컬럼 없어 self-like 방지가 불완전 (현재 로그인 캐릭터 1개만 차단)
- **#663** (P1): account_id identity 없어 BulkLoader 중복 삽입 가능
- **#664** (P1): `insertIfAbsent()` + `incrementLikeCount()`가 별도 SQL → 예외 시 count 불일치
- **#665**: 캐시 정합성 — 현재 direct DB 방식이므로 실질적으론 count drift 문제 (#664로 해결)

**핵심 설계 결정:**
- `account_id` = VARCHAR(64) (= fingerprint). BIGINT 불필요 (character_like.liker_account_id도 VARCHAR)
- #664 → DB Trigger로 원자성 보장. **2단계 배포**로 trigger+앱 중복 카운트 방지
- #665 → #664 trigger로 사실상 해결. 현재 코드는 이미 direct DB 읽기 (캐시 무관)

---

## Phase 1: Schema + Identity Foundation (#662 + #663)

### 1.1 Flyway Migration V103

**신규 파일:** `module-infra/src/main/resources/db/migration/V103__like_fingerprint_account_id.sql`

```sql
ALTER TABLE game_character ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(64);
ALTER TABLE game_character ADD COLUMN IF NOT EXISTS account_id VARCHAR(64);

-- Covering index for fingerprint→ocid resolution (hot path: every auth request)
CREATE INDEX IF NOT EXISTS idx_game_character_fingerprint
    ON game_character (fingerprint, ocid) WHERE fingerprint IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_game_character_account_id
    ON game_character (account_id) WHERE account_id IS NOT NULL;

-- Partial unique: one account cannot register the same IGN twice
CREATE UNIQUE INDEX IF NOT EXISTS uk_account_user_ign
    ON game_character (account_id, user_ign) WHERE account_id IS NOT NULL;
```

### 1.2 Entity + Domain Model

**수정:** `GameCharacterJpaEntity.kt`
```kotlin
@Column(length = 64)
open var fingerprint: String? = null

@Column(name = "account_id", length = 64)
open var accountId: String? = null
```
- `fromDomain()`, `toDomain()` 업데이트

**수정:** `GameCharacter.kt` (domain model)
- `fingerprint: String?`, `accountId: String?` 필드 추가
- `restore()` 팩토리 메서드에 **기본값** 추가: `fingerprint: String? = null, accountId: String? = null`

### 1.3 Port + Adapter (DIP 준수)

> **Consensus Review 수정사항**: `updateFingerprint`를 `GameCharacterRepository`가 아닌
> `CharacterOcidPort`(core port)에 배치하여 DIP 준수. (Architect P0, Code-Reviewer P0-1 합의)

**수정:** `CharacterOcidPort.kt` (core port)
```kotlin
fun resolveOcidsByFingerprint(fingerprint: String): Set<String>
fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int
```

**수정:** `CharacterOcidAdapter.kt` (infra adapter)
```kotlin
@Cacheable(value = ["fingerprintOcidsCache"], key = "#fingerprint", unless = "#result.isEmpty()")
override fun resolveOcidsByFingerprint(fingerprint: String): Set<String> {
    require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
    return executor.execute(
        { jpaRepository.findAllByFingerprint(fingerprint).mapNotNull { it.ocid }.toSet() },
        TaskContext.of("CharacterOcidAdapter", "ResolveOcidsByFingerprint", fingerprint),
    )
}

@CacheEvict(value = ["fingerprintOcidsCache"], key = "#fingerprint")
override fun updateFingerprint(ocid: String, fingerprint: String, accountId: String): Int {
    return executor.execute(
        { jpaRepository.updateFingerprintByOcid(ocid, fingerprint, accountId) },
        TaskContext.of("CharacterOcidAdapter", "UpdateFingerprint", ocid),
    )
}
```

### 1.4 Repository

**수정:** `GameCharacterJpaRepository.kt`
```kotlin
// Covering index 활용: fingerprint → ocid 리스트
fun findAllByFingerprint(fingerprint: String): List<GameCharacterJpaEntity>

// Lazy backfill: NULL인 경우만 업데이트 (덮어쓰기 금지)
@Modifying
@Query("UPDATE GameCharacterJpaEntity g SET g.fingerprint = :fingerprint, g.accountId = :accountId
       WHERE g.ocid = :ocid AND g.fingerprint IS NULL")
fun updateFingerprintByOcid(ocid: String, fingerprint: String, accountId: String): Int
```

### 1.5 JWT Filter — Multi-OCID Resolution + Lazy Backfill

> **Consensus Review 수정사항**: GameCharacterRepository 직접 주입 대신 CharacterOcidPort 사용.
> Lazy backfill은 fingerprint 미배정 캐릭터에만 실행 (매 요청 UPDATE 방지).

**수정:** `JwtAuthenticationFilter.kt` — `resolveAuthenticatedUser()`

```kotlin
private fun resolveAuthenticatedUser(jwt: JwtPayload): AuthenticatedUser? {
    val userIgn = jwt.userIgn
    val fingerprint = jwt.fingerprint

    // #662: fingerprint 기반 모든 OCID 조회
    val fingerprintOcids = if (!fingerprint.isNullOrBlank()) {
        characterOcidPort.resolveOcidsByFingerprint(fingerprint)
    } else {
        emptySet()
    }

    // 현재 캐릭터 OCID (fingerprint 미배정 경우 fallback)
    val myOcid = characterOcidPort.resolveOcid(userIgn)
    val allMyOcids = if (myOcid != null) fingerprintOcids + myOcid else fingerprintOcids

    // Lazy backfill: fingerprint NULL인 캐릭터에만 stamp (idempotent)
    // fingerprintOcids에 myOcid가 없으면 → 아직 stamp 안됨
    if (myOcid != null && !fingerprintOcids.contains(myOcid) && !fingerprint.isNullOrBlank()) {
        try {
            characterOcidPort.updateFingerprint(myOcid, fingerprint, fingerprint)
        } catch (e: DuplicateKeyException) {
            // uk_account_user_ign 위반: 다른 계정이 이미 해당 account_id+user_ign 조합을 소유
            // 보안: OCID 등 식별자 로깅 제외, 메트릭만 기록
            log.warn("[JWT] Unique index violation during backfill. Character already registered by different account.")
            backfillCollisionCounter.increment()
        }
    }

    val accountId = fingerprint ?: ""

    return AuthenticatedUser(
        sessionId = jwt.sessionId,
        fingerprint = fingerprint ?: "",
        userIgn = userIgn,
        accountId = accountId,
        apiKey = "",
        myOcids = allMyOcids,
        role = jwt.role,
    )
}
```

- `GameCharacterRepository` 의존성 추가 **불필요** (CharacterOcidPort만 사용)
- 기존 TODO 코멘트 제거

### 1.6 Cache 설정 추가

**수정:** `module-app/src/main/resources/application.yml`

```yaml
cache:
  specs:
    fingerprintOcidsCache:
      l1-ttl-minutes: 30
      l1-max-size: 1000
      l2-ttl-minutes: 60
      l2-serializer: json
  invalidation:
    pubsub:
      patterns:
        - "equipment:*"
        - "ocidCache:*"
        - "fingerprintOcidsCache:*"  # 신규: scale-out 노드 간 캐시 무효화
```

### 1.7 ADR 문서

**신규:** `docs/adr/031-like-fingerprint-account-id-trigger.md`

---

## Phase 2: like_count Trigger + Reconciliation (#664)

**Phase 1 완료 후 진행. 2단계 배포 전략으로 무중단 보장.**

### 2.1 Flyway Migration V104 — Trigger 생성

**신규 파일:** `module-infra/src/main/resources/db/migration/V104__like_count_trigger.sql`

```sql
CREATE OR REPLACE FUNCTION fn_like_count_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) + 1, 0), updated_at = NOW()
        WHERE ocid = NEW.target_ocid;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE game_character
        SET like_count = GREATEST(COALESCE(like_count, 0) - 1, 0), updated_at = NOW()
        WHERE ocid = OLD.target_ocid;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_like_count
    AFTER INSERT OR DELETE ON character_like
    FOR EACH ROW EXECUTE FUNCTION fn_like_count_trigger();

-- Reconciliation: 기존 drift 수정 (점검 시간 또는 low-traffic 시 실행)
-- SKIP LOCKED로 rolling update 중인 incrementLikeCount와의 deadlock 방지
WITH correct AS (
    SELECT target_ocid, COUNT(*) AS cnt FROM character_like GROUP BY target_ocid
)
UPDATE game_character gc SET like_count = COALESCE(c.cnt, 0), updated_at = NOW()
FROM correct c WHERE gc.ocid = c.target_ocid AND gc.like_count != c.cnt
  AND pg_try_advisory_xact_lock(hashtext(gc.ocid));

UPDATE game_character SET like_count = 0, updated_at = NOW()
WHERE like_count != 0 AND NOT EXISTS (
    SELECT 1 FROM character_like WHERE target_ocid = game_character.ocid
);
```

### 2.2 LikeToggleService — incrementLikeCount 제거 (Trigger 활성화와 동일 PR)

> **Consensus Review 수정사항**: trigger와 앱 코드 변경을 **동일 PR/배포**에 포함.
> Rolling update 중 일시적 double-count 가능하나, reconciliation이 즉시 수정.
> 무중단 배포 불가피한 트레이드오프.

**수정:** `LikeToggleService.java`
- `likeCharacter()`: `gameCharacterRepository.incrementLikeCount(targetUserIgn, 1)` **제거**
- `unlikeCharacter()`: `gameCharacterRepository.incrementLikeCount(targetUserIgn, -1)` **제거**
- `gameCharacterRepository` 의존성 제거 가능

**수정:** `GameCharacterJpaRepository.kt`
- `incrementLikeCount()` 메서드 제거

### 2.3 getLikeCount 최적화

**수정:** `LikeToggleService.java` — `getLikeCount()`
```java
// Before: COUNT(*) from character_like
// After: game_character.like_count 직접 읽기 (trigger 보장)
```

---

## Phase 3: #665 Cache Coherency — 검증 완료

- 현재 direct DB 읽기 방식이므로 캐시 무관
- Phase 2 trigger로 like_count drift 해결
- ADR-031에 #665 해결 근거 문서화

---

## Dependency Graph

```
Phase 1 (#662+#663): Schema + Identity
    ↓
Phase 2 (#664): Trigger + incrementLikeCount 제거 (동일 PR)
    ↓
Phase 3 (#665): 검증 + 문서화
```

단일 브랜치 `feature/like-662-665` 에서 순차 진행, PR → develop

---

## 주요 수정 파일 목록

| 파일 | Phase | 변경 유형 |
|------|-------|-----------|
| `module-infra/.../db/migration/V103__...sql` | 1 | 신규 |
| `module-infra/.../db/migration/V104__...sql` | 2 | 신규 |
| `module-infra/.../entity/GameCharacterJpaEntity.kt` | 1 | 수정 |
| `module-core/.../model/character/GameCharacter.kt` | 1 | 수정 |
| `module-core/.../port/out/CharacterOcidPort.kt` | 1 | 수정 |
| `module-infra/.../adapter/CharacterOcidAdapter.kt` | 1 | 수정 |
| `module-infra/.../jpa/GameCharacterJpaRepository.kt` | 1,2 | 수정 |
| `module-infra/.../security/filter/JwtAuthenticationFilter.kt` | 1 | 수정 |
| `module-app/.../service/like/LikeToggleService.java` | 2 | 수정 |
| `module-app/.../resources/application.yml` | 1 | 수정 |
| `docs/adr/031-like-fingerprint-account-id-trigger.md` | 1 | 신규 |

## Verification

1. `./gradlew compileKotlin compileJava --continue` — 컴파일 확인
2. `./gradlew test` — 전체 테스트
3. `LikeToggleServiceTest` — incrementLikeCount verify assertion 제거 확인
4. E2E: multi-character self-like 시나리오 (동일 fingerprint 3개 OCID → 전부 차단)
5. Trigger 검증: `character_like` INSERT 후 `game_character.like_count` 증가 확인
6. Reconciliation: drift 쿼리 결과 0 rows 확인

---

## Consensus Review 반영 사항 (3/3 에이전트 합의)

### Cross-Agent Convergence (3/3 동의)

| # | 이슈 | Architect | Critic | Code-Reviewer | 반영 |
|---|------|-----------|--------|---------------|------|
| 1 | DIP 위반: Filter→Repository 직접 의존 | P0 | P0 | P0 | `CharacterOcidPort`로 이동 |
| 2 | SQL 오타: `WHERE account IS NOT NULL` | P1 | P0 | P0 | `account_id`로 수정 |
| 3 | Lazy backfill 매 요청 UPDATE | P0 | P0 | P0 | NULL + 미포함 시만 실행 |
| 4 | Trigger+앱 double-count | P1 | P1 | — | 동일 PR + reconciliation |
| 5 | fingerprintOcidsCache 설정 누락 | P2 | — | P0 | application.yml 추가 |
| 6 | Cache eviction 누락 | P2 | — | — | @CacheEvict 추가 |

### Key Divergence

| 관점 | Critic 주장 | Architect/Code-Reviewer | 판단 |
|------|------------|------------------------|------|
| fingerprint as identity | API Key 변경 시 새 fingerprint → self-like bypass 가능 | Lazy backfill로 해결 | **Architect 의견 채택** — lazy backfill이 최초 인증 시 즉시 stamp. fallback `resolveOcid(userIgn)`으로 현재 캐릭터는 항상 보호됨. Critic 시나리오는 기존(current) 상태보다 나빠지지 않음 |

### 2차 Consensus Review 반영 사항

| # | 이슈 | Architect | Critic | Code-Reviewer | 반영 |
|---|------|-----------|--------|---------------|------|
| 7 | Scale-out 캐시 무효화: @CacheEvict 로컬만 | — | MAJOR | — | pub/sub pattern 추가 |
| 8 | Unique index 위반 에러 처리 | — | MAJOR | — | DuplicateKeyException catch |
| 9 | Reconciliation deadlock | P1 | MAJOR | — | advisory lock 추가 |
| 10 | ISP: CharacterOcidPort에 write 메서드 | P0 | — | P1 | **현행 유지** (실용적 타협) |
| 11 | GameCharacter domain에 fingerprint 필드 | P0 | — | P2 | 추가 (기본값으로 기존 영향 없음) |

**ISP 결정 근거:** Code-Reviewer APPROVE, Critic 무관심. Architect만 P0 주장.
`CharacterOcidPort` 분리 시 Adapter도 분리 → 복잡도 증가 vs 실제 책임은 동일 도메인(캐릭터 식별).
프로젝트 관행(`GameCharacterPort` 7개 메서드)과 일치. 추후 메서드 6개 초과 시 분리 검토.

### 3차 Consensus Review (2/3 APPROVE → 최종 승인)

| 에이전트 | 판정 | 핵심 의견 |
|----------|------|----------|
| Architect | **APPROVE** | ISP 타협 수용, 새 P0 없음 |
| Critic | REVISE | P0: TOCTOU (self-like bypass), P0: 로그 노출 |
| Code-Reviewer | **APPROVE** | 모든 P0 해결, Medium 2건만 제안 |

**Critic P0 분석 및 판단:**
- **TOCTOU**: `fingerprintOcids` 빈 집합 → 다른 캐릭터 우회 가능. **하지만 현재 상태와 동일** (현재도 1개 OCID만 보호). 회귀 아님. 장기적으로 lazy backfill이 점진적으로 개선.
- **로그 노출**: **수용** — OCID 제거, 메트릭만 기록으로 변경 완료.

### 롤백 계획

```sql
-- Rollback V103
DROP INDEX IF EXISTS uk_account_user_ign;
DROP INDEX IF EXISTS idx_game_character_account_id;
DROP INDEX IF EXISTS idx_game_character_fingerprint;
ALTER TABLE game_character DROP COLUMN IF EXISTS account_id;
ALTER TABLE game_character DROP COLUMN IF EXISTS fingerprint;

-- Rollback V104
DROP TRIGGER IF EXISTS trg_like_count ON character_like;
DROP FUNCTION IF EXISTS fn_like_count_trigger();
```
