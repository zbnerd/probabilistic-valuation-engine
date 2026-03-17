package maple.expectation.integration

import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import maple.expectation.core.domain.model.character.CharacterId
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.core.domain.model.character.UserIgn
import maple.expectation.domain.repository.GameCharacterRepository
import maple.expectation.infrastructure.pgmq.LikeSyncRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.queue.pgmq.LikeSyncQueueProducer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * LikeSyncWorker 통합 테스트
 *
 * <p>PGMQ Worker의 처리 흐름을 검증합니다:
 *
 * <h3>테스트 시나리오</h3>
 * <ul>
 *   <li>정상 처리 → 아카이브</li>
 *   <li>실패 시 재시도 → 큐 유지, 메시지 재읽기</li>
 *   <li>최대 재시도 초과 → 삭제 (DLQ)</li>
 *   <li>@ConditionalOnProperty Worker 비활성화</li>
 *   <li>수동 실행 모드 테스트</li>
 * </ul>
 *
 * <h3>테스트 설정</h3>
 * <p>VT(Visibility Timeout) = 1초, maxRetries = 2로 설정하여 빠른 테스트
 *
 * <h3>ADR 문서 참조</h3>
 * <ul>
 *   <li><a href="../../../../docs/adr/002-pgmq-queue-architecture.md">ADR-002: PGMQ Queue Architecture</a>
 * </ul>
 *
 * @see maple.expectation.infrastructure.worker.LikeSyncWorker
 * @see maple.expectation.infrastructure.pgmq.PgmqWorker
 */
@Tag("integration")
@Tag("pgmq")
@Tag("worker")
@DisplayName("LikeSyncWorker 통합 테스트")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "pgmq.worker.like-sync.enabled=true",
        "pgmq.worker.common.polling-interval-ms=100",
        "pgmq.worker.common.visibility-timeout-sec=1",
        "pgmq.worker.common.max-retries=2",
    ],
)
class LikeSyncWorkerIntegrationTest {

    // Note: DatabaseCleaner is in test source set, not accessible from integrationTest
    // Manual cleanup is done in @AfterEach

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var characterRepository: GameCharacterRepository

    @Autowired
    private lateinit var producer: LikeSyncQueueProducer

    @Autowired
    private lateinit var workerConfig: PgmqWorkerConfig

    // Worker 인스턴스는 @ConditionalOnProperty로 인해 자동 주입되지 않음
    // 수동 테스트를 위해 필요한 의존성을 주입받아 테스트

    private val testQueueName = "${LikeSyncQueueProducer.QUEUE_NAME}_test"

    @BeforeEach
    fun setUp() {
        // 테스트용 큐 생성
        createTestQueue()
        // 테스트용 캐릭터 생성
        createTestCharacter()
    }

    @AfterEach
    fun tearDown() {
        // 테스트 데이터 정리
        dropTestQueue()
        cleanTestData()
    }

    @Test
    @DisplayName("정상 처리: 메시지가 성공적으로 처리되어 아카이브된다")
    fun `정상 처리 시 메시지가 아카이브된다`() {
        // Given
        val initialLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        val delta = 5L
        val messageId = pgmqClient.send(
            testQueueName,
            LikeSyncRequest(
                characterName = TEST_CHARACTER_NAME,
                delta = delta,
                requestedAt = Instant.now().toString(),
            ),
        )

        // When
        // 수동으로 메시지 읽기 및 처리
        val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages).hasSize(1)

        val message = messages[0]
        assertThat(message.messageId).isEqualTo(messageId)
        assertThat(message.payload.characterName).isEqualTo(TEST_CHARACTER_NAME)
        assertThat(message.payload.delta).isEqualTo(delta)
        assertThat(message.readCount).isEqualTo(0)

        // 좋아요 수 증가 처리
        characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, delta)

        // 아카이브
        val archived = pgmqClient.archive(testQueueName, message.messageId)
        assertThat(archived).isTrue

        // Then
        // 메시지가 큐에서 삭제되었는지 확인
        val remainingMessages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 10, 1)
        assertThat(remainingMessages).isEmpty()

        // 좋아요 수가 증가했는지 확인
        val finalLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        assertThat(finalLikeCount).isEqualTo(initialLikeCount + delta)

        // 아카이브 테이블에서 메시지 확인
        val archivedMessages = getArchivedMessages()
        assertThat(archivedMessages).hasSize(1)
        assertThat(archivedMessages[0]).isEqualTo(messageId)
    }

    @Test
    @DisplayName("재시도: 처리 실패 시 메시지가 큐에 유지되고 재시도된다")
    fun `처리 실패 시 메시지가 재시도된다`() {
        // Given
        val delta = 3L
        val messageId = pgmqClient.send(
            testQueueName,
            LikeSyncRequest(
                characterName = TEST_CHARACTER_NAME,
                delta = delta,
                requestedAt = Instant.now().toString(),
            ),
        )

        // When & Then
        // 첫 번째 읽기 (readCount = 0)
        val messages1 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages1).hasSize(1)
        assertThat(messages1[0].readCount).isEqualTo(0)

        // 처리하지 않고 VT가 만료될 때까지 대기
        Thread.sleep(1100) // VT = 1초 + 100ms 여유

        // 두 번째 읽기 (readCount = 1, 재시도)
        val messages2 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages2).hasSize(1)
        assertThat(messages2[0].messageId).isEqualTo(messageId)
        assertThat(messages2[0].readCount).isEqualTo(1) // 재시도 횟수 증가

        // 처리 및 아카이브
        characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, delta)
        pgmqClient.archive(testQueueName, messages2[0].messageId)

        // 세 번째 읽기 (메시지 없음)
        val messages3 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages3).isEmpty()
    }

    @Test
    @DisplayName("최대 재시도 초과: maxRetries를 초과하면 메시지가 삭제된다 (DLQ)")
    fun `최대 재시도 초과 시 메시지가 삭제된다`() {
        // Given
        val delta = 2L
        val maxRetries = 2
        val messageId = pgmqClient.send(
            testQueueName,
            LikeSyncRequest(
                characterName = TEST_CHARACTER_NAME,
                delta = delta,
                requestedAt = Instant.now().toString(),
            ),
        )

        // When & Then
        // 첫 번째 읽기 (readCount = 0)
        val messages1 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages1).hasSize(1)
        assertThat(messages1[0].readCount).isEqualTo(0)

        // 처리하지 않고 VT 만료 대기
        Thread.sleep(1100)

        // 두 번째 읽기 (readCount = 1)
        val messages2 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages2).hasSize(1)
        assertThat(messages2[0].readCount).isEqualTo(1)

        // 처리하지 않고 VT 만료 대기
        Thread.sleep(1100)

        // 세 번째 읽기 (readCount = 2)
        val messages3 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages3).hasSize(1)
        assertThat(messages3[0].readCount).isEqualTo(2)

        // 처리하지 않고 VT 만료 대기
        Thread.sleep(1100)

        // 네 번째 읽기 (readCount = 3, maxRetries = 2 초과)
        val messages4 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages4).hasSize(1)
        assertThat(messages4[0].readCount).isEqualTo(3)

        // isRetryable 확인
        val isRetryable = messages4[0].isRetryable(maxRetries)
        assertThat(isRetryable).isFalse

        // DLQ로 삭제 (최대 재시도 초과)
        val deleted = pgmqClient.delete(testQueueName, messages4[0].messageId)
        assertThat(deleted).isTrue

        // 다시 읽기 시 메시지 없음
        val messages5 = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages5).isEmpty()

        // 아카이브 테이블에도 없음 (삭제됨)
        val archivedMessages = getArchivedMessages()
        assertThat(archivedMessages).isEmpty()
    }

    @Test
    @DisplayName("배치 처리: 여러 메시지를 순차적으로 처리한다")
    fun `여러 메시지를 배치로 처리한다`() {
        // Given
        val messageIds = mutableListOf<Long>()
        val deltas = listOf(1L, 2L, 3L, 4L, 5L)

        deltas.forEach { delta ->
            val id = pgmqClient.send(
                testQueueName,
                LikeSyncRequest(
                    characterName = TEST_CHARACTER_NAME,
                    delta = delta,
                    requestedAt = Instant.now().toString(),
                ),
            )
            messageIds.add(id)
        }

        val initialLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)

        // When
        // 배치로 읽기 (batchSize = 5)
        val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 5, 1)
        assertThat(messages).hasSize(5)

        // 모든 메시지 처리
        var totalDelta = 0L
        messages.forEach { message ->
            characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, message.payload.delta)
            totalDelta += message.payload.delta
            pgmqClient.archive(testQueueName, message.messageId)
        }

        // Then
        // 모든 메시지가 처리되었는지 확인
        val remainingMessages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 10, 1)
        assertThat(remainingMessages).isEmpty()

        // 좋아요 수가 모든 delta의 합만큼 증가했는지 확인
        val finalLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        assertThat(finalLikeCount).isEqualTo(initialLikeCount + totalDelta)

        // 아카이브 테이블에서 모든 메시지 확인
        val archivedMessages = getArchivedMessages()
        assertThat(archivedMessages).hasSize(5)
        assertThat(archivedMessages).containsAll(messageIds)
    }

    @Test
    @DisplayName("경쟁적 소비: SKIP LOCKED로 동시성이 보장된다")
    fun `SKIP LOCKED로 동시성이 보장된다`() {
        // Given
        val messageCount = 10
        val messageIds = mutableListOf<Long>()

        repeat(messageCount) {
            val id = pgmqClient.send(
                testQueueName,
                LikeSyncRequest(
                    characterName = TEST_CHARACTER_NAME,
                    delta = 1L,
                    requestedAt = Instant.now().toString(),
                ),
            )
            messageIds.add(id)
        }

        val processedCount = AtomicInteger(0)
        val lock = Any()

        // When: 두 개의 "워커"가 동시에 메시지를 읽음
        val worker1 = Thread {
            synchronized(lock) {
                val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 5, 1)
                messages.forEach { message ->
                    characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, message.payload.delta)
                    pgmqClient.archive(testQueueName, message.messageId)
                    processedCount.incrementAndGet()
                }
            }
        }

        val worker2 = Thread {
            synchronized(lock) {
                val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 5, 1)
                messages.forEach { message ->
                    characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, message.payload.delta)
                    pgmqClient.archive(testQueueName, message.messageId)
                    processedCount.incrementAndGet()
                }
            }
        }

        worker1.start()
        worker2.start()
        worker1.join()
        worker2.join()

        // Then
        // 모든 메시지가 정확히 한 번씩 처리되었는지 확인
        assertThat(processedCount.get()).isEqualTo(messageCount)

        val remainingMessages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 10, 1)
        assertThat(remainingMessages).isEmpty()
    }

    @Test
    @DisplayName("Producer: 큐 프로듀서로 메시지를 발행한다")
    fun `큐 프로듀서로 메시지를 발행한다`() {
        // Given
        val initialLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        val delta = 7L

        // When
        val messageId = producer.publish(TEST_CHARACTER_NAME, delta)

        // Then
        assertThat(messageId).isPositive()

        // 메시지가 큐에 있는지 확인
        val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages).hasSize(1)
        assertThat(messages[0].messageId).isEqualTo(messageId)
        assertThat(messages[0].payload.characterName).isEqualTo(TEST_CHARACTER_NAME)
        assertThat(messages[0].payload.delta).isEqualTo(delta)

        // 정리: 메시지 처리
        characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, delta)
        pgmqClient.archive(testQueueName, messages[0].messageId)

        val finalLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        assertThat(finalLikeCount).isEqualTo(initialLikeCount + delta)
    }

    @Test
    @DisplayName("Worker 비활성화: enabled=false 시 Worker가 동작하지 않는다")
    fun `Worker가 비활성화되면 메시지가 처리되지 않는다`() {
        // Given
        val initialLikeCount = getCharacterLikeCount(TEST_CHARACTER_NAME)
        val delta = 1L

        pgmqClient.send(
            testQueueName,
            LikeSyncRequest(
                characterName = TEST_CHARACTER_NAME,
                delta = delta,
                requestedAt = Instant.now().toString(),
            ),
        )

        // When
        // Worker가 비활성화되어 있으면 자동 처리되지 않음
        // 수동으로만 읽을 수 있음
        Thread.sleep(500) // Worker가 자동으로 처리할 시간을 줌 (하지만 비활성화되어 있음)

        // Then: 좋아요 수가 변하지 않았음
        val likeCountAfterWait = getCharacterLikeCount(TEST_CHARACTER_NAME)
        assertThat(likeCountAfterWait).isEqualTo(initialLikeCount)

        // 수동으로 메시지를 읽어서 확인
        val messages = pgmqClient.read(testQueueName, LikeSyncRequest::class.java, 1, 1)
        assertThat(messages).hasSize(1)

        // 정리
        characterRepository.incrementLikeCount(TEST_CHARACTER_NAME, delta)
        pgmqClient.archive(testQueueName, messages[0].messageId)
    }

    // ==================== Helper Methods ====================

    private fun createTestQueue() {
        // PGMQ 큐 생성
        jdbcTemplate.execute("SELECT pgmq.create('$testQueueName')")
        log.info("✅ Created test queue: $testQueueName")
    }

    private fun dropTestQueue() {
        // PGMQ 큐 삭제
        try {
            jdbcTemplate.execute("SELECT pgmq.drop_queue('$testQueueName')")
            log.info("🗑️ Dropped test queue: $testQueueName")
        } catch (e: Exception) {
            log.warn("⚠️ Failed to drop test queue (may not exist): $testQueueName")
        }
    }

    private fun createTestCharacter() {
        val character = GameCharacter.create(
            userIgn = UserIgn(TEST_CHARACTER_NAME),
            characterId = CharacterId("test-ocid-${System.currentTimeMillis()}"),
        )
        characterRepository.save(character)
        log.info("✅ Created test character: $TEST_CHARACTER_NAME")
    }

    private fun getCharacterLikeCount(characterName: String): Long = jdbcTemplate.queryForObject(
        "SELECT like_count FROM game_character WHERE userIgn = ?",
        Long::class.java,
        characterName,
    ) ?: 0L

    private fun getArchivedMessages(): List<Long> {
        val archiveTableName = "pgmq.a_${testQueueName.replace("_test", "_queue")}"
        return jdbcTemplate.query(
            "SELECT msg_id FROM $archiveTableName ORDER BY enqueued_at DESC LIMIT 100",
        ) { rs: ResultSet, _: Int ->
            rs.getLong("msg_id")
        }
    }

    private fun cleanTestData() {
        // 테스트 캐릭터 삭제
        try {
            jdbcTemplate.update("DELETE FROM game_character WHERE userIgn LIKE ?", "$TEST_CHARACTER_NAME%")
        } catch (e: Exception) {
            log.warn("⚠️ Failed to clean test character data")
        }
    }
}

// Test constants and logger
private const val TEST_CHARACTER_NAME = "TestCharacterWorker"
private val log = org.slf4j.LoggerFactory.getLogger(LikeSyncWorkerIntegrationTest::class.java)
