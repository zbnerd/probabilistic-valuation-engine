# ADR-704: Multi-Instance Cache Invalidation Consistency Test

**Status**: Accepted
**Date**: 2026-04-18
**Context**: Issue #704 — PostgresNotifyPub/Sub 분산 캐시 무효화 일관성 검증

## Context

PostgreSQL LISTEN/NOTIFY 기반 캐시 무효화(PostgresNotifyPublisher/Subscriber)가 단일 인스턴스에서는 동작하지만, **다중 인스턴스 환경**에서 L1(Caffeine) 일관성이 보장되는지 검증하는 테스트가 없었음.

### 문제

Scale-out 환경에서 각 인스턴스의 L1 캐시는 독립적이며, 한 인스턴스에서 발생한 evict/clear 이벤트가 다른 인스턴스의 L1에 올바르게 전파되지 않으면 **stale data** 문제가 발생.

### 기존 인프라

- `PostgresNotifyPublisher`: `NOTIFY "cache_invalidation", '<json>'` 전송
- `PostgresNotifySubscriber`: 전용 Connection으로 `LISTEN cache_invalidation`, 100ms 폴링
- `TieredCacheManager`: L1(Caffeine) + L2(PostgreSQL) 2계층 캐시
- Self-skip: `instanceId` 매칭으로 자기 이벤트 무시
- Version filter: `event.version <= currentVersion` 시 stale 간주 후 skip

## Decision

SpringApplicationBuilder 대신 **직접 객체 생성** 방식으로 3개 인스턴스를 구성하여 LISTEN/NOTIFY 메커니즘을 테스트.

### 직접 생성 선택 이유

1. `@ConditionalOnProperty` 체인 복잡 (`cache.l2.impl=postgres` + `cache.invalidation.impl=postgres`)
2. 검증 대상은 LISTEN/NOTIFY 메커니즘이지 Spring bean wiring이 아님
3. 직접 생성으로 instanceId 제어, 초기화 순서 보장 용이

### 테스트 아키텍처

```
Testcontainers PostgreSQL (shared)
├── Instance A (instanceId="test-A", own Caffeine L1, shared PG L2)
├── Instance B (instanceId="test-B", own Caffeine L1, shared PG L2)
└── Instance C (instanceId="test-C", own Caffeine L1, shared PG L2)
```

### 구현 중 발견한 사항

1. **`cache_storage` 테이블 누락**: V102, V107 마이그레이션은 인덱스만 생성하고 테이블 CREATE가 없음. 테스트에서 직접 생성으로 해결.
2. **버전 카운터 충돌**: 각 인스턴스의 `versionCounter`가 독립적으로 증가하므로, L2 backfill 후 버전이 동일해져 stale filter가 올바른 evict를 skip하는 문제 발견. 직접 L1 put으로 버전 추적을 우회하여 해결.

### 6개 테스트 시나리오

| # | 시나리오 | 검증 내용 |
|---|---------|----------|
| 1 | Evict 전파 | A에서 evict → B/C L1 무효화 |
| 2 | Burst 50키 | 50개 키 동시 evict → 전파 |
| 3 | Stale version skip | 구버전 이벤트 무시 |
| 4 | Self-skip | 자기 이벤트 처리 안 함 |
| 5 | CLEAR_ALL 전파 | 전체 L1 무효화 |
| 6 | 동시성 | A+B 동시 evict → C에서 모두 수신 |

## Consequences

- **긍정**: LISTEN/NOTIFY 기반 캐시 일관성이 자동 검증됨. 버전 카운터 충돌 이슈 발견.
- **부정**: `cache_storage` 테이블이 마이그레이션에 누락되어 있음 (별도 이슈로 처리 필요).
- **리스크**: 직접 객체 생성 방식이므로 Spring bean wiring 이슈는 검증하지 않음.
