# Issue #1130: Rest-Controller IO/CPU Split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 3 file 의 IO/CPU 분리. BatchReadScheduler suspend fun refactor, ReadModelQueryService.batchQuery() CompletableFuture 반환, ExpectationV6Controller.getStatus() CompletableFuture 반환.

**Architecture:** 3 file mechanical refactor. suspend fun 패턴 + CompletableFuture chain. ADR-723 §23.3 적용.

**Tech Stack:** Kotlin, kotlinx-coroutines, Spring Boot 3.5, CompletableFuture, JUnit 5

---

## File Structure

| 파일 | 작업 | 책임 |
|---|---|---|
| `module-rest-controller/.../scheduler/BatchReadScheduler.kt` (restore if missing) | Modify | suspend fun `@Scheduled` |
| `module-rest-controller/.../service/ReadModelQueryService.kt` | Modify | `batchQuery()` returns CompletableFuture<List<X>> |
| `module-rest-controller/.../controller/v6/ExpectationV6Controller.kt` | Modify | `getStatus()` returns CompletableFuture<StatusResponse> |

**작은 변경 (3 file, ~80 lines total). refactor only.**

---

## Task 1: Setup worktree + branch + restore BatchReadScheduler (if missing)

- [ ] **Step 1: branch 확인**

```bash
git branch --show-current
```

Expected: `feature/1130-rest-controller-io-cpu-split`. (이미 #1130 의 spec cherry-pick 으로 같은 branch.)

- [ ] **Step 2: BatchReadScheduler.kt develop HEAD 존재 확인**

```bash
git ls-tree -r HEAD module-rest-controller/src/main/kotlin/ | grep -i "BatchReadScheduler" 2>&1
```

Expected: 1 match. (없으면 `git checkout HEAD -- <file>` 또는 worktree restore — spec risk 참조)

---

## Task 2: Modify ReadModelQueryService.kt — `batchQuery()` returns CompletableFuture

- [ ] **Step 1: 현재 함수 signature + body 확인**

```bash
grep -nE "fun batchQuery|fun .*ReadModel|return|CompletableFuture" module-rest-controller/src/main/kotlin/maple/restcontroller/service/ReadModelQueryService.kt 2>&1 | head -20
```

- [ ] **Step 2: imports + signature 변경**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture

// batchQuery 의 return type List<X> → CompletableFuture<List<X>>
fun batchQuery(keys: List<String>): CompletableFuture<List<X>> =
    CompletableFuture.supplyAsync({ jdbcQuery(keys) }, ioExecutor)  // IO
        .thenApplyAsync({ rows ->
            withContext(Dispatchers.Default) {
                // CPU: gzip decompress + JSON parse per row
                rows.map { row -> gzipDecompressAndParse(row) }
            }
        }, Dispatchers.Default.asExecutor())
```

(실제 row/parse function/type 명세는 file current code 에 따라 결정. 위 는 illustrative.)

- [ ] **Step 3: compile + commit**

```bash
./gradlew :module-rest-controller:compileKotlin --continue 2>&1 | tail -5
git add module-rest-controller/src/main/kotlin/maple/restcontroller/service/ReadModelQueryService.kt
git commit -m "refactor(rest): ReadModelQueryService.batchQuery CompletableFuture 반환 (#1130)"
```

**Caveat:** signature 변경 → caller (BatchReadScheduler, ExpectationV6Controller) compile fail. Task 3-4 에서 update.

---

## Task 3: Modify ExpectationV6Controller.kt — `getStatus()` returns CompletableFuture

- [ ] **Step 1: imports + signature 변경**

```kotlin
// imports 추가
import java.util.concurrent.CompletableFuture

// 기존 sync 반환 → CompletableFuture 반환
@GetMapping("/{ign}/status")
fun getStatus(@PathVariable ign: String): CompletableFuture<StatusResponse> =
    readModelQueryService.batchQuery(listOf(ign))
        .thenApply { rows -> /* build StatusResponse */ }
```

(실제 build StatusResponse 로직은 current code 따름.)

- [ ] **Step 2: compile + commit**

```bash
./gradlew :module-rest-controller:compileKotlin 2>&1 | tail -5
git add module-rest-controller/src/main/kotlin/maple/restcontroller/controller/v6/ExpectationV6Controller.kt
git commit -m "refactor(rest): ExpectationV6Controller.getStatus CompletableFuture 반환 (#1130)"
```

---

## Task 4: Modify BatchReadScheduler.kt — suspend fun refactor

- [ ] **Step 1: imports + `@Scheduled` method 변경**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// 기존 @Scheduled fun drain() { ... }
// 변경 후:
@Scheduled(fixedDelayString = "\${synchronizer.batch-read.fixed-delay-ms:10}")
suspend fun drain() = coroutineScope {
    val keys = redisTemplate.opsForValue().multiGet(...) ?: return@coroutineScope
    if (keys.isEmpty()) return@coroutineScope

    val queryResult = withContext(Dispatchers.IO) { jdbcQuery(keys) }
    val parsed = withContext(Dispatchers.Default) {
        queryResult.map { row -> /* gzip decompress + JSON parse */ }
    }
    withContext(Dispatchers.IO) { redisPipelineWrite(parsed) }
    metrics.recordDrain(...)
}
```

(`@Scheduled` 의 suspend fun 직접 지원 여부는 Spring 6.1+. project 버전 확인. 미지원 시 `runBlocking { drain() }` wrapper. plan 의 risk 표 참조.)

- [ ] **Step 2: compile + commit**

```bash
./gradlew :module-rest-controller:compileKotlin 2>&1 | tail -5
git add module-rest-controller/src/main/kotlin/maple/restcontroller/scheduler/BatchReadScheduler.kt
git commit -m "refactor(rest): BatchReadScheduler suspend fun refactor (#1130)"
```

---

## Task 5: Final verification + push + PR

- [ ] **Step 1: full compile + test**

```bash
./gradlew :module-rest-controller:compileKotlin :module-rest-controller:test --continue 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만).

- [ ] **Step 2: grep 검증**

```bash
grep -rn "withContext(Dispatchers\.Default)" --include='*.kt' module-rest-controller 2>/dev/null
# Expected: 2+ hits (BatchReadScheduler drain + ReadModelQueryService batchQuery)

grep -rn "CompletableFuture.supplyAsync(Dispatchers\.Default\.asExecutor" --include='*.kt' module-rest-controller 2>/dev/null
# Expected: 1 hit (ReadModelQueryService batchQuery)
```

- [ ] **Step 3: push + PR (Closes #1130)**

```bash
git push -u origin feature/1130-rest-controller-io-cpu-split
gh pr create --base develop --head feature/1130-rest-controller-io-cpu-split \
    --title "refactor(rest): IO/CPU 분리 + ReadModelQueryService batchQuery CompletableFuture (Closes #1130)" \
    --body "..."
```

Expected: PR URL. `Closes #1130` 자동 close.

---

## Self-Review Checklist

- [x] **Spec coverage:** 3 file 모두 Task 2-4 에 매핑. BatchReadScheduler suspend fun (Task 4), ReadModelQueryService CompletableFuture (Task 2), ExpectationV6Controller CompletableFuture (Task 3).
- [x] **Type/symbol consistency:** `CompletableFuture<List<X>>` (Task 2) ↔ `thenApply` (Task 3) ↔ `batchQuery(listOf(ign))` (Task 3 caller) 정합.
- [x] **Frequent commits:** Task 1-5 각각 1 commit.

## Execution Notes

- Task 2-3 signature 변경 → caller 의존적 compile fail. Task 2-3 을 commit 후 Task 4 에서 마지막 caller 갱신. 또는 모두 한 commit 에서 동시.
- `@Scheduled` 의 suspend fun 지원: Spring 6.1+ (Spring Boot 3.2+). 미지원 시 `runBlocking { drain() }` wrapper. Plan 의 risk 표 참조.
- PR 본문에 `Closes #1130` keyword — 자동 close.
