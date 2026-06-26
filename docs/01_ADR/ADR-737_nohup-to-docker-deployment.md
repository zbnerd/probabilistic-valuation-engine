# ADR-737: nohup → docker compose 배포 전환

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline
- Related: #1245, #1428, #1429, #1430, #1431, ADR-731, ADR-733

---

## 1. Background / Problem

### Background

- 4 active 모듈(external-api/calculator/synchronizer/cleanup)을 nohup 호스트 프로세스로 운영. #1245 로 이미지 빌드(`:sha-75cb631`).
- Endurance Test #2(~71h) 종료 직후, 파이프라인 IDLE(`calculation_jobs` non-terminal=0), 4 포트 FREE.

### Problem

- 운영 통일(autoheal 자동복구, cadvisor 컨테이너 메트릭) 위해 docker 전환 필요.
- 단, grill(critic opus) + 독립 검증으로 3가지 장애물 발견:
  1. **network duality** — infra(postgres/kafka/minio)는 `probabilistic-valuation-engine_maple-network`(project=probabilistic-valuation-engine)에, redis만 `maple-network`에 존재. app 서비스(services.yml)가 `maple-network` 선언 → `postgres`/`kafka`/`minio` DNS **SERVFAIL** (실측). 기본 `compose up` 시 app crash-loop.
  2. **`:dev` 태그 부재** — services.yml 기본값 `maple/{module}:dev` 가 image not found (`:sha-75cb631`만 존재).
  3. **airflow host-network drift** — compose bridge 선언 vs 실제 host network. recreate 시 healthy webserver 파손 위험.
- autoheal/cadvisor 정의만 존재, 미가동.

### Goal

- network reconcile + tag 해석으로 4 모듈 docker 배포 정상화. autoheal/cadvisor 가동. runbook 문서화.

---

## 2. Decision

> (1) infra 컨테이너를 `maple-network`에 live 연결(DNS 해석). (2) deploy-apps.sh 가 `:sha-*` 태그 자동 해석(`:dev` 부재 회피). (3) airflow autoheal runtime 은 별도 이관(host-network drift 위험).

```text
Task0: docker network connect --alias <service> maple-network <infra-ctn>
       (postgres/kafka/minio — --alias 필수: 컨테이너명 아닌 service alias 로 app 가 참조)
deploy: IMAGE_<MOD>=maple/<mod>:sha-<latest>  (deploy-apps.sh resolves)
airflow: 제외 (follow-up — host/bridge network reconcile 선행 필요)
```

`docker network connect --alias` 는 non-disruptive(재시작 아님). 단 `--alias` 생략 시 service alias(postgres/kafka/minio)가 DNS 미해석 → app crash. prometheus 는 host network + 포트퍼블리시로 mode-agnostic → config 변경 無.

---

## 3. Trade-offs

### Sensitivity

* `maple-network` 연결 영속성 — `restart` 에는 유지되나 `rm + recreate` 시 상실(runbook 기재).
* `:sha-*` 태그 존재 — 빌드 후 최신 sha 유지 필요.
* IDLE 시점 — in-flight 손실 방지(hard gate).

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| live network connect (infra recreate 아님) | infra 무중단 reconcile | recreate 시 재연결 필요(수동) |
| airflow autoheal 이관 | 동작 중 airflow 보호 | airflow 자동복구 지연(follow-up) |

### Risk

* network connect 비영속 — runbook 기재로 보완. infra recreate 시 재실행.
* 카운터 리셋 — 프로세스 전환으로 Prometheus 누적 counter 0 초기화. endurance 데이터는 endurance-report-71h.md 로 보존. post-test 시점 허용.

### Non-Risk

* 데이터 손실 — IDLE 전환(non-terminal job=0, kafka lag=0).
* prometheus config — host network + 포트퍼블리시로 mode-agnostic, 변경 無.
* DB 데이터 — PostgreSQL 컨테이너 계속 running, 전환 무관.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| DNS(postgres from maple-network) | SERVFAIL → 해결 | Task 0 후 |
| 4 모듈 `/actuator/health` | 4/4 UP | 배포 후 |
| cadvisor metric 수집 | `container_cpu_usage_seconds_total` series ≥ 1 | 배포 후 |

### Observed Result (2026-06-26 배포 후 실측)

* **배포 성공**: 4 app 컨테이너 healthy + autoheal + cadvisor Up.
* **network reconcile 검증**: `maple-network` 에서 postgres→10.0.3.2 / kafka→10.0.3.3 / minio→10.0.3.5 / redis→10.0.3.4 해석 (이전 SERVFAIL 해소). 단 `docker network connect --alias <svc>` 필수.
* **이미지 태그**: `:dev` 부재 → deploy-apps.sh 가 `:sha-75cb631` 자동 해석.
* **pipeline 흐름**: ext-api run-on-startup → RankingFetch `fetched=270000 page=1350/3000 failed=0` → Kafka chunk-ready publish → calculator 소비 → synchronizer 파티션 할당.
* **ERROR 로그**: 4 컨테이너 전부 0. **Kafka LAG**: 전 consumer group 0.
* **cadvisor (#1430)**: prometheus 가 `container_cpu_usage_seconds_total`/`container_memory_usage_bytes` 각 76 series 스크레이프 (config reload 위해 prometheus 재시작 1회).
* **prometheus app scrape**: external-api/calc/synchronizer `up=1`. cleanup `/actuator/prometheus` 404 (기존 미노출, health UP → 회귀 아님).
* **재시작 영향**: postgres/kafka/minio 가 compose reconcile 로 recreate (수초, volume 보존, IDLE 허용).
* **경고**: `.env` DB_ROOT_PASSWORD 의 `$pNDA2` 를 compose interpolation → blank. 단 postgres·app 동일 interpolation → 상호 일관.

---

## 5. Summary

> infra 를 `maple-network`에 live 연결하고 deploy-apps.sh 가 sha 태그를 해석하여 4 모듈 docker 전환. airflow autoheal 은 network reconcile 선행 필요로 별도 이관. 파이프라인 IDLE 시점 전환으로 데이터 손실 0.
