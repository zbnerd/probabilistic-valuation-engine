# ADR: Backpressure 동시성 제한

- Status: Accepted
- Date: 2026-06-04
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- External API 파이프라인 5곳에서 동시 실행 수 제한 없이 대규모 fan-out 발생
- Nexon HTTP 커넥션 풀 max 150, HikariCP 커넥션 풀 제한 존재
- 버스트 트래픽 시 최대 1000개 HTTP 호출 동시 fan-out → 커넥션 풀 고갈, 외부 API 과부하

### Problem

- OcidLookupPhase/SnapshotFetchPhase: batch당 1000개 CompletableFuture.allOf() fan-out
- UrgentCharacterRequestConsumer: Kafka 메시지당 무제한 async chain
- PriorityAdmissionControl: PriorityBlockingQueue가 무제한 성장 (offer() never returns false)
- AdaptiveMicroBatchUserService: Channel.UNLIMITED → 생산자 > 소비자 시 무한 증가

### Goal

- 각 위치에 적합한 backpressure 메커니즘 추가
- 동시성 제한을 YAML로 외부화하여 런타임 튜닝 가능

---

## 2. Decision

> Semaphore 기반 동시성 제한 + 큐/채널 바운딩 추가.

```text
OcidLookupPhase / SnapshotFetchPhase: Semaphore(100) + tryAcquire with backoff retry
UrgentCharacterRequestConsumer: Semaphore(30) + tryAcquire (fail-fast, ACK)
PriorityAdmissionControl: size 체크 before offer (minor overshoot 허용)
AdaptiveMicroBatchUserService: Channel.BUFFERED(200) + maxInFlight YAML 외부화
```

---

## 3. Trade-offs

### Sensitivity

* Nexon HTTP 커넥션 풀 크기 (max 150)
* 외부 API rate limit (permits-per-second)
* HikariCP 커넥션 풀 크기

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Semaphore backoff retry | 100% 처리 보장 (skip 없음) | 최대 300ms 지연 (50+100+150ms) |
| 인스턴스별 Semaphore | 구현 단순성 | Phase 간 동시성 합계 제어 불가 (순차 실행이므로 무의미) |
| size 체크 (Lock 없음) | throughput 저하 없음 | 최대 worker 수(16)만큼 overshoot 가능 |
| Channel.BUFFERED | 코루틴 관용구 | send() suspend로 생산자 대기 |

### Risk

* Semaphore maxInFlight 설정치가 너무 낮으면 throughput 저하
* PriorityAdmissionControl size 체크 race condition으로 ~16개 overshoot (허용 범위)

### Non-Risk

* Phase 순차 실행으로 인한 동시 in-flight 합산 문제 제거
* UrgentConsumer disabled 상태에서 Semaphore 영향 없음

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| max-in-flight | 100 | Nexon HTTP pool 150의 ~67% |
| urgent-max-concurrent | 30 | Urgent 요청 제한 |
| batch-channel-capacity | 200 | 기존 UNLIMITED → bounded |
| backoff max retries | 3 | 50ms, 100ms, 150ms |

### Observed Result

* 컴파일 통과, 기존 테스트 통과 확인

---

## 5. Summary

> fan-out 5곳에 Semaphore/Channel 바운딩 추가로 Nexon API 과부하 및 커넥션 풀 고갈 방지.
