# Module Auth Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add login endpoint that authenticates via Nexon API key + userIgn, resolves user's characters, and issues JWT — using module-auth with Kafka-based async character fetch via external-api.

**Architecture:** module-auth orchestrates via CompletableFuture chaining (no `.join()`): fingerprint generation → Redis cache check → Kafka request → CF wait (30s timeout) → session create → JWT issue. external-api handles Nexon HTTP call on virtual thread, writes JSONL.gz chunk for Synchronizer, publishes response asynchronously. Async model: IO-bound (HTTP, DB, file, Kafka) → virtual threads; CPU-bound (HMAC, JWT signing) → coroutines; sequential ordering → CF `thenCompose`/`thenApply`.

**Tech Stack:** Kotlin, Spring Boot, Spring Kafka, Spring Data Redis, JJWT 0.12.6, Jackson, PostgreSQL (JDBC), Virtual Threads (IO), Kotlin Coroutines (CPU)

---

## File Structure

### New files — module-common (shared utility)
```
module-common/src/main/kotlin/maple/expectation/common/util/
└── FingerprintUtil.kt                       — Pure HMAC-SHA256 (no Spring)
```

### New files — module-core (shared event DTOs)
```
module-core/src/main/kotlin/maple/expectation/core/auth/event/
├── CharacterFetchRequest.kt                 — Kafka request DTO
└── CharacterFetchResponse.kt                — Kafka response DTO
```

### New files — module-auth
```
module-auth/
├── build.gradle
└── src/main/kotlin/maple/auth/
    ├── fingerprint/
    │   └── FingerprintService.kt            — Thin Spring wrapper around FingerprintUtil
    ├── jwt/
    │   └── JwtGeneratorService.kt           — JWT token generation
    ├── login/
    │   ├── LoginService.kt                  — Core orchestration
    │   └── LoginResult.kt                   — Result DTO
    ├── session/
    │   └── SessionCacheService.kt           — Redis session cache
    └── kafka/
        ├── AuthEventPublisher.kt            — Kafka producer
        ├── AuthResponseConsumer.kt          — Kafka consumer
        └── PendingLoginRegistry.kt          — CF correlation registry
```

### New files — rest-controller
```
module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/
    └── AuthController.kt                    — POST /api/v6/auth/login
```

### New files — external-api
```
module-external-api/src/main/kotlin/maple/externalapi/auth/
    └── AuthCharacterFetchConsumer.kt        — Kafka consumer (uses existing NexonAuthClient)
```

### Modified files
```
module-common/build.gradle                   — (no change needed, no new deps)
module-infra/.../security/FingerprintGenerator.kt — delegate to FingerprintUtil
module-rest-controller/build.gradle           — add module-auth dependency
module-rest-controller/.../RestControllerApplication.kt — expand component scan
module-rest-controller/src/main/resources/application.yml — auth topics
module-rest-controller/src/main/resources/application-local.yml — auth topics
module-external-api/src/main/resources/application.yml — auth topics
module-external-api/src/main/resources/application-local.yml — auth topics
settings.gradle                               — add module-auth
```

---

## Task 1: Extract FingerprintUtil to module-common

**Files:**
- Create: `module-common/src/main/kotlin/maple/expectation/common/util/FingerprintUtil.kt`
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/security/FingerprintGenerator.kt`

- [ ] **Step 1: Create FingerprintUtil**

Pure HMAC-SHA256 utility. No Spring, no LogicExecutor — just crypto.

```kotlin
package maple.expectation.common.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FingerprintUtil {
    private const val HMAC_ALGORITHM = "HmacSHA256"

    fun generate(apiKey: String, serverSecret: String): String {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(serverSecret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        val hash = mac.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun verify(apiKey: String, fingerprint: String, serverSecret: String): Boolean {
        val computed = generate(apiKey, serverSecret)
        return MessageDigest.isEqual(
            computed.toByteArray(StandardCharsets.UTF_8),
            fingerprint.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
```

- [ ] **Step 2: Refactor FingerprintGenerator to delegate**

Replace the HMAC logic in `FingerprintGenerator` with delegation to `FingerprintUtil`:

```kotlin
// In FingerprintGenerator.kt, replace generate() and verify() bodies:
fun generate(apiKey: String?): String {
    validateApiKey(apiKey)
    val key = requireNotNull(apiKey) { "apiKey must not be null after validation" }
    val context = TaskContext.of("Fingerprint", "ComputeHmac", "***")
    return executor.execute({ FingerprintUtil.generate(key, String(serverSecretBytes, StandardCharsets.UTF_8)) }, context)
}

fun verify(apiKey: String?, fingerprint: String?): Boolean {
    if (apiKey == null || fingerprint == null) return false
    val computed = generate(apiKey)
    MessageDigest.isEqual(
        computed.toByteArray(StandardCharsets.UTF_8),
        fingerprint.toByteArray(StandardCharsets.UTF_8),
    )
}
```

Note: store `serverSecret` as String field alongside `serverSecretBytes` for FingerprintUtil access. Or pass `String(serverSecretBytes, StandardCharsets.UTF_8)` — but this allocates per call. Prefer storing the original String.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-common:compileKotlin :module-infra:compileKotlin --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-common/ module-infra/
git commit -m "refactor: extract FingerprintUtil to module-common"
```

---

## Task 2: Create module-auth skeleton

**Files:**
- Create: `module-auth/build.gradle`
- Modify: `settings.gradle`

- [ ] **Step 1: Create build.gradle**

```groovy
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(':module-core'))
    implementation(project(':module-common'))
    implementation('org.springframework.boot:spring-boot-starter-data-redis')
    implementation('org.springframework.kafka:spring-kafka')
    implementation('com.fasterxml.jackson.module:jackson-module-kotlin')
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
}

bootJar { enabled = false }
jar { enabled = true }
```

- [ ] **Step 2: Add to settings.gradle**

Add `include 'module-auth'` to `settings.gradle` after the existing module includes.

- [ ] **Step 3: Create package directories**

```bash
mkdir -p module-auth/src/main/kotlin/maple/auth/{fingerprint,jwt,login,session,kafka}
mkdir -p module-auth/src/main/resources
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :module-auth:compileKotlin --continue`
Expected: BUILD SUCCESSFUL (no source files yet, but skeleton compiles)

- [ ] **Step 5: Commit**

```bash
git add module-auth/ settings.gradle
git commit -m "feat(auth): add module-auth skeleton"
```

---

## Task 3: Define Kafka event DTOs (in module-core)

**Files:**
- Create: `module-core/src/main/kotlin/maple/expectation/core/auth/event/CharacterFetchRequest.kt`
- Create: `module-core/src/main/kotlin/maple/expectation/core/auth/event/CharacterFetchResponse.kt`

- [ ] **Step 1: Create directory**

```bash
mkdir -p module-core/src/main/kotlin/maple/expectation/core/auth/event
```

- [ ] **Step 2: Create CharacterFetchRequest**

```kotlin
package maple.expectation.core.auth.event

import java.time.Instant
import java.util.UUID

data class CharacterFetchRequest(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String = "AUTH_CHARACTER_FETCH_REQUESTED",
    val fingerprint: String,
    val userIgn: String,
    val apiKey: String,
    val requestedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = fingerprint
}
```

- [ ] **Step 3: Create CharacterFetchResponse**

```kotlin
package maple.expectation.core.auth.event

import java.time.Instant

data class CharacterFetchResponse(
    val eventId: String,
    val eventType: String = "AUTH_CHARACTER_FETCH_COMPLETED",
    val fingerprint: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val characterOcidMap: Map<String, String> = emptyMap(),
    val failedIgn: List<String> = emptyList(),
    val completedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = fingerprint
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :module-core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-core/
git commit -m "feat(auth): add Kafka event DTOs with userIgn and error fields"
```

---

## Task 4: Implement FingerprintService (thin wrapper)

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/fingerprint/FingerprintService.kt`

- [ ] **Step 1: Create FingerprintService**

```kotlin
package maple.auth.fingerprint

import maple.expectation.common.util.FingerprintUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class FingerprintService(
    @Value("\${auth.fingerprint.secret}") private val serverSecret: String,
) {
    fun generate(apiKey: String): String = FingerprintUtil.generate(apiKey, serverSecret)

    fun verify(apiKey: String, fingerprint: String): Boolean =
        FingerprintUtil.verify(apiKey, fingerprint, serverSecret)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add FingerprintService wrapping FingerprintUtil"
```

---

## Task 5: Implement PendingLoginRegistry

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/kafka/PendingLoginRegistry.kt`

- [ ] **Step 1: Create PendingLoginRegistry**

```kotlin
package maple.auth.kafka

import maple.expectation.core.auth.event.CharacterFetchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class PendingLoginRegistry {
    private val pending = ConcurrentHashMap<String, CompletableFuture<CharacterFetchResponse>>()

    fun register(fingerprint: String): CompletableFuture<CharacterFetchResponse> {
        val future = CompletableFuture<CharacterFetchResponse>()
        pending[fingerprint] = future
        future.orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { _, _ -> pending.remove(fingerprint) }
        log.debug("[PendingLogin] registered: fingerprint={}, pendingCount={}", fingerprint, pending.size)
        return future
    }

    fun complete(response: CharacterFetchResponse) {
        val future = pending.remove(response.fingerprint)
        if (future != null) {
            future.complete(response)
            log.debug("[PendingLogin] completed: fingerprint={}", response.fingerprint)
        } else {
            log.warn("[PendingLogin] no pending request for fingerprint={}", response.fingerprint)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PendingLoginRegistry::class.java)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add PendingLoginRegistry for CF correlation"
```

---

## Task 6: Implement SessionCacheService

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/session/SessionCacheService.kt`

- [ ] **Step 1: Create SessionCacheService**

```kotlin
package maple.auth.session

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.domain.auth.Session
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class SessionCacheService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.session.ttl-seconds:3600}") private val ttlSeconds: Long,
) {
    fun findByFingerprint(fingerprint: String): Session? {
        val key = "session:fp:$fingerprint"
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return runCatching { objectMapper.readValue(json, Session::class.java) }
            .getOrElse {
                log.warn("[SessionCache] deserialization failed for key={}", key)
                null
            }
    }

    fun save(session: Session) {
        val key = "session:fp:${session.fingerprint}"
        val json = objectMapper.writeValueAsString(session)
        redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds))
        log.debug("[SessionCache] saved: fingerprint={}, ttl={}s", session.fingerprint, ttlSeconds)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SessionCacheService::class.java)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add SessionCacheService with Redis"
```

---

## Task 7: Implement JwtGeneratorService

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/jwt/JwtGeneratorService.kt`

- [ ] **Step 1: Create JwtGeneratorService**

```kotlin
package maple.auth.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import maple.expectation.core.auth.JwtPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtGeneratorService(
    @Value("\${auth.jwt.secret}") private val secret: String,
    @Value("\${auth.jwt.expiration:3600}") private val expirationSeconds: Long,
) {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val CLAIM_FINGERPRINT = "fgp"
        private const val CLAIM_ROLE = "role"
        private const val CLAIM_USER_IGN = "userIgn"
    }

    fun generateToken(
        sessionId: String,
        fingerprint: String,
        role: String,
        userIgn: String,
    ): String {
        val now = Instant.now()
        val payload = JwtPayload(
            sessionId = sessionId,
            fingerprint = fingerprint,
            role = role,
            userIgn = userIgn,
            issuedAt = now,
            expiration = now.plusSeconds(expirationSeconds),
        )
        return Jwts.builder()
            .subject(payload.sessionId)
            .claim(CLAIM_FINGERPRINT, payload.fingerprint)
            .claim(CLAIM_ROLE, payload.role)
            .claim(CLAIM_USER_IGN, payload.userIgn)
            .issuedAt(Date.from(payload.issuedAt))
            .expiration(Date.from(payload.expiration))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add JwtGeneratorService"
```

---

## Task 8: Implement AuthEventPublisher and AuthResponseConsumer

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/kafka/AuthEventPublisher.kt`
- Create: `module-auth/src/main/kotlin/maple/auth/kafka/AuthResponseConsumer.kt`

- [ ] **Step 1: Create AuthEventPublisher**

```kotlin
package maple.auth.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class AuthEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-request-topic}") private val requestTopic: String,
) {
    fun publishCharacterFetchRequest(request: CharacterFetchRequest) {
        val json = objectMapper.writeValueAsString(request)
        kafkaTemplate.send(requestTopic, request.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthEvent] failed to publish request: fingerprint={}", request.fingerprint, ex)
            } else {
                log.debug("[AuthEvent] published request: fingerprint={}, userIgn={}", request.fingerprint, request.userIgn)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthEventPublisher::class.java)
    }
}
```

- [ ] **Step 2: Create AuthResponseConsumer**

```kotlin
package maple.auth.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchResponse
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class AuthResponseConsumer(
    private val objectMapper: ObjectMapper,
    private val pendingLoginRegistry: PendingLoginRegistry,
) {
    @KafkaListener(
        topics = ["\${auth.kafka.character-fetch-response-topic}"],
        groupId = "\${auth.kafka.response-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(KafkaHeaders.RECEIVED_KEY) messageKey: String?,
    ) {
        val response = objectMapper.readValue(message, CharacterFetchResponse::class.java)
        log.debug("[AuthResponse] received: fingerprint={}, success={}, characters={}",
            response.fingerprint, response.success, response.characterOcidMap.size)
        pendingLoginRegistry.complete(response)
        acknowledgment.acknowledge()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthResponseConsumer::class.java)
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add Kafka publisher and consumer"
```

---

## Task 9: Implement LoginService (core orchestration)

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/login/LoginResult.kt`
- Create: `module-auth/src/main/kotlin/maple/auth/login/LoginService.kt`

- [ ] **Step 1: Create LoginResult**

```kotlin
package maple.auth.login

data class LoginResult(
    val token: String,
    val sessionId: String,
    val fingerprint: String,
    val userIgn: String,
    val characterCount: Int,
    val cached: Boolean,
)
```

- [ ] **Step 2: Create LoginService**

```kotlin
package maple.auth.login

import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.auth.fingerprint.FingerprintService
import maple.auth.jwt.JwtGeneratorService
import maple.auth.kafka.AuthEventPublisher
import maple.auth.kafka.PendingLoginRegistry
import maple.auth.session.SessionCacheService
import maple.expectation.core.domain.auth.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LoginRejectedException(val statusCode: Int, message: String) : RuntimeException(message)

@Service
class LoginService(
    private val fingerprintService: FingerprintService,
    private val sessionCacheService: SessionCacheService,
    private val authEventPublisher: AuthEventPublisher,
    private val pendingLoginRegistry: PendingLoginRegistry,
    private val jwtGeneratorService: JwtGeneratorService,
) {
    fun login(apiKey: String, userIgn: String): CompletableFuture<LoginResult> {
        val fingerprint = fingerprintService.generate(apiKey)

        // 1. Check Redis cache (IO-bound, but fast — inline is fine)
        val cached = sessionCacheService.findByFingerprint(fingerprint)
        if (cached != null) {
            val token = jwtGeneratorService.generateToken(cached.sessionId, cached.fingerprint, cached.role, cached.userIgn)
            log.info("[Login] cache hit: fingerprint={}", fingerprint)
            return CompletableFuture.completedFuture(
                LoginResult(token, cached.sessionId, fingerprint, cached.userIgn, cached.myOcids.size, cached = true)
            )
        }

        // 2. Publish Kafka request + register pending CF (fire request, then wait)
        val request = CharacterFetchRequest(fingerprint = fingerprint, userIgn = userIgn, apiKey = apiKey)
        authEventPublisher.publishCharacterFetchRequest(request)

        // 3. Chain: wait for Kafka response → validate → create session → generate JWT
        return pendingLoginRegistry.register(fingerprint)
            .thenApply { response ->
                // Validate response
                if (!response.success) {
                    log.warn("[Login] rejected: fingerprint={}, error={}", fingerprint, response.errorMessage)
                    throw LoginRejectedException(401, response.errorMessage ?: "Authentication failed")
                }
                if (userIgn !in response.characterOcidMap) {
                    log.warn("[Login] userIgn={} not found in Nexon character list", userIgn)
                    throw LoginRejectedException(401, "Character '$userIgn' not found in account")
                }
                response
            }
            .thenApply { response ->
                val sessionId = UUID.randomUUID().toString()
                val myOcids = response.characterOcidMap.values.toSet()

                val session = Session.create(
                    sessionId = sessionId,
                    fingerprint = fingerprint,
                    userIgn = userIgn,
                    accountId = fingerprint,
                    apiKey = apiKey,
                    myOcids = myOcids,
                    role = Session.ROLE_USER,
                )
                sessionCacheService.save(session)

                val token = jwtGeneratorService.generateToken(sessionId, fingerprint, Session.ROLE_USER, userIgn)
                log.info("[Login] success: fingerprint={}, userIgn={}, characters={}", fingerprint, userIgn, myOcids.size)
                LoginResult(token, sessionId, fingerprint, userIgn, myOcids.size, cached = false)
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoginService::class.java)
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add LoginService orchestration with userIgn validation"
```

---

## Task 10: Update rest-controller — AuthController + dependency

**Files:**
- Modify: `module-rest-controller/build.gradle`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/RestControllerApplication.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/AuthController.kt`

- [ ] **Step 1: Add module-auth dependency to build.gradle**

Add `implementation(project(':module-auth'))` to the dependencies block in `module-rest-controller/build.gradle`.

- [ ] **Step 2: Expand component scan in RestControllerApplication**

Add `@ComponentScan(basePackages = ["maple.restcontroller", "maple.auth"])` to the `@SpringBootApplication` annotation:

```kotlin
@SpringBootApplication
@ComponentScan(basePackages = ["maple.restcontroller", "maple.auth"])
class RestControllerApplication
```

- [ ] **Step 3: Create AuthController**

```kotlin
package maple.restcontroller.controller.v6

import maple.auth.login.LoginRejectedException
import maple.auth.login.LoginService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/v6/auth")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class AuthController(
    private val loginService: LoginService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): CompletableFuture<ResponseEntity<Any>> =
        loginService.login(request.apiKey, request.userIgn)
            .thenApply { result ->
                ResponseEntity.ok(
                    LoginResponse(
                        token = result.token,
                        sessionId = result.sessionId,
                        fingerprint = result.fingerprint,
                        userIgn = result.userIgn,
                        characterCount = result.characterCount,
                        cached = result.cached,
                    )
                )
            }
            .exceptionally { ex ->
                val cause = ex.cause ?: ex
                when (cause) {
                    is LoginRejectedException -> ResponseEntity
                        .status(cause.statusCode)
                        .body(mapOf("error" to (cause.message ?: "Authentication failed"), "status" to cause.statusCode))
                    else -> ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(mapOf("error" to "Internal error", "status" to 500))
                }
            }
}

data class LoginRequest(val apiKey: String, val userIgn: String)

data class LoginResponse(
    val token: String,
    val sessionId: String,
    val fingerprint: String,
    val userIgn: String,
    val characterCount: Int,
    val cached: Boolean,
)
```

- [ ] **Step 4: Compile all**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/
git commit -m "feat(rest-controller): add login endpoint with module-auth dependency"
```

---

## Task 11: Update external-api — AuthCharacterFetchConsumer

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt`

This consumer reuses the existing `NexonAuthClient` from module-infra (external-api already depends on module-infra). Nexon HTTP call runs on virtual thread. JSONL.gz file write runs on virtual thread. Kafka publishes are async (CF). Character map extraction is CPU-bound (runs inline, fast).

- [ ] **Step 1: Check existing NexonAuthClient and chunk-ready publisher**

Before writing, explore these existing files to understand the exact APIs:
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/NexonAuthClient.kt` — `getCharacterList(apiKey)` returns `Optional<CharacterListResponse>`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/dto/v2/CharacterListResponse.kt` — DTO structure
- Existing chunk-ready event publisher in external-api — find the class that publishes to the Synchronizer topic

- [ ] **Step 2: Create AuthCharacterFetchConsumer**

```kotlin
package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.infrastructure.external.NexonAuthClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

@Component
class AuthCharacterFetchConsumer(
    private val nexonAuthClient: NexonAuthClient,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-response-topic}") private val responseTopic: String,
) {
    private val vtExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @KafkaListener(
        topics = ["\${auth.kafka.character-fetch-request-topic}"],
        groupId = "\${auth.kafka.request-consumer-group-id}",
    )
    fun consume(
        message: String,
        acknowledgment: Acknowledgment,
        @Header(KafkaHeaders.RECEIVED_KEY) messageKey: String?,
    ) {
        val request = objectMapper.readValue(message, CharacterFetchRequest::class.java)
        log.info("[AuthFetch] processing: fingerprint={}, userIgn={}", request.fingerprint, request.userIgn)

        // Nexon HTTP call on virtual thread → extract character map → write JSONL.gz → publish response
        vtExecutor.submit {
            runCatching {
                val characterListOpt = nexonAuthClient.getCharacterList(request.apiKey)

                if (characterListOpt.isEmpty) {
                    publishError(request, "Invalid API key or Nexon API error (OPENAPI00004)")
                    return@submit
                }

                val characterList = characterListOpt.get()

                // CPU-bound: extract character_name -> ocid map
                val characterOcidMap = mutableMapOf<String, String>()
                for (account in characterList.accountList ?: emptyList()) {
                    for (char in account.characterList ?: emptyList()) {
                        if (!char.ocid.isNullOrBlank() && !char.characterName.isNullOrBlank()) {
                            characterOcidMap[char.characterName] = char.ocid
                        }
                    }
                }

                // IO-bound (file): write JSONL.gz chunk for Synchronizer
                // Use existing chunk format with runId="auth-{fingerprint}", endpoint="auth-character"
                // Find existing chunk-ready publisher in external-api and reuse it
                // TODO: implement JSONL.gz write + chunk-ready publish following existing pattern

                publishSuccess(request, characterOcidMap)
                log.info("[AuthFetch] completed: fingerprint={}, resolved={}", request.fingerprint, characterOcidMap.size)
            }.onFailure { ex ->
                log.error("[AuthFetch] failed: fingerprint={}", request.fingerprint, ex)
                publishError(request, "Internal error: ${ex.message}")
            }
        }

        acknowledgment.acknowledge()
    }

    private fun publishSuccess(request: CharacterFetchRequest, characterOcidMap: Map<String, String>) {
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            fingerprint = request.fingerprint,
            success = true,
            characterOcidMap = characterOcidMap,
        )
        publishResponse(response)
    }

    private fun publishError(request: CharacterFetchRequest, errorMessage: String) {
        log.warn("[AuthFetch] error: fingerprint={}, error={}", request.fingerprint, errorMessage)
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            fingerprint = request.fingerprint,
            success = false,
            errorMessage = errorMessage,
        )
        publishResponse(response)
    }

    private fun publishResponse(response: CharacterFetchResponse) {
        val json = objectMapper.writeValueAsString(response)
        kafkaTemplate.send(responseTopic, response.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthFetch] failed to publish response: fingerprint={}", response.fingerprint, ex)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthCharacterFetchConsumer::class.java)
    }
}
```

Note: The `CharacterListResponse` field names (`accountList`, `characterList`, `ocid`, `characterName`) must be verified against the actual DTO. Adjust field access accordingly.

The JSONL.gz + chunk-ready publishing for Synchronizer follows the existing external-api pattern. The implementer should:
1. Find the existing chunk-ready event publisher in external-api
2. Write character data as JSONL.gz in the same format as existing chunks
3. Publish chunk-ready event with `runId = "auth-{fingerprint}"`, `endpoint = "auth-character"`
4. Synchronizer consumes and upserts `game_character` with fingerprint (existing pipeline, no code change needed in Synchronizer)

- [ ] **Step 3: Compile all**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/
git commit -m "feat(external-api): add auth character fetch consumer using NexonAuthClient"
```

---

## Task 12: YAML configuration

**Files:**
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application-local.yml`
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-external-api/src/main/resources/application-local.yml`
- Create: `module-auth/src/main/resources/application.yml` (default config)

- [ ] **Step 1: Add auth config to rest-controller application.yml**

Add at root level (merge into existing structure, don't duplicate root keys):

```yaml
auth:
  kafka:
    character-fetch-request-topic: auth-character-fetch-request
    character-fetch-response-topic: auth-character-fetch-response
    response-consumer-group-id: module-auth-response-consumer
  jwt:
    secret: ${JWT_SECRET}
    expiration: 3600
  fingerprint:
    secret: ${AUTH_FINGERPRINT_SECRET:default-dev-secret-change-me}
  session:
    ttl-seconds: 3600
```

- [ ] **Step 2: Add auth config to rest-controller application-local.yml**

Add same auth block (values resolved from .env).

- [ ] **Step 3: Add auth Kafka topics to external-api application.yml**

```yaml
auth:
  kafka:
    character-fetch-request-topic: auth-character-fetch-request
    character-fetch-response-topic: auth-character-fetch-response
    request-consumer-group-id: module-external-api-auth-consumer
```

- [ ] **Step 4: Add auth config to external-api application-local.yml**

Same auth block.

- [ ] **Step 5: Create module-auth default application.yml**

```yaml
auth:
  jwt:
    secret: ${JWT_SECRET}
    expiration: ${AUTH_JWT_EXPIRATION:3600}
  fingerprint:
    secret: ${AUTH_FINGERPRINT_SECRET:default-dev-secret-change-me}
  session:
    ttl-seconds: ${AUTH_SESSION_TTL:3600}
  kafka:
    character-fetch-request-topic: ${AUTH_KAFKA_REQUEST_TOPIC:auth-character-fetch-request}
    character-fetch-response-topic: ${AUTH_KAFKA_RESPONSE_TOPIC:auth-character-fetch-response}
    response-consumer-group-id: ${AUTH_KAFKA_RESPONSE_GROUP:module-auth-response-consumer}
```

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add module-auth/src/main/resources/ module-rest-controller/src/main/resources/ module-external-api/src/main/resources/
git commit -m "feat: add YAML configuration for auth login flow"
```

---

## Task 13: Tests

**Files:**
- Create: `module-auth/src/test/kotlin/maple/auth/login/LoginServiceTest.kt`
- Create: `module-auth/src/test/kotlin/maple/auth/fingerprint/FingerprintServiceTest.kt`
- Create: `module-auth/src/test/kotlin/maple/auth/kafka/PendingLoginRegistryTest.kt`

- [ ] **Step 1: Create FingerprintServiceTest**

```kotlin
package maple.auth.fingerprint

import maple.expectation.common.util.FingerprintUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FingerprintServiceTest {
    private val service = FingerprintService("test-secret-key-for-unit-testing-32ch")

    @Test
    fun `same API key produces same fingerprint`() {
        assertThat(service.generate("key-1")).isEqualTo(service.generate("key-1"))
    }

    @Test
    fun `different API keys produce different fingerprints`() {
        assertThat(service.generate("key-1")).isNotEqualTo(service.generate("key-2"))
    }

    @Test
    fun `verify returns true for matching key`() {
        val fp = service.generate("key-1")
        assertThat(service.verify("key-1", fp)).isTrue()
    }

    @Test
    fun `verify returns false for wrong key`() {
        val fp = service.generate("key-1")
        assertThat(service.verify("key-2", fp)).isFalse()
    }

    @Test
    fun `matches FingerprintUtil output`() {
        val secret = "test-secret-key-for-unit-testing-32ch"
        val apiKey = "key-1"
        assertThat(service.generate(apiKey)).isEqualTo(FingerprintUtil.generate(apiKey, secret))
    }
}
```

- [ ] **Step 2: Create PendingLoginRegistryTest**

```kotlin
package maple.auth.kafka

import maple.expectation.core.auth.event.CharacterFetchResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class PendingLoginRegistryTest {
    private val registry = PendingLoginRegistry()

    @Test
    fun `complete resolves the future`() {
        val future = registry.register("fp-1")
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            success = true,
            characterOcidMap = mapOf("Char1" to "ocid-1"),
        )
        registry.complete(response)
        assertThat(future.get(1, TimeUnit.SECONDS)).isEqualTo(response)
    }

    @Test
    fun `unregistered complete does not throw`() {
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "unknown-fp",
            success = true,
        )
        registry.complete(response)
    }
}
```

- [ ] **Step 3: Create LoginServiceTest**

```kotlin
package maple.auth.login

import maple.expectation.core.auth.event.CharacterFetchRequest
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.auth.fingerprint.FingerprintService
import maple.auth.jwt.JwtGeneratorService
import maple.auth.kafka.AuthEventPublisher
import maple.auth.kafka.PendingLoginRegistry
import maple.auth.session.SessionCacheService
import maple.expectation.core.domain.auth.Session
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@ExtendWith(MockitoExtension::class)
class LoginServiceTest {
    @Mock private lateinit var fingerprintService: FingerprintService
    @Mock private lateinit var sessionCacheService: SessionCacheService
    @Mock private lateinit var authEventPublisher: AuthEventPublisher
    @Mock private lateinit var pendingLoginRegistry: PendingLoginRegistry
    @Mock private lateinit var jwtGeneratorService: JwtGeneratorService

    @Captor private lateinit var requestCaptor: ArgumentCaptor<CharacterFetchRequest>

    private fun createService() = LoginService(
        fingerprintService, sessionCacheService, authEventPublisher, pendingLoginRegistry, jwtGeneratorService
    )

    @Test
    fun `returns cached session on cache hit`() {
        val service = createService()
        val session = Session.create("s-1", "fp-1", "User1", "fp-1", "key", setOf("ocid-1"), "USER")
        whenever(fingerprintService.generate("key")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(session)
        whenever(jwtGeneratorService.generateToken(any(), any(), any(), any())).thenReturn("jwt-token")

        val result = service.login("key", "User1").get()
        assertThat(result.cached).isTrue()
        assertThat(result.token).isEqualTo("jwt-token")
    }

    @Test
    fun `publishes Kafka request on cache miss`() {
        val service = createService()
        whenever(fingerprintService.generate("key")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(null)
        whenever(jwtGeneratorService.generateToken(any(), any(), any(), any())).thenReturn("jwt-token")

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            success = true,
            characterOcidMap = mapOf("User1" to "ocid-1"),
        )
        whenever(pendingLoginRegistry.register("fp-1")).thenReturn(
            CompletableFuture.completedFuture(response)
        )

        val result = service.login("key", "User1").get()
        assertThat(result.cached).isFalse()
        assertThat(result.characterCount).isEqualTo(1)
        assertThat(result.userIgn).isEqualTo("User1")
        verify(authEventPublisher).publishCharacterFetchRequest(capture(requestCaptor))
        assertThat(requestCaptor.value.userIgn).isEqualTo("User1")
        verify(sessionCacheService).save(any())
    }

    @Test
    fun `throws 401 on failed response`() {
        val service = createService()
        whenever(fingerprintService.generate("key")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(null)

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            success = false,
            errorMessage = "Invalid API key",
        )
        whenever(pendingLoginRegistry.register("fp-1")).thenReturn(
            CompletableFuture.completedFuture(response)
        )

        val future = service.login("key", "User1")
        assertThatThrownBy { future.get() }
            .isInstanceOf(ExecutionException::class.java)
            .hasCauseInstanceOf(LoginRejectedException::class.java)
    }

    @Test
    fun `throws 401 when userIgn not in character map`() {
        val service = createService()
        whenever(fingerprintService.generate("key")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(null)

        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            success = true,
            characterOcidMap = mapOf("OtherChar" to "ocid-1"),
        )
        whenever(pendingLoginRegistry.register("fp-1")).thenReturn(
            CompletableFuture.completedFuture(response)
        )

        val future = service.login("key", "User1")
        assertThatThrownBy { future.get() }
            .isInstanceOf(ExecutionException::class.java)
            .hasCauseInstanceOf(LoginRejectedException::class.java)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :module-auth:test`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add module-auth/src/test/
git commit -m "test(auth): add unit tests for auth login flow"
```

---

## Task 14: Full compilation + integration verification

**Files:** None (verification only)

- [ ] **Step 1: Full compile**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit any remaining fixes**

Fix any compilation or test failures, then commit.

---

## Modified Files Summary

| File | Change |
|------|--------|
| `module-common/.../util/FingerprintUtil.kt` | New — pure HMAC-SHA256 utility |
| `module-infra/.../security/FingerprintGenerator.kt` | Modify — delegate to FingerprintUtil |
| `settings.gradle` | Add `include 'module-auth'` |
| `module-auth/build.gradle` | New — Spring Kafka, Redis, JJWT, module-core, module-common |
| `module-core/.../core/auth/event/CharacterFetchRequest.kt` | New — Kafka request DTO (fingerprint + userIgn + apiKey) |
| `module-core/.../core/auth/event/CharacterFetchResponse.kt` | New — Kafka response DTO (success + errorMessage + characterOcidMap) |
| `module-auth/.../fingerprint/FingerprintService.kt` | New — thin Spring wrapper around FingerprintUtil |
| `module-auth/.../jwt/JwtGeneratorService.kt` | New — JWT generation |
| `module-auth/.../kafka/PendingLoginRegistry.kt` | New — CF correlation |
| `module-auth/.../kafka/AuthEventPublisher.kt` | New — Kafka producer |
| `module-auth/.../kafka/AuthResponseConsumer.kt` | New — Kafka consumer |
| `module-auth/.../login/LoginResult.kt` | New — result DTO |
| `module-auth/.../login/LoginService.kt` | New — core orchestration with userIgn + error handling |
| `module-auth/.../session/SessionCacheService.kt` | New — Redis cache |
| `module-rest-controller/build.gradle` | Add module-auth dependency |
| `module-rest-controller/.../RestControllerApplication.kt` | Expand component scan |
| `module-rest-controller/.../controller/v6/AuthController.kt` | New — POST /api/v6/auth/login (apiKey + userIgn) |
| `module-external-api/.../auth/AuthCharacterFetchConsumer.kt` | New — Kafka consumer using existing NexonAuthClient |
| `module-*/src/main/resources/application*.yml` | Auth Kafka topics, JWT, fingerprint config |
| `module-auth/src/test/...` | Unit tests |
