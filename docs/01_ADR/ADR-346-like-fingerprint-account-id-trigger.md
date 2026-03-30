# ADR-346: Like Domain — Fingerprint Identity + DB Trigger 원자성

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 승인됨 (Accepted) |
| 결정일 | 2026-03-29 |
| 결정자 | probabilistic-valuation-engine Team |
| 검토자 | Architecture Review Board (3-round Consensus Review) |
| 선행 ADR | ADR-005 Hexagonal Architecture, ADR-029 Like Direct DB |
| 관련 이슈 | #662, #663, #664, #665 |

---

## 1. 배경 (Context)

Like 도메인에 4개의 이슈가 존재:

| 이슈 | 심각도 | 문제 |
|------|--------|------|
| #662 | P0 Security | `game_character`에 fingerprint 컬럼 없어 self-like 방지가 불완전 (현재 로그인 캐릭터 1개만 차단) |
| #663 | P1 Architecture | account_id identity 없어 BulkLoader 중복 삽입 가능 |
| #664 | P1 Data Integrity | `insertIfAbsent()` + `incrementLikeCount()`가 별도 SQL → 예외 시 count 불일치 |
| #665 | Architecture | 캐시 정합성 — 현재 direct DB 방식이므로 실질적으론 count drift 문제 (#664로 해결) |

### 핵심 제약

- **account_id = VARCHAR(64)** = fingerprint (API Key HMAC-SHA256 해시). BIGINT 불필요
- **2단계 배포**: Flyway migration과 앱 코드 변경을 동일 PR에 포함 (무중단 불가피한 트레이드오프)
- **Reconciliation**: rolling update 중 일시적 double-count 가능하나 migration 내 reconciliation 쿼리로 즉시 수정

---

## 2. 결정 (Decision)

### Phase 1: Schema + Identity (#662 + #663)

1. `game_character`에 `fingerprint VARCHAR(64)` + `account_id VARCHAR(64)` 컬럼 추가 (V103)
2. Covering index: `(fingerprint, ocid) WHERE fingerprint IS NOT NULL`
3. Partial unique index: `uk_account_user_ign ON (account_id, user_ign) WHERE account_id IS NOT NULL`
4. JWT Filter에서 fingerprint 기반 모든 OCID 조회 → multi-character self-like 방지
5. Lazy backfill: fingerprint NULL인 캐릭터에만 stamp (`WHERE fingerprint IS NULL`)
6. `fingerprintOcidsCache` L1/L2 캐시 + pub/sub 무효화 (scale-out)

### Phase 2: like_count Trigger (#664)

1. `fn_like_count_trigger()`: `character_like` INSERT/DELETE 시 `game_character.like_count` 자동 증감 (V104)
2. `LikeToggleService`에서 `incrementLikeCount()` 호출 제거 (trigger가 대체)
3. Reconciliation: `pg_try_advisory_xact_lock`으로 rolling update 중 deadlock 방지

### Phase 3: #665 검증

- 현재 direct DB 읽기 방식이므로 캐시 무관
- Phase 2 trigger로 like_count drift 해결

---

## 3. 근거 (Rationale)

### 왜 DB Trigger인가?

| 대안 | 단점 |
|------|------|
| App-level increment (현행) | INSERT/UPDATE 분리 → 예외 시 count 불일치 |
| Redis INCR | Redis 제거됨 (ADR-022) |
| PostgreSQL Function 호출 | App 코드에서 명시적 호출 필요, 누락 시 drift |
| **DB Trigger** | INSERT/DELETE와 원자적, app 코드 무관, 누락 불가 |

### 왜 Lazy Backfill인가?

- 전체 데이터 일괄 UPDATE는 대규모 테이블에서 위험
- 매 요청마다 fingerprint를 stamp하는 것은 불필요한 UPDATE 발생
- NULL인 경우만 + 아직 stamp되지 않은 OCID인 경우만 → idempotent + 최소 UPDATE

### 왜 account_id = fingerprint인가?

- Nexon API에서 진짜 계정 ID를 제공하지 않음
- API Key의 HMAC-SHA256 해시가 현재 유일한 사용자 식별 수단
- 동일 API Key = 동일 fingerprint = 동일 account_id

---

## 4. 결과 (Consequences)

### 긍정적

- Self-like가 fingerprint 기반 모든 캐릭터에 적용 (#662 해결)
- account_id unique index로 중복 삽입 방지 (#663 해결)
- Trigger로 like_count 원자성 보장 (#664 해결)
- Direct DB 읽기 + trigger로 캐시 정합성 무관 (#665 해결)

### 부정적

- Rolling update 중 일시적 double-count 가능 (reconciliation으로 복구)
- fingerprint = API Key 해시이므로 API Key 변경 시 새 identity (기존보다 나빠지지 않음)
- DB Trigger로 인한 약간의 쓰기 지연 증가 (per-row trigger)

### 위험 완화

| 위험 | 완화 |
|------|------|
| Rolling update double-count | Reconciliation 쿼리 (pg_try_advisory_xact_lock) |
| Unique index 위반 | DuplicateKeyException catch + warn 로그 |
| Scale-out 캐시 불일치 | Pub/sub pattern + @CacheEvict |

---

## 5. Consensus Review 결과

3-round (9 에이전트) Consensus Review 완료:

### Cross-Agent Convergence (3/3 동의)

| 이슈 | 합의 해결책 |
|------|-------------|
| DIP 위반: Filter→Repository 직접 의존 | `CharacterOcidPort`로 이동 |
| SQL 오타: `WHERE account IS NOT NULL` | `account_id`로 수정 |
| Lazy backfill 매 요청 UPDATE | NULL + 미포함 시만 실행 |

### Key Divergence (판단)

| 관점 | 판단 |
|------|------|
| fingerprint as identity | Lazy backfill로 해결. 기존 상태보다 나빠지지 않음 |
| ISP: CharacterOcidPort에 write 메서드 | 현행 유지 (프로젝트 관행과 일치) |

---

## 6. 롤백 계획

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
