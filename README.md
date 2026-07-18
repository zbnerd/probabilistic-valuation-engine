# Probabilistic Valuation Engine

**메이플스토리 장비의 확률적 기대비용을 대규모 비동기 ETL 파이프라인으로 산출하는 백엔드 시스템.**

캐릭터 이름 하나로 스타포스·큐브 기대비용을 3개 프리셋 기준으로 계산한다.
매일 03:00 KST 에 전체 랭킹 수십만 IGN 의 장비 데이터를 수집·계산·동기화하여
PostgreSQL read model 에 적재하고, REST API 로 기대치 조회를 제공한다.

Hexagonal Architecture(Port/Adapter) 기반의 멀티모듈 Kotlin/Spring 시스템이다.

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

### 모듈

| 모듈 | 역할 | Port |
|------|------|------|
| `module-external-api` | Nexon API 호출 파이프라인 (snapshot 적재) | 8081 |
| `module-calculator` | 기대치 계산 파이프라인 (result 적재) | 8082 |
| `module-synchronizer` | result → PostgreSQL read model 동기화 | 8083 |
| `module-cleanup` | consumed artifact 가비지 컬렉션 | 8084 |
| `module-rest-controller` | expectation 조회 REST API | 8080 |

### 외부 API 4단계 흐름 (external-api)

```
RANKING_FETCH     일일 랭킹 전체 IGN 수집
    ↓
OCID_LOOKUP       IGN → 내부 OCID 식별자 매핑 (배치)
    ↓
CHARACTER_BASIC   캐릭터 기본 정보 (직업·레벨·월드)
    ↓
ITEM_EQUIPMENT    장비 슬롯 상세 (스타포스·큐브·옵션)
```

각 단계는 결과를 `JSONL.gz` 청크로 MinIO 에 적재하고 chunk-ready 이벤트를 발행한다.

---

## 설계 원칙

- **Hexagonal Architecture** — Controller → Port Interface → Adapter. 도메인(`module-core`)이 인프라에 의존하지 않는다. 모듈 의존성: `common → core → infra → web → app`.
- **LogicExecutor 위임** — `try-catch` / `try-finally` 대신 `LogicExecutor` 로 예외 처리·로깅·복구 일원화.
- **비동기 체이닝** — `join()`/`get()`/`runBlocking` 금지. `CompletableFuture` 체이닝(`thenApply`·`thenCompose`)과 Kotlin `suspend` 로 IO 와 CPU 바운드를 분리.
- **Stateless** — 서버 내 mutable 상태 금지. 상태는 PostgreSQL / Redis / MinIO 에만.
- **Tiered Cache** — `L1(Caffeine) → L2(PostgreSQL UNLOGGED) → SingleFlight → Loader`.
- **Flat Work Queue** — 중첩 fan-out 대신 bounded flat queue + fixed workers 로 동시성 제어.

상세: [`.claude/rules/`](.claude/rules/) · [`CLAUDE.md`](CLAUDE.md)

---

## 기술 스택

**언어·프레임워크** Kotlin, Java 21 (Virtual Threads), Spring Boot 3
**데이터 플레인** 4개 Spring Boot 모듈 (external-api / calculator / synchronizer / cleanup) + rest-controller
**오케스트레이션** Airflow 2.10.5 (morning_chain DAG, host-network)
**메시징** Kafka (chunk-ready / result-ready / consumed 이벤트 라우팅)
**저장소** PostgreSQL 17 + PGMQ, MinIO (객체 저장), Redis (synchronizer cache)
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
├── 02_Chaos_Engineering/          카오스 엔지니어링
├── 03_Technical_Guides/           인프라·비동기·테스트 가이드
├── 11_Observability/              관측성 가이드
├── 16_Guardrails/                 아키텍처 가드레일
└── 21_Operations/                 운영·배포 runbook
```

전체 문서 맵: [`CLAUDE.md`](CLAUDE.md) 상세 문서 섹션 참조.

---

*Author: SeungJun · Stack: Spring Boot + Kotlin + PostgreSQL · 2026*
