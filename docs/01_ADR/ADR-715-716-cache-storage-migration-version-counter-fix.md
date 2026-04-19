# ADR: cache_storage 마이그레이션 누락 수정 + 버전 카운터 충돌 해결

## Status: Accepted

## Issues

- #715 (P0): cache_storage 테이블 CREATE 마이그레이션 누락
- #716 (P1): TieredCache 버전 카운터 충돌로 cross-instance evict 무시

## Decision

### #715: V110 마이그레이션으로 cache_storage 테이블 생성

`CREATE UNLOGGED TABLE IF NOT EXISTS` 사용. 기존 환경에서는 no-op, 신규 환경에서는 테이블 생성.

### #716: evict() 버전 카운터 증가 + strict `<` 비교

**근본 원인**: `TieredCache.evict()`가 `versionCounter.get()`을 사용하여 `put()`과 동일 버전 발행.

**수정**:
1. `evict()`에서 `versionCounter.incrementAndGet()` 사용
2. PostgresNotifySubscriber에서 `<=` → `<` (strict less-than)
3. EVICT 후 `clearKeyVersion()`으로 로컬 keyVersions 정리

## Rejected Alternatives

| 대안 | 기각 이유 |
|------|----------|
| EVICT version check 완전 제거 | out-of-order notification에 대한 방어막 상실 |
| L2 기반 버전 관리 (version column 추가) | DB 왕복 오버헤드, 스키마 변경 범위 과대 |
| 공유 PostgreSQL SEQUENCE | DB 왕복 오버헤드 |
| Instance별 version prefix | 복잡도 증가, 디버깅 어려움 |

## Confidence: high
## Scope-risk: narrow
