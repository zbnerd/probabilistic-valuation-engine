# Infra Layer Test Templates

Infrastructure 레이어 테스트를 위한 템플릿 클래스 모음입니다.

## Overview

| 템플릿 | 파일 | 용도 | 주요 기능 |
|--------|------|------|----------|
| `InfraAdapterTestTemplate` | `InfraAdapterTestTemplate.kt` | 인프라 어댑터 테스트 | Circuit Breaker 검증, Awaitility |
| `ExternalApiTestTemplate` | `ExternalApiTestTemplate.kt.disabled` | 외부 API 클라이언트 테스트 | WireMock (의존성 추가 필요) |

---

## InfraAdapterTestTemplate

인프라 어댑터 테스트를 위한 기본 템플릿입니다. Circuit Breaker 상태 검증과 비동기 대기 기능을 제공합니다.

### 특징

- **Circuit Breaker 검증**: Resilience4j Circuit Breaker 상태 검증 헬퍼
- **Awaitility 통합**: `Thread.sleep()` 대신 안전한 비동기 대기
- **Testcontainers 재사용**: module-app의 `TestcontainersConfiguration` 재사용

### 사용 예시

```kotlin
@Tag("integration")
class UserRepositoryAdapterTest : InfraAdapterTestTemplate() {

    private lateinit var adapter: UserRepositoryAdapter
    private lateinit var circuitBreaker: CircuitBreaker

    @BeforeEach
    override fun setupInfrastructure() {
        super.setupInfrastructure()

        // Circuit Breaker 설정
        circuitBreaker = CircuitBreaker.ofDefaults("user-repo")
        adapter = UserRepositoryAdapter(circuitBreaker)
    }

    @Test
    fun `Circuit Breaker가 CLOSED 상태로 시작`() {
        // Then
        assertCircuitBreakerClosed(circuitBreaker)
    }

    @Test
    fun `연속 실패 시 Circuit Breaker OPEN`() {
        // When: 연속 실패 유발
        repeat(5) {
            assertThatThrownBy { adapter.findById("invalid") }
        }

        // Then
        assertCircuitBreakerOpen(circuitBreaker)
    }

    @Test
    fun `비동기 상태 변화 대기`() {
        // When
        adapter.asyncUpdate("user-1", "new-name")

        // Then: Awaitility로 상태 변화 대기
        awaitUntil(timeoutMs = 2000) {
            assertThat(adapter.findById("user-1")?.name).isEqualTo("new-name")
        }
    }
}
```

### 제공되는 Helper 메서드

#### Circuit Breaker 검증

```kotlin
assertCircuitBreakerState(circuitBreaker, CircuitBreaker.State.OPEN)
assertCircuitBreakerOpen(circuitBreaker)
assertCircuitBreakerClosed(circuitBreaker)
assertCircuitBreakerHalfOpen(circuitBreaker)
```

#### Awaitility (비동기 대기)

```kotlin
// 커스텀 assertion으로 대기
awaitUntil(timeoutMs = 2000) {
    assertThat(result).isNotNull
}

// 조건이 참이 될 때까지 대기
awaitUntilEquals(
    supplier = { adapter.isReady() },
    expectedValue = true,
    timeoutMs = 3000
)
```

---

## ExternalApiTestTemplate (DISABLED)

WireMock을 사용한 외부 API 클라이언트 테스트 템플릿입니다.

**현재 상태**: `.kt.disabled` 파일로 존재합니다. WireMock 의존성 추가 후 활성화 가능합니다.

### 활성화 방법

1. `build.gradle`에 의존성 추가:
   ```kotlin
   dependencies {
       testImplementation("com.github.tomakehurst:wiremock-junit5:3.9.1")
   }
   ```

2. 파일명 변경:
   ```bash
   mv ExternalApiTestTemplate.kt.disabled ExternalApiTestTemplate.kt
   ```

### 사용 예시 (활성화 후)

```kotlin
class PaymentClientTest : ExternalApiTestTemplate() {

    private lateinit var client: PaymentClient

    @BeforeEach
    override fun setupExternalApi() {
        super.setupExternalApi()
        client = PaymentClient(baseUrl = getWireMockBaseUrl())
    }

    @Test
    fun `결제 승인 API 호출 성공`() {
        // Given
        mockExternalPostApi(
            path = "/v1/payments/approve",
            statusCode = 200,
            responseBody = """{"id": "payment-123", "status": "approved"}"""
        )

        // When
        val result = client.approvePayment(PaymentRequest(...))

        // Then
        assertThat(result.id).isEqualTo("payment-123")
        verifyApiCalled("/v1/payments/approve")
    }
}
```

---

## Testcontainers 사용법

이 프로젝트는 module-app의 `TestcontainersConfiguration`을 사용합니다:

```kotlin
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    companion object {
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<*> = ...

        @JvmStatic
        val redisContainer: GenericContainer<*> = ...
    }
}
```

테스트에서 `@SpringBootTest`와 함께 사용하면 자동으로 연결됩니다.

### 직접 사용 예시

```kotlin
@SpringBootTest
class MyInfraTest : InfraAdapterTestTemplate() {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `DB 연결 확인`() {
        // TestcontainersConfiguration의 postgresContainer 자동 연결됨
        val connection = dataSource.connection
        assertThat(connection.isValid(1)).isTrue()
    }
}
```

---

## Anti-Patterns (금지 사항)

### 1. 실제 운영 환경 API 호출

```kotlin
// ❌ BAD
class BadTest {
    @Test
    fun test() {
        val client = PaymentClient("https://api.real-production.com") // 금지
    }
}

// ✅ GOOD
class GoodTest : ExternalApiTestTemplate() {
    @Test
    fun test() {
        val client = PaymentClient(getWireMockBaseUrl()) // Mock 사용
    }
}
```

### 2. Thread.sleep() 사용

```kotlin
// ❌ BAD
@Test
fun test() {
    client.asyncUpdate()
    Thread.sleep(1000) // 금지: 타이밍 의존적
}

// ✅ GOOD
@Test
fun test() {
    client.asyncUpdate()
    awaitUntil(timeoutMs = 2000) {  // 안전한 대기
        assertThat(client.isDone()).isTrue
    }
}
```

### 3. 과도한 Mock 사용

```kotlin
// ❌ BAD
class BadTest {
    @Test
    fun test() {
        val mockContainer = mock<PostgreSQLContainer>() // 금지
        val mockRedis = mock<RedisContainer>()         // 금지
    }
}

// ✅ GOOD: 실제 Testcontainers 사용
@SpringBootTest
class GoodTest : InfraAdapterTestTemplate() {
    @Autowired
    private lateinit var dataSource: DataSource  // 실제 연결
}
```

---

## 태깅 전략

```kotlin
@Tag("integration")  // 통합 테스트 (Testcontainers 필요)
@Tag("fast")         // 빠른 단위 테스트
@Tag("slow")         // 느린 통합 테스트
```

실행 예시:
```bash
# 통합 테스트만 실행
./gradlew :module-infra:test --tests "*Test" --tags integration

# 빠른 테스트만 실행
./gradlew :module-infra:test --tags fast
```

---

## 참고 자료

- [Testing Guide](../../../../docs/03_Technical_Guides/testing-guide.md)
- [TestcontainersConfiguration](../../../app/src/test/kotlin/maple/expectation/config/TestcontainersConfiguration.kt)
- [IntegrationTestBase](../../../app/src/test/kotlin/maple/expectation/support/IntegrationTestBase.kt)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [WireMock Documentation](https://wiremock.org/docs/) (ExternalApiTestTemplate 활성화 후)
