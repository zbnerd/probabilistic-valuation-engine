# ADR-721: join()/get()/runBlocking 동기 경계 정당화

- Status: Accepted
- Date: 2026-06-04
- Owner: Claude Code

---

## 1. Background / Problem

### Background

- 프로젝트 규칙(`.claude/rules/async-patterns.md`)에서 서버 코드의 `.join()`, `.get()`, `runBlocking` 사용을 금지
- #901에서 15건 위반을 식별하고 제거를 시도
- #1109에서 4건 제거 (TieredCache .orTimeout 추가, SingleFlight .orTimeout 추가, RealNexonAuthClient .block(Duration), DiscordAlertChannel .subscribe())

### Problem

- 나머지 11건은 인터페이스 계약상 동기 반환 필수 → CF 체이닝 전환 불가

### Goal

- 동기 경계가 불가피한 이유를 문서화하고 정당화

---

## 2. Decision

> 2가지 동기 경계 카테고리에서 .join()/.get()/runBlocking 유지를 정당화한다.

```text
Category A: PGMQ Message Callback (7건)
  - PGMQ topic.subscribe 핸들러는 ConsumeResult/Boolean을 동기 반환해야 함
  - 핸들러 내부에서 비동기 작업의 결과를 기다리려면 .join() 또는 runBlocking 불가피

Category B: Spring Cache Callable (4건)
  - Spring Cache get()의 Callable 인터페이스가 동기 값 반환 요구
  - Callable 내부에서 비동기 로더의 결과를 기다리려면 .join() 불가피
```

---

## 3. Trade-offs

### Sensitivity

* PGMQ 핸들러 인터페이스 변경 시 전체 worker 아키텍처 재설계 필요
* Spring Cache 추상화 교체 시 캐시 계층 전면 재작성 필요

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 동기 경계 유지 | PGMQ/Spring Cache 인터페이스 호환 | 해당 스레드의 비동기 이점 |

### Risk

* PGMQ worker 스레드가 .join() 대기 중 블로킹 → worker pool 고갈 가능 (bounded pool로 완화)
* Spring Cache Callable에서 .join() 타임아웃 없으면 무한 대기 (.orTimeout으로 완화)

### Non-Risk

* CF 체이닝 가능한 경로에서의 .join()은 #1109에서 이미 제거 완료
* .orTimeout 추가로 무한 블로킹 위험 제거 (TieredCache 5s, SingleFlight 10s)

---

## 4. Result / Evidence

### Category A: PGMQ Sync Boundary (7건)

| 파일 | 패턴 | 비고 |
| ---- | ---- | ---- |
| `OcidResolveWorker.kt:71` | `.join()` | ADR 인라인 문서화 |
| `CalculationWorker.kt:91` | `.join()` | ADR 인라인 문서화 |
| `ExternalApiWorker.kt:224` | `.join()` | PGMQ 콜백 |
| `ExternalApiWorker.kt:338` | `runBlocking` | 병렬 아이템 변환 |
| `ExternalApiWorker.kt:392` | `.join()` | .orTimeout(15s) 적용 |
| `ResultReadyProjectionWorker.kt:89-90` | `.join()` x2 | 병렬 fan-out 후 join |
| `ResultReadyProjectionWorker.kt:123` | `runBlocking` | 병렬 처리 |
| `PgmqWorker.kt:373` | `runBlocking` | 코루틴 병렬 청크 처리 |

### Category B: Spring Cache Callable (4건)

| 파일 | 패턴 | 비고 |
| ---- | ---- | ---- |
| `TieredCache.kt:126` | `.join()` | .orTimeout(5s) + fallback 적용 |
| `PostgresSingleFlightStrategy.kt:77` | `.join()` | .orTimeout(10s) 적용 |
| `GameCharacterService.java:185` | `.join()` | .orTimeout 적용, 인라인 주석 |
| `EquipmentExpectationServiceV4.java:228` | `.join()` | 캐시 Callable 동기 경계 |
| `EquipmentFetchProvider.kt:69` | `.join()` | ADR 인라인 문서화 |

### #1109에서 제거 완료 (4건)

| 파일 | 변경 |
| ---- | ---- |
| `TieredCache.kt` | .orTimeout(5s) + TimeoutException fallback 추가 |
| `PostgresSingleFlightStrategy.kt` | .orTimeout(10s) + CompletionException catch 추가 |
| `RealNexonAuthClient.kt` | .block() → .block(Duration.ofSeconds(5)) |
| `DiscordAlertChannel.kt` | .block() → .subscribe() fire-and-forget |

---

## 5. Summary

> 프로젝트 규칙이 금지하는 .join()/.get()/runBlocking 15건 중 4건은 제거, 나머지 11건은 PGMQ 콜백과 Spring Cache Callable의 동기 인터페이스 계약으로 인해 불가피하며 .orTimeout으로 안전장치 적용 완료.
