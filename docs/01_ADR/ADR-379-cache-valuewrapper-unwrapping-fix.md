# ADR-379: Cache.ValueWrapper 명시적 언래핑으로 캐시 히트시 런타임 실패 수정

## 제1장: 문제의 발견 (Problem)

### 1.1 캐시 히트시 런타임 실패 (P0 CRITICAL)

`ExpectationCacheCoordinator`에서 캐시 히트(Cache Hit)가 발생할 때마다 `EquipmentDataProcessingException`이 발생하여 캐시의 기본 목적(빠른 데이터 제공)이 무력화되는 문제가 발견되었습니다.

### 1.2 근본 원인: ValueWrapper 언래핑 누락

Spring Framework의 `Cache` 인터페이스는 `get(Object key)` 메서드가 `ValueWrapper`를 반환하도록 설계되어 있습니다:

```java
// Spring Cache 인터페이스
public interface Cache {
    ValueWrapper get(Object key);  // ValueWrapper 반환
    // ...
}
```

그러나 `ExpectationCacheCoordinator`의 두 메서드에서 이를 언래핑하지 않고 직접 `convertCachedValueToBase64()`에 전달하는 오류가 있었습니다:

**문제 코드 (getOrCalculate 메서드, 85행):**
```java
Object cachedValue = expectationCache.get(userIgn);  // ValueWrapper 반환
if (cachedValue != null) {
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
    // cachedValue는 ValueWrapper 객체이지, String/byte[]가 아님!
}
```

**문제 코드 (getGzipOrCalculate 메서드, 133행):**
```java
Object cachedValue = expectationCache.get(userIgn);  // ValueWrapper 반환
if (cachedValue != null) {
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
    // 동일한 문제
}
```

### 1.3 convertCachedValueToBase64의 부분적 해결

`convertCachedValueToBase64()` 메서드(226행)는 이미 `SimpleValueWrapper` 언래핑 로직을 포함하고 있었습니다:

```java
private String convertCachedValueToBase64(Object cachedValue, String userIgn) {
    Object unwrappedValue = cachedValue;
    if (cachedValue instanceof org.springframework.cache.support.SimpleValueWrapper wrapper) {
        unwrappedValue = wrapper.get();
    }
    // ...
}
```

그러나 이 코드는 다음과 같은 제한이 있었습니다:
1. **구체적 클래스 체크**: `SimpleValueWrapper`로만 체크하여 `ValueWrapper` 인터페이스를 구현한 다른 구현체를 처리하지 못함
2. **호출 지점 오류**: 메서드 호출 시점에 이미 `ValueWrapper`가 넘어오고 있어, 타입 체크 전에 타입 불일치 발생

### 1.4 영향도 분석

- **심각도**: P0 CRITICAL
- **영향 범위**: V4 캐시 히트 경로 전체
- **증상**: 캐시에서 데이터를 조회할 때마다 실패하여 매번 계산을 다시 수행
- **성능 영향**: 캐시의 성능 이점이 완전히 상실됨

---

## 제2장: 선택지 탐색 (Options)

### 2.1 선택지 1: ValueWrapper.get()으로 명시적 언래핑 (채택)

**방식**: `Cache.get()` 호출 즉시 `ValueWrapper.get()`으로 언래핑합니다.

```java
Cache.ValueWrapper wrapper = expectationCache.get(userIgn);
if (wrapper != null) {
    Object cachedValue = wrapper.get();  // 명시적 언래핑
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
    return decompressCachedResponse(compressedBase64, userIgn);
}
```

**장점**:
- **명시적**: `ValueWrapper` 타입을 명확히 사용하여 코드의 의도가 드러남
- **일관성**: Spring Cache API의 의도를 정확히 따름
- **안전성**: 널 체크 후 언래핑으로 NPE 방지
- **호환성**: 모든 `ValueWrapper` 구현체 처리 가능

**단점**:
- 중간 변수 `wrapper` 추가 (가독성에는 오히려 도움)

**결론**: **채택** - Spring Cache API 표준 사용

### 2.2 선택지 2: Cache.get(key, Class) 사용

**방식**: Spring의 편의 메서드 `get(Object key, Class<T> type)`를 사용하여 자동으로 언래핑하고 타입 변환까지 수행합니다.

```java
String cachedValue = expectationCache.get(userIgn, String.class);
if (cachedValue != null) {
    // 이미 String으로 언래핑됨
}
```

**장점**:
- 간결한 코드
- 타입 안전성 컴파일 타임 보장

**단점**:
- **마이그레이션 불가**: 기존 byte[] 형식 캐시 데이터 처리 불가 (ClassCastException)
- **유연성 상실**: 다양한 레거시 형식(byte[], String)을 모두 처리해야 하는 현재 상황에 부적합

**결론**: 부적합 - 레거시 데이터 마이그레이션 필요

### 2.3 선택지 3: convertCachedValueToBase64에서 ValueWrapper 처리

**방식**: 기존 `SimpleValueWrapper` 체크를 `ValueWrapper` 인터페이스 체크로 변경.

```java
private String convertCachedValueToBase64(Object cachedValue, String userIgn) {
    Object unwrappedValue = cachedValue;
    if (cachedValue instanceof Cache.ValueWrapper wrapper) {
        unwrappedValue = wrapper.get();
    }
    // ...
}
```

**장점**:
- 호출 지점 수정 최소화
- 중앙집중식 언래핑 로직

**단점**:
- **암묵적 처리**: `ValueWrapper` 타입을 명시하지 않아 코드 의도가 불분명
- **타입 혼재**: `Object`로 선언하여 정적 타이핑 이점 상실
- **디버깅 어려움**: 어떤 시점에 언래핑되는지 추적 불가

**결론**: 부적합 - 암묵적 처리는 가독성 저하

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 결정: 선택지 1 채택

**ValueWrapper.get()으로 명시적 언래핑**을 채택하여 다음 원칙을 준수합니다:

1. **CLAUDE.md 섹션 4 (Optional Chaining)**: 명시적이고 선언적인 코드 작성
2. **Spring Framework 표준**: `Cache.get()` → `ValueWrapper.get()` 패턴 따름
3. **타입 안전성**: 컴파일 타임에 타입 체크

### 3.2 수정 패턴

**Before (버그):**
```java
Object cachedValue = expectationCache.get(userIgn);
if (cachedValue != null) {
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
}
```

**After (수정):**
```java
Cache.ValueWrapper wrapper = expectationCache.get(userIgn);
if (wrapper != null) {
    Object cachedValue = wrapper.get();
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
}
```

### 3.3 수정 범위

- **getOrCalculate() 메서드**: 85-88행
- **getGzipOrCalculate() 메서드**: 133-140행
- **convertCachedValueToBase64()**: 기존 `SimpleValueWrapper` 체크 유지 (레거시 호환용)

---

## 제4장: 구현 (Implementation)

### 4.1 코드 수정

**파일**: `module-app/src/main/java/maple/expectation/service/v4/cache/ExpectationCacheCoordinator.java`

**getOrCalculate() 수정 (85-89행):**
```java
Cache.ValueWrapper wrapper = expectationCache.get(userIgn);
if (wrapper != null) {
    Object cachedValue = wrapper.get();
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
    return decompressCachedResponse(compressedBase64, userIgn);
}
```

**getGzipOrCalculate() 수정 (133-141행):**
```java
Cache.ValueWrapper wrapper = expectationCache.get(userIgn);
if (wrapper != null) {
    Object cachedValue = wrapper.get();
    String compressedBase64 = convertCachedValueToBase64(cachedValue, userIgn);
    if (compressedBase64 == null || compressedBase64.isEmpty()) {
        throw new CacheDataNotFoundException(userIgn);
    }
    log.debug("[V4] GZIP Cache HIT: {} ({}KB)", userIgn, compressedBase64.length() / 1024);
    return java.util.Base64.getDecoder().decode(compressedBase64);
}
```

### 4.2 convertCachedValueToBase64 보존

기존 `SimpleValueWrapper` 체크는 **레거시 byte[] 형식 마이그레이션**을 위해 유지합니다:

```java
private String convertCachedValueToBase64(Object cachedValue, String userIgn) {
    // Unwrap SimpleValueWrapper (Spring Cache wrapper)
    Object unwrappedValue = cachedValue;
    if (cachedValue instanceof org.springframework.cache.support.SimpleValueWrapper wrapper) {
        unwrappedValue = wrapper.get();
        log.debug("[V4] Unwrapped SimpleValueWrapper for: {}", userIgn);
    }
    // ... 기존 로직 유지
}
```

이중 언래핑 방지를 위해 호출 지점에서 이미 `wrapper.get()`을 호출하므로, 이 체크는 더 이상 실행되지 않지만 코드의 안전성을 위해 보존합니다.

---

## 제5장: 검증 (Verification)

### 5.1 단위 테스트 시나리오

1. **Cache HIT (String format)**: Base64 String이 캐시된 경우 → 성공 반환
2. **Cache HIT (byte[] legacy format)**: 레거시 byte[]가 캐시된 경우 → 마이그레이션 후 반환
3. **Cache MISS**: 캐시에 데이터 없는 경우 → 계산 후 저장

### 5.2 테스트 코드 예시

```java
@Test
@DisplayName("캐시 히트 시 ValueWrapper 언래핑 후 반환 성공")
void cacheHit_ShouldUnwrapValueWrapper() {
    // Given: 캐시에 Base64 String 저장
    String testData = base64EncodedGzip;
    Cache.ValueWrapper wrapper = new SimpleValueWrapper(testData);
    when(expectationCache.get(userIgn)).thenReturn(wrapper);

    // When: 캐시 조회
    EquipmentExpectationResponseV4 result = coordinator.getOrCalculate(userIgn, false, calculator);

    // Then: ValueWrapper 언래핑 후 압축 해제 성공
    assertThat(result).isNotNull();
    verify(calculator, never()).call();  // 계산 미실행
}
```

---

## 제6장: 관련 문서 (Related Documents)

- **CLAUDE.md 섹션 4**: Optional Chaining Best Practice
- **CLAUDE.md 섹션 12**: LogicExecutor 패턴
- **TieredCache.java**: `get(Object key)`가 `ValueWrapper`를 반환하는 구현
- **Spring Framework Cache Abstraction**: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/Cache.html

---

## 상태 (Status)

**상태**: 🟢 Accepted (2026-02-23)

**적용 대상**:
- `ExpectationCacheCoordinator.java` (2개 메서드 수정)

**다음 작업**:
- [ ] 단위 테스트 작성
- [ ] 통합 테스트 검증
- [ ] 코드 리뷰
