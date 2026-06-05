# Spec: Split OcidMappingRepository into DB and Redis (#1077)

- Status: Draft
- Date: 2026-06-04
- Owner: TBD
- Issue: #1077

## 1. Background / Problem

`OcidMappingRepository` (module-synchronizer, 91 lines) holds two methods
with zero shared dependencies:

- `batchUpsert(mappings)` — JDBC only (PostgreSQL `COPY` upsert via temp table)
- `writeOcidToRedis(mappings)` — Redis only (pipelined `HSET` + atomic `RENAME`)

The class is a coincidence of co-location, not a single responsibility.
`OcidLookupRunConsumer` already invokes them with independent error
handling (DB call propagates, Redis call wrapped in `runCatching`),
confirming they are distinct operations.

Two independent responsibilities in one class make it harder to:
- reason about dependencies (`redisTemplate` is injected but unused by
  `BasicSnapshotChunkConsumer`, the other caller)
- test the DB path in isolation without a Redis connection
- reason about failure modes (Redis failure must not roll back DB
  write, but a single class hides the boundary)

### Goal

Split into two focused classes with no behavioral change. Caller injection
updates only.

## 2. Decision

Split `OcidMappingRepository` mechanically:

| Class | Package | Deps | Method |
|-------|---------|------|--------|
| `OcidMappingRepository` (kept, slimmed) | `maple.synchronizer.repository` | `NamedParameterJdbcTemplate` | `batchUpsert(mappings)` |
| `OcidMappingRedisWriter` (new) | `maple.synchronizer.redis` | `StringRedisTemplate` | `writeOcidToRedis(mappings)` |

Name `OcidMappingRepository` stays. The DB-only class is still a
repository; the issue body allowed rename as optional, and the user
chose to keep the name.

```text
OcidLookupRunConsumer
  ├── fileReader
  ├── ocidMappingRepository: OcidMappingRepository          (DB)
  └── ocidMappingRedisWriter: OcidMappingRedisWriter        (Redis, new)

BasicSnapshotChunkConsumer
  └── ocidMappingRepository: OcidMappingRepository          (DB only, unchanged)
```

## 3. Trade-offs

### Sensitivity

- Number of Redis writers in module-synchronizer (currently 0 new) —
  none yet, this is the first
- Existing log search patterns keyed on `[OcidMapping]` prefix —
  must be preserved on both sides
- Caller `BasicSnapshotChunkConsumer` constructor parameter order
  and test mocks — must remain stable

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Mechanical split only | Minimal blast radius, exact issue scope | DB class still has temp table + COPY complexity in one method |
| Split + port interface (rejected) | Hexagonal purity | Module-synchronizer has no port/adapter split; adding ports is scope creep |
| Rename to `OcidMappingDbRepository` (rejected) | Stronger signal | Forces update of all callers and tests; no behavioral benefit |

### Risk

- `OcidLookupRunConsumer` constructor parameter changes — if a
  caller (e.g. a test) constructs it manually, breaks. Verified: only
  `OcidLookupRunConsumer` is constructed via Spring DI; no test
  constructs it directly. (`ChunkConsumerMappingTest` constructs
  `BasicSnapshotChunkConsumer` which is unaffected.)
- Log line format changes — preserved intentionally. Both classes
  use `[OcidMapping]` prefix.

### Non-Risk

- Behavior change in DB or Redis path — none, pure code move.
- Test coverage regression — `BasicSnapshotChunkConsumer` test path
  is unchanged (still uses DB-only class).
- Transaction boundary change — `batchUpsert` keeps its existing
  `ConnectionCallback` with explicit `commit`/`rollback`.

## 4. Components

### `OcidMappingRepository` (slimmed)

```kotlin
@Repository
class OcidMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun batchUpsert(mappings: List<OcidMapping>) { /* unchanged */ }
}
```

- Removes: `redisTemplate` field, `REDIS_KEY` constant,
  `writeOcidToRedis()` method, `StringRedisTemplate` import,
  `BasicRecord`→`OcidMapping` not relevant (this class didn't have it).
- Keeps: `batchUpsert()` body unchanged.

### `OcidMappingRedisWriter` (new)

```kotlin
package maple.synchronizer.redis

@Component
class OcidMappingRedisWriter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val REDIS_KEY = "ocid:mapping"
    }

    fun writeOcidToRedis(mappings: List<OcidMapping>) {
        // moved verbatim from OcidMappingRepository.writeOcidToRedis
    }
}
```

- Log prefix preserved: `[OcidMapping] Redis cleared/written atomically...`
- Method signature unchanged. Caller invokes by same name.

### `OcidLookupRunConsumer` (caller A, updated)

```kotlin
class OcidLookupRunConsumer(
    private val fileReader: OcidMappingFileReader,
    private val repository: OcidMappingRepository,
    private val ocidMappingRedisWriter: OcidMappingRedisWriter,  // new
    private val objectMapper: ObjectMapper,
) {
    // ...
    repository.batchUpsert(mappings)              // DB (unchanged)
    runCatching {
        ocidMappingRedisWriter.writeOcidToRedis(mappings)  // Redis
    }.onFailure { ex -> log.error(...) }
}
```

### `BasicSnapshotChunkConsumer` (caller B, unchanged)

Continues to use only `ocidMappingRepository.batchUpsert()`.
No new dep.

## 5. File changes

| File | Action |
|------|--------|
| `module-synchronizer/.../repository/OcidMappingRepository.kt` | Remove `writeOcidToRedis`, `REDIS_KEY`, `redisTemplate` field, `StringRedisTemplate` import. |
| `module-synchronizer/.../redis/OcidMappingRedisWriter.kt` (new) | New file with moved Redis logic. |
| `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt` | Add `OcidMappingRedisWriter` constructor param; update `repository.writeOcidToRedis` → `ocidMappingRedisWriter.writeOcidToRedis`. |
| `module-synchronizer/.../consumer/BasicSnapshotChunkConsumer.kt` | No change. |
| `module-synchronizer/.../test/.../ChunkConsumerMappingTest.kt` | No change. |

## 6. Testing

- **Existing tests:** `BasicSnapshotChunkConsumer` test mocks
  `OcidMappingRepository` — unchanged. Test still passes.
- **No new unit tests:** issue acceptance says "No behavioral change".
  Split is a rename + move. New tests would only verify imports.
- **Compile gate:** `./gradlew :module-synchronizer:compileKotlin compileJava --continue`
- **Test gate:** `./gradlew :module-synchronizer:test`
- **Integration test:** skipped per `workflow-rules.md` rule
  (Testcontainers disabled by issue #207).

## 7. Out of scope

- Extracting SQL constants from `batchUpsert` — covered by issue #933
  (planned subsequent work).
- Renaming `OcidMappingRepository` to `OcidMappingDbRepository` —
  user decision: keep current name.
- Port/adapter interface extraction — module-synchronizer is infra-only.

## 8. Acceptance criteria (from #1077)

- [ ] Redis write logic extracted to a new class
- [ ] Original repository focused on DB operations only
- [ ] Caller updated to inject both classes
- [ ] `./gradlew :module-synchronizer:compileKotlin compileJava --continue` passes
- [ ] `./gradlew :module-synchronizer:test` passes
- [ ] No behavioral change

## 9. Summary

> Mechanical split of `OcidMappingRepository` into DB-only repository
> and new `OcidMappingRedisWriter` (redis sub-package). One caller
> updated, one caller unchanged. Zero behavioral change.
