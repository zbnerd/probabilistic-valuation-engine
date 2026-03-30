---
id: GR-RESILIENCE-003
category: backend/resilience
severity: warning
keywords: [Fallback, GracefulDegradation, CircuitBreaker, Resilience]
---

# Circuit Breaker Fallback Strategy Guardrail

## DON'T (안티패턴)

### 1. Fallback 없이 Circuit Breaker 사용
```java
// Bad - Fallback 메서드 없음
@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallback")
public NexonApiCharacterResponse callNexonApi(String ign) {
    return webClient.get()
        .uri("/api/character/{ign}", ign)
        .retrieve()
        .bodyToMono(NexonApiCharacterResponse.class)
        .block();
}

// 문제: 서킷 오픈 시 CircuitBreakerOpenException 직접 전파
// 사용자에게 기술적 예외 노출
```

### 2. Fallback에서 null 반환
```java
// Bad - null 반환
private NexonApiCharacterResponse fallback(String ign, Exception e) {
    log.error("Circuit breaker opened", e);
    return null;  // NullPointerException 위험
}
```

### 3. Fallback에서 예외를 다시 던짐
```java
// Bad - 예외 재전파
private NexonApiCharacterResponse fallback(String ign, Exception e) {
    log.error("API call failed", e);
    throw new RuntimeException("Fallback also failed", e);
    // Fallback의 의미 상실
}
```

### 4. 폴백 로직이 너무 복잡
```java
// Bad - Fallback에서 복잡한 로직 수행
private NexonApiCharacterResponse fallback(String ign, Exception e) {
    // 1. DB 조회
    // 2. 캐시 확인
    // 3. 다른 API 호출
    // 4. 복잡한 계산
    // Fallback은 빨라야 함!
}
```

### 5. 비즈니스 예외(4xx)에 대한 불필요한 Fallback
```java
// Bad - CharacterNotFoundException에 대한 Fallback
@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackForNotFound")
public NexonApiCharacterResponse callNexonApi(String ign) {
    // ...
}

private NexonApiCharacterResponse fallbackForNotFound(String ign, CharacterNotFoundException e) {
    return EMPTY_RESPONSE;  // 불필요: 404는 정상적인 응답
}
```

## DO (베스트 프랙티스)

### 1. 캐시 기반 Fallback (가장 권장)
```java
// Good - L2 캐시에서 폴백
@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackFromCache")
public NexonApiCharacterResponse callNexonApi(String ign) {
    return webClient.get()
        .uri("/api/character/{ign}", ign)
        .retrieve()
        .bodyToMono(NexonApiCharacterResponse.class)
        .block();
}

private NexonApiCharacterResponse fallbackFromCache(String ign, Exception e) {
    log.warn("Nexon API unavailable, using cache: ign={}, error={}", ign, e.getMessage());
    return redissonClient.getBucket(NexonApiCharacterResponse.getCacheKey(ign))
        .get();  // null 가능성 고려
}
```

### 2. 안전한 기본값 반환
```java
// Good - 빈 응답 객체 반환
@CircuitBreaker(name = "discordWebhook", fallbackMethod = "fallbackNoOp")
public void sendAlert(String message) {
    discordWebhookClient.sendMessage(message);
}

private void fallbackNoOp(String message, Exception e) {
    log.warn("Discord webhook unavailable, message dropped: {}", e.getMessage());
    // 조용히 실패 (Fail Silent)
}

// Good - 빈 컬렉션 반환
@CircuitBreaker(name = "likeSync", fallbackMethod = "fallbackEmptyList")
public List<LikeSyncEntry> syncLikes() {
    return likeSyncRepository.syncAll();
}

private List<LikeSyncEntry> fallbackEmptyList(Exception e) {
    log.warn("Like sync failed, returning empty list: {}", e.getMessage());
    return List.of();  // 빈 리스트로 안전하게 처리
}
```

### 3. MySQL 기반 Fallback (L3 레벨)
```kotlin
// Good - V4 Fallback Service 패턴
@Service
class NexonApiFallbackService(
    private val mysqlCharacterRepository: MysqlCharacterRepository,
    private val executor: LogicExecutor
) {
    fun getCharacterFallback(ign: String): NexonApiCharacterResponse? {
        return executor.executeOrDefault(
            {
                mysqlCharacterRepository.findById(ign)
                    ?.toDto()
                    ?.let { dto ->
                        NexonApiCharacterResponse(
                            ocid = dto.ocid,
                            characterName = dto.characterName,
                            // ... 기본값 설정
                        )
                    }
            },
            null,  // DB 조회 실패 시 null 반환 (상위에서 처리)
            TaskContext.of("Fallback", "MySQL", ign)
        )
    }
}
```

### 4. 비즈니스 예외는 CircuitBreakerIgnoreMarker로 처리
```kotlin
// Good - 비즈니스 예외는 Fallback 없이 예외 전파
@CircuitBreaker(name = "nexonApi")
fun callNexonApi(ign: String): NexonApiCharacterResponse {
    return executor.execute(
        { webClient.callApi(ign) },
        TaskContext.of("NexonApi", "callCharacter", ign)
    )
}

// CircuitBreakerIgnoreMarker 구현으로 서킷에 영향 없이 예외 전파
class CharacterNotFoundException(errorCode: ErrorCode, ign: String) :
    ClientBaseException(errorCode, ign)  // IgnoreMarker 자동 구현
```

### 5. Fallback 체이닝 (다단계 폴백)
```kotlin
// Good - 다단계 Fallback 체인
fun getCharacterWithFallback(ign: String): CharacterDto? {
    // 1차: Nexon API
    val apiResult = callWithCircuitBreaker(ign) { callNexonApi(ign) }
    if (apiResult != null) return apiResult

    // 2차: L2 캐시 (Redis)
    val cacheResult = getCachedValue(ign)
    if (cacheResult != null) {
        log.info("API unavailable, using cached data: ign={}", ign)
        return cacheResult
    }

    // 3차: MySQL (L3)
    val dbResult = mysqlFallbackService.getCharacterFallback(ign)
    if (dbResult != null) {
        log.warn("Cache unavailable, using DB fallback: ign={}", ign)
        return dbResult
    }

    // 최종: null 반환 (상위에서 404 응답)
    log.error("All fallbacks exhausted for character: ign={}", ign)
    return null
}
```

### 6. Fallback 성능 메트릭 기록
```kotlin
// Good - Fallback 발생 시 메트릭 기록
private NexonApiCharacterResponse fallbackFromCache(String ign, Exception e) {
    // 메트릭 기록
    meterRegistry.counter("resilience4j.fallback.invoked",
        "service", "nexonApi",
        "reason", e.javaClass.simpleName
    ).increment()

    log.warn("Fallback triggered: service={}, ign={}, reason={}",
        "nexonApi", ign, e.getMessage());

    return getCachedValue(ign);
}
```

### 7. Timeout과 Fallback 조합
```java
// Good - @TimeLimiter와 @CircuitBreaker 조합
@CircuitBreaker(name = "nexonApi", fallbackMethod = "fallbackFromCache")
@TimeLimiter(name = "nexonApi", fallbackMethod = "fallbackFromCache")
public CompletableFuture<NexonApiCharacterResponse> callNexonApiAsync(String ign) {
    return webClient.get()
        .uri("/api/character/{ign}", ign)
        .retrieve()
        .bodyToMono(NexonApiCharacterResponse.class)
        .toFuture();
}
```

## Fallback 계층 구조

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Primary Source (Nexon API)                        │
│  @CircuitBreaker + @TimeLimiter                              │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: L2 Cache (Redis) - Warm Data (<5 minutes old)     │
│  빠른 응답, 데이터 신선도 보장                                │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: MySQL (Persistent Storage) - Cold Data            │
│  최후의 수단, 데이터는 오래될 수 있음                          │
├─────────────────────────────────────────────────────────────┤
│  Layer 4: Fail Safe (null, EmptyList, Default Value)        │
│  사용자 경험 해치지 않음                                      │
└─────────────────────────────────────────────────────────────┘
```

## Fallback vs IgnoreExceptions

| 상황 | 전략 | Marker Interface | Fallback 필요? |
|------|------|------------------|----------------|
| **404 Not Found** | 예외 전파 | `IgnoreMarker` | ❌ 아니오 (정상 응답) |
| **400 Bad Request** | 예외 전파 | `IgnoreMarker` | ❌ 아니오 (클라이언트 오류) |
| **API Timeout (5xx)** | 캐시 폴백 | `RecordMarker` | ✅ 예 (Redis/MySQL) |
| **서킷 오픈** | 캐시 폴백 | N/A | ✅ 예 (그레이스풀 디그레이션) |
| **데이터베이스 장애** | 빈 응답 | `RecordMarker` | ✅ 예 (EmptyList/null) |

## 출처

### 문서
- `CLAUDE.md` Section 12-1: Circuit Breaker & Resilience Rules (line 354)
- `docs/03_Technical_Guides/infrastructure.md` Section 17: TieredCache & Graceful Degradation

### 코드
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/external/impl/FallbackHandler.kt`
- `module-app/src/main/kotlin/maple/expectation/service/v4/fallback/NexonApiFallbackService.java`

### 설정
- `module-app/src/main/resources/application.yml` (lines 111-131)

### 관련 ADR
- `docs/01_ADR/ADR-052-resilience4j-circuit-breaker.md`
- `docs/05_Reports/05_08_Refactor/AUDIT_BASELINE.md` (lines 74-109)
