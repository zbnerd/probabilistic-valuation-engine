package maple.expectation.testinfra

import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 컨테이너 싱글톤 검증 테스트
 *
 * <p>Testcontainers 컨테이너가 JVM당 정확히 1개만 실행되는지 검증한다.
 */
@Tag("infra-verification")
class ContainerSingletonTest : IntegrationTestBase() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `PostgreSQL 컨테이너가 실행 중이어야 함`() {
        // 연결이 되면 컨테이너가 실행 중인 것
        val result = jdbcTemplate.queryForObject(
            "SELECT 1",
            Int::class.java,
        )
        assertThat(result).isEqualTo(1)
    }

    @Test
    fun `PGMQ 확장이 설치되어 있어야 함`() {
        val result = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_extension WHERE extname = 'pgmq'",
            Int::class.java,
        )
        assertThat(result).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `필요한 큐가 생성되어 있어야 함`() {
        val queues = jdbcTemplate.queryForList(
            "SELECT queue_name FROM pgmq.meta",
            String::class.java,
        )

        // init-pgmq.sql에서 생성한 큐들
        assertThat(queues).contains("nexon_retry_queue")
    }
}
