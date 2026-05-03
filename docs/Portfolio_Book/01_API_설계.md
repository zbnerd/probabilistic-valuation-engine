# 제1장: API 설계 (API Design)

> **Probabilistic Valuation Engine**의 API 설계 철학과 버전 진화 과정을 통해 실무에서 바로 적용 가능한 RESTful API 설계 노하우를 공유합니다.

---

## 목차 (Table of Contents)

1. [서론: 왜 API 설계가 중요한가](#1-서론-왜-api-설계가-중요한가)
2. [API 버전 관리 전략](#2-api-버전-관리-전략)
3. [V1 → V5: API 진화의 역사](#3-v1--v5-api-진화의-역사)
4. [엔드포인트 설계](#4-엔드포인트-설계)
5. [요청/응답 DTO 설계](#5-요청응답-dto-설계)
6. [HTTP 상태 코드 정책](#6-http-상태-코드-정책)
7. [예외 처리 계층 구조](#7-예외-처리-계층-구조)
8. [입력 검증 (Bean Validation)](#8-입력-검증-bean-validation)
9. [보안: JWT 인증/인가](#9-보안-jwt-인증인가)
10. [Rate Limiting](#10-rate-limiting)
11. [CORS 정책](#11-cors-정책)
12. [성능 최적화 API 설계](#12-성능-최적화-api-설계)
13. [결론](#13-결론)

---

## 1. 서론: 왜 API 설계가 중요한가

API는 소프트웨어 시스템의 **얼굴**입니다. 아키텍처가 아무리 훌륭해도, API가 형편없으면 사용자는 첫인상에서 이탈합니다. 반대로, 좋은 API 설계는:

- **클라이언트 개발 생산성** 향상 (명확한 계약, 예측 가능한 응답)
- **시스템 확장성** 보장 (버전 관리, 하위 호환성)
- **운영 효율성** 개선 (통합된 에러 처리, 모니터링)

이 장에서는 **Probabilistic Valuation Engine**이 6개월 동안 V1에서 V5까지 API를 진화시키며 배운 실전 설계 노하우를 다룹니다.

---

## 2. API 버전 관리 전략

### 2.1 결정: URL Path 기반 버전 관리

**선택한 방식:**
```
/api/v1/characters/{userIgn}/expectation
/api/v2/characters/{userIgn}/expectation
/api/v3/characters/{userIgn}/expectation
/api/v4/characters/{userIgn}/expectation
/api/v5/characters/{userIgn}/expectation
```

**왜 URL Path인가?**

| 방식 | 장점 | 단점 | 도입 여부 |
|------|------|------|----------|
| **URL Path** | 명확한 시멘틱, 캐싱 용이, 디버깅 편리 | 엔드포인트 관리 부담 | ✅ 채택 |
| **Header** | URL 깔끔, A/B 테스트 용이 | 브라우저 테스트 어려움, 캐싱 복잡 | ❌ 거부 |
| **Query Parameter** | 구현 단순 | RESTful 위배, 시멘틱 모호 | ❌ 거부 |

**거부 사유 (Header 방식):**
- **개발자 경험 저하:** Postman/curl 테스트 시 매번 헤더 추가 필요
- **캐싱 복잡도:** CDN/브라우저 캐시에서 V1/V2 구분 어려움
- **디버깅 어려움:** 로그에서 URL만 보고 버전 식별 불가

### 2.2 버전 분리 정책

**하위 호환성이 깨지는 경우에만 신규 버전 생성:**

| 변경 유형 | 버전 | 예시 |
|----------|------|------|
| **필드 추가** | Same Version | 응답에 `calculatedAt` 추가 (V4) |
| **필드 제거** | New Version | `deprecated` 필드 삭제 → V5 |
| **필드 타입 변경** | New Version | `String` → `Long` |
| **응답 구조 변경** | New Version | 배열 → 객체, 중첩 구조 변경 |
| **비즈니스 로직 변경** | Same Version | 계산 알고리즘 개선 (V4 내부) |

**실제 사례:**
- **V3 → V4:** 응답 크기 200KB → 15KB (GZIP 압축) + 병렬 프리셋 계산
  - **구조 변경 없음**, 성능 개선만 → **신규 버전 아님**
  - 하지만 클라이언트 호환성 고려하여 V4로 분리 (GZIP 디코딩 필요)

---

## 3. V1 → V5: API 진화의 역사

### 3.1 V1 (2025년 8월) - Legacy Basic

**엔드포인트:**
```
GET /api/v1/characters/{userIgn}
```

**특징:**
- 단순 Nexon API 프록시
- 캐시 없음, 매번 외부 API 호출
- 동기 처리 (Blocking)
- 응답 시간: 500-1000ms

**왜 만들었나:**
- **MVP (Minimum Viable Product):** 핵심 기능 검증
- **빠른 출시:** 캐시/비동기 없이 단순 구현

**코드 예시:**
```kotlin
@GetMapping("/{userIgn}")
fun getCharacter(@PathVariable userIgn: String): CharacterResponse {
    val ocid = nexonApi.resolveOcid(userIgn)  // 200ms
    val equipment = nexonApi.getEquipment(ocid)  // 300ms
    return CharacterResponse.from(equipment)
}
```

**문제점:**
- **Cold Cache Throughput:** 20 RPS (동시 요청 처리 불가)
- **외부 API 과부하:** 동일 캐릭터 조회 시 중복 호출 100%
- **Timeout:** Nexon API 지연 시 전체 시스템 장애

**현재 상태:** Deprecated (V2로 마이그레이션 완료)

---

### 3.2 V2 (2025년 11월) - Cache Introduction

**엔드포인트:**
```
GET /api/v2/characters/{userIgn}/expectation
```

**특징:**
- **Caffeine L1 캐시** 도입 (Local Heap Cache)
- **동기 DB 저장** (계산 결과 즉시 저장)
- **응답 시간:** 100-200ms (캐시 히트 시 10ms)

**왜 만들었나:**
- **성능 개선:** 캐시로 응답 시간 5x 단축
- **외부 API 부하 감소:** 중복 호출 90% 제거

**개선 효과:**
| 지표 | V1 | V2 | 개선 |
|------|-----|-----|------|
| 응답 시간 (캐시 히트) | 500-1000ms | 10ms | **50-100x** |
| 외부 API 호출률 | 100% | 10% | **-90%** |
| Throughput | 20 RPS | 95 RPS | **4.75x** |

**문제점:**
- **동시성 경합:** 동일 장비 데이터 접근 시 Deadlock 위험
- **캐시 스토리지:** Heap 메모리 한계 (1GB 당 ~1,000캐릭터)

---

### 3.3 V3 (2025년 12월) - Concurrency Control

**엔드포인트:**
```
GET /api/v3/characters/{userIgn}/expectation
```

**특징:**
- **EquipmentStreamingParser** (State 패턴, Depth Tracking)
- **PostgreSQL Advisory Lock** (동시 장비 데이터 접근 제어)
- **분산락** (Redis 기반)

**왜 만들었나:**
- **동시 사용자 증가:** 100명 동시 접속 시 경합 문제 발생
- **데이터 정합성:** 장비 업데이트 중 계산 오류 방지

**Advisory Lock 패턴:**
```kotlin
// PostgreSQL Advisory Lock (트랜잭션 스코프)
fun calculateWithLock(ocid: Long, block: () -> Expectation): Expectation {
    val lockKey = ocid % 100_000  // Lock space 분할
    return jdbcTemplate.execute {
        // 1. Lock 획득 (pg_try_advisory_xact_lock)
        val acquired = tryAcquireLock(lockKey)
        if (!acquired) throw ConcurrentCalculationException()

        // 2. 계산 수행
        block()
    }
}
```

**문제점:**
- **Lock Contention:** Hot 캐릭터(인기 유저)에서 Lock 대기 발생
- **복잡도:** Lock 해제, Timeout 처리 로직 증가

---

### 3.4 V4 (2026년 1월) - High Performance

**엔드포인트:**
```
GET /api/v4/characters/{userIgn}/expectation
```

**특징:**
- **SingleFlight 패턴** (중복 API 호출 제거 99%)
- **GZIP 응축** (200KB → 15KB, 93% 절감)
- **병렬 프리셋 계산** (순차 300ms → 병렬 110ms, 3x 개선)
- **Write-Behind Buffer** (동기 DB 저장 → 비동기 배치)

**왜 만들었나:**
- **97 RPS 한계 돌파:** V3 성능 병목 해결
- **본격적인 성능 엔지니어링:** 캐시, 압축, 병렬화

**핵심 최적화:**

#### 1) SingleFlight (중복 제거 99%)
```kotlin
// Evidence: ExpectationCacheCoordinator.java
class ExpectationCacheCoordinator(
    private val singleFlight: SingleFlightExecutor
) {
    fun getEquipmentData(ocid: String): String {
        return singleFlight.execute(ocid) {
            // 동일 ocid로 동시 요청 시 1번만 호출
            loadFromNexonApi(ocid)
        }
    }
}
```

**증거:** [N01 Thundering Herd Test](../../02_Chaos_Engineering/06_Nightmare/Results/N01-thundering-herd-result.md)
- **중복 제거율:** 99% (100건 동시 요청 → 1건 실제 호출)

#### 2) 병렬 프리셋 계산 (3x 개선)
```kotlin
// 3개 프리셋(장비 조합) 병렬 계산
private fun calculateAllPresetsParallel(equipmentData: ByteArray): List<PresetExpectation> {
    val futures = (1..3).map { presetNo ->
        CompletableFuture.supplyAsync {
            calculatePreset(equipmentData, presetNo)
        }
    }
    return futures.map { it.join() }
}
```

**성능 비교:**
| 방식 | 소요 시간 | 개선 |
|------|----------|------|
| 순차 계산 (V3) | 300ms | 기준 |
| 병렬 계산 (V4) | 110ms | **3x** |

#### 3) Write-Behind Buffer (150-300x 개선)
```kotlin
// Lock-free 버퍼에 저장 후 비동기 배치 DB 기록
fun saveExpectation(data: Expectation) {
    val offered = buffer.offer(characterId, presets)
    if (!offered) {
        // Buffer 가득 찼을 때만 동기 저장
        saveSync(data)
    }
}

// 배치 드레인 (100건씩 upsert)
@Scheduled(fixedDelay = 100)
fun drainBuffer() {
    val batch = buffer.poll(100)
    repository.batchUpsert(batch)
}
```

**성능 비교:**
| 방식 | 소요 시간 | 처리량 |
|------|----------|--------|
| 동기 저장 (V3) | 15-30ms | 300 TPS |
| 버퍼 (V4) | 0.1ms | 1,000 TPS |

**V4 최종 성능:**
| 지표 | V3 | V4 | 개선 |
|------|-----|-----|------|
| Throughput | 95 RPS | 719 RPS | **7.6x** |
| p50 Latency (L1 HIT) | 27ms | 5ms | **5.4x** |
| 프리셋 계산 | 300ms | 110ms | **3x** |
| DB 저장 | 15-30ms | 0.1ms | **150-300x** |
| 응답 크기 | 200KB | 15KB | **93% 절감** |

**증거:** [ADR-011 Controller V4 성능 최적화](../../01_ADR/ADR-011-controller-v4-optimization.md)

---

### 3.5 V5 (2026년 4월) - CQRS Architecture

**엔드포인트:**
```
GET /api/v5/characters/{userIgn}/expectation
POST /api/v5/characters/{userIgn}/expectation/recalculate
GET /api/v5/characters/{userIgn}/task/{taskId}
```

**특징:**
- **CQRS 패턴** (읽기/쓰기 분리)
- **PostgreSQL Cache-First 조회** (View Table)
- **PGMQ Queue 기반 비동기 계산** (Priority Queue)
- **응답:** 202 Accepted + 클라이언트 폴링 또는 캐시 히트 시 200 OK

**왜 만들었나:**
- **Scale-out 준비:** 읽기(Query Server) / 쓰기(Worker Server) 분리
- **단일 DB 환경에서의 CQRS:** 별도 Event Store 없이 PostgreSQL 기반 구현

**아키텍처:**
```mermaid
graph TB
    Client[Client] -->|GET /expectation| Query[Query Server]
    Client -->|POST /recalculate| Command[Queue]
    Client -->|GET /task/{id}| Task[Task Polling]

    Query -->|Read| View[(PostgreSQL View)]
    Command -->|Enqueue| Queue[(PGMQ)]
    Queue -->|Consume| Worker[Worker Pool]
    Worker -->|Calculate| Nexon[Nexon API]
    Worker -->|Upsert| View

    style View fill:#336791
    style Queue fill:#e76f51
```

**응답 시나리오:**

#### 1) Cache HIT (1-10ms)
```json
GET /api/v5/characters/TestChar/expectation

Response 200 OK (1-10ms)
{
  "status": "success",
  "data": {
    "userIgn": "TestChar",
    "totalExpectedCost": 150000000,
    "presets": [...]
  },
  "timestamp": "2026-04-23T12:00:00Z"
}
```

#### 2) Cache MISS (202 Accepted)
```json
GET /api/v5/characters/TestChar/expectation

Response 202 Accepted
X-Task-Id: msg-123456

{
  "status": "queued",
  "message": "Calculation queued. Poll /task/msg-123456 for results."
}
```

#### 3) Task Polling
```json
GET /api/v5/characters/TestChar/task/msg-123456

// Processing
Response 200 OK
Retry-After: 5
{
  "taskId": "msg-123456",
  "status": "PROCESSING"
}

// Completed
Response 200 OK
{
  "taskId": "msg-123456",
  "status": "COMPLETED",
  "result": { ... }
}
```

**증거:** [ADR-042 V2/V4 이중 세대 아키텍처](../../01_ADR/ADR-042-v2-v4-dual-generation-architecture.md)

---

## 4. 엔드포인트 설계

### 4.1 URL 설계 원칙

**RESTful 리소스 중심:**
```
# Good
GET /api/v4/characters/{userIgn}/expectation
POST /api/v4/characters/{userIgn}/like
DELETE /api/v4/characters/{userIgn}/like

# Bad
GET /api/v4/getExpectation?userIgn=TestChar
POST /api/v4/doLike?userIgn=TestChar
```

**동사 vs 명사:**
| 용도 | 동사 사용 | 명사 사용 |
|------|----------|----------|
| **조회** | ❌ `getExpectation` | ✅ `/expectation` |
| **생성/변경** | ✅ `/recalculate` | ✅ `/like` (토글 동작) |

**이유:** RESTful 리소스 모델에서는 **명사(리소스)** + **HTTP Method**로 의도를 표현

### 4.2 주요 엔드포인트

#### 1) 기대값 조회 (V4, V5)
```
GET /api/v4/characters/{userIgn}/expectation?force=false
GET /api/v5/characters/{userIgn}/expectation?presetNo=1
```

**쿼리 파라미터:**
- `force`: 캐시 무시하고 강제 재계산 (V4)
- `presetNo`: 프리셋 번호 (1-3, V5)

**인증:** `@PreAuthorize("hasRole('ADMIN') or hasRole('USER')")` (V4), `permitAll()` (V5)

#### 2) 좋아요 토글 (V4)
```
POST /api/v4/characters/{userIgn}/like
DELETE /api/v4/characters/{userIgn}/unlike
GET /api/v4/characters/{userIgn}/like
```

**진화 과정:**
1. **Caffeine Cache + Write-Behind** (초기)
2. **Redis Sorted Set** (Scale-out 시도)
3. **PostgreSQL Direct DB Toggle** (ADR-344, 현재)

**현재 구현:**
- **Fingerprint 기반 자가 좋아요 방지**
- **DB Trigger로 `like_count` 원자성 보장**

#### 3) 인증 (Auth)
```
POST /auth/login
POST /auth/refresh
DELETE /auth/logout
GET /auth/me
```

#### 4) 관리자 (Admin)
```
GET /api/admin/admins
POST /api/admin/admins
DELETE /api/admin/admins/{fingerprint}
```

---

## 5. 요청/응답 DTO 설계

### 5.1 통합 응답 포맷

**성공 응답:**
```json
{
  "status": "success",
  "data": {
    "userIgn": "TestChar",
    "totalExpectedCost": 150000000,
    "presets": [...]
  },
  "timestamp": "2026-04-23T12:00:00Z"
}
```

**에러 응답:**
```json
{
  "status": "error",
  "error": {
    "code": "CHARACTER_NOT_FOUND",
    "message": "캐릭터를 찾을 수 없습니다",
    "details": null
  },
  "timestamp": "2026-04-23T12:00:00Z"
}
```

**왜 통합 포맷인가:**
- **일관된 클라이언트 처리:** `status` 필드만으로 성공/실패 판단
- **에러 추적 용이:** `code`로 프로그래밍 방식 에러 처리
- **확장성:** `details`에 추가 컨텍스트 포함 가능

### 5.2 V4 DTO 구조

**EquipmentExpectationResponseV4.kt:**
```kotlin
data class EquipmentExpectationResponseV4(
    val userIgn: String,
    val calculatedAt: LocalDateTime,
    val fromCache: Boolean,  // 캐시 여부 (모니터링용)
    val totalExpectedCost: Double,
    val totalCostText: String,  // "150,000,000 메소"
    val totalCostBreakdown: CostBreakdownDto,
    val maxPresetNo: Int,
    val presets: List<PresetExpectation>
)

data class PresetExpectation(
    val presetNo: Int,
    val totalExpectedCost: Double,
    val items: List<ItemExpectationV4>
)

data class ItemExpectationV4(
    val itemName: String,
    val itemIcon: String,
    val itemPart: String,  // "RING", "WEAPON" ...
    val expectedCost: Double,
    val blackCubeExpectation: CubeExpectationDto,
    val starforceExpectation: StarforceExpectationDto,
    val flameExpectation: FlameExpectationDto
)
```

**설계 원칙:**
1. **불변성 (Immutable):** `data class` + `val`
2. **Builder 패턴:** Java 호환성 (`ItemExpectationV4.builder()`)
3. **텍스트 + 수치:** `totalCost` (Double) + `totalCostText` (String)로 포맷팅 서버 분리
4. **중첩 구조:** `CostBreakdownDto` 등 재사용 가능한 서브 DTO

### 5.3 요청 DTO

**LoginRequest.kt:**
```kotlin
data class LoginRequest(
    @field:NotBlank
    @field:Size(min = 32, max = 32)
    val apiKey: String,

    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9]+$")
    @field:Size(max = 12)
    val userIgn: String
)
```

**AddAdminRequest.kt:**
```kotlin
data class AddAdminRequest(
    @field:NotBlank
    @field:Size(min = 32, max = 32)
    val fingerprint: String
) {
    fun maskedFingerprint(): String = "${fingerprint.take(8)}***"
}
```

---

## 6. HTTP 상태 코드 정책

### 6.1 상태 코드 매핑

| 상태 코드 | 의미 | 사용 예시 | 비고 |
|----------|------|----------|------|
| **200 OK** | 정상 응답 | 캐시 히트, 조회 성공 | 가장 빈번 |
| **202 Accepted** | 비동기 처리 시작 | V5 캐시 미스 시 큐잉 | `Retry-After` 헤더 권장 |
| **400 Bad Request** | 입력값 검증 실패 | `@NotBlank` 위반 | `fieldErrors` 포함 |
| **401 Unauthorized** | JWT 토큰 없음/만료 | 인증 헤더 누락 | `WWW-Authenticate` 헤더 |
| **403 Forbidden** | 권한 없음 | USER가 ADMIN 전용 API 호출 | |
| **404 Not Found** | 캐릭터/리소스 없음 | 존재하지 않는 IGN 조회 | |
| **409 Conflict** | 중복 요청 | 이미 좋아요한 캐릭터 | |
| **429 Too Many Requests** | Rate Limit 초과 | IP/User 요청 제한 | `Retry-After` 헤더 |
| **500 Internal Server Error** | 서버 내부 오류 | 알 수 없는 예외 | |
| **502 Bad Gateway** | 외부 API 장애 | Nexon API 장애 | Circuit Breaker 트리거 |
| **503 Service Unavailable** | 스레드 풀 포화 | AbortPolicy로 거부 | |
| **504 Gateway Timeout** | 외부 API 타임아웃 | Nexon API 5초 초과 | |

### 6.2 상태 코드별 예외 매핑

**GlobalExceptionHandler.kt:**
```kotlin
@ExceptionHandler(CharacterNotOwnedException::class)
fun handleCharacterNotFound(ex: CharacterNotOwnedException): ResponseEntity<ErrorResponse> {
    return ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of(ex.errorCode, ex.message))
}

@ExceptionHandler(RateLimitExceededException::class)
fun handleRateLimit(ex: RateLimitExceededException): ResponseEntity<ErrorResponse> {
    return ResponseEntity
        .status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "60")
        .body(ErrorResponse.of("RATE_LIMIT_EXCEEDED", "1분 후 재시도해주세요."))
}

@ExceptionHandler(ExternalServiceException::class)
fun handleExternalService(ex: ExternalServiceException): ResponseEntity<ErrorResponse> {
    circuitBreaker.recordFailure()
    return ResponseEntity
        .status(HttpStatus.BAD_GATEWAY)
        .body(ErrorResponse.of("EXTERNAL_API_ERROR", "외부 서비스 장애입니다."))
}
```

---

## 7. 예외 처리 계층 구조

### 7.1 예외 계층도

```
BaseException (abstract)
├── ClientBaseException (4xx)
│   ├── CharacterNotFoundException (404)
│   ├── SelfLikeNotAllowedException (400)
│   ├── DuplicateLikeException (409)
│   └── InvalidApiKeyException (401)
└── ServerBaseException (5xx)
    ├── ExternalServiceException (502)
    ├── ApiTimeoutException (504)
    └── CompressionException (500)
```

**설계 결정:**
- **Circuit Breaker 무시 (4xx):** `ClientBaseException`은 마커 인터페이스로 서버 장아로 기록하지 않음
- **Circuit Breaker 기록 (5xx):** `ServerBaseException`은 서버 장애로 간주하여 Circuit Breaker에 기록

### 7.2 예외별 HTTP 매핑

| 예외 | HTTP Code | ErrorCode | 메시지 |
|------|----------|-----------|--------|
| `CharacterNotOwnedException` | 404 | CHARACTER_NOT_OWNED | 소유하지 않은 캐릭터입니다 |
| `SelfLikeNotAllowedException` | 400 | SELF_LIKE_NOT_ALLOWED | 자기 자신은 좋아요할 수 없습니다 |
| `DuplicateLikeException` | 409 | DUPLICATE_LIKE | 이미 좋아요한 캐릭터입니다 |
| `InvalidApiKeyException` | 401 | INVALID_API_KEY | 유효하지 않은 API Key입니다 |
| `RateLimitExceededException` | 429 | RATE_LIMIT_EXCEEDED | 요청 제한을 초과했습니다 |
| `ExternalServiceException` | 502 | EXTERNAL_API_ERROR | 외부 서비스 장애입니다 |
| `ApiTimeoutException` | 504 | API_TIMEOUT | 외부 API 타임아웃 |

### 7.3 ErrorCode Enum

**CommonErrorCode.kt:**
```kotlin
enum class CommonErrorCode(
    val status: HttpStatus,
    val code: String,
    val message: String
) {
    CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "캐릭터를 찾을 수 없습니다"),
    SELF_LIKE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "SELF_LIKE_NOT_ALLOWED", "자기 자신은 좋아요할 수 없습니다"),
    DUPLICATE_LIKE(HttpStatus.CONFLICT, "DUPLICATE_LIKE", "이미 좋아요한 캐릭터입니다"),
    INVALID_API_KEY(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "유효하지 않은 API Key입니다"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "요청 제한을 초과했습니다"),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR", "외부 서비스 장애입니다")
}
```

---

## 8. 입력 검증 (Bean Validation)

### 8.1 검증 어노테이션

**Controller 레벨:**
```kotlin
@Validated  // 클래스 레벨 검증 활성화
@RestController
@RequestMapping("/api/v4/characters")
class GameCharacterControllerV4 {

    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(
        @PathVariable @NotBlank userIgn: String,  // null, 빈 문자열 방지
        @RequestParam(defaultValue = "false") force: Boolean
    ): CompletableFuture<ResponseEntity<*>> {
        // ...
    }
}
```

**DTO 레벨:**
```kotlin
data class LoginRequest(
    @field:NotBlank
    @field:Size(min = 32, max = 32)
    val apiKey: String,

    @field:NotBlank
    @field:Pattern(regexp = "^[a-zA-Z0-9]+$")  // 영문/숫자만 (Nexon API 제약)
    @field:Size(max = 12)  // 길이 제한 (Nexon API 제약)
    val userIgn: String
)
```

### 8.2 커스텀 검증

**ValidIgn.kt:**
```kotlin
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [IgnValidator::class])
@Pattern(regexp = "^[a-zA-Z0-9]+$", message = "IGN은 영문과 숫자만 허용됩니다.")
annotation class ValidIgn
```

### 8.3 검증 실패 응답

**400 Bad Request:**
```json
{
  "status": "error",
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "입력값 검증에 실패했습니다",
    "details": {
      "userIgn": "IGN은 영문과 숫자만 허용됩니다.",
      "apiKey": "API Key는 32자여야 합니다."
    }
  },
  "timestamp": "2026-04-23T12:00:00Z"
}
```

---

## 9. 보안: JWT 인증/인가

### 9.1 인증 플로우

```
1. POST /auth/login
   Request: { apiKey, userIgn }
   Response: { accessToken (15분), refreshToken (7일) }

2. API 호출
   Header: Authorization: Bearer {accessToken}

3. Access Token 만료 시
   POST /auth/refresh
   Request: { refreshToken }
   Response: { newAccessToken, newRefreshToken }  # Token Rotation

4. 로그아웃
   DELETE /auth/logout
   Action: Refresh Token 무효화
```

### 9.2 JWT 보안 강화 (ADR-337)

**Algorithm Confusion Attack 방지:**
```kotlin
companion object {
    private val ALLOWED_ALGORITHMS = setOf("HS256")
    private val FORBIDDEN_ALGORITHMS = setOf("none", "nOnE", "NONE", "None")
}

private fun parseTokenInternal(token: String?): Optional<JwtPayload> {
    // 1. Pre-parse header validation
    val headerAlgorithm = extractAlgorithmFromHeader(token)

    // 2. Explicit "none" rejection
    require(headerAlgorithm.lowercase() !in FORBIDDEN_ALGORITHMS.map { it.lowercase() }) {
        "JWT algorithm 'none' is forbidden. Algorithm confusion attack detected."
    }

    // 3. Algorithm whitelist enforcement
    require(headerAlgorithm in ALLOWED_ALGORITHMS) {
        "JWT algorithm not in whitelist. Allowed: $ALLOWED_ALGORITHMS"
    }

    // 4. Parse with signature verification
    val jws = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)

    return Optional.of(payload)
}
```

**증거:** [ADR-337 JWT Algorithm Confusion Attack Prevention](../../01_ADR/ADR-337-jwt-algorithm-security.md)

### 9.3 Token Rotation 패턴

**Refresh Token 갱신 시:**
1. 기존 Refresh Token 무효화 (DB 삭제)
2. 새 Access Token + Refresh Token 발급
3. **재사용 공격 방지:** 이미 사용된 Refresh Token은 거부

---

## 10. Rate Limiting

### 10.1 Bucket4j 기반 구현

**정책:**
- **공개 엔드포인트:** IP 기반 제한 (10 req/min)
- **인증 엔드포인트:** User 기반 제한 (100 req/min)
- **Actuator:** 내부 IP만 접근 허용

**구현:**
```kotlin
@Component
class RateLimitFilter(
    private val rateLimiter: RateLimiter
) : Filter {
    override fun doFilter(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = if (isAuthenticated()) {
            "user:${getCurrentUser()}"
        } else {
            "ip:${request.remoteAddr}"
        }

        val allowed = rateLimiter.tryConsume(key, 10)  // 10 tokens
        if (!allowed) {
            response.status = 429
            response.setHeader("Retry-After", "60")
            return
        }

        chain.doFilter(request, response)
    }
}
```

### 10.2 Rate Limit 초과 응답

```json
{
  "status": "error",
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "요청 제한을 초과했습니다. 1분 후 재시도해주세요."
  },
  "timestamp": "2026-04-23T12:00:00Z"
}
```

---

## 11. CORS 정책

### 11.1 와일드카드 대신 명시적 오리진 허용

**나쁜 예:**
```kotlin
// ❌ 보안 위험
registry.addMapping("/**")
    .allowedOrigins("*")
    .allowedMethods("GET", "POST", "DELETE")
```

**좋은 예:**
```kotlin
// ✅ 명시적 오리진 허용
registry.addMapping("/**")
    .allowedOrigins(
        "https://maplestory.gg",      // 프로덕션
        "https://maplestory-dev.com", // 개발
        "http://localhost:3000"       // 로컬 개발
    )
    .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
    .allowCredentials(true)  // 인증 정보 포함
    .maxAge(3600)
```

**이유 (Issue #172):**
- **와일드카드 + 인증:** 브라우저가 거부 (CORS 사양)
- **CSRF 위험:** 모든 도메인에서 API 호출 가능

---

## 12. 성능 최적화 API 설계

### 12.1 GZIP 압축 (V4)

**응답 크기 절감:**
| 포맷 | 크기 | 절감 |
|------|------|------|
| JSON | 200KB | 기준 |
| GZIP | 15KB | **93%** |

**구현:**
```kotlin
// Fast Path: L1 캐시에서 GZIP 직접 반환
if (acceptsGzip(acceptEncoding) && !force) {
    val gzipData = expectationPort.getGzipFromL1CacheDirect(userIgn)
    if (gzipData != null) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_ENCODING, "gzip")
            .body(gzipData)  // 역직렬화 없이 즉시 반환
    }
}
```

### 12.2 병렬 처리 (V4)

**프리셋 병렬 계산:**
```kotlin
private fun calculateAllPresetsParallel(equipmentData: ByteArray): List<PresetExpectation> {
    val futures = (1..3).map { presetNo ->
        CompletableFuture.supplyAsync(
            { calculatePreset(equipmentData, presetNo) },
            presetExecutor  // 별도 Executor로 Deadlock 방지
        )
    }
    return futures.map { it.join() }
}
```

### 12.3 SingleFlight (V4)

**중복 API 호출 제거:**
```kotlin
class ExpectationCacheCoordinator(
    private val singleFlight: SingleFlightExecutor
) {
    fun getEquipmentData(ocid: String): String {
        return singleFlight.execute(ocid) {
            loadFromNexonApi(ocid)  // 동일 ocid로 100건 요청 → 1건만 실행
        }
    }
}
```

**증거:** [N01 Thundering Herd Test](../../02_Chaos_Engineering/06_Nightmare/Results/N01-thundering-herd-result.md)
- **중복 제거율:** 99% (100건 동시 요청 → 1건 실제 호출)

### 12.4 Write-Behind Buffer (V4)

**비동기 DB 저장:**
```kotlin
// Lock-free 버퍼에 저장
fun saveExpectation(data: Expectation) {
    val offered = buffer.offer(characterId, presets)
    if (!offered) {
        // Buffer 가득 찼을 때만 동기 저장 (Backpressure)
        saveSync(data)
    }
}

// 배치 드레인 (스케줄러)
@Scheduled(fixedDelay = 100)
fun drainBuffer() {
    val batch = buffer.poll(100)
    repository.batchUpsert(batch)
}
```

**성능 개선:**
| 지표 | 동기 저장 | 버퍼 | 개선 |
|------|----------|------|------|
| 지연 시간 | 15-30ms | 0.1ms | **150-300x** |
| 처리량 | 300 TPS | 1,000 TPS | **3.3x** |

---

## 13. 결론

### 13.1 API 설계 핵심 원칙

**1. 버전 관리:**
- URL Path 기반 명확한 버전 구분
- 하위 호환성 깨지는 변경만 신규 버전
- V1~V5 병행 운영으로 점진적 마이그레이션

**2. 통합 포맷:**
- 성공/실패 응답 구조 일관성
- `status` 필드로 명확한 상태 구분
- `code`로 프로그래밍 방식 에러 처리

**3. HTTP 상태 코드:**
- RESTful 표준 준수 (200, 202, 400, 401, 404, ...)
- 4xx(클라이언트) / 5xx(서버) 명확히 구분
- Circuit Breaker와 연계 (5xx만 기록)

**4. 입력 검증:**
- Bean Validation으로 선언적 검증
- 커스텀 Validator로 도메인 규칙 적용
- 상세한 검증 실패 응답

**5. 보안:**
- JWT Token Rotation 패턴
- Algorithm Confusion Attack 방지
- Rate Limiting (IP/User 기반)
- CORS 명시적 오리진 허용

**6. 성능:**
- GZIP 압축 (93% 절감)
- 병렬 처리 (3x 개선)
- SingleFlight (99% 중복 제거)
- Write-Behind Buffer (150-300x 개선)

### 13.2 버전별 성능 개선

| 버전 | 핵심 기능 | Throughput | p50 Latency | 주요 개선 |
|------|----------|------------|-------------|----------|
| **V1** | 단순 프록시 | 20 RPS | 500-1000ms | 기본 기능 |
| **V2** | L1 캐시 | 95 RPS | 10ms | 5x 단축 |
| **V3** | 동시성 제어 | 95 RPS | 10ms | 안정성 |
| **V4** | SingleFlight + GZIP + 병렬 | 719 RPS | 5ms | 7.6x 처리량 |
| **V5** | CQRS + Queue | 1000+ RPS | 1-10ms | Scale-out 준비 |

### 13.3 다음 장에서는

- **제2장: 아키텍처 설계** - 헥사고날 아키텍처, CQRS, 이벤트 기반 설계
- **제3장: 캐시 전략** - TieredCache, SingleFlight, Write-Behind
- **제4장: 동시성 제어** - Advisory Lock, 분산락, Virtual Thread
- **제5장: 성능 최적화** - 병렬 처리, GZIP, 배치 처리

---

## 참고 문헌

| 문서 | 위치 |
|------|------|
| ADR-011 Controller V4 성능 최적화 | [docs/01_ADR/ADR-011-controller-v4-optimization.md](../../01_ADR/ADR-011-controller-v4-optimization.md) |
| ADR-042 V2/V4 이중 세대 아키텍처 | [docs/01_ADR/ADR-042-v2-v4-dual-generation-architecture.md](../../01_ADR/ADR-042-v2-v4-dual-generation-architecture.md) |
| ADR-337 JWT 보안 강화 | [docs/01_ADR/ADR-337-jwt-algorithm-security.md](../../01_ADR/ADR-337-jwt-algorithm-security.md) |
| V5 데이터 흐름 아키텍처 | [docs/04_Sequence_Diagrams/v5-endpoint-data-flow-architecture.md](../../04_Sequence_Diagrams/v5-endpoint-data-flow-architecture.md) |
| N01 Thundering Herd Test | [docs/02_Chaos_Engineering/06_Nightmare/Results/N01-thundering-herd-result.md](../../02_Chaos_Engineering/06_Nightmare/Results/N01-thundering-herd-result.md) |
| RFC 8725 JWT Best Practices | [https://datatracker.ietf.org/doc/html/rfc8725](https://datatracker.ietf.org/doc/html/rfc8725) |
| OWASP JWT Cheat Sheet | [https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html) |

---

*작성일: 2026-04-23*
*버전: 1.0.0*
*작성자: Probabilistic Valuation Engine Team*
