---
id: GR-ARCH-001
category: architecture
severity: critical
keywords: [ADR-041, ADR-017, Hexagonal, DIP, Clean-Architecture, Multi-Module]
---
# Architecture Decision Guardrails

## Overview

This document summarizes the critical architecture decisions from ADR documents as DO/DON'T patterns to guide implementation.

---

## GR-ARCH-001: Hexagonal Architecture & DIP Compliance

**Source:** ADR-041: Multi-Module Hexagonal Architecture with DIP

### DON'T (Anti-Patterns)

```java
// ❌ DIP VIOLATION: High-level module depends on low-level module
// module-app directly importing infrastructure implementation
import maple.expectation.infrastructure.cache.RedisCacheStrategy;

@Service
public class GameCharacterService {
    private final RedisCacheStrategy cacheStrategy;  // Direct dependency on impl
}

// ❌ FRAMEWORK LEAKAGE: Domain polluted with infrastructure annotations
@Entity  // Infrastructure concern in domain
@Table(name = "character_equipment")
public class CharacterEquipment {
    @Id  // Infrastructure concern
    private String ocid;
}

// ❌ CIRCULAR DEPENDENCY: module-infra → module-app
// Infrastructure layer importing application layer
import maple.expectation.app.service.GameCharacterService;
```

### DO (Best Practices)

```java
// ✅ DIP COMPLIANCE: Depend on abstractions (interfaces)
public interface CacheStrategy {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
}

// ✅ PORT DEFINITION: Domain layer defines interface
// module-core/src/main/java/maple/expectation/application/port/CacheStrategy.java
package maple.expectation.application.port;

public interface CacheStrategy {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
}

// ✅ ADAPTER IMPLEMENTATION: Infrastructure layer implements port
// module-infra/src/main/java/maple/expectation/infrastructure/cache/RedisCacheStrategy.java
@Component
public class RedisCacheStrategy implements CacheStrategy {
    private final RedissonClient redisson;
    // Implementation
}

// ✅ APPLICATION LAYER: Uses interface abstraction
@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final CacheStrategy cacheStrategy;  // Interface dependency
}
```

### Module Dependency Rules

```
┌─────────────────────────────────────────────────────────────────┐
│                    DEPENDENCY DIRECTION RULE                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  module-app ──────→  module-infra  ──────→  module-core        │
│   (Controllers)        (Adapters)            (Ports + Domain)   │
│                                                      ↓           │
│                                                module-common    │
│                                                (Shared Kernel)  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Rules:**
- ✅ app → infra (OK): Application uses infrastructure adapters
- ✅ infra → core (OK): Adapters implement domain ports
- ✅ all → common (OK): Everyone uses shared kernel
- ❌ core → infra (FORBIDDEN): Domain never depends on infrastructure
- ❌ infra → app (FORBIDDEN): Infrastructure never depends on application

### Verification Commands

```bash
# Check module-core has zero Spring dependencies
grep -r "@Component\|@Service\|@Repository\|@Controller" module-core/src/main/java/ | wc -l
# Expected: 0

# Verify dependency direction
./gradlew module-app:dependencies --configuration runtimeClasspath | grep module-infra

# Run ArchUnit tests
./gradlew test --tests "maple.expectation.architecture.ArchitectureTest"
```

---

## GR-ARCH-002: Rich Domain Model vs Anemic Domain Model

**Source:** ADR-017: Domain Extraction - Clean Architecture Migration

### DON'T (Anti-Patterns)

```java
// ❌ ANEMIC DOMAIN MODEL: Data without behavior
public class CharacterEquipmentDto {
    private String ocid;
    private String jsonContent;
    private LocalDateTime updatedAt;
    // Only getters/setters - no behavior
}

// ❌ SERVICE PROLIFERATION: Behavior scattered across services
@Service
public class EquipmentService {
    public void updateData(CharacterEquipmentDto dto, String newContent) {
        dto.setJsonContent(newContent);  // Behavior outside domain
        dto.setUpdatedAt(LocalDateTime.now());
    }

    public boolean isExpired(CharacterEquipmentDto dto, Duration ttl) {
        // Business logic in service
    }
}
```

### DO (Best Practices)

```java
// ✅ RICH DOMAIN MODEL: Data + behavior encapsulated
public class CharacterEquipment {
    private final CharacterId ocid;        // Value Object
    private EquipmentData jsonContent;     // Value Object
    private LocalDateTime updatedAt;

    // Rich behavior
    public void updateData(EquipmentData newData) {
        this.jsonContent = Objects.requireNonNull(newData, "newData cannot be null");
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isExpired(Duration ttl) {
        return Duration.between(updatedAt, LocalDateTime.now()).compareTo(ttl) > 0;
    }
}

// ✅ VALUE OBJECTS: Immutable, identity-less
public final class CharacterId {
    private final String value;

    public CharacterId(String value) {
        this.value = Objects.requireNonNull(value, "CharacterId cannot be null");
    }

    public String value() { return value; }

    @Override
    public boolean equals(Object o) {
        return o instanceof CharacterId that && value.equals(that.value);
    }

    @Override
    public int hashCode() { return value.hashCode(); }
}
```

### Domain Layer Separation

```
domain/model/                    # ✅ Pure Java (No Framework)
├── character/
│   ├── GameCharacter.java      # Rich domain model
│   ├── CharacterId.java        # Value Object
│   └── UserIgn.java            # Value Object
├── equipment/
│   ├── CharacterEquipment.java # Pure domain (no JPA)
│   └── EquipmentData.java      # Value Object

infrastructure/persistence/
├── entity/
│   └── CharacterEquipmentJpaEntity.java  # JPA entity (infrastructure)
└── repository/
    └── CharacterEquipmentRepositoryImpl.java  # JPA → Domain mapping
```

**Key Rules:**
- ✅ Domain has NO JPA annotations
- ✅ Domain has NO Spring annotations
- ✅ Domain has behavior (methods)
- ✅ Infrastructure implements domain interfaces
- ❌ Domain never imports `jakarta.persistence.*`
- ❌ Domain never imports `org.springframework.*`

---

## GR-ARCH-003: Exception Hierarchy & Circuit Breaker Markers

**Source:** ADR-052: Resilience4j Circuit Breaker, ADR-044: LogicExecutor

### DON'T (Anti-Patterns)

```java
// ❌ GENERIC EXCEPTION CATCHING
try {
    apiClient.call();
} catch (Exception e) {
    throw new RuntimeException(e);  // Loses context
}

// ❌ CATCH AND IGNORE
try {
    riskyOperation();
} catch (Exception e) {
    // Silently ignore - dangerous!
}

// ❌ BUSINESS EXCEPTION AFFECTS CIRCUIT BREAKER
public class CharacterNotFoundException extends RuntimeException {
    // Without CircuitBreakerIgnoreMarker, this trips the circuit!
}
```

### DO (Best Practices)

```java
// ✅ EXCEPTION HIERARCHY WITH MARKERS
// 4xx: Business exceptions (CircuitBreakerIgnoreMarker)
public abstract class ClientBaseException extends RuntimeException
        implements CircuitBreakerIgnoreMarker {
    protected ClientBaseException(String message) {
        super(message);
    }
    protected ClientBaseException(String message, Throwable cause) {
        super(message, cause);  // Exception chaining
    }
}

// 5xx: System exceptions (CircuitBreakerRecordMarker)
public abstract class ServerBaseException extends RuntimeException
        implements CircuitBreakerRecordMarker {
    protected ServerBaseException(String message, Object... args) {
        super(String.format(message, args));
    }
}

// ✅ SPECIFIC BUSINESS EXCEPTIONS
public class CharacterNotFoundException extends ClientBaseException {
    public CharacterNotFoundException(String ign) {
        super("Character not found: " + ign);  // Won't trip circuit breaker
    }
}

// ✅ SPECIFIC SYSTEM EXCEPTIONS
public class NexonApiTimeoutException extends ServerBaseException {
    public NexonApiTimeoutException(String url, long timeoutMs) {
        super("API timeout: %s (%dms)", url, timeoutMs);  // Will trip circuit breaker
    }
}

// ✅ LOGICEXECUTOR USAGE (Zero try-catch in business logic)
return executor.execute(
    () -> apiClient.fetchCharacter(ign),
    TaskContext.of("Character", "FindById", ign)
);
```

### Exception Classification Table

| Tier | Exception Type | Marker Interface | Circuit Impact | Logging Level |
|------|---------------|------------------|----------------|---------------|
| **Tier 1** | Business (4xx) | `CircuitBreakerIgnoreMarker` | None | `log.warn` |
| **Tier 2** | Infrastructure (5xx) | `CircuitBreakerRecordMarker` | Records failure | `log.error` |
| **Tier 3** | Unknown | No marker | Default (records) | `log.error` |

**Key Principle:**
> "캐릭터를 찾을 수 없음"은 시스템 장애가 아니다. 정상적인 비즈니스 흐름의 예외가 Circuit Breaker를 Open하게 만들어서는 안 된다.

---

## GR-ARCH-004: Zero Try-Catch Policy

**Source:** ADR-044: LogicExecutor Zero Try-Catch Policy

### DON'T (Anti-Patterns)

```java
// ❌ TRY-CATCH IN BUSINESS LOGIC (service/, scheduler/, config/, global/)
public Character findCharacter(String ign) {
    try {
        return apiClient.fetch(ign);
    } catch (IOException e) {
        log.error("Error", e);
        return null;  // Silent failure
    }
}

// ❌ INCONSISTENT EXCEPTION HANDLING
// 35 services with different patterns
```

### DO (Best Practices)

```java
// ✅ LOGICEXECUTOR FOR ALL EXECUTION
public Character findCharacter(String ign) {
    return executor.execute(
        () -> apiClient.fetch(ign),
        TaskContext.of("Character", "FindById", ign)
    );
}

// ✅ 6 EXECUTION PATTERNS
// Pattern 1: Normal execution
executor.execute(task, context)

// Pattern 2: Void execution
executor.executeVoid(task, context)

// Pattern 3: With default value
executor.executeOrDefault(task, defaultValue, context)

// Pattern 4: With recovery
executor.executeWithRecovery(task, recovery, context)

// Pattern 5: With finally (resource cleanup)
executor.executeWithFinally(task, finalizer, context)

// Pattern 6: With exception translation
executor.executeWithTranslation(task, translator, context)
```

### Allowed Exceptions (Cyclical Dependency Constraints)

Only these components may use try-catch:

| Component | Reason | Exception Handling |
|-----------|--------|-------------------|
| **TraceAspect** | AOP calling LogicExecutor causes circular reference | SLF4J logger directly |
| **DefaultLogicExecutor** | Cannot call itself | Internal try-catch |
| **ExecutionPipeline** | Inside LogicExecutor execution pipeline | Dedicated handler |
| **TaskDecorator** | Runnable wrapping structure | try-finally for MDC propagation |
| **JPA Entity** | Spring Bean injection impossible | Direct exception conversion (Section 11) |

---

## GR-ARCH-005: Scheduler Thread Pool Configuration

**Source:** ADR-034: Scheduler Thread Pool Exhaustion Fix

### DON'T (Anti-Patterns)

```java
// ❌ FIXED RATE: Causes overlap
@Scheduled(fixedRate = 1000)  // Every 1 second, regardless of completion
public void localFlush() {
    // If this takes 5 seconds, 5 threads will run concurrently!
}
```

**Consequences:**
- Thread pool exhaustion
- Connection pool exhaustion
- Redis lock contention
- Cascading failures

### DO (Best Practices)

```java
// ✅ FIXED DELAY: Waits for completion
@Scheduled(fixedDelay = 3000)  // 3 seconds AFTER completion
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

### Scheduler Configuration

| Scheduler | Before (fixedRate) | After (fixedDelay) | Impact |
|-----------|-------------------|-------------------|--------|
| LikeSyncScheduler.localFlush | 1s | 3s | Prevents overlap |
| LikeSyncScheduler.globalSyncCount | 3s | 5s | Staggered execution |
| OutboxScheduler.pollAndProcess | 10s | 15s | Reduces contention |

**Verification:**
```bash
# Check no fixedRate remains
grep -r "fixedRate" module-app/src/main/java/maple/expectation/scheduler/
# Expected: No results

# Monitor connection pool
curl -s http://localhost:8080/actuator/prometheus | grep hikaricp_connections_active
# Expected: < pool size (10)
```

---

## GR-ARCH-006: Transactional Outbox Pattern

**Source:** ADR-010: Transactional Outbox Pattern

### DON'T (Anti-Patterns)

```java
// ❌ DUAL WRITE: Database + Event separately
@Transactional
public void processDonation(Donation donation) {
    repository.save(donation);  // DB committed
    // Event published separately - RACE CONDITION!
    notificationService.send(donation);  // What if this fails?
}
```

**Consequences:**
- Dual-write problem: DB success + notification failure = inconsistency
- Data loss
- Lost events

### DO (Best Practices)

```java
// ✅ TRANSACTIONAL OUTBOX: Atomic DB + Event storage
@Transactional
public void processDonation(Donation donation) {
    repository.save(donation);

    // Outbox in same transaction
    DonationOutbox outbox = DonationOutbox.builder()
        .requestId(donation.getId())
        .eventType("DONATION_COMPLETE")
        .payload(serialize(donation))
        .contentHash(hash(donation))  // Integrity check
        .build();
    outboxRepository.save(outbox);
    // Atomic: both or neither
}

// ✅ SKIP LOCKED: Prevents distributed duplicate processing
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))  // SKIP LOCKED
@Query("SELECT o FROM DonationOutbox o WHERE o.status IN :statuses AND o.nextRetryAt <= :now")
List<DonationOutbox> findPendingWithLock(
    @Param("statuses") List<OutboxStatus> statuses,
    @Param("now") LocalDateTime now
);
```

### Triple Safety Net (DLQ)

1. **Primary:** DB Dead Letter Queue INSERT
2. **Secondary:** File backup (if DLQ fails)
3. **Tertiary:** Discord Critical Alert + Metric

```java
// Triple Safety Net Implementation
public void handleDeadLetter(DonationOutbox entry, String reason) {
    // 1st attempt: DB DLQ
    executor.executeOrCatch(
        () -> dlqRepository.save(DonationDlq.from(entry, reason)),
        dbEx -> handleDbDlqFailure(entry, reason),  // 2nd attempt
        context
    );
}
```

### Fail If Wrong Conditions

- [F1] Dual-write problem occurs (DB commit + event publish failure)
- [F2] Zombie state persists > 5 minutes
- [F3] SKIP LOCKED allows distributed duplicate processing
- [F4] DLQ Triple Safety Net failure → permanent data loss

---

## Verification Commands

### Architecture Compliance

```bash
# 1. Module dependency direction
./gradlew module-app:dependencies --configuration runtimeClasspath

# 2. Domain layer purity (no Spring annotations)
grep -r "@Component\|@Service\|@Repository\|@Controller" module-core/src/main/java/ | wc -l
# Expected: 0

# 3. ArchUnit rules
./gradlew test --tests "maple.expectation.architecture.ArchitectureTest"

# 4. Exception hierarchy compliance
./gradlew test --tests "*Exception*Test"

# 5. Scheduler overlap prevention
grep -r "fixedRate" module-app/src/main/java/maple/expectation/scheduler/
# Expected: No results
```

---

## GR-ARCH-007: CQRS Command Side - JDBC Batch over JPA

**Source:** ADR-035: Command Side JPA → JDBC Batch Refactoring

### DON'T (Anti-Patterns)

```java
// ❌ JPA IDENTITY: Disables batch insert
@Entity
public class CharacterEquipment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // Forces individual INSERTs
    private Long id;
}

// ❌ JPA saveAll(): N+1 problem for 30M rows
@Repository
public class EquipmentRepository {
    // 1M rows = 15.2 seconds = 650 rows/sec
    public void saveAll(List<CharacterEquipment> entities) {
        jpaRepository.saveAll(entities);  // No batching with IDENTITY
    }
}
```

**Consequences:**
- IDENTITY strategy disables JDBC batch (JPA needs immediate ID)
- 30M rows = ~7.6 hours (unacceptable)
- Network round-trip: 1M requests

### DO (Best Practices)

```java
// ✅ JDBC BATCH: 33x faster (15.2s → 0.4s for 10K rows)
@Repository
@RequiredArgsConstructor
public class JdbcBatchUpsertRepository {
    private final JdbcTemplate jdbcTemplate;

    private static final String UPSERT_SQL = """
        INSERT INTO character_equipment
            (character_id, equipment_slot, item_id, item_name, star_force, ...)
        VALUES (?, ?, ?, ?, ?, ...)
        ON DUPLICATE KEY UPDATE
            item_id = VALUES(item_id),
            item_name = VALUES(item_name),
            star_force = VALUES(star_force),
            ...
        """;

    public int[] batchUpsert(List<CharacterEquipment> entities) {
        List<Object[]> batchArgs = entities.stream()
            .map(e -> new Object[]{
                e.getCharacterId(),
                e.getEquipmentSlot(),
                e.getItemId(),
                // ... other fields
            })
            .toList();

        return jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs);
    }
}
```

### When to Use Each Approach

| Scenario | Recommended | Rationale |
|----------|-------------|-----------|
| **Command Side (CQRS)** | JDBC Batch | Write-only, no lazy loading, no associations |
| **Query Side (CQRS)** | JPA/MongoDB | Read-optimized, complex queries |
| **Simple CRUD** | JPA | Rapid development, adequate performance |
| **Bulk Insert (>100K)** | JDBC Batch | 30-40x faster |

### JPA Limitations in CQRS Context

| JPA Feature | V5 Command Side Needed? | Verdict |
|-------------|------------------------|---------|
| Lazy Loading | ❌ (MongoDB handles reads) | Unnecessary |
| Association Mapping | ❌ (Single table upsert) | Unnecessary |
| Dirty Checking | ❌ (Always overwrite) | Unnecessary |
| 1st Level Cache | ❌ (Write-only) | Unnecessary |

**Conclusion:** JPA advantages are irrelevant in write-only Command Side.

---

## References

- **ADR-041:** Multi-Module Hexagonal Architecture with DIP
- **ADR-017:** Domain Extraction - Clean Architecture Migration
- **ADR-052:** Resilience4j Circuit Breaker
- **ADR-044:** LogicExecutor Zero Try-Catch Policy
- **ADR-034:** Scheduler Thread Pool Configuration
- **ADR-010:** Transactional Outbox Pattern
- **ADR-035:** Command Side JPA → JDBC Batch Refactoring
