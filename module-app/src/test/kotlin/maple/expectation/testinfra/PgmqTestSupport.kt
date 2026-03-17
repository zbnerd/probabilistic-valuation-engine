package maple.expectation.testinfra

import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * PGMQ 테스트 유틸리티
 *
 * <h3>역할</h3>
 * <p>PGMQ 통합 테스트를 위한 헬퍼 메서드 제공
 *
 * <h3>기능</h3>
 * <ul>
 *   <li>테스트용 큐 생성 및 삭제
 *   <li>큐 정리 (purge)
 *   <li>메시지 발행 및 큐 사이즈 조회
 * </ul>
 */
@Component
class PgmqTestSupport(
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val pgmqClient: PgmqClient,
) {

    companion object {
        private val log = LoggerFactory.getLogger(PgmqTestSupport::class.java)
    }

    /**
     * 테스트용 큐 생성
     *
     * @param queueName 큐 이름
     */
    fun createQueue(queueName: String) {
        try {
            jdbcTemplate.execute("SELECT pgmq.create('$queueName')")
            log.debug("[PgmqTestSupport] Queue created: $queueName")
        } catch (e: Exception) {
            log.debug("[PgmqTestSupport] Queue already exists: $queueName")
        }
    }

    /**
     * 큐 삭제
     *
     * @param queueName 큐 이름
     */
    fun dropQueue(queueName: String) {
        try {
            jdbcTemplate.execute("SELECT pgmq.drop_queue('$queueName')")
            log.debug("[PgmqTestSupport] Queue dropped: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to drop queue $queueName: ${e.message}")
        }
    }

    /**
     * 큐 비우기
     *
     * @param queueName 큐 이름
     */
    fun purgeQueue(queueName: String) {
        try {
            jdbcTemplate.execute("DELETE FROM pgmq.q_$queueName")
            log.debug("[PgmqTestSupport] Queue purged: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to purge queue $queueName: ${e.message}")
        }
    }

    /**
     * 큐 사이즈 조회
     *
     * @param queueName 큐 이름
     * @return 메시지 수
     */
    fun getQueueSize(queueName: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queueName",
            Long::class.java,
        ) ?: 0L
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to get queue size for $queueName: ${e.message}")
        0L
    }

    /**
     * 아카이브된 메시지 수 조회
     *
     * @param queueName 큐 이름
     * @return 아카이브된 메시지 수
     */
    fun getArchiveSize(queueName: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.a_$queueName",
            Long::class.java,
        ) ?: 0L
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to get archive size for $queueName: ${e.message}")
        0L
    }

    /**
     * 메시지 발행
     *
     * @param queueName 큐 이름
     * @param message 메시지 객체
     * @return 메시지 ID
     */
    fun <T : Any> sendMessage(queueName: String, message: T): Long = pgmqClient.send(queueName, message)

    /**
     * 메시지 읽기
     *
     * @param queueName 큐 이름
     * @param clazz 메시지 클래스
     * @param vtSec Visibility Timeout (초)
     * @return 메시지 목록
     */
    fun <T : Any> readMessages(
        queueName: String,
        clazz: Class<T>,
        vtSec: Int = 10,
    ): List<PgmqMessage<T>> = pgmqClient.read(queueName, clazz, batchSize = 1, visibilityTimeoutSec = vtSec)

    /**
     * 메시지 보관
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 성공 여부
     */
    fun archiveMessage(queueName: String, messageId: Long): Boolean = pgmqClient.archive(queueName, messageId)

    /**
     * 메시지 삭제
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 성공 여부
     */
    fun deleteMessage(queueName: String, messageId: Long): Boolean = pgmqClient.delete(queueName, messageId)

    /**
     * 큐 생성 및 정리 (setUp용)
     *
     * @param queueName 큐 이름
     */
    fun setUpQueue(queueName: String) {
        createQueue(queueName)
        purgeQueue(queueName)
    }

    /**
     * 큐 삭제 (tearDown용)
     *
     * @param queueName 큐 이름
     */
    fun tearDownQueue(queueName: String) {
        purgeQueue(queueName)
        // 큐 자체는 삭제하지 않음 (다른 테스트에서 사용 가능)
    }
}
