# ADR-741: app 모듈 log retention 증설 (loop lifecycle 진단 가능화)

- Status: Accepted
- Date: 2026-06-27
- Owner: maple-pipeline
- Related: PhaseLoopController, ADR-740

---

## 1. Background / Problem

### Background

- 4 app module (external-api/calculator/synchronizer/cleanup) container log config = docker daemon default (`json-file`, `max-size=10m`, `max-file=3` = **30m cap**).
- external-api item-equipment phase 가 수초마다 progress 로그 → 30m 빠르게 rotate.
- PhaseLoopController infinite loop lifecycle 로그(`[Loop] startLoop`/`iteration done`/`iteration failed`/`stopped`)가 장시간 running loop 에서 rotate 아웃.

### Problem

- morning_chain infinite loop 가 1패스 후 사망(지속 안 됨) 원인 조사 시, `[Loop]` lifecycle 로그가 전부 rotate 되어 사망 사유(iteration 실패 / 외부 stop / terminal-chain 예외) 관측 불가.
- 실측: ext-api 77K 라인 중 "loop" 키워드 0건 (rotate 로 소거).
- 정적 분석 결과 continuation 코드는 정상 → 런타임 사망 사유 로그가 진단의 유일한 단서인데, log rotation 이 차단.

### Goal

- loop lifecycle 로그가 최소 수일간 persistence → 사망 사유 포착 가능.

---

## 2. Decision

> 4 app module 에 `logging` block 추가: `max-size=50m`, `max-file=10` (= 500m/container). daemon default(30m) 대체.

```yaml
logging:
  driver: json-file
  options:
    max-size: "50m"
    max-file: "10"
```

근거:
- ext-api item-equipment 활동 로그 ~50MB/일 추정 → 500m = ~10일 retention. loop 사망 사유(수시간 내 발생) 충분 포착.
- 디스크 여유 237G → 4 container × 500m = 2GB 부담 미미.
- 4 module 동일 적용(observability 일관). infra(postgres/kafka 등)는 base compose 유지, 본 변경은 app module 에 한정.

---

## 3. Trade-offs

### Sensitivity

* 디스크 사용량: container 당 500m 상한. 4 module = 2GB. (여유 237G 대비 미미)
* container recreate 필요 (logging option 은 생성 시점 적용). loop 이미 사망 상태라 현재 state 손실 없음.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| 500m retention | loop lifecycle 수일간 보존, 진단 가능 | 디스크 2GB (미미) |

### Risk

* 없음. json-file driver 유지, retention 만 증설.

### Non-Risk

* 앱 동작·성능 — logging 은 rotation 정책만 변경.
* 로그 형식 — 동일(json-file).

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| ext-api retention | 30m → 500m | ~17x |
| loop lifecycle 가시성 | 불가 → 수일 | rotate 내성 |

### Observed Result

* 구문: compose config PASS.
* 런타임: container recreate 후 `docker inspect` LogConfig max-size=50m/max-file=10 확인.
* 진단: 다음 03:00 KST morning_chain 발화 시 `[Loop]` lifecycle 지속 보존 → 사망 사유 포착 예정.

---

## 5. Summary

> loop 사망 원인 진단을 가로막던 30m log rotation 을 4 app module 에 500m logging block 추가로 증설. infinite loop lifecycle 로그 수일간 보존, 다음 발화 시 사망 사유 관측 가능.
