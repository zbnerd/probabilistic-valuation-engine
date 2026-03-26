package maple.expectation.test

import jakarta.persistence.EntityManager
import maple.expectation.config.TestcontainersConfiguration.Companion.postgresContainer
import maple.expectation.core.port.out.EventPublisher
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.messaging.PgmqStreamPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional

/**
 * Repository 통합 테스트 베이스 클래스
 *
 * <h3>사용 규칙</h3>
 * <ul>
 *   <li>모든 Repository 통합 테스트는 이 클래스를 상속
 *   <li>@Transactional 테스트 메서드에서 롤백 자동 수행
 *   <li>flushAndClear() 호출로 영속성 컨텍스트 정리
 * </ul>
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>WebEnvironment.NONE - 서버 시작 없이 DB만 사용
 *   <li>@Transactional - 테스트 후 자동 롤백
 *   <li>ServiceIntegrationTestBase 확장 - 동일한 기능 제공
 * </ul>
 *
 * @see IntegrationTestBase
 * @see ServiceIntegrationTestBase
 */
@Tag("integration")
@Tag("repository")
@DisplayName("Repository 통합 테스트")
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
@Transactional("transactionManager")
abstract class RepositoryIntegrationTestBase {

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
