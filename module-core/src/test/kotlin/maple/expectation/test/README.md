# Core Unit Test Template

Core 레이어 단위 테스트를 위한 표준 템플릿입니다.

## 목차

- [개요](#개요)
- [언제 사용해야 하나요?](#언제-사용해야-하나요)
- [빠른 시작](#빠른-시작)
- [Given-When-Then 패턴](#given-when-then-패턴)
- [Property-Based Testing](#property-based-testing)
- [Assertion Helpers](#assertion-helpers)
- [예제 참조](#예제-참조)
- [Best Practices](#best-practices)

## 개요

`CoreUnitTestTemplate`은 Core 레이어의 순수 도메인 로직 테스트를 위한 기반 클래스입니다.

### 특징

- **Spring Context 미사용**: 빠른 테스트 실행 (일반적으로 < 100ms)
- **순수 Kotlin/Java 로직**: 외부 의존성 없이 도메인 로직만 검증
- **Property-Based Testing 지원**: jqwik를 활용한 무작위 입력 기반 테스트
- **Flaky Test 방지**: 시간/외부 의존성 제거로 결정적 테스트 보장

### Anti-patterns (금지 사항)

```kotlin
// ❌ 금지: Spring Context 로딩
@SpringBootTest
class BadTest : CoreUnitTestTemplate() { }

// ❌ 금지: Database 직접 접근
@Test
fun badTest() {
    val result = jdbcTemplate.queryForObject(...)
}

// ❌ 금지: External API 호출
@Test
fun badTest() {
    val response = restTemplate.getForObject(...)
}
```

## 언제 사용해야 하나요?

### ✅ 적합한 경우

| 상황 | 예시 |
|------|------|
| **Value Object 검증** | `ItemPrice`, `CharacterId`, `UserIgn` 등의 생성/검증 로직 |
| **Domain Service 로직** | `CostFormatter.format()`, `StatParser.parse()` |
| **Parser/Converter** | 문자열 파싱, 데이터 변환 로직 |
| **Business Rule 검증** | 도메인 불변성(invariant), 계산 로직 |
| **계산 알고리즘** | 확률 계산, 기대값 계산 |

### ❌ 부적합한 경우

| 상황 | 대안 |
|------|------|
| **통합 테스트 필요** | `IntegrationTestBase` 사용 |
| **Database 조회** | `@SpringBootTest` + `Testcontainers` |
| **외부 API 연동** | `ExternalApiTestTemplate` 사용 |
| **비동기 작업 테스트** | `UsecaseTestTemplate` + `Awaitility` |

## 빠른 시작

### 1. 템플릿 상속

```kotlin
package maple.expectation.core.domain.cost

import maple.expectation.test.CoreUnitTestTemplate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostFormatterTest : CoreUnitTestTemplate() {

    @Test
    fun `10000 메소는 1만으로 포맷되어야 한다`() {
        // Given
        val cost = given { BigDecimal.valueOf(10000) }

        // When
        val formatted = `when` { CostFormatter.format(cost) }

        // Then
        then(formatted) { result ->
            assertEqual("1만", result)
        }
    }
}
```

### 2. 실행 확인

```bash
# 단일 테스트 실행
./gradlew :module-core:test --tests CostFormatterTest

# 전체 Core 단위 테스트
./gradlew :module-core:test
```

## Given-When-Then 패턴

템플릿이 제공하는 헬퍼 메서드로 명확한 테스트 구조를 만들 수 있습니다.

### Given: 데이터 준비

```kotlin
val price = given {
    ItemPrice.of(12345L, "아케인 심볼", 10_000_000L)
}
```

### When: 로직 실행

```kotlin
// `when`은 Kotlin 예약어이므로 백틱 사용
val isFresh = `when` {
    price.isFreshWithinHours(24)
}
```

### Then: 결과 검증

```kotlin
then(isFresh) { result ->
    assertTrue(result, "생성 직후의 가격은 신선해야 함")
}
```

### 체이닝 패턴

```kotlin
`when` { price.itemId }.also { actualId ->
    assertEqual(12345L, actualId)
}
```

## Property-Based Testing

jqwik를 사용하여 무작위 입력값으로 불변성을 검증합니다.

### 기본 패턴

```kotlin
import net.jqwik.api.Arbitrary
import net.jqwik.api.providers.ArbitraryProvider
import net.jqwik.api.ForAll

@Property
fun `모든 양수 itemId는 유효해야 한다`(@ForAll("positiveIds") id: Long) {
    val price = ItemPrice.of(id, "Test", 1000L)
    assertThat(price.itemId).isEqualTo(id)
}

companion object {
    @Provide
    fun positiveIds(): Arbitrary<Long> =
        Arbitraries.longs().between(1, Long.MAX_VALUE)
}
```

### 불변성 검증 예시

```kotlin
@Property
fun `가격 포맷은 항상 비어있지 않은 문자열을 반환해야 한다`(
    @ForAll("costs") cost: Long
) {
    val formatted = CostFormatter.format(cost)
    assertThat(formatted).isNotEmpty()
}

companion object {
    @Provide
    fun costs(): Arbitrary<Long> =
        Arbitraries.longs().between(0, 1_000_000_000_000)
}
```

### jqwik Property-Based Testing 패턴

jqwik 애너테이션을 직접 사용하는 것이 권장됩니다:

```kotlin
import net.jqwik.api.Property
import net.jqwik.api.ForAll
import net.jqwik.api.Arbitrary
import net.jqwik.api.Provide
import net.jqwik.api.Arbitraries

class CostFormatterPropertyTest : CoreUnitTestTemplate() {

    @Property
    fun `모든 음수가 아닌 금액은 포맷 결과가 비어있지 않다`(
        @ForAll("nonNegativeCosts") cost: Long
    ) {
        val formatted = CostFormatter.format(cost)
        assertThat(formatted).isNotEmpty()
    }

    companion object {
        @Provide
        fun nonNegativeCosts(): Arbitrary<Long> =
            Arbitraries.longs().between(0, 1_000_000_000_000)
    }
}
```

## Assertion Helpers

### assertEqual

```kotlin
assertEqual(expected, actual)
```

### assertTrue / assertFalse

```kotlin
assertTrue(condition, "조건 설명")
assertFalse(condition, "실패 이유")
```

### assertNotNull / assertNull

```kotlin
assertNotNull(value, "null이면 안 되는 이유")
assertNull(value, "null이어야 하는 이유")
```

### assertThrows

```kotlin
val exception = assertThrows(IllegalArgumentException::class.java) {
    ItemPrice.of(-1L, "Invalid", 1000)
}

assertThat(exception.message).contains("must be positive")
```

## 예제 참조

`CoreUnitTestTemplateExample`에서 모든 패턴의 실제 예제를 확인하세요.

```bash
# 예제 테스트 실행
./gradlew :module-core:test --tests CoreUnitTestTemplateExample
```

### 예제 내용

1. Given-When-Then 패턴
2. Assertion Helpers 사용
3. 예외 검증
4. Business Logic 테스트
5. Edge Case 테스트
6. Data-Driven Testing (Parameterized)
7. Companion Object Factory 테스트

## Best Practices

### 1. 시간 기반 로직은 Clock 주입

```kotlin
// ❌ Bad: 비결정적
fun isExpired() = LocalDate.now().isAfter(expiryDate)

// ✅ Good: 테스트 가능
fun isExpired(clock: Clock) = LocalDate.now(clock).isAfter(expiryDate)

// 테스트
val fixedClock = Clock.fixed(
    Instant.parse("2024-03-15T10:00:00Z"),
    ZoneOffset.UTC
)
assertTrue(service.isExpired(fixedClock))
```

### 2. 테스트 이름은 명확하게

```kotlin
// ✅ Good: 한글로 명확한 의도
@Test
fun `10000 메소는 1만으로 포맷되어야 한다`() { }

@Test
fun `itemId가 음수면 예외가 발생해야 한다`() { }
```

### 3. 한 테스트당 하나의 검증

```kotlin
// ❌ Bad: 여러 검증
@Test
fun bad() {
    val price = ItemPrice.of(1, "A", 1000)
    assertThat(price.itemId).isEqualTo(1)
    assertThat(price.itemName).isEqualTo("A")
    assertThat(price.price).isEqualTo(1000)
}

// ✅ Good: 책임 분리
@Test
fun `itemId가 정확히 설정되어야 한다`() {
    val price = ItemPrice.of(123L, "A", 1000)
    assertEqual(123L, price.itemId)
}

@Test
fun `itemName이 정확히 설정되어야 한다`() {
    val price = ItemPrice.of(1L, "Arcane Symbol", 1000)
    assertEqual("Arcane Symbol", price.itemName)
}
```

### 4. Edge Case 테스트

```kotlin
@Test
fun `0원 가격은 허용되어야 한다`() {
    val price = ItemPrice.of(1L, "Free", 0L)
    assertEqual(0L, price.price)
}

@Test
fun `Long MAX VALUE 가격도 처리 가능해야 한다`() {
    val price = ItemPrice.of(1L, "Expensive", Long.MAX_VALUE)
    assertEqual(Long.MAX_VALUE, price.price)
}
```

### 5. Flaky Test 방지

- ❌ `Thread.sleep()` 금지
- ❌ `LocalDate.now()` 직접 호출 금지
- ❌ `Math.random()` 직접 호출 금지
- ✅ `Clock` 주입 사용
- ✅ 고정된 값/생성기 사용
- ✅ `Awaitility` 사용 (비동기 대기)

## 관련 문서

- [테스트 가이드](../../../docs/03_Technical_Guides/testing-guide.md)
- [Flaky Test 방지](../../../docs/03_Technical_Guides/testing-guide.md#24-flaky-test-근본-원인-분석-및-해결-가이드-critical)
- [CLAUDE.md - 테스트 규칙](../../../CLAUDE.md#10-definition-of-done)

## 실행 및 검증

```bash
# 컴파일 확인
./gradlew :module-core:compileTestKotlin --continue

# 테스트 실행
./gradlew :module-core:test
```

## 질문?

새로운 테스트 작성 시 어려움이 있으면:
1. `CoreUnitTestTemplateExample.kt` 참조
2. 기존 테스트 코드 확인 (`module-core/src/test/`)
3. 팀원에게 문의 또는 Issue 생성
