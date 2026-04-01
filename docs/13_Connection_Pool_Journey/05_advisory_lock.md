# 5장: 숨은 병목 — Advisory Lock이 훔친 커넥션

> "락을 잡고 커넥션을 반납하지 않으면, 그것은 메모리 누수가 아니라 커넥션 누수다."

## 2026년 3월 29일, PR #628-631

대이주(4장) 이후, 모니터링에서 새로운 패턴이 포착되었다.

```
HikariCP Metrics (Production):
  connections.active:  ████████████████████████████ 25/25  POOL EXHAUSTED
  connections.pending: ++++++++++ 12 threads waiting

Leak Detection Log:
  Connection leak detection: Connection held for 45,230ms (threshold: 60,000ms)
  Stack trace:
    at maple.expectation.infrastructure.lock.PostgresLockStrategy.acquire()
    at maple.expectation.infrastructure.lock.PostgresLockStrategy.acquire()
    ...
```

커넥션 풀이 또 고갈되고 있었다. 원인은 **Advisory Lock**이었다.

## Session-Scope Lock의 위험

초기 Advisory Lock 구현은 세션 스코프를 사용했다:

```sql
-- Session-scope lock (위험)
SELECT pg_advisory_lock(12345);  -- 명시적 해제까지 보유
SELECT pg_advisory_unlock(12345); -- 반드시 호출해야 함
```

문제는 HikariCP의 커넥션 풀 동작과 충돌했다:

```
1. 스레드 A가 HikariCP에서 커넥션 획득
2. 커넥션에서 pg_advisory_lock(12345) 실행 ← 락 획득
3. 비즈니스 로직 수행
4. 커넥션을 HikariCP 풀로 반환 ← 여기서 문제 발생!
5. HikariCP는 동일 커넥션을 스레드 B에게 재할당
6. 스레드 B가 다른 락을 시도 → 교착 상태 또는 이전 락이 여전히 활성

더 나쁜 시나리오:
1. 락 획득 후 예외 발생
2. pg_advisory_unlock() 호출 안 됨
3. 커넥션이 락을 보유한 채 풀로 반환
4. 해당 커넥션은 다시 사용할 수 없음 ← 커넥션 누수!
```

### 실제 발생한 장애 시나리오

```
Timeline:
00:00 — 인스턴스 기동, HikariCP 25 connections 활성
00:05 — 트래픽 유입, 락 경합 시작
00:10 — Session-scope 락으로 5개 커넥션이 락을 보유한 채 누수
00:15 — 누적 누수 10개, 활성 커넥션 15개만 사용 가능
00:20 — 누적 누수 15개, 활성 커넥션 10개
00:25 — 누적 누수 20개, 활성 커넥션 5개
00:30 — 누적 누수 23개, 커넥션 풀 고갈
       → "Connection is not available, request timed out after 30000ms"
```

Leak detection이 60초 임계값을 넘기 전에 이미 락이 커넥션을 훔치고 있었다.

## 해결: Transaction-Scope Lock

PR #628-631에서 세션 스코프를 **트랜잭션 스코프**로 전환했다.

```sql
-- Transaction-scope lock (안전)
SELECT pg_try_advisory_xact_lock(12345);  -- 트랜잭션 종료 시 자동 해제
-- COMMIT/ROLLBACK 되면 락도 자동 해제됨
-- 명시적 unlock 불필요!
```

### 코드 변경

```kotlin
// Before: Session-scope (위험)
// 락 획득 후 반드시 unlock 필요 → 예외 시 누수
fun acquire(key: Long): Boolean {
    return jdbcTemplate.queryForObject(
        "SELECT pg_advisory_lock($key)",  // session-scope
        Boolean::class.java
    )
}

fun release(key: Long) {
    jdbcTemplate.queryForObject(
        "SELECT pg_advisory_unlock($key)",
        Boolean::class.java
    )
}

// After: Transaction-scope (안전)
// @Transactional 경계 내에서만 락 유지
// 트랜잭션 종료 시 자동 해제 → 누수 불가능
@Transactional("transactionManager")
fun acquireWithXactLock(key: Long): Boolean {
    return jdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_xact_lock(?)",  // transaction-scope
        Boolean::class.java,
        key
    )
}
// release() 메서드 불필요! COMMIT/ROLLBACK 시 자동 해제
```

### 왜 안전한가

```
트랜잭션 스코프 락의 수명 주기:

1. BEGIN TRANSACTION
2. SELECT pg_try_advisory_xact_lock(12345)  ← 락 획득
3. 비즈니스 로직 수행
4. COMMIT  ← 락 자동 해제, 커넥션 정상 반환

예외 발생 시:
1. BEGIN TRANSACTION
2. SELECT pg_try_advisory_xact_lock(12345)  ← 락 획득
3. 예외 발생!
4. ROLLBACK  ← 락 자동 해제, 커넥션 정상 반환

→ 어떤 경우에도 락이 커넥션을 훔치지 않음
```

## `try` vs blocking의 차이

`pg_advisory_lock` (blocking)에서 `pg_try_advisory_xact_lock` (non-blocking)으로의 전환도 중요했다.

```sql
-- Before: blocking (스레드가 대기하며 커넥션 점유)
SELECT pg_advisory_lock(12345);
-- 락을 얻을 때까지 스레드 차단, 커넥션 점유 지속

-- After: non-blocking (즉시 반환, 커넥션 즉시 해제 가능)
SELECT pg_try_advisory_xact_lock(12345);
-- 락 획득 성공/실패 즉시 반환
-- 실패 시 Follower 폴링 경로로 전환 (최대 5초)
```

## 모니터링에서의 변화

```
Before (session-scope):
  connections.active:  ████████████████████████████ 25/25  POOL EXHAUSTED
  leak warnings:       +23 in last hour

After (transaction-scope):
  connections.active:  ████████████████████ 18/25  여유 있음
  leak warnings:       0 ← 누수 완전 제거
```

## PostgresLockHikariConfig

별도의 락 전용 HikariCP 풀을 만드는 것도 고려했지만, 트랜잭션 스코프 락으로 해결되어 불필요해졌다.

```kotlin
// 고려했지만 불필요해진 설정
// Lock 전용 풀을 만들면 커넥션 풀이 2개가 되어 관리 복잡도 증가
// Transaction-scope 락이 근본 해결이었음
@Configuration
@Profile("!test")
class PostgresLockHikariConfig {
    // 결국 사용하지 않음 — xact_lock이 문제를 해결함
}
```

## 배운 점

> **"락의 수명 주기와 커넥션의 수명 주기가 일치해야 한다. 락이 커넥션보다 오래 살면, 그것은 누수다."**

- `pg_advisory_lock` (session)은 명시적 해제가 필요 → 예외 시 커넥션 누수
- `pg_try_advisory_xact_lock` (transaction)은 자동 해제 → 누수 불가능
- `try` 변종은 non-blocking → 커넥션을 점유하며 대기하지 않음
- HikariCP + 세션 락은 근본적으로 호환되지 않는다

---

**다음 장**: [6장 — 아웃박스의 대가: 3개 스케줄러 × 3개 커넥션](./06_outbox_problem.md)
