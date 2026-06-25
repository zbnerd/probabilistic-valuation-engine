# ADR-736: Disable Legacy In-Process Daily Cron

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline

---

## 1. Background / Problem

### Background

- 03:00 KST 신규 오케스트레이터 `morning_chain_pipeline` DAG 가 도입됨
  (spec `2026-06-23-3am-pipeline-chain-design.md`). stop_loop → ranking_ocid →
  character_basic_once → **item_equipment_infinite** 순서로 단일 체인 구동.
- 구버전 오케스트레이터 `ExternalApiScheduler.scheduledDailyRefresh()`
  (`@Scheduled(cron = "0 0 3 * * *")`) 가 여전히 활성. RANKING → OCID →
  CHARACTER_BASIC → **ITEM_EQUIPMENT once** 4-phase 체인을 in-process 로 구동.

### Problem

- 두 오케스트레이터가 **동일 03:00 KST** 에 발화 → 둘 다 ITEM_EQUIPMENT phase slot 획득 시도.
- `triggerPhase` → `RunStatusTracker.acquirePhaseSlot` 은 non-terminal run 점유 시
  `IllegalStateException("<PHASE> slot occupied")` 발생.
- 06-26 03:00 KST 실측 타임라인:
  - 04:08:13 구버전 cron chain 이 ITEM_EQUIPMENT once-run(`runId=...6368341`, loopId=null) 로 slot 선점.
  - 04:08:47 `morning_chain` 의 `trigger_loop_infinite` → `PhaseLoopController.startLoop` → iter 1 `acquireSlot` → **"slot occupied"** → `submitIteration` catch → `finalize(STOPPED)`.
  - once-run 은 1734 chunk 처리 후 05:23:53 COMPLETED. 이후 loop 재시작 없음 → 파이프라인 IDLE.
  - `morning_chain.wait_first_iteration_started` 센서가 `status==RUNNING` 을 못 봄 → 태스크 실패 → DAG failed.

### Goal

- 03:00 KST ITEM_EQUIPMENT 구동 주체를 단일화 → slot race 제거.
- `morning_chain` DAG 를 유일 오케스트레이터로 확정.

---

## 2. Decision

> 구버전 in-process `@Scheduled` daily cron 을 제거한다. `morning_chain` DAG 가 03:00 KST 유일 오케스트레이터.

```text
ExternalApiScheduler:
  - @Scheduled(cron="${external-api.schedule.daily-cron}") scheduledDailyRefresh()  ← 삭제
  - triggerDailyRefresh(airflowRunId)                                                 ← 유지 (manual/airflow HTTP endpoint 사용)
application.yml:
  - external-api.schedule.daily-cron                                                  ← 삭제 (annotation 과 함께 dead config)
```

`triggerDailyRefresh` 는 `InternalApiController` 의 수동 트리거 엔드포인트가 사용하므로 유지. 자동 03:00 발화만 제거.

---

## 3. Trade-offs

### Sensitivity

* Airflow scheduler 가동 여부 (Airflow down 시 03:00 자동 발화 없음)
* `morning_chain` DAG pause 상태 (paused 시 발화 누락 — 06-25 사전 사고와 동일 원인)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| in-process cron 제거 | slot race 영구 제거, 오케스트레이션 단일화 | Airflow 장애 시 자동 fallback 없음 (in-process cron 이 백업 역할이었음) |

### Risk

* Airflow 단일 장애점(SPOF). 단 이미 control plane 으로 Airflow 를 채택한 상태이므로 net-new risk 아님.

### Non-Risk

* 수동 daily 트리거 (`triggerDailyRefresh`) 유지 → 운영자 ad-hoc 구동 가능.
* `run-on-startup` 경로(`onStartup`) 무관 — 그대로 유지.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| 03:00 KST dual-fire | 1 → 0 | cron 제거로 단일화 |
| ITEM_EQUIPMENT slot race | 재현 → 제거 예상 | 다음 03:00 관측으로 확정 예정 |

### Observed Result

* 코드 검증: `@Scheduled` annotation + `scheduledDailyRefresh()` 메서드 제거, compile + 기존 `ExternalApiSchedulerTest` 통과.
* 런타임 검증: **deferred** — 본 변경이 03:00 자동 발화 제거이므로, 다음 03:00 KST(2026-06-27) morning_chain 단독 발화 + loop 정상 시작 관측으로 최종 확정. endurance test running 중이라 bootRun 런타임 검증은 보류.

---

## 5. Summary

> 03:00 KST dual orchestration(slot race) 해결을 위해 구버전 in-process `@Scheduled` daily cron 제거, `morning_chain` DAG 를 유일 오케스트레이터로 확정.
