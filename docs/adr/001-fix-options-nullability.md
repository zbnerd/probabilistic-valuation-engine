# ADR-001: CubeCalculationInput options Nullability 수정

## 상태
Accepted (2026-02-27)

## 컨텍스트
CI 빌드가 `CubeServiceTest.given_cooldownOptions_when_calculateExpectedTrials_shouldReturnPositiveValue` 테스트에서 실패했다.

**에러:**
```
java.lang.NullPointerException: Parameter specified as non-null is null:
method kotlin.text.StringsKt__StringsKt.trim, parameter <this>
```

**원인:**
- `CubeCalculationInput.options: MutableList<String>` 타입이 non-nullable 요소만 허용
- Java 테스트 코드에서 `Arrays.asList(null, "옵션1", "옵션2")`로 null을 추가
- Kotlin의 `isReady()` 메서드에서 `opt.trim()` 호출 시 NPE 발생

## 결정
`options` 타입을 `MutableList<String>`에서 `MutableList<String?>`로 변경하여 null 요소를 허용한다.

**변경 대상:**
- `CubeCalculationInput.options` 필드
- `CubeCalculationInputBuilder.options` 필드
- `CubeCalculationInputBuilder.options()` 메서드

## 결과
- Java 코드에서 null을 포함한 리스트를 안전하게 전달 가능
- `isReady()` 메서드의 null 체크 로직이 정상 동작
- 테스트 통과

## 관련 이슈
- CI 실패 수정 (develop 브랜치)
