# Testcontainers 통합 테스트 설정 규칙

> Issue #547: PostgreSQL Migration

## 1. 싱글톤 컨테이너 — companion object + @JvmStatic

```kotlin
abstract class PostgresContainerBaseTest {
    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("test_db")
            withUsername("test")
            withPassword("test")
            withCommand(
                "postgres",
                "-c", "fsync=off",              // 테스트 속도 향상
                "-c", "synchronous_commit=off",  // 테스트 속도 향상
                "-c", "full_page_writes=off"     // 테스트 속도 향상
            )
            withReuse(true)   // 로컬에서만. CI에서는 무시됨
            start()           // static 블록에서 1번만 시작
        }

        @JvmStatic
        @DynamicPropertySource
        fun configure(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
```

### ⚠️ 주의사항

| 금지 | 이유 |
|------|------|
| `@Container` | 수동 `start()`와 혼용 시 컨테이너 중복 생성 |
| `@Testcontainers` | 같은 이유 |

---

## 2. 플래키 테스트 방지 — DatabaseCleaner

```kotlin
@Component
@Profile("test")
class DatabaseCleaner(
    @PersistenceContext private val em: EntityManager
) : InitializingBean {
    private lateinit var tableNames: List<String>

    override fun afterPropertiesSet() {
        tableNames = em.metamodel.entities
            .filter { it.javaType.isAnnotationPresent(Table::class.java) }
            .mapNotNull {
                it.javaType.getAnnotation(Table::class.java)?.name
                    ?: it.name.lowercase()
            }
            .filter { !it.startsWith("pgmq") }  // PGMQ 내부 테이블 건드리면 안 됨
    }

    @Transactional
    fun clean() {
        em.flush()
        em.createNativeQuery("SET session_replication_role = 'replica'").executeUpdate()  // FK 무시
        tableNames.forEach {
            em.createNativeQuery("TRUNCATE TABLE $it CASCADE").executeUpdate()
        }
        em.createNativeQuery("SET session_replication_role = 'DEFAULT'").executeUpdate()
    }
}
```

### 핵심 규칙

- `@BeforeEach`에서 `clean()` 호출
- `@AfterEach`가 아님
- **이유:** 테스트 실패 시 `@AfterEach`가 실행 안 될 수 있어서 다음 테스트가 오염됨

---

## 3. 테스트 격리 베이스 클래스

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase : PostgresContainerBaseTest() {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }
}
```

**모든 통합 테스트는 이 클래스를 상속한다.**

---

## 4. Awaitility로 비동기 검증 (Thread.sleep 절대 금지)

PGMQ 큐 컨슈밍, 캐시 워밍업 등 비동기 작업 검증 시:

```kotlin
await()
    .atMost(Duration.ofSeconds(10))
    .pollInterval(Duration.ofMillis(200))
    .untilAsserted {
        val result = repository.findById(id)
        assertThat(result).isNotNull
    }
```

**`Thread.sleep()` 쓰면 CI 환경에서 100% 플래키 된다.**

---

## 5. 테스트 간 순서 의존 금지

| 규칙 | 설명 |
|------|------|
| 각 테스트는 독립적 | 실행 순서 상관없이 통과해야 함 |
| `@Order` 금지 | 순서 의존성 유발 |
| 테스트 A 결과 → 테스트 B 의존 | 금지 |
| `@BeforeEach`에서 데이터 세팅 | 각 테스트마다 필요한 데이터 직접 준비 |

---

## 6. PGMQ 테스트 주의사항

PGMQ 큐는 테스트 간 공유되므로:

```kotlin
@BeforeEach
fun setUp() {
    // 매 테스트 전 큐 비우기
    pgmqTemplate.purge("calculation-queue")

    // 또는 큐 이름에 테스트별 유니크 접미사
    val queueName = "calculation-queue-${UUID.randomUUID()}"
}
```

---

## 7. CI 환경 설정

### .github/workflows

```yaml
# withReuse(true)는 CI에서 무시됨 (정상 동작)
# 컨테이너 병렬 시작으로 CI 속도 향상
```

### build.gradle.kts

```kotlin
tasks.test {
    useJUnitPlatform()
    // Gradle 병렬 테스트
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2)
        .coerceAtLeast(1)
}
```

---

## 8. 전체 예제

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("pgtest")
class MyIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var myService: MyService

    @Test
    fun `비동기 처리 테스트`() {
        // Given
        val request = MyRequest(id = 1L, data = "test")

        // When
        myService.processAsync(request)

        // Then - Awaitility로 비동기 검증
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted {
                val result = myRepository.findById(1L)
                assertThat(result).isPresent
                assertThat(result.get().status).isEqualTo("COMPLETED")
            }
    }
}
```

---

## 참고 문서

- [Testcontainers 공식 문서](https://www.testcontainers.org/)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Awaitility](https://github.com/awaitility/awaitility)
