package maple.expectation.testinfra

import java.time.Instant
import maple.expectation.infrastructure.pgmq.CalculationRequest
import maple.expectation.infrastructure.worker.CalculationWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ActiveProfiles

/**
 * PgmqWorkerTestBase 사용 예제
 *
 * <p>실제 테스트가 아니며, PgmqWorkerTestBase의 사용법을 보여주는 예제 코드
 *
 * <p>DISABLED: Testcontainers/Docker 환경 의존으로 CI에서 불안정
 *
 * <h3>예제 시나리오</h3>
 *
 * @suppress
 */
@Disabled("Testcontainers/Docker 환경 의존 - CI에서 불안정")
@ActiveProfiles("test")
class PgmqWorkerTestBaseExample : PgmqWorkerTestBase() {

    @Autowired
    lateinit var calculationWorker: CalculationWorker

    override val queueName: String
        get() = calculationWorker.queueName

    /**
     * 예제 1: 정상 처리 후 아카이브 검증
     */
    @Test
    fun `예제 - 메시지를 처리하고 아카이브한다`() {
        // Given
        val request = CalculationRequest(
            ocid = "test-ocid",
            userIgn = "test-ign",
            presetNo = 1,
            forceRecalculation = false,
            requestedAt = Instant.now().toString(),
        )
        val messageId = injectMessage(message = request)

        // When - Worker가 메시지를 처리할 때까지 대기
        awaitMessageProcessed(timeoutMs = 5000)

        // Then - 메시지가 아카이브되었는지 검증
        assertMessageArchived(messageId = messageId)
    }

    /**
     * 예제 2: 메시지 상태 직접 확인
     */
    @Test
    fun `예제 - 큐에서 직접 메시지를 읽어 상태를 확인한다`() {
        // Given
        val request = CalculationRequest(
            ocid = "test-ocid-2",
            userIgn = "test-ign-2",
            requestedAt = Instant.now().toString(),
        )
        injectMessage(message = request)

        // When - 큐에서 직접 메시지 읽기
        val messages = readMessagesDirectly(clazz = CalculationRequest::class.java, vtSec = 10)

        // Then
        assertThat(messages).hasSize(1)
        assertThat(messages[0].payload.ocid).isEqualTo("test-ocid-2")
    }

    /**
     * 예제 3: 메시지가 큐/아카이브에서 삭제되었는지 검증
     */
    @Test
    fun `예제 - 최종 실패로 메시지가 삭제된다`() {
        // Given
        val request = CalculationRequest(
            ocid = "test-ocid-3",
            userIgn = "test-ign-3",
            requestedAt = Instant.now().toString(),
        )
        val messageId = injectMessage(message = request)

        // When - Worker가 메시지를 처리하고 실패 (재시도 초과)
        awaitMessageProcessed(timeoutMs = 10000)

        // Then - 메시지가 삭제되었는지 검증 (큐와 아카이브 모두에서 없음)
        assertMessageDeleted(messageId = messageId)
    }
}
