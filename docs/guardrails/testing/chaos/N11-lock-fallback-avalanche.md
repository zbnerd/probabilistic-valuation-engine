---
id: GR-CHAOS-N11
category: testing/chaos
severity: high
keywords: [Nightmare, chaos, N11, Lock Fallback, HikariCP, Connection Pool, MySQL Named Lock]
languages: [java, kotlin]
---

# [N11] Lock Fallback Avalanche

## DON'T (장애 원인)

Redis 장애 시 모든 락 요청이 **MySQL Named Lock으로 Fallback**되면서 **HikariCP Connection Pool이 고갈**됩니다.

### 위험 코드 패턴

```java
// 위험: 별도의 동시성 제한 없이 Fallback
public <T> T executeWithMysqlFallback(String key, Supplier<T> task) {
    try {
        return redisLockStrategy.executeWithLock(key, task);
    } catch (RedisException e) {
        // 모든 요청이 MySQL Named Lock으로 Fallback
        return mysqlLockStrategy.executeWithLock(key, task); // ❌ Connection Pool 고갈 위험
    }
}
```

### 장애 시나리오

```
MySQL GET_LOCK 특성:
- 각 락이 별도 Connection 점유
- 락 해제까지 Connection 반환 불가
- 세션 종료 시 자동 해제

Redis Down 시:
100개 동시 락 요청 → 각각 Connection 점유
                              ↓
        Pool Size(10) < 요청(100)
                              ↓
              Connection Timeout! (30초 대기 후 예외)
```

### 장애 수치
- **Connection Timeout Count**: 증가 (Pool 고갈)
- **Pool Usage Rate**: 100% (여유 없음)
- **Pending Threads**: 증가 (큐 빌드업)

---

## DO (재발 방지)

### 1. Semaphore 기반 동시성 제한

```java
private final Semaphore fallbackSemaphore = new Semaphore(5);

public <T> T executeWithMysqlFallback(String key, Supplier<T> task) {
    if (!fallbackSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
        throw new DistributedLockException("Fallback capacity exceeded");
    }
    try {
        return mysqlLockStrategy.executeWithLock(key, task);
    } finally {
        fallbackSemaphore.release();
    }
}
```

### 2. Bulkhead Pattern (전용 Connection Pool 분리)

```java
@Configuration
public class LockPoolConfig {
    // Named Lock 전용 Connection Pool
    @Bean
    @Qualifier("lockDataSource")
    public DataSource lockDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("LockPool");
        config.setMaximumPoolSize(5);  // 락 전용 제한된 풀
        config.setConnectionTimeout(1000);  // 빠른 실패
        return new HikariDataSource(config);
    }
}
```

### 3. Circuit Breaker 적용

```java
@CircuitBreaker(name = "lockFallback", fallbackMethod = "lockFallback")
public boolean acquireLock(String key) {
    return redisLockStrategy.acquire(key);
}

private boolean lockFallback(String key, Exception e) {
    // Fallback 시 Circuit Breaker로 과도한 호출 방지
    return limitedMysqlLockAcquire(key);
}
```

### 4. HikariCP 메트릭 모니터링

```promql
# Connection Pool 상태 확인
hikaricp_connections_active{pool="LockPool"}
hikaricp_connections_idle{pool="LockPool"}
hikaricp_connections_pending{pool="LockPool"}
hikaricp_connections_timeout_total{pool="LockPool"}

# 알람 설정
- alert: LockPoolExhaustion
  expr: hikaricp_connections_timeout_total{pool="LockPool"} > 0
  for: 1m
  labels:
    severity: critical
```

### 개선 수치 (테스트 결과 기준)
- **Connection Timeout Count**: 0 (Pool 고갈 방지)
- **Pool Usage Rate**: < 80% (여유 확보)
- **Max Concurrent Fallback**: 5개 (Semaphore 제한)
- **Active Connections**: 최대 5개 (Lock Pool 크기)

---

## 출처

- `docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N11-lock-fallback-avalanche.md`
- `docs/05_Reports/04_03_Deep_Dive/CHAOS_REPORT_DEEP_DIVE.md`
