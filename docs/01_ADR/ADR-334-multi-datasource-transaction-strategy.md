# ADR-334: Multi-DataSource Transaction Strategy

## Status
Accepted (2026-03-08)

## Context
probabilistic-valuation-engine currently operates with a single datasource (MySQL/JPA) for transactional operations. However, the roadmap includes:

1. **V5 CQRS Implementation**: MongoDB for read models (conditionally enabled via `v5.enabled=true`)
2. **MongoDB Read Replicas**: Planned for scaling read operations
3. **Multi-Database Transactions**: Need to coordinate transactions across MySQL and MongoDB

### Current State
- **Single TransactionManager**: `transactionManager` (MySQL/JPA)
- **Implicit Transaction Binding**: Most repositories use `@Transactional` without explicit qualifiers
- **MongoDB**: Conditionally enabled, separate configuration in `MongoDBConfig.kt`

### Problem
When multiple transaction managers exist in the application context, Spring requires explicit qualifiers to resolve ambiguity:

```kotlin
// Ambiguous when multiple TransactionManager beans exist
@Transactional
fun save(data: Data) { ... }

// Explicit qualifier required
@Transactional("transactionManager")
fun save(data: Data) { ... }
```

Without explicit qualifiers, Spring throws:
```
org.springframework.beans.factory.NoUniqueBeanDefinitionException:
No qualifying bean of type 'PlatformTransactionManager' available:
expected single matching bean but found 2: transactionManager, mongoTransactionManager
```

## Decision

### 1. Explicit TransactionManager Qualifiers
All JPA repositories MUST use explicit `"transactionManager"` qualifier:

```kotlin
@Repository
@Transactional("transactionManager")
open class GameCharacterRepositoryImpl(
    private val jpaRepo: GameCharacterJpaRepository,
) : DomainGameCharacterRepository {
    // All methods inherit "transactionManager" qualifier
}
```

### 2. Class-Level @Transactional Strategy
Apply `@Transactional("transactionManager")` at the class level:
- Reduces boilerplate
- Ensures all methods use the correct transaction manager
- Allows method-level overrides for specific propagation behaviors

### 3. Method-Level Overrides
For methods requiring specific propagation (e.g., `REQUIRES_NEW`):

```kotlin
@Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
fun upsertExpectationSummary(...) { ... }
```

### 4. Read-Only Transactions
Use explicit qualifier with read-only flag:

```kotlin
@Transactional("transactionManager", readOnly = true)
fun findById(id: Long): Entity? { ... }
```

### 5. Future MongoDB Transaction Manager
When MongoDB read replicas are added, create a separate transaction manager:

```kotlin
@Configuration
class MongoDBTransactionConfig {
    @Bean
    fun mongoTransactionManager(
        dbFactory: MongoDatabaseFactory
    ): MongoTransactionManager {
        return MongoTransactionManager(dbFactory)
    }
}
```

MongoDB repositories will then use:
```kotlin
@Transactional("mongoTransactionManager")
fun saveView(view: CharacterView) { ... }
```

## Consequences

### Positive
- **Future-Proof**: Ready for multi-datasource migration without code changes
- **Explicit Intent**: Clear which datasource each repository uses
- **No Runtime Errors**: Prevents `NoUniqueBeanDefinitionException`
- **Backward Compatible**: Works with single datasource configuration

### Negative
- **Verbose Slightly**: More boilerplate than implicit `@Transactional`
- **Migration Effort**: Required updating all existing repository implementations

### Neutral
- **Documentation**: Self-documenting code shows transaction boundaries clearly

## Implementation

### Phase 1: Repository Layer (Completed)
1. `TransactionConfig.kt`: Added multi-datasource documentation
2. `GameCharacterRepositoryImpl.kt`: Added explicit qualifier
3. `CharacterEquipmentRepositoryImpl.kt`: Added explicit qualifier
4. `CharacterLikeRepositoryImpl.kt`: Added explicit qualifier
5. `NexonCharacterRepositoryImpl.kt`: Added explicit qualifier
6. `EquipmentExpectationSummaryRepository.kt`: Added explicit qualifier (already present)

### Phase 2: Service Layer (Completed 2026-03-08)
All service classes now use explicit `@Transactional("transactionManager")`:

**Java Services:**
1. `EquipmentApplicationService.java`: Class + method-level
2. `GameCharacterService.java`: Method-level
3. `CharacterCreationService.java`: Method-level with REQUIRES_NEW
4. `LikeRelationSyncService.java`: Method-level with READ_COMMITTED
5. `DlqAdminService.java`: Multiple method-level
6. `DonationService.java`: Method-level with READ_COMMITTED
7. `InternalPointPaymentStrategy.java`: Method-level
8. `EquipmentExpectationServiceV4.java`: Method-level
9. `DonationProcessor.java`: Method-level with MANDATORY
10. `TransactionalEventPublisher.java`: Method-level
11. `CharacterAsyncService.java`: Method-level

**Kotlin Services:**
1. `EquipmentDbWorker.kt`: Method-level with REQUIRES_NEW
2. `BatchWriter.kt`: Method-level
3. `CharacterLikeJpaRepository.kt`: Method-level
4. `LikeSyncExecutor.kt`: Method-level with REQUIRES_NEW
5. `OutboxProcessor.kt`: Method-level
6. `OutboxMetrics.kt`: Method-level
7. `OutboxFetchFacade.kt`: Method-level with READ_COMMITTED
8. `EventOutboxFetchFacade.kt`: Method-level with READ_COMMITTED
9. `NexonApiOutboxProcessor.kt`: Method-level
10. `NexonApiOutboxMetrics.kt`: Method-level
11. `NexonApiOutboxFetchFacade.kt`: Method-level with READ_COMMITTED

### Phase 3: ArchUnit Test (Completed 2026-03-08)
Created `TransactionManagerBindingTest.java` to enforce explicit transaction manager binding and prevent future violations.

### Migration Path
1. ✅ Add explicit qualifiers to all JPA repositories
2. ✅ Add explicit qualifiers to all service classes
3. ✅ Document current single-datasource architecture
4. ✅ Create ArchUnit test for enforcement
5. ⏳ Add `mongoTransactionManager` when enabling read replicas
6. ⏳ Update MongoDB repositories to use `"mongoTransactionManager"`

## Related Issues
- **P1-11**: TransactionManager Multi-DataSource Support
- **V5 CQRS**: MongoDB read model implementation
- **Issue #158**: TransactionTemplate configuration

## References
- [Spring Data MongoDB Reference - Transaction Management](https://docs.spring.io/spring-data/mongodb/docs/current/reference/html/#transactions)
- [Spring Framework - Multiple Transaction Managers](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html#tx-multiple-tx-mgrs-with-attransactional)
- [TransactionConfig.kt](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/config/TransactionConfig.kt)
- [MongoDBConfig.kt](../../module-infra/src/main/kotlin/maple/expectation/infrastructure/mongodb/MongoDBConfig.kt)
