# OcidMappingRepository Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `OcidMappingRepository` (DB+Redis) into `OcidMappingRepository` (DB only) and `OcidMappingRedisWriter` (Redis only). Zero behavioral change.

**Architecture:** Mechanical split. Move `writeOcidToRedis()` and its `StringRedisTemplate` dependency into a new `@Component` in a new `maple.synchronizer.redis` sub-package. Update one caller (`OcidLookupRunConsumer`) to inject the new bean. The other caller (`BasicSnapshotChunkConsumer`) uses only the DB method and is unchanged.

**Tech Stack:** Kotlin, Spring Boot, Spring Data Redis (`StringRedisTemplate`), PostgreSQL `COPY` (existing).

**Branch:** Create `feature/1077-ocid-mapping-repository-split` off `develop`. PR base: `develop`.

---

## File structure

| File | Role | Action |
|------|------|--------|
| `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt` | DB-only upsert (PostgreSQL COPY→merge) | Modify — remove Redis code |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/redis/OcidMappingRedisWriter.kt` | Redis hash write (HSET + RENAME) | Create |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt` | Caller A — uses both beans | Modify — inject new bean |
| `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/BasicSnapshotChunkConsumer.kt` | Caller B — uses only DB | Unchanged |

**Why no new tests:** Issue #1077 acceptance is "No behavioral change". The split is a pure code move (rename + relocate). Adding a unit test for `OcidMappingRedisWriter` that just verifies the body is a rehash of `OcidMappingRepository` adds no signal. The existing `ChunkConsumerMappingTest` covers the unchanged `BasicSnapshotChunkConsumer` path. Compile + existing test pass is the gate.

**Why no TDD steps:** the spec says "no behavioral change". Writing a failing test first and then making it pass trivially is theater when the implementation already exists. The risk of the split is mechanical (imports, deps, constructor ordering) — compile + test gates catch those.

---

## Task 1: Create branch off develop

**Files:** none

- [ ] **Step 1.1: Fetch develop and create branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git checkout develop
git pull origin develop
git checkout -b feature/1077-ocid-mapping-repository-split
```

- [ ] **Step 1.2: Verify branch**

Run: `git branch --show-current`
Expected: `feature/1077-ocid-mapping-repository-split`

---

## Task 2: Create `OcidMappingRedisWriter` (new file)

**Files:**
- Create: `module-synchronizer/src/main/kotlin/maple/synchronizer/redis/OcidMappingRedisWriter.kt`

- [ ] **Step 2.1: Create the redis sub-package directory**

Run: `mkdir -p module-synchronizer/src/main/kotlin/maple/synchronizer/redis`
Expected: command exits 0, no output.

- [ ] **Step 2.2: Create the new file with the moved Redis logic**

```kotlin
package maple.synchronizer.redis

import maple.synchronizer.storage.OcidMapping
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
class OcidMappingRedisWriter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        if (mappings.isEmpty()) {
            redisTemplate.delete(REDIS_KEY)
            log.info("[OcidMapping] Redis cleared: empty mappings")
            return
        }
        val tempKey = "$REDIS_KEY:tmp:${System.nanoTime()}"
        redisTemplate.executePipelined { connection ->
            for (mapping in mappings) {
                connection.hashCommands().hSet(
                    tempKey.toByteArray(),
                    mapping.userIgn.toByteArray(),
                    mapping.ocid.toByteArray(),
                )
            }
            null
        }
        redisTemplate.rename(tempKey, REDIS_KEY)
        log.info("[OcidMapping] Redis written atomically via RENAME: {} mappings to {}", mappings.size, REDIS_KEY)
    }
}
```

- [ ] **Step 2.3: Verify the file exists**

Run: `ls module-synchronizer/src/main/kotlin/maple/synchronizer/redis/`
Expected: `OcidMappingRedisWriter.kt`

- [ ] **Step 2.4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/redis/OcidMappingRedisWriter.kt
git commit -m "feat(1077): add OcidMappingRedisWriter with moved Redis write logic"
```

---

## Task 3: Slim `OcidMappingRepository` (remove Redis code)

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`

- [ ] **Step 3.1: Replace the file contents**

Full replacement of the file:

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

    fun batchUpsert(mappings: List<OcidMapping>) {
        val affected: Int = jdbc.jdbcTemplate.execute(
            ConnectionCallback { con: Connection ->
                con.autoCommit = false

                runCatching {
                    con.createStatement().use { stmt ->
                        stmt.execute("DROP TABLE IF EXISTS tmp_ocid_mapping")
                        stmt.execute("CREATE TEMP TABLE tmp_ocid_mapping (user_ign text NOT NULL, ocid text NOT NULL) ON COMMIT DROP")
                    }

                    val copyManager = CopyManager(con.unwrap(BaseConnection::class.java))
                    val data = mappings.joinToString("\n") { "${it.userIgn}\t${it.ocid}" }
                    copyManager.copyIn("COPY tmp_ocid_mapping (user_ign, ocid) FROM STDIN", data.reader())

                    val rows = con.createStatement().use { stmt ->
                        stmt.executeUpdate("""
                            DELETE FROM game_character
                            WHERE EXISTS (
                                SELECT 1 FROM tmp_ocid_mapping t
                                WHERE t.ocid = game_character.ocid AND t.user_ign != game_character.user_ign
                            )
                        """.trimIndent())
                        stmt.executeUpdate("""
                            INSERT INTO game_character (user_ign, ocid, updated_at)
                            SELECT user_ign, ocid, now()
                            FROM tmp_ocid_mapping
                            ON CONFLICT (user_ign) DO UPDATE SET
                                ocid = EXCLUDED.ocid,
                                updated_at = now()
                            WHERE game_character.ocid IS DISTINCT FROM EXCLUDED.ocid
                        """.trimIndent())
                    }

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
}
```

Note the removed elements:
- `import org.springframework.data.redis.core.StringRedisTemplate`
- `private val redisTemplate: StringRedisTemplate,` constructor param
- `private const val REDIS_KEY = "ocid:mapping"` companion
- entire `writeOcidToRedis()` method

- [ ] **Step 3.2: Verify no Redis references remain**

Run: `grep -n "redis\|REDIS_KEY\|writeOcidToRedis" module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt`
Expected: no output (or zero matches)

- [ ] **Step 3.3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/repository/OcidMappingRepository.kt
git commit -m "refactor(1077): remove Redis logic from OcidMappingRepository (moved to OcidMappingRedisWriter)"
```

---

## Task 4: Update `OcidLookupRunConsumer` (caller A)

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`

- [ ] **Step 4.1: Add import for the new writer**

Find this line:
```kotlin
import maple.synchronizer.repository.OcidMappingRepository
```

Add directly below it (preserve existing blank line pattern):
```kotlin
import maple.synchronizer.redis.OcidMappingRedisWriter
```

- [ ] **Step 4.2: Add the constructor parameter**

Find this block:
```kotlin
class OcidLookupRunConsumer(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val objectMapper: ObjectMapper,
) {
```

Replace with:
```kotlin
class OcidLookupRunConsumer(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val ocidMappingRedisWriter: OcidMappingRedisWriter,
    private val objectMapper: ObjectMapper,
) {
```

- [ ] **Step 4.3: Update the Redis call site**

Find this line:
```kotlin
        repository.writeOcidToRedis(mappings)
```

Replace with:
```kotlin
        ocidMappingRedisWriter.writeOcidToRedis(mappings)
```

- [ ] **Step 4.4: Verify the consumer compiles and references the new class**

Run: `grep -n "writeOcidToRedis\|OcidMappingRedisWriter" module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`
Expected:
- import line for `OcidMappingRedisWriter`
- constructor param `ocidMappingRedisWriter: OcidMappingRedisWriter`
- call site `ocidMappingRedisWriter.writeOcidToRedis(mappings)`
- no `repository.writeOcidToRedis` anywhere

- [ ] **Step 4.5: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt
git commit -m "refactor(1077): inject OcidMappingRedisWriter into OcidLookupRunConsumer"
```

---

## Task 5: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 5.1: Compile synchronizer module**

Run: `./gradlew :module-synchronizer:compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL, no Kotlin/Java errors. (If errors appear, fix the import or constructor mismatch in Task 4.)

- [ ] **Step 5.2: Run synchronizer tests**

Run: `./gradlew :module-synchronizer:test`
Expected: BUILD SUCCESSFUL, all tests pass. `ChunkConsumerMappingTest` is the relevant one for this change.

- [ ] **Step 5.3: Verify no other module references the removed method**

Run:
```bash
grep -rn "repository.writeOcidToRedis\|OcidMappingRepository.*writeOcidToRedis" \
  /home/maple/probabilistic-valuation-engine --include="*.kt"
```
Expected: no output.

- [ ] **Step 5.4: Verify the new class is wired correctly**

Run:
```bash
grep -rn "OcidMappingRedisWriter" \
  /home/maple/probabilistic-valuation-engine --include="*.kt"
```
Expected output should include:
- `OcidMappingRedisWriter.kt` (definition with `@Component`)
- `OcidLookupRunConsumer.kt` (import + constructor param + call site)

If commit history is clean, do not commit again — already done in Tasks 2/3/4.

---

## Task 6: PR

**Files:** none

- [ ] **Step 6.1: Push branch**

```bash
git push -u origin feature/1077-ocid-mapping-repository-split
```

- [ ] **Step 6.2: Create PR with `gh`**

```bash
gh pr create \
  --base develop \
  --head feature/1077-ocid-mapping-repository-split \
  --title "refactor(1077): split OcidMappingRepository into DB and Redis" \
  --body "$(cat <<'EOF'
## Summary
- Split \`OcidMappingRepository\` (DB-only) and new \`OcidMappingRedisWriter\` (Redis-only).
- \`OcidLookupRunConsumer\` injects both; \`BasicSnapshotChunkConsumer\` unchanged.
- Zero behavioral change.

## Files
- New: \`module-synchronizer/.../redis/OcidMappingRedisWriter.kt\`
- Modified: \`OcidMappingRepository.kt\` (slimmed), \`OcidLookupRunConsumer.kt\` (new injection)

## Verification
- [x] \`./gradlew :module-synchronizer:compileKotlin compileJava --continue\` passes
- [x] \`./gradlew :module-synchronizer:test\` passes
- [x] \`ChunkConsumerMappingTest\` unchanged and passing

Closes #1077
EOF
)"
```

- [ ] **Step 6.3: Verify PR exists**

Run: `gh pr view --json number,url,title,state | jq '{number, url, title, state}'`
Expected: state `OPEN`, title matches the one in Step 6.2.

---

## Acceptance criteria

From #1077:
- [x] Redis write logic extracted to a new class
- [x] Original repository focused on DB operations only
- [x] Caller updated to inject both classes
- [x] `./gradlew :module-synchronizer:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-synchronizer:test` passes
- [x] No behavioral change
