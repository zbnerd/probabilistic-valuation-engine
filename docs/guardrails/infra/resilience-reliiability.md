---
id: GR-INFRA-001
category: infrastructure
severity: critical
keywords: [ADR-010, ADR-052, Outbox, Circuit-Breaker, Resilience4j, Redis-Lock, Graceful-Shutdown]
---
# Infrastructure Guardrails - Resilience & Reliability

## Overview

Guardrails for infrastructure components including Redis, Circuit Breaker, Outbox pattern, and graceful shutdown.

**Sources:** ADR-010, ADR-052, ADR-034, ADR-008

---

## GR-INFRA-001: Circuit Breaker Configuration

### DON'T (Anti-Patterns)

```java
// ❌ NO CIRCUIT BREAKER: Direct API call
public Character fetchCharacter(String ign) {
    return nexonApiClient.fetch(ign);
}
// Problem: External API failure → Cascading failure

// ❌ BUSINESS EXCEPTION TRIPS CIRCUIT
public class CharacterNotFoundException extends RuntimeException {
    // Without CircuitBreakerIgnoreMarker
}
// Problem: 404 responses open circuit breaker!
```

**Consequences:**
- External API timeout (3s+) → Thread pool exhaustion
- Connection pool saturation (HikariCP 10/10)
- Cascading failure → Total service outage

### DO (Best Practices)

```java
// ✅ CIRCUIT BREAKER WITH MARKER INTERFACES
@Configuration
public class ResilienceConfig {
    @Bean
    public Customizer<Resilience4jCircuitBreakerFactory> circuitBreakerCustomizer() {
        return factory -> factory.configureDefault(builder -> builder
            .slidingWindowSize(100)
            .failureRateThreshold(50)           // 50% failure rate opens circuit
            .waitDurationInOpenState(Duration.ofMinutes(5))
            .permittedNumberOfCallsInHalfOpenState(10)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
        );
    }
}

// ✅ EXCEPTION MARKERS
// 4xx: Business exceptions (don't trip circuit)
public abstract class ClientBaseException extends RuntimeException
        implements CircuitBreakerIgnoreMarker {
}

// 5xx: System exceptions (trip circuit)
public abstract class ServerBaseException extends RuntimeException
        implements CircuitBreakerRecordMarker {
}

// ✅ SPECIFIC EXCEPTIONS
public class CharacterNotFoundException extends ClientBaseException {
    public CharacterNotFoundException(String ign) {
        super("Character not found: " + ign);  // Won't trip circuit
    }
}

public class NexonApiTimeoutException extends ServerBaseException {
    public NexonApiTimeoutException(String url, long timeoutMs) {
        super("API timeout: %s (%dms)", url, timeoutMs);  // Will trip circuit
    }
}
```

### Circuit Breaker Configuration

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| **failureRateThreshold** | 50% | 30% too sensitive, 70% too slow |
| **slidingWindowSize** | 100 | Statistical significance |
| **waitDurationInOpenState** | 5 min | External API recovery time |
| **permittedCallsHalfOpen** | 10 | Balance between traffic and safety |

### Circuit Breaker State Transitions

```
CLOSED → (50%+ failures) → OPEN (5 min) → HALF_OPEN (10 calls) → CLOSED
                                    ↑                                  ↓
                                    └─────── (failed) ─────────────────────┘
```

---

## GR-INFRA-002: Redis Lock with Fallback

### DON'T (Anti-Patterns)

```java
// ❌ SINGLE LOCK SOURCE: No fallback
public <T> T executeWithLock(String key, Supplier<T> task) {
    RLock lock = redissonClient.getLock(key);
    lock.lock(30, TimeUnit.SECONDS);
    try {
        return task.get();
    } finally {
        lock.unlock();
    }
}
// Problem: Redis failure → Service unavailable
```

### DO (Best Practices)

```java
// ✅ RESILIENT LOCK STRATEGY: 2-layer fallback
public class ResilientLockStrategy implements LockStrategy {
    private final LockStrategy redisLockStrategy;
    private final LockStrategy mysqlLockStrategy;
    private final LogicExecutor executor;

    @Override
    public <T> T executeWithLock(String key, long wait, long lease, Supplier<T> task) {
        return executor.execute(() -> {
            try {
                // 1st try: Redis Lock (fast)
                return redisLockStrategy.executeWithLock(key, wait, lease, task);
            } catch (RedisException | RedisTimeoutException e) {
                // Infrastructure failure → MySQL fallback
                log.warn("Redis lock failed, falling back to MySQL: {}", e.getMessage());
                return mysqlLockStrategy.executeWithLock(key, wait, lease, task);
            }
            // Business exception (ClientBaseException) → Propagate without fallback
        }, TaskContext.of("Lock", "ExecuteWithLock", key));
    }
}
```

### Trade-off Analysis

| Aspect | Redis Lock | MySQL Lock (Fallback) |
|--------|-----------|----------------------|
| **Performance** | ~10ms | ~200ms (10-20x slower) |
| **Availability** | Redis-dependent | Always available |
| **Decision** | Primary | Zero-downtime fallback |

**Key Principle:** Service availability > Performance during degradation

---

## GR-INFRA-003: Redis Lock Safety Patterns

### DON'T (Anti-Patterns)

```java
// ❌ UNSAFE UNLOCK: No check if held
public void unlockSafely(String key) {
    lock.unlock();  // IllegalMonitorStateException if timeout auto-released
}

// ❌ WATCHDOG WITHOUT LEASE: Potential deadlock
public void executeWithLock(String key, Supplier<T> task) {
    lock.lock();  // No timeout → watchdog required
    try {
        return task.get();
    } finally {
        lock.unlock();
    }
}
```

### DO (Best Practices)

```java
// ✅ SAFE UNLOCK: Check held by current thread
public void unlockSafely(String key) {
    executor.executeOrDefault(
        () -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            return null;
        },
        null,  // Ignore if unlock fails (already auto-released)
        TaskContext.of("Lock", "Unlock", key)
    );
}

// ✅ LEASE TIME: Auto-release to prevent deadlock
public <T> T executeWithLock(String key, Supplier<T> task) {
    lock.lock(10, TimeUnit.SECONDS);  // Auto-release after 10s
    try {
        return task.get();
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

---

## GR-INFRA-004: Transactional Outbox Pattern

### DON'T (Anti-Patterns)

```java
// ❌ DUAL WRITE: Separate DB + event publishing
@Transactional
public void processDonation(Donation donation) {
    repository.save(donation);
    notificationService.send(donation);  // Separate transaction!
}
// Problem: DB success + notification failure = inconsistency
```

### DO (Best Practices)

```java
// ✅ OUTBOX PATTERN: Atomic DB + event
@Transactional
public void processDonation(Donation donation) {
    repository.save(donation);

    // Outbox in same transaction
    DonationOutbox outbox = DonationOutbox.builder()
        .requestId(donation.getId())
        .eventType("DONATION_COMPLETE")
        .payload(serialize(donation))
        .contentHash(hash(donation))  // SHA-256 integrity
        .build();
    outboxRepository.save(outbox);
    // Atomic: Both or neither
}

// ✅ SKIP LOCKED: Prevent distributed duplicate processing
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses AND o.nextRetryAt <= :now")
List<DonationOutbox> findPendingWithLock(
    @Param("statuses") List<OutboxStatus> statuses,
    @Param("now") LocalDateTime now
);
```

### Outbox Safety Features

| Feature | Implementation | Purpose |
|---------|---------------|---------|
| **SKIP LOCKED** | MySQL `FOR UPDATE SKIP LOCKED` | Prevent distributed duplicates |
| **Content Hash** | SHA-256(requestId + eventType + payload) | Detect data corruption |
| **Stalled Recovery** | 5-minute threshold | Auto-recover zombie state |
| **Triple Safety Net** | DB DLQ → File Backup → Alert | Prevent data loss |

---

## GR-INFRA-005: Graceful Shutdown

### DON'T (Anti-Patterns)

```java
// ❌ NO GRACEFUL SHUTDOWN: Immediate kill
// Kill signal (SIGTERM) → Immediate termination
// Problem: In-flight requests dropped, data loss
```

### DO (Best Practices)

```yaml
# ✅ GRACEFUL SHUTDOWN CONFIGURATION
spring:
  lifecycle:
    timeout-per-shutdown-phase: 50s  # Wait for tasks to complete
server:
  shutdown: graceful  # Spring Boot 2.3+
```

### Shutdown Sequence

```
1. SIGTERM received
2. New requests rejected (health check: down)
3. In-flight requests complete (max 50s)
4. @PreDestroy methods execute
5. Context closed
6. JVM exits
```

### Scheduler Graceful Shutdown

```java
// ✅ FIXED DELAY: No new tasks during shutdown
@Scheduled(fixedDelay = 3000)  // Starts AFTER completion
public void pollAndProcess() {
    // Running task completes before shutdown
}
```

---

## GR-INFRA-006: Scheduler Configuration

### DON'T (Anti-Patterns)

```java
// ❌ FIXED RATE: Overlap regardless of completion
@Scheduled(fixedRate = 1000)  // Every 1 second
public void localFlush() {
    // If this takes 5s, 5 threads run concurrently!
}
```

**Consequences:**
- Thread pool exhaustion
- Connection pool exhaustion
- Redis lock contention
- `HikariPool-1 - Connection is not available` error

### DO (Best Practices)

```java
// ✅ FIXED DELAY: Wait for completion
@Scheduled(fixedDelay = 3000)  // 3s AFTER completion
public void localFlush() {
    // Guaranteed no overlap
}

// ✅ STAGGERED SCHEDULING
@Scheduled(fixedDelay = 3000)     // L1 → L2 Flush (frequent)
public void localFlush() { }

@Scheduled(fixedDelay = 5000)     // Count DB Sync (medium)
public void globalSyncCount() { }

@Scheduled(fixedDelay = 10000)    // Relation DB Sync (infrequent)
public void globalSyncRelation() { }
```

### Scheduler Changes

| Scheduler | Before (fixedRate) | After (fixedDelay) | Impact |
|-----------|-------------------|-------------------|--------|
| LikeSyncScheduler.localFlush | 1s | 3s | Prevents overlap |
| OutboxScheduler.pollAndProcess | 10s | 15s | Reduces contention |

---

## GR-INFRA-007: Named Lock Deadlock Prevention

### DON'T (Anti-Patterns)

```java
// ❌ INCONSISTENT LOCK ORDERING: Deadlock risk
public void transferFunds(String fromAccount, String toAccount, int amount) {
    lock(fromAccount);  // Lock A then B
    lock(toAccount);
    // ...
}

// Another thread:
public void transferFunds(String fromAccount, String toAccount, int amount) {
    lock(toAccount);   // Lock B then A → DEADLOCK!
    lock(fromAccount);
}
```

### DO (Best Practices)

```java
// ✅ CONSISTENT LOCK ORDERING
public void transferFunds(String fromAccount, String toAccount, int amount) {
    // Always lock in consistent order (e.g., alphabetical)
    String first = fromAccount.compareTo(toAccount) < 0 ? fromAccount : toAccount;
    String second = fromAccount.compareTo(toAccount) < 0 ? toAccount : fromAccount;

    lock(first);
    lock(second);
    // ... transfer logic
}
```

---

## Fail If Wrong Conditions

### Circuit Breaker

1. **[F1]** Cascading failure occurs (Circuit breaker not working)
   - Verification: External API timeout → Service outage
   - Evidence: Connection pool exhaustion

2. **[F2]** Business exception trips circuit breaker
   - Verification: 404 responses open circuit
   - Evidence: `CharacterNotFoundException` without `CircuitBreakerIgnoreMarker`

### Transactional Outbox

3. **[F1]** Dual-write problem occurs (DB commit + event publish failure)
4. **[F2]** Zombie state persists > 5 minutes
5. **[F3]** SKIP LOCKED allows distributed duplicates
6. **[F4]** DLQ Triple Safety Net failure → Data loss

### Scheduler

7. **[F1]** Connection Pool exhaustion reoccurs
   - Verification: `hikaricp_connections_active` ≥ pool size (10)
8. **[F2]** Scheduler overlap occurs after fixedDelay migration
   - Verification: Log timestamps show overlapping executions

---

## Verification Commands

### Circuit Breaker

```bash
# Circuit breaker state
curl -s http://localhost:8080/actuator/health/circuitbreakers

# Circuit breaker metrics
curl -s http://localhost:8080/actuator/metrics | grep resilience4j

# Failure rate
resilience4j_circuitbreaker_failure_rate{instance="nexonApi"}
```

### Outbox

```bash
# Outbox pending count
curl -s http://localhost:8080/actuator/metrics | grep outbox_pending_count
# Expected: < 1000

# Stalled entries
curl -s http://localhost:8080/actuator/metrics | grep outbox_stalled_total

# DLQ count
curl -s http://localhost:8080/actuator/metrics | grep outbox_dlq_total
```

### Connection Pool

```bash
# HikariCP active connections
curl -s http://localhost:8080/actuator/prometheus | grep hikaricp_connections_active
# Expected: < pool size (10)

# HikariCP pending connections
curl -s http://localhost:8080/actuator/prometheus | grep hikaricp_connections_pending
# Expected: 0
```

### Scheduler

```bash
# Check no fixedRate remains
grep -r "fixedRate" src/main/java/maple/expectation/scheduler/
# Expected: No results

# Scheduler execution time
curl -s http://localhost:8080/actuator/metrics | grep scheduler_execution_duration
```

---

## Related Documents

- **ADR-010:** Transactional Outbox Pattern
- **ADR-052:** Resilience4j Circuit Breaker
- **ADR-034:** Scheduler Thread Pool Configuration
- **ADR-008:** Graceful Shutdown
- **ADR-078:** Named Lock Circular Deadlock Prevention
- **infrastructure.md** Section 8: Redis & Redisson Integration
- **infrastructure.md** Section 9: Observability & Validation
