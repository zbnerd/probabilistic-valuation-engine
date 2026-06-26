# airflow DB 접근 repair + autoheal 설계 (Issue #1435)

- Status: Revised (post-grill — bridge-migrate → host+port-publish)
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1435, #1428, #1429, ADR-737

---

## 1. Background / Problem

### Background

- #1428 로 4 app 모듈 docker compose 전환. #1429 airflow autoheal 은 #1435 이관.
- airflow 구동 상태(실측):
  - scheduler/webserver: **host-network** (compose 는 `networks: [maple-network]` 선언이나 runtime override 로 host).
  - airflow-db: bridge `probabilistic-valuation-engine_maple-network`, 포트 미퍼블리시.
  - Airflow connections: `external_api`/`calculator`/`cleanup` host = `localhost` (host-network 가 app published port 도달).
  - `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` = `@airflow-db:5432`.

### Problem (실측 확정)

- host-network scheduler/webserver 에서 `airflow-db` Docker DNS **미해석**(`socket.gaierror`). airflow-db 포트 미퍼블리시 → scheduler 가 metadata DB 미도달 → "No alive jobs found" → **unhealthy**.
- host-mode 는 app 도달(localhost:published) 목적이었으나, DB 접근이 단절되는 부작용.

### Goal

- scheduler healthy 회복(DB 도달).
- app 도달 유지(connections localhost 그대로).
- airflow 3컨테이너 autoheal label → 자동 복구.

---

## 2. Design — host 유지 + airflow-db port publish

### 2.1 접근 선택 (grill 로 bridge-migrate 폐기)

최초 구상(bridge `maple-network` 통일 + connections DNS 화)은 grill(critic opus) 결과 2개 BLOCKER 동반:
- **B1**: loki/grafana/promtail 이 `probabilistic-valuation-engine_maple-network` 에만 존재 → airflow 이관 시 strand(관측 스택 고립).
- **B2**: connection 갱신·recreate 순서 race (stale localhost DAG 전파 위험).

**채택 접근(host 유지 + port publish)** = #1435 이슈가 명시 허용한 "host 유지" 분기. Simplicity First:
- airflow-db 포트 `5433:5432` 퍼블리시(5432 는 maple-postgres 점유 → 5433 사용, 실측 FREE).
- scheduler/webserver `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` `@airflow-db:5432` → `@localhost:5433`.
- scheduler/webserver compose `network_mode: host` 명시(runtime 현실 일치) + autoheal label.
- app connections(localhost) **변경 없음** → DAG 코드 영향 無, strand 無, race 無.

### 2.2 컴포넌트 변경

| 파일 | 변경 |
|---|---|
| `docker-compose.airflow.yml` | (a) airflow-db: `ports: ["5433:5432"]` + `labels: autoheal: "true"`. (b) airflow-webserver/scheduler: `networks: [maple-network]` → `network_mode: host`(webserver `ports:` 제거 — host-mode 에서 의미 없음) + `labels: autoheal: "true"`. (c) 양쪽 `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` `@airflow-db:5432` → `@localhost:5433`. (d) scheduler healthcheck `start_period: 120s` 추가(autoheal restart-loop 방지, grill M3). |

### 2.3 전환 절차 (runtime)

```
(1) docker-compose.airflow.yml 편집 (ports/network_mode/labels/conn/start_period)
(2) compose config 구문 검증
(3) docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate \
      airflow-db airflow-webserver airflow-scheduler
    → airflow-db volume 보존(metadata 유지). scheduler/webserver host-mode + autoheal label.
(4) 검증: scheduler healthy, DB conn localhost:5433 도달, DAG trigger→app(localhost connections) 정상,
    autoheal label 인식
```

### 2.4 autoheal 연동

- autoheal 컨테이너는 #1428 배포로 이미 running.
- airflow 3컨테이너 label + healthcheck 로 autoheal 감시. scheduler unhealthy 시 5s 내 재시작.
- `start_period: 120s` 로 기동 중 false-unhealthy 회피.

---

## 3. Trade-offs

### Sensitivity

* 포트 5433 미사용(실측 FREE) — 타 서비스가 5433 사용 시 충돌.
* host-mode webserver 8180 — host 직접 bind(ports 매핑 없이).
* airflow-db recreate 시점(metadata DB 수초 단절, volume 보존).
* `morning_chain` 다음 발화(06-27 03:00 KST) 간섭 않도록 idle window 실행.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| host 유지 + port publish | 최소 변경, app connections 불변, strand/race 无 | bridge 통일의 "containerized 정합"(차선) |
| `start_period: 120s` | autoheal 기동 중 restart-loop 방지 | 첫 unhealthy 감지 120s 지연 |

### Risk

* metadata DB recreate 중 단절(수초). volume 보존.
* 5433 포트 충돌(사전 실측 FREE 로 회피).

### Non-Risk

* airflow-db 데이터 — volume 보존.
* app 컨테이너 / connections — 변경 無.
* DAG 코드 — `get_external_api_base()` 유도(localhost connections 그대로 유효).
* 관측 스택(loki/grafana/promtail) — network 이동 없음, strand 無.

---

## 4. Result / Evidence

### Metrics (배포 후)

| Metric | 기준 | Notes |
| ------ | ----: | ----- |
| scheduler health | unhealthy → healthy | DB localhost:5433 도달 |
| airflow-db 5433 publish | 미존재 → published | host localhost:5433 → container 5432 |
| DAG trigger → app | 200/202 | connections localhost 불변 |
| autoheal label (3 컨테이너) | 부착 + 인식 | `docker inspect` + autoheal log |

### Observed Result

* 사전 검증: host-net scheduler `airflow-db` DNS 실패, 5433 FREE, SQL_ALCHEMY_CONN 라인 36/65, airflow-db 미퍼블리시.
* 배포 후: plan 실행 후 실측값 기재.

---

## 5. Summary

> host-network airflow 의 DB 단결을 airflow-db 5433 port publish + SQL_ALCHEMY_CONN localhost:5433 로 최소 repair. app connections·DAG 코드 불변, 관측 스택 strand·race 无. autoheal label + start_period 로 자동 복구 활성화. bridge 통일보다 blast radius 최소.
