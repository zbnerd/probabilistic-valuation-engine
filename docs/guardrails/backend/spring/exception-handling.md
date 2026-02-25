---
id: GR-002
category: backend/spring
severity: critical
keywords: [RuntimeException, exception, throw, ClientBaseException, ServerBaseException, checked exception, exception chaining]
---

# Exception Handling Strategy

## 개요

예외 처리는 시스템의 **회복 탄력성(Resilience)**과 **디버깅 가시성**을 확보하는 핵심 수단입니다. 모든 예외는 비즈니스 맥락이 담긴 **Custom Exception**을 정의하고, Circuit Breaker 동작을 위해 적절한 **Marker Interface**를 구현해야 합니다.

> **설계 근거:** RuntimeException 직접 사용은 비즈니스 맥락을 상실하고, Circuit Breaker 오작동을 유발하며, 디버깅을 어렵게 만듭니다.

## 핵심 원칙

### 1. 예외 계층 구조 (Hierarchy)

| 예외 타입 | HTTP Status | Marker Interface | 사용 사례 |
|----------|-------------|------------------|----------|
| **ClientBaseException** | 4xx | `CircuitBreakerIgnoreMarker` | 비즈니스 예외 - 서킷브레이커 상태에 영향을 주지 않음 |
| **ServerBaseException** | 5xx | `CircuitBreakerRecordMarker` | 시스템/인프라 예외 - 장애 발생 시 서킷브레이커를 작동시킴 |

### 2. No Ambiguous Exceptions

`RuntimeException`, `Exception` 등을 직접 던지는 것을 금지하며, 반드시 비즈니스 맥락이 담긴 **Custom Exception**을 정의합니다.

### 3. Checked to Unchecked 변환

`IOException` 등 체크 예외는 발생 지점에서 `catch`하여 적절한 `ServerBaseException`으로 변환합니다. 이때 원인 예외(`cause`)를 넘겨 **Exception Chaining**을 유지합니다.

### 4. Dynamic Message

`String.format`을 활용하여 에러 메시지에 구체적인 식별자(ID, IGN 등)를 포함해 디버깅 가시성을 높입니다.

### 5. Logging Level 구분

- **비즈니스 예외(4xx):** `log.warn`을 사용하여 비정상적인 요청 흐름 기록
- **서버/외부 API 예외(5xx):** `log.error`를 사용하여 스택 트레이스와 함께 장애 상황 기록

## DON'T (안티패턴)

### 1. RuntimeException 직접 사용

```java
// Bad - RuntimeException 직접 사용
if (user == null) {
    throw new RuntimeException("User not found");
}
```

**위험성:**
- 비즈니스 맥락 없는 모호한 예외
- Circuit Breaker 오작동 유발 (RuntimeException은 실패로 카운트됨)
- 디버깅 어려움 (구체적인 실패 원인 불명확)
- 모니터맅 지표 부정확

### 2. Exception 직접 사용

```java
// Bad - Exception 직접 사용
if (apiKey == null) {
    throw new Exception("API Key is required");
}
```

**위험성:**
- 체크 예외로 인한 호출부 부담
- 비즈니스 맥락 상실
- 일관되지 않은 예외 처리

### 3. Checked Exception 변환 시 RuntimeException 사용

```java
// Bad - IOException을 RuntimeException으로 변환
try {
    return externalApi.call();
} catch (IOException e) {
    throw new RuntimeException("API call failed", e);
}
```

**위험성:**
- Circuit Breaker가 외부 API 장애를 식별하지 못함
- 모든 예외가 동일한 RuntimeException으로 처리됨
- 장애 추적 어려움

### 4. 원인 예외 누락 (Exception Chaining 위반)

```java
// Bad - cause를 넘기지 않음
try {
    return externalApi.call();
} catch (IOException e) {
    log.error("API failed", e);
    throw new NexonApiException(ErrorCode.API_ERROR);  // cause 누락
}
```

**위험성:**
- 스택 트레이스 끊김
- 근본 원인(Root Cause) 추적 불가

### 5. 정적 에러 메시지 (Dynamic Message 미사용)

```java
// Bad - 정적 메시지
throw new UserNotFoundException(ErrorCode.USER_NOT_FOUND);
// 메시지: "User not found" (어떤 사용자인지不明)

// Bad - 식별자 없음
throw new UserNotFoundException(
    ErrorCode.USER_NOT_FOUND,
    "User not found"  // ID가 포함되지 않음
);
```

**위험성:**
- 로그에서 실패한 대상 식별 불가
- 디버깅 시간 증가
- 운영 장애 대응 지연

### 6. 로그 레벨 구분 없는 기록

```java
// Bad - 비즈니스 예외도 error로 기록
try {
    return characterService.find(ign);
} catch (CharacterNotFoundException e) {
    log.error("Character not found", e);  // WARN이 적합한 데 ERROR 사용
    return null;
}

// Bad - 시스템 예외를 warn으로 기록
try {
    return database.query(sql);
} catch (DatabaseException e) {
    log.warn("Database query failed", e);  // ERROR가 적합한 데 WARN 사용
    throw e;
}
```

## DO (베스트 프랙티스)

### 1. ClientBaseException (비즈니스 예외 - 4xx)

```java
// Good - 비즈니스 예외 정의
public class UserNotFoundException extends ClientBaseException {
    public UserNotFoundException(Long userId) {
        super(ErrorCode.USER_NOT_FOUND,
              String.format("User not found: userId=%d", userId));
    }
}

public class InvalidApiKeyException extends ClientBaseException {
    public InvalidApiKeyException(String apiKey) {
        super(ErrorCode.INVALID_API_KEY,
              String.format("Invalid API key: apiKey=%s", maskApiKey(apiKey)));
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
```

**특징:**
- `CircuitBreakerIgnoreMarker` 자동 구현
- 비즈니스 로직상 예상 가능한 실패 (잘못된 입력, 리소스 없음, 권한 없음)
- 서킷브레이커 상태에 영향을 주지 않음
- 로그 레벨: `log.warn`

### 2. ServerBaseException (시스템 예외 - 5xx)

```java
// Good - 시스템 예외 정의
public class NexonApiTimeoutException extends ServerBaseException {
    public NexonApiTimeoutException(ErrorCode errorCode, Throwable cause, String url) {
        super(errorCode, cause,
              String.format("Nexon API timeout: url=%s, message=%s",
                           url, cause.getMessage()));
    }
}

public class DatabaseConnectionException extends ServerBaseException {
    public DatabaseConnectionException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause,
              String.format("Database connection failed: message=%s", cause.getMessage()));
    }
}
```

**특징:**
- `CircuitBreakerRecordMarker` 자동 구현
- 시스템/인프라 장애 (DB 장애, 외부 API 타임아웃, 메모리 부족)
- 서킷브레이커가 실패를 기록하고 임계값 도달 시 OPEN
- 로그 레벨: `log.error`

### 3. Checked Exception 변환 (LogicExecutor 사용)

```java
// Good - Checked exception을 도메인 예외로 변환
public NexonApiCharacterResponse fetchCharacter(String ign) {
    return executor.executeWithTranslation(
        () -> nexonApiClient.getCharacter(ign),
        ExceptionTranslator.forNexonApi(),
        TaskContext.of("NexonApi", "GetCharacter", ign)
    );
}

// ExceptionTransformer 내부
public static ExceptionTransformer<NexonApiCharacterResponse> forNexonApi() {
    return cause -> {
        if (cause instanceof IOException io) {
            // IOException -> NexonApiTimeoutException (ServerBaseException)
            return new NexonApiTimeoutException(
                ErrorCode.API_TIMEOUT,
                io,  // cause 보존 (Exception Chaining)
                String.format("https://api.nexon.com/character/%s", ign)
            );
        }
        return new InternalServerException(ErrorCode.INTERNAL_ERROR, cause);
    };
}
```

**핵심:**
- Checked exception 발생 지점에서 즉시 변환
- 원인 예외(cause)를 넘겨 Exception Chaining 유지
- LogicExecutor.executeWithTranslation() 사용

### 4. Dynamic Message (식별자 포함)

```java
// Good - ID, IGN 등 식별자 포함
public class CharacterNotFoundException extends ClientBaseException {
    public CharacterNotFoundException(String ign) {
        super(ErrorCode.CHARACTER_NOT_FOUND,
              String.format("Character not found: ign=%s", ign));
    }
}

public class CubeNotFoundException extends ClientBaseException {
    public CubeNotFoundException(Long cubeId, String userIgn) {
        super(ErrorCode.CUBE_NOT_FOUND,
              String.format("Cube not found: cubeId=%d, userIgn=%s", cubeId, userIgn));
    }
}
```

**효과:**
- 로그에서 실패한 대상 즉시 식별
- 운영 장애 대응 시간 단축
- 모니터링 대시보드에서 구체적인 실패 내용 확인

### 5. Logging Level 구분

```kotlin
// Good - 예외 타입에 따른 로그 레벨 구분
when (exception) {
    is ClientBaseException -> {
        // 비즈니스 예외: WARN (정상적인 요청 흐름)
        log.warn("Business exception occurred: message={}, context={}",
                 exception.message, exception.context)
    }
    is ServerBaseException -> {
        // 시스템 예외: ERROR (장애 상황)
        log.error("System exception occurred: message={}, context={}",
                  exception.message, exception.context, exception)
    }
}
```

**기준:**
| 예외 타입 | 로그 레벨 | 스택 트레이스 | 사유 |
|----------|----------|--------------|------|
| ClientBaseException | WARN | 선택적 | 비정상적인 요청 흐름 (정상 범주) |
| ServerBaseException | ERROR | 포함 | 장애 상황 (즉시 대응 필요) |

### 6. Exception Chaining (원인 예외 보존)

```java
// Good - cause를 넘겨 스택 트레이스 유지
try {
    return externalApi.call();
} catch (IOException e) {
    throw new NexonApiException(
        ErrorCode.API_ERROR,
        e,  // cause 보존
        String.format("API call failed: url=%s", url)
    );
}

// 로그 출력 시:
// NexonApiException: API call failed: url=https://api.example.com
// Caused by: java.net.SocketTimeoutException: Read timed out
//    at java.net.SocketInputStream.socketRead0(Native Method)
//    ...
```

**효과:**
- 근본 원인(Root Cause) 추적 가능
- 스택 트레이스 전체 보존
- 디버깅 시간 단축

## 예외 변환 패턴 (ExceptionTransformer)

### LogicExecutor.executeWithTranslation() 사용

```java
// 패턴: Checked Exception -> ServerBaseException
return executor.executeWithTranslation(
    () -> riskyOperation(),  // checked exception 발생 가능
    ExceptionTranslator.forOperation(),
    TaskContext.of("Domain", "Operation", id)
);
```

### 예외 변환기 구현

```java
public final class ExceptionTranslator {
    public static <T> ExceptionTransformer<T> forNexonApi() {
        return cause -> {
            if (cause instanceof IOException io) {
                return new NexonApiTimeoutException(
                    ErrorCode.API_TIMEOUT,
                    io,
                    apiEndpoint
                );
            }
            if (cause instanceof JsonProcessingException json) {
                return new NexonApiParseException(
                    ErrorCode.API_PARSE_ERROR,
                    json,
                    apiEndpoint
                );
            }
            return new InternalServerException(
                ErrorCode.INTERNAL_ERROR,
                cause
            );
        };
    }

    public static <T> ExceptionTransformer<T> forRedis() {
        return cause -> {
            if (cause instanceof RedisConnectionException e) {
                return new RedisConnectionFailedException(
                    ErrorCode.REDIS_CONNECTION_FAILED,
                    e,
                    redisKey
                );
            }
            return new CacheOperationException(
                ErrorCode.CACHE_OPERATION_FAILED,
                cause
            );
        };
    }
}
```

## GlobalExceptionHandler (최종 방어선)

모든 예외는 `GlobalExceptionHandler`를 통해 규격화된 응답으로 변환됩니다.

```java
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    @ExceptionHandler(ClientBaseException.class)
    protected ResponseEntity<ErrorResponse> handleClientException(
            ClientBaseException e, WebRequest request) {
        log.warn("Client exception: {}", e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getStatus())
            .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(ServerBaseException.class)
    protected ResponseEntity<ErrorResponse> handleServerException(
            ServerBaseException e, WebRequest request) {
        log.error("Server exception: {}", e.getMessage(), e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR,
                                  "Internal server error"));
    }
}
```

**규칙:**
- **Centralized Handling:** `@RestControllerAdvice`를 사용하여 전역적으로 예외 포착
- **Consistent Format:** 모든 에러 읅답은 `ErrorResponse` 레코드 형식 따름
- **보안:** 알 수 없는 시스템 예외는 상세 내용을 숨기고 `INTERNAL_SERVER_ERROR` 코드로 캡슐화

## 코드 예시: 전체 흐름

```java
// 1. Service Layer - Checked Exception 변환
@Service
@RequiredArgsConstructor
public class CharacterService {
    private final LogicExecutor executor;
    private final NexonApiClient nexonApiClient;

    public CharacterDto getCharacter(String ign) {
        return executor.executeWithTranslation(
            () -> nexonApiClient.getCharacter(ign),
            ExceptionTranslator.forNexonApi(),
            TaskContext.of("CharacterService", "GetCharacter", ign)
        );
    }
}

// 2. Business Logic - Custom Exception 사용
@Service
@RequiredArgsConstructor
public class CubeService {
    private final LogicExecutor executor;
    private final CubeRepository cubeRepository;

    public CubeDto getCube(Long cubeId, String userIgn) {
        return executor.executeOrDefault(
            () -> {
                Cube cube = cubeRepository.findById(cubeId)
                    .orElseThrow(() -> new CubeNotFoundException(cubeId, userIgn));
                if (!cube.getOwner().equals(userIgn)) {
                    throw new CubeAccessDeniedException(cubeId, userIgn);
                }
                return cube.toDto();
            },
            null,
            TaskContext.of("CubeService", "GetCube", cubeId, userIgn)
        );
    }
}

// 3. GlobalExceptionHandler - 일관된 응답
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CubeNotFoundException.class)
    protected ResponseEntity<ErrorResponse> handleCubeNotFound(
            CubeNotFoundException e) {
        log.warn("Cube not found: {}", e.getMessage());
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(ErrorCode.CUBE_NOT_FOUND, e.getMessage()));
    }
}
```

## 관련 문서 링크

### 상위 문서
- [CLAUDE.md](../../../../CLAUDE.md) Section 11: Exception Handling Strategy (lines 290-300)
- [CLAUDE.md](../../../../CLAUDE.md) Section 12-1: Circuit Breaker & Resilience Rules (lines 346-355)
- [CLAUDE.md](../../../../CLAUDE.md) Section 13: Global Error Mapping & Response (lines 358-366)

### 기술 가이드
- [infrastructure.md](../../../../03_Technical_Guides/infrastructure.md) Section 19: Security Best Practices (lines 539-631)
- [resilience.md](../../../../03_Technical_Guides/resilience.md) - 외부 API 장애 대응 전략

### 관련 Guardrails
- [logic-executor.md](./logic-executor.md) - Zero Try-Catch Policy & LogicExecutor
- [circuit-breaker.md](../resilience/circuit-breaker.md) - Circuit Breaker Pattern
- [marker-interface.md](../resilience/marker-interface.md) - Marker Interface Pattern
- [aop-facade.md](./aop-facade.md) - AOP & Facade Pattern

### 관련 ADR
- [ADR-052: Resilience4j Circuit Breaker](../../../../01_ADR/ADR-052-resilience4j-circuit-breaker.md)
- [ADR-044: LogicExecutor Zero Try-Catch](../../../../01_ADR/ADR-044-logicexecutor-zero-try-catch.md)

## 검증 명령어

```bash
# RuntimeException 사용 확인 (금지)
grep -r "throw new RuntimeException" src/main/java --include="*.java"

# Exception 사용 확인 (금지)
grep -r "throw new Exception(" src/main/java --include="*.java"

# ClientBaseException/ServerBaseException 사용 확인
grep -r "extends ClientBaseException\|extends ServerBaseException" src/main/java --include="*.java"

# Dynamic Message 포함 확인
grep -r "String.format" src/main/java --include="*.java" | grep Exception

# Exception Chaining 확인 (cause 파라미터)
grep -r "super(.*cause" src/main/java --include="*.java" | grep Exception
```
