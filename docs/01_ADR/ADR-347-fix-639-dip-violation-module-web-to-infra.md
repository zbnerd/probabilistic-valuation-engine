# ADR-347: Fix Issue #639 - DIP Violation: module-web → module-infra Direct Dependency

## Date
2025-03-30

## Status
Proposed

## Context
[P1][Architecture] Issue #639 identifies that `module-web` directly depends on `module-infra` implementation classes, violating the Dependency Inversion Principle (DIP) of Hexagonal Architecture (ADR-005).

### Current Violations

| File | Infra Dependency | Severity |
|------|-----------------|----------|
| `GameCharacterControllerV5.kt` | `LogicExecutor`, `TaskContext`, `CharacterValuationViewEntity` | High |
| `GameCharacterControllerV4.kt` | `GlobalAdmissionControl`, `AuthenticatedUser` | High |
| `AlertTestController.kt` | `LogicExecutor`, `TaskContext` | High |
| `GlobalExceptionHandler.kt` | `RateLimitExceededException` | Medium |
| `AuthController.kt` | `AuthenticatedUser` | High |
| `AdminController.kt` | `AuthenticatedUser` | High |
| `DonationController.kt` | `AuthenticatedUser` | High |
| `CharacterViewMapper.kt` | `CharacterValuationViewEntity` | High |
| `WebConfig.kt` | `MDCFilter` | Low |
| `CorsProperties.kt` | `ValidCorsOrigin` | Low |

## Decision

### Phase 1: Create ExecutorPort (High Priority)

**1.1 Create ExecutorPort in module-core**
- Port interface in `module-core/port/inbound/ExecutorPort.kt`
- Methods: `execute()`, `executeVoid()`, `executeOrDefault()`
- Reuses `TaskContext` from infra (pragmatic approach)

**1.2 Create ApplicationExecutionPort in module-app**
- Adapter implements `ExecutorPort` (renamed to ApplicationExecutionPort to avoid ArchUnit thread pool rule)
- Delegates to `LogicExecutor` from module-infra
- Converts between port and infra interfaces if needed

**1.3 Update Controllers**
- `AlertTestController.kt`: Replace `LogicExecutor` with `ExecutorPort`
- `GameCharacterControllerV5.kt`: Replace `LogicExecutor` with `ExecutorPort`

### Phase 2: Move Common Exceptions to module-core

**2.1 Create Exception Package**
- `module-core/exception/` package
- Move `RateLimitExceededException` from infra to core

**2.2 Update Imports**
- Update `GlobalExceptionHandler` to use core exception
- Update infra implementation if needed

### Phase 3: Create SecurityPort

**3.1 Create AuthenticatedUser DTO**
- Move or create `AuthenticatedUser` DTO in module-core
- Or create `SecurityPort` interface for user info access

**3.2 Update Controllers**
- Replace direct `AuthenticatedUser` import with port or DTO
- Update `AuthController`, `AdminController`, `DonationController`

### Phase 4: Fix CharacterValuationViewEntity Exposure

**4.1 Create View DTO**
- Create `CharacterViewDto` in module-core
- Update `CharacterViewQueryPort` return type from `Any?` to DTO

**4.2 Update Mapper and Controller**
- `CharacterViewMapper` to use DTO instead of entity
- `GameCharacterControllerV5` to use DTO

### Phase 5: Infrastructure-Specific Classes

**5.1 Filter and Configuration**
- `MDCFilter`, `ValidCorsOrigin` are infra-specific
- Spring configuration classes can stay in web layer as they wire infrastructure

## Consequences

### Positive
- Enforces Hexagonal Architecture (ADR-005) boundaries
- module-web depends only on module-core ports and module-common
- Easier testing (can mock ports instead of infra)
- Clear separation of concerns

### Negative
- Multiple files to move (risk of merge conflicts)
- Some duplicated code during transition
- Need to update all imports

## Implementation Order

1. Create `ExecutorPort` in module-core (foundational)
2. Create `ApplicationExecutionPort` in module-app
3. Update controllers to use `ExecutorPort` instead of `LogicExecutor`
4. Move `RateLimitExceededException` to module-core
5. Create `SecurityPort` and handle `AuthenticatedUser`
6. Fix `CharacterViewQueryPort` return type with DTO
7. Update all controllers to use ports
8. Remove unused imports from module-web

## Related Issues
- Issue #639: DIP violation (this issue)
- ADR-005: Hexagonal Architecture
