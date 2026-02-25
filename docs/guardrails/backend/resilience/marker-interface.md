---
id: GR-RESILIENCE-002
category: backend/resilience
severity: critical
keywords: [Marker, Interface, Exception, CircuitBreaker, CircuitBreakerIgnoreMarker, CircuitBreakerRecordMarker]
---

# Circuit Breaker Marker Interface Guardrail

## 개요

Circuit Breaker의 정확한 동작을 위해 **Marker Interface** 패턴을 사용하여 예외를 분류합니다. 비즈니스 예외(4xx)는 서킷브레이커 상태에 영향을 주지 않도록 `CircuitBreakerIgnoreMarker`를, 시스템 예외(5xx)는 장애 발생 시 서킷브레이커를 작동시키도록 `CircuitBreakerRecordMarker`를 구현합니다.

> **설계 근거:** 모든 예외를 동일하게 처리하면 정상적인 요청 흐름(404 Not Found 등)으로 인해 서킷이 오픈되어 서비스 중단이 발생합니다. Marker Interface로 예외를 분류하여 올바른 서킷 동작을 보장합니다.

## 핵심 원칙

### 1. Marker Interface 정의

순수 Marker Interface (메서드 없음)로 예외 분류를 명시합니다.

### 2. 예외 기본 클래스에 Marker 구현

`ClientBaseException`과 `ServerBaseException`에 각각 적절한 Marker를 자동 구현합니다.

### 3. 구체적 예외는 기본 클래스 상속

모든 Custom Exception은 `ClientBaseException` 또는 `ServerBaseException`을 상속하여 Marker를 자동 상속받습니다.

### 4. Resilience4j 설정에 Marker 등록

`application.yml`에 Marker Interface 패키지 경로로 등록합니다.

## DON'T (안티패턴)

### 1. Marker Interface 없이 예외 정의

```kotlin
// Bad - Marker Interface 미구현
class CharacterNotFoundException(errorCode: ErrorCode) : BaseException(errorCode) {
    // CircuitBreakerIgnoreMarker 구현 없음
}

// 문제: 비즈니스 예외임에도 서킷브레이커가 실패로 카운트
```

**위험성:**
- 404 Not Found 같은 정상적인 요청 흐름이 서킷 오픈 유발
- 불필요한 서비스 중단
- 사용자 경험 저하

### 2. 잘못된 Marker 조합

```kotlin
// Bad - 비즈니스 예외에 RecordMarker 구현
class InvalidParameterException(errorCode: ErrorCode) :
    BaseException(errorCode),
    CircuitBreakerRecordMarker {  // 잘못됨: IgnoreMarker여야 함
}

// Bad - 시스템 예외에 IgnoreMarker 구현
class DatabaseTimeoutException(errorCode: ErrorCode, cause: Throwable) :
    BaseException(errorCode, cause),
    CircuitBreakerIgnoreMarker {  // 잘못됨: RecordMarker여야 함
}
```

**위험성:**
- 잘못된 예외 분류로 서킷 오작동
- 장애 상황에서 서킷이 오픈되지 않음 (IgnoreMarker인 경우)
- 정상 요청에서 서킷이 오픈됨 (RecordMarker인 경우)

### 3. 직접 Exception을 던져 Marker 우회

```java
// Bad - RuntimeException 직접 사용
if (character == null) {
    throw new RuntimeException("Character not found");
    // CircuitBreakerIgnoreMarker가 구현되지 않아
    // Resilience4j 기본 설정에 따라 실패로 카운트됨
}
```

**위험성:**
- 비즈니스 맥락 없는 모호한 예외
- 서킷브레이커 오작동
- 모니터링 지표 부정확

### 4. unchecked exception으로 변환 시 Marker 누락

```java
// Bad - checked exception 변환 시 Marker 미포함
try {
    externalApi.call();
} catch (IOException e) {
    throw new RuntimeException("API call failed", e);
    // ServerBaseException을 사용해야 CircuitBreakerRecordMarker 자동 구현
}
```

**위험성:**
- 외부 API 장애가 서킷에 기록되지 않음
- 장애 확산 방지 실패

### 5. 기본 Exception 상속

```kotlin
// Bad - Exception 직접 상속
class CustomException(message: String) : Exception(message) {
    // Marker 구현 없음
}
```

**위험성:**
- 예외 타입 기반 분류 불가
- 일관되지 않은 예외 처리

## DO (베스트 프랙티스)

### 1. Marker Interface 정의

```kotlin
// Good - 순수 Marker Interface (메서드 없음)
package maple.expectation.error.exception.marker

/**
 * Marker interface for exceptions that should be ignored by circuit breaker.
 *
 * <p>Business logic exceptions (4xx) should implement this interface to prevent
 * circuit breaker from opening due to expected client errors.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>CharacterNotFoundException (404)</li>
 *   <li>InvalidApiKeyException (401)</li>
 *   <li>InvalidParameterException (400)</li>
 * </ul>
 */
interface CircuitBreakerIgnoreMarker

/**
 * Marker interface for exceptions that should trigger circuit breaker recording.
 *
 * <p>System/infrastructure exceptions (5xx) should implement this interface to
 * ensure circuit breaker tracks failures and opens when threshold is reached.</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>NexonApiTimeoutException (504)</li>
 *   <li>DatabaseConnectionException (500)</li>
 *   <li>RedisConnectionException (503)</li>
 * </ul>
 */
interface CircuitBreakerRecordMarker
```

### 2. 예외 기본 클래스에 Marker 구현

```kotlin
// Good - ClientBaseException에 IgnoreMarker 자동 구현
package maple.expectation.error.exception.base

import maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker

/**
 * Base class for all business exceptions (4xx).
 *
 * <p>Implements CircuitBreakerIgnoreMarker to prevent circuit breaker
 * from recording business logic failures as system errors.</p>
 */
abstract class ClientBaseException : BaseException, CircuitBreakerIgnoreMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)
}

// Good - ServerBaseException에 RecordMarker 자동 구현
package maple.expectation.error.exception.base

import maple.expectation.error.exception.marker.CircuitBreakerRecordMarker

/**
 * Base class for all system/infrastructure exceptions (5xx).
 *
 * <p>Implements CircuitBreakerRecordMarker to ensure circuit breaker
 * tracks system failures and opens when threshold is reached.</p>
 */
abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, cause: Throwable) : super(errorCode, cause)
    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?) :
        super(errorCode, cause, *args)
}
```

### 3. 구체적 예외는 기본 클래스 상속

```kotlin
// Good - 비즈니스 예외
package maple.expectation.error.exception.client

import maple.expectation.error.exception.base.ClientBaseException

class CharacterNotFoundException(
    errorCode: ErrorCode,
    vararg args: Any?
) : ClientBaseException(errorCode, *args) {
    // CircuitBreakerIgnoreMarker 자동 상속
}

class InvalidApiKeyException(
    errorCode: ErrorCode,
    vararg args: Any?
) : ClientBaseException(errorCode, *args) {
    // CircuitBreakerIgnoreMarker 자동 상속
}

class CubeAccessDeniedException(
    errorCode: ErrorCode,
    vararg args: Any?
) : ClientBaseException(errorCode, *args) {
    // CircuitBreakerIgnoreMarker 자동 상속
}

// Good - 시스템 예외
package maple.expectation.error.exception.server

import maple.expectation.error.exception.base.ServerBaseException

class NexonApiTimeoutException(
    errorCode: ErrorCode,
    cause: Throwable,
    vararg args: Any?
) : ServerBaseException(errorCode, cause, *args) {
    // CircuitBreakerRecordMarker 자동 상속
}

class DatabaseConnectionException(
    errorCode: ErrorCode,
    cause: Throwable
) : ServerBaseException(errorCode, cause) {
    // CircuitBreakerRecordMarker 자동 상속
}

class RedisConnectionException(
    errorCode: ErrorCode,
    cause: Throwable,
    vararg args: Any?
) : ServerBaseException(errorCode, cause, *args) {
    // CircuitBreakerRecordMarker 자동 상속
}
```

### 4. Resilience4j 설정에 Marker 등록

```yaml
# Good - Marker Interface 패키지 경로로 등록
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        ignoreExceptions:
          # 비즈니스 예외는 실패 카운트 제외
          - maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker
        recordExceptions:
          # 시스템 예외만 실패로 기록
          - maple.expectation.error.exception.marker.CircuitBreakerRecordMarker
```

**핵심:**
- FQCN (Fully Qualified Class Name)으로 등록
- 패키지 경로 포함
- 인터페이스 이름으로 등록 (구현 클래스 모두 적용)

### 5. 예외 변환 시 기본 클래스 사용

```kotlin
// Good - Checked exception을 도메인 예외로 변환
@Service
@RequiredArgsConstructor
class NexonApiService(
    private val executor: LogicExecutor,
    private val nexonApiClient: NexonApiClient
) {
    fun getCharacter(ign: String): NexonApiCharacterResponse {
        return executor.executeWithTranslation(
            { nexonApiClient.getCharacter(ign) },
            ExceptionTranslator.forNexonApi(),
            TaskContext.of("NexonApiService", "GetCharacter", ign)
        )
    }
}

// ExceptionTransformer 내부
object ExceptionTranslator {
    fun forNexonApi(): ExceptionTransformer<NexonApiCharacterResponse> {
        return ExceptionTransformer { cause ->
            when (cause) {
                is IOException -> NexonApiTimeoutException(
                    ErrorCode.API_TIMEOUT,
                    cause,  // cause 보존
                    cause.message
                )  // ServerBaseException 상속 -> CircuitBreakerRecordMarker 자동 구현
                is JsonProcessingException -> NexonApiParseException(
                    ErrorCode.API_PARSE_ERROR,
                    cause,
                    cause.message
                )  // ServerBaseException 상속
                else -> InternalServerException(
                    ErrorCode.INTERNAL_ERROR,
                    cause
                )  // ServerBaseException 상속
            }
        }
    }
}
```

### 6. 테스트로 Marker 구현 검증

```kotlin
// Good - Marker 구현 테스트
@DisplayName("CircuitBreakerIgnoreMarker 구현 확인")
class ClientExceptionMarkerTest {

    @Test
    fun `CharacterNotFoundException는 IgnoreMarker를 구현해야 한다`() {
        val exception = CharacterNotFoundException(
            ErrorCode.CHARACTER_NOT_FOUND,
            "testIgn"
        )

        assertThat(exception)
            .isInstanceOf(CircuitBreakerIgnoreMarker::class.java)
            .isNotInstanceOf(CircuitBreakerRecordMarker::class.java)
    }

    @Test
    fun `InvalidApiKeyException는 IgnoreMarker를 구현해야 한다`() {
        val exception = InvalidApiKeyException(
            ErrorCode.INVALID_API_KEY,
            "test_key"
        )

        assertThat(exception)
            .isInstanceOf(CircuitBreakerIgnoreMarker::class.java)
    }
}

@DisplayName("CircuitBreakerRecordMarker 구현 확인")
class ServerExceptionMarkerTest {

    @Test
    fun `NexonApiTimeoutException은 RecordMarker를 구현해야 한다`() {
        val cause = IOException("Connection timeout")
        val exception = NexonApiTimeoutException(
            ErrorCode.API_TIMEOUT,
            cause,
            "https://api.nexon.com"
        )

        assertThat(exception)
            .isInstanceOf(CircuitBreakerRecordMarker::class.java)
            .isNotInstanceOf(CircuitBreakerIgnoreMarker::class.java)
    }

    @Test
    fun `DatabaseConnectionException은 RecordMarker를 구현해야 한다`() {
        val cause = SQLException("Connection refused")
        val exception = DatabaseConnectionException(
            ErrorCode.DATABASE_CONNECTION_FAILED,
            cause
        )

        assertThat(exception)
            .isInstanceOf(CircuitBreakerRecordMarker::class.java)
    }
}
```

## 예외 분류 기준

| 예외 타입 | HTTP Status | Marker Interface | 사용 사례 | 예시 |
|----------|-------------|------------------|----------|------|
| **비즈니스 예외** | 4xx | `CircuitBreakerIgnoreMarker` | 잘못된 입력, 리소스 없음, 권한 없음 | CharacterNotFoundException (404) |
| **시스템 예외** | 5xx | `CircuitBreakerRecordMarker` | DB 장애, 외부 API 타임아웃, 메모리 부족 | NexonApiTimeoutException (504) |

### 구체적 예시

#### ClientBaseException (IgnoreMarker)

| 예외명 | HTTP Status | 설명 |
|--------|-------------|------|
| `CharacterNotFoundException` | 404 | 캐릭터를 찾을 수 없음 |
| `InvalidApiKeyException` | 401 | API Key가 유효하지 않음 |
| `InvalidParameterException` | 400 | 요청 파라미터가 잘못됨 |
| `CubeAccessDeniedException` | 403 | 큐브 접근 권한 없음 |
| `DuplicateLikeException` | 409 | 이미 좋아요를 누른 상태 |

#### ServerBaseException (RecordMarker)

| 예외명 | HTTP Status | 설명 |
|--------|-------------|------|
| `NexonApiTimeoutException` | 504 | Nexon API 타임아웃 |
| `NexonApiParseException` | 502 | Nexon API 응답 파싱 실패 |
| `DatabaseConnectionException` | 500 | DB 연결 실패 |
| `RedisConnectionException` | 503 | Redis 연결 실패 |
| `InternalServerException` | 500 | 내부 서버 오류 |

## 코드 예시: 전체 흐름

```kotlin
// 1. Marker Interface 정의
package maple.expectation.error.exception.marker

interface CircuitBreakerIgnoreMarker
interface CircuitBreakerRecordMarker

// 2. 기본 클래스에 Marker 구현
package maple.expectation.error.exception.base

abstract class ClientBaseException : BaseException, CircuitBreakerIgnoreMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?) : super(errorCode, *args)
}

abstract class ServerBaseException : BaseException, CircuitBreakerRecordMarker {
    constructor(errorCode: ErrorCode) : super(errorCode)
    constructor(errorCode: ErrorCode, cause: Throwable) : super(errorCode, cause)
}

// 3. 구체적 예외 정의
package maple.expectation.error.exception.client

class CharacterNotFoundException(
    errorCode: ErrorCode,
    vararg args: Any?
) : ClientBaseException(errorCode, *args)

// 4. 사용
@Service
@RequiredArgsConstructor
class CharacterService(
    private val executor: LogicExecutor,
    private val repository: CharacterRepository
) {
    fun getCharacter(ign: String): CharacterDto {
        return executor.executeOrDefault(
            {
                repository.findById(ign)
                    .orElseThrow { CharacterNotFoundException(ErrorCode.CHARACTER_NOT_FOUND, ign) }
            },
            null,
            TaskContext.of("CharacterService", "GetCharacter", ign)
        )
    }
}
```

## 관련 문서 링크

### 상위 문서
- [CLAUDE.md](../../../../CLAUDE.md) Section 11: Exception Handling Strategy (lines 290-300)
- [CLAUDE.md](../../../../CLAUDE.md) Section 12-1: Circuit Breaker & Resilience Rules (lines 346-355)

### 관련 Guardrails
- [circuit-breaker.md](./circuit-breaker.md) - Circuit Breaker Pattern (Resilience4j)
- [exception-handling.md](../spring/exception-handling.md) - Exception Handling Strategy
- [logic-executor.md](../spring/logic-executor.md) - Zero Try-Catch Policy & LogicExecutor

### 관련 ADR
- [ADR-052: Resilience4j Circuit Breaker](../../../../01_ADR/ADR-052-resilience4j-circuit-breaker.md) (lines 80-83, 169-170)
- [ADR-044: LogicExecutor Zero Try-Catch](../../../../01_ADR/ADR-044-logicexecutor-zero-try-catch.md) (lines 213-214, 393-425)

### 코드 (Evidence)
- `module-common/src/main/kotlin/maple/expectation/error/exception/marker/CircuitBreakerIgnoreMarker.kt`
- `module-common/src/main/kotlin/maple/expectation/error/exception/marker/CircuitBreakerRecordMarker.kt`
- `module-common/src/main/kotlin/maple/expectation/error/exception/base/ClientBaseException.kt`
- `module-common/src/main/kotlin/maple/expectation/error/exception/base/ServerBaseException.kt`

### 설정
- `module-app/src/main/resources/application.yml` (lines 121-125)

### 테스트
- `module-infra/src/test/java/maple/expectation/infrastructure/executor/LogicExecutorTest.java`
- `module-app/src/test/resources/application.yml` (lines 39-42)

## 검증 명령어

```bash
# Marker Interface 정의 확인
find src/main/java -name "CircuitBreakerIgnoreMarker.java" -o -name "CircuitBreakerRecordMarker.java"

# 기본 클래스 Marker 구현 확인
grep -r "implements CircuitBreakerIgnoreMarker\|implements CircuitBreakerRecordMarker" src/main/java

# application.yml에 Marker 등록 확인
grep -A 10 "ignoreExceptions:" src/main/resources/application.yml

# RuntimeException 직접 사용 확인 (금지)
grep -r "throw new RuntimeException" src/main/java --include="*.java" --include="*.kt"
```
