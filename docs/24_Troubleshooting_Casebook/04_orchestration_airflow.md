# 04. 오케스트레이션·Airflow

> Airflow control plane 도입 과정의 함정: HttpOperator 4xx 계약, task timeout, connection 미등록,
> in-process cron vs DAG slot race, legacy DAG 중복. control plane 이전 후 legacy 가 잔존하면 매일 fail 하는 패턴.

**영향(Impact):** Airflow HttpOperator 가 409 CONFLICT 에 도달 불가 → 멱등 트리거 2h×3 scheduled run fail; in-process cron 과 morning_chain DAG 가 동일 03:00 KST slot 경쟁 → ITEM_EQUIPMENT loop 사망 + DAG 매일 fail.

---

## 4-1. Airflow `HttpOperator.response_check` 가 409 에 도달 불가 — 멱등 트리거 2h 재시도 fail

- **Session:** 20260613-053448-2564568, 20260613-165731-3106593, 20260614-075928-3864474 (+ 0614-124902)
- **문제/에러:** `daily_collection_pipeline.trigger_daily_collection` 매 재시도 `AirflowException: 409:`. DAG 는 409 CONFLICT(이미 run 활성) 를 멱등 success 로 처리 설계 — but `response_check` callback 미실행. 3 scheduled run(06-12, 06-13) 120 재시도 × 60s = 2h fail.
- **원인:** `HttpHook.run()` → `run_and_check()` → `check_response()` → `response.raise_for_status()` 가 operator 의 `response_check` invoke *전* 4xx 에 `AirflowException` raise. 409 수용 custom `is_accepted_response` 전부 dead code.
- **해결:** `HttpOperator` → `PythonOperator` + `requests.post()` 직접 전환 — 4xx flow 를 우리 code 가 제어(200/202 → runId via xcom; 409 → status 의 active run; else raise). poll task → `PythonSensor(mode="reschedule")`(4h 대기 중 worker slot 해방). hardcode `host.docker.internal` → `BaseHook.get_connection("external_api")`. per-task retry budget(DAG-wide `retries=120` 대신). commit `05e0939a3` (#1284/#1286). ADR-726.
- **왜 이 방법 / 대안:** `PythonOperator` 가 4xx branch 도달 유일 경로 — hook `raise_for_status` disable 플래그 무. trade-off: HttpOperator retry/timeout/logging template 상실(inline 재구현). **기각:** blanket infinite timeout(실제 hang mask).

---

## 4-2. Airflow task timeout < 실제 pipeline runtime — 실행 중 SIGTERM

- **Session:** 20260613-053448-2564568, 20260614-075928-3864474
- **문제/에러:** 정상 daily pipeline(ranking 4m → ocid 25m → char-basic 40m → item-equipment 35m ≈ 1.75h)이 runId guard fix 후에도 DAG FAILED — sensor 가 2h `execution_timeout` 에 재시도 고갈.
- **원인:** timeout budget(2h)이 Nexon API rate-limit 지연/GC pause spike 여유 무; runId fix 는 정확했으나 DAG 가 여전히 timeout.
- **해결:** `wait_for_completion` 2h→4h, `wait_ie_cycle` 1h→2h 인상. commit `3043ba80a` (#1282).
- **왜 이 방법 / 대안:** 측정 runtime 대비 여유 budget — 단일 operator 프로젝트는 fast-fail 보다 Nexon 변동 흡수 선호. ADR-726 이 DAG-wide retry 대체 per-task retry-budget policy 통할.

---

## 4-3. Airflow cleanup DAG connection 미등록 — DAG 등록 단계 fail

- **Session:** 20260617-053345-4091260 (+ 20260617-084647)
- **문제/에러:** cleanup DAG 가 필수 Airflow Connection 미등록으로 fail; downstream cleanup task 가 ext-api endpoint 미해석.
- **원인:** connection provisioning 이 DAG bootstrap 외부; `BaseHook.get_connection`(ADR-726) 마이그레이션이 hardcode host 가 숨기던 missing-connection failure mode 노출.
- **해결:** `external_api` Connection 멱등 등록 script(delete-first 중복 방지 → add). commit `4b5944b31` (#1297). 관련 `6a081b12e`: prometheus+airflow host network 전환(docker-bridge hairpin bypass).
- **왜 이 방법 / 대안:** 멱등 ensure-script 를 수동 `airflow connections` CLI 대신 — redeploy 생존. host-network bypass 를 DNS/bridge 디버그 대신(bridge hairpin 은 알려진 docker 제약; ADR-738 port-publish 정책화). **기각:** `host.docker.internal` 의존(host 간 불안정).

---

## 4-4. in-process daily cron vs morning_chain DAG 동시 발화 — ITEM_EQUIPMENT slot race

- **Session:** 20260626-010319-1175180
- **문제/에러:** 06-26 03:00 KST 두 오케스트레이터 동시 발화. `ExternalApiScheduler.scheduledDailyRefresh()`(`@Scheduled` cron)가 ITEM_EQUIPMENT once-run 으로 slot 선점 후, morning_chain `trigger_loop_infinite` → `acquirePhaseSlot` `IllegalStateException("ITEM_EQUIPMENT slot occupied")` → `submitIteration` catch → `finalize(STOPPED)` → loop 사망 + morning_chain sensor 실패 → DAG failed.
- **원인:** 신규 `morning_chain_pipeline` DAG 도입 후에도 구버전 in-process `@Scheduled(cron="0 0 3 * * *")` 활성 잔존. 두 경로 동일 03:00 KST·동일 phase slot 경쟁.
- **해결:** `@Scheduled scheduledDailyRefresh()` method + `external-api.schedule.daily-cron` YAML config 제거. 수동 트리거용 `triggerDailyRefresh(airflowRunId)` endpoint 유지. morning_chain DAG 를 유일 오케스트레이터 확정. **ADR-736**, commit `85229a4e7` (#1433).
- **왜 이 방법 / 대안:** in-process cron 이 사실상 Airflow backup fallback 이었으나 이미 control plane 으로 Airflow 채택 → net-new SPOF risk 아님. **기각:** 두 오케스트레이터 유지 + phase slot 분리(경쟁 회피 비용·복잡도 상습 증가).

---

## 4-5. `daily_full_pipeline` vs `morning_chain` 동일 cron — legacy 중복 매일 전체 fail

- **Session:** 20260627-150059-3412697
- **문제/에러:** `daily_full_pipeline`(`0 18 * * *`)과 `morning_chain_pipeline`(동일 cron)이 같은 phase slot 경쟁. morning_chain 선점 → daily_full `trigger_character_basic` 매일 timeout, item_equipment/cleanup `upstream_failed`. 06-25, 06-26 연속 daily_full 전체 fail.
- **원인:** daily_full 은 morning_chain(ocid 추가 + infinite item-equipment loop = 상위 호환)의 중복 legacy. 같은 03:00 KST cron slot 경쟁.
- **해결:** `daily_full_pipeline` schedule 을 `None` 으로 퇴역(repo 컨벤션, `daily_collection` 동일 패턴). DEPRECATED docstring 로 morning_chain 정규 후계 DAG 명시. 정의 보존(수동 trigger 가능). cleanup 은 `daily_cleanup_pipeline`(`0 */6 * * *`) 독자 스케줄로 무관. **ADR-740**, commit `1ec76cc4b` (#1442).
- **왜 이 방법 / 대안:** 역참조 없음(어느 DAG 도 daily_full 미 trigger). `schedule=None`(정의 보존)은 reversible + repo 컨벤션 일관. **기각:** 파일 완전 삭제(수동 finite once-mode 풀체인 fallback 상실 위험).

---

## 4-6. airflow metadata DB 단결 — host-network scheduler 의 `airflow-db` DNS 미해석

- **Session:** 20260626-013345-1258594
- **문제/에러:** host-network 구동 airflow scheduler/webserver 가 bridge 망 `airflow-db` Docker DNS 해석 불가 → `socket.gaierror` → metadata DB 단절 → scheduler "No alive jobs found" unhealthy. airflow autoheal 미가동.
- **원인:** compose 는 bridge 선언이나 runtime host override. `SQL_ALCHEMY_CONN` `@airflow-db:5432`(Docker DNS)인데 host-network 프로세스는 bridge DNS 접근 불가.
- **해결:** host-network 유지 + airflow-db `5433:5432` port publish(5432 는 maple-postgres 점유, 5433 실측 FREE). `SQL_ALCHEMY_CONN` `@localhost:5433` 변경. compose `network_mode: host` 명시 + autoheal label + scheduler healthcheck `start_period: 120s`. 검증: scheduler `SELECT count(*) FROM dag`=8(단결→해결), app `/actuator/health`=200. **ADR-738**, commits `2022abcf8`/`37d4e106a`/`2601122c1` (#1435/#1437).
- **왜 이 방법 / 대안:** bridge 통일(최초 구상)은 grill(critic opus) 검증에서 2 BLOCKER — loki/grafana/promtail strand + connection-recreate race. host 유지 + port publish 가 app connections·DAG 코드 불변(Simplicity First), 관측 스택 이동 無. bridge 통일 기각.

---

## 4-7. scheduler healthcheck single-quote 버그 — `'$$(hostname -f)'` 항상 false-unhealthy

- **Session:** 20260626-013345-1258594
- **문제/에러:** airflow scheduler 배포 후 계속 unhealthy. healthcheck `airflow jobs check --job-type SchedulerJob --hostname '$$(hostname -f)'` 항상 매칭 실패.
- **원인:** CMD-SHELL healthcheck 에서 `'$$(hostname -f)'` single-quote 가 sh expansion 차단 → `$(hostname -f)` 가 리터럴 문자열 비교 → 실제 FQDN 불일치 → 항상 false-unhealthy. ADR-738 작업 중 잠재 bug 발견.
- **해결:** single-quote 제거 → `$$(hostname -f)` 로 sh 가 FQDN 확장. 45s 내 `healthy` 전환. commit `2601122c1` (#1437).
- **왜 이 방법 / 대안:** 1문자 변경으로 sh 확장 복원. 동시 `start_period: 120s` 추가(autoheal 기동 중 restart-loop 방지; 첫 unhealthy 감지 120s 지연을 자동복구 안정성과 교환).
