# ADR-739: morning_chain loop-started sensor 조건 수정 (iterationCount → status)

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: morning_chain_pipeline, ADR-738

---

## 1. Background / Problem

### Background

- `morning_chain_pipeline` step 4 = `trigger_item_equipment_infinite` (`mode=infinite`).
- infinite loop 는 terminal 에 도달하지 않으므로, factory `make_wait_phase_terminal_sensor` 로 gate 불가 → custom sensor `wait_first_iteration_started` 가 루프 시작을 확인.
- sensor 성공 조건(수정 전):
  ```python
  summary.get("status") == "RUNNING" and (summary.get("iterationCount") or 0) >= 1
  ```
- `timeout=10min`, `poke_interval=30s`.

### Problem

- `iterationCount` 는 iteration **완료** 시에만 증가 (`PhaseLoopController.handleIterationEnd`, "Submitted-but-not-finished iterations do not count").
- ITEM_EQUIPMENT 1 iteration = IGN ~560K 풀패스 = 실측 ~3735s (≈ **62분**, 150 files/s).
- 즉 `iterationCount >= 1` 도달에 ~62분 필요. sensor timeout 10분 → **항상 timeout 실패**.
- 2026-06-24 exec morning_chain run 이 이유로 failed (tail sensor 만, 본체 success).
- sensor 주석 intent 는 "at least one iteration has **begun**" 이나, 구현은 `iterationCount>=1` = **completed** 로 intent↔코드 불일치.

### Goal

- 루프가 시작 신호 수용 + 첫 iteration submit 한 사실을 timeout 내 감지.

---

## 2. Decision

> sensor 성공 조건을 `iterationCount >= 1`(완료) 제거, `status == "RUNNING"` 단일 조건으로. `LoopStatus.RUNNING` = "at least one iteration has been submitted; loop is active" (enum 정의) 와 정확히 일치.

```python
# before
return summary.get("status") == "RUNNING" and (summary.get("iterationCount") or 0) >= 1
# after
return summary.get("status") == "RUNNING"
```

근거:
- `startLoop` 는 상태 `RUNNING` 전환 + iteration 1 submit 을 동기적으로 수행. `activeLoops()` 가 RUNNING/STOPPING 포함 → `loopSummaries` 에 노출.
- 따라서 `loopSummaries[ITEM_EQUIPMENT].status == "RUNNING"` = 루프가 시작 신호 수용 + 첫 iteration submit 완료 = "begun".
- 첫 submit 은 trigger 후 수초 내 가시화 → 10분 timeout 안에 감지 가능.

---

## 3. Trade-offs

### Sensitivity

* sensor 가 brief RUNNING window 를 잡으면 loop 이 직후 실패해도 성공으로 기록 가능. 단 이는 sensor 역할(시작 확인) 범위 밖 — 실패는 별도 모니터링/다음 run 영역.
* `status==RUNNING` 은 startLoop 시점 설정. triggerPhase 동기 실패 시 STOPPING→STOPPED 로 빈환, activeLoops 에서 제외 → loopSummaries 에서 사라져 sensor 가 False 유지.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| status 단일 조건 | 첫 iteration submit 즉시(수초) 감지, 정상 동작 | "iteration 완전 실행 지속" 보장 불가 (별도 관측 필요) |

### Risk

* loop 시작 직후 즉사 시 false-positive success 가능. 운영 영향 최소 (다음 03:00 run + 모니터링).

### Non-Risk

* 조건 완화가 루프 자체 동작에 영향 없음 (sensor 는 감지 전용).
* 기존 terminal-based sensor (ranking/ocid/basic) 불변.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| sensor 성공 시점 | ~62min(불가) → 수초 | iterationCount>=1 → status==RUNNING |
| morning_chain tail sensor | 항상 timeout → 정상 감지 | 06-24 run 실패 회피 |

### Observed Result

* 코드/구문 검증: Python AST parse PASS, scheduler DAG reload 로 morning_chain import 정상.
* 런타임: 다음 morning_chain 발화(06-27 03:00 KST) 시 본 절 실측.

---

## 5. Summary

> morning_chain `wait_first_iteration_started` sensor 가 `iterationCount>=1`(iteration 완료, ~62분) 조건으로 10분 timeout 항상 실패하던 버그 수정. `status==RUNNING`(루프 active = 첫 iteration submit) 단일 조건으로 intent 일치시킴.
