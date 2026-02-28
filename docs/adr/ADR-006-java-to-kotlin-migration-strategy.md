# ADR-006: Java-to-Kotlin Migration Strategy

**Status**: Accepted
**Date**: 2026-02-28
**Related PRs**: #390, #391, #392, #395
**Related ADRs**: ADR-002 (Module Separation Kotlin)

---

## 1. 배경 (Background)

프로젝트 전반에 걸쳐 Java에서 Kotlin으로의 마이그레이션이 진행되고 있다. PR #390-396 기간 동안 대규모 Java-to-Kotlin 변환이 수행되었으나, 이에 대한 체계적인 전략이 문서화되어 있지 않았다. 일관된 변환 규칙과 접근 방식을 정립하여 향후 마이그레이션 작업의 효율성과 품질을 보장할 필요가 있다.

---

## 2. 문제 (Problem)

### 2.1 마이그레이션 과제

- Java와 Kotlin 코드 혼재로 인한 상호운용성 문제
- Lombok 어노테이션(`@Data`, `@Builder` 등)의 Kotlin 대체 방식 표준화 필요
- Nullable/NotNull 처리 방식의 일관성 부족
- 테스트 검증 전략의 부재

### 2.2 기술적 제약

- 기존 Java 코드와의 호환성 유지 필요
- 빌드 시스템(Gradle)의 Kotlin 지원 설정
- IDE 및 정적 분석 도구의 Kotlin 지원

---

## 3. 결정 (Decision)

### 3.1 Phase별 접근 전략

```
Phase 1: module-common (공통 유틸리티)
    ↓
Phase 2: module-core (도메인 로직)
    ↓
Phase 3: module-infra (인프라 구현)
    ↓
Phase 4: module-app (애플리케이션 서비스)
    ↓
Phase 5: module-web (웹 계층)
```

### 3.2 변환 규칙

| Java 패턴 | Kotlin 대체 | 비고 |
|-----------|-------------|------|
| `@Data` | `data class` | 자동 equals/hashCode/toString |
| `@Builder` | Named arguments / DSL | 빌더 패턴 불필요 |
| `@Getter/@Setter` | Property syntax | `var` / `val` |
| `@NoArgsConstructor` | 기본 생성자 | `constructor()` |
| `@AllArgsConstructor` | Primary constructor | 파라미터 직접 정의 |
| `@Slf4j` | `private val log = KotlinLogging.logger {}` | Kotlin-logging 사용 |
| `static` 메서드 | Companion object | `@JvmStatic` 선택적 사용 |
| `Optional<T>` | Nullable type (`T?`) | Elvis operator 활용 |

### 3.3 Java Interop 어노테이션

Java 코드에서 Kotlin 코드를 호출할 때 상호운용성을 보장하기 위한 어노테이션:

```kotlin
// 프로퍼티 접근자 이름 명시
@get:JvmName("getPropertyName")
val propertyName: String

// 정적 메서드로 노출
companion object {
    @JvmStatic
    fun staticMethod(): String = "static"
}

// 필드로 노출 (getter 없이)
@JvmField
val constantValue: Int = 42
```

### 3.4 Nullable 처리 전략

```kotlin
// Platform Type → 명시적 Nullable
// Java: String name (unknown nullability)
// Kotlin: val name: String? (explicit)

// Safe Call & Elvis
val length = name?.length ?: 0

// Require (Early Validation)
fun process(value: String) {
    require(value.isNotEmpty()) { "Value must not be empty" }
    // ...
}
```

### 3.5 테스트 전략

#### Golden Master Tests

```kotlin
// 기존 Java 동작을 캡처하여 Kotlin 구현 검증
class MigrationVerificationTest {
    @Test
    fun `verify kotlin implementation matches java behavior`() {
        // Given: Known inputs and expected outputs from Java
        val inputs = listOf(/* ... */)
        val expectedOutputs = listOf(/* ... */) // Java 실행 결과

        // When: Execute Kotlin implementation
        val actualOutputs = inputs.map { kotlinService.process(it) }

        // Then: Results must match
        assertEquals(expectedOutputs, actualOutputs)
    }
}
```

#### 컴파일/런타임 검증 체크리스트

- [ ] `./gradlew clean build` 성공
- [ ] 모든 기존 테스트 통과
- [ ] 새로운 Kotlin 테스트 추가
- [ ] IDE에서 Nullable 경고 없음
- [ ] Detekt/Ktlint 정적 분석 통과

---

## 4. 결과 (Consequences)

### 4.1 긍정적 효과

- **코드 간결성**: 보일러플레이트 코드 30-50% 감소
- **Null 안전성**: 컴파일 타임에 NullPointerException 방지
- **표현력**: DSL 및 연산자 오버로딩으로 도메인 표현 개선
- **상호운용성**: `@JvmName`, `@JvmStatic` 등으로 Java와 완벽 호환

### 4.2 주의사항

- **학습 곡선**: Kotlin idioms 학습 필요
- **빌드 시간**: Kotlin 컴파일러로 인한 빌드 시간 증가 가능
- **디버깅**: Java와 Kotlin 혼재 시 디버깅 복잡도 증가

### 4.3 마이그레이션 완료 기준

1. 모든 단위 테스트 통과
2. 통합 테스트 통과
3. 코드 리뷰 승인
4. ArchUnit 아키텍처 검증 통과
5. 문서 업데이트 (CLAUDE.md, ADR)

---

## 5. 이력 (History)

| 날짜 | 변경 내용 | 작성자 |
|------|----------|--------|
| 2026-02-28 | 초안 작성 | Claude (Architect) |
| 2026-02-28 | PR #390-396 분석 기반 전략 수립 | Claude Team |

---

## 6. 참조 (References)

- [ADR-002: Module Separation Kotlin](002-module-separation-kotlin.md)
- [Kotlin Java Interop Guide](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- PR #390: Java-to-Kotlin Phase 1
- PR #391: Java-to-Kotlin Phase 2
- PR #392: Java-to-Kotlin Phase 3
- PR #395: Kotlin 호환성 구축
