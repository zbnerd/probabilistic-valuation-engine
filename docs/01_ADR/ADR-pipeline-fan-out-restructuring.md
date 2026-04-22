# ADR: Pipeline Fan-Out 구조 리팩토링

## 상태: 제안 (2026-04-22)

## 컨텍스트

현재 expectation 계산 파이프라인은 다음 구조로 동작한다:

```
PGMQ poll (Semaphore=40)
  → Virtual Thread × N (unbounded)
    → Per-message: 3 preset fan-out
      → Per-preset: ~20 item fan-out
        → CompletableFuture.supplyAsync × 60
          → ThreadPoolTaskExecutor(32/32/5000)
            → Semaphore(64)
              → BlockingSubmitExecutor
                → compute
  → PipelineBuffer(500)
    → drain → batchWrite
```

### 발생한 문제

1. **Burst Amplification**: 40 메시지 × 3 프리셋 × ~20 아이템 = 2,400개 CompletableFuture가 동시 submit. 메시지 수준 Semaphore(40)가 item burst를 제어하지 못함.

2. **Executor 정책에 correctness 의존**: `supplyAsync`가 reject되면 `thenCombine` 체인 전체가 실패. 60개 아이템 중 1개만 reject되어도 해당 캐릭터 전체 계산이 실패한다. CallerRuns/Abort/BlockingSubmit 교체로 trade-off만 반복됨.

3. **Virtual Thread + CPU-bound anti-pattern**: `PgmqWorker`가 `newVirtualThreadPerTaskExecutor()`로 CPU-bound 계산을 실행. 과거 동일 패턴에서 3.5× latency 회귀 확인.

4. **Semaphore(64) 무의미**: ThreadPool max=32, Semaphore permits=64. 항상 즉시 acquire됨. 실제 gate는 스레드 수 자체.

5. **병목 이동 반복**: queue_wait → permit_wait → TaskRejectedException → submitAll 지연 → batchWrite 병목. 튜닝으로 해결 불가.

### 튜닝 이력 (동일 근본 원인의 다른 증상들)

| 변경 | 결과 | 한계 |
|------|------|------|
| Semaphore(32→64) | permit_wait=0 | queue_wait 그대로 |
| CallerRunsPolicy | 태스크 손실 없음 | p1submit 46초 블로킹 |
| AbortPolicy | submit 즉시 | 18,820개 태스크 드랍 |
| BlockingSubmitExecutor | 드랍 제거 | Virtual Thread 위에서 spin-wait |
| Queue 500→5000 | reject 감소 | burst 여전히 존재 |

## 결정

Fan-out 구조를 평탄화하여 bounded work queue + fixed worker 모델로 전환한다.

### 목표 구조

```
PGMQ poll (Semaphore=40)
  → Per-message: flatten to item task list
    → ArrayBlockingQueue<ItemTask>(bounded)
      → Fixed platform thread workers
        → compute single item
          → Result → PipelineBuffer
            → drain → batchWrite (all batch)
```

### 변경 원리

- **Message → item 평탄화**: fan-out(3×20)을 flat list로 변환, bounded queue가 item 단위 gate
- **Executor 정책 독립**: `supplyAsync` 제거 → rejection policy가 correctness에 영향 없음
- **Per-item 독립 실행**: 1개 item 실패 → 전체 실패 아니게 변경
- **Platform thread only**: CPU-bound 계산에 virtual thread 제거

## 이슈 분해

| # | 이슈 | Priority | 독립 배포 |
|---|------|----------|-----------|
| #733 | Semaphore(64) 제거 | P0 | O |
| #734 | Read Model Write 배치화 | P0 | O |
| #735 | Virtual Thread → Platform Thread | P1 | O |
| #736 | Fan-out → flat work queue + thenCombine 제거 | P1 | O |
| #737 | Per-item error isolation (#736에 포함되어 해소) | P1 | O |
| #738 | BlockingSubmitExecutor 제거 (#736 완료 후 불필요) | P2 | O |
| #739 | PipelineBuffer backpressure 정렬 | P2 | O |
| #740 | Item Compute Worker 분리 | P1 | O |

### 적용 순서

**Phase 1 — Quick Wins** (독립 배포, 즉시 효과):
1. #733 Semaphore 제거
2. #734 Read Model 배치화
3. #735 Platform Thread 전환

**Phase 2 — 핵심 구조 변경**:
4. #736 Flat work queue (thenCombine 제거 포함 → #737, #738 자동 해소)

**Phase 3 — 정리**:
5. #740 Item Compute Worker 분리
6. #739 PipelineBuffer 정렬

## 근거

- Burst 크기(2,400)가 steady-state 처리량(~53 item/s)을 영구적으로 초과 → 튜닝 불가
- Executor 정책이 correctness를 결정하는 구조 자체가 취약
- thenCombine 체인으로 인해 부분 실패가 불가능
- 목표 구조는 item 단위 backpressure, 독립 실패, executor 독립을 동시에 달성

## 위험

- #736(P1)은 `PresetCalculationHelper`의 핵심 집계 로직 재작성 → 정확한 테스트 필수
- Item Compute Worker 분리(#740)는 새 컴포넌트 도입 → 기존 PipelineBuffer와의 연동 확인 필요
- Platform Thread 전환(#735) 시 I/O 대기(equipLoad)에서 스레드 블로킹 가능 → 풀 크기 충분히 설정

## 관련 이슈

- Epic: #732
- Bulk JDBC upsert: commit `aa0be773`
- Semaphore(64) 증설: commit `9fe299d5`
- BlockingSubmitExecutor 도입: commit `5ed51ea0`
