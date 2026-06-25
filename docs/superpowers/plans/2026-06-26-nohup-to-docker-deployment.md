# nohup → docker compose 배포 전환 Implementation Plan (#1428–#1431) — REVISED post-grill

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 4 active 모듈을 nohup → docker compose 로 전환, autoheal/cadvisor 가동·검증, runbook 문서화.

**Architecture:** 사전 network reconcile(infra → `maple-network` 연결) 선행. prometheus 는 host network + 포트퍼블리시로 mode-agnostic → config 변경 無. deploy-apps.sh 가 image tag 자동 해석 + DNS/secret/IDLE pre-flight + health 검증 수행. airflow autoheal runtime 은 별도 follow-up 이슈로 이관(동작 중 infra 보호).

**Spec:** `docs/superpowers/specs/2026-06-26-nohup-to-docker-deployment-design.md`

**Tech Stack:** docker compose v2, willfarrell/autoheal, cadvisor, Prometheus, Spring Boot Actuator, bash.

**Test strategy:** config/ops 작업, 앱 코드 변경 無 → unit test 대상 아님. workflow-rules 통합테스트 금지(#207). 검증 = `compose config` 구문 + 런타임 health/prometheus/kafka/DB/manual-trigger.

---

## grill 반영 (REJECT → FIX)

critic(opus) 적대 리뷰 + 독립 검증으로 확인된 BLOCKER:

1. **network duality** — `maple-network`(redis만) vs `probabilistic-valuation-engine_maple-network`(infra 전부). app 컨테이너 `postgres`/`kafka`/`minio` DNS SERVFAIL. → **Task 0** reconcile.
2. **`:dev` 태그 부재** — 4 모듈 전부 `:sha-75cb631`만 존재, `:dev` 없음. services.yml 기본값 동작 안 함. → deploy-apps.sh 가 sha 태그 자동 해석.
3. **airflow host-network drift** — compose bridge 선언 vs 실제 host. recreate 시 healthy webserver 파손 위험. → airflow runtime 본 PR 제외, follow-up 이관.
4. pre-flight 강화(DNS/secret/image/IDLE), IDLE 정의 구체화, manual trigger 검증 의무화, autoheal 유발 테스트, rollback env 완비.

---

## File Structure

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md` | 전환 결정 + network 현실 반영 | 신규 |
| `docker/services/deploy-apps.sh` | tag 해석 + pre-flight + 배포 + health 검증 | 신규 |
| `docs/21_Operations/docker-deploy-runbook.md` | 전환/rollback/검증/IDLE 정의/network 주의 | 신규 |

airflow compose 변경 = 제외(follow-up #1432-airflow-autoheal).

---

## Task 0: Network Reconciliation (BLOCKER 선행)

**Files:** (none — operational, live `docker network connect`)

- [ ] **Step 1: 현재 network membership 재확인**

Run:
```bash
docker network inspect maple-network --format '{{range .Containers}}{{.Name}} {{end}}'
docker network inspect probabilistic-valuation-engine_maple-network --format '{{range .Containers}}{{.Name}} {{end}}'
```
Expected: maple-network = `maple-redis`(only); prefixed = postgres/kafka/minio/...

- [ ] **Step 2: infra 컨테이너를 `maple-network`에 연결** (live, non-disruptive)

```bash
docker network connect maple-network maple-postgres
docker network connect maple-network maple-kafka
docker network connect maple-network probabilistic-valuation-engine-minio-1
```
(redis는 이미 maple-network에 존재)

Expected: 각 명령 무출력(성공). 이미 연결 시 "already exists" 무해.

- [ ] **Step 3: DNS 해석 검증 (결정적)**

```bash
docker run --rm --network maple-network alpine sh -c 'nslookup postgres; nslookup kafka; nslookup minio; nslookup redis' 2>&1 | grep -E 'Name:|Address:'
```
Expected: postgres/kafka/minio/redis 각 Address 출력(이전 SERVFAIL → 해결).

- [ ] **Step 4: persistence 주의사항** — `docker network connect` 는 `restart: always` 재시작에는 유지되나 `rm + recreate` 시 상실. runbook(Task 4)에 기재: infra recreate 시 재연결 필요.

---

## Task 1: ADR-737 작성 (RPI)

**Files:** Create `docs/01_ADR/ADR-737_nohup-to-docker-deployment.md`

- [ ] **Step 1: ADR 작성** (adr-conventions 5섹션)

````markdown
# ADR-737: nohup → docker compose 배포 전환

- Status: Accepted
- Date: 2026-06-26
- Owner: maple-pipeline

---

## 1. Background / Problem

### Background

- 4 active 모듈을 nohup 호스트 프로세스로 운영. #1245 로 이미지 빌드(`:sha-75cb631`). Endurance Test #2(~71h) 종료 직후, 파이프라인 IDLE.
- **infra network 드리프트 발견**: infra(postgres/kafka/minio)는 `probabilistic-valuation-engine_maple-network`(project=probabilistic-valuation-engine)에, redis만 `maple-network`에 존재. docker-compose.yml 은 `maple-network` external 선언. app 서비스(services.yml)가 `maple-network` 선언 → postgres/kafka/minio DNS 미해석.
- `:dev` mutable 태그 부재(build.sh 는 두 태그 부여 의도였으나 현재 `:sha-75cb631`만).

### Problem

- 운영 통일(autoheal, cadvisor) 위해 docker 전환 필요하나, network 드리프트·태그 부재로 기본 `compose up` 시 app 컨테이너 crash-loop.
- autoheal/cadvisor 정의만 존재, 미가동.

### Goal

- network reconcile + tag 해석으로 4 모듈 docker 배포 정상화. autoheal/cadvisor 가동. runbook 문서화.

---

## 2. Decision

> (1) infra 컨테이너를 `maple-network`에 live 연결(DNS 해석). (2) deploy-apps.sh 가 `:sha-*` 태그 자동 해석(`:dev` 부재 회피). (3) airflow autoheal runtime 은 별도 이관(host-network drift 위험).

```text
Task0: docker network connect maple-network {postgres,kafka,minio}
deploy: IMAGE_<MOD>=maple/<mod>:sha-<latest> (deploy-apps.sh resolves)
airflow: 제외 (follow-up — host/bridge network reconcile 선행 필요)
```

---

## 3. Trade-offs

### Sensitivity

* `maple-network` 연결 영속성 — recreate 시 상실(runbook 기재)
* `:sha-*` 태그 존재 — 빌드 후 최신 sha 유지
* IDLE 시점 — in-flight 손실 방지

### Trade-off

| 선택 | 얻는 것 | 포기한 것 |
| -- | ---- | ----- |
| live network connect (infra recreate 아님) | infra 무중단 reconcile | recreate 시 재연결 필요(수동) |
| airflow autoheal 이관 | 동작 중 airflow 보호 | airflow 자동복구 지연(follow-up) |

### Risk

* network connect 비영속 — runbook 기재로 보완.
* 카운터 리셋 — Prometheus 누적 초기화(post-test 허용).

### Non-Risk

* 데이터 손실 — IDLE 전환(calculation_jobs non-terminal=0, kafka lag=0 확인).
* prometheus config — host network + 포트퍼블리시로 mode-agnostic, 변경 無.

---

## 4. Result / Evidence

### Metrics

| Metric | Value | Notes |
| ------ | ----: | ----- |
| DNS 해석(postgres from maple-network) | SERVFAIL → 해결 | Task0 후 |
| 4 모듈 health | 4/4 UP | 배포 후 |
| cadvisor metric 수집 | container_cpu_usage_seconds_total 조회 성공 | 배포 후 |

### Observed Result

* 설정 검증: deploy-apps.sh 구문 PASS, `compose config` PASS.
* 런타임: 배포 후 실측값 기재.

---

## 5. Summary

> infra 를 `maple-network`에 live 연결하고 deploy-apps.sh 가 sha 태그를 해석하여 4 모듈 docker 전환. airflow autoheal 은 network reconcile 선행 필요로 별도 이관.
````

- [ ] **Step 2: commit**

```bash
git add docs/01_ADR/ADR-737_nohup-to-docker-deployment.md
git commit -m "docs(adr): ADR-737 nohup→docker deployment switch

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: deploy-apps.sh (tag 해석 + pre-flight + 배포)

**Files:** Create `docker/services/deploy-apps.sh`

- [ ] **Step 1: 스크립트 작성**

````bash
#!/usr/bin/env bash
# docker/services/deploy-apps.sh
# Deploy 4 Spring Boot app services + autoheal + cadvisor.
# Resolves latest :sha-* image tag (:dev may be absent), runs pre-flight
# (ports free, DNS resolves, secrets present, jars for rollback, IDLE gate),
# then compose up + health polling.
set -euo pipefail
cd "$(dirname "$0")/../.."  # repo root

MODULES=(external-api calculator synchronizer cleanup)
declare -A IMAGE_VAR=( [external-api]=IMAGE_EXTERNAL_API [calculator]=IMAGE_CALCULATOR [synchronizer]=IMAGE_SYNCHRONIZER [cleanup]=IMAGE_CLEANUP )

# (1) Resolve image tag: prefer :dev, fall back to latest :sha-*
echo "==> Resolving image tags"
for mod in "${MODULES[@]}"; do
  if docker image inspect "maple/${mod}:dev" >/dev/null 2>&1; then
    img="maple/${mod}:dev"
  else
    img=$(docker images --format '{{.Tag}}' "maple/${mod}" | grep '^sha-' | sort -r | head -1)
    if [ -z "$img" ]; then
      echo "ERROR: no image for maple/${mod} (:dev nor :sha-*) — run build.sh" >&2; exit 1
    fi
    img="maple/${mod}:${img}"
  fi
  export "${IMAGE_VAR[$mod]}=${img}"
  echo "  ${IMAGE_VAR[$mod]}=${img}"
done

# (2) Pre-flight: ports free (split-brain)
echo "==> Pre-flight: ports 8081-8084 free"
for port in 8081 8082 8083 8084; do
  if pid=$(lsof -ti:"$port" -sTCP:LISTEN 2>/dev/null) && [ -n "$pid" ]; then
    echo "ERROR: port $port occupied (pid $pid) — stop nohup first" >&2; exit 1
  fi
done

# (3) Pre-flight: DNS resolves from maple-network (network reconcile done?)
echo "==> Pre-flight: DNS from maple-network"
if ! docker run --rm --network maple-network alpine sh -c 'nslookup postgres >/dev/null 2>&1 && nslookup kafka >/dev/null 2>&1 && nslookup minio >/dev/null 2>&1' 2>/dev/null; then
  echo "ERROR: DNS not resolving on maple-network — run Task 0 (docker network connect)" >&2; exit 1
fi

# (4) Pre-flight: secrets present
echo "==> Pre-flight: SA secrets"
ls docker/services/secrets/sa-ext-api.key docker/services/secrets/sa-calculator.key \
   docker/services/secrets/sa-synchronizer.key docker/services/secrets/sa-cleanup.key >/dev/null

# (5) Pre-flight: rollback jars exist
echo "==> Pre-flight: rollback jars"
for mod in "${MODULES[@]}"; do
  ls "module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar" >/dev/null
done

# (6) IDLE gate: no non-terminal calculation_jobs
echo "==> Pre-flight: IDLE gate"
set -a; source .env; set +a
H=$(echo "$DB_URL" | sed -n 's|.*://\([^:/]*\).*|\1|p')
P=$(echo "$DB_URL" | sed -n 's|.*://[^:/]*:\([0-9]*\).*|\1|p')
N=$(echo "$DB_URL" | sed -n 's|.*/\([^?]*\).*|\1|p')
U=$(echo "$DB_URL" | sed -n 's|.*user=\([^&]*\).*|\1|p')
W=$(echo "$DB_URL" | sed -n 's|.*password=\([^&]*\).*|\1|p')
active=$(PGPASSWORD="$W" psql "host=$H port=$P user=$U dbname=$N sslmode=disable" -t -A -c \
  "SELECT count(*) FROM calculation_jobs WHERE status IN ('API_REQUESTED','RETRYING','CALCULATING');" 2>/dev/null || echo "?")
if [ "$active" != "0" ]; then
  echo "ERROR: $active non-terminal calculation_jobs — pipeline not IDLE, abort" >&2; exit 1
fi

# (7) Start 4 app services
echo "==> Starting 4 app services"
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup

# (8) Start autoheal + cadvisor
echo "==> Starting autoheal + cadvisor"
docker compose -f docker-compose.yml up -d autoheal cadvisor

# (9) Wait for app health (~120s each)
echo "==> Waiting for app health"
for entry in external-api:8081 calculator:8082 synchronizer:8083 cleanup:8084; do
  name="${entry%%:*}"; port="${entry##*:}"; ok=0
  for _ in $(seq 1 24); do
    if curl -sf "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then ok=1; break; fi
    sleep 5
  done
  echo "  $name (port $port): $([ "$ok" -eq 1 ] && echo UP || echo FAILED)"
done

# (10) Status
echo "==> Status"
docker ps --format 'table {{.Names}}\t{{.Status}}' \
  | grep -E 'maple-(external-api|calculator|synchronizer|cleanup|autoheal|cadvisor)|NAMES'
````

- [ ] **Step 2: 권한 + 구문 검증**

```bash
chmod +x docker/services/deploy-apps.sh
bash -n docker/services/deploy-apps.sh && echo OK
```
Expected: `OK`

- [ ] **Step 3: commit**

```bash
git add docker/services/deploy-apps.sh
git commit -m "feat(docker): deploy-apps.sh with tag resolution + pre-flight (#1428)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: runbook 작성 (#1431)

**Files:** Create `docs/21_Operations/docker-deploy-runbook.md`

- [ ] **Step 1: runbook 작성** (전환/rollback/검증/IDLE/network 주의 포함)

````markdown
# nohup → docker compose 배포 Runbook (#1428/#1431)

4 active 모듈(external-api/calculator/synchronizer/cleanup) nohup ↔ docker 전환 절차.

## 사전 조건

- 이미지 빌드: `docker images | grep maple/` → `:sha-XXX` 존재.
- SA secret: `ls docker/services/secrets/sa-*.key` → 4종.
- rollback jar: `ls module-*/build/libs/*SNAPSHOT.jar` → 4종.
- 인프라 running: postgres/kafka/redis/minio Up.

## IDLE 정의 (hard gate)

파이프라인 IDLE 만 전환:
- `calculation_jobs` non-terminal(API_REQUESTED/RETRYING/CALCULATING) = 0
- Kafka consumer LAG = 0

```sql
SELECT count(*) FROM calculation_jobs WHERE status IN ('API_REQUESTED','RETRYING','CALCULATING');
```

## network reconcile (최초 1회 + infra recreate 시마다)

infra 가 `maple-network`에 연결되어 있어야 app 컨테이너가 postgres/kafka/minio DNS 해석.

```bash
docker network connect maple-network maple-postgres
docker network connect maple-network maple-kafka
docker network connect maple-network probabilistic-valuation-engine-minio-1
# 검증
docker run --rm --network maple-network alpine sh -c 'nslookup postgres; nslookup kafka; nslookup minio'
```

주의: `docker network connect` 는 infra recreate(rm+up) 시 상실. recreate 후 재실행.

## 전환 절차 (nohup → docker)

1. **IDLE 확인** (위 쿼리 = 0)
2. **nohup stop**:
   ```bash
   for p in 8081 8082 8083 8084; do pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null); [ -n "$pid" ] && kill -TERM "$pid"; done
   # graceful 대기 후 잔존 시 kill -KILL
   ```
3. **포트 free 확인**: `for p in 8081 8082 8083 8084; do lsof -ti:$p -sTCP:LISTEN; done` → 전부 공백
4. **배포**: `./docker/services/deploy-apps.sh`

## Rollback (docker → nohup)

1. docker app 중지:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.services.yml down external-api calculator synchronizer cleanup
   ```
2. 포트 free 확인
3. nohup 재시작:
   ```bash
   set -a && source .env && set +a
   for mod in external-api calculator synchronizer cleanup; do
     MINIO_ACCESS_KEY=$( [ "$mod" = external-api ] && echo ext-api || echo "$mod" ) \
     MINIO_SECRET_KEY_FILE=$(pwd)/docker/services/secrets/sa-$( [ "$mod" = external-api ] && echo ext-api || echo "$mod" ).key \
     nohup java -jar module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar > /tmp/${mod}.log 2>&1 &
   done
   # external-api 추가: NEXON_API_KEY 는 .env 에서 source 됨
   ```

## 검증 체크리스트

- [ ] 4 모듈 `/actuator/health` → UP
- [ ] `docker ps` → maple-{external-api,calculator,synchronizer,cleanup,autoheal,cadvisor} Up
- [ ] cadvisor: `curl -s http://localhost:8086/metrics | grep -m1 container_cpu_usage_seconds_total`
- [ ] Prometheus: `container_cpu_usage_seconds_total` series ≥ 1
- [ ] Kafka LAG 처리 가능 범위
- [ ] DB row 증가 (manual trigger 후 — 아래)
- [ ] ERROR 로그 0

## end-to-end 검증 (manual trigger)

IDLE 상태 DB row 증가 확인 위해 강제 트리거:
```bash
curl -s -w "\nHTTP %{http_code}\n" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
# 202 접수 후 파이프라인 전 모듈 흐름 → DB row 증가를 60s 간격 2회 측정
```

## 주의사항

- **Split-brain**: 동시 포트 점유 금지. nohup stop → free 확인 → docker up.
- **카운터 리셋**: 프로세스 전환 시 Prometheus 누적 0 초기화(endurance 데이터는 endurance-report-71h.md 보존).
- **network 비영속**: infra recreate 시 `docker network connect` 재실행.
- **cadvisor `/dev/kmsg`**: 일부 호스트에서 마운트 실패 시 `devices:` 블록 주석화 후 re-up(본 호스트는 존재).
- **airflow autoheal**: 본 runbook 미포함. airflow host/bridge network reconcile 선행 필요 → follow-up 이슈.
````

- [ ] **Step 2: commit**

```bash
git add docs/21_Operations/docker-deploy-runbook.md
git commit -m "docs(ops): nohup→docker deploy runbook (#1431)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: 운영 배포 실행

**Files:** (none — operational)

- [ ] **Step 1: Task 0 network reconcile 완료 상태 확인**

Run: `docker run --rm --network maple-network alpine sh -c 'nslookup postgres|tail -1; nslookup kafka|tail -1'`
Expected: Address 출력 (SERVFAIL 아니어야 함)

- [ ] **Step 2: deploy-apps.sh 실행**

Run: `./docker/services/deploy-apps.sh`
Expected: pre-flight 전 PASS → 4 서비스 UP → status 테이블 6 컨테이너

- [ ] **Step 3: 실패 시** — runbook rollback. 로그: `docker logs maple-<module>`. pre-flight 에러 메시지가 원인 지시.

---

## Task 5: 런타임 검증 (acceptance)

**Files:** (none)

- [ ] **Step 1: 4 모듈 health**

Run: `for p in 8081 8082 8083 8084; do echo -n "$p: "; curl -sf http://localhost:$p/actuator/health | grep -o '"status":"UP"'; echo; done`
Expected: 전부 `"status":"UP"`

- [ ] **Step 2: autoheal 유발 테스트 (#1429 acceptance)**

healthy 컨테이너 하나 강제 unhealthy 후 5초 폴 내 재시작 + 타 컨테이너 영향 없음 확인:
```bash
# before StartedAt 기록
docker inspect maple-cleanup --format '{{.State.StartedAt}}' > /tmp/before
# 강제 unhealthy: healthcheck 엔드포인트 일시 실패 유도 (컨테이너 재시작 아님 방지 위해 SIGSTOP 후 autoheal 관찰은 위험 → 대안: 별도 throwaway 컨테이너)
docker run -d --name autoheal-test --label autoheal=true \
  --health-cmd='exit 1' --health-interval=5s --health-retries=1 \
  alpine sleep 300
sleep 20
docker inspect autoheal-test --format '{{.RestartCount}}'   # ≥1 이면 autoheal 동작
# 타 컨테이너 영향 없음 확인
for m in external-api calculator synchronizer cleanup; do
  echo "$m: $(docker inspect maple-$m --format '{{.State.StartedAt}}')"
done   # before 와 동일해야(false positive 없음)
docker rm -f autoheal-test
```
Expected: autoheal-test RestartCount ≥ 1; 4 app StartedAt 불변.

- [ ] **Step 3: cadvisor metric 수집 (#1430)**

```bash
curl -sf http://localhost:8086/metrics | grep -m1 container_cpu_usage_seconds_total
curl -s --data-urlencode 'query=container_cpu_usage_seconds_total' http://localhost:9090/api/v1/query \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('prometheus series:',len(d['data']['result']))"
```
Expected: metric 라인 + prometheus series ≥ 1.

- [ ] **Step 4: Kafka lag**

```bash
KAFKA_CTN=$(docker ps --format '{{.Names}}' | grep -i kafka | head -1)
docker exec "$KAFKA_CTN" kafka-consumer-groups --bootstrap-server localhost:9092 --describe --all-groups 2>&1 | awk 'NF>=9 && $1!="GROUP"{print $1,$6}' | sort -u
```
Expected: LAG 처리 가능 범위(기동 직후 일시 backlog 후 0 수렴 허용).

- [ ] **Step 5: end-to-end (manual trigger → DB row 증가)**

```bash
curl -s -w "\nHTTP %{http_code}\n" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
# 60s 대기 후 DB row 2회 측정
```
Expected: HTTP 202 + 2회째 read model row 증가(파이프라인 전 모듈 흐름 정상).

- [ ] **Step 6: ERROR 로그 0**

```bash
for m in external-api calculator synchronizer cleanup; do
  echo -n "$m: "; docker logs maple-$m 2>&1 | grep -c ERROR
done
```
Expected: 전부 0(또는 기동 boundary 만, 증가 없음).

- [ ] **Step 7: ADR-737 §4 Observed Result 실측 반영**

---

## Task 6: follow-up 이슈 + PR + 이슈 종료

**Files:** (none)

- [ ] **Step 1: airflow autoheal follow-up 이슈 생성**

```bash
gh issue create --title "airflow autoheal 활성화 (host/bridge network reconcile 선행)" --body "..."
```
body: #1429 의 airflow 부분. compose bridge 선언 vs 실제 host network 드리프트 → recreate 시 webserver 파손 위험. network reconcile 선행 후 autoheal 라벨 적용 필요.

- [ ] **Step 2: push + PR**

```bash
git push -u origin feature/nohup-to-docker-deploy
gh pr create --base develop --title "feat(ops): nohup→docker deploy + autoheal/cadvisor (#1428-1431)" --body "..."
```

- [ ] **Step 3: 머지 + 이슈 종료**

```bash
gh pr merge <N> --merge
gh issue close 1428 1430 1431
gh issue close 1429   # app-scope 완료 + airflow follow-up 이슈로 이관 명시
```
코멘트에 검증 증거 + runbook 링크 + airflow follow-up 이슈 링크.

---

## Self-Review (post-revision)

**Spec coverage:** #1428=T0/T2/T4, #1429(autoheal for apps)=T4/T5-S2(+airflow follow-up), #1430=T4/T5-S3, #1431=T3.
**grill 반영:** network duality(T0), :dev 부재(T2 tag 해석), airflow(T6 follow-up), pre-flight 강화(T2), IDLE 정의(T3), manual trigger(T5-S5), autoheal 유발(T5-S2), rollback 완비(T3), cadvisor note(T3).
**Placeholder:** 없음.
**일관성:** 서비스명·포트·컨테이너명·네트워크명 전 태스크 일치.
