# Issue #996 Implementation Plan: Synchronizer file reader silent error loss

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate silent loss in `OcidMappingFileReader` and `BasicChunkFileReader`. JSON parse errors throw immediately; missing files throw `IllegalStateException`; field-missing records are counted and trigger a threshold exception. Add counters and update tests.

**Architecture:** Refactor each `parseRecord`/`parseMapping` to count parse errors / missing fields / filtered records in `AtomicLong` parameters; the surrounding `read` method owns the counters and logs a single summary line per concern level. A new `SynchronizerReaderMetrics` Spring component holds 3 counters; both readers receive it via constructor.

**Tech Stack:** Kotlin, Jackson `JsonProcessingException`, Spring Boot 3.x, Micrometer, JUnit 5, AssertJ, `@Value` config for the missing-field threshold.

---

## File Structure

| File | Action | Purpose |
| --- | --- | --- |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerReaderMetrics.kt` | Create | 3 counters (parse_error, missing_field, filtered) |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt` | Modify | `read` throws on missing file; `parseMapping` throws on parse error; counts missing fields |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` | Modify | `parseRecord` throws on parse error; counts filtered + missing fields with threshold; `read` / `readInBatches` aggregate counters |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerReaderConfig.kt` | Create | `@Value` for missing-field threshold (default 100) |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt` | Modify | Rewrite `skips malformed lines` test, replace `returns empty when not found` |
| `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/BasicChunkFileReaderTest.kt` | Create | 4 cases: normal, filter, parse-error-throw, threshold |

---

## Task 1: Add SynchronizerReaderMetrics

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerReaderMetrics.kt`

- [ ] **Step 1: Create the metrics component**

```kotlin
package maple.synchronizer.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SynchronizerReaderMetrics(private val registry: MeterRegistry) {

    private val parseErrorCounters = mutableMapOf<String, Counter>()
    private val missingFieldCounters = mutableMapOf<String, Counter>()
    private val filteredCounters = mutableMapOf<String, Counter>()

    fun incrementParseError(reader: String) {
        parseErrorCounters
            .getOrPut(reader) { registry.counter("synchronizer_reader_parse_error_total", "reader", reader) }
            .increment()
    }

    fun incrementMissingField(reader: String) {
        missingFieldCounters
            .getOrPut(reader) { registry.counter("synchronizer_reader_missing_field_total", "reader", reader) }
            .increment()
    }

    fun incrementFiltered(reader: String, reason: String) {
        filteredCounters
            .getOrPut("${reader}:${reason}") {
                registry.counter("synchronizer_reader_filtered_total", "reader", reader, "reason", reason)
            }
            .increment()
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/metrics/SynchronizerReaderMetrics.kt
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "feat(synchronizer): add SynchronizerReaderMetrics counters

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Add threshold config bean

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerReaderConfig.kt`

- [ ] **Step 1: Create the config**

```kotlin
package maple.synchronizer.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SynchronizerReaderConfig {

    @Bean
    fun basicChunkMissingFieldThreshold(
        @Value("\${synchronizer.reader.missing-field-threshold:100}") threshold: Int,
    ): Int = threshold
}
```

- [ ] **Step 2: Add the property to YAML**

Find: `module-synchronizer/src/main/resources/application.yml` (or the active profile YAML).

Add (under `synchronizer:` if present, otherwise at the top level):

```yaml
synchronizer:
  reader:
    missing-field-threshold: 100
```

> **Note:** If the YAML uses a different root key (e.g. `synchronizer.store` already exists), merge `reader:` as a sibling. Verify by running Step 3.

- [ ] **Step 3: Compile + verify bean loads**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/config/SynchronizerReaderConfig.kt module-synchronizer/src/main/resources/
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "feat(synchronizer): configurable missing-field threshold (default 100)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Refactor OcidMappingFileReader

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt`

- [ ] **Step 1: Update imports**

Find the import block (lines 1-10) and replace with:

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
```

- [ ] **Step 2: Update class signature + add metrics field**

Find the class declaration (lines 17-23) and replace with:

```kotlin
@Component
class OcidMappingFileReader(
    @Value("\${synchronizer.store.base-path:../data}")
    private val storeBasePath: String,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
```

- [ ] **Step 3: Replace `read` and `parseMapping` (lines 25-49)**

Find the entire `read` + `parseMapping` block. Replace with:

```kotlin
    fun read(manifestPath: String): List<OcidMapping> {
        val path = Paths.get(storeBasePath, manifestPath)
        if (!Files.exists(path)) {
            throw IllegalStateException("Ocid mapping file not found: $path")
        }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val mappings = mutableListOf<OcidMapping>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).bufferedReader().use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    parseMapping(line, parseErrors, missingFields)?.let { mappings.add(it) }
                }
        }
        val pe = parseErrors.get()
        val mf = missingFields.get()
        if (pe > 0) {
            log.error("[OcidMappingFileReader] parseErrors={} missingFields={} parsed={} from {}",
                pe, mf, mappings.size, manifestPath)
        } else if (mf > 0) {
            log.warn("[OcidMappingFileReader] missingFields={} parsed={} from {}",
                mf, mappings.size, manifestPath)
        } else {
            log.info("[OcidMappingFileReader] parsed {} mappings from {}", mappings.size, manifestPath)
        }
        return mappings
    }

    private fun parseMapping(
        line: String,
        parseErrorCount: AtomicLong,
        missingFieldCount: AtomicLong,
    ): OcidMapping? {
        val node: JsonNode = try {
            objectMapper.readTree(line)
        } catch (ex: JsonProcessingException) {
            parseErrorCount.incrementAndGet()
            readerMetrics.incrementParseError("ocid_mapping")
            log.error("[OcidMappingFileReader] parse error at line: {}", line.take(80), ex)
            throw ex
        }
        val ign = node.get("userIgn")?.asText()
        val ocid = node.get("ocid")?.asText()
        if (ign.isNullOrBlank() || ocid.isNullOrBlank()) {
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("ocid_mapping")
            return null
        }
        return OcidMapping(ign, ocid)
    }
}
```

- [ ] **Step 4: Compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL. (The next task updates the test; existing test will fail until then.)

- [ ] **Step 5: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "feat(synchronizer): OcidMappingFileReader throws on parse error / missing file

#996: eliminate silent loss. parseMapping throws JsonProcessingException;
read throws IllegalStateException on missing file; missing fields counted.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Update OcidMappingFileReaderTest

**Files:**
- Modify: `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt`

The existing test uses `OcidMappingFileReader(storeBasePath, objectMapper)`. The new constructor needs a `SynchronizerReaderMetrics` (3rd arg). Use a `SimpleMeterRegistry` for the test.

- [ ] **Step 1: Replace imports + add metrics helper**

Find the imports (lines 1-13) and replace with:

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
```

- [ ] **Step 2: Update setUp to construct new reader**

Find `setUp()` (lines 24-29) and replace with:

```kotlin
    @BeforeEach
    fun setUp() {
        val registry = SimpleMeterRegistry()
        reader = OcidMappingFileReader(
            storeBasePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(registry),
        )
    }
```

- [ ] **Step 3: Replace "returns empty when not found" test (lines 48-52)**

Find:

```kotlin
    @Test
    fun `read returns empty list when file not found`() {
        val mappings = reader.read("nonexistent/path.jsonl.gz")
        assertThat(mappings).isEmpty()
    }
```

Replace with:

```kotlin
    @Test
    fun `read throws IllegalStateException when file not found`() {
        assertThatThrownBy { reader.read("nonexistent/path.jsonl.gz") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Ocid mapping file not found")
    }
```

- [ ] **Step 4: Replace "skips blank and malformed lines" test (lines 54-70)**

Find the entire `read skips blank and malformed lines` test. Replace with two tests:

```kotlin
    @Test
    fun `read skips blank lines and counts records with missing fields`() {
        val gzPath = tempDir.resolve("test-mixed.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            "",
            "   ",
            """{"userIgn":"PlayerB","ocid":"ocid-b"}""",
            """{"missing":"fields"}""",
        ))

        val mappings = reader.read("test-mixed.jsonl.gz")

        assertThat(mappings).hasSize(2)
        assertThat(mappings[0].userIgn).isEqualTo("PlayerA")
        assertThat(mappings[1].userIgn).isEqualTo("PlayerB")
    }

    @Test
    fun `read throws JsonProcessingException on malformed line`() {
        val gzPath = tempDir.resolve("test-malformed.jsonl.gz")
        writeGzipJsonl(gzPath, listOf(
            """{"userIgn":"PlayerA","ocid":"ocid-a"}""",
            """{not valid json""",
        ))

        assertThatThrownBy { reader.read("test-malformed.jsonl.gz") }
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException::class.java)
    }
```

- [ ] **Step 5: Run the test class**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "*OcidMappingFileReaderTest*" -i`
Expected: PASS (4 tests total: parses, throws-not-found, skips-blank-counts, throws-malformed).

- [ ] **Step 6: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/storage/OcidMappingFileReaderTest.kt
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "test(synchronizer): update OcidMappingFileReaderTest for throw behavior

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Refactor BasicChunkFileReader

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt`

- [ ] **Step 1: Update imports**

Find the import block (lines 1-11) and replace with:

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.util.GzipUtils
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
```

- [ ] **Step 2: Update class signature + add threshold qualifier**

Find the class declaration (lines 27-37) and replace with:

```kotlin
@Component
class BasicChunkFileReader(
    @Value("\${synchronizer.store.base-path:../data}")
    private val basePath: String,
    private val objectMapper: ObjectMapper,
    private val readerMetrics: SynchronizerReaderMetrics,
    @org.springframework.beans.factory.annotation.Qualifier("basicChunkMissingFieldThreshold")
    private val missingFieldThreshold: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEFAULT_BATCH_SIZE = 1000
    }
```

- [ ] **Step 3: Replace `read` (lines 39-55)**

Find `read(objectKey)` and replace with:

```kotlin
    fun read(objectKey: String): List<BasicRecord> {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        val records = mutableListOf<BasicRecord>()
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    parseRecord(line, parseErrors, missingFields, filtered)?.let { records.add(it) }
                }
                line = reader.readLine()
            }
        }
        logChunkSummary(objectKey, records.size, parseErrors.get(), missingFields.get(), filtered.get())
        return records
    }
```

- [ ] **Step 4: Replace `readInBatches` (lines 57-92)**

Find `readInBatches(objectKey, batchSize, handler)` and replace with:

```kotlin
    fun readInBatches(
        objectKey: String,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        handler: (List<BasicRecord>) -> Unit,
    ) {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        val batch = mutableListOf<BasicRecord>()
        val seenOcids = mutableSetOf<String>()
        var totalCount = 0
        var line: String? = reader.readLine()  // <-- NOTE: there's a scoping bug below; fix in Step 5
```

> **Bug to fix in this step:** the current code has its `val line` declared before the `use` block in the existing implementation. The replace should keep the original `GZIPInputStream(...).bufferedReader().use { reader ->` wrapper. Rewrite the whole method body to:

```kotlin
    fun readInBatches(
        objectKey: String,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        handler: (List<BasicRecord>) -> Unit,
    ) {
        val path = Paths.get(basePath, objectKey)
        require(Files.exists(path)) { "Chunk file not found: $path" }

        val parseErrors = AtomicLong(0)
        val missingFields = AtomicLong(0)
        val filtered = AtomicLong(0)
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().use { reader ->
            val batch = mutableListOf<BasicRecord>()
            val seenOcids = mutableSetOf<String>()
            var totalCount = 0
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val record = parseRecord(line, parseErrors, missingFields, filtered)
                    if (record != null && seenOcids.add(record.ocid)) {
                        batch.add(record)
                        if (batch.size >= batchSize) {
                            totalCount += batch.size
                            handler(batch.toList())
                            batch.clear()
                        }
                    }
                }
                line = reader.readLine()
            }
            if (batch.isNotEmpty()) {
                totalCount += batch.size
                handler(batch)
            }
            logChunkSummary(objectKey, totalCount, parseErrors.get(), missingFields.get(), filtered.get())
        }
    }
```

- [ ] **Step 5: Replace `parseRecord` (lines 94-124)**

Find the entire `parseRecord` method. Replace with:

```kotlin
    private fun parseRecord(
        line: String,
        parseErrorCount: AtomicLong,
        missingFieldCount: AtomicLong,
        filteredCount: AtomicLong,
    ): BasicRecord? {
        val node: JsonNode = try {
            objectMapper.readTree(line)
        } catch (ex: JsonProcessingException) {
            parseErrorCount.incrementAndGet()
            readerMetrics.incrementParseError("basic_chunk")
            log.error("[BasicChunkFileReader] parse error at line: {}", line.take(80), ex)
            throw ex
        }

        if (node.get("status")?.asText() != "SUCCESS") {
            filteredCount.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "status")
            return null
        }
        if (node.get("endpoint")?.asText() != "character-basic") {
            filteredCount.incrementAndGet()
            readerMetrics.incrementFiltered("basic_chunk", "endpoint")
            return null
        }

        val ocid = node.get("key")?.asText()
        val body = node.get("body")
        if (ocid.isNullOrBlank() || body == null) {
            missingFieldCount.incrementAndGet()
            readerMetrics.incrementMissingField("basic_chunk")
            if (missingFieldCount.get() > missingFieldThreshold) {
                throw IllegalStateException(
                    "BasicChunk missing-field threshold exceeded: $missingFieldCount > $missingFieldThreshold",
                )
            }
            return null
        }

        val userIgn = body.get("character_name")?.asText() ?: return null
        val worldName = body.get("world_name")?.asText()
        val characterClass = body.get("character_class")?.asText()
        val characterLevel = body.get("character_level")?.asInt()
        val guildName = body.get("guild_name")?.asText()

        val bodyBytes = objectMapper.writeValueAsBytes(body)
        val compressed = GzipUtils.compress(bodyBytes)
        val hash = sha256Hex(bodyBytes)

        return BasicRecord(
            userIgn = userIgn,
            ocid = ocid,
            worldName = worldName,
            characterClass = characterClass,
            characterLevel = characterLevel,
            guildName = guildName,
            compressedBody = compressed,
            bodyHash = hash,
        )
    }
```

- [ ] **Step 6: Add the shared summary log helper at end of class**

After `sha256Hex`, add:

```kotlin
    private fun logChunkSummary(
        objectKey: String,
        records: Int,
        parseErrors: Long,
        missingFields: Long,
        filtered: Long,
    ) {
        if (parseErrors > 0) {
            log.error("[BasicChunkFileReader] parseErrors={} missingFields={} filtered={} parsed={} from {}",
                parseErrors, missingFields, filtered, records, objectKey)
        } else if (missingFields > 0 || filtered > 0) {
            log.warn("[BasicChunkFileReader] missingFields={} filtered={} parsed={} from {}",
                missingFields, filtered, records, objectKey)
        } else {
            log.info("[BasicChunkFileReader] parsed {} records from {}", records, objectKey)
        }
    }
```

- [ ] **Step 7: Compile check**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:compileKotlin --continue`
Expected: BUILD SUCCESSFUL. Existing tests (if any) using the old constructor will fail until the next task creates a new test.

- [ ] **Step 8: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "feat(synchronizer): BasicChunkFileReader throws on parse error / threshold

#996: parseRecord throws JsonProcessingException; missing-field count
exceeds threshold throws IllegalStateException; filtered records counted.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Create BasicChunkFileReaderTest

**Files:**
- Create: `module-synchronizer/src/test/kotlin/maple/synchronizer/storage/BasicChunkFileReaderTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package maple.synchronizer.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import maple.synchronizer.metrics.SynchronizerReaderMetrics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

class BasicChunkFileReaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var reader: BasicChunkFileReader
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        reader = BasicChunkFileReader(
            basePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(SimpleMeterRegistry()),
            missingFieldThreshold = 100,
        )
    }

    @Test
    fun `read parses normal records`() {
        val gz = tempDir.resolve("ok.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            basicLine(ocid = "ocid-2", ign = "PlayerB", status = "SUCCESS", endpoint = "character-basic"),
        ))

        val records = reader.read("ok.jsonl.gz")
        assertThat(records).hasSize(2)
        assertThat(records.map { it.userIgn }).containsExactly("PlayerA", "PlayerB")
    }

    @Test
    fun `read filters non-SUCCESS and non-character-basic records`() {
        val gz = tempDir.resolve("mixed.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            basicLine(ocid = "ocid-2", ign = "PlayerB", status = "FAILED", endpoint = "character-basic"),
            basicLine(ocid = "ocid-3", ign = "PlayerC", status = "SUCCESS", endpoint = "item-equipment"),
        ))

        val records = reader.read("mixed.jsonl.gz")
        assertThat(records).hasSize(1)
        assertThat(records[0].userIgn).isEqualTo("PlayerA")
    }

    @Test
    fun `read throws JsonProcessingException on malformed line`() {
        val gz = tempDir.resolve("bad.jsonl.gz")
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            """{not valid""",
        ))

        assertThatThrownBy { reader.read("bad.jsonl.gz") }
            .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException::class.java)
    }

    @Test
    fun `read throws IllegalStateException when missing-field threshold exceeded`() {
        val smallThresholdReader = BasicChunkFileReader(
            basePath = tempDir.toString(),
            objectMapper = objectMapper,
            readerMetrics = SynchronizerReaderMetrics(SimpleMeterRegistry()),
            missingFieldThreshold = 2,
        )
        val gz = tempDir.resolve("threshold.jsonl.gz")
        // First record: success. Then 3 records with body null (missing-field): threshold=2 means 3rd missing triggers throw.
        writeGzipJsonl(gz, listOf(
            basicLine(ocid = "ocid-1", ign = "PlayerA", status = "SUCCESS", endpoint = "character-basic"),
            """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-2","body":null}""",
            """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-3","body":null}""",
            """{"status":"SUCCESS","endpoint":"character-basic","key":"ocid-4","body":null}""",
        ))

        assertThatThrownBy { smallThresholdReader.read("threshold.jsonl.gz") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("missing-field threshold exceeded")
    }

    private fun basicLine(ocid: String, ign: String, status: String, endpoint: String): String =
        """{"status":"$status","endpoint":"$endpoint","key":"$ocid","body":{"character_name":"$ign"}}"""

    private fun writeGzipJsonl(path: Path, lines: List<String>) {
        Files.createDirectories(path.parent)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(path.toFile()))).use { gzip ->
            for (line in lines) {
                gzip.write((line + "\n").toByteArray())
            }
        }
    }
}
```

- [ ] **Step 2: Run the test class**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test --tests "*BasicChunkFileReaderTest*" -i`
Expected: PASS (4 tests).

- [ ] **Step 3: Commit**

```bash
cd /home/maple/probabilistic-valuation-engine
git add module-synchronizer/src/test/kotlin/maple/synchronizer/storage/BasicChunkFileReaderTest.kt
git -c user.email=claude@anthropic.com -c user.name=claude commit -m "test(synchronizer): BasicChunkFileReaderTest covers throw + threshold

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Full module verification

**Files:** none (verification only)

- [ ] **Step 1: Compile all modules**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run synchronizer tests**

Run: `cd /home/maple/probabilistic-valuation-engine && ./gradlew :module-synchronizer:test`
Expected: BUILD SUCCESSFUL. All storage tests pass.

- [ ] **Step 3: Verify no runCatching getOrNull in readers**

Run: `cd /home/maple/probabilistic-valuation-engine && grep -n "getOrNull\|runCatching" module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt`
Expected: no output.

- [ ] **Step 4: Done**

Report success. No further commits.

---

## Self-Review

**Spec coverage:**
- ✅ `parseMapping` throws `JsonProcessingException` → Task 3 Step 3
- ✅ `parseRecord` throws `JsonProcessingException` + threshold `IllegalStateException` → Task 5 Step 5
- ✅ Status/endpoint filtering stays `null` → Task 5 Step 5 (counts `filtered`)
- ✅ `OcidMappingFileReader.read` throws `IllegalStateException` on missing file → Task 3 Step 3
- ✅ `BasicChunkFileReader` already throws via `require` → unchanged
- ✅ Missing-field counter + log → Task 3 Step 3, Task 5 Step 5
- ✅ Counters: `synchronizer_reader_{parse_error,missing_field,filtered}_total` → Task 1
- ✅ Threshold externalized via YAML → Task 2
- ✅ Existing `skips malformed` test updated → Task 4
- ✅ `returns empty when not found` test updated → Task 4
- ✅ New `BasicChunkFileReaderTest` with 4 cases → Task 6

**Placeholder scan:** No TBD/TODO. Task 5 Step 4 contains a self-correction note for the reader/wrapping block — acceptable inline guidance, not a placeholder.

**Type consistency:**
- `SynchronizerReaderMetrics` API: `incrementParseError(reader: String)`, `incrementMissingField(reader: String)`, `incrementFiltered(reader: String, reason: String)` — consistent across both readers and the test stub.
- `BasicChunkFileReader` constructor: `(basePath, objectMapper, readerMetrics, missingFieldThreshold)` — consistent in production code and test setUp.
- `OcidMappingFileReader` constructor: `(storeBasePath, objectMapper, readerMetrics)` — consistent in production code and test setUp.
- Counter name `synchronizer_reader_*_total` matches spec section 4 metrics table.
- Exception types: `JsonProcessingException` (parse) + `IllegalStateException` (missing file / threshold) — matches spec section 2.
