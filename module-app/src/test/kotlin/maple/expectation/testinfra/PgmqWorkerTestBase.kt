package maple.expectation.testinfra

import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.test.ServiceIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PGMQ Worker 통합 테스트 베이스 클래스
 *
 * <h3>역할</h3>
 * <p>PGMQ Worker를 테스트하는 모든 통합 테스트의 기본 클래스
 *
 * <h3>기능</h3>
 * <ul>
 *   <li>테스트용 큐 자동 설정 및 정리
 *   <li>메시지 발행 헬퍼
 *   <li>메시지 처리 대기 헬퍼 (Awaitility 기반)
 *   <li>메시지 상태 검증 헬퍼 (아카이브/삭제)
 * </ul>
 *
 * <h3>사용법</h3>
 * <pre>
 * class CalculationWorkerIntegrationTest : PgmqWorkerTestBase() {
 *
 *     &#64;Autowired
 *     lateinit var calculationWorker: CalculationWorker
 *
 *     &#64;Test
 *     fun `메시지를 처리하고 아카이브한다`() {
 *         // Given
 *         val request = CalculationRequest("ocid", "ign", requestedAt = Instant.now().toString())
 *         val messageId = injectMessage(calculationWorker.queueName, request)
 *
 *         // When
 *         awaitMessageProcessed(calculationWorker.queueName, timeoutMs = 5000)
 *
 *         // Then
 *         assertMessageArchived(calculationWorker.queueName, messageId)
 *     }
 * }
 * </pre>
 *
 * <h3>설정</h3>
 * <p>application-pgmq-test.yml에서 VT=1초로 설정하여 빠른 테스트 가능
 *
 * @see PgmqTestSupport PGMQ 테스트 유틸리티
 * @see ServiceIntegrationTestBase 서비스 통합 테스트 베이스
 */
abstract class PgmqWorkerTestBase : ServiceIntegrationTestBase() {

    @Autowired
    lateinit var pgmqTestSupport: PgmqTestSupport

    @Autowired
    lateinit var pgmqClient: PgmqClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    /**
     * 테스트 대상 Worker의 큐 이름
     *
     * <p>각 테스트 클래스에서 오버라이드하여 큐 이름 지정
     */
    protected abstract val queueName: String

    /**
     * 테스트용 큐 설정
     *
     * <p>@BeforeEach에서 큐를 생성하고 비움
     */
    @BeforeEach
    fun setUpQueue() {
        pgmqTestSupport.setUpQueue(queueName)
        log.debug("[PgmqWorkerTestBase] Queue set up: $queueName")
    }

    /**
     * 큐에 메시지 발행
     *
     * @param queueName 큐 이름 (기본값: 테스트 클래스의 queueName)
     * @param message 메시지 객체
     * @return 메시지 ID
     */
    protected fun <T : Any> injectMessage(
        queueName: String = this.queueName,
        message: T,
    ): Long {
        val messageId = pgmqTestSupport.sendMessage(queueName, message)
        log.debug("[PgmqWorkerTestBase] Message injected: queue={}, msgId={}", queueName, messageId)
        return messageId
    }

    /**
     * 메시지가 처리될 때까지 대기
     *
     * <p>큐가 비어질 때까지 대기하여 메시지 처리 완료를 확인
     *
     * @param queueName 큐 이름 (기본값: 테스트 클래스의 queueName)
     * @param timeoutMs 타임아웃 (밀리초, 기본값: 5000)
     * @param pollIntervalMs 폴링 간격 (밀리초, 기본값: 100)
     */
    protected fun awaitMessageProcessed(
        queueName: String = this.queueName,
        timeoutMs: Long = 5000,
        pollIntervalMs: Long = 100,
    ) {
        await()
            .atMost(timeoutMs, TimeUnit.MILLISECONDS)
            .pollInterval(pollIntervalMs, TimeUnit.MILLISECONDS)
            .until {
                pgmqTestSupport.getQueueSize(queueName) == 0L
            }

        log.debug("[PgmqWorkerTestBase] Message processed: queue={}", queueName)
    }

    /**
     * 메시지가 아카이브되었는지 검증
     *
     * <p>아카이브 테이블(a_queue_name)에서 메시지 ID로 검증
     *
     * @param queueName 큐 이름 (기본값: 테스트 클래스의 queueName)
     * @param messageId 메시지 ID
     */
    protected fun assertMessageArchived(
        queueName: String = this.queueName,
        messageId: Long,
    ) {
        val archived = isMessageInArchive(queueName, messageId)
        assertThat(archived)
            .withFailMessage("Message should be archived: queue=%s, msgId=%s", queueName, messageId)
            .isTrue

        log.debug("[PgmqWorkerTestBase] Message archived verified: queue={}, msgId={}", queueName, messageId)
    }

    /**
     * 메시지가 삭제되었는지 검증
     *
     * <p>큐와 아카이브 테이블 모두에서 메시지가 없는지 확인
     *
     * @param queueName 큐 이름 (기본값: 테스트 클래스의 queueName)
     * @param messageId 메시지 ID
     */
    protected fun assertMessageDeleted(
        queueName: String = this.queueName,
        messageId: Long,
    ) {
        val inQueue = isMessageInQueue(queueName, messageId)
        val inArchive = isMessageInArchive(queueName, messageId)

        assertThat(inQueue)
            .withFailMessage("Message should not be in queue: queue=%s, msgId=%s", queueName, messageId)
            .isFalse

        assertThat(inArchive)
            .withFailMessage("Message should not be in archive: queue=%s, msgId=%s", queueName, messageId)
            .isFalse

        log.debug("[PgmqWorkerTestBase] Message deleted verified: queue={}, msgId={}", queueName, messageId)
    }

    /**
     * 메시지가 큐에 있는지 확인
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 메시지가 큐에 있으면 true
     */
    protected fun isMessageInQueue(queueName: String, messageId: Long): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queueName WHERE msg_id = ?",
            Long::class.java,
            messageId,
        ) ?: 0L

        return count > 0
    }

    /**
     * 메시지가 아카이브에 있는지 확인
     *
     * @param queueName 큐 이름
     * @param messageId 메시지 ID
     * @return 메시지가 아카이브에 있으면 true
     */
    protected fun isMessageInArchive(queueName: String, messageId: Long): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.a_$queueName WHERE msg_id = ?",
            Long::class.java,
            messageId,
        ) ?: 0L

        return count > 0
    }

    /**
     * 큐에서 직접 메시지 읽기 (테스트용)
     *
     * <p>Worker 처리 없이 직접 메시지 상태 확인
     *
     * @param queueName 큐 이름 (기본값: 테스트 클래스의 queueName)
     * @param clazz 메시지 페이로드 클래스
     * @param vtSec Visibility Timeout (초)
     * @return 메시지 목록
     */
    protected fun <T : Any> readMessagesDirectly(
        queueName: String = this.queueName,
        clazz: Class<T>,
        vtSec: Int = 10,
    ): List<PgmqMessage<T>> = pgmqClient.read(queueName, clazz, batchSize = 10, visibilityTimeoutSec = vtSec)

    companion object {
        private val log = LoggerFactory.getLogger(PgmqWorkerTestBase::class.java)
    }
}
