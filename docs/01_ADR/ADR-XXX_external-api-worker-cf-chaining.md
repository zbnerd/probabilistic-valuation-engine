# ADR-XXX: ExternalApiWorker CF Chaining — 15초+ 동기 블로킹 해소

- Status: Proposed
- Date: 2026-06-04
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- ExternalApiWorker.processPipeline()이 OCID resolve → Equipment fetch → Snapshot write → CPU 계산 → Result 저장을 단일 PGMQ worker thread에서 동기 실행
- 3개 `.join()` + 1개 `runBlocking` = 최대 15초+ worker thread 점유
- PGMQ worker pool(cores×2)의 모든 thread가 점유되면 throughput 병목

### Problem

- 단일 메시지 처리에 15초+ 소요 → worker pool 포화 → 전체 파이프라인 throughput 제한

### Goal

- Worker thread 점유 시간 5초 이하
- `.join()` 3회 → 1회 (최종 ACK/NACK만)
- `runBlocking` 제거

---

## 2. Decision

> CompletableFuture 체이닝으로 파이프라인 전체를 비동기화. process() 내부에서 단일 `.join()`만 유지.

```text
pipelineAsync() {
  findJobById (supplyAsync) → CF
    └→ 상태 체크 (terminal/snapshot_ready/not_processable)
         └→ resolveOcidAndFetchEquipmentAsync() → CF (apiCallExec VT)
              └→ thenCompose: 병렬 시작
                   ├─ snapshotPut (snapshotExec VT)
                   ├─ convertItems + buildInput + saveIfAbsent
                   └→ thenCompose: snapshotPut 완료 후
                        └→ saveSnapshotMetadata [TX] → runCalculationAndComplete [CPU+TX]
}.whenComplete { timer.close }

process() {
  pipelineAsync().join()  // CompletionException 언래핑
}
```

---

## 3. Trade-offs

### Sensitivity

* 외부 API 응답 시간 (Nexon API latency)
* Equipment 캐시 적중률 (@Cacheable hit/miss)
* VT executor 스레드 수

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| CF 체이닝 (in-process) | 코드 변경 최소, process() 시그니처 유지 | 멀티 큐 수준의 독립 스케일링 불가 |
| supplyAsync로 @Cacheable 래핑 | 캐시 동작 유지 + 비동기 실행 | 스레드 경계 1회 추가 |
| 단일 .join() (process) | ACK/NACK Boolean 반환 유지 | worker thread 1회 park |

### Risk

* CF 체이닝 디버깅 복잡도 증가 (스레드 경계 다수)
* 예외 전파가 CompletionException으로 래핑되어 언래핑 필요

### Non-Risk

* @Cacheable 동작 변경 없음 (supplyAsync 내부에서 동일 메서드 호출)
* 기존 PgmqWorker ACK/NACK 메커니즘 변경 없음
* StepTimer lifecycle: whenComplete로 단일 책임 보장

---

## 4. Result / Evidence

### Metrics

| Metric | Before | Target |
| ------ | -----: | ------ |
| .join() count | 3 | 1 |
| runBlocking count | 1 | 0 |
| Worker thread occupancy | ~15s | <5s |

### Observed Result

* (부하테스트 후 업데이트)

---

## 5. Summary

> ExternalApiWorker 파이프라인을 CF 체이닝으로 전환하여 .join() 3회→1회, runBlocking 제거, worker thread 점유 시간 5초 이하 달성.
