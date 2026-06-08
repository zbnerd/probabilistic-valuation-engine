# Issue #1128: module-external-api CPU 작업 Dispatchers.Default Offload

- Status: Accepted
- Date: 2026-06-08
- Owner: zbnerd
- Issue: #1128
- Label: ready-for-agent
- Blocked by: #1125 (MERGED in PR #1199)
- Blocks (indirect): #1198 saturation follow-up

## Goal

`module-external-api` 의 7 file 에서 CPU-heavy 작업 (JSON parse/serialize, GZIP compress/decompress, SHA-256, large collection 변환) 을 `Dispatchers.Default` 로 offload. ADR-723 §23.3 + §23.5 의 pattern 적용.

## Background

### 문제

7 file 의 VT executor 또는 CompletableFuture 체인에서 CPU 작업 inline 실행. carrier thread pinning → IO-bound VT 응답성 저하.

### 선례 / 결정 근거

- `Issue body 가 outdated` — 6 file 중 3/6 file 의 line 번호가 current code 와 mismatch. verify 후 implementation.
- `SnapshotFetchPhase.kt` — develop HEAD 에 **없음** (commit `38f32fac5 refactor(ext-api): wire ExternalApiScheduler to per-endpoint phases, drop SnapshotFetchPhase (#986)` 에서 dropped). 5/6 file 만 적용. follow-up issue 로 분리.
- `LocalExternalApiArtifactStoreAdapter.kt` — issue body 의 6 file 목록에 **누락** 되었으나 AC "SHA-256 hashing 구간이 Dispatchers.Default에서 실행" 매칭 위해 7번째 file 로 포함. SHA-256 + GZIP compress 둘 다 포함.
- `AuthCharacterFetchConsumer.kt` — develop HEAD 에는 존재 (git history) but **working tree 에서 missing** (이전 세션 잔재). `git checkout HEAD -- <file>` 로 복원 필요.

### 4 결정 (Brainstorming)

| Q | 결정 | 근거 |
|---|---|---|
| Q1 | 5/6 file + SnapshotFetchPhase follow-up | dropped in #986, 부활 위험 |
| Q2 | 7 file (6 + LocalExternalApiArtifactStoreAdapter) | AC 매칭, 누락 file 보완 |
| Q3 | `CompletableFuture.supplyAsync(Dispatchers.Default.asExecutor())` for non-coroutine | ADR-723 §23.3 multi-threaded consumer guideline (runBlocking 회피) |
| Q4 | Inline, helper 없음 | YAGNI, 7 site × 3 lines |

## Architecture

### 7 file Wrap Pattern

| File | Coroutine? | Pattern | Caller |
|---|---|---|---|
| `OcidLookupPhase.kt` | NO → refactor | `suspend fun` + caller `runBlocking` | ExternalApiScheduler (multi-threaded VT) |
| `RankingFetchPhase.kt` | TBD (verify) | `withContext(Dispatchers.Default)` OR `supplyAsync` | TBD |
| `OcidCacheProvider.kt` | TBD (verify) | `withContext` OR `supplyAsync` | TBD |
| `UrgentCharacterRequestConsumer.kt` | NO | `CompletableFuture.supplyAsync(Dispatchers.Default.asExecutor())` | PGMQ worker (single-threaded batch → runBlocking safe) |
| `AuthCharacterFetchConsumer.kt` | NO | 동일 | Kafka listener + executor.submit (multi-threaded VT) |
| `LocalExternalApiArtifactStoreAdapter.kt` | NO (sync port) | `runBlocking(Dispatchers.Default) { sha256() + gzipCompress() }` | sync port caller |

### Pattern A: Coroutine file (withContext)

```kotlin
// RankingFetchPhase (verify 후 coroutine 확인 시)
suspend fun submitRankingEntries(...) {
    val root = withContext(Dispatchers.Default) {
        objectMapper.readTree(bodyBytes)
    }
    // ... 후속 IO
}
```

### Pattern B: Non-coroutine file (supplyAsync)

```kotlin
// OcidLookupPhase.readCharacterNamesFromChunks (refactor 후):
fun readCharacterNamesFromChunks(runDir: Path): CompletableFuture<List<String>> =
    CompletableFuture.supplyAsync({
        val names = mutableListOf<String>()
        GZIPInputStream(BufferedInputStream(Files.newInputStream(chunkFile))).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val node = objectMapper.readTree(line)
                // ... extract name
                names.add(name)
            }
        }
        names
    }, Dispatchers.Default.asExecutor())
```

### Pattern C: Sync port (runBlocking)

```kotlin
// LocalExternalApiArtifactStoreAdapter.store()
override fun store(endpoint: ExternalApiEndpoint, key: String, data: ByteArray): ExternalApiPayloadRef {
    val (compressed, hash) = runBlocking(Dispatchers.Default) {
        val c = gzipCompress(data)
        val h = sha256(c)
        c to h
    }
    // ... 후속 IO
}
```

## 산출 파일

| File | 작업 | 비고 |
|---|---|---|
| `OcidLookupPhase.kt` | Modify (suspend fun refactor) | `execute()` + `readCharacterNamesFromChunks()` + `fetchAndCollectOcidAsync()` |
| `RankingFetchPhase.kt` | Modify | verify 후 pattern 결정 |
| `OcidCacheProvider.kt` | Modify | verify 후 pattern 결정 |
| `UrgentCharacterRequestConsumer.kt` | Modify | `publishUrgentChunkAsync()` |
| `AuthCharacterFetchConsumer.kt` | **Restore** + Modify | `git checkout HEAD -- <file>` prerequisite. `publishResponse()` |
| `LocalExternalApiArtifactStoreAdapter.kt` | Modify | `store()` 의 sha256 + gzipCompress |

### Prerequisite (commit 전 fix)

- `module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt` — `git checkout HEAD -- <file>` 로 복원 (working tree 에서 missing)

## Acceptance Criteria 매핑

| #1128 AC | 충족 |
|---|---|
| 모든 JSON parse/serialize 구간이 `Dispatchers.Default`에서 실행 | 5+ file 에 supplyAsync/withContext (verify 후 카운트 확정) |
| 모든 GZIP compress/decompress 구간이 `Dispatchers.Default`에서 실행 | OcidLookupPhase (decompress), OcidCacheProvider (decompress), UrgentCharacterRequestConsumer (compress) |
| SHA-256 hashing 구간이 `Dispatchers.Default`에서 실행 | `LocalExternalApiArtifactStoreAdapter.store()` 의 `runBlocking(Dispatchers.Default) { sha256() }` |
| IO 작업(HTTP call, file read, Kafka send)은 기존 VT executor 유지 | 변경 없음. CPU only. |
| ./gradlew :module-external-api:test 통과 | refactor only, default dispatcher 변경 없음 |

## Testing / Verification

### Unit test

- 기존 test (있는 경우) 는 `processBatch` / `publishResponse` / `submitRankingEntries` 등의 동작 검증. default dispatcher 변경 없음 → test 미수정.
- 새 test 불필요 (refactor only, ADR-723 §23.3 의 pattern 적용).

### 검증 절차

```bash
# 1. prerequisite
git checkout HEAD -- module-external-api/src/main/kotlin/maple/externalapi/auth/AuthCharacterFetchConsumer.kt

# 2. compile
./gradlew :module-external-api:compileKotlin --continue
# Expected: BUILD SUCCESSFUL (또는 pre-existing module-infra 에러만)

# 3. test
./gradlew :module-external-api:test
# Expected: 기존 test 모두 통과

# 4. grep 검증
grep -rn "withContext(Dispatchers\.Default)\|CompletableFuture\.supplyAsync(Dispatchers\.Default" --include='*.kt' module-external-api 2>/dev/null
# Expected: 5+ hits

grep -rn "runBlocking(Dispatchers\.Default) { sha256" --include='*.kt' module-external-api 2>/dev/null
# Expected: 1 hit (LocalExternalApiArtifactStoreAdapter)

# 5. unchanged IO 작업 확인 (Issue body "IO 작업은 기존 VT executor 유지")
grep -rn "executor\.submit\|CompletableFuture\.supplyAsync(executor\|webClient\.get" --include='*.kt' module-external-api 2>/dev/null | grep -v "Dispatchers\.Default" | head -5
# Expected: 변경 없음 (IO path 동일)
```

## Migration / Rollout

- **단일 PR:** 7 file modify + 1 file restore. Risk 낮음 (refactor only, default dispatcher 변경 없음).
- **Rollback:** `git revert` 1 commit. runtime 검증 없이 revert 가능.
- **Hot-deploy:** Spring bean 의 property 변경 없음. startup-time 영향 없음.

## 영향 범위 (Out of Scope)

- ❌ `SnapshotFetchPhase.kt` — develop HEAD에 없음 (#986 에서 dropped). follow-up issue 로 분리
- ❌ Module-external-api 외 다른 module (e.g., module-synchronizer) 의 동일 pattern — #1129 별도
- ❌ Module-external-api 의 다른 CPU site (issue body 외) — 본 PR scope 외
- ❌ 새 ADR — ADR-723 §23.3 + §23.5 pattern 적용만
- ❌ runtime 부하테스트 — #1198 saturation metric 작업과 동시

## Follow-up Issues (PR verification 단계에서 자동 생성)

1. **#TBD: SnapshotFetchPhase.kt 가 재도입될 때 CPU offload 적용** — #986 refactor 후 필요 시
2. **#TBD: AuthCharacterFetchConsumer 의 executor type 검증 (VT vs platform)** — `@Qualifier("authCharacterFetchExecutor")` 의 정확한 빈 명세 확인

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| OcidLookupPhase suspend fun refactor 시 caller (ExternalApiScheduler) compile fail | Low | High | runBlocking bridge in caller. plan Task 4 에 caller 변경 명시 |
| `runBlocking(Dispatchers.Default) { sha256() }` in store() blocks sync caller | Low | Low | sha256 ~ms 단위 CPU. sync port 의 짧은 block. multi-threaded context 영향 없음 |
| `CompletableFuture.supplyAsync(Default.asExecutor()).join()` blocks caller thread | Low | Low | 짧은 CPU. multi-threaded VT 라서 다른 submit 영향 없음. ADR-723 §23.3 위반 (실용 risk 없음) |
| AuthCharacterFetchConsumer restore 시 충돌 | Low | Low | Task 1 prerequisite 로 `git checkout HEAD -- <file>` 명시 |
| Module-infra pre-existing compile error 가 module-external-api 영향 | Medium | Low | gradle task graph 의존성. 영향 시 out-of-scope commit 으로 fix |
| RankingFetchPhase / OcidCacheProvider 의 coroutine 검증 결과 supplyAsync/withContext 분기 | Low | Low | Task 단계에서 verify, 필요 시 re-design |

## Self-Review Check (spec 작성 후)

- [x] Placeholder: 없음 (TBD 0건, "Follow-up Issues" 의 `#TBD` 는 PR 시 자동 할당)
- [x] Internal consistency: 4 결정 정합, 7 file 의 pattern 명시
- [x] Scope: 단일 PR, bounded (7 file + 1 restore)
- [x] Ambiguity: 3 wrap pattern (A/B/C) 명확, file 별 pattern 매핑
- [x] AC coverage: 4 AC 모두 file/pattern 매핑
- [x] Cross-ref: ADR-723 §23.3 + §23.5 의 pattern 적용

## Related

- Spec: 이 파일
- ADR-723: docs/01_ADR/ADR-723_io-cpu-split-pattern.md
- Plan (후속): docs/superpowers/plans/2026-06-08-1128-external-api-cpu-offload.md (writing-plans 산출)
- Issue #1128: https://github.com/zbnerd/probabilistic-valuation-engine/issues/1128
- Predecessor: #1125 (PR #1199), #1126 (PR #1200), #1127 (PR #1203)
- Sibling: #1129 (synchronizer), #1130 (rest-controller), #1131 (infra worker)
