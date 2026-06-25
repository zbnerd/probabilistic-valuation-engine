# nohup → docker compose 배포 전환 설계 (Issues #1428–#1431)

- Status: Proposed
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1245 (이미지 빌드), #1428, #1429, #1430, #1431, ADR-731, ADR-733, ADR-736

---

## 1. Background / Problem

### Background

- 4 active 모듈(`external-api` 8081 / `calculator` 8082 / `synchronizer` 8083 / `cleanup` 8084)은 현재 nohup 호스트 프로세스로 운영 중이었음.
- 이미지는 #1245 완료로 빌드됨 — `maple/{module}:sha-75cb631` (+ `:dev` mutable alias). `docker/services/build.sh` 가 두 태그 모두 부여.
- `docker-compose.services.yml` 오버레이에 4 서비스 정의(healthcheck + `autoheal:"true"` 라벨 + MinIO SA secret 마운트) 이미 구성됨.
- `docker-compose.yml` 에 `autoheal`(willfarrell/autoheal, ADR-731)·`cadvisor`(gcr.io/cadvisor, ADR-733) 정의되어 있으나 **컨테이너 미실행**.
- `docker/prometheus/prometheus.yml` 에 cadvisor job(`localhost:8086`) + 4 모듈 job(`localhost:PORT`) **이미 존재**.
- Endurance Test #2 (~71h) 종료 직후. 4 모듈 nohup 프로세스 이미 종료 → 포트 8081–8084 FREE 상태. acceptance #1428 "long-run test 종료 후 전환" 시점 충족.

### Problem

- 운영 통일(autoheal 자동복구, cadvisor 컨테이너 메트릭, 통합 관리)을 위해 nohup → docker 전환이 필요하나 절차 미문서화, autoheal/cadvisor 미가동, airflow 컨테이너 autoheal 라벨 누락.
- `maple-airflow-scheduler` 가 현재 unhealthy 상태로 방치 중 → autoheal 도입 시 자동 해소 기대.

### Goal

- 4 active 모듈을 docker compose 로 실배포 전환(#1428).
- autoheal 활성화 + 대상(4 모듈 + airflow) 라벨링(#1429).
- cadvisor 가동 + 컨테이너 메트릭 Prometheus 수집 확인(#1430).
- 전환/rollback runbook 문서화(#1431).

---

## 2. Design

### 2.1 핵심 통찰 — 프로메테우스 scrape 는 mode-agnostic

Prometheus 컨테이너는 `network_mode: host` 로 동작. 4 모듈 docker 서비스는 `ports: "808X:808X"` 호스트 포트 퍼블리시.

→ prometheus 가 바라보는 `localhost:8081..8084` 는 **nohup(호스트 프로세스 바인딩) 이나 docker(퍼블리시된 포트) 어느 쪽이든 도달**. 따라서 prometheus config 변경 無. cadvisor job(`localhost:8086`)도 동일. #1430 의 "scrape config 추가"는 이미 완료된 상태 — 부족분은 cadvisor 컨테이너 **실행** 뿐.

### 2.2 이미지 태그 — 별도 핸들링 불필요

`build.sh` 가 `:dev`(mutable) + `:sha-<SHA>`(reproducible) 두 태그 부여. `docker-compose.services.yml` 기본값 `${IMAGE_*:-maple/{module}:dev}` → `:dev` 가 최신 빌드를 가리키므로 **추가 env 설정 없이** `compose up` 만으로 정상 동작.

### 2.3 컴포넌트 변경 (코드/설정)

| 파일 | 변경 | 이슈 |
|---|---|---|
| `docker-compose.airflow.yml` | airflow-webserver / airflow-scheduler / airflow-db 에 `labels: autoheal: "true"` 추가 (healthcheck 이미 존재) | #1429 |
| `docker/services/deploy-apps.sh` (신규) | idempotent 배포 스크립트 — `compose up -d` 4 svc + autoheal + cadvisor → `/actuator/health` UP 폴링 → 상태 테이블 출력 | #1428/#1429/#1430 |
| `docs/21_Operations/docker-deploy-runbook.md` (신규) | 전환 절차 / rollback / 검증 체크리스트 / 카운터 리셋·다운타임 노트 | #1431 |
| `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` (신규) | 전환 결정 + trade-offs | RPI |

### 2.4 운영 전환 절차 (deploy, runtime)

```
# (1) 사전: phase IDLE 확인 + 포트 free 확인
# (2) nohup 4 프로세스 stop (현재 이미 종료됨 — skip 가능, runbook에 명시)
# (3) 배포 (deploy-apps.sh 가 동일 로직 수행; :dev 가 기본 태그라 env 생략 가능)
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d \   # airflow 라벨 적용 recreate
  airflow-webserver airflow-scheduler airflow-db
docker compose -f docker-compose.yml up -d autoheal cadvisor
# (4) 검증: /actuator/health 200, kafka lag, prometheus cadvisor 메트릭, DB row 증가, ERROR=0
```

### 2.5 Split-brain 방지

두 모드가 동시 포트 점유 시 split-brain. runbook 에 `lsof -ti:808X` 로 LISTEN 프로세스 0 확인 후 `compose up` 선행 조건 명시. 현재 4 포트 전부 FREE 검증 완료.

### 2.6 autoheal 적용 범위

| 대상 | 라벨 | healthcheck | 비고 |
|---|---|---|---|
| 4 active 모듈 | `autoheal:"true"` (services.yml 기존) | 있음 | unhealthy 시 자동 재시작 |
| airflow-webserver/scheduler/db | `autoheal:"true"` (본 설계 추가) | 있음 | scheduler 현재 unhealthy → 자동 해소 예상 |
| cadvisor | 제외 | 없음(설계, ADR-733) | `restart:always` 로 복구 |
| 인프라(postgres/kafka/redis/...) | 기존 라벨 유지 | 있음 | 변경 없음 |

---

## 3. Trade-offs

### Sensitivity

* 배포 시점(파이프라인 IDLE) — 처리 중 chunk 손실 방지. 현재 IDLE 검증됨.
* 포트 점유 여부 — split-brain 원천 방지 조건.
* 이미지 `:dev` 태그가 최신 빌드를 가리키는지 — stale 이미지 배포 위험.
* MinIO SA secret 파일 존재 — 4종 모두 존재 검증됨(root 소유, mode 0444).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| docker 운영 통일 | autoheal 자동복구, cadvisor 컨테이너 메트릭, 통합 관리 | nohup in-process fallback (호스트 직접 제어 단순성) |
| `:dev` 태그 default 사용 | deploy env 설정 불필요 | reproducibility 약화 (`:sha-` 태그로 보완 가능, runbook 명시) |

### Risk

* 배포 실패 시 rollback 필요 — runbook 에 `compose down` → nohup 재시작 경로 문서화.
* 카운터 리셋: 프로세스 전환으로 Prometheus 누적 counter 0 초기화. endurance 데이터는 이미 보고서화(endurance-report-71h.md). post-test 시점이라 허용.

### Non-Risk

* 데이터 손실 — 파이프라인 IDLE 시점 전환, chunk 처리 중 아님.
* prometheus config 변경 — mode-agnostic 으로 변경 無.
* DB 데이터 — PostgreSQL 컨테이너는 계속 running, 전환 무관.

---

## 4. Result / Evidence

### Metrics (검증 기준 — 배포 후 수집)

| Metric | 기준 | Notes |
| ------ | ----: | ----- |
| 4 모듈 `/actuator/health` | 4/4 = 200 UP | deploy-apps.sh 가 폴링 |
| autoheal restart (scheduler) | unhealthy → 5s 내 재시작 | AUTOHEAL_INTERVAL=5 |
| cadvisor metric 수집 | `container_cpu_usage_seconds_total` prometheus 조회 성공 | #1430 |
| kafka consumer LAG | 처리 가능 범위 (≤ 처리량) | #1428 |
| DB read model row | 증가 (파이프라인 흐름 정상) | #1428 |
| ERROR 로그 | 0 | runtime 검증 |

### Observed Result

* 사전 검증(본 설계 시점): 포트 8081–8084 FREE, 이미지 4종 빌드, SA secret 4종 존재, prometheus cadvisor job 존재, 4 서비스 healthcheck+라벨 구성됨.
* 배포 후 검증: 본 설계 실행(implementation plan) 완료 후 본 절에 실측값 기재.

---

## 5. Summary

> nohup 4 모듈을 docker compose 로 전환. prometheus 는 host network + 포트퍼블리시로 mode-agnostic 이라 config 변경 無. 부족분은 autoheal/cadvisor **실행** + airflow autoheal 라벨 + deploy 스크립트 + runbook + ADR-737. 파이프라인 IDLE 시점 전환으로 데이터 손실 0.
