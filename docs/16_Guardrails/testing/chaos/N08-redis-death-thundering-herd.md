---
id: GR-NIGHTMARE-N08
category: testing/chaos
severity: critical
keywords: [redis, fallback, thundering herd, connection pool, circuit breaker]
languages: [java, kotlin]
---

# N08: Thundering Herd (Redis Death)

## DON'T (안티패턴)

```java
// Java - Redis 장애 시 무조건 MySQL Fallback
public <T> T executeWithLock(String key, Supplier<T> task) {
    try {
        return redisLockStrategy.executeWithLock(key, task);
    } catch (Exception e) {
        // Redis 장애 시 모든 요청이 MySQL로 Fallback
        return mysqlLockStrategy.executeWithLock(key, task);
    }
}
```

**장애 수치 (Before):**
- Redis 장애 시 동시 Fallback 요청: 50건
- HikariCP Pool (10개) 고갈: 5초 만에 소진
- Connection Timeout: 15건
- Circuit Breaker 미작동: 계속 재시도

## DO (베스트 프랙티스)

```java
// Java - Semaphore + Circuit Breaker 적용
public <T> T executeWithLock(String key, Supplier<T> task) {
    try {
        return redisLockStrategy.executeWithLock(key, task);
    } catch (Exception e) {
        // Circuit Breaker 확인
        if (circuitBreaker.isOpen()) {
            throw new DistributedLockException("Redis unavailable");
        }

        // Semaphore로 동시성 제한
        if (!fallbackSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
            throw new DistributedLockException("Fallback capacity exceeded");
        }

        try {
            return mysqlLockStrategy.executeWithLock(key, task);
        } finally {
            fallbackSemaphore.release();
        }
    }
}
```

**개선 수치 (After):**
- Redis 장애 시 동시 Fallback: 최대 5건 (Semaphore)
- Connection Timeout: 0건
- Circuit Breaker OPEN: 빠른 실패
- Pool 보존: 5개 Connection 항상 여유

## 핵심 원칙

1. **Circuit Breaker**: 연속 실패 시 빠른 실패 (fail-fast)
2. **Semaphore 동시성 제한**: Fallback 요청 수 제한
3. **Connection Pool 보호**: Bulkhead 패턴 적용
4. **모니터링**: `hikaricp_connections_timeout_total` 감시

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N08-thundering-herd-redis-death.md`
- Nightmare Test N08: Thundering Herd (Redis Death)
- Test Class: `ThunderingHerdRedisDeathNightmareTest`
