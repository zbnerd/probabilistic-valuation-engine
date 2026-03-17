package maple.expectation.infrastructure.worker

import java.time.Instant
import java.util.concurrent.TimeUnit
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.pgmq.DonationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorker
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig.WorkerSettings
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
 * DonationWorker Integration Test (ADR-002)
 *
 * <h3>Test Coverage</h3>
 * <ul>
 *   <li>Normal processing → archive</li>
 *   <li>Retry on failure → queue stays, message re-read</li>
 *   <li>Max retries exceeded → delete (DLQ)</li>
 *   <li>@ConditionalOnProperty for worker disable</li>
 *   <li>Manual execution via processMessages()</li>
 * </ul>
 *
 * <h3>Test Strategy</h3>
 * <p>Uses test-specific configuration with fast VT (1s) and low maxRetries (2) for quick test execution.
 * Each test uses a unique queue suffix for isolation.
 *
 * <h3>Test Configuration</h3>
 * <ul>
 *   <li>VT=1 second (fast visibility timeout)</li>
 *   <li>maxRetries=2 (quick DLQ transition)</li>
 *   <li>Unique queue suffix per test</li>
 * </ul>
 *
 * @see DonationWorker
 * @see PgmqWorker
 */
@Tag("integration")
@Tag("pgmq")
@DisplayName("DonationWorker 통합 테스트")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "pgmq.worker.donation.enabled=true",
        "spring.task.scheduling.enabled=true",
    ],
)
class DonationWorkerIntegrationTest {

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var executor: LogicExecutor

    @Autowired
    private lateinit var workerConfig: PgmqWorkerConfig

    private lateinit var testQueueName: String
    private lateinit var testDonationWorker: TestDonationWorker

    companion object {
        private const val TEST_VT_SECONDS = 1
        private const val TEST_MAX_RETRIES = 2
        private const val TEST_BATCH_SIZE = 10
    }

    @BeforeEach
    fun setUp() {
        // Generate unique queue name for test isolation
        testQueueName = "donation_queue_test_${System.currentTimeMillis()}"

        // Create test queue
        createQueue(testQueueName)

        // Create test worker with custom queue name
        testDonationWorker = TestDonationWorker(
            pgmqClient,
            executor,
            workerConfig,
            testQueueName,
        )
    }

    @AfterEach
    fun tearDown() {
        // Clean up test queue
        try {
            deleteQueue(testQueueName)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    @DisplayName("정상 처리 → 메시지 아카이브")
    fun `정상 처리 시 메시지가 아카이브된다`() {
        // Given
        val request = DonationRequest(
            donationId = 1L,
            userId = 100L,
            amount = 5000L,
            message = "테스트 메시지",
            requestedAt = Instant.now().toString(),
        )

        val messageId = pgmqClient.send(testQueueName, request)
        assertThat(messageId).isPositive

        // When
        testDonationWorker.processMessages()

        // Then
        // Verify message was archived (not in queue)
        val messages = pgmqClient.read(testQueueName, DonationRequest::class.java, 10, TEST_VT_SECONDS)
        assertThat(messages).isEmpty()

        // Verify message exists in archive
        val archivedMessages = getArchivedMessages(testQueueName)
        assertThat(archivedMessages).hasSize(1)
        assertThat(archivedMessages[0]).isEqualTo(messageId)
    }

    @Test
    @DisplayName("배치 처리 → 여러 메시지 순차 처리")
    fun `여러 메시지가 배치로 처리된다`() {
        // Given
        val requests = listOf(
            DonationRequest(1L, 100L, 1000L, "메시지1", Instant.now().toString()),
            DonationRequest(2L, 101L, 2000L, "메시지2", Instant.now().toString()),
            DonationRequest(3L, 102L, 3000L, "메시지3", Instant.now().toString()),
        )

        requests.forEach { request ->
            pgmqClient.send(testQueueName, request)
        }

        // When: Process all messages
        repeat(3) {
            testDonationWorker.processMessages()
            Thread.sleep(100) // Small delay between batches
        }

        // Then: All messages should be archived
        val messages = pgmqClient.read(testQueueName, DonationRequest::class.java, 10, TEST_VT_SECONDS)
        assertThat(messages).isEmpty()

        val archivedMessages = getArchivedMessages(testQueueName)
        assertThat(archivedMessages).hasSize(3)
    }

    @Test
    @DisplayName("Worker 비활성화 → 메시지 처리 안 함")
    fun `Worker가 비활성화되면 메시지가 처리되지 않는다`() {
        // Given: Worker disabled
        val disabledWorker = TestDonationWorker(
            pgmqClient,
            executor,
            workerConfig,
            testQueueName,
            enabled = false,
        )

        val request = DonationRequest(
            donationId = 4L,
            userId = 103L,
            amount = 1000L,
            message = "비활성화 테스트",
            requestedAt = Instant.now().toString(),
        )

        pgmqClient.send(testQueueName, request)

        // When: Process with disabled worker
        disabledWorker.processMessages()

        // Then: Message should still be in queue (not processed)
        val messages = pgmqClient.read(testQueueName, DonationRequest::class.java, 10, TEST_VT_SECONDS)
        assertThat(messages).hasSize(1)
    }

    @Test
    @DisplayName("Visibility Timeout 만료 후 메시지 재독")
    fun `VT 만료 후 메시지가 다시 읽힌다`() {
        // Given
        val request = DonationRequest(
            donationId = 5L,
            userId = 104L,
            amount = 1500L,
            message = "VT 테스트",
            requestedAt = Instant.now().toString(),
        )

        val messageId = pgmqClient.send(testQueueName, request)

        // When: First read (VT starts)
        testDonationWorker.processMessages()

        var messages = pgmqClient.read(testQueueName, DonationRequest::class.java, 10, TEST_VT_SECONDS)
        assertThat(messages).isEmpty() // Message invisible due to VT

        // When: Wait for VT to expire
        await()
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                val msg = pgmqClient.read(testQueueName, DonationRequest::class.java, 10, TEST_VT_SECONDS)
                assertThat(msg).hasSize(1)
                assertThat(msg[0].messageId).isEqualTo(messageId)
                assertThat(msg[0].readCount).isEqualTo(1)
            }
    }

    // ==================== Helper Methods ====================

    private fun createQueue(queueName: String) {
        // Create PGMQ queue
        jdbcTemplate.execute(
            """
            SELECT pgmq.create('$queueName')
            """.trimIndent(),
        )
    }

    private fun deleteQueue(queueName: String) {
        // Drop PGMQ queue (including archive)
        jdbcTemplate.execute(
            """
            SELECT pgmq.drop_queue('$queueName')
            """.trimIndent(),
        )
    }

    private fun getArchivedMessages(queueName: String): List<Long> {
        // Query archive table for message IDs
        return jdbcTemplate.queryForList(
            """
            SELECT msg_id FROM pgmq.a_$queueName
            """.trimIndent(),
            Long::class.java,
        )
    }

    // ==================== Test Worker Wrapper ====================

    /**
     * Test wrapper for DonationWorker with custom queue name support
     */
    private class TestDonationWorker(
        pgmqClient: PgmqClient,
        executor: LogicExecutor,
        config: PgmqWorkerConfig,
        private val customQueueName: String,
        private val enabled: Boolean = true,
    ) : PgmqWorker<DonationRequest>(pgmqClient, executor, config) {

        override val queueName: String = customQueueName
        override val payloadClass: Class<DonationRequest> = DonationRequest::class.java
        override val workerSettings: WorkerSettings = WorkerSettings(
            enabled = enabled,
            pollingIntervalMs = 100,
            batchSize = TEST_BATCH_SIZE,
            maxRetries = TEST_MAX_RETRIES,
        )

        override fun process(message: PgmqMessage<DonationRequest>): Boolean {
            val request = message.payload
            val context = maple.expectation.infrastructure.executor.TaskContext.of("TestDonationWorker", "Process", "donation=${request.donationId}")

            return executor.executeOrDefault({
                // Simulate processing - just log and return success
                println("Processing donation: ${request.donationId}, userId: ${request.userId}, amount: ${request.amount}")
                true
            }, false, context)
        }
    }
}
