# Spec: OcidUserIgnResolver Empty-UserIgn Logging (Issue #1138)

- Status: Approved
- Date: 2026-06-04
- Issue: #1138 (originally part of #1096)
- Owner: zbnerd

## Background

`OcidUserIgnResolver.resolve()` filters out OCIDs whose `user_ign` is null/empty without any log or metric. Operators have no way to detect when the synchronizer write model is missing data.

## Decision

Add aggregate debug log to `resolve()` showing exclusion count + sample of excluded OCIDs.

## Scope

**Modify:** `module-synchronizer/src/main/kotlin/maple/synchronizer/resolver/OcidUserIgnResolver.kt:14-30`

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
- `mapping.filterValues { it.isEmpty() }` runs first to keep excluded count available.
- `excluded.keys.take(5)` bounds payload.
- Original happy-path log retained as `else` branch.

## Out of Scope

- Counter/metric (issue requests log only).
- Behavior change (filter still applied).
- New tests (log-only, behavior unchanged).

## Trade-offs

### Sensitivity

- Log volume: 1 line per call regardless of excluded count. Bounded.
- Sample size (5): chosen as low-cardinality; no PII risk because OCID is an internal ID, not IGN.

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| aggregate log w/ sample | bounded volume, actionable | tiny 2-pass overhead on small map |
| per-IGN log | full audit | log spam when many exclusions |
| metric + log | aggregate visibility + time series | new infra |

→ Chose aggregate (issue request).

### Risk

- `excluded.keys` ordering: `LinkedHashMap` preserves insertion order from `associate { }`. Stable, no concern.
- `take(5)` allocates a new list — fine for small sizes.

### Non-Risk

- No SQL change.
- No new dependency.
- No API surface change.

## Result / Evidence

Will be measured by:
- `./gradlew compileKotlin compileJava --continue` exits 0
- `./gradlew :module-synchronizer:test` passes
- Diff <10 lines

## Summary

> Add aggregate debug log to `OcidUserIgnResolver.resolve()` showing count + sample of OCIDs excluded for empty user_ign; no behavior change.
