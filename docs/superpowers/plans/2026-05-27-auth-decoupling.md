# Auth Decoupling + External API 401 Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple auth infrastructure from JPA so lightweight modules can use JWT authentication without importing module-infra, and fix external-api Prometheus 401 bug.

**Architecture:** Extract auth port interfaces to module-core. JWT parsing and token types move to module-core. module-infra provides JPA-backed adapters. external-api excludes Spring Security auto-configuration.

**Tech Stack:** Kotlin, Spring Security, JJWT 0.12.x, Spring Data JPA

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `module-core/.../core/auth/JwtPayload.kt` | Create | JWT token payload data class (moved from module-infra, `isExpired()` dropped as dead code) |
| `module-core/.../core/auth/JwtParserPort.kt` | Create | Interface for JWT parsing (no JPA dependency) |
| `module-core/.../core/auth/JwtGeneratorPort.kt` | Create | Interface for JWT token generation |
| `module-infra/.../security/jwt/JwtPayload.kt` | Modify | Replace with typealias to module-core version |
| `module-infra/.../security/AuthenticatedUser.kt` | Delete | Duplicate of module-core version |
| `module-infra/.../security/jwt/JwtTokenProvider.kt` | Modify | Implement JwtParserPort + JwtGeneratorPort, update imports |
| `module-infra/.../security/filter/JwtAuthenticationFilter.kt` | Modify | Depend on JwtParserPort (module-core) instead of JwtTokenProvider directly |
| `module-infra/.../auth/TokenPortImpl.kt` | Modify | Update imports to module-core JwtPayload (via typealias, automatic) |
| `module-external-api/.../ExternalApiApplication.kt` | Modify | Exclude SecurityAutoConfiguration to fix 401 |

---

### Task 1: Fix external-api Prometheus 401 Bug

**Problem:** module-external-api depends on module-infra (which has `spring-boot-starter-security`), but its component scan doesn't include the security config package. Spring Security's default auto-config activates and locks down all endpoints including `/actuator/prometheus`.

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt`

- [ ] **Step 1: Exclude SecurityAutoConfiguration in ExternalApiApplication**

```kotlin
package maple.externalapi

import maple.externalapi.config.NexonHttpClientProperties
import maple.externalapi.snapshot.SnapshotChunkingProperties
import maple.externalapi.snapshot.event.SnapshotEventProperties
import maple.expectation.infrastructure.lifecycle.ManagedLifecycleCoordinator
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    scanBasePackages = ["maple.externalapi", "maple.expectation.infrastructure.executor"],
    exclude = [
        SecurityAutoConfiguration::class,
        SecurityFilterAutoConfiguration::class,
    ]
)
@Import(
    maple.expectation.infrastructure.config.ExecutorConfig::class,
    ManagedLifecycleCoordinator::class,
)
@EnableScheduling
@EnableConfigurationProperties(SnapshotChunkingProperties::class, SnapshotEventProperties::class, NexonHttpClientProperties::class)
class ExternalApiApplication
```

Note: `build.gradle` is NOT modified. Security jars remain on classpath; only auto-configuration is disabled. This avoids `NoClassDefFoundError` from indirect security type references in module-infra.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew :module-external-api:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/ExternalApiApplication.kt
git commit -m "fix(external-api): exclude SecurityAutoConfiguration to fix Prometheus 401

Security jars remain on classpath (via module-infra transitive dep).
Only auto-configuration is disabled to prevent default filter chain
locking down actuator endpoints.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Move JwtPayload to module-core

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/auth/JwtPayload.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtPayload.kt` (replace with typealias)

- [ ] **Step 1: Create JwtPayload in module-core**

Create `module-core/src/main/kotlin/maple/expectation/core/auth/JwtPayload.kt`:

```kotlin
package maple.expectation.core.auth

import java.time.Instant

data class JwtPayload(
    val sessionId: String,
    val fingerprint: String,
    val role: String,
    val userIgn: String = "",
    val issuedAt: Instant,
    val expiration: Instant,
) {
    companion object {
        fun of(
            sessionId: String,
            fingerprint: String,
            role: String,
            ttlSeconds: Long,
            userIgn: String = "",
        ): JwtPayload {
            val now = Instant.now()
            return JwtPayload(
                sessionId = sessionId,
                fingerprint = fingerprint,
                role = role,
                userIgn = userIgn,
                issuedAt = now,
                expiration = now.plusSeconds(ttlSeconds),
            )
        }
    }
}
```

Note: `isExpired()` omitted — confirmed dead code (zero callers in production code).

- [ ] **Step 2: Replace module-infra JwtPayload with typealias**

Replace content of `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtPayload.kt`:

```kotlin
package maple.expectation.infrastructure.security.jwt

typealias JwtPayload = maple.expectation.core.auth.JwtPayload
```

This preserves backward compatibility — all existing `import maple.expectation.infrastructure.security.jwt.JwtPayload` still compile.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/auth/JwtPayload.kt module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtPayload.kt
git commit -m "refactor(auth): move JwtPayload to module-core with backward-compatible typealias

isExpired() dropped (dead code — zero callers). Existing module-infra
imports resolve via typealias.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Create JwtParserPort and JwtGeneratorPort in module-core

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/auth/JwtParserPort.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/auth/JwtGeneratorPort.kt`

- [ ] **Step 1: Create JwtParserPort interface**

Create `module-core/src/main/kotlin/maple/expectation/core/auth/JwtParserPort.kt`:

```kotlin
package maple.expectation.core.auth

import java.util.Optional

interface JwtParserPort {
    fun parseToken(token: String?): Optional<JwtPayload>
    fun validateToken(token: String?): Boolean
}
```

- [ ] **Step 2: Create JwtGeneratorPort interface**

Create `module-core/src/main/kotlin/maple/expectation/core/auth/JwtGeneratorPort.kt`:

```kotlin
package maple.expectation.core.auth

interface JwtGeneratorPort {
    fun generateToken(payload: JwtPayload): String
    fun generateToken(sessionId: String, fingerprint: String, role: String, userIgn: String = ""): String
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-core/src/main/kotlin/maple/expectation/core/auth/JwtParserPort.kt module-core/src/main/kotlin/maple/expectation/core/auth/JwtGeneratorPort.kt
git commit -m "feat(auth): add JwtParserPort and JwtGeneratorPort interfaces to module-core

Ports in core.auth package for auth domain cohesion.
module-infra JwtTokenProvider will implement both.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Remove duplicate AuthenticatedUser from module-infra

**Files:**
- Delete: `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/AuthenticatedUser.kt`
- Modify: All files importing `maple.expectation.infrastructure.security.AuthenticatedUser` → change to `maple.expectation.core.domain.model.security.AuthenticatedUser`

- [ ] **Step 1: Find all imports of module-infra AuthenticatedUser**

Run: `grep -rn "import maple.expectation.infrastructure.security.AuthenticatedUser" module-infra/ module-web/ module-app/ module-external-api/`

Expected: List of files to update.

- [ ] **Step 2: Update imports in each file**

Replace all `import maple.expectation.infrastructure.security.AuthenticatedUser` with `import maple.expectation.core.domain.model.security.AuthenticatedUser`.

Files to update (from exploration):
- `module-infra/.../security/filter/JwtAuthenticationFilter.kt`
- `module-infra/.../security/config/SecurityConfig.kt`
- Any other files found in step 1

- [ ] **Step 3: Delete module-infra AuthenticatedUser**

Delete `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/AuthenticatedUser.kt`

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A module-infra/ module-web/ module-app/
git commit -m "refactor(auth): remove duplicate AuthenticatedUser from module-infra

All references redirected to module-core version.
Two identical copies existed differing only in package name.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: JwtTokenProvider implements JwtParserPort + JwtGeneratorPort

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtTokenProvider.kt`

- [ ] **Step 1: Update JwtTokenProvider to implement both ports**

Add interface implementations to the class declaration:

```kotlin
@Component
class JwtTokenProvider(
    @Value("\${auth.jwt.secret}") private val secret: String,
    @Value("\${auth.jwt.expiration}") private val expirationSeconds: Long,
    private val environment: Environment,
    private val executor: LogicExecutor,
) : JwtParserPort, JwtGeneratorPort {
```

Add imports:
```kotlin
import maple.expectation.core.auth.JwtParserPort
import maple.expectation.core.auth.JwtGeneratorPort
```

The existing method signatures already match both interfaces:
- `parseToken(token: String?): Optional<JwtPayload>` — matches `JwtParserPort.parseToken`
- `validateToken(token: String?): Boolean` — matches `JwtParserPort.validateToken`
- `generateToken(payload: JwtPayload): String` — matches `JwtGeneratorPort.generateToken`
- `generateToken(sessionId, fingerprint, role, userIgn): String` — matches `JwtGeneratorPort.generateToken` (4-param)

The existing 3-param `generateToken(sessionId, fingerprint, role)` overload stays as a convenience method (not part of the interface).

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :module-infra:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/security/jwt/JwtTokenProvider.kt
git commit -m "refactor(auth): JwtTokenProvider implements JwtParserPort and JwtGeneratorPort

Existing method signatures already match both interfaces.
3-param generateToken overload preserved as convenience method.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Refactor JwtAuthenticationFilter to depend on ports

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/JwtAuthenticationFilter.kt`

- [ ] **Step 1: Replace direct JwtTokenProvider dependency with JwtParserPort**

Change constructor injection:

```kotlin
@Component
class JwtAuthenticationFilter(
    private val jwtParserPort: JwtParserPort,
    private val characterOcidPort: CharacterOcidPort,
    private val objectMapper: ObjectMapper,
    private val executor: LogicExecutor,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
```

Update `doFilterInternal` to use `jwtParserPort.parseToken(token)` instead of `jwtTokenProvider.parseToken(token)`. Same method signature — find-replace of the variable name.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/JwtAuthenticationFilter.kt
git commit -m "refactor(auth): JwtAuthenticationFilter depends on JwtParserPort instead of JwtTokenProvider

DIP applied: filter now depends on module-core port, not module-infra concrete class.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Full compilation and test verification

- [ ] **Step 1: Full compilation check**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify module-core auth package has no Spring/JPA imports**

Run: `grep -rn "import org.springframework\|import jakarta" module-core/src/main/kotlin/maple/expectation/core/auth/`
Expected: No results

- [ ] **Step 4: Verify JwtAuthenticationFilter depends only on module-core types for auth**

Run: `grep -n "import" module-infra/src/main/kotlin/maple/expectation/infrastructure/security/filter/JwtAuthenticationFilter.kt | grep -E "jwt|auth|AuthenticatedUser"`
Expected: Only `maple.expectation.core.auth.*` imports (not `maple.expectation.infrastructure.security.jwt.*`)

---

## Dependency Graph Comparison

### Before

```
module-core (ports: CharacterOcidPort)
    ↑
module-infra (JwtTokenProvider, JwtAuthenticationFilter, CharacterOcidAdapter, SecurityConfig)
    ↑                    ↑
module-app          module-external-api (gets JPA + Security transitively → 401 bug)

module-rest-controller → module-core (no auth, no module-infra)
```

### After

```
module-core (ports: CharacterOcidPort, JwtParserPort, JwtGeneratorPort, JwtPayload)
    ↑
module-infra
  (JwtTokenProvider implements JwtParserPort, JwtGeneratorPort)
  (JwtAuthenticationFilter depends on JwtParserPort + CharacterOcidPort — both in module-core)
  (CharacterOcidAdapter — JPA-backed, optional)

module-external-api → module-infra (SecurityAutoConfiguration excluded → no 401)

module-rest-controller → module-core (no auth change, no module-infra dependency)
```

**Key improvement:** module-infra's auth types now depend on module-core interfaces, not concrete implementations. external-api no longer suffers from unintended security activation. Future lightweight modules can implement `JwtParserPort` without pulling in JPA.

---

## Plan Review Log

Revisions applied during grill-me review:

| # | Issue | Decision |
|---|-------|----------|
| 1 | JdbcOcidQueryAdapter imports LogicExecutor (module-infra) — rest-controller has no module-infra dep | Dropped from this PR (YAGNI — no current consumer). Future PR when needed. |
| 2 | JwtPayload.isExpired() missing from plan's module-core version | Dead code — omitted. Acknowledged in commit message. |
| 3 | module-external-api/build.gradle modification planned | Removed. `@SpringBootApplication(exclude)` sufficient. Security jars stay on classpath to avoid NoClassDefFoundError. |
| 4 | SQL column names in JdbcOcidQueryAdapter | Verified correct — not applicable (task removed). |
| 5 | Port package: core.auth vs core.port.out | `core.auth` chosen for domain cohesion over convention uniformity. |
| 6 | AuthConfig bean registration location | Separate `config/AuthConfig.kt` — not applicable (task removed). |
| 7 | LogicExecutor rule in code-rules.md too rigid for lightweight modules | Changed from mandatory to recommended. Modules without LogicExecutor use Spring exception propagation. |
