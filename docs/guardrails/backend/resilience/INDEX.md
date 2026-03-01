# Guardrails - Resilience

## 개요

회복 탄력성(Resilience), Circuit Breaker, Fallback, Redis HA, Auto Warmup 전략에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-RESILIENCE-001 | [Circuit Breaker Pattern](circuit-breaker.md) | critical | CircuitBreaker, Resilience4j, Exception, Marker |
| GR-RESILIENCE-002 | [Circuit Breaker Marker Interface](marker-interface.md) | critical | Marker, Interface, Exception, CircuitBreaker |
| GR-RESILIENCE-003 | [Circuit Breaker Fallback Strategy](fallback.md) | warning | Fallback, GracefulDegradation, CircuitBreaker, Resilience |
| GR-RESILIENCE-004 | [Redis Sentinel ReadMode Configuration](redis-sentinel-readmode.md) | critical | Redis, Sentinel, ReadMode, READONLY, Failover |
| GR-RESILIENCE-005 | [Redis Failover Topology Update](redis-failover-topology.md) | critical | Redis, Sentinel, Failover, Topology, ScanInterval, DNS |
| GR-RESILIENCE-006 | [Auto Warmup Strategy](auto-warmup-strategy.md) | warning | Warmup, ColdCache, PopularCharacter, ZSET, Scheduler |
| GR-RESILIENCE-007 | [Distributed Lock for Scheduler](distributed-lock-scheduler.md) | warning | Scheduler, DistributedLock, DuplicateExecution, Redisson |
| GR-RESILIENCE-008 | [Redis Sorted Set TTL Management](redis-zset-ttl.md) | warning | Redis, ZSET, TTL, MemoryLeak, SortedSet |

## 주요 가드레일

### Circuit Breaker (GR-RESILIENCE-001 ~ 003)

#### GR-RESILIENCE-001: Circuit Breaker Pattern
- **DON'T**: 예외 없이 모든 에러를 실패로 카운트
- **DO**: Marker Interface로 예외 분류 명시

#### GR-RESILIENCE-002: Marker Interface
- **DON'T**: Marker Interface 없이 예외 정의
- **DO**: 예외 기본 클래스에 Marker 구현
  - `ClientBaseException` → `CircuitBreakerIgnoreMarker` (4xx)
  - `ServerBaseException` → `CircuitBreakerRecordMarker` (5xx)

#### GR-RESILIENCE-003: Fallback Strategy
- **DON'T**: Fallback에서 null 반환
- **DO**: 캐시 기반 Fallback (L2 → MySQL → Fail Safe)

### Redis HA (GR-RESILIENCE-004 ~ 005)

#### GR-RESILIENCE-004: Redis Sentinel ReadMode Configuration
- **DON'T**: `ReadMode.SLAVE` 사용 (READONLY 에러 발생)
- **DO**: `ReadMode.MASTER` 설정으로 모든 읽기를 Master에서 수행
  - Failover 후 READONLY 에러 완전 차단
  - Read-After-Write 일관성 보장

#### GR-RESILIENCE-005: Redis Failover Topology Update
- **DON'T**: `scanInterval` 기본값 5000ms 사용 (느린 감지)
- **DO**: `scanInterval` 1000ms, `dnsMonitoringInterval` 5000ms 설정
  - Failover 감지 시간 30초 → 1-2초 단축
  - UnknownHostException 방지

### Auto Warmup (GR-RESILIENCE-006 ~ 008)

#### GR-RESILIENCE-006: Auto Warmup Strategy
- **DON'T**: Eager Load 모든 데이터 (과도한 시작 시간)
- **DO**: Redis ZINCRBY로 인기 캐릭터 추적, TOP N만 웜업
  - Cold Cache 95 RPS → Warm Cache 310 RPS (3.3x 향상)
  - Fire-and-Forget 패턴으로 API 지연 없음

#### GR-RESILIENCE-007: Distributed Lock for Scheduler
- **DON'T**: @Scheduled만 사용 (다중 인스턴스 중복 실행)
- **DO**: Redis 분산 락으로 단일 인스턴스만 실행
  - 중복 API 호출 방지
  - Rate Limiting 위반 방지

#### GR-RESILIENCE-008: Redis Sorted Set TTL Management
- **DON'T**: TTL 미설정 (메모리 누수)
- **DO**: 48시간 TTL로 당일+전날 데이터만 유지
  - 메모리 사용량 상수로 제한 (≈ 760KB)
  - 전날 데이터 웜업 가능

## 관련 문서

- [ADR-052](../../../01_ADR/ADR-052-resilience4j-circuit-breaker.md) - Resilience4j Circuit Breaker
- [ADR-044](../../../01_ADR/ADR-044-logicexecutor-zero-try-catch.md) - LogicExecutor Zero Try-Catch
- CLAUDE.md Section 12-1: Circuit Breaker & Resilience Rules
