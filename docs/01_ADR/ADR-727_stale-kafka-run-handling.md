# ADR-727: Stale Kafka Run Handling

- Status: Accepted
- Date: 2026-06-14
- Owner: zbnerd

---

## 1. Background / Problem

### Background

Pipeline은 daily run이 실패/재시작될 때마다 `external-api.snapshot.chunk-ready`, `external-api.urgent.snapshot.chunk-ready`, `calculator.result.chunk-ready` 토픽에 메시지가 누적된다. Kafka retention이 길고, 이전 run이 chunk-ready를 발행한 뒤 source chunk가 MinIO에 쓰여지지 못한 채 죽으면 (writer thread fail, deploy 중단 등), 이후 새로 시작된 run의 calculator/synchronizer는 이전 run의 stale 메시지를 계속 consume한다.

### Problem

2026-06-14 82h endurance test의 마지막 run에서 다음 연쇄 실패 관측:

- 4개의 runId (`20260614-193138`, `201639`, `201641`, `204910`) 가 Kafka에 동시 존재
- 현재 ext-api runId: `20260614-201639-634030030`
- Calculator가 `201641-1492629` 의 item-equipment chunk-ready 585 회 consume → MinIO GET `runs/201641-1492629/item-equipment/chunks/part-000XXX.jsonl.gz` NoSuchKey 404 반복
- `calculator_users_processed_total` 1h49m 동안 정체 (13.05K)
- Synchronizer result-chunk-consumer LAG=26 (calculator가 새 result event 발행 못함)
- `character_basic_read_model` 새 row 거의 안 들어옴 (synced chunks=2)
- 동시에 Nexon 429 rate limit 15,175회 (병렬 urgent + main path 부하)

### Goal

Calculator/synchronizer가 **현재 runId가 아닌 모든 chunk-ready 메시지를 즉시 drop**하도록 해, stale message retry loop가 정상 chunk 처리를 막지 않도록 한다.

---

## 2. Decision

> **Calculator는 ext-api의 run-status를 주기적으로 poll하여 현재 runId를 추적하고, chunk-ready 메시지의 runId가 current와 다르면 metrics로 카운트만 하고 즉시 drop한다. Synchronizer는 calculator가 발행하는 result event의 runId가 current와 다를 때 동일하게 drop한다. Cleanup 모듈은 새 endpoint로 stale Kafka 메시지를 commit-skip 할 수 있다.**

```text
ext-api (8081)             Kafka topics                  Calculator (8082)
┌─────────────┐  publish   ┌──────────────────┐  poll    ┌──────────────────────┐
│  /run-status│ ────────► │ snapshot.chunk-  │ ───────► │ CurrentRunIdHolder  │
│             │            │ ready            │          │ (every 30s)         │
│  current    │            │ urgent.chunk-    │          │   │                 │
│  runId=X    │            │ ready            │          │   ▼                 │
└─────────────┘            └──────────────────┘          │ Coordinator.handle()│
                                                         │   if runId != X:    │
                                                         │     log.warn + skip │
                                                         │   else: process     │
                                                         └──────────────────────┘
                                                                  │
                                                                  ▼
                                                         ┌──────────────────────┐
                                                         │ synchronizer-result- │
                                                         │ chunk-consumer       │
                                                         │   if runId != X:     │
                                                         │     skip (idempotent)│
                                                         └──────────────────────┘
```

---

## 3. Trade-offs

### Sensitivity

- ext-api의 `/api/internal/run-status` 응답 latency (현재 ~1ms, 30s polling이면 영향 없음)
- Calculator가 stale 메시지 commit-skip할 때 Kafka lag (단기 증가, 정상화)
- ext-api 다운 시 Calculator가 currentRunId를 stale하게 잡고 있을 위험

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Polling 방식 (vs run-completed 이벤트 구독) | 구현 단순, ext-api 변경 zero | stale runId 검출 latency ≤ 30s |
| Drop (vs DLT로 보내기) | 코드 단순, retry 없음 | stale 원인 디버깅 정보 손실 (단 metrics로 카운트) |
| 모든 runId mismatch drop | 무한 retry loop 차단 | 어떤 stale가 의도된 retry였는지 구분 불가 (현 시스템엔 그런 케이스 없음) |

### Risk

- ext-api 응답이 늦어 Calculator가 stale runId를 정상으로 잘못 판단 → 정상 chunk 스킵 가능. **mitigation**: poll 30s + 마지막 successful poll의 timestamp도 함께 비교 (≤ 2분 전이면 fresh)
- Calculator가 의도적으로 같은 runId를 두 번 받아야 하는 경우 (urgent path) 도 동일 runId이므로 영향 없음
- 동기화 시점에 Calculator가 발행한 result event가 synchronizer에 늦게 도착해 synchronizer가 stale로 판단할 위험. **mitigation**: synchronizer는 result event의 `createdAt`도 함께 검사, 5분 이내만 유효

### Non-Risk

- Calculator의 정상 chunk 처리는 변경 없음 (filtered 처리만 추가)
- 정상 메시지의 commit/ACK 시점은 동일 (success/failure 관계없이 ACK는 수행)
- 새 run 시작 시점의 offset reset 불필요 (Kafka의 stale 메시지 commit-skip이 자동으로 흡수)

---

## 4. Result / Evidence

### Metrics

| Metric | Before | After (expected) |
| ------ | ----: | ----- |
| `calculator_chunks_skipped_total{reason=stale_run}` | 0 | ~600 (1회 purge) |
| `calculator_users_processed_total` 증가율 (정상 chunk 도착 시) | 0/h | 250/s |
| `synchronizer_result_chunk_lag` | 26 (정체) | 0 (정상화) |
| `character_basic_read_model` insert 속도 | ~0/min | ~1000/min |
| Nexon 429 (병렬 path 축소 후) | 15,175/run | 0 |

### Observed Result

- (이번 run 폐기, 다음 clean run에서 측정 예정)
- 검증 방법: 새 DAG trigger → calculator chunks_processed 증가율 / synchronizer lag 0 / basic_read_model +1000/min

---

## 5. Summary

> **Calculator/Synchronizer가 ext-api의 currentRunId를 추적하고, mismatch 메시지는 즉시 drop. 운영자는 cleanup 모듈의 새 endpoint로 stale Kafka를 강제 commit-skip할 수 있다.**
