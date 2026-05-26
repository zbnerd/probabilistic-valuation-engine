# ADR-719: Object Storage Abstraction — 통합 인터페이스 선행, MinIO 전환은 scale-out 시

- Status: Accepted
- Date: 2026-05-22
- Owner: zbnerd

---

## 1. Background / Problem

### Background

현재 파이프라인은 Claim Check Pattern 기반. Kafka에 metadata만 흐르고, payload는 local disk에 JSONL.gz artifact로 저장.

스토리지 추상화가 3개로 분리됨:
- `ObjectStorage` (calculator 전용, `LocalObjectStorageAdapter`)
- `ExternalApiArtifactStorePort` (external-api 전용, `LocalExternalApiArtifactStoreAdapter`)
- `SnapshotObjectStore` (legacy, `LocalSnapshotObjectStore`)

Synchronizer는 인터페이스 우회: `BasicChunkFileReader`, `ResultFileReader`, `OcidMappingFileReader`가 `Paths.get(basePath, objectKey)` 직접 사용. `ConsumedChunkCleanupScheduler`도 직접 파일 삭제.

### Problem

- Scale-out 시 consumer가 같은 머신에 있어야 artifact 접근 가능
- Worker stateless화 불가 (local disk 의존)
- 3개 분리된 스토리지 인터페이스 → 일관성 없는 path convention
- Synchronizer의 직접 filesystem 접근 → 추상화 우회
- MinIO/S3 전환 시 변경 범위 과대 (현재 구조)

### Goal

1. 통합 ObjectStorage interface로 일원화
2. Local disk adapter 유지 (현재 동작 보존)
3. MinIO/S3 adapter 추가 가능한 구조 확보
4. Scale-out 시 endpoint URL만 교체로 전환
5. Synchronizer 직접 filesystem 접근 제거

---

## 2. Decision

> 통합 `ObjectStorage` interface를 `module-common`에 정의. Local adapter만 구현. MinIO(S3-compatible) adapter는 scale-out 시점에 추가. 현재는 abstraction layer 정리에 집중.

```text
module-common
  └── maple.expectation.common.storage
        ├── ObjectStorage (interface)
        │     put(key, InputStream)
        │     get(key): InputStream
        │     delete(key)
        │     deleteByPrefix(prefix)
        │     list(prefix): List<ObjectInfo>
        │     exists(key): Boolean
        ├── LocalObjectStorage (기존 3개 adapter 통합)
        └── S3ObjectStorage (scale-out 시 구현)
```

### 전환 순서

1. `module-common`에 통합 `ObjectStorage` interface 정의
2. `LocalObjectStorage` 구현 (기존 3개 adapter 로직 통합)
3. Calculator → 통합 interface 사용하도록 변경
4. Synchronizer reader/writer → `Paths.get()` 제거, interface 사용
5. External-api cleanup → interface 사용
6. 기존 3개 interface 제거

---

## 3. Trade-offs

### Sensitivity

* 청크 크기 (1~8MB gzipped) — 네트워크 전송 시 latency 영향
* 처리율 (250 chunks/s peak) — S3 API rate limit
* MinIO single-node 안정성 — SPOF
* Synchronizer 직접 filesystem 접근량 (3개 reader + cleanup)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Abstraction 선행 | 코드 정리, 일관성, 향후 MinIO zero-cost 전환 | 지금 당장 scale-out 불가 |
| MinIO 즉시 도입 | Scale-out 즉시 가능 | 운영 복잡도, network latency, MinIO SPOF |
| 현행 유지 | 변경 비용 없음 | Scale-out 시 대공사 필요 |

### Risk

* Abstraction이 실제 S3 패턴과 안 맞을 가능성 → Local adapter 먼저 검증하며 interface 설계
* Synchronizer reader 교체 범위 과대 → 점진적 전환 필요

### Non-Risk

* Local disk 성능 — abstraction 거쳐도 성능 변화 없음 (동일 `Paths.get` 내부 구현)
* 기존 pipeline 동작 — interface만 교체, 로직 변화 없음
* MinIO → S3 migration — S3-compatible API로 endpoint만 교체

---

## 4. Result / Evidence

### Metrics

| Metric | Current | Target (abstraction) | Target (MinIO) |
| ------ | ------- | -------------------- | -------------- |
| Storage interfaces | 3개 분리 | 1개 통합 | 1개 통합 + S3 adapter |
| 직접 filesystem 접근 | 5곳 (synchronizer 3 + cleanup 2) | 0 | 0 |
| Scale-out 준비 | 불가 | interface만 준비 | 완전 가능 |
| MinIO 운영 부담 | 없음 | 없음 | container + disk + 모니터링 |

### Observed Result (pipeline test 기준)

* character-basic 청크: 1.4MB gzipped, 298개, 총 ~420MB
* item-equipment 청크: ~8MB gzipped, ~1200개 예상, 총 ~10GB
* calculator result: ~1MB gzipped
* Pipeline 처리율: chunk 읽기 ~1ms (local), DB upsert ~6-7s → 읽기 latency 영향 무시 가능

### MinIO 도입 타이밍 기준

아래 조건 충족 시 S3 adapter 추가:
- k8s/k3s 도입 완료
- calculator 또는 synchronizer replicas > 1
- NFS/shared volume 운영 부담 체감
- Backfill이 빈번해져 과거 artifact 보존 필요

---

## 5. Summary

> Local disk abstraction을 통합 interface로 일원화. MinIO는 scale-out 전환 시점에 S3 adapter만 추가. 현재는 코드 정리와 MinIO-readiness 확보에 집중.
