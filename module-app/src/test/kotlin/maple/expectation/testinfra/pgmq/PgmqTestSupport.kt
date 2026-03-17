package maple.expectation.testinfra.pgmq

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PGMQ 테스트 유틸리티
 *
 * <h3>역할</h3>
 * <p>PGMQ 통합 테스트에서 반복적으로 사용되는 큐 관리 작업을 제공
 *
 * <h3>주요 기능</h3>
 * <ul>
 *   <li>큐 생성 및 삭제</li>
 *   <li>큐 내용 정리 (purge)</li>
 *   <li>큐 상태 조회 (size)</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>
 * &#64;Autowired
 * lateinit var jdbcTemplate: JdbcTemplate
 *
 * val pgmqTestSupport = PgmqTestSupport(jdbcTemplate)
 *
 * // 큐 생성
 * pgmqTestSupport.createQueue("test_queue")
 *
 * // 큐 정리
 * pgmqTestSupport.purgeQueue("test_queue")
 *
 * // 큐 크기 확인
 * val size = pgmqTestSupport.queueSize("test_queue")
 * </pre>
 */
class PgmqTestSupport(
    private val jdbcTemplate: JdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * PGMQ 큐 생성
     *
     * <p>이미 존재하는 큐면 무시합니다.
     *
     * @param queueName 큐 이름
     */
    fun createQueue(queueName: String) {
        try {
            jdbcTemplate.execute("SELECT pgmq.create('$queueName')")
            log.debug("[PgmqTestSupport] Queue created: $queueName")
        } catch (e: Exception) {
            log.debug("[PgmqTestSupport] Queue already exists or creation failed: $queueName - ${e.message}")
        }
    }

    /**
     * PGMQ 큐 삭제
     *
     * @param queueName 큐 이름
     */
    fun dropQueue(queueName: String) {
        try {
            jdbcTemplate.execute("SELECT pgmq.drop_queue('$queueName')")
            log.debug("[PgmqTestSupport] Queue dropped: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to drop queue: $queueName - ${e.message}")
        }
    }

    /**
     * 큐의 모든 메시지 삭제
     *
     * <p>테스트 격리를 위해 각 테스트 전/후에 호출합니다.
     *
     * @param queueName 큐 이름
     */
    fun purgeQueue(queueName: String) {
        try {
            // pgmq.purge 대신 직접 DELETE 사용 (더 확실한 정리)
            jdbcTemplate.execute("DELETE FROM pgmq.q_$queueName")
            log.debug("[PgmqTestSupport] Queue purged: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to purge queue: $queueName - ${e.message}")
        }
    }

    /**
     * 큐의 메시지 수 조회
     *
     * @param queueName 큐 이름
     * @return 메시지 수 (큐가 없으면 0)
     */
    fun queueSize(queueName: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queueName",
            Long::class.java,
        ) ?: 0L
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to get queue size for $queueName: ${e.message}")
        0L // 테이블이 없으면 0
    }

    /**
     * 아카이브 테이블의 메시지 수 조회
     *
     * @param queueName 큐 이름
     * @return 아카이브된 메시지 수 (테이블이 없으면 0)
     */
    fun archiveSize(queueName: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.a_$queueName",
            Long::class.java,
        ) ?: 0L
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to get archive size for $queueName: ${e.message}")
        0L // 테이블이 없으면 0
    }

    /**
     * 큐와 아카이브 테이블 모두 정리
     *
     * @param queueName 큐 이름
     */
    fun purgeAll(queueName: String) {
        purgeQueue(queueName)
        try {
            jdbcTemplate.execute("DELETE FROM pgmq.a_$queueName")
            log.debug("[PgmqTestSupport] Archive table purged: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to purge archive: $queueName - ${e.message}")
        }
    }
}
