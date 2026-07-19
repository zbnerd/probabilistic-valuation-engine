# Pipeline Artifact Identity and Lifecycle

- **Status**: Approved
- **Priority**: P0
- **Date**: 2026-07-19
- **Program**: [ETL module-infra Deepening Program](2026-07-19-etl-infra-deepening-program-design.md)

---

## 1. Scope

`module-pipeline-artifact`가 pipeline artifact의 identity, write/finalize lifecycle, LocalFS/MinIO adapter, retention, durable cleanup inbox를 소유한다. `module-common`의 `ObjectStorage`는 backend-neutral port로 유지한다.

대상 caller는 `SnapshotSinkEventPublisher`, `ChunkFileManager`, `GzipJsonlChunkWriter`, `RunMarkerWriter`, `CalculatorChunkProcessingCoordinator`, `CalculationResultWriter`, `ResultChunkEventPathBuilder`, `RunCleanupService`, `ConsumedChunkInbox`다.

## 2. Non-goals

- 기존 source/result object key rename
- object body schema나 Kafka event schema 변경
- LocalFS 또는 MinIO 중 하나 제거
- retention 기간·개수·byte/runtime limit 변경
- 일반-purpose filesystem API 제공
- app/web 전용 persistence 이동

## 3. Problem

현재 `runs/`, `calculator/runs/`, `_RUNNING`, `_SUCCESS` 조합이 producer, calculator, synchronizer, cleanup에 흩어져 있다. storage backend는 추상화됐지만 logical artifact가 무엇이고 언제 publish/delete 가능한지는 caller가 각자 판단한다.

이 분산은 세 가지 오류를 허용한다.

- upload보다 Kafka publish가 먼저 완료되는 순서 역전
- partial run을 완료된 run으로 오인하거나 cleanup이 active run을 삭제
- writer 실패 시 임시 자원과 retry 책임이 caller마다 달라짐

## 4. Decision

### 4.1 Module and package ownership

새 Gradle module은 `module-pipeline-artifact`, package root는 `maple.pipeline.artifact`다.

```text
module-common
  └─ ObjectStorage, ObjectInfo

module-pipeline-artifact
  ├─ identity   ArtifactKey, SourceArtifactLayout, CalculatorArtifactLayout
  ├─ write      ArtifactWriter, ArtifactReceipt
  ├─ lifecycle  RunLifecycle, RunState
  ├─ retention  ArtifactRunCatalog, ArtifactRetentionService
  ├─ inbox      CleanupInboxStore, CleanupInboxEntry
  ├─ storage    LocalFsObjectStorage, MinioObjectStorage
  └─ config     ArtifactStorageAutoConfiguration
```

`ObjectStorage` 구현과 storage property/autoconfiguration은 `module-infra`에서 이 모듈로 이동한다. module-infra는 기존 bean/import compatibility가 필요할 때 delegation facade만 제공한다.

### 4.2 Typed identity

`ArtifactKey`는 상대 object key만 허용하는 validated value object다.

- 빈 값, 선행 slash, `..` segment, backslash를 거절한다.
- 문자열 변환은 storage adapter boundary에서만 수행한다.
- run ID, endpoint, chunk ID는 slash를 포함할 수 없다.
- 기존 key를 읽는 parser는 invalid key를 명시적 parse failure로 반환하고 임의 보정하지 않는다.

layout은 다음 기존 문자열을 그대로 생성한다.

| Artifact | Exact key |
| --- | --- |
| source run root | `runs/{runId}` |
| source endpoint root | `runs/{runId}/{endpoint}` |
| source chunk | `runs/{runId}/{endpoint}/chunks/{chunkFileName}.jsonl.gz` |
| source endpoint manifest | `runs/{runId}/{endpoint}/manifest.json` |
| source run marker | `runs/{runId}/_RUNNING` |
| source endpoint marker | `runs/{runId}/{endpoint}/_RUNNING` |
| source success marker | `runs/{runId}/_SUCCESS` |
| calculator run root | `calculator/runs/{runId}` |
| calculator result chunk | `calculator/runs/{runId}/{endpoint}/chunks/result-{chunkId}.jsonl.gz` |
| calculator marker/manifest | 현재 caller가 사용하는 calculator run/endpoint root 아래의 기존 filename |
| OCID mapping | `ocid-mapping/ocid-mapping-{runId}.jsonl.gz` |
| failed records | `runs/{runId}/failed.jsonl` |

`chunkFileName`은 기존 producer가 생성하는 `part-NNNNNN`을 그대로 받는다. layout은 numbering 정책을 새로 만들지 않는다. source event의 `objectKey`와 synchronizer의 `sourceObjectKey`는 동일한 layout method 결과여야 한다.

### 4.3 Artifact write boundary

`ArtifactWriter`는 다음 원자적 논리 순서를 소유한다.

1. caller가 stream/serializer callback과 typed key를 전달한다.
2. writer가 임시 sink와 gzip stream의 lifetime을 연다.
3. serialization과 checksum/byte count를 완료한다.
4. `ObjectStorage.putStream`을 완료한다.
5. put 완료 후 `ArtifactReceipt(key, compressedBytes, uncompressedBytes, checksum)`를 반환한다.
6. success/failure 모두 임시 자원을 닫고 local temp file을 제거한다.

event publisher는 `ArtifactReceipt`를 입력으로만 받을 수 있다. 따라서 upload가 완료되지 않은 artifact에 대한 event를 만들 수 없다. retry는 동일 deterministic key에 overwrite하며 동일 content checksum이면 성공으로 취급한다.

### 4.4 Run lifecycle

`RunLifecycle`은 다음 상태 전이만 허용한다.

```text
ABSENT
  → write _RUNNING
RUNNING
  → write chunks
  → write manifest
  → write _SUCCESS
  → delete _RUNNING
SUCCEEDED
```

- finalize는 manifest와 `_SUCCESS`가 이미 같은 run identity를 나타내면 성공하는 멱등 연산이다.
- `_SUCCESS` 이전 failure는 `_RUNNING`을 남긴다. retry 또는 stale-run retention이 상태를 해소한다.
- `_SUCCESS` 작성 후 `_RUNNING` 삭제 실패는 완료 상태로 판정하되 orphan-marker metric을 올리고 삭제를 재시도한다.
- reader와 retention은 `_SUCCESS` 우선, `_RUNNING` 차선으로 state를 분류한다. 둘 다 있으면 completed-with-orphan-marker다.
- manifest는 모든 chunk receipt가 확보된 뒤 작성한다.
- event publish는 관련 artifact receipt 또는 finalized run을 입력으로 받는다.

### 4.5 Typed retention

`ArtifactRunCatalog`가 raw prefix listing을 `RunState`와 `RunInfo`로 변환한다. `ArtifactRetentionService`는 기존 `RunRetentionPolicy`와 per-cycle safeguards를 재사용한다.

- active: `_RUNNING`만 존재하며 보호 대상
- succeeded: `_SUCCESS` 존재
- incomplete: marker/manifest 조합이 불완전하고 stale threshold를 넘음
- invalid: key를 parse할 수 없으며 자동 삭제하지 않고 metric/report 대상

`keepRecent`, `keepWithinHours`, `maxDeleteRunsPerCycle`, `maxDeleteBytesPerCycle`, `maxRuntimeSeconds`를 그대로 지킨다. listing이 1,000개를 넘는 경우에도 전체 page를 순회해야 하며, candidate 계산 전에 pagination을 끝낸다. delete는 exact typed run prefix에만 실행한다.

### 4.6 Durable cleanup inbox

현재 `ConsumedChunkInbox`의 in-memory queue와 overflow drop을 제거한다. Kafka record를 다음 key에 JSON envelope로 저장한다.

```text
cleanup/inbox/{topic}/{partition}/{offset}.json
```

envelope는 `topic`, `partition`, `offset`, `receivedAt`, 원본 `ChunkConsumedEvent`를 포함한다. Kafka 좌표가 idempotency identity이므로 replay put은 같은 key를 overwrite한다.

처리 순서는 다음과 같다.

1. Kafka message decode
2. inbox envelope put 완료
3. ACK
4. Airflow `POST /api/internal/cleanup/inbox`가 inbox key를 page 단위로 조회
5. event의 result/source object를 idempotent delete
6. 모든 target delete가 성공하거나 이미 없을 때 inbox object delete

target delete 하나라도 실패하면 inbox object를 남겨 다음 drain에서 재시도한다. drain 응답은 scanned, completed, retainedForRetry, deletedTargets를 구분한다. `maxPending`은 drop 기준이 아니라 backlog alert threshold로 의미를 바꾼다.

## 5. Error Semantics

| Failure | Required result |
| --- | --- |
| serialize/gzip failure | object/event/manifest 없음, temp 정리, retryable error |
| object upload failure | receipt 없음, publish 금지, retryable error |
| manifest failure | `_RUNNING` 유지, `_SUCCESS` 없음 |
| success marker failure | `_RUNNING` 유지, finalize retry |
| running marker delete failure after success | completed 판정, metric + delete retry |
| invalid key | 자동 보정/삭제 금지, explicit error/metric |
| retention list page failure | 해당 cycle 삭제 중단 |
| cleanup inbox put failure | Kafka ACK 금지 |
| cleanup target delete failure | inbox entry 유지 |
| duplicate inbox record | deterministic overwrite, pending 1건 |

## 6. Migration

1. 현재 production key와 lifecycle을 characterization tests로 고정한다.
2. `ArtifactKey`와 layout을 추가하고 기존 string builder를 caller별로 치환한다.
3. LocalFS/MinIO 구현을 옮기고 공통 contract suite를 적용한다.
4. external-api writer와 run finalization을 `ArtifactWriter`/`RunLifecycle`로 전환한다.
5. calculator result writer와 synchronizer source path builder를 전환한다.
6. cleanup run catalog/retention을 typed API로 전환한다.
7. durable cleanup inbox를 도입하고 기존 in-memory queue를 제거한다.
8. compatibility facade와 dependency guard를 추가한 뒤 활성 ETL의 infra 의존을 제거한다.

각 단계에서 key와 body가 기존 fixture와 같아야 한다. 기존 object는 rename이나 copy 없이 새 reader가 그대로 읽는다.

## 7. Tests

- `ArtifactKey` validation과 모든 exact key golden tests
- LocalFS/MinIO shared contract: put/get/stream/exists/list/delete/deleteByPrefix/size/lastModified
- 1,001개 이상 object listing과 page boundary
- upload failure 시 event publisher 미호출
- gzip/serialization failure 시 temp file 부재
- finalize replay, success+orphan marker, partial manifest cases
- active/protected run retention exclusion
- invalid run key가 자동 삭제되지 않음
- cleanup inbox restart recovery, duplicate Kafka coordinate, partial target delete retry
- external-api `DataflowContractTest`와 full pipeline LocalFS/MinIO regression

MinIO contract는 실제 MinIO-compatible endpoint를 사용한다. unit fake만으로 backend 의미를 증명하지 않는다.

## 8. Observability

낮은 cardinality tag만 사용해 다음을 기록한다.

- artifact writes/bytes/duration/failures by artifact kind and backend
- lifecycle transitions/finalize retries/orphan markers
- retention scanned/candidate/deleted/protected/invalid/error
- cleanup inbox pending age/count, duplicate writes, completed, retained

runId, object key, character name을 metric tag로 넣지 않는다. 실패 log에는 correlation 가능한 runId/key를 redaction 규칙에 맞춰 포함한다.

## 9. Acceptance Criteria

- production 코드에서 raw `runs/`, `calculator/runs/`, `_RUNNING`, `_SUCCESS` 조합은 artifact module 밖에 없다.
- `SnapshotSinkEventPublisher`와 result publisher는 upload receipt 없이 event를 publish할 수 없다.
- LocalFS/MinIO가 같은 contract suite를 통과한다.
- finalize와 cleanup inbox가 process restart 및 duplicate delivery에 멱등이다.
- cleanup은 active/protected/invalid run을 삭제하지 않는다.
- 1,000개 초과 listing이 누락 없이 처리된다.
- 기존 object key와 Kafka event field 값이 바뀌지 않는다.
- module-external-api, module-calculator, module-synchronizer, module-cleanup이 artifact 사용을 위해 module-infra를 참조하지 않는다.

## 10. ADR Alignment

- ADR-390 retention safeguards를 typed catalog에 보존한다.
- ADR-719/725의 ObjectStorage와 LocalFS/MinIO dual backend 결정을 유지한다.
- ADR-391의 outbound storage seam을 독립 module로 심화한다.
- ADR-722에 따라 module/package 책임을 일치시킨다.
