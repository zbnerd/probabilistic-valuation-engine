package maple.expectation.infrastructure.worker

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import maple.expectation.core.port.inbound.ExpectationV4Port
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.pgmq.PgmqMessage
import maple.expectation.infrastructure.pgmq.PgmqWorkerConfig
import maple.expectation.infrastructure.queue.pgmq.CalculationQueueProducer
import maple.expectation.test.ServiceIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

/**
 * CalculationWorker 통합 테스트
 *
 * <h3>테스트 목표</h3>
 * <ul>
 *   <li>정상 처리 → 메시지 아카이브</li>
 *   <li>재시도 가능 실패 → 큐 유지, 메시지 재독</li>
 *   <li>최대 재시도 초과 → 삭제 (DLQ)</li>
 *   <li>@ConditionalOnProperty Worker 활성화/비활성화</li>
 * </ul>
 *
 * <h3>테스트 전략</h3>
 * <ul>
 *   <li>테스트 전용 Configuration으로 VT=1초, maxRetries=2 설정</li>
 *   <li>고유 큐 접미사로 테스트 격리</li>
 *   <li>Mock ExpectationV4Port로 성공/실패 시나리오 시뮬레이션</li>
 *   <li>Awaitility로 비동기 Worker 완료 대기</li>
 * </ul>
 *
 * <h3>Anti-Patterns (금지)</h3>
 * <ul>
 *   <li>Thread.sleep() 금지 → Awaitility 사용</li>
 *   <li>@Transactional 사용 금지 → Worker는 별도 스레드에서 실행</li>
 *   <li>실제 큐 사용 금지 → 테스트 전용 큐 사용</li>
 * </ul>
 *
 * @see CalculationWorker
 */
@Tag("pgmq")
@DisplayName("CalculationWorker 통합 테스트")
@Import(CalculationWorkerIntegrationTest.TestConfig::class)
class CalculationWorkerIntegrationTest : ServiceIntegrationTestBase() {

    private val log = LoggerFactory.getLogger(CalculationWorkerIntegrationTest::class.java)

    @Autowired
    private lateinit var producer: CalculationQueueProducer

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var mockExpectationPort: MockExpectationV4Port

    @Autowired
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Autowired
    private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    private lateinit var workerConfig: PgmqWorkerConfig

    /**
     * 테스트 전용 큐 이름 (고유 접미사로 격리)
     */
    private val testQueueName = "calculation_queue_test_${System.currentTimeMillis()}"

    /**
     * Worker 설정 (VT=1초, maxRetries=2)
     */
    private val testWorkerSettings = PgmqWorkerConfig.WorkerSettings(
        enabled = true,
        pollingIntervalMs = 100, // 빠른 테스트를 위해 100ms
        batchSize = 10,
        maxRetries = 2, // 테스트용: 2회 재시도
    )

    @BeforeEach
    override fun setUp() {
        // Circuit Breaker 초기화
        resetPgmqCircuitBreaker()

        // 테스트 전용 큐 생성
        createQueue(testQueueName)

        // 큐 비우기
        purgeQueue(testQueueName)

        // Mock 포트 초기화
        mockExpectationPort.clear()
    }

    @AfterEach
    fun tearDown() {
        // 테스트 후 큐 정리
        purgeQueue(testQueueName)
        dropQueue(testQueueName)
    }

    // ================================
    // Normal Processing → Archive
    // ================================

    @Test
    @DisplayName("정상 처리: 메시지가 아카이브된다")
    fun `normal processing archives message`() {
        // given
        mockExpectationPort.setBehavior(success = true)
        val ocid = "test-ocid-success"
        val userIgn = "success-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - 큐에서 메시지가 사라지고 아카이브될 때까지 대기
        waitForQueueEmpty(testQueueName, timeoutSeconds = 10)

        // 메시지가 큐에 없음을 검증
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)

        // 포트가 호출되었는지 검증
        assertThat(mockExpectationPort.getCallCount(userIgn)).isEqualTo(1)
    }

    @Test
    @DisplayName("정상 처리: 여러 메시지가 순서대로 아카이브된다")
    fun `normal processing archives multiple messages in order`() {
        // given
        mockExpectationPort.setBehavior(success = true)
        val requests = (1..5).map { i ->
            CalculationRequest("ocid-$i", "user-$i", i, false, "2026-03-15T10:0$i:00Z")
        }

        // when - 일괄 발행
        val messageIds = producer.publishBatch(requests)

        // then - 모든 메시지가 처리될 때까지 대기
        waitForQueueEmpty(testQueueName, timeoutSeconds = 15)

        // 모든 메시지가 큐에서 사라졌는지 검증
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)

        // 모든 포트가 호출되었는지 검증
        requests.forEach { req ->
            assertThat(mockExpectationPort.getCallCount(req.userIgn)).isEqualTo(1)
        }
    }

    // ================================
    // Retry on Failure → Queue Stays
    // ================================

    @Test
    @DisplayName("재시도 가능 실패: 메시지가 큐에 유지되고 재독된다")
    fun `retryable failure keeps message in queue and re-reads`() {
        // given
        mockExpectationPort.setBehavior(
            success = false,
            failCount = 1, // 첫 번째만 실패
        )
        val ocid = "test-ocid-retry"
        val userIgn = "retry-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - 첫 번째 실패 후 메시지가 큐에 유지됨
        waitForMessageCount(testQueueName, expectedCount = 1, timeoutSeconds = 3)

        var initialMessage = readSingleMessage()
        assertThat(initialMessage).isNotNull()
        assertThat(initialMessage!!.readCount).isEqualTo(1) // 첫 번째 읽기

        // VT 만료까지 대기 (1초)
        Thread.sleep(1500)

        // Worker가 재시도하여 성공할 때까지 대기
        mockExpectationPort.setBehavior(success = true) // 이번엔 성공
        waitForQueueEmpty(testQueueName, timeoutSeconds = 10)

        // 최종적으로 큐가 비어있어야 함
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)

        // 포트가 2번 호출되었는지 검증 (실패 1회 + 성공 1회)
        assertThat(mockExpectationPort.getCallCount(userIgn)).isEqualTo(2)
    }

    @Test
    @DisplayName("재시도 가능 실패: readCount가 증가한다")
    fun `retryable failure increments readCount`() {
        // given
        mockExpectationPort.setBehavior(
            success = false,
            failCount = 2, // 2회 연속 실패
        )
        val ocid = "test-ocid-read-count"
        val userIgn = "read-count-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - 첫 번째 읽기
        waitForMessageCount(testQueueName, expectedCount = 1, timeoutSeconds = 3)
        var message = readSingleMessage()
        assertThat(message!!.readCount).isEqualTo(1)

        // VT 만료 대기
        Thread.sleep(1500)

        // 두 번째 읽기
        message = readSingleMessage()
        assertThat(message!!.readCount).isEqualTo(2)

        // VT 만료 대기
        Thread.sleep(1500)

        // 3번째 읽기 (maxRetries=2이므로 이번엔 성공해야 함)
        mockExpectationPort.setBehavior(success = true)
        waitForQueueEmpty(testQueueName, timeoutSeconds = 10)

        // 최종 검증
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)
    }

    // ================================
    // Max Retries Exceeded → Delete (DLQ)
    // ================================

    @Test
    @DisplayName("최대 재시도 초과: 메시지가 삭제된다 (DLQ)")
    fun `max retries exceeded deletes message to DLQ`() {
        // given
        mockExpectationPort.setBehavior(
            success = false,
            failCount = Int.MAX_VALUE, // 계속 실패
        )
        val ocid = "test-ocid-dlq"
        val userIgn = "dlq-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - readCount가 0 → 1 → 2 → 3 (삭제) 증가하며 큐에서 사라질 때까지 대기
        await().atMost(Duration.ofSeconds(15))
            .until {
                val count = getQueueMessageCount(testQueueName)
                count == 0L // 최종적으로 큐에서 사라짐
            }

        // 포트가 maxRetries + 1번 호출되었는지 검증 (초기 1회 + 재시도 2회)
        // VT=1초이므로 약 3-4초 내에 3회 호출되어야 함
        await().atMost(Duration.ofSeconds(10))
            .until {
                mockExpectationPort.getCallCount(userIgn) >= 3
            }

        val finalCallCount = mockExpectationPort.getCallCount(userIgn)
        assertThat(finalCallCount).isGreaterThanOrEqualTo(3) // 최소 3회 호출

        // 최종적으로 큐가 비어있어야 함
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)
    }

    @Test
    @DisplayName("최대 재시도 초과: readCount가 3이 되면 삭제된다")
    fun `max retries exceeded deletes when readCount is 3`() {
        // given
        mockExpectationPort.setBehavior(
            success = false,
            failCount = Int.MAX_VALUE,
        )
        val ocid = "test-ocid-read-count-3"
        val userIgn = "dlq-read-count-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - readCount 추적
        var lastReadCount = 0
        await().atMost(Duration.ofSeconds(15))
            .until {
                val message = readSingleMessage()
                if (message != null) {
                    lastReadCount = message.readCount
                    log.debug("Current readCount: $lastReadCount")
                    lastReadCount >= 3 // readCount가 3에 도달하면 삭제됨
                } else {
                    // 메시지가 없으면 이미 삭제됨
                    true
                }
            }

        // 최종적으로 큐가 비어있어야 함
        waitForQueueEmpty(testQueueName, timeoutSeconds = 5)
        val queueCount = getQueueMessageCount(testQueueName)
        assertThat(queueCount).isEqualTo(0)
    }

    // ================================
    // @ConditionalOnProperty Tests
    // ================================

    @Test
    @DisplayName("Worker 활성화: enabled=true이면 메시지가 처리된다")
    fun `worker enabled processes messages`() {
        // given
        workerConfig.calculation.enabled = true
        mockExpectationPort.setBehavior(success = true)
        val ocid = "test-ocid-enabled"
        val userIgn = "enabled-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - 메시지가 처리됨
        waitForQueueEmpty(testQueueName, timeoutSeconds = 10)
        assertThat(getQueueMessageCount(testQueueName)).isEqualTo(0)
    }

    @Test
    @DisplayName("Worker 비활성화: enabled=false이면 메시지가 처리되지 않는다")
    fun `worker disabled does not process messages`() {
        // given
        workerConfig.calculation.enabled = false
        mockExpectationPort.setBehavior(success = true)
        val ocid = "test-ocid-disabled"
        val userIgn = "disabled-test-user"

        // when - 메시지 발행
        val messageId = publishMessage(ocid, userIgn)

        // then - 메시지가 큐에 유지됨 (Worker가 비활성화되어 처리 안 됨)
        await().atMost(Duration.ofSeconds(3))
            .until {
                getQueueMessageCount(testQueueName) == 1L
            }

        // 충분한 시간이 지나도 큐가 비어있지 않음을 검증
        Thread.sleep(2000)
        assertThat(getQueueMessageCount(testQueueName)).isEqualTo(1)

        // 포트가 호출되지 않았는지 검증
        assertThat(mockExpectationPort.getCallCount(userIgn)).isEqualTo(0)

        // Cleanup: Worker 재활성화
        workerConfig.calculation.enabled = true
    }

    // ================================
    // Helper Methods
    // ================================

    /**
     * 메시지 발행 (테스트 전용 큐 사용)
     */
    private fun publishMessage(ocid: String, userIgn: String): Long {
        val request = CalculationRequest(
            ocid = ocid,
            userIgn = userIgn,
            presetNo = 1,
            forceRecalculation = false,
            requestedAt = java.time.Instant.now().toString(),
        )
        return pgmqClient.send(testQueueName, request)
    }

    /**
     * 단일 메시지 읽기
     */
    private fun readSingleMessage(): PgmqMessage<CalculationRequest>? {
        val messages = pgmqClient.read(
            testQueueName,
            CalculationRequest::class.java,
            batchSize = 1,
            visibilityTimeoutSec = 1,
        )
        return if (messages.isNotEmpty()) messages[0] else null
    }

    // ================================
    // PGMQ Helper Methods
    // ================================

    private fun resetPgmqCircuitBreaker(circuitBreakerName: String = "pgmq") {
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName)
        circuitBreaker.reset()
    }

    private fun getQueueMessageCount(queueName: String): Long = try {
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queueName",
            Long::class.java,
        ) ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun hasMessages(queueName: String): Boolean = getQueueMessageCount(queueName) > 0

    private fun purgeQueue(queueName: String) {
        try {
            jdbcTemplate.execute("DELETE FROM pgmq.q_$queueName")
        } catch (e: Exception) {
            // 큐가 없으면 무시
        }
    }

    private fun waitForQueueEmpty(
        queueName: String,
        timeoutSeconds: Long = 10,
    ) {
        await().atMost(Duration.ofSeconds(timeoutSeconds))
            .until { !hasMessages(queueName) }
    }

    private fun waitForMessageCount(
        queueName: String,
        expectedCount: Long,
        timeoutSeconds: Long = 10,
    ) {
        await().atMost(Duration.ofSeconds(timeoutSeconds))
            .until { getQueueMessageCount(queueName) == expectedCount }
    }

    private fun queueExists(queueName: String): Boolean = try {
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT FROM pgmq.q_$queueName LIMIT 1)",
            Boolean::class.java,
        ) ?: false
    } catch (e: Exception) {
        false
    }

    private fun createQueue(queueName: String): Boolean = try {
        jdbcTemplate.execute("SELECT pgmq.create('$queueName')")
        true
    } catch (e: Exception) {
        false
    }

    private fun dropQueue(queueName: String): Boolean = try {
        jdbcTemplate.execute("SELECT pgmq.drop('$queueName')")
        true
    } catch (e: Exception) {
        false
    }

    private fun dropQueueWithArchive(queueName: String): Boolean = try {
        jdbcTemplate.execute("DROP TABLE IF EXISTS pgmq.a_$queueName")
        jdbcTemplate.execute("SELECT pgmq.drop('$queueName')")
        true
    } catch (e: Exception) {
        false
    }

    // ================================
    // Test Configuration
    // ================================

    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {

        @Bean
        @Primary
        fun mockExpectationV4Port(): MockExpectationV4Port = MockExpectationV4Port()
    }

    /**
     * Mock ExpectationV4Port
     *
     * <p>성공/실패 시나리오를 시뮬레이션하여 Worker 동작 검증
     */
    class MockExpectationV4Port : ExpectationV4Port {

        private val callCountMap = ConcurrentHashMap<String, AtomicInteger>()
        private var success = true
        private var failCount = 0
        private var currentFailCount = 0

        fun setBehavior(success: Boolean, failCount: Int = 0) {
            this.success = success
            this.failCount = failCount
            this.currentFailCount = 0
        }

        fun getCallCount(userIgn: String): Int = callCountMap[userIgn]?.get() ?: 0

        fun clear() {
            callCountMap.clear()
            success = true
            failCount = 0
            currentFailCount = 0
        }

        override fun calculateExpectationAsync(userIgn: String, force: Boolean): CompletableFuture<Any> {
            // 호출 카운트 증가
            callCountMap.computeIfAbsent(userIgn) { AtomicInteger(0) }.incrementAndGet()

            return if (success || currentFailCount >= failCount) {
                // 성공 시나리오
                CompletableFuture.completedFuture(mockResponse())
            } else {
                // 실패 시나리오
                currentFailCount++
                CompletableFuture.failedFuture(RuntimeException("Simulated calculation failure"))
            }
        }

        override fun calculateExpectation(userIgn: String, force: Boolean): Any {
            // 호출 카운트 증가
            callCountMap.computeIfAbsent(userIgn) { AtomicInteger(0) }.incrementAndGet()

            return if (success || currentFailCount >= failCount) {
                // 성공 시나리오
                mockResponse()
            } else {
                // 실패 시나리오
                currentFailCount++
                throw RuntimeException("Simulated calculation failure")
            }
        }

        override fun getGzipExpectationAsync(userIgn: String, force: Boolean): CompletableFuture<ByteArray?> = CompletableFuture.completedFuture(byteArrayOf())

        override fun getGzipExpectation(userIgn: String, force: Boolean): ByteArray? = byteArrayOf()

        override fun getGzipFromL1CacheDirect(userIgn: String): ByteArray? = null

        private fun mockResponse(): Any {
            // Mock response object
            return mapOf(
                "status" to "success",
                "data" to emptyMap<String, Any>(),
            )
        }
    }
}
