# Synchronizer File Reader Logging Implementation Plan (Issue #1019)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `log.debug` at every silent-fail path in `OcidMappingFileReader.parseMapping()` and `BasicChunkFileReader.parseRecord()` so operators can diagnose why records were skipped.

**Architecture:** Inline debug log at each `return null` site with a reason tag, plus `.onFailure { log.debug(...) }` on each `runCatching` block. No new helpers, no signature change, no behavior change.

**Tech Stack:** Kotlin, SLF4J, Jackson (existing — no new deps).

**Spec:** `docs/superpowers/specs/2026-06-04-1019-synchronizer-file-reader-logging-design.md`

---

## File Structure

**Modify:**
- `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt` — `parseMapping()` only (2 null paths + 1 runCatching)
- `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt` — `parseRecord()` only (5 null paths + 1 runCatching)

**No new files.** No test changes (logging-only, behavior unchanged per spec).

---

## Task 1: Add debug logging to OcidMappingFileReader.parseMapping()

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt:42-48`

- [ ] **Step 1: Edit `parseMapping()` to add inline debug logs**

Replace the entire `parseMapping()` function body (lines 42-49 in current file) with:

```kotlin
    private fun parseMapping(line: String): OcidMapping? {
        return runCatching {
            val node = objectMapper.readTree(line)
            val ign = node.get("userIgn")?.asText() ?: run {
                log.debug("skip mapping: reason=missing_userIgn")
                return null
            }
            val ocid = node.get("ocid")?.asText() ?: run {
                log.debug("skip mapping: reason=missing_ocid")
                return null
            }
            OcidMapping(ign, ocid)
        }.onFailure { log.debug("mapping parse fail: {}", it.message) }.getOrNull()
    }
```

Key points:
- `?: run { ... return null }` is a labeled return inside `runCatching` lambda — preserves existing null-return behavior.
- `.onFailure { ... }` after `runCatching` — logs exception message, then `.getOrNull()` proceeds as before.
- `log` is already declared at class level (line 23: `private val log = LoggerFactory.getLogger(javaClass)`) — no new field.

- [ ] **Step 2: Verify compile**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin --continue
```

Expected: `BUILD SUCCESSFUL`. If `return null` inside `run { }` block fails type inference, ensure `run` block returns `Nothing` — `return null` from lambda typed `OcidMapping?` is fine because elvis expects a `OcidMapping?` and null is acceptable.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/OcidMappingFileReader.kt
git commit -m "fix(1019): add debug logging to OcidMappingFileReader silent parse paths"
```

---

## Task 2: Add debug logging to BasicChunkFileReader.parseRecord()

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt:94-123`

- [ ] **Step 1: Edit `parseRecord()` to add inline debug logs**

Replace the entire `parseRecord()` function body (lines 94-124 in current file) with:

```kotlin
    private fun parseRecord(line: String): BasicRecord? {
        return runCatching {
            val node = objectMapper.readTree(line)

            val status = node.get("status")?.asText()
            if (status != "SUCCESS") {
                log.debug("skip record: reason=status_mismatch actual={}", status)
                return null
            }
            val endpoint = node.get("endpoint")?.asText()
            if (endpoint != "character-basic") {
                log.debug("skip record: reason=endpoint_mismatch actual={}", endpoint)
                return null
            }

            val ocid = node.get("key")?.asText() ?: run {
                log.debug("skip record: reason=missing_ocid")
                return null
            }
            val body = node.get("body") ?: run {
                log.debug("skip record: reason=missing_body")
                return null
            }

            val userIgn = body.get("character_name")?.asText() ?: run {
                log.debug("skip record: reason=missing_character_name")
                return null
            }
            val worldName = body.get("world_name")?.asText()
            val characterClass = body.get("character_class")?.asText()
            val characterLevel = body.get("character_level")?.asInt()
            val guildName = body.get("guild_name")?.asText()

            val bodyBytes = objectMapper.writeValueAsBytes(body)
            val compressed = GzipUtils.compress(bodyBytes)
            val hash = sha256Hex(bodyBytes)

            BasicRecord(
                userIgn = userIgn,
                ocid = ocid,
                worldName = worldName,
                characterClass = characterClass,
                characterLevel = characterLevel,
                guildName = guildName,
                compressedBody = compressed,
                bodyHash = hash,
            )
        }.onFailure { log.debug("record parse fail: {}", it.message) }.getOrNull()
    }
```

Key points:
- For `status` and `endpoint`, capture into local val so we can log the actual value — this is what makes the log actionable. Original was `node.get("status")?.asText() != "SUCCESS"` which lost the actual.
- For nullable `node.get("body")`, use `?: run { ... return null }` pattern (same as Task 1).
- `body.get("character_name")?.asText() ?: run { ... return null }` — same pattern.
- `log` is already declared at class level (line 33).

- [ ] **Step 2: Verify compile**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin --continue
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/storage/BasicChunkFileReader.kt
git commit -m "fix(1019): add debug logging to BasicChunkFileReader silent parse paths"
```

---

## Task 3: Full validation

**Files:** none

- [ ] **Step 1: Run full compile + test**

Run:
```bash
./gradlew compileKotlin compileJava --continue
./gradlew :module-synchronizer:test
```

Expected: `BUILD SUCCESSFUL` for both. Existing tests pass (no behavior change).

- [ ] **Step 2: Verify diff size**

Run:
```bash
git diff develop -- module-synchronizer/src/main/kotlin/maple/synchronizer/storage/
```

Expected: ~20 lines added across 2 files, 0 lines removed (or only formatting).

- [ ] **Step 3: Final commit if any cleanup needed**

If `--continue` flagged any unrelated warnings, fix them in a separate commit. Otherwise no commit.

---

## Self-Review

**Spec coverage:**
- ✓ 2 OcidMappingFileReader null paths covered (Task 1)
- ✓ 1 OcidMappingFileReader runCatching.onFailure covered (Task 1)
- ✓ 5 BasicChunkFileReader null paths covered (Task 2) — wait, spec says 6. Let me recheck.

Re-reading spec: "6개 return null 경로 (status 불일치, endpoint 불일치, key/body/character_name 누락)". Code has 5 returns: status, endpoint, key(ocid), body, character_name. The issue text listed 3 + 3 = 6, but `key/body/character_name` is 3, not 3+3. Issue author miscounted — code has 5 returns, all covered. No spec gap.

- ✓ 1 BasicChunkFileReader runCatching.onFailure covered (Task 2)
- ✓ Compile + test verification (Task 3)
- ✓ Acceptance criteria: 4 of 5 from issue covered; the 5th (existing tests pass) covered in Task 3.

**Placeholder scan:** none.

**Type consistency:** `log` is class-level in both files (already declared). No new symbols introduced. `run { ... return null }` pattern used consistently.
