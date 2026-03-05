# DTO Ownership Guide (Phase 6)

## Overview

This document defines the ownership, location, and usage rules for Data Transfer Objects (DTOs) in the MapleExpectation multi-module architecture.

## Current State (Phase 6 Violation)

### Critical Architectural Violation

**module-app → module-web dependency violates DIP**

The application layer (`module-app`) has **20+ imports** from the presentation layer (`module-web`), creating a reverse dependency that violates the Dependency Inversion Principle (DIP).

```
❌ CURRENT (VIOLATION):
module-app → module-web.dto
```

### Violating Imports

| DTO Package | Used By | Count | Purpose |
|-------------|---------|-------|---------|
| `web.dto` | CubeServiceImpl, Calculator | 5+ | Cube calculation input/output |
| `web.dto.v4` | V4 services, workers | 8+ | Equipment expectation V4 |
| `web.dto` (auth) | AuthService, AuthPortAdapter | 3+ | Authentication requests/responses |
| `web.dto.dlq` | DlqAdminService | 3+ | DLQ management |
| `web.dto.page` | DlqPortAdapter | 2+ | Pagination |
| `web.dto.response` | Controllers, services | 2+ | Character responses |
| `web.dto.admin` | Admin services | 1+ | Admin operations |
| `web.dto.donation` | Donation services | 2+ | Donation requests/responses |

## Module Dependency Graph

### Ideal Architecture (Target)

```
module-web      (Presentation: Controllers, GlobalExceptionHandler)
    ↓ depends on
module-app      (Application: Services, Use Cases, DTOs)
    ↓ depends on
module-infra    (Infrastructure: Repositories, External APIs)
    ↓ depends on
module-core     (Domain: Ports, Entities)
    ↓ depends on
module-common   (Shared: Utilities, Error Handling)
```

### Current Architecture (Violation)

```
module-web      (Presentation + DTOs)
    ↑           ← Reverse dependency (VIOLATION)
module-app      (Application depends on web.dto)
    ↓
module-infra
    ↓
module-core
    ↓
module-common
```

## DTO Classification Rules

### Rule 1: Request DTOs → module-web

**Location:** `module-web/src/main/kotlin/maple/expectation/web/dto/`

**Definition:** DTOs that represent HTTP request bodies from clients.

**Examples:**
- `LoginRequest.kt`
- `RefreshRequest.kt`
- `AddAdminRequest.kt`
- `SendCoffeeRequest.kt`
- `EquipmentCalculationInput.kt`

**Ownership:** module-web owns these DTOs.

**Usage:**
- Controllers in module-web receive these DTOs
- Controllers map to domain models before passing to application layer

### Rule 2: Response DTOs → module-web

**Location:** `module-web/src/main/kotlin/maple/expectation/web/dto/`

**Definition:** DTOs that represent HTTP response bodies to clients.

**Examples:**
- `LoginResponse.kt`
- `TokenResponse.kt`
- `SendCoffeeResponse.kt`
- `CharacterResponse.kt`
- `EquipmentExpectationResponseV4.kt`

**Ownership:** module-web owns these DTOs.

**Usage:**
- Application layer returns domain models
- Controllers in module-web map domain models to response DTOs

### Rule 3: Domain DTOs → module-app

**Location:** `module-app/src/main/java/maple/expectation/application/dto/` (NEW)

**Definition:** DTOs used internally by application services, not exposed to HTTP layer.

**Examples:**
- `CalculationResult.java`
- `AuthenticationResult.java`
- `DonationResult.java`

**Ownership:** module-app owns these DTOs.

**Usage:**
- Application services use these DTOs internally
- Not exposed to HTTP layer directly

### Rule 4: Shared DTOs → module-dto (FUTURE)

**Location:** `module-dto/src/main/kotlin/maple/expectation/dto/` (FUTURE MODULE)

**Definition:** DTOs shared across multiple modules (web, app, infra).

**Examples:**
- `PageRequest.kt`
- `PageResponse.kt`
- `ErrorDto.kt`

**Ownership:** module-dto (new module to be created).

**Usage:**
- All modules depend on module-dto
- Eliminates circular dependencies

## Migration Plan

### Phase 6: Current State (Document Violation)

- [x] Document all violating imports (this document)
- [x] Add ArchUnit rule to detect app→web dependency
- [ ] Create migration ADR

### Phase 7: Option A - Move DTOs to module-app

1. Move all DTOs from `module-web/dto` to `module-app/dto`
2. Update all imports in module-app
3. Update all imports in module-web
4. Remove module-web → module-app dependency for DTOs

**Pros:**
- Aligns with domain-driven design (application owns business DTOs)
- Clear ownership

**Cons:**
- Large migration effort (20+ files)
- May require module-web to depend on module-app

### Phase 7: Option B - Create module-dto

1. Create new module `module-dto`
2. Move shared DTOs to `module-dto`
3. Update all modules to depend on `module-dto`
4. Remove app→web dependency

**Pros:**
- Clean separation of concerns
- Eliminates circular dependencies
- Reusable DTOs across modules

**Cons:**
- New module creation overhead
- Additional build complexity

### Phase 7: Option C - Interface Segregation

1. Keep DTOs in module-web
2. Create interfaces in module-core for DTO contracts
3. Application layer depends on interfaces, not concrete DTOs
4. Controllers map between interfaces and DTOs

**Pros:**
- Minimal refactoring
- Maintains current structure

**Cons:**
- Additional mapper layer
- More complex architecture

## Best Practices

### DO

✅ Place Request/Response DTOs in module-web
✅ Use Kotlin data classes for DTOs in module-web
✅ Provide Builder pattern for Java interoperability
✅ Add validation logic in DTOs (`validateForDpMode()`, `isReady()`)
✅ Document DTO fields with KDoc comments
✅ Use Jackson annotations for JSON serialization
✅ Keep DTOs immutable (use `val` in Kotlin)

### DON'T

❌ Don't let module-app import from module.dto
❌ Don't use domain entities as DTOs
❌ Don't put business logic in DTOs (except validation)
❌ Don't create circular dependencies between modules
❌ Don't use mutable state in DTOs (use `val`, not `var`)
❌ Don't expose internal domain models in API responses

## Examples

### Correct: Request DTO in module-web

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/dto/LoginRequest.kt
package maple.expectation.web.dto

data class LoginRequest(
    val ign: String,
    val worldName: String
) {
    fun validate() {
        require(ign.isNotBlank()) { "IGN must not be blank" }
        require(worldName.isNotBlank()) { "World name must not be blank" }
    }
}
```

### Correct: Response DTO in module-web

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/dto/LoginResponse.kt
package maple.expectation.web.dto

data class LoginResponse(
    val token: String,
    val refreshToken: String,
    val expiresIn: Long
)
```

### Correct: Controller maps to/from DTOs

```kotlin
// module-web/src/main/kotlin/maple/expectation/web/controller/AuthController.kt
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authPort: AuthPort // Interface from module-core
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): LoginResponse {
        // Map DTO to domain model
        val command = LoginCommand(
            ign = request.ign,
            worldName = request.worldName
        )

        // Call application layer
        val result = authPort.login(command)

        // Map domain result to response DTO
        return LoginResponse(
            token = result.token,
            refreshToken = result.refreshToken,
            expiresIn = result.expiresIn
        )
    }
}
```

## References

- [ADR-005: Hexagonal Architecture](../adr/ADR-005-hexagonal-architecture.md)
- [ADR-014: Multi-Module Cross-Cutting Concerns](../adr/ADR-014-multi-module-cross-cutting-concerns.md)
- [ADR-037: Web Layer Separation](../adr/ADR-037-web-layer-separation.md)
- [ADR-039: Current Architecture Assessment](../adr/ADR-039-current-architecture-assessment.md)
- [Module Dependency Test](../../module-web/src/test/java/maple/expectation/arch/ModuleDependencyTest.java)

## Changelog

- **2026-03-05**: Initial documentation (Phase 6)
  - Documented current violation (module-app → module-web)
  - Cataloged 20+ violating imports
  - Proposed 3 migration options (Phase 7)
