---
id: GR-NIGHTMARE-N09
category: testing/chaos
severity: critical
keywords: [deadlock, circular wait, lock ordering, mysql get_lock]
languages: [java, kotlin]
---

# N09: Circular Lock Deadlock

## DON'T (안티패턴)

```java
// Java - 역순 락 획득으로 Circular Wait 발생
public void processItems(List<String> itemIds) {
    for (String itemId : itemIds) {
        lockStrategy.executeWithLock(itemId, () -> {
            // 처리 로직
        });
    }
}

// Thread 1: ["A", "B", "C"] 순서
// Thread 2: ["C", "B", "A"] 역순
// 결과: Circular Wait → Deadlock!
```

**장애 수치 (Before):**
- Deadlock 발생 확률: 동시 10회 테스트 시 20-30%
- MySQL `lock_wait_timeout` 대기: 50초
- 영향받는 트랜잭션: 2개 스레드
- Rollback 후 재시도 필요

## DO (베스트 프랙티스)

```java
// Java - 항상 정렬된 순서로 락 획득
public void processItems(List<String> itemIds) {
    // 항상 알파벳 순서로 정렬
    List<String> sortedIds = itemIds.stream()
        .sorted()
        .toList();

    for (String itemId : sortedIds) {
        lockStrategy.executeWithLock(itemId, () -> {
            // 처리 로직
        });
    }
}
```

**개선 수치 (After):**
- Deadlock 발생 확률: 0%
- 락 획득 시간: < 100ms
- Circular Wait 불가능
- Coffman Conditions 제거

## 핵심 원칙

1. **Lock Ordering 강제**: 항상 동일 순서(알파벳, 숫자)로 정렬
2. **Coffman Conditions 제거**: Circular Wait 조건 충족 방지
3. **단일 락 선호**: 복합 키로 단일 락 획득 가능성 검토
4. **Timeout 설정**: `lock_wait_timeout`으로 무한 대기 방지

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N09-circular-lock-deadlock.md`
- Nightmare Test N09: Circular Lock Deadlock
- Test Class: `CircularLockDeadlockNightmareTest`
