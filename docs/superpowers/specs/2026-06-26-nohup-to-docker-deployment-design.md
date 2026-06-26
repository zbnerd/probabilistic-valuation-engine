# nohup → docker compose 배포 전환 설계 (Issues #1428–#1431)

- Status: Revised (post-grill)
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1245 (이미지 빌드), #1428, #1429, #1430, #1431, ADR-731, ADR-733, ADR-736, ADR-737

---

## 1. Background / Problem

### Background

- 4 active 모듈(`external-api` 8081 / `calculator` 8082 / `synchronizer` 8083 / `cleanup` 8084)은 nohup 호스트 프로세스로 운영.
- 이미지 #1245 빌드 — `maple/{module}:sha-75cb631` (단 `:dev` mutable 태그는 **부재**, grill 검증).
- `docker-compose.services.yml` 오버레이에 4 서비스 정의(healthcheck + `autoheal:"true"` 라벨 + MinIO SA secret) 구성됨.
- `docker-compose.yml` 에 `autoheal`·`cadvisor` 정의되었으나 컨테이너 미실행.
- `docker/prometheus/prometheus.yml` 에 cadvisor job + 4 모듈 job 존재.
- Endurance Test #2 (~71h) 종료 직후. 4 nohup 종료 → 포트 8081–8084 FREE. 파이프라인 IDLE(`calculation_jobs` non-terminal=0).

### Problem (grill 로 발견된 추가 장애물)

1. **network duality** — infra(postgres/kafka/minio)는 `probabilistic-valuation-engine_maple-network`(project=probabilistic-valuation-engine)에, redis만 `maple-network`에. app 서비스는 `maple-network` 선언 → `postgres`/`kafka`/`minio` DNS **SERVFAIL** (실측 확인). 기본 `compose up` 시 app crash-loop.
2. **`:dev` 태그 부재** — services.yml 기본값 `maple/{module}:dev` 가 image not found.
3. **airflow host-network drift** — compose bridge 선언 vs 실제 host network. recreate 시 healthy webserver 파손 위험.
4. autoheal/cadvisor 미가동, runbook 미문서화.

### Goal

- network reconcile + tag 해석으로 4 모듈 docker 배포 정상화(#1428).
- autoheal 가동(4 app 모듈 대상)(#1429). airflow runtime 은 follow-up 이관.
- cadvisor 가동 + 컨테이너 메트릭 수집(#1430).
- runbook 문서화(#1431).

---

## 2. Design

### 2.1 핵심 통찰 — 프로메테우스 scrape 는 mode-agnostic (유효)

Prometheus 컨테이너는 `network_mode: host`. 4 모듈 docker 서비스는 `ports: "808X:808X"` 퍼블리시. → `localhost:8081..8084` 가 nohup/docker 양쪽 도달. prometheus config 변경 無. cadvisor job(`localhost:8086`)도 동일. (grill 검증: 이 통찰은 prom→app 한정 유효. **app→infra DNS 는 별도 문제** — §2.2 network reconcile 로 해결.)

### 2.2 network reconcile + tag 해석

**network:** infra 컨테이너를 `maple-network`에 live 연결(`docker network connect`, non-disruptive). redis 는 이미 존재 → 4 infra 모두 maple-network 에서 DNS 해석. 비영속(recreate 시 상실) → runbook 기재.

**tag:** `:dev` 부재 → deploy-apps.sh 가 `:sha-*` 최신 태그 자동 해석, `IMAGE_<MODULE>` env 로 compose 전달.

### 2.3 컴포넌트 변경

| 파일 | 변경 | 이슈 |
|---|---|---|
| `docker/services/deploy-apps.sh` (신규) | tag 해석 + pre-flight(port/DNS/secret/jar/IDLE) + `compose up` + health 폴링 | #1428/#1429/#1430 |
| `docs/21_Operations/docker-deploy-runbook.md` (신규) | 전환/rollback/검증/IDLE 정의/network 주의 | #1431 |
| `docs/01_ADR/ADR-737` (신규) | 전환 결정 + network 현실 반영 | RPI |
| `docker-compose.airflow.yml` | **변경 제외** — airflow autoheal runtime follow-up 이관 | #1429 partial |

**operational(Task 0):** `docker network connect maple-network {maple-postgres, maple-kafka, probabilistic-valuation-engine-minio-1}`.

### 2.4 전환 절차 (runtime)

```
(0) network reconcile: docker network connect maple-network maple-postgres maple-kafka <minio>
(1) IDLE gate: calculation_jobs non-terminal = 0
(2) nohup stop (이미 종료 — skip 가능)
(3) 포트 free 확인
(4) ./docker/services/deploy-apps.sh   # tag 해석 + pre-flight + up + health
(5) 검증: health, autoheal 유발, cadvisor metric, kafka lag, manual trigger→DB row, ERROR=0
```

### 2.5 Split-brain 방지

pre-flight 가 `lsof -ti:808X` 로 LISTEN 0 확인 후 up. 현재 4 포트 FREE.

### 2.6 autoheal 적용 범위

| 대상 | 상태 | 비고 |
|---|---|---|
| 4 active 모듈 | `autoheal:"true"` 라벨 존재(services.yml) | up -d autoheal 로 활성화 |
| cadvisor | 제외 | healthcheck 無(ADR-733), restart:always 로 복구 |
| airflow 3컨테이너 | **follow-up 이관** | host/bridge network reconcile 선행 필요, recreate 위험 |
| 인프라(postgres/kafka/...) | 기존 라벨 유지 | 변경 없음 |

---

## 3. Trade-offs

### Sensitivity

* `maple-network` 연결 영속성 — infra recreate 시 상실(runbook 기재).
* `:sha-*` 태그 존재 — 빌드 후 최신 유지.
* IDLE 시점 — in-flight 손실 방지(hard gate).
* MinIO SA secret 존재 — 4종 검증됨(root 소유, mode 0444, bind-mount 정상).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| live network connect (infra recreate 아님) | infra 무중단 reconcile | recreate 시 재연결(수동) |
| airflow autoheal 이관 | 동작 중 airflow 보호 | airflow 자동복구 지연(follow-up) |

### Risk

* network connect 비영속 — runbook 으로 보완.
* 카운터 리셋 — Prometheus 누적 초기화(post-test 허용).

### Non-Risk

* 데이터 손실 — IDLE 전환(non-terminal job=0, kafka lag=0).
* prometheus config — mode-agnostic, 변경 無.
* DB 데이터 — PostgreSQL 컨테이너 계속 running.

---

## 4. Result / Evidence

### Metrics (배포 후)

| Metric | 기준 | Notes |
| ------ | ----: | ----- |
| DNS(postgres from maple-network) | SERVFAIL → 해결 | Task 0 |
| 4 모듈 `/actuator/health` | 4/4 UP | deploy-apps.sh 폴링 |
| autoheal 유발 테스트 | throwaway 컨테이너 재시작 + app 무영향 | #1429 |
| cadvisor metric 수집 | `container_cpu_usage_seconds_total` series ≥ 1 | #1430 |
| manual trigger → DB row | 증가 | end-to-end |
| ERROR 로그 | 0 | runtime |

### Observed Result

* 사전 검증: 포트 FREE, 이미지 4종(:sha-75cb631), SA secret 4종, jar 4종, IDLE(non-terminal=0), network duality 실측, :dev 부재 실측.
* 배포 후: plan 실행 완료 후 실측값 기재.

---

## 5. Summary

> infra 를 `maple-network`에 live 연결하고 deploy-apps.sh 가 `:sha-*` 태그를 해석하여 4 모듈 docker 전환. airflow autoheal runtime 은 network reconcile 선행 필요로 follow-up 이관. 파이프라인 IDLE 시점 전환으로 데이터 손실 0.
