# ADR-019: N+1 Query Optimization

## Status
Accepted (2025-03-08)
Updated (2026-03-08): Extended configuration to CI and Chaos profiles

## Context
probabilistic-valuation-engine application uses JPA for data persistence. During the PostgreSQL redesign (Phase 7), we identified that the default JPA configuration could lead to N+1 query problems when loading multiple entities with lazy-loaded relationships.

## Problem
1. **Default JPA Behavior**: Without explicit batch fetching configuration, Hibernate loads lazy-loaded associations one-by-one when accessed in a loop
2. **Performance Impact**: N+1 queries cause excessive database round-trips, especially for methods like `findAll()` and `findActiveCharacters()`
3. **Hidden Issue**: Open Session In View (OSIV) can mask N+1 problems during development but cause production issues

## Analysis
### Current Entity Relationships
After analyzing the codebase:
- **GameCharacterJpaEntity** (clean architecture): No lazy relationships
- **GameCharacter** (v2 domain): Has `@OneToOne(fetch = FetchType.LAZY)` with CharacterEquipmentJpaEntity
- **CharacterLikeJpaEntity**: No lazy relationships
- **Member**: No lazy relationships

### Key Findings
1. The codebase is well-architected with minimal lazy relationships
2. The only LAZY relationship (`GameCharacter.equipment`) is marked `@JsonIgnore` and not accessed in loops
3. Equipment is loaded separately via `CharacterEquipmentRepository`
4. Despite minimal risk, configuring batch fetching provides:
   - Future-proofing for new entities
   - Performance optimization for bulk operations
   - Protection against accidental N+1 queries

## Decision
Configure Hibernate batch fetching and JDBC batch optimization across all environments (local, prod, CI, chaos).

### Configuration Changes
**Base configuration (application.yml):**
```yaml
spring:
  jpa:
    open-in-view: false  # P2-24: Explicitly disable OSIV
    properties:
      hibernate:
        default_batch_fetch_size: 100  # P2-24: N+1 query prevention
        jdbc:
          batch_size: 1000             # Base JDBC batch size
```

**Profile-specific (local, prod, CI, chaos):**
```yaml
spring:
  jpa:
    open-in-view: false  # P2-24: Explicitly disable OSIV
    properties:
      hibernate:
        default_batch_fetch_size: 100  # Batch size for lazy loading
        jdbc:
          batch_size: 100              # JDBC batch size for inserts/updates
        order_inserts: true            # Order inserts for better batching
        order_updates: true            # Order updates for better batching
```

### Files Modified
- ✅ `application.yml` - Added `default_batch_fetch_size: 100` to base configuration
- ✅ `application-local.yml` - Already configured
- ✅ `application-prod.yml` - Already configured
- ✅ `application-ci.yml` - Added complete batch configuration
- ✅ `application-chaos.yml` - Added complete batch configuration

### Why These Settings?
1. **default_batch_fetch_size: 100**
   - Configures batch fetching for lazy associations
   - When accessing lazy relationships in a loop, Hibernate loads them in batches of 100
   - Reduces N+1 queries from N to N/100

2. **jdbc.batch_size: 100**
   - Enables JDBC batching for INSERT/UPDATE operations
   - Reduces database round-trips for bulk operations
   - Works with `order_inserts` and `order_updates` for optimal batching

3. **open-in-view: false**
   - Explicitly disables OSIV pattern
   - Forces developers to handle lazy loading within transaction boundaries
   - Helps catch N+1 query problems during development

## Alternatives Considered
1. **@EntityGraph**: Not needed - no complex entity relationships requiring eager loading
2. **JOIN FETCH queries**: Not needed - no @OneToMany/@ManyToOne relationships
3. **@BatchSize annotation**: Not as flexible as global configuration

## Consequences
### Positive
- **Performance**: Up to 100x reduction in lazy loading queries
- **JDBC Efficiency**: Bulk operations use batching
- **Future-Proof**: Protects against N+1 queries as codebase grows
- **Explicit Boundaries**: OSIV disabled forces proper transaction design

### Negative
- **Memory Usage**: Slightly higher memory usage for batch loading (100 entities vs 1)
- **Cache Pressure**: Larger batches may impact L1/L2 cache

### Neutral
- **Configuration**: Added 4 new properties to application.yml files

## Validation
1. ✅ No compilation errors
2. ✅ Existing entity relationships analyzed
3. ✅ Configuration follows Hibernate best practices
4. ✅ Compatible with PostgreSQL redesign (Phase 7)

## Related Issues
- P1-12: N+1 Query Optimization
- P2-24: Open-in-View Explicit Configuration

## References
- [Hibernate Batch Fetching Documentation](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#batch-fetching)
- [Spring Boot JPA Properties](https://docs.spring.io/spring-boot/docs/3.5.x/reference/html/data.html#data.sql.jpa.properties)
