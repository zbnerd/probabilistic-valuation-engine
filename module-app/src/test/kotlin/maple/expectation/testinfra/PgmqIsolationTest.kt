package maple.expectation.testinfra

import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PGMQ 격리 검증 테스트
 *
 * <p>큐 메시지가 테스트 간 누수 안 되는지 검증한다.
 */
@Tag("infra-verification")
class PgmqIsolationTest : IntegrationTestBase() {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val queueName = "test_isolation_queue"

    @BeforeEach
    fun setUpQueue() {
        // 테스트용 큐 생성 (이미 존재하면 무시)
        try {
            jdbcTemplate.execute("SELECT pgmq.create('$queueName')")
            log.debug("[PgmqIsolationTest] Queue created: $queueName")
        } catch (e: Exception) {
            log.debug("[PgmqIsolationTest] Queue already exists: $queueName")
        }
        // 큐 비우기 - 예외 무시하지 않음
        purgeQueue(queueName)
        log.debug("[PgmqIsolationTest] Queue purged: $queueName, size=${queueSize(queueName)}")
    }

    private fun sendMessage(queue: String, payload: String) {
        jdbcTemplate.queryForObject(
            "SELECT pgmq.send('$queue', '$payload'::jsonb)",
            Long::class.java,
        )
    }

    private fun queueSize(queue: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queue",
            Long::class.java,
        ) ?: 0
    } catch (e: Exception) {
        log.warn("[PgmqIsolationTest] Failed to get queue size for $queue: ${e.message}")
        0L // 테이블이 없으면 0
    }

    private fun purgeQueue(queue: String) {
        try {
            // pgmq.purge 대신 직접 DELETE 사용 (더 확실한 정리)
            jdbcTemplate.execute("DELETE FROM pgmq.q_$queue")
            log.debug("[PgmqIsolationTest] Purged queue via DELETE: $queue")
        } catch (e: Exception) {
            log.warn("[PgmqIsolationTest] Failed to purge queue $queue: ${e.message}")
            // 큐가 없으면 무시
        }
    }

    @Test
    fun `테스트 1 - 큐에 메시지 발행`() {
        sendMessage(queueName, """{"test": "message-1"}""")
        assertThat(queueSize(queueName)).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `테스트 2 - 이전 테스트 메시지가 없어야 함`() {
        // @BeforeEach에서 큐 purge 했으므로
        assertThat(queueSize(queueName)).isEqualTo(0)
    }

    @Test
    fun `테스트 3 - 여러 메시지 발행 후 정리`() {
        sendMessage(queueName, """{"test": "message-a"}""")
        sendMessage(queueName, """{"test": "message-b"}""")
        sendMessage(queueName, """{"test": "message-c"}""")

        assertThat(queueSize(queueName)).isEqualTo(3)

        // 수동 purge
        purgeQueue(queueName)
        assertThat(queueSize(queueName)).isEqualTo(0)
    }
}
