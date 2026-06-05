# DB Access Pattern Fixes Implementation Plan (#1106 + #1107)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 4 DB anti-patterns in module-infra: N+1 loop, missing LIMIT, missing batch chunking, loop INSERT.

**Architecture:** Each fix is a single-file change using existing methods/patterns. No new interfaces or classes. The N+1 fix reuses an existing batch method on `CharacterViewQueryPort`. The chunking fix reuses the same `chunked()` pattern from `EquipmentExpectationSummaryBatchRepository`.

**Tech Stack:** Kotlin, Spring JPA (`EntityManager`), `JdbcTemplate.batchUpdate()`, `CharacterViewQueryPort`.

**Branch:** Create `fix/1106-1107-db-access-patterns` off `develop`. PR base: `develop`.

**Spec:** `docs/superpowers/specs/2026-06-05-1106-1107-db-access-pattern-fixes-design.md`

---

## File structure

| File | Issue | Change |
|------|-------|--------|
| `module-infra/.../worker/AbstractExpectationCalcWorker.kt` | #1106 | Replace forEach with batch call |
| `module-infra/.../jpa/GameCharacterJpaRepositoryCustomImpl.kt` | #1107-1 | Add `.setMaxResults(100)` |
| `module-infra/.../repository/ExpectationBatchRepository.kt` | #1107-2 | Add `chunked(100)` to `doBatchUpsert` |
| `module-infra/.../pgmq/DlqReplayWorker.kt` | #1107-3 | Loop INSERT → `batchUpdate` |

---

## Task 1: Create branch off develop

**Files:** none

- [ ] **Step 1.1: Fetch develop and create branch**

```bash
cd /home/maple/probabilistic-valuation-engine
git fetch origin develop
git checkout develop
git pull origin develop
git checkout -b fix/1106-1107-db-access-patterns
```

- [ ] **Step 1.2: Verify branch**

Run: `git branch --show-current`
Expected: `fix/1106-1107-db-access-patterns`

---

## Task 2: Fix N+1 in AbstractExpectationCalcWorker (#1106)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`

- [ ] **Step 2.1: Add import for CharacterViewProjectionCommand**

Find this import line (already exists in the file):
```kotlin
import maple.expectation.core.port.inbound.CharacterViewQueryPort
```

Add directly below it:
```kotlin
import maple.expectation.core.port.inbound.CharacterViewProjectionCommand
```

- [ ] **Step 2.2: Replace the N+1 forEach block**

Find this code block (around lines 164-177):
```kotlin
        // 4. Sync read model for query-server
        parsed.forEach { view ->
            viewQueryPort.upsertFromCalculation(
                userIgn = view.userIgn,
                messageId = view.messageId,
                characterOcid = view.characterOcid,
                characterClass = view.characterClass,
                characterLevel = null,
                totalExpectedCost = view.totalExpectedCost,
                maxPresetNo = view.maxPresetNo,
                presetNo = view.presetNo,
                presetsJson = view.presetsJson,
            )
        }
```

Replace with:
```kotlin
        // 4. Sync read model for query-server — batch upsert (was N+1)
        val commands = parsed.map { view ->
            CharacterViewProjectionCommand(
                userIgn = view.userIgn,
                messageId = view.messageId,
                characterOcid = view.characterOcid,
                characterClass = view.characterClass,
                characterLevel = null,
                totalExpectedCost = view.totalExpectedCost,
                maxPresetNo = view.maxPresetNo,
                presetNo = view.presetNo,
                presetsJson = view.presetsJson,
            )
        }
        viewQueryPort.batchUpsertFromCalculations(commands)
```

- [ ] **Step 2.3: Verify no remaining forEach with upsertFromCalculation**

Run: `grep -n "upsertFromCalculation" module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt`
Expected: no output (the single-item method is no longer called in this file).

- [ ] **Step 2.4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/worker/AbstractExpectationCalcWorker.kt
git commit -m "fix(1106): replace N+1 forEach with batchUpsertFromCalculations in AbstractExpectationCalcWorker"
```

---

## Task 3: Add LIMIT to findActiveCharacters (#1107-1)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustomImpl.kt`

- [ ] **Step 3.1: Add setMaxResults(100) to the query**

Find this code block:
```kotlin
    override fun findActiveCharacters(): List<GameCharacterJpaEntity> {
        val threshold = LocalDateTime.now().minusDays(30)
        return entityManager
            .createQuery(
                """
                SELECT gc FROM GameCharacterJpaEntity gc
                WHERE gc.updatedAt > :threshold
                ORDER BY gc.updatedAt DESC
                """,
                GameCharacterJpaEntity::class.java,
            )
            .setParameter("threshold", threshold)
            .resultList
    }
```

Replace with:
```kotlin
    override fun findActiveCharacters(): List<GameCharacterJpaEntity> {
        val threshold = LocalDateTime.now().minusDays(30)
        return entityManager
            .createQuery(
                """
                SELECT gc FROM GameCharacterJpaEntity gc
                WHERE gc.updatedAt > :threshold
                ORDER BY gc.updatedAt DESC
                """,
                GameCharacterJpaEntity::class.java,
            )
            .setParameter("threshold", threshold)
            .setMaxResults(100)
            .resultList
    }
```

The only change is adding `.setMaxResults(100)` before `.resultList`.

- [ ] **Step 3.2: Verify the change**

Run: `grep -n "setMaxResults" module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustomImpl.kt`
Expected: one line containing `setMaxResults(100)`.

- [ ] **Step 3.3: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/jpa/GameCharacterJpaRepositoryCustomImpl.kt
git commit -m "fix(1107): add LIMIT 100 to findActiveCharacters JPA query"
```

---

## Task 4: Add chunking to ExpectationBatchRepository (#1107-2)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationBatchRepository.kt`

- [ ] **Step 4.1: Add BATCH_SIZE constant and chunking logic**

Find the companion object:
```kotlin
    companion object {
        private val log = LoggerFactory.getLogger(ExpectationBatchRepository::class.java)
```

Add `BATCH_SIZE` constant:
```kotlin
    companion object {
        private val log = LoggerFactory.getLogger(ExpectationBatchRepository::class.java)
        private const val BATCH_SIZE = 100
```

- [ ] **Step 4.2: Replace doBatchUpsert with chunked version**

Find this method:
```kotlin
    private fun doBatchUpsert(tasks: List<ExpectationWriteTask>): IntArray {
        if (tasks.isEmpty()) {
            log.debug("[ExpectationBatchRepo] No tasks to upsert")
            return intArrayOf()
        }

        val startTime = System.currentTimeMillis()

        // Convert tasks to batch arguments
        val batchArgs: List<Array<Any?>> = tasks.map { toBatchArgs(it) }

        // Execute batch update
        val results = jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs)

        val duration = System.currentTimeMillis() - startTime

        log.info(
            "[ExpectationBatchRepo] Batch upsert completed: {} records in {}ms ({} records/sec)",
            tasks.size,
            duration,
            if (duration > 0) tasks.size * 1000L / duration else 0,
        )

        return results
    }
```

Replace with:
```kotlin
    private fun doBatchUpsert(tasks: List<ExpectationWriteTask>): IntArray {
        if (tasks.isEmpty()) {
            log.debug("[ExpectationBatchRepo] No tasks to upsert")
            return intArrayOf()
        }

        val startTime = System.currentTimeMillis()

        val results = tasks.chunked(BATCH_SIZE).flatMap { chunk ->
            val batchArgs = chunk.map { toBatchArgs(it) }
            jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs).toList()
        }.toIntArray()

        val duration = System.currentTimeMillis() - startTime

        log.info(
            "[ExpectationBatchRepo] Batch upsert completed: {} records in {} chunks of {} in {}ms ({} records/sec)",
            tasks.size,
            tasks.chunked(BATCH_SIZE).size,
            BATCH_SIZE,
            duration,
            if (duration > 0) tasks.size * 1000L / duration else 0,
        )

        return results
    }
```

Changes:
- `tasks.map { ... }` → `tasks.chunked(BATCH_SIZE).flatMap { ... }` for batch chunking
- Log message updated to include chunk count
- Rest unchanged (same SQL, same `toBatchArgs`, same timing)

- [ ] **Step 4.3: Verify chunking logic**

Run: `grep -n "chunked\|BATCH_SIZE" module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationBatchRepository.kt`
Expected: `BATCH_SIZE` constant definition + `chunked(BATCH_SIZE)` usage.

- [ ] **Step 4.4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/persistence/repository/ExpectationBatchRepository.kt
git commit -m "fix(1107): add batch chunking (size=100) to ExpectationBatchRepository"
```

---

## Task 5: Replace DlqReplayWorker loop INSERT with batchUpdate (#1107-3)

**Files:**
- Modify: `module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt`

- [ ] **Step 5.1: Replace loop INSERT with batchUpdate**

Find this code block in the `discoverAndTrack` method (around lines 108-114):
```kotlin
        if (untracked.isEmpty()) return

        for (msgId in untracked) {
            insertTracking(queueName, msgId)
        }

        log.info("[DlqReplayWorker] Discovered {} new DLQ messages in {}", untracked.size, queueName)
```

Replace with:
```kotlin
        if (untracked.isEmpty()) return

        jdbcTemplate.batchUpdate(
            "INSERT INTO dlq_replay_meta (queue_name, message_id, replay_count, first_failed_at) VALUES (?, ?, 0, NOW()) ON CONFLICT DO NOTHING",
            untracked.map { arrayOf<Any>(queueName, it) },
        )

        log.info("[DlqReplayWorker] Discovered {} new DLQ messages in {}", untracked.size, queueName)
```

- [ ] **Step 5.2: Remove the now-unused insertTracking method**

Find and delete this entire private method:
```kotlin
    private fun insertTracking(queueName: String, messageId: Long) {
        jdbcTemplate.update(
            "INSERT INTO dlq_replay_meta (queue_name, message_id, replay_count, first_failed_at) VALUES (?, ?, 0, NOW()) ON CONFLICT DO NOTHING",
            queueName,
            messageId,
        )
    }
```

- [ ] **Step 5.3: Verify no remaining insertTracking references**

Run: `grep -n "insertTracking" module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt`
Expected: no output.

- [ ] **Step 5.4: Commit**

```bash
git add module-infra/src/main/kotlin/maple/expectation/infrastructure/pgmq/DlqReplayWorker.kt
git commit -m "fix(1107): replace DlqReplayWorker loop INSERT with batchUpdate"
```

---

## Task 6: Compile + test gates

**Files:** none (verification only)

- [ ] **Step 6.1: Compile module-infra**

Run: `./gradlew :module-infra:compileKotlin compileJava --continue`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.2: Run module-infra tests**

Run: `./gradlew :module-infra:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.3: Verify no stale references to removed method**

Run:
```bash
grep -rn "insertTracking" /home/maple/probabilistic-valuation-engine/module-infra/src --include="*.kt"
```
Expected: no output (method was removed and was only called from `discoverAndTrack`).

---

## Task 7: PR

**Files:** none

- [ ] **Step 7.1: Push branch**

```bash
git push -u origin fix/1106-1107-db-access-patterns
```

- [ ] **Step 7.2: Create PR with `gh`**

```bash
gh pr create \
  --base develop \
  --head fix/1106-1107-db-access-patterns \
  --title "fix(1106,1107): DB access pattern fixes — N+1, LIMIT, chunking, batch INSERT" \
  --body "$(cat <<'EOF'
## Summary
- **#1106:** Replace N+1 `forEach` in `AbstractExpectationCalcWorker` with existing `batchUpsertFromCalculations()` method.
- **#1107-1:** Add `LIMIT 100` to `findActiveCharacters()` JPA query (both callers already limit to 100).
- **#1107-2:** Add `chunked(100)` to `ExpectationBatchRepository.doBatchUpsert()` (matches `EquipmentExpectationSummaryBatchRepository.DEFAULT_BATCH_SIZE`).
- **#1107-3:** Replace `DlqReplayWorker` loop INSERT with `jdbcTemplate.batchUpdate()`.

## Skipped (per spec)
- `GameCharacterRepositoryImpl.findAll()` — no callers in prod
- `CharacterOcidAdapter.loadAllOcidsFromDb()` — intentional startup cache warmup

## Files
- Modified: `AbstractExpectationCalcWorker.kt`, `GameCharacterJpaRepositoryCustomImpl.kt`, `ExpectationBatchRepository.kt`, `DlqReplayWorker.kt`

## Verification
- [x] `./gradlew :module-infra:compileKotlin compileJava --continue` passes
- [x] `./gradlew :module-infra:test` passes

Closes #1106
Closes #1107
EOF
)"
```

- [ ] **Step 7.3: Verify PR exists**

Run: `gh pr view --json number,url,title,state | jq '{number, url, title, state}'`
Expected: state `OPEN`.

---

## Acceptance criteria

From #1106:
- [x] `viewQueryPort.batchUpsertFromCalculations()` batch implementation used
- [x] `AbstractExpectationCalcWorker` forEach → batch call
- [x] Existing single-item `upsertFromCalculation` kept

From #1107:
- [x] `findActiveCharacters()` SQL has LIMIT
- [x] `ExpectationBatchRepository` chunking applied
- [x] `DlqReplayWorker` loop INSERT → batchUpdate
- [x] Compile + test pass
