# V6 Like Feature — Redis Set + Lua Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement V6 like toggle/status endpoints in module-rest-controller using Redis Set + Lua atomic toggle with async Write-Back to PostgreSQL.

**Architecture:** Redis Set per target (`like:{ocid}`) stores liker accountIds. Lua script handles atomic toggle (SISMEMBER → SADD/SREM → SCARD). Dirty set (`like:dirty`) tracks changed targets for periodic Write-Back to `character_like` table. DB trigger `fn_like_count_trigger` maintains `game_character.like_count`. New `LikeCommandPort` interface avoids bean conflict with existing `LikeTogglePort`/`LikeToggleService` in module-infra.

**Tech Stack:** Kotlin, Spring Boot, StringRedisTemplate, Lua script, JDBC, Spring Security + JWT

---

## File Structure

```
module-rest-controller/src/main/kotlin/maple/restcontroller/
  like/
    LikeCommandPort.kt                       — New interface (avoids bean conflict with LikeTogglePort)
    LikeController.kt                        — V6 like endpoints
    RedisLikeToggleService.kt                — Redis Set + Lua toggle (implements LikeCommandPort)
    LikeWriteBackScheduler.kt                — Periodic Redis → DB sync
    LikeLuaScripts.kt                        — Lua script definitions
    LikeModels.kt                            — DTOs (request/response)
module-rest-controller/src/test/kotlin/maple/restcontroller/
  like/
    RedisLikeToggleServiceTest.kt            — Unit tests with mock Redis
    LikeWriteBackSchedulerTest.kt            — Unit tests for write-back
    LikeControllerTest.kt                    — Controller tests
module-rest-controller/src/main/resources/
  application.yml                            — Add like config section
  application-local.yml                      — Enable like feature
```

**Existing files modified:**
- `module-rest-controller/build.gradle` — Add module-infra dependency

**Existing files referenced (not modified):**
- `module-infra/.../like/OcidResolutionService.java` — IGN → OCID resolution
- `module-core/.../core/domain/model/like/LikeToggleResult.kt` — Enum (LIKED/UNLIKED)
- `module-core/.../core/domain/model/like/LikeToggleWithCount.kt` — Domain model
- `module-core/.../core/domain/model/security/AuthenticatedUser.kt` — Auth principal
- `module-common/.../common/logic/LogicExecutor.kt` — Exception handling

---

### Task 1: Add module-infra dependency + Enable Scheduling

**Context:** module-rest-controller needs module-infra for `OcidResolutionService`, `JwtAuthenticationFilter`, `SecurityConfig`, and `LogicExecutor`. Do NOT create a separate SecurityConfig — module-infra's will auto-configure.

**Files:**
- Modify: `module-rest-controller/build.gradle`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/RestControllerApplication.kt`

- [ ] **Step 1: Add dependency to build.gradle**

Add to `dependencies` block:
```groovy
implementation project(':module-infra')
```

- [ ] **Step 2: Add `@EnableScheduling` to application class**

```kotlin
package maple.restcontroller

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class RestControllerApplication
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :module-rest-controller:compileKotlin --quiet`
Expected: SUCCESS

- [ ] **Step 4: Commit**

```bash
git add module-rest-controller/build.gradle module-rest-controller/src/main/kotlin/maple/restcontroller/RestControllerApplication.kt
git commit -m "feat(like): add module-infra dependency + enable scheduling"
```

---

### Task 2: LikeCommandPort — New Interface

**Context:** Existing `LikeTogglePort` in module-core has `LikeToggleService` implementation in module-infra. To avoid bean conflict, create a dedicated `LikeCommandPort` for the Redis-based V6 like feature.

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeCommandPort.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package maple.restcontroller.like

import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount

interface LikeCommandPort {

    fun toggleLikeWithCount(
        targetOcid: String,
        accountId: String,
        myOcids: List<String>,
    ): LikeToggleWithCount

    fun isLiked(targetOcid: String, accountId: String): Boolean

    fun getLikeCount(targetOcid: String): Long
}
```

- [ ] **Step 2: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeCommandPort.kt
git commit -m "feat(like): add LikeCommandPort interface for V6 Redis like"
```

---

### Task 3: Redis Lua Script for Atomic Like Toggle

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeLuaScripts.kt`

- [ ] **Step 1: Write Lua script constants**

```kotlin
package maple.restcontroller.like

object LikeLuaScripts {

    /**
     * Atomic toggle: check membership → add/remove → return {liked, count}
     * KEYS[1] = like:{targetOcid}
     * ARGV[1] = accountId
     * Returns: {1/0, count} where 1=liked, 0=unliked
     */
    val TOGGLE = """
        local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
        if exists == 1 then
            redis.call('SREM', KEYS[1], ARGV[1])
            return {0, redis.call('SCARD', KEYS[1])}
        else
            redis.call('SADD', KEYS[1], ARGV[1])
            return {1, redis.call('SCARD', KEYS[1])}
        end
    """.trimIndent()

    /**
     * Check status + get count in one call.
     * KEYS[1] = like:{targetOcid}
     * ARGV[1] = accountId
     * Returns: {1/0, count}
     */
    val STATUS = """
        local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])
        local count = redis.call('SCARD', KEYS[1])
        return {isMember, count}
    """.trimIndent()
}
```

- [ ] **Step 2: Write integration tests for Lua script logic**

```kotlin
package maple.restcontroller.like

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

@SpringBootTest
class LikeLuaScriptsIntegrationTest {

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val toggleScript = DefaultRedisScript<List<Long>>(LikeLuaScripts.TOGGLE, List::class.java)
    private val statusScript = DefaultRedisScript<List<Long>>(LikeLuaScripts.STATUS, List::class.java)

    @BeforeEach
    fun cleanup() {
        redisTemplate.delete("like:test-ocid")
    }

    @Test
    fun `toggle - first like returns liked=1 count=1`() {
        val result = redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-123")!!
        assertThat(result[0]).isEqualTo(1L)
        assertThat(result[1]).isEqualTo(1L)
    }

    @Test
    fun `toggle - second toggle returns liked=0 count=0 (unlike)`() {
        redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-123")
        val result = redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-123")!!
        assertThat(result[0]).isEqualTo(0L)
        assertThat(result[1]).isEqualTo(0L)
    }

    @Test
    fun `toggle - multiple users count correctly`() {
        redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-1")
        redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-2")
        redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-3")
        val result = redisTemplate.execute(statusScript, listOf("like:test-ocid"), "user-1")!!
        assertThat(result[0]).isEqualTo(1L) // isMember
        assertThat(result[1]).isEqualTo(3L) // count
    }

    @Test
    fun `status - non-member returns 0`() {
        redisTemplate.execute(toggleScript, listOf("like:test-ocid"), "user-1")
        val result = redisTemplate.execute(statusScript, listOf("like:test-ocid"), "user-999")!!
        assertThat(result[0]).isEqualTo(0L)
        assertThat(result[1]).isEqualTo(1L)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.LikeLuaScriptsIntegrationTest"`
Expected: All 4 tests PASS

- [ ] **Step 4: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeLuaScripts.kt module-rest-controller/src/test/kotlin/maple/restcontroller/like/LikeLuaScriptsIntegrationTest.kt
git commit -m "feat(like): add Redis Lua scripts for atomic like toggle + status"
```

---

### Task 4: RedisLikeToggleService — Core Like Logic

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/RedisLikeToggleService.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package maple.restcontroller.like

import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate

@SpringBootTest
class RedisLikeToggleServiceTest {

    @Autowired
    private lateinit var service: LikeCommandPort

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun cleanup() {
        val keys = redisTemplate.keys("like:test-*") ?: emptySet()
        keys.forEach { redisTemplate.delete(it) }
    }

    @Test
    fun `toggle - first like returns LIKED`() {
        val result = service.toggleLikeWithCount("test-ocid1", "account1", emptyList())
        assertThat(result.result).isEqualTo(LikeToggleResult.LIKED)
        assertThat(result.likeCount).isEqualTo(1L)
    }

    @Test
    fun `toggle - second toggle returns UNLIKED`() {
        service.toggleLikeWithCount("test-ocid1", "account1", emptyList())
        val result = service.toggleLikeWithCount("test-ocid1", "account1", emptyList())
        assertThat(result.result).isEqualTo(LikeToggleResult.UNLIKED)
        assertThat(result.likeCount).isEqualTo(0L)
    }

    @Test
    fun `toggle - self-like blocked when target in myOcids`() {
        val result = service.toggleLikeWithCount("myOcid", "account1", listOf("myOcid"))
        assertThat(result.result).isEqualTo(LikeToggleResult.UNLIKED)
    }

    @Test
    fun `isLiked - returns correct status`() {
        service.toggleLikeWithCount("test-ocid1", "account1", emptyList())
        assertThat(service.isLiked("test-ocid1", "account1")).isTrue()
        assertThat(service.isLiked("test-ocid1", "account2")).isFalse()
    }

    @Test
    fun `getLikeCount - returns correct count`() {
        service.toggleLikeWithCount("test-ocid1", "account1", emptyList())
        service.toggleLikeWithCount("test-ocid1", "account2", emptyList())
        assertThat(service.getLikeCount("test-ocid1")).isEqualTo(2L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.RedisLikeToggleServiceTest"`
Expected: FAIL — no bean of type `LikeCommandPort`

- [ ] **Step 3: Implement RedisLikeToggleService**

```kotlin
package maple.restcontroller.like

import maple.expectation.common.logic.LogicExecutor
import maple.expectation.common.logic.TaskContext
import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["like.v6.enabled"], havingValue = "true")
class RedisLikeToggleService(
    private val redisTemplate: StringRedisTemplate,
    private val logicExecutor: LogicExecutor,
) : LikeCommandPort {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "like:"
        private const val DIRTY_KEY = "like:dirty"

        private fun likeKey(targetOcid: String) = "$KEY_PREFIX$targetOcid"
    }

    private val toggleScript = DefaultRedisScript<List<Long>>(LikeLuaScripts.TOGGLE, List::class.java)
    private val statusScript = DefaultRedisScript<List<Long>>(LikeLuaScripts.STATUS, List::class.java)

    override fun toggleLikeWithCount(
        targetOcid: String,
        accountId: String,
        myOcids: List<String>,
    ): LikeToggleWithCount {
        return logicExecutor.execute({
            if (targetOcid in myOcids) {
                log.debug("[Like] self-like blocked: account={} target={}", accountId, targetOcid)
                return@execute LikeToggleWithCount(LikeToggleResult.UNLIKED, getCount(targetOcid))
            }

            val result = redisTemplate.execute(
                toggleScript,
                listOf(likeKey(targetOcid)),
                accountId,
            ) ?: return@execute LikeToggleWithCount(LikeToggleResult.UNLIKED, 0L)

            val liked = result[0] == 1L
            val count = result[1]
            val toggleResult = if (liked) LikeToggleResult.LIKED else LikeToggleResult.UNLIKED

            redisTemplate.opsForSet().add(DIRTY_KEY, targetOcid)

            log.info("[Like] toggle: account={} target={} result={} count={}", accountId, targetOcid, toggleResult, count)
            LikeToggleWithCount(toggleResult, count)
        }, TaskContext.of("Like", "RedisToggle", "$targetOcid:$accountId"))
    }

    override fun isLiked(targetOcid: String, accountId: String): Boolean {
        return redisTemplate.opsForSet().isMember(likeKey(targetOcid), accountId) == true
    }

    override fun getLikeCount(targetOcid: String): Long {
        val result = redisTemplate.execute(
            statusScript,
            listOf(likeKey(targetOcid)),
            "__count__",
        )
        return result?.get(1) ?: 0L
    }

    private fun getCount(targetOcid: String): Long {
        return redisTemplate.opsForSet().size(likeKey(targetOcid)) ?: 0L
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.RedisLikeToggleServiceTest"`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/like/RedisLikeToggleService.kt module-rest-controller/src/test/kotlin/maple/restcontroller/like/RedisLikeToggleServiceTest.kt
git commit -m "feat(like): implement RedisLikeToggleService with Lua atomic toggle"
```

---

### Task 5: LikeWriteBackScheduler — Redis → DB Sync

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeWriteBackScheduler.kt`

**Context:** Periodically drains `like:dirty` set, reads Redis Set members, compares with `character_like` DB rows, syncs delta. DB trigger handles `like_count`. Uses `LogicExecutor` instead of `runCatching`.

- [ ] **Step 1: Write failing test**

```kotlin
package maple.restcontroller.like

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

@SpringBootTest
class LikeWriteBackSchedulerTest {

    @Autowired
    private lateinit var scheduler: LikeWriteBackScheduler

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun cleanup() {
        val keys = redisTemplate.keys("like:test-*") ?: emptySet()
        keys.forEach { redisTemplate.delete(it) }
        jdbc.update("DELETE FROM character_like WHERE target_ocid LIKE 'test-%'", emptyMap<String, Any>())
    }

    @Test
    fun `sync - writes new likes to DB`() {
        redisTemplate.opsForSet().add("like:test-ocid-1", "acc-1", "acc-2")
        redisTemplate.opsForSet().add("like:dirty", "test-ocid-1")

        scheduler.sync()

        val count = jdbc.queryForObject(
            "SELECT count(*) FROM character_like WHERE target_ocid = 'test-ocid-1'",
            emptyMap<String, Any>(),
            Long::class.java,
        )
        assertThat(count).isEqualTo(2L)
        assertThat(redisTemplate.opsForSet().isMember("like:dirty", "test-ocid-1")).isFalse()
    }

    @Test
    fun `sync - removes unliked entries from DB`() {
        jdbc.update(
            "INSERT INTO character_like (target_ocid, liker_account_id, created_at) VALUES ('test-ocid-1', 'acc-1', now())",
            emptyMap<String, Any>(),
        )
        redisTemplate.opsForSet().add("like:test-ocid-1", "acc-2")
        redisTemplate.opsForSet().add("like:dirty", "test-ocid-1")

        scheduler.sync()

        val rows = jdbc.queryForList(
            "SELECT liker_account_id FROM character_like WHERE target_ocid = 'test-ocid-1'",
            emptyMap<String, Any>(),
            String::class.java,
        )
        assertThat(rows).containsExactly("acc-2")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.LikeWriteBackSchedulerTest"`
Expected: FAIL — no `LikeWriteBackScheduler` bean

- [ ] **Step 3: Implement LikeWriteBackScheduler**

```kotlin
package maple.restcontroller.like

import maple.expectation.common.logic.LogicExecutor
import maple.expectation.common.logic.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["like.v6.enabled"], havingValue = "true")
class LikeWriteBackScheduler(
    private val redisTemplate: StringRedisTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val logicExecutor: LogicExecutor,
    @Value("\${like.writeback.batch-size:100}")
    private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DIRTY_KEY = "like:dirty"
        private const val LIKE_KEY_PREFIX = "like:"
    }

    @Scheduled(fixedDelayString = "\${like.writeback.interval-ms:5000}")
    fun sync() {
        val dirtyTargets = popDirtyTargets()
        if (dirtyTargets.isEmpty()) return

        var synced = 0
        for (targetOcid in dirtyTargets) {
            logicExecutor.executeOrDefault({
                syncTarget(targetOcid)
                synced++
            }, Unit, TaskContext.of("LikeWriteBack", "SyncTarget", targetOcid))
        }
        log.info("[LikeWriteBack] synced {}/{} targets", synced, dirtyTargets.size)
    }

    internal fun popDirtyTargets(): List<String> {
        val targets = mutableListOf<String>()
        repeat(batchSize) {
            val target = redisTemplate.opsForSet().pop(DIRTY_KEY) ?: return targets
            targets.add(target)
        }
        return targets
    }

    private fun syncTarget(targetOcid: String) {
        val key = "$LIKE_KEY_PREFIX$targetOcid"
        val redisMembers = redisTemplate.opsForSet().members(key)?.toSet() ?: emptySet()

        val dbMembers = jdbc.queryForList(
            "SELECT liker_account_id FROM character_like WHERE target_ocid = :targetOcid",
            mapOf("targetOcid" to targetOcid),
            String::class.java,
        ).toSet()

        val toInsert = redisMembers - dbMembers
        val toDelete = dbMembers - redisMembers

        if (toInsert.isNotEmpty()) {
            val args = toInsert.map { accountId ->
                arrayOf<Any>(targetOcid, accountId)
            }
            jdbc.jdbcTemplate.batchUpdate(
                "INSERT INTO character_like (target_ocid, liker_account_id, created_at) VALUES (?, ?, NOW()) ON CONFLICT (target_ocid, liker_account_id) DO NOTHING",
                args,
            )
        }

        if (toDelete.isNotEmpty()) {
            jdbc.update(
                "DELETE FROM character_like WHERE target_ocid = :targetOcid AND liker_account_id IN (:accountIds)",
                mapOf("targetOcid" to targetOcid, "accountIds" to toDelete.toList()),
            )
        }

        log.debug("[LikeWriteBack] target={} insert={} delete={}", targetOcid, toInsert.size, toDelete.size)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.LikeWriteBackSchedulerTest"`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeWriteBackScheduler.kt module-rest-controller/src/test/kotlin/maple/restcontroller/like/LikeWriteBackSchedulerTest.kt
git commit -m "feat(like): add LikeWriteBackScheduler for Redis to DB async sync"
```

---

### Task 6: V6 Like Controller + DTOs

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeController.kt`
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeModels.kt`

**Context:** Controller resolves IGN → OCID via `OcidResolutionService` (from module-infra) before calling Redis. This ensures Redis keys and DB entries use OCID, consistent with V4.

- [ ] **Step 1: Create DTOs**

```kotlin
package maple.restcontroller.like

data class LikeToggleResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long,
)

data class LikeStatusResponse(
    val targetUserIgn: String,
    val liked: Boolean,
    val likeCount: Long,
)
```

- [ ] **Step 2: Create controller**

```kotlin
package maple.restcontroller.like

import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.security.AuthenticatedUser
import maple.expectation.infrastructure.like.OcidResolutionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v6/characters")
@ConditionalOnProperty(name = ["like.v6.enabled"], havingValue = "true")
class LikeController(
    private val likeCommandPort: LikeCommandPort,
    private val ocidResolutionService: OcidResolutionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{userIgn}/like")
    @PreAuthorize("isAuthenticated()")
    fun toggleLike(
        @PathVariable userIgn: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<LikeToggleResponse> {
        val targetOcid = ocidResolutionService.resolveOcid(userIgn)
            ?: return ResponseEntity.notFound().build()

        val result = likeCommandPort.toggleLikeWithCount(
            targetOcid = targetOcid,
            accountId = user.accountId,
            myOcids = user.myOcids,
        )
        val liked = result.result == LikeToggleResult.LIKED
        log.info("[Like] toggle: userIgn={} ocid={} accountId={} liked={}", userIgn, targetOcid, user.accountId, liked)
        return ResponseEntity.ok(
            LikeToggleResponse(
                targetUserIgn = userIgn,
                liked = liked,
                likeCount = result.likeCount,
            ),
        )
    }

    @GetMapping("/{userIgn}/like/status")
    @PreAuthorize("isAuthenticated()")
    fun getLikeStatus(
        @PathVariable userIgn: String,
        @AuthenticationPrincipal user: AuthenticatedUser,
    ): ResponseEntity<LikeStatusResponse> {
        val targetOcid = ocidResolutionService.resolveOcid(userIgn)
            ?: return ResponseEntity.notFound().build()

        val liked = likeCommandPort.isLiked(targetOcid, user.accountId)
        val count = likeCommandPort.getLikeCount(targetOcid)
        return ResponseEntity.ok(
            LikeStatusResponse(
                targetUserIgn = userIgn,
                liked = liked,
                likeCount = count,
            ),
        )
    }
}
```

- [ ] **Step 3: Write controller test**

```kotlin
package maple.restcontroller.like

import maple.expectation.core.domain.model.like.LikeToggleResult
import maple.expectation.core.domain.model.like.LikeToggleWithCount
import maple.expectation.infrastructure.like.OcidResolutionService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.bean.MockitoBean
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(LikeController::class)
class LikeControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var likeCommandPort: LikeCommandPort

    @MockitoBean
    private lateinit var ocidResolutionService: OcidResolutionService

    @Test
    @WithMockUser
    fun `POST like - returns 200 with toggle result`() {
        whenever(ocidResolutionService.resolveOcid("testChar")).thenReturn("ocid-123")
        whenever(likeCommandPort.toggleLikeWithCount("ocid-123", "user", emptyList()))
            .thenReturn(LikeToggleWithCount(LikeToggleResult.LIKED, 1L))

        mockMvc.perform(post("/api/v6/characters/testChar/like"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.targetUserIgn").value("testChar"))
            .andExpect(jsonPath("$.liked").value(true))
            .andExpect(jsonPath("$.likeCount").value(1))
    }

    @Test
    @WithMockUser
    fun `POST like - returns 404 when OCID not found`() {
        whenever(ocidResolutionService.resolveOcid("unknownChar")).thenReturn(null)

        mockMvc.perform(post("/api/v6/characters/unknownChar/like"))
            .andExpect(status().isNotFound)
    }

    @Test
    @WithMockUser
    fun `GET like status - returns 200 with status`() {
        whenever(ocidResolutionService.resolveOcid("testChar")).thenReturn("ocid-123")
        whenever(likeCommandPort.isLiked("ocid-123", "user")).thenReturn(true)
        whenever(likeCommandPort.getLikeCount("ocid-123")).thenReturn(5L)

        mockMvc.perform(get("/api/v6/characters/testChar/like/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.liked").value(true))
            .andExpect(jsonPath("$.likeCount").value(5))
    }

    @Test
    fun `POST like - without auth returns 401`() {
        mockMvc.perform(post("/api/v6/characters/testChar/like"))
            .andExpect(status().isUnauthorized)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :module-rest-controller:test --tests "maple.restcontroller.like.LikeControllerTest"`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeController.kt module-rest-controller/src/main/kotlin/maple/restcontroller/like/LikeModels.kt module-rest-controller/src/test/kotlin/maple/restcontroller/like/LikeControllerTest.kt
git commit -m "feat(like): add V6 like controller with toggle + status endpoints"
```

---

### Task 7: Configuration

**Files:**
- Modify: `module-rest-controller/src/main/resources/application.yml`
- Modify: `module-rest-controller/src/main/resources/application-local.yml`

- [ ] **Step 1: Add like config to application.yml**

```yaml
like:
  v6:
    enabled: false
  writeback:
    interval-ms: 5000
    batch-size: 100
```

- [ ] **Step 2: Enable like in application-local.yml**

```yaml
like:
  v6:
    enabled: true
```

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/resources/application.yml module-rest-controller/src/main/resources/application-local.yml
git commit -m "feat(like): add like configuration with feature flag"
```

---

### Task 8: Integration Verification

- [ ] **Step 1: Full compilation check**

Run: `./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS

- [ ] **Step 2: Full test suite**

Run: `./gradlew :module-rest-controller:test`
Expected: All tests PASS

- [ ] **Step 3: Runtime verification**

Start module-rest-controller:
```bash
set -a && source .env && set +a
./gradlew :module-rest-controller:bootRun
```

Test endpoints:
```bash
# Should return 401 (no auth)
curl -s -w "\nHTTP %{http_code}" "http://localhost:8084/api/v6/characters/testChar/like/status"
```

- [ ] **Step 4: Final commit if any fixes needed**

```bash
git add -A
git commit -m "feat(like): V6 Redis Set like feature complete — toggle + status + write-back"
```
