# Plan: PostgreSQL Migration Phase 2 - PGMQ & Domain Layer

## Context

This batch addresses 4 GitHub issues for the PostgreSQL migration:
- **#552**: PGMQ Consumer/Worker implementation
- **#553**: ADR-002 PGMQ Integration (already done, needs consumer update)
- **#549**: Domain entities (PostgreSQL + jsonb)
- **#551**: Repository layer + Port interfaces

### Current State

**Already Implemented:**
- PGMQ Client (`PgmqClient.kt`) with send/read/archive/delete operations
- PGMQ Configuration (`PgmqConfig.kt`) with Circuit Breaker
- PGMQ Message types (`PgmqMessage.kt`) with payload types
- 3 Queue Producers: Calculation, Donation, LikeSync
- Docker Compose with `pgmq/pgmq:latest` image
- Init script (`docker/postgres/init.sql`) with queues and tables
- Test infrastructure (`PgmqIsolationTest.kt`)

**Missing:**
- PGMQ Consumers/Workers for each queue
- Domain entities for PostgreSQL tables
- Repository implementations with Port interfaces

---

## Work Units

### Unit 1: PGMQ Worker Infrastructure
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorker.kt` (new)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/PgmqWorkerConfig.kt` (new)

**Description:**
Create base worker infrastructure with:
- `PgmqWorker<T>` abstract class with `processMessages()` method
- Retry logic with max retries (3 by default)
- Archive on success, delete on failure pattern
- Metrics integration (queue length, processing time)
- Configuration for polling interval, batch size

**Dependencies:** None (foundational)

---

### Unit 2: Calculation Worker
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/CalculationWorker.kt` (new)
- `module-infra/src/main/resources/maple-infra-defaults.properties` (update)

**Description:**
Implement `CalculationWorker` that:
- Consumes from `calculation_queue`
- Calls calculation service via Port interface
- Handles retries and DLQ (delete on max retries exceeded)
- Uses `@Scheduled(fixedDelay = 1000)` for polling
- Feature flag: `pgmq.worker.calculation.enabled=true`

**Dependencies:** Unit 1

---

### Unit 3: LikeSync Worker
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/LikeSyncWorker.kt` (new)
- `module-infra/src/main/resources/maple-infra-defaults.properties` (update)

**Description:**
Implement `LikeSyncWorker` that:
- Consumes from `like_sync_queue`
- Updates like count in database
- Batches delta updates for efficiency
- Feature flag: `pgmq.worker.like-sync.enabled=true`

**Dependencies:** Unit 1

---

### Unit 4: Donation Worker
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/DonationWorker.kt` (new)
- `module-infra/src/main/resources/maple-infra-defaults.properties` (update)

**Description:**
Implement `DonationWorker` that:
- Consumes from `donation_queue`
- Sends notifications (Discord, etc.)
- Updates donation statistics
- Feature flag: `pgmq.worker.donation.enabled=true`

**Dependencies:** Unit 1

---

### Unit 5: Domain Entities (PostgreSQL)
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/EquipmentDataEntity.kt` (new)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/UserSessionEntity.kt` (new)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/RefreshTokenEntity.kt` (new)

**Description:**
Create JPA entities for PostgreSQL tables:
- `EquipmentDataEntity` with JSONB column (`@Column(columnDefinition = "jsonb")`)
- `UserSessionEntity` for session storage
- `RefreshTokenEntity` for refresh tokens

**Dependencies:** None

---

### Unit 6: Port Interfaces
**Files:**
- `module-core/src/main/kotlin/maple/expectation/core/port/out/EquipmentDataPort.kt` (new)
- `module-core/src/main/kotlin/maple/expectation/core/port/out/SessionPort.kt` (new)

**Description:**
Define Port interfaces in `module-core`:
- `EquipmentDataPort`: save/find equipment JSON data
- `SessionPort`: create/find/invalidate sessions

**Dependencies:** None

---

### Unit 7: Repository Implementations
**Files:**
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/EquipmentDataRepository.kt` (new)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/UserSessionRepository.kt` (new)
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RefreshTokenRepository.kt` (new)

**Description:**
Implement Spring Data JPA repositories and Port adapters:
- `EquipmentDataRepository` with JSONB queries
- `UserSessionRepository` with expiration queries
- `RefreshTokenRepository` with user/token lookup

**Dependencies:** Unit 5, Unit 6

---

### Unit 8: Worker Integration Tests
**Files:**
- `module-app/src/test/kotlin/maple/expectation/worker/CalculationWorkerTest.kt` (new)
- `module-app/src/test/kotlin/maple/expectation/worker/LikeSyncWorkerTest.kt` (new)
- `module-app/src/test/kotlin/maple/expectation/worker/DonationWorkerTest.kt` (new)

**Description:**
Integration tests for each worker using Testcontainers:
- Test message consumption and processing
- Test retry logic
- Test archive/delete behavior
- Test circuit breaker fallback

**Dependencies:** Units 2, 3, 4

---

## E2E Test Recipe

### Option A: Unit Tests Only (Recommended for this batch)
All changes are backend infrastructure. Unit tests with Testcontainers verify functionality.

```bash
# Run all PGMQ-related tests
./gradlew test --tests "*Pgmq*" --tests "*Worker*"
```

### Option B: Manual Verification
1. Start PostgreSQL with PGMQ: `docker compose -f docker-compose.postgres.yml up -d`
2. Run application: `./gradlew bootRun --args='--spring.profiles.active=pglocal'`
3. Publish test message via API or directly to queue
4. Verify worker processes message and archives it

---

## Execution Order

```
Unit 1 (Worker Infrastructure)
    │
    ├── Unit 2 (Calculation Worker) ──┐
    ├── Unit 3 (LikeSync Worker)    ──┼── Unit 8 (Worker Tests)
    └── Unit 4 (Donation Worker)    ──┘

Unit 5 (Entities)
    │
    └── Unit 7 (Repositories)
            │
Unit 6 (Ports) ────────────────────────┘
```

**Recommended parallelization:**
- Batch 1: Units 1, 5, 6 (independent, no dependencies)
- Batch 2: Units 2, 3, 4 (depend on Unit 1)
- Batch 3: Unit 7 (depends on 5, 6)
- Batch 4: Unit 8 (depends on 2, 3, 4)

---

## Conventions to Follow

1. **Zero Try-Catch Policy**: Use `LogicExecutor` for all exception handling
2. **Circuit Breaker**: All PGMQ operations protected by Resilience4j
3. **FQCN Prohibited**: Use imports, not fully qualified names
4. **Kotlin**: All new infrastructure code must be Kotlin
5. **Explicit Transaction Manager**: `@Transactional("transactionManager")`
6. **Port/Adapter Pattern**: Domain ports in `module-core`, implementations in `module-infra`
7. **Feature Flags**: All workers configurable via properties
