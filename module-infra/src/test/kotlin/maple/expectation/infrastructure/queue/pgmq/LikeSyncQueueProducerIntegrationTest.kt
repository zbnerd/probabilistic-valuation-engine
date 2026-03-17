package maple.expectation.infrastructure.queue.pgmq

import java.time.Instant
import maple.expectation.infrastructure.pgmq.LikeSyncRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.test.ServiceIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * LikeSyncQueueProducer 통합 테스트
 *
 * <h3>테스트 목표</h3>
 * <ul>
 *   <li>트랜잭션 커밋 시 메시지가 큐에 유지되는지 검증
 *   <li>트랜잭션 롤백 시 메시지가 발행되지 않는지 검증
 *   <li>일괄 발행(batch) 기능 동작 검증
 * </ul>
 *
 * <h3>테스트 전략</h3>
 * <ul>
 *   <li>ServiceIntegrationTestBase 상속 - @Transactional 롤백 지원
 *   <li>PgmqClient로 직접 큐 조회하여 검증
 *   <li>flushAndClear()로 영속성 컨텍스트 정리 후 DB 상태 확인
 * </ul>
 *
 * @see LikeSyncQueueProducer
 * @see ServiceIntegrationTestBase
 */
@Tag("pgmq")
@DisplayName("LikeSyncQueueProducer 통합 테스트")
class LikeSyncQueueProducerIntegrationTest : ServiceIntegrationTestBase() {

    @Autowired
    private lateinit var producer: LikeSyncQueueProducer

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    override fun setUp() {
        // 먼저 상위 클래스의 setUp 호출 (DB 정리)
        super.setUp()
        // 테스트 격리를 위해 큐 비우기
        purgeQueue()
    }

    // ==================== Helper Methods ====================

    /**
     * 큐의 모든 메시지 제거
     */
    private fun purgeQueue() {
        try {
            val messages = pgmqClient.read(
                LikeSyncQueueProducer.QUEUE_NAME,
                LikeSyncRequest::class.java,
                batchSize = 100,
                visibilityTimeoutSec = 1,
            )
            messages.forEach { msg ->
                pgmqClient.archive(LikeSyncQueueProducer.QUEUE_NAME, msg.messageId)
            }
        } catch (e: Exception) {
            // 큐가 비어있거나 존재하지 않는 경우 무시
        }
    }

    /**
     * 큐에서 모든 메시지 읽기
     */
    private fun readAllMessages(): List<PgmqMessage<LikeSyncRequest>> = pgmqClient.read(
        LikeSyncQueueProducer.QUEUE_NAME,
        LikeSyncRequest::class.java,
        batchSize = 100,
        visibilityTimeoutSec = 30,
    )

    // ==================== Transaction Commit Tests ====================

    @Tag("transaction")
    @DisplayName("트랜잭션 커밋 - 메시지가 큐에 유지됨")
    @Test
    fun `트랜잭션 커밋 후 메시지가 큐에서 조회된다`() {
        // given
        val characterName = "나이스비트"
        val delta = 1L

        // when
        val messageId = transactionTemplate.execute {
            producer.publish(characterName, delta)
        }

        // then
        assertThat(messageId).isNotNull()

        flushAndClear()

        // 큐에서 메시지 조회
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.characterName).isEqualTo(characterName)
        assertThat(messages[0].payload.delta).isEqualTo(delta)
    }

    @Tag("transaction")
    @DisplayName("트랜잭션 커밋 - 여러 메시지 발행 후 모두 큐에서 조회됨")
    @Test
    fun `여러 메시지 발행 후 모두 큐에서 조회된다`() {
        // given
        val characters = listOf("나이스비트", "제로", "메르헵")

        // when
        val messageIds = characters.map { characterName ->
            transactionTemplate.execute {
                producer.publish(characterName, 1L)
            }
        }

        // then
        assertThat(messageIds).allMatch { it != null }

        flushAndClear()

        // 큐에서 메시지 조회
        val messages = readAllMessages()

        assertThat(messages).hasSize(3)
        assertThat(messages.map { it.payload.characterName })
            .containsExactlyInAnyOrder(*characters.toTypedArray())
    }

    // ==================== Transaction Rollback Tests ====================

    @Tag("transaction")
    @DisplayName("트랜잭션 롤백 - 예외 발생으로 롤백 시 메시지가 큐에 없음")
    @Test
    @Transactional
    fun `예외 발생으로 롤백 시 메시지가 큐에 없다`() {
        // given
        val characterName = "나이스비트"

        // when
        try {
            producer.publish(characterName, 1L)
            // 의도적 예외 발생으로 롤백 유도
            throw RuntimeException("Intentional rollback")
        } catch (_: RuntimeException) {
            // expected
        }

        // then - 롤백으로 인해 큐에 메시지가 없어야 함
        flushAndClear()

        val messages = readAllMessages()
        assertThat(messages).isEmpty()
    }

    @Tag("transaction")
    @DisplayName("트랜잭션 롤백 - 명시적 롤백 시 메시지가 발행되지 않음")
    @Test
    fun `명시적 롤백 시 메시지가 발행되지 않는다`() {
        // given
        val characterName = "나이스비트"

        // when
        transactionTemplate.execute {
            producer.publish(characterName, 1L)
            // 명시적 롤백
            it.setRollbackOnly()
        }

        // then
        flushAndClear()

        val messages = readAllMessages()
        assertThat(messages).isEmpty()
    }

    // ==================== Batch Publish Tests ====================

    @Tag("batch")
    @DisplayName("일괄 발행 - 여러 메시지를 한 번에 발행")
    @Test
    fun `여러 메시지를 일괄 발행한다`() {
        // given
        val requests = listOf(
            LikeSyncRequest("나이스비트", 1L, Instant.now().toString()),
            LikeSyncRequest("제로", 2L, Instant.now().toString()),
            LikeSyncRequest("메르헵", 3L, Instant.now().toString()),
        )

        // when
        val messageIds = producer.publishBatch(requests)

        // then
        assertThat(messageIds).hasSize(3)
        assertThat(messageIds).allMatch { it > 0 }

        flushAndClear()

        // 큐에서 메시지 조회
        val messages = readAllMessages()

        assertThat(messages).hasSize(3)

        // 발행 순서와 조회 순서는 동일해야 함 (FIFO)
        val retrievedCharacters = messages.map { it.payload.characterName }
        assertThat(retrievedCharacters).containsExactly("나이스비트", "제로", "메르헵")

        // delta 검증
        val retrievedDeltas = messages.map { it.payload.delta }
        assertThat(retrievedDeltas).containsExactly(1L, 2L, 3L)
    }

    @Tag("batch")
    @DisplayName("일괄 발행 - 빈 요청 목록을 일괄 발행하면 빈 목록을 반환")
    @Test
    fun `빈 요청 목록을 일괄 발행하면 빈 목록을 반환한다`() {
        // given
        val emptyRequests = emptyList<LikeSyncRequest>()

        // when
        val messageIds = producer.publishBatch(emptyRequests)

        // then
        assertThat(messageIds).isEmpty()
    }

    @Tag("batch")
    @DisplayName("일괄 발행 - 대량 일괄 발행 (100건)")
    @Test
    fun `대량 일괄 발행 (100건)`() {
        // given
        val batchSize = 100
        val requests = (1..batchSize).map { index ->
            LikeSyncRequest("캐릭터_$index", index.toLong(), Instant.now().toString())
        }

        // when
        val messageIds = producer.publishBatch(requests)

        // then
        assertThat(messageIds).hasSize(batchSize)

        flushAndClear()

        // 큐에서 메시지 조회 (배치 사이즈보다 크게)
        val messages = pgmqClient.read(
            LikeSyncQueueProducer.QUEUE_NAME,
            LikeSyncRequest::class.java,
            batchSize = batchSize,
            visibilityTimeoutSec = 30,
        )

        assertThat(messages).hasSize(batchSize)
    }

    // ==================== Single Publish Tests ====================

    @Tag("publish")
    @DisplayName("단일 발행 - 개별 메시지 발행")
    @Test
    fun `단일 메시지를 발행하고 조회한다`() {
        // given
        val characterName = "나이스비트"
        val delta = 5L

        // when
        val messageId = producer.publish(characterName, delta)

        // then
        assertThat(messageId).isGreaterThan(0)

        flushAndClear()

        // 큐에서 메시지 조회
        val messages = readAllMessages()

        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.characterName).isEqualTo(characterName)
        assertThat(messages[0].payload.delta).isEqualTo(delta)
        assertThat(messages[0].payload.requestedAt).isNotBlank()
    }

    @Tag("publish")
    @DisplayName("단일 발행 - delta 기본값은 1")
    @Test
    fun `delta 기본값은 1이다`() {
        // given
        val characterName = "나이스비트"

        // when
        producer.publish(characterName) // delta 미지정

        // then
        flushAndClear()

        val messages = readAllMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].payload.delta).isEqualTo(1L)
    }
}
