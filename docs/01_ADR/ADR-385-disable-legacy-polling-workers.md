# ADR-385: Disable Legacy Polling Workers — PGMQ-Only Consumption

**Status**: Accepted
**Date**: 2026-04-04
**Context**: V5Config, PriorityCalculationExecutor, ExpectationCalculationWorker

## Context

V5 CQRS 아키텍처에서 메시지 소비 방식이 **PGMQ 기반**으로 마이그레이션 완료되었으나,
기존 **폴링 기반 워커**(`PriorityCalculationExecutor` → `ExpectationCalculationWorker`)가
서버 기동 시 자동 실행되고 있었다.

### 문제

서버 시작 시 `V5Config.@PostConstruct` → `executor.start()` → 워커 스레드 생성 →
`queue.poll()` → `UnsupportedOperationException` 무한 발생.

```
서버 시작
  → PriorityCalculationExecutor.start()
    → ExpectationCalculationWorker.runForPriority() [무한 루프]
      → queue.poll() → UnsupportedOperationException
```

**영향**: CPU 낭비, 로그 폭발, worker useless loop.

### 이중 소비 구조

| 구현 | 소비 방식 | 상태 |
|------|----------|------|
| ExpectationCalcWorker (PGMQ) | `@Scheduled` + `pgmq.read()` | 활성 |
| ExpectationCalcLowWorker (PGMQ) | `@Scheduled` + `pgmq.read()` | 활성 |
| ExpectationCalculationWorker (Legacy) | `while` 루프 + `queue.poll()` | **비활성화 필요** |

## Decision

`V5Config.startWorkerPool()`에서 `executor.start()` 호출을 주석 처리하여
레거시 폴링 워커의 자동 시작을 비활성화한다.

`PriorityCalculationExecutor`의 **작업 제출** 기능(`submitHighPriority`, `submitLowPriority`)은 유지한다.

## Consequences

### 긍정
- 서버 기동 시 무한 에러 루프 제거
- CPU/로그 낭비 방지
- PGMQ 단일 소비 구조로 명확화

### 부정
- `PriorityCalculationExecutor.start()/stop()` 메서드가 데드 코드화
- 향후 PGMQ 없는 환경에서 폴링 워커 재활성화 시 설정 필요

### 추후 정리
- `PriorityCalculationExecutor`의 워커 관련 코드 완전 제거
- `ExpectationCalculationWorker`의 `runForPriority()` 제거
- `ExpectationCalculationQueue.poll()` 제거
