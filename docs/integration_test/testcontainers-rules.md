# Testcontainers 통합 테스트 설정 규칙

> Issue #547: PostgreSQL Migration

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────┐
│                    JVM 프로세스                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  TestcontainersConfiguration (companion object)         │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  PostgreSQL Container (PGMQ)                    │    │    │
│  │  │  - JVM 레벨 싱글톤                               │    │    │
│  │  │  - start() 1번만 호출                            │    │    │
│  │  │  - withReuse(true)                              │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              ↓                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Spring Context (@ServiceConnection)                     │    │
│  │  - datasource.url 자동 주입                              │    │
│  │  - datasource.username 자동 주입                         │    │
│  │  - datasource.password 자동 주입                         │    │
│  └─────────────────────────────────────────────────────────┘    │
│                              ↓                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  IntegrationTestBase                                     │    │
│  │  - @BeforeEach: DatabaseCleaner.clean()                  │    │
│  │  - entityManager.clear()                                 │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

## 1. 싱글톤 컨테이너 설정

```kotlin
// TestcontainersConfiguration.kt
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    companion object {
        // JVM 프로세스당 정확히 1번만 시작. Context 재생성과 무관.
        @JvmStatic
        private val postgresContainer: PostgreSQLContainer<*> =
            PostgreSQLContainer<Nothing>(
                DockerImageName
                    .parse("jumski/postgres-17-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            ).apply {
                withDatabaseName("maple_test")
                withUsername("test")
                withPassword("test")
                withCommand(
                    "postgres",
                    "-c", "fsync=off",
                    "-c", "synchronous_commit=off",
                    "-c", "full_page_writes=off",
                    "-c", "max_connections=50",
                )
                withInitScript("sql/init-pgmq.sql")
                withReuse(true)
                start()
            }
    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> = Companion.postgresContainer
}
```

### ⚠️ 주의사항

| 금지 | 이유 |
|------|------|
| `@Container` | companion object + start()와 혼용 시 중복 생성 |
| `@Testcontainers` | 같은 이유 |
| `@DynamicPropertySource` | @ServiceConnection으로 대체 |

---

## 2. 자동 등록 — spring.factories

```
# src/test/resources/META-INF/spring/org.springframework.boot.test.context.TestConfiguration.imports
maple.expectation.config.TestcontainersConfiguration
```

이 한 줄이면 `@SpringBootTest` 붙이기만 해도 컨테이너가 자동으로 뜬다.
`@Import` 어노테이션 쓰지 마라. 까먹으면 테스트가 DB 없이 돌아서 깨진다.

---

## 3. DatabaseCleaner — FK 무시 + JDBC 직접 사용

```kotlin
@Component
@Profile("test")
class DatabaseCleaner(
    private val dataSource: DataSource,
) {
    private lateinit var tableNames: List<String>

    @PostConstruct
    fun init() {
        dataSource.connection.use { conn ->
            val rs = conn.metaData.getTables(null, "public", null, arrayOf("TABLE"))
            val tables = mutableListOf<String>()
            while (rs.next()) {
                val name = rs.getString("TABLE_NAME")
                // PGMQ 내부 테이블, Spring Batch 메타 테이블 제외
                if (!name.startsWith("pgmq") &&
                    !name.startsWith("batch_") &&
                    !name.startsWith("flyway")
                ) {
                    tables.add(name)
                }
            }
            tableNames = tables
        }
    }

    fun clean() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().use { stmt ->
                stmt.execute("SET session_replication_role = 'replica'")
                tableNames.forEach { stmt.execute("TRUNCATE TABLE \"$it\" CASCADE") }
                stmt.execute("SET session_replication_role = 'DEFAULT'")
            }
            conn.commit()
        }
    }
}
```

### JDBC 직접 사용 이유

- EntityManager 방식은 영속성 컨텍스트 상태에 영향받아 플래키 발생
- JDBC는 Hibernate 캐시와 무관하게 확실히 정리
- 트랜잭션도 직접 관리해서 예외 시에도 깨끗

핵심: **@BeforeEach에서 호출**. @AfterEach가 아님.
이유: 테스트 실패 시 @AfterEach가 실행 안 될 수 있어서 다음 테스트가 오염됨.

---

## 4. 통합 테스트 베이스 클래스

```kotlin
// Service 레벨 통합 테스트용
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var entityManager: EntityManager

    @BeforeEach
    fun cleanUp() {
        databaseCleaner.clean()
        entityManager.clear()  // Hibernate 1차 캐시 비우기
    }
}

// API 통합 테스트용 (HTTP 필요할 때만)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class ApiIntegrationTestBase {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @BeforeEach
    fun cleanUp() {
        databaseCleaner.clean()
    }

    // @Transactional 여기서 쓰지 마라. RANDOM_PORT에서 안 먹는다.
}
```

---

## 5. 실제 테스트 — 이렇게 깔끔해야 함

```kotlin
class CalculationServiceTest : IntegrationTestBase() {

    @Autowired
    lateinit var calculationService: CalculationService

    @Autowired
    lateinit var characterRepository: CharacterRepository

    @Test
    fun `기대값 계산이 정상 동작한다`() {
        // GIVEN
        characterRepository.save(CharacterFixtures.create("char-001"))

        // WHEN
        val result = calculationService.calculate("char-001", "starforce-22")

        // THEN
        assertThat(result.expectedCost).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `PGMQ 비동기 계산이 완료된다`() {
        // GIVEN
        characterRepository.save(CharacterFixtures.create("char-002"))

        // WHEN
        calculationService.enqueueCalculation("char-002", "starforce-22")

        // THEN — Awaitility로 비동기 검증 (Thread.sleep 절대 금지)
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(300))
            .untilAsserted {
                val saved = precomputedRepository.findByCharacterId("char-002")
                assertThat(saved).isNotNull
                assertThat(saved!!.expectedCost).isGreaterThan(BigDecimal.ZERO)
            }
    }
}
```

컨테이너 설정 코드가 테스트 클래스에 한 줄도 없다.
`@SpringBootTest`만 붙이면 PostgreSQL + PGMQ가 자동으로 뜨고 연결된다.

---

## 6. PGMQ 초기화 스크립트

```sql
-- sql/init-pgmq.sql
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- Create application queues
SELECT pgmq.create('v4_buffer_queue');
SELECT pgmq.create('v5_event_queue');
SELECT pgmq.create('donation_outbox_queue');
```

---

## 7. application-test.yml

```yaml
spring:
  profiles:
    active: test

  # DataSource is auto-configured by TestcontainersConfiguration @ServiceConnection
  # No manual datasource URL/username/password needed here

  jpa:
    hibernate:
      ddl-auto: create-drop    # 테스트마다 스키마 재생성
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        show_sql: false          # CI에서는 false (로그 폭발 방지)
        default_batch_fetch_size: 20
        jdbc:
          batch_size: 50
    open-in-view: false          # 운영과 동일하게

  datasource:
    hikari:
      maximum-pool-size: 10      # 테스트에서는 적게
      minimum-idle: 2
      connection-timeout: 5000
      leak-detection-threshold: 30000

logging:
  level:
    org.testcontainers: WARN        # 컨테이너 로그 최소화
    com.github.dockerjava: WARN     # Docker 클라이언트 로그 최소화
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

---

## 8. 속도 최적화 체크리스트

이 모든 것이 적용되면:
- ✅ 컨테이너 시작: 전체 테스트 스위트에서 1번만 (약 3~5초)
- ✅ Context 로딩: `@MockBean` 안 쓰므로 재생성 없음 (1번만)
- ✅ DB 정리: TRUNCATE로 테스트당 10~50ms
- ✅ 비동기 검증: Awaitility로 정확한 타이밍 (불필요한 대기 없음)

### 금지 사항 위반 시 속도 저하

| 위반 | 페널티 |
|------|--------|
| `@MockBean` 1개 추가 | Context 재생성 → +30초 |
| `@DirtiesContext` 1개 | Context 폐기 → +30초 |
| `Thread.sleep(5000)` | 무조건 5초 낭비 + CI에서 플래키 |

---

## 참고 문서

- [Testcontainers 공식 문서](https://www.testcontainers.org/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Awaitility](https://github.com/awaitility/awaitility)
