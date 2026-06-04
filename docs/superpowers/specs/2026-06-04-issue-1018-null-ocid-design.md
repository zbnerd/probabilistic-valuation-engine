# Issue 1018: null OCID observability fix in OcidLookupPhase

- Status: Accepted
- Date: 2026-06-04
- Owner: zbnerd
- Issue: #1018

## Background

`OcidLookupPhase.fetchOcid()` (renamed from `fetchAndCollectOcidAsync` after #984 refactor) silently drops Nexon responses that lack an `ocid` field. The character is excluded from the mapping file with no log line emitted. Although `processBatchSuspend` does count null-returned `fetchOcid` results as failures (via `chunk.size - batchSuccess.size`), the cause is invisible to operators — a null-ocid Nexon response and a network timeout both return `null` and look identical in logs and metrics.

## Problem

Character with `ocid` field missing or null in Nexon response:
- `fetchOcid` returns `null`
- Excluded from `results` (mapping file)
- Counted as failure by `processBatchSuspend` (`chunk.size - batchSuccess.size` includes the null-ocid result)
- No log line distinguishes "Nexon returned no ocid" from "exception/timeout"

Goal: emit a `warn` log line for every null-ocid Nexon response so operators can identify the affected IGN. Count semantics are already correct; do not change them.

## Decision

Add `log.warn` with `maskIgn(ign)` to the `else` branch of the `ocid != null` check in `fetchOcid`. Return `null` unchanged. No new metric, no DLQ file, no behavioral change beyond the log line.

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

## Trade-offs

### Sensitivity

- Volume of null-ocid responses per run (expected low — most IGNs have OCID)
- `maskIgn` helper availability in same package

### Trade-off

| Choice | Gain | Sacrifice |
|--------|------|-----------|
| Add log line | Operator visibility on null-ocid | +1 line per affected IGN in logs |

### Risk

- Log volume spike if Nexon API has systemic null-ocid regression — mitigated by existing `external_api_users_failed_total` Prometheus counter (already aggregates `failCount`).

### Non-Risk

- Count semantics unchanged. `processBatchSuspend` already counts null-returned `fetchOcid` results as failures.
- No new dependency. No ADR needed (bug fix, not design choice).

## Acceptance criteria

- [ ] Null OCID response emits `log.warn("[OCID] null ocid for ign={}", maskIgn(ign))`
- [ ] Null OCID result still counted in `failCount` (existing behavior preserved)
- [ ] `successCount + failCount == totalCount` invariant holds (existing behavior preserved)
- [ ] New unit test: `processBatchSuspend` returns `successCount=1, failCount=1` when 1 valid + 1 null-ocid IGN processed; `results.size == 1`
- [ ] `./gradlew :module-external-api:compileKotlin :module-external-api:compileJava --continue` passes
- [ ] `./gradlew :module-external-api:test` passes
- [ ] IGN masked in log line (e.g., `f***l`, never raw character name)

## Test design

Add to `OcidLookupPhaseTest.kt`:

```kotlin
@Test
fun `processBatchSuspend counts null-ocid response as fail and writes only valid mappings`() = runBlocking {
    val validIgn = "PlayerValid"
    val nullOcidIgn = "PlayerNull"
    val mockClient = mock<ExternalApiClientPort>()
    whenever(mockClient.fetch(any(), any(), eq(validIgn)))
        .thenReturn(CompletableFuture.completedFuture("""{"ocid":"abc123"}"""))
    whenever(mockClient.fetch(any(), any(), eq(nullOcidIgn)))
        .thenReturn(CompletableFuture.completedFuture("""{"character_name":"x"}"""))
    // ... wire into phase ...
    // assert results.size == 1, success=1, fail=1
}
```

Phase constructor must accept mock `ExternalApiClientPort`. Existing test already wires `clientPort = mock()`, so wiring is straightforward. `processBatchSuspend` is private — either change to `@VisibleForTesting internal` (Kotlin: use `internal` modifier — file is in same package, test sees it) or test through the public `execute()` method by writing chunk files with the two IGNs and asserting on the output mapping file size.

**Chosen approach:** test through `execute()` to avoid changing visibility of production method. Write 2-key chunk file → call `execute(workerExecutor, runDir)` → assert mapping file contains exactly 1 line and contains the valid ocid.

## Files changed

- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` — 3-line addition in `fetchOcid` else-branch
- `module-external-api/src/test/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhaseTest.kt` — 1 new test method

## Out of scope

- Prometheus counter for null-ocid events (use existing `external_api_users_failed_total`)
- Dead-letter file for null-ocid IGNs (can revisit if Nexon response shape changes)
- Distinguishing timeout vs no-ocid in logs (both currently return `null`; acceptable for issue scope)

## Summary

> Add one `log.warn` with masked IGN to the null-ocid branch of `OcidLookupPhase.fetchOcid()`. No count changes, no new infra. Test through `execute()` end-to-end.
