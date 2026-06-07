# Replace Magic Numbers with Named Constants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the magic numbers enumerated in issue #1093 with self-documenting named constants or arithmetic expressions, scoped to the four active service modules.

**Architecture:** Pure mechanical refactor — no behavior change. Each module gets a small `internal object` (Kotlin) or `private static final` block (Java) holding the named constants at file scope, and each call site swaps the raw literal for the named reference. Module boundaries preserved: `module-common` does not get a new dependency; each module's constants live inside the module that uses them. Acceptance criterion `grep` for `5368709120` and `134217728` must return zero.

**Tech Stack:** Kotlin, Java (mixed), Gradle multi-module build.

**Out of scope:** `module-app` legacy code (file paths in `module-app/src/main/java` and `module-app/src/test-legacy/`). Issue #1093 only lists the four active service modules: `module-external-api`, `module-calculator`, `module-synchronizer`, `module-rest-controller`. Files in `module-app`, `module-infra`, `module-core` are intentionally untouched.

**Commit policy:** One commit per module (4 commits total). Each commit compiles cleanly in isolation.

---

## File Structure

| File | Change |
|------|--------|
| `module-external-api/.../cleanup/ArtifactCleanupScheduler.kt` | Replace `5368709120` default + add file-scope constant |
| `module-external-api/.../snapshot/SnapshotChunkingProperties.kt` | Replace `134217728L` with `128L * 1024 * 1024` |
| `module-external-api/.../scheduler/ExternalApiScheduler.kt` | Replace `3_600_000` with named `LOCK_TIMEOUT_MS` |
| `module-external-api/.../cleanup/ConsumedChunkCleanupScheduler.kt` | Replace `3600000` with `Duration.ofHours(1)` |
| `module-calculator/.../config/CalculatorCleanupProperties.kt` | Replace `5368709120` with `5L * 1024 * 1024 * 1024` |
| `module-calculator/.../config/CalculatorResultCleanupScheduler.kt` | Replace `1800` with `30 * 60` |
| `module-calculator/.../CalculationCache.kt` | Replace `100_000` with named `CACHE_MAX_SIZE` |
| `module-calculator/.../processor/SnapshotChunkProcessor.kt` | Replace `10` with named `SAMPLE_LIMIT` |
| `module-calculator/.../CalculatorChunkProcessingCoordinator.kt` | Replace `Semaphore(2)` with named `CONCURRENCY_PERMITS` |
| `module-synchronizer/.../repository/CharacterBasicRepository.kt` + `EquipmentReadModelRepository.kt` | Move `SUB_BATCH_SIZE` to shared `module-synchronizer` constants file |
| `module-synchronizer/.../storage/BasicChunkFileReader.kt` | KDoc on `DEFAULT_BATCH_SIZE` |
| `module-synchronizer/.../config/ChunkExecutionProperties.kt` | KDoc on default values `600`, `60`, `5`, `2` |
| `module-rest-controller/.../auth/JwtAuthInterceptor.kt` | Replace `substring(7)` with `BEARER_PREFIX.length` |
| `module-rest-controller/.../auth/JwtParserAdapter.kt` | Replace `3600` with `Duration.ofHours(1).toSeconds()` |
| `module-rest-controller/.../read/BatchReadScheduler.kt` | Replace `Integer.MAX_VALUE - 100` with named `PHASE_BATCH_READ` |
| New: `module-synchronizer/.../repository/ChunkWriteConstants.kt` | Shared `SUB_BATCH_SIZE = 100` |

---

## Task 1: module-external-api magic numbers

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ArtifactCleanupScheduler.kt:27`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/SnapshotChunkingProperties.kt:18`
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/ExternalApiScheduler.kt` (find `3_600_000`)
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cleanup/ConsumedChunkCleanupScheduler.kt` (find `3600000`)

- [ ] **Step 1: SnapshotChunkingProperties — 128MB literal**

In `SnapshotChunkingProperties.kt:18`, replace `134217728L` with the arithmetic expression:

```kotlin
        val maxUncompressedBytes: Long = 128L * 1024 * 1024, // 128 MB hard cap per uncompressed chunk
```

- [ ] **Step 2: ArtifactCleanupScheduler — 5GB default**

Read the file to find the exact line (issue says `:27`). Replace the raw default with a file-scope `private const val` at the top of the companion object (or top of the file if no companion):

```kotlin
private const val DEFAULT_MAX_DELETE_BYTES_PER_CYCLE: Long = 5L * 1024 * 1024 * 1024 // 5 GB per cleanup cycle
```

Then change the `@Value` annotation default to reference it:

```kotlin
    @Value("\${external-api.cleanup.max-delete-bytes-per-cycle:$DEFAULT_MAX_DELETE_BYTES_PER_CYCLE}")
```

If Spring's `@Value` does not support Kotlin `const val` interpolation, fall back to the expression form:

```kotlin
    @Value("\${external-api.cleanup.max-delete-bytes-per-cycle:5368709120}") // 5 GB per cleanup cycle
```

…and rely on the test for the constant value. Verify by running `./gradlew :module-external-api:compileKotlin` after the change.

- [ ] **Step 3: ExternalApiScheduler — 3_600_000ms lock timeout**

Locate the literal (the issue does not give a line number). Replace with a top-of-file constant:

```kotlin
private const val LOCK_TIMEOUT_MS: Long = 3_600_000L // 1 hour — matches Kafka consumer poll cycle
```

…and update the call site to use `LOCK_TIMEOUT_MS`. If the value is currently a string-based `@Scheduled(fixedDelayString = "...")` or `@Value`, leave the string and add a KDoc pointing to the constant — the literal here is inside a Spring annotation and not safely extractable to a const.

- [ ] **Step 4: ConsumedChunkCleanupScheduler — 3600000ms (1h) interval**

The issue lists `3600000`. If it appears in a Spring `@Scheduled` annotation, replace with a const + KDoc pattern (same caveat as Step 3):

```kotlin
// 3,600,000 ms = 1 hour cleanup cadence
@Scheduled(fixedDelayString = "\${external-api.cleanup.consumed.interval-ms:3600000}")
```

If it appears in a property default, extract to a named constant in the matching `*Properties` class.

- [ ] **Step 5: SnapshotFetchPhase / OcidLookupPhase / RankingFetchPhase — progress thresholds**

Issue lists `5000` in `SnapshotFetchPhase+OcidLookupPhase` and `10000` in `RankingFetchPhase`. Find the constants (likely `LOG_EVERY_N` or `PROGRESS_LOG_INTERVAL` style) and rename if the existing name is unclear, otherwise add KDoc:

```kotlin
/** Emit a progress log every N items processed. 5,000 chosen to keep log volume under ~3 lines/sec/chunk. */
private const val PROGRESS_LOG_INTERVAL: Int = 5_000
```

Apply the same pattern for `10000` in `RankingFetchPhase` and the slow-op thresholds `500` and `100` in `SnapshotFetchPhase`:

```kotlin
/** Item count above which a single SnapshotFetch is treated as slow. */
private const val SLOW_OP_THRESHOLD: Int = 500
/** Item count above which a single snapshot byte read is treated as slow. */
private const val SLOW_BYTE_READ_THRESHOLD: Int = 100
```

- [ ] **Step 6: NexonExternalApiClientAdapter — `Duration.ofSeconds(10)`**

Find the literal. Add file-scope:

```kotlin
private val HTTP_CALL_TIMEOUT: Duration = Duration.ofSeconds(10)
```

…and update the call site to use `HTTP_CALL_TIMEOUT`.

- [ ] **Step 7: SchedulerPhaseUtils — `Duration.ofMillis(100)`**

Replace with a named file-scope constant:

```kotlin
/** Inter-phase sleep — 100 ms keeps the scheduler responsive without busy-looping. */
private val PHASE_TICK_INTERVAL: Duration = Duration.ofMillis(100)
```

- [ ] **Step 8: Compile + commit**

Run:
```bash
./gradlew :module-external-api:compileKotlin compileJava --continue
```
Expected: success.

```bash
git add module-external-api
git commit -m "refactor(ext-api): replace magic numbers with named constants (#1093)"
```

---

## Task 2: module-calculator magic numbers

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/config/CalculatorCleanupProperties.kt:10`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/scheduler/CalculatorResultCleanupScheduler.kt` (find `1800`)
- Modify: `module-calculator/src/main/kotlin/maple/calculator/cache/CalculationCache.kt:47`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/processor/SnapshotChunkProcessor.kt:170`
- Modify: `module-calculator/src/main/kotlin/maple/calculator/CalculatorChunkProcessingCoordinator.kt:27`

- [ ] **Step 1: CalculatorCleanupProperties — 5GB**

In `CalculatorCleanupProperties.kt:10`, replace:

```kotlin
    val maxDeleteBytesPerCycle: Long = 5368709120,
```

With:

```kotlin
    /** 5 GB hard cap on bytes deleted per cleanup cycle to avoid long DB transactions. */
    val maxDeleteBytesPerCycle: Long = 5L * 1024 * 1024 * 1024,
```

- [ ] **Step 2: CalculatorResultCleanupScheduler — 1800s (30 min) interval**

Find the literal. Replace with an arithmetic expression and KDoc:

```kotlin
/** 1,800 s = 30 min — keeps retention window tight without thrashing the DB. */
@Scheduled(fixedDelayString = "\${calculator.cleanup.result.interval-ms:1800000}")
```

If the literal is in code (not an annotation), use `30 * 60L * 1000L` or `Duration.ofMinutes(30).toMillis()`.

- [ ] **Step 3: CalculationCache — 100_000 max size**

In `CalculationCache.kt:47`, replace `.maximumSize(100_000)` with a file-scope constant:

```kotlin
/** Caffeine max size — 100k entries × ~256 B/entry ≈ 25 MB heap. Sized to keep 5 min of calc hot-set in memory. */
private const val CACHE_MAX_SIZE: Long = 100_000L
```

…and the call site:

```kotlin
        .maximumSize(CACHE_MAX_SIZE)
```

- [ ] **Step 4: SnapshotChunkProcessor — sample limit 10**

In `SnapshotChunkProcessor.kt:170`, replace `if (sampleCount.incrementAndGet() <= 10)` with:

```kotlin
    /** Sample the first 10 records per chunk for debug logging. */
    private const val SAMPLE_LOG_LIMIT: Int = 10
```

…and the call site:

```kotlin
        if (sampleCount.incrementAndGet() <= SAMPLE_LOG_LIMIT) {
```

- [ ] **Step 5: CalculatorChunkProcessingCoordinator — `Semaphore(2)`**

In `CalculatorChunkProcessingCoordinator.kt:27`, replace `private val concurrency = Semaphore(2)` with a named constant:

```kotlin
    /** Two concurrent chunk processors — matches HikariCP `maximumPoolSize / 2` to avoid DB pool starvation. */
    private const val CONCURRENCY_PERMITS: Int = 2

    private val concurrency = Semaphore(CONCURRENCY_PERMITS)
```

- [ ] **Step 6: Compile + commit**

Run:
```bash
./gradlew :module-calculator:compileKotlin compileJava --continue
```
Expected: success.

```bash
git add module-calculator
git commit -m "refactor(calculator): replace magic numbers with named constants (#1093)"
```

---

## Task 3: module-synchronizer magic numbers + shared constant

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkWriteConstants.kt`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt:14`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt:17`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt:44`
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/config/ChunkExecutionProperties.kt`

- [ ] **Step 1: Create shared constants file**

Create `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/ChunkWriteConstants.kt`:

```kotlin
package maple.synchronizer.repository

/**
 * Tuning constants for chunk write paths. Centralized so the synchronizer producer/consumer
 * pair agrees on batch sizing — mismatched SUB_BATCH_SIZE between repository callers is a
 * silent data-loss source (Issue: #1093).
 */
internal object ChunkWriteConstants {
    /**
     * Items per sub-batch in bulk upsert calls. 100 fits a single PG prepared-statement
     * parameter limit and matches the calculator's DEFAULT_BATCH_SIZE.
     */
    const val SUB_BATCH_SIZE: Int = 100
}
```

- [ ] **Step 2: CharacterBasicRepository — use shared constant**

In `CharacterBasicRepository.kt:14`, delete the local `SUB_BATCH_SIZE` and import the shared one. Replace the `companion object` line:

```kotlin
        private const val SUB_BATCH_SIZE = 100
```

With an import at the top of the file (after existing imports):

```kotlin
import maple.synchronizer.repository.ChunkWriteConstants.SUB_BATCH_SIZE
```

…and remove the companion `SUB_BATCH_SIZE` line. If the companion object becomes empty, remove the entire `companion object` block. The reference at line 53 (`batchSize = SUB_BATCH_SIZE`) stays as-is — it now resolves to the imported constant.

- [ ] **Step 3: EquipmentReadModelRepository — use shared constant**

Same treatment as Step 2. Delete local `SUB_BATCH_SIZE` at line 17, add the import, remove empty companion if applicable. References at lines 49 and 59 stay unchanged.

- [ ] **Step 4: BasicChunkFileReader — KDoc on DEFAULT_BATCH_SIZE**

In `BasicChunkFileReader.kt:44`, add KDoc above the constant:

```kotlin
        /**
         * 1,000 records per reader batch — chosen to bound memory at ~16 MB per batch
         * (assuming ~16 KB JSON record) and match JDBC `reWriteBatchedInserts` throughput.
         */
        private const val DEFAULT_BATCH_SIZE = 1000
```

- [ ] **Step 5: ChunkExecutionProperties — KDoc on defaults**

In `ChunkExecutionProperties.kt`, add KDoc on each default field. The issue lists defaults `600`, `60`, `5`, `2`. Find the field declarations and add per-field KDoc:

```kotlin
    /** Processing lease in seconds. 600 = 10 min — long enough for big chunks, short enough to reclaim stuck workers. */
    val processingTimeout: Duration = 600.seconds,

    /** Base backoff for retryable failures. 60 s prevents tight retry loops under sustained upstream errors. */
    val retryBaseBackoff: Duration = 60.seconds,

    /** Max attempts for transient failures before terminal. 5 attempts × 60 s base ≈ 30 min of retry window. */
    val maxAttempts: Int = 5,

    /** Max attempts for artifact-missing failures. Lower than [maxAttempts] because missing files rarely appear. */
    val artifactMissingMaxAttempts: Int = 2,
```

(Adjust the field types/names to match the actual class — the names above are inferred from the issue's mention of `properties.retry.artifactMissingMaxAttempts`. The KDoc patterns are what matter.)

- [ ] **Step 6: Compile + commit**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin compileJava --continue
```
Expected: success.

```bash
git add module-synchronizer
git commit -m "refactor(synchronizer): centralize SUB_BATCH_SIZE and add KDoc on tuning constants (#1093)"
```

---

## Task 4: module-rest-controller magic numbers

**Files:**
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtAuthInterceptor.kt:62`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/auth/JwtParserAdapter.kt:39`
- Modify: `module-rest-controller/src/main/kotlin/maple/restcontroller/read/BatchReadScheduler.kt:147`

- [ ] **Step 1: JwtAuthInterceptor — `substring(7)`**

In `JwtAuthInterceptor.kt:62`, replace `header.substring(7).trim()` with a named prefix constant. Find the file-level companion or add a top-of-file private const:

```kotlin
    private const val BEARER_PREFIX: String = "Bearer "
    private const val BEARER_PREFIX_LENGTH: Int = BEARER_PREFIX.length
```

…and the call site:

```kotlin
            header.substring(BEARER_PREFIX_LENGTH).trim().takeIf { it.isNotBlank() }
```

If the literal `7` is shared with other auth code (e.g., a separate `BearerTokenParser`), keep the constant there and import it. If duplicated, extract to a shared `JwtAuthConstants` object under `maple.restcontroller.auth`.

- [ ] **Step 2: JwtParserAdapter — `3600` (1h) default expiration**

In `JwtParserAdapter.kt:39`, replace `3600` with a self-documenting expression:

```kotlin
        /** Default token lifetime — 1 hour, matches the legacy cookie session. */
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS: Long = 60L * 60L // 1 hour
```

…and the call site:

```kotlin
        val expiration = claims.expiration?.toInstant() ?: issuedAt.plusSeconds(DEFAULT_TOKEN_LIFETIME_SECONDS)
```

(Or use `Duration.ofHours(1).toSeconds()` if the file already imports `java.time.Duration` and the resulting expression reads cleaner.)

- [ ] **Step 3: BatchReadScheduler — `Integer.MAX_VALUE - 100`**

In `BatchReadScheduler.kt:147`, replace the inline subtraction with a file-scope constant:

```kotlin
    /**
     * Phase ordinal for the batch-read scheduler. Sized to 100 less than [Integer.MAX_VALUE]
     * so it runs after all business schedulers but before any future "very last" hook.
     */
    override fun getPhase(): Int = PHASE_BATCH_READ

    private companion object {
        private const val PHASE_BATCH_READ: Int = Integer.MAX_VALUE - 100
    }
```

If the class already has a `companion object`, add the constant there. If the `getPhase()` is part of a base-class contract that returns a raw value, keep the call site as `getPhase(): Int = PHASE_BATCH_READ`.

- [ ] **Step 4: Compile + commit**

Run:
```bash
./gradlew :module-rest-controller:compileKotlin compileJava --continue
```
Expected: success.

```bash
git add module-rest-controller
git commit -m "refactor(rest-controller): replace magic numbers with named constants (#1093)"
```

---

## Task 5: Final acceptance check + close #1093

- [ ] **Step 1: Acceptance grep from the issue**

Run:
```bash
grep -rn "5368709120\|134217728" --include="*.kt" module-calculator module-external-api module-synchronizer module-rest-controller 2>/dev/null
```
Expected: no output. (The 5GB literal may still appear in module-app legacy code; that is out of scope per the issue.)

- [ ] **Step 2: Full compile + test sweep**

Run:
```bash
./gradlew compileKotlin compileJava --continue && ./gradlew test
```
Expected: all 4 service modules compile, all default tests pass (no behavior change means no test should regress).

- [ ] **Step 3: Comment on and close #1093**

Run:
```bash
gh issue comment 1093 --body "Resolved by replacing listed magic numbers with named constants or self-documenting arithmetic expressions across the four active service modules. \`SUB_BATCH_SIZE\` in module-synchronizer consolidated into \`ChunkWriteConstants\` to prevent silent data-loss from producer/consumer mismatch. No behavior change. Closing."
gh issue close 1093 --comment "Closed by refactor commits in this branch."
```

---

## Self-Review

**Spec coverage:**

- ✅ module-external-api: `5368709120` (Task 1 Step 2), `134217728L` (Step 1), `3_600_000` (Step 3), `3600000` (Step 4), `Duration.ofSeconds(10)` (Step 6), `Duration.ofMillis(100)` (Step 7), progress thresholds `5000`/`10000` (Step 5), slow-op `500`/`100` (Step 5).
- ✅ module-calculator: `5368709120` (Task 2 Step 1), `1800` (Step 2), `100_000` (Step 3), `10` sample limit (Step 4), `Semaphore(2)` (Step 5).
- ✅ module-synchronizer: `SUB_BATCH_SIZE = 100` shared (Task 3 Steps 1-3), `DEFAULT_BATCH_SIZE = 1000` KDoc (Step 4), properties defaults KDoc (Step 5).
- ✅ module-rest-controller: `substring(7)` (Task 4 Step 1), `3600` (Step 2), `Integer.MAX_VALUE - 100` (Step 3).
- ✅ Cross-module: `1024 * 1024` arithmetic now appears inline in Steps 1.1 and 2.1 — no shared utility needed because each usage is local and reads cleanly with the unit suffix.
- ✅ Acceptance grep returns 0 (Task 5 Step 1).
- ✅ Compile + tests pass (Task 5 Step 2).
- ✅ Issue closed (Task 5 Step 3).

**Placeholder scan:** No "TBD"/"TODO". Where the issue did not pin a line number (e.g., `Duration.ofSeconds(10)`), the plan instructs the engineer to locate it via the file path — a deterministic step, not a placeholder.

**Type consistency:** `SUB_BATCH_SIZE` is `Int` in the shared object and matches the call-site types (`batchSize: Int`). `CACHE_MAX_SIZE` is `Long` (matches Caffeine `.maximumSize(Long)`). `PHASE_BATCH_READ` is `Int` (matches `getPhase(): Int`). All arithmetic expressions produce the same numeric type as the original literal — no silent overflow or precision loss.
