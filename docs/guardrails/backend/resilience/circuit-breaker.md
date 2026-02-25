---
id: GR-RESILIENCE-001
category: backend/resilience
severity: critical
keywords: [CircuitBreaker, Resilience4j, Exception, Marker, Fallback, Retry, Graceful Degradation]
---

# Circuit Breaker Pattern Guardrail

## 개요

장애가 전체 시스템으로 전파되는 것을 방지하기 위해 **Circuit Breaker 패턴**을 필수로 적용합니다. Resilience4j를 사용하여 외부 API 호출 및 장기 실행 작업의 장애를 감지하고, 서비스 전체 마비를 방지하는 **Graceful Degradation** 전략을 구현합니다.

> **설계 근거:** 외부 API 의존성은 분산 시스템의 #1 실패 지점입니다. Circuit Breaker는 장애 확산을 방지하고, 323회의 트립(Trip)에서 서비스 중단 없이 가용성을 유지한 것이 검증되었습니다.

## 핵심 원칙

### 1. 예외 분류 (Marker Interface)

| 예외 타입 | Marker Interface | Circuit Breaker 동작 | 로그 레벨 |
|----------|------------------|---------------------|----------|
| **비즈니스 예외** | `CircuitBreakerIgnoreMarker` | 실패로 카운트하지 않음 | WARN |
| **시스템 예외** | `CircuitBreakerRecordMarker` | 실패로 기록 | ERROR |

### 2. Fallback 전략

서킷이 오픈되거나 예외 발생 시, 사용자 경험을 해치지 않도록 적절한 폴백 로직을 제공합니다.

### 3. Logging Level 구분

- **비즈니스 예외(4xx):** `log.warn`을 사용하여 비정상적인 요청 흐름 기록
- **서버/외부 API 예외(5xx):** `log.error`를 사용하여 스택 트레이스와 함께 장애 상황 기록

### 4. 설정 기준

| 파라미터 | 권장값 | 설명 |
|----------|--------|------|
| `slidingWindowSize` | 10 | 통계 산출 윈도우 크기 |
| `failureRateThreshold` | 50 | 실패율 임계값 (%) |
| `waitDurationInOpenState` | 10s | OPEN 상태 유지 시간 |
| `minimumNumberOfCalls` | 10 | 최소 호출 수 |

## DON'T (안티패턴)

### 1. 예외 분류 없이 모든 에러를 실패로 카운트

```yaml
# Bad - 모든 예외를 실패로 처리
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        # 명시적 ignoreExceptions/recordExceptions 없음
```

**문제점:**
- 404 Not Found (비즈니스 예외)로 인해 서킷이 오픈됨
- 정상적인 요청 흐름이 장애로 간주되어 서비스 중단
- 사용자 경험 저하

### 2. Checked Exception으로 서킷브레이커 설정

```java
// Bad - checked exception 사용
@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallback")
public ApiResponse callNexonApi() throws IOException {
    // IOException 발생 시 서킷 동작 불확실
}
```

**문제점:**
- Checked exception은 Resilience4j가 기록하지 않음
- 서킷이 예상대로 동작하지 않음

### 3. 로그 레벨 구분 없이 모든 예외를 error로 기록

```java
// Bad - 비즈니스 예외도 error로 기록
try {
    return characterService.find(ign);
} catch (CharacterNotFoundException e) {
    log.error("Character not found", e);  // WARN이 적합한 데 ERROR 사용
    return null;
}
```

**문제점:**
- 비즈니스 예외로 인한 로그 폴루션
- 실제 장애 상황 식별 어려움
- 온콜 대응 시 영향도 평가 어려움

### 4. Fallback 미구현

```java
// Bad - Fallback 없음
@CircuitBreaker(name = "nexonApi")
public NexonApiCharacterResponse callNexonApi(String ign) {
    return nexonApiClient.getCharacter(ign);
    // 서킷 오픈 시 CircuitBreakerOpenException 발생
    // 사용자는 500 Internal Server Error 받음
}
```

**문제점:**
- 서킷 오픈 시 사용자 경험 저하
- 명확한 에러 메시지 부재
- 재시도 가이드 제공 안 됨

### 5. 일관되지 않은 설정

```yaml
# Bad - 인스턴스별 설정 불일치
resilience4j:
  circuitbreaker:
    instances:
      nexonApi:
        slidingWindowSize: 10
        failureRateThreshold: 50
      likeSyncDb:
        slidingWindowSize: 100    # 너무 큼
        failureRateThreshold: 80  # 너무 높음
```

## DO (베스트 프랙티스)

### 1. Marker Interface로 예외 분류 명시

```yaml
# Good - Marker Interface로 예외 분류
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        ignoreExceptions:
          # 비즈니스 예외(4xx)는 실패로 카운트하지 않음
          - maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          # 시스템 예외(5xx)만 실패로 기록
          - maple.expectation.error.exception.marker.CircuitBreakerRecordMarker
```

**효과:**
- 404 Not Found (CharacterNotFoundException)는 서킷에 영향 없음
- DB 장애, 외부 API 타임아웃만 서킷에 기록됨
- 정상적인 요청 흐름 유지

### 2. 예외 계층 구조에 따른 Marker 구현

```kotlin
// Good - 비즈니스 예외 (4xx)
abstract class ClientBaseException : BaseException, CircuitBreakerIgnoreMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)
}

// Good - 시스템 예외 (5xx)
abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, cause: Throwable) : super(errorCode, cause)
    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?) :
        super(errorCode, cause, *args)
}
```

**구체적 예외:**

```kotlin
// 비즈니스 예외 (IgnoreMarker 자동 구현)
class CharacterNotFoundException(errorCode: ErrorCode, vararg args: Any?) :
    ClientBaseException(errorCode, *args)

class InvalidApiKeyException(errorCode: ErrorCode, vararg args: Any?) :
    ClientBaseException(errorCode, *args)

// 시스템 예외 (RecordMarker 자동 구현)
class NexonApiTimeoutException(
    errorCode: ErrorCode,
    cause: Throwable,
    vararg args: Any?
) : ServerBaseException(errorCode, cause, *args)

class DatabaseConnectionException(errorCode: ErrorCode, cause: Throwable) :
    ServerBaseException(errorCode, cause)
```

### 3. 적절한 로그 레벨 사용

```kotlin
// Good - 예외 타입에 따른 로그 레벨 구분
when (exception) {
    is ClientBaseException -> {
        // 비즈니스 예외: WARN (정상적인 요청 흐름)
        log.warn("Business exception occurred: {}", exception.message)
    }
    is ServerBaseException -> {
        // 시스템 예외: ERROR (장애 상황)
        log.error("System exception occurred", exception)
    }
}
```

**기준:**
| 상황 | 로그 레벨 | 스택 트레이스 | 예시 |
|------|----------|--------------|------|
| 사용자 입력 오류 | WARN | 선택적 | ign 없음, 잘못된 API Key |
| 리소스 없음 | WARN | 선택적 | 404 Not Found |
| DB 장애 | ERROR | 포함 | Connection Timeout |
| 외부 API 장애 | ERROR | 포함 | Nexon API Timeout |
| 네트워크 장애 | ERROR | 포함 | Socket Exception |

### 4. 구체적인 Circuit Breaker 설정

```yaml
# Good - 인스턴스별 설정
resilience4j:
  circuitbreaker:
    configs:
      default:
        registerHealthIndicator: true
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          - maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          - maple.expectation.error.exception.marker.CircuitBreakerRecordMarker
    instances:
      nexonApi:
        baseConfig: default
        minimumNumberOfCalls: 10  # 최소 10번 호출 후 통계 산출
      likeSyncDb:
        baseConfig: default
        slidingWindowSize: 5
        minimumNumberOfCalls: 3
      characterCalculation:
        baseConfig: default
        slidingWindowSize: 20
        minimumNumberOfCalls: 15
```

**설정 가이드:**
| 파라미터 | 권장값 | 설명 | 조정 기준 |
|----------|--------|------|----------|
| `slidingWindowSize` | 10 | 통계 윈도우 크기 | 호출 빈도가 높으면 증가 |
| `failureRateThreshold` | 50 | 실패율 임계값 (%) | 엄격한 차단 필요 시 감소 |
| `waitDurationInOpenState` | 10s | OPEN 상태 유지 시간 | 외부 API 복구 시간 고려 |
| `minimumNumberOfCalls` | 10 | 최소 호출 수 | 초기 버킷效应 방지 |
| `permittedNumberOfCallsInHalfOpenState` | 3 | HALF_OPEN 시 허용 호출 | 너무 낮으면 민감도 증가 |

### 5. Fallback 전략 구현

```java
// Good - Fallback으로 Graceful Degradation
@Service
@RequiredArgsConstructor
public class ResilientNexonApiClient {

    private final NexonApiClient delegate;
    private final CharacterRepository repository;
    private final LogicExecutor executor;

    @CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackFromCache")
    @Retry(name = "nexonApi")
    public NexonApiCharacterResponse getCharacter(String ign) {
        return delegate.getCharacter(ign);
    }

    // Scenario A: 캐시 존재 시 만료된 데이터 반환
    private NexonApiCharacterResponse fallbackFromCache(String ign, Exception e) {
        return executor.executeOrDefault(
            () -> {
                Optional<Character> cached = repository.findById(ign);
                if (cached.isPresent()) {
                    log.warn("Using stale cache: ign={} (Scenario A)", ign);
                    return cached.get().toApiResponse();
                }
                // Scenario B: 캐시 없음 -> 에러 응답
                throw new CharacterNotFoundException(
                    ErrorCode.CHARACTER_NOT_FOUND,
                    ign
                );
            },
            null,
            TaskContext.of("ResilientNexonApi", "Fallback", ign)
        );
    }
}
```

**Fallback 시나리오:**
| 시나리오 | 상황 | 동작 | 사용자 영향 |
|----------|------|------|----------|
| **Scenario A** | API 실패 + DB 캐시 존재 | 만료된 데이터 반환 (15분 전) | 서비스 유지 |
| **Scenario B** | API 실패 + DB 캐시 없음 | 404 에러 응답 | 빠른 피드백 |
| **Scenario C** | API 타임아웃 (>3초) | 타임아웃 후 A/B 분기 | 3초 후 결과 확인 |

### 6. @Retry와 @CircuitBreaker 조합

```java
// Good - Retry와 CircuitBreaker 조합
@Service
@RequiredArgsConstructor
public class ResilientNexonApiClient {

    // 순서: Retry -> CircuitBreaker
    // 1. 일시적 장애: 재시도로 복구
    // 2. 지속적 장애: CircuitBreaker가 차단
    @Retry(name = "nexonApi")
    @CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackFromCache")
    public NexonApiCharacterResponse getCharacter(String ign) {
        return delegate.getCharacter(ign);
    }
}
```

**annotation 순서:** `@Retry` -> `@CircuitBreaker` (내부에서 외부로 실행)

```yaml
resilience4j:
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 500ms
        ignoreExceptions:
          - maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          - maple.expectation.error.exception.marker.CircuitBreakerRecordMarker
```

### 7. 메트릭 수집 및 모니터링

```java
// Good - Micrometer 메트릭 등록
@Component
@RequiredArgsConstructor
public class CircuitBreakerMetrics {

    private final MeterRegistry meterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @PostConstruct
    public void registerMetrics() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            CircuitBreaker.Metrics metrics = cb.getMetrics();

            // 실패율
            Gauge.builder("circuitbreaker.failure.rate", metrics, Metrics::getFailureRate)
                .tag("name", cb.getName())
                .register(meterRegistry);

            // 상태 (CLOSED, OPEN, HALF_OPEN)
            Gauge.builder("circuitbreaker.state", cb, this::getStateAsNumber)
                .tag("name", cb.getName())
                .tag("actual_state", cb.getState().name())
                .register(meterRegistry);
        });
    }

    private double getStateAsNumber(CircuitBreaker cb) {
        return switch (cb.getState()) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
    }
}
```

**제공 메트릭:**
| 메트릭 | 설명 |
|--------|------|
| `resilience4j.circuitbreaker.state` | 서킷 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j.circuitbreaker.failure.rate` | 실패율 (%) |
| `resilience4j.circuitbreaker.buffered.calls` | 버퍼링된 호출 수 |
| `resilience4j.circuitbreaker.failed.calls` | 실패한 호출 수 |
| `resilience4j.circuitbreaker.successful.calls` | 성공한 호출 수 |

### 8. Actuator 엔드포인트 설정

```yaml
# Good - Actuator 엔드포인트 활성화
management:
  health:
    circuitbreakers:
      enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,metrics,circuitbreakers
  endpoint:
    health:
      show-details: always
```

**엔드포인트:**
| 경로 | 설명 |
|------|------|
| `/actuator/health` | 전체 건강 상태 (Circuit Breaker 포함) |
| `/actuator/circuitbreakers` | 모든 Circuit Breaker 상태 |
| `/actuator/metrics/resilience4j.circuitbreaker.state` | 서킷 상태 메트릭 |

## 코드 예시: 전체 흐름

```java
@Service
@RequiredArgsConstructor
public class ResilientCharacterService {

    private final NexonApiClient nexonApiClient;
    private final CharacterRepository repository;
    private final LogicExecutor executor;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // 1. Circuit Breaker + Retry + Fallback 조합
    @Retry(name = "nexonApi")
    @CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackFromCache")
    public CharacterDto getCharacter(String ign) {
        return executor.executeWithTranslation(
            () -> nexonApiClient.getCharacter(ign),
            ExceptionTranslator.forNexonApi(),
            TaskContext.of("CharacterService", "GetCharacter", ign)
        );
    }

    // 2. Fallback: Graceful Degradation
    private CharacterDto fallbackFromCache(String ign, Exception e) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("nexonApi");
        CircuitBreaker.State state = cb.getState();

        log.warn("CircuitBreaker fallback: ign={}, state={}, exception={}",
                 ign, state, e.getMessage());

        return executor.executeOrDefault(
            () -> {
                Optional<Character> cached = repository.findById(ign);
                if (cached.isPresent()) {
                    Character character = cached.get();
                    log.info("Returning stale cache: ign={}, cachedAt={}",
                             ign, character.getCachedAt());
                    return character.toDto();
                }
                throw new CharacterNotFoundException(ErrorCode.CHARACTER_NOT_FOUND, ign);
            },
            null,
            TaskContext.of("CharacterService", "Fallback", ign)
        );
    }
}
```

## 관련 문서 링크

### 상위 문서
- [CLAUDE.md](../../../../CLAUDE.md) Section 12-1: Circuit Breaker & Resilience Rules (lines 346-355)

### 기술 가이드
- [resilience.md](../../../../03_Technical_Guides/resilience.md) - 외부 API 장애 대응 전략 (A/B/C 시나리오)
- [infrastructure.md](../../../../03_Technical_Guides/infrastructure.md) Section 17: TieredCache & Cache Stampede Prevention

### 관련 Guardrails
- [marker-interface.md](./marker-interface.md) - Marker Interface Pattern
- [fallback.md](./fallback.md) - Fallback Strategy & Graceful Degradation
- [exception-handling.md](../spring/exception-handling.md) - Exception Handling Strategy
- [logic-executor.md](../spring/logic-executor.md) - Zero Try-Catch Policy & LogicExecutor

### 관련 ADR
- [ADR-005: Resilience4j Scenario A/B/C](../../../../01_ADR/ADR-005-resilience4j-scenario-abc.md)
- [ADR-052: Resilience4j Circuit Breaker](../../../../01_ADR/ADR-052-resilience4j-circuit-breaker.md)

### 증거 (Evidence)
- **ResilientNexonApiClient:** `src/main/java/maple/expectation/external/impl/ResilientNexonApiClient.java`
- **Marker Interfaces:** `src/main/java/maple/expectation/global/error/exception/marker/`
- **Configuration:** `src/main/resources/application.yml` (resilience4j 섹션)
- **Tests:** `src/test/java/maple/expectation/external/ResilientNexonApiClientTest.java`

## 검증 명령어

```bash
# CircuitBreaker 설정 확인
grep -A 30 "resilience4j:" src/main/resources/application.yml

# Marker Interface 확인
find src/main/java -name "*Marker.java"

# @CircuitBreaker 사용 확인
grep -r "@CircuitBreaker" src/main/java --include="*.java"

# fallbackMethod 구현 확인
grep -r "fallbackMethod" src/main/java --include="*.java"

# Circuit Breaker 상태 확인 (실행 중인 애플리케이션)
curl -s http://localhost:8080/actuator/health | jq

# Circuit Breaker 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq
```
