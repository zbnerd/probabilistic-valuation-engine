# nohup → docker compose 배포 Runbook (#1428 / #1431)

4 active 모듈(external-api:8081 / calculator:8082 / synchronizer:8083 / cleanup:8084)의 nohup ↔ docker 전환 절차. 반복 전환/rollback 시 참조.

## 사전 조건

- **이미지 빌드**: `docker images | grep maple/` → `:sha-XXX` 존재 (build.sh 가 `:dev` + `:sha-` 둘 부여; `:dev` 부재 시 deploy-apps.sh 가 sha 자동 해석).
- **SA secret**: `ls docker/services/secrets/sa-*.key` → 4종 (ext-api/calculator/synchronizer/cleanup).
- **rollback jar**: `ls module-*/build/libs/*SNAPSHOT.jar` → 4종 (non-plain).
- **인프라 running**: `docker ps` → postgres/kafka/redis/minio Up.

## IDLE 정의 (hard gate)

파이프라인 **IDLE** 상태에서만 전환 (in-flight chunk 손실 방지):

- `calculation_jobs` non-terminal(API_REQUESTED / RETRYING / CALCULATING) = 0
- Kafka consumer LAG = 0

```sql
SELECT count(*) FROM calculation_jobs
WHERE status IN ('API_REQUESTED','RETRYING','CALCULATING');
```

deploy-apps.sh 가 이 값을 pre-flight 로 검사(0 아니면 abort).

## network reconcile (최초 1회 + infra recreate 시마다)

app 컨테이너(services.yml)는 `maple-network` 선언. infra(postgres/kafka/minio)가 동일 network 에 있어야 DNS 해석.

> **현황**: infra 는 project `probabilistic-valuation-engine` 의 `probabilistic-valuation-engine_maple-network` 에 존재, redis 만 `maple-network`. 아래 connect 로 `maple-network` 에 infra 를 추가.

```bash
# --alias 필수: docker network connect 는 컨테이너명(maple-postgres)으로 연결하지만,
# app 서비스는 compose service alias(postgres)로 참조 → alias 없으면 DNS 미해석.
docker network connect --alias postgres maple-network maple-postgres
docker network connect --alias kafka maple-network maple-kafka
docker network connect --alias minio maple-network probabilistic-valuation-engine-minio-1

# 검증 (전부 실제 IP 출력되어야 함 — UNRESOLVED/SERVFAIL 이면 실패)
docker run --rm --network maple-network alpine \
  sh -c 'for h in postgres kafka minio redis; do nslookup "$h"; done'
```

> 이미 alias 없이 연결된 경우: `docker network disconnect maple-network <ctn>` 후 위 `--alias` 명령으로 재연결.

**주의 (비영속)**: `docker network connect` 는 `restart: always` 재시작에는 유지되나 **`docker rm` + recreate (예: `compose down && up` 또는 `--force-recreate`) 시 상실**. infra recreate 후 본 절 재실행.

## 전환 절차 (nohup → docker)

1. **IDLE 확인** (위 쿼리 = 0).
2. **network reconcile 완료** (위 절차, DNS 검증).
3. **nohup 4 프로세스 stop** (graceful):
   ```bash
   for p in 8081 8082 8083 8084; do
     pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null)
     [ -n "$pid" ] && kill -TERM "$pid"
   done
   # 잔존 시 graceful timeout 후 kill -KILL
   ```
4. **포트 free 확인**:
   ```bash
   for p in 8081 8082 8083 8084; do
     pid=$(lsof -ti:$p -sTCP:LISTEN 2>/dev/null); echo "$p: ${pid:-FREE}"
   done
   # 전부 FREE 여야 함
   ```
5. **배포** (스크립트 권장):
   ```bash
   ./docker/services/deploy-apps.sh
   ```
   스크립트가 tag 해석 + pre-flight(port/DNS/secret/jar/IDLE) + `compose up` + health 폴링 수행.
6. **검증** (아래 체크리스트).

## Rollback 절차 (docker → nohup)

1. docker app 서비스 중지:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.services.yml down \
     external-api calculator synchronizer cleanup
   ```
2. 포트 free 확인 (위 4번).
3. nohup 재시작 (`.env` source 후 모듈별 env):
   ```bash
   set -a && source .env && set +a
   for mod in external-api calculator synchronizer cleanup; do
     sa=$( [ "$mod" = external-api ] && echo ext-api || echo "$mod" )
     MINIO_ACCESS_KEY="$sa" \
     MINIO_SECRET_KEY_FILE="$(pwd)/docker/services/secrets/sa-${sa}.key" \
     nohup java -jar "module-${mod}/build/libs/module-${mod}-0.0.1-SNAPSHOT.jar" \
       > "/tmp/${mod}.log" 2>&1 &
   done
   # NEXON_API_KEY / KAFKA_BOOTSTRAP_SERVERS / DB_URL / SPRING_PROFILES_ACTIVE 등은
   # .env 에서 source 되어 전파 (nohup 프로세스가 host .env 환경 사용).
   ```

## 검증 체크리스트

- [ ] 4 모듈 `/actuator/health` → `{"status":"UP"}` (ports 8081-8084)
- [ ] `docker ps` → maple-{external-api,calculator,synchronizer,cleanup,autoheal,cadvisor} Up
- [ ] autoheal: throwaway unhealthy 컨테이너 → 재시작 + app 컨테이너 무영향 (false positive 없음)
- [ ] cadvisor metric: `curl -s http://localhost:8086/metrics | grep -m1 container_cpu_usage_seconds_total`
- [ ] Prometheus: `container_cpu_usage_seconds_total` series ≥ 1
- [ ] Kafka consumer LAG: 처리 가능 범위 (기동 직후 일시 backlog 후 0 수렴 허용)
- [ ] DB read model row 증가 (manual trigger 후 — 아래)
- [ ] ERROR 로그 0: `docker logs maple-<module> 2>&1 | grep -c ERROR`

## end-to-end 검증 (manual trigger)

IDLE 상태에서 DB row 증가를 확인하려면 강제 트리거로 파이프라인 전 모듈 흐름(ext-api → kafka → calculator → synchronizer → DB)을 exercise:

```bash
curl -s -w "\nHTTP %{http_code}\n" "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
# 202 접수 후 60s 간격으로 read model row 2회 측정 → 증가 확인
```

## 주의사항

- **Split-brain**: 두 모드가 동시 포트 점유 금지. 반드시 nohup stop → 포트 free 확인 후 docker up.
- **카운터 리셋**: 프로세스 전환 시 Prometheus 누적 counter 0 초기화. endurance 누적 데이터는 `docs/endurance-test/endurance-report-71h.md` 에 보존. 전환 시점 이후부터 재누적.
- **다운타임**: IDLE 시점 전환 시 데이터 손실 0. 처리 중 전환 시 in-flight chunk 손실 가능 → 반드시 IDLE 확인.
- **network 비영속**: infra recreate 시 `docker network connect` 재실행 (위).
- **이미지 태그**: 기본 `:dev`(services.yml). 부재 시 deploy-apps.sh 가 최신 `:sha-*` 자동 해석. reproducible 고정 필요 시 `IMAGE_<MODULE>=maple/<module>:sha-XXX` env 수동 지정.
- **cadvisor `/dev/kmsg`**: 일부 호스트에서 device 마운트 실패 시 `docker-compose.yml` cadvisor `devices:` 블록 주석화 후 re-up (본 호스트는 `/dev/kmsg` 존재, 정상).
- **airflow autoheal**: 본 runbook 미포함. airflow compose 는 bridge 선언이나 실제 host network 로 구동 → recreate 시 topology 변형 위험. host/bridge network reconcile 선행 필요 → 별도 follow-up 이슈.

## 관련

- ADR-731 (autoheal/coolify self-healing), ADR-733 (cadvisor/observability), ADR-737 (본 전환)
- spec: `docs/superpowers/specs/2026-06-26-nohup-to-docker-deployment-design.md`
- plan: `docs/superpowers/plans/2026-06-26-nohup-to-docker-deployment.md`
