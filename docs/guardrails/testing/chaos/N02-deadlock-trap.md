---
id: GR-NIGHTMARE-N02
category: testing/chaos
severity: critical
keywords: [deadlock, lock ordering, circular wait, coffman conditions]
languages: [java, kotlin]
---

# N02: Lock Ordering Deadlock

## DON'T (안티패턴)

```java
// Java - 역순 락 획득으로 Deadlock 발생
public void transfer(String fromAccount, String toAccount, long amount) {
    lockStrategy.executeWithLock(fromAccount, () -> {
        lockStrategy.executeWithLock(toAccount, () -> {
            // transfer logic
        });
    });
}

// Thread 1: transfer("A", "B") → A 먼저 획득
// Thread 2: transfer("B", "A") → B 먼저 획득
// 결과: Deadlock!
```

```kotlin
// Kotlin - 역순 락 획득으로 Deadlock 발생
fun transfer(fromAccount: String, toAccount: String, amount: Long) {
    lockStrategy.executeWithLock(fromAccount) {
        lockStrategy.executeWithLock(toAccount) {
            // transfer logic
        }
    }
}
```

**장애 수치 (Before):**
- Deadlock 발생 확률: 동시 100회 테스트 시 5-10%
- MySQL `lock_wait_timeout` 초과: 50초 대기 후 실패
- 영향받는 트랜잭션: 2개 스레드 + 대기열

## DO (베스트 프랙티스)

```java
// Java - Lock Ordering 강제
public void transfer(String fromAccount, String toAccount, long amount) {
    List<String> keys = Arrays.asList(fromAccount, toAccount);
    Collections.sort(keys); // 항상 동일 순서로 정렬

    lockStrategy.executeWithLocks(keys, () -> {
        // transfer logic
    });
}
```

```kotlin
// Kotlin - Lock Ordering 강제
fun transfer(fromAccount: String, toAccount: String, amount: Long) {
    val keys = listOf(fromAccount, toAccount).sorted()
    lockStrategy.executeWithLocks(keys) {
        // transfer logic
    }
}
```

**개선 수치 (After):**
- Deadlock 발생 확률: 0%
- 락 획득 시간: < 100ms
- Circular Wait 불가능

## 핵심 원칙

1. **Lock Ordering**: 항상 동일 순서(예: 알파벳 순)로 락 획득
2. **Coffman Conditions 제거**: Circular Wait 조건 충족 방지
3. **정렬 기반 키 병합**: 다중 락 사용 시 키 목록을 정렬

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N02-deadlock-trap.md`
- Nightmare Test N02: Lock Ordering Deadlock
- Test Class: `DeadlockTrapNightmareTest`
