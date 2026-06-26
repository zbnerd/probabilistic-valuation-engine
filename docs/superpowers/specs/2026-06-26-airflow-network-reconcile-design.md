# airflow 네트워크 reconcile + autoheal 설계 (Issue #1435)

- Status: Proposed
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1429 (airflow autoheal 이관), #1428 (docker 전환), ADR-737

---

## 1. Background / Problem

### Background

- #1428 로 4 app 모듈이 docker compose 로 전환되어 `maple-network` 에 존재(external-api/calculator/synchronizer/cleanup, service-name alias 로 DNS 해석).
- #1429 에서 airflow autoheal 은 host/bridge network drift 로 인해 본 PR(#1435)로 이관.
- airflow 구성(docker-compose.airflow.yml):
  - scheduler/webserver: compose 는 `networks: [maple-network]` 선언이나 **실제 구동은 `network_mode: host`**(runtime override).
  - airflow-db: bridge `probabilistic-valuation-engine_maple-network`, 포트 미퍼블리시.
  - Airflow connections: `external_api`/`calculator`/`cleanup` 의 host = **`localhost`**(host-network 가 published port 로 app 도달).

### Problem (실측으로 확정)

- host-network scheduler/webserver 에서 `airflow-db` Docker DNS 이름 **미해석**(`socket.gaierror: Temporary failure in name resolution`). airflow-db IP=172.20.0.4(bridge)에 포트 퍼블리시 없음.
- scheduler 가 자체 metadata DB 에 heartbeat 못 씀 → healthcheck "No alive jobs found" → **unhealthy**.
- 즉 host-network 선택이 app 도달(localhost:published)을 위함이었으나, 부작용으로 airflow-db 단절·scheduler 불건강 유발.
- autoheal label 추가 위해 compose recreate 시 host→bridge 전환되는데, 이게 **실은 repair**(DB 단절 해소)이나 connections 가 localhost 라 app 단절이 새로 발생.

### Goal

- scheduler/webserver 가 airflow-db 에 도달(healthy 회복).
- DAG 가 app 컨테이너에 도달(connection DNS 화).
- airflow 3컨테이너 autoheal 라벨링 → 자동 복구.

---

## 2. Design

### 2.1 핵심 — `maple-network` 로 통일 + connections DNS 화

전부 동일 bridge `maple-network` 에 배치:
- airflow-db + scheduler + webserver → maple-network(bridge).
- app 컨테이너(external-api/calculator/synchronizer/cleanup) → 이미 maple-network, service-name alias 로 DNS 해석(실측: external-api→10.0.3.8).
- Airflow connections host `localhost` → DNS(`external-api`/`calculator`/`cleanup`).

결과: scheduler/webserver 가 `airflow-db` DNS(같은 네트워크 compose service alias)로 DB 도달 + DAG 가 `external-api:8081` 등으로 app 도달.

### 2.2 컴포넌트 변경

| 파일 | 변경 |
|---|---|
| `docker-compose.airflow.yml` | (a) network decl `name: probabilistic-valuation-engine_maple-network` → `name: maple-network`(docker-compose.yml 과 일치). (b) airflow-db/webserver/scheduler 에 `labels: autoheal: "true"` 추가 |
| Airflow connections (runtime) | `external_api`/`calculator`/`cleanup` host: `localhost` → `external-api`/`calculator`/`cleanup`(port 8081/8082/8084 유지). `airflow connections` CLI 로 갱신 |

### 2.3 전환 절차 (runtime)

```
(1) docker-compose.airflow.yml 편집 (network name + autoheal labels)
(2) docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d --force-recreate \
      airflow-db airflow-webserver airflow-scheduler
    → 3컨테이너 maple-network(bridge) 재생성. airflow-db volume 보존(metadata 유지).
(3) connections 갱신:
      airflow connections delete external_api; add external_api --conn-host external-api --conn-port 8081 ...
      (calculator→calculator:8082, cleanup→cleanup:8084)
(4) 검증: scheduler healthy, DAG trigger→app 도달, autoheal label, morning_chain schedule 유지
```

### 2.4 autoheal 연동

- autoheal 컨테이너는 #1428 배포로 이미 running.
- airflow 3컨테이너에 label 추가 + healthcheck(이미 compose 에 존재) → autoheal 이 감시·재시작.
- airflow-scheduler unhealthy 시 AUTOHEAL_INTERVAL=5s 내 재시작.

---

## 3. Trade-offs

### Sensitivity

* airflow-db recreate 시점 — volume 보존되나 잠깐 metadata DB 단절(scheduler 재기동 중).
* connection host 오타 → DAG 전파 실패. alias 정확성(`external-api`/`calculator`/`cleanup`).
* `morning_chain` 다음 발화 06-27 03:00 — recreate 가 스케줄 간섭 않도록 idle window 실행.

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| bridge maple-network 통일 | DB 도달(healthy), app DNS 도달, autoheal | host-network 의 localhost 단순성 |
| connections DNS 화 | containerized 모델 정합 | localhost fallback 경로(더 이상 사용 안 함) |

### Risk

* airflow metadata DB recreate 중 단절(수~수십초). volume 보존으로 데이터 손실 無.
* connection 갱신 누락 시 DAG 실패 — 검증 단계에서 강제 trigger 로 확인.

### Non-Risk

* airflow-db 데이터 — volume `probabilistic-valuation-engine_airflow_db_data` 보존.
* app 컨테이너 — 이미 maple-network, 변경 無.
* DAG 코드 — `get_external_api_base()` 로 connection 유도(localhost 하드코딩 無, 실측).

---

## 4. Result / Evidence

### Metrics (검증 기준 — 배포 후)

| Metric | 기준 | Notes |
| ------ | ----: | ----- |
| scheduler health | unhealthy → healthy | DB 도달 후 "alive jobs" 확보 |
| `airflow-db` DNS (from scheduler) | 해석 실패 → 해결 | maple-network bridge |
| DAG trigger → app | HTTP 200/202 | connection DNS 로 ext-api 도달 |
| autoheal label (3 컨테이너) | 부착 + autoheal 인식 | `docker inspect` label + autoheal log |

### Observed Result

* 사전 검증(본 설계 시점): host-net scheduler 의 `airflow-db` DNS 실패, app alias maple-network 해석, DAG connection 유도, airflow-db volume 존재.
* 배포 후: plan 실행 후 본 절에 실측값 기재.

---

## 5. Summary

> airflow scheduler/webserver 를 bridge `maple-network` 로 이관(airflow-db 와 동일 네트워크)하여 DB 단절을 해소하고, connections 를 DNS 화하여 app 도달을 유지하며, autoheal label 로 자동 복구를 활성화. host-network 선택은 app localhost 도달 목적이었으나 DB 단결 부작용이 컸음 — bridge 통일이 양쪽을 모두 해결.
