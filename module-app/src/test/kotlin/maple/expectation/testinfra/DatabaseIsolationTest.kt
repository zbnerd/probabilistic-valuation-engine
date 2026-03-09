package maple.expectation.testinfra

import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * DB 격리 검증 테스트
 *
 * <p>DatabaseCleaner가 테스트 간 데이터를 완전히 삭제하는지 검증한다.
 */
@Tag("infra-verification")
class DatabaseIsolationTest : IntegrationTestBase() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        private const val TEST_TABLE = "test_isolation_table"
    }

    @Test
    fun `테스트 1 - 데이터 삽입 후 조회`() {
        // 테이블 생성 (없으면)
        createTableIfNotExists()

        // 데이터 삽입
        jdbcTemplate.execute(
            "INSERT INTO $TEST_TABLE (name, value) VALUES ('test-1', 100)",
        )

        // 조회
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $TEST_TABLE WHERE name = 'test-1'",
            Int::class.java,
        )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `테스트 2 - 이전 테스트 데이터가 없어야 함`() {
        // @BeforeEach에서 databaseCleaner.clean() 호출됨
        // 테이블 생성 (없으면)
        createTableIfNotExists()

        // 이전 테스트의 데이터가 없어야 함
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $TEST_TABLE",
            Int::class.java,
        )
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `테스트 3 - 여러 데이터 삽입 후 격리 확인`() {
        // 테이블 생성 (없으면)
        createTableIfNotExists()

        // 여러 데이터 삽입
        jdbcTemplate.execute(
            "INSERT INTO $TEST_TABLE (name, value) VALUES ('test-3-a', 100)",
        )
        jdbcTemplate.execute(
            "INSERT INTO $TEST_TABLE (name, value) VALUES ('test-3-b', 200)",
        )

        // 조회
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $TEST_TABLE WHERE name LIKE 'test-3-%'",
            Int::class.java,
        )
        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `테스트 4 - 테스트 3의 데이터가 없어야 함`() {
        // @BeforeEach에서 databaseCleaner.clean() 호출됨
        // 테이블 생성 (없으면)
        createTableIfNotExists()

        // 이전 테스트의 데이터가 없어야 함
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $TEST_TABLE WHERE name LIKE 'test-3-%'",
            Int::class.java,
        )
        assertThat(count).isEqualTo(0)
    }

    private fun createTableIfNotExists() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS $TEST_TABLE (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                value INTEGER NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
    }
}
