# Guardrails - MapleExpectation

## 개요

MapleExpectation 프로젝트의 가드레일(Guardrails)은 프로젝트에서 추출한 모범 사례(Best Practices)와 안티패턴(Anti-Patterns)를 체계적으로 정리한 문서 집합입니다. 각 가드레일은 실제 운영 경험, ADR(Architecture Decision Record), 그리고 기술 문서에서 추출한 검증된 규칙들을 포함합니다.

## 카테고리

| 카테고리 | 설명 | 문서 수 |
|---------|------|---------|
| **[Architecture](architecture/)** | 아키텍처 설계 원칙과 패턴 | 4 |
| **[Backend - Spring](backend/spring/)** | Spring Framework 관련 가드레일 | 5 |
| **[Backend - Cache](backend/cache/)** | 캐시 전략 및 TieredCache | 1 |
| **[Backend - Concurrency](backend/concurrency/)** | 비동기 처리 및 동시성 | 3 |
| **[Backend - Performance](backend/performance/)** | 성능 튜닝 및 최적화 | 1 |
| **[Backend - Resilience](backend/resilience/)** | 회복 탄력성 및 Circuit Breaker | 3 |
| **[Database](database/)** | 데이터베이스 연결 풀 및 쿼리 | 1 |
| **[Infrastructure](infra/)** | Redis, Scale-out 등 인프라 | 2 |
| **[Testing](testing/)** | 테스트 전략 및 Chaos Engineering | 4 |

## 전체 가드레일 목록

### Architecture

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ARCH-001 | [ADR Decisions](architecture/adr-decisions.md) | critical | ADR, Architecture, Decision |
| GR-ARCH-002 | [Multi-Agent Protocol](architecture/multi-agent.md) | critical | Agent, Council, Protocol |
| GR-ARCH-003 | [Service Modules](architecture/service-modules.md) | warning | Modules, V2, V4, Facade |
| GR-ARCH-004 | [System Design](architecture/system-design.md) | critical | Design, Patterns, Architecture |

### Backend - Spring

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-001 | [LogicExecutor & Zero Try-Catch Policy](backend/spring/logic-executor.md) | critical | LogicExecutor, try-catch, @Transactional |
| GR-002 | [Exception Handling Strategy](backend/spring/exception-handling.md) | critical | Exception, ClientBaseException, ServerBaseException |
| GR-003 | [AOP & Facade Pattern & Spring Security Filter](backend/spring/aop-facade.md) | critical | AOP, Facade, Self-Invocation, Spring Security |
| GR-003 | [SOLID Principles & Design Patterns](backend/spring/solid-principles.md) | warning | SOLID, SRP, OCP, DIP, Design Patterns |
| GR-004 | [Optional Chaining & Modern Null Handling](backend/spring/optional-chaining.md) | warning | Optional, Null, Tap Pattern, Method Reference |

### Backend - Cache

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CACHE-001 | [TieredCache & SingleFlight](cache/tiered-cache-singleflight.md) | critical | TieredCache, SingleFlight, Cache-Stampede |

### Backend - Concurrency

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-ASYNC-001 | [Async Non-Blocking Pipeline Pattern](backend/concurrency/async-patterns.md) | critical | Async, Non-Blocking, Pipeline |
| GR-ASYNC-002 | [Thread Pool Backpressure Best Practice](backend/concurrency/thread-pool.md) | critical | ThreadPool, Backpressure, RPS |
| GR-ASYNC-003 | [Virtual Threads Best Practice](backend/concurrency/virtual-threads.md) | warning | VirtualThreads, Project Loom |

### Backend - Performance

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-PERF-001 | [Thread Pool Tuning Guardrails](backend/performance/thread-pool-tuning.md) | critical | ThreadPool, ExecutorService, VirtualThreads |

### Backend - Resilience

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-RESILIENCE-001 | [Circuit Breaker Pattern](backend/resilience/circuit-breaker.md) | critical | CircuitBreaker, Resilience4j, Exception |
| GR-RESILIENCE-002 | [Circuit Breaker Marker Interface](backend/resilience/marker-interface.md) | critical | Marker, Interface, Exception |
| GR-RESILIENCE-003 | [Circuit Breaker Fallback Strategy](backend/resilience/fallback.md) | warning | Fallback, GracefulDegradation |

### Database

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-DB-001 | [Database Connection Pool Guardrails](database/connection-pool.md) | critical | ConnectionPool, HikariCP, MySQL |

### Infrastructure

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-INFRA-001 | [Scale-out Architecture Guardrails](infra/scaleout.md) | critical | ScaleOut, Stateful, Stateless |
| GR-002 | [Redis & Redisson Integration](infra/redis.md) | critical | Redis, Redisson, Lua Script, DLQ |

### Testing

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-CHAOS-001 | [Chaos Engineering Testing Strategy](testing/chaos-engineering.md) | critical | Chaos, Nightmare, Test |
| GR-NIGHTMARE-001 | [Nightmare Scenarios](testing/nightmare-tests.md) | critical | Nightmare, Cache, Stampede |
| GR-TEST-001 | [Unit Test Best Practices](testing/unit-test.md) | critical | Thread.sleep, Awaitility |
| GR-TEST-002 | [Flaky Test Prevention](testing/flaky-test-prevention.md) | critical | Flaky Test, determinism |
| GR-TEST-003 | [Concurrency Test Best Practices](testing/concurrency-test.md) | critical | ExecutorService, awaitTermination |

## 심각도별 가드레일

| 심각도 | 개수 | 비율 |
|--------|------|------|
| **critical** | 19 | 82.6% |
| **warning** | 4 | 17.4% |

## 사용법

### 코드 리뷰 시
- 해당 기능과 관련된 가드레일을 확인하여 안티패턴 사용 여부 검토
- 심각도가 `critical`인 규칙 위반 시 반드시 수정 요청

### ADR 작성 시
- 새로운 아키텍처 결정 시 관련 가드레일을 참고하여 일관성 유지
- 가드레일과 충돌하는 결정 시 근거 명시

### 테스트 작성 시
- `testing/` 카테고리의 가드레일을 참고하여 플래키 테스트 방지
- Chaos Engineering 가드레일을 활용하여 장애 시나리오 검증

## 관련 문서

- [CLAUDE.md](../../CLAUDE.md) - 프로젝트 코딩 표준
- [docs/00_Start_Here/architecture.md](../00_Start_Here/architecture.md) - 시스템 아키텍처
- [docs/01_ADR/](../01_ADR/) - 아키텍처 결정 기록
- [docs/02_Chaos_Engineering/](../02_Chaos_Engineering/) - 카오스 엔지니어링
