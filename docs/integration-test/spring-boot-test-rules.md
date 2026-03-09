# Spring Boot 통합 테스트 규칙

> Issue #547: PostgreSQL Migration

## 1. Context 캐싱 — ApplicationContext 재생성 최소화

Spring은 동일 설정의 ApplicationContext를 캐싱한다.
Context가 재생성되면 테스트 전체가 30초~1분씩 느려진다.

### ❌ Context 재생성을 유발하는 것들

| 항목 | 문제 |
|------|------|
| `@MockBean` | Context 오염시켜서 매번 재생성 |
| `@SpyBean` | Context 오염시켜서 매번 재생성 |
| `@DirtiesContext` | 명시적 Context 폐기 |
| `@TestPropertySource` | 테스트마다 다른 값 사용 |
| `@ActiveProfiles` | 테스트마다 다른 프로파일 |

### ✅ 대안: 테스트용 Fake Bean

```kotlin
// ❌ Context 매번 재생성됨
@SpringBootTest
class TestA {
    @MockBean lateinit var nexonApi: NexonApiClient  // 이거 때문에 Context 새로 뜸
}

// ✅ 모든 테스트가 같은 Context 공유
@Component
@Profile("test")
class FakeNexonApiClient : NexonApiClient {
    override fun getEquipmentData(ocid: String): String =
        loadFixture("nexon-response.json")
}
```

---

## 2. WebEnvironment 선택 기준

| WebEnvironment | 용도 | 특징 |
|----------------|------|------|
| `RANDOM_PORT` | API 통합 테스트 | 실제 HTTP 서버 뜸. RestClient/WebTestClient로 호출 |
| `NONE` | Service 레벨 통합 테스트 | 서버 안 뜸. 빠름. @Transactional 롤백 가능 |
| `MOCK` | Controller 단위 테스트 | MockMvc 사용. Service는 Mock |

### 선택 기준

```
DB 쓰기 + API 호출 검증 → RANDOM_PORT + DatabaseCleaner
DB 쓰기만 검증          → NONE + @Transactional
Controller만 검증       → @WebMvcTest
```

### 예제

```kotlin
// API 통합 테스트 (Controller → Service → DB 전체)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("pgtest")
class ApiIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `API 호출 테스트`() {
        webTestClient.get()
            .uri("/api/v1/characters/{ocid}", "test-ocid")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("TestCharacter")
    }
}

// Service 레벨 통합 테스트 (DB만 필요)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pgtest")
@Transactional
class ServiceIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    lateinit var characterService: GameCharacterService

    @Test
    fun `서비스 로직 테스트`() {
        // Given
        val ocid = "test-ocid"

        // When
        characterService.save(ocid)

        // Then
        flushAndClear()  // DB에 쓰고 1차 캐시 비우기
        val found = characterRepository.findByOcid(ocid)
        assertThat(found).isPresent
    }
}
```

---

## 3. @Transactional 테스트의 함정

### ❌ 위험한 패턴 — false positive 발생

```kotlin
@SpringBootTest
@Transactional  // 테스트 끝나면 롤백
class LikeServiceTest {
    @Test
    fun `좋아요 저장 테스트`() {
        likeService.like("char-001")
        // flush 안 됐는데 통과함
        // 실제 운영에서는 DB 제약 조건 위반으로 실패할 수 있음
        assertThat(likeRepository.count()).isEqualTo(1)
    }
}
```

### ✅ 올바른 패턴

```kotlin
@SpringBootTest
@Transactional
class LikeServiceTest {
    @Autowired lateinit var em: EntityManager

    @Test
    fun `좋아요 저장 테스트`() {
        likeService.like("char-001")
        em.flush()    // DB에 실제로 쓰기 → 제약 조건 검증됨
        em.clear()    // 1차 캐시 비우기 → DB에서 실제로 읽기
        assertThat(likeRepository.count()).isEqualTo(1)
    }
}
```

### 규칙

> @Transactional 쓸 때 반드시 `em.flush()` + `em.clear()` 후 assert

---

## 4. RANDOM_PORT에서 @Transactional 안 먹는 이유

```
@SpringBootTest(webEnvironment = RANDOM_PORT) + @Transactional 조합 시:
- 테스트 스레드에서 @Transactional 시작
- HTTP 요청은 서버 스레드에서 처리 (다른 트랜잭션)
- 서버 스레드가 커밋한 데이터를 테스트 스레드에서 롤백 불가
```

### 규칙

> RANDOM_PORT에서는 @Transactional 쓰지 마라.
> DatabaseCleaner로 @BeforeEach에서 정리해라.

---

## 5. 테스트 슬라이스 전략

테스트 피라미드:

```
        @SpringBootTest(RANDOM_PORT)  ← E2E (적게, 전체 플로우만)
                ↑
        @SpringBootTest(NONE)         ← Service 통합 (중간)
                ↑
        @DataJpaTest                  ← Repository (많이)
                ↑
        @WebMvcTest                   ← Controller 단위 (많이)
```

| 어노테이션 | 로딩 범위 | 속도 |
|------------|-----------|------|
| `@DataJpaTest` | JPA Repository만 | 가장 빠름 |
| `@WebMvcTest` | Controller만 | 빠름 |
| `@SpringBootTest(NONE)` | 서버 없이 Service + DB | 중간 |
| `@SpringBootTest(RANDOM_PORT)` | 전체 | 가장 느림 |

---

## 6. 테스트 프로파일 설정

### application-pgtest.yml

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: create-drop     # 테스트마다 스키마 재생성
    properties:
      hibernate:
        format_sql: true
        show_sql: false          # CI에서는 false (로그 폭발 방지)
        default_batch_fetch_size: 20
    open-in-view: false          # 운영과 동일하게

  datasource:
    hikari:
      maximum-pool-size: 5       # 테스트에서는 적게
      connection-timeout: 5000

logging:
  level:
    org.hibernate.SQL: DEBUG                # 쿼리 확인
    org.hibernate.type.descriptor.sql: TRACE # 바인딩 파라미터 확인
    org.testcontainers: INFO                # 컨테이너 로그 최소화
```

---

## 7. Context 로딩 실패 디버깅

Context 로딩 실패 시 에러 메시지가 불친절하다.

### logback-test.xml

```xml
<logger name="org.springframework.boot.autoconfigure" level="DEBUG"/>
```

### 흔한 원인

| 원인 | 해결 |
|------|------|
| 멀티모듈 @ComponentScan 범위 안 맞음 | `@SpringBootApplication(scanBasePackages = ["maple.expectation"])` |
| @EntityScan이 다른 모듈 엔티티 못 찾음 | `@EntityScan(basePackages = ["maple.expectation"])` |
| DataSource 설정이 먼저 로딩됨 | `@DynamicPropertySource` 사용 |

---

## 8. 테스트 실행 순서

### build.gradle.kts

```kotlin
tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1  // 통합 테스트는 순차 실행 (DB 공유하므로)

    // 단위 테스트만 병렬
    jvmArgs("-XX:+UseParallelGC")  // 테스트 GC 최적화

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false  // CI 로그 폭발 방지
    }
}
```

---

## 요약 체크리스트

- [ ] `@MockBean`, `@SpyBean` 사용 안 함
- [ ] RANDOM_PORT에서는 `@Transactional` 사용 안 함
- [ ] `@Transactional` 테스트는 `flush()` + `clear()` 후 assert
- [ ] `@BeforeEach`에서 `DatabaseCleaner.clean()` 호출
- [ ] 테스트 간 순서 의존 없음
- [ ] `Thread.sleep()` 대신 Awaitility 사용
- [ ] PGMQ 큐는 `@BeforeEach`에서 purge
