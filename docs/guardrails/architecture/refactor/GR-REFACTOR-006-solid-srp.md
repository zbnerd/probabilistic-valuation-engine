---
id: GR-REFACTOR-006
category: architecture/refactor
severity: warning
keywords: [solid, srp, single-responsibility, god-class]
languages: [java, kotlin]
---

# SRP (Single Responsibility Principle) - God Class 분리

## DON'T (God Class Anti-pattern)
- 하나의 클래스가 5개 이상의 책임을 담당
- 300+ 라인의 서비스 클래스
- 9개 이상의 의존성 주입

```java
// Bad: EquipmentService - 330라인, 8개 책임
@Service
public class EquipmentService {
    private final GameCharacterFacade gameCharacterFacade;
    private final EquipmentDataProvider equipmentProvider;
    private final EquipmentStreamingParser streamingParser;
    // ... 6개 더 많은 의존성

    // 책임 1: Orchestration
    public CompletableFuture<TotalExpectationResponse> calculateTotalExpectationAsync(String userIgn) { ... }

    // 책임 2: Cache coordination
    private void warmUpCache(String ocid, EquipmentResponse response) { ... }

    // 책임 3: Async pipeline management
    private <T> CompletableFuture<T> executeAsync(Supplier<T> task) { ... }

    // 책임 4: Snapshot management
    private void saveSnapshot(String ocid, TotalExpectationResponse response) { ... }

    // 책임 5: Calculation dispatch
    private TotalExpectationResponse dispatchCalculation(String userIgn) { ... }

    // 책임 6: Legacy API support
    public EquipmentResponse getEquipmentByUserIgn(String userIgn) { ... }

    // 책임 7: GZIP streaming
    public void streamEquipmentDataRaw(String userIgn, OutputStream outputStream) { ... }

    // 책임 8: Exception handling
    private TotalExpectationResponse handleException(Exception e, String userIgn) { ... }
}
```

```kotlin
// Bad: God Class - 200+ 라인, 다중 책임
@Service
class EquipmentExpectationServiceV4 {
    // 6개 책임 혼합
    fun calculateExpectationAsync(...) { }  // Async dispatch
    fun getGzipExpectationAsync(...) { }     // GZIP handling
    fun calculateAllPresets(...) { }         // Preset calculation
    fun getGzipFromL1CacheDirect(...) { }   // Fast path
    fun buildResponse(...) { }               // Response building
}
```

## DO (Single Responsibility - 책임 분리)
- 각 클래스는 **단 하나의 책임**만 가짐
- 100라인 이하의 클래스 유지
- 3-5개 의존성 주입으로 제한

```java
// Good: 책임 분리
// 1. Orchestrator - 조합만 담당
@Service
public class EquipmentOrchestrator {
    private final EquipmentCalculationService calculationService;
    private final EquipmentCacheService cacheService;

    public CompletableFuture<TotalExpectationResponse> calculateExpectation(String userIgn) {
        return cacheService.getCached(userIgn)
            .map(CompletableFuture::completedFuture)
            .orElseGet(() -> calculationService.calculate(userIgn));
    }
}

// 2. Calculation Service - 계산만 담당
@Service
public class EquipmentCalculationService {
    private final CalculatorFactory calculatorFactory;

    public CompletableFuture<TotalExpectationResponse> calculate(String userIgn) {
        return CompletableFuture.supplyAsync(() -> {
            var calculator = calculatorFactory.create();
            return calculator.calculate(userIgn);
        });
    }
}

// 3. Streaming Service - GZIP만 담당
@Service
public class EquipmentStreamingService {
    public void streamEquipmentData(String userIgn, OutputStream outputStream) {
        // GZIP streaming 로직만
    }
}

// 4. Response Builder - 응답 변환만 담당
@Component
public class EquipmentResponseBuilder {
    public TotalExpectationResponse buildResponse(CalculationResult result) {
        // DTO 변환만
    }
}
```

```kotlin
// Good: 책임 분리
// 1. Orchestrator
@Service
class EquipmentOrchestrator(
    private val calculationService: EquipmentCalculationService,
    private val cacheService: EquipmentCacheService
) {
    fun calculateExpectation(userIgn: String): CompletableFuture<TotalExpectationResponse> {
        return cacheService.getCached(userIgn)
            .map { CompletableFuture.completedFuture(it) }
            .orElseGet { calculationService.calculate(userIgn) }
    }
}

// 2. Calculation Service
@Service
class EquipmentCalculationService(
    private val calculatorFactory: CalculatorFactory
) {
    fun calculate(userIgn: String): CompletableFuture<TotalExpectationResponse> {
        return CompletableFuture.supplyAsync {
            val calculator = calculatorFactory.create()
            calculator.calculate(userIgn)
        }
    }
}

// 3. Streaming Service
@Service
class EquipmentStreamingService {
    fun streamEquipmentData(userIgn: String, outputStream: OutputStream) {
        // GZIP만 담당
    }
}
```

## SRP 위반 탐지 가이드

| 위반 징후 | 임계값 | 조치 |
|-----------|--------|------|
| 클래스 라인 수 | > 300 | 분리 검토 |
| 메서드 수 | > 15 | 분리 검토 |
| 의존성 수 | > 7 | 분리 검토 |
| 책임 수 | > 3 | 즉시 분리 |

## 리팩토링 전후

| 메트릭 | Before (God Class) | After (분리) | 개선율 |
|--------|-------------------|---------------|--------|
| 클래스 라인 수 | 330 | 80 × 4 | 76% 감소 |
| 의존성 수 | 9 | 2-3 × 4 | 67% 감소 |
| 테스트 가능성 | 낮음 | 높음 | - |
| 유지보수성 | 어려움 | 쉬움 | - |

## 출처
- [SOLID Violations Report](../../../../05_Reports/04_08_Refactor/SOLID_VIOLATIONS.md) - SRP-001 ~ SRP-012
- [Code Quality Analysis](../../../../05_Reports/04_08_Refactor/CODE_QUALITY_ANALYSIS_2026-02-08.md)
