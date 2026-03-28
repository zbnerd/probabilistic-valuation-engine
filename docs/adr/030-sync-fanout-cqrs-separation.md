# ADR-030: Synchronous Fan-Out I/O → Async Non-Blocking + CQRS 전환

## 메타데이터

| 항목 | 값 |
|------|-----|
| 상태 | 제안됨 (Proposed) |
| 결정일 | 2026-03-28 |
| 결정자 | MapleExpectation Team |
| 검토자 | Architecture Review Board |
| 선행 ADR | ADR-004 Collect-Compute-Serve Pipeline, ADR-006 Scale-out Strategy |
| 관련 이슈 | #623 |

---

## 1. 배경 (Context)

### 문제 상황

현재 시스템은 다음 특성을 가진 아키텍처로 구성됨:

- **Fan-out External API Calls**: 여러 외부 API로 I/O-bound 호출 발생
- **High Concurrency**: 다수의 동시 요청 처리
- **Read Aggregation + Write Persistence in Same Flow**: 집계(Read)와 영속화(Write)가 동일 Synchronous Chain에 결합
- **No Separation between Read and Write Paths**: 경로 분리 없음

이 구조에서 발생하는 문제:

1. **Thread Pool 고갈**: 각 팬아웃 호출이 요청당 Thread를 점유. N 동시 요청 × M 업스트림 = Thread Pool 빠른 소진
2. **Head-of-Line Blocking**: 전체 Latency = `max(upstream_1 ... upstream_M)`. 하나의 저하된 API가 전체 P99 지배
3. **Cascading Failure**: 느린 외부 API → Thread Starvation → Queue 적체 → Timeout 연쇄 → 상위 서비스 장애
4. **Backpressure 부재**: 업스트림 용량 저하 시 Graceful Degradation 불가
5. **Mixed Concerns**: 느린 DB Write가 Read Aggregation Path에 역압력 전파

### 요구사항

- 외부 API Fan-out 시 Thread 점유 최소화
- 업스트림 장애 격리 (격자된 장애가 전파되지 않아야 함)
- Read/Write 경로 독립적 확장 가능
- High Concurrency 하에서 안정적 응답 시간 보장

---

## 2. 결정 (Decision)

### Async Non-Blocking Fan-Out + CQRS 패턴 도입

```
[Before] Request Thread → [Sync API Call 1] → [Sync API Call 2] → [Sync API Call N] → Aggregate → DB Write → Return

[After]  Request Thread → [Async API Call 1 ┐]
                                   [Async API Call 2 ┤→ Aggregate → Event Publish → Return
                                   [Async API Call N ┘]                            ↓
                                                        Consumer → DB Persist
```

### 전환 전략

| 단계 | 변경 내용 | 기대 효과 |
|------|-----------|-----------|
| Phase 1 | Fan-Out 비동기 전환 (Coroutine / WebClient) | Thread 사용량 O(1) 수렴 |
| Phase 2 | Read/Write 분리 — Event 발행 + Async Consumer | Write 장애 → Read 경로 영향 제거 |
| Phase 3 | 업스트림별 Circuit Breaker + Bulkhead 적용 | 장애 격리, Graceful Degradation |

---

## 3. 근거 (Rationale)

### Synchronous Fan-Out vs Async Non-Blocking 비교

| 항목 | Synchronous (현재) | Async Non-Blocking (제안) |
|------|--------------------|---------------------------|
| Thread 사용 (M개 호출) | M개 Thread 점유 | O(1) Thread |
| Latency | `sum(call_1 ... call_M)` 순차 또는 `max()` Thread 점유 병렬 | `max(call_1 ... call_M)` True 병렬 |
| 업스트림 장애 영향 | 전체 요청 장애 | 해당 업스트림만 Fallback |
| Read/Write 결합 | 강결합 | 느슨한 결합 (Event) |
| Backpressure | 불가 | 가능 (Buffer, Drop, Rate Limit) |
| 일관성 | Strong Consistency | Eventual Consistency |

### 수학적 근거

```
P(request fail) = 1 - ∏(1 - P(upstream_i timeout))
```

- Synchronous: 장애 확률이 업스트림 수에 따라 기하급수적 증가
- Async + Circuit Breaker: 장애 업스트림 자동 차단 → 확률 수렴

---

## 4. 결과 (Consequences)

### 긍정적

- Thread 사용량 O(1) → High Concurrency 하에서도 Thread Pool 안정
- 업스트림 장애 격리 → Cascading Failure 차단
- Read/Write 독립 확장 → 각 경로에 최적화된 리소스 배정 가능
- Backpressure 메커니즘 도입 → Graceful Degradation

### 부정적 (한계)

- **Eventual Consistency**: Read가 즉시 Write 결과를 반영하지 않을 수 있음
- **시스템 복잡도 증가**: Event Pipeline, Consumer, Dead Letter Queue 등 추가 인프라
- **디버깅 난이도**: 비동기 흐름으로 Distributed Tracing 필수

### Risk

- Consumer 장애 시 Event 유실 가능 → Dead Letter Queue + Retry 정책 필수
- Event 순서 보장 필요 시 Partition Key 설계 필요

---

## 5. 구현 세부

### Phase 1: 비동기 Fan-Out

- Kotlin `Coroutine` `async`/`await` 또는 `WebClient`로 M개 호출 Non-blocking 병렬 실행
- `supervisorScope`로 개별 호출 실패가 전체를 중단하지 않도록 격리

### Phase 2: CQRS 분리

- Aggregation 결과를 Domain Event로 발행
- 별도 Consumer가 Event 수신 후 DB Persist
- Consumer 장애 대비 Dead Letter Queue + 지수 백오프 Retry

### Phase 3: Resilience4j 적용

- 각 업스트림에 Circuit Breaker: 느린 응답 N회 → Open → Fallback
- Bulkhead: 업스트림별 동시 호출 수 제한
- Timeout: 업스트림별 개별 Timeout 설정

---

## 6. 관련 파일

| 파일/영역 | 역할 |
|-----------|------|
| `module-infra` | Coroutine / WebClient 설정 |
| `module-app` | Fan-Out 호출 로직 → 비동기 전환 |
| `module-domain` | Domain Event 정의 |
| `docs/adr/004-collect-compute-serve-pipeline.md` | Collect-Compute-Serve 파이프라인 ADR |
| `docs/adr/006-scaleout-strategy.md` | Scale-out 전략 ADR |
