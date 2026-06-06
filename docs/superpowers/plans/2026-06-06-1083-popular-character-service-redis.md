# #1083 — PopularCharacterService Redis Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extract all `StringRedisTemplate` / ZSET operations out of `PopularCharacterService` into a `PopularCharacterRedisPort` interface + `PopularCharacterRedisAdapter` implementation, preserving public behavior and the rolling-window degradation logic.

**Architecture:** Hexagonal port/adapter — service depends on the outbound port, adapter is the sole consumer of `StringRedisTemplate`. Rolling-key generation moves into the adapter (it is Redis-shaped, not business).

**Tech Stack:** Kotlin, Spring Boot, Spring Data Redis (Lettuce), Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/port/out/PopularCharacterRedisPort.kt` | NEW — port interface |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/adapter/out/PopularCharacterRedisAdapter.kt` | NEW — port impl using `StringRedisTemplate` |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/PopularCharacterService.kt` | MODIFIED — depend on port, not template |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/PopularCharacterServiceTest.kt` | MODIFIED (if exists) — test port behavior |

---

## Task 1: Define the port interface

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/port/out/PopularCharacterRedisPort.kt`

- [ ] **Step 1: Create port interface**

```kotlin
package maple.restcontroller.character.popular.port.out

import java.time.Instant

/**
 * Outbound port for PopularCharacterService's Redis ZSET persistence.
 * Adapter implementations encapsulate `StringRedisTemplate` details and
 * rolling-key generation; service depends on this interface only.
 */
interface PopularCharacterRedisPort {
    /** Increment the IGN's score by [delta] in the current rolling window. */
    fun incrementScore(ign: String, delta: Double, now: Instant = Instant.now())

    /** Set TTL on the IGN's rolling-window ZSET key to expire at [expireAt]. */
    fun expireAt(ign: String, expireAt: Instant)

    /** Aggregate [sources] ZSETs into [destination] (UNION STORE), then expire destination. */
    fun unionAndStore(destination: String, sources: List<String>, expireAt: Instant)
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/port/out/PopularCharacterRedisPort.kt
git commit -m "refactor(rest-controller): define PopularCharacterRedisPort (#1083)"
```

---

## Task 2: Implement the adapter

**Files:**
- Create: `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/adapter/out/PopularCharacterRedisAdapter.kt`

- [ ] **Step 1: Create adapter implementation**

```kotlin
package maple.restcontroller.character.popular.adapter.out

import maple.restcontroller.character.popular.port.out.PopularCharacterRedisPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Redis adapter — only file in module-rest-controller that touches [StringRedisTemplate].
 * Owns rolling-key generation so callers express intent ("read at time T")
 * without leaking Redis-shaped key names into the service layer.
 */
@Component
class PopularCharacterRedisAdapter(
    private val redisTemplate: StringRedisTemplate,
) : PopularCharacterRedisPort {

    override fun incrementScore(ign: String, delta: Double, now: Instant) {
        val key = rollingWriteKey(ign, now)
        redisTemplate.opsForZSet().incrementScore(key, ign, delta)
    }

    override fun expireAt(ign: String, expireAt: Instant) {
        val key = rollingWriteKey(ign, expireAt)
        val ttl = Duration.between(Instant.now(), expireAt)
        if (ttl.isNegative || ttl.isZero) return
        redisTemplate.expire(key, ttl)
    }

    override fun unionAndStore(destination: String, sources: List<String>, expireAt: Instant) {
        if (sources.isEmpty()) return
        redisTemplate.opsForZSet().unionAndStore(sources.first(), sources.drop(1), destination)
        val ttl = Duration.between(Instant.now(), expireAt)
        if (ttl.isPositive) redisTemplate.expire(destination, ttl)
    }

    /** Key naming convention: `<prefix>:write:<ign>:<epochHour>`. The "write" half is the
     *  hot key; the read aggregation happens elsewhere via [unionAndStore]. */
    private fun rollingWriteKey(ign: String, at: Instant): String =
        "popular:write:$ign:${at.epochSecond / 3600}"
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:compileKotlin --console=plain
```

Expected: success.

- [ ] **Step 3: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/adapter/out/PopularCharacterRedisAdapter.kt
git commit -m "refactor(rest-controller): add PopularCharacterRedisAdapter (#1083)"
```

---

## Task 3: Refactor the service to depend on the port

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/PopularCharacterService.kt`

- [ ] **Step 1: Replace `StringRedisTemplate` field with the port**

In the constructor, replace `private val redisTemplate: StringRedisTemplate` with `private val redisPort: PopularCharacterRedisPort`. Drop the unused import of `StringRedisTemplate`/`ZSetOperations`.

- [ ] **Step 2: Replace all `redisTemplate.opsForZSet()` call sites**

Find each `redisTemplate.opsForZSet().<op>(...)` and `redisTemplate.expire(...)` in the file. Map to port methods:

| Old call | New call |
|---|---|
| `redisTemplate.opsForZSet().incrementScore(key, ign, delta)` | `redisPort.incrementScore(ign, delta)` |
| `redisTemplate.expire(key, ttl)` after increment | `redisPort.expireAt(ign, expireAt)` |
| `redisTemplate.opsForZSet().unionAndStore(...)` + `redisTemplate.expire(...)` | `redisPort.unionAndStore(destination, sources, expireAt)` |

Delete the local `rollingReadKey` private function — key generation lives in the adapter now.

- [ ] **Step 3: Compile + test**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:compileKotlin --console=plain
./gradlew :module-rest-controller:test --console=plain
```

Expected: compile success, all tests pass (no behavior change).

- [ ] **Step 4: Commit**

```bash
git add module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/PopularCharacterService.kt
git commit -m "refactor(rest-controller): route PopularCharacterService through Redis port (#1083)"
```

---

## Task 4: Final verification

- [ ] **Step 1: Confirm service has no Redis imports**

```bash
grep -n "StringRedisTemplate\|ZSetOperations" module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/PopularCharacterService.kt
```

Expected: no output.

- [ ] **Step 2: Confirm only the adapter uses `StringRedisTemplate` in the package**

```bash
grep -rn "StringRedisTemplate" module-rest-controller/src/main/kotlin/maple/restcontroller/character/popular/
```

Expected: only the adapter path matches.

- [ ] **Step 3: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-rest-controller:test --console=plain
```

Expected: all pass.

---

## Self-Review

- **Spec coverage:** ✅ Three components in spec (port, adapter, modified service) covered by Tasks 1-3. Rolling-key generation in adapter ✅.
- **Placeholder scan:** No TBD/TODO. Method signatures fixed.
- **Type consistency:** Port uses `Instant` and `Duration`; adapter matches. Service-side calls match port signatures.
