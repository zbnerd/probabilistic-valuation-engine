# Spec: Synchronizer Repository SQL Method Decomposition (#933)

- Status: Draft
- Date: 2026-06-05
- Owner: TBD
- Issue: #933

## 1. Background / Problem

3 repository batch methods in module-synchronizer mix SQL strings and
parameter binding in single methods that exceed the 30-line target:

| File | Method | Body lines |
|------|--------|-----------|
| `CharacterBasicRepository.kt` | `upsertBatch()` | 48 |
| `EquipmentReadModelRepository.kt` | `upsertBatch()` | 40 |
| `OcidMappingRepository.kt` | `batchUpsert()` | 45 |

SQL inline in method bodies obscures the control flow and makes it hard
to reason about what each method does at a glance.

### Goal

Decompose each method to ≤30 lines by extracting SQL to companion
constants and parameter binding to helper functions. No behavioral change.

## 2. Decision

### CharacterBasicRepository

Extract from `upsertBatch()`:

```text
companion object {
    private const val SUB_BATCH_SIZE = 100
    private const val DELETE_STALE_SQL = "DELETE FROM character_basic_read_model WHERE ..."
    private const val UPSERT_SQL = "INSERT INTO character_basic_read_model ... ON CONFLICT ..."
}

private fun buildUpsertParams(runId: String, chunkId: String, batch: List<BasicRecord>): MapSqlParameterSource

// upsertBatch() becomes ~15 lines:
// 1. Extract arrays
// 2. jdbc.update(DELETE_STALE_SQL, deleteParams)
// 3. jdbc.update(UPSERT_SQL, buildUpsertParams(...))
```

### EquipmentReadModelRepository

Extract from `upsertBatch()`:

```text
companion object {
    private const val SUB_BATCH_SIZE = 100
    private const val UPSERT_SQL = "INSERT INTO character_equipment_read_model ... ON CONFLICT ..."
}

private fun buildUpsertParams(runId: String, chunkId: String, batch: List<PreppedDocument>): MapSqlParameterSource

// upsertBatch() becomes ~12 lines:
// 1. jdbc.update(UPSERT_SQL, buildUpsertParams(...))
```

### OcidMappingRepository

Extract from `batchUpsert()`:

```text
companion object {
    private const val DROP_TMP_SQL = "DROP TABLE IF EXISTS tmp_ocid_mapping"
    private const val CREATE_TMP_SQL = "CREATE TEMP TABLE tmp_ocid_mapping ..."
    private const val DELETE_CONFLICT_SQL = "DELETE FROM game_character WHERE EXISTS ..."
    private const val MERGE_SQL = "INSERT INTO game_character ... ON CONFLICT ..."
}

private fun createTempTable(con: Connection)
private fun copyToTemp(con: Connection, mappings: List<OcidMapping>)
private fun mergeFromTemp(con: Connection): Int

// batchUpsert() becomes ~20 lines:
// con.autoCommit = false
// runCatching {
//     con.createTempTable()
//     con.copyToTemp(mappings)
//     val rows = con.mergeFromTemp()
//     con.commit()
//     rows
// }.getOrElse { con.rollback(); throw it }
```

Using explicit `Connection` parameter passing matches existing codebase
patterns (`InsertChunkExecutionCommand.toParams()`, `String.safeError()`).

## 3. Trade-offs

### Sensitivity

- SQL string correctness — copy-paste error during extraction would
  change behavior. Verified by: no re-typing, only cut-paste from
  existing code.
- Parameter binding order in `buildUpsertParams` — must match SQL
  `:named` placeholders exactly. Verified by: existing tests pass.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Private helpers only | Minimal blast radius | Companion constants visible in class scope |
| Shared unnest-upsert utility (rejected) | DRY across repos | Overlaps with #952 scope; premature |

### Risk

- SQL constant name ambiguity — e.g. two repos both have `UPSERT_SQL`.
  Mitigated by: constants are `private` in each class. No cross-class
  visibility.
- OcidMapping `Connection` extension functions — could conflict with
  other extensions on `Connection`. Mitigated by: `private` scope
  within the repository class.

### Non-Risk

- Behavioral change — pure cut-paste refactor. SQL strings identical.
- Test coverage regression — existing tests unchanged.

## 4. File changes

| File | Action |
|------|--------|
| `module-synchronizer/.../repository/CharacterBasicRepository.kt` | Extract SQL constants + `buildUpsertParams()` |
| `module-synchronizer/.../repository/EquipmentReadModelRepository.kt` | Extract SQL constant + `buildUpsertParams()` |
| `module-synchronizer/.../repository/OcidMappingRepository.kt` | Extract 4 SQL constants + 3 `Connection` extensions |

## 5. Testing

- **Existing tests:** `ChunkConsumerMappingTest`, `DefaultChunkProcessorTest`
  unchanged. Compile + test gate confirms no regression.
- **No new tests:** pure refactor, no behavioral change.
- **Compile gate:** `./gradlew :module-synchronizer:compileKotlin compileJava --continue`
- **Test gate:** `./gradlew :module-synchronizer:test`

## 6. Out of scope

- Common unnest-upsert utility extraction — tracked in #952.
- Method signature changes — keep all public APIs identical.
- `JdbcChunkedBatchExecutor` refactoring — already clean.

## 7. Acceptance criteria (from #933)

- [ ] 각 upsert/batchUpsert 메서드 30줄 이하
- [ ] SQL 문자열이 companion object 상수로 이동
- [ ] 기존 동작 변경 없음
- [ ] `./gradlew :module-synchronizer:test` 통과

## 8. Summary

> Extract inline SQL and parameter binding from 3 synchronizer repository
> batch methods into companion constants and private helpers. Each method
> body reduced to ≤30 lines. Zero behavioral change.
