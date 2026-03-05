# ADR-008: ExceptionClassifier 도입 및 람다 경계 명확화

## 상태

**제안됨 (Proposed)**

## 컨텍스트

### 문제 1: 도메인 순수성 침해 (Infrastructure Leakage)

현재 도메인 예외(`ClientBaseException`, `ServerBaseException`)가 인프라 마커 인터페이스(`CircuitBreakerIgnoreMarker`, `CircuitBreakerRecordMarker`)를 직접 구현합니다. 이는 기술적 세부 사항이 도메인 계층으로 누출된 사례입니다.

```kotlin
// Current (Problem):
abstract class ClientBaseException : BaseException, CircuitBreakerIgnoreMarker
abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker

// Desired (Solution):
abstract class ClientBaseException : BaseException  // No marker!
abstract class ServerBaseException : BaseException  // No marker!
```

**영향**: 35개 서비스에서 예외 분류 정책 변경 시 모든 예외 클래스를 수정해야 함.

### 문제 2: 예외 분류 로직 분산

현재 Resilience4j CircuitBreaker 설정이 마커 인터페이스 타입으로 분류를 수행합니다. 이는 분류 로직이 여러 곳에 흩어져 있음을 의미합니다.

## 결정

Spring의 `SQLExceptionTranslator` 패턴과 유사한 `ExceptionClassifier` 전략 패턴을 도입합니다.

### 아키텍처

```
module-infra/
└── executor/
    └── classifier/
        ├── ExceptionClassifier.kt         # 전략 인터페이스
        ├── CircuitBreakerClassification.kt  # 분류 결과 enum
        └── DefaultExceptionClassifier.kt  # 기본 구현체
```

### 분류 전략

```kotlin
enum class CircuitBreakerClassification {
    IGNORE,    // 4xx - 비즈니스 예외, CB 무시
    RECORD,    // 5xx - 시스템 예외, CB 기록
    DEFAULT    // 기타 - 기본 처리
}

fun interface ExceptionClassifier {
    fun classify(exception: Throwable): CircuitBreakerClassification
}

@Component
class DefaultExceptionClassifier : ExceptionClassifier {
    override fun classify(exception: Throwable): CircuitBreakerClassification {
        return when (exception) {
            is ClientBaseException -> CircuitBreakerClassification.IGNORE
            is ServerBaseException -> CircuitBreakerClassification.RECORD
            else -> CircuitBreakerClassification.DEFAULT
        }
    }
}
```

### 람다 경계 패턴 (Lambda Boundary Pattern)

```
┌─────────────────────────────────────────────────────────────────┐
│                     LogicExecutor (Infrastructure)               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                 Lambda Boundary                          │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │           Inside Lambda (Infrastructure)            │ │  │
│  │  │  • External API calls                               │ │  │
│  │  │  • DB queries                                       │ │  │
│  │  │  • Messaging                                        │ │  │
│  │  │  • Exception tracking & isolation                   │ │  │
│  │  └─────────────────────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              ↓                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │           Outside Lambda (Business)                       │  │
│  │  • Pure calculations (Cube probability)                   │  │
│  │  • Domain service processing                              │  │
│  │  • Value Object functions                                 │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**패턴 예시:**
```kotlin
// Good: Data fetch inside lambda, calculation outside
val data = executor.execute({ repository.findById(id) }, context)
val result = domainService.calculate(data)  // Outside lambda - pure function

// Bad: Everything inside lambda (unclear responsibility)
val result = executor.execute({
    val data = repository.findById(id)
    domainService.calculate(data)  // Business logic in infrastructure wrapper
}, context)
```

## 결과

### 이점

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| Domain exception dependencies | `CircuitBreakerIgnoreMarker`, `CircuitBreakerRecordMarker` | None (pure) |
| Classification logic location | Exception classes (35+ files) | `ExceptionClassifier` (1 file) |
| Policy change impact | Modify 35+ exception classes | Modify 1 classifier |

### 마이그레이션 경로

1. `ExceptionClassifier` 추가 (additive)
2. `FallbackHandler`에서 classifier 사용하도록 업데이트
3. 도메인 예외에서 마커 인터페이스 제거
4. 마커 인터페이스 deprecated 처리

### 위험 평가

- **Risk Level**: MEDIUM
- **Breaking Change**: Yes (marker interfaces removed from base exceptions)
- **Rollback**: Restore marker interfaces if circuit breaker tests fail

## 관련 문서

- [ADR-052: Resilience4j Circuit Breaker](../01_ADR/ADR-052-resilience4j-circuit-breaker.md)
- [ADR-044: LogicExecutor Zero Try-Catch](../01_ADR/ADR-044-logicexecutor-zero-try-catch.md)
- [Guardrails: Marker Interface](../guardrails/backend/resilience/marker-interface.md)
- [Guardrails: Exception Handling](../guardrails/backend/spring/exception-handling.md)

## 구현 파일

- `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/CircuitBreakerClassification.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/ExceptionClassifier.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/classifier/DefaultExceptionClassifier.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/FallbackHandler.kt`
- `module-common/src/main/kotlin/maple/expectation/error/exception/base/ClientBaseException.kt`
- `module-common/src/main/kotlin/maple/expectation/error/exception/base/ServerBaseException.kt`
