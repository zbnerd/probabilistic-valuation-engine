# Module-Common API Manifest

**Version:** 1.0.0
**Last Updated:** 2025-03-15
**Package:** `maple.expectation.*`
**Purpose:** Comprehensive documentation of all public APIs exposed by module-common

---

## Table of Contents

1. [Overview](#overview)
2. [Stability Policy](#stability-policy)
3. [Breaking Change Categories](#breaking-change-categories)
4. [API Enumeration by Package](#api-enumeration-by-package)
5. [Usage Examples](#usage-examples)
6. [Migration Guide](#migration-guide)

---

## Overview

Module-Common provides shared infrastructure and utilities for the MapleExpectation application. It contains **65 Kotlin source files** exposing public APIs across 5 major packages:

### Package Summary

| Package | Public APIs | Purpose |
|---------|-------------|---------|
| `maple.expectation.error` | 3 | Error codes and responses |
| `maple.expectation.error.exception` | 45 | Domain exceptions |
| `maple.expectation.error.exception.base` | 3 | Exception hierarchy |
| `maple.expectation.error.exception.marker` | 2 | Circuit breaker markers |
| `maple.expectation.response` | 1 | API response wrapper |
| `maple.expectation.event` | 3 | Event handling |
| `maple.expectation.util` | 4 | Utility functions |
| `maple.expectation.common.function` | 2 | Functional interfaces |
| `maple.expectation.common.resource` | 1 | Resource loading |

**Total Public APIs:** 62 classes/interfaces/objects

---

## Stability Policy

### Stability Levels

| Level | Criteria | Change Policy | Examples |
|-------|----------|---------------|----------|
| **Stable** | No signature changes for 6+ months; used by ≥2 consumers | Breaking changes require major version bump + 30-day notice | `BaseException`, `ErrorCode`, `ApiResponse`, `GzipUtils` |
| **Beta** | API contract finalized but implementation evolving | 2-week deprecation notice required | Event handling annotations, New utility methods |
| **Deprecated** | Replacement API exists; scheduled for removal | Removal date documented; migration guide provided | Legacy exception constructors (if any) |

### Stability Assessment

As of 2025-03-15, the following APIs are classified as **Stable**:

- **Core Error Hierarchy:** `ErrorCode`, `CommonErrorCode`, `ErrorResponse`
- **Exception Base Classes:** `BaseException`, `ClientBaseException`, `ServerBaseException`
- **Response Wrapper:** `ApiResponse`
- **Utilities:** `GzipUtils`, `StringMaskingUtils`, `ExceptionUtils`, `InterruptUtils`
- **Event Infrastructure:** `EventHandler`, `EventPriority`, `EventVersion`
- **Resource Loading:** `ResourceLoader`

**Beta APIs:** None currently

**Deprecated APIs:** None currently

---

## Breaking Change Categories

| Category | Definition | Examples | Version Impact |
|----------|------------|----------|----------------|
| **Signature** | Method parameter added/removed, return type changed, constructor signature modified | - Adding required parameter to `BaseException` constructor<br>- Changing `ErrorCode` property types<br>- Modifying `ApiResponse.data` type | **Major** (X.0.0) |
| **Behavior** | Exception type changed, side effects modified, semantic behavior altered | - `ClientBaseException` starts throwing `ServerBaseException`<br>- `GzipUtils.compress()` changes compression level<br>- Event handler execution order changes | **Minor** (0.X.0) |
| **Semantic** | Meaning of parameters changed, edge case handling, validation rules | - `ErrorCode.statusCode` interpretation changes<br>- Null handling in `ErrorResponse.from()`<br>- Thread safety guarantees modified | **Minor** (0.X.0) |

### Breaking Change Examples

#### Signature Change (Major)
```kotlin
// Before
class CharacterNotFoundException(userIgn: String) : ClientBaseException(...)

// After (BREAKING)
class CharacterNotFoundException(val userIgn: String, val ocid: String) : ClientBaseException(...)
```

#### Behavior Change (Minor)
```kotlin
// Before: Returns null for empty input
fun maskOcid(value: String?): String? { ... }

// After: Returns "***" for empty input (behavior change)
fun maskOcid(value: String?): String { ... }
```

---

## API Enumeration by Package

### 1. Error Handling (`maple.expectation.error`)

#### 1.1 ErrorCode Interface

**Stability:** Stable

**Location:** `maple.expectation.error.ErrorCode`

```kotlin
interface ErrorCode {
    val code: String          // Error code identifier (e.g., "C001")
    val message: String       // Error message template (may contain %s placeholders)
    val statusCode: Int       // HTTP status code (4xx or 5xx)
}
```

**Usage:** Base interface for all error code enums

**Implementations:** `CommonErrorCode`

---

#### 1.2 CommonErrorCode Enum

**Stability:** Stable

**Location:** `maple.expectation.error.CommonErrorCode`

**Total Values:** 37 error codes

**Categories:**

##### Client Errors (4xx)
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| C001 | INVALID_INPUT_VALUE | 잘못된 입력값입니다: %s | 400 |
| C002 | CHARACTER_NOT_FOUND | 존재하지 않는 캐릭터입니다 (IGN: %s) | 404 |
| C003 | INSUFFICIENT_POINTS | 포인트가 부족합니다 (보유: %s, 필요: %s) | 400 |
| C004 | DEVELOPER_NOT_FOUND | 해당 개발자를 찾을 수 없습니다 (ID: %s) | 404 |
| C005 | INVALID_CHARACTER_STATE | 유효하지 않은 캐릭터 상태입니다: %s | 400 |
| R001 | RATE_LIMIT_EXCEEDED | 요청 한도를 초과했습니다. %s초 후 다시 시도해주세요. | 429 |

##### Auth Errors (4xx)
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| A001 | INVALID_API_KEY | 유효하지 않은 API Key입니다. | 401 |
| A002 | CHARACTER_NOT_OWNED | 해당 캐릭터는 이 API Key 소유자의 캐릭터가 아닙니다 (IGN: %s) | 403 |
| A003 | SELF_LIKE_NOT_ALLOWED | 자신의 캐릭터에는 좋아요를 누를 수 없습니다. | 403 |
| A004 | DUPLICATE_LIKE | 이미 좋아요를 누른 캐릭터입니다. | 409 |
| A005 | UNAUTHORIZED | 인증이 필요합니다. | 401 |
| A006 | FORBIDDEN | 접근 권한이 없습니다. | 403 |
| A007 | ADMIN_NOT_FOUND | 유효하지 않은 Admin입니다. | 404 |
| A008 | ADMIN_MEMBER_NOT_FOUND | Admin의 Member 계정이 존재하지 않습니다. | 404 |
| A009 | SENDER_MEMBER_NOT_FOUND | 발신자 Member 계정이 존재하지 않습니다 (uuid: %s) | 404 |
| A010 | INVALID_REFRESH_TOKEN | 유효하지 않은 Refresh Token입니다. | 401 |
| A011 | REFRESH_TOKEN_EXPIRED | Refresh Token이 만료되었습니다. 다시 로그인해주세요. | 401 |
| A012 | TOKEN_USED | 이미 사용된 토큰입니다. 보안을 위해 재로그인이 필요합니다. | 401 |
| A013 | SESSION_NOT_FOUND | 세션이 만료되었습니다. 다시 로그인해주세요. | 401 |

##### DLQ Errors (4xx)
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| D001 | DLQ_NOT_FOUND | 해당 DLQ 항목을 찾을 수 없습니다 (ID: %s) | 404 |
| D002 | DLQ_ALREADY_PROCESSED | 이미 재처리된 DLQ 항목입니다 (requestId: %s) | 409 |

##### Server Errors (5xx)
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| S001 | INTERNAL_SERVER_ERROR | 서버 내부 오류가 발생했습니다. (%s) | 500 |
| S002 | DATABASE_TRANSACTION_FAILURE | 치명적인 트랜잭션 오류가 발생했습니다. (%s) | 500 |
| S003 | DATA_INITIALIZATION_FAILED | 데이터 초기화 실패 (대상: %s) | 500 |
| S004 | DATA_PROCESSING_ERROR | 데이터 처리 중 오류 발생 (%s) | 500 |
| S005 | EXTERNAL_API_ERROR | 외부 API 호출 실패 (%s) | 503 |
| S998 | COMPRESSION_ERROR | 압축/압축 해제 오류가 발생했습니다. | 500 |
| S006 | SYSTEM_CAPACITY_EXCEEDED | 시스템 부하가 임계치를 초과했습니다. (현재 대기량: %s) | 503 |
| S007 | SERVICE_UNAVAILABLE | 서비스가 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요. | 503 |
| S008 | REDIS_SCRIPT_EXECUTION_FAILED | Redis 스크립트 실행 실패 (스크립트: %s) | 500 |
| S009 | DATABASE_NAMED_LOCK_FAILED | DB named lock 처리 실패: %s (lockKey=%s, waitTime=%s) | 500 |
| S010 | API_TIMEOUT | 외부 API 호출 시간 초과 (%s) | 503 |
| S011 | INSUFFICIENT_RESOURCE | 리소스가 부족합니다: %s | 503 |
| S012 | MYSQL_FALLBACK_FAILED | MySQL 장애 시 Fallback 실패 (ocid: %s) | 503 |
| S013 | COMPENSATION_SYNC_FAILED | Compensation Log 동기화 실패 (entryId: %s) | 500 |
| S014 | LIKE_SYNC_CIRCUIT_OPEN | 좋아요 동기화 서킷이 열렸습니다 (%s) | 503 |
| S015 | STARFORCE_TABLE_NOT_INITIALIZED | 스타포스 테이블 초기화가 완료되지 않았습니다. | 503 |
| S016 | CACHE_DATA_NOT_FOUND | 캐시 데이터를 찾을 수 없습니다 (key: %s) | 500 |
| S017 | SYSTEM_ERROR | 시스템 오류가 발생했습니다. (%s) | 500 |

##### Event Handler Errors
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| E001 | EVENT_HANDLER_ERROR | 이벤트 핸들러가 잘못되었습니다. (%s) | 500 |
| E002 | EVENT_CONSUMER_ERROR | 이벤트 컨슈머가 잘못되었습니다. (%s) | 500 |

##### Unknown Error
| Code | Name | Message Template | Status |
|------|------|------------------|--------|
| U999 | COMMON_ERROR | 알 수 없는 에러 코드입니다. | 500 |

**Methods:**
```kotlin
fun formatMessage(vararg args: Any): String
```

---

#### 1.3 ErrorResponse Data Class

**Stability:** Stable

**Location:** `maple.expectation.error.dto.ErrorResponse`

```kotlin
data class ErrorResponse(
    val status: Int,                    // HTTP status code
    val code: String,                   // Error code identifier
    val message: String,                // Error message (static or dynamic)
    val timestamp: LocalDateTime = LocalDateTime.now()
)
```

**Companion Object Methods:**

| Method | Parameters | Returns | Description |
|--------|------------|---------|-------------|
| `from(e: BaseException)` | Business exception | `ErrorResponse` | Creates response with dynamic message from exception |
| `from(errorCode: ErrorCode)` | Error code enum | `ErrorResponse` | Creates response with static message from enum |
| `from(status, code, message)` | Custom values | `ErrorResponse` | Creates response with custom values |
| `builder()` | None | `Builder` | Returns Java-friendly builder |

**Builder Pattern (Java Interop):**
```java
ErrorResponse response = ErrorResponse.builder()
    .status(500)
    .code("S001")
    .message("Internal error")
    .build();
```

---

### 2. Exception Hierarchy (`maple.expectation.error.exception.base`)

#### 2.1 BaseException

**Stability:** Stable

**Location:** `maple.expectation.error.exception.base.BaseException`

```kotlin
abstract class BaseException : RuntimeException {
    val errorCode: ErrorCode

    constructor(errorCode: ErrorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?)
    constructor(errorCode: ErrorCode, cause: Throwable?, vararg args: Any?)
}
```

**Features:**
- Type-safe error classification via `ErrorCode`
- Dynamic message formatting via `String.format()`
- Cause chaining for debugging

**Example:**
```kotlin
// Static message
throw CharacterNotFoundException(CommonErrorCode.CHARACTER_NOT_FOUND, "MapleStory123")
// Result: "존재하지 않는 캐릭터입니다 (IGN: MapleStory123)"
```

---

#### 2.2 ClientBaseException

**Stability:** Stable

**Location:** `maple.expectation.error.exception.base.ClientBaseException`

```kotlin
abstract class ClientBaseException : BaseException {
    constructor(errorCode: ErrorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?)
}
```

**Purpose:** Base class for client-side (4xx) business exceptions

**Characteristics:**
- Returns 4xx HTTP status codes
- Provides user-friendly error messages
- Implements `CircuitBreakerIgnoreMarker` (excluded from circuit breaker tracking)

**Subclasses (45 total):**
- `AdminNotFoundException`, `AdminMemberNotFoundException`
- `CharacterNotFoundException`, `CharacterNotOwnedException`
- `DuplicateLikeException`, `SelfLikeNotAllowedException`
- `InsufficientPointException`
- `InvalidApiKeyException`, `InvalidRefreshTokenException`, `RefreshTokenExpiredException`, `TokenReusedException`
- `InvalidPotentialGradeException`, `InvalidCharacterStateException`, `InvalidAdminFingerprintException`
- `SessionNotFoundException`, `SenderMemberNotFoundException`, `DeveloperNotFoundException`
- `DlqNotFoundException`
- (See full list below)

---

#### 2.3 ServerBaseException

**Stability:** Stable

**Location:** `maple.expectation.error.exception.base.ServerBaseException`

```kotlin
abstract class ServerBaseException : BaseException {
    constructor(errorCode: ErrorCode)
    constructor(errorCode: ErrorCode, vararg args: Any?)
    constructor(errorCode: ErrorCode, cause: Throwable)
    constructor(errorCode: ErrorCode, cause: Throwable, vararg args: Any?)
}
```

**Purpose:** Base class for server-side (5xx) system exceptions

**Characteristics:**
- Returns 5xx HTTP status codes
- Logs detailed error information for debugging
- Includes cause chain for failure analysis
- Implements `CircuitBreakerRecordMarker` (triggers circuit breaker)

**Subclasses (45 total):**
- `SystemException`, `InternalSystemException`
- `ApiTimeoutException`, `ExternalApiException`, `ExternalServiceException`
- `CriticalTransactionFailureException`, `TransactionSnapshotException`
- `CacheDataNotFoundException`, `CachePersistenceException`
- `CompressionException`
- `DatabaseNamedLockException`, `DistributedLockException`
- `EquipmentDataProcessingException`, `MapleDataProcessingException`
- `EventProcessingException`
- `InsufficientResourceException`
- `LikeSyncCircuitOpenException`
- `ObservabilityException`, `MonitoringException`
- `QueuePublishException`
- `StarforceNotInitializedException`, `CubeDataInitializationException`
- `UnsupportedCalculationEngineException`, `ExpectationCalculationUnavailableException`
- `InvalidPotentialGradeException`, `InvalidCharacterStateException`
- `OptionParseException`, `ProbabilityInvariantException`
- `AtomicFetchException`
- (See full list below)

---

### 3. Exception Marker Interfaces (`maple.expectation.error.exception.marker`)

#### 3.1 CircuitBreakerIgnoreMarker

**Stability:** Stable

**Location:** `maple.expectation.error.exception.marker.CircuitBreakerIgnoreMarker`

```kotlin
interface CircuitBreakerIgnoreMarker
```

**Purpose:** Marker interface for exceptions that should be ignored by circuit breaker

**Implementation:** All `ClientBaseException` subclasses implement this

**Rationale:** Client errors (4xx) are expected and should not trigger circuit breaker

---

#### 3.2 CircuitBreakerRecordMarker

**Stability:** Stable

**Location:** `maple.expectation.error.exception.marker.CircuitBreakerRecordMarker`

```kotlin
interface CircuitBreakerRecordMarker
```

**Purpose:** Marker interface for exceptions that should trigger circuit breaker recording

**Implementation:** All `ServerBaseException` subclasses implement this

**Rationale:** System errors (5xx) indicate infrastructure problems and should trigger circuit breaker

---

### 4. Domain Exceptions (`maple.expectation.error.exception`)

**Total Count:** 45 exception classes

#### 4.1 Client-Side Exceptions (4xx)

| Exception | Base Class | ErrorCode | Usage |
|-----------|------------|-----------|-------|
| `AdminNotFoundException` | ClientBaseException | A007 | Admin account not found |
| `AdminMemberNotFoundException` | ClientBaseException | A008 | Admin's Member account missing |
| `CharacterNotFoundException` | ClientBaseException | C002 | Character not found by IGN |
| `CharacterNotOwnedException` | ClientBaseException | A002 | Character not owned by API key owner |
| `DeveloperNotFoundException` | ClientBaseException | C004 | Developer not found |
| `DlqNotFoundException` | ClientBaseException | D001 | DLQ entry not found |
| `DuplicateLikeException` | ClientBaseException | A004 | Like already exists |
| `InsufficientPointException` | ClientBaseException | C003 | Not enough points |
| `InvalidAdminFingerprintException` | ClientBaseException | (custom) | Invalid admin fingerprint |
| `InvalidApiKeyException` | ClientBaseException | A001 | Invalid API key |
| `InvalidCharacterStateException` | ClientBaseException | C005 | Invalid character state |
| `InvalidPotentialGradeException` | ClientBaseException | (custom) | Invalid potential grade |
| `InvalidRefreshTokenException` | ClientBaseException | A010 | Invalid refresh token |
| `RefreshTokenExpiredException` | ClientBaseException | A011 | Expired refresh token |
| `TokenReusedException` | ClientBaseException | A012 | Token already used |
| `SelfLikeNotAllowedException` | ClientBaseException | A003 | Cannot like own character |
| `SenderMemberNotFoundException` | ClientBaseException | A009 | Sender member not found |
| `SessionNotFoundException` | ClientBaseException | A013 | Session expired |

#### 4.2 Server-Side Exceptions (5xx)

| Exception | Base Class | ErrorCode | Usage |
|-----------|------------|-----------|-------|
| `SystemException` | ServerBaseException | S017 | Generic system error |
| `InternalSystemException` | ServerBaseException | S001 | Internal server error |
| `ApiTimeoutException` | ServerBaseException | S010 | External API timeout |
| `ExternalApiException` | ServerBaseException | S005 | External API failure |
| `ExternalServiceException` | ServerBaseException | (custom) | External service failure |
| `AtomicFetchException` | ServerBaseException | (custom) | Atomic operation fetch failure |
| `CacheDataNotFoundException` | ServerBaseException | S016 | Cache data missing |
| `CachePersistenceException` | ServerBaseException | (custom) | Cache persistence failure |
| `CompressionException` | ServerBaseException | S998 | GZIP compression error |
| `CriticalTransactionFailureException` | ServerBaseException | S002 | Transaction failure |
| `TransactionSnapshotException` | ServerBaseException | (custom) | Transaction snapshot error |
| `CubeDataInitializationException` | ServerBaseException | S003 | Cube data init failed |
| `StarforceNotInitializedException` | ServerBaseException | S015 | Starforce table not ready |
| `DatabaseNamedLockException` | ServerBaseException | S009 | DB named lock failed |
| `DistributedLockException` | ServerBaseException | (custom) | Distributed lock failure |
| `EquipmentDataProcessingException` | ServerBaseException | S004 | Equipment data processing error |
| `MapleDataProcessingException` | ServerBaseException | S004 | Maple data processing error |
| `EventProcessingException` | ServerBaseException | E001 | Event handler error |
| `ExpectationCalculationUnavailableException` | ServerBaseException | (custom) | Calculation engine unavailable |
| `InsufficientResourceException` | ServerBaseException | S011 | Resource exhaustion |
| `LikeSyncCircuitOpenException` | ServerBaseException | S014 | Like sync circuit open |
| `MonitoringException` | ServerBaseException | (custom) | Monitoring system error |
| `ObservabilityException` | ServerBaseException | (custom) | Observability error |
| `OptionParseException` | ServerBaseException | (custom) | Option parsing error |
| `ProbabilityInvariantException` | ServerBaseException | (custom) | Probability invariant violation |
| `QueuePublishException` | ServerBaseException | (custom) | Queue publish failed |
| `UnsupportedCalculationEngineException` | ServerBaseException | (custom) | Unsupported calculation engine |

---

### 5. API Response (`maple.expectation.response`)

#### 5.1 ApiResponse

**Stability:** Stable

**Location:** `maple.expectation.response.ApiResponse`

```kotlin
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ErrorInfo? = null
) {
    data class ErrorInfo(val code: String, val message: String)
}
```

**Companion Object Methods:**

| Method | Parameters | Returns | Description |
|--------|------------|---------|-------------|
| `success(data)` | Response data | `ApiResponse<T>` | Creates success response |
| `error(code, message)` | Error code, message | `ApiResponse<T>` | Creates error response |

**Usage Examples:**

```kotlin
// Success response
val response = ApiResponse.success(mapOf("ign" to "MapleStory123"))

// Error response
val error = ApiResponse.error("C001", "잘못된 입력값입니다")
```

---

### 6. Event Handling (`maple.expectation.event`)

#### 6.1 EventHandler Annotation

**Stability:** Stable

**Location:** `maple.expectation.event.EventHandler`

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class EventHandler(
    val eventType: KClass<*>,
    val async: Boolean = true
)
```

**Parameters:**
- `eventType`: Event class this handler processes (must match method parameter type)
- `async`: Whether to execute asynchronously on Virtual Threads (default: `true`)

**Usage:**
```kotlin
@Component
class LikeEventHandler {
    @EventHandler(LikeReceivedEvent::class)
    fun handleLikeReceived(event: LikeReceivedEvent) {
        // Handle event
    }
}
```

---

#### 6.2 EventPriority Enum

**Stability:** Stable

**Location:** `maple.expectation.event.EventPriority`

```kotlin
enum class EventPriority {
    HIGH,   // Critical events - dedicated thread pool
    LOW     // Background events - separate thread pool
}
```

---

#### 6.3 EventVersion Object

**Stability:** Stable

**Location:** `maple.expectation.event.EventVersion`

```kotlin
object EventVersion {
    const val V1 = 1
    const val CURRENT = V1

    @JvmStatic
    fun isSupported(version: Int): Boolean

    @JvmStatic
    fun needsUpcasting(version: Int): Boolean
}
```

**Purpose:** Centralized event schema version constants for evolution support

**Methods:**
- `isSupported(version)`: Check if version is not newer than current
- `needsUpcasting(version)`: Check if version is older than current

---

### 7. Utilities (`maple.expectation.util`)

#### 7.1 GzipUtils

**Stability:** Stable

**Location:** `maple.expectation.util.GzipUtils`

```kotlin
object GzipUtils {
    @JvmStatic
    @Throws(IOException::class)
    fun compress(str: String?): ByteArray

    @JvmStatic
    @Throws(IOException::class)
    fun decompress(compressed: ByteArray?): String
}
```

**Features:**
- GZIP compression/decompression
- Null-safe: Returns empty array/string for null/blank input
- Auto-detects GZIP magic number

---

#### 7.2 StringMaskingUtils

**Stability:** Stable

**Location:** `maple.expectation.util.StringMaskingUtils`

```kotlin
object StringMaskingUtils {
    @JvmStatic
    fun maskOcid(value: String?): String           // "12345678" → "1234***"

    @JvmStatic
    fun maskFingerprint(fingerprint: String?): String  // "12345678" → "1234****"

    @JvmStatic
    fun maskFingerprintWithSuffix(fingerprint: String?): String  // "12345678" → "1234****5678"

    @JvmStatic
    fun maskCacheKey(key: String?): String          // "expectation:v3:ocid123:..." → "expectation:v3:***:..."

    @JvmStatic
    fun maskAccountId(accountId: String?): String   // "12345678" → "12345678..."
}
```

**Purpose:** PII masking for secure logging

---

#### 7.3 ExceptionUtils

**Stability:** Stable

**Location:** `maple.expectation.util.ExceptionUtils`

```kotlin
object ExceptionUtils {
    const val MAX_CHAIN_DEPTH = 32

    @JvmStatic
    fun unwrapAsyncException(throwable: Throwable?): Throwable

    @JvmStatic
    fun <T : Throwable> unwrapAs(throwable: Throwable?, targetType: Class<T>): T?

    @JvmStatic
    fun containsCause(throwable: Throwable?, targetType: Class<out Throwable>): Boolean
}
```

**Purpose:** Unwrap async wrapper exceptions (`CompletionException`, `ExecutionException`, `UndeclaredThrowableException`)

**Usage:**
```kotlin
val unwrapped = ExceptionUtils.unwrapAsyncException(e)
if (unwrapped is CharacterNotFoundException) {
    // Handle specific exception
}
```

---

#### 7.4 InterruptUtils

**Stability:** Stable

**Location:** `maple.expectation.util.InterruptUtils`

```kotlin
object InterruptUtils {
    @JvmStatic
    fun restoreInterruptIfNeeded(t: Throwable?)
}
```

**Purpose:** Restore thread interrupt flag when `InterruptedException` or `InterruptedIOException` is found in exception graph

**Features:**
- Scans both cause chain and suppressed exceptions
- Prevents infinite loops with `MAX_GRAPH_DEPTH = 32`

---

### 8. Functional Interfaces (`maple.expectation.common.function`)

#### 8.1 ThrowingSupplier

**Stability:** Stable

**Location:** `maple.expectation.common.function.ThrowingSupplier`

```kotlin
@FunctionalInterface
fun interface ThrowingSupplier<T> {
    @Throws(Throwable::class)
    fun get(): T
}
```

**Purpose:** Supplier that can throw checked exceptions (used with `ThrowingSupplierUtils.getUnchecked()`)

---

#### 8.2 ThrowingSupplierUtils

**Stability:** Stable

**Location:** `maple.expectation.common.function.ThrowingSupplierUtils`

```kotlin
object ThrowingSupplierUtils {
    @JvmStatic
    fun <T> getUnchecked(supplier: ThrowingSupplier<T>): T
}
```

**Purpose:** Execute `ThrowingSupplier` and wrap checked exceptions in `IllegalStateException` (policy violation)

---

### 9. Resource Loading (`maple.expectation.common.resource`)

#### 9.1 ResourceLoader

**Stability:** Stable

**Location:** `maple.expectation.common.resource.ResourceLoader`

```kotlin
class ResourceLoader {
    fun loadResourceAsString(path: String): String
    fun loadResourceAsStream(path: String): InputStream
}
```

**Purpose:** Load resources from classpath

**Usage:**
```kotlin
val loader = ResourceLoader()
val luaScript = loader.loadResourceAsString("lua/script.lua")
```

---

## Usage Examples

### Example 1: Throwing Business Exception

```kotlin
throw CharacterNotFoundException("MapleStory123")
// Results in: 404 "존재하지 않는 캐릭터입니다 (IGN: MapleStory123)"
```

### Example 2: Creating Error Response

```kotlin
val response = ErrorResponse.from(
    BaseException(
        CommonErrorCode.INSUFFICIENT_POINTS,
        "100", "500"
    )
)
// Results in: ErrorResponse(status=400, code="C003", message="포인트가 부족합니다 (보유: 100, 필요: 500)")
```

### Example 3: API Response Wrapper

```kotlin
// Success
return ApiResponse.success(mapOf("ocid" to "12345"))

// Error
return ApiResponse.error("A001", "유효하지 않은 API Key입니다.")
```

### Example 4: Event Handler

```kotlin
@Component
class CharacterEventHandler {
    @EventHandler(CharacterCreatedEvent::class, async = true)
    fun handleCharacterCreated(event: CharacterCreatedEvent) {
        // Runs on Virtual Thread
        logger.info("Character created: {}", event.ocid)
    }
}
```

### Example 5: Using ExceptionUtils

```kotlin
executor.executeOrCatch(
    { nexonApiClient.getOcid(userIgn).join().getOcid() },
    { e ->
        val unwrapped = ExceptionUtils.unwrapAsyncException(e)
        if (unwrapped is CharacterNotFoundException) {
            negativeCacheStore.put(userIgn, Duration.ofMinutes(10))
        }
        throw e as RuntimeException
    },
    context
)
```

### Example 6: String Masking for Logging

```kotlin
logger.info(
    "Processing request: ocid={}, fingerprint={}",
    StringMaskingUtils.maskOcid(ocid),
    StringMaskingUtils.maskFingerprint(fingerprint)
)
// Output: "Processing request: ocid=1234***, fingerprint=1234****"
```

---

## Migration Guide

### Migrating from Legacy Exception Handling

**Before:**
```kotlin
throw IllegalArgumentException("Character not found: $ign")
```

**After:**
```kotlin
throw CharacterNotFoundException(ign)
// Proper HTTP status code (404), circuit breaker aware, dynamic message
```

### Migrating from Manual Error Responses

**Before:**
```kotlin
return ResponseEntity.status(500).body(ErrorResponse(...))
```

**After:**
```kotlin
throw SystemException(CommonErrorCode.SYSTEM_ERROR, cause)
// Global exception handler automatically converts to ErrorResponse
```

---

## Appendix: Quick Reference

### Stability Matrix

| Category | Stable APIs | Total APIs | Stable % |
|----------|-------------|------------|----------|
| Core Error | 3 | 3 | 100% |
| Exception Base | 3 | 3 | 100% |
| Exception Markers | 2 | 2 | 100% |
| Domain Exceptions | 45 | 45 | 100% |
| Response | 1 | 1 | 100% |
| Event | 3 | 3 | 100% |
| Utilities | 4 | 4 | 100% |
| Functional | 2 | 2 | 100% |
| Resources | 1 | 1 | 100% |
| **Total** | **62** | **62** | **100%** |

### Common Error Codes by HTTP Status

| Status | Count | Common Codes |
|--------|-------|--------------|
| 400 | 5 | C001, C003, C005, R001 |
| 401 | 4 | A001, A005, A010, A011, A012, A013 |
| 403 | 3 | A002, A003, A006 |
| 404 | 5 | C002, C004, A007, A008, A009, D001 |
| 409 | 2 | A004, D002 |
| 500 | 15 | S001-S018, E001-E002 |
| 503 | 4 | S005, S006, S007, S010-S011, S012-S015 |

---

**Document Version:** 1.0.0
**Next Review:** 2025-09-15
**Maintainer:** MapleExpectation Team
