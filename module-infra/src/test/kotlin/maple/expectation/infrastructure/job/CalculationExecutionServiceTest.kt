package maple.expectation.infrastructure.job

import java.util.UUID
import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.pgmq.PgmqClient
import maple.expectation.infrastructure.queue.QueueNames
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class CalculationExecutionServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort

    @Mock lateinit var eventAppender: DomainEventAppender

    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    @Mock lateinit var resultPort: CalculationResultPort

    @Mock lateinit var pgmqClient: PgmqClient

    private lateinit var service: CalculationExecutionService

    @BeforeEach
    fun setUp() {
        service = CalculationExecutionService(
            jobPort = jobPort,
            eventAppender = eventAppender,
            nexonApiResponseTopic = nexonApiResponseTopic,
            resultPort = resultPort,
            pgmqClient = pgmqClient,
        )
    }

    @Test
    fun `completeCalculationWithResult saves result and sends PGMQ message`() {
        val jobId = UUID.randomUUID()
        val resultJson = """{"totalExpectedCost":1000000}"""

        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED))
            .thenReturn(true)
        whenever(jobPort.unlock(jobId)).thenReturn(true)

        val result = service.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = "hero",
            presetNo = 1,
            characterId = "test-char",
        )

        assertThat(result).isTrue()

        verify(resultPort).save(
            argThat { r ->
                r.jobId == jobId && r.contentEncoding == "gzip"
            },
        )
        verify(pgmqClient).send(eq(QueueNames.RESULT_READY), any())
    }

    @Test
    fun `completeCalculationWithResult returns false when transition fails`() {
        val jobId = UUID.randomUUID()

        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED))
            .thenReturn(false)

        val result = service.completeCalculationWithResult(
            jobId = jobId,
            resultJson = """{"data":1}""",
            characterClass = "paladin",
            presetNo = 2,
            characterId = "other-char",
        )

        assertThat(result).isFalse()
    }
}
