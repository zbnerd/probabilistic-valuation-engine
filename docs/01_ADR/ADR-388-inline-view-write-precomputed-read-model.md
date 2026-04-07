# ADR-388: Inline View Write — Async Materialized View Pattern

**Status**: Accepted
**Date**: 2026-04-05
**Context**: V5 CQRS View Table Population

## Context

V5 엔드포인트(`/api/v5/characters/{userIgn}/expectation`)는 `character_valuation_views` 테이블에서 precomputed 결과를 조회하는 구조.

Worker가 계산을 완료해도 view 테이블이 비어있어 모든 요청이 202 MISS 반환.

### 기존 설계 (미구현)

```
Worker → Spring Event → TransactionalEventPublisher → PGMQ "character-sync" → Consumer → View Table
```

문제:
- `CalculationCompletedEvent` Spring 이벤트가 한 번도 발행된 적 없음 (파이프라인 끊김)
- `character-sync` Consumer Worker 미구현
- PGMQ payload에 `EquipmentExpectationResponseV4` (~95KB) 직렬화 → PGMQ payload limit (~8KB) 초과

## Decision

**Inline View Write** 패턴 채택. Worker의 계산 트랜잭션 내에서 view 테이블에 직접 upsert.

### 구조

```
Worker → calculateExpectation() [@Transactional]
         ├── 계산 로직
         ├── persistenceService.saveResults()
         └── syncToViewTable()  ← 신규 (same TX)
             └── ViewTransformer → CharacterViewQueryServicePostgres.upsert()

V5 API → character_valuation_views 조회 → 200 HIT / 202 MISS
```

### 설계 원칙

- **Queue는 lightweight job trigger 역할만** (식별자만)
- **DB가 source of truth**
- **Same transaction atomicity** — 계산 롤백 시 view write도 롤백
- **Best-effort** — V5 비활성화 시 `ObjectProvider.getIfAvailable()` → null → skip

### 패턴 분류

이 구조는 CQRS가 아닌 **Async Materialized View** 패턴:

```
Queue → Worker → DB (precompute) → API read
```

## Consequences

### 긍정
- PGMQ payload 크기 문제 해결 (95KB → 전송 안 함)
- 트랜잭션 원자성 보장 (stronger consistency than eventual)
- 구현 복잡도 최소 (Consumer Worker, Event Publisher 불필요)
- 98 RPS 환경에서 병목 영향 없음 (DB write ms 단위)

### 부정
- Write model이 read model을 직접 참조 (결합도 증가)
- 비동기 확장 포인트 없음 (Kafka fan-out 등)
- View 구조 변경 시 EquipmentExpectationServiceV4 수정 필요

### 변경 파일

| 파일 | 변경 |
|------|------|
| `EquipmentExpectationServiceV4.java` | `ViewTransformer`, `ObjectProvider<CharacterViewQueryServicePostgres>` 주입 + `syncToViewTable()` 추가 |
| `ViewTransformer.java` | `toEntityFromResponse()` 메서드 추가 |

### 재사용 컴포넌트 (변경 없음)

- `CharacterViewQueryServicePostgres.upsert()` — 기존 optimistic locking upsert
- `CharacterValuationViewEntity` — 기존 JPA 엔티티
- `PgmqWorkerConfig` — `characterSync` 필드 추가하지 않음 (불필요)

## Migration Note

```
// TODO: Replace with async projection (event-driven) when scaling out
```

Phase 2에서 실제로 다중 인스턴스 + Kafka 등 이벤트 기반 확장 필요 시:
1. `doCalculateExpectation()`에서 Spring Event 발행
2. `TransactionalEventPublisher`가 lightweight 메시지 ({userIgn, timestamp})만 PGMQ/Kafka에 발행
3. Consumer가 DB에서 결과 조회 후 view 테이블 upsert
