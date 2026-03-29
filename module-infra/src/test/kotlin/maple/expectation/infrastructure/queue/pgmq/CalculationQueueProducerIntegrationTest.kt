package maple.expectation.infrastructure.queue.pgmq

import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.test.ServiceIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * CalculationQueueProducer 통합 테스트
 *
 * <h3>테스트 목표</h3>
 * <ul>
 *   <li>트랜잭션 커밋 시 메시지가 큐에 유지되는지 검증</li>
 *   <li>트랜잭션 롤백 시 메시지가 발행되지 않는지 검증</li>
 *   <li>일괄 발행(batch publish) 기능 검증</li>
 * </ul>
 *
 * <h3>테스트 전략</h3>
 * <ul>
 *   <li>ServiceIntegrationTestBase 상속 - @Transactional 롤백 지원</li>
 *   <li>PgmqClient로 직접 큐 조회하여 검증</li>
 *   <li>flushAndClear()로 영속성 컨텍스트 정리 후 DB 상태 확인</li>
 * </ul>
 *
 * @see CalculationQueueProducer
 * @see ServiceIntegrationTestBase
 */
@Tag("pgmq")
@DisplayName("CalculationQueueProducer 통합 테스트")
class CalculationQueueProducerIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    private lateinit var producer: CalculationQueueProducer

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    override fun setUp() {
        super.setUp()
        // 테스트 격리를 위해 큐 비우기
        purgeQueue()
    }

    // ================================
    // Transaction Commit Tests
    // ================================

    @Test
    @DisplayName("트랜잭션 커밋: 메시지가 큐에 유지된다")
    fun `transaction commit persists message in queue`() {
        // given
        val ocid = "test-ocid-commit"
        val userIgn = "commit-test-user"

        // when - @Transactional 메서드 내에서 발행
        val messageId = transactionTemplate.execute { status ->
            val id = producer.publish(
                ocid = ocid,
                userIgn = userIgn,
                presetNo = 1,
                forceRecalculation = false,
            )
            id
        }!!

        // then - flushAndClear 후 큐 조회
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.ocid).isEqualTo(ocid)
        assertThat(messages[0].payload.userIgn).isEqualTo(userIgn)
    }

    @Test
    @DisplayName("트랜잭션 커밋: 여러 메시지가 순서대로 발행된다")
    fun `transaction commit publishes multiple messages in order`() {
        // given
        val requests = listOf(
            CalculationRequest("ocid-1", "user-1", 1, false, "2026-03-15T10:00:00Z"),
            CalculationRequest("ocid-2", "user-2", 1, false, "2026-03-15T10:01:00Z"),
            CalculationRequest("ocid-3", "user-3", 1, false, "2026-03-15T10:02:00Z"),
        )

        // when
        val messageIds = transactionTemplate.execute { status ->
            producer.publishBatch(requests)
        }!!

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(3)
        assertThat(messageIds).containsExactlyElementsOf(messages.map { it.messageId })

        // 순서 검증
        assertThat(messages[0].payload.ocid).isEqualTo("ocid-1")
        assertThat(messages[1].payload.ocid).isEqualTo("ocid-2")
        assertThat(messages[2].payload.ocid).isEqualTo("ocid-3")
    }

    // ================================
    // Transaction Rollback Tests
    // ================================

    @Test
    @DisplayName("트랜잭션 롤백: 메시지가 발행되지 않는다")
    fun `transaction rollback does not publish message`() {
        // given
        val ocid = "test-ocid-rollback"
        val userIgn = "rollback-test-user"

        // when - 롤백 유발
        try {
            transactionTemplate.execute { status ->
                producer.publish(
                    ocid = ocid,
                    userIgn = userIgn,
                    presetNo = 1,
                    forceRecalculation = false,
                )
                // 명시적 롤백
                status.setRollbackOnly()
                null
            }
        } catch (e: Exception) {
            // 예외 무시 (롤백 검증이 목적)
        }

        // then - 큐가 비어있어야 함
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).isEmpty()
    }

    @Test
    @DisplayName("트랜잭션 롤백: 일괄 발행 메시지 모두 취소된다")
    fun `transaction rollback cancels batch publish`() {
        // given
        val requests = listOf(
            CalculationRequest("ocid-1", "user-1", 1, false, "2026-03-15T10:00:00Z"),
            CalculationRequest("ocid-2", "user-2", 1, false, "2026-03-15T10:01:00Z"),
            CalculationRequest("ocid-3", "user-3", 1, false, "2026-03-15T10:02:00Z"),
        )

        // when - 롤백 유발
        try {
            transactionTemplate.execute { status ->
                producer.publishBatch(requests)
                status.setRollbackOnly()
                null
            }
        } catch (e: Exception) {
            // 예외 무시
        }

        // then - 모든 메시지가 발행되지 않아야 함
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).isEmpty()
    }

    @Test
    @DisplayName("예외 발생 시 트랜잭션 롤백으로 메시지 미발행")
    fun `exception causes rollback and message not published`() {
        // given
        val ocid = "test-ocid-exception"
        val userIgn = "exception-test-user"

        // when - 예외 발생
        try {
            transactionTemplate.execute { status ->
                producer.publish(
                    ocid = ocid,
                    userIgn = userIgn,
                    presetNo = 1,
                    forceRecalculation = false,
                )
                throw RuntimeException("Simulated error for rollback")
            }
        } catch (e: RuntimeException) {
            // 예외 무시
        }

        // then - 메시지가 발행되지 않아야 함
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).isEmpty()
    }

    // ================================
    // Batch Publish Tests
    // ================================

    @Test
    @DisplayName("일괄 발행: 여러 메시지를 한 번에 발행한다")
    fun `batch publish publishes multiple messages at once`() {
        // given
        val requests = (1..5).map { i ->
            CalculationRequest("ocid-$i", "user-$i", i, true, "2026-03-15T10:0$i:00Z")
        }

        // when
        val messageIds = producer.publishBatch(requests)

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(5)
        assertThat(messageIds).hasSize(5)

        // 모든 메시지 ID가 유효한지 검증
        assertThat(messageIds).allSatisfy { id ->
            assertThat(id).isPositive()
        }

        // 페이로드 검증
        messages.forEachIndexed { index, message ->
            assertThat(message.payload.ocid).isEqualTo("ocid-${index + 1}")
            assertThat(message.payload.userIgn).isEqualTo("user-${index + 1}")
            assertThat(message.payload.presetNo).isEqualTo(index + 1)
            assertThat(message.payload.forceRecalculation).isTrue
        }
    }

    @Test
    @DisplayName("일괄 발행: 빈 리스트 처리")
    fun `batch publish handles empty list`() {
        // given
        val emptyRequests = emptyList<CalculationRequest>()

        // when
        val messageIds = producer.publishBatch(emptyRequests)

        // then
        assertThat(messageIds).isEmpty()
        flushAndClear()
        val messages = readAllMessages()
        assertThat(messages).isEmpty()
    }

    @Test
    @DisplayName("일괄 발행: 단일 메시지도 처리 가능")
    fun `batch publish handles single message`() {
        // given
        val singleRequest = listOf(
            CalculationRequest("ocid-single", "user-single", 1, false, "2026-03-15T10:00:00Z"),
        )

        // when
        val messageIds = producer.publishBatch(singleRequest)

        // then
        assertThat(messageIds).hasSize(1)
        flushAndClear()
        val messages = readAllMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].payload.ocid).isEqualTo("ocid-single")
    }

    // ================================
    // Integration Tests (Non-Transaction)
    // ================================

    @Test
    @DisplayName("발행된 메시지의 메타데이터가 올바르게 설정된다")
    fun `published message has correct metadata`() {
        // given
        val ocid = "test-ocid-metadata"
        val userIgn = "metadata-test-user"

        // when
        val messageId = producer.publish(
            ocid = ocid,
            userIgn = userIgn,
            presetNo = 3,
            forceRecalculation = true,
        )

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        val message = messages[0]

        // 메타데이터 검증
        assertThat(message.messageId).isEqualTo(messageId)
        assertThat(message.readCount).isEqualTo(0) // 초기 읽기 횟수는 0
        assertThat(message.enqueuedAt).isNotNull // 큐에 추가된 시점
        assertThat(message.visibilityTimeout).isNotNull // VT 설정됨

        // 페이로드 검증
        assertThat(message.payload.ocid).isEqualTo(ocid)
        assertThat(message.payload.userIgn).isEqualTo(userIgn)
        assertThat(message.payload.presetNo).isEqualTo(3)
        assertThat(message.payload.forceRecalculation).isTrue
        assertThat(message.payload.requestedAt).isNotEmpty()
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * 큐에서 모든 메시지 읽기
     *
     * @return 큐의 모든 메시지 목록
     */
    private fun readAllMessages(): List<PgmqMessage<CalculationRequest>> = pgmqClient.read(
        CalculationQueueProducer.QUEUE_NAME,
        CalculationRequest::class.java,
        batchSize = 100,
        visibilityTimeoutSec = 1,
    )

    /**
     * 큐 비우기 (테스트 격리용)
     *
     * 모든 메시지를 읽고 보관하여 큐를 정리
     */
    private fun purgeQueue() {
        var hasMessages = true
        while (hasMessages) {
            val messages = pgmqClient.read(
                CalculationQueueProducer.QUEUE_NAME,
                CalculationRequest::class.java,
                batchSize = 100,
                visibilityTimeoutSec = 1,
            )
            if (messages.isEmpty()) {
                hasMessages = false
            } else {
                // 메시지 보관 (삭제)
                messages.forEach { msg ->
                    pgmqClient.archive(CalculationQueueProducer.QUEUE_NAME, msg.messageId)
                }
            }
        }
    }
}
