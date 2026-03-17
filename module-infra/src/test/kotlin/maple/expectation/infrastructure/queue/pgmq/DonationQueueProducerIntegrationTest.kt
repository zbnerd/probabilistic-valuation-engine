package maple.expectation.infrastructure.queue.pgmq

import maple.expectation.infrastructure.pgmq.DonationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.test.ServiceIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * DonationQueueProducer 통합 테스트
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
 * @see DonationQueueProducer
 * @see ServiceIntegrationTestBase
 */
@DisplayName("DonationQueueProducer 통합 테스트")
class DonationQueueProducerIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    private lateinit var producer: DonationQueueProducer

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
        val donationId = 12345L
        val userId = 1L
        val amount = 1000L
        val message = "응원합니다"

        // when - @Transactional 메서드 내에서 발행
        val messageId = transactionTemplate.execute { status ->
            val id = producer.publish(
                donationId = donationId,
                userId = userId,
                amount = amount,
                message = message,
            )
            id
        }!!

        // then - flushAndClear 후 큐 조회
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.donationId).isEqualTo(donationId)
        assertThat(messages[0].payload.userId).isEqualTo(userId)
        assertThat(messages[0].payload.amount).isEqualTo(amount)
        assertThat(messages[0].payload.message).isEqualTo(message)
    }

    @Test
    @DisplayName("트랜잭션 커밋: 메시지 없이 발행된다")
    fun `transaction commit publishes message without message text`() {
        // given
        val donationId = 12346L
        val userId = 2L
        val amount = 5000L

        // when
        val messageId = transactionTemplate.execute { status ->
            producer.publish(
                donationId = donationId,
                userId = userId,
                amount = amount,
                message = null,
            )
        }!!

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.donationId).isEqualTo(donationId)
        assertThat(messages[0].payload.userId).isEqualTo(userId)
        assertThat(messages[0].payload.amount).isEqualTo(amount)
        assertThat(messages[0].payload.message).isNull()
    }

    @Test
    @DisplayName("트랜잭션 커밋: 여러 메시지가 순서대로 발행된다")
    fun `transaction commit publishes multiple messages in order`() {
        // given
        val requests = listOf(
            DonationRequest(1L, 10L, 1000L, "메시지1", "2026-03-15T10:00:00Z"),
            DonationRequest(2L, 11L, 2000L, "메시지2", "2026-03-15T10:01:00Z"),
            DonationRequest(3L, 12L, 3000L, "메시지3", "2026-03-15T10:02:00Z"),
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
        assertThat(messages[0].payload.donationId).isEqualTo(1L)
        assertThat(messages[1].payload.donationId).isEqualTo(2L)
        assertThat(messages[2].payload.donationId).isEqualTo(3L)
    }

    // ================================
    // Transaction Rollback Tests
    // ================================

    @Test
    @DisplayName("트랜잭션 롤백: 메시지가 발행되지 않는다")
    fun `transaction rollback does not publish message`() {
        // given
        val donationId = 99999L
        val userId = 999L
        val amount = 100L

        // when - 롤백 유발
        try {
            transactionTemplate.execute { status ->
                producer.publish(
                    donationId = donationId,
                    userId = userId,
                    amount = amount,
                    message = "롤백 테스트",
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
            DonationRequest(1L, 10L, 1000L, "메시지1", "2026-03-15T10:00:00Z"),
            DonationRequest(2L, 11L, 2000L, "메시지2", "2026-03-15T10:01:00Z"),
            DonationRequest(3L, 12L, 3000L, "메시지3", "2026-03-15T10:02:00Z"),
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
        val donationId = 88888L
        val userId = 888L
        val amount = 500L

        // when - 예외 발생
        try {
            transactionTemplate.execute { status ->
                producer.publish(
                    donationId = donationId,
                    userId = userId,
                    amount = amount,
                    message = "예외 테스트",
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
            DonationRequest(
                donationId = i.toLong(),
                userId = (i * 10).toLong(),
                amount = (i * 1000).toLong(),
                message = "기부 메시지 $i",
                requestedAt = "2026-03-15T10:0$i:00Z",
            )
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
            assertThat(message.payload.donationId).isEqualTo((index + 1).toLong())
            assertThat(message.payload.userId).isEqualTo(((index + 1) * 10).toLong())
            assertThat(message.payload.amount).isEqualTo(((index + 1) * 1000).toLong())
            assertThat(message.payload.message).isEqualTo("기부 메시지 ${index + 1}")
        }
    }

    @Test
    @DisplayName("일괄 발행: 빈 리스트 처리")
    fun `batch publish handles empty list`() {
        // given
        val emptyRequests = emptyList<DonationRequest>()

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
            DonationRequest(12345L, 1L, 1000L, "단일 메시지", "2026-03-15T10:00:00Z"),
        )

        // when
        val messageIds = producer.publishBatch(singleRequest)

        // then
        assertThat(messageIds).hasSize(1)
        flushAndClear()
        val messages = readAllMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].payload.donationId).isEqualTo(12345L)
    }

    // ================================
    // Integration Tests (Non-Transaction)
    // ================================

    @Test
    @DisplayName("발행된 메시지의 메타데이터가 올바르게 설정된다")
    fun `published message has correct metadata`() {
        // given
        val donationId = 54321L
        val userId = 999L
        val amount = 10000L
        val message = "대형 기부"

        // when
        val messageId = producer.publish(
            donationId = donationId,
            userId = userId,
            amount = amount,
            message = message,
        )

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        val msg = messages[0]

        // 메타데이터 검증
        assertThat(msg.messageId).isEqualTo(messageId)
        assertThat(msg.readCount).isEqualTo(0) // 초기 읽기 횟수는 0
        assertThat(msg.enqueuedAt).isNotNull // 큐에 추가된 시점
        assertThat(msg.visibilityTimeout).isNotNull // VT 설정됨

        // 페이로드 검증
        assertThat(msg.payload.donationId).isEqualTo(donationId)
        assertThat(msg.payload.userId).isEqualTo(userId)
        assertThat(msg.payload.amount).isEqualTo(amount)
        assertThat(msg.payload.message).isEqualTo(message)
        assertThat(msg.payload.requestedAt).isNotEmpty()
    }

    @Test
    @DisplayName("다양한 금액의 기부 메시지 발행")
    fun `publishes various donation amounts`() {
        // given
        val amounts = listOf(1L, 100L, 1000L, 10000L, 100000L)

        // when
        amounts.forEach { amount ->
            producer.publish(
                donationId = amount,
                userId = 1L,
                amount = amount,
                message = null,
            )
        }

        // then
        flushAndClear()
        val messages = readAllMessages()

        assertThat(messages).hasSize(5)
        assertThat(messages.map { it.payload.amount }).containsExactlyInAnyOrderElementsOf(amounts)
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * 큐에서 모든 메시지 읽기
     *
     * @return 큐의 모든 메시지 목록
     */
    private fun readAllMessages(): List<PgmqMessage<DonationRequest>> = pgmqClient.read(
        DonationQueueProducer.QUEUE_NAME,
        DonationRequest::class.java,
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
                DonationQueueProducer.QUEUE_NAME,
                DonationRequest::class.java,
                batchSize = 100,
                visibilityTimeoutSec = 1,
            )
            if (messages.isEmpty()) {
                hasMessages = false
            } else {
                // 메시지 보관 (삭제)
                messages.forEach { msg ->
                    pgmqClient.archive(DonationQueueProducer.QUEUE_NAME, msg.messageId)
                }
            }
        }
    }
}
