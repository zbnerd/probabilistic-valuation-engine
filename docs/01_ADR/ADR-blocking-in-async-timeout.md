# ADR: Blocking-in-async 타임아웃 및 비동기 전환

- Status: Accepted
- Date: 2026-06-04
- Owner: zbnerd

---

## 1. Background / Problem

### Background

- 비동기 코드 4곳에서 동기 blocking 발생 (.join(), .block())
- 무한 대기로 인한 스레드 고갈 위험
- 에러 핸들링 경로 블로킹으로 알림 지연

### Problem

- TieredCache: `buffer.submit(key).join()` 무한 대기
- PostgresSingleFlightStrategy: `executeAsync(...).join()` 무한 대기
- RealNexonAuthClient: WebClient `.block()` 무한 대기
- DiscordAlertChannel: WebClient `.block()` 동기 대기

### Goal

- 각 위치에 적절한 타임아웃 또는 비동기 전환으로 무한 대기 방지

---

## 2. Decision

> Discord: fire-and-forget subscribe(). 나머지: 타임아웃 추가.

```text
DiscordAlertChannel: .block() → .subscribe() (fire-and-forget)
PostgresSingleFlightStrategy: .join() → .orTimeout(10s).join()
RealNexonAuthClient: .block() → .block(Duration(5s))
TieredCache: .join() → .orTimeout(5s).join() + fallback
```

---

## 3. Trade-offs

### Sensitivity

* 외부 API 응답 시간 (Nexon, Discord webhook)
* L2 캐시 batch buffer flush 주기
* SingleFlight 리더 실행 시간

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| Discord subscribe() | non-blocking, 스레드 해제 | 전송 성공/실패 즉시 확인 불가 |
| TieredCache orTimeout | 무한 대기 방지 + fallback | 타임아웃 내 미처리 시 direct L2 조회 |
| SingleFlight orTimeout | 무한 대기 방지 | 타임아웃 시 예외 전파 |
| RealNexonAuthClient block(Duration) | 무한 대기 방지 | 여전히 blocking (호출자 동기 제약) |

### Risk

* TieredCache fallback 시 batch 이점 상실 (개별 L2 조회)
* SingleFlight 타임아웃 10초가 짧을 수 있음 (외부 API 지연 시)

### Non-Risk

* Discord subscribe() 콜백 기반 에러 로깅으로 관측성 유지
* Spring Cache/SingleFlight 인터페이스 변경 없음

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| TieredCache timeout | 5s | batch buffer 타임아웃 |
| SingleFlight timeout | 10s | DEFAULT_TIMEOUT 정렬 |
| RealNexonAuthClient timeout | 5s | WebClient block 타임아웃 |

### Observed Result

* 컴파일 통과, 기존 테스트 통과 확인

---

## 5. Summary

> Discord fire-and-forget + 나머지 3곳 타임아웃으로 무한 대기 방지. Spring Cache/SingleFlight 동기 API 제약으로 .join() 잔존 — ADR 정당화.
