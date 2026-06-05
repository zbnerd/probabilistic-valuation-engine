# Synchronizer Repository SQL Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose 3 synchronizer repository batch methods to ≤30 lines each by extracting SQL to companion constants and parameter binding to helper functions.

**Architecture:** Mechanical cut-paste refactor. Each file gets SQL strings moved to `companion object` constants and `MapSqlParameterSource` building extracted to `private fun`. OcidMappingRepository additionally gets `Connection` extension functions for temp table lifecycle.

**Tech Stack:** Kotlin, Spring `NamedParameterJdbcTemplate`, PostgreSQL (unnest INSERT, COPY).

**Branch:** Create `refactor/933-synchronizer-repository-sql-decomposition` off `develop`. PR base: `develop`.

**Spec:** `docs/superpowers/specs/2026-06-05-933-synchronizer-repository-sql-decomposition-design.md`

---

## File structure

| File | Action | What changes |
|------|--------|--------------|
| `module-synchronizer/.../repository/CharacterBasicRepository.kt` | Modify | SQL → companion constant, params → `buildUpsertParams()` |
| `module-synchronizer/.../repository/EquipmentReadModelRepository.kt` | Modify | SQL → companion constant, params → `buildUpsertParams()` |
| `module-synchronizer/.../repository/OcidMappingRepository.kt` | Modify | 4 SQL constants + 3 `Connection` extensions |

No new files. No test changes.

---

## Task 1: Create branch off develop

**Files:** none

- [ ] **Step 1.1: Fetch develop and create branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git checkout develop
git pull origin develop
git checkout -b refactor/933-synchronizer-repository-sql-decomposition
```

- [ ] **Step 1.2: Verify branch**

Run: `git branch --show-current`
Expected: `refactor/933-synchronizer-repository-sql-decomposition`

---

## Task 2: Decompose CharacterBasicRepository

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt`

- [ ] **Step 2.1: Replace the entire file with decomposed version**

Full replacement of `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt`:

```kotlin
package maple.synchronizer.repository

import maple.synchronizer.storage.BasicRecord
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class CharacterBasicRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val batchExecutor: JdbcChunkedBatchExecutor,
) {
    companion object {
        private const val SUB_BATCH_SIZE = 100
        private const val DELETE_STALE_SQL =
            "DELETE FROM character_basic_read_model WHERE ocid = ANY(:ocids) AND NOT (user_ign = ANY(:userIgns))"
        private const val UPSERT_SQL = """
            INSERT INTO character_basic_read_model (
                user_ign, ocid, world_name, character_class, character_level,
                guild_name, basic_data, document_hash, source_run_id, source_chunk_id, updated_at
            )
            SELECT
                unnest(:userIgns), unnest(:ocids), unnest(:worldNames),
                unnest(:characterClasses), unnest(:characterLevels),
                unnest(:guildNames), unnest(:basicData), unnest(:documentHashes),
                :runId, :chunkId, now()
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = excluded.ocid,
                world_name = excluded.world_name,
                character_class = excluded.character_class,
                character_level = excluded.character_level,
                guild_name = excluded.guild_name,
                basic_data = excluded.basic_data,
                document_hash = excluded.document_hash,
                source_run_id = excluded.source_run_id,
                source_chunk_id = excluded.source_chunk_id,
                updated_at = now()
            WHERE character_basic_read_model.document_hash IS DISTINCT FROM excluded.document_hash
        """
    }

    fun bulkUpsert(runId: String, chunkId: String, records: List<BasicRecord>) {
        val deduped = records
            .groupBy { it.ocid }
            .map { it.value.first() }

        batchExecutor.execute(
            label = "CharacterBasic",
            itemLabel = "records",
            runId = runId,
            chunkId = chunkId,
            items = deduped,
            batchSize = SUB_BATCH_SIZE,
            upsertBatch = { batch -> upsertBatch(runId, chunkId, batch) },
        )
    }

    private fun upsertBatch(runId: String, chunkId: String, batch: List<BasicRecord>): Int {
        val ocids = batch.map { it.ocid }.toTypedArray()
        val userIgns = batch.map { it.userIgn }.toTypedArray()

        jdbc.update(
            DELETE_STALE_SQL,
            MapSqlParameterSource()
                .addValue("ocids", ocids)
                .addValue("userIgns", userIgns),
        )

        return jdbc.update(UPSERT_SQL, buildUpsertParams(runId, chunkId, batch))
    }

    private fun buildUpsertParams(runId: String, chunkId: String, batch: List<BasicRecord>): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("chunkId", chunkId)
            .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
            .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            .addValue("worldNames", batch.map { it.worldName }.toTypedArray())
            .addValue("characterClasses", batch.map { it.characterClass }.toTypedArray())
            .addValue("characterLevels", batch.map { it.characterLevel }.toTypedArray())
            .addValue("guildNames", batch.map { it.guildName }.toTypedArray())
            .addValue("basicData", batch.map { it.compressedBody }.toTypedArray())
            .addValue("documentHashes", batch.map { it.bodyHash }.toTypedArray())
    }
}
```

**What changed:**
- `DELETE_STALE_SQL` moved to companion constant (was inline string)
- `UPSERT_SQL` moved to companion constant (was local `val sql`)
- `buildUpsertParams()` extracted as private helper (was inline in `upsertBatch`)
- `upsertBatch()` reduced from 48 lines to ~12 lines

- [ ] **Step 2.2: Verify no inline SQL remains**

Run: `grep -n '"""' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt`
Expected: only the `UPSERT_SQL` definition in companion object (one occurrence)

- [ ] **Step 2.3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt
git commit -m "refactor(933): extract SQL constants and params helper from CharacterBasicRepository"
```

---

## Task 3: Decompose EquipmentReadModelRepository

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt`

- [ ] **Step 3.1: Replace the entire file with decomposed version**

Full replacement of `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt`:

```kotlin
package maple.synchronizer.repository

import maple.synchronizer.preparer.PreppedDocument
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class EquipmentReadModelRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val batchExecutor: JdbcChunkedBatchExecutor,
) {
    private val log = LoggerFactory.getLogger(EquipmentReadModelRepository::class.java)

    companion object {
        private const val SUB_BATCH_SIZE = 100
        private const val UPSERT_SQL = """
            INSERT INTO character_equipment_read_model (
                read_key, ocid, preset_no, user_ign, document, document_hash,
                total_cost, equipment_count, calculated_at,
                source_run_id, source_chunk_id, updated_at
            )
            SELECT
                unnest(:readKeys), unnest(:ocids), unnest(:presetNos),
                unnest(:userIgns), unnest(:documents), unnest(:documentHashes),
                unnest(:totalCosts), unnest(:equipmentCounts), unnest(:calculatedAts),
                :runId, :chunkId, now()
            ON CONFLICT (read_key) DO UPDATE SET
                user_ign = excluded.user_ign,
                document = excluded.document,
                document_hash = excluded.document_hash,
                total_cost = excluded.total_cost,
                equipment_count = excluded.equipment_count,
                calculated_at = excluded.calculated_at,
                source_run_id = excluded.source_run_id,
                source_chunk_id = excluded.source_chunk_id,
                updated_at = now()
            WHERE character_equipment_read_model.document_hash IS DISTINCT FROM excluded.document_hash
               OR character_equipment_read_model.user_ign IS DISTINCT FROM excluded.user_ign
        """
    }

    fun bulkUpsert(runId: String, chunkId: String, documents: List<PreppedDocument>) {
        val compSizes = documents.map { it.compressed.size }

        log.info("[Synchronizer] upsert start: docs={} batches={} batchSize={} " +
            "compressedBytes avg={} max={} total={} : runId={} chunkId={}",
            documents.size, documents.chunked(SUB_BATCH_SIZE).size, SUB_BATCH_SIZE,
            compSizes.average().toInt(), compSizes.max(), compSizes.sum(),
            runId, chunkId)

        batchExecutor.execute(
            label = "Synchronizer",
            itemLabel = "docs",
            runId = runId,
            chunkId = chunkId,
            items = documents,
            batchSize = SUB_BATCH_SIZE,
            upsertBatch = { batch -> upsertBatch(runId, chunkId, batch) },
        )
    }

    private fun upsertBatch(runId: String, chunkId: String, batch: List<PreppedDocument>): Int {
        return jdbc.update(UPSERT_SQL, buildUpsertParams(runId, chunkId, batch))
    }

    private fun buildUpsertParams(runId: String, chunkId: String, batch: List<PreppedDocument>): MapSqlParameterSource {
        return MapSqlParameterSource()
            .addValue("runId", runId)
            .addValue("chunkId", chunkId)
            .addValue("readKeys", batch.map { it.readKey }.toTypedArray())
            .addValue("ocids", batch.map { it.ocid }.toTypedArray())
            .addValue("presetNos", batch.map { it.presetNo }.toTypedArray())
            .addValue("userIgns", batch.map { it.userIgn }.toTypedArray())
            .addValue("documents", batch.map { it.compressed }.toTypedArray())
            .addValue("documentHashes", batch.map { it.documentHash }.toTypedArray())
            .addValue("totalCosts", batch.map { it.totalCost }.toTypedArray())
            .addValue("equipmentCounts", batch.map { it.equipmentCount }.toTypedArray())
            .addValue("calculatedAts", batch.map { it.calculatedAt }.toTypedArray())
    }
}
```

**What changed:**
- `UPSERT_SQL` moved to companion constant (was local `val sql`)
- `buildUpsertParams()` extracted as private helper (was inline in `upsertBatch`)
- `upsertBatch()` reduced from 40 lines to 3 lines

- [ ] **Step 3.2: Verify no inline SQL remains**

Run: `grep -n '"""' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt`
Expected: only the `UPSERT_SQL` definition in companion object (one occurrence)

- [ ] **Step 3.3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt
git commit -m "refactor(933): extract SQL constants and params helper from EquipmentReadModelRepository"
```

---

## Task 4: Decompose OcidMappingRepository

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`

- [ ] **Step 4.1: Replace the entire file with decomposed version**

Full replacement of `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`:

```kotlin
package maple.synchronizer.repository

import maple.synchronizer.storage.OcidMapping
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Connection

@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DROP_TMP_SQL = "DROP TABLE IF EXISTS tmp_ocid_mapping"
        private const val CREATE_TMP_SQL = "CREATE TEMP TABLE tmp_ocid_mapping (user_ign text NOT NULL, ocid text NOT NULL) ON COMMIT DROP"
        private const val DELETE_CONFLICT_SQL = """
            DELETE FROM game_character
            WHERE EXISTS (
                SELECT 1 FROM tmp_ocid_mapping t
                WHERE t.ocid = game_character.ocid AND t.user_ign != game_character.user_ign
            )
        """
        private const val MERGE_SQL = """
            INSERT INTO game_character (user_ign, ocid, updated_at)
            SELECT user_ign, ocid, now()
            FROM tmp_ocid_mapping
            ON CONFLICT (user_ign) DO UPDATE SET
                ocid = EXCLUDED.ocid,
                updated_at = now()
            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
        """
    }

    fun batchUpsert(mappings: List<OcidMapping>) {
        val affected: Int = jdbc.jdbcTemplate.execute(
            ConnectionCallback { con: Connection ->
                con.autoCommit = false
                runCatching {
                    createTempTable(con)
                    copyToTemp(con, mappings)
                    val rows = mergeFromTemp(con)
                    con.commit()
                    rows
                }.getOrElse { ex ->
                    runCatching { con.rollback() }
                    throw ex
                }
            }
        ) ?: 0

        log.info("[OcidMapping] DB upserted via COPY→merge: {} mappings, {} affected", mappings.size, affected)
    }

    private fun createTempTable(con: Connection) {
        con.createStatement().use { stmt ->
            stmt.execute(DROP_TMP_SQL)
            stmt.execute(CREATE_TMP_SQL)
        }
    }

    private fun copyToTemp(con: Connection, mappings: List<OcidMapping>) {
        val copyManager = CopyManager(con.unwrap(BaseConnection::class.java))
        val data = mappings.joinToString("\n") { "${it.userIgn}\t${it.ocid}" }
        copyManager.copyIn("COPY tmp_ocid_mapping (user_ign, ocid) FROM STDIN", data.reader())
    }

    private fun mergeFromTemp(con: Connection): Int {
        return con.createStatement().use { stmt ->
            stmt.executeUpdate(DELETE_CONFLICT_SQL)
            stmt.executeUpdate(MERGE_SQL)
        }
    }
}
```

**What changed:**
- 4 SQL strings moved to companion constants (`DROP_TMP_SQL`, `CREATE_TMP_SQL`, `DELETE_CONFLICT_SQL`, `MERGE_SQL`)
- `createTempTable(con)` — private helper for DROP + CREATE
- `copyToTemp(con, mappings)` — private helper for COPY
- `mergeFromTemp(con)` — private helper for DELETE + INSERT
- `batchUpsert()` reduced from 45 lines to ~16 lines

- [ ] **Step 4.2: Verify no inline SQL in method body**

Run: `grep -n 'executeUpdate\|stmt.execute' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`
Expected: all references use companion constant names (`DROP_TMP_SQL`, `CREATE_TMP_SQL`, `DELETE_CONFLICT_SQL`, `MERGE_SQL`). No raw SQL strings in method bodies.

- [ ] **Step 4.3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt
git commit -m "refactor(933): extract SQL constants and Connection extensions from OcidMappingRepository"
```

---

## Task 5: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 5.1: Compile synchronizer module**

Run: `./gradlew :module-synchronizer:compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 5.2: Run synchronizer tests**

Run: `./gradlew :module-synchronizer:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5.3: Verify method body line counts**

Run:
```bash
echo "=== CharacterBasicRepository.upsertBatch ==="
sed -n '/private fun upsertBatch/,/^    }/p' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/CharacterBasicRepository.kt | wc -l
echo "=== EquipmentReadModelRepository.upsertBatch ==="
sed -n '/private fun upsertBatch/,/^    }/p' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/EquipmentReadModelRepository.kt | wc -l
echo "=== OcidMappingRepository.batchUpsert ==="
sed -n '/fun batchUpsert/,/^    }/p' module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt | wc -l
```
Expected: all ≤30 lines.

---

## Task 6: PR

**Files:** none

- [ ] **Step 6.1: Push branch**

```bash
git push -u origin refactor/933-synchronizer-repository-sql-decomposition
```

- [ ] **Step 6.2: Create PR with `gh`**

```bash
gh pr create \
  --base develop \
  --head refactor/933-synchronizer-repository-sql-decomposition \
  --title "refactor(933): decompose synchronizer repository SQL methods" \
  --body "$(cat <<'EOF'
## Summary
- Extract SQL strings to companion object constants in 3 repositories.
- Extract parameter binding to `buildUpsertParams()` helpers (CharacterBasic, Equipment).
- Extract temp table lifecycle to `Connection` extension functions (OcidMapping).
- All batch methods now ≤30 lines. Zero behavioral change.

## Files
- Modified: `CharacterBasicRepository.kt`, `EquipmentReadModelRepository.kt`, `OcidMappingRepository.kt`

## Verification
- [x] `./gradlew :module-synchronizer:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-synchronizer:test` passes
- [x] Method body line counts all ≤30

Closes #933
EOF
)"
```

- [ ] **Step 6.3: Verify PR exists**

Run: `gh pr view --json number,url,title,state | jq '{number, url, title, state}'`
Expected: state `OPEN`.

---

## Acceptance criteria (from #933)

- [x] 각 upsert/batchUpsert 메서드 30줄 이하
- [x] SQL 문자열이 companion object 상수로 이동
- [x] 기존 동작 변경 없음
- [x] `./gradlew :module-synchronizer:test` 통과
