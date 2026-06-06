# Like Port Hypothetical Seam Removal (6 dead + 1 alive)

- Date: 2026-06-06
- Owner: TBD
- Related: #897, ADR-391
- Supersedes: previous sketch of "6→2 merge" (that sketch was based on assumption of parallel Redis adapters that do not exist)

---

## 1. Background / Problem

### Background

ADR-391 + #897 audit classified 49 outbound ports. Like-related ports (6) were flagged as having overlapping responsibilities. Specification #1154 §5 sketched a 6→2 merge.

### Problem — actual state of Like ports

Codebase grep reveals that **6 of 7 Like ports are dead hypothetical seams**: interface declared in `module-core/.../port/out/` with zero adapter implementations and zero non-port consumers in `module-core`/`module-infra`. The 7th port (the "6→2" merge referred to 6 Like ports, but the `like/` subdir actually contained 6 — `LikeAtomicFetchStrategy` + `CompensationCommand` — plus 4 ports in `port/out/` root = 7 files total).

| Port | Location | Adapter impls | Non-port consumers | Status |
|------|----------|---------------|--------------------|--------|
| `LikeAtomicFetchStrategy` | `port/out/like/` | 0 | 0 | **Dead hypothetical seam** |
| `CompensationCommand` | `port/out/like/` | 0 | 0 | **Dead hypothetical seam** |
| `LikeBufferStrategy` | `port/out/` | 1 (`InMemoryLikeBufferStorage`) | 4 (`DatabaseLikeProcessor`, `LikeSyncExecutor`, `BufferedLikeAspect`, `InMemoryLikeBufferStorage` impl) + 2 module-app test files | **Alive** |
| `LikeRelationBufferStrategy` | `port/out/` | 0 | 0 | **Dead hypothetical seam** |
| `LikeSyncPort` | `port/out/` | 0 (deprecated by #664, no-op) | 0 | **Dead deprecated seam** |
| `LikeRelationSyncPort` | `port/out/` | 0 | 0 | **Dead hypothetical seam** |
| `LikeEventPublisher` | `port/out/` | 0 | 1 legacy test (`LikeRealtimeSyncIntegrationTest` uses `@Autowired(required = false)` so will compile with null) | **Dead hypothetical seam** |

`LikeBufferStrategy` is the only port with a real adapter. The "6→2 merge" sketch in #1154 was based on assumed parallel Redis adapter pairs (`RedisLikeBufferAdapter`/`RedisLikeAtomicFetchAdapter`/`RedisLikeRelationBufferAdapter`/`RedisLikeRelationSyncAdapter`/`KafkaLikeEventPublisher`) that **were never implemented** — they exist only in port-interface KDoc.

### Goal

Delete the 6 dead port files (4 in `port/out/` + 2 in `port/out/like/`). Keep `LikeBufferStrategy` as-is. No new ports created. No new adapters created.

---

## 2. Decision

> Delete 6 dead port files from `module-core`. Do not create new ports. `LikeBufferStrategy` remains the single outbound port for like buffer concerns.

```text
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/like/LikeAtomicFetchStrategy.kt
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/like/CompensationCommand.kt
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationBufferStrategy.kt
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationSyncPort.kt
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/LikeSyncPort.kt
DELETE: module-core/src/main/kotlin/maple/expectation/core/port/out/LikeEventPublisher.kt
        (also remove the LikeEventSubscriber nested interface — no consumers)

KEEP:   module-core/src/main/kotlin/maple/expectation/core/port/out/LikeBufferStrategy.kt
        (and its adapter InMemoryLikeBufferStorage.kt — already wired)
```

The 6→2 merge sketch is abandoned because the premise (parallel Redis adapters) was wrong. Single-PR deletion is safe because no live code path depends on the 5 removed ports.

---

## 3. Migration Impact

### Files touched (all deletions, no new files)

| File | Reason |
|------|--------|
| `module-core/src/main/kotlin/.../port/out/like/LikeAtomicFetchStrategy.kt` | Dead seam |
| `module-core/src/main/kotlin/.../port/out/LikeRelationBufferStrategy.kt` | Dead seam |
| `module-core/src/main/kotlin/.../port/out/LikeRelationSyncPort.kt` | Dead seam |
| `module-core/src/main/kotlin/.../port/out/LikeSyncPort.kt` | Dead deprecated |
| `module-core/src/main/kotlin/.../port/out/LikeEventPublisher.kt` | Dead seam (also drops `LikeEventSubscriber` nested iface) |
| `module-core/src/main/kotlin/.../port/out/like/` directory | Becomes empty — remove directory |

### Files NOT touched (alive port path)

* `module-infra/.../cache/like/InMemoryLikeBufferStorage.kt` — impl stays
* `module-infra/.../aop/aspect/BufferedLikeAspect.kt` — consumer stays
* `module-infra/.../queue/like/LikeSyncExecutor.kt` — consumer stays (deprecated, but still wired)
* `module-infra/.../like/DatabaseLikeProcessor.java` — consumer stays
* `module-app/src/test/kotlin/.../PgmqClientIntegrationTest.kt` — `@Autowired` of `LikeBufferStrategy` stays
* `module-app/src/test/kotlin/.../PgmqTransactionAtomicityTest.kt` — same

### Files with safe side-effect (no edit needed)

* `module-app/src/test-legacy/.../LikeRealtimeSyncIntegrationTest.java` — uses `@Autowired(required = false)` for `LikeEventPublisher`. With the port removed, the autowire resolves to `null` and the test continues to compile. The `@DisplayName("LikeEventPublisher Bean이 정상 생성됨")` test (line 106) will now check `null` — must be updated to assert `null` or the test method deleted (decision: delete the test, see plan Task 4).

---

## 4. Trade-offs

### Sensitivity

* Compile-time port surface area (5 file deletions propagate to KDoc references — verified zero)
* Boot classpath scan (no Spring beans of removed types — verified zero)
* Legacy test relying on removed type (`LikeEventPublisher`) — must update assertion

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 5개 port file 삭제 (no new port) | dead code 0, surface 6→1, cognitive load ↓↑ | 향후 Redis adapter 추가 시 새 port 정의 필요 |
| `LikeBufferStrategy` 유지 (rename 안 함) | caller 변경 없음, import stable | port name이 buffer-only로 좁아짐 (현재도 그럼) |
| Legacy test method 삭제 | 깨끗 | 테스트 커버리지 1개 감소 (실질적 가치 없음 — port가 dead) |

### Risk

* Legacy test (`LikeRealtimeSyncIntegrationTest`) references `LikeEventPublisher` only in `@Autowired(required = false)` field + 1 assertion test. With the port deleted, the field type becomes unresolved → compile error. Must remove the field and the assertion test method.
* KDoc in `LikeBufferStrategy` mentions "Redis 구현" by class name `RedisLikeBufferStorage` — this class does not exist in current code. Will fix in the same PR to avoid future confusion.

### Non-Risk

* Production runtime: no live code path depends on the 5 removed ports
* Boot context: no Spring beans of removed types
* Test fakes: no test code mocks the removed ports

---

## 5. Migration Plan (single PR)

1. Update KDoc in `LikeBufferStrategy.kt` to remove references to nonexistent `RedisLikeBufferStorage`
2. Delete 5 port files (see §3)
3. Update `LikeRealtimeSyncIntegrationTest.java` — remove the `LikeEventPublisher` field and the assertion test
4. Verify compile + test
5. PR

---

## 6. Test Strategy

* `./gradlew compileKotlin compileJava --continue` — must pass
* `./gradlew test` — existing like tests (`InMemoryLikeBufferStorageTest`, `LikeToggleServiceTest`) must still pass
* Legacy `LikeRealtimeSyncIntegrationTest` should still compile (only the 1 assertion method is removed; rest of test class untouched)

Coverage target: no regression on `LikeBufferStrategy` impl (already has unit test). Removed ports had no test coverage to begin with.

---

## 7. Success Signal

* LOC: 6 port files (~280 LOC) → 1 port file (~90 LOC) = -190 LOC
* Files: 6 → 1, plus empty `like/` subdir removal
* Tests: existing like tests pass; legacy test compiles with 1 method removed

---

## 8. Out of Scope

* Monitoring port 7→2 merge (PR2)
* Inbound port consolidation (not in #897 scope)
* Renaming `LikeBufferStrategy` to something more general (YAGNI — current name matches actual responsibility)
