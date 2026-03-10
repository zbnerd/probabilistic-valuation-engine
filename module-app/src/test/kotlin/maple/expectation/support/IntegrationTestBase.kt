package maple.expectation.support

import jakarta.persistence.EntityManager
import maple.expectation.config.DatabaseCleaner
import maple.expectation.config.TestcontainersConfiguration
import maple.expectation.config.TestcontainersConfiguration.Companion.redisContainer
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 통합 테스트 베이스 클래스
 *
 * <h3>특징</h3>
 *
 * <ul>
 *   <li>PostgreSQL + PGMQ 자동 실행 (TestcontainersConfiguration)
 *   <li>@BeforeEach에서 DB 정리 (테스트 격리)
 *   <li>Hibernate 1차 캐시 비우기 (영속성 컨텍스트 초기화)
 *   <li>서버 없이 Service + DB만 테스트
 * </ul>
 *
 * <h3>사용법</h3>
 *
 * <pre>
 * class CalculationServiceTest : IntegrationTestBase() {
 *
 *     &#64;Autowired
 *     lateinit var calculationService: CalculationService
 *
 *     &#64;Test
 *     fun `서비스 로직 테스트`() {
 *         // Given
 *         val request = CalculationRequest(...)
 *
 *         // When
 *         val result = calculationService.calculate(request)
 *
 *         // Then
 *         assertThat(result).isNotNull
 *     }
 * }
 * </pre>
 *
 * @see maple.expectation.config.TestcontainersConfiguration
 * @see DatabaseCleaner
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var entityManager: EntityManager

    companion object {
        /**
         * Redis 동적 프로퍼티 설정
         *
         * <p>컨테이너는 TestcontainersConfiguration에서 싱글톤으로 시작됨.
         * 여기서는 동적 포트만 Spring Environment에 등록.
         */
        @JvmStatic
        @DynamicPropertySource
        fun redisProperties(registry: DynamicPropertyRegistry) {
            // 컨테이너가 시작될 때까지 대기 (이미 시작되어 있으면 즉시 반환)
            registry.add("spring.data.redis.host") { redisContainer.host }
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }

        /**
         * PostgreSQL 동적 프로퍼티 설정
         *
         * <p>컨테이너는 TestcontainersConfiguration에서 싱글톤으로 시작됨.
         * 여기서는 동적 포트만 Spring Environment에 등록.
         */
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.url") { maple.expectation.config.TestcontainersConfiguration.postgresContainer.jdbcUrl }
            registry.add("spring.datasource.username") { maple.expectation.config.TestcontainersConfiguration.postgresContainer.username }
            registry.add("spring.datasource.password") { maple.expectation.config.TestcontainersConfiguration.postgresContainer.password }
        }
    }

    /**
     * 각 테스트 전 DB 정리
     *
     * <p>@AfterEach가 아닌 이유: 테스트 실패 시 @AfterEach가 실행 안 될 수 있어서
     * 다음 테스트가 오염됨
     */
    @BeforeEach
    fun cleanUp() {
        databaseCleaner.clean()
        entityManager.clear() // Hibernate 1차 캐시 비우기
    }
}

/**
 * API 통합 테스트 베이스 클래스
 *
 * <h3>특징</h3>
 *
 * <ul>
 *   <li>실제 HTTP 서버 실행 (RANDOM_PORT)
 *   <li>TestRestTemplate 자동 주입
 *   <li>@Transactional 작동 안 함 (서버와 다른 스레드)
 * </ul>
 *
 * <h3>주의사항</h3>
 *
 * <p>RANDOM_PORT에서는 @Transactional이 롤백 안 됨.
 * 반드시 DatabaseCleaner로 @BeforeEach에서 정리해야 함.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class ApiIntegrationTestBase {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var restTemplate: org.springframework.boot.test.web.client.TestRestTemplate

    /**
     * 각 테스트 전 DB 정리
     */
    @BeforeEach
    fun cleanUp() {
        databaseCleaner.clean()
    }

    // @Transactional 여기서 쓰지 마라. RANDOM_PORT에서 안 먹는다.
}
