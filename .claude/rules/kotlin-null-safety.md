---
paths:
  - "**/*.kt"
  - "**/*.java"
---

# Kotlin/Java Null-Safety 가드레일

## 금지 패턴

- **`!!` 연산자 금지**: `require()` + descriptive message 또는 `?: throw` 사용
- **`.orElse(null)` 금지**: Optional 반환 또는 `.orElseThrow()` / `.orElseGet()` 사용
- **`.isPresent() + .get()` 금지**: `.map()` / `.flatMap()` / `.ifPresent()` / `.orElseThrow()` 사용
- **unchecked `as` 캐스팅 금지**: sealed interface 또는 safe cast 유틸리티 사용
- **Kotlin Value Object를 Java에서 참조 시**: `@Nullable` annotation 또는 Optional 사용

## 필수 패턴

- Converter/DTO의 모든 optional 필드에 명시적 기본값 제공
- DB schema에서 nullable인 컬럼은 entity field에 `@Nullable` 또는 Kotlin nullable type 반영
- null 가능 lookup은 항상 `Optional` 체이닝으로 처리

## 참조 이슈

#629 (unsafe !!), #630 (unsafe as), #631 (.orElse(null)), #637 (.isPresent+.get()), #638 (Java-Kotlin interop NPE), #643 (JWT claims NPE), #657 (Builder !! vs require)
