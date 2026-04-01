# 8장: 좋아요의 역습 — Like 도메인 레이스 컨디션

> "사용자는 '좋아요'를 눌렀는데, 화면에는 '좋아요 취소'로 보였다. 그리고 카운트는 0이었다."
>
> — Issue #665, Cache coherency failure

---

## 발생: 좋아요가 거짓말을 한 날

2026년 3월, 사용자 불만이 접수되기 시작했다.

"좋아요를 눌렀는데 안 눌렸다고 나와요."
"좋아요 숫자가 이상해요. 누를 때마다 1, 0, 1, 0 왔다 갔다 해요."
"좋아요를 누른 상태인데 카운트가 0이에요."

**liked 상태와 like_count가 서로 달랐다.** 사용자가 좋아요를 누른 상태인데 카운트는 0. 좋아요를 취소했는데 카운트는 1.

이건 단순한 버그가 아니었다. **데이터 무결성 문제**였다.

---

## 탐지: Split-Brain

좋아요 데이터는 두 곳에 저장되어 있었다.

```
liked 상태: character_like 테이블 (사용자가 눌렀는지 여부)
like_count: game_character 테이블 (총 좋아요 수)
```

이 두 값이 항상 일치해야 한다. 하지만 그렇지 않았다.

```sql
-- 사용자 1234가 캐릭터 5678에 좋아요
SELECT * FROM character_like WHERE user_id = 1234 AND character_id = 5678;
-- 결과: 1 row (좋아요 상태)

SELECT like_count FROM game_character WHERE id = 5678;
-- 결과: 0 ← ??? 분명 좋아요를 눌렀는데 카운트가 0
```

---

## 분석: 6가지 근본 원인

이 문제를 분석하면서 6가지 근본 원인을 발견했다. 이것은 장애대응 테스트 전에 미리 예측(pre-commitment prediction)한 것과 일치했다.

### 1. 레이스 컨디션: 버퍼 갱신과 PGMQ 발행의 간극

```
스레드 A: 좋아요 버퍼 갱신 (+1) ← 성공
스레드 A: PGMQ에 메시지 발행 ← 실패!
→ 버퍼는 +1인데 DB에는 반영 안 됨
```

버퍼 갱신과 메시지 발행이 **같은 트랜잭션 안에 없었다.** 하나는 성공하고 하나는 실패하면 불일치가 발생한다.

### 2. 중복 토글: 멱등성 키 없음

```
사용자: 좋아요 클릭
  → 요청 1 전송
  → 네트워크 지연
  → 요청 1 재시도 (클라이언트 자동 재시도)
  → 요청 1 도달: liked = true
  → 재시도 요청 도달: liked = false ← 토글!
→ 사용자는 좋아요를 눌렀는데 취소됨
```

멱등성 키가 없으면 같은 요청이 두 번 처리되어 토글이 발생한다.

### 3. 캐시 불일치: liked와 like_count의 분리

```
L1 캐시 (Caffeine):
  liked: true (갱신됨)
  like_count: 0 (아직 갱신 안 됨)

→ 사용자에게: "좋아요 누름" + "카운트 0"
```

liked와 like_count가 별도의 캐시 키로 관리되어 갱신 시점이 달랐다.

### 4. Scale-out: Caffeine 분기

인스턴스가 2개 이상이면 각 인스턴스의 Caffeine 캐시가 독립적이다.

```
인스턴스 1 Caffeine: liked = true, count = 1
인스턴스 2 Caffeine: liked = false, count = 0

사용자가 로드밸런서를 통해 인스턴스 2에 연결되면
→ "좋아요 안 누름"으로 표시
```

### 5. OCID 해상도 레이스

OCID(캐릭터 식별자)를 비동기로 조회하는데, 좋아요 처리는 동기가 필요한 경우가 있었다.

```
OCID 조회 (비동기): 진행 중...
좋아요 처리 (동기 필요): OCID 없음 → NPE
```

### 6. LikeSyncRequest 하위 호환성

Nullable 필드가 워커에서 NPE를 유발했다.

```java
// LikeSyncRequest
public record LikeSyncRequest(
    String userId,      // null 가능
    Long characterId,   // null 가능
    Boolean liked       // null 가능
) {}

// 워커에서
if (request.liked()) {  // ← null이면 NPE!
    processLike(request);
}
```

---

## 대응: 원자적 연산과 DB 트리거

### 1. 원자적 연산

좋아요 처리를 **하나의 트랜잭션 안에서** 수행.

```sql
-- 단일 트랜잭션에서 수행
BEGIN;
  -- 좋아요 토글
  INSERT INTO character_like (user_id, character_id)
  VALUES (1234, 5678)
  ON CONFLICT (user_id, character_id) DO DELETE;

  -- 카운트 동기화 (DB 트리거)
  -- 트리거가 자동으로 like_count를 갱신
COMMIT;
```

### 2. DB 트리거로 카운트 동기화

```sql
-- character_like INSERT 시 자동 증가
CREATE OR REPLACE FUNCTION sync_like_count() RETURNS TRIGGER AS $$
BEGIN
  IF (TG_OP = 'INSERT') THEN
    UPDATE game_character SET like_count = like_count + 1 WHERE id = NEW.character_id;
  ELSIF (TG_OP = 'DELETE') THEN
    UPDATE game_character SET like_count = like_count - 1 WHERE id = OLD.character_id;
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
```

이제 `character_like`에 INSERT/DELETE가 발생하면 `game_character.like_count`가 자동으로 동기화된다. 애플리케이션에서 따로 처리할 필요가 없다.

### 3. 멱등성 키

```kotlin
fun toggleLike(userId: String, characterId: String, idempotencyKey: String) {
    // 같은 idempotencyKey의 요청은 한 번만 처리
    val processed = processedRequestCache.getIfPresent(idempotencyKey)
    if (processed != null) {
        return processed  // 이미 처리됨
    }

    val result = executeToggle(userId, characterId)
    processedRequestCache.put(idempotencyKey, result)
    return result
}
```

### 4. Self-Like 방지

자기 자신의 캐릭터에 좋아요를 누르는 것을 방지.

```sql
-- fingerprint 컬럼 추가
ALTER TABLE game_character ADD COLUMN fingerprint VARCHAR(64);

-- 좋아요 시 확인
SELECT fingerprint FROM game_character WHERE id = 5678;
-- 본인 fingerprint와 일치하면 차단
```

---

## 장애대응 테스트: Like E2E

Like 도메인 전용 E2E 테스트를 작성했다.

```
Like E2E 테스트 결과:

✅ 좋아요 토글: 정상
✅ 카운트 동기화: liked == like_count 일치
✅ 중복 요청: 멱등성 키로 차단
✅ Self-Like: fingerprint로 차단
✅ 동시 요청 100개: 데이터 무결성 100%
✅ 서킷 브레이커 Open 시: Buffer 보존 후 복구
```

---

## 인증 버그까지

이 장애를 디버깅하다가 401 인증 버그도 발견되었다 (Issue #667).

```
Issue #667: [P0][Auth] Login 시 Nexon API 계정 검증 누락

로그인 시 Nexon API로 계정 검증을 하지 않고 있었다.
→ 존재하지 않는 계정으로 로그인 가능
→ 좋아요 데이터 오염 가능성
```

좋아요 장애를 고치면서 인증까지 고치게 된 것. 하나의 장애가 다른 장애를 드러낸 좋은 사례다.

---

## 교훈

**1. 상태와 카운트는 하나여야 한다.**

liked 상태와 like_count가 별도로 관리되면 불일치가 발생한다. DB 트리거로 강제 동기화하는 것이 가장 안전하다.

**2. 트랜잭션 경계를 명확히 하라.**

버퍼 갱신과 메시지 발행이 같은 트랜잭션 안에 없으면 데이터가 깨진다.

**3. 멱등성 키는 선택이 아니다.**

네트워크 재시도, 사용자 더블클릭 — 중복 요청은 항상 발생한다. 멱등성 키로 방어하라.

**4. 하나의 장애가 다른 장애를 드러낸다.**

좋아요 버그를 고치다가 인증 버그를 발견했다. 장애 대응은 단일 문제 해결이 아니라 시스템 전체의 건강 검진이다.

---

> **다음 장:** [9장: 대이주 — Redis Outbox에서 PGMQ로](09_great_migration.md)
