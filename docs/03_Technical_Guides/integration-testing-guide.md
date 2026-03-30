# Integration Testing Guide

> **상위 문서:** [testing-guide.md](testing-guide.md) | [CLAUDE.md](../../CLAUDE.md)
>
> **Last Updated:** 2026-03-18
> **Applicable Versions:** JUnit 5, Testcontainers 1.x, Spring Boot 3.x
> **Documentation Version:** 1.0

이 문서는 probabilistic-valuation-engine 프로젝트에서 통합 테스트를 작성하는 방법, 베이스 클래스 사용법, 태깅 규칙, 모범 사례를 정의합니다.

---

## Table of Contents

1. [Overview](#overview)
2. [Testcontainers Setup](#testcontainers-setup)
3. [Base Classes](#base-classes)
4. [Writing Tests](#writing-tests)
5. [Tagging Conventions](#tagging-conventions)
6. [Best Practices](#best-practices)
7. [Running Tests](#running-tests)
8. [Examples](#examples)

---

## Overview

### What are Integration Tests?

통합 테스트는 **여러 컴포넌트가 함께 작동하는 방식**을 검증합니다. 단위 테스트와 달리 실제 데이터베이스, 메시지 큐, 캐시 등의 인프라를 포함합니다.

| 특징 | 단위 테스트 | 통합 테스트 |
|------|-----------|-----------|
| **범위** | 단일 클래스/메서드 | 여러 레이어/컴포넌트 |
| **속도** | 매우 빠름 (< 100ms) | 느림 (1-5초) |
| **인프라** | Mock/Stub | Testcontainers (실제 인프라) |
| **롤백** | 불필요 | @Transactional 또는 DatabaseCleaner |
| **목적** | 로직 정확성 | 통합 동작 검증 |

### When to Use Integration Tests vs Unit Tests

```
단위 테스트 사용:
  ✅ 복잡한 도메인 로직
  ✅ 경계 조건, 예외 처리
  ✅ 알고리즘, 계산 로직
  ✅ 빠른 피드백이 필요한 경우

통합 테스트 사용:
  ✅ Repository 쿼리 검증
  ✅ Service + Repository 조합
  ✅ API 엔드포인트 E2E
  ✅ 인프라 연동 (PGMQ, Redis)
  ✅ 트랜잭션 경계 검증
```

---

## Testcontainers Setup

### PostgreSQL Testcontainers

프로젝트는 `pgtest` 프로필을 사용하여 PostgreSQL Testcontainers를 구성합니다:

```yaml
# application-pgtest.yml
spring:
  datasource:
    url: jdbc:tc:postgresql:16-alpine:///testdb
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver
  jpa:
    hibernate:
      ddl-auto: create-drop
```

### @ActiveProfiles("pgtest")

모든 통합 테스트는 `@ActiveProfiles("pgtest")`를 사용하여 Testcontainers 환경을 활성화합니다:

```kotlin
@SpringBootTest
@ActiveProfiles("pgtest")
class MyIntegrationTest : IntegrationTestBase() {
    // 테스트 코드
}
```

---

## Base Classes

프로젝트는 **3가지 베이스 클래스**를 제공합니다. 각각의 사용 목적에 따라 올바른 베이스 클래스를 선택해야 합니다.

### 1. IntegrationTestBase (RANDOM_PORT)

**용도:** API 통합 테스트 (Controller → Service → DB 전체)

```kotlin
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // ... other properties
    ],
)
@ActiveProfiles("pgtest")
abstract class IntegrationTestBase {
    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }
}
```

**특징:**
- ✅ 실제 HTTP 서버 시작 (RANDOM_PORT)
- ✅ API 엔드포인트 E2E 테스트 가능
- ✅ TestRestTemplate, WebTestClient 사용 가능
- ❌ **@Transactional 사용 금지** (롤백 안 됨)
- ❌ 느림 (서버 시작 오버헤드)

**사용 예시:**

```kotlin
@Tag("integration")
@Tag("api")
@DisplayName("AuthController 통합 테스트")
class AuthControllerIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `로그인 API - 성공`() {
        // Given
        val request = LoginRequest("user@example.com", "password")

        // When
        val response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            request,
            LoginResponse::class.java
        )

        // Then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.token).isNotNull()
    }
}
```

### 2. ServiceIntegrationTestBase (NONE)

**용도:** Service 레벨 통합 테스트 (DB만 필요)

```kotlin
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // ... other properties
    ],
)
@ActiveProfiles("pgtest")
abstract class ServiceIntegrationTestBase {
    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var em: EntityManager

    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }

    protected fun flushAndClear() {
        em.flush()
        em.clear()
    }
}
```

**특징:**
- ✅ 서버 없이 테스트 (빠름)
- ✅ @Transactional 롤백 가능
- ✅ flushAndClear()로 영속성 컨텍스트 제어
- ❌ HTTP 테스트 불가

**사용 예시:**

```kotlin
@Tag("integration")
@DisplayName("MemberService 통합 테스트")
class MemberServiceIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    lateinit var memberService: MemberService

    @Test
    fun `회원 가입 - 성공`() {
        // Given
        val request = RegisterMemberRequest("test@example.com", "password")

        // When
        val member = memberService.register(request)

        // Then
        assertThat(member.id).isNotNull()
        assertThat(member.email).isEqualTo("test@example.com")
        flushAndClear() // DB에 실제로 반영 후 검증

        val found = memberRepository.findById(member.id)
        assertThat(found).isNotNull()
    }
}
```

### 3. RepositoryIntegrationTestBase (Transactional)

**용도:** Repository 통합 테스트 (JPA 쿼리 검증)

```kotlin
@Tag("integration")
@Tag("repository")
@DisplayName("Repository 통합 테스트")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // ... other properties
    ],
)
@ActiveProfiles("pgtest")
@Transactional("transactionManager")
abstract class RepositoryIntegrationTestBase {
    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var em: EntityManager

    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }

    protected fun flushAndClear() {
        em.flush()
        em.clear()
    }
}
```

**특징:**
- ✅ @Transactional 자동 롤백
- ✅ JPA 쿼리 검증에 최적화
- ✅ 가장 빠른 통합 테스트
- ❌ Service/Controller 테스트 부적합

**사용 예시:**

```kotlin
@Tag("integration")
@Tag("repository")
@DisplayName("MemberRepository 통합 테스트")
class MemberRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("findByUuid: 존재하는 회원을 조회한다")
    fun `findByUuid는 존재하는 회원을 반환한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-001", initialPoint = 1000L)
        val saved = memberRepository.save(member)
        flushAndClear()

        // Act
        val found = memberRepository.findByUuid("test-uuid-001")

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.uuid).isEqualTo("test-uuid-001")
        assertThat(found.point).isEqualTo(1000L)
    }

    private fun createTestMember(uuid: String, initialPoint: Long): Member {
        val constructor = Member::class.java.getDeclaredConstructor(
            String::class.java, Long::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(uuid, initialPoint)
    }
}
```

---

## Writing Tests

### Test Class Structure

**권장 패턴:** Given-When-Then

```kotlin
@Tag("integration")
@DisplayName("MyFeature 통합 테스트")
class MyFeatureIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var myService: MyService

    @Test
    @DisplayName("기능 수행 - 성공")
    fun `기능을 수행하면 결과가 나온다`() {
        // Given - 테스트 데이터 준비
        val input = createTestData()

        // When - 기능 실행
        val result = myService.execute(input)

        // Then - 결과 검증
        assertThat(result).isNotNull()
        assertThat(result.status).isEqualTo(Status.SUCCESS)
    }
}
```

### Creating Test Data

#### 1. Repository 저장

```kotlin
@Test
fun `저장된 데이터로 기능 수행`() {
    // Given
    val character = GameCharacter.create(
        userIgn = UserIgn("test-character"),
        characterId = CharacterId("test-ocid-001"),
    )
    characterRepository.save(character)
    flushAndClear() // DB에 실제로 반영

    // When
    val found = characterRepository.findByUserIgn(UserIgn("test-character"))

    // Then
    assertThat(found).isNotNull()
}
```

#### 2. Builder 패턴

```kotlin
private fun createTestMember(
    uuid: String = UUID.randomUUID().toString(),
    email: String = "test@example.com",
    point: Long = 1000L
): Member {
    return Member(
        uuid = uuid,
        email = email,
        point = point
    )
}
```

#### 3. Test Fixtures (권장)

`testfixtures` 패키지에 재사용 가능한 팩토리 메서드 생성:

```kotlin
// testfixtures/MemberFixture.kt
object MemberFixture {
    fun create(
        uuid: String = UUID.randomUUID().toString(),
        email: String = "test@example.com",
        point: Long = 1000L
    ) = Member(uuid, email, point)
}

// 테스트에서 사용
@Test
fun `회원 생성`() {
    val member = MemberFixture.create(uuid = "test-uuid")
    // ...
}
```

### Database Cleanup with DatabaseCleaner

**규칙:** `@BeforeEach`에서 `DatabaseCleaner.clean()` 호출

```kotlin
@BeforeEach
fun setUp() {
    databaseCleaner.clean() // 모든 테이블 TRUNCATE
}
```

**특정 테이블만 정리:**

```kotlin
@BeforeEach
fun setUp() {
    databaseCleaner.clean("game_character", "member")
}
```

**중요:** `@AfterEach`가 아닌 `@BeforeEach`를 사용하는 이유
- 테스트 실패 시 `@AfterEach`가 실행되지 않을 수 있음
- 다음 테스트가 오염된 상태로 실행될 위험
- `@BeforeEach`는 항상 실행되므로 안전함

### Using flushAndClear()

**목적:** @Transactional 테스트에서 DB에 실제로 반영 후 검증

```kotlin
@Test
fun `JPA 영속성 컨텍스트 검증`() {
    // Given
    val member = Member(uuid = "test", email = "test@example.com")
    memberRepository.save(member)

    // flushAndClear() 없이 조회
    val found1 = memberRepository.findByUuid("test")
    assertThat(found1).isNotNull() // 1차 캐시에서 조회

    // DB에 실제로 반영
    flushAndClear()

    // DB에서 실제로 조회
    val found2 = memberRepository.findByUuid("test")
    assertThat(found2).isNotNull()
    assertThat(found2!!.id).isEqualTo(member.id)
}
```

**flushAndClear()가 필요한 경우:**
- 제약 조건 검증 (Unique, Foreign Key)
- DB 트리거 검증
- 영속성 컨텍스트 분리 후 재조회

---

## Tagging Conventions

모든 통합 테스트는 반드시 태그를 사용하여 분류해야 합니다.

### 표준 태그

| 태그 | 용도 | 실행 시점 |
|------|------|----------|
| `@Tag("integration")` | 모든 통합 테스트 | PR Gate |
| `@Tag("repository")` | Repository 테스트 | PR Gate |
| `@Tag("api")` | API 테스트 | PR Gate |
| `@Tag("infra-verification")` | 인프라 검증 (PGMQ, Redis) | Nightly |
| `@Tag("pgmq")` | PGMQ 관련 테스트 | PR Gate |
| `@Tag("worker")` | Worker 테스트 | PR Gate |

### 태깅 예시

```kotlin
@Tag("integration")           // 필수: 모든 통합 테스트
@Tag("repository")            // 선택: Repository 테스트
@DisplayName("MemberRepository 통합 테스트")
class MemberRepositoryIntegrationTest : RepositoryIntegrationTestBase() {
    // ...
}
```

```kotlin
@Tag("integration")           // 필수
@Tag("api")                   // 선택: API 테스트
@DisplayName("AuthController 통합 테스트")
class AuthControllerIntegrationTest : IntegrationTestBase() {
    // ...
}
```

```kotlin
@Tag("integration")           // 필수
@Tag("infra-verification")    // 선택: 인프라 검증
@Tag("pgmq")                  // 선택: PGMQ 관련
@DisplayName("PGMQ Client 통합 테스트")
class PgmqClientIntegrationTest : IntegrationTestBase() {
    // ...
}
```

---

## Best Practices

### 1. No @MockBean (Critical)

**문제:** @MockBean은 Spring Context 캐싱을 깨트립니다.

```kotlin
// Bad - Context 캐싱 깨짐
@SpringBootTest
class MyTest {
    @MockBean
    lateinit var redisTemplate: RedisTemplate<String, String>
    // 테스트마다 새 Context 생성 -> 느려짐
}

// Good - Testcontainers로 실제 인프라 사용
@SpringBootTest
@ActiveProfiles("pgtest")
class MyTest : IntegrationTestBase() {
    @Autowired
    lateinit var redisTemplate: RedisTemplate<String, String>
    // Context 재사용 -> 빠름
}
```

### 2. No @Transactional in RANDOM_PORT Tests

**문제:** RANDOM_PORT에서 @Transactional은 롤백되지 않습니다.

```kotlin
// Bad - RANDOM_PORT에서 롤백 안 됨
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Transactional
class MyTest {
    @Test
    fun test() {
        // 데이터가 실제로 DB에 남음
    }
}

// Good - DatabaseCleaner 사용
@SpringBootTest(webEnvironment = RANDOM_PORT)
class MyTest : IntegrationTestBase() {
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean() // 명시적 정리
    }
}
```

### 3. Use @BeforeEach for Cleanup

```kotlin
// Good - 항상 실행됨
@BeforeEach
fun setUp() {
    databaseCleaner.clean()
}

// Bad - 실패 시 실행 안 됨
@AfterEach
fun tearDown() {
    databaseCleaner.clean()
}
```

### 4. Use Awaitility for Async Operations

```kotlin
// Bad - Thread.sleep() 사용
@Test
fun test() {
    service.asyncProcess()
    Thread.sleep(1000) // 비결정적
    assertThat(result).isNotNull()
}

// Good - Awaitility 사용
@Test
fun test() {
    service.asyncProcess()

    await().atMost(Duration.ofSeconds(5))
        .untilAsserted {
            assertThat(result).isNotNull()
        }
}
```

### 5. Avoid Shared State

```kotlin
// Bad - static 변수
class MyTest {
    companion object {
        var counter = 0 // 테스트 간 오염
    }
}

// Good - 테스트마다 독립적 상태
class MyTest {
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }
}
```

### 6. Descriptive Test Names

```kotlin
// Bad
@Test
fun test1() { }

@Test
fun testLogin() { }

// Good
@Test
@DisplayName("로그인 - 잘못된 비밀번호로 실패")
fun `로그인시 잘못된 비밀번호면 실패한다`() { }
```

---

## Running Tests

### Gradle Commands

```bash
# 모든 테스트 실행
./gradlew test

# 통합 테스트만 실행
./gradlew test --tests "*IntegrationTest"

# 특정 태그만 실행 (JUnit 5)
./gradlew test --include-tag "integration"

# Repository 테스트만 실행
./gradlew test --include-tag "repository"

# API 테스트만 실행
./gradlew test --include-tag "api"

# 인프라 검증 테스트만 실행
./gradlew test --include-tag "infra-verification"

# 특정 테스트 클래스만 실행
./gradlew test --tests "MemberRepositoryIntegrationTest"

# 특정 테스트 메서드만 실행
./gradlew test --tests "MemberRepositoryIntegrationTest.findByUuid"

# 병렬 실행 (기본)
./gradlew test --parallel

# 디버그 모드
./gradlew test --debug-jvm
```

### CI/CD Integration

```yaml
# .github/workflows/test.yml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run tests
        run: ./gradlew test --include-tag "integration"
      - name: Upload test results
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: '**/build/test-results/test/'
```

---

## Examples

### Repository Test Example

```kotlin
@Tag("integration")
@Tag("repository")
@DisplayName("MemberRepository 통합 테스트")
class MemberRepositoryIntegrationTest : RepositoryIntegrationTestBase() {

    @Autowired
    private lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("findByUuid: 존재하는 회원을 조회한다")
    fun `findByUuid는 존재하는 회원을 반환한다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-001", initialPoint = 1000L)
        val saved = memberRepository.save(member)
        flushAndClear()

        // Act
        val found = memberRepository.findByUuid("test-uuid-001")

        // Assert
        assertThat(found).isNotNull
        assertThat(found!!.uuid).isEqualTo("test-uuid-001")
        assertThat(found.point).isEqualTo(1000L)
    }

    @Test
    @DisplayName("increasePointByUuid: 회원의 포인트를 증가시킨다")
    fun `increasePointByUuid는 회원의 포인트를 증가시킨다`() {
        // Arrange
        val member = createTestMember(uuid = "test-uuid-007", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // Act
        val updatedRows = memberRepository.increasePointByUuid("test-uuid-007", 500L)
        flushAndClear()

        // Assert
        assertThat(updatedRows).isEqualTo(1)

        val found = memberRepository.findByUuid("test-uuid-007")
        assertThat(found).isNotNull
        assertThat(found!!.point).isEqualTo(1500L) // 1000 + 500
    }

    private fun createTestMember(uuid: String, initialPoint: Long): Member {
        val constructor = Member::class.java.getDeclaredConstructor(
            String::class.java, Long::class.java
        )
        constructor.isAccessible = true
        return constructor.newInstance(uuid, initialPoint)
    }
}
```

### Service Test Example

```kotlin
@Tag("integration")
@DisplayName("MemberService 통합 테스트")
class MemberServiceIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    lateinit var memberService: MemberService

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Test
    @DisplayName("회원 가입 - 성공")
    fun `회원 가입에 성공한다`() {
        // Given
        val request = RegisterMemberRequest(
            email = "test@example.com",
            password = "SecurePassword123!",
            nickname = "test-user"
        )

        // When
        val member = memberService.register(request)

        // Then
        assertThat(member.id).isNotNull()
        assertThat(member.email).isEqualTo("test@example.com")
        assertThat(member.nickname).isEqualTo("test-user")

        flushAndClear() // DB에 실제로 반영 후 검증

        val found = memberRepository.findByEmail("test@example.com")
        assertThat(found).isNotNull()
        assertThat(found!!.email).isEqualTo("test@example.com")
    }

    @Test
    @DisplayName("포인트 충전 - 성공")
    fun `포인트 충전에 성공한다`() {
        // Given
        val member = createTestMember(uuid = "test-uuid", initialPoint = 1000L)
        memberRepository.save(member)
        flushAndClear()

        // When
        memberService.chargePoint(member.uuid, 500L)

        // Then
        flushAndClear()
        val found = memberRepository.findByUuid("test-uuid")
        assertThat(found).isNotNull()
        assertThat(found!!.point).isEqualTo(1500L)
    }
}
```

### API Test Example

```kotlin
@Tag("integration")
@Tag("api")
@DisplayName("AuthController 통합 테스트")
class AuthControllerIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    @DisplayName("로그인 - 성공")
    fun `로그인에 성공한다`() {
        // Given
        val request = LoginRequest(
            email = "test@example.com",
            password = "SecurePassword123!"
        )

        // When
        val response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            request,
            LoginResponse::class.java
        )

        // Then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isNotNull()
        assertThat(response.body!!.token).isNotEmpty()
        assertThat(response.body!!.email).isEqualTo("test@example.com")
    }

    @Test
    @DisplayName("로그인 - 실패 (잘못된 비밀번호)")
    fun `잘못된 비밀번호로 로그인하면 401을 반환한다`() {
        // Given
        val request = LoginRequest(
            email = "test@example.com",
            password = "WrongPassword123!"
        )

        // When
        val response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            request,
            ErrorResponse::class.java
        )

        // Then
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body).isNotNull()
        assertThat(response.body!!.message).contains("인증 실패")
    }

    @Test
    @DisplayName("회원 정보 조회 - 성공")
    fun `로그인한 사용자가 본인 정보를 조회한다`() {
        // Given
        val loginResponse = login("test@example.com", "password")
        val headers = HttpHeaders().apply {
            setBearerAuth(loginResponse.token)
        }

        // When
        val response = restTemplate.exchange(
            "/api/v1/auth/me",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            MemberResponse::class.java
        )

        // Then
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isNotNull()
        assertThat(response.body!!.email).isEqualTo("test@example.com")
    }

    private fun login(email: String, password: String): LoginResponse {
        val request = LoginRequest(email, password)
        val response = restTemplate.postForEntity(
            "/api/v1/auth/login",
            request,
            LoginResponse::class.java
        )
        return response.body!!
    }
}
```

### Worker Test Example (PGMQ)

```kotlin
@Tag("integration")
@Tag("pgmq")
@Tag("worker")
@DisplayName("LikeSyncWorker 통합 테스트")
@TestPropertySource(
    properties = [
        "pgmq.worker.like-sync.enabled=true",
        "pgmq.worker.common.visibility-timeout-sec=1",
        "pgmq.worker.common.max-retries=2",
    ],
)
class LikeSyncWorkerIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var pgmqClient: PgmqClient

    @Autowired
    lateinit var characterRepository: GameCharacterRepository

    private val testQueueName = "like_sync_test_queue"

    @BeforeEach
    override fun setUp() {
        super.setUp()
        createTestQueue()
        createTestCharacter()
    }

    @Test
    @DisplayName("정상 처리: 메시지가 성공적으로 처리되어 아카이브된다")
    fun `정상 처리 시 메시지가 아카이브된다`() {
        // Given
        val initialLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        val delta = 5L
        val messageId = pgmqClient.send(
            testQueueName,
            LikeSyncRequest(
                characterName = TEST_CHARACTER_NAME,
                delta = delta,
                requestedAt = Instant.now().toString(),
            ),
        )

        // When
        val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages).hasSize(1)

        val message = messages[0]
        characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, delta)
        pgmqClient.archive(testQueueName, message.messageId)

        // Then
        val finalLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        assertThat(finalLikeCount).isEqualTo(initialLikeCount + delta)

        val remainingMessages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 10, 1)
        assertThat(remainingMessages).isEmpty()
    }

    private fun createTestQueue() {
        jdbcTemplate.execute("SELECT pgmq.create('$testQueueName')")
    }

    private fun createTestCharacter() {
        val character = GameCharacter.create(
            userIgn = UserIgn(TEST_CHARACTER_NAME),
            characterId = CharacterId("test-ocid-001"),
        )
        characterRepository.save(character)
    }
}
```

---

## Related Documentation

- [Testing Guide](testing-guide.md) - 테스트 작성 기본 가이드
- [Async Concurrency Guide](async-concurrency.md) - 비동기 테스트 패턴
- [Infrastructure Guide](infrastructure.md) - Testcontainers 설정
- [ADR-002: PGMQ Queue Architecture](../../01_ADR/002-pgmq-queue-architecture.md)

## Evidence Links

- **Base Classes:** `module-app/src/test/kotlin/maple/expectation/test/`
- **Example Tests:** `module-app/src/test/kotlin/maple/expectation/integration/`
- **DatabaseCleaner:** `module-app/src/test/kotlin/maple/expectation/test/DatabaseCleaner.kt`
