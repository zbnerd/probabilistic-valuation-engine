# Artifact Format Evolution (1423-1427) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make artifact schemas explicit (Avro .avsc), prove out Parquet+ZSTD on OCID mapping as a side-by-side PoC, investigate the small-file problem with measurements, and produce an Iceberg readiness assessment — without committing to format migration or Iceberg adoption.

**Architecture:** Four independent PRs in fixed execution order (1425 → 1423 → 1427 → 1426). Each PR is shippable on its own. No producer/reader rewrite in PR1 (schema only). Side-by-side Parquet write in PR2 (no production migration). Read-only investigations in PR3+PR4.

**Tech Stack:** Apache Avro 1.11.x, Apache Parquet 1.17.1 (`parquet-avro`), ZSTD level 5, Gradle Avro plugin (`com.github.davidmc24.gradle.plugin.avro`), Kotlin 2.x.

**Reference spec:** `docs/superpowers/specs/2026-06-28-parquet-iceberg-readiness-design.md`

---

## PR #A: Issue 1425 — Schema Formalization

### Task A1: Add Avro plugin + parquet-avro dependency to module-common

**Files:**
- Modify: `module-common/build.gradle.kts`
- Create: `module-common/src/main/avro/.gitkeep` (empty placeholder to ensure dir tracks)

- [ ] **Step 1: Inspect current module-common build**

Run:
```bash
cat module-common/build.gradle.kts
```
Expected: read current plugins block and dependencies block to know where to insert.

- [ ] **Step 2: Add Avro plugin and parquet-avro dependency**

Modify `module-common/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

dependencies {
    // existing dependencies...
    implementation("org.apache.avro:avro:1.11.4")
    implementation("org.apache.parquet:parquet-avro:1.17.1")
    implementation("org.apache.parquet:parquet-common:1.17.1")
}
```

- [ ] **Step 3: Verify Gradle config compiles**

Run:
```bash
./gradlew :module-common:tasks --all 2>&1 | grep -i avro | head -5
```
Expected: `generateAvroJava` task appears in the list.

- [ ] **Step 4: Disable auto-generated Kotlin classes (we keep schema manual)**

Modify `module-common/build.gradle.kts` to add:

```kotlin
avro {
    sourceSet("main") {
        isAllowOptionalAccessors = true
        // DO NOT set generateBuilders or stringType - keep schema file as source of truth only
    }
}
```

Note: The plugin will still compile `.avsc` to Java under `build/generated-main-avro-java/`. This is fine — it gives us Java POJOs we can use later (PR #B), but we do NOT use them in PR #A.

- [ ] **Step 5: Run compileKotlin to verify**

Run:
```bash
./gradlew :module-common:compileKotlin --continue
```
Expected: BUILD SUCCESSFUL. No Java files generated yet because no .avsc exists.

- [ ] **Step 6: Commit**

```bash
git add module-common/build.gradle.kts module-common/src/main/avro/
git commit -m "build(module-common): add Avro plugin + parquet-avro for 1425

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task A2: Define ocid-mapping.avsc schema file

**Files:**
- Create: `module-common/src/main/avro/ocid-mapping.avsc`

- [ ] **Step 1: Write the schema file**

Create `module-common/src/main/avro/ocid-mapping.avsc`:

```json
{
  "type": "record",
  "name": "OcidMapping",
  "namespace": "maple.common.avro",
  "doc": "Single userIgn → ocid mapping record. Producer: OcidLookupPhase. Consumer: OcidLookupPhase.readCharacterNamesFromChunks. See issue 1425.",
  "fields": [
    {"name": "userIgn", "type": "string", "doc": "User in-game name. Frozen field ID: 1."},
    {"name": "ocid", "type": ["null", "string"], "default": null, "doc": "Nexon character ID. Null until lookup completes. Frozen field ID: 2."},
    {"name": "schema_version", "type": "int", "default": 1, "doc": "Schema version of this record. Bump on backward-incompatible change. Frozen field ID: 3."}
  ]
}
```

- [ ] **Step 2: Verify Gradle generates Java POJOs**

Run:
```bash
./gradlew :module-common:generateAvroJava
```
Expected: BUILD SUCCESSFUL. `module-common/build/generated-main-avro-java/maple/common/avro/OcidMapping.java` exists.

- [ ] **Step 3: Verify POJO is importable from Kotlin**

Run:
```bash
./gradlew :module-common:compileKotlin
```
Expected: BUILD SUCCESSFUL. (No Kotlin code uses the POJO yet — just verifying compile path.)

- [ ] **Step 4: Commit**

```bash
git add module-common/src/main/avro/ocid-mapping.avsc
git commit -m "feat(module-common): add ocid-mapping.avsc schema (1425)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task A3: Define snapshot.avsc schema file

**Files:**
- Create: `module-common/src/main/avro/snapshot.avsc`

- [ ] **Step 1: Write the schema file**

Create `module-common/src/main/avro/snapshot.avsc`:

```json
{
  "type": "record",
  "name": "SnapshotChunk",
  "namespace": "maple.common.avro",
  "doc": "External API snapshot chunk record. Producer: PhaseRunners. Consumer: Calculator.SnapshotChunkProcessor, Synchronizer. See issue 1425.",
  "fields": [
    {"name": "runId", "type": "string", "doc": "External API run identifier. Frozen field ID: 1."},
    {"name": "endpoint", "type": {" "type": "enum", "name": "Endpoint", "symbols": ["RANKING", "CHARACTER_BASIC", "ITEM_EQUIPMENT", "OCID_LOOKUP"]}, "doc": "Nexon endpoint. Frozen field ID: 2."},
    {"name": "status", "type": {" "type": "enum", "name": "FetchStatus", "symbols": ["SUCCESS", "FAILURE", "PRE_SERIALIZED", "CLOSE_SIGNAL"]}, "doc": "Record variant. Frozen field ID: 3."},
    {"name": "body", "type": ["null", "string"], "default": null, "doc": "Inline JSON body when success and small. Frozen field ID: 4."},
    {"name": "bodyBytes", "type": ["null", "bytes"], "default": null, "doc": "Base64-decoded body when large. Frozen field ID: 5."},
    {"name": "httpStatus", "type": ["null", "int"], "default": null, "doc": "HTTP status from Nexon. Frozen field ID: 6."},
    {"name": "errorMessage", "type": ["null", "string"], "default": null, "doc": "Failure error message. Frozen field ID: 7."},
    {"name": "schema_version", "type": "int", "default": 1, "doc": "Schema version. Frozen field ID: 8."}
  ]
}
```

Note: Nested enum types in Avro require full declaration. If the Gradle plugin rejects the inline enum syntax, split into separate enum `.avsc` files (`Endpoint.avsc`, `FetchStatus.avsc`) referenced by `{"type": "Endpoint"}`.

- [ ] **Step 2: Generate + verify**

Run:
```bash
./gradlew :module-common:generateAvroJava
./gradlew :module-common:compileKotlin
```
Expected: BUILD SUCCESSFUL. If enum syntax rejected, see Step 1 fallback.

- [ ] **Step 3: Commit**

```bash
git add module-common/src/main/avro/snapshot.avsc
git commit -m "feat(module-common): add snapshot.avsc schema (1425)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task A4: Define result.avsc schema file

**Files:**
- Create: `module-common/src/main/avro/result.avsc`

- [ ] **Step 1: Write the schema file**

Create `module-common/src/main/avro/result.avsc`:

```json
{
  "type": "record",
  "name": "CalculationResult",
  "namespace": "maple.common.avro",
  "doc": "Calculator output. Producer: Calculator. Consumer: Synchronizer. See issue 1425.",
  "fields": [
    {"name": "runId", "type": "string", "doc": "Calculator run identifier. Frozen field ID: 1."},
    {"name": "ocid", "type": "string", "doc": "Character ID. Frozen field ID: 2."},
    {"name": "expectedMin", "type": ["null", "long"], "default": null, "doc": "Expected cost min in mesos. Frozen field ID: 3."},
    {"name": "expectedMax", "type": ["null", "long"], "default": null, "doc": "Expected cost max in mesos. Frozen field ID: 4."},
    {"name": "expectedCost", "type": ["null", "double"], "default": null, "doc": "Expected cost (point estimate). NOTE: consider Decimal in future migration. Frozen field ID: 5."},
    {"name": "potentialOptions", "type": {" "type": "array", "items": ["null", "string"], "default": null}, "default": null, "doc": "Potential upgrade options (nullable elements). Frozen field ID: 6."},
    {"name": "schema_version", "type": "int", "default": 1, "doc": "Schema version. Frozen field ID: 7."}
  ]
}
```

- [ ] **Step 2: Generate + verify**

Run:
```bash
./gradlew :module-common:generateAvroJava
./gradlew :module-common:compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add module-common/src/main/avro/result.avsc
git commit -m "feat(module-common): add result.avsc schema (1425)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task A5: PR #A readiness — verify all schemas compile + ship

- [ ] **Step 1: Run full module-common test suite**

Run:
```bash
./gradlew :module-common:test --continue
```
Expected: BUILD SUCCESSFUL. No test regressions.

- [ ] **Step 2: Verify no `module-infra` imports the new POJOs**

Run:
```bash
grep -rE "maple\.common\.avro\.(OcidMapping|SnapshotChunk|CalculationResult)" module-infra module-external-api module-calculator module-synchronizer 2>&1 | head -10
```
Expected: empty (no consumers in this PR — schema files only).

- [ ] **Step 3: Create PR #A**

Run:
```bash
git checkout develop && git pull
git checkout -b feature/issue-1425-schema-formalization
git push -u origin feature/issue-1425-schema-formalization
gh pr create --base develop --title "feat(module-common): add Avro schemas for snapshot/result/ocid-mapping (1425)" --body "$(cat <<'EOF'
## Summary
- Adds Avro plugin + parquet-avro dependency to module-common
- Defines `.avsc` schema files for all 3 artifact types (no producer/reader rewrite)

## Out of scope (per issue 1425)
- Migration of existing JSONL artifacts
- Producer/reader validation against schemas (separate issue 1423)
- Schema registry server (deferred)

## Verification
- `./gradlew :module-common:generateAvroJava` produces POJOs
- `./gradlew :module-common:test` passes
- No consumer imports the new POJOs

Closes #1425

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Merge PR #A + close issue 1425**

Run:
```bash
gh pr merge <NUMBER> --merge --delete-branch
gh issue close 1425 --comment "Closed via PR #<NUMBER>. Schema files added; no migration in this PR per scope."
```

- [ ] **Step 5: Return to develop**

```bash
git checkout develop && git pull
```

---

## PR #B: Issues 1423/1424 — Parquet+ZSTD PoC on OCID Mapping

### Task B1: Add parquet-avro dependency to module-external-api

**Files:**
- Modify: `module-external-api/build.gradle.kts`

- [ ] **Step 1: Inspect current module-external-api deps**

Run:
```bash
grep -n "implementation\|api " module-external-api/build.gradle.kts | head -20
```

- [ ] **Step 2: Add parquet-avro dependencies**

Modify `module-external-api/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":module-common"))
    // existing deps...

    // 1423 PoC
    implementation("org.apache.parquet:parquet-avro:1.17.1")
    implementation("org.apache.parquet:parquet-common:1.17.1")
    implementation("com.github.luben:zstd-jni:1.5.6-1")
}
```

- [ ] **Step 3: Compile**

Run:
```bash
./gradlew :module-external-api:compileKotlin --continue
```
Expected: BUILD SUCCESSFUL. No conflict with existing libs.

- [ ] **Step 4: Commit**

```bash
git add module-external-api/build.gradle.kts
git commit -m "build(ext-api): add parquet-avro + zstd-jni for 1423 PoC

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task B2: Implement ParquetOcidMappingWriter (TDD)

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriter.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriterTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriterTest.kt`:

```kotlin
package maple.externalapi.poc.parquet

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.avro.AvroParquetReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParquetOcidMappingWriterTest {

    @Test
    fun `writes records to parquet and reader round-trips`(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("ocid-mapping.parquet").toFile()

        // When
        ParquetOcidMappingWriter(outputFile).use { writer ->
            writer.write("캐넌1", "ocid-aaa")
            writer.write("캐넌2", "ocid-bbb")
            writer.write("캐넌3", null)  // null OCID
        }

        // Then
        assertEquals(true, outputFile.exists())
        assertEquals(true, outputFile.length() > 0, "Parquet file should not be empty")

        // Read back
        val records = readAll(outputFile)
        assertEquals(3, records.size)
        assertEquals("캐넌1", records[0].get("userIgn").toString())
        assertEquals("ocid-aaa", records[0].get("ocid").toString())
        assertEquals(null, records[2].get("ocid"))
        assertEquals(1, records[0].get("schema_version"))
    }

    private fun readAll(file: File): List<GenericRecord> {
        val reader: ParquetReader<GenericRecord> = AvroParquetReader
            .builder<GenericRecord>(org.apache.parquet.hadoop.util.HadoopInputFile.fromFile(file, org.apache.hadoop.conf.Configuration()))
            .build()
        return generateSequence { reader.read() }.toList().also { reader.close() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetOcidMappingWriterTest" --continue
```
Expected: COMPILATION FAILURE (ParquetOcidMappingWriter doesn't exist).

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriter.kt`:

```kotlin
package maple.externalapi.poc.parquet

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import java.io.Closeable
import java.io.File

/**
 * Side-by-side PoC writer for OCID mapping in Parquet+ZSTD format.
 * See issue 1423. Does NOT replace JSONL.gz output.
 */
class ParquetOcidMappingWriter(
    outputFile: File,
    schema: Schema = OCID_MAPPING_SCHEMA,
) : Closeable {
    private val writer: ParquetWriter<GenericRecord> = AvroParquetWriter
        .builder<GenericRecord>(HadoopOutputFile.fromFile(outputFile, org.apache.hadoop.conf.Configuration()))
        .withSchema(schema)
        .withCompressionCodec(CompressionCodecName.ZSTD)
        .withCompressionLevel(5)
        .build()

    fun write(userIgn: String, ocid: String?) {
        val record: GenericRecord = GenericData.Record(OCID_MAPPING_SCHEMA).apply {
            put("userIgn", userIgn)
            put("ocid", ocid)
            put("schema_version", 1)
        }
        writer.write(record)
    }

    override fun close() {
        writer.close()
    }

    companion object {
        val OCID_MAPPING_SCHEMA: Schema = Schema.Parser().parse(
            """
            {
              "type": "record",
              "name": "OcidMapping",
              "namespace": "maple.common.avro",
              "fields": [
                {"name": "userIgn", "type": "string"},
                {"name": "ocid", "type": ["null", "string"], "default": null},
                {"name": "schema_version", "type": "int", "default": 1}
              ]
            }
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetOcidMappingWriterTest" --continue
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriter.kt \
        module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingWriterTest.kt
git commit -m "feat(ext-api): add ParquetOcidMappingWriter PoC (1423)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task B3: Implement ParquetOcidMappingReader (TDD)

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReader.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReaderTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReaderTest.kt`:

```kotlin
package maple.externalapi.poc.parquet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ParquetOcidMappingReaderTest {

    @Test
    fun `streams records one at a time`(@TempDir tempDir: Path) {
        val outputFile = tempDir.resolve("ocid-mapping.parquet").toFile()
        val expected = listOf(
            Triple("캐넌1", "ocid-aaa", false),
            Triple("캐넌2", null, true),  // null OCID
        )
        ParquetOcidMappingWriter(outputFile).use { w ->
            for ((ign, ocid, _) in expected) w.write(ign, ocid)
        }

        // When
        val read = ParquetOcidMappingReader(outputFile).use { r ->
            generateSequence { r.read() }.toList()
        }

        // Then
        assertEquals(expected.size, read.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, read[i].userIgn)
            assertEquals(expected[i].second, read[i].ocid)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetOcidMappingReaderTest" --continue
```
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReader.kt`:

```kotlin
package maple.externalapi.poc.parquet

import org.apache.avro.generic.GenericRecord
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.io.Closeable
import java.io.File

/**
 * Streaming reader for Parquet OCID mapping. See issue 1423.
 */
class ParquetOcidMappingReader(
    inputFile: File,
) : Closeable {
    private val reader: ParquetReader<GenericRecord> = AvroParquetReader
        .builder<GenericRecord>(HadoopInputFile.fromFile(inputFile, org.apache.hadoop.conf.Configuration()))
        .build()

    data class OcidRecord(val userIgn: String, val ocid: String?)

    fun read(): OcidRecord? {
        val record = reader.read() ?: return null
        return OcidRecord(
            userIgn = record.get("userIgn").toString(),
            ocid = record.get("ocid")?.toString(),
        )
    }

    override fun close() {
        reader.close()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetOcidMappingReaderTest" --continue
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReader.kt \
        module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetOcidMappingReaderTest.kt
git commit -m "feat(ext-api): add ParquetOcidMappingReader PoC (1423)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task B4: Wire side-by-side Parquet write into OcidLookupPhase

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/loop/phases/OcidLookupPhase.kt` (locate via `find` — exact lines depend on current state)

- [ ] **Step 1: Locate the OCID mapping write site**

Run:
```bash
grep -n "ocid-mapping" module-external-api/src/main/kotlin/maple/externalapi/loop/phases/OcidLookupPhase.kt
```
Expected: identifies the write site (typically around line 200-280 based on prior work).

- [ ] **Step 2: Write failing test**

Find existing OcidLookupPhaseTest and add a test verifying both formats written. (Locate via `find module-external-api/src/test -name "OcidLookupPhaseTest*"`)

Pattern (insert at end of test class):

```kotlin
@Test
fun `writes side-by-side Parquet output for benchmark`() {
    // Setup mocks for OcidLookupPhase dependencies
    // Run phase
    // Assert: gzip output exists AND parquet output exists
    // Assert: both contain same userIgn→ocid mapping
}
```

(Specific test code depends on existing test structure — fill in matching existing pattern.)

- [ ] **Step 3: Run test to verify it fails**

Run:
```bash
./gradlew :module-external-api:test --tests "*OcidLookupPhase*side-by-side*" --continue
```
Expected: FAIL.

- [ ] **Step 4: Modify OcidLookupPhase to write side-by-side**

After the existing gzip JSONL write completes (typically a `.use { writer → ... }` block), add:

```kotlin
// 1423 PoC: side-by-side Parquet write. NEVER replaces JSONL output.
val parquetFile = File(parquetTempDir, "ocid-mapping-parquet-$runId.parquet")
ParquetOcidMappingWriter(parquetFile).use { pWriter ->
    for ((userIgn, ocid) in mappingPairs) {
        pWriter.write(userIgn, ocid)
    }
}
objectStorage.putStream(
    key = "ocid-mapping-parquet/ocid-mapping-$runId.parquet",
    inputStream = parquetFile.inputStream(),
)
parquetFile.delete()
```

The exact injection point and `mappingPairs` variable name depend on the existing code structure — adjust to match. Wrap in a `try { ... } catch (e: Exception) { log.warn("Parquet PoC write failed (non-fatal)", e) }` so the JSONL production path is unaffected.

- [ ] **Step 5: Run OcidLookupPhase tests**

Run:
```bash
./gradlew :module-external-api:test --tests "*OcidLookupPhase*" --continue
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/loop/phases/OcidLookupPhase.kt \
        module-external-api/src/test/kotlin/maple/externalapi/loop/phases/OcidLookupPhaseTest.kt
git commit -m "feat(ext-api): side-by-side Parquet write in OcidLookupPhase (1423)

PoC only — JSONL.gz output unchanged. Failures in Parquet write
are logged warn-level and do not affect production JSONL pipeline.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task B5: Implement benchmark harness

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetBenchmark.kt`
- Create: `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetBenchmarkTest.kt`

- [ ] **Step 1: Write failing test**

Create `module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetBenchmarkTest.kt`:

```kotlin
package maple.externalapi.poc.parquet

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue

class ParquetBenchmarkTest {

    @Test
    fun `produces numeric benchmark output for both formats`(@TempDir tempDir: Path) {
        val records = (1..10_000).map { i -> "ign-$i" to if (i % 100 == 0) null else "ocid-$i" }

        val gzipFile = tempDir.resolve("gzip.jsonl.gz").toFile()
        val parquetFile = tempDir.resolve("parquet.parquet").toFile()

        val result = ParquetBenchmark.run(records, gzipFile, parquetFile)

        // Sanity: numbers present + parquet smaller than gzip is OK
        assertTrue(result.gzip.compressedBytes > 0)
        assertTrue(result.parquet.compressedBytes > 0)
        assertTrue(result.gzip.writeRecordsPerSecond > 0)
        assertTrue(result.parquet.writeRecordsPerSecond > 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetBenchmarkTest" --continue
```
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Write minimal implementation**

Create `module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetBenchmark.kt`:

```kotlin
package maple.externalapi.poc.parquet

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

/**
 * Side-by-side benchmark harness for Parquet+ZSTD vs gzip+JSONL on OCID mapping.
 * See issue 1423. Read-only: does NOT modify production artifact paths.
 */
object ParquetBenchmark {

    data class FormatMetrics(
        val compressedBytes: Long,
        val writeMillis: Long,
        val readMillis: Long,
        val writeRecordsPerSecond: Long,
    )

    data class Comparison(val gzip: FormatMetrics, val parquet: FormatMetrics)

    fun run(
        records: List<Pair<String, String?>>,
        gzipFile: File,
        parquetFile: File,
    ): Comparison {
        // Write gzip
        val gzipWriteStart = System.currentTimeMillis()
        GZIPOutputStream(FileOutputStream(gzipFile)).use { gz ->
            OutputStreamWriter(gz, Charsets.UTF_8).use { w ->
                for ((ign, ocid) in records) {
                    w.write("{\"userIgn\":\"$ign\",\"ocid\":${if (ocid != null) "\"$ocid\"" else "null"}}\n")
                }
            }
        }
        val gzipWriteMs = System.currentTimeMillis() - gzipWriteStart

        val gzipReadStart = System.currentTimeMillis()
        gzipFile.inputStream().bufferedReader().useLines { it.count() }
        val gzipReadMs = System.currentTimeMillis() - gzipReadStart

        // Write parquet
        val parquetWriteStart = System.currentTimeMillis()
        ParquetOcidMappingWriter(parquetFile).use { p ->
            for ((ign, ocid) in records) p.write(ign, ocid)
        }
        val parquetWriteMs = System.currentTimeMillis() - parquetWriteStart

        val parquetReadStart = System.currentTimeMillis()
        ParquetOcidMappingReader(parquetFile).use { r ->
            while (r.read() != null) { /* drain */ }
        }
        val parquetReadMs = System.currentTimeMillis() - parquetReadStart

        return Comparison(
            gzip = FormatMetrics(
                compressedBytes = gzipFile.length(),
                writeMillis = gzipWriteMs,
                readMillis = gzipReadMs,
                writeRecordsPerSecond = if (gzipWriteMs > 0) records.size * 1000L / gzipWriteMs else -1,
            ),
            parquet = FormatMetrics(
                compressedBytes = parquetFile.length(),
                writeMillis = parquetWriteMs,
                readMillis = parquetReadMs,
                writeRecordsPerSecond = if (parquetWriteMs > 0) records.size * 1000L / parquetWriteMs else -1,
            ),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetBenchmarkTest" --continue
```
Expected: PASS.

- [ ] **Step 5: Run benchmark on real data (10K records for unit, then run full OCID phase locally)**

Run:
```bash
./gradlew :module-external-api:test --tests "ParquetBenchmarkTest" --info 2>&1 | tail -20
```
Expected: numeric output. For real-data benchmark, see follow-up run after deploy.

- [ ] **Step 6: Commit**

```bash
git add module-external-api/src/main/kotlin/maple/externalapi/poc/parquet/ParquetBenchmark.kt \
        module-external-api/src/test/kotlin/maple/externalapi/poc/parquet/ParquetBenchmarkTest.kt
git commit -m "feat(ext-api): add Parquet+ZSTD benchmark harness (1423)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task B6: PR #B readiness — ship, merge, close 1423+1424

- [ ] **Step 1: Run full test suite**

Run:
```bash
./gradlew :module-external-api:test --continue
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Write benchmark report to docs/**

Create `docs/24_Troubleshooting_Casebook/07_parquet_poc_benchmark.md` with output from Step B5.5. Format: simple table + recommendation ("keep JSONL" / "migrate OCID mapping" / "migrate all").

- [ ] **Step 3: Create PR #B**

```bash
git checkout develop && git pull
git checkout -b feature/issue-1423-parquet-poc
git push -u origin feature/issue-1423-parquet-poc
gh pr create --base develop --title "feat(ext-api): Parquet+ZSTD PoC for OCID mapping (1423)" --body "$(cat <<'EOF'
## Summary
- Adds side-by-side Parquet+ZSTD writer/reader for OCID mapping
- Gzip JSONL output unchanged (production-safe)
- Benchmark harness with measurement report

## Out of scope (per issue 1423)
- Migration of snapshot/result artifacts
- Replacing JSONL output anywhere
- Iceberg adoption

## Verification
- All tests pass
- Benchmark numbers in `docs/24_Troubleshooting_Casebook/07_parquet_poc_benchmark.md`
- No production data path altered

Closes #1423, #1424

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Merge + close**

```bash
gh pr merge <NUMBER> --merge --delete-branch
gh issue close 1423 --comment "Closed via PR #<NUMBER>. Parquet PoC complete; production JSONL unchanged."
gh issue close 1424 --comment "Duplicate of #1423; closed together."
git checkout develop && git pull
```

---

## PR #C: Issue 1427 — Small-File Problem Investigation

### Task C1: Measurement harness — MinIO LIST latency + lifecycle rule cost

**Files:**
- Create: `docs/02_Investigations/2026-06-28-small-file-measurement.md` (output report)

- [ ] **Step 1: Measure MinIO LIST API latency**

Run (assumes MinIO mc alias already configured per local env):

```bash
mc alias set local "$MINIO_ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" 2>/dev/null
mc ls --recursive "local/$MINIO_BUCKET/runs/" 2>&1 | wc -l  # baseline file count
echo "---"
for i in 1 2 3 4 5; do
  time mc ls --recursive "local/$MINIO_BUCKET/runs/" 2>&1 > /tmp/list-run-$i.txt
done
```

Capture p50/p95/p99 from output. Write into `docs/02_Investigations/2026-06-28-small-file-measurement.md`.

- [ ] **Step 2: Count distinct files per full-day replay**

Run:
```bash
mc ls --recursive "local/$MINIO_BUCKET/runs/$(date -d 'yesterday' +%Y-%m-%d)/" 2>&1 | wc -l
```
Record in report.

- [ ] **Step 3: Lifecycle rule evaluation count**

Inspect MinIO lifecycle config:
```bash
mc ilm ls "local/$MINIO_BUCKET"
```
Estimate cost: per-file evaluation × N files/day.

- [ ] **Step 4: Read existing ChunkFileManager + CalculationResultWriter**

```bash
grep -n "chunkSize\|maxRecords\|targetBytes" module-external-api/src/main/kotlin/.../ChunkFileManager.kt
grep -n "chunkSize\|maxRecords\|targetBytes" module-calculator/src/main/kotlin/.../CalculationResultWriter.kt
```
Record current `chunkSize` config + write path in report.

- [ ] **Step 5: Commit measurement report**

```bash
git add docs/02_Investigations/2026-06-28-small-file-measurement.md
git commit -m "docs: small-file measurement report (1427)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task C2: Consolidation-point analysis + recommendation ADR

**Files:**
- Create: `docs/01_ADR/ADR-XXX-small-file-resolution.md`

- [ ] **Step 1: Write analysis section in ADR**

Use ADR template from `.claude/rules/adr-conventions.md`. Sections:
- Background: 120K files/day operational cost
- Decision: chosen consolidation approach (write-time vs flush-time vs post-write)
- Sensitivity: file count, latency, Kafka event ordering
- Trade-off: per consolidation approach, fill the comparison table
- Risk: blast radius, replay safety, ordering guarantees

- [ ] **Step 2: Pick recommendation**

Best prac recommendation: **flush-time merge** (roll multiple chunks into one before publishing manifest, Kafka event count = merge-batch count). Write 1 roll-up file per N chunks. Trade-off: slightly higher write memory (held until flush), but no Kafka event ordering change and no post-write job needed.

- [ ] **Step 3: Write ADR**

Fill in all 5 sections per template. Recommended decision: "Roll N chunks into one before manifest publish. N = 10 (config: external-api.chunk.rollup-size: 10). Expected reduction: 120K → 12K files. Kafka events: same count (one per merged file)."

- [ ] **Step 4: Commit ADR**

```bash
git add docs/01_ADR/ADR-XXX-small-file-resolution.md
git commit -m "docs(adr): small-file resolution strategy (1427)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task C3: PR #C readiness — ship, merge, close 1427

- [ ] **Step 1: Create PR #C**

```bash
git checkout develop && git pull
git checkout -b feature/issue-1427-small-file-investigation
git push -u origin feature/issue-1427-small-file-investigation
gh pr create --base develop --title "docs: small-file problem investigation + ADR (1427)" --body "$(cat <<'EOF'
## Summary
- MinIO LIST latency measurement
- Distinct-file count per replay scenario
- Consolidation-point analysis (4 alternatives)
- ADR recommending flush-time rollup

## Out of scope (per issue 1427)
- Actual write-path changes (separate implementation issue post-investigation)
- Iceberg compaction (separate issue 1426)

Closes #1427

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Merge + close**

```bash
gh pr merge <NUMBER> --merge --delete-branch
gh issue close 1427 --comment "Closed via PR #<NUMBER>. Investigation complete; implementation tracked in follow-up."
git checkout develop && git pull
```

---

## PR #D: Issue 1426 — Iceberg Readiness Assessment

### Task D1: Table schema + partition spec

**Files:**
- Create: `docs/superpowers/specs/2026-06-28-iceberg-adoption-design.md`

- [ ] **Step 1: Write table schema section**

Use spec template. Section "Table Schema":
- `raw_snapshot`: fields from `snapshot.avsc` (1425), partition `days(fetched_at) + identity(endpoint)`
- `calc_result`: fields from `result.avsc` (1425), partition `bucket(16, ocid)`
- `ocid_mapping`: fields from `ocid-mapping.avsc` (1425), partition `identity(user_ign)`

- [ ] **Step 2: Write sort-order recommendations**

Per table, recommend sort order:
- `raw_snapshot`: `[(ocid ASC), (fetched_at DESC)]`
- `calc_result`: `[(expectedCost ASC NULLS LAST)]`
- `ocid_mapping`: `[(ocid ASC NULLS LAST)]`

- [ ] **Step 3: Write catalog comparison**

Section "Catalog":
- REST catalog: pros (MinIO compat, Postgres backend), cons (extra service)
- Hive Metastore: pros (mature), cons (heavy, MySQL/Postgres dep)
- AWS Glue: blocked (no AWS)
- JDBC (Postgres): pros (no extra service), cons (scalability limits at >100K files)

Recommendation: **REST catalog with Postgres backend**, ~1-day setup, 0.5 FTE ongoing.

- [ ] **Step 4: Write compaction strategy**

Section "Compaction":
- Iceberg `rewrite_data_files` (Spark) vs Java SDK `RewriteFiles` API
- Target 128 MB/file
- Daily Airflow DAG
- Estimated: 120K → ~469 files (250x reduction)

- [ ] **Step 5: Write schema-evolution risk register**

Section "Schema evolution risks":
- Field rename: blocked (Avro field names = IDs)
- Backward-incompatible change: requires `schema_version` bump + dual-write window
- Field add: easy (default values)
- Field remove: requires new schema version + grace period

- [ ] **Step 6: Write cost/benefit estimate**

Section "Cost/benefit":
- Setup: ~1 FTE-week (catalog + first table)
- Ongoing: 0.5 FTE (compaction + schema migrations)
- Benefit: SQL-on-lakehouse, time-travel, ML feature pipelines (gated by ADR-735 T7/T8)

- [ ] **Step 7: Commit spec**

```bash
git add docs/superpowers/specs/2026-06-28-iceberg-adoption-design.md
git commit -m "docs(spec): Iceberg adoption design (1426, forward-looking)

NOT executed by this issue — ADR-735 Phase 3 trigger conditions
T7 (MLlib/iterative) or T8 (data lakehouse contract) gate actual
adoption. This spec is the readiness report.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task D2: PR #D readiness — ship, merge, close 1426

- [ ] **Step 1: Create PR #D**

```bash
git checkout develop && git pull
git checkout -b feature/issue-1426-iceberg-readiness
git push -u origin feature/issue-1426-iceberg-readiness
gh pr create --base develop --title "docs: Iceberg readiness assessment (1426)" --body "$(cat <<'EOF'
## Summary
- Iceberg table schema + partition spec per artifact
- Catalog comparison + recommendation (REST + Postgres)
- Compaction strategy (rewrite_data_files daily DAG)
- Schema evolution risk register
- Cost/benefit estimate

## Out of scope (per issue 1426)
- Iceberg adoption itself (separate post-assessment issue, gated by ADR-735 T7/T8)
- Spark/Trino/DuckDB engine selection (separate ADR)
- Schema changes (we reuse 1425 Avro schemas)

Closes #1426

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Merge + close**

```bash
gh pr merge <NUMBER> --merge --delete-branch
gh issue close 1426 --comment "Closed via PR #<NUMBER>. Readiness assessment complete; actual adoption gated on ADR-735 Phase 3 trigger."
git checkout develop && git pull
```

---

## Self-Review

**1. Spec coverage:**
- ✓ 1425 schema files (Tasks A1-A4)
- ✓ 1423 Parquet PoC writer (B2) + reader (B3) + side-by-side wire (B4) + benchmark (B5)
- ✓ 1427 small-file measurement (C1) + recommendation ADR (C2)
- ✓ 1426 Iceberg readiness (D1)

**2. Placeholder scan:** No TBD/TODO/fill-in-later.

**3. Type consistency:**
- `OCID_MAPPING_SCHEMA` defined in B2 + reused implicitly (read uses same .avsc fields)
- `OcidRecord(userIgn, ocid)` data class consistent across B3 reader and benchmark
- `ParquetBenchmark.run` signature matches B5 test usage
- All `git commit` invocations have Co-Authored-By

**4. Order:** 1425 → 1423 → 1427 → 1426 (matches spec execution order).