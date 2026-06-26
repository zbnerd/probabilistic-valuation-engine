# airflow 네트워크 reconcile + autoheal Implementation Plan (#1435)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** airflow scheduler/webserver 를 bridge `maple-network` 로 이관, airflow-db 단절 해소, connections DNS 화, autoheal label 부착.

**Architecture:** 전부 동일 bridge `maple-network` 배치. scheduler/webserver → airflow-db DNS 도달(healthy). DAG → app service-name DNS 도달. autoheal 이 3컨테이너 감시.

**Spec:** `docs/superpowers/specs/2026-06-26-airflow-network-reconcile-design.md`

**Tech Stack:** docker compose v2, willfarrell/autoheal, Airflow 2.10.5, PostgreSQL 17 (airflow-db).

**Test strategy:** config/ops. 검증 = `compose config` 구문 + 런타임(scheduler healthy, connection test, autoheal label).

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `docs/01_ADR/ADR-738_airflow-network-reconcile.md` | 결정 + trade-offs | 신규 |
| `docker-compose.airflow.yml` | network name 통일 + autoheal labels | 수정 |
| Airflow connections (runtime) | localhost → DNS | CLI 갱신 |

---

## Task 1: ADR-738 작성 (RPI 선행)

**Files:** Create `docs/01_ADR/ADR-738_airflow-network-reconcile.md`

- [ ] **Step 1: ADR 작성** (adr-conventions 5섹션)

````markdown
# ADR-738: airflow 네트워크 reconcile (bridge maple-network 통일)

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1435, #1428, #1429, ADR-737

---

## 1. Background / Problem

### Background

- #1428 로 app 모듈이 docker compose 전환(`maple-network`). airflow 는 scheduler/webserver 가 host-network, airflow-db 가 bridge(`probabilistic-valuation-engine_maple-network`)로 분리.
- #1429 airflow autoheal 은 host/bridge drift 로 #1435 이관.

### Problem

- host-network scheduler/webserver 에서 `airflow-db` DNS 미해석 → scheduler 가 metadata DB 단절 → "No alive jobs found" unhealthy(실측).
- host-mode 는 app 도달(localhost:published) 목적이었으나 DB 단절 부작용이 더 큼.

### Goal

- scheduler healthy 회복. DAG→app 도달 유지. autoheal 자동 복구.

---

## 2. Decision

> airflow 3컨테이너를 bridge `maple-network` 로 통일(app 과 동일 네트워크), Airflow connections host 를 localhost→DNS 로 전환, autoheal label 부착.

```text
docker-compose.airflow.yml:
  networks.maple-network.name: probabilistic-valuation-engine_maple-network → maple-network
  airflow-db / webserver / scheduler: + labels.autoheal="true"
connections (runtime):
  external_api/calculator/cleanup host: localhost → external-api/calculator/cleanup
recreate: compose up -d --force-recreate airflow-db airflow-webserver airflow-scheduler
```

---

## 3. Trade-offs

### Sensitivity

* airflow-db recreate 시점(metadata DB 수초 단절, volume 보존)
* connection alias 정확성
* morning_chain 다음 03:00 발화 간섭 없도록 idle window 실행

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| bridge 통일 | DB 도달(healthy), app DNS, autoheal | host localhost 단순성 |
| connections DNS | containerized 정합 | localhost fallback 경로 폐기 |

### Risk

* metadata DB recreate 중 단절(수초). volume 보존.

### Non-Risk

* airflow-db 데이터 — volume 보존.
* app 컨테이너 — 이미 maple-network, 변경 無.
* DAG 코드 — connection 유도(localhost 하드코딩 無).

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| scheduler health | unhealthy → healthy | 배포 후 |
| `airflow-db` DNS (scheduler) | 실패 → 해결 | maple-network |
| DAG trigger → app | 200/202 | connection DNS |

### Observed Result

* 코드/설정 검증: compose config PASS.
* 런타임: 배포 후 본 절 실측.

---

## 5. Summary

> airflow 를 bridge `maple-network` 로 통일하여 DB 단절을 해소, connections DNS 화로 app 도달 유지, autoheal 로 자동 복구 활성화.
````

- [ ] **Step 2: commit**

```bash
git add docs/01_ADR/ADR-738_airflow-network-reconcile.md
git commit -m "docs(adr): ADR-738 airflow network reconcile (#1435)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: docker-compose.airflow.yml 편집

**Files:** Modify `docker-compose.airflow.yml`

- [ ] **Step 1: network name 통일**

`networks:` 블록(파일 하단)의 external name 변경:

```yaml
networks:
  maple-network:
    external: true
    name: maple-network
```

(기존 `name: probabilistic-valuation-engine_maple-network`)

- [ ] **Step 2: airflow-db autoheal label 추가**

`airflow-db` 서비스의 `container_name: maple-airflow-db` 다음에:

```yaml
    container_name: maple-airflow-db
    labels:
      autoheal: "true"
    restart: always
```

- [ ] **Step 3: airflow-webserver autoheal label 추가**

```yaml
    container_name: maple-airflow-webserver
    labels:
      autoheal: "true"
    restart: unless-stopped
```

- [ ] **Step 4: airflow-scheduler autoheal label 추가**

```yaml
    container_name: maple-airflow-scheduler
    labels:
      autoheal: "true"
    restart: unless-stopped
```

- [ ] **Step 5: 구문 검증**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 6: label 적용 확인 (dry)**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config | grep -B1 autoheal | head -20`
Expected: airflow 3서비스 + 기존 infra autoheal 라벨 출력

- [ ] **Step 7: commit**

```bash
git add docker-compose.airflow.yml
git commit -m "feat(airflow): unify maple-network + autoheal labels (#1435)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: airflow 컨테이너 recreate

**Files:** (none — operational)

- [ ] **Step 1: 사전 active run 확인 (idle window)**

Run: `docker exec maple-airflow-scheduler sh -c 'airflow dags list-runs -d morning_chain_pipeline -o plain 2>&1 | head -3'`
Expected: 가장 최근 run이 03:00(완료), 진행 중 run 없음.

- [ ] **Step 2: recreate (network 이관 + label 적용)**

```bash
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate \
  airflow-db airflow-webserver airflow-scheduler
```
Expected: 3컨테이너 Recreated + Started. airflow-db volume 재사용.

- [ ] **Step 3: network 확인**

Run: `docker inspect maple-airflow-scheduler --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'`
Expected: `maple-network` (host 아님).

Run: `docker exec maple-airflow-scheduler sh -c 'python3 -c "import socket; print(socket.gethostbyname(\"airflow-db\"))"'`
Expected: IP 출력(이전 resolution 실패 → 해결).

---

## Task 4: Airflow connections localhost → DNS

**Files:** (runtime — Airflow metadata DB)

- [ ] **Step 1: connections 갱신**

```bash
docker exec maple-airflow-scheduler airflow connections delete external_api 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add external_api \
  --conn-type http --conn-host external-api --conn-port 8081 --conn-schema http
docker exec maple-airflow-scheduler airflow connections delete calculator 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add calculator \
  --conn-type http --conn-host calculator --conn-port 8082 --conn-schema http
docker exec maple-airflow-scheduler airflow connections delete cleanup 2>/dev/null
docker exec maple-airflow-scheduler airflow connections add cleanup \
  --conn-type http --conn-host cleanup --conn-port 8084 --conn-schema http
```

- [ ] **Step 2: 갱신 확인**

Run: `docker exec maple-airflow-scheduler airflow connections list 2>&1 | grep -E 'external_api|calculator|cleanup'`
Expected: host 가 `external-api` / `calculator` / `cleanup` (localhost 아님).

---

## Task 5: 런타임 검증

**Files:** (none)

- [ ] **Step 1: scheduler healthy (DB 도달)**

```bash
for i in $(seq 1 15); do
  h=$(docker inspect maple-airflow-scheduler --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')
  echo "scheduler health: $h"; [ "$h" = "healthy" ] && break; sleep 5
done
```
Expected: `healthy` (이전 unhealthy → 해소).

- [ ] **Step 2: webserver healthy**

Run: `docker inspect maple-airflow-webserver --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}'`
Expected: `healthy`. 접근: `curl -sf http://localhost:8180/health`.

- [ ] **Step 3: connection test → app 도달 (DNS)**

```bash
docker exec maple-airflow-scheduler python3 -c "
from airflow.hooks.base import BaseHook
c = BaseHook.get_connection('external_api')
import requests
r = requests.get(f'{c.get_uri()}/actuator/health', timeout=5)
print('ext-api via', c.host, '->', r.status_code, r.json().get('status'))
"
```
Expected: `ext-api via external-api -> 200 UP`.

- [ ] **Step 4: autoheal label + 인식**

Run: `for c in maple-airflow-db maple-airflow-webserver maple-airflow-scheduler; do docker inspect $c --format '{{.Name}} autoheal={{index .Config.Labels "autoheal"}}'; done`
Expected: 3컨테이너 모두 `autoheal=true`.

Run: `docker logs maple-autoheal 2>&1 | grep -iE 'airflow|monitoring' | tail -5`
Expected: autoheal 이 airflow 컨테이너 인식 로그 (또는 unhealthy 없이 정상).

- [ ] **Step 5: morning_chain schedule 유지**

Run: `docker exec maple-airflow-scheduler sh -c 'airflow dags list 2>&1 | grep morning_chain'`
Expected: `morning_chain_pipeline ... False` (is_paused=False, schedule 유지).

- [ ] **Step 6: ADR-738 §4 실측 반영**

---

## Task 6: pipeline-test skill note 갱신 + PR + close

**Files:** `.claude/skills/pipeline-test/SKILL.md` (tracked)

- [ ] **Step 1: skill의 #1435 참조 갱신** (해결됨 표시)

`#1435` 참조 2곳을 "해결됨(bridge maple-network 통일)"으로 갱신.

- [ ] **Step 2: push + PR**

```bash
git push -u origin feature/airflow-network-reconcile
gh pr create --base develop --title "feat(airflow): network reconcile to maple-network + autoheal (#1435)" --body "..."
```

- [ ] **Step 3: 머지 + 이슈 종료**

```bash
gh pr merge <N> --merge
gh issue close 1435 --comment "Done via #N. airflow 3 containers migrated to bridge maple-network; scheduler healthy (DB reachable); connections localhost→DNS; autoheal labels active."
```

---

## Self-Review

**Spec coverage:** #1435 전체 = T1(ADR)/T2(compose)/T3(recreate)/T4(connections)/T5(verify)/T6(PR+close). 
**Placeholder:** 없음 — 실제 YAML/CLI 포함.
**일관성:** 서비스명·alias·포트·네트워크명 전 태스크 일치.
