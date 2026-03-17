# App Usecase Test Template

이 문서는 module-app의 테스트 템플릿 사용법을 설명합니다.

## 템플릿 종류

### 1. UsecaseTestTemplate
**위치**: `maple.expectation.test.usecase.UsecaseTestTemplate`

**용도**: Application Service/Facade 테스트

**특징**:
- WebEnvironment.NONE (서버 없이 테스트)
- DB 격리 (IntegrationTestBase 상속)
- 외부 의존성 Mockito로 격리
- 비동기 테스트 Awaitility 지원
- @Transactional 지원 안 함 (실제 커밋 발생)

**사용 시나리오**:
- Facade/Usecase 클래스 테스트
- 여러 Port를 조합한 로직 검증
- 비동기 작업 테스트
- 외부 API 연동 로직 테스트 (Mock 사용)

### 2. ServiceTestTemplate
**위치**: `maple.expectation.test.service.ServiceTestTemplate`

**용도**: Domain Service 테스트

**특징**:
- WebEnvironment.NONE (서버 없이 테스트)
- DB 격리 (IntegrationTestBase 상속)
- @Transactional 지원 (롤백 가능)
- flushAndClear()로 영속성 컨텍스트 제어
- JPA 영속성 로직 검증에 최적화

**사용 시나리오**:
- Domain Service 단위 테스트
- JPA 영속성 로직 검증
- @Transactional 동작 확인
- 영속성 컨텍스트 제어가 필요한 테스트

## 선택 가이드

| 상황 | 사용할 템플릿 | 이유 |
|------|-------------|------|
| Facade/Application Service 테스트 | UsecaseTestTemplate | Port 조합, 외부 연동 |
| Domain Service 로직 테스트 | ServiceTestTemplate | @Transactional, JPA 제어 |
| 비동기 작업 테스트 | UsecaseTestTemplate | Awaitility 지원 |
| 영속성 컨텍스트 제어 필요 | ServiceTestTemplate | flushAndClear() 제공 |
| 실제 DB 커밋 필요 | UsecaseTestTemplate | @Transactional 미사용 |

## 사용 예시

### UsecaseTestTemplate 예시

```kotlin
package maple.expectation.app.facade

import maple.expectation.test.usecase.UsecaseTestTemplate
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import java.util.concurrent.CompletableFuture

class ExpectationFacadeTest : UsecaseTestTemplate() {

    @Autowired
    lateinit var expectationFacade: ExpectationFacade

    @MockBean
    lateinit var externalApiPort: ExternalApiPort

    @Test
    fun `기대값 비동기 계산 성공`() {
        // Given
        val ign = "testCharacter"
        val mockData = CharacterData(ign = ign, level = 250)
        given(externalApiPort.fetchData(ign)).willReturn(mockData)

        // When
        val future: CompletableFuture<ExpectationResult> =
            expectationFacade.calculateAsync(ign)

        // Then - Awaitility로 비동기 완료 대기
        awaitCompletion {
            assertThat(future).isCompleted
            val result = future.get()
            assertThat(result.expectationValue).isGreaterThan(0)
        }
    }

    @Test
    fun `조건이 만족될 때까지 대기`() {
        // Given
        val ign = "testCharacter"

        // When
        expectationFacade.scheduleCalculation(ign)

        // Then - 5초 내에 계산 완료 검증
        awaitUntil(timeout = Duration.ofSeconds(5)) {
            expectationFacade.isCalculationComplete(ign)
        }
    }
}
```

### ServiceTestTemplate 예시

```kotlin
package maple.expectation.domain.service

import maple.expectation.test.service.ServiceTestTemplate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Transactional
class CalculationServiceTest : ServiceTestTemplate() {

    @Autowired
    lateinit var calculationService: CalculationService

    @Autowired
    lateinit var characterRepository: CharacterRepository

    @Test
    fun `캐릭터 기대값 계산 후 저장`() {
        // Given
        val character = Character(ign = "test", level = 250)
        characterRepository.save(character)

        // When
        calculationService.calculateExpectation(character.id)

        // Then - DB에 실제로 반영됨을 확인
        flushAndClear()
        val saved = characterRepository.findById(character.id)
        assertThat(saved).isPresent
        assertThat(saved.get().expectationValue).isGreaterThan(0)
    }

    @Test
    fun `엔티티 영속 상태 검증`() {
        // Given
        val character = Character(ign = "test", level = 250)

        // When
        persistAndFlush(character)

        // Then
        assertPersistent(character)
        assertThat(character.id).isNotNull()
    }

    @Test
    fun `DB에서 실제 조회 확인`() {
        // Given
        val character = persistFlushAndClear(
            Character(ign = "test", level = 250)
        )

        // When
        val found = findFromDb(Character::class.java, character.id)

        // Then
        assertThat(found).isNotNull
        assertThat(found).isNotSameAs(character) // detached
    }
}
```

## Mockito 사용 패턴

### @MockBean vs @MockKBean

**@MockBean 사용 권장** (Spring 지원):
```kotlin
@MockBean
lateinit var externalApiPort: ExternalApiPort

@Test
fun test() {
    // BDD 스타일
    given(externalApiPort.fetchData("ign")).willReturn(mockData)

    // When
    val result = facade.process("ign")

    // Then
    then(externalApiPort).should().fetchData("ign")
    assertThat(result).isNotNull
}
```

### 주의사항

1. **@MockBean 과도한 사용 지양**: Context 캐싱 방해
2. **Port 인터페이스만 Mock**: Adapter는 실제 구현체 사용
3. **Given-When-Then 패턴 사용**: 테스트 가독성 향상

## Awaitility 패턴

### 조건 대기
```kotlin
awaitUntil(timeout = Duration.ofSeconds(5)) {
    someCondition()
}
```

### 비동기 완료 대기
```kotlin
awaitCompletion(timeout = Duration.ofSeconds(10)) {
    assertThat(future.get()).isNotNull
}
```

### 상태 불변 확인
```kotlin
awaitUnchanged(duration = Duration.ofSeconds(2)) {
    cache.get(key)
}
```

## Anti-patterns (금지)

1. **Thread.sleep() 사용 금지**: Awaitility 사용
2. **실제 외부 API 호출 금지**: Mock 사용
3. **@MockBean 과도한 사용**: Context 캐싱 방해
4. **@AfterEach 사용**: @BeforeEach에서 DatabaseCleaner 사용

## 검증 명령어

```bash
# 컴파일 확인
./gradlew :module-app:compileTestKotlin --continue

# 테스트 실행
./gradlew :module-app:test

# 통합 테스트만 실행
./gradlew :module-app:integrationTest
```

## 관련 문서

- [Testing Guide](../../../docs/03_Technical_Guides/testing-guide.md)
- [IntegrationTestBase](../IntegrationTestBase.kt)
- [LogicExecutor](../../../module-infra/src/main/kotlin/maple/expectation/infrastructure/executor/LogicExecutor.kt)
