# Module Auth Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add login endpoint that authenticates via Nexon API key, resolves user's characters, and issues JWT — using a new module-auth with Kafka-based async character fetch via external-api.

**Architecture:** Single Kafka round-trip (Approach A). module-auth orchestrates: fingerprint generation → Redis cache check → Kafka request → CompletableFuture wait (30s timeout) → session create → JWT issue. external-api handles Nexon character list API call and DB writes.

**Tech Stack:** Kotlin, Spring Boot, Spring Kafka, Spring Data Redis, JJWT 0.12.6, Jackson, PostgreSQL (JDBC)

---

## File Structure

### New files — module-core (shared event DTOs)
```
module-core/src/main/kotlin/maple/expectation/core/auth/event/
├── CharacterFetchRequest.kt          — Kafka request DTO
└── CharacterFetchResponse.kt         — Kafka response DTO
```

### New files — module-auth
```
module-auth/
├── build.gradle
└── src/main/kotlin/maple/auth/
    ├── fingerprint/
    │   └── FingerprintService.kt             — HMAC-SHA256 from API key
    ├── jwt/
    │   └── JwtGeneratorService.kt            — JWT token generation
    ├── login/
    │   ├── LoginService.kt                   — Core orchestration
    │   └── LoginResult.kt                    — Result DTO
    ├── session/
    │   └── SessionCacheService.kt            — Redis session cache
    └── kafka/
        ├── AuthEventPublisher.kt             — Kafka producer
        ├── AuthResponseConsumer.kt           — Kafka consumer
        └── PendingLoginRegistry.kt           — CF correlation registry
```

### New files — rest-controller
```
module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/
    └── AuthController.kt                     — POST /api/v6/auth/login
```

### New files — external-api
```
module-external-api/src/main/kotlin/maple/externalapi/auth/
    ├── AuthCharacterFetchConsumer.kt         — Kafka consumer for auth requests
    └── NexonCharacterListAdapter.kt          — Nexon /character/list API call
```

### Modified files
```
module-rest-controller/build.gradle           — add module-auth dependency
module-rest-controller/.../RestControllerApplication.kt — expand component scan
module-rest-controller/src/main/resources/application.yml — auth topics
module-rest-controller/src/main/resources/application-local.yml — auth topics
module-external-api/src/main/resources/application.yml — auth topics
module-external-api/src/main/resources/application-local.yml — auth topics
settings.gradle                               — add module-auth
```

---

## Task 1: Create module-auth skeleton

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

## Task 2: Define Kafka event DTOs (in module-core)

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
    val apiKey: String,
    val requestedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = fingerprint
}
```

- [ ] **Step 2: Create CharacterFetchResponse**

```kotlin
package maple.expectation.core.auth.event

import java.time.Instant

data class CharacterFetchResponse(
    val eventId: String,
    val eventType: String = "AUTH_CHARACTER_FETCH_COMPLETED",
    val fingerprint: String,
    val characterOcidMap: Map<String, String>,  // userIgn -> ocid
    val failedIgn: List<String> = emptyList(),
    val completedAt: Instant = Instant.now(),
) {
    fun kafkaKey(): String = fingerprint
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add Kafka event DTOs"
```

---

## Task 3: Implement FingerprintService

**Files:**
- Create: `module-auth/src/main/kotlin/maple/auth/fingerprint/FingerprintService.kt`

- [ ] **Step 1: Create FingerprintService**

```kotlin
package maple.auth.fingerprint

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class FingerprintService(
    @Value("\${auth.fingerprint.secret}") private val serverSecret: String,
) {
    private val secretBytes = serverSecret.toByteArray(StandardCharsets.UTF_8)

    fun generate(apiKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val hash = mac.doFinal(apiKey.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    fun verify(apiKey: String, fingerprint: String): Boolean {
        val computed = generate(apiKey)
        return java.security.MessageDigest.isEqual(
            computed.toByteArray(StandardCharsets.UTF_8),
            fingerprint.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :module-auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add module-auth/
git commit -m "feat(auth): add FingerprintService"
```

---

## Task 4: Implement PendingLoginRegistry

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

## Task 5: Implement SessionCacheService

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
import java.time.Instant

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

## Task 6: Implement JwtGeneratorService

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

## Task 7: Implement AuthEventPublisher and AuthResponseConsumer

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
        kafkaTemplate.send(requestTopic, request.kafkaKey(), json).whenComplete { result, ex ->
            if (ex != null) {
                log.error("[AuthEvent] failed to publish request: fingerprint={}", request.fingerprint, ex)
            } else {
                log.debug("[AuthEvent] published request: fingerprint={}, topic={}", request.fingerprint, requestTopic)
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
        log.debug("[AuthResponse] received: fingerprint={}, characters={}", response.fingerprint, response.characterOcidMap.size)
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

## Task 8: Implement LoginService (core orchestration)

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

@Service
class LoginService(
    private val fingerprintService: FingerprintService,
    private val sessionCacheService: SessionCacheService,
    private val authEventPublisher: AuthEventPublisher,
    private val pendingLoginRegistry: PendingLoginRegistry,
    private val jwtGeneratorService: JwtGeneratorService,
) {
    fun login(apiKey: String): LoginResult {
        val fingerprint = fingerprintService.generate(apiKey)

        // 1. Check Redis cache
        val cached = sessionCacheService.findByFingerprint(fingerprint)
        if (cached != null) {
            val token = jwtGeneratorService.generateToken(cached.sessionId, cached.fingerprint, cached.role, cached.userIgn)
            log.info("[Login] cache hit: fingerprint={}", fingerprint)
            return LoginResult(token, cached.sessionId, fingerprint, cached.myOcids.size, cached = true)
        }

        // 2. Publish Kafka request
        val request = CharacterFetchRequest(fingerprint = fingerprint, apiKey = apiKey)
        authEventPublisher.publishCharacterFetchRequest(request)

        // 3. Wait for response (30s timeout via PendingLoginRegistry)
        val response = pendingLoginRegistry.register(fingerprint).join()

        // 4. Build session
        val sessionId = UUID.randomUUID().toString()
        val userIgn = response.characterOcidMap.keys.firstOrNull() ?: ""
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

        // 5. Generate JWT
        val token = jwtGeneratorService.generateToken(sessionId, fingerprint, "USER", userIgn)

        log.info("[Login] success: fingerprint={}, characters={}", fingerprint, myOcids.size)
        return LoginResult(token, sessionId, fingerprint, myOcids.size, cached = false)
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
git commit -m "feat(auth): add LoginService orchestration"
```

---

## Task 9: Update rest-controller — LoginController + dependency

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

import maple.auth.login.LoginService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

@RestController
@RequestMapping("/api/v6/auth")
@ConditionalOnProperty(name = ["expectation.v6.enabled"], havingValue = "true")
class AuthController(
    private val loginService: LoginService,
) {
    private val loginExecutor = Executors.newVirtualThreadPerTaskExecutor()

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): CompletableFuture<ResponseEntity<LoginResponse>> =
        CompletableFuture.supplyAsync({
            val result = loginService.login(request.apiKey)
            ResponseEntity.ok(
                LoginResponse(
                    token = result.token,
                    sessionId = result.sessionId,
                    fingerprint = result.fingerprint,
                    characterCount = result.characterCount,
                    cached = result.cached,
                )
            )
        }, loginExecutor)
}

data class LoginRequest(val apiKey: String)

data class LoginResponse(
    val token: String,
    val sessionId: String,
    val fingerprint: String,
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

## Task 10: Update external-api — character fetch handler

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/auth/NexonCharacterListAdapter.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt`

- [ ] **Step 1: Create NexonCharacterListAdapter**

This adapter calls Nexon's `/maplestory/v1/character/list` endpoint and returns the character list. It also resolves OCIDs for each character by calling the existing OCID lookup API.

```kotlin
package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class NexonCharacterListAdapter(
    @Value("\${external-api.nexon.base-url:https://open.api.nexon.com}") private val baseUrl: String,
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun fetchCharacterList(apiKey: String): List<Map<String, Any>> {
        val webClient = WebClient.builder().baseUrl(baseUrl).build()
        val response = webClient.get()
            .uri("/maplestory/v1/character/list")
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(String::class.java)
            .block()

        val json = objectMapper.readTree(response)
        return json.path("character_list").map { node ->
            mapOf(
                "ocid" to node.path("ocid").asText(),
                "character_name" to node.path("character_name").asText(),
                "world_name" to node.path("world_name").asText(),
                "character_class" to node.path("character_class").asText(),
            )
        }
    }

    fun resolveExistingOcids(userIgns: List<String>): Map<String, String> {
        if (userIgns.isEmpty()) return emptyMap()
        val igns = userIgns.mapIndexed { i, _ -> "ign$i" }
        val placeholders = igns.joinToString(",") { ":$it" }
        val params = igns.zip(userIgns).toMap()
        return jdbc.queryForList(
            "SELECT user_ign, ocid FROM game_character WHERE user_ign IN ($placeholders) AND ocid IS NOT NULL",
            params,
        ).associate { it["user_ign"] as String to it["ocid"] as String }
    }

    fun updateFingerprints(ocids: List<String>, fingerprint: String) {
        if (ocids.isEmpty()) return
        ocids.chunked(100).forEach { chunk ->
            val params = mutableMapOf<String, Any>("fp" to fingerprint)
            val placeholders = chunk.mapIndexed { i, _ ->
                params["ocid$i"] = chunk[i]
                ":ocid$i"
            }.joinToString(",")
            jdbc.update(
                "UPDATE game_character SET fingerprint = :fp WHERE ocid IN ($placeholders) AND (fingerprint IS NULL OR fingerprint = '')",
                params,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NexonCharacterListAdapter::class.java)
    }
}
```

- [ ] **Step 2: Create AuthCharacterFetchConsumer**

```kotlin
package maple.externalapi.auth

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.expectation.core.auth.event.CharacterFetchRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class AuthCharacterFetchConsumer(
    private val nexonCharacterListAdapter: NexonCharacterListAdapter,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${auth.kafka.character-fetch-response-topic}") private val responseTopic: String,
) {
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
        log.info("[AuthFetch] processing: fingerprint={}", request.fingerprint)

        val characterOcidMap = mutableMapOf<String, String>()
        val failedIgn = mutableListOf<String>()

        try {
            val characters = nexonCharacterListAdapter.fetchCharacterList(request.apiKey)
            val igns = characters.map { it["character_name"] as String }
            log.info("[AuthFetch] Nexon returned {} characters", characters.size)

            // Resolve existing OCIDs from DB
            val existingOcids = nexonCharacterListAdapter.resolveExistingOcids(igns)
            characterOcidMap.putAll(existingOcids)

            // For characters not in DB, use OCID from Nexon response directly
            for (char in characters) {
                val ign = char["character_name"] as String
                if (ign !in existingOcids) {
                    val ocid = char["ocid"] as String
                    if (ocid.isNotBlank()) {
                        characterOcidMap[ign] = ocid
                    } else {
                        failedIgn.add(ign)
                    }
                }
            }

            // Update fingerprints in DB
            val allOcids = characterOcidMap.values.toList()
            nexonCharacterListAdapter.updateFingerprints(allOcids, request.fingerprint)

        } catch (e: Exception) {
            log.error("[AuthFetch] failed: fingerprint={}", request.fingerprint, e)
        }

        // Publish response
        val response = CharacterFetchResponse(
            eventId = request.eventId,
            fingerprint = request.fingerprint,
            characterOcidMap = characterOcidMap,
            failedIgn = failedIgn,
        )
        val json = objectMapper.writeValueAsString(response)
        kafkaTemplate.send(responseTopic, response.kafkaKey(), json).whenComplete { _, ex ->
            if (ex != null) {
                log.error("[AuthFetch] failed to publish response: fingerprint={}", request.fingerprint, ex)
            }
        }

        acknowledgment.acknowledge()
        log.info("[AuthFetch] completed: fingerprint={}, resolved={}, failed={}",
            request.fingerprint, characterOcidMap.size, failedIgn.size)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AuthCharacterFetchConsumer::class.java)
    }
}
```

- [ ] **Step 3: Compile all**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add module-external-api/
git commit -m "feat(external-api): add auth character fetch consumer with Nexon API"
```

---

## Task 11: YAML configuration

**Files:**
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application-local.yml`
- Modify: `module-external-api/src/main/resources/application.yml`
- Modify: `module-external-api/src/main/resources/application-local.yml`
- Create: `module-auth/src/main/resources/application.yml` (default config)

- [ ] **Step 1: Add auth Kafka topics to rest-controller application.yml**

Add under the existing `expectation:` block or at root level:

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

Add same auth block (values will be resolved from .env).

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

## Task 12: Tests

**Files:**
- Create: `module-auth/src/test/kotlin/maple/auth/login/LoginServiceTest.kt`
- Create: `module-auth/src/test/kotlin/maple/auth/fingerprint/FingerprintServiceTest.kt`
- Create: `module-auth/src/test/kotlin/maple/auth/kafka/PendingLoginRegistryTest.kt`

- [ ] **Step 1: Create FingerprintServiceTest**

```kotlin
package maple.auth.fingerprint

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FingerprintServiceTest {
    private val service = FingerprintService("test-secret-key-for-unit-testing-32ch")

    @Test
    fun `same API key produces same fingerprint`() {
        val fp1 = service.generate("test-api-key-1")
        val fp2 = service.generate("test-api-key-1")
        assertThat(fp1).isEqualTo(fp2)
    }

    @Test
    fun `different API keys produce different fingerprints`() {
        val fp1 = service.generate("test-api-key-1")
        val fp2 = service.generate("test-api-key-2")
        assertThat(fp1).isNotEqualTo(fp2)
    }

    @Test
    fun `verify returns true for matching key`() {
        val fp = service.generate("test-api-key-1")
        assertThat(service.verify("test-api-key-1", fp)).isTrue()
    }

    @Test
    fun `verify returns false for wrong key`() {
        val fp = service.generate("test-api-key-1")
        assertThat(service.verify("test-api-key-2", fp)).isFalse()
    }
}
```

- [ ] **Step 2: Create PendingLoginRegistryTest**

```kotlin
package maple.auth.kafka

import maple.expectation.core.auth.event.CharacterFetchResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PendingLoginRegistryTest {
    private val registry = PendingLoginRegistry()

    @Test
    fun `complete resolves the future`() {
        val future = registry.register("fp-1")
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
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
            characterOcidMap = emptyMap(),
        )
        registry.complete(response) // should not throw
    }
}
```

- [ ] **Step 3: Create LoginServiceTest**

```kotlin
package maple.auth.login

import maple.expectation.core.auth.event.CharacterFetchResponse
import maple.auth.fingerprint.FingerprintService
import maple.auth.jwt.JwtGeneratorService
import maple.auth.kafka.AuthEventPublisher
import maple.auth.kafka.PendingLoginRegistry
import maple.auth.session.SessionCacheService
import maple.expectation.core.domain.auth.Session
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class LoginServiceTest {
    @Mock private lateinit var fingerprintService: FingerprintService
    @Mock private lateinit var sessionCacheService: SessionCacheService
    @Mock private lateinit var authEventPublisher: AuthEventPublisher
    @Mock private lateinit var pendingLoginRegistry: PendingLoginRegistry
    @Mock private lateinit var jwtGeneratorService: JwtGeneratorService

    @Captor private lateinit var requestCaptor: ArgumentCaptor<maple.expectation.core.auth.event.CharacterFetchRequest>

    private val testSecret = "c3b16a6c3e66702e732c140765e7737e41302110555cabce43c4742ec1d8a9cc"

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

        val result = service.login("key")
        assertThat(result.cached).isTrue()
        assertThat(result.token).isEqualTo("jwt-token")
    }

    @Test
    fun `publishes Kafka request on cache miss`() {
        val service = createService()
        whenever(fingerprintService.generate("key")).thenReturn("fp-1")
        whenever(sessionCacheService.findByFingerprint("fp-1")).thenReturn(null)
        whenever(jwtGeneratorService.generateToken(any(), any(), any(), any())).thenReturn("jwt-token")

        // Simulate Kafka response
        val response = CharacterFetchResponse(
            eventId = "evt-1",
            fingerprint = "fp-1",
            characterOcidMap = mapOf("User1" to "ocid-1"),
        )
        whenever(pendingLoginRegistry.register("fp-1")).thenReturn(
            java.util.concurrent.CompletableFuture.completedFuture(response)
        )

        val result = service.login("key")
        assertThat(result.cached).isFalse()
        assertThat(result.characterCount).isEqualTo(1)
        verify(authEventPublisher).publishCharacterFetchRequest(capture(requestCaptor))
        assertThat(requestCaptor.value.fingerprint).isEqualTo("fp-1")
        verify(sessionCacheService).save(any())
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

## Task 13: Full compilation + integration verification

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
| `settings.gradle` | Add `include 'module-auth'` |
| `module-auth/build.gradle` | New — Spring Kafka, Redis, JJWT, module-core |
| `module-core/.../core/auth/event/CharacterFetchRequest.kt` | New — Kafka request DTO |
| `module-core/.../core/auth/event/CharacterFetchResponse.kt` | New — Kafka response DTO |
| `module-auth/.../fingerprint/FingerprintService.kt` | New — HMAC-SHA256 fingerprint |
| `module-auth/.../jwt/JwtGeneratorService.kt` | New — JWT generation |
| `module-auth/.../kafka/PendingLoginRegistry.kt` | New — CF correlation |
| `module-auth/.../kafka/AuthEventPublisher.kt` | New — Kafka producer |
| `module-auth/.../kafka/AuthResponseConsumer.kt` | New — Kafka consumer |
| `module-auth/.../login/LoginResult.kt` | New — result DTO |
| `module-auth/.../login/LoginService.kt` | New — core orchestration |
| `module-auth/.../session/SessionCacheService.kt` | New — Redis cache |
| `module-rest-controller/build.gradle` | Add module-auth dependency |
| `module-rest-controller/.../RestControllerApplication.kt` | Expand component scan |
| `module-rest-controller/.../controller/v6/AuthController.kt` | New — POST /api/v6/auth/login |
| `module-external-api/.../auth/NexonCharacterListAdapter.kt` | New — Nexon API + DB |
| `module-external-api/.../auth/AuthCharacterFetchConsumer.kt` | New — Kafka consumer |
| `module-*/src/main/resources/application*.yml` | Auth Kafka topics, JWT, fingerprint config |
| `module-auth/src/test/...` | Unit tests |
