# Spec: Synchronizer File Reader Silent-Parse Logging (Issue #1019)

- Status: Approved
- Date: 2026-06-04
- Issue: #1019
- Owner: zbnerd

## Background

`OcidMappingFileReader.parseMapping()` and `BasicChunkFileReader.parseRecord()` swallow malformed/unexpected records via `runCatching{}.getOrNull()` and `return null`. Operators have no way to know why records were skipped — silent data loss is hard to diagnose.

## Decision

Add `log.debug` at every silent-fail path:

1. Each `return null` site gets a reason-tagged debug log.
2. Each `runCatching{}.getOrNull()` gets `.onFailure { log.debug(...) }` so Jackson parse exceptions are visible.
3. Use `log.debug` (not `warn`) — status/endpoint mismatches are normal queue routing, not errors.

## Scope

### `OcidMappingFileReader.parseMapping()` (module-synchronizer/.../storage/OcidMappingFileReader.kt:42-48)

- `userIgn` missing → `log.debug("skip mapping: reason=missing_userIgn"); return null`
- `ocid` missing → `log.debug("skip mapping: reason=missing_ocid"); return null`
- `runCatching` → `.onFailure { log.debug("mapping parse fail: {}", it.message) }.getOrNull()`

### `BasicChunkFileReader.parseRecord()` (module-synchronizer/.../storage/BasicChunkFileReader.kt:94-123)

- `status != SUCCESS` → `log.debug("skip record: reason=status_mismatch actual={}", actual); return null`
- `endpoint != character-basic` → `log.debug("skip record: reason=endpoint_mismatch actual={}", actual); return null`
- `key` (ocid) missing → `log.debug("skip record: reason=missing_ocid"); return null`
- `body` missing → `log.debug("skip record: reason=missing_body"); return null`
- `character_name` missing → `log.debug("skip record: reason=missing_character_name"); return null`
- `runCatching` → `.onFailure { log.debug("record parse fail: {}", it.message) }.getOrNull()`

## Out of Scope

- Counter/metric for skip rate (issue does not request)
- Sample line capture in logs (would inflate log size on bulk data)
- New unit tests (logging-only, no behavior change)

## Trade-offs

### Sensitivity

- Log volume on large files: 1 line per skipped record. For typical sync runs skip rate is low, but worst case (all-mismatched) would be 1 line per record. `debug` level keeps this opt-in via log config.

### Trade-off

| Choice | Gain | Cost |
| --- | --- | --- |
| inline log at each return | min diff, surgical, no signature change | 8 lines added |
| extract `skip(reason)` helper | DRY | new private fn + Nothing return type, larger blast radius |

→ Chose inline (issue requests surgical fix).

### Risk

- Log redaction: line payloads may contain user data. `debug` is opt-in and synchronous files are local; acceptable.
- Null-safe logging of `actual` status/endpoint: `?.asText()` already returns `String?` — `String?` in `{}` logs as `null` literal, no NPE.

### Non-Risk

- Runtime behavior unchanged: all paths still return `null`, callers unchanged.
- No API surface change.

## Result / Evidence

Will be measured by:
- `./gradlew compileKotlin compileJava --continue` exits 0
- `./gradlew test` passes
- Diff is <20 lines added across 2 files

## Summary

> Add `log.debug` at 8 silent-return sites + 2 `runCatching.onFailure` in 2 synchronizer file readers; no behavior change.
