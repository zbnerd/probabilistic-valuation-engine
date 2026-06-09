# ADR-725: Object Storage Abstraction + MinIO Readiness (VS1+VS2 Summary)

- Status: Accepted
- Date: 2026-06-09
- Owner: zbnerd
- Supersedes: (none)
- Related: ADR-022 (Redis dependency — now Deprecated), Issue #1216 (VS1), Issue #1217 (VS2)

---

## 1. Background / Problem

### Background

- VS1 (PR #1222) shipped the unified `ObjectStorage` interface with two adapters: `LocalFsObjectStorage` and `MinioObjectStorage` (S3 SDK v2, path-style). No application call sites were migrated.
- VS2 (this PR) migrated `module-calculator`, `module-external-api`, `module-synchronizer`, `module-infra` to the unified `ObjectStorage`. `SnapshotObjectStore` kept its port signature via a thin `SnapshotObjectStoreAdapter` wrapper. `ChunkFileReaderPort` consolidates 3 separate readers into 1 port with IO/CPU 분리. Two legacy ports (`ExternalApiArtifactStorePort`, calculator's local `ObjectStorage`) marked `@Deprecated` for removal in #1221.
- Default backend = `local` (no production cutover). Production atomic cutover to `storage.backend=minio` happens in VS3+VS4.

### Problem

Application code had 3 legacy port interfaces (`SnapshotObjectStore`, `ExternalApiArtifactStorePort`, calculator's local `ObjectStorage`) plus direct `Paths.get()` access in synchronizer readers and scheduler phases. The fragmented storage API blocked a future MinIO cutover and made it impossible to add storage backend metrics, retries, or circuit-breakers in one place.

### Goal

- All application call sites in the 4 modules flow through one `ObjectStorage` interface.
- MinIO is a one-line config flip (`storage.backend=minio`) once VS3+VS4 cutover is done.
- `DefaultChunkFileReader` separates IO (`Dispatchers.IO`) from CPU (`Dispatchers.Default`) — fixes the `runBlocking(Dispatchers.Default)` issue where CPU pool was blocked on 50-200ms S3 network calls.

---

## 2. Decision

> We adopt a single `ObjectStorage` interface in `module-common` with `LocalFsObjectStorage` and `MinioObjectStorage` adapters. Application code is migrated in VS2 with default backend = `local`. Production cutover is deferred to VS3+VS4.

```text
┌─────────────────────────────────────────────────────────────┐
│  Application (4 modules)                                     │
│  - module-calculator                                         │
│  - module-external-api                                       │
│  - module-synchronizer                                       │
│  - module-infra (SnapshotObjectStoreAdapter)                 │
└────────────────────────────────┬────────────────────────────┘
                                 │ inject
                                 ▼
                ┌────────────────────────────────────┐
                │  ObjectStorage (port, module-common)│
                └────────────────┬───────────────────┘
                                 │ select by storage.backend
                ┌────────────────┴───────────────────┐
                │                                    │
        LocalFsObjectStorage            MinioObjectStorage
        (default for VS2)               (ready, not yet cutover)
```

`SnapshotObjectStore` port preserved unchanged — `SnapshotObjectStoreAdapter` is the sole implementation. `ChunkFileReaderPort` replaces 3 separate reader classes.

---

## 3. Trade-offs

### Sensitivity

- **Chunk size** (1–8 MB gzipped): MinIO `objectStorage.get` is 50–200 ms. IO/CPU 분리 in `DefaultChunkFileReader` prevents CPU pool (size = `availableProcessors`) from being blocked on network calls. Local FS sub-ms, both dispatchers equivalent.
- **Reader concurrency**: 4 concurrent synchronizer consumers × 1 in-flight get each. IO pool (default 8 VT threads) handles without starvation.
- **Backend hot-swap**: changing `storage.backend` at runtime is NOT supported. Config flip requires restart.

### Trade-off

| Choice | Gain | Cost |
|--------|------|------|
| Single `ObjectStorage` interface | One config flip for MinIO, one metrics surface, one retry policy | 1 indirection per call site (~5 ns JVM overhead) |
| `SnapshotObjectStore` port preserved | 0 caller diff for 3 callers (`ExternalApiWorker`, `NexonApiWorker`, `SnapshotCleanupWorker`) | Adapter layer adds 1 hop |
| `ChunkFileReaderPort` (1 class, 3 methods) | Simple DI, single mock in tests | One class 3 responsibilities (parse is private; 3 method entry points are 1-line) |
| `storageType` backend-specific (`"S3"` / `"LOCAL"`) | Active backend identifiable in `CalculationSnapshot.storageType` | Historic data backend-tied (acceptable; same data, different hash) |
| Default backend = local for VS2 | No production risk during migration | MinIO cutover needs separate VS3+VS4 PRs |
| Pre-existing compile fixes in VS2 PR | Unblocks `./gradlew test` verification | VS2 PR size grew (~13 files pre-existing fix) |

### Risk

- **VS3+VS4 cutover regressions**: when `storage.backend=minio` is set, the path-style endpoint + bucket policy + lifecycle rules must be validated in prod-like env first. Mitigation: VS3 = dry-run (write to MinIO but read from local), VS4 = atomic flip.
- **`storageType` field historical**: switching backends changes the field in stored snapshots. Acceptable — `storageType` is informational, not a key.
- **Pre-existing fix scope creep**: 4 pre-existing file fixes included in VS2 PR. If more pre-existing errors surface later, they go to separate issue.
- **Domain type move to `module-core`**: `BasicRecord`, `GroupedEquipmentResult`, `OcidMapping` moved from `module-synchronizer/storage` to `module-core/.../core/model/chunk`. Typealiases preserve synchronizer's old import paths. Risk: missed typealias site.

### Non-Risk

- **`SnapshotObjectStore` port signature preserved** — 3 callers unchanged.
- **`LocalFsObjectStorage` reuses VS1 wiring** — calculator, external-api, synchronizer all already on local backend.
- **Hot path throughput** — IO/CPU 분리 preserves the VS1 throughput. No regression measured in module-calculator or module-synchronizer unit tests.
- **Production cutover** — VS2 ships default=local. VS3+VS4 own the cutover risk.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| Files migrated | 4 modules, ~15 files | calculator (4 mod + 2 del), external-api (5 mod + 2 deprecate), synchronizer (5 mod + 3 del), infra (1 new + 1 del) |
| New types | 4 in `module-core` | `BasicRecord`, `GroupedEquipmentResult`, `OcidMapping` (moved), `ChunkFileReaderPort` (new) |
| New adapter | 1 | `SnapshotObjectStoreAdapter` (preserves port API) |
| Deprecated ports | 2 | `ExternalApiArtifactStorePort`, calculator's local `ObjectStorage` (removal in #1221) |
| Tests passing | All | `./gradlew test` BUILD SUCCESSFUL across all modules |
| Lines net change | +~500 / -~1500 | 3 legacy readers deleted; replaced by 1 new reader + 1 new port |

### Observed Result

- `./gradlew test` green on all 8 modules (after pre-existing fix commit for `module-rest-controller` + `module-external-api` test breakage).
- Synchronizer `DefaultChunkFileReaderTest` covers parse correctness + IO/CPU dispatcher instrumentation via `kotlinx-coroutines-test`.
- `SnapshotObjectStoreAdapter` preserves the 3 caller contracts (`ExternalApiWorker`, `NexonApiWorker`, `SnapshotCleanupWorker`) — no source change in those files.

---

## 5. Summary

> Single `ObjectStorage` port + 2 adapters; application migrated; legacy readers deleted; default local; MinIO cutover deferred to VS3+VS4.
