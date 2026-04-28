package maple.expectation.infrastructure.job

import maple.expectation.core.model.job.CalculationJobStatus
import maple.expectation.core.port.out.CalculationJobPort
import maple.expectation.core.port.out.CalculationResultPort
import maple.expectation.core.port.out.OutboxEventPort
import maple.expectation.core.port.out.mq.DomainEventAppender
import maple.expectation.infrastructure.mq.pgmq.topic.OcidResolveTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiRequestTopic
import maple.expectation.infrastructure.mq.pgmq.topic.NexonApiResponseTopic
import maple.expectation.infrastructure.persistence.repository.CalculationSnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.Mock
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CalculationJobServiceTest {

    @Mock lateinit var jobPort: CalculationJobPort
    @Mock lateinit var eventAppender: DomainEventAppender
    @Mock lateinit var resultPort: CalculationResultPort
    @Mock lateinit var outboxPort: OutboxEventPort
    @Mock lateinit var snapshotRepository: CalculationSnapshotRepository
    @Mock lateinit var ocidResolveTopic: OcidResolveTopic
    @Mock lateinit var nexonApiRequestTopic: NexonApiRequestTopic
    @Mock lateinit var nexonApiResponseTopic: NexonApiResponseTopic

    private lateinit var service: CalculationJobService

    @BeforeEach
    fun setUp() {
        service = CalculationJobService(
            jobPort = jobPort,
            eventAppender = eventAppender,
            ocidResolveTopic = ocidResolveTopic,
            nexonApiRequestTopic = nexonApiRequestTopic,
            nexonApiResponseTopic = nexonApiResponseTopic,
            snapshotRepository = snapshotRepository,
            resultPort = resultPort,
            outboxPort = outboxPort
        )
    }

    @Test
    fun `completeCalculationWithResult saves result and creates outbox event`() {
        val jobId = UUID.randomUUID()
        val resultJson = """{"totalExpectedCost":1000000}"""

        whenever(jobPort.transitionStatus(jobId, CalculationJobStatus.CALCULATING, CalculationJobStatus.COMPLETED))
            .thenReturn(true)
        whenever(jobPort.unlock(jobId)).thenReturn(true)
        whenever(outboxPort.insertIfAbsent(eq("CALCULATION_COMPLETED"), eq(jobId), any()))
            .thenReturn(true)

        val result = service.completeCalculationWithResult(
            jobId = jobId,
            resultJson = resultJson,
            characterClass = "hero",
            presetNo = 1,
            characterId = "test-char"
        )

        assertThat(result).isTrue()

        verify(resultPort).save(argThat { r ->
            r.jobId == jobId && r.contentEncoding == "gzip"
        })
        verify(outboxPort).insertIfAbsent(eq("CALCULATION_COMPLETED"), eq(jobId), any())
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
            characterId = "other-char"
        )

        assertThat(result).isFalse()
    }
}
