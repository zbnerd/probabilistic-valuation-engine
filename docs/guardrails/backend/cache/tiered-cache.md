---
id: GR-001
category: backend/cache
severity: critical
keywords: [Redis, Caffeine, TieredCache, Cache Stampede, Single-flight, TTL]
---
# TieredCache & Cache Stampede Prevention

## DON'T (안티패턴)

### 1. L1 먼저 저장 후 L2 저장
```java
// Bad (L2 실패 시 불일치 발생)
l1Cache.put(key, value);
l2Cache.put(key, value);  // 여기서 실패하면 L1에만 데이터 존재
```

### 2. leaseTime 지정 (데드락 위험)
```java
// Bad (작업이 leaseTime 초과 시 락 해제됨)
lock.tryLock(30, 5, TimeUnit.SECONDS);
doLongRunningTask();  // 5초 초과 시 다른 스레드가 락 획득
```

### 3. isHeldByCurrentThread() 없는 unlock
```java
// Bad (IllegalMonitorStateException 위험)
finally {
    lock.unlock();  // 타임아웃 해제 후 호출 시 예외
}
```

### 4. Redis 장애 시 예외 전파
```java
// Bad (Redis 장애가 서비스 장애로 전파)
boolean acquired = lock.tryLock(30, TimeUnit.SECONDS);
if (!acquired) throw new LockAcquisitionException();
```

### 5. L1 TTL > L2 TTL
```java
// Bad (L2가 먼저 만료되어 불일치)
l1Cache.put(key, value, Duration.ofMinutes(10));   // L1: 10분
l2Cache.put(key, value, Duration.ofMinutes(5));    // L2: 5분 (문제)
```

## DO (베스트 프랙티스)

### 1. Write Order: L2 -> L1 (원자성 보장)
```java
// Good (L2 저장 성공 후에만 L1 저장)
l2Cache.put(key, value);
l1Cache.put(key, value);  // L2 성공 후 L1 저장
```

### 2. Redisson Watchdog 모드 (Context7 공식)
```java
// Good (Watchdog이 자동 연장)
lock.tryLock(30, TimeUnit.SECONDS);  // leaseTime 생략
```

### 3. unlock() 안전 패턴
```java
// Good
finally {
    if (acquired && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 4. Graceful Degradation (가용성 우선)
```java
// Good (Redis 장애 시 Fallback 실행)
boolean acquired = executor.executeOrDefault(
    () -> lock.tryLock(30, TimeUnit.SECONDS),
    false,  // Redis 장애 시 락 획득 실패로 처리
    TaskContext.of("Cache", "AcquireLock", keyStr)
);
```

### 5. 분산 Single-flight 패턴
```java
// Good (Leader: 락 획득 -> Double-check -> ValueLoader)
if (lock.tryLock(30, TimeUnit.SECONDS)) {
    try {
        // Double-check L2
        V value = l2Cache.get(key);
        if (value != null) {
            l1Cache.put(key, value);
            return value;
        }
        // Leader가 valueLoader 실행
        V newValue = valueLoader.get();
        l2Cache.put(key, newValue);
        l1Cache.put(key, newValue);
        return newValue;
    } finally {
        lock.unlock();
    }
} else {
    // Follower: L2에서 읽기 -> L1 Backfill
    V value = l2Cache.get(key);
    if (value != null) l1Cache.put(key, value);
    return value;
}
```

### 6. TTL 규칙: L1 <= L2
```java
// Good (L2가 항상 Superset)
l1Cache.put(key, value, Duration.ofMinutes(5));   // L1: 5분
l2Cache.put(key, value, Duration.ofMinutes(10));  // L2: 10분 (L1 >= L2)
```

### 7. Spring @Cacheable(sync=true)
```java
// Good (Cache Stampede 방지)
@Cacheable(cacheNames="equipment", sync=true)
public Equipment findEquipment(String id) { ... }
```

### 8. Micrometer 메트릭 (점 표기법)
```java
// Good
meterRegistry.counter("cache.hit", "layer", "L1").increment();
meterRegistry.counter("cache.miss").increment();
```

## 출처
- infrastructure.md Section 17: TieredCache & Cache Stampede Prevention
- ADR-006: Redis Lock Lease-time HA Decision
- P0 Report: Cache Stampede Incident Resolution
