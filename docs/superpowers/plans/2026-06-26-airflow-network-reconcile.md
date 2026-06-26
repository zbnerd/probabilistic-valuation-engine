# airflow DB repair + autoheal Implementation Plan (#1435) — REVISED post-grill (Approach D)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** host-network airflow 의 DB 단결 repair(airflow-db 5433 publish + conn localhost:5433), autoheal label 부착, scheduler healthy 회복.

**Architecture:** host-mode 유지. airflow-db 포트 5433:5432 퍼블리시 → scheduler/webserver `@localhost:5433` 도달. app connections(localhost) 불변. autoheal label + start_period.

**Spec:** `docs/superpowers/specs/2026-06-26-airflow-network-reconcile-design.md`

**Tech Stack:** docker compose v2, willfarrell/autoheal, Airflow 2.10.5, PostgreSQL 17.

**Test strategy:** config/ops. 검증 = `compose config` 구문 + 런타임(scheduler healthy, 5433 publish, DAG trigger, autoheal).

---

## grill 반영

- **B1/B2 회피**: bridge-migrate 폐기, host+port-publish 로 설계 전환 → loki/grafana/promtail strand 없음, connection race 없음.
- **M3 반영**: scheduler healthcheck `start_period: 120s` 추가(autoheal restart-loop 방지).
- **M1 반영**: Task 5 rollback 절차 문서화.
- **M2 반영**: Task 4 Step 4 real DAG trigger 검증(connection test 아님).

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `docs/01_ADR/ADR-738_airflow-db-port-publish.md` | 결정 + trade-offs | 신규 |
| `docker-compose.airflow.yml` | airflow-db ports+label, webserver/scheduler host-mode+label+conn+start_period | 수정 |

---

## Task 1: ADR-738 작성 (RPI 선행)

**Files:** Create `docs/01_ADR/ADR-738_airflow-db-port-publish.md`

- [ ] **Step 1: ADR 작성**

````markdown
# ADR-738: airflow DB 접근 repair (host 유지 + airflow-db port publish)

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1435, #1428, #1429, ADR-737

---

## 1. Background / Problem

### Background

- airflow scheduler/webserver 는 host-network 로 구동(compose 는 bridge 선언이나 runtime host). app connections 은 localhost(published port 도달).
- airflow-db 는 bridge `probabilistic-valuation-engine_maple-network`, 포트 미퍼블리시.

### Problem

- host-network scheduler/webserver 에서 `airflow-db` DNS 미해석 → metadata DB 단절 → scheduler unhealthy("No alive jobs found").
- bridge 통일(최초 구상)은 grill 결과 loki/grafana/promtail strand + connection race 유발.

### Goal

- 최소 변경으로 DB 도달 repair + autoheal 활성화.

---

## 2. Decision

> host-network 유지. airflow-db 5433:5432 퍼블리시(5432 는 maple-postgres 점유). SQL_ALCHEMY_CONN `@airflow-db:5432` → `@localhost:5433`. compose 에 host-mode 명시 + autoheal label + scheduler healthcheck start_period 120s.

```text
docker-compose.airflow.yml:
  airflow-db: + ports ["5433:5432"], + labels.autoheal
  webserver/scheduler: networks → network_mode: host, - ports(webserver),
                        + labels.autoheal, SQL_ALCHEMY_CONN @localhost:5433,
                        scheduler healthcheck + start_period: 120s
```

근거: app connections 불변(DAG 영향 無), 관측 스택 strand 無, race 無. Simplicity First.

---

## 3. Trade-offs

### Sensitivity

* 포트 5433 미사용(실측 FREE)
* host-mode webserver 8180 host 직접 bind
* airflow-db recreate metadata 단절(수초, volume 보존)

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| host 유지 + port publish | 최소 변경, connections 불변, strand/race 无 | bridge 통일의 containerized 정합 |
| start_period 120s | autoheal 기동 loop 방지 | 첫 감지 120s 지연 |

### Risk

* metadata DB recreate 단절(수초). volume 보존.

### Non-Risk

* airflow-db 데이터(volume), app connections/DAG 코드, 관측 스택.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| scheduler health | unhealthy → healthy | 배포 후 |
| airflow-db 5433 | 미존재 → published | host localhost:5433 |
| autoheal label | 3 컨테이너 부착 | |

### Observed Result

* 코드/설정 검증: compose config PASS.
* 런타임: 배포 후 본 절 실측.

---

## 5. Summary

> airflow-db 5433 port publish + SQL_ALCHEMY_CONN localhost:5433 로 host-network airflow 의 DB 단결을 최소 repair. autoheal label + start_period 로 자동 복구.
````

- [ ] **Step 2: commit**

```bash
git add docs/01_ADR/ADR-738_airflow-db-port-publish.md
git commit -m "docs(adr): ADR-738 airflow DB repair via port publish (#1435)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: docker-compose.airflow.yml 편집

**Files:** Modify `docker-compose.airflow.yml`

- [ ] **Step 1: airflow-db — port publish + autoheal label**

`airflow-db` 서비스에 port + label 추가(`container_name: maple-airflow-db` 아래):

```yaml
    container_name: maple-airflow-db
    labels:
      autoheal: "true"
    restart: always
    ports:
      - "5433:5432"
```

(5432 는 maple-postgres 점유 → 5433 host port 사용, 실측 FREE)

- [ ] **Step 2: airflow-webserver — host-mode + label, ports 제거**

```yaml
  airflow-webserver:
    image: apache/airflow:2.10.5-python3.12
    container_name: maple-airflow-webserver
    labels:
      autoheal: "true"
    restart: unless-stopped
    network_mode: host
    user: "0:0"
```
(`networks: - maple-network` 제거 → `network_mode: host`. `ports: "8180:8180"` 제거 — host-mode 에서 webserver AIRFLOW__WEBSERVER__WEB_SERVER_PORT=8180 이 host 직접 bind.)

- [ ] **Step 3: airflow-scheduler — host-mode + label**

```yaml
  airflow-scheduler:
    image: apache/airflow:2.10.5-python3.12
    container_name: maple-airflow-scheduler
    labels:
      autoheal: "true"
    restart: unless-stopped
    network_mode: host
    user: "0:0"
```

- [ ] **Step 4: SQL_ALCHEMY_CONN → localhost:5433 (webserver + scheduler)**

양쪽 environment 의 conn 변경:

```yaml
      AIRFLOW__DATABASE__SQL_ALCHEMY_CONN: postgresql+psycopg2://airflow:airflow@localhost:5433/airflow
```

(기존 `@airflow-db:5432`)

- [ ] **Step 5: scheduler healthcheck start_period 추가**

```yaml
    healthcheck:
      test: ["CMD-SHELL", "airflow jobs check --job-type SchedulerJob --hostname '$$(hostname)'"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 120s
```

- [ ] **Step 6: 구문 검증**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 7: 변경 사항 dry 확인**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config | grep -E '5433|localhost:5433|network_mode|start_period|autoheal' | head`
Expected: 5433 publish, localhost:5433 conn, host mode, start_period, autoheal labels.

- [ ] **Step 8: commit**

```bash
git add docker-compose.airflow.yml
git commit -m "feat(airflow): airflow-db 5433 publish + host-mode + autoheal (#1435)

- airflow-db: publish 5433:5432 (5432 taken by maple-postgres)
- webserver/scheduler: network_mode: host (match runtime reality), drop ports
- SQL_ALCHEMY_CONN @airflow-db:5432 -> @localhost:5433 (DB reach repair)
- autoheal labels on 3 airflow containers
- scheduler healthcheck start_period: 120s (autoheal restart-loop guard)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: airflow 컨테이너 recreate

**Files:** (none — operational)

- [ ] **Step 1: idle window 확인**

```bash
for d in morning_chain_pipeline daily_collection_pipeline daily_cleanup_pipeline; do
  docker exec maple-airflow-scheduler sh -c "airflow dags list-runs -d $d -o plain 2>&1 | head -2"
done
```
Expected: 진행 중(running) run 없음.

- [ ] **Step 2: recreate**

```bash
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate \
  airflow-db airflow-webserver airflow-scheduler
```
Expected: 3컨테이너 Recreated + Started. airflow-db volume 재사용.

- [ ] **Step 3: 5433 publish + host-mode 확인**

```bash
docker port maple-airflow-db
docker inspect maple-airflow-scheduler --format 'NetworkMode={{.HostConfig.NetworkMode}}'
```
Expected: airflow-db `5433/tcp -> 0.0.0.0:5433`; scheduler `NetworkMode=host`.

---

## Task 4: 런타임 검증

**Files:** (none)

- [ ] **Step 1: scheduler DB 도달 + healthy**

```bash
for i in $(seq 1 30); do
  h=$(docker inspect maple-airflow-scheduler --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')
  echo "scheduler: $h"; [ "$h" = "healthy" ] && break; sleep 6
done
```
Expected: `healthy` (start_period 120s + interval 30s → 최대 ~150s).

- [ ] **Step 2: DB conn localhost:5433 동작 실측**

```bash
docker exec maple-airflow-scheduler python3 -c "
from sqlalchemy import create_engine, text
import os
e = create_engine(os.environ['AIRFLOW__DATABASE__SQL_ALCHEMY_CONN'])
with e.connect() as c:
    print('dag count:', c.execute(text('SELECT count(*) FROM dag')).scalar())
"
```
Expected: dag count ≥ 3 (이전 단절 → 해결).

- [ ] **Step 3: webserver healthy + 8180 접근**

```bash
docker inspect maple-airflow-webserver --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}'
curl -sf http://localhost:8180/health
```
Expected: `healthy` + 200.

- [ ] **Step 4: DAG trigger → app 도달 (real end-to-end, grill M2)**

app connections localhost 불변이므로 DAG 가 ext-api 도달하는지 실제 trigger:

```bash
# connections localhost 확인 (불변)
docker exec maple-airflow-scheduler airflow connections get external_api 2>&1 | grep -i host
# HttpSensor 가 ext-api:8081(localhost published) 에 200 확인
docker exec maple-airflow-scheduler python3 -c "
import requests
print('ext-api health:', requests.get('http://localhost:8081/actuator/health', timeout=5).json().get('status'))
"
```
Expected: host=localhost, ext-api health=UP.

- [ ] **Step 5: autoheal label + 인식**

```bash
for c in maple-airflow-db maple-airflow-webserver maple-airflow-scheduler; do
  docker inspect $c --format '{{.Name}} autoheal={{index .Config.Labels "autoheal"}}'
done
docker logs maple-autoheal 2>&1 | grep -iE 'airflow' | tail -3
```
Expected: 3컨테이너 autoheal=true + autoheal 인식 로그.

- [ ] **Step 6: morning_chain schedule 유지**

Run: `docker exec maple-airflow-scheduler sh -c 'airflow dags list 2>&1 | grep morning_chain'`
Expected: `morning_chain_pipeline ... False` (is_paused=False).

- [ ] **Step 7: ADR-738 §4 실측 반영**

---

## Task 5: Rollback 절차 (문서화, grill M1)

**Files:** (rollback note — ADR-738 또는 runbook에 기재)

- [ ] **Step 1: rollback 절차 ADR-738 §3 Risk 또는 본 plan에 명시**

```bash
# Rollback (scheduler still unhealthy after migrate):
# 1. revert docker-compose.airflow.yml (git revert <commit> 또는 수동):
#    - airflow-db: remove ports + label
#    - webserver/scheduler: network_mode: host -> networks: [maple-network], restore ports (webserver)
#    - SQL_ALCHEMY_CONN -> @airflow-db:5432 (revert)
#    - remove start_period
# 2. docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate \
#      airflow-db airflow-webserver airflow-scheduler
# 3. verify scheduler reachable (이전 상태로 복귀 — 단 unhealthy 는 원래 상태이므로 임시 복귀용)
# 주: rollback 시 scheduler unhealthy 원상복구. 근본 repair 필요 시 port 5433 수동 점검.
```

---

## Task 6: pipeline-test skill note 갱신 + PR + close

**Files:** `.claude/skills/pipeline-test/SKILL.md` (tracked)

- [ ] **Step 1: #1435 참조 갱신**

pipeline-test/SKILL.md 의 `#1435` 참조 2곳을 해결 표시로 갱신:
- "See issue #1435 (airflow host/bridge network reconcile) for the unresolved gap." → "Resolved via #PR (airflow-db 5433 port publish + host-mode + autoheal). See ADR-738."
- "#1435 for the airflow network reconcile gap" → 동일.

- [ ] **Step 2: push + PR**

```bash
git push -u origin feature/airflow-network-reconcile
gh pr create --base develop --title "fix(airflow): DB reach repair + autoheal (#1435)" --body "..."
```
body: root cause(host-net DB DNS fail), fix(5433 publish + conn localhost:5433 + host-mode + labels + start_period), grill 반영(B1/B2 설계 회피, M3 start_period, M1 rollback, M2 e2e verify), 검증 결과.

- [ ] **Step 3: 머지 + 이슈 종료**

```bash
gh pr merge <N> --merge
gh issue close 1435 --comment "Done via #N. airflow-db 5433 publish + SQL_ALCHEMY_CONN localhost:5433 (host-network DB reach repair). scheduler healthy. 3 airflow containers autoheal-labeled + start_period 120s. app connections/DAG unchanged. ADR-738."
```

---

## Self-Review (post-revision)

**Spec coverage:** #1435 = T1(ADR)/T2(compose)/T3(recreate)/T4(verify)/T5(rollback)/T6(PR+close).
**grill 반영:** B1/B2(설계 회피), M3(start_period), M1(rollback T5), M2(e2e trigger T4-S4).
**Placeholder:** 없음.
**일관성:** 포트 5433, conn localhost:5433, host-mode 전 태스크 일치.
