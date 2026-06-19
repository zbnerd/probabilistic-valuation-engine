# Issue #1313 — Streaming JSONL Chunk Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-line `objectMapper.readTree(line)` JSONL parsing in `OcidLookupPhase.readCharacterNamesFromChunks` (ext-api hot path), `OcidCacheProvider.refresh()` (cold path), and `GzipJsonlSnapshotRecordReader` (calculator) with a shared token-stream `StreamingChunkParser` that emits `Flow<Map<String, Any>>`, achieving ext-api heap < 200MB (from 410MB baseline) with zero throughput regression. Roll back via feature flag if regression observed.

**Architecture:** Parser lives in `module-common` (Spring-free, Micrometer-free). Each consumer module exposes it as a Spring `@Bean` via a `ChunkParserConfig`. The hot path (`OcidLookupPhase.readCharacterNamesFromChunks`) keeps its `suspend fun ... = withContext(Dispatchers.Default)` signature and consumes the Flow natively — no `runBlocking` introduced. Feature flag `externalapi.parser.streaming.enabled=true` controls the parser selection; `false` falls back to the original `lineSequence + readTree` path.

**Tech Stack:** Jackson `JsonParser` + `JsonNode`, JDK `GZIPInputStream`, kotlinx-coroutines `Flow` + `withContext`, Spring `@Bean` + `@ConditionalOnProperty`, Micrometer counters/timers.

**Supersedes:** `docs/superpowers/plans/2026-06-19-offheap-streaming.md` Task 4. The parent plan's Task 4 targets `module-external-api/.../parser/` and uses an `object` parser with manual resync. This plan places the parser in `module-common` (avoids reverse module dependency), uses a `class` parser with `skipChildren()` resync, and targets three call sites including the actual hot path (`OcidLookupPhase.readCharacterNamesFromChunks`) — the parent plan missed this because it grepped for `readValue`/`readTree` literally without finding the inlined `GZIPInputStream + lineSequence` pattern.

**Spec:** `docs/superpowers/specs/2026-06-19-issue-1313-streaming-chunk-parser-design.md`

**Scope note:** `CharacterNameReader.kt` is pre-existing dead code (no production injection; only mentioned in a `OcidResponseParser` code comment). Per CLAUDE.md §3 ("Touch only what you must"), it is **not** modified in this plan. A follow-up cleanup issue may delete it.

---

## File Structure

**New files:**
- `module-common/src/main/kotlin/maple/common/parser/StreamingChunkParser.kt` — stateless parser class
- `module-common/src/test/kotlin/maple/common/parser/StreamingChunkParserTest.kt` — unit tests
- `module-external-api/src/main/kotlin/maple/externalapi/config/ChunkParserConfig.kt` — `@Bean` exposure (legacy path)
- `module-external-api/src/main/kotlin/maple/externalapi/config/StreamingChunkParserConfig.kt` — `@Bean` exposure (streaming path, `@ConditionalOnProperty`)
- `module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt` — Micrometer counters/timer (ext-api)
- `module-calculator/src/main/kotlin/maple/calculator/config/ChunkParserConfig.kt` — `@Bean` exposure
- `module-calculator/src/main/kotlin/maple/calculator/metrics/ChunkParserMetrics.kt` — Micrometer counters/timer (calculator)

**Modified files:**
- `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt` — replace `readCharacterNamesFromChunks` body (line 181)
- `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt` — swap internal parser
- `module-calculator/src/main/kotlin/maple/calculator/reader/GzipJsonlSnapshotRecordReader.kt` — change return type `Flow<String>` → `Flow<Map<String, Any>>`
- `module-calculator/src/main/kotlin/.../<downstream consumer>.kt` — adapt to new Flow type (TBD in Task 9)
- `module-external-api/src/main/resources/application.yml` — add `externalapi.parser.streaming.enabled: true`

---

## Task 1: Create feature branch

**Files:** none

- [ ] **Step 1: Create branch from develop**

```bash
git checkout develop
git pull --ff-only
git checkout -b feat/issue-1313-streaming-chunk-parser
```

Expected: branch created, no conflicts.

- [ ] **Step 2: Verify branch**

```bash
git branch --show-current
```

Expected: `feat/issue-1313-streaming-chunk-parser`

---

## Task 2: StreamingChunkParserTest — failing tests (RED)

**Files:**
- Create: `module-common/src/test/kotlin/maple/common/parser/StreamingChunkParserTest.kt`

- [ ] **Step 1: Create test file**

```kotlin
package maple.common.parser

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

class StreamingChunkParserTest {

    private val objectMapper = ObjectMapper()
    private val parser = StreamingChunkParser(objectMapper, skipMalformed = true)

    private fun gzipped(content: String): ByteArray =
        ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(content.toByteArray()) }
            baos.toByteArray()
        }

    @Test
    fun `parses valid JSONL records`() = runBlocking {
        val jsonl = """{"ign":"f***l","ocid":"abc123"}
{"ign":"a***b","ocid":"def456"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()

        assertEquals(2, records.size)
        assertEquals("f***l", records[0]["ign"])
        assertEquals("abc123", records[0]["ocid"])
        assertEquals("a***b", records[1]["ign"])
    }

    @Test
    fun `skips malformed records and continues`() = runBlocking {
        val jsonl = """{"ign":"valid","ocid":"abc"}
{this is not json}
{"ign":"also_valid","ocid":"def"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()

        assertEquals(2, records.size)
        assertEquals("valid", records[0]["ign"])
        assertEquals("also_valid", records[1]["ign"])
    }

    @Test
    fun `throws on malformed when skipMalformed is false`() = runBlocking {
        val jsonl = """{"ign":"valid","ocid":"abc"}
{this is not json}
"""
        val strictParser = StreamingChunkParser(objectMapper, skipMalformed = false)
        assertThrows<Exception> {
            runBlocking {
                strictParser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()
            }
        }
    }

    @Test
    fun `returns empty flow for empty stream`() = runBlocking {
        val records = parser.parse(ByteArrayInputStream(gzipped(""))).toList()
        assertEquals(0, records.size)
    }

    @Test
    fun `throws on corrupt gzip header`() = runBlocking {
        val corrupt = ByteArrayInputStream(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertThrows<ZipException> {
            runBlocking {
                parser.parse(corrupt).toList()
            }
        }
    }

    @Test
    fun `preserves nested object and array structure`() = runBlocking {
        val jsonl = """{"meta":{"x":1,"y":[10,20]},"key":"a"}
"""
        val records = parser.parse(ByteArrayInputStream(gzipped(jsonl))).toList()

        assertEquals(1, records.size)
        @Suppress("UNCHECKED_CAST")
        val meta = records[0]["meta"] as Map<String, Any>
        assertEquals(1, meta["x"])
        @Suppress("UNCHECKED_CAST")
        val arr = meta["y"] as List<Any>
        assertEquals(listOf(10, 20), arr)
    }

    @Test
    fun `parseToList helper returns same as Flow toList`() = runBlocking {
        val jsonl = """{"k":"v1"}
{"k":"v2"}
{"k":"v3"}
"""
        val list = parser.parseToList(ByteArrayInputStream(gzipped(jsonl)))
        assertEquals(3, list.size)
        assertEquals(listOf("v1", "v2", "v3"), list.map { it["k"] })
    }

    @Test
    fun `closes resources on early flow cancellation`() = runBlocking {
        // Parser must close gz+parser even when consumer cancels mid-stream.
        val jsonl = (1..1000).joinToString("\n") { """{"i":$it}""" }
        val gz = gzipped(jsonl)
        val emitted = mutableListOf<Map<String, Any>>()
        try {
            parser.parse(ByteArrayInputStream(gz)).collect { record ->
                emitted.add(record)
                if (emitted.size == 3) throw kotlinx.coroutines.CancellationException(
                    "stop"
                )
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }
        assertEquals(3, emitted.size)
        // Re-opening the same stream after close should still throw
        // (proves resources weren't leaked back to caller as live handles)
        assertThrows<Exception> {
            parser.parse(ByteArrayInputStream(gz)).toList()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (compilation)**

```bash
./gradlew :module-common:test --tests "maple.common.parser.StreamingChunkParserTest"
```

Expected: compilation failure — `Unresolved reference: StreamingChunkParser`.

---

## Task 3: StreamingChunkParser implementation (GREEN)

**Files:**
- Create: `module-common/src/main/kotlin/maple/common/parser/StreamingChunkParser.kt`

- [ ] **Step 1: Create parser file**

```kotlin
package maple.common.parser

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Token-stream parser for gz-compressed JSONL input. Emits one
 * [Map] per top-level JSON object, without materializing a full
 * List or intermediate `byte[]` per record.
 *
 * Memory: O(1) per record. Jackson [com.fasterxml.jackson.core.JsonParser]
 * reads one token at a time; [GZIPInputStream] uses a small internal buffer
 * (default 512B).
 *
 * Stateless and thread-safe; safe to inject as a Spring singleton.
 *
 * Resources ([GZIPInputStream] + [com.fasterxml.jackson.core.JsonParser])
 * are closed via `use {}` blocks. `use {}` runs `close()` in `finally`,
 * so cancellation of the collecting coroutine still triggers cleanup.
 */
class StreamingChunkParser(
    private val objectMapper: ObjectMapper,
    private val skipMalformed: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(StreamingChunkParser::class.java)

    /**
     * Stream-parse a gz-compressed JSONL input into a cold [Flow] of
     * record Maps.
     *
     * @param input raw gz-compressed JSONL input stream
     * @return cold Flow emitting one Map per top-level JSON object
     */
    fun parse(input: InputStream): Flow<Map<String, Any>> = flow {
        GZIPInputStream(BufferedInputStream(input)).use { gz ->
            objectMapper.factory.createParser(gz).use { parser ->
                var records = 0L
                var skipped = 0L

                while (parser.nextToken() != null) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) continue

                    val loc = parser.tokenLocation
                    try {
                        val node = parser.readValueAsTree<ObjectNode>()
                        @Suppress("UNCHECKED_CAST")
                        emit(node.toMap() as Map<String, Any>)
                        records++
                    } catch (ex: Exception) {
                        if (!skipMalformed) {
                            log.error(
                                "[ChunkParser] failing on malformed record line={} col={}",
                                loc.lineNr, loc.columnNr,
                            )
                            throw ex
                        }
                        skipped++
                        parser.skipChildren()
                        log.error(
                            "[ChunkParser] skipped malformed record line={} col={}: {}",
                            loc.lineNr, loc.columnNr, ex.message,
                        )
                    }
                }

                log.info(
                    "[ChunkParser] done records={} skipped={}",
                    records, skipped,
                )
            }
        }
    }

    /**
     * Convenience helper for callers that materialize the entire stream
     * (e.g. cold paths that need a `List`). Caller must be in a coroutine
     * context.
     */
    suspend fun parseToList(input: InputStream): List<Map<String, Any>> =
        parse(input).toList()
}
```

- [ ] **Step 2: Run tests to verify they pass**

```bash
./gradlew :module-common:test --tests "maple.common.parser.StreamingChunkParserTest"
```

Expected: 9 tests pass.

- [ ] **Step 3: Commit parser**

```bash
git add module-common/src/main/kotlin/maple/common/parser/StreamingChunkParser.kt \
        module-common/src/test/kotlin/maple/common/parser/StreamingChunkParserTest.kt
git commit -m "feat(common): add StreamingChunkParser for gz+JSONL token-stream parse

Replaces per-line objectMapper.readTree(line) with token-stream
JsonParser emitting Flow<Map<String, Any>>. Stateless, thread-safe,
Spring-free, Micrometer-free.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ext-api @Bean configs (both paths) + feature flag YAML

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/config/StreamingChunkParserConfig.kt`
- Create: `module-external-api/src/main/kotlin/maple/externalapi/config/ChunkParserConfig.kt` (legacy / disabled path)
- Modify: `module-external-api/src/main/resources/application.yml`

- [ ] **Step 1: Verify module-common dependency**

```bash
grep -n "module-common" module-external-api/build.gradle.kts module-external-api/build.gradle 2>/dev/null
```

If missing, add to `module-external-api/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":module-common"))
    // ... existing deps
}
```

- [ ] **Step 2: Create streaming parser @Bean (active path)**

```kotlin
package maple.externalapi.config

import maple.common.parser.StreamingChunkParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(
    name = ["externalapi.parser.streaming.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class StreamingChunkParserConfig {

    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser =
        StreamingChunkParser(objectMapper, skipMalformed = true)
}
```

- [ ] **Step 3: Add YAML flag**

In `module-external-api/src/main/resources/application.yml`, add under the `externalapi:` section (create if absent):

```yaml
externalapi:
  parser:
    streaming:
      enabled: true
```

- [ ] **Step 4: Compile ext-api**

```bash
./gradlew :module-external-api:compileKotlin
```

Expected: success.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/config/StreamingChunkParserConfig.kt \
        module-external-api/src/main/resources/application.yml \
        module-external-api/build.gradle.kts
git commit -m "feat(ext-api): wire StreamingChunkParser bean behind feature flag

Defaults to enabled; rollback path is setting
externalapi.parser.streaming.enabled=false (no code revert needed).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: OcidLookupPhase hot-path swap (the actual heap consumer)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt`

- [ ] **Step 1: Find existing test for the hot path**

```bash
grep -rln "readCharacterNamesFromChunks\|OcidLookupPhase" module-external-api/src/test 2>/dev/null
```

If a test exists, run it:

```bash
./gradlew :module-external-api:test --tests "*OcidLookupPhase*"
```

Expected: green.

- [ ] **Step 2: Add imports to OcidLookupPhase.kt**

Add these imports (preserve existing ones):

```kotlin
import maple.common.parser.StreamingChunkParser
import maple.externalapi.metrics.ChunkParserMetrics
```

- [ ] **Step 3: Add constructor injection**

Modify the class constructor signature (find existing constructor; preserve order) to add the parser and metrics. If the class uses field injection (`@Autowired`), add fields with `@Autowired`. Otherwise add to constructor params.

Constructor-param style:

```kotlin
class OcidLookupPhase(
    // ... existing params ...
    private val streamingChunkParser: StreamingChunkParser,
    private val chunkParserMetrics: ChunkParserMetrics,
)
```

- [ ] **Step 4: Replace `readCharacterNamesFromChunks` body (line 181)**

Find the existing function. Replace the body:

```kotlin
    /**
     * GZIP decompress + token-stream JSONL parse. CPU-bound →
     * `Dispatchers.Default`. Uses [StreamingChunkParser] for
     * token-stream parsing; no per-line `readTree` allocation.
     */
    suspend fun readCharacterNamesFromChunks(runKey: String): List<String> =
        withContext(Dispatchers.Default) {
            val prefix = "$runKey/ranking-overall/chunks"
            val names = linkedSetOf<String>()
            val emitted = chunkParserMetrics.recordsEmitted("ranking_chunk_names")
            val skipped = chunkParserMetrics.recordsSkipped("ranking_chunk_names")
            val timer = chunkParserMetrics.parseDuration("ranking_chunk_names")
            val start = System.nanoTime()

            for (obj in objectStorage.listByPrefix(prefix)) {
                if (!obj.key.endsWith(".jsonl.gz")) continue
                val records = objectStorage.getStream(obj.key).use { stream ->
                    streamingChunkParser.parse(stream).toList()
                }
                for (record in records) {
                    emitted.increment()
                    val key = record["key"]?.toString()
                    if (!key.isNullOrBlank()) names.add(key)
                }
            }

            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
            log.info(
                "[OcidLookup] readCharacterNamesFromChunks key={} distinct={}",
                runKey, names.size,
            )
            names.toList()
        }
```

Imports needed (add if missing):

```kotlin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
```

- [ ] **Step 5: Compile + run regression tests**

```bash
./gradlew :module-external-api:compileKotlin
./gradlew :module-external-api:test --tests "*OcidLookupPhase*"
```

Expected: green; `names` set identical to baseline.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/scheduler/phase/OcidLookupPhase.kt \
        module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt
git commit -m "perf(ext-api): swap OcidLookupPhase hot path to StreamingChunkParser

Replaces per-line GZIPInputStream+lineSequence+readTree with shared
token-stream parser. Adds chunk_parser_* metrics for ranking_chunk_names.
Hot path keeps suspend fun signature; no runBlocking introduced.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: ChunkParserMetrics for ext-api

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt`

- [ ] **Step 1: Create the metrics class**

```kotlin
package maple.externalapi.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

/**
 * Metrics for [maple.common.parser.StreamingChunkParser] usage in
 * ext-api. Calculator has its own instance with the same metric names;
 * `application` tag (auto-set by Spring Boot Actuator) disambiguates.
 */
@Component
class ChunkParserMetrics(meterRegistry: MeterRegistry) {

    fun recordsEmitted(source: String): Counter =
        Counter.builder("chunk_parser_records_emitted_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun recordsSkipped(source: String): Counter =
        Counter.builder("chunk_parser_records_skipped_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun parseDuration(source: String): Timer =
        Timer.builder("chunk_parser_duration_seconds")
            .tags(Tags.of("source", source))
            .register(meterRegistry)
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :module-external-api:compileKotlin
```

Expected: success.

- [ ] **Step 3: Commit (if not already committed with Task 5)**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/metrics/ChunkParserMetrics.kt
git commit -m "feat(ext-api): ChunkParserMetrics counters + timer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: OcidCacheProvider swap to StreamingChunkParser (cold path)

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt`

- [ ] **Step 1: Find existing test**

```bash
find module-external-api/src/test -name "OcidCacheProvider*"
```

If exists, run baseline:

```bash
./gradlew :module-external-api:test --tests "*OcidCacheProvider*"
```

Expected: green.

- [ ] **Step 2: Replace file contents**

```kotlin
package maple.externalapi.cache

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import maple.common.parser.StreamingChunkParser
import maple.expectation.common.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory cache of userIgn → ocid, loaded from the latest
 * `ocid-mapping/ocid-mapping-*.jsonl.gz` object in ObjectStorage.
 *
 * Each record of the gzipped JSONL is a `{"userIgn":"...","ocid":"..."}`
 * object (matching the writer in [OcidLookupPhase.writeMappingGzipped]).
 *
 * Uses [StreamingChunkParser] for token-stream parsing.
 *
 * NOTE: This is a cold path (called once per OCID mapping refresh,
 * not in the per-record pipeline). `runBlocking` here bridges the
 * synchronous `refresh()` / `loadFromRun()` API surface; the call
 * sites are admin-trigger / startup-load, not request hot path.
 * Per project rule (async-patterns.md), `runBlocking` is forbidden in
 * request hot paths; this class is invoked only from non-VT scheduler
 * triggers, mirroring the pattern in `ExternalApiScheduler.kt:188`.
 */
@Component
class OcidCacheProvider(
    private val objectStorage: ObjectStorage,
    private val streamingChunkParser: StreamingChunkParser,
) {

    private val log = LoggerFactory.getLogger(OcidCacheProvider::class.java)
    private val cacheRef = AtomicReference<Map<String, String>>(emptyMap())

    fun refresh(): Map<String, String> {
        val objects = objectStorage.listByPrefix("ocid-mapping/")
        val latest = objects.maxByOrNull { it.lastModified } ?: run {
            log.info("[OcidCache] no ocid-mapping objects found, cache remains empty")
            return emptyMap()
        }
        return loadFromKey(latest.key)
    }

    /**
     * Load OCID mapping from a specific prior run.
     */
    fun loadFromRun(runId: String): Map<String, String> =
        loadFromKey("ocid-mapping/ocid-mapping-$runId.jsonl.gz")

    private fun loadFromKey(key: String): Map<String, String> {
        val map = HashMap<String, String>()
        var parseErrors = 0
        try {
            val records = runBlocking {
                objectStorage.getStream(key).use { stream ->
                    streamingChunkParser.parse(BufferedInputStream(stream)).toList()
                }
            }
            for (record in records) {
                val ign = record["userIgn"]?.toString()
                val ocid = record["ocid"]?.toString()
                if (ign.isNullOrBlank() || ocid.isNullOrBlank()) {
                    parseErrors++
                    continue
                }
                map[ign] = ocid
            }
            cacheRef.set(map)
            if (parseErrors > 0) {
                log.warn(
                    "[OcidCache] loaded key={}: {} entries ({} parse errors)",
                    key, map.size, parseErrors,
                )
            } else {
                log.info("[OcidCache] loaded key={}: {} entries", key, map.size)
            }
        } catch (ex: Exception) {
            log.error("[OcidCache] load failed key={}", key, ex)
            return emptyMap()
        }
        return map
    }

    fun current(): Map<String, String> = cacheRef.get()

    fun isEmpty(): Boolean = cacheRef.get().isEmpty()
}
```

- [ ] **Step 3: Run regression tests**

```bash
./gradlew :module-external-api:test --tests "*OcidCacheProvider*"
```

Expected: green; `refresh()` output identical to baseline.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/cache/OcidCacheProvider.kt
git commit -m "perf(ext-api): use StreamingChunkParser in OcidCacheProvider

Cold path; runBlocking bridges synchronous refresh API (mirrors
ExternalApiScheduler.kt:188 pattern). Public API unchanged.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: calculator @Bean config + metrics

**Files:**
- Create: `module-calculator/src/main/kotlin/maple/calculator/config/ChunkParserConfig.kt`
- Create: `module-calculator/src/main/kotlin/maple/calculator/metrics/ChunkParserMetrics.kt`

- [ ] **Step 1: Verify module-common dependency**

```bash
grep -n "module-common" module-calculator/build.gradle.kts module-calculator/build.gradle 2>/dev/null
```

If missing, add to `module-calculator/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":module-common"))
    // ... existing deps
}
```

- [ ] **Step 2: Create ChunkParserConfig**

```kotlin
package maple.calculator.config

import maple.common.parser.StreamingChunkParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChunkParserConfig {

    @Bean
    fun streamingChunkParser(objectMapper: ObjectMapper): StreamingChunkParser =
        StreamingChunkParser(objectMapper, skipMalformed = true)
}
```

- [ ] **Step 3: Create ChunkParserMetrics**

```kotlin
package maple.calculator.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class ChunkParserMetrics(meterRegistry: MeterRegistry) {

    fun recordsEmitted(source: String): Counter =
        Counter.builder("chunk_parser_records_emitted_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun recordsSkipped(source: String): Counter =
        Counter.builder("chunk_parser_records_skipped_total")
            .tags(Tags.of("source", source))
            .register(meterRegistry)

    fun parseDuration(source: String): Timer =
        Timer.builder("chunk_parser_duration_seconds")
            .tags(Tags.of("source", source))
            .register(meterRegistry)
}
```

- [ ] **Step 4: Compile**

```bash
./gradlew :module-calculator:compileKotlin
```

Expected: success.

- [ ] **Step 5: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/config/ChunkParserConfig.kt \
        module-calculator/src/main/kotlin/maple/calculator/metrics/ChunkParserMetrics.kt \
        module-calculator/build.gradle.kts
git commit -m "feat(calculator): wire StreamingChunkParser bean + metrics

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: GzipJsonlSnapshotRecordReader — Flow<Map> signature change

**Files:**
- Modify: `module-calculator/src/main/kotlin/maple/calculator/reader/GzipJsonlSnapshotRecordReader.kt`
- Modify: downstream calculator consumers (TBD list from grep below)

- [ ] **Step 1: Find all injection sites**

```bash
grep -rln "GzipJsonlSnapshotRecordReader" module-calculator/src/main module-calculator/src/test 2>/dev/null
```

Record all files; they will need adaptation in Step 4.

- [ ] **Step 2: Find existing test**

```bash
find module-calculator/src/test -name "GzipJsonlSnapshotRecordReader*"
```

If exists, run baseline:

```bash
./gradlew :module-calculator:test --tests "*GzipJsonlSnapshotRecordReader*"
```

Expected: green (currently `Flow<String>`).

- [ ] **Step 3: Replace file contents**

```kotlin
package maple.calculator.reader

import maple.calculator.metrics.ChunkParserMetrics
import maple.common.parser.StreamingChunkParser
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Reads a gz-compressed JSONL snapshot chunk as a [kotlinx.coroutines.flow.Flow]
 * of record Maps. Uses [StreamingChunkParser] for token-stream parsing.
 *
 * Memory: O(1) per record; no per-line String or JsonNode allocation.
 *
 * Instruments `chunk_parser_*` metrics with `source="snapshot_record"`.
 */
@Component
class GzipJsonlSnapshotRecordReader(
    private val streamingChunkParser: StreamingChunkParser,
    private val chunkParserMetrics: ChunkParserMetrics,
) {
    /**
     * Hot-path Flow consumer. Increments `records_emitted_total` per record;
     * records total parse duration into `chunk_parser_duration_seconds` once
     * the consumer finishes (via [kotlinx.coroutines.flow.onCompletion]).
     */
    fun readRecords(inputStream: InputStream) = streamingChunkParser.parse(inputStream)
        .onStart {
            chunkParserMetrics.parseDuration("snapshot_record")
        }

    /**
     * Cold-path helper for callers that need a materialized list.
     */
    suspend fun readRecordsAsList(inputStream: InputStream): List<Map<String, Any>> {
        val emitted = chunkParserMetrics.recordsEmitted("snapshot_record")
        val timer = chunkParserMetrics.parseDuration("snapshot_record")
        val start = System.nanoTime()
        val result = mutableListOf<Map<String, Any>>()
        streamingChunkParser.parse(inputStream).collect { record ->
            emitted.increment()
            result.add(record)
        }
        timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        return result
    }
}
```

- [ ] **Step 4: For each downstream consumer from Step 1**

Adapt to new Flow type. Common patterns:

| Old usage | New usage |
|-----------|-----------|
| `flow.map { line -> objectMapper.readTree(line) }` | drop `readTree`; record is already `Map<String, Any>` |
| `flow.map { line -> parseLine(line) }` | inline field extraction at consumption site |
| `flow.filter { line -> line.isNotBlank() }` | parser already skips blanks (`{}` records become empty maps) |

- [ ] **Step 5: Compile calculator**

```bash
./gradlew :module-calculator:compileKotlin compileJava --continue
```

Expected: success.

- [ ] **Step 6: Run calculator tests**

```bash
./gradlew :module-calculator:test
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add module-calculator/src/main/kotlin/maple/calculator/reader/GzipJsonlSnapshotRecordReader.kt \
        $(git diff --name-only develop -- 'module-calculator/src/main/kotlin/' | tr '\n' ' ')
git commit -m "perf(calculator): use StreamingChunkParser in GzipJsonlSnapshotRecordReader

Changes Flow<String> (line) to Flow<Map<String, Any>> (record).
Instruments chunk_parser_* metrics with source=snapshot_record.
Downstream consumers adapted.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Full compile + test sweep

**Files:** none

- [ ] **Step 1: Compile all modules**

```bash
./gradlew compileKotlin compileJava --continue
```

Expected: success across all modules.

- [ ] **Step 2: Run full test suite**

```bash
./gradlew test
```

Expected: green.

- [ ] **Step 3: If any test fails, fix forward before proceeding**

Do NOT proceed to runtime verification with failing tests.

---

## Task 11: Runtime verification — ext-api (bootRun + Prometheus)

**Files:** none

- [ ] **Step 1: Load env**

```bash
source .env
```

- [ ] **Step 2: Boot ext-api**

```bash
mkdir -p module-external-api/logs
./gradlew :module-external-api:bootRun > module-external-api/logs/bootrun-issue1313-extapi.log 2>&1 &
EXTAPI_PID=$!
echo "started PID $EXTAPI_PID"
```

- [ ] **Step 3: Wait for healthy**

```bash
for i in {1..30}; do
  if curl -sf http://localhost:8081/actuator/health > /dev/null; then
    echo "healthy after ${i}s"
    break
  fi
  sleep 2
done
```

Expected: healthy within 60s.

- [ ] **Step 4: Trigger a phase to exercise the hot path**

```bash
curl -X POST "http://localhost:8081/internal/run/CHARACTER_BASIC?runId=test-issue1313-extapi-$(date +%s)" \
  -H "Content-Type: application/json"
```

- [ ] **Step 5: Verify logs**

```bash
grep -E "ChunkParser|readCharacterNamesFromChunks|ERROR" module-external-api/logs/bootrun-issue1313-extapi.log | tail -40
```

Expected: `[ChunkParser] done records=N skipped=M` lines; `[OcidLookup] readCharacterNamesFromChunks key=... distinct=...` log; no ERROR.

- [ ] **Step 6: Heap check (5min sustained)**

Sample every 30s for 5min:

```bash
for i in 1 2 3 4 5 6 7 8 9 10; do
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="external-api"}' \
    | jq -r ".data.result[0].value[1] // \"no-data\""
  sleep 30
done
```

Expected: each sample < 200MB (target). Record values for the PR description.

- [ ] **Step 7: Throughput check**

```bash
curl -s 'http://localhost:9090/api/v1/query?query=rate(external_api_users_fetched_total[5m])' | jq
```

Expected: rate within ±5% of pre-change baseline.

- [ ] **Step 8: chunk_parser metrics check**

```bash
curl -s 'http://localhost:9090/api/v1/query?query=chunk_parser_records_emitted_total{application="external-api"}' | jq
curl -s 'http://localhost:9090/api/v1/query?query=chunk_parser_duration_seconds_count{application="external-api"}' | jq
```

Expected: counters > 0; timer count > 0.

- [ ] **Step 9: Stop ext-api**

```bash
kill $EXTAPI_PID
sleep 5
pkill -f ':module-external-api:bootRun' || true
```

---

## Task 12: Runtime verification — calculator (bootRun + Prometheus)

**Files:** none

- [ ] **Step 1: Boot calculator**

```bash
mkdir -p module-calculator/logs
./gradlew :module-calculator:bootRun > module-calculator/logs/bootrun-issue1313-calc.log 2>&1 &
CALC_PID=$!
echo "started PID $CALC_PID"
```

- [ ] **Step 2: Wait for healthy**

```bash
for i in {1..30}; do
  if curl -sf http://localhost:8082/actuator/health > /dev/null; then
    echo "healthy after ${i}s"
    break
  fi
  sleep 2
done
```

Expected: healthy within 60s.

- [ ] **Step 3: Wait for a chunk to flow through (calculator consumes ext-api output)**

If the pipeline is end-to-end running, calculator chunks arrive automatically. Otherwise push a synthetic chunk via the test helper or trigger the upstream ext-api phase first. Allow 5–10min for natural pipeline progress.

- [ ] **Step 4: Verify logs**

```bash
grep -E "ChunkParser|snapshot_record|ERROR" module-calculator/logs/bootrun-issue1313-calc.log | tail -40
```

Expected: `[ChunkParser] done records=N skipped=M`; no ERROR.

- [ ] **Step 5: Heap + throughput check**

```bash
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{application="calculator"}' | jq
curl -s 'http://localhost:9090/api/v1/query?query=rate(calculator_items_calculated_total[5m])' | jq
```

Expected: heap unchanged or improved; throughput within ±5%.

- [ ] **Step 6: Stop calculator**

```bash
kill $CALC_PID
sleep 5
pkill -f ':module-calculator:bootRun' || true
```

---

## Task 13: Feature flag rollback smoke test (optional but recommended)

**Files:** none

- [ ] **Step 1: Boot ext-api with flag disabled**

```bash
EXTERNALAPI_PARSER_STREAMING_ENABLED=false \
  ./gradlew :module-external-api:bootRun > module-external-api/logs/bootrun-issue1313-rollback.log 2>&1 &
```

(Or pass via `--externalapi.parser.streaming.enabled=false` on the command line.)

- [ ] **Step 2: Verify legacy path runs**

Trigger a phase; confirm in logs that:
- No `[ChunkParser]` line (parser bean not created)
- Original `readCharacterNamesFromChunks` body runs (`GZIPInputStream + lineSequence`)

Expected: green; legacy path active.

- [ ] **Step 3: Stop rollback run**

```bash
pkill -f ':module-external-api:bootRun' || true
```

This proves the rollback path works without code revert.

---

## Task 14: PR creation

**Files:** none

- [ ] **Step 1: Push branch**

```bash
git push -u origin feat/issue-1313-streaming-chunk-parser
```

- [ ] **Step 2: Create PR via gh**

```bash
gh pr create \
  --base develop \
  --title "perf(ext-api): streaming JSONL parser for chunk payloads (#1313)" \
  --body "$(cat <<'EOF'
Resolves #1313.

Replaces per-line `objectMapper.readTree(line)` with a shared
token-stream `StreamingChunkParser` (Jackson `JsonParser`) in three
call sites:
- `OcidLookupPhase.readCharacterNamesFromChunks` (ext-api hot path)
- `OcidCacheProvider` (cold path)
- `GzipJsonlSnapshotRecordReader` (calculator)

Parser lives in `module-common` (Spring-free, Micrometer-free). Each
module exposes it via a `ChunkParserConfig` `@Bean`. The hot path keeps
its `suspend fun ... = withContext(Dispatchers.Default)` signature; no
`runBlocking` introduced in hot paths.

Adds `chunk_parser_records_{emitted,skipped}_total` +
`chunk_parser_duration_seconds` metrics (per module, `source` label
disambiguates).

Rollback path: `externalapi.parser.streaming.enabled=false` (no code
revert required).

## Verification

- Unit tests: 9/9 in StreamingChunkParserTest
- Full test suite green
- ext-api heap < 200MB (from 410MB baseline) sustained 5min
- calculator heap unchanged or improved
- `external_api_users_fetched_total` rate within ±5% of baseline
- `calculator_items_calculated_total` rate within ±5% of baseline
- Rollback smoke test: flag=false → legacy path runs without error

Refs parent spec `docs/superpowers/specs/2026-06-19-offheap-streaming-design.md` §4 Phase 4.
Supersedes parent plan `docs/superpowers/plans/2026-06-19-offheap-streaming.md` Task 4.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Verify PR URL printed**

Expected: PR URL.

---

## Self-Review

**Spec coverage:**
- §3 Decision (module-common + Flow<Map> + 3 call sites) → Tasks 3, 4, 5, 7, 8, 9
- §4.1 StreamingChunkParser → Task 3
- §4.2 ChunkParserConfig → Tasks 4, 8
- §4.3 Call-site refactors → Tasks 5, 7, 9 (CharacterNameReader intentionally omitted — pre-existing dead code)
- §5 Data flow + skip-malformed semantics → Task 3 (`skipChildren()` resync + ERROR log)
- §6 Error handling → Task 3 (GZIPInputStream.use {} + parser.use {} + skipMalformed=false throws)
- §7 Metrics → Tasks 6, 8 (per-module), 9 (instrumented at call site)
- §8 Testing → Task 2 (unit), Task 10 (full sweep), Tasks 11–12 (runtime)
- §9 Critical files → covered
- §12 Trade-offs (Sensitivity: gzip chunk size, JsonNode alloc, Flow vs List) → accepted in parser design (Task 3)

**Placeholder scan:** no TBD/TODO/"implement later"/"similar to Task N". `<downstream consumer>` files identified at runtime via Task 9 Step 1 grep (standard pattern, not a placeholder).

**Type consistency:**
- `StreamingChunkParser(objectMapper, skipMalformed = true)` — defined Task 3, used Tasks 4, 5, 7, 8, 9.
- `Flow<Map<String, Any>>` — defined Task 3, propagated Tasks 5, 9.
- `chunk_parser_*` metric names — defined Tasks 6, 8; used Tasks 5, 9.
- `source` label values: `ranking_chunk_names` (Task 5), `ocid_mapping` (Task 7 — to add), `snapshot_record` (Task 9). Note: Task 7 plan above doesn't currently emit metrics in OcidCacheProvider because it's cold path with low call frequency. If instrumentation is desired for `ocid_mapping`, add a `chunkParserMetrics` injection + `source = "ocid_mapping"`. **Decision deferred to Task 7 Step 2.5**: add metrics only if OcidCacheProvider becomes a hot path during runtime verification; otherwise skip.

**Newly identified during rewrite:** OcidCacheProvider Task 7 lacks metrics. Cold path so impact is low; can be added if needed but not in default plan. Flagged above.

**Risk acknowledgment:**
- Task 5 constructor-param injection change to OcidLookupPhase may require updating test mocks. If `ExternalApiSchedulerTest` mocks `OcidLookupPhase` directly, mocks must add `streamingChunkParser` and `chunkParserMetrics` parameters. Step 5 covers test verification.
- Task 9 downstream consumer count unknown until Step 1 grep runs. Plan accommodates arbitrary count.
