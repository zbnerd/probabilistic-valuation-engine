package maple.expectation.testinfra.pgmq

import java.time.Instant
import maple.expectation.core.port.inbound.CacheManagerPort
import maple.expectation.core.port.out.LikeBufferStrategy
import maple.expectation.infrastructure.cache.TieredCacheManager
import maple.expectation.infrastructure.lock.PostgresAdvisoryLockStrategy
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate

/**
 * PGMQ 트랜잭션 원자성 검증 테스트 (Phase 0-2)
 *
 * <h3>검증 항목</h3>
 * <ul>
 *   <li>ROLLBACK: TX 롤백 시 메시지 발행도 롤백되어 큐에 메시지가 없어야 함</li>
 *   <li>COMMIT: TX 커밋 시 메시지가 큐에 정상적으로 존재해야 함</li>
 * </ul>
 *
 * @see PgmqClient
 */
@Tag("infra-verification")
@Tag("pgmq")
@TestPropertySource(
    properties = [
        "pgmq.defaultVisibilityTimeout=1",
        "pgmq.defaultBatchSize=3",
        "pgmq.circuitBreaker.slidingWindowSize=5",
        "pgmq.circuitBreaker.failureRateThreshold=50",
        "pgmq.circuitBreaker.waitDurationInOpenStateMs=1000",
        "pgmq.circuitBreaker.permittedNumberOfCallsInHalfOpenState=2",
        "pgmq.transaction-check.enabled=false",
        "cache.l2.enabled=false",
    ],
)
@DisplayName("PGMQ 트랜잭션 원자성 검증")
@Disabled("TODO: Fix Spring context - same cascading missing beans issue as PgmqClientIntegrationTest")
class PgmqTransactionAtomicityTest : IntegrationTestBase() {

    @MockBean
    lateinit var tieredCacheManager: TieredCacheManager

    @MockBean
    lateinit var cacheManagerPort: CacheManagerPort

    @MockBean
    lateinit var likeBufferStrategy: LikeBufferStrategy

    @MockBean
    lateinit var postgresAdvisoryLockStrategy: PostgresAdvisoryLockStrategy

    @Autowired
    private lateinit var pgmqClient: PgmqClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var pgmqTestSupport: PgmqTestSupport

    private val testQueue = "tx_atomicity_test_queue"

    @BeforeEach
    fun setUp() {
        pgmqTestSupport = PgmqTestSupport(jdbcTemplate)
        pgmqTestSupport.createQueue(testQueue)
    }

    @AfterEach
    fun tearDown() {
        pgmqTestSupport.purgeQueue(testQueue)
    }

    @Test
    @DisplayName("TX ROLLBACK - 롤백 시 메시지가 큐에 남지 않아야 함")
    fun `rollback 시 메시지가 큐에서 사라진다`() {
        val request = CalculationRequest(
            ocid = "rollback-test",
            userIgn = "test-user",
            requestedAt = Instant.now().toString(),
        )

        // When: TX 내에서 send 후 롤백
        transactionTemplate.execute { status ->
            pgmqClient.send(testQueue, request)
            status.setRollbackOnly()
            null
        }

        // Then: 롤백되었으므로 큐에 메시지가 없어야 함
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$testQueue",
            Long::class.java,
        )
        assertThat(count).isEqualTo(0)
    }

    @Test
    @DisplayName("TX COMMIT - 커밋 시 메시지가 큐에 존재해야 함")
    fun `commit 시 메시지가 큐에 남는다`() {
        val request = CalculationRequest(
            ocid = "commit-test",
            userIgn = "test-user",
            requestedAt = Instant.now().toString(),
        )

        // When: TX 내에서 send 후 정상 커밋
        transactionTemplate.execute {
            pgmqClient.send(testQueue, request)
        }

        // Then: 커밋되었으므로 큐에 메시지가 존재해야 함
        val count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pgmq.q_$testQueue",
            Long::class.java,
        )
        assertThat(count).isEqualTo(1)
    }
}
