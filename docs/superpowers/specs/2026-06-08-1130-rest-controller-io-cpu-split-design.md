# Issue #1130: module-rest-controller IO/CPU 분리 + ReadModelQueryService gzip+JSON offload

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1130
- Label: ready-for-human
- Blocked by: #1125 (MERGED in PR #1199)
- Blocks (indirect): #1198 saturation follow-up

## Goal

`BatchReadScheduler` (Spring `@Scheduled`) 와 `ReadModelQueryService.batchQuery()` (mixed caller), `ExpectationV6Controller.getStatus()` (sync controller) 의 IO/CPU 혼재를 해소. CPU 작업 (gzip decompress, JSON parse) 을 `Dispatchers.Default` 로 offload, IO (Redis multiGet, JDBC query, Redis pipeline write) 는 caller thread 또는 designated IO executor 유지.

## Background

### 문제

3 file 모두 mixed IO/CPU:
- `BatchReadScheduler.@Scheduled` thread: Redis multiGet + JDBC query + per-row gzip decompress + JSON parse + Redis pipeline write. CPU 가 scheduler thread block → 다음 drain 지연.
- `ReadModelQueryService.batchQuery()`: per-row gzip decompress + JSON parse. Caller (`BatchReadScheduler`, `ExpectationV6Controller`) thread 에서 실행.
- `ExpectationV6Controller.getStatus()`: 현재 sync. Redis IO + JDBC IO + gzip+JSON CPU 가 Tomcat thread.

### Best Practice 결정 (3 file, file별 최적 패턴)

| File | Pattern 선택 | 근거 |
|---|---|---|
| `BatchReadScheduler` | **Suspend fun refactor** | `@Scheduled` 는 suspend fun 직접 가능. `coroutineScope` 안에서 IO/CPU 분기 자연스러움. |
| `ReadModelQueryService.batchQuery()` | **`CompletableFuture<List<X>>` 반환 + `supplyAsync(Default.asExecutor())` for CPU** | caller 2개 (BatchReadScheduler, Controller) 모두 CompletableFuture chain 가능. ADR-723 §23.3 multi-threaded consumer 가이드 일치. |
| `ExpectationV6Controller.getStatus()` | **`CompletableFuture<X>` 반환** (기존 `getExpectation()` async 패턴 동일) | Spring async controller 패턴. caller 가 `supplyAsync` 로 IO executor 에 dispatch. |

## Architecture

### File 1: BatchReadScheduler (suspend fun refactor)

```kotlin
// 기존: @Scheduled method 가 sync (Tomcat-like scheduler thread)
@Scheduled(fixedDelayString = "...:10ms")
fun drain() { ... }

// 변경 후: suspend fun + coroutineScope
@Scheduled(fixedDelayString = "...:10ms")
suspend fun drain() = coroutineScope {
    val keys = redisTemplate.opsForValue().multiGet(...)  // IO on caller dispatcher
    if (keys.isEmpty()) return@coroutineScope
    val queryResult = withContext(Dispatchers.IO) { jdbcQuery(keys) }  // DB IO
    val parsed = withContext(Dispatchers.Default) { gzipDecompressAndParse(queryResult) }  // CPU
    withContext(Dispatchers.IO) { redisPipelineWrite(parsed) }  // IO
    metrics.recordDrain(...)
}
```

### File 2: ReadModelQueryService.batchQuery() (CompletableFuture 반환)

```kotlin
// 기존: List<X> sync 반환
fun batchQuery(keys: List<String>): List<X> { ... }

// 변경 후: CompletableFuture<List<X>> 반환
fun batchQuery(keys: List<String>): CompletableFuture<List<X>> =
    CompletableFuture.supplyAsync({ jdbcQuery(keys) }, ioExecutor)  // IO
        .thenApplyAsync({ rows -> withContext(Dispatchers.Default) { gzipDecompressAndParse(rows) } }, Default.asExecutor())  // CPU
```

### File 3: ExpectationV6Controller.getStatus() (CompletableFuture)

```kotlin
// 기존: X sync 반환
@GetMapping("/status")
fun getStatus(@PathVariable ign: String): StatusResponse { ... }

// 변경 후: CompletableFuture<StatusResponse> 반환 (Spring async pattern)
@GetMapping("/status")
fun getStatus(@PathVariable ign: String): CompletableFuture<StatusResponse> =
    readModelQueryService.batchQuery(listOf(ign))
        .thenApply { rows -> StatusResponse(...) }
```

## 산출 파일 (3 file modify)

| File | 작업 | 핵심 |
|---|---|---|
| `module-rest-controller/.../scheduler/BatchReadScheduler.kt` (if exists) | Modify | suspend fun refactor |
| `module-rest-controller/.../service/ReadModelQueryService.kt` | Modify | `batchQuery()` returns CompletableFuture |
| `module-rest-controller/.../controller/v6/ExpectationV6Controller.kt` | Modify | `getStatus()` returns CompletableFuture |

(BatchReadScheduler.kt 가 develop HEAD 에 없으면 worktree-only → restore 필요.)

## Acceptance Criteria 매핑

| #1130 AC | 충족 |
|---|---|
| BatchReadScheduler의 CPU 작업(gzip+JSON)이 Dispatchers.Default에서 실행 | suspend fun refactor 안의 `withContext(Dispatchers.Default)` |
| ReadModelQueryService.batchQuery()의 gzip+JSON parse가 Dispatchers.Default에서 실행 | `thenApplyAsync` with `Dispatchers.Default.asExecutor()` |
| BatchReadScheduler drain latency 개선 | CPU off scheduler thread |
| ExpectationV6Controller.getStatus() Tomcat thread block 시간 감소 | CompletableFuture 반환 → async |
| ./gradlew :module-rest-controller:test 통과 | refactor only, default dispatcher 변경 없음 |

## Testing / Verification

```bash
# 1. compile
./gradlew :module-rest-controller:compileKotlin --continue
# Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만)

# 2. test
./gradlew :module-rest-controller:test
# Expected: 기존 test 모두 통과 (signature 변경 시 test 도 update)

# 3. grep 검증
grep -rn "withContext(Dispatchers\.Default)" --include='*.kt' module-rest-controller 2>/dev/null
# Expected: 2+ hits (BatchReadScheduler, ReadModelQueryService batchQuery)

grep -rn "CompletableFuture.supplyAsync(Dispatchers\.Default\.asExecutor" --include='*.kt' module-rest-controller 2>/dev/null
# Expected: 1+ hit (ReadModelQueryService)

grep -rn "fun getStatus.*CompletableFuture" --include='*.kt' module-rest-controller 2>/dev/null
# Expected: 1 hit
```

## 영향 범위 (Out of Scope)

- ❌ `ExpectationV6Controller.getExpectation()` 는 이미 async. 본 PR scope 외.
- ❌ Module-synchronizer / module-app 동일 pattern — #1129 / #1131 별도.
- ❌ 새 ADR — ADR-723 §23.3 pattern 적용만.
- ❌ Runtime 부하테스트 — #1198 saturation metric 와 동시.

## Follow-up Issues (PR verification 단계에서 자동 생성)

1. **#TBD: BatchReadScheduler.kt 가 develop HEAD 에 없으면 restore 필요** — worktree-only 가능성.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| BatchReadScheduler.kt 가 develop HEAD 에 없음 (worktree-only) | High | High | PR verification 단계 에서 `git checkout HEAD -- <file>` 로 restore. 또는 follow-up 으로 분리. |
| ReadModelQueryService 의 CompletableFuture 반환 시 caller (현재 sync 가정) compile fail | Medium | High | caller 변경 (BatchReadScheduler, ExpectationV6Controller) 동시 처리. |
| `@Scheduled` suspend fun 직접 호출 가능 여부 | Low | Low | Spring 6.1+ 지원. project 버전 확인 필요. 미지원 시 `runBlocking { drain() }` wrapper. |

## Self-Review Check

- [x] Placeholder: 없음
- [x] Internal consistency: 3 file 의 pattern 정합 (suspend fun / CompletableFuture / CompletableFuture)
- [x] Scope: 단일 PR, bounded
- [x] Ambiguity: file별 pattern 명확
- [x] AC coverage: 5 AC 모두 file/pattern 매핑

## Related

- Spec: 이 파일
- ADR-723: `docs/01_ADR/ADR-723_io-cpu-split-pattern.md`
- Plan (후속): `docs/superpowers/plans/2026-06-08-1130-rest-controller-io-cpu-split.md`
- Issue #1130: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1130
- Predecessor: #1125, #1128, #1129
- Sibling: #1131, #1198
