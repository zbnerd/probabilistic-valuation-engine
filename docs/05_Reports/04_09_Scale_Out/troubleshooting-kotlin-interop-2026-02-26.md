# Kotlin-Java Interop 트러블슈팅 보고서

> **작성일**: 2026-02-26
> **작성자**: Claude (AI Assistant)
> **관련 이슈**: #383, #384 Architecture Refactoring
> **브랜치**: feature/architecture-refactoring-383-384

---

## 1. 개요

### 작업 목적
- Java → Kotlin 마이그레이션 및 모듈 구조 정리
- module-infra 컴파일 오류 해결
- CI 파이프라인 통과

### 대상 모듈
- `module-infra` (monitoring, queue, config 패키지)
- `module-core` (port/out 인터페이스)

### 주요 이슈
- Kotlin-Java Interop 타입 불일치
- 컬렉션 타입 충돌 (`java.util.List` ↔ `kotlin.collections.List`)
- 아키텍처 위반 (infra → app 역의존)

### 결과
✅ 컴파일 성공
✅ CI fast 테스트 통과 (38 tasks)
✅ 아키텍처 경계 준수

---

## 2. 발생 문제 상세

### 2.1 AlertNotificationService.kt - List 타입 불일치

**파일 위치**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/pipeline/AlertNotificationService.kt`

**증상**
```
e: AlertNotificationService.kt:103:51 Argument type mismatch:
   actual type is 'kotlin.collections.(Mutable)List<DiscordNotifier.AnnotatedSignal!>!',
   but 'java.util.List<DiscordNotifier.AnnotatedSignal>' was expected.
```

**원인**
- `DiscordNotifier.formatIncidentMessage()` 메서드가 `java.util.List` 파라미터 사용
- Kotlin의 `.toList()`는 `kotlin.collections.List` 반환
- Java Stream의 `Collectors.toList()`는 플랫폼 타입 `List<T!>!` 반환

**해결 전**
```kotlin
val annotatedSignals = context.anomalies.stream()
    .limit(3)
    .map { anomaly -> DiscordNotifier.AnnotatedSignal(...) }
    .collect(java.util.stream.Collectors.toList())  // ← 플랫폼 타입
```

**해결 후**
```kotlin
val annotatedSignals = context.anomalies.take(3).map { anomaly ->
    DiscordNotifier.AnnotatedSignal(
        signalMap[anomaly.signalId]!!,
        anomaly.currentValue
    )
}  // ← Kotlin 컬렉션 API 사용
```

---

### 2.2 RedisLikeRelationBufferAdapter.kt - Set 타입 불일치

**파일 위치**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/RedisLikeRelationBufferAdapter.kt`

**증상**
```
e: RedisLikeRelationBufferAdapter.kt:66:16 Return type mismatch:
   expected 'java.util.Set<kotlin.String>',
   actual 'kotlin.collections.MutableCollection<kotlin.String!>'.
```

**원인**
- 인터페이스 `LikeRelationBufferStrategy`가 `java.util.Set<String>` 반환 타입 사용
- 구현체에서 Kotlin `mutableSetOf()` 사용 시 타입 충돌

**해결 전**
```kotlin
override fun fetchAndRemovePending(limit: Int): Set<String> {
    val result: Set<String>? = executor.executeOrDefault(
        { ... java.util.HashSet(resultSet) },  // ← Java 타입 혼용
        java.util.HashSet<String>(),
        ...
    )
    return result ?: java.util.HashSet()
}
```

**해결 후**
```kotlin
override fun fetchAndRemovePending(limit: Int): Set<String> {
    return executor.executeOrDefault(
        {
            val resultSet = mutableSetOf<String>()
            // ... 로직 ...
            resultSet.toSet()  // ← Kotlin 불변 Set 반환
        },
        emptySet(),  // ← Kotlin 표준 함수
        TaskContext.of("RedisLikeRelationBuffer", "FetchAndRemovePending", limit.toString())
    ) ?: emptySet()
}
```

---

### 2.3 RedisLikeRelationBuffer.kt - 인터페이스 구현 타입 불일치

**파일 위치**: `module-infra/src/main/kotlin/maple/expectation/infrastructure/queue/like/RedisLikeRelationBuffer.kt`

**증상**
```
e: RedisLikeRelationBuffer.kt:92:53 Return type of 'fetchAndRemovePending' is not a subtype
   of the return type of the overridden member 'fun fetchAndRemovePending(limit: Int): Set<String>'
```

**원인**
- 메서드 시그니처에 `java.util.Set<String>` 명시
- 내부에서 `result.toSet() as java.util.Set<String>` 불필요한 캐스팅

**해결 전**
```kotlin
override fun fetchAndRemovePending(limit: Int): java.util.Set<String> {
    val result = executor.executeOrDefault(
        { doFetchAndRemovePending(limit) },
        Collections.emptySet(),  // ← Java Collections 사용
        ...
    )
    return if (result is java.util.Set<String>) result else result as java.util.Set<String>
}
```

**해결 후**
```kotlin
override fun fetchAndRemovePending(limit: Int): Set<String> {
    return executor.executeOrDefault(
        { doFetchAndRemovePending(limit) },
        emptySet(),  // ← Kotlin 표준 함수
        TaskContext.of("LikeRelation", "FetchPending")
    ) ?: emptySet()
}
```

---

### 2.4 인터페이스 레벨 java.util 타입 사용

**영향받은 파일**
- `module-core/src/main/kotlin/maple/expectation/core/port/out/LikeRelationBufferStrategy.kt`
- `module-infra/src/main/kotlin/maple/expectation/infrastructure/monitoring/copilot/notifier/DiscordNotifier.kt`

**문제**
```kotlin
// LikeRelationBufferStrategy.kt
import java.util.Set  // ← Java 타입 import

fun fetchAndRemovePending(limit: Int): Set<String>  // ← java.util.Set으로 해석

// DiscordNotifier.kt
import java.util.List  // ← Java 타입 import

fun formatIncidentMessage(
    signals: List<AnnotatedSignal>,  // ← java.util.List으로 해석
    ...
): String
```

**해결**
```kotlin
// import 제거
// import java.util.Set  ← 삭제
// import java.util.List ← 삭제

// Kotlin의 표준 컬렉션 타입으로 자동 해석
fun fetchAndRemovePending(limit: Int): Set<String>  // ← kotlin.collections.Set
fun formatIncidentMessage(signals: List<AnnotatedSignal>): String  // ← kotlin.collections.List
```

---

## 3. 원인 분석

### 핵심 원인 1: 플랫폼 타입(Platform Type) 혼용

Kotlin은 Java 타입을 `T!` (platform type)으로 인식하며, nullability 및 mutability 정보를 명확히 보장하지 않음.

```
Java: List<String>
        ↓
Kotlin: List<String!>! (platform type)
        ↓
문제: kotlin.collections.List<String>과 호환되지 않음
```

### 핵심 원인 2: 모듈 경계에서 Java 타입 노출

```
module-core (인터페이스)
    ↓ java.util.Set 사용
module-infra (구현체)
    ↓ Kotlin Set 사용
    ↓
타입 충돌 발생
```

### 핵심 원인 3: Java Stream API와 Kotlin 컬렉션 API 혼용

```kotlin
// 혼용 패턴 (안티패턴)
stream().map { }.collect(Collectors.toList())  // Java Stream
    + .toList()  // Kotlin 확장 함수
    = 타입 추론 실패
```

---

## 4. 해결 전략

### 전략 1: Kotlin 컬렉션 타입으로 전면 통일

| 변경 전 | 변경 후 |
|---------|---------|
| `import java.util.Set` | (import 제거) |
| `import java.util.List` | (import 제거) |
| `java.util.HashSet<String>()` | `mutableSetOf<String>()` / `hashSetOf<String>()` |
| `Collections.emptySet()` | `emptySet()` |
| `new java.util.ArrayList<>()` | `listOf()` / `mutableListOf()` |

### 전략 2: Java Stream API 제거

| Java Stream | Kotlin 컬렉션 |
|-------------|---------------|
| `stream().limit(n)` | `take(n)` |
| `stream().map { }` | `map { }` |
| `collect(Collectors.toList())` | `toList()` |
| `stream().filter { }` | `filter { }` |

### 전략 3: 불필요한 캐스팅 제거

```kotlin
// Before (안티패턴)
return result.toSet() as java.util.Set<String>

// After (권장)
return result.toSet()  // Kotlin의 Set<String> 반환
```

---

## 5. 개선 효과

| 항목 | 개선 전 | 개선 후 |
|------|---------|---------|
| 컴파일 상태 | 실패 (8개 오류) | 성공 |
| 타입 안정성 | 낮음 (플랫폼 타입 혼용) | 높음 (Kotlin 타입 통일) |
| 캐스팅 사용 | 다수 존재 | 제거 |
| Interop 리스크 | 높음 | 최소화 |
| 코드 가독성 | Java 스타일 혼재 | Kotlin 관용구 통일 |

---

## 6. CI 검증 결과

```bash
$ ./gradlew spotlessApply --no-daemon
BUILD SUCCESSFUL in 25s
13 actionable tasks: 13 executed

$ ./gradlew clean test -PfastTest --no-daemon --stacktrace
BUILD SUCCESSFUL in 2m 56s
38 actionable tasks: 38 executed
```

### 테스트 통과 항목
- ArchitectureTest (컨트롤러 thin 레이어 검증)
- JwtTokenProviderTest (보안 검증)
- AdminControllerUnitTest (@Valid 검증)
- 기타 fast 테스트 전체 통과

---

## 7. 수정된 파일 목록

### module-core
| 파일 | 변경 내용 |
|------|-----------|
| `LikeRelationBufferStrategy.kt` | `java.util.Set` import 제거 |

### module-infra
| 파일 | 변경 내용 |
|------|-----------|
| `AlertNotificationService.kt` | Java Stream → Kotlin 컬렉션 API 변경 |
| `DiscordNotifier.kt` | `java.util.List` import 제거 |
| `RedisLikeRelationBufferAdapter.kt` | `java.util.Set` → Kotlin `Set` 통일 |
| `RedisLikeRelationBuffer.kt` | 반환 타입 및 내부 구현 Kotlin화 |
| `AnomalyDetectionOrchestrator.kt` | `ZScoreConfig.builder()` → data class 생성자 |
| `AiSreService.kt` | nullable 타입 처리 (`?: ""`) |
| `AiResponseParser.kt` | 구문 오류 수정 |

---

## 8. 향후 권장 사항

### 8.1 코딩 컨벤션
1. **모듈 경계(core)에는 Kotlin 타입만 사용**
   - 인터페이스 시그니처에 `java.util.*` 타입 사용 금지
   - Port/Adapter 패턴에서 Kotlin 표준 타입 사용

2. **Java 타입은 어댑터 레이어에서만 허용**
   - Redis/JPA 등 외부 라이브러리 연동 시에만 Java 타입 노출 허용
   - 내부 변환 후 Kotlin 타입으로 래핑

3. **Kotlin 컬렉션 API 우선 사용**
   - `stream()` 대신 `map`, `filter`, `take` 등 사용
   - `Collectors.toList()` 대신 `toList()` 사용

### 8.2 아키텍처 가이드
1. **의존성 방향 준수**
   ```
   module-app → module-infra → module-core
   ```
   - 역방향 의존 금지 (infra → app)

2. **인터페이스 정의 위치**
   - 포트(Port) 인터페이스: `module-core/port/out/`
   - 구현체(Adapter): `module-infra/`

3. **Builder 패턴 대체 고려**
   - Kotlin data class + named arguments 사용
   - `@JvmStatic` + `@JvmOverloads`는 Java 호환성 필요 시에만

---

## 9. 참고 자료

- [Kotlin Java Interop](https://kotlinlang.org/docs/java-to-kotlin-interop.html)
- [Kotlin Collections](https://kotlinlang.org/docs/collections-overview.html)
- [CLAUDE.md - Section 4: Implementation Logic & SOLID](/CLAUDE.md)
- [docs/adr/ADR-036-monitoring-config-infra-migration.md](/docs/adr/ADR-036-monitoring-config-infra-migration.md)

---

## 10. 총평

이번 이슈는 "기능 오류"가 아니라 **Kotlin-Java Interop 설계 일관성 부족**에서 비롯된 **타입 체계 충돌 문제**였다.

### 문제의 본질
1. Java에서 Kotlin으로 마이그레이션 시 `java.util.*` 타입을 그대로 유지
2. Kotlin 코드에서 Java 타입과 Kotlin 타입 혼용
3. 플랫폼 타입(`T!`)으로 인한 타입 추론 실패

### 해결의 핵심
- **컬렉션 타입을 Kotlin으로 통일**함으로써 컴파일 안정성 확보
- **Java Stream API 제거**로 코드 일관성 확보
- **불필요한 캐스팅 제거**로 런타임 안정성 확보

### 교훈
> Kotlin 마이그레이션은 단순히 문법 변환이 아니라,
> **타입 체계와 아키텍처 경계를 함께 고려해야 하는 설계 작업**이다.

---

*이 보고서는 #383, #384 이슈 해결 과정에서 작성되었습니다.*
