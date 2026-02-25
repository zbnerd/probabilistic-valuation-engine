# Guardrails - Backend

## 개요

백엔드 개발에 관한 전반적인 가드레일입니다.

## 하위 카테고리

| 카테고리 | 설명 | 문서 수 |
|---------|------|---------|
| **[Cache](cache/)** | 캐시 전략 및 TieredCache | 1 |
| **[Concurrency](concurrency/)** | 비동기 처리 및 동시성 | 3 |
| **[Performance](performance/)** | 성능 튜닝 및 최적화 | 1 |
| **[Resilience](resilience/)** | 회복 탄력성 및 Circuit Breaker | 3 |
| **[Spring](spring/)** | Spring Framework 관련 | 5 |

## 전체 파일 목록

### Cache

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CACHE-001 | [TieredCache & SingleFlight](cache/tiered-cache-singleflight.md) | critical | ADR-003, TieredCache, SingleFlight, Cache-Stampede |

### Concurrency

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ASYNC-001 | [Async Non-Blocking Pipeline Pattern](concurrency/async-patterns.md) | critical | Async, Non-Blocking, Pipeline |
| GR-ASYNC-002 | [Thread Pool Backpressure Best Practice](concurrency/thread-pool.md) | critical | ThreadPool, Backpressure, RPS |
| GR-ASYNC-003 | [Virtual Threads Best Practice](concurrency/virtual-threads.md) | warning | VirtualThreads, Project Loom |

### Performance

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-PERF-001 | [Thread Pool Tuning Guardrails](performance/thread-pool-tuning.md) | critical | ThreadPool, ExecutorService, VirtualThreads, Backpressure, RPS |

### Resilience

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-RESILIENCE-001 | [Circuit Breaker Pattern](resilience/circuit-breaker.md) | critical | CircuitBreaker, Resilience4j, Exception, Marker |
| GR-RESILIENCE-002 | [Circuit Breaker Marker Interface](resilience/marker-interface.md) | critical | Marker, Interface, Exception, CircuitBreaker |
| GR-RESILIENCE-003 | [Circuit Breaker Fallback Strategy](resilience/fallback.md) | warning | Fallback, GracefulDegradation, CircuitBreaker |

### Spring

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-001 | [LogicExecutor & Zero Try-Catch Policy](spring/logic-executor.md) | critical | LogicExecutor, try-catch, @Transactional, Zero Try-Catch Policy |
| GR-002 | [Exception Handling Strategy](spring/exception-handling.md) | critical | Exception, ClientBaseException, ServerBaseException, CircuitBreaker, Exception Chaining |
| GR-003 | [AOP & Facade Pattern & Spring Security Filter](spring/aop-facade.md) | critical | AOP, Facade, Self-Invocation, CGLIB, Spring Security, Filter, OncePerRequestFilter |
| GR-003 | [SOLID Principles & Design Patterns](spring/solid-principles.md) | warning | SOLID, SRP, OCP, DIP, Design Patterns, Clean Architecture |
| GR-004 | [Optional Chaining & Modern Null Handling](spring/optional-chaining.md) | warning | Optional, Null, Tap Pattern, Checked Exception, Method Reference |

## 관련 문서

- [CLAUDE.md](../../../CLAUDE.md) - Sections 4, 11, 12, 15
- [docs/03_Technical_Guides/infrastructure.md](../03_Technical_Guides/infrastructure.md) - 인프라 가이드
- [docs/03_Technical_Guides/async-concurrency.md](../03_Technical_Guides/async-concurrency.md) - 비동기 가이드
