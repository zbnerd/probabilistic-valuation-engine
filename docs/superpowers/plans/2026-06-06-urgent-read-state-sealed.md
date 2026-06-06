# UrgentReadState Sealed Class Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `UrgentReadState` enum with sealed class hierarchy; carry behavior on subtypes; preserve JSON contract.

**Architecture:** Convert enum to sealed class in same file (`UrgentReadStatus.kt`). 4 subtypes: `Ready`, `NotFound`, `Pending` (data class), `Unknown`. Jackson uses `@JsonValue`/`@JsonCreator` for backward-compatible JSON. `ReadModelCacheService.status()` builds subtype directly. `ExpectationV6Controller.getStatus()` uses `state.shouldTryDb()`.

**Tech Stack:** Kotlin sealed class, Jackson, Spring Web

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/UrgentReadStatus.kt` | Modify | Convert enum to sealed class with subtypes + behavior |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt` | Modify | `status()` constructs subtype directly |
| `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt` | Modify | Use `state.shouldTryDb()` instead of enum comparison |
| `module-rest-controller/src/test/kotlin/maple/restcontroller/read/UrgentReadStateTest.kt` | Create | Behavior + JSON round-trip tests |

---

## Task 1: Convert UrgentReadState to sealed class

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/UrgentReadStatus.kt`

- [ ] **Step 1: Replace the enum with sealed class**

Replace the entire file contents with:

```kotlin
package maple.restcontroller.read

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

sealed class UrgentReadState(val name: String) {
    abstract fun retryAfterSeconds(configDefault: Long): Long
    abstract fun shouldTryDb(): Boolean
    open val queuePositionApprox: Long? = null
    open val estimatedWaitSeconds: Long? = null

    @JsonCreator
    companion object {
        fun fromName(s: String): UrgentReadState = when (s) {
            Ready.NAME -> Ready
            NotFound.NAME -> NotFound
            Pending.NAME -> Pending(null, null)
            Unknown.NAME -> Unknown
            else -> throw IllegalArgumentException("Unknown UrgentReadState name: $s")
        }
    }

    /** Singleton — use `is Ready` checks, not `===`. */
    object Ready : UrgentReadState(NAME) {
        const val NAME: String = "READY"
        override fun retryAfterSeconds(configDefault: Long): Long = 0L
        override fun shouldTryDb(): Boolean = false
    }

    /** Singleton — use `is NotFound` checks, not `===`. */
    object NotFound : UrgentReadState(NAME) {
        const val NAME: String = "NOT_FOUND"
        override fun retryAfterSeconds(configDefault: Long): Long = 0L
        override fun shouldTryDb(): Boolean = false
    }

    data class Pending(
        override val queuePositionApprox: Long?,
        override val estimatedWaitSeconds: Long?,
    ) : UrgentReadState(NAME) {
        companion object { const val NAME: String = "PENDING" }
        override fun retryAfterSeconds(configDefault: Long): Long = configDefault
        override fun shouldTryDb(): Boolean = true
    }

    /** Singleton — use `is Unknown` checks, not `===`. */
    object Unknown : UrgentReadState(NAME) {
        const val NAME: String = "UNKNOWN"
        override fun retryAfterSeconds(configDefault: Long): Long = configDefault
        override fun shouldTryDb(): Boolean = true
    }
}

data class UrgentReadStatusResponse(
    val state: UrgentReadState,
    val userIgn: String,
    val statusUrl: String,
    val queuePositionApprox: Long?,
    val estimatedWaitSeconds: Long?,
    val retryAfterSeconds: Long,
)
```

- [ ] **Step 2: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-rest-controller:compileKotlin --continue`
Expected: FAIL only in `ReadModelCacheService.kt` and `ExpectationV6Controller.kt` (still using enum constants)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/UrgentReadStatus.kt
git commit -m "refactor(rest): UrgentReadState sealed class with behavior (#959)"
```

---

## Task 2: Update ReadModelCacheService.status() to build subtype directly

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt`

- [ ] **Step 1: Replace `status()` method body**

Find:
```kotlin
fun status(userIgn: String, presetNo: Int): UrgentReadStatusResponse {
    val state = when {
        hasReadyCache(userIgn, presetNo) -> UrgentReadState.READY
        getNegativeCache(userIgn) -> UrgentReadState.NOT_FOUND
        isUrgentPending(userIgn) -> UrgentReadState.PENDING
        else -> UrgentReadState.UNKNOWN
    }
    val position = if (state == UrgentReadState.PENDING) queuePosition(userIgn) else null
    return UrgentReadStatusResponse(
        state = state,
        userIgn = userIgn,
        statusUrl = statusUrl(userIgn, presetNo),
        queuePositionApprox = position,
        estimatedWaitSeconds = position?.let(::estimateWaitSeconds),
        retryAfterSeconds = if (state == UrgentReadState.READY || state == UrgentReadState.NOT_FOUND) 0 else properties.statusRetryAfterSeconds,
    )
}
```

Replace with:
```kotlin
fun status(userIgn: String, presetNo: Int): UrgentReadStatusResponse {
    val state: UrgentReadState = when {
        hasReadyCache(userIgn, presetNo) -> UrgentReadState.Ready
        getNegativeCache(userIgn) -> UrgentReadState.NotFound
        isUrgentPending(userIgn) -> {
            val pos = queuePosition(userIgn)
            UrgentReadState.Pending(
                queuePositionApprox = pos,
                estimatedWaitSeconds = pos?.let(::estimateWaitSeconds),
            )
        }
        else -> UrgentReadState.Unknown
    }
    return UrgentReadStatusResponse(
        state = state,
        userIgn = userIgn,
        statusUrl = statusUrl(userIgn, presetNo),
        queuePositionApprox = state.queuePositionApprox,
        estimatedWaitSeconds = state.estimatedWaitSeconds,
        retryAfterSeconds = state.retryAfterSeconds(properties.statusRetryAfterSeconds),
    )
}
```

- [ ] **Step 2: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-rest-controller:compileKotlin --continue`
Expected: FAIL only in `ExpectationV6Controller.kt` (still using enum comparison)

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/read/ReadModelCacheService.kt
git commit -m "refactor(rest): ReadModelCacheService builds UrgentReadState subtype directly (#959)"
```

---

## Task 3: Update ExpectationV6Controller to use shouldTryDb

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt`

- [ ] **Step 1: Replace enum comparison in getStatus()**

Find:
```kotlin
val status = if (current.state == UrgentReadState.PENDING || current.state == UrgentReadState.UNKNOWN) {
```
Replace with:
```kotlin
val status = if (current.state.shouldTryDb()) {
```

- [ ] **Step 2: Compile**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-rest-controller:compileKotlin --continue`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/main/kotlin/maple/restcontroller/controller/ExpectationV6Controller.kt
git commit -m "refactor(rest): ExpectationV6Controller uses shouldTryDb instead of enum comparison (#959)"
```

---

## Task 4: Create UrgentReadState unit tests

**Files:**
- Create: `module-rest-controller/src/test/kotlin/maple/restcontroller/read/UrgentReadStateTest.kt`

- [ ] **Step 1: Write the test file**

```kotlin
package maple.restcontroller.read

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UrgentReadStateTest {

    @Test
    fun `fromName round-trips all four names`() {
        assertThat(UrgentReadState.fromName("READY")).isEqualTo(UrgentReadState.Ready)
        assertThat(UrgentReadState.fromName("NOT_FOUND")).isEqualTo(UrgentReadState.NotFound)
        assertThat(UrgentReadState.fromName("PENDING")).isEqualTo(UrgentReadState.Pending(null, null))
        assertThat(UrgentReadState.fromName("UNKNOWN")).isEqualTo(UrgentReadState.Unknown)
    }

    @Test
    fun `fromName throws IllegalArgumentException on unknown value`() {
        val ex = assertThrows<IllegalArgumentException> {
            UrgentReadState.fromName("BOGUS")
        }
        assertThat(ex.message).contains("BOGUS")
    }

    @Test
    fun `Ready returns 0 retry-after and does not try DB`() {
        assertThat(UrgentReadState.Ready.retryAfterSeconds(configDefault = 30L)).isEqualTo(0L)
        assertThat(UrgentReadState.Ready.shouldTryDb()).isFalse()
    }

    @Test
    fun `NotFound returns 0 retry-after and does not try DB`() {
        assertThat(UrgentReadState.NotFound.retryAfterSeconds(configDefault = 30L)).isEqualTo(0L)
        assertThat(UrgentReadState.NotFound.shouldTryDb()).isFalse()
    }

    @Test
    fun `Pending returns config retry-after and tries DB`() {
        val pending = UrgentReadState.Pending(queuePositionApprox = 5L, estimatedWaitSeconds = 30L)
        assertThat(pending.retryAfterSeconds(configDefault = 10L)).isEqualTo(10L)
        assertThat(pending.shouldTryDb()).isTrue()
        assertThat(pending.queuePositionApprox).isEqualTo(5L)
        assertThat(pending.estimatedWaitSeconds).isEqualTo(30L)
    }

    @Test
    fun `Pending with null position has null position fields`() {
        val pending = UrgentReadState.Pending(queuePositionApprox = null, estimatedWaitSeconds = null)
        assertThat(pending.queuePositionApprox).isNull()
        assertThat(pending.estimatedWaitSeconds).isNull()
        assertThat(pending.shouldTryDb()).isTrue()
    }

    @Test
    fun `Unknown returns config retry-after and tries DB`() {
        assertThat(UrgentReadState.Unknown.retryAfterSeconds(configDefault = 30L)).isEqualTo(30L)
        assertThat(UrgentReadState.Unknown.shouldTryDb()).isTrue()
    }

    @Test
    fun `name property on each subtype matches NAME constant`() {
        assertThat(UrgentReadState.Ready.name).isEqualTo("READY")
        assertThat(UrgentReadState.NotFound.name).isEqualTo("NOT_FOUND")
        assertThat(UrgentReadState.Pending(null, null).name).isEqualTo("PENDING")
        assertThat(UrgentReadState.Unknown.name).isEqualTo("UNKNOWN")
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-rest-controller:test --tests "maple.restcontroller.read.UrgentReadStateTest"`
Expected: 8 tests, all PASS

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-rest-controller/src/test/kotlin/maple/restcontroller/read/UrgentReadStateTest.kt
git commit -m "test(rest): UrgentReadState sealed class behavior tests (#959)"
```

---

## Task 5: Final verification

- [ ] **Step 1: Run rest-controller tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-rest-controller:test`
Expected: PASS

- [ ] **Step 2: Full compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: SUCCESS

- [ ] **Step 3: Search for stale enum references**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -rn "UrgentReadState\.\(PENDING\|READY\|NOT_FOUND\|UNKNOWN\)" --include="*.kt" .`
Expected: empty output

- [ ] **Step 4: Push and open PR**

```bash
cd /home/maple/probabilistic-valuation-engine
git push origin HEAD
gh pr create --base develop --title "refactor(rest): UrgentReadState sealed class with behavior (#959)" --body "Implements #959. Sealed class hierarchy replaces enum; behavior methods on subtypes; JSON contract preserved."
```
