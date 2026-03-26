package maple.expectation.test

import jakarta.persistence.EntityManager
import maple.expectation.config.DatabaseCleaner
import maple.expectation.config.TestcontainersConfiguration.Companion.postgresContainer
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.messaging.PgmqStreamPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 통합 테스트 베이스 클래스
 *
 * <h3>규칙 준수 (Issue #547)</h3>
 *
 * <ul>
 *   <li>모든 통합 테스트는 이 클래스를 상속
 *   <li>@BeforeEach에서 DatabaseCleaner.clean() 호출 - 테스트 격리
 *   <li>RANDOM_PORT - 포트 충돌 방지, 실제 HTTP 서버로 E2E 테스트
 *   <li>ActiveProfiles("pgtest") - PostgreSQL Testcontainers 프로필
 *   <li>@MockBean 사용 금지 - Context 캐싱 유지
 *   <li>@Transactional 사용 금지 - RANDOM_PORT에서는 롤백 안 됨
 * </ul>
 *
 * <h3>WebEnvironment 선택 기준</h3>
 * <ul>
 *   <li>RANDOM_PORT: API 통합 테스트 (Controller → Service → DB 전체)
 *   <li>NONE: Service 레벨 통합 테스트 (DB만 필요)
 *   <li>@WebMvcTest: Controller 단위 테스트 (MockMvc)
 * </ul>
 *
 * @see DatabaseCleaner
 * @see PostgresContainerBaseTest
 */
@Tag("integration")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=20",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=2",
        "lock.datasource.pool-size=2",
    ],
)
@ActiveProfiles("pgtest")
abstract class IntegrationTestBase {

    companion object {
        /**
         * PostgreSQL 동적 프로퍼티 설정
         *
         * 컨테이너는 TestcontainersConfiguration에서 싱글톤으로 시작됨.
         * 여기서는 동적 포트만 Spring Environment에 등록.
         */
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.url") { postgresContainer.jdbcUrl }
            registry.add("spring.datasource.username") { postgresContainer.username }
            registry.add("spring.datasource.password") { postgresContainer.password }
        }
    }

    // Mock TieredCacheManager to avoid Spring context loading issues
    @MockBean
    lateinit var tieredCacheManager: TieredCacheManager

    // Mock PgmqStreamPublisher to avoid dependency on PGMQ infrastructure
    @MockBean
    lateinit var pgmqStreamPublisher: PgmqStreamPublisher

    // Mock EventPublisher to avoid dependency on event publishing infrastructure
    @MockBean
    lateinit var eventPublisher: EventPublisher

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    /**
     * 테스트 격리를 위한 DB 정리
     *
     * 핵심: @AfterEach가 아님. 테스트 실패 시 @AfterEach가 실행 안 될 수 있어서
     * 다음 테스트가 오염됨.
     */
    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }
}

/**
 * Service 레벨 통합 테스트 베이스
 *
 * <p>서버 없이 Service + DB만 테스트할 때 사용.
 * <p>@Transactional 롤백 가능 (같은 스레드에서 실행).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=20",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=2",
        "lock.datasource.pool-size=2",
    ],
)
@ActiveProfiles("pgtest")
abstract class ServiceIntegrationTestBase {

    companion object {
        /**
         * PostgreSQL 동적 프로퍼티 설정
         *
         * 컨테이너는 TestcontainersConfiguration에서 싱글톤으로 시작됨.
         * 여기서는 동적 포트만 Spring Environment에 등록.
         */
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.url") { postgresContainer.jdbcUrl }
            registry.add("spring.datasource.username") { postgresContainer.username }
            registry.add("spring.datasource.password") { postgresContainer.password }
        }
    }

    // Mock TieredCacheManager to avoid Spring context loading issues
    @MockBean
    lateinit var tieredCacheManager: TieredCacheManager

    // Mock PgmqStreamPublisher to avoid dependency on PGMQ infrastructure
    @MockBean
    lateinit var pgmqStreamPublisher: PgmqStreamPublisher

    // Mock EventPublisher to avoid dependency on event publishing infrastructure
    @MockBean
    lateinit var eventPublisher: EventPublisher

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var em: EntityManager

    @BeforeEach
    fun setUp() {
        databaseCleaner.clean()
    }

    /**
     * @Transactional 테스트에서 flush + clear 후 assert
     *
     * <p>flush: DB에 실제로 쓰기 → 제약 조건 검증
     * <p>clear: 1차 캐시 비우기 → DB에서 실제로 읽기
     */
    protected fun flushAndClear() {
        em.flush()
        em.clear()
    }
}
