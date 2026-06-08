# Design: Magic numbers to named constants (#1093)

- Status: Accepted
- Date: 2026-06-06
- Owner: synchronizer / calculator / external-api / rest-controller

## 1. Background / Problem

### Background

Issue #1093 catalogs ~25 raw numeric literals across 4 modules that obscure intent and require mental arithmetic. Examples: `5368709120` (5 GB), `134217728L` (128 MB), `3600000` (1 hour ms), `header.substring(7)` (Bearer prefix).

Some of these are dead-on-arrival: the issue body refers to `CalculatorChunkProcessingCoordinator: Semaphore(2)` and "see issue #4" — that link is gone, and the value is intentional (matches HikariCP). Others are already partially constant-ized in this codebase (`JdbcBatchRetryConfig` has `Duration.ofMillis(100)` and `RetryConfig` in `RateLimitProperties` uses `Duration.ofSeconds(6)`). The work is to push remaining sites to a consistent pattern.

### Problem

Reading `ArtifactCleanupScheduler.kt:27` — `@Value(... max-delete-bytes-per-cycle:5368709120)` — requires the reader to convert 5368709120 to 5 GB. The codebase already uses self-documenting patterns (`Duration.ofSeconds(10)`, `RateLimitProperties.refillPeriod: Duration = Duration.ofSeconds(6)`), so the remaining raw literals are an inconsistency.

### Goal

Eliminate raw byte/time/count literals in the listed files. No behavioral change. Existing typed wrappers (`Duration`, `ByteSize` utilities) are preferred over `Int`/`Long` constants where possible.

## 2. Decision

> Three-tier constant strategy: in-file `private const val` for module-local values; module-internal `object` constants for cross-file within a module; no new shared module-common constants unless a value is genuinely cross-module.

### Strategy by value type

**Bytes** — Use `5L * 1024 * 1024 * 1024` style self-documenting expression, or `private const val` with `_L` suffix and a KDoc comment. Do **not** introduce a `ByteSize` value class — YAGNI, two sites in two files does not justify a shared type.

**Durations** — Use the existing `Duration` type. Replace `3600000L` with `Duration.ofHours(1).toMillis()` where the surrounding code is typed `Duration`. Where the literal lives in a Spring annotation (`@Scheduled(fixedDelayString = "...")`, `@Value`), keep the raw number but add a `private const val` with the converted value and reference it from the annotation. (Spring annotations cannot reference `const val` properties, but can reference `@Value("${property:default}")` where `default` is a build-time substitution — out of scope here.)

**Counts / thresholds** — Use `private const val` with a `KDoc` explaining rationale (e.g. why 5000 not 1000).

**HTTP prefix length** — `header.substring(7)` → introduce `private const val BEARER_PREFIX = "Bearer "` and `header.substring(BEARER_PREFIX.length)`.

**`Integer.MAX_VALUE - 100`** — already self-documenting as a phase ordering sentinel; add a comment instead of a constant.

### File-by-file change set

#### module-external-api

| File | Change |
| --- | --- |
| `ArtifactCleanupScheduler.kt:27` | Default value `:5368709120` → `:5L * 1024 * 1024 * 1024` in annotation. Add comment: `// 5 GB` |
| `SnapshotChunkingProperties.kt:18` | `134217728L` → `128L * 1024 * 1024` with KDoc `// 128 MB` |
| `ConsumedChunkCleanupScheduler.kt:62` | Default `:3600000` → `:Duration.ofHours(1).toMillis()` (or keep raw + add `private const val` reference) |
| `ExternalApiScheduler.kt:67` | `3_600_000` → extract `private const val LOCK_TIMEOUT_MS = 3_600_000L // 1 hour`; call site `acquireLock("daily_refresh", LOCK_TIMEOUT_MS)` |
| `NexonExternalApiClientAdapter.kt:123` | `Duration.ofSeconds(10)` → `Duration.ofSeconds(API_TIMEOUT_SECONDS)` with `private const val API_TIMEOUT_SECONDS = 10L` + KDoc |
| `SchedulerPhaseUtils.kt:34` | `delay(100)` → extract `private const val REFILL_BACKOFF_MS = 100L` (or keep inline + comment) |
| `SnapshotFetchPhase.kt:213,256` | `5000`, `500`, `100` → `private const val` with KDoc |
| `OcidLookupPhase.kt:137` | `5000` → shared const in `SchedulerPhaseUtils` object or local |
| `RankingFetchPhase.kt:112` | `10000` → `private const val` |
| `ChunkedSnapshotSink.kt:83` | `100, TimeUnit.MILLISECONDS` → `private const val ENQUEUE_TIMEOUT_MS = 100L` |

#### module-calculator

| File | Change |
| --- | --- |
| `CalculatorCleanupProperties.kt:10` | `5368709120` → `5L * 1024 * 1024 * 1024` with KDoc `// 5 GB` |
| `CalculatorResultCleanupScheduler.kt:69` | `1800` → `Duration.ofMinutes(30).toSeconds()` |
| `CalculationCache.kt:100_000` | Add `private const val CACHE_MAX_ENTRIES = 100_000` + KDoc explaining sizing |
| `SnapshotChunkProcessor.kt:10` | Add `private const val SAMPLE_LIMIT = 10` + KDoc |
| `CalculatorChunkProcessingCoordinator.kt:27` | `Semaphore(2)` → `private const val CONCURRENCY_PERMITS = 2` + KDoc referencing HikariCP sizing |

#### module-synchronizer

| File | Change |
| --- | --- |
| `CharacterBasicRepository.kt:14` + `EquipmentReadModelRepository.kt:17` | Both define `SUB_BATCH_SIZE = 100`. Extract to shared `object SynchronizerBatchConstants { const val SUB_BATCH_SIZE = 100 }` in same package |
| `BasicChunkFileReader.kt:44` | `DEFAULT_BATCH_SIZE = 1000` — already named; keep |
| `ChunkExecutionProperties` defaults | Add KDoc to existing defaults: `600`, `60`, `5`, `2` |

#### module-rest-controller

| File | Change |
| --- | --- |
| `JwtAuthInterceptor.kt:62` | `header.substring(7)` → `header.substring(BEARER_PREFIX.length)` with `private const val BEARER_PREFIX = "Bearer "` |
| `JwtParserAdapter.kt:3600` | `3600` → `Duration.ofHours(1).toSeconds()` |
| `BatchReadScheduler.kt:147` | `Integer.MAX_VALUE - 100` → `private const val BATCH_READ_SCHEDULER_PHASE = Integer.MAX_VALUE - 100` + KDoc explaining ordering convention |

#### Cross-module (decline)

| Item | Decision |
| --- | --- |
| `1024 * 1024` bytes→MB | Appears in 4 files (2 prod, 1 config, 1 collector). **Decline to extract.** The expression is universally understood; a `BYTES_PER_MB` constant in module-common adds an import for trivial clarity. The 3-byte repeated pattern is acceptable. |
| `128 * 1024 * 1024` (maxUncompressedBytes) | Single site. Inline expression is self-documenting. |

## 3. Trade-offs

### Sensitivity

- Annotation defaults (`@Value("...:5368709120")`): must keep numeric literal. Replacing with `5L * 1024 * 1024 * 1024` inside `@Value` string is allowed by Spring SpEL but the surrounding quotes make it ambiguous. **Approach: keep numeric in annotation, add a `private const val` separately, add KDoc on the field.** Two references, one source of truth.
- `Semaphore(2)` is intentional sizing, not a magic number. Add a KDoc, not a constant.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| In-file `private const val` + KDoc for each site | No new types, no cross-module coupling, minimal diff | Some repetition across modules (e.g. `5L * 1024 * 1024 * 1024` in two files) |
| Shared `ByteSize` value class in module-common | One source of truth, type-safe arithmetic | Indirection, requires changing all sites to wrap/unwrap, overkill for 2 sites |
| Declined to extract | No diff to small working patterns (1024*1024) | — |

### Risk

- **Annotation default value change** (`@Scheduled(fixedDelayString = "...":3600000)` → `...:Duration.ofHours(1).toMillis()`): the annotation is a string. Spring's `fixedDelayString` accepts a `String` of the numeric value, not a `Duration` expression. **Must keep the raw number in the annotation; extract to a `private const val` and add a comment that the annotation string mirrors the constant.** This is the only risk site.
- Test reliance on exact ms value: search tests for `3600000` / `3600001` literals. None found in production code paths.

### Non-Risk

- `1024 * 1024` repeated 4 times — declining to extract is a deliberate YAGNI choice, not a missing cleanup.
- `Semaphore(2)` is a sizing decision, not a magic number — KDoc only.

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| Files touched | ~15 | Across 4 modules |
| Raw `5368709120` literals remaining | 0 | Verify: `rg "5368709120" -g "*.kt"` |
| Raw `134217728` literals remaining | 0 | Verify: `rg "134217728" -g "*.kt"` |
| Raw `3600000` literals in production code | 0 | Verify: `rg "3600000" -g "*.kt" -g '!*Test.kt'` |
| `header.substring(7)` remaining in rest-controller | 0 | Verify: `rg "substring\(7\)" -g "*.kt" module-rest-controller/` |
| Behavioral diff | none | All values preserved exactly |

### Observed Result

After merging:
- Each raw byte literal is either an inline self-documenting expression (`5L * 1024 * 1024 * 1024`) or a `private const val` with a KDoc explaining intent.
- Each duration annotation default is paired with a `private const val` for reference.
- `CharacterBasicRepository.SUB_BATCH_SIZE` and `EquipmentReadModelRepository.SUB_BATCH_SIZE` share a single `SynchronizerBatchConstants.SUB_BATCH_SIZE`.
- `JwtAuthInterceptor` uses `BEARER_PREFIX.length` instead of `7`.
- `BatchReadScheduler.getPhase()` returns a named constant.
- `./gradlew compileKotlin compileJava --continue` passes for all 4 modules.
- `./gradlew test` passes for all 4 modules.

## 5. Summary

> Push the remaining ~25 raw numeric literals in #1093 to in-file `private const val` with KDoc, or to self-documenting expressions like `5L * 1024 * 1024 * 1024`. Keep existing typed patterns (`Duration`, `Semaphore(2)` with KDoc). Decline to extract `1024 * 1024` (too small to abstract) and decline `ByteSize` value class (YAGNI for 2 sites).
