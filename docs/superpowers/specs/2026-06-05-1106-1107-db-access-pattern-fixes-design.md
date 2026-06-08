# Spec: DB Access Pattern Fixes (#1106 + #1107)

- Status: Draft
- Date: 2026-06-05
- Owner: TBD
- Issues: #1106, #1107

## 1. Background / Problem

Two DB access anti-patterns in module-infra:

### #1106: N+1 in AbstractExpectationCalcWorker

`AbstractExpectationCalcWorker.batchViewUpsert()` (line 165-177) calls
`viewQueryPort.upsertFromCalculation()` per item in a `forEach` loop.
The `CharacterViewQueryPort` already has a `batchUpsertFromCalculations()`
method — the worker just isn't using it.

### #1107: DB anti-patterns (3 items)

1. **`findActiveCharacters()` no LIMIT** — `GameCharacterJpaRepositoryCustomImpl`
   loads all characters updated in last 30 days. Both callers
   (`NexonApiCollectorScheduler`, `NexonDataCollectionScheduler`) immediately
   limit to 100 via `.take(100)` / `.limit(100)`. DB sends full result,
   JVM trims.

2. **`ExpectationBatchRepository` no chunking** — `jdbcTemplate.batchUpdate()`
   receives entire list at once. `EquipmentExpectationSummaryBatchRepository`
   already uses `chunked(batchSize)` pattern.

3. **`DlqReplayWorker` loop INSERT** — `for (msgId in untracked) { insertTracking(...) }`
   issues N individual INSERT statements.

### Goal

Fix all 4 anti-patterns. No behavioral change (same data, same results).

### Skipped items (from #1107)

- `GameCharacterRepositoryImpl.findAll()` — no callers in production code.
- `CharacterOcidAdapter.loadAllOcidsFromDb()` — intentional startup cache
  warmup. Comment explicitly states "intentional full-table scan."

## 2. Decision

### #1106: Replace N+1 forEach with batch call

Replace `parsed.forEach { viewQueryPort.upsertFromCalculation(...) }` with:

```kotlin
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

`batchUpsertFromCalculations()` already exists on the port interface
(`CharacterViewQueryPort:65`) and the adapter (`CharacterViewQueryPortAdapter:56`).
No new methods needed.

### #1107-1: Add LIMIT to findActiveCharacters()

```kotlin
override fun findActiveCharacters(): List<GameCharacterJpaEntity> {
    val threshold = LocalDateTime.now().minusDays(30)
    return entityManager
        .createQuery(...)
        .setParameter("threshold", threshold)
        .setMaxResults(100)
        .resultList
}
```

Both callers limit to 100 anyway. DB now sends at most 100 rows.

### #1107-2: Add chunking to ExpectationBatchRepository

Apply same `chunked(batchSize)` pattern from `EquipmentExpectationSummaryBatchRepository`:

```kotlin
private fun doBatchUpsert(tasks: List<ExpectationWriteTask>): IntArray {
    if (tasks.isEmpty()) return intArrayOf()
    val batchSize = 100
    return tasks.chunked(batchSize).flatMap { chunk ->
        jdbcTemplate.batchUpdate(UPSERT_SQL, chunk.map { toBatchArgs(it) }).toList()
    }.toIntArray()
}
```

### #1107-3: Replace DlqReplayWorker loop INSERT with batchUpdate

```kotlin
if (untracked.isEmpty()) return
jdbcTemplate.batchUpdate(
    "INSERT INTO dlq_replay_meta (queue_name, message_id, replay_count, first_failed_at) VALUES (?, ?, 0, NOW()) ON CONFLICT DO NOTHING",
    untracked.map { arrayOf(queueName, it) },
)
```

## 3. Trade-offs

### Sensitivity

- `findActiveCharacters()` LIMIT 100 — if a future caller needs more than 100,
  they need a paginated variant. Current callers are both schedulers that
  process in batches of 100.
- `ExpectationBatchRepository` chunk size 100 — matches
  `EquipmentExpectationSummaryBatchRepository.DEFAULT_BATCH_SIZE`.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| LIMIT 100 hardcoded | Simple, matches callers | Future callers need new method if they need more |
| LIMIT as parameter | Flexible | Over-engineering for 2 callers |

### Risk

- `batchUpsertFromCalculations()` may have different SQL than
  `upsertFromCalculation()`. If the batch method's SQL or parameter binding
  differs, the results could diverge. Verified: adapter delegates to
  `queryService.batchUpsertFromCalculations()` which uses the same table and
  same ON CONFLICT logic.

### Non-Risk

- Behavioral change — same data written, same conflict resolution.
- DlqReplayWorker — `ON CONFLICT DO NOTHING` already handles duplicates.
  Batch `batchUpdate` preserves this.

## 4. File changes

| File | Issue | Action |
|------|-------|--------|
| `module-infra/.../worker/AbstractExpectationCalcWorker.kt` | #1106 | Replace forEach with batch call |
| `module-infra/.../jpa/GameCharacterJpaRepositoryCustomImpl.kt` | #1107-1 | Add `.setMaxResults(100)` |
| `module-infra/.../repository/ExpectationBatchRepository.kt` | #1107-2 | Add chunking |
| `module-infra/.../pgmq/DlqReplayWorker.kt` | #1107-3 | Loop INSERT → batchUpdate |

## 5. Testing

- **Existing tests:** compile + test gate.
- **No new tests:** no behavioral change, existing methods used.
- **Compile gate:** `./gradlew :module-infra:compileKotlin compileJava --continue`
- **Test gate:** `./gradlew :module-infra:test`

## 6. Out of scope

- `GameCharacterRepositoryImpl.findAll()` — no callers, skip.
- `CharacterOcidAdapter.loadAllOcidsFromDb()` — intentional, skip.
- Port interface changes — batch method already exists.

## 7. Acceptance criteria

From #1106:
- [x] `viewQueryPort.batchUpsertFromCalculations()` batch implementation used
- [x] `AbstractExpectationCalcWorker` forEach → batch call
- [x] Existing single-item `upsertFromCalculation` kept (other callers)

From #1107:
- [x] `findActiveCharacters()` SQL has LIMIT
- [x] `ExpectationBatchRepository` chunking applied
- [x] `DlqReplayWorker` loop INSERT → batchUpdate
- [x] Compile + test pass

## 8. Summary

> Fix 4 DB anti-patterns: N+1→batch (worker), add LIMIT (JPA query), add
> chunking (batch repo), loop INSERT→batchUpdate (DLQ worker). Zero behavioral
> change. 4 files in module-infra.
