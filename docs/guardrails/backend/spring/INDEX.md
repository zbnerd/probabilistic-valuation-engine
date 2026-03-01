# Guardrails - Spring

## 개요

Spring Framework 관련 가드레일입니다. LogicExecutor, Exception Handling, AOP, SOLID 원칙 등 Spring Boot 3.x 애플리케이션 개발 시 필수적인 규칙들을 정의합니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-SPRING-001 | [LogicExecutor & Zero Try-Catch Policy](logic-executor.md) | critical | LogicExecutor, try-catch, execute, executeOrDefault, executeWithTranslation |
| GR-SPRING-002 | [Exception Handling Strategy](exception-handling.md) | critical | Exception, ClientBaseException, ServerBaseException, CircuitBreaker, Exception Chaining |
| GR-SPRING-003 | [AOP & Facade Pattern & Spring Security Filter](aop-facade.md) | critical | AOP, Facade, Self-Invocation, CGLIB, Filter, OncePerRequestFilter, SecurityContext |
| GR-SPRING-004 | [SOLID Principles & Design Patterns](solid-principles.md) | warning | SOLID, SRP, OCP, DIP, Strategy, Facade, Factory, Template Method |
| GR-SPRING-005 | [Optional Chaining & Modern Null Handling](optional-chaining.md) | warning | Optional, Null, Tap Pattern, Checked Exception, Method Reference, Lambda Hell |

## 주요 가드레일

### GR-SPRING-001: LogicExecutor & Zero Try-Catch Policy
- **DON'T**: 직접 try-catch 사용 금지
- **DO**: LogicExecutor 6가지 패턴 사용
  - `execute()` - 일반 실행
  - `executeVoid()` - 반환값 없는 작업
  - `executeOrDefault()` - 기본값 반환
  - `executeWithRecovery()` - 복구 로직 실행
  - `executeWithFinally()` - finally 블록 필요 시
  - `executeWithTranslation()` - 예외 변환

### GR-SPRING-002: Exception Handling Strategy
- **DON'T**: 모호한 예외 사용 금지 (`RuntimeException`, `Exception`)
- **DO**: Custom Exception 사용 필수
  - `ClientBaseException` (4xx) - `CircuitBreakerIgnoreMarker`
  - `ServerBaseException` (5xx) - `CircuitBreakerRecordMarker`
  - Exception Chaining 유지 (cause 파라미터)
  - Dynamic Message (ID, IGN 등 식별자 포함)

### GR-SPRING-003: AOP & Facade Pattern & Spring Security Filter
- **DON'T**: Self-Invocation (AOP 무시), OncePerRequestFilter에 @Component
- **DO**: Facade Pattern, Filter Bean 수동 등록 (@Bean)
- **추가**: SecurityContext 새로 생성 (Thread-Safe), 민감 데이터 마스킹

### GR-SPRING-004: SOLID Principles & Design Patterns
- **DON'T**: SRP 위반, OCP 위반, God Class, 하드코딩, @Deprecated 사용
- **DO**: 단일 책임 원칙, 개방 폐쇄 원칙 (Strategy 패턴), 의존성 역전 원칙 (인터페이스)
- **디자인 패턴**: Strategy, Facade, Factory, Template Method

### GR-SPRING-005: Optional Chaining & Modern Null Handling
- **DON'T**: 명령형 null 체크, Optional 내부 try-catch, 람다 3줄 초과, 과도한 람다 중첩
- **DO**: Optional 체이닝, Tap 패턴, Method Reference 우선, Private Method 추출 (3줄 규칙)

## 적용 범위

- **service/** - 비즈니스 로직
- **scheduler/** - 배치 작업
- **config/** - 설정 클래스
- **global/** - 글로벌 컴포넌트
- **aop/** - AOP Aspect

## 허용 예외

다음 경우에는 직접 try-catch 사용이 허용됩니다:

| 컴포넌트 | 사유 |
|--------|------|
| `TraceAspect` | AOP에서 LogicExecutor 호출 시 순환참조 발생 |
| `DefaultLogicExecutor` | LogicExecutor 구현체 내부 |
| `ExecutionPipeline` | LogicExecutor 실행 파이프라인 내부 |
| `TaskDecorator` | Runnable 래핑 구조로 LogicExecutor 적용 불가 |
| JPA Entity | Spring Bean 주입 불가 |

## 출처 문서

### 상위 문서
- [CLAUDE.md](../../../../CLAUDE.md)
  - Section 4: Implementation Logic & SOLID
  - Section 5: Anti-Pattern & Deprecation Prohibition
  - Section 6: Design Patterns & Structure
  - Section 11: Exception Handling Strategy
  - Section 12: Zero Try-Catch Policy & LogicExecutor
  - Section 12-1: Circuit Breaker & Resilience Rules
  - Section 13: Global Error Mapping & Response
  - Section 14: Anti-Pattern: Error Handling & Maintenance
  - Section 15: Anti-Pattern: Lambda & Parenthesis Hell
  - Section 16: Proactive Refactoring & Quality

### 기술 가이드
- [docs/03_Technical_Guides/infrastructure.md](../../../../03_Technical_Guides/infrastructure.md)
  - Section 7: AOP & Facade Pattern
  - Section 18: Spring Security 6.x Filter Best Practice
  - Section 19: Security Best Practices (Logging & API Client)
  - Section 20: SpringDoc OpenAPI (Swagger UI) Best Practice
- [docs/03_Technical_Guides/service-modules.md](../../../../03_Technical_Guides/service-modules.md)
  - V2 핵심 비즈니스 서비스 (15개 모듈)
  - V4 성능 강화 서비스 (7개 모듈)
  - 설계 패턴 요약 (Facade, Decorator, Strategy, etc.)

### 관련 ADR
- [ADR-011: Controller V4 Optimization](../../../../01_ADR/ADR-011-controller-v4-optimization.md)
- [ADR-014: Multi-Module Cross-Cutting Concerns](../../../../01_ADR/ADR-014-multi-module-cross-cutting-concerns.md)

### Production Incidents
- P0 #238: CGLIB Proxy NPE in Filter
- P0 #241: Self-Invocation Bug

## 관련 Guardrails

### Backend
- [../cache/tiered-cache.md](../cache/tiered-cache.md) - TieredCache & Cache Stampede Prevention
- [../cache/tiered-cache-singleflight.md](../cache/tiered-cache-singleflight.md) - Single-flight Pattern
- [../resilience/circuit-breaker.md](../resilience/circuit-breaker.md) - Circuit Breaker Pattern
- [../resilience/marker-interface.md](../resilience/marker-interface.md) - Marker Interface Pattern
- [../concurrency/async-patterns.md](../concurrency/async-patterns.md) - Async Non-Blocking Pipeline

### Infrastructure
- [../../infra/redis.md](../../infra/redis.md) - Redis & Redisson Integration
