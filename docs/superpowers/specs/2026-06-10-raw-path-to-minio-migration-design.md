# Raw Path → MinIO Migration (ext-api, cleanup)

- **Status**: Draft (brainstorming complete, awaiting user review)
- **Date**: 2026-06-10
- **Owner**: ext-api squad (calculator + synchronizer owners consulted for objectKey compat)

---

## 1. Background / Problem

### Background

VS2 ObjectStorage migration (commits `3d17a68a0`..`17abd7d8d`) consolidated artifact storage to a single `ObjectStorage` interface with two impls: `LocalFsObjectStorage` (local FS) and `MinioObjectStorage` (S3). Calculator + Synchronizer were fully migrated.

ext-api and module-cleanup were NOT fully migrated. They still use raw `java.nio.file` paths in writers and readers. Result: when `STORAGE_BACKEND=minio`, ext-api writes to local FS but publishes events with `objectKey` pointing at MinIO prefixes. Calculator/sync then fail to read the chunks they expected.

### Problem

Pipeline test (STORAGE_BACKEND=minio) cannot run end-to-end:
- MinIO bucket stays empty
- Calculator Kafka listener fails when chunks arrive (after the 9fbea109f TODO is unblocked)
- `ext-api/actuator/health/minioHealthIndicator` is UP but the storage write path is local

### Goal

ext-api and module-cleanup use `ObjectStorage` exclusively. MinIO becomes the canonical production storage. Local mode remains functional (regression-free) via `LocalFsObjectStorage`.

---

## 2. Decision

> Migrate all raw `java.nio.file` writers/readers in `ext-api` (8 files) and `cleanup` (2 files) to use the `ObjectStorage` interface. Inter-phase API changes from `Path` to `String` (object key). The deprecated `LocalExternalApiArtifactStoreAdapter` is removed.

```text
ext-api writers/readers (8 files)
  └─ inject ObjectStorage, replace Files.* with objectStorage.put/get/listByPrefix
  └─ inter-phase: runDir:Path → runKey:String
  └─ delete LocalExternalApiArtifactStoreAdapter (0 callers)

cleanup (2 files)
  └─ inject ObjectStorage, replace Paths.get + Files.walk + Files.delete

objectStorage impl (module-infra, unchanged)
  ├─ LocalFsObjectStorage (local mode, ../data base-path)
  └─ MinioObjectStorage (MinIO mode, S3 client)
```

---

## 3. Trade-offs

### Sensitivity

* **Object key format compatibility** — Calculator + Synchronizer read via `DefaultChunkFileReader` using the event's `objectKey`. Format must stay `runs/$runId/$endpoint/...` to keep the consumer path working.
* **Inter-phase coupling** — RankingFetch produces chunks that OcidLookupPhase reads. Both must agree on the key prefix.
* **OCID mapping location** — Lives under `ocid-mapping/` (not under `runs/`), so cleanup retention policy treats it differently.
* **_RUNNING marker semantics** — Used as "run in progress" indicator. Local: empty file. MinIO: 0-byte object. `exists()` check is identical.
* **Failed records append semantics** — Local: append to file. S3: no native append. Read-modify-write required (acceptable for low volume).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| **B/B1 (class 내부 impl 교체, 시그니처 유지)** | Minimal caller changes (phases keep method calls). Same test surface. Class 책임 경계 보존. | 내부 impl 변경이라 테스트가 직접 확인 가능. `ChunkFileManager` 같은 wrapper class가 thin해질 수 있음. |
| (vs A) 직접 ObjectStorage 주입 | 같은 변경량. 노출 면 동일. | 동일 — A와 B/B1의 실질 차이는 미미. |
| (vs C) 새 `SnapshotStorage` port | 도메인 의미 명확. 테스트 mock surface 좁음. | 새 추상화 layer. YAGNI (3-4 phase만 사용). 프로젝트 "Port → Adapter" 패턴과 정합성 가능하나 over-abstraction 위험. |

### Risk

* **Object key collision** — Multiple phases writing to overlapping keys under `runs/$runId/`. Mitigated by `$endpoint/` sub-namespace + atomic put.
* **Read-modify-write race on failed.jsonl** — Two writers could race. Mitigated by serialized scheduler (one run at a time per phase).
* **OCID mapping temp file removal** — Currently `deleteOnExit`. After migration, no temp file (in-memory `ByteArrayOutputStream`). Eliminates JVM-temp leak risk.

### Non-Risk

* **Calculator / Synchronizer** — Already use `ObjectStorage`. No changes.
* **`ObjectStorage` interface** — `put/putStream/get/getStream/delete/exists/listByPrefix/deleteByPrefix/calculatePrefixSize/getLastModified` covers all needed operations.
* **Local mode** — `LocalFsObjectStorage` impl handles all 9 operations on local FS. No regression.

---

## 4. Result / Evidence

### Architecture (after migration)

```
ext-api (changed)
  ├─ writers/readers (8 files) — internal impl swapped to ObjectStorage
  │    ├─ GzipJsonlChunkWriter:   FileOutputStream → objectStorage.put
  │    ├─ ChunkFileManager:        Files.writeString → objectStorage.put
  │    ├─ SnapshotChunkManifest*:  Files.write → objectStorage.put
  │    ├─ RunMarkerWriter:         Files.writeString → objectStorage.put
  │    ├─ SnapshotFailedRecordWr*: Files.append → objectStorage.putStream (read-modify-write)
  │    ├─ RankingFetchPhase:       runDir:Path → runKey:String
  │    ├─ CharacterBasicFetchPhase: same
  │    ├─ ItemEquipmentFetchPhase:  same
  │    ├─ OcidLookupPhase:          runDir:Path → runKey, tempFile → ByteArrayOutputStream
  │    └─ OcidCacheProvider:        Files.list(dir) → objectStorage.listByPrefix
  └─ (deprecated) LocalExternalApiArtifactStoreAdapter: **삭제**

cleanup (changed)
  ├─ RunCleanupService:     Paths.get + Files.walk → objectStorage.listByPrefix + deleteByPrefix
  └─ CleanupController:     deleteFile(objectKey): Path → objectStorage.delete

module-common
  └─ ObjectStorage: unchanged
```

### Object keys (format preserved)

| Artifact | Key |
| -- | -- |
| chunk | `runs/$runId/$endpoint/part-XXXXXX.jsonl.gz` |
| manifest | `runs/$runId/$endpoint/manifest.json` |
| _RUNNING marker | `runs/$runId/_RUNNING` |
| OCID mapping | `ocid-mapping/ocid-mapping-$runId.jsonl.gz` |
| failed records | `runs/$runId/$endpoint/failed.jsonl` |

### Metrics

| Metric | Before | After (target) |
| ------ | ----: | -----: |
| Raw path usage in ext-api writers | 8 files | 0 |
| Raw path usage in cleanup | 2 files | 0 |
| `LocalExternalApiArtifactStoreAdapter` callers | 0 (deprecated) | deleted |
| `STORAGE_BACKEND=minio` end-to-end | broken | works |
| `STORAGE_BACKEND=local` end-to-end | works | works (regression check) |

### Observed Result

Three-layer verification (smoke is layer 1; dataflow test adds layers 2-3).

#### Layer 1: smoke E2E (pipeline-test skill)

```bash
# MinIO mode
for prefix in runs ocid-mapping calculator/runs; do
  count=$(mc ls --recursive "local/${MINIO_BUCKET}/${prefix}/" | wc -l)
  [ "${count}" -gt 0 ] || fail
done

# Local mode (regression)
for path in ../data/runs ../data/ocid-mapping; do
  count=$(find "${path}" -name '*.jsonl.gz' | wc -l)
  [ "${count}" -gt 0 ] || fail
done

# Unit tests
./gradlew :module-{external-api,calculator,synchronizer,cleanup}:test
# → 0 fail
```

#### Layer 2: fixture-based dataflow test

**Goal**: verify that data written by ext-api is *exactly* the data calculator/sync consume — content round-trip, not just object presence.

```bash
# Fixture: 5 known IGNs (deterministic, replayable)
FIXTURE_IGNS=("테스트캐릭터1" "테스트캐릭터2" "테스트캐릭터3" "테스트캐릭터4" "테스트캐릭터5")

# 1) ext-api writes chunks to MinIO
# 2) Calculator reads from MinIO, processes, writes results
# 3) Synchronizer upserts read model

# Verify content round-trip:
for ign in "${FIXTURE_IGNS[@]}"; do
  # ext-api wrote: runs/$runId/ranking-overall/part-*.jsonl.gz (contains $ign)
  result=$(zcat $(mc ls --recursive "local/${MINIO_BUCKET}/runs/$runId/ranking-overall/" | grep part- | head -1) | grep "$ign")
  [ -n "$result" ] || fail "ext-api did not write $ign to MinIO"

  # calculator read from MinIO + wrote result
  calc_result=$(mc cat "local/${MINIO_BUCKET}/calculator/runs/$runId/result-${ign}.json")
  echo "$calc_result" | jq -e ".userIgn == \"$ign\"" > /dev/null || fail "calculator result for $ign missing or malformed"

  # synchronizer upserted to read model
  psql -t -A -c "SELECT count(*) FROM character_valuation_views WHERE user_ign = '$ign'" | grep -q "^[1-9]" || fail "sync did not upsert $ign"
done
```

Layer 2 runs **after** Layer 1 passes. Reuses the pipeline-test's run (same runId), so no second pipeline boot.

#### Layer 3: schema validation at each data boundary

Per boundary (chunk, manifest, OCID mapping, calculator result), assert schema invariants:

| Boundary | Validation |
| -------- | ---------- |
| chunk bytes (MinIO) | gzip decode → line count > 0 → each line is valid JSON → required fields (`character_name` for ranking, `ocid` for character-basic) |
| manifest (MinIO) | JSON parse → required fields (`runId`, `totalRecords`, `chunks[]`, `startedAt`, `finishedAt`) → `chunks[].path` is reachable (put-then-get) |
| OCID mapping (MinIO) | gzip decode → JSON Lines → each line has `userIgn` + `ocid` → ocid format matches `^[a-f0-9]{40}$` |
| calculator result (MinIO) | JSON parse → required fields (`userIgn`, `ocid`, `presetNo`, `cubes[]`, `result`) → `cubes[].score` is numeric |
| synchronizer read model (PostgreSQL) | `character_valuation_views.user_ign` indexed lookup → row exists for each fixture IGN → non-null `expected_value` |

Implementation:
- `module-calculator` + `module-synchronizer` already have unit tests for schema parsing (keep).
- New: **`DataflowContractTest` in `module-external-api` test sources** (since ext-api is the writer). Loads fixture IGNs, runs `RankingFetchPhase` + `OcidLookupPhase` against a mock `NexonApiClient` that returns canned responses, then asserts every byte put to `ObjectStorage` round-trips through `get()` and parses to the expected schema.
- `DataflowContractTest` runs in unit test suite (`./gradlew :module-external-api:test`).
- Pipeline test (`STORAGE_BACKEND=minio`) runs Layer 2 against the live MinIO + local PG — confirms real-world schema conformance.

#### Why not pure integration tests

Project rule: "통합테스트 금지 (Testcontainers 포함) - Issue #207". Layer 2 is end-to-end but uses local infra (MinIO + local PG already running), not Testcontainers. Layer 3 is unit + live-MinIO combined — same exclusion.

---

## 5. Summary

> ext-api (8 files) + cleanup (2 files) use `ObjectStorage` exclusively. Inter-phase API is `runKey: String` (not `Path`). Deprecated adapter removed. MinIO is the production default; local mode stays regression-free. Verification: unit + smoke + fixture-based dataflow + per-boundary schema — all atomic single PR.
