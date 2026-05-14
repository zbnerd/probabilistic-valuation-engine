# ADR-716: Extract ChunkProcessor Seam from Synchronizer Consumer

- Status: Accepted
- Date: 2026-05-14
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- module-synchronizer의 KafkaResultChunkConsumer가 Kafka 수신, 동시성 제어, MDC, 메트릭 기록, 처리 파이프라인(file read → build → upsert)을 단일 클래스에서 모두 담당
- Zero Try-Catch Policy를 위반: 직접 try-catch-finally 사용

### Problem

- 처리 파이프라인을 Kafka 없이 테스트 불가 (0개 테스트)
- Consumer가 6개 의존성을 직접持有하여 변경 영향 범위가 넓음
- 동일한 예외 처리 패턴이 Consumer에 하드코딩됨

### Goal

- 처리 로직을 별도 모듈로 추출하여 단위 테스트 가능하게 만들기
- Consumer는 Kafka/인프라 관심사만 담당
- try-catch를 LogicExecutor로 교체

---

## 2. Decision

> ChunkProcessor 인터페이스를 seam으로 추출하고, Consumer는 인프라 관심사만 유지한다.

```text
KafkaResultChunkConsumer
  ├─ Kafka 수신, idempotency guard, semaphore, virtual thread dispatch
  ├─ LogicExecutor로 예외 처리 (executeWithFinally + executeOrCatch)
  └─ ACK 정책 (성공 시에만 ACK)

ChunkProcessor (interface)
  └─ process(event): ChunkProcessResult

DefaultChunkProcessor
  ├─ file read → document build → bulk upsert
  └─ pipeline metrics (duration, count, volume)
```

---

## 3. Trade-offs

### Sensitivity

* 메트릭 기록 위치가 Consumer에서 ChunkProcessor로 이동 — 기존 대시보드 쿼리 영향 없음 (메트릭 이름 동일)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| ChunkProcessor seam 추출 | Kafka 없는 단위 테스트, 관심사 분리 | interface + impl 클래스 2개 추가 |

### Risk

* ResultFileReader의 `runBlocking`은 이 PR에서 수정하지 않음 — PR 2에서 별도 처리

### Non-Risk

* 기능 변경 없음 — 동일한 처리 흐름, 동일한 메트릭, 동일한 ACK 정책
* 기존 vtExecutor 누락된 @PreDestroy 추가 — shutdown 안전성 향상

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| | | PR 병합 후 업데이트 |

---

## 5. Summary

> Consumer에서 처리 파이프라인을 ChunkProcessor seam으로 추출하여 테스트 가능하게 만들고, try-catch를 LogicExecutor로 교체했다.
