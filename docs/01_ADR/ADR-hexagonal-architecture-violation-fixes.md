# ADR: Hexagonal Architecture Violation Fixes

## Status: Accepted

## Context

Architecture review of GameCharacterControllerV5 trace revealed 5 categories of hexagonal architecture violations:

1. **CRITICAL — app→web reverse dependency**: 23+ source files in module-app imported DTOs (`CubeCalculationInput`, `EquipmentExpectationResponseV4`) from module-web
2. **MAJOR — web→infra ghost dependency**: module-web declared `implementation project(':module-infra')` with zero actual imports
3. **MAJOR — Queue coupling**: `ExpectationCalculationQueue` directly imported `PgmqClient`, worker constants, and message types from infra
4. **MAJOR — Controller in wrong module**: `BulkLoadController` in module-app directly injected `BulkLoaderService` from infra
5. **MAJOR — Spring types in domain interface**: `GameCharacterRepository` used `org.springframework.data.domain.Pageable/Page` in method signatures

## Decision

### Fix 1: Move shared DTOs to module-core
- `CubeCalculationInput` → `module-core/.../core/dto/cube/`
- `EquipmentCalculationInput` → `module-core/.../core/dto/v4/`
- `EquipmentExpectationResponseV4` → `module-core/.../core/dto/v4/`
- module-core already has Jackson dependencies and no Spring deps (verified by `verifyNoSpringDependency` task)
- module-app→module-web dependency changed from `implementation` to `testImplementation`

### Fix 2: Remove ghost dependency
- Removed `implementation project(':module-infra')` from module-web/build.gradle

### Fix 3: Extract PgmqPort
- Created `PgmqPort` interface in module-core (send, queueLength, findActiveMessageIdByUserIgn)
- Created `QueueNames` constants in module-core
- Created `ExpectationCalcMessage` data class in module-core
- Created `PgmqPortAdapter` in module-infra (delegates to PgmqClient)
- `ExpectationCalculationQueue` now depends on core port, not infra PgmqClient

### Fix 4: Move BulkLoadController
- Created `BulkLoadPort` interface in module-core
- Created `BulkLoadPortAdapter` in module-app (delegates to BulkLoaderService)
- Moved `BulkLoadController` from module-app to module-web

### Fix 5: Remove Spring Pageable from repository
- Changed `GameCharacterRepository.findAll` to use core `PageRequest/Page`
- Conversion to Spring types happens in `GameCharacterRepositoryImpl` at adapter boundary
- Core `Page/PageRequest` types already existed in module-core

## Target dependency graph

```
module-common (base)
module-core → module-common (Spring-free)
module-infra → module-core, module-common
module-web → module-core, module-common (NOT module-infra)
module-app → module-core, module-infra, module-common (module-web as testImplementation only)
```

## Consequences

- module-core gains 3 DTO files (pure Kotlin data classes, no Spring deps)
- Application layer no longer depends on web layer at compile time
- Queue logic decoupled from PGMQ implementation details
- Domain repository interface free of Spring Framework types
- Future: LogicExecutor/TaskContext coupling in module-app remains (tracked separately)
