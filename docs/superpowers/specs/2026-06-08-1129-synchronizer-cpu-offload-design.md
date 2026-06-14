# Issue #1129: module-synchronizer CPU 작업 Dispatchers.Default Offload + OcidLookupRunConsumer executor dispatch

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1129
- Label: ready-for-agent
- Blocked by: #1125 (MERGED in PR #1199)
- Blocks (indirect): #1198 saturation follow-up

## Goal

`module-synchronizer` 의 5 file 에서 CPU-heavy 작업 (JSON parse/serialize, GZIP compress/decompress, SHA-256, large collection 변환) 을 `Dispatchers.Default` 로 offload. `OcidLookupRunConsumer` 에 executor dispatch 추가 (현재 Kafka consumer thread 직접 실행).

## Background

### 문제

5 file 의 VT executor 또는 Kafka consumer thread 에서 CPU 작업 inline 실행. carrier thread pinning → IO/CPU 분리 깨짐.

### 결정 (Best practice)

- Issue body 가 `runBlocking(Dispatchers.Default) { }` 명시. 그대로 적용.
- ADR-723 §23.3 multi-threaded consumer + runBlocking = VT carrier block risk. 4 file (`EquipmentDocumentPreparer`, `ResultFileReader`, `BasicChunkFileReader`, `EquipmentRankingRedisWriter`) 은 multi-threaded executor caller (ChunkPipelineOrchestrator 또는 Redis writer caller) 로 risk 있으나, issue body 의 권장 안 그대로 따름.
- ADR-723 §23.3 PGMQ/Kafka single-threaded batch → `runBlocking` safe. `OcidLookupRunConsumer` (Kafka consumer) 는 single-threaded batch 라 safe.

## Architecture

### 5 file Wrap Pattern (모두 runBlocking)

| File | Caller type | Pattern |
|---|---|---|
| `EquipmentDocumentPreparer.kt` | ChunkPipelineOrchestrator (multi-threaded) | `runBlocking(Dispatchers.Default) { cpuWork() }` |
| `ResultFileReader.kt` | ChunkPipelineOrchestrator (multi-threaded) | 동일 |
| `BasicChunkFileReader.kt` | ChunkPipelineOrchestrator (multi-threaded) | 동일. DB write는 VT 유지 |
| `EquipmentRankingRedisWriter.kt` | Redis caller (multi-threaded) | 동일. Redis pipelined ZADD는 VT 유지 |
| `OcidLookupRunConsumer.kt` | Kafka single-threaded batch | 동일. executor dispatch 추가 |

### Pattern A: `runBlocking(Dispatchers.Default) { cpuWork() }` (4 file)

```kotlin
// EquipmentDocumentPreparer.prepare()
override fun prepare(...) {
    val prepared = runBlocking(Dispatchers.Default) {
        documents.map { doc -> prepareOne(doc) }
    }
    // ... 후속 IO
}

private fun prepareOne(doc: Document): PreparedDocument = runBlocking(Dispatchers.Default) {
    val body = objectMapper.writeValueAsBytes(doc)
    val compressed = GzipUtils.compress(body)
    val hash = sha256Hex(compressed)
    PreparedDocument(body, compressed, hash)
}
```

### Pattern B: OcidLookupRunConsumer executor dispatch 추가

```kotlin
@Component
class OcidLookupRunConsumer(
    // ... existing deps
    @Qualifier("ocidLookupRunExecutor") private val executor: ExecutorService,  // NEW
) {
    @KafkaListener(...)
    fun consume(message: String, acknowledgment: Acknowledgment) {
        executor.submit {  // NEW: dispatch
            runCatching {
                val cpuResult = runBlocking(Dispatchers.Default) {  // CPU offload
                    objectMapper.readTree(message)  // parse
                }
                // ... 후속 IO
            }.whenComplete { _, ex ->
                runCatching { acknowledgment.acknowledge() }
            }
        }
    }
}
```

## 산출 파일 (5 file modify)

| File | 작업 | 핵심 |
|---|---|---|
| `EquipmentDocumentPreparer.kt` | Modify | `prepareOne()` per-document CPU → `runBlocking(Dispatchers.Default)` |
| `ResultFileReader.kt` | Modify | `readAndGroupByCompositeKey()` per-line parse + map grouping → `runBlocking(Dispatchers.Default)` |
| `BasicChunkFileReader.kt` | Modify | `readInBatches()` per-record parse + compress + hash → `runBlocking(Dispatchers.Default)`. DB write는 VT 유지 |
| `EquipmentRankingRedisWriter.kt` | Modify | `filter` + `groupBy` collection ops → `runBlocking(Dispatchers.Default)`. Redis ZADD는 VT 유지 |
| `OcidLookupRunConsumer.kt` | Modify | `@Qualifier("ocidLookupRunExecutor") ExecutorService` 주입. `fun consume` body를 `executor.submit { ... }` 로 wrap. CPU 구간은 `runBlocking(Dispatchers.Default) { parse }` |

## Acceptance Criteria 매핑

| #1129 AC | 충족 |
|---|---|
| EquipmentDocumentPreparer의 serialize+GZIP+SHA-256이 Dispatchers.Default에서 실행 | Pattern A 적용 |
| ResultFileReader의 JSON parse+grouping이 Dispatchers.Default에서 실행 | Pattern A 적용 |
| BasicChunkFileReader의 parse+compress+hash가 Dispatchers.Default에서 실행 | Pattern A 적용 |
| OcidLookupRunConsumer에 executor dispatch 추가됨 | Pattern B 적용 |
| DB write, Redis write, file read는 기존 VT executor 유지 | 변경 없음 |
| ./gradlew :module-synchronizer:test 통과 | refactor only, default 동작 변경 없음 |

## Testing / Verification

### Unit test

기존 test 영향 없음. default dispatcher 변경 안 됨.

### 검증 절차

```bash
# 1. compile
./gradlew :module-synchronizer:compileKotlin --continue
# Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만)

# 2. test
./gradlew :module-synchronizer:test
# Expected: 기존 test 모두 통과

# 3. grep 검증
grep -rn "runBlocking(Dispatchers\.Default)" --include='*.kt' module-synchronizer 2>/dev/null
# Expected: 5+ hits (4 file + OcidLookupRunConsumer)

grep -rn "@Qualifier(\"ocidLookupRunExecutor\")" --include='*.kt' module-synchronizer 2>/dev/null
# Expected: 1 hit (OcidLookupRunConsumer)
```

## Migration / Rollout

- 단일 PR. Risk 낮음 (refactor only, default dispatcher 변경 없음).
- Rollback: `git revert` 1 commit.
- Hot-deploy: Spring bean property 변경 없음.

## 영향 범위 (Out of Scope)

- ❌ RunBlocking 의 multi-threaded consumer risk — issue body 의 권장 안 그대로 따름. follow-up 으로 Q3 A (suspend fun refactor) 검토 가능.
- ❌ Kafka consumer 의 executor 명세 (`ocidLookupRunExecutor`) — 기존 executor 빈 정의 미확인. 별도 issue.
- ❌ 다른 module 동일 pattern (#1130, #1131) — 별도 brainstorming.
- ❌ 새 ADR — ADR-723 §23.3 pattern 적용만.
- ❌ Runtime 부하테스트 — 후속 #1198 saturation metric 와 동시.

## Follow-up Issues (PR verification 단계에서 자동 생성)

1. **#TBD: 4 file 의 multi-threaded runBlocking risk 완화** — OcidLookupRunConsumer (Kafka single-threaded) 외 4 file 의 caller 가 multi-threaded. ADR-723 §23.3 위반 가능. 추후 suspend fun refactor 또는 supplyAsync 로 전환.
2. **#TBD: ocidLookupRunExecutor 빈 명세 검증** — VT vs platform, pool size 등.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| runBlocking in multi-threaded consumer (4 file) blocks VT carrier | Medium | Low | 짧은 CPU. multi-threaded VT 라서 다른 submit 영향 없음. ADR-723 §23.3 위반 (principle only). |
| OcidLookupRunConsumer 의 executor submit + runBlocking pattern | Low | Low | Kafka single-threaded batch → runBlocking safe. |
| Issue body 의 line 번호 outdated | High | Low | plan 의 implementation 시 current line number 로 verify. |
| OcidLookupRunExecutor 빈 정의 부재 | Medium | High | application.yml 에 정의 추가 또는 @Primary bean 으로 wiring. |

## Self-Review Check (spec 작성 후)

- [x] Placeholder: 없음 (TBD 0건, "#TBD" 는 PR 시 자동 할당)
- [x] Internal consistency: 5 file 의 pattern 정합 (모두 runBlocking)
- [x] Scope: 단일 PR, bounded
- [x] Ambiguity: OcidLookupRunConsumer 의 executor type 명시 (단, pool size 등은 follow-up)
- [x] AC coverage: 6 AC 모두 file/pattern 매핑

## Related

- Spec: 이 파일
- ADR-723: docs/01_ADR/ADR-723_io-cpu-split-pattern.md
- Plan (후속): docs/superpowers/plans/2026-06-08-1129-synchronizer-cpu-offload.md
- Issue #1129: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1129
- Sibling: #1127 (PR #1203), #1128 (PR #1207)
