# #1087 — External-API Phase Parser Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove `ObjectMapper` and `GZIPInputStream` direct usage from `OcidLookupPhase` and `RankingFetchPhase` by extracting 3 parser/reader classes.

**Architecture:** Each parser/reader owns its own `ObjectMapper`/`GZIPInputStream` use; phases become orchestration-only (HTTP calls, sink writes, phase state transitions).

**Tech Stack:** Kotlin, Jackson, GZIP, Java NIO, Gradle multi-module.

---

## File Structure

| File | Change |
|---|---|
| `module-external-api/src/main/kotlin/maple/externalapi/parser/OcidResponseParser.kt` | NEW — Nexon HTTP JSON → `String` OCID |
| `module-external-api/src/main/kotlin/maple/externalapi/reader/CharacterNameReader.kt` | NEW — GZIP file → distinct character names |
| `module-external-api/src/main/kotlin/maple/externalapi/parser/RankingEntryParser.kt` | NEW — Nexon HTTP JSON → `RankingEntry` list |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/OcidLookupPhase.kt` | MODIFIED — drops 2 ObjectMapper call sites, 1 GZIP usage |
| `module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/RankingFetchPhase.kt` | MODIFIED — drops 1 ObjectMapper call site |

---

## Task 1: Create `OcidResponseParser`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/parser/OcidResponseParser.kt`

- [ ] **Step 1: Create the parser**

```kotlin
package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class OcidResponseParser(
    private val objectMapper: ObjectMapper,
) {
    /**
     * Extract OCID from a Nexon character-name lookup response.
     * Throws [IllegalStateException] if the response is missing the OCID field —
     * caller treats that as a not-found path.
     */
    fun extractOcid(responseBody: String): String {
        val root = objectMapper.readTree(responseBody)
        return root.path("ocid").asText()
            .takeIf { it.isNotBlank() }
            ?: error("OCID missing in response")
    }

    /** Re-serialize an HTTP response for downstream chunk consumption. */
    fun reserialize(responseBody: String): ByteArray =
        objectMapper.writeValueAsBytes(objectMapper.readTree(responseBody))
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/parser/OcidResponseParser.kt
git commit -m "refactor(ext-api): add OcidResponseParser (#1087)"
```

---

## Task 2: Create `CharacterNameReader`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/reader/CharacterNameReader.kt`

- [ ] **Step 1: Create the reader**

```kotlin
package maple.externalapi.reader

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Reads a GZIP JSONL chunk of character-name entries and returns the
 * distinct set of names. Encapsulates GZIP + JSON parsing so the calling
 * phase does not import `GZIPInputStream` or `ObjectMapper`.
 */
@Component
class CharacterNameReader(
    private val objectMapper: ObjectMapper,
) {
    fun readDistinctNames(path: Path): Set<String> {
        if (!Files.exists(path)) return emptySet()
        val names = mutableSetOf<String>()
        GZIPInputStream(Files.newInputStream(path)).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val name = objectMapper.readTree(line).path("characterName").asText()
                if (name.isNotBlank()) names.add(name)
            }
        }
        return names
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/reader/CharacterNameReader.kt
git commit -m "refactor(ext-api): add CharacterNameReader (#1087)"
```

---

## Task 3: Create `RankingEntryParser`

**Files:**
- Create: `module-external-api/src/main/kotlin/maple/externalapi/parser/RankingEntryParser.kt`

- [ ] **Step 1: Create the parser**

```kotlin
package maple.externalapi.parser

import com.fasterxml.jackson.databind.ObjectMapper
import maple.expectation.core.dto.RankingEntry
import org.springframework.stereotype.Component

@Component
class RankingEntryParser(
    private val objectMapper: ObjectMapper,
) {
    fun parseEntries(responseBody: String): List<RankingEntry> {
        val root = objectMapper.readTree(responseBody)
        val ranking = root.path("ranking")
        if (!ranking.isArray) return emptyList()
        return ranking.map { node ->
            RankingEntry(
                rank = node.path("rank").asInt(),
                characterName = node.path("characterName").asText(),
                worldName = node.path("worldName").asText(),
                className = node.path("className").asText(),
                level = node.path("level").asInt(),
            )
        }
    }

    fun serializeToBytes(entry: RankingEntry): ByteArray =
        objectMapper.writeValueAsBytes(entry)
}
```

Note: `RankingEntry` may live elsewhere — verify and match. Adjust field names if the repo's DTO differs.

- [ ] **Step 2: Compile + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/parser/RankingEntryParser.kt
git commit -m "refactor(ext-api): add RankingEntryParser (#1087)"
```

---

## Task 4: Refactor `OcidLookupPhase`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/OcidLookupPhase.kt`

- [ ] **Step 1: Inject parsers, drop `ObjectMapper` field**

Replace `objectMapper: ObjectMapper` with `ocidResponseParser: OcidResponseParser` + `characterNameReader: CharacterNameReader`.

- [ ] **Step 2: Replace call sites**

In `fetchAndCollectOcidAsync`:
- `objectMapper.readTree(...)` → `ocidResponseParser.extractOcid(...)`
- `objectMapper.writeValueAsBytes(...)` → `ocidResponseParser.reserialize(...)`

In `readCharacterNamesFromChunks`:
- The whole GZIP + `readTree` + dedup loop → `characterNameReader.readDistinctNames(path)`. Add `import java.nio.file.Paths` if needed and convert string paths to `Path` via `Paths.get(...)`.

- [ ] **Step 3: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/OcidLookupPhase.kt
git commit -m "refactor(ext-api): OcidLookupPhase delegates to parsers (#1087)"
```

---

## Task 5: Refactor `RankingFetchPhase`

**Files:**
- Modify: `module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/RankingFetchPhase.kt`

- [ ] **Step 1: Inject `RankingEntryParser`, drop `ObjectMapper` field**

- [ ] **Step 2: Replace call sites in `submitRankingEntries`**

- `objectMapper.readTree(...)` → `rankingEntryParser.parseEntries(...)`
- `objectMapper.writeValueAsBytes(entry)` → `rankingEntryParser.serializeToBytes(entry)`

- [ ] **Step 3: Compile + test + commit**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:compileKotlin --console=plain
./gradlew :module-external-api:test --console=plain
git add module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/RankingFetchPhase.kt
git commit -m "refactor(ext-api): RankingFetchPhase delegates to RankingEntryParser (#1087)"
```

---

## Task 6: Final verification

- [ ] **Step 1: No `ObjectMapper` in modified phases**

```bash
grep -n "ObjectMapper\|GZIPInputStream" \
  module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/OcidLookupPhase.kt \
  module-external-api/src/main/kotlin/maple/externalapi/snapshot/phase/RankingFetchPhase.kt
```

Expected: no output.

- [ ] **Step 2: Full module test sweep**

```bash
cd /home/maple/probabilistic-valuation-engine/.worktrees/refactor-batch-1
./gradlew :module-external-api:test --console=plain
```

Expected: all pass.

---

## Self-Review

- **Spec coverage:** Three spec components (response parser, file reader, ranking parser) covered by Tasks 1-3. Two phase refactors covered.
- **Placeholder scan:** `RankingEntry` import flagged for verification.
- **Type consistency:** `RankingEntry` field names assumed — implementer must match repo DTO.
