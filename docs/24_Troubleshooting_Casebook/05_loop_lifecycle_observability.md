# 05. 무한루프 생명주기·관측성

> `morning_chain` 의 ITEM_EQUIPMENT infinite loop 가 매일 사망하던 연쇄 사건.
> 3개 사례(05-3 → 05-2 → 05-1)가 인과로 연결된 한 사가(episode). log retention(05-3) 없이는 05-1 진단 불가.

---

## 5-1. Loop 사망 — `ITEM_EQUIPMENT requires upstreamRunId` (OCID_LOOKUP refresh 창)

- **Session:** 20260628-071021-1933904 (진단은 20260627-150059-3412697 의 500m log 관찰로 확보)
- **문제/에러:** 매 03:00 KST `morning_chain` infinite loop 가 273 iteration 정상 후 사망. 500m-retention 로그(ADR-741 도입 후 첫 포착) 실측: `iter=274` 에서 `[Loop] iteration submit failed: ITEM_EQUIPMENT requires upstreamRunId` → `[Loop] stopped iterations=273`. ITEM_EQUIPMENT 매일 아침 사망 → 지속 구동 안 됨. (사용자 프롬프트: "루프가 왜안돌지")
- **원인:** 03:00 KST morning_chain 가 OCID_LOOKUP refresh 중 OCID_LOOKUP slot non-terminal → `getLastCompletedForPhase(OCID_LOOKUP)` = null. 이 창에 다음 iteration `triggerPhase(ITEM_EQUIPMENT, runId, null, loopId)` → `runItemEquipmentPhase` 의 `require(upstreamRunId != null)` 가 slot acquire *이전* throw. `submitIteration` catch block 이 fatal(STOPPING→finalize) 처리.
- **해결:** `submitIteration` catch 분기 — throw 원인이 `upstream == null` & loop 아직 RUNNING 이면 finalize 대신 backoff 후 re-submit(defer). backoff = `external-api.loop.upstream-retry-interval-seconds`(기본 30s, YAML 외부화). non-null upstream 실패는 기존 fatal 경로 유지. `PhaseLoopControllerTest.loop defers iteration when upstream not ready` 추가. **ADR-742**, commit `3d1264d43` (#1444).
- **왜 이 방법 / 대안:** `require` 가 slot acquire 이전 throw 라 실패 시 slot 미점유 → 동일 runId 안전 재submit. 분기 `upstream == null` 로 transient 업스트림 부재와 진짜 submit 실패 분리(후자 defer 시 과잉 재시도). loopExecutor virtual-thread → backoff sleep carrier pinning 無(architecture-guardrails §9-10). **기각:** OCID_LOOKUP refresh 시 loop 를 외부에서 pause(coupling 증가·morning_chain stop_loop 와 상태 충돌).

---

## 5-2. morning_chain loop-started sensor 항상 10분 timeout (`iterationCount>=1`)

- **Session:** 20260626-110222-2915509
- **문제/에러:** 2026-06-24 morning_chain run 본체 success 임에도 tail sensor `wait_first_iteration_started` 만 failed. sensor 성공 조건 `status==RUNNING and iterationCount>=1`, `timeout=10min`. ITEM_EQUIPMENT 1 iteration = IGN ~560K 풀패스 = 실측 ~3735s(≈62분, 150 files/s) → `iterationCount>=1` 도달 전 timeout.
- **원인:** `iterationCount` 는 iteration *완료* 시에만 증가(`PhaseLoopController.handleIterationEnd`). sensor 주석 intent 는 "iteration begun" 이나 구현은 "completed" → intent↔코드 불일치.
- **해결:** `iterationCount>=1` 조건 제거, `status == "RUNNING"` 단일 조건. `LoopStatus.RUNNING` enum 정의("at least one iteration submitted; loop active")가 `startLoop` 동기 iteration 1 submit 시 전환 → trigger 후 수초 내 가시화. **ADR-739**, commit `dd992209c` (#1441).
- **왜 이 방법 / 대안:** loop 시작 직후 실패 시 false-positive 가능성 수용 — sensor 역할(시작 확인) 범위 밖, 실패는 별도 모니터링·다음 run 영역. **기각:** timeout 연장(62분 분기당 1회인 DAG 에 비효율, 근본 intent 불일치 해소 못함).

---

## 5-3. infinite-loop lifecycle 로그 rotate-out — 사망 원인 진단 불가 (관측성 회귀)

- **Session:** 20260627-150059-3412697
- **문제/에러:** morning_chain infinite loop 가 1패스 후 사망(지속 안 됨) 원인 조사 시 `[Loop] startLoop`/`iteration done/failed/stopped` lifecycle 로그가 전부 rotate-out → 사망 사유 관측 불가. 실측: ext-api 77K 라인 중 "loop" 키워드 **0건**. 정적 분석상 continuation 코드 정상이라 런타임 로그가 유일한 단서.
- **원인:** docker daemon default log config(`json-file`, `max-size=10m`, `max-file=3` = 30m cap). item-equipment phase 가 수초마다 progress 로그 → 30m 빠르게 rotate → 장시간 loop lifecycle 로그 소거.
- **해결:** 4 app module compose 에 `logging` block 추가: `max-size=50m`, `max-file=10`(= 500m/container, ~10일 retention). infra 는 base compose 유지. **ADR-741**, commit `a23aeac67` (#1443). 이 500m retention 가 ADR-742(05-1) 의 iter=274 stacktrace 포착 가능케 함 — 두 ADR 인과 연결.
- **왜 이 방법 / 대안:** 디스크 여유 237G 대비 4×500m=2GB 미미. ext-api 활동 로그 ~50MB/일 추정 10일 보존. json-file driver 유지(rotation 정책만 변경)로 로그 형식·앱 동작 영향 無. container recreate 필요하나 loop 이미 사망 상태라 state 손실 無.

---

## 에피소드 요약: 한 사건의 3단계

```
05-3 (log rotate)  →  관측 불가 상태로 loop 사망 원인 은폐
       │
       ▼  (ADR-741: 500m retention)
05-2 (sensor)      →  morning_chain 본체 success 조차 DAG fail (tail sensor)
       │              (ADR-739: iterationCount→status) — 별개 버그, 같은 run 노이즈
       ▼
05-1 (loop death)  →  진짜 원인 포착: OCID_LOOKUP refresh 창 upstream null → 사망
                      (ADR-742: catch block defer) — 근본 fix
```

**교훈:** 관측성 회귀(05-3)가 먼저 해결되어야 진짜 버그(05-1)가 보인다.
"코드는 정상인데 죽는다" = 로그가 증거를 숨기고 있을 가능성 먼저 의심.
