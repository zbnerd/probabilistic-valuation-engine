# IntelliJ Kotlin 변환 가이드

> **Branch**: `v2/postgresql-redesign`
> **Related Issue**: #548

## Overview

이 문서는 Java 파일을 Kotlin으로 변환할 때 사용하는 IntelliJ IDEA 설정과 절차를 안내합니다.

---

## 사전 준비

### 1. IntelliJ IDEA 플러그인 확인

```
Settings (Ctrl+Alt+S) → Plugins → Installed
- Kotlin (필수)
- Spring (권장)
```

### 2. Kotlin 컴파일러 설정

```
Settings → Build, Execution, Deployment → Compiler → Kotlin Compiler
- Target JVM version: 21
- Language version: 2.1
- API version: 2.1
```

### 3. 코드 스타일 설정

```
Settings → Editor → Code Style → Kotlin
- "Set from..." → Kotlin style guide
```

---

## Java → Kotlin 변환 절차

### 1단계: 파일 변환

1. Java 파일 열기
2. `Code → Convert Java File to Kotlin File` (Ctrl+Alt+Shift+K)
3. 자동 변환 결과 검토

### 2단계: 변환 후 체크리스트

#### 필수 확인 사항

- [ ] **Null Safety**: `!!` 연산자 제거 → `?.let`, `?:` 등으로 대체
- [ ] **Data Class**: POJO → `data class` 변환 검토
- [ ] **Companion Object**: `static` 메서드 → `companion object` 이동
- [ ] **Property Access**: Getter/Setter → 프로퍼티 직접 접근
- [ ] **String Template**: 문자열 연결 → `"${variable}"` 사용
- [ ] **When Expression**: 복잡한 if-else → `when` 변환

#### JPA Entity 특별 사항

```kotlin
// ❌ 변환 직후 (문제 있음)
class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null  // nullable

    var name: String? = null  // 문제
}

// ✅ 권장 형태
@Entity
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String  // non-null
)
```

### 3단계: 컴파일 및 테스트

```bash
# 컴파일 확인
./gradlew compileKotlin

# 코드 스타일 검사
./gradlew spotlessCheck

# 자동 수정
./gradlew spotlessApply

# 테스트 실행
./gradlew test --tests "*[변환한클래스명]*"
```

---

## 자주 발생하는 문제 해결

### 1. Lombok 어노테이션 제거

```kotlin
// ❌ Lombok 사용 (Kotlin에서 미작동)
@Getter @Setter
class User {
    private String name
}

// ✅ Kotlin 기본 기능 사용
class User(
    var name: String  // 자동으로 getter/setter 생성
)
```

### 2. Builder 패턴 → Named Arguments

```kotlin
// ❌ Builder 패턴 (Java 스타일)
User.builder()
    .name("test")
    .email("test@example.com")
    .build()

// ✅ Named Arguments (Kotlin 스타일)
User(
    name = "test",
    email = "test@example.com"
)
```

### 3. Optional → Nullable

```kotlin
// ❌ Java Optional
Optional.ofNullable(user).map(User::getName).orElse("default")

// ✅ Kotlin Nullable
user?.name ?: "default"
```

### 4. Stream → Collection Operators

```kotlin
// ❌ Java Stream
list.stream()
    .filter { it.isActive }
    .map { it.name }
    .collect(Collectors.toList())

// ✅ Kotlin Collection Operators
list.filter { it.isActive }
    .map { it.name }
```

---

## Spring Boot 특별 고려사항

### 1. 생성자 주입

```kotlin
// ❌ 필드 주입
@Service
class UserService {
    @Autowired
    private lateinit var repository: UserRepository
}

// ✅ 생성자 주입 (권장)
@Service
class UserService(
    private val repository: UserRepository
) {
    // ...
}
```

### 2. @Value → Constructor Parameter

```kotlin
// ❌ 필드 @Value
@Component
class MyService {
    @Value("\${app.timeout}")
    private lateinit var timeout: String
}

// ✅ 생성자 주입
@Component
class MyService(
    @Value("\${app.timeout}") private val timeout: String
)
```

### 3. Configuration Properties

```kotlin
// ✅ Kotlin Data Class
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val timeout: Duration = Duration.ofSeconds(30),
    val maxRetries: Int = 3
)
```

---

## 변환 우선순위

### Phase 1: Domain Layer
- Entity 클래스
- Domain Service
- Repository Interface

### Phase 2: Application Layer
- Service 클래스
- DTO/Request/Response

### Phase 3: Infrastructure Layer
- Adapter/Repository 구현체
- Config 클래스

### Phase 4: Presentation Layer
- Controller
- Filter/Interceptor

---

## 검증 체크리스트

각 파일 변환 완료 후:

- [ ] `./gradlew compileKotlin` 통과
- [ ] `./gradlew spotlessCheck` 통과
- [ ] 해당 파일 관련 테스트 통과
- [ ] ArchUnit 아키텍처 테스트 통과
- [ ] PR 리뷰 완료

---

## 참고 자료

- [Kotlin 공식 문서 - Java to Kotlin Migration](https://kotlinlang.org/docs/mixing-java-kotlin-intellij.html)
- [Spring 공식 문서 - Kotlin](https://docs.spring.io/spring-framework/reference/languages/kotlin.html)
- [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
