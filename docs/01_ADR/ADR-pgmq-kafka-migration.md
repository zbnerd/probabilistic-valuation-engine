# ADR: PGMQ 쓰기 파이프라인 DB 커넥션 병목 해결 — PgBouncer 도입 결정

**상태**: Approved
**날짜**: 2026-04-19
**영향**: PGMQ Worker, HikariCP, 전체 쓰기 파이프라인

---

## 배경

V5 expectation 엔드포인트 10K IGN 부하 테스트를 통해 PGMQ 기반 쓰기 파이프라인의 DB 커넥션 병목 확인.

초기 96% 실패율은 btree(JSONB) 인덱스 제거, Kotlin varargs 수정, 캐시 오염 방어 등으로 **0.7%**까지 개선 (ADR-pgmq-write-pipeline-debugging 참조).
이후 워커 비동기화(non-blocking)로 **22.3 tasks/sec** 달성 (Round 6).

그러나 처리량을 더 끌어올리기 위해 동시성을 높이면 **오히려 처리량이 감소**하는 현상 발견.

---

## 부하 테스트 라운드별 지표

| 항목 | Round 5 | Round 6 | Round 7 | Round 8 |
|------|---------|---------|---------|---------|
| maxInFlight | 100 | 100 | **400** | **400** |
| hikari pool | 100 | 100 | **400** | **400** |
| batchSize | 50 | 50 | **200** | **200** |
| highPriorityCapacity | 1000 | 1000 | 1000 | **10000** |
| 503 (Queue Full) | 5,588 | 7,565 | 8,576 | **0** |
| 202 (수락) | 960 | 2,435 | 1,424 | **9,608** |
| 처리 완료 | 1,427 | 2,435 | 1,069 | 1,859 |
| DLQ | 10 | 247 | 1 | 1 |
| 처리 속도 | ~7.9 t/s | **22.3 t/s** | 5.9 t/s | 10.3 t/s |

### 핵심 관찰

1. **Round 6 → 7**: maxInFlight 100→400, hikari 100→400, batchSize 50→200 올렸더니 처리량 **22.3 → 5.9 t/s (73% 감소)**
2. **Round 7 → 8**: highPriorityCapacity 1000→10000으로 503 제거. 수락은 9,608개지만 처리량은 여전히 10.3 t/s
3. **Round 9**: Round 8에서 maxInFlight/hikari만 100으로 되돌린 설정 → 응답시간 p50 489ms에서 4,695ms로 폭증

---

## 근본 원인: PGMQ 폴링이 DB 커넥션을 소비

PGMQ는 PostgreSQL 테이블 기반 메시지 큐입니다. **폴링 자체가 DB 커넥션을 소비**합니다.

### 구조적 문제

```
[HTTP Request]
    ↓
[PGMQ send() → DB INSERT]          ← 커넥션 1
    ↓
[PGMQ read() → DB SELECT]          ← 커넥션 2 (워커 폴링)
    ↓
[Nexon API 호출]                    ← I/O 대기
    ↓
[character_valuation_views INSERT]  ← 커넥션 3
[cache_storage UPSERT]              ← 커넥션 4
[PGMQ archive() → DB UPDATE]       ← 커넥션 5
```

**메시지 1개 처리에 최소 4~5개의 DB 커넥션 점유** (폴링 + 읽기 + 쓰기 + 보관 + 캐시).
동시성 400이면 이론상 1,600~2,000 커넥션 필요. HikariCP 400풀로는 절대 부족.

### 실측 메트릭 (HikariCP)

```
hikaricp_connections_acquire_seconds_max: 8.188s    ← 정상 < 1ms
hikaricp_connections_acquire_seconds_sum / count: 28ms  ← 평균 대기
```

커넥션 획득에 **최대 8.18초** 대기. 워커들이 커넥션 기다리느라 대부분의 시간을 소비.

### 왜 동시성을 올리면 더 느려지는가

```
maxInFlight=100, hikari=100 → 커넥션 수요 ~500, 풀 100 → 경합 존재 but tolerable → 22.3 t/s
maxInFlight=400, hikari=400 → 커넥션 수요 ~2000, 풀 400 → 경합 폭발 → 5.9 t/s
```

---

## 대안 검토

### PgBouncer (선택)

- **장점**: PostgreSQL 커넥션 풀러. 앱 수천 커넥션을 PgBouncer 수십 커넥션으로 멀티플렉싱. 인프라 변경만으로 해결
- **단점**: session mode에서는 LISTEN/NOTIFY 비호환. transaction mode에서는 SET/ advisory lock 비호환
- **선택 이유**: 목표 처리량 ~500 t/s에서 코드 변경 없이 인프라만으로 해결 가능

### Kafka

- **장점**: 오프셋 기반 재처리, 파티션 수 = 선형 스케일아웃, DB 커넥션 경로 완전 분리
- **단점**: 운영 복잝도 높음, 인프라 구축 비용, 소규모에는 과한 설정
- **기각 이유**: 목표 처리량 ~500 t/s에서 오버엔지니어링. PGMQ 구조 자체는 문제 없고 커넥션 풀링만으로 해결됨
- **재검토 조건**: 처리량 수만 t/s 도달 시, 또는 멀티 서버 scale-out에서 파티션 기반 병렬 처리 필요 시

### Redis Streams

- **장점**: Kafka 경량 버전. DB 커넥션 경합 해결
- **단점**: Redis 메모리 기반 → 보관 한계. 오프셋/DLQ 재구현 필요
- **기각 이유**: PGMQ에서 이미 오프셋/DLQ/retry 사용 중. Redis Streams는 이를 재구현해야 함

### RabbitMQ

- **장점**: 복잡한 메시지 라우팅 가능
- **단점**: 오프셋 관리 없음. 한번 소비하면 삭제
- **기각 이유**: 방향이 다름. 재처리/이벤트 스트리밍 필요

| 항목 | PgBouncer | Kafka | Redis Streams | RabbitMQ |
|------|-----------|-------|---------------|----------|
| 코드 변경 | 없음 | 대규모 | 중 | 중 |
| DB 커넥션 해결 | O (멀티플렉싱) | O (경로 분리) | O | O |
| 운영 복잡도 | 낮 | 높 | 중 | 낮 |
| 목표 대응 (~500 t/s) | O | 과함 | 과함 | 과함 |
| 수만 t/s 확장성 | 한계 | O | 제한적 | X |

---

## 결정

**PgBouncer 도입.** session 모드로 애플리케이션 커넥션을 멀티플렉싱.

### PgBouncer 설정

```ini
[databases]
maple_expectation = host=127.0.0.1 port=5432 dbname=maple_expectation

[pgbouncer]
pool_mode = session
max_client_conn = 1000
default_pool_size = 50
reserve_pool_size = 10
```

### 트레이드오프: LISTEN/NOTIFY 비호환

PgBouncer session mode에서는 `LISTEN/NOTIFY`가 클라이언트 세션 내에서만 동작합니다.
현재 프로젝트에서 `LISTEN/NOTIFY`는 캐시 무효화(`PostgresNotifySubscriber`)에 사용됩니다.

**완화 방안**:
- PgBouncer를 통하지 않는 직접 연결을 캐시 무효화 채널에 사용
- 또는 transaction mode + `supervisor` 방식으로 LISTEN만 전용 연결 사용

### 기대 효과

1. **커넥션 대기 시간 제거**: 앱 1000 커넥션 → PgBouncer 50 커넥션으로 멀티플렉싱
2. **동시성 확장 가능**: hikari pool을 늘려도 PostgreSQL 실제 커넥션은 PgBouncer가 관리
3. **코드 변경 없음**: 애플리케이션은 연결 문자열만 변경
4. **목표 처리량 달성**: ~500 t/s 달성 가능 예상

### Kafka 재검토 조건

- 단일 서버에서 **수만 t/s** 필요 시
- **멀티 서버 scale-out**에서 파티션 기반 병렬 처리 필요 시
- PGMQ의 PostgreSQL 테이블 기반 한계(예: 큐 크기 증가로 인한 vacuum 부하) 도달 시

---

## 교훈

1. **PGMQ는 소규모에 적합**: 메시지 큐와 DB가 같은 자원을 공유하므로, 대규모에서는 필연적으로 경합
2. **동시성 ≠ 처리량**: I/O 바운드에서 커넥션 풀을 넘어서면 오히려 throughput 하락
3. **메트릭 기반 원인 특정**: HikariCP acquire_seconds_max(8.18s)로 커넥션 경합 확진
4. **오버엔지니어링 주의**: 목표 처리량에 맞는 최소 솔루션 선택. Kafka는 강력하지만 운영 비용이 높음

---

## 참고

- [ADR-pgmq-write-pipeline-debugging](ADR-pgmq-write-pipeline-debugging.md) — 96%→0.7% 실패율 개선 과정
- [ADR-btree-jsonb-index-removal](ADR-btree-jsonb-index-removal.md) — btree(JSONB) 인덱스 제거
