# probabilistic-valuation-engine 운영 가이드 (Runbook)

> **Last Updated:** 2026-03-26
> **Documentation Version:** 2.0 (V5 Migration)
> **Production Status:** Active (Validated through P0/P1 incident responses)

## Terminology

| 용어 | 정의 |
|------|------|
| **ExternalServiceException** | 넥슨 API 장애 예외 |
| **RateLimitExceededException** | API 호출 한도 초과 예외 |
| **Circuit Breaker** | 장애 확산 방지 패턴 |
| **Graceful Degradation** | 장애 시 서비스 가용성 유지 |
| **PostgreSQL Advisory Lock** | PostgreSQL 내장 락 메커니즘 (V5) |
| **PGMQ** | PostgreSQL 기반 메시지 큐 (V5) |

## Documentation Integrity Statement

This runbook is based on **production incident response** from 2025-2026:
- P0 incidents: 23 resolution procedures validated (Evidence: [P0 Report](../05_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- P1 nightmare issues: 7 distributed system problems resolved (Evidence: [P1 Report](../05_Reports/P1_Nightmare_Issues_Resolution_Report.md))
- Graceful shutdown: 100% data preservation during deployments (Evidence: [ADR-008](../01_ADR/ADR-008-durability-graceful-shutdown.md))
- **V5 Migration (2026-03)**: PostgreSQL, PGMQ, Advisory Lock adoption (Evidence: [ADR-027](../01_ADR/ADR-027-v5-postgresql-pgmq-migration.md))

---

## 1. 장애 대응 매뉴얼

### 1.1 ExternalServiceException (Nexon API 장애)

> **Production Frequency:** Average 2-3 incidents/month (Nexon API maintenance)
> **MTTR Target:** < 5 minutes (cache provides 15-minute stale data)
> **Validation:** Scenario A/B/C tested in N05, N06 chaos tests (Evidence: [Chaos Results](../02_Chaos_Engineering/06_Nightmare/Results/)).

**증상:**
- 로그: `[ERROR] ExternalServiceException: Nexon API call failed`
- 영향: 캐릭터 조회, 장비 조회 실패

**조치:**
1. Nexon API 상태 확인: https://openapi.nexon.com/
2. Circuit Breaker 상태 확인: `/actuator/health`
3. 임시 조치: 캐시된 데이터 반환 (Graceful Degradation)

### 1.2 RateLimitExceededException (429)

**증상:**
- HTTP 429 응답
- 헤더: `Retry-After: {seconds}`

**조치:**
1. 클라이언트에게 재시도 안내
2. IP/User별 요청량 모니터링
3. 필요 시 Rate Limit 임계값 조정

### 1.3 PostgreSQL 장애 (V5 Migration)

> **Production Frequency:** PostgreSQL primary/replica failover (rare)
> **MTTR Target:** < 5 minutes (connection pool refresh)
> **Validation:** Testcontainers integration tests pass

**증상:**
- 로그: `[ERROR] Connection to PostgreSQL refused`
- 영향: 모든 서비스 장애 (DB 의존)

**조치:**
1. PostgreSQL 상태 확인
   ```bash
   docker ps | grep postgres
   docker logs maple-postgres
   ```
2. Connection pool 상태 확인
   ```bash
   curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
   curl http://localhost:8080/actuator/metrics/hikaricp.connections.max
   ```
3. 즉시: Application 재시작 (connection pool refresh)
4. 근본: PostgreSQL 서버 상태 점검
5. 영구: Connection pool 설정 검토 (`maximum-pool-size`)

### 1.4 Connection Pool 고갈 (V5 Migration)

**증상:**
- 로그: `HikariPool-1 - Connection is not available`
- 영향: Request timeout, 서비스 느려짐

**조치:**
1. Connection pool metrics 확인
   ```bash
   curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
   curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
   ```
2. 즉시: Application 재시작 (connection leak 해제)
3. 설정: `maximum-pool-size` 증가 (기본값 10 → 20)
4. 근본: Connection leak 원인 코드 수정

### 1.5 PGMQ 큐 적체 (V5 Migration)

> **Production Frequency:** Worker 속도 저하 시 발생
> **MTTR Target:** < 10 minutes (worker scale-out)
> **Validation:** PGMQ integration tests pass

**증상:**
- 로그: `[WARN] PGMQ queue depth high`
- 영향: 좋아요 동기화 지연, 기부 알림 지연

**조치:**
1. Queue depth 확인
   ```sql
   SELECT queue_name, count(*) FROM pgmq.metrics GROUP BY queue_name;
   ```
2. Worker 상태 확인 (CPU, 메모리)
3. Worker scale-out (환경 변수로 worker 수 증가)
4. 실패 메시지 재시도 (DLQ 처리)

## 2. 모니터링 체크리스트

### 2.1 Health Check 엔드포인트
- [ ] `/actuator/health` - 서비스 상태
- [ ] `/actuator/prometheus` - 메트릭
- [ ] `/actuator/info` - 앱 정보

### 2.2 핵심 메트릭

| 메트릭 | 정상 범위 | 경고 임계값 |
|--------|----------|------------|
| `http_server_requests_seconds` | < 500ms | > 1s |
| `cache.hit{layer=L1}` | > 80% | < 60% |
| `cache.hit{layer=L2}` | > 90% | < 70% |
| `hikaricp.connections.active` | < 80% pool | > 90% pool |
| `postgres_advisory_lock_wait_seconds` | < 50ms | > 100ms |
| `pgmq_queue_depth` | < 100 | > 1000 |

### 2.3 JaCoCo 커버리지 리포트
- 로컬: `build/reports/jacoco/test/html/index.html`
- 목표: 핵심 서비스 60% 이상

## 3. 배포 체크리스트

### 3.1 배포 전
- [ ] `./gradlew clean test` All Green
- [ ] JaCoCo 커버리지 확인
- [ ] 환경변수 확인 (API Key, DB 연결정보)

### 3.2 배포 후
- [ ] `/actuator/health` 정상 응답
- [ ] 로그 에러 없음 확인
- [ ] 주요 API 응답 시간 정상

## 4. 긴급 연락처

- 운영팀: Discord #ops-alerts
- 온콜: 당직 담당자

## 5. 롤백 절차

### 5.1 Docker 환경
```bash
# 이전 버전으로 롤백
docker-compose down
docker-compose -f docker-compose.rollback.yml up -d
```

### 5.2 Kubernetes 환경
```bash
# 이전 리비전으로 롤백
kubectl rollout undo deployment/maple-expectation
```

## Evidence Links
- **GlobalExceptionHandler:** `src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java` (Evidence: [CODE-ERROR-001])
- **DiscordAlertService:** `src/main/java/maple/expectation/service/v2/alert/DiscordAlertService.java` (Evidence: [CODE-ALERT-001])
- **Actuator Config:** `src/main/resources/application.yml` (management 섹션) (Evidence: [CONF-ACTUATOR-001])
- **P0 Report:** `docs/05_Reports/P0_Issues_Resolution_Report_2026-01-20.md` (Incident response validation)

## Technical Validity Check

This runbook would be invalidated if:
- **Incident response procedures don't work**: Compare with actual production logs
- **Rollback procedures don't match environment**: Verify deployment environment
- **Metrics not collected**: Verify Actuator endpoints and Prometheus targets
- **Discord alerts not firing**: Test alert endpoint
- **PostgreSQL metrics unavailable**: Check `postgres_exporter` is running

### Verification Commands
```bash
# Actuator 엔드포인트 확인
curl http://localhost:8080/actuator/health

# 메트릭 확인
curl http://localhost:8080/actuator/metrics

# Discord 알림 설정 확인
grep -A 10 "discord" src/main/resources/application-*.yml

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

### Related Evidence
- P0 Report: `docs/05_Reports/P0_Issues_Resolution_Report_2026-01-20.md`
- P1 Report: `docs/05_Reports/P1_Nightmare_Issues_Resolution_Report.md`
- ADR-008: `docs/01_ADR/ADR-008-durability-graceful-shutdown.md`
