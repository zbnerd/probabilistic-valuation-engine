# PostgreSQL Migration Design

## Overview

probabilistic-valuation-engine 프로젝트의 DB 인프라를 MySQL + MongoDB + Redis → PostgreSQL 단일 DB로 마이그레이션합니다.

## Strategy: Selective Infrastructure Replacement

**Full Rewrite가 아닌, DB 인프라만 선택적 교체**

```
From develop branch → v2/postgresql-redesign

┌─────────────────────────────────────────────────────────────┐
│  ❌ REMOVE (DB Infrastructure)                               │
├─────────────────────────────────────────────────────────────┤
│  - MySQL JPA Entities, Repository                            │
│  - MongoDB config, MongoTemplate, View                       │
│  - Redis config, RedisTemplate, Redisson                     │
│  - Redis Streams Consumer/Producer                           │
│  - Outbox tables/pollers/schedulers/metrics (ALL 3)          │
│  - Redis-based distributed locks, cache, buffer              │
│  - application-*.yml (DB sections)                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  🔄 REPLACE (New PostgreSQL Infrastructure)                  │
├─────────────────────────────────────────────────────────────┤
│  - PostgreSQL config + jsonb schema                          │
│  - PGMQ Extension (message queue)                            │
│  - Advisory Lock (distributed lock, Single Flight)           │
│  - Caffeine local cache                                      │
│  - JPA Entities → PostgreSQL-native (jsonb, UNLOGGED)        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  ✅ KEEP (Convert Java → Kotlin)                             │
├─────────────────────────────────────────────────────────────┤
│  - LogicExecutor, ResilientExecutor, SafeExecutor            │
│  - Resilience4j, Circuit Breaker, Retry                      │
│  - AOP (logging, transaction, cache)                         │
│  - Exception hierarchy (ClientBaseException, etc.)           │
│  - JWT authentication                                        │
│  - Rate Limiting                                             │
│  - Calculation engines (Starforce, Cube, Flame)              │
│  - Monitoring/Metrics                                        │
│  - Gradle multi-module structure                             │
│  - CI/CD workflows                                           │
│  - ArchUnit tests                                            │
│  - ALL existing unit tests (verify after conversion)         │
└─────────────────────────────────────────────────────────────┘
```

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| PGMQ | Extension (native) | Docker PostgreSQL has extension privileges; better performance |
| Module Structure | Keep current 6 modules | ArchUnit tests already validate; lower risk |
| Issue Granularity | Coarse-grained (20-25) | Easier coordination, fewer PRs |
| Testcontainers | Reuse container | Fastest local development |
| Java → Kotlin | Full conversion | Unify codebase; existing tests verify correctness |

## Phase Overview

```
Phase 0: Foundation (2 Issues)
  ├── P0-01: Project Setup + Kotlin Conversion Foundation
  └── P0-02: PostgreSQL + PGMQ Docker Compose Setup

Phase 1: Core Data Layer (3 Issues)
  ├── P1-01: ADR-001 PostgreSQL Single DB Strategy
  ├── P1-02: Domain Entities (PostgreSQL + jsonb)
  └── P1-03: Repository Layer + Ports

Phase 2: Message Queue (2 Issues)
  ├── P2-01: ADR-002 PGMQ Integration
  └── P2-02: PGMQ Producers & Consumers

Phase 3: Locking & Caching (2 Issues)
  ├── P3-01: ADR-003 Advisory Lock (Redisson Replacement)
  └── P3-02: Caffeine Cache (Redis Cache Replacement)

Phase 4: Data Pipeline (3 Issues)
  ├── P4-01: ADR-004 Collect/Compute/Serve Separation
  ├── P4-02: Nexon API Collector (→ PostgreSQL)
  └── P4-03: Expectation Calculation Workers (PGMQ-based)

Phase 5: API Layer (2 Issues)
  ├── P5-01: REST Controllers (Adapt to New Repository)
  └── P5-02: ADR-005 Single Flight + Hot Key Handling

Phase 6: Features (3 Issues)
  ├── P6-01: Like System (PostgreSQL UNLOGGED + PGMQ)
  ├── P6-02: Donation System (PostgreSQL + PGMQ)
  └── P6-03: JWT Authentication (Keep, Convert to Kotlin)

Phase 7: Testing (2 Issues)
  ├── P7-01: Integration Tests (Testcontainers + PGMQ)
  └── P7-02: Chaos Tests (PostgreSQL Failure Scenarios)

Phase 8: Performance (2 Issues)
  ├── P8-01: ADR-006 Scale-out Strategy
  └── P8-02: Load Testing + Optimization

Phase 9: Deployment (2 Issues)
  ├── P9-01: CI/CD Pipeline Updates
  └── P9-02: Monitoring + Runbook Updates

Total: 23 Issues
```

## ADR List

| ADR | Title | Phase |
|-----|-------|-------|
| ADR-001 | PostgreSQL Single DB Strategy (MySQL + MongoDB + Redis 제거 근거) | Phase 1 |
| ADR-002 | PGMQ 도입 (Redis Streams + Outbox 패턴 대체 근거) | Phase 2 |
| ADR-003 | Advisory Lock 도입 (Redisson 대체 근거) | Phase 3 |
| ADR-004 | 수집/계산/서빙 분리 전략 | Phase 4 |
| ADR-005 | 인기 캐릭터 핫 키 대응 (Single Flight + Caffeine) | Phase 5 |
| ADR-006 | 단계별 스케일업 전략 (PostgreSQL 단일 → Redis 재도입 트리거 조건) | Phase 8 |

## Traffic Assumptions

| Scenario | Concurrent Users | QPS | Strategy |
|----------|------------------|-----|----------|
| Normal | 1,000 | 0.5 | Single PostgreSQL instance |
| Patch Day | 50,000 | 500 | PostgreSQL connection pooling + Caffeine |
| Viral | 200,000+ | 2,000+ | Scale-out trigger (consider Redis reintroduction) |

## Risk Mitigation

1. **Java → Kotlin Conversion**: Existing unit tests verify correctness
2. **Data Migration**: Not applicable (fresh start, no production data migration)
3. **Performance**: Load testing in Phase 8 before production
4. **Rollback**: Keep `develop` branch intact; merge only after validation

## Success Criteria

- [ ] All existing unit tests pass after Kotlin conversion
- [ ] Integration tests pass with PostgreSQL + PGMQ
- [ ] Load test: 500 QPS with <200ms p99 latency
- [ ] All 6 ADRs documented
- [ ] ArchUnit architecture tests pass
