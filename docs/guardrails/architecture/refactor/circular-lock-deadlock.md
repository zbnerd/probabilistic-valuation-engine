---
id: GR-REFACTOR-004
category: architecture/refactor
severity: critical
keywords: [deadlock, lock-ordering, threadlocal, circular-wait, coffman]
languages: [java, kotlin]
---

# Circular Lock Deadlock

## DON'T (위반 사항/장애 원인)

### 위험 코드
```java
// 역순 락 획득 → Deadlock 발생
// Thread A: lock("B") → lock("A")
// Thread B: lock("A") → lock("B")
public void processAB() {
    lockStrategy.executeWithLock("B", () -> {...});
    lockStrategy.executeWithLock("A", () -> {...});
}
```

### 위험 요소
- **Coffman Condition #4 위반**: Circular Wait (역순 락 획득)
- **100% Deadlock**: 역순 락 획득 시 확정적 Deadlock
- **ThreadLocal Memory Leak**: cleanup 누락 시 메모리 누수

### 수치 (Before)
- Deadlock 확률: 100% (역순 획득 시)

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// ThreadLocal로 락 획득 순서 추적 + 역순 시 WARN 로그
private static final ThreadLocal<Deque<String>> ACQUIRED_LOCKS =
        ThreadLocal.withInitial(ArrayDeque::new);

private void validateLockOrder(String lockKey) {
    Deque<String> acquired = ACQUIRED_LOCKS.get();
    if (!acquired.isEmpty()) {
        String lastLock = acquired.peekLast();
        if (lockKey.compareTo(lastLock) < 0) {
            lockOrderMetrics.recordViolation(lockKey, lastLock);
            log.warn("Lock ordering violation: {} after {}", lastLock, lockKey);
        }
    }
}

// finally 블록에서 cleanup 필수
finally {
    ACQUIRED_LOCKS.remove(); // Memory Leak 방지
}
```

### LockStrategy 인터페이스 확장
```java
default <T> T executeWithOrderedLocks(
    List<String> keys,
    long totalTimeout,
    TimeUnit timeUnit,
    long leaseTime,
    ThrowingSupplier<T> task
) throws Throwable {
    // 알파벳순 정렬 후 복합키로 결합
    String compositeKey = keys.stream()
            .sorted()
            .collect(Collectors.joining(":"));
    return executeWithLock(compositeKey, timeUnit.toSeconds(totalTimeout), leaseTime, task);
}
```

### 개선 수치 (After)
- Lock Order Violation: Prometheus 메트릭으로 모니터링
- ThreadLocal Memory Leak: try-finally 패턴으로 보장

### 핵심 원칙
1. **알파벳순 정렬**: 다중 락은 항상 일정한 순서로 획득
2. **ThreadLocal.remove() 필수**: finally 블록에서 cleanup 보장
3. **메트릭 기록**: 역순 획득 시 Prometheus 카운터 기록

## 출처
- 문서: [docs/05_Reports/04_05_Incidents/P0_Issues_Resolution_Report.md](../../../05_Reports/04_05_Incidents/P0_Issues_Resolution_Report.md)
- 이슈: #228 (N09-Circular Lock), #221 (N02-Lock Ordering)
- Nightmare: CircularLockDeadlockNightmareTest, DeadlockTrapNightmareTest
- ADR: [ADR-006](../../../01_ADR/ADR-006-redis-lock (see docs/_archive/redis-deprecated/).md)
