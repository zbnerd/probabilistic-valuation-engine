# Issue 1018: null OCID observability fix in OcidLookupPhase

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `log.warn` with masked IGN to `OcidLookupPhase.fetchOcid()` so operators can identify Nexon responses that lack an `ocid` field. Count semantics are already correct.

**Architecture:** Single-file production change (3 lines) in `fetchOcid` else-branch + one new test in `OcidLookupPhaseTest`. Test drives the public `execute()` end-to-end with mocked `ExternalApiClientPort` returning both a valid and a null-ocid response, asserts the mapping file contains exactly 1 line.

**Tech Stack:** Kotlin, kotlinx-coroutines, JUnit 5, mockito-kotlin, Jackson, AssertJ

---

## File Structure

### Files Modified

- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` — add `log.warn` + `maskIgn` import in `fetchOcid` else-branch
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` — add `@Test execute counts null-ocid as fail` test method, switch `clientPort` from inline `mock()` to `lateinit var` for stubbing

### Files Created

None.

---

## Task 1: Add failing test for null-ocid handling

**Files:**
- Modify: `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt`

- [ ] **Step 1: Refactor `setUp` to use `lateinit var clientPort` for stubbing**

Replace the current `setUp` body with one that declares `clientPort` as a class field (matching the pattern in `RankingFetchPhaseTest` lines 35, 42). Final shape:

```kotlin
class OcidLookupPhaseTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var objectMapper: ObjectMapper
    private lateinit var clientPort: ExternalApiClientPort
    private lateinit var phase: OcidLookupPhase
    private lateinit var executor: java.util.concurrent.ExecutorService

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        clientPort = mock()
        phase = OcidLookupPhase(
            clientPort = clientPort,
            objectMapper = objectMapper,
            ocidLookupPermitsPerSecond = 400,
            batchSize = 1000,
            storeBasePath = tempDir.resolve("store").toString(),
            eventPublisher = maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher(),
            maxInFlight = 100,
        )
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    }

    @AfterEach
    fun tearDown() {
        executor.close()
    }
```

Add the new imports at top of file (alphabetically placed):

```kotlin
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.port.out.ExternalApiClientPort
import org.mockito.kotlin.whenever
import java.util.concurrent.CompletableFuture
```

`ExternalApiClientPort` and `mock` imports already exist. Keep existing imports intact.

- [ ] **Step 2: Append the new test method**

Add after the two existing tests in `OcidLookupPhaseTest`:

```kotlin
@Test
fun `execute writes only valid ocids when one Nexon response lacks ocid field`() {
    val runDir = tempDir.resolve("runs").resolve("20260604-140000-001")
    val chunksDir = runDir.resolve("ranking-overall").resolve("chunks")
    writeGzipJsonl(chunksDir.resolve("part-000001.jsonl.gz"), listOf("PlayerValid", "PlayerNullOcid"))

    whenever(
        clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "PlayerValid"),
    ).thenReturn(CompletableFuture.completedFuture("""{"ocid":"abc123"}"""))
    whenever(
        clientPort.fetch(ExternalApiProvider.NEXON, ExternalApiEndpoint.OCID_LOOKUP, "PlayerNullOcid"),
    ).thenReturn(CompletableFuture.completedFuture("""{"character_name":"x"}"""))

    val mappingDir = tempDir.resolve("store").resolve("ocid-mapping")
    val outputPath = phase.execute(executor, runDir).get()

    assertThat(outputPath).isNotNull
    assertThat(Files.exists(outputPath!!)).isTrue

    val lines = GZIPInputStream(BufferedInputStream(Files.newInputStream(outputPath)))
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() }
    assertThat(lines).hasSize(1)
    assertThat(lines[0]).contains("\"ocid\":\"abc123\"").contains("\"userIgn\":\"PlayerValid\"")
}
```

Add imports (only new ones):

```kotlin
import maple.externalapi.domain.ExternalApiEndpoint
import maple.externalapi.domain.ExternalApiProvider
import maple.externalapi.snapshot.event.NoOpSnapshotChunkEventPublisher
import org.mockito.kotlin.whenever
import java.io.BufferedInputStream
import java.util.concurrent.CompletableFuture
import java.util.zip.GZIPInputStream
```

- [ ] **Step 3: Run the new test to confirm it fails**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest.execute writes only valid ocids when one Nexon response lacks ocid field"`

Expected: FAIL — the existing `fetchOcid` already returns `null` for null-ocid input, so `results` should contain only 1 entry and the count logic is correct. The test should actually PASS without any production change. If it passes, the bug is purely the missing log line and Task 2 is the only production work. Skip to Task 2.

If the test fails for a different reason (e.g., NPE on objectMapper.readTree with invalid input, or mapping file path missing), report the failure and adjust the test before continuing.

- [ ] **Step 4: Commit test scaffold**

```bash
git add module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt
git commit -m "test(external-api): add null-ocid end-to-end test for OcidLookupPhase

Mocked ExternalApiClientPort returns one valid and one null-ocid response.
Asserts mapping file contains only the valid entry, proving
processBatchSuspend already counts null-ocid as fail."
```

---

## Task 2: Add log.warn in fetchOcid else-branch

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` lines 149-163

- [ ] **Step 1: Add `maskIgn` import**

Add to the import block (alphabetical, after `java.util.zip.GZIPOutputStream`):

```kotlin
import maple.expectation.util.StringMaskingUtils.maskIgn
```

- [ ] **Step 2: Replace the `fetchOcid` body to add the warn log**

Current code (lines 149-163):

```kotlin
private suspend fun fetchOcid(ign: String): String? {
    return withTimeoutOrNull(10_000L) {
        semaphore.withPermit {
            val data = clientPort.fetch(
                ExternalApiProvider.NEXON,
                ExternalApiEndpoint.OCID_LOOKUP,
                ign,
            ).await()
            val ocid = objectMapper.readTree(data).get("ocid")?.asText()
            if (ocid != null) {
                String(objectMapper.writeValueAsBytes(mapOf("userIgn" to ign, "ocid" to ocid)))
            } else null
        }
    }
}
```

Replace with:

```kotlin
private suspend fun fetchOcid(ign: String): String? {
    return withTimeoutOrNull(10_000L) {
        semaphore.withPermit {
            val data = clientPort.fetch(
                ExternalApiProvider.NEXON,
                ExternalApiEndpoint.OCID_LOOKUP,
                ign,
            ).await()
            val ocid = objectMapper.readTree(data).get("ocid")?.asText()
            if (ocid != null) {
                String(objectMapper.writeValueAsBytes(mapOf("userIgn" to ign, "ocid" to ocid)))
            } else {
                log.warn("[OCID] null ocid for ign={}", maskIgn(ign))
                null
            }
        }
    }
}
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`

Expected: BUILD SUCCESSFUL. No warnings related to unused imports or unsafe casts.

- [ ] **Step 4: Run the new test to verify it passes**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest.execute writes only valid ocids when one Nexon response lacks ocid field"`

Expected: PASS. The test exercises both branches and confirms the null-ocid response is dropped from results.

- [ ] **Step 5: Run the full OcidLookupPhaseTest class**

Run: `./gradlew :module-external-api:test --tests "maple.externalapi.scheduler.phase.OcidLookupPhaseTest"`

Expected: All 3 tests pass (2 existing + 1 new).

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt
git commit -m "fix(external-api): log warn for null ocid in OcidLookupPhase (issue 1018)

Nexon responses without ocid field were silently dropped from both the
mapping file and successCount, with no log line to identify the affected
IGN. Count semantics were already correct via processBatchSuspend
null-return handling; only the log line was missing.

Add log.warn with maskIgn(ign) so operators can identify which characters
are missing ocid in Nexon's response."
```

---

## Task 3: Verify full module build and tests

- [ ] **Step 1: Run module compile with --continue**

Run: `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run module unit tests**

Run: `./gradlew :module-external-api:test`

Expected: BUILD SUCCESSFUL. All non-integration/pgmq/quarantine/flaky/sentinel tests pass.

- [ ] **Step 3: Final commit if any cleanup needed**

If step 1 or 2 surfaced formatting/lint issues fixed in this branch, commit them:

```bash
git add -A
git commit -m "style(external-api): apply lint fixes from full build"
```

Otherwise skip.

---

## Out of Scope

- No ADR (bug fix, not design decision)
- No Prometheus counter for null-ocid events (use existing `external_api_users_failed_total`)
- No dead-letter file for null-ocid IGNs
- No distinction between timeout and no-ocid in logs (both return `null`; not in issue scope)
- No changes to `processBatchSuspend` count logic (already correct)
