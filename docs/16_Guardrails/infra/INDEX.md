# Guardrails - Infrastructure

## 개요

인프라 구성요소(Redis, Scale-out 등)에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-INFRA-001 | [Scale-out Architecture Guardrails](scaleout.md) | critical | ScaleOut, Stateful, Stateless, FeatureFlag, Scheduler, DistributedLock |
| GR-INFRA-002 | [Redis & Redisson Integration](redis.md) | critical | Redis, Redisson, Lua Script, Hash Tag, DLQ, Distributed Lock |
| GR-INFRA-003 | [Resilience & Reliability](resilience-reliiability.md) | critical | CircuitBreaker, Outbox, RedisLock, GracefulShutdown, Scheduler, Resilience4j |
| GR-INFRA-004 | [Rate Limiting in Distributed Environment](rate-limiting-distributed.md) | high | RateLimiting, Bucket4j, Redis, Distributed, FailClosed, DoS |
| GR-INFRA-005 | [Scheduler Distributed Lock](scheduler-distributed-lock.md) | critical | Scheduler, @Scheduled, DistributedLock, @Locked, LeaderElection, Duplicates |
| GR-INFRA-006 | [Graceful Shutdown Coordination](graceful-shutdown-coordination.md) | critical | GracefulShutdown, Shutdown, DataLoss, RollingDeploy, SmartLifecycle, RedisFlag |

## 주요 주제

### Scale-out Architecture
- **In-Memory 상태 제거**: Stateless 아키텍처 준수
- **Feature Flag 안전장치**: `matchIfMissing=true` 기본값
- **Scheduler 분산화**: `@Locked` 분산 락 또는 Leader Election

### Redis Integration
- **Hash Tag 패턴**: Redis Cluster에서 다중 키 연동
- **DLQ (Dead Letter Queue)**: 데이터 유실 방지
- **Graceful Degradation**: Redis 장애 시 Fallback

## P0 Scale-out Blockers

| ID | Component | Pattern | Severity |
|----|-----------|---------|----------|
| P0-1 | AlertThrottler | In-Memory AtomicInteger | Critical |
| P0-2 | InMemoryBufferStrategy | JVM Local Queue | Critical |
| P0-3 | LikeBufferStorage | Feature Flag | Critical |
| P0-4 | SingleFlightExecutor | In-Memory inFlight | Critical |

## 관련 문서

- [docs/05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md](../../05_Reports/05_09_Scale_Out/scale-out-blockers-analysis.md)
- [docs/03_Technical_Guides/infrastructure.md](../03_Technical_Guides/infrastructure.md)
- CLAUDE.md Section 18: Stateless Architecture Principles
