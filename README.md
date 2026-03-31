# Probabilistic Valuation Engine

**메이플스토리 장비의 확률적 기대비용을 계산하는 서비스.**  
캐릭터 이름 하나로 스타포스·큐브 기대비용을 3개 프리셋 기준으로 산출한다.

---

## 한 줄 요약

> Redis + MySQL + MongoDB를 걷어내고 PostgreSQL 하나로 교체한 뒤, Micro-Batching을 적용했더니 97 RPS에서 7,347 RPS가 됐다.

---

## 무엇을 보여주는 프로젝트인가

이 프로젝트는 기능보다 **의사결정의 기록**이다.

- 인프라를 더할수록 느려졌고, 걷어낼수록 빨라진 10주간의 여정
- 실패한 최적화(LocalSingleFlight -56%)와 그 원인 분석
- 단일 PostgreSQL로 캐시·분산락·Pub/Sub·메시지큐를 대체한 과정
- 좋아요 도메인 하나가 123일간 어떻게 진화했는지

숫자가 아니라 그 숫자가 나온 이유를 설명할 수 있는 코드베이스다.

---

## 핵심 수치

| 지표 | 시작 | 최종 |
|------|------|------|
| RPS | 97 | **7,347** (실데이터 200k rows 기준) |
| p99 latency | 4,100ms | **36ms** |
| 데이터베이스 | Redis + MySQL + MongoDB | **PostgreSQL 단일** |
| 에러율 | 59.7% | **0%** |
| Scale-out | 불가 | **선형 확장 준비 완료** |

---

## 성능 여정 (10주, 8단계)

```
97 → 555 → 674 → 965 → [325] → 940 → 7,347 RPS

1단계  97  →  223  Chaos baseline (Redis + MySQL + MongoDB)
2단계 223  →   97  Singleflight 도입 → -56% 회귀 → 즉시 롤백
3단계  97  →  555  L1 Fast Path: GZIP byte[] 직접 반환, Executor 우회
4단계 555  →  674  Write-Behind Buffer: DB 저장 150ms → 0.1ms
5단계 674  →  965  프리셋 병렬 계산: 전용 Executor 분리로 데드락 방지
6단계 965  →  325  V5 Stateless 전환: 정합성 확보, 속도 53% 감소 (의도된 트레이드오프)
7단계 325  →  940  Auto Warmup: Cold Start 227% 개선
8단계 940  → 7,347  Redis·MySQL·MongoDB 제거 → PostgreSQL 단일화 → Micro-Batching (DB 왕복 3~5회→1회)
```

각 단계의 상세 기록: [`docs/06_Performance_Journey/`](docs/06_Performance_Journey/)

---

## 핵심 아키텍처 결정

### PostgreSQL만으로 충분하다

| 기능 | 이전 | 현재 |
|------|------|------|
| 캐시 | Redis | Caffeine L1 + PostgreSQL UNLOGGED |
| 분산락 | Redis Named Lock | `pg_advisory_lock` |
| Pub/Sub | Redis Pub/Sub | PostgreSQL LISTEN/NOTIFY |
| 메시지큐 | Redis Stream | PGMQ (PostgreSQL 익스텐션) |
| 이벤트스토어 | MongoDB | PostgreSQL JSONB |
| 영속성 | MySQL | PostgreSQL |

Redis를 쓸 때보다 빨라진 이유:
- Caffeine 히트율 99%+ → PostgreSQL이 실제로 맞는 요청이 거의 없음
- 이미 연결된 DB 커넥션 재활용 → 추가 네트워크 홉 없음
- 트랜잭션 내 `pg_notify()` → 롤백 시 무효화 이벤트도 사라짐 (원자성)

### Micro-Batching — 940→7,347의 실제 원인

PostgreSQL 단일화는 전제조건이었고, 실제 성능 엔진은 Micro-Batching이었다.

```
Before (개별 쿼리):
Request 1 → SELECT WHERE id = 1  (DB 왕복 1)
Request 2 → SELECT WHERE id = 2  (DB 왕복 2)
Request 3 → SELECT WHERE id = 3  (DB 왕복 3)

After (Micro-Batching):
Request 1~3 → SELECT WHERE id IN (1, 2, 3)  (DB 왕복 1)
```

수 ms 시간 창 안에 들어온 요청을 모아 배치 쿼리로 처리한다.  
캐시 미스 시 DB 왕복이 3~5회에서 1회로 줄었다. LISTEN/NOTIFY는 이 성능을 Scale-out 환경에서도 유지 가능하게 만든 보완재다.

### L1 Fast Path

캐시 히트 시 스레드풀·직렬화·역직렬화를 전부 우회하고 GZIP byte[]를 그대로 반환한다.

```
캐시 히트 경로 (Before): Controller → Executor → L1.get() → Deserialize → GZIP → Response (200ms)
캐시 히트 경로 (After):  Controller → L1.getGzipDirect() → Response (4ms)
```

### Write-Behind Buffer

계산 결과를 즉시 DB에 쓰지 않는다. 메모리 버퍼에 모았다가 배치로 처리한다.

```
DB 저장 지연: 150ms(동기) → 0.1ms(Buffer.offer)
셧다운 안전성: Phaser 기반 graceful flush
동시성 제어:  CAS + Exponential Backoff (lock-free)
```

### PostgreSQL LISTEN/NOTIFY 기반 분산 캐시 정합성

Scale-out 환경에서 각 노드의 Caffeine L1 캐시가 달라지는 문제를 해결한다.

```
노드 A → UPDATE + NOTIFY (같은 트랜잭션)
노드 B, C → LISTEN → Caffeine evict → 다음 요청에서 재조회
```

트랜잭션 롤백 시 NOTIFY도 발생하지 않는다. Redis Pub/Sub과 달리 spurious invalidation이 없다.

---

## 좋아요 도메인 — 123일의 진화

좋아요 기능 하나가 123일간 어떻게 변했는지 기록한 별도 문서가 있다.

```
2025.11  비관적 락 → 낙관적 락 → Write-Behind Buffer + Graceful Shutdown
2026.01  Redis Lua Script 원자성 → 보상 트랜잭션 → Scale-out Pub/Sub
2026.02  Java → Kotlin 마이그레이션 → 멀티모듈 분리
2026.03  헥사고날 아키텍처 → Redis 제거 → DB Trigger 기반 정합성
```

**시작**: Controller → DB (동기, 원자성 없음)  
**최종**: Controller → DB Transaction + Trigger → 완료

인프라를 교체할 때 module-core와 module-app 코드는 한 줄도 바뀌지 않았다. 헥사고날 아키텍처(Port/Adapter)가 실제로 작동한 사례다.

상세 기록: [`docs/22_Like_Refactoring_Journey/`](docs/22_Like_Refactoring_Journey/)

---

## 배운 것

**측정 없는 최적화는 미신이다**  
LocalSingleFlight는 이론적으로 완벽했다. 실제로는 캐시 히트마저 blocking해서 -56%가 됐다. 측정하지 않았으면 "좋은 최적화"로 남아있었을 것이다.

**복잡도가 성능의 적이다**  
Redis, MySQL, MongoDB를 걷어낼 때 RPS가 올라갈 거라고 생각하지 못했다. 네트워크 홉과 인프라 오버헤드가 그만큼 비쌌다.

**트레이드오프를 명시하라**  
V5 Stateless에서 속도를 53% 포기하고 정합성을 선택했다. 이 포기를 문서화하지 않으면 "왜 느리지?"로만 남는다.

**현실에서 측정하라**  
빈 DB에서 10,994 RPS는 자랑이다. 200k rows 환경의 7,347이 진짜다. 낙관적 수치로 계획하면 첫날 장애가 난다.

---

## 기술 스택

**언어·프레임워크**: Kotlin, Java 21 (Virtual Threads), Spring Boot 3  
**데이터베이스**: PostgreSQL 17 (PGMQ 익스텐션 포함)  
**캐시**: Caffeine (L1), PostgreSQL UNLOGGED TABLE (L2)  
**모니터링**: Prometheus, Grafana, Micrometer  
**테스트**: Testcontainers, JUnit 5, ArchUnit  
**부하테스트**: wrk  
**인프라**: Docker Compose, Vultr Seoul KR

---

## 문서 구조

```
docs/
├── 01_ADR/                        아키텍처 결정 기록 (86개)
├── 05_Reports/
│   └── 05_06_Load_Tests/          부하테스트 리포트 전체
├── 06_Performance_Journey/        97 → 7,347 RPS 여정 (11장)
└── 22_Like_Refactoring_Journey/   좋아요 도메인 123일 기록 (7장 + 부록)
```

---

## 빠른 시작

```bash
git clone https://github.com/zbnerd/probabilistic-valuation-engine
cd probabilistic-valuation-engine
cp .env.example .env  # NEXON_API_KEY 등 설정
docker compose up -d
./gradlew :module-app:bootRun --args='--spring.profiles.active=local'
```

```bash
# 부하테스트
./gradlew loadTest -Pscenario=patch-day

# 비교 리포트
./gradlew loadTestReport -PbeforeFile=baseline.json -PafterFile=current.json
```

---

*Author: SeungJun | Stack: Spring Boot + Kotlin + PostgreSQL | 2026*
