# ADR: PGMQ → Kafka 마이그레이션 결정

**상태**: Approved
**날짜**: 2026-04-19
**영향**: PGMQ Worker, HikariCP, 전체 쓰기 파이프라인

---

## 배경

V5 expectation 엔드포인트 10K IGN 부하 테스트를 통해 PGMQ 기반 쓰기 파이프라인의 근본적 한계를 확인.

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

동시성을 올릴수록 커넥션 경합이 기하급수적으로 심해집니다.
풀을 늘려도 PostgreSQL 자체의 커넥션 처리 한계가 있고, 커넥션 간 lock 경합이 증가합니다.

---

## PGMQ vs Kafka 구조 비교

| 항목 | PGMQ (현재) | Kafka (마이그레이션) |
|------|------------|---------------------|
| 메시지 저장 | PostgreSQL 테이블 | Kafka 브로커 (디스크/페이지캐시) |
| 폴링 방식 | DB SELECT (커넥션 소비) | 브로커 push (커넥션 불필요) |
| 병렬 단위 | 워커 스레드 수 | 파티션 수 (선형 스케일아웃) |
| 오프셋 관리 | DB 컬럼 (read_ct, vt) | 컨슈머 오프셋 (DB 불필요) |
| DB 커넥션 영향 | 폴링마다 커넥션 소비 | **DB 커넥션完全不使用** |
| 스케일아웃 | 인스턴스 추가 시 커넥션 경합 악화 | 파티션 추가로 선형 확장 |
| 처리량 한계 | ~22 t/s (DB 커넥션 경합) | 파티션 수 × 컨슈머 처리량 |

### Kafka 도입 시 커넥션 경로 분리

```
[HTTP Request]
    ↓
[Kafka Producer → 브로커]           ← DB 커넥션 불필요
    ↓
[Kafka Consumer → 메시지 수신]       ← DB 커넥션 불필요
    ↓
[Nexon API 호출]                      ← I/O 대기
    ↓
[character_valuation_views INSERT]    ← 커넥션 1
[cache_storage UPSERT]                ← 커넥션 2
```

**메시지 1개당 DB 커넥션 2개** (기존 4~5개에서 60% 감소).
폴링/보관을 위한 커넥션이 완전히 사라집니다.

---

## 결정

**Kafka 도입.** PGMQ를 Kafka로 교체하여 메시지 큐잉 경로를 DB에서 분리.

### 기대 효과

1. **DB 커넥션 경합 제거**: 폴링/보관용 커넥션 3개/메시지 제거
2. **선형 스케일아웃**: 파티션 수 = 병렬 처리 단위
3. **처리량 향상**: DB 커넥션 대기 시간 제거로 이론적 한계 도달 가능
4. **부하 분리**: 메시지 큐 부하가 DB에 전이되지 않음

---

## 교훈

1. **PGMQ는 소규모에 적합**: 메시지 큐와 DB가 같은 자원을 공유하므로, 대규모에서는 필연적으로 경합
2. **동시성 ≠ 처리량**: I/O 바운드에서 커넥션 풀을 넘어서면 오히려 throughput 하락
3. **메트릭 기반 원인 특정**: HikariCP acquire_seconds_max(8.18s)로 커넥션 경합 확진
4. **구조적 한계는 튜닝으로 안 됨**: PGMQ의 DB 기반 폴링은 아키텍처 수준 한계. 코드 최적화로 극복 불가

---

## 참고

- [ADR-pgmq-write-pipeline-debugging](ADR-pgmq-write-pipeline-debugging.md) — 96%→0.7% 실패율 개선 과정
- [ADR-btree-jsonb-index-removal](ADR-btree-jsonb-index-removal.md) — btree(JSONB) 인덱스 제거
