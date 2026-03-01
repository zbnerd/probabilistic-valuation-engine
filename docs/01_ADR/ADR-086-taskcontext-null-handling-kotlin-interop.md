# ADR-086: TaskContext.of P1 Null 처리로 Kotlin 마이그레이션 호환성 수정

## 제1장: 문제의 발견 (Problem)

### 1.1 P1 - Kotlin 마이그레이션 후 Java 호출 시 Null 파라미터 실패

`TaskContext`가 Java에서 Kotlin으로 마이그레이션된 후, **Java 코드에서 `null`을 `dynamicValue`로 전달할 수 없는 호환성 문제**가 발생했습니다.

**문제 상황:**

**Java 구현 (이전):**
```java
public class TaskContext {
    public static TaskContext of(String component, String operation, String dynamicValue) {
        // dynamicValue가 null이어도 됨
        return new TaskContext(component, operation, dynamicValue != null ? dynamicValue : "");
    }
}
```

**Kotlin 구현 (마이그레이션 후, 버그):**
```kotlin
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String = ""  // Non-null default, but @JvmStatic은 null 허용 안 함
) {
    @JvmStatic
    fun of(component: String, operation: String, dynamicValue: String?): TaskContext =
        TaskContext(component, operation, dynamicValue ?: "")  // 컴파일 오류!
}
```

**에러 메시지:**
```
Null can not be a value of a non-null type String
```

### 1.2 근본 원본: Kotlin Non-null 타입 시스템

Kotlin의 `String` 타입은 기본적으로 non-null이며, `@JvmStatic` 메서드에서 Java의 `null`을 받으려면 **명시적으로 nullable 타입(`String?`)**으로 선언해야 합니다.

**문제 패턴:**
```kotlin
// 생성자 파라미터는 non-null
data class TaskContext(
    val dynamicValue: String = ""  // Non-null
)

// 하지만 Java에서는 null을 전달하고 싶음
TaskContext.of("A", "B", null);  // Kotlin에서는 String? 필요
```

### 1.3 영향도 분석

- **심각도**: P1 (Java-Kotlin Interop)
- **영향 범위**: `TaskContext.of()`를 호출하는 모든 Java 코드
- **증상**: Java 코드에서 `null`을 `dynamicValue`로 전달 시 런타임/컴파일 오류

---

## 제2장: 선택지 탐색 (Options)

### 2.1 선택지 1: Kotlin 타입을 Nullable로 변경하고 내부에서 정규화 (채택)

**방식**: 생성자 파라미터를 nullable(`String?`)로 받고, 내부적으로 빈 문자열로 정규화합니다.

```kotlin
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String? = null  // Nullable for Java interop
) {
    // Null을 빈 문자열로 정규화
    private val normalizedDynamicValue: String = dynamicValue ?: ""

    init {
        require(component.isNotBlank()) { "component must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
    }

    // 외부에서는 정규화된 값 사용
    fun dynamicValue(): String = normalizedDynamicValue
    fun toTaskName(): String = if (normalizedDynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$normalizedDynamicValue"
    }
}
```

**장점**:
- **Java 호환성**: Java 코드에서 `null` 전달 가능
- **내부 일관성**: 내부적으로는 항상 non-null String 사용
- **명시적 정규화**: `normalizedDynamicValue`로 null 처리가 명확히 드러남

**단점**:
- **이중 필드**: `dynamicValue` (nullable) + `normalizedDynamicValue` (non-null)
- **메서드 필요**: `dynamicValue()` 메서드로 접근 (프로퍼티 대신)

**결론**: **채택**

### 2.2 선택지 2: 자바 오버로딩 메서드 추가 (미채택)

**방식**: Kotlin의 @JvmStatic 대신 Java 오버로딩을 사용.

```kotlin
companion object {
    @JvmStatic
    fun of(component: String, operation: String): TaskContext =
        TaskContext(component, operation, null)

    @JvmStatic
    fun of(component: String, operation: String, dynamicValue: String): TaskContext =
        TaskContext(component, operation, dynamicValue)
}
```

**단점**:
- **여전히 문제**: 3-parameter 버전은 여전히 non-null만 받음
- **API 혼란**: 오버로딩으로 인한 호출 모호성

**결론**: 부적합

### 2.3 선택지 3: @JvmOver 사용 (미채택)

**방식**: Kotlin의 `@JvmOverloads`로 자동 오버로딩 생성.

```kotlin
@JvmOverloads
@JvmStatic
fun of(component: String, operation: String, dynamicValue: String? = null): TaskContext =
    TaskContext(component, operation, dynamicValue)
```

**단점**:
- **동일한 문제**: 여전히 nullable 타입으로 선언해야 함
- **불필요한 오버로딩**: 2-parameter, 3-parameter 버전 모두 생성

**결론**: 부적합

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 결정: 선택지 1 채택

**생성자 파라미터를 nullable로 변경하고 내부 정규화**하여 Java-Kotlin 호환성을 확보합니다.

### 3.2 수정 패턴

**Before (버그):**
```kotlin
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String = ""  // Non-null
) {
    fun toTaskName(): String = if (dynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$dynamicValue"
    }
}
```

**After (수정):**
```kotlin
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String? = null  // Nullable for Java interop
) {
    private val normalizedDynamicValue: String = dynamicValue ?: ""

    fun toTaskName(): String = if (normalizedDynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$normalizedDynamicValue"
    }
}
```

### 3.3 호환성 보장

- **Java 호출**: `TaskContext.of("A", "B", null)` → 정상 작동
- **Kotlin 호출**: `TaskContext("A", "B", null)` → 정상 작동
- **내부 사용**: 항상 `normalizedDynamicValue` 사용 (null-safe)

---

## 제4장: 구현 (Implementation)

### 4.1 코드 수정

**파일**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/TaskContext.kt`

**수정된 전체 코드:**
```kotlin
data class TaskContext(
    val component: String,
    val operation: String,
    val dynamicValue: String? = null  // ADR-086: Nullable for Java interop
) {
    // ADR-086: Normalize null to empty string for internal use
    private val normalizedDynamicValue: String = dynamicValue ?: ""

    init {
        require(component.isNotBlank()) { "component must not be blank" }
        require(operation.isNotBlank()) { "operation must not be blank" }
    }

    fun component(): String = component
    fun operation(): String = operation
    fun dynamicValue(): String = normalizedDynamicValue

    fun toTaskName(): String = if (normalizedDynamicValue.isEmpty()) {
        "$component:$operation"
    } else {
        "$component:$operation:$normalizedDynamicValue"
    }

    companion object {
        @JvmStatic
        fun of(component: String, operation: String): TaskContext =
            TaskContext(component, operation, null)

        @JvmStatic
        fun of(component: String, operation: String, dynamicValue: String?): TaskContext =
            TaskContext(component, operation, dynamicValue)
    }
}
```

---

## 제5장: 검증 (Verification)

### 5.1 단위 테스트 시나리오

1. **2-parameter 호출**: `TaskContext.of("A", "B")` → dynamicValue는 ""
2. **3-parameter null 호출**: `TaskContext.of("A", "B", null)` → dynamicValue는 ""
3. **3-parameter 값 호출**: `TaskContext.of("A", "B", "value")` → dynamicValue는 "value"
4. **toTaskName()**: null/empty인 경우 "component:operation" 형식

### 5.2 테스트 코드

```java
@Test
@DisplayName("TaskContext.of() with null dynamicValue should normalize to empty string")
void testTaskContext_OfWithNullDynamicValue() {
    // Given
    TaskContext context = TaskContext.of("Component", "operation", null);

    // Then
    assertThat(context.dynamicValue()).isEmpty();
    assertThat(context.toTaskName()).isEqualTo("Component:operation");
}

@Test
@DisplayName("TaskContext.of() with 2 parameters should normalize null to empty string")
void testTaskContext_OfWithoutDynamicValue() {
    // Given
    TaskContext context = TaskContext.of("Component", "operation");

    // Then
    assertThat(context.dynamicValue()).isEmpty();
    assertThat(context.toTaskName()).isEqualTo("Component:operation");
}
```

---

## 제6장: 관련 문서 (Related Documents)

- **Kotlin Java Interop**: https://kotlinlang.org/docs/java-interop.html#nullability-annotations
- **CLAUDE.md 섹션 12**: LogicExecutor 패턴

---

## 상태 (Status)

**상태**: 🟢 Accepted (2026-02-23)

**적용 대상**:
- `TaskContext.kt` (nullable dynamicValue, 정규화 로직)
- `LogicExecutorTest.java` (null 전달 테스트 추가)

**다음 작업**:
- [x] 코드 수정 (완료)
- [ ] 단위 테스트 추가
- [ ] 코드 리뷰
