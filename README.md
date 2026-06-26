# Probabilistic Valuation Engine

**메이플스토리 장비의 확률적 기대비용을 대규모 비동기 ETL 파이프라인으로 산출하는 백엔드 시스템.**

캐릭터 이름 하나로 스타포스·큐브 기대비용을 3개 프리셋 기준으로 계산한다.
매일 03:00 KST 에 전체 랭킹 수십만 IGN 의 장비 데이터를 수집·계산·동기화하여
PostgreSQL read model 에 적재하고, REST API 로 초당 수천 건의 기대치 조회를 제공한다.

---

## 한 줄 요약

> 복잡한 인프라(Redis + MySQL + MongoDB)를 제거하고 **PostgreSQL 단일 구조 + Micro-Batching** 으로  
> **97 RPS → 7,347 RPS (약 75배 개선)** 를 달성, 운영 복잡도를 단순화했다.

---

## 시스템 아키텍처 — ETL 파이프라인

매일 새벽 03:00 KST, Airflow 가 4단계 수집 체인을 트리거하면 4개의 서비스 모듈이 Kafka 이벤트로 연쇄 동작한다.

```
   ┌─────────────────────────────────────────────┐
   │   Airflow  (control plane)                  │
   │   morning_chain_pipeline  ·  03:00 KST      │
   │   stop → ranking → ocid → basic → item      │
   └──────────────────────┬──────────────────────┘
                          │ phase trigger
   ┌──────────────────────▼──────────────────────┐
   │  ① external-api         :8081               │
   │     Nexon API 4단계 fetch                   │
   │     RANKING → OCID → CHARACTER_BASIC        │
   │              → ITEM_EQUIPMENT               │
   │     snapshot chunks (JSONL.gz) → MinIO      │
   └──────────────────────┬──────────────────────┘
                          │ chunk-ready  (Kafka)
   ┌──────────────────────▼──────────────────────┐
   │  ② calculator           :8082               │
   │     기대비용 계산 (3 프리셋)                 │
   │     result chunks (JSONL.gz) → MinIO        │
   └──────────────────────┬──────────────────────┘
                          │ result-ready (Kafka)
   ┌──────────────────────▼──────────────────────┐
   │  ③ synchronizer         :8083               │
   │     read model upsert → PostgreSQL          │
   └──────────────────────┬──────────────────────┘
                          │ consumed     (Kafka)
   ┌──────────────────────▼──────────────────────┐
   │  ④ cleanup              :8084               │
   │     MinIO artifact GC  (6h cycle)           │
   └─────────────────────────────────────────────┘

   Serving     rest-controller :8080  ←  PostgreSQL read models
   Storage     MinIO(artifacts) · PostgreSQL 17+PGMQ(state·queue) · Kafka(events) · Redis(cache)
   Observe     Prometheus · cAdvisor · Grafana · Loki · Promtail
```

| 모듈 | 역할 | Port |
|------|------|------|
| `module-external-api` | Nexon API 호출 파이프라인 (snapshot 적재) | 8081 |
| `module-calculator` | 기대치 계산 파이프라인 (result 적재) | 8082 |
| `module-synchronizer` | result → PostgreSQL read model 동기화 | 8083 |
| `module-cleanup` | consumed artifact 가비지 컬렉션 | 8084 |
| `module-rest-controller` | expectation 조회 REST API | 8080 |

---

## 핵심 수치

| 지표 | 시작 | 최종 |
|------|------|------|
| RPS | 97 | **7,347** (실데이터 200K rows 기준) |
| p99 latency | 4,100ms | **36ms** |
| 데이터베이스 | Redis + MySQL + MongoDB | **PostgreSQL 단일** |
| 에러율 | 59.7% | **0%** |
| Scale-out | 불가 | **선형 확장 준비 완료** |

---

## 핵심 아키텍처 결정

### PostgreSQL 만으로 충분하다

| 기능 | 이전 | 현재 |
|------|------|------|
| 캐시 | Redis | Caffeine L1 + PostgreSQL UNLOGGED |
| 분산락 | Redis Named Lock | `pg_advisory_lock` |
| Pub/Sub | Redis Pub/Sub | PostgreSQL LISTEN/NOTIFY |
| 메시지큐 | Redis Stream | PGMQ (PostgreSQL 익스텐션) |
| 이벤트스토어 | MongoDB | PostgreSQL JSONB |
| 영속성 | MySQL | PostgreSQL |

Redis 시절보다 빨라진 이유 — Caffeine 히트율 99%+ 로 DB 도달이 거의 없고, 이미 열린 커넥션 재사용으로 네트워크 홉이 사라지며, 트랜잭션 내 `pg_notify()` 는 롤백 시 무효 이벤트까지 함께 사라진다(원자성).

### Micro-Batching — 940→7,347 의 실제 원인

PostgreSQL 단일화는 전제조건, 실제 성능 엔진은 Micro-Batching.

```
Before   요청 1,2,3 → SELECT WHERE id = ?  (DB 왕복 3회)
After    요청 1,2,3 → SELECT WHERE id IN (1,2,3)  (DB 왕복 1회)
```

수 ms 시간창 안에 들어온 요청을 모아 배치 쿼리로 처리. 캐시 미스 시 DB 왕복이 3~5회 → 1회 로 축소됐다. LISTEN/NOTIFY 는 이 성능을 Scale-out 환경에서도 유지하게 만드는 보완재.

### L1 Fast Path

캐시 히트 시 스레드풀·직렬화·역직렬화를 전부 우회하고 GZIP `byte[]` 를 그대로 반환한다.

```
Before   Controller → Executor → L1.get() → Deserialize → GZIP → Response  (200ms)
After    Controller → L1.getGzipDirect() → Response                       (4ms)
```

### Write-Behind Buffer

계산 결과를 즉시 DB 에 쓰지 않고 메모리 버퍼에 모았다가 배치로 flush.
DB 저장 지연 150ms(동기) → 0.1ms(`Buffer.offer`). CAS + Exponential Backoff(lock-free), Phaser 기반 graceful shutdown.

### LISTEN/NOTIFY 기반 분산 캐시 정합성

Scale-out 에서 각 노드의 Caffeine L1 이 달라지는 문제를 해결. 노드가 UPDATE + NOTIFY(동일 트랜잭션) 하면 다른 노드들이 LISTEN → evict → 재조회. 롤백 시 NOTIFY 도 발생하지 않아 Redis Pub/Sub 의 spurious invalidation 이 없다.

---

## 성능 여정 (10주, 8단계)

```
97 → 555 → 674 → 965 → [325] → 940 → 7,347 RPS

1  97  → 223   Chaos baseline (Redis + MySQL + MongoDB)
2 223  →  97   Singleflight 도입 → -56% 회귀 → 즉시 롤백
3  97  → 555   L1 Fast Path: GZIP byte[] 직접 반환, Executor 우회
4 555  → 674   Write-Behind Buffer: DB 저장 150ms → 0.1ms
5 674  → 965   프리셋 병렬 계산: 전용 Executor 분리로 데드락 방지
6 965  → 325   V5 Stateless 전환: 정합성 확보, 속도 53% 감소 (의도된 트레이드오프)
7 325  → 940   Auto Warmup: Cold Start 227% 개선
8 940  → 7,347 Redis·MySQL·MongoDB 제거 → PostgreSQL 단일화 → Micro-Batching
```

상세 기록: [`docs/06_Performance_Journey/`](docs/06_Performance_Journey/)

---

## 좋아요 도메인 — 123일의 진화

좋아요 기능 하나가 헥사고날 아키텍처(Port/Adapter) 와 함께 어떻게 진화했는지.
비관적 락 → 낙관적 락 → Write-Behind → Lua Script → 보상 트랜잭션 → DB Trigger 기반 정합성.
인프라를 교체하는 동안 `module-core`·`module-app` 코드는 한 줄도 바뀌지 않았다.

상세 기록: [`docs/22_Like_Refactoring_Journey/`](docs/22_Like_Refactoring_Journey/)

---

## 기술 스택

**언어·프레임워크** Kotlin, Java 21 (Virtual Threads), Spring Boot 3
**데이터 플레인** 4개 Spring Boot 모듈 (external-api / calculator / synchronizer / cleanup) + rest-controller
**오케스트레이션** Airflow 2.10.5 (morning_chain DAG, host-network)
**메시징** Kafka (chunk-ready / result-ready / consumed 이벤트 라우팅)
**저장소** PostgreSQL 17 + PGMQ, MinIO (객체 저장), Redis (synchronizer cache)
**캐시** Caffeine (L1), PostgreSQL UNLOGGED TABLE (L2)
**관측성** Prometheus, cAdvisor, Grafana, Loki, Promtail, Micrometer
**테스트** JUnit 5, ArchUnit (Testcontainers 금지 — Issue #207)
**인프라** Docker Compose, Vultr Seoul KR

---

## 로컬 실행 (4 서비스 docker 기동)

```bash
# 1. 인프라 기동 + MinIO SA 시크릿 발급
docker compose -f docker-compose.yml up -d minio postgres kafka redis
docker compose -f docker-compose.yml run --rm minio-bootstrap   # → docker/services/secrets/ + .env

# 2. 4 서비스 이미지 빌드
./docker/services/build.sh

# 3. 4 서비스 기동
docker compose -f docker-compose.yml -f docker-compose.services.yml up -d \
  external-api calculator synchronizer cleanup

# 4. Airflow 추가 (선택)
docker compose -f docker-compose.yml -f docker-compose.airflow.yml up -d airflow-webserver airflow-scheduler
```

```bash
# expectation API 조회
curl -s "http://localhost:8080/api/v5/characters/진격캐넌/expectation"
```

> `202` 는 접수 응답. 실제 계산 완료는 서비스 로그(`Calculation completed`) 로 확인한다.

상세 배포 절차: [`docs/21_Operations/docker-deploy-runbook.md`](docs/21_Operations/docker-deploy-runbook.md)

---

## 문서 구조

```
docs/
├── 01_ADR/                        아키텍처 결정 기록
├── 06_Performance_Journey/        97 → 7,347 RPS 여정
├── 22_Like_Refactoring_Journey/   좋아요 도메인 123일 기록
├── 21_Operations/                 운영·배포 runbook
└── 11_Observability/              관측성 가이드
```

---

## 배운 것

- **측정 없는 최적화는 미신이다.** LocalSingleFlight 는 이론상 완벽했으나 캐시 히트마저 blocking 해 -56% 회귀. 측정 안 했으면 "좋은 최적화"로 남았을 것.
- **복잡도가 성능의 적이다.** Redis·MySQL·MongoDB 를 걷어냈을 때 RPS 가 올라갔다. 네트워크 홉과 인프라 오버헤드가 그만큼 비쌌음.
- **트레이드오프를 명시하라.** V5 Stateless 에서 속도 53% 포기하고 정합성 선택. 이 포기를 문서화하지 않으면 "왜 느리지?"로만 남는다.

---

*Author: SeungJun · Stack: Spring Boot + Kotlin + PostgreSQL · 2026*
