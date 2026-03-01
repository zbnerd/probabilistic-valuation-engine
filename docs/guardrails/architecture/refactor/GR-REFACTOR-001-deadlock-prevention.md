---
id: GR-REFACTOR-001
category: architecture/refactor
severity: critical
keywords: [deadlock, lock-ordering, circular-wait, coffman-conditions]
languages: [java, kotlin]
---

# Deadlock Prevention - Lock Ordering

## DON'T (안티패턴)
- 다중 락을 획득할 때 순서를 고정하지 않음
- 역순 락 획득으로 Circular Wait 발생

```java
// Bad: 역순 락 획득으로 Deadlock 발생
public void processMultipleLocks(String lockA, String lockB) {
    lockStrategy.executeWithLock(lockA, () -> {
        // lockB가 lockA보다 앞서면 Circular Wait 발생
        lockStrategy.executeWithLock(lockB, () -> {
            // 작업 수행
        });
    });
}
```

```kotlin
// Bad: 순서 없는 락 획득
fun processMultipleLocks(lockA: String, lockB: String) {
    lockStrategy.executeWithLock(lockA) {
        lockStrategy.executeWithLock(lockB) {
            // Circular Wait 위험
        }
    }
}
```

## DO (베스트 프랙티스)
- **알파벳순 정렬**로 락 획득 순서 고정
- `executeWithOrderedLocks()` API 사용
- ThreadLocal 기반 락 순서 추적 및 위반 로깅

```java
// Good: 알파벳순 정렬로 Circular Wait 제거
public void processMultipleLocks(String lockA, String lockB) {
    // OrderedLockExecutor가 자동으로 정렬
    orderedLockExecutor.executeWithOrderedLocks(
        List.of(lockA, lockB),  // 내부적으로 정렬됨
        30, TimeUnit.SECONDS,
        () -> {
            // 안전하게 작업 수행
        }
    );
}

// 또는 LockStrategy default 메서드 활용
public void processMultipleLocks(String lockA, String lockB) {
    lockStrategy.executeWithOrderedLocks(
        List.of(lockA, lockB),
        30, TimeUnit.SECONDS,
        10,  // lease time
        () -> doWork()
    );
}
```

```kotlin
// Good: 순서 보장 락 획득
fun processMultipleLocks(lockA: String, lockB: String) {
    orderedLockExecutor.executeWithOrderedLocks(
        listOf(lockA, lockB),
        30, TimeUnit.SECONDS,
        10
    ) {
        // 안전한 작업 수행
    }
}
```

## Lock Ordering Tracking

```java
// ThreadLocal로 락 순서 추적 (P0-BLUE-01 준수)
private static final ThreadLocal<Deque<String>> ACQUIRED_LOCKS =
        ThreadLocal.withInitial(ArrayDeque::new);

private void validateLockOrder(String lockKey) {
    Deque<String> acquired = ACQUIRED_LOCKS.get();
    if (!acquired.isEmpty()) {
        String lastLock = acquired.peekLast();
        if (lockKey.compareTo(lastLock) < 0) {
            lockOrderMetrics.recordViolation(lockKey, lastLock);
            log.warn("Lock order violation: {} -> {}", lastLock, lockKey);
        }
    }
    acquired.add(lockKey);
}

// finally에서 반드시 제거
finally {
    ACQUIRED_LOCKS.get().removeLast();
    if (ACQUIRED_LOCKS.get().isEmpty()) {
        ACQUIRED_LOCKS.remove();  // 메모리 누수 방지
    }
}
```

## 출처
- [P0 Issues Resolution Report](../../../../05_Reports/04_05_Incidents/P0_Issues_Resolution_Report_2026-01-20.md) - Issues #221, #228
- [ADR-006: Redis Lock Lease Timeout HA](../../../../adr/ADR-006-redis-lock-lease-timeout-ha.md) - Section 4, 5

## 관련 메트릭
- `lock_order_violation_total` - 락 순서 위반 횟수 (0이어야 정상)
- `lock_acquisition_total` - 락 획득 시도 횟수
- `lock_held_current` - 현재 보유 중인 락 수

## Coffman Conditions 분석

| Condition | 제거 방법 | 적용 여부 |
|-----------|-----------|-----------|
| Mutual Exclusion | 변경 불가 (락 특성) | N/A |
| Hold and Wait | 순차적 획득 | ✅ |
| No Preemption | 타임아웃 설정 | ✅ |
| **Circular Wait** | **알파벳순 정렬** | **✅ 핵심** |
