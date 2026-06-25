# nohup → docker compose 배포 전환 Implementation Plan (#1428–#1431)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 4 active 모듈을 nohup → docker compose 로 전환하고 autoheal/cadvisor 를 가동·검증한 뒤 runbook 으로 문서화.

**Architecture:** 설정 변경은 최소(airflow autoheal 라벨 추가만). prometheus 는 host network + 포트퍼블리시로 mode-agnostic 이라 config 변경 無. idempotent deploy 스크립트가 `compose up` + health 폴링 수행. 파이프라인 IDLE 시점 전환.

**Tech Stack:** docker compose v2, willfarrell/autoheal, gcr.io/cadvisor/cadvisor, Prometheus, Spring Boot Actuator, bash.

**Spec:** `docs/superpowers/specs/2026-06-26-nohup-to-docker-deployment-design.md`

**Test strategy note:** 본 작업은 config/docs/ops 이며 애플리케이션 코드 변경 無 → unit test 대상 아님. workflow-rules 통합테스트 금지(#207). 검증 = `docker compose config` 구문 검증 + 런타임 health/prometheus/kafka/DB 확인.

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` | 전환 결정 + trade-offs | 신규 |
| `docker-compose.airflow.yml` | airflow 3컨테이너 autoheal 라벨 | 수정 |
| `docker/services/deploy-apps.sh` | idempotent 배포 + health 검증 스크립트 | 신규 |
| `docs/21_Operations/docker-deploy-runbook.md` | 전환/rollback/검증 체크리스트 | 신규 |

---

## Task 1: ADR-737 작성 (RPI 선행)

**Files:**
- Create: `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md`

- [ ] **Step 1: ADR 파일 작성** (adr-conventions 5섹션 준수)

내용:

````markdown
# ADR-737: nohup → docker compose 배포 전환

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline

---

## 1. Background / Problem

### Background

- 4 active 모듈(external-api/calculator/synchronizer/cleanup)을 nohup 호스트 프로세스로 운영.
- #1245 로 이미지 빌드 완료(`maple/{module}:dev` + `:sha-75cb631`).
- `docker-compose.services.yml` 오버레이 구성 완료(healthcheck + autoheal 라벨 + MinIO SA secret).
- Endurance Test #2(~71h) 종료 직후, 파이프라인 IDLE, 4 포트 FREE.

### Problem

- 운영 통일(autoheal 자동복구, cadvisor 컨테이너 메트릭) 위해 docker 전환 필요.
- autoheal/cadvisor 정의는 있으나 미가동. airflow 컨테이너 autoheal 라벨 누락.

### Goal

- docker compose 로 4 모듈 실배포. autoheal/cadvisor 가동. runbook 문서화.

---

## 2. Decision

> nohup 호스트 프로세스를 docker compose 로 전환한다. prometheus 는 host network + 포트퍼블리시로 mode-agnostic → scrape config 유지.

```text
4 modules: nohup java -jar → docker compose (services.yml overlay)
autoheal: 정의만 존재 → up -d 가동
cadvisor: 정의만 존재 → up -d 가동
airflow 3 containers: autoheal label 추가 후 recreate
```

---

## 3. Trade-offs

### Sensitivity

* 배포 시점(파이프라인 IDLE) — chunk 손실 방지
* 포트 점유 — split-brain 방지 조건
* `:dev` 태그가 최신 빌드 지칭 여부

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| docker 운영 통일 | autoheal 자동복구, cadvisor 메트릭, 통합 관리 | nohup in-process fallback |

### Risk

* 배포 실패 시 rollback — runbook 에 `compose down` → nohup 경로 문서화.
* 카운터 리셋 — Prometheus 누적값 초기화(post-test 허용).

### Non-Risk

* 데이터 손실 — IDLE 시점 전환.
* prometheus config — mode-agnostic, 변경 無.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| 4 모듈 health | 4/4 UP | 배포 후 |
| cadvisor metric 수집 | container_cpu_usage_seconds_total 조회 성공 | 배포 후 |

### Observed Result

* 코드/설정 검증: airflow 라벨 추가 + deploy-apps.sh + runbook 작성. `docker compose config` 구문 PASS.
* 런타임 검증: 배포 실행 후 본 절 실측값 기재.

---

## 5. Summary

> 파이프라인 IDLE 시점에 4 모듈을 docker compose 로 전환, autoheal/cadvisor 가동. prometheus mode-agnostic 으로 config 변경 無.
````

- [ ] **Step 2: commit**

```bash
git add docs/01_ADR/ADR-737_nohup-to-docker-deployment.md
git commit -m "docs(adr): ADR-737 nohup→docker deployment switch

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: airflow autoheal 라벨 추가 (#1429)

**Files:**
- Modify: `docker-compose.airflow.yml` (airflow-db ~L6, airflow-webserver ~L24, airflow-scheduler ~L55)

- [ ] **Step 1: airflow-db 라벨 추가**

`docker-compose.airflow.yml` 의 `airflow-db` 서비스 — `container_name: maple-airflow-db` 다음 줄에 labels 추가:

```yaml
    container_name: maple-airflow-db
    labels:
      autoheal: "true"
    restart: always
```

- [ ] **Step 2: airflow-webserver 라벨 추가**

```yaml
    container_name: maple-airflow-webserver
    labels:
      autoheal: "true"
    restart: unless-stopped
```

- [ ] **Step 3: airflow-scheduler 라벨 추가**

```yaml
    container_name: maple-airflow-scheduler
    labels:
      autoheal: "true"
    restart: unless-stopped
```

- [ ] **Step 4: 구문 검증**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config >/dev/null && echo OK`
Expected: `OK`

- [ ] **Step 5: 라벨 적용 확인 (dry)**

Run: `docker compose -f docker-compose.yml -f docker-compose.airflow.yml config | grep -A1 autoheal | head -20`
Expected: 3개 airflow 서비스 + 기존 infra 서비스 autoheal 라벨 출력

- [ ] **Step 6: commit**

```bash
git add docker-compose.airflow.yml
git commit -m "feat(airflow): add autoheal labels to 3 airflow containers (#1429)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: deploy-apps.sh 스크립트 (#1428/#1429/#1430)

**Files:**
- Create: `docker/services/deploy-apps.sh`

- [ ] **Step 1: 스크립트 작성**

````bash
#!/usr/bin/env bash
# docker/services/deploy-apps.sh
# Deploy 4 Spring Boot app services + autoheal + cadvisor via docker compose.
# Idempotent: safe to re-run. Pre-flight checks ports (split-brain prevention)
# and verifies /actuator/health after start.
#
# Usage: ./docker/services/deploy-apps.sh
set -euo pipefail
cd "$(dirname "$0")/../.."  # repo root

# (1) Pre-flight: ports 8081-8084 must be FREE (no nohup split-brain)
echo "==> Pre-flight: checking ports 8081-8084 are free"
for port in 8081 8082 8083 8084; do
  if pid=$(lsof -ti:"$port" -sTCP:LISTEN 2>/dev/null) && [ -n "$pid" ]; then
    echo "ERROR: port $port occupied (pid $pid) — stop nohup modules first" >&2
    exit 1
  fi
done

# (2) Start 4 app services (maple/{module}:dev is the default tag in services.yml)
echo "==> Starting 4 app services"
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup

# (3) Recreate airflow containers with autoheal labels
echo "==> Recreating airflow with autoheal labels"
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d \
  airflow-webserver airflow-scheduler airflow-db

# (4) Start autoheal + cadvisor
echo "==> Starting autoheal + cadvisor"
docker compose -f docker-compose.yml up -d autoheal cadvisor

# (5) Wait for app health (max ~120s per service)
echo "==> Waiting for app health"
for entry in external-api:8081 calculator:8082 synchronizer:8083 cleanup:8084; do
  name="${entry%%:*}"; port="${entry##*:}"
  ok=0
  for _ in $(seq 1 24); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      ok=1; break
    fi
    sleep 5
  done
  if [ "$ok" -eq 1 ]; then echo "  $name (port $port): UP"; else echo "  $name (port $port): FAILED"; fi
done

# (6) Status table
echo "==> Status"
docker ps --format 'table {{.Names}}\t{{.Status}}' \
  | grep -E 'maple-(external-api|calculator|synchronizer|cleanup|autoheal|cadvisor)|NAMES'
````

- [ ] **Step 2: 실행 권한 부여**

Run: `chmod +x docker/services/deploy-apps.sh`
Expected: no output

- [ ] **Step 3: 구문 검증**

Run: `bash -n docker/services/deploy-apps.sh && echo OK`
Expected: `OK`

- [ ] **Step 4: commit**

```bash
git add docker/services/deploy-apps.sh
git commit -m "feat(docker): add deploy-apps.sh idempotent deploy script (#1428)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: runbook 작성 (#1431)

**Files:**
- Create: `docs/21_Operations/docker-deploy-runbook.md`

- [ ] **Step 1: runbook 작성**

내용(전환/rollback/검증/주의사항):

````markdown
# nohup → docker compose 배포 Runbook (#1428/#1431)

4 active 모듈(external-api/calculator/synchronizer/cleanup)의 nohup ↔ docker 전환 절차.

## 사전 조건

- 파이프라인 **IDLE** 상태 (phase terminal, chunk 처리 중 아님). 처리 중 전환 시 chunk 손실.
- 이미지 빌드 완료: `docker images | grep maple/` → `:dev` + `:sha-XXX` 존재 확인.
- SA secret 존재: `ls docker/services/secrets/sa-*.key` → 4종.
- 인프라 running: `docker ps` → postgres/kafka/redis/minio/prometheus Up.

## 전환 절차 (nohup → docker)

1. **phase terminal 확인**

   ```bash
   curl -s http://localhost:8081/api/internal/run-status | python3 -m json.tool
   # phase=IDLE 또는 terminal=true 인지 확인
   ```

2. **nohup 4 프로세스 stop**

   ```bash
   for p in 8081 8082 8083 8084; do
     pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null)
     [ -n "$pid" ] && kill "$pid"
   done
   ```

3. **포트 free 확인 (split-brain 방지)**

   ```bash
   for p in 8081 8082 8083 8084; do
     pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null)
     echo "$p: ${pid:-FREE}"
   done
   # 전부 FREE 여야 함
   ```

4. **배포 (스크립트 사용 권장)**

   ```bash
   ./docker/services/deploy-apps.sh
   # 또는 수동:
   docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
     external-api calculator synchronizer cleanup
   docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d \
     airflow-webserver airflow-scheduler airflow-db
   docker compose -f docker-compose.yml up -d autoheal cadvisor
   ```

5. **검증** (아래 체크리스트)

## Rollback 절차 (docker → nohup)

1. docker 서비스 중지

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.services.yml down \
     external-api calculator synchronizer cleanup
   ```

2. 포트 free 확인 (위 3번)

3. nohup 재시작

   ```bash
   set -a && source .env && set +a
   MINIO_ACCESS_KEY=<module> MINIO_SECRET_KEY_FILE=$(pwd)/docker/services/secrets/sa-<module>.key \
     nohup java -jar module-<module>/build/libs/module-<module>-0.0.1-SNAPSHOT.jar &
   # 4 모듈 각각
   ```

## 검증 체크리스트

- [ ] 4 모듈 `/actuator/health` → `{"status":"UP"}` (ports 8081-8084)
- [ ] `docker ps` → maple-external-api/calculator/synchronizer/cleanup/autoheal/cadvisor Up
- [ ] autoheal: `docker logs maple-autoheal` → restart 이벤트 (또는 healthy 유지)
- [ ] cadvisor metric: `curl -s http://localhost:8086/metrics | grep container_cpu_usage_seconds_total | head -1`
- [ ] Prometheus: `curl -s 'http://localhost:9090/api/v1/query?query=up' | grep -c '"1"'` ≥ 6
- [ ] Kafka consumer lag: `docker exec maple-kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups` → LAG 처리 가능 범위
- [ ] DB read model row 증가: `psql ... -c "SELECT count(*) FROM character_basic_read_model"` (재측정 시 증가)
- [ ] ERROR 로그: `docker logs maple-<module> 2>&1 | grep ERROR | tail` → 0

## 주의사항

- **Split-brain**: 두 모드 동시 포트 점유 금지. 반드시 nohup stop → 포트 free 확인 후 docker up.
- **카운터 리셋**: 프로세스 전환 시 Prometheus 누적 counter 0 초기화. endurance 누적 데이터는 보고서화(endurance-report-71h.md). 전환 시점 이후부터 재누적.
- **다운타임**: IDLE 시점 전환 시 데이터 손실 0. 처리 중 전환 시 in-flight chunk 손실 가능 → 반드시 IDLE 확인.
- **이미지 태그**: `:dev` 가 최신 빌드 가리킴. reproducible 배포 시 `IMAGE_<MODULE>=maple/<module>:sha-XXX` env 로 고정.
````

- [ ] **Step 2: commit**

```bash
git add docs/21_Operations/docker-deploy-runbook.md
git commit -m "docs(ops): nohup→docker deploy runbook (#1431)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: 운영 배포 실행 (#1428 runtime)

**Files:** (none — operational)

- [ ] **Step 1: 사전 상태 재확인**

Run: `for p in 8081 8082 8083 8084; do pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null); echo "$p: ${pid:-FREE}"; done`
Expected: 전부 `FREE`

- [ ] **Step 2: 배포 스크립트 실행**

Run: `./docker/services/deploy-apps.sh`
Expected: 4 서비스 `UP` + status 테이블에 6 컨테이너(4 svc + autoheal + cadvisor) 표시

- [ ] **Step 3: 실패 시** — runbook rollback 절차 수행 후 원인 조사. 컨테이너 로그: `docker logs maple-<module>`

---

## Task 6: 런타임 검증 (acceptance)

**Files:** (none — verification)

- [ ] **Step 1: 4 모듈 health**

Run: `for p in 8081 8082 8083 8084; do echo -n "$p: "; curl -sf http://localhost:$p/actuator/health || echo FAIL; echo; done`
Expected: 전부 `{"status":"UP",...}`

- [ ] **Step 2: autoheal 동작** (airflow-scheduler unhealthy 자동복구 확인)

Run: `sleep 10 && docker ps --format '{{.Names}} {{.Status}}' | grep airflow-scheduler`
Expected: `(healthy)` (autoheal 이 restart 후 복구) 또는 재시작 진행 중

Run: `docker logs maple-autoheal 2>&1 | grep -i restart | tail`
Expected: restart 이벤트 (scheduler) 또는 "no unhealthy" (이미 healthy)

- [ ] **Step 3: cadvisor metric 수집**

Run: `curl -sf http://localhost:8086/metrics | grep -m1 container_cpu_usage_seconds_total`
Expected: metric 라인 출력

Run: `curl -s --data-urlencode 'query=container_cpu_usage_seconds_total' http://localhost:9090/api/v1/query | python3 -c "import sys,json;d=json.load(sys.stdin);print('series:',len(d['data']['result']))"`
Expected: `series:` ≥ 1 (prometheus 가 cadvisor scrape 성공)

- [ ] **Step 4: Kafka lag**

Run: `KAFKA_CTN=$(docker ps --format '{{.Names}}' | grep -i kafka | head -1); docker exec "$KAFKA_CTN" kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups 2>&1 | awk 'NF>=9 && $1!="GROUP"{print $1, $6}' | sort -u`
Expected: LAG 값 처리 가능 범위 (모듈 기동 직후 일시적 backlog 후 0 수렴 허용)

- [ ] **Step 5: DB row 증가 (파이프라인 흐름)**

Run (2회 측정, 간격 60s):
```bash
set -a && source .env && set +a
H=$(echo "$DB_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
P=$(echo "$DB_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
N=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
U=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
W=$(echo "$DB_URL" | sed -n 's|.*password=\([^&]*\).*|\1|p')
PGPASSWORD="$W" psql "host=$H port=$P user=$U dbname=$N sslmode=disable" -t -A -c "SELECT count(*) FROM character_basic_read_model"
```
Expected: 2회째 값 > 1회째 (증가). 단 파이프라인 idle 시 정체 가능 → ext-api manual trigger 로 검증 가능.

- [ ] **Step 6: ERROR 로그 0**

Run: `for m in external-api calculator synchronizer cleanup; do echo -n "$m: "; docker logs maple-$m 2>&1 | grep -c ERROR || echo 0; done`
Expected: 전부 `0` (또는 기동 boundary error 만 있고 증가 없음)

- [ ] **Step 7: 검증 결과 기록** — ADR-737 §4 Observed Result 에 실측값 반영

---

## Task 7: PR + 이슈 종료

**Files:** (none)

- [ ] **Step 1: develop 동기화 + push**

```bash
git push -u origin feature/nohup-to-docker-deploy
```

- [ ] **Step 2: PR 생성**

```bash
gh pr create --base develop --title "feat(ops): nohup→docker deploy + autoheal/cadvisor (#1428-1431)" --body "..."
```

body: summary + ADR-737/runbook 링크 + 검증 결과(health/kafka/prometheus/DB/error)

- [ ] **Step 3: 머지 후 이슈 종료**

```bash
gh pr merge <N> --merge
gh issue close 1428 1429 1430 1431
```
각 이슈 코멘트에 검증 증거(health UP, cadvisor series 수, kafka lag, DB row, error=0) + runbook 링크.

---

## Self-Review (작성자 자점)

**Spec coverage:** #1428(전환)=T1/T3/T5, #1429(autoheal)=T2/T5, #1430(cadvisor)=T3/T5/T6-S3, #1431(runbook)=T4. 전 섹션 커버.

**Placeholder:** 없음 — 모든 스텝에 실제 코드/명령/expected 포함.

**Type/name 일관성:** 서비스명·포트·컨테이너명 전 태스크 일치(external-api:8081 등).
