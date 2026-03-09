package maple.expectation.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Testcontainers 싱글톤 설정
 *
 * <h3>핵심 원칙</h3>
 *
 * <ul>
 *   <li>컨테이너는 JVM 레벨 싱글톤 (companion object + start())
 *   <li>Context는 @ServiceConnection + spring.factories 자동 등록
 *   <li>Context 재생성되어도 컨테이너는 재시작 안 됨
 *   <li>withReuse(true) - 로컬에서 컨테이너 재사용
 * </ul>
 *
 * <h3>사용법</h3>
 *
 * <pre>
 * // src/test/resources/META-INF/spring/org.springframework.boot.test.context.TestConfiguration.imports
 * maple.expectation.config.TestcontainersConfiguration
 *
 * // 테스트에서는 아무것도 안 해도 됨
 * &#64;SpringBootTest
 * class MyTest : IntegrationTestBase() {
 *     // PostgreSQL + PGMQ 자동 연결됨
 * }
 * </pre>
 *
 * @see maple.expectation.support.IntegrationTestBase
 * @see maple.expectation.config.DatabaseCleaner
 */
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
                    "-c",
                    "fsync=off",
                    "-c",
                    "synchronous_commit=off",
                    "-c",
                    "full_page_writes=off",
                    "-c",
                    "max_connections=50",
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
