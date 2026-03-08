# ADR-013: Multi-DataSource Transaction Strategy

**Status:** Accepted
**Date:** 2026-03-08
**Issue:** P1-11, #158

## Context

The application currently uses a single MySQL/JPA datasource for all persistent operations. However, the V5 CQRS architecture introduces MongoDB as a read model store, and future plans include MongoDB read replicas for query scalability.

### Current State
- **Primary DataSource:** MySQL/JPA (`transactionManager`)
- **All Repositories:** Use `@Transactional` without explicit qualifier
- **Risk:** Transaction ambiguity when multiple transaction managers exist

### Future State (MongoDB Read Replicas)
- **Secondary TransactionManager:** `mongoTransactionManager`
- **CQRS Pattern:** Write to MySQL, Read from MongoDB
- **Challenge:** Explicit transaction manager qualification required

## Decision

All JPA repository implementations MUST use explicit `@Transactional("transactionManager")` qualifier to prevent ambiguity in multi-datasource environments.

### Implementation Pattern

```kotlin
@Repository
@Transactional("transactionManager")  // ← Explicit qualifier required
open class CharacterEquipmentRepositoryImpl(
    private val jpaRepo: CharacterEquipmentJpaRepository,
) : DomainCharacterEquipmentRepository {

    @Transactional(readOnly = true)  // Inherits "transactionManager" from class
    override fun findById(id: CharacterId): CharacterEquipment? {
        return jpaRepo.findById(id.value).map { it.toDomain() }.orElse(null)
    }
}
```

### Method-Level Override

When a specific method needs different transaction behavior:

```kotlin
@Transactional("transactionManager", propagation = Propagation.REQUIRES_NEW)
fun upsertSummary(...) {
    // Independent transaction
}
```

## Architecture

### Transaction Manager Beans

```kotlin
@Configuration
class TransactionConfig {

    @Bean
    @Primary
    fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate {
        return TransactionTemplate(transactionManager)  // MySQL/JPA
    }

    // Future: MongoDB transaction manager
    // @Bean
    // fun mongoTransactionTemplate(mongoTransactionManager): TransactionTemplate {
    //     return TransactionTemplate(mongoTransactionManager)
    // }
}
```

### Repository Classification

| Repository Type | Transaction Manager | Usage |
|----------------|---------------------|-------|
| JPA Repositories | `transactionManager` | MySQL read/write |
| MongoDB Repositories (Future) | `mongoTransactionManager` | MongoDB read model |

## Migration Path

### Phase 1: Current (Single DataSource)
- ✅ All JPA repositories use `@Transactional("transactionManager")`
- ✅ No transaction ambiguity
- ✅ Ready for MongoDB addition

### Phase 2: MongoDB Read Model (Planned)
1. Add `mongoTransactionManager` bean
2. MongoDB repositories use `@Transactional("mongoTransactionManager")`
3. MySQL repositories continue using `@Transactional("transactionManager")`

### Phase 3: Read Replicas (Future)
- MongoDB secondaries for read scalability
- Read concern: `majority` (strong consistency)
- Write concern: `majority` (durability)

## Benefits

1. **No Ambiguity:** Explicit qualifiers prevent Spring transaction manager confusion
2. **Migration Ready:** Infrastructure prepared for multi-datasource architecture
3. **Clear Intent:** Each repository declares its transaction manager explicitly
4. **Type Safety:** Compilation-time verification of transaction manager usage

## Consequences

### Positive
- ✅ Clear transaction boundary definitions
- ✅ No runtime ambiguity errors
- ✅ Ready for MongoDB read replicas
- ✅ Supports CQRS pattern migration

### Negative
- ⚠️ Slightly verbose annotation syntax
- ⚠️ Must remember to add qualifier to new repositories

### Mitigation
- Code review checklist: Verify `@Transactional("transactionManager")` on new repositories
- IDE templates: Pre-configure annotation with qualifier
- ArchUnit rules: Enforce explicit qualifier usage

## Related ADRs

- [ADR-004: Module Core Migration](ADR-004-module-core-migration.md)
- [ADR-019: N+1 Query Optimization](019-n-plus-one-query-optimization.md)
- [V5 Stateless Architecture](../00_Start_Here/ROADMAP.md#phase-5)

## References

- [Issue #158: Expectation API 캐시 타겟 전환](https://github.com/issue/158)
- [P1-11: TransactionManager Multi-DataSource Support](https://github.com/issue/P1-11)
- [Spring Data MongoDB: Transaction Management](https://docs.spring.io/spring-data/mongodb/docs/current/reference/html/#transactions)
- [CQRS Pattern with Spring Boot](https://spring.io/blog/2023/11/08/cqrs-with-spring-boot)
