package maple.expectation.infrastructure.pgmq

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Duration
import org.awaitility.Awaitility.await
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * PGMQ 테스트 유틸리티 기본 클래스
 *
 * <h3>제공 기능</h3>
 * <ul>
 *   <li>Circuit Breaker 상태 초기화 (reset)</li>
 *   <li>큐 메시지 수 조회 (getQueueMessageCount)</li>
 *   <li>큐 전체 비우기 (purgeQueue)</li>
 *   <li>큐가 빌 때까지 대기 (waitForQueueEmpty)</li>
 * </ul>
 *
 * <h3>사용법</h3>
 * <pre>
 * class MyPgmqTest : InfraAdapterTestTemplate(), PgmqTestSupport {
 *
 *     &#64;Autowired
 *     lateinit var jdbcTemplate: JdbcTemplate
 *
 *     &#64;Autowired
 *     lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
 *
 *     &#64;Test
 *     fun `큐 메시지 처리 테스트`() {
 *         // Given
 *         val queueName = "test_queue"
 *         resetPgmqCircuitBreaker()
 *         purgeQueue(queueName)
 *
 *         // When
 *         sendMessage(queueName, "test payload")
 *
 *         // Then
 *         assertThat(getQueueMessageCount(queueName)).isEqualTo(1)
 *         waitForQueueEmpty(queueName)
 *     }
 * }
 * </pre>
 *
 * @see InfraAdapterTestTemplate
 */
abstract class PgmqTestSupport {

    protected val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    // ========================================
    // Circuit Breaker Utilities
    // ========================================

    /**
     * PGMQ Circuit Breaker 상태 초기화
     *
     * <p>테스트 간 Circuit Breaker 상태를 리셋하여 독립성 보장.</p>
     *
     * <h3>Circuit Breaker 이름</h3>
     * <p>기본값: "pgmq" ({@link maple.expectation.infrastructure.pgmq.PgmqConfig#pgmqCircuitBreaker})</p>
     *
     * @param circuitBreakerName Circuit Breaker 이름 (기본값: "pgmq")
     */
    protected fun resetPgmqCircuitBreaker(circuitBreakerName: String = "pgmq") {
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(circuitBreakerName)
        circuitBreaker.reset()
        log.debug("[PgmqTestSupport] CircuitBreaker reset: $circuitBreakerName")
    }

    // ========================================
    // Queue Query Utilities
    // ========================================

    /**
     * 큐 메시지 수 조회
     *
     * <p>PGMQ 큐 테이블({@code pgmq.q_{queueName}})에서 직접 메시지 수를 카운트.</p>
     *
     * @param queueName 큐 이름
     * @return 메시지 수 (큐가 없으면 0 반환)
     */
    protected fun getQueueMessageCount(queueName: String): Long = try {
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$queueName",
            Long::class.java,
        ) ?: 0L
        log.debug("[PgmqTestSupport] Queue '$queueName' message count: $count")
        count
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to get message count for queue '$queueName': ${e.message}")
        0L // 큐 테이블이 없으면 0 반환
    }

    /**
     * 큐 메시지 존재 여부 확인
     *
     * @param queueName 큐 이름
     * @return 메시지가 있으면 true, 없으면 false
     */
    protected fun hasMessages(queueName: String): Boolean = getQueueMessageCount(queueName) > 0

    // ========================================
    // Queue Purge Utilities
    // ========================================

    /**
     * 큐 전체 비우기
     *
     * <p>PGMQ 큐 테이블({@code pgmq.q_{queueName}})에서 모든 메시지 삭제.</p>
     *
     * <h3>구현 방식</h3>
     * <p>{@code pgmq.purge()} 함수 대신 직접 {@code DELETE} 사용.</p>
     * <ul>
     *   <li>이유: purge는 한 번에 하나의 메시지만 삭제</li>
     *   <li>DELETE: 전체 삭제를 원자적으로 수행</li>
     * </ul>
     *
     * @param queueName 큐 이름
     */
    protected fun purgeQueue(queueName: String) {
        try {
            jdbcTemplate.execute("DELETE FROM pgmq.q_$queueName")
            log.debug("[PgmqTestSupport] Queue purged: $queueName")
        } catch (e: Exception) {
            log.warn("[PgmqTestSupport] Failed to purge queue '$queueName': ${e.message}")
            // 큐가 없으면 무시 (테스트 격리 유지)
        }
    }

    // ========================================
    // Wait Utilities
    // ========================================

    /**
     * 큐가 빌 때까지 대기
     *
     * <p>메시지가 모두 소비될 때까지 폴링하며 대기.</p>
     *
     * <h3>대기 조건</h3>
     * <ul>
     *   <li>기본 타임아웃: 10초</li>
     *   <li>폴링 간격: 100ms</li>
     * </ul>
     *
     * @param queueName 큐 이름
     * @param timeoutSeconds 타임아웃 (초 단위, 기본값: 10)
     * @throws org.awaitility.core.ConditionTimeoutException 타임아웃 발생 시
     */
    protected fun waitForQueueEmpty(
        queueName: String,
        timeoutSeconds: Long = 10,
    ) {
        await().atMost(Duration.ofSeconds(timeoutSeconds))
            .until { !hasMessages(queueName) }
        log.debug("[PgmqTestSupport] Queue is now empty: $queueName")
    }

    /**
     * 큐에 메시지가 존재할 때까지 대기
     *
     * @param queueName 큐 이름
     * @param timeoutSeconds 타임아웃 (초 단위, 기본값: 10)
     * @throws org.awaitility.core.ConditionTimeoutException 타임아웃 발생 시
     */
    protected fun waitForMessages(
        queueName: String,
        timeoutSeconds: Long = 10,
    ) {
        await().atMost(Duration.ofSeconds(timeoutSeconds))
            .until { hasMessages(queueName) }
        log.debug("[PgmqTestSupport] Messages detected in queue: $queueName")
    }

    /**
     * 큐 메시지 수가 특정 값이 될 때까지 대기
     *
     * @param queueName 큐 이름
     * @param expectedCount 기대 메시지 수
     * @param timeoutSeconds 타임아웃 (초 단위, 기본값: 10)
     * @throws org.awaitility.core.ConditionTimeoutException 타임아웃 발생 시
     */
    protected fun waitForMessageCount(
        queueName: String,
        expectedCount: Long,
        timeoutSeconds: Long = 10,
    ) {
        await().atMost(Duration.ofSeconds(timeoutSeconds))
            .until { getQueueMessageCount(queueName) == expectedCount }
        log.debug("[PgmqTestSupport] Queue '$queueName' message count reached: $expectedCount")
    }

    // ========================================
    // Helper Utilities (Optional)
    // ========================================

    /**
     * 큐 존재 여부 확인
     *
     * @param queueName 큐 이름
     * @return 큐가 존재하면 true, 없으면 false
     */
    protected fun queueExists(queueName: String): Boolean = try {
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT FROM pgmq.q_$queueName LIMIT 1)",
            Boolean::class.java,
        ) ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * 큐 생성
     *
     * @param queueName 큐 이름
     * @return 생성 성공 여부 (이미 존재하면 false)
     */
    protected fun createQueue(queueName: String): Boolean = try {
        jdbcTemplate.execute("SELECT pgmq.create('$queueName')")
        log.debug("[PgmqTestSupport] Queue created: $queueName")
        true
    } catch (e: Exception) {
        log.debug("[PgmqTestSupport] Queue already exists or creation failed: $queueName")
        false
    }

    /**
     * 큐 삭제
     *
     * @param queueName 큐 이름
     * @return 삭제 성공 여부
     */
    protected fun dropQueue(queueName: String): Boolean = try {
        jdbcTemplate.execute("SELECT pgmq.drop('$queueName')")
        log.debug("[PgmqTestSupport] Queue dropped: $queueName")
        true
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to drop queue '$queueName': ${e.message}")
        false
    }

    /**
     * 큐와 아카이브 테이블 모두 삭제
     *
     * <p>완전한 정리가 필요할 때 사용.</p>
     *
     * @param queueName 큐 이름
     * @return 삭제 성공 여부
     */
    protected fun dropQueueWithArchive(queueName: String): Boolean = try {
        // 아카이브 테이블 삭제
        jdbcTemplate.execute("DROP TABLE IF EXISTS pgmq.a_$queueName")
        // 큐 삭제
        jdbcTemplate.execute("SELECT pgmq.drop('$queueName')")
        log.debug("[PgmqTestSupport] Queue and archive dropped: $queueName")
        true
    } catch (e: Exception) {
        log.warn("[PgmqTestSupport] Failed to drop queue with archive '$queueName': ${e.message}")
        false
    }
}
