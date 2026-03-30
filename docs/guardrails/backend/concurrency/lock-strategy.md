---
id: GR-CONC-004
category: backend/concurrency
severity: critical
keywords: [Redisson, Redis, MySQL, DistributedLock, GET_LOCK, FeatureFlag]
languages: [java, kotlin]
---
# Lock Strategy (Redis → MySQL Fallback)

## DON'T (안티패턴)

### 1. Redis Lock만 사용 (장애 시 스케줄러 중복 실행)
```java
// Bad (Redis 장애 시 락 획득 실패 -> 중복 실행)
public void schedule() {
    RLock lock = redissonClient.getLock("scheduler-lock");
    if (lock.tryLock()) {
        try {
            scheduledTask();
        } finally {
            lock.unlock();
        }
    }
}
```

**문제점 (P1-P7-P8-P9 Incident):**
- Redis 장애 시 락 획득 실패 -> 다중 인스턴스에서 동시 실행
- MySQL Fallback 없으면 중복 작업 발생
- 2025 Q4: 4건의 스케줄러 중복 실행 사고 발생

### 2. MySQL Named Lock만 사용 (성능 저하)
```java
// Bad (모든 락을 MySQL로 처리 -> 성능 저하)
public <T> T executeWithLock(String name, Supplier<T> task) {
    return jdbcTemplate.execute(conn -> {
        // MySQL GET_LOCK은 ~1-10ms 지연
        if (conn.nativeSQL("SELECT GET_LOCK(?, 10)", name).equals("1")) {
            try { return task.get(); }
            finally { conn.nativeSQL("SELECT RELEASE_LOCK(?)", name); }
        }
        throw new LockException();
    });
}
```

### 3. Feature Flag 없이 Redis Lock 강제
```java
// Bad (장애 시 Redis 비활성화 불가)
@Bean
public LockStrategy lockStrategy() {
    return new RedisDistributedLockStrategy(redissonClient);  // Hardcoded
}
```

## DO (베스트 프랙티스)

### 1. 3-Tier Lock Architecture (Redis → MySQL → JPA)
```java
// Good (ResilientLockStrategy: Redis 우선, MySQL Fallback)
@Component
public class ResilientLockStrategy implements LockStrategy {

    private final RedisDistributedLockStrategy redisStrategy;
    private final MySqlNamedLockStrategy mysqlStrategy;
    private final CircuitBreaker circuitBreaker;
    private final FeatureFlagClient featureFlagClient;

    @Override
    public <T> T executeWithLock(String lockName, long waitTime, long leaseTime, Supplier<T> task) {
        // Feature Flag 확인
        boolean redisEnabled = featureFlagClient.isEnabled("feature.lock.redis.enabled");
        boolean fallbackEnabled = featureFlagClient.isEnabled("feature.lock.mysql-fallback.enabled");

        if (redisEnabled) {
            // Redis 락 시도 (Circuit Breaker 보호)
            try {
                return redisStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
            } catch (CircuitBreakerOpenException e) {
                if (fallbackEnabled) {
                    log.info("Redis lock circuit breaker open, falling back to MySQL: {}", lockName);
                    return mysqlStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
                }
                throw new LockException("Redis lock unavailable and fallback disabled", e);
            }
        } else {
            // Feature Flag 비활성화 시 MySQL로 직접 전환
            return mysqlStrategy.executeWithLock(lockName, waitTime, leaseTime, task);
        }
    }
}
```

### 2. Feature Flag 제어 시스템
```yaml
# application.yml
feature:
  lock:
    redis:
      enabled: true
      circuit-breaker:
        enabled: true
    mysql-fallback:
      enabled: true
    least-strict:
      enabled: false
```

| Feature Flag | 기본값 | 제어 대상 | 목적 |
|--------------|--------|----------|------|
| `feature.lock.redis.enabled` | `true` | Redis 분산 락 | Redis lock 활성화/비활성화 |
| `feature.lock.redis.circuit-breaker.enabled` | `true` | 서킷브레이커 | Redis 장애 자동 감지 |
| `feature.lock.mysql-fallback.enabled` | `true` | MySQL Fallback | Redis 장애 시 자동 전환 |
| `feature.lock.least-strict.enabled` | `false` | 완화된 락 | 장애 시 성능 우선 |

### 3. Circuit Breaker로 Redis 장애 감지
```java
// Good (Circuit Breaker로 자동 Fallback)
@Component
public class RedisDistributedLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;
    private final CircuitBreaker circuitBreaker;

    @Override
    public <T> T executeWithLock(String lockName, long waitTime, long leaseTime, Supplier<T> task) {
        return circuitBreaker.executeSupplier(() -> {
            RLock lock = redissonClient.getLock(lockName);
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                throw new LockException("Lock acquisition failed: " + lockName);
            }
            try {
                return task.get();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }
}
```

### 4. MySQL Named Lock Fallback
```java
// Good (MySQL GET_LOCK으로 Fallback)
@Component
public class MySqlNamedLockStrategy implements LockStrategy {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public <T> T executeWithLock(String lockName, long waitTime, long leaseTime, Supplier<T> task) {
        return jdbcTemplate.execute(conn -> {
            // MySQL GET_LOCK (세션 기반 락)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT GET_LOCK(?, ?)")) {
                stmt.setString(1, lockName);
                stmt.setInt(2, (int) waitTime);
                boolean acquired = stmt.executeQuery().getBoolean(1);

                if (!acquired) {
                    throw new LockException("MySQL lock acquisition failed: " + lockName);
                }

                try {
                    return task.get();
                } finally {
                    // RELEASE_LOCK (동일 세션에서만 해제 가능)
                    try (PreparedStatement releaseStmt = conn.prepareStatement(
                            "SELECT RELEASE_LOCK(?)")) {
                        releaseStmt.setString(1, lockName);
                        releaseStmt.execute();
                    }
                }
            }
        });
    }
}
```

### 5. 스케줄러에서 분산 락 사용
```java
// Good (분산 환경에서 단일 실행 보장)
@Component
public class LikeSyncScheduler {

    private final LockStrategy lockStrategy;

    @Scheduled(fixedDelay = 60000)
    public void syncRedisToDatabase() {
        lockStrategy.executeWithLock("like-db-sync-lock", 0, 30, () -> {
            likeSyncService.syncRedisToDatabase();
            return null;
        });
    }
}
```

### 6. Lock 전략별 성능 비교
| Lock Strategy | 지연시간 | 처리량 | 장애 복구 | 적합 케이스 |
|---------------|---------|--------|----------|------------|
| **Redis (Redisson)** | < 1ms | 높음 | Circuit Breaker | ~~일반적인 분산 락~~ **DEPRECATED (ADR-022)** |
| **PostgreSQL Advisory Lock** | < 1ms | 높음 | 트랜잭션 종료 시 | 현재 권장 (ADR-003, ADR-022) |
| **MySQL Named Lock** | 1-10ms | 중간 | 자동 (세션 종료 시) | Redis 장애 시 Fallback |
| **JPA Pessimistic Lock** | 10-100ms | 낮음 | 트랜잭션 롤백 | 단일 DB 내 락 |
| **낙관적 락 (@Version)** | < 1ms | 높음 | 재시도 필요 | 충돌 드문 엔티티 |

## 출처
- lock-strategy.md - 3-Tier Lock Architecture
- Production Incident: P1-P7-P8-P9 (2025 Q4) - Scheduler duplicate execution during Redis failover
- ADR-006: `docs/01_Adr/ADR-006-redis-lock-lease-timeout-ha.md` (Watchdog decision)

## 검증 명령어
```bash
# LockStrategy 구현 확인
find src/main/java -name "*LockStrategy.java"

# Feature Flag 구현 확인
find src/main/java -name "*FeatureFlag*.java"

# Redis Lock Metrics 확인
curl -s http://localhost:8080/actuator/metrics/lock.acquired | jq
curl -s http://localhost:8080/actuator/metrics/lock.redis.fallback | jq

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/circuitbreakers | jq
```

## 롤백 계획
- Redis lock 장애 시: `feature.lock.redis.enabled=false`로 MySQL 전환
- Circuit Breaker 불안정 시: `feature.lock.redis.circuit-breaker.enabled=false`로 직접 예외 처리
