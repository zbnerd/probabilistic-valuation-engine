# Endurance Test #2 — 71h (Terminated for Redeploy)

- **Status:** TERMINATED @ ~71h (150h target abandoned — 운영 설계 문제 발견, 수정 반영 위해 재배포)
- **Test Period:** 2026-06-23 09:03 KST ~ 2026-06-26 05:30 KST (~71h, T0 → stop)
- **Modules:** external-api (8081), calculator (8082), synchronizer (8083), cleanup (8084)
- **Deployment:** nohup `java -jar` (host processes, not docker)
- **Termination Reason:** daily-rollover orchestration bug reproduced 2× (06-25, 06-26 03:00 KST). 06-26 failure code-traced to **dual orchestration** root cause; fix authored (ADR-736). Continuing to 150h adds no further signal — test goal (infra stability + surfacing long-running operational bugs) already met.

> **Framing (정확한 표현).**
>
> 이번 상황은 다음이 **아니다**: ❌ JVM 크래시 · ❌ OOM · ❌ Kafka 장애 · ❌ PostgreSQL 장애 · ❌ 인프라 재시작
>
> 실제 발생한 것:
> - ⚠️ 03:00 KST daily rollover 때 **두 오케스트레이터가 동시 발화** → ITEM_EQUIPMENT phase slot race → loop 크래시
> - ⚠️ 최초 진단(06-25)은 "null upstream" 으로 추정했으나, **06-26 코드 추적 결과 실제 root cause = dual orchestration** (구버전 in-process `@Scheduled` cron + 신규 `morning_chain` DAG 가 둘 다 03:00 에 ITEM_EQUIPMENT 구동)
> - ✅ root cause 확정 + fix 작성 (ADR-736: 구버전 cron 제거, `morning_chain` 단일 오케스트레이터화)
>
> **인정 기준.** "150시간 연속 서비스 가동" — 인프라 안정성 관점에서는 **71h 동안 인프라 레벨 결함 0** (재시작·OOM·Kafka/DB 장애 전무). 단 "무개입·무변경 연속 가동" 기준에는 **미충족** (orchestration 버그로 03:00 마다 처리 중단 + 수동 loop restart 개입). 실무적으로 장기 검증 중 운영 버그가 하나도 안 나오는 경우는 드물며 — 이번 테스트의 가치는 **"장기 구동에서만 드러나는 daily-rollover orchestration 결함을 발견하고 root cause 를 확정한 것"**.

---

## 1. Endurance — Infrastructure Stability (SUCCESS, 71h)

### Core indicators

| Indicator | Status | Evidence |
|-----------|--------|----------|
| JVM restart | **0** | 4 modules continuous 71h since T0 |
| OOM | **0** | RSS flat (see §3) |
| Kafka lag | **0** | all consumer groups caught up between cycles |
| DB (PostgreSQL) | **stable** | HikariCP 0 active/idle healthy, 0 timeout, no exhaustion |
| Disk exhaustion | **0** | cleanup equilibrium, 279 GB free at 71h |
| Service crash | **0** | no module process died |
| Calculator errors | **0** | `calculator_items_errored_total = 0` 누적 2.28B items |

→ **인프라 안정성 검증 통과.** 71h 연속, 인프라 레벨 결함 0.

### Throughput (cumulative @ 71h)

| Metric | Value | Notes |
|--------|------:|-------|
| Users processed | 33.28M | calculator counter |
| Items calculated | 2.28B | 0 errored |
| Chunks processed | 75.62K | calculator (1.73K skipped) |
| Result rows | 2.28B | calculator |
| Ext-API item_equip fetched | 33.28M | failed 55.69K (0.17%) |
| Data volume (uncomp) | 10.09 TB snapshot / 10.09 TB calc input | compressed ~871 GB |
| Calculator cache hit-rate | 72.41 % | 1.65B hits / 630M misses |

---

## 2. Operational Issue — Daily-Rollover Dual Orchestration (ROOT CAUSE CONFIRMED)

### Symptom (reproduced 2×)

- **06-25 03:23 KST** — ITEM_EQUIPMENT loop (`loopId=2bdcb918`) crash. Observed error: `ITEM_EQUIPMENT requires upstreamRunId`. 최초 추정 = "OCID_LOOKUP upstream 일시 null".
- **06-26 03:00 KST** — `morning_chain` DAG failed. ITEM_EQUIPMENT loop (`loopId=29bc9e2a`) iter 1 crash: `lastError=ITEM_EQUIPMENT slot occupied`.

### Root cause (code-traced 06-26)

**두 오케스트레이터가 동일 03:00 KST 에 발화, 둘 다 ITEM_EQUIPMENT phase slot 획득 시도:**

| Orchestrator | Trigger | ITEM_EQUIPMENT mode |
|---|---|---|
| `ExternalApiScheduler.scheduledDailyRefresh()` — `@Scheduled(cron="0 0 3 * * *")` | in-process Spring cron (구버전) | **once** (`triggerDailyRefresh` L121, loopId=null) |
| `morning_chain_pipeline` DAG — `schedule="0 18 * * *"` (03:00 KST) | Airflow (신규, spec `2026-06-23-3am-pipeline-chain-design.md`) | **infinite** |

`morning_chain` 은 구버전 cron 을 대체하려 도입됐으나 **마이그레이션 미완료 — 구버전 `@Scheduled` 가 여전히 활성**.

### 06-26 03:00 KST 타임라인 (결정적 증거)

```
04:08:13  구버전 cron chain 이 ITEM_EQUIPMENT once-run 선점
          (runId=...6368341, loopId=null) — slot acquired
04:08:38  item_equipment_pipeline DAG 실행 (mode=infinite, branch 정상)
04:08:47  morning_chain → POST /loop/phase → startLoop loopId=29bc9e2a
          → iter 1 acquireSlot → "ITEM_EQUIPMENT slot occupied"
          → submitIteration catch → finalize(STOPPED) → loop 즉사
05:23:53  once-run 6368341 (1734 chunks) COMPLETED → 이후 loop 재시작 없음 → IDLE
          morning_chain.wait_first_iteration_started 센서 status==RUNNING 관측 실패 → DAG failed
```

- `item_equipment_pipeline` sub-DAG 자체는 정상 (mode=infinite, `trigger_loop_infinite` success). once-run 은 구버전 in-process cron 이 뿌림.
- 06-25 "null upstream" 도 동일 dual-orchestration 의 다른 발현으로 추정 (두 chain 이 OCID_LOOKUP slot/upstream 를 동시 리셋) — 06-26 코드 증거로 root cause 확정.

### Fix — ADR-736

> 구버전 in-process `@Scheduled` daily cron 제거. `morning_chain` DAG = 03:00 KST 유일 오케스트레이터.

- `ExternalApiScheduler`: `@Scheduled` annotation + `scheduledDailyRefresh()` 제거. `triggerDailyRefresh` 유지 (manual/airflow HTTP endpoint).
- `application.yml`: dead `schedule.daily-cron` 제거.
- 검증: compile clean + `ExternalApiSchedulerTest`/`InternalApiControllerTest` PASS. 런타임(다음 03:00 단일 발화)은 deploy 후 관측 예정.

---

## 3. Memory (RSS, 71h)

| Module | Port | 71h RSS | Max heap | Notes |
|--------|-----:|--------:|---------:|-------|
| external-api | 8081 | 1611 MB | 2 GB | peak 후 회복, leak 无 |
| calculator | 8082 | 1332 MB | 1 GB | 안정 |
| synchronizer | 8083 | 921 MB | 1 GB | 안정 |
| cleanup | 8084 | 801 MB | - | 안정 |

**Memory leak: Not observed.** 71h RSS flat, GC 회복 정상. heap used: ext 465MB / calc 736MB / sync 80MB.

---

## 4. Disk

| Checkpoint | Free | Notes |
|-----------|-----:|-------|
| ~33h | 263 GB | mid-cycle |
| ~56h | 266 GB | cleanup equilibrium |
| ~71h | 279 GB | 종료 시점, cleanup holding |

Cleanup equilibrium 유지. 압축 비율 snapshot ~11.5×, result ~24×.

---

## 5. Autonomous Scheduling (morning_chain) — 부분 검증

- `morning_chain_pipeline` DAG unpause 후 schedule `0 18 * * *` (03:00 KST) 정상 인식.
- 06-24 발화: success (ranking→ocid→char_basic→item-equipment loop 자동 시작까지 확인).
- 06-25 / 06-26 발화: **failed** — 본 리포트 §2 의 dual-orchestration slot race.
- 결론: morning_chain 자체는 동작하나 구버전 cron 충돌로 안정적 단독 운영 미확보 → ADR-736 로 해결.

---

## 6. Summary

| 항목 | 결과 |
|------|------|
| Total service uptime | **~71h** (150h target abandoned) |
| Service restarts (infra) | **0** |
| Memory leak | **Not observed** (RSS flat) |
| JVM crash / OOM | **0** |
| Kafka / PostgreSQL 장애 | **0** (lag 0, DB stable) |
| Operational incidents | **1 root cause** (daily-rollover dual orchestration, 2× 발현) |
| Root cause | 구버전 `@Scheduled` cron + `morning_chain` DAG 동시 03:00 발화 → slot race |
| Fix | ✅ ADR-736 (구버전 cron 제거, morning_chain 단일화) — code-verified, deploy 예정 |

> **결론.** 인프라 안정성(71h 무재시작·무 OOM·Kafka/DB/heap 안정) 검증 성공. 장기 구동에서만 드러나는 daily-rollover orchestration 결함을 발견하고 root cause 를 코드 수준에서 확정했으며 fix(ADR-736) 작성. "무개입 연속 150h" 기준엔 미충족이나, 테스트의 본 목적(인프라 검증 + 운영 버그 발견)은 달성. 150h 까지 연장은 동일 버그의 재현만 반복하므로 무의미 → 71h 종료.

---

## 7. Live Checkpoints

### Checkpoint @ 52h18m (2026-06-25 13:21 KST)

| 항목 | 값 |
|------|-----|
| Loop iteration | 200 (loopId cf0100fc, 무결점) |
| Calculator cumulative | 25.13M users · 1.69B items · 0 errors · 55.77K chunks |
| ext-api users_fetched | 26.81M (failed 65.28K = 0.24%) |
| Rate | ext-api 160 files/s · calc 259 users/s · 18.87K items/s |
| System CPU | 85.6–88.6 % (8 cores, load ~10) |
| Disk free | 247 GB |

### Checkpoint @ 71h (2026-06-26 05:30 KST, 종료 시점)

| 항목 | 값 |
|------|-----|
| Phase | IDLE (ext-api fetch 정지 — 06-26 03:00 dual-orchestration crash 여파, calc drain 중) |
| ext-api fetched | 35.55M (failed 65.45K = 0.18%) · item_equip 33.28M |
| calc cumulative | 33.28M users · **2.28B items** · **0 errors** · 75.62K chunks (1.73K skip) · 2.28B result rows |
| sync | pre_upsert 2.28B rows · 75.38K chunks (0 fail) · 99.39M docs |
| Volume (comp/uncomp) | snap 871GB/10.09TB · calc in 869GB/10.09TB · result 50.52GB/1.20TB · sync pre 50.37GB/1.20TB |
| Calculator cache | size 100K · hit 72.41 % · hits 1.65B · misses 630M |
| JVM heap (used/max) | ext 465MB/2GB · calc 736MB/1GB · sync 80MB/1GB |
| RSS | ext 1611 · calc 1332 · sync 921 · cleanup 801 MB |
| HikariCP | 전부 0 active, timeout 0 |
| Kafka LAG | 0 (caught up) |
| Disk free | 279 GB |
| DB rows | basic 672K · equip 2.01M · game_char 603K |
| Service restart | **0** |

**관찰 (71h):**
- 인프라 71h 무결점 (0 restart / 0 OOM / 0 calc error / heap flat / Kafka 0).
- processing 은 03:00 dual-orchestration crash 로 단절 — calc 가 버퍼 drain 중 items/s 24K 유지.
- bandwidth-bound 확인: 응답당 ~241KB × 130–160/s ≈ 250–290 Mbps 수신.
- **테스트 종료**: 같은 root cause 의 03:00 재현만 반복하므로 150h 연장 무의미. fix(ADR-736) 적용 후 별도 clean run 으로 재검증 권장.

---

## 8. Engineering Narrative — Test #2 → Test #3

**종료 사유는 "테스트 실패"가 아니라 "장기 테스트로 운영상 설계 문제를 발견했고, 수정 반영에 재배포가 필요해서" 다.**

수정 내용(ADR-736)은 기능 추가가 아닌 **운영 아키텍처 수정**:
- `@Scheduled` 제거
- legacy `daily-cron` 제거
- `morning_chain` 을 유일 03:00 오케스트레이터로 변경
- 03:00 race condition 제거

→ 이걸 반영 않고 150h 까지 가면 "수정할 걸 알면서 옛 버전 계속 돌린 테스트" 가 됨. 그래서 종료가 정답.

### Test #2 (본 리포트, ~71h) — 달성

| 검증 항목 | 결과 |
|---|---|
| Memory leak | ✅ 없음 (RSS flat 71h) |
| JVM 안정 | ✅ 0 crash / 0 OOM |
| Kafka 안정 | ✅ lag 0 |
| PostgreSQL 안정 | ✅ Hikari 0 timeout |
| RSS 안정 | ✅ flat |
| **장기 운영 중 스케줄링 race 발견** | ✅ 03:00 dual-orchestration (배포 전 포착) |
| Root cause 코드 확정 + fix 검증 | ✅ ADR-736, compile + test PASS |

→ 테스트 목적(인프라 검증 + 운영 버그 발견) 충분히 달성. race 를 **배포 전에** 잡았으므로 장기적으로 이득.

### Test #3 (next, 수정 버전) — 계획

- ADR-736 deploy 후 **clean run**, 목표 100~150h.
- 검증: `morning_chain` 단독 orchestration 정상, 03:00 race 재발 여부(단일 발화 + loop 정상 시작), 다음 날 03:00 자동 발화 연속 성공.
- 실제 운영 사이클 동일: **테스트 → 문제 발견 → 수정 → 재장기검증**.

