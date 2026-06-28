# ADR-742: PhaseLoopController iteration 업스트림 부재 시 defer/retry (루프 사망 방지)

- Status: Accepted
- Date: 2026-06-28
- Owner: maple-pipeline
- Related: PhaseLoopController, ADR-739, ADR-741

---

## 1. Background / Problem

### Background

- `morning_chain` ITEM_EQUIPMENT infinite loop 는 매 iteration 마다 `latestUpstreamRunId` = `getLastCompletedForPhase(OCID_LOOKUP)?.runId` 로 업스트림을 읽어 `runItemEquipmentPhase` 에 전달.
- `runItemEquipmentPhase` line 267: `require(upstreamRunId != null) { "ITEM_EQUIPMENT requires upstreamRunId" }`.

### Problem

- 매일 03:00 KST `morning_chain` 가 OCID_LOOKUP 을 refresh 하는 동안, OCID_LOOKUP slot 이 non-terminal(in-progress) → `getLastCompletedForPhase(OCID_LOOKUP)` = **null**.
- 이 창에 loop 의 다음 iteration 이 `submitIteration` → `triggerPhase(ITEM_EQUIPMENT, runId, null, loopId)` → `require` throw `IllegalArgumentException`.
- `submitIteration` catch block 이 이를 fatal 로 처리 → `status=STOPPING` → `finalize` → **루프 사망**.
- 실측(500m log, ADR-741): `iter=274` 에서 `[Loop] iteration submit failed: ITEM_EQUIPMENT requires upstreamRunId` → `[Loop] stopped iterations=273`. 273 iteration 정상 후 매일 아침 사망.

### Goal

- loop 가 upstream(OCID_LOOKUP) transient 부재 시 **사망 대신 대기/재시도**. upstream 이 terminal-completed 로 복귀하면 iteration 재개.

---

## 2. Decision

> `submitIteration` catch block 에서, throw 원인이 `upstream == null` 이면 finalize 대신 **backoff 후 재submit** (defer). loop 상태 RUNNING 유지.

```text
submitIteration(phase, loopId, runId, n):
  upstream = latestUpstreamRunId(phase)
  try triggerPhase(phase, runId, upstream, loopId).whenComplete{...}
  catch ex:
    if upstream == null && loop still RUNNING:
        log.info("[Loop] upstream not ready — deferring retry in {N}s")
        loopExecutor.execute { sleep(N); submitIteration(phase, loopId, runId, n) }
        return                         // 사망 방지
    // upstream non-null 인 진짜 실패 → 기존 fatal 경로 유지
    log.error("[Loop] iteration submit failed ..."); STOPPING; finalize
```

근거:
- `require` 가 triggerPhase 내부에서 **slot acquire 이전**에 throw 하므로, 실패 시 slot 미점유 상태 → 동일 runId 로 안전 재submit.
- 판별 조건 `upstream == null` 로 "업스트림 부재" 와 "진짜 submit 실패" 명확 분리. upstream non-null 실패는 기존대로 사망 유지(과잉 재시도 방지).
- backoff = `external-api.loop.upstream-retry-interval-seconds` (기본 30s, YAML 외부화). OCID_LOOKUP refresh 가 terminal 되면 다음 retry 에 iteration 정상 진행.
- loopExecutor 가 virtual-thread → retry sleep 이 carrier pinning 없이 대기(architecture-guardrails §9-10).

---

## 3. Trade-offs

### Sensitivity

* upstream 이 장기 부재 시 loop 가 `upstreamRetryIntervalSeconds` 마다 retry 하며 RUNNING 유지. 30s 마다 info-log 1건 → 노이즈 가능(수용; 사망보다 양호).
* morning_chain stop_loop 가 STOPPING 전환하면, defer-resubmit 의 `status == RUNNING` guard 가 추가 재submit 차단 → 정상 종료.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| defer/retry (upstream null) | 일일 loop 사망 제거, upstream 복귀 시 자동 재개 | 장기 부재 시 무한 대기 + 주기적 로그 |

### Risk

* defer 중 loop 교체/stop 시 guard(`loopId`/`status`) 누락되면 stale retry 가능 → guard 로 차단(구현).

### Non-Risk

* `runItemEquipmentPhase` require(precondition guard) — 유지. loop controller 가 null 전달을 defer 로 흡수.
* non-null upstream 실패 경로 — 기존 fatal 유지.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| 일일 loop 사망(03:00KST) | 매일 → 0 (예상) | upstream defer 로 회피 |
| iteration submit failed(사망) | 발생 → defer | catch block 분기 |

### Observed Result

* 단위 테스트: `loop defers iteration when upstream not ready` — triggerPhase throw 시 루프 RUNNING 유지, iterationCount=0, lastError=null 검증.
* 코드 검증: compileKotlin PASS.
* 런타임: 다음 03:00 KST morning_chain OCID_LOOKUP refresh 창에 `[Loop] upstream not ready — deferring` 로그 + 루프 사망 없이 유지 관측 예정.

---

## 5. Summary

> morning_chain OCID_LOOKUP refresh 창에 loop iteration 이 null upstream 으로 throw → 사망하던 버그 수정. catch block 에서 `upstream == null` 시 backoff retry(defer) 로 루프 RUNNING 유지, non-null 실패는 기존 fatal 유지.
