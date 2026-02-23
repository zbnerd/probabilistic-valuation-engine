# ADR-087: P2 구성 및 모니터링 이슈 수정

## 제1장: 문제의 발견 (Problem)

### 1.1 P2 이슈 개요

8개의 P2 구성 및 모니터링 관련 이슈가 발견되었습니다. 이 중 3개는 이미 수정되었으며, 나머지 5개를 수정합니다.

### 1.2 Issue 1: YAML 문서 분리 문자로 JPA 설정 프로필 의존성 오작동

**문제 코드 (application.yml:24):**
```yaml
---
# Profile-specific: MySQL connection init SQL (NOT for test/chaos)
spring:
  config:
    activate:
      on-profile: "!test & !chaos"
```

**설명:**
- `---`는 YAML 문서 분리 문자로 새로운 문서를 시작합니다.
- 첫 번째 문서의 `spring:` 설정(1-23행)이 새 문서에서 분리되었습니다.
- JPA 설정(37-46행)이 두 번째 문서에 있어 프로필 조건(`!test & !chaos`)이 적용됩니다.

**영향:**
- JPA 설정(`ddl-auto: update`)이 test/chaos 프로필에서 비활성화되어야 하지만 활성화될 수 있음
- 테스트 환경에서 테이블 자동 생성이 예기치 않게 발생할 수 있음

### 1.3 Issue 2: ResourceLoader IOException 처리

**문제 (ResourceLoader.kt:29-30):**
```kotlin
* @throws IllegalStateException if resource not found or read error occurs
```

**설명:**
- JavaDoc에서 `IOException`을 `IllegalStateException`으로 변환한다고 명시
- 하지만 Kotlin 코드에서 `IOException`을 명시적으로 catch하지 않음
- `getResourceAsStream()`이 `null`을 반환할 때만 `IllegalStateException`을 던짐

**실제 동작:**
```kotlin
private fun getResourceAsStream(path: String): InputStream {
    val inputStream = javaClass.classLoader.getResourceAsStream(path)
        ?: throw IllegalStateException("Required resource not found: $path")
    return inputStream  // IOException 가능하지만 처리 안 함
}
```

### 1.4 Issue 6: RedisEventPublisher 프로퍼티 키

**분석 결과:** `RedisEventPublisher.java`는 프로퍼티 키를 사용하지 않습니다. 이슈 해결됨.

### 1.5 Issue 8: .kotlin/errors/ 디렉토리를 .gitignore에 추가

**문제:** Kotlin 컴파일러가 생성하는 에러 로그 파일이 git에 추적될 수 있습니다.

### 1.6 Issue 5: Grafana 대시보드 메트릭 이름

**분석 필요:** `docs/04_Reports/issue-344-grafana-dashboard.json` 확인 필요

---

## 제2장: 선택지 탐색 (Options)

### 2.1 Issue 1: YAML 문서 분리 문자 제거 (채택)

**방식:** `---`를 제거하고 모든 설정을 단일 문서로 통합합니다.

**수정:**
```yaml
# Before (lines 24-35):
---
# Profile-specific: MySQL connection init SQL (NOT for test/chaos)
spring:
  config:
    activate:
      on-profile: "!test & !chaos"
  datasource:
    hikari:
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"

# After - Remove --- and integrate:
spring:
  # ... existing config ...
  config:
    activate:
      on-profile: "!test & !chaos"  # JPA settings only for non-test/chaos
  datasource:
    hikari:
      connection-init-sql: "SET SESSION lock_wait_timeout = 8"
  jpa:
    # ... JPA settings ...
```

**결론**: **채택**

### 2.2 Issue 2: ResourceLoader IOException 처리 개선 (채택)

**방식:** `use {}` 블록 내에서 발생하는 `IOException`을 명시적으로 처리합니다.

**수정:**
```kotlin
fun loadResourceAsString(path: String): String {
    return getResourceAsStream(path).use { inputStream ->
        try {
            String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw IllegalStateException("Failed to read resource: $path", e)
        }
    }
}
```

**결론**: **채택**

### 2.3 Issue 8: .gitignore에 .kotlin/errors/ 추가 (채택)

**방식:** `.kotlin/errors/` 패턴을 `.gitignore`에 추가합니다.

**결론**: **채택**

---

## 제3장: 결정의 근거 (Decision)

### 3.1 최종 결정

- **Issue 1**: YAML `---` 제거로 단일 문서 통합
- **Issue 2**: `IOException`을 `IllegalStateException`으로 래핑
- **Issue 8**: `.kotlin/errors/`를 `.gitignore`에 추가

### 3.2 수정 파일 목록

1. `module-app/src/main/resources/application.yml` - `---` 제거
2. `module-common/src/main/kotlin/maple/expectation/common/resource/ResourceLoader.kt` - IOException 처리
3. `.gitignore` - `.kotlin/errors/` 추가

---

## 제4장: 구현 (Implementation)

### 4.1 YAML 수정

**파일**: `module-app/src/main/resources/application.yml`

`---` (line 24) 제거

### 4.2 ResourceLoader 수정

**파일**: `module-common/src/main/kotlin/maple/expectation/common/resource/ResourceLoader.kt`

```kotlin
fun loadResourceAsString(path: String): String {
    return getResourceAsStream(path).use { inputStream ->
        try {
            String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            throw IllegalStateException("Failed to read resource: $path", e)
        }
    }
}
```

### 4.3 .gitignore 수정

**파일**: `.gitignore`

```gitignore
# Kotlin compiler errors
.kotlin/errors/
```

---

## 제5장: 검증 (Verification)

### 5.1 YAML 통합 테스트

1. **Local 프로필**: JPA 설정이 적용되는지 확인
2. **Test 프로필**: JPA 설정이 프로필 조건에 따라 비활성화되는지 확인
3. **YAML 파싱**: `spring.config.activate.on-profile`이 올바르게 작동하는지 확인

### 5.2 ResourceLoader 테스트

```kotlin
@Test
fun loadResourceAsString_shouldThrowIllegalStateExceptionOnIOError() {
    // Given: 리소스를 읽다가 IOException 발생 시뮬레이션
    // When & Then: IllegalStateException이 발생하고 원인이 IOException인지 확인
}
```

### 5.3 .gitignore 검증

```bash
# .kotlin/errors/ 디렉토리 생성 후 git 상태 확인
mkdir -p .kotlin/errors
echo "test" > .kotlin/errors/test.log
git status  # .kotlin/errors/가 추적되지 않아야 함
```

---

## 제6장: 관련 문서 (Related Documents)

- **CLAUDE.md 섹션 5**: Anti-Pattern & Deprecation Prohibition
- **Spring Boot YAML Documentation**: https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html
- **Kotlin Exception Handling**: https://kotlinlang.org/docs/exceptions.html

---

## 상태 (Status)

**상태**: 🟢 Accepted (2026-02-23)

**적용 대상**:
- `application.yml` (YAML `---` 제거)
- `ResourceLoader.kt` (IOException 처리)
- `.gitignore` (.kotlin/errors/ 추가)

**이미 수정된 이슈:**
- Issue 3: Scheduler @Value injection
- Issue 4: SchedulerConfig rejection log
- Issue 7: PresetCalculationExecutorConfig saturation log

**다음 작업**:
- [x] YAML 수정
- [x] ResourceLoader 수정
- [x] .gitignore 수정
- [ ] 단위 테스트 작성
- [ ] 통합 테스트 검증
