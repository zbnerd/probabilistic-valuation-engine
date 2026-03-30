---
id: GR-REFACTOR-010
category: architecture/refactor
severity: critical
keywords: [threadlocal, stateful, scale-out, memory-leak, distributed]
languages: [java, kotlin]
---

# ThreadLocal Stateful Component

## DON'T (위반 사항/장애 원인)

### 위험 코드
```java
// Lock Order Tracking을 위한 ThreadLocal
private static final ThreadLocal<Deque<String>> ACQUIRED_LOCKS =
        ThreadLocal.withInitial(ArrayDeque::new);

// cleanup 누락 시 Memory Leak
public void executeWithLock(String key, Runnable task) {
    ACQUIRED_LOCKS.get().push(key);
    task.run();
    // ACQUIRED_LOCKS.remove() 누락 → Memory Leak
}
```

### 위험 요소
- **Scale-out 불가**: ThreadLocal은 단일 JVM 내에서만 유효
- **Memory Leak**: Web Container 스레드 풀에서 ThreadLocal 제거 누락 시 누수
- **데이터 불일치**: 다중 인스턴스에서 각자 다른 상태 유지

### 수치 (Before)
- ThreadLocal Memory Leak 위험: HIGH
- Scale-out 호환성: NONE

## DO (수정 방법/재발 방지)

### 수정 코드
```java
// 1. try-finally 패턴으로 cleanup 보장
public void executeWithLock(String key, Runnable task) {
    ACQUIRED_LOCKS.get().push(key);
    try {
        task.run();
    } finally {
        ACQUIRED_LOCKS.remove(); // 항상 cleanup
    }
}

// 또는 2. Redis 등 분산 저장소로 이동 (완전한 Stateless)
@Component
public class DistributedLockOrderTracker {
    private final RedissonClient redisson;

    public void recordAcquisition(String instanceId, String lockKey) {
        // Redis에 저장하여 다중 인스턴스 공유
        RMap<String, String> lockOrder = redisson.getMap("lock:order:" + instanceId);
        lockOrder.put(lockKey, Instant.now().toString());
    }

    public boolean validateOrder(String instanceId, String newKey) {
        // Redis에서 순서 검증
        RMap<String, String> lockOrder = redisson.getMap("lock:order:" + instanceId);
        // 알파벳순 검증 로직
    }
}
```

### 개선 수치 (After)
- ThreadLocal Memory Leak: 방지됨 (finally 패턴)
- Scale-out 호환성: Redis 이동 시 FULL

### 핵심 원칙
1. **finally 블록 필수**: ThreadLocal.remove()는 finally에서 호출
2. **Stateless 선호**: ThreadLocal 대신 Redis 등 분산 저장소 사용
3. **Monitorong**: ThreadLocal 크기를 Prometheus Gauge로 모니터링

## 출처
- 문서: [docs/05_Reports/04_08_Refactor/STATEFUL_REFACTORING_TARGETS.md](../../../05_Reports/04_08_Refactor/STATEFUL_REFACTORING_TARGETS.md)
- 관련 ADR: [ADR-006](../../../01_ADR/ADR-006-redis-lock-lease-timeout-ha.md)
