# Guardrails - Concurrency

## 개요

비동기 처리, 스레드 풀 튜닝, Virtual Threads에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ASYNC-001 | [Async Non-Blocking Pipeline Pattern](async-patterns.md) | critical | Async, Non-Blocking, Pipeline, CompletableFuture |
| GR-ASYNC-002 | [Thread Pool Backpressure Best Practice](thread-pool.md) | critical | ThreadPool, Backpressure, RPS, Queue Capacity |
| GR-ASYNC-003 | [Virtual Threads Best Practice](virtual-threads.md) | warning | VirtualThreads, Project Loom |

## 주요 가드레일

### Async Non-Blocking Pipeline
- **DON'T**: `.join()` 내부 호출로 Deadlock 유발
- **DO**: `thenCompose()`로 비동기 체이닝

### Thread Pool Backpressure
- **DON'T**: 1000 RPS 목표인데 max=8로 설정
- **DO**: RPS 요구사항에 맞는 ThreadPool 크기 계산
  ```
  Max Pool Size = (Target RPS × Average Request Time) / Core Count
  Queue Capacity = Max Pool Size × 10
  ```

### Virtual Threads
- **DON'T**: unbounded Virtual Thread Executor 사용
- **DO**: Bean 기반 Executor + 제어된 Virtual Threads

## 관련 문서

- [async-concurrency.md](../../../03_Technical_Guides/async-concurrency.md) Section 21: Async Non-Blocking Pipeline
- [high-traffic-performance-analysis.md](../../../05_Reports/04_02_Cost_Performance/high-traffic-performance-analysis.md)
