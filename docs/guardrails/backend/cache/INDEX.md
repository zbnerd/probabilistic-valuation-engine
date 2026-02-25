# Guardrails - Cache

## 개요

캐시 전략, TieredCache, SingleFlight 패턴에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CACHE-001 | [TieredCache & SingleFlight](tiered-cache-singleflight.md) | critical | ADR-003, TieredCache, SingleFlight, Cache-Stampede, Thundering-Herd |
| GR-CACHE-002 | [TieredCache Best Practices](tiered-cache.md) | critical | Redis, Caffeine, Write-Order, Watchdog, TTL, Graceful-Degradation |
| GR-CACHE-006 | [Two-Phase Snapshot Pattern](two-phase-snapshot.md) | warning | Light-Full, Cache-Optimization, OCID-Lookup, Negative-Cache |
| GR-CACHE-007 | [Probabilistic Early Recomputation](probabilistic-early-recomputation.md) | warning | PER, X-Fetch, Probabilistic-Caching, Cache-Stampede, Early-Recomputation |
| GR-CACHE-008 | [Double-Check Pattern](double-check-pattern.md) | warning | Double-Check, Cache-Stampede, Leader-Follower, Distributed-Lock, Race-Condition |
| GR-CACHE-009 | [Follower Timeout & Retry](follower-timeout-retry.md) | warning | Follower-Timeout, Leader-Follower, Single-Flight, Retry-Strategy |
| GR-CACHE-010 | [Cache Key Design & Versioning](cache-key-design.md) | info | Cache-Key, Versioning, Fingerprint, Hash-Collision, TTL-Strategy |
| GR-CACHE-011 | [L1 Cache Backfill Pattern](l1-backfill.md) | info | L1-Backfill, Cache-Warming, TieredCache, Caffeine, Redis, Write-Through |

## 주요 가드레일

### GR-CACHE-001: Cache Stampede Prevention
- **DON'T**: Singleflight 없이 모든 요청이 DB로 직행
- **DO**: TieredCache + SingleFlight 패턴 적용

### GR-CACHE-002: TieredCache Best Practices
- **DON'T**: L1 먼저 저장 후 L2 저장 (L2 실패 시 불일치)
- **DO**: Write Order: L2 → L1 (원자성 보장)
- **TTL 규칙**: L1 TTL ≤ L2 TTL

### GR-CACHE-006: Two-Phase Snapshot Pattern
- **DON'T**: Full Data 조회 후 캐시 키 생성
- **DO**: Light Snapshot 먼저 조회 (ocid, fingerprint만)
- **성능**: Cache HIT 시 DB 부하 -60%

### GR-CACHE-007: Probabilistic Early Recomputation (PER/X-Fetch)
- **DON'T**: TTL 만료 시 모든 요청이 계산 실행
- **DO**: 확률적 조기 갱신으로 Stampede 방지
- **성능**: 만료 시 동시 요청 -80% ~ -95%

### GR-CACHE-008: Double-Check Pattern
- **DON'T**: Lock 획득 후 Double-Check 생략
- **DO**: Leader가 Lock 획득 후 L2 Double-Check
- **성능**: Leader 경합 시 중복 로드 -50%

### GR-CACHE-009: Follower Timeout & Retry
- **DON'T**: Shared Future로 모든 Follower 연결
- **DO**: Isolated Future + Exponential Backoff Retry
- **성능**: Follower 복구율 +85% p.p.

### GR-CACHE-010: Cache Key Design & Versioning
- **DON'T**: 버전 없는 캐시 키
- **DO**: 버전 + Fingerprint 기반 키
- **효과**: 데이터 구조 변경 시 자동 무효화

### GR-CACHE-011: L1 Cache Backfill Pattern
- **DON'T**: L2 HIT 후 L1 백필 생략
- **DO**: L2 HIT 시 비동기 L1 백필
- **성능**: L1 Hit Ratio +60~80% p.p., Redis Load -60% ~ -80%

### Cache Lookup Flow
```
[Request]
    ↓
[L1 Cache - Caffeine]  ← HIT: < 5ms
    ↓ miss
[L2 Cache - Redis]     ← HIT: < 20ms
    ↓ miss
[SingleFlight]         ← Merge concurrent requests
    ↓
[External API / DB]
```

### Before/After 성능

| Scenario | Without SingleFlight | With SingleFlight | Improvement |
|----------|---------------------|-------------------|-------------|
| 100 concurrent requests | 100 API calls | **1 API call** | **-99%** |
| p99 Latency | 2,340ms | **180ms** | **-92%** |
| Light Snapshot (Cache HIT) | DB 조회 15ms | Light 조회 6ms | **-60%** |
| PER (TTL 만료 시) | 100 API 호출 | 5-20 API 호출 | **-80% ~ -95%** |

## 관련 문서

- [ADR-003](../../../01_ADR/ADR-003-tiered-cache-singleflight.md) - Tiered Cache & SingleFlight Pattern
- [infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 17: TieredCache
- [cache-sequence.md](../../../04_Sequence_Diagrams/cache-sequence.md) - TieredCache Single-flight 시퀀스
- [expectation-cache-sequence.md](../../../04_Sequence_Diagrams/expectation-cache-sequence.md) - Two-Phase Snapshot 흐름
- [p1-p2-performance-improvements-report.md](../../../05_Reports/04_02_Cost_Performance/p1-p2-performance-improvements-report.md) - PER 알고리즘
