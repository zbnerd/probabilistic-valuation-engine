package maple.expectation.test

import jakarta.persistence.EntityManager
import maple.expectation.test.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Service 레벨 통합 테스트 베이스
 *
 * <p>서버 없이 Service + DB만 테스트할 때 사용.
 * <p>@Transactional 롤백 가능 (같은 스레드에서 실행).
 *
 * <h3>특징</h3>
 * <ul>
 *   <li>@BeforeEach에서 DB 정리 (테스트 격리)</li>
 *   <li>Hibernate 1차 캐시 비우기 지원 (flushAndClear)</li>
 * </ul>
 *
 * <h3>의존성</h3>
 * <p>이 클래스는 module-app의 TestcontainersConfiguration을 사용합니다.
 * module-app 테스트를 실행할 때 PostgreSQL + PGMQ가 자동으로 시작됩니다.
 *
 * @see DatabaseCleaner
 * @see maple.expectation.config.TestcontainersConfiguration
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = [InfraTestConfiguration::class],
    properties = [
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.properties.hibernate.default_batch_fetch_size=20",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=5",
    ],
)
@Tag("integration")
@ActiveProfiles("test")
abstract class ServiceIntegrationTestBase {

    @Autowired
    lateinit var databaseCleaner: DatabaseCleaner

    @Autowired
    lateinit var em: EntityManager

    @BeforeEach
    open fun setUp() {
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
