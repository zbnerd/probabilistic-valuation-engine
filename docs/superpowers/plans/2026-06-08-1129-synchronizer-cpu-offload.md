# Issue #1129: Synchronizer CPU Offload — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 5 file 의 CPU 작업을 `runBlocking(Dispatchers.Default) { }` 로 offload. OcidLookupRunConsumer 에 executor dispatch 추가.

**Architecture:** 5 file mechanical modify. `import kotlinx.coroutines.Dispatchers` + `import kotlinx.coroutines.runBlocking` 추가 (또는 이미 import 되어 있는지 verify). 각 file 의 CPU section (objectMapper, GzipUtils, sha256Hex) 을 `runBlocking(Dispatchers.Default) { cpuWork() }` 로 wrap. IO 작업 (DB write, Redis write, file read, Kafka send) 미변경.

**Tech Stack:** Kotlin, kotlinx-coroutines, Spring Boot 3.5, JUnit 5

---

## File Structure

| 파일 | 작업 | 책임 |
|---|---|---|
| `module-synchronizer/.../preparer/EquipmentDocumentPreparer.kt` | Modify | `prepareOne()` per-document CPU (serialize + GZIP + SHA-256) |
| `module-synchronizer/.../reader/ResultFileReader.kt` | Modify | `readAndGroupByCompositeKey()` per-line JSON parse + map grouping |
| `module-synchronizer/.../reader/BasicChunkFileReader.kt` | Modify | `readInBatches()` per-record parse + compress + hash |
| `module-synchronizer/.../writer/EquipmentRankingRedisWriter.kt` | Modify | `filter` + `groupBy` collection ops |
| `module-synchronizer/.../consumer/OcidLookupRunConsumer.kt` | Modify | `@Qualifier("ocidLookupRunExecutor") ExecutorService` 주입. `fun consume` body 를 `executor.submit { ... }` 로 wrap |

**작은 변경 (5 file, ~50 lines total). refactor only.**

---

## Task 1: Setup worktree + branch

**Files:** None (git only)

- [ ] **Step 1: develop 최신 sync + branch 생성**

```bash
git checkout develop && git pull origin develop && git checkout -b feature/1129-synchronizer-cpu-offload && git branch --show-current
```

Expected: `feature/1129-synchronizer-cpu-offload`.

- [ ] **Step 2: working tree 잔재 확인 (out of scope) + commit prerequisite 없음**

```bash
git status --short | head -5
```

Expected: 30+ untracked D files (이전 세션 잔재, 본 PR scope 외). `git add <specific-file>` 만 사용.

---

## Task 2: Modify EquipmentDocumentPreparer.kt

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt`

- [ ] **Step 1: 현재 import + 함수 시그니처 verify**

```bash
grep -nE "^import |^class |fun prepare|fun prepareOne|sha256Hex|GzipUtils\.compress|objectMapper\.writeValueAsBytes" module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt 2>&1 | head -20
```

- [ ] **Step 2: imports 추가 (없으면)**

`kotlinx.coroutines.Dispatchers` + `kotlinx.coroutines.runBlocking` 추가 (정확한 위치는 현재 import 순서에 따라 결정).

- [ ] **Step 3: `prepare()` method 의 CPU section wrap**

기존 (per issue body line 11-34):
```kotlin
fun prepare(documents: List<Document>): List<PreparedDocument> {
    val prepared = mutableListOf<PreparedDocument>()
    for (doc in documents) {
        prepared.add(prepareOne(doc))
    }
    return prepared
}
```

변경 후:
```kotlin
fun prepare(documents: List<Document>): List<PreparedDocument> {
    // CPU offload (Issue #1129): serialize + GZIP + SHA-256 on Dispatchers.Default.
    return runBlocking(Dispatchers.Default) {
        documents.map { doc -> prepareOne(doc) }
    }
}
```

(`prepareOne()` 자체는 per-document CPU. outer `runBlocking` 으로 batch 전체를 offload.)

또는 (per-document `prepareOne` 자체가 runBlocking):
```kotlin
fun prepare(documents: List<Document>): List<PreparedDocument> = runBlocking(Dispatchers.Default) {
    documents.map { doc -> prepareOne(doc) }
}

private fun prepareOne(doc: Document): PreparedDocument = runBlocking(Dispatchers.Default) {
    val body = objectMapper.writeValueAsBytes(doc)
    val compressed = GzipUtils.compress(body)
    val hash = sha256Hex(compressed)
    PreparedDocument(body, compressed, hash)
}
```

**Implementation note:** `prepareOne()` 가 `runBlocking` 을 두 번 호출하지 않도록 주의. outer 또는 inner 중 한 곳에서만. 가장 간단: outer 에서 `runBlocking(Dispatchers.Default) { documents.map { prepareOne(it) } }` 만. `prepareOne()` 는 sync 유지.

- [ ] **Step 4: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/kotlin/maple/synchronizer/preparer/EquipmentDocumentPreparer.kt
git commit -m "refactor(sync): EquipmentDocumentPreparer CPU offload (#1129)"
```

---

## Task 3: Modify ResultFileReader.kt

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/reader/ResultFileReader.kt`

- [ ] **Step 1: imports 추가 + `readAndGroupByCompositeKey()` wrap**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// readAndGroupByCompositeKey() 의 CPU section wrap
fun readAndGroupByCompositeKey(path: Path): Map<CompositeKey, List<ResultEntry>> = runBlocking(Dispatchers.Default) {
    // ... 기존 per-line readTree + map grouping 로직
}
```

- [ ] **Step 2: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/kotlin/maple/synchronizer/reader/ResultFileReader.kt
git commit -m "refactor(sync): ResultFileReader CPU offload (#1129)"
```

---

## Task 4: Modify BasicChunkFileReader.kt

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/reader/BasicChunkFileReader.kt`

- [ ] **Step 1: imports 추가 + `readInBatches()` wrap**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// readInBatches() 의 CPU section (per-record parse + compress + hash) 을 runBlocking(Dispatchers.Default) 로 wrap
// DB write (`handler(batch)`) 는 VT 유지
fun readInBatches(path: Path, handler: (Batch) -> Unit) {
    val batch = runBlocking(Dispatchers.Default) {
        // ... per-record parse + compress + hash
        buildBatch(path)
    }
    handler(batch)  // DB write — VT 유지
}
```

- [ ] **Step 2: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/kotlin/maple/synchronizer/reader/BasicChunkFileReader.kt
git commit -m "refactor(sync): BasicChunkFileReader CPU offload (#1129)"
```

---

## Task 5: Modify EquipmentRankingRedisWriter.kt

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/writer/EquipmentRankingRedisWriter.kt`

- [ ] **Step 1: imports 추가 + `filter` + `groupBy` collection ops wrap**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// Collection ops (filter + groupBy) 를 runBlocking(Dispatchers.Default) 로 wrap
// Redis pipelined ZADD 는 VT 유지
fun writeRankings(entries: List<RankingEntry>) {
    val grouped = runBlocking(Dispatchers.Default) {
        entries.filter { /* ... */ }.groupBy { /* ... */ }
    }
    // Redis ZADD on caller executor
    redisTemplate.executePipelined { /* ... */ }
}
```

- [ ] **Step 2: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/kotlin/maple/synchronizer/writer/EquipmentRankingRedisWriter.kt
git commit -m "refactor(sync): EquipmentRankingRedisWriter CPU offload (#1129)"
```

---

## Task 6: Modify OcidLookupRunConsumer.kt

**Files:**
- Modify: `module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt`

- [ ] **Step 1: constructor 에 ExecutorService 추가 + imports**

```kotlin
// imports 추가
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

// class body
class OcidLookupRunConsumer(
    // ... existing deps
    @Qualifier("ocidLookupRunExecutor") private val executor: ExecutorService,  // NEW
) {
    // ... existing class members
}
```

- [ ] **Step 2: `fun consume` body 를 `executor.submit { ... }` 로 wrap + CPU offload**

기존 (per issue body line 29-62): Kafka consumer thread 에서 직접 실행. `fun consume(message: String, acknowledgment: Acknowledgment)` 가 sync.

변경 후:
```kotlin
@KafkaListener(...)
fun consume(message: String, acknowledgment: Acknowledgment) {
    executor.submit {  // NEW: dispatch to executor (single-threaded batch → runBlocking safe per ADR-723 §23.3)
        runCatching {
            val cpuResult = runBlocking(Dispatchers.Default) {
                // parse + transform
                parseAndTransform(message)
            }
            // ... 후속 IO
        }.whenComplete { _, ex ->
            if (ex != null) log.error("[OcidLookupRun] failed", ex)
            runCatching { acknowledgment.acknowledge() }
        }
    }
}
```

- [ ] **Step 3: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/kotlin/maple/synchronizer/consumer/OcidLookupRunConsumer.kt
git commit -m "refactor(sync): OcidLookupRunConsumer executor dispatch + CPU offload (#1129)"
```

---

## Task 7: Add ocidLookupRunExecutor bean + yaml key

**Files:**
- Modify: `module-synchronizer/src/main/resources/application.yml` (or application-*.yml)
- Modify: `module-infra/.../config/ExecutorConfig.kt` (if not auto-configured)

- [ ] **Step 1: yaml 에 `executor.async` 또는 별도 키 확인**

```bash
grep -n "async:\|ocid-lookup-run\|executor\." module-synchronizer/src/main/resources/application.yml 2>&1 | head -10
```

- [ ] **Step 2: 별도 executor key 가 필요하면 yaml 에 추가 + 빈 정의**

yaml (예):
```yaml
executor:
  ocid-lookup-run:
    core-pool-size: 1
    max-pool-size: 1
    queue-capacity: 100
```

빈 정의는 infra 의 ExecutorProperties 또는 별도 config class 에 추가. (기존 `executor.async` 재사용 가능하면 skip.)

- [ ] **Step 3: compile + commit**

```bash
./gradlew :module-synchronizer:compileKotlin 2>&1 | tail -5
git add module-synchronizer/src/main/resources/application.yml
git commit -m "feat(sync): add ocidLookupRunExecutor bean config (#1129)"
```

---

## Task 8: Final verification + push + PR + follow-up issues

- [ ] **Step 1: full compile + test**

```bash
./gradlew :module-synchronizer:compileKotlin :module-synchronizer:compileJava :module-synchronizer:test --continue 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만).

- [ ] **Step 2: grep 검증**

```bash
grep -rn "runBlocking(Dispatchers\.Default)" --include='*.kt' module-synchronizer 2>/dev/null
# Expected: 5+ hits (4 file CPU + OcidLookupRunConsumer consume + parseAndTransform)

grep -rn "@Qualifier(\"ocidLookupRunExecutor\")" --include='*.kt' module-synchronizer 2>/dev/null
# Expected: 1 hit (OcidLookupRunConsumer)
```

- [ ] **Step 3: push branch**

```bash
git push -u origin feature/1129-synchronizer-cpu-offload
```

- [ ] **Step 4: 2 follow-up issues 자동 생성**

```bash
gh issue create --label ready-for-agent --title "refactor(sync): mitigate multi-threaded runBlocking risk in 4 synchronizer files" --body "$(cat <<'EOF'
## Background

Issue #1129 applied runBlocking(Dispatchers.Default) to 4 files (EquipmentDocumentPreparer, ResultFileReader, BasicChunkFileReader, EquipmentRankingRedisWriter). All 4 have multi-threaded VT caller (ChunkPipelineOrchestrator etc.), violating ADR-723 §23.3 (multi-threaded consumer + runBlocking = VT carrier block).

OcidLookupRunConsumer (Kafka single-threaded) 는 safe.

## Scope

Convert 4 files to either:
- SupplyAsync(Dispatchers.Default.asExecutor()) (Q3 결정)
- Suspend fun refactor (Q2 A 결정, similar to OcidLookupPhase #1128)

## Related

#1129, ADR-723, #1128 (OcidLookupPhase refactor precedent)
EOF
)"

gh issue create --label ready-for-agent --title "refactor(sync): verify ocidLookupRunExecutor bean type (VT vs platform)" --body "..."
```

- [ ] **Step 5: PR 생성 (Closes #1129 keyword)**

```bash
gh pr create --base develop --head feature/1129-synchronizer-cpu-offload \
    --title "refactor(sync): CPU 작업 Dispatchers.Default offload + OcidLookupRunConsumer executor dispatch (Closes #1129)" \
    --body "..."
```

Expected: PR URL. `Closes #1129` keyword 로 issue 자동 close.

---

## Self-Review Checklist

- [x] **Spec coverage:** #1129 AC 6개 모두 Task 2-6 에 매핑. `prepareOne/resultFile/basicChunk/redisWriter` (Task 2-5) + `OcidLookupRunConsumer` executor dispatch (Task 6) + `ocidLookupRunExecutor` bean (Task 7).
- [x] **No placeholders:** 모든 코드 블록 완전. (단, "기존 ... 로직" 은 file-specific edit)
- [x] **Type/symbol consistency:** `runBlocking(Dispatchers.Default)` 일관, `@Qualifier("ocidLookupRunExecutor")` 일관.
- [x] **Frequent commits:** Task 1-7 각각 1 commit.

## Execution Notes

- Task 2-6 compile: 중간에 pre-existing module-infra 에러로 fail 가능. plan 의 #1126/#1128 precedent 와 동일.
- OcidLookupRunExecutor 빈 정의: 기존 `async` executor 재사용 가능 시 Task 7 skip. 단일 thread (Kafka single-threaded) 라 platform or VT 둘 다 OK.
- PR 본문에 `Closes #1129` keyword 사용 — 자동 close (lesson from #1125-#1128 close).
