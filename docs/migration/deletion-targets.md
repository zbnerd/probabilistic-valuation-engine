# PostgreSQL Migration: Deletion Targets

> **Last Updated**: 2026-03-09
> **Branch**: `v2/postgresql-redesign`
> **Related Issues**: #548, #547, #551

## Overview

Files scheduled for deletion or migration during the PostgreSQL unification (Issue #548).

**Important**: This is documentation ONLY. Do NOT delete any files until the corresponding migration phase is complete and verified.

---

## Phase 0: Foundation (Current Phase)

### 삭제 불필요
현재 단계는 Kotlin 변환 기반 구축만 수행하므로 삭제 대상 없음.

---

## Phase 1: MySQL -> PostgreSQL

### JPA Entities (module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/entity/)

These JPA entities will be converted to PostgreSQL entities:

- [ ] `GameCharacterJpaEntity.kt` - Game character data
- [ ] `CharacterEquipmentJpaEntity.kt` - Equipment data
- [ ] `CharacterLikeJpaEntity.kt` - Like relationships

**Action**: Migrate to PostgreSQL dialect (Hibernate ORM already supports PostgreSQL).

---

### JPA Repositories (module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/)

Spring Data JPA repositories will be updated:

- [ ] `GameCharacterJpaRepository.kt`
- [ ] `GameCharacterJpaRepositoryCustom.kt`
- [ ] `GameCharacterJpaRepositoryCustomImpl.kt`
- [ ] `CharacterLikeJpaRepository.kt`
- [ ] `MemberJpaRepository.kt`
- [ ] `CharacterEquipmentJpaRepository.kt`

**Action**: Update dialect configuration; no code changes required (Hibernate abstraction).

---

### Repository Implementations (module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/)

Repository implementations using JDBC/JPA:

- [ ] `GameCharacterRepositoryImpl.kt`
- [ ] `CharacterEquipmentRepositoryImpl.kt`
- [ ] `CharacterLikeRepositoryImpl.kt`
- [ ] `NexonCharacterRepositoryImpl.kt`
- [ ] `NexonCharacterRepositoryCustom.kt`
- [ ] `NexonCharacterRepository.kt`
- [ ] `EquipmentExpectationSummaryRepository.kt`
- [ ] `MemberRepositoryImpl.kt`
- [ ] `MemberRepository.kt`
- [ ] `CubeProbabilityRepositoryImpl.kt`
- [ ] `CubeProbabilityRepository.kt`

**Action**: Update datasource configuration; minimal code changes.

---

### MySQL-Specific Configuration Files

- [ ] `module-app/src/main/resources/application.yml` - MySQL datasource configuration
- [ ] `module-app/src/main/resources/application-local.yml` - Local MySQL config
- [ ] `module-app/src/main/resources/application-prod.yml` - Production MySQL config
- [ ] `module-app/src/main/resources/application-test.yml` - Test MySQL config
- [ ] `module-app/src/main/resources/application-ci.yml` - CI MySQL config
- [ ] `module-app/src/main/resources/application-chaos.yml` - Chaos test MySQL config

**Action**: Replace MySQL datasource with PostgreSQL datasource.

---

### MySQL Lock Strategy (Already Deprecated per ADR-310)

**Status**: MIGRATED to Redis (Issue #310)

These files were already migrated from MySQL Named Lock to Redis Distributed Lock:

- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/MySqlNamedLockStrategy.kt` - DELETED
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockHikariConfig.kt` - DELETED
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/config/LockHikariConfig.kt` - DELETED

**Note**: These files are already deleted as part of ADR-310 (Redis Lock Migration).

---

### MySQL Resilience Components (module-infra/src/main/kotlin/maple/expectation/infrastructure/resilience/)

MySQL-specific resilience features will be removed:

- [ ] `MySQLFallbackProperties.kt` - Fallback configuration
- [ ] `MySQLHealthState.kt` - Health state management
- [ ] `MySQLHealthEventPublisher.kt` - Event publishing
- [ ] `MySQLDownEvent.kt` - Down event
- [ ] `MySQLUpEvent.kt` - Up event
- [ ] `CompensationLogService.kt` - Compensation logging

**Action**: Remove after verifying PostgreSQL fallback is not needed (PGMQ provides better durability).

---

### MySQL Exception Classes (module-common/src/main/kotlin/maple/expectation/error/exception/)

- [ ] `MySQLFallbackException.kt` - Fallback exception

**Action**: Remove after migration.

---

### JDBC Batch Repository (module-infra/src/main/kotlin/maple/expectation/infrastructure/jdbc/)

- [ ] `JdbcBatchUpsertRepository.kt` - Batch upsert operations

**Action**: Keep but update for PostgreSQL dialect (UPSERT syntax differs).

---

## Phase 2: MongoDB -> PostgreSQL JSONB

### MongoDB Domain Models (module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/)

V5 CQRS Read Side models using MongoDB:

- [ ] `CharacterValuationView.kt` - Character valuation document
- [ ] `CharacterValuationRepository.kt` - Repository interface

**Action**: Convert to PostgreSQL JSONB column in new table.

---

### MongoDB Services (module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/)

- [ ] `CharacterViewQueryService.kt` - Query service for views
- [ ] `BatchCharacterViewService.kt` - Batch view operations
- [ ] `MongoDBConfig.kt` - MongoDB configuration
- [ ] `MongoDBHealthIndicator.kt` - Health check
- [ ] `HealthCheck.kt` - Health check utilities

**Action**: Replace with PostgreSQL JSONB queries.

---

### MongoDB Sync Worker (module-app/src/main/java/maple/expectation/application/worker/)

- [ ] `MongoDBSyncWorker.java` - Background sync worker

**Action**: Replace with PostgreSQL change tracking or trigger-based sync.

---

### MongoDB Test Files (module-infra/src/test/java/maple/expectation/infrastructure/mongodb/)

- [ ] `CharacterViewQueryServiceTest.java`
- [ ] `CharacterViewQueryServiceOptimisticLockTest.java`
- [ ] `CharacterViewQueryServiceIdempotencyTest.java`
- [ ] `MongoDBSyncWorkerIntegrationTest.java`
- [ ] `MongoDBSyncWorkerTest.java`

**Action**: Replace with PostgreSQL integration tests.

---

### Docker Compose MongoDB Service

**File**: `docker-compose.yml`

- [ ] Lines 147-165: `mongodb` service definition
- [ ] Line 218: `mongodb_data` volume

**Action**: Remove service after data migration.

---

## Phase 3: Redis Queue -> PGMQ

### Redis Queue Components (module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/)

**Strategy Pattern Files** (to be replaced with PGMQ equivalent):

- [ ] `strategy/RedisBufferStrategy.kt` - Buffer strategy
- [ ] `strategy/RedisQueueRecoveryHandler.kt` - Queue recovery
- [ ] `strategy/RedisLuaScriptExecutor.kt` - Lua script execution

**Priority Queue**:

- [ ] `priority/PriorityCalculationQueue.kt` - Priority queue implementation

**Like Buffer** (Real-time sync uses Redis pub/sub):

- [ ] `like/RedisLikeRelationBuffer.kt` - Like buffer
- [ ] `like/RedisLikeBufferStorage.kt` - Storage
- [ ] `like/AtomicLikeToggleExecutor.kt` - Atomic toggle
- [ ] `like/PartitionedFlushStrategy.kt` - Flush strategy
- [ ] `like/realtime/RedisLikeEventPublisher.kt` - Event publisher
- [ ] `like/realtime/RedisLikeEventSubscriber.kt` - Event subscriber
- [ ] `like/realtime/ReliableRedisLikeEventPublisher.kt` - Reliable publisher
- [ ] `like/realtime/ReliableRedisLikeEventSubscriber.kt` - Reliable subscriber

**Persistence Tracking**:

- [ ] `persistence/RedisEquipmentPersistenceTracker.kt` - Persistence tracker

**Keys**:

- [ ] `RedisKey.kt` - Redis key definitions

**Action**: Migrate to PGMQ for durable messaging; keep Redis for cache only.

---

### Redis Messaging (module-infra/src/main/kotlin/maple/expectation/infrastructure/messaging/)

- [ ] `RedisMessageTopic.kt` - Pub/Sub topic
- [ ] `RedisMessageQueue.kt` - Message queue

**Action**: Replace with PGMQ for durable messaging.

---

### Redis Messaging Config (module-infra/src/main/kotlin/maple/expectation/infrastructure/config/)

- [ ] `MessagingConfig.kt` - Messaging configuration
- [ ] `LikeRealtimeSyncConfig.kt` - Like sync config
- [ ] `LikeBufferConfig.kt` - Like buffer config

**Action**: Update for PGMQ configuration.

---

### Cache Invalidation (Uses Redis Pub/Sub - KEEP with Redis)

**Status**: These use Redis pub/sub for cache invalidation, which is appropriate for Redis.

**Files to KEEP** (cache invalidation stays on Redis):

- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationPublisher.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/CacheInvalidationSubscriber.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/RedisCacheInvalidationPublisher.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/invalidation/impl/RedisCacheInvalidationSubscriber.kt`

**Reason**: Cache invalidation via pub/sub is a Redis best practice and should remain.

---

### Redis Session Storage (KEEP with Redis)

**Files to KEEP** (session storage stays on Redis):

- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisSessionRepository.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisSessionRepositoryImpl.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisRefreshTokenRepository.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/RedisRefreshTokenRepositoryImpl.kt`

**Reason**: Session storage in Redis is appropriate and performant.

---

### Redis Lock Strategy (KEEP with Redis)

**Files to KEEP** (distributed lock stays on Redis):

- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/RedisDistributedLockStrategy.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/ResilientLockStrategy.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategy.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockStrategyConfiguration.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/OrderedLockExecutor.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockMetrics.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockFallbackMetrics.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/LockOrderMetrics.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/AbstractLockStrategy.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/lock/GuavaLockStrategy.kt`

**Reason**: Redis distributed locks are mature and performant; should remain.

---

### Redis Cache (KEEP with Redis)

**Files to KEEP** (cache stays on Redis):

- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/TieredCache.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/HybridLikeRelationBuffer.kt`
- [x] `module-infra/src/main/kotlin/maple/expectation/infrastructure/cache/like/InMemoryLikeBufferStorage.kt`

**Reason**: Caching is Redis's primary use case; should remain.

---

### Docker Compose Redis Services

**Status**: Redis HA services remain for caching and session storage.

**Files to KEEP**:

- [x] `docker-compose.yml` - Lines 25-125: Redis Master, Slave, Sentinel services
- [x] `docker-compose.yml` - Line 217: `redis_data` volume

**Action**: Keep Redis for cache, session, and lock use cases.

---

## Disabled Files (현재 비활성화된 파일)

```
module-core/src/test/java/maple/expectation/properties/DeterminismPropertiesTemplate.java.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxIntegrationTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventOutboxProcessorTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/event/outbox/EventDlqHandlerTest.kt.disabled
module-infra/src/test/kotlin/maple/expectation/infrastructure/messaging/RedisStreamEventConsumerTest.kt.disabled
```

---

## Application Configuration Changes

### application.yml Updates

**MySQL DataSource** (to be replaced):

```yaml
# DELETE these lines:
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"
```

**PostgreSQL DataSource** (to be added):

```yaml
# ADD these lines:
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    hikari:
      # PostgreSQL doesn't need lock_wait_timeout
```

---

### Docker Compose Updates

**DELETE** (after Phase 2):

```yaml
# Lines 4-23: MySQL service
db:
  image: mysql:8.0
  # ...

# Lines 147-165: MongoDB service
mongodb:
  image: mongo:7.0
  # ...
```

**KEEP** (PostgreSQL already exists in docker-compose.yml):

```yaml
# Lines 167-188: PostgreSQL service (already added)
postgres:
  image: postgres:16
  # ...
```

---

## Dependencies (build.gradle.kts)

### Dependencies to REMOVE:

```kotlin
// MySQL Driver
implementation("com.mysql:mysql-connector-j")

// MongoDB (if using Spring Data MongoDB)
implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
implementation("org.mongodb:mongodb-driver-sync")
```

### Dependencies to ADD:

```kotlin
// PostgreSQL Driver
implementation("org.postgresql:postgresql")

// PGMQ Java Client
implementation("io.tembo:pgmq-java:0.9.0") // Verify latest version
```

---

## Test Files to Update

### Integration Tests

All integration tests using MySQL or MongoDB containers will need updates:

- [ ] `module-app/src/test/java/maple/expectation/support/SharedContainers.java`
- [ ] `module-app/src/test/java/maple/expectation/support/AbstractContainerBaseTest.java`
- [ ] `module-app/src/test-legacy/java/maple/expectation/support/AbstractContainerBaseTest.java`
- [ ] `module-chaos-test/src/chaos-test/java/maple/expectation/support/ContainerManager.java`
- [ ] `module-chaos-test/src/chaos-test/java/maple/expectation/support/AbstractContainerBaseTest.java`

**Action**: Replace MySQL/MongoDB containers with PostgreSQL Testcontainers.

---

### Chaos Tests

- [ ] `module-chaos-test/src/chaos-test/java/maple/expectation/global/resilience/MySQLResilienceIntegrationTest.java`

**Action**: Replace with PostgreSQL resilience tests.

---

### Lock Tests (Already migrated to Redis per ADR-310)

**Status**: Tests already updated for Redis lock.

- [x] `module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/DualRunLockTest.java`
- [x] `module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/RedisLockConsistencyTest.java`
- [x] `module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/CircularLockDeadlockNightmareTest.java`
- [x] `module-chaos-test/src/chaos-test/java/maple/expectation/global/lock/MetadataLockFreezeNightmareTest.java`

**Action**: No changes needed (already using Redis locks).

---

## Migration Timeline

| Phase | Duration | Target Date | Status |
|-------|----------|-------------|--------|
| **Phase 0** | Foundation | Week 1-2 | In Progress |
| - Docker Compose PostgreSQL + PGMQ setup | | | ✅ Done |
| - Gradle dependencies update | | | Pending |
| - Testcontainers PostgreSQL setup | | | Pending |
| **Phase 1** | MySQL -> PostgreSQL | Week 3-4 | Pending |
| - Entity migration | | | Blocked by Phase 0 |
| - Repository migration | | | Pending |
| - Configuration update | | | Pending |
| **Phase 2** | MongoDB -> PostgreSQL JSONB | Week 5-6 | Pending |
| - V5 CQRS Read Side migration | | | Blocked by Phase 1 |
| - JSONB schema design | | | Pending |
| **Phase 3** | Redis Queue -> PGMQ | Week 7-8 | Pending |
| - Queue logic migration | | | Blocked by Phase 2 |
| - Monitoring setup | | | Pending |
| **Phase 4** | Cleanup & Verification | Week 9-10 | Pending |
| - Delete old files | | | Blocked by Phase 3 |
| - Performance testing | | | Pending |

---

## Dependencies

### Blocked By:
- #547: Docker Compose PostgreSQL + PGMQ Setup (Phase 0) - ✅ Done
- ADR-001: PostgreSQL Single DB Strategy approval - ✅ Done

### Blocks:
- None (this is a foundational document for all migration work)

---

## Verification Checklist

Before deleting any file, verify:

- [ ] PostgreSQL equivalent is implemented
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Chaos tests pass
- [ ] Performance benchmarks acceptable
- [ ] Data migration completed
- [ ] Backup created
- [ ] Rollback plan tested

---

## Appendix: File Inventory

### Complete File Count Summary

| Category | Count | Action |
|----------|-------|--------|
| MySQL Entities | 3 | Migrate |
| MySQL Repositories | 15+ | Update config |
| MongoDB Documents | 1 | Convert to JSONB |
| MongoDB Services | 5 | Replace |
| Redis Queue Files | 20+ | Migrate to PGMQ |
| Redis Cache Files | 10+ | KEEP |
| Redis Lock Files | 10+ | KEEP |
| Test Files | 15+ | Update |

**Total**: 80+ files affected across 3 phases.

---

## Related Documents

- [ADR-001: PostgreSQL Single DB Strategy](../adr/001-postgresql-single-db-strategy.md)
- [Local DB Connection Guide](./local-db-connection-guide.md)
- [IntelliJ Kotlin Conversion Guide](./intellij-kotlin-conversion-guide.md)
- [Issue #547: Docker Compose PostgreSQL + PGMQ](https://github.com/zbnerd/probabilistic-valuation-engine/issues/547)
- [Issue #548: PostgreSQL Migration](https://github.com/zbnerd/probabilistic-valuation-engine/issues/548)

---

*Last Updated: 2026-03-09*
*Document Version: 1.1*
