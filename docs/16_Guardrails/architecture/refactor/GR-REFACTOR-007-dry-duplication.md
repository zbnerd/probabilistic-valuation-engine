---
id: GR-REFACTOR-007
category: architecture/refactor
severity: info
keywords: [dry, duplication, code-reuse, template-method]
languages: [java, kotlin]
---

# DRY (Don't Repeat Yourself) - 코드 중복 제거

## DON'T (중복 코드 Anti-pattern)

### 1. Controller 응답 패턴 중복 (5회 반복)

```java
// Bad: V2, V3, V4 모두 동일한 패턴 반복
public class GameCharacterControllerV2 {
    public CompletableFuture<ResponseEntity<TotalExpectationResponse>> calculateTotalCost(
            @PathVariable String userIgn) {
        return equipmentService.calculateTotalExpectationAsync(userIgn)
            .thenApply(ResponseEntity::ok);  // 중복
    }

    public CompletableFuture<ResponseEntity<EquipmentResponse>> getEquipment(
            @PathVariable String userIgn) {
        return equipmentService.getEquipmentByUserIgnAsync(userIgn)
            .thenApply(ResponseEntity::ok);  // 중복
    }
}

public class GameCharacterControllerV4 {
    public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectation(
            @PathVariable String userIgn) {
        return equipmentService.calculateExpectationAsync(userIgn)
            .thenApply(ResponseEntity::ok);  // 중복
    }

    public CompletableFuture<ResponseEntity<byte[]>> getGzipExpectation(
            @PathVariable String userIgn) {
        return equipmentService.getGzipExpectationAsync(userIgn)
            .thenApply(bytes -> buildGzipResponse(bytes));  // 중복
    }
}
```

```kotlin
// Bad: 동일한 응답 패턴 반복
@Controller
class GameCharacterControllerV2 {
    fun calculateTotalCost(@PathVariable userIgn: String): CompletableFuture<ResponseEntity<TotalExpectationResponse>> {
        return equipmentService.calculateTotalExpectationAsync(userIgn)
            .thenApply { ResponseEntity.ok(it) }  // 중복
    }
}
```

### 2. Cube Decorator 계산 로직 중복 (V2 vs V4, 90% 유사)

```java
// Bad: V2 - long 기반
public class BlackCubeDecorator extends EnhanceDecorator {
    @Override
    public long calculateCost() {
        long previousCost = super.calculateCost();
        long expectedTrials = calculateTrials();
        long costPerTrial = costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade());
        return previousCost + (expectedTrials * costPerTrial);
    }
}

// Bad: V4 - BigDecimal 기반 (논리는 동일)
public class BlackCubeDecoratorV4 extends EnhanceDecorator {
    @Override
    public BigDecimal calculateCost() {
        BigDecimal previousCost = super.calculateCost();
        BigDecimal expectedTrials = calculateTrials();
        BigDecimal costPerTrial = BigDecimal.valueOf(
            costPolicy.getCubeCost(CubeType.BLACK, input.getLevel(), input.getGrade()));
        return previousCost.add(blackCubeCost);  // 타입만 다름
    }
}
```

## DO (공통 유틸리티 추출)

### 1. AsyncResponseUtils - 응답 패턴 통합

```java
// Good: 공통 유틸리티 클래스
public class AsyncResponseUtils {

    public static <T> CompletableFuture<ResponseEntity<T>> ok(
            CompletableFuture<T> future) {
        return future.thenApply(ResponseEntity::ok);
    }

    public static <T> CompletableFuture<ResponseEntity<byte[]>> okWithGzip(
            CompletableFuture<T> future,
            boolean acceptsGzip,
            Function<T, byte[]> gzipConverter
    ) {
        if (acceptsGzip) {
            return future.thenApply(data -> buildGzipResponse(gzipConverter.apply(data)));
        }
        return future.thenApply(data -> ResponseEntity.ok(data));
    }

    private static ResponseEntity<byte[]> buildGzipResponse(byte[] gzipBytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(gzipBytes.length)
                .body(gzipBytes);
    }
}

// 사용
@Controller
@RequiredArgsConstructor
public class GameCharacterControllerV4 {
    private final EquipmentService equipmentService;

    @GetMapping("/{userIgn}/expectation")
    public CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> getExpectation(
            @PathVariable String userIgn) {
        return AsyncResponseUtils.ok(
            equipmentService.calculateExpectationAsync(userIgn)
        );
    }
}
```

```kotlin
// Good: 확장 함수로 간결하게
object AsyncResponseUtils {
    fun <T> CompletableFuture<T>.okResponse(): CompletableFuture<ResponseEntity<T>> {
        return this.thenApply { ResponseEntity.ok(it) }
    }

    fun <T> CompletableFuture<T>.okWithGzip(
        acceptsGzip: Boolean,
        gzipConverter: (T) -> ByteArray
    ): CompletableFuture<ResponseEntity<ByteArray>> {
        return if (acceptsGzip) {
            this.thenApply { buildGzipResponse(gzipConverter(it)) }
        } else {
            this.thenApply { ResponseEntity.ok(it) }
        }
    }
}

// 사용
@Controller
class GameCharacterControllerV4(
    private val equipmentService: EquipmentService
) {
    @GetMapping("/{userIgn}/expectation")
    fun getExpectation(@PathVariable userIgn: String): CompletableFuture<ResponseEntity<EquipmentExpectationResponseV4>> {
        return equipmentService.calculateExpectationAsync(userIgn)
            .okResponse()
    }
}
```

### 2. AbstractCubeDecorator - 제네릭 기반 추상화

```java
// Good: 제네릭 기반 추상 Decorator
public abstract class AbstractCubeDecorator<N extends Number> extends EquipmentEnhanceDecorator {

    protected final CubeTrialsProvider trialsProvider;
    protected final CubeCostPolicy costPolicy;
    protected final CubeCalculationInput input;

    // Template Method Pattern
    @Override
    public N calculateCost() {
        N previousCost = getPreviousCost();
        N expectedTrials = calculateTrials();
        N costPerTrial = getCostPerTrial();
        return addCosts(previousCost, multiply(expectedTrials, costPerTrial));
    }

    // Subclass에서 타입별 구현
    protected abstract N getPreviousCost();
    protected abstract N calculateTrials();
    protected abstract N getCostPerTrial();
    protected abstract N addCosts(N a, N b);
    protected abstract N multiply(N a, N b);
}

// V2 구현체 - 단순 래퍼
public class BlackCubeDecoratorV2 extends AbstractCubeDecorator<Long> {
    @Override
    protected Long addCosts(Long a, Long b) { return a + b; }

    @Override
    protected Long multiply(Long a, Long b) { return a * b; }

    // ... 기본 타입 연산
}

// V4 구현체 - 단순 래퍼
public class BlackCubeDecoratorV4 extends AbstractCubeDecorator<BigDecimal> {
    @Override
    protected BigDecimal addCosts(BigDecimal a, BigDecimal b) { return a.add(b); }

    @Override
    protected BigDecimal multiply(BigDecimal a, BigDecimal b) { return a.multiply(b); }

    // ... BigDecimal 연산
}
```

```kotlin
// Good: 제네릭 추상화
abstract class AbstractCubeDecorator<N : Number> : EquipmentEnhanceDecorator() {
    protected abstract fun addCosts(a: N, b: N): N
    protected abstract fun multiply(a: N, b: N): N
    protected abstract fun calculateTrials(): N
    protected abstract fun getCostPerTrial(): N

    override fun calculateCost(): N {
        val previousCost = previousCost
        val expectedTrials = calculateTrials()
        val costPerTrial = getCostPerTrial()
        return addCosts(previousCost, multiply(expectedTrials, costPerTrial))
    }
}

class BlackCubeDecoratorV2 : AbstractCubeDecorator<Long>() {
    override fun addCosts(a: Long, b: Long) = a + b
    override fun multiply(a: Long, b: Long) = a * b
    // ...
}

class BlackCubeDecoratorV4 : AbstractCubeDecorator<BigDecimal>() {
    override fun addCosts(a: BigDecimal, b: BigDecimal) = a.add(b)
    override fun multiply(a: BigDecimal, b: BigDecimal) = a.multiply(b)
    // ...
}
```

### 3. Cache Service 템플릿화

```java
// Good: 추상 템플릿
public abstract class AbstractTieredCacheService<K, V> {
    protected final TieredCacheStrategy<K, V> strategy;
    protected final LogicExecutor executor;

    public Optional<V> getValidCache(K key) {
        return executor.execute(() -> {
            // L1 → L2 → L1 Warm-up 패턴 통합
            Optional<V> l1Hit = strategy.getFromL1(key);
            if (l1Hit.isPresent()) {
                return l1Hit;
            }

            Optional<V> l2Hit = strategy.getFromL2(key);
            if (l2Hit.isPresent()) {
                strategy.saveToL1(key, l2Hit.get()); // Warm-up
                return l2Hit;
            }

            return Optional.empty();
        }, buildContext("GetValid", key));
    }

    public void saveCache(K key, V value) {
        executor.executeVoid(() -> {
            if (strategy.isValid(value)) {
                strategy.saveToL2(key, value); // L2 first
            }
            strategy.saveToL1(key, value);     // L1 always
        }, buildContext("Save", key));
    }
}
```

## 리팩토링 효과

| 중복 유형 | Before | After | 감소율 |
|-----------|--------|-------|--------|
| Controller 응답 | 5회 반복 | 유틸리티 1개 | 80% |
| Cube Decorator | 6개 × 2 = 12개 파일 | 제네릭 1개 + 래퍼 12개 | 90% |
| Cache Service | 3개 × 80라인 | 템플릿 1개 + 구현 3개 | 68% |
| **전체 코드 라인** | - | - | **15% 감소** |

## 출처
- [Duplicated Code Analysis](../../../../05_Reports/05_08_Refactor/duplicated-code-analysis.md)
- [Clean Code Analysis](../../../../05_Reports/05_08_Refactor/cleancode-analysis-2026-02-08.md)
