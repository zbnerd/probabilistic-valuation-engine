# Guardrails - Cache

## 개요

캐시 전략, TieredCache, SingleFlight 패턴에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CACHE-001 | [TieredCache & SingleFlight](tiered-cache-singleflight.md) | critical | ADR-003, TieredCache, SingleFlight, Cache-Stampede, Thundering-Herd |

## 주요 가드레일

### GR-CACHE-001: Cache Stampede Prevention
- **DON'T**: Singleflight 없이 모든 요청이 DB로 직행
- **DO**: TieredCache + SingleFlight 패턴 적용

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

## 관련 문서

- [ADR-003](../../../01_ADR/ADR-003-tiered-cache-singleflight.md) - Tiered Cache & SingleFlight Pattern
- [infrastructure.md](../../../03_Technical_Guides/infrastructure.md) Section 17: TieredCache
