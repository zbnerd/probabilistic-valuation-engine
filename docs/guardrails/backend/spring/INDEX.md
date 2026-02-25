# Guardrails - Spring

## 개요

Spring Framework 관련 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-001 | [LogicExecutor & Zero Try-Catch Policy](logic-executor.md) | critical | LogicExecutor, try-catch, @Transactional, Zero Try-Catch Policy |
| GR-002 | [Exception Handling Strategy](exception-handling.md) | critical | Exception, ClientBaseException, ServerBaseException, CircuitBreaker, Exception Chaining |
| GR-003 | [AOP & Facade Pattern & Spring Security Filter](aop-facade.md) | critical | AOP, Facade, Self-Invocation, CGLIB, Spring Security, Filter, OncePerRequestFilter |
| GR-003 | [SOLID Principles & Design Patterns](solid-principles.md) | warning | SOLID, SRP, OCP, DIP, Design Patterns, Clean Architecture |
| GR-004 | [Optional Chaining & Modern Null Handling](optional-chaining.md) | warning | Optional, Null, Tap Pattern, Checked Exception, Method Reference |

## 주요 가드레일

### LogicExecutor & Zero Try-Catch Policy (GR-001)
- **DON'T**: 직접 try-catch 사용 금지
- **DO**: LogicExecutor 사용 필수
  - `execute()` - 일반 실행
  - `executeOrDefault()` - 기본값 반환
  - `executeWithTranslation()` - 예외 변환
  - `executeWithFinally()` - finally 블록 필요 시

### Exception Handling Strategy (GR-002)
- **DON'T**: 모호한 예외 사용 금지 (`RuntimeException`, `Exception`)
- **DO**: Custom Exception 사용 필수
  - `ClientBaseException` (4xx) - `CircuitBreakerIgnoreMarker`
  - `ServerBaseException` (5xx) - `CircuitBreakerRecordMarker`

### AOP & Facade Pattern (GR-003)
- **DON'T**: Self-Invocation (AOP 무시), OncePerRequestFilter에 @Component
- **DO**: Facade Pattern, Filter Bean 수동 등록 (@Bean)

### SOLID Principles (GR-003)
- **DON'T**: SRP 위반, OCP 위반, God Class, 하드코딩
- **DO**: 단일 책임 원칙, 개방 폐쇄 원칙, 의존성 역전 원칙

### Optional Chaining (GR-004)
- **DON'T**: 명령형 null 체크, Optional 내부 try-catch, 람다 3줄 초과
- **DO**: Optional 체이닝, Tap 패턴, Method Reference 우선

## 관련 문서

- CLAUDE.md Sections 4, 11, 12, 15
- [docs/03_Technical_Guides/infrastructure.md](../../../03_Technical_Guides/infrastructure.md)
