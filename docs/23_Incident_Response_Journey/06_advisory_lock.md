# 6장: 락의 미로 — Advisory Lock과 교착상태의 함정

> "락은 필요하다. 하지만 락이 죽음의 덫이 될 수도 있다."
>
> — ADR-318, PostgreSQL Advisory Lock

---

## 발생: 아무것도 처리되지 않는 서버

서버는 돌아가고 있었다. CPU도 정상이고, 메모리도 여유가 있었다. 하지만 **아무것도 처리되지 않았다.**

요청은 들어오는데 응답이 나가지 않았다. 로그에는 타임아웃 에러만 반복되었다.

```
[WARN] Advisory lock acquisition timeout after 5000ms for key: 1234567890
[WARN] Advisory lock acquisition timeout after 5000ms for key: 2345678901
[WARN] Advisory lock acquisition timeout after 5000ms for key: 3456789012
... (계속)
```

모든 요청이 락 획득에 실패하고 있었다. 하지만 **아무도 락을 잡고 있지 않았다.**

---

## 탐지: 유령 락

데이터베이스에서 활성 락을 조회했다.

```sql
SELECT * FROM pg_locks WHERE locktype = 'advisory';
```

결과: **0개.** 활성 advisory lock이 없다. 그런데 새 락은 획득되지 않는다.

이건 불가능했다... 고 생각했다.

---

## 분석: 세션 스코프의 저주

원인은 `pg_advisory_lock`의 **세션 스코프**였다.

```sql
-- 세션 스코프 락
SELECT pg_advisory_lock(12345);
-- 획득 성공

-- 트랜잭션 커밋
COMMIT;

-- 락 상태 확인
SELECT * FROM pg_locks WHERE locktype = 'advisory';
-- → 락이 여전히 존재함!

-- 커넥션 반환 (HikariCP)
-- → 락은 여전히 존재함!

-- 다른 요청이 같은 커넥션을 재사용
-- → 다른 요청이 락을 물고 시작함
-- → 하지만 락을 해제할 방법이 없음
-- → 누적 → 모든 커넥션이 "유령 락"을 보유
```

HikariCP는 커넥션을 재사용한다. 커넥션을 반환해도 TCP 연결은 유지된다. `pg_advisory_lock`은 세션(커넥션) 단위로 유지되므로, 커넥션이 살아있는 한 락도 살아있다.

시간이 지나면서 모든 커넥션이 하나씩 유령 락을 물게 되었다. 그리고 새 요청은 락을 획득할 수 없게 되었다.

```
시간 경과에 따른 유령 락 누적:

T+0h: 커넥션 1이 락 획득 → 반환 (락 잔존)
T+1h: 커넥션 2가 락 획득 → 반환 (락 잔존)
T+2h: 커넥션 3이 락 획득 → 반환 (락 잔존)
...
T+24h: 모든 25개 커넥션이 유령 락 보유 → 새 락 획득 불가 → 시스템 마비
```

---

## 대응: 용도별 락 전략 분리

해결책은 용도에 따라 두 가지 락 전략을 분리하는 것이었다.

### 전략 1: executeWithLock — 트랜잭션 스코프 (일반적인 경우)

대부분의 락은 `pg_try_advisory_xact_lock`으로 트랜잭션 스코프에서 사용한다.

```sql
-- executeWithLock: 트랜잭션 스코프 (안전)
SELECT pg_try_advisory_xact_lock(hashtext('latch:char:아이유'));
-- 트랜잭션 커밋/롤백 시 자동 해제
-- 커넥션 반환 시 락도 자동 해제 ✅
```

`xact_lock`은 트랜잭션이 끝나면 자동으로 해제된다. 커넥션이 반환되면 트랜잭션도 종료되고 락도 해제된다. 유령 락이 발생할 수 없다.

### 전략 2: tryLockImmediately — 세션 스코프 (전용 커넥션 풀)

일부 특수한 경우에는 세션 스코프 락이 필요하다. 이 경우 전용 커넥션 풀을 사용한다.

```kotlin
// tryLockImmediately: 세션 스코프 (전용 커넥션 풀 필수)
// pg_try_advisory_lock을 사용하되, 전용 커넥션 풀에서만 실행
// → 일반 요청과 커넥션 풀 분리로 유령 락 누적 방지
```

이 전략은 락 유지 시간이 트랜잭션보다 길어야 하는 특수한 경우에만 사용한다.

### ADR-318: Advisory Lock 원칙

이 경험을 바탕으로 명확한 원칙을 세웠다:

```
Advisory Lock 규칙:

1. executeWithLock: pg_try_advisory_xact_lock 사용 (트랜잭션 스코프)
2. tryLockImmediately: pg_try_advisory_lock 사용 (세션 스코프 + 전용 커넥션 풀)
3. Leader/Follower 패턴 필수
4. 락 키, 캐시 키, NOTIFY 페이로드는 동일한 생성 로직 사용
```

---

## 순환 교착상태 (N09)

세션 스코프 문제를 해결한 후에도 또 다른 락 관련 장애가 있었다. **순환 교착상태.**

장애대응 테스트 N09에서 재현되었다.

```
장애대응 테스트 — Circular Lock Deadlock:

스레드 A: 락 1 획득 → 락 2 대기
스레드 B: 락 2 획득 → 락 1 대기
→ 서로가 서로의 락을 기다림
→ 영원히 대기
```

### 해결: 항상 같은 순서로 락 획득

```kotlin
// Anti-Pattern: 순서 없는 락 획득
fun transfer(from: String, to: String) {
    lock(from) { lock(to) { /* 이체 */ } }
    // from="A", to="B" → 락 A → 락 B
    // 다른 요청 from="B", to="A" → 락 B → 락 A → 교착!
}

// 해결: 항상 같은 순서
fun transfer(from: String, to: String) {
    val sorted = listOf(from, to).sorted()
    lock(sorted[0]) { lock(sorted[1]) { /* 이체 */ } }
}
```

---

## Lock Fallback Avalanche (N11)

또 하나의 락 관련 장애.

락 획득에 실패하면 폴백을 실행한다. 하지만 **모든 요청이 동시에 폴백을 실행하면?**

```
장애대응 테스트 — Lock Fallback Avalanche:

락 획득 실패 요청: 100개
모두 동시에 폴백 실행 (DB 직접 조회)
→ DB에 100개의 동일한 쿼리
→ Cache Stampede와 동일한 문제
```

### 해결: Fallback에도 SingleFlight 적용

락 획득 실패 시 폴백도 SingleFlight로 보호한다. Leader/Follower 패턴을 락 밖에서도 적용하는 것이다.

```kotlin
fun get(key: String): Data {
    // 1. 락 획득 시도
    val locked = tryAcquireLock(key)
    if (locked) {
        return loadAndCache(key)  // Leader
    }

    // 2. 락 실패 → SingleFlight로 폴백 보호
    return singleFlight.execute(key) {
        loadFromDb(key)  // DB 직접 조회 (SingleFlight로 보호됨)
    }
}
```

---

## Thundering Herd on Lock (S17)

가장 극단적인 락 시나리오.

```
장애대응 테스트 — Thundering Herd Lock:

스레드: 100개가 동시에 같은 락 요청
성공: 87, 타임아웃: 13
평균 대기: 523ms
최대 대기: 12,456ms ← 거의 12.5초!

데이터 무결성: 5,000/5,000 (100%) ✅
→ Advisory Lock의 획득 순서가 대기 순서와 상관관계를 가짐
```

100개의 스레드가 하나의 락을 요구하는 상황. 13개는 타임아웃되었지만, 87개는 순서대로 처리되었다. Advisory Lock의 획득 순서가 대기 순서와 상관관계를 가진 덕분이다.

> **※ PostgreSQL의 lock queue는 OS 수준에서 관리되며 엄격한 FIFO는 아닙니다.** 다만, 대기 순서와 락 획득 순서가 높은 상관관계를 가질 뿐입니다.

최대 대기 12.5초는 길지만, 데이터 무결성은 100% 유지되었다. 빠르지만 데이터가 깨지는 것보다 느리지만 정확한 것이 낫다.

---

## 교훈

**1. 락 스코프는 생명과 직결된다.**

세션 스코프 락은 HikariCP와 함께 쓰면 안 된다. 트랜잭션 스코프만 사용하라.

**2. 락 키는 일관성이 있어야 한다.**

락 키, 캐시 키, NOTIFY 페이로드가 다르면 시스템이 깨진다. 하나의 생성 로직으로 통일하라.

**3. 폴백에도 보호가 필요하다.**

락 실패 시 실행하는 폴백도 stampede를 유발할 수 있다. SingleFlight로 보호하라.

**4. 락은 도구다, 목적이 아니다.**

락은 동시성을 제어하는 도구다. 락 자체가 목적이 되면 시스템은 락에 갇힌다.

---

> **다음 장:** [7장: 침묵의 경보 — 장애 중 알림마저 죽었을 때](07_alert_silence.md)
