---
id: GR-REFACTOR-005
category: architecture/refactor
severity: warning
keywords: [circuit-breaker, resilience, auto-mitigation, fallback]
languages: [java, kotlin]
---

# Circuit Breaker - 자동 장애 완화

## DON'T (외부 장애 전파)
- 외부 API 장애 시 재시도만 반복
- 서킷브레이커 없이 장애가 전체 시스템으로 전파
- 타임아웃 예외를 적절히 처리하지 않음

```java
// Bad: Circuit Breaker 없는 재시도
@Retryable(maxAttempts = 3)
public EquipmentResponse getEquipment(String ocid) {
    // 외부 API 호출
    return nexonApiClient.getEquipment(ocid);  // 장애 시 계속 재시도
}

// 예외 처리 미흡
private TotalExpectationResponse handleAsyncException(Throwable e, String userIgn) {
    // ❌ 타임아웃 예외를 일반 RuntimeException으로 변환
    throw new RuntimeException("Async calculation failed", e);
}
```

```kotlin
// Bad: 무조건 재시도
@Retryable(maxAttempts = 3)
fun getEquipment(ocid: String): EquipmentResponse {
    return nexonApiClient.getEquipment(ocid)  // 장애 전파
}
```

## DO (Circuit Breaker + Fallback)
- Resilience4j Circuit Breaker로 외부 장애 차단
- 실패율 50% 이상 시 자동 OPEN
- 10초 후 HALF_OPEN으로 복구 시도
- Fallback으로 캐시 데이터 반환

```yaml
# application.yml - Circuit Breaker 설정
resilience4j:
  circuitbreaker:
    instances:
      nexonApi:
        sliding-window-size: 10           # 최근 10번 호출 기준
        failure-rate-threshold: 50        # 50% 실패 시 OPEN
        wait-duration-in-open-state: 10s  # 10초 후 HALF_OPEN
        minimum-number-of-calls: 10       # 최소 10번 호출 후 통계
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      nexonApi:
        max-attempts: 3
        wait-duration: 500ms
```

```java
// Good: Circuit Breaker + Fallback
@CircuitBreaker(name = "nexonApi", fallbackMethod = "getEquipmentFromCache")
public EquipmentResponse getEquipment(String ocid) {
    return nexonApiClient.getEquipment(ocid);
}

// Fallback 메서드
private EquipmentResponse getEquipmentFromCache(String ocid, Exception e) {
    if (e instanceof CallNotPermittedException) {
        log.warn("Circuit Breaker OPEN - serving from cache: {}", ocid);
        return equipmentCacheService.getCachedEquipment(ocid)
            .orElse(EquipmentResponse.empty());
    }
    throw new EquipmentDataProcessingException("Failed to fetch equipment", e);
}
```

```kotlin
// Good: Circuit Breaker + Fallback
@CircuitBreaker(name = "nexonApi", fallbackMethod = "getEquipmentFromCache")
fun getEquipment(ocid: String): EquipmentResponse {
    return nexonApiClient.getEquipment(ocid)
}

private fun getEquipmentFromCache(ocid: String, e: Exception): EquipmentResponse {
    return when (e) {
        is CallNotPermittedException -> {
            log.warn("Circuit Breaker OPEN - serving from cache: {}", ocid)
            equipmentCacheService.getCachedEquipment(ocid)
                .orElse(EquipmentResponse.empty())
        }
        else -> throw EquipmentDataProcessingException("Failed to fetch equipment", e)
    }
}
```

## MTTD/MTTR 분석

| 항목 | 현재 시스템 | 산업 평균 | 개선율 |
|------|-----------|----------|--------|
| **MTTD** (감지 시간) | < 1초 | 5-10분 | 99.8% ⬆️ |
| **MTTR** (복구 시간) | ~11초 | 50-60분 | 99.6% ⬆️ |

**설정 기준:**
- Sliding Window: 10 calls
- Detection: 즉시 감지
- Wait Duration: 10s
- Half-Open Test: 3 calls

## 장애 감지 및 복구 흐름

```
[Normal Operation]
  Circuit Breaker: CLOSED
  All requests: Allowed

[Failure Detected]
  Failure Rate: > 50% (10 calls)
  → Transition to OPEN

[Open State]
  New requests: Blocked (CircuitBreakerOpenException)
  Duration: 10s
  Purpose: Cascade failure 방지

[After 10s]
  → HALF_OPEN
  Test 3 calls

[Half-Open State]
  Success: → CLOSED (복구 완료)
  Failure: → OPEN (다시 10s 대기)
```

## Prometheus 메트릭

```promql
# Circuit Breaker 상태
resilience4j_circuitbreaker_state{name="nexonApi"}  # CLOSED, OPEN, HALF_OPEN

# 실패율
resilience4j_circuitbreaker_failure_rate{name="nexonApi"}

# 호출 차단 횟수
rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="nexonApi"}[5m])
```

## 출처
- [Incident Report N21: Auto-Mitigation](../../../../05_Reports/04_05_Incidents/INCIDENT_REPORT_N21_ACTUAL.md)
- [Resilience Baseline](../../../../05_Reports/04_08_Refactor/RESILIENCE_BASELINE.md)
