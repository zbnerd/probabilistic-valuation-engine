# OcidUserIgnResolver Logging Implementation Plan (Issue #1138)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add aggregate debug log to `OcidUserIgnResolver.resolve()` showing count + sample of OCIDs excluded due to empty `user_ign`.

**Architecture:** Replace the existing single-line `log.debug` with a branching log: if exclusions exist, log with sample; else log the existing happy-path message. No behavior change, no signature change, no new tests.

**Tech Stack:** Kotlin, SLF4J (existing — no new deps).

**Spec:** `docs/superpowers/specs/2026-06-04-1138-ocid-user-ign-resolver-logging-design.md`

---

## File Structure

**Modify:**
- `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt` — `resolve()` only (lines 14-30)

**No new files.** No test changes (logging-only, behavior unchanged per spec).

---

## Task 1: Add exclusion logging to OcidUserIgnResolver.resolve()

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt:14-30`

- [ ] **Step 1: Replace `resolve()` body**

Replace the entire `resolve()` function body (lines 14-30 in current file) with:

```kotlin
    fun resolve(ocids: Set<String>): Map<String, String> {
        if (ocids.isEmpty()) return emptyMap()

        val sql = """
            SELECT ocid, user_ign FROM character_basic_read_model WHERE ocid IN (:ocids)
        """.trimIndent()

        val params = MapSqlParameterSource("ocids", ocids.toList())

        val rows = jdbc.queryForList(sql, params)
        val mapping = rows.associate { row ->
            row["ocid"].toString() to (row["user_ign"]?.toString() ?: "")
        }
        val excluded = mapping.filterValues { it.isEmpty() }
        val result = mapping.filterValues { it.isNotEmpty() }
        if (excluded.isNotEmpty()) {
            log.debug("Resolved {} of {} ocids; excluded {} with empty user_ign (sample: {})",
                result.size, ocids.size, excluded.size, excluded.keys.take(5))
        } else {
            log.debug("Resolved {} of {} ocids to userIgn", result.size, ocids.size)
        }
        return result
    }
```

Key points:
- `rows` is now a local val (was inlined into `jdbc.queryForList(...).associate { ... }`).
- `mapping.filterValues { it.isEmpty() }` produces the excluded map.
- `result = mapping.filterValues { it.isNotEmpty() }` is the previously-returned map.
- `excluded.keys.take(5)` bounds log payload.
- `log` is class-level (line 12) — no new field.

- [ ] **Step 2: Verify compile**

Run:
```bash
./gradlew :module-synchronizer:compileKotlin --continue
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run synchronizer tests**

Run:
```bash
./gradlew :module-synchronizer:test
```

Expected: `BUILD SUCCESSFUL`. Existing tests pass (no behavior change — only logging path added).

- [ ] **Step 4: Commit**

```bash
git add module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt
git commit -m "fix(1138): add debug logging for empty userIgn exclusions in OcidUserIgnResolver"
```

---

## Task 2: Final diff check

- [ ] **Step 1: Verify diff size**

Run:
```bash
git diff HEAD~1 -- module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt
```

Expected: ~8 lines added, ~2 lines removed, no formatting churn.

---

## Self-Review

**Spec coverage:**
- ✓ Aggregate log with sample — Task 1
- ✓ No behavior change — preserved `filterValues { it.isNotEmpty() }` on `result`
- ✓ Compile + test verification — Task 1 step 2-3
- ✓ No new tests — explicitly skipped per spec

**Placeholder scan:** none.

**Type consistency:** `Map<String, String>` types preserved, `excluded` is `Map<String, String>`, `excluded.keys` is `Set<String>`, `take(5)` returns `List<String>` — log accepts.
