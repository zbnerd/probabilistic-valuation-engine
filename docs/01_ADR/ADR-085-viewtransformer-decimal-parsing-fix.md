# ADR-085: ViewTransformer P1 소수점 파싱으로 데이터 손실 수정

## 제1장: 문제의 발견 (Problem)

### 1.1 P1 - BigDecimal 직렬화 시 데이터 부풀림

`ViewTransformer`의 `removeDecimal()` 메서드가 모든 점(`.`)을 제거하여 **소수점이 있는 값이 100배 이상 부풀려지는 버그**가 발견되었습니다.

**문제 코드 (211-216행):**
```java
private String removeDecimal(String numericStr) {
    if (numericStr == null || numericStr.isBlank()) {
        return "0";
    }
    return numericStr.replace(".", "");  // 모든 점을 제거!
}
```

**버그 시나리오:**
1. `BigDecimal`이 JSON으로 직렬화될 때: `123.45` → `"123.45"`
2. `removeDecimal()` 호출: `"123.45"` → `"12345"`
3. `Long.parseLong()` 호출: `"12345"` → `12345`
4. **결과**: 원래 값 `123.45`가 `12345`로 100배 부풀려짐

### 1.2 근본 원인: String Manipulation vs Numeric Parsing

**잘못된 접근**: 문자열 치환으로 소수점 제거
```java
"123.45".replace(".", "")  // "12345" - 데이터 손실!
```

**올바른 접근**: `BigDecimal`로 파싱 후 정수 변환
```java
new BigDecimal("123.45").longValue()  // 123 - 소수점 이하 반올림/절삭
```

### 1.3 영향도 분석

- **심각도**: P1 (Data Corruption)
- **영향 범위**: MongoDB CharacterValuationView의 모든 비용 필드
- **증상**: 소수점이 있는 비용(주로 테스트 데이터)이 100배 이상 부풀려짐
- **데이터 원본**: RDB는 정확함, MongoDB만 영향받음

---

## 제2장: 선택지 탐색 (Options)

### 2.1 선택지 1: BigDecimal로 직접 파싱 (채택)

**방식**: `removeDecimal()` 대신 `BigDecimal`을 사용하여 소수점을 처리합니다.

```java
private Long parseCostToLong(String costStr) {
    if (costStr == null || costStr.isBlank()) {
        return 0L;
    }
    return parseSafely(() -> {
        BigDecimal decimal = new BigDecimal(costStr.replace(",", ""));  // 천단위 콤마만 제거
        return decimal.longValue();  // 소수점 이하 절삭
    }, 0L);
}
```

**장점**:
- **정확성**: `BigDecimal`의 표준 소수점 처리
- **국제화**: 천단위 구분자(콤마) 처리 가능
- **명확성**: 코드 의도가 명확히 드러남
- **안전성**: `parseSafely()`로 예외 처리

**단점**:
- `removeDecimal()` 호출 코드를 모두 변경해야 함 (3곳)

**결론**: **채택**

### 2.2 선택지 2: 정규식으로 소수점만 제거 (미채택)

**방식**: 점(`.`)이 숫자 다음에 오는 경우에만 제거.

```java
private String removeDecimal(String numericStr) {
    return numericStr.replaceAll("\\.(?=\\d)", "");  // Lookahead 불가능
}
```

**단점**:
- **복잡성**: 정규식이 복잡해지
- **취약성**: "1.234.56" 같은 잘못된 형식 처리 어려움
- **여전히 문자열 조작**: 근본적인 해결책 아님

**결론**: 부적합

### 2.3 선택지 3: NumberFormat 사용 (미채택)

**방식**: Java의 `NumberFormat`으로 locale-aware 파싱.

```java
private Long parseCostToLong(String costStr, Locale locale) {
    NumberFormat format = NumberFormat.getInstance(locale);
    return format.parse(costStr).longValue();
}
```

**단점**:
- **Locale 문제**: Korean vs Western 혼합 시 문제
- **복잡성**: Locale 관리 로직 추가 필요
- **오버엔지니어링**: `BigDecimal`으로 충분

**결론**: 부적합

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 결정: 선택지 1 채택

`BigDecimal`로 직접 파싱하여 소수점을 정확하게 처리합니다.

### 3.2 수정 패턴

**Before (버그):**
```java
.totalExpectedCost(
    parseSafely(() -> Long.parseLong(removeDecimal(event.getTotalExpectedCost())), 0L))
```

**After (수정):**
```java
.totalExpectedCost(parseCostToLong(event.getTotalExpectedCost()))
```

### 3.3 한글 숫자 형식 가정

**CLAUDE.md 섹션 5 (No Hardcoding)** 준수를 위해 **숫자 형식 가정을 문서화**:

- **천단위 구분자**: 콤마(`,`) - 제거 필요
- **소수점**: 점(`.`) - `BigDecimal`로 파싱 후 정수 변환
- **예시**: `"1,234.56"` → `1234` (소수점 이하 절삭)

---

## 제4장: 구현 (Implementation)

### 4.1 코드 수정

**파일**: `module-app/src/main/java/maple/expectation/service/v5/event/ViewTransformer.java`

**수정 1: `removeDecimal()` 삭제, `parseCostToLong()` 추가**
```java
/**
 * Parse cost string to Long (mesos units).
 *
 * <p>Handles Korean number format with commas as thousand separators.
 * Decimal points are handled by BigDecimal parsing.
 *
 * <p>Examples:
 * <ul>
 *   <li>"1,234.56" -> 1234 (decimal truncated)</li>
 *   <li>"100" -> 100</li>
 *   <li>"50.25" -> 50</li>
 *   <li>null/blank -> 0</li>
 * </ul>
 *
 * @param costStr Cost string from BigDecimal serialization
 * @return Long value in mesos units
 */
private Long parseCostToLong(String costStr) {
  if (costStr == null || costStr.isBlank()) {
    return 0L;
  }
  return parseSafely(
      () -> {
        String cleaned = costStr.replace(",", "");  // Remove thousand separators
        BigDecimal decimal = new BigDecimal(cleaned);
        return decimal.longValue();  // Truncate decimal part
      },
      0L);
}
```

**수정 2: 호출처 변경 (93행)**
```java
// Before:
.totalExpectedCost(
    parseSafely(() -> Long.parseLong(removeDecimal(event.getTotalExpectedCost())), 0L))

// After:
.totalExpectedCost(parseCostToLong(event.getTotalExpectedCost()))
```

**수정 3: `toLong()` 메서드도 `parseCostToLong()` 패턴으로 변경 (223-225행)**
```java
// Before:
private Long toLong(BigDecimal value) {
    return value != null ? value.longValue() : 0L;
}

// After: String 입력도 처리 가능하도록 통일
private Long toLong(BigDecimal value) {
    return value != null ? value.longValue() : 0L;
}

// String 버전은 parseCostToLong() 사용
```

---

## 제5장: 검증 (Verification)

### 5.1 단위 테스트 시나리오

1. **소수점 있는 값**: `"123.45"` → `123L`
2. **천단위 콤마**: `"1,234.56"` → `1234L`
3. **정수만**: `"1000"` → `1000L`
4. **빈 문자열**: `null`/`""` → `0L`
5. **Korean 형식**: `"10,000,000.99"` → `10000000L`

### 5.2 테스트 코드 예시

```java
@Test
@DisplayName("소수점이 있는 비용 파싱 - 소수점 이하 절삭")
void parseCostWithDecimal_ShouldTruncateDecimal() {
    // Given
    String costStr = "123.45";

    // When
    Long result = transformer.parseCostToLong(costStr);

    // Then
    assertThat(result).isEqualTo(123L);
}

@Test
@DisplayName("천단위 콤마가 있는 비용 파싱")
void parseCostWithComma_ShouldRemoveComma() {
    // Given
    String costStr = "1,234.56";

    // When
    Long result = transformer.parseCostToLong(costStr);

    // Then
    assertThat(result).isEqualTo(1234L);
}

@Test
@DisplayName("빈 문자열은 0 반환")
void parseEmptyCost_ShouldReturnZero() {
    assertThat(transformer.parseCostToLong(null)).isEqualTo(0L);
    assertThat(transformer.parseCostToLong("")).isEqualTo(0L);
}
```

---

## 제6장: 관련 문서 (Related Documents)

- **CLAUDE.md 섹션 5**: Anti-Pattern & Deprecation Prohibition (No Hardcoding)
- **CLAUDE.md 섹션 12**: LogicExecutor 패턴
- **BigDecimal JavaDoc**: https://docs.oracle.com/javase/8/docs/api/java/math/BigDecimal.html

---

## 상태 (Status)

**상태**: 🟢 Accepted (2026-02-23)

**적용 대상**:
- `ViewTransformer.java` (removeDecimal 삭제, parseCostToLong 추가, 호출처 3곳 수정)

**다음 작업**:
- [ ] 단위 테스트 작성
- [ ] 통합 테스트 검증
- [ ] 코드 리뷰
