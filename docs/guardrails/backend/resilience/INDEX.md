# Guardrails - Resilience

## 개요

회복 탄력성(Resilience), Circuit Breaker, Fallback 전략에 관한 가드레일입니다.

## 파일 목록

| ID | 제목 | 심각도 | 키워드 |
|----|------|--------|--------|
| GR-RESILIENCE-001 | [Circuit Breaker Pattern](circuit-breaker.md) | critical | CircuitBreaker, Resilience4j, Exception, Marker |
| GR-RESILIENCE-002 | [Circuit Breaker Marker Interface](marker-interface.md) | critical | Marker, Interface, Exception, CircuitBreaker |
| GR-RESILIENCE-003 | [Circuit Breaker Fallback Strategy](fallback.md) | warning | Fallback, GracefulDegradation, CircuitBreaker, Resilience |

## 주요 가드레일

### GR-RESILIENCE-001: Circuit Breaker Pattern
- **DON'T**: 예외 없이 모든 에러를 실패로 카운트
- **DO**: Marker Interface로 예외 분류 명시

### GR-RESILIENCE-002: Marker Interface
- **DON'T**: Marker Interface 없이 예외 정의
- **DO**: 예외 기본 클래스에 Marker 구현
  - `ClientBaseException` → `CircuitBreakerIgnoreMarker` (4xx)
  - `ServerBaseException` → `CircuitBreakerRecordMarker` (5xx)

### GR-RESILIENCE-003: Fallback Strategy
- **DON'T**: Fallback에서 null 반환
- **DO**: 캐시 기반 Fallback (L2 → MySQL → Fail Safe)

### Fallback 계층 구조
```
Layer 1: Primary Source (Nexon API)
Layer 2: L2 Cache (Redis) - Warm Data
Layer 3: MySQL (Persistent Storage) - Cold Data
Layer 4: Fail Safe (null, EmptyList, Default Value)
```

## 관련 문서

- [ADR-052](../../../01_ADR/ADR-052-resilience4j-circuit-breaker.md) - Resilience4j Circuit Breaker
- [ADR-044](../../../01_ADR/ADR-044-logicexecutor-zero-try-catch.md) - LogicExecutor Zero Try-Catch
- CLAUDE.md Section 12-1: Circuit Breaker & Resilience Rules
