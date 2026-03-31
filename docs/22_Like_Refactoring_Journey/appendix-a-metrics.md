# 부록 A: 메트릭 변천사

---

## A.1 성능 메트릭 진화

### 응답 시간 (P99)

| 시기 | Like | Unlike | 비고 |
|------|------|--------|------|
| 2025.12 (In-Memory) | 15-25ms | 25-40ms | 동기 DB DELETE |
| 2026.01 (Redis Lua) | 10-15ms | 22-35ms | 여전히 동기 DELETE |
| 2026.01 말 (Atomic Toggle) | 3-5ms | 8-12ms | Hot path DB 제거 |
| 2026.03 (Direct DB) | 5-10ms | 5-10ms | 단일 트랜잭션 |

### DB 쿼리 부하

| 시기 | DB QPS (like endpoint) | 비고 |
|------|----------------------|------|
| 2025.12 | 2,500-3,500/s | 매 요청마다 JOIN FETCH + DELETE |
| 2026.01 말 | <200/s | Write-Behind + 배치 동기화 |
| 2026.03 (Direct DB) | 1/request | 단일 INSERT/DELETE per toggle |

### Redis Round Trip

| 시기 | RTT per toggle | 비고 |
|------|----------------|------|
| 2026.01 초 | 3-4회 | SISMEMBER + SADD + HINCRBY + PUBLISH |
| 2026.01 말 | 1회 | Lua Script 통합 |
| 2026.03 | 0회 | Redis 제거 |

### HikariCP 커넥션 사용률

| 시기 | 사용률 | 비고 |
|------|--------|------|
| 2025.12 | 75-125% | 포화 상태 (unlike 동기 DELETE) |
| 2026.01 말 | 10-15% | 배치 동기화 전환 |
| 2026.03 | 5-10% | Direct DB + Trigger |

---

## A.2 안정성 메트릭

### 데이터 정합성

| 시기 | 알려진 정합성 이슈 | 비고 |
|------|-------------------|------|
| 2025.12 | fetchAndClear 비원자적 | 서버 크래시 시 유실/중복 |
| 2026.01 | Relation/Counter 이중 쓰기 | Lua Script로 해결 |
| 2026.01 말 | TOCTOU 레이스 | Atomic Toggle로 해결 |
| 2026.03 말 | like_count drift | DB Trigger로 해결 |

### 장애 내구성

| 시기 | SPOF | Fallback | 비고 |
|------|------|----------|------|
| 2025.12 | DB | 없음 | DB 장애 = 서비스 중단 |
| 2026.01 | Redis + DB | DB Fallback | Redis 장애 시 DB 직접 |
| 2026.03 | DB | 없음 | 단일 DB (ADR-015 수용) |

---

## A.3 코드 메트릭

### 패키지/파일 수

| 시기 | Like 관련 파일 | 패키지 수 | 비고 |
|------|---------------|-----------|------|
| 2025.12 | 12 | 5 | 모놀리식 |
| 2026.02 | 18 | 8 | 모듈 분리 |
| 2026.03 | 17 | 10 | 헥사고날 (역설적으로 감소) |

### 코드 복잡도

| 지표 | Before | After |
|------|--------|-------|
| God Class 최대 라인 | 910줄 | 150줄 |
| 직접 try-catch | 15개 | 0개 |
| 순환 의존 | 3개 | 0개 |
| Controller 비즈니스 로직 | 45줄 | 0줄 |
